package app.granger.readiness

import app.granger.FakeBookshelf
import app.granger.FakeCatalogSource
import app.granger.FakeDownloads
import app.granger.FakeLibrary
import app.granger.FakeVolumes
import app.granger.MainDispatcherRule
import app.granger.book
import app.granger.catalogOf
import app.granger.core.ai.DeviceTier
import app.granger.core.catalog.CatalogState
import app.granger.core.catalog.Domain
import app.granger.core.catalog.Readiness
import app.granger.core.downloads.DownloadRecord
import app.granger.core.downloads.DownloadState
import app.granger.core.downloads.Integrity
import app.granger.core.downloads.ItemKind
import app.granger.core.library.BookOrigin
import app.granger.core.library.PackStatus
import app.granger.pack
import app.granger.packEntry
import app.granger.record
import app.granger.volume
import com.google.common.truth.Truth.assertThat
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test

class ReadinessViewModelTest {
    @get:Rule val main = MainDispatcherRule()

    private val downloads = FakeDownloads()
    private val library = FakeLibrary()
    private val bookshelf = FakeBookshelf()
    private val catalog = FakeCatalogSource()
    private val volumes = FakeVolumes(listOf(volume("/internal", "Internal storage", free = 9_000, total = 64_000)))
    private val quick = mutableMapOf<String, Integrity>()
    private var fullResult = CompletableDeferred<Integrity>()
    private var fullThrows = false

    private val med = record("med", DownloadState.INSTALLED, filePath = "/internal/packs/med.zim")
    private val e2b = record("e2b", DownloadState.INSTALLED, kind = ItemKind.MODEL, filePath = "/internal/models/e2b.litertlm")
    private fun key(r: DownloadRecord) = "${r.itemId}@${r.hash}"

    private fun TestScope.viewModel(): ReadinessViewModel {
        val vm = ReadinessViewModel(
            downloads, library, bookshelf, catalog, volumes, DeviceTier.ASK, totalRamBytes = 7_974_752_256, appScope = this,
            io = main.dispatcher,
            quick = { quick[it.itemId] ?: Integrity.SIZE_OK },
            full = { _, progress ->
                if (fullThrows) throw IOException("card pulled")
                progress(0.5f)
                fullResult.await()
            },
        )
        backgroundScope.launch { vm.ui.collect {} }
        testScheduler.advanceUntilIdle()
        return vm
    }

    @Test fun voicesGetTheirOwnSectionAndStayOutOfPacks() = runTest(main.dispatcher) {
        val voice = record("vits-piper-en_US-ljspeech-medium", DownloadState.INSTALLED, kind = ItemKind.VOICE, filePath = "/internal/voices/vits-piper-en_US-ljspeech-medium.zip")
        downloads.list.value = listOf(med, e2b, voice)
        quick[voice.itemId] = Integrity.VERIFIED
        val ui = viewModel().ui.value
        assertThat(ui.voices.map { it.key }).containsExactly(key(voice))
        assertThat(ui.voices.single().status).isEqualTo(CheckStatus.VERIFIED)
        assertThat(ui.voices.single().isModel).isFalse()
        assertThat(ui.packs.map { it.key }).containsExactly(key(med))
        assertThat(ui.models.map { it.key }).containsExactly(key(e2b))
    }

    @Test fun everyRowKnowsTheSourceItIsListedUnder() = runTest(main.dispatcher) {
        val voice = record("vits-piper-en_US-ljspeech-medium", DownloadState.INSTALLED, kind = ItemKind.VOICE, filePath = "/internal/voices/v.zip")
        val kiwix = record("wikem", DownloadState.INSTALLED).copy(urls = listOf("https://download.kiwix.org/zim/wikem.zim"))
        downloads.list.value = listOf(med, kiwix, e2b, voice) + chapters
        library.packList.value = listOf(pack("u-hand", title = "Pushed"))
        val ui = viewModel().ui.value
        assertThat(ui.models.single().source).isEqualTo(Source.MODELS)
        assertThat(ui.voices.single().source).isEqualTo(Source.VOICES)
        assertThat(ui.media.single().source).isEqualTo(Source.LIBRIVOX)
        assertThat(ui.packs.associate { it.title to it.source }).containsExactly(
            "Title med", Source.GRANGER, "Title wikem", Source.KIWIX, "Pushed", Source.KIWIX,
        )
    }

    @Test fun showsTierRamAndVolumes() = runTest(main.dispatcher) {
        val ui = viewModel().ui.value
        assertThat(ui.loading).isFalse()
        assertThat(ui.tier).isEqualTo(DeviceTier.ASK)
        assertThat(ui.totalRamBytes).isEqualTo(7_974_752_256)
        assertThat(ui.volumes.single().freeBytes).isEqualTo(9_000)
    }

    @Test fun installedFilesGetTheQuickCheck() = runTest(main.dispatcher) {
        downloads.list.value = listOf(med, e2b, record("other", DownloadState.RUNNING))
        quick["e2b"] = Integrity.VERIFIED
        quick["med"] = Integrity.INCOMPLETE
        val ui = viewModel().ui.value
        assertThat(ui.models.single().status).isEqualTo(CheckStatus.VERIFIED)
        val row = ui.packs.single()
        assertThat(row.status).isEqualTo(CheckStatus.INCOMPLETE)
        assertThat(row.canRepair).isTrue()
        assertThat(row.snapshotDate).isEqualTo("2026-10-10")
    }

    @Test fun aPackTheLibraryCannotOpenIsDamaged() = runTest(main.dispatcher) {
        downloads.list.value = listOf(med)
        library.packList.value = listOf(pack("u-med", status = PackStatus.CORRUPT).copy(filePath = med.filePath, title = "WikiMed"))
        val row = viewModel().ui.value.packs.single()
        assertThat(row.status).isEqualTo(CheckStatus.DAMAGED)
        assertThat(row.title).isEqualTo("WikiMed")
        assertThat(row.canRepair).isTrue()
    }

    @Test fun packsAddedByHandAreListedButNotVerifiable() = runTest(main.dispatcher) {
        library.packList.value = listOf(pack("u-hand", title = "Pushed"), pack("u-gone", status = PackStatus.MISSING))
        val rows = viewModel().ui.value.packs
        assertThat(rows.map { it.title to it.status })
            .containsExactly("Pushed" to CheckStatus.NOT_FROM_CATALOG, "u-gone" to CheckStatus.MISSING)
        assertThat(rows.none { it.canVerify || it.canRepair }).isTrue()
    }

    @Test fun verifyShowsProgressThenTheResult() = runTest(main.dispatcher) {
        downloads.list.value = listOf(med)
        val vm = viewModel()
        vm.verify(key(med))
        testScheduler.advanceUntilIdle()
        assertThat(vm.ui.value.packs.single().progress).isEqualTo(0.5f)
        assertThat(vm.ui.value.packs.single().canVerify).isFalse()

        fullResult.complete(Integrity.CORRUPTED)
        testScheduler.advanceUntilIdle()
        val row = vm.ui.value.packs.single()
        assertThat(row.progress).isNull()
        assertThat(row.status).isEqualTo(CheckStatus.CORRUPTED)
        assertThat(row.canRepair).isTrue()
        assertThat(downloads.calls).contains("check:med:false")
    }

    @Test fun aPassingCheckIsRecorded() = runTest(main.dispatcher) {
        downloads.list.value = listOf(med)
        fullResult.complete(Integrity.VERIFIED)
        val vm = viewModel()
        vm.verify(key(med))
        testScheduler.advanceUntilIdle()
        assertThat(vm.ui.value.packs.single().status).isEqualTo(CheckStatus.VERIFIED)
        assertThat(downloads.calls).contains("check:med:true")
    }

    @Test fun repairDownloadsAgain() = runTest(main.dispatcher) {
        downloads.list.value = listOf(med)
        val vm = viewModel()
        vm.repair(key(med))
        testScheduler.advanceUntilIdle()
        assertThat(downloads.calls).contains("repair:med")
    }

    @Test fun aFailingCheckFallsBackToTheQuickStatus() = runTest(main.dispatcher) {
        downloads.list.value = listOf(med)
        fullThrows = true
        quick["med"] = Integrity.MISSING
        val vm = viewModel()
        vm.verify(key(med))
        testScheduler.advanceUntilIdle()
        val row = vm.ui.value.packs.single()
        assertThat(row.progress).isNull()
        assertThat(row.status).isEqualTo(CheckStatus.MISSING)
        assertThat(downloads.calls).contains("check:med:false")
    }

    @Test fun aLaterMissingFileBeatsAnEarlierVerifiedResult() = runTest(main.dispatcher) {
        downloads.list.value = listOf(med)
        fullResult.complete(Integrity.VERIFIED)
        val vm = viewModel()
        vm.verify(key(med))
        testScheduler.advanceUntilIdle()
        assertThat(vm.ui.value.packs.single().status).isEqualTo(CheckStatus.VERIFIED)
        quick["med"] = Integrity.MISSING
        downloads.list.value = listOf(med.copy(verifiedAt = 1L)) // any emission rebuilds the rows
        testScheduler.advanceUntilIdle()
        assertThat(vm.ui.value.packs.single().status).isEqualTo(CheckStatus.MISSING)
    }

    // ---- Plan R1: search vectors are part of their item ----

    private val medVectors = record("med.vectors", DownloadState.INSTALLED, sha256 = "3".repeat(64), kind = ItemKind.VECTORS,
        filePath = "/internal/vectors/med_333333333333.gvec", sizeBytes = 400)

    @Test fun vectorsAreFoldedIntoTheirItemsRow() = runTest(main.dispatcher) {
        downloads.list.value = listOf(med, medVectors, e2b)
        quick["med"] = Integrity.VERIFIED
        quick["med.vectors"] = Integrity.INCOMPLETE
        val ui = viewModel().ui.value
        val row = ui.packs.single()
        assertThat(row.key).isEqualTo(key(med))
        assertThat(row.sizeBytes).isEqualTo(1_400)
        assertThat(row.status).isEqualTo(CheckStatus.INCOMPLETE)
        assertThat(row.canRepair).isTrue()
        assertThat(ui.models.map { it.key }).containsExactly(key(e2b))
        // Storage counts them under their item's source, without a second item.
        val granger = ui.report.storage.single { it.source == Source.GRANGER }
        assertThat(granger.bytes).isEqualTo(1_400)
        assertThat(granger.items).isEqualTo(1)
    }

    @Test fun verifyChecksTheItemAndItsVectors() = runTest(main.dispatcher) {
        downloads.list.value = listOf(med, medVectors)
        fullResult.complete(Integrity.VERIFIED)
        val vm = viewModel()
        vm.verify(key(med))
        testScheduler.advanceUntilIdle()
        assertThat(downloads.calls).containsAtLeast("check:med:true", "check:med.vectors:true")
        assertThat(vm.ui.value.packs.single().status).isEqualTo(CheckStatus.VERIFIED)
    }

    @Test fun repairFetchesOnlyTheDamagedFile() = runTest(main.dispatcher) {
        downloads.list.value = listOf(med, medVectors)
        quick["med.vectors"] = Integrity.MISSING
        val vm = viewModel()
        vm.repair(key(med))
        testScheduler.advanceUntilIdle()
        assertThat(downloads.calls).contains("repair:med.vectors")
        assertThat(downloads.calls).doesNotContain("repair:med")
    }

    private val bookGroup = "media:librivox:817"
    private val chapters = (1..13).map { n ->
        record("$bookGroup/%03d".format(n), DownloadState.INSTALLED, kind = ItemKind.AUDIO, sizeBytes = 100L * n,
            filePath = "/internal/audiobooks/librivox/817/%02d - Chapter.mp3".format(n), title = "Time Machine",
            hashAlgorithm = app.granger.core.downloads.HashAlgorithm.SHA1).copy(groupId = bookGroup)
    }

    @Test fun mediaFilesShowAsOneRowPerItem() = runTest(main.dispatcher) {
        downloads.list.value = chapters + med
        val ui = viewModel().ui.value
        val row = ui.media.single()
        assertThat(row.key).isEqualTo("group:$bookGroup")
        assertThat(row.title).isEqualTo("Time Machine")
        assertThat(row.sizeBytes).isEqualTo(chapters.sumOf { it.sizeBytes })
        assertThat(row.status).isEqualTo(CheckStatus.SIZE_OK)
        assertThat(ui.packs.map { it.key }).containsExactly(key(med))
    }

    @Test fun verifyChecksEveryFileAndRepairFetchesOnlyTheDamagedOnes() = runTest(main.dispatcher) {
        downloads.list.value = chapters
        quick[chapters[4].itemId] = Integrity.MISSING
        val vm = viewModel()
        val row = vm.ui.value.media.single()
        assertThat(row.status).isEqualTo(CheckStatus.MISSING)
        assertThat(row.canRepair).isTrue()

        fullResult.complete(Integrity.VERIFIED)
        vm.verify(row.key)
        testScheduler.advanceUntilIdle()
        assertThat(downloads.calls.filter { it.startsWith("check:") }).hasSize(13)

        vm.repair(row.key)
        testScheduler.advanceUntilIdle()
        assertThat(downloads.calls.filter { it.startsWith("repair:") }).containsExactly("repair:${chapters[4].itemId}")
    }

    // --- Plan H1: coverage, signals and storage on the UI ---

    private val medEntry = packEntry("med", category = "Medical").copy(domain = "medicine", family = "wikipedia")
    private val wikemEntry = packEntry("wikem", category = "Medical").copy(domain = "medicine", family = "wikem")
    private val mapEntry = packEntry("m1", category = "Maps").copy(domain = "maps", family = "maps")
    private val model = Readiness(
        listOf(
            Domain("medicine", "Medicine & first aid", listOf("med"), listOf("med", "wikem")),
            Domain("maps", "Maps, places & travel", listOf("family:maps"), listOf("family:maps")),
        ),
    )

    @Test fun coverageRowsComeFromTheCatalogAndTheLibrary() = runTest(main.dispatcher) {
        catalog.mutable.value = CatalogState(catalog = catalogOf(packs = listOf(medEntry, wikemEntry, mapEntry)).copy(readiness = model))
        library.packList.value = listOf(pack("u-med", "WikiMed").copy(name = "n-med", sizeBytes = 900))
        val report = viewModel().ui.value.report
        assertThat(report.needsCatalogRefresh).isFalse()
        assertThat(report.domains.map { it.id }).containsExactly("medicine", "maps").inOrder()
        assertThat(report.domains.map { it.coverage }).containsExactly(Coverage.BASIC, Coverage.NONE).inOrder()
        assertThat(report.domains.first().installedBytes).isEqualTo(900)
        assertThat(catalog.loads).isEqualTo(1)
    }

    @Test fun anOlderCatalogAsksForARefreshAndKeepsTheFileRows() = runTest(main.dispatcher) {
        downloads.list.value = listOf(med, e2b)
        val ui = viewModel().ui.value
        assertThat(ui.report.needsCatalogRefresh).isTrue()
        assertThat(ui.report.domains).isEmpty()
        assertThat(ui.packs.map { it.key }).containsExactly(key(med))
        assertThat(ui.models.map { it.key }).containsExactly(key(e2b))
    }

    @Test fun storageBySourceAndBooksReachTheUi() = runTest(main.dispatcher) {
        downloads.list.value = listOf(med, e2b)
        bookshelf.bookList.value = listOf(book("notes").copy(origin = BookOrigin.IMPORT, sizeBytes = 42))
        val storage = viewModel().ui.value.report.storage.associateBy { it.source }
        // The fakes' URLs are on a.test, which is neither Kiwix nor archive.org: Granger's own.
        assertThat(storage.getValue(Source.GRANGER).bytes).isEqualTo(1_000)
        assertThat(storage.getValue(Source.MODELS).bytes).isEqualTo(1_000)
        assertThat(storage.getValue(Source.MY_BOOKS).bytes).isEqualTo(42)
    }

    @Test fun shownSignalsCapAtThreeUntilShowAll() {
        val signals = (1..5).map { Signal.Unsearchable("p$it", "Pack $it can't be searched: the pack has no index.") }
        assertThat(shownSignals(signals, showAll = false)).hasSize(SHOWN_SIGNALS)
        assertThat(shownSignals(signals, showAll = true)).hasSize(5)
        assertThat(shownSignals(signals.take(2), showAll = false)).hasSize(2)
    }

    @Test fun coverageTextReadsTheStatus() {
        fun d(coverage: Coverage, installed: Long, essential: Long = 1_000, comprehensive: Long = 4_000) =
            DomainCoverage("x", "X", coverage, installed, essential, comprehensive, emptyList())
        assertThat(coverageText(d(Coverage.NONE, 0))).isEqualTo("Nothing yet · the essentials are 1.0 KB")
        assertThat(coverageText(d(Coverage.NONE, 0, essential = 0))).isEqualTo("Nothing yet")
        assertThat(coverageText(d(Coverage.NONE, 500))).isEqualTo("No essentials yet · 500 B held · the essentials are 1.0 KB")
        assertThat(coverageText(d(Coverage.BASIC, 1_200))).isEqualTo("Basic · 1.2 KB of 4.0 KB")
        assertThat(coverageText(d(Coverage.COMPREHENSIVE, 5_000))).isEqualTo("Comprehensive · 5.0 KB")
    }
}
