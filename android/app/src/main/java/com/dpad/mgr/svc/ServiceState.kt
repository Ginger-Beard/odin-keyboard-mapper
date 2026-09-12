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
    val pad = MutableStateFlow(PadInfo(null, null, "not checked"))
    val foreground = MutableStateFlow<String?>(null)
    val binaryPath = MutableStateFlow<String?>(null)
    val message = MutableStateFlow<String?>(null)

    fun <T> StateFlow<T>.value() = this.value
}
