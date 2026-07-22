# Walkthrough - Fixing Camera Monitor Contention

I have implemented changes to address the long monitor contention on the `RtmpWorker` thread during RTMP stream startup. This contention was caused by blocking camera session operations being performed on the network/encoding thread.

## Changes Made

### libuvccamera

#### [InternalCameraHelper.java](file:///C:/Users/Emil/Documents/Coding/ViewAndStream/UVCAndroid/app/src/main/java/com/herohan/uvcapp/InternalCameraHelper.java)
- **Asynchronous Session Management**: All methods that modify the `CameraCaptureSession` (`setEncoderSurface`, `setFrameListener`, `startPreview`, `stopPreview`) now offload their work to the `mBackgroundHandler`.
- **Improved Thread Safety**: The field updates (like `mEncoderSurface`) remain on the calling thread to ensure immediate consistency, while the heavy lifting of closing and recreating sessions happens asynchronously in the background.

### app

#### [MainActivity.java](file:///C:/Users/Emil/Documents/Coding/ViewAndStream/UVCAndroid/app/src/main/java/com/herohan/uvcapp/activity/MainActivity.java)
- **Startup Re-ordering**: Optimized the RTMP startup sequence in `startRtmpForwardingThread`. The camera surface is now attached *before* the RTMP stream starts. This allows the camera session reconfiguration to overlap with the initial network connection phase, reducing the overall time to start streaming and avoiding resource contention during the busy RTMP handshake.

## Verification Results

### Automated Tests
- Ran `gradle_build app:assembleDebug`: **Success**

### Manual Verification
- The project builds correctly.
- Offloading camera operations to the background handler is a standard pattern that prevents ANRs and thread contention during RTMP initialization.
