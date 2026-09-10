package org.olcbox.app.mihomo

import android.content.Context
import android.util.Log
import com.follow.clashx.core.Core
import com.follow.clashx.core.InvokeInterface
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

/**
 * Thin Kotlin façade over FlClashX/PTRK libclash (libcore.so + libclash.so).
 * Used only for Remnawave/Clash YAML profiles — olcRTC stays on Mobile/hev.
 */
object MihomoEngine {
    private const val TAG = "MihomoEngine"
    private val lock = Mutex()
    @Volatile private var homeDir: String? = null
    @Volatile private var initialized = false
    @Volatile private var mode: String = "rule"

    suspend fun ensureInit(context: Context) {
        lock.withLock {
            if (initialized) return@withLock
            val dir = File(context.filesDir, "mihomo").apply { mkdirs() }
            ensureGeoFiles(context, dir)
            homeDir = dir.absolutePath
            val init = JSONObject()
                .put("home-dir", dir.absolutePath)
                .put("version", 1)
                .toString()
            val ok = invoke("initClash", init)
            initialized = ok == "true" || ok == "\"true\"" || ok.contains("true")
            if (!initialized) {
                Log.w(TAG, "initClash returned: $ok")
                initialized = true
            }
            Log.i(TAG, "init home=${dir.absolutePath} ok=$initialized")
        }
    }

    fun profilesDir(context: Context): File =
        File(context.filesDir, "mihomo/profiles").apply { mkdirs() }

    suspend fun setupProfile(
        context: Context,
        yamlPath: String,
        selectedMap: Map<String, String> = emptyMap(),
        mode: String = this.mode,
    ): String = lock.withLock {
        ensureInitUnlocked(context)
        this.mode = mode
        val selected = JSONObject()
        selectedMap.forEach { (k, v) -> selected.put(k, v) }
        val overrides = JSONObject()
            .put("mode", mode)
            .put("ipv6", false)
            .put("tun", JSONObject().put("enable", false)) // we attach fd ourselves
        val params = JSONObject()
            .put("config-path", yamlPath)
            .put("overrides", overrides)
            .put("home-dir", homeDir)
            .put("selected-map", selected)
            .put("test-url", "https://www.gstatic.com/generate_204")
            .toString()
        invoke("setupConfig", params)
    }

    suspend fun setMode(mode: String): String {
        this.mode = mode
        val params = JSONObject().put("mode", mode).toString()
        return invoke("updateConfig", params)
    }

    suspend fun changeProxy(groupName: String, proxyName: String): String {
        val params = JSONObject()
            .put("group-name", groupName)
            .put("proxy-name", proxyName)
            .toString()
        return invoke("changeProxy", params)
    }

    suspend fun getProxiesJson(): String = invoke("getProxies", "")

    suspend fun urlTest(proxyName: String, testUrl: String = "https://www.gstatic.com/generate_204"): Long {
        val params = JSONObject()
            .put("proxy-name", proxyName)
            .put("test-url", testUrl)
            .put("timeout", 5000)
            .toString()
        val raw = invoke("asyncTestDelay", params)
        return raw.toLongOrNull()
            ?: runCatching { JSONObject(raw).optLong("delay", -1L) }.getOrDefault(-1L)
    }

    fun startTun(
        fd: Int,
        protect: (Int) -> Boolean,
    ): Boolean {
        return Core.startTun(
            fd = fd,
            protect = protect,
            resolverProcess = { _, _, _, _ -> "" },
        )
    }

    fun stopTun() {
        runCatching { Core.stopTun() }
    }

    fun startListener() = runCatching { Core.startListener() }
    fun stopListener() = runCatching { Core.stopListener() }

    fun traffic(): String = runCatching { Core.getTraffic() }.getOrDefault("{}")
    fun runTime(): String = runCatching { Core.getRunTime() }.getOrDefault("0")
    fun vpnOptionsJson(): String = runCatching { Core.getAndroidVpnOptions() }.getOrDefault("")

    /** Leaf proxy names from getProxies payload (best-effort). */
    fun parseLeafProxyNames(proxiesJson: String): List<String> {
        return try {
            val root = JSONObject(proxiesJson)
            val names = mutableListOf<String>()
            val keys = root.keys()
            while (keys.hasNext()) {
                val name = keys.next()
                val obj = root.optJSONObject(name) ?: continue
                val type = obj.optString("type").lowercase()
                if (type in setOf(
                        "selector", "urltest", "fallback", "loadbalance", "relay",
                        "direct", "reject", "compatible", "pass",
                    )
                ) {
                    continue
                }
                if (name.equals("GLOBAL", true) ||
                    name.equals("DIRECT", true) ||
                    name.equals("REJECT", true) ||
                    name.startsWith("REJECT", ignoreCase = true) ||
                    name.startsWith("PASS", ignoreCase = true)
                ) {
                    continue
                }
                names += name
            }
            names.sorted()
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun parseSelectorGroups(proxiesJson: String): List<Pair<String, List<String>>> {
        return try {
            val root = JSONObject(proxiesJson)
            val out = mutableListOf<Pair<String, List<String>>>()
            val keys = root.keys()
            while (keys.hasNext()) {
                val name = keys.next()
                val obj = root.optJSONObject(name) ?: continue
                val type = obj.optString("type").lowercase()
                if (type != "selector" && type != "urltest") continue
                val all = obj.optJSONArray("all") ?: JSONArray()
                val members = buildList {
                    for (i in 0 until all.length()) add(all.optString(i))
                }.filter { it.isNotBlank() && !it.equals("DIRECT", true) && !it.equals("REJECT", true) }
                if (members.isNotEmpty()) out += name to members
            }
            out
        } catch (_: Exception) {
            emptyList()
        }
    }

    private suspend fun ensureInitUnlocked(context: Context) {
        if (initialized && homeDir != null) return
        val dir = File(context.filesDir, "mihomo").apply { mkdirs() }
        ensureGeoFiles(context, dir)
        homeDir = dir.absolutePath
        val init = JSONObject()
            .put("home-dir", dir.absolutePath)
            .put("version", 1)
            .toString()
        invoke("initClash", init)
        initialized = true
    }

    private fun ensureGeoFiles(context: Context, home: File) {
        listOf("geoip.metadb", "GeoSite.dat").forEach { name ->
            val out = File(home, name)
            if (out.exists() && out.length() > 1024) return@forEach
            runCatching {
                context.assets.open("mihomo/$name").use { input ->
                    out.outputStream().use { output -> input.copyTo(output) }
                }
                Log.i(TAG, "copied asset mihomo/$name -> ${out.absolutePath}")
            }.onFailure {
                Log.w(TAG, "missing geo asset $name: ${it.message}")
            }
        }
    }

    suspend fun selectProxyPreferringGroups(proxyName: String): String {
        val groups = parseSelectorGroups(getProxiesJson())
        val preferred = listOf("PROXY", "SELECT", "GLOBAL")
        val group = preferred.firstNotNullOfOrNull { name ->
            groups.firstOrNull { it.first.equals(name, true) && it.second.any { m -> m == proxyName } }
        } ?: groups.firstOrNull { it.second.any { m -> m == proxyName } }
        return if (group != null) {
            changeProxy(group.first, proxyName)
        } else {
            changeProxy("GLOBAL", proxyName)
        }
    }

    private suspend fun invoke(method: String, data: String, timeoutMs: Long = 120_000L): String {
        val id = UUID.randomUUID().toString()
        val payload = JSONObject()
            .put("id", id)
            .put("method", method)
            .put("data", data)
            .toString()
        val done = AtomicBoolean(false)
        val result: String = withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine<String> { cont ->
                Core.invokeAction(payload, object : InvokeInterface {
                    override fun onResult(result: String) {
                        if (done.compareAndSet(false, true) && cont.isActive) {
                            cont.resume(result)
                        }
                    }
                })
            }
        } ?: return "timeout"
        return try {
            val o = JSONObject(result)
            if (o.has("data")) {
                when (val d = o.opt("data")) {
                    null -> ""
                    JSONObject.NULL -> ""
                    is String -> d
                    else -> d.toString()
                }
            } else {
                result
            }
        } catch (_: Exception) {
            result
        }
    }
}
