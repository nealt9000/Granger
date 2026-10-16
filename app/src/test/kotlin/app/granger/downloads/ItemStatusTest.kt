package app.granger.downloads

import app.granger.SHA_A
import app.granger.SHA_B
import app.granger.core.ai.DeviceTier
import app.granger.core.catalog.ModelRole
import app.granger.core.downloads.DownloadState
import app.granger.core.downloads.ItemKind
import app.granger.core.library.PackStatus
import app.granger.modelEntry
import app.granger.pack
import app.granger.packEntry
import app.granger.record
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ItemStatusTest {
    private val med = packEntry("med")
    private val e2b = modelEntry("e2b")
    private val tier = DeviceTier.THINK_DEEPER

    @Test fun nothingYet() {
        assertThat(statusOf(med, emptyList(), emptyList(), tier)).isEqualTo(ItemStatus.NotInstalled)
    }

    @Test fun unfinishedRecordsDecide() {
        assertThat(statusOf(med, listOf(record("med", DownloadState.QUEUED, wifiOnly = true)), emptyList(), tier))
            .isEqualTo(ItemStatus.Queued(wifiOnly = true))
        assertThat(statusOf(med, listOf(record("med", DownloadState.RUNNING, bytesDone = 250)), emptyList(), tier))
            .isEqualTo(ItemStatus.Downloading(0.25f))
        assertThat(statusOf(med, listOf(record("med", DownloadState.FAILED, error = "Checksum mismatch")), emptyList(), tier))
            .isEqualTo(ItemStatus.Failed("Checksum mismatch"))
    }

    @Test fun aQueuedRetryCarriesTheLastError() {
        val records = listOf(record("med", DownloadState.QUEUED, wifiOnly = false, error = "a.test answered 503"))
        assertThat(statusOf(med, records, emptyList(), tier))
            .isEqualTo(ItemStatus.Queued(wifiOnly = false, lastError = "a.test answered 503"))
    }

    @Test fun anUnfinishedUpdateShowsOverTheInstalledVersion() {
        val records = listOf(record("med", DownloadState.INSTALLED, sha256 = SHA_B), record("med", DownloadState.RUNNING, bytesDone = 500))
        assertThat(statusOf(med, records, emptyList(), tier)).isEqualTo(ItemStatus.Downloading(0.5f))
    }

    @Test fun packIsInstalledWhenTheLibraryHasItsZim() {
        assertThat(statusOf(med, emptyList(), listOf(pack("u-med")), tier)).isEqualTo(ItemStatus.Installed)
        assertThat(statusOf(med, emptyList(), listOf(pack("u-med", status = PackStatus.CORRUPT)), tier)).isEqualTo(ItemStatus.Installed)
    }

    @Test fun packIsInstalledFromItsRecordBeforeTheLibraryRefreshes() {
        assertThat(statusOf(med, listOf(record("med", DownloadState.INSTALLED)), emptyList(), tier)).isEqualTo(ItemStatus.Installed)
    }

    @Test fun olderVersionMeansUpdateAvailable() {
        val records = listOf(record("med", DownloadState.INSTALLED, sha256 = SHA_B))
        assertThat(statusOf(med, records, listOf(pack("u-old-snapshot")), tier)).isEqualTo(ItemStatus.UpdateAvailable)
    }

    @Test fun aMissingPackIsNotInstalled() {
        assertThat(statusOf(med, emptyList(), listOf(pack("u-med", status = PackStatus.MISSING)), tier)).isEqualTo(ItemStatus.NotInstalled)
    }

    @Test fun modelsMatchOnTheirExactVersion() {
        assertThat(statusOf(e2b, listOf(record("e2b", DownloadState.INSTALLED, sha256 = SHA_B, kind = ItemKind.MODEL)), emptyList(), tier))
            .isEqualTo(ItemStatus.Installed)
        assertThat(statusOf(e2b, listOf(record("e2b", DownloadState.INSTALLED, sha256 = SHA_A, kind = ItemKind.MODEL)), emptyList(), tier))
            .isEqualTo(ItemStatus.UpdateAvailable)
    }

    @Test fun modelsTooBigForThePhone() {
        val e4b = modelEntry("e4b", role = ModelRole.LARGE)
        assertThat(statusOf(e4b, emptyList(), emptyList(), DeviceTier.ASK)).isEqualTo(ItemStatus.NeedsMoreMemory)
        assertThat(statusOf(e4b, emptyList(), emptyList(), DeviceTier.THINK_DEEPER)).isEqualTo(ItemStatus.NotInstalled)
        assertThat(statusOf(e2b, emptyList(), emptyList(), DeviceTier.LIBRARY_ONLY)).isEqualTo(ItemStatus.NeedsMoreMemory)
        assertThat(statusOf(e2b, emptyList(), emptyList(), DeviceTier.ASK)).isEqualTo(ItemStatus.NotInstalled)
    }

    @Test fun installedPackIdFollowsTheZimOrTheFile() {
        assertThat(installedPackId(med, emptyList(), listOf(pack("u-med")))).isEqualTo("u-med")
        val older = pack("u-old").copy(filePath = "/internal/packs/med.zim")
        assertThat(installedPackId(med, listOf(record("med", DownloadState.INSTALLED, sha256 = SHA_B)), listOf(older))).isEqualTo("u-old")
        assertThat(installedPackId(med, emptyList(), listOf(older))).isNull()
    }
}
