package com.dpad.mgr.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeOut
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
        Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                modifier = Modifier.heightIn(min = 48.dp),
                onClick = { editing = null to Profile(name = uniqueName("New profile", data.profiles.map { it.name })) },
            ) { Text("New profile") }
        }
        LazyColumn {
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
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ProfileEditor(
    original: String?, initial: Profile, existingNames: List<String>,
    onDone: () -> Unit, modifier: Modifier = Modifier,
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var draft by remember { mutableStateOf(initial) }
    var persistedName by remember { mutableStateOf(original) } // name draft is currently saved under in the Store, if any
    var binding by remember { mutableStateOf<String?>(null) } // target key name being bound
    var showLetters by remember { mutableStateOf(false) }
    var showSwallowed by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var showSaved by remember { mutableStateOf(false) }
    var pendingJob by remember { mutableStateOf<Job?>(null) } // debounced text/slider save in flight
    var savedFlashJob by remember { mutableStateOf<Job?>(null) }
    val nameClash = draft.name.isBlank() || (draft.name != persistedName && draft.name in existingNames)
    val snackbarHostState = remember { SnackbarHostState() }

    // A non-visual focus sink: claims focus once on entry so that later, when LearnDialog (a
    // separate Window) is dismissed, Android restores focus here -- not to the name field, which
    // would otherwise be the first focusable element found by the default "nothing was focused"
    // search, and would drag the scroll position up to it in the process.
    val rootFocusRequester = remember { FocusRequester() }

    fun flashSaved() {
        showSaved = true
        savedFlashJob?.cancel()
        savedFlashJob = scope.launch { delay(1000); showSaved = false }
    }

    /** Writes [p] to the Store (skipping a blank/duplicate name -- same guard the old Save button's
     *  `enabled` used) and, if [live] and this profile is the one currently Running in the daemon,
     *  nudges it live via updateConfigLive. That path only actually live-reloads touch.offset (via
     *  SIGUSR1); everything else there is a no-op signal, so binding/invert/deadzone/wheel changes
     *  are deliberately NOT pushed live here -- DpadService's own Store.data collector notices the
     *  config text changed for the running profile and restarts the daemon through the Supervisor. */
    fun persistNow(p: Profile, live: Boolean) {
        if (p.name.isBlank() || (p.name != persistedName && p.name in existingNames)) return
        Store.saveProfile(p, persistedName)
        persistedName = p.name
        flashSaved()
        if (live) {
            val d = ServiceState.daemon.value
            if (d is DaemonState.Running && d.profile == p.name) {
                DpadService.send(ctx, DpadService.ACTION_UPDATE_CONFIG_LIVE, profile = p.name)
            }
        }
    }

    fun change(debounceMs: Long = 0, live: Boolean = false, mutate: (Profile) -> Profile) {
        draft = mutate(draft)
        val snapshot = draft
        pendingJob?.cancel()
        pendingJob = if (debounceMs <= 0) {
            persistNow(snapshot, live)
            null
        } else {
            scope.launch { delay(debounceMs); persistNow(snapshot, live) }
        }
    }

    // Brand-new profile: persist it as soon as the editor opens so it exists in the Store (and
    // Delete/Duplicate/rename bookkeeping below have something to key off) right away.
    LaunchedEffect(Unit) {
        if (persistedName == null) persistNow(draft, live = false)
        rootFocusRequester.requestFocus()
    }

    // CalibrateActivity saves straight to the Store; pick up its touch-offset result here.
    val storeData by Store.data.collectAsStateWithLifecycle()
    LaunchedEffect(storeData) {
        val latest = storeData.profile(draft.name) ?: return@LaunchedEffect
        if (latest.touchOffsetEnabled != draft.touchOffsetEnabled || latest.touchDx != draft.touchDx || latest.touchDy != draft.touchDy) {
            draft = draft.copy(touchOffsetEnabled = latest.touchOffsetEnabled, touchDx = latest.touchDx, touchDy = latest.touchDy)
        }
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
                        { text -> change(debounceMs = 300) { d -> d.copy(name = text) } },
                        Modifier.fillMaxWidth(),
                        label = { Text("Profile name") }, isError = nameClash, singleLine = true,
                    )
                    AnimatedVisibility(visible = showSaved, exit = fadeOut()) {
                        Text("Saved", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        val def = builtinDefault(original)
                        if (def != null) {
                            OutlinedButton(modifier = Modifier.heightIn(min = 48.dp), onClick = { change { def } }) {
                                Text("Reset to default")
                            }
                        }
                        OutlinedButton(
                            modifier = Modifier.heightIn(min = 48.dp),
                            onClick = {
                                val dup = draft.copy(name = uniqueName("${draft.name} copy", existingNames))
                                pendingJob?.cancel()
                                Store.saveProfile(dup)
                                draft = dup
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
                    Text(
                        "To bind a combo, hold a button while pressing the control during Bind.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text("Game keys", style = MaterialTheme.typography.titleSmall)
                    for (k in Keys.PRIMARY) {
                        KeyRow(
                            k, draft.sourcesFor(k.keyName),
                            onUnbind = { src -> change { d -> d.unbind(src) } },
                            onBind = { binding = k.keyName },
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
                                onUnbind = { src -> change { d -> d.unbind(src) } },
                                onBind = { binding = k.keyName },
                            )
                        }
                    }
                }
            }

            // ---- Sticks section ----
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    StickRow(
                        "Left stick", draft.lsInvertY, { v -> change { d -> d.copy(lsInvertY = v) } },
                        draft.lsInvertX, { v -> change { d -> d.copy(lsInvertX = v) } },
                    )
                    StickRow(
                        "Right stick", draft.rsInvertY, { v -> change { d -> d.copy(rsInvertY = v) } },
                        draft.rsInvertX, { v -> change { d -> d.copy(rsInvertX = v) } },
                    )
                    Text("Deadzone: ${"%.2f".format(draft.deadzone)}", style = MaterialTheme.typography.bodyMedium)
                    Slider(
                        value = draft.deadzone,
                        onValueChange = { v -> change(debounceMs = 300) { d -> d.copy(deadzone = v) } },
                        valueRange = 0.2f..0.8f, steps = 11,
                    )
                    Text("Wheel repeat: ${draft.wheelRepeatMs} ms", style = MaterialTheme.typography.bodyMedium)
                    Slider(
                        value = draft.wheelRepeatMs.toFloat(),
                        onValueChange = { v -> change(debounceMs = 300) { d -> d.copy(wheelRepeatMs = v.toInt()) } },
                        valueRange = 60f..400f, steps = 32,
                    )
                }
            }

            // ---- Stylus offset card ----
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Stylus offset", style = MaterialTheme.typography.titleMedium)
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("Enabled", Modifier.weight(1f))
                        Switch(
                            checked = draft.touchOffsetEnabled,
                            onCheckedChange = { v -> change(live = true) { d -> d.copy(touchOffsetEnabled = v) } },
                        )
                        Spacer(Modifier.width(8.dp))
                        OutlinedButton(
                            modifier = Modifier.heightIn(min = 48.dp),
                            onClick = { change(live = true) { d -> d.copy(touchOffsetEnabled = false, touchDx = 0, touchDy = 0) } },
                        ) { Text("Disable") }
                    }
                    Text(
                        "Only active while the assigned game is in front. To turn it off from inside the game, hold both back buttons for 1 second.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text("Offset: dx=${draft.touchDx}, dy=${draft.touchDy} (panel units)", style = MaterialTheme.typography.bodySmall)
                    OutlinedButton(
                        modifier = Modifier.heightIn(min = 48.dp),
                        onClick = {
                            // Flush any pending debounced edit first so calibration (a separate
                            // activity) has a fully up-to-date saved profile to read and write
                            // touch offset fields on.
                            pendingJob?.cancel()
                            persistNow(draft, live = false)
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
                change { d -> d.bind(src, keyName) }
                binding = null
                if (oldKey != null && oldKey != keyName) {
                    val msg = "Moved from ${Keys.label(oldKey)}"
                    scope.launch { snackbarHostState.showSnackbar(msg) }
                }
            },
            onDismiss = { binding = null },
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
}

/** One target key: label, chips for each bound source (with × to unbind), and a "+ Bind" button. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun KeyRow(k: KeyDef, sources: List<String>, onUnbind: (String) -> Unit, onBind: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp).heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(k.label, Modifier.width(88.dp), style = MaterialTheme.typography.bodyMedium)
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
