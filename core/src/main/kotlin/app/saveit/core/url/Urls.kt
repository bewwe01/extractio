package app.saveit.core.url

import app.saveit.core.model.Platform
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.util.Base64

/** Pulls URLs out of free-form share text ("Check this out! https://vm.tiktok.com/ZM.../ via @app"). */
object UrlExtractor {
    private val schemeUrl = Regex("""(?i)https?://[^\s<>"'`{}|\\^\[\]「」『』【】《》〈〉（）]+""")

    // Links pasted without a scheme ("vm.tiktok.com/xyz", "www.instagram.com/reel/...").
    private val bareUrl = Regex(
        """(?i)(?<![\w@./])((?:[a-z0-9-]+\.)*(?:reddit\.com|redd\.it|twitter\.com|x\.com|fxtwitter\.com|vxtwitter\.com|fixupx\.com|""" +
            """instagram\.com|instagr\.am|tiktok\.com|snapchat\.com|facebook\.com|fb\.watch|fb\.com|pinterest\.[a-z.]{2,6}|pin\.it|t\.co)""" +
            """/[^\s<>"'`{}|\\^\[\]]*)""",
    )

    private const val TRAILING = ".,;:!?)]}>'\"`»”’…*_~」』】》〉）。、，！？"

    fun findUrls(text: String): List<String> {
        val found = LinkedHashSet<String>()
        schemeUrl.findAll(text).forEach { found += clean(it.value) }
        val withoutSchemeUrls = schemeUrl.replace(text, " ")
        bareUrl.findAll(withoutSchemeUrls).forEach { found += "https://" + clean(it.groupValues[1]) }
        return found.filter { it.toHttpUrlOrNull() != null }
    }

    /** First URL in [text] that belongs to a supported platform (or a direct media URL). */
    fun findFirstSupported(text: String): String? =
        findUrls(text).firstOrNull { PlatformDetector.detect(it) != null }

    private fun clean(raw: String): String {
        var s = raw
        // Markdown link: [title](https://...) leaves a trailing ')' — keep balanced parentheses only.
        while (s.isNotEmpty() && s.last() in TRAILING) {
            if (s.last() == ')' && s.count { it == '(' } >= s.count { it == ')' }) break
            s = s.dropLast(1)
        }
        // Share text sometimes glues the next word with an ellipsis or a non-breaking space.
        s = s.substringBefore(' ').substringBefore('…')
        return s
    }
}

object PlatformDetector {
    private val pinterestHost = Regex("""(^|\.)pinterest\.(com|[a-z]{2}|co\.[a-z]{2}|com\.[a-z]{2})$""")

    fun detect(url: String): Platform? {
        val u = url.toHttpUrlOrNull() ?: return null
        return detect(u)
    }

    fun detect(u: HttpUrl): Platform? {
        val h = u.host.lowercase().removePrefix("www.")
        return when {
            isDirectMediaHost(h) -> Platform.DIRECT
            h == "redd.it" || h.endsWith(".redd.it") || h.endsWith("reddit.com") || h == "reddit.app.link" || h.endsWith("redditmedia.com") -> Platform.REDDIT
            h in X_HOSTS || h.endsWith(".twitter.com") || h.endsWith(".x.com") || h.endsWith(".fxtwitter.com") ||
                h.endsWith(".vxtwitter.com") || h.endsWith(".fixupx.com") -> Platform.X
            h == "instagram.com" || h.endsWith(".instagram.com") || h == "instagr.am" || h.endsWith("ddinstagram.com") -> Platform.INSTAGRAM
            h == "tiktok.com" || h.endsWith(".tiktok.com") -> Platform.TIKTOK
            h == "snapchat.com" || h.endsWith(".snapchat.com") -> Platform.SNAPCHAT
            h == "facebook.com" || h.endsWith(".facebook.com") || h == "fb.watch" || h == "fb.com" || h == "fb.me" ||
                h.endsWith(".fb.watch") -> Platform.FACEBOOK
            pinterestHost.containsMatchIn(h) || h == "pin.it" -> Platform.PINTEREST
            else -> null
        }
    }

    private val X_HOSTS = setOf(
        "twitter.com", "x.com", "mobile.twitter.com", "mobile.x.com", "fxtwitter.com", "fixupx.com", "vxtwitter.com",
        "fixvx.com", "twittpr.com", "t.co", "nitter.net",
    )

    /** CDN hosts that serve a media file directly (someone pasted "copy image address"). */
    fun isDirectMediaHost(host: String): Boolean {
        val h = host.lowercase()
        return h == "i.redd.it" || h == "preview.redd.it" || h == "external-preview.redd.it" ||
            h == "i.imgur.com" || h == "pbs.twimg.com" || h == "video.twimg.com" ||
            h == "i.pinimg.com" || h == "v.pinimg.com" || h.endsWith(".pinimg.com") && h.startsWith("v") ||
            h.endsWith(".cdninstagram.com") || h.endsWith(".fbcdn.net")
    }
}

/**
 * Canonicalizes post URLs: one host per platform, no tracking parameters, no trailing noise.
 * Pure function — short links are left alone (see [needsResolution]) and handled by [ShortLinkResolver].
 */
object UrlNormalizer {

    private val FACEBOOK_KEEP_PARAMS = setOf("v", "story_fbid", "id", "fbid", "set", "multi_permalinks")

    fun normalize(input: String): String {
        val url = input.trim().let { if (it.startsWith("http", ignoreCase = true)) it else "https://$it" }
        val u = url.toHttpUrlOrNull() ?: return url
        val platform = PlatformDetector.detect(u) ?: return stripTracking(u).toString()
        return when (platform) {
            Platform.REDDIT -> reddit(u)
            Platform.X -> twitter(u)
            Platform.INSTAGRAM -> instagram(u)
            Platform.TIKTOK -> tiktok(u)
            Platform.SNAPCHAT -> snapchat(u)
            Platform.FACEBOOK -> facebook(u)
            Platform.PINTEREST -> pinterest(u)
            Platform.DIRECT -> direct(u)
        } ?: stripTracking(u).toString()
    }

    /** Short links whose target is only known after following redirects. */
    fun needsResolution(url: String): Boolean {
        val u = url.toHttpUrlOrNull() ?: return false
        val h = u.host.lowercase().removePrefix("www.")
        val segs = u.pathSegments.filter { it.isNotEmpty() }
        return when {
            h == "vm.tiktok.com" || h == "vt.tiktok.com" -> true
            h.endsWith("tiktok.com") && segs.firstOrNull() == "t" -> true
            h == "pin.it" -> true
            h == "api.pinterest.com" && segs.firstOrNull() == "url_shortener" -> true
            h == "fb.watch" || h == "fb.me" -> true
            h.endsWith("facebook.com") && segs.firstOrNull() == "share" -> true
            h == "t.co" -> true
            h == "v.redd.it" -> true
            h == "reddit.app.link" -> true
            h.endsWith("reddit.com") && segs.size >= 4 && segs[0] == "r" && segs[2] == "s" -> true
            h == "t.snapchat.com" -> true
            h.endsWith("snapchat.com") && segs.firstOrNull() == "t" -> true
            h.endsWith("instagram.com") && segs.firstOrNull() == "share" -> true
            else -> false
        }
    }

    /** The URL to hand to yt-dlp (some of its URL patterns are narrower than the platforms' own). */
    fun engineUrl(url: String): String {
        val u = url.toHttpUrlOrNull() ?: return url
        if (PlatformDetector.detect(u) == Platform.TIKTOK) {
            val segs = u.pathSegments
            val i = segs.indexOf("photo")
            if (i > 0 && i + 1 < segs.size) return "https://www.tiktok.com/${segs[i - 1]}/video/${segs[i + 1]}"
        }
        return url
    }

    // ---- per platform -------------------------------------------------------------------------

    private fun segments(u: HttpUrl) = u.pathSegments.filter { it.isNotEmpty() }

    private fun reddit(u: HttpUrl): String? {
        val h = u.host.lowercase()
        val s = segments(u)
        if (h == "redd.it" && s.isNotEmpty()) return "https://www.reddit.com/comments/${s[0]}/"
        if (h == "v.redd.it" || h == "reddit.app.link") return u.newBuilder().query(null).fragment(null).build().toString()
        // reddit.com/media?url=https%3A%2F%2Fi.redd.it%2Fabc.jpg
        if (s.firstOrNull() == "media") u.queryParameter("url")?.let { return normalize(it) }
        if (s.firstOrNull() == "gallery" && s.size >= 2) return "https://www.reddit.com/comments/${s[1]}/"
        val ci = s.indexOf("comments")
        if (ci >= 0 && ci + 1 < s.size) {
            val prefix = s.subList(0, ci)
            val keepPrefix = if (prefix.size >= 2 && prefix[0] in setOf("r", "user", "u")) {
                listOf(if (prefix[0] == "u") "user" else prefix[0], prefix[1])
            } else emptyList()
            val parts = keepPrefix + listOf("comments", s[ci + 1]) + listOfNotNull(s.getOrNull(ci + 2))
            return "https://www.reddit.com/" + parts.joinToString("/") + "/"
        }
        // Share link /r/sub/s/CODE: keep as is (resolved later) but drop tracking.
        if (s.size >= 4 && s[0] == "r" && s[2] == "s") return "https://www.reddit.com/r/${s[1]}/s/${s[3]}"
        return null
    }

    private val statusId = Regex("""^\d{5,25}$""")

    private fun twitter(u: HttpUrl): String? {
        val h = u.host.lowercase()
        if (h == "t.co") return u.newBuilder().query(null).fragment(null).build().toString()
        val s = segments(u)
        // /{user}/status/{id}[/photo/1|/video/1], /i/web/status/{id}, /i/status/{id}, /statuses/{id}
        val si = s.indexOfFirst { it == "status" || it == "statuses" }
        if (si >= 0 && si + 1 < s.size) {
            val id = s[si + 1].takeWhile { it.isDigit() }
            if (!statusId.matches(id)) return null
            val user = when {
                si >= 1 && s[si - 1] == "web" -> "i"
                si >= 1 -> s[si - 1]
                else -> "i"
            }
            return "https://x.com/$user/status/$id"
        }
        return null
    }

    private fun instagram(u: HttpUrl): String? {
        val s = segments(u)
        if (s.isEmpty()) return null
        if (s[0] == "share") return u.newBuilder().host("www.instagram.com").query(null).fragment(null).build().toString()
        val kindIdx = s.indexOfFirst { it in setOf("p", "reel", "reels", "tv") }
        if (kindIdx >= 0 && kindIdx + 1 < s.size) {
            val kind = when (s[kindIdx]) { "reels" -> "reel"; else -> s[kindIdx] }
            return "https://www.instagram.com/$kind/${s[kindIdx + 1]}/"
        }
        if (s[0] == "stories" && s.size >= 3) {
            return if (s[1] == "highlights") "https://www.instagram.com/stories/highlights/${s[2]}/"
            else "https://www.instagram.com/stories/${s[1]}/${s[2]}/"
        }
        if (s[0] == "stories" && s.size == 2) return "https://www.instagram.com/stories/${s[1]}/"
        // Highlight share links: /s/<base64("highlight:<id>")>?story_media_id=...
        if (s[0] == "s" && s.size >= 2) {
            val decoded = runCatching {
                String(Base64.getUrlDecoder().decode(s[1].padEnd((s[1].length + 3) / 4 * 4, '=')))
            }.getOrNull()
            if (decoded != null && decoded.startsWith("highlight:")) {
                return "https://www.instagram.com/stories/highlights/${decoded.removePrefix("highlight:")}/"
            }
        }
        return null
    }

    private fun tiktok(u: HttpUrl): String? {
        val h = u.host.lowercase()
        val s = segments(u)
        if (h == "vm.tiktok.com" || h == "vt.tiktok.com" || s.firstOrNull() == "t") {
            return u.newBuilder().query(null).fragment(null).build().toString().trimEnd('/') + "/"
        }
        // /@user/video/123, /@user/photo/123
        val vi = s.indexOfFirst { it == "video" || it == "photo" }
        if (vi >= 0 && vi + 1 < s.size && s[vi + 1].all { it.isDigit() }) {
            val user = s.getOrNull(vi - 1)?.takeIf { it.startsWith("@") } ?: "@"
            return "https://www.tiktok.com/$user/${s[vi]}/${s[vi + 1]}"
        }
        // m.tiktok.com/v/123.html, /embed/v2/123, /embed/123
        if (s.firstOrNull() == "v" && s.size >= 2) {
            val id = s[1].removeSuffix(".html")
            if (id.all { it.isDigit() }) return "https://www.tiktok.com/@/video/$id"
        }
        if (s.firstOrNull() == "embed") {
            val id = s.last()
            if (id.all { it.isDigit() }) return "https://www.tiktok.com/@/video/$id"
        }
        return null
    }

    private fun snapchat(u: HttpUrl): String? {
        val h = u.host.lowercase()
        val s = segments(u)
        if (h == "t.snapchat.com") return u.newBuilder().query(null).fragment(null).build().toString()
        if (s.isEmpty()) return null
        val host = if (h == "story.snapchat.com") "story.snapchat.com" else "www.snapchat.com"
        return u.newBuilder().scheme("https").host(host).query(null).fragment(null).build().toString().trimEnd('/')
    }

    private fun facebook(u: HttpUrl): String? {
        val h = u.host.lowercase()
        if (h == "fb.watch" || h.endsWith(".fb.watch") || h == "fb.me") return u.newBuilder().query(null).fragment(null).build().toString()
        val s = segments(u)
        // Login interstitials carry the real target in ?next=
        if (s.firstOrNull() == "login" || s.firstOrNull() == "login.php") {
            u.queryParameter("next")?.let { next -> if (PlatformDetector.detect(next) == Platform.FACEBOOK) return normalize(next) }
        }
        val b = u.newBuilder().scheme("https").host("www.facebook.com").fragment(null).query(null)
        FACEBOOK_KEEP_PARAMS.forEach { p -> u.queryParameter(p)?.let { b.addQueryParameter(p, it) } }
        return b.build().toString()
    }

    private val pinId = Regex("""^(?:.*--)?(\d{6,25})$""")

    private fun pinterest(u: HttpUrl): String? {
        val h = u.host.lowercase()
        if (h == "pin.it") return u.newBuilder().query(null).fragment(null).build().toString()
        val s = segments(u)
        val pi = s.indexOf("pin")
        if (pi >= 0 && pi + 1 < s.size) {
            val m = pinId.find(s[pi + 1]) ?: return null
            return "https://www.pinterest.com/pin/${m.groupValues[1]}/"
        }
        return null
    }

    private fun direct(u: HttpUrl): String {
        val h = u.host.lowercase()
        // preview.redd.it/<id>.jpg?width=640&s=... -> original on i.redd.it
        if (h == "preview.redd.it") {
            val name = u.pathSegments.lastOrNull().orEmpty()
            if (name.isNotEmpty() && !name.endsWith(".gif")) return "https://i.redd.it/$name"
            return u.toString()
        }
        if (h == "pbs.twimg.com" && u.pathSegments.firstOrNull() == "media") {
            return TwitterUrls.originalPhoto(u.toString())
        }
        return u.toString()
    }

    private val TRACKING_PREFIXES = listOf("utm_", "fb_", "ga_", "mc_", "pk_")
    private val TRACKING_PARAMS = setOf(
        "fbclid", "gclid", "igsh", "igshid", "si", "s", "t", "ref", "ref_src", "ref_url", "share_id", "_r", "_t",
        "is_from_webapp", "sender_device", "sender_web_id", "share_app_id", "share_link_id", "social_sharing",
        "tt_from", "u_code", "preview_pb", "_d", "timestamp", "user_id", "mibextid", "rdid", "share_url", "context",
        "sh", "feature", "invite_code", "embed_source", "web_id", "checksum", "sec_user_id", "correlation_id",
        "share_item_id", "utm", "rcm", "src", "__cft__[0]", "__tn__", "sfnsn", "wtsid", "_rdc", "_rdr",
    )

    /** Removes well-known tracking parameters from an otherwise unknown URL. */
    fun stripTracking(u: HttpUrl): HttpUrl {
        val b = u.newBuilder().query(null).fragment(null)
        for (name in u.queryParameterNames) {
            val lower = name.lowercase()
            if (lower in TRACKING_PARAMS || TRACKING_PREFIXES.any { lower.startsWith(it) } || lower.startsWith("__cft__")) continue
            u.queryParameterValues(name).forEach { b.addQueryParameter(name, it) }
        }
        return b.build()
    }
}

object TwitterUrls {
    /** pbs.twimg.com/media/ABC.jpg or ABC?format=jpg&name=small -> ABC?format=jpg&name=orig */
    fun originalPhoto(url: String): String {
        val u = url.toHttpUrlOrNull() ?: return url
        val last = u.pathSegments.lastOrNull() ?: return url
        val base = last.substringBeforeLast('.', last)
        val ext = if ('.' in last) last.substringAfterLast('.') else u.queryParameter("format") ?: "jpg"
        val path = u.pathSegments.dropLast(1).joinToString("/") + "/" + base
        return "https://pbs.twimg.com/$path?format=$ext&name=orig"
    }
}
