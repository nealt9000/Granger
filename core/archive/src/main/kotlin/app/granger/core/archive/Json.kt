package app.granger.core.archive

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

/** JSON helpers shared by the archive.org and LibriVox clients, which both send numbers as strings at times. */

internal fun JsonElement.allStrings(): List<String> = when (this) {
    is JsonArray -> mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
    is JsonPrimitive -> listOfNotNull(contentOrNull)
    else -> emptyList()
}

internal fun JsonElement.firstString(): String? = allStrings().firstOrNull()

internal fun JsonElement.asInt(): Int? = (this as? JsonPrimitive)?.let { it.intOrNull ?: it.contentOrNull?.trim()?.toIntOrNull() }

internal fun JsonElement.asLong(): Long? = (this as? JsonPrimitive)?.let { it.longOrNull ?: it.contentOrNull?.trim()?.toLongOrNull() }

internal fun JsonElement.asBool(): Boolean? =
    (this as? JsonPrimitive)?.let { it.booleanOrNull ?: it.contentOrNull?.trim()?.lowercase()?.toBooleanStrictOrNull() }

private val TAG = Regex("<[^>]*>")
private val SPACES = Regex("\\s+")
private const val MAX_DESCRIPTION = 2000

/** Tags become spaces, runs of white space one space, and the result is cut to 2000 characters. */
internal fun stripHtml(text: String): String =
    text.replace(TAG, " ").replace(SPACES, " ").trim().take(MAX_DESCRIPTION)
