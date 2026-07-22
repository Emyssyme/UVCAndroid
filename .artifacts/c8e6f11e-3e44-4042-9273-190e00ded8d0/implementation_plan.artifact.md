# Implementation Plan - Context-Aware Stream Settings & Connection Guide

The current "Set Stream Destination" dialog shows all parameters regardless of the active protocol, which is confusing. This plan refines the settings UI to show only relevant fields and includes an interactive guide to help users connect.

## User Review Required

> [!IMPORTANT]
> - The dialog will now look different depending on whether **RTMP** or **TCP/SRT** is selected.
> - For **TCP/SRT**, it will prominently display the phone's IP address and instructions for OBS.

## Proposed Changes

### MainActivity Implementation

#### [MODIFY] [MainActivity.java](file:///C:/Users/Emil/Documents/Coding/ViewAndStream/UVCAndroid/app/src/main/java/com/herohan/uvcapp/activity/MainActivity.java)
- **Refactor `showSetStreamDestinationDialog()`**:
    - Check `mStreamProtocol` at the start of the method.
    - If **RTMP**:
        - Hide Host/Port fields.
        - Add a guide text: "Enter your RTMP server URL (e.g., from YouTube or Twitch)."
    - If **TCP/UDP** or **SRT**:
        - Hide RTMP URL field.
        - Display the phone's IP address as a non-editable info field.
        - Add a guide text: "This phone acts as a server. In OBS, use: `srt://[IP]:[PORT]` or the DroidCam OBS plugin."
    - Common Fields: Keep FPS and Quality for all protocols.
- **Update Dialog Validation**: Ensure only relevant fields are validated based on the protocol.

### Resources

#### [MODIFY] [strings.xml](file:///C:/Users/Emil/Documents/Coding/ViewAndStream/UVCAndroid/app/src/main/res/values/strings.xml)
- Add guide strings:
    - `stream_guide_rtmp`: "Stream to YouTube, Twitch, etc."
    - `stream_guide_network`: "Local network stream for OBS."
    - `stream_guide_network_obs_info`: "Connect from OBS using:\n%1$s:%2$d"

## Verification Plan

### Automated Tests
- Build check: `./gradlew :app:assembleDebug`.

### Manual Verification
1.  **RTMP Mode**:
    - Switch to RTMP.
    - Open "Set Stream Destination".
    - Verify only URL, FPS, and Quality are shown with the RTMP guide.
2.  **SRT/TCP Mode**:
    - Switch to SRT or TCP.
    - Open "Set Stream Destination".
    - Verify Port, FPS, and Quality are shown.
    - Verify the "Phone IP" and connection guide are displayed correctly.
