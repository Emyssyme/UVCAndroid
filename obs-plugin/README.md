# OBS Custom Network Plugin

This folder contains a Windows OBS Studio plugin that receives custom UDP video frames from the UVCAndroid app.
It also listens for Android discovery beacons on UDP port `8866` to auto-detect the stream port.

## Build and install on Windows

1. Install Visual Studio 2022/2019 with C++ desktop development.
2. Install CMake and Git.
3. Clone the OBS Studio source tree or use a developer build install that includes `include/` and `lib/` directories. The runtime-only OBS installer does not contain the headers and import libraries needed to build plugins.
4. This plugin can also be built as part of the OBS source tree. Place this folder under `obs-studio-master/plugins/uvc-custom-network` and add the line `add_subdirectory(uvc-custom-network)` to `obs-studio-master/plugins/CMakeLists.txt`.
5. If you are using an OBS source checkout outside the OBS tree, point `OBS_DIR` at the source root or the source root containing a built `build/` or `output/` tree.
6. Run from this folder:

```powershell
mkdir build
cd build
cmake -G "Visual Studio 17 2022" -A x64 -DOBS_DIR="C:/Program Files/obs-studio" ..
cmake --build . --config Release
```

5. Copy the generated plugin DLL into OBS Studio plugin folder:

- `C:/Program Files/obs-studio/plugins/uvc-custom-network/bin/64bit/`
- or install into your OBS development install.

## How it works

- OBS source listens on the configured UDP port for Android stream packets.
- The Android app uses a custom UDP packet format with a 16-byte header.
- If discovery is enabled, the plugin also listens for `UVCAPP` beacons on port `8866`.
- When a beacon arrives and the source host/port are empty or the discovered stream port changes, the plugin auto-fills the UDP receive port and restarts the receiver.
- The source properties include a read-only "Discovery Status" field that shows the latest discovered host/port state.
- The plugin currently supports NV12 input and converts it to RGBA for rendering in OBS.

## Properties

- `Host` — optional device host string saved with the source
- `Port` — UDP receive port for the stream
- `Resolution` — preferred output resolution when no frame has arrived yet
- `FPS` — display-only setting for expected capture rate
- `Quality` — quality hint saved to the source
- `Enable Network Discovery` — listen for Android discovery beacons

## Files

- `CMakeLists.txt` — build script for OBS plugin
- `uvc_custom_network.cpp` — plugin source implementation and UDP receiver
- `uvc_custom_network.h` — OBS source state definition
- `net_discovery.cpp` — discovery listener implementation
- `net_discovery.h` — discovery helper API
