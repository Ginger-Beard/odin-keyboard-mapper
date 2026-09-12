#!/usr/bin/env bash
# Start dpadkeys on the device in the background without hanging adb.
# Usage: daemon/start.sh [fkeys|wasd|path/to/config.conf] [--no-grab] [--verbose]
set -euo pipefail
cd "$(dirname "$0")/.."; source ./env.sh
arg="${1:-fkeys}"; shift || true
grab="--grab"; extra=""
for a in "$@"; do case "$a" in --no-grab) grab="";; *) extra="$extra $a";; esac; done

if [[ "$arg" == *.conf ]]; then
    adb push "$arg" /data/local/tmp/dpadkeys.conf >/dev/null
    modeargs="--config /data/local/tmp/dpadkeys.conf"
else
    modeargs="--profile $arg"
fi

# Note: '(nohup ... &)' in a subshell with all three fds redirected is the form
# that lets the adb session close; 'cmd && bg &' backgrounds the whole list.
timeout 25 adb shell "cd /data/local/tmp; (nohup ./dpadkeys $modeargs $grab $extra --pidfile dpadkeys.pid < /dev/null > dpadkeys.log 2>&1 &); sleep 1; cat dpadkeys.log"
