package com.dpad.mgr.core

import android.content.Context
import android.hardware.display.DisplayManager
import android.util.Log
import android.view.Display
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import kotlin.math.roundToInt

/** JSON-file persistence for profiles and per-app assignments (filesDir/dpad.json). */
object Store {
    private const val TAG = "DpadMgr"
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true; encodeDefaults = true }
    private val _data = MutableStateFlow(AppData())
    val data: StateFlow<AppData> get() = _data
    @Volatile private var loaded = false
    private lateinit var file: File

    @Synchronized
    fun init(ctx: Context) {
        if (loaded) return
        file = File(ctx.applicationContext.filesDir, "dpad.json")
        val onDisk = runCatching {
            if (file.exists()) json.decodeFromString<AppData>(file.readText()) else AppData()
        }.getOrElse { e ->
            Log.w(TAG, "store: failed to read ${file.path}: $e; using defaults")
            AppData()
        }.let { d -> if (d.profiles.isEmpty()) d.copy(profiles = Profile.DEFAULTS) else d }
        val rotation = currentRotation(ctx)
        val migrated = onDisk.copy(
            profiles = onDisk.profiles.map(::migrateModifier).map { migrateTouchSpace(it, rotation) }.map(::clampOffsets),
        )
        _data.value = migrated
        loaded = true
        if (migrated != onDisk) persistNow(migrated)
    }

    /** Best-effort default-display rotation, for the panel->display touch-offset migration
     *  below; falls back to ROTATION_0 if unavailable (e.g. very early in service start). */
    private fun currentRotation(ctx: Context): Int = runCatching {
        val dm = ctx.applicationContext.getSystemService(DisplayManager::class.java)
        dm?.getDisplay(Display.DEFAULT_DISPLAY)?.rotation ?: 0
    }.getOrDefault(0)

    /**
     * Migrates a pre-display-space profile: [Profile.touchSpace] == "panel" (the default, so
     * this also catches old JSON that predates the field entirely) means [Profile.touchDx]/[Profile.touchDy]
     * are stored in PANEL units. Converts them to DISPLAY px via [Calibration.rotateDeltaInverse]
     * (the inverse of the old panel-space mapping) at the current display rotation, and flips
     * [Profile.touchSpace] to "display" so this never re-runs. Profiles with the offset disabled
     * and at (0,0) are just flipped to "display" with no conversion (nothing to convert).
     */
    private fun migrateTouchSpace(p: Profile, rotation: Int): Profile {
        if (p.touchSpace != "panel") return p
        if (!p.touchOffsetEnabled && p.touchDx == 0 && p.touchDy == 0) return p.copy(touchSpace = "display")
        val (dx, dy) = Calibration.rotateDeltaInverse(p.touchDx.toFloat(), p.touchDy.toFloat(), rotation)
        Log.i(TAG, "store: migrating '${p.name}' touch offset panel(${p.touchDx},${p.touchDy}) -> display(${dx.roundToInt()},${dy.roundToInt()}) rotation=$rotation")
        return p.copy(touchSpace = "display", touchDx = dx.roundToInt(), touchDy = dy.roundToInt())
    }

    /**
     * Migrates the deprecated `modifier` / `modBindings` fields (old `btn.X MOD` / `mod+src`
     * config syntax) into chord entries (`<modifier>+<src>`) in [Profile.map], then clears them.
     * No code path writes modifier/modBindings after this runs.
     */
    private fun migrateModifier(p: Profile): Profile {
        val m = p.modifier ?: return if (p.modBindings.isEmpty()) p else p.copy(modBindings = emptyMap())
        val merged = p.map.toMutableMap()
        for ((src, target) in p.modBindings) merged["$m+$src"] = target
        return p.copy(map = merged, modifier = null, modBindings = emptyMap())
    }

    /** Hard clamp on touchDx/touchDy (Calibration.MAX_OFFSET, display px); applied on load and on every save. */
    private fun clampOffsets(p: Profile): Profile = p.copy(
        touchDx = p.touchDx.coerceIn(-Calibration.MAX_OFFSET, Calibration.MAX_OFFSET),
        touchDy = p.touchDy.coerceIn(-Calibration.MAX_OFFSET, Calibration.MAX_OFFSET),
    )

    @Synchronized
    fun update(fn: (AppData) -> AppData) {
        val next = fn(_data.value)
        _data.value = next
        persistNow(next)
    }

    private fun persistNow(d: AppData) = runCatching {
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(json.encodeToString(d))
        if (!tmp.renameTo(file)) { file.writeText(json.encodeToString(d)); tmp.delete() }
    }.onFailure { Log.w(TAG, "store: save failed: $it") }

    fun saveProfile(p: Profile, originalName: String? = null) = update { d ->
        val p = clampOffsets(p)
        val list = d.profiles.toMutableList()
        val idx = list.indexOfFirst { it.name == (originalName ?: p.name) }
        if (idx >= 0) list[idx] = p else list.add(p)
        val assigns = if (originalName != null && originalName != p.name)
            d.assignments.mapValues { (_, v) -> if (v == originalName) p.name else v } else d.assignments
        d.copy(profiles = list, assignments = assigns)
    }

    /**
     * Field-level merge: applies [fn] to whatever profile is CURRENTLY stored under [name] (never
     * to a caller-held copy that might be stale) and writes the result back, clamping offsets.
     * A no-op if [name] isn't found (e.g. it was deleted or renamed concurrently). This is the only
     * way editor UI should persist a single-field change -- it can never clobber a concurrent
     * writer's edit to some other field (e.g. CalibrateActivity's touch offset) the way writing a
     * whole locally-held draft Profile back can.
     */
    fun updateProfile(name: String, fn: (Profile) -> Profile) = update { d ->
        val idx = d.profiles.indexOfFirst { it.name == name }
        if (idx < 0) return@update d
        val list = d.profiles.toMutableList()
        list[idx] = clampOffsets(fn(list[idx]))
        d.copy(profiles = list)
    }

    fun deleteProfile(name: String) = update { d ->
        d.copy(profiles = d.profiles.filterNot { it.name == name },
            assignments = d.assignments.filterValues { it != name })
    }

    fun assign(pkg: String, profileName: String?) = update { d ->
        d.copy(assignments = if (profileName == null) d.assignments - pkg else d.assignments + (pkg to profileName))
    }

    fun setAllowQ(v: Boolean) = update { d -> d.copy(allowQ = v) }
}
