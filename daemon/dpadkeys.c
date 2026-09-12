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
    struct { int min, max; double center, half; int state; } axis;
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

    /* touch pass-through: absent touch.offset means the daemon never opens
     * or touches the panel at all. */
    bool touch_offset_set;
    int touch_dx, touch_dy;
    char touch_device[64]; /* explicit /dev/input/eventN, or empty = auto */
} config_t;

static volatile sig_atomic_t g_running = 1;
static int g_uinput_fd = -1;
static int g_pad_fd = -1;
static bool g_grabbed = false;
static bool g_verbose = false;
static bool g_source_pressed[MAX_SOURCES] = {0};
static int g_key_count[KEY_CNT] = {0};
static long long g_press_seq = 0;
static long long g_source_press_seq[MAX_SOURCES] = {0}; /* bumped on every press, for "most recently pressed hold" */
static int g_resolved_target[MAX_SOURCES] = {0}; /* target used at press, replayed at release */
static int g_wheel_count[WHEEL_COUNT] = {0};
static long long g_wheel_next_due_ms[WHEEL_COUNT] = {0};
static int g_wheel_repeat_ms = 120;

/* touch pass-through state: the virtual touchscreen is created once at
 * startup and kept alive across panel re-detects (like the keyboard). */
static volatile sig_atomic_t g_reload_offset = 0; /* SIGUSR1 */
static int g_touch_fd = -1;
static int g_touch_uinput_fd = -1;
static bool g_touch_grabbed = false;
static char g_touch_path[64] = {0};
static char g_touch_name[128] = {0};
static struct input_absinfo g_touch_mtx_info, g_touch_mty_info, g_touch_x_info, g_touch_y_info;
static bool g_touch_has_mtx = false, g_touch_has_mty = false, g_touch_has_x = false, g_touch_has_y = false;

/* Panic chord: while a touch offset is active and the panel is grabbed,
 * holding both back buttons (raw BTN_C/BTN_Z on the pad) for
 * PANIC_CHORD_MS disables the touch offset by tearing down touch
 * pass-through and exiting(6) so the supervisor can restart without it. */
typedef enum { PANIC_CHORD_NONE, PANIC_CHORD_M1M2 } panic_chord_t;
#define PANIC_CHORD_MS 1000
static panic_chord_t g_panic_chord = PANIC_CHORD_M1M2;
static bool g_chord_m1_held = false, g_chord_m2_held = false;
static long long g_chord_start_ms = -1; /* -1 = not both held */

static void on_signal(int sig) {
    (void)sig;
    g_running = 0;
}

static void on_usr1(int sig) {
    (void)sig;
    g_reload_offset = 1;
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
    cfg->touch_offset_set = false;
    cfg->touch_dx = 0;
    cfg->touch_dy = 0;
    cfg->touch_device[0] = '\0';
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

/* Resolves (adding a raw slot on first use) and registers a source name from
 * a config line, exiting(2) with a message on any error -- unknown name,
 * raw/semantic collision, etc. Shared by plain lines and both sides of a
 * `<hold>+<src>` chord line. */
static int resolve_source(config_t *cfg, const char *name, const char *path, int lineno) {
    const char *err = NULL;
    int src = lookup_or_add_source(cfg, name, &err);
    if (src < 0) {
        if (err) fprintf(stderr, "dpadkeys: %s:%d: %s\n", path, lineno, err);
        else fprintf(stderr, "dpadkeys: %s:%d: unknown source '%s'\n", path, lineno, name);
        exit(2);
    }
    if (src < SRC_COUNT && !cfg->defined[src]) {
        const char *raw = semantic_collides_with_raw(cfg, src);
        if (raw) {
            fprintf(stderr, "dpadkeys: %s:%d: source '%s' collides with raw source '%s'\n",
                    path, lineno, name, raw);
            exit(2);
        }
    }
    cfg->defined[src] = true;
    return src;
}

/* Resolves a target token, exiting(2) with a message if it's not a known
 * target name (KEY_*, WHEEL_* / HWHEEL_*, or NONE). */
static int resolve_target_tok(const char *tok, const char *path, int lineno) {
    int target = lookup_target(tok);
    if (target == TARGET_UNKNOWN) {
        fprintf(stderr, "dpadkeys: %s:%d: unknown target '%s'\n", path, lineno, tok);
        exit(2);
    }
    return target;
}

/* Appends a chord, exiting(2) if the table is full. */
static void add_chord(config_t *cfg, int hold, int src, int target, const char *path, int lineno) {
    if (cfg->n_chords >= MAX_CHORDS) {
        fprintf(stderr, "dpadkeys: %s:%d: too many chords (max %d)\n", path, lineno, MAX_CHORDS);
        exit(2);
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

static void load_config_file(const char *path, config_t *cfg) {
    FILE *f = fopen(path, "r");
    if (!f) {
        fprintf(stderr, "dpadkeys: cannot open config '%s': %s\n", path, strerror(errno));
        exit(2);
    }
    init_config(cfg);
    char line[256];
    int lineno = 0;
    while (fgets(line, sizeof(line), f)) {
        lineno++;
        char *hash = strchr(line, '#');
        if (hash) *hash = '\0';
        char *save = NULL;
        char *tok1 = strtok_r(line, " \t\r\n", &save);
        if (!tok1) continue;
        char *tok2 = strtok_r(NULL, " \t\r\n", &save);
        if (!tok2) {
            fprintf(stderr, "dpadkeys: %s:%d: missing value for '%s'\n", path, lineno, tok1);
            exit(2);
        }
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
                fprintf(stderr, "dpadkeys: %s:%d: touch.offset needs two values (dx dy)\n", path, lineno);
                exit(2);
            }
            cfg->touch_dx = atoi(tok2);
            cfg->touch_dy = atoi(tok3);
            cfg->touch_offset_set = true;
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
                int target = resolve_target_tok(tok2, path, lineno);
                cfg->legacy_mod_set[src] = true;
                cfg->legacy_mod_target[src] = target;
                continue;
            }
            int hold = resolve_source(cfg, hold_name, path, lineno);
            if (!source_can_be_hold(hold)) {
                fprintf(stderr, "dpadkeys: %s:%d: '%s' cannot be used as a hold "
                                "(stick directions can't be held)\n", path, lineno, hold_name);
                exit(2);
            }
            int src = resolve_source(cfg, src_name, path, lineno);
            int target = resolve_target_tok(tok2, path, lineno);
            add_chord(cfg, hold, src, target, path, lineno);
            continue;
        }

        int src = resolve_source(cfg, tok1, path, lineno);

        if (strcmp(tok2, "MOD") == 0) {
            if (cfg->modifier_src != -1) {
                fprintf(stderr, "dpadkeys: %s:%d: modifier already declared as '%s'\n",
                        path, lineno, source_name(cfg, cfg->modifier_src));
                exit(2);
            }
            cfg->modifier_src = src;
            continue;
        }

        cfg->target[src] = resolve_target_tok(tok2, path, lineno);
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
    if (cfg->touch_offset_set)
        fprintf(out, "%-12s %d %d\n", "touch.offset", cfg->touch_dx, cfg->touch_dy);
    else
        fprintf(out, "%-12s %s\n", "touch.offset", "off");
    fprintf(out, "%-12s %s\n", "touch.device", cfg->touch_device[0] ? cfg->touch_device : "auto");
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

static int open_uinput(const config_t *cfg) {
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
    bool used[KEY_CNT] = {0};
    for (int i = 0; i < n_sources(cfg); i++)
        if (cfg->target[i] >= 0 && cfg->target[i] < KEY_CNT) used[cfg->target[i]] = true;
    for (int i = 0; i < cfg->n_chords; i++) {
        int t = cfg->chords[i].target;
        if (t >= 0 && t < KEY_CNT) used[t] = true;
    }
    for (int c = 0; c < KEY_CNT; c++) {
        if (used[c] && ioctl(fd, UI_SET_KEYBIT, c) < 0) {
            perror("UI_SET_KEYBIT");
            close(fd);
            return -1;
        }
    }

    bool uses_wheel = false;
    for (int i = 0; i < n_sources(cfg); i++)
        if (target_is_wheel(cfg->target[i])) uses_wheel = true;
    for (int i = 0; i < cfg->n_chords; i++)
        if (target_is_wheel(cfg->chords[i].target)) uses_wheel = true;
    if (uses_wheel) {
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
    strncpy(usetup.name, "Odin DPad Keys", sizeof(usetup.name) - 1);
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
    strncpy(uud.name, "Odin DPad Keys", sizeof(uud.name) - 1);
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

    /* Triggers: Z/RZ if the right stick didn't already claim them as its
     * Z/RZ fallback, else BRAKE/GAS, else HAT2Y/HAT2X. */
    bool rs_uses_z_rz = !has_rx_ry && has_z_rz;
    if (has_z_rz && !rs_uses_z_rz) {
        query_abs(fd, ABS_Z, &ax->lt);
        query_abs(fd, ABS_RZ, &ax->rt);
    } else if (TEST_BIT(ABS_BRAKE, absbits) && TEST_BIT(ABS_GAS, absbits)) {
        query_abs(fd, ABS_BRAKE, &ax->lt);
        query_abs(fd, ABS_GAS, &ax->rt);
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

static bool classify_trigger(int raw, const axis_t *a, double deadzone) {
    double v = raw, hys = 0.1 * a->half;
    double press = a->center + deadzone * a->half;
    return a->state ? (v >= press - hys) : (v >= press);
}

#define DIR_U 1u
#define DIR_D 2u
#define DIR_L 4u
#define DIR_R 8u

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
        r->axis.state = 0;
    }
}

/* Raw abs threshold: same deadzone + 0.1 hysteresis as triggers, mirrored
 * for `.neg`. A hat-like -1..1 axis therefore fires exactly at -1 / +1. */
static bool raw_abs_pressed(const raw_source_t *r, int value, double deadzone) {
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
}

/* Applies the configured offset to one event in place, clamped to that axis'
 * reported range. Only the four position axes are touched; MAJOR, SLOT,
 * TRACKING_ID, BTN_TOUCH, timestamps and everything else pass through. */
static void offset_touch_event(const config_t *cfg, struct input_event *ev) {
    if (ev->type != EV_ABS) return;
    int delta;
    const struct input_absinfo *info;
    if (ev->code == ABS_MT_POSITION_X && g_touch_has_mtx) { delta = cfg->touch_dx; info = &g_touch_mtx_info; }
    else if (ev->code == ABS_MT_POSITION_Y && g_touch_has_mty) { delta = cfg->touch_dy; info = &g_touch_mty_info; }
    else if (ev->code == ABS_X && g_touch_has_x) { delta = cfg->touch_dx; info = &g_touch_x_info; }
    else if (ev->code == ABS_Y && g_touch_has_y) { delta = cfg->touch_dy; info = &g_touch_y_info; }
    else return;
    long v = (long)ev->value + delta;
    if (v < info->minimum) v = info->minimum;
    if (v > info->maximum) v = info->maximum;
    ev->value = (int)v;
}

/* Creates the virtual touchscreen once at startup, copying the real panel's
 * EV_KEY/EV_ABS capabilities (with identical absinfo via UI_ABS_SETUP) and
 * INPUT_PROP bits, name "fts_ts", and bus/vendor/product/version from the
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
 * nonce until the descriptor is unique, so both devices coexist. */
static int open_touch_uinput(int real_fd, const struct input_id *id, const char *dev_name) {
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

/* Releases both grabs, closes both real fds, destroys both uinput devices,
 * and exits with `code`. Used for the touch-specific fatal-error contract
 * (grab failure -> 4, uinput write failure -> 5). */
static void touch_fatal_exit(int code) {
    if (g_touch_grabbed) { ioctl(g_touch_fd, EVIOCGRAB, 0); g_touch_grabbed = false; }
    if (g_touch_fd >= 0) { close(g_touch_fd); g_touch_fd = -1; }
    if (g_grabbed) { ioctl(g_pad_fd, EVIOCGRAB, 0); g_grabbed = false; }
    if (g_pad_fd >= 0) { close(g_pad_fd); g_pad_fd = -1; }
    destroy_uinput(g_touch_uinput_fd);
    destroy_uinput(g_uinput_fd);
    exit(code);
}

/* Logs the panic trigger and reuses touch_fatal_exit's cleanup (release the
 * panel grab, destroy the virtual touchscreen, ungrab the pad, destroy the
 * keyboard device) to exit(6) so the supervisor can persist "touch offset
 * disabled" and restart without it. */
static void panic_chord_fire(void) {
    fprintf(stderr, "dpadkeys: panic: back-button chord held, disabling touch offset\n");
    fflush(stderr);
    touch_fatal_exit(6);
}

/* Tracks raw BTN_C ("m1")/BTN_Z ("m2") hold state and fires the panic chord
 * once both have been held continuously for PANIC_CHORD_MS while a touch
 * offset is active (configured and the panel currently grabbed). Called both
 * on every raw pad EV_KEY event (code, pressed) and, with code 0, from the
 * poll-timeout path so the chord fires purely from elapsed time even if no
 * further pad events arrive while both buttons are held. */
static void check_panic_chord(const config_t *cfg, int code, bool pressed) {
    if (g_panic_chord != PANIC_CHORD_M1M2) return;
    if (code == BTN_C) g_chord_m1_held = pressed;
    else if (code == BTN_Z) g_chord_m2_held = pressed;

    if (!(cfg->touch_offset_set && g_touch_grabbed) || !g_chord_m1_held || !g_chord_m2_held) {
        g_chord_start_ms = -1;
        return;
    }
    long long now = now_ms();
    if (g_chord_start_ms < 0) {
        g_chord_start_ms = now;
        return;
    }
    if (now - g_chord_start_ms >= PANIC_CHORD_MS)
        panic_chord_fire();
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
        touch_fatal_exit(5);
    }
    fprintf(stderr, "dpadkeys: touch: short/failed uinput write (%zd/%zd)%s%s\n",
            w, want, w < 0 ? ": " : "", w < 0 ? strerror(errno) : "");
    fflush(stderr);
    return false;
}

/* Mirrors the panel's live MT state onto the freshly created clone.
 *
 * At grab time a finger may already be down. The panel will then keep
 * streaming updates for that slot without ever re-sending its
 * ABS_MT_TRACKING_ID, and the clone (which starts with every slot empty)
 * would drop them as belonging to no contact. EVIOCGMTSLOTS reads the
 * panel's per-slot state, and for every slot with tracking id != -1 we emit
 * SLOT / TRACKING_ID / POSITION_X / POSITION_Y (offset) / TOUCH_MAJOR, plus
 * BTN_TOUCH 1, in a single synthetic frame. The current slot is restored
 * last so the clone's implicit slot matches the panel's before the live
 * stream resumes. Skipped (with a log line) when EVIOCGMTSLOTS is missing. */
static void touch_sync_initial_contacts(const config_t *cfg) {
#ifdef EVIOCGMTSLOTS
    struct input_absinfo slotinfo;
    if (ioctl(g_touch_fd, EVIOCGABS(ABS_MT_SLOT), &slotinfo) < 0) return;
    int nslots = slotinfo.maximum - slotinfo.minimum + 1;
    if (nslots <= 0) return;
    if (nslots > 64) nslots = 64;

    /* buf[0] is the requested ABS_MT_* code; buf[1..nslots] the slot values. */
    int32_t tid[65], px[65], py[65], maj[65];
    bool have_maj = true;
    tid[0] = ABS_MT_TRACKING_ID;
    px[0] = ABS_MT_POSITION_X;
    py[0] = ABS_MT_POSITION_Y;
    maj[0] = ABS_MT_TOUCH_MAJOR;
    size_t sz = (size_t)(nslots + 1) * sizeof(int32_t);
    if (ioctl(g_touch_fd, EVIOCGMTSLOTS(sz), tid) < 0) {
        fprintf(stderr, "dpadkeys: touch: EVIOCGMTSLOTS unavailable (%s); "
                        "a finger already down at grab time will be ignored "
                        "until it is lifted\n", strerror(errno));
        return;
    }
    if (ioctl(g_touch_fd, EVIOCGMTSLOTS(sz), px) < 0) return;
    if (ioctl(g_touch_fd, EVIOCGMTSLOTS(sz), py) < 0) return;
    if (ioctl(g_touch_fd, EVIOCGMTSLOTS(sz), maj) < 0) have_maj = false;

    struct input_event out[5 * 64 + 3];
    int n = 0;
    int active = 0;
    for (int s = 0; s < nslots; s++) {
        if (tid[s + 1] == -1) continue;
        active++;
        struct input_event e[5];
        memset(e, 0, sizeof(e));
        int k = 0;
        e[k].type = EV_ABS; e[k].code = ABS_MT_SLOT; e[k].value = slotinfo.minimum + s; k++;
        e[k].type = EV_ABS; e[k].code = ABS_MT_TRACKING_ID; e[k].value = tid[s + 1]; k++;
        e[k].type = EV_ABS; e[k].code = ABS_MT_POSITION_X; e[k].value = px[s + 1]; k++;
        e[k].type = EV_ABS; e[k].code = ABS_MT_POSITION_Y; e[k].value = py[s + 1]; k++;
        if (have_maj) { e[k].type = EV_ABS; e[k].code = ABS_MT_TOUCH_MAJOR; e[k].value = maj[s + 1]; k++; }
        for (int j = 0; j < k; j++) {
            offset_touch_event(cfg, &e[j]);
            out[n++] = e[j];
        }
    }
    if (active == 0) return;

    /* Restore the panel's current slot, then BTN_TOUCH and the frame end. */
    memset(&out[n], 0, sizeof(out[n]));
    out[n].type = EV_ABS; out[n].code = ABS_MT_SLOT; out[n].value = slotinfo.value; n++;
    memset(&out[n], 0, sizeof(out[n]));
    out[n].type = EV_KEY; out[n].code = BTN_TOUCH; out[n].value = 1; n++;
    memset(&out[n], 0, sizeof(out[n]));
    out[n].type = EV_SYN; out[n].code = SYN_REPORT; out[n].value = 0; n++;

    touch_write(out, n);
    fprintf(stderr, "dpadkeys: touch: carried over %d contact(s) live at grab time\n", active);
    fflush(stderr);
#else
    (void)cfg;
    fprintf(stderr, "dpadkeys: touch: EVIOCGMTSLOTS not in headers; a finger "
                    "already down at grab time will be ignored until lifted\n");
#endif
}

/* Reads whatever the panel has queued (whole events only; evdev never returns
 * a partial one), applies the configured X/Y offset in place, and replays the
 * batch to the clone with a single write() so frames stay contiguous and the
 * original timestamps/order are preserved verbatim. Loops until EAGAIN so a
 * burst larger than the buffer is drained in-order. Returns 0 normally, or -1
 * with errno set (notably ENODEV when the panel vanished) so the caller can
 * re-detect. */
static int forward_touch_batch(const config_t *cfg) {
    struct input_event batch[128];
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
        for (int i = 0; i < n_ev; i++)
            offset_touch_event(cfg, &batch[i]);
        touch_write(batch, n_ev);
        /* A full buffer means there may be more queued; anything shorter
         * means the queue is drained. */
        if ((size_t)n < sizeof(batch)) return 0;
    }
}

/* Re-reads `config_path` on SIGUSR1 and applies only a new touch.offset, if
 * present; logs the new offset. No-op if touch pass-through isn't enabled. */
static void reload_touch_offset(const char *config_path, config_t *cfg) {
    if (!cfg->touch_offset_set || !config_path) return;
    FILE *f = fopen(config_path, "r");
    if (!f) {
        fprintf(stderr, "dpadkeys: SIGUSR1: cannot reopen config '%s': %s\n", config_path, strerror(errno));
        fflush(stderr);
        return;
    }
    char line[256];
    int dx = cfg->touch_dx, dy = cfg->touch_dy;
    bool found = false;
    while (fgets(line, sizeof(line), f)) {
        char *hash = strchr(line, '#');
        if (hash) *hash = '\0';
        char *save = NULL;
        char *tok1 = strtok_r(line, " \t\r\n", &save);
        if (!tok1 || strcmp(tok1, "touch.offset") != 0) continue;
        char *tok2 = strtok_r(NULL, " \t\r\n", &save);
        char *tok3 = tok2 ? strtok_r(NULL, " \t\r\n", &save) : NULL;
        if (tok2 && tok3) { dx = atoi(tok2); dy = atoi(tok3); found = true; }
    }
    fclose(f);
    if (!found) {
        fprintf(stderr, "dpadkeys: SIGUSR1: no touch.offset in '%s'; keeping %d %d\n",
                config_path, cfg->touch_dx, cfg->touch_dy);
        fflush(stderr);
        return;
    }
    cfg->touch_dx = dx;
    cfg->touch_dy = dy;
    clamp_touch_offset(cfg, "SIGUSR1");
    fprintf(stderr, "dpadkeys: touch: offset now %d %d\n", cfg->touch_dx, cfg->touch_dy);
    fflush(stderr);
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
    char panic_b[24];
    if (g_panic_chord == PANIC_CHORD_M1M2) snprintf(panic_b, sizeof(panic_b), "m1+m2 %dms", PANIC_CHORD_MS);
    else snprintf(panic_b, sizeof(panic_b), "none");
    printf("dpadkeys: touch=%s \"%s\" off=(%d,%d) x=%s y=%s panic=%s\n",
           g_touch_path, g_touch_name, cfg->touch_dx, cfg->touch_dy, xb, yb, panic_b);
    fflush(stdout);
}

/* ---- banner formatting ---- */

static void fmt_axis(char *buf, size_t n, const axis_t *a) {
    if (a->present) snprintf(buf, n, "[%d,%d]", a->min, a->max);
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

static void print_usage(const char *argv0) {
    fprintf(stderr,
            "usage: %s --config FILE [--grab] [--list] [--device auto|/dev/input/eventN] "
            "[--verbose] [--pidfile PATH] [--print-config] [--panic-chord none|m1+m2]\n"
            "       %s --profile fkeys|wasd [--grab] ...\n"
            "       %s --learn [--learn-timeout-ms N] [--learn-hold-ms N] [--config FILE] [--device ...] [--pidfile PATH]\n"
            "\n"
            "--learn reports a single press as \"learned <source>\", e.g. \"learned hat.up\".\n"
            "Holding one button-like control (btn.*, hat.*, lt, rt, key.0x.., abs.0x..) and\n"
            "then triggering another reports a chord, e.g. hold btn.thumbl and push the left\n"
            "stick up -> \"learned btn.thumbl+ls.up\"; hold btn.tl and press hat up ->\n"
            "\"learned btn.tl+hat.up\". --learn-hold-ms (default 150) is how long a lone\n"
            "control must still be held, with nothing else pressed yet, before it is a\n"
            "candidate hold for a later chord; release it first with nothing else having\n"
            "happened and it is reported as a plain \"learned btn.thumbl\" instead.\n",
            argv0, argv0, argv0);
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
    /* Per-axis tri-state (-1/0/1) seeded from the CURRENT position so a
     * trigger resting at its minimum or an off-centre stick never counts as
     * a press; only a transition into the pressed zone does. */
    struct { bool known; double center, half; int state; } axes[ABS_CNT];
    memset(axes, 0, sizeof(axes));
    unsigned long absbits[NLONGS(ABS_CNT)] = {0};
    ioctl(fd, EVIOCGBIT(EV_ABS, sizeof(absbits)), absbits);
    const double dz = 0.5;
    for (int c = 0; c < ABS_CNT; c++) {
        if (!TEST_BIT(c, absbits)) continue;
        struct input_absinfo info;
        if (ioctl(fd, EVIOCGABS(c), &info) < 0) continue;
        axes[c].known = true;
        axes[c].center = (info.minimum + info.maximum) / 2.0;
        axes[c].half = (info.maximum - info.minimum) / 2.0;
        if (axes[c].half <= 0) axes[c].half = 1.0;
        double v = (info.value - axes[c].center) / axes[c].half;
        axes[c].state = v >= dz ? 1 : (v <= -dz ? -1 : 0);
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
        double v = (ev.value - axes[ev.code].center) / axes[ev.code].half;
        int st = axes[ev.code].state;
        int ns = st == 1 ? (v >= dz - 0.1 ? 1 : 0)
               : st == -1 ? (v <= -(dz - 0.1) ? -1 : 0)
               : (v >= dz ? 1 : (v <= -dz ? -1 : 0));
        if (ns == st) continue;
        axes[ev.code].state = ns;

        if (learn_is_stick_axis(ax, ev.code)) {
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

        /* hat / trigger / raw abs axis: button-like, so track press+release
         * like an EV_KEY control. A direct sign flip (-1 <-> 1, e.g. a hat
         * snapping past centre in one event) is a release of the old
         * direction followed by a press of the new one. */
        if (st != 0) {
            char name[48];
            learn_abs_name(ax, ev.code, st, name, sizeof(name));
            learn_held_t *e = learn_find(held, LEARN_MAX_HELD, name);
            if (e) e->held = false;
            /* A pure release (back to centre) may itself be the plain report;
             * a direct sign flip (st and ns both nonzero) falls through to
             * report the new direction's press instead. */
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
    if (!out[0]) {
        printf("learned NONE\n");
        fflush(stdout);
        return 3;
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

int main(int argc, char **argv) {
    const char *config_path = NULL;
    const char *profile_name = NULL;
    bool grab = false;
    bool do_list = false;
    bool do_dump = false;
    bool print_config_flag = false;
    bool do_learn = false;
    long long learn_timeout_ms = 15000;
    long long learn_hold_ms = 150;
    const char *device_override = NULL;
    const char *pidfile = NULL;

    for (int i = 1; i < argc; i++) {
        if (strcmp(argv[i], "--config") == 0 && i + 1 < argc) config_path = argv[++i];
        else if (strcmp(argv[i], "--profile") == 0 && i + 1 < argc) profile_name = argv[++i];
        else if (strcmp(argv[i], "--grab") == 0) grab = true;
        else if (strcmp(argv[i], "--list") == 0) do_list = true;
        else if (strcmp(argv[i], "--dump") == 0) do_dump = true;
        else if (strcmp(argv[i], "--device") == 0 && i + 1 < argc) device_override = argv[++i];
        else if (strcmp(argv[i], "--verbose") == 0) g_verbose = true;
        else if (strcmp(argv[i], "--pidfile") == 0 && i + 1 < argc) pidfile = argv[++i];
        else if (strcmp(argv[i], "--print-config") == 0) print_config_flag = true;
        else if (strcmp(argv[i], "--learn") == 0) do_learn = true;
        else if (strcmp(argv[i], "--learn-timeout-ms") == 0 && i + 1 < argc) learn_timeout_ms = atoll(argv[++i]);
        else if (strcmp(argv[i], "--learn-hold-ms") == 0 && i + 1 < argc) learn_hold_ms = atoll(argv[++i]);
        else if (strcmp(argv[i], "--panic-chord") == 0 && i + 1 < argc) {
            const char *v = argv[++i];
            if (strcmp(v, "none") == 0) g_panic_chord = PANIC_CHORD_NONE;
            else if (strcmp(v, "m1+m2") == 0) g_panic_chord = PANIC_CHORD_M1M2;
            else {
                fprintf(stderr, "dpadkeys: --panic-chord must be 'none' or 'm1+m2'\n");
                return 2;
            }
        }
        else { print_usage(argv[0]); return 1; }
    }
    if (device_override && strcmp(device_override, "auto") == 0) device_override = NULL;

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

    static config_t cfg;
    if (config_path) load_config_file(config_path, &cfg);
    else if (do_learn && !profile_name) init_config(&cfg); /* learn needs no mapping, only device.match */
    else load_profile(&cfg, profile_name ? profile_name : "fkeys");

    if (print_config_flag) {
        print_config(&cfg, stdout);
        return 0;
    }

    {
        /* No SA_RESTART: a blocking read() on the pad must return EINTR so the
         * main loop notices g_running == 0 and cleans up, and SIGUSR1 must
         * break poll() so g_reload_offset is seen on the next iteration. */
        struct sigaction sa;
        memset(&sa, 0, sizeof(sa));
        sa.sa_handler = on_signal;
        sigemptyset(&sa.sa_mask);
        sa.sa_flags = 0;
        sigaction(SIGINT, &sa, NULL);
        sigaction(SIGTERM, &sa, NULL);
        sa.sa_handler = on_usr1;
        sigaction(SIGUSR1, &sa, NULL);
    }

    char pad_path[64] = {0};
    char pad_name[128] = {0};
    unsigned short vendor = 0, product = 0;
    axes_t ax;

    /* learn never grabs and never writes, so a read-only open suffices there */
    int open_flags = do_learn ? O_RDONLY : O_RDWR;
    long long find_deadline = do_learn ? now_ms() + learn_timeout_ms : -1;

    if (device_override) {
        g_pad_fd = open(device_override, open_flags);
        if (g_pad_fd < 0) {
            perror("open device");
            if (do_learn) { printf("learned NONE\n"); return 3; }
            return 1;
        }
        strncpy(pad_path, device_override, sizeof(pad_path) - 1);
        bool s;
        device_info(g_pad_fd, &vendor, &product, pad_name, sizeof(pad_name), &s);
    } else {
        bool warned = false;
        while (g_running) {
            g_pad_fd = find_pad(cfg.device_match, open_flags, pad_path, sizeof(pad_path),
                                pad_name, sizeof(pad_name), &vendor, &product);
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
    detect_axes(g_pad_fd, &ax);

    if (do_learn) {
        if (pidfile) {
            FILE *f = fopen(pidfile, "w");
            if (f) { fprintf(f, "%d\n", getpid()); fclose(f); }
        }
        fprintf(stderr, "dpadkeys: learn pad=%s name=\"%s\" vid=0x%04x pid=0x%04x timeout=%lldms\n",
                pad_path, pad_name, vendor, product, learn_timeout_ms);
        long long remaining = find_deadline - now_ms();
        int rc = learn_mode(g_pad_fd, &ax, remaining > 0 ? remaining : 0, learn_hold_ms);
        close(g_pad_fd);
        if (pidfile) unlink(pidfile);
        return rc;
    }

    raw_query_axes(g_pad_fd, &cfg);
    {
        const char *coll = raw_axis_collision(&cfg, &ax);
        if (coll) {
            fprintf(stderr, "dpadkeys: %s\n", coll);
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
     * the panel at all. */
    if (cfg.touch_offset_set) {
        g_touch_fd = find_touch(&cfg, g_touch_path, sizeof(g_touch_path));
        if (g_touch_fd < 0) {
            fprintf(stderr, "dpadkeys: touch: no touch panel found%s%s. Exiting.\n",
                    cfg.touch_device[0] ? " at " : "", cfg.touch_device);
            if (g_grabbed) ioctl(g_pad_fd, EVIOCGRAB, 0);
            destroy_uinput(g_uinput_fd);
            close(g_pad_fd);
            return 1;
        }
        struct input_id touch_id;
        memset(&touch_id, 0, sizeof(touch_id));
        ioctl(g_touch_fd, EVIOCGID, &touch_id);
        if (ioctl(g_touch_fd, EVIOCGNAME(sizeof(g_touch_name)), g_touch_name) < 0)
            snprintf(g_touch_name, sizeof(g_touch_name), "?");
        query_touch_axes(g_touch_fd);

        g_touch_uinput_fd = open_touch_uinput(g_touch_fd, &touch_id, "fts_ts");
        if (g_touch_uinput_fd < 0) {
            fprintf(stderr, "dpadkeys: touch: could not create virtual touchscreen. Exiting.\n");
            close(g_touch_fd);
            if (g_grabbed) ioctl(g_pad_fd, EVIOCGRAB, 0);
            destroy_uinput(g_uinput_fd);
            close(g_pad_fd);
            return 1;
        }
        fprintf(stderr, "dpadkeys: touch: created virtual touchscreen \"fts_ts\" (from %s)\n", g_touch_path);

        if (ioctl(g_touch_fd, EVIOCGRAB, 1) < 0) {
            perror("EVIOCGRAB (touch)");
            fprintf(stderr, "dpadkeys: touch: cannot grab the panel. Exiting.\n");
            touch_fatal_exit(4);
        }
        g_touch_grabbed = true;
        touch_sync_initial_contacts(&cfg);
    }

    if (pidfile) {
        FILE *pf = fopen(pidfile, "r");
        if (pf) {
            int oldpid = 0;
            if (fscanf(pf, "%d", &oldpid) == 1 && oldpid > 0 && kill(oldpid, 0) == 0) {
                fprintf(stderr, "dpadkeys: already running as pid %d (per %s). Exiting.\n", oldpid, pidfile);
                fclose(pf);
                if (g_touch_grabbed) ioctl(g_touch_fd, EVIOCGRAB, 0);
                if (g_touch_fd >= 0) close(g_touch_fd);
                destroy_uinput(g_touch_uinput_fd);
                if (g_grabbed) ioctl(g_pad_fd, EVIOCGRAB, 0);
                destroy_uinput(g_uinput_fd);
                close(g_pad_fd);
                return 1;
            }
            fclose(pf);
        }
        FILE *f = fopen(pidfile, "w");
        if (f) { fprintf(f, "%d\n", getpid()); fclose(f); }
    }

    print_banner(pad_path, pad_name, vendor, product, &ax, &cfg);
    print_touch_banner(&cfg);

    while (g_running) {
        if (g_reload_offset) {
            g_reload_offset = 0;
            reload_touch_offset(config_path, &cfg);
        }

        long long deadline = -1;
        for (int i = 0; i < WHEEL_COUNT; i++) {
            if (g_wheel_count[i] > 0 && (deadline < 0 || g_wheel_next_due_ms[i] < deadline))
                deadline = g_wheel_next_due_ms[i];
        }
        if (g_chord_start_ms >= 0) {
            /* Both m1/m2 already held: wake in time to fire the chord even
             * if no further pad events arrive while they're held. */
            long long chord_deadline = g_chord_start_ms + PANIC_CHORD_MS;
            if (deadline < 0 || chord_deadline < deadline)
                deadline = chord_deadline;
        }
        int timeout_ms = -1;
        if (deadline >= 0) {
            long long now = now_ms();
            timeout_ms = (int)(deadline > now ? deadline - now : 0);
        }

        struct pollfd pfds[2];
        pfds[0].fd = g_pad_fd;
        pfds[0].events = POLLIN;
        pfds[0].revents = 0;
        pfds[1].revents = 0;
        int nfds = 1;
        int touch_slot = -1;
        if (cfg.touch_offset_set && g_touch_fd >= 0) {
            pfds[1].fd = g_touch_fd;
            pfds[1].events = POLLIN;
            touch_slot = 1;
            nfds = 2;
        }
        int pr = poll(pfds, nfds, timeout_ms);
        if (pr < 0) {
            if (errno == EINTR)
                continue;
            perror("poll pad");
            break;
        }
        if (pr == 0) {
            /* Nothing readable: fire any wheel repeats that came due, and
             * check whether the panic chord's hold time has elapsed. */
            if (g_chord_start_ms >= 0)
                check_panic_chord(&cfg, 0, true); /* code 0: re-check elapsed time only */
            long long now = now_ms();
            for (int i = 0; i < WHEEL_COUNT; i++) {
                if (g_wheel_count[i] > 0 && now >= g_wheel_next_due_ms[i]) {
                    emit_wheel_notch(g_uinput_fd, i);
                    g_wheel_next_due_ms[i] = now + g_wheel_repeat_ms;
                }
            }
            continue;
        }

        if (touch_slot >= 0 && (pfds[touch_slot].revents & POLLIN)) {
            if (forward_touch_batch(&cfg) < 0) {
                if (errno == ENODEV) {
                    fprintf(stderr, "dpadkeys: touch: panel disconnected, re-detecting...\n");
                    if (g_touch_grabbed) { ioctl(g_touch_fd, EVIOCGRAB, 0); g_touch_grabbed = false; }
                    close(g_touch_fd);
                    g_touch_fd = -1;
                    while (g_running && (g_touch_fd = find_touch(&cfg, g_touch_path, sizeof(g_touch_path))) < 0) {
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
                        touch_sync_initial_contacts(&cfg);
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
        if (!(pfds[0].revents & POLLIN))
            continue;

        struct input_event ev;
        ssize_t n = read(g_pad_fd, &ev, sizeof(ev));
        if (n < 0) {
            if (errno == EINTR)
                continue;
            if (errno == ENODEV) {
                fprintf(stderr, "dpadkeys: pad disconnected (style switch?), re-detecting...\n");
                release_all_sources(&cfg);
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
                           (g_pad_fd = find_pad(cfg.device_match, O_RDWR, pad_path, sizeof(pad_path),
                                                 pad_name, sizeof(pad_name), &vendor, &product)) < 0) {
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
                detect_axes(g_pad_fd, &ax);
                raw_query_axes(g_pad_fd, &cfg);
                fprintf(stderr, "dpadkeys: reacquired pad=%s name=\"%s\"\n", pad_path, pad_name);
                print_banner(pad_path, pad_name, vendor, product, &ax, &cfg);
                continue;
            }
            if (errno == EAGAIN)
                continue;
            perror("read pad");
            break;
        }
        if (n != (ssize_t)sizeof(ev))
            continue;

        if (ev.type == EV_KEY) {
            if (ev.value == 2)
                continue; /* ignore autorepeat */
            /* Raw check ahead of (and independent of) whatever btn.m1/btn.m2
             * are mapped to in this config; mapping still happens below. */
            if (ev.code == BTN_C || ev.code == BTN_Z)
                check_panic_chord(&cfg, ev.code, ev.value != 0);
            handle_raw_key(&cfg, ev.code, ev.value != 0);
            for (int i = 0; i < BTN_MAP_LEN; i++) {
                if (BTN_MAP[i].code == ev.code) {
                    update_source(&cfg, BTN_MAP[i].src, ev.value != 0);
                    break;
                }
            }
        } else if (ev.type == EV_ABS) {
            handle_raw_abs(&cfg, ev.code, ev.value);
            if (ev.code == ABS_HAT0X) {
                update_source(&cfg, SRC_HAT_LEFT, ev.value < 0);
                update_source(&cfg, SRC_HAT_RIGHT, ev.value > 0);
            } else if (ev.code == ABS_HAT0Y) {
                update_source(&cfg, SRC_HAT_UP, ev.value < 0);
                update_source(&cfg, SRC_HAT_DOWN, ev.value > 0);
            } else if (ax.ls_x.present && ax.ls_y.present &&
                       (ev.code == ax.ls_x.code || ev.code == ax.ls_y.code)) {
                if (ev.code == ax.ls_x.code) ax.ls_rx = ev.value; else ax.ls_ry = ev.value;
                handle_stick_2d(&cfg, &ax.ls_x, &ax.ls_y, ax.ls_rx, ax.ls_ry, &ax.ls_active, &ax.ls_dirs,
                                cfg.deadzone, cfg.ls_invert_x, cfg.ls_invert_y,
                                SRC_LS_UP, SRC_LS_DOWN, SRC_LS_LEFT, SRC_LS_RIGHT);
            } else if (ax.rs_x.present && ax.rs_y.present &&
                       (ev.code == ax.rs_x.code || ev.code == ax.rs_y.code)) {
                if (ev.code == ax.rs_x.code) ax.rs_rx = ev.value; else ax.rs_ry = ev.value;
                handle_stick_2d(&cfg, &ax.rs_x, &ax.rs_y, ax.rs_rx, ax.rs_ry, &ax.rs_active, &ax.rs_dirs,
                                cfg.deadzone, cfg.rs_invert_x, cfg.rs_invert_y,
                                SRC_RS_UP, SRC_RS_DOWN, SRC_RS_LEFT, SRC_RS_RIGHT);
            } else if (ax.lt.present && ev.code == ax.lt.code) {
                handle_trigger_axis(&cfg, &ax.lt, ev.value, SRC_LT, cfg.deadzone);
            } else if (ax.rt.present && ev.code == ax.rt.code) {
                handle_trigger_axis(&cfg, &ax.rt, ev.value, SRC_RT, cfg.deadzone);
            }
        }
    }

    if (g_touch_grabbed)
        ioctl(g_touch_fd, EVIOCGRAB, 0);
    if (g_touch_fd >= 0)
        close(g_touch_fd);
    destroy_uinput(g_touch_uinput_fd);

    if (g_grabbed)
        ioctl(g_pad_fd, EVIOCGRAB, 0);
    if (g_pad_fd >= 0)
        close(g_pad_fd);
    destroy_uinput(g_uinput_fd);
    return 0;
}
