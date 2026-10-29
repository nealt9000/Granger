package app.granger.library

import android.net.Uri
import app.granger.FakeBookshelf
import app.granger.FakeLibrary
import app.granger.core.library.Bookshelf
import app.granger.core.library.ImportResult
import app.granger.FakeShelves
import app.granger.MainDispatcherRule
import app.granger.book
import app.granger.pack
import app.granger.player.audiobook
import app.granger.core.library.AlbumSummary
import app.granger.core.library.CollectionRow
import app.granger.core.library.MediaType
import app.granger.core.library.PackStatus
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

    @Test fun refreshRescansTheLibrary() = runTest(main.dispatcher) {
        val vm = vm()
        vm.refresh()
        vm.refresh()
        testScheduler.advanceUntilIdle()
        assertThat(library.refreshes).isEqualTo(2)
    }

    /** Plan U3c Decision 2: the Library is books; the Kiwix folder, pack collections and Maps are Browse's. */
    @Test fun theLibraryIsBooksWithNoReferenceOrMaps() = runTest(main.dispatcher) {
        val folder = CollectionRow(
            ShelfCollection("c:kiwix", "Kiwix", "literature", userCreated = false, defaultKey = "kiwix"), emptyList(),
            children = shelf("Literature", "Wikipedia" to 3).collections,
        )
        val classics = bookRow("Granger Classics", 2)
        val mine = bookRow("My books", 1)
        val granger = shelf("Literature", "Granger packs" to 1).collections.single()
        shelves.shelfList.value = listOf(
            shelf("Music", "Phone" to 1),
            shelf("Literature").copy(collections = listOf(classics, granger, folder, mine)),
            shelf("Maps", "OpenStreetMap" to 1),
            shelf("Audiobooks", "LibriVox" to 1),
        )
        val ui = vm().ui.filterNotNull().first()
        // Music and Audiobooks are Listen's (Plan U2 Decision 4); packs and maps, Browse's.
        assertThat(ui.kinds.map { it.title }).containsExactly("Books")
        assertThat(ui.kinds.single().collections.map { it.collection.name }).containsExactly("Granger Classics", "My books").inOrder()
        assertThat(ui.kinds.single().id).isEqualTo(BOOKS_KIND)
        assertThat(ui.kinds.single().mediaTypeId).isEqualTo("literature")
        assertThat(ui.emptyLibrary).isFalse()
    }

    @Test fun packsAloneLeaveTheLibraryEmpty() = runTest(main.dispatcher) {
        val folder = CollectionRow(
            ShelfCollection("c:kiwix", "Kiwix", "literature", userCreated = false, defaultKey = "kiwix"), emptyList(),
            children = shelf("Literature", "Wikipedia" to 3, "DevDocs" to 2).collections,
        )
        shelves.shelfList.value = listOf(shelf("Literature").copy(collections = listOf(folder)), shelf("Maps", "OpenStreetMap" to 1))
        val ui = vm().ui.filterNotNull().first()
        assertThat(ui.kinds).isEmpty()
        assertThat(ui.emptyLibrary).isTrue()
    }

    /** A collection the user made with a pack and a book in it stays: its book is the Library's, its pack Browse's too. */
    @Test fun aCollectionHoldingBooksAndPacksStays() {
        val both = CollectionRow(
            ShelfCollection("c:mine", "Field kit", "literature", userCreated = true, defaultKey = null), listOf(pack("w")),
            books = listOf(book("b")),
        )
        val packsOnly = CollectionRow(ShelfCollection("c:ref", "My reference", "literature", userCreated = true, defaultKey = null), listOf(pack("m")))
        val kinds = libraryKinds(listOf(shelf("Literature").copy(collections = listOf(both, packsOnly))))
        assertThat(kinds.single().collections.map { it.collection.name }).containsExactly("Field kit")
        assertThat(rowCountText(kinds.single().collections.single())).isEqualTo("1 pack · 1 book")
    }

    @Test fun anotherMediaTypeWithBooksComesAfterBooks() {
        val video = Shelf(MediaType("video", "Video", builtIn = false, sortOrder = 5), listOf(bookRow("Lectures", 1)))
        val kinds = libraryKinds(listOf(video, shelf("Literature").copy(collections = listOf(bookRow("Classics", 1))), shelf("Maps", "OpenStreetMap" to 1)))
        assertThat(kinds.map { it.title }).containsExactly("Books", "Video").inOrder()
        // A type holding only packs (Neal's "Reference") is Browse's.
        assertThat(libraryKinds(listOf(shelf("Reference", "Medicine" to 2)))).isEmpty()
    }

    private fun bookRow(name: String, count: Int) = CollectionRow(
        ShelfCollection("c:$name", name, "literature", userCreated = false, defaultKey = name), emptyList(),
        books = List(count) { book("$name-$it") },
    )

    @Test fun theKiwixFolderCountsItsChildren() {
        val folder = CollectionRow(
            ShelfCollection("c:kiwix", "Kiwix", "literature", userCreated = false, defaultKey = "kiwix"), emptyList(),
            children = shelf("Literature", "Wikipedia" to 3, "DevDocs" to 2).collections,
        )
        assertThat(folder.isFolder).isTrue()
        assertThat(rowCountText(folder)).isEqualTo("5 packs")
        assertThat(rowCountText(bookRow("Granger Classics", 1))).isEqualTo("1 book")
    }

    @Test fun noAudioKindsAndMusicAloneLeavesTheLibraryEmpty() = runTest(main.dispatcher) {
        shelves.shelfList.value = listOf(shelf("Music", "Phone" to 1), shelf("Audiobooks", "LibriVox" to 1))
        val ui = vm().ui.filterNotNull().first()
        assertThat(ui.kinds).isEmpty()
        assertThat(ui.emptyLibrary).isTrue()
        val video = Shelf(MediaType("video", "Video", builtIn = false, sortOrder = 5), listOf(bookRow("Lectures", 1)))
        assertThat(libraryKinds(listOf(shelf("Music", "Phone" to 1), video)).map { it.id }).containsExactly("video")
    }

    /** An audio shelf: each Music collection holds an album, each Audiobooks one an audiobook, with [status]. */
    private fun audio(type: String, vararg names: String, status: PackStatus = PackStatus.OK) = Shelf(
        MediaType(type.lowercase(), type, builtIn = true, sortOrder = 0),
        names.map { name ->
            val collection = ShelfCollection("c:$name", name, type.lowercase(), userCreated = false, defaultKey = name)
            if (type == "Music") CollectionRow(collection, emptyList(), albums = listOf(AlbumSummary(name, name, "Artist", "", "", 1, 0L, null, status)))
            else CollectionRow(collection, emptyList(), audiobooks = listOf(audiobook(name).copy(status = status)))
        },
    )

    @Test fun listenHasMusicThenAudiobooksEachOnlyWithSomething() {
        val shelves = listOf(audio("Audiobooks", "LibriVox"), shelf("Literature", "Wikipedia" to 1), audio("Music", "Phone", "Archive.org"))
        assertThat(listenKinds(shelves).map { it.title }).containsExactly("Music", "Audiobooks").inOrder()
        assertThat(listenKinds(shelves).map { it.mediaTypeId }).containsExactly("music", "audiobooks").inOrder()
        assertThat(listenKinds(listOf(audio("Audiobooks", "LibriVox"), audio("Music"))).map { it.id }).containsExactly("audiobooks")
        assertThat(listenKinds(listOf(shelf("Literature", "Wikipedia" to 1)))).isEmpty()
        // Plan U2 Decision 7: only an audiobook that plays makes the shelf.
        assertThat(listenKinds(listOf(audio("Audiobooks", "LibriVox", status = PackStatus.MISSING), audio("Music", "Phone"))).map { it.id })
            .containsExactly("music")
    }

    @Test fun emptyLibraryIsFlagged() = runTest(main.dispatcher) {
        val ui = vm().ui.filterNotNull().first()
        assertThat(ui.emptyLibrary).isTrue()
        assertThat(ui.kinds).isEmpty()
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
