package app.granger.core.zim

import org.kiwix.libzim.Archive
import org.kiwix.libzim.Query
import org.kiwix.libzim.Searcher
import org.kiwix.libzim.SuggestionSearcher
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

/**
 * [ZimArchive] over libkiwix. Reads run in parallel under a read lock; [close] takes the write lock,
 * so native objects are never used after they are disposed. Searchers are not thread-safe, so
 * searches are serialized on [searchLock].
 */
internal class KiwixZimArchive(private val archive: Archive, fallbackTitle: String) : ZimArchive {
    private val lock = ReentrantReadWriteLock()
    private var closed = false
    private val searchLock = Any()
    private var searcher: Searcher? = null
    private var suggester: SuggestionSearcher? = null

    override val uuid: String = archive.uuid
    override val metadata = ZimMetadata(
        title = meta("Title").ifBlank { fallbackTitle },
        description = meta("Description"),
        language = meta("Language"),
        date = meta("Date"),
        name = meta("Name"),
        scraper = meta("Scraper"),
    )
    override val mainPath: String? =
        if (archive.hasMainEntry()) archive.mainEntry.getItem(true).path else null
    override val hasFulltextIndex: Boolean = archive.hasFulltextIndex()

    private fun meta(name: String): String =
        try { archive.getMetadata(name) } catch (e: Exception) { "" }

    private inline fun <T> reading(fallback: T, block: () -> T): T = lock.read {
        if (closed) fallback else try { block() } catch (e: Exception) { fallback }
    }

    override fun resolvePath(path: String): String? = reading(null) {
        if (archive.hasEntryByPath(path)) archive.getEntryByPath(path).getItem(true).path else null
    }

    override fun randomPath(): String? = reading(null) { archive.randomEntry.getItem(true).path }

    override fun read(path: String): ZimItem? = reading(null) {
        if (!archive.hasEntryByPath(path)) return@reading null
        val item = archive.getEntryByPath(path).getItem(true)
        ZimItem(item.path, item.mimetype, item.data.data)
    }

    override fun entry(path: String): ZimEntry? = reading(null) {
        if (!archive.hasEntryByPath(path)) return@reading null
        val item = archive.getEntryByPath(path).getItem(true)
        ZimEntry(item.path, item.title, item.mimetype, item.size)
    }

    /**
     * A stored (uncompressed) entry, as PDFs in a ZIM usually are, is copied as a byte range of the `.zim` file
     * itself; a compressed one has no such range and is read whole, as [read] does.
     */
    override fun copyTo(path: String, out: File): Boolean {
        val source = reading(null) {
            if (!archive.hasEntryByPath(path)) return@reading null
            val item = archive.getEntryByPath(path).getItem(true)
            val direct = item.directAccessInformation
            if (direct != null && direct.filename.isNotEmpty()) {
                Source.Range(File(direct.filename), direct.offset, item.size)
            } else {
                Source.Bytes(item.data.data)
            }
        } ?: return false
        return try {
            out.parentFile?.mkdirs()
            when (source) {
                is Source.Bytes -> out.writeBytes(source.bytes)
                is Source.Range -> RandomAccessFile(source.file, "r").use { zim ->
                    FileOutputStream(out).use { stream ->
                        val channel = zim.channel
                        var done = 0L
                        while (done < source.size) {
                            val n = channel.transferTo(source.offset + done, source.size - done, stream.channel)
                            if (n <= 0) throw java.io.IOException("Short copy of $path at $done of ${source.size}")
                            done += n
                        }
                    }
                }
            }
            true
        } catch (e: Exception) {
            out.delete()
            false
        }
    }

    private sealed interface Source {
        class Range(val file: File, val offset: Long, val size: Long) : Source
        class Bytes(val bytes: ByteArray) : Source
    }

    override fun suggest(query: String, limit: Int): List<ZimHit> = reading(emptyList()) {
        if (query.isBlank()) return@reading emptyList()
        synchronized(searchLock) {
            val suggester = suggester ?: SuggestionSearcher(archive).also { suggester = it }
            val search = suggester.suggest(query.trim())
            val results = search.getResults(0, limit)
            try {
                buildList {
                    while (results.hasNext()) {
                        val item = results.next()
                        add(ZimHit(item.path, item.title))
                    }
                }
            } finally {
                results.dispose()
                search.dispose()
            }
        }
    }

    override fun search(query: String, limit: Int): List<ZimHit> = search(query, limit, snippets = true)

    override fun searchWithoutSnippets(query: String, limit: Int): List<ZimHit> = search(query, limit, snippets = false)

    private fun search(query: String, limit: Int, snippets: Boolean): List<ZimHit> = reading(emptyList()) {
        if (!hasFulltextIndex || query.isBlank()) return@reading emptyList()
        synchronized(searchLock) {
            val searcher = searcher ?: Searcher(archive).also { searcher = it }
            val q = Query(query.trim())
            val search = searcher.search(q)
            val results = search.getResults(0, limit)
            try {
                buildList {
                    while (results.hasNext()) {
                        // libkiwix's getters describe the entry the *next* call to next() returns,
                        // so read them before advancing.
                        val hit = ZimHit(
                            path = results.path,
                            title = results.title,
                            snippet = if (snippets) results.snippet?.takeIf { it.isNotBlank() } else null,
                            score = results.score,
                        )
                        results.next()
                        add(hit)
                    }
                }
            } finally {
                // Disposed here, under the lock: left to their finalizers, they would be freed on the finalizer thread
                // while the next search uses the same Xapian database, whose reference counts aren't thread-safe
                // (a SIGSEGV in Xapian::Database::get_doccount during Plan R5 Task 1).
                results.dispose()
                search.dispose()
                q.dispose()
            }
        }
    }

    override fun close() = lock.write {
        if (closed) return@write
        closed = true
        searcher?.dispose()
        suggester?.dispose()
        archive.dispose()
    }
}
