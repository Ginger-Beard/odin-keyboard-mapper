package com.dpad.mgr.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dpad.mgr.core.Keys
import com.dpad.mgr.core.Profile
import com.dpad.mgr.core.Sources
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
                        val mapped = Sources.ALL.filter { p.key(it.id) != Keys.NONE }.joinToString { "${it.label}→${Keys.label(p.key(it.id))}" }
                        Text(if (mapped.isEmpty()) "no keys mapped" else mapped, style = MaterialTheme.typography.bodySmall, maxLines = 2)
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

private fun uniqueName(base: String, names: List<String>): String {
    if (base !in names) return base
    var i = 2
    while ("$base $i" in names) i++
    return "$base $i"
}

@Composable
fun ProfileEditor(
    original: String?, initial: Profile, existingNames: List<String>,
    onSave: (Profile) -> Unit, onCancel: () -> Unit, modifier: Modifier = Modifier,
) {
    var draft by remember { mutableStateOf(initial) }
    var picking by remember { mutableStateOf<String?>(null) }
    var modPicking by remember { mutableStateOf<String?>(null) }
    var modMenu by remember { mutableStateOf(false) }
    val nameClash = draft.name.isBlank() || (draft.name != original && draft.name in existingNames)

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
        Spacer(Modifier.height(4.dp))
        Text("Modifier button", style = MaterialTheme.typography.titleMedium)
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = { modMenu = true }) {
                Text(draft.modifier?.let { id -> Sources.ALL.first { it.id == id }.label } ?: "None")
            }
            DropdownMenu(expanded = modMenu, onDismissRequest = { modMenu = false }) {
                DropdownMenuItem(text = { Text("None") }, onClick = {
                    draft = draft.copy(modifier = null)
                    modMenu = false
                })
                Sources.ALL.filter { it.id.startsWith("btn.") }.forEach { s ->
                    DropdownMenuItem(text = { Text(s.label) }, onClick = {
                        draft = draft.copy(modifier = s.id, map = draft.map - s.id)
                        modMenu = false
                    })
                }
            }
        }
        for (g in Sources.GROUPS) {
            Spacer(Modifier.height(4.dp))
            Text(g, style = MaterialTheme.typography.titleMedium)
            if (g == Sources.STICKS) {
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Invert left stick vertical", Modifier.weight(1f))
                    Switch(checked = draft.lsInvertY, onCheckedChange = { draft = draft.copy(lsInvertY = it) })
                }
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Invert right stick vertical", Modifier.weight(1f))
                    Switch(checked = draft.rsInvertY, onCheckedChange = { draft = draft.copy(rsInvertY = it) })
                }
            }
            for (s in Sources.ALL.filter { it.group == g }) {
                Row(Modifier.fillMaxWidth().clickable { picking = s.id }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(s.label, Modifier.weight(1f))
                    if (s.id == draft.modifier) {
                        Text("Modifier", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
                    } else {
                        OutlinedButton(onClick = { picking = s.id }) { Text(Keys.label(draft.key(s.id))) }
                    }
                }
            }
        }
        if (draft.modifier != null) {
            Spacer(Modifier.height(4.dp))
            Text("While modifier held", style = MaterialTheme.typography.titleMedium)
            for (s in Sources.ALL.filter { it.id != draft.modifier }) {
                Row(Modifier.fillMaxWidth().clickable { modPicking = s.id }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(s.label, Modifier.weight(1f))
                    val cur = draft.modKey(s.id)
                    OutlinedButton(onClick = { modPicking = s.id }) { Text(cur?.let { Keys.label(it) } ?: "Same as base") }
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
    picking?.let { src ->
        KeyPickerDialog(
            title = Sources.ALL.first { it.id == src }.label,
            current = draft.key(src),
            onPick = { k -> draft = draft.withKey(src, k); picking = null },
            onDismiss = { picking = null },
        )
    }
    modPicking?.let { src ->
        ModKeyPickerDialog(
            title = Sources.ALL.first { it.id == src }.label,
            current = draft.modKey(src),
            onPick = { k -> draft = draft.withModKey(src, k); modPicking = null },
            onDismiss = { modPicking = null },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun KeyPickerDialog(title: String, current: String, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    var showOther by remember { mutableStateOf(Keys.OTHER.any { it.keyName == current }) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Keys.PRIMARY.forEach { k ->
                        FilterChip(selected = k.keyName == current, onClick = { onPick(k.keyName) }, label = { Text(k.label) })
                    }
                }
                Spacer(Modifier.height(8.dp))
                TextButton(onClick = { showOther = !showOther }) { Text(if (showOther) "Hide other (A–Z)" else "Other (A–Z)…") }
                if (showOther) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Keys.OTHER.forEach { k ->
                            FilterChip(selected = k.keyName == current, onClick = { onPick(k.keyName) }, label = { Text(k.label) })
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

/** Like [KeyPickerDialog] but with an extra "Same as base" choice (= null, not present in modBindings). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ModKeyPickerDialog(title: String, current: String?, onPick: (String?) -> Unit, onDismiss: () -> Unit) {
    var showOther by remember { mutableStateOf(current != null && Keys.OTHER.any { it.keyName == current }) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(selected = current == null, onClick = { onPick(null) }, label = { Text("Same as base") })
                    Keys.PRIMARY.forEach { k ->
                        FilterChip(selected = k.keyName == current, onClick = { onPick(k.keyName) }, label = { Text(k.label) })
                    }
                }
                Spacer(Modifier.height(8.dp))
                TextButton(onClick = { showOther = !showOther }) { Text(if (showOther) "Hide other (A–Z)" else "Other (A–Z)…") }
                if (showOther) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Keys.OTHER.forEach { k ->
                            FilterChip(selected = k.keyName == current, onClick = { onPick(k.keyName) }, label = { Text(k.label) })
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}
