# RACE_TIMING_OS

Professional race timing Android application for the **Chainway C72** UHF RFID handheld reader. Built for race timers, event organizers, and athletic officials who need rugged reliability, extreme legibility in outdoor conditions, and low-latency tag processing.

## Features

- **Live Reads Dashboard** — Real-time session timer, total reads counter, unique tags counter, scrollable live feed with EPC, timestamp, and RSSI
- **UHF RFID Scanning** — Start/stop inventory with configurable power (0–30 dBm) and EPC prefix filtering. Physical trigger button support
- **Wiclax Cloud Integration** — Auto-sends tag reads to the Wiclax timing platform via HTTP GET (`user`, `deviceId`, `passings=@EPC@timestamp`)
- **CSV Logging** — Every tag read is saved locally to `/sdcard/racetiming/racetiming_YYYYMMDD.csv` in Wiclax-compatible format
- **Sound Feedback** — Audible beep on each tag read
- **Data Status Indicator** — Live green/orange/red dot on the dashboard showing send status to the API server
- **Send Statistics** — Real-time display of successful vs failed sends
- **Dark Theme** — High-contrast industrial dark interface optimized for OLED displays and direct sunlight
- **Persistent Settings** — All configuration saved to SharedPreferences across app restarts
- **Splash Screen** — Branded launch screen with TAS logo and company info

## Screens

| Screen | Description |
|---|---|
| **Splash** | Yellow branded splash with TAS logo, "TIMING IS EVERYTHING", company copyright |
| **Dashboard** | Session timer, KPI cards, action buttons (START/CLEAR), live feed, send status indicator |
| **Settings** | RFID power slider, EPC filter, Wiclax server config (URL, user, device ID), hardware monitor, SAVE button |

## Architecture

```
com.tas.racetiming/
├── activity/
│   ├── SplashActivity.java    — 2s branded splash → MainActivity
│   └── MainActivity.java      — Header + fragment container, trigger key handling
├── ui/
│   ├── dashboard/
│   │   └── DashboardFragment.java   — Live reads, scan controls, send status
│   └── settings/
│       └── SettingsFragment.java     — RFID power, EPC filter, Wiclax config
├── uhf/
│   └── UHFManager.java       — Singleton wrapping RSCJA RFIDWithUHFUART SDK
├── network/
│   ├── WiclaxClient.java     — HTTP GET client sending passing data to Wiclax
│   └── RufusClient.java      — Placeholder for RunOnRufus API integration
├── model/
│   ├── TagRead.java           — Tag data model (EPC, timestamp, RSSI)
│   └── SessionData.java       — Scan session state (counts, timer, unique tags)
└── tools/
    └── FileUtils.java         — CSV file writer to /sdcard/racetiming/
```

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

## Wiclax Configuration

| Setting | Default | Description |
|---|---|---|
| Server URL | `http://wiclax.com:35014` | Wiclax timing platform endpoint |
| User | (empty) | Wiclax account username |
| Device ID | `C72-SN-001` | Reader identifier sent as `@` in passing mask |

Tag data is sent as: `GET {url}?user={user}&deviceId={device}&passings=@{epc}@{YYYYMMDDHHmmssSSS}`

## Tech Stack

- **Android**: Java, AndroidX, Material 3
- **UHF SDK**: RSCJA DeviceAPI (prebuilt AAR at `app/libs/`)
- **Build**: AGP 8.2.2, Gradle 8.2
- **Target**: Java 1.8 compatibility

## License

Developed by **Total Active Sports**  
Contact: totalactivesports@gmail.com  
Development: Tanvir Ahmed, CTO
