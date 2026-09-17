package org.olcbox.app.vpn

import kotlinx.coroutines.flow.StateFlow
import org.olcbox.app.data.model.LocationConfig
import org.olcbox.app.data.repository.SubscriptionFetchProxy

sealed class VpnStatus {
    object Disconnected : VpnStatus()
    object Connecting : VpnStatus()
    object Connected : VpnStatus()
    object Reconnecting : VpnStatus()
    object Stopping : VpnStatus()
    data class Error(val message: String) : VpnStatus()
}

interface VpnManager {
    val logs: StateFlow<List<String>>
    val status: StateFlow<VpnStatus>
    val isConnected: StateFlow<Boolean>
    fun needsPermission(): Boolean
    fun startVpn()
    fun stopVpn()
    /** Kill the inactive engine process / recycle :vpn when location engine changes. */
    fun prepareActiveEngine() = Unit
    suspend fun ping(locationConfig: LocationConfig): Long?
    suspend fun checkConnection(locationConfig: LocationConfig): Long?
    fun subscriptionFetchProxy(): SubscriptionFetchProxy? = null
    fun mihomoMode(): String = "rule"
    suspend fun setMihomoMode(mode: String) = Unit
    fun mihomoModeFlow(): kotlinx.coroutines.flow.Flow<String> =
        kotlinx.coroutines.flow.flowOf("rule")
    /** Wall-clock ms when the current VPN session connected; survives UI process death. */
    fun connectedSinceEpochMs(): Long? = null
    /** Clear persisted uptime (call on server switch before reconnect). */
    fun resetConnectedSince() = Unit
    /**
     * Soft-heal tunnel after subscription/APK traffic.
     * @param restartTransport if true on olcRTC, soft-restarts Mobile once (restores
     *   internet after fetch freeze). Never pass true from app-open auto-refresh.
     */
    fun healTransportAfterFetch(restartTransport: Boolean = false) = Unit

    /** True when connected olcRTC session (fetch shares Mobile SOCKS with Telegram). */
    fun isOlcrtcFetchSession(): Boolean = false
}
