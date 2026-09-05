import asyncio
import base64
import importlib.util
from pathlib import Path
import sys
import tempfile
import types
import unittest
from unittest.mock import AsyncMock

sys.modules['decky'] = types.SimpleNamespace(DECKY_PLUGIN_SETTINGS_DIR='/unused')
spec = importlib.util.spec_from_file_location('syncdeck_plugin', Path(__file__).parents[1] / 'main.py')
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


class PluginTests(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.plugin = module.Plugin()
        self.plugin.settings = Path(self.temp.name) / 'settings.json'
        self.plugin.data = Path(self.temp.name)

    async def asyncTearDown(self):
        self.temp.cleanup()

    async def test_disabled_by_default_and_no_sync(self):
        self.assertFalse(self.plugin.enabled)
        with self.assertRaises(RuntimeError):
            await self.plugin.sync_now()

    async def test_disable_and_unload_revoke_permission(self):
        self.plugin._command = AsyncMock(return_value=['OK'])
        self.plugin.enabled = True
        await self.plugin.set_enabled(False)
        self.plugin._command.assert_any_await('DECKY_DISABLE')
        self.assertFalse(self.plugin.enabled)
        await self.plugin._unload()
        self.plugin._command.assert_awaited_with('DECKY_DISABLE')

    async def test_control_protocol_and_private_endpoint(self):
        token = 'a' * 43
        async def handle(reader, writer):
            line = await reader.readline()
            self.assertEqual(line, f'{token} STATUS\n'.encode())
            text = base64.b64encode(b'Ready').decode()
            writer.write(f'OK\tbackground\t{text}\t1\t3\n'.encode())
            await writer.drain()
            writer.close()
        server = await asyncio.start_server(handle, '127.0.0.1', 0)
        async with server:
            endpoint = self.plugin.data / 'worker.endpoint'
            endpoint.write_text(f'{server.sockets[0].getsockname()[1]}\n{token}\n')
            endpoint.chmod(0o600)
            state = await self.plugin.status()
            self.assertEqual(state['status'], 'Ready')
            self.assertEqual(state['online'], 1)
            endpoint.chmod(0o644)
            with self.assertRaises(RuntimeError):
                await self.plugin._command('STATUS')

    async def test_open_app_prevents_second_sync_owner(self):
        self.plugin.enabled = True
        self.plugin.status = AsyncMock(return_value={'mode': 'app'})
        self.plugin._ensure_helper = AsyncMock()
        with self.assertRaises(RuntimeError):
            await self.plugin.sync_now()
        self.plugin._ensure_helper.assert_not_awaited()
