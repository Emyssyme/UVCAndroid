/*
 * NDI JNI Wrapper - Initialization and Support Functions
 * Based on https://github.com/WalkerKnapp/devolay (Apache 2.0)
 */

#include "ndi-wrapper.h"

// Global NDI state
static bool g_ndi_initialized = false;

/**
 * Convert C++ string to Java jstring
 */
jstring stringToJString(JNIEnv* env, const std::string& str) {
    return env->NewStringUTF(str.c_str());
}

/**
 * Convert C++ bool to Java boolean
 */
jboolean boolToJBoolean(bool value) {
    return value ? JNI_TRUE : JNI_FALSE;
}

extern "C" {

    /**
     * Initialize NDI library
     */
    JNIEXPORT jboolean JNICALL
    Java_com_serenegiant_ndi_Ndi_nInitializeNDI(JNIEnv *env, jclass jClazz) {
        if (!g_ndi_initialized) {
            g_ndi_initialized = NDIlib_initialize();
            if (g_ndi_initialized) {
                LOGI("NDI library initialized successfully");
            } else {
                LOGE("Failed to initialize NDI library");
            }
        }
        return boolToJBoolean(g_ndi_initialized);
    }

    /**
     * Shutdown NDI library
     */
    JNIEXPORT void JNICALL
    Java_com_serenegiant_ndi_Ndi_nShutdownNDI(JNIEnv *env, jclass jClazz) {
        if (g_ndi_initialized) {
            NDIlib_destroy();
            g_ndi_initialized = false;
            LOGI("NDI library shutdown successfully");
        }
    }

    /**
     * Get NDI version string
     */
    JNIEXPORT jstring JNICALL
    Java_com_serenegiant_ndi_Ndi_nGetNdiVersion(JNIEnv* env, jclass jClazz) {
        const char* version = NDIlib_version();
        if (version == nullptr) {
            version = "Unknown";
        }
        return stringToJString(env, version);
    }

    /**
     * Check if CPU is supported
     */
    JNIEXPORT jboolean JNICALL
    Java_com_serenegiant_ndi_Ndi_nIsSupportedCpu(JNIEnv *env, jclass jClazz) {
        return boolToJBoolean(NDIlib_is_supported_CPU());
    }

} // extern "C"
