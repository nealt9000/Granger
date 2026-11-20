package app.granger.online

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Plan O7 call 2: whether an install had Granger before it read online is fixed at its first start. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class NetworkPolicyInstallTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before fun clear() { context.getSharedPreferences("online", Context.MODE_PRIVATE).edit().clear().commit() }

    @Test fun aFreshInstallIsMarkedNewAndStaysSoAfterOnboarding() {
        NetworkPolicy(context).markInstall { false }
        // Onboarding finishes later (Welcome skipped): the mark doesn't move.
        NetworkPolicy(context).markInstall { true }
        assertThat(NetworkPolicy(context).hadGrangerBefore).isFalse()
    }

    @Test fun anInstallThatFinishedOnboardingBeforeIsOlder() {
        NetworkPolicy(context).markInstall { true }
        assertThat(NetworkPolicy(context).hadGrangerBefore).isTrue()
    }

    @Test fun onceThePrivacyLineIsSeenTheCardNeverShowsSoItsNew() {
        NetworkPolicy(context).setPrivacySeen()
        NetworkPolicy(context).markInstall { true }
        assertThat(NetworkPolicy(context).hadGrangerBefore).isFalse()
    }
}
