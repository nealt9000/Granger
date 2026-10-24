package app.granger.library

import android.net.Uri
import app.granger.FakeBookshelf
import app.granger.core.books.DrmKind
import app.granger.core.library.Bookshelf
import app.granger.core.library.ImportResult
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.InputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ImportRunnerTest {
    /** Answers each imported display name from [results]; a Throwable in the map is thrown instead. */
    private class ScriptedShelf(private val results: Map<String, Any>) : Bookshelf by FakeBookshelf() {
        val seen = mutableListOf<String>()
        override suspend fun import(displayName: String, open: () -> InputStream): ImportResult {
            seen += displayName
            open().close()
            return when (val r = results.getValue(displayName)) {
                is Throwable -> throw r
                else -> r as ImportResult
            }
        }
    }

    private fun uri(name: String) = Uri.parse("content://docs/$name")
    private fun runner(shelf: Bookshelf, open: (Uri) -> InputStream? = { ByteArrayInputStream(ByteArray(1)) }) =
        ImportRunner(shelf, open) { it.lastPathSegment.orEmpty() }

    @Test fun oneBadFileDoesNotStopTheOthers() = runTest {
        val shelf = ScriptedShelf(
            mapOf(
                "a.epub" to ImportResult.Imported("1"),
                "b.epub" to IllegalStateException("boom"),
                "c.epub" to ImportResult.Imported("3"),
            ),
        )
        val summary = runner(shelf).run(listOf(uri("a.epub"), uri("b.epub"), uri("c.epub")))
        assertThat(shelf.seen).containsExactly("a.epub", "b.epub", "c.epub").inOrder()
        assertThat(summary.imported).isEqualTo(2)
        assertThat(summary.problems).containsExactly("b.epub is damaged or isn't a complete e-book.")
    }

    @Test fun aPdfImportsAndAnEncryptedOneSaysWhy() = runTest {
        val shelf = ScriptedShelf(
            mapOf(
                "water.pdf" to ImportResult.Imported("1"),
                "locked.pdf" to ImportResult.Protected(DrmKind.PDF_ENCRYPTED),
                "book.epub" to ImportResult.Imported("3"),
            ),
        )
        val summary = runner(shelf).run(listOf("water.pdf", "locked.pdf", "book.epub").map(::uri))
        assertThat(summary.imported).isEqualTo(2)
        assertThat(summary.problems).containsExactly(
            "locked.pdf is password-protected or encrypted. Granger can't open it and won't remove the protection.",
        )
    }

    @Test fun summaryCountsAndNamesProblems() = runTest {
        val shelf = ScriptedShelf(
            mapOf(
                "a.epub" to ImportResult.Imported("1"),
                "b.epub" to ImportResult.Imported("2"),
                "c.epub" to ImportResult.AlreadyThere("3"),
                "Book.epub" to ImportResult.Protected(DrmKind.entries.first()),
            ),
        )
        val summary = runner(shelf).run(listOf("a.epub", "b.epub", "c.epub", "Book.epub").map(::uri))
        assertThat(summary.imported).isEqualTo(2)
        assertThat(summary.alreadyThere).isEqualTo(1)
        assertThat(summary.text()).isEqualTo(
            "Imported 2 books. 1 was already in My books. " +
                "Book.epub is copy-protected (DRM). Granger can't open it and won't remove the protection.",
        )
    }

    @Test fun textForTheSimpleCases() {
        assertThat(ImportSummary(3, 0, emptyList()).text()).isEqualTo("Imported 3 books.")
        assertThat(ImportSummary(1, 0, emptyList()).text()).isEqualTo("Imported 1 book.")
        assertThat(ImportSummary(0, 2, emptyList()).text()).isEqualTo("2 were already in My books.")
    }

    @Test fun unreadableUriIsAProblemNotACrash() = runTest {
        val shelf = ScriptedShelf(mapOf("x.epub" to ImportResult.Imported("1"), "ok.epub" to ImportResult.Imported("2")))
        val summary = runner(shelf) { uri ->
            when (uri.lastPathSegment) {
                "x.epub" -> null
                else -> ByteArrayInputStream(ByteArray(1))
            }
        }.run(listOf(uri("x.epub"), uri("ok.epub")))
        assertThat(summary.imported).isEqualTo(1)
        assertThat(summary.problems).hasSize(1)
        assertThat(summary.problems.single()).startsWith("x.epub")
    }

    @Test fun aSecurityExceptionOnOpenIsAProblemToo() = runTest {
        val shelf = ScriptedShelf(mapOf("x.epub" to ImportResult.Imported("1")))
        val summary = runner(shelf) { throw SecurityException("no permission") }.run(listOf(uri("x.epub")))
        assertThat(summary.imported).isEqualTo(0)
        assertThat(summary.problems).hasSize(1)
    }
}
