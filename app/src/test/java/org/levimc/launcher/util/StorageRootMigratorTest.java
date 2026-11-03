package org.levimc.launcher.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

public class StorageRootMigratorTest {
    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    private static File write(File dir, String relPath, String content) throws IOException {
        File file = new File(dir, relPath);
        file.getParentFile().mkdirs();
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(content.getBytes(StandardCharsets.UTF_8));
        }
        return file;
    }

    private static String read(File file) throws IOException {
        return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
    }

    private static boolean move(File from, File to) {
        return StorageRootMigrator.moveRoot(from, to, null);
    }

    @Test
    public void moveRootRenamesIntoEmptyTarget() throws IOException {
        File from = temporaryFolder.newFolder("from");
        File to = temporaryFolder.newFolder("to");
        write(from, "minecraft/games/com.mojang/level.dat", "world");
        write(from, "backups/worlds/backup.zip", "zip");

        assertTrue(move(from, to));

        assertEquals("world", read(new File(to, "minecraft/games/com.mojang/level.dat")));
        assertEquals("zip", read(new File(to, "backups/worlds/backup.zip")));
        assertTrue(StorageRootMigrator.hasUserData(to));
    }

    @Test
    public void moveRootLeavesNonWhitelistedEntries() throws IOException {
        File from = temporaryFolder.newFolder("from");
        File to = temporaryFolder.newFolder("to");
        write(from, "minecraft/level.dat", "data");
        write(from, "xal/tokens.bin", "secret");
        write(from, "personalization/bg.png", "img");
        write(from, "games/com.mojang/level.dat", "legacy");

        assertTrue(move(from, to));

        assertEquals("data", read(new File(to, "minecraft/level.dat")));
        assertEquals("secret", read(new File(from, "xal/tokens.bin")));
        assertEquals("img", read(new File(from, "personalization/bg.png")));
        assertEquals("legacy", read(new File(from, "games/com.mojang/level.dat")));
    }

    @Test
    public void moveRootMergesIntoExistingTargetDir() throws IOException {
        File from = temporaryFolder.newFolder("from");
        File to = temporaryFolder.newFolder("to");
        write(from, "minecraft/level.dat", "data");
        write(to, "minecraft/extra.txt", "keep");

        assertTrue(move(from, to));

        assertEquals("data", read(new File(to, "minecraft/level.dat")));
        assertEquals("keep", read(new File(to, "minecraft/extra.txt")));
        assertFalse(new File(from, "minecraft").exists());
    }

    @Test
    public void moveRootFailureKeepsSourceAndCleansTarget() throws IOException {
        File from = temporaryFolder.newFolder("from");
        File to = temporaryFolder.newFolder("to");
        write(from, "minecraft/level.dat", "data");
        write(from, "backups/a.zip", "z");
        write(to, "minecraft", "occupied");

        assertFalse(move(from, to));

        assertEquals("data", read(new File(from, "minecraft/level.dat")));
        assertEquals("z", read(new File(from, "backups/a.zip")));
        assertFalse(StorageRootMigrator.hasUserData(to));
    }

    @Test
    public void hasUserDataIgnoresEmptyRoots() throws IOException {
        File root = temporaryFolder.newFolder("root");
        new File(root, "minecraft").mkdirs();

        assertFalse(StorageRootMigrator.hasUserData(root));
    }
}
