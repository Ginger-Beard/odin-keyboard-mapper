package com.dpad.mgr.ui

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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dpad.mgr.core.KeyDef
import com.dpad.mgr.core.Keys
import com.dpad.mgr.core.Profile
import com.dpad.mgr.core.SourceNames
import com.dpad.mgr.core.Sources
import com.dpad.mgr.core.Store
import kotlinx.coroutines.launch

@Composable
fun ProfilesScreen(modifier: Modifier = Modifier) {
    val data by Store.data.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<Pair<String?, Profile>?>(null) } // originalName, draft
    val e = editing
    if (e != null) {
        ProfileEditor(
            original = e.first, initial = e.second,
            existingNames = data.profiles.map { it.name },
            onSave = { p -> Store.saveProfile(p, e.first); editing = null },
            onDuplicate = { dup -> Store.saveProfile(dup); editing = dup.name to dup },
            onDelete = { Store.deleteProfile(e.first!!); editing = null },
            onCancel = { editing = null },
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

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ProfileEditor(
    original: String?, initial: Profile, existingNames: List<String>,
    onSave: (Profile) -> Unit, onDuplicate: (Profile) -> Unit, onDelete: () -> Unit,
    onCancel: () -> Unit, modifier: Modifier = Modifier,
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var draft by remember { mutableStateOf(initial) }
    var binding by remember { mutableStateOf<String?>(null) } // target key name being bound
    var showLetters by remember { mutableStateOf(false) }
    var showSwallowed by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val nameClash = draft.name.isBlank() || (draft.name != original && draft.name in existingNames)
    val snackbarHostState = remember { SnackbarHostState() }

    // CalibrateActivity saves straight to the Store; pick up its touch-offset result here.
    val storeData by Store.data.collectAsStateWithLifecycle()
    LaunchedEffect(storeData) {
        val latest = storeData.profile(draft.name) ?: return@LaunchedEffect
        if (latest.touchOffsetEnabled != draft.touchOffsetEnabled || latest.touchDx != draft.touchDx || latest.touchDy != draft.touchDy) {
            draft = draft.copy(touchOffsetEnabled = latest.touchOffsetEnabled, touchDx = latest.touchDx, touchDy = latest.touchDy)
        }
    }

    Box(modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxWidth().padding(12.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // ---- Header card ----
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        draft.name, { draft = draft.copy(name = it) }, Modifier.fillMaxWidth(),
                        label = { Text("Profile name") }, isError = nameClash, singleLine = true,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        val def = builtinDefault(original)
                        if (def != null) {
                            OutlinedButton(modifier = Modifier.heightIn(min = 48.dp), onClick = { draft = def }) {
                                Text("Reset to default")
                            }
                        }
                        OutlinedButton(
                            modifier = Modifier.heightIn(min = 48.dp),
                            onClick = { onDuplicate(draft.copy(name = uniqueName("${draft.name} copy", existingNames))) },
                        ) { Text("Duplicate") }
                        if (original != null) {
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
                        KeyRow(k, draft.sourcesFor(k.keyName), onUnbind = { draft = draft.unbind(it) }, onBind = { binding = k.keyName })
                    }
                    TextButton(modifier = Modifier.heightIn(min = 48.dp), onClick = { showLetters = !showLetters }) {
                        Text(if (showLetters) "Hide letters" else "Letters (A–Z)…")
                    }
                    if (showLetters) {
                        Text("Letters", style = MaterialTheme.typography.titleSmall)
                        for (k in Keys.OTHER) {
                            KeyRow(k, draft.sourcesFor(k.keyName), onUnbind = { draft = draft.unbind(it) }, onBind = { binding = k.keyName })
                        }
                    }
                }
            }

            // ---- Sticks section ----
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Sticks", style = MaterialTheme.typography.titleMedium)
                    StickRow("Left stick", draft.lsInvertY, { draft = draft.copy(lsInvertY = it) }, draft.lsInvertX, { draft = draft.copy(lsInvertX = it) })
                    StickRow("Right stick", draft.rsInvertY, { draft = draft.copy(rsInvertY = it) }, draft.rsInvertX, { draft = draft.copy(rsInvertX = it) })
                    Text("Deadzone: ${"%.2f".format(draft.deadzone)}", style = MaterialTheme.typography.bodyMedium)
                    Slider(value = draft.deadzone, onValueChange = { draft = draft.copy(deadzone = it) }, valueRange = 0.2f..0.8f, steps = 11)
                    Text("Wheel repeat: ${draft.wheelRepeatMs} ms", style = MaterialTheme.typography.bodyMedium)
                    Slider(
                        value = draft.wheelRepeatMs.toFloat(),
                        onValueChange = { draft = draft.copy(wheelRepeatMs = it.toInt()) },
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
                        Switch(checked = draft.touchOffsetEnabled, onCheckedChange = { draft = draft.copy(touchOffsetEnabled = it) })
                        Spacer(Modifier.width(8.dp))
                        OutlinedButton(
                            modifier = Modifier.heightIn(min = 48.dp),
                            onClick = { draft = draft.copy(touchOffsetEnabled = false, touchDx = 0, touchDy = 0) },
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
                            // Persist the draft first so calibration (a separate activity) has a saved
                            // profile to read and write touch offset fields on.
                            Store.saveProfile(draft, original)
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
                Button(
                    modifier = Modifier.heightIn(min = 48.dp),
                    enabled = !nameClash,
                    onClick = { onSave(draft.copy(name = draft.name.trim())) },
                ) { Text("Save") }
                OutlinedButton(modifier = Modifier.heightIn(min = 48.dp), onClick = onCancel) { Text("Cancel") }
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
                draft = draft.bind(src, keyName)
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
            confirmButton = { TextButton(onClick = { confirmDelete = false; onDelete() }) { Text("Delete") } },
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
