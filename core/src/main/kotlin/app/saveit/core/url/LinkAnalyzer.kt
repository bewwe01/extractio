package app.saveit.core.url

import app.saveit.core.error.ErrorKind
import app.saveit.core.error.SaveItException
import app.saveit.core.model.Platform
import app.saveit.core.net.Http
import app.saveit.core.net.UserAgents
import app.saveit.core.net.await
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.jsoup.Jsoup

/**
 * Follows short links (vm.tiktok.com, pin.it, fb.watch, t.co, v.redd.it, reddit /s/ links, snapchat.com/t,
 * instagram.com/share, facebook.com/share) one hop at a time until a canonical post URL appears.
 */
class ShortLinkResolver(private val http: Http, private val maxHops: Int = 10) {

    suspend fun resolve(start: String): String = withContext(Dispatchers.IO) {
        var current = start
        repeat(maxHops) {
            if (!UrlNormalizer.needsResolution(current)) return@withContext current
            val next = nextHop(current) ?: return@withContext current
            val normalized = UrlNormalizer.normalize(next)
            if (normalized == current) return@withContext current
            current = normalized
        }
        current
    }

    private suspend fun nextHop(url: String): String? {
        // Mobile UA: several shorteners (TikTok, Pinterest) answer with a plain 301 for mobile clients.
        val req = http.request(url, userAgent = UserAgents.MOBILE)
        http.noRedirectClient.newCall(req).await().use { resp ->
            if (resp.isRedirect) {
                val loc = resp.header("Location") ?: return null
                return resp.request.url.resolve(loc)?.toString()
            }
            if (!resp.isSuccessful) {
                if (resp.code == 404 || resp.code == 410) {
                    throw SaveItException(ErrorKind.DELETED, PlatformDetector.detect(url), "Short link no longer exists")
                }
                return null
            }
            val body = resp.body?.source()?.let { src ->
                src.request(512 * 1024)
                src.buffer.readUtf8(minOf(src.buffer.size, 512L * 1024))
            } ?: return null
            return targetFromHtml(body, resp.request.url.toString())
        }
    }

    companion object {
        private val jsLocation = Regex("""(?:window\.)?location(?:\.href)?\s*=\s*["']([^"']+)["']""")

        /** t.co and some share pages answer 200 with a meta-refresh / canonical instead of a 30x. */
        fun targetFromHtml(html: String, baseUrl: String): String? {
            val doc = Jsoup.parse(html, baseUrl)
            doc.selectFirst("meta[http-equiv~=(?i)refresh]")?.attr("content")?.let { c ->
                val target = c.substringAfter("url=", c.substringAfter("URL=", "")).trim().trim('\'', '"')
                if (target.isNotEmpty()) return absolutize(baseUrl, target)
            }
            listOf("link[rel=canonical]" to "href", "meta[property=og:url]" to "content", "meta[property=al:web:url]" to "content")
                .forEach { (sel, attr) ->
                    val v = doc.selectFirst(sel)?.attr(attr)
                    if (!v.isNullOrBlank() && v != baseUrl && PlatformDetector.detect(absolutize(baseUrl, v)) != null) {
                        return absolutize(baseUrl, v)
                    }
                }
            jsLocation.find(html)?.groupValues?.get(1)?.replace("\\/", "/")?.let { return absolutize(baseUrl, it) }
            return null
        }

        private fun absolutize(base: String, target: String): String =
            base.toHttpUrlOrNull()?.resolve(target)?.toString() ?: target
    }
}

data class PreparedLink(val url: String, val platform: Platform, val originalText: String)

/** Share text → canonical, resolved post URL with its platform. */
class LinkAnalyzer(private val resolver: ShortLinkResolver) {

    suspend fun prepare(text: String): PreparedLink {
        val found = UrlExtractor.findFirstSupported(text)
            ?: throw SaveItException(ErrorKind.UNSUPPORTED_URL, null, "No supported link found in the shared text")
        var url = UrlNormalizer.normalize(found)
        if (UrlNormalizer.needsResolution(url)) {
            url = UrlNormalizer.normalize(resolver.resolve(url))
        }
        val platform = PlatformDetector.detect(url)
            ?: throw SaveItException(ErrorKind.UNSUPPORTED_URL, null, "Link points to an unsupported site: ${url.substringBefore('?')}")
        // Expired short links (TikTok, Pinterest, Snapchat) redirect to the platform's home page.
        if (found != url && isHomePage(url)) {
            throw SaveItException(ErrorKind.DELETED, platform, "This short link has expired or the post was removed")
        }
        return PreparedLink(url, platform, text)
    }

    private fun isHomePage(url: String): Boolean {
        val u = url.toHttpUrlOrNull() ?: return false
        val segs = u.pathSegments.filter { it.isNotEmpty() }
        return segs.isEmpty() || (segs.size == 1 && segs[0] in setOf("foryou", "explore", "home", "login", "404"))
    }
}
