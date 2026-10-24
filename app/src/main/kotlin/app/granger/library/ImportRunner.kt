package app.granger.library

import android.net.Uri
import app.granger.core.library.Bookshelf
import app.granger.core.library.ImportResult
import kotlinx.coroutines.CancellationException
import java.io.IOException
import java.io.InputStream

/** What a batch of imports did. [problems] are the user-facing messages, one per file that couldn't be added. */
data class ImportSummary(val imported: Int, val alreadyThere: Int, val problems: List<String>) {
    fun text(): String = buildList {
        if (imported > 0) add("Imported $imported ${if (imported == 1) "book" else "books"}.")
        if (alreadyThere > 0) add("$alreadyThere ${if (alreadyThere == 1) "was" else "were"} already in My books.")
        addAll(problems)
    }.joinToString(" ").ifEmpty { "Nothing was imported." }
}

/**
 * Imports the files a user picked, one at a time. Whatever goes wrong with a file (an unreadable URI, a revoked
 * permission, a book the importer chokes on) is a problem line for that file; the rest still import. [onImported] runs
 * once after a batch that added a book, so its text is indexed now rather than at the next launch.
 */
class ImportRunner(
    private val bookshelf: Bookshelf,
    private val open: (Uri) -> InputStream?,
    private val nameOf: (Uri) -> String,
    private val onImported: () -> Unit = {},
) {
    suspend fun run(uris: List<Uri>): ImportSummary {
        var imported = 0
        var alreadyThere = 0
        val problems = mutableListOf<String>()
        for (uri in uris) {
            val name = try {
                nameOf(uri).ifBlank { null }
            } catch (e: Exception) {
                null
            } ?: uri.lastPathSegment ?: "A file"
            try {
                val result = bookshelf.import(name) { open(uri) ?: throw IOException("Couldn't open $name") }
                when (result) {
                    is ImportResult.Imported -> imported++
                    is ImportResult.AlreadyThere -> alreadyThere++
                    else -> result.message(name)?.let(problems::add)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                problems += ImportResult.Damaged(e.message.orEmpty()).message(name).orEmpty()
            }
        }
        if (imported > 0) onImported()
        return ImportSummary(imported, alreadyThere, problems)
    }
}
