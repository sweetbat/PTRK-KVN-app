package org.olcbox.app.vpn

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.olcbox.app.data.model.LocationConfig

internal object OlcRtcConnectionChecker {
    suspend fun check(locationConfig: LocationConfig, deviceId: String): Long? {
        return withContext(Dispatchers.IO) {
            probe(locationConfig, deviceId, OlcRtcProbeService.ACTION_CHECK)
        }
    }

    suspend fun ping(locationConfig: LocationConfig, deviceId: String): Long? {
        return withContext(Dispatchers.IO) {
            probe(locationConfig, deviceId, OlcRtcProbeService.ACTION_PING)
        }
    }

    private fun probe(locationConfig: LocationConfig, deviceId: String, action: String): Long? {
        val app = org.olcbox.app.data.mihomo.MihomoAndroidContext.app
            ?: return null
        return runCatching {
            OlcRtcProbeService.probe(
                context = app,
                locationConfig = locationConfig,
                deviceId = deviceId,
                action = action,
            )
        }.onFailure {
            Log.e("OlcRtcConnectionChecker", "probe failed", it)
        }.getOrNull()
    }
}
