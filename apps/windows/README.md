# SyncDows for Windows

SyncDows is the Windows peer in the SyncDroid-Mesh local-Wi-Fi network. It uses the same Compose Desktop interface and interoperable mesh behavior as SyncTosh while storing its state beneath `%LOCALAPPDATA%\Fullm3t41\SyncDows` and integrating with File Explorer and the Windows system tray.

## Current implementation

- Equal-peer mesh creation and six-digit pairing.
- Local mDNS and UDP discovery with fingerprint-pinned TLS sessions.
- Shared pairing, session, index and transfer codecs.
- SQLite membership, folder, file-index, chat and history persistence.
- Whole-file and resumable block transfers with atomic application.
- Matching SyncTosh Sync, Folders, Devices, Chat and Settings interface.
- Folder selection/creation and Open in File Explorer.
- Conflict comparison with keep-local, keep-remote and numbered keep-both choices.
- Signed overwrite-only exception listing and Undo controls.
- Mesh-wide device renaming/removal and a signed Leave mesh flow.
- Multiple registered Wi-Fi networks for background-only power restrictions.
- Lightweight tray worker with discovery interval and duration controls. The full Compose/Skia UI runs in a separate process and unloads when its window closes.
- Scheduled discovery closes UDP and Bonjour sockets between windows, polls Wi-Fi less often in the background and lets active transfers finish before changing processes.
- Self-contained branded EXE configuration for native Windows builds.

## Development verification

The JVM sources and shared compatibility fixtures can be compiled and tested on macOS or Linux:

```powershell
.\gradlew.bat test
```

On macOS/Linux use `./gradlew test`. Native EXE packaging must run on Windows:

```powershell
.\gradlew.bat packageExe packageMsi
```

For a clean test and both versioned installers, run:

```powershell
.\build-windows.ps1
```

The result is written to `build\release\SyncDows-<version>-Windows-x64.exe`, using the version in `build.gradle.kts`. An explicit `-Version` must match the project and MSI version. The EXE is a branded, dark WiX bootstrapper around the internal MSI. Before installation begins it validates the chosen path and preserves legacy mesh state. Application files default to `%ProgramFiles%\SyncDows` (normally `C:\Program Files\SyncDows`) and install for all users after Windows requests administrator approval. Persistent identity and mesh data remain per-user under `%LOCALAPPDATA%\Fullm3t41\SyncDows`.

The installed application folder includes `Uninstall SyncDows.exe` and `Uninstall-SyncDows.ps1`. The script is also copied to `build\release` for standalone use. Windows repair and Installed Apps removal remain available. The Windows workflow tests a fresh default installation and scripted removal on its disposable runner, and retains installer logs as an artifact.

The installer bundles its Java runtime; end users do not need to install Java. Physical Windows testing remains required for Windows Firewall prompts, LAN interface selection, tray lifecycle, sleep/wake behavior and installer upgrades.

## Moving from an older per-user installation

Windows Installer cannot upgrade a per-user MSI directly to a machine-wide MSI. Quit SyncDows from its notification-area menu, run the new `Uninstall-SyncDows.ps1` (or remove SyncDows in Windows Settings), then install the new version. The new installer detects a registered per-user copy and stops with this guidance instead of installing a second conflicting copy. It preserves legacy mesh files before stopping. The standalone uninstall script also preserves legacy identity/database files from `%LOCALAPPDATA%\SyncDows` into the current data directory without replacing existing files. If a very old version still stores its data beside application files, use the new script or run the new installer once before using Windows Settings to remove it.

## Uninstalling

Quit SyncDows from the notification area, then use Windows Settings > Apps, `Uninstall SyncDows.exe`, or PowerShell:

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "C:\Program Files\SyncDows\Uninstall-SyncDows.ps1"
```

The script uses the registered WiX setup (or the legacy MSI product IDs), waits for its result, and prints the log location under `%TEMP%\SyncDows-uninstall-<id>`. It retains mesh data and synced folders. `-Quiet` suppresses installer UI; Windows can still request administrator approval. `-WhatIf` shows the intended action without uninstalling. A missing or ambiguous registration produces an error; the script never recursively deletes an application or user-data folder. Exit code `3010` means a restart is required, not forced.

## Collecting a failed-install log

From the folder containing the downloaded installer, replace the filename below with the version being tested:

```powershell
.\SyncDows-1.2.15-Windows-x64.exe /log "$env:TEMP\SyncDows-install.log"
```

After setup finishes, collect **all** `SyncDows-install*.log` files in `%TEMP%`, including the MSI logs and, in new builds, `SyncDows-install.log.preflight.log`. Include the installer filename, Windows version/architecture, error text, and whether SyncDows was previously installed. With no `/log` argument, new builds log to `%TEMP%\SyncDows-setup*.log`. Logs can contain local usernames and folder paths.

Installer regression checks can be run without installing the application:

```powershell
.\installer\tests\Test-UninstallScript.ps1
```

The complete native install/uninstall test is for a **disposable x64 Windows machine only** and refuses an existing SyncDows installation:

```powershell
.\installer\tests\Test-PackagedInstaller.ps1 -Installer .\build\release\SyncDows-1.2.15-Windows-x64.exe -DisposableMachine
```

## Desktop updates

The in-app update action waits for SyncDows to close, runs the verified installer
in progress-only mode against the current application folder, and reopens the app
on success. It does not ask users to uninstall or choose their folder again.
The existing stable MSI and bundle upgrade identifiers continue to handle subsequent machine-wide upgrades;
identity, settings and synchronized files remain separate from application files.
A per-user installation needs the one-time migration described above.
A required Windows restart is reported rather than forced. Errors display the
installer log location; a failed installer does not launch the app automatically.

The download is still the full release EXE. Older app versions open the original
setup UI once when installing the version containing the streamlined updater.
Setup, the app and the uninstaller share `syncdows.ico`; setup's header uses the
same PNG source as the app window and tray. The Windows build runs a native helper
smoke test covering progress-only arguments, installation paths containing spaces
and apostrophes, and reopening the app.

Before launching the update helper, the foreground app disables discovery and new
connections and waits for its active sessions and synchronization lock to drain.
The UI displays “Preparing update” during this wait. Only after the runtime closes
does it start the helper and quit the worker; it does not hand control back to a
background sync. A failed drain prevents both installer launch and update shutdown.
The helper's process-exit timeout therefore does not limit the transfer drain time.
