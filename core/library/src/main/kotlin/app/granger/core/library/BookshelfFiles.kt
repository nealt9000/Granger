package app.granger.core.library

import java.io.File
import java.io.InputStream
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.io.asSource
import kotlinx.io.buffered

/**
 * [Bookshelf] backed by Room: the shared [BookshelfStore] (Plan I5 Task 1) over Android's `File`s and the platform's PDF
 * renderer for covers.
 *
 * @param bookDirs each volume's `books/` folder; downloads live in `granger/` and imports in `imports/` under it.
 * @param importDir where imports are copied (the internal volume's `books/imports`).
 * @param maxTextBytes the largest TXT or Markdown file imported; conversion holds the whole text in memory.
 */
class RoomBookshelf(
    db: LibraryDatabase,
    bookDirs: () -> List<File>,
    importDir: () -> File,
    coversDir: File,
    io: CoroutineDispatcher = Dispatchers.IO,
    now: () -> Long = System::currentTimeMillis,
    maxTextBytes: Long = 20L * 1024 * 1024,
    pdfCovers: PdfCovers = PlatformPdfCovers,
) : BookshelfStore(
    db, { bookDirs().map { it.path } }, { importDir().path }, coversDir.path, io, now, maxTextBytes, pdfCovers,
)

/*
 * Android's callers hand the shelf `File`s and `InputStream`s, as they did before it was shared (Plan I5): these pass
 * them on. Callers outside this package import them by name (app.granger.core.library.addDownloaded, …).
 */

suspend fun Bookshelf.addDownloaded(file: File, origin: BookOrigin, ref: String, details: BookDetails? = null): Book? =
    addDownloaded(file.path, origin, ref, details)

suspend fun Bookshelf.import(displayName: String, open: () -> InputStream): ImportResult =
    importFrom(displayName) { open().asSource().buffered() }

suspend fun Bookshelf.addLoose(
    id: String, origin: BookOrigin, sourceRef: String, file: File, details: BookDetails, place: PackItem? = null,
): Book? = addLoose(id, origin, sourceRef, file.path, details, place)

suspend fun Bookshelf.keepLoose(id: String, to: File): Book? = keepLoose(id, to.path)

suspend fun Bookshelf.registerPackDocument(
    zimId: String, path: String, title: String, file: File, linkText: String = "", listedTitle: String = "",
): Book? = registerPackDocument(zimId, path, title, file.path, linkText, listedTitle)
