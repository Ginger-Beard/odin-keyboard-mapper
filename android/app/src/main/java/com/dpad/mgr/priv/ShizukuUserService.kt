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

    override fun spawn(argv: Array<String>, pidfile: String): Int {
        val cmd = "nohup ${argv.joinToString(" ")} < /dev/null > $LOG 2>&1 & echo \$!"
        val r = exec(arrayOf("sh", "-c", cmd))
        return r.getString("out")?.trim()?.lines()?.lastOrNull()?.trim()?.toIntOrNull() ?: -1
    }

    override fun kill(pid: Int) {
        if (pid > 1) exec(arrayOf("kill", "-TERM", pid.toString()))
    }

    override fun isAlive(pid: Int): Boolean {
        if (pid <= 1) return false
        return File("/proc/$pid").exists() || exec(arrayOf("kill", "-0", pid.toString())).getInt("rc") == 0
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
