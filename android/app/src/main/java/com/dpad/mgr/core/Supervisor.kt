package com.dpad.mgr.core

import android.util.Log
import com.dpad.mgr.priv.PrivShell
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

sealed class DaemonState {
    data object Idle : DaemonState()
    data class Starting(val pkg: String?, val profile: String) : DaemonState()
    data class Running(val pkg: String?, val profile: String, val pid: Int) : DaemonState()
    data class Backoff(val profile: String, val attempt: Int, val untilMs: Long, val reason: String) : DaemonState()
    data class Failed(val reason: String) : DaemonState()

    val label: String
        get() = when (this) {
            Idle -> "stopped"
            is Starting -> "starting ($profile)"
            is Running -> "running ($profile, pid $pid)"
            is Backoff -> "retrying ($profile, attempt $attempt): $reason"
            is Failed -> "failed: $reason"
        }
}

private data class Target(val pkg: String?, val profile: Profile)

/**
 * Owns the daemon process lifecycle: reconciles the desired (pkg, profile) target
 * against what is running, with a 2 s watchdog and exponential backoff.
 */
class Supervisor(
    private val scope: CoroutineScope,
    private val shellProvider: () -> PrivShell?,
    private val installer: BinaryInstaller,
    private val onFailed: (String) -> Unit,
) {
    private val _state = MutableStateFlow<DaemonState>(DaemonState.Idle)
    val state: StateFlow<DaemonState> get() = _state

    private val wake = Channel<Unit>(Channel.CONFLATED)
    @Volatile private var desired: Target? = null
    @Volatile private var override: Target? = null
    private var overrideJob: Job? = null

    private var running: Triple<Int, String, Target>? = null // pid, configText, target
    private var startedAt = 0L
    private var fastFailures = 0
    private var attempt = 0
    private var backoffUntil = 0L
    private var lastReason = ""
    private var failedLatched = false

    init {
        scope.launch {
            while (isActive) {
                runCatching { reconcile() }.onFailure { Log.w(TAG, "supervisor: reconcile threw $it") }
                // wait for a wake-up or the 2 s watchdog tick
                withTimeoutOrNull(2000) { wake.receive() }
            }
        }
    }

    fun setTarget(pkg: String?, profile: Profile?) {
        val t = if (pkg != null && profile != null) Target(pkg, profile) else null
        if (desired != t) {
            Log.i(TAG, "supervisor: target=${t?.let { "${it.pkg} -> ${it.profile.name}" } ?: "<none>"}")
            desired = t
            failedLatched = false
            wake.trySend(Unit)
        }
    }

    /** Manual test: run [profile] now for [seconds], then return to the assigned target. */
    fun test(profile: Profile, seconds: Int = 30) {
        overrideJob?.cancel()
        override = Target(null, profile)
        failedLatched = false
        fastFailures = 0
        Log.i(TAG, "supervisor: test profile=${profile.name} for ${seconds}s")
        wake.trySend(Unit)
        overrideJob = scope.launch {
            delay(seconds * 1000L)
            override = null
            Log.i(TAG, "supervisor: test window over")
            wake.trySend(Unit)
        }
    }

    /** Manual stop: clears any test override and the current target until the next foreground change. */
    fun stop() {
        overrideJob?.cancel(); override = null
        desired = null
        fastFailures = 0; attempt = 0; backoffUntil = 0; failedLatched = false
        wake.trySend(Unit)
    }

    /** Called when privilege changes (e.g. after Re-check). */
    fun reset() {
        fastFailures = 0; attempt = 0; backoffUntil = 0; failedLatched = false
        wake.trySend(Unit)
    }

    private suspend fun reconcile() {
        val want = override ?: desired
        val cur = running
        val shell = shellProvider()

        if (want == null) {
            if (cur != null) {
                Log.i(TAG, "supervisor: stopping daemon pid=${cur.first}")
                shell?.kill(cur.first)
                running = null
            }
            fastFailures = 0; attempt = 0; backoffUntil = 0
            if (_state.value !is DaemonState.Failed || !failedLatched) _state.value = DaemonState.Idle
            return
        }
        if (shell == null) {
            running = null
            _state.value = DaemonState.Failed("no privileged shell (root/Shizuku unavailable)")
            return
        }
        val wantConf = want.profile.toConfigText()
        if (cur != null) {
            if (cur.second == wantConf) {
                // same profile: watchdog
                if (shell.isAlive(cur.first)) {
                    val s = _state.value
                    if (s !is DaemonState.Running || s.pkg != want.pkg) _state.value = DaemonState.Running(want.pkg, want.profile.name, cur.first)
                    return
                }
                Log.w(TAG, "supervisor: daemon pid=${cur.first} died")
                running = null
                onFailure("daemon exited: ${lastLogLine(shell)}", fast = System.currentTimeMillis() - startedAt < 10_000)
                if (failedLatched) return
            } else {
                Log.i(TAG, "supervisor: profile change ${cur.third.profile.name} -> ${want.profile.name}; restarting")
                shell.kill(cur.first)
                running = null
                delay(150)
            }
        }
        if (failedLatched) return
        if (System.currentTimeMillis() < backoffUntil) return
        start(shell, want, wantConf)
    }

    private suspend fun start(shell: PrivShell, want: Target, conf: String) {
        _state.value = DaemonState.Starting(want.pkg, want.profile.name)
        val bin = installer.resolve(shell)
        if (bin == null) { onFailure("daemon binary not runnable", fast = true); return }
        if (!shell.writeFile(CONF, conf)) { onFailure("cannot write $CONF", fast = true); return }
        // kill a stale instance recorded in the pidfile (ours or the adb kit's)
        val stale = shell.exec(listOf("cat", PIDFILE)).out.trim().toIntOrNull()
        if (stale != null && stale > 1 && shell.isAlive(stale)) {
            Log.i(TAG, "supervisor: killing stale pid=$stale")
            shell.kill(stale)
            delay(300)
        }
        val pid = shell.spawn(listOf(bin, "--config", CONF, "--grab", "--pidfile", PIDFILE), PIDFILE)
        startedAt = System.currentTimeMillis()
        delay(400)
        if (pid > 1 && shell.isAlive(pid)) {
            Log.i(TAG, "supervisor: running pid=$pid profile=${want.profile.name} pkg=${want.pkg}")
            running = Triple(pid, conf, want)
            attempt = 0
            _state.value = DaemonState.Running(want.pkg, want.profile.name, pid)
        } else {
            onFailure("daemon did not start (pid=$pid): ${lastLogLine(shell)}", fast = true)
        }
    }

    private suspend fun lastLogLine(shell: PrivShell): String =
        shell.exec(listOf("tail", "-n", "2", LOG)).out.trim().lines().lastOrNull()?.take(160) ?: ""

    private fun onFailure(reason: String, fast: Boolean) {
        lastReason = reason
        if (fast) fastFailures++ else fastFailures = 0
        Log.w(TAG, "supervisor: failure ($fastFailures fast): $reason")
        if (fastFailures >= 5) {
            failedLatched = true
            _state.value = DaemonState.Failed(reason)
            onFailed(reason)
            return
        }
        val delays = longArrayOf(1, 2, 4, 8, 16, 30)
        val d = delays[minOf(attempt, delays.size - 1)] * 1000
        attempt++
        backoffUntil = System.currentTimeMillis() + d
        val prof = (override ?: desired)?.profile?.name ?: "?"
        _state.value = DaemonState.Backoff(prof, attempt, backoffUntil, reason)
    }

    companion object {
        private const val TAG = "DpadMgr"
        const val CONF = "/data/local/tmp/dpadkeys.conf"
        const val PIDFILE = "/data/local/tmp/dpadkeys.pid"
        const val LOG = "/data/local/tmp/dpadkeys.log"
    }
}
