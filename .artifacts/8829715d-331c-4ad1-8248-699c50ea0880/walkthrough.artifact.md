# Walkthrough - Fixing "Surface was abandoned" Crash

I have addressed the `java.lang.IllegalArgumentException: Surface was abandoned` crash in the `InternalCameraHelper` class. This crash was caused by race conditions where surfaces were being released while the Camera2 system was attempting to configure a capture session.

## Changes

### [InternalCameraHelper.java](file:///C:/Users/Emil/Documents/Coding/ViewAndStream/UVCAndroid/app/src/main/java/com/herohan/uvcapp/InternalCameraHelper.java)

- **Thread Affinity for Session Management**:
    - All methods that affect the camera session state (`startPreview`, `stopPreview`, `setEncoderSurface`, `setFrameListener`, `takePicture`, `startRecording`, `stopRecording`, and `triggerTapToFocus`) now post their work to the `mBackgroundHandler`.
    - This ensures that all modifications to the `CameraCaptureSession`, `CameraDevice`, and `Surface` objects are serialized on the same background thread.

- **Robust `closeCamera` Implementation**:
    - The `closeCamera` method now signals the background thread to perform the cleanup (closing the session, the device, and image readers).
    - It uses a `postDelayed` call to `quitSafely()` the `HandlerThread`, allowing pending Camera2 callbacks (like `onClosed`) to be processed correctly before the thread terminates.
    - The `mStateCallback.onClosed()` notification is only triggered after the background cleanup is complete.

- **Defensive Surface Checks**:
    - Added `surface.isValid()` checks before adding surfaces to the `createCaptureSession` list or adding targets to `CaptureRequest` builders.
    - Wrapped session creation and request updates in `try-catch` blocks to handle `IllegalArgumentException` and `IllegalStateException` gracefully.

- **Resource Cleanup**:
    - `mPreviewSurface.release()` and `closeImageReaders()` are now called on the background thread to prevent them from being pulled out from under a running session configuration.

## Verification Results

### Automated Tests
- Ran `gradle assembleDebug` to ensure no compilation errors were introduced.
- [x] Build Successful

### Manual Verification Recommended
- Stress-test the application by:
    1.  Repeatedly opening and closing the internal camera.
    2.  Changing resolutions rapidly.
    3.  Starting/stopping RTMP streaming while the camera is active.
- Verify that the "Surface was abandoned" fatal exception no longer occurs.
