package app.granger.downloads

import app.granger.book
import app.granger.core.downloads.DownloadLive
import app.granger.core.downloads.DownloadQueueSummary
import app.granger.core.downloads.DownloadRecord
import app.granger.core.downloads.DownloadState
import app.granger.core.downloads.GoneFile
import app.granger.core.downloads.ItemKind
import app.granger.core.downloads.QueueProgress
import app.granger.core.downloads.VectorDownloads
import app.granger.core.downloads.WaitReason
import app.granger.pack
import app.granger.record
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class DownloadsModelTest {
    private val midnight = 10_000L
    private val now = 20_000L

    private fun rec(
        itemId: String, state: DownloadState, updated: Long = now, kind: ItemKind = ItemKind.PACK, size: Long = 1_000,
        done: Long = 0, group: String? = null, title: String = "Title $itemId", wifiOnly: Boolean = true,
    ): DownloadRecord = record(itemId, state, kind = kind, sizeBytes = size, bytesDone = done, title = title, wifiOnly = wifiOnly)
        .copy(createdAt = updated, updatedAt = updated, groupId = group)

    private fun vectorsOf(parent: String, state: DownloadState, updated: Long = now) =
        rec(VectorDownloads.itemIdOf(parent), state, updated, kind = ItemKind.VECTORS, size = 100)

    private fun ui(
        records: List<DownloadRecord>, live: DownloadLive = DownloadLive(batchStartedAt = midnight), catalogIds: Set<String>? = null,
        volumes: List<VolumeSpace> = emptyList(), packs: List<app.granger.core.library.Pack> = emptyList(),
        books: List<app.granger.core.library.Book> = emptyList(),
    ): DownloadsUi {
        val summary = DownloadQueueSummary.of(records, live, now, volumes.associate { it.root to it.freeBytes })
        return DownloadsModel.of(records, summary, midnight, packs, books, catalogIds, volumes)
    }

    private fun running(id: String, done: Long, rate: Long?) =
        DownloadLive(QueueProgress(id, app.granger.SHA_A, done, rate), batchStartedAt = midnight)

    @Test fun theRunningFileIsNowAndTheRestIsUpNextInQueueOrder() {
        val group = "onboarding:7"
        val ui = ui(
            listOf(
                rec("a", DownloadState.INSTALLED, group = group),
                rec("b", DownloadState.RUNNING, group = group, done = 300),
                rec("c", DownloadState.QUEUED, group = group),
                rec("d", DownloadState.QUEUED, group = group),
            ),
            running("b", done = 500, rate = 100),
        )
        val current = ui.now!!
        assertThat(current.title).isEqualTo("Title b")
        assertThat(current.groupLabel).isEqualTo("Setting up")
        assertThat(downloadsText(current, now, speed = 100)).isEqualTo("500 B of 1.0 KB · 100 B/s")
        // The group's files appear in place, labelled by the group.
        assertThat(ui.upNext.map { it.title to it.groupLabel }).containsExactly("Title c" to "Setting up", "Title d" to "Setting up").inOrder()
        assertThat(ui.upNext.map { downloadsText(it, now) }).containsExactly("1.0 KB · Next", "1.0 KB · 2nd in line").inOrder()
        val header = ui.header!!
        assertThat(headerText(header)).isEqualTo("1 of 4 · 1.5 KB of 4.0 KB · less than a minute left")
        assertThat(header.bytesPerSecond).isEqualTo(100)
    }

    @Test fun eachWaitingReasonReads() {
        assertThat(reasonText(WaitReason.Paused, now)).isEqualTo("Paused")
        assertThat(reasonText(WaitReason.WaitingForWifi, now)).isEqualTo("Waiting for Wi-Fi")
        assertThat(reasonText(WaitReason.Retrying(now + 120_000, 3), now)).isEqualTo("Trying again in 2 min (attempt 3 of 10)")
        assertThat(reasonText(WaitReason.NeedsSpace(1_200_000_000), now)).isEqualTo("Needs 1.2 GB more space")
        assertThat(reasonText(WaitReason.InLine(1), now)).isEqualTo("Next")
        assertThat(reasonText(WaitReason.InLine(4), now)).isEqualTo("4th in line")
    }

    @Test fun aRowWaitingForWifiSaysSo() {
        val ui = ui(listOf(rec("a", DownloadState.QUEUED, done = 312)), DownloadLive(unmetered = false, batchStartedAt = midnight))
        assertThat(downloadsText(ui.upNext.single(), now)).isEqualTo("312 B of 1.0 KB · Waiting for Wi-Fi")
    }

    @Test fun pausedTheHeaderSaysHowManyWait() {
        val ui = ui(
            listOf(rec("a", DownloadState.QUEUED, done = 200), rec("b", DownloadState.QUEUED)),
            DownloadLive(paused = true, batchStartedAt = midnight),
        )
        assertThat(ui.now).isNull()
        assertThat(ui.header!!.paused).isTrue()
        assertThat(headerText(ui.header!!)).isEqualTo("Paused · 2 waiting")
        assertThat(ui.upNext.map { downloadsText(it, now) }).containsExactly("200 B of 1.0 KB · Paused", "1.0 KB · Paused").inOrder()
    }

    @Test fun theStorageLineNamesTheVolume() {
        val records = listOf(rec("a", DownloadState.QUEUED, size = 9_800_000_000).copy(filePath = "/phone/packs/a.zim"))
        val ui = ui(records, volumes = listOf(VolumeSpace("/phone", "Phone", 41_000_000_000)))
        assertThat(storageText(ui.header!!)).isEqualTo("9.8 GB to go · 41 GB free on Phone")
    }

    @Test fun failuresAreStoppedWithTheirReason() {
        val ui = ui(listOf(rec("x", DownloadState.FAILED).copy(error = "The checksum didn't match.")))
        assertThat(ui.header).isNull()
        assertThat(ui.stopped.single().itemIds).containsExactly("x")
        assertThat(downloadsText(ui.stopped.single(), now)).isEqualTo("The checksum didn't match.")
        assertThat(ui.retriable).hasSize(1)
    }

    @Test fun aFileKiwixWithdrewOffersRemoveAndTheRoundStillFinishes() {
        // The phone's setup round: everything landed but WikEM, which Kiwix withdrew.
        val group = "onboarding:1"
        val ui = ui(
            listOf(
                rec("med", DownloadState.INSTALLED, group = group),
                rec("wikem", DownloadState.FAILED, group = group).copy(error = GoneFile.KIWIX),
            ),
            catalogIds = setOf("med"),
        )
        assertThat(ui.header).isNull()
        val row = ui.stopped.single()
        assertThat(row.gone).isTrue()
        assertThat(row.removeOnly).isTrue()
        assertThat(downloadsText(row, now)).isEqualTo("Kiwix no longer offers this file")
        assertThat(ui.retriable).isEmpty()
    }

    @Test fun anItemANewCatalogDroppedSaysSoAndOffersRemove() {
        val ui = ui(
            listOf(
                rec("dropped", DownloadState.QUEUED),
                rec("kept", DownloadState.QUEUED),
                rec("failed", DownloadState.FAILED).copy(error = "x answered 503"),
                // An archive.org book was never in the catalog: it isn't flagged.
                rec("ia-book", DownloadState.QUEUED, kind = ItemKind.BOOK).copy(filePath = "/internal/books/archive-org/x.epub"),
            ),
            catalogIds = setOf("kept"),
        )
        val byTitle = (ui.upNext + ui.stopped).associateBy { it.title }
        assertThat(downloadsText(byTitle.getValue("Title dropped"), now)).isEqualTo("1.0 KB · No longer in the catalog")
        assertThat(downloadsText(byTitle.getValue("Title failed"), now)).isEqualTo("No longer in the catalog")
        assertThat(byTitle.getValue("Title failed").removeOnly).isTrue()
        assertThat(byTitle.getValue("Title kept").notInCatalog).isFalse()
        assertThat(byTitle.getValue("Title ia-book").notInCatalog).isFalse()
    }

    @Test fun withNoCatalogNothingIsFlagged() {
        assertThat(ui(listOf(rec("x", DownloadState.QUEUED)), catalogIds = null).upNext.single().notInCatalog).isFalse()
    }

    @Test fun doneTodayStartsAtMidnightNewestFirstWithAGroupFoldedIntoOneRow() {
        val ui = ui(
            listOf(
                rec("yesterday", DownloadState.INSTALLED, updated = midnight - 1),
                rec("morning", DownloadState.INSTALLED, updated = midnight),
                rec("noon", DownloadState.INSTALLED, updated = midnight + 500),
                rec("g1", DownloadState.INSTALLED, updated = midnight + 100, group = "onboarding:1", kind = ItemKind.BOOK),
                rec("g2", DownloadState.INSTALLED, updated = midnight + 200, group = "onboarding:1", kind = ItemKind.BOOK),
                vectorsOf("g1", DownloadState.INSTALLED, updated = midnight + 300),
            ),
        )
        assertThat(ui.doneToday.map { it.title }).containsExactly("Title noon", "Setting up", "Title morning").inOrder()
        val setup = ui.doneToday[1]
        assertThat(downloadsText(setup, now)).isEqualTo("2 files · 2.1 KB + index for Ask")
    }

    @Test fun nothingLeftIsEmpty() {
        assertThat(ui(listOf(rec("old", DownloadState.INSTALLED, updated = 5))).isEmpty).isTrue()
    }

    @Test fun anAlbumsFilesAreLabelledByTheAlbumAndToldApartByName() {
        val group = "media:archive-org:blue"
        val tracks = (1..2).map { i ->
            rec("t$i", DownloadState.QUEUED, kind = ItemKind.AUDIO, group = group, title = "Blue").copy(filePath = "/music/blue/0$i Track.mp3")
        }
        val rows = ui(tracks).upNext
        assertThat(rows.map { it.title }).containsExactly("01 Track.mp3", "02 Track.mp3").inOrder()
        assertThat(rows.map { it.groupLabel }.distinct()).containsExactly("Blue")
    }

    @Test fun vectorsFoldIntoTheirPacksRowOrShowAsItsIndex() {
        val ui = ui(
            listOf(
                rec("wiki", DownloadState.INSTALLED, updated = midnight - 5).copy(filePath = "/packs/wiki.zim"),
                vectorsOf("wiki", DownloadState.QUEUED),
                rec("med", DownloadState.QUEUED),
                vectorsOf("med", DownloadState.QUEUED),
            ),
            packs = listOf(pack("wiki", "Wikipedia")),
        )
        assertThat(ui.upNext.map { it.title }).containsExactly("Wikipedia", "Title med").inOrder()
        val index = ui.upNext[0]
        assertThat(index.indexOnly).isTrue()
        assertThat(downloadsText(index, now)).isEqualTo("Index for Ask · 100 B · Next")
        val med = ui.upNext[1]
        assertThat(med.withIndex).isTrue()
        assertThat(med.itemIds).containsExactly("med", "med.vectors").inOrder()
        assertThat(med.bytes).isEqualTo(1_100)
    }

    @Test fun finishedPacksAndBooksOpenUnderTheirLibraryTitle() {
        val ui = ui(
            listOf(
                rec("wiki", DownloadState.INSTALLED, updated = midnight + 2).copy(filePath = "/packs/wiki.zim"),
                rec("emma", DownloadState.INSTALLED, updated = midnight + 1, kind = ItemKind.BOOK).copy(filePath = "/books/emma.epub"),
                rec("model", DownloadState.INSTALLED, updated = midnight, kind = ItemKind.MODEL),
            ),
            packs = listOf(pack("wiki", "Wikipedia")), books = listOf(book("book:granger:emma", "Emma").copy(filePath = "/books/emma.epub")),
        )
        assertThat(ui.doneToday.map { it.title to it.open }).containsExactly(
            "Wikipedia" to OpenTarget.Pack("wiki"),
            "Emma" to OpenTarget.Book("book:granger:emma"),
            "Title model" to null,
        ).inOrder()
    }

    @Test fun theBadgeCountsAGroupOnce() {
        val records = (1..5).map { rec("g$it", DownloadState.QUEUED, group = "onboarding:1") } +
            rec("a", DownloadState.FAILED) + vectorsOf("a", DownloadState.QUEUED) + rec("done", DownloadState.INSTALLED)
        assertThat(DownloadsModel.badge(records)).isEqualTo(2)
    }
}
