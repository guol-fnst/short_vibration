# short_vibration

Virb Lite is a lightweight Android notification vibration app focused on low power usage.

## What It Does

- Listens to posted notifications via `NotificationListenerService`
- Triggers short custom vibration when notifications arrive
- Provides settings UI for:
  - enable/disable switch
  - vibration duration (`1-1000 ms`)
  - notification access shortcut
  - test vibration button
- Uses a foreground notification-listener runtime mode to reduce MIUI/HyperOS listener drop issues
- Ignores system-app notifications to avoid false vibrations during system cleanup actions

## Current Behavior

- Repeated notifications are **not filtered** (kept intentionally)
- Minimum vibration duration is **1 ms**
- Notification vibration path uses `USAGE_ALARM`

## Tech Stack

- Kotlin
- Android SDK 35
- Min SDK 26
- Gradle (AGP 8.5.2)

## Build

On this workspace, use JDK 17:

```bat
cmd /c "set JAVA_HOME=C:\Program Files\Java\jdk-17&& set PATH=%JAVA_HOME%\bin;%PATH%&& d:\virb\gradle-8.7\bin\gradle.bat -p d:\virb assembleDebug --console=plain"
```

APK output:

- `app/build/outputs/apk/debug/short_vibration-debug.apk`

## Install

```bat
cmd /c "C:\Users\leile\AppData\Local\Android\Sdk\platform-tools\adb.exe install -r d:\virb\app\build\outputs\apk\debug\short_vibration-debug.apk"
```

## Notes for MIUI/HyperOS

- Keep notification access enabled for the app
- Foreground listener mode is used to improve reliability when background process freezing is aggressive
