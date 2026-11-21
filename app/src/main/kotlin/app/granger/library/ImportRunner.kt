package app.granger.library

import android.net.Uri
import app.granger.core.library.Bookshelf
import java.io.InputStream
import kotlinx.io.asSource
import kotlinx.io.buffered

/**
 * Android's importer of picked books: the shared [FileImports] (Plan I5 Task 2) over content URIs, [open] and [nameOf]
 * being the ContentResolver's.
 */
class ImportRunner(
    bookshelf: Bookshelf,
    private val open: (Uri) -> InputStream?,
    private val nameOf: (Uri) -> String,
    onImported: () -> Unit = {},
) : FileImports(bookshelf, onImported) {
    suspend fun run(uris: List<Uri>): ImportSummary = importAll(picked(uris))

    /** The URIs as the shared importer takes them; a URI's last path segment names it when the resolver can't. */
    fun picked(uris: List<Uri>): List<PickedFile> =
        uris.map { uri -> PickedFile({ nameOf(uri) }, runCatching { uri.lastPathSegment }.getOrNull(), { open(uri)?.asSource()?.buffered() }) }
}
