package com.dpad.mgr.svc

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * adb hooks:
 *   am broadcast -a com.dpad.mgr.DEBUG_TEST --es profile OSRS [--ei seconds 30] -p com.dpad.mgr
 *   am broadcast -a com.dpad.mgr.DEBUG_STOP -p com.dpad.mgr
 *   am broadcast -a com.dpad.mgr.DEBUG_RECHECK -p com.dpad.mgr
 */
class DebugReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Log.i("DpadMgr", "debug: ${intent.action} ${intent.extras?.keySet()?.joinToString { "$it=${intent.extras?.get(it)}" }}")
        when (intent.action) {
            "com.dpad.mgr.DEBUG_TEST" -> DpadService.send(
                context, DpadService.ACTION_TEST,
                profile = intent.getStringExtra("profile") ?: "OSRS",
                seconds = intent.getIntExtra("seconds", 30),
            )
            "com.dpad.mgr.DEBUG_STOP" -> DpadService.send(context, DpadService.ACTION_STOP_DAEMON)
            "com.dpad.mgr.DEBUG_RECHECK" -> DpadService.send(context, DpadService.ACTION_RECHECK)
        }
    }
}
