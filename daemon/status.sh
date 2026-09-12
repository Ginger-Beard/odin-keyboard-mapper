#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."; source ./env.sh
adb shell "pgrep -x dpadkeys >/dev/null && echo 'dpadkeys: running' || echo 'dpadkeys: not running'; cat /data/local/tmp/dpadkeys.log 2>/dev/null | tail -20"
