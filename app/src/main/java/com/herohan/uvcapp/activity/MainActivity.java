package com.herohan.uvcapp.activity;

import android.Manifest;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.SurfaceTexture;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.google.gson.Gson;
import com.herohan.uvcapp.ImageCapture;
import com.herohan.uvcapp.VideoCapture;
import com.hjq.permissions.XXPermissions;
import com.serenegiant.opengl.renderer.MirrorMode;
import com.herohan.uvcapp.CameraHelper;
import com.herohan.uvcapp.ICameraHelper;
import com.serenegiant.usb.IButtonCallback;
import com.serenegiant.usb.Size;
import com.serenegiant.usb.USBMonitor;
import com.serenegiant.usb.UVCCamera;
import com.serenegiant.utils.UriHelper;
import com.herohan.uvcapp.R;
import com.herohan.uvcapp.databinding.ActivityMainBinding;
import com.herohan.uvcapp.fragment.CameraControlsDialogFragment;
import com.herohan.uvcapp.fragment.DeviceListDialogFragment;
import com.herohan.uvcapp.fragment.VideoFormatDialogFragment;
import com.herohan.uvcapp.utils.SaveHelper;

import com.serenegiant.ndi.Ndi;
import com.serenegiant.ndi.NdiSender;
import com.serenegiant.ndi.UvcNdiFrameForwarder;

import android.os.SystemClock;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.text.TextUtils;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.TextureView;
import android.view.View;
import android.view.ViewParent;
import android.widget.Toast;

import java.io.File;
import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.text.DecimalFormat;
import java.util.Timer;
import java.util.TimerTask;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = MainActivity.class.getSimpleName();
    private static final boolean DEBUG = true;

    private ActivityMainBinding mBinding;

    private static final int QUARTER_SECOND = 250;
    private static final int HALF_SECOND = 500;
    private static final int ONE_SECOND = 1000;

    private static final int DEFAULT_WIDTH = 640;
    private static final int DEFAULT_HEIGHT = 480;
    private static final float ASPECT_WARNING_THRESHOLD = 0.03f;
    private static final float EXPECTED_FIT_FILL_TOLERANCE = 0.92f;
    private static final float PREVIEW_FILL_EXTRA_SCALE = 1.0f;

    /**
     * Camera preview width
     */
    private int mPreviewWidth = DEFAULT_WIDTH;
    /**
     * Camera preview height
     */
    private int mPreviewHeight = DEFAULT_HEIGHT;

    private int mPreviewRotation = 0;

    private ICameraHelper mCameraHelper;
    private MultiFrameCallback mMultiCallback; // dispatches frames to NDI/RTP

    private UsbDevice mUsbDevice;
    private final ICameraHelper.StateCallback mStateCallback = new MyCameraHelperCallback();

    // NDI Streaming
    private static final String DEFAULT_NDI_FORMAT = "rgba"; // highest-quality, uncompressed
    private NdiSender mNdiSender;
    private UvcNdiFrameForwarder mFrameForwarder;
    private long mNdiStartTime = 0;

    private long mRecordStartTime = 0;
    private Timer mRecordTimer = null;
    private DecimalFormat mDecimalFormat;

    private boolean mIsRecording = false;
    private boolean mIsCameraConnected = false;
    private boolean mPreviewFillEnabled = false;
    private boolean mNdiHighQuality = true;   // toggle state for NDI mode
    // RTP streaming removed per request; keep NDI only

    private CameraControlsDialogFragment mControlsDialog;
    private DeviceListDialogFragment mDeviceListDialog;
    private VideoFormatDialogFragment mFormatDialog;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        mBinding = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(mBinding.getRoot());

        setSupportActionBar(mBinding.toolbar);

        checkCameraHelper();

        setListeners();

        // Initialize NDI
        try {
            Ndi.initialize();
            Log.i(TAG, "✅ NDI initialized. Version: " + Ndi.getNdiVersion());
        } catch (Exception e) {
            Log.e(TAG, "❌ Failed to initialize NDI", e);
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);

        if (intent.getAction().equals(UsbManager.ACTION_USB_DEVICE_ATTACHED)) {
            if (!mIsCameraConnected) {
                mUsbDevice = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
                selectDevice(mUsbDevice);
            }
        }
    }

    @Override
    protected void onStart() {
        super.onStart();

        initPreviewView();
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (mIsRecording) {
            toggleVideoRecord(false);
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        clearCameraHelper();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        // Inflate the menu; this adds items to the action bar if it is present.
        getMenuInflater().inflate(R.menu.menu_main, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        // Handle action bar item clicks here. The action bar will
        // automatically handle clicks on the Home/Up button, so long
        // as you specify a parent activity in AndroidManifest.xml.
        int id = item.getItemId();

        //noinspection SimplifiableIfStatement
        if (id == R.id.action_control) {
            showCameraControlsDialog();
        } else if (id == R.id.action_device) {
            showDeviceListDialog();
        } else if (id == R.id.action_safely_eject) {
            safelyEject();
        } else if (id == R.id.action_settings) {
        } else if (id == R.id.action_video_format) {
            showVideoFormatDialog();
        } else if (id == R.id.action_rotate_90_CW) {
            rotateBy(90);
        } else if (id == R.id.action_rotate_90_CCW) {
            rotateBy(-90);
        } else if (id == R.id.action_flip_horizontally) {
            flipHorizontally();
        } else if (id == R.id.action_flip_vertically) {
            flipVertically();
        } else if (id == R.id.action_preview_fill_toggle) {
            mPreviewFillEnabled = !mPreviewFillEnabled;
            applyPreviewFillTransform();
            invalidateOptionsMenu();
            Toast.makeText(this,
                    mPreviewFillEnabled ? getString(R.string.action_preview_mode_fill)
                            : getString(R.string.action_preview_mode_fit),
                    Toast.LENGTH_SHORT).show();
        } else if (id == R.id.action_ndimode) {
            // toggle NDI format
            setNdiFormat(!mNdiHighQuality);
            invalidateOptionsMenu();
            Toast.makeText(this,
                    mNdiHighQuality ? getString(R.string.action_ndimode_high)
                                   : getString(R.string.action_ndimode_low),
                    Toast.LENGTH_SHORT).show();
        }
        return true;
    }

    @Override
    public boolean onPrepareOptionsMenu(Menu menu) {
        if (mIsCameraConnected) {
            menu.findItem(R.id.action_control).setVisible(true);
            menu.findItem(R.id.action_safely_eject).setVisible(true);
            menu.findItem(R.id.action_video_format).setVisible(true);
            menu.findItem(R.id.action_rotate_90_CW).setVisible(true);
            menu.findItem(R.id.action_rotate_90_CCW).setVisible(true);
            menu.findItem(R.id.action_flip_horizontally).setVisible(true);
            menu.findItem(R.id.action_flip_vertically).setVisible(true);
            menu.findItem(R.id.action_preview_fill_toggle).setVisible(true);
            menu.findItem(R.id.action_ndimode).setVisible(true);
        } else {
            menu.findItem(R.id.action_control).setVisible(false);
            menu.findItem(R.id.action_safely_eject).setVisible(false);
            menu.findItem(R.id.action_video_format).setVisible(false);
            menu.findItem(R.id.action_rotate_90_CW).setVisible(false);
            menu.findItem(R.id.action_rotate_90_CCW).setVisible(false);
            menu.findItem(R.id.action_flip_horizontally).setVisible(false);
            menu.findItem(R.id.action_flip_vertically).setVisible(false);
            menu.findItem(R.id.action_preview_fill_toggle).setVisible(false);
            menu.findItem(R.id.action_ndimode).setVisible(false);
        }
        final MenuItem previewModeItem = menu.findItem(R.id.action_preview_fill_toggle);
        if (previewModeItem != null) {
            previewModeItem.setTitle(mPreviewFillEnabled
                    ? R.string.action_preview_mode_fill
                    : R.string.action_preview_mode_fit);
        }
        final MenuItem ndiModeItem = menu.findItem(R.id.action_ndimode);
        if (ndiModeItem != null) {
            ndiModeItem.setTitle(mNdiHighQuality
                    ? R.string.action_ndimode_high
                    : R.string.action_ndimode_low);
        }
        return super.onPrepareOptionsMenu(menu);
    }

    private void setListeners() {
        mBinding.fabPicture.setOnClickListener(v -> {
            XXPermissions.with(this)
                    .permission(Manifest.permission.MANAGE_EXTERNAL_STORAGE)
                    .request((permissions, all) -> {
                        takePicture();
                    });
        });

        mBinding.fabVideo.setOnClickListener(v -> {
            XXPermissions.with(this)
                    .permission(Manifest.permission.MANAGE_EXTERNAL_STORAGE)
                    .permission(Manifest.permission.RECORD_AUDIO)
                    .request((permissions, all) -> {
                        toggleVideoRecord(!mIsRecording);
                    });
        });
    }

    private void showCameraControlsDialog() {
        if (mControlsDialog == null) {
            mControlsDialog = new CameraControlsDialogFragment(mCameraHelper);
        }
        // When DialogFragment is not showing
        if (!mControlsDialog.isAdded()) {
            mControlsDialog.show(getSupportFragmentManager(), "camera_controls");
        }
    }

    private void showDeviceListDialog() {
        if (mDeviceListDialog != null && mDeviceListDialog.isAdded()) {
            return;
        }

        mDeviceListDialog = new DeviceListDialogFragment(mCameraHelper, mIsCameraConnected ? mUsbDevice : null);
        mDeviceListDialog.setOnDeviceItemSelectListener(usbDevice -> {
            if (mIsCameraConnected) {
                mCameraHelper.closeCamera();
            }
            mUsbDevice = usbDevice;
            selectDevice(mUsbDevice);
        });

        mDeviceListDialog.show(getSupportFragmentManager(), "device_list");
    }

    private void showVideoFormatDialog() {
        if (mFormatDialog != null && mFormatDialog.isAdded()) {
            return;
        }

        mFormatDialog = new VideoFormatDialogFragment(mCameraHelper.getSupportedFormatList(), mCameraHelper.getPreviewSize());
        mFormatDialog.setOnVideoFormatSelectListener(size -> {
            if (mIsCameraConnected && !mCameraHelper.isRecording()) {
                mCameraHelper.stopPreview();
                mCameraHelper.setPreviewSize(size);
                mCameraHelper.startPreview();
                resizePreviewView(size);
                // save selected preview size
                setSavedPreviewSize(size);
            }
        });

        mFormatDialog.show(getSupportFragmentManager(), "video_format");
    }

    private void closeAllDialogFragment() {
        if (mControlsDialog != null && mControlsDialog.isAdded()) {
            mControlsDialog.dismiss();
        }
        if (mDeviceListDialog != null && mDeviceListDialog.isAdded()) {
            mDeviceListDialog.dismiss();
        }
        if (mFormatDialog != null && mFormatDialog.isAdded()) {
            mFormatDialog.dismiss();
        }
    }

    private void safelyEject() {
        if (mCameraHelper != null) {
            mCameraHelper.closeCamera();
        }
    }

    /**
     * Switch between high‑quality RGBA and efficient YUV NDI modes.
     * This tears down and re‑creates the forwarder and updates callback.
     */
    private void setNdiFormat(final boolean highQuality) {
        // change desired output type without tearing down camera
        mNdiHighQuality = highQuality;
        if (mFrameForwarder != null) {
            mFrameForwarder.setNdiFormat(mNdiHighQuality ? "rgba" : "nv12");
            Log.i(TAG, "NDI format set to " + (mNdiHighQuality ? "RGBA" : "NV12"));
        }
        // re-register combined callback so that the correct frame receiver is used
        if (mCameraHelper != null && mMultiCallback != null) {
            mCameraHelper.setFrameCallback(mMultiCallback, UVCCamera.PIXEL_FORMAT_NV12);
        }
    }

    // PROMOTE incoming UVC frames to NDI and/or RTP
    // frame callback for NDI only
    private class MultiFrameCallback implements com.serenegiant.usb.IFrameCallback {
        @Override
        public void onFrame(java.nio.ByteBuffer frame) {
            if (mFrameForwarder != null) {
                mFrameForwarder.onFrame(frame);
            }
        }
    }


    private void rotateBy(int angle) {
        mPreviewRotation += angle;
        mPreviewRotation %= 360;
        if (mPreviewRotation < 0) {
            mPreviewRotation += 360;
        }

        if (mCameraHelper != null) {
            mCameraHelper.setPreviewConfig(
                    mCameraHelper.getPreviewConfig().setRotation(mPreviewRotation));
        }
    }

    private void flipHorizontally() {
        if (mCameraHelper != null) {
            mCameraHelper.setPreviewConfig(
                    mCameraHelper.getPreviewConfig().setMirror(MirrorMode.MIRROR_HORIZONTAL));
        }
    }

    private void flipVertically() {
        if (mCameraHelper != null) {
            mCameraHelper.setPreviewConfig(
                    mCameraHelper.getPreviewConfig().setMirror(MirrorMode.MIRROR_VERTICAL));
        }
    }

    private void checkCameraHelper() {
        if (!mIsCameraConnected) {
            clearCameraHelper();
        }
        initCameraHelper();
    }

    private void initCameraHelper() {
        if (mCameraHelper == null) {
            mCameraHelper = new CameraHelper();
            mCameraHelper.setStateCallback(mStateCallback);

            setCustomImageCaptureConfig();
            setCustomVideoCaptureConfig();
        }
    }

    private void clearCameraHelper() {
        if (DEBUG) Log.v(TAG, "clearCameraHelper:");
        if (mCameraHelper != null) {
            mCameraHelper.release();
            mCameraHelper = null;
        }
    }

    private void initPreviewView() {
        mBinding.viewMainPreview.setAspectRatio(mPreviewWidth, mPreviewHeight);
        mBinding.viewMainPreview.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            @Override
            public void onSurfaceTextureAvailable(@NonNull SurfaceTexture surface, int width, int height) {
                Log.i(TAG, "Preview surface available: " + width + "x" + height);
                if (mCameraHelper != null) {
                    mCameraHelper.addSurface(surface, false);
                }
                // give a quick hint about zooming controls
                Toast.makeText(MainActivity.this, "Preview: pinch to zoom, double-tap for 100%", Toast.LENGTH_SHORT).show();
                mBinding.viewMainPreview.post(() -> {
                    applyPreviewFillTransform();
                    logPreviewDiagnostics("surface_available", mCameraHelper != null ? mCameraHelper.getPreviewSize() : null);
                });
            }

            @Override
            public void onSurfaceTextureSizeChanged(@NonNull SurfaceTexture surface, int width, int height) {
                Log.i(TAG, "Preview surface size changed: " + width + "x" + height);
                mBinding.viewMainPreview.post(() -> {
                    applyPreviewFillTransform();
                    logPreviewDiagnostics("surface_size_changed", mCameraHelper != null ? mCameraHelper.getPreviewSize() : null);
                    // update zoom limit in case the view dimension changed
                    final TextureView previewView = mBinding.viewMainPreview;
                    final int viewW = previewView.getWidth();
                    final int viewH = previewView.getHeight();
                    if (viewW > 0 && viewH > 0) {
                        final float scaleX = (float) mPreviewWidth / (float) viewW;
                        final float scaleY = (float) mPreviewHeight / (float) viewH;
                        final float required = Math.max(1.0f, Math.max(scaleX, scaleY));
                        if (previewView instanceof com.serenegiant.widget.UVCCameraTextureView) {
                            ((com.serenegiant.widget.UVCCameraTextureView)previewView).setMaxScale(required);
                        }
                    }
                });
            }

            @Override
            public boolean onSurfaceTextureDestroyed(@NonNull SurfaceTexture surface) {
                if (mCameraHelper != null) {
                    mCameraHelper.removeSurface(surface);
                }
                return false;
            }

            @Override
            public void onSurfaceTextureUpdated(@NonNull SurfaceTexture surface) {

            }
        });
    }


    public void attachNewDevice(UsbDevice device) {
        if (mUsbDevice == null) {
            mUsbDevice = device;

            selectDevice(device);
        }
    }

    /**
     * In Android9+, connected to the UVC CAMERA, CAMERA permission is required
     *
     * @param device
     */
    protected void selectDevice(UsbDevice device) {
        if (DEBUG) Log.v(TAG, "selectDevice:device=" + device.getDeviceName());

        XXPermissions.with(this)
                .permission(Manifest.permission.CAMERA)
                .request((permissions, all) -> {
                    mIsCameraConnected = false;
                    updateUIControls();

                    if (mCameraHelper != null) {
                        // 通过UsbDevice对象，尝试获取设备权限
                        mCameraHelper.selectDevice(device);
                    }
                });
    }

    private class MyCameraHelperCallback implements ICameraHelper.StateCallback {
        @Override
        public void onAttach(UsbDevice device) {
            if (DEBUG) Log.v(TAG, "onAttach:device=" + device.getDeviceName());

            attachNewDevice(device);
        }

        /**
         * After obtaining USB device permissions, connect the USB camera
         */
        @Override
        public void onDeviceOpen(UsbDevice device, boolean isFirstOpen) {
            if (DEBUG) Log.v(TAG, "onDeviceOpen:device=" + device.getDeviceName());

            mCameraHelper.openCamera(getSavedPreviewSize());

            mCameraHelper.setButtonCallback(new IButtonCallback() {
                @Override
                public void onButton(int button, int state) {
                    Toast.makeText(MainActivity.this, "onButton(button=" + button + "; " +
                            "state=" + state + ")", Toast.LENGTH_SHORT).show();
                }
            });
        }

        @Override
        public void onCameraOpen(UsbDevice device) {
            if (DEBUG) Log.v(TAG, "onCameraOpen:device=" + device.getDeviceName());
            
            // ✅ Step 1: Get camera size FIRST (before any preview setup)
            Size size = mCameraHelper.getPreviewSize();
            if (size != null) {
                Log.i(TAG, "✅ Camera size: " + size.width + "x" + size.height);
                resizePreviewView(size);
                mBinding.viewMainPreview.post(() -> {
                    applyPreviewFillTransform();
                    logPreviewDiagnostics("camera_open_resize", size);
                });
            } else {
                Log.w(TAG, "❌ Could not get camera preview size");
            }
            
            // ✅ Step 2: Setup NDI sender and forwarder (format toggled via helper)
            if (size != null) {
                try {
                    mNdiStartTime = SystemClock.elapsedRealtime();
                    String sourceName = "UVCAndroid-" + mNdiStartTime;
                    mNdiSender = new NdiSender(sourceName);
                    Log.i(TAG, "✅ NDI sender created: " + sourceName);

                    // create forwarder with known camera format (assume NV12)
                    mFrameForwarder = new UvcNdiFrameForwarder(mNdiSender, "nv12", null);
                    mFrameForwarder.setFrameDimensions(size.width, size.height);
                    // now apply quality mode (will register callback)
                    setNdiFormat(mNdiHighQuality);
                } catch (Exception e) {
                    Log.e(TAG, "❌ Failed to create NDI sender", e);
                    mNdiSender = null;
                    mFrameForwarder = null;
                }
            }
            
            // ✅ Step 3: register a combined frame callback BEFORE startPreview
            mMultiCallback = new MultiFrameCallback();
            try {
                mCameraHelper.setFrameCallback(mMultiCallback, UVCCamera.PIXEL_FORMAT_NV12);
                Log.i(TAG, "✅ Multi-frame callback registered with mCameraHelper (NV12)");
            } catch (Exception e) {
                Log.w(TAG, "⚠️ NV12 callback registration failed, fallback to YUV", e);
                try {
                    mCameraHelper.setFrameCallback(mMultiCallback, UVCCamera.PIXEL_FORMAT_YUV);
                    Log.i(TAG, "✅ Multi-frame callback registered with mCameraHelper (YUV fallback)");
                } catch (Exception fallbackError) {
                    Log.e(TAG, "❌ Failed to register multi-frame callback", fallbackError);
                }
            }
            
            // ✅ Step 4: Now start preview (frames will flow to NDI)
            mCameraHelper.startPreview();
            Log.i(TAG, "✅ Camera preview started, NDI ready to stream");
            mBinding.viewMainPreview.postDelayed(() -> {
                applyPreviewFillTransform();
                logPreviewDiagnostics("after_start_preview", mCameraHelper != null ? mCameraHelper.getPreviewSize() : size);
            }, 500);

            // Add surface texture for display
            if (mBinding.viewMainPreview.getSurfaceTexture() != null) {
                mCameraHelper.addSurface(mBinding.viewMainPreview.getSurfaceTexture(), false);
                Log.i(TAG, "✅ Preview surface added");
            }

            mIsCameraConnected = true;
            updateUIControls();
        }

        @Override
        public void onCameraClose(UsbDevice device) {
            if (DEBUG) Log.v(TAG, "onCameraClose:device=" + device.getDeviceName());

            if (mIsRecording) {
                toggleVideoRecord(false);
            }

            // ✅ Cleanup NDI/RTP resources
            try {
                if (mCameraHelper != null) {
                    mCameraHelper.setFrameCallback(null, 0);
                    Log.i(TAG, "✅ Frame callback unregistered");
                }
                if (mFrameForwarder != null) {
                    mFrameForwarder = null;
                    Log.i(TAG, "✅ Frame forwarder released");
                }
                if (mNdiSender != null) {
                    mNdiSender.close();
                    mNdiSender = null;
                    Log.i(TAG, "✅ NDI Sender closed");
                }
                } catch (Exception e) {
                Log.e(TAG, "❌ Error stopping streams", e);
            }

            if (mCameraHelper != null && mBinding.viewMainPreview.getSurfaceTexture() != null) {
                mCameraHelper.removeSurface(mBinding.viewMainPreview.getSurfaceTexture());
            }

            mIsCameraConnected = false;
            updateUIControls();

            closeAllDialogFragment();
        }

        @Override
        public void onDeviceClose(UsbDevice device) {
            if (DEBUG) Log.v(TAG, "onDeviceClose:device=" + device.getDeviceName());
        }

        @Override
        public void onDetach(UsbDevice device) {
            if (DEBUG) Log.v(TAG, "onDetach:device=" + device.getDeviceName());

            if (device.equals(mUsbDevice)) {
                mUsbDevice = null;
            }
        }

        @Override
        public void onCancel(UsbDevice device) {
            if (DEBUG) Log.v(TAG, "onCancel:device=" + device.getDeviceName());

            if (device.equals(mUsbDevice)) {
                mUsbDevice = null;
            }
        }
    }

    private void resizePreviewView(Size size) {
        // Update the preview size (camera capture resolution)
        mPreviewWidth = size.width;
        mPreviewHeight = size.height;
        // Set the aspect ratio of TextureView to match the aspect ratio of the camera
        mBinding.viewMainPreview.setAspectRatio(mPreviewWidth, mPreviewHeight);
        // apply fill/fit transform if necessary
        mBinding.viewMainPreview.post(this::applyPreviewFillTransform);
        
        // calculate the zoom factor required for 1:1 pixel mapping and update maxscale
        mBinding.viewMainPreview.post(() -> {
            final TextureView previewView = mBinding.viewMainPreview;
            final int viewW = previewView.getWidth();
            final int viewH = previewView.getHeight();
            if (viewW > 0 && viewH > 0) {
                final float scaleX = (float) mPreviewWidth / (float) viewW;
                final float scaleY = (float) mPreviewHeight / (float) viewH;
                final float required = Math.max(1.0f, Math.max(scaleX, scaleY));
                if (previewView instanceof com.serenegiant.widget.UVCCameraTextureView) {
                    ((com.serenegiant.widget.UVCCameraTextureView)previewView).setMaxScale(required);
                    if (DEBUG) Log.i(TAG, "set max preview zoom scale=" + required);
                }
            }
        });
    }

    private void applyPreviewFillTransform() {
        final TextureView previewView = mBinding.viewMainPreview;
        final ViewParent parent = previewView.getParent();
        if (!(parent instanceof View)) {
            return;
        }
        final View container = (View) parent;
        final int viewWidth = previewView.getWidth();
        final int viewHeight = previewView.getHeight();
        final int containerWidth = container.getWidth();
        final int containerHeight = container.getHeight();
        if (viewWidth <= 0 || viewHeight <= 0 || containerWidth <= 0 || containerHeight <= 0) {
            return;
        }

        final float finalScale;
        if (mPreviewFillEnabled) {
            final float scaleToFill = Math.max(
                    (float) containerWidth / (float) viewWidth,
                    (float) containerHeight / (float) viewHeight
            ) * PREVIEW_FILL_EXTRA_SCALE;
            finalScale = Math.max(1.0f, scaleToFill);
        } else {
            finalScale = 1.0f;
        }
        previewView.setPivotX(viewWidth * 0.5f);
        previewView.setPivotY(viewHeight * 0.5f);
        previewView.setScaleX(finalScale);
        previewView.setScaleY(finalScale);

        if (DEBUG) {
            Log.i(TAG, "[PreviewFill] view=" + viewWidth + "x" + viewHeight
                    + " container=" + containerWidth + "x" + containerHeight
                    + " mode=" + (mPreviewFillEnabled ? "fill" : "fit")
                    + " appliedScale=" + finalScale);
        }
    }

    private void logPreviewDiagnostics(@NonNull String stage, @Nullable Size cameraSize) {
        final TextureView previewView = mBinding.viewMainPreview;
        final boolean hasSurfaceTexture = previewView.getSurfaceTexture() != null;
        final int viewWidth = previewView.getWidth();
        final int viewHeight = previewView.getHeight();
        final int measuredWidth = previewView.getMeasuredWidth();
        final int measuredHeight = previewView.getMeasuredHeight();
        final int screenWidth = getResources().getDisplayMetrics().widthPixels;
        final int screenHeight = getResources().getDisplayMetrics().heightPixels;
        final ViewParent parent = previewView.getParent();
        final int containerWidth = parent instanceof View ? ((View) parent).getWidth() : 0;
        final int containerHeight = parent instanceof View ? ((View) parent).getHeight() : 0;

        final int camWidth = cameraSize != null ? cameraSize.width : mPreviewWidth;
        final int camHeight = cameraSize != null ? cameraSize.height : mPreviewHeight;

        final float camAspect = camHeight > 0 ? (float) camWidth / (float) camHeight : 0f;
        final float viewAspect = viewHeight > 0 ? (float) viewWidth / (float) viewHeight : 0f;

        Log.i(TAG, "[PreviewCheck:" + stage + "] cam=" + camWidth + "x" + camHeight
                + " view=" + viewWidth + "x" + viewHeight
                + " measured=" + measuredWidth + "x" + measuredHeight
            + " container=" + containerWidth + "x" + containerHeight
                + " screen=" + screenWidth + "x" + screenHeight
                + " camAspect=" + camAspect + " viewAspect=" + viewAspect);

        if (camAspect > 0f && viewAspect > 0f) {
            final float aspectDelta = Math.abs(camAspect - viewAspect);
            if (hasSurfaceTexture && aspectDelta > ASPECT_WARNING_THRESHOLD) {
                Log.w(TAG, "[PreviewCheck:" + stage + "] Aspect mismatch detected. delta=" + aspectDelta
                        + " (cam=" + camAspect + ", view=" + viewAspect + ")");
            }
        }

        if (hasSurfaceTexture && containerWidth > 0 && containerHeight > 0 && viewWidth > 0 && viewHeight > 0 && camAspect > 0f) {
            final float viewArea = (float) viewWidth * (float) viewHeight;
            final float containerArea = (float) containerWidth * (float) containerHeight;
            final float areaRatio = viewArea / containerArea;
            final float containerAspect = (float) containerWidth / (float) containerHeight;
            final float expectedFitAreaRatio = Math.min(containerAspect / camAspect, camAspect / containerAspect);
            if (areaRatio < expectedFitAreaRatio * EXPECTED_FIT_FILL_TOLERANCE) {
                Log.w(TAG, "[PreviewCheck:" + stage + "] Preview viewport uses a small part of its container."
                        + " containerAreaRatio=" + areaRatio
                        + " expectedFitAreaRatio=" + expectedFitAreaRatio);
            }
        }
    }

    private void updateUIControls() {
        runOnUiThread(() -> {
            if (mIsCameraConnected) {
                mBinding.viewMainPreview.setVisibility(View.VISIBLE);
                mBinding.tvConnectUSBCameraTip.setVisibility(View.GONE);

                mBinding.fabPicture.setVisibility(View.VISIBLE);
                mBinding.fabVideo.setVisibility(View.VISIBLE);

                // Update record button
                int colorId = R.color.WHITE;
                if (mIsRecording) {
                    colorId = R.color.RED;
                }
                ColorStateList colorStateList = ColorStateList.valueOf(getResources().getColor(colorId));
                mBinding.fabVideo.setSupportImageTintList(colorStateList);

            } else {
                mBinding.viewMainPreview.setVisibility(View.GONE);
                mBinding.tvConnectUSBCameraTip.setVisibility(View.VISIBLE);

                mBinding.fabPicture.setVisibility(View.GONE);
                mBinding.fabVideo.setVisibility(View.GONE);

                mBinding.tvVideoRecordTime.setVisibility(View.GONE);
            }
            invalidateOptionsMenu();
        });
    }

    private Size getSavedPreviewSize() {
        String key = getString(R.string.saved_preview_size) + USBMonitor.getProductKey(mUsbDevice);
        String sizeStr = getPreferences(MODE_PRIVATE).getString(key, null);
        if (TextUtils.isEmpty(sizeStr)) {
            return null;
        }
        Gson gson = new Gson();
        return gson.fromJson(sizeStr, Size.class);
    }

    private void setSavedPreviewSize(Size size) {
        String key = getString(R.string.saved_preview_size) + USBMonitor.getProductKey(mUsbDevice);
        Gson gson = new Gson();
        String json = gson.toJson(size);
        getPreferences(MODE_PRIVATE)
                .edit()
                .putString(key, json)
                .apply();
    }

    private void setCustomImageCaptureConfig() {
//        mCameraHelper.setImageCaptureConfig(
//                mCameraHelper.getImageCaptureConfig().setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY));
        mCameraHelper.setImageCaptureConfig(
                mCameraHelper.getImageCaptureConfig().setJpegCompressionQuality(90));
    }

    public void takePicture() {
        if (mIsRecording) {
            return;
        }

        try {
            File file = new File(SaveHelper.getSavePhotoPath());
            ImageCapture.OutputFileOptions options =
                    new ImageCapture.OutputFileOptions.Builder(file).build();
            mCameraHelper.takePicture(options, new ImageCapture.OnImageCaptureCallback() {
                @Override
                public void onImageSaved(@NonNull ImageCapture.OutputFileResults outputFileResults) {
                    Toast.makeText(MainActivity.this,
                            "save \"" + UriHelper.getPath(MainActivity.this, outputFileResults.getSavedUri()) + "\"",
                            Toast.LENGTH_SHORT).show();
                }

                @Override
                public void onError(int imageCaptureError, @NonNull String message, @Nullable Throwable cause) {
                    Toast.makeText(MainActivity.this, message, Toast.LENGTH_SHORT).show();
                }
            });
        } catch (Exception e) {
            Log.e(TAG, e.getLocalizedMessage(), e);
        }
    }

    public void toggleVideoRecord(boolean isRecording) {
        try {
            if (isRecording) {
                if (mIsCameraConnected && mCameraHelper != null && !mCameraHelper.isRecording()) {
                    startRecord();
                }
            } else {
                if (mIsCameraConnected && mCameraHelper != null && mCameraHelper.isRecording()) {
                    stopRecord();
                }

                stopRecordTimer();
            }
        } catch (Exception e) {
            Log.e(TAG, e.getLocalizedMessage(), e);
            stopRecordTimer();
        }

        mIsRecording = isRecording;

        updateUIControls();
    }

    private void setCustomVideoCaptureConfig() {
        // this config only affects the recorded file; NDI/preview are independent
        mCameraHelper.setVideoCaptureConfig(
                mCameraHelper.getVideoCaptureConfig()
//                        .setAudioCaptureEnable(false) // disable audio if not needed
                        // bump bitrate up to max allowed by your device/network for best image quality
                        .setBitRate(30 * 1024 * 1024)   // ~30 Mbps
                        .setVideoFrameRate(30)
                        .setIFrameInterval(1));
    }

    private void startRecord() {
        File file = new File(SaveHelper.getSaveVideoPath());
        VideoCapture.OutputFileOptions options =
                new VideoCapture.OutputFileOptions.Builder(file).build();
        mCameraHelper.startRecording(options, new VideoCapture.OnVideoCaptureCallback() {
            @Override
            public void onStart() {
                startRecordTimer();
            }

            @Override
            public void onVideoSaved(@NonNull VideoCapture.OutputFileResults outputFileResults) {
                toggleVideoRecord(false);

                Toast.makeText(
                        MainActivity.this,
                        "save \"" + UriHelper.getPath(MainActivity.this, outputFileResults.getSavedUri()) + "\"",
                        Toast.LENGTH_SHORT).show();
            }

            @Override
            public void onError(int videoCaptureError, @NonNull String message, @Nullable Throwable cause) {
                toggleVideoRecord(false);

                Toast.makeText(MainActivity.this, message, Toast.LENGTH_LONG).show();
            }
        });
    }

    private void stopRecord() {
        mCameraHelper.stopRecording();
    }

    private void startRecordTimer() {
        runOnUiThread(() -> mBinding.tvVideoRecordTime.setVisibility(View.VISIBLE));

        // Set “00:00:00” to record time TextView
        setVideoRecordTimeText(formatTime(0));

        // Start Record Timer
        mRecordStartTime = SystemClock.elapsedRealtime();
        mRecordTimer = new Timer();
        //The timer is refreshed every quarter second
        mRecordTimer.scheduleAtFixedRate(new TimerTask() {
            @Override
            public void run() {
                long recordTime = (SystemClock.elapsedRealtime() - mRecordStartTime) / 1000;
                if (recordTime > 0) {
                    setVideoRecordTimeText(formatTime(recordTime));
                }
            }
        }, QUARTER_SECOND, QUARTER_SECOND);
    }

    private void stopRecordTimer() {
        runOnUiThread(() -> mBinding.tvVideoRecordTime.setVisibility(View.GONE));

        // Stop Record Timer
        mRecordStartTime = 0;
        if (mRecordTimer != null) {
            mRecordTimer.cancel();
            mRecordTimer = null;
        }
        // Set “00:00:00” to record time TextView
        setVideoRecordTimeText(formatTime(0));
    }

    private void setVideoRecordTimeText(String timeText) {
        runOnUiThread(() -> {
            mBinding.tvVideoRecordTime.setText(timeText);
        });
    }

    /**
     * 将秒转化为 HH:mm:ss 的格式
     *
     * @param time 秒
     * @return
     */
    private String formatTime(long time) {
        if (mDecimalFormat == null) {
            mDecimalFormat = new DecimalFormat("00");
        }
        String hh = mDecimalFormat.format(time / 3600);
        String mm = mDecimalFormat.format(time % 3600 / 60);
        String ss = mDecimalFormat.format(time % 60);
        return hh + ":" + mm + ":" + ss;
    }
}