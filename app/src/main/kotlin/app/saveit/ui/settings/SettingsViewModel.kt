package app.saveit.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.saveit.AppContainer
import app.saveit.core.model.Platform
import app.saveit.core.model.Quality
import app.saveit.data.AppSettings
import app.saveit.data.ThemeMode
import app.saveit.engine.AndroidYtDlpEngine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class EngineState(val version: String? = null, val updating: Boolean = false, val message: String? = null)

class SettingsViewModel(private val c: AppContainer) : ViewModel() {
    val settings: StateFlow<AppSettings> = c.settings.settings.stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())
    val loggedIn: StateFlow<Set<Platform>> = c.sessions.loggedIn

    private val _engine = MutableStateFlow(EngineState())
    val engine: StateFlow<EngineState> = _engine.asStateFlow()

    init {
        viewModelScope.launch { _engine.update { it.copy(version = runCatching { c.engine.version() }.getOrNull() ?: "unavailable") } }
    }

    fun setQuality(q: Quality) = viewModelScope.launch { c.settings.setQuality(q) }
    fun setTheme(t: ThemeMode) = viewModelScope.launch { c.settings.setTheme(t) }
    fun setDynamic(v: Boolean) = viewModelScope.launch { c.settings.setDynamicColor(v) }
    fun setTemplate(t: String) = viewModelScope.launch { c.settings.setFilenameTemplate(t) }
    fun setClipboard(v: Boolean) = viewModelScope.launch { c.settings.setClipboardDetection(v) }
    fun logout(p: Platform) = c.sessions.logout(p)

    fun updateEngine() {
        if (_engine.value.updating) return
        viewModelScope.launch {
            _engine.update { it.copy(updating = true, message = null) }
            val (version, msg) = when (val r = c.engine.update()) {
                is AndroidYtDlpEngine.UpdateResult.Updated -> r.version to "Updated to ${r.version ?: "the latest version"}"
                is AndroidYtDlpEngine.UpdateResult.AlreadyLatest -> r.version to "Already up to date"
                is AndroidYtDlpEngine.UpdateResult.Failed -> _engine.value.version to "Update failed: ${r.message}"
            }
            _engine.update { EngineState(version = version ?: it.version, updating = false, message = msg) }
        }
    }

    fun clearHistory() = viewModelScope.launch { c.database.downloads().clear() }
}
