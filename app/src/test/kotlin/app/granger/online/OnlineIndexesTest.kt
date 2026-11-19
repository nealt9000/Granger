package app.granger.online

import app.granger.FakeDownloads
import app.granger.catalogOf
import app.granger.core.catalog.SidecarEntry
import app.granger.core.catalog.TimeIndexEntry
import app.granger.core.downloads.DownloadState
import app.granger.core.downloads.ItemKind
import app.granger.packEntry
import app.granger.record
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.io.File

/** Plan O1 Task 8: Wikipedia online's small index set, queued once per knowledge language, one copy. */
class OnlineIndexesTest {
    private val gqid = "c".repeat(64)
    private val gtime = "d".repeat(64)
    private fun top(lang: String) = packEntry(OnlineIndexes.topOf(lang)).copy(
        sidecars = listOf(SidecarEntry("gqid", "https://x.test/$lang.gqid", gqid, 16_953_344)),
        timeIndex = TimeIndexEntry("https://x.test/$lang.gtime", gtime, 2_330_624),
    )
    private val catalog = catalogOf(packs = listOf(top("en"), top("es")))
    private val downloads = FakeDownloads()
    private var wifiOnly = true
    private val indexes = OnlineIndexes(downloads, { File("/internal") }, { wifiOnly })

    private suspend fun reconcile(langs: List<String> = listOf("en"), enabled: Boolean = true, online: Boolean = true) =
        indexes.reconcile(catalog, downloads.list.value, langs, enabled, online)

    @Test fun queuedOncePerLanguageAsSidecarsOfTheOnlineSource() = runTest {
        reconcile(listOf("en", "es"))
        assertThat(downloads.enqueuedSpecs.map { it.itemId }).containsExactly(
            "wikipedia_en_online.gqid", "wikipedia_en_online.timeindex", "wikipedia_es_online.gqid", "wikipedia_es_online.timeindex",
        )
        // Their own files, never a pack's path.
        assertThat(downloads.enqueuedSpecs.map { it.relativePath.substringAfter('/').substringBefore("_online_") }).containsExactly("wikipedia_en", "wikipedia_en", "wikipedia_es", "wikipedia_es")
        assertThat(downloads.list.value.all { it.wifiOnly }).isTrue()
        reconcile(listOf("en", "es"))
        assertThat(downloads.enqueuedSpecs).hasSize(4)
    }

    @Test fun nothingWhileOfflineOrOff() = runTest {
        reconcile(online = false)
        reconcile(enabled = false)
        assertThat(downloads.enqueuedSpecs).isEmpty()
    }

    @Test fun aPackWithTheSameFileServesForIt() = runTest {
        downloads.list.value = listOf(
            record("wikipedia_en_top_mini", DownloadState.INSTALLED),  // its file: /internal/packs/wikipedia_en_top_mini.zim
            record("wikipedia_en_top_mini.gqid", DownloadState.INSTALLED, sha256 = gqid, kind = ItemKind.SIDECAR),
            record("wikipedia_en_online.gqid", DownloadState.INSTALLED, sha256 = gqid, kind = ItemKind.SIDECAR),
        )
        reconcile()
        assertThat(downloads.calls).contains("removeVersion:wikipedia_en_online.gqid:$gqid")
        assertThat(downloads.enqueuedSpecs.map { it.itemId }).containsExactly("wikipedia_en_online.timeindex")
    }

    @Test fun aHiddenPackDoesntServeForIt() = runTest {
        // Preview: no Wikipedia on the phone hides the pack, so the online set is wanted after all.
        downloads.list.value = listOf(
            record("wikipedia_en_top_mini", DownloadState.INSTALLED),
            record("wikipedia_en_top_mini.gqid", DownloadState.INSTALLED, sha256 = gqid, kind = ItemKind.SIDECAR),
        )
        indexes.reconcile(catalog, downloads.list.value, listOf("en"), enabled = true, online = true, packFiles = emptySet())
        assertThat(downloads.enqueuedSpecs.map { it.itemId }).containsExactly("wikipedia_en_online.gqid", "wikipedia_en_online.timeindex")
    }

    @Test fun aLanguageDroppedTakesItsSet() = runTest {
        downloads.list.value = listOf(record("wikipedia_es_online.gqid", DownloadState.INSTALLED, sha256 = gqid, kind = ItemKind.SIDECAR))
        reconcile(listOf("en"))
        assertThat(downloads.calls).contains("remove:wikipedia_es_online.gqid")
    }

    @Test fun aNewSnapshotReplacesTheOld() = runTest {
        val old = "e".repeat(64)
        downloads.list.value = listOf(
            record("wikipedia_en_online.gqid", DownloadState.INSTALLED, sha256 = old, kind = ItemKind.SIDECAR),
            record("wikipedia_en_online.gqid", DownloadState.INSTALLED, sha256 = gqid, kind = ItemKind.SIDECAR),
        )
        reconcile()
        assertThat(downloads.calls).contains("removeVersion:wikipedia_en_online.gqid:$old")
    }

    @Test fun exploreSaysWhatTheSetIsWaitingFor() = runTest {
        reconcile()
        assertThat(OnlineIndexes.waitOf(downloads.list.value, unmetered = false)).isEqualTo(IndexWait.WAITING_FOR_WIFI)
        assertThat(OnlineIndexes.waitOf(downloads.list.value, unmetered = true)).isEqualTo(IndexWait.COMING)
        // Get it now: queued again without waiting for Wi-Fi.
        indexes.startNow(downloads.list.value)
        assertThat(downloads.calls.filter { it.startsWith("cancel:") }).hasSize(2)
        downloads.list.value = emptyList()
        reconcile()
        assertThat(downloads.list.value.none { it.wifiOnly }).isTrue()
        assertThat(OnlineIndexes.waitOf(emptyList(), unmetered = false)).isEqualTo(IndexWait.NONE)
    }
}
