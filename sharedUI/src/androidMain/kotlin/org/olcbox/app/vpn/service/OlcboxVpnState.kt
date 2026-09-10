package org.olcbox.app.vpn.service

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.olcbox.app.vpn.VpnStatus

object OlcboxVpnState {
    private val _logs = MutableStateFlow<List<String>>(emptyList())
    val logs = _logs.asStateFlow()

    private val _status = MutableStateFlow<VpnStatus>(VpnStatus.Disconnected)
    val status = _status.asStateFlow()

    private val _isConnected = MutableStateFlow(false)
    val isConnected = _isConnected.asStateFlow()

    fun setStatus(status: VpnStatus) {
        _status.value = status
        _isConnected.value = status is VpnStatus.Connected
        // Best-effort publish for UI process when service runs in :vpn.
        runCatching {
            val app = org.olcbox.app.data.mihomo.MihomoAndroidContext.app
            if (app != null) {
                VpnStatusBridge.publish(app, status)
            }
        }
    }

    fun addLog(msg: String) {
        Log.d(TAG, msg)
        _logs.update { (it + msg).takeLast(MAX_LOG_ENTRIES) }
        runCatching {
            val app = org.olcbox.app.data.mihomo.MihomoAndroidContext.app
            if (app != null) {
                VpnStatusBridge.publish(app, _status.value, msg)
            }
        }
    }

    private const val MAX_LOG_ENTRIES = 1_000
    private const val TAG = "OlcboxVpnService"
}
