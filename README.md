# GTA VI Spoiler Firewall

An Android app that keeps **YouTube** and **Instagram** covered and only uncovers a post, Short, Reel or story **after checking that it isn't about Grand Theft Auto**. It was built to get through GTA VI launch week without spoilers.

Everything runs **on the phone**. No screenshots, text or usage data leave the device.

**Download:** [GTA6SpoilerFirewall-1.0.apk](https://github.com/PixxySynchronous/GTA6SpoilerFirewall/releases/latest), on the Releases page (Android 11+, OCR on Android 14+).

## What it does

- **Blocked until cleared.** When YouTube or Instagram opens, the app window is covered. Each item (feed card, Short, Reel, story, search result, button) gets its own hole in the cover only once it passes the check.
- **Two checks per item:**
  1. **Text:** titles, captions, channel names and descriptions, read through Android's accessibility API.
  2. **Picture text (Android 14+):** a screenshot of *only* the app's window, so the cover itself is never captured, run through on-device OCR (Google ML Kit). This catches spoilers written on thumbnails and inside videos.
- **What counts as blocked:** anything Grand Theft Auto. That means GTA in any form (GTA 5/V, GTA VI, GTA Online, GTA RP, #gta6…), San Andreas, Vice City, Liberty City, Los Santos, Leonida and Rockstar Games. Character names (Lucia, Jason, Trevor, Franklin…) are blocked when they appear next to words like *ending*, *mission* or *leak*.
- **Unknown stays hidden.** If something has no readable text or hasn't been checked yet, it stays covered ("Checking…"). Matches show "Spoiler blocked".
- **Heavy blur, not a blackout.** Covered areas show an extremely blurred copy of what's underneath: the window screenshot shrunk to ~24 px wide, smoothed, and stretched back. You see colours and layout but can't read text or recognise images. Before the first screenshot (and on Android below 14) the cover is plain dark.
- **Scrolling.** Any scroll immediately re-covers the area that moved. Holes reopen once the list settles and the new items pass. Items it has already checked are remembered by their text and a small pixel fingerprint, so scrolling back up doesn't re-run OCR.
- **Video.** While a full-screen Short, Reel or video is still covered, the app takes audio focus so the video pauses. A playing video is re-scanned every 2.5 s for on-screen text.
- **Usable phone.** The cover is touch-through, so you can still scroll and tap. The nav bar, keyboard and notification shade stay visible.
- **On/off pop-up.**
  - Opening YouTube or Instagram shows a notification: *"Spoiler Firewall is ON"* with **Turn off** and **Pause 15 min**.
  - If protection is off, or the accessibility service has been disabled, opening either app shows *"Spoiler Firewall is OFF"* with **Turn on**.

## Limits

- **First-frame flash.** Android has no way for one app to inspect another app's content before it's drawn. A few frames can show at the very start of a swipe before the cover catches up.
- **Pictures with no text.** A video or image that shows GTA footage but has a clean caption and no visible text is let through. Catching that would need an image classifier.
- **No audio check.**
- **Strict on purpose.** Anything mentioning GTA is hidden, including unrelated things that happen to be called *San Andreas*.
- **Prototype.** Tested on a Samsung Galaxy S25 Ultra (Android 16, One UI). YouTube and Instagram change their layouts often, so behaviour on other versions may differ.

## Build and install

Requirements: JDK 17, Android SDK (platform 35), an Android 11+ phone with USB debugging enabled. OCR needs Android 14+.

```bash
echo "sdk.dir=/path/to/android/sdk" > local.properties
JAVA_HOME=/path/to/jdk17 ./gradlew testDebugUnitTest installDebug
```

Then, on the phone:
1. Open **GTA VI Spoiler Firewall** and allow notifications.
2. **Settings → Accessibility → Installed apps → GTA VI Spoiler Firewall → On.**
3. Tap **Allow usage access** in the app. This is only needed for the "firewall is off" pop-up.

Optional: let the **Turn on** button switch the accessibility service back on by itself, without opening Settings:

```bash
adb shell pm grant com.piyush.spoilerfirewall android.permission.WRITE_SECURE_SETTINGS
```

To stop it, use **Turn off** in the pop-up or the switch in Accessibility settings. To remove it completely, uninstall the app.

## How it's put together

| File | Role |
|---|---|
| `SpoilerAccessibilityService.kt` | Watches YouTube/Instagram, decides what to cover or uncover, runs OCR, holds audio focus |
| `FeedScanner.kt` | Splits the app's accessibility tree into checkable items (list children, labelled and unlabelled leaves) |
| `SpoilerRules.kt` | Offline word rules (unit-tested in `app/src/test`) |
| `OcrInspector.kt` | Window-only screenshot plus ML Kit text recognition |
| `PixelHash.kt` | 256-bit difference hash used to recognise already-checked items |
| `HeavyBlur.kt` | Makes the extremely blurred backdrop for covered areas |
| `CoverView.kt` | Touch-through overlay that paints everything except cleared items |
| `Notifier.kt`, `ActionReceiver.kt` | The on/off pop-up and its buttons |
| `WatcherService.kt`, `BootReceiver.kt` | Shows the "OFF" pop-up when the accessibility service is disabled |
| `AudioGuard.kt`, `Prefs.kt`, `A11y.kt`, `MainActivity.kt` | Audio pause, settings, service switching, main screen |

## Privacy

The app uses Android's accessibility service and window screenshots, which are powerful permissions. In this app:
- they're used only while YouTube or Instagram is on screen;
- text and screenshots are processed in memory on the device and never saved or sent anywhere;
- the only network access is Gradle fetching the ML Kit library at build time.
