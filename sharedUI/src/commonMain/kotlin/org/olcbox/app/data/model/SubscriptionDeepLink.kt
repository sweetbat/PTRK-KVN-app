package org.olcbox.app.data.model

/**
 * Deep links from Remnawave subscription page / QR.
 *
 * Remnawave app entry `urlScheme` should be: `ptrkkvn://add/`
 * → opens `ptrkkvn://add/https://drink.ptrkkvn.beer/mug/{token}`
 * (same Happ-style concatenation). Mihomo YAML is imported from that HTTPS URL;
 * [PtrkSubscriptionCompanion] also pulls `olcsub.ptrkkvn.beer/{token}`.
 */
object SubscriptionDeepLink {
    private val ADD_PREFIX = Regex("""(?i)^ptrkkvn://add/(.+)$""")
    private val QUERY_URL = Regex(
        """(?i)^ptrkkvn://(?:install-config|import)\?.*?[?&]?url=([^&#]+)""",
    )
    private val BARE_HTTPS = Regex("""(?i)^ptrkkvn://(https?://.+)$""")

    fun extractSubscriptionUrl(raw: String?): String? {
        val trimmed = raw?.trim()?.takeIf { it.isNotBlank() } ?: return null
        ADD_PREFIX.find(trimmed)?.groupValues?.getOrNull(1)?.let { return decodeCandidate(it) }
        QUERY_URL.find(trimmed)?.groupValues?.getOrNull(1)?.let { return decodeCandidate(it) }
        BARE_HTTPS.find(trimmed)?.groupValues?.getOrNull(1)?.let { return decodeCandidate(it) }
        // Already a normal subscription URL pasted into the handler.
        if (trimmed.startsWith("http://", ignoreCase = true) ||
            trimmed.startsWith("https://", ignoreCase = true)
        ) {
            return trimmed
        }
        return null
    }

    private fun decodeCandidate(value: String): String? {
        var current = value.trim().trimStart('/')
        if (current.isEmpty()) return null
        // Remnawave / browsers may percent-encode the nested https URL once or twice.
        repeat(2) {
            val decoded = runCatching { decodeUriComponent(current) }.getOrNull() ?: return@repeat
            if (decoded == current) return@repeat
            current = decoded.trim()
        }
        return current.takeIf {
            it.startsWith("http://", ignoreCase = true) ||
                it.startsWith("https://", ignoreCase = true)
        }
    }

    private fun decodeUriComponent(value: String): String {
        val out = StringBuilder(value.length)
        var i = 0
        while (i < value.length) {
            val c = value[i]
            if (c == '%' && i + 2 < value.length) {
                val hex = value.substring(i + 1, i + 3)
                val code = hex.toIntOrNull(16)
                if (code != null) {
                    out.append(code.toChar())
                    i += 3
                    continue
                }
            }
            if (c == '+') {
                out.append(' ')
                i++
                continue
            }
            out.append(c)
            i++
        }
        return out.toString()
    }
}
