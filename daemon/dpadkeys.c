/*
 * dpadkeys v2 - translate the AYN Odin 2 Portal's built-in gamepad into real
 * keyboard keys via a uinput virtual keyboard, driven by a text config file.
 *
 * Why: the Odin 2's built-in controller is a VIRTUAL evdev gamepad created
 * by AYN firmware (bus 0x0003, vendor 0x2020, "Xbox Wireless Controller" /
 * "Odin"-style, product 0x0111 or 0x0112). It is destroyed and recreated
 * whenever the user switches "Controller Style" in system settings. Some
 * Android games (e.g. OSRS mobile) have no gamepad support but do support
 * keyboard shortcuts, so this daemon reads the pad's buttons/hat/sticks and
 * emits real KEY_* events on a dedicated kernel keyboard device (via
 * /dev/uinput) -- no input injection/accessibility hacks, just a second real
 * keyboard the game can bind to.
 *
 * There is also a separate vendor 0x2020 device, "ODIN Station Virtual
 * Mouse" (product 0x0111), which must be ignored -- it has no gamepad
 * buttons, so auto-detection keys off the presence of BTN_SOUTH.
 *
 * v2 replaces the hardcoded fkeys/wasd profiles with a config file mapping
 * arbitrary pad sources (hat, buttons, sticks, triggers) to arbitrary KEY_*
 * codes or NONE (explicit swallow). --profile fkeys/wasd remain as built-in
 * shorthands equivalent to a two-line config file.
 *
 * v3 makes the daemon device-agnostic: any evdev node with BTN_SOUTH is a
 * candidate pad (optionally narrowed by a `device.match` config line), raw
 * sources `key.0xNNN` / `abs.0xNN.neg|pos` address controls that have no
 * semantic name, and `--learn` reports the first control pressed so an app
 * can bind by pressing.
 */

#include <errno.h>
#include <fcntl.h>
#include <limits.h>
#include <signal.h>
#include <math.h>
#include <poll.h>
#include <stdarg.h>
#include <stdbool.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <strings.h>
#include <sys/ioctl.h>
#include <sys/stat.h>
#include <time.h>
#include <unistd.h>

#include <linux/input.h>
#include <linux/uinput.h>

#define BITS_PER_LONG (sizeof(long) * 8)
#define NLONGS(x) (((x) + BITS_PER_LONG - 1) / BITS_PER_LONG)
#define TEST_BIT(bit, arr) ((arr[(bit) / BITS_PER_LONG] >> ((bit) % BITS_PER_LONG)) & 1)

/* Tag stamped on our virtual touchscreen (UI_SET_PHYS, and UI_SET_UNIQ if a
 * kernel ever grows one) so the daemon can recognise its own device. */
#define TOUCH_SELF_TAG "dpadkeys-touch"
#define DEFAULT_DEVICE_NAME "Odin DPad Keys"

static bool evdev_is_virtual(const char *devpath);

/* ---- config sources ---- */

typedef enum {
    SRC_HAT_UP, SRC_HAT_DOWN, SRC_HAT_LEFT, SRC_HAT_RIGHT,
    SRC_BTN_SOUTH, SRC_BTN_EAST, SRC_BTN_NORTH, SRC_BTN_WEST,
    SRC_BTN_TL, SRC_BTN_TR, SRC_BTN_TL2, SRC_BTN_TR2,
    SRC_BTN_SELECT, SRC_BTN_START, SRC_BTN_MODE, SRC_BTN_THUMBL, SRC_BTN_THUMBR,
    SRC_BTN_M1, SRC_BTN_M2,
    SRC_LS_UP, SRC_LS_DOWN, SRC_LS_LEFT, SRC_LS_RIGHT,
    SRC_RS_UP, SRC_RS_DOWN, SRC_RS_LEFT, SRC_RS_RIGHT,
    SRC_LT, SRC_RT,
    SRC_COUNT
} source_id_t;

static const char *SOURCE_NAMES[SRC_COUNT] = {
    "hat.up", "hat.down", "hat.left", "hat.right",
    "btn.south", "btn.east", "btn.north", "btn.west",
    "btn.tl", "btn.tr", "btn.tl2", "btn.tr2",
    "btn.select", "btn.start", "btn.mode", "btn.thumbl", "btn.thumbr",
    "btn.m1", "btn.m2",
    "ls.up", "ls.down", "ls.left", "ls.right",
    "rs.up", "rs.down", "rs.left", "rs.right",
    "lt", "rt",
};

/* EV_KEY codes that map 1:1 onto a source (BTN_DPAD_* included so a pad that
 * sends digital dpad buttons instead of ABS_HAT0X/Y still hits hat.*). */
static const struct { int code; source_id_t src; } BTN_MAP[] = {
    { BTN_DPAD_UP, SRC_HAT_UP }, { BTN_DPAD_DOWN, SRC_HAT_DOWN },
    { BTN_DPAD_LEFT, SRC_HAT_LEFT }, { BTN_DPAD_RIGHT, SRC_HAT_RIGHT },
    { BTN_SOUTH, SRC_BTN_SOUTH }, { BTN_EAST, SRC_BTN_EAST },
    { BTN_NORTH, SRC_BTN_NORTH }, { BTN_WEST, SRC_BTN_WEST },
    { BTN_TL, SRC_BTN_TL }, { BTN_TR, SRC_BTN_TR },
    { BTN_TL2, SRC_BTN_TL2 }, { BTN_TR2, SRC_BTN_TR2 },
    { BTN_SELECT, SRC_BTN_SELECT }, { BTN_START, SRC_BTN_START },
    { BTN_MODE, SRC_BTN_MODE },
    { BTN_THUMBL, SRC_BTN_THUMBL }, { BTN_THUMBR, SRC_BTN_THUMBR },
    { BTN_C, SRC_BTN_M1 }, { BTN_Z, SRC_BTN_M2 }, /* Odin 2 back buttons M1/M2 */
};
#define BTN_MAP_LEN (int)(sizeof(BTN_MAP) / sizeof(BTN_MAP[0]))

/* Raw sources: `key.0xNNN` (any EV_KEY code) and `abs.0xNN.neg|pos` (any
 * EV_ABS code, thresholded like a trigger). They occupy source slots
 * SRC_COUNT..MAX_SOURCES-1 so all per-source state arrays cover both kinds. */
#define MAX_RAW 64
#define MAX_SOURCES (SRC_COUNT + MAX_RAW)

typedef enum { RAW_KEY, RAW_ABS } raw_kind_t;

typedef struct {
    char name[32];   /* exactly as written in the config */
    raw_kind_t kind;
    int code;        /* EV_KEY or EV_ABS code */
    int dir;         /* RAW_ABS only: -1 (neg) or +1 (pos) */
    bool axis_known; /* RAW_ABS: axis range queried from the device */
    struct { int min, max; double center, half; bool unipolar; int state; } axis;
} raw_source_t;

/* ---- target key name table ---- */

static const struct { const char *name; int code; } KEY_TABLE[] = {
    {"KEY_A",KEY_A},{"KEY_B",KEY_B},{"KEY_C",KEY_C},{"KEY_D",KEY_D},{"KEY_E",KEY_E},{"KEY_F",KEY_F},
    {"KEY_G",KEY_G},{"KEY_H",KEY_H},{"KEY_I",KEY_I},{"KEY_J",KEY_J},{"KEY_K",KEY_K},{"KEY_L",KEY_L},
    {"KEY_M",KEY_M},{"KEY_N",KEY_N},{"KEY_O",KEY_O},{"KEY_P",KEY_P},{"KEY_Q",KEY_Q},{"KEY_R",KEY_R},
    {"KEY_S",KEY_S},{"KEY_T",KEY_T},{"KEY_U",KEY_U},{"KEY_V",KEY_V},{"KEY_W",KEY_W},{"KEY_X",KEY_X},
    {"KEY_Y",KEY_Y},{"KEY_Z",KEY_Z},
    {"KEY_0",KEY_0},{"KEY_1",KEY_1},{"KEY_2",KEY_2},{"KEY_3",KEY_3},{"KEY_4",KEY_4},
    {"KEY_5",KEY_5},{"KEY_6",KEY_6},{"KEY_7",KEY_7},{"KEY_8",KEY_8},{"KEY_9",KEY_9},
    {"KEY_F1",KEY_F1},{"KEY_F2",KEY_F2},{"KEY_F3",KEY_F3},{"KEY_F4",KEY_F4},
    {"KEY_F5",KEY_F5},{"KEY_F6",KEY_F6},{"KEY_F7",KEY_F7},{"KEY_F8",KEY_F8},
    {"KEY_F9",KEY_F9},{"KEY_F10",KEY_F10},{"KEY_F11",KEY_F11},{"KEY_F12",KEY_F12},
    {"KEY_UP",KEY_UP},{"KEY_DOWN",KEY_DOWN},{"KEY_LEFT",KEY_LEFT},{"KEY_RIGHT",KEY_RIGHT},
    {"KEY_SPACE",KEY_SPACE},{"KEY_ENTER",KEY_ENTER},{"KEY_ESC",KEY_ESC},{"KEY_TAB",KEY_TAB},
    {"KEY_BACKSPACE",KEY_BACKSPACE},{"KEY_LEFTSHIFT",KEY_LEFTSHIFT},{"KEY_LEFTCTRL",KEY_LEFTCTRL},
    {"KEY_LEFTALT",KEY_LEFTALT},{"KEY_PAGEUP",KEY_PAGEUP},{"KEY_PAGEDOWN",KEY_PAGEDOWN},
    {"KEY_HOME",KEY_HOME},{"KEY_END",KEY_END},{"KEY_INSERT",KEY_INSERT},{"KEY_DELETE",KEY_DELETE},
    {"KEY_MINUS",KEY_MINUS},{"KEY_EQUAL",KEY_EQUAL},{"KEY_COMMA",KEY_COMMA},{"KEY_DOT",KEY_DOT},
    {"KEY_SLASH",KEY_SLASH},{"KEY_SEMICOLON",KEY_SEMICOLON},{"KEY_APOSTROPHE",KEY_APOSTROPHE},
    {"KEY_GRAVE",KEY_GRAVE},{"KEY_LEFTBRACE",KEY_LEFTBRACE},{"KEY_RIGHTBRACE",KEY_RIGHTBRACE},
    {"KEY_BACKSLASH",KEY_BACKSLASH},
    {"KEY_F13",KEY_F13},{"KEY_F14",KEY_F14},{"KEY_F15",KEY_F15},{"KEY_F16",KEY_F16},
    {"KEY_F17",KEY_F17},{"KEY_F18",KEY_F18},{"KEY_F19",KEY_F19},{"KEY_F20",KEY_F20},
    {"KEY_F21",KEY_F21},{"KEY_F22",KEY_F22},{"KEY_F23",KEY_F23},{"KEY_F24",KEY_F24},
    {"KEY_CAPSLOCK",KEY_CAPSLOCK},{"KEY_RIGHTSHIFT",KEY_RIGHTSHIFT},{"KEY_RIGHTCTRL",KEY_RIGHTCTRL},
    {"KEY_RIGHTALT",KEY_RIGHTALT},{"KEY_LEFTMETA",KEY_LEFTMETA},{"KEY_RIGHTMETA",KEY_RIGHTMETA},
    {"KEY_MENU",KEY_MENU},{"KEY_SYSRQ",KEY_SYSRQ},{"KEY_SCROLLLOCK",KEY_SCROLLLOCK},
    {"KEY_PAUSE",KEY_PAUSE},{"KEY_NUMLOCK",KEY_NUMLOCK},
    {"KEY_KP0",KEY_KP0},{"KEY_KP1",KEY_KP1},{"KEY_KP2",KEY_KP2},{"KEY_KP3",KEY_KP3},{"KEY_KP4",KEY_KP4},
    {"KEY_KP5",KEY_KP5},{"KEY_KP6",KEY_KP6},{"KEY_KP7",KEY_KP7},{"KEY_KP8",KEY_KP8},{"KEY_KP9",KEY_KP9},
    {"KEY_KPDOT",KEY_KPDOT},{"KEY_KPENTER",KEY_KPENTER},{"KEY_KPPLUS",KEY_KPPLUS},
    {"KEY_KPMINUS",KEY_KPMINUS},{"KEY_KPASTERISK",KEY_KPASTERISK},{"KEY_KPSLASH",KEY_KPSLASH},
    {"KEY_VOLUMEUP",KEY_VOLUMEUP},{"KEY_VOLUMEDOWN",KEY_VOLUMEDOWN},{"KEY_MUTE",KEY_MUTE},
    {"KEY_PLAYPAUSE",KEY_PLAYPAUSE},{"KEY_NEXTSONG",KEY_NEXTSONG},{"KEY_PREVIOUSSONG",KEY_PREVIOUSSONG},
    {"KEY_BACK",KEY_BACK},{"KEY_HOMEPAGE",KEY_HOMEPAGE},
};
#define KEY_TABLE_LEN (int)(sizeof(KEY_TABLE) / sizeof(KEY_TABLE[0]))
#define TARGET_NONE -1
#define TARGET_UNKNOWN -2
#define TARGET_WHEEL_UP     -10
#define TARGET_WHEEL_DOWN   -11
#define TARGET_HWHEEL_LEFT  -12
#define TARGET_HWHEEL_RIGHT -13

enum { WHEEL_UP_IDX = 0, WHEEL_DOWN_IDX, HWHEEL_LEFT_IDX, HWHEEL_RIGHT_IDX, WHEEL_COUNT };

static int wheel_idx_from_target(int t) {
    switch (t) {
        case TARGET_WHEEL_UP: return WHEEL_UP_IDX;
        case TARGET_WHEEL_DOWN: return WHEEL_DOWN_IDX;
        case TARGET_HWHEEL_LEFT: return HWHEEL_LEFT_IDX;
        case TARGET_HWHEEL_RIGHT: return HWHEEL_RIGHT_IDX;
        default: return -1;
    }
}

static bool target_is_wheel(int t) { return wheel_idx_from_target(t) >= 0; }

/* ---- config ---- */

/* Generalized chords: `<hold>+<src> T` fires T for <src> while <hold> is
 * held. <hold> may be any button-like source (see source_can_be_hold());
 * <src> may be anything, including another hold. Several chords may share a
 * <src>; resolve_target() picks among the ones whose hold is currently held,
 * most-recently-pressed hold wins (see update_source()/resolve_target()). */
#define MAX_CHORDS 64
/* Sources that must be held together for the panic chord. Four is already
 * more than anyone can hold on a handheld without also holding the device. */
#define MAX_PANIC_SRC 4
typedef struct {
    int hold;   /* source slot that must be held */
    int src;    /* source slot the chord applies to */
    int target; /* KEY_* code, TARGET_WHEEL_*, or TARGET_NONE */
} chord_t;

typedef struct {
    int target[MAX_SOURCES]; /* KEY_* code, TARGET_WHEEL_*, or TARGET_NONE */
    bool defined[MAX_SOURCES];     /* any config line referenced this source */
    chord_t chords[MAX_CHORDS];
    int n_chords;
    int modifier_src;      /* legacy staging: source slot of a "MOD" line, or -1 */
    /* legacy staging for `mod+<src> T` lines, resolved into chords against
     * modifier_src once the whole file has been read (see load_config_file);
     * a `mod+<src>` line may appear before or after the `<src> MOD` line. */
    int legacy_mod_target[MAX_SOURCES];
    bool legacy_mod_set[MAX_SOURCES];
    double deadzone;       /* fraction of half-range that counts as pressed */
    bool ls_invert_x, rs_invert_x; /* flip stick left/right */
    bool ls_invert_y, rs_invert_y; /* flip stick up/down */
    int wheel_repeat_ms;   /* repeat interval for held wheel targets */
    int n_raw;             /* raw sources in use (slots SRC_COUNT..SRC_COUNT+n_raw-1) */
    raw_source_t raw[MAX_RAW];
    char device_match[128]; /* substring of the pad name, or "vvvv:pppp" hex; empty = any */

    /* Panic chord (`panic <src>[+<src>...]`): the hardware escape hatch. Every
     * listed source held together for PANIC_CHORD_MS parks the daemon (serve:
     * idle + panic counter; one-shot: exit 6); in serve mode holding on to
     * PANIC_RESTART_MS exits 6 so the supervisor respawns with freshly created
     * virtual devices -- the guaranteed cure for a clone that has somehow been
     * left in a bad state. Per profile, no default: n_panic == 0 means the
     * profile has no chord at all. */
    int panic_src[MAX_PANIC_SRC];
    int n_panic;

    /* `idle 1`: an explicit "this profile deliberately does nothing" marker so
     * --serve can be parked on a config file that still exists. Equivalent to
     * a file with no bindings and no touch.offset, but self-documenting. */
    bool idle;

    /* touch pass-through: absent touch.offset means the daemon never opens
     * or touches the panel at all. */
    bool touch_offset_set;
    int touch_dx, touch_dy;
    char touch_device[64]; /* explicit /dev/input/eventN, or empty = auto */

    /* touch.rotation: quarter-turns (0-3) the daemon must apply to incoming
     * real-panel coordinates before replaying them to the clone, to compensate
     * for the clone's stale device orientation after a display flip. This is a
     * COMPENSATION delta owned by the app, not the raw display rotation: the
     * app computes it as (currentDisplayRotation - referenceRotation) & 3,
     * where referenceRotation is the orientation at which the clone last landed
     * correctly (on the Odin's forced-landscape panel that is rotation 1, so in
     * practice this is 0 normally and 2 after a 180 flip). 0 = passthrough, the
     * exact pre-existing behaviour. See offset_touch_event(). Fed live over the
     * config file + SIGUSR1; never needs the clone recreated. */
    int touch_rotation;

    /* touch.generation: a counter the app bumps whenever the virtual
     * touchscreen must be thrown away and rebuilt -- in practice on every
     * display rotation event. Android mishandles a long-lived cloned
     * touchscreen across a rotation: the clone's coordinate handling toggles
     * between correct and axis-swapped on each rotation event, independent of
     * the rotation actually in force, so no static compensation can track it.
     * A clone created AFTER the rotation has settled is always correct, so the
     * cure is to recreate it on demand. Serve mode only (see
     * serve_recreate_touch_clone); one-shot mode parses and ignores the key,
     * since it creates its clone once per run anyway. Independent of
     * touch.rotation, which stays supported and defaults to 0. */
    int touch_generation;

    /* touch.display W H R: switches the clone from panel-space to
     * display-space coordinates. Why: Android maps our cloned touchscreen
     * onto the display with AXIS SCALING ONLY (no rotation), while the real
     * panel gets the proper rotation from its input-device orientation
     * config -- so a clone that merely mirrors panel-native coordinates
     * lands in the wrong place whenever the display's natural orientation
     * differs from the panel's. touch.display makes the daemon do that
     * rotation itself: W,H are the display size in pixels in its current
     * orientation, R is the display's rotation (quarter-turns) relative to
     * the panel's own natural (portrait) frame. When set, the clone is
     * CREATED with ABS_MT_POSITION_X/Y (and ABS_X/Y, if present) ranged
     * [0,W-1]/[0,H-1], and every forwarded position is remapped from panel
     * raw coordinates into that box before touch.offset/touch.rotation are
     * applied (see transform_touch_event_display()). Absent (touch_display_set
     * == false) means exactly the historical panel-space clone. Changing W/H/R
     * on a live serve reload recreates the clone (see serve_apply), because
     * the ABS ranges are fixed at creation time. */
    bool touch_display_set;
    int touch_display_w, touch_display_h, touch_display_r;
} config_t;

/* --device-name NAME (default "Odin DPad Keys"): names the keyboard+mouse
 * uinput device; the touchscreen clone is named "<NAME> Touch". Both are
 * sanitized (control characters stripped) and truncated to fit
 * UINPUT_MAX_NAME_SIZE-1 bytes -- see sanitize_device_name() in main(). */
static char g_device_name[UINPUT_MAX_NAME_SIZE] = DEFAULT_DEVICE_NAME;
static char g_touch_device_name[UINPUT_MAX_NAME_SIZE] = DEFAULT_DEVICE_NAME " Touch";
static bool g_touch_name_overridden = false; /* --touch-name given: don't derive from --device-name */

static volatile sig_atomic_t g_running = 1;
static int g_uinput_fd = -1;
static int g_pad_fd = -1;
static bool g_grabbed = false;
static bool g_verbose = false;
static bool g_allow_q = false;   /* --allow-q: include KEY_Q in the --serve keyboard superset */
static bool g_source_pressed[MAX_SOURCES] = {0};
static int g_key_count[KEY_CNT] = {0};
static long long g_press_seq = 0;
static long long g_source_press_seq[MAX_SOURCES] = {0}; /* bumped on every press, for "most recently pressed hold" */
static int g_resolved_target[MAX_SOURCES] = {0}; /* target used at press, replayed at release */
static int g_wheel_count[WHEEL_COUNT] = {0};
static long long g_wheel_next_due_ms[WHEEL_COUNT] = {0};
static int g_wheel_repeat_ms = 120;

/* ---- serve mode ----
 *
 * In --serve the daemon lives as long as the app's privilege does: both
 * uinput devices are created once at startup and destroyed only at exit, and
 * "switching profile" is the app rewriting the config file and sending
 * SIGUSR1. Everything that used to be decided once at startup -- whether to
 * grab the pad, whether to grab the panel, what the mappings are -- becomes a
 * transition between an IDLE state (nothing grabbed, the pad behaves normally
 * for every other app) and an ACTIVE one.
 *
 * g_want_pad / g_want_touch are the *intent* derived from the active profile;
 * g_pad_fd >= 0 and g_touch_grabbed are whether that intent is currently
 * satisfied. They can disagree while a device is missing, which is what
 * g_retry_due_ms is for. */
static bool g_serve = false;
static const char *g_status_path = NULL;   /* --status-file, or NULL */
static bool g_want_pad = false;            /* active profile has bindings */
static bool g_want_touch = false;          /* active profile has touch.offset */
static long g_panic_count = 0;             /* panic-chord firings since start */
/* `contacts` is the one status field that changes without a transition, so the
 * loop refreshes the file when it drifts -- rate-limited, since a status write
 * is an fsync + rename and a stuck contact is a lasting condition, not a
 * per-frame one. */
#define STATUS_CONTACTS_MS 400
static int g_status_contacts = -1;         /* contacts= as last written */
static long long g_status_written_ms = -1; /* when the file was last written */
static long long g_retry_due_ms = -1;      /* next acquisition retry, -1 = none */
#define SERVE_RETRY_MS 500

/* ---- grab-when-neutral ----
 *
 * EVIOCGRAB is not a snapshot: whatever is DOWN on a device at the instant we
 * grab it never gets its release delivered to anyone else, because the release
 * event arrives after the grab and is ours alone. Android is left holding a
 * phantom pointer on the real panel (so every touch through the clone lands as
 * a *second* pointer -- pinch/pan, "off by half a screen") or a phantom button
 * on the real pad, for the whole active period.
 *
 * So the rule is: NEVER grab a device that is not neutral. Both devices are
 * opened ungrabbed first; while a contact/button is still down the daemon
 * merely consumes their events (reading an ungrabbed evdev node drains only
 * our own client buffer -- Android still gets its copy) and re-checks the
 * kernel's authoritative state after every SYN_REPORT, grabbing on the very
 * frame that completes the release. Capped, because a device whose key/slot
 * state is latched would otherwise never activate; past the cap we grab anyway
 * and fall back to the old carry-over (touch_sync_initial_contacts). */
#define GRAB_WAIT_MAX_MS 10000
static bool g_pad_wait = false;             /* pad open + ungrabbed, waiting for neutral */
static long long g_pad_wait_since_ms = -1;
static bool g_touch_wait = false;           /* panel open + ungrabbed, waiting for release */
static long long g_touch_wait_since_ms = -1;

/* Self-pipe, so a signal that lands between the g_reload_req test and poll()
 * still wakes the loop. This matters far more in serve mode than it used to:
 * an idle daemon has no fds to poll at all and would otherwise sleep through
 * the very SIGUSR1 that is meant to activate it. */
static int g_sigpipe[2] = { -1, -1 };

/* touch pass-through state: the virtual touchscreen is created once at
 * startup and kept alive across panel re-detects (like the keyboard). */
static volatile sig_atomic_t g_reload_req = 0; /* SIGUSR1: re-read the config */
static int g_touch_fd = -1;
static int g_touch_uinput_fd = -1;
static bool g_touch_grabbed = false;
static char g_touch_path[64] = {0};
static char g_touch_name[128] = {0};
static struct input_absinfo g_touch_mtx_info, g_touch_mty_info, g_touch_x_info, g_touch_y_info;
static bool g_touch_has_mtx = false, g_touch_has_mty = false, g_touch_has_x = false, g_touch_has_y = false;
/* Silences touch_enable()'s "no panel"/"cannot grab" lines while serve mode
 * is retrying acquisition every SERVE_RETRY_MS -- the retry itself logs, but
 * throttled (see serve_retry_acquire). */
static bool g_touch_quiet = false;
/* Set by touch_write() in serve mode when the clone stops accepting writes.
 * One-shot mode treats that as fatal (exit 5); a serve daemon must not exit,
 * so the loop picks this up and drops to keys-only instead. */
static bool g_touch_uinput_dead = false;
/* touch.generation as last acted on. A reload that names a different value
 * rebuilds the clone (serve mode only); reported as gen= in the status file so
 * the app can see which generation the live device belongs to. */
static int g_touch_gen_applied = 0;
/* touch.display as last acted on (mirrors g_touch_gen_applied): the clone is
 * rebuilt on a serve reload whenever these disagree with the incoming
 * profile's touch_display_* (see serve_apply). Set at serve startup and
 * inside serve_recreate_touch_clone(), never touched otherwise. */
static bool g_touch_display_applied_set = false;
static int g_touch_display_applied_w = 0, g_touch_display_applied_h = 0, g_touch_display_applied_r = 0;

/* ---- clone contact bookkeeping ----
 *
 * The virtual touchscreen is a persistent device: it outlives every profile
 * switch, panel re-detect and idle transition.  Anything left DOWN on it stays
 * down as far as Android is concerned -- which is exactly how a single finger
 * came to read as a *second* pointer ("touch is off by half the screen"), with
 * nothing short of a daemon restart clearing it.  So the daemon mirrors what
 * it has actually written to the clone -- the tracking id live in each slot,
 * the BTN_TOUCH state, the implicit current slot -- and releases it on every
 * path where forwarding stops. */
#define TOUCH_MAX_SLOTS 64
/* No-panel-events watchdog.  A finger resting perfectly still can go a long
 * time with zero reports on a panel that filters stationary contacts, so this
 * is deliberately far longer than any plausible long-press: it is a backstop
 * for a release path we missed, not a touch timeout. */
#define TOUCH_STUCK_MS 30000
static int g_clone_tid[TOUCH_MAX_SLOTS];      /* tracking id live per slot ON THE CLONE, -1 = up */
static bool g_clone_btn_touch = false;        /* last BTN_TOUCH value written to the clone */
static int g_clone_cur_slot = -1;             /* clone's implicit ABS_MT_SLOT, -1 = unknown */
static int g_touch_slot_min = 0;              /* panel's ABS_MT_SLOT minimum */
static int g_touch_nslots = 0;                /* slots tracked, 0 until a panel has been seen */
static int g_clone_next_tid = 0x40000000;     /* private id space: carry-over + clash remaps */
static long long g_touch_last_event_ms = -1;  /* last panel event forwarded, -1 = none */

/* Panic chord: the hardware escape hatch, configured per profile by the
 * `panic <src>[+<src>...]` config line (see config_t::panic_src). Every listed
 * source held together for PANIC_CHORD_MS parks the daemon -- serve mode goes
 * idle and bumps the panic counter, one-shot mode exits 6 so the supervisor
 * restarts without the touch offset. In serve mode holding on to
 * PANIC_RESTART_MS exits 6 instead, destroying BOTH virtual devices on the way
 * out so the watchdog respawns the daemon with fresh ones: the last-resort
 * cure for a clone Android has gotten confused about. No `panic` line means no
 * chord at all. */
#define PANIC_CHORD_MS 1000
#define PANIC_RESTART_MS 4000
static bool g_panic_held[MAX_PANIC_SRC] = {0}; /* per cfg->panic_src position */
static long long g_chord_start_ms = -1; /* -1 = not all held / not armed */
static bool g_panic_idle_fired = false; /* the PANIC_CHORD_MS step already fired this hold */
/* Serve mode's escalation window. Parking at PANIC_CHORD_MS lets go of the
 * pad, which would also destroy the daemon's only view of whether the chord is
 * still held -- so the pad fd is kept open but UNGRABBED (the pad behaves
 * normally for every other app, we merely watch it) until PANIC_RESTART_MS.
 * -1 = not parked. */
static long long g_panic_watch_until_ms = -1;
static const char *g_pidfile = NULL;     /* so the restart path can unlink it */

/* Both handlers poke the self-pipe; write() is async-signal-safe and the
 * write end is non-blocking, so a full pipe (many signals, loop not yet
 * drained) is harmless -- the loop is going to wake anyway. */
static void sigpipe_poke(void) {
    if (g_sigpipe[1] < 0) return;
    char b = 1;
    ssize_t r = write(g_sigpipe[1], &b, 1);
    (void)r;
}

static void on_signal(int sig) {
    (void)sig;
    g_running = 0;
    sigpipe_poke();
}

static void on_usr1(int sig) {
    (void)sig;
    g_reload_req = 1;
    sigpipe_poke();
}

static void init_config(config_t *cfg) {
    memset(cfg, 0, sizeof(*cfg));
    for (int i = 0; i < MAX_SOURCES; i++) {
        cfg->target[i] = TARGET_NONE;
        cfg->defined[i] = false;
        cfg->legacy_mod_target[i] = TARGET_NONE;
        cfg->legacy_mod_set[i] = false;
    }
    cfg->n_chords = 0;
    cfg->modifier_src = -1;
    cfg->deadzone = 0.5;
    cfg->ls_invert_x = false; cfg->rs_invert_x = false;
    cfg->ls_invert_y = false; cfg->rs_invert_y = false;
    cfg->wheel_repeat_ms = 120;
    cfg->n_raw = 0;
    cfg->device_match[0] = '\0';
    cfg->idle = false;
    cfg->touch_offset_set = false;
    cfg->touch_dx = 0;
    cfg->touch_dy = 0;
    cfg->touch_device[0] = '\0';
    cfg->touch_rotation = 0;
    cfg->touch_generation = 0;
    cfg->touch_display_set = false;
    cfg->touch_display_w = 0;
    cfg->touch_display_h = 0;
    cfg->touch_display_r = 0;
    cfg->n_panic = 0;
    for (int i = 0; i < MAX_PANIC_SRC; i++) cfg->panic_src[i] = -1;
}

static int n_sources(const config_t *cfg) { return SRC_COUNT + cfg->n_raw; }

static const char *source_name(const config_t *cfg, int slot) {
    if (slot < SRC_COUNT) return SOURCE_NAMES[slot];
    return cfg->raw[slot - SRC_COUNT].name;
}

/* Semantic source that a raw EV_KEY code aliases, or -1. */
static int semantic_for_key(int code) {
    for (int i = 0; i < BTN_MAP_LEN; i++)
        if (BTN_MAP[i].code == code) return BTN_MAP[i].src;
    return -1;
}

/* Semantic source that a raw hat axis+direction aliases, or -1. Stick and
 * trigger axes are device-dependent and are checked after detect_axes(). */
static int semantic_for_abs(int code, int dir) {
    if (code == ABS_HAT0X) return dir < 0 ? SRC_HAT_LEFT : SRC_HAT_RIGHT;
    if (code == ABS_HAT0Y) return dir < 0 ? SRC_HAT_UP : SRC_HAT_DOWN;
    return -1;
}

/* Parses "key.0xNNN" / "abs.0xNN.neg|pos" into *out; returns true on success. */
static bool parse_raw_source(const char *name, raw_source_t *out) {
    memset(out, 0, sizeof(*out));
    const char *p;
    char *end;
    if (strncmp(name, "key.0x", 6) == 0) {
        p = name + 6;
        long code = strtol(p, &end, 16);
        if (end == p || *end != '\0' || code < 0 || code >= KEY_CNT) return false;
        out->kind = RAW_KEY; out->code = (int)code; out->dir = 0;
    } else if (strncmp(name, "abs.0x", 6) == 0) {
        p = name + 6;
        long code = strtol(p, &end, 16);
        if (end == p || code < 0 || code >= ABS_CNT) return false;
        if (strcmp(end, ".neg") == 0) out->dir = -1;
        else if (strcmp(end, ".pos") == 0) out->dir = 1;
        else return false;
        out->kind = RAW_ABS; out->code = (int)code;
    } else {
        return false;
    }
    if (strlen(name) >= sizeof(out->name)) return false;
    strcpy(out->name, name);
    return true;
}

/* Returns the source slot for a config name, adding a raw slot on first use.
 * Returns -1 for an unknown name; sets *err for a collision/overflow. */
static int lookup_or_add_source(config_t *cfg, const char *name, const char **err) {
    *err = NULL;
    for (int i = 0; i < SRC_COUNT; i++)
        if (strcmp(name, SOURCE_NAMES[i]) == 0) return i;
    raw_source_t r;
    if (!parse_raw_source(name, &r)) return -1;
    for (int i = 0; i < cfg->n_raw; i++) {
        const raw_source_t *e = &cfg->raw[i];
        if (e->kind == r.kind && e->code == r.code && e->dir == r.dir) return SRC_COUNT + i;
    }
    int sem = (r.kind == RAW_KEY) ? semantic_for_key(r.code) : semantic_for_abs(r.code, r.dir);
    if (sem >= 0 && cfg->defined[sem]) {
        static char msg[96];
        snprintf(msg, sizeof(msg), "raw source '%s' collides with '%s'", name, SOURCE_NAMES[sem]);
        *err = msg;
        return -1;
    }
    if (cfg->n_raw >= MAX_RAW) { *err = "too many raw sources"; return -1; }
    cfg->raw[cfg->n_raw] = r;
    return SRC_COUNT + cfg->n_raw++;
}

/* The reverse collision: a semantic line after its raw alias was mapped. */
static const char *semantic_collides_with_raw(const config_t *cfg, int sem) {
    for (int i = 0; i < cfg->n_raw; i++) {
        const raw_source_t *r = &cfg->raw[i];
        int alias = (r->kind == RAW_KEY) ? semantic_for_key(r->code) : semantic_for_abs(r->code, r->dir);
        if (alias == sem) return r->name;
    }
    return NULL;
}

static int lookup_target(const char *name) {
    if (strcmp(name, "NONE") == 0) return TARGET_NONE;
    if (strcmp(name, "WHEEL_UP") == 0) return TARGET_WHEEL_UP;
    if (strcmp(name, "WHEEL_DOWN") == 0) return TARGET_WHEEL_DOWN;
    if (strcmp(name, "HWHEEL_LEFT") == 0) return TARGET_HWHEEL_LEFT;
    if (strcmp(name, "HWHEEL_RIGHT") == 0) return TARGET_HWHEEL_RIGHT;
    for (int i = 0; i < KEY_TABLE_LEN; i++)
        if (strcmp(name, KEY_TABLE[i].name) == 0) return KEY_TABLE[i].code;
    return TARGET_UNKNOWN;
}

static const char *key_name(int code) {
    if (code == TARGET_NONE) return "NONE";
    if (code == TARGET_WHEEL_UP) return "WHEEL_UP";
    if (code == TARGET_WHEEL_DOWN) return "WHEEL_DOWN";
    if (code == TARGET_HWHEEL_LEFT) return "HWHEEL_LEFT";
    if (code == TARGET_HWHEEL_RIGHT) return "HWHEEL_RIGHT";
    for (int i = 0; i < KEY_TABLE_LEN; i++)
        if (KEY_TABLE[i].code == code) return KEY_TABLE[i].name;
    return "?";
}

/* A hold in `<hold>+<src>` must be a source with a plain pressed/released
 * state: btn.*, hat.*, lt, rt, key.0xNNN, abs.0xNN.neg|pos. Stick directions
 * (ls.* / rs.*) are continuous/derived and may not be held. SRC_LS_UP..
 * SRC_RS_RIGHT is one contiguous run in the source_id_t enum. */
static bool source_can_be_hold(int slot) {
    return !(slot >= SRC_LS_UP && slot <= SRC_RS_RIGHT);
}

/* ---- config diagnostics ----
 *
 * A config file reaches the parser in one of two modes. STRICT is how the
 * one-shot CLI has always behaved: the first bad line is fatal (exit 2), so a
 * typo can never silently half-apply. LENIENT is what --serve needs: the
 * daemon owns the virtual devices for the lifetime of the app's privilege, so
 * a bad line in a profile the app just wrote must never take the daemon (and
 * with it both uinput devices) down -- it is logged and the line is skipped,
 * leaving the rest of the profile to apply.
 *
 * cfg_problem() is the single choke point for both. In lenient mode it sets
 * g_cfg_line_bad, which every caller inside load_config_file() checks so the
 * offending line is abandoned rather than half-applied. */
static bool g_cfg_lenient = false;
static bool g_cfg_line_bad = false;
/* --panic-chord: an override applied on top of every profile the daemon loads,
 * for standalone/debug use. NULL = no override (the profile decides). */
static const char *g_panic_cli_spec = NULL;

static void cfg_problem(const char *path, int lineno, const char *fmt, ...) {
    va_list ap;
    fprintf(stderr, "dpadkeys: %s:%d: ", path, lineno);
    va_start(ap, fmt);
    vfprintf(stderr, fmt, ap);
    va_end(ap);
    fputc('\n', stderr);
    fflush(stderr);
    if (!g_cfg_lenient) exit(2);
    g_cfg_line_bad = true;
}

/* Resolves (adding a raw slot on first use) and registers a source name from
 * a config line. Shared by plain lines and both sides of a `<hold>+<src>`
 * chord line. Returns -1 (lenient mode only) after reporting an unknown name,
 * a raw/semantic collision, etc.; in strict mode cfg_problem() exits first. */
static int resolve_source(config_t *cfg, const char *name, const char *path, int lineno) {
    const char *err = NULL;
    int src = lookup_or_add_source(cfg, name, &err);
    if (src < 0) {
        if (err) cfg_problem(path, lineno, "%s", err);
        else cfg_problem(path, lineno, "unknown source '%s'", name);
        return -1;
    }
    if (src < SRC_COUNT && !cfg->defined[src]) {
        const char *raw = semantic_collides_with_raw(cfg, src);
        if (raw) {
            cfg_problem(path, lineno, "source '%s' collides with raw source '%s'", name, raw);
            return -1;
        }
    }
    cfg->defined[src] = true;
    return src;
}

/* Resolves a target token; reports (and in strict mode exits on) anything that
 * is not a known target name (KEY_*, WHEEL_* / HWHEEL_*, or NONE), returning
 * TARGET_UNKNOWN so a lenient caller can skip the line. */
static int resolve_target_tok(const char *tok, const char *path, int lineno) {
    int target = lookup_target(tok);
    if (target == TARGET_UNKNOWN)
        cfg_problem(path, lineno, "unknown target '%s'", tok);
    return target;
}

/* Appends a chord; reports (and in strict mode exits on) a full table. */
/* Parses a panic-chord spec -- "none"/"off", or one to MAX_PANIC_SRC
 * button-like sources joined by '+' ("btn.m1+btn.m2", "btn.tl+btn.tr",
 * "key.0x13d") -- into cfg->panic_src/n_panic. Stick directions are rejected
 * for the same reason they cannot be chord holds: they are derived, not held.
 * `spec` is copied before being tokenised, so string literals and the CLI's
 * argv are both safe to pass. Returns false (cfg->n_panic left at 0) on any
 * problem, having reported it through cfg_problem(). */
static bool parse_panic_spec(config_t *cfg, const char *spec, const char *path, int lineno) {
    cfg->n_panic = 0;
    for (int i = 0; i < MAX_PANIC_SRC; i++) cfg->panic_src[i] = -1;
    if (strcmp(spec, "none") == 0 || strcmp(spec, "off") == 0) return true;

    char buf[160];
    if (snprintf(buf, sizeof(buf), "%s", spec) >= (int)sizeof(buf)) {
        cfg_problem(path, lineno, "panic chord spec too long");
        return false;
    }
    int n = 0;
    char *save = NULL;
    for (char *tok = strtok_r(buf, "+", &save); tok; tok = strtok_r(NULL, "+", &save)) {
        if (n >= MAX_PANIC_SRC) {
            cfg_problem(path, lineno, "panic chord takes at most %d sources", MAX_PANIC_SRC);
            cfg->n_panic = 0;
            return false;
        }
        int src = resolve_source(cfg, tok, path, lineno);
        if (src < 0) { cfg->n_panic = 0; return false; }
        if (!source_can_be_hold(src)) {
            cfg_problem(path, lineno, "'%s' cannot be part of a panic chord "
                                      "(stick directions can't be held)", tok);
            cfg->n_panic = 0;
            return false;
        }
        for (int i = 0; i < n; i++) {
            if (cfg->panic_src[i] == src) {
                cfg_problem(path, lineno, "panic chord lists '%s' twice", tok);
                cfg->n_panic = 0;
                return false;
            }
        }
        cfg->panic_src[n++] = src;
    }
    if (n == 0) {
        cfg_problem(path, lineno, "panic needs at least one source (or 'none')");
        return false;
    }
    cfg->n_panic = n;
    return true;
}

/* Renders cfg's panic chord back into its config spelling ("btn.m1+btn.m2",
 * or "none"), for --print-config and the banners. */
static void panic_spec_str(const config_t *cfg, char *out, size_t outsz) {
    if (cfg->n_panic <= 0) { snprintf(out, outsz, "none"); return; }
    out[0] = '\0';
    for (int i = 0; i < cfg->n_panic; i++) {
        size_t len = strlen(out);
        snprintf(out + len, outsz > len ? outsz - len : 0, "%s%s",
                 i ? "+" : "", source_name(cfg, cfg->panic_src[i]));
    }
}

/* --panic-chord overrides whatever the profile asked for, on every profile the
 * daemon loads. Validated once in main(), so it cannot fail here. */
static void apply_panic_cli_override(config_t *cfg) {
    if (!g_panic_cli_spec) return;
    parse_panic_spec(cfg, g_panic_cli_spec, "--panic-chord", 0);
}

static void add_chord(config_t *cfg, int hold, int src, int target, const char *path, int lineno) {
    if (cfg->n_chords >= MAX_CHORDS) {
        cfg_problem(path, lineno, "too many chords (max %d)", MAX_CHORDS);
        return;
    }
    cfg->chords[cfg->n_chords].hold = hold;
    cfg->chords[cfg->n_chords].src = src;
    cfg->chords[cfg->n_chords].target = target;
    cfg->n_chords++;
}

#define TOUCH_OFFSET_MIN -200
#define TOUCH_OFFSET_MAX 200

/* Clamps cfg->touch_dx/dy to [TOUCH_OFFSET_MIN, TOUCH_OFFSET_MAX], logging a
 * warning (tagged with `context`, e.g. a config path or "SIGUSR1") if either
 * value was out of range. No-op if touch.offset isn't set. */
static void clamp_touch_offset(config_t *cfg, const char *context) {
    if (!cfg->touch_offset_set) return;
    int dx = cfg->touch_dx, dy = cfg->touch_dy;
    if (dx < TOUCH_OFFSET_MIN) cfg->touch_dx = TOUCH_OFFSET_MIN;
    else if (dx > TOUCH_OFFSET_MAX) cfg->touch_dx = TOUCH_OFFSET_MAX;
    if (dy < TOUCH_OFFSET_MIN) cfg->touch_dy = TOUCH_OFFSET_MIN;
    else if (dy > TOUCH_OFFSET_MAX) cfg->touch_dy = TOUCH_OFFSET_MAX;
    if (cfg->touch_dx != dx || cfg->touch_dy != dy) {
        fprintf(stderr, "dpadkeys: %s: touch.offset %d %d out of range, clamped to %d %d\n",
                context, dx, dy, cfg->touch_dx, cfg->touch_dy);
        fflush(stderr);
    }
}

/* Parses `path` into a fresh *cfg. `lenient` selects the diagnostic mode
 * described above cfg_problem(): false = the historical CLI behaviour (any
 * problem exits 2), true = skip the offending line and carry on, which is
 * what --serve's SIGUSR1 reload uses. Returns false only in lenient mode and
 * only when the file could not be opened at all (errno set); in that case
 * *cfg is left in the init_config() state. */
static bool load_config_file(const char *path, config_t *cfg, bool lenient) {
    bool prev_lenient = g_cfg_lenient;
    g_cfg_lenient = lenient;
    init_config(cfg);
    FILE *f = fopen(path, "r");
    if (!f) {
        int e = errno;
        if (!lenient) {
            fprintf(stderr, "dpadkeys: cannot open config '%s': %s\n", path, strerror(e));
            exit(2);
        }
        g_cfg_lenient = prev_lenient;
        errno = e;
        return false;
    }
    char line[256];
    int lineno = 0;
    while (fgets(line, sizeof(line), f)) {
        lineno++;
        g_cfg_line_bad = false;
        char *hash = strchr(line, '#');
        if (hash) *hash = '\0';
        char *save = NULL;
        char *tok1 = strtok_r(line, " \t\r\n", &save);
        if (!tok1) continue;
        char *tok2 = strtok_r(NULL, " \t\r\n", &save);
        if (!tok2) {
            cfg_problem(path, lineno, "missing value for '%s'", tok1);
            continue;
        }
        if (strcmp(tok1, "idle") == 0) { cfg->idle = atoi(tok2) != 0; continue; }
        if (strcmp(tok1, "ls.invert_x") == 0) { cfg->ls_invert_x = atoi(tok2) != 0; continue; }
        if (strcmp(tok1, "rs.invert_x") == 0) { cfg->rs_invert_x = atoi(tok2) != 0; continue; }
        if (strcmp(tok1, "ls.invert_y") == 0) { cfg->ls_invert_y = atoi(tok2) != 0; continue; }
        if (strcmp(tok1, "rs.invert_y") == 0) { cfg->rs_invert_y = atoi(tok2) != 0; continue; }
        if (strcmp(tok1, "deadzone") == 0) {
            cfg->deadzone = atof(tok2);
            continue;
        }
        if (strcmp(tok1, "wheel_repeat_ms") == 0) {
            cfg->wheel_repeat_ms = atoi(tok2);
            continue;
        }
        if (strcmp(tok1, "touch.offset") == 0) {
            if (strcmp(tok2, "off") == 0 || strcmp(tok2, "none") == 0) {
                cfg->touch_offset_set = false;
                cfg->touch_dx = cfg->touch_dy = 0;
                continue;
            }
            char *tok3 = strtok_r(NULL, " \t\r\n", &save);
            if (!tok3) {
                cfg_problem(path, lineno, "touch.offset needs two values (dx dy)");
                continue;
            }
            cfg->touch_dx = atoi(tok2);
            cfg->touch_dy = atoi(tok3);
            cfg->touch_offset_set = true;
            continue;
        }
        if (strcmp(tok1, "touch.rotation") == 0) {
            int r = atoi(tok2);
            cfg->touch_rotation = ((r % 4) + 4) % 4;
            continue;
        }
        if (strcmp(tok1, "touch.generation") == 0) {
            cfg->touch_generation = atoi(tok2);
            continue;
        }
        if (strcmp(tok1, "touch.display") == 0) {
            if (strcmp(tok2, "off") == 0 || strcmp(tok2, "none") == 0) {
                cfg->touch_display_set = false;
                cfg->touch_display_w = cfg->touch_display_h = cfg->touch_display_r = 0;
                continue;
            }
            char *tok3 = strtok_r(NULL, " \t\r\n", &save);
            char *tok4 = strtok_r(NULL, " \t\r\n", &save);
            if (!tok3 || !tok4) {
                cfg_problem(path, lineno, "touch.display needs three values (W H R)");
                continue;
            }
            int w = atoi(tok2), h = atoi(tok3), r = atoi(tok4);
            if (w <= 0 || h <= 0) {
                cfg_problem(path, lineno, "touch.display W/H must be positive (got %d %d)", w, h);
                continue;
            }
            cfg->touch_display_w = w;
            cfg->touch_display_h = h;
            cfg->touch_display_r = ((r % 4) + 4) % 4;
            cfg->touch_display_set = true;
            continue;
        }
        if (strcmp(tok1, "panic") == 0) {
            parse_panic_spec(cfg, tok2, path, lineno);
            continue;
        }
        if (strcmp(tok1, "touch.device") == 0) {
            if (strcmp(tok2, "auto") == 0) cfg->touch_device[0] = '\0';
            else snprintf(cfg->touch_device, sizeof(cfg->touch_device), "%s", tok2);
            continue;
        }
        if (strcmp(tok1, "device.match") == 0) {
            /* value is the rest of the line (device names may contain spaces) */
            char buf[128];
            const char *rest = save ? save : "";
            while (*rest == ' ' || *rest == '\t') rest++;
            if (*rest) snprintf(buf, sizeof(buf), "%s %s", tok2, rest);
            else snprintf(buf, sizeof(buf), "%s", tok2);
            size_t len = strlen(buf);
            while (len > 0 && strchr(" \t\r\n", buf[len - 1])) buf[--len] = '\0';
            snprintf(cfg->device_match, sizeof(cfg->device_match), "%s", buf);
            continue;
        }

        /* `<hold>+<src> T`: either the new generalized-chord syntax, or the
         * legacy `mod+<src> T` spelling (deferred below since the `<src>
         * MOD` line naming the modifier may come before or after it). */
        char *plus = strchr(tok1, '+');
        if (plus) {
            *plus = '\0';
            const char *hold_name = tok1;
            const char *src_name = plus + 1;
            if (strcmp(hold_name, "mod") == 0) {
                int src = resolve_source(cfg, src_name, path, lineno);
                if (src < 0) continue;
                int target = resolve_target_tok(tok2, path, lineno);
                if (target == TARGET_UNKNOWN) continue;
                cfg->legacy_mod_set[src] = true;
                cfg->legacy_mod_target[src] = target;
                continue;
            }
            int hold = resolve_source(cfg, hold_name, path, lineno);
            if (hold < 0) continue;
            if (!source_can_be_hold(hold)) {
                cfg_problem(path, lineno, "'%s' cannot be used as a hold "
                                          "(stick directions can't be held)", hold_name);
                continue;
            }
            int src = resolve_source(cfg, src_name, path, lineno);
            if (src < 0) continue;
            int target = resolve_target_tok(tok2, path, lineno);
            if (target == TARGET_UNKNOWN) continue;
            add_chord(cfg, hold, src, target, path, lineno);
            continue;
        }

        int src = resolve_source(cfg, tok1, path, lineno);
        if (src < 0) continue;

        if (strcmp(tok2, "MOD") == 0) {
            if (cfg->modifier_src != -1) {
                cfg_problem(path, lineno, "modifier already declared as '%s'",
                            source_name(cfg, cfg->modifier_src));
                continue;
            }
            cfg->modifier_src = src;
            continue;
        }

        int target = resolve_target_tok(tok2, path, lineno);
        if (target == TARGET_UNKNOWN) continue;
        cfg->target[src] = target;
    }
    fclose(f);

    /* Resolve legacy `<src> MOD` + `mod+<src> T` lines into chords now that
     * the whole file has been read. A hold declared this way always swallows
     * its own plain press (the old modifier always did, regardless of any
     * target the source itself had), and mod+ lines with no `MOD` line ever
     * declared are silently unreachable, exactly as before. */
    if (cfg->modifier_src != -1) {
        int hold = cfg->modifier_src;
        cfg->target[hold] = TARGET_NONE;
        for (int i = 0; i < n_sources(cfg); i++) {
            if (!cfg->legacy_mod_set[i]) continue;
            add_chord(cfg, hold, i, cfg->legacy_mod_target[i], path, lineno);
        }
    }

    clamp_touch_offset(cfg, path);
    g_cfg_lenient = prev_lenient;
    g_cfg_line_bad = false;
    return true;
}

static void load_profile(config_t *cfg, const char *name) {
    init_config(cfg);
    if (strcmp(name, "fkeys") == 0) {
        cfg->target[SRC_HAT_UP] = KEY_F1;
        cfg->target[SRC_HAT_DOWN] = KEY_F2;
        cfg->target[SRC_HAT_LEFT] = KEY_F3;
        cfg->target[SRC_HAT_RIGHT] = KEY_F4;
    } else if (strcmp(name, "wasd") == 0) {
        cfg->target[SRC_HAT_UP] = KEY_W;
        cfg->target[SRC_HAT_DOWN] = KEY_S;
        cfg->target[SRC_HAT_LEFT] = KEY_A;
        cfg->target[SRC_HAT_RIGHT] = KEY_D;
    } else {
        fprintf(stderr, "dpadkeys: unknown profile '%s'\n", name);
        exit(2);
    }
}

static void print_config(const config_t *cfg, FILE *out) {
    fprintf(out, "# effective dpadkeys config\n");
    if (cfg->device_match[0]) fprintf(out, "%-12s %s\n", "device.match", cfg->device_match);
    if (cfg->idle) fprintf(out, "%-12s %d\n", "idle", 1);
    /* semantic sources are always listed; raw ones exactly as they were given.
     * A source used as a hold prints its plain target here (NONE unless it
     * also has its own binding) and its chords below in canonical
     * `<hold>+<src>` form -- there is no more standalone "MOD" target. */
    for (int i = 0; i < n_sources(cfg); i++)
        fprintf(out, "%-12s %s\n", source_name(cfg, i), key_name(cfg->target[i]));
    fprintf(out, "%-12s %.2f\n", "deadzone", cfg->deadzone);
    fprintf(out, "%-12s %d\n", "ls.invert_x", cfg->ls_invert_x ? 1 : 0);
    fprintf(out, "%-12s %d\n", "ls.invert_y", cfg->ls_invert_y ? 1 : 0);
    fprintf(out, "%-12s %d\n", "rs.invert_x", cfg->rs_invert_x ? 1 : 0);
    fprintf(out, "%-12s %d\n", "rs.invert_y", cfg->rs_invert_y ? 1 : 0);
    for (int i = 0; i < cfg->n_chords; i++) {
        char name[80];
        snprintf(name, sizeof(name), "%s+%s",
                 source_name(cfg, cfg->chords[i].hold), source_name(cfg, cfg->chords[i].src));
        fprintf(out, "%-12s %s\n", name, key_name(cfg->chords[i].target));
    }
    fprintf(out, "%-12s %d\n", "wheel_repeat_ms", cfg->wheel_repeat_ms);
    {
        char pb[160];
        panic_spec_str(cfg, pb, sizeof(pb));
        fprintf(out, "%-12s %s\n", "panic", pb);
    }
    if (cfg->touch_offset_set)
        fprintf(out, "%-12s %d %d\n", "touch.offset", cfg->touch_dx, cfg->touch_dy);
    else
        fprintf(out, "%-12s %s\n", "touch.offset", "off");
    fprintf(out, "%-12s %s\n", "touch.device", cfg->touch_device[0] ? cfg->touch_device : "auto");
    fprintf(out, "%-12s %d\n", "touch.rotation", cfg->touch_rotation);
    fprintf(out, "%-12s %d\n", "touch.generation", cfg->touch_generation);
    if (cfg->touch_display_set)
        fprintf(out, "%-12s %d %d %d\n", "touch.display",
                cfg->touch_display_w, cfg->touch_display_h, cfg->touch_display_r);
    else
        fprintf(out, "%-12s %s\n", "touch.display", "off");
}

/* ---- uinput device ---- */

static void emit(int fd, int type, int code, int value) {
    struct input_event ev = {0};
    ev.type = type;
    ev.code = code;
    ev.value = value;
    if (write(fd, &ev, sizeof(ev)) != sizeof(ev))
        perror("write uinput event");
}

static void emit_key(int fd, int code, int value) {
    emit(fd, EV_KEY, code, value);
    emit(fd, EV_SYN, SYN_REPORT, 0);
}

static void emit_wheel_notch(int fd, int wheel_idx) {
    switch (wheel_idx) {
        case WHEEL_UP_IDX:      emit(fd, EV_REL, REL_WHEEL, 1); break;
        case WHEEL_DOWN_IDX:    emit(fd, EV_REL, REL_WHEEL, -1); break;
        case HWHEEL_LEFT_IDX:   emit(fd, EV_REL, REL_HWHEEL, -1); break;
        case HWHEEL_RIGHT_IDX:  emit(fd, EV_REL, REL_HWHEEL, 1); break;
        default: return;
    }
    emit(fd, EV_SYN, SYN_REPORT, 0);
}

static long long now_ms(void) {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (long long)ts.tv_sec * 1000 + ts.tv_nsec / 1000000;
}

/* True if `code` is on the fixed key superset the --serve keyboard registers:
 * everything in KEY_TABLE except KEY_Q.
 *
 * Why KEY_Q is held back: Android's EventHub classifies a device as
 * INPUT_DEVICE_CLASS_ALPHAKEY when its key bitmap covers the alphabetic
 * block, and an alphabetic keyboard being present makes the framework hide
 * the on-screen keyboard (and, on the Odin, makes AYN's mapper treat us as a
 * text device). Leaving one letter out keeps the device a plain KEYBOARD
 * class, so the soft keyboard still comes up in the game's chat box while
 * every other letter we might want to bind is still emittable. KEY_Q is the
 * sacrifice because no profile in this kit binds it.
 *
 * --allow-q overrides this and lets KEY_Q back into the superset, for users
 * who have confirmed Android's "Use on-screen keyboard" override is on. */
static bool key_in_superset(int code) {
    if (code == KEY_Q) return g_allow_q;
    for (int i = 0; i < KEY_TABLE_LEN; i++)
        if (KEY_TABLE[i].code == code) return true;
    return false;
}

/* Creates the g_device_name (default "Odin DPad Keys") uinput device with exactly the EV_KEY codes
 * flagged in `used` (KEY_CNT entries), plus -- when `want_pointer` -- the
 * EV_REL axes and mouse buttons a wheel target needs. Callers:
 * open_uinput() (one-shot mode: only the keys this config uses) and
 * open_uinput_superset() (--serve: a fixed set, so profile switches never
 * have to recreate the device). */
static int open_uinput_dev(const bool *used, bool want_pointer) {
    int fd = open("/dev/uinput", O_WRONLY | O_NONBLOCK);
    if (fd < 0) {
        perror("open /dev/uinput");
        return -1;
    }
    if (ioctl(fd, UI_SET_EVBIT, EV_KEY) < 0 || ioctl(fd, UI_SET_EVBIT, EV_SYN) < 0) {
        perror("UI_SET_EVBIT");
        close(fd);
        return -1;
    }
    for (int c = 0; c < KEY_CNT; c++) {
        if (used[c] && ioctl(fd, UI_SET_KEYBIT, c) < 0) {
            perror("UI_SET_KEYBIT");
            close(fd);
            return -1;
        }
    }

    if (want_pointer) {
        if (ioctl(fd, UI_SET_EVBIT, EV_REL) < 0 ||
            ioctl(fd, UI_SET_RELBIT, REL_WHEEL) < 0 ||
            ioctl(fd, UI_SET_RELBIT, REL_HWHEEL) < 0 ||
            ioctl(fd, UI_SET_RELBIT, REL_X) < 0 ||
            ioctl(fd, UI_SET_RELBIT, REL_Y) < 0 ||
            ioctl(fd, UI_SET_KEYBIT, BTN_LEFT) < 0 ||
            ioctl(fd, UI_SET_KEYBIT, BTN_RIGHT) < 0 ||
            ioctl(fd, UI_SET_KEYBIT, BTN_MIDDLE) < 0) {
            perror("UI_SET_EVBIT/RELBIT (wheel)");
            close(fd);
            return -1;
        }
    }

#ifdef UI_DEV_SETUP
    struct uinput_setup usetup = {0};
    usetup.id.bustype = BUS_VIRTUAL;
    usetup.id.vendor = 0;
    usetup.id.product = 0;
    usetup.id.version = 2;
    strncpy(usetup.name, g_device_name, sizeof(usetup.name) - 1);
    if (ioctl(fd, UI_DEV_SETUP, &usetup) < 0) {
        perror("UI_DEV_SETUP");
        close(fd);
        return -1;
    }
    if (ioctl(fd, UI_DEV_CREATE) < 0) {
        perror("UI_DEV_CREATE");
        close(fd);
        return -1;
    }
#else
    struct uinput_user_dev uud = {0};
    strncpy(uud.name, g_device_name, sizeof(uud.name) - 1);
    uud.id.bustype = BUS_VIRTUAL;
    uud.id.vendor = 0;
    uud.id.product = 0;
    uud.id.version = 2;
    if (write(fd, &uud, sizeof(uud)) != sizeof(uud)) {
        perror("write uinput_user_dev");
        close(fd);
        return -1;
    }
    if (ioctl(fd, UI_DEV_CREATE) < 0) {
        perror("UI_DEV_CREATE");
        close(fd);
        return -1;
    }
#endif
    return fd;
}

/* One-shot mode: register only the keys this config can actually emit. */
static int open_uinput(const config_t *cfg) {
    bool used[KEY_CNT] = {0};
    for (int i = 0; i < n_sources(cfg); i++)
        if (cfg->target[i] >= 0 && cfg->target[i] < KEY_CNT) used[cfg->target[i]] = true;
    for (int i = 0; i < cfg->n_chords; i++) {
        int t = cfg->chords[i].target;
        if (t >= 0 && t < KEY_CNT) used[t] = true;
    }
    bool uses_wheel = false;
    for (int i = 0; i < n_sources(cfg); i++)
        if (target_is_wheel(cfg->target[i])) uses_wheel = true;
    for (int i = 0; i < cfg->n_chords; i++)
        if (target_is_wheel(cfg->chords[i].target)) uses_wheel = true;
    return open_uinput_dev(used, uses_wheel);
}

/* --serve mode: one device for the daemon's whole lifetime, carrying every
 * key any profile is allowed to bind (see key_in_superset()) and always the
 * pointer bits, so switching to a profile that uses WHEEL_* never means
 * destroying and recreating the device -- which is exactly what makes AYN's
 * mapper service toast "<device> connected". */
static int open_uinput_superset(void) {
    bool used[KEY_CNT] = {0};
    for (int i = 0; i < KEY_TABLE_LEN; i++)
        if (key_in_superset(KEY_TABLE[i].code)) used[KEY_TABLE[i].code] = true;
    return open_uinput_dev(used, true);
}

static void destroy_uinput(int fd) {
    if (fd >= 0) {
        ioctl(fd, UI_DEV_DESTROY);
        close(fd);
    }
}

/* ---- source state -> uinput key, with per-key press counting so a key
 * stays down as long as any source that maps to it is pressed ---- */

static void apply_target_press(int target) {
    if (target == TARGET_NONE) return;
    if (target >= 0) {
        if (target < KEY_CNT && g_key_count[target]++ == 0) emit_key(g_uinput_fd, target, 1);
        return;
    }
    int w = wheel_idx_from_target(target);
    if (w < 0) return;
    if (g_wheel_count[w]++ == 0) {
        emit_wheel_notch(g_uinput_fd, w);
        g_wheel_next_due_ms[w] = now_ms() + g_wheel_repeat_ms;
    }
}

static void apply_target_release(int target) {
    if (target == TARGET_NONE) return;
    if (target >= 0) {
        if (target < KEY_CNT && g_key_count[target] > 0 && --g_key_count[target] == 0)
            emit_key(g_uinput_fd, target, 0);
        return;
    }
    int w = wheel_idx_from_target(target);
    if (w < 0) return;
    if (g_wheel_count[w] > 0) g_wheel_count[w]--;
}

/* Chord resolution for a source about to be pressed: among the chords whose
 * `src` matches, pick the one whose `hold` is currently held, preferring
 * whichever such hold was pressed most recently (g_source_press_seq); fall
 * back to the source's own plain target, else NONE. A hold with no plain
 * binding of its own therefore resolves to NONE when pressed alone -- it is
 * "swallowed" simply because nothing set its target. */
static int resolve_target(const config_t *cfg, int src) {
    int best_target = TARGET_UNKNOWN;
    long long best_seq = -1;
    for (int i = 0; i < cfg->n_chords; i++) {
        const chord_t *c = &cfg->chords[i];
        if (c->src != src || !g_source_pressed[c->hold]) continue;
        if (g_source_press_seq[c->hold] > best_seq) {
            best_seq = g_source_press_seq[c->hold];
            best_target = c->target;
        }
    }
    if (best_target != TARGET_UNKNOWN) return best_target;
    return cfg->target[src];
}

/* Chord/layer resolution happens at PRESS time and is cached in
 * g_resolved_target so RELEASE always targets the same key/wheel even if the
 * holds involved changed in between (no stuck keys). Releasing a hold does
 * NOT release keys already down from a chord it enabled. */
static void update_source(const config_t *cfg, int src, bool pressed) {
    if (g_source_pressed[src] == pressed) return;
    g_source_pressed[src] = pressed;

    if (pressed) {
        g_source_press_seq[src] = ++g_press_seq;
        int target = resolve_target(cfg, src);
        g_resolved_target[src] = target;
        apply_target_press(target);
    } else {
        apply_target_release(g_resolved_target[src]);
    }
    if (g_verbose)
        fprintf(stderr, "dpadkeys: %s -> %s\n", source_name(cfg, src), pressed ? "down" : "up");
}

static void release_all_sources(const config_t *cfg) {
    for (int i = 0; i < n_sources(cfg); i++)
        if (g_source_pressed[i]) update_source(cfg, i, false);
}

/* ---- analog stick / trigger axes ---- */

typedef struct {
    bool present;
    int code;
    int min, max;
    double center, half;
    bool unipolar; /* min >= 0, resting at/near min (a real trigger axis) */
    int state; /* tri-state (-1/0/1) for sticks, 0/1 for triggers */
} axis_t;

typedef struct {
    bool has_hat;       /* ABS_HAT0X/Y present */
    bool has_dpad_btns; /* BTN_DPAD_* present */
    axis_t ls_x, ls_y, rs_x, rs_y, lt, rt;
    /* 2-D stick state: last raw values, active flag, pressed direction bits */
    int ls_rx, ls_ry, rs_rx, rs_ry;
    bool ls_active, rs_active;
    unsigned ls_dirs, rs_dirs;
} axes_t;

/* The pad the daemon is currently driving: identity plus the axis layout
 * detected on it. Global alongside g_pad_fd so serve mode's acquire/release
 * helpers, the event loop and the banner all agree without threading five
 * out-params through every call. */
static char g_pad_path[64] = {0};
static char g_pad_name[128] = {0};
static unsigned short g_pad_vendor = 0, g_pad_product = 0;
static axes_t g_ax;

/* Unipolar: min >= 0 and the axis was resting within 5% of its minimum when
 * queried -- i.e. a real trigger axis (0..N resting at 0), as opposed to a
 * 0..255-style stick axis resting at its midpoint. Overridden to true
 * unconditionally for the trigger axes detect_axes() picks from
 * ABS_GAS/ABS_BRAKE/ABS_Z/ABS_RZ, since those are always triggers even if
 * queried mid-pull. */
static void query_abs(int fd, int code, axis_t *a) {
    struct input_absinfo info;
    if (ioctl(fd, EVIOCGABS(code), &info) < 0) {
        a->present = false;
        return;
    }
    a->present = true;
    a->code = code;
    a->min = info.minimum;
    a->max = info.maximum;
    a->center = (info.minimum + info.maximum) / 2.0;
    a->half = (info.maximum - info.minimum) / 2.0;
    if (a->half <= 0) a->half = 1.0; /* degenerate axis: avoid div-by-zero feel */
    double range = info.maximum - info.minimum;
    a->unipolar = info.minimum >= 0 && info.maximum > 0 &&
                  info.value <= info.minimum + 0.05 * range;
    a->state = 0;
}

static void detect_axes(int fd, axes_t *ax) {
    memset(ax, 0, sizeof(*ax));
    unsigned long keybits[NLONGS(KEY_CNT)] = {0};
    unsigned long absbits[NLONGS(ABS_CNT)] = {0};
    ioctl(fd, EVIOCGBIT(EV_KEY, sizeof(keybits)), keybits);
    ioctl(fd, EVIOCGBIT(EV_ABS, sizeof(absbits)), absbits);

    ax->has_hat = TEST_BIT(ABS_HAT0X, absbits) && TEST_BIT(ABS_HAT0Y, absbits);
    ax->has_dpad_btns = TEST_BIT(BTN_DPAD_UP, keybits);

    if (TEST_BIT(ABS_X, absbits)) query_abs(fd, ABS_X, &ax->ls_x);
    if (TEST_BIT(ABS_Y, absbits)) query_abs(fd, ABS_Y, &ax->ls_y);

    bool has_rx_ry = TEST_BIT(ABS_RX, absbits) && TEST_BIT(ABS_RY, absbits);
    bool has_z_rz = TEST_BIT(ABS_Z, absbits) && TEST_BIT(ABS_RZ, absbits);
    if (has_rx_ry) {
        query_abs(fd, ABS_RX, &ax->rs_x);
        query_abs(fd, ABS_RY, &ax->rs_y);
    } else if (has_z_rz) {
        query_abs(fd, ABS_Z, &ax->rs_x);
        query_abs(fd, ABS_RZ, &ax->rs_y);
    }

    /* Triggers, in priority order: ABS_BRAKE/ABS_GAS (the real triggers on
     * pads like the AYN Odin 2's virtual gamepad, which also expose Z/RZ);
     * else ABS_Z/ABS_RZ, but only if the right stick claimed RX/RY instead
     * (so Z/RZ are free -- otherwise they're the right stick's own axes,
     * not triggers); else ABS_HAT2Y/ABS_HAT2X. Whichever of
     * BRAKE/GAS/Z/RZ is chosen is always treated as unipolar (0-resting),
     * regardless of query_abs()'s resting-value heuristic -- a trigger may
     * be mid-pulled at detect time. */
    if (TEST_BIT(ABS_BRAKE, absbits) && TEST_BIT(ABS_GAS, absbits)) {
        query_abs(fd, ABS_BRAKE, &ax->lt);
        query_abs(fd, ABS_GAS, &ax->rt);
        ax->lt.unipolar = ax->rt.unipolar = true;
    } else if (has_z_rz && has_rx_ry) {
        query_abs(fd, ABS_Z, &ax->lt);
        query_abs(fd, ABS_RZ, &ax->rt);
        ax->lt.unipolar = ax->rt.unipolar = true;
    } else if (TEST_BIT(ABS_HAT2Y, absbits) && TEST_BIT(ABS_HAT2X, absbits)) {
        query_abs(fd, ABS_HAT2Y, &ax->lt);
        query_abs(fd, ABS_HAT2X, &ax->rt);
    }
    ax->ls_rx = (int)ax->ls_x.center; ax->ls_ry = (int)ax->ls_y.center;
    ax->rs_rx = (int)ax->rs_x.center; ax->rs_ry = (int)ax->rs_y.center;
    ax->ls_active = ax->rs_active = false;
    ax->ls_dirs = ax->rs_dirs = 0;
}

/* value > center + deadzone*half => pressed; hysteresis of 0.1*half on
 * release to avoid chatter right at the threshold. */
static int classify_stick(int raw, const axis_t *a, double deadzone) {
    double v = raw, hys = 0.1 * a->half;
    double hi = a->center + deadzone * a->half, lo = a->center - deadzone * a->half;
    double rel_hi = hi - hys, rel_lo = lo + hys;
    if (a->state == 1) return (v <= lo) ? -1 : (v < rel_hi ? 0 : 1);
    if (a->state == -1) return (v >= hi) ? 1 : (v > rel_lo ? 0 : -1);
    if (v >= hi) return 1;
    if (v <= lo) return -1;
    return 0;
}

/* Unipolar (real trigger) axes are thresholded off the minimum, not the
 * midpoint: pressed at min + deadzone*(max-min) (50% pull by default),
 * released below min + (deadzone-0.1)*(max-min). A center-based threshold
 * would put a 0-resting 0..32767 axis' press point at 75% pull instead of
 * 50%. Non-unipolar (bipolar, e.g. a HAT2-fallback trigger) axes keep the
 * old center/half hysteresis formula. */
static bool classify_trigger(int raw, const axis_t *a, double deadzone) {
    double v = raw;
    if (a->unipolar) {
        double range = a->max - a->min;
        if (range <= 0) range = 1.0;
        double press = a->min + deadzone * range;
        double release = a->min + (deadzone - 0.1) * range;
        return a->state ? (v >= release) : (v >= press);
    }
    double hys = 0.1 * a->half;
    double press = a->center + deadzone * a->half;
    return a->state ? (v >= press - hys) : (v >= press);
}

#define DIR_U 1u
#define DIR_D 2u
#define DIR_L 4u
#define DIR_R 8u

/* One axis at rest? Queried straight from the kernel (EVIOCGABS), classified
 * with the same thresholds the event path uses but with the hysteresis state
 * forced to 0, so "at rest" here means the same thing as "would not fire a
 * press" there. An axis we cannot read is treated as at rest: refusing to
 * activate over an ioctl failure would be worse than the phantom it guards. */
static bool abs_axis_at_rest(int fd, const axis_t *a, double deadzone) {
    if (!a->present) return true;
    struct input_absinfo info;
    if (ioctl(fd, EVIOCGABS(a->code), &info) < 0) return true;
    axis_t probe = *a;
    probe.state = 0;
    if (probe.unipolar) return !classify_trigger(info.value, &probe, deadzone);
    return classify_stick(info.value, &probe, deadzone) == 0;
}

/* True if nothing on the pad is being held: no button among the device's own
 * EV_KEY bits is down, every detected stick/trigger axis is inside the
 * deadzone, and the hat is centred. `why` (optional) gets a short description
 * of the first thing found held, for the cap warning.
 *
 * Requires detect_axes() to have run on `fd` -- all read-only ioctls, so it is
 * safe to call before the grab. Raw `abs:` sources are not re-checked
 * separately: in practice they name one of the axes below, and every raw
 * `key:` source is covered by the EV_KEY scan. */
static bool pad_neutral(int fd, const config_t *cfg, char *why, size_t whysz) {
    if (why && whysz) why[0] = '\0';
    unsigned long keybits[NLONGS(KEY_CNT)] = {0}, keystate[NLONGS(KEY_CNT)] = {0};
    ioctl(fd, EVIOCGBIT(EV_KEY, sizeof(keybits)), keybits);
    if (ioctl(fd, EVIOCGKEY(sizeof(keystate)), keystate) >= 0) {
        for (int c = 0; c < KEY_CNT; c++) {
            if (!TEST_BIT(c, keybits) || !TEST_BIT(c, keystate)) continue;
            if (why && whysz) snprintf(why, whysz, "key 0x%x is held", c);
            return false;
        }
    }
    static const char *anames[] = { "left stick X", "left stick Y", "right stick X",
                                    "right stick Y", "left trigger", "right trigger" };
    const axis_t *axes[6] = { &g_ax.ls_x, &g_ax.ls_y, &g_ax.rs_x,
                              &g_ax.rs_y, &g_ax.lt, &g_ax.rt };
    for (int i = 0; i < 6; i++) {
        if (abs_axis_at_rest(fd, axes[i], cfg->deadzone)) continue;
        if (why && whysz) snprintf(why, whysz, "%s is off rest", anames[i]);
        return false;
    }
    if (g_ax.has_hat) {
        struct input_absinfo info;
        if (ioctl(fd, EVIOCGABS(ABS_HAT0X), &info) == 0 && info.value != 0) {
            if (why && whysz) snprintf(why, whysz, "the d-pad is held");
            return false;
        }
        if (ioctl(fd, EVIOCGABS(ABS_HAT0Y), &info) == 0 && info.value != 0) {
            if (why && whysz) snprintf(why, whysz, "the d-pad is held");
            return false;
        }
    }
    return true;
}

/* 2-D stick -> 8-way digital. Magnitude gate (deadzone, with 0.1 hysteresis),
 * then the angle picks one of eight 45-degree sectors; diagonal sectors press
 * two keys. A 4-degree hysteresis on sector boundaries avoids chatter. */
static void handle_stick_2d(const config_t *cfg, const axis_t *ax, const axis_t *ay,
                            int rx, int ry, bool *active, unsigned *dirs, double deadzone,
                            bool invert_x, bool invert_y,
                            source_id_t up, source_id_t down, source_id_t left, source_id_t right) {
    static const unsigned SECT[8] = { DIR_R, DIR_R|DIR_U, DIR_U, DIR_U|DIR_L,
                                      DIR_L, DIR_L|DIR_D, DIR_D, DIR_D|DIR_R };
    double nx = (rx - ax->center) / ax->half;
    if (invert_x) nx = -nx;
    double ny = (ry - ay->center) / ay->half;
    if (invert_y) ny = -ny;
    double mag = sqrt(nx * nx + ny * ny);
    bool act = *active ? (mag >= deadzone - 0.1) : (mag >= deadzone);
    unsigned nd = 0;
    if (act) {
        double ang = atan2(-ny, nx) * 180.0 / M_PI; /* raw y is inverted: up = +90 */
        if (ang < 0) ang += 360.0;
        int k = (int)floor((ang + 22.5) / 45.0) % 8;
        if (*dirs) {
            /* stay in the current sector while within 4 degrees past its edge */
            int cur = -1;
            for (int i = 0; i < 8; i++) if (SECT[i] == *dirs) { cur = i; break; }
            if (cur >= 0 && cur != k) {
                double c = cur * 45.0, d = fabs(ang - c);
                if (d > 180.0) d = 360.0 - d;
                if (d <= 22.5 + 4.0) k = cur;
            }
        }
        nd = SECT[k];
    }
    *active = act;
    if (nd == *dirs) return;
    update_source(cfg, up,    (nd & DIR_U) != 0);
    update_source(cfg, down,  (nd & DIR_D) != 0);
    update_source(cfg, left,  (nd & DIR_L) != 0);
    update_source(cfg, right, (nd & DIR_R) != 0);
    *dirs = nd;
}

static void handle_trigger_axis(const config_t *cfg, axis_t *a, int raw,
                                 source_id_t src, double deadzone) {
    bool pressed = classify_trigger(raw, a, deadzone);
    if (pressed == (bool)a->state) return;
    a->state = pressed;
    update_source(cfg, src, pressed);
}

/* ---- raw sources ---- */

/* Query the axis range for every raw abs source (called after the pad is
 * opened / reacquired). Missing axes stay axis_known=false and never fire. */
static void raw_query_axes(int fd, config_t *cfg) {
    for (int i = 0; i < cfg->n_raw; i++) {
        raw_source_t *r = &cfg->raw[i];
        if (r->kind != RAW_ABS) continue;
        struct input_absinfo info;
        if (ioctl(fd, EVIOCGABS(r->code), &info) < 0) { r->axis_known = false; continue; }
        r->axis_known = true;
        r->axis.min = info.minimum; r->axis.max = info.maximum;
        r->axis.center = (info.minimum + info.maximum) / 2.0;
        r->axis.half = (info.maximum - info.minimum) / 2.0;
        if (r->axis.half <= 0) r->axis.half = 1.0;
        double range = info.maximum - info.minimum;
        r->axis.unipolar = info.minimum >= 0 && info.maximum > 0 &&
                            info.value <= info.minimum + 0.05 * range;
        r->axis.state = 0;
    }
}

/* A raw abs source addressing the same physical axis code the daemon chose
 * as lt/rt inherits that axis' (possibly forced-true) unipolar flag, so
 * e.g. abs.0x9.pos agrees with rt on an AYN-style GAS/BRAKE pad even though
 * the per-axis heuristic alone would already have gotten it right. Called
 * after both detect_axes() and raw_query_axes(). */
static void propagate_trigger_unipolar(config_t *cfg, const axes_t *ax) {
    for (int i = 0; i < cfg->n_raw; i++) {
        raw_source_t *r = &cfg->raw[i];
        if (r->kind != RAW_ABS || !r->axis_known) continue;
        if (ax->lt.present && r->code == ax->lt.code) r->axis.unipolar = ax->lt.unipolar;
        else if (ax->rt.present && r->code == ax->rt.code) r->axis.unipolar = ax->rt.unipolar;
    }
}

/* Raw abs threshold: same deadzone + 0.1 hysteresis as triggers, mirrored
 * for `.neg`. A hat-like -1..1 axis therefore fires exactly at -1 / +1. A
 * unipolar axis (see raw_query_axes()/propagate_trigger_unipolar()) is
 * thresholded off its minimum like classify_trigger(); `.neg` is rejected
 * for such an axis at load time (raw_unipolar_neg_reject()), so `r->dir` is
 * always +1 here in practice. */
static bool raw_abs_pressed(const raw_source_t *r, int value, double deadzone) {
    if (r->axis.unipolar) {
        double range = r->axis.max - r->axis.min;
        if (range <= 0) range = 1.0;
        double press = r->axis.min + deadzone * range;
        double release = r->axis.min + (deadzone - 0.1) * range;
        return r->axis.state ? (value >= release) : (value >= press);
    }
    double v = (value - r->axis.center) / r->axis.half;
    if (r->dir < 0) v = -v;
    return r->axis.state ? (v >= deadzone - 0.1) : (v >= deadzone);
}

static void handle_raw_key(config_t *cfg, int code, bool pressed) {
    for (int i = 0; i < cfg->n_raw; i++) {
        raw_source_t *r = &cfg->raw[i];
        if (r->kind == RAW_KEY && r->code == code) update_source(cfg, SRC_COUNT + i, pressed);
    }
}

static void handle_raw_abs(config_t *cfg, int code, int value) {
    for (int i = 0; i < cfg->n_raw; i++) {
        raw_source_t *r = &cfg->raw[i];
        if (r->kind != RAW_ABS || r->code != code || !r->axis_known) continue;
        bool pressed = raw_abs_pressed(r, value, cfg->deadzone);
        if (pressed == (bool)r->axis.state) continue;
        r->axis.state = pressed;
        update_source(cfg, SRC_COUNT + i, pressed);
    }
}

/* Stick/trigger axis codes are only known once the pad is open: refuse a
 * raw abs source that addresses an axis a mapped semantic source already
 * consumes (e.g. abs.0x00.neg next to ls.left). Returns NULL or a message. */
static const char *raw_axis_collision(const config_t *cfg, const axes_t *ax) {
    static char msg[96];
    for (int i = 0; i < cfg->n_raw; i++) {
        const raw_source_t *r = &cfg->raw[i];
        if (r->kind != RAW_ABS) continue;
        const char *sem = NULL;
        if (ax->ls_x.present && ax->ls_y.present && (r->code == ax->ls_x.code || r->code == ax->ls_y.code)) {
            for (int s = SRC_LS_UP; s <= SRC_LS_RIGHT; s++) if (cfg->defined[s]) sem = "ls.*";
        } else if (ax->rs_x.present && ax->rs_y.present && (r->code == ax->rs_x.code || r->code == ax->rs_y.code)) {
            for (int s = SRC_RS_UP; s <= SRC_RS_RIGHT; s++) if (cfg->defined[s]) sem = "rs.*";
        } else if (ax->lt.present && r->code == ax->lt.code) {
            if (cfg->defined[SRC_LT]) sem = "lt";
        } else if (ax->rt.present && r->code == ax->rt.code) {
            if (cfg->defined[SRC_RT]) sem = "rt";
        }
        if (sem) {
            snprintf(msg, sizeof(msg), "raw source '%s' collides with '%s' on this pad", r->name, sem);
            return msg;
        }
    }
    return NULL;
}

/* A unipolar axis (real trigger: min >= 0, resting at/near min) never goes
 * negative, so `abs.0xNN.neg` can never fire on one -- reject it once the
 * device's axis range is known (after raw_query_axes()/
 * propagate_trigger_unipolar()), suggesting `.pos` and, if this raw source
 * addresses the axis chosen as lt/rt, that semantic name too. */
static const char *raw_unipolar_neg_reject(const config_t *cfg, const axes_t *ax) {
    static char msg[160];
    for (int i = 0; i < cfg->n_raw; i++) {
        const raw_source_t *r = &cfg->raw[i];
        if (r->kind != RAW_ABS || r->dir >= 0) continue; /* only .neg */
        if (!r->axis_known || !r->axis.unipolar) continue;
        char pos_name[32];
        snprintf(pos_name, sizeof(pos_name), "abs.0x%x.pos", (unsigned)r->code);
        if (ax->lt.present && r->code == ax->lt.code)
            snprintf(msg, sizeof(msg), "raw source '%s' addresses a unipolar axis (min=%d,max=%d); "
                     "'.neg' can never fire -- use '%s' or 'lt' instead", r->name, r->axis.min, r->axis.max, pos_name);
        else if (ax->rt.present && r->code == ax->rt.code)
            snprintf(msg, sizeof(msg), "raw source '%s' addresses a unipolar axis (min=%d,max=%d); "
                     "'.neg' can never fire -- use '%s' or 'rt' instead", r->name, r->axis.min, r->axis.max, pos_name);
        else
            snprintf(msg, sizeof(msg), "raw source '%s' addresses a unipolar axis (min=%d,max=%d); "
                     "'.neg' can never fire -- use '%s' instead", r->name, r->axis.min, r->axis.max, pos_name);
        return msg;
    }
    return NULL;
}

/* ---- pad detection ---- */

/* Returns 0 and fills fields if fd is a readable evdev node. */
static int device_info(int fd, unsigned short *vendor, unsigned short *product,
                        char *name, size_t namelen, bool *has_south) {
    struct input_id id;
    if (ioctl(fd, EVIOCGID, &id) < 0)
        return -1;
    *vendor = id.vendor;
    *product = id.product;
    if (ioctl(fd, EVIOCGNAME(namelen), name) < 0)
        strncpy(name, "?", namelen);

    unsigned long keybits[NLONGS(KEY_CNT)] = {0};
    ioctl(fd, EVIOCGBIT(EV_KEY, sizeof(keybits)), keybits);
    *has_south = TEST_BIT(BTN_SOUTH, keybits);
    return 0;
}

static void list_devices(void) {
    char path[64];
    for (int i = 0; i < 32; i++) {
        snprintf(path, sizeof(path), "/dev/input/event%d", i);
        int fd = open(path, O_RDONLY);
        if (fd < 0) {
            if (errno == EACCES)
                printf("%s: permission denied\n", path);
            continue;
        }
        unsigned short vendor, product;
        char name[128] = {0};
        bool has_south = false;
        struct input_id id;
        ioctl(fd, EVIOCGID, &id);
        device_info(fd, &vendor, &product, name, sizeof(name), &has_south);
        axes_t ax;
        detect_axes(fd, &ax);
        unsigned long propbits[NLONGS(INPUT_PROP_CNT)] = {0};
        unsigned long absbits[NLONGS(ABS_CNT)] = {0};
        ioctl(fd, EVIOCGPROP(sizeof(propbits)), propbits);
        ioctl(fd, EVIOCGBIT(EV_ABS, sizeof(absbits)), absbits);
        bool direct = TEST_BIT(INPUT_PROP_DIRECT, propbits);
        bool mt = TEST_BIT(ABS_MT_POSITION_X, absbits);
        bool virt = evdev_is_virtual(path);
        printf("%s: bus=0x%04x vendor=0x%04x product=0x%04x name=\"%s\" "
               "BTN_SOUTH=%s hat=%s dpad_btns=%s ls=%s rs=%s lt=%s rt=%s "
               "DIRECT=%s MT=%s virtual=%s\n",
               path, id.bustype, vendor, product, name, has_south ? "yes" : "no",
               ax.has_hat ? "yes" : "no", ax.has_dpad_btns ? "yes" : "no",
               ax.ls_x.present ? "yes" : "no", ax.rs_x.present ? "yes" : "no",
               ax.lt.present ? "yes" : "no", ax.rt.present ? "yes" : "no",
               direct ? "yes" : "no", mt ? "yes" : "no", virt ? "yes" : "no");
        close(fd);
    }
}

/* True if `match` (a case-insensitive substring of the name, or "vvvv:pppp"
 * hex) selects this device. */
static bool device_matches(const char *match, const char *name, unsigned short vendor, unsigned short product) {
    if (!match || !*match) return true;
    unsigned v, p;
    char tail;
    if (sscanf(match, "%x:%x%c", &v, &p, &tail) == 2) return v == vendor && p == product;
    size_t ml = strlen(match), nl = strlen(name);
    for (size_t i = 0; i + ml <= nl; i++)
        if (strncasecmp(name + i, match, ml) == 0) return true;
    return false;
}

/* Scans /dev/input/event* for a gamepad: any node whose EV_KEY set includes
 * BTN_SOUTH (== BTN_GAMEPAD). With several candidates the first one matching
 * `match` wins, else the first found. Returns an opened fd or -1. */
static int find_pad(const char *match, int open_flags, char *path_out, size_t pathlen,
                    char *name_out, size_t namelen,
                    unsigned short *vendor_out, unsigned short *product_out) {
    char path[64];
    int best_fd = -1;
    bool best_matched = false;
    for (int i = 0; i < 32; i++) {
        snprintf(path, sizeof(path), "/dev/input/event%d", i);
        int fd = open(path, open_flags);
        if (fd < 0)
            continue;
        unsigned short vendor, product;
        char name[128] = {0};
        bool has_south = false;
        if (device_info(fd, &vendor, &product, name, sizeof(name), &has_south) < 0 || !has_south) {
            close(fd);
            continue;
        }
        bool matched = device_matches(match, name, vendor, product);
        if (best_fd < 0 || (matched && !best_matched)) {
            if (best_fd >= 0) close(best_fd);
            best_fd = fd;
            best_matched = matched;
            strncpy(path_out, path, pathlen - 1);
            strncpy(name_out, name, namelen - 1);
            *vendor_out = vendor;
            *product_out = product;
            if (matched && match && *match) break;
        } else {
            close(fd);
        }
    }
    return best_fd;
}

/* ---- touch pass-through ---- */

/* True if /dev/input/eventN is a kernel-virtual (uinput) node.
 *
 * Rationale for this test: on re-detect we must not "discover" our own clone
 * of the panel.  The obvious tag would be a uniq string, but the uinput ABI
 * has no UI_SET_UNIQ ioctl (see linux/uinput.h: UI_SET_PHYS exists, there is
 * no UNIQ counterpart), so a uinput device's EVIOCGUNIQ is always empty and
 * cannot be used to mark ourselves.  EVIOCGID is no help either: the clone
 * copies the panel's bus/vendor/product/version by design.  What *does*
 * separate them is where the input device hangs in sysfs: the real panel is
 * an i2c client, so /sys/class/input/eventN/device resolves under
 * /sys/devices/platform/..., while every uinput device (ours or anyone
 * else's) resolves under /sys/devices/virtual/input/.  That is stable,
 * readable without any ioctl, and is what --list reports as virtual=yes/no.
 * UI_SET_PHYS("dpadkeys-touch") is set as well and checked as a secondary
 * tag, so the daemon still skips its own device if sysfs is unavailable. */
static bool evdev_is_virtual(const char *devpath) {
    const char *base = strrchr(devpath, '/');
    base = base ? base + 1 : devpath;
    char link[128];
    snprintf(link, sizeof(link), "/sys/class/input/%s/device", base);
    char resolved[PATH_MAX];
    if (!realpath(link, resolved)) return false;
    return strncmp(resolved, "/sys/devices/virtual/", strlen("/sys/devices/virtual/")) == 0;
}

/* True if fd/path is a direct-input multitouch panel (INPUT_PROP_DIRECT and
 * ABS_MT_POSITION_X) that isn't a virtual device. */
static bool touch_candidate_ok(int fd, const char *path) {
    unsigned long propbits[NLONGS(INPUT_PROP_CNT)] = {0};
    if (ioctl(fd, EVIOCGPROP(sizeof(propbits)), propbits) < 0) return false;
    if (!TEST_BIT(INPUT_PROP_DIRECT, propbits)) return false;
    unsigned long absbits[NLONGS(ABS_CNT)] = {0};
    ioctl(fd, EVIOCGBIT(EV_ABS, sizeof(absbits)), absbits);
    if (!TEST_BIT(ABS_MT_POSITION_X, absbits)) return false;
    if (evdev_is_virtual(path)) return false;
    char buf[64] = {0};
    if (ioctl(fd, EVIOCGPHYS(sizeof(buf)), buf) == 0 && strcmp(buf, TOUCH_SELF_TAG) == 0) return false;
    buf[0] = '\0';
    if (ioctl(fd, EVIOCGUNIQ(sizeof(buf)), buf) == 0 && strcmp(buf, TOUCH_SELF_TAG) == 0) return false;
    return true;
}

/* Opens the configured touch device: an explicit touch.device path, or (auto)
 * the first /dev/input/event* passing touch_candidate_ok(). Returns an opened
 * fd (path_out filled) or -1. Read-only and non-blocking: we never write to
 * the panel, and the poll loop must not block inside a partial frame. */
static int find_touch(const config_t *cfg, char *path_out, size_t pathlen) {
    if (cfg->touch_device[0]) {
        int fd = open(cfg->touch_device, O_RDONLY | O_NONBLOCK);
        if (fd < 0) return -1;
        /* An explicit path skips the DIRECT/MT heuristics, but still has to
         * be an evdev node -- open() happily succeeds on a directory or an
         * unrelated char device, and every ioctl below would then read junk. */
        struct input_id probe;
        if (ioctl(fd, EVIOCGID, &probe) < 0) {
            fprintf(stderr, "dpadkeys: touch: '%s' is not an evdev node: %s\n",
                    cfg->touch_device, strerror(errno));
            close(fd);
            return -1;
        }
        snprintf(path_out, pathlen, "%s", cfg->touch_device);
        return fd;
    }
    for (int i = 0; i < 32; i++) {
        char path[64];
        snprintf(path, sizeof(path), "/dev/input/event%d", i);
        int fd = open(path, O_RDONLY | O_NONBLOCK);
        if (fd < 0) continue;
        if (!touch_candidate_ok(fd, path)) { close(fd); continue; }
        snprintf(path_out, pathlen, "%s", path);
        return fd;
    }
    return -1;
}

/* Refreshes the X/Y and MT_POSITION_X/Y axis ranges used to clamp the offset
 * touch events, from whichever real panel fd is currently open. */
static void query_touch_axes(int fd) {
    g_touch_has_mtx = g_touch_has_mty = g_touch_has_x = g_touch_has_y = false;
    struct input_absinfo info;
    if (ioctl(fd, EVIOCGABS(ABS_MT_POSITION_X), &info) == 0) { g_touch_mtx_info = info; g_touch_has_mtx = true; }
    if (ioctl(fd, EVIOCGABS(ABS_MT_POSITION_Y), &info) == 0) { g_touch_mty_info = info; g_touch_has_mty = true; }
    if (ioctl(fd, EVIOCGABS(ABS_X), &info) == 0) { g_touch_x_info = info; g_touch_has_x = true; }
    if (ioctl(fd, EVIOCGABS(ABS_Y), &info) == 0) { g_touch_y_info = info; g_touch_has_y = true; }
    /* Slot geometry drives every per-slot array below. A panel that reports no
     * ABS_MT_SLOT is single-slot MT-A; treat it as one slot so BTN_TOUCH and
     * slot 0 are still tracked. */
    if (ioctl(fd, EVIOCGABS(ABS_MT_SLOT), &info) == 0 && info.maximum >= info.minimum) {
        g_touch_slot_min = info.minimum;
        int n = info.maximum - info.minimum + 1;
        g_touch_nslots = n > TOUCH_MAX_SLOTS ? TOUCH_MAX_SLOTS : n;
    } else {
        g_touch_slot_min = 0;
        g_touch_nslots = 1;
    }
}

/* True if an EVIOCGMTSLOTS position is inside the panel's own reported range.
 * A slot can hold a stale id with a nonsense position across a re-open, and
 * carrying that over is how a pointer ends up nailed to a screen edge. */
static bool touch_pos_in_range(int x, int y) {
    if (g_touch_has_mtx && (x < g_touch_mtx_info.minimum || x > g_touch_mtx_info.maximum)) return false;
    if (g_touch_has_mty && (y < g_touch_mty_info.minimum || y > g_touch_mty_info.maximum)) return false;
    return true;
}

/* ---- touch.display: panel-raw -> display-space coordinate transform ----
 *
 * Why: Android maps our cloned touchscreen onto the display with AXIS
 * SCALING ONLY (raw X -> display X * W/Xmax, raw Y -> display Y * H/Ymax,
 * no rotation), while the real panel gets the correct rotation from its own
 * input-device orientation config. A clone that just mirrors panel-native
 * coordinates therefore lands in the wrong place whenever the display's
 * natural orientation (R quarter-turns from the panel's own portrait frame)
 * is not 0. touch.display fixes this by having the daemon itself transform
 * every position into display space before writing it to the clone, so the
 * clone can be dumb-scaled by Android exactly like the real panel is.
 *
 * The four rotations, panel-raw (px in [0,xspan], py in [0,yspan]) ->
 * display (dx in [0,W-1], dy in [0,H-1]):
 *   R=0: dx = px*W/Xmax,        dy = py*H/Ymax
 *   R=1: dx = py*W/Ymax,        dy = H - px*H/Xmax
 *   R=2: dx = (Xmax-px)*W/Xmax, dy = (Ymax-py)*H/Ymax
 *   R=3: dx = W - py*W/Ymax,    dy = px*H/Xmax
 * In every case dx is a function of exactly one raw axis and dy of the
 * other -- R=0/2 keep X feeding dx and Y feeding dy, R=1/3 swap them -- so
 * each incoming ABS_MT_POSITION_X/Y (or ABS_X/Y) event can be remapped on
 * its own, without waiting to see its paired axis; touch_axis_to_display()
 * below is exactly this per-axis map, and touch_display_transform() (used
 * only by --selftest-transform) is the two-argument form for testing both
 * axes of a point at once. */

/* Rounds num/den to the nearest integer (num,den assumed >= 0). */
static long long touch_round_div(long long num, long long den) {
    if (den <= 0) return 0;
    if (num < 0) num = 0;
    return (num + den / 2) / den;
}

/* Maps one raw axis value `rel` (already relative to that axis' own minimum,
 * i.e. in [0,span]) into a display coordinate for display rotation `r`.
 * `is_x` says whether `rel` came from the panel's X axis (true) or Y axis
 * (false); *out_is_x reports whether the returned value feeds the display's
 * X or Y axis -- they swap for R=1/3. Result is unclamped; callers clamp to
 * [0,w-1]/[0,h-1] as appropriate. */
static long long touch_axis_to_display(bool is_x, long long rel, long long span,
                                        int w, int h, int r, bool *out_is_x) {
    r = ((r % 4) + 4) % 4;
    if (is_x) {
        switch (r) {
            case 0: *out_is_x = true;  return touch_round_div(rel * w, span);
            case 2: *out_is_x = true;  return touch_round_div((span - rel) * w, span);
            case 1: *out_is_x = false; return (long long)h - touch_round_div(rel * h, span);
            default /* 3 */: *out_is_x = false; return touch_round_div(rel * h, span);
        }
    } else {
        switch (r) {
            case 0: *out_is_x = false; return touch_round_div(rel * h, span);
            case 2: *out_is_x = false; return touch_round_div((span - rel) * h, span);
            case 1: *out_is_x = true;  return touch_round_div(rel * w, span);
            default /* 3 */: *out_is_x = true;  return (long long)w - touch_round_div(rel * w, span);
        }
    }
}

/* Two-axis form of touch_axis_to_display(), for --selftest-transform: px/py
 * are panel-raw positions relative to their own axis minimum (so xspan/yspan
 * are Xmax/Ymax when the panel's minimum is 0, as on every touchscreen this
 * has been tested against), r is the display rotation. Result clamped to
 * [0,w-1]/[0,h-1]. */
static void touch_display_transform(long long px, long long py, long long xspan, long long yspan,
                                     int w, int h, int r, int *out_dx, int *out_dy) {
    bool x_is_dx, y_is_dx;
    long long vx = touch_axis_to_display(true, px, xspan, w, h, r, &x_is_dx);
    long long vy = touch_axis_to_display(false, py, yspan, w, h, r, &y_is_dx);
    long long dx = x_is_dx ? vx : vy;
    long long dy = x_is_dx ? vy : vx;
    if (dx < 0) dx = 0; else if (dx > w - 1) dx = w - 1;
    if (dy < 0) dy = 0; else if (dy > h - 1) dy = h - 1;
    *out_dx = (int)dx;
    *out_dy = (int)dy;
}

/* Display-space counterpart of offset_touch_event_panel(): remaps one event
 * from panel-raw into display coordinates (touch_axis_to_display(), which may
 * swap ABS_MT_POSITION_X<->Y or ABS_X<->Y for R=1/3 -- exactly what the
 * panel-space path could never do without recreating the clone), THEN adds
 * the stylus offset in display pixels, clamped, THEN applies touch_rotation
 * (kept for compatibility) as a mirror in display space. Non-position codes
 * pass through untouched. */
static void offset_touch_event_display(const config_t *cfg, struct input_event *ev) {
    if (ev->type != EV_ABS) return;
    bool is_x, is_mt;
    const struct input_absinfo *info;
    if (ev->code == ABS_MT_POSITION_X && g_touch_has_mtx) { is_x = true; is_mt = true; info = &g_touch_mtx_info; }
    else if (ev->code == ABS_MT_POSITION_Y && g_touch_has_mty) { is_x = false; is_mt = true; info = &g_touch_mty_info; }
    else if (ev->code == ABS_X && g_touch_has_x) { is_x = true; is_mt = false; info = &g_touch_x_info; }
    else if (ev->code == ABS_Y && g_touch_has_y) { is_x = false; is_mt = false; info = &g_touch_y_info; }
    else return;

    long long span = (long long)info->maximum - (long long)info->minimum;
    long long rel = (long long)ev->value - (long long)info->minimum;
    if (span <= 0) span = 1;
    if (rel < 0) rel = 0; else if (rel > span) rel = span;

    bool out_is_x;
    long long outv = touch_axis_to_display(is_x, rel, span,
                                            cfg->touch_display_w, cfg->touch_display_h,
                                            cfg->touch_display_r, &out_is_x);
    int dim = out_is_x ? cfg->touch_display_w : cfg->touch_display_h;
    if (outv < 0) outv = 0; else if (outv > dim - 1) outv = dim - 1;

    outv += out_is_x ? cfg->touch_dx : cfg->touch_dy;
    if (outv < 0) outv = 0; else if (outv > dim - 1) outv = dim - 1;

    if (cfg->touch_rotation == 2) {
        outv = (long long)(dim - 1) - outv;
    } else if (cfg->touch_rotation == 1 || cfg->touch_rotation == 3) {
        static bool warned = false;
        if (!warned) {
            warned = true;
            fprintf(stderr, "dpadkeys: touch: rotation %d (90/270) is not supported together "
                            "with touch.display; ignoring\n", cfg->touch_rotation);
            fflush(stderr);
        }
    }

    ev->code = out_is_x ? (unsigned short)(is_mt ? ABS_MT_POSITION_X : ABS_X)
                         : (unsigned short)(is_mt ? ABS_MT_POSITION_Y : ABS_Y);
    ev->value = (int)outv;
}

/* Applies the configured rotation-compensation and offset to one event in
 * place, clamped to that axis' reported range. Only the four position axes are
 * touched; MAJOR, SLOT, TRACKING_ID, BTN_TOUCH, timestamps and everything else
 * pass through.
 *
 * Rotation (cfg->touch_rotation, quarter-turns) is applied FIRST, then the
 * stylus offset, both in the clone's (panel-native) coordinate box:
 *   0  passthrough -- byte-for-byte the old behaviour.
 *   2  180 flip: each position axis is mirrored about its own centre
 *      (v -> min+max-v). A 180 rotation is separable per axis, so BTN_TOUCH,
 *      slots and tracking ids are untouched and no cross-axis state is needed.
 *   1,3  90/270: cannot be represented on a panel-native (portrait) clone
 *      without swapping the X/Y ranges, which would require recreating the
 *      clone with landscape axes. Not reachable on the Odin (forced landscape
 *      only ever flips 1<->3, i.e. delta 0 or 2); warned once and passed
 *      through so a misconfig degrades to "no rotation" rather than garbage. */
static void offset_touch_event_panel(const config_t *cfg, struct input_event *ev) {
    if (ev->type != EV_ABS) return;
    int delta;
    const struct input_absinfo *info;
    if (ev->code == ABS_MT_POSITION_X && g_touch_has_mtx) { delta = cfg->touch_dx; info = &g_touch_mtx_info; }
    else if (ev->code == ABS_MT_POSITION_Y && g_touch_has_mty) { delta = cfg->touch_dy; info = &g_touch_mty_info; }
    else if (ev->code == ABS_X && g_touch_has_x) { delta = cfg->touch_dx; info = &g_touch_x_info; }
    else if (ev->code == ABS_Y && g_touch_has_y) { delta = cfg->touch_dy; info = &g_touch_y_info; }
    else return;
    long v = ev->value;
    if (cfg->touch_rotation == 2) {
        v = (long)info->minimum + (long)info->maximum - v;   /* mirror this axis */
    } else if (cfg->touch_rotation == 1 || cfg->touch_rotation == 3) {
        static bool warned = false;
        if (!warned) {
            warned = true;
            fprintf(stderr, "dpadkeys: touch: rotation %d (90/270) needs a landscape "
                            "clone; ignoring, passing coordinates through\n", cfg->touch_rotation);
            fflush(stderr);
        }
    }
    v += delta;
    if (v < info->minimum) v = info->minimum;
    if (v > info->maximum) v = info->maximum;
    ev->value = (int)v;
}

/* Single choke point every call site uses: dispatches to the display-space
 * transform when touch.display is configured, otherwise to the exact
 * pre-existing panel-space behaviour. This is deliberately the only place
 * that branches on touch_display_set, so both touch_sync_initial_contacts()
 * and forward_touch_batch() -- the two places positions are written to the
 * clone -- get the transform for free without knowing which mode is active. */
static void offset_touch_event(const config_t *cfg, struct input_event *ev) {
    if (cfg->touch_display_set) offset_touch_event_display(cfg, ev);
    else offset_touch_event_panel(cfg, ev);
}

/* Creates the virtual touchscreen once at startup, copying the real panel's
 * EV_KEY/EV_ABS capabilities (with identical absinfo via UI_ABS_SETUP) and
 * INPUT_PROP bits, name "<device name> Touch" (dev_name, see g_touch_device_name),
 * and bus/vendor/product/version from the
 * panel's EVIOCGID. Kept alive across panel re-detects.
 *
 * Android: EventHub classifies this as a second internal (bus 0x18 is neither
 * USB nor Bluetooth, so isExternalDeviceLocked() is false) INPUT_PROP_DIRECT
 * MT device, so InputReader instantiates a TouchInputMapper whose
 * computeParameters() sets deviceType=TOUCH_SCREEN / hasAssociatedDisplay,
 * and configureSurface() binds it to the internal viewport (display 0). No
 * .idc file is needed for that. The descriptor EventHub derives from
 * bus/vendor/product/version would collide with the real panel's, but
 * EventHub::assignDescriptorLocked() salts duplicates with an incrementing
 * nonce until the descriptor is unique, so both devices coexist.
 *
 * When cfg->touch_display_set, the position axes are NOT cloned verbatim:
 * ABS_MT_POSITION_X/Y (and ABS_X/Y, if the panel has them) get range
 * [0,W-1]/[0,H-1], resolution 0, since the daemon is about to feed them
 * display-space coordinates instead of panel-raw ones (see
 * offset_touch_event_display()) and the ABS range is fixed for the life of
 * the clone. fuzz/flat are zeroed too -- the panel's noise-filtering
 * thresholds were tuned for its own raw scale and mean nothing at the
 * display's. */
static int open_touch_uinput(int real_fd, const struct input_id *id, const char *dev_name,
                              const config_t *cfg) {
    int fd = open("/dev/uinput", O_WRONLY | O_NONBLOCK);
    if (fd < 0) {
        perror("open /dev/uinput (touch)");
        return -1;
    }
    if (ioctl(fd, UI_SET_EVBIT, EV_SYN) < 0 ||
        ioctl(fd, UI_SET_EVBIT, EV_KEY) < 0 ||
        ioctl(fd, UI_SET_EVBIT, EV_ABS) < 0) {
        perror("UI_SET_EVBIT (touch)");
        close(fd);
        return -1;
    }

    unsigned long keybits[NLONGS(KEY_CNT)] = {0};
    ioctl(real_fd, EVIOCGBIT(EV_KEY, sizeof(keybits)), keybits);
    for (int c = 0; c < KEY_CNT; c++) {
        if (TEST_BIT(c, keybits) && ioctl(fd, UI_SET_KEYBIT, c) < 0) {
            perror("UI_SET_KEYBIT (touch)");
            close(fd);
            return -1;
        }
    }

    unsigned long absbits[NLONGS(ABS_CNT)] = {0};
    ioctl(real_fd, EVIOCGBIT(EV_ABS, sizeof(absbits)), absbits);
    for (int c = 0; c < ABS_CNT; c++) {
        if (!TEST_BIT(c, absbits)) continue;
        struct input_absinfo info;
        if (ioctl(real_fd, EVIOCGABS(c), &info) < 0) continue;
        struct uinput_abs_setup as;
        memset(&as, 0, sizeof(as));
        as.code = (unsigned short)c;
        as.absinfo = info;
        /* The clone starts with no contacts down, so never inherit a live
         * value; UI_ABS_SETUP also implies UI_SET_ABSBIT. */
        as.absinfo.value = 0;
        if (cfg->touch_display_set && (c == ABS_MT_POSITION_X || c == ABS_X)) {
            as.absinfo.minimum = 0;
            as.absinfo.maximum = cfg->touch_display_w - 1;
            as.absinfo.resolution = 0;
            as.absinfo.fuzz = 0;
            as.absinfo.flat = 0;
        } else if (cfg->touch_display_set && (c == ABS_MT_POSITION_Y || c == ABS_Y)) {
            as.absinfo.minimum = 0;
            as.absinfo.maximum = cfg->touch_display_h - 1;
            as.absinfo.resolution = 0;
            as.absinfo.fuzz = 0;
            as.absinfo.flat = 0;
        }
        if (ioctl(fd, UI_ABS_SETUP, &as) < 0) {
            perror("UI_ABS_SETUP (touch)");
            close(fd);
            return -1;
        }
    }

    unsigned long propbits[NLONGS(INPUT_PROP_CNT)] = {0};
    ioctl(real_fd, EVIOCGPROP(sizeof(propbits)), propbits);
    for (int p = 0; p < INPUT_PROP_CNT; p++) {
        if (TEST_BIT(p, propbits) && ioctl(fd, UI_SET_PROPBIT, p) < 0) {
            perror("UI_SET_PROPBIT (touch)");
            close(fd);
            return -1;
        }
    }

    /* Self-identification tag. UI_SET_UNIQ does not exist in the uinput ABI
     * (guarded anyway in case a future kernel adds it); UI_SET_PHYS does, and
     * the primary test is the sysfs one in evdev_is_virtual(). */
#ifdef UI_SET_UNIQ
    if (ioctl(fd, UI_SET_UNIQ, TOUCH_SELF_TAG) < 0)
        perror("UI_SET_UNIQ (touch)");
#endif
    if (ioctl(fd, UI_SET_PHYS, TOUCH_SELF_TAG) < 0)
        perror("UI_SET_PHYS (touch)"); /* non-fatal: secondary self-ID only */

    struct uinput_setup usetup;
    memset(&usetup, 0, sizeof(usetup));
    usetup.id = *id;
    strncpy(usetup.name, dev_name, sizeof(usetup.name) - 1);
    if (ioctl(fd, UI_DEV_SETUP, &usetup) < 0) {
        perror("UI_DEV_SETUP (touch)");
        close(fd);
        return -1;
    }
    if (ioctl(fd, UI_DEV_CREATE) < 0) {
        perror("UI_DEV_CREATE (touch)");
        close(fd);
        return -1;
    }
    return fd;
}

/* Lets go of the real panel -- ungrab, close -- while leaving the virtual
 * touchscreen alone. This is the whole of "turn touch off" in serve mode:
 * touch stops being intercepted, but the clone stays registered with the
 * kernel so no device appears or disappears. Safe against -1/false state. */
static int touch_release_stuck_contacts(const char *why);
static void touch_forget_contacts(void);
static int touch_contacts_live(void);

static void touch_release_panel(void) {
    /* Order matters: the clone must be told the contacts ended BEFORE we stop
     * being able to say so. This one call covers idle transitions, profile
     * switches that drop touch.offset, panel re-detects, the panic chord and
     * shutdown -- every path that used to leave a finger down forever. */
    touch_release_stuck_contacts("panel released");
    g_touch_wait = false;
    g_touch_wait_since_ms = -1;
    if (g_touch_grabbed) { ioctl(g_touch_fd, EVIOCGRAB, 0); g_touch_grabbed = false; }
    if (g_touch_fd >= 0) { close(g_touch_fd); g_touch_fd = -1; }
}

/* touch_release_panel() plus destroying the virtual touchscreen -- the
 * touch-only half of teardown. Shared by touch_fatal_exit(), normal shutdown,
 * and the one-shot mode's live SIGUSR1 disable path (see
 * reload_touch_config). Never called from a serve-mode transition: in serve
 * mode the clone is created once and destroyed only at exit. */
static void touch_disable(void) {
    touch_release_panel();
    destroy_uinput(g_touch_uinput_fd);
    g_touch_uinput_fd = -1;
}

/* Releases both grabs, closes both real fds, destroys both uinput devices,
 * and exits with `code`. Used for the touch-specific fatal-error contract
 * (grab failure -> 4, uinput write failure -> 5). */
static void touch_fatal_exit(int code) {
    touch_disable();
    if (g_grabbed) { ioctl(g_pad_fd, EVIOCGRAB, 0); g_grabbed = false; }
    if (g_pad_fd >= 0) { close(g_pad_fd); g_pad_fd = -1; }
    destroy_uinput(g_uinput_fd);
    exit(code);
}

/* Defined in the serve section below; the panic chord is the one place a
 * mid-file function has to reach forward into it. */
static void serve_go_idle(config_t *cfg);
static void write_status(const config_t *cfg);
static void release_all_virtual(config_t *cfg);

/* One-shot mode: log the panic trigger and reuse touch_fatal_exit's cleanup
 * (release the panel grab, destroy the virtual touchscreen, ungrab the pad,
 * destroy the keyboard device) to exit(6) so the supervisor can persist
 * "touch offset disabled" and restart without it.
 *
 * Serve mode must not exit here -- it owns devices that other profiles will
 * want again -- so it drops to IDLE instead: both grabs released, every held
 * key let go, every contact released on the clone, the config file left
 * exactly as the app wrote it, and the panic counter bumped in the status file
 * so the supervisor notices and decides what to write next.
 *
 * The pad fd survives this, ungrabbed, until PANIC_RESTART_MS: it is the only
 * way to tell whether the user is still holding the chord and wants the harder
 * cure (see panic_chord_restart). Nothing is mapped from it while parked. */
static void panic_chord_fire(config_t *cfg) {
    if (g_serve) {
        g_panic_count++;
        fprintf(stderr, "dpadkeys: panic: idle\n");
        fflush(stderr);

        /* serve_go_idle() -> release_all_virtual() deliberately forgets the
         * chord; the escalation needs it kept, including the "already fired"
         * latch -- without it the still-held chord re-fires every wakeup. */
        long long chord_start = g_chord_start_ms;
        bool fired = g_panic_idle_fired;
        bool held[MAX_PANIC_SRC];
        memcpy(held, g_panic_held, sizeof(held));
        int keep_fd = -1;
        if (g_pad_fd >= 0 && cfg->n_panic > 0) {
            if (g_grabbed) { ioctl(g_pad_fd, EVIOCGRAB, 0); g_grabbed = false; }
            keep_fd = g_pad_fd;
            g_pad_fd = -1; /* hidden from serve_go_idle so it is not closed */
        }
        serve_go_idle(cfg);
        g_pad_fd = keep_fd;
        if (keep_fd >= 0) {
            g_panic_watch_until_ms = chord_start + PANIC_RESTART_MS;
            g_chord_start_ms = chord_start;
            g_panic_idle_fired = fired;
            memcpy(g_panic_held, held, sizeof(held));
        }
        write_status(cfg);
        return;
    }
    fprintf(stderr, "dpadkeys: panic: chord held, disabling touch offset\n");
    fflush(stderr);
    touch_fatal_exit(6);
}

/* The long hold: serve mode's nuclear option. Both virtual devices are
 * destroyed on the way out and the process exits 6, so the app's watchdog
 * respawns a daemon whose clone Android has never seen a phantom pointer on.
 * This is the guaranteed cure for any stuck-contact case the release paths
 * above somehow miss, and it needs no app, no adb and no menu. */
static void panic_chord_restart(config_t *cfg) {
    g_panic_count++;
    fprintf(stderr, "dpadkeys: panic: long hold, exiting to recreate devices\n");
    fflush(stderr);
    release_all_virtual(cfg);
    touch_disable();               /* releases stuck contacts, drops panel, destroys clone */
    g_want_pad = false;
    g_want_touch = false;
    g_panic_watch_until_ms = -1;
    write_status(cfg);             /* last word to the supervisor before we go */
    if (g_pad_fd >= 0) {
        if (g_grabbed) ioctl(g_pad_fd, EVIOCGRAB, 0);
        g_grabbed = false;
        close(g_pad_fd);
        g_pad_fd = -1;
    }
    destroy_uinput(g_uinput_fd);
    g_uinput_fd = -1;
    if (g_pidfile) unlink(g_pidfile);
    exit(6);
}

static void panic_set_held(const config_t *cfg, int slot, bool pressed) {
    for (int i = 0; i < cfg->n_panic; i++)
        if (cfg->panic_src[i] == slot) g_panic_held[i] = pressed;
}

/* Tracks the panic chord's sources straight off the raw pad event.
 *
 * Deliberately separate from update_source()/the mapping: the chord has to
 * work for sources this profile does not bind at all, and while parked (pad
 * ungrabbed, nothing dispatched) during the PANIC_CHORD_MS..PANIC_RESTART_MS
 * window. Axis thresholds are read-only here -- hysteresis state belongs to
 * the mapping path. */
static void panic_track_event(config_t *cfg, const struct input_event *ev) {
    if (cfg->n_panic <= 0) return;
    if (ev->type == EV_KEY) {
        if (ev->value == 2) return; /* autorepeat */
        bool pressed = ev->value != 0;
        int sem = semantic_for_key(ev->code);
        if (sem >= 0) panic_set_held(cfg, sem, pressed);
        for (int i = 0; i < cfg->n_raw; i++)
            if (cfg->raw[i].kind == RAW_KEY && cfg->raw[i].code == ev->code)
                panic_set_held(cfg, SRC_COUNT + i, pressed);
        return;
    }
    if (ev->type != EV_ABS) return;
    if (ev->code == ABS_HAT0X) {
        panic_set_held(cfg, SRC_HAT_LEFT, ev->value < 0);
        panic_set_held(cfg, SRC_HAT_RIGHT, ev->value > 0);
    } else if (ev->code == ABS_HAT0Y) {
        panic_set_held(cfg, SRC_HAT_UP, ev->value < 0);
        panic_set_held(cfg, SRC_HAT_DOWN, ev->value > 0);
    }
    if (g_ax.lt.present && ev->code == g_ax.lt.code)
        panic_set_held(cfg, SRC_LT, classify_trigger(ev->value, &g_ax.lt, cfg->deadzone));
    if (g_ax.rt.present && ev->code == g_ax.rt.code)
        panic_set_held(cfg, SRC_RT, classify_trigger(ev->value, &g_ax.rt, cfg->deadzone));
    for (int i = 0; i < cfg->n_raw; i++) {
        const raw_source_t *r = &cfg->raw[i];
        if (r->kind != RAW_ABS || r->code != ev->code || !r->axis_known) continue;
        panic_set_held(cfg, SRC_COUNT + i, raw_abs_pressed(r, ev->value, cfg->deadzone));
    }
}

/* Fires the profile's panic chord once every listed source has been held
 * continuously for PANIC_CHORD_MS, and (serve mode only) escalates to a
 * device-recreating exit at PANIC_RESTART_MS if the hold continues. Called
 * after panic_track_event() on every raw pad event and, with no event at all,
 * from the poll-timeout path so the chord fires purely from elapsed time while
 * the buttons are held down and nothing else arrives.
 *
 * Arming: in serve mode, whenever the daemon holds something the user may need
 * to take back -- a grabbed pad (so keyboard-only profiles get the same escape
 * hatch), a grabbed panel, or the parked window. One-shot mode keeps the
 * original rule, since there the chord only ever existed to undo a touch
 * offset. Returns true if the chord fired, which in serve mode means the pad
 * has just been let go and the caller must stop using it for this event. */
static bool check_panic_chord(config_t *cfg) {
    bool armed = cfg->n_panic > 0 &&
                 (g_serve ? (g_grabbed || g_touch_grabbed || g_panic_watch_until_ms >= 0)
                          : (cfg->touch_offset_set && g_touch_grabbed));
    if (armed) {
        for (int i = 0; i < cfg->n_panic; i++)
            if (!g_panic_held[i]) { armed = false; break; }
    }
    if (!armed) {
        g_chord_start_ms = -1;
        g_panic_idle_fired = false;
        return false;
    }
    long long now = now_ms();
    if (g_chord_start_ms < 0) {
        g_chord_start_ms = now;
        g_panic_idle_fired = false;
        return false;
    }
    long long held_ms = now - g_chord_start_ms;
    if (g_serve && held_ms >= PANIC_RESTART_MS) {
        panic_chord_restart(cfg); /* does not return */
        return true;
    }
    if (held_ms < PANIC_CHORD_MS) return false;
    if (g_panic_idle_fired) return false; /* already parked; waiting on the escalation */
    g_panic_idle_fired = true;
    panic_chord_fire(cfg);
    return true;
}

/* Writes a batch of events to the virtual touchscreen. A write error of
 * ENODEV/EIO means the clone is gone and the daemon cannot do its job, so it
 * is fatal (exit 5) per the contract; a short write is logged but survivable.
 * Returns false if nothing was written. */
static bool touch_write(const struct input_event *evs, int n) {
    if (n <= 0) return true;
    ssize_t want = (ssize_t)n * (ssize_t)sizeof(struct input_event);
    ssize_t w = write(g_touch_uinput_fd, evs, (size_t)want);
    if (w == want) return true;
    if (w < 0 && (errno == ENODEV || errno == EIO)) {
        fprintf(stderr, "dpadkeys: touch: uinput write failed: %s\n", strerror(errno));
        fflush(stderr);
        if (g_serve) { g_touch_uinput_dead = true; return false; }
        touch_fatal_exit(5);
    }
    fprintf(stderr, "dpadkeys: touch: short/failed uinput write (%zd/%zd)%s%s\n",
            w, want, w < 0 ? ": " : "", w < 0 ? strerror(errno) : "");
    fflush(stderr);
    return false;
}

/* ---- clone contact state ---- */

/* Contacts the clone currently believes are down. Reported in the status file
 * so the supervising app can see a stuck pointer without guessing. */
static int touch_contacts_live(void) {
    int n = 0;
    for (int s = 0; s < TOUCH_MAX_SLOTS; s++)
        if (g_clone_tid[s] >= 0) n++;
    return n;
}

/* True if `tid` is already live on the clone in a slot other than `skip`.
 * Tracking ids only have to be unique across *live* contacts, but a duplicate
 * makes the kernel/Android merge two pointers, so ids are remapped on clash. */
static bool touch_tid_live(int tid, int skip) {
    if (tid < 0) return false;
    for (int s = 0; s < TOUCH_MAX_SLOTS; s++)
        if (s != skip && g_clone_tid[s] == tid) return true;
    return false;
}

/* An id from the daemon's own space, used for contacts carried over at grab
 * time (whose panel ids we deliberately do not reuse) and for remapping a
 * forwarded id that would clash with one already live. */
static int touch_alloc_tid(void) {
    int id;
    do {
        id = g_clone_next_tid++;
        if (g_clone_next_tid < 0) g_clone_next_tid = 0x40000000; /* stay positive */
    } while (touch_tid_live(id, -1));
    return id;
}

/* Drops the tracked state without writing: only for a clone that is already
 * gone (uinput write failed ENODEV/EIO), where a release frame could not be
 * delivered anyway. */
static void touch_forget_contacts(void) {
    for (int s = 0; s < TOUCH_MAX_SLOTS; s++) g_clone_tid[s] = -1;
    g_clone_btn_touch = false;
    g_clone_cur_slot = -1;
    g_touch_last_event_ms = -1;
}

/* Emits, in one frame, ABS_MT_TRACKING_ID -1 for every slot the clone still
 * has down plus BTN_TOUCH 0 if it was 1.
 *
 * This is the fix for the phantom-pointer bug. The clone is a persistent
 * device in serve mode, so any contact left down on it stays down in Android's
 * view forever: every later real touch becomes a *second* pointer and
 * single-touch handling follows the phantom, which is what "touch is off by
 * half the screen, and Home doesn't fix it" actually was.
 *
 * Tracked state is cleared BEFORE the write, so the one-shot exit-5 contract
 * (touch_write -> touch_fatal_exit -> touch_disable -> touch_release_panel)
 * cannot recurse back in here. Returns the number of contacts released. */
static int touch_release_stuck_contacts(const char *why) {
    if (touch_contacts_live() == 0 && !g_clone_btn_touch) {
        g_touch_last_event_ms = -1;
        return 0;
    }
    if (g_touch_uinput_fd < 0 || g_touch_uinput_dead) { touch_forget_contacts(); return 0; }

    struct input_event out[TOUCH_MAX_SLOTS * 2 + 2];
    int n = 0, released = 0;
    for (int s = 0; s < TOUCH_MAX_SLOTS; s++) {
        if (g_clone_tid[s] < 0) continue;
        memset(&out[n], 0, sizeof(out[n]));
        out[n].type = EV_ABS; out[n].code = ABS_MT_SLOT; out[n].value = g_touch_slot_min + s; n++;
        memset(&out[n], 0, sizeof(out[n]));
        out[n].type = EV_ABS; out[n].code = ABS_MT_TRACKING_ID; out[n].value = -1; n++;
        g_clone_cur_slot = g_touch_slot_min + s;
        released++;
    }
    if (g_clone_btn_touch) {
        memset(&out[n], 0, sizeof(out[n]));
        out[n].type = EV_KEY; out[n].code = BTN_TOUCH; out[n].value = 0; n++;
    }
    memset(&out[n], 0, sizeof(out[n]));
    out[n].type = EV_SYN; out[n].code = SYN_REPORT; out[n].value = 0; n++;

    for (int s = 0; s < TOUCH_MAX_SLOTS; s++) g_clone_tid[s] = -1;
    g_clone_btn_touch = false;
    g_touch_last_event_ms = -1;

    touch_write(out, n);
    if (released > 0) {
        fprintf(stderr, "dpadkeys: touch: released %d stuck contact(s) (%s)\n",
                released, why ? why : "?");
        fflush(stderr);
    }
    return released;
}

/* Reconciles the clone's contact state with the panel's at (re)grab time.
 *
 * Two things can be true at once here. A finger may already be down on the
 * panel: it will keep streaming position updates for that slot without ever
 * re-sending its ABS_MT_TRACKING_ID, so a clone that has the slot empty drops
 * them all. And the clone may still have contacts of its own from before --
 * it is never destroyed in serve mode -- which the panel has since forgotten.
 *
 * So: read the panel's per-slot state with EVIOCGMTSLOTS, take a slot as live
 * only if its tracking id is >= 0 AND its position is inside the panel's own
 * axis range, then in a single frame release every slot the clone has down but
 * the panel does not, press every slot the panel has down and the clone does
 * not, and refresh the position of the ones both agree on. Contacts pressed
 * here get an id from the daemon's private space (touch_alloc_tid()), never
 * the panel's, so they cannot collide with an id still live on the clone or
 * with one the panel is about to send for a different finger.
 *
 * If EVIOCGMTSLOTS is unavailable we carry nothing and release everything:
 * guessing is what produced phantom pointers in the first place. */
static void touch_sync_initial_contacts(const config_t *cfg) {
#ifdef EVIOCGMTSLOTS
    struct input_absinfo slotinfo;
    if (ioctl(g_touch_fd, EVIOCGABS(ABS_MT_SLOT), &slotinfo) < 0 ||
        slotinfo.maximum < slotinfo.minimum) {
        touch_release_stuck_contacts("regrab, no slot info");
        return;
    }
    int nslots = slotinfo.maximum - slotinfo.minimum + 1;
    if (nslots > TOUCH_MAX_SLOTS) nslots = TOUCH_MAX_SLOTS;
    g_touch_slot_min = slotinfo.minimum;
    g_touch_nslots = nslots;

    /* buf[0] is the requested ABS_MT_* code; buf[1..nslots] the slot values. */
    int32_t tid[TOUCH_MAX_SLOTS + 1], px[TOUCH_MAX_SLOTS + 1];
    int32_t py[TOUCH_MAX_SLOTS + 1], maj[TOUCH_MAX_SLOTS + 1];
    bool have_maj = true;
    tid[0] = ABS_MT_TRACKING_ID;
    px[0] = ABS_MT_POSITION_X;
    py[0] = ABS_MT_POSITION_Y;
    maj[0] = ABS_MT_TOUCH_MAJOR;
    size_t sz = (size_t)(nslots + 1) * sizeof(int32_t);
    if (ioctl(g_touch_fd, EVIOCGMTSLOTS(sz), tid) < 0) {
        fprintf(stderr, "dpadkeys: touch: EVIOCGMTSLOTS unavailable (%s); "
                        "carrying nothing over\n", strerror(errno));
        fflush(stderr);
        touch_release_stuck_contacts("regrab, EVIOCGMTSLOTS unavailable");
        return;
    }
    if (ioctl(g_touch_fd, EVIOCGMTSLOTS(sz), px) < 0 ||
        ioctl(g_touch_fd, EVIOCGMTSLOTS(sz), py) < 0) {
        touch_release_stuck_contacts("regrab, no slot positions");
        return;
    }
    if (ioctl(g_touch_fd, EVIOCGMTSLOTS(sz), maj) < 0) have_maj = false;

    bool panel_down[TOUCH_MAX_SLOTS];
    for (int s = 0; s < nslots; s++)
        panel_down[s] = tid[s + 1] >= 0 && touch_pos_in_range(px[s + 1], py[s + 1]);

    struct input_event out[TOUCH_MAX_SLOTS * 5 + 3];
    int n = 0, released = 0, carried = 0;
    for (int s = 0; s < nslots; s++) {
        bool clone_down = g_clone_tid[s] >= 0;
        if (!clone_down && !panel_down[s]) continue;

        memset(&out[n], 0, sizeof(out[n]));
        out[n].type = EV_ABS; out[n].code = ABS_MT_SLOT; out[n].value = slotinfo.minimum + s; n++;

        if (clone_down && !panel_down[s]) {
            /* Ours, not the panel's: a contact the clone was left holding. */
            memset(&out[n], 0, sizeof(out[n]));
            out[n].type = EV_ABS; out[n].code = ABS_MT_TRACKING_ID; out[n].value = -1; n++;
            g_clone_tid[s] = -1;
            released++;
            continue;
        }
        if (!clone_down) {
            /* New to the clone: press it under an id of our own. */
            memset(&out[n], 0, sizeof(out[n]));
            out[n].type = EV_ABS; out[n].code = ABS_MT_TRACKING_ID;
            out[n].value = touch_alloc_tid(); n++;
            g_clone_tid[s] = out[n - 1].value;
            carried++;
        }
        /* Both down (same slot, same panel, so the same finger) or freshly
         * pressed: refresh the position either way. */
        struct input_event e[3];
        memset(e, 0, sizeof(e));
        int k = 0;
        e[k].type = EV_ABS; e[k].code = ABS_MT_POSITION_X; e[k].value = px[s + 1]; k++;
        e[k].type = EV_ABS; e[k].code = ABS_MT_POSITION_Y; e[k].value = py[s + 1]; k++;
        if (have_maj) { e[k].type = EV_ABS; e[k].code = ABS_MT_TOUCH_MAJOR; e[k].value = maj[s + 1]; k++; }
        for (int j = 0; j < k; j++) {
            offset_touch_event(cfg, &e[j]);
            out[n++] = e[j];
        }
    }

    /* Restore the panel's current slot so the clone's implicit slot matches
     * before the live stream resumes, then BTN_TOUCH, then end the frame. */
    memset(&out[n], 0, sizeof(out[n]));
    out[n].type = EV_ABS; out[n].code = ABS_MT_SLOT; out[n].value = slotinfo.value; n++;
    g_clone_cur_slot = slotinfo.value;

    bool want_btn = touch_contacts_live() > 0;
    if (want_btn != g_clone_btn_touch) {
        memset(&out[n], 0, sizeof(out[n]));
        out[n].type = EV_KEY; out[n].code = BTN_TOUCH; out[n].value = want_btn ? 1 : 0; n++;
        g_clone_btn_touch = want_btn;
    }
    memset(&out[n], 0, sizeof(out[n]));
    out[n].type = EV_SYN; out[n].code = SYN_REPORT; out[n].value = 0; n++;

    touch_write(out, n);
    g_touch_last_event_ms = touch_contacts_live() > 0 ? now_ms() : -1;
    if (released > 0)
        fprintf(stderr, "dpadkeys: touch: released %d stuck contact(s) (regrab reconcile)\n", released);
    if (carried > 0)
        fprintf(stderr, "dpadkeys: touch: carried over %d contact(s) live at grab time\n", carried);
    if (released > 0 || carried > 0) fflush(stderr);
#else
    (void)cfg;
    touch_release_stuck_contacts("regrab, no EVIOCGMTSLOTS in headers");
    fprintf(stderr, "dpadkeys: touch: EVIOCGMTSLOTS not in headers; a finger "
                    "already down at grab time will be ignored until lifted\n");
    fflush(stderr);
#endif
}

/* True if the panel has nothing on it: every MT slot's tracking id is -1 and
 * BTN_TOUCH is 0, straight from the kernel. This is the pre-grab gate -- a
 * panel that is not neutral must not be grabbed, or Android never sees the
 * release of whatever is down and keeps a phantom pointer for the whole
 * active period. A panel we cannot interrogate (no EVIOCGMTSLOTS) reads as
 * neutral: the carry-over path already covers that case, and refusing to
 * activate at all would be worse. */
static bool touch_panel_neutral(int fd, const char **why) {
    if (why) *why = NULL;
    unsigned long keystate[NLONGS(KEY_CNT)] = {0};
    if (ioctl(fd, EVIOCGKEY(sizeof(keystate)), keystate) >= 0 &&
        TEST_BIT(BTN_TOUCH, keystate)) {
        if (why) *why = "BTN_TOUCH is still down";
        return false;
    }
#ifdef EVIOCGMTSLOTS
    struct input_absinfo si;
    if (ioctl(fd, EVIOCGABS(ABS_MT_SLOT), &si) < 0 || si.maximum < si.minimum) return true;
    int nslots = si.maximum - si.minimum + 1;
    if (nslots > TOUCH_MAX_SLOTS) nslots = TOUCH_MAX_SLOTS;
    int32_t tid[TOUCH_MAX_SLOTS + 1];
    tid[0] = ABS_MT_TRACKING_ID;
    if (ioctl(fd, EVIOCGMTSLOTS((size_t)(nslots + 1) * sizeof(int32_t)), tid) < 0) return true;
    for (int sl = 0; sl < nslots; sl++) {
        if (tid[sl + 1] < 0) continue;
        if (why) *why = "a contact is still down";
        return false;
    }
#endif
    return true;
}

typedef enum {
    TOUCH_ENABLE_OK = 0,
    /* Panel open and being watched, not grabbed yet: a finger is still on it.
     * Not a failure -- the event loop finishes the job. */
    TOUCH_ENABLE_WAITING,
    TOUCH_ENABLE_ERR_NO_PANEL,
    TOUCH_ENABLE_ERR_UINPUT,
    TOUCH_ENABLE_ERR_GRAB,
} touch_enable_result_t;

/* Brings touch pass-through up: discovers/opens the configured panel, creates
 * the virtual touchscreen, grabs the panel, and carries over any contacts
 * already down. Shared by startup and the live SIGUSR1 enable path (see
 * reload_touch_config) -- the two differ only in how they react to failure
 * (startup exits; the live path logs and stays off), so this function never
 * exits itself. On any failure it tears back down whatever it had partially
 * created (via touch_disable()) and returns a code identifying the failed
 * step; g_touch_grabbed is true if and only if it returns TOUCH_ENABLE_OK. */
static touch_enable_result_t touch_enable(const config_t *cfg) {
    /* Serve mode pre-creates the clone at startup (serve_create_touch_device)
     * and never destroys it, so this may be a pure re-grab. Only a clone this
     * call created is torn down again on failure. */
    bool created_here = false;

    g_touch_fd = find_touch(cfg, g_touch_path, sizeof(g_touch_path));
    if (g_touch_fd < 0) {
        if (!g_touch_quiet)
            fprintf(stderr, "dpadkeys: touch: no touch panel found%s%s\n",
                    cfg->touch_device[0] ? " at " : "", cfg->touch_device);
        return TOUCH_ENABLE_ERR_NO_PANEL;
    }
    struct input_id touch_id;
    memset(&touch_id, 0, sizeof(touch_id));
    ioctl(g_touch_fd, EVIOCGID, &touch_id);
    if (ioctl(g_touch_fd, EVIOCGNAME(sizeof(g_touch_name)), g_touch_name) < 0)
        snprintf(g_touch_name, sizeof(g_touch_name), "?");
    query_touch_axes(g_touch_fd);

    if (g_touch_uinput_fd < 0) {
        g_touch_uinput_fd = open_touch_uinput(g_touch_fd, &touch_id, g_touch_device_name, cfg);
        if (g_touch_uinput_fd < 0) {
            fprintf(stderr, "dpadkeys: touch: could not create virtual touchscreen\n");
            close(g_touch_fd);
            g_touch_fd = -1;
            return TOUCH_ENABLE_ERR_UINPUT;
        }
        created_here = true;
        fprintf(stderr, "dpadkeys: touch: created virtual touchscreen \"%s\" (from %s)\n", g_touch_device_name, g_touch_path);
    }

    /* Never grab a panel with a finger on it (see GRAB_WAIT_MAX_MS). The fd
     * stays open and ungrabbed; the event loop drains it and grabs on the
     * frame that completes the release. The clone is left alone either way --
     * in serve mode it is never ours to destroy, and in one-shot mode the wait
     * resolves in the same loop. */
    const char *why = NULL;
    if (!touch_panel_neutral(g_touch_fd, &why)) {
        g_touch_wait = true;
        g_touch_wait_since_ms = now_ms();
        fprintf(stderr, "dpadkeys: touch: waiting for the panel to be released before grabbing\n");
        fflush(stderr);
        return TOUCH_ENABLE_WAITING;
    }

    if (ioctl(g_touch_fd, EVIOCGRAB, 1) < 0) {
        if (!g_touch_quiet) {
            perror("EVIOCGRAB (touch)");
            fprintf(stderr, "dpadkeys: touch: cannot grab the panel\n");
        }
        touch_release_panel();
        if (created_here) { destroy_uinput(g_touch_uinput_fd); g_touch_uinput_fd = -1; }
        return TOUCH_ENABLE_ERR_GRAB;
    }
    g_touch_grabbed = true;
    touch_sync_initial_contacts(cfg);
    return TOUCH_ENABLE_OK;
}

/* Closes out a pending panel wait by taking the grab. `capped_why` is NULL for
 * the normal case (the panel went neutral) and names what is still down when
 * GRAB_WAIT_MAX_MS ran out and we are grabbing regardless -- only then does
 * touch_sync_initial_contacts()'s carry-over have anything to carry.
 * Returns false (panel dropped) if the grab itself fails. */
static bool touch_finish_grab(const config_t *cfg, const char *capped_why) {
    long long waited = g_touch_wait_since_ms >= 0 ? now_ms() - g_touch_wait_since_ms : 0;
    if (ioctl(g_touch_fd, EVIOCGRAB, 1) < 0) {
        perror("EVIOCGRAB (touch)");
        fprintf(stderr, "dpadkeys: touch: cannot grab the panel after the wait\n");
        fflush(stderr);
        touch_release_panel();   /* clears g_touch_wait */
        return false;
    }
    g_touch_grabbed = true;
    g_touch_wait = false;
    g_touch_wait_since_ms = -1;
    if (capped_why)
        fprintf(stderr, "dpadkeys: touch: still not released after %d ms (%s); grabbing anyway\n",
                GRAB_WAIT_MAX_MS, capped_why);
    else
        fprintf(stderr, "dpadkeys: touch: grabbed after wait (%lld ms)\n", waited);
    fflush(stderr);
    touch_sync_initial_contacts(cfg);
    return true;
}

/* Drains the ungrabbed panel while we wait for it to go neutral. Reading an
 * evdev node we have not grabbed empties only our own client buffer, so
 * Android keeps receiving the very events that end the stuck contact -- which
 * is the whole point: it gets the release it would otherwise never see.
 *
 * State is re-read from the kernel after every SYN_REPORT rather than tracked
 * from the stream, so the grab lands on the frame that completes the release
 * and the rest of the batch (which belongs to whatever touch comes next) is
 * simply dropped. Returns 1 grabbed, 0 still waiting, -1 panel gone. */
static int touch_wait_drain(const config_t *cfg) {
    struct input_event batch[64];
    for (;;) {
        ssize_t n = read(g_touch_fd, batch, sizeof(batch));
        if (n <= 0) {
            if (n < 0 && (errno == ENODEV || errno == EIO)) return -1;
            return 0;
        }
        int cnt = (int)(n / (ssize_t)sizeof(struct input_event));
        for (int i = 0; i < cnt; i++) {
            if (batch[i].type != EV_SYN || batch[i].code != SYN_REPORT) continue;
            if (!touch_panel_neutral(g_touch_fd, NULL)) continue;
            return touch_finish_grab(cfg, NULL) ? 1 : -1;
        }
    }
}

/* Reads whatever the panel has queued (whole events only; evdev never returns
 * a partial one), applies the configured X/Y offset in place, and replays the
 * batch to the clone with a single write() so frames stay contiguous and the
 * original timestamps/order are preserved verbatim. Loops until EAGAIN so a
 * burst larger than the buffer is drained in-order.
 *
 * On the way through it mirrors the MT state it is writing (current slot, the
 * tracking id live in each slot, BTN_TOUCH) so every "stop forwarding" path can
 * release exactly what is still down, and fixes up two things the raw stream
 * can get wrong from the clone's point of view:
 *   - a new tracking id on a slot the clone still has down (the lift was lost
 *     across a re-grab, or the panel simply restarted the contact): the old one
 *     is closed with -1 in a frame of its own first, because two ids in one
 *     frame collapse to the last and leave a pointer that never ends;
 *   - an id that would duplicate one already live in another slot: remapped
 *     into the daemon's private id space.
 * Returns 0 normally, or -1 with errno set (notably ENODEV when the panel
 * vanished) so the caller can re-detect. */
static int forward_touch_batch(const config_t *cfg) {
    struct input_event batch[128];
    /* Worst case each input event also emits an inserted release frame
     * (TRACKING_ID -1 + SYN + SLOT) ahead of itself. */
    static struct input_event out[128 * 4 + 8];
    for (;;) {
        ssize_t n = read(g_touch_fd, batch, sizeof(batch));
        if (n < 0) {
            if (errno == EINTR) continue;
            if (errno == EAGAIN || errno == EWOULDBLOCK) return 0;
            return -1;
        }
        if (n == 0) return 0;
        int n_ev = (int)((size_t)n / sizeof(struct input_event));
        if (n_ev <= 0) return 0;
        g_touch_last_event_ms = now_ms();

        int k = 0;
        for (int i = 0; i < n_ev; i++) {
            struct input_event ev = batch[i];
            offset_touch_event(cfg, &ev);

            if (ev.type == EV_ABS && ev.code == ABS_MT_SLOT) {
                g_clone_cur_slot = ev.value;
                out[k++] = ev;
                continue;
            }
            if (ev.type == EV_KEY && ev.code == BTN_TOUCH) {
                g_clone_btn_touch = (ev.value != 0);
                out[k++] = ev;
                continue;
            }
            if (ev.type == EV_ABS && ev.code == ABS_MT_TRACKING_ID) {
                int slot = g_clone_cur_slot - g_touch_slot_min;
                if (slot < 0 || slot >= g_touch_nslots || slot >= TOUCH_MAX_SLOTS) {
                    /* A slot outside the range the panel reports for itself:
                     * forward it verbatim, but do not pretend to track it. */
                    out[k++] = ev;
                    continue;
                }
                if (ev.value < 0) {
                    g_clone_tid[slot] = -1;
                    out[k++] = ev;
                    continue;
                }
                if (g_clone_tid[slot] >= 0) {
                    memset(&out[k], 0, sizeof(out[k]));
                    out[k].type = EV_ABS; out[k].code = ABS_MT_TRACKING_ID; out[k].value = -1; k++;
                    memset(&out[k], 0, sizeof(out[k]));
                    out[k].type = EV_SYN; out[k].code = SYN_REPORT; out[k].value = 0; k++;
                    memset(&out[k], 0, sizeof(out[k]));
                    out[k].type = EV_ABS; out[k].code = ABS_MT_SLOT; out[k].value = g_clone_cur_slot; k++;
                    g_clone_tid[slot] = -1;
                }
                if (touch_tid_live(ev.value, slot)) ev.value = touch_alloc_tid();
                g_clone_tid[slot] = ev.value;
                out[k++] = ev;
                continue;
            }
            out[k++] = ev;
        }
        touch_write(out, k);
        /* A full buffer means there may be more queued; anything shorter
         * means the queue is drained. */
        if ((size_t)n < sizeof(batch)) return 0;
    }
}

/* Reads just the touch.offset line out of `path` (last one wins, matching
 * load_config_file): *out_set/out_dx/out_dy reflect what was found, with
 * *out_set false when the line is absent or says "off"/"none". Tolerant of
 * `path` being rewritten out from under us by whatever regenerates the
 * config: a failed open, or a file that reads as completely empty, is
 * retried once after 50ms before giving up. Returns false (out params
 * untouched, errno describing the last open failure if any) only when both
 * attempts failed. */
static bool read_touch_offset_line(const char *path, bool *out_set, int *out_dx, int *out_dy,
                                   int *out_rot) {
    for (int attempt = 0; attempt < 2; attempt++) {
        FILE *f = fopen(path, "r");
        int open_errno = errno;
        char line[256];
        bool saw_any_line = false;
        bool found = false, off = true;
        int dx = 0, dy = 0, rot = 0;
        if (f) {
            while (fgets(line, sizeof(line), f)) {
                saw_any_line = true;
                char *hash = strchr(line, '#');
                if (hash) *hash = '\0';
                char *save = NULL;
                char *tok1 = strtok_r(line, " \t\r\n", &save);
                if (!tok1) continue;
                if (strcmp(tok1, "touch.rotation") == 0) {
                    char *tok2 = strtok_r(NULL, " \t\r\n", &save);
                    if (tok2) { int r = atoi(tok2); rot = ((r % 4) + 4) % 4; }
                    continue;
                }
                if (strcmp(tok1, "touch.offset") != 0) continue;
                char *tok2 = strtok_r(NULL, " \t\r\n", &save);
                if (!tok2) continue;
                if (strcmp(tok2, "off") == 0 || strcmp(tok2, "none") == 0) {
                    found = true; off = true; continue;
                }
                char *tok3 = strtok_r(NULL, " \t\r\n", &save);
                if (tok3) { dx = atoi(tok2); dy = atoi(tok3); found = true; off = false; }
            }
            fclose(f);
        }
        if (f && saw_any_line) {
            *out_set = found && !off;
            *out_dx = dx;
            *out_dy = dy;
            *out_rot = rot;
            return true;
        }
        if (attempt == 0) {
            struct timespec ts = { 0, 50 * 1000 * 1000 };
            nanosleep(&ts, NULL);
            continue;
        }
        errno = f ? 0 : open_errno;
        return false;
    }
    return false;
}

/* Re-reads `config_path` on SIGUSR1 for touch.* keys only (currently just
 * touch.offset) and brings live touch pass-through to match what was found:
 *  - was off, file now names an offset -> touch_enable() live; on failure,
 *    log and stay off (never exits).
 *  - was on, file no longer names an offset (absent or "off"/"none") ->
 *    touch_disable() live.
 *  - was on and still on -> just update dx/dy (as before).
 * No-op (with the previous touch config kept) if config_path is unset or the
 * file can't be read even after read_touch_offset_line()'s retry. */
static void reload_touch_config(const char *config_path, config_t *cfg) {
    if (!config_path) return;
    bool new_set; int new_dx = 0, new_dy = 0, new_rot = 0;
    if (!read_touch_offset_line(config_path, &new_set, &new_dx, &new_dy, &new_rot)) {
        if (errno) fprintf(stderr, "dpadkeys: SIGUSR1: cannot reopen config '%s': %s\n",
                            config_path, strerror(errno));
        else fprintf(stderr, "dpadkeys: SIGUSR1: config '%s' still empty after retry; "
                             "keeping current touch config\n", config_path);
        fflush(stderr);
        return;
    }

    bool was_on = cfg->touch_offset_set;
    int old_rot = cfg->touch_rotation;
    cfg->touch_offset_set = new_set;
    cfg->touch_dx = new_dx;
    cfg->touch_dy = new_dy;
    cfg->touch_rotation = new_rot;
    clamp_touch_offset(cfg, "SIGUSR1");
    /* Rotation is applied live in the forward path (offset_touch_event), so a
     * rotation-only change needs no re-grab and never recreates the clone --
     * exactly the "does not churn the clone" property we want on a flip. */
    if (new_rot != old_rot) {
        fprintf(stderr, "dpadkeys: touch: rotation now %d\n", new_rot);
        fflush(stderr);
    }

    if (!was_on && new_set) {
        touch_enable_result_t r = touch_enable(cfg);
        if (r == TOUCH_ENABLE_OK || r == TOUCH_ENABLE_WAITING) {
            fprintf(stderr, "dpadkeys: touch: enabled live path=%s off=(%d,%d)%s\n",
                    g_touch_path, cfg->touch_dx, cfg->touch_dy,
                    r == TOUCH_ENABLE_WAITING ? " (waiting for the panel to be released)" : "");
        } else {
            fprintf(stderr, "dpadkeys: touch: failed to enable live; staying off\n");
            cfg->touch_offset_set = false;
        }
        fflush(stderr);
        return;
    }

    if (was_on && !new_set) {
        touch_disable();
        fprintf(stderr, "dpadkeys: touch: disabled live\n");
        fflush(stderr);
        return;
    }

    if (was_on && new_set) {
        fprintf(stderr, "dpadkeys: touch: offset now %d %d\n", cfg->touch_dx, cfg->touch_dy);
        fflush(stderr);
    }
    /* !was_on && !new_set: nothing changed. */
}

static void print_touch_banner(const config_t *cfg) {
    if (!cfg->touch_offset_set) {
        printf("dpadkeys: touch=off\n");
        fflush(stdout);
        return;
    }
    char xb[32] = "none", yb[32] = "none";
    if (g_touch_has_mtx) snprintf(xb, sizeof(xb), "[%d,%d]", g_touch_mtx_info.minimum, g_touch_mtx_info.maximum);
    else if (g_touch_has_x) snprintf(xb, sizeof(xb), "[%d,%d]", g_touch_x_info.minimum, g_touch_x_info.maximum);
    if (g_touch_has_mty) snprintf(yb, sizeof(yb), "[%d,%d]", g_touch_mty_info.minimum, g_touch_mty_info.maximum);
    else if (g_touch_has_y) snprintf(yb, sizeof(yb), "[%d,%d]", g_touch_y_info.minimum, g_touch_y_info.maximum);
    char panic_b[192];
    if (cfg->n_panic > 0) {
        char pb[160];
        panic_spec_str(cfg, pb, sizeof(pb));
        snprintf(panic_b, sizeof(panic_b), "%s %dms", pb, PANIC_CHORD_MS);
    } else snprintf(panic_b, sizeof(panic_b), "none");
    char space_b[32];
    if (cfg->touch_display_set)
        snprintf(space_b, sizeof(space_b), "display %dx%d rot=%d",
                 cfg->touch_display_w, cfg->touch_display_h, cfg->touch_display_r);
    else
        snprintf(space_b, sizeof(space_b), "panel");
    printf("dpadkeys: touch=%s \"%s\" off=(%d,%d) x=%s y=%s panic=%s space=%s\n",
           g_touch_path, g_touch_name, cfg->touch_dx, cfg->touch_dy, xb, yb, panic_b, space_b);
    fflush(stdout);
}

/* ---- banner formatting ---- */

/* lt=/rt= banner form includes the chosen ABS code so it's clear which axis
 * won the priority order in detect_axes(), e.g. "lt=0xa[0,32767]". */
static void fmt_axis(char *buf, size_t n, const axis_t *a) {
    if (a->present) snprintf(buf, n, "0x%02x[%d,%d]", (unsigned)a->code, a->min, a->max);
    else snprintf(buf, n, "none");
}

static void fmt_pair(char *buf, size_t n, const axis_t *x, const axis_t *y) {
    if (x->present && y->present) snprintf(buf, n, "x=[%d,%d]y=[%d,%d]", x->min, x->max, y->min, y->max);
    else snprintf(buf, n, "none");
}

static void print_banner(const char *pad_path, const char *pad_name, unsigned short vendor,
                          unsigned short product, const axes_t *ax, const config_t *cfg) {
    char lsb[48], rsb[48], ltb[24], rtb[24];
    fmt_pair(lsb, sizeof(lsb), &ax->ls_x, &ax->ls_y);
    fmt_pair(rsb, sizeof(rsb), &ax->rs_x, &ax->rs_y);
    fmt_axis(ltb, sizeof(ltb), &ax->lt);
    fmt_axis(rtb, sizeof(rtb), &ax->rt);
    printf("dpadkeys: pad=%s name=\"%s\" vid=0x%04x pid=0x%04x grab=%s "
           "hat=%s dpad_btns=%s ls=%s rs=%s lt=%s rt=%s deadzone=%.2f\n",
           pad_path, pad_name, vendor, product, g_grabbed ? "yes" : "no",
           ax->has_hat ? "yes" : "no", ax->has_dpad_btns ? "yes" : "no",
           lsb, rsb, ltb, rtb, cfg->deadzone);
    fflush(stdout);
}

/* ---- main ---- */

/* Copies src into dst (a dstsize-byte buffer), stripping ASCII control
 * characters (including DEL) so a hostile/garbled --device-name can't smuggle
 * one into a uinput device name, and truncating to fit dstsize (callers pass
 * sizeof(g_device_name) == UINPUT_MAX_NAME_SIZE, so the result is always
 * within uinput's UINPUT_MAX_NAME_SIZE-1 limit). Always NUL-terminates. */
static void sanitize_device_name(char *dst, size_t dstsize, const char *src) {
    size_t j = 0;
    for (size_t i = 0; src[i] != '\0' && j + 1 < dstsize; i++) {
        unsigned char c = (unsigned char)src[i];
        if (c < 0x20 || c == 0x7f) continue; /* strip control chars */
        dst[j++] = (char)c;
    }
    dst[j] = '\0';
}

static void print_usage(const char *argv0) {
    fprintf(stderr,
            "usage: %s --config FILE [--grab] [--list] [--device auto|/dev/input/eventN] "
            "[--device-name NAME] [--verbose] [--pidfile PATH] [--print-config] [--panic-chord none|SRC+SRC]\n"
            "       %s --profile fkeys|wasd [--grab] ...\n"
            "       %s --serve --config FILE [--pidfile PATH] [--status-file PATH] [--verbose]\n"
            "                  [--device ...] [--device-name NAME] [--panic-chord none|SRC+SRC] [--allow-q]\n"
            "       %s --learn [--learn-timeout-ms N] [--learn-hold-ms N] [--config FILE] [--device ...] [--pidfile PATH]\n"
            "       %s --learn-chord [--learn-timeout-ms N] [--config FILE] [--device ...] [--pidfile PATH]\n"
            "\n"
            "--serve keeps ONE daemon alive for as long as the supervising app holds its\n"
            "privilege. Both virtual devices -- the \"Odin DPad Keys\" keyboard+mouse and the\n"
            "cloned touchscreen -- are created once at startup and destroyed only at exit, so\n"
            "switching profiles never makes an input device appear or disappear (which is what\n"
            "makes the system mapper toast \"<device> connected\" on every game launch).\n"
            "\n"
            "--device-name NAME renames the keyboard+mouse uinput device (default \"Odin DPad\n"
            "Keys\"); the cloned touchscreen is always named \"NAME Touch\". Control characters\n"
            "are stripped and the result is truncated to fit uinput's 79-character name limit.\n"
            "\n"
            "The config file is the active profile. Rewrite it and send SIGUSR1 to switch:\n"
            "the whole file is re-read and the daemon transitions between states.\n"
            "  * no bindings and no touch.offset (or an empty file, or just `idle 1`)\n"
            "    -> IDLE: the pad and panel are not grabbed and nothing is emitted, so the\n"
            "       pad behaves normally for every other app.\n"
            "  * any binding -> the pad is grabbed and mapped.\n"
            "  * touch.offset dx dy -> the panel is grabbed and passed through with the offset.\n"
            "A binding naming a key the fixed serve keyboard does not carry (KEY_Q, which is\n"
            "held back so Android keeps offering the on-screen keyboard) is logged and ignored;\n"
            "a bad line never fails a reload.\n"
            "\n"
            "--status-file PATH makes the daemon write, atomically, on every transition:\n"
            "    state=idle|active touch=on|off keys=<n> panic=<count> contacts=<n> waiting=panel|pad|none\n"
            "state/touch are the active profile's intent; `keys` counts the bindings that can\n"
            "emit something; `panic` counts panic-chord firings; `contacts` is how many\n"
            "contacts the virtual touchscreen currently has down (0 at every transition -- a\n"
            "nonzero value means a pointer is stuck).\n"
            "\n"
            "`panic <src>[+<src>...]` in the profile arms the hardware escape hatch: every\n"
            "listed button-like source (btn.*, hat.*, lt, rt, key.0x.., abs.0x..; not stick\n"
            "directions) held together for 1s parks the daemon, and in --serve holding on to\n"
            "4s exits 6 with both virtual devices destroyed so a watchdog respawns fresh ones.\n"
            "In --serve the 1s step drops to idle, logs `panic: idle`, leaves the config file\n"
            "untouched and bumps `panic`; one-shot mode exits 6 straight away. There is no\n"
            "default chord: with no `panic` line there is none. --panic-chord overrides the\n"
            "profile's line for standalone use (`none`, `m1+m2`, or any source list).\n"
            "\n"
            "--learn is a separate process and needs the pad ungrabbed, so run it while the\n"
            "serve daemon is idle: write an idle config (`idle 1`) and send SIGUSR1 first if a\n"
            "profile is active, then restore the profile the same way afterwards.\n"
            "\n"
            "--learn reports a single press as \"learned <source>\", e.g. \"learned hat.up\".\n"
            "Holding one button-like control (btn.*, hat.*, lt, rt, key.0x.., abs.0x..) and\n"
            "then triggering another reports a chord, e.g. hold btn.thumbl and push the left\n"
            "stick up -> \"learned btn.thumbl+ls.up\"; hold btn.tl and press hat up ->\n"
            "\"learned btn.tl+hat.up\". --learn-hold-ms (default 150) is how long a lone\n"
            "control must still be held, with nothing else pressed yet, before it is a\n"
            "candidate hold for a later chord; release it first with nothing else having\n"
            "happened and it is reported as a plain \"learned btn.thumbl\" instead.\n"
            "\n"
            "--learn-chord captures a whole chord at once instead of one trigger: it waits\n"
            "for the first button-like press (btn.*, hat.*, lt, rt, key.0x.. -- stick\n"
            "directions are never chord-eligible and are ignored, same as raw axes with no\n"
            "button-like classification), then keeps adding further button-like presses to\n"
            "the set, in the order pressed, for as long as at least one member is still\n"
            "held. It finalizes -- \"learned btn.tl+btn.tr\", or \"learned btn.tl\" for a lone\n"
            "button -- the moment every collected button has been released, or 1500ms after\n"
            "the first press, whichever comes first. --learn-timeout-ms only bounds the wait\n"
            "for that first press; nothing pressed before it elapses is \"learned NONE\"\n"
            "(exit 3).\n",
            argv0, argv0, argv0, argv0, argv0);
}

/* ---- learn mode ---- */

/* Tracks which button-like controls (see source_can_be_hold()) are currently
 * held during --learn, by canonical name, so a trigger can be prefixed by
 * "the most recently pressed currently-held" one -- see learn_mode(). */
#define LEARN_MAX_HELD 48
typedef struct {
    bool valid;
    bool held;
    long long since;
    char name[48];
} learn_held_t;

static learn_held_t *learn_find(learn_held_t *tbl, int n, const char *name) {
    for (int i = 0; i < n; i++)
        if (tbl[i].valid && strcmp(tbl[i].name, name) == 0) return &tbl[i];
    return NULL;
}

static learn_held_t *learn_find_or_add(learn_held_t *tbl, int n, const char *name) {
    learn_held_t *e = learn_find(tbl, n, name);
    if (e) return e;
    for (int i = 0; i < n; i++) {
        if (!tbl[i].valid) {
            tbl[i].valid = true;
            tbl[i].held = false;
            snprintf(tbl[i].name, sizeof(tbl[i].name), "%s", name);
            return &tbl[i];
        }
    }
    return NULL; /* table full: vanishingly unlikely given real pads */
}

/* Most-recently-pressed control that is both currently held and has been
 * held for at least `hold_ms` -- the "currently-held" hold a fresh trigger
 * chords with. A too-recent hold doesn't count yet, so two controls pressed
 * within hold_ms of each other read as near-simultaneous rather than a
 * hold+trigger chord. */
static learn_held_t *learn_best_hold(learn_held_t *tbl, int n, long long now, long long hold_ms) {
    learn_held_t *best = NULL;
    for (int i = 0; i < n; i++) {
        if (!tbl[i].valid || !tbl[i].held) continue;
        if (now - tbl[i].since < hold_ms) continue;
        if (!best || tbl[i].since > best->since) best = &tbl[i];
    }
    return best;
}

static void learn_key_name(int code, char *out, size_t outsz) {
    int sem = semantic_for_key(code);
    if (sem >= 0) snprintf(out, outsz, "%s", SOURCE_NAMES[sem]);
    else snprintf(out, outsz, "key.0x%x", (unsigned)code);
}

/* Name for a non-stick abs axis direction (hat, lt/rt, or a raw axis) --
 * these are button-like/hold-eligible. Stick axes are named separately by
 * learn_stick_name() and are never hold-eligible. */
static void learn_abs_name(const axes_t *ax, int code, int sign, char *out, size_t outsz) {
    int sem = semantic_for_abs(code, sign); /* hat only */
    if (sem < 0) {
        if (ax->lt.present && code == ax->lt.code && sign > 0) sem = SRC_LT;
        else if (ax->rt.present && code == ax->rt.code && sign > 0) sem = SRC_RT;
    }
    if (sem >= 0) snprintf(out, outsz, "%s", SOURCE_NAMES[sem]);
    else snprintf(out, outsz, "abs.0x%x.%s", (unsigned)code, sign < 0 ? "neg" : "pos");
}

static bool learn_is_stick_axis(const axes_t *ax, int code) {
    return (ax->ls_x.present && code == ax->ls_x.code) || (ax->ls_y.present && code == ax->ls_y.code) ||
           (ax->rs_x.present && code == ax->rs_x.code) || (ax->rs_y.present && code == ax->rs_y.code);
}

static void learn_stick_name(const axes_t *ax, int code, int sign, char *out, size_t outsz) {
    int sem = -1;
    if (ax->ls_x.present && code == ax->ls_x.code) sem = sign < 0 ? SRC_LS_LEFT : SRC_LS_RIGHT;
    else if (ax->ls_y.present && code == ax->ls_y.code) sem = sign < 0 ? SRC_LS_UP : SRC_LS_DOWN;
    else if (ax->rs_x.present && code == ax->rs_x.code) sem = sign < 0 ? SRC_RS_LEFT : SRC_RS_RIGHT;
    else if (ax->rs_y.present && code == ax->rs_y.code) sem = sign < 0 ? SRC_RS_UP : SRC_RS_DOWN;
    if (sem >= 0) snprintf(out, outsz, "%s", SOURCE_NAMES[sem]);
    else snprintf(out, outsz, "abs.0x%x.%s", (unsigned)code, sign < 0 ? "neg" : "pos");
}

/* Waits (pad ungrabbed) for the first trigger and prints `learned <source>`
 * or, if a button-like control (btn.*, hat.*, lt, rt, key.0x.., abs.0x..) was
 * already held when the trigger happened, `learned <hold>+<source>` -- see
 * print_usage() for examples and --learn-hold-ms. Returns 0 on success, 3 on
 * timeout (`learned NONE`). */
static int learn_mode(int fd, const axes_t *ax, long long timeout_ms, long long hold_ms) {
    /* Per-axis state seeded from the CURRENT position so a trigger resting
     * at its minimum or an off-centre stick never counts as a press; only a
     * transition into the pressed zone does. Bipolar axes (sticks, hat,
     * HAT2-fallback triggers) use a tri-state -1/0/1 centered on the axis
     * midpoint; unipolar axes (real triggers: min >= 0, resting at/near
     * min -- see query_abs()) use a 0/1 state anchored at the minimum, same
     * as the daemon's classify_trigger()/raw_abs_pressed(), so --learn
     * fires at the same 50%-pull point the daemon will. A chosen lt/rt axis
     * is unipolar per `ax` regardless of the heuristic below (it may be
     * mid-pulled right now), mirroring detect_axes(). */
    struct { bool known; double center, half; int min, max; bool unipolar; int state; } axes[ABS_CNT];
    memset(axes, 0, sizeof(axes));
    unsigned long absbits[NLONGS(ABS_CNT)] = {0};
    ioctl(fd, EVIOCGBIT(EV_ABS, sizeof(absbits)), absbits);
    const double dz = 0.5;
    for (int c = 0; c < ABS_CNT; c++) {
        if (!TEST_BIT(c, absbits)) continue;
        struct input_absinfo info;
        if (ioctl(fd, EVIOCGABS(c), &info) < 0) continue;
        axes[c].known = true;
        axes[c].min = info.minimum;
        axes[c].max = info.maximum;
        axes[c].center = (info.minimum + info.maximum) / 2.0;
        axes[c].half = (info.maximum - info.minimum) / 2.0;
        if (axes[c].half <= 0) axes[c].half = 1.0;
        double range = info.maximum - info.minimum;
        axes[c].unipolar = info.minimum >= 0 && info.maximum > 0 &&
                            info.value <= info.minimum + 0.05 * range;
        if (ax->lt.present && c == ax->lt.code) axes[c].unipolar = ax->lt.unipolar;
        else if (ax->rt.present && c == ax->rt.code) axes[c].unipolar = ax->rt.unipolar;
        if (axes[c].unipolar) {
            double r = axes[c].max - axes[c].min;
            double frac = (info.value - axes[c].min) / (r > 0 ? r : 1.0);
            axes[c].state = frac >= dz ? 1 : 0;
        } else {
            double v = (info.value - axes[c].center) / axes[c].half;
            axes[c].state = v >= dz ? 1 : (v <= -dz ? -1 : 0);
        }
    }

    static learn_held_t held[LEARN_MAX_HELD];
    memset(held, 0, sizeof(held));

    long long deadline = now_ms() + timeout_ms;
    struct pollfd pfd = { .fd = fd, .events = POLLIN };
    char out[96] = {0};

    while (g_running && !out[0]) {
        long long now = now_ms();
        if (now >= deadline) break;
        int pr = poll(&pfd, 1, (int)(deadline - now));
        if (pr < 0) { if (errno == EINTR) continue; break; }
        if (pr == 0) break;
        struct input_event ev;
        ssize_t n = read(fd, &ev, sizeof(ev));
        if (n != (ssize_t)sizeof(ev)) {
            if (n < 0 && (errno == EAGAIN || errno == EINTR)) continue;
            break; /* ENODEV etc. */
        }
        now = now_ms();

        if (ev.type == EV_KEY) {
            if (ev.value == 2) continue; /* autorepeat */
            char name[48];
            learn_key_name(ev.code, name, sizeof(name));
            if (ev.value == 1) {
                learn_held_t *best = learn_best_hold(held, LEARN_MAX_HELD, now, hold_ms);
                if (best) { snprintf(out, sizeof(out), "%s+%s", best->name, name); break; }
                learn_held_t *e = learn_find_or_add(held, LEARN_MAX_HELD, name);
                if (e) { e->held = true; e->since = now; }
            } else {
                learn_held_t *e = learn_find(held, LEARN_MAX_HELD, name);
                if (e) e->held = false;
                if (!out[0]) { snprintf(out, sizeof(out), "%s", name); break; }
            }
            continue;
        }

        if (ev.type != EV_ABS || ev.code >= ABS_CNT || !axes[ev.code].known) continue;

        if (learn_is_stick_axis(ax, ev.code)) {
            double v = (ev.value - axes[ev.code].center) / axes[ev.code].half;
            int st = axes[ev.code].state;
            int ns = st == 1 ? (v >= dz - 0.1 ? 1 : 0)
                   : st == -1 ? (v <= -(dz - 0.1) ? -1 : 0)
                   : (v >= dz ? 1 : (v <= -dz ? -1 : 0));
            if (ns == st) continue;
            axes[ev.code].state = ns;
            /* stick directions are never hold-eligible: a release is not a
             * trigger, and a fresh press is a trigger with no press/release
             * bookkeeping of its own. */
            if (ns == 0) continue;
            char name[48];
            learn_stick_name(ax, ev.code, ns, name, sizeof(name));
            learn_held_t *best = learn_best_hold(held, LEARN_MAX_HELD, now, hold_ms);
            if (best) snprintf(out, sizeof(out), "%s+%s", best->name, name);
            else snprintf(out, sizeof(out), "%s", name);
            break;
        }

        if (axes[ev.code].unipolar) {
            /* Real trigger axis (chosen lt/rt, or any other unipolar raw
             * abs axis): press-only, thresholded off the minimum at the
             * same 50%-pull point as the daemon (classify_trigger()/
             * raw_abs_pressed()). Never reports on release -- there is no
             * meaningful ".neg" for a unipolar axis, so a bare release
             * (e.g. the trigger was already held when --learn started)
             * stays silent instead of misreporting one. */
            double r = axes[ev.code].max - axes[ev.code].min;
            if (r <= 0) r = 1.0;
            double frac = (ev.value - axes[ev.code].min) / r;
            int st = axes[ev.code].state; /* 0 or 1 */
            int ns = st ? (frac >= dz - 0.1 ? 1 : 0) : (frac >= dz ? 1 : 0);
            if (ns == st) continue;
            axes[ev.code].state = ns;
            char name[48];
            learn_abs_name(ax, ev.code, 1, name, sizeof(name)); /* lt/rt or abs.0xNN.pos -- never .neg */
            if (ns == 0) {
                learn_held_t *e = learn_find(held, LEARN_MAX_HELD, name);
                if (e) e->held = false;
                continue; /* no report on release */
            }
            learn_held_t *best = learn_best_hold(held, LEARN_MAX_HELD, now, hold_ms);
            if (best) { snprintf(out, sizeof(out), "%s+%s", best->name, name); break; }
            learn_held_t *e = learn_find_or_add(held, LEARN_MAX_HELD, name);
            if (e) { e->held = true; e->since = now; }
            continue;
        }

        /* bipolar hat / HAT2-fallback trigger / raw abs axis: button-like,
         * so track press+release like an EV_KEY control. A direct sign flip
         * (-1 <-> 1, e.g. a hat snapping past centre in one event) is a
         * release of the old direction followed by a press of the new
         * one. */
        {
            double v = (ev.value - axes[ev.code].center) / axes[ev.code].half;
            int st = axes[ev.code].state;
            int ns = st == 1 ? (v >= dz - 0.1 ? 1 : 0)
                   : st == -1 ? (v <= -(dz - 0.1) ? -1 : 0)
                   : (v >= dz ? 1 : (v <= -dz ? -1 : 0));
            if (ns == st) continue;
            axes[ev.code].state = ns;

            if (st != 0) {
                char name[48];
                learn_abs_name(ax, ev.code, st, name, sizeof(name));
                learn_held_t *e = learn_find(held, LEARN_MAX_HELD, name);
                if (e) e->held = false;
                /* A pure release (back to centre) may itself be the plain
                 * report; a direct sign flip (st and ns both nonzero) falls
                 * through to report the new direction's press instead. */
                if (ns == 0 && !out[0]) { snprintf(out, sizeof(out), "%s", name); break; }
            }
            if (ns != 0) {
                char name[48];
                learn_abs_name(ax, ev.code, ns, name, sizeof(name));
                learn_held_t *best = learn_best_hold(held, LEARN_MAX_HELD, now, hold_ms);
                if (best) { snprintf(out, sizeof(out), "%s+%s", best->name, name); break; }
                learn_held_t *e = learn_find_or_add(held, LEARN_MAX_HELD, name);
                if (e) { e->held = true; e->since = now; }
            }
        }
    }
    if (!out[0]) {
        printf("learned NONE\n");
        fflush(stdout);
        return 3;
    }
    printf("learned %s\n", out);
    fflush(stdout);
    return 0;
}

/* ---- learn-chord mode ---- */

#define LEARN_CHORD_MAX 8
#define LEARN_CHORD_WINDOW_MS 1500

static bool learn_chord_any_held(const bool *held, int n) {
    for (int i = 0; i < n; i++)
        if (held[i]) return true;
    return false;
}

static void learn_chord_release(char order[][48], bool *held, int n, const char *name) {
    for (int i = 0; i < n; i++)
        if (strcmp(order[i], name) == 0) { held[i] = false; return; }
    /* Not a member -- either not part of the chord, or already-held before
     * --learn-chord started (so never recorded a press): ignore, same as any
     * other release of a control that isn't in the set. */
}

/* Adds `name` to the chord (or, if already a member, marks it held again).
 * Starts the fixed collection window on the very first press. */
static int learn_chord_press(char order[][48], bool *held, int n, const char *name,
                              long long now, long long *chord_deadline) {
    for (int i = 0; i < n; i++)
        if (strcmp(order[i], name) == 0) { held[i] = true; return n; }
    if (n == 0) *chord_deadline = now + LEARN_CHORD_WINDOW_MS;
    if (n < LEARN_CHORD_MAX) {
        snprintf(order[n], 48, "%s", name);
        held[n] = true;
        n++;
    }
    return n;
}

/* Waits (pad ungrabbed) for the first button-like press (btn.*, hat.*, lt,
 * rt, key.0x..; stick directions and any other non-button-like control are
 * ignored throughout), then keeps adding further button-like presses to the
 * chord, in the order pressed, for as long as at least one member is still
 * held. Finalizes -- prints `learned a+b[+c]` (a lone button prints
 * `learned a`) and returns 0 -- the moment every collected button has been
 * released, or LEARN_CHORD_WINDOW_MS after the first press, whichever comes
 * first. `timeout_ms` bounds only the wait for that first press; nothing
 * pressed before it elapses prints `learned NONE` and returns 3. See
 * print_usage() for examples. */
static int learn_chord_mode(int fd, const axes_t *ax, long long timeout_ms) {
    struct { bool known; double center, half; int min, max; bool unipolar; int state; } axes[ABS_CNT];
    memset(axes, 0, sizeof(axes));
    unsigned long absbits[NLONGS(ABS_CNT)] = {0};
    ioctl(fd, EVIOCGBIT(EV_ABS, sizeof(absbits)), absbits);
    const double dz = 0.5;
    for (int c = 0; c < ABS_CNT; c++) {
        if (!TEST_BIT(c, absbits)) continue;
        struct input_absinfo info;
        if (ioctl(fd, EVIOCGABS(c), &info) < 0) continue;
        axes[c].known = true;
        axes[c].min = info.minimum;
        axes[c].max = info.maximum;
        axes[c].center = (info.minimum + info.maximum) / 2.0;
        axes[c].half = (info.maximum - info.minimum) / 2.0;
        if (axes[c].half <= 0) axes[c].half = 1.0;
        double range = info.maximum - info.minimum;
        axes[c].unipolar = info.minimum >= 0 && info.maximum > 0 &&
                            info.value <= info.minimum + 0.05 * range;
        if (ax->lt.present && c == ax->lt.code) axes[c].unipolar = ax->lt.unipolar;
        else if (ax->rt.present && c == ax->rt.code) axes[c].unipolar = ax->rt.unipolar;
        if (axes[c].unipolar) {
            double r = axes[c].max - axes[c].min;
            double frac = (info.value - axes[c].min) / (r > 0 ? r : 1.0);
            axes[c].state = frac >= dz ? 1 : 0;
        } else {
            double v = (info.value - axes[c].center) / axes[c].half;
            axes[c].state = v >= dz ? 1 : (v <= -dz ? -1 : 0);
        }
    }

    char order[LEARN_CHORD_MAX][48];
    bool held[LEARN_CHORD_MAX] = {0};
    int n = 0;
    long long chord_deadline = -1; /* set once the first button is pressed */
    long long wait_deadline = now_ms() + timeout_ms;
    struct pollfd pfd = { .fd = fd, .events = POLLIN };

    while (g_running) {
        long long now = now_ms();
        long long cur_deadline = n == 0 ? wait_deadline : chord_deadline;
        if (now >= cur_deadline) break;
        int pr = poll(&pfd, 1, (int)(cur_deadline - now));
        if (pr < 0) { if (errno == EINTR) continue; break; }
        if (pr == 0) break;
        struct input_event ev;
        ssize_t rd = read(fd, &ev, sizeof(ev));
        if (rd != (ssize_t)sizeof(ev)) {
            if (rd < 0 && (errno == EAGAIN || errno == EINTR)) continue;
            break; /* ENODEV etc. */
        }
        now = now_ms();

        if (ev.type == EV_KEY) {
            if (ev.value == 2) continue; /* autorepeat */
            char name[48];
            learn_key_name(ev.code, name, sizeof(name));
            if (ev.value == 1) n = learn_chord_press(order, held, n, name, now, &chord_deadline);
            else learn_chord_release(order, held, n, name);
            if (n > 0 && !learn_chord_any_held(held, n)) break;
            continue;
        }

        if (ev.type != EV_ABS || ev.code >= ABS_CNT || !axes[ev.code].known) continue;
        if (learn_is_stick_axis(ax, ev.code)) continue; /* never chord-eligible */

        if (axes[ev.code].unipolar) {
            /* Real trigger axis: press-only, same 50%-pull threshold as the
             * daemon. A release when the trigger isn't a chord member (e.g.
             * already held when --learn-chord started) is silently ignored
             * by learn_chord_release(). */
            double r = axes[ev.code].max - axes[ev.code].min;
            if (r <= 0) r = 1.0;
            double frac = (ev.value - axes[ev.code].min) / r;
            int st = axes[ev.code].state;
            int ns = st ? (frac >= dz - 0.1 ? 1 : 0) : (frac >= dz ? 1 : 0);
            if (ns == st) continue;
            axes[ev.code].state = ns;
            char name[48];
            learn_abs_name(ax, ev.code, 1, name, sizeof(name));
            if (ns == 1) n = learn_chord_press(order, held, n, name, now, &chord_deadline);
            else learn_chord_release(order, held, n, name);
            if (n > 0 && !learn_chord_any_held(held, n)) break;
            continue;
        }

        /* bipolar hat / HAT2-fallback trigger / raw abs axis: button-like,
         * so track press+release like an EV_KEY control. A direct sign flip
         * is a release of the old direction followed by a press of the new
         * one. */
        {
            double v = (ev.value - axes[ev.code].center) / axes[ev.code].half;
            int st = axes[ev.code].state;
            int ns = st == 1 ? (v >= dz - 0.1 ? 1 : 0)
                   : st == -1 ? (v <= -(dz - 0.1) ? -1 : 0)
                   : (v >= dz ? 1 : (v <= -dz ? -1 : 0));
            if (ns == st) continue;
            axes[ev.code].state = ns;
            if (st != 0) {
                char rname[48];
                learn_abs_name(ax, ev.code, st, rname, sizeof(rname));
                learn_chord_release(order, held, n, rname);
            }
            if (ns != 0) {
                char pname[48];
                learn_abs_name(ax, ev.code, ns, pname, sizeof(pname));
                n = learn_chord_press(order, held, n, pname, now, &chord_deadline);
            }
            if (n > 0 && !learn_chord_any_held(held, n)) break;
        }
    }

    if (n == 0) {
        printf("learned NONE\n");
        fflush(stdout);
        return 3;
    }
    char out[256] = {0};
    size_t off = 0;
    for (int i = 0; i < n; i++) {
        int w = snprintf(out + off, sizeof(out) - off, "%s%s", i ? "+" : "", order[i]);
        if (w < 0 || (size_t)w >= sizeof(out) - off) break;
        off += (size_t)w;
    }
    printf("learned %s\n", out);
    fflush(stdout);
    return 0;
}


/* --dump: print every EV_KEY / EV_ABS(hat, non-stick) event from ALL evdev
 * nodes, unbuffered, until killed. Debug aid for identifying buttons. */
static int dump_all(void) {
    struct pollfd pfd[32]; char paths[32][32]; int n = 0;
    for (int i = 0; i < 32; i++) {
        char path[32]; snprintf(path, sizeof path, "/dev/input/event%d", i);
        int fd = open(path, O_RDONLY | O_NONBLOCK);
        if (fd < 0) continue;
        pfd[n].fd = fd; pfd[n].events = POLLIN; snprintf(paths[n], 32, "%s", path); n++;
    }
    fprintf(stderr, "dump: watching %d devices (Ctrl-C to stop)\n", n);
    while (g_running) {
        if (poll(pfd, n, 1000) <= 0) continue;
        for (int i = 0; i < n; i++) {
            if (!(pfd[i].revents & POLLIN)) continue;
            struct input_event ev;
            while (read(pfd[i].fd, &ev, sizeof ev) == (ssize_t)sizeof ev) {
                if (ev.type == EV_KEY || (ev.type == EV_ABS && ev.code >= ABS_HAT0X && ev.code <= ABS_HAT3Y)) {
                    fprintf(stderr, "%s type=%u code=0x%03x value=%d\n", paths[i], ev.type, ev.code, ev.value);
                    fflush(stderr);
                }
            }
        }
    }
    return 0;
}

/* ---- serve mode ---- */

/* Number of bindings that can actually emit something -- plain targets plus
 * chord targets, excluding NONE. Reported as keys=<n> in the status file and
 * the transition log so the supervisor can tell "profile applied" from
 * "profile parsed to nothing". */
static int count_bindings(const config_t *cfg) {
    int n = 0;
    for (int i = 0; i < n_sources(cfg); i++)
        if (cfg->target[i] != TARGET_NONE) n++;
    for (int i = 0; i < cfg->n_chords; i++)
        if (cfg->chords[i].target != TARGET_NONE) n++;
    return n;
}

/* Whether this profile needs the pad grabbed. Deliberately keyed off
 * cfg->defined rather than the targets: a profile that maps every button to
 * NONE has no bindings but very much wants the grab, since swallowing the
 * pad's own events is the entire point of those lines. */
static bool config_wants_pad(const config_t *cfg) {
    if (cfg->idle) return false;
    for (int i = 0; i < n_sources(cfg); i++)
        if (cfg->defined[i]) return true;
    return cfg->n_chords > 0;
}

/* Drops any binding whose key is not on the fixed serve keyboard (in
 * practice only KEY_Q -- see key_in_superset()) so a profile can never ask
 * for a key the device cannot emit. The binding becomes NONE rather than
 * disappearing: the pad is grabbed either way, so leaving it defined keeps
 * the button swallowed instead of half-working. Never fails a reload. */
static int filter_targets_to_superset(config_t *cfg, const char *ctx) {
    int dropped = 0;
    for (int i = 0; i < n_sources(cfg); i++) {
        if (cfg->target[i] < 0 || key_in_superset(cfg->target[i])) continue;
        fprintf(stderr, "dpadkeys: %s: %s -> %s is not on the serve keyboard; ignoring\n",
                ctx, source_name(cfg, i), key_name(cfg->target[i]));
        cfg->target[i] = TARGET_NONE;
        dropped++;
    }
    for (int i = 0; i < cfg->n_chords; i++) {
        chord_t *c = &cfg->chords[i];
        if (c->target < 0 || key_in_superset(c->target)) continue;
        fprintf(stderr, "dpadkeys: %s: %s+%s -> %s is not on the serve keyboard; ignoring\n",
                ctx, source_name(cfg, c->hold), source_name(cfg, c->src), key_name(c->target));
        c->target = TARGET_NONE;
        dropped++;
    }
    if (dropped) fflush(stderr);
    return dropped;
}

/* Writes the one-line status the supervisor polls, atomically (temp file in
 * the same directory + rename), so a reader never sees a half-written line.
 * Called on every transition, including the panic chord's. */
static void write_status(const config_t *cfg) {
    if (!g_status_path) return;
    char tmp[PATH_MAX];
    if (snprintf(tmp, sizeof(tmp), "%s.tmp", g_status_path) >= (int)sizeof(tmp)) return;
    FILE *f = fopen(tmp, "w");
    if (!f) {
        fprintf(stderr, "dpadkeys: serve: cannot write status '%s': %s\n", tmp, strerror(errno));
        fflush(stderr);
        return;
    }
    /* `contacts` is the number of contacts the virtual touchscreen currently
     * has down. It should be 0 at every transition -- a nonzero value in the
     * status file is the app's direct signal that a pointer is stuck. */
    /* `waiting` names the device the daemon is holding off on because it is
     * not neutral, so the app can say "lift your finger" instead of looking
     * hung. Single-valued by contract; the panel wins if both are pending,
     * since that is the one the user has to act on. */
    /* `gen` is the touch.generation the live virtual touchscreen was built
     * for, so the app can confirm a rotation-triggered rebuild landed.
     * `space` is which coordinate space that clone was built in. */
    fprintf(f, "state=%s touch=%s keys=%d panic=%ld contacts=%d waiting=%s gen=%d space=%s\n",
            (g_want_pad || g_want_touch) ? "active" : "idle",
            g_want_touch ? "on" : "off", count_bindings(cfg), g_panic_count,
            touch_contacts_live(),
            g_touch_wait ? "panel" : (g_pad_wait ? "pad" : "none"),
            g_touch_gen_applied,
            g_touch_display_applied_set ? "display" : "panel");
    fflush(f);
    fsync(fileno(f));
    fclose(f);
    g_status_contacts = touch_contacts_live();
    g_status_written_ms = now_ms();
    if (rename(tmp, g_status_path) < 0) {
        fprintf(stderr, "dpadkeys: serve: cannot rename status into place: %s\n", strerror(errno));
        fflush(stderr);
        unlink(tmp);
    }
}

static void log_serve_state(const config_t *cfg) {
    if (g_want_pad || g_want_touch) {
        char tb[48], pb[160];
        if (g_want_touch) snprintf(tb, sizeof(tb), "on %d %d", cfg->touch_dx, cfg->touch_dy);
        else snprintf(tb, sizeof(tb), "off");
        panic_spec_str(cfg, pb, sizeof(pb));
        fprintf(stderr, "dpadkeys: serve: state=active keys=%d touch=%s panic=%s\n",
                count_bindings(cfg), tb, cfg->n_panic > 0 ? pb : "none");
    } else {
        fprintf(stderr, "dpadkeys: serve: state=idle\n");
    }
    fflush(stderr);
}

static void serve_schedule_retry(void) { g_retry_due_ms = now_ms() + SERVE_RETRY_MS; }

/* Drops every virtual key and wheel repeat the daemon is currently holding,
 * and forgets all per-source and per-axis state. Called before a mapping
 * swap (so a key bound only by the outgoing profile can never stick down)
 * and when going idle. The axis states are cleared too, so a stick already
 * deflected across the switch re-presses under the new mapping on its next
 * event rather than being swallowed by handle_stick_2d's no-change test. */
static void release_all_virtual(config_t *cfg) {
    release_all_sources(cfg);
    for (int c = 0; c < KEY_CNT; c++) {
        if (g_key_count[c] > 0) {
            emit_key(g_uinput_fd, c, 0);
            g_key_count[c] = 0;
        }
    }
    for (int w = 0; w < WHEEL_COUNT; w++) { g_wheel_count[w] = 0; g_wheel_next_due_ms[w] = 0; }
    memset(g_source_pressed, 0, sizeof(g_source_pressed));
    memset(g_source_press_seq, 0, sizeof(g_source_press_seq));
    memset(g_resolved_target, 0, sizeof(g_resolved_target));
    g_ax.ls_active = g_ax.rs_active = false;
    g_ax.ls_dirs = g_ax.rs_dirs = 0;
    g_ax.ls_x.state = g_ax.ls_y.state = g_ax.rs_x.state = g_ax.rs_y.state = 0;
    g_ax.lt.state = g_ax.rt.state = 0;
    for (int i = 0; i < cfg->n_raw; i++) cfg->raw[i].axis.state = 0;
    memset(g_panic_held, 0, sizeof(g_panic_held));
    g_chord_start_ms = -1;
    g_panic_idle_fired = false;
}

/* Raw-source problems that can only be spotted once the pad is open. In
 * one-shot mode these exit(2) before the daemon ever runs; in serve mode a
 * profile is never allowed to kill the daemon, so they are warnings and the
 * offending raw source simply behaves oddly (double-firing with the semantic
 * source it collides with, or never firing for a `.neg` on a trigger). */
static void serve_warn_raw_problems(const config_t *cfg) {
    const char *coll = raw_axis_collision(cfg, &g_ax);
    if (coll) { fprintf(stderr, "dpadkeys: serve: %s\n", coll); fflush(stderr); }
    const char *bad = raw_unipolar_neg_reject(cfg, &g_ax);
    if (bad) { fprintf(stderr, "dpadkeys: serve: %s\n", bad); fflush(stderr); }
}

/* Takes the grab on a pad that is already open (freshly, or after a
 * wait-for-neutral) and derives everything that depends on holding it.
 * `capped_why` is NULL normally and names what is still held when
 * GRAB_WAIT_MAX_MS ran out and we are grabbing anyway. Returns false (and
 * drops the pad) if the grab fails. */
static bool pad_finish_grab(config_t *cfg, const char *capped_why) {
    long long waited = g_pad_wait_since_ms >= 0 ? now_ms() - g_pad_wait_since_ms : 0;
    bool was_waiting = g_pad_wait;
    if (ioctl(g_pad_fd, EVIOCGRAB, 1) < 0) {
        close(g_pad_fd);
        g_pad_fd = -1;
        g_pad_wait = false;
        g_pad_wait_since_ms = -1;
        return false;
    }
    g_grabbed = true;
    g_pad_wait = false;
    g_pad_wait_since_ms = -1;
    /* Re-derived after the grab, not before: the axis metadata query_abs()
     * does (notably the unipolar resting-value heuristic) is only honest once
     * the pad is actually at rest. */
    detect_axes(g_pad_fd, &g_ax);
    raw_query_axes(g_pad_fd, cfg);
    propagate_trigger_unipolar(cfg, &g_ax);
    serve_warn_raw_problems(cfg);
    if (capped_why)
        fprintf(stderr, "dpadkeys: pad: still not neutral after %d ms (%s); grabbing anyway\n",
                GRAB_WAIT_MAX_MS, capped_why);
    else if (was_waiting)
        fprintf(stderr, "dpadkeys: pad: grabbed after wait (%lld ms)\n", waited);
    fprintf(stderr, "dpadkeys: serve: pad=%s name=\"%s\" vid=0x%04x pid=0x%04x grabbed\n",
            g_pad_path, g_pad_name, g_pad_vendor, g_pad_product);
    fflush(stderr);
    return true;
}

typedef enum {
    PAD_ACQ_OK = 0,
    PAD_ACQ_WAITING,   /* open and ungrabbed: something on it is still held */
    PAD_ACQ_FAIL,
} pad_acquire_t;

/* One non-blocking attempt to open and grab the pad. Non-blocking matters:
 * the one-shot path can afford to spin until a pad shows up, but a serve
 * daemon that blocked here would stop answering SIGUSR1 -- the app could not
 * even park it back to idle. A failure just asks for a retry. */
static pad_acquire_t serve_acquire_pad(config_t *cfg, const char *device_override) {
    if (g_pad_fd >= 0) return g_pad_wait ? PAD_ACQ_WAITING : PAD_ACQ_OK;
    memset(g_pad_path, 0, sizeof(g_pad_path));
    memset(g_pad_name, 0, sizeof(g_pad_name));
    if (device_override) {
        g_pad_fd = open(device_override, O_RDWR);
        if (g_pad_fd < 0) return PAD_ACQ_FAIL;
        snprintf(g_pad_path, sizeof(g_pad_path), "%s", device_override);
        bool has_south;
        device_info(g_pad_fd, &g_pad_vendor, &g_pad_product, g_pad_name, sizeof(g_pad_name), &has_south);
    } else {
        g_pad_fd = find_pad(cfg->device_match, O_RDWR, g_pad_path, sizeof(g_pad_path),
                            g_pad_name, sizeof(g_pad_name), &g_pad_vendor, &g_pad_product);
        if (g_pad_fd < 0) return PAD_ACQ_FAIL;
    }
    /* Never grab a pad with a button or stick held: the release would land
     * inside our grab and Android would keep that button down for the whole
     * active period (see GRAB_WAIT_MAX_MS). detect_axes() first -- pad_neutral
     * needs the axis layout, and every ioctl it does is read-only. */
    detect_axes(g_pad_fd, &g_ax);
    char why[80];
    if (!pad_neutral(g_pad_fd, cfg, why, sizeof(why))) {
        g_pad_wait = true;
        g_pad_wait_since_ms = now_ms();
        fprintf(stderr, "dpadkeys: pad: waiting for neutral before grabbing\n");
        fflush(stderr);
        return PAD_ACQ_WAITING;
    }
    /* The grab is all-or-nothing here exactly as in one-shot mode: an
     * ungrabbed active profile would let the pad's own events reach the game
     * alongside our keys. */
    return pad_finish_grab(cfg, NULL) ? PAD_ACQ_OK : PAD_ACQ_FAIL;
}

static void serve_release_pad(config_t *cfg) {
    g_pad_wait = false;
    g_pad_wait_since_ms = -1;
    if (g_pad_fd < 0) { g_panic_watch_until_ms = -1; return; }
    g_panic_watch_until_ms = -1;
    /* Order matters, the mirror image of the grab rule: every virtual key we
     * are holding goes up BEFORE the ungrab, so the game never sees our key
     * and the pad's own button down at the same instant. */
    release_all_virtual(cfg);
    if (g_grabbed) { ioctl(g_pad_fd, EVIOCGRAB, 0); g_grabbed = false; }
    close(g_pad_fd);
    g_pad_fd = -1;
    fprintf(stderr, "dpadkeys: serve: pad released\n");
    fflush(stderr);
}

/* The panic chord's landing state: everything let go, nothing wanted, no
 * retries pending. Only a SIGUSR1 reload can bring the daemon back. */
static void serve_go_idle(config_t *cfg) {
    release_all_virtual(cfg);
    serve_release_pad(cfg);
    touch_release_panel();
    g_want_pad = false;
    g_want_touch = false;
    g_retry_due_ms = -1;
}

/* The clone factory lives further down with the rest of serve startup. */
static bool serve_create_touch_device(const config_t *cfg);

/* `touch.generation` changed: throw the virtual touchscreen away and build an
 * identical one (same cloned capabilities, name and ids).
 *
 * This is the cure for Android mishandling a persistent clone across display
 * rotation -- see config_t::touch_generation. The caller re-grabs afterwards
 * through the ordinary touch_enable() path, so a rebuild while touch is
 * active is exactly a normal activation (neutral-wait, grab, carry-over) and
 * a rebuild while idle grabs nothing.
 *
 * Nothing can be forwarded across the swap: this runs inside the SIGUSR1
 * transition at the top of the event loop, the panel fd is closed (by
 * touch_release_panel) before the clone fd is destroyed, and the poll set is
 * rebuilt from g_touch_fd on every loop iteration, so no fd of a destroyed
 * device is ever read from or written to. */
static void serve_recreate_touch_clone(const config_t *cfg) {
    /* Releases every contact the clone still has down -- while it can still be
     * written to -- then drops the panel grab and fd if we hold them. */
    touch_release_panel();
    touch_forget_contacts();   /* slot mirror, BTN_TOUCH, implicit slot */
    g_clone_next_tid = 0x40000000; /* remap-id space: nothing to clash with any more */
    destroy_uinput(g_touch_uinput_fd);
    g_touch_uinput_fd = -1;
    /* A write that failed against the device we just destroyed must not make
     * the loop tear the replacement down on the next iteration. */
    g_touch_uinput_dead = false;
    bool ok = serve_create_touch_device(cfg);
    if (!ok) {
        fprintf(stderr, "dpadkeys: touch: could not recreate the virtual touchscreen; "
                        "touch stays off until the next reload\n");
        fflush(stderr);
    }
    g_touch_gen_applied = cfg->touch_generation;
    g_touch_display_applied_set = cfg->touch_display_set;
    g_touch_display_applied_w = cfg->touch_display_w;
    g_touch_display_applied_h = cfg->touch_display_h;
    g_touch_display_applied_r = cfg->touch_display_r;
    if (ok) {
        fprintf(stderr, "dpadkeys: touch: generation %d -> recreated virtual touchscreen "
                        "(space=%s)\n", cfg->touch_generation,
                cfg->touch_display_set ? "display" : "panel");
        fflush(stderr);
    }
}

/* Makes `newcfg` the active profile. The uinput devices are never touched
 * here -- that is the entire point of serve mode -- except when
 * touch.generation asks for the clone to be rebuilt, so this is otherwise
 * purely: let go of whatever the old profile held, swap the mappings in, then
 * acquire whatever the new one wants. */
static void serve_apply(config_t *cfg, const config_t *newcfg, const char *device_override) {
    bool match_changed = strcmp(cfg->device_match, newcfg->device_match) != 0;
    bool touch_dev_changed = strcmp(cfg->touch_device, newcfg->touch_device) != 0;

    release_all_virtual(cfg);
    *cfg = *newcfg;
    g_wheel_repeat_ms = cfg->wheel_repeat_ms > 0 ? cfg->wheel_repeat_ms : 120;

    g_want_pad = config_wants_pad(cfg);
    g_want_touch = !cfg->idle && cfg->touch_offset_set;

    /* Before the no-clone test below, so a profile that wants touch is judged
     * against the device this reload is about to build, and before the
     * acquisition further down, so the re-grab targets the new clone. */
    bool touch_display_changed = cfg->touch_display_set != g_touch_display_applied_set ||
        (cfg->touch_display_set &&
         (cfg->touch_display_w != g_touch_display_applied_w ||
          cfg->touch_display_h != g_touch_display_applied_h ||
          cfg->touch_display_r != g_touch_display_applied_r));
    if (cfg->touch_generation != g_touch_gen_applied || touch_display_changed)
        serve_recreate_touch_clone(cfg);

    if (g_want_touch && g_touch_uinput_fd < 0) {
        fprintf(stderr, "dpadkeys: serve: profile asks for touch.offset but there is no "
                        "virtual touchscreen; ignoring\n");
        fflush(stderr);
        g_want_touch = false;
    }

    /* device.match / touch.device changing means the profile may want a
     * different node, so drop what we hold and re-pick below. */
    if (g_pad_fd >= 0 && (!g_want_pad || match_changed)) serve_release_pad(cfg);
    if (g_touch_grabbed && (!g_want_touch || touch_dev_changed)) touch_release_panel();

    g_retry_due_ms = -1;
    if (g_want_pad) {
        if (g_pad_fd >= 0) {
            /* Pad already in hand: only the raw-source axis metadata is
             * profile-specific and has to be re-derived. */
            raw_query_axes(g_pad_fd, cfg);
            propagate_trigger_unipolar(cfg, &g_ax);
            serve_warn_raw_problems(cfg);
        } else if (serve_acquire_pad(cfg, device_override) == PAD_ACQ_FAIL) {
            fprintf(stderr, "dpadkeys: serve: no pad yet; will keep trying\n");
            fflush(stderr);
            serve_schedule_retry();
        }
    }
    if (g_want_touch && !g_touch_grabbed && !g_touch_wait) {
        touch_enable_result_t r = touch_enable(cfg);
        if (r == TOUCH_ENABLE_OK) {
            fprintf(stderr, "dpadkeys: serve: touch grabbed %s off=(%d,%d)\n",
                    g_touch_path, cfg->touch_dx, cfg->touch_dy);
            fflush(stderr);
        } else if (r != TOUCH_ENABLE_WAITING) {
            fprintf(stderr, "dpadkeys: serve: could not grab the panel; will keep trying\n");
            fflush(stderr);
            serve_schedule_retry();
        }
    }

    log_serve_state(cfg);
    write_status(cfg);
}

/* Re-reads the whole config, tolerating the app rewriting it underneath us:
 * an open failure (or a zero-length file, which is what a truncate-then-write
 * looks like mid-flight) is retried once after 50ms. A file that is still
 * empty on the second look is taken at face value -- an empty config is a
 * perfectly good idle profile. Returns false only if the file is gone. */
static bool load_config_retry(const char *path, config_t *out) {
    for (int attempt = 0; attempt < 2; attempt++) {
        struct stat st;
        bool empty_now = (stat(path, &st) == 0 && st.st_size == 0);
        if (!empty_now && load_config_file(path, out, true)) return true;
        if (attempt == 0) {
            struct timespec ts = { 0, 50 * 1000 * 1000 };
            nanosleep(&ts, NULL);
        }
    }
    struct stat st;
    if (stat(path, &st) == 0) { init_config(out); return true; }
    return false;
}

static void serve_reload(config_t *cfg, const char *config_path, const char *device_override) {
    static config_t next;
    if (!load_config_retry(config_path, &next)) {
        fprintf(stderr, "dpadkeys: serve: cannot read config '%s': %s; keeping current profile\n",
                config_path, strerror(errno));
        fflush(stderr);
        return;
    }
    apply_panic_cli_override(&next);
    filter_targets_to_superset(&next, config_path);
    serve_apply(cfg, &next, device_override);
}

/* Retries whatever the active profile wants but does not yet hold (pad,
 * panel, or both). Called from the loop when g_retry_due_ms comes due, so a
 * pad that is still being recreated after a controller-style switch, or a
 * panel that has not come back yet, is picked up without ever blocking. */
static void serve_retry_acquire(config_t *cfg, const char *device_override) {
    static long long last_log_ms = -1;
    long long now = now_ms();
    bool log_now = (last_log_ms < 0 || now - last_log_ms >= 10000);
    bool pending = false;

    /* A device we are merely waiting on (open, ungrabbed, not neutral) is not
     * pending acquisition -- the event loop owns it until it goes neutral. */
    if (g_want_pad && g_pad_fd < 0) {
        pad_acquire_t pr = serve_acquire_pad(cfg, device_override);
        if (pr == PAD_ACQ_FAIL) pending = true;
        else write_status(cfg);
    }
    if (g_want_touch && !g_touch_grabbed && !g_touch_wait) {
        g_touch_quiet = !log_now;
        touch_enable_result_t r = touch_enable(cfg);
        g_touch_quiet = false;
        if (r == TOUCH_ENABLE_WAITING) {
            write_status(cfg);
        } else if (r == TOUCH_ENABLE_OK) {
            fprintf(stderr, "dpadkeys: serve: touch grabbed %s off=(%d,%d)\n",
                    g_touch_path, cfg->touch_dx, cfg->touch_dy);
            fflush(stderr);
            write_status(cfg);
        } else {
            pending = true;
        }
    }
    if (pending) {
        if (log_now) {
            last_log_ms = now;
            fprintf(stderr, "dpadkeys: serve: still waiting for %s%s%s\n",
                    (g_want_pad && g_pad_fd < 0) ? "pad" : "",
                    (g_want_pad && g_pad_fd < 0 && g_want_touch && !g_touch_grabbed) ? "+" : "",
                    (g_want_touch && !g_touch_grabbed) ? "panel" : "");
            fflush(stderr);
        }
        serve_schedule_retry();
    }
}

/* Creates the virtual touchscreen once, cloned from the real panel, then
 * closes the panel again. The clone outlives every profile switch; the panel
 * itself is only opened (and grabbed) while a profile asks for a touch
 * offset, so an idle daemon holds no panel fd whose event queue could
 * overflow. Returns false (and logs) when there is no panel at all: the
 * daemon then serves keys only. */
static bool serve_create_touch_device(const config_t *cfg) {
    memset(g_touch_path, 0, sizeof(g_touch_path));
    int fd = find_touch(cfg, g_touch_path, sizeof(g_touch_path));
    if (fd < 0) {
        fprintf(stderr, "dpadkeys: serve: no touch panel found%s%s; serving without touch support\n",
                cfg->touch_device[0] ? " at " : "", cfg->touch_device);
        fflush(stderr);
        g_touch_path[0] = '\0';
        return false;
    }
    struct input_id touch_id;
    memset(&touch_id, 0, sizeof(touch_id));
    ioctl(fd, EVIOCGID, &touch_id);
    if (ioctl(fd, EVIOCGNAME(sizeof(g_touch_name)), g_touch_name) < 0)
        snprintf(g_touch_name, sizeof(g_touch_name), "?");
    query_touch_axes(fd);
    g_touch_uinput_fd = open_touch_uinput(fd, &touch_id, g_touch_device_name, cfg);
    close(fd);
    if (g_touch_uinput_fd < 0) {
        fprintf(stderr, "dpadkeys: serve: could not create the virtual touchscreen\n");
        fflush(stderr);
        return false;
    }
    fprintf(stderr, "dpadkeys: serve: created virtual touchscreen \"%s\" (cloned from %s \"%s\")\n",
            g_touch_device_name, g_touch_path, g_touch_name);
    fflush(stderr);
    return true;
}

/* ---- event loop ---- */

/* Turns one pad event into source updates. Shared verbatim by both modes.
 * Returns false if the pad was let go while handling the event (the panic
 * chord firing in serve mode), so the caller stops using g_pad_fd. */
static bool dispatch_pad_event(config_t *cfg, const struct input_event *ev) {
    /* The panic chord is evaluated from the RAW event, ahead of and entirely
     * independent of what this profile maps its sources to; mapping still
     * happens below for the same event. */
    panic_track_event(cfg, ev);
    if (check_panic_chord(cfg)) return false;

    if (ev->type == EV_KEY) {
        if (ev->value == 2)
            return true; /* ignore autorepeat */
        handle_raw_key(cfg, ev->code, ev->value != 0);
        for (int i = 0; i < BTN_MAP_LEN; i++) {
            if (BTN_MAP[i].code == ev->code) {
                update_source(cfg, BTN_MAP[i].src, ev->value != 0);
                break;
            }
        }
        return true;
    }
    if (ev->type != EV_ABS) return true;

    handle_raw_abs(cfg, ev->code, ev->value);
    if (ev->code == ABS_HAT0X) {
        update_source(cfg, SRC_HAT_LEFT, ev->value < 0);
        update_source(cfg, SRC_HAT_RIGHT, ev->value > 0);
    } else if (ev->code == ABS_HAT0Y) {
        update_source(cfg, SRC_HAT_UP, ev->value < 0);
        update_source(cfg, SRC_HAT_DOWN, ev->value > 0);
    } else if (g_ax.ls_x.present && g_ax.ls_y.present &&
               (ev->code == g_ax.ls_x.code || ev->code == g_ax.ls_y.code)) {
        if (ev->code == g_ax.ls_x.code) g_ax.ls_rx = ev->value; else g_ax.ls_ry = ev->value;
        handle_stick_2d(cfg, &g_ax.ls_x, &g_ax.ls_y, g_ax.ls_rx, g_ax.ls_ry, &g_ax.ls_active, &g_ax.ls_dirs,
                        cfg->deadzone, cfg->ls_invert_x, cfg->ls_invert_y,
                        SRC_LS_UP, SRC_LS_DOWN, SRC_LS_LEFT, SRC_LS_RIGHT);
    } else if (g_ax.rs_x.present && g_ax.rs_y.present &&
               (ev->code == g_ax.rs_x.code || ev->code == g_ax.rs_y.code)) {
        if (ev->code == g_ax.rs_x.code) g_ax.rs_rx = ev->value; else g_ax.rs_ry = ev->value;
        handle_stick_2d(cfg, &g_ax.rs_x, &g_ax.rs_y, g_ax.rs_rx, g_ax.rs_ry, &g_ax.rs_active, &g_ax.rs_dirs,
                        cfg->deadzone, cfg->rs_invert_x, cfg->rs_invert_y,
                        SRC_RS_UP, SRC_RS_DOWN, SRC_RS_LEFT, SRC_RS_RIGHT);
    } else if (g_ax.lt.present && ev->code == g_ax.lt.code) {
        handle_trigger_axis(cfg, &g_ax.lt, ev->value, SRC_LT, cfg->deadzone);
    } else if (g_ax.rt.present && ev->code == g_ax.rt.code) {
        handle_trigger_axis(cfg, &g_ax.rt, ev->value, SRC_RT, cfg->deadzone);
    }
    return true;
}

/* The daemon's event loop, shared by one-shot and serve modes.
 *
 * The only structural difference is that in serve mode every fd here may
 * legitimately be -1 (an idle profile holds nothing), and a device going
 * away is a transient to retry rather than something to block on or die
 * from. The self-pipe is always in the poll set, so an idle daemon with no
 * device fds at all still wakes promptly on SIGUSR1/SIGTERM. */
static void event_loop(config_t *cfg, const char *config_path, const char *device_override, bool grab) {
    while (g_running) {
        if (g_reload_req) {
            g_reload_req = 0;
            if (g_serve) serve_reload(cfg, config_path, device_override);
            else reload_touch_config(config_path, cfg);
        }
        if (g_serve && g_touch_uinput_dead) {
            /* The clone stopped accepting writes (something destroyed it out
             * from under us). One-shot mode exits 5 here; a serve daemon has
             * a keyboard to keep serving, so it drops touch support, tells
             * the supervisor via the status file, and carries on. */
            g_touch_uinput_dead = false;
            fprintf(stderr, "dpadkeys: serve: virtual touchscreen is gone; touch support disabled\n");
            fflush(stderr);
            /* Nothing can be written to it any more, so drop the tracked
             * contacts rather than try to release them. */
            touch_forget_contacts();
            touch_release_panel();
            destroy_uinput(g_touch_uinput_fd);
            g_touch_uinput_fd = -1;
            g_want_touch = false;
            write_status(cfg);
        }
        if (g_serve && g_retry_due_ms >= 0 && now_ms() >= g_retry_due_ms) {
            g_retry_due_ms = -1;
            serve_retry_acquire(cfg, device_override);
        }
        /* Parked after a panic: the pad is open but ungrabbed purely so the
         * escalation can be seen. Let it go as soon as the chord breaks or the
         * window closes. */
        if (g_panic_watch_until_ms >= 0) {
            /* Ask about the escalation BEFORE deciding the window is over:
             * both come due at the same instant, and closing first would drop
             * exactly the hold the user is asking us to act on. Exits 6 if the
             * chord has now been held for PANIC_RESTART_MS. */
            check_panic_chord(cfg);
            if (g_chord_start_ms < 0 || now_ms() >= g_panic_watch_until_ms) {
                g_panic_watch_until_ms = -1;
                if (g_pad_fd >= 0) { close(g_pad_fd); g_pad_fd = -1; }
                g_grabbed = false;
                g_chord_start_ms = -1;
                g_panic_idle_fired = false;
                memset(g_panic_held, 0, sizeof(g_panic_held));
            }
        }
        /* `contacts` drifting from what the status file says (a finger went
         * down or came up with no transition around it) is refreshed here so
         * the app can actually see a stuck pointer. */
        if (g_serve && g_status_path && touch_contacts_live() != g_status_contacts &&
            (g_status_written_ms < 0 || now_ms() - g_status_written_ms >= STATUS_CONTACTS_MS))
            write_status(cfg);
        /* Safety net. A contact live on the clone while the panel is not
         * grabbed means a release path was missed; one live with the panel
         * grabbed but no panel traffic at all for TOUCH_STUCK_MS cannot be a
         * real finger. Either way, let it go rather than leave Android holding
         * a phantom pointer. */
        if (touch_contacts_live() > 0 || g_clone_btn_touch) {
            bool stale = !g_touch_grabbed ||
                         (g_touch_last_event_ms >= 0 &&
                          now_ms() - g_touch_last_event_ms >= TOUCH_STUCK_MS);
            if (stale) {
                int freed = touch_release_stuck_contacts(
                        g_touch_grabbed ? "no panel events" : "panel not grabbed");
                if (freed > 0 && g_serve) write_status(cfg);
            }
        }

        /* Wait-for-neutral caps. A device whose key or slot state is latched
         * (a panel that never clears BTN_TOUCH, a pad reporting a button that
         * is not physically down) would otherwise keep the profile inactive
         * forever, so past GRAB_WAIT_MAX_MS we grab regardless and fall back
         * to the old behaviour: the clone carries the contacts over, and the
         * pad's phantom button is the lesser evil against never activating. */
        if (g_pad_wait && g_pad_fd >= 0 && g_pad_wait_since_ms >= 0 &&
            now_ms() - g_pad_wait_since_ms >= GRAB_WAIT_MAX_MS) {
            char why[80];
            bool ok_now = pad_neutral(g_pad_fd, cfg, why, sizeof(why));
            if (!pad_finish_grab(cfg, ok_now ? NULL : (why[0] ? why : "unknown"))) {
                fprintf(stderr, "dpadkeys: serve: could not grab the pad; will keep trying\n");
                fflush(stderr);
                serve_schedule_retry();
            }
            if (g_serve) write_status(cfg);
        }
        if (g_touch_wait && g_touch_fd >= 0 && g_touch_wait_since_ms >= 0 &&
            now_ms() - g_touch_wait_since_ms >= GRAB_WAIT_MAX_MS) {
            const char *why = NULL;
            bool ok_now = touch_panel_neutral(g_touch_fd, &why);
            if (!touch_finish_grab(cfg, ok_now ? NULL : (why ? why : "unknown")) && g_serve)
                serve_schedule_retry();
            if (g_serve) write_status(cfg);
        }

        long long deadline = -1;
        for (int i = 0; i < WHEEL_COUNT; i++) {
            if (g_wheel_count[i] > 0 && (deadline < 0 || g_wheel_next_due_ms[i] < deadline))
                deadline = g_wheel_next_due_ms[i];
        }
        if (g_chord_start_ms >= 0) {
            /* The chord is already fully held: wake in time to fire it (and
             * then to escalate) even if no further pad events arrive. */
            long long chord_deadline = g_chord_start_ms +
                    (g_panic_idle_fired ? PANIC_RESTART_MS : PANIC_CHORD_MS);
            if (deadline < 0 || chord_deadline < deadline)
                deadline = chord_deadline;
        }
        if (g_panic_watch_until_ms >= 0 && (deadline < 0 || g_panic_watch_until_ms < deadline))
            deadline = g_panic_watch_until_ms;
        if (g_serve && g_status_path && touch_contacts_live() != g_status_contacts) {
            long long st_deadline = (g_status_written_ms < 0 ? 0 : g_status_written_ms) + STATUS_CONTACTS_MS;
            if (deadline < 0 || st_deadline < deadline) deadline = st_deadline;
        }
        if ((touch_contacts_live() > 0 || g_clone_btn_touch) && g_touch_last_event_ms >= 0) {
            long long stuck_deadline = g_touch_last_event_ms + TOUCH_STUCK_MS;
            if (deadline < 0 || stuck_deadline < deadline)
                deadline = stuck_deadline;
        }
        if (g_retry_due_ms >= 0 && (deadline < 0 || g_retry_due_ms < deadline))
            deadline = g_retry_due_ms;
        /* A device held non-neutral may emit nothing at all while it is held,
         * so the cap needs its own wakeup rather than riding on an event. */
        if (g_pad_wait && g_pad_wait_since_ms >= 0) {
            long long d = g_pad_wait_since_ms + GRAB_WAIT_MAX_MS;
            if (deadline < 0 || d < deadline) deadline = d;
        }
        if (g_touch_wait && g_touch_wait_since_ms >= 0) {
            long long d = g_touch_wait_since_ms + GRAB_WAIT_MAX_MS;
            if (deadline < 0 || d < deadline) deadline = d;
        }
        int timeout_ms = -1;
        if (deadline >= 0) {
            long long now = now_ms();
            timeout_ms = (int)(deadline > now ? deadline - now : 0);
        }

        struct pollfd pfds[3];
        int nfds = 0, sig_slot = -1, pad_slot = -1, touch_slot = -1;
        if (g_sigpipe[0] >= 0) {
            pfds[nfds].fd = g_sigpipe[0]; pfds[nfds].events = POLLIN; pfds[nfds].revents = 0;
            sig_slot = nfds++;
        }
        if (g_pad_fd >= 0) {
            pfds[nfds].fd = g_pad_fd; pfds[nfds].events = POLLIN; pfds[nfds].revents = 0;
            pad_slot = nfds++;
        }
        if (g_touch_fd >= 0 && (g_touch_grabbed || g_touch_wait)) {
            pfds[nfds].fd = g_touch_fd; pfds[nfds].events = POLLIN; pfds[nfds].revents = 0;
            touch_slot = nfds++;
        }

        int pr = poll(nfds ? pfds : NULL, (nfds_t)nfds, timeout_ms);
        if (pr < 0) {
            if (errno == EINTR)
                continue;
            perror("poll");
            break;
        }
        if (pr == 0) {
            /* Nothing readable: fire any wheel repeats that came due, and
             * check whether the panic chord's hold time has elapsed. */
            if (g_chord_start_ms >= 0)
                check_panic_chord(cfg); /* re-check elapsed time only */
            long long now = now_ms();
            for (int i = 0; i < WHEEL_COUNT; i++) {
                if (g_wheel_count[i] > 0 && now >= g_wheel_next_due_ms[i]) {
                    emit_wheel_notch(g_uinput_fd, i);
                    g_wheel_next_due_ms[i] = now + g_wheel_repeat_ms;
                }
            }
            continue;
        }

        if (sig_slot >= 0 && (pfds[sig_slot].revents & POLLIN)) {
            char drain[64];
            while (read(g_sigpipe[0], drain, sizeof(drain)) > 0) { }
            continue; /* re-evaluate g_running / g_reload_req at the top */
        }

        if (touch_slot >= 0 && (pfds[touch_slot].revents & POLLIN) && g_touch_wait) {
            /* Not grabbed yet: consume the panel's events (Android still gets
             * its own copy, including the release we are waiting for) and take
             * the grab on the frame that leaves it neutral. */
            int wr = touch_wait_drain(cfg);
            if (wr < 0) {
                touch_release_panel();
                fprintf(stderr, "dpadkeys: touch: lost the panel while waiting for it "
                                "to be released\n");
                fflush(stderr);
                if (g_serve) serve_schedule_retry();
            }
            if (wr != 0 && g_serve) write_status(cfg);
            continue;
        }

        if (touch_slot >= 0 && (pfds[touch_slot].revents & POLLIN)) {
            if (forward_touch_batch(cfg) < 0) {
                if (errno == ENODEV) {
                    if (g_serve) {
                        /* Never block and never exit: drop the panel and let
                         * the retry timer pick it back up. */
                        fprintf(stderr, "dpadkeys: serve: touch panel disconnected, will re-detect\n");
                        fflush(stderr);
                        touch_release_panel();
                        serve_schedule_retry();
                        continue;
                    }
                    fprintf(stderr, "dpadkeys: touch: panel disconnected, re-detecting...\n");
                    /* Releases whatever the clone still has down first: the
                     * panel is gone, so nothing will ever lift those fingers. */
                    touch_release_panel();
                    while (g_running && (g_touch_fd = find_touch(cfg, g_touch_path, sizeof(g_touch_path))) < 0) {
                        struct timespec ts = { 0, 500 * 1000 * 1000 };
                        nanosleep(&ts, NULL);
                    }
                    if (g_running && g_touch_fd >= 0) {
                        query_touch_axes(g_touch_fd);
                        /* Freshly recreated node: retry briefly before giving up. */
                        bool got = false;
                        for (int tries = 0; g_running && tries < 20 && !got; tries++) {
                            if (ioctl(g_touch_fd, EVIOCGRAB, 1) == 0) got = true;
                            else usleep(100000);
                        }
                        if (!got) {
                            if (!g_running) break;
                            fprintf(stderr, "dpadkeys: touch: could not re-grab the panel. Exiting.\n");
                            touch_fatal_exit(4);
                        }
                        g_touch_grabbed = true;
                        touch_sync_initial_contacts(cfg);
                        fprintf(stderr, "dpadkeys: touch: reacquired %s\n", g_touch_path);
                        fflush(stderr);
                    }
                } else if (errno != EAGAIN) {
                    perror("read touch");
                }
            }
        }

        if (!g_running)
            break;
        if (pad_slot < 0 || !(pfds[pad_slot].revents & POLLIN))
            continue;

        struct input_event ev;
        ssize_t n = read(g_pad_fd, &ev, sizeof(ev));
        if (n < 0) {
            if (errno == EINTR)
                continue;
            if (errno == ENODEV) {
                if (g_serve) {
                    fprintf(stderr, "dpadkeys: serve: pad disconnected (style switch?), will re-detect\n");
                    fflush(stderr);
                    serve_release_pad(cfg);
                    serve_schedule_retry();
                    continue;
                }
                fprintf(stderr, "dpadkeys: pad disconnected (style switch?), re-detecting...\n");
                release_all_sources(cfg);
                if (g_grabbed) { ioctl(g_pad_fd, EVIOCGRAB, 0); g_grabbed = false; }
                close(g_pad_fd);
                g_pad_fd = -1;
                if (device_override) {
                    while (g_running && (g_pad_fd = open(device_override, O_RDWR)) < 0) {
                        struct timespec ts = { 0, 500 * 1000 * 1000 };
                        nanosleep(&ts, NULL);
                    }
                } else {
                    while (g_running &&
                           (g_pad_fd = find_pad(cfg->device_match, O_RDWR, g_pad_path, sizeof(g_pad_path),
                                                 g_pad_name, sizeof(g_pad_name), &g_pad_vendor, &g_pad_product)) < 0) {
                        struct timespec ts = { 0, 500 * 1000 * 1000 };
                        nanosleep(&ts, NULL);
                    }
                }
                if (!g_running)
                    break;
                if (grab) {
                    /* Freshly recreated device: retry briefly before giving up. */
                    int tries = 20;
                    while (g_running && tries-- > 0 && ioctl(g_pad_fd, EVIOCGRAB, 1) < 0)
                        usleep(100000);
                    if (tries < 0) {
                        perror("EVIOCGRAB");
                        fprintf(stderr, "dpadkeys: could not re-grab the pad. Exiting.\n");
                        g_running = 0;
                        break;
                    }
                    g_grabbed = true;
                }
                detect_axes(g_pad_fd, &g_ax);
                raw_query_axes(g_pad_fd, cfg);
                propagate_trigger_unipolar(cfg, &g_ax);
                fprintf(stderr, "dpadkeys: reacquired pad=%s name=\"%s\"\n", g_pad_path, g_pad_name);
                print_banner(g_pad_path, g_pad_name, g_pad_vendor, g_pad_product, &g_ax, cfg);
                continue;
            }
            if (errno == EAGAIN)
                continue;
            perror("read pad");
            break;
        }
        if (n != (ssize_t)sizeof(ev))
            continue;

        if (g_panic_watch_until_ms >= 0) {
            /* Parked: the pad is ungrabbed and belongs to the system again.
             * We only watch for the chord escalating to a restart. */
            panic_track_event(cfg, &ev);
            check_panic_chord(cfg);
            continue;
        }

        if (g_pad_wait) {
            /* Open but not grabbed because something was held when we got
             * here. Nothing is mapped or swallowed meanwhile -- the pad is
             * still the system's. Re-read the kernel's state at each frame
             * boundary and grab the moment it reads neutral. */
            if (ev.type == EV_SYN && ev.code == SYN_REPORT &&
                pad_neutral(g_pad_fd, cfg, NULL, 0)) {
                if (!pad_finish_grab(cfg, NULL)) {
                    fprintf(stderr, "dpadkeys: serve: could not grab the pad; will keep trying\n");
                    fflush(stderr);
                    serve_schedule_retry();
                }
                if (g_serve) write_status(cfg);
            }
            continue;
        }

        if (!dispatch_pad_event(cfg, &ev))
            continue; /* pad was released mid-event (panic chord) */
    }
}

/* ---- startup ---- */

/* Refuses to start if `pidfile` names a live process, then claims it.
 * Returns false if another instance owns it. */
static bool claim_pidfile(const char *pidfile) {
    FILE *pf = fopen(pidfile, "r");
    if (pf) {
        int oldpid = 0;
        if (fscanf(pf, "%d", &oldpid) == 1 && oldpid > 0 && kill(oldpid, 0) == 0) {
            fprintf(stderr, "dpadkeys: already running as pid %d (per %s). Exiting.\n", oldpid, pidfile);
            fclose(pf);
            return false;
        }
        fclose(pf);
    }
    FILE *f = fopen(pidfile, "w");
    if (f) { fprintf(f, "%d\n", getpid()); fclose(f); }
    return true;
}

/* --serve: create both virtual devices once, then spend the whole process
 * lifetime switching profiles underneath them. */
static int run_serve(config_t *cfg, const char *config_path, const char *device_override,
                     const char *pidfile) {
    g_serve = true;
    g_pidfile = pidfile; /* so panic_chord_restart() can clean up on its way out */

    /* Claimed before any device is created: two serve daemons would each
     * create their own pair, which is exactly the churn this mode exists to
     * avoid. */
    if (pidfile && !claim_pidfile(pidfile)) return 1;

    g_uinput_fd = open_uinput_superset();
    if (g_uinput_fd < 0) {
        fprintf(stderr, "dpadkeys: serve: cannot create the virtual keyboard. Exiting.\n");
        if (pidfile) unlink(pidfile);
        return 1;
    }
    /* The clone about to be created belongs to the startup config's
     * generation and touch.display, so the startup serve_apply() below must
     * not immediately think either changed and rebuild it again. */
    g_touch_gen_applied = cfg->touch_generation;
    g_touch_display_applied_set = cfg->touch_display_set;
    g_touch_display_applied_w = cfg->touch_display_w;
    g_touch_display_applied_h = cfg->touch_display_h;
    g_touch_display_applied_r = cfg->touch_display_r;
    bool touch_ok = serve_create_touch_device(cfg);

    {
        char pb[160];
        panic_spec_str(cfg, pb, sizeof(pb));
        printf("dpadkeys: serve: keyboard+mouse ok, touch=%s, state=idle panic=%s\n",
               touch_ok ? g_touch_path : "none",
               cfg->n_panic > 0 ? pb : "none");
    }
    fflush(stdout);

    /* Apply the startup profile through the same path SIGUSR1 takes, from a
     * known-idle base, so there is exactly one transition implementation. */
    {
        static config_t startup;
        startup = *cfg;
        init_config(cfg);
        serve_apply(cfg, &startup, device_override);
    }

    event_loop(cfg, config_path, device_override, true);

    serve_go_idle(cfg);
    /* Last word to the supervisor: state=idle, contacts=0. Without it the file
     * keeps whatever was true at the last transition, and an app polling it
     * would read a long-dead daemon's contact count as a stuck pointer. */
    write_status(cfg);
    touch_disable();
    destroy_uinput(g_uinput_fd);
    g_uinput_fd = -1;
    if (pidfile) unlink(pidfile);
    fprintf(stderr, "dpadkeys: serve: stopped\n");
    fflush(stderr);
    return 0;
}

/* --selftest-transform: exercises touch_display_transform() against an
 * independent floating-point reference of the same four formulas (see the
 * comment above touch_axis_to_display()), for the Odin's own panel/display
 * geometry (Xmax=1080, Ymax=1920, W=1920, H=1080) plus the four panel
 * corners, at every rotation. No device needed. Prints one PASS/FAIL line
 * per case plus a summary, and returns 0 iff every case passed. */
static int selftest_transform(void) {
    static const struct { int px, py; const char *label; } cases[] = {
        { 100, 200, "example" },
        { 0, 0, "corner top-left" },
        { 1080, 0, "corner top-right" },
        { 0, 1920, "corner bottom-left" },
        { 1080, 1920, "corner bottom-right" },
    };
    const int xmax = 1080, ymax = 1920, w = 1920, h = 1080;
    bool all_ok = true;

    for (int r = 0; r < 4; r++) {
        for (size_t i = 0; i < sizeof(cases) / sizeof(cases[0]); i++) {
            int px = cases[i].px, py = cases[i].py;
            int dx, dy;
            touch_display_transform(px, py, xmax, ymax, w, h, r, &dx, &dy);

            double edx, edy;
            switch (r) {
                case 0: edx = (double)px * w / xmax; edy = (double)py * h / ymax; break;
                case 1: edx = (double)py * w / ymax; edy = (double)h - (double)px * h / xmax; break;
                case 2: edx = (double)(xmax - px) * w / xmax; edy = (double)(ymax - py) * h / ymax; break;
                default: edx = (double)w - (double)py * w / ymax; edy = (double)px * h / xmax; break;
            }
            long erx = lround(edx), ery = lround(edy);
            if (erx < 0) erx = 0; else if (erx > w - 1) erx = w - 1;
            if (ery < 0) ery = 0; else if (ery > h - 1) ery = h - 1;

            bool ok = (dx == (int)erx && dy == (int)ery);
            printf("%s R=%d %-19s panel=(%d,%d) -> display=(%d,%d) expected=(%ld,%ld)\n",
                   ok ? "PASS" : "FAIL", r, cases[i].label, px, py, dx, dy, erx, ery);
            if (!ok) all_ok = false;
        }
    }
    printf(all_ok ? "PASS: touch.display transform selftest\n"
                  : "FAIL: touch.display transform selftest\n");
    fflush(stdout);
    return all_ok ? 0 : 1;
}

int main(int argc, char **argv) {
    /* We may be spawned from a Java process (Shizuku user service) whose threads
     * block SIGUSR1/SIGTERM; the mask is inherited across exec, so clear it or
     * our reload/exit signals stay pending forever. */
    { sigset_t none; sigemptyset(&none); sigprocmask(SIG_SETMASK, &none, NULL); }
    /* Every slot starts empty; static zero-init would read as "tracking id 0
     * is live in every slot". */
    touch_forget_contacts();
    const char *config_path = NULL;
    const char *profile_name = NULL;
    bool grab = false;
    bool do_list = false;
    bool do_dump = false;
    bool print_config_flag = false;
    bool do_learn = false;
    bool do_learn_chord = false;
    bool do_serve = false;
    bool do_selftest_transform = false;
    long long learn_timeout_ms = 15000;
    long long learn_hold_ms = 150;
    const char *device_override = NULL;
    const char *device_name_arg = NULL;
    const char *pidfile = NULL;

    for (int i = 1; i < argc; i++) {
        if (strcmp(argv[i], "--config") == 0 && i + 1 < argc) config_path = argv[++i];
        else if (strcmp(argv[i], "--profile") == 0 && i + 1 < argc) profile_name = argv[++i];
        else if (strcmp(argv[i], "--grab") == 0) grab = true;
        else if (strcmp(argv[i], "--list") == 0) do_list = true;
        else if (strcmp(argv[i], "--dump") == 0) do_dump = true;
        else if (strcmp(argv[i], "--device") == 0 && i + 1 < argc) device_override = argv[++i];
        else if (strcmp(argv[i], "--device-name") == 0 && i + 1 < argc) device_name_arg = argv[++i];
        else if (strcmp(argv[i], "--touch-name") == 0 && i + 1 < argc) { snprintf(g_touch_device_name, sizeof g_touch_device_name, "%s", argv[++i]); g_touch_name_overridden = true; }
        else if (strcmp(argv[i], "--verbose") == 0) g_verbose = true;
        else if (strcmp(argv[i], "--allow-q") == 0) g_allow_q = true;
        else if (strcmp(argv[i], "--pidfile") == 0 && i + 1 < argc) pidfile = argv[++i];
        else if (strcmp(argv[i], "--print-config") == 0) print_config_flag = true;
        else if (strcmp(argv[i], "--learn") == 0) do_learn = true;
        else if (strcmp(argv[i], "--learn-chord") == 0) do_learn_chord = true;
        else if (strcmp(argv[i], "--serve") == 0) do_serve = true;
        else if (strcmp(argv[i], "--selftest-transform") == 0) do_selftest_transform = true;
        else if (strcmp(argv[i], "--status-file") == 0 && i + 1 < argc) g_status_path = argv[++i];
        else if (strcmp(argv[i], "--learn-timeout-ms") == 0 && i + 1 < argc) learn_timeout_ms = atoll(argv[++i]);
        else if (strcmp(argv[i], "--learn-hold-ms") == 0 && i + 1 < argc) learn_hold_ms = atoll(argv[++i]);
        else if (strcmp(argv[i], "--panic-chord") == 0 && i + 1 < argc) {
            const char *v = argv[++i];
            /* Legacy spelling of what is now an ordinary source list. */
            if (strcmp(v, "m1+m2") == 0) v = "btn.m1+btn.m2";
            static config_t probe;
            init_config(&probe);
            if (!parse_panic_spec(&probe, v, "--panic-chord", 0)) return 2;
            g_panic_cli_spec = v;
        }
        else { print_usage(argv[0]); return 1; }
    }
    if (device_override && strcmp(device_override, "auto") == 0) device_override = NULL;

    if (do_selftest_transform) return selftest_transform();

    if (device_name_arg) sanitize_device_name(g_device_name, sizeof(g_device_name), device_name_arg);
    if (g_device_name[0] == '\0') sanitize_device_name(g_device_name, sizeof(g_device_name), DEFAULT_DEVICE_NAME);
    if (!g_touch_name_overridden)
        snprintf(g_touch_device_name, sizeof(g_touch_device_name), "%s Touch", g_device_name);

    if (do_dump) {
        struct sigaction sa; memset(&sa, 0, sizeof sa); sa.sa_handler = on_signal;
        sigaction(SIGINT, &sa, NULL); sigaction(SIGTERM, &sa, NULL);
        return dump_all();
    }
    if (do_list) {
        list_devices();
        return 0;
    }

    if (config_path && profile_name) {
        fprintf(stderr, "dpadkeys: --config and --profile are mutually exclusive\n");
        return 2;
    }
    if (do_serve) {
        if (!config_path) {
            fprintf(stderr, "dpadkeys: --serve requires --config FILE (the active profile)\n");
            return 2;
        }
        if (do_learn || do_learn_chord) {
            fprintf(stderr, "dpadkeys: --serve and --learn/--learn-chord are separate processes; run them on their own\n");
            return 2;
        }
    }
    if (do_learn && do_learn_chord) {
        fprintf(stderr, "dpadkeys: --learn and --learn-chord are separate processes; run one at a time\n");
        return 2;
    }

    static config_t cfg;
    if (do_serve) {
        /* Lenient from the very first read: a broken startup profile must
         * still leave a daemon running with both devices up, just idle. */
        if (!load_config_retry(config_path, &cfg)) {
            fprintf(stderr, "dpadkeys: serve: cannot read config '%s': %s; starting idle\n",
                    config_path, strerror(errno));
            init_config(&cfg);
        }
        filter_targets_to_superset(&cfg, config_path);
    }
    else if (config_path) load_config_file(config_path, &cfg, false);
    else if ((do_learn || do_learn_chord) && !profile_name) init_config(&cfg); /* learn needs no mapping, only device.match */
    else load_profile(&cfg, profile_name ? profile_name : "fkeys");

    apply_panic_cli_override(&cfg);

    if (print_config_flag) {
        print_config(&cfg, stdout);
        return 0;
    }

    {
        /* No SA_RESTART: a blocking read() on the pad must return EINTR so the
         * main loop notices g_running == 0 and cleans up, and SIGUSR1 must
         * break poll() so g_reload_req is seen on the next iteration. The
         * self-pipe closes the remaining gap (a signal landing between the
         * g_reload_req test and poll()), which an idle serve daemon -- with
         * no device fds in the poll set at all -- would otherwise sleep
         * through indefinitely. */
        if (pipe(g_sigpipe) == 0) {
            for (int i = 0; i < 2; i++) {
                int fl = fcntl(g_sigpipe[i], F_GETFL, 0);
                if (fl >= 0) fcntl(g_sigpipe[i], F_SETFL, fl | O_NONBLOCK);
                fcntl(g_sigpipe[i], F_SETFD, FD_CLOEXEC);
            }
        } else {
            g_sigpipe[0] = g_sigpipe[1] = -1;
            perror("pipe");
        }
        struct sigaction sa;
        memset(&sa, 0, sizeof(sa));
        sa.sa_handler = on_signal;
        sigemptyset(&sa.sa_mask);
        sa.sa_flags = 0;
        sigaction(SIGINT, &sa, NULL);
        sigaction(SIGTERM, &sa, NULL);
        sa.sa_handler = on_usr1;
        sigaction(SIGUSR1, &sa, NULL);
        signal(SIGPIPE, SIG_IGN);
    }

    if (do_serve)
        return run_serve(&cfg, config_path, device_override, pidfile);

    /* learn never grabs and never writes, so a read-only open suffices there */
    bool learning = do_learn || do_learn_chord;
    int open_flags = learning ? O_RDONLY : O_RDWR;
    long long find_deadline = learning ? now_ms() + learn_timeout_ms : -1;

    if (device_override) {
        g_pad_fd = open(device_override, open_flags);
        if (g_pad_fd < 0) {
            perror("open device");
            if (learning) { printf("learned NONE\n"); return 3; }
            return 1;
        }
        strncpy(g_pad_path, device_override, sizeof(g_pad_path) - 1);
        bool s;
        device_info(g_pad_fd, &g_pad_vendor, &g_pad_product, g_pad_name, sizeof(g_pad_name), &s);
    } else {
        bool warned = false;
        while (g_running) {
            g_pad_fd = find_pad(cfg.device_match, open_flags, g_pad_path, sizeof(g_pad_path),
                                g_pad_name, sizeof(g_pad_name), &g_pad_vendor, &g_pad_product);
            if (g_pad_fd >= 0)
                break;
            if (!warned) {
                fprintf(stderr, "waiting for a gamepad device%s%s...\n",
                        cfg.device_match[0] ? " matching " : "", cfg.device_match);
                warned = true;
            }
            if (find_deadline >= 0 && now_ms() >= find_deadline) {
                printf("learned NONE\n");
                return 3;
            }
            struct timespec ts = { 0, 500 * 1000 * 1000 };
            nanosleep(&ts, NULL);
        }
        if (!g_running)
            return 0;
    }
    detect_axes(g_pad_fd, &g_ax);

    if (do_learn) {
        if (pidfile) {
            FILE *f = fopen(pidfile, "w");
            if (f) { fprintf(f, "%d\n", getpid()); fclose(f); }
        }
        fprintf(stderr, "dpadkeys: learn pad=%s name=\"%s\" vid=0x%04x pid=0x%04x timeout=%lldms\n",
                g_pad_path, g_pad_name, g_pad_vendor, g_pad_product, learn_timeout_ms);
        long long remaining = find_deadline - now_ms();
        int rc = learn_mode(g_pad_fd, &g_ax, remaining > 0 ? remaining : 0, learn_hold_ms);
        close(g_pad_fd);
        if (pidfile) unlink(pidfile);
        return rc;
    }

    if (do_learn_chord) {
        if (pidfile) {
            FILE *f = fopen(pidfile, "w");
            if (f) { fprintf(f, "%d\n", getpid()); fclose(f); }
        }
        fprintf(stderr, "dpadkeys: learn-chord pad=%s name=\"%s\" vid=0x%04x pid=0x%04x timeout=%lldms\n",
                g_pad_path, g_pad_name, g_pad_vendor, g_pad_product, learn_timeout_ms);
        long long remaining = find_deadline - now_ms();
        int rc = learn_chord_mode(g_pad_fd, &g_ax, remaining > 0 ? remaining : 0);
        close(g_pad_fd);
        if (pidfile) unlink(pidfile);
        return rc;
    }

    raw_query_axes(g_pad_fd, &cfg);
    propagate_trigger_unipolar(&cfg, &g_ax);
    {
        const char *coll = raw_axis_collision(&cfg, &g_ax);
        if (coll) {
            fprintf(stderr, "dpadkeys: %s\n", coll);
            close(g_pad_fd);
            return 2;
        }
    }
    {
        const char *bad = raw_unipolar_neg_reject(&cfg, &g_ax);
        if (bad) {
            fprintf(stderr, "dpadkeys: %s\n", bad);
            close(g_pad_fd);
            return 2;
        }
    }

    g_uinput_fd = open_uinput(&cfg);
    if (g_uinput_fd < 0) {
        close(g_pad_fd);
        return 1;
    }
    g_wheel_repeat_ms = cfg.wheel_repeat_ms > 0 ? cfg.wheel_repeat_ms : 120;

    if (grab) {
        /* Grab is all-or-nothing: running ungrabbed would let the pad's own
         * events reach the game alongside our keys (double input). */
        if (ioctl(g_pad_fd, EVIOCGRAB, 1) < 0) {
            perror("EVIOCGRAB");
            fprintf(stderr, "dpadkeys: cannot grab the pad (another instance running?). Exiting.\n");
            destroy_uinput(g_uinput_fd);
            close(g_pad_fd);
            return 1;
        }
        g_grabbed = true;
    }

    /* Touch pass-through: absent touch.offset means we never open or touch
     * the panel at all. Startup and the live SIGUSR1 path share
     * touch_enable(); only the failure handling differs -- startup is fatal
     * (matching the prior per-step exit codes: 4 for a grab failure, 1 for
     * anything else), while the live path just logs and stays off. */
    if (cfg.touch_offset_set) {
        touch_enable_result_t r = touch_enable(&cfg);
        if (r != TOUCH_ENABLE_OK && r != TOUCH_ENABLE_WAITING) {
            fprintf(stderr, "dpadkeys: touch: startup touch init failed. Exiting.\n");
            touch_fatal_exit(r == TOUCH_ENABLE_ERR_GRAB ? 4 : 1);
        }
    }

    if (pidfile && !claim_pidfile(pidfile)) {
        touch_disable();
        if (g_grabbed) ioctl(g_pad_fd, EVIOCGRAB, 0);
        destroy_uinput(g_uinput_fd);
        close(g_pad_fd);
        return 1;
    }

    print_banner(g_pad_path, g_pad_name, g_pad_vendor, g_pad_product, &g_ax, &cfg);
    print_touch_banner(&cfg);

    event_loop(&cfg, config_path, device_override, grab);

    touch_disable();

    if (g_grabbed)
        ioctl(g_pad_fd, EVIOCGRAB, 0);
    if (g_pad_fd >= 0)
        close(g_pad_fd);
    destroy_uinput(g_uinput_fd);
    return 0;
}
