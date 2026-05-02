# AGENTS.md

## What this repo is

UHF RFID reader SDK documentation and an Android demo app (`com.example.uhf`) for communicating with RSCJA-compatible UHF hardware via UART/serial. Not a library you build — the SDK is delivered as a prebuilt AAR/JAR.

## Layout

- `docs_sdk/` — SDK docs (JavaDoc HTML in RAR archives), Device API AAR, and the Android projects
- `docs_sdk/Demo-uhf_as/uhf-uart-demo/` — General-purpose UHF debug app (14+ tabs, AGP 8.2.2)
- `docs_sdk/RaceTimingOS/` — Race timing app (2-screen: live reads dashboard + settings)
- `.github/java-upgrade/` — tool-use-recording hooks for a Java upgrade extension; not CI

## Building

All Gradle commands run from each project's own directory.

```powershell
# Demo app (output: UHF-serial_v1.4.6.apk)
cd docs_sdk/Demo-uhf_as/uhf-uart-demo
.\gradlew assembleDebug

# RaceTimingOS (2-screen race timing app)
cd docs_sdk/RaceTimingOS
.\gradlew assembleDebug

# Install on connected C72
adb install -r app\build\outputs\apk\debug\app-debug.apk
```

## Key gotchas

- **SDK is prebuilt**: `app/libs/DeviceAPI_ver20251103_release.aar` and other jars (`jxl.jar`, `poi-*.jar`, `xUtils-2.5.5.jar`) are committed. Do not modify them.
- **SDK API class**: `com.rscja.deviceapi.RFIDWithUHFUART` — init takes Activity context; inventory uses `startInventoryTag(InventoryParameter)` with `setInventoryCallback(IUHFInventoryCallback)`.
- **Wiclax protocol**: RaceTimingOS runs a TCP server (`WiclaxClient`) that Wiclax software connects to. Default port 9854. Passing format: `EPC;dd-MM-yyyy HH:mm:ss.ccc;RSSI;;;0[CR][LF]`. Handles STARTREAD/STOPREAD/CLOCK commands. Heartbeat every 30s.
- **Rufus API**: Placeholder in `network/RufusClient.java` — HTTP POST to `api.runonrufus.com/v0/passings` with `api_key` header.
- **Signing**: Demo app uses `app/uhf-serial_release.jks` (pw `123456`, alias `uhf-serial-key`). RaceTimingOS has no custom signing config (debug keystore only).
- **Java target**: 1.8 (`sourceCompatibility`/`targetCompatibility`). Do not upgrade without testing all hardware API calls.
- **Crash logs**: Written to `/sdcard/uhf/` on device (demo app).
- **Android permissions**: Both apps require `MANAGE_EXTERNAL_STORAGE` (Android 11+) and read/write external storage.
- **No root build/lint/test commands**: No CI, no lint, no typecheck at repo root. Validating requires a full Android build.
- **Unit tests**: Skeleton only — no meaningful test coverage.
