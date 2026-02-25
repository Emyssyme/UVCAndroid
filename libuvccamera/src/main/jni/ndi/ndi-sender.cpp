/*
 * NDI Sender Implementation - JNI Bindings for Video Transmission
 * Based on https://github.com/WalkerKnapp/devolay (Apache 2.0)
 */

#include "ndi-wrapper.h"
#include <memory>
#include <cstring>

extern "C" {

    /**
     * Create a new NDI sender instance
     */
    JNIEXPORT jlong JNICALL
    Java_com_serenegiant_ndi_NdiSender_nSendCreate(JNIEnv* env, jclass jClazz, jstring jSourceName) {
        const char* sourceName = env->GetStringUTFChars(jSourceName, nullptr);
        
        // Create sender creation structure
        NDIlib_send_create_t send_create_struct;
        std::memset(&send_create_struct, 0, sizeof(send_create_struct));
        
        send_create_struct.p_ndi_name = sourceName;
        send_create_struct.clock_audio = false;  // We manage audio timing ourselves
        send_create_struct.clock_video = true;   // Let NDI SDK handle video frame pacing for smooth playback
        
        // Create the sender
        NDIlib_send_instance_t sender = NDIlib_send_create(&send_create_struct);
        
        env->ReleaseStringUTFChars(jSourceName, sourceName);
        
        if (sender == nullptr) {
            LOGE("Failed to create NDI sender");
            return 0;
        }
        
        LOGI("NDI sender created successfully: %s", sourceName);
        return reinterpret_cast<jlong>(sender);
    }

    /**
     * Destroy an NDI sender instance
     */
    JNIEXPORT void JNICALL
    Java_com_serenegiant_ndi_NdiSender_nSendDestroy(JNIEnv* env, jclass jClazz, jlong pSend) {
        auto sender = reinterpret_cast<NDIlib_send_instance_t>(pSend);
        if (sender != nullptr) {
            NDIlib_send_destroy(sender);
            LOGI("NDI sender destroyed");
        }
    }

    /**
     * Send video frame with ByteBuffer (generic method)
     */
    JNIEXPORT void JNICALL
    Java_com_serenegiant_ndi_NdiSender_nSendVideo(JNIEnv* env, jclass jClazz, 
                                                   jlong pSend, jint width, jint height, 
                                                   jobject jBuffer) {
        auto sender = reinterpret_cast<NDIlib_send_instance_t>(pSend);
        if (sender == nullptr) {
            LOGE("NDI sender pointer is null");
            return;
        }

        // Get buffer pointer
        void* bufferPtr = env->GetDirectBufferAddress(jBuffer);
        if (bufferPtr == nullptr) {
            LOGE("Failed to get buffer address");
            return;
        }

        // Create video frame structure
        NDIlib_video_frame_v2_t videoFrame;
        std::memset(&videoFrame, 0, sizeof(videoFrame));
        
        videoFrame.xres = width;
        videoFrame.yres = height;
        videoFrame.picture_aspect_ratio = static_cast<float>(width) / static_cast<float>(height);
        videoFrame.p_data = static_cast<uint8_t*>(bufferPtr);
        videoFrame.line_stride_in_bytes = width * 4; // Assume RGBA
        videoFrame.FourCC = NDIlib_FourCC_type_RGBA;
        videoFrame.frame_rate_N = 30000;
        videoFrame.frame_rate_D = 1000;
        videoFrame.p_metadata = nullptr;

        // Send the frame (async)
        NDIlib_send_send_video_v2(sender, &videoFrame);
    }

    /**
     * Send video frame in YUYV format
     */
    JNIEXPORT void JNICALL
    Java_com_serenegiant_ndi_NdiSender_nSendVideoYUYV(JNIEnv* env, jclass jClazz,
                                                       jlong pSend, jint width, jint height,
                                                       jbyteArray jData) {
        auto sender = reinterpret_cast<NDIlib_send_instance_t>(pSend);
        if (sender == nullptr) {
            LOGE("NDI sender pointer is null");
            return;
        }

        // Get array pointer
        jbyte* dataPtr = env->GetByteArrayElements(jData, nullptr);
        if (dataPtr == nullptr) {
            LOGE("Failed to get array elements");
            return;
        }

        // Create video frame structure
        NDIlib_video_frame_v2_t videoFrame;
        std::memset(&videoFrame, 0, sizeof(videoFrame));
        
        videoFrame.xres = width;
        videoFrame.yres = height;
        videoFrame.picture_aspect_ratio = static_cast<float>(width) / static_cast<float>(height);
        videoFrame.p_data = reinterpret_cast<uint8_t*>(dataPtr);
        videoFrame.line_stride_in_bytes = width * 2; // YUYV is 2 bytes per pixel
        videoFrame.FourCC = NDIlib_FourCC_type_UYVY;
        videoFrame.frame_rate_N = 30000;
        videoFrame.frame_rate_D = 1000;
        videoFrame.p_metadata = nullptr;

        // Send the frame (async)
        NDIlib_send_send_video_v2(sender, &videoFrame);

        // Release array
        env->ReleaseByteArrayElements(jData, dataPtr, JNI_ABORT);
    }

    /**
     * Send video frame in NV12 format
     */
    JNIEXPORT void JNICALL
    Java_com_serenegiant_ndi_NdiSender_nSendVideoNV12(JNIEnv* env, jclass jClazz,
                                                       jlong pSend, jint width, jint height,
                                                       jbyteArray jData) {
        auto sender = reinterpret_cast<NDIlib_send_instance_t>(pSend);
        if (sender == nullptr) {
            LOGE("NDI sender pointer is null");
            return;
        }

        // Get array pointer
        jbyte* dataPtr = env->GetByteArrayElements(jData, nullptr);
        if (dataPtr == nullptr) {
            LOGE("Failed to get array elements");
            return;
        }

        // Create video frame structure
        NDIlib_video_frame_v2_t videoFrame;
        std::memset(&videoFrame, 0, sizeof(videoFrame));
        
        videoFrame.xres = width;
        videoFrame.yres = height;
        videoFrame.picture_aspect_ratio = static_cast<float>(width) / static_cast<float>(height);
        videoFrame.p_data = reinterpret_cast<uint8_t*>(dataPtr);
        // NV12: line stride must be the full row width in bytes (Y plane stride = width)
        // NDI expects stride to match the actual memory layout of the Y plane
        videoFrame.line_stride_in_bytes = width; // correct: 1 byte per pixel for Y plane
        videoFrame.FourCC = NDIlib_FourCC_type_NV12;
        // Use 60000/2000 = 30fps; adjust numerator/denominator for other rates
        videoFrame.frame_rate_N = 60000;
        videoFrame.frame_rate_D = 2000;
        videoFrame.p_metadata = nullptr;

        // Send the frame (async)
        NDIlib_send_send_video_v2(sender, &videoFrame);

        // Release array
        env->ReleaseByteArrayElements(jData, dataPtr, JNI_ABORT);
    }

    /**
     * Send video frame in NV12 format with explicit frame rate
     */
    JNIEXPORT void JNICALL
    Java_com_serenegiant_ndi_NdiSender_nSendVideoNV12WithFps(JNIEnv* env, jclass jClazz,
                                                              jlong pSend, jint width, jint height,
                                                              jbyteArray jData, jint fpsN, jint fpsD) {
        auto sender = reinterpret_cast<NDIlib_send_instance_t>(pSend);
        if (sender == nullptr) return;

        jbyte* dataPtr = env->GetByteArrayElements(jData, nullptr);
        if (dataPtr == nullptr) return;

        NDIlib_video_frame_v2_t videoFrame;
        std::memset(&videoFrame, 0, sizeof(videoFrame));

        videoFrame.xres = width;
        videoFrame.yres = height;
        videoFrame.picture_aspect_ratio = static_cast<float>(width) / static_cast<float>(height);
        videoFrame.p_data = reinterpret_cast<uint8_t*>(dataPtr);
        videoFrame.line_stride_in_bytes = width;
        videoFrame.FourCC = NDIlib_FourCC_type_NV12;
        videoFrame.frame_rate_N = fpsN;
        videoFrame.frame_rate_D = fpsD;
        videoFrame.p_metadata = nullptr;

        NDIlib_send_send_video_v2(sender, &videoFrame);
        env->ReleaseByteArrayElements(jData, dataPtr, JNI_ABORT);
    }

} // extern "C"
