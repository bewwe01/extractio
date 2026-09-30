package app.saveit.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import app.saveit.core.download.FilenameTemplate
import app.saveit.core.model.Quality
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

enum class ThemeMode(val label: String) { SYSTEM("System default"), LIGHT("Light"), DARK("Dark") }

data class AppSettings(
    val defaultQuality: Quality = Quality.BEST,
    val theme: ThemeMode = ThemeMode.SYSTEM,
    val dynamicColor: Boolean = true,
    val filenameTemplate: String = FilenameTemplate.DEFAULT,
    val clipboardDetection: Boolean = true,
)

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

class SettingsRepository(private val context: Context) {
    private object Keys {
        val quality = stringPreferencesKey("default_quality")
        val theme = stringPreferencesKey("theme")
        val dynamic = booleanPreferencesKey("dynamic_color")
        val template = stringPreferencesKey("filename_template")
        val clipboard = booleanPreferencesKey("clipboard_detection")
    }

    val settings: Flow<AppSettings> = context.dataStore.data.map { p ->
        AppSettings(
            defaultQuality = p[Keys.quality]?.let { runCatching { Quality.valueOf(it) }.getOrNull() } ?: Quality.BEST,
            theme = p[Keys.theme]?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() } ?: ThemeMode.SYSTEM,
            dynamicColor = p[Keys.dynamic] ?: true,
            filenameTemplate = p[Keys.template]?.takeIf { it.isNotBlank() } ?: FilenameTemplate.DEFAULT,
            clipboardDetection = p[Keys.clipboard] ?: true,
        )
    }

    suspend fun current(): AppSettings = settings.first()

    suspend fun setQuality(q: Quality) = context.dataStore.edit { it[Keys.quality] = q.name }
    suspend fun setTheme(t: ThemeMode) = context.dataStore.edit { it[Keys.theme] = t.name }
    suspend fun setDynamicColor(v: Boolean) = context.dataStore.edit { it[Keys.dynamic] = v }
    suspend fun setFilenameTemplate(t: String) = context.dataStore.edit { it[Keys.template] = t }
    suspend fun setClipboardDetection(v: Boolean) = context.dataStore.edit { it[Keys.clipboard] = v }
}
