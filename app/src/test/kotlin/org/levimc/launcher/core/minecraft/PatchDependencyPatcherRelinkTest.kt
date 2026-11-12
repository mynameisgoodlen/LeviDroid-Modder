package org.levimc.launcher.core.minecraft

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PatchDependencyPatcherRelinkTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private class ElfLayout(
        val file: File,
        val dynstrOffset: Int,
        val sonameOffset: Int,
        val neededOffsets: List<Int>
    )

    private class ElfBuilder(soname: String, needed: List<String>, tailRefIntoSoname: Boolean) {
        val dynstr = StringBuilder()

        fun intern(value: String): Int {
            val at = dynstr.indexOf(value)
            if (at >= 0) return at
            val offset = dynstr.length
            dynstr.append(value).append('\u0000')
            return offset
        }

        val sonameOffset = intern(soname)
        val neededOffsets = needed.map { intern(it) }
        val symbolOffset = intern("game_symbol")
        val dynstrBytes = dynstr.toString().toByteArray(Charsets.US_ASCII)

        val dynEntryCount = needed.size + 3
        val dynOffset = 52 + 2 * 32
        val dynSize = dynEntryCount * 8
        val dynstrOffset = dynOffset + dynSize
        val dynsymOffset = dynstrOffset + dynstrBytes.size
        val shdrOffset = dynsymOffset + 2 * 16
        val totalSize = shdrOffset + 3 * 40

        fun build(target: File): ElfLayout {
            val buffer = ByteBuffer.allocate(totalSize).order(ByteOrder.LITTLE_ENDIAN)
            buffer.put(byteArrayOf(0x7f, 'E'.code.toByte(), 'L'.code.toByte(), 'F'.code.toByte()))
            buffer.put(1)
            buffer.put(1)
            buffer.put(1)
            buffer.put(ByteArray(9))
            buffer.putShort(3)
            buffer.putShort(40)
            buffer.putInt(1)
            buffer.putInt(0)
            buffer.putInt(52)
            buffer.putInt(shdrOffset)
            buffer.putInt(0)
            buffer.putShort(52)
            buffer.putShort(32)
            buffer.putShort(2)
            buffer.putShort(40)
            buffer.putShort(3)
            buffer.putShort(0)

            buffer.putInt(1)
            buffer.putInt(0)
            buffer.putInt(0)
            buffer.putInt(0)
            buffer.putInt(totalSize)
            buffer.putInt(totalSize)
            buffer.putInt(5)
            buffer.putInt(0x1000)

            buffer.putInt(2)
            buffer.putInt(dynOffset)
            buffer.putInt(dynOffset)
            buffer.putInt(0)
            buffer.putInt(dynSize)
            buffer.putInt(dynSize)
            buffer.putInt(6)
            buffer.putInt(4)

            neededOffsets.forEach { offset ->
                buffer.putInt(1)
                buffer.putInt(offset)
            }
            buffer.putInt(14)
            buffer.putInt(sonameOffset)
            buffer.putInt(5)
            buffer.putInt(dynstrOffset)
            buffer.putInt(0)
            buffer.putInt(0)

            buffer.put(dynstrBytes)

            buffer.putInt(0)
            buffer.putInt(0)
            buffer.putInt(0)
            buffer.putInt(0)
            buffer.put(0)
            buffer.put(0)
            buffer.putShort(0)
            val symName = if (tailRefIntoSoname) sonameOffset + 3 else symbolOffset
            buffer.putInt(symName)
            buffer.putInt(0)
            buffer.putInt(0)
            buffer.put(0x12)
            buffer.put(0)
            buffer.putShort(1)

            repeat(3) { index ->
                if (index == 1) {
                    buffer.putInt(0)
                    buffer.putInt(11)
                    buffer.putInt(2)
                    buffer.putInt(dynsymOffset)
                    buffer.putInt(dynsymOffset)
                    buffer.putInt(2 * 16)
                    buffer.putInt(2)
                    buffer.putInt(1)
                    buffer.putInt(4)
                    buffer.putInt(16)
                } else if (index == 2) {
                    buffer.putInt(0)
                    buffer.putInt(3)
                    buffer.putInt(2)
                    buffer.putInt(dynstrOffset)
                    buffer.putInt(dynstrOffset)
                    buffer.putInt(dynstrBytes.size)
                    buffer.putInt(0)
                    buffer.putInt(0)
                    buffer.putInt(1)
                    buffer.putInt(0)
                } else {
                    buffer.putInt(0)
                    buffer.putInt(0)
                    buffer.putInt(0)
                    buffer.putInt(0)
                    buffer.putInt(0)
                    buffer.putInt(0)
                    buffer.putInt(0)
                    buffer.putInt(0)
                    buffer.putInt(0)
                    buffer.putInt(0)
                }
            }

            target.writeBytes(buffer.array())
            return ElfLayout(target, dynstrOffset, sonameOffset, neededOffsets)
        }
    }

    private fun readCString(file: File, offset: Int): String {
        RandomAccessFile(file, "r").use { raf ->
            raf.seek(offset.toLong())
            val bytes = ArrayList<Byte>()
            while (true) {
                val value = raf.read()
                if (value <= 0) break
                bytes.add(value.toByte())
            }
            return String(bytes.toByteArray(), Charsets.US_ASCII)
        }
    }

    @Test
    fun relinksNeededAndSonameStringsInPlace() {
        val file = temporaryFolder.newFile("libgame.so")
        val layout = ElfBuilder("libmaesdk.so", listOf("libc++_shared.so", "libmaesdk.so"), false).build(file)

        val reason = PatchDependencyPatcher.relinkSonames(
            listOf(file),
            mapOf(
                "libc++_shared.so" to "lcpp_g.so",
                "libmaesdk.so" to "maesdk_g.so"
            )
        )

        assertNull(reason)
        assertEquals("maesdk_g.so", readCString(file, layout.dynstrOffset + layout.sonameOffset))
        assertEquals("lcpp_g.so", readCString(file, layout.dynstrOffset + layout.neededOffsets[0]))
        assertEquals("maesdk_g.so", readCString(file, layout.dynstrOffset + layout.neededOffsets[1]))

        val snapshot = file.readBytes()
        assertNull(
            PatchDependencyPatcher.relinkSonames(
                listOf(file),
                mapOf(
                    "libc++_shared.so" to "lcpp_g.so",
                    "libmaesdk.so" to "maesdk_g.so"
                )
            )
        )
        assertTrue(snapshot.contentEquals(file.readBytes()))
    }

    @Test
    fun abortsWhenDynstrSlotSharesSuffixReference() {
        val file = temporaryFolder.newFile("libshared.so")
        val layout = ElfBuilder("libmaesdk.so", listOf("libmaesdk.so"), true).build(file)

        val reason = PatchDependencyPatcher.relinkSonames(listOf(file), mapOf("libmaesdk.so" to "maesdk_g.so"))

        assertNotNull(reason)
        assertEquals("libmaesdk.so", readCString(file, layout.dynstrOffset + layout.sonameOffset))
        assertEquals("libmaesdk.so", readCString(file, layout.dynstrOffset + layout.neededOffsets[0]))
    }

    @Test
    fun refusesReplacementLongerThanTheSlot() {
        val file = temporaryFolder.newFile("liblong.so")
        ElfBuilder("libmaesdk.so", emptyList(), false).build(file)

        val reason = PatchDependencyPatcher.relinkSonames(listOf(file), mapOf("libmaesdk.so" to "maesdk_levi.so"))

        assertNotNull(reason)
    }
}
