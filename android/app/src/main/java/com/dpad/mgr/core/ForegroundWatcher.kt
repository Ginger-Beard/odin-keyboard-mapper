package com.dpad.mgr.core

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import com.dpad.mgr.priv.PrivShell
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Foreground-app detection by tailing the activity-manager event log through the
 * privileged shell (no usage-stats, no accessibility). Emits the foreground package,
 * or null when the launcher / an ignored package / nothing is in front.
 */
class ForegroundWatcher(ctx: Context, private val scope: CoroutineScope) {
    private val appCtx = ctx.applicationContext
    private val _foreground = MutableStateFlow<String?>(null)
    val foreground: StateFlow<String?> get() = _foreground
    private val _raw = MutableStateFlow<String?>(null)
    /** Last parsed package regardless of ignore list (for the status UI). */
    val lastSeen: StateFlow<String?> get() = _raw

    private val ignore = setOf("com.android.systemui", "com.odin.gameassistant", "com.odin.mapping", appCtx.packageName)
    private val launchers: Set<String> by lazy { resolveLaunchers() }
    private var shell: PrivShell? = null
    private var debounce: Job? = null
    @Volatile private var pending: String? = null

    private fun resolveLaunchers(): Set<String> {
        val pm = appCtx.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val set = mutableSetOf<String>()
        runCatching { pm.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName?.let { set += it } }
        runCatching { pm.queryIntentActivities(intent, 0).forEach { set += it.activityInfo.packageName } }
        Log.i(TAG, "watcher: launchers=$set")
        return set
    }

    suspend fun start(shell: PrivShell) {
        stop()
        this.shell = shell
        shell.startTail(::onLine)
        Log.i(TAG, "watcher: tail started via ${shell.source.name}")
    }

    suspend fun stop() {
        shell?.let { runCatching { it.stopTail() } }
        shell = null
        debounce?.cancel()
        _foreground.value = null
    }

    fun onLine(line: String) {
        val m = PKG_RE.find(line) ?: return
        val pkg = m.groupValues[1]
        _raw.value = pkg
        val target: String? = when {
            pkg in ignore -> return // transient overlays: keep current state
            pkg in launchers -> null
            else -> pkg
        }
        pending = target
        debounce?.cancel()
        debounce = scope.launch {
            delay(250)
            val t = pending
            if (_foreground.value != t) {
                Log.i(TAG, "watcher: foreground=${t ?: "<none>"}")
                _foreground.value = t
            }
        }
    }

    companion object {
        private const val TAG = "DpadMgr"
        val PKG_RE = Regex("([A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z0-9_]+)+)/[.A-Za-z0-9_$]+")
    }
}
