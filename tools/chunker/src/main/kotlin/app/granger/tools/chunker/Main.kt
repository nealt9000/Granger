package app.granger.tools.chunker

import app.granger.core.books.XhtmlChunks
import app.granger.core.research.HtmlPassages
import app.granger.core.text.Chunkers
import java.util.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Reads one source per line, `{"kind": "xhtml"|"html", "key": ..., "bytes": <base64>}`, and writes one line per chunk:
 * `{"key", "ordinal", "heading", "anchor", "progression", "text", "hash"}`. The bytes are decoded exactly as the phone
 * decodes them, so chunks match the app's. `--version` prints [Chunkers.VERSION].
 */
fun main(args: Array<String>) {
    if (args.contains("--version")) {
        println(Chunkers.VERSION)
        return
    }
    val out = System.out.bufferedWriter()
    System.`in`.bufferedReader().lineSequence().filter { it.isNotBlank() }.forEach { line ->
        for (chunk in chunk(Json.parseToJsonElement(line).jsonObject)) {
            out.write(chunk.toString())
            out.newLine()
        }
    }
    out.flush()
}

internal fun chunk(source: JsonObject): List<JsonObject> {
    val kind = source.getValue("kind").jsonPrimitive.content
    val key = source.getValue("key").jsonPrimitive.content
    val bytes = Base64.getDecoder().decode(source.getValue("bytes").jsonPrimitive.content)
    return when (kind) {
        // As BookIndex reads a chapter: XhtmlText.chunks over the zip entry's bytes.
        "xhtml" -> XhtmlChunks.of(key, bytes).map {
            line(key, it.ordinal, heading = null, anchor = null, progression = it.progression, text = it.text)
        }
        // As LibraryPassageFinder reads an article: the item's bytes as UTF-8, then HtmlPassages.split.
        "html" -> HtmlPassages.split(bytes.toString(Charsets.UTF_8)).mapIndexed { i, p ->
            line(key, i, heading = p.heading, anchor = p.anchor, progression = null, text = p.text)
        }
        else -> error("unknown kind: $kind")
    }
}

private fun line(key: String, ordinal: Int, heading: String?, anchor: String?, progression: Double?, text: String) =
    buildJsonObject {
        put("key", key)
        put("ordinal", ordinal)
        put("heading", heading)
        put("anchor", anchor)
        put("progression", progression)
        put("text", text)
        put("hash", Chunkers.hash(text))
    }
