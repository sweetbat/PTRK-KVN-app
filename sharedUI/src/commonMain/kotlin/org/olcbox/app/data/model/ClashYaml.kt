package org.olcbox.app.data.model

import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * Best-effort Clash/Mihomo YAML probe — no full YAML parser required.
 *
 * Remnawave often ships leaf nodes only as members of proxy-groups
 * (Обычные локации / Обходы) while `proxies:` itself is empty or provider-backed.
 */
object ClashYaml {
    private val clashKey = Regex(
        """^(proxies|proxy-groups|proxy-providers|mixed-port|socks-port|port|rules|dns|tun)\s*:""",
        RegexOption.MULTILINE,
    )
    private val topLevelKey = Regex("""^[A-Za-z0-9_-]+\s*:""")

    data class ExtractedNodes(
        val regular: List<String>,
        val bypass: List<String>,
    ) {
        val all: List<String> get() = regular + bypass
    }

    fun looksLikeClash(text: String): Boolean {
        val body = decode(text)
        if (body.contains("olcrtc://")) return false
        if (clashKey.containsMatchIn(body)) return true
        // Remnawave / Meta sometimes ships provider-only profiles.
        val lower = body.lowercase()
        return lower.contains("proxy-providers:") ||
            lower.contains("proxy-groups:") ||
            (lower.contains("proxies:") && (lower.contains("type:") || lower.contains("- {")))
    }

    fun decode(raw: String): String {
        var text = raw.replace("\r\n", "\n")
        if (text.startsWith("\uFEFF")) text = text.substring(1)
        text = text.trimStart()
        if (text.isEmpty()) return text
        if (clashKey.containsMatchIn(text) || text.contains("olcrtc://")) return text
        return tryBase64(text) ?: text
    }

    /** Ordered leaf nodes: regular (обычные) first, then bypass (обходы). */
    fun extractNodes(raw: String): ExtractedNodes {
        val text = decode(raw)
        val leafFromProxies = extractDashNamesInSection(text, "proxies")
            .filterNot { isBuiltin(it) }
        val groups = extractProxyGroups(text)
        val groupNames = groups.map { it.name }.toSet()

        val bypassMembers = linkedSetOf<String>()
        val regularMembers = linkedSetOf<String>()
        groups.forEach { group ->
            when {
                isBypassGroup(group.name) -> bypassMembers += group.members
                isRegularGroup(group.name) -> regularMembers += group.members
            }
        }

        // Prefer explicit leaf proxies; if empty, use group membership lists.
        val leafPool = if (leafFromProxies.isNotEmpty()) {
            leafFromProxies.filterNot { it in groupNames }
        } else {
            (regularMembers + bypassMembers + groups.flatMap { it.members })
                .filterNot { isBuiltin(it) || it in groupNames }
                .distinct()
        }

        val regularOrdered = buildList {
            regularMembers.forEach { if (it in leafPool || leafFromProxies.isEmpty()) add(it) }
            leafPool.forEach { name ->
                if (name !in this && name !in bypassMembers) add(name)
            }
        }.filterNot { isBuiltin(it) }.distinct()

        val bypassOrdered = buildList {
            bypassMembers.forEach { name ->
                if (name !in regularOrdered && !isBuiltin(name) && name !in groupNames) add(name)
            }
        }.distinct()

        return ExtractedNodes(regular = regularOrdered, bypass = bypassOrdered)
    }

    @Deprecated("Use extractNodes", ReplaceWith("extractNodes(raw).all"))
    fun extractProxyNames(raw: String): List<String> = extractNodes(raw).all

    fun profileIdFor(subscriptionUrl: String?, body: String): String {
        val seed = subscriptionUrl?.trim()?.ifBlank { null } ?: body.take(256)
        var h = 0
        for (ch in seed) {
            h = (h * 31) + ch.code
        }
        return "mh_" + (h.toUInt().toString(16))
    }

    /**
     * Best-effort leaf protocol tags for UI chips, e.g. `VLESS|GRPC|REALITY`.
     * Looks up the named entry under the top-level `proxies:` section.
     */
    fun extractProxyProtocolTags(raw: String, proxyName: String): List<String> {
        val text = decode(raw)
        val target = proxyName.trim()
        if (target.isEmpty()) return emptyList()
        val lines = text.lineSequence().toList()
        var i = 0
        var inProxies = false
        while (i < lines.size) {
            val line = lines[i]
            val trimmed = line.trimEnd()
            if (!inProxies) {
                if (trimmed == "proxies:" || trimmed.startsWith("proxies:")) {
                    inProxies = true
                }
                i++
                continue
            }
            val indent = line.length - line.trimStart().length
            val body = line.trim()
            if (body.isNotEmpty() && indent == 0 && topLevelKey.containsMatchIn(body) &&
                !body.startsWith("-") && !body.startsWith("#")
            ) {
                break
            }
            val nameMatch = Regex("""^\s*-\s*name:\s*(.+)\s*$""").matchEntire(line)
                ?: Regex("""^\s*name:\s*(.+)\s*$""").matchEntire(line)?.takeIf {
                    // flow-style / nested name inside an already-started dash item
                    i > 0 && lines[i - 1].trimStart().startsWith("-")
                }
            if (nameMatch != null && unquote(nameMatch.groupValues[1]) == target) {
                val fields = linkedMapOf<String, String>()
                // Inline `- { name: X, type: vless, ... }`
                if (body.contains('{') && body.contains('}')) {
                    Regex("""(\w[\w-]*)\s*:\s*([^,}]+)""")
                        .findAll(body)
                        .forEach { m ->
                            fields[m.groupValues[1].lowercase()] = unquote(m.groupValues[2])
                        }
                } else {
                    var j = i + 1
                    while (j < lines.size) {
                        val next = lines[j]
                        val nextTrim = next.trim()
                        val nextIndent = next.length - next.trimStart().length
                        if (nextTrim.startsWith("- ") && nextIndent <= indent) break
                        if (nextTrim.isNotEmpty() && nextIndent == 0 &&
                            topLevelKey.containsMatchIn(nextTrim)
                        ) break
                        val kv = Regex("""^\s*([A-Za-z0-9_-]+)\s*:\s*(.*)$""").matchEntire(next)
                        if (kv != null) {
                            val key = kv.groupValues[1].lowercase()
                            val value = unquote(kv.groupValues[2])
                            if (value.isNotBlank() && value != "|" && value != ">") {
                                fields[key] = value
                            } else if (
                                key == "reality-opts" || key == "ws-opts" ||
                                key == "grpc-opts" || key == "smux" ||
                                key == "tls" || key == "ech-opts" ||
                                key == "brutal-opts" || key == "hysteria-opts"
                            ) {
                                fields[key] = "present"
                            }
                        }
                        j++
                    }
                }
                return buildProtocolTags(fields)
            }
            i++
        }
        return emptyList()
    }

    /**
     * True when the named leaf looks IPv6-capable (literal address or explicit opts).
     * Used to decide whether IPv6-only sites (e.g. ntc.party) should exit via the node.
     */
    fun proxyHasIpv6(raw: String, proxyName: String): Boolean {
        val fields = extractProxyFields(raw, proxyName) ?: return false
        if (fields["ipv6"]?.equals("true", true) == true) return true
        val ipVersion = fields["ip-version"]?.lowercase().orEmpty()
        if (ipVersion.contains("6") && !ipVersion.contains("ipv4")) return true
        if (looksLikeIpv6Host(fields["server"])) return true
        if (looksLikeIpv6Host(fields["servername"])) return true
        listOf("server-v6", "serverv6", "ipv6-addr", "ipv6").forEach { key ->
            fields[key]?.takeIf { it.isNotBlank() && !it.equals("false", true) }?.let { return true }
        }
        return false
    }

    private fun extractProxyFields(raw: String, proxyName: String): Map<String, String>? {
        val text = decode(raw)
        val target = proxyName.trim()
        if (target.isEmpty()) return null
        val lines = text.lineSequence().toList()
        var i = 0
        var inProxies = false
        while (i < lines.size) {
            val line = lines[i]
            val trimmed = line.trimEnd()
            if (!inProxies) {
                if (trimmed == "proxies:" || trimmed.startsWith("proxies:")) {
                    inProxies = true
                }
                i++
                continue
            }
            val indent = line.length - line.trimStart().length
            val body = line.trim()
            if (body.isNotEmpty() && indent == 0 && topLevelKey.containsMatchIn(body) &&
                !body.startsWith("-") && !body.startsWith("#")
            ) {
                break
            }
            val nameMatch = Regex("""^\s*-\s*name:\s*(.+)\s*$""").matchEntire(line)
                ?: Regex("""^\s*name:\s*(.+)\s*$""").matchEntire(line)?.takeIf {
                    i > 0 && lines[i - 1].trimStart().startsWith("-")
                }
            if (nameMatch != null && unquote(nameMatch.groupValues[1]) == target) {
                val fields = linkedMapOf<String, String>()
                if (body.contains('{') && body.contains('}')) {
                    Regex("""(\w[\w-]*)\s*:\s*([^,}]+)""")
                        .findAll(body)
                        .forEach { m ->
                            fields[m.groupValues[1].lowercase()] = unquote(m.groupValues[2])
                        }
                } else {
                    var j = i + 1
                    while (j < lines.size) {
                        val next = lines[j]
                        val nextTrim = next.trim()
                        val nextIndent = next.length - next.trimStart().length
                        if (nextTrim.startsWith("- ") && nextIndent <= indent) break
                        if (nextTrim.isNotEmpty() && nextIndent == 0 &&
                            topLevelKey.containsMatchIn(nextTrim)
                        ) break
                        val kv = Regex("""^\s*([A-Za-z0-9_-]+)\s*:\s*(.*)$""").matchEntire(next)
                        if (kv != null) {
                            val key = kv.groupValues[1].lowercase()
                            val value = unquote(kv.groupValues[2])
                            if (value.isNotBlank() && value != "|" && value != ">") {
                                fields[key] = value
                            } else if (
                                key == "reality-opts" || key == "ws-opts" ||
                                key == "grpc-opts" || key == "smux" ||
                                key == "tls" || key == "ech-opts" ||
                                key == "brutal-opts" || key == "hysteria-opts"
                            ) {
                                fields[key] = "present"
                            }
                        }
                        j++
                    }
                }
                return fields
            }
            i++
        }
        return null
    }

    private fun looksLikeIpv6Host(value: String?): Boolean {
        val host = value?.trim()?.removePrefix("[")?.substringBefore(']')?.substringBefore('%')
            ?.takeIf { it.isNotBlank() }
            ?: return false
        if (host.count { it == ':' } < 2) return false
        return host.any { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' }
    }

    private fun buildProtocolTags(fields: Map<String, String>): List<String> {
        val tags = mutableListOf<String>()
        fun add(tag: String?) {
            val t = tag?.trim()?.uppercase()?.takeIf { it.isNotBlank() } ?: return
            if (t !in tags) tags += t
        }
        val type = fields["type"]?.lowercase()
        when (type) {
            "vless" -> add("VLESS")
            "vmess" -> add("VMESS")
            "trojan" -> add("TROJAN")
            "ss", "shadowsocks" -> add("SS")
            "hysteria" -> add("HYSTERIA")
            "hysteria2", "hy2" -> add("HYSTERIA2")
            "tuic" -> add("TUIC")
            "wireguard" -> add("WIREGUARD")
            "anytls" -> add("ANYTLS")
            null, "" -> Unit
            else -> add(type.uppercase())
        }
        val network = fields["network"]?.lowercase()
        when (network) {
            "grpc" -> add("GRPC")
            "ws", "websocket" -> add("WS")
            "http" -> add("HTTP")
            "h2", "http2" -> add("H2")
            "tcp" -> add("TCP")
            "udp" -> add("UDP")
            "xhttp", "httpupgrade" -> add("JSON")
            null, "" -> Unit
            else -> add(network.uppercase())
        }
        if (fields.containsKey("reality-opts") ||
            fields["reality"]?.equals("true", true) == true ||
            fields["client-fingerprint"] != null && fields["public-key"] != null
        ) {
            add("REALITY")
        }
        val tlsHint = fields["tls"]?.lowercase()
        val hasTls = fields.containsKey("tls") ||
            tlsHint in setOf("true", "1", "tls", "present") ||
            fields["security"]?.equals("tls", true) == true ||
            !fields["sni"].isNullOrBlank() ||
            !fields["servername"].isNullOrBlank() ||
            !fields["alpn"].isNullOrBlank() ||
            fields.containsKey("skip-cert-verify") ||
            fields.containsKey("fingerprint") ||
            fields.containsKey("client-fingerprint") ||
            type == "hysteria" || type == "hysteria2" || type == "hy2" || type == "tuic"
        if (hasTls && "REALITY" !in tags) add("TLS")
        fields["flow"]?.takeIf { it.contains("vision", ignoreCase = true) }?.let { add("VISION") }
        if (fields["packet-encoding"] != null ||
            fields["xmux"] != null ||
            network == "xhttp" ||
            fields["obfs"]?.contains("salamander", ignoreCase = true) == true ||
            fields.containsKey("brutal-opts") ||
            // Remnawave / panel often labels Hysteria2 QUIC path as JSON in UIs.
            ((type == "hysteria2" || type == "hy2") &&
                (fields.containsKey("quic") || fields["ports"] != null ||
                    fields["port-hopping"] != null || fields["hop-interval"] != null ||
                    fields["up"] != null || fields["down"] != null))
        ) {
            add("JSON")
        }
        // Hysteria2 is always TLS+QUIC; ensure both chips show even on minimal YAML.
        if (type == "hysteria2" || type == "hy2") {
            add("TLS")
            add("JSON")
        }
        return tags
    }

    private data class ProxyGroup(val name: String, val members: List<String>)

    private fun extractProxyGroups(text: String): List<ProxyGroup> {
        val lines = text.lineSequence().toList()
        val start = lines.indexOfFirst { line ->
            val trimmed = line.trim()
            val indent = line.takeWhile { it == ' ' || it == '\t' }.length
            indent == 0 && (trimmed == "proxy-groups:" || trimmed.startsWith("proxy-groups:"))
        }
        if (start < 0) return emptyList()
        val out = mutableListOf<ProxyGroup>()
        var i = start + 1
        var currentName: String? = null
        var members = mutableListOf<String>()
        var inProxiesList = false

        fun flush() {
            val n = currentName?.takeIf { it.isNotBlank() } ?: return
            out += ProxyGroup(n, members.toList())
            members = mutableListOf()
            inProxiesList = false
        }

        while (i < lines.size) {
            val line = lines[i]
            val trimmed = line.trim()
            if (trimmed.isNotEmpty() && !line.startsWith(" ") && !line.startsWith("\t") &&
                !trimmed.startsWith("#") && topLevelKey.containsMatchIn(trimmed) &&
                !trimmed.startsWith("-")
            ) {
                flush()
                break
            }

            val nameMatch = Regex("""^\s*-\s*name:\s*(.+)\s*$""").matchEntire(line)
            if (nameMatch != null) {
                flush()
                currentName = unquote(nameMatch.groupValues[1])
                inProxiesList = false
                i++
                continue
            }

            if (Regex("""^\s*proxies\s*:\s*$""").matches(line) ||
                Regex("""^\s*proxies\s*:\s*\[.*]\s*$""").matches(line)
            ) {
                inProxiesList = true
                val inline = Regex("""^\s*proxies\s*:\s*\[(.*)]\s*$""").matchEntire(line)
                if (inline != null) {
                    inline.groupValues[1].split(',')
                        .map { unquote(it.trim()) }
                        .filter { it.isNotBlank() }
                        .forEach { members += it }
                    inProxiesList = false
                }
                i++
                continue
            }

            if (inProxiesList) {
                val item = Regex("""^\s*-\s+(.+?)\s*$""").matchEntire(line)
                if (item != null) {
                    val value = unquote(item.groupValues[1])
                    if (value.isNotBlank() && !value.contains(':')) {
                        members += value
                    } else if (value.startsWith("name:", ignoreCase = true)) {
                        // nested object — ignore
                    }
                } else if (trimmed.isNotEmpty() && !trimmed.startsWith("#") &&
                    !line.startsWith(" ") && !line.startsWith("\t")
                ) {
                    inProxiesList = false
                } else if (trimmed.contains(":") && !trimmed.startsWith("-") &&
                    line.indexOf(trimmed) > 0 &&
                    line.takeWhile { it == ' ' || it == '\t' }.length <= 4
                ) {
                    // next group field at same indent as proxies
                    if (!trimmed.startsWith("proxies")) inProxiesList = false
                }
            }
            i++
        }
        flush()
        return out
    }

    private fun extractDashNamesInSection(text: String, section: String): List<String> {
        val lines = text.lineSequence().toList()
        // Only match TOP-LEVEL sections. Nested `proxies:` under proxy-groups would
        // otherwise steal the cursor and yield group names → empty leaf set → import fail.
        val start = lines.indexOfFirst { line ->
            val trimmed = line.trim()
            val indent = line.takeWhile { it == ' ' || it == '\t' }.length
            indent == 0 && (trimmed == "$section:" || trimmed.startsWith("$section:"))
        }
        if (start < 0) return emptyList()
        val names = mutableListOf<String>()
        var i = start + 1
        while (i < lines.size) {
            val line = lines[i]
            val trimmed = line.trim()
            if (trimmed.isNotEmpty() && !line.startsWith(" ") && !line.startsWith("\t") &&
                !trimmed.startsWith("#") && !trimmed.startsWith("-") &&
                topLevelKey.containsMatchIn(trimmed)
            ) {
                break
            }
            val nameMatch = Regex("""^\s*-\s*name:\s*(.+)\s*$""").matchEntire(line)
            if (nameMatch != null) {
                val value = unquote(nameMatch.groupValues[1])
                if (value.isNotBlank() && !isBuiltin(value)) names += value
            }
            i++
        }
        return names.distinct()
    }

    private fun isBypassGroup(name: String): Boolean {
        val n = name.lowercase()
        // Cyrillic via escapes so source encoding cannot break matching.
        return n.contains("\u043e\u0431\u0445\u043e\u0434") || // обход
            n.contains("bypass") ||
            n.contains("obhod")
    }

    private fun isRegularGroup(name: String): Boolean {
        val n = name.lowercase()
        return n.contains("\u043e\u0431\u044b\u0447\u043d") || // обычн
            n.contains("\u043b\u043e\u043a\u0430\u0446") || // локац
            n.contains("regular") ||
            n.contains("location") ||
            n.contains("server") ||
            n.contains("node")
    }

    private fun isBuiltin(name: String): Boolean {
        val n = name.trim()
        if (n.isEmpty()) return true
        val upper = n.uppercase()
        if (upper in setOf(
                "DIRECT", "REJECT", "PASS", "COMPATIBLE", "GLOBAL",
                "REJECT-DROP", "PASS-RULE", "REJECT-ALL", "BLOCK", "BLACKHOLE",
                "DNS", "COMPATIBLE",
            )
        ) return true
        if (upper.startsWith("REJECT") || upper.startsWith("PASS")) return true
        if (upper.startsWith("MATCH") || upper.startsWith("FINAL")) return true
        return false
    }

    private fun unquote(value: String): String {
        var v = value.trim()
        if ((v.startsWith("\"") && v.endsWith("\"")) || (v.startsWith("'") && v.endsWith("'"))) {
            v = v.substring(1, v.length - 1)
        }
        return v.trim()
    }

    @OptIn(ExperimentalEncodingApi::class)
    private fun tryBase64(text: String): String? {
        val compact = text.filterNot { it.isWhitespace() }
        if (compact.length < 64) return null
        return runCatching {
            val bytes = Base64.decode(compact)
            val decoded = bytes.decodeToString()
            if (clashKey.containsMatchIn(decoded)) decoded else null
        }.getOrNull()
    }
}
