package org.olcbox.app.vpn

import android.content.Context
import android.util.Log
import java.io.File

/**
 * Keeps mihomo / update caches from eating hundreds of MB after geo downloads
 * and repeated beta installs.
 */
object PtrkStorageCleanup {
    private const val TAG = "PtrkCleanup"
    private const val PREF = "ptrk_storage_cleanup"
    private const val KEY_LAST_VERSION = "last_cleaned_version"

    fun runOnAppStart(context: Context, appVersion: String) {
        val prefs = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        val last = prefs.getString(KEY_LAST_VERSION, null)
        val force = last != appVersion
        cleanup(context, aggressive = force)
        if (force) {
            prefs.edit().putString(KEY_LAST_VERSION, appVersion).apply()
            Log.i(TAG, "cleanup after version change $last → $appVersion")
        }
    }

    fun cleanup(context: Context, aggressive: Boolean) {
        val filesDir = context.filesDir
        val mihomo = File(filesDir, "mihomo")
        var freed = 0L

        fun deleteFile(f: File) {
            if (!f.exists()) return
            val size = if (f.isFile) f.length() else 0L
            if (f.isFile) {
                if (f.delete()) freed += size
            } else if (f.deleteRecursively()) {
                freed += size
            }
        }

        listOf(
            "GeoIP.dat", "geoip.dat", "geosite.dat", "ASN.mmdb", "country.mmdb",
            "geoip-roscom.dat", "geosite-roscom.dat", "GeoSite.dat.bak", "geoip.metadb.bak",
        ).forEach { deleteFile(File(mihomo, it)) }

        File(mihomo, "GeoSite.dat").takeIf { it.isFile && it.length() > 12_000_000L }?.let(::deleteFile)
        File(mihomo, "geoip.metadb").takeIf { it.isFile && it.length() > 20_000_000L }?.let(::deleteFile)

        File(mihomo, "profiles").listFiles().orEmpty()
            .filter { it.name.contains(".runtime.") && it.isFile }
            .forEach { f ->
                if (aggressive || f.lastModified() < System.currentTimeMillis() - 3L * 24 * 60 * 60 * 1000) {
                    deleteFile(f)
                }
            }

        listOf("updates", "update-cache", "apk-cache").forEach { dirName ->
            File(filesDir, dirName).takeIf { it.exists() }?.let(::deleteFile)
        }

        context.cacheDir.listFiles().orEmpty()
            .filter { it.extension.equals("apk", true) || it.name.contains("update", true) }
            .forEach(::deleteFile)

        runCatching {
            org.olcbox.app.mihomo.MihomoEngine.ensureBundledGeoFiles(context)
        }

        if (freed > 0L) {
            Log.i(TAG, "freed ~${freed / (1024 * 1024)} MB")
        }
    }
}
