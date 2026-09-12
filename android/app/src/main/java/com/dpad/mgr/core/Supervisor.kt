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
    /** The daemon exited via the panic chord (exit code 6): touch offset was disabled and the
     *  daemon will not be restarted for [profile] until the target changes or Re-check runs. */
    data class PanicStopped(val profile: String) : DaemonState()

    val label: String
        get() = when (this) {
            Idle -> "stopped"
            is Starting -> "starting ($profile)"
            is Running -> "running ($profile, pid $pid)"
            is Backoff -> "retrying ($profile, attempt $attempt): $reason"
            is Failed -> "failed: $reason"
            is PanicStopped -> "touch offset disabled by panic chord ($profile)"
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
    private val onPanic: (profileName: String) -> Unit = {},
) {
    private val _state = MutableStateFlow<DaemonState>(DaemonState.Idle)
    val state: StateFlow<DaemonState> get() = _state

    private val wake = Channel<Unit>(Channel.CONFLATED)
    @Volatile private var desired: Target? = null
    @Volatile private var override: Target? = null
    private var overrideJob: Job? = null
    @Volatile private var suspended = false

    private var running: Triple<Int, String, Target>? = null // pid, configText, target
    private var pointerRefreshJob: Job? = null
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
    fun test(profile: Profile, seconds: Int = 30) = startTest(profile, seconds)

    /** Test [profile] now. [timeoutSec] <= 0 means no auto-stop; call [stopTest] to end it. */
    fun startTest(profile: Profile, timeoutSec: Int = 0) {
        overrideJob?.cancel()
        override = Target(null, profile)
        failedLatched = false
        fastFailures = 0
        Log.i(TAG, "supervisor: test profile=${profile.name} timeoutSec=$timeoutSec")
        wake.trySend(Unit)
        overrideJob = if (timeoutSec > 0) {
            scope.launch {
                delay(timeoutSec * 1000L)
                override = null
                Log.i(TAG, "supervisor: test window over")
                wake.trySend(Unit)
            }
        } else null
    }

    /** Ends a running test override (started via [test]/[startTest]) without touching [desired]. */
    fun stopTest() {
        overrideJob?.cancel(); override = null
        fastFailures = 0; attempt = 0; backoffUntil = 0; failedLatched = false
        wake.trySend(Unit)
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

    /** Stops any running daemon and ignores target changes until [resume] (used during calibration). */
    fun suspend() {
        overrideJob?.cancel(); override = null
        suspended = true
        Log.i(TAG, "supervisor: suspended")
        wake.trySend(Unit)
    }

    fun resume() {
        suspended = false
        Log.i(TAG, "supervisor: resumed")
        wake.trySend(Unit)
    }

    /**
     * Writes [profile]'s config and, if a daemon is running (test or otherwise), signals it to
     * re-read touch.offset live via SIGUSR1 instead of restarting. Updates local bookkeeping so
     * the watchdog doesn't see a "profile change" and restart the daemon on the next tick.
     */
    suspend fun updateConfigLive(profile: Profile) {
        val shell = shellProvider() ?: return
        val conf = profile.toConfigText()
        if (!shell.writeFile(CONF, conf)) { Log.w(TAG, "supervisor: updateConfigLive: write failed"); return }
        override = override?.copy(profile = profile)
        desired = desired?.copy(profile = profile)
        val cur = running
        if (cur != null) {
            running = Triple(cur.first, conf, cur.third.copy(profile = profile))
            val pid = runCatching { shell.exec(listOf("cat", PIDFILE)).out.trim().toIntOrNull() }.getOrNull()
            if (pid != null && pid > 1) {
                shell.exec(listOf("kill", "-USR1", pid.toString()))
                Log.i(TAG, "supervisor: live-updated pid=$pid dx=${profile.touchDx} dy=${profile.touchDy}")
            } else {
                Log.w(TAG, "supervisor: updateConfigLive: no pid to signal")
            }
        }
    }

    /**
     * Starts a coroutine that re-applies setPointerHidden(true) every 700ms while a wheel
     * profile's daemon is running. Android resets the pointer icon back to the arrow on the
     * first mouse event delivered to a newly-focused window, so a single call at spawn time
     * isn't enough -- the first wheel notch would show (and leave visible) the cursor.
     */
    private fun startPointerRefresh(shell: PrivShell) {
        pointerRefreshJob?.cancel()
        pointerRefreshJob = scope.launch {
            runCatching { shell.setPointerHidden(true) }
            Log.i(TAG, "pointer: hidden")
            while (isActive) {
                delay(700)
                runCatching { shell.setPointerHidden(true) }
            }
        }
    }

    /** Unconditionally attempts to restore the pointer; harmless/no-op if it wasn't hidden or the channel doesn't support it. */
    private suspend fun restorePointer(shell: PrivShell?) {
        pointerRefreshJob?.cancel()
        pointerRefreshJob = null
        runCatching { shell?.setPointerHidden(false) }
        Log.i(TAG, "pointer: restored")
    }

    private suspend fun reconcile() {
        if (suspended) {
            val cur = running
            if (cur != null) {
                Log.i(TAG, "supervisor: stopping daemon (suspended) pid=${cur.first}")
                shellProvider()?.kill(cur.first)
                running = null
                restorePointer(shellProvider())
            }
            fastFailures = 0; attempt = 0; backoffUntil = 0
            _state.value = DaemonState.Idle
            return
        }
        val want = override ?: desired
        val cur = running
        val shell = shellProvider()

        if (want == null) {
            if (cur != null) {
                Log.i(TAG, "supervisor: stopping daemon pid=${cur.first}")
                shell?.kill(cur.first)
                running = null
                restorePointer(shell)
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
                val ec = shell.exitCode(cur.first)
                running = null
                restorePointer(shell)
                if (ec == 6) {
                    val prof = cur.third.profile
                    Log.i(TAG, "supervisor: panic chord → touch offset disabled for ${prof.name}")
                    Store.saveProfile(prof.copy(touchOffsetEnabled = false), prof.name)
                    failedLatched = true
                    _state.value = DaemonState.PanicStopped(prof.name)
                    onPanic(prof.name)
                    return
                }
                Log.w(TAG, "supervisor: daemon pid=${cur.first} died")
                onFailure("daemon exited: ${lastLogLine(shell)}", fast = System.currentTimeMillis() - startedAt < 10_000)
                if (failedLatched) return
            } else {
                Log.i(TAG, "supervisor: profile change ${cur.third.profile.name} -> ${want.profile.name}; restarting")
                shell.kill(cur.first)
                running = null
                restorePointer(shell)
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
            Log.i(TAG, spawnLogLine(want.profile.name, conf))
            running = Triple(pid, conf, want)
            attempt = 0
            _state.value = DaemonState.Running(want.pkg, want.profile.name, pid)
            if (want.profile.usesWheel()) {
                startPointerRefresh(shell)
            }
        } else {
            onFailure("daemon did not start (pid=$pid): ${lastLogLine(shell)}", fast = true)
        }
    }

    private suspend fun lastLogLine(shell: PrivShell): String =
        shell.exec(listOf("tail", "-n", "2", LOG)).out.trim().lines().lastOrNull()?.take(160) ?: ""

    /** One-line spawn summary ("supervisor: spawn profile=OSRS touch=on -2 -10 keys=18"), parsed
     *  straight out of the exact config text handed to the daemon -- so it's diagnosable from
     *  logcat whether touch.offset made it into the config a given process actually started with. */
    private fun spawnLogLine(profile: String, conf: String): String {
        val directives = setOf(
            "deadzone", "ls.invert_y", "ls.invert_x", "rs.invert_y", "rs.invert_x", "wheel_repeat_ms", "touch.offset",
        )
        var touch = "off"
        var keys = 0
        for (raw in conf.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) continue
            val parts = line.split(Regex("\\s+"))
            val key = parts.getOrNull(0) ?: continue
            when {
                key == "touch.offset" -> touch = "on ${parts.getOrNull(1) ?: "?"} ${parts.getOrNull(2) ?: "?"}"
                key in directives -> {}
                parts.size >= 2 && parts[1] != "NONE" -> keys++
            }
        }
        return "supervisor: spawn profile=$profile touch=$touch keys=$keys"
    }

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
