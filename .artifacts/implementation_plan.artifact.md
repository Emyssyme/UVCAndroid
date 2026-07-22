# Implementation Plan - Fix "Surface was abandoned" Crash in InternalCameraHelper

The application crashes with `java.lang.IllegalArgumentException: Surface was abandoned` when creating a `CameraCaptureSession`. This typically occurs when a `Surface` (usually from a `TextureView`) is released or destroyed before the `Camera2` API finishes configuring the session.

## User Review Required

> [!IMPORTANT]
> The fix involves validating all `Surface` objects before they are used in `Camera2` session creation or capture requests. If the main preview surface is found to be invalid, session creation will be aborted to prevent the crash.
>
> Additionally, a bug in `triggerTapToFocus` where a new `Surface` was created on every tap without being released will be fixed.

## Proposed Changes

### [app]

#### [MODIFY] [InternalCameraHelper.java](file:///C:/Users/Emil/Documents/Coding/ViewAndStream/UVCAndroid/app/src/main/java/com/herohan/uvcapp/InternalCameraHelper.java)

-   **Update `startPreviewSession()`**:
    -   Verify if `mPreviewSurface` is valid before adding it to the session.
    -   If `mPreviewSurface` is null or invalid, attempt to recreate it from `mPreviewSurfaceTexture`.
    -   Verify all other surfaces (`mEncoderSurface`, `ImageReader` surfaces) are valid before adding them to the `surfaces` list.
    -   Abort session creation if `mPreviewSurface` remains invalid.
    -   Wrap `createCaptureSession` in a `try-catch` block that specifically catches `RuntimeException` (like `IllegalArgumentException`) to handle edge cases where a surface becomes invalid during the call.

-   **Update `tryUpdatePreviewRequest()`**:
    -   Check `isValid()` for each surface before calling `builder.addTarget()`.
    -   Ensure only valid targets are added to the capture request.

-   **Update `triggerTapToFocus()`**:
    -   Fix the memory leak/bug where `new Surface(mPreviewSurfaceTexture)` was called every time. Use the existing `mPreviewSurface` instead.
    -   Add `isValid()` check for the surface before adding it as a target.

## Verification Plan

### Automated Tests
-   I will perform a build to ensure no syntax errors were introduced.
    -   `./gradlew :app:assembleDebug`

### Manual Verification
-   Stress test switching between cameras.
-   Quickly exit the camera activity to trigger potential race conditions where the UI surface is destroyed while the background thread is still configuring the camera.
-   Verify that the app no longer crashes with `Surface was abandoned`.
-   Verify that tap-to-focus still works correctly.
