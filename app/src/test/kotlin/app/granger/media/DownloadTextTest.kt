package app.granger.media

import app.granger.sources.ItemStatus
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class DownloadTextTest {
    @Test fun aStoppedDownloadSaysWhatItsWaitingFor() {
        assertThat(downloadText(ItemStatus.Queued(wifiOnly = true), 5, 13, "chapters")).isEqualTo("Waiting for Wi-Fi: 5 of 13 chapters")
        assertThat(downloadText(ItemStatus.Queued(wifiOnly = false), 5, 13, "chapters")).isEqualTo("Waiting for a connection: 5 of 13 chapters")
        assertThat(downloadText(ItemStatus.Queued(wifiOnly = true, lastError = "a.test answered 503"), 2, 7, "tracks")).isEqualTo("Retrying: 2 of 7 tracks")
        assertThat(downloadText(ItemStatus.Downloading(0.5f), 5, 13, "chapters")).isEqualTo("Downloading: 5 of 13 chapters")
        assertThat(downloadText(ItemStatus.Installed, 13, 13, "chapters")).isNull()
    }
}
