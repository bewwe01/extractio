package app.saveit.ui.history

import android.content.IntentSender
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.saveit.AppContainer
import app.saveit.core.error.ErrorClassifier
import app.saveit.core.model.Quality
import app.saveit.data.DownloadEntity
import app.saveit.data.MediaStoreSaver
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class HistoryViewModel(private val c: AppContainer) : ViewModel() {
    val items: StateFlow<List<DownloadEntity>> =
        c.database.downloads().observeAll().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    /** Set when Android needs the user to confirm deleting a file (e.g. saved by a previous install). */
    private val _pendingDelete = MutableStateFlow<Pair<Long, IntentSender>?>(null)
    val pendingDelete: StateFlow<Pair<Long, IntentSender>?> = _pendingDelete.asStateFlow()

    fun delete(entity: DownloadEntity) = viewModelScope.launch {
        when (val r = c.saver.delete(Uri.parse(entity.contentUri))) {
            MediaStoreSaver.DeleteResult.Deleted, MediaStoreSaver.DeleteResult.AlreadyGone -> {
                c.database.downloads().delete(entity.id)
                _message.value = "Deleted"
            }
            is MediaStoreSaver.DeleteResult.NeedsConfirmation -> _pendingDelete.value = entity.id to r.intentSender
        }
    }

    fun onDeleteConfirmed(confirmed: Boolean) = viewModelScope.launch {
        val id = _pendingDelete.value?.first
        _pendingDelete.value = null
        if (confirmed && id != null) {
            c.database.downloads().delete(id)
            _message.value = "Deleted"
        }
    }

    fun redownload(entity: DownloadEntity) = viewModelScope.launch {
        _message.value = "Fetching post again…"
        try {
            val post = c.resolver.resolve(entity.postUrl)
            val index = post.items.indexOfFirst { it.id == entity.itemId }.takeIf { it >= 0 }
                ?: entity.itemIndex.takeIf { it in post.items.indices }
                ?: 0
            c.queue.enqueue(post, listOf(index), runCatching { Quality.valueOf(entity.quality) }.getOrDefault(Quality.BEST))
            _message.value = "Download started"
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _message.value = ErrorClassifier.toException(e, null).userMessage
        }
    }

    fun clearHistory() = viewModelScope.launch {
        c.database.downloads().clear()
        _message.value = "History cleared (files stay in your gallery)"
    }

    fun consumeMessage() {
        _message.value = null
    }
}
