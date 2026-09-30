package app.saveit.core.extractor

import app.saveit.core.FakeHttp
import app.saveit.core.error.ErrorKind
import app.saveit.core.error.SaveItException
import app.saveit.core.fixture
import app.saveit.core.model.MediaType
import app.saveit.core.model.PostInfo
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ExtractorTest {
    private val fake = FakeHttp()
    private val ctx = ExtractionContext(fake.http)

    private suspend fun expectError(kind: ErrorKind, block: suspend () -> Unit) {
        try {
            block()
            fail("expected $kind")
        } catch (e: SaveItException) {
            assertEquals(e.detail, kind, e.kind)
        }
    }

    // ---------------- Reddit ----------------
    private val reddit = RedditExtractor(EmbedResolver(fake.http))
    private suspend fun redditPost(json: String, url: String = "https://www.reddit.com/r/x/comments/1abc/t/"): PostInfo {
        fake.on(url.trimEnd('/') + "/.json", body = fixture(json), contentType = "application/json")
        return reddit.extract(url, ctx)
    }

    @Test fun redditGalleryKeepsOrderAndSkipsFailed() = runTest {
        val p = redditPost("reddit_gallery.json")
        assertEquals(3, p.items.size)
        assertEquals(listOf("aaa111", "bbb222", "ccc333"), p.items.map { it.id })
        assertEquals("https://i.redd.it/aaa111.jpg", p.items[0].url)
        assertEquals("Summit", p.items[0].label)
        assertEquals("https://i.redd.it/bbb222.png", p.items[1].url)
        assertEquals("png", p.items[1].ext)
        assertEquals(MediaType.GIF, p.items[2].type)
        assertEquals("https://i.redd.it/ccc333.gif", p.items[2].url)
        assertEquals(4032, p.items[0].width)
        assertEquals("u/hiker42", p.author)
    }

    @Test fun redditVideoPairsBestAudioWithEveryResolution() = runTest {
        fake.on("https://v.redd.it/vv123abc/DASHPlaylist.mpd", body = fixture("reddit_dash.mpd"), contentType = "application/dash+xml")
        val p = redditPost("reddit_video.json")
        val v = p.items.single()
        assertEquals(MediaType.VIDEO, v.type)
        assertEquals(true, v.hasAudio)
        assertEquals(listOf(1920, 1280, 854), v.variants.map { it.height })
        assertEquals("https://v.redd.it/vv123abc/CMAF_1080.mp4", v.variants[0].url)
        assertEquals(listOf("https://v.redd.it/vv123abc/CMAF_AUDIO_128.mp4", "https://v.redd.it/vv123abc/CMAF_AUDIO_64.mp4"), v.variants[0].audioUrls)
        assertEquals("https://www.reddit.com/r/aww/comments/1vid01/cat_learns/", v.engineUrl)
        assertTrue(v.preferEngine)
    }

    @Test fun redditVideoWithoutManifestGuessesAudio() = runTest {
        fake.on("https://v.redd.it/vv123abc/DASHPlaylist.mpd", code = 403)
        val v = redditPost("reddit_video.json").items.single()
        assertEquals("https://v.redd.it/vv123abc/CMAF_1080.mp4?source=fallback", v.variants.single().url)
        assertTrue(v.variants.single().audioUrls.any { it.endsWith("DASH_AUDIO_128.mp4") })
    }

    @Test fun redditImageAndGif() = runTest {
        val img = redditPost("reddit_image.json").items.single()
        assertEquals("https://i.redd.it/sunset99.jpeg", img.url)
        assertEquals(MediaType.IMAGE, img.type)
        assertEquals("jpg", img.ext)
        val gif = redditPost("reddit_gif.json", "https://www.reddit.com/r/gifs/comments/1gif01/funny/").items.single()
        assertEquals(MediaType.GIF, gif.type)
        assertEquals("gif", gif.ext)
    }

    @Test fun redditCrosspostUsesParentMedia() = runTest {
        fake.on("https://v.redd.it/vv123abc/DASHPlaylist.mpd", body = fixture("reddit_dash.mpd"))
        val p = redditPost("reddit_crosspost.json")
        assertEquals(MediaType.VIDEO, p.items.single().type)
        assertEquals("u/reposter", p.author)
    }

    @Test fun redditRedgifsEmbed() = runTest {
        fake.on("https://api.redgifs.com/v2/auth/temporary", body = fixture("redgifs_auth.json"), contentType = "application/json")
        fake.on("https://api.redgifs.com/v2/gifs/happyblueduck", body = fixture("redgifs_gif.json"), contentType = "application/json")
        val v = redditPost("reddit_redgifs.json").items.single()
        assertEquals("https://media.redgifs.com/HappyBlueDuck.mp4", v.variants.single().url)
        assertEquals(true, v.hasAudio)
        assertEquals("https://www.redgifs.com/watch/happyblueduck", v.engineUrl)
    }

    @Test fun redditErrors() = runTest {
        expectError(ErrorKind.NO_MEDIA) { redditPost("reddit_text.json") }
        expectError(ErrorKind.PRIVATE) { redditPost("reddit_private.json", "https://www.reddit.com/r/secret/comments/1p/t/") }
        fake.on("https://www.reddit.com/r/x/comments/404/t/.json", code = 404, body = "{}")
        expectError(ErrorKind.DELETED) { reddit.extract("https://www.reddit.com/r/x/comments/404/t/", ctx) }
    }

    // ---------------- X ----------------
    private val x = TwitterExtractor()

    @Test fun xPhotosAtOrigAndHighestBitrateVideo() {
        val p = x.parse(app.saveit.core.util.parseJsonOrNull(fixture("x_multi.json"))!!, "https://x.com/SpaceFans/status/1834812402348290048", "1834812402348290048")
        assertEquals(3, p.items.size)
        assertEquals("https://pbs.twimg.com/media/GXaAAAAWAAA1bcD?format=jpg&name=orig", p.items[0].url)
        assertEquals("https://pbs.twimg.com/media/GXaBBBBWBBB2cdE?format=png&name=orig", p.items[1].url)
        assertEquals("png", p.items[1].ext)
        val video = p.items[2]
        assertEquals(MediaType.VIDEO, video.type)
        assertTrue(video.variants.first().url.contains("1920x1080"))
        assertEquals(3, video.variants.size) // m3u8 dropped
        assertEquals(1080, video.variants.first().height)
        assertNull(video.enginePlaylistIndex) // only one video in the post
        assertEquals("Two photos and a clip", p.title)
        assertEquals("@SpaceFans", p.author)
    }

    @Test fun xGifIsSilentMp4() {
        val p = x.parse(app.saveit.core.util.parseJsonOrNull(fixture("x_gif.json"))!!, "https://x.com/giffer/status/1600009362759733248", "1600009362759733248")
        val g = p.items.single()
        assertEquals(MediaType.GIF, g.type)
        assertEquals("mp4", g.ext)
        assertEquals(false, g.hasAudio)
    }

    @Test fun xQuotedMediaAndTombstone() = runTest {
        val p = x.parse(app.saveit.core.util.parseJsonOrNull(fixture("x_quote_only.json"))!!, "u", "1629307668568633344")
        assertEquals(1, p.items.size)
        expectError(ErrorKind.DELETED) { x.parse(app.saveit.core.util.parseJsonOrNull(fixture("x_tombstone.json"))!!, "u", "1") }
        fake.on("https://cdn.syndication.twimg.com/tweet-result", code = 404)
        expectError(ErrorKind.DELETED) { x.extract("https://x.com/a/status/1234567890", ctx) }
    }

    @Test fun xRequestCarriesToken() = runTest {
        fake.on("https://cdn.syndication.twimg.com/tweet-result", body = fixture("x_multi.json"), contentType = "application/json")
        x.extract("https://x.com/SpaceFans/status/1834812402348290048", ctx)
        assertTrue(fake.requests.last().contains("token=4g48e6hvede"))
    }

    // ---------------- Instagram ----------------
    private val ig = InstagramExtractor()

    @Test fun instagramGraphqlCarouselAllItemsFullRes() = runTest {
        fake.on("https://www.instagram.com/api/v1/web/get_ruling_for_content", body = "{}")
        fake.on("https://www.instagram.com/graphql/query/", body = fixture("ig_graphql_sidecar.json"), contentType = "application/json")
        val p = ig.extract("https://www.instagram.com/p/C8abcDEF123/", ctx)
        assertEquals(listOf(MediaType.IMAGE, MediaType.VIDEO, MediaType.IMAGE), p.items.map { it.type })
        assertTrue(p.items[0].url!!.contains("img0_1080"))
        assertEquals(1080, p.items[0].width)
        assertEquals(2, p.items[1].enginePlaylistIndex)
        assertEquals(true, p.items[1].hasAudio)
        assertEquals("@wildlife_photos", p.author)
    }

    @Test fun instagramReel() = runTest {
        fake.on("https://www.instagram.com/graphql/query/", body = fixture("ig_graphql_reel.json"))
        val v = ig.extract("https://www.instagram.com/reel/C9reel555/", ctx).items.single()
        assertEquals(MediaType.VIDEO, v.type)
        assertTrue(v.variants.single().url.contains("vid5.mp4"))
        assertNull(v.enginePlaylistIndex)
    }

    @Test fun instagramFallsBackToEmbed() = runTest {
        fake.on("https://www.instagram.com/graphql/query/", body = fixture("ig_graphql_empty.json"))
        fake.on("https://www.instagram.com/p/C8abcDEF123/embed/captioned/", body = fixture("ig_embed.html"))
        val p = ig.extract("https://www.instagram.com/p/C8abcDEF123/", ctx)
        assertEquals(3, p.items.size)
    }

    @Test fun instagramEmbedSingleImageHtml() = runTest {
        fake.on("https://www.instagram.com/graphql/query/", code = 401, body = "{}")
        fake.on("https://www.instagram.com/p/Cimg/embed/captioned/", body = fixture("ig_embed_image.html"))
        val p = ig.extract("https://www.instagram.com/p/Cimg/", ctx)
        assertEquals("https://scontent.cdninstagram.com/single_1080.jpg", p.items.single().url)
    }

    @Test fun instagramProductCarouselPicksLargestCandidates() {
        val p = ig.parseProduct(app.saveit.core.util.parseJsonOrNull(fixture("ig_product_carousel.json"))!!.let { root ->
            (root as kotlinx.serialization.json.JsonObject)["items"]!!.let { (it as kotlinx.serialization.json.JsonArray)[0] }
        }, "https://www.instagram.com/p/C8abcDEF123/", "C8abcDEF123")
        assertEquals(2, p.items.size)
        assertEquals("https://scontent.cdninstagram.com/p1_1440.jpg", p.items[0].url)
        assertEquals("https://scontent.cdninstagram.com/p2_720.mp4", p.items[1].variants.first().url)
        assertEquals(2, p.items[1].enginePlaylistIndex)
    }

    @Test fun instagramNothingWithoutLogin() = runTest {
        fake.on("https://www.instagram.com/graphql/query/", body = fixture("ig_graphql_empty.json"))
        fake.on("https://www.instagram.com/p/Cpriv/embed/captioned/", body = "<html><body>nothing</body></html>")
        expectError(ErrorKind.LOGIN_REQUIRED) { ig.extract("https://www.instagram.com/p/Cpriv/", ctx) }
        expectError(ErrorKind.LOGIN_REQUIRED) { ig.extract("https://www.instagram.com/stories/someone/123/", ctx) }
    }

    @Test fun shortcodeToPk() {
        // Known pair from Instagram's public API documentation examples.
        assertEquals("1", InstagramExtractor.shortcodeToPk("B"))
        assertEquals("64", InstagramExtractor.shortcodeToPk("BA"))
        assertEquals(InstagramExtractor.shortcodeToPk("C8abcDEF123"), InstagramExtractor.shortcodeToPk("C8abcDEF123" + "x".repeat(28)))
    }

    // ---------------- TikTok ----------------
    private val tt = TikTokExtractor()

    @Test fun tiktokVideoPrefersH264NoWatermark() = runTest {
        fake.on("https://www.tiktok.com/@creator/video/7312345678901234567", body = fixture("tiktok_video.html"))
        val p = tt.extract("https://www.tiktok.com/@creator/video/7312345678901234567", ctx)
        val v = p.items.single()
        assertEquals("https://v16.tiktokcdn.com/h264_720.mp4", v.variants.first().url)
        assertFalse(v.variants.any { it.url.contains("h265") })
        assertTrue(v.variants.last().url.contains("download_wm"))
        assertEquals("https://www.tiktok.com/@creator/video/7312345678901234567", v.engineUrl)
        assertEquals("@creator", p.author)
    }

    @Test fun tiktokSlideshowAllImagesPlusOptionalAudio() = runTest {
        fake.on("https://www.tiktok.com/@traveler/photo/7300000000000000000", body = fixture("tiktok_photo.html"))
        val p = tt.extract("https://www.tiktok.com/@traveler/photo/7300000000000000000", ctx)
        assertEquals(listOf(MediaType.IMAGE, MediaType.IMAGE, MediaType.IMAGE, MediaType.AUDIO), p.items.map { it.type })
        assertTrue(p.items[0].url!!.endsWith(".jpeg"))
        assertEquals("jpg", p.items[0].ext)
        assertFalse(p.items[3].selectedByDefault)
        assertEquals("mp3", p.items[3].ext)
    }

    @Test fun tiktokStatusCodes() = runTest {
        fake.on("https://www.tiktok.com/@a/video/1", body = fixture("tiktok_private.html"))
        expectError(ErrorKind.PRIVATE) { tt.extract("https://www.tiktok.com/@a/video/1", ctx) }
        fake.on("https://www.tiktok.com/@a/video/2", body = fixture("tiktok_deleted.html"))
        expectError(ErrorKind.DELETED) { tt.extract("https://www.tiktok.com/@a/video/2", ctx) }
    }

    // ---------------- Snapchat ----------------
    private val snap = SnapchatExtractor()

    @Test fun snapchatSpotlightPicksMatchingStory() {
        val p = snap.parsePage(fixture("snap_spotlight.html"), "https://www.snapchat.com/spotlight/W7_EDlXWTBiX")
        val v = p.items.single()
        assertEquals("https://cf-st.sc-cdn.net/d/right.mp4?mo=x", v.variants.single().url)
        assertEquals(10.91, v.durationSec!!, 0.001)
        assertEquals("@spotlight_creator", p.author)
    }

    @Test fun snapchatPublicStoryAllSnaps() {
        val p = snap.parsePage(fixture("snap_story.html"), "https://www.snapchat.com/add/sample_artist")
        assertEquals(listOf(MediaType.IMAGE, MediaType.VIDEO), p.items.map { it.type })
        assertEquals("@sample_artist", p.author)
    }

    @Test fun snapchatProfileWithoutStoryExplains() = runTest {
        expectError(ErrorKind.UNSUPPORTED_CONTENT) { snap.parsePage(fixture("snap_profile_nostory.html"), "https://www.snapchat.com/add/quiet") }
    }

    // ---------------- Pinterest ----------------
    private val pin = PinterestExtractor()
    private fun pinOf(name: String, id: String) =
        pin.parsePin(app.saveit.core.util.parseJsonOrNull(fixture(name))!!.let { (it as kotlinx.serialization.json.JsonObject)["resource_response"]!!.let { r -> (r as kotlinx.serialization.json.JsonObject)["data"]!! } }, id)

    @Test fun pinterestImageOriginal() {
        val i = pinOf("pin_image.json", "123456789012345678").items.single()
        assertEquals("https://i.pinimg.com/originals/ab/cd/ef/abcdef0123.png", i.url)
        assertEquals(1200, i.width)
        assertEquals("png", i.ext)
    }

    @Test fun pinterestVideoPrefersMp4() {
        val v = pinOf("pin_video.json", "222").items.single()
        assertEquals("https://v1.pinimg.com/videos/mc/720p/aa/bb/cc/x.mp4", v.variants.first().url)
        assertTrue(v.variants.last().isManifest)
        assertEquals(30.0, v.durationSec!!, 0.01)
    }

    @Test fun pinterestIdeaPinEveryPage() {
        val p = pinOf("pin_story.json", "333")
        assertEquals(listOf(MediaType.IMAGE, MediaType.VIDEO, MediaType.IMAGE), p.items.map { it.type })
        assertEquals("https://v1.pinimg.com/videos/iht/expMp4/p2_t7.mp4", p.items[1].variants.first().url)
    }

    @Test fun pinterestCarouselEverySlotUpgradedToOriginals() {
        val p = pinOf("pin_carousel.json", "444")
        assertEquals(2, p.items.size)
        assertEquals("https://i.pinimg.com/originals/aa/bb/cc/c2.jpg", p.items[1].url)
        assertTrue(p.items[1].altUrls.contains("https://i.pinimg.com/736x/aa/bb/cc/c2.jpg"))
        assertTrue(p.items[1].altUrls.contains("https://i.pinimg.com/originals/aa/bb/cc/c2.png"))
    }

    @Test fun pinterestGif() {
        val g = pinOf("pin_gif.json", "555").items.single()
        assertEquals(MediaType.GIF, g.type)
        assertEquals("gif", g.ext)
    }

    @Test fun pinterestOriginalRewrite() {
        assertEquals("https://i.pinimg.com/originals/ab/cd/ef/x.jpg", PinterestExtractor.toOriginal("https://i.pinimg.com/736x/ab/cd/ef/x.jpg"))
        assertEquals("https://i.pinimg.com/originals/ab/cd/ef/x.jpg", PinterestExtractor.toOriginal("https://i.pinimg.com/236x/ab/cd/ef/x.jpg"))
    }

    // ---------------- Facebook ----------------
    private val fb = FacebookExtractor()

    @Test fun facebookDashPlusProgressive() {
        val p = fb.parsePage(fixture("fb_video.html"), "https://www.facebook.com/reel/555666777", "https://www.facebook.com/reel/555666777")
        val v = p.items.single()
        assertEquals(1920, v.variants.first().height)
        assertEquals(listOf("https://scontent.xx.fbcdn.net/v/audio.mp4?efg=1"), v.variants.first().audioUrls)
        assertTrue(v.variants.any { it.url == "https://video.xx.fbcdn.net/v/hd.mp4?_nc_cat=1&efg=2" })
        assertEquals(true, v.hasAudio)
        assertEquals("Amazing reel", p.title)
    }

    @Test fun facebookPhotoLargestUnique() {
        val p = fb.parsePage(fixture("fb_photo.html"), "https://www.facebook.com/photo/?fbid=333", "https://www.facebook.com/photo/?fbid=333")
        assertEquals(2, p.items.size)
        assertEquals("https://scontent.xx.fbcdn.net/v/t39/photo_big_n.jpg?stp=dst", p.items[0].url)
    }

    @Test fun facebookLoginWall() = runTest {
        expectError(ErrorKind.LOGIN_REQUIRED) { fb.parsePage(fixture("fb_login.html"), "https://www.facebook.com/x/posts/1", "u") }
    }

    // ---------------- DASH ----------------
    @Test fun dashManifestResolvesRelativeUrls() {
        val reps = DashManifest.parse(fixture("reddit_dash.mpd"), "https://v.redd.it/vv123abc/DASHPlaylist.mpd?a=1")
        assertEquals(5, reps.size)
        assertEquals(2, reps.count { it.isAudio })
        assertEquals("https://v.redd.it/vv123abc/CMAF_480.mp4", reps.first().url)
    }
}
