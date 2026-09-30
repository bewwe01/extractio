package app.saveit.core.download

import app.saveit.core.engine.EngineException
import app.saveit.core.engine.YtDlpArgs
import app.saveit.core.engine.YtDlpEngine
import app.saveit.core.error.ErrorClassifier
import app.saveit.core.error.ErrorKind
import app.saveit.core.error.SaveItException
import app.saveit.core.model.MediaItem
import app.saveit.core.model.MediaType
import app.saveit.core.model.Platform
import app.saveit.core.model.PostInfo
import app.saveit.core.model.Quality
import app.saveit.core.model.VideoVariant
import app.saveit.core.net.Http
import app.saveit.core.net.UserAgents
import app.saveit.core.net.await
import app.saveit.core.util.arrAt
import app.saveit.core.util.at
import app.saveit.core.util.double
import app.saveit.core.util.intAt
import app.saveit.core.util.parseJsonOrNull
import app.saveit.core.util.strAt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File

/** Result of inspecting a media file with ffprobe. */
data class ProbeResult(
    val hasVideo: Boolean,
    val hasAudio: Boolean,
    val durationSec: Double?,
    val width: Int?,
    val height: Int?,
    val formatName: String?,
    val videoCodec: String?,
    val audioCodec: String?,
) {
    companion object {
        /** Parses `ffprobe -v error -print_format json -show_streams -show_format` output. */
        fun fromFfprobeJson(json: String): ProbeResult? {
            val root = parseJsonOrNull(json) ?: return null
            val streams = root.arrAt("streams")
            val video = streams.firstOrNull { it.strAt("codec_type") == "video" }
            val audio = streams.firstOrNull { it.strAt("codec_type") == "audio" }
            return ProbeResult(
                hasVideo = video != null,
                hasAudio = audio != null,
                durationSec = root.at("format", "duration").double ?: video.at("duration").double,
                width = video.intAt("width"),
                height = video.intAt("height"),
                formatName = root.strAt("format", "format_name"),
                videoCodec = video.strAt("codec_name"),
                audioCodec = audio.strAt("codec_name"),
            )
        }
    }
}

/** Native tools: the Android app runs the ffmpeg/ffprobe bundled with the engine, the verifier the system ones. */
interface MediaTools {
    suspend fun ffmpeg(args: List<String>)
    suspend fun probe(file: File): ProbeResult?
}

data class DownloadProgress(val bytes: Long, val totalBytes: Long?, val speedBytesPerSec: Long, val fraction: Float?, val stage: String)

data class DownloadedFile(val file: File, val ext: String, val mimeType: String, val probe: ProbeResult?, val via: String)

/**
 * Downloads one [MediaItem] into [workDir] and returns the finished file. Tries the item's preferred
 * route (engine or direct) first, then the other, and never returns a silent video when the source has audio.
 */
class MediaDownloader(
    private val http: Http,
    private val engine: YtDlpEngine?,
    private val tools: MediaTools,
    private val cookieFileFor: (Platform) -> File? = { null },
) {
    suspend fun download(
        post: PostInfo,
        item: MediaItem,
        quality: Quality,
        workDir: File,
        processId: String,
        onProgress: (DownloadProgress) -> Unit = {},
    ): DownloadedFile {
        workDir.mkdirs()
        val routes = routesFor(item, quality)
        if (routes.isEmpty()) throw SaveItException(ErrorKind.NO_MEDIA, post.platform, "Nothing downloadable in this item")
        val errors = ArrayList<SaveItException>()
        for (route in routes) {
            try {
                val result = when (route) {
                    Route.ENGINE -> viaEngine(post, item, quality, workDir, processId, onProgress)
                    Route.DIRECT -> viaDirect(post, item, quality, workDir, onProgress)
                }
                return verify(post, item, quality, result)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                errors += ErrorClassifier.toException(e, post.platform)
            }
        }
        throw errors.maxBy { it.kind.specificity + if (it.kind == ErrorKind.SILENT_RESULT) 10 else 0 }
    }

    private enum class Route { ENGINE, DIRECT }

    private fun routesFor(item: MediaItem, quality: Quality): List<Route> {
        val canEngine = engine != null && item.engineUrl != null
        val canDirect = item.url != null || item.variants.isNotEmpty()
        // Audio extraction and anything that needs merging/format choice is best done by the engine.
        val engineFirst = item.preferEngine || (quality.isAudioOnly && item.type == MediaType.VIDEO && item.variants.isEmpty())
        return buildList {
            if (engineFirst && canEngine) add(Route.ENGINE)
            if (canDirect) add(Route.DIRECT)
            if (!engineFirst && canEngine) add(Route.ENGINE)
        }
    }

    // ---- engine route ------------------------------------------------------------------------

    private suspend fun viaEngine(
        post: PostInfo, item: MediaItem, quality: Quality, workDir: File, processId: String, onProgress: (DownloadProgress) -> Unit,
    ): DownloadedFile {
        val eng = engine ?: throw SaveItException(ErrorKind.UNKNOWN, post.platform, "Engine unavailable")
        val dir = File(workDir, "engine-${System.nanoTime()}").apply { mkdirs() }
        val q = if (item.type == MediaType.AUDIO && !quality.isAudioOnly) Quality.AUDIO_M4A else quality
        val args = YtDlpArgs.download(
            url = item.engineUrl!!, playlistIndex = item.enginePlaylistIndex, quality = q,
            outputTemplate = File(dir, "%(id).80B.%(ext)s").absolutePath, cookieFile = cookieFileFor(post.platform),
        )
        val out = try {
            eng.run(args, processId) { percent, _, line ->
                onProgress(DownloadProgress(0, null, parseSpeed(line), if (percent >= 0) percent / 100f else null, "Downloading"))
            }
        } catch (e: EngineException) {
            throw SaveItException(ErrorClassifier.classify(e.output), post.platform, e.message, e)
        }
        val printed = out.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.map(::File).lastOrNull { it.isFile }
        val file = printed ?: dir.listFiles()?.filter { it.isFile && !it.name.endsWith(".part") && !it.name.endsWith(".ytdl") }
            ?.maxByOrNull { it.length() }
            ?: throw SaveItException(ErrorKind.UNKNOWN, post.platform, "Engine finished without producing a file")
        val ext = file.extension.lowercase().ifEmpty { if (q.isAudioOnly) "m4a" else "mp4" }
        return DownloadedFile(file, ext, MimeTypes.forExt(ext), null, "yt-dlp")
    }

    private val speedRe = Regex("""at\s+([\d.]+)\s*([KMG]?i?B)/s""")

    private fun parseSpeed(line: String): Long {
        val m = speedRe.find(line) ?: return 0
        val v = m.groupValues[1].toDoubleOrNull() ?: return 0
        val mult = when (m.groupValues[2].uppercase().first()) { 'K' -> 1024.0; 'M' -> 1024.0 * 1024; 'G' -> 1024.0 * 1024 * 1024; else -> 1.0 }
        return (v * mult).toLong()
    }

    // ---- direct route ------------------------------------------------------------------------

    private suspend fun viaDirect(post: PostInfo, item: MediaItem, quality: Quality, workDir: File, onProgress: (DownloadProgress) -> Unit): DownloadedFile {
        val base = "item-${System.nanoTime()}"
        return when {
            item.type == MediaType.VIDEO || (item.variants.isNotEmpty() && item.url == null) -> {
                val video = directVideo(post, item, quality, workDir, base, onProgress)
                if (quality.isAudioOnly) extractAudio(video, quality, workDir, base) else video
            }
            else -> {
                val candidates = listOfNotNull(item.url) + item.altUrls
                var last: Exception? = null
                for (u in candidates) {
                    try {
                        val target = File(workDir, "$base.${item.ext}")
                        val contentType = fetch(u, target, item.headers, onProgress, "Downloading")
                        val ext = MimeTypes.extForContentType(contentType, item.ext)
                        if (contentType?.startsWith("text/") == true) throw SaveItException(ErrorKind.UNKNOWN, post.platform, "Server returned a web page instead of media")
                        val finalFile = if (ext != item.ext) File(workDir, "$base.$ext").also { target.renameTo(it) } else target
                        val result = DownloadedFile(finalFile, ext, MimeTypes.forExt(ext), null, "direct")
                        return if (item.type == MediaType.AUDIO && quality == Quality.AUDIO_MP3 && ext != "mp3") {
                            extractAudio(result, quality, workDir, base)
                        } else result
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        last = e
                    }
                }
                throw last ?: SaveItException(ErrorKind.NO_MEDIA, post.platform)
            }
        }
    }

    /** Orders variants for a quality cap: best at/below the cap, then unknown height, then the smallest above. */
    internal fun orderVariants(variants: List<VideoVariant>, quality: Quality): List<VideoVariant> {
        val cap = quality.maxHeight
        if (cap == null) {
            val known = variants.filter { it.height != null }.sortedWith(compareByDescending<VideoVariant> { it.height }.thenByDescending { it.bitrate ?: 0 })
            return known + variants.filter { it.height == null }
        }
        val below = variants.filter { (it.height ?: Int.MAX_VALUE) <= cap }.sortedByDescending { it.height }
        val unknown = variants.filter { it.height == null }
        val above = variants.filter { (it.height ?: 0) > cap }.sortedBy { it.height }
        return below + unknown + above
    }

    private suspend fun directVideo(
        post: PostInfo, item: MediaItem, quality: Quality, workDir: File, base: String, onProgress: (DownloadProgress) -> Unit,
    ): DownloadedFile {
        val variants = orderVariants(item.variants.ifEmpty { listOfNotNull(item.url?.let { VideoVariant(it) }) }, quality)
        var lastError: Exception? = null
        for (v in variants) {
            try {
                if (v.isManifest) {
                    val out = File(workDir, "$base.mp4")
                    onProgress(DownloadProgress(0, null, 0, null, "Downloading stream"))
                    tools.ffmpeg(buildList {
                        add("-y")
                        val hdrs = item.headers + ("User-Agent" to UserAgents.DESKTOP)
                        add("-headers"); add(hdrs.entries.joinToString("") { "${it.key}: ${it.value}\r\n" })
                        add("-i"); add(v.url)
                        add("-c"); add("copy"); add("-bsf:a"); add("aac_adtstoasc"); add("-movflags"); add("+faststart")
                        add(out.absolutePath)
                    })
                    return DownloadedFile(out, "mp4", "video/mp4", null, "direct-hls")
                }
                val videoFile = File(workDir, "$base.video.mp4")
                fetch(v.url, videoFile, item.headers, onProgress, "Downloading video")
                if (v.audioUrls.isEmpty()) {
                    val out = File(workDir, "$base.mp4")
                    videoFile.renameTo(out)
                    return DownloadedFile(out, "mp4", "video/mp4", null, "direct")
                }
                val audioFile = File(workDir, "$base.audio.m4a")
                val gotAudio = v.audioUrls.firstOrNull { a ->
                    runCatching { fetch(a, audioFile, item.headers, onProgress, "Downloading audio"); audioFile.length() > 0 }.getOrDefault(false)
                } != null
                val out = File(workDir, "$base.mp4")
                if (!gotAudio) {
                    if (item.hasAudio == true) throw SaveItException(ErrorKind.SILENT_RESULT, post.platform, "Audio stream unavailable")
                    videoFile.renameTo(out)
                    return DownloadedFile(out, "mp4", "video/mp4", null, "direct-noaudio")
                }
                onProgress(DownloadProgress(0, null, 0, null, "Merging audio"))
                tools.ffmpeg(
                    listOf(
                        "-y", "-i", videoFile.absolutePath, "-i", audioFile.absolutePath,
                        "-map", "0:v:0", "-map", "1:a:0", "-c", "copy", "-movflags", "+faststart", "-shortest", out.absolutePath,
                    ),
                )
                videoFile.delete(); audioFile.delete()
                return DownloadedFile(out, "mp4", "video/mp4", null, "direct-merged")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                lastError = e
            }
        }
        throw lastError ?: SaveItException(ErrorKind.NO_MEDIA, post.platform, "No video stream")
    }

    private suspend fun extractAudio(source: DownloadedFile, quality: Quality, workDir: File, base: String): DownloadedFile {
        val mp3 = quality == Quality.AUDIO_MP3
        val out = File(workDir, "$base.audio-out.${if (mp3) "mp3" else "m4a"}")
        val codec = if (mp3) listOf("-c:a", "libmp3lame", "-q:a", "2") else listOf("-c:a", "copy")
        runCatching { tools.ffmpeg(listOf("-y", "-i", source.file.absolutePath, "-vn") + codec + out.absolutePath) }.getOrElse {
            if (mp3) throw it
            // Source audio isn't AAC: re-encode.
            tools.ffmpeg(listOf("-y", "-i", source.file.absolutePath, "-vn", "-c:a", "aac", "-b:a", "192k", out.absolutePath))
        }
        source.file.delete()
        val ext = if (mp3) "mp3" else "m4a"
        return DownloadedFile(out, ext, MimeTypes.forExt(ext), null, source.via + "+audio")
    }

    /** Streams [url] into [target]; returns the response Content-Type. */
    private suspend fun fetch(url: String, target: File, headers: Map<String, String>, onProgress: (DownloadProgress) -> Unit, stage: String): String? =
        withContext(Dispatchers.IO) {
            val req = http.request(url, headers + mapOf("Accept" to "*/*"))
            http.client.newCall(req).await().use { resp ->
                if (!resp.isSuccessful) {
                    throw SaveItException(ErrorClassifier.fromHttpStatus(resp.code).let { if (it == ErrorKind.DELETED) ErrorKind.UNKNOWN else it },
                        null, "HTTP ${resp.code} while downloading")
                }
                val body = resp.body ?: throw SaveItException(ErrorKind.NETWORK, null, "Empty response")
                val total = body.contentLength().takeIf { it > 0 }
                val started = System.nanoTime()
                var lastReport = 0L
                var written = 0L
                target.outputStream().buffered(256 * 1024).use { out ->
                    body.byteStream().use { input ->
                        val buf = ByteArray(128 * 1024)
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            written += n
                            val now = System.nanoTime()
                            if (now - lastReport > 250_000_000L) {
                                lastReport = now
                                val secs = (now - started) / 1e9
                                onProgress(DownloadProgress(written, total, if (secs > 0) (written / secs).toLong() else 0, total?.let { written.toFloat() / it }, stage))
                            }
                        }
                    }
                }
                if (written == 0L) throw SaveItException(ErrorKind.UNKNOWN, null, "Downloaded file is empty")
                onProgress(DownloadProgress(written, total, 0, 1f, stage))
                resp.header("Content-Type")
            }
        }

    // ---- verification ------------------------------------------------------------------------

    private suspend fun verify(post: PostInfo, item: MediaItem, quality: Quality, result: DownloadedFile): DownloadedFile {
        if (!result.file.isFile || result.file.length() == 0L) {
            throw SaveItException(ErrorKind.UNKNOWN, post.platform, "Downloaded file is empty")
        }
        val probe = runCatching { tools.probe(result.file) }.getOrNull()
        if (probe != null) {
            val expectsVideo = !quality.isAudioOnly && (item.type == MediaType.VIDEO || (item.type == MediaType.GIF && result.ext == "mp4"))
            if (expectsVideo && !probe.hasVideo) {
                throw SaveItException(ErrorKind.UNKNOWN, post.platform, "Result has no video stream")
            }
            if (expectsVideo && item.hasAudio == true && !probe.hasAudio) {
                result.file.delete()
                throw SaveItException(ErrorKind.SILENT_RESULT, post.platform, "Result has no audio track")
            }
            if (quality.isAudioOnly && !probe.hasAudio) {
                throw SaveItException(ErrorKind.NO_MEDIA, post.platform, "This item has no audio track")
            }
        }
        return result.copy(probe = probe)
    }
}

object MimeTypes {
    fun forExt(ext: String): String = when (ext.lowercase()) {
        "mp4", "m4v" -> "video/mp4"
        "webm" -> "video/webm"
        "mkv" -> "video/x-matroska"
        "mov" -> "video/quicktime"
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        "gif" -> "image/gif"
        "webp" -> "image/webp"
        "heic" -> "image/heic"
        "avif" -> "image/avif"
        "mp3" -> "audio/mpeg"
        "m4a" -> "audio/mp4"
        "aac" -> "audio/aac"
        "opus" -> "audio/opus"
        "ogg" -> "audio/ogg"
        else -> "application/octet-stream"
    }

    fun extForContentType(contentType: String?, fallback: String): String = when (contentType?.substringBefore(';')?.trim()?.lowercase()) {
        "image/jpeg", "image/jpg" -> "jpg"
        "image/png" -> "png"
        "image/gif" -> "gif"
        "image/webp" -> "webp"
        "image/heic" -> "heic"
        "image/avif" -> "avif"
        "video/mp4" -> "mp4"
        "video/webm" -> "webm"
        "audio/mpeg" -> "mp3"
        "audio/mp4", "audio/x-m4a" -> "m4a"
        else -> fallback
    }

    fun isVideo(ext: String) = forExt(ext).startsWith("video/")
    fun isAudio(ext: String) = forExt(ext).startsWith("audio/")
}
