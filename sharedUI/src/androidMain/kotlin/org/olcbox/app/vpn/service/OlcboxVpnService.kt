package org.olcbox.app.vpn.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import mobile.Mobile
import mobile.Runtime as OlcRtcRuntime
import mobile.SocketProtector
import org.olcbox.app.data.TUN2SOCKS_CONFIG_FILE_NAME
import org.olcbox.app.vpn.awaitRuntimeReady
import org.olcbox.app.data.datasource.LocationsDataSourceImpl
import org.olcbox.app.data.datasource.LocationsRepositoryImpl
import org.olcbox.app.data.identity.PersistentDeviceIdentityProvider
import org.olcbox.app.data.model.LocationConfig
import org.olcbox.app.data.model.LocationEntry
import org.olcbox.app.data.mihomo.mihomoProfilePath
import org.olcbox.app.mihomo.MihomoEngine
import org.olcbox.app.data.repository.LocationsRepository
import org.olcbox.app.vpn.AndroidConnectionMode
import org.olcbox.app.vpn.AndroidSocksProxySettings
import org.olcbox.app.vpn.RuSafeDns
import org.olcbox.app.vpn.AndroidSplitTunnelMode
import org.olcbox.app.vpn.OlcRtcRoutingService
import org.olcbox.app.vpn.UpstreamCandidate
import org.olcbox.app.vpn.UpstreamNetworkSelector
import org.olcbox.app.vpn.UpstreamTransport
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
import org.json.JSONObject
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread
import kotlin.coroutines.coroutineContext

class OlcboxVpnService : VpnService() {

    private external fun startTun2socksNative(configPath: String, fd: Int): Int
    private external fun stopTun2socksNative()
    private external fun getTun2socksStatsNative(): LongArray

    private val job = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.Default + job)
    @Volatile private var olcRtcRuntime: OlcRtcRuntime? = null
    @Volatile private var mihomoTunActive = false
    /** True only after Mihomo/libclash was actually initialized in this :vpn process. */
    @Volatile private var mihomoEngineTouched = false
    /** Cleared on olcRTC start; kept only to tear down a leftover `:route` from older builds. */
    @Volatile private var olcRtcRoutingActive = false

    private fun rtc(): OlcRtcRuntime {
        val existing = olcRtcRuntime
        if (existing != null) return existing
        val created = Mobile.new_()
        olcRtcRuntime = created
        runCatching {
            java.io.File(applicationContext.filesDir, "vpn_gojni_loaded").writeText("1")
        }
        return created
    }

    private fun isRtcRunning(): Boolean = olcRtcRuntime?.isRunning == true
    private var lastMobileRoom = ""
    private var lastStoppedJitsiRoom = ""
    private val tunnelMutex = Mutex()
    private val repository: LocationsRepository by lazy {
        LocationsRepositoryImpl(LocationsDataSourceImpl(applicationContext))
    }
    private val deviceIdentityProvider by lazy {
        PersistentDeviceIdentityProvider(LocationsDataSourceImpl(applicationContext))
    }

    private var startupJob: Job? = null
    private var watchdogJob: Job? = null
    private var cleanupJob: Job? = null
    private var networkLossJob: Job? = null
    private var recoveryJob: Job? = null
    private var reconnectAttempt = 0
    private var generation = 0L
    private var recoveryRequestedForGeneration = 0L
    private var watchdogTunStats: Tun2SocksStats? = null
    private var watchdogStalledSamples = 0
    private var lastWakeLockRefreshAtMs = 0L
    @Volatile
    private var lastRtcConnectedAtMs = 0L
    @Volatile
    private var lastRtcFailureAtMs = 0L
    @Volatile
    private var rtcFailureCount = 0
    @Volatile
    private var lastMobileProvider: String? = null
    @Volatile
    private var lastJitsiStopCompletedAtMs = 0L

    private var vpnInterface: ParcelFileDescriptor? = null
    private var tun2socksThread: Thread? = null
    @Volatile
    private var tun2socksStarted = false
    @Volatile
    private var tun2socksStopRequested = false

    private var wakeLock: PowerManager.WakeLock? = null
    private lateinit var connectivityManager: ConnectivityManager
    private var currentNetwork: Network? = null
    private var currentNetworkTransport: UpstreamTransport? = null
    private var isCallbackRegistered = false
    private var connectionMode = AndroidConnectionMode.Tun
    private var socksListenHost = AndroidSocksProxySettings.DEFAULT_HOST
    private var socksListenPort = AndroidSocksProxySettings.DEFAULT_PORT
    private var socksUsername = ""
    private var socksPassword = ""
    private var splitTunnelMode = AndroidSplitTunnelMode.AllApps
    private var splitTunnelProxyApps = emptySet<String>()
    private var splitTunnelBypassApps = emptySet<String>()
    private var mihomoMode = "rule"
    private var socksProxy: AuthenticatedSocksProxy? = null

    private data class StartOptions(
        val connectionMode: AndroidConnectionMode,
        val socksListenHost: String,
        val socksListenPort: Int,
        val socksUsername: String,
        val socksPassword: String,
        val splitTunnelMode: AndroidSplitTunnelMode,
        val splitTunnelProxyApps: Set<String>,
        val splitTunnelBypassApps: Set<String>,
        val mihomoMode: String,
    )

    private data class Tun2SocksStats(
        val txPackets: Long,
        val txBytes: Long,
        val rxPackets: Long,
        val rxBytes: Long
    )

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            handleNetworkChange(network, "Available")
        }

        override fun onLost(network: Network) {
            addLog("Network lost")
            if (network != currentNetwork) return

            networkLossJob?.cancel()
            networkLossJob = scope.launch {
                delay(NETWORK_LOSS_GRACE_MS)
                if (network != currentNetwork) return@launch

                val upstream = findActiveUpstreamNetwork()
                if (upstream != null) {
                    handleNetworkChange(upstream, "Fallback")
                    return@launch
                }

                if (OlcboxVpnState.status.value is VpnStatus.Connected ||
                    OlcboxVpnState.status.value is VpnStatus.Reconnecting
                ) {
                    updateUnderlyingNetwork(null)
                    unbindProcessFromNetwork()
                    setStatus(VpnStatus.Reconnecting)
                    updateNotification(vpnNotifyWaitingNetwork())
                    addLog("Waiting for upstream network")
                }
            }
        }

        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
            if (network == currentNetwork || caps.isUsableUpstream()) {
                handleNetworkChange(network, "Capabilities")
            }
        }

        private fun handleNetworkChange(network: Network, reason: String) {
            val caps = connectivityManager.getNetworkCapabilities(network) ?: return
            if (!caps.isUsableUpstream()) return
            networkLossJob?.cancel()

            val upstream = findActiveUpstreamNetwork() ?: return
            if (currentNetwork == upstream) {
                if (OlcboxVpnState.status.value is VpnStatus.Reconnecting &&
                    startupJob?.isActive != true
                ) {
                    addLog("Network $reason: ${getNetName(upstream)}")
                    requestTransportRecovery(
                        reason = "Network available",
                        fullRestart = false,
                        delayMs = NETWORK_STABILITY_GRACE_MS
                    )
                }
                return
            }

            val previousTransport = currentNetworkTransport
            val nextTransport = upstream.transportOrNull()
            updateUnderlyingNetwork(upstream)
            addLog("Network $reason: ${getNetName(upstream)}")

            when (OlcboxVpnState.status.value) {
                is VpnStatus.Connected -> {
                    if (isBenignWifiRefresh(previousTransport, nextTransport)) {
                        addLog("Keeping transport on refreshed Wi-Fi network")
                    } else {
                        requestTransportRecovery(
                            reason = "Upstream network changed",
                            fullRestart = false,
                            delayMs = NETWORK_STABILITY_GRACE_MS,
                            setReconnectingImmediately = false
                        )
                    }
                }

                is VpnStatus.Reconnecting -> {
                    if (isBenignWifiRefresh(previousTransport, nextTransport) &&
                        isRtcRunning() &&
                        canReconnectTransportInPlace()
                    ) {
                        setStatus(VpnStatus.Connected)
                        updateNotification(connectedNotificationText())
                        startWatchdog()
                    } else {
                        requestTransportRecovery(
                            reason = "Upstream network changed",
                            fullRestart = false,
                            delayMs = NETWORK_STABILITY_GRACE_MS
                        )
                    }
                }

                else -> Unit
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        wakeLock = (getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "PTRKKVN::VpnWakeLock")
            .apply { setReferenceCounted(false) }
        // Do NOT touch Mobile/libgojni here — loading it before Mihomo/libclash
        // in the same `:vpn` process crashes or hangs the dual Go runtimes.
        org.olcbox.app.data.mihomo.MihomoAndroidContext.app = applicationContext
        org.olcbox.app.vpn.service.VpnStatusBridge.ensureRegistered(applicationContext)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            OlcboxVpnActions.ACTION_STOP_VPN -> {
                addLog("Stop VPN requested")
                cleanup()
                return START_NOT_STICKY
            }

            OlcboxVpnActions.ACTION_START_VPN -> Unit
            else -> {
                cleanup()
                stopSelf()
                return START_NOT_STICKY
            }
        }

        applyStartOptions(loadStartOptions(intent))
        val isRestart = shouldRestartForStartCommand()
        if (isRestart) {
            addLog("Restarting ${activeModeLabel()} for selected location")
        }
        // Show Connecting immediately — if this never appears, START never reached the service.
        setStatus(if (isRestart) VpnStatus.Reconnecting else VpnStatus.Connecting)
        startForeground(
            if (connectionMode == AndroidConnectionMode.Proxy) {
                vpnNotifyStartingProxy()
            } else {
                vpnNotifyConnecting()
            }
        )
        startTunnel(isMigration = false, isRestart = isRestart)
        return START_REDELIVER_INTENT
    }

    override fun onDestroy() {
        super.onDestroy()
        cleanup(stopService = false)
    }

    override fun onRevoke() {
        addLog("VPN permission revoked")
        cleanup()
        stopSelf()
        super.onRevoke()
    }

    private fun installMobileCallbacks() {
        rtc().setProtector(object : SocketProtector {
            override fun protect(fd: Long): Boolean {
                if (connectionMode == AndroidConnectionMode.Proxy) return true
                return this@OlcboxVpnService.protect(fd.toInt())
            }
        })
        // Bundled olcrtc AAR may not expose setLogWriter; rtc logs still appear via status/watchdog.
    }

    private fun loadStartOptions(intent: Intent): StartOptions {
        val preferences = runCatching {
            runBlocking { applicationContext.vpnPrefDataStore.data.first() }
        }.getOrNull()

        val socksPort = if (intent.hasExtra(OlcboxVpnActions.EXTRA_SOCKS_PORT)) {
            intent.getIntExtra(
                OlcboxVpnActions.EXTRA_SOCKS_PORT,
                AndroidSocksProxySettings.DEFAULT_PORT
            )
        } else {
            preferences?.get(KEY_ANDROID_SOCKS_PORT)
        }

        return StartOptions(
            connectionMode = AndroidConnectionMode.fromValue(
                intent.getStringExtra(OlcboxVpnActions.EXTRA_CONNECTION_MODE)
                    ?: preferences?.get(KEY_ANDROID_CONNECTION_MODE)
            ),
            socksListenHost = AndroidSocksProxySettings.sanitizeHost(
                intent.getStringExtra(OlcboxVpnActions.EXTRA_SOCKS_HOST)
                    ?: preferences?.get(KEY_ANDROID_SOCKS_HOST)
            ),
            socksListenPort = AndroidSocksProxySettings.sanitizePort(socksPort),
            socksUsername = (
                intent.getStringExtra(OlcboxVpnActions.EXTRA_SOCKS_USERNAME)
                    ?: preferences?.get(KEY_ANDROID_SOCKS_USERNAME)
                ).orEmpty().takeIf { it.isNotBlank() }.orEmpty(),
            socksPassword = (
                intent.getStringExtra(OlcboxVpnActions.EXTRA_SOCKS_PASSWORD)
                    ?: preferences?.get(KEY_ANDROID_SOCKS_PASSWORD)
                ).orEmpty(),
            splitTunnelMode = AndroidSplitTunnelMode.fromValue(
                intent.getStringExtra(OlcboxVpnActions.EXTRA_SPLIT_TUNNEL_MODE)
                    ?: preferences?.get(KEY_ANDROID_SPLIT_TUNNEL_MODE)
            ),
            splitTunnelProxyApps = intent.stringCollectionExtra(
                OlcboxVpnActions.EXTRA_SPLIT_TUNNEL_PROXY_APPS
            ) ?: preferences?.get(KEY_ANDROID_SPLIT_TUNNEL_PROXY_APPS).orEmpty(),
            splitTunnelBypassApps = intent.stringCollectionExtra(
                OlcboxVpnActions.EXTRA_SPLIT_TUNNEL_BYPASS_APPS
            ) ?: preferences?.get(KEY_ANDROID_SPLIT_TUNNEL_BYPASS_APPS).orEmpty(),
            mihomoMode = MihomoModeStore.resolve(
                context = applicationContext,
                intentExtra = intent.getStringExtra(OlcboxVpnActions.EXTRA_MIHOMO_MODE),
                dataStoreValue = preferences?.get(KEY_MIHOMO_MODE),
            ),
        )
    }

    private fun applyStartOptions(options: StartOptions) {
        connectionMode = options.connectionMode
        socksListenHost = options.socksListenHost
        socksListenPort = options.socksListenPort
        socksUsername = options.socksUsername
        socksPassword = options.socksPassword
        splitTunnelMode = options.splitTunnelMode
        splitTunnelProxyApps = options.splitTunnelProxyApps
        splitTunnelBypassApps = options.splitTunnelBypassApps
        mihomoMode = options.mihomoMode
    }

    private fun Intent.stringCollectionExtra(key: String): Set<String>? {
        @Suppress("DEPRECATION")
        val value = extras?.get(key) ?: return null
        val items = when (value) {
            is ArrayList<*> -> value.asSequence()
            is Set<*> -> value.asSequence()
            is Array<*> -> value.asSequence()
            else -> return emptySet()
        }
        return items
            .mapNotNull { (it as? String)?.trim()?.takeIf { item -> item.isNotBlank() } }
            .toSet()
    }

    private fun startTunnel(
        isMigration: Boolean,
        forceFullRestart: Boolean = false,
        isRestart: Boolean = false
    ) {
        val previousStartupJob = startupJob
        val hadPendingStartup = previousStartupJob?.isActive == true
        previousStartupJob?.cancel()
        watchdogJob?.cancel()
        networkLossJob?.cancel()
        recoveryJob?.cancel()
        recoveryJob = null
        if (hadPendingStartup) {
            addLog("Canceling pending olcRTC start")
            stopMobile()
            stopTun2socks()
        }
        if (!isMigration) {
            resetRecoveryState()
        }
        val requestedGeneration = ++generation
        refreshWakeLock(force = true)

        startupJob = scope.launch {
            try {
                cleanupJob?.takeIf { it.isActive }?.let {
                    addLog("Waiting for previous olcRTC cleanup")
                    val completed = withTimeoutOrNull(PREVIOUS_STOP_WAIT_MS) {
                        it.join()
                        true
                    } ?: false

                    if (!completed) {
                        addLog("Previous olcRTC cleanup is still pending; forcing transport cleanup")
                        it.cancel()
                        stopTransportProcesses(closeTun = true, waitForSocksPort = false)
                    }
                }

                if (!isMigration) {
                    registerNetworkMonitor()
                    updateUnderlyingNetwork(findActiveUpstreamNetwork())
                }

                tunnelMutex.withLock {
                    coroutineContext.ensureActive()
                    if (requestedGeneration != generation) return@withLock

                    val entry = repository.getActiveLocation()?.normalized()
                    if (entry == null || !entry.isUsable()) {
                        setStatus(VpnStatus.Error("No active location"))
                        updateNotification("Add a location first")
                        stopTransportProcesses(closeTun = true, waitForSocksPort = false)
                        return@withLock
                    }

                    if (entry.isMihomo()) {
                        startMihomoFullTunnel(entry, requestedGeneration, isMigration, isRestart)
                        return@withLock
                    }

                    val location = entry.location
                    if (isMigration && !forceFullRestart && canReconnectTransportInPlace()) {
                        reconnectTransport(location, requestedGeneration)
                    } else {
                        startFullTunnel(location, requestedGeneration, isMigration, isRestart)
                    }
                }
            } finally {
                if (requestedGeneration == generation) {
                    releaseWakeLock()
                }
            }
        }
    }

    private suspend fun reconnectTransport(location: LocationConfig, requestedGeneration: Long) {
        setStatus(VpnStatus.Reconnecting)
        updateNotification(vpnNotifyReconnecting())
        val upstream = findActiveUpstreamNetwork()
        if (upstream == null) {
            updateUnderlyingNetwork(null)
            unbindProcessFromNetwork()
            updateNotification(vpnNotifyWaitingNetwork())
            addLog("No upstream network; keeping tunnel alive")
            scheduleTransportRetry(requestedGeneration, "no upstream network", NETWORK_RETRY_BASE_DELAY_MS)
            return
        }

        updateUnderlyingNetwork(upstream)
        stopMobileAndWait()
        coroutineContext.ensureActive()
        if (requestedGeneration != generation) return

        if (startMobile(location, upstream, requestedGeneration, setErrorOnFailure = false)) {
            setStatus(VpnStatus.Connected)
            resetRecoveryState()
            updateNotification(connectedNotificationText())
            addLog("${activeModeLabel()} transport reconnected")
            startWatchdog()
        } else {
            updateUnderlyingNetwork(null)
            setStatus(VpnStatus.Reconnecting)
            updateNotification(vpnNotifyWaitingTransport())
            scheduleTransportRetry(requestedGeneration, "transport reconnect failed")
        }
    }

    private suspend fun startMihomoFullTunnel(
        entry: LocationEntry,
        requestedGeneration: Long,
        isMigration: Boolean,
        isRestart: Boolean,
    ) {
        setStatus(if (isMigration || isRestart) VpnStatus.Reconnecting else VpnStatus.Connecting)
        updateNotification(vpnNotifyConnecting())
        // Tear down hev/olcRTC sockets, but do NOT bind this process to upstream —
        // FlClash notes that bindProcessToNetwork breaks ordinary Mihomo nodes.
        stopOlcRtcRoutingRouter()
        stopAuthenticatedSocksProxy()
        stopTun2socks()
        cleanupVpnInterface()
        stopMihomoTun()
        unbindProcessFromNetwork()
        updateUnderlyingNetwork(null)
        coroutineContext.ensureActive()
        if (requestedGeneration != generation) return

        val upstream = findActiveUpstreamNetwork()
        if (upstream == null) {
            addLog("No upstream network")
            setStatus(VpnStatus.Reconnecting)
            updateNotification(vpnNotifyWaitingNetwork())
            if (isMigration) {
                scheduleTransportRetry(requestedGeneration, "no upstream network", NETWORK_RETRY_BASE_DELAY_MS)
            }
            return
        }
        currentNetwork = upstream
        currentNetworkTransport = upstream.transportOrNull()

        val profileId = entry.mihomoProfileId.orEmpty()
        val proxyName = entry.mihomoProxyName.orEmpty()
        val yamlPath = mihomoProfilePath(profileId)
        if (yamlPath.isNullOrBlank()) {
            setStatus(VpnStatus.Error("Mihomo profile missing"))
            updateNotification("Re-import subscription")
            return
        }

        // Intent / mihomo_mode.txt beat DataStore: UI and `:vpn` have separate
        // in-memory DataStore caches, so prefs alone often keep stale `rule`.
        val mode = MihomoModeStore.normalize(mihomoMode).also {
            MihomoModeStore.write(applicationContext, it)
        }
        // rule (default) = routing on: RU whitelist DIRECT via subscription rules.
        // global = routing off: all traffic via the selected node.

        try {
            mihomoEngineTouched = true
            runCatching {
                java.io.File(applicationContext.filesDir, "vpn_clash_loaded").writeText("1")
            }
            MihomoEngine.ensureInit(applicationContext)
            MihomoEngine.setVpnState(enabled = true)
            pushUpstreamDnsToMihomo(upstream)
            // hev → Clash mixed-port (Core.startTun TCP is broken on this stack;
            // olcRTC already proves hev-socks5-tunnel works in :vpn).
            socksListenHost = "127.0.0.1"
            socksListenPort = MIHOMO_MIXED_PORT
            addLog("Mihomo setup mode=$mode proxy=$proxyName path=$yamlPath (YouTube→GLOBAL)")
            val setup = MihomoEngine.setupProfile(
                context = applicationContext,
                yamlPath = yamlPath,
                selectedMap = mapOf("GLOBAL" to proxyName, "PROXY" to proxyName),
                mode = mode,
                selectedProxyName = proxyName,
            )
            addLog("Mihomo setupConfig: ${setup.take(160)}")
            if (setup.isNotBlank() &&
                !setup.equals("null", true) &&
                !setup.equals("true", true)
            ) {
                addLog("Mihomo setup failed: $setup")
                stopMihomoTun()
                setStatus(VpnStatus.Error("Mihomo config failed"))
                updateNotification(vpnNotifyTunnelFailed())
                return
            }
            // Belt-and-suspenders: force mode after setup (subscription YAML may reset it).
            val modeApply = MihomoEngine.setMode(mode)
            addLog("Mihomo setMode($mode): ${modeApply.take(80)}")
            MihomoEngine.selectProxyPreferringGroups(proxyName)
            addLog(
                "Mihomo selected GLOBAL=${MihomoEngine.nowSelected("GLOBAL")} " +
                    "PROXY=${MihomoEngine.nowSelected("PROXY")} " +
                    "root=${MihomoEngine.nowSelected("🚀 PTRK-KVN")}",
            )
            MihomoEngine.startListener()
            val socksReady = waitForLocalSocksPort(MIHOMO_MIXED_PORT, timeoutMs = 8_000L)
            if (!socksReady) {
                addLog("Mihomo mixed-port $MIHOMO_MIXED_PORT not ready")
                stopMihomoTun()
                setStatus(VpnStatus.Error("Mihomo SOCKS failed"))
                updateNotification(vpnNotifyTunnelFailed())
                return
            }
            addLog("Mihomo mixed-port ready on 127.0.0.1:$MIHOMO_MIXED_PORT")

            coroutineContext.ensureActive()
            if (requestedGeneration != generation) return

            delay(TUNNEL_HANDOFF_DELAY_MS)
            // No hev mapdns: its 100.64/10 answers hit GEOIP,private → DIRECT and
            // make rule-mode look like "everything is home IP".
            val pfd = establishSystemVpnTunnel(
                dnsServers = RuSafeDns.YANDEX_PLAIN,
                extraBypassPackages = torrentBypassPackages(),
                useMapDns = false,
            )
            if (pfd == null) {
                stopMihomoTun()
                return
            }
            vpnInterface = pfd
            if (!startTun2socks(pfd, useMapDns = false)) {
                stopMihomoTun()
                cleanupVpnInterface()
                return
            }
            mihomoTunActive = true
            runCatching {
                setUnderlyingNetworks(arrayOf(upstream))
            }
            pushUpstreamDnsToMihomo(upstream)

            coroutineContext.ensureActive()
            if (requestedGeneration != generation) return

            val probeMs = runCatching {
                MihomoEngine.urlTest(proxyName)
            }.getOrDefault(-1L)
            addLog("Mihomo post-hev urlTest($proxyName)=${probeMs}ms traffic=${MihomoEngine.traffic()}")

            setStatus(VpnStatus.Connected)
            resetRecoveryState()
            updateNotification(connectedNotificationText())
            addLog("Mihomo VPN tunnel established (hev→mixed-port)")
            startWatchdog()
        } catch (e: CancellationException) {
            withContext(NonCancellable) {
                stopMihomoTun()
                cleanupVpnInterface()
            }
            throw e
        } catch (e: Exception) {
            addLog("Mihomo start failed: ${e.message}")
            android.util.Log.e(TAG, "Mihomo start failed", e)
            stopMihomoTun()
            cleanupVpnInterface()
            if (requestedGeneration == generation) {
                setStatus(VpnStatus.Error(e.message ?: "Mihomo failed"))
                updateNotification(vpnNotifyFailed())
            }
        }
    }

    private fun establishMihomoVpnTunnel(): ParcelFileDescriptor? {
        return try {
            // FlClash/PTRK core expects 172.19.0.1/30 + in-tun DNS 172.19.0.2.
            // Using olcRTC's 10.0.88.88 here makes Core.startTun fail → "Tunnel failed".
            val optionsJson = MihomoEngine.vpnOptionsJson()
            val options = runCatching { JSONObject(optionsJson) }.getOrNull()
            val ipv4Cidr = options?.optString("ipv4Address")
                ?.takeIf { it.isNotBlank() }
                ?: "172.19.0.1/30"
            val dns = options?.optString("dnsServerAddress")
                ?.takeIf { it.isNotBlank() }
                ?: "172.19.0.2"
            val slash = ipv4Cidr.lastIndexOf('/')
            val address = if (slash > 0) ipv4Cidr.substring(0, slash) else "172.19.0.1"
            val prefix = if (slash > 0) {
                ipv4Cidr.substring(slash + 1).toIntOrNull() ?: 30
            } else {
                30
            }
            addLog("Mihomo VPN establish addr=$address/$prefix dns=$dns")
            val builder = Builder()
                .setSession("PTRK-KVN")
                .setMtu(TUN_MTU)
                .addAddress(address, prefix)
                .addRoute("0.0.0.0", 0)
                .addDnsServer(dns)
                .setBlocking(false)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                runCatching { builder.setMetered(false) }
            }

            if (!applySplitTunneling(builder)) return null

            // Do not setUnderlyingNetworks on Builder — FlClash attaches upstream
            // only after Core.startTun so dial sockets are not mis-bound early.
            builder.establish()
        } catch (e: Exception) {
            addLog("Mihomo VPN establish failed: ${e.message}")
            setStatus(VpnStatus.Error(e.message ?: "VPN establish failed"))
            updateNotification("VPN tunnel error")
            null
        }
    }

    private fun pushUpstreamDnsToMihomo(network: Network?) {
        if (network == null) return
        val servers = RuSafeDns.sanitize(
            connectivityManager.getLinkProperties(network)
                ?.dnsServers
                .orEmpty()
                .mapNotNull { it.hostAddress?.takeIf(String::isNotBlank) },
        )
        val effective = servers.ifEmpty { RuSafeDns.YANDEX_PLAIN }
        MihomoEngine.updateDns(effective)
        addLog(
            if (servers.isEmpty()) {
                "Mihomo updateDns fallback ${effective.joinToString(",")}"
            } else {
                "Mihomo updateDns ${effective.joinToString(",")}"
            },
        )
    }

    private fun stopMihomoTun() {
        // CRITICAL: Core.init loads libclash. Calling this before any Mihomo use
        // poisons :vpn so a later Mobile/libgojni start crashes → no olcRTC VPN.
        if (!mihomoTunActive && !mihomoEngineTouched) return
        if (mihomoTunActive) {
            addLog("Stopping Mihomo TUN")
        }
        stopTun2socks()
        cleanupVpnInterface()
        runCatching { MihomoEngine.stopListener() }
        runCatching { MihomoEngine.stopTun() }
        mihomoTunActive = false
    }

    private suspend fun waitForLocalSocksPort(port: Int, timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (isLocalSocksPortOpen(port)) return true
            delay(SOCKS_RELEASE_POLL_MS)
        }
        return isLocalSocksPortOpen(port)
    }

    private suspend fun startFullTunnel(
        location: LocationConfig,
        requestedGeneration: Long,
        isMigration: Boolean,
        isRestart: Boolean
    ) {
        setStatus(if (isMigration || isRestart) VpnStatus.Reconnecting else VpnStatus.Connecting)
        updateNotification(vpnNotifyConnecting())
        stopMihomoTun()
        stopTransportProcesses(closeTun = true, waitForSocksPort = true)
        coroutineContext.ensureActive()
        if (requestedGeneration != generation) return

        val upstream = findActiveUpstreamNetwork()
        if (upstream == null) {
            updateUnderlyingNetwork(null)
            unbindProcessFromNetwork()
            addLog("No upstream network")
            setStatus(VpnStatus.Reconnecting)
            updateNotification(vpnNotifyWaitingNetwork())
            if (isMigration) {
                scheduleTransportRetry(requestedGeneration, "no upstream network", NETWORK_RETRY_BASE_DELAY_MS)
            }
            return
        }
        updateUnderlyingNetwork(upstream)

        if (!startMobile(location, upstream, requestedGeneration, setErrorOnFailure = !isMigration)) {
            if (isMigration) {
                updateUnderlyingNetwork(null)
                setStatus(VpnStatus.Reconnecting)
                updateNotification(vpnNotifyWaitingTransport())
                scheduleTransportRetry(requestedGeneration, "transport start failed")
            }
            return
        }

        coroutineContext.ensureActive()
        if (requestedGeneration != generation) return

        if (connectionMode == AndroidConnectionMode.Proxy) {
//            if (!startAuthenticatedSocksProxy()) {
//                stopTransportProcesses(closeTun = true)
//                return
//            }
            setStatus(VpnStatus.Connected)
            resetRecoveryState()
            updateNotification(connectedNotificationText())
            addLog("Proxy mode connected on SOCKS $socksListenHost:$socksListenPort")
            startWatchdog()
            return
        }

        delay(TUNNEL_HANDOFF_DELAY_MS)
        coroutineContext.ensureActive()

        // olcRTC routing is server-side (olcwave). App "Маршрутизация" is Mihomo-only.
        stopOlcRtcRoutingRouter()
        val pfd = establishSystemVpnTunnel()
        if (pfd == null) {
            stopMobileAndWait()
            return
        }

        vpnInterface = pfd
        if (!startTun2socks(pfd)) {
            stopTransportProcesses(closeTun = true)
            return
        }

        coroutineContext.ensureActive()
        if (requestedGeneration != generation) return

        setStatus(VpnStatus.Connected)
        resetRecoveryState()
        updateNotification(connectedNotificationText())
        addLog("VPN tunnel established (olcRTC; app routing N/A)")
        startWatchdog()
    }

    private fun stopOlcRtcRoutingRouter() {
        if (olcRtcRoutingActive) {
            addLog("Stopping leftover olcRTC Clash router")
        }
        olcRtcRoutingActive = false
        runCatching { OlcRtcRoutingService.stop(applicationContext) }
    }

    private suspend fun startMobile(
        location: LocationConfig,
        upstream: Network,
        requestedGeneration: Long,
        setErrorOnFailure: Boolean
    ): Boolean {
        val keepProcessBound = shouldKeepProcessBound(upstream)
        val config = location.normalized()
        return try {
            installMobileCallbacks()
            val targetSocksPort = socksListenPort
            val deviceId = deviceIdentityProvider.hwid()
            resetRtcHealthState()

            waitForSocksPortReleased(targetSocksPort, SOCKS_RELEASE_QUICK_TIMEOUT_MS)
            if (isLocalSocksPortOpen(targetSocksPort)) {
                throw IllegalStateException("SOCKS port $targetSocksPort is still in use")
            }
            waitForJitsiRoomCleanup(config.bypassProvider, config.id)
            bindProcessToNetwork(upstream, "Bound to ${getNetName(upstream)}")
            coroutineContext.ensureActive()
            olcRtcRuntime = Mobile.new_()
            runCatching {
                java.io.File(applicationContext.filesDir, "vpn_gojni_loaded").writeText("1")
            }
            installMobileCallbacks()
            configureMobileRuntime(config, deviceId, targetSocksPort)
            addLog(
                "Starting olcRTC provider=${config.bypassProvider}, " +
                    "transport=${config.transport}, room=${config.id}"
            )
            lastMobileProvider = config.bypassProvider
            lastMobileRoom = config.id
            rtc().start()
            awaitRuntimeReady(MOBILE_READY_TIMEOUT_MS) { rtc().waitReady(it) }
            if (requestedGeneration != generation) {
                addLog("olcRTC start superseded")
                return false
            }
            coroutineContext.ensureActive()
            addLog("olcRTC ready on $socksListenHost:$targetSocksPort")
            markRtcConnected()
            if (keepProcessBound) {
                addLog("Keeping olcRTC bound to ${getNetName(upstream)}")
            }
            true
        } catch (e: CancellationException) {
            withContext(NonCancellable) {
                addLog("olcRTC start canceled")
                unbindProcessFromNetwork()
                stopMobileAndWait()
            }
            throw e
        } catch (e: Exception) {
            val staleRequest = requestedGeneration != generation
            val message = e.message ?: "Transport failed"
            if (staleRequest) {
                addLog("olcRTC start canceled: $message")
            } else {
                addLog("olcRTC start failed: $message")
            }
            unbindProcessFromNetwork()
            stopMobileAndWait()
            if (!staleRequest && setErrorOnFailure) {
                setStatus(VpnStatus.Error(message))
                updateNotification(vpnNotifyFailed())
            }
            false
        } finally {
            if (!keepProcessBound || !isRtcRunning()) {
                unbindProcessFromNetwork()
            }
        }
    }

    private suspend fun waitForJitsiRoomCleanup(provider: String, room: String) {
        if (LocationConfig.normalizeProvider(provider) != LocationConfig.PROVIDER_JITSI) return
        if (room != lastStoppedJitsiRoom) return

        val waitMs = JITSI_RESTART_SETTLE_MS -
            (System.currentTimeMillis() - lastJitsiStopCompletedAtMs)
        if (waitMs <= 0L) return

        addLog("Waiting for previous Jitsi room cleanup")
        delay(waitMs)
    }

    private fun configureMobileRuntime(
        location: LocationConfig,
        deviceId: String,
        socksPort: Int
    ) {
        val config = location.normalized()
        rtc().setProvider(config.bypassProvider)
        rtc().setTransport(config.transport)
        rtc().setRoom(config.id)
        rtc().setKey(config.key)
        rtc().setDeviceID(deviceId)
        rtc().setDNS(resolveOlcRtcDnsServer(config.dnsServer))
        rtc().setSocksListenHost(socksListenHost)
        rtc().setSocksPort(socksPort.toLong())
        rtc().setSocksCredentials(socksUsername, socksPassword)
        rtc().setVP8Options(config.vp8Fps.toLong(), config.vp8Batch.toLong())
    }

    private fun startTun2socks(
        pfd: ParcelFileDescriptor,
        useMapDns: Boolean = true,
    ): Boolean {
        return try {
            if (!ensureNativeLibrariesLoaded()) {
                addLog("tun2socks native libraries are unavailable")
                setStatus(VpnStatus.Error("tun2socks native libraries are unavailable"))
                updateNotification(vpnNotifyTunnelFailed())
                return false
            }

            val nativeFd = ParcelFileDescriptor.dup(pfd.fileDescriptor).detachFd()
            val configFile = writeTun2socksConfig(useMapDns = useMapDns)
            tun2socksStarted = true
            tun2socksStopRequested = false
            tun2socksThread = thread(name = "OlcboxTun2Socks", isDaemon = true) {
                try {
                    val result = startTun2socksNative(configFile.absolutePath, nativeFd)
                    if (OlcboxVpnState.status.value !is VpnStatus.Stopping && result != 0) {
                        addLog("tun2socks exited with code $result")
                    } else {
                        addLog("tun2socks stopped")
                    }
                } finally {
                    tun2socksStarted = false
                    tun2socksStopRequested = false
                }
            }
            true
        } catch (e: Exception) {
            addLog("tun2socks start failed: ${e.message}")
            setStatus(VpnStatus.Error(e.message ?: "tun2socks failed"))
            updateNotification(vpnNotifyTunnelFailed())
            false
        }
    }

    private fun establishSystemVpnTunnel(
        dnsServers: List<String> = listOf(MAPDNS_ADDRESS),
        extraBypassPackages: Collection<String> = emptyList(),
        useMapDns: Boolean = true,
    ): ParcelFileDescriptor? {
        return try {
            val builder = Builder()
                .setSession("PTRK-KVN")
                .setMtu(TUN_MTU)
                .addAddress(TUN_IPV4_ADDRESS, IPV4_PREFIX_LENGTH)
                .addRoute("0.0.0.0", 0)
                // Many RU sites are dual-stack. Without an IPv6 VPN route MIUI often
                // blackholes AAAA while foreign IPv4-only hosts still work.
                .addAddress(TUN_IPV6_ADDRESS, IPV6_PREFIX_LENGTH)
                .addRoute("::", 0)
                .setBlocking(true)

            dnsServers
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .distinct()
                .ifEmpty { listOf(if (useMapDns) MAPDNS_ADDRESS else RuSafeDns.YANDEX_PLAIN.first()) }
                .forEach { builder.addDnsServer(it) }

            if (!applySplitTunneling(builder, extraBypassPackages)) return null

            currentNetwork?.let { builder.setUnderlyingNetworks(arrayOf(it)) }
            builder.establish().also {
                addLog("VPN establish v4=$TUN_IPV4_ADDRESS v6=$TUN_IPV6_ADDRESS mapdns=$useMapDns")
            }
        } catch (e: Exception) {
            addLog("VPN establish failed: ${e.message}")
            setStatus(VpnStatus.Error(e.message ?: "VPN establish failed"))
            updateNotification("VPN tunnel error")
            null
        }
    }

    private fun applySplitTunneling(
        builder: Builder,
        extraBypassPackages: Collection<String> = emptyList(),
    ): Boolean {
        return when (splitTunnelMode) {
            AndroidSplitTunnelMode.AllApps -> {
                addDisallowedApp(builder, packageName, "PTRK-KVN")
                extraBypassPackages
                    .map { it.trim() }
                    .filter { it.isNotBlank() && it != packageName }
                    .distinct()
                    .forEach { addDisallowedApp(builder, it) }
                addLog("Split tunneling: all apps use TUN")
                true
            }

            AndroidSplitTunnelMode.ProxySelected -> {
                val packages = splitTunnelProxyApps
                    .filter { it.isNotBlank() && it != packageName }
                    .distinct()

                if (packages.isEmpty()) {
                    addLog("Split tunneling proxy list is empty")
                    setStatus(VpnStatus.Error("Select apps for split tunneling"))
                    updateNotification("Split tunneling error")
                    return false
                }

                val applied = packages.count { addAllowedApp(builder, it) }
                if (applied == 0) {
                    addLog("Split tunneling has no valid proxy apps")
                    setStatus(VpnStatus.Error("Selected apps are unavailable"))
                    updateNotification("Split tunneling error")
                    false
                } else {
                    addLog("Split tunneling: $applied selected apps use TUN")
                    true
                }
            }

            AndroidSplitTunnelMode.BypassSelected -> {
                addDisallowedApp(builder, packageName, "PTRK-KVN")
                val bypass = (
                    splitTunnelBypassApps + extraBypassPackages
                    )
                    .map { it.trim() }
                    .filter { it.isNotBlank() && it != packageName }
                    .distinct()
                val applied = bypass.count { addDisallowedApp(builder, it) }

                if (applied == 0) {
                    addLog("Split tunneling: no selected apps bypass TUN")
                } else {
                    addLog("Split tunneling: $applied selected apps bypass TUN")
                }
                true
            }
        }
    }

    private fun addAllowedApp(builder: Builder, targetPackage: String): Boolean {
        return runCatching {
            builder.addAllowedApplication(targetPackage)
            true
        }.getOrElse {
            addLog("Failed to route $targetPackage through TUN: ${it.message}")
            false
        }
    }

    private fun addDisallowedApp(
        builder: Builder,
        targetPackage: String,
        label: String = targetPackage
    ): Boolean {
        return runCatching {
            builder.addDisallowedApplication(targetPackage)
            true
        }.getOrElse {
            addLog("Failed to bypass $label from TUN: ${it.message}")
            false
        }
    }

    private fun writeTun2socksConfig(useMapDns: Boolean = true): File {
        val file = File(filesDir, TUN2SOCKS_CONFIG_FILE_NAME)
        // trimMargin (not trimIndent): trimIndent previously de-indented mapdns to column 0
        // and broke the YAML so hev ignored mapdns entirely.
        val mapDnsBlock = if (useMapDns) {
            """
            |
            |mapdns:
            |  address: $MAPDNS_ADDRESS
            |  port: 53
            |  network: $MAPDNS_NETWORK
            |  netmask: $MAPDNS_NETMASK
            |  cache-size: 10000
            """.trimMargin()
        } else {
            ""
        }

        val yaml = """
            |tunnel:
            |  name: tun0
            |  mtu: $TUN_MTU
            |  multi-queue: false
            |  ipv4: $TUN_IPV4_ADDRESS
            |  ipv6: $TUN_IPV6_ADDRESS
            |
            |socks5:
            |  address: ${socksConnectHost()}
            |  port: $socksListenPort
            |  udp: '${if (useMapDns) "tcp" else "udp"}'
            |  pipeline: false
            |  username: '$socksUsername'
            |  password: '$socksPassword'
            |$mapDnsBlock
            |misc:
            |  task-stack-size: $TUN_TASK_STACK_SIZE
            |  tcp-buffer-size: $TUN_TCP_BUFFER_SIZE
            |  max-session-count: 1200
            |  connect-timeout: 10000
            |  tcp-read-write-timeout: 300000
            |  udp-read-write-timeout: 60000
            |  log-file: stderr
            |  log-level: warn
            """.trimMargin()
        file.writeText(yaml)
        addLog("tun2socks config mapdns=$useMapDns bytes=${yaml.length}")
        return file
    }

    /** Android torrent clients — always bypass VPN (home IP), hev can't see PROCESS-NAME. */
    private fun torrentBypassPackages(): List<String> {
        val installed = runCatching {
            packageManager.getInstalledApplications(0).map { it.packageName }.toSet()
        }.getOrDefault(emptySet())
        val fromProfile = runCatching {
            File(filesDir, "mihomo/rules/torrent-clients.yaml")
                .takeIf { it.isFile }
                ?.readLines()
                .orEmpty()
                .mapNotNull { line ->
                    val m = Regex("""PROCESS-NAME,\s*([A-Za-z0-9_.]+)""").find(line) ?: return@mapNotNull null
                    m.groupValues[1].takeIf { it.contains('.') }
                }
        }.getOrDefault(emptyList())
        return (TORRENT_BYPASS_PACKAGES + fromProfile)
            .distinct()
            .filter { it in installed }
            .also { if (it.isNotEmpty()) addLog("Torrent bypass: ${it.joinToString()}") }
    }

    private fun startWatchdog() {
        watchdogJob?.cancel()
        watchdogTunStats = null
        watchdogStalledSamples = 0
        val mode = connectionMode
        val watchdogStartedAtMs = System.currentTimeMillis()
        watchdogJob = scope.launch {
            while (isActive && OlcboxVpnState.status.value is VpnStatus.Connected) {
                delay(WATCHDOG_INTERVAL_MS)
                // Mihomo sessions have no Mobile/hev — skip olcRTC/tun2socks checks entirely.
                if (mihomoTunActive) {
                    val upstream = findActiveUpstreamNetwork()
                    if (upstream == null) {
                        addLog("Watchdog: no upstream network")
                        requestTransportRecovery("No upstream network", fullRestart = false)
                        return@launch
                    }
                    if (currentNetwork != upstream) {
                        currentNetwork = upstream
                        currentNetworkTransport = upstream.transportOrNull()
                        runCatching { setUnderlyingNetworks(arrayOf(upstream)) }
                        pushUpstreamDnsToMihomo(upstream)
                        addLog("Watchdog: refreshed Mihomo upstream ${getNetName(upstream)}")
                    }
                    if (tun2socksThread?.isAlive != true) {
                        addLog("Watchdog: Mihomo tun2socks stopped")
                        requestTransportRecovery("Mihomo tun2socks stopped", fullRestart = true)
                        return@launch
                    }
                    if (!isLocalSocksPortOpen(socksListenPort)) {
                        addLog("Watchdog: Mihomo mixed-port unavailable")
                        requestTransportRecovery("Mihomo SOCKS unavailable", fullRestart = true)
                        return@launch
                    }
                    continue
                }

                when {
                    !isRtcRunning() -> {
                        addLog("Watchdog: olcRTC stopped")
                        requestTransportRecovery("olcRTC stopped", fullRestart = false)
                        return@launch
                    }

                    mode == AndroidConnectionMode.Tun && tun2socksThread?.isAlive != true -> {
                        addLog("Watchdog: tun2socks stopped")
                        requestTransportRecovery("tun2socks stopped", fullRestart = true)
                        return@launch
                    }

                    mode == AndroidConnectionMode.Proxy && !isLocalSocksPortOpen(socksListenPort) -> {
                        addLog("Watchdog: SOCKS port is not accepting connections")
                        requestTransportRecovery("SOCKS port unavailable", fullRestart = true)
                        return@launch
                    }
                }

                val upstream = findActiveUpstreamNetwork()
                if (upstream == null) {
                    addLog("Watchdog: no upstream network")
                    requestTransportRecovery("No upstream network", fullRestart = false)
                    return@launch
                }

                if (currentNetwork != upstream) {
                    val previousTransport = currentNetworkTransport
                    val nextTransport = upstream.transportOrNull()
                    updateUnderlyingNetwork(upstream)
                    if (isBenignWifiRefresh(previousTransport, nextTransport)) {
                        addLog("Watchdog: refreshed Wi-Fi upstream")
                        continue
                    }
                    addLog("Watchdog: upstream changed to ${getNetName(upstream)}")
                    requestTransportRecovery("Upstream network changed", fullRestart = false)
                    return@launch
                }

                val pastStallGrace =
                    System.currentTimeMillis() - watchdogStartedAtMs >= WATCHDOG_STALL_GRACE_MS
                if (pastStallGrace &&
                    mode == AndroidConnectionMode.Tun &&
                    !mihomoTunActive &&
                    isTunTrafficStalled()
                ) {
                    addLog("Watchdog: TUN traffic has no upstream response")
                    requestTransportRecovery("TUN traffic stalled", fullRestart = false)
                    return@launch
                }
            }
        }
    }

    private fun cleanup(stopService: Boolean = true) {
        // Second STOP while cleanup is running used to no-op — force another pass so the
        // system VPN notification actually disappears on the first user intent.
        if (cleanupJob?.isActive == true) {
            cleanupJob?.cancel()
            cleanupJob = null
        }

        // Sync tear-down first: MIUI keeps the shade VPN tile until the iface is gone,
        // and waiting only inside a coroutine made the first STOP look like a no-op.
        runCatching { stopOlcRtcRoutingRouter() }
        runCatching { stopMihomoTun() }
        runCatching { stopVisibleVpnProcessesBlocking() }

        val status = OlcboxVpnState.status.value
        if (status is VpnStatus.Disconnected &&
            vpnInterface == null &&
            tun2socksThread == null &&
            socksProxy == null &&
            !mihomoTunActive &&
            cleanupJob?.isActive != true
        ) {
            if (stopService) stopSelf()
            return
        }

        val cleanupGeneration = ++generation
        setStatus(VpnStatus.Stopping)
        startupJob?.cancel()
        watchdogJob?.cancel()
        networkLossJob?.cancel()
        recoveryJob?.cancel()
        recoveryJob = null
        releaseWakeLock()

        if (isCallbackRegistered) {
            runCatching { connectivityManager.unregisterNetworkCallback(networkCallback) }
            isCallbackRegistered = false
        }
        stopAuthenticatedSocksProxy()
        updateUnderlyingNetwork(null)
        unbindProcessFromNetwork()

        cleanupJob = scope.launch {
            try {
                stopMihomoTun()
                stopVisibleVpnProcesses()
                if (generation == cleanupGeneration) {
                    setStatus(VpnStatus.Disconnected)
                    addLog("${activeModeLabel()} stopped")
                }

                stopMobileAndWait()
                resetRecoveryState()
            } finally {
                if (stopService && generation == cleanupGeneration) {
                    stopSelf()
                }
            }
        }
    }

    private fun stopVisibleVpnProcessesBlocking() {
        runCatching { stopAuthenticatedSocksProxy() }
        runCatching { stopTun2socks() }
        runCatching { cleanupVpnInterface() }
        tun2socksThread?.interrupt()
        tun2socksThread = null
        unbindProcessFromNetwork()
    }

    private suspend fun stopVisibleVpnProcesses() {
        val tunThread = tun2socksThread
        stopAuthenticatedSocksProxy()
        stopTun2socks()
        cleanupVpnInterface()
        tunThread?.interrupt()
        waitForTun2socksStopped(tunThread)
        if (tun2socksThread == tunThread) {
            tun2socksThread = null
        }
        unbindProcessFromNetwork()
    }

    private suspend fun waitForTun2socksStopped(thread: Thread?) {
        if (thread == null) return
        val stopped = withTimeoutOrNull(TUN2SOCKS_STOP_WAIT_MS) {
            while (thread.isAlive) {
                delay(SOCKS_RELEASE_POLL_MS)
            }
            true
        } ?: false
        if (!stopped) {
            addLog("tun2socks cleanup is still pending")
        }
    }

    private suspend fun stopTransportProcesses(
        closeTun: Boolean,
        waitForSocksPort: Boolean = true,
        stopMobileBeforeTun: Boolean = false
    ) {
        val tunThread = tun2socksThread
        stopAuthenticatedSocksProxy()
        stopOlcRtcRoutingRouter()
        stopMihomoTun()
        if (stopMobileBeforeTun) {
            stopMobile()
        }
        stopTun2socks()
        if (closeTun) cleanupVpnInterface()
        tunThread?.interrupt()
        if (closeTun) {
            waitForTun2socksStopped(tunThread)
        }
        if (tun2socksThread == tunThread) {
            tun2socksThread = null
        }
        if (waitForSocksPort) {
            if (stopMobileBeforeTun) {
                waitForSocksPortReleased()
            } else {
                stopMobileAndWait()
            }
        } else if (!stopMobileBeforeTun) {
            stopMobile()
        }
        if (closeTun) {
            unbindProcessFromNetwork()
        }
    }

    private fun stopTun2socks() {
        if (nativeLibrariesLoaded && tun2socksStarted && !tun2socksStopRequested) {
            tun2socksStopRequested = true
            runCatching { stopTun2socksNative() }
        }
    }

    private fun stopMobile() {
        val runtime = olcRtcRuntime ?: return
        val provider = lastMobileProvider
        val room = lastMobileRoom
        val wasRunning = runtime.isRunning
        // Stop cancels synchronously. Provider teardown may finish later; it must not
        // hold the UI or prevent a new Runtime from joining another room.
        runCatching { runtime.stop(1L) }
        if (wasRunning) scope.launch { runCatching { runtime.stop(MOBILE_STOP_TIMEOUT_MS) } }
        if (wasRunning && provider == LocationConfig.PROVIDER_JITSI) {
            lastStoppedJitsiRoom = room
            lastJitsiStopCompletedAtMs = System.currentTimeMillis()
        }
    }

    private fun stopAuthenticatedSocksProxy() {
        socksProxy?.stop()
        socksProxy = null
    }

    private suspend fun stopMobileAndWait() {
        val socksPort = socksListenPort
        stopMobile()
        waitForSocksPortReleased(socksPort)
    }

    private suspend fun waitForSocksPortReleased(
        port: Int = socksListenPort,
        timeoutMs: Long = SOCKS_RELEASE_TIMEOUT_MS
    ) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (!isLocalSocksPortOpen(port)) return
            delay(SOCKS_RELEASE_POLL_MS)
        }
        addLog("SOCKS port $port is still busy after stop")
    }

    private fun isLocalSocksPortOpen(port: Int): Boolean {
        return runCatching {
            Socket().use { socket ->
                socket.connect(
                    InetSocketAddress(socksConnectHost(), port),
                    SOCKET_CONNECT_TIMEOUT_MS
                )
            }
        }.isSuccess
    }

    private fun socksConnectHost(): String {
        return AndroidSocksProxySettings.connectHost(socksListenHost)
    }

    private fun handleRtcLine(line: String) {
        val lowerLine = line.lowercase()

        if (lowerLine.contains("ice connection state changed: connected") ||
            lowerLine.contains("peer connection state changed: connected") ||
            lowerLine.contains("socks5 server listening")
        ) {
            markRtcConnected()
            return
        }

        if (lowerLine.contains("ice connection state changed: failed") ||
            lowerLine.contains("peer connection state changed: failed")
        ) {
            noteRtcFailure(
                reason = "RTC failed",
                fullRestart = shouldRecreateTunnelOnRtcLoss(),
                threshold = RTC_FAILED_RECOVERY_THRESHOLD
            )
            return
        }

        if (lowerLine.contains("ice connection state changed: closed") ||
            lowerLine.contains("peer connection state changed: closed")
        ) {
            noteRtcFailure(
                reason = "RTC closed",
                fullRestart = shouldRecreateTunnelOnRtcLoss(),
                threshold = RTC_CLOSED_RECOVERY_THRESHOLD
            )
            return
        }

        if (lowerLine.contains("network is unreachable") ||
            lowerLine.contains("use of closed network connection") ||
            lowerLine.contains("read/write on closed pipe")
        ) {
            noteRtcFailure(
                reason = "RTC network path is closed",
                fullRestart = false,
                threshold = RTC_IO_ERROR_RECOVERY_THRESHOLD
            )
        }
    }

    private fun markRtcConnected() {
        lastRtcConnectedAtMs = System.currentTimeMillis()
        lastRtcFailureAtMs = 0L
        rtcFailureCount = 0
    }

    private fun resetRtcHealthState() {
        lastRtcConnectedAtMs = System.currentTimeMillis()
        lastRtcFailureAtMs = 0L
        rtcFailureCount = 0
    }

    private fun noteRtcFailure(
        reason: String,
        fullRestart: Boolean,
        threshold: Int
    ) {
        if (OlcboxVpnState.status.value !is VpnStatus.Connected) return

        val now = System.currentTimeMillis()
        if (now - lastRtcConnectedAtMs < RTC_RECOVERY_GRACE_MS) return

        rtcFailureCount = if (now - lastRtcFailureAtMs <= RTC_FAILURE_WINDOW_MS) {
            rtcFailureCount + 1
        } else {
            1
        }
        lastRtcFailureAtMs = now

        if (rtcFailureCount >= threshold) {
            requestTransportRecovery(reason, fullRestart)
        }
    }

    private fun isTunTrafficStalled(): Boolean {
        val stats = readTun2SocksStats() ?: return false
        val previous = watchdogTunStats
        watchdogTunStats = stats

        if (previous == null) return false

        val txDelta = stats.txPackets - previous.txPackets
        val rxDelta = stats.rxPackets - previous.rxPackets
        if (txDelta >= WATCHDOG_STALLED_TX_PACKET_DELTA && rxDelta <= 0L && isRtcRunning()) {
            watchdogStalledSamples++
        } else if (rxDelta > 0L || txDelta <= 0L) {
            watchdogStalledSamples = 0
        }

        return watchdogStalledSamples >= WATCHDOG_STALLED_SAMPLE_LIMIT
    }

    private fun readTun2SocksStats(): Tun2SocksStats? {
        if (!nativeLibrariesLoaded || !tun2socksStarted) return null
        return runCatching {
            val values = getTun2socksStatsNative()
            if (values.size < 4) return null
            Tun2SocksStats(
                txPackets = values[0],
                txBytes = values[1],
                rxPackets = values[2],
                rxBytes = values[3]
            )
        }.getOrNull()
    }

    private fun requestTransportRecovery(
        reason: String,
        fullRestart: Boolean,
        delayMs: Long = 0L,
        setReconnectingImmediately: Boolean = true
    ) {
        val status = OlcboxVpnState.status.value
        if (status !is VpnStatus.Connected && status !is VpnStatus.Reconnecting) return

        val recoveryGeneration = generation
        if (delayMs <= 0L &&
            recoveryRequestedForGeneration == recoveryGeneration &&
            recoveryJob?.isActive == true
        ) {
            return
        }

        recoveryJob?.cancel()
        if (setReconnectingImmediately && status is VpnStatus.Connected) {
            setStatus(VpnStatus.Reconnecting)
            updateNotification(vpnNotifyReconnecting())
        }

        recoveryJob = scope.launch {
            if (delayMs > 0L) delay(delayMs)
            if (generation != recoveryGeneration) return@launch
            val currentStatus = OlcboxVpnState.status.value
            if (currentStatus !is VpnStatus.Connected && currentStatus !is VpnStatus.Reconnecting) {
                return@launch
            }

            recoveryRequestedForGeneration = recoveryGeneration
            if (setReconnectingImmediately && currentStatus is VpnStatus.Connected) {
                setStatus(VpnStatus.Reconnecting)
                updateNotification(vpnNotifyReconnecting())
            }

            addLog("$reason; reconnecting transport")
            recoveryJob = null
            startTunnel(isMigration = true, forceFullRestart = fullRestart)
        }
    }

    private fun refreshWakeLock(force: Boolean = false) {
        val lock = wakeLock ?: return
        val now = System.currentTimeMillis()
        if (!force &&
            lock.isHeld &&
            now - lastWakeLockRefreshAtMs < WAKE_LOCK_REFRESH_INTERVAL_MS
        ) {
            return
        }

        runCatching {
            lock.acquire(WAKE_LOCK_TIMEOUT_MS)
            lastWakeLockRefreshAtMs = now
        }.onFailure {
            Log.w(TAG, "Failed to refresh VPN wake lock", it)
        }
    }

    private fun releaseWakeLock() {
        runCatching {
            wakeLock?.let { if (it.isHeld) it.release() }
        }.onFailure {
            Log.w(TAG, "Failed to release VPN wake lock", it)
        }
        lastWakeLockRefreshAtMs = 0L
    }

    private fun scheduleTransportRetry(
        requestedGeneration: Long,
        reason: String,
        baseDelayMs: Long = RECONNECT_RETRY_BASE_DELAY_MS
    ) {
        val delayMs = nextReconnectRetryDelay(baseDelayMs)
        recoveryJob?.cancel()
        recoveryJob = scope.launch {
            addLog("Retrying transport after $reason in ${delayMs / 1_000}s")
            delay(delayMs)
            if (generation != requestedGeneration) return@launch
            if (OlcboxVpnState.status.value !is VpnStatus.Reconnecting) return@launch

            recoveryJob = null
            startTunnel(isMigration = true)
        }
    }

    private fun nextReconnectRetryDelay(baseDelayMs: Long): Long {
        val multiplier = 1L shl reconnectAttempt.coerceAtMost(MAX_RECONNECT_BACKOFF_POWER)
        reconnectAttempt++
        return (baseDelayMs * multiplier).coerceAtMost(RECONNECT_RETRY_MAX_DELAY_MS)
    }

    private fun resetRecoveryState() {
        recoveryRequestedForGeneration = 0L
        reconnectAttempt = 0
        recoveryJob?.cancel()
        recoveryJob = null
    }

    private fun shouldRecreateTunnelOnRtcLoss(): Boolean {
        return connectionMode == AndroidConnectionMode.Tun
    }

    private fun cleanupVpnInterface() {
        runCatching { vpnInterface?.close() }
        vpnInterface = null
    }

    private fun canReconnectTransportInPlace(): Boolean {
        return when (connectionMode) {
            AndroidConnectionMode.Tun -> vpnInterface != null && tun2socksThread?.isAlive == true
            AndroidConnectionMode.Proxy -> isRtcRunning()
        }
    }

    private fun shouldRestartForStartCommand(): Boolean {
        return when (OlcboxVpnState.status.value) {
            VpnStatus.Connected,
            VpnStatus.Connecting,
            VpnStatus.Reconnecting,
            VpnStatus.Stopping -> true
            VpnStatus.Disconnected,
            is VpnStatus.Error -> false
        } ||
            startupJob?.isActive == true ||
            cleanupJob?.isActive == true ||
            vpnInterface != null ||
            mihomoTunActive ||
            tun2socksThread != null ||
            socksProxy != null ||
            isRtcRunning()
    }

    private fun registerNetworkMonitor() {
        if (isCallbackRegistered) return
        try {
            val request = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                .build()
            connectivityManager.registerNetworkCallback(request, networkCallback)
            isCallbackRegistered = true
            addLog("Network monitor registered")
        } catch (e: Exception) {
            Log.e(TAG, "Network monitor failed", e)
        }
    }

    private fun findActiveUpstreamNetwork(): Network? {
        val active = connectivityManager.activeNetwork
        val candidates = connectivityManager.allNetworks.mapNotNull { network ->
            val caps = connectivityManager.getNetworkCapabilities(network) ?: return@mapNotNull null
            if (!caps.isUsableUpstream()) return@mapNotNull null
            network to UpstreamCandidate(
                isActive = network == active,
                isValidated = caps.isValidatedUpstream(),
                transport = caps.upstreamTransport()
            )
        }
        val selectedIndex = UpstreamNetworkSelector.selectIndex(candidates.map { it.second }) ?: return null
        return candidates[selectedIndex].first
    }

    private fun resolveOlcRtcDnsServer(configuredDnsServer: String): String {
        if (configuredDnsServer.isNotBlank()) {
            addLog("Using configured DNS server $configuredDnsServer for olcRTC signaling")
            return configuredDnsServer
        }

        val upstreamDnsServer = currentNetwork
            ?.let(connectivityManager::getLinkProperties)
            ?.dnsServers
            ?.asSequence()
            ?.filterNot { it.isAnyLocalAddress || it.isLoopbackAddress || it.isMulticastAddress }
            ?.sortedBy { it.address.size }
            ?.mapNotNull { it.hostAddress }
            ?.map(::dnsEndpoint)
            ?.firstOrNull()

        val selectedDnsServer = upstreamDnsServer ?: DEFAULT_OLCRTC_DNS_SERVER
        val source = if (upstreamDnsServer != null) "upstream" else "fallback"
        addLog("Using $source DNS server $selectedDnsServer for olcRTC signaling")
        return selectedDnsServer
    }

    private fun dnsEndpoint(address: String): String {
        return if (':' in address) "[$address]:53" else "$address:53"
    }

    private fun NetworkCapabilities.isUsableUpstream(): Boolean {
        return !hasTransport(NetworkCapabilities.TRANSPORT_VPN) &&
            hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private fun NetworkCapabilities.isValidatedUpstream(): Boolean {
        return isUsableUpstream() &&
            hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    private fun NetworkCapabilities.upstreamTransport(): UpstreamTransport {
        return when {
            hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> UpstreamTransport.Wifi
            hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> UpstreamTransport.Cellular
            else -> UpstreamTransport.Other
        }
    }

    private fun updateUnderlyingNetwork(network: Network?) {
        currentNetwork = network
        currentNetworkTransport = network?.transportOrNull()
        if (mihomoTunActive || connectionMode == AndroidConnectionMode.Tun || vpnInterface != null) {
            setUnderlyingNetworks(if (network != null) arrayOf(network) else null)
        }
        if (mihomoTunActive && network != null) {
            pushUpstreamDnsToMihomo(network)
        }
    }

    private fun Network.transportOrNull(): UpstreamTransport? {
        val caps = connectivityManager.getNetworkCapabilities(this) ?: return null
        if (!caps.isUsableUpstream()) return null
        return caps.upstreamTransport()
    }

    private fun isBenignWifiRefresh(
        previousTransport: UpstreamTransport?,
        nextTransport: UpstreamTransport?
    ): Boolean {
        return previousTransport == UpstreamTransport.Wifi &&
            nextTransport == UpstreamTransport.Wifi
    }

    private fun bindProcessToNetwork(network: Network?, successLog: String? = null) {
        try {
            connectivityManager.bindProcessToNetwork(network)
            if (successLog != null) addLog(successLog)
        } catch (e: Exception) {
            Log.w(TAG, "bindProcessToNetwork failed", e)
        }
    }

    private fun unbindProcessFromNetwork() {
        bindProcessToNetwork(null)
    }

    private fun getNetName(network: Network): String {
        val caps = connectivityManager.getNetworkCapabilities(network)
        return if (caps != null) getNetName(caps) else "Other"
    }

    private fun getNetName(caps: NetworkCapabilities): String = when {
        caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
        caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "Mobile"
        caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
        else -> "Other"
    }

    private fun shouldKeepProcessBound(network: Network): Boolean {
        val caps = connectivityManager.getNetworkCapabilities(network) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
    }

    private fun startForeground(statusText: String = vpnNotifyProtecting()) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "PTRK-KVN VPN",
                NotificationManager.IMPORTANCE_LOW
            )
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(channel)
        }

        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification(statusText),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            } else {
                0
            }
        )
    }

    private fun vpnRu(): Boolean =
        org.olcbox.app.i18n.AppLocale.current == org.olcbox.app.i18n.AppLanguage.Russian

    private fun vpnNotifyConnecting(): String =
        if (vpnRu()) "\u041f\u043e\u0434\u043a\u043b\u044e\u0447\u0435\u043d\u0438\u0435..." else "Connecting..."

    private fun vpnNotifyReconnecting(): String =
        if (vpnRu()) "\u041f\u0435\u0440\u0435\u043f\u043e\u0434\u043a\u043b\u044e\u0447\u0435\u043d\u0438\u0435..." else "Reconnecting..."

    private fun vpnNotifyStartingProxy(): String =
        if (vpnRu()) "\u0417\u0430\u043f\u0443\u0441\u043a \u043f\u0440\u043e\u043a\u0441\u0438..." else "Starting proxy..."

    private fun vpnNotifyProtecting(): String =
        if (vpnRu()) "VPN \u0430\u043a\u0442\u0438\u0432\u0435\u043d" else "Protecting your connection"

    private fun vpnNotifyWaitingNetwork(): String =
        if (vpnRu()) "\u041e\u0436\u0438\u0434\u0430\u043d\u0438\u0435 \u0441\u0435\u0442\u0438..." else "Waiting for network..."

    private fun vpnNotifyWaitingTransport(): String =
        if (vpnRu()) "\u041e\u0436\u0438\u0434\u0430\u043d\u0438\u0435 \u0442\u0440\u0430\u043d\u0441\u043f\u043e\u0440\u0442\u0430..." else "Waiting for transport..."

    private fun vpnNotifyFailed(): String =
        if (vpnRu()) "\u041e\u0448\u0438\u0431\u043a\u0430 \u043f\u043e\u0434\u043a\u043b\u044e\u0447\u0435\u043d\u0438\u044f" else "Connection failed"

    private fun vpnNotifyTunnelFailed(): String =
        if (vpnRu()) "\u041e\u0448\u0438\u0431\u043a\u0430 \u0442\u0443\u043d\u043d\u0435\u043b\u044f" else "Tunnel failed"

    private fun updateNotification(status: String) {
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .notify(NOTIFICATION_ID, buildNotification(status))
    }

    private fun buildNotification(status: String) =
        NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("PTRK-KVN")
            .setContentText(status)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setOngoing(true)
            .setContentIntent(getAppPendingIntent())
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                "Stop",
                PendingIntent.getService(
                    this,
                    0,
                    Intent(this, OlcboxVpnService::class.java).apply { action = ACTION_STOP_VPN },
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
            )
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

    private fun getAppPendingIntent(): PendingIntent {
        return PendingIntent.getActivity(
            this,
            0,
            packageManager.getLaunchIntentForPackage(packageName),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    private fun setStatus(status: VpnStatus) {
        OlcboxVpnState.setStatus(status)
    }

    private fun activeModeLabel(): String {
        return when (connectionMode) {
            AndroidConnectionMode.Tun -> "VPN"
            AndroidConnectionMode.Proxy -> "Proxy"
        }
    }

    private fun connectedNotificationText(): String = "${activeModeLabel()} Connected"

    private class AuthenticatedSocksProxy(
        private val listenPort: Int,
        private val backendPort: Int,
        private val username: String,
        private val password: String,
        private val log: (String) -> Unit
    ) {
        @Volatile
        private var stopped = false
        @Volatile
        private var serverSocket: ServerSocket? = null
        private var acceptThread: Thread? = null
        private val sockets = mutableSetOf<Socket>()

        val isRunning: Boolean
            get() = !stopped && serverSocket?.isClosed == false && acceptThread?.isAlive == true

        fun start() {
            stopped = false
            val server = ServerSocket().apply {
                reuseAddress = true
                bind(InetSocketAddress(AndroidSocksProxySettings.DEFAULT_HOST, listenPort))
            }
            serverSocket = server
            acceptThread = thread(name = "OlcboxSocksProxy", isDaemon = true) {
                acceptLoop(server)
            }
            log("SOCKS proxy listening on ${AndroidSocksProxySettings.DEFAULT_HOST}:$listenPort")
        }

        fun stop() {
            stopped = true
            runCatching { serverSocket?.close() }
            synchronized(sockets) {
                sockets.forEach { socket -> runCatching { socket.close() } }
                sockets.clear()
            }
            acceptThread?.interrupt()
            acceptThread = null
            serverSocket = null
        }

        private fun acceptLoop(server: ServerSocket) {
            while (!stopped) {
                val client = runCatching { server.accept() }
                    .onFailure { if (!stopped) log("SOCKS proxy accept failed: ${it.message}") }
                    .getOrNull() ?: continue

                synchronized(sockets) { sockets.add(client) }
                thread(name = "OlcboxSocksProxyClient", isDaemon = true) {
                    try {
                        handleClient(client)
                    } finally {
                        synchronized(sockets) { sockets.remove(client) }
                        runCatching { client.close() }
                    }
                }
            }
        }

        private fun handleClient(client: Socket) {
            val clientIn = DataInputStream(client.getInputStream())
            val clientOut = DataOutputStream(client.getOutputStream())
            if (!authenticate(clientIn, clientOut)) return

            Socket().use { backend ->
                backend.connect(
                    InetSocketAddress(AndroidSocksProxySettings.DEFAULT_HOST, backendPort),
                    SOCKET_CONNECT_TIMEOUT_MS
                )
                val backendIn = DataInputStream(backend.getInputStream())
                val backendOut = DataOutputStream(backend.getOutputStream())

                backendOut.write(byteArrayOf(SOCKS_VERSION, 0x01, SOCKS_METHOD_USERNAME_PASSWORD))
                backendOut.flush()

                if (backendIn.readUnsignedByte() != SOCKS_VERSION.toInt()) return
                if (backendIn.readUnsignedByte() != SOCKS_METHOD_USERNAME_PASSWORD.toInt()) return

                val userBytes = username.toByteArray()
                val passBytes = password.toByteArray()

                backendOut.write(SOCKS_AUTH_VERSION.toInt())
                backendOut.write(userBytes.size)
                backendOut.write(userBytes)
                backendOut.write(passBytes.size)
                backendOut.write(passBytes)
                backendOut.flush()

                if (backendIn.readUnsignedByte() != SOCKS_AUTH_VERSION.toInt()) return
                if (backendIn.readUnsignedByte() != 0x00) return // 0x00 - успешно

                val c2b = relay(client, backend, "client-to-backend")
                val b2c = relay(backend, client, "backend-to-client")
                c2b.join()
                runCatching { backend.close() }
                runCatching { client.close() }
                b2c.join(RELAY_JOIN_TIMEOUT_MS)
            }
        }

        private fun authenticate(input: DataInputStream, output: DataOutputStream): Boolean {
            if (input.readUnsignedByte() != SOCKS_VERSION.toInt()) return false
            val methodCount = input.readUnsignedByte()
            var supportsPassword = false
            repeat(methodCount) {
                if (input.readUnsignedByte() == SOCKS_METHOD_USERNAME_PASSWORD.toInt()) {
                    supportsPassword = true
                }
            }
            if (!supportsPassword) {
                output.write(byteArrayOf(SOCKS_VERSION, SOCKS_METHOD_NO_ACCEPTABLE))
                output.flush()
                return false
            }

            output.write(byteArrayOf(SOCKS_VERSION, SOCKS_METHOD_USERNAME_PASSWORD))
            output.flush()

            if (input.readUnsignedByte() != SOCKS_AUTH_VERSION.toInt()) return false
            val userBytes = ByteArray(input.readUnsignedByte())
            input.readFully(userBytes)
            val passwordBytes = ByteArray(input.readUnsignedByte())
            input.readFully(passwordBytes)

            val accepted = userBytes.decodeToString() == username &&
                passwordBytes.decodeToString() == password
            output.write(byteArrayOf(SOCKS_AUTH_VERSION, if (accepted) 0x00 else 0x01))
            output.flush()
            return accepted
        }

        private fun relay(from: Socket, to: Socket, name: String): Thread {
            return thread(name = "OlcboxSocksRelay-$name", isDaemon = true) {
                runCatching {
                    from.getInputStream().copyTo(to.getOutputStream(), RELAY_BUFFER_SIZE)
                }
                runCatching { to.shutdownOutput() }
                runCatching { from.shutdownInput() }
            }
        }

        private companion object {
            const val SOCKS_VERSION: Byte = 0x05
            const val SOCKS_AUTH_VERSION: Byte = 0x01
            const val SOCKS_METHOD_NO_AUTH: Byte = 0x00
            const val SOCKS_METHOD_USERNAME_PASSWORD: Byte = 0x02
            const val SOCKS_METHOD_NO_ACCEPTABLE: Byte = 0xFF.toByte()
            const val SOCKET_CONNECT_TIMEOUT_MS = 1_000
            const val RELAY_BUFFER_SIZE = 16 * 1024
            const val RELAY_JOIN_TIMEOUT_MS = 500L
        }
    }

    companion object {
        @Volatile
        private var nativeLibrariesLoaded = false
        private var nativeLibrariesLoadError: Throwable? = null
        private val nativeLibrariesLock = Any()

        private fun ensureNativeLibrariesLoaded(): Boolean {
            if (nativeLibrariesLoaded) return true
            nativeLibrariesLoadError?.let { return false }

            return synchronized(nativeLibrariesLock) {
                if (nativeLibrariesLoaded) {
                    true
                } else {
                    try {
                        System.loadLibrary("hev-socks5-tunnel")
                        System.loadLibrary("olcbox_tun2socks")
                        nativeLibrariesLoaded = true
                        true
                    } catch (e: UnsatisfiedLinkError) {
                        nativeLibrariesLoadError = e
                        Log.e(TAG, "Failed to load native tun2socks libraries", e)
                        false
                    }
                }
            }
        }

        const val ACTION_START_VPN = OlcboxVpnActions.ACTION_START_VPN
        const val ACTION_STOP_VPN = OlcboxVpnActions.ACTION_STOP_VPN

        private const val LOCAL_SOCKS_PORT_BASE = 10818
        private const val LOCAL_SOCKS_PORT_MAX = 10858
        private const val MOBILE_READY_TIMEOUT_MS = 60_000L
        private const val MOBILE_STOP_TIMEOUT_MS = 5_000L
        private const val PREVIOUS_STOP_WAIT_MS = 12_000L
        private const val JITSI_RESTART_SETTLE_MS = 2_000L
        private const val TUN2SOCKS_STOP_WAIT_MS = 1_000L
        private const val TUNNEL_HANDOFF_DELAY_MS = 300L
        private const val NETWORK_LOSS_GRACE_MS = 2_500L
        private const val NETWORK_STABILITY_GRACE_MS = 1_500L
        private const val WATCHDOG_INTERVAL_MS = 15_000L
        private const val WATCHDOG_STALLED_TX_PACKET_DELTA = 8L
        // Was 3 (~45s): aggressive recoveries left UI "Connected" with dead internet.
        private const val WATCHDOG_STALLED_SAMPLE_LIMIT = 10
        private const val WATCHDOG_STALL_GRACE_MS = 90_000L
        private const val RTC_RECOVERY_GRACE_MS = 2_500L
        private const val RTC_FAILURE_WINDOW_MS = 6_000L
        private const val RTC_FAILED_RECOVERY_THRESHOLD = 1
        private const val RTC_CLOSED_RECOVERY_THRESHOLD = 2
        private const val RTC_IO_ERROR_RECOVERY_THRESHOLD = 3
        private const val RECONNECT_RETRY_BASE_DELAY_MS = 4_000L
        private const val NETWORK_RETRY_BASE_DELAY_MS = 8_000L
        private const val RECONNECT_RETRY_MAX_DELAY_MS = 30_000L
        private const val MAX_RECONNECT_BACKOFF_POWER = 3
        private const val SOCKS_RELEASE_TIMEOUT_MS = 2_500L
        private const val SOCKS_RELEASE_QUICK_TIMEOUT_MS = 500L
        private const val SOCKS_RELEASE_POLL_MS = 100L
        private const val SOCKET_CONNECT_TIMEOUT_MS = 150
        private const val WAKE_LOCK_REFRESH_INTERVAL_MS = 30_000L
        private const val WAKE_LOCK_TIMEOUT_MS = 2 * 60 * 1000L
        private const val TUN_MTU = 1500
        private const val TUN_IPV4_ADDRESS = "10.0.88.88"
        private const val TUN_IPV6_ADDRESS = "fd00:88::88"
        private const val TUN_TCP_BUFFER_SIZE = 65_536
        private const val TUN_TASK_STACK_SIZE = 86_016
        private const val IPV4_PREFIX_LENGTH = 24
        private const val IPV6_PREFIX_LENGTH = 128
        private const val MIHOMO_MIXED_PORT = 7890
        private const val DEFAULT_OLCRTC_DNS_SERVER = "1.1.1.1:53"
        private const val MAPDNS_ADDRESS = "1.1.1.1"
        private const val MAPDNS_NETWORK = "100.64.0.0"
        private const val MAPDNS_NETMASK = "255.192.0.0"
        private val TORRENT_BYPASS_PACKAGES = listOf(
            "org.proninyaroslav.libretorrent",
            "hu.tagsoft.ttorrent.noads",
            "hu.tagsoft.ttorrent.lite",
            "com.utorrent.client",
            "com.utorrent.client.pro",
            "com.bittorrent.client",
            "com.bittorrent.client.pro",
            "com.mtorrent.client",
            "com.gray.pikatorrent",
            "com.yablio.torrentium",
            "in.gopalakrishnareddy.torrent",
            "intelligems.torrdroid",
            "org.jsp.wide",
            "mobi.zona",
            "com.houseoflife.bitlord",
            "connect.torrentpower",
            "com.premature.flud",
            "com.delphicoder.flud",
            "org.transdroid.lite",
            "org.transdroid.full",
            "com.frostwire.android",
            "com.digipom.easycoolermaster",
            "org.zbhd.torrent",
            "com.kevinforeman.nzb360",
            "org.freedownloadmanager.fdm",
            "com.dv.adm",
            "com.dv.adm.pl",
        )
        private const val NOTIFICATION_CHANNEL_ID = "olcbox_vpn"
        private const val NOTIFICATION_ID = 100
        private const val TAG = "OlcboxVpnService"

        private fun addLog(msg: String) {
            OlcboxVpnState.addLog(msg)
        }
    }
}
