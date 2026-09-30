package app.saveit.core

import app.saveit.core.engine.EngineException
import app.saveit.core.engine.YtDlpArgs
import app.saveit.core.engine.YtDlpEngine
import app.saveit.core.engine.YtDlpJsonParser
import app.saveit.core.error.ErrorClassifier
import app.saveit.core.error.ErrorKind
import app.saveit.core.error.SaveItException
import app.saveit.core.extractor.EmbedResolver
import app.saveit.core.extractor.ExtractionContext
import app.saveit.core.extractor.FacebookExtractor
import app.saveit.core.extractor.InstagramExtractor
import app.saveit.core.extractor.PinterestExtractor
import app.saveit.core.extractor.PlatformExtractor
import app.saveit.core.extractor.RedditExtractor
import app.saveit.core.extractor.SnapchatExtractor
import app.saveit.core.extractor.TikTokExtractor
import app.saveit.core.extractor.TwitterExtractor
import app.saveit.core.model.MediaItem
import app.saveit.core.model.MediaType
import app.saveit.core.model.Platform
import app.saveit.core.model.PostInfo
import app.saveit.core.model.VideoVariant
import app.saveit.core.net.Http
import app.saveit.core.url.LinkAnalyzer
import app.saveit.core.url.PreparedLink
import app.saveit.core.url.ShortLinkResolver
import app.saveit.core.url.UrlNormalizer
import app.saveit.core.util.extensionFromUrl
import kotlinx.coroutines.CancellationException
import java.io.File

/** Diagnostic trail of what was tried, surfaced by the verifier and in debug logs. */
data class ResolveAttempt(val extractor: String, val ok: Boolean, val detail: String?)

/**
 * Turns share text into a [PostInfo]: cleans and resolves the link, then runs the yt-dlp engine and the
 * platform's own extractor in the platform's preferred order, merging when one of them misses content.
 */
class MediaResolver(
    private val http: Http,
    private val engine: YtDlpEngine?,
    private val cookieFileFor: (Platform) -> File? = { null },
    private val loggedIn: (Platform) -> Boolean = { false },
    val extractors: Map<Platform, PlatformExtractor> = defaultExtractors(http),
) {
    private val analyzer = LinkAnalyzer(ShortLinkResolver(http))

    var lastAttempts: List<ResolveAttempt> = emptyList()
        private set

    /** Share text → canonical post URL. Network failures while following short links become [SaveItException]s. */
    suspend fun prepare(text: String): PreparedLink = try {
        analyzer.prepare(text)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        throw ErrorClassifier.toException(e, app.saveit.core.url.UrlExtractor.findFirstSupported(text)?.let(app.saveit.core.url.PlatformDetector::detect))
    }

    suspend fun resolve(text: String): PostInfo = resolve(prepare(text))

    suspend fun resolve(link: PreparedLink): PostInfo {
        val attempts = ArrayList<ResolveAttempt>()
        lastAttempts = attempts
        if (link.platform == Platform.DIRECT) return direct(link.url)

        val extractor = extractors[link.platform] ?: throw SaveItException(ErrorKind.UNSUPPORTED_URL, link.platform)
        val ctx = ExtractionContext(http, loggedIn)
        val errors = ArrayList<SaveItException>()

        suspend fun viaEngine(): PostInfo? {
            val eng = engine ?: return null
            return try {
                val out = eng.run(YtDlpArgs.info(UrlNormalizer.engineUrl(link.url), cookieFileFor(link.platform)))
                YtDlpJsonParser.parse(out, link.platform, link.url).takeIf { it.items.isNotEmpty() }.also {
                    attempts += ResolveAttempt("yt-dlp", it != null, if (it == null) "no media" else "${it.items.size} item(s)")
                } ?: run {
                    errors += SaveItException(ErrorKind.NO_MEDIA, link.platform, "Engine found no media")
                    null
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: EngineException) {
                val ex = SaveItException(ErrorClassifier.classify(e.output), link.platform, e.message, e)
                errors += ex
                attempts += ResolveAttempt("yt-dlp", false, "${ex.kind}: ${e.message}")
                null
            } catch (e: Exception) {
                val ex = ErrorClassifier.toException(e, link.platform)
                errors += ex
                attempts += ResolveAttempt("yt-dlp", false, "${ex.kind}: ${e.message}")
                null
            }
        }

        suspend fun viaExtractor(): PostInfo? = try {
            extractor.extract(link.url, ctx).also { attempts += ResolveAttempt(extractor.name, true, "${it.items.size} item(s)") }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val ex = ErrorClassifier.toException(e, link.platform)
            errors += ex
            attempts += ResolveAttempt(extractor.name, false, "${ex.kind}: ${ex.detail ?: e.message}")
            null
        }

        val result: PostInfo? = if (extractor.engineFirst) {
            val fromEngine = viaEngine()
            when {
                fromEngine == null -> viaExtractor()
                missesContent(link.platform, fromEngine) -> viaExtractor()?.let { merge(fromEngine, it) } ?: fromEngine
                else -> fromEngine
            }
        } else {
            viaExtractor() ?: viaEngine()
        }
        return result ?: throw bestError(errors, link.platform)
    }

    /** Engine results that are known to be incomplete for a platform. */
    private fun missesContent(platform: Platform, post: PostInfo): Boolean = when (platform) {
        // TikTok photo slideshows: yt-dlp only returns the soundtrack.
        Platform.TIKTOK -> post.items.all { it.type == MediaType.AUDIO }
        else -> false
    }

    private fun merge(engine: PostInfo, fallback: PostInfo): PostInfo {
        val hasVisual = fallback.items.any { it.type != MediaType.AUDIO }
        if (!hasVisual) return engine
        val audio = fallback.items.filter { it.type == MediaType.AUDIO }
            .ifEmpty { engine.items.filter { it.type == MediaType.AUDIO }.map { it.copy(selectedByDefault = false, label = it.label ?: "Soundtrack") } }
        return fallback.copy(
            items = fallback.items.filter { it.type != MediaType.AUDIO } + audio,
            title = fallback.title ?: engine.title,
            author = fallback.author ?: engine.author,
            source = "${fallback.source}+yt-dlp",
        )
    }

    private fun bestError(errors: List<SaveItException>, platform: Platform): SaveItException {
        if (errors.isEmpty()) return SaveItException(ErrorKind.NO_MEDIA, platform)
        val best = errors.maxBy { it.kind.specificity }
        // If only generic failures happened but the engine reported parsing problems, suggest an update.
        if (best.kind.specificity <= 1 && errors.any { it.kind == ErrorKind.ENGINE_OUTDATED }) {
            return errors.first { it.kind == ErrorKind.ENGINE_OUTDATED }
        }
        return best
    }

    private fun direct(url: String): PostInfo {
        val ext = extensionFromUrl(url) ?: if ("format=" in url) url.substringAfter("format=").substringBefore('&') else "jpg"
        val name = url.substringBefore('?').substringAfterLast('/').substringBeforeLast('.')
        val type = when (ext) {
            "mp4", "mov", "webm", "m3u8" -> MediaType.VIDEO
            "gif" -> MediaType.GIF
            "mp3", "m4a" -> MediaType.AUDIO
            else -> MediaType.IMAGE
        }
        val item = if (type == MediaType.VIDEO) {
            MediaItem(name, type, variants = listOf(VideoVariant(url, isManifest = ext == "m3u8")), ext = "mp4")
        } else MediaItem(name, type, url = url, ext = ext, thumbnailUrl = url.takeIf { type != MediaType.AUDIO })
        return PostInfo(Platform.DIRECT, url, name, title = name, author = null, thumbnailUrl = item.thumbnailUrl, items = listOf(item), source = "direct")
    }

    companion object {
        fun defaultExtractors(http: Http): Map<Platform, PlatformExtractor> = listOf(
            RedditExtractor(EmbedResolver(http)),
            TwitterExtractor(),
            InstagramExtractor(),
            TikTokExtractor(),
            SnapchatExtractor(),
            FacebookExtractor(),
            PinterestExtractor(),
        ).associateBy { it.platform }
    }
}
