package app.granger.reader

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.granger.core.zim.KiwixZimOpener
import app.granger.core.zim.ZimArchive
import app.granger.core.zim.ZimOpenResult
import app.granger.core.zim.ZimResolver
import app.granger.core.zim.ZimUrls
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** Loads the test ZIM in a real WebView through ZimWebViewClient. Runs on the Pixel 6. */
@RunWith(AndroidJUnit4::class)
class ReaderWebViewTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private lateinit var archive: ZimArchive
    private lateinit var webView: ReaderWebView
    private val shown = LinkedBlockingQueue<String>()
    private val external = LinkedBlockingQueue<String>()
    private val chromeToggles = LinkedBlockingQueue<Unit>()

    @Before fun setUp() {
        val file = File(instrumentation.targetContext.cacheDir, "granger-test.zim")
        instrumentation.context.assets.open("granger-test.zim").use { input -> file.outputStream().use { input.copyTo(it) } }
        archive = (KiwixZimOpener(instrumentation.targetContext).open(file) as ZimOpenResult.Opened).archive
        val client = ZimWebViewClient(
            resolver = ZimResolver(archiveFor = { id -> archive.takeIf { id == "test" } }),
            assets = instrumentation.targetContext.assets,
            onExternalLink = { external.add(it) },
            onLeavingPage = {},
            onPageShown = { _, url -> shown.add(url) },
        )
        instrumentation.runOnMainSync {
            webView = ReaderWebView(instrumentation.targetContext, client, onToggleChrome = { chromeToggles.add(Unit) })
        }
    }

    @After fun tearDown() {
        instrumentation.runOnMainSync { webView.destroy() }
        archive.close()
    }

    private fun load(path: String) {
        instrumentation.runOnMainSync { webView.loadUrl(ZimUrls.forEntry("test", path)) }
        assertThat(shown.poll(10, TimeUnit.SECONDS)).isEqualTo(ZimUrls.forEntry("test", path))
    }

    /** Evaluates [script] and returns its JSON-encoded result. */
    private fun js(script: String): String {
        val result = LinkedBlockingQueue<String>()
        instrumentation.runOnMainSync { webView.evaluateJavascript(script) { result.add(it) } }
        return result.poll(5, TimeUnit.SECONDS)!!
    }

    @Test fun rendersArticleWithStylesheetAndImageFromTheZim() {
        load("Photosynthesis")
        assertThat(js("document.title")).isEqualTo("\"Photosynthesis\"")
        assertThat(js("getComputedStyle(document.body).fontFamily")).contains("serif")
        assertThat(js("document.images[0].complete && document.images[0].naturalWidth")).isEqualTo("1")
    }

    @Test fun opensPathsWithSpecialCharacters() {
        load("C++")
        assertThat(js("document.title")).isEqualTo("\"C++\"")
        load("What_is_this?")
        assertThat(js("document.title")).isEqualTo("\"What is this?\"")
    }

    @Test fun followingARedirectLinkLandsOnTheCanonicalUrl() {
        load("index")
        js("document.querySelector('a[href=\"Plants\"]').click()")
        assertThat(shown.poll(10, TimeUnit.SECONDS)).isEqualTo(ZimUrls.forEntry("test", "Photosynthesis"))
    }

    @Test fun linksWithEncodedCharactersOpenTheRightArticle() {
        load("index")
        js("document.querySelector('a[href=\"C%2B%2B\"]').click()")
        assertThat(shown.poll(10, TimeUnit.SECONDS)).isEqualTo(ZimUrls.forEntry("test", "C++"))
        assertThat(js("document.title")).isEqualTo("\"C++\"")
    }

    @Test fun externalLinksAreHandedOffNotLoaded() {
        load("index")
        js("document.querySelector('a[href=\"https://example.com/\"]').click()")
        assertThat(external.poll(10, TimeUnit.SECONDS)).isEqualTo("https://example.com/")
        assertThat(shown.poll(1, TimeUnit.SECONDS)).isNull()
    }

    @Test fun missingArticlesShowANotFoundPage() {
        load("No_such_article")
        assertThat(js("document.body.innerText")).contains("isn't in this pack")
    }

    @Test fun webViewCannotReachTheNetwork() {
        instrumentation.runOnMainSync {
            assertThat(webView.settings.blockNetworkLoads).isTrue()
            assertThat(webView.settings.safeBrowsingEnabled).isFalse()
        }
        // ZIM content still loads through request interception.
        load("Photosynthesis")
        assertThat(js("document.querySelector('img').naturalWidth")).isEqualTo("1")
    }

    /** Dispatches a click on the first element matching [selector], at [xFraction] of the page's width. */
    private fun clickAt(selector: String, xFraction: Double) {
        js(
            "document.querySelector('$selector').dispatchEvent(new MouseEvent('click', " +
                "{bubbles: true, cancelable: true, clientX: window.innerWidth * $xFraction, clientY: 10}))",
        )
    }

    @Test fun tapInTheMiddleOfTextTogglesChrome() {
        load("Photosynthesis")
        clickAt("p", 0.5)
        assertThat(chromeToggles.poll(5, TimeUnit.SECONDS)).isNotNull()
        // Near the edges a tap is left to the page.
        clickAt("p", 0.1)
        clickAt("p", 0.9)
        assertThat(chromeToggles.poll(1, TimeUnit.SECONDS)).isNull()
    }

    @Test fun tapOnALinkDoesNotToggleChrome() {
        load("index")
        clickAt("a[href=\"https://example.com/\"]", 0.5)
        assertThat(external.poll(10, TimeUnit.SECONDS)).isEqualTo("https://example.com/")
        assertThat(chromeToggles.poll(1, TimeUnit.SECONDS)).isNull()
    }

    @Test fun kiwixMainPagesGetJqueryFromTheApp() {
        load("index")
        js("var s = document.createElement('script'); s.src = '${MainPageFixes.JQUERY_URL}'; document.head.appendChild(s)")
        // The app's own copy, served by interception; the WebView can't reach code.jquery.com.
        var version = js("window.jQuery ? jQuery.fn.jquery : null")
        var tries = 0
        while (version == "null" && tries++ < 50) {
            Thread.sleep(100)
            version = js("window.jQuery ? jQuery.fn.jquery : null")
        }
        assertThat(version).isEqualTo("\"3.7.1\"")
    }
}
