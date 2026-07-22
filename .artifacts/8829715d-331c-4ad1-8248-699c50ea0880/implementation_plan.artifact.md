# Fix FATAL EXCEPTION: InternalCamera-bg (Surface was abandoned)

The application crashes with a `java.lang.IllegalArgumentException: Surface was abandoned` during `createCaptureSession` in `InternalCameraHelper`. This is caused by a race condition where a `Surface` (preview, encoder, or ImageReader surface) is released on one thread while the Camera2 background thread is attempting to use it to configure a new capture session.

## User Review Required

> [!IMPORTANT]
> The fix involves moving more logic to the background thread and adding defensive checks. This might slightly change the timing of camera state callbacks (like `onClosed`), but should be safer.

## Proposed Changes

### [app]

#### [MODIFY] [InternalCameraHelper.java](file:///C:/Users/Emil/Documents/Coding/ViewAndStream/UVCAndroid/app/src/main/java/com/herohan/uvcapp/InternalCameraHelper.java)

- **Synchronize Session Lifecycle**: Ensure all modifications to `mCaptureSession` and associated `Surface` objects are performed on the `mBackgroundHandler`.
- **Safe Surface Management**:
    - Move `mPreviewSurface.release()` and `closeImageReaders()` to the background thread.
    - Check `surface.isValid()` before adding to the session surfaces list.
- **Robust `closeCamera`**:
    - Post the majority of `closeCamera` logic to the background thread to ensure it's serialized with any pending `startPreviewSession` or `setEncoderSurface` calls.
    - Only signal `onClosed` after the background thread has finished cleaning up resources.
- **Defensive Session Creation**:
    - Wrap `createCaptureSession` in a try-catch block to handle `IllegalArgumentException` gracefully (logging it instead of crashing) if a surface is abandoned exactly during the call.
    - Validate that `mCameraDevice` and `mPreviewSurfaceTexture` are still valid immediately before starting session configuration.

## Verification Plan

### Automated Tests
- This issue is timing-dependent and hard to reproduce with unit tests without a mock camera environment.
- I will perform a code review of the changes to ensure synchronization is correct.

### Manual Verification
- Deploy the app and stress-test the internal camera:
    - Rapidly switch between different internal cameras.
    - Rapidly change resolutions.
    - Start and stop RTMP streaming while switching cameras/resolutions.
    - Check logcat for any "Surface was abandoned" warnings (which should now be caught and logged instead of crashing).
