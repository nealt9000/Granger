package app.granger.ebook

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import app.granger.BookTarget
import app.granger.FakeBookOpener
import app.granger.FakeBookshelf
import app.granger.FakeReadingSessions
import app.granger.MainDispatcherRule
import app.granger.TrackingContainer
import app.granger.book
import app.granger.core.books.PdfOrigin
import app.granger.core.books.PdfToEpub
import app.granger.core.books.TextQuote
import app.granger.core.library.BookAnnotation
import app.granger.core.library.BookPosition
import app.granger.core.library.MarkKind
import app.granger.core.library.ReadingTab
import app.granger.testPublication
import com.google.common.truth.Truth.assertThat
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.readium.r2.navigator.Decoration
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.publication.services.positions
import org.readium.r2.shared.util.Url
import org.readium.r2.shared.util.mediatype.MediaType
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class EbookReaderViewModelTest {
    @get:Rule val main = MainDispatcherRule()
    @get:Rule val tmp = TemporaryFolder()

    private var clock = 10_000L
    private val bookshelf = FakeBookshelf()
    private val sessions = FakeReadingSessions()
    private val opener = FakeBookOpener()
    private val prefsStore = ReadingPrefsStore(
        ApplicationProvider.getApplicationContext<Context>().getSharedPreferences("reading-vm-test", Context.MODE_PRIVATE),
    )

    @After fun forgetOpenBook() {
        OpenBooks.current = null
    }

    private fun locator(href: String, progression: Double, total: Double, title: String = "Chapter") = Locator(
        href = Url(href)!!,
        mediaType = MediaType.XHTML,
        title = title,
        locations = Locator.Locations(progression = progression, totalProgression = total),
    )

    /** Tab 1 shows book "b", whose file exists unless [fileExists] is false. */
    private fun TestScope.reader(
        fileExists: Boolean = true,
        target: BookTarget? = null,
        appScope: CoroutineScope = this,
        searcher: BookSearcher = FakeBookSearcher(),
        savedState: SavedStateHandle = SavedStateHandle(),
        pdfOutline: (java.io.File) -> List<app.granger.core.books.OutlineEntry> = { emptyList() },
        pdfLinks: (java.io.File) -> List<app.granger.core.books.PdfLink> = { emptyList() },
        pdfColour: (java.io.File) -> Boolean = { false },
    ): EbookReaderViewModel {
        val file = tmp.root.resolve("b.epub").apply { if (fileExists) writeText("epub") }
        bookshelf.bookList.value = listOf(book("b", title = "The Upanishads").copy(filePath = file.path))
        sessions.tabs.value = listOf(ReadingTab(1, "b", "", "The Upanishads", 1L, onRail = true))
        return EbookReaderViewModel(1, target, bookshelf, sessions, opener, prefsStore, appScope, main.dispatcher, searcher, savedState, pdfOutline = pdfOutline, pdfLinks = pdfLinks, pdfColour = pdfColour)
            .also { testScheduler.advanceUntilIdle() }
    }

    // ---- read aloud (Plan V1) ----

    private fun TestScope.readingReader(
        hub: app.granger.player.FakePlayerHub, now: () -> Long = { clock },
        savedPlace: suspend (String) -> app.granger.core.speech.ReadAddress? = { null },
    ): EbookReaderViewModel {
        opener.publication = app.granger.testPublicationWithContent(
            title = "The Upanishads",
            chapters = linkedMapOf("c001.xhtml" to "<h1>One</h1><p>First sentence. Second sentence.</p>", "c002.xhtml" to "<p>Chapter two text.</p>"),
        )
        val file = tmp.root.resolve("b.epub").apply { writeText("epub") }
        bookshelf.bookList.value = listOf(book("b", title = "The Upanishads").copy(filePath = file.path))
        sessions.tabs.value = listOf(ReadingTab(1, "b", "", "The Upanishads", 1L, onRail = true))
        return EbookReaderViewModel(1, null, bookshelf, sessions, opener, prefsStore, this, main.dispatcher, FakeBookSearcher(), SavedStateHandle(), player = hub, now = now, savedPlace = savedPlace)
            .also { testScheduler.advanceUntilIdle() }
    }

    private fun voiceUiFor(key: String) = app.granger.core.player.PlayerUi(
        app.granger.core.player.NowPlaying.Voice(key, "The Upanishads", null, "", 0, 1f, app.granger.core.speech.VoiceId.Granger("v")),
        playing = true, positionMs = 0L, durationMs = 0L, sleepRemainingMs = null,
    )

    /** Readium parses on its own threads: run the scheduler until [until] holds, within a few seconds. */
    private fun TestScope.settle(until: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        while (!until() && System.currentTimeMillis() < deadline) { testScheduler.advanceUntilIdle(); Thread.sleep(10) }
        testScheduler.advanceUntilIdle()
    }

    @Test fun listenGoesOnFromTheBooksSavedPlaceUnlessThePageIsPastIt() = runTest(main.dispatcher) {
        val hub = app.granger.player.FakePlayerHub()
        val asked = mutableListOf<String>()
        var saved: app.granger.core.speech.ReadAddress? = null
        val vm = readingReader(hub, savedPlace = { key -> asked += key; saved })
        val sentences = kotlinx.coroutines.runBlocking { BookReadSource(opener.publication!!, "b", "The Upanishads", kotlinx.coroutines.Dispatchers.Unconfined).utterances(null, 10) }
        saved = sentences.first { it.text == "Second sentence." }.address
        // The page shows the chapter's start, before the saved place: the voice goes on from where it stopped.
        vm.readAloud(locator("c001.xhtml", 0.0, 0.0), visibleText = "First sentence.")
        settle { hub.calls.isNotEmpty() }
        assertThat(hub.calls.single()).isEqualTo("readAloud:book:b@${saved!!.encode()}")
        assertThat(asked).containsExactly("book:b")
        hub.calls.clear()
        // The page is past it: the first visible sentence wins.
        vm.readAloud(locator("c002.xhtml", 0.0, 0.5))
        settle { hub.calls.isNotEmpty() }
        assertThat(hub.calls.single()).contains("c002.xhtml")
    }

    @Test fun readAloudStartsAtTheVisibleElementAndReadFromAtTheSelection() = runTest(main.dispatcher) {
        val hub = app.granger.player.FakePlayerHub()
        val vm = readingReader(hub)
        vm.readAloud(locator("c002.xhtml", 0.0, 0.5))
        settle { hub.calls.isNotEmpty() }
        assertThat(hub.calls).hasSize(1)
        assertThat(hub.calls[0]).startsWith("readAloud:book:b@book:b@")
        assertThat(hub.calls[0]).contains("c002.xhtml")
        hub.calls.clear()
        // The page's first words pick the sentence; words from nowhere fall back to the page's first element.
        vm.readAloud(locator("c001.xhtml", 0.0, 0.0), visibleText = "Second sentence.")
        settle { hub.calls.isNotEmpty() }
        assertThat(hub.calls[0]).contains("c001.xhtml")
        assertThat(hub.calls[0]).endsWith(":1")
        hub.calls.clear()
        vm.readAloud(locator("c001.xhtml", 0.0, 0.0), visibleText = "Not in this book at all")
        settle { hub.calls.isNotEmpty() }
        assertThat(hub.calls[0]).contains("c001.xhtml")
        assertThat(hub.calls[0]).endsWith(":0")
        hub.calls.clear()

        hub.ui.value = voiceUiFor("book:b")
        testScheduler.advanceUntilIdle()
        assertThat(vm.state.value.readingThisBook).isTrue()
        vm.readFrom(locator("c001.xhtml", 0.0, 0.0).copy(text = Locator.Text(before = "First sentence. ", highlight = "Second", after = " sentence.")))
        settle { hub.calls.isNotEmpty() }
        assertThat(hub.calls).hasSize(1)
        assertThat(hub.calls[0]).startsWith("jumpTo:book:b@")
        assertThat(hub.calls[0]).endsWith(":1")           // the second sentence of that paragraph
    }

    @Test fun theVoicesSentenceIsDecoratedAndAUserPageTurnStopsFollowing() = runTest(main.dispatcher) {
        val hub = app.granger.player.FakePlayerHub()
        val vm = readingReader(hub)
        vm.readAloud(null)                                // builds the view model's source
        settle { hub.calls.isNotEmpty() }
        hub.ui.value = voiceUiFor("book:b")
        val src = BookReadSource(vm.state.value.publication!!, "b", "The Upanishads", main.dispatcher)
        val second = src.utterances(null, 10)[2]           // "Second sentence."
        // The addresses are the same whichever source made them; the view model's reads the element it hasn't seen.
        hub.voiceProgress.value = app.granger.core.player.voice.VoiceProgress(second.address, second, 2)
        settle { vm.state.value.reading != null }
        val reading = vm.state.value.reading!!
        assertThat(reading.locator.text.highlight).isEqualTo("Second sentence.")
        assertThat(reading.paragraph).isEqualTo(1)
        assertThat(reading.following).isTrue()
        // The page change right after the voice's own turn is the voice's; one later than the grace is the user's.
        vm.voiceMoved()
        clock += 1_000
        vm.onUserMoved()
        assertThat(vm.state.value.reading!!.following).isTrue()
        clock += 1_000
        vm.onUserMoved()
        assertThat(vm.state.value.reading!!.following).isFalse()
        vm.follow()
        assertThat(vm.state.value.reading!!.following).isTrue()
        hub.ui.value = voiceUiFor("book:other")
        testScheduler.advanceUntilIdle()
        assertThat(vm.state.value.reading).isNull()
        assertThat(vm.state.value.readingThisBook).isFalse()
        vm.stopReading()
        assertThat(hub.calls).contains("stopVoice")
    }

    @Test fun aReaderOpenedWhileTheVoiceAlreadyReadsThisBookShowsItsSentence() = runTest(main.dispatcher) {
        // The voice was restored by the service (or started from an earlier reader); this reader never called readAloud.
        val hub = app.granger.player.FakePlayerHub()
        hub.ui.value = voiceUiFor("book:b")
        val vm = readingReader(hub)
        val src = BookReadSource(vm.state.value.publication!!, "b", "The Upanishads", main.dispatcher)
        val second = src.utterances(null, 10)[2]
        hub.voiceProgress.value = app.granger.core.player.voice.VoiceProgress(second.address, second, 2)
        settle { vm.state.value.reading != null }
        assertThat(vm.state.value.readingThisBook).isTrue()
        assertThat(vm.state.value.reading!!.locator.text.highlight).isEqualTo("Second sentence.")
        assertThat(vm.state.value.reading!!.following).isTrue()
        assertThat(hub.calls).isEmpty()
    }

    @Test fun aPdfOpensWithItsOutlineAndNamesThePlaceByIt() = runTest(main.dispatcher) {
        opener.publication = app.granger.testPdfPublication(pages = 5)
        val vm = reader()
        val state = vm.state.value
        assertThat(state.isPdf).isTrue()
        assertThat(vm.navigatorFactory).isInstanceOf(BookNavigator.Pdf::class.java)
        assertThat(state.toc.map { it.title }).containsExactly("Boiling", "Filters", "Storage").inOrder()
        val positions = kotlinx.coroutines.runBlocking { state.publication!!.positions() }

        vm.onLocator(positions[2]) // Page 3: inside "Filters", which starts on page 2.
        assertThat(vm.state.value.currentTocIndex).isEqualTo(1)
        testScheduler.advanceUntilIdle()
        assertThat(bookshelf.positionMap.value.getValue("b").chapterTitle).isEqualTo("Filters")

        val untitled = app.granger.testPdfPublication(pages = 5, outline = listOf("Storage" to 4))
        opener.publication = untitled
        val bare = reader()
        val page2 = kotlinx.coroutines.runBlocking { untitled.positions() }[1]
        bare.onLocator(page2)
        testScheduler.advanceUntilIdle()
        assertThat(bookshelf.positionMap.value.getValue("b").chapterTitle).isEqualTo("Page 2")
    }

    @Test fun aPdfWhoseOutlinePdfiumCantReadTakesPdfBoxs() = runTest(main.dispatcher) {
        // PDFium's outline: one entry whose destination it couldn't follow (page 0).
        opener.publication = app.granger.testPdfPublication(pages = 5, outline = listOf("Water" to 0))
        val read = mutableListOf<java.io.File>()
        val outline = listOf(
            app.granger.core.books.OutlineEntry("Water", 1, 0),
            app.granger.core.books.OutlineEntry("Filters", 3, 1),
        )
        val vm = reader(pdfOutline = { file -> read += file; outline })
        assertThat(read).hasSize(1)
        assertThat(vm.state.value.toc).containsExactly(TocRow("Water", "doc.pdf#page=1", 0), TocRow("Filters", "doc.pdf#page=3", 1)).inOrder()

        val jumps = mutableListOf<Locator>()
        val collecting = launch { vm.navigation.collect { jumps += it } }
        vm.goTo(vm.state.value.toc[1].href)
        testScheduler.advanceUntilIdle()
        assertThat(jumps.single().locations.fragments).containsExactly("page=3")
        collecting.cancel()
    }

    @Test fun aPdfLinkJumpsToItsPageThroughPdfBoxWhenPdfiumGaveNone() = runTest(main.dispatcher) {
        opener.publication = app.granger.testPdfPublication(pages = 5)
        var reads = 0
        val vm = reader(pdfLinks = { reads++; listOf(app.granger.core.books.PdfLink(0, 72f, 600f, 300f, 620f, target = 3)) })
        val jumps = mutableListOf<Locator>()
        val collecting = launch { vm.navigation.collect { jumps += it } }

        vm.onPdfLink(PdfLinkTap(page = 1, uri = "", left = 0f, top = 0f, right = 0f, bottom = 0f))
        testScheduler.advanceUntilIdle()
        assertThat(jumps.last().locations.fragments).containsExactly("page=2")
        assertThat(reads).isEqualTo(0)

        // PDFium found the link but not its page: PdfBox's links place it by its box, read once.
        vm.onPdfLink(PdfLinkTap(page = -1, uri = "", left = 72f, top = 620f, right = 300f, bottom = 600f))
        testScheduler.advanceUntilIdle()
        assertThat(jumps.last().locations.fragments).containsExactly("page=4")
        vm.onPdfLink(PdfLinkTap(page = null, uri = null, left = 72f, top = 620f, right = 300f, bottom = 600f))
        vm.onPdfLink(PdfLinkTap(page = null, uri = null, left = 1f, top = 2f, right = 3f, bottom = 4f))
        testScheduler.advanceUntilIdle()
        assertThat(jumps).hasSize(3)
        assertThat(reads).isEqualTo(1)
        collecting.cancel()
    }

    @Test fun aPdfWithNoOutlineHasNoContentsRows() = runTest(main.dispatcher) {
        opener.publication = app.granger.testPdfPublication(pages = 5, outline = listOf("Water" to 0))
        assertThat(reader().state.value.toc).isEmpty()
        // A good outline from PDFium isn't read again.
        opener.publication = app.granger.testPdfPublication(pages = 5)
        var asked = false
        assertThat(reader(pdfOutline = { asked = true; emptyList() }).state.value.toc).hasSize(3)
        assertThat(asked).isFalse()
    }

    @Test fun aPdfBookmarkIsNamedByItsPage() = runTest(main.dispatcher) {
        opener.publication = app.granger.testPdfPublication(pages = 5)
        val vm = reader()
        val positions = kotlinx.coroutines.runBlocking { vm.state.value.publication!!.positions() }
        vm.onLocator(positions[2])
        vm.toggleBookmark()
        advanceUntilIdle()
        assertThat(bookshelf.markList.value.single().text).isEqualTo("Filters · p. 3")
    }

    // ---- ebooks made from PDFs (Plan IA) ----

    @Test fun anEbookMadeFromAPdfOpensAndRemovesItsOriginalPages() = runTest(main.dispatcher) {
        val pages = book("pages", title = "The Upanishads")
        bookshelf.originalPages["b"] = pages to 2_500_000L
        val vm = reader()
        assertThat(vm.state.value.originalPagesBytes).isEqualTo(2_500_000L)
        assertThat(vm.state.value.canReadAsEbook).isFalse()
        val tab = vm.openOriginalPages()!!
        assertThat(sessions.tabs.value.single { it.id == tab }.title).isEqualTo("The Upanishads (original pages)")
        vm.removeOriginalPages()
        advanceUntilIdle()
        assertThat(vm.state.value.originalPagesBytes).isNull()
        assertThat(vm.state.value.notice).isEqualTo("Original pages removed")
        assertThat(bookshelf.originalPages).isEmpty()
    }

    @Test fun aPdfReadAsAnEbookOpensAgainOrSaysWhyNot() = runTest(main.dispatcher) {
        opener.publication = app.granger.testPdfPublication()
        val vm = reader()
        assertThat(vm.state.value.canReadAsEbook).isTrue()
        val passing = PdfToEpub.Report(PdfOrigin.BORN_DIGITAL, 10, 1000, 1000, 0, 10, 100, 0, 1, 0, 0, 2, null, 0, true, "passes")
        bookshelf.ebookReports["b"] = passing.copy(passes = false, why = "kept only 80% of the text")
        var reopened = 0
        vm.readAsEbook { reopened++ }
        advanceUntilIdle()
        assertThat(reopened).isEqualTo(0)
        assertThat(vm.state.value.notice).contains("kept only 80% of the text")
        assertThat(vm.state.value.makingEbook).isFalse()

        bookshelf.ebookReports["b"] = passing
        vm.readAsEbook { reopened++ }
        advanceUntilIdle()
        assertThat(reopened).isEqualTo(1)
    }

    @Test fun aColourPdfKeepsItsColoursInNight() = runTest(main.dispatcher) {
        prefsStore.update(ReadingPrefs(theme = ReadingTheme.NIGHT))
        opener.publication = app.granger.testPdfPublication()
        val pdf = reader(pdfColour = { true }).navigatorFactory as BookNavigator.Pdf
        assertThat(pdf.night).isFalse()
    }

    @Test fun aPdfsNightFollowsTheTheme() = runTest(main.dispatcher) {
        prefsStore.update(ReadingPrefs(theme = ReadingTheme.DAY))
        opener.publication = app.granger.testPdfPublication()
        val vm = reader()
        val pdf = vm.navigatorFactory as BookNavigator.Pdf
        assertThat(pdf.night).isFalse()
        vm.updatePrefs(vm.state.value.prefs.copy(theme = ReadingTheme.NIGHT))
        testScheduler.advanceUntilIdle()
        assertThat(pdf.night).isTrue()
        assertThat(pdf.fitWidth).isFalse()
        vm.updatePrefs(vm.state.value.prefs.copy(pdfFitWidth = true))
        testScheduler.advanceUntilIdle()
        assertThat(pdf.fitWidth).isTrue()
    }

    @Test fun anEpubIsNotAPdf() = runTest(main.dispatcher) {
        val vm = reader()
        assertThat(vm.state.value.isPdf).isFalse()
        assertThat(vm.navigatorFactory).isInstanceOf(BookNavigator.Epub::class.java)
    }

    @Test fun opensAtSavedPosition() = runTest(main.dispatcher) {
        val saved = locator("c002.xhtml", 0.4, 0.45)
        bookshelf.positionMap.value = mapOf("b" to BookPosition("b", saved.toJSON().toString(), 0.45, "Two", 0L))
        val vm = reader()
        val state = vm.state.value
        assertThat(state.loading).isFalse()
        assertThat(state.error).isNull()
        assertThat(state.title).isEqualTo("The Upanishads")
        assertThat(state.publication).isSameInstanceAs(opener.publication)
        assertThat(state.initial!!.href).isEqualTo(Url("c002.xhtml"))
        assertThat(state.initial!!.locations.progression).isEqualTo(0.4)
        assertThat(OpenBooks.current?.first).isEqualTo("b")
        // The tab now points at the book.
        assertThat(sessions.tab(1)!!.packId).isEqualTo("b")
        assertThat(sessions.tab(1)!!.title).isEqualTo("The Upanishads")
    }

    @Test fun routeTargetWinsOverSavedPosition() = runTest(main.dispatcher) {
        bookshelf.positionMap.value =
            mapOf("b" to BookPosition("b", locator("c002.xhtml", 0.4, 0.45).toJSON().toString(), 0.45, "Two", 0L))
        val vm = reader(target = BookTarget("c003.xhtml", 0.5))
        assertThat(vm.state.value.initial!!.href).isEqualTo(Url("c003.xhtml"))
    }

    @Test fun openingConsumesTheRouteTarget() = runTest(main.dispatcher) {
        val savedState = SavedStateHandle()
        reader(target = BookTarget("c003.xhtml", 0.5), savedState = savedState)
        assertThat(savedState.get<Boolean>(EbookReaderViewModel.TARGET_CONSUMED)).isTrue()
    }

    @Test fun consumedTargetIsNotReappliedAfterProcessDeath() = runTest(main.dispatcher) {
        bookshelf.positionMap.value =
            mapOf("b" to BookPosition("b", locator("c002.xhtml", 0.4, 0.45).toJSON().toString(), 0.45, "Two", 0L))
        // The back stack restored the route's target, and the flag saved with it says it was already used.
        val restored = SavedStateHandle(mapOf(EbookReaderViewModel.TARGET_CONSUMED to true))
        val vm = reader(target = BookTarget("c003.xhtml", 0.5), savedState = restored)
        assertThat(vm.state.value.initial!!.href).isEqualTo(Url("c002.xhtml"))
        assertThat(vm.state.value.initial!!.locations.progression).isEqualTo(0.4)
    }

    @Test fun missingFileShowsBookGone() = runTest(main.dispatcher) {
        val vm = reader(fileExists = false)
        assertThat(vm.state.value.error).isEqualTo(EbookReaderViewModel.BOOK_GONE)
        assertThat(vm.state.value.loading).isFalse()
        assertThat(vm.state.value.chromeVisible).isTrue()
        assertThat(opener.opened).isEmpty()
    }

    @Test fun unopenableShowsBookDamaged() = runTest(main.dispatcher) {
        opener.publication = null
        val vm = reader()
        assertThat(vm.state.value.error).isEqualTo(EbookReaderViewModel.BOOK_DAMAGED)
        assertThat(vm.state.value.publication).isNull()
        assertThat(OpenBooks.current).isNull()
    }

    @Test fun missingTabShowsTabGone() = runTest(main.dispatcher) {
        val vm = EbookReaderViewModel(42, null, bookshelf, sessions, opener, prefsStore, this, main.dispatcher)
        testScheduler.advanceUntilIdle()
        assertThat(vm.state.value.error).isEqualTo(EbookReaderViewModel.TAB_GONE)
        assertThat(vm.state.value.chromeVisible).isTrue()
    }

    @Test fun locatorChangesAreSavedDebounced() = runTest(main.dispatcher) {
        val vm = reader()
        vm.onLocator(locator("c001.xhtml", 0.5, 0.15))
        advanceTimeBy(500)
        vm.onLocator(locator("c002.xhtml", 0.2, 0.4, title = "Two"))
        advanceTimeBy(900)
        runCurrent()
        assertThat(bookshelf.positionMap.value["b"]).isNull()
        advanceTimeBy(200)
        runCurrent()
        val saved = bookshelf.positionMap.value["b"]!!
        assertThat(saved.totalProgression).isEqualTo(0.4)
        assertThat(saved.chapterTitle).isEqualTo("Two")
        assertThat(Locator.fromJSON(JSONObject(saved.locatorJson))!!.href).isEqualTo(Url("c002.xhtml"))
        assertThat(vm.state.value.locator!!.href).isEqualTo(Url("c002.xhtml"))
    }

    @Test fun placesReportedOffScreenAreIgnored() = runTest(main.dispatcher) {
        val vm = reader()
        vm.onLocator(locator("c002.xhtml", 0.5, 0.4))
        advanceTimeBy(1_100)
        runCurrent()
        // Leaving the reader: the outgoing navigator reports the page before; that mustn't replace the place.
        vm.onLocator(locator("c002.xhtml", 0.33, 0.38), onScreen = false)
        vm.flush()
        advanceTimeBy(2_000)
        runCurrent()
        assertThat(bookshelf.positionMap.value["b"]!!.totalProgression).isEqualTo(0.4)
        assertThat(vm.state.value.locator!!.locations.progression).isEqualTo(0.5)
    }

    @Test fun searchHitWithTextIsMarkedWhereTheReaderLands() = runTest(main.dispatcher) {
        val vm = reader(target = BookTarget("c003.xhtml", 0.5, TextQuote("The ", "quality of mercy", " is")))
        val hit = vm.state.value.searchHit!!
        assertThat(hit).isSameInstanceAs(vm.state.value.initial)
        assertThat(hit.text.highlight).isEqualTo("quality of mercy")
    }

    @Test fun usedTargetIsNotMarkedAgainAfterProcessDeath() = runTest(main.dispatcher) {
        val restored = SavedStateHandle(mapOf(EbookReaderViewModel.TARGET_CONSUMED to true))
        val vm = reader(target = BookTarget("c003.xhtml", 0.5, TextQuote("", "mercy", "")), savedState = restored)
        assertThat(vm.state.value.searchHit).isNull()
    }

    @Test fun flushSavesAtOnce() = runTest(main.dispatcher) {
        val vm = reader()
        vm.onLocator(locator("c003.xhtml", 0.1, 0.7))
        vm.flush()
        runCurrent()
        assertThat(bookshelf.positionMap.value["b"]!!.totalProgression).isEqualTo(0.7)
        // The debounced save that was pending doesn't write again later.
        bookshelf.positionMap.value = emptyMap()
        advanceTimeBy(2_000)
        runCurrent()
        assertThat(bookshelf.positionMap.value["b"]).isNull()
    }

    private fun EbookReaderViewModel.clear() = ViewModelStore().also { it.put("vm", this) }.clear()

    @Test fun clearingClosesThePublicationAndReleasesTheBook() = runTest(main.dispatcher) {
        val container = TrackingContainer()
        opener.publication = testPublication(container = container)
        val vm = reader()
        assertThat(OpenBooks.current?.first).isEqualTo("b")
        vm.clear()
        advanceUntilIdle()
        assertThat(container.closed).isTrue()
        assertThat(OpenBooks.current).isNull()
    }

    @Test fun clearingLeavesANewerReaderCurrent() = runTest(main.dispatcher) {
        val first = reader()
        val newer = EbookReaderViewModel(1, null, bookshelf, sessions, FakeBookOpener(), prefsStore, this, main.dispatcher)
        advanceUntilIdle()
        val newerFactory = newer.navigatorFactory
        first.clear()
        advanceUntilIdle()
        assertThat(OpenBooks.current?.second).isSameInstanceAs(newerFactory)
    }

    @Test fun publicationOpenedAfterClearingIsClosed() = runTest(main.dispatcher) {
        val container = TrackingContainer()
        val gate = CompletableDeferred<Unit>()
        var started = false
        val slow = object : BookOpener {
            override suspend fun open(file: File): Publication {
                started = true
                gate.await()
                return testPublication(container = container)
            }
        }
        val file = tmp.root.resolve("b.epub").apply { writeText("epub") }
        bookshelf.bookList.value = listOf(book("b").copy(filePath = file.path))
        sessions.tabs.value = listOf(ReadingTab(1, "b", "", "b", 1L, onRail = true))
        val vm = EbookReaderViewModel(1, null, bookshelf, sessions, slow, prefsStore, this, main.dispatcher)
        runCurrent()
        assertThat(started).isTrue()
        vm.clear()
        gate.complete(Unit)
        advanceUntilIdle()
        assertThat(container.closed).isTrue()
        assertThat(OpenBooks.current).isNull()
    }

    @Test fun chromeStartsVisibleAndAMiddleTapToggles() = runTest(main.dispatcher) {
        // A new reader opens with its bars and the rail showing; a middle tap goes full screen, another comes back.
        val vm = reader()
        assertThat(vm.state.value.chromeVisible).isTrue()
        vm.toggleChrome()
        assertThat(vm.state.value.chromeVisible).isFalse()
        vm.toggleChrome()
        assertThat(vm.state.value.chromeVisible).isTrue()
        vm.toggleChrome()
        vm.showChrome()
        assertThat(vm.state.value.chromeVisible).isTrue()
    }

    @Test fun leavingTheScreenBringsTheChromeBackForTheReturn() = runTest(main.dispatcher) {
        val vm = reader()
        vm.toggleChrome()
        assertThat(vm.state.value.chromeVisible).isFalse()
        vm.onScreenLeft()
        assertThat(vm.state.value.chromeVisible).isTrue()
    }

    private fun bookmark(id: Long, href: String, position: Int, total: Double, createdAt: Long, title: String = "T") = BookAnnotation(
        id, "b", MarkKind.BOOKMARK, locator(href, 0.1, total, title).copy(
            locations = Locator.Locations(progression = 0.1, position = position, totalProgression = total),
        ).toJSON().toString(), title, "", "", createdAt, createdAt,
    )

    @Test fun tocIsFlattenedWithDepth() = runTest(main.dispatcher) {
        opener.publication = testPublication(toc = listOf("c001.xhtml" to listOf("c002.xhtml#s1"), "c003.xhtml" to emptyList()))
        val vm = reader()
        assertThat(vm.state.value.toc.map { it.depth }).containsExactly(0, 1, 0).inOrder()
        assertThat(vm.state.value.toc.map { it.title }).containsExactly("c001.xhtml", "c002.xhtml#s1", "c003.xhtml").inOrder()
    }

    @Test fun currentChapterFollowsLocator() = runTest(main.dispatcher) {
        opener.publication = testPublication(toc = listOf("c001.xhtml" to listOf("c002.xhtml#s1"), "c003.xhtml" to emptyList()))
        val vm = reader()
        assertThat(vm.state.value.currentTocIndex).isNull()
        vm.onLocator(locator("c002.xhtml", 0.5, 0.4))
        assertThat(vm.state.value.currentTocIndex).isEqualTo(1)
        vm.onLocator(locator("c003.xhtml", 0.5, 0.8))
        assertThat(vm.state.value.currentTocIndex).isEqualTo(2)
    }

    @Test fun positionsCountComesFromThePublication() = runTest(main.dispatcher) {
        val vm = reader()
        assertThat(vm.state.value.positionsCount).isEqualTo(3)
    }

    @Test fun bookmarkTogglesAtCurrentPlace() = runTest(main.dispatcher) {
        val vm = reader()
        vm.onLocator(locator("c002.xhtml", 0.2, 0.4, "Two").copy(locations = Locator.Locations(position = 2, totalProgression = 0.4)))
        assertThat(vm.state.value.isBookmarkedHere).isFalse()
        vm.toggleBookmark()
        advanceUntilIdle()
        assertThat(bookshelf.markList.value.single().kind).isEqualTo(MarkKind.BOOKMARK)
        assertThat(bookshelf.markList.value.single().text).isEqualTo("Two")
        assertThat(vm.state.value.bookmarks).hasSize(1)
        assertThat(vm.state.value.isBookmarkedHere).isTrue()
        vm.toggleBookmark()
        advanceUntilIdle()
        assertThat(bookshelf.markList.value).isEmpty()
        assertThat(vm.state.value.isBookmarkedHere).isFalse()
    }

    @Test fun marksAreSplitAndBookmarksNewestFirst() = runTest(main.dispatcher) {
        bookshelf.markList.value = listOf(
            bookmark(1, "c001.xhtml", 1, 0.1, 100),
            bookmark(2, "c003.xhtml", 3, 0.8, 200),
            bookmark(3, "c001.xhtml", 1, 0.1, 150).copy(kind = MarkKind.HIGHLIGHT),
        )
        val vm = reader()
        assertThat(vm.state.value.bookmarks.map { it.id }).containsExactly(2L, 1L).inOrder()
        assertThat(vm.state.value.highlights.map { it.id }).containsExactly(3L)
    }

    @Test fun sliderJumpEmitsNavigation() = runTest(main.dispatcher) {
        val vm = reader()
        val seen = mutableListOf<Locator>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.navigation.collect { seen += it } }
        runCurrent()
        vm.goToProgression(0.9)
        advanceUntilIdle()
        assertThat(seen.single().href).isEqualTo(Url("c003.xhtml"))
    }

    @Test fun contentsTapNavigatesKeepingFragmentAndClosesPanel() = runTest(main.dispatcher) {
        val vm = reader()
        val seen = mutableListOf<Locator>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.navigation.collect { seen += it } }
        runCurrent()
        vm.open(Panel.CONTENTS)
        vm.goTo("c002.xhtml#s1")
        vm.goTo("missing.xhtml")
        advanceUntilIdle()
        assertThat(seen).hasSize(1)
        assertThat(seen[0].href).isEqualTo(Url("c002.xhtml"))
        assertThat(seen[0].locations.fragments).containsExactly("s1")
        assertThat(vm.state.value.panel).isNull()
    }

    @Test fun markTapNavigatesToItsLocator() = runTest(main.dispatcher) {
        val vm = reader()
        val seen = mutableListOf<Locator>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.navigation.collect { seen += it } }
        runCurrent()
        vm.goToMark(bookmark(1, "c003.xhtml", 3, 0.8, 1))
        advanceUntilIdle()
        assertThat(seen.single().href).isEqualTo(Url("c003.xhtml"))
    }

    @Test fun staleBookmarkJumpsNearby() = runTest(main.dispatcher) {
        val vm = reader()
        val seen = mutableListOf<Locator>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.navigation.collect { seen += it } }
        runCurrent()
        vm.open(Panel.CONTENTS)
        // A re-release renamed the chapter files: the bookmark's progression through the book still places it.
        vm.goToMark(bookmark(1, "renamed/c003.xhtml", 3, 0.8, 1))
        advanceUntilIdle()
        assertThat(seen.single().href).isEqualTo(Url("c003.xhtml"))
        assertThat(vm.state.value.panel).isNull()
        // Nothing to place it by: the tap does nothing.
        val lost = Locator(Url("gone.xhtml")!!, MediaType.XHTML, locations = Locator.Locations(progression = 0.5))
        vm.goToMark(BookAnnotation(2, "b", MarkKind.BOOKMARK, lost.toJSON().toString(), "", "", "", 2, 2))
        advanceUntilIdle()
        assertThat(seen).hasSize(1)
    }

    @Test fun prefsChangesAreStored() = runTest(main.dispatcher) {
        val vm = reader()
        vm.updatePrefs(ReadingPrefs(theme = ReadingTheme.SEPIA, fontScale = 9.0))
        advanceUntilIdle()
        assertThat(vm.state.value.prefs.theme).isEqualTo(ReadingTheme.SEPIA)
        assertThat(vm.state.value.prefs.fontScale).isEqualTo(ReadingPrefs.MAX_FONT_SCALE)
        assertThat(prefsStore.prefs.value.theme).isEqualTo(ReadingTheme.SEPIA)
    }

    private fun placed(href: String, position: Int?, total: Double, title: String = "T") =
        locator(href, 0.1, total, title).copy(locations = Locator.Locations(position = position, totalProgression = total))

    @Test fun doubleTapOnBookmarkEndsWithNone() = runTest(main.dispatcher) {
        val vm = reader()
        vm.onLocator(placed("c002.xhtml", 2, 0.4))
        vm.toggleBookmark()
        vm.toggleBookmark()
        advanceUntilIdle()
        assertThat(bookshelf.markList.value).isEmpty()
        vm.toggleBookmark()
        vm.toggleBookmark()
        vm.toggleBookmark()
        advanceUntilIdle()
        assertThat(bookshelf.markList.value).hasSize(1)
    }

    @Test fun nullPositionsCompareByProgression() = runTest(main.dispatcher) {
        val vm = reader()
        vm.onLocator(placed("c002.xhtml", null, 0.40))
        vm.toggleBookmark()
        advanceUntilIdle()
        assertThat(vm.state.value.isBookmarkedHere).isTrue()
        vm.onLocator(placed("c002.xhtml", null, 0.45))
        assertThat(vm.state.value.isBookmarkedHere).isFalse()
        vm.toggleBookmark()
        advanceUntilIdle()
        assertThat(bookshelf.markList.value).hasSize(2)
        vm.onLocator(placed("c002.xhtml", null, 0.4003))
        assertThat(vm.state.value.isBookmarkedHere).isTrue()
    }

    @Test fun lastMatchingTocRowInTheFileIsCurrent() = runTest(main.dispatcher) {
        opener.publication = testPublication(
            toc = listOf("c001.xhtml" to listOf("c002.xhtml", "c002.xhtml#a", "c002.xhtml#b"), "c003.xhtml" to emptyList()),
        )
        val vm = reader()
        vm.onLocator(locator("c002.xhtml", 0.5, 0.4))
        assertThat(vm.state.value.currentTocIndex).isEqualTo(3)
    }

    @Test fun backToBackJumpsAreAllDelivered() = runTest(main.dispatcher) {
        val vm = reader()
        vm.goToMark(bookmark(1, "c001.xhtml", 1, 0.1, 1))
        vm.goToMark(bookmark(2, "c002.xhtml", 2, 0.4, 2))
        vm.goToMark(bookmark(3, "c003.xhtml", 3, 0.8, 3))
        val seen = mutableListOf<Locator>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.navigation.collect { seen += it } }
        advanceUntilIdle()
        assertThat(seen.map { it.href.toString() }).containsExactly("c001.xhtml", "c002.xhtml", "c003.xhtml").inOrder()
    }

    // --- Highlights, notes and search in the book ---

    private fun selection(href: String, text: String, before: String = "", after: String = "", title: String = "Two") =
        locator(href, 0.3, 0.4, title).copy(text = Locator.Text(before = before, highlight = text, after = after))

    @Test fun highlightIsStoredAndDecorated() = runTest(main.dispatcher) {
        val vm = reader()
        val id = vm.highlight(selection("c002.xhtml", "the self"), "the self", "green")
        advanceUntilIdle()
        val mark = bookshelf.markList.value.single()
        assertThat(mark.id).isEqualTo(id)
        assertThat(mark.kind).isEqualTo(MarkKind.HIGHLIGHT)
        assertThat(mark.color).isEqualTo("green")
        assertThat(mark.text).isEqualTo("the self")
        val decoration = vm.state.value.decorations.single()
        assertThat(decoration.id).isEqualTo(id.toString())
        assertThat((decoration.style as Decoration.Style.Highlight).tint).isEqualTo(HighlightColors.argb("green"))
        assertThat(vm.state.value.unplaceable).isEmpty()
    }

    @Test fun unplaceableHighlightIsListedNotDrawn() = runTest(main.dispatcher) {
        val gone = bookmark(7, "gone.xhtml", 1, 0.1, 1).copy(kind = MarkKind.HIGHLIGHT, text = "lost", color = "yellow")
        val here = bookmark(8, "c002.xhtml", 2, 0.4, 2).copy(kind = MarkKind.HIGHLIGHT, text = "kept", color = "blue")
        bookshelf.markList.value = listOf(gone, here)
        val vm = reader()
        assertThat(vm.state.value.unplaceable).containsExactly(7L)
        assertThat(vm.state.value.decorations.map { it.id }).containsExactly("8")
        assertThat(vm.state.value.highlights).hasSize(2)
    }

    @Test fun noteCreatesHighlightWithNote() = runTest(main.dispatcher) {
        val vm = reader()
        vm.note(selection("c002.xhtml", "tat tvam asi"), "tat tvam asi")
        advanceUntilIdle()
        val mark = bookshelf.markList.value.single()
        assertThat(mark.color).isEqualTo("yellow")
        assertThat(vm.state.value.editingHighlight).isEqualTo(mark.id)
        vm.updateHighlight(mark.copy(note = "You are that", color = "pink"))
        advanceUntilIdle()
        assertThat(bookshelf.markList.value.single().note).isEqualTo("You are that")
        assertThat(bookshelf.markList.value.single().color).isEqualTo("pink")
        vm.deleteHighlight(mark.id)
        advanceUntilIdle()
        assertThat(bookshelf.markList.value).isEmpty()
        assertThat(vm.state.value.decorations).isEmpty()
        assertThat(vm.state.value.editingHighlight).isNull()
    }

    private fun hit(href: String, before: String, match: String, after: String) =
        selection(href, match, before, after, title = "")

    @Test fun searchListsMarksThenTextHits() = runTest(main.dispatcher) {
        val searcher = FakeBookSearcher()
        searcher.results["fire"] = listOf(hit("c003.xhtml", "a ", "fire", " burns"))
        val marked = bookmark(1, "c001.xhtml", 1, 0.1, 1).copy(kind = MarkKind.HIGHLIGHT, text = "Sacred FIRE rises", color = "yellow")
        val noted = bookmark(2, "c002.xhtml", 2, 0.4, 2).copy(kind = MarkKind.HIGHLIGHT, text = "other", color = "blue", note = "about fire")
        val unrelated = bookmark(3, "c002.xhtml", 2, 0.4, 3).copy(kind = MarkKind.HIGHLIGHT, text = "water", color = "blue")
        bookshelf.markList.value = listOf(marked, noted, unrelated)
        val vm = reader(searcher = searcher)
        vm.search("fire")
        advanceUntilIdle()
        val ui = vm.state.value.search
        assertThat(ui.running).isFalse()
        assertThat(ui.results.map { it.markId }).containsExactly(1L, 2L, null).inOrder()
        assertThat(ui.results[0].chapter).startsWith("Your highlight")
        assertThat(ui.results[1].chapter).startsWith("Your note")
        val text = ui.results[2]
        assertThat(text.before).isEqualTo("a ")
        assertThat(text.match).isEqualTo("fire")
        assertThat(text.after).isEqualTo(" burns")
        assertThat(text.locator!!.href).isEqualTo(Url("c003.xhtml"))
    }

    @Test fun blankSearchClearsAndRunsNothing() = runTest(main.dispatcher) {
        val searcher = FakeBookSearcher()
        searcher.results["fire"] = listOf(hit("c003.xhtml", "", "fire", ""))
        val vm = reader(searcher = searcher)
        vm.search("fire")
        advanceUntilIdle()
        assertThat(vm.state.value.search.results).hasSize(1)
        vm.search("   ")
        advanceUntilIdle()
        assertThat(vm.state.value.search.results).isEmpty()
        assertThat(searcher.calls).containsExactly("fire")
    }

    @Test fun newSearchCancelsTheOld() = runTest(main.dispatcher) {
        val searcher = FakeBookSearcher()
        val slow = CompletableDeferred<List<Locator>>()
        searcher.gates["old"] = slow
        searcher.results["new"] = listOf(hit("c002.xhtml", "", "new", ""))
        val vm = reader(searcher = searcher)
        vm.search("old")
        runCurrent()
        assertThat(vm.state.value.search.running).isTrue()
        vm.search("new")
        advanceUntilIdle()
        slow.complete(listOf(hit("c001.xhtml", "", "old", "")))
        advanceUntilIdle()
        val ui = vm.state.value.search
        assertThat(ui.query).isEqualTo("new")
        assertThat(ui.running).isFalse()
        assertThat(ui.results.map { it.match }).containsExactly("new")
    }

    @Test fun tappingAResultJumpsAndMarksTheHitUntilTheNextSearchOrDismissal() = runTest(main.dispatcher) {
        val searcher = FakeBookSearcher()
        searcher.results["fire"] = listOf(hit("c003.xhtml", "", "fire", ""))
        val vm = reader(searcher = searcher)
        val seen = mutableListOf<Locator>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.navigation.collect { seen += it } }
        vm.open(Panel.SEARCH)
        vm.search("fire")
        advanceUntilIdle()
        vm.goToHit(vm.state.value.search.results.single())
        advanceUntilIdle()
        assertThat(seen.single().href).isEqualTo(Url("c003.xhtml"))
        assertThat(vm.state.value.panel).isNull()
        assertThat(vm.state.value.searchHit).isNotNull()
        vm.search("water")
        assertThat(vm.state.value.searchHit).isNull()
        vm.goToHit(BookSearchRow(hit("c003.xhtml", "", "x", ""), "", "", "x", "", null))
        vm.open(Panel.SEARCH)
        vm.open(null)
        assertThat(vm.state.value.searchHit).isNull()
    }

    @Test fun searchWithoutAServiceIsEmptyNotACrash() = runTest(main.dispatcher) {
        val searcher = FakeBookSearcher().apply { fail = true }
        val vm = reader(searcher = searcher)
        vm.search("fire")
        advanceUntilIdle()
        assertThat(vm.state.value.search.results).isEmpty()
        assertThat(vm.state.value.search.running).isFalse()
    }

    @Test fun menuTapsReachTheViewModelsChannel() = runTest(main.dispatcher) {
        val vm = reader()
        val seen = mutableListOf<SelectionAction>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.selectionActions.collect { seen += it.action } }
        runCurrent()
        SelectionBridge.dispatch(SelectionRequest(SelectionAction.SEARCH, null))
        advanceUntilIdle()
        assertThat(seen).containsExactly(SelectionAction.SEARCH)
    }

    @Test fun newestReaderOwnsTheBridgeAndClearingAnOlderOneKeepsIt() = runTest(main.dispatcher) {
        val first = reader()
        val newer = reader()
        val seen = mutableListOf<SelectionAction>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { newer.selectionActions.collect { seen += it.action } }
        runCurrent()
        first.clear()
        SelectionBridge.dispatch(SelectionRequest(SelectionAction.COPY, null))
        advanceUntilIdle()
        assertThat(seen).containsExactly(SelectionAction.COPY)
    }

    @Test fun noteSheetSurvivesMarksArrivingLate() = runTest(main.dispatcher) {
        val vm = reader()
        vm.note(selection("c002.xhtml", "word"), "word")
        // The stored marks haven't reached the screen yet.
        bookshelf.markList.value = emptyList()
        advanceUntilIdle()
        assertThat(vm.state.value.editingHighlight).isNotNull()
    }

    @Test fun aClearedReaderCannotClaimTheMenu() = runTest(main.dispatcher) {
        val vm = reader()
        vm.clear()
        assertThat(SelectionBridge.sink).isNull()
        vm.claimSelection()
        assertThat(SelectionBridge.sink).isNull()
        assertThat(vm.isCleared).isTrue()
    }

    @Test fun backToAnOlderReaderCanClaimTheMenuAgain() = runTest(main.dispatcher) {
        val a = reader()
        val b = reader()
        b.clear()
        assertThat(SelectionBridge.sink).isNull()
        val seen = mutableListOf<SelectionAction>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { a.selectionActions.collect { seen += it.action } }
        runCurrent()
        a.claimSelection()
        SelectionBridge.dispatch(SelectionRequest(SelectionAction.HIGHLIGHT, null))
        advanceUntilIdle()
        assertThat(seen).containsExactly(SelectionAction.HIGHLIGHT)
    }

    @Test fun theVoiceButtonFollowsThePlayerForThisBookOnly() = runTest(main.dispatcher) {
        val hub = app.granger.player.FakePlayerHub()
        val vm = readingReader(hub)
        assertThat(vm.state.value.voice).isEqualTo(app.granger.player.VoiceButton.IDLE)
        hub.ui.value = voiceUiFor("book:b")
        settle { vm.state.value.voice == app.granger.player.VoiceButton.READING }
        assertThat(vm.state.value.voice).isEqualTo(app.granger.player.VoiceButton.READING)
        hub.ui.value = voiceUiFor("book:b").copy(playing = false)
        settle { vm.state.value.voice == app.granger.player.VoiceButton.PAUSED }
        assertThat(vm.state.value.voice).isEqualTo(app.granger.player.VoiceButton.PAUSED)
        assertThat(vm.state.value.readingThisBook).isTrue()          // Read from here still moves the paused voice
        hub.ui.value = voiceUiFor("book:other")
        settle { vm.state.value.voice == app.granger.player.VoiceButton.IDLE }
        assertThat(vm.state.value.readingThisBook).isFalse()
        val ended = voiceUiFor("book:b")
        hub.ui.value = ended.copy(now = (ended.now as app.granger.core.player.NowPlaying.Voice).copy(ended = true), playing = false)
        testScheduler.advanceUntilIdle()
        assertThat(vm.state.value.voice).isEqualTo(app.granger.player.VoiceButton.IDLE)   // Read aloud starts it again
    }

    @Test fun pauseResumeTogglesThePlayerOnlyWhileThisBookIsRead() = runTest(main.dispatcher) {
        val hub = app.granger.player.FakePlayerHub()
        val vm = readingReader(hub)
        vm.pauseResume()
        assertThat(hub.calls).isEmpty()
        hub.ui.value = voiceUiFor("book:b")
        settle { vm.state.value.readingThisBook }
        vm.pauseResume()
        assertThat(hub.calls).containsExactly("playPause")
    }
}

class FakeBookSearcher : BookSearcher {
    val results = mutableMapOf<String, List<Locator>>()
    val gates = mutableMapOf<String, CompletableDeferred<List<Locator>>>()
    val calls = mutableListOf<String>()
    var fail = false
    override suspend fun search(publication: Publication, query: String, max: Int): List<Locator> {
        calls += query
        if (fail) throw IllegalStateException("no search service")
        gates[query]?.let { return it.await() }
        return results[query].orEmpty()
    }
}
