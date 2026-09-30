package app.saveit.download

import android.content.Context
import android.util.Log
import app.saveit.AppContainer
import app.saveit.core.download.FilenameTemplate
import app.saveit.core.error.ErrorClassifier
import app.saveit.core.error.SaveItException
import app.saveit.core.model.MediaItem
import app.saveit.core.model.MediaType
import app.saveit.core.model.PostInfo
import app.saveit.core.model.Quality
import app.saveit.data.DownloadEntity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

enum class TaskState { QUEUED, RUNNING, DONE, FAILED, CANCELLED }

data class DownloadTask(
    val id: String,
    val post: PostInfo,
    val itemIndex: Int,
    val item: MediaItem,
    val quality: Quality,
    val state: TaskState = TaskState.QUEUED,
    val progress: Float? = null,
    val bytes: Long = 0,
    val speedBytesPerSec: Long = 0,
    val stage: String = "Waiting",
    val attempt: Int = 0,
    val error: SaveItException? = null,
    val resultUri: String? = null,
) {
    val isActive: Boolean get() = state == TaskState.QUEUED || state == TaskState.RUNNING
}

/**
 * Process-wide download queue. The UI observes [tasks]; [DownloadService] drains the queue while in
 * the foreground. Transient failures are retried with backoff, re-extracting the post (CDN URLs expire).
 */
class DownloadQueue(private val context: Context, private val c: AppContainer) {
    private val _tasks = MutableStateFlow<List<DownloadTask>>(emptyList())
    val tasks: StateFlow<List<DownloadTask>> = _tasks.asStateFlow()

    private val jobs = ConcurrentHashMap<String, Job>()

    fun enqueue(post: PostInfo, itemIndexes: List<Int>, quality: Quality) {
        val newTasks = itemIndexes.distinct().sorted().mapNotNull { i ->
            post.items.getOrNull(i)?.let { item ->
                // Quality presets only apply to videos/audio; photos and GIFs are always saved as-is.
                val q = if (item.type == MediaType.VIDEO || item.type == MediaType.AUDIO) quality else Quality.BEST
                DownloadTask(UUID.randomUUID().toString(), post, i, item, q)
            }
        }
        if (newTasks.isEmpty()) return
        _tasks.update { it + newTasks }
        DownloadService.start(context)
    }

    fun cancel(taskId: String) {
        jobs[taskId]?.cancel()
        mutate(taskId) { if (it.isActive) it.copy(state = TaskState.CANCELLED, stage = "Cancelled") else it }
    }

    fun cancelAll() {
        jobs.values.forEach { it.cancel() }
        _tasks.update { list -> list.map { if (it.isActive) it.copy(state = TaskState.CANCELLED, stage = "Cancelled") else it } }
    }

    fun retry(taskId: String) {
        mutate(taskId) { it.copy(state = TaskState.QUEUED, error = null, attempt = 0, progress = null, stage = "Waiting") }
        DownloadService.start(context)
    }

    fun dismiss(taskId: String) = _tasks.update { list -> list.filterNot { it.id == taskId && !it.isActive } }

    fun clearFinished() = _tasks.update { list -> list.filter { it.isActive } }

    fun hasPending(): Boolean = _tasks.value.any { it.state == TaskState.QUEUED }

    /** Runs queued tasks one after another until none are left. Called by the foreground service. */
    suspend fun drain() = coroutineScope {
        while (true) {
            val next = _tasks.value.firstOrNull { it.state == TaskState.QUEUED } ?: break
            val job = launch { process(next) }
            jobs[next.id] = job
            job.join()
            jobs.remove(next.id)
        }
    }

    private suspend fun process(initial: DownloadTask) {
        var task = initial
        val workDir = File(context.cacheDir, "work/${task.id}")
        try {
            while (true) {
                task = task.copy(attempt = task.attempt + 1)
                mutate(task.id) { it.copy(state = TaskState.RUNNING, attempt = task.attempt, stage = "Starting", error = null) }
                try {
                    val result = c.downloader.download(task.post, task.item, task.quality, workDir, task.id) { p ->
                        mutate(task.id) { it.copy(progress = p.fraction, bytes = p.bytes, speedBytesPerSec = p.speedBytesPerSec, stage = p.stage) }
                    }
                    mutate(task.id) { it.copy(stage = "Saving to gallery", progress = null) }
                    val settings = c.settings.current()
                    val base = FilenameTemplate.render(settings.filenameTemplate, task.post, task.item, task.itemIndex, task.quality.label)
                    val saved = c.saver.save(result.file, base, result.ext, result.mimeType)
                    c.database.downloads().insert(
                        DownloadEntity(
                            postUrl = task.post.url, platform = task.post.platform.name, title = task.post.title,
                            author = task.post.author, itemIndex = task.itemIndex, itemId = task.item.id,
                            mediaType = task.item.type.name, mimeType = result.mimeType, contentUri = saved.uri.toString(),
                            displayName = saved.displayName, sizeBytes = saved.sizeBytes,
                            width = result.probe?.width ?: task.item.width, height = result.probe?.height ?: task.item.height,
                            durationSec = result.probe?.durationSec ?: task.item.durationSec,
                            quality = task.quality.name, createdAt = System.currentTimeMillis(),
                        ),
                    )
                    mutate(task.id) { it.copy(state = TaskState.DONE, progress = 1f, stage = "Saved", resultUri = saved.uri.toString()) }
                    return
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    val err = ErrorClassifier.toException(e, task.post.platform)
                    Log.w(TAG, "Download attempt ${task.attempt} failed: ${err.kind} ${err.detail}", e)
                    if (!err.kind.retryable || task.attempt >= MAX_ATTEMPTS) {
                        mutate(task.id) { it.copy(state = TaskState.FAILED, error = err, stage = "Failed", progress = null) }
                        return
                    }
                    mutate(task.id) { it.copy(stage = "Retrying (${task.attempt}/$MAX_ATTEMPTS)…", progress = null) }
                    delay(BACKOFF_MS[task.attempt - 1])
                    workDir.deleteRecursively()
                    // Media URLs are short-lived: re-extract the post and pick the same item again.
                    runCatching { c.resolver.resolve(task.post.url) }.getOrNull()?.let { fresh ->
                        val item = fresh.items.firstOrNull { it.id == task.item.id } ?: fresh.items.getOrNull(task.itemIndex)
                        if (item != null) task = task.copy(post = fresh, item = item)
                    }
                }
            }
        } catch (e: CancellationException) {
            mutate(task.id) { it.copy(state = TaskState.CANCELLED, stage = "Cancelled", progress = null) }
            throw e
        } finally {
            workDir.deleteRecursively()
        }
    }

    private fun mutate(id: String, f: (DownloadTask) -> DownloadTask) =
        _tasks.update { list -> list.map { if (it.id == id) f(it) else it } }

    companion object {
        private const val TAG = "SaveItQueue"
        private const val MAX_ATTEMPTS = 3
        private val BACKOFF_MS = longArrayOf(2_000, 6_000, 15_000)
    }
}

