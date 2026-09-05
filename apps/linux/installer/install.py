#!/usr/bin/env python3
"""Install a built SyncDeck app image for the current user without changing SteamOS."""
import fcntl
import os
from pathlib import Path
import shutil
import sys
import tempfile


def install(image):
    if os.getuid() == 0:
        raise SystemExit("Run this installer as your desktop user, without sudo")
    image = Path(image).resolve()
    if not (image / "bin/SyncDeck").is_file():
        raise SystemExit("Pass the SyncDeck app image directory containing bin/SyncDeck")
    home = Path.home()
    data = Path(os.environ.get("XDG_DATA_HOME", home / ".local/share"))
    state = data / "syncdeck"
    state.mkdir(parents=True, exist_ok=True, mode=0o700)
    with (state / "worker.lock").open("a") as lock:
        try:
            fcntl.lockf(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError:
            raise SystemExit("Exit SyncDeck and turn off its Decky plugin before installing")
        parent = home / ".local/opt"
        parent.mkdir(parents=True, exist_ok=True)
        destination = parent / "syncdeck"
        staging = Path(tempfile.mkdtemp(prefix="syncdeck-install-", dir=parent))
        backup = parent / "syncdeck.previous"
        try:
            shutil.copytree(image, staging / "app", symlinks=True)
            if backup.exists():
                shutil.rmtree(backup)
            if destination.exists():
                destination.rename(backup)
            try:
                (staging / "app").rename(destination)
            except Exception:
                if backup.exists():
                    backup.rename(destination)
                raise
        finally:
            shutil.rmtree(staging)
        applications = data / "applications"
        applications.mkdir(parents=True, exist_ok=True)
        icon_source = destination / 'lib/SyncDeck.png'
        icons = data / 'icons/hicolor/256x256/apps'
        if icon_source.is_file():
            icons.mkdir(parents=True, exist_ok=True)
            shutil.copy2(icon_source, icons / 'com.syncdeck.app.png')
        executable = str(destination / "bin/SyncDeck")
        escaped = executable.replace('\\', '\\\\').replace('"', '\\"').replace('`', '\\`').replace('$', '\\$').replace('%', '%%')
        (applications / "com.syncdeck.app.desktop").write_text(
            '[Desktop Entry]\nType=Application\nName=SyncDeck\nComment=Sync files with your trusted mesh\n'
            f'Exec="{escaped}"\nIcon=com.syncdeck.app\nTerminal=false\nCategories=Network;Utility;\nStartupWMClass=SyncDeck\n')
    print("Installed SyncDeck. Open it from the desktop application menu to pair and configure folders.")


if __name__ == "__main__":
    if len(sys.argv) != 2:
        raise SystemExit("Usage: python3 install.py /path/to/SyncDeck")
    install(sys.argv[1])
