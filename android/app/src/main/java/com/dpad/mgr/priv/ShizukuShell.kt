package com.dpad.mgr.priv

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import android.util.Log
import com.dpad.mgr.BuildConfig
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import rikka.shizuku.Shizuku

/** Shizuku-backed shell: everything is delegated to [ShizukuUserService] over AIDL. */
class ShizukuShell(ctx: Context) : PrivShell {
    override val source = PrivSource.SHIZUKU
    private val appCtx = ctx.applicationContext
    @Volatile private var service: IUserService? = null
    private val args = Shizuku.UserServiceArgs(ComponentName(appCtx, ShizukuUserService::class.java))
        .daemon(false)
        .processNameSuffix("dpad")
        .debuggable(false)
        .version(BuildConfig.VERSION_CODE)
    @Volatile var lastProbe: String = "not probed"
        private set

    private var bound = CompletableDeferred<IUserService?>()
    private val conn = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val s = if (binder != null && binder.pingBinder()) IUserService.Stub.asInterface(binder) else null
            service = s
            if (!bound.isCompleted) bound.complete(s)
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
        }
    }

    private val binderReceived = CompletableDeferred<Unit>()
    private val binderListener = Shizuku.OnBinderReceivedListener { if (!binderReceived.isCompleted) binderReceived.complete(Unit) }

    /** Logs `probe: shizuku ping=<bool>` (+ permission/bind details) and returns true if usable. */
    suspend fun probe(): Boolean {
        runCatching { Shizuku.addBinderReceivedListenerSticky(binderListener) }
        val ping = runCatching { Shizuku.pingBinder() }.getOrDefault(false)
        if (!ping) {
            Log.i(TAG, "probe: shizuku ping=false")
            lastProbe = "Shizuku not running"
            return false
        }
        var granted = runCatching { Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED }.getOrDefault(false)
        if (!granted) {
            val result = CompletableDeferred<Boolean>()
            val l = object : Shizuku.OnRequestPermissionResultListener {
                override fun onRequestPermissionResult(requestCode: Int, grantResult: Int) {
                    if (requestCode == REQ) result.complete(grantResult == PackageManager.PERMISSION_GRANTED)
                }
            }
            Shizuku.addRequestPermissionResultListener(l)
            try {
                runCatching { Shizuku.requestPermission(REQ) }
                granted = withTimeoutOrNull(30_000) { result.await() } ?: false
            } finally {
                Shizuku.removeRequestPermissionResultListener(l)
            }
        }
        if (!granted) {
            Log.i(TAG, "probe: shizuku ping=true permission=denied")
            lastProbe = "Shizuku permission denied"
            return false
        }
        if (service == null) {
            bound = CompletableDeferred()
            runCatching { Shizuku.bindUserService(args, conn) }
                .onFailure { Log.w(TAG, "probe: shizuku bind threw $it") }
            withTimeoutOrNull(15_000) { bound.await() }
        }
        val s = service
        val id = if (s != null) execRaw(s, listOf("id")).out.trim() else "<bind failed>"
        Log.i(TAG, "probe: shizuku ping=true permission=granted bind=${s != null} id=$id")
        lastProbe = if (s != null) "Shizuku ok ($id)" else "Shizuku bind failed"
        return s != null
    }

    private fun execRaw(s: IUserService, argv: List<String>): ExecResult = try {
        val b = s.exec(argv.toTypedArray())
        ExecResult(b.getInt("rc", 255), b.getString("out") ?: "")
    } catch (e: Exception) {
        ExecResult(255, "shizuku exec failed: $e")
    }

    private fun svc(): IUserService? = service

    override suspend fun exec(argv: List<String>): ExecResult = withContext(Dispatchers.IO) {
        val s = svc() ?: return@withContext ExecResult(255, "shizuku: not bound")
        execRaw(s, argv)
    }

    override suspend fun execLong(argv: List<String>, timeoutMs: Long): ExecResult = withContext(Dispatchers.IO) {
        val s = svc() ?: return@withContext ExecResult(255, "shizuku: not bound")
        try {
            PrivShell.requireSafe(argv)
            val b = s.execTimeout(argv.toTypedArray(), timeoutMs.coerceIn(1L, Int.MAX_VALUE.toLong()).toInt())
            ExecResult(b.getInt("rc", 255), b.getString("out") ?: "")
        } catch (e: Exception) {
            ExecResult(255, "shizuku exec failed: $e")
        }
    }

    override suspend fun spawn(argv: List<String>, pidfile: String): Int = withContext(Dispatchers.IO) {
        PrivShell.requireSafe(argv)
        runCatching { svc()?.spawn(argv.toTypedArray(), pidfile) ?: -1 }.getOrDefault(-1)
    }

    override suspend fun kill(pid: Int) { withContext(Dispatchers.IO) { runCatching { svc()?.kill(pid) } } }

    override suspend fun isAlive(pid: Int): Boolean = withContext(Dispatchers.IO) {
        runCatching { svc()?.isAlive(pid) ?: false }.getOrDefault(false)
    }

    override suspend fun writeFile(path: String, content: String): Boolean = withContext(Dispatchers.IO) {
        runCatching { svc()?.writeFile(path, content) ?: false }.getOrDefault(false)
    }

    override suspend fun startTail(cb: (String) -> Unit) {
        withContext(Dispatchers.IO) {
            runCatching {
                svc()?.startTail(object : ILineCallback.Stub() {
                    override fun onLine(line: String?) { if (line != null) cb(line) }
                })
            }.onFailure { Log.w(TAG, "shizuku: startTail failed $it") }
        }
    }

    override suspend fun stopTail() { withContext(Dispatchers.IO) { runCatching { svc()?.stopTail() } } }

    fun release() {
        runCatching { Shizuku.unbindUserService(args, conn, true) }
        runCatching { Shizuku.removeBinderReceivedListener(binderListener) }
        service = null
    }

    companion object {
        private const val TAG = "DpadMgr"
        private const val REQ = 4242
    }
}
