package app.granger.reader

import app.granger.core.library.Book
import app.granger.core.library.BookOrigin
import app.granger.core.library.Bookshelf
import app.granger.core.library.NautilusDatabase
import app.granger.core.library.PackDocumentTitle
import app.granger.core.zim.ZimArchive
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * PDFs inside Kiwix packs (Plan P2). A tapped PDF is copied out of its pack, streamed, to
 * `<dir>/<packId>/<sha1 of path>.pdf` and kept as a pack document: a hidden book, so the e-book reader opens it and
 * it keeps its place. Removing the pack removes it ([Bookshelf.removePackDocuments]).
 */
class PackDocuments(
    private val bookshelf: Bookshelf,
    private val archiveOf: (packId: String) -> ZimArchive?,
    /** The internal volume's `books/packs`. */
    private val dir: () -> File,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    sealed interface Opened {
        data class Ready(val book: Book) : Opened
        data class Failed(val message: String) : Opened
    }

    /** Each pack's list of documents, file name to title ([NautilusDatabase]), read once; empty for a pack without one. */
    private val listed = ConcurrentHashMap<String, Map<String, String>>()

    /**
     * The pack document for [path] in [packId], copied out first unless it already is. Its title in the pack's own list
     * of documents names it first; [linkText], the words of the link tapped in the article, names it when neither that,
     * the entry nor the PDF has a title ([PackDocumentTitle]).
     */
    suspend fun open(packId: String, path: String, linkText: String = ""): Opened = withContext(io) {
        val archive = archiveOf(packId) ?: return@withContext Opened.Failed("Couldn't open this PDF: the pack isn't readable.")
        val entry = archive.entry(path) ?: return@withContext Opened.Failed("Couldn't open this PDF: it isn't in this pack.")
        val file = fileFor(packId, entry.path)
        if (!(file.isFile && file.length() == entry.sizeBytes)) {
            val free = file.parentFile?.apply { mkdirs() }?.usableSpace ?: 0L
            if (free < entry.sizeBytes) return@withContext Opened.Failed("Couldn't open this PDF: not enough space for it.")
            val partial = File(file.parentFile, ".incoming-${file.name}")
            if (!archive.copyTo(entry.path, partial) || !partial.renameTo(file)) {
                partial.delete()
                return@withContext Opened.Failed("Couldn't open this PDF: copying it out of the pack failed.")
            }
        }
        val listedTitle = listedTitles(packId, archive)[entry.path.removePrefix(NautilusDatabase.FILES)].orEmpty()
        val book = bookshelf.registerPackDocument(packId, entry.path, entry.title, file, linkText, listedTitle)
            ?: return@withContext Opened.Failed("Couldn't open this PDF: it is damaged or protected.")
        Opened.Ready(book)
    }

    /**
     * Copies a pack document's file out again when it is gone (storage cleared); false if its pack can't give it. The
     * name it has stands in for the link's words, which are gone, so a document named by its link keeps that name.
     */
    suspend fun restore(book: Book): Boolean {
        if (book.origin != BookOrigin.PACK) return false
        val packId = book.sourceRef.substringBefore('/')
        val path = book.sourceRef.substringAfter('/', "")
        if (path.isEmpty()) return false
        return open(packId, path, book.title) is Opened.Ready
    }

    /**
     * [packId]'s list of documents, read from its `database.js` the first time it's asked for (a nautilus pack's;
     * Kiwix's zimgit-* packs). A pack without one, or one that can't be read, lists nothing.
     */
    private fun listedTitles(packId: String, archive: ZimArchive): Map<String, String> = listed.getOrPut(packId) {
        try {
            archive.read(NautilusDatabase.PATH)?.let { NautilusDatabase.parse(String(it.bytes, Charsets.UTF_8)) }.orEmpty()
        } catch (e: Exception) {
            emptyMap()
        }
    }

    private fun fileFor(packId: String, path: String): File =
        File(File(dir(), packId.replace(UNSAFE, "-")), "${sha1(path)}.pdf")

    private companion object {
        val UNSAFE = Regex("[^A-Za-z0-9._-]+")

        fun sha1(text: String): String =
            MessageDigest.getInstance("SHA-1").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}
