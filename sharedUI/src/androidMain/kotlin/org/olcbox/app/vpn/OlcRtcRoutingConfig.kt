package org.olcbox.app.vpn

import android.content.Context
import java.io.File

/**
 * Builds a Clash profile that reuses Remnawave rule-providers/rules but sends
 * non-DIRECT traffic through a local olcRTC SOCKS outbound.
 *
 * libclash cannot share a process with libgojni, so this YAML is loaded in `:route`.
 */
object OlcRtcRoutingConfig {
    const val PROXY_NAME = "olcRTC-tunnel"
    const val MIXED_PORT = 7890
    private const val OUT_NAME = "olcrtc-routing.yaml"

    private val FORCE_PROXY_RULES = listOf(
        "DOMAIN-KEYWORD,youtube,PROXY",
        "DOMAIN-KEYWORD,googlevideo,PROXY",
        "DOMAIN-KEYWORD,ytimg,PROXY",
        "DOMAIN-SUFFIX,youtu.be,PROXY",
        "DOMAIN-SUFFIX,ggpht.com,PROXY",
        "DOMAIN-KEYWORD,speedtest,PROXY",
        "DOMAIN-KEYWORD,ookla,PROXY",
        "DOMAIN-SUFFIX,speedtest.net,PROXY",
        "DOMAIN-SUFFIX,ookla.com,PROXY",
        "DOMAIN-SUFFIX,speedtestcustom.com,PROXY",
        // hev mapdns 100.64/10 must not hit GEOIP,private → DIRECT.
        "IP-CIDR,100.64.0.0/10,PROXY,no-resolve",
        // ntc.party AAAA-only: force via tunnel (any routing mode).
        "DOMAIN,ntc.party,PROXY",
        "DOMAIN-SUFFIX,ntc.party,PROXY",
        "DOMAIN-KEYWORD,ntc.party,PROXY",
        "IP-CIDR6,2a02:e00:ffec:4b8::1/128,PROXY,no-resolve",
        "IP-CIDR6,2a02:e00:ffec:4b8::/64,PROXY,no-resolve",
        "IP-CIDR,130.255.77.28/32,PROXY,no-resolve",
    )

    fun outputFile(context: Context): File =
        File(context.filesDir, "mihomo/profiles").apply { mkdirs() }.resolve(OUT_NAME)

    fun findSourceProfile(context: Context): File? {
        val dir = File(context.filesDir, "mihomo/profiles")
        if (!dir.isDirectory) return null
        return dir.listFiles()
            ?.filter {
                it.isFile &&
                    it.name.endsWith(".yaml", ignoreCase = true) &&
                    !it.name.contains(".runtime.") &&
                    it.name != OUT_NAME
            }
            ?.maxByOrNull { it.lastModified() }
    }

    fun build(context: Context, olcRtcSocksPort: Int, sourceYaml: String?): File {
        val providers = sourceYaml?.let { extractTopLevelSection(it, "rule-providers") }.orEmpty()
        val rulesSection = sourceYaml?.let { extractTopLevelSection(it, "rules") }
        val rewrittenRules = if (rulesSection != null) {
            rewriteRulesPolicies(rulesSection)
        } else {
            fallbackRules()
        }
        val youtube = FORCE_PROXY_RULES.joinToString("\n") { "  - $it" }
        val rulesBody = rewrittenRules
            .lineSequence()
            .dropWhile { it.trim().isEmpty() || it.trimStart().startsWith("rules:") }
            .joinToString("\n")

        val yaml = buildString {
            appendLine("mixed-port: $MIXED_PORT")
            appendLine("allow-lan: false")
            appendLine("mode: rule")
            appendLine("log-level: warning")
            appendLine("ipv6: false")
            appendLine("find-process-mode: off")
            appendLine("unified-delay: true")
            appendLine()
            appendLine("hosts:")
            appendLine("  ntc.party: 130.255.77.28")
            appendLine("  www.ntc.party: 130.255.77.28")
            appendLine("  box.ntc.party: 130.255.77.28")
            appendLine()
            appendLine("dns:")
            appendLine("  enable: true")
            appendLine("  ipv6: false")
            appendLine("  use-hosts: true")
            appendLine("  use-system-hosts: false")
            appendLine("  enhanced-mode: redir-host")
            appendLine()
            appendLine("sniffer:")
            appendLine("  enable: true")
            appendLine("  force-dns-mapping: true")
            appendLine("  parse-pure-ip: true")
            appendLine("  override-destination: true")
            appendLine("  force-domain:")
            appendLine("    - ntc.party")
            appendLine("    - +.ntc.party")
            appendLine()
            appendLine("proxies:")
            appendLine("  - name: $PROXY_NAME")
            appendLine("    type: socks5")
            appendLine("    server: 127.0.0.1")
            appendLine("    port: $olcRtcSocksPort")
            appendLine("    udp: true")
            appendLine()
            appendLine("proxy-groups:")
            appendLine("  - name: PROXY")
            appendLine("    type: select")
            appendLine("    proxies:")
            appendLine("      - $PROXY_NAME")
            appendLine("  - name: GLOBAL")
            appendLine("    type: select")
            appendLine("    proxies:")
            appendLine("      - $PROXY_NAME")
            appendLine()
            if (providers.isNotBlank()) {
                append(providers.trimEnd())
                appendLine()
                appendLine()
            }
            appendLine("rules:")
            appendLine(youtube)
            appendLine(rulesBody.trimEnd())
            appendLine()
        }

        val out = outputFile(context)
        out.writeText(yaml)
        return out
    }

    private fun fallbackRules(): String = """
        rules:
          - GEOIP,private,DIRECT,no-resolve
          - GEOIP,RU,DIRECT
          - MATCH,PROXY
    """.trimIndent()

    private fun extractTopLevelSection(yaml: String, key: String): String? {
        val lines = yaml.replace("\r\n", "\n").lines()
        val start = lines.indexOfFirst { line ->
            val t = line.trimEnd()
            t == "$key:" || t.startsWith("$key:")
        }
        if (start < 0) return null
        val out = StringBuilder()
        out.appendLine(lines[start])
        var i = start + 1
        while (i < lines.size) {
            val line = lines[i]
            val trimmed = line.trimStart()
            val indent = line.length - trimmed.length
            if (line.isNotEmpty() && indent == 0 && trimmed.contains(':') && !trimmed.startsWith("#")) {
                break
            }
            out.appendLine(line)
            i++
        }
        return out.toString().trimEnd()
    }

    private fun rewriteRulesPolicies(rulesSection: String): String {
        return rulesSection.lineSequence().joinToString("\n") { line ->
            rewriteRuleLine(line)
        }
    }

    private fun rewriteRuleLine(line: String): String {
        val trimmed = line.trim()
        if (!trimmed.startsWith("- ")) return line
        val indent = line.takeWhile { it == ' ' || it == '\t' }
        var body = trimmed.removePrefix("- ").trim()
        val noResolve = body.endsWith(",no-resolve", ignoreCase = true)
        if (noResolve) {
            body = body.removeSuffix(",no-resolve").removeSuffix(",no-Resolve").trimEnd(',')
        }
        val idx = body.lastIndexOf(',')
        if (idx <= 0) return line
        val head = body.substring(0, idx)
        val policy = body.substring(idx + 1).trim()
        val keep = policy.equals("DIRECT", true) ||
            policy.equals("REJECT", true) ||
            policy.equals("REJECT-DROP", true) ||
            policy.equals("PASS", true) ||
            policy.equals("PROXY", true) ||
            policy.startsWith("REJECT", true)
        val newPolicy = if (keep) policy else "PROXY"
        return buildString {
            append(indent)
            append("- ")
            append(head)
            append(',')
            append(newPolicy)
            if (noResolve) append(",no-resolve")
        }
    }
}
