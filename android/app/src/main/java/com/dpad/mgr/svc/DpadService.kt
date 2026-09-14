package com.dpad.mgr.svc

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import com.dpad.mgr.R
import com.dpad.mgr.core.BinaryInstaller
import com.dpad.mgr.core.DaemonState
import com.dpad.mgr.core.ForegroundWatcher
import com.dpad.mgr.core.PadInspector
import com.dpad.mgr.core.SourceNames
import com.dpad.mgr.core.Store
import com.dpad.mgr.core.Supervisor
import com.dpad.mgr.priv.PrivProbe
import com.dpad.mgr.priv.PrivSource
import com.dpad.mgr.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuProvider

class DpadService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var probe: PrivProbe
    private lateinit var installer: BinaryInstaller
    private lateinit var watcher: ForegroundWatcher
    private lateinit var supervisor: Supervisor
    private val probeMutex = Mutex()
    /** The privileged shell instance the watcher is currently tailing for, or null. Used to
     *  avoid restarting the tail (and leaking a logcat process) on every re-check when the
     *  same privilege is still held. */
    private var watcherStartedFor: com.dpad.mgr.priv.PrivShell? = null

    /** Job for [binderAcquisitionLoop], running whenever privilege is NONE and Shizuku is
     *  installed; null when not needed (privilege held, or Shizuku not installed). */
    private var binderAcquisitionJob: Job? = null

    /** Fires whenever the Shizuku binder (re)appears, including after the service was started
     *  before Shizuku itself was up. Re-runs the probe so privilege is picked up without the
     *  user having to hit Re-check by hand. Sticky, so it also fires immediately on registration
     *  if the binder is already available. */
    private val shizukuBinderListener = Shizuku.OnBinderReceivedListener {
        Log.i(TAG, "shizuku: binder received, re-probing")
        scope.launch { recheck("shizuku binder received") }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Store.init(this)
        createChannels()
        startForeground(NOTIF_ID, buildNotification("${getString(R.string.app_name)} active"))
        probe = PrivProbe(this)
        installer = BinaryInstaller(this)
        watcher = ForegroundWatcher(this, scope)
        supervisor = Supervisor(
            scope, { probe.state.value.shell }, installer,
            deviceName = getString(R.string.app_name),
            onFailed = { reason -> notifyFailed(reason) },
            onPanic = { profile -> notifyPanic(profile) },
        )
        ServiceState.supervisor = supervisor
        ServiceState.serviceRunning.value = true

        scope.launch {
            probe.state.collect { st ->
                ServiceState.priv.value = st
                manageBinderAcquisitionLoop(st.source)
            }
        }
        scope.launch {
            supervisor.state.collect { s ->
                ServiceState.daemon.value = s
                updateNotification(s)
                // Clear the test countdown once the daemon state shows the test truly ended:
                // fully idle, or running/starting the real assigned target (pkg != null). Backoff
                // and Failed are left alone since they can happen mid-test as well as mid-run.
                val testEnded = when (s) {
                    is DaemonState.Idle, is DaemonState.Stopped, is DaemonState.Running -> true
                    else -> false
                }
                if (testEnded) ServiceState.testEndsAtMs.value = null
            }
        }
        scope.launch { watcher.foreground.collect { ServiceState.foreground.value = it } }
        scope.launch {
            combine(watcher.foreground, Store.data) { pkg, data -> pkg to (pkg?.let { data.profileFor(it) }) }
                .distinctUntilChanged()
                .collect { (pkg, prof) -> supervisor.setTarget(if (prof != null) pkg else null, prof) }
        }
        scope.launch { recheck("service start") }
        runCatching { Shizuku.addBinderReceivedListenerSticky(shizukuBinderListener) }
            .onFailure { Log.w(TAG, "shizuku: addBinderReceivedListenerSticky failed $it") }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_RECHECK -> {
                ServiceState.lastAction.value = "Re-checking…"
                scope.launch { recheck("re-check") }
            }
            ACTION_STOP_DAEMON -> {
                if (!isDaemonActive()) {
                    Log.i(TAG, "action: stop daemon (nothing running)")
                    ServiceState.lastAction.value = "Nothing is running"
                } else {
                    Log.i(TAG, "action: stop daemon")
                    supervisor.stop()
                    ServiceState.testEndsAtMs.value = null
                    ServiceState.lastAction.value = "Stopping…"
                }
            }
            ACTION_TEST -> {
                val name = intent.getStringExtra(EXTRA_PROFILE)
                val secs = intent.getIntExtra(EXTRA_SECONDS, 30)
                val p = name?.let { Store.data.value.profile(it) }
                when {
                    name.isNullOrBlank() -> {
                        Log.w(TAG, "action: test with no profile selected")
                        ServiceState.lastAction.value = "Select a profile first"
                    }
                    p == null -> {
                        Log.w(TAG, "action: test profile '$name' not found")
                        ServiceState.lastAction.value = "Profile '$name' not found"
                    }
                    else -> {
                        Log.i(TAG, "action: test profile=$name secs=$secs")
                        ServiceState.testEndsAtMs.value = System.currentTimeMillis() + secs * 1000L
                        ServiceState.lastAction.value = "Starting test of $name…"
                        supervisor.test(p, secs)
                    }
                }
            }
            ACTION_STOP_SERVICE -> {
                scope.launch {
                    supervisor.stop()
                    watcher.stop()
                    watcherStartedFor = null
                    stopSelf()
                }
            }
            ACTION_STOP_TEST -> {
                if (ServiceState.testEndsAtMs.value == null) {
                    Log.i(TAG, "action: stop test (nothing running)")
                    ServiceState.lastAction.value = "Nothing is running"
                } else {
                    Log.i(TAG, "action: stop test")
                    supervisor.stopTest()
                    ServiceState.testEndsAtMs.value = null
                    ServiceState.lastAction.value = "Stopping…"
                }
            }
            ACTION_SUSPEND -> {
                Log.i(TAG, "action: suspend (calibration)")
                supervisor.suspend()
            }
            ACTION_RESUME -> {
                Log.i(TAG, "action: resume (calibration done)")
                supervisor.resume()
            }
            ACTION_RESTART_DAEMON -> {
                Log.i(TAG, "action: restart daemon")
                supervisor.restartDaemon()
                ServiceState.lastAction.value = "Restarting daemon…"
            }
            ACTION_UPDATE_CONFIG_LIVE -> {
                val name = intent.getStringExtra(EXTRA_PROFILE)
                val p = name?.let { Store.data.value.profile(it) }
                if (p == null) {
                    Log.w(TAG, "action: updateConfigLive: profile '$name' not found")
                } else {
                    scope.launch { supervisor.updateConfigLive(p) }
                }
            }
        }
        return START_STICKY
    }

    /** True if the daemon is mapping or actively trying to (not idle/failed/panic-stopped). */
    private fun isDaemonActive(): Boolean = when (ServiceState.daemon.value) {
        is DaemonState.Running, is DaemonState.Testing, is DaemonState.Starting, is DaemonState.Backoff -> true
        else -> false
    }

    private suspend fun recheck(why: String) = probeMutex.withLock {
        Log.i(TAG, "probe: begin ($why) version=${com.dpad.mgr.BuildConfig.VERSION_CODE}")
        val st = probe.recheck(force = why == "re-check")
        val shell = st.shell
        if (shell != null) {
            val bin = installer.resolve(shell, force = why == "re-check")
            ServiceState.binaryPath.value = bin
            if (watcherStartedFor !== shell) {
                runCatching { watcher.start(shell) }
                    .onSuccess { watcherStartedFor = shell }
                    .onFailure { Log.w(TAG, "watcher: start failed $it") }
            } else {
                Log.i(TAG, "watcher: already running for this privilege, skip restart")
            }
        } else {
            runCatching { watcher.stop() }
            watcherStartedFor = null
            ServiceState.binaryPath.value = null
            Log.i(TAG, "binary: skipped (no privileged shell)")
        }
        ServiceState.pad.value = PadInspector.inspect(shell)
        supervisor.reset()
    }

    /** Starts or stops [binderAcquisitionLoop] to match the current privilege source: running
     *  only while privilege is NONE and Shizuku is installed. Called on every probe result, so
     *  it both starts the loop (service start, or privilege dropping back to NONE e.g. Shizuku
     *  restarting) and stops it (privilege acquired via either source). */
    private fun manageBinderAcquisitionLoop(source: PrivSource) {
        if (source != PrivSource.NONE) {
            if (binderAcquisitionJob != null) Log.i(TAG, "shizuku: binder acquisition loop stopping (privilege=${source.name})")
            binderAcquisitionJob?.cancel()
            binderAcquisitionJob = null
            return
        }
        if (binderAcquisitionJob?.isActive == true) return
        if (!isShizukuInstalled()) return
        Log.i(TAG, "shizuku: binder acquisition loop starting")
        binderAcquisitionJob = scope.launch { binderAcquisitionLoop() }
    }

    /** Shizuku pushes its binder to apps when its server starts and when an app's activity
     *  starts; a background-only service that came up before the Shizuku server (e.g. at boot)
     *  is left out until the user happens to open our activity. This polls pingBinder() and, if
     *  it's not up yet, proactively asks the Shizuku manager to deliver the binder to us. Polls
     *  every 3s for the first 2 minutes (covers the common "server starting shortly after us at
     *  boot" case quickly), then every 15s thereafter. Stops as soon as pingBinder() succeeds,
     *  handing off to [recheck] to actually pick up the privilege (and to
     *  [manageBinderAcquisitionLoop], driven by the resulting probe state, to decide whether to
     *  restart the loop). */
    private suspend fun binderAcquisitionLoop() {
        val startMs = System.currentTimeMillis()
        var attempt = 0
        var lastRequestLogMs = 0L
        while (true) {
            val pinged = runCatching { Shizuku.pingBinder() }.getOrDefault(false)
            if (pinged) {
                Log.i(TAG, "shizuku: binder acquisition loop: ping ok, re-probing")
                recheck("binder acquisition: ping ok")
                return
            }
            attempt++
            runCatching { ShizukuProvider.requestBinderForNonProviderProcess(applicationContext) }
                .onFailure { Log.w(TAG, "shizuku: requestBinderForNonProviderProcess failed $it") }
            val now = System.currentTimeMillis()
            if (now - lastRequestLogMs >= 30_000) {
                Log.i(TAG, "shizuku: requested binder (attempt $attempt)")
                lastRequestLogMs = now
            }
            val elapsed = now - startMs
            delay(if (elapsed < 120_000L) 3_000L else 15_000L)
        }
    }

    private fun isShizukuInstalled(): Boolean =
        runCatching { packageManager.getPackageInfo(SHIZUKU_PKG, 0) }.isSuccess

    /** Swiping the app from recents (task removed) must not stop the daemon: restart the
     *  service immediately so the mapping keeps running. Belt-and-suspenders alongside
     *  android:stopWithTask="false" on the service and excludeFromRecents on MainActivity. */
    override fun onTaskRemoved(rootIntent: Intent?) {
        Log.i(TAG, "onTaskRemoved: restarting service")
        ensureStarted(this)
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        ServiceState.serviceRunning.value = false
        supervisor.shutdown()
        ServiceState.supervisor = null
        runCatching { Shizuku.removeBinderReceivedListener(shizukuBinderListener) }
        scope.cancel()
        super.onDestroy()
    }

    // ---- notifications ----

    private fun createChannels() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH_STATUS, "Service status", NotificationManager.IMPORTANCE_LOW).apply {
            setShowBadge(false); setSound(null, null); enableVibration(false)
        })
        nm.createNotificationChannel(NotificationChannel(CH_ALERT, "Problems", NotificationManager.IMPORTANCE_DEFAULT))
    }

    private fun buildNotification(text: String): Notification {
        val pi = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CH_STATUS)
            .setSmallIcon(android.R.drawable.ic_menu_directions)
            .setContentTitle("${getString(R.string.app_name)} active")
            .setContentText(text)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(s: DaemonState) {
        val text = when (s) {
            is DaemonState.Running -> "Mapping ${s.pkg} with ${s.profile}"
            is DaemonState.Testing -> "Testing ${s.profile}"
            else -> "Daemon ${s.label}"
        }
        runCatching {
            getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification(text))
        }
    }

    private fun notifyFailed(reason: String) {
        val pi = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val n = Notification.Builder(this, CH_ALERT)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle("Set AYN Controller Style to Xbox")
            .setContentText("dpadkeys keeps exiting: $reason")
            .setStyle(Notification.BigTextStyle().bigText("The daemon failed 5 times in a row. Set the AYN Controller Style for this app to Xbox, then Re-check.\n$reason"))
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        runCatching { getSystemService(NotificationManager::class.java).notify(NOTIF_FAIL_ID, n) }
    }

    private fun notifyPanic(profile: String) {
        val pi = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val chord = Store.data.value.profile(profile)?.panicChord
        val chordPart = chord?.let { "hold ${SourceNames.chordLabel(it)}" } ?: "the panic chord"
        val text = "Touch offset disabled by panic chord ($chordPart). Re-enable in the app."
        val n = Notification.Builder(this, CH_ALERT)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle("Touch offset disabled by panic chord")
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText("$text\nProfile: $profile"))
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        runCatching { getSystemService(NotificationManager::class.java).notify(NOTIF_PANIC_ID, n) }
    }

    companion object {
        private const val TAG = "DpadMgr"
        private const val SHIZUKU_PKG = "moe.shizuku.privileged.api"
        const val CH_STATUS = "status"
        const val CH_ALERT = "alert"
        const val NOTIF_ID = 1
        const val NOTIF_FAIL_ID = 2
        const val NOTIF_PANIC_ID = 3
        const val ACTION_START = "com.dpad.mgr.action.START"
        const val ACTION_RECHECK = "com.dpad.mgr.action.RECHECK"
        const val ACTION_STOP_DAEMON = "com.dpad.mgr.action.STOP_DAEMON"
        const val ACTION_TEST = "com.dpad.mgr.action.TEST"
        const val ACTION_STOP_TEST = "com.dpad.mgr.action.STOP_TEST"
        const val ACTION_SUSPEND = "com.dpad.mgr.action.SUSPEND"
        const val ACTION_RESUME = "com.dpad.mgr.action.RESUME"
        const val ACTION_UPDATE_CONFIG_LIVE = "com.dpad.mgr.action.UPDATE_CONFIG_LIVE"
        const val ACTION_RESTART_DAEMON = "com.dpad.mgr.action.RESTART_DAEMON"
        const val ACTION_STOP_SERVICE = "com.dpad.mgr.action.STOP_SERVICE"
        const val EXTRA_PROFILE = "profile"
        const val EXTRA_SECONDS = "seconds"

        fun send(ctx: Context, action: String, profile: String? = null, seconds: Int? = null) {
            val i = Intent(ctx, DpadService::class.java).setAction(action)
            profile?.let { i.putExtra(EXTRA_PROFILE, it) }
            seconds?.let { i.putExtra(EXTRA_SECONDS, it) }
            ctx.startForegroundService(i)
        }

        fun ensureStarted(ctx: Context) = send(ctx, ACTION_START)
    }
}
