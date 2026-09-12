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

typedef struct {
    int target[MAX_SOURCES]; /* KEY_* code, TARGET_WHEEL_*, or TARGET_NONE */
    int mod_target[MAX_SOURCES];   /* layer active while the modifier is held */
    bool mod_defined[MAX_SOURCES]; /* true if a mod+<src> line set mod_target */
    bool defined[MAX_SOURCES];     /* any config line referenced this source */
    int modifier_src;      /* source slot of the "MOD" source, or -1 if none */
    double deadzone;       /* fraction of half-range that counts as pressed */
    bool ls_invert_y, rs_invert_y; /* flip stick up/down */
    int wheel_repeat_ms;   /* repeat interval for held wheel targets */
    int n_raw;             /* raw sources in use (slots SRC_COUNT..SRC_COUNT+n_raw-1) */
    raw_source_t raw[MAX_RAW];
    char device_match[128]; /* substring of the pad name, or "vvvv:pppp" hex; empty = any */
} config_t;

static volatile sig_atomic_t g_running = 1;
static int g_uinput_fd = -1;
static int g_pad_fd = -1;
static bool g_grabbed = false;
static bool g_verbose = false;
static bool g_source_pressed[MAX_SOURCES] = {0};
static int g_key_count[KEY_CNT] = {0};
static bool g_modifier_held = false;
static int g_resolved_target[MAX_SOURCES] = {0}; /* target used at press, replayed at release */
static int g_wheel_count[WHEEL_COUNT] = {0};
static long long g_wheel_next_due_ms[WHEEL_COUNT] = {0};
static int g_wheel_repeat_ms = 120;

static void on_signal(int sig) {
    (void)sig;
    g_running = 0;
}

static void init_config(config_t *cfg) {
    memset(cfg, 0, sizeof(*cfg));
    for (int i = 0; i < MAX_SOURCES; i++) {
        cfg->target[i] = TARGET_NONE;
        cfg->mod_target[i] = TARGET_NONE;
        cfg->mod_defined[i] = false;
        cfg->defined[i] = false;
    }
    cfg->modifier_src = -1;
    cfg->deadzone = 0.5;
    cfg->ls_invert_y = false; cfg->rs_invert_y = false;
    cfg->wheel_repeat_ms = 120;
    cfg->n_raw = 0;
    cfg->device_match[0] = '\0';
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

        bool is_mod_line = strncmp(tok1, "mod+", 4) == 0;
        const char *src_name = is_mod_line ? tok1 + 4 : tok1;
        const char *err = NULL;
        int src = lookup_or_add_source(cfg, src_name, &err);
        if (src < 0) {
            if (err) fprintf(stderr, "dpadkeys: %s:%d: %s\n", path, lineno, err);
            else fprintf(stderr, "dpadkeys: %s:%d: unknown source '%s'\n", path, lineno, src_name);
            exit(2);
        }
        if (src < SRC_COUNT && !cfg->defined[src]) {
            const char *raw = semantic_collides_with_raw(cfg, src);
            if (raw) {
                fprintf(stderr, "dpadkeys: %s:%d: source '%s' collides with raw source '%s'\n",
                        path, lineno, src_name, raw);
                exit(2);
            }
        }
        cfg->defined[src] = true;

        if (is_mod_line) {
            int target = lookup_target(tok2);
            if (target == TARGET_UNKNOWN) {
                fprintf(stderr, "dpadkeys: %s:%d: unknown target '%s'\n", path, lineno, tok2);
                exit(2);
            }
            cfg->mod_defined[src] = true;
            cfg->mod_target[src] = target;
            continue;
        }

        if (strcmp(tok2, "MOD") == 0) {
            if (cfg->modifier_src != -1) {
                fprintf(stderr, "dpadkeys: %s:%d: modifier already declared as '%s'\n",
                        path, lineno, source_name(cfg, cfg->modifier_src));
                exit(2);
            }
            cfg->modifier_src = src;
            continue;
        }

        int target = lookup_target(tok2);
        if (target == TARGET_UNKNOWN) {
            fprintf(stderr, "dpadkeys: %s:%d: unknown target '%s'\n", path, lineno, tok2);
            exit(2);
        }
        cfg->target[src] = target;
    }
    fclose(f);
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
    /* semantic sources are always listed; raw ones exactly as they were given */
    for (int i = 0; i < n_sources(cfg); i++) {
        const char *tgt = (i == cfg->modifier_src) ? "MOD" : key_name(cfg->target[i]);
        fprintf(out, "%-12s %s\n", source_name(cfg, i), tgt);
    }
    fprintf(out, "%-12s %.2f\n", "deadzone", cfg->deadzone);
    fprintf(out, "%-12s %d\n", "ls.invert_y", cfg->ls_invert_y ? 1 : 0);
    fprintf(out, "%-12s %d\n", "rs.invert_y", cfg->rs_invert_y ? 1 : 0);
    for (int i = 0; i < n_sources(cfg); i++) {
        if (!cfg->mod_defined[i]) continue;
        char name[48];
        snprintf(name, sizeof(name), "mod+%s", source_name(cfg, i));
        fprintf(out, "%-12s %s\n", name, key_name(cfg->mod_target[i]));
    }
    fprintf(out, "%-12s %d\n", "wheel_repeat_ms", cfg->wheel_repeat_ms);
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
    for (int i = 0; i < n_sources(cfg); i++) {
        if (cfg->target[i] >= 0 && cfg->target[i] < KEY_CNT) used[cfg->target[i]] = true;
        if (cfg->mod_defined[i] && cfg->mod_target[i] >= 0 && cfg->mod_target[i] < KEY_CNT)
            used[cfg->mod_target[i]] = true;
    }
    for (int c = 0; c < KEY_CNT; c++) {
        if (used[c] && ioctl(fd, UI_SET_KEYBIT, c) < 0) {
            perror("UI_SET_KEYBIT");
            close(fd);
            return -1;
        }
    }

    bool uses_wheel = false;
    for (int i = 0; i < n_sources(cfg); i++) {
        if (target_is_wheel(cfg->target[i])) uses_wheel = true;
        if (cfg->mod_defined[i] && target_is_wheel(cfg->mod_target[i])) uses_wheel = true;
    }
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

/* Layer resolution happens at PRESS time and is cached in g_resolved_target so
 * RELEASE always targets the same key/wheel even if the modifier changed in
 * between (no stuck keys). Toggling the modifier itself never re-evaluates
 * sources that are already held. */
static void update_source(const config_t *cfg, int src, bool pressed) {
    if (g_source_pressed[src] == pressed) return;
    g_source_pressed[src] = pressed;

    if (src == cfg->modifier_src) {
        g_modifier_held = pressed;
        if (g_verbose)
            fprintf(stderr, "dpadkeys: %s -> %s (modifier)\n", source_name(cfg, src), pressed ? "down" : "up");
        return;
    }

    if (pressed) {
        int target = (g_modifier_held && cfg->mod_defined[src]) ? cfg->mod_target[src] : cfg->target[src];
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
                            int rx, int ry, bool *active, unsigned *dirs, double deadzone, bool invert_y,
                            source_id_t up, source_id_t down, source_id_t left, source_id_t right) {
    static const unsigned SECT[8] = { DIR_R, DIR_R|DIR_U, DIR_U, DIR_U|DIR_L,
                                      DIR_L, DIR_L|DIR_D, DIR_D, DIR_D|DIR_R };
    double nx = (rx - ax->center) / ax->half;
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
        printf("%s: bus=0x%04x vendor=0x%04x product=0x%04x name=\"%s\" "
               "BTN_SOUTH=%s hat=%s dpad_btns=%s ls=%s rs=%s lt=%s rt=%s\n",
               path, id.bustype, vendor, product, name, has_south ? "yes" : "no",
               ax.has_hat ? "yes" : "no", ax.has_dpad_btns ? "yes" : "no",
               ax.ls_x.present ? "yes" : "no", ax.rs_x.present ? "yes" : "no",
               ax.lt.present ? "yes" : "no", ax.rt.present ? "yes" : "no");
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
            "[--verbose] [--pidfile PATH] [--print-config]\n"
            "       %s --profile fkeys|wasd [--grab] ...\n"
            "       %s --learn [--learn-timeout-ms N] [--config FILE] [--device ...] [--pidfile PATH]\n",
            argv0, argv0, argv0);
}

/* ---- learn mode ---- */

/* Waits (pad ungrabbed) for the first press and prints `learned <source>`:
 * the semantic name when one exists, else key.0xNNN / abs.0xNN.neg|pos.
 * Returns 0 on success, 3 on timeout (`learned NONE`). */
static int learn_mode(int fd, const axes_t *ax, long long timeout_ms) {
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

    long long deadline = now_ms() + timeout_ms;
    struct pollfd pfd = { .fd = fd, .events = POLLIN };
    char out[48] = {0};
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
        if (ev.type == EV_KEY) {
            if (ev.value != 1) continue; /* release / autorepeat */
            int sem = semantic_for_key(ev.code);
            if (sem >= 0) snprintf(out, sizeof(out), "%s", SOURCE_NAMES[sem]);
            else snprintf(out, sizeof(out), "key.0x%x", (unsigned)ev.code);
        } else if (ev.type == EV_ABS && ev.code < ABS_CNT && axes[ev.code].known) {
            double v = (ev.value - axes[ev.code].center) / axes[ev.code].half;
            int st = axes[ev.code].state;
            int ns = st == 1 ? (v >= dz - 0.1 ? 1 : 0)
                   : st == -1 ? (v <= -(dz - 0.1) ? -1 : 0)
                   : (v >= dz ? 1 : (v <= -dz ? -1 : 0));
            if (ns == st) continue;
            axes[ev.code].state = ns;
            if (ns == 0) continue; /* return to centre is a release */
            int sem = semantic_for_abs(ev.code, ns);
            if (sem < 0) {
                if (ax->ls_x.present && ax->ls_y.present && ev.code == ax->ls_x.code)
                    sem = ns < 0 ? SRC_LS_LEFT : SRC_LS_RIGHT;
                else if (ax->ls_x.present && ax->ls_y.present && ev.code == ax->ls_y.code)
                    sem = ns < 0 ? SRC_LS_UP : SRC_LS_DOWN;
                else if (ax->rs_x.present && ax->rs_y.present && ev.code == ax->rs_x.code)
                    sem = ns < 0 ? SRC_RS_LEFT : SRC_RS_RIGHT;
                else if (ax->rs_x.present && ax->rs_y.present && ev.code == ax->rs_y.code)
                    sem = ns < 0 ? SRC_RS_UP : SRC_RS_DOWN;
                else if (ax->lt.present && ev.code == ax->lt.code && ns > 0)
                    sem = SRC_LT;
                else if (ax->rt.present && ev.code == ax->rt.code && ns > 0)
                    sem = SRC_RT;
            }
            if (sem >= 0) snprintf(out, sizeof(out), "%s", SOURCE_NAMES[sem]);
            else snprintf(out, sizeof(out), "abs.0x%x.%s", (unsigned)ev.code, ns < 0 ? "neg" : "pos");
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
         * main loop notices g_running == 0 and cleans up. */
        struct sigaction sa;
        memset(&sa, 0, sizeof(sa));
        sa.sa_handler = on_signal;
        sigemptyset(&sa.sa_mask);
        sa.sa_flags = 0;
        sigaction(SIGINT, &sa, NULL);
        sigaction(SIGTERM, &sa, NULL);
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
        int rc = learn_mode(g_pad_fd, &ax, remaining > 0 ? remaining : 0);
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

    if (pidfile) {
        FILE *pf = fopen(pidfile, "r");
        if (pf) {
            int oldpid = 0;
            if (fscanf(pf, "%d", &oldpid) == 1 && oldpid > 0 && kill(oldpid, 0) == 0) {
                fprintf(stderr, "dpadkeys: already running as pid %d (per %s). Exiting.\n", oldpid, pidfile);
                fclose(pf);
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

    struct pollfd pfd = { .fd = g_pad_fd, .events = POLLIN };
    while (g_running) {
        long long deadline = -1;
        for (int i = 0; i < WHEEL_COUNT; i++) {
            if (g_wheel_count[i] > 0 && (deadline < 0 || g_wheel_next_due_ms[i] < deadline))
                deadline = g_wheel_next_due_ms[i];
        }
        int timeout_ms = -1;
        if (deadline >= 0) {
            long long now = now_ms();
            timeout_ms = (int)(deadline > now ? deadline - now : 0);
        }

        pfd.fd = g_pad_fd;
        int pr = poll(&pfd, 1, timeout_ms);
        if (pr < 0) {
            if (errno == EINTR)
                continue;
            perror("poll pad");
            break;
        }
        if (pr == 0) {
            /* Nothing readable: fire any wheel repeats that came due. */
            long long now = now_ms();
            for (int i = 0; i < WHEEL_COUNT; i++) {
                if (g_wheel_count[i] > 0 && now >= g_wheel_next_due_ms[i]) {
                    emit_wheel_notch(g_uinput_fd, i);
                    g_wheel_next_due_ms[i] = now + g_wheel_repeat_ms;
                }
            }
            continue;
        }

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
                pfd.fd = g_pad_fd;
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
                                cfg.deadzone, cfg.ls_invert_y, SRC_LS_UP, SRC_LS_DOWN, SRC_LS_LEFT, SRC_LS_RIGHT);
            } else if (ax.rs_x.present && ax.rs_y.present &&
                       (ev.code == ax.rs_x.code || ev.code == ax.rs_y.code)) {
                if (ev.code == ax.rs_x.code) ax.rs_rx = ev.value; else ax.rs_ry = ev.value;
                handle_stick_2d(&cfg, &ax.rs_x, &ax.rs_y, ax.rs_rx, ax.rs_ry, &ax.rs_active, &ax.rs_dirs,
                                cfg.deadzone, cfg.rs_invert_y, SRC_RS_UP, SRC_RS_DOWN, SRC_RS_LEFT, SRC_RS_RIGHT);
            } else if (ax.lt.present && ev.code == ax.lt.code) {
                handle_trigger_axis(&cfg, &ax.lt, ev.value, SRC_LT, cfg.deadzone);
            } else if (ax.rt.present && ev.code == ax.rt.code) {
                handle_trigger_axis(&cfg, &ax.rt, ev.value, SRC_RT, cfg.deadzone);
            }
        }
    }

    if (g_grabbed)
        ioctl(g_pad_fd, EVIOCGRAB, 0);
    if (g_pad_fd >= 0)
        close(g_pad_fd);
    destroy_uinput(g_uinput_fd);
    return 0;
}
