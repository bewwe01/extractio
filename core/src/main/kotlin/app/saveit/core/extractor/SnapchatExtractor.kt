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
import app.saveit.core.util.findObjectsWithKey
import app.saveit.core.util.int
import app.saveit.core.util.intAt
import app.saveit.core.util.meta
import app.saveit.core.util.parseJsonOrNull
import app.saveit.core.util.strAt
import app.saveit.core.util.truncateForTitle
import kotlinx.serialization.json.JsonElement
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Snapchat public content only: Spotlight videos and public stories / public profiles (their current
 * story snaps). Private snaps, chats and friends-only stories are never reachable and are reported as such.
 */
class SnapchatExtractor : PlatformExtractor {
    override val platform = Platform.SNAPCHAT
    override val name = "snapchat-web"
    override val engineFirst = false

    override suspend fun extract(url: String, ctx: ExtractionContext): PostInfo {
        val segs = url.toHttpUrlOrNull()?.pathSegments?.filter { it.isNotEmpty() }.orEmpty()
        if (segs.firstOrNull() in setOf("lens", "unlock", "discover")) {
            fail(ErrorKind.UNSUPPORTED_CONTENT, platform, "Snapchat lenses and Discover shows can't be downloaded. Supported: public Spotlight videos and public stories.")
        }
        val r = ctx.http.get(url, userAgent = app.saveit.core.net.UserAgents.DESKTOP)
        if (r.code == 404) fail(ErrorKind.DELETED, platform, "This Snapchat link doesn't exist or is no longer public")
        if (!r.isSuccess) fail(ErrorKind.NETWORK, platform, "Snapchat HTTP ${r.code}")
        return parsePage(r.body, r.finalUrl)
    }

    internal fun parsePage(html: String, url: String): PostInfo {
        val doc = Html.parse(html, url)
        val next = doc.selectFirst("script#__NEXT_DATA__")?.data()?.let(::parseJsonOrNull)
        val props = next.at("props", "pageProps")
        val spotlightId = Regex("""/spotlight/([\w-]+)""").find(url)?.groupValues?.get(1)

        val items = ArrayList<MediaItem>()
        var title: String? = null
        var author: String? = null

        if (props != null) {
            // Spotlight: pick the story matching the URL id (the feed also contains recommendations).
            val stories = props.arrAt("spotlightFeed", "spotlightStories")
            val story = stories.firstOrNull { it.strAt("story", "storyId", "value") == spotlightId } ?: stories.firstOrNull()
            val vm = story.at("metadata", "videoMetadata") ?: props.at("videoMetadata")
            vm?.strAt("contentUrl")?.let { video ->
                items += MediaItem(
                    id = spotlightId ?: "spotlight", type = MediaType.VIDEO, ext = "mp4",
                    variants = listOf(VideoVariant(video, vm.intAt("height"), vm.intAt("width"))),
                    width = vm.intAt("width"), height = vm.intAt("height"),
                    durationSec = vm.at("durationMs").double?.div(1000), thumbnailUrl = vm.strAt("thumbnailUrl"),
                    engineUrl = url.takeIf { spotlightId != null },
                )
                title = vm.strAt("description") ?: vm.strAt("name")
                author = vm.strAt("creator", "personCreator", "username")
            }

            if (items.isEmpty()) {
                // Public story / profile / shared story page: every snap in the first story found.
                val snapList = props.at("story", "snapList")?.let { listOf(it) }
                    ?: props.findObjectsWithKey("snapList").map { it["snapList"]!! }
                snapList.firstOrNull()?.let { list -> items += snapItems(list) }
                author = props.strAt("userProfile", "publicProfileInfo", "username")
                    ?: props.strAt("userProfile", "userInfo", "username")
                    ?: props.strAt("story", "storyTitle", "value")
                title = props.strAt("story", "storyTitle", "value") ?: props.strAt("userProfile", "publicProfileInfo", "title")
            }
        }

        // Last resort: Open Graph video on shared snap pages.
        if (items.isEmpty()) {
            doc.meta("og:video", "og:video:secure_url", "twitter:player:stream")?.let { v ->
                items += MediaItem("snap", MediaType.VIDEO, ext = "mp4", variants = listOf(VideoVariant(v)),
                    thumbnailUrl = doc.meta("og:image"))
            }
        }
        if (items.isEmpty()) {
            val profileOnly = props?.at("userProfile") != null
            fail(
                ErrorKind.UNSUPPORTED_CONTENT, platform,
                if (profileOnly) "This Snapchat profile has no public story right now. Private snaps and friends-only stories can't be downloaded."
                else "No public Snapchat media found. Only public Spotlight videos and public stories can be downloaded; private snaps are not supported.",
            )
        }
        return PostInfo(
            platform = platform, url = url, id = spotlightId ?: url.substringAfterLast('/'),
            title = (title ?: doc.meta("og:title"))?.truncateForTitle(), author = author?.let { "@$it" },
            thumbnailUrl = items.first().thumbnailUrl ?: doc.meta("og:image"), items = items, source = name,
        )
    }

    private fun snapItems(list: JsonElement): List<MediaItem> = list.arrAt().ifEmpty { (list as? kotlinx.serialization.json.JsonArray) ?: emptyList() }
        .mapIndexedNotNull { i, snap ->
            val media = snap.strAt("snapUrls", "mediaUrl") ?: return@mapIndexedNotNull null
            val preview = snap.strAt("snapUrls", "mediaPreviewUrl", "value")
            val isVideo = snap.at("snapMediaType").int == 1
            val id = snap.strAt("snapId", "value") ?: "snap$i"
            if (isVideo) {
                MediaItem(id, MediaType.VIDEO, ext = "mp4", variants = listOf(VideoVariant(media)), thumbnailUrl = preview)
            } else {
                MediaItem(id, MediaType.IMAGE, url = media, ext = "jpg", thumbnailUrl = preview ?: media)
            }
        }
}
