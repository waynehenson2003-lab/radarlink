# SØPHIA — Passive Signal Awareness for Android

Offline-first Kotlin / Jetpack Compose app that observes nearby Wi-Fi access points and BLE
advertisements using only the public Android APIs. No root, no Termux, no internet permission.

## Build the APK

**Android Studio (recommended)**
1. Open this folder in Android Studio (Narwhal 2025.1 or newer).
2. Let Gradle sync (downloads AGP 8.11, Kotlin 2.2, Compose BOM 2025.06).
3. Build ▸ Build APK(s). Output: `app/build/outputs/apk/debug/app-debug.apk`.

**Command line** (JDK 17 + Android SDK with platform 36 installed, `ANDROID_HOME` set):
```
./gradlew assembleDebug        # app/build/outputs/apk/debug/app-debug.apk
./gradlew assembleRelease      # minified, signed with the debug key
```

**No computer with the SDK?** Push this folder to a GitHub repository. The included workflow
(`.github/workflows/build.yml`) builds both APKs on every push; download them from the run's
*Artifacts* section.

Install: `adb install app-debug.apk`, or copy it to the phone and allow "Install unknown apps".
Before distributing, replace the debug signing config in `app/build.gradle.kts` with your own key.

## Sections
| Tab | What it does |
|---|---|
| COMMAND | Engine status, live counters, mini radar, signal density, hardware classes, top vendors, priority feed |
| RADAR | Animated sweep; one marker per real observation. Radius = RSSI, angle = stable hash (not a bearing) |
| WI-FI | Start/stop/clear/export; SSID, BSSID, RSSI + trend, band/channel/width/standard, security, vendor, class |
| BLE | Start/stop/clear/export; address, name, company IDs, raw manufacturer data, services, TX power, iBeacon/Eddystone, rotating-address hints |
| PRIORITY | Live priority signals, watchlists (exact ID, name, vendor, BLE company, category), flagged identifiers, stored matches |
| HISTORY | Sessions (sort, view, delete), compare any two sessions, full-text search across all history |
| DEBRIEF | Per-session report; export CSV, JSON (`sophia.debrief.v1`) and an AI-readable Markdown debrief |
| SETTINGS | Permissions with explanations, scan interval / BLE mode / RSSI floor, alert tone/volume/cooldown, priority rules, OUI import, retention, wipe |

## Architecture
```
scan/      WifiEngine (WifiManager scans, throttle-aware) · BleEngine (BluetoothLeScanner, rate-limited,
           25-min restart) · ScanController (independent Wi-Fi/BLE control) · ScanService (foreground, type=location)
data/      Repository (single-thread SQLite writer, live StateFlows, watch matching, alerts) · SophiaDb (SQLite + WAL)
           · LocalCrypto (AES-256-GCM, Android Keystore) · Prefs
classify/  Classifier (explainable rules + confidence + evidence) · OuiRegistry (built-in seed + imported IEEE) · tables
export/    DebriefEngine · Exporter (CSV / JSON / AI debrief)
alert/     Alerts (ToneGenerator, vibration, notifications)
ui/        Compose screens, tactical theme, radar canvas
```

## Manufacturer database
A small built-in OUI seed works offline from first launch. For full coverage download the IEEE
MA-L registry `oui.csv` (https://standards-oui.ieee.org/oui/oui.csv) on any device, copy it to the
phone, and use **Settings ▸ OUI database ▸ Import**. The app never downloads it itself.

## What Android does not expose (and SØPHIA does not fake)
- Wi-Fi scans are throttled to 4 per 2 minutes in the foreground (Developer options ▸ "Wi-Fi scan throttling" can lift this). Rejected requests read the OS cache; only entries with a new timestamp are counted.
- Wi-Fi results are empty while Location services are off.
- No client devices, probe requests, or traffic — Android only reports access points.
- BLE addresses on modern phones/wearables rotate; identity across rotations cannot be established. The "≈N" tag only flags identical payloads, with low confidence.
- Unfiltered BLE scans pause with the screen off on many devices; OEM battery managers (Samsung, Xiaomi) may stop the service — set battery usage to Unrestricted.
- No direction finding or positioning; the radar is a signal-strength display.
- Classification identifies hardware makers/classes from OUIs, company IDs and naming heuristics. It never attributes a signal to a person, vehicle, or organization.
