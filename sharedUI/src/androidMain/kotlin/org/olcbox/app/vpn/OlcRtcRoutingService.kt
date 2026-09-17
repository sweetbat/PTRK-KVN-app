package org.olcbox.app.vpn

import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.os.IBinder
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.olcbox.app.data.mihomo.MihomoAndroidContext
import org.olcbox.app.mihomo.MihomoEngine
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicInteger

/**
 * Clash mixed-port for olcRTC **subscription/update fetch** in `:route` (no libgojni).
 *
 * Success is signaled by writing [STATUS_FILE] and opening :7890 — the VPN process
 * polls the port. Start/stop uses a generation counter so a STOP racing a START does
 * not poison status with ``err:Job was cancelled`` (that forced the flaky DIY bridge).
 */
class OlcRtcRoutingService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var startJob: Job? = null
    private val startGeneration = AtomicInteger(0)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        writeStatus(applicationContext, "starting")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                startGeneration.incrementAndGet()
                startJob?.cancel()
                startJob = null
                runCatching { MihomoEngine.stopListener() }
                runCatching {
                    getSystemService(ConnectivityManager::class.java)?.bindProcessToNetwork(null)
                }
                writeStatus(applicationContext, "stopped")
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_RESET -> {
                runCatching { MihomoEngine.resetConnections() }
                Log.i(TAG, "resetConnections")
                return START_STICKY
            }
            ACTION_START, null -> Unit
            else -> {
                stopSelf(startId)
                return START_NOT_STICKY
            }
        }

        val socksPort = intent?.getIntExtra(EXTRA_SOCKS_PORT, 10808) ?: 10808
        val networkHandle = intent?.getLongExtra(EXTRA_NETWORK_HANDLE, 0L) ?: 0L
        val socksUsername = intent?.getStringExtra(EXTRA_SOCKS_USERNAME).orEmpty()
        val socksPassword = intent?.getStringExtra(EXTRA_SOCKS_PASSWORD).orEmpty()
        val generation = startGeneration.incrementAndGet()

        startJob?.cancel()
        writeStatus(applicationContext, "starting")
        startJob = scope.launch {
            val ok = runCatching {
                if (generation != startGeneration.get()) return@runCatching false
                val app = applicationContext
                MihomoAndroidContext.app = app
                bindToUpstream(networkHandle)
                writeStatus(app, "building")

                val yamlFile = OlcRtcRoutingConfig.buildFetchOnly(
                    context = app,
                    olcRtcSocksPort = socksPort,
                    socksUsername = socksUsername,
                    socksPassword = socksPassword,
                )
                writeStatus(app, "init clash yaml=${yamlFile.length()}b")
                MihomoEngine.ensureInit(app)
                writeStatus(app, "setupConfig")
                val setup = MihomoEngine.setupProfile(
                    context = app,
                    yamlPath = yamlFile.absolutePath,
                    selectedMap = mapOf(
                        "PROXY" to OlcRtcRoutingConfig.PROXY_NAME,
                        "GLOBAL" to OlcRtcRoutingConfig.PROXY_NAME,
                    ),
                    mode = "global",
                    selectedProxyName = OlcRtcRoutingConfig.PROXY_NAME,
                )
                if (setup.isNotBlank() &&
                    !setup.equals("null", true) &&
                    !setup.equals("true", true)
                ) {
                    error("setupConfig failed: ${setup.take(160)}")
                }
                if (generation != startGeneration.get()) return@runCatching false
                MihomoEngine.changeProxy("PROXY", OlcRtcRoutingConfig.PROXY_NAME)
                MihomoEngine.changeProxy("GLOBAL", OlcRtcRoutingConfig.PROXY_NAME)
                writeStatus(app, "startListener")
                MihomoEngine.startListener()
                if (!waitForPort(OlcRtcRoutingConfig.MIXED_PORT, 20_000L)) {
                    error("mixed-port ${OlcRtcRoutingConfig.MIXED_PORT} not ready")
                }
                if (generation != startGeneration.get()) return@runCatching false
                writeStatus(app, "ok")
                true
            }.onFailure {
                if (it is CancellationException) {
                    // STOP/superseded START — do not write err (VPN would fall back to bridge).
                    Log.w(TAG, "start cancelled gen=$generation")
                } else if (generation == startGeneration.get()) {
                    val msg = it.message ?: it.javaClass.simpleName
                    writeStatus(applicationContext, "err:$msg")
                    Log.e(TAG, "olcRTC fetch router start failed", it)
                }
            }.getOrDefault(false)

            if (!ok &&
                generation == startGeneration.get() &&
                readStatus(applicationContext).startsWith("err:")
            ) {
                runCatching {
                    getSystemService(ConnectivityManager::class.java)?.bindProcessToNetwork(null)
                }
                stopSelf(startId)
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        startGeneration.incrementAndGet()
        startJob?.cancel()
        scope.cancel()
        runCatching { MihomoEngine.stopListener() }
        runCatching {
            getSystemService(ConnectivityManager::class.java)?.bindProcessToNetwork(null)
        }
        super.onDestroy()
    }

    private fun bindToUpstream(networkHandle: Long) {
        if (networkHandle == 0L) {
            Log.w(TAG, "no upstream network handle")
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
        const val ACTION_RESET = "org.olcbox.app.vpn.OlcRtcRoutingService.RESET"
        const val EXTRA_SOCKS_PORT = "socks_port"
        const val EXTRA_NETWORK_HANDLE = "network_handle"
        const val EXTRA_SOCKS_USERNAME = "socks_username"
        const val EXTRA_SOCKS_PASSWORD = "socks_password"
        private const val STATUS_NAME = "olcrtc-fetch-status.txt"

        fun statusFile(context: Context): File =
            File(context.filesDir, STATUS_NAME)

        fun writeStatus(context: Context, text: String) {
            runCatching { statusFile(context).writeText(text) }
            Log.i(TAG, "status=$text")
        }

        fun readStatus(context: Context): String =
            runCatching { statusFile(context).readText().trim() }.getOrDefault("")

        fun start(
            context: Context,
            olcRtcSocksPort: Int,
            networkHandle: Long,
            socksUsername: String = "",
            socksPassword: String = "",
        ) {
            writeStatus(context, "queued")
            val intent = Intent(context, OlcRtcRoutingService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_SOCKS_PORT, olcRtcSocksPort)
                putExtra(EXTRA_NETWORK_HANDLE, networkHandle)
                putExtra(EXTRA_SOCKS_USERNAME, socksUsername)
                putExtra(EXTRA_SOCKS_PASSWORD, socksPassword)
            }
            context.startService(intent)
        }

        fun stop(context: Context) {
            writeStatus(context, "stopping")
            val intent = Intent(context, OlcRtcRoutingService::class.java).apply {
                action = ACTION_STOP
            }
            runCatching { context.startService(intent) }
        }

        fun resetConnections(context: Context) {
            val intent = Intent(context, OlcRtcRoutingService::class.java).apply {
                action = ACTION_RESET
            }
            runCatching { context.startService(intent) }
        }
    }
}
