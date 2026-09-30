package app.saveit.core.extractor

import app.saveit.core.error.ErrorKind
import app.saveit.core.model.MediaItem
import app.saveit.core.model.MediaType
import app.saveit.core.model.Platform
import app.saveit.core.model.PostInfo
import app.saveit.core.model.VideoVariant
import app.saveit.core.util.arrAt
import app.saveit.core.util.at
import app.saveit.core.util.double
import app.saveit.core.util.extensionFromUrl
import app.saveit.core.util.intAt
import app.saveit.core.util.meta
import app.saveit.core.util.obj
import app.saveit.core.util.strAt
import app.saveit.core.util.truncateForTitle
import kotlinx.serialization.json.JsonElement
import java.net.URLEncoder

/**
 * Pinterest via Pinterest's own public resource endpoint (the one pinterest.com's web app calls):
 * original-resolution images (/originals/), video pins (MP4), GIF pins, idea pins (every page) and
 * carousel pins (every slot).
 */
class PinterestExtractor : PlatformExtractor {
    override val platform = Platform.PINTEREST
    override val name = "pinterest-resource"
    override val engineFirst = false

    private val pinRe = Regex("""/pin/(?:[^/]*--)?(\d+)""")

    override suspend fun extract(url: String, ctx: ExtractionContext): PostInfo {
        val id = pinRe.find(url)?.groupValues?.get(1) ?: fail(ErrorKind.UNSUPPORTED_URL, platform, "Not a pin link (boards and profiles aren't supported)")
        val data = """{"options":{"id":"$id","field_set_key":"unauth_react_main_pin"},"context":{}}"""
        val r = ctx.http.get(
            "https://www.pinterest.com/resource/PinResource/get/?source_url=" + URLEncoder.encode("/pin/$id/", "UTF-8") +
                "&data=" + URLEncoder.encode(data, "UTF-8"),
            mapOf(
                "Accept" to "application/json, text/javascript, */*; q=0.01",
                "X-Pinterest-PWS-Handler" to "www/pin/[id].js",
                "X-Requested-With" to "XMLHttpRequest",
                "Referer" to "https://www.pinterest.com/",
            ),
        )
        val pin = r.json().at("resource_response", "data")
        if (pin?.obj != null && pin.strAt("id") != null) return parsePin(pin, id)
        if (r.code == 404 || r.json().strAt("resource_response", "status") == "failure" && r.code in 400..499) {
            fail(ErrorKind.DELETED, platform, "Pin not found")
        }
        return fromHtml(id, ctx)
    }

    internal fun parsePin(pin: JsonElement, id: String): PostInfo {
        val items = ArrayList<MediaItem>()
        val canonical = "https://www.pinterest.com/pin/$id/"

        // Idea pins ("story pins"): one item per page, image or video.
        pin.arrAt("story_pin_data", "pages").forEachIndexed { i, page ->
            page.arrAt("blocks").forEach { block ->
                val videoList = block.at("video", "video_list")
                if (videoList != null) {
                    videoItem("${id}_$i", videoList, block.strAt("video", "cover_image_url"), canonical)?.let { items += it }
                } else {
                    val img = block.at("image", "images")
                    imageFromImages("${id}_$i", img)?.let { items += it }
                }
            }
        }
        // Carousel pins: one item per slot.
        if (items.isEmpty()) {
            pin.arrAt("carousel_data", "carousel_slots").forEachIndexed { i, slot ->
                imageFromImages("${id}_$i", slot.at("images"))?.let { items += it }
            }
        }
        // Regular video pin.
        if (items.isEmpty()) {
            pin.at("videos", "video_list")?.let { vl ->
                videoItem(id, vl, pin.strAt("images", "orig", "url"), canonical)?.let { items += it }
            }
        }
        // GIF pins embed the animated file.
        if (items.isEmpty() && pin.strAt("embed", "type") == "gif") {
            pin.strAt("embed", "src")?.let { src ->
                items += MediaItem(id, MediaType.GIF, url = src, ext = extensionFromUrl(src) ?: "gif",
                    width = pin.intAt("embed", "width"), height = pin.intAt("embed", "height"),
                    thumbnailUrl = pin.strAt("images", "orig", "url"))
            }
        }
        // Image pin.
        if (items.isEmpty()) imageFromImages(id, pin.at("images"))?.let { items += it }

        // Pins that just link to an external video (YouTube, Vimeo...) are left to the engine.
        if (items.isEmpty() && pin.strAt("embed", "src") != null) {
            items += MediaItem(id, MediaType.VIDEO, ext = "mp4", engineUrl = pin.strAt("embed", "src"), preferEngine = true,
                thumbnailUrl = pin.strAt("images", "orig", "url"), label = "Embedded video")
        }
        if (items.isEmpty()) fail(ErrorKind.NO_MEDIA, platform)

        return PostInfo(
            platform = platform, url = canonical, id = id,
            title = (pin.strAt("title") ?: pin.strAt("grid_title") ?: pin.strAt("seo_title") ?: pin.strAt("description"))?.truncateForTitle()?.ifBlank { null },
            author = pin.strAt("pinner", "username")?.let { "@$it" } ?: pin.strAt("closeup_attribution", "full_name"),
            thumbnailUrl = pin.strAt("images", "736x", "url") ?: pin.strAt("images", "orig", "url") ?: items.first().thumbnailUrl,
            items = items, source = name,
        )
    }

    private fun imageFromImages(id: String, images: JsonElement?): MediaItem? {
        val obj = images?.obj ?: return null
        val orig = obj["orig"] ?: obj["originals"]
        val best = orig ?: obj.values.maxByOrNull { (it.intAt("width") ?: 0) * (it.intAt("height") ?: 0) }
        val url = best.strAt("url") ?: return null
        val original = toOriginal(url)
        val ext = extensionFromUrl(original) ?: "jpg"
        return MediaItem(
            id = id, type = if (ext == "gif") MediaType.GIF else MediaType.IMAGE,
            url = original, altUrls = listOf(url).filter { it != original } + originalExtAlternatives(original),
            ext = ext, width = best.intAt("width"), height = best.intAt("height"),
            thumbnailUrl = obj["736x"].strAt("url") ?: obj["474x"].strAt("url") ?: url,
        )
    }

    private fun videoItem(id: String, videoList: JsonElement, cover: String?, pinUrl: String): MediaItem? {
        val entries = videoList.obj?.entries ?: return null
        val mp4 = entries.filter { (_, v) -> v.strAt("url")?.substringBefore('?')?.endsWith(".mp4") == true }
            .map { (_, v) -> VideoVariant(v.strAt("url")!!, v.intAt("height"), v.intAt("width")) }
            .sortedByDescending { it.height ?: 0 }
        val hls = entries.firstOrNull { (k, v) -> "HLS" in k.uppercase() || v.strAt("url")?.contains(".m3u8") == true }
            ?.value?.strAt("url")?.let { VideoVariant(it, isManifest = true) }
        val variants = mp4 + listOfNotNull(hls)
        if (variants.isEmpty()) return null
        val any = entries.first().value
        return MediaItem(
            id = id, type = MediaType.VIDEO, ext = "mp4", variants = variants,
            width = mp4.firstOrNull()?.width, height = mp4.firstOrNull()?.height,
            durationSec = any.at("duration").double?.div(1000), thumbnailUrl = cover ?: any.strAt("thumbnail"),
            engineUrl = pinUrl, preferEngine = mp4.isEmpty(),
        )
    }

    private suspend fun fromHtml(id: String, ctx: ExtractionContext): PostInfo {
        val r = ctx.http.getOk("https://www.pinterest.com/pin/$id/", platform)
        val doc = Html.parse(r.body, r.finalUrl)
        val video = doc.meta("og:video", "og:video:secure_url")
        val image = doc.meta("og:image")?.let(::toOriginal)
        val item = when {
            video != null -> MediaItem(id, MediaType.VIDEO, ext = "mp4", variants = listOf(VideoVariant(video)), thumbnailUrl = image,
                engineUrl = "https://www.pinterest.com/pin/$id/")
            image != null -> MediaItem(id, if (image.endsWith(".gif")) MediaType.GIF else MediaType.IMAGE, url = image,
                altUrls = originalExtAlternatives(image), ext = extensionFromUrl(image) ?: "jpg", thumbnailUrl = image)
            else -> fail(ErrorKind.NO_MEDIA, platform)
        }
        return PostInfo(platform, "https://www.pinterest.com/pin/$id/", id, doc.meta("og:title")?.truncateForTitle(), null,
            image, listOf(item), "$name-og")
    }

    companion object {
        private val sizeSegment = Regex("""(https?://i\.pinimg\.com)/(?:\d+x\d*|\d+x|[a-z0-9_]+x)/""")

        /** i.pinimg.com/736x/ab/cd/ef/hash.jpg → i.pinimg.com/originals/ab/cd/ef/hash.jpg */
        fun toOriginal(url: String): String =
            if ("/originals/" in url) url else sizeSegment.replace(url) { "${it.groupValues[1]}/originals/" }

        /** Originals keep the uploaded format, which can differ from the resized JPEG's extension. */
        fun originalExtAlternatives(original: String): List<String> {
            if ("/originals/" !in original) return emptyList()
            val base = original.substringBeforeLast('.')
            val ext = original.substringAfterLast('.')
            return listOf("jpg", "png", "gif", "webp").filter { it != ext }.map { "$base.$it" }
        }
    }
}
