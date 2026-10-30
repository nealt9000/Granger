package app.granger.library

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import app.granger.core.library.Book
import app.granger.core.library.BookOrigin
import app.granger.core.library.GutenbergBooks
import app.granger.core.library.GutenbergList
import app.granger.core.library.Pack
import app.granger.core.library.PackStatus
import app.granger.core.zim.ZimArchive
import app.granger.shell.RailCovers
import java.io.File

/**
 * A Gutenberg book's cover before its first open (Plan GB Decision 3): the pack's own `{id}_cover.jpg` (or another of
 * the scraper's names), read on demand and kept as a small JPEG under the app's cache, the way Home keeps article
 * pictures ([app.granger.home.ArticleThumbnails]). Nothing is copied at sync; once the book has been opened, its own
 * cover (`Book.coverPath`) wins. Blocking; call off the main thread.
 */
class GutenbergCovers(
    private val packs: suspend () -> List<Pack>,
    private val archiveOf: (packId: String) -> ZimArchive?,
    private val dir: File,
) {
    /** The cached thumbnail's path, or null for a book that isn't Gutenberg's, or whose pack has no usable cover. */
    suspend fun thumbnail(book: Book): String? {
        if (book.origin != BookOrigin.GUTENBERG) return null
        val id = GutenbergBooks.gutenbergIdOf(book)
        val file = File(dir, "$id.jpg")
        if (file.isFile && file.length() > 0L) return file.path
        return try {
            val packName = GutenbergBooks.packNameOf(book)
            val pack = packs().firstOrNull { it.name == packName && it.status == PackStatus.OK } ?: return null
            val archive = archiveOf(pack.id) ?: return null
            val path = GutenbergList.coverPath(archive, id) ?: return null
            val bytes = archive.read(path)?.bytes?.takeIf { it.isNotEmpty() } ?: return null
            val bitmap = decode(bytes) ?: return null
            dir.mkdirs()
            val tmp = File(dir, "${file.name}.tmp")
            tmp.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 85, it) }
            if (tmp.renameTo(file)) file.path else null
        } catch (_: Throwable) { // Includes OutOfMemoryError: a bad cover never takes the shelf down.
            null
        }
    }

    private fun decode(bytes: ByteArray): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0 || bounds.outWidth > RailCovers.MAX_SIDE || bounds.outHeight > RailCovers.MAX_SIDE) return null
        val options = BitmapFactory.Options().apply { inSampleSize = RailCovers.sampleSize(bounds.outWidth, bounds.outHeight, TARGET_PX) }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
    }

    companion object {
        /** The shorter side of a kept cover: a grid cell's width, twice over. */
        const val TARGET_PX = 300
    }
}
