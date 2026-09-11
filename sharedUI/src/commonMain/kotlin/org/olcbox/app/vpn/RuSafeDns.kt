package org.olcbox.app.vpn

/**
 * DNS helpers for RU networks where TSPU hijacks plain UDP to Google/Cloudflare
 * (8.8.8.8 / 1.1.1.1 and siblings). Prefer carrier resolvers, then Yandex / AdGuard.
 * DoH over HTTPS (TCP) is listed for Clash nameserver — TSPU intercept is UDP-only.
 */
object RuSafeDns {
    val YANDEX_PLAIN = listOf("77.88.8.8", "77.88.8.1")
    val ADGUARD_PLAIN = listOf("94.140.14.14", "94.140.15.15")

    /** HTTPS DoH — not subject to the UDP TSPU hijack of 8.8.8.8 / 1.1.1.1. */
    val YANDEX_DOH = listOf(
        "https://dns.yandex.ru/dns-query",
        "https://common.dot.dns.yandex.net/dns-query",
    )

    private val HIJACKED = setOf(
        "8.8.8.8", "8.8.4.4",
        "1.1.1.1", "1.0.0.1", "1.1.1.2", "1.0.0.2",
        "2001:4860:4860::8888", "2001:4860:4860::8844",
        "2606:4700:4700::1111", "2606:4700:4700::1001",
        "2606:4700:4700::1112", "2606:4700:4700::1002",
    )

    fun isHijacked(address: String): Boolean {
        val normalized = address.trim().lowercase()
            .substringBefore('%')
            .removePrefix("[")
            .let { if (']' in it) it.substringBefore(']') else it }
            // Strip :port only for IPv4 "a.b.c.d:53"
            .let { raw ->
                if (raw.count { it == ':' } == 1 && '.' in raw) raw.substringBefore(':') else raw
            }
        return normalized in HIJACKED ||
            normalized.startsWith("2001:4860:4860:") ||
            normalized.startsWith("2606:4700:4700:")
    }

    fun sanitize(servers: List<String>): List<String> =
        servers.map { it.trim() }
            .filter { it.isNotBlank() && it != "0.0.0.0" && it != "::" }
            .filterNot { isHijacked(it) }
            .distinct()

    /** Plain UDP resolvers safe to use before the tunnel is up. */
    fun plainBootstrap(preferred: List<String> = emptyList()): List<String> =
        (sanitize(preferred) + YANDEX_PLAIN + ADGUARD_PLAIN).distinct()

    /**
     * Clash `nameserver` list: DoH (TCP) first, then plain Yandex/carrier.
     * `default-nameserver` / `proxy-server-nameserver` should stay plain-only.
     */
    fun clashNameservers(preferred: List<String> = emptyList()): List<String> =
        (YANDEX_DOH + plainBootstrap(preferred)).distinct()
}
