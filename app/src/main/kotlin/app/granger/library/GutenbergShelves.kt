package app.granger.library

import app.granger.core.library.Book
import java.io.File
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers

/** "Make all searchable…" on Android (the shared [GutenbergShelf], Plan I5): the copies go to [dir], a `File`. */
fun GutenbergShelf(
    restore: suspend (Book) -> Boolean,
    indexPending: suspend () -> Unit,
    dir: () -> File,
    scope: CoroutineScope,
    io: CoroutineDispatcher = Dispatchers.IO,
    batch: Int = GutenbergShelf.BATCH,
): GutenbergShelf = GutenbergShelf(restore, indexPending, { dir().path }, { File(it).usableSpace }, scope, io, batch)
