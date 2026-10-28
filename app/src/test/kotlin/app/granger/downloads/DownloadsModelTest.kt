package app.granger.downloads

import app.granger.book
import app.granger.core.downloads.DownloadRecord
import app.granger.core.downloads.DownloadState
import app.granger.core.downloads.ItemKind
import app.granger.core.downloads.VectorDownloads
import app.granger.pack
import app.granger.record
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class DownloadsModelTest {
    private val midnight = 10_000L

    private fun rec(
        itemId: String, state: DownloadState, created: Long = 1, updated: Long = created, kind: ItemKind = ItemKind.PACK,
        size: Long = 1_000, done: Long = 0, group: String? = null, title: String = "Title $itemId",
    ): DownloadRecord = record(itemId, state, kind = kind, sizeBytes = size, bytesDone = done, title = title)
        .copy(createdAt = created, updatedAt = updated, groupId = group)

    private fun vectorsOf(parent: String, state: DownloadState, created: Long = 1, updated: Long = created) =
        rec(VectorDownloads.itemIdOf(parent), state, created, updated, kind = ItemKind.VECTORS, size = 100)

    @Test fun runningComesFirstThenQueuedInQueueOrder() {
        val ui = DownloadsModel.of(
            listOf(
                rec("late", DownloadState.QUEUED, created = 30),
                rec("early", DownloadState.QUEUED, created = 10),
                rec("busy", DownloadState.RUNNING, created = 20, done = 400),
            ),
            midnight,
        )
        assertThat(ui.now.map { it.title }).containsExactly("Title busy", "Title early", "Title late").inOrder()
        assertThat(ui.now.first().state).isEqualTo(RowState.RUNNING)
        assertThat(downloadsText(ui.now.first())).isEqualTo("400 B of 1.0 KB")
        assertThat(ui.badge).isEqualTo(3)
    }

    @Test fun failuresAreStoppedWithTheirReason() {
        val ui = DownloadsModel.of(listOf(rec("x", DownloadState.FAILED).copy(error = "The checksum didn't match.")), midnight)
        assertThat(ui.now).isEmpty()
        assertThat(ui.stopped.single().itemIds).containsExactly("x")
        assertThat(downloadsText(ui.stopped.single())).isEqualTo("The checksum didn't match.")
        assertThat(ui.badge).isEqualTo(1)
    }

    @Test fun doneTodayStartsAtMidnightNewestFirst() {
        val ui = DownloadsModel.of(
            listOf(
                rec("yesterday", DownloadState.INSTALLED, updated = midnight - 1),
                rec("morning", DownloadState.INSTALLED, updated = midnight),
                rec("noon", DownloadState.INSTALLED, updated = midnight + 500),
            ),
            midnight,
        )
        assertThat(ui.doneToday.map { it.title }).containsExactly("Title noon", "Title morning").inOrder()
        assertThat(ui.badge).isEqualTo(0)
    }

    @Test fun nothingLeftIsEmpty() {
        val ui = DownloadsModel.of(listOf(rec("old", DownloadState.INSTALLED, updated = 5)), midnight)
        assertThat(ui.isEmpty).isTrue()
    }

    @Test fun aGroupIsOneRowThatOpensToItsFiles() {
        val group = "onboarding:7"
        val ui = DownloadsModel.of(
            listOf(
                rec("a", DownloadState.INSTALLED, updated = 5, group = group),
                rec("b", DownloadState.RUNNING, group = group, done = 500),
                rec("c", DownloadState.QUEUED, group = group),
            ),
            midnight,
        )
        val row = ui.now.single()
        assertThat(row.title).isEqualTo("Setting up")
        assertThat(row.groupId).isEqualTo(group)
        assertThat(row.files.map { it.name to it.state }).containsExactly(
            "Title a" to DownloadState.INSTALLED, "Title b" to DownloadState.RUNNING, "Title c" to DownloadState.QUEUED,
        ).inOrder()
        assertThat(downloadsText(row)).isEqualTo("1 of 3 · 1.5 KB of 3.0 KB")
    }

    @Test fun anAlbumIsTitledByItsAlbumAndItsFilesByName() {
        val group = "media:archive-org:blue"
        val tracks = (1..2).map { i ->
            rec("t$i", DownloadState.QUEUED, kind = ItemKind.AUDIO, group = group, title = "Blue").copy(filePath = "/music/blue/0$i Track.mp3")
        }
        val row = DownloadsModel.of(tracks, midnight).now.single()
        assertThat(row.title).isEqualTo("Blue")
        assertThat(row.files.map { it.name }).containsExactly("01 Track.mp3", "02 Track.mp3").inOrder()
    }

    @Test fun aGroupWithAFailureIsStoppedAndCounted() {
        val group = "media:x:y"
        val ui = DownloadsModel.of(
            listOf(rec("a", DownloadState.FAILED, group = group), rec("b", DownloadState.QUEUED, group = group)),
            midnight,
        )
        assertThat(ui.now).isEmpty()
        assertThat(downloadsText(ui.stopped.single())).isEqualTo("0 of 2 · 1 failed")
    }

    @Test fun vectorsFoldIntoTheirPack() {
        val ui = DownloadsModel.of(
            listOf(rec("wiki", DownloadState.INSTALLED, updated = midnight + 1), vectorsOf("wiki", DownloadState.RUNNING)),
            midnight,
        )
        // Never a row of their own: the pack's row is still coming while its index is.
        assertThat(ui.doneToday).isEmpty()
        val row = ui.now.single()
        assertThat(row.title).isEqualTo("Title wiki")
        assertThat(row.withIndex).isTrue()
        assertThat(row.itemIds).containsExactly("wiki", "wiki.vectors").inOrder()
        assertThat(row.bytes).isEqualTo(1_100)
        assertThat(downloadsText(row)).endsWith(" + index for Ask")
    }

    @Test fun vectorsOfAPackFromBeforeTodayShowUnderThePacksTitle() {
        val ui = DownloadsModel.of(
            listOf(
                rec("wiki", DownloadState.INSTALLED, updated = 5).copy(filePath = "/packs/wiki.zim"),
                vectorsOf("wiki", DownloadState.QUEUED),
            ),
            midnight, packs = listOf(pack("wiki", "Wikipedia")),
        )
        val row = ui.now.single()
        assertThat(row.title).isEqualTo("Wikipedia")
        assertThat(row.indexOnly).isTrue()
        assertThat(row.itemIds).containsExactly("wiki.vectors")
        assertThat(downloadsText(row)).isEqualTo("Index for Ask · Waiting for Wi-Fi")
    }

    @Test fun vectorsOfABookInTheSetupGroupFoldIntoTheGroup() {
        val group = "onboarding:1"
        val ui = DownloadsModel.of(
            listOf(
                rec("b1", DownloadState.INSTALLED, updated = midnight + 1, kind = ItemKind.BOOK, group = group),
                rec("b2", DownloadState.QUEUED, kind = ItemKind.BOOK, group = group),
                vectorsOf("b1", DownloadState.QUEUED),
            ),
            midnight,
        )
        val row = ui.now.single()
        assertThat(row.title).isEqualTo("Setting up")
        assertThat(row.files).hasSize(2)
        assertThat(row.itemIds).containsExactly("b1.vectors")
        assertThat(row.withIndex).isTrue()
    }

    @Test fun finishedPacksAndBooksOpenUnderTheirLibraryTitle() {
        val ui = DownloadsModel.of(
            listOf(
                rec("wiki", DownloadState.INSTALLED, updated = midnight + 2).copy(filePath = "/packs/wiki.zim"),
                rec("emma", DownloadState.INSTALLED, updated = midnight + 1, kind = ItemKind.BOOK).copy(filePath = "/books/emma.epub"),
                rec("model", DownloadState.INSTALLED, updated = midnight, kind = ItemKind.MODEL),
            ),
            midnight, packs = listOf(pack("wiki", "Wikipedia")), books = listOf(book("book:granger:emma", "Emma").copy(filePath = "/books/emma.epub")),
        )
        assertThat(ui.doneToday.map { it.title to it.open }).containsExactly(
            "Wikipedia" to OpenTarget.Pack("wiki"),
            "Emma" to OpenTarget.Book("book:granger:emma"),
            "Title model" to null,
        ).inOrder()
    }

    @Test fun anUnfinishedRowOpensNothing() {
        val ui = DownloadsModel.of(listOf(rec("wiki", DownloadState.RUNNING).copy(filePath = "/packs/wiki.zim")), midnight, packs = listOf(pack("wiki")))
        assertThat(ui.now.single().open).isNull()
    }
}
