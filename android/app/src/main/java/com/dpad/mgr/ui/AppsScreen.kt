package com.dpad.mgr.ui

import android.content.Intent
import android.content.pm.PackageManager
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dpad.mgr.core.Store
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class AppEntry(val pkg: String, val label: String, val icon: ImageBitmap?)

@Composable
fun AppsScreen(modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val data by Store.data.collectAsStateWithLifecycle()
    var apps by remember { mutableStateOf<List<AppEntry>?>(null) }
    var filter by remember { mutableStateOf("") }
    LaunchedEffect(Unit) {
        apps = withContext(Dispatchers.IO) {
            val pm = ctx.packageManager
            val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            pm.queryIntentActivities(intent, PackageManager.MATCH_ALL)
                .filter { it.activityInfo.packageName != ctx.packageName }
                .distinctBy { it.activityInfo.packageName }
                .map { ri ->
                    val icon = runCatching { ri.loadIcon(pm).toBitmap(96, 96).asImageBitmap() }.getOrNull()
                    AppEntry(ri.activityInfo.packageName, ri.loadLabel(pm).toString(), icon)
                }
                .sortedWith(compareBy({ data.assignments[it.pkg] == null }, { it.label.lowercase() }))
        }
    }
    Column(modifier) {
        Text("Apps", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp))
        OutlinedTextField(filter, { filter = it }, Modifier.fillMaxWidth().padding(horizontal = 12.dp), label = { Text("Filter apps") }, singleLine = true)
        val list = apps
        if (list == null) {
            Text("Loading apps…", Modifier.padding(16.dp))
            return
        }
        val shown = list.filter { filter.isBlank() || it.label.contains(filter, true) || it.pkg.contains(filter, true) }
        LazyColumn {
            items(shown, key = { it.pkg }) { app ->
                var menu by remember { mutableStateOf(false) }
                val assigned = data.assignments[app.pkg]
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (app.icon != null) Image(app.icon, null, Modifier.size(40.dp)) else Spacer(Modifier.size(40.dp))
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(app.label, style = MaterialTheme.typography.bodyLarge)
                        Text(app.pkg, style = MaterialTheme.typography.bodySmall)
                    }
                    OutlinedButton(onClick = { menu = true }) { Text(assigned ?: "Off") }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text("Off") }, onClick = { Store.assign(app.pkg, null); menu = false })
                        data.profiles.forEach { p ->
                            DropdownMenuItem(text = { Text(p.name) }, onClick = { Store.assign(app.pkg, p.name); menu = false })
                        }
                    }
                }
                HorizontalDivider()
            }
        }
    }
}
