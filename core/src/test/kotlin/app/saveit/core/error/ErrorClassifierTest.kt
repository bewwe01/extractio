package app.saveit.core.error

import org.junit.Assert.assertEquals
import org.junit.Test

/** Messages are real yt-dlp error texts (as printed with the "ERROR: [extractor] id:" prefix). */
class ErrorClassifierTest {
    private fun k(msg: String) = ErrorClassifier.classify(msg)

    @Test fun realEngineMessages() {
        assertEquals(ErrorKind.LOGIN_REQUIRED, k("ERROR: [Instagram] C8abc: Requested content is not available, rate-limit reached or login required. Use --cookies, --cookies-from-browser, --username and --password, --netrc-cmd, or --netrc (instagram) to provide account credentials"))
        assertEquals(ErrorKind.NO_MEDIA, k("ERROR: [Instagram] C8abc: There is no video in this post"))
        assertEquals(ErrorKind.NO_MEDIA, k("ERROR: [twitter] 1834: No video could be found in this tweet"))
        assertEquals(ErrorKind.PRIVATE, k("ERROR: [twitter] 1834: You are not authorized to view this protected tweet. Use --cookies"))
        assertEquals(ErrorKind.AGE_RESTRICTED, k("ERROR: [twitter] 1834: NSFW tweet requires authentication. Use --cookies"))
        assertEquals(ErrorKind.DELETED, k("ERROR: [TikTok] 7312: Video not available, status code 10204"))
        assertEquals(ErrorKind.PRIVATE, k("ERROR: [TikTok] 7312: This video is private"))
        assertEquals(ErrorKind.RATE_LIMITED, k("ERROR: [Reddit] 1abc: Your IP address is unable to access the Reddit API"))
        assertEquals(ErrorKind.RATE_LIMITED, k("ERROR: [TikTok] 7312: Your IP address is blocked from accessing this post"))
        assertEquals(ErrorKind.LOGIN_REQUIRED, k("ERROR: [facebook] 123: This video is only available for registered users. Use --cookies"))
        assertEquals(ErrorKind.REGION_BLOCKED, k("ERROR: [facebook] 123: This video is not available from your location due to geo restriction"))
        assertEquals(ErrorKind.UNSUPPORTED_URL, k("ERROR: Unsupported URL: https://www.snapchat.com/add/someone"))
        assertEquals(ErrorKind.ENGINE_OUTDATED, k("ERROR: [TikTok] 7312: Unable to extract webpage video data; please report this issue on https://github.com/yt-dlp/yt-dlp/issues"))
        assertEquals(ErrorKind.DELETED, k("ERROR: [Pinterest] 1: Unable to download JSON metadata: HTTP Error 404: Not Found"))
        assertEquals(ErrorKind.NETWORK, k("ERROR: [Reddit] x: Unable to download webpage: <urlopen error [Errno -3] Temporary failure in name resolution>"))
        assertEquals(ErrorKind.RATE_LIMITED, k("ERROR: [Instagram] x: Unable to download JSON metadata: HTTP Error 429: Too Many Requests"))
        assertEquals(ErrorKind.NETWORK, k("ERROR: [TikTok] 674: Unable to download webpage: ('Unable to connect to proxy', OSError('Tunnel connection failed: 403 Forbidden'))"))
        assertEquals(ErrorKind.UNKNOWN, k("something odd"))
    }

    @Test fun condenseKeepsErrorLines() {
        val out = "[info] x\nWARNING: meh\nERROR: [Instagram] C8: There is no video in this post\n"
        assertEquals("[Instagram] C8: There is no video in this post", ErrorClassifier.condense(out))
    }

    @Test fun messagesAreSpecific() {
        val m = SaveItException(ErrorKind.LOGIN_REQUIRED, app.saveit.core.model.Platform.INSTAGRAM).userMessage
        assertEquals(true, m.contains("Instagram") && m.contains("Settings"))
        val snap = SaveItException(ErrorKind.LOGIN_REQUIRED, app.saveit.core.model.Platform.SNAPCHAT).userMessage
        assertEquals(true, snap.contains("Private snaps"))
    }
}
