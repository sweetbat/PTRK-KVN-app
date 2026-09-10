package org.olcbox.app.vpn

import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.ResultReceiver
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.olcbox.app.data.mihomo.MihomoAndroidContext
import org.olcbox.app.mihomo.MihomoEngine
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Clash rule engine for olcRTC in `:route` (no libgojni).
 *
 * Must [ConnectivityManager.bindProcessToNetwork] to the real upstream so DIRECT
 * (Минцифры) does not re-enter the VPN tun → hev → Clash loop.
 */
class OlcRtcRoutingService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                scope.launch {
                    runCatching { MihomoEngine.stopListener() }
                    runCatching {
                        val cm = getSystemService(ConnectivityManager::class.java)
                        cm?.bindProcessToNetwork(null)
                    }
                    stopSelf()
                }
                return START_NOT_STICKY
            }
            ACTION_START, null -> Unit
            else -> {
                stopSelf(startId)
                return START_NOT_STICKY
            }
        }

        val receiver = if (Build.VERSION.SDK_INT >= 33) {
            intent?.getParcelableExtra(EXTRA_RECEIVER, ResultReceiver::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent?.getParcelableExtra(EXTRA_RECEIVER)
        }
        val socksPort = intent?.getIntExtra(EXTRA_SOCKS_PORT, 10808) ?: 10808
        val profilePath = intent?.getStringExtra(EXTRA_PROFILE_PATH)
        val networkHandle = intent?.getLongExtra(EXTRA_NETWORK_HANDLE, 0L) ?: 0L

        scope.launch {
            val ok = runCatching {
                val app = applicationContext
                MihomoAndroidContext.app = app
                bindToUpstream(networkHandle)

                val yamlFile = if (!profilePath.isNullOrBlank()) {
                    java.io.File(profilePath).takeIf { it.isFile }
                } else {
                    null
                } ?: run {
                    val sourceText = OlcRtcRoutingConfig.findSourceProfile(app)?.readText()
                    OlcRtcRoutingConfig.build(app, socksPort, sourceText)
                }
                Log.i(
                    TAG,
                    "routing yaml=${yamlFile.absolutePath} socks=$socksPort netHandle=$networkHandle",
                )
                MihomoEngine.ensureInit(app)
                val setup = MihomoEngine.setupProfile(
                    context = app,
                    yamlPath = yamlFile.absolutePath,
                    selectedMap = mapOf(
                        "PROXY" to OlcRtcRoutingConfig.PROXY_NAME,
                        "GLOBAL" to OlcRtcRoutingConfig.PROXY_NAME,
                    ),
                    mode = "rule",
                )
                if (setup.isNotBlank() &&
                    !setup.equals("null", true) &&
                    !setup.equals("true", true)
                ) {
                    error("setupConfig failed: ${setup.take(160)}")
                }
                MihomoEngine.changeProxy("PROXY", OlcRtcRoutingConfig.PROXY_NAME)
                MihomoEngine.changeProxy("GLOBAL", OlcRtcRoutingConfig.PROXY_NAME)
                MihomoEngine.startListener()
                if (!waitForPort(OlcRtcRoutingConfig.MIXED_PORT, 15_000L)) {
                    error("mixed-port ${OlcRtcRoutingConfig.MIXED_PORT} not ready")
                }
                true
            }.onFailure {
                Log.e(TAG, "olcRTC routing start failed", it)
            }.getOrDefault(false)

            receiver?.send(
                if (ok) RESULT_OK else RESULT_ERROR,
                Bundle().apply {
                    putBoolean(EXTRA_OK, ok)
                    putInt(EXTRA_MIXED_PORT, OlcRtcRoutingConfig.MIXED_PORT)
                    putString(EXTRA_ERROR, if (ok) null else "setup failed")
                },
            )
            if (!ok) {
                runCatching {
                    getSystemService(ConnectivityManager::class.java)?.bindProcessToNetwork(null)
                }
                stopSelf(startId)
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        runCatching { MihomoEngine.stopListener() }
        runCatching {
            getSystemService(ConnectivityManager::class.java)?.bindProcessToNetwork(null)
        }
        super.onDestroy()
    }

    private fun bindToUpstream(networkHandle: Long) {
        if (networkHandle == 0L) {
            Log.w(TAG, "no upstream network handle — DIRECT may loop into VPN")
            return
        }
        val cm = getSystemService(ConnectivityManager::class.java) ?: return
        val network = cm.allNetworks.firstOrNull { net ->
            runCatching { net.networkHandle == networkHandle }.getOrDefault(false)
        }
        if (network == null) {
            Log.w(TAG, "upstream handle $networkHandle not found")
            return
        }
        val ok = cm.bindProcessToNetwork(network)
        Log.i(TAG, "bindProcessToNetwork($network) → $ok")
    }

    private fun waitForPort(port: Int, timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val open = runCatching {
                Socket().use { s ->
                    s.connect(InetSocketAddress("127.0.0.1", port), 250)
                }
                true
            }.getOrDefault(false)
            if (open) return true
            Thread.sleep(150)
        }
        return false
    }

    companion object {
        private const val TAG = "OlcRtcRouting"
        const val ACTION_START = "org.olcbox.app.vpn.OlcRtcRoutingService.START"
        const val ACTION_STOP = "org.olcbox.app.vpn.OlcRtcRoutingService.STOP"
        const val EXTRA_RECEIVER = "receiver"
        const val EXTRA_SOCKS_PORT = "socks_port"
        const val EXTRA_PROFILE_PATH = "profile_path"
        const val EXTRA_NETWORK_HANDLE = "network_handle"
        const val EXTRA_OK = "ok"
        const val EXTRA_MIXED_PORT = "mixed_port"
        const val EXTRA_ERROR = "error"
        const val RESULT_OK = 0
        const val RESULT_ERROR = 1

        fun start(
            context: Context,
            olcRtcSocksPort: Int,
            profilePath: String?,
            networkHandle: Long,
            onResult: (Boolean) -> Unit,
        ) {
            val receiver = object : ResultReceiver(Handler(Looper.getMainLooper())) {
                override fun onReceiveResult(resultCode: Int, resultData: Bundle?) {
                    onResult(resultCode == RESULT_OK && resultData?.getBoolean(EXTRA_OK) == true)
                }
            }
            val intent = Intent(context, OlcRtcRoutingService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_RECEIVER, receiver)
                putExtra(EXTRA_SOCKS_PORT, olcRtcSocksPort)
                putExtra(EXTRA_NETWORK_HANDLE, networkHandle)
                if (!profilePath.isNullOrBlank()) putExtra(EXTRA_PROFILE_PATH, profilePath)
            }
            context.startService(intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, OlcRtcRoutingService::class.java).apply {
                action = ACTION_STOP
            }
            runCatching { context.startService(intent) }
            runCatching {
                val am = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
                am.runningAppProcesses
                    ?.filter { it.processName.endsWith(":route") }
                    ?.forEach { android.os.Process.killProcess(it.pid) }
            }
        }
    }
}
