package com.herohan.uvcdemo;

import android.hardware.usb.UsbDevice;
import android.os.Bundle;
import android.util.Log;
import android.view.SurfaceHolder;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;

import com.herohan.uvcapp.CameraHelper;
import com.herohan.uvcapp.ICameraHelper;
import com.serenegiant.usb.IFrameCallback;
import com.serenegiant.usb.Size;
import com.serenegiant.widget.AspectRatioSurfaceView;

import com.serenegiant.ndi.Ndi;
import com.serenegiant.ndi.NdiSender;
import com.serenegiant.ndi.UvcNdiFrameForwarder;

import java.util.List;

public class BasicPreviewActivity extends AppCompatActivity implements View.OnClickListener {

    private static final boolean DEBUG = true;
    private static final String TAG = BasicPreviewActivity.class.getSimpleName();

    private static final int DEFAULT_WIDTH = 640;
    private static final int DEFAULT_HEIGHT = 480;

    private ICameraHelper mCameraHelper;

    private AspectRatioSurfaceView mCameraViewMain;
    private TextView mStatusText;
    
    // NDI streaming components
    private NdiSender mNdiSender;
    private UvcNdiFrameForwarder mFrameForwarder;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_basic_preview);
        setTitle(R.string.entry_basic_preview);

        // Initialize NDI
        try {
            Ndi.initialize();
            Log.i(TAG, "NDI initialized. Version: " + Ndi.getNdiVersion());
        } catch (Exception e) {
            Log.e(TAG, "Failed to initialize NDI", e);
        }

        initViews();
    }

    private void initViews() {
        mCameraViewMain = findViewById(R.id.svCameraViewMain);
        mCameraViewMain.setAspectRatio(DEFAULT_WIDTH, DEFAULT_HEIGHT);

        mCameraViewMain.getHolder().addCallback(new SurfaceHolder.Callback() {
            @Override
            public void surfaceCreated(@NonNull SurfaceHolder holder) {
                if (mCameraHelper != null) {
                    mCameraHelper.addSurface(holder.getSurface(), false);
                }
            }

            @Override
            public void surfaceChanged(@NonNull SurfaceHolder holder, int format, int width, int height) {

            }

            @Override
            public void surfaceDestroyed(@NonNull SurfaceHolder holder) {
                if (mCameraHelper != null) {
                    mCameraHelper.removeSurface(holder.getSurface());
                }
            }
        });

        Button btnOpenCamera = findViewById(R.id.btnOpenCamera);
        btnOpenCamera.setOnClickListener(this);
        Button btnCloseCamera = findViewById(R.id.btnCloseCamera);
        btnCloseCamera.setOnClickListener(this);
    }

    @Override
    protected void onStart() {
        super.onStart();
        initCameraHelper();
    }

    @Override
    protected void onStop() {
        super.onStop();
        stopNdiStreaming();
        clearCameraHelper();
    }

    public void initCameraHelper() {
        if (DEBUG) Log.d(TAG, "initCameraHelper:");
        if (mCameraHelper == null) {
            mCameraHelper = new CameraHelper();
            mCameraHelper.setStateCallback(mStateListener);
        }
    }

    private void clearCameraHelper() {
        if (DEBUG) Log.d(TAG, "clearCameraHelper:");
        if (mCameraHelper != null) {
            mCameraHelper.release();
            mCameraHelper = null;
        }
    }

    private void selectDevice(final UsbDevice device) {
        if (DEBUG) Log.v(TAG, "selectDevice:device=" + device.getDeviceName());
        mCameraHelper.selectDevice(device);
    }

    private final ICameraHelper.StateCallback mStateListener = new ICameraHelper.StateCallback() {
        @Override
        public void onAttach(UsbDevice device) {
            if (DEBUG) Log.v(TAG, "onAttach:");
            selectDevice(device);
        }

        @Override
        public void onDeviceOpen(UsbDevice device, boolean isFirstOpen) {
            if (DEBUG) Log.v(TAG, "onDeviceOpen:");
            mCameraHelper.openCamera();
        }

        @Override
        public void onCameraOpen(UsbDevice device) {
            if (DEBUG) Log.v(TAG, "onCameraOpen:");

            Size size = mCameraHelper.getPreviewSize();
            if (size != null) {
                int width = size.width;
                int height = size.height;
                if (DEBUG) Log.i(TAG, "Camera resolution: " + width + "x" + height);
                
                //auto aspect ratio
                mCameraViewMain.setAspectRatio(width, height);
                
                // ✅ IMPORTANT: Set up NDI BEFORE starting preview
                // Create NDI sender and frame forwarder before any frames are captured
                try {
                    // Initialize NDI sender
                    String sourceName = "UVCAndroid-" + System.currentTimeMillis();
                    mNdiSender = new NdiSender(sourceName);
                    Log.i(TAG, "✅ NDI sender created: " + sourceName);

                    // Create frame forwarder BEFORE setting callback
                    mFrameForwarder = new UvcNdiFrameForwarder(mNdiSender, "nv12", null);
                    mFrameForwarder.setFrameDimensions(width, height);
                    Log.i(TAG, "✅ Frame forwarder configured for " + width + "x" + height);

                    // Register NDI frame forwarder as camera frame callback
                    // This MUST happen before startPreview()
                    if (mCameraHelper != null) {
                        mCameraHelper.setFrameCallback(mFrameForwarder, 2); // PIXEL_FORMAT_NV12 = 2
                        Log.i(TAG, "✅ Frame callback registered with mCameraHelper");
                    }
                } catch (Exception e) {
                    Log.e(TAG, "❌ Failed to create NDI sender", e);
                    e.printStackTrace();
                }
            }

            // NOW start preview - frames will go to NDI
            mCameraHelper.startPreview();
            Log.i(TAG, "✅ Camera preview started");
            
            mCameraHelper.addSurface(mCameraViewMain.getHolder().getSurface(), false);
            Log.i(TAG, "✅ Surface added to camera");
        }

        @Override
        public void onCameraClose(UsbDevice device) {
            if (DEBUG) Log.v(TAG, "onCameraClose:");
            
            // Stop NDI streaming
            stopNdiStreaming();

            if (mCameraHelper != null) {
                mCameraHelper.removeSurface(mCameraViewMain.getHolder().getSurface());
            }
        }

        @Override
        public void onDeviceClose(UsbDevice device) {
            if (DEBUG) Log.v(TAG, "onDeviceClose:");
        }

        @Override
        public void onDetach(UsbDevice device) {
            if (DEBUG) Log.v(TAG, "onDetach:");
        }

        @Override
        public void onCancel(UsbDevice device) {
            if (DEBUG) Log.v(TAG, "onCancel:");
        }

    };

    @Override
    public void onClick(View v) {
        if (v.getId() == R.id.btnOpenCamera) {
            // select a uvc device
            if (mCameraHelper != null) {
                final List<UsbDevice> list = mCameraHelper.getDeviceList();
                if (list != null && list.size() > 0) {
                    mCameraHelper.selectDevice(list.get(0));
                }
            }
        } else if (v.getId() == R.id.btnCloseCamera) {
            // close camera
            if (mCameraHelper != null) {
                mCameraHelper.closeCamera();
            }
        }
    }

    /**
     * Stop NDI streaming and clean up resources
     */
    private void stopNdiStreaming() {
        try {
            if (mCameraHelper != null) {
                mCameraHelper.setFrameCallback(null, 0);
                Log.i(TAG, "Frame callback unregistered");
            }

            if (mFrameForwarder != null) {
                mFrameForwarder = null;
                Log.i(TAG, "Frame forwarder released");
            }

            if (mNdiSender != null) {
                mNdiSender.close();
                mNdiSender = null;
                Log.i(TAG, "✅ NDI sender closed");
            }
        } catch (Exception e) {
            Log.e(TAG, "Error stopping NDI streaming", e);
        }
    }
}