package app.granger.core.library

import java.io.File
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/** Android's [BookVectors] (shared since Plan I7 Task 7a), over a [File] folder as before. */
fun BookVectors(
    bookshelf: Bookshelf,
    dir: File,
    embed: suspend (List<Pair<String, String>>) -> List<FloatArray>?,
    embedderSha: suspend () -> String?,
    io: CoroutineDispatcher = Dispatchers.IO,
    batch: Int = 8,
    dims: Int = BookVectors.DIMS,
    origins: Set<BookOrigin> = setOf(BookOrigin.IMPORT),
    clock: () -> Long = { System.nanoTime() / 1_000_000 },
    log: (String) -> Unit = {},
): BookVectors = BookVectors(bookshelf, dir.path, embed, embedderSha, io, batch, dims, origins, clock, log)
