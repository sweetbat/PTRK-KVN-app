package org.olcbox.app.data.identity

import org.olcbox.app.CurrentAppInfo

/**
 * Identity Remnawave / panel / Telegram bot show for this install.
 * Must stay distinct from ClashMeta so HWID slots are labeled correctly.
 */
object RemnawaveDeviceIdentity {
    const val MODEL = "PTRK-KVN-app"

    fun userAgent(version: String = CurrentAppInfo.value.version): String = "$MODEL/$version"
}
