#!/usr/bin/env python3
"""Isolated headless startup/Decky-revocation smoke test; never uses real app data."""
import os
from pathlib import Path
import socket
import subprocess
import sys
import tempfile
import time


def request(endpoint, command):
    port, token = endpoint.read_text().splitlines()
    with socket.create_connection(('127.0.0.1', int(port)), timeout=2) as client:
        client.settimeout(2)
        client.sendall(f'{token} {command}\n'.encode())
        return client.makefile().readline().strip()


with tempfile.TemporaryDirectory(prefix='syncdeck-headless-') as directory:
    root = Path(directory)
    env = dict(os.environ, XDG_DATA_HOME=str(root / 'data'), XDG_CACHE_HOME=str(root / 'cache'),
               XDG_CONFIG_HOME=str(root / 'config'), JAVA_TOOL_OPTIONS=f'-Djava.awt.headless=true -Djava.util.prefs.userRoot={root}/prefs')
    for key in ['DISPLAY', 'WAYLAND_DISPLAY', 'XDG_CURRENT_DESKTOP', 'DESKTOP_SESSION']:
        env.pop(key, None)
    command = sys.argv[1:]
    if not command:
        raise SystemExit('Usage: smoke-helper.py <launcher command...>')
    log = (root / 'helper.log').open('w+')
    process = subprocess.Popen(command + ['--background', '--decky'], env=env, stdout=log, stderr=subprocess.STDOUT)
    endpoint = root / 'data/syncdeck/worker.endpoint'
    try:
        for _ in range(100):
            if process.poll() is not None:
                log.seek(0)
                raise AssertionError('Helper exited during startup: ' + log.read())
            try:
                if request(endpoint, 'PING') == 'OK':
                    break
            except (OSError, ValueError):
                pass
            time.sleep(0.1)
        else:
            raise AssertionError('Helper did not publish a working control endpoint')
        assert endpoint.stat().st_mode & 0o077 == 0
        # Once the runtime has started, Decky sees the background owner.
        for _ in range(100):
            if request(endpoint, 'STATUS').startswith('OK\tbackground\t'):
                break
            time.sleep(0.1)
        else:
            raise AssertionError('Background runtime never started')
        maps = Path(f'/proc/{process.pid}/maps')
        if maps.exists():
            assert 'libskiko' not in maps.read_text().lower(), 'Headless helper loaded graphics runtime'
        assert request(endpoint, 'DECKY_DISABLE') == 'OK'
        assert process.wait(timeout=20) == 0, 'Helper failed to exit after plugin disabled'
        assert not endpoint.exists(), 'Stale control endpoint remains'
        # Without Desktop Mode or a Decky request, background startup must do no work.
        assert subprocess.run(command + ['--background'], env=env, stdout=log, stderr=subprocess.STDOUT, timeout=10).returncode == 0
        assert not endpoint.exists()
        print('Headless helper startup, private IPC, Decky revocation, and no-plugin shutdown passed.')
    finally:
        if process.poll() is None:
            process.kill()
            process.wait()
        log.close()
