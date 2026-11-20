package app.granger.library

import app.granger.MainDispatcherRule
import app.granger.core.catalog.PackEntry
import app.granger.core.library.Book
import app.granger.core.library.BookFormat
import app.granger.core.library.BookIds
import app.granger.core.library.BookOrigin
import app.granger.core.library.Loose
import app.granger.core.library.ManifestCollection
import app.granger.core.library.ManifestEdition
import app.granger.core.library.ManifestItem
import app.granger.core.library.PackStatus
import app.granger.online.Availability
import app.granger.online.RemoteCollection
import com.google.common.truth.Truth.assertThat
import java.io.File
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Plan BK Tasks 4–6: a book pack's collection read online, its marks, the big-book question and reading editions. */
@OptIn(ExperimentalCoroutinesApi::class)
class RemoteCollectionViewModelTest {
    @get:Rule val main = MainDispatcherRule()
    @get:Rule val tmp = TemporaryFolder()

    private val pack = PackEntry(
        id = "zimgit-knots_en", title = "A Library of Knots", category = "other", language = "eng", sizeBytes = 28_000_000,
        sha256 = "a".repeat(64), urls = listOf("https://download.kiwix.org/zim/other/zimgit-knots_en_2024-08.zim"),
        snapshotDate = "2024-08", license = "", hasFulltextIndex = false, zimUuid = "u", zimName = "zimgit-knots_en", content = "books",
    )

    private fun item(key: String, title: String, bytes: Long = 1_000_000, edition: ManifestEdition? = null, group: String = "") = ManifestItem(
        key = key, path = "files/$key", format = BookFormat.PDF, title = title, author = "Various", description = "", language = "",
        groupName = group, ord = 0, bytes = bytes, partOf = null, workKey = null, workTitle = null, partNo = null,
        zimOffset = 1234, sha256 = null, edition = edition,
    )

    private val rigging = ManifestEdition("https://x/re-rigging.epub", "b".repeat(64), 4_800_000, "Rigging Techniques", "re-rigging")
    private val items = listOf(
        item("Knots (2).pdf", "Take me fishing"),
        item("Knots (6).pdf", "Knots, Splices, Attachments and Ladders", edition = rigging),
        item("Knots (11).pdf", "Knots", bytes = 80L * 1024 * 1024),
    )
    private val remote by lazy {
        RemoteCollection(pack, tmp.newFile(), ManifestCollection("A Library of Knots", "books", "", emptyList(), origin = "nautilus"), items)
    }

    private val books = MutableStateFlow<List<Book>>(emptyList())
    private val reads = mutableListOf<String>()
    private val editionReads = mutableListOf<String>()
    private val keptBooks = mutableListOf<String>()
    private val keptEditions = mutableListOf<String>()

    private fun vm() = RemoteCollectionViewModel(
        load = { remote },
        books = books,
        read = { key, progress -> reads += key; progress(5, 10); remote.bookId(items.first { it.key == key }) },
        keepBook = { id -> keptBooks += id; true },
        keepPack = { true },
        packQueued = MutableStateFlow(false),
        capBytes = { 300L * 1024 * 1024 },
        online = { true },
        readEdition = { id -> editionReads += id; BookIds.of(BookOrigin.GRANGER, id) },
        keepEdition = { id -> keptEditions += id; true },
        io = main.dispatcher,
    )

    private fun book(id: String, origin: BookOrigin, loose: String?, hidden: Boolean) = Book(
        id = id, origin = origin, sourceRef = "", filePath = tmp.newFile().absolutePath, title = "", author = "", translator = "",
        date = "", language = "", subjects = "", description = "", coverPath = null, sizeBytes = 1, status = PackStatus.OK,
        indexedAt = null, addedAt = 1, format = BookFormat.PDF, hidden = hidden, loose = loose,
    )

    @Test fun aTapReadsTheBookFromThePackAndOpensIt() = runTest(main.dispatcher) {
        val vm = vm()
        val events = mutableListOf<RemoteEvent>()
        backgroundScope.launch { vm.event.collect { events += it } }
        backgroundScope.launch { vm.ui.collect {} }
        advanceUntilIdle()
        val ui = vm.ui.value
        assertThat(ui.total).isEqualTo(3)
        vm.open(ui.sections.single().rows.first())
        advanceUntilIdle()
        assertThat(reads).containsExactly("Knots (2).pdf")
        assertThat(events.single()).isEqualTo(RemoteEvent.Open("book:nautilus:zimgit-knots_en/Knots (2).pdf", "Take me fishing"))
    }

    @Test fun aBigBookAsksFirstAndKeepReadsThenKeeps() = runTest(main.dispatcher) {
        val vm = vm()
        backgroundScope.launch { vm.ui.collect {} }
        backgroundScope.launch { vm.event.collect {} }
        advanceUntilIdle()
        val big = vm.ui.value.sections.single().rows.first { it.key == "Knots (11).pdf" }
        vm.open(big)
        advanceUntilIdle()
        assertThat(vm.ui.value.bigAsk!!.bytes).isEqualTo(80L * 1024 * 1024)
        assertThat(reads).isEmpty()
        vm.answerBig(read = false, keep = true)
        advanceUntilIdle()
        assertThat(reads).containsExactly("Knots (11).pdf")
        assertThat(keptBooks).containsExactly("book:nautilus:zimgit-knots_en/Knots (11).pdf")
    }

    @Test fun aBookWithAReadingEditionOpensTheEditionAndOriginalScanTheFile() = runTest(main.dispatcher) {
        val vm = vm()
        val events = mutableListOf<RemoteEvent>()
        backgroundScope.launch { vm.event.collect { events += it } }
        backgroundScope.launch { vm.ui.collect {} }
        advanceUntilIdle()
        val row = vm.ui.value.sections.single().rows.first { it.key == "Knots (6).pdf" }
        assertThat(row.edition).isEqualTo("Rigging Techniques")
        assertThat(row.editionBytes).isEqualTo(4_800_000)
        vm.open(row)
        advanceUntilIdle()
        assertThat(editionReads).containsExactly("re-rigging")
        assertThat(reads).isEmpty()
        assertThat((events.last() as RemoteEvent.Open).bookId).isEqualTo("book:granger:re-rigging")
        vm.openOriginal(row)
        advanceUntilIdle()
        assertThat(reads).containsExactly("Knots (6).pdf")
        vm.keep(row)
        advanceUntilIdle()
        assertThat(keptEditions).containsExactly("re-rigging")
        assertThat(keptBooks).isEmpty()
    }

    @Test fun marksComeFromTheLibraryRowsEditionsByTheirGrangerBook() = runTest(main.dispatcher) {
        val vm = vm()
        backgroundScope.launch { vm.ui.collect {} }
        advanceUntilIdle()
        books.value = listOf(
            book("book:nautilus:zimgit-knots_en/Knots (2).pdf", BookOrigin.NAUTILUS, Loose.SAVED, hidden = true),
            book("book:nautilus:zimgit-knots_en/Knots (11).pdf", BookOrigin.NAUTILUS, Loose.KEPT, hidden = false),
            book("book:granger:re-rigging", BookOrigin.GRANGER, null, hidden = true),
        )
        advanceUntilIdle()
        val marks = vm.ui.value.sections.single().rows.associate { it.key to it.mark }
        assertThat(marks["Knots (2).pdf"]).isEqualTo(Availability.Saved)
        assertThat(marks["Knots (11).pdf"]).isEqualTo(Availability.OnPhone(full = true))
        assertThat(marks["Knots (6).pdf"]).isEqualTo(Availability.Saved)  // the edition, opened without keeping
        assertThat(vm.ui.value.kept).isEqualTo(1)
    }

    @Test fun noListSaysSo() = runTest(main.dispatcher) {
        val vm = RemoteCollectionViewModel(
            load = { null }, books = books, read = { _, _ -> null }, keepBook = { false }, keepPack = { false },
            packQueued = MutableStateFlow(false), capBytes = { 0 }, online = { false }, io = main.dispatcher,
        )
        backgroundScope.launch { vm.ui.collect {} }
        advanceUntilIdle()
        assertThat(vm.ui.value.noList).isTrue()
        assertThat(vm.ui.value.loading).isFalse()
    }
}
