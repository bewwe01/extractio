package app.saveit.core.download

import app.saveit.core.model.MediaItem
import app.saveit.core.model.PostInfo
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * User-configurable file names. Tokens: {platform} {author} {title} {id} {index} {date} {time} {quality}.
 * Output is always a safe single path segment (no separators, no reserved characters, bounded length).
 */
object FilenameTemplate {
    const val DEFAULT = "{platform}_{author}_{id}_{index}"
    val TOKENS = listOf("{platform}", "{author}", "{title}", "{id}", "{index}", "{date}", "{time}", "{quality}")

    private val illegal = Regex("""[\\/:*?"<>|\u0000-\u001F\u007F]""")
    private val collapse = Regex("""[\s_]{2,}""")

    fun render(
        template: String,
        post: PostInfo,
        item: MediaItem,
        index: Int,
        quality: String? = null,
        now: Instant = Instant.now(),
        zone: ZoneId = ZoneId.systemDefault(),
    ): String {
        val t = template.ifBlank { DEFAULT }
        val values = mapOf(
            "{platform}" to post.platform.displayName.lowercase(),
            "{author}" to (post.author?.removePrefix("@")?.removePrefix("u/") ?: ""),
            "{title}" to (post.title ?: ""),
            "{id}" to post.id,
            "{index}" to if (post.items.size > 1) (index + 1).toString() else "",
            "{date}" to DateTimeFormatter.ofPattern("yyyyMMdd").withZone(zone).format(now),
            "{time}" to DateTimeFormatter.ofPattern("HHmmss").withZone(zone).format(now),
            "{quality}" to (quality ?: ""),
        )
        var name = t
        values.forEach { (k, v) -> name = name.replace(k, sanitize(v)) }
        name = sanitize(name)
            .replace(collapse, "_")
            .trim('_', '-', '.', ' ')
        if (name.isEmpty()) name = "${post.platform.displayName.lowercase()}_${post.id}_${item.id}"
        return name.take(100).trimEnd('_', '-', '.', ' ')
    }

    fun sanitize(s: String): String = s.replace(illegal, "_").replace('\n', ' ').trim()
}
