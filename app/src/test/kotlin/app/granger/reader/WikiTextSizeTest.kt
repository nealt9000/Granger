package app.granger.reader

import android.content.Context
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import app.granger.FakeLibrary
import app.granger.FakeReadingSessions
import app.granger.FakeZimArchive
import app.granger.core.library.ReadingTab
import app.granger.core.zim.ZimResolver
import app.granger.core.zim.ZimUrls
import app.granger.ebook.ReadingPrefs
import app.granger.ebook.ReadingPrefsStore
import app.granger.ebook.ReadingTheme
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The wiki's Aa (Plan U3c Decision 4): the text size is the WebView's text zoom, 80–160 %, saved with the e-books' prefs. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class WikiTextSizeTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val shared = ApplicationProvider.getApplicationContext<Context>().getSharedPreferences("wiki-zoom-test", Context.MODE_PRIVATE)

    @Test fun theZoomIsSavedInStepsWithinItsRange() {
        val store = ReadingPrefsStore(shared)
        assertThat(store.prefs.value.wikiTextZoom).isEqualTo(100)
        store.update(store.prefs.value.copy(wikiTextZoom = 130))
        assertThat(ReadingPrefsStore(shared).prefs.value.wikiTextZoom).isEqualTo(130)
        store.update(store.prefs.value.copy(wikiTextZoom = 175))
        assertThat(store.prefs.value.wikiTextZoom).isEqualTo(ReadingPrefs.MAX_WIKI_ZOOM)
        store.update(store.prefs.value.copy(wikiTextZoom = 40))
        assertThat(store.prefs.value.wikiTextZoom).isEqualTo(ReadingPrefs.MIN_WIKI_ZOOM)
        assertThat(ReadingPrefs(wikiTextZoom = 114).clamped().wikiTextZoom).isEqualTo(110)
    }

    @Test fun theReaderThemeDarkensAWikiPageForNightOrMatchAppInNight() {
        assertThat(ReadingPrefs(theme = ReadingTheme.APP).wikiNight(appNight = true)).isTrue()
        assertThat(ReadingPrefs(theme = ReadingTheme.APP).wikiNight(appNight = false)).isFalse()
        assertThat(ReadingPrefs(theme = ReadingTheme.NIGHT).wikiNight(appNight = false)).isTrue()
        assertThat(ReadingPrefs(theme = ReadingTheme.DAY).wikiNight(appNight = true)).isFalse()
        assertThat(ReadingPrefs(theme = ReadingTheme.SEPIA).wikiNight(appNight = true)).isFalse()
    }

    @Test fun aaSetsTheZoomOnThePageAndSavesIt() {
        val store = ReadingPrefsStore(shared).apply { update(prefs.value.copy(wikiTextZoom = 120)) }
        val library = FakeLibrary().apply { archives["p"] = FakeZimArchive("p", setOf("index", "Owl")) }
        val sessions = FakeReadingSessions().apply { tabs.value = listOf(ReadingTab(1, "p", "Owl", "Owl", 1L, onRail = true)) }
        val viewModel = ReaderViewModel(1, library, sessions, CoroutineScope(Dispatchers.Unconfined), Dispatchers.Unconfined, prefsStore = store)
        compose.setContent {
            ReaderScreen(viewModel = viewModel, resolver = ZimResolver({ library.archives[it] }), onChromeVisibleChange = {}, onSearchText = {}, onBack = {})
        }
        compose.waitForIdle()
        val web = compose.activity.window.decorView.findWebView()!!
        // The saved size is on the page from the start.
        assertThat(web.settings.textZoom).isEqualTo(120)
        runBlocking { viewModel.onPageShown(ZimUrls.forEntry("p", "Owl"), "Owl") }
        compose.onNodeWithContentDescription("Text settings").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("120%").assertExists()
        compose.onNodeWithContentDescription("Larger text").performClick()
        compose.waitForIdle()
        assertThat(store.prefs.value.wikiTextZoom).isEqualTo(130)
        assertThat(ReadingPrefsStore(shared).prefs.value.wikiTextZoom).isEqualTo(130)
        assertThat(web.settings.textZoom).isEqualTo(130)
        compose.onNodeWithText("130%").assertExists()
    }

    private fun View.findWebView(): ReaderWebView? {
        if (this is ReaderWebView) return this
        if (this is ViewGroup) for (i in 0 until childCount) getChildAt(i).findWebView()?.let { return it }
        return null
    }
}
