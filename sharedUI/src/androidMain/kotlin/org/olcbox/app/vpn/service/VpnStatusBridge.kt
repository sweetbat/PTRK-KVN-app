package org.olcbox.app.vpn.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.olcbox.app.vpn.VpnStatus

/**
 * Cross-process VPN status bridge.
 * The VpnService may run in `:vpn` so in-memory [OlcboxVpnState] is not shared with the UI process.
 */
object VpnStatusBridge {
    const val ACTION = "ptrkkvn.app.action.VPN_STATE"
    private const val EXTRA_KIND = "kind"
    private const val EXTRA_MESSAGE = "message"
    private const val EXTRA_LOG = "log"

    private const val KIND_DISCONNECTED = "disconnected"
    private const val KIND_CONNECTING = "connecting"
    private const val KIND_CONNECTED = "connected"
    private const val KIND_RECONNECTING = "reconnecting"
    private const val KIND_STOPPING = "stopping"
    private const val KIND_ERROR = "error"

    private val _logs = MutableStateFlow<List<String>>(emptyList())
    val logs: StateFlow<List<String>> = _logs.asStateFlow()

    private val _status = MutableStateFlow<VpnStatus>(VpnStatus.Disconnected)
    val status: StateFlow<VpnStatus> = _status.asStateFlow()

    private val _isConnected = MutableStateFlow(false)
    val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

    /** True after the `:vpn` process published at least one status/log for the current start. */
    @Volatile
    var serviceAcked: Boolean = false
        private set

    @Volatile private var registered = false

    fun markConnecting() {
        serviceAcked = false
        applyLocal(VpnStatus.Connecting, null, fromService = false)
    }

    fun markStopping() {
        applyLocal(VpnStatus.Stopping, null, fromService = false)
    }

    fun markDisconnected() {
        applyLocal(VpnStatus.Disconnected, null, fromService = false)
    }

    fun publish(context: Context, status: VpnStatus, log: String? = null) {
        val kind = when (status) {
            VpnStatus.Disconnected -> KIND_DISCONNECTED
            VpnStatus.Connecting -> KIND_CONNECTING
            VpnStatus.Connected -> KIND_CONNECTED
            VpnStatus.Reconnecting -> KIND_RECONNECTING
            VpnStatus.Stopping -> KIND_STOPPING
            is VpnStatus.Error -> KIND_ERROR
        }
        val intent = Intent(ACTION).setPackage(context.packageName)
            .putExtra(EXTRA_KIND, kind)
            .putExtra(EXTRA_MESSAGE, (status as? VpnStatus.Error)?.message)
        if (!log.isNullOrBlank()) intent.putExtra(EXTRA_LOG, log)
        context.sendBroadcast(intent)

        // Also update local process mirrors (same-process case).
        applyLocal(status, log, fromService = true)
    }

    fun ensureRegistered(context: Context) {
        if (registered) return
        synchronized(this) {
            if (registered) return
            val app = context.applicationContext
            val filter = IntentFilter(ACTION)
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(ctx: Context?, intent: Intent?) {
                    if (intent?.action != ACTION) return
                    val kind = intent.getStringExtra(EXTRA_KIND) ?: return
                    val status = when (kind) {
                        KIND_CONNECTING -> VpnStatus.Connecting
                        KIND_CONNECTED -> VpnStatus.Connected
                        KIND_RECONNECTING -> VpnStatus.Reconnecting
                        KIND_STOPPING -> VpnStatus.Stopping
                        KIND_ERROR -> VpnStatus.Error(
                            intent.getStringExtra(EXTRA_MESSAGE) ?: "Error"
                        )
                        else -> VpnStatus.Disconnected
                    }
                    applyLocal(status, intent.getStringExtra(EXTRA_LOG), fromService = true)
                }
            }
            if (Build.VERSION.SDK_INT >= 33) {
                app.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag")
                app.registerReceiver(receiver, filter)
            }
            registered = true
        }
    }

    private fun applyLocal(status: VpnStatus, log: String?, fromService: Boolean) {
        if (fromService) serviceAcked = true
        _status.value = status
        _isConnected.value = status is VpnStatus.Connected || status is VpnStatus.Reconnecting
        if (!log.isNullOrBlank()) {
            _logs.update { (it + log).takeLast(1_000) }
        }
    }
}
