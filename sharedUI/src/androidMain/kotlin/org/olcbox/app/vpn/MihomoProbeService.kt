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
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
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
            runCatching {
                val app = applicationContext
                MihomoAndroidContext.app = app
                val bootstrapDns = bindToUpstreamAndDns()
                Log.i(TAG, "probe upstream dns=${bootstrapDns.joinToString(",")}")
                val path = mihomoProfilePath(profileId)
                    ?: error("mihomo profile missing: $profileId")
                MihomoEngine.ensureInit(app)
                // One setup for the whole batch.
                MihomoEngine.setupProfile(
                    context = app,
                    yamlPath = path,
                    selectedMap = emptyMap(),
                    mode = mode,
                    bootstrapDns = bootstrapDns,
                    testUrl = MihomoEngine.DEFAULT_TEST_URL,
                )
                // setupConfig kicks provider health-checks; give the core a beat before
                // the first urlTest or the first leaf often returns -1.
                kotlinx.coroutines.delay(1_200)
                for ((index, proxyName) in proxies.withIndex()) {
                    if (index > 0) kotlinx.coroutines.delay(100)
                    var ms = runCatching {
                        MihomoEngine.urlTestResilient(proxyName).takeIf { it > 0L }
                    }.onFailure {
                        Log.e(TAG, "mihomo probe failed for $proxyName", it)
                    }.getOrNull()
                    // First leaf is most likely to race health-check / dialer warm-up.
                    if (ms == null && index == 0) {
                        kotlinx.coroutines.delay(800)
                        ms = runCatching {
                            MihomoEngine.urlTestResilient(proxyName).takeIf { it > 0L }
                        }.getOrNull()
                        Log.i(TAG, "probe retry $proxyName -> ${ms ?: -1}")
                    }
                    delays[proxyName] = ms ?: -1L
                    Log.i(TAG, "probe $proxyName -> ${ms ?: -1}")
                }
            }.onFailure {
                Log.e(TAG, "mihomo probe batch failed", it)
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
     * Bind `:mihomo` to Wi‑Fi/cellular (not VPN) and return that network's DNS.
     * T2/Tele2 often breaks probes that use only 8.8.8.8 / Google DoH for
     * resolving proxy hostnames while the full VPN path (with protect) works.
     */
    private fun bindToUpstreamAndDns(): List<String> {
        val cm = getSystemService(ConnectivityManager::class.java) ?: return emptyList()
        fun usable(network: Network): Boolean {
            val caps = cm.getNetworkCapabilities(network) ?: return false
            return !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        }
        val network = cm.activeNetwork?.takeIf(::usable)
            ?: cm.allNetworks.firstOrNull(::usable)
            ?: return emptyList()
        val bound = runCatching { cm.bindProcessToNetwork(network) }.getOrDefault(false)
        Log.i(TAG, "bindProcessToNetwork($network) → $bound")
        val dns = cm.getLinkProperties(network)
            ?.dnsServers
            .orEmpty()
            .mapNotNull { it.hostAddress?.trim()?.takeIf { addr -> addr.isNotBlank() } }
            .filter { ':' !in it } // IPv4
            .distinct()
        return dns
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
            // Resilient urlTest may try several URLs per leaf on cellular.
            val budget = timeoutMs.coerceAtLeast(12_000L + names.size * 12_000L)
            if (!latch.await(budget, TimeUnit.MILLISECONDS)) {
                Log.w(TAG, "mihomo probe batch timed out")
                return resultRef.get()
            }
            return resultRef.get()
        }
    }
}
