package com.dpad.mgr.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
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
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import com.dpad.mgr.core.Store

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
            onCancel = { editing = null },
            modifier = modifier,
        )
        return
    }
    Column(modifier) {
        Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { editing = null to Profile(name = uniqueName("New profile", data.profiles.map { it.name })) }) { Text("New profile") }
        }
        LazyColumn {
            items(data.profiles, key = { it.name }) { p ->
                val used = data.assignments.count { it.value == p.name }
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(p.name, style = MaterialTheme.typography.bodyLarge)
                        Text(summary(p), style = MaterialTheme.typography.bodySmall, maxLines = 2)
                        Text("deadzone ${"%.2f".format(p.deadzone)} · used by $used app(s)", style = MaterialTheme.typography.bodySmall)
                    }
                    TextButton(onClick = { editing = p.name to p }) { Text("Edit") }
                    TextButton(onClick = {
                        editing = null to p.copy(name = uniqueName("${p.name} copy", data.profiles.map { it.name }))
                    }) { Text("Copy") }
                    if (p.name == "OSRS" || p.name == "WASD") {
                        TextButton(onClick = {
                            val def = if (p.name == "OSRS") Profile.OSRS else Profile.WASD
                            Store.saveProfile(def, p.name)
                        }) { Text("Reset to default") }
                    }
                    TextButton(enabled = data.profiles.size > 1, onClick = { Store.deleteProfile(p.name) }) { Text("Delete") }
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

/** What the "Bind…" button was pressed for: a key in the base or modifier layer, or the modifier control itself. */
private sealed class BindTarget(val title: String) {
    data object Modifier : BindTarget("the modifier control")
    class Key(val keyName: String, val mod: Boolean) : BindTarget(
        (if (keyName == Keys.NONE) "Swallow (no key)" else Keys.label(keyName)) + if (mod) " (while modifier held)" else ""
    )
}

@Composable
fun ProfileEditor(
    original: String?, initial: Profile, existingNames: List<String>,
    onSave: (Profile) -> Unit, onCancel: () -> Unit, modifier: Modifier = Modifier,
) {
    val ctx = LocalContext.current
    var draft by remember { mutableStateOf(initial) }
    var binding by remember { mutableStateOf<BindTarget?>(null) }
    var showLetters by remember { mutableStateOf(false) }
    var showModLetters by remember { mutableStateOf(false) }
    val nameClash = draft.name.isBlank() || (draft.name != original && draft.name in existingNames)
    val swallow = KeyDef("Swallow (no key)", Keys.NONE)

    // CalibrateActivity saves straight to the Store; pick up its touch-offset result here.
    val storeData by Store.data.collectAsStateWithLifecycle()
    LaunchedEffect(storeData) {
        val latest = storeData.profile(draft.name) ?: return@LaunchedEffect
        if (latest.touchOffsetEnabled != draft.touchOffsetEnabled || latest.touchDx != draft.touchDx || latest.touchDy != draft.touchDy) {
            draft = draft.copy(touchOffsetEnabled = latest.touchOffsetEnabled, touchDx = latest.touchDx, touchDy = latest.touchDy)
        }
    }

    Column(modifier.padding(12.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(draft.name, { draft = draft.copy(name = it) }, Modifier.fillMaxWidth(), label = { Text("Profile name") },
            isError = nameClash, singleLine = true)
        Text("Deadzone: ${"%.2f".format(draft.deadzone)}", style = MaterialTheme.typography.bodyMedium)
        Slider(value = draft.deadzone, onValueChange = { draft = draft.copy(deadzone = it) }, valueRange = 0.2f..0.8f, steps = 11)
        Text("Wheel repeat: ${draft.wheelRepeatMs} ms", style = MaterialTheme.typography.bodyMedium)
        Slider(
            value = draft.wheelRepeatMs.toFloat(),
            onValueChange = { draft = draft.copy(wheelRepeatMs = it.toInt()) },
            valueRange = 60f..400f, steps = 32,
        )
        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Invert left stick vertical", Modifier.weight(1f))
            Switch(checked = draft.lsInvertY, onCheckedChange = { draft = draft.copy(lsInvertY = it) })
        }
        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Invert right stick vertical", Modifier.weight(1f))
            Switch(checked = draft.rsInvertY, onCheckedChange = { draft = draft.copy(rsInvertY = it) })
        }

        Spacer(Modifier.height(4.dp))
        Text("Stylus / touch offset", style = MaterialTheme.typography.titleMedium)
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("Enabled", Modifier.weight(1f))
                    Switch(checked = draft.touchOffsetEnabled, onCheckedChange = { draft = draft.copy(touchOffsetEnabled = it) })
                    Spacer(Modifier.width(8.dp))
                    OutlinedButton(onClick = { draft = draft.copy(touchOffsetEnabled = false, touchDx = 0, touchDy = 0) }) { Text("Disable") }
                }
                Text(
                    "Only active while the assigned game is in front. To turn it off from inside the game, hold both back buttons for 1 second.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Text("Offset: dx=${draft.touchDx}, dy=${draft.touchDy} (panel units)", style = MaterialTheme.typography.bodySmall)
                OutlinedButton(onClick = {
                    // Persist the draft first so calibration (a separate activity) has a saved
                    // profile to read and write touch offset fields on.
                    Store.saveProfile(draft, original)
                    ctx.startActivity(Intent(ctx, CalibrateActivity::class.java).putExtra(CalibrateActivity.EXTRA_PROFILE, draft.name))
                }) { Text("Calibrate…") }
            }
        }

        Spacer(Modifier.height(4.dp))
        Text("Modifier control", style = MaterialTheme.typography.titleMedium)
        Text("Hold it to switch to the \"While modifier held\" bindings.", style = MaterialTheme.typography.bodySmall)
        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            val m = draft.modifier
            if (m == null) Text("None", Modifier.weight(1f))
            else Row(Modifier.weight(1f)) { SourceChip(m) { draft = draft.withModifier(null) } }
            OutlinedButton(onClick = { binding = BindTarget.Modifier }) { Text("Bind…") }
        }

        Spacer(Modifier.height(4.dp))
        Text("OSRS keys", style = MaterialTheme.typography.titleMedium)
        Text("For each key, press Bind… then press the pad control you want to send it.", style = MaterialTheme.typography.bodySmall)
        for (k in Keys.PRIMARY) {
            KeyRow(k, draft.sourcesFor(k.keyName), onUnbind = { draft = draft.unbind(it) }, onBind = { binding = BindTarget.Key(k.keyName, false) })
        }
        TextButton(onClick = { showLetters = !showLetters }) { Text(if (showLetters) "Hide letters" else "Letters (A–Z)…") }
        if (showLetters) {
            Text("Letters", style = MaterialTheme.typography.titleMedium)
            for (k in Keys.OTHER) {
                KeyRow(k, draft.sourcesFor(k.keyName), onUnbind = { draft = draft.unbind(it) }, onBind = { binding = BindTarget.Key(k.keyName, false) })
            }
        }

        if (draft.modifier != null) {
            Spacer(Modifier.height(4.dp))
            Text("While modifier held", style = MaterialTheme.typography.titleMedium)
            Text("Controls not listed here keep their normal key while the modifier is held.", style = MaterialTheme.typography.bodySmall)
            KeyRow(swallow, draft.modSourcesFor(Keys.NONE), onUnbind = { draft = draft.unbindMod(it) }, onBind = { binding = BindTarget.Key(Keys.NONE, true) })
            for (k in Keys.PRIMARY) {
                KeyRow(k, draft.modSourcesFor(k.keyName), onUnbind = { draft = draft.unbindMod(it) }, onBind = { binding = BindTarget.Key(k.keyName, true) })
            }
            TextButton(onClick = { showModLetters = !showModLetters }) { Text(if (showModLetters) "Hide letters" else "Letters (A–Z)…") }
            if (showModLetters) {
                for (k in Keys.OTHER) {
                    KeyRow(k, draft.modSourcesFor(k.keyName), onUnbind = { draft = draft.unbindMod(it) }, onBind = { binding = BindTarget.Key(k.keyName, true) })
                }
            }
        }

        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(enabled = !nameClash, onClick = { onSave(draft.copy(name = draft.name.trim())) }) { Text("Save") }
            OutlinedButton(onClick = onCancel) { Text("Cancel") }
        }
        Spacer(Modifier.height(24.dp))
    }

    binding?.let { target ->
        LearnDialog(
            title = target.title,
            onLearned = { src ->
                draft = when (target) {
                    is BindTarget.Modifier -> draft.withModifier(src)
                    is BindTarget.Key -> if (target.mod) draft.bindMod(src, target.keyName) else draft.bind(src, target.keyName)
                }
                binding = null
            },
            onDismiss = { binding = null },
        )
    }
}

/** One target key: label, chips for each bound source (with × to unbind), and a Bind… button. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun KeyRow(k: KeyDef, sources: List<String>, onUnbind: (String) -> Unit, onBind: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(k.label, Modifier.width(88.dp), style = MaterialTheme.typography.bodyMedium)
        FlowRow(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            if (sources.isEmpty()) Text("—", style = MaterialTheme.typography.bodySmall)
            for (s in sources) SourceChip(s) { onUnbind(s) }
        }
        Spacer(Modifier.width(4.dp))
        OutlinedButton(onClick = onBind) { Text("Bind…") }
    }
    HorizontalDivider()
}

@Composable
private fun SourceChip(src: String, onRemove: () -> Unit) {
    InputChip(
        selected = false,
        onClick = onRemove,
        label = { Text(SourceNames.label(src)) },
        trailingIcon = { Icon(Icons.Default.Close, contentDescription = "Unbind", Modifier.size(InputChipDefaults.IconSize)) },
    )
}
