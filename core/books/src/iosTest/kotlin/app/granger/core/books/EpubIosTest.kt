package app.granger.core.books

import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import platform.Foundation.NSTemporaryDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** A text becomes an EPUB on the Simulator (the zip writer), which the shared inspector reads back (the zip reader). */
class EpubIosTest {
    private fun temp(name: String) = NSTemporaryDirectory().trimEnd('/') + "/granger-books-" + randomUuid() + "-" + name

    @Test
    fun aTextBecomesAnEpubThatReadsBack() {
        val path = temp("walden.epub")
        val text = (1..3).joinToString("\n\n") { "CHAPTER $it\n\n" + "It was a quiet morning by the pond. ".repeat(40) }
        TextToEpub.convert(text, "Walden", markdown = false, out = outputTo(path))
        try {
            val meta = assertIs<Inspection.Ok>(EpubInspector.inspect(path)).meta
            assertEquals("Walden", meta.title)
            assertEquals("en", meta.language)
            assertEquals(listOf("OEBPS/c001.xhtml", "OEBPS/c002.xhtml", "OEBPS/c003.xhtml"), meta.spine)
            val chunks = XhtmlText.all(path, meta.spine).toList()
            assertTrue(chunks.isNotEmpty())
            assertTrue(chunks.first().text.contains("quiet morning by the pond"))
        } finally {
            deleteAll(path)
        }
    }

    @Test
    fun notAZipIsSaidSo() {
        val path = temp("not.epub")
        SystemFileSystem.sink(Path(path)).buffered().use { it.write("plain text, not a zip".encodeToByteArray()) }
        try {
            assertEquals(Inspection.Invalid("Not a zip file"), EpubInspector.inspect(path))
        } finally {
            deleteAll(path)
        }
    }

    @Test
    fun theIdentifierMatchesJavasNameUuid() {
        // java.util.UUID.nameUUIDFromBytes("walden".toByteArray()), worked out on the JVM.
        assertEquals(JAVA_WALDEN, nameUuid("walden".encodeToByteArray()))
    }

    private companion object {
        const val JAVA_WALDEN = "2f1c8915-8649-3e33-8f5a-250c53fadb51"
    }
}
