package app.granger.core.downloads

import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.Shadows.shadowOf
import androidx.test.core.app.ApplicationProvider

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DownloadNotificationsTest {
    private val launch = Intent(Intent.ACTION_MAIN)
        .addCategory(Intent.CATEGORY_LAUNCHER)
        .setComponent(ComponentName("app.granger", "app.granger.MainActivity"))

    @Test fun theNotificationOpensDownloads() {
        val intent = downloadsIntent(launch)
        assertThat(intent.data).isEqualTo(Uri.parse("granger://downloads"))
        assertThat(intent.data.toString()).isEqualTo(DOWNLOADS_LINK)
        // Still the app's own activity: explicit, so no intent filter is needed.
        assertThat(intent.component).isEqualTo(launch.component)
    }

    @Test fun aRunningAppGetsItAsANewIntent() {
        val flags = downloadsIntent(launch).flags
        assertThat(flags and Intent.FLAG_ACTIVITY_SINGLE_TOP).isNotEqualTo(0)
        assertThat(flags and Intent.FLAG_ACTIVITY_CLEAR_TOP).isNotEqualTo(0)
        assertThat(flags and Intent.FLAG_ACTIVITY_NEW_TASK).isNotEqualTo(0)
    }

    @Test fun theLaunchIntentIsLeftAsItWas() {
        downloadsIntent(launch)
        assertThat(launch.data).isNull()
    }

    private val summary = QueueSummary(
        current = DownloadRecord(
            "wikipedia_en_top_maxi", SHA_1, kind = ItemKind.PACK, title = "Wikipedia Top", urls = emptyList(), sizeBytes = 10,
            filePath = "/p/top.zim", snapshotDate = "", wifiOnly = true, state = DownloadState.RUNNING, createdAt = 1, updatedAt = 1,
        ),
        files = 14, filesDone = 3, bytes = 9_800, bytesDone = 1_200, timeLeftMillis = 25 * 60_000L,
    )

    @Test fun oneNotificationForTheQueue() {
        assertThat(notificationText(summary)).isEqualTo("Downloading Wikipedia Top" to "3 of 14 · about 25 min left")
        assertThat(notificationText(summary.copy(timeLeftMillis = null))).isEqualTo("Downloading Wikipedia Top" to "3 of 14")
        assertThat(notificationText(QueueSummary())).isEqualTo("Downloading" to "Starting…")
    }

    @Test fun itShowsTheRoundsBarAndOffersPause() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val notification = DownloadNotifications(context).build(summary)
        assertThat(notification.extras.getInt(android.app.Notification.EXTRA_PROGRESS_MAX)).isEqualTo(1000)
        assertThat(notification.extras.getInt(android.app.Notification.EXTRA_PROGRESS)).isEqualTo(122)
        val pause = notification.actions.single()
        assertThat(pause.title.toString()).isEqualTo("Pause")
        val sent = shadowOf(pause.actionIntent).savedIntent
        assertThat(sent.action).isEqualTo(DownloadActionReceiver.ACTION_PAUSE)
        assertThat(sent.component!!.className).isEqualTo(DownloadActionReceiver::class.java.name)
        assertThat(shadowOf(pause.actionIntent).isBroadcastIntent).isTrue()
    }
}
