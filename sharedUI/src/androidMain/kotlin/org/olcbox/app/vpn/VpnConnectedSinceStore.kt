package org.olcbox.app.vpn

import android.content.Context
import java.io.File

/**
 * Persists the wall-clock moment the VPN first reached Connected so the UI
 * uptime counter survives process death / app swipe-away while the tunnel stays up.
 */
object VpnConnectedSinceStore {
    private const val FILE = "vpn_connected_since_ms.txt"

    fun read(context: Context): Long? {
        return runCatching {
            val text = File(context.filesDir, FILE).takeIf { it.isFile }?.readText()?.trim().orEmpty()
            text.toLongOrNull()?.takeIf { it > 0L }
        }.getOrNull()
    }

    /**
     * @param forceNew when true, always overwrite (server switch / restart).
     * Default keeps the original timestamp across transport reconnects.
     */
    fun markConnected(context: Context, forceNew: Boolean = false) {
        val file = File(context.filesDir, FILE)
        if (!forceNew) {
            val existing = read(context)
            if (existing != null && existing > 0L) return
        }
        runCatching { file.writeText(System.currentTimeMillis().toString()) }
    }

    fun clear(context: Context) {
        runCatching { File(context.filesDir, FILE).delete() }
    }
}
