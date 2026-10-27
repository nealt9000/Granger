package app.granger.core.library

import app.granger.core.books.XhtmlText
import app.granger.core.research.DenseFinder
import app.granger.core.research.HtmlPassages
import app.granger.core.research.Passage
import app.granger.core.research.SourceKind
import app.granger.core.research.SourceMeta
import app.granger.core.text.Chunkers
import app.granger.core.vectors.VectorFile
import app.granger.core.vectors.VectorHit
import app.granger.core.vectors.VectorSearch
import app.granger.core.zim.ZimArchive
import java.io.IOException
import java.util.concurrent.ExecutorService
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.ZipFile
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.jsoup.parser.Parser

/**
 * A vector file on this phone and what it belongs to: a pack's ZIM `Name` ([kind] ARTICLE; any flavour of the pack, since
 * `_maxi` and `_nopic` share their text) or a book's id ([kind] BOOK).
 */
data class VectorSource(val file: VectorFile, val kind: SourceKind, val target: String)

fun interface VectorSources {
    suspend fun current(): List<VectorSource>
}

/** One [DensePassageFinder.find]'s time in milliseconds: listing the vector files, the scan, and rebuilding the hits. */
data class DenseTimings(val sourcesMs: Long, val scanMs: Long, val rebuildMs: Long, val rows: Long, val files: Int)

/**
 * Finds passages by meaning: the [k] best vectors over every installed pack and book that has a vector file, turned back
 * into [Passage]s by re-chunking their article or chapter, just as [LibraryPassageFinder] builds them. A rebuilt chunk
 * whose text no longer hashes to the vector's (another snapshot, a chunker change) is dropped and counted in [mismatches].
 */
class DensePassageFinder(
    private val library: Library,
    private val bookshelf: Bookshelf,
    private val shelves: Shelves,
    private val sources: VectorSources,
    private val pool: ExecutorService,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val k: Int = 24,
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
) : DenseFinder {
    val mismatches = AtomicInteger()

    /** The last [find]'s timings (Plan R5 Task 1); null before the first, or when it found no usable vector file. */
    @Volatile var lastTimings: DenseTimings? = null
        private set

    override suspend fun find(query: FloatArray): List<Passage> = withContext(io) {
        val start = clock()
        lastTimings = null
        val packs = library.packs.first().filter { it.status == PackStatus.OK && it.name.isNotBlank() }
            .groupBy { it.name }.mapValues { (_, same) -> same.maxBy { it.date } }
        val books = bookshelf.books.first().filter { it.status == PackStatus.OK }.associateBy { it.id }
        val usable = sources.current().filter { s ->
            when (s.kind) {
                SourceKind.ARTICLE -> s.target in packs
                SourceKind.BOOK -> s.target in books
                else -> false
            }
        }
        if (usable.isEmpty()) return@withContext emptyList()
        val byFile = usable.associateBy { it.file }
        val dims = usable.groupBy { it.file.header.dims }.maxBy { it.value.size }.key
        val searched = usable.map { it.file }.filter { it.header.dims == dims }
        val listed = clock()
        val hits = VectorSearch.top(query, searched, k, pool)
        val scanned = clock()
        val collections = shelves.collectionNames()
        val rebuilt = HashMap<VectorHit, Passage>()
        hits.groupBy { byFile.getValue(it.file) }.forEach { (source, sourceHits) ->
            try {
                when (source.kind) {
                    SourceKind.ARTICLE -> {
                        val pack = packs.getValue(source.target)
                        val archive = library.archive(pack.id) ?: return@forEach
                        articles(pack, archive, sourceHits, collections[pack.id].orEmpty(), rebuilt)
                    }
                    else -> chapters(books.getValue(source.target), sourceHits, collections, rebuilt)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // One damaged source drops only its own passages.
            }
        }
        lastTimings = DenseTimings(listed - start, scanned - listed, clock() - scanned, searched.sumOf { it.header.count.toLong() }, searched.size)
        hits.mapNotNull { rebuilt[it] }
    }

    private fun articles(pack: Pack, archive: ZimArchive, hits: List<VectorHit>, collection: String, out: MutableMap<VectorHit, Passage>) {
        hits.groupBy { it.file.key(it.row).key }.forEach { (path, same) ->
            val item = archive.read(path) ?: return@forEach
            if (!item.mimeType.startsWith("text/html") && !item.mimeType.startsWith("application/xhtml")) return@forEach
            val html = item.bytes.toString(Charsets.UTF_8)
            val parts = HtmlPassages.split(html)
            val title = TITLE.find(html)?.groupValues?.get(1)?.let { Parser.unescapeEntities(it, false).trim() }.orEmpty()
            val location = archive.resolvePath(path) ?: path
            for (hit in same) {
                val part = parts.getOrNull(hit.file.key(hit.row).ordinal)
                if (part == null || Chunkers.hash(part.text) != hit.file.hash(hit.row)) {
                    mismatches.incrementAndGet()
                    continue
                }
                out[hit] = Passage(
                    kind = SourceKind.ARTICLE,
                    sourceId = pack.id,
                    sourceTitle = title.ifEmpty { part.heading },
                    packTitle = pack.title,
                    location = location,
                    anchor = part.anchor,
                    heading = part.heading,
                    text = part.text,
                    meta = SourceMeta(date = pack.date, collection = collection),
                    cosine = hit.score.toDouble(),
                )
            }
        }
    }

    private fun chapters(book: Book, hits: List<VectorHit>, collections: Map<String, String>, out: MutableMap<VectorHit, Passage>) {
        val zip = try { ZipFile(book.filePath) } catch (_: IOException) { return }
        zip.use {
            hits.groupBy { it.file.key(it.row).key }.forEach { (href, same) ->
                val chunks = XhtmlText.chunks(zip, href)
                for (hit in same) {
                    val chunk = chunks.getOrNull(hit.file.key(hit.row).ordinal)
                    if (chunk == null || Chunkers.hash(chunk.text) != hit.file.hash(hit.row)) {
                        mismatches.incrementAndGet()
                        continue
                    }
                    out[hit] = Passage(
                        kind = SourceKind.BOOK,
                        sourceId = book.id,
                        sourceTitle = book.title,
                        location = href,
                        progression = chunk.progression,
                        text = chunk.text,
                        meta = SourceMeta(book.author, book.date, book.subjects, collections[book.id].orEmpty()),
                        cosine = hit.score.toDouble(),
                    )
                }
            }
        }
    }

    private companion object {
        val TITLE = Regex("<title[^>]*>(.*?)</title>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    }
}
