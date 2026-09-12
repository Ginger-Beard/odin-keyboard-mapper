package com.dpad.mgr.core

import android.util.Log
import com.dpad.mgr.priv.PrivShell
import java.io.File

data class PadInfo(val name: String?, val product: String?, val detail: String) {
    val missing: Boolean get() = name == null
    val isNoneController: Boolean get() = name?.contains("None Controller", ignoreCase = true) == true
    val warn: Boolean get() = missing || isNoneController
}

/** Reads /proc/bus/input/devices and finds AYN's virtual pad (vendor 2020, product 0112 or any with BTN bits). */
object PadInspector {
    private const val TAG = "DpadMgr"

    suspend fun inspect(shell: PrivShell?): PadInfo {
        var text = ""
        if (shell != null) {
            val r = shell.exec(listOf("sh", "-c", "cat /proc/bus/input/devices"))
            if (r.rc == 0) text = r.out
        }
        if (text.isBlank()) text = runCatching { File("/proc/bus/input/devices").readText() }.getOrDefault("")
        if (text.isBlank()) {
            Log.i(TAG, "pad: <unreadable /proc/bus/input/devices>")
            return PadInfo(null, null, "cannot read /proc/bus/input/devices")
        }
        val info = parse(text)
        Log.i(TAG, "pad: ${info.name ?: "missing"}")
        return info
    }

    fun parse(text: String): PadInfo {
        val blocks = text.split(Regex("\n\\s*\n"))
        var fallback: PadInfo? = null
        for (b in blocks) {
            val i = Regex("I: Bus=([0-9a-fA-F]{4}) Vendor=([0-9a-fA-F]{4}) Product=([0-9a-fA-F]{4})").find(b) ?: continue
            if (!i.groupValues[2].equals("2020", true)) continue
            val name = Regex("N: Name=\"([^\"]*)\"").find(b)?.groupValues?.get(1) ?: ""
            val product = i.groupValues[3]
            val keyBits = Regex("B: KEY=([0-9a-f ]+)", RegexOption.IGNORE_CASE).find(b)?.groupValues?.get(1)?.trim() ?: ""
            val hasBtnBits = hasGamepadButtons(keyBits)
            val pi = PadInfo(name, product, "vendor 2020 product $product")
            if (product.equals("0112", true)) return pi
            if (hasBtnBits && fallback == null) fallback = pi
            if (name.contains("None Controller", true) && fallback == null) fallback = pi
        }
        return fallback ?: PadInfo(null, null, "no vendor 2020 gamepad")
    }

    /** BTN_SOUTH is 0x130 (bit 304). KEY= words are printed MSB-first, 64 bits each. */
    private fun hasGamepadButtons(keyBits: String): Boolean {
        if (keyBits.isEmpty()) return false
        val words = keyBits.split(Regex("\\s+")).filter { it.isNotEmpty() }.reversed()
        val idx = 304 / 64
        if (idx >= words.size) return false
        val w = words[idx].toULongOrNull(16) ?: return false
        return (w shr (304 % 64)) and 1uL == 1uL
    }
}
