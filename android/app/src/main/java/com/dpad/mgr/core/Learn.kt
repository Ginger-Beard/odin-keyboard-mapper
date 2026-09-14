package com.dpad.mgr.core

import android.content.Context
import android.util.Log
import com.dpad.mgr.priv.PrivShell
import com.dpad.mgr.svc.ServiceState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Learn-by-press: runs `dpadkeys --learn` through the privileged shell (pad ungrabbed) and
 * returns the source name of the first control pressed.
 */
object Learn {
    private const val TAG = "DpadMgr"
    const val PIDFILE = "/data/local/tmp/dpadkeys.learn.pid"
    private const val TIMEOUT_MS = 15_000
    private val LEARNED = Regex("^learned (\\S+)$", RegexOption.MULTILINE)

    /**
     * Survives the dialog's composition scope. [requestCancel] must keep running after the
     * caller's coroutine is cancelled: the exec itself is a blocking binder call that ignores
     * cancellation until the daemon exits, so the kill has to come from a separate coroutine.
     */
    private val killScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    suspend fun runLearn(ctx: Context, onStatus: (String) -> Unit): Result<String> {
        val shell = ServiceState.priv.value.shell
            ?: return Result.failure(LearnError("No privileged shell (Shizuku/root not available)"))
        val bin = ServiceState.binaryPath.value
            ?: return Result.failure(LearnError("Daemon binary not ready — check the Status tab"))

        // `--learn` is a separate one-shot process and needs the serve daemon to be idle first.
        // beginLearn() writes an idle config + SIGUSR1 and waits for the status file to report it;
        // endLearn() restores whatever target is still intended (nothing, if the user moved on).
        val sup = ServiceState.supervisor
        if (sup == null) {
            if (daemonBusy()) return Result.failure(LearnError("Daemon is still running; stop it and try again"))
        } else {
            if (daemonBusy()) onStatus("Going idle…")
            if (!sup.beginLearn()) {
                sup.endLearn()
                return Result.failure(LearnError("Daemon did not go idle; stop it and try again"))
            }
            Log.i(TAG, "learn: daemon idle for learn")
        }
        try {
            return runLearnIdle(shell, bin, onStatus)
        } finally {
            sup?.endLearn()
        }
    }

    /** The actual one-shot `--learn` invocation; the serve daemon is already idle here. */
    private suspend fun runLearnIdle(shell: PrivShell, bin: String, onStatus: (String) -> Unit): Result<String> {

        /* drop a pidfile left behind by a previous, SIGKILLed learn so cancel can't kill a stranger */
        runCatching { shell.exec(listOf("rm", "-f", PIDFILE)) }

        onStatus("Waiting for a press…")
        val argv = listOf(bin, "--learn", "--learn-timeout-ms", TIMEOUT_MS.toString(), "--pidfile", PIDFILE)
        val r = try {
            withContext(Dispatchers.IO) { shell.execLong(argv, 20_000) }
        } catch (e: kotlinx.coroutines.CancellationException) {
            withContext(NonCancellable) { cancelLearn(shell) }
            throw e
        }
        val lastNonEmpty = r.out.lineSequence().map { it.trim() }.lastOrNull { it.isNotEmpty() } ?: ""
        Log.i(TAG, "learn: rc=${r.rc} out=${r.out.lineSequence().firstOrNull()?.take(120) ?: ""} last=${lastNonEmpty.take(120)}")
        val src = LEARNED.find(r.out)?.groupValues?.get(1)
        Log.i(TAG, "learn: parsed source=${src ?: "no learned line"}")
        if (src == null) return Result.failure(LearnError("learn failed (rc=${r.rc}): ${r.out.trim().lines().lastOrNull()?.take(160) ?: ""}"))
        return when {
            src == "NONE" -> Result.failure(LearnError("Nothing pressed within ${TIMEOUT_MS / 1000} s"))
            SourceNames.isValid(src) -> Result.success(src)
            else -> Result.failure(LearnError("Unrecognised control '$src'"))
        }
    }

    /**
     * Fire-and-forget kill of a running learn, for the dialog's Cancel button. Retries briefly
     * because the daemon only writes its pidfile once it has found the pad.
     */
    fun requestCancel() {
        val shell = ServiceState.priv.value.shell ?: return
        killScope.launch {
            val deadline = System.currentTimeMillis() + 3_000
            while (System.currentTimeMillis() < deadline) {
                if (cancelLearn(shell)) return@launch
                delay(200)
            }
            Log.i(TAG, "learn: cancel found no learn pid")
        }
    }

    private fun daemonBusy(): Boolean = when (ServiceState.daemon.value) {
        is DaemonState.Running, is DaemonState.Testing, is DaemonState.Starting, is DaemonState.Backoff -> true
        else -> false
    }

    /** Kills a learn process left running after the dialog was cancelled; true if one was killed. */
    suspend fun cancelLearn(shell: PrivShell): Boolean = withContext(Dispatchers.IO) {
        val pid = runCatching { shell.exec(listOf("cat", PIDFILE)).out.trim().toIntOrNull() }
            .getOrNull() ?: return@withContext false
        if (pid <= 1) return@withContext false
        Log.i(TAG, "learn: cancelled, killing pid=$pid")
        runCatching { shell.kill(pid) }
        runCatching { shell.exec(listOf("rm", "-f", PIDFILE)) }
        true
    }

    class LearnError(msg: String) : Exception(msg)
}
