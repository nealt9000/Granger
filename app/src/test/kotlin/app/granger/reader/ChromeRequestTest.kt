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

    // A system-bar swipe while reading full screen brings the chrome back (Plan U3c Decision 5).

    @Test fun barsStillGoingAfterTheyWereHiddenAreNotASwipe() {
        val watch = TransientBarsWatch()
        assertThat(watch.swipedBack(hidden = false, barsVisible = true)).isFalse()
        // Asked to hide: they're visible for a moment yet.
        assertThat(watch.swipedBack(hidden = true, barsVisible = true)).isFalse()
        assertThat(watch.swipedBack(hidden = true, barsVisible = false)).isFalse()
    }

    @Test fun barsComingBackWhileHiddenAreASwipeOnce() {
        val watch = TransientBarsWatch()
        watch.swipedBack(hidden = true, barsVisible = true)
        watch.swipedBack(hidden = true, barsVisible = false)
        assertThat(watch.swipedBack(hidden = true, barsVisible = true)).isTrue()
        assertThat(watch.swipedBack(hidden = true, barsVisible = true)).isFalse()
    }

    @Test fun showingTheChromeStartsTheWatchAgain() {
        val watch = TransientBarsWatch()
        watch.swipedBack(hidden = true, barsVisible = false)
        assertThat(watch.swipedBack(hidden = false, barsVisible = true)).isFalse()
        // Hidden again: the bars must be seen gone first.
        assertThat(watch.swipedBack(hidden = true, barsVisible = true)).isFalse()
    }
}
