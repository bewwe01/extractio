package app.saveit.core.extractor

import app.saveit.core.error.ErrorKind
import app.saveit.core.error.SaveItException
import app.saveit.core.model.MediaItem
import app.saveit.core.model.MediaType
import app.saveit.core.model.Platform
import app.saveit.core.model.PostInfo
import app.saveit.core.model.VideoVariant
import app.saveit.core.util.arrAt
import app.saveit.core.util.at
import app.saveit.core.util.bool
import app.saveit.core.util.double
import app.saveit.core.util.extensionFromUrl
import app.saveit.core.util.extractJsonAfter
import app.saveit.core.util.int
import app.saveit.core.util.intAt
import app.saveit.core.util.long
import app.saveit.core.util.parseJsonOrNull
import app.saveit.core.util.strAt
import app.saveit.core.util.truncateForTitle
import app.saveit.core.util.findJsonStringValue
import kotlinx.serialization.json.JsonElement
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.math.BigInteger
import java.net.URLEncoder

/**
 * Instagram posts, reels and carousels (every item, full resolution) via, in order:
 * the logged-in media API, the public GraphQL post query, and the public embed page.
 * Stories and highlights need a logged-in session (Instagram never serves them anonymously).
 */
class InstagramExtractor : PlatformExtractor {
    override val platform = Platform.INSTAGRAM
    override val name = "instagram-web"
    override val engineFirst = false

    private val shortcodeRe = Regex("""/(?:p|reel|tv)/([A-Za-z0-9_-]+)""")

    override suspend fun extract(url: String, ctx: ExtractionContext): PostInfo {
        val segs = url.toHttpUrlOrNull()?.pathSegments?.filter { it.isNotEmpty() }.orEmpty()
        if (segs.firstOrNull() == "stories") return stories(url, segs, ctx)

        val code = shortcodeRe.find(url)?.groupValues?.get(1) ?: fail(ErrorKind.UNSUPPORTED_URL, platform, "Not a post or reel link")
        val errors = ArrayList<SaveItException>()

        if (ctx.loggedIn(platform)) {
            attempt(errors) {
                val info = ctx.http.getJson("https://i.instagram.com/api/v1/media/${shortcodeToPk(code)}/info/", platform, apiHeaders(ctx, url))
                info.at("items", 0)?.let { return parseProduct(it, url, code) }
            }
        }
        attempt(errors) { graphql(code, url, ctx)?.let { return parseShortcodeMedia(it, url, code) } }
        attempt(errors) { embed(code, url, ctx)?.let { return it } }

        throw errors.maxByOrNull { it.kind.specificity }?.takeIf { it.kind.specificity >= 4 }
            ?: SaveItException(
                if (code.length > 28) ErrorKind.PRIVATE else ErrorKind.LOGIN_REQUIRED, platform,
                "Instagram did not return this post without a login",
            )
    }

    private inline fun attempt(errors: MutableList<SaveItException>, block: () -> Unit) {
        try {
            block()
        } catch (e: SaveItException) {
            errors += e
        } catch (e: java.io.IOException) {
            errors += SaveItException(ErrorKind.NETWORK, platform, e.message, e)
        }
    }

    private fun apiHeaders(ctx: ExtractionContext, referer: String) = buildMap {
        put("X-IG-App-ID", "936619743392459")
        put("X-ASBD-ID", "198387")
        put("X-IG-WWW-Claim", "0")
        put("X-Requested-With", "XMLHttpRequest")
        put("Origin", "https://www.instagram.com")
        put("Referer", referer)
        ctx.http.cookieJar.get("www.instagram.com", "csrftoken")?.let { put("X-CSRFToken", it) }
    }

    private suspend fun graphql(code: String, url: String, ctx: ExtractionContext): JsonElement? {
        // Warm-up call that makes Instagram set the csrftoken cookie the GraphQL endpoint expects.
        runCatching {
            ctx.http.get("https://www.instagram.com/api/v1/web/get_ruling_for_content/?content_type=MEDIA&target_id=${shortcodeToPk(code)}", apiHeaders(ctx, url))
        }
        val variables = """{"shortcode":"$code","child_comment_count":3,"fetch_comment_count":40,"parent_comment_count":24,"has_threaded_comments":true}"""
        val r = ctx.http.get(
            "https://www.instagram.com/graphql/query/?doc_id=8845758582119845&variables=" + URLEncoder.encode(variables, "UTF-8"),
            apiHeaders(ctx, url),
        )
        if (r.code == 429) fail(ErrorKind.RATE_LIMITED, platform)
        if (r.code == 404) fail(ErrorKind.DELETED, platform)
        val json = r.json() ?: return null
        return json.at("data", "xdt_shortcode_media") ?: json.at("data", "shortcode_media")
    }

    private suspend fun embed(code: String, url: String, ctx: ExtractionContext): PostInfo? {
        val r = ctx.http.get("https://www.instagram.com/p/$code/embed/captioned/", mapOf("Referer" to "https://www.instagram.com/"))
        if (r.code == 404) fail(ErrorKind.DELETED, platform)
        if (!r.isSuccess) return null
        val html = r.body

        // 1) Media JSON in the embed script, either raw or inside an escaped "contextJSON" string.
        extractJsonAfter(html, "\"shortcode_media\":")?.let(::parseJsonOrNull)?.let { return parseShortcodeMedia(it, url, code) }
        findJsonStringValue(html, "contextJSON")?.let { decoded ->
            val ctxJson = parseJsonOrNull(decoded)
            val media = ctxJson.at("gql_data", "shortcode_media") ?: ctxJson.at("context", "media")
            if (media != null) return parseShortcodeMedia(media, url, code)
        }
        extractJsonAfter(html, "window.__additionalDataLoaded('extra',")?.let(::parseJsonOrNull)?.let { data ->
            data.at("items", 0)?.let { return parseProduct(it, url, code) }
            data.at("shortcode_media")?.let { return parseShortcodeMedia(it, url, code) }
        }

        // 2) Plain HTML: a single photo is rendered as <img class="EmbeddedMediaImage">.
        val doc = Html.parse(html, url)
        if (doc.selectFirst(".EmbeddedMediaVideo, video, [data-media-type=GraphVideo]") == null) {
            val img = doc.selectFirst("img.EmbeddedMediaImage")
            val src = img?.attr("srcset")?.split(',')?.map { it.trim().substringBefore(' ') }?.lastOrNull()?.takeIf { it.isNotEmpty() }
                ?: img?.attr("src")?.takeIf { it.isNotEmpty() }
            if (src != null) {
                return PostInfo(
                    platform, url, code,
                    title = doc.selectFirst(".Caption")?.text()?.truncateForTitle(),
                    author = doc.selectFirst(".UsernameText, .Username")?.text(),
                    thumbnailUrl = src,
                    items = listOf(MediaItem(code, MediaType.IMAGE, url = src, ext = extensionFromUrl(src) ?: "jpg", thumbnailUrl = src)),
                    source = "$name-embed-html",
                )
            }
        }
        if (html.contains("login", ignoreCase = true) && html.contains("\"is_private\":true")) fail(ErrorKind.PRIVATE, platform)
        return null
    }

    // ---- stories / highlights (logged in) ----------------------------------------------------

    private suspend fun stories(url: String, segs: List<String>, ctx: ExtractionContext): PostInfo {
        if (!ctx.loggedIn(platform)) {
            fail(ErrorKind.LOGIN_REQUIRED, platform, "Instagram only shows stories and highlights to logged-in users")
        }
        val headers = apiHeaders(ctx, url)
        val (reelId, wantedPk) = if (segs.getOrNull(1) == "highlights") {
            "highlight:${segs[2]}" to null
        } else {
            val user = segs.getOrNull(1) ?: fail(ErrorKind.UNSUPPORTED_URL, platform)
            val profile = ctx.http.getJson("https://i.instagram.com/api/v1/users/web_profile_info/?username=$user", platform, headers)
            val uid = profile.strAt("data", "user", "id") ?: fail(ErrorKind.DELETED, platform, "Account not found")
            uid to segs.getOrNull(2)
        }
        val feed = ctx.http.getJson("https://i.instagram.com/api/v1/feed/reels_media/?reel_ids=" + URLEncoder.encode(reelId, "UTF-8"), platform, headers)
        val reel = feed.at("reels", reelId) ?: feed.at("reels_media", 0) ?: fail(ErrorKind.DELETED, platform, "No active stories")
        var storyItems = reel.arrAt("items")
        if (wantedPk != null) storyItems = storyItems.filter { it.strAt("pk") == wantedPk || it.strAt("id")?.startsWith(wantedPk) == true }
        val items = storyItems.flatMapIndexed { i, it -> productItems(it, url, null, i) }
        if (items.isEmpty()) fail(ErrorKind.DELETED, platform, "This story has expired")
        return PostInfo(
            platform, url, reelId,
            title = reel.strAt("title") ?: "Stories",
            author = reel.strAt("user", "username")?.let { "@$it" },
            thumbnailUrl = items.first().thumbnailUrl, items = items, source = "$name-stories",
        )
    }

    // ---- parsers -----------------------------------------------------------------------------

    /** Private-API "product" format (media/info, feed/reels_media, embed additional data). */
    internal fun parseProduct(p: JsonElement, url: String, code: String): PostInfo {
        val carousel = p.arrAt("carousel_media")
        val items = if (carousel.isNotEmpty()) {
            carousel.flatMapIndexed { i, m -> productItems(m, url, i + 1, i) }
        } else productItems(p, url, null, 0)
        if (items.isEmpty()) fail(ErrorKind.NO_MEDIA, platform)
        return PostInfo(
            platform, url, code,
            title = p.strAt("caption", "text")?.truncateForTitle(),
            author = p.strAt("user", "username")?.let { "@$it" },
            thumbnailUrl = items.first().thumbnailUrl, items = items, source = "$name-api",
            timestampSec = p.at("taken_at").long,
        )
    }

    private fun productItems(m: JsonElement, postUrl: String, carouselIndex: Int?, i: Int): List<MediaItem> {
        val id = m.strAt("pk") ?: m.strAt("id") ?: "item$i"
        val image = m.arrAt("image_versions2", "candidates").maxByOrNull { (it.intAt("width") ?: 0) * (it.intAt("height") ?: 0) }
        val imageUrl = image.strAt("url")
        val videos = m.arrAt("video_versions").filter { it.strAt("url") != null }
        if (videos.isNotEmpty()) {
            val variants = videos.map { VideoVariant(it.strAt("url")!!, it.intAt("height"), it.intAt("width"), hasAudio = m.at("has_audio").bool) }
                .distinctBy { it.url }.sortedByDescending { it.height ?: 0 }
            return listOf(
                MediaItem(
                    id = id, type = MediaType.VIDEO, ext = "mp4", variants = variants,
                    width = variants.first().width, height = variants.first().height,
                    durationSec = m.at("video_duration").double, thumbnailUrl = imageUrl,
                    engineUrl = postUrl.takeIf { "/stories/" !in it }, enginePlaylistIndex = carouselIndex,
                    hasAudio = m.at("has_audio").bool, headers = REFERER,
                ),
            )
        }
        if (imageUrl != null) {
            return listOf(
                MediaItem(
                    id = id, type = MediaType.IMAGE, url = imageUrl, ext = extensionFromUrl(imageUrl)?.takeIf { it != "heic" } ?: "jpg",
                    width = image.intAt("width"), height = image.intAt("height"), thumbnailUrl = imageUrl, headers = REFERER,
                ),
            )
        }
        return emptyList()
    }

    /** Public GraphQL format (xdt_shortcode_media / shortcode_media). */
    internal fun parseShortcodeMedia(sm: JsonElement, url: String, code: String): PostInfo {
        val children = sm.arrAt("edge_sidecar_to_children", "edges").mapNotNull { it.at("node") }
        val nodes = children.ifEmpty { listOf(sm) }
        val items = nodes.mapIndexedNotNull { i, n -> nodeItem(n, url, if (children.isNotEmpty()) i + 1 else null, i) }
        if (items.isEmpty()) fail(ErrorKind.NO_MEDIA, platform)
        return PostInfo(
            platform, url, sm.strAt("shortcode") ?: code,
            title = sm.strAt("edge_media_to_caption", "edges", 0, "node", "text")?.truncateForTitle(),
            author = sm.strAt("owner", "username")?.let { "@$it" },
            thumbnailUrl = sm.strAt("display_url") ?: items.first().thumbnailUrl,
            items = items, source = "$name-graphql",
            timestampSec = sm.at("taken_at_timestamp").long,
        )
    }

    private fun nodeItem(n: JsonElement, postUrl: String, carouselIndex: Int?, i: Int): MediaItem? {
        val id = n.strAt("id") ?: n.strAt("shortcode") ?: "item$i"
        val best = n.arrAt("display_resources").maxByOrNull { it.at("config_width").int ?: 0 }
        val display = best.strAt("src") ?: n.strAt("display_url") ?: n.strAt("display_src")
        val w = best.intAt("config_width") ?: n.intAt("dimensions", "width")
        val h = best.intAt("config_height") ?: n.intAt("dimensions", "height")
        if (n.at("is_video").bool == true) {
            val video = n.strAt("video_url")
            return MediaItem(
                id = id, type = MediaType.VIDEO, ext = "mp4",
                variants = listOfNotNull(video?.let { VideoVariant(it, n.intAt("dimensions", "height"), n.intAt("dimensions", "width"), hasAudio = n.at("has_audio").bool) }),
                width = n.intAt("dimensions", "width"), height = n.intAt("dimensions", "height"),
                durationSec = n.at("video_duration").double, thumbnailUrl = display,
                engineUrl = postUrl, enginePlaylistIndex = carouselIndex, preferEngine = video == null,
                hasAudio = n.at("has_audio").bool, headers = REFERER,
            )
        }
        display ?: return null
        return MediaItem(id = id, type = MediaType.IMAGE, url = display, ext = extensionFromUrl(display) ?: "jpg", width = w, height = h,
            thumbnailUrl = display, headers = REFERER)
    }

    companion object {
        private val REFERER = mapOf("Referer" to "https://www.instagram.com/")
        private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"

        /** Media shortcode → numeric primary key (private-post shortcodes carry 28 extra characters). */
        fun shortcodeToPk(code: String): String {
            val c = if (code.length > 28) code.take(code.length - 28) else code
            var n = BigInteger.ZERO
            val base = BigInteger.valueOf(64)
            for (ch in c) {
                val idx = ALPHABET.indexOf(ch)
                require(idx >= 0) { "Invalid shortcode" }
                n = n.multiply(base).add(BigInteger.valueOf(idx.toLong()))
            }
            return n.toString()
        }
    }
}
