package app.granger.tools.chunker

import app.granger.core.books.XhtmlChunks
import app.granger.core.research.HtmlPassages
import app.granger.core.text.Chunkers
import com.google.common.truth.Truth.assertThat
import java.util.Base64
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Test

class MainTest {
    private fun source(kind: String, key: String, bytes: ByteArray) = buildJsonObject {
        put("kind", kind)
        put("key", key)
        put("bytes", Base64.getEncoder().encodeToString(bytes))
    }

    private val chapter = ("<html xmlns=\"http://www.w3.org/1999/xhtml\"><body>" +
        (1..40).joinToString("") { "<p>Verse $it: take no thought for the morrow, for the morrow shall take thought.</p>" } +
        "</body></html>").toByteArray()

    private val article = ("<html><body><h1 id=\"top\">Knots</h1><p>" + "A knot is a fastening. ".repeat(60) +
        "</p><h2 id=\"Uses\">Uses</h2><p>" + "Sailors tie knots. ".repeat(30) + "</p></body></html>").toByteArray()

    @Test fun xhtmlMatchesTheAppsChunker() {
        val lines = chunk(source("xhtml", "OEBPS/c001.xhtml", chapter))
        val app = XhtmlChunks.of("OEBPS/c001.xhtml", chapter)
        assertThat(lines.map { it.getValue("text").jsonPrimitive.content }).isEqualTo(app.map { it.text })
        assertThat(lines.map { it.getValue("ordinal").jsonPrimitive.int }).isEqualTo(app.map { it.ordinal })
        assertThat(lines.map { it.getValue("progression").jsonPrimitive.double }).isEqualTo(app.map { it.progression })
        assertThat(lines.first()["heading"]).isEqualTo(JsonNull)
    }

    @Test fun htmlMatchesTheAppsSplitter() {
        val lines = chunk(source("html", "A/Knot", article))
        val app = HtmlPassages.split(article.toString(Charsets.UTF_8))
        assertThat(lines.map { it.getValue("text").jsonPrimitive.content }).isEqualTo(app.map { it.text })
        assertThat(lines.map { it.getValue("anchor").jsonPrimitive.content }).isEqualTo(app.map { it.anchor })
        assertThat(lines.map { it.getValue("ordinal").jsonPrimitive.int }).isEqualTo(app.indices.toList())
    }

    @Test fun hashIsTheTextsHash() {
        val line = chunk(source("html", "A/Knot", article)).first()
        assertThat(line.getValue("hash").jsonPrimitive.content).isEqualTo(Chunkers.hash(line.getValue("text").jsonPrimitive.content))
    }

    @Test fun malformedUtf8DecodesAsThePhoneDoes() {
        val bytes = "<p>caf".toByteArray() + byteArrayOf(0xC3.toByte()) + " ok</p>".toByteArray()
        val text = chunk(source("html", "A/x", bytes)).single().getValue("text").jsonPrimitive.content
        assertThat(text).isEqualTo(HtmlPassages.split(bytes.toString(Charsets.UTF_8)).single().text)
    }
}
