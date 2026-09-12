#!/usr/bin/env bash
# build_module.sh <profile> - Package a Magisk module zip that overlays
# /system/usr/keylayout/Vendor_XXXX_Product_YYYY.kl with the generated
# D-pad remap for the given profile.
#
# LEGACY ROUTE: only needed if the no-Magisk route (gen_kl.sh + install_
# profile.sh, which drop a version-qualified .kl straight into
# /data/system/devices/keylayout - no reboot-persistent overlay required)
# fails for some reason. See README.
#
# CAVEAT: Android's InputDevice.cpp searches /odm, /vendor, /product,
# /system_ext, /system (in that order) BEFORE /data/system/devices, so this
# module's overlay only wins if it lands in the SAME directory the stock
# file actually resolves from - check investigate.sh baseline/report's
# KL_RESOLVED value and change the "system/usr/keylayout" path below to
# match (e.g. KL_RESOLVED under /vendor/usr/keylayout means this script's
# build tree needs system/vendor/usr/keylayout instead, since Magisk module
# paths are relative to /).
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" &>/dev/null && pwd)"
# shellcheck source=lib/common.sh
source "$SCRIPT_DIR/lib/common.sh"

PROFILE="${1:-}"
case "$PROFILE" in
  fkeys) KEYS_DESC="F1/F2/F3/F4" ;;
  wasd)  KEYS_DESC="W/A/S/D" ;;
  *) die "Usage: $0 <fkeys|wasd>" ;;
esac

command -v zip >/dev/null 2>&1 || die "'zip' not found on PATH"

[[ -f "$SCRIPT_DIR/device.env" ]] || die "device.env not found. Run ./probe.sh first."
# shellcheck source=/dev/null
source "$SCRIPT_DIR/device.env"
: "${VID:?device.env missing VID}"
: "${PID:?device.env missing PID}"

FILENAME="Vendor_${VID}_Product_${PID}.kl"
SRC_KL="$SCRIPT_DIR/profiles/$PROFILE/$FILENAME"
[[ -f "$SRC_KL" ]] || die "$SRC_KL not found. Run ./gen_kl.sh $PROFILE first."

MODULE_ID="odin2-dpad-kl"
BUILD_DIR="$SCRIPT_DIR/build/$PROFILE"
OUT_DIR="$SCRIPT_DIR/out"
OUT_ZIP="$OUT_DIR/${MODULE_ID}-${PROFILE}.zip"

info "Building module tree in $BUILD_DIR ..."
rm -rf "$BUILD_DIR"
mkdir -p "$BUILD_DIR/system/usr/keylayout" \
         "$BUILD_DIR/META-INF/com/google/android" \
         "$OUT_DIR"

cp "$SRC_KL" "$BUILD_DIR/system/usr/keylayout/$FILENAME"

cat > "$BUILD_DIR/module.prop" <<EOF
id=$MODULE_ID
name=Odin2 DPad KeyLayout ($PROFILE)
version=v1.0
versionCode=1
author=josh
description=Remaps built-in controller D-pad to $KEYS_DESC via key layout file
EOF

cat > "$BUILD_DIR/customize.sh" <<'EOF'
#!/system/bin/sh
# odin2-dpad-kl customize script
set_perm_recursive $MODPATH/system/usr/keylayout 0 0 0755 0644
EOF

# Standard Magisk module update-binary stub (loads util_functions.sh, calls install_module).
cat > "$BUILD_DIR/META-INF/com/google/android/update-binary" <<'EOF'
#!/sbin/sh
#################
# Initialization
#################

umask 022

ui_print() { echo "$1"; }

require_new_magisk() {
  ui_print "*******************************"
  ui_print " Please install Magisk v20.4+! "
  ui_print "*******************************"
  exit 1
}

#########################
# Load util_functions.sh
#########################

OUTFD=$2
ZIPFILE=$3

mount /data 2>/dev/null

[ -f /data/adb/magisk/util_functions.sh ] || require_new_magisk
. /data/adb/magisk/util_functions.sh
[ "$MAGISK_VER_CODE" -lt 20400 ] && require_new_magisk

install_module
exit 0
EOF

echo '#MAGISK' > "$BUILD_DIR/META-INF/com/google/android/updater-script"

info "Zipping $OUT_ZIP ..."
rm -f "$OUT_ZIP"
(
  cd "$BUILD_DIR"
  zip -r -X "$OUT_ZIP" . -x '.*'
)

info "Built $OUT_ZIP"
