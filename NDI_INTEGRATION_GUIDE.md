# NDI Integration Guide for UVCAndroid

## Quick Start

### 1. Add NDI Support to Your Android Project

The NDI integration is already built into the UVCAndroid library. Simply use the latest version:

```gradle
dependencies {
    implementation 'com.herohan:UVCAndroid:1.0.11' // or latest version with NDI support
}
```

### 2. Minimal Example

```java
import com.serenegiant.ndi.Ndi;
import com.serenegiant.ndi.NdiSender;
import com.serenegiant.ndi.UvcNdiFrameForwarder;
import com.serenegiant.usb.UVCCamera;

public class MyActivity extends Activity {
    
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        // Initialize NDI
        Ndi.initialize();
        
        // Create sender
        NdiSender sender = new NdiSender("My Camera");
        
        // Create forwarder
        UvcNdiFrameForwarder forwarder = new UvcNdiFrameForwarder(sender, "nv12");
        forwarder.setFrameDimensions(1920, 1080);
        
        // Forward frames from UVC camera
        uvcCamera.setFrameCallback(forwarder, UVCCamera.PIXEL_FORMAT_NV12);
    }
    
    @Override
    protected void onDestroy() {
        sender.close();
        Ndi.shutdown();
        super.onDestroy();
    }
}
```

## Supported Camera Formats

### Frame Format to Format ID Mapping

| Format | UVCCamera Constant | NDI Format | Use Case |
|--------|-------------------|-----------|----------|
| NV12 | `PIXEL_FORMAT_NV12` | NV12 (12-bit planar) | Most common, efficient |
| YUYV | `PIXEL_FORMAT_YUYV` | UYVY (16-bit packed) | Some USB capture cards |
| MJPEG | `PIXEL_FORMAT_MJPEG` | N/A | Need to decompress first |
| H.264 | `PIXEL_FORMAT_H264` | N/A | Need to decode first |

### Example: Different Formats

```java
// NV12 format (most efficient)
forwarder = new UvcNdiFrameForwarder(sender, "nv12");
uvcCamera.setFrameCallback(forwarder, UVCCamera.PIXEL_FORMAT_NV12);

// YUYV format
forwarder = new UvcNdiFrameForwarder(sender, "yuyv");
uvcCamera.setFrameCallback(forwarder, UVCCamera.PIXEL_FORMAT_YUYV);
```

## Architecture

### Component Diagram

```
┌─────────────────┐
│  UVC Camera     │
│   (USB Device)  │
└────────┬────────┘
         │
         │ Raw Frames
         │ (NV12/YUYV)
         ▼
┌─────────────────────────────────┐
│  UVCCamera (Android Library)    │
│  - USB Communication             │
│  - Frame Capture                │
└────────┬────────────────────────┘
         │
         │ IFrameCallback.onFrame()
         │
         ▼
┌─────────────────────────────────┐
│  UvcNdiFrameForwarder           │
│  - Bridges UVC to NDI           │
│  - Frame buffering              │
└────────┬────────────────────────┘
         │
         │ Frame data (byte[])
         │
         ▼
┌─────────────────────────────────┐
│  NdiSender (UVCAndroid-NDI)     │
│  - JNI Layer                     │
│  - to NDI C++ Library            │
└────────┬────────────────────────┘
         │
         │ Video frames
         │
         ▼
┌─────────────────────────────────┐
│  NDI C++ Library (libndi.so)    │
│  - Network transmission          │
│  - mDNS discovery               │
│  - NV12/YUYV/RGBA processing   │
└─────────────────────────────────┘
         │
         │ Network packets
         │
         ▼
  [NDI Receivers]
  - OBS Studio
  - NewTek software
  - Other NDI-compatible apps
```

## Advanced Usage

### With Event Callbacks

```java
INdiFrameSender callback = new INdiFrameSender() {
    @Override
    public void onNdiFrameAvailable(ByteBuffer frame, String format, int w, int h, long ts) {
        Log.d("NDI", "Frame sent: " + w + "x" + h + " " + format);
    }
    
    @Override
    public void onNdiError(String msg, Throwable t) {
        Log.e("NDI", msg, t);
    }
    
    @Override
    public void onNdiConnectionChanged(boolean connected) {
        Log.i("NDI", connected ? "Connected" : "Disconnected");
    }
};

UvcNdiFrameForwarder forwarder = new UvcNdiFrameForwarder(sender, "nv12", callback);
```

### Dynamic Resolution Handling

```java
// When resolution changes
uvcCamera.setPreviewSize(new int[]{width, height});
forwarder.setFrameDimensions(width, height);
```

### Frame Rate Control

The NDI sender uses 30fps by default. Modify in [ndi-sender.cpp](libuvccamera/src/main/cpp/ndi/ndi-sender.cpp):

```cpp
videoFrame.frame_rate_N = 30000;  // 30 fps
videoFrame.frame_rate_D = 1000;
```

### Monitor Streaming Performance

```java
// Get frame statistics
long frameCount = forwarder.getFrameCount();
forwarder.resetFrameCount();
```

## Manifest Permissions

Add to `AndroidManifest.xml`:

```xml
<!-- Per USB camera access -->
<uses-permission android:name="android.permission.USB" />

<!-- Per network access for NDI discovery -->
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />

<!-- Recommended but not required -->
<uses-permission android:name="android.permission.CHANGE_NETWORK_STATE" />
```

## Network Configuration

### For mDNS Discovery to Work

The device needs to support mDNS/Bonjour for NDI sources to be discoverable:

- **Android 6.0+**: mDNS support is built-in
- **Devices**: Must be on same LAN (192.168.x.x or similar)
- **Firewall**: Should allow mDNS on port 5353 (UDP)
- **Router**: Should not block multicast traffic

### Testing Network Connectivity

```java
// Verify NDI is working
Log.i("NDI", "Version: " + Ndi.getNdiVersion());
Log.i("NDI", "CPU supported: " + Ndi.isCPUSupported());
```

## Troubleshooting

### Issue: NdiSender.nSendCreate returned 0

**Cause**: NDI library initialization failed

**Solutions**:
1. Verify `Ndi.initialize()` was called successfully
2. Check if CPU is supported: `Ndi.isCPUSupported()`
3. Verify .so files exist in `jniLibs/` directory
4. Check logcat for detailed errors: `adb logcat UVCNdiWrapper`

### Issue: Frames not transmitting

**Cause**: Wrong format or frame dimensions

**Solutions**:
1. Verify frame format matches camera output:
   ```java
   Log.i("NDI", "Camera format: " + uvcCamera.getPreviewFormat());
   ```
2. Ensure dimensions are set: `forwarder.setFrameDimensions(w, h)`
3. Verify UVC camera is actually sending frames (not paused)

### Issue: High CPU usage

**Cause**: Inefficient frame conversion or high resolution

**Solutions**:
1. Reduce resolution
2. Reduce frame rate (modify in C++ code)
3. Use hardware-accelerated formats (NV12)
4. Profile with Android Profiler

### Issue: NDI source not visible in receivers

**Cause**: Network isolation or mDNS issue

**Solutions**:
1. Verify devices are on same network
2. Ping the other device to confirm connectivity
3. Check firewall allows UDP port 5353
4. Restart NDI receiver application
5. Check if receiver supports the source format

## Performance Notes

- **Resolution**: 1080p30 ~= 70-100 Mbps network usage
- **Formats**: NV12 most efficient, RGBA uses 2x bandwidth
- **CPU**: ~5-10% per core for format conversion
- **Memory**: ~10-30 MB per frame buffer

## Related Documentation

- [NDI Official Documentation](https://ndi.tv/)
- [UVCAndroid GitHub](https://github.com/shiyinghan/UVCAndroid)
- [devolay - NDI Java wrapper](https://github.com/WalkerKnapp/devolay)
- [NDI SDK for Android](https://www.newtek.com/inputs/ndi-sdk/)

## Hardware Compatibility

### Tested Cameras
- UGREEN USB Capture Card 4K
- Logitech C920 HD Pro Webcam
- Standard USB HDMI capture devices
- Most UVC-compatible USB cameras

### ABI Support
- ✅ arm64-v8a (Most common modern Android)
- ✅ armeabi-v7a (Older devices)
- ✅ x86 (Emulator/Tablet)
- ✅ x86_64 (Newer emulator/Tablet)

## Contributing & Issues

For issues or improvements, please contribute to the UVCAndroid project or create an issue with:
1. Android version
2. Device model
3. Camera model
4. Logcat output
5. Steps to reproduce
