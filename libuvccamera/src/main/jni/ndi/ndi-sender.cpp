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
        send_create_struct.clock_video = false;  // We manage video timing ourselves
        
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
        videoFrame.frame_format_type = NDIlib_frame_format_type_progressive;
        videoFrame.p_data = static_cast<uint8_t*>(bufferPtr);
        videoFrame.line_stride_in_bytes = width * 4; // Assume RGBA
        videoFrame.FourCC = NDIlib_FourCC_type_RGBA;
        videoFrame.frame_rate_N = 30000;
        videoFrame.frame_rate_D = 1001;  // 29.97 fps typical
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
        videoFrame.frame_format_type = NDIlib_frame_format_type_progressive;
        videoFrame.p_data = reinterpret_cast<uint8_t*>(dataPtr);
        videoFrame.line_stride_in_bytes = width * 2; // YUYV is 2 bytes per pixel
        videoFrame.FourCC = NDIlib_FourCC_type_UYVY;
        videoFrame.frame_rate_N = 30000;
        videoFrame.frame_rate_D = 1001;
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
        videoFrame.frame_format_type = NDIlib_frame_format_type_progressive;
        videoFrame.p_data = reinterpret_cast<uint8_t*>(dataPtr);
        // NV12: Y plane = width * height, UV plane = width * height/2 (packed)
        // Line stride is width for Y, width for UV (but UV is interleaved with 2x horizontal stride)
        videoFrame.line_stride_in_bytes = width;
        videoFrame.FourCC = NDIlib_FourCC_type_NV12;
        videoFrame.frame_rate_N = 30000;
        videoFrame.frame_rate_D = 1001;
        videoFrame.p_metadata = nullptr;

        // Send the frame (async)
        NDIlib_send_send_video_v2(sender, &videoFrame);

        // Release array
        env->ReleaseByteArrayElements(jData, dataPtr, JNI_ABORT);
    }

    /**
     * Query the tally state from a sender.  Returns bit mask: bit0=program, bit1=preview.
     */
    JNIEXPORT jint JNICALL
    Java_com_serenegiant_ndi_NdiSender_nSendGetTally(JNIEnv* env, jclass jClazz, jlong pSend) {
        auto sender = reinterpret_cast<NDIlib_send_instance_t>(pSend);
        if (sender == nullptr) {
            return 0;
        }
        NDIlib_tally_t tally;
        // timeout=0 polls immediately; the return value indicates whether the state
        // has changed since the last call, which we ignore here because we want the
        // current state every time.
        bool ok = NDIlib_send_get_tally(sender, &tally, 0);
        (void)ok; // we don't care about change flag
        int mask = 0;
        if (tally.on_program) mask |= 1;
        if (tally.on_preview) mask |= 2;
        return mask;
    }

} // extern "C"
