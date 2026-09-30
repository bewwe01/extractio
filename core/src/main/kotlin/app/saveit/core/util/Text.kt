package app.saveit.core.util

import app.saveit.core.util.LenientJson
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import org.jsoup.nodes.Document

/** Decodes the body of a JSON string literal (the part between the quotes). */
fun unescapeJsonString(raw: String): String =
    runCatching { LenientJson.parseToJsonElement("\"$raw\"").jsonPrimitive.content }.getOrElse {
        raw.replace("\\/", "/").replace("\\u0026", "&").replace("\\u0025", "%").replace("\\\"", "\"")
    }

fun jsonQuote(value: String): String = JsonPrimitive(value).toString()

/** Content of `<meta property|name="...">`. */
fun Document.meta(vararg names: String): String? {
    for (name in names) {
        val v = selectFirst("meta[property=$name], meta[name=$name]")?.attr("content")
        if (!v.isNullOrBlank()) return v
    }
    return null
}

/**
 * Extracts the JSON value that starts at [start] (an object or array) using bracket matching that
 * respects strings. Returns null if the text is truncated.
 */
fun extractBalancedJson(text: String, start: Int): String? {
    if (start !in text.indices) return null
    val open = text[start]
    if (open != '{' && open != '[') return null
    var depth = 0
    var inString = false
    var escaped = false
    for (i in start until text.length) {
        val c = text[i]
        if (inString) {
            when {
                escaped -> escaped = false
                c == '\\' -> escaped = true
                c == '"' -> inString = false
            }
            continue
        }
        when (c) {
            '"' -> inString = true
            '{', '[' -> depth++
            '}', ']' -> {
                depth--
                if (depth == 0) return text.substring(start, i + 1)
            }
        }
    }
    return null
}

/** Finds `marker` and returns the balanced JSON value that follows it. */
fun extractJsonAfter(text: String, marker: String, from: Int = 0): String? {
    val idx = text.indexOf(marker, from)
    if (idx < 0) return null
    var i = idx + marker.length
    while (i < text.length && text[i] != '{' && text[i] != '[') {
        if (!text[i].isWhitespace() && text[i] != '=' && text[i] != ':' && text[i] != '(' && text[i] != ',') return null
        i++
    }
    return extractBalancedJson(text, i)
}

/** File extension guessed from a URL path (without query). */
fun extensionFromUrl(url: String): String? {
    val path = url.substringBefore('?').substringBefore('#')
    val last = path.substringAfterLast('/')
    if ('.' !in last) return null
    val ext = last.substringAfterLast('.').lowercase()
    return ext.takeIf { it.length in 2..5 && it.all { c -> c.isLetterOrDigit() } }
        ?.let { if (it == "jpeg") "jpg" else it }
}

fun String.truncateForTitle(max: Int = 120): String {
    val oneLine = replace(Regex("\\s+"), " ").trim()
    return if (oneLine.length <= max) oneLine else oneLine.take(max - 1).trimEnd() + "…"
}

/**
 * Finds `"key":"...value..."` in raw page source and returns the decoded string value.
 * Scans linearly (regex alternation over long escaped strings overflows the JVM stack).
 */
fun findJsonStringValue(text: String, key: String, from: Int = 0): String? {
    val marker = "\"" + key + "\""
    var idx = text.indexOf(marker, from)
    while (idx >= 0) {
        var i = idx + marker.length
        while (i < text.length && text[i].isWhitespace()) i++
        if (i < text.length && text[i] == ':') {
            i++
            while (i < text.length && text[i].isWhitespace()) i++
            if (i < text.length && text[i] == '"') {
                val raw = readJsonStringBody(text, i + 1)
                if (raw != null) return unescapeJsonString(raw)
            }
        }
        idx = text.indexOf(marker, idx + marker.length)
    }
    return null
}

/** Returns the still-escaped body of a JSON string starting right after its opening quote. */
fun readJsonStringBody(text: String, start: Int): String? {
    var i = start
    while (i < text.length) {
        when (text[i]) {
            '\\' -> i += 2
            '"' -> return text.substring(start, i)
            else -> i++
        }
    }
    return null
}
