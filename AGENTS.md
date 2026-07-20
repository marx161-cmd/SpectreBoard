# SpectreBoard — Agent Workflow

## Build & Deploy

```bash
cd /home/comrade/builds/android/Termux_addons/SpectreBoard/source/SpectreBoard
./gradlew assembleRelease
blazer-sysapp-update install com.termux.spectreboard app/build/outputs/apk/release/SpectreBoard_4.0-alpha2-release.apk
```

## Rollback

```bash
blazer-sysapp-update rollback com.termux.spectreboard
```

## Device

Pixel 10 Pro "blazer" — `100.69.13.12:5555` (ADB), `100.69.13.12:8022` (SSH/u0_a464).
Script: `blazer-sysapp-update` (in `~/bin`) handles signing checks and ledger tracking.

## ROM Build (OTA only — don't touch during adb test loop)

Prebuilt lives at `~/homelab/crDroid/device/google/blazer-secur/prebuilts-apk/com.termux.spectreboard.apk`.
Manifest: `~/homelab/crDroid/device/google/blazer-secur/APPS_MANIFEST.md`.
Workflow doc: `~/homelab/crDroid/device/google/blazer-secur/SYSTEM_APP_UPDATE_WORKFLOW.md`.

## Signing

Termux platform key via `~/.gradle/gradle.properties`:
`TERMUX_KEYSTORE`, `TERMUX_STORE_PASSWORD`, `TERMUX_KEY_ALIAS`, `TERMUX_KEY_PASSWORD`.
Never print or log password values.

## Key Files

- Suggest pipeline: `latin/Suggest.kt`
- Settings: `latin/settings/Settings.java`, `latin/settings/Defaults.kt`
- Gesture enabler gate: `keyboard/internal/GestureEnabler.java`, `latin/settings/SettingsValues.java:257`
- Touch handler: `keyboard/PointerTracker.java`
- Neural engine: `spectre/neural/` (new, isolated)
- BUILD_STATUS.md, README.md — current state

## System App Context

SpectreBoard is a priv-app on crDroid (`android:sharedUserId="com.termux"`).
APK must be signed with the Termux platform key. `adb install -r` creates an
overlay in `/data/app/` — privileges are retained. Full ROM promotion requires
repacking (uncompressed dex/JNI) and OTA rebuild.
