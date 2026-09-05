#!/usr/bin/env python3
"""Exercise first install, safe reinstall, and live-worker refusal in an isolated home."""
import fcntl
import os
from pathlib import Path
import subprocess
import sys
import tempfile

installer = Path(sys.argv[1]).resolve()
with tempfile.TemporaryDirectory(prefix='syncdeck-installer-check-') as directory:
    root = Path(directory)
    env = dict(os.environ, HOME=str(root), XDG_DATA_HOME=str(root / '.local/share'), XDG_CACHE_HOME=str(root / '.cache'))
    env.pop('DISPLAY', None)
    env.pop('WAYLAND_DISPLAY', None)
    def run():
        return subprocess.run(['bash', str(installer)], env=env, text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=60)
    first = run()
    assert first.returncode == 0, first.stdout
    executable = root / '.local/opt/syncdeck/bin/SyncDeck'
    assert executable.is_file() and os.access(executable, os.X_OK)
    assert (root / '.local/share/applications/com.syncdeck.app.desktop').is_file()
    state = root / '.local/share/syncdeck'
    sentinel = state / 'test-user-data'
    sentinel.write_text('keep existing mesh data')
    with (state / 'worker.lock').open('a') as lock:
        fcntl.lockf(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        blocked = run()
        assert blocked.returncode != 0 and 'Exit SyncDeck' in blocked.stdout, blocked.stdout
        assert sentinel.read_text() == 'keep existing mesh data'
    second = run()
    assert second.returncode == 0, second.stdout
    assert sentinel.read_text() == 'keep existing mesh data'
    assert (root / '.local/opt/syncdeck.previous/bin/SyncDeck').is_file()
    subprocess.run(['python3', str(Path(__file__).with_name('smoke-helper.py')), str(executable)], env=env, check=True)
    print('Installer first install, lock refusal, safe reinstall, and installed helper checks passed.')
