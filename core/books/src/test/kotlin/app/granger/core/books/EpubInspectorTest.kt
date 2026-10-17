package app.granger.core.books

import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class EpubInspectorTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun ok(file: File) = (EpubInspector.inspect(file) as Inspection.Ok).meta

    @Test fun readsMetadataCoverAndSpine() {
        val file = TestEpubs.build(
            tmp.root,
            title = "Moby Dick",
            authors = listOf("Herman Melville"),
            translators = listOf("T. Ranslator"),
            subjects = listOf("Whales", "Sea"),
            description = "A whale.",
            collection = "Sea Stories",
            cover = TestEpubs.CoverStyle.EPUB3_PROPERTY,
            chapters = listOf("<p>One</p>", "<p>Two</p>"),
        )
        val meta = ok(file)
        assertThat(meta.title).isEqualTo("Moby Dick")
        assertThat(meta.authors).containsExactly("Herman Melville")
        assertThat(meta.translators).containsExactly("T. Ranslator")
        assertThat(meta.subjects).containsExactly("Whales", "Sea").inOrder()
        assertThat(meta.description).isEqualTo("A whale.")
        assertThat(meta.language).isEqualTo("en")
        assertThat(meta.date).isEqualTo("2020-01-02")
        assertThat(meta.identifier).startsWith("urn:uuid:")
        assertThat(meta.collection).isEqualTo("Sea Stories")
        assertThat(meta.cover).isEqualTo(TestEpubs.COVER_BYTES)
        assertThat(meta.coverExtension).isEqualTo("png")
        assertThat(meta.spine).containsExactly("OEBPS/c001.xhtml", "OEBPS/c002.xhtml").inOrder()
    }

    @Test fun epub3RefinedRolesGiveAuthorsAndTranslators() {
        val contributors = ok(
            TestEpubs.build(
                tmp.root, name = "a.epub", epub3Roles = true,
                authors = listOf("Leo Tolstoy"), translators = listOf("Louise Maude", "Aylmer Maude"),
            ),
        )
        assertThat(contributors.authors).containsExactly("Leo Tolstoy")
        assertThat(contributors.translators).containsExactly("Louise Maude", "Aylmer Maude").inOrder()

        // Translators written as creators are translators, not authors.
        val creators = ok(
            TestEpubs.build(
                tmp.root, name = "b.epub", epub3Roles = true, translatorTag = "dc:creator",
                authors = listOf("Homer"), translators = listOf("Samuel Butler"),
            ),
        )
        assertThat(creators.authors).containsExactly("Homer")
        assertThat(creators.translators).containsExactly("Samuel Butler")
    }

    @Test fun missingTitleUsesFileName() {
        val file = TestEpubs.build(tmp.root, name = "lost-book.epub", title = null)
        assertThat(ok(file).title).isEqualTo("lost-book")
        assertThat((EpubInspector.inspect(file, "Given") as Inspection.Ok).meta.title).isEqualTo("Given")
    }

    @Test fun epub2CoverMetaIsFound() {
        val meta = ok(TestEpubs.build(tmp.root, cover = TestEpubs.CoverStyle.EPUB2_META))
        assertThat(meta.cover).isEqualTo(TestEpubs.COVER_BYTES)
    }

    @Test fun noCoverIsNull() {
        val meta = ok(TestEpubs.build(tmp.root))
        assertThat(meta.cover).isNull()
        assertThat(meta.coverExtension).isNull()
        assertThat(meta.collection).isNull()
    }

    @Test fun pdfRenamedEpubIsInvalid() {
        val file = tmp.newFile("fake.epub").apply { writeText("%PDF-1.7 not a zip at all") }
        assertThat(EpubInspector.inspect(file)).isInstanceOf(Inspection.Invalid::class.java)
    }

    @Test fun zipWithoutOpfIsInvalid() {
        val file = TestEpubs.build(tmp.root, includeOpf = false)
        assertThat(EpubInspector.inspect(file)).isInstanceOf(Inspection.Invalid::class.java)
    }

    @Test fun emptySpineIsInvalid() {
        val file = TestEpubs.build(tmp.root, chapters = emptyList())
        assertThat(EpubInspector.inspect(file)).isInstanceOf(Inspection.Invalid::class.java)
    }

    @Test fun emptyFileIsInvalid() {
        val file = tmp.newFile("empty.epub")
        assertThat(EpubInspector.inspect(file)).isInstanceOf(Inspection.Invalid::class.java)
    }

    @Test fun protectedIsReportedBeforeParsing() {
        val file = TestEpubs.build(tmp.root, includeOpf = false, drmFiles = mapOf("META-INF/license.lcpl" to "{}"))
        assertThat(EpubInspector.inspect(file)).isEqualTo(Inspection.Protected(DrmKind.LCP))
    }

    @Test fun corruptOpfIsInvalid() {
        val file = TestEpubs.build(tmp.root)
        TestEpubs.corrupt(file, "OEBPS/content.opf")
        assertThat(EpubInspector.inspect(file)).isInstanceOf(Inspection.Invalid::class.java)
    }

    @Test fun corruptContainerIsInvalid() {
        val file = TestEpubs.build(tmp.root)
        TestEpubs.corrupt(file, "META-INF/container.xml")
        assertThat(EpubInspector.inspect(file)).isInstanceOf(Inspection.Invalid::class.java)
    }

    @Test fun oversizedCoverIsDroppedButBookIsOk() {
        val file = TestEpubs.build(
            tmp.root,
            cover = TestEpubs.CoverStyle.EPUB3_PROPERTY,
            coverBytes = ByteArray(20 * 1024 * 1024),
        )
        assertThat(ok(file).cover).isNull()
        assertThat(ok(file).coverExtension).isNull()
    }

    @Test fun oversizedOpfIsInvalid() {
        val file = TestEpubs.build(tmp.root, description = "x".repeat(5 * 1024 * 1024))
        assertThat(EpubInspector.inspect(file)).isInstanceOf(Inspection.Invalid::class.java)
    }
}
