package app.granger.librivox

import app.granger.FakeDownloads
import app.granger.FakePrefs
import app.granger.FakeVolumes
import app.granger.MainDispatcherRule
import app.granger.core.archive.ArchiveOrgClient
import app.granger.core.archive.LibriVoxClient
import app.granger.core.archive.LibriVoxOffer
import app.granger.core.archive.LibriVoxShelf
import app.granger.core.downloads.DownloadState
import app.granger.core.downloads.ItemKind
import app.granger.core.library.MediaLibrary
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
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
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

class LibriVoxViewModelTest {
    @get:Rule val main = MainDispatcherRule()
    @get:Rule val tmp = TemporaryFolder()

    private val group = "media:librivox:817"

    /** Every URL asked for, LibriVox and archive.org alike, in order. */
    private val requests = CopyOnWriteArrayList<HttpUrl>()
    @Volatile private var offline = false
    /** A status for every LibriVox request (429 for busy), or null to serve the fixtures. */
    @Volatile private var librivoxStatus: Int? = null
    private var bookBody: () -> String = { resource("librivox-book-817.json") }
    private var itemBody: () -> String = { resource("item-librivox-time-machine.json") }
    /** Runs before a LibriVox list request is answered: tests block on it to hold a response back. */
    private var beforeList: (HttpUrl) -> Unit = {}

    private val server = Interceptor { chain ->
        val url = chain.request().url
        requests += url
        if (offline) throw IOException("offline")
        val (code, body) = when {
            url.host == "archive.org" -> 200 to itemBody()
            librivoxStatus != null -> librivoxStatus!! to ""
            url.queryParameter("id") == "817" -> 200 to bookBody()
            url.queryParameter("id") != null -> 404 to resource("librivox-not-found.json")
            else -> {
                beforeList(url)
                when {
                    url.queryParameter("sort_field") != null -> 200 to resource("librivox-recent.json")
                    url.queryParameter("genre") != null -> 200 to resource("librivox-search-time-machine.json")
                    url.queryParameter("title") == "Time Machine" -> 200 to resource("librivox-search-time-machine.json")
                    else -> 404 to resource("librivox-not-found.json")
                }
            }
        }
        Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).message("").code(code)
            .body(body.toResponseBody()).build()
    }
    private val http = OkHttpClient.Builder().addInterceptor(server).build()
    private val librivox = LibriVoxClient(http, "Granger/1.0 (+test)", sleep = {})
    private val archive = ArchiveOrgClient(http, "Granger/1.0 (+test)")

    private val downloads = FakeDownloads()
    private val downloadPrefs = FakePrefs()
    private val mediaRoot by lazy { tmp.newFolder("media") }

    private fun resource(name: String) =
        javaClass.classLoader!!.getResourceAsStream("archive/$name")!!.readBytes().decodeToString()

    private fun lists() = requests.filter { it.host == "librivox.org" && it.queryParameter("id") == null }

    private fun TestScope.settle(done: () -> Boolean) {
        repeat(300) {
            testScheduler.advanceUntilIdle()
            if (done()) return
            Thread.sleep(10)
        }
        testScheduler.advanceUntilIdle()
    }

    private fun TestScope.settleNow(done: () -> Boolean) {
        repeat(300) {
            testScheduler.runCurrent()
            if (done()) return
            Thread.sleep(10)
        }
    }

    private fun TestScope.viewModel(settle: Boolean = true): LibriVoxViewModel {
        // Remove forgets the book's rows; nothing here is filed, so there's nothing to forget.
        val library = object : MediaLibrary by FakeMediaLibrary() {
            override suspend fun forget(trackIds: Collection<String>, audiobookIds: Collection<String>) {}
        }
        val scanner = DownloadedMediaScanner({ listOf(mediaRoot) }, library, TagReader { _, _ -> FileTags(null, emptyList()) })
        val volumes = FakeVolumes(listOf(volume(mediaRoot.path, "Internal storage")))
        val media = MediaDownloader(downloads, scanner, library, volumes, downloadPrefs, backgroundScope, io = main.dispatcher)
        val vm = LibriVoxViewModel(librivox, archive, media, downloads, appScope = this, io = main.dispatcher, now = { 5L })
        backgroundScope.launch { vm.ui.collect {} }
        if (settle) settle { !vm.ui.value.loading && requests.isNotEmpty() }
        return vm
    }

    /** Ledger rows for the Time Machine group: the cover, then [installed] of its 13 chapters, all INSTALLED. */
    private fun installed(chapters: Int, title: String = "Time Machine"): List<app.granger.core.downloads.DownloadRecord> {
        val specs = MediaDownloads.specs(MediaFixtures.timeMachine())
        return specs.take(chapters + 1).map { spec ->
            record(
                spec.itemId, DownloadState.INSTALLED, kind = ItemKind.AUDIO, filePath = File(mediaRoot, spec.relativePath).path,
                sizeBytes = 100, bytesDone = 100, title = title,
            ).copy(groupId = group)
        }
    }

    @Test fun startsOnRecentlyAdded() = runTest(main.dispatcher) {
        val vm = viewModel()
        val first = lists().first()
        assertThat(first.queryParameter("sort_field")).isEqualTo("id")
        assertThat(first.queryParameter("sort_order")).isEqualTo("desc")
        assertThat(first.queryParameter("offset")).isEqualTo("0")
        val ui = vm.ui.value
        assertThat(ui.shelf).isEqualTo(LibriVoxShelf.RECENT)
        assertThat(ui.tab).isEqualTo(LibriVoxTab.BROWSE)
        assertThat(ui.rows.map { it.id }).containsExactly("23008", "23007", "23006").inOrder()
        assertThat(ui.rows.all { it.status == ItemStatus.NotInstalled }).isTrue()
        assertThat(ui.error).isNull()
        assertThat(ui.canLoadMore).isFalse()
    }

    @Test fun typingSearchesByTitleAndAuthorAfterTheDebounce() = runTest(main.dispatcher) {
        val vm = viewModel()
        requests.clear()
        vm.setQuery("The")
        testScheduler.advanceTimeBy(200)
        vm.setQuery("The Time")
        testScheduler.advanceTimeBy(200)
        vm.setQuery("The Time Machine")
        assertThat(vm.searchText).isEqualTo("The Time Machine")
        testScheduler.advanceTimeBy(399)
        settleNow { false }
        // No request per keystroke: nothing has gone out yet.
        assertThat(requests).isEmpty()
        testScheduler.advanceTimeBy(2)
        settle { lists().size == 2 && !vm.ui.value.loading }
        assertThat(lists().map { it.queryParameter("title") to it.queryParameter("author") })
            .containsExactly("Time Machine" to null, null to "Machine").inOrder()
        val ui = vm.ui.value
        assertThat(ui.shelf).isNull()
        assertThat(ui.rows.first().id).isEqualTo("817")
        assertThat(ui.rows.first().title).isEqualTo("Time Machine")
        assertThat(ui.rows.first().details).isEqualTo("H. G. Wells · 4 h 9 min")
        // Clearing the box goes back to Recently added.
        vm.setQuery("")
        settle { lists().size == 3 && !vm.ui.value.loading }
        assertThat(vm.ui.value.shelf).isEqualTo(LibriVoxShelf.RECENT)
    }

    @Test fun noResultsIsAnEmptyListNotAnError() = runTest(main.dispatcher) {
        val vm = viewModel()
        vm.setQuery("zzzzqqq")
        settle { lists().size == 3 && !vm.ui.value.loading }
        val ui = vm.ui.value
        assertThat(ui.rows).isEmpty()
        assertThat(ui.error).isNull()
        assertThat(ui.loading).isFalse()
        assertThat(ui.canLoadMore).isFalse()
    }

    @Test fun busyShowsTheMessageWithRetry() = runTest(main.dispatcher) {
        librivoxStatus = 429
        val vm = viewModel()
        assertThat(vm.ui.value.error).isEqualTo("LibriVox is busy right now. Try again in a minute.")
        assertThat(vm.ui.value.rows).isEmpty()
        assertThat(vm.ui.value.loading).isFalse()
        librivoxStatus = null
        vm.retry()
        settle { vm.ui.value.rows.isNotEmpty() }
        assertThat(vm.ui.value.error).isNull()
        assertThat(vm.ui.value.rows.first().id).isEqualTo("23008")
    }

    @Test fun staleResultsAreDropped() = runTest(main.dispatcher) {
        val releaseRecent = CountDownLatch(1)
        val releaseGenre = CountDownLatch(1)
        val recentServed = CountDownLatch(1)
        beforeList = { url ->
            when {
                url.queryParameter("sort_field") != null -> { releaseRecent.await(5, TimeUnit.SECONDS); recentServed.countDown() }
                url.queryParameter("genre") != null -> releaseGenre.await(5, TimeUnit.SECONDS)
            }
        }
        val vm = viewModel(settle = false)
        settle { lists().isNotEmpty() }
        vm.selectShelf(LibriVoxShelf.POETRY)
        // The Recently added answer lands after Poetry was asked for: it's dropped.
        releaseRecent.countDown()
        assertThat(recentServed.await(5, TimeUnit.SECONDS)).isTrue()
        repeat(20) { Thread.sleep(10); testScheduler.advanceUntilIdle() }
        assertThat(vm.ui.value.rows).isEmpty()
        assertThat(vm.ui.value.loading).isTrue()
        assertThat(vm.ui.value.shelf).isEqualTo(LibriVoxShelf.POETRY)
        releaseGenre.countDown()
        settle { vm.ui.value.rows.isNotEmpty() }
        assertThat(vm.ui.value.rows.first().id).isEqualTo("817")
        assertThat(vm.ui.value.rows.none { it.id == "23008" }).isTrue()
        assertThat(lists().last().queryParameter("genre")).isEqualTo("Poetry")
    }

    @Test fun openBookFillsTheSheetWithChaptersAndSize() = runTest(main.dispatcher) {
        val vm = viewModel()
        vm.openBook("817")
        settle { vm.ui.value.sheet?.loading == false }
        val sheet = vm.ui.value.sheet!!
        assertThat(sheet.id).isEqualTo("817")
        assertThat(sheet.error).isNull()
        assertThat(sheet.book!!.sections).hasSize(13)
        val offer = sheet.offer as LibriVoxOffer.Files
        assertThat(offer.files).hasSize(13)
        assertThat(sheet.sizeBytes).isEqualTo(offer.totalBytes)
        assertThat(sheet.status).isEqualTo(ItemStatus.NotInstalled)
        assertThat(requests.last().encodedPath).isEqualTo("/metadata/time_machine_0805_librivox")
        vm.closeBook()
        testScheduler.advanceUntilIdle()
        assertThat(vm.ui.value.sheet).isNull()
    }

    @Test fun getQueuesTheBookAsOneGroup() = runTest(main.dispatcher) {
        val vm = viewModel()
        vm.get("817")
        settle { downloads.enqueuedGroups.isNotEmpty() }
        val (id, specs, root) = downloads.enqueuedGroups.single()
        assertThat(id).isEqualTo(group)
        assertThat(specs).hasSize(14)
        assertThat(specs.first().itemId).isEqualTo("$group/000")
        assertThat(specs.all { it.kind == ItemKind.AUDIO && it.relativePath.startsWith("audiobooks/librivox/817/") }).isTrue()
        assertThat(specs[1].urls.single()).endsWith("/timemachinewells_01_ae_64kb.mp3")
        assertThat(root).isEqualTo(mediaRoot)
        val manifest = MediaManifests.read(File(mediaRoot, "audiobooks/librivox/817"))!!
        assertThat(manifest.source).isEqualTo(MediaManifest.LIBRIVOX)
        assertThat(manifest.files).hasSize(13)
        settle { vm.ui.value.rows.isNotEmpty() }
        assertThat(vm.ui.value.notice).isNull()
    }

    @Test fun incompleteBookIsNotQueued() = runTest(main.dispatcher) {
        itemBody = { resource("item-librivox-time-machine.json").replace("timemachinewells_01_ae_64kb.mp3", "elsewhere_64kb.mp3") }
        val vm = viewModel()
        vm.openBook("817")
        settle { vm.ui.value.sheet?.loading == false }
        assertThat(vm.ui.value.sheet!!.offer).isEqualTo(LibriVoxOffer.Incomplete(1))
        assertThat(vm.ui.value.sheet!!.sizeBytes).isNull()
        vm.get("817")
        settle { vm.ui.value.notice != null }
        assertThat(vm.ui.value.notice).isEqualTo("This book's files aren't all on archive.org.")
        assertThat(downloads.enqueuedGroups).isEmpty()
        assertThat(File(mediaRoot, "audiobooks/librivox/817").exists()).isFalse()
    }

    @Test fun bookNotOnArchiveIsNotQueued() = runTest(main.dispatcher) {
        bookBody = {
            resource("librivox-book-817.json")
                .replace("\"https://www.archive.org/details/time_machine_0805_librivox\"", "\"\"")
        }
        val vm = viewModel()
        vm.openBook("817")
        settle { vm.ui.value.sheet?.loading == false }
        assertThat(vm.ui.value.sheet!!.offer).isEqualTo(LibriVoxOffer.NotOnArchive)
        // Nothing to look up on archive.org.
        assertThat(requests.none { it.host == "archive.org" }).isTrue()
        vm.get("817")
        settle { vm.ui.value.notice != null }
        assertThat(vm.ui.value.notice).isEqualTo("This book isn't on archive.org yet.")
        assertThat(downloads.enqueuedGroups).isEmpty()
    }

    @Test fun installedTabWorksOffline() = runTest(main.dispatcher) {
        offline = true
        downloads.list.value = installed(13) + record("med", DownloadState.INSTALLED)
        val vm = viewModel()
        assertThat(vm.ui.value.error).contains("Couldn't reach LibriVox")
        vm.setTab(LibriVoxTab.INSTALLED)
        testScheduler.advanceUntilIdle()
        val row = vm.ui.value.rows.single()
        assertThat(row.id).isEqualTo("817")
        assertThat(row.title).isEqualTo("Time Machine")
        assertThat(row.status).isEqualTo(ItemStatus.Installed)
        assertThat(vm.ui.value.error).isNull()
        assertThat(vm.ui.value.loading).isFalse()
    }

    @Test fun partlyDownloadedOffersGetTheRest() = runTest(main.dispatcher) {
        // Cancelled after five chapters: the unfinished records went, the manifest still says thirteen.
        val manifest = MediaFixtures.timeMachine()
        MediaManifests.write(File(mediaRoot, MediaManifests.relativeDir(manifest)), manifest)
        downloads.list.value = installed(5)
        val vm = viewModel()
        vm.setTab(LibriVoxTab.INSTALLED)
        testScheduler.advanceUntilIdle()
        assertThat(vm.ui.value.rows.single().status).isEqualTo(ItemStatus.Partial(5, 13))
        vm.openBook("817")
        settle { vm.ui.value.sheet?.loading == false }
        val sheet = vm.ui.value.sheet!!
        assertThat(sheet.status).isEqualTo(ItemStatus.Partial(5, 13))
        assertThat(sheet.filesDone).isEqualTo(5)
        assertThat(sheet.filesTotal).isEqualTo(13)
        assertThat(sheet.coverPath).isEqualTo(File(mediaRoot, "audiobooks/librivox/817/cover.jpg").path)
        // Get the rest queues the whole group again; the ledger skips what's in.
        vm.get("817")
        settle { downloads.enqueuedGroups.isNotEmpty() }
        assertThat(downloads.enqueuedGroups.single().first).isEqualTo(group)
    }

    @Test fun cancelRetryAndRemoveGoToTheGroup() = runTest(main.dispatcher) {
        val vm = viewModel()
        vm.cancel("817")
        testScheduler.advanceUntilIdle()
        vm.retryDownload("817")
        testScheduler.advanceUntilIdle()
        vm.remove("817")
        settle { "removeGroup:$group" in downloads.calls }
        assertThat(downloads.calls).containsAtLeast("cancelGroup:$group", "retryGroup:$group", "removeGroup:$group").inOrder()
        // A bad id never reaches the downloader.
        vm.cancel("../x")
        testScheduler.advanceUntilIdle()
        assertThat(downloads.calls.none { "../x" in it }).isTrue()
    }
}
