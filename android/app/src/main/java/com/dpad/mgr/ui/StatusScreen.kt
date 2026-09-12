package com.dpad.mgr.ui

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
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
import com.dpad.mgr.core.DaemonState
import com.dpad.mgr.core.Store
import com.dpad.mgr.priv.PrivSource
import com.dpad.mgr.svc.DpadService
import com.dpad.mgr.svc.ServiceState

private const val SHIZUKU_PKG = "moe.shizuku.privileged.api"

private fun isShizukuInstalled(pm: PackageManager): Boolean =
    runCatching { pm.getPackageInfo(SHIZUKU_PKG, 0) }.isSuccess

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
        if (priv.source == PrivSource.NONE) {
            SetupCard()
        }
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Status", style = MaterialTheme.typography.titleMedium)
                Text("Service: " + if (running) "running" else "stopped")
                Text("Privilege: ${priv.source.label}" + if (priv.probing) " (probing…)" else "")
                Text(priv.detail, style = MaterialTheme.typography.bodySmall)
                Text("Daemon: ${daemon.label}")
                Text("Binary: ${bin ?: "n/a"}", style = MaterialTheme.typography.bodySmall)
                Text("Pad device: " + (pad.name?.let { "$it (${pad.idText})" } ?: "missing"))
                Text("Foreground: ${fg ?: "none / launcher"}", style = MaterialTheme.typography.bodySmall)
                val runningProfile = (daemon as? DaemonState.Running)?.let { data.profile(it.profile) }
                if (runningProfile?.touchOffsetEnabled == true) {
                    Text("Touch offset: ${runningProfile.touchDx},${runningProfile.touchDy}", style = MaterialTheme.typography.bodySmall)
                    Text("Panic: hold both back buttons 1 s", style = MaterialTheme.typography.bodySmall)
                }
                if (pad.warn) {
                    Spacer(Modifier.height(4.dp))
                    Card(colors = CardDefaults.cardColors(containerColor = Color(0xFFB00020))) {
                        Text(
                            if (pad.missing) "No gamepad device found (on the Odin 2, set AYN Controller Style to Xbox for this app)"
                            else "Pad is \"None Controller\" — on the Odin 2, set AYN Controller Style for this app to Xbox.",
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

@Composable
private fun SetupCard(modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val pm = ctx.packageManager
    var shizukuInstalled by remember { mutableStateOf(isShizukuInstalled(pm)) }

    Card(modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Setup", style = MaterialTheme.typography.titleMedium)
            Text(
                "This app has no shell access yet. Complete these steps to enable it:",
                style = MaterialTheme.typography.bodySmall,
            )

            SetupStep(
                number = 1,
                title = "Install Shizuku",
                body = "Shizuku gives this app shell access without root.",
                buttonLabel = if (shizukuInstalled) "Open Shizuku" else "Install Shizuku",
                onClick = {
                    if (shizukuInstalled) {
                        pm.getLaunchIntentForPackage(SHIZUKU_PKG)?.let { ctx.startActivity(it) }
                    } else {
                        val market = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$SHIZUKU_PKG"))
                        runCatching { ctx.startActivity(market) }.onFailure {
                            val web = Intent(
                                Intent.ACTION_VIEW,
                                Uri.parse("https://play.google.com/store/apps/details?id=$SHIZUKU_PKG"),
                            )
                            ctx.startActivity(web)
                        }
                    }
                    shizukuInstalled = isShizukuInstalled(pm)
                },
            )

            SetupStep(
                number = 2,
                title = "Turn on Wireless debugging",
                body = "Settings -> System -> Developer options -> Wireless debugging " +
                    "(tap the row, not just the switch).",
                buttonLabel = "Open Developer options",
                onClick = { ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)) },
            )

            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("3. Start Shizuku via Wireless debugging", style = MaterialTheme.typography.titleSmall)
                Text(
                    "In Shizuku, tap \"Start via Wireless debugging\", follow its pairing steps " +
                        "(you'll enter a pairing code from the Wireless debugging screen), then tap Start.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            SetupStep(
                number = 4,
                title = "Grant access",
                body = "Requests the Shizuku permission for this app and re-checks privilege.",
                buttonLabel = "Grant access",
                onClick = { DpadService.send(ctx, DpadService.ACTION_RECHECK) },
            )

            Text(
                "After a reboot, Wireless debugging turns off on most devices; turn it on again " +
                    "and tap Start in Shizuku.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun SetupStep(number: Int, title: String, body: String, buttonLabel: String, onClick: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("$number. $title", style = MaterialTheme.typography.titleSmall)
        Text(body, style = MaterialTheme.typography.bodySmall)
        Button(onClick = onClick) { Text(buttonLabel) }
    }
}
