package org.olcbox.app

import android.app.PendingIntent
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.olcbox.app.vpn.AndroidConnectionMode
import org.olcbox.app.vpn.AndroidSocksProxySettings
import org.olcbox.app.vpn.AndroidSplitTunnelMode
import org.olcbox.app.vpn.VpnStatus
import org.olcbox.app.vpn.data.KEY_ANDROID_CONNECTION_MODE
import org.olcbox.app.vpn.data.KEY_ANDROID_SPLIT_TUNNEL_BYPASS_APPS
import org.olcbox.app.vpn.data.KEY_ANDROID_SPLIT_TUNNEL_MODE
import org.olcbox.app.vpn.data.KEY_ANDROID_SPLIT_TUNNEL_PROXY_APPS
import org.olcbox.app.vpn.data.KEY_ANDROID_SOCKS_HOST
import org.olcbox.app.vpn.data.KEY_ANDROID_SOCKS_PASSWORD
import org.olcbox.app.vpn.data.KEY_ANDROID_SOCKS_PORT
import org.olcbox.app.vpn.data.KEY_ANDROID_SOCKS_USERNAME
import org.olcbox.app.vpn.data.KEY_MIHOMO_MODE
import org.olcbox.app.vpn.data.MihomoModeStore
import org.olcbox.app.vpn.data.vpnPrefDataStore
import org.olcbox.app.vpn.service.OlcboxVpnActions
import org.olcbox.app.vpn.service.OlcboxVpnState
import org.olcbox.app.vpn.service.VpnStatusBridge

/**
 * Quick Settings tile — toggles VPN to the last selected (active) server.
 */
@RequiresApi(Build.VERSION_CODES.N)
class QuickSettingsTileService : TileService() {

    private var scope: CoroutineScope? = null

    override fun onStartListening() {
        super.onStartListening()
        VpnStatusBridge.ensureRegistered(applicationContext)
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        OlcboxVpnState.status
            .onEach { status -> updateTile(status) }
            .launchIn(scope!!)
        updateTile(OlcboxVpnState.status.value)
    }

    override fun onStopListening() {
        super.onStopListening()
        scope?.cancel()
        scope = null
    }

    override fun onClick() {
        super.onClick()
        val status = OlcboxVpnState.status.value
        val isActive = status is VpnStatus.Connected ||
            status is VpnStatus.Connecting ||
            status is VpnStatus.Reconnecting

        if (isActive) {
            stopVpn()
        } else {
            val prepIntent = VpnService.prepare(applicationContext)
            if (prepIntent == null) {
                startVpn()
            } else {
                openMainApp()
            }
        }
    }

    private fun startVpn() {
        val intent = buildStartIntent()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ContextCompat.startForegroundService(applicationContext, intent)
        } else {
            startService(intent)
        }
        updateTile(VpnStatus.Connecting)
    }

    private fun buildStartIntent(): Intent {
        val preferences = runCatching {
            runBlocking { applicationContext.vpnPrefDataStore.data.first() }
        }.getOrNull()

        return Intent().apply {
            setClassName(packageName, OlcboxVpnActions.SERVICE_CLASS_NAME)
            action = OlcboxVpnActions.ACTION_START_VPN
            putExtra(
                OlcboxVpnActions.EXTRA_CONNECTION_MODE,
                AndroidConnectionMode.fromValue(
                    preferences?.get(KEY_ANDROID_CONNECTION_MODE)
                ).value,
            )
            putExtra(
                OlcboxVpnActions.EXTRA_SOCKS_HOST,
                AndroidSocksProxySettings.sanitizeHost(preferences?.get(KEY_ANDROID_SOCKS_HOST)),
            )
            putExtra(
                OlcboxVpnActions.EXTRA_SOCKS_PORT,
                AndroidSocksProxySettings.sanitizePort(preferences?.get(KEY_ANDROID_SOCKS_PORT)),
            )
            putExtra(
                OlcboxVpnActions.EXTRA_SOCKS_USERNAME,
                preferences?.get(KEY_ANDROID_SOCKS_USERNAME).orEmpty(),
            )
            putExtra(
                OlcboxVpnActions.EXTRA_SOCKS_PASSWORD,
                preferences?.get(KEY_ANDROID_SOCKS_PASSWORD).orEmpty(),
            )
            putExtra(
                OlcboxVpnActions.EXTRA_SPLIT_TUNNEL_MODE,
                AndroidSplitTunnelMode.fromValue(
                    preferences?.get(KEY_ANDROID_SPLIT_TUNNEL_MODE)
                ).value,
            )
            putStringArrayListExtra(
                OlcboxVpnActions.EXTRA_SPLIT_TUNNEL_PROXY_APPS,
                ArrayList(preferences?.get(KEY_ANDROID_SPLIT_TUNNEL_PROXY_APPS).orEmpty()),
            )
            putStringArrayListExtra(
                OlcboxVpnActions.EXTRA_SPLIT_TUNNEL_BYPASS_APPS,
                ArrayList(preferences?.get(KEY_ANDROID_SPLIT_TUNNEL_BYPASS_APPS).orEmpty()),
            )
            putExtra(
                OlcboxVpnActions.EXTRA_MIHOMO_MODE,
                MihomoModeStore.resolve(
                    context = applicationContext,
                    dataStoreValue = preferences?.get(KEY_MIHOMO_MODE),
                ),
            )
        }
    }

    private fun stopVpn() {
        val intent = Intent().apply {
            setClassName(packageName, OlcboxVpnActions.SERVICE_CLASS_NAME)
            action = OlcboxVpnActions.ACTION_STOP_VPN
        }
        startService(intent)
    }

    private fun openMainApp() {
        val intent = Intent(applicationContext, AppActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val pending = PendingIntent.getActivity(
                applicationContext,
                0,
                intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            startActivityAndCollapse(pending)
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }

    private fun updateTile(status: VpnStatus) {
        val tile = qsTile ?: return
        when (status) {
            is VpnStatus.Connected -> {
                tile.state = Tile.STATE_ACTIVE
                tile.label = getString(R.string.qs_tile_label)
                tile.contentDescription = getString(R.string.qs_tile_connected)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    tile.subtitle = getString(R.string.qs_tile_connected)
                }
            }
            is VpnStatus.Connecting, is VpnStatus.Reconnecting -> {
                tile.state = Tile.STATE_ACTIVE
                tile.label = getString(R.string.qs_tile_label)
                tile.contentDescription = getString(R.string.qs_tile_connecting)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    tile.subtitle = getString(R.string.qs_tile_connecting)
                }
            }
            else -> {
                tile.state = Tile.STATE_INACTIVE
                tile.label = getString(R.string.qs_tile_label)
                tile.contentDescription = getString(R.string.qs_tile_disconnected)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    tile.subtitle = getString(R.string.qs_tile_disconnected)
                }
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            runCatching {
                tile.setIcon(
                    android.graphics.drawable.Icon.createWithResource(
                        this,
                        R.drawable.ic_stat_ptrk,
                    )
                )
            }
        }
        tile.updateTile()
    }
}
