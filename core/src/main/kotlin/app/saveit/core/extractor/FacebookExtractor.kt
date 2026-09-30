package app.saveit.core.extractor

import app.saveit.core.error.ErrorKind
import app.saveit.core.model.MediaItem
import app.saveit.core.model.MediaType
import app.saveit.core.model.Platform
import app.saveit.core.model.PostInfo
import app.saveit.core.model.VideoVariant
import app.saveit.core.util.arrAt
import app.saveit.core.util.extensionFromUrl
import app.saveit.core.util.extractBalancedJson
import app.saveit.core.util.intAt
import app.saveit.core.util.long
import app.saveit.core.util.at
import app.saveit.core.util.meta
import app.saveit.core.util.parseJsonOrNull
import app.saveit.core.util.strAt
import app.saveit.core.util.truncateForTitle
import app.saveit.core.util.findJsonStringValue
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Facebook fallback for when yt-dlp fails: public videos/reels/watch links (best progressive HD stream,
 * or DASH video + audio for merging) and photo posts, read from the JSON embedded in the page.
 * Facebook shows much less to logged-out visitors; that case is reported as "login required".
 */
class FacebookExtractor : PlatformExtractor {
    override val platform = Platform.FACEBOOK
    override val name = "facebook-web"
    override val engineFirst = true

    override suspend fun extract(url: String, ctx: ExtractionContext): PostInfo {
        val r = ctx.http.get(
            url,
            mapOf(
                "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
                "Sec-Fetch-Mode" to "navigate",
                "Sec-Fetch-Site" to "none",
            ),
        )
        if (r.code == 404) fail(ErrorKind.DELETED, platform)
        val finalPath = r.finalUrl.toHttpUrlOrNull()?.encodedPath.orEmpty()
        if (finalPath.startsWith("/login") || finalPath.startsWith("/checkpoint")) {
            fail(ErrorKind.LOGIN_REQUIRED, platform, "Facebook redirected to its login page")
        }
        return parsePage(r.body, r.finalUrl, url)
    }

    internal fun parsePage(html: String, finalUrl: String, originalUrl: String): PostInfo {
        val doc = Html.parse(html, finalUrl)
        val id = Regex("""(?:v=|/videos/(?:[^/]+/)?|/reel/|fbid=|story_fbid=|/posts/|/permalink/)(\w+)""").find(finalUrl)?.groupValues?.get(1)
            ?: finalUrl.hashCode().toUInt().toString()

        val items = ArrayList<MediaItem>()
        videoItem(html, id, originalUrl, doc.meta("og:image"))?.let { items += it }
        if (items.isEmpty()) items += photoItems(html, id)
        if (items.isEmpty()) {
            doc.meta("og:video", "og:video:secure_url", "og:video:url")?.let {
                items += MediaItem(id, MediaType.VIDEO, ext = "mp4", variants = listOf(VideoVariant(it)), thumbnailUrl = doc.meta("og:image"),
                    engineUrl = originalUrl)
            }
        }
        if (items.isEmpty()) {
            val img = doc.meta("og:image")
            val looksLikePhoto = "/photo" in finalUrl || "fbid=" in finalUrl
            if (img != null && looksLikePhoto) {
                items += MediaItem(id, MediaType.IMAGE, url = img, ext = extensionFromUrl(img) ?: "jpg", thumbnailUrl = img)
            }
        }
        if (items.isEmpty()) {
            when {
                html.contains("id=\"login_form\"") || html.contains("\"loginFormNode\"") || html.contains("You must log in") ->
                    fail(ErrorKind.LOGIN_REQUIRED, platform, "Facebook only shows this post to logged-in users")
                html.contains("This content isn't available", ignoreCase = true) || html.contains("content isn&#039;t available") ->
                    fail(ErrorKind.PRIVATE, platform, "This content isn't available (deleted, or shared only with a limited audience)")
                else -> fail(ErrorKind.NO_MEDIA, platform)
            }
        }
        return PostInfo(
            platform = platform, url = originalUrl, id = id,
            title = (doc.meta("og:title") ?: doc.meta("og:description"))?.truncateForTitle(),
            author = null,
            thumbnailUrl = doc.meta("og:image") ?: items.first().thumbnailUrl,
            items = items, source = name,
        )
    }

    private fun jsonString(html: String, key: String): String? = findJsonStringValue(html, key)?.takeIf { it.isNotEmpty() }

    private fun videoItem(html: String, id: String, pageUrl: String, poster: String?): MediaItem? {
        val variants = ArrayList<VideoVariant>()

        // DASH representations (higher resolutions, audio separate).
        val repsJson = Regex("\"all_video_dash_prefetch_representations\"\\s*:\\s*").find(html)
            ?.let { extractBalancedJson(html, it.range.last + 1) }?.let(::parseJsonOrNull)
        val reps = repsJson?.arrAt(0, "representations").orEmpty()
        if (reps.isNotEmpty()) {
            val audio = reps.filter { it.strAt("mime_type")?.startsWith("audio") == true }
                .sortedByDescending { it.at("bandwidth").long ?: 0 }.mapNotNull { it.strAt("base_url") }
            reps.filter { it.strAt("mime_type")?.startsWith("video") == true && it.strAt("base_url") != null }
                .sortedByDescending { it.intAt("height") ?: 0 }
                .distinctBy { it.intAt("height") }
                .forEach { v ->
                    variants += VideoVariant(v.strAt("base_url")!!, v.intAt("height"), v.intAt("width"), v.at("bandwidth").long,
                        audioUrls = audio, hasAudio = false)
                }
        } else {
            jsonString(html, "dash_manifest")?.let { xml ->
                val parsed = runCatching { DashManifest.parse(xml, pageUrl) }.getOrDefault(emptyList())
                val audio = parsed.filter { it.isAudio }.sortedByDescending { it.bandwidth }.map { it.url }
                parsed.filter { !it.isAudio }.sortedByDescending { it.height ?: 0 }.distinctBy { it.height }.forEach {
                    variants += VideoVariant(it.url, it.height, it.width, it.bandwidth, audioUrls = audio, hasAudio = false)
                }
            }
        }

        // Progressive (muxed) streams; heights are not given, so they rank after DASH streams of known height.
        val hd = jsonString(html, "browser_native_hd_url") ?: jsonString(html, "playable_url_quality_hd") ?: jsonString(html, "hd_src")
        val sd = jsonString(html, "browser_native_sd_url") ?: jsonString(html, "playable_url") ?: jsonString(html, "sd_src")
        listOfNotNull(hd, sd).distinct().forEach { variants += VideoVariant(it, hasAudio = null) }

        if (variants.isEmpty()) return null
        return MediaItem(
            id = id, type = MediaType.VIDEO, ext = "mp4", variants = variants,
            width = variants.first().width, height = variants.first().height,
            thumbnailUrl = jsonString(html, "preferred_thumbnail")?.takeIf { it.startsWith("http") } ?: poster,
            engineUrl = pageUrl,
            hasAudio = if (variants.any { it.audioUrls.isNotEmpty() }) true else null,
        )
    }

    private fun photoItems(html: String, id: String): List<MediaItem> {
        val found = LinkedHashMap<String, Pair<Int?, Int?>>()
        Regex("\"(?:photo_image|viewer_image)\"\\s*:\\s*\\{([^{}]*)\\}").findAll(html).forEach { m ->
            val body = m.groupValues[1]
            val uri = findJsonStringValue(body, "uri") ?: return@forEach
            val w = Regex("\"width\"\\s*:\\s*(\\d+)").find(body)?.groupValues?.get(1)?.toIntOrNull()
            val h = Regex("\"height\"\\s*:\\s*(\\d+)").find(body)?.groupValues?.get(1)?.toIntOrNull()
            val key = uri.substringBefore('?').substringAfterLast('/')
            val prev = found.entries.firstOrNull { it.key.substringBefore('?').substringAfterLast('/') == key }
            if (prev == null) found[uri] = w to h
            else if ((w ?: 0) > (prev.value.first ?: 0)) {
                found.remove(prev.key); found[uri] = w to h
            }
        }
        return found.entries.take(80).mapIndexed { i, (uri, size) ->
            MediaItem("${id}_$i", MediaType.IMAGE, url = uri, ext = extensionFromUrl(uri) ?: "jpg", width = size.first, height = size.second, thumbnailUrl = uri)
        }
    }
}
