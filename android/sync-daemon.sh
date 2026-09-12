#!/usr/bin/env bash
# Copy the built daemon into the app's jniLibs so it ships inside the APK as
# libdpadkeys.so (extracted, executable-friendly path under nativeLibraryDir).
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
src="$here/../daemon/out/dpadkeys"
dst="$here/app/src/main/jniLibs/arm64-v8a/libdpadkeys.so"
[[ -f "$src" ]] || { echo "missing $src (run daemon/build.sh first)" >&2; exit 1; }
mkdir -p "$(dirname "$dst")"
cp -f "$src" "$dst"
chmod 644 "$dst"
ls -la "$dst"
