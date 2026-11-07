# AirFree

A free, open-source, AirDroid-style app. Your computer's browser connects straight to your phone over Wi-Fi.
No account, no cloud, no subscription, no ads.

## What it does

| Feature | How |
|---|---|
| Browse, download, upload and delete phone files | Files tab |
| Mirror phone notifications to the computer | Notifications tab |
| Read and send SMS from the computer | Messages tab |
| See the phone screen live (about 5 frames per second) | Screen tab |
| Tap, swipe, Back, Home and Recents from the computer | Screen tab (needs "remote control" enabled) |
| Send text from computer to phone clipboard, and back | Clipboard tab |
| Battery, storage and model info | Device tab |

Everything is protected by a 6-digit PIN shown in the app.

## Build it (free)

1. Install **Android Studio** (free): https://developer.android.com/studio
2. Open the `airfree` folder (File > Open). Let Gradle sync. It needs internet once to download dependencies.
3. Plug in your phone with USB debugging on (or use an emulator), then press Run.
   To get an installable file instead: Build > Build APK(s), and copy `app-debug.apk` to your phone.

## Use it

1. Open AirFree on the phone and tap the numbered buttons 1 to 4 to grant permissions.
2. Tap **Start server**. The app shows an address such as `http://192.168.1.20:8080` and a PIN.
3. On your computer (same Wi-Fi), open that address in a browser and enter the PIN.
4. For screen mirroring, tap **Start screen sharing** in the app.
   For remote control, enable AirFree in Accessibility settings (button 4).

## Good to know

- Traffic is plain HTTP on your local network, so use it on Wi-Fi you trust. It does not work over the internet.
- Because it asks for SMS and all-files access, Google Play would not accept it. Install the APK yourself.
- Screen mirroring does not rotate with the phone yet. Re-start sharing after rotating.
- After 5 wrong PINs, that device must wait 30 seconds.
- Android may stop background apps on some brands (Xiaomi, Huawei, Samsung). If the server stops, set AirFree to "No restrictions" under battery settings.
