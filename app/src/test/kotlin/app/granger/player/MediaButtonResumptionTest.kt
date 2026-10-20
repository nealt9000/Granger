package app.granger.player

import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import android.content.Context
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A headset play press after the process died reaches [app.granger.core.player.PlaybackService.onPlaybackResumption] only
 * through Media3's media-button receiver, which then starts the library service. Without the receiver the press goes
 * nowhere (M3 walkthrough, 2026-10-20).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MediaButtonResumptionTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test fun mediaButtonsHaveAReceiver() {
        val intent = Intent(Intent.ACTION_MEDIA_BUTTON).setPackage(context.packageName)
        val receivers = context.packageManager.queryBroadcastReceivers(intent, 0).map { it.activityInfo.name }
        assertThat(receivers).contains("androidx.media3.session.MediaButtonReceiver")
    }

    @Test fun theReceiverCanFindTheLibraryService() {
        val intent = Intent("androidx.media3.session.MediaLibraryService").setPackage(context.packageName)
        val services = context.packageManager.queryIntentServices(intent, 0).map { it.serviceInfo.name }
        assertThat(services).containsExactly("app.granger.core.player.PlaybackService")
    }
}
