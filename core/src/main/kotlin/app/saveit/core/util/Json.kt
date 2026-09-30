package app.saveit.core.util

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

val LenientJson = Json {
    ignoreUnknownKeys = true
    isLenient = true
    explicitNulls = false
    coerceInputValues = true
}

fun parseJsonOrNull(text: String?): JsonElement? =
    if (text.isNullOrBlank()) null else runCatching { LenientJson.parseToJsonElement(text) }.getOrNull()

/**
 * Walks a JSON tree: string keys index objects, int keys index arrays (negative = from the end).
 * Returns null as soon as a step does not exist.
 */
fun JsonElement?.at(vararg path: Any): JsonElement? {
    var cur: JsonElement? = this
    for (key in path) {
        cur = when {
            cur == null || cur is JsonNull -> return null
            key is String && cur is JsonObject -> cur[key]
            key is Int && cur is JsonArray -> {
                val idx = if (key < 0) cur.size + key else key
                cur.getOrNull(idx)
            }
            else -> return null
        }
    }
    return if (cur is JsonNull) null else cur
}

val JsonElement?.obj: JsonObject? get() = this as? JsonObject
val JsonElement?.arr: JsonArray? get() = this as? JsonArray
val JsonElement?.str: String? get() = (this as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull
val JsonElement?.long: Long? get() = (this as? JsonPrimitive)?.let { it.longOrNull ?: it.doubleOrNull?.toLong() ?: it.contentOrNull?.toLongOrNull() }
val JsonElement?.int: Int? get() = long?.toInt()
val JsonElement?.double: Double? get() = (this as? JsonPrimitive)?.let { it.doubleOrNull ?: it.contentOrNull?.toDoubleOrNull() }
val JsonElement?.bool: Boolean? get() = (this as? JsonPrimitive)?.booleanOrNull

fun JsonElement?.strAt(vararg path: Any): String? = at(*path).str?.takeIf { it.isNotEmpty() }
fun JsonElement?.intAt(vararg path: Any): Int? = at(*path).int
fun JsonElement?.arrAt(vararg path: Any): List<JsonElement> = at(*path).arr ?: emptyList()

/** Depth-first search for every object that has [key]; handy for platform JSON whose layout drifts. */
fun JsonElement.findObjectsWithKey(key: String, limit: Int = 200): List<JsonObject> {
    val out = ArrayList<JsonObject>()
    val stack = ArrayDeque<JsonElement>().apply { add(this@findObjectsWithKey) }
    while (stack.isNotEmpty() && out.size < limit) {
        when (val e = stack.removeLast()) {
            is JsonObject -> {
                if (key in e) out += e
                e.values.reversed().forEach { stack.addLast(it) }
            }
            is JsonArray -> e.reversed().forEach { stack.addLast(it) }
            else -> Unit
        }
    }
    return out
}
