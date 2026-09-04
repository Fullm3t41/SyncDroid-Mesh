#!/bin/bash
# Runs outside the app bundle so its runtime can be replaced safely.
set -euo pipefail
image=$1
target=$2
ui_pid=$3
worker_pid=$4
work=$(cd "$(dirname "$0")" && pwd)
mount="$work/volume"
stage=""
backup=""
mounted=0
swapped=0
finish() {
    result=$?
    trap - EXIT
    if [ "$result" -ne 0 ]; then
        if [ "$swapped" -eq 1 ] && [ -d "$backup/SyncTosh.app" ] && [ ! -e "$target" ]; then
            /bin/mv "$backup/SyncTosh.app" "$target" || true
        fi
        /usr/bin/osascript - "$work/update.log" <<'APPLESCRIPT' || true
on run arguments
    display alert "SyncTosh update could not finish" message ("Your data has been kept. Reopen SyncTosh and try again. Log: " & item 1 of arguments)
end run
APPLESCRIPT
    fi
    if [ "$mounted" -eq 1 ]; then /usr/bin/hdiutil detach "$mount" -quiet || true; fi
    if [ -n "$stage" ]; then /bin/rm -rf "$stage"; fi
    # Retain a backup if rollback itself failed.
    if [ -n "$backup" ] && [ ! -e "$backup/SyncTosh.app" ]; then /bin/rmdir "$backup" || true; fi
    exit "$result"
}
trap finish EXIT
# Wait for both the UI and the background worker, without killing active syncs.
for ((attempt=0; attempt<480; attempt++)); do
    if ! kill -0 "$ui_pid" 2>/dev/null && { [ "$worker_pid" -eq 0 ] || ! kill -0 "$worker_pid" 2>/dev/null; }; then break; fi
    sleep 0.25
done
! kill -0 "$ui_pid" 2>/dev/null
[ "$worker_pid" -eq 0 ] || ! kill -0 "$worker_pid" 2>/dev/null
mkdir "$mount"
/usr/bin/hdiutil attach "$image" -readonly -nobrowse -mountpoint "$mount" -quiet
mounted=1
source="$mount/SyncTosh.app"
[ "$(/usr/libexec/PlistBuddy -c 'Print :CFBundleIdentifier' "$source/Contents/Info.plist")" = "com.synctosh.app" ]
[ -x "$source/Contents/MacOS/SyncTosh" ]
[ "$(/usr/libexec/PlistBuddy -c 'Print :CFBundleIdentifier' "$target/Contents/Info.plist")" = "com.synctosh.app" ]
stage=$(mktemp -d "$(dirname "$target")/.synctosh-update.XXXXXX")
/usr/bin/ditto "$source" "$stage/SyncTosh.app"
/usr/bin/hdiutil detach "$mount" -quiet
mounted=0
backup=$(mktemp -d "$(dirname "$target")/.synctosh-backup.XXXXXX")
/bin/mv "$target" "$backup/SyncTosh.app"
swapped=1
/bin/mv "$stage/SyncTosh.app" "$target"
if ! /usr/bin/open "$target"; then
    /bin/mv "$target" "$stage/SyncTosh.app"
    exit 1
fi
/bin/rm -rf "$backup/SyncTosh.app"
