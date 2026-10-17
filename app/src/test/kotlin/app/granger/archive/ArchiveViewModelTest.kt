package app.granger.archive

import app.granger.FakeBookshelf
import app.granger.FakeDownloads
import app.granger.FakePrefs
import app.granger.FakeVolumes
import app.granger.MainDispatcherRule
import app.granger.core.archive.ArchiveOrgClient
import app.granger.core.archive.ArchiveQuery
import app.granger.core.archive.ArchiveShelf
import app.granger.core.archive.Offer
import app.granger.core.archive.TextKind
import app.granger.core.downloads.DownloadState
import app.granger.core.downloads.EnqueueResult
import app.granger.core.downloads.ItemKind
import app.granger.record
import app.granger.sources.ItemStatus
import app.granger.volume
import com.google.common.truth.Truth.assertThat
import java.io.File
import java.io.IOException
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

    private fun TestScope.viewModel(settle: Boolean = true): ArchiveViewModel {
        val vm = ArchiveViewModel(
            client, downloads, bookshelf, prefs, volumes, downloadPrefs, sidecarDir = { sidecars },
            appScope = this, io = main.dispatcher,
        )
        backgroundScope.launch { vm.ui.collect {} }
        if (settle) settle { !vm.ui.value.loading && searches().isNotEmpty() }
        return vm
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

    @Test fun openItemFillsTheSheetWithOfferAndSize() = runTest(main.dispatcher) {
        val vm = viewModel()
        vm.openItem(scanId)
        testScheduler.runCurrent()
        assertThat(vm.ui.value.sheet!!.loading).isTrue()
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
        assertThat(vm.ui.value.notice).isEqualTo("This item isn't marked public domain. Show everything to download it.")
        assertThat(downloads.enqueuedSpecs).isEmpty()
        assertThat(File(sidecars, "$MODERN.json").exists()).isFalse()
        vm.dismissNotice()
        vm.acceptWaiver()
        vm.get(MODERN)
        settle { downloads.enqueuedSpecs.isNotEmpty() }
        assertThat(downloads.enqueuedSpecs.single().itemId).isEqualTo("archive-org:$MODERN")
    }

    @Test fun withdrawingTheWaiverRefusesAnItemOpenedWhileUnfiltered() = runTest(main.dispatcher) {
        prefs.accept()
        val vm = viewModel()
        vm.openItem(MODERN)
        settle { vm.ui.value.sheet?.loading == false }
        prefs.withdraw()
        vm.get(MODERN)
        settle { vm.ui.value.notice != null }
        assertThat(vm.ui.value.notice).isEqualTo("This item isn't marked public domain. Show everything to download it.")
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

    private class FakeArchivePrefs : ArchivePrefs {
        private val state = MutableStateFlow(false)
        override val waiverAccepted: StateFlow<Boolean> = state
        override fun accept() { state.value = true }
        override fun withdraw() { state.value = false }
    }

    private companion object {
        const val MODERN = "modern1950"
        val MODERN_JSON = """
            {"metadata":{"identifier":"$MODERN","title":"A Modern Book","creator":"Someone","date":"1950","mediatype":"texts"},
             "files":[{"name":"$MODERN.epub","format":"EPUB","size":"1000","sha1":"${"c".repeat(40)}","source":"derivative"}]}
        """.trimIndent()

        fun searchJson(total: Int, vararg ids: String) =
            """{"response":{"numFound":$total,"docs":[""" +
                ids.joinToString(",") { """{"identifier":"$it","title":"Title $it","creator":"C","year":1900,"format":["EPUB"]}""" } +
                "]}}"
    }
}
