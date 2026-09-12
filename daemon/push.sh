#!/bin/bash
# Pushes the built dpadkeys binary to the device and makes it executable.
# Run `source ../env.sh` first so `adb` resolves to the Windows adb.exe shim.
set -euo pipefail

DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

adb push "$DIR/out/dpadkeys" /data/local/tmp/dpadkeys
adb shell chmod 755 /data/local/tmp/dpadkeys
