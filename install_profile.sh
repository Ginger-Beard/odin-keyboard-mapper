#!/usr/bin/env bash
# install_profile.sh <profile|stock> [--restart|--reboot] - Install (or
# remove) a version-qualified .kl override directly in
# /data/system/devices/keylayout, no Magisk required, then force Android to
# reload it.
#
# Why /data/system/devices/keylayout: Android's InputDevice.cpp searches
# /odm, /vendor, /product, /system_ext, /system BEFORE /data/system/devices,
# so a same-named file there can be shadowed by a stock file on those
# partitions. BUT the loader tries the VERSION-qualified name
# (Vendor_%04x_Product_%04x_Version_%04x.kl) across ALL of those directories
# before it tries the unversioned name in ANY of them - so a version-
# qualified file dropped in /data/system/devices/keylayout wins everywhere,
# with no partition overlay needed. See gen_kl.sh for the filename logic.
#
# Reload has no userspace trigger; by default this rebinds the pad's kernel
# driver (same logic as investigate.sh reload-test, factored into
# lib/reload.sh). --restart instead restarts system_server (closes all
# apps); --reboot reboots the device.
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" &>/dev/null && pwd)"
# shellcheck source=lib/common.sh
source "$SCRIPT_DIR/lib/common.sh"
# shellcheck source=lib/reload.sh
source "$SCRIPT_DIR/lib/reload.sh"

TARGET=""
RELOAD_MODE="rebind"
for arg in "$@"; do
  case "$arg" in
    --restart) RELOAD_MODE="restart" ;;
    --reboot)  RELOAD_MODE="reboot" ;;
    fkeys|wasd|stock) TARGET="$arg" ;;
    *) die "Unknown argument: $arg" ;;
  esac
done
[[ -n "$TARGET" ]] || die "Usage: $0 <fkeys|wasd|stock> [--restart|--reboot]"

REMOTE_DIR="/data/system/devices/keylayout"

require_adb_device
have_su || die "su not available on this device - install_profile.sh requires root to write $REMOTE_DIR. See README's legacy Magisk route (build_module.sh/flash.sh) if root truly isn't available."

[[ -f "$SCRIPT_DIR/device.env" ]] || die "device.env not found. Run ./investigate.sh baseline (or ./probe.sh) first."
# shellcheck source=/dev/null
source "$SCRIPT_DIR/device.env"
: "${VID:?device.env missing VID}"
: "${PID:?device.env missing PID}"
VERSION="${VERSION:-0000}"

FILENAME="Vendor_${VID}_Product_${PID}.kl"
VERSION_LC="${VERSION,,}"
if [[ -n "$VERSION_LC" && "$VERSION_LC" != "0000" ]]; then
  OUT_FILENAME="Vendor_${VID}_Product_${PID}_Version_${VERSION_LC}.kl"
else
  OUT_FILENAME="$FILENAME"
fi

if [[ "$TARGET" == "stock" ]]; then
  info "Removing any installed override(s) for Vendor_${VID}_Product_${PID}* from $REMOTE_DIR ..."
  su_run "rm -f '$REMOTE_DIR/$FILENAME' $REMOTE_DIR/Vendor_${VID}_Product_${PID}_Version_*.kl" \
    || warn "Removal command reported an error (files may already be absent)."
else
  LOCAL_KL="$SCRIPT_DIR/profiles/$TARGET/$OUT_FILENAME"
  [[ -f "$LOCAL_KL" ]] || die "$LOCAL_KL not found. Run ./gen_kl.sh $TARGET first."

  info "Pushing $LOCAL_KL to /sdcard/ ..."
  adb push "$LOCAL_KL" "/sdcard/$OUT_FILENAME" >/dev/null

  info "Installing to $REMOTE_DIR/$OUT_FILENAME (root) ..."
  su_run "mkdir -p '$REMOTE_DIR' && cp '/sdcard/$OUT_FILENAME' '$REMOTE_DIR/$OUT_FILENAME' && chown system:system '$REMOTE_DIR/$OUT_FILENAME' && chmod 644 '$REMOTE_DIR/$OUT_FILENAME'" \
    || die "Failed to install $OUT_FILENAME to $REMOTE_DIR."
  su_run "command -v restorecon >/dev/null 2>&1 && restorecon -v '$REMOTE_DIR/$OUT_FILENAME' || true" \
    || warn "restorecon not available or failed; continuing (this is only needed on strict SELinux configs)."

  info "Installed profile: $TARGET -> $REMOTE_DIR/$OUT_FILENAME"
fi

case "$RELOAD_MODE" in
  rebind)
    : "${DRIVER_PATH:?device.env missing DRIVER_PATH - re-run investigate.sh baseline (with su available) to capture it, or use --restart/--reboot instead}"
    if reload_device "$DRIVER_PATH" "$VID" "$PID"; then
      info "Reload succeeded in ${RELOAD_ELAPSED}s; new event node: $NEW_EVENT_NODE"
    else
      warn "Driver rebind reload failed; falling back is not automatic - re-run with --restart or --reboot."
      exit 1
    fi
    ;;
  restart)
    system_server_restart
    ;;
  reboot)
    reboot_device
    ;;
esac

if [[ "$RELOAD_MODE" != "reboot" ]]; then
  info "Checking resolved KeyLayoutFile via dumpsys input ..."
  sleep 1
  BLOCK="$(adb shell dumpsys input 2>/dev/null | tr -d '\r' | grep -A30 -iF "${DEV_NAME:-}" || true)"
  KL="$(echo "$BLOCK" | grep -i 'KeyLayoutFile' | head -n1)"
  if [[ -n "$KL" ]]; then
    info "$KL"
  else
    warn "Could not confirm the resolved KeyLayoutFile (device may still be settling). Re-check with: ./investigate.sh report"
  fi
else
  info "Device is rebooting - after it comes back, confirm with: ./investigate.sh report"
fi
