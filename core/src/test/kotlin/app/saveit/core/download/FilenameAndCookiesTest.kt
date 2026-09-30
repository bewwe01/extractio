package app.saveit.core.download

import app.saveit.core.model.MediaItem
import app.saveit.core.model.MediaType
import app.saveit.core.model.Platform
import app.saveit.core.model.PostInfo
import app.saveit.core.net.NetscapeCookies
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneOffset

class FilenameAndCookiesTest {
    private val item = MediaItem("m1", MediaType.IMAGE, url = "u", ext = "jpg")
    private fun post(n: Int, title: String? = "A/B: \"quoted\" <title>?", author: String? = "@user.name") =
        PostInfo(Platform.INSTAGRAM, "u", "C8abc", title, author, null, List(n) { item }, "t")

    @Test fun defaultTemplate() {
        assertEquals("instagram_user.name_C8abc_2", FilenameTemplate.render(FilenameTemplate.DEFAULT, post(3), item, 1))
        assertEquals("instagram_user.name_C8abc", FilenameTemplate.render(FilenameTemplate.DEFAULT, post(1), item, 0))
    }

    @Test fun unsafeCharactersAndDates() {
        val name = FilenameTemplate.render("{title} {date}", post(1), item, 0, now = Instant.parse("2026-09-30T10:00:00Z"), zone = ZoneOffset.UTC)
        assertFalse(name.any { it in "/\\:*?\"<>|" })
        assertTrue(name.endsWith("20260930"))
    }

    @Test fun emptyResultFallsBack() {
        assertEquals("instagram_C8abc_m1", FilenameTemplate.render("{author}", post(1, author = null), item, 0))
        assertTrue(FilenameTemplate.render("{title}", post(1, title = "x".repeat(500)), item, 0).length <= 100)
    }

    @Test fun netscapeFormat() {
        val text = NetscapeCookies.fromHeader("instagram.com", "sessionid=abc%3A123; csrftoken=xyz; ds_user_id=42", 1900000000)
        val lines = text.lines().filter { it.isNotBlank() && !it.startsWith("#") }
        assertEquals(3, lines.size)
        assertEquals(".instagram.com\tTRUE\t/\tTRUE\t1900000000\tsessionid\tabc%3A123", lines[0])
        assertTrue(text.startsWith("# Netscape HTTP Cookie File"))
    }
}
