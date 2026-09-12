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
        Source("lt", "L2 trigger", SHOULDER),
        Source("rt", "R2 trigger", SHOULDER),
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
}

/** Source-name validation and labelling for both semantic (`hat.up`) and raw (`key.0x130`, `abs.0x12.pos`) names. */
object SourceNames {
    val RAW = Regex("^(key\\.0x[0-9a-fA-F]{1,3}|abs\\.0x[0-9a-fA-F]{1,2}\\.(neg|pos))$")
    fun isValid(id: String): Boolean = id in Sources.IDS || RAW.matches(id)
    fun label(id: String): String = Sources.ALL.firstOrNull { it.id == id }?.label ?: id
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
}

@Serializable
data class Profile(
    val name: String,
    /** source id -> KEY_* name or "NONE". Missing entries mean NONE. */
    val map: Map<String, String> = emptyMap(),
    val deadzone: Float = 0.5f,
    val lsInvertY: Boolean = false,
    val rsInvertY: Boolean = false,
    /** source id of the control that acts as the modifier, or null for none. */
    val modifier: String? = null,
    /** source id -> KEY_ name, WHEEL_ target, or "NONE"; active only while [modifier] is held. Missing entries fall through to [map]. */
    val modBindings: Map<String, String> = emptyMap(),
    val wheelRepeatMs: Int = 120,
) {
    fun key(source: String): String = map[source] ?: Keys.NONE

    // ---- key-first accessors (base layer) ----

    /** Sources bound to [key] in the base layer, Sources.ALL order then raw names alphabetically. */
    fun sourcesFor(key: String): List<String> =
        map.filterValues { it == key }.keys.filter { it != modifier }.sortedWith(Sources.comparator)

    /** Binds [src] to [key]; a source maps to exactly one key, so any previous binding of [src] is replaced. */
    fun bind(src: String, key: String): Profile =
        copy(map = map + (src to key), modifier = if (src == modifier) null else modifier)

    fun unbind(src: String): Profile = copy(map = map - src)

    // ---- key-first accessors (modifier layer) ----

    fun modSourcesFor(key: String): List<String> =
        modBindings.filterValues { it == key }.keys.filter { it != modifier }.sortedWith(Sources.comparator)

    fun bindMod(src: String, key: String): Profile =
        copy(modBindings = modBindings + (src to key), modifier = if (src == modifier) null else modifier)

    fun unbindMod(src: String): Profile = copy(modBindings = modBindings - src)

    /** Sets the modifier control; that source can no longer carry a key in either layer. */
    fun withModifier(src: String?): Profile =
        copy(modifier = src, map = if (src == null) map else map - src, modBindings = if (src == null) modBindings else modBindings - src)

    private fun keyOrNone(k: String?): String = if (k != null && Keys.isValid(k)) k else Keys.NONE

    /** Exact daemon config text: source/target lines (semantic then raw, as stored), deadzone, invert flags, mod+ layer, wheel repeat. */
    fun toConfigText(): String = buildString {
        append("# generated by Odin DPad Keys for profile \"").append(name.replace('\n', ' ')).append("\"\n")
        for (s in Sources.ALL) {
            val tgt = if (s.id == modifier) "MOD" else keyOrNone(map[s.id])
            append(s.id).append(' ').append(tgt).append('\n')
        }
        val rawSources = map.keys.filter { it !in Sources.IDS && SourceNames.RAW.matches(it) }.sorted()
        for (src in rawSources) {
            val tgt = if (src == modifier) "MOD" else keyOrNone(map[src])
            append(src).append(' ').append(tgt).append('\n')
        }
        val m = modifier
        if (m != null && m !in Sources.IDS && m !in rawSources && SourceNames.RAW.matches(m)) {
            append(m).append(" MOD\n")
        }
        append("deadzone ").append(String.format(java.util.Locale.ROOT, "%.2f", deadzone.coerceIn(0.2f, 0.8f))).append('\n')
        append("ls.invert_y ").append(if (lsInvertY) 1 else 0).append('\n')
        append("rs.invert_y ").append(if (rsInvertY) 1 else 0).append('\n')
        if (m != null) {
            for ((src, k) in modBindings.entries.sortedWith(compareBy(Sources.comparator) { it.key })) {
                if (src == m || !SourceNames.isValid(src)) continue
                append("mod+").append(src).append(' ').append(keyOrNone(k)).append('\n')
            }
        }
        append("wheel_repeat_ms ").append(wheelRepeatMs.coerceIn(60, 400)).append('\n')
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
            modifier = "btn.thumbl",
            modBindings = mapOf(
                "ls.up" to "WHEEL_UP", "ls.down" to "WHEEL_DOWN",
                "ls.left" to "NONE", "ls.right" to "NONE",
            ),
            wheelRepeatMs = 120,
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
