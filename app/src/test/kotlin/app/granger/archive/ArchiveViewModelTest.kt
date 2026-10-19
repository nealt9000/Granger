package app.granger.archive

import app.granger.FakeBookshelf
import app.granger.FakeDownloads
import app.granger.FakePrefs
import app.granger.FakeVolumes
import app.granger.MainDispatcherRule
import app.granger.core.archive.ArchiveItem
import app.granger.core.archive.ArchiveKind
import app.granger.core.archive.ArchiveOrgClient
import app.granger.core.archive.ArchiveQuery
import app.granger.core.archive.ArchiveShelf
import app.granger.core.archive.AudioOffer
import app.granger.core.archive.Offer
import app.granger.core.archive.TextKind
import app.granger.core.downloads.DownloadState
import app.granger.core.downloads.EnqueueResult
import app.granger.core.downloads.ItemKind
import app.granger.core.media.DownloadedMediaScanner
import app.granger.core.media.FileTags
import app.granger.core.media.MediaManifest
import app.granger.core.media.MediaManifests
import app.granger.core.media.TagReader
import app.granger.media.MediaDownloader
import app.granger.media.MediaDownloads
import app.granger.media.MediaFixtures
import app.granger.music.FakeMediaLibrary
import app.granger.record
import app.granger.sources.ItemStatus
import app.granger.volume
import com.google.common.truth.Truth.assertThat
import java.io.File
import java.io.IOException
import java.time.LocalDate
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ArchiveViewModelTest {
    @get:Rule val main = MainDispatcherRule()
    @get:Rule val tmp = TemporaryFolder()

    private val epubId = "contributionletu0037vari_w0s3"
    private val scanId = "scientific-american-1881-10-15"
    private val lendingId = "lbg.vol.xviii.p.1.agriculturaljour0000unse_b7i9"

    /** Every URL the client asked for, in order. */
    private val requests = CopyOnWriteArrayList<HttpUrl>()
    private var searchBody: (q: String, page: Int) -> String = { _, _ -> resource("search-steam-engine.json") }
    private var itemBody: (id: String) -> String = { id ->
        when (id) {
            epubId -> resource("item-open-epub.json")
            scanId -> resource("item-scan-only.json")
            lendingId -> resource("item-lending.json")
            MODERN -> MODERN_JSON
            TAGGED -> TAGGED_JSON
            HOLST -> resource("item-holst-planets.json")
            AUDIO_LENDING -> resource("item-audio-lending.json")
            else -> "{}"
        }
    }
    @Volatile private var offline = false

    private val server = Interceptor { chain ->
        val url = chain.request().url
        requests += url
        if (offline) throw IOException("offline")
        val body = if (url.encodedPath.startsWith("/metadata/")) {
            itemBody(url.pathSegments.last())
        } else {
            searchBody(url.queryParameter("q").orEmpty(), url.queryParameter("page")!!.toInt())
        }
        Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).message("").code(200)
            .body(body.toResponseBody()).build()
    }
    private val client = ArchiveOrgClient(OkHttpClient.Builder().addInterceptor(server).build(), "Granger/1.0 (+test)")

    private val downloads = FakeDownloads()
    private val bookshelf = FakeBookshelf()
    private val prefs = FakeArchivePrefs()
    private val volumes = FakeVolumes(listOf(volume("/internal", "Internal storage"), volume("/sd", "SD card")))
    private val downloadPrefs = FakePrefs()
    private val sidecars by lazy { tmp.newFolder("sidecars") }

    private fun resource(name: String) =
        javaClass.classLoader!!.getResourceAsStream("archive/$name")!!.readBytes().decodeToString()

    private fun searches() = requests.filter { it.encodedPath == "/advancedsearch.php" }

    /** The client hops to the real IO dispatcher, so run the test scheduler until [done] holds (or give up). */
    private fun TestScope.settle(done: () -> Boolean) {
        repeat(300) {
            testScheduler.advanceUntilIdle()
            if (done()) return
            Thread.sleep(10)
        }
        testScheduler.advanceUntilIdle()
    }

    /** Like [settle] but without moving virtual time, so a pending debounce stays pending. */
    private fun TestScope.settleNow(done: () -> Boolean) {
        repeat(300) {
            testScheduler.runCurrent()
            if (done()) return
            Thread.sleep(10)
        }
    }

    private fun TestScope.viewModel(settle: Boolean = true): ArchiveViewModel {
        val vm = ArchiveViewModel(
            client, downloads, bookshelf, prefs, volumes, downloadPrefs, sidecarDir = { sidecars },
            appScope = this, io = main.dispatcher,
        )
        backgroundScope.launch { vm.ui.collect {} }
        if (settle) settle { !vm.ui.value.loading && searches().isNotEmpty() }
        return vm
    }

    @Test fun theSearchFieldTextUpdatesSynchronously() = runTest(main.dispatcher) {
        // ui is combine(...).stateIn(...), so ui.value.query lags a dispatch behind each keystroke; a text field
        // fed from it gets stale values back and scrambles fast typing. The field reads searchText instead.
        val vm = viewModel()
        vm.setQuery("s"); vm.setQuery("st"); vm.setQuery("ste")
        assertThat(vm.searchText).isEqualTo("ste")
        vm.selectShelf(ArchiveShelf.MANUALS)
        assertThat(vm.searchText).isEmpty()
    }

    @Test fun startsOnMostDownloadedFiltered() = runTest(main.dispatcher) {
        val vm = viewModel()
        assertThat(searches().first().queryParameter("q")).isEqualTo(ArchiveQuery.build("", ArchiveShelf.MOST_DOWNLOADED, true))
        assertThat(searches().first().queryParameter("page")).isEqualTo("1")
        val ui = vm.ui.value
        assertThat(ui.filtered).isTrue()
        assertThat(ui.shelf).isEqualTo(ArchiveShelf.MOST_DOWNLOADED)
        assertThat(ui.tab).isEqualTo(ArchiveTab.BROWSE)
        assertThat(ui.total).isEqualTo(1341)
        assertThat(ui.rows.first().identifier).isEqualTo("HistoryOfTheFrickCompany")
        // The search-time guess: OCR text, with a PDF alongside.
        val preview = ui.rows.first().preview as Offer.Download
        assertThat(preview.kind).isEqualTo(TextKind.OCR_TEXT)
        assertThat(preview.alsoPdf).isTrue()
        assertThat(ui.canLoadMore).isTrue()
        assertThat(ui.error).isNull()
    }

    @Test fun typingSearchesWithoutAShelfAfterTheDebounce() = runTest(main.dispatcher) {
        val vm = viewModel()
        requests.clear()
        vm.setQuery("steam")
        testScheduler.advanceTimeBy(200)
        vm.setQuery("steam engine")
        testScheduler.advanceTimeBy(399)
        testScheduler.runCurrent()
        assertThat(vm.ui.value.query).isEqualTo("steam engine")
        assertThat(searches()).isEmpty()
        testScheduler.advanceTimeBy(2)
        testScheduler.runCurrent()
        settle { searches().isNotEmpty() && !vm.ui.value.loading }
        assertThat(searches().map { it.queryParameter("q") }).containsExactly(ArchiveQuery.build("steam engine", null, true))
        assertThat(vm.ui.value.shelf).isNull()
    }

    @Test fun selectingAShelfClearsTheQuery() = runTest(main.dispatcher) {
        val vm = viewModel()
        vm.setQuery("steam")
        settle { searches().size == 2 && !vm.ui.value.loading }
        vm.selectShelf(ArchiveShelf.MANUALS)
        settle { searches().size == 3 && !vm.ui.value.loading }
        assertThat(vm.ui.value.query).isEmpty()
        assertThat(vm.ui.value.shelf).isEqualTo(ArchiveShelf.MANUALS)
        assertThat(searches().last().queryParameter("q")).isEqualTo(ArchiveQuery.build("", ArchiveShelf.MANUALS, true))
    }

    @Test fun acceptingTheWaiverRerunsUnfiltered() = runTest(main.dispatcher) {
        val vm = viewModel()
        vm.acceptWaiver()
        settle { searches().size == 2 && !vm.ui.value.loading }
        assertThat(prefs.waiverAccepted.value).isTrue()
        val q = searches().last().queryParameter("q")!!
        assertThat(q).doesNotContain("year:[* TO 1930]")
        assertThat(q).isEqualTo(ArchiveQuery.build("", ArchiveShelf.MOST_DOWNLOADED, false))
        assertThat(vm.ui.value.filtered).isFalse()
    }

    @Test fun staleResultsFromTheOtherViewAreDropped() = runTest(main.dispatcher) {
        val release = CountDownLatch(1)
        val filteredServed = CountDownLatch(1)
        searchBody = { q, _ ->
            if (ArchiveQuery.PUBLIC_DOMAIN in q) {
                release.await(5, TimeUnit.SECONDS)
                searchJson(1, "filteredDoc").also { filteredServed.countDown() }
            } else {
                searchJson(1, "openDoc")
            }
        }
        val vm = viewModel(settle = false)
        settle { searches().isNotEmpty() }
        vm.acceptWaiver()
        settle { vm.ui.value.rows.isNotEmpty() }
        assertThat(vm.ui.value.rows.map { it.identifier }).containsExactly("openDoc")
        // Now the filtered response from before the waiver lands.
        release.countDown()
        assertThat(filteredServed.await(5, TimeUnit.SECONDS)).isTrue()
        repeat(20) { Thread.sleep(10); testScheduler.advanceUntilIdle() }
        val ui = vm.ui.value
        assertThat(ui.rows.map { it.identifier }).containsExactly("openDoc")
        assertThat(ui.filtered).isFalse()
        assertThat(ui.loading).isFalse()
    }

    @Test fun loadMoreAppendsTheNextPage() = runTest(main.dispatcher) {
        searchBody = { _, page -> if (page == 1) searchJson(4, "a", "b") else searchJson(4, "c", "d") }
        val vm = viewModel()
        assertThat(vm.ui.value.rows.map { it.identifier }).containsExactly("a", "b").inOrder()
        assertThat(vm.ui.value.canLoadMore).isTrue()
        vm.loadMore()
        settle { vm.ui.value.rows.size == 4 }
        assertThat(searches().last().queryParameter("page")).isEqualTo("2")
        assertThat(vm.ui.value.rows.map { it.identifier }).containsExactly("a", "b", "c", "d").inOrder()
        assertThat(vm.ui.value.canLoadMore).isFalse()
        vm.loadMore()
        settle { false }
        assertThat(searches()).hasSize(2)
    }

    @Test fun loadMoreDuringTheDebounceStaysOnTheShownQuery() = runTest(main.dispatcher) {
        searchBody = { q, page ->
            if ("steam" in q) searchJson(4, "s1", "s2") else if (page == 1) searchJson(4, "a", "b") else searchJson(4, "c", "d")
        }
        val vm = viewModel()
        vm.setQuery("steam")
        testScheduler.advanceTimeBy(100)
        testScheduler.runCurrent()
        vm.loadMore()
        settleNow { vm.ui.value.rows.size == 4 }
        assertThat(searches().none { "steam" in it.queryParameter("q")!! }).isTrue()
        assertThat(searches().last().queryParameter("q")).isEqualTo(ArchiveQuery.build("", ArchiveShelf.MOST_DOWNLOADED, true))
        assertThat(searches().last().queryParameter("page")).isEqualTo("2")
        assertThat(vm.ui.value.rows.map { it.identifier }).containsExactly("a", "b", "c", "d").inOrder()
        assertThat(vm.ui.value.query).isEqualTo("steam")
        // The debounce then runs the typed search from page 1, replacing the rows.
        settle { vm.ui.value.rows.map { it.identifier } == listOf("s1", "s2") }
        assertThat(vm.ui.value.rows.map { it.identifier }).containsExactly("s1", "s2").inOrder()
        assertThat(searches().last().queryParameter("q")).isEqualTo(ArchiveQuery.build("steam", null, true))
        assertThat(searches().last().queryParameter("page")).isEqualTo("1")
    }

    @Test fun theWaiverRerunDuringTheDebounceStaysOnTheShownQuery() = runTest(main.dispatcher) {
        val vm = viewModel()
        vm.setQuery("steam")
        testScheduler.advanceTimeBy(100)
        testScheduler.runCurrent()
        vm.acceptWaiver()
        settleNow { searches().size == 2 }
        assertThat(searches()[1].queryParameter("q")).isEqualTo(ArchiveQuery.build("", ArchiveShelf.MOST_DOWNLOADED, false))
    }

    @Test fun aThrowingEnqueueLeavesNoSidecar() = runTest(main.dispatcher) {
        downloads.onEnqueue = { throw IllegalStateException("database closed") }
        val vm = viewModel()
        vm.get(epubId)
        settle { vm.ui.value.notice != null }
        assertThat(File(sidecars, "$epubId.json").exists()).isFalse()
    }

    @Test fun openItemFillsTheSheetWithOfferAndSize() = runTest(main.dispatcher) {
        val vm = viewModel()
        val gate = CountDownLatch(1)
        val serve = itemBody
        itemBody = { id -> gate.await(5, TimeUnit.SECONDS); serve(id) }
        vm.openItem(scanId)
        testScheduler.runCurrent()
        assertThat(vm.ui.value.sheet!!.loading).isTrue()
        gate.countDown()
        settle { vm.ui.value.sheet?.loading == false }
        val sheet = vm.ui.value.sheet!!
        assertThat(sheet.identifier).isEqualTo(scanId)
        assertThat(sheet.error).isNull()
        assertThat(sheet.item!!.identifier).isEqualTo(scanId)
        val offer = sheet.offer as Offer.Download
        assertThat(offer.kind).isEqualTo(TextKind.OCR_TEXT)
        assertThat(offer.alsoPdf).isTrue()
        assertThat(sheet.sizeBytes).isEqualTo(251_326L)
        assertThat(sheet.status).isEqualTo(ItemStatus.NotInstalled)
        vm.closeItem()
        testScheduler.advanceUntilIdle()
        assertThat(vm.ui.value.sheet).isNull()
    }

    @Test fun getQueuesTheSpecAndWritesTheSidecar() = runTest(main.dispatcher) {
        val sidecar = File(sidecars, "$epubId.json")
        var sidecarAtEnqueue = false
        downloads.onEnqueue = { sidecarAtEnqueue = sidecar.exists() }
        val vm = viewModel()
        vm.get(epubId)
        settle { downloads.enqueuedSpecs.isNotEmpty() }
        val spec = downloads.enqueuedSpecs.single()
        assertThat(spec.itemId).isEqualTo("archive-org:$epubId")
        assertThat(spec.kind).isEqualTo(ItemKind.BOOK)
        assertThat(spec.relativePath).isEqualTo("books/archive-org/$epubId.epub")
        assertThat(downloads.enqueued.single().second).isEqualTo(File("/internal"))
        assertThat(sidecarAtEnqueue).isTrue()
        assertThat(sidecar.exists()).isTrue()
        assertThat(ArchiveDownloads.readSidecar(sidecar)!!.second).isEqualTo(TextKind.EPUB)
        assertThat(vm.ui.value.notice).isNull()
    }

    @Test fun getUsesTheSheetItemWithoutFetchingAgain() = runTest(main.dispatcher) {
        val vm = viewModel()
        vm.openItem(epubId)
        settle { vm.ui.value.sheet?.loading == false }
        val fetches = requests.count { it.encodedPath.startsWith("/metadata/") }
        vm.get(epubId)
        settle { downloads.enqueuedSpecs.isNotEmpty() }
        assertThat(requests.count { it.encodedPath.startsWith("/metadata/") }).isEqualTo(fetches)
        assertThat(vm.ui.value.sheet!!.status).isEqualTo(ItemStatus.Queued(wifiOnly = true))
    }

    @Test fun getGoesToTheChosenVolume() = runTest(main.dispatcher) {
        downloadPrefs.volumePath = "/sd"
        downloadPrefs.wifiOnly = false
        val vm = viewModel()
        vm.get(epubId)
        settle { downloads.enqueuedSpecs.isNotEmpty() }
        assertThat(downloads.enqueued.single().second).isEqualTo(File("/sd"))
        assertThat(downloads.list.value.single().wifiOnly).isFalse()
        // The sidecar stays on the internal volume's dir (R2).
        assertThat(File(sidecars, "$epubId.json").exists()).isTrue()
    }

    @Test fun filteredGetRefusesANonPublicDomainItem() = runTest(main.dispatcher) {
        val vm = viewModel()
        vm.get(MODERN)
        settle { vm.ui.value.notice != null }
        assertThat(vm.ui.value.notice).isEqualTo("This item wasn't published in 1930 or earlier. Show everything to download it.")
        assertThat(downloads.enqueuedSpecs).isEmpty()
        assertThat(File(sidecars, "$MODERN.json").exists()).isFalse()
        vm.dismissNotice()
        vm.acceptWaiver()
        vm.get(MODERN)
        settle { downloads.enqueuedSpecs.isNotEmpty() }
        assertThat(downloads.enqueuedSpecs.single().itemId).isEqualTo("archive-org:$MODERN")
    }

    @Test fun filteredGetRefusesAModernItemTaggedPublicDomain() = runTest(main.dispatcher) {
        // Changed 2026-10-17 after the Pixel 6 walkthrough: uploader licence tags are not trusted.
        val vm = viewModel()
        vm.get(TAGGED)
        settle { vm.ui.value.notice != null }
        assertThat(vm.ui.value.notice).isEqualTo("This item wasn't published in 1930 or earlier. Show everything to download it.")
        assertThat(downloads.enqueuedSpecs).isEmpty()
    }

    @Test fun filteredSearchesFilterByDateOnly() = runTest(main.dispatcher) {
        viewModel()
        val q = searches().last().queryParameter("q")!!
        assertThat(q).endsWith(" AND year:[* TO 1930]")
        assertThat(q).doesNotContain("licenseurl")
    }

    @Test fun withdrawingTheWaiverRefusesAnItemOpenedWhileUnfiltered() = runTest(main.dispatcher) {
        prefs.accept()
        val vm = viewModel()
        vm.openItem(MODERN)
        settle { vm.ui.value.sheet?.loading == false }
        prefs.withdraw()
        vm.get(MODERN)
        settle { vm.ui.value.notice != null }
        assertThat(vm.ui.value.notice).isEqualTo("This item wasn't published in 1930 or earlier. Show everything to download it.")
        assertThat(downloads.enqueuedSpecs).isEmpty()
    }

    @Test fun getOnALendingItemQueuesNothing() = runTest(main.dispatcher) {
        val vm = viewModel()
        vm.get(lendingId)
        settle { vm.ui.value.notice != null }
        assertThat(vm.ui.value.notice).isEqualTo("Nothing on this item can be downloaded.")
        assertThat(downloads.enqueuedSpecs).isEmpty()
        assertThat(sidecars.listFiles()!!.toList()).isEmpty()
    }

    @Test fun overCapShowsTheLimitNotice() = runTest(main.dispatcher) {
        downloads.nextResult = EnqueueResult.OverCap(10_000_000_000, 11_000_000_000)
        val vm = viewModel()
        vm.get(epubId)
        settle { vm.ui.value.notice != null }
        assertThat(vm.ui.value.notice).contains("would bring your downloads to")
        assertThat(vm.ui.value.notice).contains("10 GB limit")
        // No orphan sidecar is left behind.
        assertThat(File(sidecars, "$epubId.json").exists()).isFalse()
    }

    @Test fun noSpaceExplainsWhere() = runTest(main.dispatcher) {
        downloads.nextResult = EnqueueResult.NoSpace(2_000_000_000)
        val vm = viewModel()
        vm.get(epubId)
        settle { vm.ui.value.notice != null }
        assertThat(vm.ui.value.notice).contains("Not enough space on Internal storage")
        assertThat(vm.ui.value.notice).contains("2.0 GB")
        assertThat(File(sidecars, "$epubId.json").exists()).isFalse()
        vm.dismissNotice()
        testScheduler.advanceUntilIdle()
        assertThat(vm.ui.value.notice).isNull()
    }

    @Test fun anAlreadyActiveDownloadKeepsItsSidecar() = runTest(main.dispatcher) {
        downloads.nextResult = EnqueueResult.AlreadyActive
        val vm = viewModel()
        vm.get(epubId)
        settle { downloads.enqueuedSpecs.isNotEmpty() }
        testScheduler.advanceUntilIdle()
        assertThat(File(sidecars, "$epubId.json").exists()).isTrue()
        assertThat(vm.ui.value.notice).isNull()
    }

    @Test fun anAlreadyInstalledItemKeepsItsSidecar() = runTest(main.dispatcher) {
        // The installed book's sidecar is what a later repair files it with.
        downloads.nextResult = EnqueueResult.AlreadyInstalled
        val vm = viewModel()
        vm.get(epubId)
        settle { downloads.enqueuedSpecs.isNotEmpty() }
        testScheduler.advanceUntilIdle()
        assertThat(File(sidecars, "$epubId.json").exists()).isTrue()
    }

    @Test fun removeAndCancelDeleteTheSidecar() = runTest(main.dispatcher) {
        val vm = viewModel()
        val removed = File(sidecars, "$epubId.json").apply { writeText("{}") }
        val cancelled = File(sidecars, "a.json").apply { writeText("{}") }
        val kept = File(sidecars, "b.json").apply { writeText("{}") }
        vm.remove(epubId)
        vm.cancel("a")
        testScheduler.advanceUntilIdle()
        assertThat(removed.exists()).isFalse()
        assertThat(cancelled.exists()).isFalse()
        assertThat(kept.exists()).isTrue()
    }

    @Test fun installedTabLabelsTextDownloadsFromTheirSidecar() = runTest(main.dispatcher) {
        // Both are .txt on disk; only the sidecar knows one is OCR. The third has no sidecar: the extension decides.
        ArchiveDownloads.writeSidecar(sidecars, ArchiveItem("ocr", "OCR", "", "", 1900, emptyList(), "", "", "", false, emptyList()), TextKind.OCR_TEXT)
        ArchiveDownloads.writeSidecar(sidecars, ArchiveItem("plain", "Plain", "", "", 1900, emptyList(), "", "", "", false, emptyList()), TextKind.TEXT)
        downloads.list.value = listOf("ocr", "plain", "bare").map { id ->
            record("archive-org:$id", DownloadState.INSTALLED, kind = ItemKind.BOOK, filePath = "/internal/books/archive-org/$id.txt", title = id)
        }
        val vm = viewModel()
        vm.setTab(ArchiveTab.INSTALLED)
        testScheduler.advanceUntilIdle()
        val kinds = vm.ui.value.rows.associate { it.identifier to (it.preview as Offer.Download).kind }
        assertThat(kinds).containsExactly("ocr", TextKind.OCR_TEXT, "plain", TextKind.TEXT, "bare", TextKind.TEXT)
    }

    @Test fun offlineSearchShowsAMessageAndInstalledStillWorks() = runTest(main.dispatcher) {
        offline = true
        downloads.list.value = listOf(
            record(
                "archive-org:x", DownloadState.INSTALLED, kind = ItemKind.BOOK,
                filePath = "/internal/books/archive-org/x.epub", title = "The X Book",
            ),
            record("med", DownloadState.INSTALLED),
        )
        val vm = viewModel()
        assertThat(vm.ui.value.error).contains("Couldn't reach archive.org")
        assertThat(vm.ui.value.rows).isEmpty()
        assertThat(vm.ui.value.loading).isFalse()
        vm.setTab(ArchiveTab.INSTALLED)
        testScheduler.advanceUntilIdle()
        val row = vm.ui.value.rows.single()
        assertThat(row.identifier).isEqualTo("x")
        assertThat(row.title).isEqualTo("The X Book")
        assertThat(row.status).isEqualTo(ItemStatus.Installed)
        assertThat((row.preview as Offer.Download).kind).isEqualTo(TextKind.EPUB)
        // Back online, Retry fills Browse.
        offline = false
        vm.setTab(ArchiveTab.BROWSE)
        vm.retry()
        settle { vm.ui.value.rows.isNotEmpty() }
        assertThat(vm.ui.value.error).isNull()
    }

    @Test fun openItemOfflineShowsTheError() = runTest(main.dispatcher) {
        val vm = viewModel()
        offline = true
        vm.openItem(epubId)
        settle { vm.ui.value.sheet?.loading == false }
        assertThat(vm.ui.value.sheet!!.error).contains("Couldn't reach archive.org")
        assertThat(vm.ui.value.sheet!!.item).isNull()
    }

    @Test fun rowStatusFollowsTheDownloadRecords() = runTest(main.dispatcher) {
        searchBody = { _, _ -> searchJson(2, "a", "b") }
        downloads.list.value = listOf(
            record("archive-org:a", DownloadState.RUNNING, kind = ItemKind.BOOK, sizeBytes = 1_000, bytesDone = 500),
        )
        val vm = viewModel()
        assertThat(vm.ui.value.rows.first { it.identifier == "a" }.status).isEqualTo(ItemStatus.Downloading(0.5f))
        assertThat(vm.ui.value.rows.first { it.identifier == "b" }.status).isEqualTo(ItemStatus.NotInstalled)
        downloads.list.value = listOf(record("archive-org:a", DownloadState.INSTALLED, kind = ItemKind.BOOK))
        testScheduler.advanceUntilIdle()
        assertThat(vm.ui.value.rows.first { it.identifier == "a" }.status).isEqualTo(ItemStatus.Installed)
        downloads.list.value = listOf(record("archive-org:a", DownloadState.FAILED, kind = ItemKind.BOOK, error = "Checksum mismatch"))
        testScheduler.advanceUntilIdle()
        assertThat(vm.ui.value.rows.first { it.identifier == "a" }.status).isEqualTo(ItemStatus.Failed("Checksum mismatch"))
    }

    @Test fun removeDeletesTheDownloadThenTheBook() = runTest(main.dispatcher) {
        val vm = viewModel()
        vm.remove(epubId)
        vm.cancel("a")
        vm.retryDownload("b")
        testScheduler.advanceUntilIdle()
        assertThat(downloads.calls).containsAtLeast("remove:archive-org:$epubId", "cancel:archive-org:a", "retry:archive-org:b")
        assertThat(bookshelf.removed).containsExactly("book:archive-org:$epubId")
    }

    // --- Archive.org audio (Plan M2, Task 8) ---

    private val mediaRoot by lazy { tmp.newFolder("media") }
    private val mediaVolumes by lazy { FakeVolumes(listOf(volume(mediaRoot.path, "Internal storage"))) }
    private val today = LocalDate.of(2026, 10, 19)

    private fun TestScope.mediaDownloader(): MediaDownloader {
        val library = FakeMediaLibrary()
        val scanner = DownloadedMediaScanner({ listOf(mediaRoot) }, library, TagReader { _, _ -> FileTags(null, emptyList()) })
        return MediaDownloader(downloads, scanner, library, mediaVolumes, downloadPrefs, backgroundScope, io = main.dispatcher)
    }

    private fun TestScope.audioViewModel(kind: ArchiveKind, settle: Boolean = true): ArchiveViewModel {
        val vm = ArchiveViewModel(
            client, downloads, bookshelf, prefs, volumes, downloadPrefs, sidecarDir = { sidecars },
            appScope = this, io = main.dispatcher, kind = kind, media = mediaDownloader(), today = { today },
        )
        backgroundScope.launch { vm.ui.collect {} }
        if (settle) settle { !vm.ui.value.loading && (searches().isNotEmpty() || vm.ui.value.waiverNeeded) }
        return vm
    }

    private fun audioSearch(kind: ArchiveKind) {
        searchBody = { q, _ ->
            when {
                HOLST in q -> audioSearchJson(1, HOLST)
                kind == ArchiveKind.MUSIC -> resource("search-audio-music.json")
                else -> resource("search-audio-spoken.json")
            }
        }
    }

    private fun itemFetches() = requests.count { it.encodedPath.startsWith("/metadata/") }

    private val holstGroup = "media:archive-org:$HOLST"

    @Test fun musicStartsOnItsMostDownloadedShelfFiltered() = runTest(main.dispatcher) {
        audioSearch(ArchiveKind.MUSIC)
        val vm = audioViewModel(ArchiveKind.MUSIC)
        assertThat(searches().single().queryParameter("q"))
            .isEqualTo(ArchiveQuery.build("", ArchiveShelf.MUSIC_MOST_DOWNLOADED, true, ArchiveKind.MUSIC, today))
        val ui = vm.ui.value
        assertThat(ui.kind).isEqualTo(ArchiveKind.MUSIC)
        assertThat(ui.shelf).isEqualTo(ArchiveShelf.MUSIC_MOST_DOWNLOADED)
        assertThat(ui.filtered).isTrue()
        assertThat(ui.filterYear).isEqualTo(1926)
        assertThat(ui.waiverNeeded).isFalse()
        assertThat(ui.rows).isNotEmpty()
        // Rows preview from the search formats: AlessandroMoreschi lists Ogg Vorbis and 64Kbps MP3.
        assertThat((ui.rows.first().audio as AudioOffer.Tracks).format).isEqualTo("Ogg Vorbis")
        // Clearing the box goes back to the kind's own first shelf.
        vm.setQuery("caruso")
        settle { searches().size == 2 && !vm.ui.value.loading }
        vm.setQuery("")
        settle { searches().size == 3 && !vm.ui.value.loading }
        assertThat(vm.ui.value.shelf).isEqualTo(ArchiveShelf.MUSIC_MOST_DOWNLOADED)
    }

    @Test fun acceptingTheWaiverLiftsTheAudioFilter() = runTest(main.dispatcher) {
        audioSearch(ArchiveKind.MUSIC)
        val vm = audioViewModel(ArchiveKind.MUSIC)
        vm.acceptWaiver()
        settle { searches().size == 2 && !vm.ui.value.loading }
        val q = searches().last().queryParameter("q")!!
        assertThat(q).doesNotContain("year:[")
        assertThat(q).isEqualTo(ArchiveQuery.build("", ArchiveShelf.MUSIC_MOST_DOWNLOADED, false, ArchiveKind.MUSIC, today))
        assertThat(vm.ui.value.filtered).isFalse()
    }

    @Test fun filteredAudioGetRefusesARecordingAfterTheCutoff() = runTest(main.dispatcher) {
        audioSearch(ArchiveKind.MUSIC)
        val vm = audioViewModel(ArchiveKind.MUSIC)
        vm.get(HOLST)
        settle { vm.ui.value.notice != null }
        assertThat(vm.ui.value.notice).isEqualTo("This recording wasn't published before 1926. Show everything to download it.")
        assertThat(downloads.enqueuedGroups).isEmpty()
        assertThat(File(mediaRoot, "music/archive-org/$HOLST").exists()).isFalse()
    }

    @Test fun waiverWithdrawnAfterBrowsingStillRefusesAtGet() = runTest(main.dispatcher) {
        prefs.accept()
        audioSearch(ArchiveKind.MUSIC)
        val vm = audioViewModel(ArchiveKind.MUSIC)
        vm.openItem(HOLST)
        settle { vm.ui.value.sheet?.loading == false }
        assertThat(vm.ui.value.sheetAudio).isInstanceOf(AudioOffer.Tracks::class.java)
        prefs.withdraw()
        vm.get(HOLST)
        settle { vm.ui.value.notice != null }
        assertThat(vm.ui.value.notice).isEqualTo("This recording wasn't published before 1926. Show everything to download it.")
        assertThat(downloads.enqueuedGroups).isEmpty()
    }

    @Test fun unfilteredAudioGetQueuesOneGroup() = runTest(main.dispatcher) {
        prefs.accept()
        audioSearch(ArchiveKind.MUSIC)
        val vm = audioViewModel(ArchiveKind.MUSIC)
        vm.get(HOLST)
        settle { downloads.enqueuedGroups.isNotEmpty() }
        val (group, specs, root) = downloads.enqueuedGroups.single()
        assertThat(group).isEqualTo(holstGroup)
        assertThat(specs).hasSize(8)
        assertThat(specs.all { it.kind == ItemKind.AUDIO && it.relativePath.startsWith("music/archive-org/$HOLST/") }).isTrue()
        assertThat(root).isEqualTo(mediaRoot)
        assertThat(MediaManifests.read(File(mediaRoot, "music/archive-org/$HOLST"))!!.kind).isEqualTo(MediaManifest.ALBUM)
        settle { vm.ui.value.rows.isNotEmpty() }
        assertThat(vm.ui.value.notice).isNull()
    }

    @Test fun spokenWordBeforeTheWaiverShowsOnlyTheNote() = runTest(main.dispatcher) {
        audioSearch(ArchiveKind.SPOKEN_WORD)
        val vm = audioViewModel(ArchiveKind.SPOKEN_WORD)
        // Typing, a shelf, Load more and Retry send nothing either.
        vm.setQuery("poems")
        testScheduler.advanceTimeBy(500)
        vm.selectShelf(ArchiveShelf.SPOKEN_POETRY)
        vm.loadMore()
        vm.retry()
        settle { false }
        assertThat(requests).isEmpty()
        val ui = vm.ui.value
        assertThat(ui.waiverNeeded).isTrue()
        assertThat(ui.filtered).isTrue()
        assertThat(ui.rows).isEmpty()
        assertThat(ui.loading).isFalse()
        assertThat(ui.error).isNull()
        assertThat(ui.canLoadMore).isFalse()
    }

    @Test fun acceptingTheWaiverRevealsSpokenWordResults() = runTest(main.dispatcher) {
        audioSearch(ArchiveKind.SPOKEN_WORD)
        val vm = audioViewModel(ArchiveKind.SPOKEN_WORD)
        assertThat(requests).isEmpty()
        vm.acceptWaiver()
        settle { searches().isNotEmpty() && !vm.ui.value.loading }
        assertThat(searches().single().queryParameter("q"))
            .isEqualTo(ArchiveQuery.build("", ArchiveShelf.SPOKEN_MOST_DOWNLOADED, false, ArchiveKind.SPOKEN_WORD))
        val ui = vm.ui.value
        assertThat(ui.waiverNeeded).isFalse()
        assertThat(ui.rows.first().identifier).isEqualTo("nguyenngocnganfull")
        assertThat(ui.total).isEqualTo(53_721)
    }

    @Test fun filteredSpokenWordGetIsRefused() = runTest(main.dispatcher) {
        prefs.accept()
        audioSearch(ArchiveKind.SPOKEN_WORD)
        val vm = audioViewModel(ArchiveKind.SPOKEN_WORD)
        assertThat(vm.ui.value.rows).isNotEmpty()
        val id = vm.ui.value.rows.first().identifier
        prefs.withdraw()
        settle { vm.ui.value.waiverNeeded }
        val sent = requests.size
        vm.get(id)
        settle { vm.ui.value.notice != null }
        assertThat(vm.ui.value.notice).isEqualTo("Show everything to download archive.org spoken word.")
        assertThat(requests).hasSize(sent)
        assertThat(itemFetches()).isEqualTo(0)
        assertThat(downloads.enqueuedGroups).isEmpty()
        assertThat(vm.ui.value.rows).isEmpty()
    }

    @Test fun spokenWordGetMakesAnAudiobookManifest() = runTest(main.dispatcher) {
        prefs.accept()
        audioSearch(ArchiveKind.SPOKEN_WORD)
        val vm = audioViewModel(ArchiveKind.SPOKEN_WORD)
        vm.get(HOLST)
        settle { downloads.enqueuedGroups.isNotEmpty() }
        val dir = File(mediaRoot, "audiobooks/archive-org/$HOLST")
        val manifest = MediaManifests.read(dir)!!
        assertThat(manifest.kind).isEqualTo(MediaManifest.AUDIOBOOK)
        assertThat(MediaManifests.relativeDir(manifest)).isEqualTo("audiobooks/archive-org/$HOLST")
        assertThat(downloads.enqueuedGroups.single().second.all { it.relativePath.startsWith("audiobooks/archive-org/$HOLST/") }).isTrue()
        assertThat(File(mediaRoot, "music/archive-org/$HOLST").exists()).isFalse()
    }

    @Test fun lendingAudioOffersBorrowOnly() = runTest(main.dispatcher) {
        audioSearch(ArchiveKind.MUSIC)
        val vm = audioViewModel(ArchiveKind.MUSIC)
        vm.openItem(AUDIO_LENDING)
        settle { vm.ui.value.sheet?.loading == false }
        assertThat(vm.ui.value.sheetAudio).isEqualTo(AudioOffer.LendOnly)
        assertThat(vm.ui.value.sheet!!.offer).isNull()
        vm.get(AUDIO_LENDING)
        settle { vm.ui.value.notice != null }
        assertThat(vm.ui.value.notice).isEqualTo("Nothing on this item can be downloaded.")
        assertThat(downloads.enqueuedGroups).isEmpty()
    }

    @Test fun audioRowStatusFollowsTheGroup() = runTest(main.dispatcher) {
        audioSearch(ArchiveKind.MUSIC)
        searchBody = { _, _ -> audioSearchJson(2, HOLST, "other") }
        val manifest = MediaFixtures.holst()
        val dir = File(mediaRoot, MediaManifests.relativeDir(manifest))
        MediaManifests.write(dir, manifest)
        val specs = MediaDownloads.specs(manifest)
        fun rec(i: Int, state: DownloadState) = record(
            specs[i].itemId, state, kind = ItemKind.AUDIO, filePath = File(mediaRoot, specs[i].relativePath).path,
            sizeBytes = 100, bytesDone = if (state == DownloadState.INSTALLED) 100 else 0, title = specs[i].title,
        ).copy(groupId = holstGroup)
        // The cover and two tracks in (3 of 8 files), the next running, the rest queued.
        downloads.list.value = (0..7).map { i ->
            rec(i, when { i < 3 -> DownloadState.INSTALLED; i == 3 -> DownloadState.RUNNING; else -> DownloadState.QUEUED })
        }
        val vm = audioViewModel(ArchiveKind.MUSIC)
        assertThat(vm.ui.value.rows.first { it.identifier == HOLST }.status).isInstanceOf(ItemStatus.Downloading::class.java)
        assertThat(vm.ui.value.rows.first { it.identifier == "other" }.status).isEqualTo(ItemStatus.NotInstalled)
        // Cancelled part-way: the unfinished records go, and the manifest still says seven tracks.
        downloads.list.value = (0..2).map { rec(it, DownloadState.INSTALLED) }
        testScheduler.advanceUntilIdle()
        assertThat(vm.ui.value.rows.first { it.identifier == HOLST }.status).isEqualTo(ItemStatus.Partial(2, 7))
        vm.openItem(HOLST)
        settle { vm.ui.value.sheet?.loading == false }
        assertThat(vm.ui.value.sheet!!.status).isEqualTo(ItemStatus.Partial(2, 7))
        assertThat(vm.ui.value.sheet!!.sizeBytes).isEqualTo((vm.ui.value.sheetAudio as AudioOffer.Tracks).totalBytes)
        // The Installed tab lists the album from the ledger: Music shows it, Audiobooks doesn't.
        vm.setTab(ArchiveTab.INSTALLED)
        testScheduler.advanceUntilIdle()
        val row = vm.ui.value.rows.single()
        assertThat(row.identifier).isEqualTo(HOLST)
        assertThat(row.title).isEqualTo("The Planets Op. 32, H. 125")
        assertThat(row.status).isEqualTo(ItemStatus.Partial(2, 7))
        val spoken = audioViewModel(ArchiveKind.SPOKEN_WORD)
        spoken.setTab(ArchiveTab.INSTALLED)
        testScheduler.advanceUntilIdle()
        assertThat(spoken.ui.value.rows).isEmpty()
    }

    @Test fun audioCancelRetryAndRemoveGoToTheGroup() = runTest(main.dispatcher) {
        audioSearch(ArchiveKind.MUSIC)
        val vm = audioViewModel(ArchiveKind.MUSIC)
        vm.cancel(HOLST)
        vm.retryDownload(HOLST)
        testScheduler.advanceUntilIdle()
        assertThat(downloads.calls).containsAtLeast("cancelGroup:$holstGroup", "retryGroup:$holstGroup").inOrder()
        assertThat(downloads.calls.none { it.startsWith("cancel:") || it.startsWith("retry:") }).isTrue()
        assertThat(sidecars.listFiles()!!.toList()).isEmpty()
    }

    @Test fun textsAreUnchanged() = runTest(main.dispatcher) {
        val vm = viewModel()
        assertThat(searches().single().queryParameter("q")).isEqualTo(ArchiveQuery.build("", ArchiveShelf.MOST_DOWNLOADED, true))
        val ui = vm.ui.value
        assertThat(ui.kind).isEqualTo(ArchiveKind.TEXTS)
        assertThat(ui.waiverNeeded).isFalse()
        assertThat(ui.rows.all { it.audio == null }).isTrue()
        vm.openItem(epubId)
        settle { vm.ui.value.sheet?.loading == false }
        assertThat(vm.ui.value.sheetAudio).isNull()
        assertThat(vm.ui.value.sheet!!.offer).isInstanceOf(Offer.Download::class.java)
    }

    private class FakeArchivePrefs : ArchivePrefs {
        private val state = MutableStateFlow(false)
        override val waiverAccepted: StateFlow<Boolean> = state
        override fun accept() { state.value = true }
        override fun withdraw() { state.value = false }
    }

    private companion object {
        const val HOLST = MediaFixtures.HOLST
        const val AUDIO_LENDING = "rvji2imcowl7mslliunbjf94m7meqr7isqpghxlm"

        fun audioSearchJson(total: Int, vararg ids: String) =
            """{"response":{"numFound":$total,"docs":[""" +
                ids.joinToString(",") { """{"identifier":"$it","title":"Title $it","creator":"C","year":1920,"mediatype":"audio","format":["VBR MP3","Item Tile"]}""" } +
                "]}}"

        const val MODERN = "modern1950"
        val MODERN_JSON = """
            {"metadata":{"identifier":"$MODERN","title":"A Modern Book","creator":"Someone","date":"1950","mediatype":"texts"},
             "files":[{"name":"$MODERN.epub","format":"EPUB","size":"1000","sha1":"${"c".repeat(40)}","source":"derivative"}]}
        """.trimIndent()

        const val TAGGED = "wimpykid2007"
        val TAGGED_JSON = """
            {"metadata":{"identifier":"$TAGGED","title":"A Tagged Modern Book","creator":"Someone","date":"2007","mediatype":"texts",
             "licenseurl":"http://creativecommons.org/publicdomain/mark/1.0/"},
             "files":[{"name":"$TAGGED.epub","format":"EPUB","size":"1000","sha1":"${"d".repeat(40)}","source":"derivative"}]}
        """.trimIndent()

        fun searchJson(total: Int, vararg ids: String) =
            """{"response":{"numFound":$total,"docs":[""" +
                ids.joinToString(",") { """{"identifier":"$it","title":"Title $it","creator":"C","year":1900,"format":["EPUB"]}""" } +
                "]}}"
    }
}
