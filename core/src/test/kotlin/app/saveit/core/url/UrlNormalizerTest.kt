package app.saveit.core.url

import app.saveit.core.model.Platform
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UrlNormalizerTest {
    private fun n(u: String) = UrlNormalizer.normalize(u)

    @Test fun reddit() {
        assertEquals("https://www.reddit.com/r/pics/comments/1abc2d/some_title/",
            n("https://old.reddit.com/r/pics/comments/1abc2d/some_title/?utm_source=share&utm_medium=android_app&utm_name=androidcss&utm_term=1&utm_content=share_button"))
        assertEquals("https://www.reddit.com/r/pics/comments/1abc2d/some_title/", n("https://np.reddit.com/r/pics/comments/1abc2d/some_title/kx9zz1/"))
        assertEquals("https://www.reddit.com/comments/1abc2d/", n("https://redd.it/1abc2d"))
        assertEquals("https://www.reddit.com/comments/1abc2d/", n("https://www.reddit.com/gallery/1abc2d"))
        assertEquals("https://www.reddit.com/user/bob/comments/1abc2d/t/", n("https://m.reddit.com/u/bob/comments/1abc2d/t"))
        assertEquals("https://www.reddit.com/r/videos/s/AbCdEf123", n("https://www.reddit.com/r/videos/s/AbCdEf123?share_id=x&utm_medium=ios_app"))
        assertTrue(UrlNormalizer.needsResolution("https://www.reddit.com/r/videos/s/AbCdEf123"))
        assertEquals("https://i.redd.it/abc123.jpg", n("https://www.reddit.com/media?url=https%3A%2F%2Fi.redd.it%2Fabc123.jpg"))
        assertEquals("https://i.redd.it/xyz.png", n("https://preview.redd.it/xyz.png?width=640&crop=smart&auto=webp&s=abcdef"))
    }

    @Test fun x() {
        val c = "https://x.com/NASA/status/1834812402348290048"
        assertEquals(c, n("https://twitter.com/NASA/status/1834812402348290048?s=20&t=abc"))
        assertEquals(c, n("https://mobile.twitter.com/NASA/status/1834812402348290048"))
        assertEquals(c, n("https://fxtwitter.com/NASA/status/1834812402348290048"))
        assertEquals(c, n("https://vxtwitter.com/NASA/status/1834812402348290048/photo/1"))
        assertEquals(c, n("https://fixupx.com/NASA/status/1834812402348290048/video/1"))
        assertEquals(c, n("https://d.fxtwitter.com/NASA/status/1834812402348290048.mp4"))
        assertEquals("https://x.com/i/status/1834812402348290048", n("https://x.com/i/web/status/1834812402348290048"))
        assertEquals("https://x.com/i/status/1834812402348290048", n("https://twitter.com/statuses/1834812402348290048"))
        assertTrue(UrlNormalizer.needsResolution("https://t.co/AbC123"))
    }

    @Test fun instagram() {
        assertEquals("https://www.instagram.com/reel/C8abcDEF123/", n("https://www.instagram.com/reel/C8abcDEF123/?igsh=MWx5ZXJ6NHd3dnF2Nw=="))
        assertEquals("https://www.instagram.com/reel/C8abcDEF123/", n("https://instagram.com/reels/C8abcDEF123"))
        assertEquals("https://www.instagram.com/p/C8abcDEF123/", n("https://www.instagram.com/someuser/p/C8abcDEF123/?img_index=2"))
        assertEquals("https://www.instagram.com/p/C8abcDEF123/", n("https://ddinstagram.com/p/C8abcDEF123/"))
        assertEquals("https://www.instagram.com/tv/C8abcDEF123/", n("https://m.instagram.com/tv/C8abcDEF123"))
        assertEquals("https://www.instagram.com/stories/natgeo/3456789012345678901/", n("https://www.instagram.com/stories/natgeo/3456789012345678901/?utm_source=ig_story_item_share"))
        // base64("highlight:17912345678901234")
        assertEquals("https://www.instagram.com/stories/highlights/17912345678901234/",
            n("https://www.instagram.com/s/aGlnaGxpZ2h0OjE3OTEyMzQ1Njc4OTAxMjM0?story_media_id=1_2&igsh=x"))
        assertTrue(UrlNormalizer.needsResolution(n("https://www.instagram.com/share/reel/BAbcdEfGh?igsh=xyz")))
    }

    @Test fun tiktok() {
        assertEquals("https://www.tiktok.com/@scout2015/video/6718335390845095173",
            n("https://www.tiktok.com/@scout2015/video/6718335390845095173?is_from_webapp=1&sender_device=pc&web_id=123"))
        assertEquals("https://www.tiktok.com/@user/photo/7300000000000000000", n("https://www.tiktok.com/@user/photo/7300000000000000000?_r=1"))
        assertEquals("https://www.tiktok.com/@/video/6718335390845095173", n("https://m.tiktok.com/v/6718335390845095173.html?u_code=x"))
        assertEquals("https://www.tiktok.com/@/video/6718335390845095173", n("https://www.tiktok.com/embed/v2/6718335390845095173"))
        assertEquals("https://vm.tiktok.com/ZMabc123/", n("https://vm.tiktok.com/ZMabc123"))
        assertTrue(UrlNormalizer.needsResolution("https://vm.tiktok.com/ZMabc123/"))
        assertTrue(UrlNormalizer.needsResolution("https://www.tiktok.com/t/ZT8abc/"))
        assertEquals("https://www.tiktok.com/@user/video/7300000000000000000",
            UrlNormalizer.engineUrl("https://www.tiktok.com/@user/photo/7300000000000000000"))
    }

    @Test fun snapchat() {
        assertEquals("https://www.snapchat.com/spotlight/W7_EDlXWTBiX", n("https://snapchat.com/spotlight/W7_EDlXWTBiX?share_id=abc&locale=en-US"))
        assertEquals("https://www.snapchat.com/add/someuser", n("https://www.snapchat.com/add/someuser/?share_id=1"))
        assertTrue(UrlNormalizer.needsResolution("https://t.snapchat.com/AbCdEf"))
        assertTrue(UrlNormalizer.needsResolution("https://www.snapchat.com/t/AbCdEf"))
    }

    @Test fun facebook() {
        assertEquals("https://www.facebook.com/watch/?v=1234567890", n("https://m.facebook.com/watch/?v=1234567890&mibextid=abc&rdid=xyz"))
        assertEquals("https://www.facebook.com/reel/1234567890", n("https://web.facebook.com/reel/1234567890?fs=e&s=cl&mibextid=wwXIfr"))
        assertEquals("https://www.facebook.com/permalink.php?story_fbid=111&id=222", n("https://www.facebook.com/permalink.php?story_fbid=111&id=222&__cft__[0]=AZX&__tn__=%2CO"))
        assertEquals("https://www.facebook.com/photo.php?fbid=333&set=a.444", n("https://mbasic.facebook.com/photo.php?fbid=333&set=a.444&type=3&mibextid=1"))
        assertEquals("https://www.facebook.com/watch/?v=987", n("https://www.facebook.com/login/?next=https%3A%2F%2Fwww.facebook.com%2Fwatch%2F%3Fv%3D987"))
        assertTrue(UrlNormalizer.needsResolution(n("https://www.facebook.com/share/v/1AbCdEfG/?mibextid=xyz")))
        assertTrue(UrlNormalizer.needsResolution(n("https://www.facebook.com/share/r/1AbCdEfG/")))
        assertTrue(UrlNormalizer.needsResolution("https://fb.watch/qRsTuV-wx/"))
    }

    @Test fun pinterest() {
        assertEquals("https://www.pinterest.com/pin/123456789012345678/", n("https://nl.pinterest.com/pin/123456789012345678/sent/?invite_code=abc&sender=1&sfo=1"))
        assertEquals("https://www.pinterest.com/pin/123456789012345678/", n("https://www.pinterest.co.uk/pin/123456789012345678/"))
        assertEquals("https://www.pinterest.com/pin/123456789012345678/", n("https://pinterest.com.au/pin/cute-cat-pictures--123456789012345678/"))
        assertTrue(UrlNormalizer.needsResolution("https://pin.it/1a2B3c4D"))
    }

    @Test fun directMedia() {
        assertEquals("https://pbs.twimg.com/media/GAbcDEF?format=jpg&name=orig", n("https://pbs.twimg.com/media/GAbcDEF.jpg"))
        assertEquals("https://pbs.twimg.com/media/GAbcDEF?format=png&name=orig", n("https://pbs.twimg.com/media/GAbcDEF?format=png&name=small"))
        assertEquals(Platform.DIRECT, PlatformDetector.detect("https://i.redd.it/abc.jpg"))
        assertEquals(Platform.DIRECT, PlatformDetector.detect("https://i.pinimg.com/originals/aa/bb/cc/x.jpg"))
    }

    @Test fun detection() {
        assertEquals(Platform.REDDIT, PlatformDetector.detect("https://v.redd.it/abc"))
        assertEquals(Platform.X, PlatformDetector.detect("https://t.co/abc"))
        assertEquals(Platform.PINTEREST, PlatformDetector.detect("https://www.pinterest.de/pin/1/"))
        assertEquals(Platform.PINTEREST, PlatformDetector.detect("https://pin.it/abc"))
        assertEquals(Platform.FACEBOOK, PlatformDetector.detect("https://fb.watch/abc/"))
        assertEquals(null, PlatformDetector.detect("https://notreddit.example.com/r/x"))
        assertEquals(null, PlatformDetector.detect("https://evilx.com/a/status/1"))
        assertFalse(UrlNormalizer.needsResolution("https://www.tiktok.com/@a/video/1"))
    }
}
