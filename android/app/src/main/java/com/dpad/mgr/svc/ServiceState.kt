package com.dpad.mgr.svc

import com.dpad.mgr.core.DaemonState
import com.dpad.mgr.core.PadInfo
import com.dpad.mgr.priv.PrivState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Process-wide snapshot of the service for the UI (the service lives in the same process). */
object ServiceState {
    val serviceRunning = MutableStateFlow(false)
    val priv = MutableStateFlow(PrivState())
    val daemon = MutableStateFlow<DaemonState>(DaemonState.Idle)
    val pad = MutableStateFlow(PadInfo(null, null, null, "not checked"))
    val foreground = MutableStateFlow<String?>(null)
    val binaryPath = MutableStateFlow<String?>(null)
    /** Short feedback for the last button press ("Stopping…", "Nothing is running", …), set by
     *  DpadService on every action so the UI can show it immediately regardless of whether the
     *  action changed any other state. */
    val lastAction = MutableStateFlow<String?>(null)
    /** Wall-clock deadline (ms) of an in-progress manual test, or null when no test is running.
     *  Set on ACTION_TEST and cleared once the daemon state shows the test ended (naturally or
     *  via ACTION_STOP_TEST/STOP_DAEMON). Lets the UI render a live "N s left" countdown without
     *  the Supervisor needing to expose a ticking timer itself. */
    val testEndsAtMs = MutableStateFlow<Long?>(null)

    fun <T> StateFlow<T>.value() = this.value
}
