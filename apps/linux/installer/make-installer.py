#!/usr/bin/env python3
"""Wrap the tested app image in a checksummed, user-only Steam Deck installer."""
import hashlib
from pathlib import Path
import sys

archive, destination = map(Path, sys.argv[1:])
payload = archive.read_bytes()
header = r'''#!/usr/bin/env bash
set -euo pipefail
fail() {
  printf '%s\n' "$1" >&2
  if command -v kdialog >/dev/null 2>&1; then kdialog --error "$1" || true; fi
  exit 1
}
[[ "$(uname -s)" == Linux && "$(uname -m)" == x86_64 ]] || fail 'This installer requires Linux x86_64 (Steam Deck).'
[[ "$EUID" != 0 ]] || fail 'Run this installer as your desktop user, without sudo.'
command -v python3 >/dev/null 2>&1 || fail 'Python 3 is needed to install SyncDeck.'
work="$(mktemp -d -t syncdeck-install.XXXXXXXX)"
trap 'rm -rf "$work"' EXIT
line="$(awk '/^__SYNCDECK_PAYLOAD__$/ { print NR + 1; exit }' "$0")"
tail -n +"$line" "$0" > "$work/payload.tar.gz"
actual="$(sha256sum "$work/payload.tar.gz")"
[[ "${actual%% *}" == '@SHA256@' ]] || fail 'The installer is incomplete or damaged. Download it again.'
tar -xzf "$work/payload.tar.gz" -C "$work"
if ! python3 "$work/install.py" "$work/SyncDeck" > "$work/install.log" 2>&1; then
  fail "$(cat "$work/install.log")"
fi
cat "$work/install.log"
if command -v kdialog >/dev/null 2>&1; then
  if kdialog --title 'SyncDeck installed' --yesno 'SyncDeck is installed in your application menu. Open it now to pair your devices?'; then
    nohup "$HOME/.local/opt/syncdeck/bin/SyncDeck" >/dev/null 2>&1 </dev/null &
  fi
fi
exit 0
__SYNCDECK_PAYLOAD__
'''.replace('@SHA256@', hashlib.sha256(payload).hexdigest())
destination.write_bytes(header.encode() + payload)
destination.chmod(0o755)
print(destination)
