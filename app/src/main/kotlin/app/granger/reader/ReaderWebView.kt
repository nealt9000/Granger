package app.granger.reader

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Rect
import android.view.ActionMode
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import androidx.compose.ui.graphics.toArgb
import androidx.webkit.ScriptHandler
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import app.granger.ebook.SelectionBridge
import app.granger.theme.FieldColors
import app.granger.theme.NightColors
import org.json.JSONTokener

/** What the text-selection menu offers besides the system's own Copy. [READ_ALOUD] hands over the selection's block (JSON), not its text. */
enum class SelectionAction(val label: String) {
    SEARCH("Search"),
    RESEARCH("Ask"),
    READ_ALOUD("Read from here"),
}

/**
 * A WebView configured for ZIM content that can report and restore how far down the page it is. A middle tap on the
 * page calls [onToggleChrome]; a selection action calls [onSelectionAction] with the selected text (for Read from
 * here, the page script's `{id, prefix, offset}` of the selection's block); scrolls other than the voice's call
 * [onUserScroll] with their distance in dp and whether a finger moved the page. Every page gets the reader's bars'
 * padding ([setChromePadding]).
 */
@SuppressLint("SetJavaScriptEnabled", "ViewConstructor", "JavascriptInterface")
class ReaderWebView(
    context: Context,
    client: ZimWebViewClient,
    onToggleChrome: () -> Unit = {},
    private val onSelectionAction: (SelectionAction, String) -> Unit = { _, _ -> },
    onUserScroll: (dyDp: Float, byHand: Boolean) -> Unit = { _, _ -> },
    night: Boolean = true,
) : WebView(context) {
    init {
        // Compose's AndroidView adds views with wrap-content params, which puts WebView in "zero layout height" mode:
        // percentage heights then resolve to 0 and pages built on them (Kiwix main pages) render blank.
        layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        settings.javaScriptEnabled = true // Wikipedia ZIMs use JS for collapsible sections
        settings.domStorageEnabled = true
        settings.allowFileAccess = false
        settings.allowContentAccess = false
        settings.builtInZoomControls = true
        settings.displayZoomControls = false
        // Belt and braces: ZimWebViewClient already refuses every non-ZIM request, and the app now
        // holds INTERNET for downloads. Intercepted ZIM responses are unaffected by this setting.
        settings.blockNetworkLoads = true
        settings.safeBrowsingEnabled = false
        // In Night, pages that don't style themselves for dark are darkened (the window theme must say dark too, which
        // MainActivity sets from the same choice); in Field they show as published (Plan T1, Decision 5).
        if (WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)) {
            try {
                WebSettingsCompat.setAlgorithmicDarkeningAllowed(settings, night)
            } catch (e: UnsupportedOperationException) {
                // Reported as supported but refused (a framework and WebView APK that disagree): pages keep their own colours.
            }
        }
        setBackgroundColor((if (night) NightColors else FieldColors).background.toArgb()) // no wrong-colour flash before the first page paints
        addJavascriptInterface(GrangerBridge(onToggleChrome, onUserScroll), ReaderPageScript.BRIDGE_NAME)
        webViewClient = client
    }

    private var chromePadding: String? = null
    private var chromePaddingAtStart: ScriptHandler? = null

    /**
     * Pads every page for the reader's bars over it: [topDp] and [bottomDp]. Set before a page loads, it is there from
     * the page's first paint where the WebView runs scripts at document start; [applyChromePadding] puts it on a page
     * that has loaded.
     */
    fun setChromePadding(topDp: Int, bottomDp: Int) {
        val script = chromePaddingScript(topDp, bottomDp)
        if (script == chromePadding) return
        chromePadding = script
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            chromePaddingAtStart?.remove()
            chromePaddingAtStart = WebViewCompat.addDocumentStartJavaScript(this, script, setOf("*"))
        }
        applyChromePadding()
    }

    /** The bars' padding on the page shown now. */
    fun applyChromePadding() {
        chromePadding?.let { evaluateJavascript(it, null) }
    }

    /** 0 at the top, 1 at the bottom; 0 when the page does not scroll. */
    fun scrollFraction(): Float {
        val range = computeVerticalScrollRange() - height
        return if (range <= 0) 0f else (scrollY.toFloat() / range).coerceIn(0f, 1f)
    }

    fun scrollToFraction(fraction: Float) {
        evaluateJavascript(
            "window.scrollTo(0, (document.documentElement.scrollHeight - window.innerHeight) * $fraction)",
            null,
        )
    }

    override fun startActionMode(callback: ActionMode.Callback, type: Int): ActionMode? =
        super.startActionMode(SelectionCallback(callback), type)

    override fun startActionMode(callback: ActionMode.Callback): ActionMode? =
        super.startActionMode(SelectionCallback(callback))

    /** The system's selection menu (Copy, Select all…) plus Granger's actions, ahead of them. */
    private inner class SelectionCallback(private val system: ActionMode.Callback) : ActionMode.Callback2() {
        private val actions = SelectionAction.entries.filter {
            when (it) {
                SelectionAction.RESEARCH -> SelectionBridge.offersResearch
                SelectionAction.READ_ALOUD -> SelectionBridge.readAloudAvailable
                SelectionAction.SEARCH -> true
            }
        }

        override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
            val created = system.onCreateActionMode(mode, menu)
            actions.forEachIndexed { i, action ->
                menu.add(Menu.NONE, MENU_ID_BASE + i, i, action.label).setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
            }
            return created
        }

        override fun onPrepareActionMode(mode: ActionMode, menu: Menu) = system.onPrepareActionMode(mode, menu)

        override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean {
            val action = actions.getOrNull(item.itemId - MENU_ID_BASE) ?: return system.onActionItemClicked(mode, item)
            // Read the selection before closing the menu, which clears it.
            val script = if (action == SelectionAction.READ_ALOUD) ReadAloudScript.call("selectionBlock") else "window.getSelection().toString()"
            evaluateJavascript(script) { json ->
                mode.finish()
                val text = (JSONTokener(json).nextValue() as? String)?.trim().orEmpty()
                if (text.isNotEmpty() && text != "null") onSelectionAction(action, text)
            }
            return true
        }

        override fun onDestroyActionMode(mode: ActionMode) = system.onDestroyActionMode(mode)

        override fun onGetContentRect(mode: ActionMode, view: View, outRect: Rect) {
            if (system is ActionMode.Callback2) system.onGetContentRect(mode, view, outRect)
            else super.onGetContentRect(mode, view, outRect)
        }
    }

    private companion object {
        const val MENU_ID_BASE = 0x6772 // clear of the system's own menu ids
    }
}
