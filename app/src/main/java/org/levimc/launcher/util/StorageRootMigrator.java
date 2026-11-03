package org.levimc.launcher.util;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public final class StorageRootMigrator {

    private static final int BUFFER_SIZE = 64 * 1024;
    private static final String BACKUPS_DIR = "backups";
    private static final String CRASH_LOGS_DIR = "crash_logs";
    private static final String RESOURCES_DIR = "resources";
    private static final String NO_MEDIA_FILE = ".nomedia";

    private StorageRootMigrator() {
    }

    public static boolean hasUserData(File appRoot) {
        return isNonEmptyDir(new File(appRoot, LauncherStorage.MINECRAFT_DIR))
                || isNonEmptyDir(new File(appRoot, BACKUPS_DIR))
                || isNonEmptyDir(new File(appRoot, CRASH_LOGS_DIR))
                || isNonEmptyDir(new File(appRoot, RESOURCES_DIR));
    }

    public static boolean moveRoot(File from, File to, Runnable onDataVerified) {
        if (from == null || to == null
                || !from.isDirectory()
                || from.getAbsolutePath().equals(to.getAbsolutePath())) {
            return false;
        }
        if (!LauncherStorage.ensureDir(to)) {
            return false;
        }
        File[] children = from.listFiles();
        if (children == null) {
            return false;
        }
        Arrays.sort(children);
        List<File> renamedSources = new ArrayList<>();
        List<File> renamedTargets = new ArrayList<>();
        List<File> copiedSources = new ArrayList<>();
        List<File> createdTargets = new ArrayList<>();
        for (File child : children) {
            if (!isMigratableEntry(child.getName())) {
                continue;
            }
            File target = new File(to, child.getName());
            boolean targetExisted = target.exists();
            if (child.renameTo(target)) {
                renamedSources.add(child);
                renamedTargets.add(target);
            } else if (copyRecursive(child, target)) {
                copiedSources.add(child);
                if (!targetExisted) {
                    createdTargets.add(target);
                }
            } else {
                rollbackRenames(renamedSources, renamedTargets);
                deleteCreatedTargets(createdTargets);
                return false;
            }
        }
        if (onDataVerified != null) {
            onDataVerified.run();
        }
        for (File copied : copiedSources) {
            deleteRecursively(copied);
        }
        return true;
    }

    private static boolean isMigratableEntry(String name) {
        return LauncherStorage.MINECRAFT_DIR.equals(name)
                || BACKUPS_DIR.equals(name)
                || CRASH_LOGS_DIR.equals(name)
                || RESOURCES_DIR.equals(name)
                || NO_MEDIA_FILE.equals(name);
    }

    private static void rollbackRenames(List<File> sources, List<File> targets) {
        for (int i = targets.size() - 1; i >= 0; i--) {
            File target = targets.get(i);
            File source = sources.get(i);
            if (!target.renameTo(source)) {
                copyRecursive(target, source);
                deleteRecursively(target);
            }
        }
    }

    private static void deleteCreatedTargets(List<File> targets) {
        for (File target : targets) {
            deleteRecursively(target);
        }
    }

    private static boolean copyRecursive(File src, File dst) {
        if (src.isDirectory()) {
            if (!LauncherStorage.ensureDir(dst)) {
                return false;
            }
            File[] children = src.listFiles();
            if (children == null) {
                return false;
            }
            for (File child : children) {
                if (!copyRecursive(child, new File(dst, child.getName()))) {
                    return false;
                }
            }
            return true;
        }
        if (src.isFile()) {
            return copyFile(src, dst);
        }
        return true;
    }

    private static boolean copyFile(File src, File dst) {
        File parent = dst.getParentFile();
        if (parent != null && !LauncherStorage.ensureDir(parent)) {
            return false;
        }
        try (InputStream in = new FileInputStream(src);
             OutputStream out = new FileOutputStream(dst)) {
            byte[] buffer = new byte[BUFFER_SIZE];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
        } catch (IOException e) {
            dst.delete();
            return false;
        }
        return dst.length() == src.length();
    }

    private static void deleteRecursively(File file) {
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    deleteRecursively(child);
                }
            }
        }
        file.delete();
    }

    private static boolean isNonEmptyDir(File dir) {
        File[] children = dir.isDirectory() ? dir.listFiles() : null;
        return children != null && children.length > 0;
    }
}
