package app.granger.reader

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The Wikipedia page keeps the line being read where it is on screen when the toolbar shows or hides: the WebView
 * tells the page how far its top moved, before the page sees its new size, and the page script scrolls by the rest.
 */
class ReaderSpotTest {
    @Test fun theToolbarComingUpTellsThePageItsTopMovedDown() {
        assertThat(ReaderPageScript.spotShift(before = 0, top = 210, resized = true)).isEqualTo(ReaderPageScript.keepSpot(210))
    }

    @Test fun theToolbarHidingTellsThePageItsTopMovedUp() {
        assertThat(ReaderPageScript.spotShift(before = 210, top = 0, resized = true)).isEqualTo(ReaderPageScript.keepSpot(-210))
    }

    @Test fun aResizeThatLeavesTheTopInPlaceNeedsNoMessage() {
        // The rail beside it, or the keyboard below it: the page script keeps the spot at the same height by itself.
        assertThat(ReaderPageScript.spotShift(before = 210, top = 210, resized = true)).isNull()
    }

    @Test fun theFirstLayoutNeedsNoMessage() {
        assertThat(ReaderPageScript.spotShift(before = null, top = 210, resized = false)).isNull()
        assertThat(ReaderPageScript.spotShift(before = 0, top = 210, resized = false)).isNull()
    }

    @Test fun theMessageReachesThePageScript() {
        assertThat(ReaderPageScript.keepSpot(210)).isEqualTo("window.__grangerSpot && window.__grangerSpot.shift(210)")
        val source = ReaderPageScript.SOURCE
        // Taken near the top as the page scrolls; put back on resize, less the shift, before the new layout paints.
        assertThat(source).contains("window.__grangerSpot = {")
        assertThat(source).contains("addEventListener('resize'")
        assertThat(source).contains("caretRangeFromPoint")
        assertThat(source).contains("spot.y - shift / scale()")
    }
}
