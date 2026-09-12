package com.dpad.mgr.core

import android.content.Context
import android.util.Log
import com.dpad.mgr.BuildConfig
import com.dpad.mgr.priv.PServerShell
import com.dpad.mgr.priv.PrivShell
import java.io.File

/**
 * Locates a runnable copy of the daemon. It ships as libdpadkeys.so in the APK (extracted
 * to nativeLibraryDir). If the privileged shell cannot exec it from there, it is copied
 * to /data/local/tmp/dpadkeys. The working path is cached per versionCode.
 */
class BinaryInstaller(ctx: Context) {
    private val appCtx = ctx.applicationContext
    private val prefs = appCtx.getSharedPreferences("bin", Context.MODE_PRIVATE)
    private val libPath: String = File(appCtx.applicationInfo.nativeLibraryDir, "libdpadkeys.so").path

    @Volatile var lastDetail: String = ""
        private set

    fun cachedPath(sourceName: String): String? =
        if (prefs.getInt("ver", -1) == BuildConfig.VERSION_CODE && prefs.getString("src", "") == sourceName)
            prefs.getString("path", null) else null

    fun invalidate() = prefs.edit().clear().apply()

    /** Returns a path the shell can run, or null. Logs `binary:` lines. */
    suspend fun resolve(shell: PrivShell, force: Boolean = false): String? {
        val srcName = shell.source.name
        if (!force) cachedPath(srcName)?.let { p ->
            val r = probeRun(shell, p)
            if (r.rc == 0) { Log.i(TAG, "binary: path=$p rc=0 (cached)"); lastDetail = p; return p }
            Log.i(TAG, "binary: cached path=$p rc=${r.rc}; re-resolving")
        }
        val libFile = File(libPath)
        Log.i(TAG, "binary: nativeLib=$libPath exists=${libFile.exists()} size=${libFile.length()}")
        val r1 = probeRun(shell, libPath)
        val head1 = r1.out.lineSequence().firstOrNull()?.take(120) ?: ""
        Log.i(TAG, "binary: path=$libPath rc=${r1.rc} out=$head1")
        if (r1.rc == 0) return remember(srcName, libPath)

        val denied = r1.rc == 126 || r1.rc == 127 || r1.out.contains("Permission denied", true) ||
            r1.out.contains("not found", true) || r1.out.contains("no result", true) || r1.rc == 255
        if (!denied) Log.i(TAG, "binary: unexpected rc=${r1.rc}; trying /data/local/tmp anyway")

        val cp = if (shell is PServerShell)
            shell.execSlow(listOf("cp", "-f", libPath, TMP_BIN), 10_000)
        else shell.exec(listOf("cp", "-f", libPath, TMP_BIN))
        val ch = shell.exec(listOf("chmod", "755", TMP_BIN))
        Log.i(TAG, "binary: cp rc=${cp.rc} ${cp.out.trim().take(100)} chmod rc=${ch.rc}")
        if (cp.rc != 0) {
            // Fallback: stream bytes through the shell's writeFile is not binary-safe; try cat via sh.
            val alt = shell.exec(listOf("sh", "-c", "cat $libPath > $TMP_BIN".let { it }))
            Log.i(TAG, "binary: cat-fallback rc=${alt.rc}")
        }
        val r2 = probeRun(shell, TMP_BIN)
        val head2 = r2.out.lineSequence().firstOrNull()?.take(120) ?: ""
        Log.i(TAG, "binary: path=$TMP_BIN rc=${r2.rc} out=$head2")
        if (r2.rc == 0) return remember(srcName, TMP_BIN)
        lastDetail = "daemon binary not runnable (lib rc=${r1.rc}, tmp rc=${r2.rc})"
        Log.w(TAG, "binary: no runnable path")
        return null
    }

    private fun remember(src: String, path: String): String {
        prefs.edit().putInt("ver", BuildConfig.VERSION_CODE).putString("src", src).putString("path", path).apply()
        lastDetail = path
        return path
    }

    /** `bin --config <probe conf> --print-config`; falls back to --profile if writeFile failed. */
    private suspend fun probeRun(shell: PrivShell, bin: String): com.dpad.mgr.priv.ExecResult {
        val wrote = shell.writeFile(PROBE_CONF, Profile.OSRS.toConfigText())
        val argv = if (wrote) listOf(bin, "--config", PROBE_CONF, "--print-config")
        else listOf(bin, "--profile", "fkeys", "--print-config")
        return shell.exec(argv)
    }

    companion object {
        private const val TAG = "DpadMgr"
        const val TMP_BIN = "/data/local/tmp/dpadkeys"
        const val PROBE_CONF = "/data/local/tmp/dpadkeys.probe.conf"
    }
}
