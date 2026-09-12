#!/usr/bin/env bash
# flash.sh <profile> [--reboot] - Push the built Magisk module zip to the
# device and install it with Magisk.
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" &>/dev/null && pwd)"
# shellcheck source=lib/common.sh
source "$SCRIPT_DIR/lib/common.sh"

PROFILE=""
REBOOT=0
for arg in "$@"; do
  case "$arg" in
    --reboot) REBOOT=1 ;;
    fkeys|wasd) PROFILE="$arg" ;;
    *) die "Unknown argument: $arg" ;;
  esac
done
[[ -n "$PROFILE" ]] || die "Usage: $0 <fkeys|wasd> [--reboot]"

MODULE_ID="odin2-dpad-kl"
ZIP="$SCRIPT_DIR/out/${MODULE_ID}-${PROFILE}.zip"
[[ -f "$ZIP" ]] || die "$ZIP not found. Run ./build_module.sh $PROFILE first."

require_adb_device

info "Pushing $ZIP to /sdcard/ ..."
adb push "$ZIP" /sdcard/

REMOTE_ZIP="/sdcard/$(basename "$ZIP")"
info "Installing module with Magisk ..."
adb shell su -c "magisk --install-module $REMOTE_ZIP"

info "Module installed."
if [[ "$REBOOT" -eq 1 ]]; then
  info "Rebooting device now (--reboot given) ..."
  adb reboot
else
  warn "A reboot is required for the new key layout to take effect."
  warn "Reboot manually, or re-run with --reboot."
fi
