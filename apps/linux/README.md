# SyncDeck for Linux and SteamOS — preview

SyncDeck is the Linux desktop peer for SyncDroid-Mesh. The dedicated Compose Desktop app is the primary interface, based on SyncTosh: pairing, approved Wi-Fi networks, sync folders, device map, chat attachments, recovery, and per-file management. It uses the existing shared mesh protocol and sync core.

## Desktop Mode and Gaming Mode

- **Desktop Mode:** opening SyncDeck shows the full app. Closing its window or choosing **Minimise to notification tray** releases the window process and leaves the smaller tray worker syncing on the configured schedule. The tray can reopen the app. “No background service” disables this behaviour. Launch at login uses the desktop session's autostart directory.
- **Gaming Mode / Big Picture on Steam Deck:** the dedicated app syncs while open. After it closes, no background helper remains unless the SyncDeck Decky plugin is installed and its **Background sync** switch is on.
- **Decky enabled:** the plugin runs the same headless worker and offers status and **Sync now**. Configure the mesh and folders in the dedicated app first. If that app is open, use its controls; the plugin never starts a second sync engine.
- **Decky disabled or unloaded:** its permission is revoked. Active transfers drain before the helper exits in Gaming Mode. A plugin crash expires its permission within 45 seconds, followed by transfer draining. In Desktop Mode the independent tray preference still applies.

The worker holds a single-instance lock. Before opening the full app it drains transfers and releases the runtime; after the window process exits it may restart the background runtime. The headless route does not initialise Compose/Skia or create a tray in Gaming Mode. Resource use still needs measurement on Steam Deck hardware.

## Build and install

Build the Steam Deck app image on **Linux x86_64**, with JDK 17 and Python 3:

```sh
bash apps/linux/installer/package.sh
```

This runs the unit tests, creates a self-contained application image including Java, exercises headless worker startup/shutdown, and produces `apps/linux/build/release/SyncDeck-Linux-x64-preview.tar.gz`. The `SyncDeck Linux preview` workflow provides Linux and Decky artifact jobs separately from stable releases.

Extract the archive in Desktop Mode and run:

```sh
python3 install.py ./SyncDeck
```

Run as the desktop user, without sudo. This installs under `~/.local/opt/syncdeck` and creates an application-menu entry. It leaves SteamOS's read-only system partition untouched. Exit SyncDeck and disable its Decky plugin before reinstalling; the installer refuses while the worker owns its lock. The prior installation is retained at `~/.local/opt/syncdeck.previous`.

Launch SyncDeck from the desktop menu. Pair with an existing mesh, register the Wi-Fi network, and choose local folders. Removable storage should use a stable mounted path and must be available when syncing. To use the dedicated window in Gaming Mode, add SyncDeck as a non-Steam game from Desktop Mode.

## Decky plugin

Uses the current [`@decky/api` and `@decky/ui` plugin interfaces](https://github.com/SteamDeckHomebrew/decky-plugin-template). The plugin is optional, runs without root, and requires the installed desktop package above.

```sh
cd apps/linux/decky
npm ci --ignore-scripts
npm run typecheck
npm run build
python3 -m unittest discover -s tests
```

For Decky's ZIP installer, create one `SyncDeck/` directory containing `dist/`, `main.py`, `plugin.json`, `package.json`, and `LICENSE`. The preview workflow packages this ZIP. Install it using Decky's development ZIP installation support, then enable **Background sync**. The switch defaults off and is remembered between plugin loads. This preview has not been submitted to the Decky store.

The plugin reads the same user's mode-0600 worker endpoint and sends bounded, token-authenticated loopback requests. It never receives mesh keys or opens the sync database. Only its Python backend keeps the permission alive; closing the plugin panel does not disable a deliberately enabled helper.

## Storage and preview limits

- Mesh database, identity, chat, and worker endpoint: `$XDG_DATA_HOME/syncdeck` (default `~/.local/share/syncdeck`).
- Updates and UI log: `$XDG_CACHE_HOME/syncdeck` (default `~/.cache/syncdeck`).
- Preferences: Java user preferences under the `com/syncdeck/app` node; plugin enable state lives in Decky's settings directory.
- Wi-Fi detection uses NetworkManager's `nmcli`, with no rescan. If no SSID is available, automatic syncing remains subject to the existing approved-network policy.
- Cloud provider registration is still required as described in [CLOUD_SETUP.md](../../CLOUD_SETUP.md).
- Linux native packages, KDE tray integration, Gaming Mode transitions, controller navigation, cross-device interoperability, suspend/resume, and SD-card behaviour require physical Steam Deck testing. The dedicated app currently retains the desktop interaction model; controller-only navigation is not yet certified.
- Linux self-update installation is not enabled for this preview. Exit and reinstall from Desktop Mode. The shared manifest can recognise an optional `linux-x64` asset while continuing to accept existing three-platform signed bundles. This does not add Linux to the current stable release train.
- The Linux port currently has its own desktop runtime/UI sources, like the Mac and Windows targets. Fixes to duplicated desktop code must be kept in step until a later shared-desktop extraction.
