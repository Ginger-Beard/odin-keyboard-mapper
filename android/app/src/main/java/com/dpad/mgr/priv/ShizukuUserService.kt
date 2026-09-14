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
import org.lsposed.hiddenapibypass.HiddenApiBypass

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

        killOtherHelperProcesses()
    }

    /**
     * Belt-and-suspenders for leaked helper processes: DpadService.onDestroy now unbinds with
     * remove=true on a clean stop (see PrivProbe.release/ShizukuShell.release), but a Shizuku
     * rebind, an app force-stop, or an older build's install can still leave a stale
     * `com.dpad.mgr:dpad` process (this service's own Shizuku process name, set via
     * UserServiceArgs.processNameSuffix("dpad")) running under uid shell. Every time a NEW
     * instance of this process starts, kill every OTHER process with that exact cmdline --
     * never this one, compared by pid against Process.myPid() so a fresh instance never kills
     * itself mid-startup.
     */
    private fun killOtherHelperProcesses() {
        val myPid = android.os.Process.myPid()
        var killed = 0
        runCatching {
            File("/proc").listFiles { f -> f.isDirectory && f.name.toIntOrNull() != null }?.forEach { procDir ->
                val pid = procDir.name.toIntOrNull() ?: return@forEach
                if (pid == myPid) return@forEach
                val cmdline = runCatching {
                    File(procDir, "cmdline").readBytes().toString(Charsets.UTF_8).trimEnd('\u0000')
                }.getOrNull() ?: return@forEach
                if (cmdline == HELPER_PROCESS_NAME) {
                    runCatching {
                        ProcessBuilder("kill", "-TERM", pid.toString()).redirectErrorStream(true).start().waitFor(2, TimeUnit.SECONDS)
                    }.onFailure { Log.w(TAG, "userservice: kill of leaked pid=$pid failed: $it") }
                    killed++
                }
            }
        }.onFailure { Log.w(TAG, "userservice: leaked-helper sweep failed: $it") }
        if (killed > 0) Log.i(TAG, "userservice: killed $killed leaked '$HELPER_PROCESS_NAME' process(es)")
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

    @Volatile private var hiddenApiExempted = false

    private fun ensureHiddenApiExemption() {
        if (hiddenApiExempted) return
        hiddenApiExempted = true
        try {
            HiddenApiBypass.addHiddenApiExemptions("")
        } catch (e: Exception) {
            Log.w(TAG, "userservice: HiddenApiBypass exemption failed: $e")
        }
    }

    // Cached reflection handles for setPointerIconType, resolved once and reused on every call
    // thereafter (this is polled every 700ms by the Supervisor while a wheel profile's daemon is
    // running, so re-resolving via Class.forName/getMethod on every tick would be wasteful).
    @Volatile private var pointerImInstance: Any? = null
    @Volatile private var pointerImMethod: java.lang.reflect.Method? = null
    @Volatile private var pointerIimInstance: Any? = null
    @Volatile private var pointerIimMethod: java.lang.reflect.Method? = null
    @Volatile private var pointerLoggedSuccess = false

    /**
     * Hides (type=0, PointerIcon.TYPE_NULL) or restores (type=1000, PointerIcon.TYPE_ARROW) the
     * mouse pointer sprite, via InputManager.getInstance().setPointerIconType(type) (hidden API),
     * falling back to IInputManager via ServiceManager if InputManager.getInstance() is absent.
     * The resolved Method/instance are cached after the first successful call; only the first
     * success and any failure are logged, not every tick.
     */
    override fun setPointerIconType(type: Int): Boolean {
        ensureHiddenApiExemption()

        pointerImMethod?.let { m ->
            try {
                m.invoke(pointerImInstance, type)
                if (!pointerLoggedSuccess) {
                    pointerLoggedSuccess = true
                    Log.i(TAG, "userservice: setPointerIconType via InputManager ok (cached)")
                }
                return true
            } catch (e: Exception) {
                Log.w(TAG, "userservice: cached InputManager.setPointerIconType($type) failed: $e")
                pointerImInstance = null
                pointerImMethod = null
            }
        }
        pointerIimMethod?.let { m ->
            try {
                m.invoke(pointerIimInstance, type)
                if (!pointerLoggedSuccess) {
                    pointerLoggedSuccess = true
                    Log.i(TAG, "userservice: setPointerIconType via IInputManager ok (cached)")
                }
                return true
            } catch (e: Exception) {
                Log.w(TAG, "userservice: cached IInputManager.setPointerIconType($type) failed: $e")
                pointerIimInstance = null
                pointerIimMethod = null
            }
        }

        try {
            val imClass = Class.forName("android.hardware.input.InputManager")
            val im = imClass.getMethod("getInstance").invoke(null)
            if (im != null) {
                val m = imClass.getMethod("setPointerIconType", Int::class.javaPrimitiveType)
                m.invoke(im, type)
                pointerImInstance = im
                pointerImMethod = m
                if (!pointerLoggedSuccess) {
                    pointerLoggedSuccess = true
                    Log.i(TAG, "userservice: setPointerIconType($type) via InputManager ok")
                }
                return true
            }
            throw IllegalStateException("InputManager.getInstance() returned null")
        } catch (e: Exception) {
            Log.w(TAG, "userservice: InputManager.setPointerIconType($type) failed: $e; trying IInputManager")
        }
        return try {
            val smClass = Class.forName("android.os.ServiceManager")
            val binder = smClass.getMethod("getService", String::class.java).invoke(null, "input") as? android.os.IBinder
                ?: throw IllegalStateException("ServiceManager.getService(\"input\") returned null")
            val stubClass = Class.forName("android.hardware.input.IInputManager\$Stub")
            val iim = stubClass.getMethod("asInterface", android.os.IBinder::class.java).invoke(null, binder)
            val iimClass = Class.forName("android.hardware.input.IInputManager")
            val m = iimClass.getMethod("setPointerIconType", Int::class.javaPrimitiveType)
            m.invoke(iim, type)
            pointerIimInstance = iim
            pointerIimMethod = m
            if (!pointerLoggedSuccess) {
                pointerLoggedSuccess = true
                Log.i(TAG, "userservice: setPointerIconType($type) via IInputManager ok")
            }
            true
        } catch (e2: Exception) {
            Log.w(TAG, "userservice: IInputManager.setPointerIconType($type) failed: $e2")
            false
        }
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
        /** This service's Shizuku process name (package + UserServiceArgs.processNameSuffix("dpad")
         *  in ShizukuShell), as it appears verbatim in each pid's /proc cmdline file. */
        private const val HELPER_PROCESS_NAME = "com.dpad.mgr:dpad"
    }
}
