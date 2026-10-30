package app.granger.library

import app.granger.core.library.Book
import app.granger.core.library.BookOrigin
import app.granger.core.library.GutenbergBooks
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** How far "Make all searchable…" has got with one pack's books: [done] of [total] copied out. */
data class SearchableProgress(val done: Int, val total: Int)

/** What making a collection searchable takes: [bytes] more on the phone, of [freeBytes] free. */
data class SearchableCost(val books: Int, val bytes: Long, val freeBytes: Long) {
    val fits: Boolean get() = bytes <= freeBytes
}

/** Whether a Gutenberg book's full text is in Search and Ask (Plan GB Decision 6): it's been opened and indexed. */
val Book.searchableInFull: Boolean get() = origin == BookOrigin.GUTENBERG && indexedAt != null && searchable

/**
 * "Make all searchable…" for a Gutenberg collection (Plan GB Decision 5): every book not yet copied out of its pack is
 * copied out ([GutenbergBooks.restore]) and indexed, a batch at a time, on [scope] so leaving the screen doesn't stop
 * it. One run per pack at a time; [progress] says how far each has got, and the run's entry goes when it ends.
 */
class GutenbergShelf(
    /** Copies one book out of its pack ([GutenbergBooks.restore]). */
    private val restore: suspend (Book) -> Boolean,
    private val indexPending: suspend () -> Unit,
    /** Where the copies go: the internal volume's `books/gutenberg`. */
    private val dir: () -> File,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val batch: Int = BATCH,
) {
    private val _progress = MutableStateFlow<Map<String, SearchableProgress>>(emptyMap())

    /** Running runs by pack name. */
    val progress: StateFlow<Map<String, SearchableProgress>> = _progress.asStateFlow()

    /** What copying out the [rows] not yet on the phone takes, against the free space where they'd go. */
    suspend fun cost(rows: List<Book>): SearchableCost = withContext(io) {
        val pending = rows.filter { it.origin == BookOrigin.GUTENBERG && !File(it.filePath).isFile }
        val target = dir().apply { mkdirs() }
        SearchableCost(pending.size, pending.sumOf { it.sizeBytes }, target.usableSpace)
    }

    /** Copies out and indexes the [rows] of [packName] not yet on the phone; nothing if a run for it is going. */
    fun makeSearchable(packName: String, rows: List<Book>) {
        var started = false
        _progress.update { if (packName in it) it else { started = true; it + (packName to SearchableProgress(0, rows.size)) } }
        if (!started) return
        scope.launch {
            try {
                val pending = withContext(io) { rows.filter { it.origin == BookOrigin.GUTENBERG && !File(it.filePath).isFile } }
                var done = rows.size - pending.size
                publish(packName, done, rows.size)
                for ((i, book) in pending.withIndex()) {
                    try {
                        restore(book)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        android.util.Log.w("Granger", "Couldn't copy out ${book.id}", e)
                    }
                    publish(packName, ++done, rows.size)
                    if ((i + 1) % batch == 0) indexPending()
                }
                indexPending()
            } finally {
                _progress.update { it - packName }
            }
        }
    }

    private fun publish(packName: String, done: Int, total: Int) =
        _progress.update { it + (packName to SearchableProgress(done, total)) }

    private companion object {
        /** Books copied between index passes, so the count of searchable books climbs as the run goes. */
        const val BATCH = 20
    }
}
