package app.granger.library

import androidx.lifecycle.SavedStateHandle
import app.granger.FakeBookshelf
import app.granger.FakeCatalogSource
import app.granger.bookEntry
import app.granger.catalogOf
import app.granger.asbSet
import app.granger.core.library.BookIds
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
import app.granger.library.shelf.LibraryViewPrefs
import app.granger.library.shelf.ShelfKind
import app.granger.library.shelf.ShelfView
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import androidx.test.core.app.ApplicationProvider

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CollectionViewModelTest {
    private fun app.granger.UiText?.en(): String? = this?.resolve(ApplicationProvider.getApplicationContext())

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
        assertThat(folder.children.map { rowCountText(it).en() }).containsExactly("1 pack", "1 pack")
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

    // Plan U2 Task 2: find, sort and browse by kind.

    private val emma = book("emma", "Emma", author = "Jane Austen")
    private val pan = book("pan", "Peter Pan", author = "J. M. Barrie")
    private val hamlet = book("hamlet", "Hamlet", author = "William Shakespeare")
    private val japan = book("japan", "Things Japanese", author = "")
    private val mine = book("import:x", "Notes", author = "Me").copy(origin = BookOrigin.IMPORT, sourceRef = "x")
    private val classics = FakeCatalogSource(
        catalogOf(books = listOf(bookEntry("pan", "Novels & stories"), bookEntry("hamlet", "Drama"), bookEntry("emma", "Novels & stories"))),
    )

    private val viewPrefs = LibraryViewPrefs()

    private fun TestScope.browse(vararg books: Book, saved: SavedStateHandle = SavedStateHandle(), id: String = "c:granger-classics"): CollectionViewModel {
        show(collection(id, false, books = books.toList()))
        val vm = CollectionViewModel(
            id, shelves, library, sessions, bookshelf, downloads, appScope = this,
            catalog = classics, savedState = saved, viewPrefs = viewPrefs, compute = main.dispatcher,
        )
        backgroundScope.launch { vm.books.collect {} }
        testScheduler.advanceUntilIdle()
        return vm
    }

    private fun CollectionViewModel.ids(): List<String> = books.value.books.map { it.id }

    @Test fun findMatchesTitleOrAuthorFromTheStartOfAWord() = runTest(main.dispatcher) {
        val vm = browse(emma, pan, hamlet, japan)
        vm.setQuery("pan")
        testScheduler.advanceUntilIdle()
        assertThat(vm.ids()).containsExactly("pan")
        vm.setQuery("AUSTEN")
        testScheduler.advanceUntilIdle()
        assertThat(vm.ids()).containsExactly("emma")
        assertThat(vm.findText).isEqualTo("AUSTEN")
        vm.setQuery("")
        testScheduler.advanceUntilIdle()
        assertThat(vm.ids()).hasSize(4)
    }

    @Test fun nothingMatchingLeavesAnEmptyListButKeepsTheTotal() = runTest(main.dispatcher) {
        val vm = browse(emma, pan)
        vm.setQuery("zola")
        testScheduler.advanceUntilIdle()
        assertThat(vm.books.value.books).isEmpty()
        assertThat(vm.books.value.total).isEqualTo(2)
    }

    @Test fun sortsByTitleAuthorSurnameOrRecentlyRead() = runTest(main.dispatcher) {
        bookshelf.positionMap.value = mapOf(
            "hamlet" to BookPosition("hamlet", "{}", 0.1, "", updatedAt = 100L),
            "pan" to BookPosition("pan", "{}", 0.5, "", updatedAt = 300L),
        )
        val vm = browse(pan, hamlet, japan, emma)
        assertThat(vm.ids()).containsExactly("emma", "hamlet", "pan", "japan").inOrder()
        vm.setSort(BookSort.AUTHOR)
        testScheduler.advanceUntilIdle()
        // Austen, Barrie, Shakespeare; no author last.
        assertThat(vm.ids()).containsExactly("emma", "pan", "hamlet", "japan").inOrder()
        vm.setSort(BookSort.RECENT)
        testScheduler.advanceUntilIdle()
        // Read most recently first, then the unread by title.
        assertThat(vm.ids()).containsExactly("pan", "hamlet", "emma", "japan").inOrder()
        assertThat(vm.sort.value).isEqualTo(BookSort.RECENT)
    }

    @Test fun categoriesComeFromTheCatalogInItsOrder() = runTest(main.dispatcher) {
        val vm = browse(emma, hamlet, pan, mine)
        assertThat(vm.books.value.categories).containsExactly("Novels & stories", "Drama").inOrder()
        vm.setCategory("Drama")
        testScheduler.advanceUntilIdle()
        assertThat(vm.ids()).containsExactly("hamlet")
        assertThat(vm.books.value.category).isEqualTo("Drama")
        // Find works within the kind.
        vm.setCategory("Novels & stories")
        vm.setQuery("peter")
        testScheduler.advanceUntilIdle()
        assertThat(vm.ids()).containsExactly("pan")
    }

    @Test fun oneKindOrNoCatalogOffersNoChips() = runTest(main.dispatcher) {
        assertThat(browse(emma, pan).books.value.categories).isEmpty()
        // A category no longer among the books falls back to All.
        val vm = browse(emma, pan, saved = SavedStateHandle(mapOf("category" to "Drama")))
        assertThat(vm.books.value.category).isNull()
        assertThat(vm.ids()).containsExactly("emma", "pan")
    }

    @Test fun theFindTextAndKindComeBackFromSavedState() = runTest(main.dispatcher) {
        val vm = browse(emma, hamlet, pan, saved = SavedStateHandle(mapOf("query" to "a", "category" to "Novels & stories")))
        assertThat(vm.findText).isEqualTo("a")
        assertThat(vm.ids()).containsExactly("emma")
    }

    // Plan LV Task 3: the sort and view are remembered for every book collection; titles skip The, A and An.

    @Test fun theSortAndViewAreRememberedAcrossCollections() = runTest(main.dispatcher) {
        val classicsVm = browse(emma, hamlet, pan)
        assertThat(classicsVm.sort.value).isEqualTo(BookSort.TITLE)
        assertThat(classicsVm.view.value).isEqualTo(ShelfView.LIST)
        classicsVm.setSort(BookSort.AUTHOR)
        classicsVm.toggleView()
        testScheduler.advanceUntilIdle()
        // My books opens the same way.
        val mineVm = browse(mine, emma, id = "c:my-books")
        assertThat(mineVm.sort.value).isEqualTo(BookSort.AUTHOR)
        assertThat(mineVm.view.value).isEqualTo(ShelfView.GRID)
        assertThat(viewPrefs.sort(ShelfKind.BOOKS).value).isEqualTo("AUTHOR")
        mineVm.toggleView()
        assertThat(classicsVm.view.value).isEqualTo(ShelfView.LIST)
    }

    @Test fun titlesFileUnderTheirFirstWordAfterAnArticle() = runTest(main.dispatcher) {
        val odyssey = book("odyssey", "The Odyssey", author = "Homer")
        val nineteen = book("1984", "1984", author = "George Orwell")
        val emile = book("emile", "Émile", author = "Jean-Jacques Rousseau")
        val tale = book("tale", "A Tale of Two Cities", author = "Charles Dickens")
        val filler = (1..30).map { book("b$it", "Book $it", author = "Author $it") }
        val vm = browse(odyssey, nineteen, emile, tale, emma, pan, *filler.toTypedArray())
        val sections = vm.books.value.sections
        assertThat(sections.lettered).isTrue()
        fun letterOf(id: String) = sections.items.first { it.value.id == id }.letter
        assertThat(letterOf("odyssey")).isEqualTo("O")
        assertThat(letterOf("1984")).isEqualTo("#")
        assertThat(letterOf("emile")).isEqualTo("E")
        assertThat(letterOf("tale")).isEqualTo("T")
        // In the order shown: Émile with Emma under E, The Odyssey before Peter Pan, 1984 last.
        assertThat(vm.ids().filter { it in setOf("odyssey", "1984", "emile", "emma", "pan", "tale") })
            .containsExactly("emile", "emma", "odyssey", "pan", "tale", "1984").inOrder()
        assertThat(sections.letters.last()).isEqualTo("#")
    }

    @Test fun authorSortFilesUnderTheSurnameAndRecentHasNoLetters() = runTest(main.dispatcher) {
        val filler = (1..30).map { book("b$it", "Book $it", author = "Ann Zed") }
        val vm = browse(emma, pan, hamlet, japan, *filler.toTypedArray())
        vm.setSort(BookSort.AUTHOR)
        testScheduler.advanceUntilIdle()
        val byAuthor = vm.books.value.sections
        assertThat(byAuthor.letters).containsExactly("A", "B", "S", "Z", "#").inOrder()
        assertThat(byAuthor.sections.last().items.map { it.value.id }).containsExactly("japan")
        vm.setSort(BookSort.RECENT)
        testScheduler.advanceUntilIdle()
        assertThat(vm.books.value.sections.lettered).isFalse()
    }

    @Test fun authorsFileUnderTheFirstSurname() {
        assertThat(authorKey("J. M. Barrie")).isEqualTo("barrie")
        assertThat(authorKey("Jacob and Wilhelm Grimm")).isEqualTo("grimm")
        assertThat(authorKey("Alfred, Lord Tennyson")).isEqualTo("tennyson")
        assertThat(authorKey("Alexander Hamilton, James Madison, John Jay")).isEqualTo("hamilton")
        assertThat(authorKey("Homer")).isEqualTo("homer")
        assertThat(authorKey("Émile Zola")).isEqualTo("zola")
        assertThat(authorKey("")).isEmpty()
    }

    // --- Plan GB: a Gutenberg collection ---

    private val music = pack("u-m", "Music and books on music · Project Gutenberg")
        .copy(name = "gutenberg_en_lcc-m", description = "Music and books on music")

    private fun gutenbergBook(id: Int, title: String, indexed: Boolean = false, copied: Boolean = false): Book {
        val file = java.io.File(tmp.root, "gutenberg/$id.epub")
        if (copied) file.apply { parentFile!!.mkdirs(); writeText("epub") }
        return book("book:gutenberg:$id", title).copy(
            origin = BookOrigin.GUTENBERG, sourceRef = "gutenberg_en_lcc-m/$title.$id.epub", filePath = file.absolutePath,
            sizeBytes = 1_000_000L * id, indexedAt = if (indexed) 5L else null,
        )
    }

    private val gutenbergRow get() = CollectionRow(
        ShelfCollection("c:gutenberg-m", "Music and books on music", "lit", false, defaultKey = "gutenberg-m"), emptyList(),
        listOf(gutenbergBook(1, "Bach", indexed = true, copied = true), gutenbergBook(2, "Beethoven"), gutenbergBook(3, "Chopin")),
    )

    @Test fun aGutenbergCollectionsHeaderCountsItsBooksAndTheSearchableOnes() = runTest(main.dispatcher) {
        library.packList.value = listOf(music)
        show(gutenbergRow)
        val vm = vm("c:gutenberg-m")
        backgroundScope.launch { vm.gutenbergHeader.collect {} }
        testScheduler.advanceUntilIdle()
        val header = vm.gutenbergHeader.value!!
        assertThat(header.pack).isEqualTo(music)
        assertThat(header.books).isEqualTo(3)
        assertThat(header.searchable).isEqualTo(1)
        // Plan KB2 Task 4.5: only the exception is said.
        assertThat(header.text.en()).isEqualTo("3 books · From Kiwix · Project Gutenberg · 2 titles only until opened")
        // The Library's row says the same (Plan GB walkthrough).
        assertThat(rowCountText(gutenbergRow).en()).isEqualTo("3 books · From Kiwix · Project Gutenberg")
    }

    /** Review polish: when every book is the exception, the line doesn't count them again. */
    @Test fun anExceptionForEveryBookIsSaidWithoutItsCount() {
        fun line(unopened: Int, scans: Int) = GutenbergHeader(null, 65, 0, unopened = unopened, scans = scans).exceptionText?.en()
        assertThat(line(65, 0)).isEqualTo("Titles only until opened")
        assertThat(line(12, 0)).isEqualTo("12 titles only until opened")
        assertThat(line(0, 65)).isEqualTo("Scans: titles only")
        assertThat(line(60, 5)).isEqualTo("60 titles only until opened · 5 scans: titles only")
        assertThat(line(0, 0)).isNull()
    }

    @Test fun anyOtherCollectionHasNoGutenbergHeader() = runTest(main.dispatcher) {
        show(collection("c:granger-classics", false, books = listOf(book("hamlet"))))
        val vm = vm("c:granger-classics")
        backgroundScope.launch { vm.gutenbergHeader.collect {} }
        testScheduler.advanceUntilIdle()
        assertThat(vm.gutenbergHeader.value).isNull()
    }

    @Test fun makeAllSearchableGivesTheSizeThenCopiesOutAndIndexesWhatIsLeft() = runTest(main.dispatcher) {
        library.packList.value = listOf(music)
        show(gutenbergRow)
        val copied = mutableListOf<String>()
        var indexed = 0
        val shelf = GutenbergShelf(
            restore = { copied += it.id; true }, indexPending = { indexed++ },
            dir = { java.io.File(tmp.root, "gutenberg") }, scope = this, io = main.dispatcher, batch = 1,
        )
        val vm = CollectionViewModel(
            "c:gutenberg-m", shelves, library, sessions, bookshelf, downloads, appScope = this, gutenberg = shelf,
        )
        backgroundScope.launch { vm.ui.collect {} }
        backgroundScope.launch { vm.gutenbergHeader.collect {} }
        testScheduler.advanceUntilIdle()

        val cost = vm.searchableCost()!!
        assertThat(cost.books).isEqualTo(2)
        assertThat(cost.bytes).isEqualTo(5_000_000L)
        assertThat(cost.freeBytes).isGreaterThan(0L)

        vm.makeAllSearchable()
        assertThat(vm.gutenbergHeader.value).isNotNull()
        assertThat(shelf.progress.value["gutenberg_en_lcc-m"]).isEqualTo(SearchableProgress(0, 3))
        testScheduler.advanceUntilIdle()
        assertThat(copied).containsExactly("book:gutenberg:2", "book:gutenberg:3").inOrder()
        assertThat(indexed).isAtLeast(1)
        assertThat(shelf.progress.value).isEmpty()
    }

    // --- Plan KB: a nautilus pack's collection ---

    private val food = pack("u-food", "Food for Preppers").copy(name = "zimgit-food-preparation_en", scraper = "nautiluszim 1.1.1")

    private fun document(fp: String, title: String, indexed: Boolean = false, searchable: Boolean = true) =
        book("book:nautilus:zimgit-food-preparation_en/$fp", title).copy(
            origin = BookOrigin.NAUTILUS, sourceRef = "zimgit-food-preparation_en/files/$fp",
            format = app.granger.core.library.BookFormat.PDF, indexedAt = if (indexed) 5L else null, searchable = searchable,
        )

    private val foodRow get() = CollectionRow(
        ShelfCollection("c:kiwixbooks-zimgit-food-preparation_en", "Food for Preppers", "lit", false,
            defaultKey = "kiwixbooks-zimgit-food-preparation_en"),
        emptyList(),
        listOf(
            document("Food Preparation (1).pdf", "Philmont Trail Cookbook", indexed = true),
            // Opened and indexed, but a scan: no text to search.
            document("Food Preparation (2).pdf", "Camping Recipes", indexed = true, searchable = false),
            document("Food Preparation (3).pdf", "Dutch Oven"),
        ),
    )

    @Test fun aNautilusCollectionsHeaderSaysKiwixLibraryAndLeavesOutScans() = runTest(main.dispatcher) {
        library.packList.value = listOf(food)
        show(foodRow)
        val vm = vm("c:kiwixbooks-zimgit-food-preparation_en")
        backgroundScope.launch { vm.gutenbergHeader.collect {} }
        testScheduler.advanceUntilIdle()
        val header = vm.gutenbergHeader.value!!
        assertThat(header.pack).isEqualTo(food)
        assertThat(header.books).isEqualTo(3)
        assertThat(header.searchable).isEqualTo(1)
        assertThat(header.text.en()).isEqualTo("3 documents · From Kiwix · 1 title only until opened · 1 scan: title only")
        assertThat(rowCountText(foodRow).en()).isEqualTo("3 documents · From Kiwix")
        assertThat(rowCountText(foodRow.copy(books = foodRow.books.take(1).map { it.copy(indexedAt = null) })).en())
            .isEqualTo("1 document · From Kiwix")
    }

    // --- Plan KB2 Task 4.3: removing one pack book ---

    @Test fun aRemovedPackBookIsListedForShowRemovedWhichPutsItBack() = runTest(main.dispatcher) {
        library.packList.value = listOf(food)
        val gone = document("Food Preparation (3).pdf", "Dutch Oven").copy(hidden = true)
        val elsewhere = book("book:nautilus:zimgit-water_en/w.pdf", "Water").copy(
            origin = BookOrigin.NAUTILUS, sourceRef = "zimgit-water_en/files/w.pdf", hidden = true,
        )
        bookshelf.bookList.value = foodRow.books.take(2) + gone + elsewhere
        show(foodRow.copy(books = foodRow.books.take(2)))
        val vm = CollectionViewModel(
            "c:kiwixbooks-zimgit-food-preparation_en", shelves, library, sessions, bookshelf, downloads, appScope = this,
            compute = main.dispatcher,
        )
        backgroundScope.launch { vm.removed.collect {} }
        testScheduler.advanceUntilIdle()
        // Only this collection's removed books.
        assertThat(vm.removed.value.map { it.title }).containsExactly("Dutch Oven")
        vm.showRemoved()
        testScheduler.advanceUntilIdle()
        assertThat(bookshelf.unhidden).containsExactly(gone.id)
        assertThat(vm.removed.value).isEmpty()

        // A pack book's Remove goes to the shelf, which hides it.
        vm.removeBook(foodRow.books.first())
        testScheduler.advanceUntilIdle()
        assertThat(bookshelf.removed).containsExactly(foodRow.books.first().id)
    }

    // --- Plan KB2 Task 2.2: a pure container listed by its files' names ---

    @Test fun aCollectionListedByFileNamesSaysSo() = runTest(main.dispatcher) {
        val box = pack("u-box", "Box of manuals").copy(name = "box_en_all", scraper = "zimit", bookManifest = app.granger.core.library.Pack.BOOK_FILES)
        val key = "kiwixbooks-box_en_all"
        val row = CollectionRow(
            ShelfCollection("c:$key", "Box of manuals", "lit", false, defaultKey = key), emptyList(),
            listOf(book("book:kiwix:box_en_all/files/a.pdf", "a").copy(origin = BookOrigin.KIWIX, sourceRef = "box_en_all/files/a.pdf", ord = 0)),
        )
        library.packList.value = listOf(box)
        show(row)
        val vm = vm("c:$key")
        backgroundScope.launch { vm.gutenbergHeader.collect {} }
        testScheduler.advanceUntilIdle()
        val header = vm.gutenbergHeader.value!!
        assertThat(header.pack).isEqualTo(box)
        assertThat(header.fromFileNames).isTrue()
        assertThat(header.fileNamesText.en()).isEqualTo("Titles from file names")
        assertThat(header.text.en()).endsWith(" · Titles from file names")
        // A manifest pack's header doesn't.
        library.packList.value = listOf(box.copy(bookManifest = "/s/box.gbooks"))
        assertThat(vm.gutenbergHeader.first { it?.pack?.bookManifest == "/s/box.gbooks" && it.fromFileNames == false }).isNotNull()
    }

    // --- Plan KB2 Task 2.3: groups, parts and languages ---

    private val mm = pack("u-mm", "Military Medicine").copy(name = "mm_en_all", scraper = "zimit", bookManifest = "/s/mm.gbooks")
    private val mmKey = "kiwixbooks-mm_en_all"

    private fun mmBook(key: String, title: String, group: String, ord: Int, workKey: String? = null, partNo: Int? = null, language: String = "en") =
        book("book:kiwix:mm_en_all/$key", title).copy(
            origin = BookOrigin.KIWIX, sourceRef = "mm_en_all/$key.pdf", format = app.granger.core.library.BookFormat.PDF,
            groupName = group, ord = ord, workKey = workKey, workTitle = workKey?.let { "Hospital Corpsman" }, partNo = partNo, language = language,
        )

    private val mmBooks = listOf(
        mmBook("intro", "Introduction", "", 0),
        mmBook("hc1", "Hospital Corpsman (Part 1)", "Handbooks", 1, workKey = "hc", partNo = 1),
        mmBook("hc2", "Hospital Corpsman (Part 2)", "Handbooks", 2, workKey = "hc", partNo = 2),
        mmBook("fa", "Primeros auxilios", "Handbooks", 3, language = "es"),
    ) + (1..60).map { mmBook("o$it", "Order $it", "Orders", 10 + it) }

    private fun TestScope.mmVm(): CollectionViewModel {
        library.packList.value = listOf(mm)
        show(CollectionRow(ShelfCollection("c:$mmKey", "Military Medicine", "lit", false, defaultKey = mmKey), emptyList(), mmBooks))
        return CollectionViewModel(
            "c:$mmKey", shelves, library, sessions, bookshelf, downloads, appScope = this, viewPrefs = LibraryViewPrefs(),
            compute = main.dispatcher,
            manifestOf = { app.granger.core.library.ManifestCollection("Military Medicine", "books", "Federation of American Scientists", listOf("Handbooks", "Orders")) },
            languageName = { mapOf("en" to "English", "es" to "Spanish")[it] ?: it },
        )
    }

    /**
     * Plan KB2 Task 3.2: an African Storybook collection is its levels, lowest first, each headed in the app's words, and
     * its header says how many books and who made them, with no "From Kiwix".
     */
    @Test fun anAfricanStorybookCollectionIsItsLevelsAndNamesItsMaker() = runTest(main.dispatcher) {
        val set = asbSet()
        val books = set.books.map { e -> book(BookIds.of(BookOrigin.GRANGER, e.id), e.title).copy(sourceRef = e.id, indexedAt = 1L) }
        val key = "african-storybook-en"
        show(CollectionRow(ShelfCollection("c:$key", "African Storybook · English", "lit", false, defaultKey = key), emptyList(), books))
        val vm = CollectionViewModel(
            "c:$key", shelves, library, sessions, bookshelf, downloads, appScope = this,
            catalog = FakeCatalogSource(catalogOf().copy(bookSets = listOf(set))), viewPrefs = LibraryViewPrefs(), compute = main.dispatcher,
            levelName = { "Nivel $it" },
        )
        backgroundScope.launch { vm.books.collect {} }
        backgroundScope.launch { vm.gutenbergHeader.collect {} }
        testScheduler.advanceUntilIdle()
        assertThat(vm.books.value.rows!!.map { it.label() }).containsExactly(
            "# Nivel 1", "A very tall man", "Cows at the market", "# Nivel 2", "Goat and the hyena",
        ).inOrder()
        assertThat(vm.books.value.languages).isEmpty()
        val header = vm.gutenbergHeader.value!!
        assertThat(header.kind).isEqualTo(HeaderKind.BOOK_SET)
        assertThat(header.text.en()).isEqualTo("3 books · African Storybook Initiative")
        // The Library's row says the same.
        assertThat(rowCountText(vm.ui.value!!).en()).isEqualTo("3 books · African Storybook Initiative")
    }

    /** Plan KB2 review: a book pack's catalog `provider` names its maker on Books, the Library row and the header. */
    @Test fun aBookPacksCatalogProviderIsSaidWhereItsManifestNamesNoOne() = runTest(main.dispatcher) {
        val knots = app.granger.packEntry("zimgit-knots_en", zimName = "zimgit-knots_en")
            .copy(family = "zimgit-knots", content = "books", provider = "Zimgit")
        assertThat(app.granger.sources.BookShelves.packMeta(knots).en()).endsWith(" · From Kiwix · Zimgit")
        // A catalog from before the field keeps the family heuristic.
        assertThat(app.granger.sources.BookShelves.provider(knots.copy(provider = "", family = "irp.fas.org"))).isEqualTo("irp.fas.org")
        assertThat(app.granger.sources.BookShelves.provider(knots.copy(provider = ""))).isNull()
        val key = "kiwixbooks-zimgit-knots_en"
        assertThat(bookPackProviders(catalogOf(packs = listOf(knots)))).containsExactly(key, "Zimgit")
        val doc = book("book:nautilus:zimgit-knots_en/a.pdf", "Bowline").copy(origin = BookOrigin.NAUTILUS, sourceRef = "zimgit-knots_en/a.pdf")
        val row = CollectionRow(ShelfCollection("c:$key", "A Library of Knots", "lit", false, defaultKey = key), emptyList(), listOf(doc))
        assertThat(rowCountText(row, null, "Zimgit").en()).isEqualTo("1 document · From Kiwix · Zimgit")
        show(row)
        val vm = CollectionViewModel(
            "c:$key", shelves, library, sessions, bookshelf, downloads, appScope = this,
            catalog = FakeCatalogSource(catalogOf(packs = listOf(knots))), viewPrefs = LibraryViewPrefs(), compute = main.dispatcher,
        )
        backgroundScope.launch { vm.gutenbergHeader.collect {} }
        testScheduler.advanceUntilIdle()
        assertThat(vm.gutenbergHeader.value!!.provider.en()).isEqualTo("From Kiwix · Zimgit")
    }

    private fun SectionRow.label(): String = when (this) {
        is SectionRow.Header -> "# $name"
        is SectionRow.Item -> (if (part) "- " else "") + book.title
        is SectionRow.Work -> "$title (${parts.size})"
    }

    @Test fun aManifestCollectionShowsItsSectionsPartsAndLanguages() = runTest(main.dispatcher) {
        val vm = mmVm()
        backgroundScope.launch { vm.books.collect {} }
        backgroundScope.launch { vm.gutenbergHeader.collect {} }
        testScheduler.advanceUntilIdle()
        val shown = vm.books.value
        assertThat(shown.rows!!.map { it.label() }).containsExactly(
            "Introduction", "# Handbooks", "Hospital Corpsman (2)", "Primeros auxilios", "# Orders",
        ).inOrder()
        assertThat((shown.rows!!.last() as SectionRow.Header).let { it.count to it.foldable }).isEqualTo(60 to true)
        assertThat(shown.languages).containsExactly("English", "Spanish").inOrder()
        // The header names the manifest's provider and counts a work once, in its noun.
        val header = vm.gutenbergHeader.value!!
        assertThat(header.text.en()).isEqualTo("63 books · From Kiwix · Federation of American Scientists · Titles only until opened")

        vm.toggleWork("hc")
        vm.toggleSection("Orders")
        testScheduler.advanceUntilIdle()
        val open = vm.books.value.rows!!.map { it.label() }
        assertThat(open.take(6)).containsExactly(
            "Introduction", "# Handbooks", "Hospital Corpsman (2)", "- Hospital Corpsman (Part 1)", "- Hospital Corpsman (Part 2)", "Primeros auxilios",
        ).inOrder()
        assertThat(open).hasSize(7 + 60)

        vm.setLanguage("Spanish")
        testScheduler.advanceUntilIdle()
        assertThat(vm.books.value.rows!!.map { it.label() }).containsExactly("# Handbooks", "Primeros auxilios").inOrder()
        assertThat(vm.books.value.language).isEqualTo("Spanish")

        // Find, or another sort, lists the books flat as before.
        vm.setLanguage(null)
        vm.setQuery("hospital")
        testScheduler.advanceUntilIdle()
        assertThat(vm.books.value.rows).isNull()
        assertThat(vm.books.value.books.map { it.title }).containsExactly("Hospital Corpsman (Part 1)", "Hospital Corpsman (Part 2)")
    }

    @Test fun otherCollectionsHaveNoSectionsOrLanguageChips() = runTest(main.dispatcher) {
        show(collection("c:mine", true, books = listOf(book("a", "Alpha"), book("b", "Beta").copy(language = "spa"))))
        val shown = vm("c:mine").books.first { it.total == 2 }
        assertThat(shown.rows).isNull()
        assertThat(shown.languages).isEmpty()
        assertThat(shown.books).hasSize(2)
    }
}
