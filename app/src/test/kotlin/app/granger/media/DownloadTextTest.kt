package app.granger.media

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.granger.sources.ItemStatus
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DownloadTextTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    private fun text(status: ItemStatus, done: Int, total: Int, unit: DownloadUnit, onWifi: Boolean = false) =
        downloadText(status, done, total, unit, onWifi)?.resolve(context)

    @Test fun aStoppedDownloadSaysWhatItsWaitingFor() {
        assertThat(text(ItemStatus.Queued(wifiOnly = true), 5, 13, DownloadUnit.CHAPTERS)).isEqualTo("Waiting for Wi-Fi: 5 of 13 chapters")
        assertThat(text(ItemStatus.Queued(wifiOnly = true), 5, 13, DownloadUnit.CHAPTERS, onWifi = true)).isEqualTo("Queued: 5 of 13 chapters")
        assertThat(text(ItemStatus.Queued(wifiOnly = false), 5, 13, DownloadUnit.CHAPTERS)).isEqualTo("Waiting for a connection: 5 of 13 chapters")
        assertThat(text(ItemStatus.Queued(wifiOnly = true, lastError = "a.test answered 503"), 2, 7, DownloadUnit.TRACKS)).isEqualTo("Retrying: 2 of 7 tracks")
        assertThat(text(ItemStatus.Downloading(0.5f), 5, 13, DownloadUnit.CHAPTERS)).isEqualTo("Downloading: 5 of 13 chapters")
        assertThat(text(ItemStatus.Queued(wifiOnly = true), 0, 0, DownloadUnit.TRACKS)).isEqualTo("Waiting for Wi-Fi")
        assertThat(text(ItemStatus.Partial(1, 1), 1, 1, DownloadUnit.TRACKS)).isEqualTo("Partly downloaded: 1 of 1 track")
        assertThat(text(ItemStatus.Installed, 13, 13, DownloadUnit.CHAPTERS)).isNull()
    }
}
