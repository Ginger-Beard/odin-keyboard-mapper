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

/** A key the user can pick: UI label <-> daemon KEY_ name. */
data class KeyDef(val label: String, val keyName: String, val other: Boolean = false)

object Keys {
    const val NONE = "NONE"

    /** "OSRS keys": the keys a typical OSRS mobile profile needs. */
    val PRIMARY: List<KeyDef> = buildList {
        for (i in 1..12) add(KeyDef("F$i", "KEY_F$i"))
        for (i in 0..9) add(KeyDef("$i", "KEY_$i"))
        add(KeyDef("Up", "KEY_UP"))
        add(KeyDef("Down", "KEY_DOWN"))
        add(KeyDef("Left", "KEY_LEFT"))
        add(KeyDef("Right", "KEY_RIGHT"))
        add(KeyDef("Enter", "KEY_ENTER"))
        add(KeyDef("Space", "KEY_SPACE"))
        add(KeyDef("Esc", "KEY_ESC"))
        add(KeyDef("Wheel up", "WHEEL_UP"))
        add(KeyDef("Wheel down", "WHEEL_DOWN"))
    }
    /** "Letters": A-Z plus the horizontal wheel. */
    val OTHER: List<KeyDef> = ('A'..'Z').map { KeyDef("$it", "KEY_$it", other = true) } + listOf(
        KeyDef("Wheel left", "HWHEEL_LEFT", other = true),
        KeyDef("Wheel right", "HWHEEL_RIGHT", other = true),
    )
    val ALL: List<KeyDef> = PRIMARY + OTHER
    private val byName = ALL.associateBy { it.keyName }

    fun label(keyName: String?): String = when (keyName) {
        null, NONE -> "None"
        else -> byName[keyName]?.label ?: keyName
    }
    fun isValid(keyName: String): Boolean = keyName == NONE || byName.containsKey(keyName)

    /** The four scroll-wheel targets: bound via mouse scroll events, which carry the OSRS ban-risk warning. */
    val WHEEL_TARGETS: Set<String> = setOf("WHEEL_UP", "WHEEL_DOWN", "HWHEEL_LEFT", "HWHEEL_RIGHT")
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
    /** Stylus/touch offset, calibrated via CalibrateActivity. Panel units (natural/portrait orientation). */
    val touchOffsetEnabled: Boolean = false,
    val touchDx: Int = 0,
    val touchDy: Int = 0,
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
        )
        val WASD = Profile(
            name = "WASD",
            map = mapOf("hat.up" to "KEY_W", "hat.down" to "KEY_S", "hat.left" to "KEY_A", "hat.right" to "KEY_D"),
            deadzone = 0.5f,
        )
        val DEFAULTS = listOf(OSRS, WASD)
    }
}

@Serializable
data class AppData(
    val profiles: List<Profile> = Profile.DEFAULTS,
    /** package -> profile name. Absent = Off. */
    val assignments: Map<String, String> = emptyMap(),
) {
    fun profile(name: String?): Profile? = name?.let { n -> profiles.firstOrNull { it.name == n } }
    fun profileFor(pkg: String): Profile? = profile(assignments[pkg])
}
