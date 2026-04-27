# Pi-hole WiFi Monitor (Root Required)

[![Build Pi-hole Monitor APK](https://github.com/YOUR_USERNAME/Pi-Hole-Status/actions/workflows/build.yml/badge.svg)](https://github.com/YOUR_USERNAME/Pi-Hole-Status/actions/workflows/build.yml)

An Android companion app for [Pi-hole for Android](https://github.com/DesktopECHO/Pi-hole-for-Android) by DesktopECHO. This app monitors and maintains WiFi connectivity, custom static IP assignment, and DNS routing through a locally-running Pi-hole instance.

## ⚠️ Root Required

This app requires **root access** (Magisk, KernelSU, or similar). It uses root shell commands to manage WiFi, network interfaces, DNS settings, and tethering.

## Features

- **Automatic WiFi Management** — Connects to a configured WiFi network and maintains the connection
- **Custom Static IP** — Assigns a custom IP address to the WiFi interface
- **DNS Enforcement** — Forces all DNS traffic through the local Pi-hole using multiple methods (settings, iptables, ndc, setprop)
- **Pi-hole Health Monitoring** — Continuously checks that Pi-hole is running and responsive
- **Auto-Recovery** — Automatically reconnects WiFi and reapplies settings on failure
- **USB/Ethernet Tethering** — Optionally enables tethering so other devices use Pi-hole
- **Boot Auto-Start** — Optionally starts monitoring automatically on device boot
- **Comprehensive Logging** — Color-coded log viewer with export capability
- **Capability Detection** — Detects available shell commands and adapts fallback strategies

## Architecture

| Layer | Technology |
|-------|-----------|
| Language | Kotlin |
| UI | Jetpack Compose + Material 3 |
| Root | libsu (topjohnwu) |
| Background | ForegroundService |
| Settings | Jetpack DataStore |
| Architecture | MVVM + Repository |
| Async | Coroutines + StateFlow |
| Build | Gradle Kotlin DSL, AGP 8.x |
| CI/CD | GitHub Actions |

## Requirements

- Android 5.0+ (API 21)
- Root access (Magisk/KSU)
- [Pi-hole for Android](https://github.com/DesktopECHO/Pi-hole-for-Android) installed and configured

## Building

### Prerequisites

- JDK 17
- Android SDK (compileSdk 35)

### Build Debug APK

```bash
./gradlew assembleDebug
```

### Build Release APK (unsigned)

```bash
./gradlew assembleRelease
```

APKs will be in `app/build/outputs/apk/`.

## How It Works

The app runs a foreground service that executes the following phases sequentially:

1. **Detect Capabilities** — Probes for available shell commands (`ip`, `wpa_cli`, `cmd`, `svc`, etc.)
2. **Enable WiFi** — Ensures WiFi radio is on
3. **Connect to WiFi** — Connects to the configured SSID
4. **Wait for Connection** — Polls until WiFi is connected with an IP
5. **Add Custom IP** — Assigns the configured static IP to the WiFi interface
6. **Set DNS** — Configures DNS to point to the local Pi-hole (runs ALL methods)
7. **Wait for Pi-hole** — Blocks until Pi-hole responds on the configured port
8. **Enable Tethering** — Optionally enables USB/Ethernet tethering
9. **Monitor Loop** — Continuously checks WiFi, IP, and Pi-hole status; auto-recovers on failure

### Fallback Strategy

Every operation uses a cascading fallback chain:
- Try method 1 → if success, stop
- Try method 2 → if success, stop
- ...
- If all methods fail → log ERROR

**Exception:** DNS configuration runs ALL methods regardless of success, because multiple enforcement layers are needed simultaneously.

## Configuration

All settings are configurable through the app's Configuration screen:

| Setting | Default | Description |
|---------|---------|-------------|
| WiFi SSID | YourSSID | Target WiFi network name |
| WiFi Password | YourPassword | WiFi password |
| Custom IP | 192.168.1.100 | Static IP to assign |
| Prefix Length | 24 | Network prefix |
| Subnet Mask | 255.255.255.0 | Network mask |
| DNS Primary | 127.0.0.1 | Primary DNS (local Pi-hole) |
| DNS Secondary | 127.0.0.1 | Secondary DNS |
| Pi-hole IP | 127.0.0.1 | Pi-hole address |
| Pi-hole Port | 80 | Pi-hole web admin port |
| WiFi Interface | wlan0 | WiFi interface name |
| Monitor Interval | 15s | Check interval |
| USB Tethering | OFF | Enable USB tethering |
| ETH Tethering | OFF | Enable Ethernet tethering |
| Auto-start on Boot | OFF | Start on device boot |

## Screenshots

The app has 4 main screens:
- **Dashboard** — Service control, status cards, recent logs
- **Configuration** — All settings with validation
- **Log** — Filterable, color-coded log viewer
- **Status** — Root status, detected capabilities, device info

## CI/CD

GitHub Actions automatically builds debug and release APKs on:
- Push to `main`, `master`, or `develop`
- Pull requests to `main` or `master`
- Tag pushes (`v*`) — creates a GitHub Release with APKs

## License

This project is provided as-is. See the [Pi-hole for Android](https://github.com/DesktopECHO/Pi-hole-for-Android) project for the upstream Pi-hole Android implementation.
