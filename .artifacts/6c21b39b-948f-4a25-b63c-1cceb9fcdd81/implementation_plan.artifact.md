# Implementation Plan - Fix RTMP Video Flow

The RTMP connection is successful (`RTMP Connected`), but no video is visible in the browser. This confirms that the network handshake is working, but media data is either not being sent or is being sent incorrectly.

## Problem Analysis

In the current implementation, the RTMP surface is attached to the camera *before* `startStream()`. For the `RootEncoder` library (v2.7.5) using `NoVideoSource`, the internal OpenGL context might not be fully active or ready to consume frames until the stream (or preview) is started.

Additionally, we need to ensure that `setForceRender` is active to maintain a steady flow of frames, especially when bridging an external camera surface to the encoder.

## Proposed Changes

### [app]

#### [MODIFY] [MainActivity.java](file:///C:/Users/Emil/Documents/Coding/ViewAndStream/UVCAndroid/app/src/main/java/com/herohan/uvcapp/activity/MainActivity.java)

- **Adjust RTMP Start Sequence**:
    1.  Call `prepareVideo` and `prepareAudio`.
    2.  Call `startStream(fullRtmpUrl)`.
    3.  **Immediately after**, call `setForceRender(true, fps)`.
    4.  **Then** obtain the `rtmpSurface` and attach it to the camera helper.
- **Enhanced Logging**:
    - Log the composed `fullRtmpUrl` (redacting the stream key for privacy).
    - Add a periodic log within the worker loop to report the current count of frames encoded and packets sent. This will help us see if the encoder is actually producing data.
- **Bitrate Adjustment**: Ensure the bitrate is not too low for the selected resolution.

## Verification Plan

### Manual Verification
1.  Monitor Logcat for the updated telemetry logs.
2.  Verify that `mRtmpFramesEncoded` and `mRtmpPacketsSent` are incrementing rapidly (approx. 24-30 per second).
3.  Check the streaming service (YouTube/Restreamer) to see if video appears.
4.  Verify that the bitrate stabilizes at a value much higher than 20kbps.
