# Odin DPad Keys

Turns a handheld's built-in gamepad into real keyboard keystrokes, per app,
with no root, no touch injection, and no accessibility service. Built for
OSRS mobile on the AYN Odin 2 Portal. Works with any Android gamepad
exposed as an evdev device — bind new controls by pressing them. The
Retroid Pocket and AYN Thor are intended targets too, but untested.

## How it works

- A daemon (`dpadkeys`) opens the gamepad's kernel input device and grabs
  it exclusively, so its raw input never reaches any app.
- It emits keystrokes (and mouse-wheel events) from a virtual keyboard
  device, translated from the pad according to a per-app profile.
- The Android app assigns a profile to each app and starts/stops the
  daemon automatically when the foreground app changes.
- Shizuku provides the shell rights the daemon needs to run, without
  rooting the device.
- An optional touch offset (for stylus play) adds a second virtual
  touchscreen with a fixed pixel offset; the mouse pointer is hidden while
  scrolling.

## Install

1. Download the APK from the [Releases](../../releases) page and install
   it (allow installs from this source when prompted).
2. Open the app. If it has no shell access yet, a Setup card walks you
   through the steps below; you can also do them manually:
   - Install Shizuku from the Play Store
     (`moe.shizuku.privileged.api`) and open it.
   - Turn on Developer options and, inside it, Wireless debugging
     (Settings -> System -> Developer options -> Wireless debugging — tap
     the row, not just the switch).
   - In Shizuku, tap "Start via Wireless debugging", follow its pairing
     steps (you'll enter a pairing code shown on the Wireless debugging
     screen), then tap Start.
   - Back in this app, grant the Shizuku permission when asked (or tap
     "Grant access" / "Re-check" on the Status tab).
3. Android 11+ is required to start Shizuku via Wireless debugging
   without a PC. After a reboot, Wireless debugging turns itself off on
   most devices — turn it back on and tap Start in Shizuku again.

## Use

- **Apps tab** — assign a profile to each app you want remapped. The
  daemon starts and stops automatically as you switch apps.
- **Profiles tab** — a key-first editor: pick the keys a game needs and
  tap **Bind...** to assign the pad control that should send that key by
  pressing it on the pad. A profile can designate one control as the
  modifier button; controls bound under "While modifier held" only fire
  while it's held. Sticks support inversion and a deadzone; the mouse
  wheel has a configurable repeat rate.
- **Status tab** — shows privilege source (Shizuku/root/none), the
  detected pad, and whether the daemon is running.
- **Stylus offset calibration** — from a profile, calibrate a fixed pixel
  offset for stylus play: tap five targets, verify the offset feels
  right, then tap **Keep** within 20 seconds or it reverts automatically.
- **Safety** — press the Home button, or hold both back buttons for 1
  second while inside the game, to disable an active touch offset
  immediately. The app's own screens are never offset, only the target
  game.

## Device notes

**AYN Odin 2**: in AYN's slide-out panel, set Controller Style to Xbox
for the game you're remapping — None/Disconnect removes the pad entirely
and the daemon will see nothing. The back buttons M1/M2 are supported.

**Other devices**: bind every control by pressing it; nothing is
hardcoded to one pad's layout. Please report quirks (unusual button
codes, axes that don't show up, etc).

## OSRS and the rules

This is a 1:1 mapping: one pad press produces exactly one keystroke, with
no macros, no auto-repeat beyond what a held key naturally does, and no
game-state awareness. Jagex's rules permit input remapping, including
mapping controller input to keyboard shortcuts. This project deliberately
does not synthesize touches or otherwise inject input into the game
surface — a previous project of the author's that did synthesize touches
resulted in a ban. No warranty of any kind; use at your own risk.

Wheel (scroll) bindings carry a fixed pointer position; see the in-app
warning; not recommended for OSRS.

## Developer

**Layout**

- `daemon/` — the C daemon (`dpadkeys.c`) and its build/push/start
  scripts.
- `android/` — the Android app (Kotlin, Jetpack Compose).
- `scripts/` — release and packaging tooling.
- `docs/` — reference docs, including this file's legacy history.

**Build**

```
ZIG=/path/to/zig daemon/build.sh   # cross-compiles dpadkeys, needs zig 0.13+, no NDK
android/sync-daemon.sh             # copies the built binary into the app's assets
cd android && ./gradlew assembleDebug
```

**Config format** (`daemon/configs/*.conf`, one `source key` pair per
line):

- Semantic sources: `hat.up/down/left/right`, `btn.south/east/north/west`,
  `btn.tl/tr/tl2/tr2`, `btn.select/start/mode/thumbl/thumbr`, `btn.m1`
  (Odin 2 back button), `btn.m2`, `ls.up/down/left/right`,
  `rs.up/down/left/right`, `lt`, `rt`.
- Raw sources for controls with no standard code: `key.0xNNN` (any
  `EV_KEY` code) and `abs.0xNN.neg` / `abs.0xNN.pos` (any `EV_ABS` axis,
  thresholded like a trigger).
- Targets: a `KEY_*` name, `NONE` (explicit swallow), `WHEEL_UP` /
  `WHEEL_DOWN`, or `MOD` (marks a source as the modifier).
- `deadzone <0..1>` — fraction of half-range that counts as pressed.
- `ls.invert_y 0|1`, `rs.invert_y 0|1` — flip a stick's up/down.
- `mod+<source> <target>` — binding that only applies while the modifier
  is held.
- `wheel_repeat_ms <n>` — repeat interval for a held wheel target.
- `touch.offset <dx> <dy>` — enables the virtual touchscreen with this
  pixel offset.
- `device.match <substring-of-name>` or `device.match vvvv:pppp` (hex) —
  prefer one pad when several are present.

**CLI flags** (`dpadkeys --help` prints the full usage):

```
--config FILE | --profile fkeys|wasd
--grab                    grab the pad exclusively (omit for testing)
--list                    list candidate pad devices and exit
--learn [--learn-timeout-ms N]   print the first control pressed, for bind-by-press
--dump                    print every EV_KEY/EV_ABS event from all evdev nodes
--print-config             print the effective config and exit
--panic-chord none|m1+m2  chord that disables an active touch offset
--device auto|/dev/input/eventN
--device-name NAME        name the virtual devices (default "Odin DPad Keys"; touch clone is "NAME Touch")
--verbose
--pidfile PATH
```

**Tools**: `daemon/*.sh` (`build.sh`, `push.sh`, `start.sh`, `status.sh`,
`stop.sh`) drive the daemon over adb for local testing.

See [`docs/RELEASING.md`](docs/RELEASING.md) for cutting a release, and
[`docs/legacy-kl-route.md`](docs/legacy-kl-route.md) for the abandoned
`.kl`-file approach this project used before `dpadkeys`.
