package app.saveit.core

import app.saveit.core.engine.EngineException
import app.saveit.core.engine.EngineProgress
import app.saveit.core.engine.YtDlpEngine
import app.saveit.core.error.ErrorKind
import app.saveit.core.error.SaveItException
import app.saveit.core.model.MediaType
import app.saveit.core.model.Platform
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class MediaResolverTest {
    private val fake = FakeHttp()

    private class FakeEngine(val reply: (List<String>) -> String) : YtDlpEngine {
        val calls = mutableListOf<List<String>>()
        override suspend fun run(args: List<String>, processId: String?, progress: EngineProgress?): String {
            calls += args
            return reply(args)
        }
        override suspend fun version() = "2025.11.12"
    }

    @Test fun tiktokSlideshowMergesFallbackImagesWithAudio() = runTest {
        val engine = FakeEngine { fixture("ytdlp_tiktok_slideshow.json") }
        fake.on("https://www.tiktok.com/@traveler/photo/7300000000000000000", body = fixture("tiktok_photo.html"))
        val post = MediaResolver(fake.http, engine).resolve("https://www.tiktok.com/@traveler/photo/7300000000000000000?is_from_webapp=1")
        assertEquals(3, post.items.count { it.type == MediaType.IMAGE })
        assertEquals(1, post.items.count { it.type == MediaType.AUDIO })
        assertFalse(post.items.last().selectedByDefault)
        // yt-dlp got the /video/ form it understands.
        assertTrue(engine.calls.single().last().endsWith("/video/7300000000000000000"))
    }

    @Test fun fallbackFirstPlatformsUseEngineWhenFallbackFails() = runTest {
        fake.on("https://cdn.syndication.twimg.com/tweet-result", code = 500)
        val engine = FakeEngine { fixture("ytdlp_reddit_video.json") }
        val post = MediaResolver(fake.http, engine).resolve("https://twitter.com/a/status/1834812402348290048?s=20")
        assertEquals("yt-dlp", post.source)
        assertEquals(Platform.X, post.platform)
    }

    @Test fun fallbackFirstSkipsEngineOnSuccess() = runTest {
        fake.on("https://cdn.syndication.twimg.com/tweet-result", body = fixture("x_multi.json"), contentType = "application/json")
        val engine = FakeEngine { error("engine should not run") }
        val post = MediaResolver(fake.http, engine).resolve("https://x.com/SpaceFans/status/1834812402348290048")
        assertEquals(3, post.items.size)
        assertTrue(engine.calls.isEmpty())
    }

    @Test fun mostSpecificErrorWins() = runTest {
        fake.on("https://www.facebook.com/reel/1", code = 503)
        val engine = FakeEngine { throw EngineException("ERROR: [facebook] 1: This video is only available for registered users. Use --cookies") }
        try {
            MediaResolver(fake.http, engine).resolve("https://www.facebook.com/reel/1")
            fail()
        } catch (e: SaveItException) {
            assertEquals(ErrorKind.LOGIN_REQUIRED, e.kind)
            assertEquals(Platform.FACEBOOK, e.platform)
        }
    }

    @Test fun engineOutdatedSurfacesWhenNothingBetter() = runTest {
        fake.on("https://www.facebook.com/reel/2", body = "<html><body>nothing here</body></html>")
        val engine = FakeEngine { throw EngineException("ERROR: [facebook] 2: Unable to extract video data; please report this issue") }
        try {
            MediaResolver(fake.http, engine).resolve("https://www.facebook.com/reel/2")
            fail()
        } catch (e: SaveItException) {
            assertEquals(ErrorKind.ENGINE_OUTDATED, e.kind)
        }
    }

    @Test fun directImageLink() = runTest {
        val post = MediaResolver(fake.http, null).resolve("https://i.redd.it/abc123.png")
        assertEquals(Platform.DIRECT, post.platform)
        assertEquals(MediaType.IMAGE, post.items.single().type)
        assertEquals("png", post.items.single().ext)
    }

    @Test fun worksWithoutEngine() = runTest {
        fake.on("https://www.pinterest.com/resource/PinResource/get/", body = fixture("pin_story.json"), contentType = "application/json")
        val post = MediaResolver(fake.http, null).resolve("https://pin.it/x".let { "https://www.pinterest.com/pin/333/" })
        assertEquals(3, post.items.size)
    }
}
