package com.dpad.mgr.core

import kotlin.math.roundToInt

/**
 * Pure geometry for stylus/touch offset calibration: screen-space taps -> panel-space
 * (touch.offset dx/dy) deltas. Rotation constants mirror android.view.Surface.ROTATION_*.
 */
object Calibration {
    /** Hard clamp for Profile.touchDx/touchDy (panel units), enforced wherever they are set. */
    const val MAX_OFFSET = 200

    const val ROTATION_0 = 0
    const val ROTATION_90 = 1
    const val ROTATION_180 = 2
    const val ROTATION_270 = 3

    data class NaturalSize(val natW: Int, val natH: Int)

    /** Natural (ROTATION_0) display size given the CURRENT window size and rotation. */
    fun naturalSize(curW: Int, curH: Int, rotation: Int): NaturalSize =
        if (rotation == ROTATION_90 || rotation == ROTATION_270) NaturalSize(curH, curW) else NaturalSize(curW, curH)

    /** Rotates a SCREEN-space delta (dsx, dsy) into PANEL (natural/portrait) space. */
    fun rotateDelta(dsx: Float, dsy: Float, rotation: Int): Pair<Float, Float> = when (rotation) {
        ROTATION_90 -> -dsy to dsx
        ROTATION_180 -> -dsx to -dsy
        ROTATION_270 -> dsy to -dsx
        else -> dsx to dsy // ROTATION_0
    }

    /** Inverse of [rotateDelta]: rotates a PANEL-space delta back into SCREEN space. */
    fun rotateDeltaInverse(dpx: Float, dpy: Float, rotation: Int): Pair<Float, Float> = when (rotation) {
        ROTATION_90 -> dpy to -dpx
        ROTATION_180 -> -dpx to -dpy
        ROTATION_270 -> -dpy to dpx
        else -> dpx to dpy // ROTATION_0
    }

    /**
     * Full screen-delta -> panel dx/dy (integer, panel units). [panelMaxX]/[panelMaxY] default to
     * [natW]/[natH] (panel == natural display size, true on the Odin 2).
     */
    fun toPanelOffset(
        dsx: Float, dsy: Float, rotation: Int,
        natW: Int, natH: Int, panelMaxX: Int = natW, panelMaxY: Int = natH,
    ): Pair<Int, Int> {
        val (rx, ry) = rotateDelta(dsx, dsy, rotation)
        val sx = if (natW != 0) panelMaxX.toFloat() / natW else 1f
        val sy = if (natH != 0) panelMaxY.toFloat() / natH else 1f
        return (rx * sx).roundToInt() to (ry * sy).roundToInt()
    }
}
