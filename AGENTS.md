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
- **SDK API class**: `com.rscja.deviceapi.RFIDWithUHFUART` — `init(Context)` (RaceTimingOS passes the application context, since scanning outlives the activity); inventory uses `startInventoryTag(InventoryParameter)` with `setInventoryCallback(IUHFInventoryCallback)`.
- **RaceTimingOS architecture**: `scan/ScanController` (process-wide singleton, main thread) owns scanning and the read pipeline: daily CSV log + upload queue + live feed/per-tag stats. `scan/ScanService` is a foreground service (CPU + Wi-Fi locks) that keeps the process alive while scanning or while passings are waiting to upload; it stops itself when idle. `UploadManager` is started from `AppContext`, not from a screen. `DashboardFragment` is only a listener. Back while scanning asks before closing; Home keeps scanning in the background.
- **Upload queue rules**: platform Off = nothing is queued (reads stay in the daily CSV; "Re-send from log" in Settings queues a time range later). Blank Wiclax user/device ID or Rufus API key = passings stay queued, never dropped. Queue lines with no usable read time are skipped, never sent with the upload time. Rufus sends up to 50 passings per request; Wiclax one per request (relay batching unverified).
- **Device clock**: all passing times come from the device clock. `tools/ClockCheck` compares it with internet time (SNTP) at startup and the dashboard warns if it is off by more than 1 s. It never adjusts timestamps.
- **Files on device** (`/sdcard/racetiming/`): `racetiming_YYYYMMDD.csv` (every read), `pending.csv` (upload queue), `markers_YYYYMMDD.csv` (gun start), `bibs.csv` (optional `CHIP,BIB` list for display/manual entry), `settings_backup.json` (Export settings; contains the Rufus API key in plain text).
- **Wiclax protocol**: RaceTimingOS sends over HTTP GET to the Wiclax relay (`UploadManager.sendWiclax`, default `http://wiclax.com:35014?user=&deviceId=&passings=`). `WiclaxClient` is only a status broadcaster — there is no TCP server. `passings` uses the relay format `SEQ@EPC@yyyyMMddHHmmssSSS` (NOT the `;` line from the Wiclax TCP generic-acquisition spec). SEQ is a per-device-ID counter (prefs key `wiclax_seq_<deviceId>`) assigned at read time and stored as the 5th field of `pending.csv`; Wiclax shows it in its sequential-number column and draws a red line on any gap (= lost passing). The relay replies HTTP 200 even on rejection (`error: ...` in body, `ok` on success); body errors are treated as failures and retried. Received passings can be checked at `https://wiclax.com/pointages/pointages.php?ptg=<deviceId>&fab=GENERIQUE&u=<user>&v2=1&browser&filtre=T`. Every read is uploaded (no dedup). Read time is stamped in the SDK inventory callback and stored as epoch ms in `pending.csv` (`EPC;dd-MM-yyyy HH:mm:ss.SSS;RSSI;epochMs;seq`); uploads always use that, never the upload time, so offline backlog keeps true read times.
- **Scan trigger**: System Keyboard Emulator (`com.rscja.scanner`) grabs the trigger and fires the barcode engine. `MainActivity` closes it in `onResume` / reopens in `onPause` via `ScannerUtility` (not reopened while a scan is running in the background; `ScanController.stop()` reopens it then). Settings has a hold-to-scan trigger mode and a minimum-signal (RSSI) filter.
- **Rufus API**: `network/RufusClient.java` (bind device, sessions) + `UploadManager.sendRufus` — HTTP POST to `api.runonrufus.com/v0/passings` with `api_key` header. `num_passing` is the saved sequence counter (prefs key `rufus_seq`). Configured from prefs in `AppContext` at startup.
- **Signing**: Demo app uses `app/uhf-serial_release.jks` (pw `123456`, alias `uhf-serial-key`). RaceTimingOS release builds are signed from `keystore.properties` + `app/release.keystore` (both gitignored; keep backups — updates must use the same key). Debug and release builds have different signatures, so one cannot be installed over the other.
- **Java target**: 1.8 (`sourceCompatibility`/`targetCompatibility`). Do not upgrade without testing all hardware API calls.
- **Crash logs**: Written to `/sdcard/uhf/` on device (demo app).
- **Android permissions**: Both apps require `MANAGE_EXTERNAL_STORAGE` (Android 11+) and read/write external storage.
- **No root build/lint/test commands**: No CI, no lint, no typecheck at repo root. Validating requires a full Android build.
- **Unit tests**: RaceTimingOS has JVM tests for the queue line format, timestamps, sequence handling and passing formats (`.\gradlew testDebugUnitTest` in `docs_sdk/RaceTimingOS`). Keep `tools/QueueLine` and `tools/PassingFormat` free of Android classes so they stay testable. Nothing else is covered.
- **Build output is not tracked**: `docs_sdk/RaceTimingOS/app/build/`, `.gradle/` and `local.properties` are gitignored.
