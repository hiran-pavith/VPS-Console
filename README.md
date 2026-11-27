# VPS Console — Android SSH control & monitoring app

Native Kotlin + Jetpack Compose application that manages **multiple VPS servers directly over SSH** (no relay server required).

## Features

| Area | What is included |
| --- | --- |
| Servers | Unlimited servers, groups, search/filter, connect/disconnect, notes, color, host/port/user settings |
| Auth | Password or private key (PEM / OpenSSH / PKCS8) with optional passphrase; private key can be pasted or uploaded from a file |
| Security | Credentials encrypted with Android Keystore-backed `EncryptedSharedPreferences`; optional PIN + biometric app lock; SSH host-key trust-on-first-use or strict verification with fingerprint management |
| Terminal | Interactive PTY shell, ANSI cleanup, command history, Ctrl+C / Tab / Esc / arrows, font-size setting, quick snippets |
| Monitoring | CPU / RAM / disk / load / uptime / network rates, history charts, configurable refresh, threshold alert rules |
| Alerts | Rule editor, event history, acknowledge/delete, WorkManager background checks with Android notifications |
| Services | System load (1/5/15 min), `systemctl` service load/active status + start/stop/restart/logs, process list + kill, Docker containers + start/stop/restart/logs/prune |
| Files | SFTP browser, upload/download via Android SAF, inline text edit, create folder/file, rename, delete, chmod |
| Tools | Firewall (ufw/firewalld/iptables), cron jobs, users & sessions, package updates, tar.gz backups, local (-L) and reverse (-R) SSH port forwarding with optional VPS bind address, SFTP file manager, saved command library |
| Settings | Theme, refresh interval, terminal font, host-key policy, app lock & PIN, auto-lock, notifications, vibration, hidden files, keep-alive, confirm-dangerous toggle |
| Layout | Material 3, bottom navigation on phones, navigation rail on tablets, responsive cards/grids |

## Build

Requirements: **Android Studio Ladybug or newer**, Android SDK 36, JDK 17.

```bash
cd /home/admin/PROJECT/CONSOLE
./gradlew assembleDebug
```

Or open the folder in Android Studio and run the `app` configuration on a device/emulator with API 26+.

## First steps

1. Open **Servers → Add VPS**.
2. Enter host, SSH port, username, and either a password or a private key. Secret fields are left blank when editing to keep the stored value.
3. Tap **Save & test** to verify host-key trust and authentication.
4. Use the top bar server picker to choose the active server for Terminal/Tools.
5. Monitoring runs automatically in the background every 15 minutes; alerts appear as notifications and in **Alerts**.

## Security notes

- Connections are direct TCP SSH from the device. No credentials are sent to third-party services.
- First connection to a new host stores its key fingerprint (**trust on first use**). Select **Strict** in Settings to reject unknown hosts and manage fingerprints under **Known host keys**.
- App lock protects the UI and stored secrets with device biometrics or a PIN (SHA-256 + salt inside encrypted storage).
- Dangerous commands ask for confirmation by default.

## Project structure

```
app/src/main/java/com/vpsconsole/app/
├── data/        Room entities/DAOs, repositories, encrypted secrets, settings
├── ssh/         SSHJ connection manager, interactive shell, metrics probe, host-key verifier
├── worker/      Periodic monitoring worker
├── core/        Terminal buffer, formatters, notifications
└── ui/          Compose screens, navigation, theme, shared components
```

Key dependencies: SSHJ 0.38.0 (Terrapin mitigation), Room 2.6.1, WorkManager 2.10.0, Jetpack Compose BOM 2024.12.01, Security Crypto 1.1.0-alpha06.
