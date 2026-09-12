# Legacy: the .kl key-layout route (abandoned)

This is the approach this project used before `daemon/dpadkeys.c`. It is
dead on the AYN Odin 2 Portal: the built-in D-pad reports as
`ABS_HAT0X`/`ABS_HAT0Y` hat axes, and `.kl` files cannot map axes to keys.
It's kept only for reference and for parts that reused elsewhere.

## Why it can't work here

- The pad's D-pad reports as `ABS_HAT0X`/`ABS_HAT0Y` hat axes, and `.kl`
  files cannot map axes to keys.
- AYN ships `/system/usr/keylayout/Vendor_2020_Product_0112.kl` and the
  device reports keylayout version 0, so a `/data` override is shadowed by
  the stock file and would need a Magisk module on `/system` to win.

## The scripts

- `investigate.sh` - read-only(*) device investigation toolkit for the
  Odin 2 Portal: figures out how the built-in D-pad is reported, which
  `.kl` file Android resolves for it, and whether a reload can be forced
  without Magisk/reboot. `investigate.sh baseline`/`investigate.sh report`
  are still useful standalone for pulling device facts (vendor/product/
  version, resolved `.kl`, D-pad mode, etc.). (*) Everything is read-only
  except logcat/dmesg buffer clears (toggle-test) and the driver
  unbind/bind in reload-test (gated behind `--yes`).
- `probe.sh` - discovers the Odin 2 built-in gamepad's vendor/product id
  and D-pad reporting mode (buttons / arrow-keys / hat-axes / none), and
  writes `device.env` for the other scripts to consume. `--watch` streams
  raw events after identifying the device.
- `gen_kl.sh <profile>` - generates a `.kl` file that remaps the D-pad to
  keyboard keys (`fkeys`: UP->F1 DOWN->F2 LEFT->F3 RIGHT->F4; `wasd`:
  UP->W DOWN->S LEFT->A RIGHT->D), based on the base layout the device is
  already using.
- `install_profile.sh <profile|stock> [--restart|--reboot]` - installs (or
  removes) a version-qualified `.kl` override directly in
  `/data/system/devices/keylayout`, no Magisk required, then forces
  Android to reload it. Why that path: Android's `InputDevice.cpp`
  searches `/odm`, `/vendor`, `/product`, `/system_ext`, `/system` before
  `/data/system/devices`, so a same-named file there can be shadowed by a
  stock file on those partitions. But the loader tries the
  version-qualified name (`Vendor_%04x_Product_%04x_Version_%04x.kl`)
  across all of those directories before it tries the unversioned name in
  any of them, so a version-qualified file dropped in
  `/data/system/devices/keylayout` wins everywhere with no partition
  overlay needed.
- `build_module.sh <profile>` - packages a Magisk module zip that overlays
  `/system/usr/keylayout/Vendor_XXXX_Product_YYYY.kl` with the generated
  D-pad remap. Legacy fallback, only needed if the no-Magisk route above
  fails; the module's overlay only wins if it lands in the same directory
  the stock file actually resolves from (check `investigate.sh
  baseline`/`report`'s `KL_RESOLVED` value).
- `flash.sh <profile> [--reboot]` - pushes the built Magisk module zip to
  the device and installs it with Magisk.
- `switch_profile.sh <profile|stock> [--restart|--reboot]` - thin
  backwards-compatible wrapper around `install_profile.sh` (kept under its
  old name; prefer calling `install_profile.sh` directly in new usage).
