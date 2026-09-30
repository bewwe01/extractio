package app.saveit.core.engine

import app.saveit.core.error.ErrorClassifier
import app.saveit.core.error.ErrorKind
import app.saveit.core.error.SaveItException
import app.saveit.core.model.MediaItem
import app.saveit.core.model.MediaType
import app.saveit.core.model.Platform
import app.saveit.core.model.PostInfo
import app.saveit.core.model.Quality
import app.saveit.core.util.arrAt
import app.saveit.core.util.at
import app.saveit.core.util.double
import app.saveit.core.util.extensionFromUrl
import app.saveit.core.util.int
import app.saveit.core.util.long
import app.saveit.core.util.obj
import app.saveit.core.util.parseJsonOrNull
import app.saveit.core.util.str
import app.saveit.core.util.strAt
import app.saveit.core.util.truncateForTitle
import kotlinx.serialization.json.JsonElement
import java.io.File

/** Progress reported by the engine: percent in 0..100 (or -1 if unknown), ETA seconds, raw output line. */
typealias EngineProgress = (percent: Float, etaSec: Long, line: String) -> Unit

/**
 * The yt-dlp engine. On Android it is backed by youtubedl-android (bundled Python + yt-dlp + ffmpeg);
 * the JVM verifier backs it with the `yt-dlp` executable. Both run the same arguments.
 */
interface YtDlpEngine {
    /** Runs yt-dlp with [args] (the URL included) and returns stdout. Throws [EngineException] on failure. */
    suspend fun run(args: List<String>, processId: String? = null, progress: EngineProgress? = null): String

    /** Kills a running process started with [processId]. */
    fun cancel(processId: String) {}

    suspend fun version(): String?
}

class EngineException(val output: String, cause: Throwable? = null) : Exception(ErrorClassifier.condense(output), cause)

object YtDlpArgs {
    /** Arguments shared by every invocation. */
    fun common(cookieFile: File?): List<String> = buildList {
        add("--no-playlist-reverse")
        add("--no-warnings")
        add("--no-mtime")
        add("--socket-timeout"); add("30")
        add("--retries"); add("3")
        add("--extractor-retries"); add("2")
        if (cookieFile != null) { add("--cookies"); add(cookieFile.absolutePath) }
    }

    fun info(url: String, cookieFile: File?): List<String> =
        common(cookieFile) + listOf("--dump-single-json", "--no-download", "--ignore-no-formats-error", url)

    /**
     * Format selection that never silently drops audio: merge best video+audio, else a combined stream,
     * else whatever exists (for sources that truly have no audio, e.g. GIF-style videos).
     */
    fun format(quality: Quality): List<String> = when {
        quality.isAudioOnly -> listOf(
            "-f", "ba/b",
            "-x", "--audio-format", if (quality == Quality.AUDIO_MP3) "mp3" else "m4a",
            "--audio-quality", "0",
        )
        else -> buildList {
            add("-f"); add("bv*+ba/b/bv*/b*")
            // Resolution cap as a *preference* (closest lower one wins, falls back to the smallest above),
            // then prefer H.264/AAC in MP4 for gallery-app compatibility.
            add("-S"); add(listOfNotNull(quality.maxHeight?.let { "res:$it" }, "vcodec:h264", "acodec:aac", "ext:mp4:m4a").joinToString(","))
            add("--merge-output-format"); add("mp4")
            add("--remux-video"); add("mp4")
        }
    }

    fun download(
        url: String,
        playlistIndex: Int?,
        quality: Quality,
        outputTemplate: String,
        cookieFile: File?,
    ): List<String> = common(cookieFile) + format(quality) + buildList {
        if (playlistIndex != null) { add("--playlist-items"); add(playlistIndex.toString()) } else add("--no-playlist")
        add("--newline")
        add("--no-part")
        add("--restrict-filenames")
        add("-o"); add(outputTemplate)
        add("--print"); add("after_move:filepath")
        add(url)
    }
}

/** Converts `yt-dlp --dump-single-json` output into [PostInfo]. */
object YtDlpJsonParser {

    fun parse(json: String, platform: Platform, sourceUrl: String): PostInfo {
        val root = parseJsonOrNull(json) ?: throw SaveItException(ErrorKind.ENGINE_OUTDATED, platform, "Engine returned unreadable data")
        return parse(root, platform, sourceUrl)
    }

    fun parse(root: JsonElement, platform: Platform, sourceUrl: String): PostInfo {
        val isPlaylist = root.strAt("_type") == "playlist" || root.at("entries") != null
        val entries = if (isPlaylist) root.arrAt("entries").filter { it.obj != null } else listOf(root)
        val multi = isPlaylist && entries.size > 1
        val items = entries.mapIndexedNotNull { idx, e ->
            toItem(e, sourceUrl, if (isPlaylist && multi) idx + 1 else null, idx)
        }
        val first = entries.firstOrNull()
        return PostInfo(
            platform = platform,
            url = sourceUrl,
            id = root.strAt("id") ?: first.strAt("id") ?: sourceUrl.hashCode().toString(),
            title = (root.strAt("title") ?: first.strAt("title") ?: root.strAt("description"))?.truncateForTitle(),
            author = root.strAt("uploader") ?: root.strAt("channel") ?: first.strAt("uploader") ?: first.strAt("channel")
                ?: root.strAt("uploader_id") ?: first.strAt("uploader_id"),
            thumbnailUrl = bestThumbnail(root) ?: first?.let { bestThumbnail(it) } ?: items.firstOrNull()?.thumbnailUrl,
            items = items,
            source = "yt-dlp",
            timestampSec = (root.at("timestamp") ?: first.at("timestamp")).long,
        )
    }

    private fun toItem(e: JsonElement, sourceUrl: String, playlistIndex: Int?, idx: Int): MediaItem? {
        val formats = e.arrAt("formats").ifEmpty { e.takeIf { it.strAt("url") != null }?.let { listOf(it) } ?: emptyList() }
        val id = e.strAt("id") ?: "item$idx"
        val thumb = bestThumbnail(e)
        val video = formats.filter { hasVideo(it) }
        val audioOnly = formats.filter { !hasVideo(it) && hasAudio(it) }

        if (video.isNotEmpty()) {
            val heights = video.mapNotNull { it.at("height").int }.distinct().sortedDescending()
            val anyAudio = video.any { hasAudio(it) } || audioOnly.isNotEmpty()
            val unknownAudio = video.any { it.strAt("acodec") == null }
            val best = video.maxByOrNull { (it.at("height").int ?: 0) * 10_000 + (it.at("tbr").double ?: 0.0).toInt() }
            return MediaItem(
                id = id,
                type = MediaType.VIDEO,
                ext = "mp4",
                width = best?.at("width").int ?: e.at("width").int,
                height = heights.firstOrNull() ?: e.at("height").int,
                durationSec = e.at("duration").double,
                thumbnailUrl = thumb,
                engineUrl = sourceUrl,
                enginePlaylistIndex = playlistIndex,
                engineHeights = heights,
                preferEngine = true,
                hasAudio = when {
                    anyAudio -> true
                    unknownAudio -> null
                    else -> false
                },
            )
        }
        if (audioOnly.isNotEmpty()) {
            return MediaItem(
                id = id, type = MediaType.AUDIO, ext = "m4a", durationSec = e.at("duration").double, thumbnailUrl = thumb,
                engineUrl = sourceUrl, enginePlaylistIndex = playlistIndex, preferEngine = true, hasAudio = true,
                label = "Audio",
            )
        }
        // No formats: image entries (Instagram carousel photos, Pinterest image pins) only carry thumbnails.
        val imageUrl = formats.firstOrNull { isImageFormat(it) }?.strAt("url") ?: bestThumbnail(e, preferOriginal = true)
        if (imageUrl != null) {
            val ext = extensionFromUrl(imageUrl)?.takeIf { it in IMAGE_EXTS } ?: "jpg"
            return MediaItem(
                id = id,
                type = if (ext == "gif") MediaType.GIF else MediaType.IMAGE,
                url = imageUrl,
                ext = ext,
                width = e.at("width").int,
                height = e.at("height").int,
                thumbnailUrl = imageUrl,
                headers = httpHeaders(e),
            )
        }
        return null
    }

    private val IMAGE_EXTS = setOf("jpg", "jpeg", "png", "webp", "gif", "heic", "avif")

    private fun isImageFormat(f: JsonElement): Boolean =
        (f.strAt("ext") ?: extensionFromUrl(f.strAt("url").orEmpty())) in IMAGE_EXTS && f.strAt("vcodec").let { it == null || it == "none" }

    private fun hasVideo(f: JsonElement): Boolean {
        val v = f.strAt("vcodec")
        if (v != null) return v != "none"
        if (isImageFormat(f)) return false
        val ext = f.strAt("ext") ?: extensionFromUrl(f.strAt("url").orEmpty())
        if (ext in setOf("m4a", "mp3", "aac", "opus", "ogg", "wav")) return false
        // Unknown codec info: treat as video if it has dimensions or a video container.
        return f.at("height").int != null || ext in setOf("mp4", "webm", "mov", "m3u8", "mkv")
    }

    private fun hasAudio(f: JsonElement): Boolean {
        val a = f.strAt("acodec")
        return a != null && a != "none"
    }

    private fun httpHeaders(e: JsonElement): Map<String, String> =
        e.at("http_headers").obj?.mapNotNull { (k, v) -> v.str?.let { k to it } }?.toMap()?.filterKeys { it.equals("Referer", true) }
            ?: emptyMap()

    fun bestThumbnail(e: JsonElement, preferOriginal: Boolean = false): String? {
        val thumbs = e.arrAt("thumbnails").filter { it.strAt("url") != null }
        if (thumbs.isEmpty()) return e.strAt("thumbnail")
        val scored = thumbs.maxByOrNull {
            val area = (it.at("width").int ?: 0).toLong() * (it.at("height").int ?: 0)
            val pref = it.at("preference").int ?: 0
            if (preferOriginal) area * 10 + pref else pref * 1_000_000_000L + area
        }
        return scored?.strAt("url") ?: e.strAt("thumbnail")
    }
}
