# shellcheck shell=bash
# lib/reload.sh - shared logic for forcing Android's EventHub to reopen the
# built-in gamepad after a .kl change, by unbinding/rebinding its kernel
# driver (no userspace API exists to make EventHub reload a single device).
#
# Sourced by investigate.sh (reload-test) and install_profile.sh. Requires
# lib/common.sh to already be sourced, and SCRIPT_DIR to be set by the caller.

# reload_device <driver_path> <vid> <pid>
#
# driver_path: the result of `readlink -f /sys/class/input/eventN/device`
#   (as captured in device.env DRIVER_PATH).
# vid/pid: 4-hex-digit lowercase vendor/product id, used to detect that the
#   pad has re-enumerated.
#
# Requires root. Prints progress. On success, sets NEW_EVENT_NODE and
# RELOAD_ELAPSED and returns 0. Returns 1 on failure (su unavailable, driver
# path not resolvable, or the pad did not reappear within the timeout).
reload_device() {
  local driver_path="$1" vid="$2" pid="$3"
  local real_path if_name

  if ! have_su; then
    warn "su not available; cannot unbind/rebind the pad's driver."
    return 1
  fi

  real_path="$(su_run "readlink -f '$driver_path'" | tr -d '\r')"
  if [[ -z "$real_path" ]]; then
    warn "Could not resolve driver path: $driver_path"
    return 1
  fi

  if [[ "$real_path" == *"/usb"* ]]; then
    if_name="$(echo "$real_path" | tr '/' '\n' | grep -E '^[0-9]+-[0-9.]+:[0-9]+\.[0-9]+$' | tail -n1 || true)"
    if [[ -z "$if_name" ]]; then
      warn "Could not derive a USB interface name (bus-port:config.interface) from $real_path"
      return 1
    fi
    warn "Unbinding/rebinding USB interface $if_name - the pad will drop for ~1s."
    su_run "echo '$if_name' > /sys/bus/usb/drivers/usbhid/unbind" >/dev/null || true
    sleep 0.5
    su_run "echo '$if_name' > /sys/bus/usb/drivers/usbhid/bind" >/dev/null || true
  else
    local drv_dir base
    base="$(basename "$real_path")"
    drv_dir="$(su_run "readlink -f '$real_path/driver'" | tr -d '\r')"
    if [[ -z "$drv_dir" ]]; then
      warn "Could not resolve platform/i2c driver directory for $real_path"
      return 1
    fi
    info "Platform/i2c driver directory: $drv_dir"
    warn "Unbinding/rebinding $base on driver $(basename "$drv_dir") - the pad will drop for ~1s."
    su_run "echo '$base' > '$drv_dir/unbind'" >/dev/null || true
    sleep 0.5
    su_run "echo '$base' > '$drv_dir/bind'" >/dev/null || true
  fi

  info "Polling for the pad to reappear (up to 5s) ..."
  local start end node=""
  start="$(date +%s.%N)"
  for _ in $(seq 1 25); do
    node="$(adb shell getevent -i 2>/dev/null | tr -d '\r' \
      | awk -f "$SCRIPT_DIR/lib/parse_getevent.awk" \
      | grep -E "VENDOR:${vid}\|PRODUCT:${pid}" || true)"
    [[ -n "$node" ]] && break
    sleep 0.2
  done
  end="$(date +%s.%N)"
  RELOAD_ELAPSED="$(awk -v s="$start" -v e="$end" 'BEGIN{printf "%.1f", e-s}')"

  if [[ -z "$node" ]]; then
    warn "Pad did not reappear within 5s."
    return 1
  fi

  NEW_EVENT_NODE="$(echo "$node" | sed -n 's/.*EVENT:\([^|]*\).*/\1/p')"
  info "Pad reappeared after ${RELOAD_ELAPSED}s at $NEW_EVENT_NODE"
  return 0
}

# system_server_restart - reload by restarting system_server. Closes all apps.
system_server_restart() {
  if ! have_su; then
    die "su not available; cannot restart system_server. Use --reboot instead."
  fi
  warn "Restarting system_server (adb shell su -c 'stop && start'). This CLOSES ALL APPS."
  adb shell su -c 'stop && start'
  info "system_server restart issued; give the device ~20s to come back."
}

# reboot_device - reload by rebooting.
reboot_device() {
  info "Rebooting device ..."
  adb reboot
}
