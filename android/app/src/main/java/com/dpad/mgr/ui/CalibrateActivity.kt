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
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
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
import kotlin.math.roundToInt

/**
 * Calibrates the stylus/touch offset for one profile: step 1 ("drag to align") shows one target
 * dot at a time -- the user puts the stylus down near it, then without lifting slides until an
 * on-screen crosshair (which follows the raw touch position) is centered on the dot and lifts;
 * the drag's end-minus-start delta is one sample. Samples can be collected at multiple dot
 * positions (center, then the four corners) and averaged (plain mean, no outlier rejection) into
 * a screen-space offset, which is converted to panel units and saved; step 2 runs the daemon in
 * TEST mode with the offset applied so the user can verify (and nudge) it. Runs full-screen while the
 * Supervisor is suspended (daemon stopped, foreground changes ignored) so calibration taps are
 * never intercepted or shifted by a live daemon.
 *
 * Safety: the verify step (step 2) is confirm-or-revert. The profile's touch offset as it stood
 * BEFORE this calibration session ("previous") is remembered as soon as verify starts. Unless the
 * user explicitly taps "Keep", the offset reverts to that previous value -- either automatically
 * after a 45s countdown, or when the screen is left any other way (Back/Home -> onStop).
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
            // Forced dark scheme: this screen is a full-screen black calibration canvas
            // regardless of the app's (light) theme, so Material3 tokens read correctly on it.
            MaterialTheme(colorScheme = darkColorScheme()) {
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
        const val VERIFY_COUNTDOWN_S = 45
    }
}

private enum class Phase { DRAG, VERIFY }

/** One verify-phase touch: [pos] is the corrected touch position (local canvas px, updated live
 *  while dragging), [target] is the nearest verify-grid target, [residual] is target-pos in px. */
private data class VerifyMarker(val pos: Offset, val target: Offset, val residual: Offset)

private fun nearestTarget(p: Offset, targets: List<Offset>): Offset =
    targets.minByOrNull { (it - p).getDistance() } ?: p

/** Formats a px delta with an explicit sign, rounded to the nearest int, for the sample chips. */
private fun fmtSigned(v: Float): String {
    val r = v.roundToInt()
    return if (r >= 0) "+$r" else "$r"
}

private const val VERIFY_MARKER_CAP = 12

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

    var phase by remember { mutableStateOf(Phase.DRAG) }
    var dotIndex by remember { mutableIntStateOf(0) } // cycles through targetsLocal (center, then the 4 corners)
    var samples by remember { mutableStateOf(listOf<Offset>()) } // p1-p0 deltas (screen px), all dot positions
    var dragP0 by remember { mutableStateOf<Offset?>(null) } // window coords, start of the in-progress drag
    var dragRaw by remember { mutableStateOf<Offset?>(null) } // window coords, current raw touch pos while dragging
    var revertMsg by remember { mutableStateOf<String?>(null) }
    var resultDx by remember { mutableIntStateOf(0) }
    var resultDy by remember { mutableIntStateOf(0) }

    // Verify phase: every touch leaves a marker (local canvas coords), capped at the newest 12.
    var markers by remember { mutableStateOf(listOf<VerifyMarker>()) }
    var dragTrail by remember { mutableStateOf(listOf<Offset>()) } // local coords, in-progress drag only
    var liveDragPos by remember { mutableStateOf<Offset?>(null) } // local coords, live crosshair while dragging
    var lastResidualVerify by remember { mutableStateOf<Offset?>(null) }

    var kept by remember { mutableStateOf(false) }
    var countdown by remember { mutableIntStateOf(CalibrateActivity.VERIFY_COUNTDOWN_S) }

    // The five positions the drag-align dot cycles through, in canvas-local and window (screen)
    // coordinates. Corners are inset 12% of the window width/height from their respective edges.
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

    // Verify phase: a 3x3 grid of targets at 15/50/85% of width and height, in local canvas coords.
    val verifyGridFracs = listOf(0.15f, 0.5f, 0.85f)
    val targetsVerifyLocal = verifyGridFracs.flatMap { fy -> verifyGridFracs.map { fx -> Offset(boxSize.width * fx, boxSize.height * fy) } }

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
        phase = Phase.DRAG
        dotIndex = 0
        samples = emptyList()
        dragP0 = null
        dragRaw = null
        markers = emptyList()
        dragTrail = emptyList()
        liveDragPos = null
        lastResidualVerify = null
    }

    // Starts the verify test run once, the moment this phase is entered, and remembers the
    // pre-calibration offset so the Activity can revert to it in onStop if Keep is never pressed.
    LaunchedEffect(phase) {
        if (phase == Phase.VERIFY) {
            countdown = CalibrateActivity.VERIFY_COUNTDOWN_S
            kept = false
            markers = emptyList()
            dragTrail = emptyList()
            liveDragPos = null
            lastResidualVerify = null
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

    val primaryColor = MaterialTheme.colorScheme.primary
    val tertiaryColor = MaterialTheme.colorScheme.tertiary
    val outlineColor = MaterialTheme.colorScheme.outline
    val onSurfaceColor = MaterialTheme.colorScheme.onSurface
    val errorColor = MaterialTheme.colorScheme.error

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
            .pointerInput(phase) {
                when (phase) {
                    Phase.DRAG -> awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        revertMsg = null
                        val p0 = Offset(down.position.x + viewOffsetX, down.position.y + viewOffsetY)
                        dragP0 = p0
                        dragRaw = p0
                        val id = down.id
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == id } ?: break
                            val raw = Offset(change.position.x + viewOffsetX, change.position.y + viewOffsetY)
                            dragRaw = raw
                            if (!change.pressed) {
                                samples = samples + Offset(raw.x - p0.x, raw.y - p0.y)
                                dragP0 = null
                                dragRaw = null
                                break
                            }
                        }
                    }
                    Phase.VERIFY -> awaitEachGesture {
                        // ACTION_DOWN immediately drops a marker at the (already offset-corrected)
                        // touch position; while held, the same marker follows the finger (live
                        // crosshair + drag trail) and its position is finalized on ACTION_UP. Taps
                        // never touch the countdown/kept state -- only nudges and Clear marks do.
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val downTarget = nearestTarget(down.position, targetsVerifyLocal)
                        val downResidual = Offset(downTarget.x - down.position.x, downTarget.y - down.position.y)
                        markers = (markers + VerifyMarker(down.position, downTarget, downResidual))
                            .let { if (it.size > VERIFY_MARKER_CAP) it.takeLast(VERIFY_MARKER_CAP) else it }
                        lastResidualVerify = downResidual
                        dragTrail = listOf(down.position)
                        liveDragPos = down.position
                        val id = down.id
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == id } ?: break
                            val t = nearestTarget(change.position, targetsVerifyLocal)
                            val r = Offset(t.x - change.position.x, t.y - change.position.y)
                            markers = markers.dropLast(1) + VerifyMarker(change.position, t, r)
                            lastResidualVerify = r
                            if (change.pressed) {
                                dragTrail = (dragTrail + change.position).let { if (it.size > 300) it.takeLast(300) else it }
                                liveDragPos = change.position
                            } else {
                                dragTrail = emptyList()
                                liveDragPos = null
                                break
                            }
                        }
                    }
                }
            },
    ) {
        Canvas(Modifier.fillMaxSize()) {
            when (phase) {
                Phase.DRAG -> {
                    val t = targetsLocal[dotIndex % targetsLocal.size]
                    drawCircle(color = primaryColor.copy(alpha = 0.5f), radius = 24.dp.toPx(), center = t, style = Stroke(width = 2.dp.toPx()))
                    drawCircle(color = primaryColor, radius = 6.dp.toPx(), center = t)
                    dragRaw?.let { raw ->
                        val p = Offset(raw.x - viewOffsetX, raw.y - viewOffsetY)
                        val len = 14.dp.toPx()
                        drawLine(tertiaryColor, Offset(p.x - len, p.y), Offset(p.x + len, p.y), strokeWidth = 3f)
                        drawLine(tertiaryColor, Offset(p.x, p.y - len), Offset(p.x, p.y + len), strokeWidth = 3f)
                    }
                }
                Phase.VERIFY -> {
                    val ringR = 8.dp.toPx()
                    val ringStroke = Stroke(width = 1.5.dp.toPx())
                    val centerIdx = targetsVerifyLocal.size / 2
                    targetsVerifyLocal.forEachIndexed { i, t ->
                        val scale = if (i == centerIdx) 1.3f else 1f
                        drawCircle(color = primaryColor.copy(alpha = 0.6f), radius = ringR * scale, center = t, style = ringStroke)
                        if (i == centerIdx) drawCircle(color = primaryColor, radius = 3.dp.toPx(), center = t)
                    }
                    if (dragTrail.size >= 2) {
                        val path = Path().apply {
                            moveTo(dragTrail.first().x, dragTrail.first().y)
                            dragTrail.drop(1).forEach { lineTo(it.x, it.y) }
                        }
                        drawPath(path, color = tertiaryColor.copy(alpha = 0.8f), style = Stroke(width = 3f))
                    }
                    val armLen = 6.dp.toPx()
                    val n = markers.size
                    markers.forEachIndexed { idx, m ->
                        val age = (idx + 1f) / n.coerceAtLeast(1) // 0..1, newest marker = 1
                        val alpha = 0.25f + 0.75f * age
                        drawLine(outlineColor.copy(alpha = 0.4f * alpha), m.pos, m.target, strokeWidth = 1.5f)
                        drawLine(tertiaryColor.copy(alpha = alpha), Offset(m.pos.x - armLen, m.pos.y), Offset(m.pos.x + armLen, m.pos.y), strokeWidth = 3f)
                        drawLine(tertiaryColor.copy(alpha = alpha), Offset(m.pos.x, m.pos.y - armLen), Offset(m.pos.x, m.pos.y + armLen), strokeWidth = 3f)
                    }
                    liveDragPos?.let { p ->
                        drawLine(tertiaryColor, Offset(p.x - armLen, p.y), Offset(p.x + armLen, p.y), strokeWidth = 3f)
                        drawLine(tertiaryColor, Offset(p.x, p.y - armLen), Offset(p.x, p.y + armLen), strokeWidth = 3f)
                    }
                }
            }
        }

        // DRAG: one short instruction line, top center; nothing else on the canvas itself.
        if (phase == Phase.DRAG) {
            Text(
                "Slide the crosshair onto the dot, then lift.",
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 16.dp),
                color = onSurfaceColor.copy(alpha = 0.8f),
                style = MaterialTheme.typography.bodyLarge,
            )
            revertMsg?.let {
                Text(
                    it,
                    modifier = Modifier.align(Alignment.TopCenter).padding(top = 52.dp),
                    color = errorColor,
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            // Bottom bar: sample chips + Add/Redo/Continue (once there's a sample), plus the
            // (collapsed by default) advanced panel-size override.
            Column(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.55f))
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (samples.isNotEmpty()) {
                    val meanX = samples.map { it.x }.average().toFloat()
                    val meanY = samples.map { it.y }.average().toFloat()
                    val spread = samples.maxOf { hypot((it.x - meanX).toDouble(), (it.y - meanY).toDouble()) }.toFloat()

                    Row(
                        Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        samples.forEachIndexed { i, d ->
                            SampleChip("Sample ${i + 1}: ${fmtSigned(d.x)}, ${fmtSigned(d.y)}")
                        }
                    }
                    if (samples.size >= 2) {
                        Text("Spread: ${spread.roundToInt()} px", color = onSurfaceColor.copy(alpha = 0.8f), style = MaterialTheme.typography.bodySmall)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            modifier = Modifier.heightIn(min = 48.dp),
                            onClick = { dotIndex = (dotIndex + 1) % targetsLocal.size },
                        ) { Text("Add position") }
                        OutlinedButton(
                            modifier = Modifier.heightIn(min = 48.dp),
                            onClick = { samples = samples.dropLast(1) },
                        ) { Text("Redo") }
                        Button(
                            modifier = Modifier.heightIn(min = 48.dp),
                            onClick = {
                                val (dx, dy) = Calibration.toPanelOffset(meanX, meanY, rotation, natSize.natW, natSize.natH, panelMaxX, panelMaxY)
                                resultDx = dx; resultDy = dy
                                saveOffset(dx, dy, liveUpdate = false)
                                phase = Phase.VERIFY
                            },
                        ) { Text("Continue") }
                    }
                }
                TextButton(onClick = { showAdvanced = !showAdvanced }) {
                    Text(if (showAdvanced) "Hide advanced" else "Advanced…", color = onSurfaceColor.copy(alpha = 0.8f))
                }
                if (showAdvanced) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(panelMaxXText, { panelMaxXText = it }, Modifier.width(120.dp), label = { Text("Panel max X") }, singleLine = true)
                        OutlinedTextField(panelMaxYText, { panelMaxYText = it }, Modifier.width(120.dp), label = { Text("Panel max Y") }, singleLine = true)
                    }
                }
            }
        }

        // VERIFY: one compact readout pill top-center, and a bottom bar with nudge / Reset / Keep.
        if (phase == Phase.VERIFY) {
            val live = currentProfile()
            val offsetPart = "Offset ${live?.touchDx ?: 0}, ${live?.touchDy ?: 0}"
            val lastPart = lastResidualVerify?.let { "Last ${it.x.roundToInt()}, ${it.y.roundToInt()}" }
            val avgPart = if (markers.isNotEmpty()) {
                val meanR = markers.map { hypot(it.residual.x.toDouble(), it.residual.y.toDouble()) }.average()
                "Avg ${meanR.roundToInt()} px"
            } else {
                null
            }
            val pillText = listOfNotNull(offsetPart, lastPart, avgPart).joinToString(" · ")

            Row(
                Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 16.dp)
                    .clip(RoundedCornerShape(50))
                    .background(Color.Black.copy(alpha = 0.55f))
                    .padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(pillText, color = onSurfaceColor.copy(alpha = 0.8f), style = MaterialTheme.typography.bodyMedium)
                TextButton(onClick = {
                    markers = emptyList()
                    lastResidualVerify = null
                    countdown = CalibrateActivity.VERIFY_COUNTDOWN_S
                    kept = false
                }) { Text("Clear") }
            }

            Box(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.55f))
                    .padding(horizontal = 16.dp, vertical = 12.dp),
            ) {
                Row(
                    Modifier.align(Alignment.CenterStart),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    NudgeButton("←") { nudge(ctx, profileName, data, -1f, 0f, rotation, natSize, panelMaxX, panelMaxY); countdown = CalibrateActivity.VERIFY_COUNTDOWN_S; kept = false }
                    NudgeButton("→") { nudge(ctx, profileName, data, 1f, 0f, rotation, natSize, panelMaxX, panelMaxY); countdown = CalibrateActivity.VERIFY_COUNTDOWN_S; kept = false }
                    NudgeButton("↑") { nudge(ctx, profileName, data, 0f, -1f, rotation, natSize, panelMaxX, panelMaxY); countdown = CalibrateActivity.VERIFY_COUNTDOWN_S; kept = false }
                    NudgeButton("↓") { nudge(ctx, profileName, data, 0f, 1f, rotation, natSize, panelMaxX, panelMaxY); countdown = CalibrateActivity.VERIFY_COUNTDOWN_S; kept = false }
                }
                OutlinedButton(
                    modifier = Modifier.align(Alignment.Center).heightIn(min = 48.dp),
                    onClick = {
                        saveOffset(resultDx, resultDy, liveUpdate = true)
                        countdown = CalibrateActivity.VERIFY_COUNTDOWN_S
                        kept = false
                    },
                ) { Text("Reset") }
                Button(
                    modifier = Modifier.align(Alignment.CenterEnd).heightIn(min = 48.dp),
                    onClick = {
                        if (!kept) {
                            kept = true
                            onKeep()
                        } else {
                            onDone()
                        }
                    },
                ) { Text(if (kept) "Done" else "Keep ($countdown)") }
            }
        }
    }
}

@Composable
private fun SampleChip(text: String) {
    Surface(shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.surfaceVariant) {
        Text(
            text,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun NudgeButton(label: String, onClick: () -> Unit) {
    OutlinedButton(
        modifier = Modifier.size(56.dp),
        contentPadding = PaddingValues(0.dp),
        onClick = onClick,
    ) { Text(label, style = MaterialTheme.typography.titleLarge) }
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
