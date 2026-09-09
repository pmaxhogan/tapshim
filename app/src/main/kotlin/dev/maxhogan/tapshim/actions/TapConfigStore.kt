package dev.maxhogan.tapshim.actions

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "tapshim")

class TapConfigStore(private val dataStore: DataStore<Preferences>) {

    val config: Flow<TapConfig> = dataStore.data.map { it.toConfig() }

    suspend fun current(): TapConfig = config.first()

    suspend fun update(transform: (TapConfig) -> TapConfig) {
        dataStore.edit { prefs ->
            val next = transform(prefs.toConfig())
            prefs[LAUNCH_APP_ENABLED] = next.launchAppEnabled
            prefs[LAUNCH_APP_PACKAGE] = next.launchAppPackage
            prefs[BROADCAST_ENABLED] = next.broadcastEnabled
            prefs[OPEN_URL_ENABLED] = next.openUrlEnabled
            prefs[URL] = next.url
            prefs[OPEN_ACTIVITY_ENABLED] = next.openActivityEnabled
            prefs[ACTIVITY_COMPONENT] = next.activityComponent
        }
    }

    private fun Preferences.toConfig(): TapConfig {
        val d = TapConfig()
        return TapConfig(
            launchAppEnabled = this[LAUNCH_APP_ENABLED] ?: d.launchAppEnabled,
            launchAppPackage = this[LAUNCH_APP_PACKAGE] ?: d.launchAppPackage,
            broadcastEnabled = this[BROADCAST_ENABLED] ?: d.broadcastEnabled,
            openUrlEnabled = this[OPEN_URL_ENABLED] ?: d.openUrlEnabled,
            url = this[URL] ?: d.url,
            openActivityEnabled = this[OPEN_ACTIVITY_ENABLED] ?: d.openActivityEnabled,
            activityComponent = this[ACTIVITY_COMPONENT] ?: d.activityComponent,
        )
    }

    companion object {
        /** The app-wide store backed by the process singleton DataStore. */
        fun from(context: Context): TapConfigStore = TapConfigStore(context.applicationContext.dataStore)

        private val LAUNCH_APP_ENABLED = booleanPreferencesKey("launch_app_enabled")
        private val LAUNCH_APP_PACKAGE = stringPreferencesKey("launch_app_package")
        private val BROADCAST_ENABLED = booleanPreferencesKey("broadcast_enabled")
        private val OPEN_URL_ENABLED = booleanPreferencesKey("open_url_enabled")
        private val URL = stringPreferencesKey("url")
        private val OPEN_ACTIVITY_ENABLED = booleanPreferencesKey("open_activity_enabled")
        private val ACTIVITY_COMPONENT = stringPreferencesKey("activity_component")
    }
}
