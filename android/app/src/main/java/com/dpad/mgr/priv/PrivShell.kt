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

    /** Exit code of a pid started via [spawn], once it has died; -1 while running or unknown.
     *  Only meaningful where the channel actually tracks the child (see [ShizukuShell]); other
     *  implementations keep the default (always -1, i.e. "unknown"). */
    suspend fun exitCode(pid: Int): Int = -1

    /** Write content to path (created 0644). */
    suspend fun writeFile(path: String, content: String): Boolean

    /** Hide (true) or restore (false, TYPE_ARROW) the mouse pointer sprite. Only meaningful over
     *  Shizuku (see [ShizukuShell]); other channels keep the default (unsupported, returns false). */
    suspend fun setPointerHidden(hidden: Boolean): Boolean = false

    /** Tail `logcat -b events` for activity-resume lines; cb is invoked per line on an arbitrary thread. */
    suspend fun startTail(cb: (String) -> Unit)

    suspend fun stopTail()

    companion object {
        val SAFE_TOKEN = Regex("^[A-Za-z0-9._/~=+,:@-]+$")
        // wm_set_resumed_activity carries "pkg/cls" in its raw text on Android 13; am_resume_activity
        // is kept for older Android where wm_set_resumed_activity doesn't exist. wm_on_resume_called
        // only carries the class name (no package), so it can't be parsed and is intentionally excluded.
        const val TAIL_CMD = "logcat -b events -v raw -T 1 -s wm_set_resumed_activity:I am_resume_activity:I"
        val TAIL_ARGV: List<String> = TAIL_CMD.split(' ')

        fun isSafeToken(s: String): Boolean = s.isNotEmpty() && SAFE_TOKEN.matches(s)
        fun requireSafe(argv: List<String>): List<String> {
            for (a in argv) require(isSafeToken(a)) { "unsafe shell token: '$a'" }
            return argv
        }
    }
}
