package app.granger

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class FeaturesTest {
    @Test fun researchIsOnInDebug() {
        // Unit tests run against the debug build; a release build gates Research off.
        assertThat(BuildConfig.DEBUG).isTrue()
        assertThat(Features.RESEARCH).isTrue()
    }
}
