# SyncTosh

SyncTosh is the self-contained Apple Silicon macOS peer for the SyncDroid-Mesh network.

The current preview includes the native macOS application, Android-compatible mesh pairing, folder synchronization, resumable transfers, file history, and signed mesh chat. Later parity phases follow [`docs/design-plan.md`](docs/design-plan.md).

Requirements:

- macOS 13 or newer
- Apple Silicon (ARM64); Intel Macs are not supported

At wide window sizes, Sync, Folders, Devices, and Settings automatically reflow into desktop columns. Chat intentionally remains a single conversation column.

The SyncTosh icon is derived from the SyncDroid-Mesh artwork, with a macOS-inspired multicolour centre to distinguish the desktop app.

## Implemented mesh foundation

- P-256 device identity retained in an owner-only local identity file, with one-time migration of existing Keychain identities
- SQLite mesh profile, membership log, trusted-device projection, and separately pinned TLS keys
- Android-compatible Bonjour and UDP pairing discovery
- Transcript-authenticated six-digit J-PAKE pairing
- Five attempts per rolling 15-minute attempt window
- Android-compatible signed membership and mesh-bundle codecs, including verification of immutable membership-v1 histories created by early SyncDroid-Mesh builds
- Signed Android folder announcements persisted as per-Mac Configure/Configured/Declined entries
- Existing-folder selection and new local folder creation for received mesh folders
- Signed TLS-bound identity proofs and returning-peer synchronization sessions
- Local-network privacy and Bonjour service declarations in the macOS bundle
- Per-file indexes with version vectors, tombstones, received/applied acknowledgements, and conflict detection
- Hash-verified whole-file and resumable block transfer with atomic file application
- Thirty-day deletion recovery and file history
- Signed, persisted mesh chat replicated through trusted peers
- Open configured folders directly in Finder
- Menu-bar background operation with midnight-aligned discovery intervals and configurable windows

## Background process model

SyncTosh uses two mutually exclusive mesh-runtime modes:

- The menu-bar worker runs without loading Compose or Skia. It owns scheduled synchronization while the window is closed.
- Opening SyncTosh stops new background sessions, lets any authenticated transfer finish, closes the worker runtime, and launches the Compose window in a separate process.
- Closing the window releases the Compose process and returns mesh ownership to the worker. The mesh database is therefore opened by only one runtime at a time.
- An operating-system file lock enforces one worker and one mesh-database owner even if control-socket health checks fail during startup.
- Worker control is restricted to an authenticated loopback socket whose token file is owner-readable only.
- UDP fallback discovery, pairing broadcasts, and Bonjour are fully closed outside foreground use, always-on mode, or a scheduled discovery window. The low-frequency Wi-Fi check remains active so the worker can recognize a registered network.
- Failed UDP or Bonjour startup is retried with bounded backoff while discovery remains requested.
- Active transfers have no total-duration cutoff. A peer socket is considered stalled only after five minutes without network activity, and all sockets are closed and joined before SQLite shuts down.

## Development

```bash
./gradlew run
```

## Package the macOS application

```bash
./gradlew packageDmg
```

## Desktop updates

The in-app update action installs the verified DMG into the running app's existing
location and reopens SyncTosh. The app must be installed in a writable Applications
folder (not running from a mounted disk image). The detached helper waits for the
UI and worker to exit, validates the incoming bundle identity, stages a complete
copy beside the existing app, and swaps the bundles. A failed swap or launch
restores the old bundle. User data and synced folders are never part of the swap.
Failures show a dialog with the helper log path.

The full release artifact is still downloaded; this change streamlines installation,
not download size. Versions predating this updater use their existing manual DMG
flow once to install the new updater. First-time installs still use the DMG.

`packageDmg` embeds the canonical `synctosh.icns` as the mounted volume icon as
well as the app icon. The volume branding survives uploading/downloading the DMG.
Run `python3 -m unittest discover -s installer/tests -v` from this directory to
exercise staging, rejection, and rollback with isolated filesystem fixtures.

Before launching the update helper, the foreground app disables discovery and new
connections and waits for its active sessions and synchronization lock to drain.
The UI displays “Preparing update” during this wait. Only after the runtime closes
does it start the helper and quit the worker; it does not hand control back to a
background sync. A failed drain prevents both installer launch and update shutdown.
The helper's process-exit timeout therefore does not limit the transfer drain time.
