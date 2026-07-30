# NotifyPulse

NotifyPulse is a low-power Android notification vibration assistant. It listens
for notifications from user-selected apps and adds an immediate vibration,
lock-screen unread reminders, and quiet-hour controls.

The package ID remains `com.virb.lite` so existing installations can upgrade
without losing settings or notification-access authorization.

## Features

- Notification whitelist with app-name/package search
- App discovery from launcher apps and apps observed by the notification listener
- Per-app vibration profiles: default, short, double, and long
- Configurable default vibration duration and amplitude
- Global duplicate-notification gap with a bounded trailing vibration
- Optional vibration only while the device is locked
- Lock-screen unread reminders with configurable interval and maximum count
- Multiple quiet periods with weekday selection and overnight support
- Quiet-period editing, per-period enable/disable, overlap validation, and live status
- Foreground listener runtime for reliability on MIUI/HyperOS
- On-device diagnostic log viewer

## Power Model

NotifyPulse does not poll. Immediate vibrations run from
`NotificationListenerService` callbacks. Unread reminders use one
`AlarmManager.setAndAllowWhileIdle` alarm at a time and compensate when a normal
device wake-up occurs after the reminder deadline. Per-app vibration profiles
only change the dispatched `VibrationEffect`; they do not add wake-ups.

## App Visibility And Store Compliance

The app does not request `QUERY_ALL_PACKAGES`. The whitelist picker combines:

- applications with a launcher activity;
- packages observed through notification-listener callbacks;
- packages already saved in the whitelist.

The app also avoids directly requesting a battery-optimization exemption. The
reliability warning opens the system battery settings, where the user can make
the choice manually.

The foreground service uses the Android `specialUse` type because deferred
notification-listener callbacks would miss time-sensitive vibration behavior.
This use must be declared in Play Console when publishing.

## Logging

File logging is enabled by default and stored in the app's internal files
directory. Logs are capped and trimmed automatically. They can include package,
channel, notification title, decision reason, vibration profile, and reminder
scheduling details. Users can disable file logging from the main screen.

## Requirements

- Android 8.0 or later (`minSdk 26`)
- Android SDK 36
- JDK 17 or later
- Gradle 8.11.1

## Build And Test

```bash
gradle --no-daemon testDebugUnitTest testReleaseUnitTest lintDebug assembleDebug
```

Debug APK:

```text
app/build/outputs/apk/debug/NotifyPulse-debug.apk
```

Install over an existing build:

```bash
adb install -r app/build/outputs/apk/debug/NotifyPulse-debug.apk
```

## MIUI / HyperOS

- Enable notification access for NotifyPulse.
- Enable auto-start if the listener is repeatedly killed.
- If needed, open the system battery settings and manually select an unrestricted
  policy for NotifyPulse.
- Keep the persistent runtime notification available; it indicates that the
  listener reliability service is active.
