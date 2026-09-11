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

    private val RULES_SECTION = Regex("""(?m)^rules\s*:""")

    // FlClashX default; HTTP variant is tried first in urlTestResilient (lighter for gRPC).
    const val DEFAULT_TEST_URL = "https://www.gstatic.com/generate_204"

    private val TEST_URLS = listOf(
        "http://www.gstatic.com/generate_204",
        DEFAULT_TEST_URL,
        "http://captive.apple.com/hotspot-detect.html",
    )

    /**
     * Always via selected node — Mihomo has no Xray TLS fragment for DIRECT.
     * YouTube: RU throttle on DIRECT. Speedtest/Ookla: whitelist DIRECT breaks
     * the site/app when routing (rule mode) is on.
     */
    private val FORCE_VIA_GLOBAL_RULES = listOf(
        "DOMAIN-KEYWORD,youtube,GLOBAL",
        "DOMAIN-KEYWORD,googlevideo,GLOBAL",
        "DOMAIN-KEYWORD,ytimg,GLOBAL",
        "DOMAIN-SUFFIX,youtu.be,GLOBAL",
        "DOMAIN-SUFFIX,ggpht.com,GLOBAL",
        "DOMAIN-KEYWORD,speedtest,GLOBAL",
        "DOMAIN-KEYWORD,ookla,GLOBAL",
        "DOMAIN-SUFFIX,speedtest.net,GLOBAL",
        "DOMAIN-SUFFIX,ookla.com,GLOBAL",
        "DOMAIN-SUFFIX,speedtestcustom.com,GLOBAL",
    )

    /**
     * ntc.party is AAAA-only in public DNS. hev is IPv4-only, so we map the name to a
     * Clash fake-ip (198.18/16). Sniffer/DNS-mapping then sends the *domain* to the
     * selected node; the VPS resolves AAAA. Mapping to the real box IPv4 made Hy2
     * (UDP) work but VLESS/gRPC dialed that IPv4 over TCP and failed.
     */
    private const val NTC_PARTY_FAKE_IP = "198.18.0.53"
    private const val NTC_PARTY_IPV4 = "130.255.77.28"
    private const val NTC_PARTY_IPV6 = "2a02:e00:ffec:4b8::1"

    /** ntc.party must always exit via the selected node (GLOBAL), never DIRECT. */
    private fun ntcPartyRules(): List<String> = listOf(
        "DOMAIN,ntc.party,GLOBAL",
        "DOMAIN-SUFFIX,ntc.party,GLOBAL",
        "DOMAIN-KEYWORD,ntc.party,GLOBAL",
        // Fake-ip from hosts — must not fall through to GEOIP,private,DIRECT.
        "IP-CIDR,$NTC_PARTY_FAKE_IP/32,GLOBAL,no-resolve",
        "IP-CIDR,198.18.0.0/16,GLOBAL,no-resolve",
        "IP-CIDR,$NTC_PARTY_IPV4/32,GLOBAL,no-resolve",
        "IP-CIDR6,$NTC_PARTY_IPV6/128,GLOBAL,no-resolve",
        "DOMAIN,ntc.party,PROXY",
        "DOMAIN-SUFFIX,ntc.party,PROXY",
        "IP-CIDR,$NTC_PARTY_FAKE_IP/32,PROXY,no-resolve",
        "IP-CIDR,198.18.0.0/16,PROXY,no-resolve",
        "IP-CIDR,$NTC_PARTY_IPV4/32,PROXY,no-resolve",
        "IP-CIDR6,$NTC_PARTY_IPV6/128,PROXY,no-resolve",
    )

    private fun forceRules(): List<String> =
        FORCE_VIA_GLOBAL_RULES + ntcPartyRules()

    private fun ntcPartyHosts(): JSONObject =
        JSONObject()
            .put("ntc.party", NTC_PARTY_FAKE_IP)
            .put("www.ntc.party", NTC_PARTY_FAKE_IP)
            .put("box.ntc.party", NTC_PARTY_FAKE_IP)

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
        /** Plain DNS IPs for resolving proxy hosts (use carrier DNS on cellular). */
        bootstrapDns: List<String> = emptyList(),
        testUrl: String = DEFAULT_TEST_URL,
        selectedProxyName: String? = null,
    ): String = lock.withLock {
        ensureInitUnlocked(context)
        this.mode = mode
        val selected = JSONObject()
        selectedMap.forEach { (k, v) -> selected.put(k, v) }
        val proxyForLog = selectedProxyName
            ?: selectedMap["GLOBAL"]
            ?: selectedMap["PROXY"]
        // ntc.party: local fake-ip (hev IPv4) → sniffer sends domain to node → VPS AAAA.
        Log.i(TAG, "setupProfile proxy=$proxyForLog ntc=GLOBAL fake-ip=$NTC_PARTY_FAKE_IP")
        // No Xray-style TLS fragment in Mihomo: YouTube via DIRECT is broken under
        // RU throttling, so always pin youtube/googlevideo through GLOBAL (selected node).
        val configPath = if (mode.equals("global", ignoreCase = true)) {
            yamlPath
        } else {
            runtimeConfigWithForcedProxyDomains(yamlPath)
        }
        // RKN TSPU hijacks UDP DNS to 8.8.8.8 / 1.1.1.1. Use carrier + Yandex,
        // and DoH over HTTPS (TCP) so nameserver lookups are not intercepted.
        val plainDns = org.olcbox.app.vpn.RuSafeDns.plainBootstrap(bootstrapDns)
        val nameServers = org.olcbox.app.vpn.RuSafeDns.clashNameservers(bootstrapDns)
        val plainDnsJson = org.json.JSONArray().also { arr ->
            plainDns.forEach { arr.put(it) }
        }
        val nameServersJson = org.json.JSONArray().also { arr ->
            nameServers.forEach { arr.put(it) }
        }
        val overrides = JSONObject()
            .put("mode", mode)
            .put("ipv6", false)
            .put("unified-delay", true)
            .put("tcp-concurrent", true)
            .put("hosts", ntcPartyHosts())
            // Keep a local mixed-port so we can diagnose; VpnService path does not need it.
            .put("mixed-port", 7890)
            .put(
                "tun",
                JSONObject()
                    .put("enable", false)
                    // Xiaomi/MIUI: mixed/gvisor often accepts UDP DNS but stalls TCP.
                    .put("stack", "system")
                    .put("auto-route", false)
                    .put("auto-detect-interface", false)
                    .put("mtu", 1500)
                    .put(
                        "dns-hijack",
                        org.json.JSONArray().put("any:53"),
                    ),
            )
            .put("find-process-mode", "off")
            // Required with hev/SOCKS: recover Host/SNI so domain rules (Минцифры /
            // antizapret) match instead of GEOIP on the SOCKS destination IP.
            .put(
                "sniffer",
                JSONObject()
                    .put("enable", true)
                    .put("force-dns-mapping", true)
                    .put("parse-pure-ip", true)
                    // Send sniffed domain to proxy (VPS resolves AAAA for ntc.party).
                    .put("override-destination", true)
                    .put(
                        "force-domain",
                        org.json.JSONArray()
                            .put("ntc.party")
                            .put("+.ntc.party"),
                    )
                    .put(
                        "sniff",
                        JSONObject()
                            .put(
                                "HTTP",
                                JSONObject()
                                    .put(
                                        "ports",
                                        org.json.JSONArray().put("80").put("8080-8880"),
                                    )
                                    .put("override-destination", true),
                            )
                            .put(
                                "TLS",
                                JSONObject()
                                    .put(
                                        "ports",
                                        org.json.JSONArray().put("443").put("8443"),
                                    )
                                    .put("override-destination", true),
                            )
                            .put(
                                "QUIC",
                                JSONObject()
                                    .put(
                                        "ports",
                                        org.json.JSONArray().put("443").put("8443"),
                                    )
                                    .put("override-destination", true),
                            ),
                    ),
            )
            .put(
                "dns",
                JSONObject()
                    .put("enable", true)
                    .put("ipv6", false)
                    .put("use-hosts", true)
                    .put("use-system-hosts", false)
                    // redir-host + hosts in fake-ip-range: IP→domain mapping, dial by name.
                    .put("enhanced-mode", "redir-host")
                    .put("fake-ip-range", "198.18.0.1/16")
                    .put("listen", "0.0.0.0:1053")
                    .put("nameserver", nameServersJson)
                    .put("default-nameserver", plainDnsJson)
                    .put("proxy-server-nameserver", plainDnsJson)
                    .put("direct-nameserver", plainDnsJson),
            )
        // Routing off: wipe subscription rules (RU whitelist would still DIRECT).
        // Keep YouTube pin at the top even though MATCH,GLOBAL already covers it.
        if (mode.equals("global", ignoreCase = true)) {
            val rules = org.json.JSONArray()
            forceRules().forEach { rules.put(it) }
            rules.put("GEOIP,private,DIRECT,no-resolve")
            rules.put("MATCH,GLOBAL")
            overrides.put("rules", rules)
        }
        val params = JSONObject()
            .put("config-path", configPath)
            .put("overrides", overrides)
            .put("home-dir", homeDir)
            .put("selected-map", selected)
            .put("test-url", testUrl)
            .toString()
        invoke("setupConfig", params)
    }

    /**
     * Prepend force-proxy rules so subscription DIRECT/whitelist cannot win.
     * Writes sibling `*.runtime.yaml` — original profile on disk stays untouched.
     */
    private fun runtimeConfigWithForcedProxyDomains(yamlPath: String): String {
        val src = File(yamlPath)
        if (!src.isFile) return yamlPath
        val dest = File(src.parentFile, "${src.nameWithoutExtension}.runtime.yaml")
        var body = src.readText()
        // ntc.party → fake-ip so sniffer/DNS-mapping dials by domain through the node.
        val hostsBlock = """
hosts:
  ntc.party: $NTC_PARTY_FAKE_IP
  www.ntc.party: $NTC_PARTY_FAKE_IP
  box.ntc.party: $NTC_PARTY_FAKE_IP
""".trimIndent()
        if (Regex("""(?m)^hosts\s*:""").containsMatchIn(body)) {
            body = body
                .replace(Regex("""(?m)^(\s*)ntc\.party:\s*.*$"""), "$1ntc.party: $NTC_PARTY_FAKE_IP")
                .replace(Regex("""(?m)^(\s*)www\.ntc\.party:\s*.*$"""), "$1www.ntc.party: $NTC_PARTY_FAKE_IP")
                .replace(Regex("""(?m)^(\s*)box\.ntc\.party:\s*.*$"""), "$1box.ntc.party: $NTC_PARTY_FAKE_IP")
            if (!body.contains("ntc.party:")) {
                body = body.replaceFirst(
                    Regex("""(?m)^hosts\s*:\s*\n"""),
                    "hosts:\n  ntc.party: $NTC_PARTY_FAKE_IP\n  www.ntc.party: $NTC_PARTY_FAKE_IP\n  box.ntc.party: $NTC_PARTY_FAKE_IP\n",
                )
            }
        } else {
            body = body.trimEnd() + "\n\n" + hostsBlock + "\n"
        }
        if (Regex("""(?m)^ipv6\s*:""").containsMatchIn(body)) {
            body = body.replace(Regex("""(?m)^ipv6\s*:\s*\S+"""), "ipv6: false")
        }
        val insert = forceRules().joinToString("\n") { "  - $it" } + "\n"
        val match = RULES_SECTION.find(body)
        val patched = if (match != null) {
            val lineEnd = body.indexOf('\n', match.range.last).let { if (it < 0) body.length else it + 1 }
            body.substring(0, lineEnd) + insert + body.substring(lineEnd)
        } else {
            body.trimEnd() + "\n\nrules:\n" + insert
        }
        dest.writeText(patched)
        Log.i(TAG, "Force-proxy domains via GLOBAL prepended -> ${dest.name} ntc=GLOBAL+fake-ip=$NTC_PARTY_FAKE_IP")
        return dest.absolutePath
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

    suspend fun urlTest(
        proxyName: String,
        testUrl: String = DEFAULT_TEST_URL,
        timeoutMs: Long = 10_000L,
    ): Long {
        val params = JSONObject()
            .put("proxy-name", proxyName)
            .put("test-url", testUrl)
            .put("timeout", timeoutMs.toInt().coerceIn(3_000, 30_000))
            .toString()
        val raw = invoke("asyncTestDelay", params, timeoutMs = timeoutMs + 4_000L)
        return parseDelayMs(raw)
    }

    /**
     * HTTP first (gRPC-friendly), then HTTPS. Overall budget ~30s per node so
     * several URL attempts fit without stretching a single probe forever.
     */
    suspend fun urlTestResilient(
        proxyName: String,
        overallTimeoutMs: Long = 30_000L,
    ): Long {
        val started = System.currentTimeMillis()
        for (url in TEST_URLS) {
            val left = overallTimeoutMs - (System.currentTimeMillis() - started)
            if (left < 2_500L) break
            val slice = minOf(10_000L, left)
            val ms = runCatching { urlTest(proxyName, url, timeoutMs = slice) }.getOrDefault(-1L)
            if (ms > 0L) {
                Log.i(TAG, "urlTestResilient $proxyName ok via $url → ${ms}ms")
                return ms
            }
            Log.i(TAG, "urlTestResilient $proxyName fail via $url")
        }
        return -1L
    }

    private fun parseDelayMs(raw: String): Long {
        val trimmed = raw.trim()
        if (trimmed.isEmpty() || trimmed.equals("timeout", true) || trimmed.equals("null", true)) {
            return -1L
        }
        trimmed.toLongOrNull()?.let { return it }
        return runCatching {
            val o = JSONObject(trimmed)
            when {
                o.has("value") -> o.optLong("value", -1L)
                o.has("delay") -> o.optLong("delay", -1L)
                o.has("data") -> {
                    when (val d = o.opt("data")) {
                        is Number -> d.toLong()
                        is String -> parseDelayMs(d)
                        is JSONObject -> d.optLong("value", d.optLong("delay", -1L))
                        else -> -1L
                    }
                }
                else -> -1L
            }
        }.getOrDefault(-1L)
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

    fun updateDns(servers: List<String>) {
        val cleaned = servers.map { it.trim() }.filter { it.isNotBlank() }.distinct()
        if (cleaned.isEmpty()) return
        runCatching {
            Core.updateDns(JSONArray(cleaned).toString())
        }.onFailure {
            Log.w(TAG, "updateDns failed: ${it.message}")
        }
    }

    fun resetConnections() {
        runCatching { Core.resetConnections() }
    }

    /** Tell libclash VpnService is owning the tunnel (FlClash CoreState). */
    fun setVpnState(enabled: Boolean = true, ipv6: Boolean = true) {
        runCatching {
            val state = JSONObject()
                .put(
                    "vpn-props",
                    JSONObject()
                        .put("enable", enabled)
                        .put("system-proxy", false)
                        .put("allow-bypass", false)
                        .put("ipv6", ipv6),
                )
                .put("only-statistics-proxy", false)
                .put("current-profile-name", "PTRK-KVN")
                .put("bypass-domain", JSONArray())
            Core.setState(state.toString())
        }.onFailure {
            Log.w(TAG, "setVpnState failed: ${it.message}")
        }
    }

    fun traffic(): String = runCatching { Core.getTraffic() }.getOrDefault("{}")
    fun runTime(): String = runCatching { Core.getRunTime() }.getOrDefault("0")
    fun vpnOptionsJson(): String = runCatching { Core.getAndroidVpnOptions() }.getOrDefault("")

    suspend fun nowSelected(groupName: String): String? {
        return runCatching {
            val root = JSONObject(getProxiesJson())
            root.optJSONObject(groupName)?.optString("now")?.takeIf { it.isNotBlank() }
        }.getOrNull()
    }

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
        val matching = groups.filter { (_, members) -> members.any { it == proxyName } }
        var last = ""
        if (matching.isEmpty()) {
            last = changeProxy("GLOBAL", proxyName)
        } else {
            val preferredOrder = listOf("PROXY", "SELECT", "GLOBAL")
            val ordered = matching.sortedBy { (name, _) ->
                val idx = preferredOrder.indexOfFirst { it.equals(name, true) }
                if (idx >= 0) idx else 100
            }
            for ((group, _) in ordered) {
                last = changeProxy(group, proxyName)
            }
            // Point parent selectors (🚀 PTRK-KVN) at the subgroup that owns this leaf.
            val leafGroupNames = matching.map { it.first }.toSet()
            groups.forEach { (parent, members) ->
                if (parent in leafGroupNames) return@forEach
                val child = members.firstOrNull { it in leafGroupNames } ?: return@forEach
                last = changeProxy(parent, child)
            }
        }
        // Always pin GLOBAL to the leaf for mode=global.
        runCatching { changeProxy("GLOBAL", proxyName) }.onSuccess { last = it }
        return last
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
