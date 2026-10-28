package app.granger.library

import android.net.Uri
import app.granger.FakeBookshelf
import app.granger.FakeLibrary
import app.granger.core.library.Bookshelf
import app.granger.core.library.ImportResult
import app.granger.FakeShelves
import app.granger.MainDispatcherRule
import app.granger.pack
import app.granger.core.library.CollectionRow
import app.granger.core.library.MediaType
import app.granger.core.library.Shelf
import app.granger.core.library.ShelfCollection
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.InputStream
import kotlinx.coroutines.test.TestScope

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LibraryViewModelTest {
    @get:Rule val main = MainDispatcherRule()
    private val library = FakeLibrary()
    private val shelves = FakeShelves()

    private class ImportingShelf : Bookshelf by FakeBookshelf() {
        override suspend fun import(displayName: String, open: () -> InputStream): ImportResult =
            if (displayName == "bad.epub") ImportResult.Unsupported else ImportResult.Imported(displayName)
    }

    private fun TestScope.vm() =
        LibraryViewModel(shelves, library, ImportRunner(ImportingShelf(), { java.io.ByteArrayInputStream(ByteArray(1)) }, nameOf = { it.lastPathSegment.orEmpty() }), appScope = this)

    private fun shelf(type: String, vararg collections: Pair<String, Int>) = Shelf(
        MediaType(type.lowercase(), type, builtIn = true, sortOrder = 0),
        collections.map { (name, count) ->
            CollectionRow(
                ShelfCollection("c:$name", name, type.lowercase(), userCreated = false, defaultKey = name),
                List(count) { pack("$name-$it") },
            )
        },
    )

    @Test fun sourcesAreGroupedByMediaType() = runTest(main.dispatcher) {
        val vm = vm()
        val ui = vm.ui.filterNotNull().first()
        assertThat(ui.sources).containsExactly(
            SourceGroup("Literature", listOf(SourceLink.GRANGER, SourceLink.KIWIX, SourceLink.ARCHIVE_ORG)),
            SourceGroup("Audiobooks", listOf(SourceLink.LIBRIVOX, SourceLink.ARCHIVE_ORG_SPOKEN)),
            SourceGroup("Music", listOf(SourceLink.ARCHIVE_ORG_MUSIC)),
        ).inOrder()
        // Every audio source is named for what it is: "Archive.org" under each header.
        assertThat(SourceLink.ARCHIVE_ORG_SPOKEN.title).isEqualTo("Archive.org")
        assertThat(SourceLink.ARCHIVE_ORG_MUSIC.title).isEqualTo("Archive.org")
    }

    @Test fun refreshRescansTheLibrary() = runTest(main.dispatcher) {
        val vm = vm()
        vm.refresh()
        vm.refresh()
        testScheduler.advanceUntilIdle()
        assertThat(library.refreshes).isEqualTo(2)
    }

    @Test fun showsShelvesAndSources() = runTest(main.dispatcher) {
        shelves.shelfList.value = listOf(shelf("Literature", "Wikipedia" to 3, "Medicine" to 1))
        val ui = vm().ui.filterNotNull().first()
        assertThat(ui.media.map { it.mediaType.name }).containsExactly("Literature")
        assertThat(ui.media.single().collections.map { it.packs.size }).containsExactly(3, 1).inOrder()
        assertThat(ui.sources.map { it.title }).containsExactly("Literature", "Audiobooks", "Music").inOrder()
        assertThat(ui.emptyLibrary).isFalse()
    }

    @Test fun theKiwixFolderIsOneRowCountingItsChildren() = runTest(main.dispatcher) {
        val kiwix = shelf("Literature", "Wikipedia" to 3, "DevDocs" to 2).collections
        val folder = CollectionRow(
            ShelfCollection("c:kiwix", "Kiwix", "literature", userCreated = false, defaultKey = "kiwix"), emptyList(),
            children = kiwix,
        )
        val classics = shelf("Literature", "Granger Classics" to 1).collections.single()
        shelves.shelfList.value = listOf(shelf("Literature").copy(collections = listOf(classics, folder)))
        val literature = vm().ui.filterNotNull().first().media.single()
        assertThat(literature.collections.map { it.collection.name }).containsExactly("Granger Classics", "Kiwix").inOrder()
        val row = literature.collections[1]
        assertThat(row.isFolder).isTrue()
        assertThat(rowCountText(row)).isEqualTo("5 packs")
        assertThat(rowCountText(classics)).isEqualTo("1 pack")
    }

    @Test fun emptyLibraryIsFlagged() = runTest(main.dispatcher) {
        val ui = vm().ui.filterNotNull().first()
        assertThat(ui.emptyLibrary).isTrue()
        assertThat(ui.sources.flatMap { it.links }).hasSize(SourceLink.entries.size)
    }

    @Test fun importSummaryIsShown() = runTest(main.dispatcher) {
        val vm = vm()
        vm.import(listOf("a.epub", "bad.epub", "b.epub").map { Uri.parse("content://docs/$it") })
        testScheduler.advanceUntilIdle()
        assertThat(vm.notice.value).isEqualTo("Imported 2 books. bad.epub isn't an EPUB, PDF, TXT or Markdown file.")
        vm.dismissNotice()
        assertThat(vm.notice.value).isNull()
    }
}
