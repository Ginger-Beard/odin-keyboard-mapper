package com.dpad.mgr.ui

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dpad.mgr.core.DaemonState
import com.dpad.mgr.core.SourceNames
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

private fun isIgnoringBatteryOptimizations(ctx: Context): Boolean {
    val pm = ctx.getSystemService(PowerManager::class.java) ?: return true
    return pm.isIgnoringBatteryOptimizations(ctx.packageName)
}

private fun isDaemonActive(d: DaemonState): Boolean = when (d) {
    is DaemonState.Running, is DaemonState.Testing, is DaemonState.Starting, is DaemonState.Backoff -> true
    else -> false
}

/** Accent color for the hero card, matched to what the status means for the user. */
private enum class HeroTone { INFO, GOOD, ACTIVE, WARN }

@Composable
fun StatusScreen(modifier: Modifier = Modifier, onOpenApps: () -> Unit = {}) {
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
    var showAllowQDialog by remember { mutableStateOf(false) }
    var showAdvanced by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }

    // Battery-optimization exemption state, refreshed on resume (the user grants it in a
    // Settings dialog that returns here, so a one-shot check at composition wouldn't pick it up).
    var batteryExempt by remember { mutableStateOf(isIgnoringBatteryOptimizations(ctx)) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) batteryExempt = isIgnoringBatteryOptimizations(ctx)
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

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
    val assignedCount = data.assignments.size

    Box(modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (priv.source == PrivSource.NONE) {
                HeroCard(
                    icon = Icons.Default.Info,
                    tone = HeroTone.INFO,
                    title = "Needs setup",
                    subtitle = "Shizuku is not connected. Follow the steps below.",
                )
                SetupCard()
            } else {
                when (val d = daemon) {
                    DaemonState.Idle -> HeroCard(
                        icon = Icons.Default.CheckCircle,
                        tone = HeroTone.GOOD,
                        title = "Ready",
                        subtitle = "Mapping starts automatically when an assigned app opens.",
                        extraLines = listOf("$assignedCount app${if (assignedCount == 1) "" else "s"} assigned"),
                        linkLabel = if (assignedCount == 0) "Assign apps" else null,
                        onLink = if (assignedCount == 0) onOpenApps else null,
                    )
                    is DaemonState.Running -> {
                        val profile = data.profile(d.profile)
                        val chordLine = profile?.panicChord?.let { "Hold ${SourceNames.chordLabel(it)} for 1 s to pause" }
                        HeroCard(
                            icon = Icons.Default.PlayArrow,
                            tone = HeroTone.ACTIVE,
                            title = "Mapping ${appLabel(ctx, d.pkg)}",
                            subtitle = "Profile: ${d.profile}",
                            extraLines = listOfNotNull(chordLine),
                            actionLabel = "Pause",
                            onAction = { DpadService.send(ctx, DpadService.ACTION_STOP_DAEMON) },
                        )
                    }
                    is DaemonState.Testing -> HeroCard(
                        icon = Icons.Default.PlayArrow,
                        tone = HeroTone.ACTIVE,
                        title = "Testing ${d.profile}",
                        subtitle = "${remainingSec ?: 0} s left",
                        actionLabel = "Stop",
                        onAction = { DpadService.send(ctx, DpadService.ACTION_STOP_TEST) },
                    )
                    DaemonState.Starting -> HeroCard(
                        icon = Icons.Default.PlayArrow,
                        tone = HeroTone.ACTIVE,
                        title = "Starting",
                        subtitle = "Setting up the mapping service.",
                    )
                    else -> {
                        val subtitle = when (d) {
                            is DaemonState.Failed -> "The mapping service stopped and needs a restart."
                            is DaemonState.PanicStopped -> "Mapping was paused by the panic shortcut."
                            is DaemonState.Backoff -> "The mapping service is restarting itself."
                            else -> "The mapping service isn't running."
                        }
                        HeroCard(
                            icon = Icons.Default.Warning,
                            tone = HeroTone.WARN,
                            title = "Something went wrong",
                            subtitle = subtitle,
                            actionLabel = "Restart",
                            onAction = { DpadService.send(ctx, DpadService.ACTION_RESTART_DAEMON) },
                        )
                    }
                }
            }

            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Run in background", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Lets mapping keep working even when you're not looking at this app.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            if (batteryExempt) "Allowed" else "Not allowed",
                            Modifier.weight(1f),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        if (!batteryExempt) {
                            Button(onClick = {
                                val intent = Intent(
                                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                                    Uri.parse("package:${ctx.packageName}"),
                                )
                                ctx.startActivity(intent)
                            }) { Text("Allow running in background") }
                        }
                    }
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("Allow Q key (see Profiles)", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                        Switch(
                            checked = data.allowQ,
                            onCheckedChange = { v ->
                                if (v) showAllowQDialog = true
                                else DpadService.send(ctx, DpadService.ACTION_DISALLOW_Q)
                            },
                        )
                    }
                }
            }

            TextButton(onClick = { showAdvanced = !showAdvanced }) { Text("Advanced") }

            if (showAdvanced) {
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
                        Text("Daemon: ${daemon.label}", style = MaterialTheme.typography.bodySmall)
                        Text("Binary: ${bin ?: "n/a"}", style = MaterialTheme.typography.bodySmall)
                        Text("Controller: " + (pad.name?.let { "$it (${pad.idText})" } ?: "missing"))
                        Text("Foreground: ${fg ?: "none / launcher"}", style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = { DpadService.send(ctx, DpadService.ACTION_RESTART_DAEMON) }) {
                            Text("Restart daemon")
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
                    }
                }
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Test a profile", style = MaterialTheme.typography.titleMedium)
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
        }
        SnackbarHost(snackbarHostState, Modifier.align(Alignment.BottomCenter))
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

/** Large status card at the top of the screen: one headline, one sentence, optional extra lines,
 *  an optional link (e.g. "Assign apps") and an optional single action button. */
@Composable
private fun HeroCard(
    icon: ImageVector,
    tone: HeroTone,
    title: String,
    subtitle: String,
    extraLines: List<String> = emptyList(),
    linkLabel: String? = null,
    onLink: (() -> Unit)? = null,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val accent = when (tone) {
        HeroTone.INFO -> MaterialTheme.colorScheme.primary
        HeroTone.GOOD -> Color(0xFF2E7D32)
        HeroTone.ACTIVE -> MaterialTheme.colorScheme.primary
        HeroTone.WARN -> Color(0xFFB00020)
    }
    Card(modifier.fillMaxWidth()) {
        Row(Modifier.height(IntrinsicSize.Min)) {
            Box(Modifier.fillMaxHeight().width(6.dp).background(accent))
            Column(Modifier.padding(20.dp).weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(icon, contentDescription = null, tint = accent, modifier = Modifier.size(32.dp))
                    Spacer(Modifier.width(12.dp))
                    Text(title, style = MaterialTheme.typography.headlineMedium)
                }
                Text(subtitle, style = MaterialTheme.typography.bodyLarge)
                extraLines.forEach { Text(it, style = MaterialTheme.typography.bodyMedium) }
                if (linkLabel != null && onLink != null) {
                    TextButton(onClick = onLink, contentPadding = PaddingValues(0.dp)) { Text(linkLabel) }
                }
                if (actionLabel != null && onAction != null) {
                    Spacer(Modifier.height(4.dp))
                    Button(onClick = onAction) { Text(actionLabel) }
                }
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
                "The app runs in the background; you don't need to open it before launching a game.",
                style = MaterialTheme.typography.bodySmall,
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
