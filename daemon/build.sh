#!/usr/bin/env bash
# Cross-compile dpadkeys for the Odin 2 (arm64) as a static musl binary.
# Needs zig (https://ziglang.org/download/, any 0.13+); point ZIG at the binary
# or have it on PATH. No NDK required.
set -euo pipefail
cd "$(dirname "$0")"
ZIG="${ZIG:-$(command -v zig || true)}"
[[ -n "$ZIG" ]] || { echo "zig not found; set ZIG=/path/to/zig" >&2; exit 1; }
mkdir -p out
"$ZIG" cc -target aarch64-linux-musl -static -O2 -Wall -o out/dpadkeys dpadkeys.c
file out/dpadkeys
