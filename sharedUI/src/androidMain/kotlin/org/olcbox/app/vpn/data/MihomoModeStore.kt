package org.olcbox.app.vpn.data

import android.content.Context
import java.io.File

/**
 * Cross-process routing mode. DataStore is process-local in memory, so the UI
 * process can write `global` while `:vpn` still reads a cached `rule`. A plain
 * file is always re-read from disk by every process.
 */
object MihomoModeStore {
    private const val FILE_NAME = "mihomo_mode.txt"

    fun normalize(raw: String?): String {
        return when (raw?.lowercase()?.trim()) {
            "global", "direct" -> "global"
            else -> "rule"
        }
    }

    fun write(context: Context, mode: String) {
        val normalized = normalize(mode)
        runCatching {
            File(context.filesDir, FILE_NAME).writeText(normalized)
        }
    }

    fun exists(context: Context): Boolean =
        File(context.filesDir, FILE_NAME).isFile

    /** Always re-reads the file from disk (no in-memory cache). */
    fun read(context: Context): String {
        return normalize(
            runCatching {
                File(context.filesDir, FILE_NAME).takeIf { it.isFile }?.readText()
            }.getOrNull(),
        )
    }

    /**
     * Intent extra → file → DataStore. Seeds the file when DataStore has a value
     * so `:vpn` does not depend on a stale in-memory DataStore cache.
     * Does not write `rule` from a null DataStore (avoids racing ahead of prefs load).
     */
    fun resolve(context: Context, intentExtra: String? = null, dataStoreValue: String? = null): String {
        intentExtra?.trim()?.takeIf { it.isNotBlank() }?.let { raw ->
            val mode = normalize(raw)
            write(context, mode)
            return mode
        }
        if (exists(context)) return read(context)
        if (dataStoreValue != null) {
            val mode = normalize(dataStoreValue)
            write(context, mode)
            return mode
        }
        return "rule"
    }
}
