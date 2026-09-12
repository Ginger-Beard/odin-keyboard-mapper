package com.dpad.mgr.svc

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            Log.i("DpadMgr", "boot: starting service")
            runCatching { DpadService.ensureStarted(context) }
                .onFailure { Log.w("DpadMgr", "boot: start failed $it") }
        }
    }
}
