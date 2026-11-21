package app.granger.library

import app.granger.UiText
import app.granger.core.books.DrmKind
import app.granger.core.library.Bookshelf
import app.granger.core.library.ImportResult
import kotlinx.coroutines.CancellationException
import kotlinx.io.IOException
import kotlinx.io.Source

// Shared since Plan I5 Task 2: importing the books a user picked. Android's `ImportRunner` reads content URIs; the
// iPhone's picker hands over file URLs.

/** What a batch of imports did. [problems] are the user-facing messages, one per file that couldn't be added. */
data class ImportSummary(val imported: Int, val alreadyThere: Int, val problems: List<UiText>) {
    fun text(): UiText {
        val sentences = buildList {
            if (imported > 0) add(UiText.Plural("library_imported", imported, imported))
            if (alreadyThere > 0) add(UiText.Plural("library_already_there", alreadyThere, alreadyThere))
            addAll(problems)
        }
        if (sentences.isEmpty()) return UiText.Res("library_nothing_imported")
        return sentences.drop(1).fold(sentences.first()) { text, next -> UiText.Res("library_sentences", text, next) }
    }
}

/** The message to show about the file called [name] (a [UiText] when the name is the app's own); null when nothing went wrong. */
fun importProblem(result: ImportResult, name: Any): UiText? = when (result) {
    is ImportResult.Imported, is ImportResult.AlreadyThere -> null
    is ImportResult.Protected ->
        if (result.kind == DrmKind.PDF_ENCRYPTED) UiText.Res("library_import_encrypted", name) else UiText.Res("library_import_drm", name)
    ImportResult.Unsupported -> UiText.Res("library_import_unsupported", name)
    is ImportResult.Damaged -> UiText.Res("library_import_damaged", name)
}

/**
 * One file a user picked: its shown [name] (it may throw, or be blank, when the phone can't say), a [fallbackName]
 * then (the last part of its address), and [open] to read it (null when it can't be opened).
 */
class PickedFile(val name: () -> String, val fallbackName: String?, val open: () -> Source?)

/**
 * Imports the files a user picked, one at a time. Whatever goes wrong with a file (an unreadable address, a revoked
 * permission, a book the importer chokes on) is a problem line for that file; the rest still import. [onImported] runs
 * once after a batch that added a book, so its text is indexed now rather than at the next launch.
 */
open class FileImports(
    private val bookshelf: Bookshelf,
    private val onImported: () -> Unit = {},
) {
    suspend fun importAll(files: List<PickedFile>): ImportSummary {
        var imported = 0
        var alreadyThere = 0
        val problems = mutableListOf<UiText>()
        for (file in files) {
            val name = try {
                file.name().ifBlank { null }
            } catch (e: Exception) {
                null
            } ?: file.fallbackName
            val shown: Any = name ?: UiText.Res("library_a_file")
            try {
                // The importer's file name; a book's title comes from its own metadata.
                val result = bookshelf.importFrom(name ?: "A file") { file.open() ?: throw IOException("Couldn't open $name") }
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
