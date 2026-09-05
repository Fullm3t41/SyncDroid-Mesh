#!/usr/bin/env bash
set -euo pipefail
root="$(cd "$(dirname "$0")/.." && pwd)"
if [[ "$(uname -s)" != Linux || "$(uname -m)" != x86_64 ]]; then
  echo 'Build the Steam Deck package on Linux x86_64.' >&2
  exit 1
fi
"$root/gradlew" -p "$root" --no-daemon test createDistributable
image="$root/build/compose/binaries/main/app/SyncDeck"
test -x "$image/bin/SyncDeck"
python3 "$root/installer/smoke-helper.py" "$image/bin/SyncDeck"
mkdir -p "$root/build/release"
cp "$root/installer/install.py" "$(dirname "$image")/install.py"
tar -czf "$root/build/release/SyncDeck-Linux-x64-preview.tar.gz" -C "$(dirname "$image")" SyncDeck install.py

python3 "$root/installer/make-installer.py" "$root/build/release/SyncDeck-Linux-x64-preview.tar.gz" "$root/build/release/SyncDeck-SteamDeck-Preview-1.run"
(cd "$root/build/release" && sha256sum SyncDeck-SteamDeck-Preview-1.run > SHA256SUMS.txt)
