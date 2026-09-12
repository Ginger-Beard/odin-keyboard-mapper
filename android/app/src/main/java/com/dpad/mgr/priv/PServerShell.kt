package com.dpad.mgr.priv

import android.content.Context
import android.os.IBinder
import android.os.Parcel
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.atomic.AtomicInteger

/**
 * Root channel through AYN's "PServerBinder" system service. The service accepts a
 * command string via a raw binder transaction and runs it as root, with no output
 * channel, so every command is wrapped so that its output and exit code land in a
 * per-call directory under our cache dir that we then poll.
 *
 * Two wrapping modes are tried during [probe]:
 *  - SCRIPT: we write `cmd.sh` ourselves and transact `sh <path>` (no quoting at all,
 *    robust to Runtime.exec-style whitespace splitting on the far side).
 *  - INLINE: `sh -c '<cmd> > out 2>&1; echo $? > rc; ...'` as specified.
 */
class PServerShell(ctx: Context) : PrivShell {
    override val source = PrivSource.ROOT_PSERVER

    enum class Mode { SCRIPT, INLINE }

    private val appCtx = ctx.applicationContext
    private val psDir = File(appCtx.cacheDir, "ps")
    private val tailDir = File(appCtx.cacheDir, "tail")
    private val tailFile = File(tailDir, "events.log")
    private val mutex = Mutex()
    private val seq = AtomicInteger((System.currentTimeMillis() % 100000).toInt())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile var mode: Mode = Mode.SCRIPT
        private set
    /** When true, poll files live under /data/local/tmp (world-traversable) instead of cacheDir. */
    @Volatile private var useTmpPoll: Boolean = false
    private val tmpPollDir = File("/data/local/tmp/dpadmgr_ps")
    @Volatile private var binder: IBinder? = null
    @Volatile private var tailPid = -1
    private var tailJob: Job? = null

    /** Human-readable summary of the last probe, for the status card. */
    @Volatile var lastProbe: String = "not probed"
        private set

    private fun getService(): IBinder? = runCatching {
        val sm = Class.forName("android.os.ServiceManager")
        sm.getMethod("getService", String::class.java).invoke(null, "PServerBinder") as IBinder?
    }.getOrElse { e -> Log.w(TAG, "probe: PServerBinder getService threw $e"); null }

    /** Sends cmd; returns "ok" or "exception <msg>". Retries per spec. */
    private fun transact(cmd: String): String {
        val b = binder ?: return "no-binder"
        var last: Throwable? = null
        val attempts = listOf(
            Triple(false, 0, "plain"),
            Triple(true, 0, "token"),
            Triple(false, IBinder.FLAG_ONEWAY, "oneway"),
        )
        for ((token, flags, label) in attempts) {
            val data = Parcel.obtain()
            val reply = Parcel.obtain()
            try {
                if (token) data.writeInterfaceToken("PServerBinder")
                data.writeString(cmd)
                data.writeString("1")
                val r = b.transact(0, data, reply, flags)
                return if (label == "plain") "ok" else "ok($label)" + if (!r) "/returned-false" else ""
            } catch (e: Throwable) {
                last = e
                Log.w(TAG, "probe: PServerBinder transact[$label] threw $e")
            } finally {
                reply.recycle()
                data.recycle()
            }
        }
        return "exception ${last?.javaClass?.simpleName}: ${last?.message}"
    }

    private class Call(val dir: File, val out: File, val rc: File)

    private fun newCall(): Call {
        if (useTmpPoll) {
            // The command (running privileged) creates the dir; the app only polls.
            val d = File(tmpPollDir, seq.incrementAndGet().toString())
            return Call(d, File(d, "out"), File(d, "rc"))
        }
        val d = File(psDir, seq.incrementAndGet().toString())
        d.mkdirs()
        d.setReadable(true, false); d.setExecutable(true, false); d.setWritable(true, false)
        psDir.setReadable(true, false); psDir.setExecutable(true, false); psDir.setWritable(true, false)
        return Call(d, File(d, "out"), File(d, "rc"))
    }

    /** Builds the transact string for [inner] in the given mode; [inner] must contain no single quotes. */
    private fun buildCommand(call: Call, inner: String, m: Mode): String {
        val d = call.dir.path
        val ps = if (useTmpPoll) tmpPollDir.path else psDir.path
        if (useTmpPoll) {
            val body = "mkdir -p $d; ( $inner ) > $d/out 2>&1; echo \$? > $d/rc; chmod -R 755 $ps; chmod 644 $d/out $d/rc"
            return "sh -c '$body'"
        }
        val body = "( $inner ) > $d/out 2>&1; echo \$? > $d/rc; chmod -R 755 $ps; chmod 644 $d/out $d/rc"
        return when (m) {
            Mode.INLINE -> "sh -c '$body'"
            Mode.SCRIPT -> {
                val script = File(call.dir, "cmd.sh")
                script.writeText("#!/system/bin/sh\n$body\n")
                script.setReadable(true, false); script.setExecutable(true, false)
                "sh ${script.path}"
            }
        }
    }

    private data class RawResult(val rc: Int?, val out: String, val transact: String)

    /** Runs [inner] as root, waits up to [timeoutMs] for the rc file. rc==null means no result. */
    private suspend fun runRaw(inner: String, m: Mode = mode, timeoutMs: Long = 3000): RawResult =
        mutex.withLock {
            withContext(Dispatchers.IO) {
                require(!inner.contains('\'')) { "inner command must not contain quotes" }
                if (binder == null) binder = getService()
                val call = newCall()
                try {
                    val cmd = buildCommand(call, inner, m)
                    val tx = async { transact(cmd) }
                    val deadline = System.currentTimeMillis() + timeoutMs
                    var rc: Int? = null
                    while (isActive) {
                        if (call.rc.exists()) {
                            rc = call.rc.readText().trim().toIntOrNull()
                            if (rc != null) break
                        }
                        if (tx.isCompleted && System.currentTimeMillis() > deadline) break
                        if (System.currentTimeMillis() > deadline + 2000) break
                        delay(40)
                    }
                    val out = if (call.out.exists()) runCatching { call.out.readText() }.getOrDefault("") else ""
                    val txs = if (tx.isCompleted) tx.await() else "pending"
                    RawResult(rc, out, txs)
                } finally {
                    launch { delay(200); runCatching { call.dir.deleteRecursively() } }
                }
            }
        }

    /**
     * Probe: getService, then `id` in SCRIPT mode, falling back to INLINE. Logs the
     * spec'd `probe:` line. Returns true when the command ran as uid 0.
     */
    suspend fun probe(): Boolean {
        binder = getService()
        val gs = if (binder == null) "null" else "ok"
        if (binder == null) {
            lastProbe = "PServerBinder service not found"
            Log.i(TAG, "probe: PServerBinder getService=null transact=n/a id=n/a")
            return false
        }
        var r = runRaw("id", Mode.SCRIPT)
        var m = Mode.SCRIPT
        if (r.rc == null) {
            r = runRaw("id", Mode.INLINE)
            m = Mode.INLINE
        }
        var idOut = r.out.trim().ifEmpty { if (r.rc == null) "<no output within timeout>" else "<empty>" }
        Log.i(TAG, "probe: PServerBinder getService=$gs transact=${r.transact} id=$idOut mode=${m.name.lowercase()} rc=${r.rc}")
        var ok = r.rc != null && idOut.contains("uid=0")
        if (ok) mode = m
        if (!ok) {
            // Fallback diagnostic: the app's private cacheDir may be untraversable by
            // pservice's SELinux domain, so retry with a world-traversable /data/local/tmp
            // poll path to learn whether the command executes at all.
            val t = probeTmp()
            if (t != null) {
                idOut = t.second.trim().ifEmpty { "<empty>" }
                ok = t.first != null && idOut.contains("uid=0")
                Log.i(TAG, "probe: PServerBinder tmp-poll rc=${t.first} id=$idOut")
                if (ok) { mode = Mode.INLINE; useTmpPoll = true }
            }
        }
        lastProbe = if (ok) "root ok ($idOut)" else "no root: transact=${r.transact} id=$idOut"
        return ok
    }

    /** Runs `id` through INLINE mode but polling /data/local/tmp (world-traversable). */
    private suspend fun probeTmp(): Pair<Int?, String>? = withContext(Dispatchers.IO) {
        if (binder == null) return@withContext null
        val rcFile = File("/data/local/tmp/dpadmgr.rc")
        val outFile = File("/data/local/tmp/dpadmgr.out")
        runCatching { rcFile.delete(); outFile.delete() }
        val body = "id > ${outFile.path} 2>&1; echo \$? > ${rcFile.path}; chmod 644 ${outFile.path} ${rcFile.path}"
        val cmd = "sh -c '$body'"
        transact(cmd)
        val deadline = System.currentTimeMillis() + 3000
        var rc: Int? = null
        while (System.currentTimeMillis() < deadline) {
            if (rcFile.exists()) { rc = rcFile.readText().trim().toIntOrNull(); if (rc != null) break }
            delay(50)
        }
        val out = if (outFile.exists()) runCatching { outFile.readText() }.getOrDefault("") else ""
        rc to out
    }

    override suspend fun exec(argv: List<String>): ExecResult {
        PrivShell.requireSafe(argv)
        return execInner(argv.joinToString(" "))
    }

    private suspend fun execInner(inner: String, timeoutMs: Long = 3000): ExecResult {
        val r = runRaw(inner, timeoutMs = timeoutMs)
        return ExecResult(r.rc ?: 255, if (r.rc == null) "pserver: no result (transact=${r.transact})\n${r.out}" else r.out)
    }

    /** Longer-timeout exec for copies etc. */
    suspend fun execSlow(argv: List<String>, timeoutMs: Long): ExecResult {
        PrivShell.requireSafe(argv)
        return execInner(argv.joinToString(" "), timeoutMs)
    }

    /** Runs a shell snippet built from safe tokens joined with shell operators (used by callers that need `;`). */
    suspend fun sh(vararg parts: List<String>, sep: String = "; "): ExecResult {
        parts.forEach { PrivShell.requireSafe(it) }
        return execInner(parts.joinToString(sep) { it.joinToString(" ") })
    }

    override suspend fun spawn(argv: List<String>, pidfile: String): Int {
        PrivShell.requireSafe(argv)
        require(PrivShell.isSafeToken(pidfile))
        val inner = "nohup ${argv.joinToString(" ")} < /dev/null > $LOG 2>&1 & echo \$!"
        val r = runRaw(inner)
        val pid = r.out.trim().lines().lastOrNull()?.trim()?.toIntOrNull() ?: -1
        Log.i(TAG, "pserver: spawn rc=${r.rc} pid=$pid")
        return pid
    }

    override suspend fun kill(pid: Int) {
        if (pid <= 1) return
        execInner("kill -TERM $pid")
    }

    override suspend fun isAlive(pid: Int): Boolean {
        if (pid <= 1) return false
        return execInner("kill -0 $pid").rc == 0
    }

    override suspend fun writeFile(path: String, content: String): Boolean {
        require(PrivShell.isSafeToken(path))
        val b64 = Base64.encodeToString(content.toByteArray(), Base64.NO_WRAP)
        require(B64.matches(b64))
        val r = execInner("echo $b64 | base64 -d > $path; chmod 644 $path")
        return r.rc == 0
    }

    override suspend fun startTail(cb: (String) -> Unit) {
        stopTail()
        tailDir.mkdirs()
        tailDir.setReadable(true, false); tailDir.setExecutable(true, false); tailDir.setWritable(true, false)
        tailFile.writeText("")
        tailFile.setReadable(true, false); tailFile.setWritable(true, false)
        val inner = "nohup ${PrivShell.TAIL_CMD} < /dev/null >> ${tailFile.path} 2>&1 & echo \$!"
        val r = runRaw(inner)
        tailPid = r.out.trim().lines().lastOrNull()?.trim()?.toIntOrNull() ?: -1
        Log.i(TAG, "pserver: tail logcat pid=$tailPid rc=${r.rc}")
        tailJob = scope.launch {
            var pos = 0L
            val buf = StringBuilder()
            while (isActive) {
                try {
                    val len = tailFile.length()
                    if (len < pos) pos = 0
                    if (len > pos) {
                        RandomAccessFile(tailFile, "r").use { raf ->
                            raf.seek(pos)
                            val bytes = ByteArray((len - pos).toInt())
                            raf.readFully(bytes)
                            pos = len
                            buf.append(String(bytes))
                        }
                        var nl = buf.indexOf('\n')
                        while (nl >= 0) {
                            val line = buf.substring(0, nl).trimEnd('\r')
                            buf.delete(0, nl + 1)
                            if (line.isNotBlank()) runCatching { cb(line) }
                            nl = buf.indexOf('\n')
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "pserver: tail read error $e")
                }
                delay(120)
            }
        }
    }

    override suspend fun stopTail() {
        tailJob?.cancel(); tailJob = null
        val p = tailPid
        tailPid = -1
        if (p > 1) runCatching { execInner("kill -TERM $p") }
    }

    companion object {
        private const val TAG = "DpadMgr"
        const val LOG = "/data/local/tmp/dpadkeys.log"
        private val B64 = Regex("^[A-Za-z0-9+/=]*$")
    }
}
