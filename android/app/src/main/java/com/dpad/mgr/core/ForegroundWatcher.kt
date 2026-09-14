package com.dpad.mgr.core

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import com.dpad.mgr.priv.PrivShell
import com.dpad.mgr.priv.PrivShell.Companion.TAIL_CMD
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
        Log.i(TAG, "watcher: tail cmd=$TAIL_CMD")
        shell.startTail(::onLine)
        Log.i(TAG, "watcher: tail started via ${shell.source.name}")
        scope.launch { seedForeground(shell) }
    }

    /**
     * The logcat tail only sees app switches from the moment it starts, so a game already in
     * the foreground when the tail (re)starts -- e.g. right after boot, before Shizuku's binder
     * shows up, which can take up to a minute -- would otherwise never be detected until the
     * user manually switches apps. Query the currently-resumed activity once via the privileged
     * shell and feed it through the same ignore-list/launcher logic as a live event. Retries once
     * after 2s if the first query comes back empty (dumpsys can be slow to answer right after
     * boot too).
     */
    private suspend fun seedForeground(shell: PrivShell) {
        if (trySeed(shell)) return
        Log.i(TAG, "watcher: seed query returned nothing, retrying in 2s")
        delay(2000)
        if (this.shell !== shell) return // stop()/start() happened meanwhile; a fresh seed is already in flight
        if (!trySeed(shell)) Log.i(TAG, "watcher: re-seed query returned nothing")
    }

    /** Returns true once a foreground line was found (and fed to [onLine]); false if the query was empty. */
    private suspend fun trySeed(shell: PrivShell): Boolean {
        val line = queryForegroundLine(shell) ?: return false
        if (this.shell !== shell) return true // shell replaced mid-query; the new start() will seed on its own
        val pkg = PKG_RE.find(line)!!.groupValues[1]
        Log.i(TAG, "watcher: seeded foreground=$pkg")
        onLine(line)
        return true
    }

    // "topResumedActivity" is the Android 13+ single-source-of-truth field; older/OEM dumpsys
    // builds instead print "mResumedActivity" or (observed on this device's ROM) a bare
    // "ResumedActivity:" line -- grepping the "ResumedActivity" substring catches all three.
    private suspend fun queryForegroundLine(shell: PrivShell): String? =
        queryVia(shell, "topResumedActivity") ?: queryVia(shell, "ResumedActivity")

    private suspend fun queryVia(shell: PrivShell, marker: String): String? {
        val r = runCatching { shell.exec(listOf("sh", "-c", "dumpsys activity activities | grep $marker")) }
            .getOrElse { return null }
        if (!r.ok) return null
        return r.out.lineSequence().firstOrNull { PKG_RE.containsMatchIn(it) }
    }

    suspend fun stop() {
        shell?.let { runCatching { it.stopTail() } }
        shell = null
        debounce?.cancel()
        _foreground.value = null
    }

    fun onLine(line: String) {
        val m = PKG_RE.find(line) ?: return
        val raw = m.value
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
                if (t != null) {
                    Log.i(TAG, "foreground: $t (raw=$raw)")
                } else {
                    Log.i(TAG, "watcher: launcher/ignored entered (raw=$raw)")
                }
                _foreground.value = t
            }
        }
    }

    companion object {
        private const val TAG = "DpadMgr"
        val PKG_RE = Regex("([A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z0-9_]+)+)/[.A-Za-z0-9_$]+")
    }
}
