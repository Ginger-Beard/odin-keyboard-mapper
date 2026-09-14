package com.dpad.mgr.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.InputChip
import androidx.compose.material3.InputChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusTarget
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dpad.mgr.core.Calibration
import com.dpad.mgr.core.DaemonState
import com.dpad.mgr.core.KeyDef
import com.dpad.mgr.core.Keys
import com.dpad.mgr.core.Profile
import com.dpad.mgr.core.SourceNames
import com.dpad.mgr.core.Sources
import com.dpad.mgr.core.Store
import com.dpad.mgr.svc.DpadService
import com.dpad.mgr.svc.ServiceState
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@Composable
fun ProfilesScreen(modifier: Modifier = Modifier) {
    val data by Store.data.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<Pair<String?, Profile>?>(null) } // originalName, initial draft
    val e = editing
    if (e != null) {
        ProfileEditor(
            original = e.first, initial = e.second,
            existingNames = data.profiles.map { it.name },
            onDone = { editing = null },
            modifier = modifier,
        )
        return
    }
    Column(modifier) {
        Text("Profiles", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp))
        Row(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                modifier = Modifier.heightIn(min = 48.dp),
                onClick = { editing = null to Profile(name = uniqueName("New profile", data.profiles.map { it.name })) },
            ) { Text("New profile") }
        }
        LazyColumn(contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 16.dp)) {
            items(data.profiles, key = { it.name }) { p ->
                val used = data.assignments.count { it.value == p.name }
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp).heightIn(min = 48.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(p.name, style = MaterialTheme.typography.bodyLarge)
                        Text(summary(p), style = MaterialTheme.typography.bodySmall, maxLines = 2)
                        Text("deadzone ${"%.2f".format(p.deadzone)} · used by $used app(s)", style = MaterialTheme.typography.bodySmall)
                    }
                    TextButton(modifier = Modifier.heightIn(min = 48.dp), onClick = { editing = p.name to p }) { Text("Edit") }
                }
                HorizontalDivider()
            }
        }
    }
}

/** "F1←D-pad up, Up←Left stick up" for every key with at least one bound source. */
private fun summary(p: Profile): String {
    val parts = Keys.ALL.mapNotNull { k ->
        val srcs = p.sourcesFor(k.keyName)
        if (srcs.isEmpty()) null else "${k.label}←${srcs.joinToString(", ") { SourceNames.label(it) }}"
    }
    return if (parts.isEmpty()) "no keys mapped" else parts.joinToString(", ")
}

private fun uniqueName(base: String, names: List<String>): String {
    if (base !in names) return base
    var i = 2
    while ("$base $i" in names) i++
    return "$base $i"
}

/** The built-in default for [name], if any — gates "Reset to default" in the header card. */
private fun builtinDefault(name: String?): Profile? = when (name) {
    "OSRS" -> Profile.OSRS
    "WASD" -> Profile.WASD
    else -> null
}

/**
 * Editor for one profile. Every change persists to the Store immediately: this editor never has
 * "unsaved" state, so there is no Save button — only a debounce on text/slider fields to avoid
 * hammering disk + the daemon on every keystroke/drag tick. [original] is the profile name as it
 * stood when this editor session began (or null for a brand-new profile); it is intentionally
 * never mutated so "Reset to default" keeps working after a mid-session rename. All Store lookups
 * instead key off [persistedName] below, which tracks whatever name the draft is currently saved
 * under and is updated in place on every successful save (including renames and Duplicate) --
 * this editor composable is never re-created (no re-keying) for any of that to happen.
 *
 * `draft` is a pure VIEW derived from [Store.data] (the profile currently stored under
 * [persistedName]) -- this editor never holds its own authoritative copy of the profile, so it can
 * never write a stale field back over a concurrent writer's change (notably CalibrateActivity's
 * touch offset). Every edit -- bind/unbind, a switch, a slider, a rename, Duplicate, Reset to
 * default -- persists as a field-level merge onto whatever's currently in the Store, via
 * [Store.updateProfile] (or [Store.saveProfile] for the rename/Duplicate/create cases that need its
 * name-keyed/assignment-remapping behavior), never as a whole-draft overwrite. The only local state
 * is transient: `nameText`/`deadzoneLocal`/`wheelLocal` hold in-progress values for the three
 * debounced fields while their save is still pending, so typing/dragging stays responsive; they're
 * cleared the moment the debounced write lands.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ProfileEditor(
    original: String?, initial: Profile, existingNames: List<String>,
    onDone: () -> Unit, modifier: Modifier = Modifier,
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var persistedName by remember { mutableStateOf(original) } // name draft is currently saved under in the Store, if any
    var binding by remember { mutableStateOf<String?>(null) } // target key name being bound
    var settingPanic by remember { mutableStateOf(false) } // panic-chord LearnDialog open
    var showLetters by remember { mutableStateOf(false) }
    var showSwallowed by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var showWheelWarning by remember { mutableStateOf(false) }
    var showSaved by remember { mutableStateOf(false) }
    var savedFlashJob by remember { mutableStateOf<Job?>(null) }
    val snackbarHostState = remember { SnackbarHostState() }

    // A non-visual focus sink: claims focus once on entry so that later, when LearnDialog (a
    // separate Window) is dismissed, Android restores focus here -- not to the name field, which
    // would otherwise be the first focusable element found by the default "nothing was focused"
    // search, and would drag the scroll position up to it in the process.
    val rootFocusRequester = remember { FocusRequester() }

    // The pure view: whatever's stored under persistedName right now (or `initial`, for the one
    // frame before the brand-new-profile LaunchedEffect below has persisted it), with transient
    // local overrides layered on top for the three debounced fields while their save is pending.
    val storeData by Store.data.collectAsStateWithLifecycle()
    val stored = storeData.profile(persistedName) ?: initial
    var nameText by remember { mutableStateOf<String?>(null) }
    var nameJob by remember { mutableStateOf<Job?>(null) }
    var deadzoneLocal by remember { mutableStateOf<Float?>(null) }
    var deadzoneJob by remember { mutableStateOf<Job?>(null) }
    var wheelLocal by remember { mutableStateOf<Int?>(null) }
    var wheelJob by remember { mutableStateOf<Job?>(null) }
    val draft = stored.copy(
        name = nameText ?: stored.name,
        deadzone = deadzoneLocal ?: stored.deadzone,
        wheelRepeatMs = wheelLocal ?: stored.wheelRepeatMs,
    )
    val nameClash = draft.name.isBlank() || (draft.name != persistedName && draft.name in existingNames)

    // Stylus offset, in SCREEN pixels as perceived in landscape: the stored profile only ever
    // holds PANEL-space touchDx/touchDy, so display/edit values are the panel value rotated back
    // into screen space via Calibration.rotateDeltaInverse (the inverse of the same rotation
    // Calibration.rotateDelta/CalibrateActivity use to go the other way). Rotation is read once
    // from this Activity's display; a composable context can't otherwise learn it, so this mirrors
    // CalibrateActivity's `activity.display?.rotation` read, with ROTATION_90 (this device's
    // landscape) as the fallback if display is unavailable.
    val touchRotation = remember { ctx.display?.rotation ?: Calibration.ROTATION_90 }
    val (storedScreenXF, storedScreenYF) = Calibration.rotateDeltaInverse(stored.touchDx.toFloat(), stored.touchDy.toFloat(), touchRotation)
    val storedScreenX = storedScreenXF.roundToInt()
    val storedScreenY = storedScreenYF.roundToInt()
    var touchXText by remember { mutableStateOf<String?>(null) }
    var touchXJob by remember { mutableStateOf<Job?>(null) }
    var touchYText by remember { mutableStateOf<String?>(null) }
    var touchYJob by remember { mutableStateOf<Job?>(null) }

    fun flashSaved() {
        showSaved = true
        savedFlashJob?.cancel()
        savedFlashJob = scope.launch { delay(1000); showSaved = false }
    }

    /** Immediate field-level merge onto the CURRENTLY stored profile (never the draft) via
     *  [Store.updateProfile] -- used for every non-debounced edit: bind/unbind, invert switches,
     *  and (from the Stylus card only) the touch-offset Enabled switch / Disable button. If [live]
     *  and this profile is the one currently Running in the daemon, nudges it live via
     *  updateConfigLive; that path only actually live-reloads touch.offset (via SIGUSR1), so it's
     *  only passed true from the Stylus card -- binding/invert/deadzone/wheel changes rely on
     *  DpadService's own Store.data collector noticing the config text changed and restarting the
     *  daemon through the Supervisor. */
    fun changeNow(live: Boolean = false, mutate: (Profile) -> Profile) {
        val name = persistedName ?: return
        Store.updateProfile(name, mutate)
        flashSaved()
        if (live) {
            val d = ServiceState.daemon.value
            val activeProfile = (d as? DaemonState.Running)?.profile ?: (d as? DaemonState.Testing)?.profile
            if (activeProfile == name) {
                DpadService.send(ctx, DpadService.ACTION_UPDATE_CONFIG_LIVE, profile = name)
            }
        }
    }

    /** Commits a debounced rename: builds the renamed profile from whatever's CURRENTLY in the
     *  Store (not the draft), skipping a blank/duplicate name -- same guard the old Save button's
     *  `enabled` used. Renames go through [Store.saveProfile] (not [Store.updateProfile]) since a
     *  rename also has to remap [AppData.assignments] entries pointing at the old name. */
    fun commitName(text: String) {
        val name = persistedName ?: return
        val cur = Store.data.value.profile(name) ?: return
        val renamed = cur.copy(name = text)
        if (renamed.name.isBlank() || (renamed.name != name && renamed.name in existingNames)) return
        Store.saveProfile(renamed, name)
        persistedName = renamed.name
        nameText = null
        flashSaved()
    }

    fun commitDeadzone(v: Float) {
        val name = persistedName ?: return
        Store.updateProfile(name) { p -> p.copy(deadzone = v) }
        deadzoneLocal = null
        flashSaved()
    }

    fun commitWheel(v: Int) {
        val name = persistedName ?: return
        Store.updateProfile(name) { p -> p.copy(wheelRepeatMs = v) }
        wheelLocal = null
        flashSaved()
    }

    /** Reads the CURRENTLY stored profile's touch offset back out as a screen-space (horizontal,
     *  vertical) pair, same rotation as [storedScreenX]/[storedScreenY] above but read fresh --
     *  used so that committing one axis while the other has its own pending debounce still writes
     *  the other axis's up-to-date value instead of a stale closure-captured one. */
    fun freshScreenXY(): Pair<Int, Int> {
        val name = persistedName ?: return 0 to 0
        val p = Store.data.value.profile(name) ?: return 0 to 0
        val (sx, sy) = Calibration.rotateDeltaInverse(p.touchDx.toFloat(), p.touchDy.toFloat(), touchRotation)
        return sx.roundToInt() to sy.roundToInt()
    }

    /** Rotates a SCREEN-space (horizontal, vertical) pair back to PANEL space, clamps to
     *  Calibration.MAX_OFFSET (rotateDelta only swaps/flips-sign components, so clamping either
     *  space by the same bound is equivalent), and writes+live-pushes it exactly like the Enabled
     *  switch does. */
    fun writeTouchOffset(sx: Int, sy: Int) {
        val (pdxF, pdyF) = Calibration.rotateDelta(sx.toFloat(), sy.toFloat(), touchRotation)
        val pdx = pdxF.roundToInt().coerceIn(-Calibration.MAX_OFFSET, Calibration.MAX_OFFSET)
        val pdy = pdyF.roundToInt().coerceIn(-Calibration.MAX_OFFSET, Calibration.MAX_OFFSET)
        changeNow(live = true) { d -> d.copy(touchDx = pdx, touchDy = pdy) }
    }

    fun commitTouchX(sx: Int) {
        val (_, freshY) = freshScreenXY()
        val sy = touchYText?.toIntOrNull() ?: freshY
        writeTouchOffset(sx, sy)
        touchXText = null
    }

    fun commitTouchY(sy: Int) {
        val (freshX, _) = freshScreenXY()
        val sx = touchXText?.toIntOrNull() ?: freshX
        writeTouchOffset(sx, sy)
        touchYText = null
    }

    /** Cancels and immediately runs any pending debounced field commit. Called before Calibrate
     *  and Duplicate (so they read a fully up-to-date stored profile) and on ON_PAUSE (so a pending
     *  edit can't land AFTER CalibrateActivity underneath has written its own fields, per the old
     *  race -- see the class doc comment). */
    fun flushPending() {
        nameJob?.cancel(); nameJob = null
        nameText?.let { commitName(it) }
        deadzoneJob?.cancel(); deadzoneJob = null
        deadzoneLocal?.let { commitDeadzone(it) }
        wheelJob?.cancel(); wheelJob = null
        wheelLocal?.let { commitWheel(it) }
        touchXJob?.cancel(); touchXJob = null
        touchXText?.toIntOrNull()?.let { commitTouchX(it) }
        touchYJob?.cancel(); touchYJob = null
        touchYText?.toIntOrNull()?.let { commitTouchY(it) }
    }

    // Brand-new profile: persist it as soon as the editor opens so it exists in the Store (and
    // Delete/Duplicate/rename bookkeeping below have something to key off) right away. This is a
    // whole-profile create (there's nothing stored yet to merge onto), not an edit.
    LaunchedEffect(Unit) {
        if (persistedName == null) {
            Store.saveProfile(initial)
            persistedName = initial.name
            flashSaved()
        }
        rootFocusRequester.requestFocus()
    }

    // Flush pending debounced edits before this editor is paused (e.g. CalibrateActivity is about
    // to start on top of it) so they can't land later and overwrite whatever Calibrate writes.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_PAUSE) flushPending() }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Box(modifier.fillMaxSize().focusRequester(rootFocusRequester).focusTarget()) {
        Column(
            Modifier.fillMaxWidth().padding(12.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // ---- Header card ----
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        draft.name,
                        { text ->
                            nameText = text
                            nameJob?.cancel()
                            nameJob = scope.launch { delay(300); commitName(text) }
                        },
                        Modifier.fillMaxWidth(),
                        label = { Text("Profile name") }, isError = nameClash, singleLine = true,
                    )
                    AnimatedVisibility(visible = showSaved, exit = fadeOut()) {
                        Text("Saved", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        val def = builtinDefault(original)
                        if (def != null) {
                            OutlinedButton(modifier = Modifier.heightIn(min = 48.dp), onClick = { changeNow { def } }) {
                                Text("Reset to default")
                            }
                        }
                        OutlinedButton(
                            modifier = Modifier.heightIn(min = 48.dp),
                            onClick = {
                                flushPending()
                                val base = persistedName?.let { Store.data.value.profile(it) } ?: draft
                                val dup = base.copy(name = uniqueName("${base.name} copy", existingNames))
                                Store.saveProfile(dup)
                                persistedName = dup.name
                                flashSaved()
                            },
                        ) { Text("Duplicate") }
                        if (persistedName != null) {
                            TextButton(
                                modifier = Modifier.heightIn(min = 48.dp),
                                enabled = existingNames.size > 1,
                                onClick = { confirmDelete = true },
                            ) { Text("Delete") }
                        }
                    }
                }
            }

            // ---- Keys section ----
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Keys", style = MaterialTheme.typography.titleMedium)
                    WheelWarningLabel(onClick = { showWheelWarning = true })
                    Text(
                        "To bind a combo, hold a button while pressing the control during Bind.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text("Game keys", style = MaterialTheme.typography.titleSmall)
                    for (k in Keys.PRIMARY) {
                        KeyRow(
                            k, draft.sourcesFor(k.keyName),
                            onUnbind = { src -> changeNow { d -> d.unbind(src) } },
                            onBind = { binding = k.keyName },
                            onWarningClick = if (k.keyName in Keys.WHEEL_TARGETS) ({ showWheelWarning = true }) else null,
                        )
                    }
                    TextButton(modifier = Modifier.heightIn(min = 48.dp), onClick = { showLetters = !showLetters }) {
                        Text(if (showLetters) "Hide letters" else "Letters (A–Z)…")
                    }
                    if (showLetters) {
                        Text("Letters", style = MaterialTheme.typography.titleSmall)
                        for (k in Keys.OTHER) {
                            KeyRow(
                                k, draft.sourcesFor(k.keyName),
                                onUnbind = { src -> changeNow { d -> d.unbind(src) } },
                                onBind = { binding = k.keyName },
                                onWarningClick = if (k.keyName in Keys.WHEEL_TARGETS) ({ showWheelWarning = true }) else null,
                            )
                        }
                    }
                }
            }

            // ---- Sticks section ----
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    StickRow(
                        "Left stick", draft.lsInvertY, { v -> changeNow { d -> d.copy(lsInvertY = v) } },
                        draft.lsInvertX, { v -> changeNow { d -> d.copy(lsInvertX = v) } },
                    )
                    StickRow(
                        "Right stick", draft.rsInvertY, { v -> changeNow { d -> d.copy(rsInvertY = v) } },
                        draft.rsInvertX, { v -> changeNow { d -> d.copy(rsInvertX = v) } },
                    )
                    Text("Deadzone: ${"%.2f".format(draft.deadzone)}", style = MaterialTheme.typography.bodyMedium)
                    Slider(
                        value = draft.deadzone,
                        onValueChange = { v ->
                            deadzoneLocal = v
                            deadzoneJob?.cancel()
                            deadzoneJob = scope.launch { delay(300); commitDeadzone(v) }
                        },
                        valueRange = 0.2f..0.8f, steps = 11,
                    )
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Wheel repeat: ${draft.wheelRepeatMs} ms", style = MaterialTheme.typography.bodyMedium)
                        WheelWarningLabel(onClick = { showWheelWarning = true })
                    }
                    Slider(
                        value = draft.wheelRepeatMs.toFloat(),
                        onValueChange = { v ->
                            val iv = v.toInt()
                            wheelLocal = iv
                            wheelJob?.cancel()
                            wheelJob = scope.launch { delay(300); commitWheel(iv) }
                        },
                        valueRange = 60f..400f, steps = 32,
                    )
                }
            }

            // ---- Panic chord card ----
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Panic chord", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Hold this to stop mapping from inside a game: 1 second pauses, 4 seconds restarts the daemon. Required before enabling the stylus offset.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        val chord = draft.panicChord
                        if (chord != null) {
                            InputChip(
                                selected = false,
                                onClick = {
                                    changeNow { d -> d.copy(panicChord = null) }
                                    if (draft.touchOffsetEnabled) {
                                        changeNow(live = true) { d -> d.copy(touchOffsetEnabled = false) }
                                        scope.launch { snackbarHostState.showSnackbar("Stylus offset turned off: no panic chord") }
                                    }
                                },
                                label = { Text(SourceNames.chordLabel(chord)) },
                                trailingIcon = { Icon(Icons.Default.Close, contentDescription = "Clear panic chord", Modifier.size(InputChipDefaults.IconSize)) },
                            )
                        } else {
                            Text("Not set", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                        }
                        Spacer(Modifier.weight(1f))
                        OutlinedButton(modifier = Modifier.heightIn(min = 48.dp), onClick = { settingPanic = true }) { Text("Set by pressing…") }
                    }
                }
            }

            // ---- Stylus offset card ----
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Stylus offset", style = MaterialTheme.typography.titleMedium)
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("Enabled", Modifier.weight(1f))
                        Switch(
                            checked = draft.touchOffsetEnabled,
                            enabled = draft.panicChord != null,
                            onCheckedChange = { v -> changeNow(live = true) { d -> d.copy(touchOffsetEnabled = v) } },
                        )
                    }
                    if (draft.panicChord == null) {
                        Text("Set a panic chord first", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                    Text(
                        "Only active while the assigned game is in front. To turn it off from inside the game, hold " +
                            (draft.panicChord?.let { SourceNames.chordLabel(it) } ?: "the panic chord") +
                            " for 1 second. Turning Enabled off keeps these values, ready to restore.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text("Offset, as seen in landscape", style = MaterialTheme.typography.bodySmall)
                    OffsetRow(
                        "Horizontal", touchXText ?: storedScreenX.toString(),
                        onStep = { delta ->
                            touchXJob?.cancel(); touchXJob = null
                            val cur = touchXText?.toIntOrNull() ?: storedScreenX
                            commitTouchX((cur + delta).coerceIn(-Calibration.MAX_OFFSET, Calibration.MAX_OFFSET))
                        },
                        onTextChange = { text ->
                            touchXText = text
                            touchXJob?.cancel()
                            touchXJob = scope.launch { delay(300); text.toIntOrNull()?.let { commitTouchX(it) } }
                        },
                    )
                    OffsetRow(
                        "Vertical", touchYText ?: storedScreenY.toString(),
                        onStep = { delta ->
                            touchYJob?.cancel(); touchYJob = null
                            val cur = touchYText?.toIntOrNull() ?: storedScreenY
                            commitTouchY((cur + delta).coerceIn(-Calibration.MAX_OFFSET, Calibration.MAX_OFFSET))
                        },
                        onTextChange = { text ->
                            touchYText = text
                            touchYJob?.cancel()
                            touchYJob = scope.launch { delay(300); text.toIntOrNull()?.let { commitTouchY(it) } }
                        },
                    )
                    OutlinedButton(
                        modifier = Modifier.heightIn(min = 48.dp),
                        enabled = draft.panicChord != null,
                        onClick = {
                            // Flush any pending debounced edit first so calibration (a separate
                            // activity) has a fully up-to-date saved profile to read and write
                            // touch offset fields on.
                            flushPending()
                            ctx.startActivity(Intent(ctx, CalibrateActivity::class.java).putExtra(CalibrateActivity.EXTRA_PROFILE, draft.name))
                        },
                    ) { Text("Calibrate…") }
                }
            }

            // ---- Swallowed controls footer ----
            val swallowed = Sources.ALL.filter { draft.key(it.id) == Keys.NONE }
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(modifier = Modifier.heightIn(min = 48.dp), onClick = { showSwallowed = !showSwallowed }) {
                        Text(if (showSwallowed) "Hide swallowed controls (${swallowed.size})" else "Swallowed controls (${swallowed.size})…")
                    }
                    if (showSwallowed) {
                        Text("These do nothing while this profile is active.", style = MaterialTheme.typography.bodySmall)
                        Text(
                            if (swallowed.isEmpty()) "None" else swallowed.joinToString(", ") { it.label },
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }

            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(modifier = Modifier.heightIn(min = 48.dp), onClick = onDone) { Text("Done") }
            }
            Spacer(Modifier.height(24.dp))
        }

        SnackbarHost(snackbarHostState, Modifier.align(Alignment.BottomCenter))
    }

    binding?.let { keyName ->
        LearnDialog(
            title = Keys.label(keyName),
            onLearned = { src ->
                val oldKey = draft.map[src]
                changeNow { d -> d.bind(src, keyName) }
                binding = null
                if (oldKey != null && oldKey != keyName) {
                    val msg = "Moved from ${Keys.label(oldKey)}"
                    scope.launch { snackbarHostState.showSnackbar(msg) }
                }
            },
            onDismiss = { binding = null },
        )
    }

    if (settingPanic) {
        LearnDialog(
            title = "panic chord",
            onLearned = { src ->
                settingPanic = false
                if (Sources.isValidPanicChord(src)) {
                    changeNow { d -> d.copy(panicChord = src) }
                } else {
                    scope.launch { snackbarHostState.showSnackbar("Use buttons only") }
                }
            },
            onDismiss = { settingPanic = false },
        )
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete profile?") },
            text = { Text("Delete \"${draft.name}\"? This can't be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    persistedName?.let { Store.deleteProfile(it) }
                    onDone()
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }

    if (showWheelWarning) {
        WheelWarningDialog(onDismiss = { showWheelWarning = false })
    }
}

/** Red, clickable "Warning: OSRS ban risk" label; opens [WheelWarningDialog] when tapped. */
@Composable
private fun WheelWarningLabel(onClick: () -> Unit) {
    Text(
        "Warning: OSRS ban risk",
        color = MaterialTheme.colorScheme.error,
        style = MaterialTheme.typography.bodySmall,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.clickable(onClick = onClick),
    )
}

/** Explains why wheel (scroll) bindings carry OSRS ban risk: fixed pointer position on every scroll event. */
@Composable
private fun WheelWarningDialog(onDismiss: () -> Unit) {
    val screenHeight = LocalConfiguration.current.screenHeightDp.dp
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Mouse wheel and OSRS") },
        text = {
            Column(Modifier.heightIn(max = screenHeight * 0.6f).verticalScroll(rememberScrollState())) {
                Text(
                    "Wheel bindings reach the game as mouse scroll events. Android attaches the mouse " +
                        "pointer's position to each one, and this app never moves the pointer, so every " +
                        "zoom notch arrives at the same coordinates, every session. Jagex bans for " +
                        "automation by looking for input that is too regular to be human. Scroll events " +
                        "are not clicks and are not the usual trigger, but a fixed position is not what a " +
                        "real mouse produces, and the mobile client's checks are not public. Keyboard " +
                        "bindings carry no position and are safe. For Old School RuneScape we recommend " +
                        "no wheel bindings; use pinch zoom instead. Use at your own risk."
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("OK") } },
    )
}

/** One target key: label, chips for each bound source (with × to unbind), and a "+ Bind" button.
 *  [onWarningClick], when non-null, renders a red clickable "Warning: OSRS ban risk" label next to
 *  the key name (used for the wheel targets). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun KeyRow(
    k: KeyDef, sources: List<String>, onUnbind: (String) -> Unit, onBind: () -> Unit,
    onWarningClick: (() -> Unit)? = null,
) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp).heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(k.label, Modifier.width(88.dp), style = MaterialTheme.typography.bodyMedium)
        if (onWarningClick != null) {
            WheelWarningLabel(onClick = onWarningClick)
            Spacer(Modifier.width(4.dp))
        }
        FlowRow(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            if (sources.isEmpty()) Text("—", style = MaterialTheme.typography.bodySmall)
            for (s in sources) SourceChip(s) { onUnbind(s) }
        }
        Spacer(Modifier.width(4.dp))
        OutlinedButton(modifier = Modifier.heightIn(min = 48.dp), onClick = onBind) { Text("+ Bind") }
    }
    HorizontalDivider()
}

/** A bound source chip; chords are visibly distinct because [SourceNames.label] prefixes them "Hold …". */
@Composable
private fun SourceChip(src: String, onRemove: () -> Unit) {
    InputChip(
        selected = SourceNames.isChord(src),
        onClick = onRemove,
        label = { Text(SourceNames.label(src)) },
        trailingIcon = { Icon(Icons.Default.Close, contentDescription = "Unbind", Modifier.size(InputChipDefaults.IconSize)) },
    )
}

/** One stylus-offset axis: label, a −/+ 48dp stepper (writes immediately), and a numeric text
 *  field for the value in between (debounced by the caller like other text fields). */
@Composable
private fun OffsetRow(label: String, text: String, onStep: (Int) -> Unit, onTextChange: (String) -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(label, Modifier.width(88.dp), style = MaterialTheme.typography.bodyMedium)
        OffsetStepButton("−", onClick = { onStep(-1) })
        OutlinedTextField(
            value = text,
            onValueChange = onTextChange,
            modifier = Modifier.width(84.dp),
            singleLine = true,
        )
        OffsetStepButton("+", onClick = { onStep(1) })
    }
}

@Composable
private fun OffsetStepButton(label: String, onClick: () -> Unit) {
    OutlinedButton(
        modifier = Modifier.size(48.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
        onClick = onClick,
    ) { Text(label, style = MaterialTheme.typography.titleMedium) }
}

/** Compact per-stick row: label plus "Invert vertical" / "Invert horizontal" switches. */
@Composable
private fun StickRow(label: String, invertY: Boolean, onInvertY: (Boolean) -> Unit, invertX: Boolean, onInvertX: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.width(88.dp), style = MaterialTheme.typography.bodyMedium)
        Column(Modifier.weight(1f)) {
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Invert vertical", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                Switch(checked = invertY, onCheckedChange = onInvertY)
            }
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Invert horizontal", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                Switch(checked = invertX, onCheckedChange = onInvertX)
            }
        }
    }
}
