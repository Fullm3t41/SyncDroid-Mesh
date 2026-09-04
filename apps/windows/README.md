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

The result is written to `build\release\SyncDows-1.2.8-Windows-x64.exe`. The EXE is a branded, dark WiX bootstrapper around the internal MSI. Before installation begins it validates the chosen path and preserves legacy mesh state. Application files default to `%LOCALAPPDATA%\Programs\SyncDows`; persistent identity and mesh data live separately under `%LOCALAPPDATA%\Fullm3t41\SyncDows`. The installed application folder includes `Uninstall SyncDows.exe`, and normal Windows repair, upgrade and Installed Apps removal remain available. The repository also contains Windows and cross-platform release workflows that run compatibility tests and publish update-compatible installers.

The installer bundles its Java runtime; end users do not need to install Java. Physical Windows testing remains required for Windows Firewall prompts, LAN interface selection, tray lifecycle, sleep/wake behavior and installer upgrades.

## Desktop updates

The in-app update action waits for SyncDows to close, runs the verified installer
in progress-only mode against the current application folder, and reopens the app
on success. It does not ask users to uninstall or choose their folder again.
The existing stable MSI and bundle upgrade identifiers continue to handle upgrades;
identity, settings and synchronized files remain separate from application files.
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
