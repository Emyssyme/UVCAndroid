package com.herohan.uvcapp;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.ImageFormat;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.media.MediaRecorder;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.util.Log;
import android.util.Size;
import android.util.SparseIntArray;
import android.view.Surface;

import androidx.annotation.NonNull;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Wraps the Android Camera2 API to provide a simple interface for:
 * <ul>
 *   <li>Enumerating all internal cameras (wide, ultra-wide, telephoto, front)</li>
 *   <li>Opening / closing a selected camera</li>
 *   <li>Displaying a live preview on a {@link SurfaceTexture}</li>
 *   <li>Changing preview resolution</li>
 *   <li>Capturing JPEG photos</li>
 *   <li>Recording video via {@link MediaRecorder}</li>
 *   <li>Delivering raw NV12 frames for NDI streaming</li>
 * </ul>
 */
public class InternalCameraHelper {

    private static final String TAG = "InternalCameraHelper";

    // Orientation hint lookup tables (from AOSP Camera2Video sample)
    private static final SparseIntArray DEFAULT_ORIENTATIONS  = new SparseIntArray();
    private static final SparseIntArray INVERSE_ORIENTATIONS  = new SparseIntArray();

    static {
        DEFAULT_ORIENTATIONS.append(Surface.ROTATION_0,   90);
        DEFAULT_ORIENTATIONS.append(Surface.ROTATION_90,   0);
        DEFAULT_ORIENTATIONS.append(Surface.ROTATION_180, 270);
        DEFAULT_ORIENTATIONS.append(Surface.ROTATION_270, 180);

        INVERSE_ORIENTATIONS.append(Surface.ROTATION_0,   270);
        INVERSE_ORIENTATIONS.append(Surface.ROTATION_90,  180);
        INVERSE_ORIENTATIONS.append(Surface.ROTATION_180,  90);
        INVERSE_ORIENTATIONS.append(Surface.ROTATION_270,   0);
    }

    // -------------------------------------------------------------------------
    // Public interfaces
    // -------------------------------------------------------------------------

    public interface OnCameraStateCallback {
        void onOpened(InternalCameraInfo cameraInfo, Size previewSize);
        void onClosed(InternalCameraInfo cameraInfo);
        void onError(InternalCameraInfo cameraInfo, String message);
    }

    /** Delivers individual YUV frames converted to NV12 for external consumers (NDI). */
    public interface OnFrameAvailableListener {
        void onFrame(ByteBuffer nv12Frame, int width, int height);
    }

    public interface OnPictureTakenListener {
        void onSuccess(File file);
        void onError(String message);
    }

    public interface OnRecordingStateListener {
        void onStarted();
        void onStopped(File file);
        void onError(String message);
    }

    // -------------------------------------------------------------------------
    // Fields
    // -------------------------------------------------------------------------

    private final Context       mContext;
    private final CameraManager mCameraManager;

    private CameraDevice            mCameraDevice;
    private CameraCaptureSession    mCaptureSession;
    private InternalCameraInfo      mCurrentCameraInfo;
    private Size                    mPreviewSize;

    // Background thread for Camera2 callbacks
    private HandlerThread mBackgroundThread;
    private Handler       mBackgroundHandler;

    // Surfaces / readers
    private SurfaceTexture mPreviewSurfaceTexture;
    private ImageReader    mJpegReader;
    private ImageReader    mNdiYuvReader;

    // Recording
    private MediaRecorder  mMediaRecorder;
    private boolean        mIsRecording = false;
    private File           mRecordingFile;

    // Flags for lazy preview start
    private boolean mCameraReadyForPreview = false;
    // Set to true during/after closeCamera() so in-flight frame callbacks are discarded
    private volatile boolean mClosed = false;

    // Callbacks
    private OnCameraStateCallback    mStateCallback;
    private OnFrameAvailableListener mFrameListener;
    private OnPictureTakenListener   mPictureListener;
    private OnRecordingStateListener mRecordingListener;

    private final Handler mMainHandler = new Handler(Looper.getMainLooper());

    // -------------------------------------------------------------------------
    // Construction
    // -------------------------------------------------------------------------

    public InternalCameraHelper(Context context) {
        mContext        = context.getApplicationContext();
        mCameraManager  = (CameraManager) mContext.getSystemService(Context.CAMERA_SERVICE);
    }

    // -------------------------------------------------------------------------
    // Static helpers: enumerate cameras / sizes
    // -------------------------------------------------------------------------

    /**
     * Returns all internal cameras labelled by type (wide, ultra-wide, telephoto, front, …).
     * Call this from the UI thread; it performs only lightweight metadata queries.
     */
    public static List<InternalCameraInfo> getAvailableCameras(Context context) {
        CameraManager manager = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
        List<InternalCameraInfo> result     = new ArrayList<>();
        List<InternalCameraInfo> backCameras = new ArrayList<>();

        try {
            for (String id : manager.getCameraIdList()) {
                CameraCharacteristics c = manager.getCameraCharacteristics(id);

                Integer facing = c.get(CameraCharacteristics.LENS_FACING);
                if (facing == null) continue;

                Integer sensorOrientation = c.get(CameraCharacteristics.SENSOR_ORIENTATION);
                int orientation = (sensorOrientation != null) ? sensorOrientation : 0;

                float[] fls = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS);
                float focalLength = (fls != null && fls.length > 0) ? fls[0] : 0f;

                switch (facing) {
                    case CameraCharacteristics.LENS_FACING_BACK:
                        // Classify later once we know all back cameras
                        backCameras.add(new InternalCameraInfo(
                                id, InternalCameraInfo.TYPE_BACK_WIDE, facing, focalLength, orientation));
                        break;

                    case CameraCharacteristics.LENS_FACING_FRONT:
                        result.add(new InternalCameraInfo(
                                id, InternalCameraInfo.TYPE_FRONT, facing, focalLength, orientation));
                        break;

                    default:
                        result.add(new InternalCameraInfo(
                                id, InternalCameraInfo.TYPE_EXTERNAL, facing, focalLength, orientation));
                        break;
                }
            }
        } catch (CameraAccessException e) {
            Log.e(TAG, "Failed to enumerate cameras", e);
        }

        classifyBackCameras(backCameras, result);
        return result;
    }

    private static void classifyBackCameras(List<InternalCameraInfo> back,
                                            List<InternalCameraInfo> out) {
        if (back.isEmpty()) return;

        // Sort ascending by focal length
        Collections.sort(back, (a, b) -> Float.compare(a.focalLength, b.focalLength));

        if (back.size() == 1) {
            InternalCameraInfo c = back.get(0);
            out.add(0, new InternalCameraInfo(
                    c.cameraId, InternalCameraInfo.TYPE_BACK_WIDE, c.lensFacing,
                    c.focalLength, c.sensorOrientation));
            return;
        }

        // Shortest focal = ultra-wide, longest = telephoto, middle = wide
        int n = back.size();
        for (int i = 0; i < n; i++) {
            InternalCameraInfo c = back.get(i);
            int type;
            if      (i == 0)     type = InternalCameraInfo.TYPE_BACK_ULTRA_WIDE;
            else if (i == n - 1) type = InternalCameraInfo.TYPE_BACK_TELEPHOTO;
            else                 type = InternalCameraInfo.TYPE_BACK_WIDE;
            out.add(i, new InternalCameraInfo(
                    c.cameraId, type, c.lensFacing, c.focalLength, c.sensorOrientation));
        }
    }

    /**
     * Returns supported preview sizes for the given camera, sorted largest-area first.
     */
    public static Size[] getSupportedSizes(Context context, String cameraId) {
        CameraManager manager = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
        try {
            CameraCharacteristics c = manager.getCameraCharacteristics(cameraId);
            StreamConfigurationMap map = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            if (map != null) {
                Size[] sizes = map.getOutputSizes(SurfaceTexture.class);
                if (sizes != null) {
                    Arrays.sort(sizes, (a, b) ->
                            (b.getWidth() * b.getHeight()) - (a.getWidth() * a.getHeight()));
                    return sizes;
                }
            }
        } catch (CameraAccessException e) {
            Log.e(TAG, "getSupportedSizes failed", e);
        }
        return new Size[0];
    }

    // -------------------------------------------------------------------------
    // Callback setters
    // -------------------------------------------------------------------------

    public void setStateCallback(OnCameraStateCallback cb)         { mStateCallback    = cb; }
    public void setFrameListener(OnFrameAvailableListener listener) { mFrameListener    = listener; }
    public void setPictureTakenListener(OnPictureTakenListener l)  { mPictureListener  = l; }
    public void setRecordingListener(OnRecordingStateListener l)   { mRecordingListener = l; }

    public boolean isRecording() { return mIsRecording; }

    // -------------------------------------------------------------------------
    // Camera lifecycle
    // -------------------------------------------------------------------------

    @SuppressLint("MissingPermission")
    public void openCamera(InternalCameraInfo cameraInfo, Size previewSize) {
        mClosed            = false;
        mCurrentCameraInfo = cameraInfo;
        mPreviewSize       = previewSize;
        startBackgroundThread();

        // Defensively close any device that was not properly released by a previous session.
        if (mCameraDevice != null) {
            Log.w(TAG, "openCamera: closing stale device before reopening");
            try { mCameraDevice.close(); } catch (Exception ignored) {}
            mCameraDevice = null;
        }

        try {
            mCameraManager.openCamera(cameraInfo.cameraId, new CameraDevice.StateCallback() {
                @Override
                public void onOpened(@NonNull CameraDevice camera) {
                    mCameraDevice = camera;
                    mCameraReadyForPreview = true;
                    // If a surface is already waiting, start preview immediately
                    if (mPreviewSurfaceTexture != null) {
                        startPreviewSession();
                    }
                    mMainHandler.post(() -> {
                        if (mStateCallback != null) {
                            mStateCallback.onOpened(mCurrentCameraInfo, mPreviewSize);
                        }
                    });
                }

                @Override
                public void onDisconnected(@NonNull CameraDevice camera) {
                    Log.w(TAG, "Camera disconnected");
                    camera.close();
                    mCameraDevice = null;
                    mMainHandler.post(() -> {
                        if (mStateCallback != null) mStateCallback.onClosed(mCurrentCameraInfo);
                    });
                }

                @Override
                public void onError(@NonNull CameraDevice camera, int error) {
                    Log.e(TAG, "Camera error: " + error);
                    camera.close();
                    mCameraDevice = null;
                    mMainHandler.post(() -> {
                        if (mStateCallback != null)
                            mStateCallback.onError(mCurrentCameraInfo, "Camera device error: " + error);
                    });
                }
            }, mBackgroundHandler);
        } catch (CameraAccessException | SecurityException e) {
            Log.e(TAG, "openCamera failed", e);
            if (mStateCallback != null)
                mStateCallback.onError(cameraInfo, e.getMessage());
        }
    }

    /**
     * Attach the preview surface and start streaming.
     * Safe to call before or after {@link #openCamera} - whichever comes last triggers the session.
     */
    public void startPreview(SurfaceTexture surfaceTexture) {
        mPreviewSurfaceTexture = surfaceTexture;
        if (mCameraDevice != null && mCameraReadyForPreview) {
            startPreviewSession();
        }
        // else: will be triggered from onOpened()
    }

    public void stopPreview() {
        mPreviewSurfaceTexture = null;
        closeCaptureSession();
    }

    public void closeCamera() {
        mClosed = true;
        mCameraReadyForPreview = false;
        try {
            if (mIsRecording) {
                stopRecordingInternal(false);
            }
            closeCaptureSession();
            if (mCameraDevice != null) {
                mCameraDevice.close();
                mCameraDevice = null;
            }
            closeImageReaders();
        } finally {
            // Null our references NOW so openCamera() can create a fresh HandlerThread.
            // Do NOT call quitSafely() yet: Camera2 posts an internal ClientStateCallback
            // .onClosed notification to our background handler AFTER camera.close() returns.
            // Killing the thread synchronously makes that post throw
            //   "Handler sending message to a Handler on a dead thread"
            // which prevents Camera2's state machine from reaching CLOSED, causing the
            // camera service to refuse the next openCamera() call on the same camera ID.
            // Schedule the quit 300 ms out — Camera2's post typically arrives in < 50 ms.
            final HandlerThread oldThread = mBackgroundThread;
            mBackgroundThread  = null;
            mBackgroundHandler = null;
            if (oldThread != null) {
                mMainHandler.postDelayed(oldThread::quitSafely, 300);
            }
            InternalCameraInfo closed = mCurrentCameraInfo;
            mCurrentCameraInfo = null;
            mMainHandler.post(() -> {
                if (mStateCallback != null && closed != null)
                    mStateCallback.onClosed(closed);
            });
        }
    }

    // -------------------------------------------------------------------------
    // Preview session
    // -------------------------------------------------------------------------

    private void startPreviewSession() {
        if (mCameraDevice == null || mPreviewSurfaceTexture == null) return;

        mPreviewSurfaceTexture.setDefaultBufferSize(
                mPreviewSize.getWidth(), mPreviewSize.getHeight());
        Surface previewSurface = new Surface(mPreviewSurfaceTexture);

        List<Surface> surfaces = new ArrayList<>();
        surfaces.add(previewSurface);

        // JPEG reader for photo capture (always included in preview session)
        mJpegReader = ImageReader.newInstance(
                mPreviewSize.getWidth(), mPreviewSize.getHeight(),
                ImageFormat.JPEG, 2);

        surfaces.add(mJpegReader.getSurface());

        // YUV reader for NDI frame delivery (only if a listener is registered)
        if (mFrameListener != null) {
            mNdiYuvReader = ImageReader.newInstance(
                    mPreviewSize.getWidth(), mPreviewSize.getHeight(),
                    ImageFormat.YUV_420_888, 2);
            mNdiYuvReader.setOnImageAvailableListener(this::onNdiFrameAvailable,
                    mBackgroundHandler);
            surfaces.add(mNdiYuvReader.getSurface());
        }

        try {
            final CaptureRequest.Builder previewBuilder =
                    mCameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            previewBuilder.addTarget(previewSurface);
            if (mNdiYuvReader != null)
                previewBuilder.addTarget(mNdiYuvReader.getSurface());

            // Continuous auto-focus and auto-exposure
            previewBuilder.set(CaptureRequest.CONTROL_AF_MODE,
                    CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO);
            previewBuilder.set(CaptureRequest.CONTROL_AE_MODE,
                    CaptureRequest.CONTROL_AE_MODE_ON);

            mCameraDevice.createCaptureSession(surfaces,
                    new CameraCaptureSession.StateCallback() {
                        @Override
                        public void onConfigured(@NonNull CameraCaptureSession session) {
                            if (mCameraDevice == null) return;
                            mCaptureSession = session;
                            try {
                                session.setRepeatingRequest(
                                        previewBuilder.build(), null, mBackgroundHandler);
                            } catch (CameraAccessException e) {
                                Log.e(TAG, "setRepeatingRequest failed", e);
                            }
                        }

                        @Override
                        public void onConfigureFailed(@NonNull CameraCaptureSession session) {
                            Log.e(TAG, "Preview session configure failed");
                        }
                    }, mBackgroundHandler);
        } catch (CameraAccessException e) {
            Log.e(TAG, "startPreviewSession failed", e);
        }
    }

    // -------------------------------------------------------------------------
    // Photo capture
    // -------------------------------------------------------------------------

    public void takePicture(File outputFile, OnPictureTakenListener listener) {
        if (mCaptureSession == null || mJpegReader == null) {
            if (listener != null) listener.onError("Camera not ready for capture");
            return;
        }
        mPictureListener = listener;

        mJpegReader.setOnImageAvailableListener(reader -> {
            try (Image image = reader.acquireLatestImage()) {
                if (image == null) return;
                ByteBuffer buffer = image.getPlanes()[0].getBuffer();
                byte[] bytes = new byte[buffer.remaining()];
                buffer.get(bytes);
                try (java.io.FileOutputStream fos = new java.io.FileOutputStream(outputFile)) {
                    fos.write(bytes);
                    mMainHandler.post(() -> {
                        if (mPictureListener != null) mPictureListener.onSuccess(outputFile);
                    });
                } catch (IOException e) {
                    mMainHandler.post(() -> {
                        if (mPictureListener != null) mPictureListener.onError(e.getMessage());
                    });
                }
            }
        }, mBackgroundHandler);

        try {
            CaptureRequest.Builder captureBuilder =
                    mCameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
            captureBuilder.addTarget(mJpegReader.getSurface());
            captureBuilder.set(CaptureRequest.CONTROL_AF_MODE,
                    CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
            captureBuilder.set(CaptureRequest.JPEG_QUALITY, (byte) 92);
            mCaptureSession.capture(captureBuilder.build(), null, mBackgroundHandler);
        } catch (CameraAccessException e) {
            mMainHandler.post(() -> {
                if (mPictureListener != null) mPictureListener.onError(e.getMessage());
            });
        }
    }

    // -------------------------------------------------------------------------
    // Video recording
    // -------------------------------------------------------------------------

    /**
     * @param displayRotation Surface.ROTATION_* constant from the activity's
     *                        {@code getWindowManager().getDefaultDisplay().getRotation()}.
     */
    public void startRecording(File outputFile, int displayRotation,
                               OnRecordingStateListener listener) {
        if (mCameraDevice == null) {
            if (listener != null) listener.onError("Camera not open");
            return;
        }
        mRecordingListener = listener;
        mRecordingFile     = outputFile;

        try {
            setUpMediaRecorder(outputFile, displayRotation);
        } catch (IOException e) {
            Log.e(TAG, "MediaRecorder prepare failed", e);
            if (listener != null) listener.onError(e.getMessage());
            return;
        }

        // Close preview session and open a recording session
        closeCaptureSession();
        startRecordingSession();
    }

    public void stopRecording() {
        stopRecordingInternal(true);
    }

    private void stopRecordingInternal(boolean restartPreview) {
        if (!mIsRecording) return;
        mIsRecording = false;
        try {
            if (mCaptureSession != null) {
                mCaptureSession.stopRepeating();
                mCaptureSession.abortCaptures();
            }
        } catch (CameraAccessException ignored) {}

        closeCaptureSession();

        File saved = mRecordingFile;
        final boolean[] success = {true};
        try {
            mMediaRecorder.stop();
            mMediaRecorder.reset();
        } catch (RuntimeException e) {
            Log.w(TAG, "MediaRecorder stop error", e);
            success[0] = false;
        }

        final File savedFile = saved;
        mMainHandler.post(() -> {
            if (mRecordingListener != null) {
                if (success[0]) mRecordingListener.onStopped(savedFile);
                else            mRecordingListener.onError("Recording failed");
            }
        });

        if (restartPreview && mPreviewSurfaceTexture != null) {
            startPreviewSession();
        }
    }

    private void setUpMediaRecorder(File outputFile, int displayRotation) throws IOException {
        if (mMediaRecorder == null) {
            mMediaRecorder = new MediaRecorder();
        } else {
            mMediaRecorder.reset();
        }

        mMediaRecorder.setAudioSource(MediaRecorder.AudioSource.MIC);
        mMediaRecorder.setVideoSource(MediaRecorder.VideoSource.SURFACE);
        mMediaRecorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
        mMediaRecorder.setOutputFile(outputFile.getAbsolutePath());
        mMediaRecorder.setVideoEncodingBitRate(10_000_000);
        mMediaRecorder.setVideoFrameRate(30);
        mMediaRecorder.setVideoSize(mPreviewSize.getWidth(), mPreviewSize.getHeight());
        mMediaRecorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264);
        mMediaRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);

        // Compute the correct orientation hint so playback is upright
        int sensorOrientation = mCurrentCameraInfo.sensorOrientation;
        int orientationHint;
        if (sensorOrientation == 90) {
            orientationHint = DEFAULT_ORIENTATIONS.get(displayRotation);
        } else if (sensorOrientation == 270) {
            orientationHint = INVERSE_ORIENTATIONS.get(displayRotation);
        } else {
            orientationHint = sensorOrientation; // 0 or 180, use as-is
        }
        mMediaRecorder.setOrientationHint(orientationHint);
        mMediaRecorder.prepare();
    }

    private void startRecordingSession() {
        if (mCameraDevice == null || mMediaRecorder == null) return;

        Surface previewSurface  = null;
        if (mPreviewSurfaceTexture != null) {
            mPreviewSurfaceTexture.setDefaultBufferSize(
                    mPreviewSize.getWidth(), mPreviewSize.getHeight());
            previewSurface = new Surface(mPreviewSurfaceTexture);
        }
        Surface recorderSurface = mMediaRecorder.getSurface();

        List<Surface> surfaces = new ArrayList<>();
        if (previewSurface != null) surfaces.add(previewSurface);
        surfaces.add(recorderSurface);

        // Keep NDI frame delivery during recording if requested
        if (mFrameListener != null && mNdiYuvReader == null) {
            mNdiYuvReader = ImageReader.newInstance(
                    mPreviewSize.getWidth(), mPreviewSize.getHeight(),
                    ImageFormat.YUV_420_888, 2);
            mNdiYuvReader.setOnImageAvailableListener(this::onNdiFrameAvailable,
                    mBackgroundHandler);
            surfaces.add(mNdiYuvReader.getSurface());
        } else if (mNdiYuvReader != null) {
            surfaces.add(mNdiYuvReader.getSurface());
        }

        final Surface finalPreviewSurface = previewSurface;
        try {
            mCameraDevice.createCaptureSession(surfaces,
                    new CameraCaptureSession.StateCallback() {
                        @Override
                        public void onConfigured(@NonNull CameraCaptureSession session) {
                            mCaptureSession = session;
                            try {
                                CaptureRequest.Builder builder =
                                        mCameraDevice.createCaptureRequest(
                                                CameraDevice.TEMPLATE_RECORD);
                                if (finalPreviewSurface != null)
                                    builder.addTarget(finalPreviewSurface);
                                builder.addTarget(recorderSurface);
                                if (mNdiYuvReader != null)
                                    builder.addTarget(mNdiYuvReader.getSurface());

                                builder.set(CaptureRequest.CONTROL_MODE,
                                        CaptureRequest.CONTROL_MODE_AUTO);
                                session.setRepeatingRequest(
                                        builder.build(), null, mBackgroundHandler);

                                mMediaRecorder.start();
                                mIsRecording = true;
                                mMainHandler.post(() -> {
                                    if (mRecordingListener != null)
                                        mRecordingListener.onStarted();
                                });
                            } catch (CameraAccessException e) {
                                Log.e(TAG, "Recording session capture failed", e);
                                mMainHandler.post(() -> {
                                    if (mRecordingListener != null)
                                        mRecordingListener.onError(e.getMessage());
                                });
                            }
                        }

                        @Override
                        public void onConfigureFailed(@NonNull CameraCaptureSession session) {
                            Log.e(TAG, "Recording session configure failed");
                            mMainHandler.post(() -> {
                                if (mRecordingListener != null)
                                    mRecordingListener.onError("Session configuration failed");
                            });
                        }
                    }, mBackgroundHandler);
        } catch (CameraAccessException e) {
            Log.e(TAG, "createCaptureSession (record) failed", e);
        }
    }

    // -------------------------------------------------------------------------
    // NDI frame delivery (YUV_420_888 → NV12)
    // -------------------------------------------------------------------------

    private void onNdiFrameAvailable(ImageReader reader) {
        if (mClosed || mFrameListener == null) return;
        try (Image image = reader.acquireLatestImage()) {
            if (image == null) return;
            ByteBuffer nv12 = yuv420ToNv12(image);
            if (nv12 != null && !mClosed) {
                mFrameListener.onFrame(nv12, image.getWidth(), image.getHeight());
            }
        } catch (IllegalStateException e) {
            // Image buffer was invalidated (camera closed mid-frame) — discard silently
            Log.d(TAG, "onNdiFrameAvailable: buffer inaccessible, skipping frame");
        }
    }

    /**
     * Converts an {@link ImageFormat#YUV_420_888} {@link Image} to a packed NV12 buffer.
     * NV12 layout: full Y plane, then interleaved U/V half-resolution plane.
     *
     * Uses bulk {@link ByteBuffer#put} with a positioned duplicate to avoid per-byte
     * absolute-index accesses that throw {@link IllegalStateException} on closed images,
     * and is also substantially faster on large frames.
     */
    private static ByteBuffer yuv420ToNv12(Image image) {
        int width  = image.getWidth();
        int height = image.getHeight();

        Image.Plane[] planes = image.getPlanes();
        if (planes.length < 3) return null;

        ByteBuffer yBuf  = planes[0].getBuffer();
        ByteBuffer uBuf  = planes[1].getBuffer();
        ByteBuffer vBuf  = planes[2].getBuffer();

        int yRowStride  = planes[0].getRowStride();
        int uvRowStride = planes[1].getRowStride();
        int uvPixStride = planes[1].getPixelStride();

        ByteBuffer nv12 = ByteBuffer.allocateDirect(width * height + (width * height / 2));

        // ── Y plane ──────────────────────────────────────────────────────────
        // If the row stride equals the width the entire plane is contiguous.
        // Clamp limit to capacity: some Samsung/Qualcomm HALs allocate the last
        // row 1 byte short, so width*height can exceed the buffer capacity.
        if (yRowStride == width) {
            ByteBuffer src = yBuf.duplicate();
            int yBytes = Math.min(width * height, src.capacity());
            src.position(0).limit(yBytes);
            nv12.put(src);
            // If the HAL shorted us a byte, pad with a zero so NV12 size stays correct
            for (int i = yBytes; i < width * height; i++) nv12.put((byte) 0);
        } else {
            // Copy row-by-row using a positioned duplicate (no absolute-index get)
            byte[] rowBuf = new byte[width];
            ByteBuffer src = yBuf.duplicate();
            for (int row = 0; row < height; row++) {
                int rowPos = row * yRowStride;
                if (rowPos + width <= src.capacity()) {
                    src.position(rowPos);
                    src.get(rowBuf, 0, width);
                } else {
                    // Last row is truncated — copy what we can and zero-pad
                    int available = Math.max(0, src.capacity() - rowPos);
                    if (available > 0) {
                        src.position(rowPos);
                        src.get(rowBuf, 0, available);
                    }
                    java.util.Arrays.fill(rowBuf, available, width, (byte) 0);
                }
                nv12.put(rowBuf);
            }
        }

        // ── Interleaved U/V (NV12 = UVUV...) ────────────────────────────────
        // Pixel stride == 2 means the U plane is already interleaved with V
        // (common on Android: it is actually the UV plane directly).
        if (uvPixStride == 2 && uvRowStride == width) {
            // Both planes are packed and already interleaved — copy UV directly.
            ByteBuffer src = uBuf.duplicate();
            int uvBytes = Math.min(width * (height / 2), src.capacity());
            src.position(0).limit(uvBytes);
            nv12.put(src);
            for (int i = uvBytes; i < width * (height / 2); i++) nv12.put((byte) 128);
        } else {
            // General case: build UV row by row
            ByteBuffer uSrc = uBuf.duplicate();
            ByteBuffer vSrc = vBuf.duplicate();
            for (int row = 0; row < height / 2; row++) {
                int rowStart = row * uvRowStride;
                for (int col = 0; col < width / 2; col++) {
                    int offset = rowStart + col * uvPixStride;
                    nv12.put(offset < uSrc.capacity() ? uSrc.get(offset) : (byte) 128);
                    nv12.put(offset < vSrc.capacity() ? vSrc.get(offset) : (byte) 128);
                }
            }
        }

        nv12.rewind();
        return nv12;
    }

    // -------------------------------------------------------------------------
    // Internal helpers
    // -------------------------------------------------------------------------

    private void closeCaptureSession() {
        if (mCaptureSession != null) {
            try {
                mCaptureSession.close();
            } catch (Exception ignored) {}
            mCaptureSession = null;
        }
    }

    private void closeImageReaders() {
        if (mJpegReader != null) {
            mJpegReader.close();
            mJpegReader = null;
        }
        if (mNdiYuvReader != null) {
            mNdiYuvReader.close();
            mNdiYuvReader = null;
        }
        if (mMediaRecorder != null) {
            mMediaRecorder.release();
            mMediaRecorder = null;
        }
    }

    private void startBackgroundThread() {
        // If a previous thread is somehow still alive (e.g. openCamera called twice without
        // closeCamera), quit it before creating a fresh one.
        if (mBackgroundThread != null) {
            mBackgroundThread.quitSafely();
            mBackgroundThread = null;
            mBackgroundHandler = null;
        }
        mBackgroundThread = new HandlerThread("InternalCamera-bg");
        mBackgroundThread.start();
        mBackgroundHandler = new Handler(mBackgroundThread.getLooper());
    }

    private void stopBackgroundThread() {
        // Only used as the delayed quit scheduled by closeCamera().
        // By the time this fires (~300 ms after closeCamera), Camera2 has already posted
        // its internal ClientStateCallback.onClosed to our now-drained background handler.
        if (mBackgroundThread == null) return;
        mBackgroundThread.quitSafely();
        mBackgroundThread = null;
        mBackgroundHandler = null;
    }
}
