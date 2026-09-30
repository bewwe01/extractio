package app.saveit.core.extractor

import app.saveit.core.error.ErrorKind
import app.saveit.core.model.MediaItem
import app.saveit.core.model.MediaType
import app.saveit.core.model.Platform
import app.saveit.core.model.PostInfo
import app.saveit.core.model.VideoVariant
import app.saveit.core.url.UrlNormalizer
import app.saveit.core.util.arrAt
import app.saveit.core.util.at
import app.saveit.core.util.bool
import app.saveit.core.util.double
import app.saveit.core.util.extensionFromUrl
import app.saveit.core.util.int
import app.saveit.core.util.intAt
import app.saveit.core.util.long
import app.saveit.core.util.parseJsonOrNull
import app.saveit.core.util.str
import app.saveit.core.util.strAt
import app.saveit.core.util.truncateForTitle
import kotlinx.serialization.json.JsonElement
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * TikTok videos and photo-mode slideshows from the web page's rehydration JSON.
 * Videos: yt-dlp runs first (it handles TikTok's signed URLs best); this extractor is the fallback and
 * the only source of slideshow images (yt-dlp returns just the soundtrack for those).
 */
class TikTokExtractor : PlatformExtractor {
    override val platform = Platform.TIKTOK
    override val name = "tiktok-web"
    override val engineFirst = true

    private val idRe = Regex("""/(?:video|photo)/(\d+)""")

    override suspend fun extract(url: String, ctx: ExtractionContext): PostInfo {
        val id = idRe.find(url)?.groupValues?.get(1) ?: fail(ErrorKind.UNSUPPORTED_URL, platform, "Not a TikTok video or photo link")
        val r = ctx.http.get(url, mapOf("Referer" to "https://www.tiktok.com/"))
        if (r.finalUrl.toHttpUrlOrNull()?.encodedPath?.startsWith("/login") == true) {
            fail(ErrorKind.LOGIN_REQUIRED, platform, "TikTok requires login for this post")
        }
        if (r.code == 404) fail(ErrorKind.DELETED, platform)
        val item = itemStructFromHtml(r.body, id)
        return parseItem(item, url, id)
    }

    internal fun itemStructFromHtml(html: String, id: String): JsonElement {
        val doc = Html.parse(html, "https://www.tiktok.com/")
        doc.selectFirst("script#__UNIVERSAL_DATA_FOR_REHYDRATION__")?.data()?.let(::parseJsonOrNull)?.let { data ->
            val detail = data.at("__DEFAULT_SCOPE__", "webapp.video-detail")
            checkStatus(detail.at("statusCode").int ?: 0, detail.strAt("statusMsg"))
            detail.at("itemInfo", "itemStruct")?.let { return it }
        }
        doc.selectFirst("script#SIGI_STATE")?.data()?.let(::parseJsonOrNull)?.let { sigi ->
            checkStatus(sigi.at("VideoPage", "statusCode").int ?: 0, null)
            sigi.at("ItemModule", id)?.let { return it }
        }
        doc.selectFirst("script#__NEXT_DATA__")?.data()?.let(::parseJsonOrNull)?.let { next ->
            checkStatus(next.at("props", "pageProps", "statusCode").int ?: 0, null)
            next.at("props", "pageProps", "itemInfo", "itemStruct")?.let { return it }
        }
        fail(ErrorKind.ENGINE_OUTDATED, platform, "TikTok page layout not recognized")
    }

    private fun checkStatus(code: Int, msg: String?) {
        when (code) {
            0 -> Unit
            10204 -> fail(ErrorKind.DELETED, platform, "Video unavailable")
            10216 -> fail(ErrorKind.PRIVATE, platform, "This TikTok is private")
            10222 -> fail(ErrorKind.PRIVATE, platform, "This account is private")
            10101, 10231 -> fail(ErrorKind.REGION_BLOCKED, platform, msg)
            else -> fail(ErrorKind.UNKNOWN, platform, "TikTok status $code ${msg.orEmpty()}".trim())
        }
    }

    internal fun parseItem(item: JsonElement, url: String, id: String): PostInfo {
        val author = item.strAt("author", "uniqueId") ?: item.strAt("author")
        val canonical = if (author != null) "https://www.tiktok.com/@$author/video/$id" else url
        val engineUrl = UrlNormalizer.engineUrl(canonical)
        val items = ArrayList<MediaItem>()

        val images = item.arrAt("imagePost", "images")
        if (images.isNotEmpty()) {
            images.forEachIndexed { i, img ->
                val urls = img.arrAt("imageURL", "urlList").mapNotNull { it.str }
                val best = urls.firstOrNull { ".jpeg" in it || ".jpg" in it } ?: urls.firstOrNull() ?: return@forEachIndexed
                items += MediaItem(
                    id = "${id}_$i", type = MediaType.IMAGE, url = best, altUrls = urls - best,
                    ext = imageExt(best), width = img.intAt("imageWidth"), height = img.intAt("imageHeight"),
                    thumbnailUrl = best, headers = HEADERS,
                )
            }
            item.strAt("music", "playUrl")?.let { audio ->
                items += MediaItem(
                    id = "${id}_audio", type = MediaType.AUDIO, url = audio, ext = extensionFromUrl(audio)?.takeIf { it in setOf("mp3", "m4a", "aac") } ?: "mp3",
                    durationSec = item.at("music", "duration").double, thumbnailUrl = item.strAt("music", "coverLarge"),
                    engineUrl = engineUrl, hasAudio = true, headers = HEADERS, selectedByDefault = false,
                    label = listOfNotNull(item.strAt("music", "title"), item.strAt("music", "authorName")).joinToString(" · ").ifEmpty { "Soundtrack" },
                )
            }
        } else {
            val video = item.at("video") ?: fail(ErrorKind.NO_MEDIA, platform)
            if (item.at("isContentClassified").bool == true && video.strAt("playAddr") == null) {
                fail(ErrorKind.AGE_RESTRICTED, platform)
            }
            items += MediaItem(
                id = id, type = MediaType.VIDEO, ext = "mp4", variants = videoVariants(video),
                width = video.intAt("width"), height = video.intAt("height"), durationSec = video.at("duration").double,
                thumbnailUrl = video.strAt("cover") ?: video.strAt("originCover"),
                engineUrl = engineUrl, preferEngine = true, hasAudio = null, headers = HEADERS,
            )
        }
        if (items.isEmpty()) fail(ErrorKind.NO_MEDIA, platform)
        return PostInfo(
            platform = platform, url = canonical, id = id,
            title = item.strAt("desc")?.truncateForTitle()?.ifBlank { null },
            author = author?.let { "@$it" },
            thumbnailUrl = item.strAt("video", "cover") ?: item.strAt("imagePost", "cover", "imageURL", "urlList", 0) ?: items.first().thumbnailUrl,
            items = items, source = name, timestampSec = item.at("createTime").long,
        )
    }

    /** Watermark-free streams first (bitrateInfo/playAddr, H.264 preferred), the watermarked download last. */
    private fun videoVariants(video: JsonElement): List<VideoVariant> {
        val fromBitrates = video.arrAt("bitrateInfo").mapNotNull { b ->
            val u = b.arrAt("PlayAddr", "UrlList").firstNotNullOfOrNull { it.str } ?: return@mapNotNull null
            val codec = b.strAt("CodecType").orEmpty()
            Triple(VideoVariant(u, b.intAt("PlayAddr", "Height"), b.intAt("PlayAddr", "Width"), b.at("Bitrate").long), codec, b)
        }
        val h264 = fromBitrates.filter { it.second.contains("h264", true) }
        val preferred = (h264.ifEmpty { fromBitrates }).map { it.first }.sortedWith(
            compareByDescending<VideoVariant> { it.height ?: 0 }.thenByDescending { it.bitrate ?: 0 },
        )
        val play = video.strAt("playAddr")?.let { VideoVariant(it, video.intAt("height"), video.intAt("width")) }
        val download = video.strAt("downloadAddr")?.let { VideoVariant(it, null, null) }
        return (preferred + listOfNotNull(play, download)).distinctBy { it.url }
    }

    private fun imageExt(url: String): String = when {
        ".jpeg" in url || ".jpg" in url -> "jpg"
        ".png" in url -> "png"
        ".webp" in url -> "webp"
        ".heic" in url -> "heic"
        else -> "jpg"
    }

    companion object {
        private val HEADERS = mapOf("Referer" to "https://www.tiktok.com/")
    }
}
