package org.levimc.launcher.core.mods.inbuilt.nativemod;

public class FpsMod {
    private static volatile boolean arm32LibraryLoaded;

    public static boolean init() {
        if (!android.os.Process.is64Bit()) {
            try {
                System.loadLibrary("arm32fps");
                arm32LibraryLoaded = true;
                return nativeInit();
            } catch (UnsatisfiedLinkError error) {
                return false;
            }
        }
        if (!InbuiltModsNative.loadLibrary()) {
            return false;
        }
        return nativeInit();
    }

    public static native boolean nativeInit();
    public static native int nativeGetFps();
    public static native boolean nativeIsInitialized();
}
