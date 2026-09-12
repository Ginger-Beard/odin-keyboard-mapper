# shellcheck shell=bash
# Shared helpers for the odin2-dpad-kl toolkit. Source this file; do not execute it directly.

info()  { echo "[*] $*"; }
warn()  { echo "[!] $*" >&2; }
die()   { echo "[x] $*" >&2; exit 1; }

# Fails unless exactly one adb device is attached and in "device" state.
require_adb_device() {
  command -v adb >/dev/null 2>&1 || die "adb not found on PATH"
  local state
  state="$(adb get-state 2>/dev/null || true)"
  if [[ "$state" != "device" ]]; then
    die "No adb device in 'device' state (got: '${state:-none}'). Connect the Odin 2 with USB debugging enabled and authorize this PC."
  fi
}

# Normalizes a hex string (with or without 0x prefix, any case) to lowercase 4-digit hex.
norm_hex4() {
  local v="${1#0x}"
  v="${v#0X}"
  printf '%04x' "$((16#$v))"
}

# --- root (su) helpers -----------------------------------------------------
# These exist so scripts can degrade gracefully (warn + continue) on a device
# where su/root is unavailable, instead of hard failing.

SU_AVAILABLE=""

# Returns success if `adb shell su -c ...` grants root. Caches the result.
have_su() {
  if [[ -z "$SU_AVAILABLE" ]]; then
    if adb shell su -c id 2>/dev/null | grep -q 'uid=0'; then
      SU_AVAILABLE=1
    else
      SU_AVAILABLE=0
    fi
  fi
  [[ "$SU_AVAILABLE" == "1" ]]
}

# Runs "$*" as root via su. If su is unavailable, warns and returns 1 instead
# of dying, so the caller can skip that piece of data and keep going.
su_run() {
  if ! have_su; then
    warn "su not available; skipping: $*"
    return 1
  fi
  adb shell su -c "$*" 2>/dev/null
}
