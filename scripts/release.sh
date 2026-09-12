#!/usr/bin/env bash
# Build the daemon, sync it into the app, build a release APK, and stage it
# under dist/. Signs the APK if KEYSTORE_FILE/KEYSTORE_PASSWORD/KEY_ALIAS/
# KEY_PASSWORD are set (see docs/RELEASING.md); otherwise the APK is built
# unsigned.
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
root="$(cd "$here/.." && pwd)"

APP_NAME="${APP_NAME:-odin-keyboard-mapper}"
VERSION_NAME="${VERSION_NAME:-0.0.0-dev}"
ANDROID_HOME="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"

ZIG="${ZIG:-$(command -v zig || true)}"
[[ -n "$ZIG" ]] || { echo "zig not found; set ZIG=/path/to/zig" >&2; exit 1; }

echo "==> building daemon"
ZIG="$ZIG" "$root/daemon/build.sh"

echo "==> syncing daemon into jniLibs"
"$root/android/sync-daemon.sh"

echo "==> assembling release APK (versionName=$VERSION_NAME)"
export VERSION_NAME
(cd "$root/android" && ./gradlew assembleRelease)

apk_src=$(find "$root/android/app/build/outputs/apk/release" -maxdepth 1 -name "*.apk" | head -n1)
[[ -n "$apk_src" ]] || { echo "no APK produced" >&2; exit 1; }

mkdir -p "$root/dist"
apk_dst="$root/dist/${APP_NAME}-${VERSION_NAME}.apk"
cp -f "$apk_src" "$apk_dst"
echo "==> APK staged at $apk_dst"

apksigner=""
if [[ -n "$ANDROID_HOME" ]]; then
    apksigner="$ANDROID_HOME/build-tools/34.0.0/apksigner"
fi

if [[ -n "$apksigner" && -x "$apksigner" ]]; then
    echo "==> checking signature"
    if "$apksigner" verify --print-certs "$apk_dst" 2>/dev/null; then
        echo "==> APK is SIGNED"
    else
        echo "==> APK is UNSIGNED"
    fi
else
    echo "==> apksigner not found (looked at ${apksigner:-\$ANDROID_HOME/build-tools/34.0.0/apksigner}); skipping signature check"
fi
