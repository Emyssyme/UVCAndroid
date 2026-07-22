# Implementation Plan - Add RTMP Streaming Support

The goal is to add support for RTMP streaming to the UVCAndroid app, allowing users to switch between TCP/UDP, SRT, and RTMP protocols. Users should also be able to save their RTMP configurations.

## User Review Required

> [!IMPORTANT]
> - I will be adding the `RootEncoder` library (formerly `rtmp-rtsp-stream-client-java`) to handle the RTMP protocol.
> - The RTMP streaming will use the same NV12 frames currently used for SRT and TCP/UDP.
> - A new configuration field for RTMP URL will be added to the stream destination dialog.

## Proposed Changes

### Build Configuration

#### [MODIFY] [app/build.gradle](file:///C:/Users/Emil/Documents/Coding/ViewAndStream/UVCAndroid/app/build.gradle)
- Add `com.github.pedroSG94.RootEncoder:library:2.8.0` dependency.

#### [MODIFY] [AndroidManifest.xml](file:///C:/Users/Emil/Documents/Coding/ViewAndStream/UVCAndroid/app/src/main/AndroidManifest.xml)
- Add `camera` and `connectedDevice` to `foregroundServiceType` for `CameraKeepAliveService` to comply with Android 14+ requirements during streaming.

### Resources

#### [MODIFY] [strings.xml](file:///C:/Users/Emil/Documents/Coding/ViewAndStream/UVCAndroid/app/src/main/res/values/strings.xml)
- Add strings for RTMP protocol and its configuration hints.

### Core Logic

#### [MODIFY] [MainActivity.java](file:///C:/Users/Emil/Documents/Coding/ViewAndStream/UVCAndroid/app/src/main/java/com/herohan/uvcapp/activity/MainActivity.java)
- **Enum Update**: Add `RTMP` to `StreamProtocol`.
- **Preference Keys**: Add `PREF_RTMP_URL`.
- **RTMP Implementation**:
    - Initialize `RtmpStream` from `RootEncoder`.
    - Implement `startRtmpStreaming()`, `stopRtmpStreaming()`, and `enqueueRtmpFrame()`.
    - Update `MultiFrameCallback` to dispatch frames to RTMP when selected.
    - Update `cleanupStreaming()` to stop RTMP.
- **UI Updates**:
    - Update `onOptionsItemSelected` to include `RTMP` in the protocol cycle.
    - Update `showSetStreamDestinationDialog()` to show and save RTMP URL.
    - Update `updateStreamStatus()` to display RTMP status and telemetry.

## Verification Plan

### Automated Tests
- Build the project to ensure no dependency conflicts.
- Check if the app runs on the device.

### Manual Verification
- **Protocol Switching**: Verify that tapping the stream protocol menu item cycles through TCP/UDP, SRT, and RTMP.
- **Configuration Saving**:
    - Select RTMP protocol.
    - Open "Set Stream Destination" dialog.
    - Enter an RTMP URL.
    - Save and restart the app to ensure the URL is persisted.
- **Streaming**:
    - Start RTMP streaming to a test server (e.g., local RTMP server or service like Twitch/YouTube).
    - Verify that frames are being sent and received correctly.
