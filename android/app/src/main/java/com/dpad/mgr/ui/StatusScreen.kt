package com.dpad.mgr.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dpad.mgr.core.Store
import com.dpad.mgr.svc.DpadService
import com.dpad.mgr.svc.ServiceState

@Composable
fun StatusScreen(modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val priv by ServiceState.priv.collectAsStateWithLifecycle()
    val daemon by ServiceState.daemon.collectAsStateWithLifecycle()
    val pad by ServiceState.pad.collectAsStateWithLifecycle()
    val fg by ServiceState.foreground.collectAsStateWithLifecycle()
    val bin by ServiceState.binaryPath.collectAsStateWithLifecycle()
    val msg by ServiceState.message.collectAsStateWithLifecycle()
    val running by ServiceState.serviceRunning.collectAsStateWithLifecycle()
    val data by Store.data.collectAsStateWithLifecycle()
    var testProfile by remember { mutableStateOf(data.profiles.firstOrNull()?.name ?: "") }
    var menu by remember { mutableStateOf(false) }

    Column(modifier.padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Status", style = MaterialTheme.typography.titleMedium)
                Text("Service: " + if (running) "running" else "stopped")
                Text("Privilege: ${priv.source.label}" + if (priv.probing) " (probing…)" else "")
                Text(priv.detail, style = MaterialTheme.typography.bodySmall)
                Text("Daemon: ${daemon.label}")
                Text("Binary: ${bin ?: "n/a"}", style = MaterialTheme.typography.bodySmall)
                Text("Pad device: ${pad.name ?: "missing"}")
                Text("Foreground: ${fg ?: "none / launcher"}", style = MaterialTheme.typography.bodySmall)
                if (pad.warn) {
                    Spacer(Modifier.height(4.dp))
                    Card(colors = CardDefaults.cardColors(containerColor = Color(0xFFB00020))) {
                        Text(
                            if (pad.missing) "Pad device missing. Set AYN Controller Style for this app to Xbox."
                            else "Pad is \"None Controller\". Set AYN Controller Style for this app to Xbox.",
                            Modifier.padding(12.dp), color = Color.White,
                        )
                    }
                }
                msg?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { DpadService.send(ctx, DpadService.ACTION_RECHECK) }) { Text("Re-check") }
            OutlinedButton(onClick = { DpadService.send(ctx, DpadService.ACTION_STOP_DAEMON) }) { Text("Stop daemon") }
        }
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Test profile now (30 s)", style = MaterialTheme.typography.titleMedium)
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    OutlinedButton(onClick = { menu = true }) { Text(testProfile.ifEmpty { "select profile" }) }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        data.profiles.forEach { p ->
                            DropdownMenuItem(text = { Text(p.name) }, onClick = { testProfile = p.name; menu = false })
                        }
                    }
                    Spacer(Modifier.width(8.dp))
                    Button(enabled = testProfile.isNotEmpty(), onClick = {
                        DpadService.send(ctx, DpadService.ACTION_TEST, profile = testProfile, seconds = 30)
                    }) { Text("Test profile now") }
                }
                Text("Starts the daemon with the selected profile for 30 seconds. All pad input is swallowed except the mapped keys.",
                    style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
