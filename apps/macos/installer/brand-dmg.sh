#!/bin/bash
set -euo pipefail
image=$1
icon=$2
work=$(mktemp -d)
mounted=0
cleanup() {
    if [ "$mounted" -eq 1 ]; then hdiutil detach "$work/volume" -quiet || true; fi
    rm -rf "$work"
}
trap cleanup EXIT
hdiutil convert "$image" -format UDRW -o "$work/writable.dmg" -quiet
mkdir "$work/volume"
hdiutil attach "$work/writable.dmg" -owners off -nobrowse -mountpoint "$work/volume" -quiet
mounted=1
chmod u+w "$work/volume"
if [ -e "$work/volume/.VolumeIcon.icns" ]; then chmod u+w "$work/volume/.VolumeIcon.icns"; fi
cp "$icon" "$work/volume/.VolumeIcon.icns"
xcrun SetFile -a C "$work/volume"
hdiutil detach "$work/volume" -quiet
mounted=0
hdiutil convert "$work/writable.dmg" -format UDZO -o "$work/branded.dmg" -quiet
mv "$work/branded.dmg" "$image"
