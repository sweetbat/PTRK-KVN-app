package org.olcbox.app.vpn

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import android.os.Handler
import android.os.Looper
import org.olcbox.app.data.model.LocationConfig
import org.olcbox.app.data.datasource.LocationsDataSourceImpl
import org.olcbox.app.data.identity.PersistentDeviceIdentityProvider
import org.olcbox.app.data.mihomo.mihomoProfilePath
import org.olcbox.app.data.repository.SubscriptionFetchProxy
import org.olcbox.app.vpn.data.KEY_ANDROID_CONNECTION_MODE
import org.olcbox.app.vpn.data.KEY_ANDROID_DYNAMIC_THEME
import org.olcbox.app.vpn.data.KEY_ANDROID_SPLIT_TUNNEL_BYPASS_APPS
import org.olcbox.app.vpn.data.KEY_ANDROID_SPLIT_TUNNEL_MODE
import org.olcbox.app.vpn.data.KEY_ANDROID_SPLIT_TUNNEL_PROXY_APPS
import org.olcbox.app.vpn.data.KEY_ANDROID_SOCKS_HOST
import org.olcbox.app.vpn.data.KEY_ANDROID_SOCKS_PASSWORD
import org.olcbox.app.vpn.data.KEY_ANDROID_SOCKS_PORT
import org.olcbox.app.vpn.data.KEY_ANDROID_SOCKS_USERNAME
import org.olcbox.app.vpn.data.KEY_ANDROID_SOCKS_USERNAME_INITIALIZED
import org.olcbox.app.vpn.data.KEY_MIHOMO_MODE
import org.olcbox.app.vpn.data.MihomoModeStore
import org.olcbox.app.vpn.data.vpnPrefDataStore
import org.olcbox.app.vpn.service.OlcboxVpnActions
import kotlinx.coroutines.flow.first
import org.olcbox.app.vpn.service.OlcboxVpnState
import java.security.SecureRandom

class AndroidVpnManager(private val context: Context) : VpnManager {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val mihomoPingMutex = Mutex()
    /** profileId → (proxyName → waiters) */
    private val mihomoPingWaiters =
        mutableMapOf<String, MutableMap<String, MutableList<CompletableDeferred<Long?>>>>()
    private var mihomoPingFlushScheduled = false

    init {
        org.olcbox.app.vpn.service.VpnStatusBridge.ensureRegistered(appContext)
    }
    private val _connectionMode = MutableStateFlow(AndroidConnectionMode.Tun)
    private val _proxySettings = MutableStateFlow(AndroidSocksProxySettings())
    private val _splitTunnelSettings = MutableStateFlow(AndroidSplitTunnelSettings())
    private val _dynamicThemeEnabled = MutableStateFlow(true)
    private val _installedApps = MutableStateFlow<List<AndroidInstalledApp>>(emptyList())
    private val deviceIdentityProvider = PersistentDeviceIdentityProvider(
        LocationsDataSourceImpl(appContext)
    )

    // VpnService runs in `:vpn`; mirror status/logs via broadcast bridge.
    override val logs: StateFlow<List<String>> = org.olcbox.app.vpn.service.VpnStatusBridge.logs
    override val status: StateFlow<VpnStatus> = org.olcbox.app.vpn.service.VpnStatusBridge.status
    override val isConnected: StateFlow<Boolean> = org.olcbox.app.vpn.service.VpnStatusBridge.isConnected
    val connectionMode: StateFlow<AndroidConnectionMode> = _connectionMode.asStateFlow()
    val proxySettings: StateFlow<AndroidSocksProxySettings> = _proxySettings.asStateFlow()
    val splitTunnelSettings: StateFlow<AndroidSplitTunnelSettings> = _splitTunnelSettings.asStateFlow()
    val dynamicThemeEnabled: StateFlow<Boolean> = _dynamicThemeEnabled.asStateFlow()
    val installedApps: StateFlow<List<AndroidInstalledApp>> = _installedApps.asStateFlow()

    init {
        scope.launch {
            ensureProxySettings()
            appContext.vpnPrefDataStore.data
                .map { preferences ->
                    val mode = AndroidConnectionMode.fromValue(preferences[KEY_ANDROID_CONNECTION_MODE])
                    val proxy = AndroidSocksProxySettings(
                        host = AndroidSocksProxySettings.sanitizeHost(
                            preferences[KEY_ANDROID_SOCKS_HOST]
                        ),
                        port = AndroidSocksProxySettings.sanitizePort(
                            preferences[KEY_ANDROID_SOCKS_PORT]
                        ),
                        username = preferences[KEY_ANDROID_SOCKS_USERNAME].orEmpty(),
                        password = preferences[KEY_ANDROID_SOCKS_PASSWORD].orEmpty()
                    )
                    val splitTunnel = AndroidSplitTunnelSettings(
                        mode = AndroidSplitTunnelMode.fromValue(
                            preferences[KEY_ANDROID_SPLIT_TUNNEL_MODE]
                        ),
                        proxyPackages = preferences[KEY_ANDROID_SPLIT_TUNNEL_PROXY_APPS].orEmpty(),
                        bypassPackages = preferences[KEY_ANDROID_SPLIT_TUNNEL_BYPASS_APPS].orEmpty()
                    )
                    AndroidAppPreferences(
                        mode = mode,
                        proxy = proxy,
                        splitTunnel = splitTunnel,
                        dynamicThemeEnabled = preferences[KEY_ANDROID_DYNAMIC_THEME] != false
                    )
                }
                .collect { settings ->
                    _connectionMode.value = settings.mode
                    _proxySettings.value = settings.proxy
                    _splitTunnelSettings.value = settings.splitTunnel
                    _dynamicThemeEnabled.value = settings.dynamicThemeEnabled
                }
        }
        refreshInstalledApps()
    }

    override fun needsPermission(): Boolean = needsPermission(_connectionMode.value)

    fun needsPermission(mode: AndroidConnectionMode): Boolean {
        return mode == AndroidConnectionMode.Tun && VpnService.prepare(context) != null
    }

    fun selectConnectionMode(mode: AndroidConnectionMode) {
        _connectionMode.value = mode
        scope.launch {
            appContext.vpnPrefDataStore.edit { preferences ->
                preferences[KEY_ANDROID_CONNECTION_MODE] = mode.value
            }
        }
    }

    fun setDynamicThemeEnabled(enabled: Boolean) {
        _dynamicThemeEnabled.value = enabled
        scope.launch {
            appContext.vpnPrefDataStore.edit { preferences ->
                preferences[KEY_ANDROID_DYNAMIC_THEME] = enabled
            }
        }
    }

    fun updateProxySettings(
        host: String,
        username: String,
        password: String,
        port: Int = _proxySettings.value.port
    ) {
        val sanitizedHost = AndroidSocksProxySettings.sanitizeHost(host)
        val sanitizedUsername = username.trim().take(MAX_SOCKS_USERNAME_LENGTH)
            .ifBlank { generateProxyUsername() }
        val sanitized = password.trim().take(MAX_SOCKS_PASSWORD_LENGTH)
            .ifBlank { generateProxyPassword() }
        val sanitizedPort = AndroidSocksProxySettings.sanitizePort(port)
        _proxySettings.value = _proxySettings.value.copy(
            host = sanitizedHost,
            port = sanitizedPort,
            username = sanitizedUsername,
            password = sanitized
        )
        scope.launch {
            appContext.vpnPrefDataStore.edit { preferences ->
                preferences[KEY_ANDROID_SOCKS_HOST] = sanitizedHost
                preferences[KEY_ANDROID_SOCKS_PORT] = sanitizedPort
                preferences[KEY_ANDROID_SOCKS_USERNAME] = sanitizedUsername
                preferences[KEY_ANDROID_SOCKS_USERNAME_INITIALIZED] = true
                preferences[KEY_ANDROID_SOCKS_PASSWORD] = sanitized
            }
        }
    }

    fun updateProxyPassword(password: String) {
        updateProxySettings(
            host = _proxySettings.value.host,
            username = _proxySettings.value.username,
            password = password
        )
    }

    fun regenerateProxyPassword() {
        updateProxyPassword(generateProxyPassword())
    }

    fun refreshInstalledApps() {
        scope.launch {
            _installedApps.value = loadInstalledApps()
        }
    }

    fun selectSplitTunnelMode(mode: AndroidSplitTunnelMode) {
        _splitTunnelSettings.value = _splitTunnelSettings.value.copy(mode = mode)
        scope.launch {
            appContext.vpnPrefDataStore.edit { preferences ->
                preferences[KEY_ANDROID_SPLIT_TUNNEL_MODE] = mode.value
            }
        }
    }

    fun toggleSplitTunnelApp(list: AndroidSplitTunnelList, packageName: String) {
        val current = _splitTunnelSettings.value
        val next = when (list) {
            AndroidSplitTunnelList.Proxy -> {
                val packages = current.proxyPackages.toggle(packageName)
                current.copy(proxyPackages = packages)
            }

            AndroidSplitTunnelList.Bypass -> {
                val packages = current.bypassPackages.toggle(packageName)
                current.copy(bypassPackages = packages)
            }
        }

        updateSplitTunnelSettings(next)
    }

    fun setSplitTunnelApps(list: AndroidSplitTunnelList, packages: Set<String>) {
        val normalizedPackages = packages
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .toSet()
        val current = _splitTunnelSettings.value
        val next = when (list) {
            AndroidSplitTunnelList.Proxy -> current.copy(proxyPackages = normalizedPackages)
            AndroidSplitTunnelList.Bypass -> current.copy(bypassPackages = normalizedPackages)
        }

        updateSplitTunnelSettings(next)
    }

    override fun startVpn() {
        org.olcbox.app.vpn.service.VpnStatusBridge.markConnecting()
        prepareActiveEngine()
        val intent = buildStartIntent()
        launchVpnService(intent)
        // Only retry if :vpn never acked — do not interrupt an in-flight handshake.
        mainHandler.postDelayed({
            val bridge = org.olcbox.app.vpn.service.VpnStatusBridge
            if (!bridge.serviceAcked && bridge.status.value is VpnStatus.Connecting) {
                android.util.Log.w("AndroidVpnManager", "retry START_VPN (no :vpn ack)")
                launchVpnService(intent)
            }
        }, 1_500)
        mainHandler.postDelayed({
            val bridge = org.olcbox.app.vpn.service.VpnStatusBridge
            if (!bridge.serviceAcked && bridge.status.value is VpnStatus.Connecting) {
                android.util.Log.w("AndroidVpnManager", "second retry START_VPN")
                launchVpnService(intent)
            }
        }, 4_000)
    }

    override fun prepareActiveEngine() {
        recycleVpnProcessIfNeeded()
        when (readActiveEngine()) {
            "mihomo" -> {
                killEngineProcess(":olcrtc")
                killEngineProcess(":route")
            }
            "olcrtc" -> {
                killEngineProcess(":mihomo")
                // `:route` is started later by VPN when routing is ON.
                killEngineProcess(":route")
            }
        }
    }

    private fun recycleVpnProcessIfNeeded() {
        val gojni = java.io.File(appContext.filesDir, "vpn_gojni_loaded").exists()
        val clash = java.io.File(appContext.filesDir, "vpn_clash_loaded").exists()
        val activeEngine = readActiveEngine()
        val needKill = when (activeEngine) {
            // Starting Mihomo after olcRTC loaded gojni into :vpn
            "mihomo" -> gojni
            // Starting olcRTC after Mihomo loaded libclash into :vpn
            "olcrtc" -> clash
            else -> false
        }
        if (!needKill) return
        val am = appContext.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        val vpnPid = am.runningAppProcesses
            ?.firstOrNull { it.processName == "${appContext.packageName}:vpn" }
            ?.pid
        if (vpnPid != null && vpnPid > 0) {
            android.util.Log.w(
                "AndroidVpnManager",
                "killing :vpn pid=$vpnPid for engine switch → $activeEngine"
            )
            runCatching { android.os.Process.killProcess(vpnPid) }
            try {
                Thread.sleep(500)
            } catch (_: InterruptedException) {
            }
        }
        runCatching { java.io.File(appContext.filesDir, "vpn_gojni_loaded").delete() }
        runCatching { java.io.File(appContext.filesDir, "vpn_clash_loaded").delete() }
    }

    private fun readActiveEngine(): String {
        return runCatching {
            val active = java.io.File(appContext.filesDir, "active_location.json")
                .takeIf { it.exists() }
                ?.readText()
                .orEmpty()
            when {
                active.contains("\"engine\":\"mihomo\"", ignoreCase = true) ||
                    active.contains("\"mihomo_proxy\"", ignoreCase = true) -> "mihomo"
                active.contains("\"engine\":\"olcrtc\"", ignoreCase = true) ||
                    active.contains("olcrtc://", ignoreCase = true) ||
                    active.contains("\"auth_provider\"", ignoreCase = true) -> "olcrtc"
                else -> {
                    // Fallback: active_location_id from bundle
                    val bundle = java.io.File(appContext.filesDir, "locations_v4.json")
                        .takeIf { it.exists() }
                        ?.readText()
                        .orEmpty()
                    val idMatch = Regex(""""active_location_id"\s*:\s*"([^"]+)"""")
                        .find(bundle)
                        ?.groupValues
                        ?.getOrNull(1)
                    if (idMatch != null) {
                        val idx = bundle.indexOf("\"storage_id\": \"$idMatch\"")
                            .takeIf { it >= 0 }
                            ?: bundle.indexOf("\"storage_id\":\"$idMatch\"")
                        if (idx >= 0) {
                            val slice = bundle.substring(idx, (idx + 800).coerceAtMost(bundle.length))
                            when {
                                slice.contains("\"engine\": \"mihomo\"") ||
                                    slice.contains("\"engine\":\"mihomo\"") ||
                                    slice.contains("\"mihomo_proxy\"") -> "mihomo"
                                else -> "olcrtc"
                            }
                        } else "olcrtc"
                    } else "olcrtc"
                }
            }
        }.getOrDefault("olcrtc")
    }

    private fun buildStartIntent(): Intent = Intent().apply {
        setClassName(context.packageName, OlcboxVpnActions.SERVICE_CLASS_NAME)
        action = OlcboxVpnActions.ACTION_START_VPN
        putExtra(OlcboxVpnActions.EXTRA_CONNECTION_MODE, _connectionMode.value.value)
        putExtra(OlcboxVpnActions.EXTRA_SOCKS_HOST, _proxySettings.value.host)
        putExtra(OlcboxVpnActions.EXTRA_SOCKS_PORT, _proxySettings.value.port)
        putExtra(OlcboxVpnActions.EXTRA_SOCKS_USERNAME, _proxySettings.value.username)
        putExtra(OlcboxVpnActions.EXTRA_SOCKS_PASSWORD, _proxySettings.value.password)
        putExtra(OlcboxVpnActions.EXTRA_SPLIT_TUNNEL_MODE, _splitTunnelSettings.value.mode.value)
        putStringArrayListExtra(
            OlcboxVpnActions.EXTRA_SPLIT_TUNNEL_PROXY_APPS,
            ArrayList(_splitTunnelSettings.value.proxyPackages)
        )
        putStringArrayListExtra(
            OlcboxVpnActions.EXTRA_SPLIT_TUNNEL_BYPASS_APPS,
            ArrayList(_splitTunnelSettings.value.bypassPackages)
        )
        putExtra(
            OlcboxVpnActions.EXTRA_MIHOMO_MODE,
            runCatching {
                kotlinx.coroutines.runBlocking {
                    MihomoModeStore.resolve(
                        context = appContext,
                        dataStoreValue = appContext.vpnPrefDataStore.data.first()[KEY_MIHOMO_MODE],
                    )
                }
            }.getOrElse { MihomoModeStore.read(appContext) },
        )
    }

    private fun launchVpnService(intent: Intent) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ContextCompat.startForegroundService(context, intent)
            } else {
                context.startService(intent)
            }
        } catch (error: Throwable) {
            android.util.Log.e("AndroidVpnManager", "startForegroundService failed", error)
            runCatching { context.startService(intent) }.onFailure {
                org.olcbox.app.vpn.service.VpnStatusBridge.markDisconnected()
            }
        }
    }

    override fun stopVpn() {
        org.olcbox.app.vpn.service.VpnStatusBridge.markStopping()
        val intent = Intent().apply {
            setClassName(context.packageName, OlcboxVpnActions.SERVICE_CLASS_NAME)
            action = OlcboxVpnActions.ACTION_STOP_VPN
        }
        runCatching { context.startService(intent) }
            .onFailure { android.util.Log.e("AndroidVpnManager", "stopVpn startService failed", it) }
        // Second kick — first STOP is sometimes ignored while :vpn is mid-start.
        mainHandler.postDelayed({
            if (status.value is VpnStatus.Stopping || status.value is VpnStatus.Connected) {
                runCatching { context.startService(intent) }
            }
        }, 600)
    }

    override suspend fun ping(locationConfig: LocationConfig): Long? {
        if (locationConfig.isMihomo()) {
            killEngineProcess(":olcrtc")
            killEngineProcess(":route")
            return pingMihomo(locationConfig)
        }
        killEngineProcess(":mihomo")
        killEngineProcess(":route")
        return OlcRtcConnectionChecker.ping(
            locationConfig = locationConfig,
            deviceId = deviceIdentityProvider.hwid()
        )
    }

    override suspend fun checkConnection(locationConfig: LocationConfig): Long? {
        if (locationConfig.isMihomo()) {
            killEngineProcess(":olcrtc")
            killEngineProcess(":route")
            return pingMihomo(locationConfig)
        }
        killEngineProcess(":mihomo")
        killEngineProcess(":route")
        return OlcRtcConnectionChecker.check(
            locationConfig = locationConfig,
            deviceId = deviceIdentityProvider.hwid()
        )
    }

    private fun killEngineProcess(suffix: String) {
        val am = appContext.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        val pid = am.runningAppProcesses
            ?.firstOrNull { it.processName == "${appContext.packageName}$suffix" }
            ?.pid
            ?: return
        if (pid > 0) {
            runCatching { android.os.Process.killProcess(pid) }
        }
    }

    private suspend fun pingMihomo(locationConfig: LocationConfig): Long? {
        val proxyName = locationConfig.id.trim()
        val profileId = locationConfig.mihomoProfileId.trim()
        if (proxyName.isBlank() || profileId.isBlank()) return null
        if (mihomoProfilePath(profileId) == null) return null

        val deferred = CompletableDeferred<Long?>()
        mihomoPingMutex.withLock {
            mihomoPingWaiters
                .getOrPut(profileId) { mutableMapOf() }
                .getOrPut(proxyName) { mutableListOf() }
                .add(deferred)
            if (!mihomoPingFlushScheduled) {
                mihomoPingFlushScheduled = true
                scope.launch {
                    // Let regular + bypass nodes all register before one cold probe.
                    var last = -1
                    var stableRounds = 0
                    val deadline = System.currentTimeMillis() + 2_000L
                    while (System.currentTimeMillis() < deadline) {
                        delay(150)
                        val count = mihomoPingMutex.withLock {
                            mihomoPingWaiters.values.sumOf { it.size }
                        }
                        if (count == last && count > 0) {
                            stableRounds++
                            if (stableRounds >= 2) break
                        } else {
                            stableRounds = 0
                            last = count
                        }
                    }
                    flushMihomoPingBatches()
                }
            }
        }
        return deferred.await()
    }

    private suspend fun flushMihomoPingBatches() {
        val snapshot = mihomoPingMutex.withLock {
            mihomoPingFlushScheduled = false
            val copy = mihomoPingWaiters.mapValues { (_, byProxy) ->
                byProxy.mapValues { it.value.toList() }
            }
            mihomoPingWaiters.clear()
            copy
        }
        if (snapshot.isEmpty()) return
        val mode = MihomoModeStore.resolve(
            context = appContext,
            dataStoreValue = appContext.vpnPrefDataStore.data.first()[KEY_MIHOMO_MODE],
        )
        for ((profileId, waiters) in snapshot) {
            if (waiters.isEmpty()) continue
            val results = withContext(Dispatchers.IO) {
                runCatching {
                    org.olcbox.app.vpn.service.VpnStatusBridge.publishLog(
                        appContext,
                        "Mihomo ping: ${waiters.size} node(s), profile=$profileId, mode=$mode",
                    )
                    MihomoProbeService.pingMany(
                        context = appContext,
                        proxyNames = waiters.keys.toList(),
                        profileId = profileId,
                        mode = mode,
                        onPartial = { name, value ->
                            waiters[name]?.forEach { deferred ->
                                deferred.complete(value)
                            }
                        },
                    )
                }.onFailure {
                    android.util.Log.w("AndroidVpnManager", "mihomo batch ping failed: ${it.message}")
                    org.olcbox.app.vpn.service.VpnStatusBridge.publishLog(
                        appContext,
                        "Mihomo ping failed: ${it.message}",
                    )
                }.getOrDefault(emptyMap())
            }
            val ok = results.count { (_, v) -> v != null && v > 0L }
            org.olcbox.app.vpn.service.VpnStatusBridge.publishLog(
                appContext,
                "Mihomo ping done: $ok/${waiters.size} online (profile=$profileId)",
            )
            waiters.forEach { (name, deferreds) ->
                val value = results[name]
                deferreds.forEach { it.complete(value) }
            }
        }
    }

    override fun mihomoMode(): String {
        return runCatching {
            kotlinx.coroutines.runBlocking {
                MihomoModeStore.resolve(
                    context = appContext,
                    dataStoreValue = appContext.vpnPrefDataStore.data.first()[KEY_MIHOMO_MODE],
                )
            }
        }.getOrElse { MihomoModeStore.read(appContext) }
    }

    override fun mihomoModeFlow(): kotlinx.coroutines.flow.Flow<String> =
        appContext.vpnPrefDataStore.data.map { prefs ->
            MihomoModeStore.resolve(
                context = appContext,
                dataStoreValue = prefs[KEY_MIHOMO_MODE],
            )
        }

    override suspend fun setMihomoMode(mode: String) {
        val normalized = MihomoModeStore.normalize(mode)
        // File first so `:vpn` never starts with a stale DataStore `rule`.
        MihomoModeStore.write(appContext, normalized)
        appContext.vpnPrefDataStore.edit { it[KEY_MIHOMO_MODE] = normalized }
    }

    override fun subscriptionFetchProxy(): SubscriptionFetchProxy? {
        val currentStatus = status.value
        if (currentStatus !is VpnStatus.Connected &&
            currentStatus !is VpnStatus.Reconnecting
        ) {
            return null
        }

        // UI process bypasses VpnService TUN — must hit the local engine SOCKS.
        // Mihomo: hev dials Clash mixed-port 7890 (no auth).
        // olcRTC: Mobile SOCKS on the configured listen port (with auth).
        return when (readActiveEngine()) {
            "mihomo" -> SubscriptionFetchProxy(
                host = "127.0.0.1",
                port = 7890,
            )
            else -> {
                val proxy = _proxySettings.value
                SubscriptionFetchProxy(
                    host = AndroidSocksProxySettings.connectHost(proxy.host),
                    port = proxy.port,
                    username = proxy.username,
                    password = proxy.password,
                )
            }
        }
    }

    private suspend fun ensureProxySettings() {
        appContext.vpnPrefDataStore.edit { preferences ->
            val username = preferences[KEY_ANDROID_SOCKS_USERNAME]
            val usernameInitialized = preferences[KEY_ANDROID_SOCKS_USERNAME_INITIALIZED] == true
            if (username.isNullOrBlank() || (!usernameInitialized && username == LEGACY_DEFAULT_USERNAME)) {
                preferences[KEY_ANDROID_SOCKS_USERNAME] = generateProxyUsername()
            }
            preferences[KEY_ANDROID_SOCKS_USERNAME_INITIALIZED] = true
            if (preferences[KEY_ANDROID_SOCKS_PASSWORD].isNullOrBlank()) {
                preferences[KEY_ANDROID_SOCKS_PASSWORD] = generateProxyPassword()
            }
            preferences[KEY_ANDROID_SOCKS_HOST] = AndroidSocksProxySettings.sanitizeHost(
                preferences[KEY_ANDROID_SOCKS_HOST]
            )
            preferences[KEY_ANDROID_SOCKS_PORT] = AndroidSocksProxySettings.sanitizePort(
                preferences[KEY_ANDROID_SOCKS_PORT]
            )
        }
    }

    private suspend fun loadInstalledApps(): List<AndroidInstalledApp> = withContext(Dispatchers.IO) {
        val packageManager = appContext.packageManager
        val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val resolveInfos = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.queryIntentActivities(
                launcherIntent,
                PackageManager.ResolveInfoFlags.of(0)
            )
        } else {
            @Suppress("DEPRECATION")
            packageManager.queryIntentActivities(launcherIntent, 0)
        }
        val launcherApps = resolveInfos
            .mapNotNull { it.activityInfo?.applicationInfo }

        val installedApps = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.getInstalledApplications(
                PackageManager.ApplicationInfoFlags.of(0)
            )
        } else {
            @Suppress("DEPRECATION")
            packageManager.getInstalledApplications(0)
        }

        (launcherApps + installedApps)
            .filter { it.packageName != appContext.packageName }
            .distinctBy { it.packageName }
            .map { appInfo ->
                AndroidInstalledApp(
                    packageName = appInfo.packageName,
                    label = appInfo.loadLabel(packageManager).toString(),
                    isSystem = appInfo.isSystemApp()
                )
            }
            .sortedWith(compareBy<AndroidInstalledApp> { it.label.lowercase() }.thenBy { it.packageName })
    }

    private fun ApplicationInfo.isSystemApp(): Boolean {
        val systemFlags = ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP
        return flags and systemFlags != 0
    }

    private fun generateProxyPassword(): String {
        return buildString(PROXY_PASSWORD_LENGTH) {
            repeat(PROXY_PASSWORD_LENGTH) {
                append(PROXY_PASSWORD_ALPHABET[random.nextInt(PROXY_PASSWORD_ALPHABET.length)])
            }
        }
    }

    private fun generateProxyUsername(): String {
        return buildString(PROXY_USERNAME_PREFIX.length + PROXY_USERNAME_RANDOM_LENGTH) {
            append(PROXY_USERNAME_PREFIX)
            repeat(PROXY_USERNAME_RANDOM_LENGTH) {
                append(PROXY_USERNAME_ALPHABET[random.nextInt(PROXY_USERNAME_ALPHABET.length)])
            }
        }
    }

    private fun Set<String>.toggle(value: String): Set<String> {
        return if (value in this) this - value else this + value
    }

    private fun updateSplitTunnelSettings(settings: AndroidSplitTunnelSettings) {
        _splitTunnelSettings.value = settings
        scope.launch {
            appContext.vpnPrefDataStore.edit { preferences ->
                preferences[KEY_ANDROID_SPLIT_TUNNEL_PROXY_APPS] = settings.proxyPackages
                preferences[KEY_ANDROID_SPLIT_TUNNEL_BYPASS_APPS] = settings.bypassPackages
            }
        }
    }

    private data class AndroidAppPreferences(
        val mode: AndroidConnectionMode,
        val proxy: AndroidSocksProxySettings,
        val splitTunnel: AndroidSplitTunnelSettings,
        val dynamicThemeEnabled: Boolean
    )

    private companion object {
        const val LEGACY_DEFAULT_USERNAME = "olcbox"
        const val PROXY_USERNAME_PREFIX = "ptrkkvn"
        const val PROXY_USERNAME_RANDOM_LENGTH = 8
        const val MAX_SOCKS_USERNAME_LENGTH = 64
        const val PROXY_PASSWORD_LENGTH = 24
        const val MAX_SOCKS_PASSWORD_LENGTH = 64
        const val PROXY_USERNAME_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789"
        const val PROXY_PASSWORD_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789"
        const val DEFAULT_LOCATION_PING_PARALLELISM = 4
        val random = SecureRandom()
    }
}
