<p align="center">
  <img src="Thumb.png" alt="SILO360" />
</p>

# SILO360 - Multicast UDP 360 Streamer

An Android app that receives a live UDP video stream and displays it as an interactive 360° view — swipe the screen to look around the scene.

---

## What It Does

- Connects to a live UDP stream on port **1234**
- Displays the video mapped onto a **360° sphere**
- **Swipe** left/right/up/down to pan and tilt the camera
- Shows a "Connecting to stream…" indicator when no feed is detected
- Automatically reconnects if the stream drops

---

## Installing the APK (Sideloading)

### Step 1 — Allow Unknown Sources

1. On your Android device go to **Settings**
2. Search for **"Install unknown apps"** (or *Special app access*)
3. Select the app you will use to open the APK (e.g. **Files** or **Chrome**)
4. Toggle **"Allow from this source"** on

> ⚠️ You can turn this back off after installing.

### Step 2 — Transfer the APK

Copy the `.apk` file to your device via USB, Google Drive, email, etc.

### Step 3 — Install

1. Open the APK file on your device using your file manager
2. Tap **Install**
3. Tap **Open** when complete

---

## Using the App

1. Connect your Android device to the **same Wi-Fi network** as the stream source
2. Open **UDP Streamer 360°**
3. The app will automatically start listening on `udp://@:1234`
4. Once a stream is detected, video will appear on the sphere
5. **Swipe** to look around in 360°

### If No Video Appears

- Check your device and stream source are on the same network
- Ensure the stream is being sent to port **1234**
- Check your router allows UDP multicast/broadcast between devices

---

## Sending a Stream (Quick Reference)

### FFmpeg

```bash
ffmpeg -re -i input.mp4 \
  -vcodec libx264 -preset ultrafast -tune zerolatency \
  -f mpegts udp://<device-ip>:1234