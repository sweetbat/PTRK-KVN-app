package org.olcbox.app.vpn

import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.ResultReceiver
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.olcbox.app.data.mihomo.MihomoAndroidContext
import org.olcbox.app.data.mihomo.mihomoProfilePath
import org.olcbox.app.mihomo.MihomoEngine
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Runs Mihomo urlTest in a dedicated process so libclash never shares
 * an address space with libgojni (dual Go runtimes crash the app).
 *
 * One process start can test many proxies — per-proxy cold starts were 20–30s each.
 */
class MihomoProbeService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        val receiver = if (android.os.Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(EXTRA_RECEIVER, ResultReceiver::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(EXTRA_RECEIVER)
        }
        val proxies = intent.getStringArrayListExtra(EXTRA_PROXIES).orEmpty()
            .ifEmpty {
                listOfNotNull(intent.getStringExtra(EXTRA_PROXY)?.takeIf { it.isNotBlank() })
            }
        val profileId = intent.getStringExtra(EXTRA_PROFILE).orEmpty()
        val mode = intent.getStringExtra(EXTRA_MODE)?.lowercase()
            ?.takeIf { it in setOf("rule", "global", "direct") }
            ?: "rule"

        scope.launch {
            val delays = LinkedHashMap<String, Long>()
            fun probeLog(msg: String) {
                Log.i(TAG, msg)
                runCatching {
                    org.olcbox.app.vpn.service.VpnStatusBridge.publishLog(applicationContext, "MihomoProbe: $msg")
                }
            }
            runCatching {
                val app = applicationContext
                MihomoAndroidContext.app = app
                val bootstrapDns = RuSafeDns.plainBootstrap(bindToUpstreamAndDns())
                probeLog("dns=${bootstrapDns.joinToString(",")} proxies=${proxies.size}")
                val path = mihomoProfilePath(profileId)
                    ?: error("mihomo profile missing: $profileId")
                MihomoEngine.ensureInit(app)
                val setup = MihomoEngine.setupProfile(
                    context = app,
                    yamlPath = path,
                    selectedMap = emptyMap(),
                    mode = mode,
                    bootstrapDns = bootstrapDns,
                    testUrl = MihomoEngine.DEFAULT_TEST_URL,
                )
                probeLog("setupConfig: ${setup.take(120)}")
                MihomoEngine.updateDns(bootstrapDns)
                // Same warm-up as the working post-connect path: open listeners before urlTest.
                // Without this, gRPC/VLESS leaves often return -1 while Hysteria still works.
                MihomoEngine.setVpnState(enabled = false)
                MihomoEngine.startListener()
                kotlinx.coroutines.delay(1_200)
                val known = MihomoEngine.parseLeafProxyNames(MihomoEngine.getProxiesJson()).toSet()
                val missing = proxies.filter { it !in known }
                if (missing.isNotEmpty()) {
                    probeLog("missing in core (${missing.size}): ${missing.take(3).joinToString()}")
                }
                // Low concurrency: gRPC HTTP/2 dials fight each other in a cold core.
                val gate = Semaphore(2)
                coroutineScope {
                    proxies.map { proxyName ->
                        async {
                            gate.withPermit {
                                var ms = runCatching {
                                    MihomoEngine.urlTestResilient(proxyName).takeIf { it > 0L }
                                }.onFailure {
                                    Log.e(TAG, "mihomo probe failed for $proxyName", it)
                                    probeLog("error $proxyName: ${it.message}")
                                }.getOrNull()
                                if (ms == null) {
                                    kotlinx.coroutines.delay(350)
                                    ms = runCatching {
                                        MihomoEngine.urlTestResilient(proxyName).takeIf { it > 0L }
                                    }.getOrNull()
                                    if (ms != null) probeLog("retry $proxyName -> $ms")
                                }
                                proxyName to (ms ?: -1L)
                            }
                        }
                    }.awaitAll().forEach { (name, ms) ->
                        delays[name] = ms
                        probeLog("$name -> $ms")
                    }
                }
                runCatching { MihomoEngine.stopListener() }
            }.onFailure {
                Log.e(TAG, "mihomo probe batch failed", it)
                runCatching {
                    org.olcbox.app.vpn.service.VpnStatusBridge.publishLog(
                        applicationContext,
                        "MihomoProbe: batch failed: ${it.message}",
                    )
                }
            }

            runCatching {
                getSystemService(ConnectivityManager::class.java)?.bindProcessToNetwork(null)
            }

            receiver?.send(
                RESULT_OK,
                Bundle().apply {
                    putStringArrayList(EXTRA_RESULT_NAMES, ArrayList(delays.keys))
                    putLongArray(EXTRA_RESULT_MS_ARRAY, delays.values.map { it }.toLongArray())
                    // Back-compat for single-proxy callers.
                    putLong(EXTRA_RESULT_MS, delays.values.firstOrNull() ?: -1L)
                },
            )
            stopSelf(startId)
            Handler(Looper.getMainLooper()).postDelayed({
                android.os.Process.killProcess(android.os.Process.myPid())
            }, 250)
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        runCatching {
            getSystemService(ConnectivityManager::class.java)?.bindProcessToNetwork(null)
        }
        super.onDestroy()
    }

    /**
     * Bind `:mihomo` to Wi‑Fi/cellular (not VPN) and return that network's DNS,
     * stripping Google/Cloudflare IPs hijacked by RKN TSPU on UDP/53.
     */
    private fun bindToUpstreamAndDns(): List<String> {
        val cm = getSystemService(ConnectivityManager::class.java) ?: return emptyList()
        fun usable(network: Network): Boolean {
            val caps = cm.getNetworkCapabilities(network) ?: return false
            return !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        }
        fun score(network: Network): Int {
            val caps = cm.getNetworkCapabilities(network) ?: return 0
            var s = 1
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) s += 4
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) s += 3
            if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED)) s += 1
            return s
        }
        val network = cm.activeNetwork?.takeIf(::usable)
            ?: cm.allNetworks.filter(::usable).maxByOrNull(::score)
            ?: return emptyList()
        val bound = runCatching { cm.bindProcessToNetwork(network) }.getOrDefault(false)
        Log.i(TAG, "bindProcessToNetwork($network) → $bound")
        val dns = cm.getLinkProperties(network)
            ?.dnsServers
            .orEmpty()
            .mapNotNull { it.hostAddress?.trim()?.takeIf { addr -> addr.isNotBlank() } }
            .distinct()
        val safe = RuSafeDns.sanitize(dns)
        val ipv4 = safe.filter { ':' !in it }
        val ipv6 = safe.filter { ':' in it }
        return ipv4 + ipv6
    }

    companion object {
        private const val TAG = "MihomoProbe"
        const val EXTRA_RECEIVER = "receiver"
        const val EXTRA_PROXY = "proxy"
        const val EXTRA_PROXIES = "proxies"
        const val EXTRA_PROFILE = "profile"
        const val EXTRA_MODE = "mode"
        const val EXTRA_RESULT_MS = "result_ms"
        const val EXTRA_RESULT_NAMES = "result_names"
        const val EXTRA_RESULT_MS_ARRAY = "result_ms_array"
        const val RESULT_OK = 0
        const val RESULT_ERROR = 1

        fun ping(
            context: Context,
            proxyName: String,
            profileId: String,
            mode: String,
            timeoutMs: Long = 12_000L,
        ): Long? {
            return pingMany(
                context = context,
                proxyNames = listOf(proxyName),
                profileId = profileId,
                mode = mode,
                timeoutMs = timeoutMs,
            )[proxyName]
        }

        fun pingMany(
            context: Context,
            proxyNames: List<String>,
            profileId: String,
            mode: String,
            timeoutMs: Long = 90_000L,
        ): Map<String, Long?> {
            val names = proxyNames.map { it.trim() }.filter { it.isNotBlank() }.distinct()
            if (names.isEmpty() || profileId.isBlank()) return emptyMap()
            val latch = CountDownLatch(1)
            val resultRef = AtomicReference<Map<String, Long?>>(emptyMap())
            val receiver = object : ResultReceiver(Handler(Looper.getMainLooper())) {
                override fun onReceiveResult(resultCode: Int, resultData: Bundle?) {
                    val keys = resultData?.getStringArrayList(EXTRA_RESULT_NAMES).orEmpty()
                    val values = resultData?.getLongArray(EXTRA_RESULT_MS_ARRAY)
                    val map = LinkedHashMap<String, Long?>()
                    keys.forEachIndexed { index, key ->
                        val raw = values?.getOrNull(index) ?: -1L
                        map[key] = raw.takeIf { it >= 0L }
                    }
                    resultRef.set(map)
                    latch.countDown()
                }
            }
            val intent = Intent(context, MihomoProbeService::class.java).apply {
                putExtra(EXTRA_RECEIVER, receiver)
                putStringArrayListExtra(EXTRA_PROXIES, ArrayList(names))
                putExtra(EXTRA_PROFILE, profileId)
                putExtra(EXTRA_MODE, mode)
            }
            runCatching { context.startService(intent) }.onFailure {
                Log.e(TAG, "failed to start mihomo probe", it)
                return emptyMap()
            }
            // urlTest may retry a couple of URLs per leaf on cellular.
            val budget = timeoutMs.coerceAtLeast(15_000L + names.size * 10_000L)
            if (!latch.await(budget, TimeUnit.MILLISECONDS)) {
                Log.w(TAG, "mihomo probe batch timed out")
                return resultRef.get()
            }
            return resultRef.get()
        }
    }
}
