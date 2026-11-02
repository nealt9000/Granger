package app.granger.library

import android.net.Uri
import app.granger.R
import app.granger.UiText
import app.granger.core.library.Bookshelf
import app.granger.core.books.DrmKind
import app.granger.core.library.ImportResult
import kotlinx.coroutines.CancellationException
import java.io.IOException
import java.io.InputStream

/** What a batch of imports did. [problems] are the user-facing messages, one per file that couldn't be added. */
data class ImportSummary(val imported: Int, val alreadyThere: Int, val problems: List<UiText>) {
    fun text(): UiText {
        val sentences = buildList {
            if (imported > 0) add(UiText.Plural(R.plurals.library_imported, imported, imported))
            if (alreadyThere > 0) add(UiText.Plural(R.plurals.library_already_there, alreadyThere, alreadyThere))
            addAll(problems)
        }
        if (sentences.isEmpty()) return UiText.Res(R.string.library_nothing_imported)
        return sentences.drop(1).fold(sentences.first()) { text, next -> UiText.Res(R.string.library_sentences, text, next) }
    }
}

/** The message to show about the file called [name] (a [UiText] when the name is the app's own); null when nothing went wrong. */
fun importProblem(result: ImportResult, name: Any): UiText? = when (result) {
    is ImportResult.Imported, is ImportResult.AlreadyThere -> null
    is ImportResult.Protected ->
        UiText.Res(if (result.kind == DrmKind.PDF_ENCRYPTED) R.string.library_import_encrypted else R.string.library_import_drm, name)
    ImportResult.Unsupported -> UiText.Res(R.string.library_import_unsupported, name)
    is ImportResult.Damaged -> UiText.Res(R.string.library_import_damaged, name)
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
        val problems = mutableListOf<UiText>()
        for (uri in uris) {
            val name = try {
                nameOf(uri).ifBlank { null }
            } catch (e: Exception) {
                null
            } ?: uri.lastPathSegment
            val shown: Any = name ?: UiText.Res(R.string.library_a_file)
            try {
                // The importer's file name; a book's title comes from its own metadata.
                val result = bookshelf.import(name ?: "A file") { open(uri) ?: throw IOException("Couldn't open $name") }
                when (result) {
                    is ImportResult.Imported -> imported++
                    is ImportResult.AlreadyThere -> alreadyThere++
                    else -> importProblem(result, shown)?.let(problems::add)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                importProblem(ImportResult.Damaged(e.message.orEmpty()), shown)?.let(problems::add)
            }
        }
        if (imported > 0) onImported()
        return ImportSummary(imported, alreadyThere, problems)
    }
}
