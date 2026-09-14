#!/usr/bin/env bash
# Stop dpadkeys on the device and confirm the virtual keyboard is gone.
set -euo pipefail
cd "$(dirname "$0")/.."; source ./env.sh
adb shell "pkill -x dpadkeys 2>/dev/null; sleep 1; pgrep -x dpadkeys >/dev/null && echo 'still running' || echo 'stopped'; echo \"virtual keyboards present: \$(dumpsys input | grep -ciE "Odin DPad Keys|Device [0-9]+: .* Touch")\""
