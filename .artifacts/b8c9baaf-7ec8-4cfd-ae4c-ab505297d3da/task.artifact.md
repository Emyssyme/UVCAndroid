# Task: Fix Camera Monitor Contention during RTMP Start

- [x] Offload camera session management in `InternalCameraHelper.java` to background thread
    - [x] `setEncoderSurface`
    - [x] `setFrameListener`
    - [x] `startPreview`
    - [x] `stopPreview`
    - [x] Refactor session restart logic
- [x] Optimize RTMP startup sequence in `MainActivity.java`
- [x] Verify changes
