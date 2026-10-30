package app.granger.core.library

import app.granger.core.zim.ZimArchive
import java.io.File
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import org.json.JSONArray
import org.json.JSONException

/** One book in a Gutenberg pack's list: its title, author and Gutenberg id (`22636`). */
data class GutenbergListing(val title: String, val author: String, val gutenbergId: String)

/**
 * A Gutenberg pack's list of books, `full_by_title.js`: `var json_data = [[title, author, "110", id, "LCC"], …];`
 * (gutenberg2zim 3.0.1, inspected 2026-10-30). Author pages and per-shelf lists sit beside it; this one lists them all.
 */
object GutenbergList {
    const val PATH = "full_by_title.js"

    /** The books of [js], in its order; a malformed row is skipped, and a malformed file lists nothing. */
    fun parse(js: String): List<GutenbergListing> {
        val start = js.indexOf('[')
        val end = js.lastIndexOf(']')
        if (start < 0 || end <= start) return emptyList()
        val rows = try {
            JSONArray(js.substring(start, end + 1))
        } catch (_: JSONException) {
            return emptyList()
        }
        val out = ArrayList<GutenbergListing>(rows.length())
        for (i in 0 until rows.length()) {
            val row = rows.optJSONArray(i) ?: continue
            val title = row.optString(0).trim()
            val id = row.opt(3)?.toString()?.trim()?.removeSuffix(".0").orEmpty()
            if (title.isEmpty() || id.isEmpty() || !id.all(Char::isDigit)) continue
            out += GutenbergListing(title, row.optString(1).trim(), id)
        }
        return out
    }

    /**
     * Where [listing]'s EPUB is in [archive]: `{title}.{id}.epub`, built from the listed title exactly (titles hold `:`,
     * `'`, `(`). If that misses (the scraper changed the title for its file name), the title suggestions are asked for
     * the book's own page, `{title}.{id}` or `{title}_cover.{id}`, and its EPUB is the page's path plus `.epub`. Null
     * when the pack has no EPUB for it.
     */
    fun epubPath(archive: ZimArchive, listing: GutenbergListing): String? {
        val id = listing.gutenbergId
        val direct = "${listing.title}.$id.epub"
        if (isEpub(archive, direct)) return direct
        val suffix = ".$id"
        return archive.suggest(listing.title, SUGGESTIONS).asSequence()
            .map { it.path }
            .filter { it.endsWith(suffix) }
            .map { it.removeSuffix(suffix).removeSuffix("_cover") + "$suffix.epub" }
            .firstOrNull { isEpub(archive, it) }
    }

    /** The pack's cover image for book [gutenbergId], by the names the scraper gives them; null when it has none. */
    fun coverPath(archive: ZimArchive, gutenbergId: String): String? =
        COVER_NAMES.asSequence().map { it.replace("{id}", gutenbergId) }.firstNotNullOfOrNull { archive.resolvePath(it) }

    /** The Gutenberg id in an EPUB entry's path (`Some Title.22636.epub`), or null if it isn't one. */
    fun idOfEpub(path: String): String? =
        path.takeIf { it.endsWith(".epub") }?.removeSuffix(".epub")?.substringAfterLast('.', "")?.takeIf { it.isNotEmpty() && it.all(Char::isDigit) }

    private fun isEpub(archive: ZimArchive, path: String): Boolean =
        archive.entry(path)?.mimeType?.substringBefore(';')?.trim() == EPUB_MIME

    const val EPUB_MIME = "application/epub+zip"
    private const val SUGGESTIONS = 20

    /** Seen in the packs: `{id}_cover.jpg`, `{id}_{id}-cover.png`, `{id}_new-cover.jpg`; the rest are their siblings. */
    private val COVER_NAMES = listOf(
        "{id}_cover.jpg", "{id}_{id}-cover.png", "{id}_new-cover.jpg",
        "{id}_cover.png", "{id}_{id}-cover.jpg", "{id}_new-cover.png",
    )
}

/**
 * Project Gutenberg packs as a [PackBookSource] (Plan GB Decision 3): each book of the pack's `full_by_title.js` is a
 * [BookOrigin.GUTENBERG] row, `book:gutenberg:<id>`, one row whichever pack or snapshot holds it, copied out to
 * `<id>.epub` when opened. Subject packs are read before the two everything-packs, so a shared book is filed under its
 * subject.
 */
object GutenbergSource : PackBookSource {
    override val origin = BookOrigin.GUTENBERG

    override val packOrder: Comparator<Pack> = compareBy<Pack>({ it.name.endsWith("_all") }, { it.name }, { it.id })

    override fun list(pack: Pack, archive: ZimArchive): List<PackItem>? {
        val js = archive.read(GutenbergList.PATH)?.bytes ?: return null
        return GutenbergList.parse(String(js, Charsets.UTF_8)).mapNotNull { listing ->
            val path = GutenbergList.epubPath(archive, listing) ?: return@mapNotNull null
            PackItem(listing.gutenbergId, path, listing.title, listing.author, BookFormat.EPUB, archive.entry(path)?.sizeBytes ?: 0L)
        }
    }

    override fun refOf(pack: Pack, path: String): String? = GutenbergList.idOfEpub(path)

    override fun fileName(ref: String, format: BookFormat): String = "$ref.epub"
}

/**
 * The books of the installed Project Gutenberg packs as shelf rows (Plan GB Decision 3): [PackBooks] over
 * [GutenbergSource]. [sync] lists every book of every installed Gutenberg pack, and nothing is copied; [restore] copies
 * a book's EPUB out of its pack the first time it's opened.
 *
 * A row's `sourceRef` is `<pack name>/<epub path>`: the ZIM name, not its UUID, so a new snapshot keeps the row, and
 * with it the place and marks. Its `filePath` is `<dir>/<id>.epub`, there only once it has been opened.
 */
class GutenbergBooks(
    db: LibraryDatabase,
    archiveOf: (packId: String) -> ZimArchive?,
    /** Where opened books are copied: the internal volume's `books/gutenberg`. */
    dir: () -> File,
    coversDir: File,
    io: CoroutineDispatcher = Dispatchers.IO,
    now: () -> Long = System::currentTimeMillis,
) : PackBooks(db, GutenbergSource, archiveOf, dir, coversDir, io, now) {
    companion object {
        /** The pack name in a Gutenberg row's `sourceRef` (`gutenberg_en_lcc-m/Title.123.epub`). */
        fun packNameOf(book: Book): String = PackBooks.packNameOf(book)

        /** The Gutenberg id of a [BookOrigin.GUTENBERG] row (`book:gutenberg:22636` → `22636`). */
        fun gutenbergIdOf(book: Book): String = book.id.removePrefix(BookIds.of(BookOrigin.GUTENBERG, ""))
    }
}
