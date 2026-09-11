package org.olcbox.app.data.identity

import org.olcbox.app.CurrentAppInfo

/**
 * Identity Remnawave / panel / Telegram bot show for this install.
 *
 * - User-Agent: always `PTRK-KVN-app/<appVersion>` (HWID table).
 * - x-device-model: real phone model (panel appends OS in parentheses from x-ver-os).
 * - Clash YAML body still needs a ClashMeta-compatible request; that must not overwrite HWID.
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
