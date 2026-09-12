package com.dpad.mgr.priv

import android.content.Context
import android.os.Build
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class PrivState(
    val source: PrivSource = PrivSource.NONE,
    val shell: PrivShell? = null,
    val detail: String = "not probed",
    val probing: Boolean = false,
)

/** Tries PServerBinder (root) first, then Shizuku; exposes the winner. */
class PrivProbe(ctx: Context) {
    private val appCtx = ctx.applicationContext
    private val _state = MutableStateFlow(PrivState())
    val state: StateFlow<PrivState> get() = _state
    private val mutex = Mutex()
    private var pserver: PServerShell? = null
    private var shizuku: ShizukuShell? = null
    // PServerBinder is a dead end on some firmware builds (the probe still eats a ~3s
    // transact timeout every time); cache a negative result per Build.DISPLAY so routine
    // rechecks skip straight to Shizuku. [force] (the Re-check button) always retries.
    private val prefs by lazy { appCtx.getSharedPreferences("priv_probe", Context.MODE_PRIVATE) }
    private val deadKey = "pserver_dead_${Build.DISPLAY}"

    suspend fun recheck(force: Boolean = false): PrivState = mutex.withLock {
        _state.value = _state.value.copy(probing = true)
        val ps = pserver ?: PServerShell(appCtx).also { pserver = it }
        val skipPs = !force && prefs.getBoolean(deadKey, false)
        var psOk = false
        var psDetail = "not probed"
        if (skipPs) {
            psDetail = "skipped (cached dead end on ${Build.DISPLAY}; use Re-check to retry)"
            Log.i(TAG, "probe: skipping PServerBinder, cached dead on ${Build.DISPLAY}")
        } else {
            psOk = runCatching { ps.probe() }.getOrElse { Log.w(TAG, "probe: PServerBinder threw $it"); false }
            psDetail = ps.lastProbe
            prefs.edit().putBoolean(deadKey, !psOk).apply()
        }
        val result = if (psOk) {
            PrivState(PrivSource.ROOT_PSERVER, ps, psDetail)
        } else {
            val sz = shizuku ?: ShizukuShell(appCtx).also { shizuku = it }
            val szOk = runCatching { sz.probe() }.getOrElse { Log.w(TAG, "probe: shizuku threw $it"); false }
            if (szOk) PrivState(PrivSource.SHIZUKU, sz, sz.lastProbe)
            else PrivState(PrivSource.NONE, null, "PServerBinder: $psDetail; Shizuku: ${sz.lastProbe}")
        }
        Log.i(TAG, "probe: winner=${result.source.name}")
        _state.value = result
        result
    }

    companion object { private const val TAG = "DpadMgr" }
}
