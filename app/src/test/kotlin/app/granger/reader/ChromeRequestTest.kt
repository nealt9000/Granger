package app.granger.reader

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ChromeRequestTest {
    @Test fun aSwipeFromBeforeTheReaderWasEnteredIsNotReplayed() {
        assertThat(isNewChromeRequest(atEntry = 3, current = 3)).isFalse()
    }

    @Test fun aSwipeWhileTheReaderIsShownCounts() {
        assertThat(isNewChromeRequest(atEntry = 3, current = 4)).isTrue()
    }
}
