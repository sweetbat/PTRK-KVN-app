package org.olcbox.app.data.model

/**
 * PTRK Remnawave (drink) subscriptions share a short token with olcRTC (olcsub).
 * Importing `https://drink.ptrkkvn.beer/mug/{token}` should also pull
 * `https://olcsub.ptrkkvn.beer/{token}`.
 */
object PtrkSubscriptionCompanion {
    private val DRINK_MUG = Regex(
        pattern = """(?i)^https?://drink\.ptrkkvn\.beer/mug/([A-Za-z0-9_-]+)/?(?:\?.*)?(?:#.*)?$""",
    )

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
}
