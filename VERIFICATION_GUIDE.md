# Verification & Testing Guide

After integrating NDI support into UVCAndroid, use this guide to verify everything is working correctly.

## Pre-Build Verification

### 1. Check File Structure

```bash
# Verify NDI Java classes exist
ls -la libuvccamera/src/main/java/com/serenegiant/ndi/

# Expected output:
# Ndi.java
# NdiSender.java
# NdiVideoFrame.java
# NdiFrameCleaner.java
# FourCCType.java
# UvcNdiFrameForwarder.java
# INdiFrameSender.java
```

### 2. Check C++ Files

```bash
# Verify NDI C++ files
ls -la libuvccamera/src/main/cpp/ndi/

# Expected output:
# ndi-wrapper.h
# ndi.cpp
# ndi-sender.cpp
# color-conversion.cpp
# CMakeLists.txt
# include/ (with 18 NDI SDK headers)
```

### 3. Check NDI Libraries

```bash
# Verify native libraries for all architectures
for arch in arm64-v8a armeabi-v7a x86 x86_64; do
    if [ -f "libuvccamera/src/main/jniLibs/$arch/libndi.so" ]; then
        echo "✓ Found libndi.so for $arch"
    else
        echo "✗ Missing libndi.so for $arch"
    fi
done
```

### 4. Check Build Configuration

```bash
# Verify Android.mk includes NDI module
grep -l "ndi/Android.mk" libuvccamera/src/main/jni/Android.mk

# Output should show the file path if found
```

---

## Build Verification

### 1. Clean Build

```bash
# Clean previous builds
./gradlew clean

# Build the NDI-enabled library
./gradlew build

# Check for build success
echo $?  # Should output 0 for success
```

### 2. Check Build Output

```bash
# Verify uvc-ndi-wrapper library was built
adb shell find /system -name "*uvc-ndi-wrapper*" 2>/dev/null

# Or check your APK/AAR
unzip -l app/build/outputs/apk/debug/app-debug.apk | grep "libuvc-ndi-wrapper"
```

### 3. Monitor Build Logs

```bash
# Build with detailed output
./gradlew build -d 2>&1 | grep -E "NDI|ndi"

# Look for compilation of:
# - ndi.cpp
# - ndi-sender.cpp
# - color-conversion.cpp
```

---

## Runtime Verification

### 1. Create Test Application

Create a simple test activity:

```java
import android.app.Activity;
import android.os.Bundle;
import android.util.Log;
import android.widget.TextView;

import com.serenegiant.ndi.Ndi;

public class TestActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        TextView text = new TextView(this);
        setText("Testing NDI Integration...");
        
        try {
            Ndi.initialize();
            setText("✓ NDI Initialized\n" +
                   "Version: " + Ndi.getNdiVersion() + "\n" +
                   "CPU Supported: " + Ndi.isCPUSupported());
        } catch (Exception e) {
            setText("✗ NDI Error: " + e.getMessage());
            Log.e("NDI", "Error", e);
        }
        
        setContentView(text);
    }
}
```

### 2. Run Test

```bash
# Install and run
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n com.example/.TestActivity

# Watch logcat
adb logcat | grep -E "NDI|UVCNdiWrapper"
```

### 3. Check Logcat Output

**Expected output**:
```
I/UVCNdiWrapper: NDI library initialized successfully
I/UVCNdiWrapper: NDI version: 6.x.x
I/TestActivity: ✓ NDI Initialized
I/TestActivity: Version: 6.x.x
I/TestActivity: CPU Supported: true
```

**Error indicators**:
```
E/UVCNdiWrapper: Failed to initialize NDI library
E/UVCNdiWrapper: NDI sender pointer is null
```

---

## Integration Verification

### 1. Test UVC + NDI Integration

```java
// After camera is ready
NdiSender sender = new NdiSender("TestCamera");
UvcNdiFrameForwarder forwarder = new UvcNdiFrameForwarder(sender, "nv12");
forwarder.setFrameDimensions(1280, 720);

uvcCamera.setFrameCallback(forwarder, UVCCamera.PIXEL_FORMAT_NV12);
uvcCamera.startCapture();

// Check logcat
// Should see: "UVCNdiWrapper: Frame sent" messages

// After ~10 seconds, frame count should increase:
Log.i("Test", "Frames: " + forwarder.getFrameCount());
```

### 2. Monitor Frame Rate

```bash
# Watch frame forwarding
adb logcat | grep "UVCNdiWrapper\|Frame\|Frames"

# Should see entries like:
# UVCNdiWrapper: Forwarded frame 1 (1280x720)
# UVCNdiWrapper: Forwarded frame 2 (1280x720)
```

### 3. Check Network

```bash
# Verify NDI discovery
adb shell netstat -tuln | grep 5353

# Check mDNS traffic (requires network analyzer)
tcpdump -i any -n "udp port 5353" -c 10
```

---

## OBS Studio Verification (Production Test)

### Prerequisites
- OBS Studio installed on Windows/Mac/Linux
- OBS NDI plugin installed
- Android device and computer on same WiFi

### Steps

1. **Start Streaming**:
```java
Ndi.initialize();
NdiSender sender = new NdiSender("TestCamera");
UvcNdiFrameForwarder forwarder = new UvcNdiFrameForwarder(sender, "nv12");
uvcCamera.setFrameCallback(forwarder, UVCCamera.PIXEL_FORMAT_NV12);
uvcCamera.startCapture();
```

2. **Open OBS Studio**
   - Install NDI Plugin if not already installed
   - Create new scene
   - Add source → NDI Source
   - Should see "TestCamera" in the list

3. **Verify**
   - Video should appear in preview
   - No lag or artifacts
   - Green status indicator

4. **Monitor**
   ```
   adb logcat TestCamera
   # Should show active streaming
   ```

---

## Performance Verification

### CPU Usage

```bash
# Monitor CPU per process
adb shell top -n 1 | grep -E "PID|app_process|kworker"

# Expected: <10% CPU usage for normal resolution
```

### Memory Usage

```bash
# Check memory allocation
adb shell dumpsys meminfo | grep -A 10 "TOTAL"

# Expected: 50-100MB additional for NDI

# Monitor in real-time
adb shell watch -n 1 "dumpsys meminfo | head -20"
```

### Network Bandwidth

On computer monitoring network:
```bash
# For 1080p30 expect ~70-100 Mbps
wireshark -k -i en0 -f "udp port 5353"  # mDNS discovery
wireshark -k -i en0 -f "host 192.168.x.x"  # Android device traffic
```

---

## Troubleshooting Verification

### Issue: NDI initialization fails

**Check**:
```bash
# 1. Verify .so files
adb shell ls -la /data/app/com.example.app/lib/*/

# 2. Check logcat for specific error
adb logcat | grep "UVCNdiWrapper\|libndi"

# 3. Verify CPU support
adb shell getprop ro.product.cpu.abilist
# Should show: arm64-v8a or similar
```

### Issue: Sender fails to create

**Check**:
```java
// Verify NDI is initialized BEFORE creating sender
try {
    Ndi.initialize();
    Log.i("NDI", "Init: " + Ndi.isCPUSupported());
    
    NdiSender sender = new NdiSender("Test");
    Log.i("NDI", "Sender created");
} catch (Exception e) {
    Log.e("NDI", "Error", e);
}
```

### Issue: Frames not transmitting

**Check**:
```bash
# Verify frame callback is being called
adb logcat | grep "onFrame\|forward"

# Check frame dimensions match camera
adb logcat | grep "dimension\|dimension"

# Verify format matches
adb logcat | grep "format\|nv12\|yuyv"
```

### Issue: Source not visible in OBS

**Check**:
```bash
# 1. Verify network connectivity
adb shell ping -c 4 192.168.1.1

# 2. Check mDNS resolution
adb shell nslookup -type=mdns <device_name>.local

# 3. Monitor NDI discovery
adb logcat | grep "discover\|advertise"

# 4. Restart OBS and check for sources
```

---

## Automated Testing Script

Save this as `test_ndi.sh`:

```bash
#!/bin/bash

echo "=== NDI Integration Test Suite ==="
echo ""

# Test 1: File structure
echo "Test 1: Checking file structure..."
if [ -f "libuvccamera/src/main/java/com/serenegiant/ndi/Ndi.java" ]; then
    echo "✓ Java files exist"
else
    echo "✗ Java files missing"
    exit 1
fi

# Test 2: C++ files
echo "Test 2: Checking C++ files..."
if [ -f "libuvccamera/src/main/cpp/ndi/ndi.cpp" ]; then
    echo "✓ C++ files exist"
else
    echo "✗ C++ files missing"
    exit 1
fi

# Test 3: Native libraries
echo "Test 3: Checking native libraries..."
for arch in arm64-v8a armeabi-v7a x86 x86_64; do
    if [ -f "libuvccamera/src/main/jniLibs/$arch/libndi.so" ]; then
        echo "✓ libndi.so found for $arch"
    else
        echo "✗ libndi.so missing for $arch"
    fi
done

# Test 4: Build configuration
echo "Test 4: Checking build configuration..."
if grep -q "ndi/Android.mk" "libuvccamera/src/main/jni/Android.mk"; then
    echo "✓ Android.mk configured"
else
    echo "✗ Android.mk not configured"
fi

# Test 5: Clean build
echo "Test 5: Building project..."
./gradlew clean build > /dev/null 2>&1
if [ $? -eq 0 ]; then
    echo "✓ Build successful"
else
    echo "✗ Build failed"
    ./gradlew build 2>&1 | tail -20
    exit 1
fi

echo ""
echo "=== All Tests Passed! ==="
echo "NDI integration is ready for use."
```

Run it:
```bash
chmod +x test_ndi.sh
./test_ndi.sh
```

---

## Success Criteria

✅ **Expected Results When Everything Works**:

1. **Build**:
   - [ ] Project builds without errors
   - [ ] No NDI-related warnings
   - [ ] uvc-ndi-wrapper library created

2. **Runtime**:
   - [ ] `Ndi.initialize()` succeeds
   - [ ] Sender can be created
   - [ ] Frames are forwarded
   - [ ] No JNI exceptions

3. **Network**:
   - [ ] NDI source appears in receivers
   - [ ] Video transmits without artifacts
   - [ ] mDNS discovery works
   - [ ] Proper frame rate maintained

4. **Performance**:
   - [ ] CPU usage < 10%
   - [ ] Memory stable around 50-100MB
   - [ ] No frame drops
   - [ ] Bandwidth matches bitrate calculation

---

## Getting Help

If verification fails at any step:

1. **Check documentation**:
   - NDI_INTEGRATION_GUIDE.md (Troubleshooting section)
   - QUICK_NDI_INTEGRATION.md (Common issues)

2. **Review logs**:
   ```
   adb logcat > ndi_logs.txt
   # Search for ERROR or warnings
   ```

3. **Verify prerequisites**:
   - Android NDK installed
   - Gradle properly configured
   - Devices on same network

4. **Test minimal example**:
   - Use TestActivity from "Test Application" section above
   - Test with NdiCameraExampleActivity.java

---

**Verification complete when all tests pass successfully! 🎉**
