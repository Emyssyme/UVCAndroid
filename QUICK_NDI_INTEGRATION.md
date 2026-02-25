# Quick Integration - Add NDI Streaming to Your UVC Camera App

This guide shows how to add NDI streaming to an existing UVCAndroid-based application in just a few lines of code.

> **Note:** the sender now tags frames as *progressive* and advertises 30 fps, so receivers (OBS, etc.) will see 4K‑30p instead of defaulting to 60i.

> **Note on streams:** preview, NDI and file recording are three independent consumers of the
> camera frames.  Preview is drawn to a Surface/TextureView, NDI uses a frame callback, and
> recording (if you need it) is handled by a separate encoder configuration.  They can all run
> concurrently and you can adjust each path independently.  The NDI flow still starts
> automatically as shown below, while you are free to tune recording for the best compression.

## Step 1: Update Your Activity Constructor

Add NDI initialization:

```java
import com.serenegiant.ndi.Ndi;

@Override
protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    setContentView(R.layout.activity_camera);
    
    // NEW: Initialize NDI
    try {
        Ndi.initialize();
    } catch (Exception e) {
        Log.e(TAG, "NDI init failed", e);
    }
    
    // ... rest of your code ...
}
```

## Step 2: Create NDI Sender When Camera is Ready

(Preview configuration is unaffected – you keep whatever surface/texture setup you already had.)


```java
import com.serenegiant.ndi.NdiSender;
import com.serenegiant.ndi.UvcNdiFrameForwarder;

private NdiSender mNdiSender;
private UvcNdiFrameForwarder mNdiForwarder;

// Call this after your UVCCamera is ready
private void setupNdiStreaming() {
    try {
        // Create NDI sender
        mNdiSender = new NdiSender("AndroidCamera-" + System.currentTimeMillis());
        
        // Create forwarder
        mNdiForwarder = new UvcNdiFrameForwarder(mNdiSender, "nv12");
        mNdiForwarder.setFrameDimensions(1920, 1080);
        
        Log.i(TAG, "NDI sender created");
    } catch (Exception e) {
        Log.e(TAG, "NDI setup failed", e);
    }
}
```

## Step 3: Forward Frames from UVC Camera

Replace your frame callback setup:

```java
// OLD: Just displaying frames
// mTextureView.setFrameCallback(null, 0);

// NEW: Forward frames to NDI
if (mNdiForwarder != null) {
    mUVCCamera.setFrameCallback(mNdiForwarder, UVCCamera.PIXEL_FORMAT_NV12);
} else {
    mUVCCamera.setFrameCallback(null, 0);
}
```

## Step 3.5: (Optional) Configure High‑Quality Recording

If you also want to record a local file with the best possible quality, adjust the video capture
configuration before starting the camera.  The example app uses `CameraHelper`, but the idea is
that you increase the bitrate/frame‑rate to whatever your device and storage can sustain.

```java
// call this once (for example from onCreate) before opening the camera
private void setCustomVideoCaptureConfig() {
    mCameraHelper.setVideoCaptureConfig(
            mCameraHelper.getVideoCaptureConfig()
                    // use a large bitrate for high quality
                    .setBitRate(30 * 1024 * 1024)   // 30 Mbps
                    .setVideoFrameRate(30)
                    .setIFrameInterval(1)
                    // optionally disable audio if you only need video
                    //.setAudioCaptureEnable(false)
    );
}
```

Then start/stop recording as shown in the main app (`toggleVideoRecord()` etc.).  This
configuration is completely separate from the NDI sender; recording uses the usual
`VideoCapture` path and does **not** affect the NDI or preview streams.

## Step 4: Cleanup in onDestroy

```java
@Override
protected void onDestroy() {
    // NEW: Close NDI sender
    if (mNdiSender != null) {
        mNdiSender.close();
        mNdiSender = null;
    }
    
    // NEW: Shutdown NDI
    try {
        Ndi.shutdown();
    } catch (Exception e) {
        Log.e(TAG, "NDI shutdown error", e);
    }
    
    // ... rest of your cleanup ...
    super.onDestroy();
}
```

## Complete Example

```java
import android.app.Activity;
import android.hardware.usb.UsbDevice;
import android.os.Bundle;
import android.util.Log;

import com.serenegiant.usb.USBMonitor;
import com.serenegiant.usb.UVCCamera;
import com.serenegiant.widget.UVCCameraTextureView;

// NEW IMPORTS
import com.serenegiant.ndi.Ndi;
import com.serenegiant.ndi.NdiSender;
import com.serenegiant.ndi.UvcNdiFrameForwarder;

public class CameraActivity extends Activity {
    private static final String TAG = "CameraActivity";
    
    // Existing variables
    private USBMonitor mUSBMonitor;
    private UVCCamera mUVCCamera;
    private UVCCameraTextureView mTextureView;
    
    // NEW: NDI variables
    private NdiSender mNdiSender;
    private UvcNdiFrameForwarder mNdiForwarder;
    
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_camera);
        
        // NEW: Initialize NDI
        try {
            Ndi.initialize();
            Log.i(TAG, "NDI initialized: " + Ndi.getNdiVersion());
        } catch (Exception e) {
            Log.e(TAG, "NDI init failed", e);
        }
        
        mTextureView = findViewById(R.id.camera_view);
    // NOTE: the UVCCameraTextureView supports pinch‑to‑zoom and double‑tap (100%)
    // so you can see the original resolution even when the view is smaller.
        mUSBMonitor = new USBMonitor(this, onDeviceConnectListener);
        mUSBMonitor.register();
    }
    
    private final USBMonitor.OnDeviceConnectListener onDeviceConnectListener =
            new USBMonitor.OnDeviceConnectListener() {
        @Override
        public void onConnect(UsbDevice device, USBMonitor.UsbControlBlock ctrlBlock, boolean createNew) {
            if (mUVCCamera == null) {
                mUVCCamera = new UVCCamera();
                mUVCCamera.open(ctrlBlock);
                
                // NEW: Setup NDI when camera is ready
                setupNdiStreaming();
                
                // Start camera preview (regular Surface/TextureView path)
                mUVCCamera.setPreviewSize(1920, 1080);
                mUVCCamera.setPreviewDisplay(mTextureView.getSurfaceTexture());
                
                // NEW: Forward frames to NDI.  This callback runs in parallel to the preview
                // and does not interfere with the Surface output; the two streams are independent.
                if (mNdiForwarder != null) {
                    mUVCCamera.setFrameCallback(mNdiForwarder, UVCCamera.PIXEL_FORMAT_NV12);
                }
                
                mUVCCamera.startCapture();
            }
        }
        
        @Override
        public void onDisconnect(UsbDevice device, USBMonitor.UsbControlBlock ctrlBlock) {
            if (mUVCCamera != null) {
                mUVCCamera.stopCapture();
                mUVCCamera.close();
                mUVCCamera = null;
            }
        }
        
        @Override
        public void onAttach(UsbDevice device) {}
        @Override
        public void onDettach(UsbDevice device) {}
        @Override
        public void onCancel(UsbDevice device) {}
    };
    
    // NEW: Setup NDI streaming
    private void setupNdiStreaming() {
        try {
            mNdiSender = new NdiSender("AndroidCamera-" + System.currentTimeMillis());
            mNdiForwarder = new UvcNdiFrameForwarder(mNdiSender, "nv12");
            mNdiForwarder.setFrameDimensions(1920, 1080);
            Log.i(TAG, "NDI sender created");
        } catch (Exception e) {
            Log.e(TAG, "NDI setup failed", e);
        }
    }
    
    @Override
    protected void onDestroy() {
        // NEW: Cleanup NDI
        if (mNdiSender != null) {
            mNdiSender.close();
        }
        
        if (mUVCCamera != null) {
            mUVCCamera.stopCapture();
            mUVCCamera.close();
            mUVCCamera = null;
        }
        
        if (mUSBMonitor != null) {
            mUSBMonitor.unregister();
        }
        
        try {
            Ndi.shutdown();
        } catch (Exception e) {
            Log.e(TAG, "NDI shutdown error", e);
        }
        
        super.onDestroy();
    }
}
```

## Minimal Changes Checklist

- [ ] Add `Ndi.initialize()` in `onCreate()`
- [ ] Create `NdiSender` and `UvcNdiFrameForwarder` when camera is ready
- [ ] Set frame dimensions: `forwarder.setFrameDimensions(w, h)`
- [ ] Forward frames: `camera.setFrameCallback(forwarder, format)`
- [ ] Close sender and shutdown NDI in `onDestroy()`
- [ ] Add permissions to `AndroidManifest.xml`:
  ```xml
  <uses-permission android:name="android.permission.INTERNET" />
  <uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
  ```

## Format Support

Update the format string based on your camera and desired bandwidth:

```java
// Default (maximum quality)
// the forwarder will transmit RGBA frames; this mode uses the most bandwidth but
// avoids additional NDI compression.  Your network must support the resulting
// data rate (e.g. several hundred Mbps at 1080p30).
mNdiForwarder = new UvcNdiFrameForwarder(mNdiSender, "rgba");
mUVCCamera.setFrameCallback(mNdiForwarder, UVCCamera.PIXEL_FORMAT_NV12);

// Alternative efficient formats:
// mNdiForwarder = new UvcNdiFrameForwarder(mNdiSender, "nv12");
// mUVCCamera.setFrameCallback(mNdiForwarder, UVCCamera.PIXEL_FORMAT_NV12);

// mNdiForwarder = new UvcNdiFrameForwarder(mNdiSender, "yuyv");
// mUVCCamera.setFrameCallback(mNdiForwarder, UVCCamera.PIXEL_FORMAT_YUYV);
```
```markdown
// Note: the preview path itself is unaffected by this choice; only the network
// stream changes.  RGBA will generate very high bandwidth usage (e.g. ~370 Mbps
// at 1080p30) so use it only on fast LANs or for short bursts.
```

## Testing

After adding NDI support:

1. **Verify NDI works**:
   ```
   adb logcat | grep "UVCNdiWrapper\|NDI"
   ```

2. **Open OBS Studio** (or other NDI receiver) on the same network

3. **Look for your source** - Should appear as "AndroidCamera-[timestamp]"

4. **Monitor performance**:
   ```
   adb shell top | grep -E "kworker|ndi|app_process"
   ```

## Troubleshooting

### Still not seeing the source in OBS?
- Verify devices are on same WiFi network
- Check Windows Firewall (allow mDNS on port 5353)
- Verify OBS has NDI plugin installed
- Restart NDI discovery: Close and reopen OBS

### Camera freezing?
- Reduce frame dimensions
- Check network bandwidth usage
- Verify USB cable quality
- Monitor CPU usage: `adb shell top`

### Getting errors in logcat?
- Search for "UVCNdiWrapper" for native errors
- Search for "NdiSender" or "NdiCamera" for Java-level errors
- Check "NDI" for initialization errors

## Next Steps

1. **Add UI controls** - Button to start/stop streaming *and* toggle NDI quality.  The sample app now includes an ``NDI Mode`` entry in the overflow menu which switches between
   high‑quality (RGBA) and efficient (NV12) on the fly.
2. **Add event callbacks** - Monitor stream quality
3. **Add frame rate control** - Adjust FPS based on network
4. **Add resolution selection** - Dynamic resolution change
5. **Add audio support** - Future enhancement


See [NDI_INTEGRATION_GUIDE.md](NDI_INTEGRATION_GUIDE.md) for advanced usage patterns.

---

That's it! Your UVC camera is now streaming via NDI! 🎥📡
