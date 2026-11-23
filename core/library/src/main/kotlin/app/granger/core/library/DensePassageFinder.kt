package app.granger.core.library

import app.granger.core.vectors.VectorSearch
import app.granger.core.vectors.top
import java.util.concurrent.ExecutorService
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import org.jsoup.parser.Parser

/**
 * Android's [DensePassageFinder] (shared since Plan I7 Task 7a), as before: the scan's tasks on [pool], page titles
 * unescaped by jsoup.
 */
fun DensePassageFinder(
    library: Library,
    bookshelf: Bookshelf,
    shelves: Shelves,
    sources: VectorSources,
    pool: ExecutorService,
    io: CoroutineDispatcher = Dispatchers.IO,
    k: Int = 24,
    probes: Int = DensePassageFinder.PROBES,
    clock: () -> Long = { System.nanoTime() / 1_000_000 },
    slices: PackSlices? = null,
): DensePassageFinder = DensePassageFinder(
    library, bookshelf, shelves, sources,
    unescape = { Parser.unescapeEntities(it, false) },
    io = io, k = k, probes = probes, clock = clock, slices = slices,
    scan = { query, files, n, p -> VectorSearch.top(query, files, n, pool, probes = p) },
)
