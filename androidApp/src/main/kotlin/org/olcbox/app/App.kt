package org.olcbox.app

import android.app.Application
import android.content.Context
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
        // Restore locale before any Activity/Compose first frame — otherwise START /
        // Disconnected flash in English until the first recomposition.
        runBlocking {
            AppLanguageStore.restoreIntoLocale(this@App)
            if (!AppLanguageStore.isLanguageChosen(this@App)) {
                AppLanguage.fromCode(Locale.getDefault().language)?.let { AppLocale.set(it) }
            }
        }
    }
}
