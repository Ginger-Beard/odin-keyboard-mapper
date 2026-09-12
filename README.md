# odin2-dpad-kl

Turn the AYN Odin 2 Portal's built-in D-pad into real keyboard keystrokes
(F1-F4 for OSRS mobile, WASD for emulators) on Android 13, with no touch
injection, no accessibility service, and no root.

## Status (2026-09-11): working

The `daemon/` route below is the one that works. The key-layout (`.kl`)
route elsewhere in this repo is **dead** on the Odin 2 Portal and kept only
for reference. Two reasons it can't work here:

- The pad's D-pad reports as `ABS_HAT0X`/`ABS_HAT0Y` hat axes, and `.kl`
  files cannot map axes to keys.
- AYN ships `/system/usr/keylayout/Vendor_2020_Product_0112.kl` and the
  device reports keylayout version 0, so a `/data` override is shadowed by
  the stock file and would need a Magisk module on `/system` to win.

## How it works

The built-in controller is a *virtual* evdev device created by AYN's
firmware (vendor `0x2020`, product `0x0112`, named "Xbox Wireless
Controller" when Controller Style is set to Xbox). It's destroyed and
recreated whenever Controller Style changes. `dpadkeys` (`daemon/dpadkeys.c`)
opens that device, grabs it exclusively (`EVIOCGRAB`) so none of its events
reach any app, and emits F1-F4 or WASD from a `uinput` virtual keyboard
named "Odin DPad Keys". Android sees that keyboard as an ordinary
non-alphabetic keyboard using the stock `Generic.kl` - no custom layout
file needed.

It runs as the plain `adb shell` user, not root: on this firmware that user
is in the `input` and `uhid` groups, and `/dev/input/event*` and
`/dev/uinput` are world-writable.

## Important gotcha: per-app Controller Style

AYN lets you set a Controller Style per app from the slide-out panel. If
the target app's style is "None"/"Disconnect", AYN replaces the gamepad
with a buttonless "None Controller" device and the daemon sees nothing
("waiting for Odin controller device..."). Set the app's style to **Xbox**.
Also keep AYN's touch key mapping OFF for OSRS.

## Setup (from WSL)

```
source ./env.sh   # puts bin/adb (a Windows adb.exe shim) on PATH
```

Edit `ADB_EXE` in `bin/adb` if your `adb.exe` lives somewhere other than
the default Android SDK platform-tools path. The device must already be
authorized for USB debugging (`adb devices` shows `device`, not
`unauthorized`).

Build (needs [zig](https://ziglang.org/download/) 0.13+, no NDK):

```
ZIG=/path/to/zig daemon/build.sh
```

Push it to the device:

```
daemon/push.sh
# or manually: adb push daemon/out/dpadkeys /data/local/tmp/ && adb shell chmod 755 /data/local/tmp/dpadkeys
```

## Use

```
daemon/start.sh fkeys    # or: daemon/start.sh wasd
daemon/status.sh
daemon/stop.sh
```

`start.sh` accepts `--no-grab` (leave the pad ungrabbed, for testing) and
`--verbose`. While running, **all** pad input is swallowed except the D-pad
translation, so stop the daemon before playing anything else that wants the
gamepad. A second `start.sh` refuses to run if one is already active.
`SIGTERM` (`stop.sh`) cleans up and removes the virtual keyboard. Logs go
to `/data/local/tmp/dpadkeys.log`.

## Learn-by-press and other devices

Profiles are stored as *pad control -> key* bindings, and the app's profile
editor is key-first: it lists the keys a game needs (F1-F12, digits,
arrows, Enter/Space/Esc, wheel, letters) and each key has a **Bind...**
button that runs `dpadkeys --learn` and records whatever you press. The
daemon no longer cares about the vendor id: any evdev node with `BTN_SOUTH`
is a candidate pad, so the same app and profiles work on a Retroid Pocket 5,
an AYN Thor, or a plugged-in USB/Bluetooth pad.

What transfers between pads:

- Semantic sources (`hat.up`, `btn.south`, `btn.tl`, `ls.up`, `lt`, ...)
  follow the Linux gamepad conventions, so a profile built from them on the
  Odin behaves the same on any pad that reports standard codes. The
  built-in OSRS/WASD profiles only use these.
- Learn reports the semantic name whenever one exists.

What doesn't:

- Controls that have no standard code are stored as raw sources:
  `key.0xNNN` (any EV_KEY code, e.g. `key.0x2c4` for an odd back button) or
  `abs.0xNN.neg` / `abs.0xNN.pos` (any EV_ABS axis, thresholded with the
  same deadzone as the triggers; a -1..1 hat-style axis fires at +-1).
  Those codes are device-specific - rebind them on the new pad.
- `btn.m1` / `btn.m2` are the Odin 2's back buttons (`BTN_C`/`BTN_Z`);
  other pads may expose their back buttons under different codes.

To add a device: run the app on it with Shizuku, open the profile, and
press **Bind...** next to each key; press the control on the pad. The
Status tab shows the detected pad name and `vendor:product`. Binding stops
a running daemon first (learn needs the pad ungrabbed). A raw source and
its semantic alias (`key.0x130` and `btn.south`) cannot both be mapped in
one profile - the daemon rejects that config.

Hand-written configs may add `device.match <substring-of-name>` or
`device.match vvvv:pppp` (hex) to prefer one pad when several are present;
otherwise the first gamepad found is used. `dpadkeys --list` shows the
candidates and `dpadkeys --learn [--learn-timeout-ms N]` prints
`learned <source>` (or `learned NONE`, exit 3, on timeout).

## Why this is low-risk for OSRS

- 1:1 mapping: one D-pad press produces exactly one keystroke.
- Events come from a real kernel input device with a real device id - no
  injection API, no accessibility service.
- Nothing touches screen coordinates.

## Next step

Per-app automation, so the daemon doesn't need to be started/stopped by
hand. Two options under consideration:

- A small app using Shizuku that starts/stops the daemon when the
  foreground app changes.
- Keying the daemon's own behavior off AYN's per-app Controller Style,
  grabbing only the Xbox-style device identity while leaving Odin style
  pads alone elsewhere.

## Legacy (.kl route, abandoned)

`investigate.sh`, `probe.sh`, `gen_kl.sh`, `install_profile.sh`,
`build_module.sh`, `flash.sh`, and `switch_profile.sh` implement the
abandoned key-layout-file approach (see Status above for why it can't
work). `investigate.sh baseline`/`investigate.sh report` are still useful
standalone for pulling device facts (vendor/product/version, resolved
`.kl`, D-pad mode, etc.).
