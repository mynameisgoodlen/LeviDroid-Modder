package org.levimc.launcher.util;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.nio.file.Files;

import org.junit.Test;

public class NativeImageGuardTest {

    @Test
    public void publishWithoutGxCoreFallsBackToRawLibrary() throws Exception {
        File dir = Files.createTempDirectory("nativetest").toFile();
        File staged = new File(dir, "libc++_shared.so.tmp");
        Files.write(staged.toPath(), new byte[]{1, 2, 3, 4});
        File output = new File(dir, "libc++_shared.so");

        NativeImageGuard.publishStagedImage(staged, output);

        assertTrue(output.isFile());
        assertTrue(output.length() == 4);
        assertFalse(output.canWrite());
        assertTrue(NativeImageGuard.isPrepared(output));
        assertFalse(staged.exists());
    }

    @Test(expected = java.io.IOException.class)
    public void publishRejectsEmptyLibrary() throws Exception {
        File dir = Files.createTempDirectory("nativetest").toFile();
        File staged = new File(dir, "libminecraftpe.so.tmp");
        Files.write(staged.toPath(), new byte[0]);
        File output = new File(dir, "libminecraftpe.so");

        NativeImageGuard.publishStagedImage(staged, output);
    }
}
