package app.saveit.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.saveit.AppContainer
import app.saveit.core.error.ErrorClassifier
import app.saveit.core.error.ErrorKind
import app.saveit.core.error.SaveItException
import app.saveit.core.model.PostInfo
import app.saveit.core.model.Quality
import app.saveit.core.url.UrlExtractor
import app.saveit.data.AppSettings
import app.saveit.data.DownloadEntity
import app.saveit.download.DownloadTask
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class HomeState(
    val input: String = "",
    val analyzing: Boolean = false,
    val preview: PostInfo? = null,
    val error: SaveItException? = null,
    val clipboardSuggestion: String? = null,
    val engineUpdating: Boolean = false,
    val message: String? = null,
)

class HomeViewModel(private val c: AppContainer) : ViewModel() {
    private val _state = MutableStateFlow(HomeState())
    val state: StateFlow<HomeState> = _state.asStateFlow()

    val tasks: StateFlow<List<DownloadTask>> = c.queue.tasks
    val recent: StateFlow<List<DownloadEntity>> =
        c.database.downloads().observeRecent(5).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val settings: StateFlow<AppSettings> =
        c.settings.settings.stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())

    private var analyzeJob: Job? = null
    private var dismissedClip: String? = null
    private var lastAnalyzed: String? = null

    fun onInput(text: String) = _state.update { it.copy(input = text, error = null) }

    fun onSharedText(text: String) {
        _state.update { it.copy(input = text, clipboardSuggestion = null) }
        analyze()
    }

    /** Called when the window gains focus (Android only allows clipboard reads then). */
    fun onClipboard(text: String?) {
        if (!settings.value.clipboardDetection || text.isNullOrBlank()) return
        val url = UrlExtractor.findFirstSupported(text) ?: return
        val current = UrlExtractor.findFirstSupported(_state.value.input)
        if (url == dismissedClip || url == current || url == lastAnalyzed) return
        _state.update { it.copy(clipboardSuggestion = url) }
    }

    fun useSuggestion() {
        val s = _state.value.clipboardSuggestion ?: return
        _state.update { it.copy(input = s, clipboardSuggestion = null) }
        analyze()
    }

    fun dismissSuggestion() {
        dismissedClip = _state.value.clipboardSuggestion
        _state.update { it.copy(clipboardSuggestion = null) }
    }

    fun paste(text: String?) {
        if (text.isNullOrBlank()) {
            _state.update { it.copy(message = "Clipboard is empty") }
            return
        }
        _state.update { it.copy(input = text.trim(), clipboardSuggestion = null, error = null) }
        if (UrlExtractor.findFirstSupported(text) != null) analyze()
    }

    fun analyze() {
        val text = _state.value.input
        if (text.isBlank()) {
            _state.update { it.copy(message = "Paste a link first") }
            return
        }
        analyzeJob?.cancel()
        analyzeJob = viewModelScope.launch {
            _state.update { it.copy(analyzing = true, error = null, preview = null) }
            try {
                val post = withContext(Dispatchers.IO) { c.resolver.resolve(text) }
                lastAnalyzed = UrlExtractor.findFirstSupported(text)
                _state.update { it.copy(preview = post) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(error = ErrorClassifier.toException(e, null)) }
            } finally {
                _state.update { it.copy(analyzing = false) }
            }
        }
    }

    fun dismissPreview() = _state.update { it.copy(preview = null) }
    fun dismissError() = _state.update { it.copy(error = null) }
    fun consumeMessage() = _state.update { it.copy(message = null) }

    fun download(indexes: List<Int>, quality: Quality) {
        val post = _state.value.preview ?: return
        c.queue.enqueue(post, indexes, quality)
        _state.update { it.copy(preview = null, input = "", message = if (indexes.size == 1) "Download started" else "${indexes.size} downloads started") }
    }

    fun cancelTask(id: String) = c.queue.cancel(id)
    fun retryTask(id: String) = c.queue.retry(id)
    fun dismissTask(id: String) = c.queue.dismiss(id)
    fun clearFinished() = c.queue.clearFinished()

    fun updateEngine() {
        if (_state.value.engineUpdating) return
        viewModelScope.launch {
            _state.update { it.copy(engineUpdating = true) }
            val msg = when (val r = c.engine.update()) {
                is app.saveit.engine.AndroidYtDlpEngine.UpdateResult.Updated -> "Extractor engine updated to ${r.version ?: "the latest version"}"
                is app.saveit.engine.AndroidYtDlpEngine.UpdateResult.AlreadyLatest -> "Engine already up to date (${r.version ?: "latest"})"
                is app.saveit.engine.AndroidYtDlpEngine.UpdateResult.Failed -> "Update failed: ${r.message}"
            }
            _state.update { it.copy(engineUpdating = false, message = msg, error = it.error?.takeIf { e -> e.kind != ErrorKind.ENGINE_OUTDATED }) }
            if (msg.startsWith("Extractor engine updated")) analyze()
        }
    }
}
