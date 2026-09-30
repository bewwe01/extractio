package app.saveit.core.extractor

import app.saveit.core.error.ErrorKind
import app.saveit.core.model.MediaItem
import app.saveit.core.model.MediaType
import app.saveit.core.model.Platform
import app.saveit.core.model.PostInfo
import app.saveit.core.model.VideoVariant
import app.saveit.core.net.HttpResult
import app.saveit.core.util.arrAt
import app.saveit.core.util.at
import app.saveit.core.util.bool
import app.saveit.core.util.double
import app.saveit.core.util.extensionFromUrl
import app.saveit.core.util.intAt
import app.saveit.core.util.obj
import app.saveit.core.util.strAt
import app.saveit.core.util.truncateForTitle
import kotlinx.serialization.json.JsonElement
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Reddit via the public `<post>.json` endpoint: galleries (every image), image posts, GIFs,
 * v.redd.it videos (video-only DASH stream + separate audio → merged), crossposts, Redgifs/Imgur embeds.
 */
class RedditExtractor(private val embeds: EmbedResolver) : PlatformExtractor {
    override val platform = Platform.REDDIT
    override val name = "reddit-json"
    override val engineFirst = false

    override suspend fun extract(url: String, ctx: ExtractionContext): PostInfo {
        val u = url.toHttpUrlOrNull() ?: fail(ErrorKind.UNSUPPORTED_URL, platform)
        if (u.host == "v.redd.it") {
            val id = u.pathSegments.firstOrNull { it.isNotEmpty() } ?: fail(ErrorKind.UNSUPPORTED_URL, platform)
            val item = videoFromRedditVideo(ctx, null, id, null)
            return PostInfo(platform, url, id, title = null, author = null, thumbnailUrl = null, items = listOf(item), source = name)
        }
        if ("comments" !in u.pathSegments) fail(ErrorKind.UNSUPPORTED_URL, platform, "Not a Reddit post link")

        val listing = fetchListing(ctx, url)
        val post = listing.at(0, "data", "children", 0, "data") ?: fail(ErrorKind.DELETED, platform)
        return parsePost(post, ctx)
    }

    private suspend fun fetchListing(ctx: ExtractionContext, url: String): JsonElement {
        val base = url.substringBefore('?').trimEnd('/')
        val attempts = listOf(
            "$base/.json?raw_json=1",
            base.replace("://www.reddit.com", "://old.reddit.com") + "/.json?raw_json=1",
        )
        var last: HttpResult? = null
        for (a in attempts) {
            val r = ctx.http.get(a, mapOf("Accept" to "application/json"))
            last = r
            val json = r.json()
            if (json != null) {
                // {"reason": "private", "message": "Forbidden", "error": 403}
                when (json.strAt("reason")) {
                    "private" -> fail(ErrorKind.PRIVATE, platform, "Private subreddit")
                    "quarantined" -> fail(ErrorKind.LOGIN_REQUIRED, platform, "Quarantined subreddit")
                    "banned" -> fail(ErrorKind.DELETED, platform, "Banned subreddit")
                    "gold_only" -> fail(ErrorKind.LOGIN_REQUIRED, platform)
                }
                if (json.at(0) != null) return json
            }
            if (r.code == 404) fail(ErrorKind.DELETED, platform)
        }
        val code = last?.code ?: 0
        // Reddit answers 403/429 to clients it throttles (common on VPN/datacenter IPs); logging in helps.
        fail(if (code == 403 || code == 429) ErrorKind.RATE_LIMITED else ErrorKind.NETWORK, platform, "Reddit API HTTP $code")
    }

    internal suspend fun parsePost(post: JsonElement, ctx: ExtractionContext): PostInfo {
        val id = post.strAt("id") ?: "reddit"
        val permalink = post.strAt("permalink")?.let { "https://www.reddit.com$it" }
        // A crosspost carries its media on the parent.
        val src = post.at("crosspost_parent_list", 0)?.takeIf { !hasOwnMedia(post) } ?: post

        val items = ArrayList<MediaItem>()
        items += galleryItems(src)
        if (items.isEmpty()) items += inlineMediaItems(src, ctx, permalink)

        val redditVideo = src.at("secure_media", "reddit_video") ?: src.at("media", "reddit_video")
        if (items.isEmpty() && redditVideo != null) {
            items += videoFromRedditVideo(ctx, redditVideo, videoIdFrom(redditVideo), permalink)
        }
        if (items.isEmpty()) items += linkedMediaItems(src, ctx)

        // Link posts to GIF hosts often only carry a transcoded preview video.
        val previewVideo = src.at("preview", "reddit_video_preview")
        if (items.isEmpty() && previewVideo != null) {
            items += videoFromRedditVideo(ctx, previewVideo, videoIdFrom(previewVideo), null)
        }

        if (items.isEmpty()) {
            val removed = post.strAt("removed_by_category")
            if (removed != null) fail(ErrorKind.DELETED, platform, "Removed ($removed)")
            fail(ErrorKind.NO_MEDIA, platform)
        }

        return PostInfo(
            platform = platform,
            url = permalink ?: "https://www.reddit.com/comments/$id/",
            id = id,
            title = post.strAt("title")?.truncateForTitle(),
            author = post.strAt("author")?.let { "u/$it" },
            thumbnailUrl = src.strAt("preview", "images", 0, "source", "url")
                ?: src.strAt("thumbnail")?.takeIf { it.startsWith("http") }
                ?: items.first().thumbnailUrl,
            items = items,
            source = name,
            timestampSec = post.at("created_utc").double?.toLong(),
        )
    }

    private fun hasOwnMedia(p: JsonElement) =
        p.at("media_metadata") != null || p.at("secure_media", "reddit_video") != null || p.at("media", "reddit_video") != null

    // ---- galleries & inline media -------------------------------------------------------------

    private fun galleryItems(p: JsonElement): List<MediaItem> {
        if (p.at("is_gallery").bool != true) return emptyList()
        val meta = p.at("media_metadata").obj ?: return emptyList()
        return p.arrAt("gallery_data", "items").mapNotNull { g ->
            val mediaId = g.strAt("media_id") ?: return@mapNotNull null
            imageFromMetadata(mediaId, meta[mediaId])?.copy(label = g.strAt("caption"))
        }
    }

    /** Images / videos embedded in a text post body (media_metadata without a gallery). */
    private suspend fun inlineMediaItems(p: JsonElement, ctx: ExtractionContext, permalink: String?): List<MediaItem> {
        if (p.at("is_gallery").bool == true) return emptyList()
        val meta = p.at("media_metadata").obj ?: return emptyList()
        return meta.entries.mapIndexedNotNull { i, (mediaId, m) ->
            if (m.strAt("e") == "RedditVideo") {
                val dash = m.strAt("dashUrl")
                if (dash != null) videoFromManifest(ctx, mediaId, dash, m.intAt("x"), m.intAt("y"), null, permalink, i) else null
            } else imageFromMetadata(mediaId, m)
        }
    }

    private fun imageFromMetadata(mediaId: String, m: JsonElement?): MediaItem? {
        if (m == null || (m.strAt("status") ?: "valid") != "valid") return null
        val w = m.intAt("s", "x")
        val h = m.intAt("s", "y")
        return when (m.strAt("e")) {
            "AnimatedImage" -> {
                val gif = m.strAt("s", "gif")
                val mp4 = m.strAt("s", "mp4")
                val url = gif ?: mp4 ?: return null
                MediaItem(
                    id = mediaId, type = MediaType.GIF, url = url, altUrls = listOfNotNull(mp4.takeIf { gif == null }),
                    ext = if (gif != null) "gif" else "mp4", width = w, height = h, thumbnailUrl = m.strAt("p", -1, "u") ?: url,
                    hasAudio = false,
                )
            }
            "Image", null -> {
                val mime = m.strAt("m") ?: "image/jpg"
                val ext = mime.substringAfter('/').let { if (it == "jpeg") "jpg" else it }
                val original = "https://i.redd.it/$mediaId.$ext"
                val signed = m.strAt("s", "u")
                MediaItem(
                    id = mediaId, type = if (ext == "gif") MediaType.GIF else MediaType.IMAGE, url = original,
                    altUrls = listOfNotNull(signed), ext = ext, width = w, height = h,
                    thumbnailUrl = m.arrAt("p").lastOrNull().strAt("u") ?: signed ?: original,
                )
            }
            else -> null
        }
    }

    // ---- v.redd.it ----------------------------------------------------------------------------

    private fun videoIdFrom(rv: JsonElement): String? =
        (rv.strAt("fallback_url") ?: rv.strAt("dash_url") ?: rv.strAt("hls_url"))
            ?.toHttpUrlOrNull()?.takeIf { it.host == "v.redd.it" }?.pathSegments?.firstOrNull()

    private suspend fun videoFromRedditVideo(ctx: ExtractionContext, rv: JsonElement?, videoId: String?, permalink: String?): MediaItem {
        val id = videoId ?: "video"
        val dash = rv.strAt("dash_url") ?: videoId?.let { "https://v.redd.it/$it/DASHPlaylist.mpd" }
        val isGif = rv.at("is_gif").bool == true
        val declaredAudio = rv.at("has_audio").bool
        if (dash != null) {
            runCatching {
                return videoFromManifest(ctx, id, dash, rv.intAt("width"), rv.intAt("height"), rv.at("duration").double, permalink, 0,
                    knownNoAudio = isGif || declaredAudio == false)
            }
        }
        // Manifest unavailable: use the progressive fallback stream plus the usual audio file names.
        val fallback = rv.strAt("fallback_url") ?: fail(ErrorKind.NO_MEDIA, platform, "Video stream missing")
        val audioGuesses = if (isGif || declaredAudio == false || videoId == null) emptyList() else guessAudio(videoId)
        return MediaItem(
            id = id, type = MediaType.VIDEO, ext = "mp4",
            variants = listOf(VideoVariant(fallback, rv.intAt("height"), rv.intAt("width"), audioUrls = audioGuesses, hasAudio = false)),
            width = rv.intAt("width"), height = rv.intAt("height"), durationSec = rv.at("duration").double,
            engineUrl = permalink, preferEngine = permalink != null,
            hasAudio = if (isGif) false else declaredAudio,
        )
    }

    private suspend fun videoFromManifest(
        ctx: ExtractionContext, id: String, dashUrl: String, width: Int?, height: Int?, duration: Double?,
        permalink: String?, index: Int, knownNoAudio: Boolean = false,
    ): MediaItem {
        val r = ctx.http.getOk(dashUrl, platform)
        val reps = DashManifest.parse(r.body, r.finalUrl)
        val videos = reps.filter { !it.isAudio }.sortedWith(compareByDescending<DashManifest.Representation> { it.height ?: 0 }.thenByDescending { it.bandwidth })
        if (videos.isEmpty()) fail(ErrorKind.NO_MEDIA, platform, "No video streams in manifest")
        val audio = reps.filter { it.isAudio }.sortedByDescending { it.bandwidth }.map { it.url }
        val audioUrls = if (knownNoAudio) emptyList() else audio
        val variants = videos.distinctBy { it.height }.map {
            VideoVariant(it.url, it.height, it.width, it.bandwidth, audioUrls = audioUrls, hasAudio = false)
        }
        return MediaItem(
            id = id, type = MediaType.VIDEO, ext = "mp4", variants = variants,
            width = videos.first().width ?: width, height = videos.first().height ?: height, durationSec = duration,
            engineUrl = permalink, enginePlaylistIndex = null, preferEngine = permalink != null && index == 0,
            hasAudio = audioUrls.isNotEmpty(),
        )
    }

    private fun guessAudio(videoId: String) = listOf("DASH_AUDIO_128.mp4", "DASH_AUDIO_64.mp4", "DASH_audio.mp4", "CMAF_AUDIO_128.mp4", "CMAF_AUDIO_64.mp4", "audio")
        .map { "https://v.redd.it/$videoId/$it" }

    // ---- link posts ---------------------------------------------------------------------------

    private suspend fun linkedMediaItems(p: JsonElement, ctx: ExtractionContext): List<MediaItem> {
        val link = p.strAt("url_overridden_by_dest") ?: p.strAt("url") ?: return emptyList()
        val host = link.toHttpUrlOrNull()?.host?.lowercase() ?: return emptyList()
        val preview = p.strAt("preview", "images", 0, "source", "url")
        val pw = p.intAt("preview", "images", 0, "source", "width")
        val ph = p.intAt("preview", "images", 0, "source", "height")

        if (host == "i.redd.it" || host == "preview.redd.it") {
            val ext = extensionFromUrl(link) ?: "jpg"
            val type = if (ext == "gif") MediaType.GIF else MediaType.IMAGE
            return listOf(MediaItem(p.strAt("id") ?: "img", type, url = link, altUrls = listOfNotNull(preview), ext = ext,
                width = pw, height = ph, thumbnailUrl = preview ?: link, hasAudio = if (type == MediaType.GIF) false else null))
        }
        embeds.imgurDirect(link)?.let { r ->
            return listOf(MediaItem("imgur", if (r.isVideo) MediaType.GIF else MediaType.IMAGE, url = r.url, ext = extensionFromUrl(r.url) ?: "jpg",
                thumbnailUrl = r.thumbnail ?: preview, width = pw, height = ph, engineUrl = link.takeIf { r.isVideo }, hasAudio = r.hasAudio))
        }
        if (embeds.isRedgifs(link)) {
            val r = runCatching { embeds.redgifs(link) }.getOrNull()
            return listOf(MediaItem("redgifs", MediaType.VIDEO, variants = listOfNotNull(r?.let { VideoVariant(it.url, it.height, it.width, hasAudio = it.hasAudio) }),
                ext = "mp4", width = r?.width, height = r?.height, thumbnailUrl = r?.thumbnail ?: preview,
                engineUrl = link, preferEngine = r == null, hasAudio = r?.hasAudio))
        }
        if (host.endsWith("reddit.com") || host == "redd.it") return emptyList()
        // Other hosts (imgur albums, streamable, youtube, ...): let the engine handle it.
        if (p.strAt("post_hint") in setOf("hosted:video", "rich:video", "link", "image") || p.at("secure_media") != null) {
            return listOf(MediaItem("external", MediaType.VIDEO, ext = "mp4", thumbnailUrl = preview, engineUrl = link, preferEngine = true,
                label = "Linked from $host"))
        }
        return emptyList()
    }
}
