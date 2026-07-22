# Implementation Plan - Fix Camera Monitor Contention during RTMP Start

The goal is to eliminate the long monitor contention on the `RtmpWorker` thread by serializing camera session changes on a dedicated background thread and optimizing the RTMP connection sequence.

## User Review Required

> [!IMPORTANT]
> The changes involve offloading synchronous camera session management (`close` and `createCaptureSession`) to a background thread. While this improves UI and worker thread responsiveness, it introduces a small asynchronous delay in surface attachment. This is standard practice for Camera2 but should be monitored for any "black frame" duration increases during stream start.

## Proposed Changes

### libuvccamera (Project Source)

#### [MODIFY] [InternalCameraHelper.java](file:///C:/Users/Emil/Documents/Coding/ViewAndStream/UVCAndroid/app/src/main/java/com/herohan/uvcapp/InternalCameraHelper.java)
- Move the logic within `setEncoderSurface`, `setFrameListener`, `startPreview`, and `stopPreview` to run on `mBackgroundHandler`.
- Ensure that `mEncoderSurface` and `mFrameListener` field updates remain thread-safe while their session-affecting side effects are executed asynchronously.
- Refactor session restart logic into a private helper method to ensure consistency.

### app (Project Source)

#### [MODIFY] [MainActivity.java](file:///C:/Users/Emil/Documents/Coding/ViewAndStream/UVCAndroid/app/src/main/java/com/herohan/uvcapp/activity/MainActivity.java)
- In `startRtmpForwardingThread`, move the `setEncoderSurface` call immediately after `prepareVideo` and before `startStream`.
- This ensures the camera is aware of the new surface earlier and potentially completes its session transition before the network stack starts heavy activity.

## Verification Plan

### Automated Tests
- I will verify the code compiles and the `InternalCameraHelper` lifecycle remains stable.

### Manual Verification
- Deploy the app and start RTMP streaming.
- Monitor Logcat for the `Long monitor contention` warning.
- Verify that the stream starts successfully without prolonged freezes or crashes.
