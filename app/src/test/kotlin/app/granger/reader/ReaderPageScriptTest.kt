package app.granger.reader

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The page scripts' text (Plan U3c Decision 5); Robolectric can't run the JavaScript. Kiwix main pages scroll inside
 * their own full-screen `#container`, so the bars' padding goes on it too, and its scrolls move the chrome. It runs
 * on Robolectric for `org.json`, which the read-aloud script quotes with.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ReaderPageScriptTest {
    private val script = chromePaddingScript(64, 56, leftDp = 80)

    @Test fun theRootIsPaddedForTheBarsAndTheRail() {
        assertThat(script).contains("document.documentElement.style")
        assertThat(script).contains("s.setProperty('padding-top', '64px', 'important')")
        assertThat(script).contains("s.setProperty('padding-bottom', '56px', 'important')")
        assertThat(script).contains("s.setProperty('padding-left', '80px', 'important')")
        assertThat(script).contains("s.setProperty('padding-right', '0px', 'important')")
        assertThat(script).contains("s.setProperty('scroll-padding-top', '64px')")
        assertThat(script).contains("window.__grangerTop = 64")
    }

    /** A full-screen box that scrolls (absolute or fixed, at the top left, ≥ 90 % high) gets the same four, border-box. */
    @Test fun aPageScrollingInItsOwnFullScreenBoxIsPaddedThere() {
        assertThat(script).contains("document.body.children")
        assertThat(script).contains("firstElementChild")
        assertThat(script).contains("cs.position !== 'absolute' && cs.position !== 'fixed'")
        assertThat(script).contains("(auto|scroll)")
        assertThat(script).contains("r.top <= 2 && r.left <= 2 && r.height >= window.innerHeight * 0.9")
        assertThat(script).contains("pad(el.style)")
        assertThat(script).contains("el.style.setProperty('box-sizing', 'border-box', 'important')")
    }

    /** At document start there's no body yet: the script runs again once the DOM is loaded. */
    @Test fun atDocumentStartItRunsAgainWhenTheDomIsLoaded() {
        assertThat(script).contains("if (!document.body) return;")
        assertThat(script).contains("document.readyState === 'loading'")
        assertThat(script).contains("document.addEventListener('DOMContentLoaded', apply, { once: true })")
    }

    /** Scrolls are heard in the capture phase, so an inner page-sized scroller reports them too. */
    @Test fun anInnerScrollerReportsItsScrolls() {
        val source = ReadAloudScript.SOURCE
        assertThat(source).contains("window.addEventListener('scroll', function (e) {")
        assertThat(source).contains("}, { passive: true, capture: true });")
        assertThat(source).contains("y = el.scrollTop;")
        assertThat(source).contains("if (el.clientHeight < window.innerHeight * 0.5) return;")
        assertThat(source).contains("${ReaderPageScript.BRIDGE_NAME}.onUserScroll(dy * scale, hand)")
    }
}
