package app.saveit.core.extractor

import app.saveit.core.error.ErrorKind
import app.saveit.core.error.SaveItException
import app.saveit.core.model.Platform
import app.saveit.core.model.PostInfo
import app.saveit.core.net.Http
import app.saveit.core.util.at
import app.saveit.core.util.bool
import app.saveit.core.util.intAt
import app.saveit.core.util.strAt
import org.jsoup.Jsoup
import org.jsoup.parser.Parser
import java.net.URI

/**
 * A platform's own (non-yt-dlp) extractor. One class per platform so a platform that changes its
 * internals can be fixed in isolation. Implementations use the platform's public pages/endpoints only.
 */
interface PlatformExtractor {
    val platform: Platform

    /** Short identifier shown in diagnostics / verification reports. */
    val name: String

    /**
     * Whether yt-dlp should be tried before this extractor. Platforms where yt-dlp misses image
     * content (Reddit galleries, X photos, Pinterest images, Instagram photos) run the fallback first.
     */
    val engineFirst: Boolean

    suspend fun extract(url: String, ctx: ExtractionContext): PostInfo
}

class ExtractionContext(
    val http: Http,
    /** True when the user imported a login session for the platform being extracted. */
    val loggedIn: (Platform) -> Boolean = { false },
)

internal fun fail(kind: ErrorKind, platform: Platform, detail: String? = null): Nothing =
    throw SaveItException(kind, platform, detail)

/** Minimal MPEG-DASH manifest reader: enough for Reddit / Facebook style single-period manifests. */
object DashManifest {
    data class Representation(
        val url: String,
        val isAudio: Boolean,
        val bandwidth: Long,
        val width: Int?,
        val height: Int?,
    )

    fun parse(xml: String, manifestUrl: String): List<Representation> {
        val doc = Jsoup.parse(xml, manifestUrl, Parser.xmlParser())
        val out = ArrayList<Representation>()
        val mpdBase = doc.selectFirst("MPD > BaseURL")?.text()?.trim()
        for (set in doc.select("AdaptationSet")) {
            val setType = set.attr("contentType").ifEmpty { set.attr("mimeType").substringBefore('/') }
            val setBase = set.selectFirst("> BaseURL")?.text()?.trim()
            for (rep in set.select("Representation")) {
                val type = setType.ifEmpty { rep.attr("mimeType").substringBefore('/') }
                val repBase = rep.selectFirst("BaseURL")?.text()?.trim() ?: continue
                val resolved = listOfNotNull(mpdBase, setBase, repBase).fold(manifestUrl) { acc, part -> resolve(acc, part) }
                out += Representation(
                    url = resolved,
                    isAudio = type == "audio" || (type.isEmpty() && rep.attr("audioSamplingRate").isNotEmpty() && rep.attr("height").isEmpty()),
                    bandwidth = rep.attr("bandwidth").toLongOrNull() ?: 0,
                    width = rep.attr("width").toIntOrNull(),
                    height = rep.attr("height").toIntOrNull(),
                )
            }
        }
        return out
    }

    private fun resolve(base: String, ref: String): String = runCatching { URI(base).resolve(ref).toString() }.getOrDefault(ref)
}

/** Resolves embeds that commonly appear inside Reddit posts (Redgifs, Imgur). */
class EmbedResolver(private val http: Http) {

    data class Resolved(val url: String, val isVideo: Boolean, val hasAudio: Boolean?, val thumbnail: String?, val width: Int?, val height: Int?)

    private val redgifsId = Regex("""redgifs\.com/(?:watch|ifr|i)/([A-Za-z]+)""", RegexOption.IGNORE_CASE)

    fun isRedgifs(url: String) = redgifsId.containsMatchIn(url)

    suspend fun redgifs(url: String): Resolved? {
        val id = redgifsId.find(url)?.groupValues?.get(1)?.lowercase() ?: return null
        val token = http.get("https://api.redgifs.com/v2/auth/temporary", mapOf("Accept" to "application/json"))
            .json().strAt("token") ?: return null
        val gif = http.get(
            "https://api.redgifs.com/v2/gifs/$id",
            mapOf("Authorization" to "Bearer $token", "Accept" to "application/json"),
        ).json().at("gif") ?: return null
        val hd = gif.strAt("urls", "hd") ?: gif.strAt("urls", "sd") ?: return null
        return Resolved(
            url = hd,
            isVideo = true,
            hasAudio = gif.at("hasAudio").bool,
            thumbnail = gif.strAt("urls", "poster") ?: gif.strAt("urls", "thumbnail"),
            width = gif.intAt("width"),
            height = gif.intAt("height"),
        )
    }

    /** i.imgur.com/X.gifv → .mp4 ; single images pass through. Albums are left to yt-dlp. */
    fun imgurDirect(url: String): Resolved? {
        val m = Regex("""^https?://i\.imgur\.com/([A-Za-z0-9]+)\.(gifv|mp4|jpe?g|png|gif|webp)""", RegexOption.IGNORE_CASE).find(url) ?: return null
        val (id, ext) = m.destructured
        return when (ext.lowercase()) {
            "gifv", "mp4" -> Resolved("https://i.imgur.com/$id.mp4", true, null, "https://i.imgur.com/${id}h.jpg", null, null)
            else -> Resolved("https://i.imgur.com/$id.${ext.lowercase()}", false, null, url, null, null)
        }
    }
}

internal object Html {
    fun parse(html: String, baseUrl: String) = Jsoup.parse(html, baseUrl)
}
