package org.olcbox.app.data.datasource

import org.olcbox.app.data.model.ClashYaml

/**
 * Remnawave External Squad returns a Clash YAML stub whose only proxy is named
 * "Приложение не поддерживается" when User-Agent is not allowlisted. Treat that
 * (and English variants) as unusable so we retry with PTRK-KVN-app + HWID.
 */
internal fun isRemnawaveClientRejectedBody(text: String): Boolean {
    val lower = text.lowercase()
    return REJECT_MARKERS.any { it in lower }
}

internal fun isUsableSubscriptionBody(text: String): Boolean {
    if (text.contains("olcrtc://", ignoreCase = true)) return true
    val looksClash = ClashYaml.looksLikeClash(text) ||
        text.contains("proxies:", ignoreCase = true) ||
        text.contains("proxy-groups:", ignoreCase = true) ||
        text.contains("mixed-port:", ignoreCase = true)
    if (!looksClash) return false
    return !isRemnawaveClientRejectedBody(text)
}

private val REJECT_MARKERS = listOf(
    "приложение не поддерживается",
    "app is not supported",
    "application is not supported",
    "client is not supported",
    "device is not supported",
    "not supported by this subscription",
)
