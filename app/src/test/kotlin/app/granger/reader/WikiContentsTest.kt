package app.granger.reader

import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import app.granger.FakeLibrary
import app.granger.FakeReadingSessions
import app.granger.FakeZimArchive
import app.granger.core.library.ReadingTab
import app.granger.core.zim.ZimResolver
import app.granger.core.zim.ZimUrls
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** The wiki's Contents (Plan U3c Decision 4): the article's top-level sections, and a tap jumps the page to one. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class WikiContentsTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val html = """
        <html><body>
        <h1 id="firstHeading">Rosetta Stone</h1>
        <p>The Rosetta Stone is a stele.</p>
        <h2 id="History">History</h2>
        <p>Ptolemaic era.</p>
        <h3 id="Finding">Finding</h3>
        <p>Found in 1799.</p>
        <h2>Reading the stone</h2>
        <p>Champollion read it.</p>
        </body></html>
    """.trimIndent()
    private val archive = FakeZimArchive("p", setOf("index"), contents = mapOf("Rosetta_Stone" to html, "Owl" to "<html><body><p>Hoot.</p></body></html>"))
    private val library = FakeLibrary().apply { archives["p"] = archive }
    private val sessions = FakeReadingSessions().apply { tabs.value = listOf(ReadingTab(1, "p", "Rosetta_Stone", "Rosetta Stone", 1L, onRail = true)) }
    private val viewModel = ReaderViewModel(1, library, sessions, CoroutineScope(Dispatchers.Unconfined), Dispatchers.Unconfined)

    private val expected = listOf(
        // The lead, under the article's title; then the h2s, whose h3s stay inside them. A heading without an id is `g<n>`.
        ArticleSection("Rosetta Stone", "firstHeading", "Rosetta Stone"),
        ArticleSection("History", "History", "History"),
        ArticleSection("Reading the stone", "g6", "Reading the stone"),
    )

    @Test fun theSectionsAreTheTopLevelHeadingsWithTheirBlocks() = runTest {
        val source = ArticleReadSource(archive, "p", "Rosetta_Stone", "Rosetta Stone", Dispatchers.Unconfined)
        assertThat(source.contents()).containsExactlyElementsIn(expected).inOrder()
        // An article without headings has none.
        assertThat(ArticleReadSource(archive, "p", "Owl", "Owl", Dispatchers.Unconfined).contents()).isEmpty()
    }

    @Test fun openingContentsReadsThePagesSectionsAndANewPageForgetsThem() {
        runBlocking { viewModel.onPageShown(ZimUrls.forEntry("p", "Rosetta_Stone"), "Rosetta Stone") }
        viewModel.open(ReaderViewModel.Panel.CONTENTS)
        assertThat(viewModel.state.value.panel).isEqualTo(ReaderViewModel.Panel.CONTENTS)
        assertThat(viewModel.state.value.contents).containsExactlyElementsIn(expected).inOrder()
        // A middle tap closes it with the chrome.
        viewModel.toggleChrome()
        assertThat(viewModel.state.value.panel).isNull()
        runBlocking { viewModel.onPageShown(ZimUrls.forEntry("p", "Owl"), "Owl") }
        assertThat(viewModel.state.value.contents).isNull()
    }

    @Test fun aTapOnASectionJumpsThePageToItsHeadingAndCloses() {
        compose.setContent {
            ReaderScreen(
                viewModel = viewModel,
                resolver = ZimResolver({ library.archives[it] }),
                onChromeVisibleChange = {},
                onSearchText = {},
                onBack = {},
            )
        }
        compose.waitForIdle()
        runBlocking { viewModel.onPageShown(ZimUrls.forEntry("p", "Rosetta_Stone"), "Rosetta Stone") }
        compose.onNodeWithContentDescription("Contents").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Reading the stone").performClick()
        compose.waitForIdle()
        val web = compose.activity.window.decorView.findWebView()!!
        assertThat(shadowOf(web).lastEvaluatedJavascript).isEqualTo(ReadAloudScript.call("jump", "g6", "Reading the stone"))
        assertThat(ReadAloudScript.call("jump", "g6", "Reading the stone")).contains("__grangerRead.jump(\"g6\", \"Reading the stone\")")
        compose.onNodeWithTag(WIKI_CONTENTS).assertDoesNotExist()
        assertThat(viewModel.state.value.panel).isNull()
    }

    @Test fun aPageWithoutSectionsSaysSo() {
        compose.setContent { WikiContentsPanel(emptyList(), onJump = {}, onDismiss = {}) }
        compose.onNodeWithText("This page has no sections.").assertExists()
    }

    private fun View.findWebView(): ReaderWebView? {
        if (this is ReaderWebView) return this
        if (this is ViewGroup) for (i in 0 until childCount) getChildAt(i).findWebView()?.let { return it }
        return null
    }
}
