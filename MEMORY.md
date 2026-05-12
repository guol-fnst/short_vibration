# Conversation Memory

## Project

- Path: `/root/short_vibration`
- App: Virb Lite, an Android notification vibration helper.
- Target scenario discussed: Xiaomi 17 Pro / HyperOS, long idle screen-off periods where WeChat notifications may arrive but custom vibration can be missed.

## Investigation Summary

- The app listens through `NotificationListenerService` and vibrates from `onNotificationPosted()`.
- Long-idle missed vibration is most likely caused by HyperOS disconnecting or freezing the notification listener binding while the phone is locked.
- The existing wakelock only runs after `onNotificationPosted()` is reached, so it cannot help if the listener callback never arrives.
- `globalGapMs` is a normal anti-spam interval and is not the likely cause for missed vibrations at roughly 10-second notification intervals.
- A previous issue was found where `lastVibrationAtMs` was loaded from preferences but not persisted after a successful vibration. That could cause extra vibration after service restart, not missed vibration.

## Implemented Changes

- File: `app/src/main/java/com/virb/lite/listener/VibratingNotificationListenerService.kt`
- On `onListenerDisconnected()`, the app now requests notification listener rebind.
- Rebind requests use exponential backoff:
  - Initial interval: `15_000 ms`
  - Doubles after each rebind request
  - Maximum interval: `5 min`
  - Resets to `15_000 ms` on `onListenerConnected()`
- Successful vibration now persists `lastVibrationAtMs` through `prefs.markVibrationNow(now)`.
- File: `.gitignore`
- Added `.gradle-user/` because Gradle was run with a project-local `GRADLE_USER_HOME`.

## Build Notes

- There is no Gradle wrapper in the repo.
- System Gradle used: `/opt/gradle/gradle-8.7/bin/gradle`
- Android SDK path is configured in `local.properties` as `/opt/android-sdk`.
- Sandbox Gradle build initially failed because `/root/.gradle` was read-only and Gradle daemon needed local socket access.
- Working build command:

```bash
env GRADLE_USER_HOME=/root/short_vibration/.gradle-user /opt/gradle/gradle-8.7/bin/gradle -p /root/short_vibration assembleDebug --console=plain --no-daemon
```

- Building required escalated execution because the sandbox blocked Gradle daemon socket creation.
- Build warning observed: Android Gradle Plugin 8.5.2 was tested up to `compileSdk = 34`, while project uses `compileSdk = 35`. APK still builds successfully.

## Current APK

- APK path: `/root/short_vibration/app/build/outputs/apk/debug/short_vibration-debug.apk`
- Last confirmed size: about `9.6M`
- Last build result after exponential backoff change: `BUILD SUCCESSFUL in 17s`

## User Preferences / Concerns

- User is concerned about missed vibrations after long phone idle periods.
- User is cautious about battery impact and whether frequent service rebind requests could stress the system.
- Current implementation chooses a conservative exponential backoff rather than fixed frequent rebind attempts.
