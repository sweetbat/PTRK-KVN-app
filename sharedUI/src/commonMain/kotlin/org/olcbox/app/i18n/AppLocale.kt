package org.olcbox.app.i18n

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class AppLanguage(val code: String) {
    English("en"),
    Russian("ru");

    companion object {
        fun fromCode(code: String?): AppLanguage? = when (code?.lowercase()) {
            "ru", "ru-ru", "russian" -> Russian
            "en", "en-us", "english" -> English
            else -> null
        }
    }
}

object AppLocale {
    private val _language = MutableStateFlow(AppLanguage.English)
    val language: StateFlow<AppLanguage> = _language.asStateFlow()
    val current: AppLanguage get() = _language.value

    fun set(language: AppLanguage) {
        _language.value = language
    }
}

/** UI copy. Russian uses Unicode escapes so source encoding cannot corrupt glyphs. */
object S {
    private val ru get() = AppLocale.current == AppLanguage.Russian

    val appName get() = "PTRK-KVN"
    val appSubtitle get() = "Mihomo + olcRTC"

    val modeRule get() = if (ru) "\u0412\u043a\u043b\u044e\u0447\u0438\u0442\u044c" else "On"
    val modeGlobal get() = if (ru) "\u0412\u044b\u043a\u043b\u044e\u0447\u0438\u0442\u044c" else "Off"
    val modeDirect get() = modeGlobal
    val routingTitle get() =
        if (ru) "\u041c\u0430\u0440\u0448\u0440\u0443\u0442\u0438\u0437\u0430\u0446\u0438\u044f" else "Routing"
    val routingSummaryOn get() =
        if (ru) "\u0411\u0435\u043b\u044b\u0439 \u0441\u043f\u0438\u0441\u043e\u043a \u041c\u0438\u043d\u0446\u0438\u0444\u0440\u044b \u2014 \u043d\u0430\u043f\u0440\u044f\u043c\u0443\u044e"
        else "Mincifry whitelist goes direct"
    val routingSummaryOff get() =
        if (ru) "\u0412\u0435\u0441\u044c \u0442\u0440\u0430\u0444\u0438\u043a \u0447\u0435\u0440\u0435\u0437 \u0432\u044b\u0431\u0440\u0430\u043d\u043d\u0443\u044e \u043d\u043e\u0434\u0443"
        else "All traffic via selected node"
    val routingSubtitle get() =
        if (ru) "Mihomo"
        else "Mihomo"
    val routingBody get() =
        if (ru) "\u0420\u0430\u0431\u043e\u0442\u0430\u0435\u0442 \u0442\u043e\u043b\u044c\u043a\u043e \u043d\u0430 Mihomo.\n\n\u0412\u043a\u043b\u044e\u0447\u0435\u043d\u043e: \u0431\u0435\u043b\u044b\u0439 \u0441\u043f\u0438\u0441\u043e\u043a \u041c\u0438\u043d\u0446\u0438\u0444\u0440\u044b \u2014 \u0441 \u0434\u043e\u043c\u0430\u0448\u043d\u0435\u0433\u043e IP, \u043e\u0441\u0442\u0430\u043b\u044c\u043d\u043e\u0435 \u2014 \u0447\u0435\u0440\u0435\u0437 \u0432\u044b\u0431\u0440\u0430\u043d\u043d\u0443\u044e \u043d\u043e\u0434\u0443.\n\n\u0412\u044b\u043a\u043b\u044e\u0447\u0435\u043d\u043e: \u0432\u0435\u0441\u044c \u0442\u0440\u0430\u0444\u0438\u043a \u0447\u0435\u0440\u0435\u0437 \u043d\u043e\u0434\u0443.\n\n\u041d\u0430 olcRTC \u043c\u0430\u0440\u0448\u0440\u0443\u0442\u0438\u0437\u0430\u0446\u0438\u044f \u0430\u0432\u0442\u043e\u043c\u0430\u0442\u0438\u0447\u0435\u0441\u043a\u0430\u044f \u043f\u043e \u0431\u0435\u043b\u043e\u043c\u0443 \u0441\u043f\u0438\u0441\u043a\u0443 \u041c\u0438\u043d\u0446\u0438\u0444\u0440\u044b.\n\n\u0422\u043e\u0440\u0440\u0435\u043d\u0442\u044b \u0432\u0441\u0435\u0433\u0434\u0430 \u0438\u0434\u0443\u0442 \u043d\u0430\u043f\u0440\u044f\u043c\u0443\u044e."
        else "Applies to Mihomo only.\n\nOn: Mincifry whitelist uses your home IP; everything else via the selected node.\n\nOff: all traffic via the selected node.\n\nolcRTC routing follows the Mincifry whitelist automatically.\n\nTorrents always use your home IP."



    val start get() = if (ru) "\u0421\u0422\u0410\u0420\u0422" else "START"
    val stop get() = if (ru) "\u0421\u0422\u041e\u041f" else "STOP"
    val setup get() = if (ru) "\u041d\u0410\u0421\u0422\u0420\u041e\u0419\u041a\u0410" else "SETUP"
    val connected get() = if (ru) "\u041f\u043e\u0434\u043a\u043b\u044e\u0447\u0435\u043d\u043e" else "Connected"
    val disconnected get() = if (ru) "\u041e\u0442\u043a\u043b\u044e\u0447\u0435\u043d\u043e" else "Disconnected"

    val subscription get() = if (ru) "\u041f\u043e\u0434\u043f\u0438\u0441\u043a\u0430" else "Subscription"
    val selectedSubscription get() =
        if (ru) "\u0412\u044b\u0431\u0440\u0430\u043d\u043d\u0430\u044f \u043f\u043e\u0434\u043f\u0438\u0441\u043a\u0430" else "Selected subscription"
    val support get() =
        if (ru) "\u041f\u043e\u0434\u0434\u0435\u0440\u0436\u043a\u0430" else "Support"
    val website get() =
        if (ru) "\u0421\u0430\u0439\u0442" else "Website"
    val updateAvailable get() =
        if (ru) "\u0414\u043e\u0441\u0442\u0443\u043f\u043d\u043e \u043e\u0431\u043d\u043e\u0432\u043b\u0435\u043d\u0438\u0435" else "Update available"
    val downloadUpdate get() =
        if (ru) "\u0421\u043a\u0430\u0447\u0430\u0442\u044c" else "Download"
    val later get() =
        if (ru) "\u041f\u043e\u0437\u0436\u0435" else "Later"
    val updateSubscription get() =
        if (ru) "\u041e\u0431\u043d\u043e\u0432\u0438\u0442\u044c \u043f\u043e\u0434\u043f\u0438\u0441\u043a\u0438" else "Update subscriptions"
    val subscriptionUpdated get() =
        if (ru) "\u041f\u043e\u0434\u043f\u0438\u0441\u043a\u0430 \u043e\u0431\u043d\u043e\u0432\u043b\u0435\u043d\u0430" else "Subscription updated"
    val traffic get() = if (ru) "\u0422\u0440\u0430\u0444\u0438\u043a" else "Traffic"
    val status get() = if (ru) "\u0421\u0442\u0430\u0442\u0443\u0441" else "Status"
    val subscriptionExpires get() =
        if (ru) "\u0414\u0435\u0439\u0441\u0442\u0432\u0443\u0435\u0442 \u0434\u043e" else "Expires"

    val language get() = if (ru) "\u042f\u0437\u044b\u043a" else "Language"
    val chooseLanguageTitle get() = if (ru) "\u0412\u044b\u0431\u0435\u0440\u0438\u0442\u0435 \u044f\u0437\u044b\u043a" else "Choose language"
    val chooseLanguageBody get() =
        if (ru) "\u041d\u0430 \u043a\u0430\u043a\u043e\u043c \u044f\u0437\u044b\u043a\u0435 \u043f\u043e\u043a\u0430\u0437\u044b\u0432\u0430\u0442\u044c \u0438\u043d\u0442\u0435\u0440\u0444\u0435\u0439\u0441?"
        else "Which language should the app use?"
    val russian get() = "\uD83C\uDDF7\uD83C\uDDFA  \u0420\u0443\u0441\u0441\u043a\u0438\u0439"
    val english get() = "\uD83C\uDDFA\uD83C\uDDF8  English"
    val continueLabel get() = if (ru) "\u041f\u0440\u043e\u0434\u043e\u043b\u0436\u0438\u0442\u044c" else "Continue"

    val mihomoEngine get() = "Mihomo"
    val olcrtcEngine get() = "olcRTC"
    val noSubscription get() = if (ru) "\u041d\u0435\u0442 \u043f\u043e\u0434\u043f\u0438\u0441\u043a\u0438" else "No subscription"

    val node get() = if (ru) "\u0423\u0437\u0435\u043b" else "Node"
    val bypass get() = if (ru) "\u041e\u0431\u0445\u043e\u0434" else "Bypass"
    val regular get() = if (ru) "\u041e\u0431\u044b\u0447\u043d\u044b\u0435" else "Regular"
    val offline get() = if (ru) "\u041e\u0444\u0444\u043b\u0430\u0439\u043d" else "Offline"
    val ping get() = if (ru) "\u041f\u0438\u043d\u0433" else "Ping"
    val update get() = if (ru) "\u041e\u0431\u043d\u043e\u0432\u0438\u0442\u044c" else "Update"
    val remaining get() = if (ru) "\u043e\u0441\u0442\u0430\u043b\u043e\u0441\u044c" else "remaining"
    val used get() = if (ru) "\u0438\u0441\u043f\u043e\u043b\u044c\u0437\u043e\u0432\u0430\u043d\u043e" else "used"
    val addCustomLocation get() =
        if (ru) "\u0414\u043e\u0431\u0430\u0432\u0438\u0442\u044c \u0441\u0435\u0440\u0432\u0435\u0440" else "Add server"
    val addRelaySetup get() =
        if (ru) "\u0414\u043e\u0431\u0430\u0432\u0438\u0442\u044c \u043d\u0430\u0441\u0442\u0440\u043e\u0439\u043a\u0443" else "Add relay setup"
    val addSubscription get() =
        if (ru) "\u0414\u043e\u0431\u0430\u0432\u0438\u0442\u044c \u043f\u043e\u0434\u043f\u0438\u0441\u043a\u0443" else "Add subscription"
    val addSubscriptionHint get() =
        if (ru) "\u0421\u043a\u0430\u043d QR, \u0432\u0441\u0442\u0430\u0432\u043a\u0430 URI \u0438\u043b\u0438 \u0444\u0430\u0439\u043b"
        else "Scan QR, paste URI, or import file"
    val createCustomLocation get() =
        if (ru) "\u0421\u043e\u0437\u0434\u0430\u0442\u044c \u043b\u043e\u043a\u0430\u0446\u0438\u044e olcRTC" else "Create olcRTC location"
    val createCustomLocationHint get() =
        if (ru) "olcRTC: \u043a\u043e\u043c\u043d\u0430\u0442\u0430, \u043a\u043b\u044e\u0447, \u043f\u0440\u043e\u0432\u0430\u0439\u0434\u0435\u0440 \u0438 \u0442\u0440\u0430\u043d\u0441\u043f\u043e\u0440\u0442"
        else "olcRTC: enter room, key, provider, and transport"
    val refreshPrefix get() = if (ru) "\u041e\u0431\u043d\u043e\u0432\u043b\u0435\u043d\u0438\u0435" else "Refresh"

    // —— Add configuration sheet / import dialog ——
    val addConnection get() =
        if (ru) "\u0414\u043e\u0431\u0430\u0432\u0438\u0442\u044c \u043f\u043e\u0434\u043a\u043b\u044e\u0447\u0435\u043d\u0438\u0435" else "Add connection"
    val addConnectionSubtitle get() =
        if (ru) "\u041f\u043e\u0434\u043f\u0438\u0441\u043a\u0430 \u0438\u043b\u0438 \u0441\u0432\u043e\u044f \u043b\u043e\u043a\u0430\u0446\u0438\u044f"
        else "Subscription or custom location"
    val scanQr get() =
        if (ru) "\u0421\u043a\u0430\u043d\u0438\u0440\u043e\u0432\u0430\u0442\u044c QR-\u043a\u043e\u0434" else "Scan QR code"
    val scanQrHint get() =
        if (ru) "\u041f\u043e\u0434\u043f\u0438\u0441\u043a\u0430 \u0438\u043b\u0438 olcrtc URI" else "Subscription or olcrtc URI"
    val enterLinkOrUri get() =
        if (ru) "\u0412\u0432\u0435\u0441\u0442\u0438 \u0441\u0441\u044b\u043b\u043a\u0443 \u0438\u043b\u0438 URI" else "Enter link or URI"
    val enterLinkOrUriHint get() =
        if (ru) "\u0412\u0432\u043e\u0434, \u043f\u0440\u0430\u0432\u043a\u0430 \u0438\u043b\u0438 \u0438\u043c\u043f\u043e\u0440\u0442 \u0438\u0437 \u0431\u0443\u0444\u0435\u0440\u0430"
        else "Type, edit, or import from clipboard"
    val importFromFile get() =
        if (ru) "\u0418\u043c\u043f\u043e\u0440\u0442 \u0438\u0437 \u0444\u0430\u0439\u043b\u0430" else "Import from file"
    val importFromFileHint get() =
        if (ru) "\u041f\u043e\u0434\u043f\u0438\u0441\u043a\u0430 \u0438\u043b\u0438 \u0444\u0430\u0439\u043b \u043a\u043e\u043d\u0444\u0438\u0433\u0443\u0440\u0430\u0446\u0438\u0438"
        else "Read subscription or config file"
    val updateSubscriptionsHint get() =
        if (ru) "\u041e\u0431\u043d\u043e\u0432\u0438\u0442\u044c \u0438\u043c\u043f\u043e\u0440\u0442\u0438\u0440\u043e\u0432\u0430\u043d\u043d\u044b\u0435 \u043b\u043e\u043a\u0430\u0446\u0438\u0438 \u043f\u043e\u0434\u043f\u0438\u0441\u043e\u043a"
        else "Refresh imported subscription locations"
    val importLinkTitle get() =
        if (ru) "\u0418\u043c\u043f\u043e\u0440\u0442 \u0441\u0441\u044b\u043b\u043a\u0438 \u0438\u043b\u0438 URI" else "Import link or URI"
    val importLinkLabel get() =
        if (ru) "HTTP, HTTPS \u0438\u043b\u0438 olcrtc URI" else "HTTP, HTTPS, or olcrtc URI"
    val importLinkPlaceholder get() = "https://example.org/subscription"
    val subscriptionRefreshRate get() =
        if (ru) "\u0427\u0430\u0441\u0442\u043e\u0442\u0430 \u043e\u0431\u043d\u043e\u0432\u043b\u0435\u043d\u0438\u044f \u043f\u043e\u0434\u043f\u0438\u0441\u043a\u0438"
        else "Subscription refresh rate"
    val subscriptionRefreshOptional get() =
        if (ru) "\u041d\u0435\u043e\u0431\u044f\u0437\u0430\u0442\u0435\u043b\u044c\u043d\u043e. \u041f\u0443\u0441\u0442\u043e\u0435 \u2014 \u043f\u043e \u0443\u043c\u043e\u043b\u0447\u0430\u043d\u0438\u044e."
        else "Optional. Empty implies default."
    val subscriptionRefreshError get() =
        if (ru) "\u0418\u0441\u043f\u043e\u043b\u044c\u0437\u0443\u0439\u0442\u0435 5m\u201330d, \u043d\u0430\u043f\u0440\u0438\u043c\u0435\u0440 10m, 6h \u0438\u043b\u0438 1d"
        else "Use 5m–30d, for example 10m, 6h, or 1d"
    val allowInsecureRequests get() =
        if (ru) "\u0420\u0430\u0437\u0440\u0435\u0448\u0438\u0442\u044c \u043d\u0435\u0431\u0435\u0437\u043e\u043f\u0430\u0441\u043d\u044b\u0435 \u0437\u0430\u043f\u0440\u043e\u0441\u044b"
        else "Allow insecure requests"
    val pasteFromClipboard get() =
        if (ru) "\u0412\u0441\u0442\u0430\u0432\u0438\u0442\u044c \u0438\u0437 \u0431\u0443\u0444\u0435\u0440\u0430" else "Paste from clipboard"
    val importAction get() =
        if (ru) "\u0418\u043c\u043f\u043e\u0440\u0442" else "Import"
    fun subscriptionsUpdated(count: Int): String =
        if (ru) "\u041f\u043e\u0434\u043f\u0438\u0441\u043a\u0438 \u043e\u0431\u043d\u043e\u0432\u043b\u0435\u043d\u044b: $count"
        else "Subscriptions updated: $count"
    val noSubscriptionsToUpdate get() =
        if (ru) "\u041d\u0435\u0442 \u043f\u043e\u0434\u043f\u0438\u0441\u043e\u043a \u0434\u043b\u044f \u043e\u0431\u043d\u043e\u0432\u043b\u0435\u043d\u0438\u044f"
        else "No subscriptions to update"
    val subscriptionsUpToDate get() =
        if (ru) "\u041f\u043e\u0434\u043f\u0438\u0441\u043a\u0438 \u0430\u043a\u0442\u0443\u0430\u043b\u044c\u043d\u044b" else "Subscriptions are up to date"
    fun couldNotUpdateSubscription(message: String): String =
        if (ru) "\u041d\u0435 \u0443\u0434\u0430\u043b\u043e\u0441\u044c \u043e\u0431\u043d\u043e\u0432\u0438\u0442\u044c \u043f\u043e\u0434\u043f\u0438\u0441\u043a\u0443: $message"
        else "Could not update subscription: $message"
    val configurationImported get() =
        if (ru) "\u041a\u043e\u043d\u0444\u0438\u0433\u0443\u0440\u0430\u0446\u0438\u044f \u0438\u043c\u043f\u043e\u0440\u0442\u0438\u0440\u043e\u0432\u0430\u043d\u0430"
        else "Configuration imported"
    val importBodyNotSupported get() =
        if (ru) "\u0421\u0435\u0440\u0432\u0435\u0440 \u043e\u0442\u0432\u0435\u0442\u0438\u043b, \u043d\u043e \u0442\u0435\u043b\u043e \u043d\u0435 olcRTC \u0438 \u043d\u0435 Clash/Mihomo YAML"
        else "The server responded, but the body is not olcRTC or Clash/Mihomo YAML"
    val importBodyUriDump get() =
        if (ru) "\u0421\u0435\u0440\u0432\u0435\u0440 \u043e\u0442\u0434\u0430\u043b \u0441\u043f\u0438\u0441\u043e\u043a URI \u0432\u043c\u0435\u0441\u0442\u043e Clash YAML. \u041f\u043e\u043f\u0440\u043e\u0431\u0443\u0439\u0442\u0435 \u0435\u0449\u0451 \u0440\u0430\u0437 \u0438\u043b\u0438 \u043e\u0431\u043d\u043e\u0432\u0438\u0442\u0435 \u043f\u0440\u0438\u043b\u043e\u0436\u0435\u043d\u0438\u0435."
        else "Server returned a URI list instead of Clash YAML. Try again or update the app."
    val importTextNotSupported get() =
        if (ru) "\u0422\u0435\u043a\u0441\u0442 \u043d\u0435 \u044f\u0432\u043b\u044f\u0435\u0442\u0441\u044f \u043f\u043e\u0434\u0434\u0435\u0440\u0436\u0438\u0432\u0430\u0435\u043c\u043e\u0439 \u043a\u043e\u043d\u0444\u0438\u0433\u0443\u0440\u0430\u0446\u0438\u0435\u0439 PTRK-KVN"
        else "The text is not a supported PTRK-KVN configuration"

    fun localizeDataUnit(raw: String): String {
        if (!ru) return raw
        return raw
            .replace(Regex("""(?i)ms\b"""), "\u043c\u0441")
            .replace(Regex("""(?i)mb\b"""), "\u043c\u0431")
            .replace(Regex("""(?i)gb\b"""), "\u0433\u0431")
            .replace(Regex("""(?i)kb\b"""), "\u043a\u0431")
            .replace(Regex("""(?i)tb\b"""), "\u0442\u0431")
            // Glued units from formatBytes: "126MB", "0B"
            .replace(Regex("""(?i)(\d+(?:\.\d+)?)mb\b"""), "$1\u043c\u0431")
            .replace(Regex("""(?i)(\d+(?:\.\d+)?)gb\b"""), "$1\u0433\u0431")
            .replace(Regex("""(?i)(\d+(?:\.\d+)?)kb\b"""), "$1\u043a\u0431")
            .replace(Regex("""(?i)(\d+(?:\.\d+)?)tb\b"""), "$1\u0442\u0431")
            .replace(Regex("""(?i)(\d+(?:\.\d+)?)b\b"""), "$1\u0431")
            .replace(Regex("""(?i)(\d+)\s*h\b"""), "$1\u0447")
            .replace(Regex("""(?i)(\d+)\s*m\b"""), "$1\u043c\u0438\u043d")
            .replace(Regex("""(?i)(\d+)\s*d\b"""), "$1\u0434")
    }

    fun trafficRemainingLabel(available: String): String {
        if (isUnlimitedTraffic(available)) {
            return if (ru) "\u221e \u043e\u0441\u0442\u0430\u043b\u043e\u0441\u044c" else "∞ remaining"
        }
        val unit = localizeDataUnit(available)
        return if (ru) "$unit \u043e\u0441\u0442\u0430\u043b\u043e\u0441\u044c" else "$unit remaining"
    }

    fun trafficSummary(used: String, available: String): String {
        val usedParts = used.split('/', limit = 2).map { it.trim() }
        val usedPart = localizeDataUnit(usedParts.getOrNull(0).orEmpty())
        val totalFromUsed = usedParts.getOrNull(1)?.let { localizeDataUnit(it) }.orEmpty()
        val unlimited = isUnlimitedTraffic(available) || isUnlimitedTraffic(totalFromUsed)
        val availLabel = when {
            unlimited -> "\u221e"
            available.isNotBlank() -> localizeDataUnit(available)
            totalFromUsed.isNotBlank() -> totalFromUsed
            else -> ""
        }
        return when {
            usedPart.isNotBlank() && availLabel.isNotBlank() -> "$usedPart/$availLabel"
            usedPart.isNotBlank() -> usedPart
            else -> localizeDataUnit(listOfNotNull(used, available).joinToString("/"))
        }
    }

    private fun isUnlimitedTraffic(value: String): Boolean {
        val v = value.trim()
        if (v.isEmpty()) return false
        if (v == "∞" || v == "\u221e" || v.equals("unlimited", true)) return true
        // Remnawave / Clash: total=0 means unlimited → we format as "0B" / "0MB".
        return Regex("""(?i)^0(\.0+)?\s*(b|kb|mb|gb|tb)?$""").matches(v)
    }

    // —— Settings hub ——
    val applicationSettings get() =
        if (ru) "\u041d\u0430\u0441\u0442\u0440\u043e\u0439\u043a\u0438 \u043f\u0440\u0438\u043b\u043e\u0436\u0435\u043d\u0438\u044f" else "Application Settings"
    val dynamicTheme get() =
        if (ru) "\u0422\u0435\u043c\u0430 \u043f\u0440\u0438\u043b\u043e\u0436\u0435\u043d\u0438\u044f" else "App theme"
    val usingAndroidSystemColors get() =
        if (ru) "\u0426\u0432\u0435\u0442\u0430 \u0441\u0438\u0441\u0442\u0435\u043c\u044b Android" else "Android system colors"
    val usingSystemColors get() =
        if (ru) "\u0446\u0432\u0435\u0442\u0430 PTRK-KVN" else "PTRK-KVN colors"
    val connectionSettings get() =
        if (ru) "\u041d\u0430\u0441\u0442\u0440\u043e\u0439\u043a\u0438 \u043f\u043e\u0434\u043a\u043b\u044e\u0447\u0435\u043d\u0438\u044f" else "Connection Settings"
    val connectionSettingsSummary get() =
        if (ru) "\u0420\u0435\u0436\u0438\u043c, SOCKS5 \u0438 \u043c\u0430\u0440\u0448\u0440\u0443\u0442\u0438\u0437\u0430\u0446\u0438\u044f \u043f\u0440\u0438\u043b\u043e\u0436\u0435\u043d\u0438\u0439"
        else "Mode, SOCKS5 proxy, and app routing"
    val subscriptionsSharing get() =
        if (ru) "\u041f\u043e\u0434\u043f\u0438\u0441\u043a\u0438 \u0438 \u043e\u0431\u043c\u0435\u043d" else "Subscriptions & Sharing"
    val updateSettings get() =
        if (ru) "\u041d\u0430\u0441\u0442\u0440\u043e\u0439\u043a\u0438 \u043e\u0431\u043d\u043e\u0432\u043b\u0435\u043d\u0438\u0439" else "Update Settings"
    val applicationLogs get() =
        if (ru) "\u0416\u0443\u0440\u043d\u0430\u043b \u043f\u0440\u0438\u043b\u043e\u0436\u0435\u043d\u0438\u044f" else "Application Logs"
    val diagnosticsAndExport get() =
        if (ru) "\u0414\u0438\u0430\u0433\u043d\u043e\u0441\u0442\u0438\u043a\u0430 \u0438 \u044d\u043a\u0441\u043f\u043e\u0440\u0442" else "Diagnostics and export"

    val connectionMode get() =
        if (ru) "\u0420\u0435\u0436\u0438\u043c \u043f\u043e\u0434\u043a\u043b\u044e\u0447\u0435\u043d\u0438\u044f" else "Connection Mode"
    val socks5Proxy get() =
        if (ru) "\u041f\u0440\u043e\u043a\u0441\u0438 SOCKS5" else "SOCKS5 Proxy"
    val splitTunneling get() =
        if (ru) "\u0420\u0430\u0437\u0434\u0435\u043b\u044c\u043d\u043e\u0435 \u0442\u0443\u043d\u043d\u0435\u043b\u0438\u0440\u043e\u0432\u0430\u043d\u0438\u0435" else "Split Tunneling"

    val updates get() = if (ru) "\u041e\u0431\u043d\u043e\u0432\u043b\u0435\u043d\u0438\u044f" else "Updates"
    fun currentVersion(version: String): String =
        if (ru) "\u0422\u0435\u043a\u0443\u0449\u0430\u044f \u0432\u0435\u0440\u0441\u0438\u044f $version" else "Current version $version"
    val checkInterval get() =
        if (ru) "\u0418\u043d\u0442\u0435\u0440\u0432\u0430\u043b \u043f\u0440\u043e\u0432\u0435\u0440\u043a\u0438" else "Check Interval"
    val lastCheck get() =
        if (ru) "\u041f\u043e\u0441\u043b\u0435\u0434\u043d\u044f\u044f \u043f\u0440\u043e\u0432\u0435\u0440\u043a\u0430" else "Last check"
    val notCheckedYet get() =
        if (ru) "\u0415\u0449\u0451 \u043d\u0435 \u043f\u0440\u043e\u0432\u0435\u0440\u044f\u043b\u043e\u0441\u044c" else "Not checked yet"
    val checkForUpdates get() =
        if (ru) "\u041f\u0440\u043e\u0432\u0435\u0440\u0438\u0442\u044c \u043e\u0431\u043d\u043e\u0432\u043b\u0435\u043d\u0438\u044f" else "Check for updates"
    val checkNow get() =
        if (ru) "\u041f\u0440\u043e\u0432\u0435\u0440\u0438\u0442\u044c \u0441\u0435\u0439\u0447\u0430\u0441" else "Check now"

    val channelStable get() =
        if (ru) "\u0421\u0442\u0430\u0431\u0438\u043b\u044c\u043d\u044b\u0439" else "Stable"
    val channelBeta get() =
        if (ru) "\u0411\u0435\u0442\u0430" else "Beta"
    val channelNightly get() = channelBeta

    /** [channelName] is a display label such as [channelStable] / [channelBeta]. */
    fun channelSummary(channelName: String, hours: Int): String =
        if (ru) "$channelName · \u043a\u0430\u0436\u0434\u044b\u0435 ${hours}\u0447" else "$channelName · every ${hours}h"

    /** Prefer this when callers have a beta flag and should not import update types. */
    fun updateChannelSummary(isBeta: Boolean, hours: Int): String =
        if (isBeta) betaEveryHours(hours) else stableEveryHours(hours)

    fun stableEveryHours(hours: Int): String = channelSummary(channelStable, hours)
    fun betaEveryHours(hours: Int): String = channelSummary(channelBeta, hours)
    fun nightlyEveryHours(hours: Int): String = channelSummary(channelBeta, hours)

    fun checkingChannel(channelLabel: String): String =
        if (ru) "\u041f\u0440\u043e\u0432\u0435\u0440\u043a\u0430 $channelLabel..." else "Checking $channelLabel..."
    val upToDate get() =
        if (ru) "\u0423 \u0432\u0430\u0441 \u0430\u043a\u0442\u0443\u0430\u043b\u044c\u043d\u0430\u044f \u0432\u0435\u0440\u0441\u0438\u044f" else "PTRK-KVN is up to date"
    val updateServiceUnavailable get() =
        if (ru) "\u0421\u0435\u0440\u0432\u0438\u0441 \u043e\u0431\u043d\u043e\u0432\u043b\u0435\u043d\u0438\u0439 \u043d\u0435\u0434\u043e\u0441\u0442\u0443\u043f\u0435\u043d"
        else "Update service unavailable"

    val subscriptions get() = if (ru) "\u041f\u043e\u0434\u043f\u0438\u0441\u043a\u0438" else "Subscriptions"
    val noSubscriptions get() = if (ru) "\u041d\u0435\u0442 \u043f\u043e\u0434\u043f\u0438\u0441\u043e\u043a" else "No subscriptions"
    fun subscriptionCount(count: Int): String = when {
        count <= 0 -> noSubscriptions
        count == 1 -> if (ru) "1 \u043f\u043e\u0434\u043f\u0438\u0441\u043a\u0430" else "1 subscription"
        else -> if (ru) "$count \u043f\u043e\u0434\u043f\u0438\u0441\u043e\u043a" else "$count subscriptions"
    }

    val deleteSubscription get() =
        if (ru) "\u0423\u0434\u0430\u043b\u0438\u0442\u044c \u043f\u043e\u0434\u043f\u0438\u0441\u043a\u0443" else "Delete subscription"
    val deleteSubscriptionTitle get() =
        if (ru) "\u0423\u0434\u0430\u043b\u0438\u0442\u044c \u043f\u043e\u0434\u043f\u0438\u0441\u043a\u0443?" else "Delete subscription?"
    fun deleteSubscriptionBody(name: String, locationLabel: String): String =
        if (ru) {
            "\u0411\u0443\u0434\u0435\u0442 \u0443\u0434\u0430\u043b\u0435\u043d\u0430 \u00ab$name\u00bb \u0438 $locationLabel, " +
                "\u0438\u043c\u043f\u043e\u0440\u0442\u0438\u0440\u043e\u0432\u0430\u043d\u043d\u044b\u0435 \u0438\u0437 \u043d\u0435\u0451. " +
                "\u042d\u0442\u043e \u0434\u0435\u0439\u0441\u0442\u0432\u0438\u0435 \u043d\u0435\u043b\u044c\u0437\u044f \u043e\u0442\u043c\u0435\u043d\u0438\u0442\u044c."
        } else {
            "This will delete “$name” and $locationLabel imported from it. This cannot be undone."
        }
    fun locationCountLabel(count: Int): String =
        if (count == 1) {
            if (ru) "1 \u043b\u043e\u043a\u0430\u0446\u0438\u044f" else "1 location"
        } else {
            if (ru) "$count \u043b\u043e\u043a\u0430\u0446\u0438\u0439" else "$count locations"
        }
    fun removeSubscriptionAndLocations(locationLabel: String): String =
        if (ru) "\u0423\u0434\u0430\u043b\u0438\u0442\u044c \u0435\u0451 \u0438 $locationLabel"
        else "Remove it and $locationLabel"
    val delete get() = if (ru) "\u0423\u0434\u0430\u043b\u0438\u0442\u044c" else "Delete"
    val cancel get() = if (ru) "\u041e\u0442\u043c\u0435\u043d\u0430" else "Cancel"
    val save get() = if (ru) "\u0421\u043e\u0445\u0440\u0430\u043d\u0438\u0442\u044c" else "Save"

    // —— Split tunneling ——
    val routingBehavior get() =
        if (ru) "\u041f\u043e\u0432\u0435\u0434\u0435\u043d\u0438\u0435 \u043c\u0430\u0440\u0448\u0440\u0443\u0442\u0438\u0437\u0430\u0446\u0438\u0438" else "Routing Behavior"
    val appsUsingVpn get() =
        if (ru) "\u041f\u0440\u0438\u043b\u043e\u0436\u0435\u043d\u0438\u044f \u0447\u0435\u0440\u0435\u0437 VPN" else "Apps using VPN"
    val bypassedApps get() =
        if (ru) "\u041f\u0440\u0438\u043b\u043e\u0436\u0435\u043d\u0438\u044f \u0432 \u043e\u0431\u0445\u043e\u0434\u0435" else "Bypassed Apps"
    val noSystemAppsFound get() =
        if (ru) "\u0421\u0438\u0441\u0442\u0435\u043c\u043d\u044b\u0435 \u043f\u0440\u0438\u043b\u043e\u0436\u0435\u043d\u0438\u044f \u043d\u0435 \u043d\u0430\u0439\u0434\u0435\u043d\u044b" else "No system apps found"
    fun appsIncluded(label: String): String =
        if (ru) "$label \u0432\u043a\u043b\u044e\u0447\u0435\u043d\u043e" else "$label included"
    fun appsHiddenByDefault(label: String): String =
        if (ru) "$label \u0441\u043a\u0440\u044b\u0442\u043e \u043f\u043e \u0443\u043c\u043e\u043b\u0447\u0430\u043d\u0438\u044e" else "$label hidden by default"
    val searchApps get() =
        if (ru) "\u041f\u043e\u0438\u0441\u043a \u043f\u0440\u0438\u043b\u043e\u0436\u0435\u043d\u0438\u0439" else "Search apps"
    val showSystemApps get() =
        if (ru) "\u041f\u043e\u043a\u0430\u0437\u044b\u0432\u0430\u0442\u044c \u0441\u0438\u0441\u0442\u0435\u043c\u043d\u044b\u0435" else "Show system apps"
    val noAppsFound get() =
        if (ru) "\u041f\u0440\u0438\u043b\u043e\u0436\u0435\u043d\u0438\u044f \u043d\u0435 \u043d\u0430\u0439\u0434\u0435\u043d\u044b" else "No apps found"
    val noMatchingApps get() =
        if (ru) "\u041d\u0435\u0442 \u043f\u043e\u0434\u0445\u043e\u0434\u044f\u0449\u0438\u0445 \u043f\u0440\u0438\u043b\u043e\u0436\u0435\u043d\u0438\u0439" else "No matching apps"
    val installAppsToConfigure get() =
        if (ru) "\u0423\u0441\u0442\u0430\u043d\u043e\u0432\u0438\u0442\u0435 \u043f\u0440\u0438\u043b\u043e\u0436\u0435\u043d\u0438\u044f \u0441 \u044f\u0440\u043b\u044b\u043a\u043e\u043c, \u0447\u0442\u043e\u0431\u044b \u043d\u0430\u0441\u0442\u0440\u043e\u0438\u0442\u044c \u043c\u0430\u0440\u0448\u0440\u0443\u0442\u0438\u0437\u0430\u0446\u0438\u044e."
        else "Install launchable apps to configure routing rules."
    val tryAnotherAppName get() =
        if (ru) "\u041f\u043e\u043f\u0440\u043e\u0431\u0443\u0439\u0442\u0435 \u0434\u0440\u0443\u0433\u043e\u0435 \u0438\u043c\u044f \u0438\u043b\u0438 \u043f\u0430\u043a\u0435\u0442."
        else "Try another app name or package."
    val noAppListNeeded get() =
        if (ru) "\u0421\u043f\u0438\u0441\u043e\u043a \u043f\u0440\u0438\u043b\u043e\u0436\u0435\u043d\u0438\u0439 \u043d\u0435 \u043d\u0443\u0436\u0435\u043d" else "No app list needed"
    val everyAppSameTunRoute get() =
        if (ru) "\u0412\u0441\u0435 \u043f\u0440\u0438\u043b\u043e\u0436\u0435\u043d\u0438\u044f \u0438\u0434\u0443\u0442 \u043f\u043e \u043e\u0434\u043d\u043e\u043c\u0443 \u043c\u0430\u0440\u0448\u0440\u0443\u0442\u0443 TUN"
        else "Every app follows the same TUN route"
    val appList get() =
        if (ru) "\u0421\u043f\u0438\u0441\u043e\u043a \u043f\u0440\u0438\u043b\u043e\u0436\u0435\u043d\u0438\u0439" else "App List"
    val allApps get() =
        if (ru) "\u0412\u0441\u0435 \u043f\u0440\u0438\u043b\u043e\u0436\u0435\u043d\u0438\u044f" else "All apps"
    val selectedAppsOnly get() =
        if (ru) "\u0422\u043e\u043b\u044c\u043a\u043e \u0432\u044b\u0431\u0440\u0430\u043d\u043d\u044b\u0435" else "Selected apps only"
    val bypassSelectedApps get() =
        if (ru) "\u041e\u0431\u0445\u043e\u0434 \u0432\u044b\u0431\u0440\u0430\u043d\u043d\u044b\u0445" else "Bypass selected apps"
    val allAppsTitle get() =
        if (ru) "\u0412\u0441\u0435 \u043f\u0440\u0438\u043b\u043e\u0436\u0435\u043d\u0438\u044f" else "All Apps"
    val selectedAppsOnlyTitle get() =
        if (ru) "\u0422\u043e\u043b\u044c\u043a\u043e \u0432\u044b\u0431\u0440\u0430\u043d\u043d\u044b\u0435" else "Selected Apps Only"
    val bypassSelectedTitle get() =
        if (ru) "\u041e\u0431\u0445\u043e\u0434 \u0432\u044b\u0431\u0440\u0430\u043d\u043d\u044b\u0445" else "Bypass Selected"
    val everyAppUsesVpn get() =
        if (ru) "\u0412\u0441\u0435 \u043f\u0440\u0438\u043b\u043e\u0436\u0435\u043d\u0438\u044f \u0438\u0441\u043f\u043e\u043b\u044c\u0437\u0443\u044e\u0442 VPN" else "Every app uses the VPN"
    val chooseAppsUsingVpn get() =
        if (ru) "\u0412\u044b\u0431\u0435\u0440\u0438\u0442\u0435 \u043f\u0440\u0438\u043b\u043e\u0436\u0435\u043d\u0438\u044f \u0434\u043b\u044f VPN" else "Choose apps that use the VPN"
    val chooseAppsBypassVpn get() =
        if (ru) "\u0412\u044b\u0431\u0435\u0440\u0438\u0442\u0435 \u043f\u0440\u0438\u043b\u043e\u0436\u0435\u043d\u0438\u044f \u0434\u043b\u044f \u043e\u0431\u0445\u043e\u0434\u0430" else "Choose apps that bypass the VPN"
    val allAppsUseVpn get() =
        if (ru) "\u0412\u0441\u0435 \u043f\u0440\u0438\u043b\u043e\u0436\u0435\u043d\u0438\u044f \u0438\u0441\u043f\u043e\u043b\u044c\u0437\u0443\u044e\u0442 VPN" else "All apps use the VPN"
    val noAppsSelected get() =
        if (ru) "\u041f\u0440\u0438\u043b\u043e\u0436\u0435\u043d\u0438\u044f \u043d\u0435 \u0432\u044b\u0431\u0440\u0430\u043d\u044b" else "No apps selected"
    val noAppsBypassVpn get() =
        if (ru) "\u041d\u0435\u0442 \u043f\u0440\u0438\u043b\u043e\u0436\u0435\u043d\u0438\u0439 \u0432 \u043e\u0431\u0445\u043e\u0434\u0435" else "No apps bypass the VPN"
    fun onlyAppCount(countLabel: String): String =
        if (ru) "\u0422\u043e\u043b\u044c\u043a\u043e $countLabel" else "Only $countLabel"
    fun appsBypassed(countLabel: String): String =
        if (ru) "$countLabel \u0432 \u043e\u0431\u0445\u043e\u0434\u0435" else "$countLabel bypassed"
    fun appsUseVpn(countLabel: String): String =
        if (ru) "$countLabel \u0438\u0441\u043f\u043e\u043b\u044c\u0437\u0443\u044e\u0442 VPN" else "$countLabel use the VPN"
    fun appsBypassVpn(countLabel: String): String =
        if (ru) "$countLabel \u043e\u0431\u0445\u043e\u0434\u044f\u0442 VPN" else "$countLabel bypass the VPN"
    fun onlyAppsUseVpn(countLabel: String): String =
        if (ru) "\u0422\u043e\u043b\u044c\u043a\u043e $countLabel \u0438\u0441\u043f\u043e\u043b\u044c\u0437\u0443\u044e\u0442 VPN" else "Only $countLabel use the VPN"
    fun appCount(count: Int): String =
        if (count == 1) {
            if (ru) "1 \u043f\u0440\u0438\u043b\u043e\u0436\u0435\u043d\u0438\u0435" else "1 app"
        } else {
            if (ru) "$count \u043f\u0440\u0438\u043b\u043e\u0436\u0435\u043d\u0438\u0439" else "$count apps"
        }
    val required get() = if (ru) "\u041e\u0431\u044f\u0437\u0430\u0442\u0435\u043b\u044c\u043d\u043e" else "Required"
    val noBypassedApps get() =
        if (ru) "\u041d\u0435\u0442 \u043f\u0440\u0438\u043b\u043e\u0436\u0435\u043d\u0438\u0439 \u0432 \u043e\u0431\u0445\u043e\u0434\u0435" else "No bypassed apps"
    val savedForTunMode get() =
        if (ru) "\u0421\u043e\u0445\u0440\u0430\u043d\u0435\u043d\u043e \u0434\u043b\u044f \u0440\u0435\u0436\u0438\u043c\u0430 TUN" else "Saved for TUN mode"
    val appliesWhenSettingsCloses get() =
        if (ru) "\u041f\u0440\u0438\u043c\u0435\u043d\u0438\u0442\u0441\u044f \u043f\u0440\u0438 \u0437\u0430\u043a\u0440\u044b\u0442\u0438\u0438 \u043d\u0430\u0441\u0442\u0440\u043e\u0435\u043a" else "Applies when settings closes"
    val tunModeRoutingRule get() =
        if (ru) "\u041f\u0440\u0430\u0432\u0438\u043b\u043e \u043c\u0430\u0440\u0448\u0440\u0443\u0442\u0438\u0437\u0430\u0446\u0438\u0438 TUN" else "TUN mode routing rule"
    val ruBypassOn get() =
        if (ru) "\u041e\u0431\u0445\u043e\u0434 RU \u0432\u043a\u043b." else "RU bypass on"
    val bypassRuApps get() =
        if (ru) "\u041e\u0431\u0445\u043e\u0434 RU-\u043f\u0440\u0438\u043b\u043e\u0436\u0435\u043d\u0438\u0439" else "Bypass RU apps"
    val autoDetectionMayBeInaccurate get() =
        if (ru) "\u0410\u0432\u0442\u043e\u043e\u043f\u0440\u0435\u0434\u0435\u043b\u0435\u043d\u0438\u0435 \u043c\u043e\u0436\u0435\u0442 \u0431\u044b\u0442\u044c \u043d\u0435\u0442\u043e\u0447\u043d\u044b\u043c."
        else "Auto-detection may be inaccurate."
    val noMatchingInstalledApps get() =
        if (ru) "\u041d\u0435\u0442 \u043f\u043e\u0434\u0445\u043e\u0434\u044f\u0449\u0438\u0445 \u0443\u0441\u0442\u0430\u043d\u043e\u0432\u043b\u0435\u043d\u043d\u044b\u0445 \u043f\u0440\u0438\u043b\u043e\u0436\u0435\u043d\u0438\u0439"
        else "No matching installed apps"
    fun matchedByPackage(countLabel: String): String =
        if (ru) "$countLabel \u043f\u043e \u0441\u043e\u0432\u043f\u0430\u0434\u0435\u043d\u0438\u044e \u043f\u0430\u043a\u0435\u0442\u0430" else "$countLabel matched by package"
    val noRuAppsSelected get() =
        if (ru) "RU-\u043f\u0440\u0438\u043b\u043e\u0436\u0435\u043d\u0438\u044f \u043d\u0435 \u0432\u044b\u0431\u0440\u0430\u043d\u044b" else "No RU apps selected"
    fun alreadySelected(countLabel: String): String =
        if (ru) "$countLabel \u0443\u0436\u0435 \u0432\u044b\u0431\u0440\u0430\u043d\u043e" else "$countLabel already selected"
    fun autoBypassed(countLabel: String): String =
        if (ru) "$countLabel \u0432 \u0430\u0432\u0442\u043e-\u043e\u0431\u0445\u043e\u0434\u0435" else "$countLabel auto-bypassed"
    fun autoManualMix(autoCount: Int, manualCount: Int): String =
        if (ru) "$autoCount \u0430\u0432\u0442\u043e \u00b7 $manualCount \u0432\u0440\u0443\u0447\u043d\u0443\u044e"
        else "$autoCount auto · $manualCount manual"

    // —— Logs ——
    val noEntries get() = if (ru) "\u041d\u0435\u0442 \u0437\u0430\u043f\u0438\u0441\u0435\u0439" else "No entries"
    fun entriesCount(count: Int): String =
        if (ru) "$count \u0437\u0430\u043f\u0438\u0441\u0435\u0439" else "$count entries"
    val share get() = if (ru) "\u041f\u043e\u0434\u0435\u043b\u0438\u0442\u044c\u0441\u044f" else "Share"

    // —— Subscriptions management ——
    val importSubscriptionHint get() =
        if (ru) "\u0418\u043c\u043f\u043e\u0440\u0442\u0438\u0440\u0443\u0439\u0442\u0435 \u043f\u043e\u0434\u043f\u0438\u0441\u043a\u0443 \u0441 \u0433\u043b\u0430\u0432\u043d\u043e\u0433\u043e \u044d\u043a\u0440\u0430\u043d\u0430, \u0447\u0442\u043e\u0431\u044b \u0443\u043f\u0440\u0430\u0432\u043b\u044f\u0442\u044c \u0435\u0439 \u0437\u0434\u0435\u0441\u044c."
        else "Import a subscription from the home screen to manage it here."
    val backupAndExport get() =
        if (ru) "\u0420\u0435\u0437\u0435\u0440\u0432\u043d\u0430\u044f \u043a\u043e\u043f\u0438\u044f \u0438 \u044d\u043a\u0441\u043f\u043e\u0440\u0442" else "Backup & export"
    val exportFullConfiguration get() =
        if (ru) "\u042d\u043a\u0441\u043f\u043e\u0440\u0442 \u043f\u043e\u043b\u043d\u043e\u0439 \u043a\u043e\u043d\u0444\u0438\u0433\u0443\u0440\u0430\u0446\u0438\u0438" else "Export full configuration"
    val copyAllLocationsToClipboard get() =
        if (ru) "\u0421\u043a\u043e\u043f\u0438\u0440\u043e\u0432\u0430\u0442\u044c \u0432\u0441\u0435 \u043b\u043e\u043a\u0430\u0446\u0438\u0438 \u0432 \u0431\u0443\u0444\u0435\u0440" else "Copy all locations to clipboard"
    val overview get() = if (ru) "\u041e\u0431\u0437\u043e\u0440" else "Overview"
    val source get() = if (ru) "\u0418\u0441\u0442\u043e\u0447\u043d\u0438\u043a" else "Source"
    val refreshSchedule get() =
        if (ru) "\u0420\u0430\u0441\u043f\u0438\u0441\u0430\u043d\u0438\u0435 \u043e\u0431\u043d\u043e\u0432\u043b\u0435\u043d\u0438\u044f" else "Refresh schedule"
    val chooseRefreshScheduleBody get() =
        if (ru) "\u0412\u044b\u0431\u0435\u0440\u0438\u0442\u0435, \u043a\u0430\u043a \u0447\u0430\u0441\u0442\u043e \u043f\u0440\u043e\u0432\u0435\u0440\u044f\u0442\u044c \u044d\u0442\u0443 \u043f\u043e\u0434\u043f\u0438\u0441\u043a\u0443."
        else "Choose how often this subscription should be checked."
    val auto get() = if (ru) "\u0410\u0432\u0442\u043e" else "Auto"
    val customInterval get() =
        if (ru) "\u0421\u0432\u043e\u0439 \u0438\u043d\u0442\u0435\u0440\u0432\u0430\u043b" else "Custom interval"
    val customIntervalHint get() =
        if (ru) "\u0418\u0441\u043f\u043e\u043b\u044c\u0437\u0443\u0439\u0442\u0435 5m\u201330d, \u043d\u0430\u043f\u0440\u0438\u043c\u0435\u0440 10m, 6h \u0438\u043b\u0438 1d"
        else "Use 5m–30d, for example 10m, 6h, or 1d"
    val customIntervalOverrides get() =
        if (ru) "\u0421\u0432\u043e\u0439 \u0438\u043d\u0442\u0435\u0440\u0432\u0430\u043b \u043f\u0435\u0440\u0435\u043e\u043f\u0440\u0435\u0434\u0435\u043b\u044f\u0435\u0442 \u0437\u043d\u0430\u0447\u0435\u043d\u0438\u0435 \u0438\u0437 \u043f\u043e\u0434\u043f\u0438\u0441\u043a\u0438."
        else "A custom interval overrides the subscription value."
    val locations get() = if (ru) "\u041b\u043e\u043a\u0430\u0446\u0438\u0438" else "Locations"
    val updated get() = if (ru) "\u041e\u0431\u043d\u043e\u0432\u043b\u0435\u043d\u043e" else "Updated"
    val notYet get() = if (ru) "\u0415\u0449\u0451 \u043d\u0435\u0442" else "Not yet"
    val next get() = if (ru) "\u0414\u0430\u043b\u0435\u0435" else "Next"
    val onAppStart get() =
        if (ru) "\u041f\u0440\u0438 \u0437\u0430\u043f\u0443\u0441\u043a\u0435 \u043f\u0440\u0438\u043b\u043e\u0436\u0435\u043d\u0438\u044f" else "On app start"
    val refreshing get() =
        if (ru) "\u041e\u0431\u043d\u043e\u0432\u043b\u0435\u043d\u0438\u0435\u2026" else "Refreshing…"
    val refreshNow get() =
        if (ru) "\u041e\u0431\u043d\u043e\u0432\u0438\u0442\u044c \u0441\u0435\u0439\u0447\u0430\u0441" else "Refresh now"
    val subscriptionLink get() =
        if (ru) "\u0421\u0441\u044b\u043b\u043a\u0430 \u043d\u0430 \u043f\u043e\u0434\u043f\u0438\u0441\u043a\u0443" else "Subscription link"
    val shareSubscription get() =
        if (ru) "\u041f\u043e\u0434\u0435\u043b\u0438\u0442\u044c\u0441\u044f \u043f\u043e\u0434\u043f\u0438\u0441\u043a\u043e\u0439" else "Share subscription"
    val automatic get() =
        if (ru) "\u0410\u0432\u0442\u043e\u043c\u0430\u0442\u0438\u0447\u0435\u0441\u043a\u0438" else "Automatic"
    val customSchedule get() =
        if (ru) "\u0421\u0432\u043e\u0451 \u0440\u0430\u0441\u043f\u0438\u0441\u0430\u043d\u0438\u0435" else "Custom schedule"
    val usesSubscriptionSchedule get() =
        if (ru) "\u041f\u043e \u0440\u0430\u0441\u043f\u0438\u0441\u0430\u043d\u0438\u044e \u043f\u043e\u0434\u043f\u0438\u0441\u043a\u0438" else "Uses the subscription schedule"
    fun autoUsesSchedule(schedule: String): String =
        if (ru) "\u0410\u0432\u0442\u043e: $schedule." else "Auto uses $schedule."
    val autoUsesDefaultSchedule get() =
        if (ru) "\u0410\u0432\u0442\u043e \u0438\u0441\u043f\u043e\u043b\u044c\u0437\u0443\u0435\u0442 \u0440\u0430\u0441\u043f\u0438\u0441\u0430\u043d\u0438\u0435 \u043f\u043e \u0443\u043c\u043e\u043b\u0447\u0430\u043d\u0438\u044e."
        else "Auto uses default schedule."
    fun updatedRelative(time: String): String =
        if (ru) "\u041e\u0431\u043d\u043e\u0432\u043b\u0435\u043d\u043e $time" else "Updated $time"
    val notUpdatedYet get() =
        if (ru) "\u0415\u0449\u0451 \u043d\u0435 \u043e\u0431\u043d\u043e\u0432\u043b\u044f\u043b\u043e\u0441\u044c" else "Not updated yet"
    val subscriptionSource get() =
        if (ru) "\u0418\u0441\u0442\u043e\u0447\u043d\u0438\u043a \u043f\u043e\u0434\u043f\u0438\u0441\u043a\u0438" else "Subscription source"
    val everyDay get() = if (ru) "\u041a\u0430\u0436\u0434\u044b\u0439 \u0434\u0435\u043d\u044c" else "Every day"
    fun everyNDays(n: Long): String =
        if (ru) "\u041a\u0430\u0436\u0434\u044b\u0435 $n \u0434\u043d." else "Every $n days"
    val everyHour get() = if (ru) "\u041a\u0430\u0436\u0434\u044b\u0439 \u0447\u0430\u0441" else "Every hour"
    fun everyNHours(n: Long): String =
        if (ru) "\u041a\u0430\u0436\u0434\u044b\u0435 $n \u0447" else "Every $n hours"
    val everyMinute get() = if (ru) "\u041a\u0430\u0436\u0434\u0443\u044e \u043c\u0438\u043d\u0443\u0442\u0443" else "Every minute"
    fun everyNMinutes(n: Long): String =
        if (ru) "\u041a\u0430\u0436\u0434\u044b\u0435 $n \u043c\u0438\u043d" else "Every $n minutes"
    val justNow get() = if (ru) "\u0442\u043e\u043b\u044c\u043a\u043e \u0447\u0442\u043e" else "just now"
    fun inRelative(time: String): String =
        if (ru) "\u0447\u0435\u0440\u0435\u0437 $time" else "in $time"
    fun agoRelative(time: String): String =
        if (ru) "$time \u043d\u0430\u0437\u0430\u0434" else "$time ago"

    // —— SOCKS proxy ——
    val endpoint get() = if (ru) "\u0410\u0434\u0440\u0435\u0441" else "Endpoint"
    val listenAddress get() =
        if (ru) "\u0410\u0434\u0440\u0435\u0441 \u043f\u0440\u043e\u0441\u043b\u0443\u0448\u0438\u0432\u0430\u043d\u0438\u044f" else "Listen address"
    val listenAddressRequired get() =
        if (ru) "\u0423\u043a\u0430\u0436\u0438\u0442\u0435 \u0430\u0434\u0440\u0435\u0441 \u043f\u0440\u043e\u0441\u043b\u0443\u0448\u0438\u0432\u0430\u043d\u0438\u044f" else "Listen address is required"
    val savingRestartsConnection get() =
        if (ru) "\u0421\u043e\u0445\u0440\u0430\u043d\u0435\u043d\u0438\u0435 \u043f\u0435\u0440\u0435\u0437\u0430\u043f\u0443\u0441\u0442\u0438\u0442 \u0430\u043a\u0442\u0438\u0432\u043d\u043e\u0435 \u043f\u043e\u0434\u043a\u043b\u044e\u0447\u0435\u043d\u0438\u0435"
        else "Saving restarts the active connection"
    val unsavedChange get() =
        if (ru) "\u041d\u0435\u0441\u043e\u0445\u0440\u0430\u043d\u0451\u043d\u043d\u043e\u0435 \u0438\u0437\u043c\u0435\u043d\u0435\u043d\u0438\u0435" else "Unsaved change"
    val port get() = if (ru) "\u041f\u043e\u0440\u0442" else "Port"
    val portRequired get() =
        if (ru) "\u0423\u043a\u0430\u0436\u0438\u0442\u0435 \u043f\u043e\u0440\u0442" else "Port is required"
    fun portRangeHint(range: String): String =
        if (ru) "\u0418\u0441\u043f\u043e\u043b\u044c\u0437\u0443\u0439\u0442\u0435 $range" else "Use $range"
    val credentials get() =
        if (ru) "\u0423\u0447\u0451\u0442\u043d\u044b\u0435 \u0434\u0430\u043d\u043d\u044b\u0435" else "Credentials"
    val username get() =
        if (ru) "\u0418\u043c\u044f \u043f\u043e\u043b\u044c\u0437\u043e\u0432\u0430\u0442\u0435\u043b\u044f" else "Username"
    val usernameRequired get() =
        if (ru) "\u0423\u043a\u0430\u0436\u0438\u0442\u0435 \u0438\u043c\u044f \u043f\u043e\u043b\u044c\u0437\u043e\u0432\u0430\u0442\u0435\u043b\u044f" else "Username is required"
    val password get() = if (ru) "\u041f\u0430\u0440\u043e\u043b\u044c" else "Password"
    val generatedPassword get() =
        if (ru) "\u0421\u0433\u0435\u043d\u0435\u0440\u0438\u0440\u043e\u0432\u0430\u043d\u043d\u044b\u0439 \u043f\u0430\u0440\u043e\u043b\u044c" else "Generated password"
    val passwordRequired get() =
        if (ru) "\u0423\u043a\u0430\u0436\u0438\u0442\u0435 \u043f\u0430\u0440\u043e\u043b\u044c" else "Password is required"
    val regeneratePassword get() =
        if (ru) "\u0421\u0433\u0435\u043d\u0435\u0440\u0438\u0440\u043e\u0432\u0430\u0442\u044c \u043f\u0430\u0440\u043e\u043b\u044c \u0437\u0430\u043d\u043e\u0432\u043e" else "Regenerate password"

    // —— Connection mode labels ——
    val proxy get() = if (ru) "\u041f\u0440\u043e\u043a\u0441\u0438" else "Proxy"
    val fullTunnel get() =
        if (ru) "\u041f\u043e\u043b\u043d\u044b\u0439 \u0442\u0443\u043d\u043d\u0435\u043b\u044c" else "Full tunnel"
    val localSocks5Proxy get() =
        if (ru) "\u041b\u043e\u043a\u0430\u043b\u044c\u043d\u044b\u0439 \u043f\u0440\u043e\u043a\u0441\u0438 SOCKS5" else "Local SOCKS5 proxy"
    val tunFullTunnel get() =
        if (ru) "TUN \u00b7 \u041f\u043e\u043b\u043d\u044b\u0439 \u0442\u0443\u043d\u043d\u0435\u043b\u044c" else "TUN · Full tunnel"
    val proxyLocalSocks5 get() =
        if (ru) "\u041f\u0440\u043e\u043a\u0441\u0438 \u00b7 \u041b\u043e\u043a\u0430\u043b\u044c\u043d\u044b\u0439 SOCKS5" else "Proxy · Local SOCKS5"
    val systemVpnInterface get() =
        if (ru) "\u0421\u0438\u0441\u0442\u0435\u043c\u043d\u044b\u0439 VPN-\u0438\u043d\u0442\u0435\u0440\u0444\u0435\u0439\u0441" else "System VPN interface"
    val localSocksEndpoint get() =
        if (ru) "\u041b\u043e\u043a\u0430\u043b\u044c\u043d\u0430\u044f \u043a\u043e\u043d\u0435\u0447\u043d\u0430\u044f \u0442\u043e\u0447\u043a\u0430 SOCKS" else "Local SOCKS endpoint"

    // —— App bar / a11y ——
    val history get() = if (ru) "\u0418\u0441\u0442\u043e\u0440\u0438\u044f" else "History"
    val back get() = if (ru) "\u041d\u0430\u0437\u0430\u0434" else "Back"
    val addConfiguration get() =
        if (ru) "\u0414\u043e\u0431\u0430\u0432\u0438\u0442\u044c \u043a\u043e\u043d\u0444\u0438\u0433\u0443\u0440\u0430\u0446\u0438\u044e" else "Add configuration"

    // —— Beta updates (easter-egg) ——
    val seekBeta get() =
        if (ru) "\u0418\u0441\u043a\u0430\u0442\u044c \u0431\u0435\u0442\u0430" else "Seek beta"
    val betaUpdates get() =
        if (ru) "\u0411\u0435\u0442\u0430-\u043e\u0431\u043d\u043e\u0432\u043b\u0435\u043d\u0438\u044f" else "Beta updates"
    val betaEnabled get() =
        if (ru) "\u0411\u0435\u0442\u0430 \u0432\u043a\u043b\u044e\u0447\u0435\u043d\u0430" else "Beta enabled"
    val betaDisabled get() =
        if (ru) "\u0411\u0435\u0442\u0430 \u0432\u044b\u043a\u043b\u044e\u0447\u0435\u043d\u0430" else "Beta disabled"
    val betaHint get() =
        if (ru) "\u041d\u0430\u0436\u043c\u0438\u0442\u0435 \u043d\u0430 \u0432\u0435\u0440\u0441\u0438\u044e 7 \u0440\u0430\u0437, \u0447\u0442\u043e\u0431\u044b \u043e\u0442\u043a\u0440\u044b\u0442\u044c \u0431\u0435\u0442\u0430-\u043e\u0431\u043d\u043e\u0432\u043b\u0435\u043d\u0438\u044f"
        else "Tap the version number 7 times to unlock beta updates"
}
