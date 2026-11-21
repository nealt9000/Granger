package app.granger.core.library

import app.granger.core.zim.ZimArchive
import java.io.File
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/**
 * Project Gutenberg packs as a [PackBookSource] (Plan GB Decision 3): each book of the pack's `full_by_title.js` is a
 * [BookOrigin.GUTENBERG] row, `book:gutenberg:<id>`, one row whichever pack or snapshot holds it, copied out to
 * `<id>.epub` when opened. Subject packs are read before the two everything-packs, so a shared book is filed under its
 * subject.
 */
object GutenbergSource : PackBookSource {
    override val origin = BookOrigin.GUTENBERG

    override val packOrder: Comparator<Pack> = compareBy<Pack>({ it.name.endsWith("_all") }, { it.name }, { it.id })

    override fun list(pack: Pack, archive: ZimArchive): List<PackItem>? = PackBookList.gutenberg(archive)

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
