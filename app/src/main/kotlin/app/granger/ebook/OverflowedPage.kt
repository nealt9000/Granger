package app.granger.ebook

import org.readium.r2.shared.publication.Link
import org.readium.r2.shared.util.Url

/** Finds the page Readium's next-page tap can't leave: see OverflowEscapingTurns in the reader screen. */
internal object OverflowedPage {
    /** The page's horizontal scroll, the screen's width and the page's whole width, in CSS pixels. */
    const val JS = "(function(){var s=document.scrollingElement;return s.scrollLeft+','+window.innerWidth+','+s.scrollWidth})()"

    /**
     * Whether [answer], [JS]'s result, is the last whole screen of a page with less than a screen left over. Readium's
     * columns make a page a whole number of screens, so any such rest is overflow. A scroll between two screens (a
     * turn still moving) doesn't count, and neither does an answer that can't be read.
     */
    fun isTrapped(answer: String?): Boolean {
        val parts = answer?.trim('"')?.split(',')?.map { it.toDoubleOrNull() ?: return false } ?: return false
        if (parts.size != 3) return false
        val (scrolled, screen, whole) = parts
        if (screen <= 0) return false
        val intoScreen = scrolled % screen
        if (intoScreen > SLACK_PX && intoScreen < screen - SLACK_PX) return false
        val rest = whole - scrolled - screen
        return rest >= SLACK_PX && rest < screen - SLACK_PX
    }

    /** The chapter after [href] in [readingOrder], or null at the end or when [href] isn't in it. */
    fun nextChapter(readingOrder: List<Link>, href: Url): Link? {
        val at = readingOrder.indexOfFirst { it.url().removeFragment().isEquivalent(href.removeFragment()) }
        return if (at < 0) null else readingOrder.getOrNull(at + 1)
    }

    /** Rounding: a column's width isn't always a whole number of pixels. */
    private const val SLACK_PX = 1.0
}
