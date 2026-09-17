package org.olcbox.app.vpn

import android.app.ActivityManager
import android.content.Context
import java.io.File

/**
 * Sticky VPN "still up" marker written by `:vpn` so the UI process can restore
 * Connected after the app is swiped away.
 */
object VpnServiceStatusStore {
    private const val FILE = "vpn_service_status.txt"

    fun markConnected(context: Context) {
        runCatching { File(context.filesDir, FILE).writeText("connected") }
    }

    fun clear(context: Context) {
        runCatching { File(context.filesDir, FILE).delete() }
    }

    fun isMarkedConnected(context: Context): Boolean {
        return runCatching {
            File(context.filesDir, FILE).takeIf { it.isFile }?.readText()?.trim() == "connected"
        }.getOrDefault(false)
    }

    fun isVpnProcessAlive(context: Context): Boolean {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return false
        val vpnName = "${context.packageName}:vpn"
        return am.runningAppProcesses.orEmpty().any { it.processName == vpnName }
    }

    /**
     * True only when the sticky marker is set AND `:vpn` is actually alive.
     * Do not treat [VpnConnectedSinceStore] alone as connected — that resurrected
     * stale uptime timers when the tunnel was already down.
     */
    fun isLikelyConnected(context: Context): Boolean {
        return isMarkedConnected(context) && isVpnProcessAlive(context)
    }
}
