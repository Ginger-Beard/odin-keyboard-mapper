package com.dpad.mgr.ui

import android.app.Activity
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dpad.mgr.core.Calibration
import com.dpad.mgr.core.Store
import com.dpad.mgr.svc.DpadService
import kotlinx.coroutines.delay
import kotlin.math.hypot

/**
 * Calibrates the stylus/touch offset for one profile: step 1 taps five targets (center, then the
 * four corners) once each, per round, to compute a screen-space offset -- accepted deltas can be
 * accumulated over multiple rounds before proceeding -- converts it to panel units and saves it;
 * step 2 runs the daemon in TEST mode with the offset applied so the user can verify (and nudge)
 * it. Runs full-screen while the
 * Supervisor is suspended (daemon stopped, foreground changes ignored) so calibration taps are
 * never intercepted or shifted by a live daemon.
 *
 * Safety: the verify step (step 2) is confirm-or-revert. The profile's touch offset as it stood
 * BEFORE this calibration session ("previous") is remembered as soon as verify starts. Unless the
 * user explicitly taps "Keep", the offset reverts to that previous value -- either automatically
 * after a 20s countdown, or when the screen is left any other way (Back/Home -> onStop).
 */
class CalibrateActivity : ComponentActivity() {
    private var profileName: String = ""

    /** (touchOffsetEnabled, touchDx, touchDy) as they stood right before verify started; null
     *  until verify begins, since only the verify step is confirm-or-revert. */
    private var verifyPrevious: Triple<Boolean, Int, Int>? = null
    private var verifyKept: Boolean = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Store.init(this)
        profileName = intent.getStringExtra(EXTRA_PROFILE) ?: ""

        WindowCompat.setDecorFitsSystemWindows(window, false)
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller.hide(WindowInsetsCompat.Type.systemBars())
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE

        if (Store.data.value.profile(profileName) == null) {
            Log.w(TAG, "calibrate: profile '$profileName' not found, aborting")
            finish()
            return
        }

        setContent {
            MaterialTheme {
                Surface {
                    CalibrateScreen(
                        profileName,
                        onDone = { finish() },
                        onVerifyStarted = { prev -> verifyPrevious = prev },
                        onKeep = { verifyKept = true },
                    )
                }
            }
        }
    }

    // onStart/onStop (not onCreate/onDestroy) so leaving the screen any way — Done, back, or the
    // Home button — stops the test daemon and resumes the supervisor; returning re-suspends it.
    override fun onStart() {
        super.onStart()
        if (Store.data.value.profile(profileName) != null) DpadService.send(this, DpadService.ACTION_SUSPEND)
    }

    override fun onStop() {
        // Confirm-or-revert: leaving mid-verify without pressing Keep reverts to whatever the
        // offset was before this calibration session, so an unconfirmed (possibly bad) offset can
        // never survive the screen closing.
        val prev = verifyPrevious
        if (prev != null && !verifyKept) {
            Store.data.value.profile(profileName)?.let { cur ->
                Store.saveProfile(cur.copy(touchOffsetEnabled = prev.first, touchDx = prev.second, touchDy = prev.third), cur.name)
                Log.i(TAG, "calibrate: onStop reverted '$profileName' to enabled=${prev.first} dx=${prev.second} dy=${prev.third}")
            }
        }
        DpadService.send(this, DpadService.ACTION_STOP_TEST)
        DpadService.send(this, DpadService.ACTION_RESUME)
        super.onStop()
    }

    companion object {
        private const val TAG = "DpadMgr"
        const val EXTRA_PROFILE = "profile"
        const val VERIFY_COUNTDOWN_S = 20
    }
}

private enum class Phase { TARGETS, ROUND_RESULT, RESULT, VERIFY }

/** Order of the five calibration targets tapped each round: centre first, then the four corners
 *  (each inset 12% of the window width/height from its edge). */
private val TARGET_NAMES = listOf("center", "top-left", "top-right", "bottom-left", "bottom-right")

@Composable
private fun CalibrateScreen(
    profileName: String,
    onDone: () -> Unit,
    onVerifyStarted: (Triple<Boolean, Int, Int>) -> Unit,
    onKeep: () -> Unit,
) {
    val ctx = LocalContext.current
    val activity = ctx as Activity
    val view = LocalView.current
    val data by Store.data.collectAsStateWithLifecycle()

    // The offset as it stood before this calibration session touched anything -- captured once,
    // from the store's state at first composition (i.e. before any tap result is saved).
    val previous = remember(profileName) {
        val p = Store.data.value.profile(profileName)
        Triple(p?.touchOffsetEnabled ?: false, p?.touchDx ?: 0, p?.touchDy ?: 0)
    }

    // Rotation + natural (ROTATION_0) panel size, read once on entry (the user is asked to hold
    // the device the way they play before starting).
    val rotation = remember { activity.display?.rotation ?: Calibration.ROTATION_0 }
    val natSize = remember {
        val b = activity.windowManager.currentWindowMetrics.bounds
        Calibration.naturalSize(b.width(), b.height(), rotation)
    }
    var panelMaxXText by remember { mutableStateOf(natSize.natW.toString()) }
    var panelMaxYText by remember { mutableStateOf(natSize.natH.toString()) }
    var showAdvanced by remember { mutableStateOf(false) }
    val panelMaxX = panelMaxXText.toIntOrNull() ?: natSize.natW
    val panelMaxY = panelMaxYText.toIntOrNull() ?: natSize.natH

    var viewOffsetX by remember { mutableStateOf(0f) }
    var viewOffsetY by remember { mutableStateOf(0f) }
    var boxSize by remember { mutableStateOf(IntSize.Zero) }

    var phase by remember { mutableStateOf(Phase.TARGETS) }
    var targetIndex by remember { mutableIntStateOf(0) } // 0..4, current target within the round
    var roundTaps by remember { mutableStateOf(listOf<Offset>()) } // window coords, this round only
    var pool by remember { mutableStateOf(listOf<Offset>()) } // accepted deltas (screen px), all rounds
    var roundNumber by remember { mutableIntStateOf(1) }
    var roundTable by remember { mutableStateOf(listOf<Triple<String, Offset, Boolean>>()) } // name, delta, accepted
    var roundSpread by remember { mutableStateOf(0f) }
    var spreadMsg by remember { mutableStateOf<String?>(null) }
    var revertMsg by remember { mutableStateOf<String?>(null) }
    var resultDx by remember { mutableIntStateOf(0) }
    var resultDy by remember { mutableIntStateOf(0) }
    var spreadPx by remember { mutableStateOf(0f) }
    var verifyTap by remember { mutableStateOf<Offset?>(null) } // window coords
    var residual by remember { mutableStateOf<Offset?>(null) }

    var kept by remember { mutableStateOf(false) }
    var countdown by remember { mutableIntStateOf(CalibrateActivity.VERIFY_COUNTDOWN_S) }

    val dotCenter = Offset(
        boxSize.width / 2f + viewOffsetX,
        boxSize.height / 2f + viewOffsetY,
    )

    // The five calibration targets, in canvas-local and window (screen) coordinates. Corners are
    // inset 12% of the window width/height from their respective edges.
    val insetX = boxSize.width * 0.12f
    val insetY = boxSize.height * 0.12f
    val targetsLocal = listOf(
        Offset(boxSize.width / 2f, boxSize.height / 2f),
        Offset(insetX, insetY),
        Offset(boxSize.width - insetX, insetY),
        Offset(insetX, boxSize.height - insetY),
        Offset(boxSize.width - insetX, boxSize.height - insetY),
    )
    val targetsScreen = targetsLocal.map { Offset(it.x + viewOffsetX, it.y + viewOffsetY) }

    fun currentProfile() = data.profile(profileName)

    fun saveOffset(dx: Int, dy: Int, liveUpdate: Boolean) {
        val cur = currentProfile() ?: return
        val updated = cur.copy(touchOffsetEnabled = true, touchDx = dx, touchDy = dy)
        Store.saveProfile(updated, cur.name)
        if (liveUpdate) DpadService.send(ctx, DpadService.ACTION_UPDATE_CONFIG_LIVE, profile = updated.name)
    }

    fun revertToPrevious(reason: String) {
        val cur = currentProfile()
        if (cur != null) {
            Store.saveProfile(cur.copy(touchOffsetEnabled = previous.first, touchDx = previous.second, touchDy = previous.third), cur.name)
        }
        DpadService.send(ctx, DpadService.ACTION_STOP_TEST)
        revertMsg = reason
        kept = false
        phase = Phase.TARGETS
        targetIndex = 0
        roundTaps = emptyList()
        pool = emptyList()
        roundNumber = 1
        spreadMsg = null
        verifyTap = null
        residual = null
    }

    fun handleTap(sx: Float, sy: Float) {
        when (phase) {
            Phase.TARGETS -> {
                if (roundTaps.isEmpty()) revertMsg = null
                if (targetIndex >= 5) return
                roundTaps = roundTaps + Offset(sx, sy)
                targetIndex = roundTaps.size
                if (roundTaps.size == 5) {
                    // delta_i = target_i - tap_i, screen px.
                    val deltas = targetsScreen.zip(roundTaps) { t, p -> Offset(t.x - p.x, t.y - p.y) }
                    val meanX = deltas.map { it.x }.average().toFloat()
                    val meanY = deltas.map { it.y }.average().toFloat()
                    val acceptedFlags = deltas.map { hypot((it.x - meanX).toDouble(), (it.y - meanY).toDouble()) <= 40.0 }
                    val accepted = deltas.filterIndexed { i, _ -> acceptedFlags[i] }
                    if (accepted.size < 3) {
                        spreadMsg = "Too much spread, try again"
                        roundTaps = emptyList()
                        targetIndex = 0
                    } else {
                        spreadMsg = null
                        val ax = accepted.map { it.x }.average().toFloat()
                        val ay = accepted.map { it.y }.average().toFloat()
                        val spread = accepted.maxOf { hypot((it.x - ax).toDouble(), (it.y - ay).toDouble()) }.toFloat()
                        roundTable = TARGET_NAMES.indices.map { i -> Triple(TARGET_NAMES[i], deltas[i], acceptedFlags[i]) }
                        roundSpread = spread
                        pool = pool + accepted
                        roundTaps = emptyList()
                        targetIndex = 0
                        phase = Phase.ROUND_RESULT
                    }
                }
            }
            Phase.ROUND_RESULT -> {} // wait for Another round / Use this
            Phase.RESULT -> {} // wait for the Continue button
            Phase.VERIFY -> {
                verifyTap = Offset(sx, sy)
                residual = Offset(dotCenter.x - sx, dotCenter.y - sy)
            }
        }
    }

    // Starts the verify test run once, the moment this phase is entered, and remembers the
    // pre-calibration offset so the Activity can revert to it in onStop if Keep is never pressed.
    LaunchedEffect(phase) {
        if (phase == Phase.VERIFY) {
            countdown = CalibrateActivity.VERIFY_COUNTDOWN_S
            kept = false
            DpadService.send(ctx, DpadService.ACTION_TEST, profile = profileName, seconds = 0)
            onVerifyStarted(previous)
        }
    }

    // Confirm-or-revert countdown: (re)started whenever we're in VERIFY and not yet kept, e.g.
    // right after entering VERIFY, or again after a nudge/Reset un-confirms a previously kept
    // offset. Cancelled the moment `kept` becomes true or the phase changes away from VERIFY.
    LaunchedEffect(phase, kept) {
        if (phase == Phase.VERIFY && !kept) {
            while (countdown > 0 && !kept) {
                delay(1000)
                if (!kept) countdown--
            }
            if (!kept && phase == Phase.VERIFY) {
                revertToPrevious("Reverted: no confirmation")
            }
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .onGloballyPositioned { coords ->
                boxSize = coords.size
                val loc = IntArray(2)
                view.getLocationOnScreen(loc)
                viewOffsetX = loc[0].toFloat(); viewOffsetY = loc[1].toFloat()
            }
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    handleTap(down.position.x + viewOffsetX, down.position.y + viewOffsetY)
                }
            },
    ) {
        Canvas(Modifier.fillMaxSize()) {
            when (phase) {
                Phase.TARGETS -> {
                    val t = targetsLocal.getOrElse(targetIndex) { Offset(size.width / 2f, size.height / 2f) }
                    drawCircle(color = Color.White.copy(alpha = 0.25f), radius = 18.dp.toPx(), center = t, style = Stroke(width = 2.dp.toPx()))
                    drawCircle(color = Color.Red, radius = 6.dp.toPx(), center = t)
                }
                Phase.VERIFY -> {
                    val center = Offset(size.width / 2f, size.height / 2f)
                    drawCircle(color = Color.Red, radius = 6.dp.toPx(), center = center)
                    verifyTap?.let { t ->
                        val p = Offset(t.x - viewOffsetX, t.y - viewOffsetY)
                        val len = 14.dp.toPx()
                        drawLine(Color.Green, Offset(p.x - len, p.y), Offset(p.x + len, p.y), strokeWidth = 3f)
                        drawLine(Color.Green, Offset(p.x, p.y - len), Offset(p.x, p.y + len), strokeWidth = 3f)
                    }
                }
                else -> {}
            }
        }

        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Stylus offset calibration — $profileName", color = Color.White, style = MaterialTheme.typography.titleMedium)
            Text("Hold the device the way you play.", color = Color.White, style = MaterialTheme.typography.bodyMedium)
            when (phase) {
                Phase.TARGETS -> {
                    Text("Tap target ${targetIndex + 1}/5 — ${TARGET_NAMES[targetIndex]}", color = Color.White)
                    spreadMsg?.let { Text(it, color = Color.Yellow) }
                    revertMsg?.let { Text(it, color = Color.Red, style = MaterialTheme.typography.bodyMedium) }
                }
                Phase.ROUND_RESULT -> {
                    Text("Round $roundNumber — spread ${"%.1f".format(roundSpread)} px", color = Color.White)
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Row {
                            Text("Target", color = Color.White, modifier = Modifier.width(96.dp), style = MaterialTheme.typography.bodySmall)
                            Text("dx", color = Color.White, modifier = Modifier.width(56.dp), style = MaterialTheme.typography.bodySmall)
                            Text("dy", color = Color.White, modifier = Modifier.width(56.dp), style = MaterialTheme.typography.bodySmall)
                        }
                        for ((name, d, accepted) in roundTable) {
                            val c = if (accepted) Color.White else Color.Gray
                            Row {
                                Text(name, color = c, modifier = Modifier.width(96.dp), style = MaterialTheme.typography.bodySmall)
                                Text("%.1f".format(d.x), color = c, modifier = Modifier.width(56.dp), style = MaterialTheme.typography.bodySmall)
                                Text("%.1f".format(d.y), color = c, modifier = Modifier.width(56.dp), style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        OutlinedButton(onClick = { roundNumber++; phase = Phase.TARGETS }) { Text("Another round") }
                        Button(onClick = {
                            val meanX = pool.map { it.x }.average().toFloat()
                            val meanY = pool.map { it.y }.average().toFloat()
                            val finalSpread = pool.maxOf { hypot((it.x - meanX).toDouble(), (it.y - meanY).toDouble()) }.toFloat()
                            val (dx, dy) = Calibration.toPanelOffset(meanX, meanY, rotation, natSize.natW, natSize.natH, panelMaxX, panelMaxY)
                            resultDx = dx; resultDy = dy; spreadPx = finalSpread
                            saveOffset(dx, dy, liveUpdate = false)
                            phase = Phase.RESULT
                        }) { Text("Use this") }
                    }
                }
                Phase.RESULT -> {
                    Text("Computed offset: dx=$resultDx, dy=$resultDy (panel units)", color = Color.White)
                    Text("Spread: ${"%.1f".format(spreadPx)} px", color = Color.White)
                    Button(onClick = { phase = Phase.VERIFY }) { Text("Continue to verify") }
                }
                Phase.VERIFY -> {
                    Text("Step 2: verify — tap the dot again", color = Color.White)
                    val live = currentProfile()
                    Text("Offset: dx=${live?.touchDx ?: 0}, dy=${live?.touchDy ?: 0} (panel units)", color = Color.White)
                    residual?.let { r ->
                        Text("Residual: ${"%.1f".format(r.x)}, ${"%.1f".format(r.y)} px", color = Color.White)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        NudgeButton("X-1") { nudge(ctx, profileName, data, -1f, 0f, rotation, natSize, panelMaxX, panelMaxY); countdown = CalibrateActivity.VERIFY_COUNTDOWN_S; kept = false }
                        NudgeButton("X+1") { nudge(ctx, profileName, data, 1f, 0f, rotation, natSize, panelMaxX, panelMaxY); countdown = CalibrateActivity.VERIFY_COUNTDOWN_S; kept = false }
                        NudgeButton("Y-1") { nudge(ctx, profileName, data, 0f, -1f, rotation, natSize, panelMaxX, panelMaxY); countdown = CalibrateActivity.VERIFY_COUNTDOWN_S; kept = false }
                        NudgeButton("Y+1") { nudge(ctx, profileName, data, 0f, 1f, rotation, natSize, panelMaxX, panelMaxY); countdown = CalibrateActivity.VERIFY_COUNTDOWN_S; kept = false }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        OutlinedButton(onClick = {
                            saveOffset(resultDx, resultDy, liveUpdate = true)
                            countdown = CalibrateActivity.VERIFY_COUNTDOWN_S
                            kept = false
                        }) { Text("Reset") }
                        Button(enabled = kept, onClick = onDone) { Text("Done") }
                    }
                    if (!kept) {
                        Button(
                            onClick = { kept = true; onKeep() },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2E7D32)),
                        ) { Text("Keep these settings ($countdown)") }
                        Text(
                            "Unconfirmed — reverts automatically if you don't tap Keep.",
                            color = Color.Yellow, style = MaterialTheme.typography.bodySmall,
                        )
                    } else {
                        Text("Kept. Tap Done to finish.", color = Color.Green, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
            if (phase == Phase.TARGETS) {
                TextButton(onClick = { showAdvanced = !showAdvanced }) {
                    Text(if (showAdvanced) "Hide advanced" else "Advanced…", color = Color.White)
                }
                if (showAdvanced) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(panelMaxXText, { panelMaxXText = it }, Modifier.width(120.dp), label = { Text("Panel max X") }, singleLine = true)
                        OutlinedTextField(panelMaxYText, { panelMaxYText = it }, Modifier.width(120.dp), label = { Text("Panel max Y") }, singleLine = true)
                    }
                }
            }
        }
    }
}

@Composable
private fun NudgeButton(label: String, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick) { Text(label) }
}

private fun nudge(
    ctx: android.content.Context, profileName: String, data: com.dpad.mgr.core.AppData,
    dsx: Float, dsy: Float, rotation: Int, natSize: Calibration.NaturalSize, panelMaxX: Int, panelMaxY: Int,
) {
    val cur = data.profile(profileName) ?: return
    val (ddx, ddy) = Calibration.toPanelOffset(dsx, dsy, rotation, natSize.natW, natSize.natH, panelMaxX, panelMaxY)
    val updated = cur.copy(touchOffsetEnabled = true, touchDx = cur.touchDx + ddx, touchDy = cur.touchDy + ddy)
    Store.saveProfile(updated, cur.name)
    DpadService.send(ctx, DpadService.ACTION_UPDATE_CONFIG_LIVE, profile = updated.name)
}
