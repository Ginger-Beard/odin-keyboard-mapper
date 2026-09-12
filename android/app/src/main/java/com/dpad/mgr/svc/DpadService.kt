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
import com.dpad.mgr.core.BinaryInstaller
import com.dpad.mgr.core.DaemonState
import com.dpad.mgr.core.ForegroundWatcher
import com.dpad.mgr.core.PadInspector
import com.dpad.mgr.core.Store
import com.dpad.mgr.core.Supervisor
import com.dpad.mgr.priv.PrivProbe
import com.dpad.mgr.priv.PrivSource
import com.dpad.mgr.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class DpadService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var probe: PrivProbe
    private lateinit var installer: BinaryInstaller
    private lateinit var watcher: ForegroundWatcher
    private lateinit var supervisor: Supervisor
    private val probeMutex = Mutex()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Store.init(this)
        createChannels()
        startForeground(NOTIF_ID, buildNotification("Odin DPad Keys active"))
        probe = PrivProbe(this)
        installer = BinaryInstaller(this)
        watcher = ForegroundWatcher(this, scope)
        supervisor = Supervisor(scope, { probe.state.value.shell }, installer) { reason -> notifyFailed(reason) }
        ServiceState.serviceRunning.value = true

        scope.launch { probe.state.collect { ServiceState.priv.value = it } }
        scope.launch { supervisor.state.collect { ServiceState.daemon.value = it; updateNotification(it) } }
        scope.launch { watcher.foreground.collect { ServiceState.foreground.value = it } }
        scope.launch {
            combine(watcher.foreground, Store.data) { pkg, data -> pkg to (pkg?.let { data.profileFor(it) }) }
                .distinctUntilChanged()
                .collect { (pkg, prof) -> supervisor.setTarget(if (prof != null) pkg else null, prof) }
        }
        scope.launch { recheck("service start") }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_RECHECK -> scope.launch { recheck("re-check") }
            ACTION_STOP_DAEMON -> {
                Log.i(TAG, "action: stop daemon")
                supervisor.stop()
                ServiceState.message.value = "Daemon stop requested"
            }
            ACTION_TEST -> {
                val name = intent.getStringExtra(EXTRA_PROFILE) ?: "OSRS"
                val secs = intent.getIntExtra(EXTRA_SECONDS, 30)
                val p = Store.data.value.profile(name)
                if (p == null) {
                    Log.w(TAG, "action: test profile '$name' not found")
                    ServiceState.message.value = "Profile '$name' not found"
                } else {
                    Log.i(TAG, "action: test profile=$name secs=$secs")
                    ServiceState.message.value = "Testing $name for $secs s"
                    supervisor.test(p, secs)
                }
            }
            ACTION_STOP_SERVICE -> {
                scope.launch {
                    supervisor.stop()
                    watcher.stop()
                    stopSelf()
                }
            }
        }
        return START_STICKY
    }

    private suspend fun recheck(why: String) = probeMutex.withLock {
        Log.i(TAG, "probe: begin ($why) version=${com.dpad.mgr.BuildConfig.VERSION_CODE}")
        val st = probe.recheck(force = why == "re-check")
        val shell = st.shell
        if (shell != null) {
            val bin = installer.resolve(shell, force = why == "re-check")
            ServiceState.binaryPath.value = bin
            runCatching { watcher.start(shell) }.onFailure { Log.w(TAG, "watcher: start failed $it") }
        } else {
            runCatching { watcher.stop() }
            ServiceState.binaryPath.value = null
            Log.i(TAG, "binary: skipped (no privileged shell)")
        }
        ServiceState.pad.value = PadInspector.inspect(shell)
        supervisor.reset()
    }

    override fun onDestroy() {
        ServiceState.serviceRunning.value = false
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
            .setContentTitle("Odin DPad Keys active")
            .setContentText(text)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(s: DaemonState) {
        val text = when (s) {
            is DaemonState.Running -> "Mapping ${s.profile}" + (s.pkg?.let { " for $it" } ?: " (test)")
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

    companion object {
        private const val TAG = "DpadMgr"
        const val CH_STATUS = "status"
        const val CH_ALERT = "alert"
        const val NOTIF_ID = 1
        const val NOTIF_FAIL_ID = 2
        const val ACTION_START = "com.dpad.mgr.action.START"
        const val ACTION_RECHECK = "com.dpad.mgr.action.RECHECK"
        const val ACTION_STOP_DAEMON = "com.dpad.mgr.action.STOP_DAEMON"
        const val ACTION_TEST = "com.dpad.mgr.action.TEST"
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
