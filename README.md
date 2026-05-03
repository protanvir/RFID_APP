# RACE_TIMING_OS

Professional race timing Android application for the **Chainway C72** UHF RFID handheld reader. Built for race timers, event organizers, and athletic officials who need rugged reliability, extreme legibility in outdoor conditions, and low-latency tag processing.

## Features

- **Live Reads Dashboard** — Real-time system clock, total reads counter, unique tags counter, scrollable live feed with EPC, timestamp, and RSSI
- **UHF RFID Scanning** — Start/stop inventory with configurable power (0–30 dBm) and EPC prefix filtering. Physical trigger button support
- **Platform Selector** — Choose your upload destination: **Off**, **Wiclax**, or **Rufus Cloud**
- **Wiclax Cloud Integration** — Sends tag reads to the Wiclax timing platform via HTTP GET with `user`, `deviceId`, and `passings=@EPC@timestamp`
- **Rufus Cloud API** — Full REST integration: bind device, create sessions, upload passings (`POST /passings`), telemetry reporting. Auto-binds on first START, creates session per scan session
- **File-Backed Upload Queue** — All tag reads queue to `/sdcard/racetiming/pending.csv`. A background processor sends from the file every 3 seconds. **Zero reads missed** — survives network drops, app restarts, and scan stop/start boundaries
- **CSV Logging** — Every tag read saved locally to `/sdcard/racetiming/racetiming_YYYYMMDD.csv` (one file per day)
- **CSV Download** — One-tap copy of the daily CSV to `Downloads/racetiming/` folder
- **Sound Feedback** — Audible beep on each tag read
- **Upload Status Indicator** — Live dot on dashboard: green = all sent, orange = pending queue, gray = platform off
- **Hardware Monitor** — Settings screen shows live UHF module temperature and battery health (read every 5s)
- **Dark Theme** — High-contrast industrial dark interface optimized for OLED displays and direct sunlight
- **Persistent Settings** — All configuration saved to SharedPreferences across app restarts
- **Splash Screen** — Branded launch screen with TAS logo and company info

## Screens

| Screen | Description |
|---|---|
| **Splash** | Yellow branded splash with TAS logo, "TIMING IS EVERYTHING", company copyright |
| **Dashboard** | Live system clock, KPI cards, action buttons (START/STOP/CLEAR/DOWNLOAD CSV), live feed, upload status indicator |
| **Settings** | Platform selector (Off / Wiclax / Rufus), RFID power slider, EPC filter, Wiclax server config, Rufus API config with BIND DEVICE button, hardware monitor |

## Architecture

```
com.tas.racetiming/
├── activity/
│   ├── SplashActivity.java        — 2s branded splash → MainActivity
│   └── MainActivity.java          — Header + fragment container, trigger key handling
├── ui/
│   ├── dashboard/
│   │   └── DashboardFragment.java — Live reads, scan controls, upload status
│   └── settings/
│       └── SettingsFragment.java  — Platform selector, RFID power, EPC filter, server configs, hardware monitor
├── uhf/
│   └── UHFManager.java            — Singleton wrapping RSCJA RFIDWithUHFUART SDK
├── network/
│   ├── WiclaxClient.java          — HTTP GET client for Wiclax timing platform
│   ├── RufusClient.java           — Full REST client for Rufus Cloud API (bind, sessions, passings, telemetry)
│   └── UploadManager.java         — File-backed upload queue: appends reads to pending.csv, processes every 3s
├── model/
│   ├── TagRead.java               — Tag data model (EPC, timestamp, RSSI)
│   └── SessionData.java           — Scan session state (counts, unique tags)
└── tools/
    └── FileUtils.java             — CSV file writer to /sdcard/racetiming/ (date-wise files)
```

## Upload Queue — How It Works

1. Every tag read is **immediately appended** to `/sdcard/racetiming/pending.csv` — no network call during read
2. A **background processor** runs every 3 seconds while the app is alive
3. It reads from a cursor position (stored in prefs), sends each line to the active platform via direct HTTP, and advances the cursor on success
4. On failure (network down, session not ready), it stops and retries next cycle — **no data lost**
5. When all lines are sent, the pending file is deleted and cursor resets
6. The processor is **independent of scan state** — runs even when scanning is stopped, ensuring any lingering queue drains

## Platform Configuration

### Wiclax

| Setting | Default | Description |
|---|---|---|
| Server URL | `http://wiclax.com:35014` | Wiclax timing platform endpoint |
| User | (empty) | Wiclax account username |
| Device ID | `C72-SN-001` | Reader identifier |

Tag data sent as: `GET {url}?user={user}&deviceId={device}&passings=@{epc}@{YYYYMMDDHHmmssSSS}`

### Rufus Cloud

| Setting | Auto-fill | Description |
|---|---|---|
| API Key | — | Rufus API key (`api_key` header) |
| Device SN | `Build.SERIAL` or `C72-SN-001` | Serial number sent in device bind request |
| Device Alias | `C72-UHF-{SN}` | Human-readable alias for the device |

Flow: **BIND DEVICE** (POST /devices) → START creates session (POST /sessions) → UploadManager sends passings (POST /passings) → STOP closes session (PATCH /sessions/close/{token})

## Prerequisites

- **Hardware**: Chainway C72 (or compatible RSCJA UHF RFID reader)
- **Android SDK**: API 34 (compileSdk), minSdk 26
- **Java**: JDK 17+
- **Gradle**: 8.2 (wrapper included)

## Building

```powershell
cd docs_sdk/RaceTimingOS
.\gradlew assembleDebug
```

Output: `app/build/outputs/apk/debug/app-debug.apk`

## Installing on C72

```powershell
adb install -r app\build\outputs\apk\debug\app-debug.apk
```

## Tech Stack

- **Android**: Java, AndroidX, Material 3
- **UHF SDK**: RSCJA DeviceAPI (prebuilt AAR at `app/libs/`)
- **HTTP**: `HttpURLConnection` (no third-party HTTP library)
- **JSON**: `org.json` (Android built-in)
- **Build**: AGP 8.2.2, Gradle 8.2
- **Target**: Java 1.8 compatibility

## License

Developed by **Total Active Sports**  
Contact: totalactivesports@gmail.com  
Development: Tanvir Ahmed, CTO
