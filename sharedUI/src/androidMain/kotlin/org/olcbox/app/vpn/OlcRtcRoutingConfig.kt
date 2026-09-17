package org.olcbox.app.vpn

import android.content.Context
import java.io.File

/**
 * Minimal Clash profile for olcRTC **fetch only** (subscription / app update).
 *
 * Must NOT import Remnawave rule-providers / GEOIP — those download on startup and
 * fail under whitelist before PROXY is usable.
 *
 * DNS uses fake-ip so CONNECT hostnames are dialed as domains through Mobile SOCKS
 * (redir-host + public DNS often returns wrong/blocked A records on whitelist).
 *
 * libclash cannot share a process with libgojni, so this YAML is loaded in `:route`.
 */
object OlcRtcRoutingConfig {
    const val PROXY_NAME = "olcRTC-tunnel"
    const val MIXED_PORT = 7890
    private const val OUT_NAME = "olcrtc-fetch.yaml"

    fun outputFile(context: Context): File =
        File(context.filesDir, "mihomo/profiles").apply { mkdirs() }.resolve(OUT_NAME)

    /**
     * Everything via olcRTC SOCKS — no DIRECT, no rule-providers, no geoip.
     * Same role as Mihomo mixed-port for OkHttp subscription/update.
     */
    fun buildFetchOnly(
        context: Context,
        olcRtcSocksPort: Int,
        socksUsername: String = "",
        socksPassword: String = "",
    ): File {
        val userEsc = socksUsername.replace("'", "''")
        val passEsc = socksPassword.replace("'", "''")
        val yaml = buildString {
            appendLine("mixed-port: $MIXED_PORT")
            appendLine("allow-lan: false")
            appendLine("mode: global")
            appendLine("log-level: warning")
            appendLine("ipv6: false")
            appendLine("find-process-mode: off")
            appendLine("unified-delay: true")
            appendLine("tcp-concurrent: false")
            appendLine("keep-alive-interval: 15")
            appendLine()
            appendLine("dns:")
            appendLine("  enable: true")
            appendLine("  ipv6: false")
            appendLine("  use-system-hosts: false")
            // fake-ip: keep hostname for SOCKS dial (remote DNS inside olcRTC).
            appendLine("  enhanced-mode: fake-ip")
            appendLine("  fake-ip-range: 198.18.0.1/16")
            appendLine("  fake-ip-filter:")
            appendLine("    - '*.lan'")
            appendLine("    - localhost")
            appendLine("    - '*.local'")
            appendLine("  default-nameserver:")
            appendLine("    - 77.88.8.8")
            appendLine("    - 77.88.8.1")
            appendLine("  nameserver:")
            appendLine("    - 77.88.8.8")
            appendLine("    - 77.88.8.1")
            appendLine()
            appendLine("proxies:")
            appendLine("  - name: $PROXY_NAME")
            appendLine("    type: socks5")
            appendLine("    server: 127.0.0.1")
            appendLine("    port: $olcRtcSocksPort")
            if (socksUsername.isNotBlank()) {
                appendLine("    username: '$userEsc'")
                appendLine("    password: '$passEsc'")
            }
            appendLine("    udp: false")
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
            appendLine("rules:")
            appendLine("  - MATCH,PROXY")
            appendLine()
        }
        val out = outputFile(context)
        out.writeText(yaml)
        return out
    }

    /** @deprecated Use [buildFetchOnly] — full whitelist routing is server-side for olcRTC. */
    fun build(
        context: Context,
        olcRtcSocksPort: Int,
        sourceYaml: String?,
        socksUsername: String = "",
        socksPassword: String = "",
    ): File = buildFetchOnly(context, olcRtcSocksPort, socksUsername, socksPassword)
}
