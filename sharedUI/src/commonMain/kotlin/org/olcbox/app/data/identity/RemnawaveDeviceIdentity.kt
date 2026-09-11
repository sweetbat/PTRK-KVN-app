package org.olcbox.app.data.identity

import org.olcbox.app.CurrentAppInfo

/**
 * Identity Remnawave / panel / Telegram bot show for this install.
 *
 * - User-Agent: `PTRK-KVN-app/<appVersion>` (External Squad + HWID table).
 * - Subscription download prefers that UA with `?flag=meta` and `x-hwid` so the
 *   panel does not return the "Приложение не поддерживается" stub.
 * - x-device-model: real phone model (panel may append OS from x-ver-os).
 */
object RemnawaveDeviceIdentity {
    const val APP_NAME = "PTRK-KVN-app"

    /** @deprecated Use [APP_NAME]; kept so older call sites compile during rename. */
    const val MODEL = APP_NAME

    fun userAgent(version: String = CurrentAppInfo.value.version): String = "$APP_NAME/$version"

    /** Real handset model for Remnawave `x-device-model`. */
    fun deviceModel(): String = platformDeviceModel()

    /** Android/iOS release string for Remnawave `x-ver-os`. */
    fun osVersion(): String = platformOsVersion()
}

internal expect fun platformDeviceModel(): String

internal expect fun platformOsVersion(): String
