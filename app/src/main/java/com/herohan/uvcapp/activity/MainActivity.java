package com.herohan.uvcapp.activity;

import android.Manifest;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Matrix;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.os.Bundle;
import android.app.AlertDialog;
import android.preference.PreferenceManager;
import android.util.Log;
import android.view.Surface;
import android.widget.EditText;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.google.gson.Gson;
import com.herohan.uvcapp.ImageCapture;
import com.herohan.uvcapp.InternalCameraHelper;
import com.herohan.uvcapp.InternalCameraInfo;
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
import com.herohan.uvcapp.fragment.InternalCameraListDialogFragment;
import com.herohan.uvcapp.fragment.InternalCameraResolutionDialogFragment;
import com.herohan.uvcapp.fragment.VideoFormatDialogFragment;
import com.herohan.uvcapp.utils.SaveHelper;
import com.herohan.uvcapp.CameraKeepAliveService;

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
import android.graphics.Color;
import android.os.Handler;

import java.io.File;
import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.text.DecimalFormat;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.List;
import java.util.Timer;
import java.util.TimerTask;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = MainActivity.class.getSimpleName();
    private static final boolean DEBUG = true;

    private static final String PREF_NDI_NAME = "pref_ndi_name";

    // remember camera input format used by the current forwarder (always nv12
    // in this app, but we track it so that we can recreate the forwarder when
    // renaming the sender).
    private String mNdiCameraFormat = "nv12";

    // cached stream name; defaults to saved preference or device name later
    private String mNdiSourceName;

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
    // start in efficient (NV12) mode instead of RGBA high‑quality; user can toggle later
    private boolean mNdiHighQuality = false;   // toggle state for NDI mode
    // RTP streaming removed per request; keep NDI only

    private CameraControlsDialogFragment mControlsDialog;
    private DeviceListDialogFragment mDeviceListDialog;
    private VideoFormatDialogFragment mFormatDialog;

    // ── Internal (phone) camera ──────────────────────────────────────────────
    private enum CameraMode { USB, INTERNAL }
    private CameraMode mCameraMode = CameraMode.USB;

    private InternalCameraHelper mInternalCameraHelper;
    private InternalCameraInfo   mCurrentInternalCamera;
    private android.util.Size    mInternalPreviewSize;
    // Pending reopen: when resolution changes, we close the current camera then open a new one
    // from onClosed() instead of racing with the close.
    private InternalCameraInfo   mPendingOpenCamera;
    private android.util.Size    mPendingOpenSize;

    private InternalCameraListDialogFragment       mInternalCameraListDialog;
    private InternalCameraResolutionDialogFragment mInternalResolutionDialog;
    // ────────────────────────────────────────────────────────────────────────

    // tally indicator state
    private View mTallyIndicator;
    private final Handler mHandler = new Handler();

    // keys used for saving instance state (if we ever switch to state restoration)
    private static final String KEY_USB_DEVICE_ID = "usb_device_id";
    private final Runnable mTallyPoller = new Runnable() {
        @Override
        public void run() {
            if (mNdiSender != null) {
                NdiSender.Tally t = mNdiSender.getTally();
                if (t != null && mTallyIndicator != null) {
                    if (t.program)       mTallyIndicator.setBackgroundColor(Color.RED);
                    else if (t.preview) mTallyIndicator.setBackgroundColor(Color.GREEN);
                    else                mTallyIndicator.setBackgroundColor(Color.GRAY);
                }
            }
            if (mNdiSender != null) {
                mHandler.postDelayed(this, 50);
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        mBinding = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(mBinding.getRoot());

        setSupportActionBar(mBinding.toolbar);

        // Assign tally indicator early so both USB and internal camera paths can use it
        mTallyIndicator = findViewById(R.id.tally_indicator);
        if (mTallyIndicator != null) {
            mTallyIndicator.setBackgroundColor(Color.GRAY);
        }

        checkCameraHelper();

        setListeners();

        // run-time prompt to ignore battery optimizations (optional)
        requestIgnoreBatteryOptimizations();

        // load previously saved NDI name (may be empty)
        mNdiSourceName = getSavedNdiName();

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

    private SurfaceTexture mDummySurfaceTexture;

    @Override
    protected void onStart() {
        super.onStart();

        // remove dummy surface if we added one while backgrounded
        if (mDummySurfaceTexture != null) {
            if (mCameraHelper != null) {
                mCameraHelper.removeSurface(mDummySurfaceTexture);
            }
            mDummySurfaceTexture.release();
            mDummySurfaceTexture = null;
        }
        initPreviewView();
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (mIsRecording) {
            toggleVideoRecord(false);
        }
        // keep the camera running by attaching an offscreen texture when the
        // UI goes away; this prevents the underlying UVCCamera from shutting
        // down due to lack of a surface.
        if (mIsCameraConnected && mCameraHelper != null && mDummySurfaceTexture == null) {
            mDummySurfaceTexture = new SurfaceTexture(0);
            mCameraHelper.addSurface(mDummySurfaceTexture, false);
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        clearCameraHelper();
        if (mInternalCameraHelper != null) {
            mInternalCameraHelper.closeCamera();
            mInternalCameraHelper = null;
        }
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
        } else if (id == R.id.action_set_ndi_name) {
            showSetNdiNameDialog();
        } else if (id == R.id.action_use_internal_camera) {
            switchToInternalCamera();
        } else if (id == R.id.action_use_usb_camera) {
            switchToUsbCamera();
        } else if (id == R.id.action_select_internal_camera) {
            showInternalCameraListDialog();
        } else if (id == R.id.action_internal_camera_resolution) {
            showInternalCameraResolutionDialog();
        }
        return true;
    }

    @Override
    public boolean onPrepareOptionsMenu(Menu menu) {
        final boolean usbMode      = mCameraMode == CameraMode.USB;
        final boolean internalMode = mCameraMode == CameraMode.INTERNAL;

        // ── UVC-only items ───────────────────────────────────────────────────
        if (mIsCameraConnected && usbMode) {
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

        // USB device icon — only in USB mode
        menu.findItem(R.id.action_device).setVisible(usbMode);

        // ── Labels for toggle items ──────────────────────────────────────────
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
        MenuItem nameItem = menu.findItem(R.id.action_set_ndi_name);
        if (nameItem != null) {
            nameItem.setVisible(mIsCameraConnected);
        }

        // ── Internal camera items ────────────────────────────────────────────
        menu.findItem(R.id.action_use_internal_camera).setVisible(usbMode);
        menu.findItem(R.id.action_use_usb_camera).setVisible(internalMode);
        menu.findItem(R.id.action_select_internal_camera).setVisible(internalMode);
        menu.findItem(R.id.action_internal_camera_resolution)
                .setVisible(internalMode && mIsCameraConnected);

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
    // note: default format will be NV12 (low-latency) since mNdiHighQuality=false
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
        // ensure we survive orientation changes by handling them ourselves
        // without letting Android destroy/recreate the activity.  the
        // manifest is updated accordingly, and this method will be invoked
        // again after a config change, so we recalc the transform.
        mBinding.viewMainPreview.setAspectRatio(mPreviewWidth, mPreviewHeight);
        mBinding.viewMainPreview.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            @Override
            public void onSurfaceTextureAvailable(@NonNull SurfaceTexture surface, int width, int height) {
                Log.i(TAG, "Preview surface available: " + width + "x" + height);
                if (mCameraMode == CameraMode.INTERNAL && mInternalCameraHelper != null) {
                    mInternalCameraHelper.startPreview(surface);
                    mBinding.viewMainPreview.post(() -> applyInternalCameraTransform());
                } else if (mCameraHelper != null) {
                    mCameraHelper.addSurface(surface, false);
                    Toast.makeText(MainActivity.this, "Preview: pinch to zoom, double-tap for 100%", Toast.LENGTH_SHORT).show();
                    mBinding.viewMainPreview.post(() -> {
                        applyPreviewFillTransform();
                        logPreviewDiagnostics("surface_available", mCameraHelper != null ? mCameraHelper.getPreviewSize() : null);
                    });
                }
            }

            @Override
            public void onSurfaceTextureSizeChanged(@NonNull SurfaceTexture surface, int width, int height) {
                Log.i(TAG, "Preview surface size changed: " + width + "x" + height);
                if (mCameraMode == CameraMode.INTERNAL) {
                    mBinding.viewMainPreview.post(() -> applyInternalCameraTransform());
                } else {
                    mBinding.viewMainPreview.post(() -> {
                        applyPreviewFillTransform();
                        logPreviewDiagnostics("surface_size_changed", mCameraHelper != null ? mCameraHelper.getPreviewSize() : null);
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
            }

            @Override
            public boolean onSurfaceTextureDestroyed(@NonNull SurfaceTexture surface) {
                if (mCameraMode == CameraMode.INTERNAL && mInternalCameraHelper != null) {
                    mInternalCameraHelper.stopPreview();
                } else if (mCameraHelper != null) {
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
            
            // start a foreground notification so that Android doesn't kill the
            // process when our activity goes to the background.  this is a
            // lightweight service that merely keeps the process alive while a
            // camera is opened.
            startKeepAliveService();
            
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
                    // choose stream name: preference first, otherwise derive it from
                    // the USB device.  using getProductName()/getManufacturerName
                    // usually yields the actual camera model rather than the generic
                    // device path which can look like "/dev/bus/usb/...".
                    String sourceName = mNdiSourceName;
                    if (TextUtils.isEmpty(sourceName)) {
                        sourceName = getDefaultNdiName(device);
                        // remember this default so future streams reuse it
                        setSavedNdiName(sourceName);
                    }
                    mNdiSender = new NdiSender(sourceName);
                    Log.i(TAG, "✅ NDI sender created: " + sourceName);
                    // start polling tally indicator
                    mHandler.post(mTallyPoller);

                    // create forwarder with known camera format (assume NV12)
                    mNdiCameraFormat = "nv12";
                    mFrameForwarder = new UvcNdiFrameForwarder(mNdiSender, mNdiCameraFormat, null);
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

            // stop the foreground service; no camera is active any more
            stopKeepAliveService();

            if (mIsRecording) {
                toggleVideoRecord(false);
            }

            // if we added an offscreen texture earlier, remove it now
            if (mDummySurfaceTexture != null) {
                try {
                    mCameraHelper.removeSurface(mDummySurfaceTexture);
                } catch (Exception ignored) {}
                mDummySurfaceTexture.release();
                mDummySurfaceTexture = null;
            }

            cleanupNdiAndStreaming();

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

            // make sure camera is fully torn down and UI is updated; the service
            // will already have removed the camera, but we rely on onCameraClose
            // to do the heavy lifting so request a close here too.
            if (device.equals(mUsbDevice)) {
                if (mCameraHelper != null) {
                    mCameraHelper.closeCamera();
                }
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
        // called after orientation changes as well
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

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        if (mCameraMode == CameraMode.INTERNAL) {
            mBinding.viewMainPreview.post(() -> {
                // Recalculate dimension swap for the new orientation
                if (mCurrentInternalCamera != null && mInternalPreviewSize != null) {
                    boolean swap = isSwapDimensionsNeeded(mCurrentInternalCamera.sensorOrientation, mCurrentInternalCamera.lensFacing);
                    int w = swap ? mInternalPreviewSize.getHeight() : mInternalPreviewSize.getWidth();
                    int h = swap ? mInternalPreviewSize.getWidth()  : mInternalPreviewSize.getHeight();
                    mPreviewWidth  = w;
                    mPreviewHeight = h;
                    mBinding.viewMainPreview.setAspectRatio(w, h);
                }
                applyInternalCameraTransform();
            });
        } else {
            // re-apply transform since the container dimensions have changed
            mBinding.viewMainPreview.post(this::applyPreviewFillTransform);
        }
    }

    /**
     * Helpers for keeping the process alive when the activity is backgrounded.
     */
    private void startKeepAliveService() {
        startService(new Intent(this, CameraKeepAliveService.class));
    }

    private void stopKeepAliveService() {
        stopService(new Intent(this, CameraKeepAliveService.class));
    }

    /**
     * If the device is subject to battery optimizations, prompt the user
     * to exclude our app.  Once the app is exempt the OS is much less
     * aggressive about throttling network access or background execution
     * when the screen is off.
     */
    private void requestIgnoreBatteryOptimizations() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            android.os.PowerManager pm = (android.os.PowerManager) getSystemService(POWER_SERVICE);
            if (pm != null && !pm.isIgnoringBatteryOptimizations(getPackageName())) {
                Intent intent = new Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
                intent.setData(android.net.Uri.parse("package:" + getPackageName()));
                startActivity(intent);
            }
        }
    }

    private void setSavedNdiName(final String name) {
        PreferenceManager
                .getDefaultSharedPreferences(this)
                .edit()
                .putString(PREF_NDI_NAME, name)
                .apply();
        mNdiSourceName = name;
    }

    private String getSavedNdiName() {
        return PreferenceManager
                .getDefaultSharedPreferences(this)
                .getString(PREF_NDI_NAME, "");
    }

    /**
     * Produce the default stream name.  per user request we now prefer the
     * phone itself (manufacturer/model) rather than the USB peripheral name.
     * If the build information is missing for some reason we still fall back
     * to the USB device name or a timestamp.
     */
    private String getDefaultNdiName(@Nullable UsbDevice device) {
        // first try to use the handset identity, since that's what was
        // requested
        String phoneName = android.os.Build.MANUFACTURER + " " + android.os.Build.MODEL;
        if (!TextUtils.isEmpty(phoneName) && !phoneName.trim().isEmpty()) {
            return phoneName.trim();
        }
        // if that somehow fails, try the connected device
        if (device != null) {
            String name = device.getProductName();
            if (!TextUtils.isEmpty(name)) return name;
            name = device.getManufacturerName();
            if (!TextUtils.isEmpty(name)) return name;
            name = device.getDeviceName();
            if (!TextUtils.isEmpty(name)) return name;
        }
        // final fallback
        return "UVCAndroid-" + mNdiStartTime;
    }

    private void showSetNdiNameDialog() {
        String current = getSavedNdiName();
        if (TextUtils.isEmpty(current) && mUsbDevice != null) {
            current = mUsbDevice.getDeviceName();
        }
        final EditText input = new EditText(this);
        input.setSingleLine();
        input.setText(current);
        new AlertDialog.Builder(this)
                .setTitle(R.string.action_set_ndi_name)
                .setView(input)
                .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                    String name = input.getText().toString().trim();
                    if (!TextUtils.isEmpty(name)) {
                        setSavedNdiName(name);
                        updateNdiSourceName(name);
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /**
     * Recreate the NDI sender with a new name while the camera is active.
     */
    private void updateNdiSourceName(final String newName) {
        if (mNdiSender != null) {
            try {
                stopTallyPolling();
                mNdiSender.close();
            } catch (Exception ignored) {}
            mNdiSender = new NdiSender(newName);
            if (mFrameForwarder != null) {
                mFrameForwarder = new UvcNdiFrameForwarder(mNdiSender, mNdiCameraFormat, null);
                mFrameForwarder.setFrameDimensions(mPreviewWidth, mPreviewHeight);
                setNdiFormat(mNdiHighQuality);
            }
        }
    }

    private void applyPreviewFillTransform() {
        // (no additional changes)
        // this is also invoked from onConfigurationChanged when the view
        // dimensions shift due to rotation
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
        // (no change) method left intact here
        // for debugging orientation/resizing issues
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

    private void stopTallyPolling() {
        mHandler.removeCallbacks(mTallyPoller);
        if (mTallyIndicator != null) {
            mTallyIndicator.setBackgroundColor(Color.GRAY);
        }
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

        if (mCameraMode == CameraMode.INTERNAL && mInternalCameraHelper != null) {
            File file = new File(SaveHelper.getSavePhotoPath());
            mInternalCameraHelper.takePicture(file, new InternalCameraHelper.OnPictureTakenListener() {
                @Override
                public void onSuccess(File f) {
                    Toast.makeText(MainActivity.this,
                            "Saved \"" + f.getAbsolutePath() + "\"", Toast.LENGTH_SHORT).show();
                }

                @Override
                public void onError(String message) {
                    Toast.makeText(MainActivity.this, message, Toast.LENGTH_SHORT).show();
                }
            });
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
        if (mCameraMode == CameraMode.INTERNAL) {
            toggleInternalVideoRecord(isRecording);
            return;
        }

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

    private void toggleInternalVideoRecord(boolean isRecording) {
        if (isRecording) {
            if (!mIsCameraConnected || mInternalCameraHelper == null
                    || mInternalCameraHelper.isRecording()) return;

            File file = new File(SaveHelper.getSaveVideoPath());
            int displayRotation = getWindowManager().getDefaultDisplay().getRotation();
            mInternalCameraHelper.startRecording(file, displayRotation,
                    new InternalCameraHelper.OnRecordingStateListener() {
                        @Override
                        public void onStarted() {
                            mIsRecording = true;
                            startRecordTimer();
                            updateUIControls();
                        }

                        @Override
                        public void onStopped(File f) {
                            mIsRecording = false;
                            stopRecordTimer();
                            updateUIControls();
                            Toast.makeText(MainActivity.this,
                                    "Saved \"" + f.getAbsolutePath() + "\"",
                                    Toast.LENGTH_SHORT).show();
                        }

                        @Override
                        public void onError(String message) {
                            mIsRecording = false;
                            stopRecordTimer();
                            updateUIControls();
                            Toast.makeText(MainActivity.this, message, Toast.LENGTH_LONG).show();
                        }
                    });
        } else {
            if (mInternalCameraHelper != null && mInternalCameraHelper.isRecording()) {
                mInternalCameraHelper.stopRecording();
            }
            mIsRecording = false;
            stopRecordTimer();
            updateUIControls();
        }
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

    // =========================================================================
    //  Internal (phone) camera — mode switching
    // =========================================================================

    private void switchToInternalCamera() {
        if (mCameraMode == CameraMode.INTERNAL) {
            showInternalCameraListDialog();
            return;
        }
        // Close any active USB camera
        if (mIsCameraConnected && mCameraHelper != null) {
            mCameraHelper.closeCamera();
            mIsCameraConnected = false;
        }
        mCameraMode = CameraMode.INTERNAL;
        mPreviewFillEnabled = false; // internal camera should show full frame (fit) not cropped fill
        resetPreviewViewState();
        updateUIControls();
        invalidateOptionsMenu();
        showInternalCameraListDialog();
    }

    private void switchToUsbCamera() {
        if (mCameraMode == CameraMode.USB) return;
        // Close any open internal camera
        if (mInternalCameraHelper != null) {
            mInternalCameraHelper.closeCamera();
        }
        mCameraMode            = CameraMode.USB;
        mCurrentInternalCamera = null;
        mInternalPreviewSize   = null;
        resetPreviewViewState();
        updateUIControls();
        invalidateOptionsMenu();
        Toast.makeText(this, "USB Camera mode — connect a USB camera",
                Toast.LENGTH_SHORT).show();
    }

    // =========================================================================
    //  Internal camera — list / resolution dialogs
    // =========================================================================

    private void showInternalCameraListDialog() {
        if (mInternalCameraListDialog != null && mInternalCameraListDialog.isAdded()) return;

        List<InternalCameraInfo> cameras = InternalCameraHelper.getAvailableCameras(this);
        mInternalCameraListDialog = new InternalCameraListDialogFragment(cameras);
        mInternalCameraListDialog.setOnCameraSelectedListener(cameraInfo -> {
            // Default to the best size at or below 1080p — very large preview sizes
            // (e.g. 4000×3000) can trigger ERROR_CAMERA_SERVICE on some devices.
            android.util.Size[] sizes =
                    InternalCameraHelper.getSupportedSizes(this, cameraInfo.cameraId);
            android.util.Size selectedSize = pickDefaultPreviewSize(sizes);
            openInternalCamera(cameraInfo, selectedSize);
        });
        mInternalCameraListDialog.show(getSupportFragmentManager(), "internal_camera_list");
    }

    private void showInternalCameraResolutionDialog() {
        if (mCurrentInternalCamera == null) return;
        if (mInternalResolutionDialog != null && mInternalResolutionDialog.isAdded()) return;

        android.util.Size[] sizesArr =
                InternalCameraHelper.getSupportedSizes(this, mCurrentInternalCamera.cameraId);
        List<android.util.Size> sizes = Arrays.asList(sizesArr);

        mInternalResolutionDialog = new InternalCameraResolutionDialogFragment(
                sizes, mInternalPreviewSize);
        mInternalResolutionDialog.setOnResolutionSelectedListener(size -> {
            if (mCurrentInternalCamera == null) return;
            // Store pending reopen; the actual openCamera call happens inside
            // InternalCameraStateCallback.onClosed() to avoid racing with the async close.
            mPendingOpenCamera = mCurrentInternalCamera;
            mPendingOpenSize   = size;
            if (mInternalCameraHelper != null) {
                mInternalCameraHelper.closeCamera();
            } else {
                InternalCameraInfo pending     = mPendingOpenCamera;
                android.util.Size  pendingSize = mPendingOpenSize;
                mPendingOpenCamera = null;
                mPendingOpenSize   = null;
                openInternalCamera(pending, pendingSize);
            }
        });
        mInternalResolutionDialog.show(getSupportFragmentManager(), "internal_resolution");
    }

    // =========================================================================
    //  Internal camera — open / orientation
    // =========================================================================

    private void openInternalCamera(InternalCameraInfo cameraInfo,
                                    android.util.Size previewSize) {
        // If a camera is already open, close it first and let onClosed() reopen via the
        // pending mechanism — ensures NDI resources are fully released before the new
        // NdiSender is created (same name would fail otherwise).
        if (mIsCameraConnected && mInternalCameraHelper != null) {
            mPendingOpenCamera = cameraInfo;
            mPendingOpenSize   = previewSize;
            mInternalCameraHelper.closeCamera();
            return;
        }

        mCurrentInternalCamera = cameraInfo;
        mInternalPreviewSize   = previewSize;

        // Choose view aspect ratio based on sensor orientation so the displayed
        // frame area matches the actual rotated buffer orientation.
        boolean swap  = isSwapDimensionsNeeded(cameraInfo.sensorOrientation, cameraInfo.lensFacing);
        int viewW = swap ? previewSize.getHeight() : previewSize.getWidth();
        int viewH = swap ? previewSize.getWidth()  : previewSize.getHeight();
        mPreviewWidth  = viewW;
        mPreviewHeight = viewH;
        mBinding.viewMainPreview.setAspectRatio(viewW, viewH);

        if (mInternalCameraHelper == null) {
            mInternalCameraHelper = new InternalCameraHelper(this);
            mInternalCameraHelper.setStateCallback(new InternalCameraStateCallback());
        }

        // Wire up frame delivery for NDI (set before openCamera so the reader is
        // included in the initial capture session)
        mInternalCameraHelper.setFrameListener((nv12Frame, width, height) -> {
            if (mFrameForwarder != null) {
                mFrameForwarder.onFrame(nv12Frame);
            }
        });

        XXPermissions.with(this)
                .permission(Manifest.permission.CAMERA)
                .request((permissions, all) ->
                        mInternalCameraHelper.openCamera(cameraInfo, previewSize));
    }

    /**
     * Picks the largest preview size whose area is at or below 1920×1080 pixels.
     * Very-large preview sizes (e.g. 4000×3000 from a 12 MP sensor) cause
     * ERROR_CAMERA_SERVICE on many devices because they exceed the camera service's
     * concurrent stream limits for SurfaceTexture outputs.
     */
    private static android.util.Size pickDefaultPreviewSize(android.util.Size[] sizes) {
        final int MAX_AREA = 1920 * 1080; // 1080p cap
        // sizes[] is sorted largest-area first by getSupportedSizes()
        for (android.util.Size s : sizes) {
            if (s.getWidth() * s.getHeight() <= MAX_AREA) {
                return s; // first (largest) size that fits
            }
        }
        // All reported sizes are above 1080p — just take the smallest
        return (sizes.length > 0)
                ? sizes[sizes.length - 1]
                : new android.util.Size(1280, 720);
    }

    /**
     * Returns true when the sensor's native buffer dimensions must be swapped in order
     * to show the image with the correct display orientation.
     */
    private boolean isSwapDimensionsNeeded(int sensorOrientation, int lensFacing) {
        int displayRotation = getWindowManager().getDefaultDisplay().getRotation();
        int displayDegrees  = displayRotation * 90;
        boolean isFront = (lensFacing == CameraCharacteristics.LENS_FACING_FRONT);
        int rotationNeeded = isFront
                ? (sensorOrientation + displayDegrees) % 360
                : (sensorOrientation - displayDegrees + 360) % 360;
        return rotationNeeded % 180 != 0;
    }

    /**
     * Clears all view-level transforms so switching between UVC and internal preview
     * starts from a clean state. UVC preview uses view scale/translation, while the
     * internal path uses TextureView content transforms; mixing them causes stretch.
     */
    private void resetPreviewViewState() {
        final com.serenegiant.widget.AspectRatioTextureView tv = mBinding.viewMainPreview;
        tv.setPivotX(tv.getWidth() * 0.5f);
        tv.setPivotY(tv.getHeight() * 0.5f);
        tv.setScaleX(1f);
        tv.setScaleY(1f);
        tv.setTranslationX(0f);
        tv.setTranslationY(0f);
        tv.setRotation(0f);
        tv.setTransform(new Matrix());
    }

    /**
     * Applies a {@link Matrix} to the TextureView so the Camera2 sensor buffer is
     * displayed correctly regardless of device orientation.
     */
    private void applyInternalCameraTransform() {
        if (mCurrentInternalCamera == null || mInternalPreviewSize == null) return;

        final com.serenegiant.widget.AspectRatioTextureView tv = mBinding.viewMainPreview;
        final int viewW = tv.getWidth();
        final int viewH = tv.getHeight();
        if (viewW <= 0 || viewH <= 0) {
            tv.post(this::applyInternalCameraTransform);
            return;
        }

        final int sensorOrientation   = mCurrentInternalCamera.sensorOrientation;
        final int displayRotation     = getWindowManager().getDefaultDisplay().getRotation();
        final int displayDegrees      = displayRotation * 90;
        final boolean isFront         =
                mCurrentInternalCamera.lensFacing == CameraCharacteristics.LENS_FACING_FRONT;

        final int rotationNeeded;
        if (isFront) {
            rotationNeeded = (sensorOrientation + displayDegrees) % 360;
        } else {
            rotationNeeded = (sensorOrientation - displayDegrees + 360) % 360;
        }

        final Matrix matrix = new Matrix();
        final float cx = viewW / 2f;
        final float cy = viewH / 2f;
        final int   bufW   = mInternalPreviewSize.getWidth();
        final int   bufH   = mInternalPreviewSize.getHeight();

        final int transformRotation = (rotationNeeded + 270) % 360;
        if (transformRotation % 180 != 0) {
            // 90° or 270° rotation needed.
            // Map view rect to buffer rect with width/height swapped.
            final android.graphics.RectF viewRect = new android.graphics.RectF(0f, 0f, viewW, viewH);
            final android.graphics.RectF bufferRect = new android.graphics.RectF(0f, 0f, bufH, bufW);
            bufferRect.offset(cx - bufferRect.centerX(), cy - bufferRect.centerY());
            matrix.setRectToRect(viewRect, bufferRect, Matrix.ScaleToFit.FILL);
            final float scale = Math.max((float) viewH / (float) bufH, (float) viewW / (float) bufW);
            matrix.postScale(scale, scale, cx, cy);
        }
        if (transformRotation != 0) {
            matrix.postRotate(transformRotation, cx, cy);
        }
        if (isFront) {
            matrix.postScale(-1f, 1f, cx, cy);
        }
        tv.setTransform(matrix);
        // apply fill transform (if enabled) to make preview as large as possible
        applyPreviewFillTransform();

        if (DEBUG) {
            Log.i(TAG, "[InternalCam] sensorOrient=" + sensorOrientation
                    + " displayRot=" + displayRotation
                    + " rotNeeded=" + rotationNeeded
                    + " transformRot=" + transformRotation
                    + " view=" + viewW + "x" + viewH
                    + " buf=" + bufW + "x" + bufH);
        }
    }

    // =========================================================================
    //  Internal camera — state callback
    // =========================================================================

    private class InternalCameraStateCallback
            implements InternalCameraHelper.OnCameraStateCallback {

        @Override
        public void onOpened(InternalCameraInfo cameraInfo, android.util.Size previewSize) {
            // Attach the TextureView surface so Camera2 can start streaming
            SurfaceTexture st = mBinding.viewMainPreview.getSurfaceTexture();
            if (st != null && mInternalCameraHelper != null) {
                mInternalCameraHelper.startPreview(st);
            }

            // Set up NDI streaming
            setupNdiForInternalCamera(cameraInfo, previewSize);

            startKeepAliveService();
            mIsCameraConnected = true;
            updateUIControls();
            mBinding.viewMainPreview.post(() -> applyInternalCameraTransform());
        }

        @Override
        public void onClosed(InternalCameraInfo cameraInfo) {
            // Always mark disconnected before doing pending reopen. This prevents the
            // openInternalCamera() path from thinking we're already connected and
            // re-entering close->open recursion.
            mIsCameraConnected = false;

            // If a resolution change (or camera switch) requested a reopen, do that
            // now that the device is fully closed — avoids racing with the async HAL cleanup.
            if (mPendingOpenCamera != null) {
                InternalCameraInfo pending     = mPendingOpenCamera;
                android.util.Size  pendingSize = mPendingOpenSize;
                mPendingOpenCamera = null;
                mPendingOpenSize   = null;
                // Must release old NdiSender before creating a new one
                cleanupNdiAndStreaming();
                openInternalCamera(pending, pendingSize);
                return;
            }

            stopKeepAliveService();
            if (mIsRecording) {
                mIsRecording = false;
                stopRecordTimer();
            }
            cleanupNdiAndStreaming();
            updateUIControls();
            closeAllDialogFragment();
        }

        @Override
        public void onError(InternalCameraInfo cameraInfo, String message) {
            Toast.makeText(MainActivity.this,
                    "Camera error: " + message, Toast.LENGTH_SHORT).show();
            // Discard the errored helper so the next openInternalCamera creates a fresh one.
            mInternalCameraHelper = null;
            mIsCameraConnected = false;
            updateUIControls();
        }
    }

    // =========================================================================
    //  NDI helpers shared by both USB and internal paths
    // =========================================================================

    private void setupNdiForInternalCamera(InternalCameraInfo cameraInfo,
                                           android.util.Size previewSize) {
        try {
            mNdiStartTime = SystemClock.elapsedRealtime();
            String sourceName = mNdiSourceName;
            if (TextUtils.isEmpty(sourceName)) {
                sourceName = android.os.Build.MANUFACTURER
                        + " " + android.os.Build.MODEL
                        + " — " + cameraInfo.displayName;
                setSavedNdiName(sourceName);
            }
            mNdiSender = new NdiSender(sourceName);
            mHandler.post(mTallyPoller);

            mNdiCameraFormat = "nv12";
            mFrameForwarder = new UvcNdiFrameForwarder(mNdiSender, mNdiCameraFormat, null);
            mFrameForwarder.setFrameDimensions(previewSize.getWidth(), previewSize.getHeight());
            Log.i(TAG, "NDI ready for internal camera: " + sourceName);
        } catch (Exception e) {
            Log.e(TAG, "Failed to set up NDI for internal camera", e);
            mNdiSender      = null;
            mFrameForwarder = null;
        }
    }

    /** Tears down NDI sender and frame forwarder — used by both camera paths. */
    private void cleanupNdiAndStreaming() {
        try {
            if (mCameraHelper != null) {
                mCameraHelper.setFrameCallback(null, 0);
            }
            if (mFrameForwarder != null) {
                mFrameForwarder = null;
            }
            if (mNdiSender != null) {
                stopTallyPolling();
                mNdiSender.close();
                mNdiSender = null;
            }
        } catch (Exception e) {
            Log.e(TAG, "Error stopping NDI", e);
        }
    }
}