package com.dpad.mgr.core

import kotlinx.serialization.Serializable

/** One pad control the daemon can read (daemon source name, UI label, group). */
data class Source(val id: String, val label: String, val group: String)

object Sources {
    const val DPAD = "D-pad"
    const val FACE = "Face buttons"
    const val SHOULDER = "Shoulders / Triggers"
    const val BACK = "Back buttons"
    const val STICKS = "Sticks"
    const val MENU = "Menu"

    val ALL: List<Source> = listOf(
        Source("hat.up", "D-pad up", DPAD),
        Source("hat.down", "D-pad down", DPAD),
        Source("hat.left", "D-pad left", DPAD),
        Source("hat.right", "D-pad right", DPAD),
        Source("btn.south", "A (south)", FACE),
        Source("btn.east", "B (east)", FACE),
        Source("btn.north", "Y (north)", FACE),
        Source("btn.west", "X (west)", FACE),
        Source("btn.tl", "L1", SHOULDER),
        Source("btn.tr", "R1", SHOULDER),
        Source("btn.tl2", "L2 button", SHOULDER),
        Source("btn.tr2", "R2 button", SHOULDER),
        Source("lt", "Left trigger (L2)", SHOULDER),
        Source("rt", "Right trigger (R2)", SHOULDER),
        Source("btn.m1", "Back M1", BACK),
        Source("btn.m2", "Back M2", BACK),
        Source("ls.up", "Left stick up", STICKS),
        Source("ls.down", "Left stick down", STICKS),
        Source("ls.left", "Left stick left", STICKS),
        Source("ls.right", "Left stick right", STICKS),
        Source("rs.up", "Right stick up", STICKS),
        Source("rs.down", "Right stick down", STICKS),
        Source("rs.left", "Right stick left", STICKS),
        Source("rs.right", "Right stick right", STICKS),
        Source("btn.thumbl", "L3", STICKS),
        Source("btn.thumbr", "R3", STICKS),
        Source("btn.select", "Select/View", MENU),
        Source("btn.start", "Start/Menu", MENU),
        Source("btn.mode", "Home/Guide", MENU),
    )
    val GROUPS: List<String> = listOf(DPAD, FACE, SHOULDER, BACK, STICKS, MENU)
    val IDS: Set<String> = ALL.map { it.id }.toSet()
    private val ORDER: Map<String, Int> = ALL.mapIndexed { i, s -> s.id to i }.toMap()

    /** Sources.ALL order first, then unknown (raw) names alphabetically. */
    val comparator: Comparator<String> = compareBy<String>({ ORDER[it] ?: Int.MAX_VALUE }, { it })

    private val BUTTON_LIKE = Regex("^(btn\\.[a-zA-Z0-9_]+|key\\.0x[0-9a-fA-F]{1,3}|hat\\.(up|down|left|right)|lt|rt)$")

    /** True if [src] is a button-like source: btn.*, key.0x…, hat.*, lt, rt — never a stick
     *  direction (ls./rs.) or any other analog/raw axis. The only sources allowed in a panic chord. */
    fun isButtonLike(src: String): Boolean = BUTTON_LIKE.matches(src)

    /** True if [chord] is a valid panic chord: `a` or `a+b`, every part button-like. */
    fun isValidPanicChord(chord: String): Boolean {
        val parts = chord.split('+')
        return parts.size in 1..2 && parts.all { isButtonLike(it) }
    }
}

/** Source-name validation and labelling for both semantic (`hat.up`) and raw (`key.0x130`, `abs.0x12.pos`) names. */
object SourceNames {
    val RAW = Regex("^(key\\.0x[0-9a-fA-F]{1,3}|abs\\.0x[0-9a-fA-F]{1,2}\\.(neg|pos))$")

    /** True if [id] is a chord (`<hold>+<src>`). */
    fun isChord(id: String): Boolean = id.indexOf('+') >= 0

    private fun isPlainValid(id: String): Boolean = id in Sources.IDS || RAW.matches(id)

    fun isValid(id: String): Boolean {
        val plus = id.indexOf('+')
        if (plus < 0) return isPlainValid(id)
        val hold = id.substring(0, plus)
        val rest = id.substring(plus + 1)
        return Sources.isButtonLike(hold) && !isChord(rest) && isPlainValid(rest)
    }

    private fun plainLabel(id: String): String = Sources.ALL.firstOrNull { it.id == id }?.label ?: id

    /** Chords render as "Hold <hold label> + <src label>"; everything else as its plain label. */
    fun label(id: String): String {
        val plus = id.indexOf('+')
        if (plus < 0) return plainLabel(id)
        val hold = id.substring(0, plus)
        val rest = id.substring(plus + 1)
        return "Hold ${plainLabel(hold)} + ${plainLabel(rest)}"
    }

    /** Panic-chord label: "A + B" (or just "A" for a single control) — plain per-source labels,
     *  no "Hold" prefix (unlike [label], which is for key-binding chords). */
    fun chordLabel(chord: String): String = chord.split('+').joinToString(" + ") { plainLabel(it) }
}

/** A key the user can pick: UI label <-> daemon KEY_ name. [disabledReason], when non-null, means the
 *  key is visible in pickers but cannot be bound (shown greyed out with the reason below its label).
 *  Currently only KEY_Q (Android treats a keyboard with a Q key as "full" and hides the on-screen
 *  keyboard everywhere), but the mechanism is generic so more can be marked later. */
data class KeyDef(val label: String, val keyName: String, val disabledReason: String? = null)

/** A labeled group of [KeyDef]s, for any full grouped key list (a key picker, or the profile
 *  editor's "Unassigned" expansion). */
data class KeyGroup(val label: String, val keys: List<KeyDef>)

object Keys {
    const val NONE = "NONE"

    private const val Q_DISABLED_REASON =
        "Not bindable: Android treats a keyboard with a Q key as a full keyboard and hides the on-screen keyboard everywhere."

    /** "Game keys": the keys a typical OSRS mobile profile needs. */
    val GAME: List<KeyDef> = buildList {
        for (i in 1..12) add(KeyDef("F$i", "KEY_F$i"))
        for (i in 0..9) add(KeyDef("$i", "KEY_$i"))
        add(KeyDef("Up", "KEY_UP"))
        add(KeyDef("Down", "KEY_DOWN"))
        add(KeyDef("Left", "KEY_LEFT"))
        add(KeyDef("Right", "KEY_RIGHT"))
        add(KeyDef("Enter", "KEY_ENTER"))
        add(KeyDef("Space", "KEY_SPACE"))
        add(KeyDef("Esc", "KEY_ESC"))
    }
    val LETTERS: List<KeyDef> = ('A'..'Z').map { c ->
        if (c == 'Q') KeyDef("Q", "KEY_Q", disabledReason = Q_DISABLED_REASON) else KeyDef("$c", "KEY_$c")
    }
    val FUNCTION_HIGH: List<KeyDef> = (13..24).map { KeyDef("F$it", "KEY_F$it") }
    val EDITING: List<KeyDef> = listOf(
        KeyDef("Tab", "KEY_TAB"),
        KeyDef("Backspace", "KEY_BACKSPACE"),
        KeyDef("Insert", "KEY_INSERT"),
        KeyDef("Delete", "KEY_DELETE"),
        KeyDef("Home", "KEY_HOME"),
        KeyDef("End", "KEY_END"),
        KeyDef("Page Up", "KEY_PAGEUP"),
        KeyDef("Page Down", "KEY_PAGEDOWN"),
    )
    val MODIFIERS: List<KeyDef> = listOf(
        KeyDef("Left Shift", "KEY_LEFTSHIFT"),
        KeyDef("Right Shift", "KEY_RIGHTSHIFT"),
        KeyDef("Left Ctrl", "KEY_LEFTCTRL"),
        KeyDef("Right Ctrl", "KEY_RIGHTCTRL"),
        KeyDef("Left Alt", "KEY_LEFTALT"),
        KeyDef("Right Alt", "KEY_RIGHTALT"),
        KeyDef("Left Meta", "KEY_LEFTMETA"),
        KeyDef("Right Meta", "KEY_RIGHTMETA"),
        KeyDef("Caps Lock", "KEY_CAPSLOCK"),
        KeyDef("Menu", "KEY_MENU"),
    )
    val NUMPAD: List<KeyDef> = buildList {
        for (i in 0..9) add(KeyDef("Num $i", "KEY_KP$i"))
        add(KeyDef("Num .", "KEY_KPDOT"))
        add(KeyDef("Num Enter", "KEY_KPENTER"))
        add(KeyDef("Num +", "KEY_KPPLUS"))
        add(KeyDef("Num -", "KEY_KPMINUS"))
        add(KeyDef("Num *", "KEY_KPASTERISK"))
        add(KeyDef("Num /", "KEY_KPSLASH"))
        add(KeyDef("Num Lock", "KEY_NUMLOCK"))
    }
    val PUNCTUATION: List<KeyDef> = listOf(
        KeyDef("-", "KEY_MINUS"),
        KeyDef("=", "KEY_EQUAL"),
        KeyDef("[", "KEY_LEFTBRACE"),
        KeyDef("]", "KEY_RIGHTBRACE"),
        KeyDef("\\", "KEY_BACKSLASH"),
        KeyDef(";", "KEY_SEMICOLON"),
        KeyDef("'", "KEY_APOSTROPHE"),
        KeyDef("`", "KEY_GRAVE"),
        KeyDef(",", "KEY_COMMA"),
        KeyDef(".", "KEY_DOT"),
        KeyDef("/", "KEY_SLASH"),
    )
    val MEDIA_SYSTEM: List<KeyDef> = listOf(
        KeyDef("Volume Up", "KEY_VOLUMEUP"),
        KeyDef("Volume Down", "KEY_VOLUMEDOWN"),
        KeyDef("Mute", "KEY_MUTE"),
        KeyDef("Play/Pause", "KEY_PLAYPAUSE"),
        KeyDef("Next", "KEY_NEXTSONG"),
        KeyDef("Previous", "KEY_PREVIOUSSONG"),
        KeyDef("Back", "KEY_BACK"),
        KeyDef("Home Page", "KEY_HOMEPAGE"),
        KeyDef("Print Screen", "KEY_SYSRQ"),
        KeyDef("Scroll Lock", "KEY_SCROLLLOCK"),
        KeyDef("Pause", "KEY_PAUSE"),
    )
    /** The four scroll-wheel targets: bound via mouse scroll events, which carry the OSRS ban-risk warning. */
    val WHEEL: List<KeyDef> = listOf(
        KeyDef("Wheel up", "WHEEL_UP"),
        KeyDef("Wheel down", "WHEEL_DOWN"),
        KeyDef("Wheel left", "HWHEEL_LEFT"),
        KeyDef("Wheel right", "HWHEEL_RIGHT"),
    )

    /** Every group, in display order, for any full grouped key list (a key picker, or the editor's
     *  "Unassigned" expansion). */
    val GROUPS: List<KeyGroup> = listOf(
        KeyGroup("Game keys", GAME),
        KeyGroup("Letters", LETTERS),
        KeyGroup("Function F13–F24", FUNCTION_HIGH),
        KeyGroup("Editing", EDITING),
        KeyGroup("Modifiers", MODIFIERS),
        KeyGroup("Numpad", NUMPAD),
        KeyGroup("Punctuation", PUNCTUATION),
        KeyGroup("Media & system", MEDIA_SYSTEM),
        KeyGroup("Mouse wheel", WHEEL),
    )
    val ALL: List<KeyDef> = GROUPS.flatMap { it.keys }
    private val byName = ALL.associateBy { it.keyName }

    fun label(keyName: String?): String = when (keyName) {
        null, NONE -> "None"
        else -> byName[keyName]?.label ?: keyName
    }
    fun isValid(keyName: String): Boolean = keyName == NONE || byName.containsKey(keyName)

    val WHEEL_TARGETS: Set<String> = WHEEL.map { it.keyName }.toSet()
}

@Serializable
data class Profile(
    val name: String,
    /** source id (plain or `<hold>+<src>` chord) -> KEY_* name or "NONE". Missing entries mean NONE. */
    val map: Map<String, String> = emptyMap(),
    val deadzone: Float = 0.5f,
    val lsInvertY: Boolean = false,
    val rsInvertY: Boolean = false,
    val lsInvertX: Boolean = false,
    val rsInvertX: Boolean = false,
    /**
     * Deprecated pre-chord modifier fields. Kept only so old stored JSON still deserializes;
     * [Store] migrates them into [map] as `<modifier>+<src>` chords on load and clears them.
     * Never populated by any code path after that.
     */
    val modifier: String? = null,
    val modBindings: Map<String, String> = emptyMap(),
    val wheelRepeatMs: Int = 120,
    /** Panic chord: `<src>` or `<src>+<src>`, both button-like (Sources.isButtonLike). Holding it
     *  1s pauses mapping, 4s restarts the daemon. Null means no chord is configured. Required
     *  before [touchOffsetEnabled] can be turned on -- see ProfilesScreen/CalibrateActivity. */
    val panicChord: String? = null,
    /** Stylus/touch offset, calibrated via CalibrateActivity. Display-pixel units (as seen on
     *  screen in the game's orientation) -- see [touchSpace]. */
    val touchOffsetEnabled: Boolean = false,
    val touchDx: Int = 0,
    val touchDy: Int = 0,
    /** Coordinate space [touchDx]/[touchDy] are stored in: "panel" (legacy, natural/portrait
     *  panel units) or "display" (current display px, as the daemon now expects via
     *  `touch.display`). Defaults to "panel" so an old serialized profile missing this field is
     *  recognized as needing migration on load -- see [Store]'s migration, which converts the
     *  offset and flips this to "display". Every profile constructed fresh by app code should
     *  pass "display" explicitly (never rely on this default) since it needs no migration. */
    val touchSpace: String = "panel",
) {
    fun key(source: String): String = map[source] ?: Keys.NONE

    /** True if any binding targets a scroll-wheel key. */
    fun usesWheel(): Boolean = map.values.any { it in Keys.WHEEL_TARGETS }

    // ---- key-first accessors ----

    /** Sources (plain or chord) bound to [key], Sources.ALL order then raw/chord names alphabetically. */
    fun sourcesFor(key: String): List<String> =
        map.filterValues { it == key }.keys.sortedWith(Sources.comparator)

    /** Binds [src] to [key]; a source maps to exactly one key, so any previous binding of [src] is replaced. */
    fun bind(src: String, key: String): Profile = copy(map = map + (src to key))

    fun unbind(src: String): Profile = copy(map = map - src)

    private fun keyOrNone(k: String?): String = if (k != null && Keys.isValid(k)) k else Keys.NONE

    /** Exact daemon config text: source/target lines (semantic then raw/chord, as stored), deadzone, invert flags, wheel repeat. */
    fun toConfigText(): String = buildString {
        append("# generated by Odin DPad Keys for profile \"").append(name.replace('\n', ' ')).append("\"\n")
        for (s in Sources.ALL) {
            append(s.id).append(' ').append(keyOrNone(map[s.id])).append('\n')
        }
        val extra = map.keys.filter { it !in Sources.IDS && SourceNames.isValid(it) }.sortedWith(Sources.comparator)
        for (src in extra) {
            append(src).append(' ').append(keyOrNone(map[src])).append('\n')
        }
        append("deadzone ").append(String.format(java.util.Locale.ROOT, "%.2f", deadzone.coerceIn(0.2f, 0.8f))).append('\n')
        append("ls.invert_y ").append(if (lsInvertY) 1 else 0).append('\n')
        append("ls.invert_x ").append(if (lsInvertX) 1 else 0).append('\n')
        append("rs.invert_y ").append(if (rsInvertY) 1 else 0).append('\n')
        append("rs.invert_x ").append(if (rsInvertX) 1 else 0).append('\n')
        append("wheel_repeat_ms ").append(wheelRepeatMs.coerceIn(60, 400)).append('\n')
        if (panicChord != null) {
            append("panic ").append(panicChord).append('\n')
        }
        // Safety: the touch pass-through is never emitted without a panic chord to escape it.
        if (touchOffsetEnabled && panicChord != null) {
            append("touch.offset ").append(touchDx).append(' ').append(touchDy).append('\n')
        }
    }

    companion object {
        val OSRS = Profile(
            name = "OSRS",
            map = mapOf(
                "hat.up" to "KEY_F1", "hat.down" to "KEY_F2", "hat.left" to "KEY_F3", "hat.right" to "KEY_F4",
                "ls.up" to "KEY_UP", "ls.down" to "KEY_DOWN", "ls.left" to "KEY_LEFT", "ls.right" to "KEY_RIGHT",
            ),
            deadzone = 0.5f,
            lsInvertY = true,
            touchSpace = "display",
        )
        val WASD = Profile(
            name = "WASD",
            map = mapOf("hat.up" to "KEY_W", "hat.down" to "KEY_S", "hat.left" to "KEY_A", "hat.right" to "KEY_D"),
            deadzone = 0.5f,
            touchSpace = "display",
        )
        val DEFAULTS = listOf(OSRS, WASD)
    }
}

@Serializable
data class AppData(
    val profiles: List<Profile> = Profile.DEFAULTS,
    /** package -> profile name. Absent = Off. */
    val assignments: Map<String, String> = emptyMap(),
    /** Global (not per-profile) opt-in: when true, the --serve daemon is started with --allow-q,
     *  making KEY_Q bindable. Off by default because binding Q makes Android treat the app's
     *  virtual keyboard as "full" and hide the on-screen keyboard everywhere unless the user has
     *  turned on Android's "Use on-screen keyboard" override (Settings > System > Languages &
     *  input > Physical keyboard) -- see the confirmation dialog in ProfilesScreen.kt. */
    val allowQ: Boolean = false,
) {
    fun profile(name: String?): Profile? = name?.let { n -> profiles.firstOrNull { it.name == n } }
    fun profileFor(pkg: String): Profile? = profile(assignments[pkg])
}
