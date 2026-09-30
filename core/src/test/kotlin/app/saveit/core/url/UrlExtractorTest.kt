package app.saveit.core.url

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UrlExtractorTest {
    private fun first(text: String) = UrlExtractor.findFirstSupported(text)

    @Test fun tiktokShareSheetText() {
        val text = "Check out @someone's video! #TikTok > https://vm.tiktok.com/ZMabc123/ "
        assertEquals("https://vm.tiktok.com/ZMabc123/", first(text))
    }

    @Test fun instagramShareWithTrackingAndSentence() {
        val text = "Look at this: https://www.instagram.com/reel/C8abcDEF123/?igsh=MWx5ZXJ6NHd3dnF2Nw==."
        assertEquals("https://www.instagram.com/reel/C8abcDEF123/?igsh=MWx5ZXJ6NHd3dnF2Nw==", first(text))
    }

    @Test fun redditShareWithParensAndMarkdown() {
        assertEquals(
            "https://www.reddit.com/r/pics/comments/abc123/title/",
            first("[my post](https://www.reddit.com/r/pics/comments/abc123/title/)"),
        )
        assertEquals("https://redd.it/abc123", first("(see https://redd.it/abc123)"))
    }

    @Test fun wikipediaStyleParenthesesKept() {
        assertEquals(
            "https://www.facebook.com/watch/?v=123(1)",
            UrlExtractor.findUrls("x https://www.facebook.com/watch/?v=123(1) y").first(),
        )
    }

    @Test fun xShareGluedToText() {
        assertEquals("https://x.com/user/status/1834812402348290048?s=46&t=abcDEF", first("wow!https://x.com/user/status/1834812402348290048?s=46&t=abcDEF"))
    }

    @Test fun barePinterestAndFbWatchWithoutScheme() {
        assertEquals("https://pin.it/1a2B3c4D", first("Found on Pinterest pin.it/1a2B3c4D"))
        assertEquals("https://fb.watch/qRsTuV-wx/", first("watch fb.watch/qRsTuV-wx/ now"))
    }

    @Test fun snapchatSpotlightShare() {
        val text = "Watch this Spotlight on Snapchat! https://www.snapchat.com/spotlight/W7_EDlXWTBiXAEEniNoMPwAAYY2Nlb3RvcmdmAZE-u5ZwAZE-u5ZWAAAAAQ?share_id=abc&locale=en-US"
        assertEquals(
            "https://www.snapchat.com/spotlight/W7_EDlXWTBiXAEEniNoMPwAAYY2Nlb3RvcmdmAZE-u5ZwAZE-u5ZWAAAAAQ?share_id=abc&locale=en-US",
            first(text),
        )
    }

    @Test fun skipsUnsupportedFirstUrl() {
        assertEquals(
            "https://www.pinterest.com/pin/123456789012345678/",
            first("via https://example.com/blog and https://www.pinterest.com/pin/123456789012345678/"),
        )
    }

    @Test fun ellipsisTruncatedShare() {
        assertEquals("https://x.com/a/status/12345678901", first("Post by a https://x.com/a/status/12345678901…more"))
    }

    @Test fun noUrl() {
        assertNull(first("just some text"))
        assertNull(first("https://example.com/video.mp4"))
    }

    @Test fun chineseQuotesAndNbsp() {
        assertEquals("https://www.tiktok.com/t/ZT8abc/", first("「https://www.tiktok.com/t/ZT8abc/」"))
        assertEquals("https://vt.tiktok.com/ZSabc/", first("link: https://vt.tiktok.com/ZSabc/ shared"))
    }
}
