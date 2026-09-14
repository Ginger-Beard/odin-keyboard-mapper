package com.dpad.mgr.core

import kotlin.math.roundToInt

/**
 * Pure geometry for stylus/touch offset calibration: screen-space taps -> touch.offset dx/dy
 * deltas. Offsets are now DISPLAY-space (identity with the screen delta -- see [toPanelOffset]);
 * [rotateDelta]/[rotateDeltaInverse] remain only for the one-time panel->display migration in
 * [Store]. Rotation constants mirror android.view.Surface.ROTATION_*.
 */
object Calibration {
    /** Hard clamp for Profile.touchDx/touchDy (display px), enforced wherever they are set. */
    const val MAX_OFFSET = 200

    const val ROTATION_0 = 0
    const val ROTATION_90 = 1
    const val ROTATION_180 = 2
    const val ROTATION_270 = 3

    data class NaturalSize(val natW: Int, val natH: Int)

    /** Natural (ROTATION_0) display size given the CURRENT window size and rotation. */
    fun naturalSize(curW: Int, curH: Int, rotation: Int): NaturalSize =
        if (rotation == ROTATION_90 || rotation == ROTATION_270) NaturalSize(curH, curW) else NaturalSize(curW, curH)

    /** Rotates a SCREEN-space delta (dsx, dsy) into PANEL (natural/portrait) space. Kept only for
     *  [Store]'s one-time panel->display migration of old profiles; no longer used by
     *  calibration itself. */
    fun rotateDelta(dsx: Float, dsy: Float, rotation: Int): Pair<Float, Float> = when (rotation) {
        ROTATION_90 -> -dsy to dsx
        ROTATION_180 -> -dsx to -dsy
        ROTATION_270 -> dsy to -dsx
        else -> dsx to dsy // ROTATION_0
    }

    /** Inverse of [rotateDelta]: rotates a PANEL-space delta back into SCREEN space. Kept only
     *  for [Store]'s one-time panel->display migration of old profiles. */
    fun rotateDeltaInverse(dpx: Float, dpy: Float, rotation: Int): Pair<Float, Float> = when (rotation) {
        ROTATION_90 -> dpy to -dpx
        ROTATION_180 -> -dpx to -dpy
        ROTATION_270 -> -dpy to dpx
        else -> dpx to dpy // ROTATION_0
    }

    /**
     * Full screen-delta -> display-space dx/dy (integer, display px): identity on the screen
     * delta -- the daemon now presents touch in DISPLAY pixels (`touch.display`), so no
     * rotation mapping applies here any more. [panelMaxX]/[panelMaxY] default to [natW]/[natH]
     * (no-op scale by default; the CalibrateActivity "Advanced" override can still request a
     * different target resolution).
     */
    fun toPanelOffset(
        dsx: Float, dsy: Float,
        natW: Int, natH: Int, panelMaxX: Int = natW, panelMaxY: Int = natH,
    ): Pair<Int, Int> {
        val sx = if (natW != 0) panelMaxX.toFloat() / natW else 1f
        val sy = if (natH != 0) panelMaxY.toFloat() / natH else 1f
        return (dsx * sx).roundToInt() to (dsy * sy).roundToInt()
    }
}
