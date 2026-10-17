package app.granger.ebook

import android.content.Context
import android.view.ActionMode
import android.view.Menu
import android.view.MenuInflater
import android.view.View
import android.widget.PopupMenu
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.readium.r2.shared.ExperimentalReadiumApi
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.publication.LocatorCollection
import org.readium.r2.shared.publication.services.search.SearchError
import org.readium.r2.shared.publication.services.search.SearchIterator
import org.readium.r2.shared.util.Try
import org.readium.r2.shared.util.Url
import org.readium.r2.shared.util.mediatype.MediaType
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

private class FakeActionMode : ActionMode() {
    var finished = false
    override fun setTitle(title: CharSequence?) = Unit
    override fun setTitle(resId: Int) = Unit
    override fun setSubtitle(subtitle: CharSequence?) = Unit
    override fun setSubtitle(resId: Int) = Unit
    override fun setCustomView(view: View?) = Unit
    override fun invalidate() = Unit
    override fun finish() { finished = true }
    override fun getMenu(): Menu? = null
    override fun getTitle(): CharSequence? = null
    override fun getSubtitle(): CharSequence? = null
    override fun getCustomView(): View? = null
    override fun getMenuInflater(): MenuInflater? = null
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SelectionMenuTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @After fun reset() {
        SelectionBridge.sink = null
        SelectionBridge.dictionaryAvailable = false
    }

    private fun shown(): Menu {
        val menu = PopupMenu(context, View(context)).menu
        val mode = FakeActionMode()
        val callback = SelectionMenu()
        callback.onCreateActionMode(mode, menu)
        callback.onPrepareActionMode(mode, menu)
        return menu
    }

    private fun Menu.visibleTitles() = (0 until size()).map { getItem(it) }.filter { it.isVisible }.map { it.title.toString() }

    @Test fun menuReadsCopyHighlightNoteDefineSearch() {
        SelectionBridge.dictionaryAvailable = true
        assertThat(shown().visibleTitles()).containsExactly("Copy", "Highlight", "Note", "Define", "Search").inOrder()
    }

    @Test fun defineIsHiddenWhenNothingCanDefine() {
        SelectionBridge.dictionaryAvailable = false
        assertThat(shown().visibleTitles()).containsExactly("Copy", "Highlight", "Note", "Search").inOrder()
    }

    @Test fun defineFollowsTheLiveFlagWhenPreparedAgain() {
        val menu = PopupMenu(context, View(context)).menu
        val mode = FakeActionMode()
        val callback = SelectionMenu()
        callback.onCreateActionMode(mode, menu)
        callback.onPrepareActionMode(mode, menu)
        assertThat(menu.visibleTitles()).doesNotContain("Define")
        SelectionBridge.dictionaryAvailable = true
        callback.onPrepareActionMode(mode, menu)
        assertThat(menu.visibleTitles()).contains("Define")
    }

    @Test fun tapsReachWhicheverSinkIsCurrent() {
        val menu = shown()
        val callback = SelectionMenu()
        val mode = FakeActionMode()
        val first = mutableListOf<SelectionAction>()
        val second = mutableListOf<SelectionAction>()
        SelectionBridge.sink = { first += it.action }
        callback.onActionItemClicked(mode, menu.getItem(1))
        // Rotation: a new reader takes over; the old menu callback is still the one inside the navigator.
        SelectionBridge.sink = { second += it.action }
        callback.onActionItemClicked(mode, menu.getItem(2))
        assertThat(first).containsExactly(SelectionAction.HIGHLIGHT)
        assertThat(second).containsExactly(SelectionAction.NOTE)
        assertThat(mode.finished).isFalse()
    }

    // --- ReadiumBookSearcher.drain ---

    private fun locators(n: Int, from: Int = 0) = (from until from + n).map {
        Locator(href = Url("c001.xhtml")!!, mediaType = MediaType.XHTML, text = Locator.Text(highlight = "hit$it"))
    }

    private class PagedIterator(val pages: List<List<Locator>>, val gate: CompletableDeferred<Unit>? = null) : SearchIterator {
        var closed = false
        private var i = 0
        override suspend fun next(): Try<LocatorCollection?, SearchError> {
            gate?.await()
            return Try.success(pages.getOrNull(i++)?.let { LocatorCollection(locators = it) })
        }
        override fun close() { closed = true }
    }

    @OptIn(ExperimentalReadiumApi::class)
    @Test fun drainStopsAtMaxAndCloses() = runTest {
        val iterator = PagedIterator(listOf(locators(3), locators(3, 3), locators(3, 6)))
        val found = ReadiumBookSearcher().drain(iterator, max = 5)
        assertThat(found).hasSize(5)
        assertThat(iterator.closed).isTrue()
    }

    @OptIn(ExperimentalReadiumApi::class)
    @Test fun drainStopsWhenPagesRunOut() = runTest {
        val iterator = PagedIterator(listOf(locators(2)))
        assertThat(ReadiumBookSearcher().drain(iterator, max = 200)).hasSize(2)
        assertThat(iterator.closed).isTrue()
    }

    @OptIn(ExperimentalReadiumApi::class)
    @Test fun drainClosesWhenCancelled() = runTest {
        val iterator = PagedIterator(listOf(locators(1)), gate = CompletableDeferred())
        val job = launch { ReadiumBookSearcher().drain(iterator, 10) }
        advanceUntilIdle()
        job.cancel()
        advanceUntilIdle()
        assertThat(iterator.closed).isTrue()
    }
}
