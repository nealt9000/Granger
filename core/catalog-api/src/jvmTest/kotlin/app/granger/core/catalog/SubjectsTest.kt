package app.granger.core.catalog

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SubjectsTest {
    private fun pack(id: String, subjects: List<String>, content: String = CONTENT_BOOKS) = PackEntry(
        id = id, title = id.uppercase(), category = "Books", language = "en", sizeBytes = 1, sha256 = SHA_A,
        urls = listOf("https://example.com/$id.zim"), snapshotDate = "2026-11-01", license = "PD",
        hasFulltextIndex = false, zimUuid = "u-$id", zimName = id, content = content, subjects = subjects)

    private fun book(id: String, subjects: List<String>, collection: String = "granger-classics") = BookEntry(
        id = id, title = id, category = "x", language = "en", sizeBytes = 1, sha256 = SHA_A,
        urls = listOf("https://example.com/$id.epub"), releaseDate = "2026-11-01", license = "PD",
        collection = collection, subjects = subjects)

    private val catalog = Catalog(
        formatVersion = 1, generatedAt = "2026-11-16T00:00:00Z",
        packs = listOf(
            pack("gutenberg_en_lcc-r", listOf("health")),
            pack("zimgit-water_en", listOf("food", "health")),
            pack("wikipedia_en_medicine", listOf("health"), content = ""),  // a website: never a book collection
            pack("gutenberg_en_lcc-q", listOf("science")),  // not installed
        ),
        books = listOf(
            book("gc-hamlet", listOf("literature")), book("gc-bible", listOf("religion")),
            book("gc-plato", listOf("religion")), book("cg-quijote", listOf("literature"), "clasicos-granger"),
        ),
        bookSets = listOf(BookSet(id = "african-storybook-en", title = "African Storybook · English",
            subjects = listOf("literature", "languages"),
            books = listOf(book("asb-1", emptyList(), "african-storybook-en"), book("asb-2", emptyList(), "african-storybook-en")))),
    )

    @Test fun installedCollectionsAreListedUnderEachOfTheirSubjects() {
        val bySubject = catalog.bookCollectionsBySubject(
            installedPackIds = setOf("gutenberg_en_lcc-r", "zimgit-water_en", "wikipedia_en_medicine"),
            installedBookIds = setOf("gc-hamlet", "gc-bible", "gc-plato", "asb-2"),
        )
        assertThat(bySubject.keys).containsExactly("literature", "religion", "health", "food", "languages")
        assertThat(bySubject.getValue("health").map { it.id }).containsExactly("gutenberg_en_lcc-r", "zimgit-water_en").inOrder()
        assertThat(bySubject.getValue("food").single().kind).isEqualTo(SubjectCollection.Kind.BOOK_PACK)
        val religion = bySubject.getValue("religion").single()
        assertThat(religion).isEqualTo(SubjectCollection("granger-classics", "granger-classics",
            SubjectCollection.Kind.CLASSICS, listOf("gc-bible", "gc-plato")))
        assertThat(bySubject.getValue("literature").map { it.id }).containsExactly("granger-classics", "african-storybook-en").inOrder()
        assertThat(bySubject.getValue("languages").single().bookIds).containsExactly("asb-2")
    }

    @Test fun nothingInstalledMeansNoSubjects() {
        assertThat(catalog.bookCollectionsBySubject(emptySet(), emptySet())).isEmpty()
    }

    @Test fun subjectsParseFromTheCatalogAndDefaultToEmpty() {
        val bytes = catalogJson(packExtra = ""","content": "books", "subjects": ["health", "science"]""")
        val parsed = (CatalogParser.parse(bytes, TestKeys.sign(bytes), TestKeys.PUBLIC) as ParseResult.Ok).catalog
        assertThat(parsed.packs.single().subjects).containsExactly("health", "science").inOrder()
        val plain = catalogJson()
        val old = (CatalogParser.parse(plain, TestKeys.sign(plain), TestKeys.PUBLIC) as ParseResult.Ok).catalog
        assertThat(old.packs.single().subjects).isEmpty()
    }
}
