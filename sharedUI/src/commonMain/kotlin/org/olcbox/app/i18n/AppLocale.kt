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

    val modeRule get() = if (ru) "\u041f\u043e \u043f\u0440\u0430\u0432\u0438\u043b\u0430\u043c" else "Rule"
    val modeGlobal get() = if (ru) "\u0413\u043b\u043e\u0431\u0430\u043b\u044c\u043d\u044b\u0439" else "Global"
    val modeDirect get() = if (ru) "\u041f\u0440\u044f\u043c\u043e\u0439" else "Direct"

    val start get() = if (ru) "\u0421\u0422\u0410\u0420\u0422" else "START"
    val stop get() = if (ru) "\u0421\u0422\u041e\u041f" else "STOP"
    val setup get() = if (ru) "\u041d\u0410\u0421\u0422\u0420\u041e\u0419\u041a\u0410" else "SETUP"
    val connected get() = if (ru) "\u041f\u043e\u0434\u043a\u043b\u044e\u0447\u0435\u043d\u043e" else "Connected"
    val disconnected get() = if (ru) "\u041e\u0442\u043a\u043b\u044e\u0447\u0435\u043d\u043e" else "Disconnected"

    val subscription get() = if (ru) "\u041f\u043e\u0434\u043f\u0438\u0441\u043a\u0430" else "Subscription"
    val updateSubscription get() = if (ru) "\u041e\u0431\u043d\u043e\u0432\u0438\u0442\u044c \u043f\u043e\u0434\u043f\u0438\u0441\u043a\u0443" else "Update subscription"
    val traffic get() = if (ru) "\u0422\u0440\u0430\u0444\u0438\u043a" else "Traffic"
    val status get() = if (ru) "\u0421\u0442\u0430\u0442\u0443\u0441" else "Status"

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
    val addCustomLocation get() = if (ru) "+\u00a0\u0414\u043e\u0431\u0430\u0432\u0438\u0442\u044c \u0441\u0435\u0440\u0432\u0435\u0440" else "+ Add custom location"
    val refreshPrefix get() = if (ru) "\u041e\u0431\u043d\u043e\u0432\u043b\u0435\u043d\u0438\u0435" else "Refresh"
}
