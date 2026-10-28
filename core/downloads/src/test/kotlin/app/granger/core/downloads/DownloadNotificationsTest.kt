package app.granger.core.downloads

import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

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
}
