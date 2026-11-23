package app.granger.core.library

import app.granger.core.books.PdfText
import app.granger.core.books.TextChunk
import app.granger.core.books.XhtmlText
import app.granger.core.books.openBookZip
import app.granger.core.research.DenseFinder
import app.granger.core.research.HtmlPassages
import app.granger.core.research.Passage
import app.granger.core.research.ResearchLanguage
import app.granger.core.research.SourceKind
import app.granger.core.research.SourceMeta
import app.granger.core.text.Chunkers
import app.granger.core.vectors.VectorFile
import app.granger.core.vectors.VectorHit
import app.granger.core.vectors.VectorSearch
import app.granger.core.zim.ZimArchive
import kotlin.concurrent.Volatile
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

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

/** A count several threads add to (Android's `AtomicInteger` before Plan I7). */
class Counter {
    private val lock = TryLock()
    private var value = 0

    fun get(): Int = locked { value }

    fun incrementAndGet(): Int = locked { ++value }

    private inline fun <T> locked(block: () -> T): T {
        lock.lock()
        try {
            return block()
        } finally {
            lock.unlock()
        }
    }
}

/** The vector scan: the [k] best rows of [files] for a query, reading [probes] lists of a version 2 file. */
fun interface VectorScan {
    suspend fun top(query: FloatArray, files: List<VectorFile>, k: Int, probes: Int): List<VectorHit>
}

/**
 * Finds passages by meaning: the [k] best vectors over every installed pack and book that has a vector file, turned back
 * into [Passage]s by re-chunking their article or chapter, just as [PackPassageFinder] builds them. A rebuilt chunk
 * whose text no longer hashes to the vector's (another snapshot, a chunker change) is dropped and counted in [mismatches].
 *
 * Shared by both phones (Plan I7 Task 7a; Android's until then): Android scans on its thread pool and unescapes titles
 * with jsoup (`core:library`'s `DensePassageFinder(…, pool, …)`); the iPhone scans on [io].
 *
 * @param unescape a page title's HTML entities decoded.
 */
class DensePassageFinder(
    private val library: Library,
    private val bookshelf: Bookshelf,
    private val shelves: Shelves,
    private val sources: VectorSources,
    private val unescape: (String) -> String,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val k: Int = 24,
    /** Lists read from version 2 vector files (Plan SQ Task 2.2: 1,024 of ~250 rows kept 96% of brute force offline). */
    private val probes: Int = PROBES,
    private val clock: () -> Long = monotonicMillis(),
    /** The sets the packs are slices of, so a smaller slice's articles don't double a bigger one's (Plan WF Decision 6). */
    private val slices: PackSlices? = null,
    /** The scan; null scans on [io]. */
    private val scan: VectorScan? = null,
) : DenseFinder {
    val mismatches = Counter()

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
        // Version 2 files are read by their nearest lists (Plan SQ Task 2.2); version 1 files whole, as before.
        val hits = scan?.top(query, searched, k, probes) ?: VectorSearch.top(query, searched, k, io, probes = probes)
        val scanned = clock()
        val collections = shelves.collectionNames()
        // A few sources' articles at a time (Plan SQ, the Pixel 6: re-reading and re-chunking up to 24 hits' articles one
        // after another took 1.1–2.9 s, twice the scan): each article is a read and a parse, on its own archive's reader.
        val gate = Semaphore(PARALLEL_REBUILDS)
        val rebuilt = coroutineScope {
            hits.groupBy { byFile.getValue(it.file) }.flatMap { (source, sourceHits) ->
                sourceHits.groupBy { it.file.key(it.row).key }.values.map { same -> source to same }
            }.map { (source, same) ->
                async {
                    gate.withPermit {
                        try {
                            when (source.kind) {
                                SourceKind.ARTICLE -> {
                                    val pack = packs.getValue(source.target)
                                    library.archive(pack.id)?.let { articles(pack, it, same, collections[pack.id].orEmpty()) }.orEmpty()
                                }
                                else -> chapters(books.getValue(source.target), same, collections)
                            }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (_: Exception) {
                            // One damaged source drops only its own passages.
                            emptyList()
                        }
                    }
                }
            }.awaitAll().flatten().toMap()
        }
        lastTimings = DenseTimings(listed - start, scanned - listed, clock() - scanned, searched.sumOf { it.header.count.toLong() }, searched.size)
        SliceDedupe.apply(
            hits.mapNotNull { rebuilt[it] }, packs.values.toList(), slices, library::archive,
            { p -> p.sourceId.takeIf { p.kind == SourceKind.ARTICLE } }, { it.location },
        )
    }

    private fun articles(pack: Pack, archive: ZimArchive, hits: List<VectorHit>, collection: String): List<Pair<VectorHit, Passage>> {
        val out = ArrayList<Pair<VectorHit, Passage>>()
        hits.groupBy { it.file.key(it.row).key }.forEach { (path, same) ->
            val item = archive.read(path) ?: return@forEach
            if (!item.mimeType.startsWith("text/html") && !item.mimeType.startsWith("application/xhtml")) return@forEach
            val html = item.bytes.decodeToString()
            val parts = HtmlPassages.split(html)
            val title = TITLE.find(html)?.groupValues?.get(1)?.let { unescape(it).trim() }.orEmpty()
            val location = archive.resolvePath(path) ?: path
            for (hit in same) {
                val part = parts.getOrNull(hit.file.key(hit.row).ordinal)
                if (part == null || Chunkers.hash(part.text) != hit.file.hash(hit.row)) {
                    mismatches.incrementAndGet()
                    continue
                }
                out += hit to Passage(
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
                    language = ResearchLanguage.ofCode(pack.language),
                )
            }
        }
        return out
    }

    private fun chapters(book: Book, hits: List<VectorHit>, collections: Map<String, String>): List<Pair<VectorHit, Passage>> {
        val out = ArrayList<Pair<VectorHit, Passage>>()
        // A PDF's chunks are its pages' (Plan R2 Task 9: vectors made on the phone for the PDFs you add).
        if (book.format == BookFormat.PDF) {
            PdfText.open(book.filePath)?.use { reader ->
                hits.groupBy { it.file.key(it.row).key }.forEach { (href, same) -> rebuild(book, reader.chunks(href), same, collections, out) }
            }
            return out
        }
        val zip = try { openBookZip(book.filePath) } catch (e: CancellationException) { throw e } catch (_: Exception) { return out }
        zip.use {
            hits.groupBy { it.file.key(it.row).key }.forEach { (href, same) -> rebuild(book, XhtmlText.chunks(zip, href), same, collections, out) }
        }
        return out
    }

    private fun rebuild(book: Book, chunks: List<TextChunk>, same: List<VectorHit>, collections: Map<String, String>, out: MutableList<Pair<VectorHit, Passage>>) {
        for (hit in same) {
            val chunk = chunks.getOrNull(hit.file.key(hit.row).ordinal)
            if (chunk == null || Chunkers.hash(chunk.text) != hit.file.hash(hit.row)) {
                mismatches.incrementAndGet()
                continue
            }
            out += hit to Passage(
                kind = SourceKind.BOOK,
                sourceId = book.id,
                sourceTitle = book.title,
                location = chunk.href,
                heading = if (book.format == BookFormat.PDF) PdfText.pageOf(chunk.href)?.let { n -> "p. $n" }.orEmpty() else "",
                progression = chunk.progression,
                text = chunk.text,
                meta = SourceMeta(book.author, book.date, book.subjects, collections[book.id].orEmpty()),
                cosine = hit.score.toDouble(),
                language = ResearchLanguage.ofCode(book.language),
            )
        }
    }

    companion object {
        const val PROBES = 1024
        /** Articles or chapters rebuilt at once. */
        const val PARALLEL_REBUILDS = 4
        private val TITLE = Regex("<title[^>]*>(.*?)</title>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    }
}

/** A vector file and what it belongs to, before it is opened; see [VectorSource]. */
data class ListedVectors(val path: String, val kind: SourceKind, val target: String)

/**
 * The vector files [listing] names (Plan R1 Task 10: the installed vectors downloads). A file is opened once and kept open
 * while its size and date stay the same; a missing, damaged or unlisted one is skipped, and so is one cut by another
 * chunker or embedded by another embedder than [embedder]'s sha256 (null when it isn't known: then any is used).
 */
class ListedVectorSources(
    private val listing: suspend () -> List<ListedVectors>,
    private val embedder: suspend () -> String? = { null },
) : VectorSources {
    private val cache = VectorFileCache()

    override suspend fun current(): List<VectorSource> {
        val listed = listing()
        val sha = embedder()
        return cache.open(listed).filter { sha == null || it.file.header.embedderSha256 == sha }
    }
}

/** Every source of [parts], in order; a target listed twice keeps its first file (the catalog's over a pushed one). */
class CombinedVectorSources(private vararg val parts: VectorSources) : VectorSources {
    override suspend fun current(): List<VectorSource> {
        val seen = HashSet<Pair<SourceKind, String>>()
        return parts.flatMap { it.current() }.filter { seen.add(it.kind to it.target) }
    }
}

/** The open files behind a listing, each kept open while its size and date stay the same. */
class VectorFileCache {
    private class Opened(val length: Long, val modified: Long, val file: VectorFile)

    private val open = HashMap<String, Opened>()
    private val lock = TryLock()

    fun open(listed: List<ListedVectors>): List<VectorSource> {
        lock.lock()
        try {
            val paths = listed.map { it.path }.toSet()
            // Files gone from the listing are closed.
            open.keys.filter { it !in paths }.forEach { open.remove(it)?.file?.close() }
            return listed.mapNotNull { (f, kind, target) ->
                if (!Disk.isFile(f)) return@mapNotNull null
                val cached = open[f]
                val length = Disk.length(f)
                val modified = Disk.lastModified(f)
                val file = if (cached != null && cached.length == length && cached.modified == modified) {
                    cached.file
                } else {
                    cached?.file?.close()
                    val opened = VectorFile.open(f) ?: return@mapNotNull null.also { open.remove(f) }
                    open[f] = Opened(length, modified, opened)
                    opened
                }
                // Chunks cut another way can't be rebuilt into passages.
                if (file.header.chunkerVersion != Chunkers.VERSION) null else VectorSource(file, kind, target)
            }
        } finally {
            lock.unlock()
        }
    }
}
