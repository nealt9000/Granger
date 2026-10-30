package app.granger.core.library

import app.granger.core.books.TestEpubs
import java.io.File

/**
 * Gutenberg packs for tests, laid out as `gutenberg_en_lcc-pf_2026-03.zim` is (gutenberg2zim 3.0.1, inspected
 * 2026-10-30): `full_by_title.js` listing every book, one EPUB per book at `{title}.{id}.epub`, its page at
 * `{title}.{id}` and its cover page at `{title}_cover.{id}`, and a cover image `{id}_cover.jpg` (or another of the
 * scraper's names) for most books.
 */
object GutenbergFixtures {
    /** A book of a fixture pack. [epubName] overrides the EPUB's file title; [epub] false leaves the EPUB out. */
    data class FixtureBook(
        val title: String,
        val author: String,
        val id: Int,
        val epub: Boolean = true,
        val cover: String? = "{id}_cover.jpg",
        val epubName: String = title,
    )

    /** Four books of the real West Germanic pack: quotes, a colon, an `Ä` (written `Ä`), brackets. */
    val PF_BOOKS = listOf(
        FixtureBook("An Irishman's Difficulties with the Dutch Language", "J. Irwin (John Irwin) Brown", 43349),
        FixtureBook("Mr. Honey's Banking Dictionary (English-German)", "Winfried Honig", 3299, cover = "{id}_{id}-cover.png"),
        FixtureBook("Pennsylvania Dutch: A Dialect of South German With an Infusion of English", "Samuel Stehman Haldeman", 48417),
        FixtureBook("Ährenlese: A German Reader with Practical Exercises", "Anonymous", 49503, cover = null),
    )

    val COVER_BYTES = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 1, 2, 3)

    /** The scraper's `full_by_title.js` for [books], escaped as Python's `json.dumps` writes it. */
    fun listJs(books: List<FixtureBook>, lcc: String = "PF"): String =
        "var json_data = [" + books.joinToString(", ") { "[${json(it.title)}, ${json(it.author)}, \"110\", ${it.id}, \"$lcc\"]" } + "];"

    /** EPUB bytes for [book], built once in [dir]. */
    fun epubBytes(dir: File, book: FixtureBook): ByteArray =
        TestEpubs.build(dir, name = "${book.id}.epub", title = book.title, authors = listOf(book.author),
            chapters = listOf("<p>The text of ${book.title}. Gutenberg number ${book.id}.</p>"),
            cover = TestEpubs.CoverStyle.EPUB3_PROPERTY).readBytes()

    /** A fixture pack holding [books]. */
    fun archive(
        dir: File,
        books: List<FixtureBook> = PF_BOOKS,
        uuid: String = "u-pf",
        name: String = "gutenberg_en_lcc-pf",
        description: String = "West Germanic languages",
        suggestions: List<app.granger.core.zim.ZimHit> = emptyList(),
    ): FakeZimArchive {
        val files = LinkedHashMap<String, Pair<String, ByteArray>>()
        files[GutenbergList.PATH] = "text/javascript" to listJs(books).toByteArray()
        val pages = HashSet<String>(setOf("Home"))
        for (book in books) {
            pages += "${book.epubName}.${book.id}"
            pages += "${book.epubName}_cover.${book.id}"
            if (book.epub) files["${book.epubName}.${book.id}.epub"] = GutenbergList.EPUB_MIME to epubBytes(dir, book)
            book.cover?.let { files[it.replace("{id}", book.id.toString())] = "image/jpeg" to COVER_BYTES }
        }
        return FakeZimArchive(
            uuid, "Project Gutenberg Library", pages, mainPath = "Home", name = name, description = description,
            files = files, suggestions = suggestions,
        )
    }

    /** The pack row for [archive], as `Pack.from` writes it. */
    fun pack(archive: FakeZimArchive, status: PackStatus = PackStatus.OK) = Pack(
        id = archive.uuid, filePath = "/packs/${archive.uuid}.zim",
        title = GutenbergNames.packTitle(archive.metadata.name, archive.metadata.description),
        description = archive.metadata.description, language = "eng", date = "2026-03-05", sizeBytes = 1_000L,
        hasFulltextIndex = true, status = status, addedAt = 0L, name = archive.metadata.name,
    )

    private fun json(text: String): String = buildString {
        append('"')
        for (c in text) when {
            c == '"' -> append("\\\"")
            c == '\\' -> append("\\\\")
            c.code > 0x7e || c.code < 0x20 -> append("\\u%04x".format(c.code))
            else -> append(c)
        }
        append('"')
    }
}
