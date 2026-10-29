package app.granger.ebook

import android.graphics.PointF
import android.graphics.RectF
import com.github.barteksc.pdfviewer.model.LinkTapEvent
import com.google.common.truth.Truth.assertThat
import com.shockwave.pdfium.PdfDocument
import org.junit.Test
import org.junit.runner.RunWith
import org.readium.r2.navigator.input.DragEvent
import org.readium.r2.navigator.input.InputListener
import org.readium.r2.navigator.input.TapEvent
import org.readium.r2.shared.ExperimentalReadiumApi
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** A tap on a PDF link follows the link only: the page doesn't turn under it, nor the bars toggle. */
@OptIn(ExperimentalReadiumApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LinkAwareTapsTest {
    private val posted = mutableListOf<Runnable>()
    private val links = PdfLinkTaps()
    private val taps = mutableListOf<PointF>()
    private var drags = 0
    private val turns = object : InputListener {
        override fun onTap(event: TapEvent): Boolean { taps += event.point; return true }
        override fun onDrag(event: DragEvent): Boolean { drags++; return true }
    }
    private val listener = LinkAwareTaps(listOf(turns), links, post = { posted += it })

    /** What the viewer does with a tap: Readium's listeners first, then its link check, in one call. */
    private fun tap(onLink: Boolean) {
        assertThat(listener.onTap(TapEvent(PointF(10f, 20f)))).isTrue()
        if (onLink) links.handleLinkEvent(LinkTapEvent(10f, 20f, 10f, 20f, RectF(), PdfDocument.Link(RectF(72f, 620f, 300f, 600f), 4, null)))
        posted.toList().also { posted.clear() }.forEach { it.run() }
    }

    @Test fun aTapOffALinkTurnsOrTogglesAsBefore() {
        tap(onLink = false)
        assertThat(taps).hasSize(1)
    }

    @Test fun aTapOnALinkOnlyFollowsIt() {
        val followed = mutableListOf<PdfLinkTap>()
        links.onLink = { followed += it }
        tap(onLink = true)
        assertThat(taps).isEmpty()
        assertThat(followed).containsExactly(PdfLinkTap(4, null, 72f, 620f, 300f, 600f))
        // The next tap, off the link, is handled again.
        tap(onLink = false)
        assertThat(taps).hasSize(1)
    }

    @Test fun dragsPassStraightThrough() {
        assertThat(listener.onDrag(DragEvent(DragEvent.Type.Start, PointF(), PointF()))).isTrue()
        assertThat(drags).isEqualTo(1)
    }
}
