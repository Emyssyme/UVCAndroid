package com.herohan.uvcapp.activity;

import android.Manifest;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Matrix;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CaptureRequest;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.os.Bundle;
import android.app.AlertDialog;
import android.preference.PreferenceManager;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.Surface;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.util.Range;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import com.google.android.material.bottomsheet.BottomSheetDialog;

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
import android.view.MotionEvent;
import android.view.TextureView;
import android.view.View;
import android.view.ViewParent;
import android.widget.Toast;
import android.graphics.Color;
import android.os.Handler;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.File;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.text.DecimalFormat;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.Locale;
import java.util.ArrayList;
import java.util.List;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = MainActivity.class.getSimpleName();
    private static final boolean DEBUG = true;

    private static final String PREF_NDI_NAME = "pref_ndi_name";

    // remember camera input format used by the current forwarder (always nv12
    // in this app, but we track it so that we can recreate the forwarder when
    // renaming the sender).
    private String mNdiCameraFormat = "nv12";

    // target frame rate for NDI forwarding (0 = passthrough all frames)
    private int mNdiTargetFps = 0;
    private long mNdiMinFrameIntervalNs = 0;
    private long mLastEnqueueFrameTimeNs = 0;

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
    private static final long[] PRO_SHUTTER_VALUES_NS = new long[] {
            1_000_000L, // 1/1000
            2_000_000L, // 1/500
            4_000_000L, // 1/250
            8_000_000L, // 1/125
            16_666_667L, // 1/60
            22_222_222L, // 1/45
            33_333_333L, // 1/30
            66_666_667L, // 1/15
            100_000_000L, // 1/10
            125_000_000L, // 1/8
            250_000_000L, // 1/4
            500_000_000L  // 1/2
    };

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

    // NDI frame queue + worker thread for NDI frame forwarding.
    // Uses a larger queue in “high-latency, stable” mode to reduce drops.
    private final ArrayBlockingQueue<java.nio.ByteBuffer> mNdiFrameQueue = new ArrayBlockingQueue<>(16);
    private final java.util.concurrent.atomic.AtomicReference<java.nio.ByteBuffer> mNdiReusableBuffer
            = new java.util.concurrent.atomic.AtomicReference<>();
    private final java.util.concurrent.atomic.AtomicReference<java.nio.ByteBuffer> mTcpReusableBuffer
            = new java.util.concurrent.atomic.AtomicReference<>();

    private volatile boolean mNdiWorkerRunning = false;
    private Thread mNdiWorkerThread;

    private long mLastQueueFullLog = 0;
    private static final long QUEUE_FULL_LOG_INTERVAL_MS = 500;

    private long mRecordStartTime = 0;
    private Timer mRecordTimer = null;
    private DecimalFormat mDecimalFormat;

    private boolean mIsRecording = false;
    private boolean mIsCameraConnected = false;
    private boolean mPreviewFillEnabled = false;
    // start in efficient (NV12) mode instead of RGBA high‑quality; user can toggle later
    private boolean mNdiHighQuality = false;   // toggle state for NDI mode
    // RTP streaming removed per request; keep NDI only

    private enum StreamProtocol { NDI, TCP_UDP }
    private StreamProtocol mStreamProtocol = StreamProtocol.NDI;
    private static final String PREF_STREAM_PROTOCOL = "pref_stream_protocol";
    private static final String PREF_STREAM_HOST = "pref_stream_host";
    private static final String PREF_STREAM_PORT = "pref_stream_port";
    private static final String PREF_VIDEO_TARGET_FPS = "pref_video_target_fps";
    private static final String PREF_VIDEO_QUALITY = "pref_video_quality";
    private static final int DEFAULT_STREAM_PORT = 5600;
    private static final int DISCOVERY_PORT = 8866;
    private static final int TCP_TALLY_PORT = 8867;
    private static final int DEFAULT_VIDEO_TARGET_FPS = 24;
    private static final int DEFAULT_VIDEO_QUALITY = 60;

    // H.265-over-TCP frame header constants (compatible with DroidCam OBS protocol)
    private static final long H264_NO_PTS = 0xFFFFFFFFFFFFFFFFL; // config/SPS+PPS marker

    private static class CustomUdpFrame {
        final java.nio.ByteBuffer frame;
        final int width;
        final int height;
        final long captureTimeNs;

        CustomUdpFrame(java.nio.ByteBuffer frame, int width, int height, long captureTimeNs) {
            this.frame = frame;
            this.width = width;
            this.height = height;
            this.captureTimeNs = captureTimeNs;
        }
    }

    private String mStreamHost;
    private int mStreamPort = DEFAULT_STREAM_PORT;
    private String mDeviceIp;
    private int mVideoTargetFps = DEFAULT_VIDEO_TARGET_FPS;
    private int mVideoQuality = DEFAULT_VIDEO_QUALITY;

    // H.265 TCP server
    private ServerSocket mH265ServerSocket;
    private volatile OutputStream mH264OutputStream;
    private MediaCodec mH264Encoder;
    private volatile int mH264EncoderWidth = 0;
    private volatile int mH264EncoderHeight = 0;
    private volatile byte[] mH264SpsPps;
    private volatile boolean mH264SyncFrameRequested = false;
    private final byte[] mH264FrameHeaderBuf = new byte[12];
    private byte[] mH264EncodeBuffer;          // reused NV12 input — allocated once per resolution
    private byte[] mH264OutputBuffer = new byte[2 * 1024 * 1024]; // reused encoded-packet buffer
    private Thread mTcpUdpWorkerThread;
    private volatile boolean mTcpUdpWorkerRunning = false;
    private final java.util.concurrent.ArrayBlockingQueue<CustomUdpFrame> mTcpUdpFrameQueue = new java.util.concurrent.ArrayBlockingQueue<>(6);
    private int mTcpUdpTargetFps = 30;
    private long mTcpUdpMinFrameIntervalNs = 0;
    private long mLastTcpUdpEnqueueTimeNs = 0;
    private long mNextTcpUdpEnqueueTimeNs = 0;
    private long mLastTcpFlushTimeNs = 0;
    private static final long TCP_FLUSH_INTERVAL_NS = 8_000_000L;
    private static final long TCP_DISCOVERY_INTERVAL_MS = 1000L;
    private final java.util.concurrent.atomic.AtomicLong mTcpFramesCaptured = new java.util.concurrent.atomic.AtomicLong();
    private final java.util.concurrent.atomic.AtomicLong mTcpFramesDropped = new java.util.concurrent.atomic.AtomicLong();
    private final java.util.concurrent.atomic.AtomicLong mTcpFramesEncoded = new java.util.concurrent.atomic.AtomicLong();
    private final java.util.concurrent.atomic.AtomicLong mTcpPacketsSent = new java.util.concurrent.atomic.AtomicLong();
    private final java.util.concurrent.atomic.AtomicLong mTcpEncodeTimeNsSum = new java.util.concurrent.atomic.AtomicLong();
    private final java.util.concurrent.atomic.AtomicLong mTcpEncodeSamples = new java.util.concurrent.atomic.AtomicLong();
    private final java.util.concurrent.atomic.AtomicLong mTcpQueueDepthMax = new java.util.concurrent.atomic.AtomicLong();
    private volatile long mTcpTelemetryLastUiNs = 0;
    private volatile long mTcpTelemetryLastLogNs = 0;
    private volatile long mTcpTelemetryPrevCaptured = 0;
    private volatile long mTcpTelemetryPrevDropped = 0;
    private volatile long mTcpTelemetryPrevEncoded = 0;
    private volatile long mTcpTelemetryPrevSent = 0;
    // TCP tally state is reported by OBS plugin over UDP backchannel.
    private volatile boolean mTcpObsProgram = false;
    private volatile boolean mTcpObsPreview = false;
    private volatile long mTcpLastTallyUpdateNs = 0;
    private volatile String mTcpTallyRemoteHost = null;
    private volatile String mLastSentControlStatePayload = "";
    private volatile long mLastSentControlStateNs = 0;
    private volatile String mLastProcessedTcpControlPayload = "";
    private volatile long mLastProcessedTcpControlNs = 0;
    private static final long TCP_TALLY_STALE_TIMEOUT_MS = 1500;
    private Thread mTcpDiscoveryThread;
    private volatile boolean mTcpDiscoveryRunning = false;
    private Thread mTcpTallyListenerThread;
    private volatile boolean mTcpTallyListenerRunning = false;

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

    private boolean mInternalExposureLock = false;
    private boolean mInternalAeLock = false;
    private boolean mInternalFocusLock = false;
    private int mInternalFocusLockPreviousAfMode = CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO;
    private boolean mInternalFocusLockPreviousTapToFocus = false;
    private int mInternalAfMode = CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO;
    private boolean mInternalAfLock = false;
    private int mInternalAfLockPreviousAfMode = CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO;
    private boolean mInternalAfLockPreviousTapToFocus = false;
    private int mInternalFlashMode = CaptureRequest.FLASH_MODE_OFF;
    private int mInternalWbMode = CaptureRequest.CONTROL_AWB_MODE_AUTO;
    private int mInternalWbKelvin = 4500;

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
            // Tally for NDI
            if (mNdiSender != null) {
                NdiSender.Tally t = mNdiSender.getTally();
                if (t != null && mTallyIndicator != null) {
                    if (t.program)       mTallyIndicator.setBackgroundColor(Color.RED);
                    else if (t.preview) mTallyIndicator.setBackgroundColor(Color.GREEN);
                    else                mTallyIndicator.setBackgroundColor(Color.GRAY);
                }
            }
            // Tally for TCP/UDP: reflect OBS state sent by plugin (program/preview/none).
            else if (mStreamProtocol == StreamProtocol.TCP_UDP && mTcpUdpWorkerRunning) {
                long nowNs = System.nanoTime();
                long staleMs = (nowNs - mTcpLastTallyUpdateNs) / 1_000_000;
                int color = Color.GRAY;
                if (staleMs < TCP_TALLY_STALE_TIMEOUT_MS) {
                    if (mTcpObsProgram) {
                        color = Color.RED;
                    } else if (mTcpObsPreview) {
                        color = Color.GREEN;
                    }
                }
                if (mTallyIndicator != null) {
                    mTallyIndicator.setBackgroundColor(color);
                }
            }
            // Schedule next poll if any transport is active
            if (mNdiSender != null || (mStreamProtocol == StreamProtocol.TCP_UDP && mTcpUdpWorkerRunning)) {
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
        mStreamProtocol = getSavedStreamProtocol();
        mStreamHost = getSavedStreamHost();
        mStreamPort = getSavedStreamPort();
        mVideoTargetFps = getSavedVideoTargetFps();
        mVideoQuality = getSavedVideoQuality();
        mDeviceIp = getLocalIpAddress();
        updateStreamStatus();

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

    private final Runnable mHideTapFocusMarker = () -> runOnUiThread(() -> {
        if (mBinding != null) {
            mBinding.tapFocusMarker.setVisibility(View.GONE);
        }
    });

    private void showTapFocusMarker(float x, float y) {
        runOnUiThread(() -> {
            if (mBinding == null || mBinding.tapFocusMarker == null) return;
            int markerW = mBinding.tapFocusMarker.getWidth();
            int markerH = mBinding.tapFocusMarker.getHeight();
            if (markerW == 0 || markerH == 0) {
                markerW = (int) getResources().getDisplayMetrics().density * 48;
                markerH = markerW;
            }
            mBinding.tapFocusMarker.setX(x - markerW / 2f);
            mBinding.tapFocusMarker.setY(y - markerH / 2f);
            mBinding.tapFocusMarker.setVisibility(View.VISIBLE);
            mHandler.removeCallbacks(mHideTapFocusMarker);
            mHandler.postDelayed(mHideTapFocusMarker, 700);
        });
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
            if (mCameraMode == CameraMode.INTERNAL) {
                boolean visible = mBinding.internalCameraControls.getVisibility() == View.VISIBLE;
                mBinding.internalCameraControls.setVisibility(visible ? View.GONE : View.VISIBLE);
                mBinding.internalCameraQuickButtons.setVisibility(visible ? View.GONE : View.VISIBLE);
                if (!visible) updateInternalCameraControlPanel();
            } else {
                showCameraControlsDialog();
            }
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
        } else if (id == R.id.action_stream_protocol) {
            final StreamProtocol nextProtocol = (mStreamProtocol == StreamProtocol.NDI)
                    ? StreamProtocol.TCP_UDP
                    : StreamProtocol.NDI;
            if (nextProtocol == StreamProtocol.TCP_UDP && !hasCustomTransportDestination()) {
                showSetStreamDestinationDialog();
                return true;
            }
            setSavedStreamProtocol(nextProtocol);
            invalidateOptionsMenu();
            if (mIsCameraConnected) {
                if (mCameraMode == CameraMode.USB && mUsbDevice != null && mCameraHelper != null) {
                    final Size size = mCameraHelper.getPreviewSize();
                    if (size != null) {
                        cleanupNdiAndStreaming();
                        if (nextProtocol == StreamProtocol.NDI) {
                            mNdiSourceName = getSavedNdiName();
                            try {
                                mNdiStartTime = SystemClock.elapsedRealtime();
                                String sourceName = TextUtils.isEmpty(mNdiSourceName)
                                        ? getDefaultNdiName(mUsbDevice)
                                        : mNdiSourceName;
                                if (TextUtils.isEmpty(mNdiSourceName)) {
                                    setSavedNdiName(sourceName);
                                }
                                mNdiSender = new NdiSender(sourceName);
                                mHandler.post(mTallyPoller);
                                mNdiCameraFormat = "nv12";
                                mFrameForwarder = new UvcNdiFrameForwarder(mNdiSender, mNdiCameraFormat, null);
                                mFrameForwarder.setFrameDimensions(size.width, size.height);
                                setNdiTargetFps(25);
                                setNdiFormat(mNdiHighQuality);
                                startNdiForwardingThread();
                            } catch (Exception e) {
                                Log.e(TAG, "❌ Failed to create NDI sender", e);
                            }
                        } else {
                            setupTcpUdpForUsbCamera(mUsbDevice, size);
                        }
                    }
                } else if (mCameraMode == CameraMode.INTERNAL && mCurrentInternalCamera != null && mInternalPreviewSize != null) {
                    cleanupNdiAndStreaming();
                    if (nextProtocol == StreamProtocol.NDI) {
                        setupNdiForInternalCamera(mCurrentInternalCamera, mInternalPreviewSize);
                    } else {
                        setupTcpUdpForInternalCamera(mCurrentInternalCamera, mInternalPreviewSize);
                    }
                }
                if (mCameraHelper != null && mMultiCallback != null) {
                    try {
                        mCameraHelper.setFrameCallback(mMultiCallback, UVCCamera.PIXEL_FORMAT_NV12);
                    } catch (Exception e) {
                        Log.e(TAG, "Failed to restore frame callback after protocol switch", e);
                    }
                }
            }
            Toast.makeText(this,
                    nextProtocol == StreamProtocol.NDI
                            ? getString(R.string.action_stream_protocol_ndi)
                            : getString(R.string.action_stream_protocol_tcp_udp),
                    Toast.LENGTH_SHORT).show();
        } else if (id == R.id.action_set_stream_destination) {
            showSetStreamDestinationDialog();
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
            menu.findItem(R.id.action_stream_protocol).setVisible(true);
            menu.findItem(R.id.action_ndimode).setVisible(mStreamProtocol == StreamProtocol.NDI);
        } else if (mIsCameraConnected && internalMode) {
            menu.findItem(R.id.action_control).setVisible(true);
            menu.findItem(R.id.action_safely_eject).setVisible(false);
            menu.findItem(R.id.action_video_format).setVisible(false);
            menu.findItem(R.id.action_rotate_90_CW).setVisible(false);
            menu.findItem(R.id.action_rotate_90_CCW).setVisible(false);
            menu.findItem(R.id.action_flip_horizontally).setVisible(false);
            menu.findItem(R.id.action_flip_vertically).setVisible(false);
            menu.findItem(R.id.action_preview_fill_toggle).setVisible(false);
            menu.findItem(R.id.action_stream_protocol).setVisible(true);
            menu.findItem(R.id.action_ndimode).setVisible(mStreamProtocol == StreamProtocol.NDI);
        } else {
            menu.findItem(R.id.action_safely_eject).setVisible(false);
            menu.findItem(R.id.action_video_format).setVisible(false);
            menu.findItem(R.id.action_rotate_90_CW).setVisible(false);
            menu.findItem(R.id.action_rotate_90_CCW).setVisible(false);
            menu.findItem(R.id.action_flip_horizontally).setVisible(false);
            menu.findItem(R.id.action_flip_vertically).setVisible(false);
            menu.findItem(R.id.action_preview_fill_toggle).setVisible(false);
            menu.findItem(R.id.action_stream_protocol).setVisible(true);
            menu.findItem(R.id.action_ndimode).setVisible(false);
        }

        // internal and USB preview can use control UI toggle
        menu.findItem(R.id.action_control).setVisible(usbMode || internalMode);
        menu.findItem(R.id.action_set_stream_destination).setVisible(isCustomTransportActive());

        // USB device icon — only in USB mode
        menu.findItem(R.id.action_device).setVisible(usbMode);

        // ── Labels for toggle items ──────────────────────────────────────────
        final MenuItem previewModeItem = menu.findItem(R.id.action_preview_fill_toggle);
        if (previewModeItem != null) {
            previewModeItem.setTitle(mPreviewFillEnabled
                    ? R.string.action_preview_mode_fill
                    : R.string.action_preview_mode_fit);
        }
        final MenuItem streamProtocolItem = menu.findItem(R.id.action_stream_protocol);
        if (streamProtocolItem != null) {
            streamProtocolItem.setTitle(mStreamProtocol == StreamProtocol.NDI
                    ? R.string.action_stream_protocol_ndi
                    : R.string.action_stream_protocol_tcp_udp);
            streamProtocolItem.setVisible(true);
        }
        final MenuItem destinationItem = menu.findItem(R.id.action_set_stream_destination);
        if (destinationItem != null) {
            destinationItem.setVisible(true);
        }
        final MenuItem ndiModeItem = menu.findItem(R.id.action_ndimode);
        if (ndiModeItem != null) {
            ndiModeItem.setTitle(mNdiHighQuality
                    ? R.string.action_ndimode_high
                    : R.string.action_ndimode_low);
            ndiModeItem.setVisible(mIsCameraConnected && mStreamProtocol == StreamProtocol.NDI);
        }
        MenuItem nameItem = menu.findItem(R.id.action_set_ndi_name);
        if (nameItem != null) {
            nameItem.setVisible(mIsCameraConnected && mStreamProtocol == StreamProtocol.NDI);
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

        setupInternalCameraControlPanel();
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

    private void setNdiTargetFps(final int fps) {
        // Enforce minimum 25 fps, even for 4K, to keep smooth output at a fixed bound.
        mNdiTargetFps = Math.max(25, fps);
        mNdiMinFrameIntervalNs = 1_000_000_000L / mNdiTargetFps;
        if (mFrameForwarder != null) {
            mFrameForwarder.setTargetFps(mNdiTargetFps);
        }
        Log.i(TAG, "NDI target FPS set to " + mNdiTargetFps);
    }

    // PROMOTE incoming UVC frames to the selected transport.
    // note: default format will be NV12 (low-latency) since mNdiHighQuality=false
    private class MultiFrameCallback implements com.serenegiant.usb.IFrameCallback {
        @Override
        public void onFrame(java.nio.ByteBuffer frame) {
            if (frame == null) {
                Log.w(TAG, "MultiFrameCallback: null frame");
                return;
            }
            if (mStreamProtocol == StreamProtocol.NDI) {
                enqueueNdiFrame(frame);
            } else {
                enqueueTcpUdpFrame(frame, mPreviewWidth, mPreviewHeight);
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
        mBinding.viewMainPreview.setOnTouchListener((v, event) -> {
            if (mCameraMode == CameraMode.INTERNAL && mInternalCameraHelper != null && mInternalCameraHelper.isTapToFocusMode()) {
                if (event.getAction() == MotionEvent.ACTION_UP) {
                    mInternalCameraHelper.triggerTapToFocus(event.getX(), event.getY(), v.getWidth(), v.getHeight());
                    showTapFocusMarker(event.getX(), event.getY());
                }
                return true;
            }
            return false;
        });

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
            
            // ✅ Step 2: Setup the selected transport layer
            if (size != null) {
                if (mStreamProtocol == StreamProtocol.NDI) {
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
                        // Always target 25 fps for stable output (latency may grow, but fluidity is guaranteed).
                        setNdiTargetFps(25);
                        // now apply quality mode (will register callback)
                        setNdiFormat(mNdiHighQuality);
                        startNdiForwardingThread();
                    } catch (Exception e) {
                        Log.e(TAG, "❌ Failed to create NDI sender", e);
                        mNdiSender = null;
                        mFrameForwarder = null;
                    }
                } else {
                    setupTcpUdpForUsbCamera(device, size);
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

    private StreamProtocol getSavedStreamProtocol() {
        final String protocol = PreferenceManager
                .getDefaultSharedPreferences(this)
                .getString(PREF_STREAM_PROTOCOL, StreamProtocol.NDI.name());
        try {
            return StreamProtocol.valueOf(protocol);
        } catch (IllegalArgumentException e) {
            return StreamProtocol.NDI;
        }
    }

    private void setSavedStreamProtocol(final StreamProtocol protocol) {
        if (protocol == null) return;
        PreferenceManager
                .getDefaultSharedPreferences(this)
                .edit()
                .putString(PREF_STREAM_PROTOCOL, protocol.name())
                .apply();
        mStreamProtocol = protocol;
    }

    private String getSavedStreamHost() {
        return PreferenceManager
                .getDefaultSharedPreferences(this)
                .getString(PREF_STREAM_HOST, "");
    }

    private int getSavedStreamPort() {
        return PreferenceManager
                .getDefaultSharedPreferences(this)
                .getInt(PREF_STREAM_PORT, DEFAULT_STREAM_PORT);
    }

    private int getSavedVideoTargetFps() {
        int fps = PreferenceManager
                .getDefaultSharedPreferences(this)
                .getInt(PREF_VIDEO_TARGET_FPS, DEFAULT_VIDEO_TARGET_FPS);
        return Math.max(24, Math.min(60, fps));
    }

    private int getSavedVideoQuality() {
        return PreferenceManager
                .getDefaultSharedPreferences(this)
                .getInt(PREF_VIDEO_QUALITY, DEFAULT_VIDEO_QUALITY);
    }

    private void setSavedVideoTargetFps(int fps) {
        fps = Math.max(24, Math.min(60, fps));
        PreferenceManager
                .getDefaultSharedPreferences(this)
                .edit()
                .putInt(PREF_VIDEO_TARGET_FPS, fps)
                .apply();
        mVideoTargetFps = fps;
    }

    private void setSavedVideoQuality(int quality) {
        PreferenceManager
                .getDefaultSharedPreferences(this)
                .edit()
                .putInt(PREF_VIDEO_QUALITY, quality)
                .apply();
        mVideoQuality = quality;
    }

    private String getLocalIpAddress() {
        try {
            for (Enumeration<NetworkInterface> en = NetworkInterface.getNetworkInterfaces(); en.hasMoreElements(); ) {
                NetworkInterface intf = en.nextElement();
                if (!intf.isUp() || intf.isLoopback() || intf.isVirtual()) {
                    continue;
                }
                for (Enumeration<InetAddress> enumIpAddr = intf.getInetAddresses(); enumIpAddr.hasMoreElements(); ) {
                    InetAddress inetAddress = enumIpAddr.nextElement();
                    if (!inetAddress.isLoopbackAddress() && inetAddress instanceof Inet4Address) {
                        return inetAddress.getHostAddress();
                    }
                }
            }
        } catch (SocketException e) {
            Log.w(TAG, "Failed to detect local IP address", e);
        }
        return "";
    }

    private void setSavedStreamDestination(final String host, final int port) {
        if (port <= 0 || port > 65535) return;
        PreferenceManager
                .getDefaultSharedPreferences(this)
                .edit()
                .putInt(PREF_STREAM_PORT, port)
                .apply();
        mStreamHost = mDeviceIp;
        mStreamPort = port;
        updateStreamStatus();
    }

    private boolean hasCustomTransportDestination() {
        return mStreamPort > 0;
    }

    private void showSetStreamDestinationDialog() {
        final android.widget.LinearLayout container = new android.widget.LinearLayout(this);
        container.setOrientation(android.widget.LinearLayout.VERTICAL);
        int padding = (int) (getResources().getDisplayMetrics().density * 16);
        container.setPadding(padding, padding, padding, padding);

        final EditText hostInput = new EditText(this);
        hostInput.setHint(getString(R.string.stream_destination_host_hint));
        hostInput.setText(mDeviceIp != null ? mDeviceIp : "");
        hostInput.setEnabled(false);
        hostInput.setFocusable(false);
        hostInput.setFocusableInTouchMode(false);
        container.addView(hostInput);

        final EditText portInput = new EditText(this);
        portInput.setHint(getString(R.string.stream_destination_port_hint));
        portInput.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        portInput.setText(String.valueOf(mStreamPort));
        container.addView(portInput);

        final EditText fpsInput = new EditText(this);
        fpsInput.setHint("Target FPS (24-60)");
        fpsInput.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        fpsInput.setText(String.valueOf(mVideoTargetFps));
        container.addView(fpsInput);

        final EditText qualityInput = new EditText(this);
        qualityInput.setHint("Quality 10-100");
        qualityInput.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        qualityInput.setText(String.valueOf(mVideoQuality));
        container.addView(qualityInput);

        new AlertDialog.Builder(this)
                .setTitle(R.string.stream_destination_title)
                .setView(container)
                .setPositiveButton(R.string.stream_destination_set, (dialog, which) -> {
                    String host = mDeviceIp != null ? mDeviceIp : "";
                    int port = DEFAULT_STREAM_PORT;
                    int targetFps = DEFAULT_VIDEO_TARGET_FPS;
                    int quality = DEFAULT_VIDEO_QUALITY;
                    try {
                        port = Integer.parseInt(portInput.getText().toString().trim());
                    } catch (NumberFormatException ignored) {
                    }
                    try {
                        targetFps = Integer.parseInt(fpsInput.getText().toString().trim());
                    } catch (NumberFormatException ignored) {
                    }
                    try {
                        quality = Integer.parseInt(qualityInput.getText().toString().trim());
                    } catch (NumberFormatException ignored) {
                    }
                    targetFps = Math.max(24, Math.min(60, targetFps));
                    quality = Math.max(10, Math.min(100, quality));

                    if (TextUtils.isEmpty(host) || port <= 0 || port > 65535) {
                        Toast.makeText(this, R.string.stream_destination_error, Toast.LENGTH_SHORT).show();
                        return;
                    }
                    setSavedStreamDestination(host, port);
                    setSavedVideoTargetFps(targetFps);
                    setSavedVideoQuality(quality);
                    Toast.makeText(this, String.format("%s:%d fps=%d quality=%d", host, port, targetFps, quality), Toast.LENGTH_SHORT).show();
                    invalidateOptionsMenu();
                    if (mStreamProtocol == StreamProtocol.TCP_UDP && mIsCameraConnected) {
                        cleanupNdiAndStreaming();
                        if (mCameraMode == CameraMode.USB && mUsbDevice != null && mCameraHelper != null) {
                            Size size = mCameraHelper.getPreviewSize();
                            if (size != null) {
                                setupTcpUdpForUsbCamera(mUsbDevice, size);
                            }
                        } else if (mCameraMode == CameraMode.INTERNAL && mCurrentInternalCamera != null && mInternalPreviewSize != null) {
                            setupTcpUdpForInternalCamera(mCurrentInternalCamera, mInternalPreviewSize);
                        }
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void updateStreamStatus() {
        if (mBinding == null || mBinding.tvStreamStatus == null) {
            return;
        }
        if (mStreamProtocol == StreamProtocol.NDI) {
            mBinding.tvStreamStatus.setText(R.string.stream_status_ndi);
        } else if (mStreamProtocol == StreamProtocol.TCP_UDP) {
            if (hasCustomTransportDestination()) {
                String base = "H.265 TCP server: 0.0.0.0:" + mStreamPort
                        + " @ " + mVideoTargetFps + " fps"
                        + " q=" + mVideoQuality;
                mBinding.tvStreamStatus.setText(base + "\n" + getTcpTelemetryOverlay());
            } else {
                mBinding.tvStreamStatus.setText(R.string.stream_status_no_destination);
            }
        } else {
            mBinding.tvStreamStatus.setText(R.string.stream_status_inactive);
        }
    }

    private boolean isCustomTransportActive() {
        return mStreamProtocol == StreamProtocol.TCP_UDP;
    }

    private String getTcpTelemetryOverlay() {
        long captured = mTcpFramesCaptured.get();
        long dropped = mTcpFramesDropped.get();
        long encoded = mTcpFramesEncoded.get();
        long sent = mTcpPacketsSent.get();
        long maxQ = mTcpQueueDepthMax.get();
        long samples = mTcpEncodeSamples.get();
        double avgEncMs = samples > 0
                ? (mTcpEncodeTimeNsSum.get() / 1_000_000.0) / (double) samples
                : 0.0;
        return String.format(Locale.US,
                "cap=%d drop=%d enc=%d sent=%d q=%d/%d encAvg=%.2fms",
                captured,
                dropped,
                encoded,
                sent,
                mTcpUdpFrameQueue.size(),
                Math.max(maxQ, 1),
                avgEncMs);
    }

    private void resetTcpTelemetry() {
        mTcpFramesCaptured.set(0);
        mTcpFramesDropped.set(0);
        mTcpFramesEncoded.set(0);
        mTcpPacketsSent.set(0);
        mTcpEncodeTimeNsSum.set(0);
        mTcpEncodeSamples.set(0);
        mTcpQueueDepthMax.set(0);
        mTcpTelemetryLastUiNs = 0;
        mTcpTelemetryLastLogNs = 0;
        mTcpTelemetryPrevCaptured = 0;
        mTcpTelemetryPrevDropped = 0;
        mTcpTelemetryPrevEncoded = 0;
        mTcpTelemetryPrevSent = 0;
    }

    private void noteTcpQueueDepth() {
        long depth = mTcpUdpFrameQueue.size();
        while (true) {
            long cur = mTcpQueueDepthMax.get();
            if (depth <= cur || mTcpQueueDepthMax.compareAndSet(cur, depth)) {
                break;
            }
        }
    }

    private void maybePublishTcpTelemetry(boolean forceUi) {
        long nowNs = System.nanoTime();
        if (forceUi || nowNs - mTcpTelemetryLastUiNs >= 500_000_000L) {
            mTcpTelemetryLastUiNs = nowNs;
            runOnUiThread(this::updateStreamStatus);
        }
        if (nowNs - mTcpTelemetryLastLogNs >= 1_000_000_000L) {
            mTcpTelemetryLastLogNs = nowNs;
            long c = mTcpFramesCaptured.get();
            long d = mTcpFramesDropped.get();
            long e = mTcpFramesEncoded.get();
            long s = mTcpPacketsSent.get();
            long dc = c - mTcpTelemetryPrevCaptured;
            long dd = d - mTcpTelemetryPrevDropped;
            long de = e - mTcpTelemetryPrevEncoded;
            long ds = s - mTcpTelemetryPrevSent;
            mTcpTelemetryPrevCaptured = c;
            mTcpTelemetryPrevDropped = d;
            mTcpTelemetryPrevEncoded = e;
            mTcpTelemetryPrevSent = s;
            Log.i(TAG, "TCP telemetry/s cap=" + dc
                    + " drop=" + dd
                    + " enc=" + de
                    + " sent=" + ds
                    + " q=" + mTcpUdpFrameQueue.size()
                    + " maxQ=" + mTcpQueueDepthMax.get()
                    + " avgEncMs=" + String.format(Locale.US, "%.2f",
                    mTcpEncodeSamples.get() > 0
                            ? (mTcpEncodeTimeNsSum.get() / 1_000_000.0) / (double) mTcpEncodeSamples.get()
                            : 0.0));
        }
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
            boolean internalShown = mCameraMode == CameraMode.INTERNAL && mIsCameraConnected;

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

            mBinding.internalCameraControls.setVisibility(View.GONE);
            mBinding.internalCameraQuickButtons.setVisibility(View.GONE);
            mBinding.internalCameraBottomBar.setVisibility(internalShown ? View.VISIBLE : View.GONE);
            if (internalShown) {
                updateInternalCameraControlPanel();
            }

            invalidateOptionsMenu();
        });
    }

    private void setupInternalCameraControlPanel() {
        mBinding.seekbarInternalZoom.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (mInternalCameraHelper == null) return;
                float maxZoom = Math.max(1.0f, mInternalCameraHelper.getMaxDigitalZoom());
                float zoom = 1.0f + (maxZoom - 1.0f) * progress / 100f;
                mInternalCameraHelper.setCurrentZoom(zoom);
                mBinding.tvInternalZoomValue.setText(getString(R.string.internal_camera_zoom_label, zoom));
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });

        mBinding.seekbarInternalExposure.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (mInternalCameraHelper == null) return;
                Range<Integer> range = mInternalCameraHelper.getAeCompensationRange();
                if (range == null) return;
                int value = range.getLower() + Math.round((range.getUpper() - range.getLower()) * (progress / 100f));
                mInternalCameraHelper.setExposureCompensation(value);
                mBinding.tvInternalExposureValue.setText(getString(R.string.internal_camera_exposure_label, value));
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });

        mBinding.seekbarInternalKelvin.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (mInternalCameraHelper == null) return;
                int kelvin = 2000 + Math.round(8000 * (progress / 100f));
                mInternalCameraHelper.setAwbTemperatureKelvin(kelvin);
                mBinding.tvInternalKelvinValue.setText("Kelvin: " + kelvin + "K");
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });

        mBinding.seekbarInternalFocus.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (mInternalCameraHelper == null) return;
                float maxFocus = mInternalCameraHelper.getMinFocusDistance();
                float focus = maxFocus * progress / 100f;
                mInternalCameraHelper.setFocusDistance(focus);
                String focusText = (progress == 0) ? "infinity" : String.format(Locale.US, "%.2f", focus);
                mBinding.tvInternalFocusValue.setText(getString(R.string.internal_camera_focus_label, focusText));
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });

        mBinding.seekbarInternalIso.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (mInternalCameraHelper == null) return;
                Range<Integer> range = mInternalCameraHelper.getSensitivityRange();
                if (range == null) return;
                int iso = range.getLower() + Math.round((range.getUpper() - range.getLower()) * (progress / 100f));
                mInternalCameraHelper.setSensitivity(iso);
                mBinding.tvInternalIsoValue.setText(getString(R.string.internal_camera_iso_label, iso));
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });

        mBinding.seekbarInternalExposureTime.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (mInternalCameraHelper == null) return;
                int idx = Math.round((PRO_SHUTTER_VALUES_NS.length - 1) * progress / 100f);
                idx = Math.max(0, Math.min(PRO_SHUTTER_VALUES_NS.length - 1, idx));
                long exposure = PRO_SHUTTER_VALUES_NS[idx];
                mInternalCameraHelper.setExposureTime(exposure);
                mBinding.tvInternalExposureTimeValue.setText(getString(R.string.internal_camera_exposure_time_label, formatExposureTime(exposure)));
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });

        mBinding.btnInternalAeToggle.setOnClickListener(v -> {
            if (mInternalCameraHelper == null) return;
            boolean nextAuto = !mInternalCameraHelper.isAeAuto();
            mInternalCameraHelper.setAeAuto(nextAuto);
            mBinding.btnInternalAeToggle.setText(nextAuto ? getString(R.string.internal_camera_ae_auto) : getString(R.string.internal_camera_ae_off));
            mBinding.btnQuickAe.setText(nextAuto ? "AE Auto" : "AE Manual");
            mBinding.tvInternalExposureMode.setText(nextAuto ? getString(R.string.internal_camera_exposure_mode_auto) : getString(R.string.internal_camera_exposure_mode_manual));
        });

        mBinding.btnQuickAe.setOnClickListener(v -> {
            if (mInternalCameraHelper == null) return;
            boolean nextAuto = !mInternalCameraHelper.isAeAuto();
            mInternalCameraHelper.setAeAuto(nextAuto);
            mBinding.btnInternalAeToggle.setText(nextAuto ? getString(R.string.internal_camera_ae_auto) : getString(R.string.internal_camera_ae_off));
            mBinding.btnQuickAe.setText(nextAuto ? "AE Auto" : "AE Manual");
            mBinding.tvInternalExposureMode.setText(nextAuto ? getString(R.string.internal_camera_exposure_mode_auto) : getString(R.string.internal_camera_exposure_mode_manual));
        });

        mBinding.btnInternalAwbToggle.setOnClickListener(v -> {
            if (mInternalCameraHelper == null) return;
            boolean awbAuto = !mInternalCameraHelper.isAwbAuto();
            mInternalCameraHelper.setAwbAuto(awbAuto);
            mInternalWbMode = awbAuto ? CaptureRequest.CONTROL_AWB_MODE_AUTO : CaptureRequest.CONTROL_AWB_MODE_OFF;
            mBinding.btnInternalAwbToggle.setText(awbAuto ? getString(R.string.internal_camera_awb_auto) : getString(R.string.internal_camera_awb_off));
            mBinding.btnQuickAwb.setText(awbAuto ? "AWB Auto" : "AWB Manual");
            mBinding.tvInternalKelvinValue.setVisibility(awbAuto ? View.GONE : View.VISIBLE);
            mBinding.seekbarInternalKelvin.setVisibility(awbAuto ? View.GONE : View.VISIBLE);
        });

        mBinding.btnQuickAwb.setOnClickListener(v -> {
            if (mInternalCameraHelper == null) return;
            boolean awbAuto = !mInternalCameraHelper.isAwbAuto();
            mInternalCameraHelper.setAwbAuto(awbAuto);
            mInternalWbMode = awbAuto ? CaptureRequest.CONTROL_AWB_MODE_AUTO : CaptureRequest.CONTROL_AWB_MODE_OFF;
            mBinding.btnInternalAwbToggle.setText(awbAuto ? getString(R.string.internal_camera_awb_auto) : getString(R.string.internal_camera_awb_off));
            mBinding.btnQuickAwb.setText(awbAuto ? "AWB Auto" : "AWB Manual");
            mBinding.tvInternalKelvinValue.setVisibility(awbAuto ? View.GONE : View.VISIBLE);
            mBinding.seekbarInternalKelvin.setVisibility(awbAuto ? View.GONE : View.VISIBLE);
        });

        mBinding.btnInternalAfToggle.setOnClickListener(v -> {
            if (mInternalCameraHelper == null) return;
            if (mInternalCameraHelper.isTapToFocusMode()) {
                mInternalCameraHelper.setTapToFocusMode(false);
                mInternalCameraHelper.setAfMode(CaptureRequest.CONTROL_AF_MODE_OFF);
                mInternalAfMode = 0;
                mBinding.btnInternalAfToggle.setText("AF: Off");
            } else if (mInternalCameraHelper.getAfMode() == CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO) {
                mInternalCameraHelper.setTapToFocusMode(true);
                mInternalAfMode = 3;
                mBinding.btnInternalAfToggle.setText("AF: Tap");
            } else {
                mInternalCameraHelper.setTapToFocusMode(false);
                mInternalCameraHelper.setAfMode(CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO);
                mInternalAfMode = 2;
                mBinding.btnInternalAfToggle.setText("AF: Auto");
            }
            mBinding.btnQuickAf.setText(mInternalCameraHelper.isTapToFocusMode() ? "AF Tap" :
                    (mInternalCameraHelper.getAfMode() == CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO ? "AF Auto" : "AF Off"));
        });

        mBinding.btnQuickAf.setOnClickListener(v -> {
            if (mInternalCameraHelper == null) return;
            if (mInternalCameraHelper.isTapToFocusMode()) {
                mInternalCameraHelper.setTapToFocusMode(false);
                mInternalCameraHelper.setAfMode(CaptureRequest.CONTROL_AF_MODE_OFF);
                mInternalAfMode = 0;
                mBinding.btnQuickAf.setText("AF Off");
                mBinding.btnInternalAfToggle.setText("AF: Off");
            } else if (mInternalCameraHelper.getAfMode() == CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO) {
                mInternalCameraHelper.setTapToFocusMode(true);
                mInternalAfMode = 3;
                mBinding.btnQuickAf.setText("AF Tap");
                mBinding.btnInternalAfToggle.setText("AF: Tap");
            } else {
                mInternalCameraHelper.setTapToFocusMode(false);
                mInternalCameraHelper.setAfMode(CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO);
                mInternalAfMode = 2;
                mBinding.btnQuickAf.setText("AF Auto");
                mBinding.btnInternalAfToggle.setText("AF: Auto");
            }
        });

        mBinding.btnBottomSelectCamera.setOnClickListener(v -> showInternalCameraListDialog());
        mBinding.btnBottomExposure.setOnClickListener(v -> showExposureDialog());
        mBinding.btnBottomExposureLock.setOnClickListener(v -> {
            mInternalExposureLock = !mInternalExposureLock;
            if (mInternalCameraHelper != null && mCameraMode == CameraMode.INTERNAL) {
                if (mInternalExposureLock) {
                    if (!mInternalCameraHelper.isAeAuto()) {
                        mInternalCameraHelper.setAeAuto(true);
                    }
                    int currentCompensation = mInternalCameraHelper.getCurrentExposureCompensation();
                    mInternalCameraHelper.setExposureCompensation(currentCompensation);
                    mInternalCameraHelper.setAeLock(true);
                } else {
                    mInternalCameraHelper.setAeLock(false);
                }
            }
            updateInternalCameraControlPanel();
        });
        mBinding.btnBottomAfMode.setOnClickListener(v -> showAfModeDialog());
        mBinding.btnBottomFocusLock.setOnClickListener(v -> setInternalFocusLock(!mInternalFocusLock));
        mBinding.btnBottomFlash.setOnClickListener(v -> showFlashDialog());
        mBinding.btnBottomWhiteBalance.setOnClickListener(v -> showWhiteBalanceDialog());
    }

    private void updateInternalCameraControlPanel() {
        if (mInternalCameraHelper == null) return;

        float maxZoom = Math.max(1.0f, mInternalCameraHelper.getMaxDigitalZoom());
        mBinding.seekbarInternalZoom.setProgress((int) ((mInternalCameraHelper.getCurrentZoom() - 1.0f) / (maxZoom - 1.0f) * 100));
        mBinding.tvInternalZoomValue.setText(getString(R.string.internal_camera_zoom_label, mInternalCameraHelper.getCurrentZoom()));

        Range<Integer> aeRange = mInternalCameraHelper.getAeCompensationRange();
        if (aeRange != null && aeRange.getUpper() > aeRange.getLower()) {
            int exposureValue = mInternalCameraHelper.getCurrentExposureCompensation();
            int progress = (int) ((exposureValue - aeRange.getLower()) / (float) (aeRange.getUpper() - aeRange.getLower()) * 100f);
            mBinding.seekbarInternalExposure.setProgress(progress);
            mBinding.tvInternalExposureValue.setText(getString(R.string.internal_camera_exposure_label, exposureValue));
            mBinding.seekbarInternalExposure.setEnabled(true);
        } else {
            mBinding.seekbarInternalExposure.setProgress(50);
            mBinding.tvInternalExposureValue.setText(getString(R.string.internal_camera_exposure_label, 0));
            mBinding.seekbarInternalExposure.setEnabled(false);
        }

        float minFocus = mInternalCameraHelper.getMinFocusDistance();
        mBinding.seekbarInternalFocus.setProgress((int) ((mInternalCameraHelper.getCurrentFocusDistance() / Math.max(minFocus, 1f)) * 100));
        String focusText = (mInternalCameraHelper.getCurrentFocusDistance() <= 0f || minFocus <= 0f) ? "infinity" : String.format("%.2f", mInternalCameraHelper.getCurrentFocusDistance());
        mBinding.tvInternalFocusValue.setText(getString(R.string.internal_camera_focus_label, focusText));

        Range<Integer> isoRange = mInternalCameraHelper.getSensitivityRange();
        if (isoRange != null && isoRange.getUpper() > isoRange.getLower()) {
            int iso = mInternalCameraHelper.getCurrentSensitivity();
            int progress = (int) ((iso - isoRange.getLower()) / (float) (isoRange.getUpper() - isoRange.getLower()) * 100f);
            mBinding.seekbarInternalIso.setProgress(progress);
            mBinding.tvInternalIsoValue.setText(getString(R.string.internal_camera_iso_label, iso));
        }

        long exp = mInternalCameraHelper.getCurrentExposureTime();
        int idx = 0;
        for (int i = 0; i < PRO_SHUTTER_VALUES_NS.length; i++) {
            if (exp >= PRO_SHUTTER_VALUES_NS[i]) idx = i;
        }
        mBinding.seekbarInternalExposureTime.setProgress((int) (idx * 100f / (PRO_SHUTTER_VALUES_NS.length - 1)));
        mBinding.tvInternalExposureTimeValue.setText(getString(R.string.internal_camera_exposure_time_label, formatExposureTime(exp)));

        boolean aeAuto = mInternalCameraHelper.isAeAuto();
        mBinding.btnInternalAeToggle.setText(aeAuto ? getString(R.string.internal_camera_ae_auto) : getString(R.string.internal_camera_ae_off));
        mBinding.btnQuickAe.setText(aeAuto ? "AE Auto" : "AE Manual");
        mBinding.tvInternalExposureMode.setText(aeAuto ? getString(R.string.internal_camera_exposure_mode_auto) : getString(R.string.internal_camera_exposure_mode_manual));

        boolean awbAuto = mInternalCameraHelper.isAwbAuto();
        mBinding.btnInternalAwbToggle.setText(awbAuto ? getString(R.string.internal_camera_awb_auto) : getString(R.string.internal_camera_awb_off));
        mBinding.btnQuickAwb.setText(awbAuto ? "AWB Auto" : "AWB Manual");
        mBinding.btnBottomWhiteBalance.setText("WB");
        mBinding.tvInternalKelvinValue.setVisibility(awbAuto ? View.GONE : View.VISIBLE);
        mBinding.seekbarInternalKelvin.setVisibility(awbAuto ? View.GONE : View.VISIBLE);
        if (!awbAuto) {
            int kelvin = mInternalCameraHelper.getAwbTemperatureKelvin();
            mBinding.tvInternalKelvinValue.setText("Kelvin: " + kelvin + "K");
            mBinding.seekbarInternalKelvin.setProgress((kelvin - 2000) * 100 / 8000);
        }

        int afMode = mInternalCameraHelper.getAfMode();
        if (mInternalCameraHelper.isTapToFocusMode()) {
            mBinding.btnInternalAfToggle.setText("AF: Tap");
            mBinding.btnQuickAf.setText("AF Tap");
            mBinding.btnBottomAfMode.setText(mInternalAfLock ? "AF Locked" : "AF Tap");
        } else if (afMode == CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO) {
            mBinding.btnInternalAfToggle.setText("AF: Auto");
            mBinding.btnQuickAf.setText("AF Auto");
            mBinding.btnBottomAfMode.setText(mInternalAfLock ? "AF Locked" : "AF Auto");
        } else {
            mBinding.btnInternalAfToggle.setText("AF: Off");
            mBinding.btnQuickAf.setText("AF Off");
            mBinding.btnBottomAfMode.setText(mInternalAfLock ? "AF Locked" : "AF Off");
        }

        mBinding.btnBottomExposure.setText("EV");
        mBinding.btnBottomExposureLock.setText("EV-L");
        mBinding.btnBottomAfMode.setText("AF");
        mBinding.btnBottomFocusLock.setText("AF-L");
        mBinding.btnBottomWhiteBalance.setText("WB");
        mBinding.btnBottomFlash.setText("FL");
        mBinding.btnBottomExposureLock.setSelected(mInternalExposureLock);
        mBinding.btnBottomFocusLock.setSelected(mInternalFocusLock);
        mBinding.btnBottomExposure.setEnabled(!mInternalExposureLock);
        mBinding.btnBottomAfMode.setEnabled(!mInternalAfLock);
        int activeColor = Color.parseColor("#FFD54F");
        int inactiveColor = Color.parseColor("#FFFFFF");
        int activeBackground = Color.parseColor("#88FFC107");
        int inactiveBackground = Color.parseColor("#55000000");
        mBinding.btnBottomExposureLock.setTextColor(mInternalExposureLock ? activeColor : inactiveColor);
        mBinding.btnBottomFocusLock.setTextColor(mInternalFocusLock ? activeColor : inactiveColor);
        mBinding.btnBottomExposureLock.setBackgroundTintList(android.content.res.ColorStateList.valueOf(mInternalExposureLock ? activeBackground : inactiveBackground));
        mBinding.btnBottomFocusLock.setBackgroundTintList(android.content.res.ColorStateList.valueOf(mInternalFocusLock ? activeBackground : inactiveBackground));
    }

    private void showExposureDialog() {
        if (mInternalCameraHelper == null) return;

        Range<Integer> aeRange = mInternalCameraHelper.getAeCompensationRange();
        if (aeRange == null) return;

        int current = mInternalCameraHelper.getCurrentExposureCompensation();
        int min = aeRange.getLower();
        int max = aeRange.getUpper();

        BottomSheetDialog dialog = new BottomSheetDialog(this);
        dialog.setCancelable(true);
        dialog.setCanceledOnTouchOutside(true);

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(16, 12, 16, 12);
        layout.setGravity(android.view.Gravity.START);
        layout.setLayoutParams(new android.widget.LinearLayout.LayoutParams(android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView label = new TextView(this);
        label.setText(getString(R.string.internal_camera_exposure_label, current));
        label.setTextColor(getResources().getColor(R.color.white));
        label.setLayoutParams(new android.widget.LinearLayout.LayoutParams(android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT));
        layout.addView(label);

        SeekBar seekBar = new SeekBar(this);
        seekBar.setMax(max - min);
        seekBar.setProgress(current - min);
        seekBar.setLayoutParams(new android.widget.LinearLayout.LayoutParams(android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT));
        layout.addView(seekBar);

        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                int value = min + progress;
                label.setText(getString(R.string.internal_camera_exposure_label, value));
                if (fromUser && mInternalCameraHelper != null) {
                    mInternalCameraHelper.setExposureCompensation(value);
                    updateInternalCameraControlPanel();
                }
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });

        dialog.setContentView(layout);
        dialog.setOnShowListener(dialogInterface -> {
            android.view.Window window = ((BottomSheetDialog)dialogInterface).getWindow();
            if (window != null) {
                window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            }
        });
        dialog.show();
    }

    private void showAfModeDialog() {
        if (mInternalCameraHelper == null) return;

        String[] labels = {"Off", "Auto", "Continuous", "Tap", "Infinity", "Macro"};
        int[] modes = {
            CaptureRequest.CONTROL_AF_MODE_OFF,
            CaptureRequest.CONTROL_AF_MODE_AUTO,
            CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO,
            100,
            101,
            102
        };

        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Autofocus Mode");
        builder.setItems(labels, (dialog, which) -> {
            if (which == 3) {
                mInternalCameraHelper.setTapToFocusMode(true);
                mInternalAfMode = 3;
            } else if (which == 4) {
                mInternalCameraHelper.setTapToFocusMode(false);
                mInternalCameraHelper.setAfMode(CaptureRequest.CONTROL_AF_MODE_OFF);
                mInternalCameraHelper.setFocusDistance(0f);
                mInternalAfMode = 4;
            } else if (which == 5) {
                mInternalCameraHelper.setTapToFocusMode(false);
                mInternalCameraHelper.setAfMode(CaptureRequest.CONTROL_AF_MODE_OFF);
                mInternalCameraHelper.setFocusDistance(mInternalCameraHelper.getMinFocusDistance());
                mInternalAfMode = 5;
            } else {
                mInternalCameraHelper.setTapToFocusMode(false);
                mInternalCameraHelper.setAfMode(modes[which]);
                mInternalAfMode = which;
            }
            updateInternalCameraControlPanel();
        });
        builder.show();
    }

    private void showFlashDialog() {
        String[] labels = {"Auto", "On", "Off", "Torch"};
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Flash Mode");
        builder.setItems(labels, (dialog, which) -> {
            switch (which) {
                case 0:
                    mInternalCameraHelper.setAeAuto(true);
                    mInternalCameraHelper.setFlashMode(CaptureRequest.FLASH_MODE_OFF);
                    mInternalFlashMode = CaptureRequest.FLASH_MODE_OFF;
                    break;
                case 1:
                    mInternalCameraHelper.setAeAuto(true);
                    mInternalCameraHelper.setFlashMode(CaptureRequest.FLASH_MODE_SINGLE);
                    mInternalFlashMode = CaptureRequest.FLASH_MODE_SINGLE;
                    break;
                case 2:
                    mInternalCameraHelper.setAeAuto(true);
                    mInternalCameraHelper.setFlashMode(CaptureRequest.FLASH_MODE_OFF);
                    mInternalFlashMode = CaptureRequest.FLASH_MODE_OFF;
                    break;
                case 3:
                    mInternalCameraHelper.setAeAuto(true);
                    mInternalCameraHelper.setFlashMode(CaptureRequest.FLASH_MODE_TORCH);
                    mInternalFlashMode = CaptureRequest.FLASH_MODE_TORCH;
                    break;
            }
            updateInternalCameraControlPanel();
        });
        builder.show();
    }

    private void showWhiteBalanceDialog() {
        String[] labels = {"Auto", "Incandescent", "Fluorescent", "Daylight", "Cloudy", "Shade", "Kelvin"};
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("White Balance");
        builder.setItems(labels, (dialog, which) -> {
            switch (which) {
                case 0:
                    mInternalCameraHelper.setAwbAuto(true);
                    mInternalWbMode = CaptureRequest.CONTROL_AWB_MODE_AUTO;
                    break;
                case 1:
                    mInternalCameraHelper.setAwbMode(CaptureRequest.CONTROL_AWB_MODE_INCANDESCENT);
                    mInternalWbMode = CaptureRequest.CONTROL_AWB_MODE_INCANDESCENT;
                    break;
                case 2:
                    mInternalCameraHelper.setAwbMode(CaptureRequest.CONTROL_AWB_MODE_FLUORESCENT);
                    mInternalWbMode = CaptureRequest.CONTROL_AWB_MODE_FLUORESCENT;
                    break;
                case 3:
                    mInternalCameraHelper.setAwbMode(CaptureRequest.CONTROL_AWB_MODE_DAYLIGHT);
                    mInternalWbMode = CaptureRequest.CONTROL_AWB_MODE_DAYLIGHT;
                    break;
                case 4:
                    mInternalCameraHelper.setAwbMode(CaptureRequest.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT);
                    mInternalWbMode = CaptureRequest.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT;
                    break;
                case 5:
                    mInternalCameraHelper.setAwbMode(CaptureRequest.CONTROL_AWB_MODE_SHADE);
                    mInternalWbMode = CaptureRequest.CONTROL_AWB_MODE_SHADE;
                    break;
                case 6:
                    mInternalWbMode = CaptureRequest.CONTROL_AWB_MODE_OFF;
                    mInternalCameraHelper.setAwbAuto(false);
                    mInternalCameraHelper.setAwbMode(CaptureRequest.CONTROL_AWB_MODE_OFF);
                    showWhiteBalanceKelvinDialog();
                    return;
            }
            updateInternalCameraControlPanel();
        });
        builder.show();
    }

    private void showWhiteBalanceKelvinDialog() {
        if (mInternalCameraHelper == null) return;

        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("White Balance Kelvin");

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(32, 24, 32, 0);

        TextView label = new TextView(this);
        label.setText("Kelvin: " + mInternalWbKelvin + "K");
        label.setTextColor(getResources().getColor(R.color.white));
        layout.addView(label);

        SeekBar seekBar = new SeekBar(this);
        seekBar.setMax(9000);
        seekBar.setProgress(mInternalWbKelvin - 1000);
        layout.addView(seekBar);

        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                int kelvin = 1000 + progress;
                label.setText("Kelvin: " + kelvin + "K");
                if (fromUser && mInternalCameraHelper != null) {
                    mInternalWbKelvin = kelvin;
                    mInternalCameraHelper.setAwbTemperatureKelvin(mInternalWbKelvin);
                    updateInternalCameraControlPanel();
                }
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });

        builder.setView(layout);
        builder.setNegativeButton("Cancel", null);
        builder.show();
    }

    private void processTcpControlMessage(String msg) {
        long nowNs = System.nanoTime();
        if (msg != null && msg.equals(mLastProcessedTcpControlPayload)
                && (nowNs - mLastProcessedTcpControlNs) < 1_000_000_000L) {
            return;
        }
        mLastProcessedTcpControlPayload = msg != null ? msg : "";
        mLastProcessedTcpControlNs = nowNs;

        boolean exposureLock = msg.contains("exposure_lock=1");
        boolean focusLock = msg.contains("focus_lock=1");
        boolean afLock = msg.contains("af_lock=1");
        int parsedExposureCompensation = 0;
        int parsedAfMode = -1;
        int parsedFlashMode = -1;
        int parsedWbMode = -1;
        int parsedWbKelvin = mInternalWbKelvin;

        String[] parts = msg.split(";");
        for (String part : parts) {
            if (part.startsWith("exposure_compensation=")) {
                try {
                    parsedExposureCompensation = Integer.parseInt(part.substring(part.indexOf('=') + 1));
                } catch (NumberFormatException ignored) {
                }
            } else if (part.startsWith("af_mode=")) {
                try {
                    parsedAfMode = Integer.parseInt(part.substring(part.indexOf('=') + 1));
                } catch (NumberFormatException ignored) {
                }
            } else if (part.startsWith("flash_mode=")) {
                try {
                    parsedFlashMode = Integer.parseInt(part.substring(part.indexOf('=') + 1));
                } catch (NumberFormatException ignored) {
                }
            } else if (part.startsWith("wb_mode=")) {
                try {
                    parsedWbMode = Integer.parseInt(part.substring(part.indexOf('=') + 1));
                } catch (NumberFormatException ignored) {
                }
            } else if (part.startsWith("wb_kelvin=")) {
                try {
                    parsedWbKelvin = Integer.parseInt(part.substring(part.indexOf('=') + 1));
                } catch (NumberFormatException ignored) {
                }
            }
        }

        final int exposureCompensation = parsedExposureCompensation;
        final int afMode = parsedAfMode;
        final int flashMode = parsedFlashMode;
        final int wbMode = parsedWbMode;
        final int wbKelvin = parsedWbKelvin;

        runOnUiThread(() -> {
            mInternalExposureLock = exposureLock;
            mInternalFocusLock = focusLock;
            mInternalAfLock = afLock;
            if (mInternalCameraHelper != null && mCameraMode == CameraMode.INTERNAL) {
                mInternalCameraHelper.setPreviewUpdatesSuspended(true);
                try {
                    if (mInternalExposureLock) {
                        if (!mInternalCameraHelper.isAeAuto()) {
                            mInternalCameraHelper.setAeAuto(true);
                        }
                        if (mInternalCameraHelper.isAeAuto()) {
                            mInternalCameraHelper.setExposureCompensation(exposureCompensation);
                        }
                        mInternalCameraHelper.setAeLock(true);
                    } else {
                        mInternalCameraHelper.setAeLock(false);
                    }
                    if (afMode >= 0) {
                        if (afMode == 3) { // Tap
                            mInternalCameraHelper.setTapToFocusMode(true);
                        } else if (afMode == 4) { // Infinity
                            mInternalCameraHelper.setTapToFocusMode(false);
                            mInternalCameraHelper.setAfMode(CaptureRequest.CONTROL_AF_MODE_OFF);
                            mInternalCameraHelper.setFocusDistance(0f);
                        } else if (afMode == 5) { // Macro
                            mInternalCameraHelper.setTapToFocusMode(false);
                            mInternalCameraHelper.setAfMode(CaptureRequest.CONTROL_AF_MODE_OFF);
                            mInternalCameraHelper.setFocusDistance(mInternalCameraHelper.getMinFocusDistance());
                        } else if (afMode == 2) { // Continuous — OBS index 2 → Camera2 CONTINUOUS_VIDEO=3
                            mInternalCameraHelper.setTapToFocusMode(false);
                            mInternalCameraHelper.setAfMode(CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO);
                        } else { // 0=Off, 1=Auto — Camera2 OFF=0, AUTO=1
                            mInternalCameraHelper.setTapToFocusMode(false);
                            mInternalCameraHelper.setAfMode(afMode);
                        }
                        mInternalAfMode = afMode;
                    }
                    if (flashMode >= 0) {
                        // OBS plugin: 0=Auto, 1=On, 2=Off, 3=Torch
                        // Camera2 FLASH_MODE: 0=OFF, 1=SINGLE, 2=TORCH
                        final int camera2FlashMode;
                        switch (flashMode) {
                            case 1:  camera2FlashMode = CaptureRequest.FLASH_MODE_SINGLE; break; // On
                            case 3:  camera2FlashMode = CaptureRequest.FLASH_MODE_TORCH;  break; // Torch
                            default: camera2FlashMode = CaptureRequest.FLASH_MODE_OFF;    break; // Auto or Off
                        }
                        mInternalFlashMode = camera2FlashMode;
                        mInternalCameraHelper.setFlashMode(camera2FlashMode);
                    }
                    if (wbMode >= 0) {
                        // OBS plugin wb_mode: 0=Auto,1=Incandescent,2=Fluorescent,3=Daylight,4=Cloudy,5=Shade,6=Kelvin
                        // Camera2 CONTROL_AWB_MODE: AUTO=1,INCANDESCENT=2,FLUORESCENT=3,DAYLIGHT=5,CLOUDY_DAYLIGHT=6,SHADE=8
                        switch (wbMode) {
                            case 0: // Auto
                                mInternalWbMode = CaptureRequest.CONTROL_AWB_MODE_AUTO;
                                mInternalCameraHelper.setAwbAuto(true);
                                break;
                            case 1: // Incandescent
                                mInternalWbMode = CaptureRequest.CONTROL_AWB_MODE_INCANDESCENT;
                                mInternalCameraHelper.setAwbAuto(false);
                                mInternalCameraHelper.setAwbMode(CaptureRequest.CONTROL_AWB_MODE_INCANDESCENT);
                                break;
                            case 2: // Fluorescent
                                mInternalWbMode = CaptureRequest.CONTROL_AWB_MODE_FLUORESCENT;
                                mInternalCameraHelper.setAwbAuto(false);
                                mInternalCameraHelper.setAwbMode(CaptureRequest.CONTROL_AWB_MODE_FLUORESCENT);
                                break;
                            case 3: // Daylight
                                mInternalWbMode = CaptureRequest.CONTROL_AWB_MODE_DAYLIGHT;
                                mInternalCameraHelper.setAwbAuto(false);
                                mInternalCameraHelper.setAwbMode(CaptureRequest.CONTROL_AWB_MODE_DAYLIGHT);
                                break;
                            case 4: // Cloudy
                                mInternalWbMode = CaptureRequest.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT;
                                mInternalCameraHelper.setAwbAuto(false);
                                mInternalCameraHelper.setAwbMode(CaptureRequest.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT);
                                break;
                            case 5: // Shade
                                mInternalWbMode = CaptureRequest.CONTROL_AWB_MODE_SHADE;
                                mInternalCameraHelper.setAwbAuto(false);
                                mInternalCameraHelper.setAwbMode(CaptureRequest.CONTROL_AWB_MODE_SHADE);
                                break;
                            case 6: // Kelvin (custom temperature)
                                mInternalWbMode = CaptureRequest.CONTROL_AWB_MODE_OFF;
                                mInternalCameraHelper.setAwbAuto(false);
                                mInternalCameraHelper.setAwbMode(CaptureRequest.CONTROL_AWB_MODE_OFF);
                                mInternalCameraHelper.setAwbTemperatureKelvin(wbKelvin);
                                mInternalWbKelvin = wbKelvin;
                                break;
                        }
                    }
                    if (mInternalFocusLock) {
                        setInternalFocusLock(true);
                    } else {
                        setInternalFocusLock(false);
                    }
                    if (mInternalAfLock) {
                        setInternalAfLock(true);
                    } else {
                        setInternalAfLock(false);
                    }
                } finally {
                    mInternalCameraHelper.setPreviewUpdatesSuspended(false);
                    clearTcpUdpFrameQueue();
                    requestH264SyncFrameAsync();
                }
            }
        });
    }

    private void setInternalFocusLock(boolean locked) {
        if (mInternalCameraHelper == null) {
            mInternalFocusLock = locked;
            updateInternalCameraControlPanel();
            return;
        }

        if (locked) {
            if (!mInternalFocusLock) {
                mInternalFocusLockPreviousAfMode = mInternalCameraHelper.getAfMode();
                mInternalFocusLockPreviousTapToFocus = mInternalCameraHelper.isTapToFocusMode();
            }
            mInternalFocusLock = true;
            mInternalCameraHelper.setTapToFocusMode(false);
            mInternalCameraHelper.setAfMode(CaptureRequest.CONTROL_AF_MODE_OFF);
        } else {
            mInternalFocusLock = false;
            if (mInternalFocusLockPreviousTapToFocus) {
                mInternalCameraHelper.setTapToFocusMode(true);
            } else {
                mInternalCameraHelper.setTapToFocusMode(false);
                mInternalCameraHelper.setAfMode(mInternalFocusLockPreviousAfMode);
            }
        }
        updateInternalCameraControlPanel();
    }

    private void setInternalAfLock(boolean locked) {
        if (mInternalCameraHelper == null) {
            mInternalAfLock = locked;
            updateInternalCameraControlPanel();
            return;
        }

        if (locked) {
            if (!mInternalAfLock) {
                mInternalAfLockPreviousAfMode = mInternalCameraHelper.getAfMode();
                mInternalAfLockPreviousTapToFocus = mInternalCameraHelper.isTapToFocusMode();
            }
            mInternalAfLock = true;
            mInternalCameraHelper.setTapToFocusMode(false);
            mInternalCameraHelper.setAfMode(CaptureRequest.CONTROL_AF_MODE_OFF);
        } else {
            mInternalAfLock = false;
            if (mInternalAfLockPreviousTapToFocus) {
                mInternalCameraHelper.setTapToFocusMode(true);
            } else {
                mInternalCameraHelper.setTapToFocusMode(false);
                mInternalCameraHelper.setAfMode(mInternalAfLockPreviousAfMode);
            }
        }
        updateInternalCameraControlPanel();
    }

    private String getFlashModeLabel(int flashMode) {
        switch (flashMode) {
            case CaptureRequest.FLASH_MODE_SINGLE:
                return "Flash On";
            case CaptureRequest.FLASH_MODE_TORCH:
                return "Torch";
            default:
                return "Flash Off";
        }
    }

    private String getWbModeLabel(int wbMode) {
        switch (wbMode) {
            case CaptureRequest.CONTROL_AWB_MODE_INCANDESCENT:
                return "Incandescent";
            case CaptureRequest.CONTROL_AWB_MODE_FLUORESCENT:
                return "Fluorescent";
            case CaptureRequest.CONTROL_AWB_MODE_DAYLIGHT:
                return "Daylight";
            case CaptureRequest.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT:
                return "Cloudy";
            case CaptureRequest.CONTROL_AWB_MODE_SHADE:
                return "Shade";
            case CaptureRequest.CONTROL_AWB_MODE_OFF:
                return "Kelvin";
            default:
                return "WB Auto";
        }
    }

    private String formatExposureTime(long exposureTime) {
        if (exposureTime <= 0) {
            return "1/1000000";
        }
        for (long val : PRO_SHUTTER_VALUES_NS) {
            if (exposureTime == val) {
                double seconds = val / 1_000_000_000.0;
                if (seconds >= 1.0) {
                    return String.format(Locale.US, "%.1fs", seconds);
                }
                int den = (int) Math.round(1.0 / seconds);
                return "1/" + den;
            }
        }
        double seconds = exposureTime / 1_000_000_000.0;
        if (seconds >= 1.0) {
            return String.format(Locale.US, "%.1fs", seconds);
        }
        int den = (int) Math.round(1.0 / seconds);
        return "1/" + den;
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

        // Wire up frame delivery for the currently selected transport.
        // Must be set before openCamera so the reader is included in the initial
        // capture session and frames can be delivered immediately.
        updateInternalCameraFrameListener();

        XXPermissions.with(this)
                .permission(Manifest.permission.CAMERA)
                .request((permissions, all) ->
                        mInternalCameraHelper.openCamera(cameraInfo, previewSize));
    }

    private void updateInternalCameraFrameListener() {
        if (mInternalCameraHelper == null) {
            return;
        }

        if (mStreamProtocol == StreamProtocol.NDI) {
            mInternalCameraHelper.setFrameListener((nv12Frame, width, height) -> {
                if (nv12Frame == null) {
                    return;
                }
                enqueueNdiFrame(nv12Frame);
            });
            Log.i(TAG, "Internal camera transport set to NDI");
        } else {
            mInternalCameraHelper.setFrameListener((nv12Frame, width, height) -> {
                if (nv12Frame == null) {
                    return;
                }

                mPreviewWidth = width;
                mPreviewHeight = height;
                enqueueTcpUdpFrame(nv12Frame, width, height);
            });
            Log.i(TAG, "Internal camera transport set to TCP/UDP");
        }
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

            // Set up the selected transport
            if (mStreamProtocol == StreamProtocol.NDI) {
                setupNdiForInternalCamera(cameraInfo, previewSize);
            } else {
                setupTcpUdpForInternalCamera(cameraInfo, previewSize);
            }

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
            setNdiTargetFps(25); // enforce 25 fps minimum for smooth 4K delivery
            setNdiFormat(mNdiHighQuality); // ensure format mode is applied
            updateInternalCameraFrameListener();
            startNdiForwardingThread();
            Log.i(TAG, "NDI ready for internal camera: " + sourceName);
        } catch (Exception e) {
            Log.e(TAG, "Failed to set up NDI for internal camera", e);
            mNdiSender      = null;
            mFrameForwarder = null;
        }
    }

    private void setupTcpUdpForUsbCamera(final UsbDevice device, final Size previewSize) {
        Log.i(TAG, "✅ H.265 TCP stream mode selected for USB camera.");
        stopNdiForwardingThread();
        cleanupNdiAndStreaming();
        new Thread(() -> {
            if (!hasCustomTransportDestination()) {
                runOnUiThread(() -> Toast.makeText(this,
                        "Port not configured — tap the stream protocol button to set a port",
                        Toast.LENGTH_SHORT).show());
                return;
            }
            startTcpUdpForwardingThread();
        }, "CustomTransportInit").start();
    }

    private void setupTcpUdpForInternalCamera(final InternalCameraInfo cameraInfo,
                                              final android.util.Size previewSize) {
        Log.i(TAG, "✅ H.265 TCP stream mode selected for internal camera.");
        stopNdiForwardingThread();
        cleanupNdiAndStreaming();
        new Thread(() -> {
            if (!hasCustomTransportDestination()) {
                runOnUiThread(() -> Toast.makeText(this,
                        "Port not configured — tap the stream protocol button to set a port",
                        Toast.LENGTH_SHORT).show());
                return;
            }
            updateInternalCameraFrameListener();
            startTcpUdpForwardingThread();
        }, "CustomTransportInit").start();
    }

    /** Tears down NDI sender and frame forwarder — used by both camera paths. */
    private void cleanupNdiAndStreaming() {
        try {
            if (mCameraHelper != null) {
                mCameraHelper.setFrameCallback(null, 0);
            }
            if (mInternalCameraHelper != null) {
                mInternalCameraHelper.setFrameListener(null);
            }
            stopNdiForwardingThread();
            if (mFrameForwarder != null) {
                mFrameForwarder = null;
            }
            if (mNdiSender != null) {
                stopTallyPolling();
                mNdiSender.close();
                mNdiSender = null;
            }
            stopTcpUdpForwardingThread();
        } catch (Exception e) {
            Log.e(TAG, "Error stopping NDI", e);
        }
    }

    private void startNdiForwardingThread() {
        if (mNdiWorkerRunning) {
            return;
        }
        mNdiWorkerRunning = true;
        mNdiWorkerThread = new Thread(() -> {
            while (mNdiWorkerRunning && !Thread.currentThread().isInterrupted()) {
                try {
                    java.nio.ByteBuffer frame = mNdiFrameQueue.poll(40, TimeUnit.MILLISECONDS);
                    if (frame == null) {
                        continue;
                    }
                    if (mFrameForwarder != null) {
                        mFrameForwarder.onFrame(frame);
                    }
                    mNdiReusableBuffer.set(frame);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (Exception e) {
                    Log.w(TAG, "Error in NDI forwarding thread", e);
                }
            }
        }, "NdiFrameForwarder");
        mNdiWorkerThread.setPriority(Thread.MAX_PRIORITY);
        mNdiWorkerThread.start();
    }

    private void stopNdiForwardingThread() {
        mNdiWorkerRunning = false;
        if (mNdiWorkerThread != null) {
            mNdiWorkerThread.interrupt();
            try {
                mNdiWorkerThread.join(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            mNdiWorkerThread = null;
        }
        mNdiFrameQueue.clear();
        mNdiReusableBuffer.set(null);
    }

    private void stopTcpUdpForwardingThread() {
        mTcpUdpWorkerRunning = false;
        stopTcpDiscoveryBeaconThread();
        stopTcpTallyListenerThread();
        if (mNdiSender == null) {
            stopTallyPolling();
        }
        // Close the server socket so accept() unblocks immediately
        ServerSocket ss = mH265ServerSocket;
        if (ss != null) {
            try { ss.close(); } catch (Exception ignored) {}
        }
        // Close any active output stream
        OutputStream out = mH264OutputStream;
        mH264OutputStream = null;
        if (out != null) {
            try { out.close(); } catch (Exception ignored) {}
        }
        if (mTcpUdpWorkerThread != null) {
            mTcpUdpWorkerThread.interrupt();
            try {
                mTcpUdpWorkerThread.join(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            mTcpUdpWorkerThread = null;
        }
        clearTcpUdpFrameQueue();
        runOnUiThread(this::updateStreamStatus);
    }

    // ── H.265 encoder lifecycle ──────────────────────────────────────────────

    private void startH264Encoder(int width, int height) {
        stopH264Encoder();
        try {
            int pixels = width * height;
            boolean is4K = (long) pixels > 1920L * 1080L;
            int targetFps = Math.max(24, Math.min(60, mVideoTargetFps));
            if (is4K) {
                targetFps = Math.min(targetFps, 30);
            }
            int quality = Math.max(10, Math.min(100, mVideoQuality));
            // H.265 needs ~50% the bitrate of H.264; scale but cap aggressively for TCP stability
            long baseBitrate = 3_000_000L + (17_000_000L * quality / 100L);
            long scaledBitrate = baseBitrate * pixels / (1920 * 1080);
            long maxBitrate = is4K ? 25_000_000L : 20_000_000L;
            long bitrate = Math.min(maxBitrate, Math.max(2_000_000L, scaledBitrate));
            MediaCodec enc = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_HEVC);
            MediaFormat fmt = MediaFormat.createVideoFormat(
                    MediaFormat.MIMETYPE_VIDEO_HEVC, width, height);
            fmt.setInteger(MediaFormat.KEY_COLOR_FORMAT,
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar);
            fmt.setInteger(MediaFormat.KEY_BIT_RATE, (int) bitrate);
            fmt.setInteger(MediaFormat.KEY_FRAME_RATE, targetFps);
            // Longer I-frame interval → fewer expensive keyframes → smoother TCP delivery
            fmt.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, is4K ? 5 : 2);
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
                // CBR for predictable TCP frame sizes (reduces buffering jitter)
                fmt.setInteger(MediaFormat.KEY_BITRATE_MODE,
                        MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR);
                // Minimum complexity = fastest possible encoding (less variant encode time)
                fmt.setInteger(MediaFormat.KEY_COMPLEXITY, 0);
            }
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                // Tell encoder to emit output as soon as each frame is encoded
                fmt.setInteger(MediaFormat.KEY_LOW_LATENCY, 1);
            }
            enc.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            enc.start();
            mH264Encoder = enc;
            mH264EncoderWidth = width;
            mH264EncoderHeight = height;
            mH264SpsPps = null;
            mTcpUdpTargetFps = targetFps;
            mTcpUdpMinFrameIntervalNs = 1_000_000_000L / mTcpUdpTargetFps;
            mNextTcpUdpEnqueueTimeNs = 0;
            Log.i(TAG, "H.265 encoder started " + width + "x" + height + " @ " + bitrate / 1_000_000 + " Mbps @ " + targetFps + " fps");
        } catch (Exception e) {
            Log.e(TAG, "Failed to start H.265 encoder", e);
            mH264Encoder = null;
        }
    }

    private void stopH264Encoder() {
        MediaCodec enc = mH264Encoder;
        mH264Encoder = null;
        mH264EncoderWidth = 0;
        mH264EncoderHeight = 0;
        if (enc != null) {
            try { enc.stop(); } catch (Exception ignored) {}
            try { enc.release(); } catch (Exception ignored) {}
        }
    }

    private void reconfigureH264Encoder(int width, int height) {
        stopH264Encoder();
        startH264Encoder(width, height);
    }

    /**
     * Feed one NV12 frame to the H.265 encoder and drain all output packets to TCP.
     * Throws IOException if the TCP stream is broken.
     */
    private void feedFrameToH264Encoder(CustomUdpFrame frame) throws IOException {
        MediaCodec enc = mH264Encoder;
        if (enc == null) return;
        long encodeStartNs = System.nanoTime();

        // Feed NV12 frame directly into encoder input buffer — avoids intermediate byte[] copy
        int yuvSize = frame.width * frame.height * 3 / 2;
        int actualYuvSize = Math.min(yuvSize, frame.frame.remaining());

        // Phase 1 — drain any already-ready output BEFORE requesting an input buffer.
        // This prevents a deadlock: encoder holds all input slots until its output is consumed.
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        drainEncoderOutput(enc, info, 0);

        // Phase 2 — submit the new frame (short timeout: skip rather than block the pipeline)
        int inputIndex = enc.dequeueInputBuffer(5_000 /* µs */);
        int enqueuedSize = 0;
        if (inputIndex >= 0) {
            java.nio.ByteBuffer inputBuf = enc.getInputBuffer(inputIndex);
            if (inputBuf != null) {
                inputBuf.clear();
                java.nio.ByteBuffer src = frame.frame.duplicate();
                src.limit(src.position() + Math.min(actualYuvSize, inputBuf.remaining()));
                enqueuedSize = src.remaining();
                inputBuf.put(src);
            }
            long ptsUs = frame.captureTimeNs > 0 ? (frame.captureTimeNs / 1000L) : (System.nanoTime() / 1000L);
            enc.queueInputBuffer(inputIndex, 0, enqueuedSize, ptsUs, 0);
        } else {
            mTcpFramesDropped.incrementAndGet();
            maybePublishTcpTelemetry(false);
            return;
        }

        // Phase 3 — drain output again, short wait to avoid blocking the forwarding loop
        drainEncoderOutput(enc, info, 10_000);
        mTcpEncodeTimeNsSum.addAndGet(System.nanoTime() - encodeStartNs);
        mTcpEncodeSamples.incrementAndGet();
    }

    /** Drain all available encoder output packets and forward them to the TCP stream. */
    private void drainEncoderOutput(MediaCodec enc, MediaCodec.BufferInfo info, long firstTimeoutUs)
            throws IOException {
        long timeoutUs = firstTimeoutUs;
        while (true) {
            int outIndex = enc.dequeueOutputBuffer(info, timeoutUs);
            timeoutUs = 0; // subsequent polls: non-blocking
            if (outIndex == MediaCodec.INFO_TRY_AGAIN_LATER) break;
            if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                // Extract SPS+PPS from the new format
                MediaFormat newFmt = enc.getOutputFormat();
                java.nio.ByteBuffer spsB = newFmt.getByteBuffer("csd-0");
                java.nio.ByteBuffer ppsB = newFmt.getByteBuffer("csd-1");
                int spsLen = spsB != null ? spsB.remaining() : 0;
                int ppsLen = ppsB != null ? ppsB.remaining() : 0;
                byte[] spsPps = new byte[spsLen + ppsLen];
                if (spsB != null) spsB.get(spsPps, 0, spsLen);
                if (ppsB != null) ppsB.get(spsPps, spsLen, ppsLen);
                mH264SpsPps = spsPps;
                // Send SPS+PPS as config packet
                OutputStream out = mH264OutputStream;
                if (out != null) {
                    sendH264Packet(H264_NO_PTS, spsPps, 0, spsPps.length);
                    out.flush();
                }
                continue;
            }
            if (outIndex < 0) break;
            java.nio.ByteBuffer outBuf = enc.getOutputBuffer(outIndex);
            if (outBuf != null && info.size > 0) {
                boolean isConfig = (info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0;
                if (mH264OutputBuffer == null || mH264OutputBuffer.length < info.size) {
                    mH264OutputBuffer = new byte[info.size * 2];
                }
                byte[] data = mH264OutputBuffer;
                outBuf.position(info.offset);
                outBuf.get(data, 0, info.size);
                long pts = isConfig ? H264_NO_PTS : (info.presentationTimeUs);
                OutputStream out = mH264OutputStream;
                if (out != null) {
                    sendH264Packet(pts, data, 0, info.size);
                    if (!isConfig) {
                        mTcpFramesEncoded.incrementAndGet();
                    }
                }
                if (isConfig) {
                    mH264SpsPps = java.util.Arrays.copyOf(data, info.size);
                }
            }
            enc.releaseOutputBuffer(outIndex, false);
            if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) break;
        }
    }

    /**
     * Write a DroidCam-style H.264 packet: 8-byte PTS (BE) + 4-byte length (BE) + payload.
     * Throws IOException if the TCP stream is broken.
     */
    private void sendH264Packet(long pts, byte[] data, int offset, int length) throws IOException {
        OutputStream out = mH264OutputStream;
        if (out == null) return;
        byte[] hdr = mH264FrameHeaderBuf;
        // PTS — 8 bytes big-endian
        hdr[0] = (byte) (pts >>> 56);
        hdr[1] = (byte) (pts >>> 48);
        hdr[2] = (byte) (pts >>> 40);
        hdr[3] = (byte) (pts >>> 32);
        hdr[4] = (byte) (pts >>> 24);
        hdr[5] = (byte) (pts >>> 16);
        hdr[6] = (byte) (pts >>>  8);
        hdr[7] = (byte) (pts);
        // Length — 4 bytes big-endian
        hdr[8]  = (byte) (length >>> 24);
        hdr[9]  = (byte) (length >>> 16);
        hdr[10] = (byte) (length >>>  8);
        hdr[11] = (byte) (length);
        out.write(hdr, 0, 12);
        out.write(data, offset, length);
        if (pts != H264_NO_PTS) {
            mTcpPacketsSent.incrementAndGet();
        }
    }

    private void requestH264SyncFrameAsync() {
        mH264SyncFrameRequested = true;
    }

    private void requestH264SyncFrame() {
        if (mH264Encoder == null) {
            return;
        }
        try {
            android.os.Bundle params = new android.os.Bundle();
            params.putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0);
            mH264Encoder.setParameters(params);
        } catch (Exception e) {
            Log.w(TAG, "Failed to request H.265 sync frame", e);
        }
    }

    private void recycleFrameBuffer(java.nio.ByteBuffer frame) {
        if (frame == null) {
            return;
        }
        frame.clear();
        // Try TCP pool first, then NDI pool — two pooled buffers avoids allocateDirect at 4K
        if (!mTcpReusableBuffer.compareAndSet(null, frame)) {
            mNdiReusableBuffer.compareAndSet(null, frame);
        }
    }

    private void clearTcpUdpFrameQueue() {
        CustomUdpFrame pendingFrame;
        while ((pendingFrame = mTcpUdpFrameQueue.poll()) != null) {
            recycleFrameBuffer(pendingFrame.frame);
        }
    }

    private void startTcpDiscoveryBeaconThread() {
        if (mTcpDiscoveryRunning) {
            return;
        }
        mTcpDiscoveryRunning = true;
        mTcpDiscoveryThread = new Thread(() -> {
            while (mTcpDiscoveryRunning) {
                try {
                    sendTcpDiscoveryBeacon();
                } catch (Exception e) {
                    if (mTcpDiscoveryRunning) {
                        Log.w(TAG, "Failed to send TCP discovery beacon", e);
                    }
                }
                try {
                    Thread.sleep(TCP_DISCOVERY_INTERVAL_MS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }, "TcpDiscoveryBeacon");
        mTcpDiscoveryThread.setPriority(Thread.NORM_PRIORITY);
        mTcpDiscoveryThread.start();
    }

    private void stopTcpDiscoveryBeaconThread() {
        mTcpDiscoveryRunning = false;
        if (mTcpDiscoveryThread != null) {
            mTcpDiscoveryThread.interrupt();
            try {
                mTcpDiscoveryThread.join(300);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            mTcpDiscoveryThread = null;
        }
    }

    private void sendTcpDiscoveryBeacon() {
        int port = mStreamPort > 0 ? mStreamPort : DEFAULT_STREAM_PORT;
        String payload = "UVCAPP;port=" + port;
        byte[] data = payload.getBytes(java.nio.charset.StandardCharsets.UTF_8);

        java.net.DatagramSocket socket = null;
        try {
            socket = new java.net.DatagramSocket();
            socket.setBroadcast(true);

            // Broadcast to global address.
            java.net.DatagramPacket globalPacket = new java.net.DatagramPacket(
                    data, data.length,
                    java.net.InetAddress.getByName("255.255.255.255"),
                    DISCOVERY_PORT);
            socket.send(globalPacket);

            // Broadcast on each interface-specific subnet address as well.
            java.util.Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                NetworkInterface nif = interfaces.nextElement();
                if (!nif.isUp() || nif.isLoopback()) {
                    continue;
                }
                for (java.net.InterfaceAddress ia : nif.getInterfaceAddresses()) {
                    java.net.InetAddress broadcast = ia.getBroadcast();
                    if (broadcast == null) {
                        continue;
                    }
                    java.net.DatagramPacket packet = new java.net.DatagramPacket(
                            data, data.length, broadcast, DISCOVERY_PORT);
                    socket.send(packet);
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "TCP discovery beacon error", e);
        } finally {
            if (socket != null) {
                socket.close();
            }
        }
    }

    private void startTcpTallyListenerThread() {
        if (mTcpTallyListenerRunning) {
            return;
        }
        mTcpTallyListenerRunning = true;
        mTcpTallyListenerThread = new Thread(() -> {
            java.net.DatagramSocket socket = null;
            try {
                socket = new java.net.DatagramSocket(TCP_TALLY_PORT);
                socket.setReuseAddress(true);
                socket.setSoTimeout(500);
                byte[] buf = new byte[256];
                while (mTcpTallyListenerRunning) {
                    java.net.DatagramPacket packet = new java.net.DatagramPacket(buf, buf.length);
                    try {
                        socket.receive(packet);
                    } catch (java.net.SocketTimeoutException ignored) {
                        maybeSendTcpControlStateToObs();
                        continue;
                    }
                    mTcpTallyRemoteHost = packet.getAddress() != null ? packet.getAddress().getHostAddress() : null;
                    String msg = new String(packet.getData(), 0, packet.getLength(), java.nio.charset.StandardCharsets.UTF_8);
                    boolean program = msg.contains("program=1");
                    boolean preview = msg.contains("preview=1");
                    mTcpObsProgram = program;
                    mTcpObsPreview = preview;
                    if (msg.startsWith("CONTROL;")) {
                        processTcpControlMessage(msg);
                    }
                    maybeSendTcpControlStateToObs();
                    mTcpLastTallyUpdateNs = System.nanoTime();
                }
            } catch (Exception e) {
                if (mTcpTallyListenerRunning) {
                    Log.w(TAG, "TCP tally listener error", e);
                }
            } finally {
                if (socket != null) {
                    socket.close();
                }
            }
        }, "TcpTallyListener");
        mTcpTallyListenerThread.setPriority(Thread.NORM_PRIORITY);
        mTcpTallyListenerThread.start();
    }

    private void stopTcpTallyListenerThread() {
        mTcpTallyListenerRunning = false;
        if (mTcpTallyListenerThread != null) {
            mTcpTallyListenerThread.interrupt();
            try {
                mTcpTallyListenerThread.join(300);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            mTcpTallyListenerThread = null;
        }
        mTcpObsProgram = false;
        mTcpObsPreview = false;
        mTcpTallyRemoteHost = null;
        mLastSentControlStatePayload = "";
        mLastSentControlStateNs = 0;
        mLastProcessedTcpControlPayload = "";
        mLastProcessedTcpControlNs = 0;
        mTcpLastTallyUpdateNs = 0;
    }

    private int mapInternalWbModeToObsWbMode() {
        if (mInternalWbMode == CaptureRequest.CONTROL_AWB_MODE_AUTO) return 0;
        if (mInternalWbMode == CaptureRequest.CONTROL_AWB_MODE_INCANDESCENT) return 1;
        if (mInternalWbMode == CaptureRequest.CONTROL_AWB_MODE_FLUORESCENT) return 2;
        if (mInternalWbMode == CaptureRequest.CONTROL_AWB_MODE_DAYLIGHT) return 3;
        if (mInternalWbMode == CaptureRequest.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT) return 4;
        if (mInternalWbMode == CaptureRequest.CONTROL_AWB_MODE_SHADE) return 5;
        if (mInternalWbMode == CaptureRequest.CONTROL_AWB_MODE_OFF) return 6;
        return 0;
    }

    private int mapInternalFlashModeToObsFlashMode() {
        if (mInternalFlashMode == CaptureRequest.FLASH_MODE_SINGLE) return 1;
        if (mInternalFlashMode == CaptureRequest.FLASH_MODE_TORCH) return 3;
        return 2;
    }

    private String buildTcpControlStatePayload() {
        int exposureCompensation = 0;
        if (mInternalCameraHelper != null) {
            exposureCompensation = mInternalCameraHelper.getCurrentExposureCompensation();
        }
        return String.format(java.util.Locale.US,
                "CONTROL_STATE;exposure_lock=%d;focus_lock=%d;exposure_compensation=%d;af_mode=%d;af_lock=%d;flash_mode=%d;wb_mode=%d;wb_kelvin=%d",
                mInternalExposureLock ? 1 : 0,
                mInternalFocusLock ? 1 : 0,
                exposureCompensation,
                mInternalAfMode,
                mInternalAfLock ? 1 : 0,
                mapInternalFlashModeToObsFlashMode(),
                mapInternalWbModeToObsWbMode(),
                mInternalWbKelvin);
    }

    private void maybeSendTcpControlStateToObs() {
        if (!mTcpTallyListenerRunning || mCameraMode != CameraMode.INTERNAL) {
            return;
        }
        final String remoteHost = mTcpTallyRemoteHost;
        if (remoteHost == null || remoteHost.isEmpty()) {
            return;
        }
        final String payload = buildTcpControlStatePayload();
        final long nowNs = System.nanoTime();
        if (payload.equals(mLastSentControlStatePayload)
                && (nowNs - mLastSentControlStateNs) < 1_000_000_000L) {
            return;
        }

        byte[] data = payload.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        java.net.DatagramSocket outSocket = null;
        try {
            outSocket = new java.net.DatagramSocket();
            java.net.DatagramPacket packet = new java.net.DatagramPacket(
                    data,
                    data.length,
                    java.net.InetAddress.getByName(remoteHost),
                    TCP_TALLY_PORT);
            outSocket.send(packet);
            mLastSentControlStatePayload = payload;
            mLastSentControlStateNs = nowNs;
        } catch (Exception e) {
            Log.w(TAG, "Failed to send CONTROL_STATE to OBS", e);
        } finally {
            if (outSocket != null) {
                outSocket.close();
            }
        }
    }

    private void enqueueTcpUdpFrame(java.nio.ByteBuffer frame, int width, int height) {
        if (frame == null) {
            return;
        }
        if (width <= 0 || height <= 0) {
            Log.w(TAG, "TCP/UDP enqueue: invalid frame dimensions " + width + "x" + height);
            return;
        }
        mTcpFramesCaptured.incrementAndGet();
        long nowNs = System.nanoTime();
        if (mTcpUdpTargetFps > 0 && mTcpUdpMinFrameIntervalNs > 0) {
            long nextNs = mNextTcpUdpEnqueueTimeNs;
            if (nextNs <= 0) {
                nextNs = nowNs;
            }
            // Small early-accept window keeps cadence stable despite callback jitter.
            if (nowNs + (mTcpUdpMinFrameIntervalNs / 3) < nextNs) {
                mTcpFramesDropped.incrementAndGet();
                maybePublishTcpTelemetry(false);
                return;
            }
            while (nowNs > nextNs + mTcpUdpMinFrameIntervalNs) {
                nextNs += mTcpUdpMinFrameIntervalNs;
            }
            mNextTcpUdpEnqueueTimeNs = nextNs + mTcpUdpMinFrameIntervalNs;
        }
        mLastTcpUdpEnqueueTimeNs = nowNs;

        java.nio.ByteBuffer frameCopy = copyFrameBuffer(frame);
        if (frameCopy == null) {
            Log.w(TAG, "TCP/UDP enqueue: failed to copy frame");
            mTcpFramesDropped.incrementAndGet();
            maybePublishTcpTelemetry(false);
            return;
        }
        try {
            CustomUdpFrame packet = new CustomUdpFrame(frameCopy, width, height, nowNs);
            // Keep up to 2 frames queued for jitter absorption; only drop oldest if full
            if (!mTcpUdpFrameQueue.offer(packet)) {
                CustomUdpFrame stale = mTcpUdpFrameQueue.poll();
                if (stale != null) {
                    recycleFrameBuffer(stale.frame);
                    mTcpFramesDropped.incrementAndGet();
                }
                if (!mTcpUdpFrameQueue.offer(packet)) {
                    recycleFrameBuffer(frameCopy);
                    mTcpFramesDropped.incrementAndGet();
                }
            }
            noteTcpQueueDepth();
            maybePublishTcpTelemetry(false);
        } catch (Exception e) {
            recycleFrameBuffer(frameCopy);
            Log.w(TAG, "TCP/UDP enqueue: unexpected error", e);
            mTcpFramesDropped.incrementAndGet();
            maybePublishTcpTelemetry(false);
        }
    }

    private void startTcpUdpForwardingThread() {
        if (mTcpUdpWorkerRunning) {
            return;
        }
        resetTcpTelemetry();
        mTcpObsProgram = false;
        mTcpObsPreview = false;
        mTcpLastTallyUpdateNs = 0;
        mHandler.removeCallbacks(mTallyPoller);
        mHandler.post(mTallyPoller);
        startTcpDiscoveryBeaconThread();
        startTcpTallyListenerThread();
        mTcpUdpWorkerRunning = true;
        mTcpUdpWorkerThread = new Thread(() -> {
            Log.i(TAG, "H.265 TCP forwarding thread started, port=" + mStreamPort);

            // Open server socket once for this thread's lifetime
            try {
                mH265ServerSocket = new ServerSocket();
                mH265ServerSocket.setReuseAddress(true);
                mH265ServerSocket.setSoTimeout(500);
                mH265ServerSocket.bind(new java.net.InetSocketAddress(mStreamPort));
                Log.i(TAG, "H.265 TCP server listening on port " + mStreamPort);
            } catch (IOException e) {
                Log.e(TAG, "Failed to open H.265 TCP server socket", e);
                mTcpUdpWorkerRunning = false;
                return;
            }

            // Outer loop: accept/reconnect
            while (mTcpUdpWorkerRunning) {
                Socket client = null;
                // Accept loop — retries on SO_TIMEOUT until stopped
                while (mTcpUdpWorkerRunning && client == null) {
                    try {
                        client = mH265ServerSocket.accept();
                        client.setTcpNoDelay(true);
                        try { client.setKeepAlive(true); } catch (Exception ignored) {}
                        try { client.setSendBufferSize(1024 * 1024); } catch (Exception ignored) {}
                        try { client.setTrafficClass(0x10); } catch (Exception ignored) {}
                        mH264OutputStream = new BufferedOutputStream(
                                client.getOutputStream(), 256 * 1024);
                        mLastTcpFlushTimeNs = 0;
                        Log.i(TAG, "OBS connected from " + client.getInetAddress().getHostAddress());
                    } catch (java.net.SocketTimeoutException ignored) {
                        // no client yet — keep waiting
                    } catch (IOException e) {
                        if (mTcpUdpWorkerRunning) {
                            Log.w(TAG, "Error accepting H.265 TCP connection", e);
                        }
                        break;
                    }
                }
                if (!mTcpUdpWorkerRunning || client == null) break;

                // Clear any stale queued frames before starting a new connection.
                clearTcpUdpFrameQueue();
                requestH264SyncFrame();

                // Send cached SPS+PPS so OBS can decode immediately
                try {
                    byte[] sps = mH264SpsPps;
                    if (sps != null) {
                        sendH264Packet(H264_NO_PTS, sps, 0, sps.length);
                        mH264OutputStream.flush();
                    }
                } catch (IOException e) {
                    Log.w(TAG, "Failed to send SPS/PPS to OBS", e);
                    mH264OutputStream = null;
                    try { client.close(); } catch (Exception ignored) {}
                    continue;
                }

                // Frame streaming loop — runs until client disconnects or we stop
                boolean clientActive = true;
                while (mTcpUdpWorkerRunning && clientActive) {
                    try {
                        CustomUdpFrame frame = mTcpUdpFrameQueue.poll(
                                5, java.util.concurrent.TimeUnit.MILLISECONDS);
                        if (frame == null) continue;
                        try {
                            if (mH264Encoder == null
                                    || frame.width != mH264EncoderWidth
                                    || frame.height != mH264EncoderHeight) {
                                reconfigureH264Encoder(frame.width, frame.height);
                            }
                            if (mH264SyncFrameRequested) {
                                mH264SyncFrameRequested = false;
                                requestH264SyncFrame();
                            }
                            feedFrameToH264Encoder(frame);
                            // Micro-batched flush reduces syscall jitter while preserving low latency.
                            OutputStream flushOut = mH264OutputStream;
                            if (flushOut != null) {
                                long nowFlushNs = System.nanoTime();
                                if (mLastTcpFlushTimeNs <= 0
                                        || (nowFlushNs - mLastTcpFlushTimeNs) >= TCP_FLUSH_INTERVAL_NS
                                        || mTcpUdpFrameQueue.isEmpty()) {
                                    flushOut.flush();
                                    mLastTcpFlushTimeNs = nowFlushNs;
                                }
                            }
                            maybePublishTcpTelemetry(false);
                        } finally {
                            recycleFrameBuffer(frame.frame);
                        }
                    } catch (IOException e) {
                        Log.i(TAG, "OBS disconnected: " + e.getMessage());
                        clientActive = false;
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        clientActive = false;
                    } catch (Exception e) {
                        Log.w(TAG, "Error in H.265 TCP forwarding thread", e);
                    }
                }

                mH264OutputStream = null;
                try { client.close(); } catch (Exception ignored) {}
            }

            stopH264Encoder();
            ServerSocket ss = mH265ServerSocket;
            mH265ServerSocket = null;
            if (ss != null) try { ss.close(); } catch (Exception ignored) {}
            Log.i(TAG, "H.265 TCP forwarding thread exiting");
            maybePublishTcpTelemetry(true);
        }, "H265TcpForwarder");
        mTcpUdpWorkerThread.setPriority(Thread.MAX_PRIORITY);
        mTcpUdpWorkerThread.start();
    }

    private void enqueueNdiFrame(java.nio.ByteBuffer frame) {
        if (frame == null || mFrameForwarder == null) {
            return;
        }

        long nowNs = System.nanoTime();
        if (mNdiTargetFps > 0 && mLastEnqueueFrameTimeNs > 0
                && (nowNs - mLastEnqueueFrameTimeNs) < mNdiMinFrameIntervalNs) {
            // Throttle incoming frames at the source to reduce queue thrashing
            return;
        }
        mLastEnqueueFrameTimeNs = nowNs;

        java.nio.ByteBuffer frameCopy = copyFrameBuffer(frame);
        if (frameCopy == null) {
            return;
        }

        try {
            if (!mNdiFrameQueue.offer(frameCopy, 160, TimeUnit.MILLISECONDS)) {
                // queue is still full after wait; drop newest frame (rare fallback)
                long now = System.currentTimeMillis();
                if (now - mLastQueueFullLog > QUEUE_FULL_LOG_INTERVAL_MS) {
                    Log.w(TAG, "NDI frame queue full after wait: dropped newest frame");
                    mLastQueueFullLog = now;
                }
                mNdiReusableBuffer.set(frameCopy);
                return;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            mNdiReusableBuffer.set(frameCopy);
            return;
        }
    }

    private java.nio.ByteBuffer copyFrameBuffer(java.nio.ByteBuffer src) {
        if (src == null || src.remaining() <= 0) {
            return null;
        }

        int needed = src.remaining();
        // Try both buffer pools before falling back to expensive allocateDirect
        java.nio.ByteBuffer dest = mTcpReusableBuffer.getAndSet(null);
        if (dest == null || dest.capacity() < needed) {
            dest = mNdiReusableBuffer.getAndSet(null);
        }
        if (dest == null || dest.capacity() < needed) {
            dest = java.nio.ByteBuffer.allocateDirect(needed);
        }
        dest.clear();
        int oldPos = src.position();
        dest.put(src);
        dest.flip();
        src.position(oldPos);
        return dest;
    }
}
