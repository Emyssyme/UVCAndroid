# OBS Custom Network Plugin

This folder contains an OBS Studio plugin that receives H.265 video streams from the UVCAndroid app
over **TCP** or **SRT (UDP)**. Tally indicators and camera control always remain on a separate TCP
back-channel (UDP port 8867), so they work regardless of the video transport.

## Features

- **H.265 over TCP** — low-latency hardware-accelerated decode with automatic reconnect
- **SRT (UDP)** — video over UDP (caller-mode), while tally & control stay on TCP
- **Network Discovery** — auto-discovers Android devices via UDP broadcast on port 8866
- **Seamless source switching** — switching between discovered devices preserves the decoder,
  avoiding unnecessary reconnects when the same device is re-selected
- **Latency display** — shows real-time end-to-end delay (ms) in the status bar
- **Dynamic source naming** — each source instance renames itself in the OBS sources list
  to show `📱 192.168.1.5:5600  1920x1080  45ms` so you instantly know which phone is which
- **Per-source device binding** — the "Discovered Devices" dropdown marks which device
  is currently active with `← THIS SOURCE` so you never accidentally connect to the wrong phone
- **Camera control** — exposure lock, focus lock, AF mode, flash, white balance, resolution,
  FPS, and quality can be controlled from OBS and synced bidirectionally with the phone
- **Tally** — program/preview tally sent to the phone via UDP back-channel

## Multiple Sources — How to avoid confusion

When you add multiple instances of "UVC Custom Network" in OBS:
1. Each source **auto-renames** in the sources list to show the connected phone IP and resolution
2. The **📋 Discovered Devices** dropdown marks the active device with `← THIS SOURCE`
3. Each phone should use a **different TCP/SRT port** on Android (tap the stream protocol
   button to change ports, or set them in the Android app's destination dialog)
4. Discovery fills each source with a unique device — sources won't "steal" each other's connection

## Build and install on Windows

1. Install Visual Studio 2022/2019 with C++ desktop development.
2. Install CMake and Git.
3. Clone the OBS Studio source tree or use a developer build install that includes `include/` and `lib/` directories.
4. Place this folder under `obs-studio-master/plugins/uvc-custom-network` or add via `add_subdirectory()`.
5. Run from this folder:

```powershell
mkdir build
cd build
cmake -G "Visual Studio 17 2022" -A x64 -DOBS_DIR="C:/Program Files/obs-studio" ..
cmake --build . --config Release
```

6. Copy the generated plugin DLL into OBS Studio plugin folder:
   `C:/Program Files/obs-studio/plugins/uvc-custom-network/bin/64bit/`

## How it works

- **TCP mode**: OBS connects to the Android TCP server (default port 5600) and receives H.265 frames.
- **SRT mode**: Android sends H.265 over UDP to OBS (default port 5601). OBS binds a UDP socket and decodes incoming packets.
- **Tally/Control**: Always on UDP port 8867. OBS sends `TALLY;program=X;preview=Y` every second.
- **Discovery**: Android broadcasts `UVCAPP;port=XXXX` on UDP port 8866. OBS auto-detects available devices.
- **Latency**: Calculated as `OBS receive time - Android capture PTS`, displayed in the status field.

## Properties

- `📡 Status` — shows connection state, resolution, and delay at a glance
- `📋 Discovered Devices` — dropdown list with `← THIS SOURCE` marker on the active device
- `Phone IP` — manual IP override
- `Stream Port (TCP)` — TCP port for video stream (default: 5600)
- `Use SRT (UDP)` — switch to SRT transport (tally/control stay on TCP)
- `SRT Port` — UDP port for SRT video stream (default: 5601)
- `Discovery Status` — shows connection state, resolution, and delay
- `Activate` / `Refresh Discovery` — control buttons
- `Resolution` — target resolution for camera output
- `FPS` — target frame rate (15-60)
- `Quality` — encoder quality hint (1-100)
- `Exposure Lock`, `Focus Lock`, `AF Lock` — camera control toggles
- `Exposure Compensation` — EV adjustment (-10 to +10)
- `Autofocus Mode` — Off/Auto/Continuous/Tap/Infinity/Macro
- `Flash Mode` — Auto/On/Off/Torch
- `White Balance` — Auto/Incandescent/Fluorescent/Daylight/Cloudy/Shade/Kelvin
- `Enable Network Discovery` — toggle auto-discovery
- `net_discovery.h` — discovery helper API
