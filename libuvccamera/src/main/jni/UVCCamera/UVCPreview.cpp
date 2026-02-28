/*
 * UVCCamera
 * library and sample to access to UVC web camera on non-rooted Android device
 *
 * Copyright (c) 2014-2017 saki t_saki@serenegiant.com
 *
 * File name: UVCPreview.cpp
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 * All files in the folder are under this Apache License, Version 2.0.
 * Files in the jni/libjpeg, jni/libusb, jin/libuvc, jni/rapidjson folder may have a different license, see the respective files.
*/

#include <stdlib.h>
#include <linux/time.h>
#include <unistd.h>

#ifndef LOG_NDEBUG
#define  LOCAL_DEBUG 1
#endif

#include "utilbase.h"
#include "UVCPreview.h"
#include "libuvc_internal.h"
#include "ConvertHelper.h"

#define MAX_FRAME 4
// RGBA_8888/RGBX_8888:4
// RGB_565:2
#define PREVIEW_PIXEL_BYTES 4
#define FRAME_POOL_SZ MAX_FRAME + 2

static inline bool can_passthrough_callback_frame(const int pixel_format, const uvc_frame_t *frame) {
    if (!frame) return false;
    switch (pixel_format) {
        case PIXEL_FORMAT_RAW:
        case PIXEL_FORMAT_YUV:
            return frame->frame_format == UVC_FRAME_FORMAT_YUYV;
        case PIXEL_FORMAT_NV12:
            return frame->frame_format == UVC_FRAME_FORMAT_NV12;
        case PIXEL_FORMAT_NV21:
            return frame->frame_format == UVC_FRAME_FORMAT_NV21;
        case PIXEL_FORMAT_I420:
            return frame->frame_format == UVC_FRAME_FORMAT_I420;
        default:
            return false;
    }
}

static inline size_t min_frame_bytes_for_format(const uvc_frame_t *frame) {
    if (!frame || frame->width <= 0 || frame->height <= 0) return 0;
    const size_t wh = static_cast<size_t>(frame->width) * static_cast<size_t>(frame->height);
    switch (frame->frame_format) {
        case UVC_FRAME_FORMAT_YUYV:
            return wh * 2;
        case UVC_FRAME_FORMAT_NV12:
        case UVC_FRAME_FORMAT_NV21:
        case UVC_FRAME_FORMAT_I420:
            if ((frame->width & 1) || (frame->height & 1)) return 0;
            return (wh * 3) / 2;
        case UVC_FRAME_FORMAT_RGBX:
            return wh * 4;
        case UVC_FRAME_FORMAT_RGB:
        case UVC_FRAME_FORMAT_BGR:
            return wh * 3;
        case UVC_FRAME_FORMAT_MJPEG:
            return 1;
        default:
            return 1;
    }
}

static inline size_t frame_bytes_for_pixel_format(const int pixel_format, const int width, const int height) {
    if (width <= 0 || height <= 0) return 0;
    const size_t wh = static_cast<size_t>(width) * static_cast<size_t>(height);
    switch (pixel_format) {
        case PIXEL_FORMAT_RAW:
        case PIXEL_FORMAT_YUV:
        case PIXEL_FORMAT_RGB565:
            return wh * 2;
        case PIXEL_FORMAT_NV12:
        case PIXEL_FORMAT_NV21:
        case PIXEL_FORMAT_I420:
            if ((width & 1) || (height & 1)) return 0;
            return (wh * 3) / 2;
        case PIXEL_FORMAT_RGB:
        case PIXEL_FORMAT_BGR:
            return wh * 3;
        case PIXEL_FORMAT_RGBX:
            return wh * 4;
        default:
            return wh * 4;
    }
}

static inline const char *uvc_frame_format_name(const enum uvc_frame_format fmt) {
    switch (fmt) {
        case UVC_FRAME_FORMAT_MJPEG: return "MJPEG";
        case UVC_FRAME_FORMAT_YUYV: return "YUYV";
        case UVC_FRAME_FORMAT_NV12: return "NV12";
        case UVC_FRAME_FORMAT_NV21: return "NV21";
        case UVC_FRAME_FORMAT_I420: return "I420";
        case UVC_FRAME_FORMAT_RGBX: return "RGBX";
        case UVC_FRAME_FORMAT_RGB: return "RGB";
        case UVC_FRAME_FORMAT_BGR: return "BGR";
        case UVC_FRAME_FORMAT_UNCOMPRESSED: return "UNCOMPRESSED";
        default: return "OTHER";
    }
}

UVCPreview::UVCPreview(uvc_device_handle_t *devh)
        : mPreviewWindow(NULL),
          mCaptureWindow(NULL),
          mDeviceHandle(devh),
          requestWidth(DEFAULT_PREVIEW_WIDTH),
          requestHeight(DEFAULT_PREVIEW_HEIGHT),
          requestFormatType(DEFAULT_PREVIEW_FORMAT_TYPE),
          requestFps(DEFAULT_PREVIEW_FPS),
          frameWidth(DEFAULT_PREVIEW_WIDTH),
          frameHeight(DEFAULT_PREVIEW_HEIGHT),
          negotiatedFrameFormat(UVC_FRAME_FORMAT_MJPEG),
          frameBytes(DEFAULT_PREVIEW_WIDTH * DEFAULT_PREVIEW_HEIGHT * 2),    // YUYV
          frameFormatType(DEFAULT_PREVIEW_FRAME_TYPE),
          previewBytes(DEFAULT_PREVIEW_WIDTH * DEFAULT_PREVIEW_HEIGHT * PREVIEW_PIXEL_BYTES),
          previewFormat(WINDOW_FORMAT_RGBA_8888),
          mIsRunning(false),
          mIsCapturing(false),
          captureQueu(NULL),
          mFrameCallbackObj(NULL),
          mFrameCallbackFunc(NULL),
          callbackPixelBytes(2),
          preview_thread(0),
          capture_thread(0) {

    ENTER();
    pthread_cond_init(&preview_sync, NULL);
    pthread_mutex_init(&preview_mutex, NULL);
//
    pthread_cond_init(&capture_sync, NULL);
    pthread_mutex_init(&capture_mutex, NULL);
//	
    pthread_mutex_init(&pool_mutex, NULL);
    EXIT();
}

UVCPreview::~UVCPreview() {

    ENTER();
    if (mPreviewWindow)
        ANativeWindow_release(mPreviewWindow);
    mPreviewWindow = NULL;
    if (mCaptureWindow)
        ANativeWindow_release(mCaptureWindow);
    mCaptureWindow = NULL;
    mFrameCallbackObj = NULL;
    iframecallback_fields.onFrame = NULL;
    clearPreviewFrame();
    clearCaptureFrame();
    clear_pool();
    pthread_mutex_destroy(&preview_mutex);
    pthread_cond_destroy(&preview_sync);
    pthread_mutex_destroy(&capture_mutex);
    pthread_cond_destroy(&capture_sync);
    pthread_mutex_destroy(&pool_mutex);
    EXIT();
}

/**
 * get uvc_frame_t from frame pool
 * if pool is empty, create new frame
 * this function does not confirm the frame size
 * and you may need to confirm the size
 */
uvc_frame_t *UVCPreview::get_frame(size_t data_bytes) {
    uvc_frame_t *frame = NULL;
    pthread_mutex_lock(&pool_mutex);
    {
        if (!mFramePool.isEmpty()) {
            frame = mFramePool.last();
        }
    }
    pthread_mutex_unlock(&pool_mutex);
    if UNLIKELY(!frame) {
        LOGI("allocate new frame");
        frame = uvc_allocate_frame(data_bytes);
    } else {
        uvc_ensure_frame_size(frame, data_bytes);
    }
    return frame;
}

void UVCPreview::recycle_frame(uvc_frame_t *frame) {
    pthread_mutex_lock(&pool_mutex);
    if (LIKELY(mFramePool.size() < FRAME_POOL_SZ)) {
        mFramePool.put(frame);
        frame = NULL;
    }
    pthread_mutex_unlock(&pool_mutex);
    if (UNLIKELY(frame)) {
        uvc_free_frame(frame);
    }
}


void UVCPreview::init_pool(size_t data_bytes) {
    ENTER();

    clear_pool();
    pthread_mutex_lock(&pool_mutex);
    {
        for (int i = 0; i < FRAME_POOL_SZ; i++) {
            mFramePool.put(uvc_allocate_frame(data_bytes));
        }
    }
    pthread_mutex_unlock(&pool_mutex);

    EXIT();
}

void UVCPreview::clear_pool() {
    ENTER();

    pthread_mutex_lock(&pool_mutex);
    {
        const int n = mFramePool.size();
        for (int i = 0; i < n; i++) {
            uvc_free_frame(mFramePool[i]);
        }
        mFramePool.clear();
    }
    pthread_mutex_unlock(&pool_mutex);
    EXIT();
}

inline const bool UVCPreview::isRunning() const { return mIsRunning; }

static uvc_frame_format getFrameFormatByType(int frameType) {
    enum uvc_frame_format frame_format;

    switch (frameType) {
        case UVC_VS_FRAME_UNCOMPRESSED:
            frame_format = UVC_FRAME_FORMAT_UNCOMPRESSED;
            break;
        case UVC_VS_FRAME_MJPEG:
            frame_format = UVC_FRAME_FORMAT_MJPEG;
            break;
        case UVC_VS_FRAME_FRAME_BASED:
            frame_format = UVC_FRAME_FORMAT_H264;
            break;
        default:
            frame_format = UVC_FRAME_FORMAT_YUYV;
            break;
    }

    return frame_format;
}

int UVCPreview::setPreviewSize(int width, int height, int frameType, int fps) {
    ENTER();

    int result = 0;

    enum uvc_frame_format frame_format = getFrameFormatByType(frameType);

    requestWidth = width;
    requestHeight = height;
    requestFps = fps;
    requestFormatType = frameType;
    negotiatedFrameFormat = frame_format;

    uvc_stream_ctrl_t ctrl;

    // If requested format is uncompressed, prefer NV12/I420 when the
    // device advertises corresponding GUIDs (helps Elgato CamLink 4K).
    if (frame_format == UVC_FRAME_FORMAT_UNCOMPRESSED && mDeviceHandle && mDeviceHandle->info) {
        uvc_streaming_interface_t *stream_if;
        bool found = false;
        DL_FOREACH(mDeviceHandle->info->stream_ifs, stream_if) {
            uvc_format_desc_t *fmt_desc;
            DL_FOREACH(stream_if->format_descs, fmt_desc) {
                if (fmt_desc->bDescriptorSubtype == UVC_VS_FORMAT_UNCOMPRESSED) {
                    // check first 4 bytes of GUID for ASCII fourcc like 'NV12' or 'I420'
                    uint8_t *g = fmt_desc->guidFormat;
                    if (g[0] == 'N' && g[1] == 'V' && g[2] == '1' && g[3] == '2') {
                        frame_format = UVC_FRAME_FORMAT_NV12;
                        found = true;
                        break;
                    }
                    if (g[0] == 'I' && g[1] == '4' && g[2] == '2' && g[3] == '0') {
                        // libuvc doesn't define a distinct I420 frame enum here,
                        // treat I420 as NV12 for negotiation and convert if needed.
                        frame_format = UVC_FRAME_FORMAT_NV12;
                        found = true;
                        break;
                    }
                }
            }
            if (found) break;
        }
    }
    result = uvc_get_stream_ctrl_format_size(
            mDeviceHandle, &ctrl,
            frame_format,
            width, height, fps);

    // If the initial request failed for uncompressed format, try common
    // YUV420 fallbacks (NV12, NV21) so devices like Elgato CamLink 4K
    // which advertise NV12/I420 are handled.
    if (result != UVC_SUCCESS && frame_format == UVC_FRAME_FORMAT_UNCOMPRESSED) {
        enum uvc_frame_format fallback_formats[] = {
                UVC_FRAME_FORMAT_NV12,
                UVC_FRAME_FORMAT_NV21,
                UVC_FRAME_FORMAT_YUYV,
                UVC_FRAME_FORMAT_MJPEG
        };
        const int num_fallbacks = sizeof(fallback_formats) / sizeof(fallback_formats[0]);
        for (int i = 0; i < num_fallbacks; ++i) {
            enum uvc_frame_format ff = fallback_formats[i];
            result = uvc_get_stream_ctrl_format_size(mDeviceHandle, &ctrl, ff, width, height, fps);
            if (result == UVC_SUCCESS) {
                frame_format = ff; // use the successful frame format
                LOGI("Requested uncompressed format not supported, falling back to %d", frame_format);
                break;
            }
        }
    }

#if LOCAL_DEBUG
    uvc_print_stream_ctrl(&ctrl, stderr);
#endif

    if (result == UVC_SUCCESS) {
        negotiatedFrameFormat = frame_format;
    }

    RETURN(result, int);
}

int UVCPreview::setPreviewDisplay(ANativeWindow *preview_window) {
    ENTER();
    pthread_mutex_lock(&preview_mutex);
    {
        if (mPreviewWindow != preview_window) {
            if (mPreviewWindow)
                ANativeWindow_release(mPreviewWindow);
            mPreviewWindow = preview_window;
            if (LIKELY(mPreviewWindow)) {
                ANativeWindow_setBuffersGeometry(mPreviewWindow,
                                                 frameWidth, frameHeight, previewFormat);
            }
        }
    }
    pthread_mutex_unlock(&preview_mutex);
    RETURN(0, int);
}

int UVCPreview::setFrameCallback(JNIEnv *env, jobject frame_callback_obj, int pixel_format) {

    ENTER();
    pthread_mutex_lock(&capture_mutex);
    {
        if (isRunning() && isCapturing()) {
            mIsCapturing = false;
            if (mFrameCallbackObj) {
                pthread_cond_signal(&capture_sync);
                pthread_cond_wait(&capture_sync, &capture_mutex);    // wait finishing capturing
            }
        }
        if (!env->IsSameObject(mFrameCallbackObj, frame_callback_obj)) {
            iframecallback_fields.onFrame = NULL;
            if (mFrameCallbackObj) {
                env->DeleteGlobalRef(mFrameCallbackObj);
            }
            mFrameCallbackObj = frame_callback_obj;
            if (frame_callback_obj) {
                // get method IDs of Java object for callback
                jclass clazz = env->GetObjectClass(frame_callback_obj);
                if (LIKELY(clazz)) {
                    iframecallback_fields.onFrame = env->GetMethodID(clazz,
                                                                     "onFrame",
                                                                     "(Ljava/nio/ByteBuffer;)V");
                } else {
                    LOGW("failed to get object class");
                }
                env->ExceptionClear();
                if (!iframecallback_fields.onFrame) {
                    LOGE("Can't find IFrameCallback#onFrame");
                    env->DeleteGlobalRef(frame_callback_obj);
                    mFrameCallbackObj = frame_callback_obj = NULL;
                }
            }
        }
        if (frame_callback_obj) {
            mPixelFormat = pixel_format;
            callbackPixelFormatChanged();
        }
    }
    pthread_mutex_unlock(&capture_mutex);
    RETURN(0, int);
}

void UVCPreview::callbackPixelFormatChanged() {
    mFrameCallbackFunc = NULL;
    const int callback_width = frameWidth > 0 ? frameWidth : requestWidth;
    const int callback_height = frameHeight > 0 ? frameHeight : requestHeight;
    const size_t sz = static_cast<size_t>(callback_width) * static_cast<size_t>(callback_height);
    switch (mPixelFormat) {
        case PIXEL_FORMAT_RAW:
            LOGI("PIXEL_FORMAT_RAW:");
            callbackPixelBytes = sz * 2;
            mFrameCallbackFunc = uvc_rgbx_to_yuyv;
            break;
        case PIXEL_FORMAT_YUV:
            LOGI("PIXEL_FORMAT_YUV:");
            callbackPixelBytes = sz * 2;
            mFrameCallbackFunc = uvc_rgbx_to_yuyv;
            break;
        case PIXEL_FORMAT_NV12:
            LOGI("PIXEL_FORMAT_NV12:");
            mFrameCallbackFunc = uvc_rgbx_to_nv12;
            callbackPixelBytes = (sz * 3) / 2;
            break;
        case PIXEL_FORMAT_NV21:
            LOGI("PIXEL_FORMAT_NV21:");
            mFrameCallbackFunc = uvc_rgbx_to_nv21;
            callbackPixelBytes = (sz * 3) / 2;
            break;
        case PIXEL_FORMAT_RGB:
            LOGI("PIXEL_FORMAT_RGB:");
            mFrameCallbackFunc = uvc_rgbx_to_rgb;
            callbackPixelBytes = sz * 3;
            break;
        case PIXEL_FORMAT_RGB565:
            LOGI("PIXEL_FORMAT_RGB565:");
            mFrameCallbackFunc = uvc_rgbx_to_rgb565;
            callbackPixelBytes = sz * 2;
            break;
        case PIXEL_FORMAT_RGBX:
            LOGI("PIXEL_FORMAT_RGBX:");
            callbackPixelBytes = sz * 4;
            break;
        case PIXEL_FORMAT_BGR:
            LOGI("PIXEL_FORMAT_BGR:");
            mFrameCallbackFunc = uvc_rgbx_to_bgr;
            callbackPixelBytes = sz * 3;
            break;
        case PIXEL_FORMAT_I420:
            LOGI("PIXEL_FORMAT_I420:");
            mFrameCallbackFunc = uvc_rgbx_to_i420;
            callbackPixelBytes = (sz * 3) / 2;
            break;
    }
}

void UVCPreview::clearDisplay() {
    ENTER();

    ANativeWindow_Buffer buffer;
    pthread_mutex_lock(&capture_mutex);
    {
        if (LIKELY(mCaptureWindow)) {
            if (LIKELY(ANativeWindow_lock(mCaptureWindow, &buffer, NULL) == 0)) {
                uint8_t *dest = (uint8_t *) buffer.bits;
                const size_t bytes = buffer.width * PREVIEW_PIXEL_BYTES;
                const int stride = buffer.stride * PREVIEW_PIXEL_BYTES;
                for (int i = 0; i < buffer.height; i++) {
                    memset(dest, 0, bytes);
                    dest += stride;
                }
                ANativeWindow_unlockAndPost(mCaptureWindow);
            }
        }
    }
    pthread_mutex_unlock(&capture_mutex);
    pthread_mutex_lock(&preview_mutex);
    {
        if (LIKELY(mPreviewWindow)) {
            if (LIKELY(ANativeWindow_lock(mPreviewWindow, &buffer, NULL) == 0)) {
                uint8_t *dest = (uint8_t *) buffer.bits;
                const size_t bytes = buffer.width * PREVIEW_PIXEL_BYTES;
                const int stride = buffer.stride * PREVIEW_PIXEL_BYTES;
                for (int i = 0; i < buffer.height; i++) {
                    memset(dest, 0, bytes);
                    dest += stride;
                }
                ANativeWindow_unlockAndPost(mPreviewWindow);
            }
        }
    }
    pthread_mutex_unlock(&preview_mutex);

    EXIT();
}

int UVCPreview::startPreview() {
    ENTER();

    int result = EXIT_FAILURE;
    if (!isRunning()) {
        mIsRunning = true;
        pthread_mutex_lock(&preview_mutex);
        {
            if (LIKELY(mPreviewWindow)) {
                result = pthread_create(&preview_thread, NULL, preview_thread_func, (void *) this);
                pthread_setname_np(preview_thread, "preview_thread");
            }
        }
        pthread_mutex_unlock(&preview_mutex);
        if (UNLIKELY(result != EXIT_SUCCESS)) {
            LOGW("UVCCamera::window does not exist/already running/could not create thread etc.");
            mIsRunning = false;
            pthread_mutex_lock(&preview_mutex);
            {
                pthread_cond_signal(&preview_sync);
            }
            pthread_mutex_unlock(&preview_mutex);
        }
    }
    RETURN(result, int);
}

int UVCPreview::stopPreview() {
    ENTER();
    bool b = isRunning();
    if (LIKELY(b)) {
        mIsRunning = false;
        pthread_cond_signal(&preview_sync);
        pthread_cond_signal(&capture_sync);
        if (capture_thread && pthread_join(capture_thread, NULL) != EXIT_SUCCESS) {
            LOGW("UVCPreview::terminate capture thread: pthread_join failed");
        }
        if (preview_thread && pthread_join(preview_thread, NULL) != EXIT_SUCCESS) {
            LOGW("UVCPreview::terminate preview thread: pthread_join failed");
        }
        clearDisplay();
    }
    clearPreviewFrame();
    clearCaptureFrame();
    pthread_mutex_lock(&preview_mutex);
    if (mPreviewWindow) {
        ANativeWindow_release(mPreviewWindow);
        mPreviewWindow = NULL;
    }
    pthread_mutex_unlock(&preview_mutex);
    pthread_mutex_lock(&capture_mutex);
    if (mCaptureWindow) {
        ANativeWindow_release(mCaptureWindow);
        mCaptureWindow = NULL;
    }
    pthread_mutex_unlock(&capture_mutex);
    RETURN(0, int);
}

//**********************************************************************
//
//**********************************************************************
void UVCPreview::uvc_preview_frame_callback(uvc_frame_t *frame, void *vptr_args) {
    UVCPreview *preview = reinterpret_cast<UVCPreview *>(vptr_args);
    if UNLIKELY(!preview->isRunning() || !frame || !frame->frame_format || !frame->data ||
                !frame->data_bytes)
        return;
    const size_t min_bytes = min_frame_bytes_for_format(frame);
    if (UNLIKELY(min_bytes == 0 || frame->data_bytes < min_bytes)) {
#if LOCAL_DEBUG
        LOGW("drop invalid frame: fmt=%d size=%zu need=%zu %dx%d", frame->frame_format,
             frame->data_bytes, min_bytes, frame->width, frame->height);
#endif
        return;
    }
//    if (UNLIKELY(
//            ((frame->frame_format != UVC_FRAME_FORMAT_MJPEG)
//             && (frame->data_bytes < preview->frameBytes))
//            || (frame->width != preview->frameWidth) || (frame->height != preview->frameHeight))) {
//
//#if LOCAL_DEBUG
//        LOGD("broken frame!:format=%d,actual_bytes=%d/%d(%d,%d/%d,%d)",
//             frame->frame_format, frame->data_bytes, preview->frameBytes,
//             frame->width, frame->height, preview->frameWidth, preview->frameHeight);
//#endif
//        return;
//    }
    if (LIKELY(preview->isRunning())) {
        uvc_frame_t *copy = preview->get_frame(frame->data_bytes);
        if (UNLIKELY(!copy)) {
#if LOCAL_DEBUG
            LOGE("uvc_callback:unable to allocate duplicate frame!");
#endif
            return;
        }
        uvc_error_t ret = uvc_duplicate_frame(frame, copy);
        if (UNLIKELY(ret)) {
            preview->recycle_frame(copy);
            return;
        }
        preview->addPreviewFrame(copy);
    }
}

void UVCPreview::addPreviewFrame(uvc_frame_t *frame) {

    pthread_mutex_lock(&preview_mutex);
    if (isRunning() && (previewFrames.size() < MAX_FRAME)) {
        previewFrames.put(frame);
        frame = NULL;
        pthread_cond_signal(&preview_sync);
    }
    pthread_mutex_unlock(&preview_mutex);
    if (frame) {
        recycle_frame(frame);
    }
}

uvc_frame_t *UVCPreview::waitPreviewFrame() {
    uvc_frame_t *frame = NULL;
    pthread_mutex_lock(&preview_mutex);
    {
        if (!previewFrames.size()) {
            pthread_cond_wait(&preview_sync, &preview_mutex);
        }
        if (LIKELY(isRunning() && previewFrames.size() > 0)) {
            frame = previewFrames.remove(0);
        }
    }
    pthread_mutex_unlock(&preview_mutex);
    return frame;
}

void UVCPreview::clearPreviewFrame() {
    pthread_mutex_lock(&preview_mutex);
    {
        for (int i = 0; i < previewFrames.size(); i++)
            recycle_frame(previewFrames[i]);
        previewFrames.clear();
    }
    pthread_mutex_unlock(&preview_mutex);
}

void *UVCPreview::preview_thread_func(void *vptr_args) {
    int result;

    ENTER();
    UVCPreview *preview = reinterpret_cast<UVCPreview *>(vptr_args);
    if (LIKELY(preview)) {
        uvc_stream_ctrl_t ctrl;
        result = preview->prepare_preview(&ctrl);
        if (LIKELY(!result)) {
            preview->do_preview(&ctrl);
        }
    }
    PRE_EXIT();
    pthread_exit(NULL);
}

int UVCPreview::prepare_preview(uvc_stream_ctrl_t *ctrl) {
    uvc_error_t result;

    ENTER();
    const enum uvc_frame_format requested_stream_format =
            negotiatedFrameFormat ? negotiatedFrameFormat : getFrameFormatByType(requestFormatType);
    result = uvc_get_stream_ctrl_format_size(mDeviceHandle, ctrl,
                                             requested_stream_format,
                                             requestWidth, requestHeight,
                                             requestFps
    );
        LOGI("prepare_preview: requested=%s %dx%d@%dfps",
            uvc_frame_format_name(requested_stream_format), requestWidth, requestHeight, requestFps);
    if (LIKELY(!result)) {
#if LOCAL_DEBUG
        uvc_print_stream_ctrl(ctrl, stderr);
#endif
        uvc_frame_desc_t *frame_desc;
        frame_desc = uvc_find_frame_desc(mDeviceHandle, ctrl->bFormatIndex, ctrl->bFrameIndex);
        if (LIKELY(!result)) {
            frameWidth = frame_desc->wWidth;
            frameHeight = frame_desc->wHeight;
            frameFormatType = frame_desc->bDescriptorSubtype;
              LOGI("frameSize=(%d,%d) negotiated=%s descSubtype=0x%02x",
                  frameWidth, frameHeight,
                  uvc_frame_format_name(requested_stream_format),
                  frameFormatType & 0xFF);
            pthread_mutex_lock(&preview_mutex);
            if (LIKELY(mPreviewWindow)) {
                ANativeWindow_setBuffersGeometry(mPreviewWindow,
                                                 frameWidth, frameHeight, previewFormat);
            }
            pthread_mutex_unlock(&preview_mutex);
        } else {
            frameWidth = requestWidth;
            frameHeight = requestHeight;
            frameFormatType = requestFormatType;
        }
        frameBytes = frameWidth * frameHeight * (frameFormatType == UVC_VS_FRAME_MJPEG ? 4 : 2);
        previewBytes = frameWidth * frameHeight * PREVIEW_PIXEL_BYTES;
    } else {
        LOGE("could not negotiate with camera:err=%d", result);
    }
    RETURN(result, int);
}

void UVCPreview::do_preview(uvc_stream_ctrl_t *ctrl) {
    ENTER();

//    time_t c_start, c_end;

    uvc_frame_t *frame = NULL;
    uvc_frame_t *frame_yuv = NULL;
    uvc_frame_t *frame_mjpeg = NULL;
    int result = uvc_start_streaming(
            mDeviceHandle, ctrl, uvc_preview_frame_callback, (void *) this, 0);

    if (LIKELY(!result)) {
        clearPreviewFrame();
        pthread_create(&capture_thread, NULL, capture_thread_func, (void *) this);
        pthread_setname_np(capture_thread, "capture_thread");

#if LOCAL_DEBUG
        LOGI("Streaming...");
#endif
        if (frameFormatType == UVC_VS_FRAME_MJPEG) {
            // MJPEG mode
            for (; LIKELY(isRunning());) {
                frame_mjpeg = waitPreviewFrame();
                if (LIKELY(frame_mjpeg)) {
//                    frame = get_frame(frame_mjpeg->width * frame_mjpeg->height * 2);
                    frame = get_frame(
                            frame_mjpeg->width * frame_mjpeg->height * PREVIEW_PIXEL_BYTES);
                    if (UNLIKELY(!frame)) {
                        recycle_frame(frame_mjpeg);
                        continue;
                    }
//                    c_start = clock();
                    result = uvc_mjpeg2rgbx_tj(frame_mjpeg, frame);   // MJPEG => yuyv
//                    c_end = clock();
//                    LOGI("uvc_mjpeg2yuyv time: %f", (double) (c_end - c_start) / CLOCKS_PER_SEC);
                    if (LIKELY(!result)) {
                        draw_preview_one(frame, &mPreviewWindow);
                        if (!addCaptureFrame(frame)) {
                            recycle_frame(frame);
                        }
                    } else {
                        recycle_frame(frame);
                    }
                    recycle_frame(frame_mjpeg);
                }
            }
        } else {
            // yuvyv mode
            bool logged_input_format_once = false;
            for (; LIKELY(isRunning());) {
                frame_yuv = waitPreviewFrame();
                if (LIKELY(frame_yuv)) {
                    if (UNLIKELY(!logged_input_format_once)) {
                        LOGI("preview_input: fmt=%s %dx%d bytes=%zu step=%zu",
                             uvc_frame_format_name(frame_yuv->frame_format),
                             frame_yuv->width, frame_yuv->height,
                             frame_yuv->data_bytes, frame_yuv->step);
                        logged_input_format_once = true;
                    }
                    const size_t min_bytes = min_frame_bytes_for_format(frame_yuv);
                    if (UNLIKELY(min_bytes == 0 || frame_yuv->data_bytes < min_bytes)) {
#if LOCAL_DEBUG
                        LOGW("skip bad preview frame: fmt=%d size=%zu need=%zu %dx%d", frame_yuv->frame_format,
                             frame_yuv->data_bytes, min_bytes, frame_yuv->width, frame_yuv->height);
#endif
                        recycle_frame(frame_yuv);
                        continue;
                    }

                    frame = get_frame(frame_yuv->width * frame_yuv->height * PREVIEW_PIXEL_BYTES);
                    if (UNLIKELY(!frame)) {
                        recycle_frame(frame_yuv);
                        continue;
                    }
                    // choose conversion based on incoming frame format
                    if (frame_yuv->frame_format == UVC_FRAME_FORMAT_MJPEG) {
                        result = uvc_mjpeg2rgbx_tj(frame_yuv, frame);
                    } else if (frame_yuv->frame_format == UVC_FRAME_FORMAT_YUYV) {
                        result = uvc_yuyv2rgbx(frame_yuv, frame);
                    } else if (frame_yuv->frame_format == UVC_FRAME_FORMAT_NV12) {
                        result = uvc_nv12_to_rgbx(frame_yuv, frame);
                    } else if (frame_yuv->frame_format == UVC_FRAME_FORMAT_NV21) {
                        result = uvc_nv21_to_rgbx(frame_yuv, frame);
                    } else if (frame_yuv->frame_format == UVC_FRAME_FORMAT_I420) {
                        result = uvc_i420_to_rgbx(frame_yuv, frame);
                    } else {
                        // fallback: try YUYV conversion if nothing else matches
                        result = uvc_yuyv2rgbx(frame_yuv, frame);
                    }

                    if (LIKELY(!result)) {
                        draw_preview_one(frame, &mPreviewWindow);

                        bool callbackQueued = false;
                        bool canPassRawFrame = false;
                        pthread_mutex_lock(&capture_mutex);
                        canPassRawFrame = mFrameCallbackObj
                                          && !mCaptureWindow
                                          && can_passthrough_callback_frame(mPixelFormat, frame_yuv);
                        pthread_mutex_unlock(&capture_mutex);

                        if (canPassRawFrame) {
                            uvc_frame_t *raw_for_callback = get_frame(frame_yuv->data_bytes);
                            if (LIKELY(raw_for_callback)) {
                                const uvc_error_t copy_ret = uvc_duplicate_frame(frame_yuv, raw_for_callback);
                                if (LIKELY(!copy_ret)) {
                                    callbackQueued = addCaptureFrame(raw_for_callback);
                                }
                                if (!callbackQueued) {
                                    recycle_frame(raw_for_callback);
                                }
                            }
                        }

                        if (!callbackQueued) {
                            if (!addCaptureFrame(frame)) {
                                recycle_frame(frame);
                            }
                        } else {
                            recycle_frame(frame);
                        }
                    } else {
                        recycle_frame(frame);
                    }
                    recycle_frame(frame_yuv);
                }
            }
        }
        pthread_cond_signal(&capture_sync);
#if LOCAL_DEBUG
        LOGI("preview_thread_func:wait for all callbacks complete");
#endif
        uvc_stop_streaming(mDeviceHandle);
#if LOCAL_DEBUG
        LOGI("Streaming finished");
#endif
    } else {
        LOGE("failed start_streaming (%d)", result);
    }

    EXIT();
}

// transfer specific frame data to the Surface(ANativeWindow)
int copyToSurface(uvc_frame_t *frame, ANativeWindow **window) {
    // ENTER();
    int result = 0;
    if (LIKELY(*window)) {
        ANativeWindow_Buffer buffer;
        if (LIKELY(ANativeWindow_lock(*window, &buffer, NULL) == 0)) {

            if (frame->width >= buffer.stride) {
                memcpy(buffer.bits, frame->data,
                       buffer.width * buffer.height * PREVIEW_PIXEL_BYTES);
            } else {
                for (int i = 0; i < buffer.height; i++) {
                    memcpy((uint8_t *) buffer.bits + i * buffer.stride * PREVIEW_PIXEL_BYTES,
                           (uint8_t *) frame->data + i * buffer.width * PREVIEW_PIXEL_BYTES,
                           buffer.width * PREVIEW_PIXEL_BYTES);
                }
            }

            ANativeWindow_unlockAndPost(*window);
        } else {
            result = -1;
        }
    } else {
        result = -1;
    }
    return result; //RETURN(result, int);
}

void UVCPreview::draw_preview_one(uvc_frame_t *frame, ANativeWindow **window) {
    // ENTER();

    pthread_mutex_lock(&preview_mutex);
    {
        if (LIKELY(*window != NULL)) {
            const int32_t win_w = ANativeWindow_getWidth(*window);
            const int32_t win_h = ANativeWindow_getHeight(*window);
            if (UNLIKELY(win_w != frame->width || win_h != frame->height)) {
                ANativeWindow_setBuffersGeometry(*window, frame->width, frame->height, previewFormat);
            }
            copyToSurface(frame, window);
        }
    }
    pthread_mutex_unlock(&preview_mutex);

    //RETURN();
}

//======================================================================
//
//======================================================================
inline const bool UVCPreview::isCapturing() const { return mIsCapturing; }

int UVCPreview::setCaptureDisplay(ANativeWindow *capture_window) {
    ENTER();
    pthread_mutex_lock(&capture_mutex);
    {
        if (isRunning() && isCapturing()) {
            mIsCapturing = false;
            if (mCaptureWindow) {
                pthread_cond_signal(&capture_sync);
                pthread_cond_wait(&capture_sync, &capture_mutex);    // wait finishing capturing
            }
        }
        if (mCaptureWindow != capture_window) {
            // release current Surface if already assigned.
            if (UNLIKELY(mCaptureWindow))
                ANativeWindow_release(mCaptureWindow);
            mCaptureWindow = capture_window;
            // if you use Surface came from MediaCodec#createInputSurface
            // you could not change window format at least when you use
            // ANativeWindow_lock / ANativeWindow_unlockAndPost
            // to write frame data to the Surface...
            // So we need check here.
            if (mCaptureWindow) {
                int32_t window_format = ANativeWindow_getFormat(mCaptureWindow);
                if ((window_format != WINDOW_FORMAT_RGB_565)
                    && (previewFormat == WINDOW_FORMAT_RGB_565)) {
                    LOGE("window format mismatch, cancelled movie capturing.");
                    ANativeWindow_release(mCaptureWindow);
                    mCaptureWindow = NULL;
                }
            }
        }
    }
    pthread_mutex_unlock(&capture_mutex);
    RETURN(0, int);
}

bool UVCPreview::addCaptureFrame(uvc_frame_t *frame) {
    bool result = false;
    pthread_mutex_lock(&capture_mutex);
    if (LIKELY(isRunning())) {
        // keep only latest one
        if (captureQueu) {
            recycle_frame(captureQueu);
        }
        captureQueu = frame;
        pthread_cond_broadcast(&capture_sync);
        result = true;
    }
    pthread_mutex_unlock(&capture_mutex);
    return result;
}

/**
 * get frame data for capturing, if not exist, block and wait
 */
uvc_frame_t *UVCPreview::waitCaptureFrame() {
    uvc_frame_t *frame = NULL;
    pthread_mutex_lock(&capture_mutex);
    {
        if (!captureQueu) {
            pthread_cond_wait(&capture_sync, &capture_mutex);
        }
        if (LIKELY(isRunning() && captureQueu)) {
            frame = captureQueu;
            captureQueu = NULL;
        }
    }
    pthread_mutex_unlock(&capture_mutex);
    return frame;
}

/**
 * clear drame data for capturing
 */
void UVCPreview::clearCaptureFrame() {
    pthread_mutex_lock(&capture_mutex);
    {
        if (captureQueu)
            recycle_frame(captureQueu);
        captureQueu = NULL;
    }
    pthread_mutex_unlock(&capture_mutex);
}

//======================================================================
/*
 * thread function
 * @param vptr_args pointer to UVCPreview instance
 */
// static
void *UVCPreview::capture_thread_func(void *vptr_args) {
    int result;

    ENTER();
    UVCPreview *preview = reinterpret_cast<UVCPreview *>(vptr_args);
    if (LIKELY(preview)) {
        JavaVM *vm = getVM();
        JNIEnv *env;
        // attach to JavaVM
        vm->AttachCurrentThread(&env, NULL);
        preview->do_capture(env);    // never return until finish previewing
        // detach from JavaVM
        vm->DetachCurrentThread();
        MARK("DetachCurrentThread");
    }
    PRE_EXIT();
    pthread_exit(NULL);
}

/**
 * the actual function for capturing
 */
void UVCPreview::do_capture(JNIEnv *env) {

    ENTER();

    clearCaptureFrame();
    callbackPixelFormatChanged();
    for (; isRunning();) {
        mIsCapturing = true;
        if (mCaptureWindow) {
            do_capture_surface(env);
        } else {
            do_capture_idle_loop(env);
        }
        pthread_cond_broadcast(&capture_sync);
    }    // end of for (; isRunning() ;)
    EXIT();
}

void UVCPreview::do_capture_idle_loop(JNIEnv *env) {
    ENTER();

    for (; isRunning() && isCapturing();) {
        do_capture_callback(env, waitCaptureFrame());
    }

    EXIT();
}

/**
 * write frame data to Surface for capturing
 */
void UVCPreview::do_capture_surface(JNIEnv *env) {
    ENTER();

    uvc_frame_t *frame = NULL;
    char *local_picture_path;

    for (; isRunning() && isCapturing();) {
        frame = waitCaptureFrame();
        if (LIKELY(frame)) {
            // frame data is always YUYV format.
            if LIKELY(isCapturing()) {
                if (LIKELY(mCaptureWindow)) {
                    copyToSurface(frame, &mCaptureWindow);
                }
            }
            do_capture_callback(env, frame);
        }
    }

    if (mCaptureWindow) {
        ANativeWindow_release(mCaptureWindow);
        mCaptureWindow = NULL;
    }

    EXIT();
}

/**
* call IFrameCallback#onFrame if needs
 */
void UVCPreview::do_capture_callback(JNIEnv *env, uvc_frame_t *frame) {
//    ENTER();

    if (LIKELY(frame)) {
        static bool logged_callback_path_once = false;
        uvc_frame_t *callback_frame = frame;
        size_t callback_bytes = frame->data_bytes;
        const bool passthrough = can_passthrough_callback_frame(mPixelFormat, frame);
        if (mFrameCallbackObj && iframecallback_fields.onFrame) {
            if (mFrameCallbackFunc && !passthrough) {
                const size_t expected_bytes = frame_bytes_for_pixel_format(mPixelFormat, frame->width, frame->height);
                callback_frame = get_frame(expected_bytes > 0 ? expected_bytes : callbackPixelBytes);
                if (LIKELY(callback_frame)) {
                    int b = mFrameCallbackFunc(frame, callback_frame);
                    recycle_frame(frame);
                    if (UNLIKELY(b)) {
                        LOGW("failed to convert for callback frame");
                        goto SKIP;
                    }
                    callback_bytes = callback_frame->data_bytes;
                } else {
                    LOGW("failed to allocate for callback frame");
                    callback_frame = frame;
                    goto SKIP;
                }
            } else {
                callback_bytes = frame->data_bytes;
            }
            if (UNLIKELY(!logged_callback_path_once)) {
                LOGI("callback_forward: input=%s passthrough=%d pixelFormat=%d bytes=%zu outBytes=%zu size=%dx%d",
                     uvc_frame_format_name(frame->frame_format), passthrough ? 1 : 0,
                     mPixelFormat, frame->data_bytes, callback_bytes,
                     frame->width, frame->height);
                logged_callback_path_once = true;
            }
            jobject buf = env->NewDirectByteBuffer(callback_frame->data, callback_bytes);
            env->CallVoidMethod(mFrameCallbackObj, iframecallback_fields.onFrame, buf);
            env->ExceptionClear();
            env->DeleteLocalRef(buf);
        }
        SKIP:
        recycle_frame(callback_frame);
    }
//    EXIT();
}
