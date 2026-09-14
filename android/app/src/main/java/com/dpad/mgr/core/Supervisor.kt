package com.dpad.mgr.core

import android.content.Context
import android.util.Log
import com.dpad.mgr.priv.PrivShell
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

sealed class DaemonState {
    /** No `--serve` daemon running (no privilege yet, or it was terminated). */
    data object Stopped : DaemonState()
    /** Spawning the single long-lived `--serve` daemon. */
    data object Starting : DaemonState()
    /** The serve daemon is up and idle: nothing grabbed, the pad works normally. */
    data object Idle : DaemonState()
    /** Serving and actively mapping [profile] for foreground app [pkg]. [waitingFor] is
     *  `"panel"`/`"pad"` while the daemon is holding off the grab for a still-held touch or
     *  button, null otherwise. */
    data class Running(val pkg: String, val profile: String, val pid: Int, val waitingFor: String? = null) : DaemonState()
    /** Serving and actively mapping [profile] as a manual test / calibration verify. */
    data class Testing(val profile: String, val pid: Int, val waitingFor: String? = null) : DaemonState()
    data class Backoff(val attempt: Int, val untilMs: Long, val reason: String) : DaemonState()
    data class Failed(val reason: String) : DaemonState()
    /** The panic chord fired: the daemon went idle and touch offset was disabled for [profile]. */
    data class PanicStopped(val profile: String) : DaemonState()

    val label: String
        get() = when (this) {
            Stopped -> "stopped"
            Starting -> "starting"
            Idle -> "serving (idle)"
            is Running -> "mapping $profile for $pkg (pid $pid)"
            is Testing -> "testing $profile (pid $pid)"
            is Backoff -> "retrying (attempt $attempt): $reason"
            is Failed -> "failed: $reason"
            is PanicStopped -> "touch offset disabled by panic chord ($profile)"
        }
}

private data class Target(val pkg: String?, val profile: Profile)

/** One parsed line of the daemon's status file: `state=… touch=… keys=N panic=C waiting=panel|pad|none`. */
private data class Status(
    val state: String,
    val touch: String,
    val keys: Int,
    val panic: Int,
    val waiting: String?,
    val raw: String,
)

/**
 * Owns exactly ONE long-lived `dpadkeys --serve` process per privilege session: it creates the
 * virtual devices once and keeps them until it exits. Everything else -- foreground app changes,
 * manual tests, calibration suspend/verify, live config edits -- is "write the config file that
 * should be active now, then SIGUSR1 the daemon", never a process spawn or kill. A 2 s watchdog
 * respawns the serve daemon (with backoff) if it dies; that is the only case where the virtual
 * devices are recreated.
 */
class Supervisor(
    ctx: Context,
    private val scope: CoroutineScope,
    private val shellProvider: () -> PrivShell?,
    private val installer: BinaryInstaller,
    /** Passed to the daemon as `--device-name`, so its virtual devices (the keyboard+mouse
     *  device and the "<name> Touch" touchscreen clone) are named after this app. */
    private val deviceName: String,
    private val onFailed: (String) -> Unit,
    private val onPanic: (profileName: String) -> Unit = {},
) {
    private val _state = MutableStateFlow<DaemonState>(DaemonState.Stopped)
    val state: StateFlow<DaemonState> get() = _state

    private val wake = Channel<Unit>(Channel.CONFLATED)
    /** Survives [scope] being cancelled, so the SIGTERM on service destroy still goes out. */
    private val exitScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile private var desired: Target? = null
    @Volatile private var override: Target? = null
    private var overrideJob: Job? = null
    private var debounceJob: Job? = null
    @Volatile private var suspended = false
    @Volatile private var learning = false
    @Volatile private var restartRequested = false
    private val prefs = ctx.applicationContext.getSharedPreferences("supervisor", Context.MODE_PRIVATE)
    /** Monotonic; bumped whenever the display rotation moves to a value the current generation
     *  wasn't created at. Persisted so a service respawn never reuses a generation number the
     *  daemon may already have seen -- see [setDisplayRotation]/[seedRotation]. */
    @Volatile private var touchGeneration: Int = prefs.getInt(KEY_TOUCH_GENERATION, 0)
    /** The rotation [touchGeneration] was last set at. -1 = not yet seeded, so the very first
     *  seed/rotation call always applies regardless of what it reads. Device state, not part of
     *  the Profile model. */
    @Volatile private var rotationAtGeneration: Int = -1
    private var rotationDebounceJob: Job? = null
    /** Current default-display logical size + rotation, fed by [setDisplayMetrics]/[seedDisplayMetrics]
     *  (DpadService's DisplayListener and service-start/daemon-respawn seeding, respectively) and
     *  emitted as `touch.display W H R` on every config write -- see [withTouchRotation]. Device
     *  state, not part of the Profile model; 0/0/0 until first seeded (harmless: idle config). */
    @Volatile private var touchDisplayW: Int = 0
    @Volatile private var touchDisplayH: Int = 0
    @Volatile private var touchDisplayRotation: Int = 0

    // ---- the one serve daemon ----
    private var servePid = -1
    private var serveShell: PrivShell? = null
    /** Config text last written + signalled; null while no daemon is up. */
    private var appliedConf: String? = null
    /** Target the [appliedConf] belongs to; null means the idle config is active. */
    private var appliedTarget: Target? = null
    private var lastPanic = 0
    private var lastStatusRaw = ""
    /** Most recently read `waiting` value ("panel"/"pad"/null); also drives the once-per-change log. */
    private var lastWaiting: String? = null

    private var pointerRefreshJob: Job? = null
    private var pointerHidden = false
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

    /** Foreground-driven target. Rapid switches are coalesced to the latest one over 150 ms. */
    fun setTarget(pkg: String?, profile: Profile?) {
        val t = if (pkg != null && profile != null) Target(pkg, profile) else null
        if (desired != t) {
            Log.i(TAG, "supervisor: target=${t?.let { "${it.pkg} -> ${it.profile.name}" } ?: "<none>"}")
            desired = t
            failedLatched = false
            debounceJob?.cancel()
            debounceJob = scope.launch { delay(TARGET_DEBOUNCE_MS); wake.trySend(Unit) }
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

    /** "Go idle now": drops the test override and the current target until the next foreground change. */
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

    /** Manual recovery: SIGTERM the serve daemon and spawn a fresh one (recreates virtual devices). */
    fun restartDaemon() {
        Log.i(TAG, "supervisor: restart requested")
        restartRequested = true
        wake.trySend(Unit)
    }

    /** Goes idle and ignores foreground targets until [resume] (calibration); an explicit test
     *  override (the verify step) still runs. */
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
     * Feeds in a genuine display size/rotation change (called by DpadService's DisplayListener
     * callback only -- see [seedDisplayMetrics] for service-start/daemon-respawn seeding). Always
     * updates [touchDisplayW]/[touchDisplayH]/[touchDisplayRotation] (emitted as `touch.display`
     * on every config write). If the new rotation differs from the rotation [touchGeneration] was
     * created at, additionally bumps and persists [touchGeneration]: on the next SIGUSR1 reload
     * the daemon sees the new generation number and destroys+recreates its virtual touchscreen
     * fresh at the current rotation. Debounces 300 ms (to absorb rotation settling) then wakes the
     * reconcile loop, which rewrites the current config (active profile or idle) in place via the
     * existing apply() path (no daemon respawn).
     */
    fun setDisplayMetrics(width: Int, height: Int, rotation: Int) {
        touchDisplayW = width
        touchDisplayH = height
        touchDisplayRotation = rotation
        if (rotation != rotationAtGeneration) {
            touchGeneration++
            rotationAtGeneration = rotation
            prefs.edit().putInt(KEY_TOUCH_GENERATION, touchGeneration).apply()
            Log.i(TAG, "supervisor: rotation=$rotation -> touch.generation=$touchGeneration")
        }
        rotationDebounceJob?.cancel()
        rotationDebounceJob = scope.launch { delay(300); wake.trySend(Unit) }
    }

    /** Seeds the display size/rotation bookkeeping without bumping [touchGeneration]: called at
     *  service start and whenever the serve daemon (re)spawns, since a fresh daemon process
     *  always creates its virtual touchscreen clone fresh at whatever rotation is current -- no
     *  generation bump is needed, just keep [rotationAtGeneration] (so the next genuine
     *  [setDisplayMetrics] call is compared correctly) and the touch.display fields in sync. */
    fun seedDisplayMetrics(width: Int, height: Int, rotation: Int) {
        touchDisplayW = width
        touchDisplayH = height
        touchDisplayRotation = rotation
        rotationAtGeneration = rotation
    }

    /** touch.rotation is always 0 now: rotation compensation happens entirely via
     *  [touchGeneration] bumps (the daemon recreates its touchscreen clone fresh at the current
     *  rotation on reload) rather than a sign-based formula here. Kept as a function so the
     *  config-writing path reads the same as before. */
    private fun touchRotationN(): Int = 0

    /** Appends the device's touch-generation, touch-rotation, and touch-display directives to
     *  config text about to be written to CONF. `touch.display W H R` tells the daemon the
     *  default display's current logical size and Surface rotation, so it presents touch (and
     *  applies touch.offset) in DISPLAY pixels. Always appended (harmless when touch isn't in
     *  play/idle) so it's simplest to reason about from logcat and the CONF file alike. */
    private fun withTouchRotation(conf: String): String =
        conf + "touch.generation $touchGeneration\n" + "touch.rotation ${touchRotationN()}\n" +
            "touch.display $touchDisplayW $touchDisplayH $touchDisplayRotation\n"

    /**
     * Forces the daemon idle so a one-shot `--learn` invocation can grab the pad, and waits (up to
     * 4 s) for that to take effect. Leaves [desired]/[override] untouched, so [endLearn] restores
     * whatever is still the intended target -- and restores nothing if the user navigated away.
     */
    suspend fun beginLearn(): Boolean {
        learning = true
        wake.trySend(Unit)
        val deadline = System.currentTimeMillis() + 4_000
        while (System.currentTimeMillis() < deadline) {
            if (isIdleish(_state.value)) return true
            delay(100)
        }
        return isIdleish(_state.value)
    }

    fun endLearn() {
        learning = false
        wake.trySend(Unit)
    }

    private fun isIdleish(s: DaemonState): Boolean = when (s) {
        DaemonState.Idle, DaemonState.Stopped, is DaemonState.Failed, is DaemonState.PanicStopped -> true
        else -> false
    }

    /** SIGTERMs the serve daemon (privilege loss / service destroy). */
    fun shutdown() {
        val shell = shellProvider()
        val pid = servePid
        servePid = -1; serveShell = null; appliedConf = null; appliedTarget = null
        pointerRefreshJob?.cancel(); pointerRefreshJob = null
        val wasHidden = pointerHidden
        pointerHidden = false
        _state.value = DaemonState.Stopped
        exitScope.launch {
            if (wasHidden) runCatching { shell?.setPointerHidden(false) }
            if (pid > 1 && shell != null) {
                Log.i(TAG, "supervisor: SIGTERM pid=$pid")
                runCatching { shell.kill(pid) }
            }
        }
    }

    /**
     * Applies [profile]'s edits without waiting for the next foreground change: updates the
     * bookkeeping copies and wakes the loop, which writes the new config text and SIGUSR1s the
     * daemon (no restart, no device churn).
     */
    suspend fun updateConfigLive(profile: Profile) {
        override = override?.let { if (it.profile.name == profile.name) it.copy(profile = profile) else it }
        desired = desired?.let { if (it.profile.name == profile.name) it.copy(profile = profile) else it }
        wake.trySend(Unit)
    }

    // ---- reconciliation ----

    /** The config that should be active right now; null means the idle config. */
    private fun wanted(): Target? = when {
        failedLatched -> null
        learning -> null
        suspended -> override
        else -> override ?: desired
    }

    private suspend fun reconcile() {
        val shell = shellProvider()
        if (shell == null) {
            if (servePid > 1) Log.i(TAG, "supervisor: privilege lost, dropping pid=$servePid")
            servePid = -1; serveShell = null; appliedConf = null; appliedTarget = null
            restorePointer(null)
            if (!failedLatched) _state.value = DaemonState.Failed("no privileged shell (root/Shizuku unavailable)")
            return
        }
        if (serveShell !== shell) {
            // Privilege channel changed: whatever we started belongs to the old one; terminate it
            // through the new channel so we never end up with two serve daemons.
            if (servePid > 1) {
                Log.i(TAG, "supervisor: privilege changed, SIGTERM pid=$servePid")
                shell.kill(servePid)
                delay(200)
            }
            servePid = -1; appliedConf = null; appliedTarget = null
            serveShell = shell
        }
        if (restartRequested) {
            restartRequested = false
            if (servePid > 1) { Log.i(TAG, "supervisor: SIGTERM pid=$servePid (restart)"); shell.kill(servePid); delay(300) }
            servePid = -1; appliedConf = null; appliedTarget = null
            fastFailures = 0; attempt = 0; backoffUntil = 0; failedLatched = false
            restorePointer(shell)
        }

        // 1. keep exactly one serve daemon alive
        if (servePid > 1 && !shell.isAlive(servePid)) {
            Log.w(TAG, "supervisor: serve pid=$servePid died")
            val fast = System.currentTimeMillis() - startedAt < 10_000
            servePid = -1; appliedConf = null; appliedTarget = null
            restorePointer(shell)
            onFailure("daemon exited: ${lastLogLine(shell)}", fast)
        }
        if (servePid <= 1) {
            if (failedLatched) return
            if (System.currentTimeMillis() < backoffUntil) return
            if (!spawnServe(shell)) return
        }

        // 2. panic detection (the daemon goes idle by itself and bumps the counter)
        readStatus(shell)?.let { st ->
            logStatus(st)
            if (checkPanic(shell, st)) return
        }

        // 3. make the active config match what should be active now
        val want = wanted()
        val conf = withTouchRotation(want?.profile?.toConfigText() ?: IDLE_CONF)
        if (conf != appliedConf) apply(shell, want, conf) else syncState(want)
    }

    private suspend fun spawnServe(shell: PrivShell): Boolean {
        _state.value = DaemonState.Starting
        val bin = installer.resolve(shell)
        if (bin == null) { onFailure("daemon binary not runnable", fast = true); return false }
        val idleConf = withTouchRotation(IDLE_CONF)
        if (!shell.writeFile(CONF, idleConf)) { onFailure("cannot write $CONF", fast = true); return false }
        // kill a stale instance recorded in the pidfile (ours from a previous process, or the adb kit's)
        val stale = shell.exec(listOf("cat", PIDFILE)).out.trim().toIntOrNull()
        if (stale != null && stale > 1 && shell.isAlive(stale)) {
            Log.i(TAG, "supervisor: killing stale pid=$stale")
            shell.kill(stale)
            delay(300)
        }
        runCatching { shell.exec(listOf("rm", "-f", STATUS)) }
        val argv = buildList {
            add(bin); add("--serve"); add("--config"); add(CONF); add("--pidfile"); add(PIDFILE)
            add("--status-file"); add(STATUS); add("--device-name"); add(deviceName)
            if (Store.data.value.allowQ) add("--allow-q")
        }
        val pid = shell.spawn(argv, PIDFILE)
        startedAt = System.currentTimeMillis()
        delay(400)
        if (pid <= 1 || !shell.isAlive(pid)) {
            onFailure("daemon did not start (pid=$pid): ${lastLogLine(shell)}", fast = true)
            return false
        }
        servePid = pid
        appliedConf = idleConf
        appliedTarget = null
        attempt = 0
        lastStatusRaw = ""
        lastPanic = pollStatus(shell, "idle")?.panic ?: 0
        Log.i(TAG, "supervisor: serve spawned pid=$pid")
        _state.value = DaemonState.Idle
        return true
    }

    /** Writes the config that should be active and SIGUSR1s the daemon, then waits for the status
     *  file to reflect it (up to 1 s). Never spawns or kills a process. */
    private suspend fun apply(shell: PrivShell, want: Target?, conf: String) {
        if (!shell.writeFile(CONF, conf)) { onFailure("cannot write $CONF", fast = true); return }
        val r = shell.exec(listOf("kill", "-USR1", servePid.toString()))
        if (!r.ok) Log.w(TAG, "supervisor: SIGUSR1 to pid=$servePid failed rc=${r.rc} ${r.out.trim().take(80)}")
        appliedConf = conf
        appliedTarget = want
        if (want == null) Log.i(TAG, "supervisor: idle") else Log.i(TAG, activateLogLine(want.profile.name, conf))

        val expect = if (want == null) "idle" else "active"
        val st = pollStatus(shell, expect)
        if (st == null) Log.w(TAG, "supervisor: no status file after SIGUSR1")
        else if (st.state != expect) Log.w(TAG, "supervisor: status still state=${st.state}, expected $expect")
        if (st != null && checkPanic(shell, st)) return

        if (want != null && want.profile.usesWheel()) startPointerRefresh(shell) else restorePointer(shell)
        syncState(want)
    }

    private fun syncState(want: Target?) {
        if (failedLatched) return
        val s = when {
            want == null -> DaemonState.Idle
            want.pkg != null -> DaemonState.Running(want.pkg, want.profile.name, servePid, lastWaiting)
            else -> DaemonState.Testing(want.profile.name, servePid, lastWaiting)
        }
        if (_state.value != s) _state.value = s
    }

    // ---- status file ----

    private suspend fun readStatus(shell: PrivShell): Status? {
        val r = runCatching { shell.exec(listOf("cat", STATUS)) }.getOrNull() ?: return null
        if (!r.ok) return null
        val line = r.out.lineSequence().map { it.trim() }.lastOrNull { it.startsWith("state=") } ?: return null
        val kv = line.split(Regex("\\s+")).mapNotNull { tok ->
            val i = tok.indexOf('=')
            if (i <= 0) null else tok.substring(0, i) to tok.substring(i + 1)
        }.toMap()
        val state = kv["state"] ?: return null
        val waiting = kv["waiting"]?.takeIf { it == "panel" || it == "pad" }
        if (waiting != lastWaiting) {
            lastWaiting = waiting
            if (waiting != null) Log.i(TAG, "supervisor: waiting for $waiting")
        }
        return Status(state, kv["touch"] ?: "?", kv["keys"]?.toIntOrNull() ?: 0, kv["panic"]?.toIntOrNull() ?: 0, waiting, line)
    }

    /** Polls the status file for up to 1 s waiting for [expectState]; returns the last line read. */
    private suspend fun pollStatus(shell: PrivShell, expectState: String): Status? {
        val deadline = System.currentTimeMillis() + STATUS_POLL_MS
        var last: Status? = null
        while (true) {
            last = readStatus(shell) ?: last
            if (last?.state == expectState) break
            if (System.currentTimeMillis() >= deadline) break
            delay(100)
        }
        last?.let { logStatus(it) }
        return last
    }

    private fun logStatus(st: Status) {
        if (st.raw == lastStatusRaw) return
        lastStatusRaw = st.raw
        Log.i(TAG, "supervisor: status ${st.raw}")
    }

    /**
     * The panic chord makes the daemon go idle by itself and bump the `panic` counter. Mirrors that:
     * persists touchOffsetEnabled=false for the profile that was active, notifies, and writes the
     * idle config so our view of the daemon matches. Returns true if a panic was handled.
     */
    private suspend fun checkPanic(shell: PrivShell, st: Status): Boolean {
        if (st.panic <= lastPanic) { lastPanic = st.panic; return false }
        lastPanic = st.panic
        val prof = (appliedTarget ?: override ?: desired)?.profile
        Log.i(TAG, "supervisor: panic chord -> touch offset disabled for ${prof?.name ?: "?"}")
        if (prof != null) Store.saveProfile(prof.copy(touchOffsetEnabled = false), prof.name)
        failedLatched = true
        val idleConf = withTouchRotation(IDLE_CONF)
        shell.writeFile(CONF, idleConf)
        shell.exec(listOf("kill", "-USR1", servePid.toString()))
        appliedConf = idleConf
        appliedTarget = null
        Log.i(TAG, "supervisor: idle")
        restorePointer(shell)
        _state.value = DaemonState.PanicStopped(prof?.name ?: "?")
        if (prof != null) onPanic(prof.name)
        return true
    }

    // ---- pointer ----

    /**
     * Re-applies setPointerHidden(true) every 200 ms while the active config uses a wheel target.
     * Android resets the pointer icon back to the arrow on the first mouse event delivered to a
     * newly-focused window, so a single call isn't enough -- the first wheel notch would show
     * (and leave visible) the cursor.
     */
    private fun startPointerRefresh(shell: PrivShell) {
        if (pointerHidden && pointerRefreshJob?.isActive == true) return
        pointerRefreshJob?.cancel()
        pointerHidden = true
        pointerRefreshJob = scope.launch {
            runCatching { shell.setPointerHidden(true) }
            Log.i(TAG, "pointer: hidden")
            while (isActive) {
                delay(200)
                runCatching { shell.setPointerHidden(true) }
            }
        }
    }

    /** Restores the pointer if it was hidden; no-op otherwise. */
    private suspend fun restorePointer(shell: PrivShell?) {
        pointerRefreshJob?.cancel()
        pointerRefreshJob = null
        if (!pointerHidden) return
        pointerHidden = false
        runCatching { shell?.setPointerHidden(false) }
        Log.i(TAG, "pointer: restored")
    }

    // ---- logging / failure ----

    private suspend fun lastLogLine(shell: PrivShell): String =
        shell.exec(listOf("tail", "-n", "2", LOG)).out.trim().lines().lastOrNull()?.take(160) ?: ""

    /** One-line activation summary ("supervisor: activate profile=OSRS touch=on -2 -10 keys=18"),
     *  parsed straight out of the exact config text handed to the daemon -- so it's diagnosable
     *  from logcat whether touch.offset made it into the config that was actually signalled. */
    private fun activateLogLine(profile: String, conf: String): String {
        val directives = setOf(
            "deadzone", "ls.invert_y", "ls.invert_x", "rs.invert_y", "rs.invert_x", "wheel_repeat_ms", "touch.offset", "touch.rotation", "touch.generation", "touch.display", "idle",
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
        return "supervisor: activate profile=$profile touch=$touch keys=$keys"
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
        _state.value = DaemonState.Backoff(attempt, backoffUntil, reason)
    }

    companion object {
        private const val TAG = "DpadMgr"
        const val CONF = "/data/local/tmp/dpadkeys.conf"
        const val PIDFILE = "/data/local/tmp/dpadkeys.pid"
        const val STATUS = "/data/local/tmp/dpadkeys.status"
        const val LOG = "/data/local/tmp/dpadkeys.log"
        private const val TARGET_DEBOUNCE_MS = 150L
        private const val STATUS_POLL_MS = 1000L
        private const val KEY_TOUCH_GENERATION = "touch_generation"
        /** A config with no bindings and no touch.offset: the daemon serves but grabs nothing. */
        const val IDLE_CONF = "# generated by Odin DPad Keys (idle)\nidle 1\n"
    }
}
