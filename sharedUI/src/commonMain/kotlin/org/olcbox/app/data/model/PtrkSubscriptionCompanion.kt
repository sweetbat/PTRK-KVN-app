package org.olcbox.app.data.model

/**
 * PTRK Remnawave (drink) subscriptions share a short token with olcRTC (olcsub).
 * Importing `https://drink.ptrkkvn.beer/mug/{token}` should also pull
 * `https://olcsub.ptrkkvn.beer/{token}`.
 *
 * One olcsub container can expose several UI "servers" via `##exit` (de/pl/fi);
 * the client switches SOCKS with `POST /{uuid}/exit` before connecting.
 */
object PtrkSubscriptionCompanion {
    private val DRINK_MUG = Regex(
        pattern = """(?i)^https?://drink\.ptrkkvn\.beer/mug/([A-Za-z0-9_-]+)/?(?:\?.*)?(?:#.*)?$""",
    )
    private val OLCSUB = Regex(
        pattern = """(?i)^https?://olcsub\.ptrkkvn\.beer/([A-Za-z0-9_-]+)/?(?:exit)?/?(?:\?.*)?(?:#.*)?$""",
    )

    val ALLOWED_EXITS: Set<String> = setOf("de", "pl", "fi")

    fun olcRtcCompanionUrl(subscriptionUrl: String?): String? {
        val raw = subscriptionUrl?.trim()?.takeIf { it.isNotBlank() } ?: return null
        val match = DRINK_MUG.find(raw) ?: return null
        val token = match.groupValues[1].trim()
        if (token.isEmpty()) return null
        return "https://olcsub.ptrkkvn.beer/$token"
    }

    fun isOlcSubUrl(subscriptionUrl: String?): Boolean {
        val raw = subscriptionUrl?.trim()?.lowercase() ?: return false
        return raw.startsWith("https://olcsub.ptrkkvn.beer/") ||
            raw.startsWith("http://olcsub.ptrkkvn.beer/")
    }

    /** Base `https://olcsub.ptrkkvn.beer/{uuid}` for drink or olcsub subscription URLs. */
    fun olcSubBaseUrl(subscriptionUrl: String?): String? {
        val raw = subscriptionUrl?.trim()?.takeIf { it.isNotBlank() } ?: return null
        olcRtcCompanionUrl(raw)?.let { return it }
        val match = OLCSUB.find(raw) ?: return null
        val token = match.groupValues[1].trim()
        if (token.isEmpty()) return null
        return "https://olcsub.ptrkkvn.beer/$token"
    }

    fun exitRequestUrl(subscriptionUrl: String?): String? {
        val base = olcSubBaseUrl(subscriptionUrl) ?: return null
        return "$base/exit"
    }

    fun normalizeExitCountry(value: String?): String? {
        val exit = value?.trim()?.lowercase()?.takeIf { it.isNotBlank() } ?: return null
        return exit.takeIf { it in ALLOWED_EXITS }
    }
}
