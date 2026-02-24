/*
 * NDI Sender for UVC Camera - Based on https://github.com/WalkerKnapp/devolay (Apache 2.0)
 * Integrated into UVCAndroid library
 */

package com.serenegiant.ndi;

/**
 * Main NDI initialization class. Call initialize() before using any NDI functionality.
 */
public class Ndi {
    static {
        System.loadLibrary("uvc-ndi-wrapper");
    }

    /**
     * Initialize NDI library. Must be called once at application startup.
     * @throws RuntimeException if NDI initialization fails
     */
    public static void initialize() {
        boolean initialized = nInitializeNDI();
        if (!initialized) {
            throw new RuntimeException("Could not initialize NDI.");
        }
    }

    /**
     * Get the NDI SDK version string
     * @return NDI version string
     */
    public static String getNdiVersion() {
        return nGetNdiVersion();
    }

    /**
     * Check if the current CPU is supported by NDI
     * @return true if CPU is supported
     */
    public static boolean isCPUSupported() {
        return nIsSupportedCpu();
    }

    /**
     * Shutdown NDI library - call at application exit
     */
    public static void shutdown() {
        nShutdownNDI();
    }

    // Native methods
    private static native boolean nInitializeNDI();
    private static native void nShutdownNDI();
    private static native String nGetNdiVersion();
    private static native boolean nIsSupportedCpu();
}
