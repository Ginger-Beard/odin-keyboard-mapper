# Odin DPad Keys

Turns a handheld's built-in gamepad into real keyboard keystrokes, per app,
with no root, no touch injection, and no accessibility service. Built for
OSRS mobile on the AYN Odin 2 Portal and also tested on the Retroid Pocket
5. Works with any Android gamepad exposed as an evdev device — bind
controls by pressing them, nothing is hardcoded to one layout.

## How it works

- A daemon (`dpadkeys`) opens the gamepad's kernel input device and grabs
  it exclusively while a profile is active, so its raw input never reaches
  the game.
- It emits keystrokes (or controller buttons, or mouse-wheel events) from
  a virtual device, translated from the pad according to a per-app
  profile.
- One daemon stays running for as long as the app holds its privilege.
  The Android app assigns a profile to each app and switches the daemon
  between profiles (or idle) as the foreground app changes, so no input
  device appears or disappears on each game launch.
- Shizuku provides the shell rights the daemon needs to run, without
  rooting the device.
- An optional touch offset (for stylus play) adds a cloned touchscreen
  with a fixed pixel offset. While a profile with wheel bindings is
  active the mouse pointer is kept hidden.

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
  mapping switches on and off automatically as you switch apps.
- **Profiles tab** — a key-first editor: every keyboard key and standard
  controller button is listed, so pick the ones a game needs and tap
  **Bind…** to assign the pad control that should send it by pressing that
  control. Keys with nothing bound are collapsed under **Unassigned**.
  To bind a combo, hold one button while pressing the control during
  Bind; the binding then only fires while that button is held. Binding a
  control that is already in use asks before moving it. Sticks map to
  four directions (diagonals press two keys), with a deadzone and
  horizontal/vertical inversion; the mouse wheel has a repeat rate.
  Any pad control with no binding is swallowed while the profile is
  active, so nothing leaks into the game.
- **Panic chord** — each profile can set a button or combination to stop
  the mapping from inside a game: hold it 1 second to pause, 4 seconds to
  restart the daemon. There is no default; set it by pressing. A panic
  chord is required before the stylus offset can be turned on.
- **Stylus offset calibration** — from a profile, put the stylus down on
  the target dot, slide until the crosshair sits on the dot, and lift;
  repeat at more positions if you like. The verify step then runs the
  offset live on a grid of targets: tap **Keep** within 45 seconds or it
  reverts automatically, as it does if you leave the screen.
- **Status tab** — shows Ready / Mapping / Needs setup, walks through
  setup when access is missing, and has a Keep alive card (battery
  exemption, the Q-key switch) plus an Advanced section for the raw
  daemon state, a 30 second profile test and a daemon restart.
- **The Q key** is greyed out by default: binding it makes Android treat
  the virtual keyboard as a full keyboard and hide the on-screen keyboard
  everywhere. The **Allow…** link on the key explains this and turns it
  on if you want it anyway.
- **Safety** — press Home to leave the game and everything is released
  immediately; the panic chord does the same from inside it. The app's
  own screens are never offset, only the target game.

## Device notes

**AYN Odin 2**: in AYN's slide-out panel, set Controller Style to Xbox
for the game you're remapping — None/Disconnect removes the pad entirely
and the daemon will see nothing. The back buttons M1/M2 are supported.

**Retroid Pocket 5**: works as-is; the pad shows up as "Retroid Pocket
Controller".

**Other devices**: bind every control by pressing it; nothing is
hardcoded to one pad's layout. Controls with no standard code are still
bindable (they show as raw key/axis codes). Please report quirks
(unusual button codes, axes that don't show up, etc).

**Button letters**: the editor labels face buttons A/B/X/Y by the
letter printed on your pad, not by position. Bind by pressing and the
letters do not matter.

## OSRS and the rules

This is a 1:1 mapping: one pad press produces exactly one keystroke, with
no macros, no auto-repeat beyond what a held key naturally does, and no
game-state awareness. Jagex's rules permit input remapping, including
mapping controller input to keyboard shortcuts. This project deliberately
does not synthesize touches or otherwise inject input into the game
surface — a previous project of the author's that did synthesize touches
resulted in a ban. No warranty of any kind; use at your own risk.

Wheel (scroll) bindings reach the game as mouse scroll events, which
Android attaches to a pointer position; the editor marks them with a
ban-risk warning and they are not recommended for OSRS. Keyboard and
controller-button bindings carry no position.

## For the security-minded

This section states plainly what the app needs, why, and what it records.

### Why it needs Shizuku (or root)

Everything the mapping daemon does sits below the line Android draws for normal apps.
A normal app only ever receives input aimed at its own windows. This app has to work
underneath the whole system, which needs the rights of the `shell` user (what `adb`
runs as). Shizuku lends those rights to the app by starting one helper process through
adb's own credentials; root would do the same. Concretely, the shell user is required to:

- read the gamepad and touchscreen event nodes under `/dev/input` (blocked for app
  processes by SELinux policy regardless of file permissions);
- take an exclusive grab on the pad (and, when a stylus offset is enabled, the panel)
  so the game does not also see the original events;
- create virtual input devices through `/dev/uinput` (reserved for the `uhid` group);
  this is what makes the mapped keystrokes real hardware events rather than injected ones;
- learn which app is in the foreground by tailing the system's activity event log, which
  costs no usage-stats permission and is not visible to other apps;
- hide the mouse pointer while a profile uses scroll-wheel targets (a hidden system call,
  re-applied periodically only while such a profile is active);
- start, signal and stop the daemon, which runs as that same user.

### What cannot work without it

- **Accessibility-service mappers** can intercept key events and inject events into other
  apps, but they cannot grab the pad (the game keeps seeing the original buttons), cannot
  see analog sticks or hat D-pads at all, and their injected events carry the system's
  virtual-keyboard device id. An enabled accessibility service is also visible to every
  app on the device. This app deliberately has no accessibility service.
- **Input-method (keyboard app) tricks** only work while a text field has focus, which a
  fullscreen game never has.
- With no privilege at all the app can do nothing: it will show "Needs setup" and wait.

### Android permissions the app declares

| Permission | Why |
|---|---|
| `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_SPECIAL_USE` | the always-on service that watches for assigned apps |
| `RECEIVE_BOOT_COMPLETED` | start the service after a reboot |
| `POST_NOTIFICATIONS` | the persistent "active" notification and the panic-chord notice |
| `QUERY_ALL_PACKAGES` | list installed apps so you can assign profiles to them |
| `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` | offer the "run in background" exemption |

No network permission of any kind is declared. Nothing can leave the device, and there is
no analytics or crash-reporting library in the build.

### What is logged, where, and for how long

**During play: no input is recorded.** The daemon has a per-event logging mode, but it is
behind a `--verbose` flag that the app never passes; it can only be turned on by starting the
daemon by hand from a shell.

Files the daemon writes, all under `/data/local/tmp/` (readable by any shell/adb user on the
device, not by other apps):

- `dpadkeys.log` — overwritten every time the daemon starts. Contains the startup banner
  (which devices it found), state transitions (idle/active), grab and release messages,
  config errors, and panic-chord events. Never key presses, coordinates or contacts unless
  `--verbose` was given manually.
- `dpadkeys.status` — one line of counters: state, whether touch is on, how many keys are
  mapped, the panic count, live contact count, what it is waiting for. Rewritten on change,
  and the app is notified of the change by the file system rather than by polling.
- `dpadkeys.conf` — the currently active profile (your bindings and offset), rewritten on
  every profile switch. It describes your configuration, not your input.

Lines the app writes to Android's logcat (tag `DpadMgr`; a volatile ring buffer, readable only
over adb or by root, gone on reboot):

- service and privilege state, and the package name of the app that came to the foreground
  each time it changes (this is how it decides which profile to activate);
- during **Bind**, the name of the control you pressed (the daemon is idled first, so the
  pad is ungrabbed while you press);
- during **Calibrate**, the drag deltas and target positions it measured.

The last two exist for diagnosing a bad binding or calibration and only run while you are on
those screens. Nothing is written while a game is in front.

### Scope and escape hatches

- The daemon only grabs the pad (and panel) while an app you assigned is in front. Everywhere
  else the controller and touchscreen are untouched, including inside this app. The virtual
  keyboard device itself stays attached for as long as the service runs, so that switching
  profiles never makes a device appear or disappear; it emits nothing while idle.
- Leaving the game (Home) releases everything immediately. The per-profile panic chord (none
  by default; you choose it by pressing) pauses the mapping from inside the game after one
  second and rebuilds the virtual devices after four.
- To see for yourself: `adb shell cat /data/local/tmp/dpadkeys.log`, `adb logcat -s DpadMgr:V`,
  and `adb shell dumpsys package com.dpad.mgr | grep permission`.

## Developer

**Layout**

- `daemon/` — the C daemon (`dpadkeys.c`) and its build/push/start
  scripts.
- `android/` — the Android app (Kotlin, Jetpack Compose).
- `scripts/` — release and packaging tooling.
- `docs/` — release procedure and the history of the abandoned `.kl` route.

**Build**

```
ZIG=/path/to/zig daemon/build.sh   # cross-compiles dpadkeys, needs zig 0.13+, no NDK
android/sync-daemon.sh             # copies the built binary into the app's jniLibs
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
- Chords: `<hold>+<source> <target>` — applies only while `<hold>` (any
  button-like source) is held. A source used as a hold always swallows
  its own press.
- Targets: a `KEY_*` name, `NONE` (explicit swallow), `WHEEL_UP` /
  `WHEEL_DOWN`, or a standard controller-button name --
  `BTN_SOUTH/EAST/NORTH/WEST`, `BTN_TL/TR/TL2/TR2`,
  `BTN_SELECT/START/MODE/THUMBL/THUMBR`, `BTN_DPAD_UP/DOWN/LEFT/RIGHT` --
  emitted as a real button press on the virtual device (Android then
  sees it as a GAMEPAD source too). `KEY_Q` is ignored in `--serve`
  unless `--allow-q` was given.
- `deadzone <0..1>` — fraction of half-range that counts as pressed.
- `ls.invert_x`, `ls.invert_y`, `rs.invert_x`, `rs.invert_y` (`0|1`) —
  flip a stick axis.
- `wheel_repeat_ms <n>` — repeat interval for a held wheel target.
- `panic <src>[+<src>...]` — the panic chord (no default).
- `touch.offset <dx> <dy>` — enables the cloned touchscreen with this
  offset, in display pixels; `touch.display <w> <h> <rotation>` gives
  the display geometry used to convert it into panel units.
- `idle 1` — an explicitly idle profile (nothing grabbed, nothing
  emitted); an empty file means the same.
- `device.match <substring-of-name>` or `device.match vvvv:pppp` (hex) —
  prefer one pad when several are present.

**CLI flags** (`dpadkeys --help` prints the full usage):

```
--config FILE | --profile fkeys|wasd
--serve                   stay resident; re-read the config on SIGUSR1 (what the app uses)
--status-file PATH        with --serve: write a one-line status on every transition
--grab                    one-shot mode: grab the pad exclusively (omit for testing)
--list                    list candidate pad devices and exit
--learn [--learn-timeout-ms N] [--learn-hold-ms N]
                          print the first control (or hold+control chord) pressed
--learn-chord             capture a whole button chord at once (for the panic chord)
--dump                    print every EV_KEY/EV_ABS event from all evdev nodes
--print-config            print the effective config and exit
--panic-chord none|SRC+SRC  override the profile's panic chord
--allow-q                 let the serve keyboard carry KEY_Q
--device auto|/dev/input/eventN
--device-name NAME        name the keyboard+mouse device (default "Odin DPad Keys")
--touch-name NAME         rename the cloned touchscreen (testing only; it must normally
                          match the real panel's name to be mapped identically)
--verbose                 per-event logging (never passed by the app)
--pidfile PATH
```

**Tools**: `daemon/*.sh` (`build.sh`, `push.sh`, `start.sh`, `status.sh`,
`stop.sh`) drive the daemon over adb for local testing.

See [`docs/RELEASING.md`](docs/RELEASING.md) for cutting a release, and
[`docs/legacy-kl-route.md`](docs/legacy-kl-route.md) for the abandoned
`.kl`-file approach this project used before `dpadkeys`.
