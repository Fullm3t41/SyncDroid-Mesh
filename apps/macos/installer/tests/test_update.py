"""Exercise the shipped updater's swap/rollback logic in isolated fixture folders."""
import os
from pathlib import Path
import subprocess
import tempfile
import unittest

SCRIPT = Path(__file__).resolve().parents[2] / 'src/main/resources/updates/install-update.sh'


class MacUpdateTest(unittest.TestCase):
    def run_update(self, mode):
        with tempfile.TemporaryDirectory(prefix="update test ' ") as temporary:
            root = Path(temporary)
            target = root / 'SyncTosh.app'
            target.mkdir()
            (target / 'version').write_text('old')
            data = root / 'Application Support'
            data.mkdir()
            (data / 'identity.p12').write_text('identity')
            (data / 'settings.db').write_text('settings')
            source = root / 'release/SyncTosh.app/Contents/MacOS'
            source.mkdir(parents=True)
            (source / 'SyncTosh').write_text('new executable')
            (source / 'SyncTosh').chmod(0o700)
            (root / 'release/SyncTosh.app/version').write_text('new')
            helper = root / 'helper'
            helper.mkdir()
            # Substitute OS services, leaving the actual staged copy, swap and rollback intact.
            replacements = {
                '/usr/bin/hdiutil': 'fake_hdiutil',
                '/usr/libexec/PlistBuddy': 'fake_plist',
                '/usr/bin/ditto': 'fake_ditto',
                '/usr/bin/open': 'fake_open',
                '/bin/mv': 'fake_mv',
                '/usr/bin/osascript': 'true',
            }
            code = SCRIPT.read_text()
            for original, replacement in replacements.items():
                code = code.replace(original, replacement)
            stubs = '''
fake_hdiutil() {
    if [ "$1" = attach ]; then cp -R "$FIXTURE/release/SyncTosh.app" "$mount/SyncTosh.app"; fi
}
fake_plist() { if [ "$MODE" = invalid ]; then echo wrong.bundle; else echo com.synctosh.app; fi; }
fake_ditto() { if [ "$MODE" = copy_failure ]; then return 1; fi; cp -R "$1" "$2"; }
fake_open() { [ "$MODE" != launch_failure ]; }
fake_mv() {
    if [ "$MODE" = swap_failure ] && [[ "$1" = *update.*/SyncTosh.app ]]; then return 1; fi
    /bin/mv "$@"
}
'''
            code = code.replace('set -euo pipefail', 'set -euo pipefail\n' + stubs)
            script = helper / 'update.sh'
            script.write_text(code)
            env = dict(os.environ, FIXTURE=str(root), MODE=mode)
            # A reaped child PID is guaranteed to have belonged to this test, not a real app.
            child = subprocess.Popen(['true'])
            child.wait()
            result = subprocess.run(['bash', str(script), str(root / 'release.dmg'),
                                     str(target), str(child.pid), '0'], env=env,
                                    capture_output=True, text=True, timeout=10)
            self.assertEqual(result.returncode == 0, mode == 'success', result.stderr)
            self.assertEqual((target / 'version').read_text(), 'new' if mode == 'success' else 'old')
            self.assertEqual((data / 'identity.p12').read_text(), 'identity')
            self.assertEqual((data / 'settings.db').read_text(), 'settings')
            self.assertFalse(list(root.glob('.synctosh-*')))

    def test_success_preserves_data(self):
        self.run_update('success')

    def test_rejects_wrong_application(self):
        self.run_update('invalid')

    def test_failed_copy_keeps_old_application(self):
        self.run_update('copy_failure')

    def test_failed_swap_restores_old_application(self):
        self.run_update('swap_failure')

    def test_failed_launch_rolls_back(self):
        self.run_update('launch_failure')


if __name__ == '__main__':
    unittest.main()
