package app.granger.core.speech

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The English titles, for JVM tests; [VoiceTitlesTest] checks the resources read the same. */
internal val ENGLISH = VoiceTitles("Granger voice (%1\$s)", "System voice %1\$s (%2\$s)", "System voice default (%1\$s)")

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class VoiceTitlesTest {
    private val titles = VoiceTitles.of(ApplicationProvider.getApplicationContext<Context>())

    @Test fun theResourcesReadAsBefore() {
        assertThat(titles.granger("Maggie")).isEqualTo("Granger voice (Maggie)")
        assertThat(titles.system("iom", "en-US")).isEqualTo("System voice iom (en-US)")
        assertThat(titles.system(null, "en-US")).isEqualTo("System voice default (en-US)")
        for (t in listOf(titles, ENGLISH)) assertThat(SystemSpeech.Factory.title("en-us", "en-US", t)).isEqualTo("System voice default (en-US)")
    }

    @Test fun bareTitlesAreTheNamesAlone() {
        assertThat(VoiceTitles.BARE.granger("Maggie")).isEqualTo("Maggie")
        assertThat(VoiceTitles.BARE.system("iom", "en-US")).isEqualTo("iom (en-US)")
    }
}
