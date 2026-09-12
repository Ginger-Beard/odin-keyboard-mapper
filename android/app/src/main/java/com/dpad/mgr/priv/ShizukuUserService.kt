package com.dpad.mgr.priv

import android.content.Context
import android.os.Bundle
import android.util.Log
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.system.exitProcess

/**
 * Runs inside Shizuku's privileged process (uid 2000 shell, or root if Shizuku was
 * started with root). Plain ProcessBuilder here already runs privileged.
 */
class ShizukuUserService() : IUserService.Stub() {
    @Suppress("unused")
    constructor(context: Context) : this()

    @Volatile private var tailProc: Process? = null
    /** pid -> Process for children started via [spawn], so we can wait() for their exit code. */
    private val spawned = java.util.concurrent.ConcurrentHashMap<Int, Process>()
    /** pid -> exit code, filled in by the waiter thread once a spawned child has died. */
    private val spawnedExit = java.util.concurrent.ConcurrentHashMap<Int, Int>()

    init {
        // Safety net: a previous instance of this user service can die (Shizuku rebind,
        // binder death) without going through destroy()/stopTail(), orphaning its logcat
        // child. Sweep those up whenever a new instance of this process starts.
        runCatching {
            val p = ProcessBuilder("pkill", "-f", "logcat -b events").redirectErrorStream(true).start()
            p.waitFor(2, TimeUnit.SECONDS)
        }.onFailure { Log.w(TAG, "userservice: startup pkill failed: $it") }
    }

    override fun exec(argv: Array<String>): Bundle = execTimeout(argv, 20_000)

    override fun execTimeout(argv: Array<String>, timeoutMs: Int): Bundle {
        val b = Bundle()
        try {
            val p = ProcessBuilder(argv.toList()).redirectErrorStream(true).start()
            val out = p.inputStream.bufferedReader().readText()
            val done = p.waitFor(timeoutMs.toLong().coerceAtLeast(1L), TimeUnit.MILLISECONDS)
            if (!done) p.destroyForcibly()
            b.putInt("rc", if (done) p.exitValue() else 124)
            b.putString("out", out)
        } catch (e: Exception) {
            b.putInt("rc", 127)
            b.putString("out", "exec failed: $e")
        }
        return b
    }

    /**
     * Starts argv detached, keeping the [Process] handle (rather than the previous
     * `nohup ... & echo $!` shell trick) so a waiter thread can capture its real exit code
     * once it dies -- see [exitCode]. Stdio matches the old behaviour: input from /dev/null,
     * stdout+stderr merged and truncated to [LOG] (same as the prior `> LOG 2>&1`).
     */
    override fun spawn(argv: Array<String>, pidfile: String): Int = try {
        val pb = ProcessBuilder(argv.toList())
        pb.redirectErrorStream(true)
        pb.redirectOutput(File(LOG))
        pb.redirectInput(File("/dev/null"))
        val p: Process = pb.start()
        val pid = pidOf(p)
        if (pid <= 1) throw IllegalStateException("could not read pid of spawned process")
        spawned[pid] = p
        thread(name = "dpad-wait-$pid", isDaemon = true) {
            val rc = try { p.waitFor() } catch (e: Exception) { -1 }
            spawnedExit[pid] = rc
            spawned.remove(pid)
            Log.i(TAG, "userservice: pid=$pid exited rc=$rc")
        }
        pid
    } catch (e: Exception) {
        Log.w(TAG, "userservice: spawn failed: $e")
        -1
    }

    override fun kill(pid: Int) {
        if (pid > 1) exec(arrayOf("kill", "-TERM", pid.toString()))
    }

    override fun isAlive(pid: Int): Boolean {
        if (pid <= 1) return false
        return File("/proc/$pid").exists() || exec(arrayOf("kill", "-0", pid.toString())).getInt("rc") == 0
    }

    /** Exit code of a pid previously started via [spawn]; -1 while still running or unknown. */
    override fun exitCode(pid: Int): Int = spawnedExit[pid] ?: -1

    /**
     * The real OS pid of a just-started [Process]. `Process.pid()` (Java 9+) isn't used here: on
     * this toolchain it fails to resolve against the Android SDK stub jar at compile time, so we
     * fall back to the traditional reflection trick (a private `pid` int field on the JVM's
     * concrete Process implementation) that predates that API and still works on Android.
     */
    private fun pidOf(p: Process): Int = try {
        val f = p.javaClass.getDeclaredField("pid")
        f.isAccessible = true
        f.getInt(p)
    } catch (e: Exception) {
        Log.w(TAG, "userservice: could not read pid via reflection: $e")
        -1
    }

    override fun writeFile(path: String, content: String): Boolean = try {
        val f = File(path)
        f.writeText(content)
        f.setReadable(true, false)
        true
    } catch (e: Exception) {
        Log.w(TAG, "userservice: writeFile $path failed: $e"); false
    }

    override fun startTail(cb: ILineCallback) {
        stopTail()
        val p = ProcessBuilder(PrivShell.TAIL_ARGV).redirectErrorStream(true).start()
        tailProc = p
        thread(name = "dpad-tail", isDaemon = true) {
            try {
                BufferedReader(InputStreamReader(p.inputStream)).useLines { lines ->
                    for (l in lines) {
                        if (tailProc !== p) break
                        try { cb.onLine(l) } catch (_: Exception) { break }
                    }
                }
            } catch (_: Exception) {
            }
        }
    }

    override fun stopTail() {
        tailProc?.let { runCatching { it.destroy() } }
        tailProc = null
    }

    override fun destroy() {
        stopTail()
        exitProcess(0)
    }

    companion object {
        private const val TAG = "DpadMgr"
        const val LOG = "/data/local/tmp/dpadkeys.log"
    }
}
