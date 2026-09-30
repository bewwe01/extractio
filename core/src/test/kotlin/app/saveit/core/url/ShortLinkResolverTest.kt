package app.saveit.core.url

import app.saveit.core.FakeHttp
import app.saveit.core.error.ErrorKind
import app.saveit.core.error.SaveItException
import app.saveit.core.model.Platform
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class ShortLinkResolverTest {
    private val fake = FakeHttp()
    private val analyzer = LinkAnalyzer(ShortLinkResolver(fake.http))

    @Test fun tiktokVmRedirectChain() = runTest {
        fake.redirect("https://vm.tiktok.com/ZMabc123/", "https://www.tiktok.com/@creator/video/7312345678901234567?_t=8abc&_r=1")
        val link = analyzer.prepare("Check this TikTok https://vm.tiktok.com/ZMabc123/")
        assertEquals(Platform.TIKTOK, link.platform)
        assertEquals("https://www.tiktok.com/@creator/video/7312345678901234567", link.url)
    }

    @Test fun pinItThroughApiShortener() = runTest {
        fake.redirect("https://pin.it/1a2B3c4D", "https://api.pinterest.com/url_shortener/1a2B3c4D/redirect/", 302)
        fake.redirect("https://api.pinterest.com/url_shortener/1a2B3c4D/redirect/",
            "https://www.pinterest.com/pin/123456789012345678/sent/?invite_code=abc&sfo=1", 302)
        assertEquals("https://www.pinterest.com/pin/123456789012345678/", analyzer.prepare("pin.it/1a2B3c4D").url)
    }

    @Test fun tcoMetaRefresh() = runTest {
        fake.on("https://t.co/AbC123", body = """<html><head><meta name="referrer" content="always"><noscript><META http-equiv="refresh" content="0;URL=https://twitter.com/user/status/1834812402348290048/video/1"></noscript></head></html>""")
        assertEquals("https://x.com/user/status/1834812402348290048", analyzer.prepare("https://t.co/AbC123").url)
    }

    @Test fun redditShareLinkRelativeLocation() = runTest {
        fake.redirect("https://www.reddit.com/r/videos/s/AbCdEf123", "/r/videos/comments/1xyz99/funny_cat/?share_id=q&utm_content=1")
        assertEquals("https://www.reddit.com/r/videos/comments/1xyz99/funny_cat/", analyzer.prepare("https://www.reddit.com/r/videos/s/AbCdEf123").url)
    }

    @Test fun vReddItToPost() = runTest {
        fake.redirect("https://v.redd.it/abcdef", "https://www.reddit.com/r/aww/comments/1q2w3e/cat/")
        assertEquals("https://www.reddit.com/r/aww/comments/1q2w3e/cat/", analyzer.prepare("https://v.redd.it/abcdef").url)
    }

    @Test fun facebookShareAndFbWatch() = runTest {
        fake.redirect("https://www.facebook.com/share/v/1AbCdEfG/", "https://www.facebook.com/reel/555666777?mibextid=x", 302)
        assertEquals("https://www.facebook.com/reel/555666777", analyzer.prepare("https://www.facebook.com/share/v/1AbCdEfG/?mibextid=xyz").url)
        fake.redirect("https://fb.watch/qRsTuV-wx/", "https://www.facebook.com/watch/?v=4242&extid=abc", 302)
        assertEquals("https://www.facebook.com/watch/?v=4242", analyzer.prepare("fb.watch/qRsTuV-wx/").url)
    }

    @Test fun instagramShareCanonicalInHtml() = runTest {
        fake.on("https://www.instagram.com/share/reel/BAbcdEfGh", body = """<html><head><link rel="canonical" href="https://www.instagram.com/reel/C9zyXWvu123/"></head></html>""")
        assertEquals("https://www.instagram.com/reel/C9zyXWvu123/", analyzer.prepare("https://www.instagram.com/share/reel/BAbcdEfGh?igsh=1").url)
    }

    @Test fun snapchatT() = runTest {
        fake.redirect("https://t.snapchat.com/AbCdEf", "https://www.snapchat.com/spotlight/W7_EDlX?share_id=1", 302)
        assertEquals("https://www.snapchat.com/spotlight/W7_EDlX", analyzer.prepare("https://t.snapchat.com/AbCdEf").url)
    }

    @Test fun deadShortLink() = runTest {
        fake.on("https://vm.tiktok.com/ZMdead/", code = 404)
        try {
            analyzer.prepare("https://vm.tiktok.com/ZMdead/")
            fail("expected error")
        } catch (e: SaveItException) {
            assertEquals(ErrorKind.DELETED, e.kind)
        }
    }

    @Test fun expiredShortLinkToHomePageIsReportedAsDeleted() = runTest {
        fake.redirect("https://vm.tiktok.com/ZTR45GpSF/", "https://www.tiktok.com/")
        try {
            analyzer.prepare("https://vm.tiktok.com/ZTR45GpSF/")
            fail("expected error")
        } catch (e: SaveItException) {
            assertEquals(ErrorKind.DELETED, e.kind)
            assertEquals(Platform.TIKTOK, e.platform)
        }
    }

    @Test fun unsupportedText() = runTest {
        try {
            analyzer.prepare("hello https://example.com")
            fail("expected error")
        } catch (e: SaveItException) {
            assertEquals(ErrorKind.UNSUPPORTED_URL, e.kind)
        }
    }
}
