"""Decky controls SyncDeck's same-user helper; it never opens the mesh database."""
import asyncio
import base64
import contextlib
import json
import os
from pathlib import Path

import decky


class Plugin:
    def __init__(self):
        self.enabled = False
        self.task = None
        self.error = None
        self.lock = asyncio.Lock()
        self.settings = Path(decky.DECKY_PLUGIN_SETTINGS_DIR) / "syncdeck.json"
        self.home = Path(os.path.expanduser("~"))
        self.data = Path(os.environ.get("XDG_DATA_HOME", self.home / ".local/share")) / "syncdeck"

    async def _command(self, command):
        # A token protected by mode 0600 avoids an unauthenticated localhost control API.
        endpoint = self.data / "worker.endpoint"
        info = endpoint.lstat()
        if endpoint.is_symlink() or info.st_uid != os.getuid() or info.st_mode & 0o077:
            raise RuntimeError("SyncDeck endpoint permissions are unsafe")
        if info.st_size > 256:
            raise RuntimeError("Invalid SyncDeck endpoint")
        port, token = endpoint.read_text().splitlines()
        if not port.isdecimal() or not 0 < int(port) < 65536 or not 32 <= len(token) <= 128:
            raise RuntimeError("Invalid SyncDeck endpoint")
        reader, writer = await asyncio.wait_for(asyncio.open_connection("127.0.0.1", int(port), limit=4096), 2)
        try:
            writer.write(f"{token} {command}\n".encode())
            await writer.drain()
            reply = (await asyncio.wait_for(reader.readline(), 3)).decode().strip().split("\t")
            if reply[0] != "OK":
                raise RuntimeError("SyncDeck rejected the request")
            return reply
        finally:
            writer.close()
            await writer.wait_closed()

    async def _ensure_helper(self):
        try:
            await self._command("DECKY_KEEPALIVE")
            return
        except (OSError, ValueError, asyncio.TimeoutError):
            pass
        executable = self.home / ".local/opt/syncdeck/bin/SyncDeck"
        if not executable.is_file():
            raise RuntimeError("Install the SyncDeck desktop app first, then enable background sync here")
        if os.getuid() == 0:
            raise RuntimeError("SyncDeck must run as the desktop user, not root")
        # No shell and no graphics environment required for this path.
        process = await asyncio.create_subprocess_exec(str(executable), "--background", "--decky",
            stdin=asyncio.subprocess.DEVNULL, stdout=asyncio.subprocess.DEVNULL,
            stderr=asyncio.subprocess.DEVNULL, start_new_session=True)
        for _ in range(20):
            await asyncio.sleep(0.25)
            try:
                await self._command("DECKY_KEEPALIVE")
                return
            except (OSError, ValueError, asyncio.TimeoutError):
                if process.returncode is not None:
                    break
        raise RuntimeError("SyncDeck helper did not start; open the desktop app to check setup")

    async def _heartbeat(self):
        while True:
            async with self.lock:
                if self.enabled:
                    try:
                        await self._ensure_helper()
                        self.error = None
                    except Exception as error:
                        self.error = str(error)
            await asyncio.sleep(10)

    async def set_enabled(self, enabled: bool):
        if type(enabled) is not bool:
            raise ValueError("enabled must be a boolean")
        async with self.lock:
            self.enabled = enabled
            self.settings.parent.mkdir(parents=True, exist_ok=True)
            temporary = self.settings.with_suffix(".tmp")
            temporary.write_text(json.dumps({"enabled": enabled}))
            temporary.replace(self.settings)
            if enabled:
                try:
                    await self._ensure_helper()
                    self.error = None
                except Exception as error:
                    self.error = str(error)
            else:
                with contextlib.suppress(Exception):
                    await self._command("DECKY_DISABLE")
                self.error = None
        return await self.status()

    async def status(self):
        result = {"enabled": self.enabled, "mode": "stopped", "status": self.error or "Background sync is off", "online": 0, "devices": 0}
        try:
            reply = await self._command("STATUS")
            if len(reply) == 5:
                result.update(mode=reply[1], status=self.error or base64.b64decode(reply[2]).decode(), online=int(reply[3]), devices=int(reply[4]))
        except (OSError, ValueError, asyncio.TimeoutError, RuntimeError):
            pass
        return result

    async def sync_now(self):
        async with self.lock:
            if not self.enabled:
                raise RuntimeError("Enable background sync first")
            state = await self.status()
            if state["mode"] == "app":
                raise RuntimeError("Use Sync now in the open SyncDeck app")
            await self._ensure_helper()
            await self._command("SYNC_NOW")
        return await self.status()

    async def _main(self):
        try:
            self.enabled = json.loads(self.settings.read_text()).get("enabled") is True
        except (OSError, ValueError):
            self.enabled = False
        self.task = asyncio.create_task(self._heartbeat())

    async def _unload(self):
        if self.task:
            self.task.cancel()
            with contextlib.suppress(asyncio.CancelledError):
                await self.task
        with contextlib.suppress(Exception):
            await self._command("DECKY_DISABLE")

    async def _uninstall(self):
        await self._unload()
