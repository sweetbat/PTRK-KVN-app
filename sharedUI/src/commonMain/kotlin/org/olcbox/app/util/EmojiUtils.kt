package org.olcbox.app.util

/**
 * Extracts a leading flag/emoji and the remaining display name.
 * Country flags are two regional-indicator codepoints — both must be consumed.
 */
fun parseEmojiAndName(rawName: String, defaultEmoji: String = ""): Pair<String, String> {
    if (rawName.isBlank()) return normalizeFlagToken(defaultEmoji) to ""

    val trimmed = rawName.trim()
    val flagFromStart = extractLeadingFlag(trimmed)
    if (flagFromStart != null) {
        val (flag, rest) = flagFromStart
        return flag to rest.trim()
    }

    val inferred = inferFlagFromName(trimmed)
    if (inferred != null) {
        return inferred to trimmed
    }

    return normalizeFlagToken(defaultEmoji) to trimmed
}

/** ISO-3166 alpha-2 → regional-indicator flag emoji. */
fun isoToFlagEmoji(iso: String): String? {
    val code = iso.trim().uppercase()
    if (code.length != 2) return null
    if (!code[0].isLetter() || !code[1].isLetter()) return null
    val first = 0x1F1E6 + (code[0].code - 'A'.code)
    val second = 0x1F1E6 + (code[1].code - 'A'.code)
    return String(intArrayOf(first, second), 0, 2)
}

fun normalizeFlagToken(token: String): String {
    if (token.isBlank()) return ""
    extractLeadingFlag(token.trim())?.first?.let { return it }
    isoToFlagEmoji(token)?.let { return it }
    return token.takeIf { it.length <= 8 }.orEmpty()
}

private fun extractLeadingFlag(text: String): Pair<String, String>? {
    if (text.isEmpty()) return null
    val cps = text.codePoints().toArray()
    if (cps.isEmpty()) return null

    // Full flag: two regional indicators
    if (cps.size >= 2 && isRegionalIndicator(cps[0]) && isRegionalIndicator(cps[1])) {
        val flag = String(cps, 0, 2)
        val rest = String(cps, 2, cps.size - 2)
        return flag to rest
    }

    // Single non-flag emoji / pictograph
    if (isEmojiCodePoint(cps[0]) && !isRegionalIndicator(cps[0])) {
        val emoji = String(cps, 0, 1)
        val rest = String(cps, 1, cps.size - 1)
        return emoji to rest
    }

    return null
}

private fun isRegionalIndicator(cp: Int): Boolean = cp in 0x1F1E6..0x1F1FF

private fun isEmojiCodePoint(cp: Int): Boolean =
    cp in 0x1F300..0x1FAFF ||
        cp in 0x2600..0x27BF ||
        cp in 0x1F600..0x1F64F ||
        cp in 0x1F680..0x1F6FF

private fun inferFlagFromName(name: String): String? {
    val lower = name.lowercase()
    val map = listOf(
        "\u0433\u0435\u0440\u043c\u0430\u043d" to "DE", // герман
        "germany" to "DE",
        "\u0444\u0438\u043d\u043b\u044f\u043d" to "FI", // финлян
        "finland" to "FI",
        "\u043f\u043e\u043b\u044c\u0448" to "PL", // польш
        "poland" to "PL",
        "\u043d\u0438\u0434\u0435\u0440\u043b" to "NL", // нидерл
        "netherlands" to "NL",
        "holland" to "NL",
        "\u0444\u0440\u0430\u043d\u0446" to "FR", // франц
        "france" to "FR",
        "\u0441\u0448\u0430" to "US",
        "united states" to "US",
        "america" to "US",
        "\u0430\u043c\u0435\u0440\u0438\u043a" to "US",
        "\u0431\u0440\u0438\u0442\u0430\u043d" to "GB",
        "united kingdom" to "GB",
        "england" to "GB",
        "\u0440\u043e\u0441\u0441" to "RU", // росс
        "russia" to "RU",
        "\u043e\u0431\u0445\u043e\u0434" to "RU", // обход
        "\u0442\u0443\u0440\u0446" to "TR",
        "turkey" to "TR",
        "\u043b\u0430\u0442\u0432" to "LV",
        "latvia" to "LV",
        "\u043b\u0438\u0442\u0432" to "LT",
        "lithuania" to "LT",
        "\u044d\u0441\u0442\u043e\u043d" to "EE",
        "estonia" to "EE",
        "\u0448\u0432\u0435\u0446" to "SE",
        "sweden" to "SE",
        "\u0447\u0435\u0445" to "CZ",
        "czech" to "CZ",
        "\u0430\u0432\u0441\u0442\u0440" to "AT",
        "austria" to "AT",
        "\u0438\u0442\u0430\u043b" to "IT",
        "italy" to "IT",
        "\u0438\u0441\u043f\u0430\u043d" to "ES",
        "spain" to "ES",
        "\u043a\u0430\u043d\u0430\u0434" to "CA",
        "canada" to "CA",
        "\u044f\u043f\u043e\u043d" to "JP",
        "japan" to "JP",
        "\u043a\u043e\u0440\u0435" to "KR",
        "korea" to "KR",
        "\u0441\u0438\u043d\u0433\u0430\u043f" to "SG",
        "singapore" to "SG",
        "\u043d\u043e\u0440\u0432\u0435\u0433" to "NO",
        "norway" to "NO",
        "\u0448\u0432\u0435\u0439\u0446" to "CH",
        "swiss" to "CH",
        "switzerland" to "CH",
    )
    for ((needle, iso) in map) {
        if (lower.contains(needle)) return isoToFlagEmoji(iso)
    }
    // [DE] / DE- style codes in name
    Regex("""\b([A-Za-z]{2})\b""").findAll(name).forEach { m ->
        val iso = m.groupValues[1].uppercase()
        if (iso in KNOWN_ISO) return isoToFlagEmoji(iso)
    }
    return null
}

private val KNOWN_ISO = setOf(
    "DE", "FI", "PL", "NL", "FR", "US", "GB", "RU", "TR", "LV", "LT", "EE",
    "SE", "CZ", "AT", "IT", "ES", "CA", "JP", "KR", "SG", "NO", "CH", "UA",
    "BY", "KZ", "AE", "HK", "TW", "CN", "IN", "BR", "AU", "NZ", "IE", "PT",
)
