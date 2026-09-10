package org.olcbox.app.i18n

import android.content.Context
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.olcbox.app.vpn.data.KEY_APP_LANGUAGE
import org.olcbox.app.vpn.data.KEY_APP_LANGUAGE_SET
import org.olcbox.app.vpn.data.vpnPrefDataStore

object AppLanguageStore {
    suspend fun isLanguageChosen(context: Context): Boolean {
        return context.vpnPrefDataStore.data.map { it[KEY_APP_LANGUAGE_SET] == true }.first()
    }

    suspend fun load(context: Context): AppLanguage? {
        val code = context.vpnPrefDataStore.data.map { it[KEY_APP_LANGUAGE] }.first()
        return AppLanguage.fromCode(code)
    }

    suspend fun save(context: Context, language: AppLanguage) {
        context.vpnPrefDataStore.edit { prefs ->
            prefs[KEY_APP_LANGUAGE] = language.code
            prefs[KEY_APP_LANGUAGE_SET] = true
        }
        AppLocale.set(language)
    }

    suspend fun restoreIntoLocale(context: Context) {
        load(context)?.let { AppLocale.set(it) }
    }
}
