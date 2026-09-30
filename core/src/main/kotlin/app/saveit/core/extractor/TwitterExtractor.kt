package app.saveit.core.extractor

import app.saveit.core.error.ErrorClassifier
import app.saveit.core.error.ErrorKind
import app.saveit.core.model.MediaItem
import app.saveit.core.model.MediaType
import app.saveit.core.model.Platform
import app.saveit.core.model.PostInfo
import app.saveit.core.model.VideoVariant
import app.saveit.core.url.TwitterUrls
import app.saveit.core.util.arrAt
import app.saveit.core.util.at
import app.saveit.core.util.extensionFromUrl
import app.saveit.core.util.intAt
import app.saveit.core.util.long
import app.saveit.core.util.strAt
import app.saveit.core.util.truncateForTitle
import kotlinx.serialization.json.JsonElement
import kotlin.math.floor
import kotlin.math.max

/**
 * X / Twitter via X's public syndication endpoint (the one embedded tweets use). Gives photos at
 * original quality, every MP4 variant of videos (we take the highest bitrate) and GIFs (MP4).
 */
class TwitterExtractor : PlatformExtractor {
    override val platform = Platform.X
    override val name = "x-syndication"
    override val engineFirst = false

    private val idRe = Regex("""/status(?:es)?/(\d+)""")

    override suspend fun extract(url: String, ctx: ExtractionContext): PostInfo {
        val id = idRe.find(url)?.groupValues?.get(1) ?: fail(ErrorKind.UNSUPPORTED_URL, platform, "Not a post link")
        val r = ctx.http.get(
            "https://cdn.syndication.twimg.com/tweet-result?id=$id&token=${syndicationToken(id)}&lang=en",
            mapOf("Accept" to "application/json", "Referer" to "https://platform.twitter.com/"),
        )
        if (r.code == 404) fail(ErrorKind.DELETED, platform, "Post not found (deleted, private or from a suspended account)")
        if (!r.isSuccess) fail(ErrorClassifier.fromHttpStatus(r.code), platform, "Syndication HTTP ${r.code}")
        val tweet = r.json() ?: fail(ErrorKind.DELETED, platform)
        return parse(tweet, url, id)
    }

    internal fun parse(tweet: JsonElement, url: String, id: String): PostInfo {
        when (tweet.strAt("__typename")) {
            "TweetTombstone" -> {
                val text = tweet.strAt("tombstone", "text", "text").orEmpty()
                val kind = when {
                    text.contains("age", true) || text.contains("adult", true) -> ErrorKind.AGE_RESTRICTED
                    text.contains("log in", true) || text.contains("protected", true) -> ErrorKind.PRIVATE
                    else -> ErrorKind.DELETED
                }
                fail(kind, platform, text.ifEmpty { null })
            }
            null -> if (tweet.at("id_str") == null) fail(ErrorKind.DELETED, platform)
        }
        var mediaDetails = tweet.arrAt("mediaDetails")
        if (mediaDetails.isEmpty()) mediaDetails = tweet.arrAt("quoted_tweet", "mediaDetails")
        if (mediaDetails.isEmpty()) fail(ErrorKind.NO_MEDIA, platform)

        val videoCount = mediaDetails.count { it.strAt("type") != "photo" }
        var videoOrdinal = 0
        val items = mediaDetails.mapIndexedNotNull { i, m ->
            when (m.strAt("type")) {
                "photo" -> {
                    val src = m.strAt("media_url_https") ?: return@mapIndexedNotNull null
                    MediaItem(
                        id = m.strAt("id_str") ?: "photo$i", type = MediaType.IMAGE,
                        url = TwitterUrls.originalPhoto(src), altUrls = listOf(src),
                        ext = extensionFromUrl(src) ?: "jpg",
                        width = m.intAt("original_info", "width"), height = m.intAt("original_info", "height"),
                        thumbnailUrl = "$src?name=small",
                    )
                }
                "video", "animated_gif" -> {
                    videoOrdinal++
                    val isGif = m.strAt("type") == "animated_gif"
                    val variants = m.arrAt("video_info", "variants")
                        .filter { it.strAt("content_type") == "video/mp4" && it.strAt("url") != null }
                        .map { v ->
                            val vu = v.strAt("url")!!
                            val (w, h) = sizeFromVideoUrl(vu)
                            VideoVariant(vu, h, w, v.at("bitrate").long, hasAudio = if (isGif) false else null)
                        }
                        .sortedWith(compareByDescending<VideoVariant> { it.bitrate ?: 0 }.thenByDescending { it.height ?: 0 })
                    MediaItem(
                        id = m.strAt("id_str") ?: "video$i",
                        type = if (isGif) MediaType.GIF else MediaType.VIDEO,
                        ext = "mp4",
                        variants = variants,
                        width = m.intAt("original_info", "width"), height = m.intAt("original_info", "height"),
                        durationSec = m.at("video_info", "duration_millis").long?.div(1000.0),
                        thumbnailUrl = m.strAt("media_url_https"),
                        engineUrl = url,
                        enginePlaylistIndex = if (videoCount > 1) videoOrdinal else null,
                        preferEngine = variants.isEmpty(),
                        hasAudio = if (isGif) false else null,
                    )
                }
                else -> null
            }
        }
        if (items.isEmpty()) fail(ErrorKind.NO_MEDIA, platform)
        val screenName = tweet.strAt("user", "screen_name")
        return PostInfo(
            platform = platform,
            url = if (screenName != null) "https://x.com/$screenName/status/$id" else url,
            id = id,
            title = tweet.strAt("text")?.replace(Regex("""\s*https://t\.co/\w+"""), "")?.truncateForTitle()?.ifBlank { null },
            author = screenName?.let { "@$it" } ?: tweet.strAt("user", "name"),
            thumbnailUrl = items.first().thumbnailUrl,
            items = items,
            source = name,
        )
    }

    private val sizeRe = Regex("""/(\d{2,5})x(\d{2,5})/""")

    private fun sizeFromVideoUrl(url: String): Pair<Int?, Int?> {
        val m = sizeRe.find(url) ?: return null to null
        return m.groupValues[1].toIntOrNull() to m.groupValues[2].toIntOrNull()
    }

    companion object {
        /** Same token the embed widget computes: ((id / 1e15) * PI).toString(36) without zeros and dots. */
        fun syndicationToken(id: String): String =
            JsNumber.toRadixString((id.toDouble() / 1e15) * Math.PI, 36).replace(Regex("(0+|\\.)"), "")
    }
}

/** Port of V8's DoubleToRadixCString so the result matches JavaScript's Number.prototype.toString(radix). */
object JsNumber {
    private const val CHARS = "0123456789abcdefghijklmnopqrstuvwxyz"

    fun toRadixString(value: Double, radix: Int): String {
        require(radix in 2..36)
        if (value.isNaN()) return "NaN"
        if (value.isInfinite()) return if (value > 0) "Infinity" else "-Infinity"
        if (value < 0) return "-" + toRadixString(-value, radix)
        var integer = floor(value)
        var fraction = value - integer
        var delta = max(0.5 * (Math.nextUp(value) - value), Math.nextUp(0.0))
        val frac = StringBuilder()
        if (fraction >= delta) {
            do {
                fraction *= radix
                delta *= radix
                val digit = fraction.toInt()
                frac.append(CHARS[digit])
                fraction -= digit
                if (fraction > 0.5 || (fraction == 0.5 && (digit and 1) == 1)) {
                    if (fraction + delta > 1) {
                        // Round up, propagating the carry.
                        while (true) {
                            if (frac.isEmpty()) {
                                integer += 1
                                break
                            }
                            val d = CHARS.indexOf(frac.last())
                            frac.setLength(frac.length - 1)
                            if (d + 1 < radix) {
                                frac.append(CHARS[d + 1])
                                break
                            }
                        }
                        break
                    }
                }
            } while (fraction >= delta)
        }
        val intPart = if (integer < 9.007199254740992E15) integer.toLong().toString(radix) else bigIntegerString(integer, radix)
        return if (frac.isEmpty()) intPart else "$intPart.$frac"
    }

    /** V8 only emits the representable leading digits of huge integers and pads with zeros. */
    private fun bigIntegerString(v: Double, radix: Int): String {
        var integer = v
        var zeros = 0
        while (integer / radix >= 9.007199254740992E15) {
            integer /= radix
            zeros++
        }
        val sb = StringBuilder()
        do {
            val rem = integer % radix
            sb.append(CHARS[rem.toInt()])
            integer = (integer - rem) / radix
        } while (integer > 0)
        return sb.reverse().toString() + "0".repeat(zeros)
    }
}
