/*
 * Color Format Conversion Utilities
 * Converts between common video formats (YUYV, NV12, RGBA)
 */

#include "ndi-wrapper.h"
#include <cstring>
#include <cmath>

/**
 * YUV to RGB conversion helper
 */
static inline void yuv_to_rgba(uint8_t y, uint8_t u, uint8_t v, uint8_t* rgba) {
    int c = y - 16;
    int d = u - 128;
    int e = v - 128;

    int r = (298 * c + 409 * e + 128) >> 8;
    int g = (298 * c - 100 * d - 208 * e + 128) >> 8;
    int b = (298 * c + 516 * d + 128) >> 8;

    rgba[0] = (uint8_t)std::max(0, std::min(255, r)); // R
    rgba[1] = (uint8_t)std::max(0, std::min(255, g)); // G
    rgba[2] = (uint8_t)std::max(0, std::min(255, b)); // B
    rgba[3] = 255; // A
}

extern "C" {

    /**
     * Convert YUYV to RGBA
     * YUYV format: Y0 U Y1 V Y2 U Y3 V ...
     */
    JNIEXPORT void JNICALL
    Java_com_serenegiant_ndi_NdiSender_nConvertYuyvToRgba(JNIEnv* env, jclass jClazz,
                                                           jbyteArray jYuyv, jobject jRgba,
                                                           jint width, jint height) {
        jbyte* yuyv = env->GetByteArrayElements(jYuyv, nullptr);
        uint8_t* rgba = static_cast<uint8_t*>(env->GetDirectBufferAddress(jRgba));

        if (yuyv == nullptr || rgba == nullptr) {
            LOGE("Invalid buffer addresses in YUYV to RGBA conversion");
            if (yuyv != nullptr) env->ReleaseByteArrayElements(jYuyv, yuyv, JNI_ABORT);
            return;
        }

        int rgbaIndex = 0;
        for (int i = 0; i < width * height * 2; i += 4) {
            uint8_t y0 = (uint8_t)yuyv[i];
            uint8_t u = (uint8_t)yuyv[i + 1];
            uint8_t y1 = (uint8_t)yuyv[i + 2];
            uint8_t v = (uint8_t)yuyv[i + 3];

            yuv_to_rgba(y0, u, v, &rgba[rgbaIndex]);
            rgbaIndex += 4;

            yuv_to_rgba(y1, u, v, &rgba[rgbaIndex]);
            rgbaIndex += 4;
        }

        env->ReleaseByteArrayElements(jYuyv, yuyv, JNI_ABORT);
    }

    /**
     * Convert NV12 to RGBA
     * NV12 format: Y plane (width * height), then UV plane (width * height / 2 interleaved)
     */
    JNIEXPORT void JNICALL
    Java_com_serenegiant_ndi_NdiSender_nConvertNv12ToRgba(JNIEnv* env, jclass jClazz,
                                                           jbyteArray jNv12, jobject jRgba,
                                                           jint width, jint height) {
        jbyte* nv12 = env->GetByteArrayElements(jNv12, nullptr);
        uint8_t* rgba = static_cast<uint8_t*>(env->GetDirectBufferAddress(jRgba));

        if (nv12 == nullptr || rgba == nullptr) {
            LOGE("Invalid buffer addresses in NV12 to RGBA conversion");
            if (nv12 != nullptr) env->ReleaseByteArrayElements(jNv12, nv12, JNI_ABORT);
            return;
        }

        int ySize = width * height;
        uint8_t* yPlane = reinterpret_cast<uint8_t*>(nv12);
        uint8_t* uvPlane = reinterpret_cast<uint8_t*>(nv12) + ySize;

        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int yIdx = y * width + x;
                int uvIdx = (y / 2) * width + (x & ~1);

                uint8_t yVal = yPlane[yIdx];
                uint8_t u = uvPlane[uvIdx];
                uint8_t v = uvPlane[uvIdx + 1];

                int rgbaIdx = (y * width + x) * 4;
                yuv_to_rgba(yVal, u, v, &rgba[rgbaIdx]);
            }
        }

        env->ReleaseByteArrayElements(jNv12, nv12, JNI_ABORT);
    }

} // extern "C"
