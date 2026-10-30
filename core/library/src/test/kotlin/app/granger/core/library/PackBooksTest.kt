package app.granger.core.library

import com.google.common.truth.Truth.assertThat
import java.io.File
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Plan KB Decision 3: the pack says it's a bundle of books, by its scraper, with no catalog and whatever its name. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PackBooksTest {
    @get:Rule val tmp = TemporaryFolder()

    private val db = inMemoryDatabase()
    private val dispatcher = StandardTestDispatcher()
    private val src by lazy { tmp.newFolder("src") }
    private val archives = HashMap<String, FakeZimArchive>()
    private val gutenberg by lazy {
        GutenbergBooks(db, { archives[it] }, { File(tmp.root, "books/gutenberg") }, File(tmp.root, "covers"), dispatcher, { 1L })
    }

    @After fun close() = db.close()

    private fun test(block: suspend TestScope.() -> Unit) = runTest(dispatcher) { block() }

    @Test fun aSideloadedGutenbergPackIsReadByItsScraperWithoutTheCatalog() = test {
        val archive = GutenbergFixtures.archive(src, uuid = "u-side", name = "my-own_gutenberg-shelf")
        archives[archive.uuid] = archive
        val pack = GutenbergFixtures.pack(archive).copy(scraper = "gutenberg2zim-3.0.1")
        db.packDao().upsert(pack)
        assertThat(pack.bookSource).isEqualTo(BookOrigin.GUTENBERG)
        assertThat(Filing.folderKey(pack)).isNull()

        gutenberg.sync(listOf(pack))
        val rows = db.bookDao().byOrigin(BookOrigin.GUTENBERG)
        assertThat(rows).hasSize(GutenbergFixtures.PF_BOOKS.size)
        assertThat(rows.all { it.sourceRef.startsWith("my-own_gutenberg-shelf/") }).isTrue()
        assertThat(rows.map(Filing::defaultKey).distinct()).containsExactly(Filing.gutenbergKey("my-own_gutenberg-shelf"))
    }

    @Test fun aPackWithoutABookScraperIsntRead() = test {
        val archive = GutenbergFixtures.archive(src, uuid = "u-web", name = "some-site_en_all")
        archives[archive.uuid] = archive
        val pack = GutenbergFixtures.pack(archive).copy(scraper = "zimit 3.1.3")
        gutenberg.sync(listOf(pack))
        assertThat(db.bookDao().byOrigin(BookOrigin.GUTENBERG)).isEmpty()
        assertThat(archive.reads).isEmpty()
    }
}
