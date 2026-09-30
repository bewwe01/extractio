package app.saveit.core.engine

import app.saveit.core.fixture
import app.saveit.core.model.MediaType
import app.saveit.core.model.Platform
import app.saveit.core.model.Quality
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class YtDlpJsonParserTest {
    @Test fun instagramCarouselMixesImagesAndVideos() {
        val p = YtDlpJsonParser.parse(fixture("ytdlp_ig_carousel.json"), Platform.INSTAGRAM, "https://www.instagram.com/p/C8abcDEF123/")
        assertEquals(listOf(MediaType.IMAGE, MediaType.VIDEO), p.items.map { it.type })
        assertEquals("https://scontent.cdninstagram.com/c0_1440.jpg", p.items[0].url)
        val v = p.items[1]
        assertEquals(2, v.enginePlaylistIndex)
        assertEquals(true, v.hasAudio)
        assertEquals(listOf(1920, 1280), v.engineHeights)
        assertEquals("Wildlife Photos", p.author)
    }

    @Test fun tiktokSlideshowIsAudioOnly() {
        val p = YtDlpJsonParser.parse(fixture("ytdlp_tiktok_slideshow.json"), Platform.TIKTOK, "u")
        assertEquals(MediaType.AUDIO, p.items.single().type)
    }

    @Test fun redditSplitStreamsKnowAudioExists() {
        val v = YtDlpJsonParser.parse(fixture("ytdlp_reddit_video.json"), Platform.REDDIT, "u").items.single()
        assertEquals(MediaType.VIDEO, v.type)
        assertEquals(true, v.hasAudio)
        assertNull(v.enginePlaylistIndex)
        assertEquals(listOf(1080, 720), v.availableHeights)
    }

    @Test fun pinterestImageFromThumbnails() {
        val i = YtDlpJsonParser.parse(fixture("ytdlp_pinterest_image.json"), Platform.PINTEREST, "u").items.single()
        assertEquals(MediaType.IMAGE, i.type)
        assertEquals("https://i.pinimg.com/originals/a.jpg", i.url)
    }

    @Test fun formatArgsNeverDropAudio() {
        val best = YtDlpArgs.format(Quality.BEST)
        assertEquals("bv*+ba/b/bv*/b*", best[best.indexOf("-f") + 1])
        assertTrue(best.contains("--merge-output-format"))
        val p720 = YtDlpArgs.format(Quality.P720)
        assertTrue(p720[p720.indexOf("-S") + 1].startsWith("res:720,"))
        val mp3 = YtDlpArgs.format(Quality.AUDIO_MP3)
        assertEquals("mp3", mp3[mp3.indexOf("--audio-format") + 1])
    }

    @Test fun downloadArgsSelectPlaylistItem() {
        val args = YtDlpArgs.download("https://x.com/a/status/1", 2, Quality.BEST, "/tmp/%(id)s.%(ext)s", null)
        assertEquals("2", args[args.indexOf("--playlist-items") + 1])
        assertEquals("https://x.com/a/status/1", args.last())
        assertTrue("--no-playlist" !in YtDlpArgs.download("u", 2, Quality.BEST, "o", null))
        assertTrue("--no-playlist" in YtDlpArgs.download("u", null, Quality.BEST, "o", null))
    }
}
