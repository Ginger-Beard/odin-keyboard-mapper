package com.dpad.mgr.svc

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Restarts [DpadService] on the system events that can leave it stopped without user action:
 * a normal boot, a locked (pre-unlock) boot, and this app being updated (which kills the
 * process and clears any stickiness). Not direct-boot-aware: [Store] reads from
 * credential-encrypted storage, so ACTION_LOCKED_BOOT_COMPLETED is listed for completeness but
 * won't actually be delivered pre-unlock without that (non-trivial) migration; skipped for now.
 */
class SystemEventsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_LOCKED_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED -> {
                Log.i("DpadMgr", "${intent.action}: starting service")
                runCatching { DpadService.ensureStarted(context) }
                    .onFailure { Log.w("DpadMgr", "${intent.action}: start failed $it") }
            }
        }
    }
}
