# NDI Integration for UVCAndroid

This module adds support for transmitting UVC camera frames over the network using the NDI (Network Device Interface) protocol.

## Overview

The NDI integration allows you to:
- Capture video from UVC USB cameras
- Stream it over the network via NDI protocol  
- Make the video discoverable to any NDI receiver/consumer on the same network
- Convert and forward frames in various formats (NV12, YUYV, RGBA)

## Components

### Java Classes

- **`Ndi`** - Initialization class for NDI library. Call `Ndi.initialize()` at app startup.
- **`NdiSender`** - Main class for sending video frames via NDI. Supports multiple formats.
- **`NdiVideoFrame`** - Wrapper for NDI video frame metadata and data
- **`UvcNdiFrameForwarder`** - Helper class that bridges UVC camera callbacks to NDI sender
- **`INdiFrameSender`** - Optional callback interface for frame events
- **`FourCCType`** - Enumeration of supported color formats

### C++ Components

- **`ndi-wrapper.h`** - Main NDI wrapper header
- **`ndi.cpp`** - NDI initialization and version methods
- **`ndi-sender.cpp`** - JNI bindings for video transmission
- **`color-conversion.cpp`** - Color format conversion utilities

## Setup

### 1. Initialize NDI

At your application's startup, initialize the NDI library:

```java
import com.serenegiant.ndi.Ndi;

public class MyApplication extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        
        // Initialize NDI
        try {
            Ndi.initialize();
            Log.i(TAG, "NDI version: " + Ndi.getNdiVersion());
        } catch (Exception e) {
            Log.e(TAG, "Failed to initialize NDI", e);
        }
    }
}
```

### 2. Create an NDI Sender

```java
import com.serenegiant.ndi.NdiSender;

// Create sender (this source will be visible to NDI receivers)
NdiSender sender = new NdiSender("My Camera");
```

### 3. Forward UVC Framework to NDI

```java
import com.serenegiant.ndi.UvcNdiFrameForwarder;

// Create forwarder to bridge UVC frames to NDI
UvcNdiFrameForwarder forwarder = new UvcNdiFrameForwarder(sender, "nv12");
forwarder.setFrameDimensions(1920, 1080);

// Set as frame callback on UVCCamera
uvcCamera.setFrameCallback(forwarder, IFrameCallback.PIXEL_FORMAT_NV12);
```

### 4. Shutdown

```java
import com.serenegiant.ndi.Ndi;

// When done, cleanup resources
sender.close();
Ndi.shutdown();
```

## Supported Formats

The NDI integration supports the following video formats:

| Format | Description | CPP Enum |
|--------|-------------|----------|
| YUYV | 16-bit YUV 4:2:2 (no alpha) | `NDIlib_FourCC_type_UYVY` |
| NV12 | 12-bit planar YUV 4:2:0 | `NDIlib_FourCC_type_NV12` |
| RGBA | 32-bit RGBA (with alpha) | `NDIlib_FourCC_type_RGBA` |
| BGRA | 32-bit BGRA (with alpha) | `NDIlib_FourCC_type_BGRA` |

## Example: Complete Integration

```java
import android.app.Activity;
import android.os.Bundle;
import android.util.Log;

import com.serenegiant.ndi.Ndi;
import com.serenegiant.ndi.NdiSender;
import com.serenegiant.ndi.UvcNdiFrameForwarder;
import com.serenegiant.usb.IFrameCallback;
import com.serenegiant.usb.UVCCamera;

public class NdiCameraActivity extends Activity {
    private static final String TAG = "NdiCamera";
    
    private NdiSender ndiSender;
    private UvcNdiFrameForwarder frameForwarder;
    private UVCCamera uvcCamera;
    
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        // Initialize NDI
        try {
            Ndi.initialize();
            Log.i(TAG, "NDI initialized: " + Ndi.getNdiVersion());
        } catch (Exception e) {
            Log.e(TAG, "Failed to initialize NDI", e);
            return;
        }
        
        // Create NDI sender
        ndiSender = new NdiSender("AndroidCamera-" + System.currentTimeMillis());
        
        // Setup UVC camera
        setupUvcCamera();
    }
    
    private void setupUvcCamera() {
        // Your UVC camera setup code here
        // When ready, forward frames to NDI
        
        frameForwarder = new UvcNdiFrameForwarder(ndiSender, "nv12");
        frameForwarder.setFrameDimensions(1920, 1080);
        
        // Assuming you have a UVCCamera instance
        // uvcCamera.setFrameCallback(frameForwarder, UVCCamera.PIXEL_FORMAT_NV12);
    }
    
    @Override
    protected void onDestroy() {
        // Cleanup
        if (ndiSender != null) {
            ndiSender.close();
        }
        Ndi.shutdown();
        
        super.onDestroy();
    }
}
```

## Build Configuration

The NDI integration is automatically compiled with the library through the Android NDK build system. The necessary .so libraries for NDI are included in the `src/main/jniLibs/` directory for all supported architectures:

- `armeabi-v7a`
- `arm64-v8a`
- `x86`
- `x86_64`

## Network Requirements

- All devices must be on the same local network (LAN)
- NDI uses mDNS for discovery, so multicast traffic should be allowed
- Typical bandwidth requirement: ~50 Mbps for 1080p30fps video

## Troubleshooting

### NDI not initializing
```
Check: NDIlib_initialize() might fail if:
- CPU is not supported (older ARM cores)
- System library compatibility issues
- Missing .so files in jniLibs
```

### Frames not being sent
```
Check:
- Verify sender instance was created successfully
- Ensure frame dimensions are set correctly
- Check frame format matches your camera output (usually NV12)
- Monitor logcat for JNI errors
```

### Performance issues
```
Tips:
- Consider frame rate reduction
- Use smaller resolution
- Check network bandwidth
- Verify thread priorities are correct
```

## License

This NDI integration is based on code from [devolay](https://github.com/WalkerKnapp/devolay) (Apache 2.0 License).

NDI is a protocol by NewTek. For more information visit: https://ndi.tv/
