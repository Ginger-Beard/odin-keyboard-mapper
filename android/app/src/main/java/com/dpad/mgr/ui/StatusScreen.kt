package com.dpad.mgr.ui

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
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
import kotlinx.coroutines.delay

private const val SHIZUKU_PKG = "moe.shizuku.privileged.api"

private fun isShizukuInstalled(pm: PackageManager): Boolean =
    runCatching { pm.getPackageInfo(SHIZUKU_PKG, 0) }.isSuccess

/** Best-effort app label for a package, falling back to the package name. */
private fun appLabel(ctx: Context, pkg: String): String =
    runCatching {
        val pm = ctx.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    }.getOrDefault(pkg)

private fun isDaemonActive(d: DaemonState): Boolean = when (d) {
    is DaemonState.Running, is DaemonState.Starting, is DaemonState.Backoff -> true
    else -> false
}

@Composable
fun StatusScreen(modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val priv by ServiceState.priv.collectAsStateWithLifecycle()
    val daemon by ServiceState.daemon.collectAsStateWithLifecycle()
    val pad by ServiceState.pad.collectAsStateWithLifecycle()
    val fg by ServiceState.foreground.collectAsStateWithLifecycle()
    val bin by ServiceState.binaryPath.collectAsStateWithLifecycle()
    val lastAction by ServiceState.lastAction.collectAsStateWithLifecycle()
    val testEndsAtMs by ServiceState.testEndsAtMs.collectAsStateWithLifecycle()
    val data by Store.data.collectAsStateWithLifecycle()
    var testProfile by remember {
        mutableStateOf(data.profiles.firstOrNull { it.name == "OSRS" }?.name ?: data.profiles.firstOrNull()?.name ?: "")
    }
    var menu by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(lastAction) { lastAction?.let { snackbarHostState.showSnackbar(it) } }

    // Ticks once a second while a test is running so the "N s left" countdown stays live.
    var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(testEndsAtMs) {
        while (testEndsAtMs != null) {
            nowMs = System.currentTimeMillis()
            delay(500)
        }
    }

    val remainingSec = testEndsAtMs?.let { deadline ->
        val msLeft = deadline - nowMs
        ((msLeft + 999) / 1000).toInt().coerceAtLeast(0)
    }
    val active = isDaemonActive(daemon)
    val assignedCount = data.assignments.size

    val statusLine = when (val d = daemon) {
        DaemonState.Idle -> "Stopped — starts automatically when an assigned app is in front"
        is DaemonState.Starting ->
            if (d.pkg == null) "Testing ${d.profile} — starting…" else "Starting ${d.profile} for ${appLabel(ctx, d.pkg)}…"
        is DaemonState.Running ->
            if (d.pkg == null) "Testing ${d.profile} — ${remainingSec ?: 0} s left"
            else "Running for ${appLabel(ctx, d.pkg)} with profile ${d.profile} (pid ${d.pid})"
        is DaemonState.Backoff -> "Retrying ${d.profile} (attempt ${d.attempt}): ${d.reason}"
        is DaemonState.Failed -> "Failed: ${d.reason}"
        is DaemonState.PanicStopped -> "Disabled by panic chord"
    }
    val assignedLine = "$assignedCount app${if (assignedCount == 1) "" else "s"} assigned" +
        if (assignedCount == 0) " — assign one in the Apps tab" else ""

    Box(modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (priv.source == PrivSource.NONE) {
                SetupCard()
            }
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Daemon", style = MaterialTheme.typography.titleMedium)
                    Text(statusLine)
                    Text(assignedLine, style = MaterialTheme.typography.bodySmall)
                    val runningProfile = (daemon as? DaemonState.Running)?.let { data.profile(it.profile) }
                    if (runningProfile?.touchOffsetEnabled == true) {
                        Text("Touch offset: ${runningProfile.touchDx},${runningProfile.touchDy}", style = MaterialTheme.typography.bodySmall)
                        Text("Panic: hold both back buttons 1 s", style = MaterialTheme.typography.bodySmall)
                    }
                    if (active) {
                        Button(onClick = {
                            val action = if (testEndsAtMs != null) DpadService.ACTION_STOP_TEST else DpadService.ACTION_STOP_DAEMON
                            DpadService.send(ctx, action)
                        }) { Text("Stop") }
                    } else {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            OutlinedButton(onClick = { menu = true }) { Text(testProfile.ifEmpty { "select profile" }) }
                            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                data.profiles.forEach { p ->
                                    DropdownMenuItem(text = { Text(p.name) }, onClick = { testProfile = p.name; menu = false })
                                }
                            }
                            Spacer(Modifier.width(8.dp))
                            Button(onClick = {
                                DpadService.send(ctx, DpadService.ACTION_TEST, profile = testProfile, seconds = 30)
                            }) { Text("Test for 30 s") }
                        }
                        Text(
                            "Starts the daemon with the selected profile for 30 seconds. All pad input is swallowed except the mapped keys.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Privilege", style = MaterialTheme.typography.titleMedium)
                    Text("${priv.source.label}" + if (priv.probing) " (probing…)" else "")
                    Text(priv.detail, style = MaterialTheme.typography.bodySmall)
                    Button(onClick = { DpadService.send(ctx, DpadService.ACTION_RECHECK) }) { Text("Re-check") }
                }
            }
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Status", style = MaterialTheme.typography.titleMedium)
                    Text("Binary: ${bin ?: "n/a"}", style = MaterialTheme.typography.bodySmall)
                    Text("Pad device: " + (pad.name?.let { "$it (${pad.idText})" } ?: "missing"))
                    Text("Foreground: ${fg ?: "none / launcher"}", style = MaterialTheme.typography.bodySmall)
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
                }
            }
        }
        SnackbarHost(snackbarHostState, Modifier.align(Alignment.BottomCenter))
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
