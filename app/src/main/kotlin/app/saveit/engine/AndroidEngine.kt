package app.saveit.engine

import android.content.Context
import android.util.Log
import app.saveit.core.download.MediaTools
import app.saveit.core.download.ProbeResult
import app.saveit.core.download.ProcessMediaTools
import app.saveit.core.engine.EngineException
import app.saveit.core.engine.EngineProgress
import app.saveit.core.engine.YtDlpEngine
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLException
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/** yt-dlp + Python + ffmpeg bundled by youtubedl-android, behind the shared [YtDlpEngine] interface. */
class AndroidYtDlpEngine(private val context: Context, scope: CoroutineScope) : YtDlpEngine {

    private val init: Deferred<Result<Unit>> = scope.async(Dispatchers.IO, start = CoroutineStart.LAZY) {
        runCatching {
            YoutubeDL.getInstance().init(context)
            FFmpeg.getInstance().init(context)
        }.onFailure { Log.e(TAG, "Engine init failed", it) }
    }

    fun warmUp() {
        init.start()
    }

    suspend fun awaitReady() {
        init.await().getOrElse { throw EngineException("ERROR: Extractor engine failed to start: ${it.message}", it) }
    }

    override suspend fun run(args: List<String>, processId: String?, progress: EngineProgress?): String {
        awaitReady()
        val id = processId ?: UUID.randomUUID().toString()
        val request = YoutubeDLRequest(emptyList<String>()).addCommands(args)
        var finished = false
        return try {
            // Cancellation interrupts the blocking call; youtubedl-android then destroys the process.
            runInterruptible(Dispatchers.IO) {
                YoutubeDL.getInstance().execute(request, id) { percent: Float, eta: Long, line: String ->
                    progress?.invoke(percent, eta, line)
                }.out.also { finished = true }
            }
        } catch (e: YoutubeDL.CanceledException) {
            throw CancellationException("Engine process cancelled")
        } catch (e: YoutubeDLException) {
            throw EngineException(e.message.orEmpty(), e)
        } finally {
            if (!finished) YoutubeDL.getInstance().destroyProcessById(id)
        }
    }

    override fun cancel(processId: String) {
        YoutubeDL.getInstance().destroyProcessById(processId)
    }

    override suspend fun version(): String? {
        awaitReady()
        YoutubeDL.getInstance().versionName(context)?.let { return it }
        return runCatching { run(listOf("--version")).trim() }.getOrNull()
    }

    sealed interface UpdateResult {
        data class Updated(val version: String?) : UpdateResult
        data class AlreadyLatest(val version: String?) : UpdateResult
        data class Failed(val message: String) : UpdateResult
    }

    /** Downloads the latest stable yt-dlp release from GitHub (the only non-platform network call). */
    suspend fun update(): UpdateResult = withContext(Dispatchers.IO) {
        try {
            awaitReady()
            when (YoutubeDL.getInstance().updateYoutubeDL(context, YoutubeDL.UpdateChannel.STABLE)) {
                YoutubeDL.UpdateStatus.DONE -> UpdateResult.Updated(version())
                YoutubeDL.UpdateStatus.ALREADY_UP_TO_DATE -> UpdateResult.AlreadyLatest(version())
                null -> UpdateResult.Failed("Update returned no status")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            UpdateResult.Failed(e.message ?: e.javaClass.simpleName)
        }
    }

    companion object {
        private const val TAG = "SaveItEngine"
    }
}

/** ffmpeg/ffprobe shipped inside the engine's native libs (they need the unpacked shared libraries). */
class AndroidMediaTools(context: Context, private val engine: AndroidYtDlpEngine) : MediaTools {
    private val delegate: ProcessMediaTools by lazy {
        val nativeDir = context.applicationInfo.nativeLibraryDir
        val packages = File(context.noBackupFilesDir, "youtubedl-android/packages")
        ProcessMediaTools(
            ffmpegPath = "$nativeDir/libffmpeg.so",
            ffprobePath = "$nativeDir/libffprobe.so",
            environment = mapOf(
                "LD_LIBRARY_PATH" to "${packages.absolutePath}/ffmpeg/usr/lib:${packages.absolutePath}/python/usr/lib",
                "TMPDIR" to context.cacheDir.absolutePath,
            ),
        )
    }

    override suspend fun ffmpeg(args: List<String>) {
        engine.awaitReady()
        delegate.ffmpeg(args)
    }

    override suspend fun probe(file: File): ProbeResult? {
        engine.awaitReady()
        return delegate.probe(file)
    }
}
