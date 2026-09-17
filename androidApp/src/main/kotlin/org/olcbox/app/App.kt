package org.olcbox.app

import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.os.Build
import android.os.Process
import java.util.Locale
import kotlinx.coroutines.runBlocking
import org.olcbox.app.i18n.AppLanguage
import org.olcbox.app.i18n.AppLanguageStore
import org.olcbox.app.i18n.AppLocale

class App : Application() {
    companion object {
        lateinit var appContext: Context
    }

    override fun onCreate() {
        super.onCreate()
        appContext = applicationContext
        org.olcbox.app.data.mihomo.MihomoAndroidContext.app = applicationContext

        // :vpn / :route / :olcrtc / :mihomo must NOT touch DataStore here.
        // Multi-process DataStore + runBlocking deadlocks → Clash stays status=queued forever.
        if (isSecondaryProcess()) return

        org.olcbox.app.vpn.service.VpnStatusBridge.ensureRegistered(applicationContext)
        runCatching {
            org.olcbox.app.vpn.PtrkStorageCleanup.runOnAppStart(
                applicationContext,
                org.olcbox.app.CurrentAppInfo.value.version,
            )
        }
        // Restore locale before any Activity/Compose first frame — otherwise START /
        // Disconnected flash in English until the first recomposition.
        runBlocking {
            AppLanguageStore.restoreIntoLocale(this@App)
            if (!AppLanguageStore.isLanguageChosen(this@App)) {
                AppLanguage.fromCode(Locale.getDefault().language)?.let { AppLocale.set(it) }
            }
        }
    }

    private fun isSecondaryProcess(): Boolean {
        val name = currentProcessName()
        return name.contains(':')
    }

    private fun currentProcessName(): String {
        if (Build.VERSION.SDK_INT >= 28) {
            return getProcessName()
        }
        val pid = Process.myPid()
        val am = getSystemService(ActivityManager::class.java) ?: return packageName
        return am.runningAppProcesses
            ?.firstOrNull { it.pid == pid }
            ?.processName
            ?: packageName
    }
}
