/*
 * Example NDI Camera Activity
 * Demonstrates how to integrate NDI streaming with UVC camera access
 */

package com.serenegiant.uvcapp;

import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicBoolean;

import android.app.Activity;
import android.hardware.usb.UsbDevice;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import com.serenegiant.usb.IFrameCallback;
import com.serenegiant.usb.USBMonitor;
import com.serenegiant.usb.UVCCamera;
import com.serenegiant.widget.UVCCameraTextureView;

import com.serenegiant.ndi.Ndi;
import com.serenegiant.ndi.NdiSender;
import com.serenegiant.ndi.UvcNdiFrameForwarder;
import com.serenegiant.ndi.INdiFrameSender;

/**
 * Example activity demonstrating NDI streaming from a UVC camera
 */
public class NdiCameraExampleActivity extends Activity {
    private static final String TAG = "NdiExample";

    private static final int PREVIEW_WIDTH = 1280;
    private static final int PREVIEW_HEIGHT = 720;

    private USBMonitor mUSBMonitor;
    private UVCCamera mUVCCamera;
    private UVCCameraTextureView mTextureView;

    private NdiSender mNdiSender;
    private UvcNdiFrameForwarder mFrameForwarder;
    
    private TextView mStatusText;
    private Button mStartButton;
    private Button mStopButton;
    
    private final AtomicBoolean mNdiActive = new AtomicBoolean(false);
    private long mFrameCount = 0;
    private long mLastStatusUpdate = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_ndi_camera);

        // Initialize NDI early
        try {
            Ndi.initialize();
            Log.i(TAG, "NDI initialized. Version: " + Ndi.getNdiVersion());
            Log.i(TAG, "CPU supported: " + Ndi.isCPUSupported());
        } catch (Exception e) {
            Log.e(TAG, "Failed to initialize NDI", e);
            showError("NDI initialization failed: " + e.getMessage());
            return;
        }

        // Setup UI
        mStatusText = findViewById(R.id.status_text);
        mStartButton = findViewById(R.id.start_button);
        mStopButton = findViewById(R.id.stop_button);
        mTextureView = findViewById(R.id.camera_view);

        mStartButton.setOnClickListener(v -> startNdiStreaming());
        mStopButton.setOnClickListener(v -> stopNdiStreaming());
        mStopButton.setEnabled(false);

        // Setup USB monitoring
        mUSBMonitor = new USBMonitor(this, onDeviceConnectListener);
        mUSBMonitor.register();

        updateStatus("Waiting for USB camera...");
    }

    private final USBMonitor.OnDeviceConnectListener onDeviceConnectListener
            = new USBMonitor.OnDeviceConnectListener() {
        @Override
        public void onAttach(UsbDevice device) {
            Log.i(TAG, "USB device attached: " + device.getDeviceName());
            // Auto-open first UVC device
            if (mUVCCamera == null) {
                mUSBMonitor.requestPermission(device);
            }
        }

        @Override
        public void onConnect(UsbDevice device, USBMonitor.UsbControlBlock ctrlBlock, boolean createNew) {
            Log.i(TAG, "USB device connected");
            if (mUVCCamera == null) {
                mUVCCamera = new UVCCamera();
                mUVCCamera.open(ctrlBlock);
                Log.i(TAG, "UVC camera opened");
                updateStatus("Camera ready. Press 'Start' to stream");
            }
        }

        @Override
        public void onDisconnect(UsbDevice device, USBMonitor.UsbControlBlock ctrlBlock) {
            Log.i(TAG, "USB device disconnected");
            if (mUVCCamera != null) {
                mUVCCamera.close();
                mUVCCamera = null;
            }
            stopNdiStreaming();
            updateStatus("Camera disconnected");
        }

        @Override
        public void onDettach(UsbDevice device) {
            Log.i(TAG, "USB device detached");
        }

        @Override
        public void onCancel(UsbDevice device) {
            Log.i(TAG, "USB permission cancelled");
        }
    };

    private void startNdiStreaming() {
        if (mUVCCamera == null) {
            showError("No USB camera connected");
            return;
        }

        if (mNdiActive.get()) {
            return;
        }

        try {
            // Create NDI sender with unique name
            String sourceName = "AndroidCamera-" + System.currentTimeMillis();
            mNdiSender = new NdiSender(sourceName);
            Log.i(TAG, "NDI sender created: " + sourceName);

            // Create frame forwarder
            mFrameForwarder = new UvcNdiFrameForwarder(mNdiSender, "nv12", new INdiFrameSender() {
                @Override
                public void onNdiFrameAvailable(ByteBuffer frame, String frameFormat, int width, int height, long presentationTimeUs) {
                    mFrameCount++;
                    long now = System.currentTimeMillis();
                    if (now - mLastStatusUpdate > 1000) {
                        updateStatus(String.format("Streaming %d FPS | Frames: %d", 
                            mFrameCount - (mFrameCount / 2), mFrameCount));
                        mLastStatusUpdate = now;
                        mFrameCount = 0;
                    }
                }

                @Override
                public void onNdiError(String errorMessage, Throwable throwable) {
                    Log.e(TAG, "NDI Error: " + errorMessage, throwable);
                    stopNdiStreaming();
                    showError("NDI Error: " + errorMessage);
                }

                @Override
                public void onNdiConnectionChanged(boolean connected) {
                    Log.i(TAG, "NDI connection: " + (connected ? "connected" : "disconnected"));
                }
            });

            mFrameForwarder.setFrameDimensions(PREVIEW_WIDTH, PREVIEW_HEIGHT);

            // Start camera preview
            mUVCCamera.setPreviewSize(PREVIEW_WIDTH, PREVIEW_HEIGHT);
            mTextureView.setSurfaceTextureListener(new UVCCameraTextureView.SurfaceTextureListener() {
                @Override
                public void onSurfaceTextureAvailable(android.graphics.SurfaceTexture surface, int width, int height) {
                    try {
                        mUVCCamera.setPreviewDisplay(new android.view.SurfaceTexture(0));
                        mUVCCamera.setFrameCallback(mFrameForwarder, UVCCamera.PIXEL_FORMAT_NV12);
                        mUVCCamera.startCapture();
                        
                        mNdiActive.set(true);
                        mStartButton.setEnabled(false);
                        mStopButton.setEnabled(true);
                        updateStatus("Streaming to NDI...");
                        Log.i(TAG, "NDI streaming started");
                    } catch (Exception e) {
                        Log.e(TAG, "Failed to start capture", e);
                        showError("Failed to start streaming: " + e.getMessage());
                    }
                }

                @Override
                public void onSurfaceTextureSizeChanged(android.graphics.SurfaceTexture surface, int width, int height) {
                }

                @Override
                public boolean onSurfaceTextureDestroyed(android.graphics.SurfaceTexture surface) {
                    return false;
                }

                @Override
                public void onSurfaceTextureFrameAvailable(android.graphics.SurfaceTexture surface) {
                }
            });

        } catch (Exception e) {
            Log.e(TAG, "Failed to start NDI streaming", e);
            showError("Failed to start streaming: " + e.getMessage());
        }
    }

    private void stopNdiStreaming() {
        if (!mNdiActive.getAndSet(false)) {
            return;
        }

        try {
            if (mUVCCamera != null) {
                mUVCCamera.stopCapture();
                mUVCCamera.setFrameCallback(null, 0);
            }

            if (mFrameForwarder != null) {
                Log.i(TAG, "Total frames forwarded: " + mFrameForwarder.getFrameCount());
                mFrameForwarder = null;
            }

            if (mNdiSender != null) {
                mNdiSender.close();
                mNdiSender = null;
                Log.i(TAG, "NDI sender closed");
            }

            mStartButton.setEnabled(true);
            mStopButton.setEnabled(false);
            updateStatus("Streaming stopped");

        } catch (Exception e) {
            Log.e(TAG, "Error stopping NDI streaming", e);
        }
    }

    private void updateStatus(String message) {
        runOnUiThread(() -> {
            mStatusText.setText(message);
            Log.i(TAG, "Status: " + message);
        });
    }

    private void showError(String message) {
        updateStatus("ERROR: " + message);
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
    }

    @Override
    protected void onPause() {
        stopNdiStreaming();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        stopNdiStreaming();

        if (mUVCCamera != null) {
            mUVCCamera.close();
            mUVCCamera = null;
        }

        if (mUSBMonitor != null) {
            mUSBMonitor.unregister();
            mUSBMonitor = null;
        }

        try {
            Ndi.shutdown();
        } catch (Exception e) {
            Log.e(TAG, "Error shutting down NDI", e);
        }

        super.onDestroy();
    }
}
