package app.granger.core.online

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** `org.json`'s forgiving reads, over kotlinx.serialization (Plan I1): a missing or mistyped value gives the default. */
internal fun parseObject(json: String): JsonObject? = runCatching { Json.parseToJsonElement(json) as? JsonObject }.getOrNull()

internal fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject
internal fun JsonObject.arr(key: String): JsonArray? = this[key] as? JsonArray

/** `optString`: "" for a missing value or JSON null; a number's or boolean's text otherwise. */
internal fun JsonObject.str(key: String): String = (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content.orEmpty()

internal fun JsonObject.long(key: String, default: Long = 0): Long = (this[key] as? JsonPrimitive)?.content?.toLongOrNull() ?: default
internal fun JsonObject.int(key: String, default: Int = 0): Int = (this[key] as? JsonPrimitive)?.content?.toIntOrNull() ?: default
internal fun JsonObject.bool(key: String): Boolean = (this[key] as? JsonPrimitive)?.content == "true"

internal fun JsonArray.objects(): List<JsonObject> = mapNotNull { it as? JsonObject }

internal fun jsonOf(vararg pairs: Pair<String, Any?>): String = JsonObject(
    pairs.mapNotNull { (k, v) ->
        when (v) {
            null -> null
            is String -> k to JsonPrimitive(v)
            is Number -> k to JsonPrimitive(v)
            is Boolean -> k to JsonPrimitive(v)
            is JsonElement -> k to v
            else -> k to JsonPrimitive(v.toString())
        }
    }.toMap(),
).toString()
