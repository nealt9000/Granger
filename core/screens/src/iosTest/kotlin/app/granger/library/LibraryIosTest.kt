package app.granger.library

import androidx.lifecycle.SavedStateHandle
import app.granger.UiText
import app.granger.archive.ArchiveDownloads
import app.granger.core.archive.ArchiveItem
import app.granger.core.archive.ArchiveQuery
import app.granger.core.archive.TextKind
import app.granger.core.catalog.PackEntry
import app.granger.core.library.Book
import app.granger.core.library.BookFormat
import app.granger.core.library.BookOrigin
import app.granger.core.library.BookPosition
import app.granger.core.library.ManifestCollection
import app.granger.core.library.ManifestItem
import app.granger.core.library.PackStatus
import app.granger.library.shelf.ShelfChoices
import app.granger.library.shelf.ShelfKind
import app.granger.library.shelf.ShelfView
import app.granger.online.RemoteCollection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import platform.Foundation.NSTemporaryDirectory
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The Library's and archive.org's shared models on the iPhone (Plan I5 Task 2), with fakes in place of the stores. */
@OptIn(ExperimentalCoroutinesApi::class)
class LibraryIosTest {
    private val main = StandardTestDispatcher()

    @BeforeTest fun setUp() = Dispatchers.setMain(main)

    @AfterTest fun tearDown() = Dispatchers.resetMain()

    private fun book(id: String, title: String, author: String = "", origin: BookOrigin = BookOrigin.IMPORT) = Book(
        id = id, origin = origin, sourceRef = id, filePath = "/nowhere/$id.epub", title = title, author = author, translator = "",
        date = "", language = "en", subjects = "", description = "", coverPath = null, sizeBytes = 1, status = PackStatus.OK,
        indexedAt = null, addedAt = 1, format = BookFormat.EPUB, hidden = false, loose = null,
    )

    @Test fun aCollectionsBooksSortAndTheReadingShelfFills() {
        val books = listOf(book("b1", "The Time Machine", "H. G. Wells"), book("b2", "Emma", "Jane Austen"), book("b3", "A Room with a View", "E. M. Forster"))
        val byTitle = collectionBooks(books, "", BookSort.TITLE, null, emptyMap(), emptyMap())
        assertEquals(listOf("Emma", "A Room with a View", "The Time Machine"), byTitle.books.map { it.title })
        val byAuthor = collectionBooks(books, "", BookSort.AUTHOR, null, emptyMap(), emptyMap())
        assertEquals(listOf("Jane Austen", "E. M. Forster", "H. G. Wells"), byAuthor.books.map { it.author })
        assertEquals(listOf("The Time Machine"), collectionBooks(books, "tim", BookSort.TITLE, null, emptyMap(), emptyMap()).books.map { it.title })

        val positions = mapOf(
            "b1" to BookPosition("b1", "{}", 0.4, "", 20), "b2" to BookPosition("b2", "{}", 0.99, "", 10),
        )
        val shelf = ReadingShelf.of(books, positions)
        assertEquals(listOf("b1"), shelf.now.map { it.book.id })
        assertEquals(listOf("b2"), shelf.finished.map { it.id })
    }

    @Test fun wordsAreKeyed() {
        assertEquals(UiText.Res("library_sentences", UiText.Plural("library_imported", 2, 2), UiText.Plural("library_already_there", 1, 1)), ImportSummary(2, 1, emptyList()).text())
        assertEquals(UiText.Res("library_nothing_imported"), ImportSummary(0, 0, emptyList()).text())
        val header = GutenbergHeader(null, 1284, 0, kind = HeaderKind.GUTENBERG)
        assertEquals("library_dotted", (header.provenance as UiText.Res).key)
        assertEquals("library_sort_author", BookSort.AUTHOR.labelKey)
        assertEquals("library_link_archive", SourceLink.ARCHIVE_ORG_MUSIC.titleKey)
    }

    @Test fun shelfChoicesAndSavedStateKeepTheirValues() {
        val kept = mutableMapOf<String, String>()
        val choices = ShelfChoices({ kept[it] }, { k, v -> kept[k] = v })
        choices.setView(ShelfKind.BOOKS, ShelfView.GRID)
        choices.setSort(ShelfKind.BOOKS, "AUTHOR")
        val again = ShelfChoices({ kept[it] }, { k, v -> kept[k] = v })
        assertEquals(ShelfView.GRID, again.view(ShelfKind.BOOKS).value)
        assertEquals("AUTHOR", again.sort(ShelfKind.BOOKS).value)
        val saved = SavedStateHandle()
        val open = saved.getStateFlow("openSections", ArrayList<String>())
        saved["openSections"] = arrayListOf("Course materials")
        assertEquals(listOf("Course materials"), open.value)
    }

    @Test fun archiveSidecarsRoundTripAndTheBookRuleStays() {
        val dir = NSTemporaryDirectory().trimEnd('/') + "/granger-sidecars-test"
        val item = ArchiveItem("steamengine00haed", "Steam engines", "Haeder", "1902", 1902, listOf("engines"), "A book.", "eng", "", false, emptyList())
        val path = ArchiveDownloads.writeSidecar(dir, item, TextKind.OCR_TEXT)
        val (details, kind) = ArchiveDownloads.readSidecar(path)!!
        assertEquals(TextKind.OCR_TEXT, kind)
        assertEquals("Steam engines", details.title)
        assertTrue(details.description.startsWith("OCR text from a scan"))
        ArchiveDownloads.deleteSidecar(dir, item.identifier)
        assertNull(ArchiveDownloads.readSidecar(path))
        assertTrue(ArchiveQuery.build("", null, filtered = true).endsWith("year:[* TO 1930]"))
    }

    @Test fun aRemoteCollectionsRowsAndMarks() = runTest(main) {
        val pack = PackEntry(
            id = "zimgit-knots_en", title = "A Library of Knots", category = "other", language = "eng", sizeBytes = 28_000_000,
            sha256 = "a".repeat(64), urls = listOf("https://download.kiwix.org/zim/other/zimgit-knots_en_2024-08.zim"),
            snapshotDate = "2024-08", license = "", hasFulltextIndex = false, zimUuid = "u", zimName = "zimgit-knots_en", content = "books",
        )
        fun item(key: String, title: String, bytes: Long = 1_000_000) = ManifestItem(
            key = key, path = "files/$key", format = BookFormat.PDF, title = title, author = "Various", description = "", language = "",
            groupName = "", ord = 0, bytes = bytes, partOf = null, workKey = null, workTitle = null, partNo = null,
            zimOffset = 1234, sha256 = null, edition = null,
        )
        val items = listOf(item("Knots (2).pdf", "Take me fishing"), item("Knots (11).pdf", "Knots", bytes = 80L * 1024 * 1024))
        val remote = RemoteCollection(pack, "/nowhere/list.gbooks", ManifestCollection("A Library of Knots", "books", "", emptyList(), origin = "nautilus"), items)
        val vm = RemoteCollectionViewModel(
            load = { remote }, books = MutableStateFlow(emptyList()), read = { _, _ -> null }, keepBook = { true }, keepPack = { true },
            packQueued = MutableStateFlow(false), capBytes = { 300L * 1024 * 1024 }, online = { true }, io = main,
        )
        backgroundScope.launch { vm.ui.collect {} }
        advanceUntilIdle()
        val ui = vm.ui.value
        assertEquals("A Library of Knots", ui.title)
        assertEquals(2, ui.total)
        assertEquals(listOf("Take me fishing", "Knots"), ui.sections.flatMap { it.rows }.map { it.title })
        assertTrue(ui.sections.flatMap { it.rows }.all { it.mark == null })
        // A big book asks first.
        vm.open(ui.sections.first().rows.last())
        advanceUntilIdle()
        assertEquals("Knots", vm.ui.value.bigAsk?.title)
        // A row whose file isn't here has no mark.
        assertNull(RemoteCollectionViewModel.markOf(book("x", "X")))
    }
}
