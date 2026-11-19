package app.granger.online

import app.granger.core.downloads.NetworkMode
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** Plan O3 Task 1: reach from the policy and the connection; what opens. */
class ReachTest {
    @Test fun thePolicyWinsAndTheConnectionDecidesWhenItsOpen() {
        assertThat(ReachState.of(NetworkMode.ONLINE, connected = true)).isEqualTo(ReachState.ONLINE)
        assertThat(ReachState.of(NetworkMode.ONLINE, connected = false)).isEqualTo(ReachState.NO_SIGNAL)
        assertThat(ReachState.of(NetworkMode.LOCAL_ONLY, connected = true)).isEqualTo(ReachState.LOCAL_ONLY)
        assertThat(ReachState.of(NetworkMode.NEVER, connected = true)).isEqualTo(ReachState.NEVER)
    }

    @Test fun onThePhoneAndSavedAlwaysOpenOnlineOnlyWhenOnline() {
        for (r in ReachState.entries) {
            assertThat(openable(Availability.OnPhone(true), r)).isTrue()
            assertThat(openable(Availability.Saved, r)).isTrue()
            assertThat(openable(Availability.Nowhere, r)).isFalse()
            assertThat(openable(Availability.Online, r)).isEqualTo(r == ReachState.ONLINE)
        }
    }
}
