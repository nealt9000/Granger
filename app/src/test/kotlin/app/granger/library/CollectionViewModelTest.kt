package app.granger.library

import app.granger.FakeBookshelf
import app.granger.FakeDownloads
import app.granger.FakeLibrary
import app.granger.book
import app.granger.core.library.Book
import app.granger.core.library.BookOrigin
import app.granger.core.library.BookPosition
import app.granger.FakeReadingSessions
import app.granger.FakeShelves
import app.granger.MainDispatcherRule
import app.granger.pack
import app.granger.core.library.CollectionRow
import app.granger.core.library.MediaType
import app.granger.core.library.PackStatus
import app.granger.core.library.Shelf
import app.granger.core.library.ShelfCollection
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test

class CollectionViewModelTest {
    @get:Rule val main = MainDispatcherRule()
    private val library = FakeLibrary()
    private val shelves = FakeShelves()
    private val sessions = FakeReadingSessions()
    private val bookshelf = FakeBookshelf()
    private val downloads = FakeDownloads()

    private fun collection(id: String, userCreated: Boolean, vararg packs: app.granger.core.library.Pack, books: List<Book> = emptyList()) =
        CollectionRow(ShelfCollection(id, id, "lit", userCreated, defaultKey = if (userCreated) null else id), packs.toList(), books)

    private fun show(vararg rows: CollectionRow) {
        shelves.shelfList.value = listOf(Shelf(MediaType("lit", "Literature", true, 0), rows.toList()))
    }

    @get:Rule val tmp = org.junit.rules.TemporaryFolder()
    private val sidecars by lazy { tmp.newFolder("sidecars") }

    private fun TestScope.vm(id: String) =
        CollectionViewModel(id, shelves, library, sessions, bookshelf, downloads, appScope = this, archiveSidecarDir = sidecars)

    private fun kiwix(vararg children: CollectionRow) =
        CollectionRow(ShelfCollection("c:kiwix", "Kiwix", "lit", false, defaultKey = "kiwix"), emptyList(), children = children.toList())

    @Test fun theFolderScreenListsItsCollectionsAndEachOpensItsOwn() = runTest(main.dispatcher) {
        val wiki = pack("wiki", "Wikipedia")
        show(collection("c:granger-classics", false), kiwix(collection("c:devdocs", false, pack("py")), collection("c:wikipedia", false, wiki)))
        val folder = vm("c:kiwix").ui.filterNotNull().first()
        assertThat(folder.isFolder).isTrue()
        assertThat(folder.children.map { it.collection.id }).containsExactly("c:devdocs", "c:wikipedia").inOrder()
        assertThat(folder.children.map(::rowCountText)).containsExactly("1 pack", "1 pack")
        // A child opens as an ordinary collection.
        val child = vm("c:wikipedia").ui.filterNotNull().first()
        assertThat(child.isFolder).isFalse()
        assertThat(child.packs).containsExactly(wiki)
    }

    @Test fun theMoveListIndentsAFoldersChildrenAndOffersNoFolder() {
        val shelves = listOf(
            Shelf(
                MediaType("lit", "Literature", true, 0),
                listOf(collection("c:granger-classics", false), kiwix(collection("c:devdocs", false), collection("c:wikipedia", false))),
            ),
        )
        assertThat(moveChoices(shelves, currentCollectionId = "c:wikipedia", allowNewMediaType = true)).containsExactly(
            MoveChoice.Header("Literature"),
            MoveChoice.Existing("c:granger-classics", "c:granger-classics"),
            MoveChoice.Folder("Kiwix"),
            MoveChoice.Existing("c:devdocs", "c:devdocs", indented = true),
            MoveChoice.NewCollection("lit", "Literature"),
            MoveChoice.NewMediaType,
        ).inOrder()
        // A folder with nothing left to offer isn't listed.
        assertThat(moveChoices(shelves.map { it.copy(collections = listOf(kiwix(collection("c:devdocs", false)))) }, "c:devdocs", false))
            .containsExactly(MoveChoice.Header("Literature"), MoveChoice.NewCollection("lit", "Literature")).inOrder()
    }

    @Test fun renameAndDeleteOnlyForUserCollections() = runTest(main.dispatcher) {
        show(collection("built", false, pack("a")), collection("mine", true, pack("b")))
        assertThat(vm("built").ui.filterNotNull().first().collection.userCreated).isFalse()
        assertThat(vm("mine").ui.filterNotNull().first().collection.userCreated).isTrue()
    }

    @Test fun removeDeletesThePackFromThePhone() = runTest(main.dispatcher) {
        val p = pack("a")
        show(collection("c1", false, p))
        vm("c1").remove(p)
        testScheduler.advanceUntilIdle()
        assertThat(library.removed).containsExactly("a")
    }

    @Test fun movePackPassesThroughToShelves() = runTest(main.dispatcher) {
        val p = pack("a")
        show(collection("c1", false, p))
        vm("c1").movePack(p, "c2")
        testScheduler.advanceUntilIdle()
        assertThat(shelves.moves).containsExactly("a" to "c2")
    }

    @Test fun booksListWithProgress() = runTest(main.dispatcher) {
        val b = book("b1", "Emma")
        show(collection("c1", false, books = listOf(b)))
        bookshelf.positionMap.value = mapOf("b1" to BookPosition("b1", "{}", 0.4, "Ch 2", 0L))
        val vm = vm("c1")
        backgroundScope.launch { vm.ui.collect {} }
        backgroundScope.launch { vm.positions.collect {} }
        testScheduler.advanceUntilIdle()
        assertThat(vm.ui.value!!.books.map { it.id }).containsExactly("b1")
        assertThat(vm.positions.value["b1"]!!.totalProgression).isEqualTo(0.4)
    }

    @Test fun openBookOpensOneTabPerBook() = runTest(main.dispatcher) {
        val b = book("b1", "Emma")
        show(collection("c1", false, books = listOf(b)))
        val vm = vm("c1")
        val first = vm.openBook(b)
        val second = vm.openBook(b)
        assertThat(second).isEqualTo(first)
        assertThat(sessions.tabs.value.single().packId).isEqualTo("b1")
        assertThat(sessions.tabs.value.single().title).isEqualTo("Emma")
    }

    @Test fun openBookDoesNothingUnlessItIsOk() = runTest(main.dispatcher) {
        val vm = vm("c1")
        assertThat(vm.openBook(book("b1", status = PackStatus.MISSING))).isNull()
        assertThat(vm.openBook(book("b2", status = PackStatus.CORRUPT))).isNull()
        assertThat(sessions.tabs.value).isEmpty()
    }

    @Test fun removeGrangerBookAlsoRemovesItsDownload() = runTest(main.dispatcher) {
        val b = book("granger:emma", "Emma").copy(origin = BookOrigin.GRANGER, sourceRef = "emma")
        bookshelf.bookList.value = listOf(b)
        show(collection("c1", false, books = listOf(b)))
        vm("c1").removeBook(b)
        testScheduler.advanceUntilIdle()
        assertThat(downloads.calls).containsExactly("remove:emma")
        assertThat(bookshelf.bookList.value).isEmpty()
    }

    @Test fun removingAnArchiveBookRemovesItsDownload() = runTest(main.dispatcher) {
        val b = book("book:archive-org:x", "Old").copy(origin = BookOrigin.ARCHIVE_ORG, sourceRef = "x")
        bookshelf.bookList.value = listOf(b)
        vm("c1").removeBook(b)
        testScheduler.advanceUntilIdle()
        assertThat(downloads.calls).containsExactly("remove:archive-org:x")
        assertThat(bookshelf.bookList.value).isEmpty()
    }

    @Test fun removingAnArchiveBookDeletesItsSidecar() = runTest(main.dispatcher) {
        val sidecar = java.io.File(sidecars, "x.json").apply { writeText("{}") }
        val other = java.io.File(sidecars, "y.json").apply { writeText("{}") }
        val b = book("book:archive-org:x", "Old").copy(origin = BookOrigin.ARCHIVE_ORG, sourceRef = "x")
        bookshelf.bookList.value = listOf(b)
        vm("c1").removeBook(b)
        testScheduler.advanceUntilIdle()
        assertThat(sidecar.exists()).isFalse()
        assertThat(other.exists()).isTrue()
    }

    @Test fun removeImportedBookLeavesDownloadsAlone() = runTest(main.dispatcher) {
        val b = book("import:abc", "Mine").copy(origin = BookOrigin.IMPORT, sourceRef = "abc")
        bookshelf.bookList.value = listOf(b)
        vm("c1").removeBook(b)
        testScheduler.advanceUntilIdle()
        assertThat(downloads.calls).isEmpty()
        assertThat(bookshelf.bookList.value).isEmpty()
    }

    @Test fun moveBookPassesThroughToShelves() = runTest(main.dispatcher) {
        val b = book("b1")
        vm("c1").moveBook(b, "c2")
        testScheduler.advanceUntilIdle()
        assertThat(shelves.moves).containsExactly("b1" to "c2")
    }

    @Test fun supersededClassicsZimIsFlagged() = runTest(main.dispatcher) {
        val old = pack("z1", "Classics").copy(name = "granger_classics_en")
        val other = pack("z2", "Wikipedia")
        show(collection("c1", false, old, other))
        val vm = vm("c1")
        backgroundScope.launch { vm.superseded.collect {} }
        testScheduler.advanceUntilIdle()
        assertThat(vm.superseded.value).containsExactly("z1")
    }
}
