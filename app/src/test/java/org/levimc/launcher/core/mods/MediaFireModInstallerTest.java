package org.levimc.launcher.core.mods;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

public class MediaFireModInstallerTest {

    @Test
    public void normalizeLinkAcceptsMediaFireLinks() {
        assertEquals("https://www.mediafire.com/file/abc/mod.zip/file",
                MediaFireModInstaller.normalizeLink("  www.mediafire.com/file/abc/mod.zip/file "));
        assertEquals("https://www.mediafire.com/file/abc/mod.zip/file",
                MediaFireModInstaller.normalizeLink("http://www.mediafire.com/file/abc/mod.zip/file"));
        assertEquals("https://app.mediafire.com/abc",
                MediaFireModInstaller.normalizeLink("https://app.mediafire.com/abc"));
        assertEquals("https://download2434.mediafire.com/abc/mod.zip",
                MediaFireModInstaller.normalizeLink("https://download2434.mediafire.com/abc/mod.zip"));
    }

    @Test
    public void normalizeLinkRejectsNonMediaFireHosts() {
        assertNull(MediaFireModInstaller.normalizeLink(null));
        assertNull(MediaFireModInstaller.normalizeLink("   "));
        assertNull(MediaFireModInstaller.normalizeLink("https://example.com/file/abc/file"));
        assertNull(MediaFireModInstaller.normalizeLink("https://fakemediafire.com/file/abc/file"));
        assertNull(MediaFireModInstaller.normalizeLink("https://www.mediafire.com.evil.com/file/abc/file"));
        assertNull(MediaFireModInstaller.normalizeLink("not a link"));
    }

    @Test
    public void fileNameFromUrlDecodesMediaFireNames() {
        assertEquals("no rain copanhagen brazilian (1).apk",
                MediaFireModInstaller.fileNameFromUrl(
                        "https://download2434.mediafire.com/abc/55rp3j84sglniwl/no+rain+copanhagen+brazilian+%281%29.apk"));
        assertEquals("mod.zip",
                MediaFireModInstaller.fileNameFromUrl("https://download2434.mediafire.com/abc/mod.zip"));
    }
}
