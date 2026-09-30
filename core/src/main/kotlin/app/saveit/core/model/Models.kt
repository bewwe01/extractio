package app.saveit.core.model

import kotlinx.serialization.Serializable

/** Platforms SaveIt knows how to extract from. [DIRECT] is a bare media URL (e.g. an i.redd.it image). */
@Serializable
enum class Platform(val displayName: String, val loginSupported: Boolean) {
    REDDIT("Reddit", true),
    X("X", true),
    INSTAGRAM("Instagram", true),
    TIKTOK("TikTok", true),
    SNAPCHAT("Snapchat", true),
    FACEBOOK("Facebook", true),
    PINTEREST("Pinterest", true),
    DIRECT("Direct link", false),
}

@Serializable
enum class MediaType { VIDEO, IMAGE, GIF, AUDIO }

/**
 * One concrete progressive stream of a video. When [audioUrl] is set, the video stream is silent
 * and the audio must be muxed in with ffmpeg.
 */
@Serializable
data class VideoVariant(
    val url: String,
    val height: Int? = null,
    val width: Int? = null,
    val bitrate: Long? = null,
    /** Separate audio stream that must be merged. Alternatives are tried in order. */
    val audioUrls: List<String> = emptyList(),
    /** true = the stream itself carries audio, false = known silent, null = unknown. */
    val hasAudio: Boolean? = null,
    /** HLS/DASH manifest instead of a plain file: must be fetched with yt-dlp/ffmpeg. */
    val isManifest: Boolean = false,
)

/**
 * A single downloadable thing inside a post (one photo of a gallery, one video of a carousel...).
 *
 * An item can be downloaded in two ways:
 *  - directly from [url]/[variants] (Kotlin fallback extractors resolve these), and/or
 *  - through the yt-dlp engine using [engineUrl] (+ [enginePlaylistIndex] for multi-item posts).
 * [preferEngine] tells the downloader which one to try first; the other is the fallback.
 */
@Serializable
data class MediaItem(
    val id: String,
    val type: MediaType,
    /** Best direct URL for images/GIFs/audio (or single-stream videos). */
    val url: String? = null,
    /** Other URLs for the same file, tried in order when [url] fails (e.g. original vs preview). */
    val altUrls: List<String> = emptyList(),
    val variants: List<VideoVariant> = emptyList(),
    val ext: String,
    val width: Int? = null,
    val height: Int? = null,
    val durationSec: Double? = null,
    val thumbnailUrl: String? = null,
    val engineUrl: String? = null,
    val enginePlaylistIndex: Int? = null,
    /** Heights the engine reported for this item (for the quality picker). */
    val engineHeights: List<Int> = emptyList(),
    val preferEngine: Boolean = false,
    /** Whether the source has an audio track (null = unknown). Used to reject silent results. */
    val hasAudio: Boolean? = null,
    /** HTTP headers the CDN needs (Referer, cookies are added separately). */
    val headers: Map<String, String> = emptyMap(),
    /** Pre-checked in the preview sheet. Optional extras (e.g. slideshow music) are unchecked. */
    val selectedByDefault: Boolean = true,
    val label: String? = null,
) {
    val isVideoLike: Boolean get() = type == MediaType.VIDEO || (type == MediaType.GIF && ext == "mp4")

    /** Distinct heights available for quality selection, highest first. */
    val availableHeights: List<Int>
        get() = (variants.mapNotNull { it.height } + engineHeights + listOfNotNull(height.takeIf { type == MediaType.VIDEO }))
            .distinct().sortedDescending()
}

@Serializable
data class PostInfo(
    val platform: Platform,
    /** URL after cleanup and short-link resolution. */
    val url: String,
    val id: String,
    val title: String? = null,
    val author: String? = null,
    val thumbnailUrl: String? = null,
    val items: List<MediaItem>,
    /** Which extractor produced this ("yt-dlp", "reddit-json", ...), for diagnostics. */
    val source: String,
    val timestampSec: Long? = null,
)

/** Quality presets shown in the UI. */
@Serializable
enum class Quality(val label: String, val maxHeight: Int?) {
    BEST("Best", null),
    P1080("1080p", 1080),
    P720("720p", 720),
    P480("480p", 480),
    AUDIO_M4A("Audio only (M4A)", null),
    AUDIO_MP3("Audio only (MP3)", null);

    val isAudioOnly: Boolean get() = this == AUDIO_M4A || this == AUDIO_MP3
}
