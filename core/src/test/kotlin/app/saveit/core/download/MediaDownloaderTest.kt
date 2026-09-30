package app.saveit.core.download

import app.saveit.core.FakeHttp
import app.saveit.core.engine.EngineProgress
import app.saveit.core.engine.YtDlpEngine
import app.saveit.core.error.ErrorKind
import app.saveit.core.error.SaveItException
import app.saveit.core.model.MediaItem
import app.saveit.core.model.MediaType
import app.saveit.core.model.Platform
import app.saveit.core.model.PostInfo
import app.saveit.core.model.Quality
import app.saveit.core.model.VideoVariant
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** Runs the real download pipeline (HTTP → files → ffmpeg merge → ffprobe check) on generated media. */
class MediaDownloaderTest {
    companion object {
        private lateinit var dir: File
        private lateinit var videoOnly: ByteArray
        private lateinit var audioOnly: ByteArray
        private lateinit var muxed: ByteArray
        private lateinit var png: ByteArray
        private var hasFfmpeg = false

        private fun ff(vararg args: String) {
            val p = ProcessBuilder(listOf("ffmpeg", "-hide_banner", "-loglevel", "error", "-y") + args).redirectErrorStream(true).start()
            val out = p.inputStream.bufferedReader().readText()
            check(p.waitFor() == 0) { out }
        }

        @BeforeClass @JvmStatic fun generateMedia() {
            hasFfmpeg = runCatching { ProcessBuilder("ffmpeg", "-version").start().waitFor() == 0 }.getOrDefault(false)
            if (!hasFfmpeg) return
            dir = Files.createTempDirectory("saveit-media").toFile()
            ff("-f", "lavfi", "-i", "testsrc=size=320x240:rate=25:duration=2", "-c:v", "libx264", "-pix_fmt", "yuv420p", "-an", "$dir/v.mp4")
            ff("-f", "lavfi", "-i", "sine=frequency=440:duration=2", "-c:a", "aac", "$dir/a.m4a")
            ff("-i", "$dir/v.mp4", "-i", "$dir/a.m4a", "-c", "copy", "$dir/muxed.mp4")
            ff("-f", "lavfi", "-i", "color=c=red:size=64x48", "-frames:v", "1", "$dir/img.png")
            videoOnly = File(dir, "v.mp4").readBytes()
            audioOnly = File(dir, "a.m4a").readBytes()
            muxed = File(dir, "muxed.mp4").readBytes()
            png = File(dir, "img.png").readBytes()
        }
    }

    private val fake = FakeHttp()
    private val tools = ProcessMediaTools("ffmpeg", "ffprobe")
    private val work get() = Files.createTempDirectory("saveit-work").toFile()
    private fun post(vararg items: MediaItem) = PostInfo(Platform.REDDIT, "https://www.reddit.com/comments/x/", "x", "t", "u/a", null, items.toList(), "test")

    @Test fun mergesSeparateAudioTrackAfterSkippingDeadAudioUrl() = runBlocking {
        assumeTrue(hasFfmpeg)
        fake.onBytes("https://v.redd.it/abc/CMAF_720.mp4", videoOnly, "video/mp4")
        fake.on("https://v.redd.it/abc/DASH_AUDIO_128.mp4", code = 403)
        fake.onBytes("https://v.redd.it/abc/CMAF_AUDIO_128.mp4", audioOnly, "audio/mp4")
        val item = MediaItem("abc", MediaType.VIDEO, ext = "mp4", hasAudio = true, variants = listOf(
            VideoVariant("https://v.redd.it/abc/CMAF_720.mp4", 720, audioUrls = listOf("https://v.redd.it/abc/DASH_AUDIO_128.mp4", "https://v.redd.it/abc/CMAF_AUDIO_128.mp4"), hasAudio = false),
        ))
        val out = MediaDownloader(fake.http, null, tools).download(post(item), item, Quality.BEST, work, "p1")
        val probe = out.probe!!
        assertTrue("video stream", probe.hasVideo)
        assertTrue("audio stream", probe.hasAudio)
        assertEquals(2.0, probe.durationSec!!, 0.2)
        assertEquals("direct-merged", out.via)
        assertEquals("mp4", out.ext)
    }

    @Test fun refusesToDeliverSilentVideoWhenSourceHasAudio() = runBlocking {
        assumeTrue(hasFfmpeg)
        fake.onBytes("https://v.redd.it/s/CMAF_720.mp4", videoOnly, "video/mp4")
        fake.on("https://v.redd.it/s/audio", code = 404)
        val item = MediaItem("s", MediaType.VIDEO, ext = "mp4", hasAudio = true,
            variants = listOf(VideoVariant("https://v.redd.it/s/CMAF_720.mp4", 720, audioUrls = listOf("https://v.redd.it/s/audio"))))
        try {
            MediaDownloader(fake.http, null, tools).download(post(item), item, Quality.BEST, work, "p2")
            throw AssertionError("expected SILENT_RESULT")
        } catch (e: SaveItException) {
            assertEquals(ErrorKind.SILENT_RESULT, e.kind)
        }
    }

    @Test fun mislabeledMuxedStreamIsRejectedIfSilent() = runBlocking {
        assumeTrue(hasFfmpeg)
        // Source claims audio, the single progressive stream has none → must not be delivered.
        fake.onBytes("https://video.twimg.com/x.mp4", videoOnly, "video/mp4")
        val item = MediaItem("t", MediaType.VIDEO, ext = "mp4", hasAudio = true, variants = listOf(VideoVariant("https://video.twimg.com/x.mp4", 720)))
        try {
            MediaDownloader(fake.http, null, tools).download(post(item), item, Quality.BEST, work, "p3")
            throw AssertionError("expected SILENT_RESULT")
        } catch (e: SaveItException) {
            assertEquals(ErrorKind.SILENT_RESULT, e.kind)
        }
    }

    @Test fun gifStyleVideoWithoutAudioIsFine() = runBlocking {
        assumeTrue(hasFfmpeg)
        fake.onBytes("https://video.twimg.com/tweet_video/g.mp4", videoOnly, "video/mp4")
        val item = MediaItem("g", MediaType.GIF, ext = "mp4", hasAudio = false, variants = listOf(VideoVariant("https://video.twimg.com/tweet_video/g.mp4")))
        val out = MediaDownloader(fake.http, null, tools).download(post(item), item, Quality.BEST, work, "p4")
        assertTrue(out.probe!!.hasVideo)
    }

    @Test fun imageFallsBackToAltUrlAndFixesExtension() = runBlocking {
        assumeTrue(hasFfmpeg)
        fake.on("https://i.pinimg.com/originals/aa/x.jpg", code = 404)
        fake.onBytes("https://i.pinimg.com/originals/aa/x.png", png, "image/png")
        val item = MediaItem("i", MediaType.IMAGE, url = "https://i.pinimg.com/originals/aa/x.jpg",
            altUrls = listOf("https://i.pinimg.com/originals/aa/x.png"), ext = "jpg")
        val out = MediaDownloader(fake.http, null, tools).download(post(item), item, Quality.BEST, work, "p5")
        assertEquals("png", out.ext)
        assertEquals(64, out.probe!!.width)
        assertEquals(48, out.probe!!.height)
    }

    @Test fun htmlInsteadOfImageIsAnError() = runBlocking {
        assumeTrue(hasFfmpeg)
        fake.on("https://i.redd.it/gone.jpg", body = "<html>removed</html>", contentType = "text/html")
        val item = MediaItem("i", MediaType.IMAGE, url = "https://i.redd.it/gone.jpg", ext = "jpg")
        try {
            MediaDownloader(fake.http, null, tools).download(post(item), item, Quality.BEST, work, "p6")
            throw AssertionError("expected failure")
        } catch (e: SaveItException) {
            assertTrue(e.detail!!.contains("web page"))
        }
    }

    @Test fun audioOnlyFromMuxedVideo() = runBlocking {
        assumeTrue(hasFfmpeg)
        fake.onBytes("https://video.twimg.com/m.mp4", muxed, "video/mp4")
        val item = MediaItem("m", MediaType.VIDEO, ext = "mp4", variants = listOf(VideoVariant("https://video.twimg.com/m.mp4", 240)))
        val out = MediaDownloader(fake.http, null, tools).download(post(item), item, Quality.AUDIO_M4A, work, "p7")
        assertEquals("m4a", out.ext)
        assertTrue(out.probe!!.hasAudio)
        assertTrue(!out.probe!!.hasVideo)
    }

    @Test fun engineFailureFallsBackToDirect() = runBlocking {
        assumeTrue(hasFfmpeg)
        fake.onBytes("https://v16.tiktokcdn.com/h264_720.mp4", muxed, "video/mp4")
        val failingEngine = object : YtDlpEngine {
            override suspend fun run(args: List<String>, processId: String?, progress: EngineProgress?): String =
                throw app.saveit.core.engine.EngineException("ERROR: [TikTok] 1: Unable to extract webpage video data")
            override suspend fun version() = "test"
        }
        val item = MediaItem("tt", MediaType.VIDEO, ext = "mp4", preferEngine = true, engineUrl = "https://www.tiktok.com/@a/video/1",
            variants = listOf(VideoVariant("https://v16.tiktokcdn.com/h264_720.mp4", 720)))
        val out = MediaDownloader(fake.http, failingEngine, tools).download(post(item), item, Quality.BEST, work, "p8")
        assertEquals("direct", out.via)
        assertTrue(out.probe!!.hasAudio)
    }

    @Test fun engineRouteUsesPrintedPath() = runBlocking {
        assumeTrue(hasFfmpeg)
        val engine = object : YtDlpEngine {
            override suspend fun run(args: List<String>, processId: String?, progress: EngineProgress?): String {
                val template = args[args.indexOf("-o") + 1]
                val f = File(template.replace("%(id).80B.%(ext)s", "vid.mp4"))
                f.writeBytes(muxed)
                progress?.invoke(50f, 1, "[download]  50.0% of 1.00MiB at  2.00MiB/s ETA 00:01")
                return "some log\n${f.absolutePath}\n"
            }
            override suspend fun version() = "test"
        }
        val item = MediaItem("e", MediaType.VIDEO, ext = "mp4", preferEngine = true, engineUrl = "https://x", hasAudio = true)
        var lastSpeed = 0L
        val out = MediaDownloader(fake.http, engine, tools).download(post(item), item, Quality.P720, work, "p9") { lastSpeed = it.speedBytesPerSec }
        assertEquals("yt-dlp", out.via)
        assertEquals(2L * 1024 * 1024, lastSpeed)
    }

    @Test fun variantOrderingHonoursCap() {
        val d = MediaDownloader(fake.http, null, tools)
        val vs = listOf(VideoVariant("1080", 1080), VideoVariant("720", 720), VideoVariant("480", 480), VideoVariant("unk", null), VideoVariant("1440", 1440))
        assertEquals(listOf("1440", "1080", "720", "480", "unk"), d.orderVariants(vs, Quality.BEST).map { it.url })
        assertEquals(listOf("720", "480", "unk", "1080", "1440"), d.orderVariants(vs, Quality.P720).map { it.url })
        assertEquals(listOf("480", "unk", "720", "1080", "1440"), d.orderVariants(vs, Quality.P480).map { it.url })
    }
}
