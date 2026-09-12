package com.dpad.mgr.priv

data class ExecResult(val rc: Int, val out: String) {
    val ok: Boolean get() = rc == 0
}

enum class PrivSource(val label: String) {
    ROOT_PSERVER("Root via PServerBinder"),
    SHIZUKU("Shizuku"),
    NONE("None"),
}

/**
 * A privileged command channel (root via AYN's PServerBinder, or Shizuku).
 * Implementations must be safe to call from any coroutine; they serialize internally.
 */
interface PrivShell {
    val source: PrivSource

    /** Run argv to completion; returns exit code and combined stdout+stderr. */
    suspend fun exec(argv: List<String>): ExecResult

    /** Like [exec] but with an explicit timeout (for long-running commands such as `--learn`). */
    suspend fun execLong(argv: List<String>, timeoutMs: Long): ExecResult = exec(argv)

    /** Start argv detached (nohup, stdio to /data/local/tmp/dpadkeys.log); returns pid or -1. */
    suspend fun spawn(argv: List<String>, pidfile: String): Int

    suspend fun kill(pid: Int)

    suspend fun isAlive(pid: Int): Boolean

    /** Write content to path (created 0644). */
    suspend fun writeFile(path: String, content: String): Boolean

    /** Tail `logcat -b events` for activity-resume lines; cb is invoked per line on an arbitrary thread. */
    suspend fun startTail(cb: (String) -> Unit)

    suspend fun stopTail()

    companion object {
        val SAFE_TOKEN = Regex("^[A-Za-z0-9._/~=+,:@-]+$")
        const val TAIL_CMD = "logcat -b events -v raw -T 1 -s am_resume_activity:I wm_on_resume_called:I"
        val TAIL_ARGV: List<String> = TAIL_CMD.split(' ')

        fun isSafeToken(s: String): Boolean = s.isNotEmpty() && SAFE_TOKEN.matches(s)
        fun requireSafe(argv: List<String>): List<String> {
            for (a in argv) require(isSafeToken(a)) { "unsafe shell token: '$a'" }
            return argv
        }
    }
}
