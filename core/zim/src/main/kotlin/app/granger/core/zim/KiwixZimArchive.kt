package app.granger.core.zim

import org.kiwix.libzim.Archive
import org.kiwix.libzim.Query
import org.kiwix.libzim.Searcher
import org.kiwix.libzim.SuggestionSearcher
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

    override fun read(path: String): ZimItem? = reading(null) {
        if (!archive.hasEntryByPath(path)) return@reading null
        val item = archive.getEntryByPath(path).getItem(true)
        ZimItem(item.path, item.mimetype, item.data.data)
    }

    override fun suggest(query: String, limit: Int): List<ZimHit> = reading(emptyList()) {
        if (query.isBlank()) return@reading emptyList()
        synchronized(searchLock) {
            val suggester = suggester ?: SuggestionSearcher(archive).also { suggester = it }
            val results = suggester.suggest(query.trim()).getResults(0, limit)
            buildList {
                while (results.hasNext()) {
                    val item = results.next()
                    add(ZimHit(item.path, item.title))
                }
            }
        }
    }

    override fun search(query: String, limit: Int): List<ZimHit> = reading(emptyList()) {
        if (!hasFulltextIndex || query.isBlank()) return@reading emptyList()
        synchronized(searchLock) {
            val searcher = searcher ?: Searcher(archive).also { searcher = it }
            val results = searcher.search(Query(query.trim())).getResults(0, limit)
            buildList {
                while (results.hasNext()) {
                    // libkiwix's getters describe the entry the *next* call to next() returns,
                    // so read them before advancing.
                    val hit = ZimHit(
                        path = results.path,
                        title = results.title,
                        snippet = results.snippet?.takeIf { it.isNotBlank() },
                        score = results.score,
                    )
                    results.next()
                    add(hit)
                }
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
