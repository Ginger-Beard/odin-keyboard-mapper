package com.dpad.mgr.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.surfaceColorAtElevation
import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dpad.mgr.core.Calibration
import com.dpad.mgr.core.DaemonState
import com.dpad.mgr.core.KeyDef
import com.dpad.mgr.core.Keys
import com.dpad.mgr.core.Learn
import com.dpad.mgr.core.Profile
import com.dpad.mgr.core.SourceNames
import com.dpad.mgr.core.Sources
import com.dpad.mgr.core.Store
import com.dpad.mgr.svc.DpadService
import com.dpad.mgr.svc.ServiceState
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun ProfilesScreen(modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
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
                onClick = { editing = null to Profile(name = uniqueName("New profile", data.profiles.map { it.name }), touchSpace = "display") },
            ) { Text("New profile") }
        }
        LazyColumn(contentPadding = PaddingValues(bottom = 16.dp)) {
            items(data.profiles, key = { it.name }) { p ->
                val used = data.assignments.count { it.value == p.name }
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp).heightIn(min = 48.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(p.name, style = MaterialTheme.typography.bodyLarge)
                        val assignedPkgs = data.assignments.filterValues { it == p.name }.keys
                        if (assignedPkgs.isEmpty()) {
                            Text(
                                "Not assigned to any app", style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        } else {
                            Text(
                                "Assigned to: ${assignedPkgs.joinToString(", ") { appLabel(ctx, it) }}",
                                style = MaterialTheme.typography.bodySmall, maxLines = 2,
                            )
                        }
                    }
                    TextButton(modifier = Modifier.heightIn(min = 48.dp), onClick = { editing = p.name to p }) { Text("Edit") }
                }
                HorizontalDivider()
            }
        }
    }
}

/** Best-effort app label for a package, falling back to the package name. */
private fun appLabel(ctx: Context, pkg: String): String =
    runCatching {
        val pm = ctx.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    }.getOrDefault(pkg)

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

/** A just-learned bind ([src], plain control or chord) that's already bound to [oldKey] in this
 *  profile, pending the user's choice to move it to [newKey] or leave it alone. */
private data class BindConflict(val src: String, val oldKey: String, val newKey: String)

/** A just-learned panic [chord] where at least one of its buttons already has a key binding in
 *  this profile; [message] lists which. */
private data class PanicConflict(val chord: String, val message: String)

/**
 * Editor for one profile. Every change persists to the Store immediately: this editor never has
 * "unsaved" state, so there is no Save button — only a debounce on text fields to avoid hammering
 * disk + the daemon on every keystroke. Deadzone and wheel repeat are steppers (not sliders), so
 * they commit on every tap with no debounce -- see [stepDeadzone]/[stepWheel]. [original] is the
 * profile name as it stood when this editor session began (or null for a brand-new profile); it is
 * intentionally never mutated so "Reset to default" keeps working after a mid-session rename. All
 * Store lookups instead key off [persistedName] below, which tracks whatever name the draft is
 * currently saved under and is updated in place on every successful save (including renames and
 * Duplicate) -- this editor composable is never re-created (no re-keying) for any of that to
 * happen.
 *
 * `draft` is a pure VIEW derived from [Store.data] (the profile currently stored under
 * [persistedName]) -- this editor never holds its own authoritative copy of the profile, so it can
 * never write a stale field back over a concurrent writer's change (notably CalibrateActivity's
 * touch offset). Every edit -- bind/unbind, a switch, a stepper, a rename, Duplicate, Reset to
 * default -- persists as a field-level merge onto whatever's currently in the Store, via
 * [Store.updateProfile] (or [Store.saveProfile] for the rename/Duplicate/create cases that need its
 * name-keyed/assignment-remapping behavior), never as a whole-draft overwrite. The only local state
 * is transient: `nameText`/`touchXText`/`touchYText` hold in-progress values for the three
 * debounced fields while their save is still pending, so typing stays responsive; they're cleared
 * the moment the debounced write lands.
 *
 * The body below is a [LazyColumn] (not a scrolled [Column]) so the Keys card's often-huge
 * "Unassigned" list is only composed/laid out near the viewport, and each row/section is its own
 * keyed item so an edit to one row's data doesn't force every other row to recompose -- both of
 * which is what made scrolling stutter (and made it easy to snag the deadzone/wheel sliders by
 * accident) before this was a plain scrolled Column of everything.
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
    var showUnassigned by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var showWheelWarning by remember { mutableStateOf(false) }
    var showAllowQDialog by remember { mutableStateOf(false) }
    var bindConflict by remember { mutableStateOf<BindConflict?>(null) } // pending rebind awaiting "Move"/"Cancel"
    var panicConflict by remember { mutableStateOf<PanicConflict?>(null) } // pending panic chord awaiting "Use anyway"/"Cancel"
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
    val draft = stored.copy(name = nameText ?: stored.name)
    val nameClash = draft.name.isBlank() || (draft.name != persistedName && draft.name in existingNames)
    val listState = rememberSaveable(saver = LazyListState.Saver) { LazyListState() }

    // Stylus offset, in DISPLAY pixels as seen on screen in the game's orientation: the stored
    // profile holds touchDx/touchDy directly in this space (see Profile.touchSpace / Store's
    // panel->display migration), so no rotation mapping is needed here any more.
    val storedScreenX = stored.touchDx
    val storedScreenY = stored.touchDy
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

    /** Steppers commit immediately (no debounce): each tap re-reads the CURRENTLY stored value
     *  inside the mutate lambda so rapid taps never race against a stale closed-over value. */
    fun stepDeadzone(delta: Float) {
        changeNow { d -> d.copy(deadzone = (d.deadzone + delta).coerceIn(0.2f, 0.8f)) }
    }

    fun stepWheel(delta: Int) {
        changeNow { d -> d.copy(wheelRepeatMs = (d.wheelRepeatMs + delta).coerceIn(60, 400)) }
    }

    /** Reads the CURRENTLY stored profile's touch offset back out as a display-space (horizontal,
     *  vertical) pair, read fresh -- used so that committing one axis while the other has its own
     *  pending debounce still writes the other axis's up-to-date value instead of a stale
     *  closure-captured one. */
    fun freshScreenXY(): Pair<Int, Int> {
        val name = persistedName ?: return 0 to 0
        val p = Store.data.value.profile(name) ?: return 0 to 0
        return p.touchDx to p.touchDy
    }

    /** Clamps a display-space (horizontal, vertical) pair to Calibration.MAX_OFFSET and
     *  writes+live-pushes it exactly like the Enabled switch does. */
    fun writeTouchOffset(sx: Int, sy: Int) {
        val dx = sx.coerceIn(-Calibration.MAX_OFFSET, Calibration.MAX_OFFSET)
        val dy = sy.coerceIn(-Calibration.MAX_OFFSET, Calibration.MAX_OFFSET)
        changeNow(live = true) { d -> d.copy(touchDx = dx, touchDy = dy) }
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

    // Keys section rows/groups: computed once per recomposition of this whole editor (cheap --
    // linear scans over Keys.ALL, ~150 entries) and handed to the LazyColumn below as item lists.
    // Per-row *content* (a row's bound sources, its callbacks) is still derived independently
    // inside each item so an edit to one row doesn't invalidate its siblings -- see the per-row
    // remember(...)/derivedStateOf blocks below.
    val assignedKeys = Keys.ALL.filter { draft.sourcesFor(it.keyName).isNotEmpty() }
    val assignedNames = assignedKeys.map { it.keyName }.toSet()
    val unassignedCount = Keys.ALL.count { it.keyName !in assignedNames }
    val groupsWithUnassigned = Keys.GROUPS.mapNotNull { g ->
        val ks = g.keys.filter { it.keyName !in assignedNames }
        if (ks.isEmpty()) null else g to ks
    }
    val toggleIsLastKeysItem = !showUnassigned || groupsWithUnassigned.isEmpty()

    Box(modifier.fillMaxSize().focusRequester(rootFocusRequester).focusTarget()) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(bottom = 24.dp),
        ) {
            // ---- Header card ----
            item(key = "header") {
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
            }

            // ---- Keys section: header, then one item per bound key row, then the Unassigned
            // toggle, then (when expanded) one item per key group with any unassigned keys. All
            // share a background so they still read as one continuous card. ----
            item(key = "keysHeader") {
                Column(
                    Modifier.keysCardSection(top = true).padding(top = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text("Keys", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "To bind a combo, hold a button while pressing the control during Bind.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    if (assignedKeys.isEmpty()) {
                        Spacer(Modifier.height(4.dp))
                        Text("No keys mapped yet.", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            items(assignedKeys, key = { "keyrow:${it.keyName}" }) { k ->
                val sourcesState = remember(k.keyName) {
                    derivedStateOf { (storeData.profile(persistedName) ?: initial).sourcesFor(k.keyName) }
                }
                val sources by sourcesState
                val onUnbind = remember(k.keyName) { { src: String -> changeNow { d -> d.unbind(src) } } }
                val onBind = remember(k.keyName) { { binding = k.keyName } }
                val onWarningClick = remember(k.keyName) {
                    if (k.keyName in Keys.WHEEL_TARGETS) ({ showWheelWarning = true }) else null
                }
                Column(Modifier.keysCardSection()) {
                    KeyRow(k, sources, onUnbind, onBind, onWarningClick)
                }
            }
            item(key = "unassignedToggle") {
                Column(Modifier.keysCardSection(bottom = toggleIsLastKeysItem)) {
                    TextButton(modifier = Modifier.heightIn(min = 48.dp), onClick = { showUnassigned = !showUnassigned }) {
                        Text(if (showUnassigned) "Hide unassigned" else "Unassigned ($unassignedCount)")
                    }
                }
            }
            if (showUnassigned) {
                itemsIndexed(groupsWithUnassigned, key = { _, (g, _) -> "unassignedGroup:${g.label}" }) { idx, (group, keys) ->
                    Column(
                        Modifier.keysCardSection(bottom = idx == groupsWithUnassigned.lastIndex)
                            .padding(bottom = if (idx == groupsWithUnassigned.lastIndex) 16.dp else 0.dp),
                    ) {
                        Text(group.label, style = MaterialTheme.typography.titleSmall)
                        if (group.label == "Controller buttons") {
                            Text(
                                "Sends real controller button presses from the virtual device (Android sees it as a gamepad too).",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        for (k in keys) {
                            val sourcesState = remember(k.keyName) {
                                derivedStateOf { (storeData.profile(persistedName) ?: initial).sourcesFor(k.keyName) }
                            }
                            val sources by sourcesState
                            val onUnbind = remember(k.keyName) { { src: String -> changeNow { d -> d.unbind(src) } } }
                            val onBind = remember(k.keyName) { { binding = k.keyName } }
                            val onWarningClick = remember(k.keyName) {
                                if (k.keyName in Keys.WHEEL_TARGETS) ({ showWheelWarning = true }) else null
                            }
                            val forceEnabled = k.keyName == "KEY_Q" && storeData.allowQ
                            val onAllowClick = if (k.keyName == "KEY_Q" && !storeData.allowQ) ({ showAllowQDialog = true }) else null
                            KeyRow(k, sources, onUnbind, onBind, onWarningClick, forceEnabled, onAllowClick)
                        }
                    }
                }
            }

            // ---- Sticks section ----
            item(key = "sticks") {
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
                        StepperRow(
                            "Deadzone", "%.2f".format(draft.deadzone),
                            onDec = { stepDeadzone(-0.05f) }, onInc = { stepDeadzone(0.05f) },
                            decEnabled = draft.deadzone > 0.2f, incEnabled = draft.deadzone < 0.8f,
                        )
                        StepperRow(
                            "Wheel repeat", "${draft.wheelRepeatMs} ms",
                            onDec = { stepWheel(-10) }, onInc = { stepWheel(10) },
                            decEnabled = draft.wheelRepeatMs > 60, incEnabled = draft.wheelRepeatMs < 400,
                        )
                    }
                }
            }

            // ---- Panic chord card ----
            item(key = "panic") {
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
            }

            // ---- Stylus offset card ----
            item(key = "stylus") {
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
            }

            item(key = "done") {
                Column {
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(modifier = Modifier.heightIn(min = 48.dp), onClick = onDone) { Text("Done") }
                    }
                }
            }
        }

        SnackbarHost(snackbarHostState, Modifier.align(Alignment.BottomCenter))
    }

    binding?.let { keyName ->
        LearnDialog(
            title = Keys.label(keyName),
            onLearned = { src ->
                val oldKey = draft.map[src]
                binding = null
                if (oldKey != null && oldKey != keyName) {
                    bindConflict = BindConflict(src, oldKey, keyName)
                } else {
                    changeNow { d -> d.bind(src, keyName) }
                }
            },
            onDismiss = { binding = null },
        )
    }

    if (settingPanic) {
        LearnDialog(
            title = "panic chord",
            instructions = "Press and hold the button or buttons you want as the panic chord, then release.",
            learn = Learn::runLearnChord,
            onLearned = { src ->
                settingPanic = false
                if (Sources.isValidPanicChord(src)) {
                    val conflicts = src.split('+').mapNotNull { part -> draft.map[part]?.let { part to it } }
                    if (conflicts.isEmpty()) {
                        changeNow { d -> d.copy(panicChord = src) }
                    } else {
                        val desc = conflicts.joinToString("; ") { (part, key) -> "${SourceNames.label(part)} is bound to ${Keys.label(key)}" }
                        panicConflict = PanicConflict(src, "$desc; pressing it as part of the panic chord will still send that key. Use it anyway?")
                    }
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

    bindConflict?.let { c ->
        AlertDialog(
            onDismissRequest = { bindConflict = null },
            title = { Text("Already bound") },
            text = { Text("${SourceNames.label(c.src)} is already bound to ${Keys.label(c.oldKey)}. Move it to ${Keys.label(c.newKey)}?") },
            confirmButton = {
                TextButton(onClick = {
                    changeNow { d -> d.bind(c.src, c.newKey) }
                    bindConflict = null
                }) { Text("Move") }
            },
            dismissButton = { TextButton(onClick = { bindConflict = null }) { Text("Cancel") } },
        )
    }

    panicConflict?.let { c ->
        AlertDialog(
            onDismissRequest = { panicConflict = null },
            title = { Text("Panic chord warning") },
            text = { Text(c.message) },
            confirmButton = {
                TextButton(onClick = {
                    changeNow { d -> d.copy(panicChord = c.chord) }
                    panicConflict = null
                }) { Text("Use anyway") }
            },
            dismissButton = { TextButton(onClick = { panicConflict = null }) { Text("Cancel") } },
        )
    }

    if (showWheelWarning) {
        WheelWarningDialog(onDismiss = { showWheelWarning = false })
    }

    if (showAllowQDialog) {
        AlertDialog(
            onDismissRequest = { showAllowQDialog = false },
            title = { Text("Allow the Q key") },
            text = {
                Text(
                    "Binding Q makes Android treat this app's virtual keyboard as a full keyboard, " +
                        "which normally hides the on-screen keyboard everywhere while the app runs. " +
                        "To keep the on-screen keyboard working, Android's \"Use on-screen keyboard\" " +
                        "option under Settings > System > Languages & input > Physical keyboard must " +
                        "be on. Allowing Q turns that option on for you and restarts the mapping " +
                        "daemon once (you may see one \"device connected\" notice)."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showAllowQDialog = false
                    DpadService.send(ctx, DpadService.ACTION_ALLOW_Q)
                }) { Text("Allow") }
            },
            dismissButton = { TextButton(onClick = { showAllowQDialog = false }) { Text("Cancel") } },
        )
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

/** Backing for one LazyColumn item inside the Keys section (see [ProfileEditor]): a flat
 *  surface-tinted background so a run of separately-keyed items (header, each key row, the
 *  Unassigned toggle, each unassigned group) still reads as one continuous card, rounded only at
 *  [top] and/or [bottom] of that run. */
@Composable
private fun Modifier.keysCardSection(top: Boolean = false, bottom: Boolean = false): Modifier {
    val shape = RoundedCornerShape(
        topStart = if (top) 12.dp else 0.dp, topEnd = if (top) 12.dp else 0.dp,
        bottomStart = if (bottom) 12.dp else 0.dp, bottomEnd = if (bottom) 12.dp else 0.dp,
    )
    return this
        .fillMaxWidth()
        .background(MaterialTheme.colorScheme.surfaceColorAtElevation(1.dp), shape)
        .padding(horizontal = 16.dp)
}

/** One target key: label, chips for each bound source (with × to unbind), and a "+ Bind" button —
 *  or, if [KeyDef.disabledReason] is set and [forceEnabled] is false, a greyed label/reason and no
 *  bind button (still shows any existing chips, so a legacy binding to a since-disabled key stays
 *  visible and removable). If [onAllowClick] is non-null, a clickable "Allow…" link renders next to
 *  the reason (used for KEY_Q's opt-in confirmation flow). [onWarningClick], when non-null, renders
 *  a red clickable "Warning: OSRS ban risk" label next to the key name (used for the wheel targets). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun KeyRow(
    k: KeyDef, sources: List<String>, onUnbind: (String) -> Unit, onBind: () -> Unit,
    onWarningClick: (() -> Unit)? = null, forceEnabled: Boolean = false, onAllowClick: (() -> Unit)? = null,
) {
    val disabled = k.disabledReason != null && !forceEnabled
    Column(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                k.label, Modifier.width(88.dp), style = MaterialTheme.typography.bodyMedium,
                color = if (disabled) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
            )
            if (onWarningClick != null) {
                WheelWarningLabel(onClick = onWarningClick)
                Spacer(Modifier.width(4.dp))
            }
            FlowRow(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (sources.isEmpty()) Text("—", style = MaterialTheme.typography.bodySmall)
                for (s in sources) SourceChip(s) { onUnbind(s) }
            }
            Spacer(Modifier.width(4.dp))
            if (!disabled) {
                OutlinedButton(modifier = Modifier.heightIn(min = 48.dp), onClick = onBind) { Text("+ Bind") }
            }
        }
        if (disabled) {
            Row(Modifier.fillMaxWidth().padding(start = 88.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    k.disabledReason!!, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                if (onAllowClick != null) {
                    Spacer(Modifier.width(8.dp))
                    TextButton(modifier = Modifier.heightIn(min = 48.dp), onClick = onAllowClick) { Text("Allow…") }
                }
            }
        }
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
private fun OffsetStepButton(label: String, onClick: () -> Unit, enabled: Boolean = true) {
    OutlinedButton(
        modifier = Modifier.size(48.dp),
        contentPadding = PaddingValues(0.dp),
        enabled = enabled,
        onClick = onClick,
    ) { Text(label, style = MaterialTheme.typography.titleMedium) }
}

/** A labeled stepper row in the same style as [OffsetRow], but for a value that's read-only
 *  between the −/+ buttons (no text field) and commits on every tap -- no debounce. Used for
 *  Deadzone and Wheel repeat, which used to be [androidx.compose.material3.Slider]s; those were
 *  easy to drag by accident while scrolling the editor, which this replaces. */
@Composable
private fun StepperRow(
    label: String, valueText: String, onDec: () -> Unit, onInc: () -> Unit,
    decEnabled: Boolean = true, incEnabled: Boolean = true,
) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(label, Modifier.width(88.dp), style = MaterialTheme.typography.bodyMedium)
        OffsetStepButton("−", onClick = onDec, enabled = decEnabled)
        Text(
            valueText, Modifier.width(84.dp), style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
        )
        OffsetStepButton("+", onClick = onInc, enabled = incEnabled)
    }
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
