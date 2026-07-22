# Walkthrough - Context-Aware Stream Settings & Connection Guide

I have refined the stream settings dialog to be context-aware and added a connection guide to help you set up your stream more easily.

## Changes Made

### User Interface & Experience

#### [strings.xml](file:///C:/Users/Emil/Documents/Coding/ViewAndStream/UVCAndroid/app/src/main/res/values/strings.xml)
- Added new strings for protocol-specific guides.
- Included detailed instructions for connecting from OBS via SRT.

#### [MainActivity.java](file:///C:/Users/Emil/Documents/Coding/ViewAndStream/UVCAndroid/app/src/main/java/com/herohan/uvcapp/activity/MainActivity.java)
- **Context-Aware Dialog**: The "Set Stream Destination" dialog now dynamically changes based on the selected protocol:
    - **RTMP Mode**: Only shows the RTMP URL, FPS, and Quality fields. Displays a guide for streaming to platforms like YouTube or Twitch.
    - **SRT/TCP Mode**: Shows the phone's IP address, Port, FPS, and Quality. Displays a detailed guide on how to configure OBS as a client (using the DroidCam OBS plugin or a Media Source).
- **Interactive IP Display**: The dialog prominently shows your phone's current IP address, so you don't have to look it up elsewhere.

## Connection Guide

> [!TIP]
> **For OBS (SRT Mode):**
> 1. Set the protocol to **SRT** in the app.
> 2. Open the "Set Stream Destination" dialog to see your phone's IP and port.
> 3. In OBS, add a **Media Source**.
> 4. Uncheck "Local File".
> 5. In **Input**, enter: `srt://[YOUR_PHONE_IP]:[PORT]`
> 6. Set **Input Format** to `mpegts`.

## Verification Results

### Automated Tests
- Build successful: `:app:assembleDebug`.

### Manual Verification Required
- Switch to **RTMP** and verify the settings dialog only asks for a URL.
- Switch to **SRT** or **TCP** and verify the settings dialog shows the phone IP and OBS instructions.
