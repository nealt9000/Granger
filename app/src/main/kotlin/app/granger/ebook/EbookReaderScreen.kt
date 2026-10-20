package app.granger.ebook

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.granger.reader.ChromeBackOnReturn
import app.granger.reader.SystemBarsHidden
import app.granger.reader.isNewChromeRequest
import app.granger.reader.readerPageInsets
import app.granger.shell.ChromeSlot
import app.granger.shell.SafeDrawingIgnoringBars
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.readium.r2.navigator.DecorableNavigator
import org.readium.r2.navigator.Decoration
import org.readium.r2.navigator.epub.EpubNavigatorFragment
import org.readium.r2.navigator.input.InputListener
import org.readium.r2.navigator.input.TapEvent
import org.readium.r2.navigator.util.DirectionalNavigationAdapter
import org.readium.r2.shared.util.AbsoluteUrl

/** Taps this far in from either side turn the page; taps between show or hide the chrome. */
private const val EDGE_FRACTION = 0.2

/**
 * The e-book reader, paginated. It opens (and comes back) with its chrome up: the top and bottom bars here and the
 * shell's rail (through [onChromeVisibleChange]), with the page fitted between them. Edge taps and swipes turn pages;
 * a tap in the middle hides the chrome and the system bars for full-screen reading, and another brings them back.
 * A rising [showChromeRequest] (the shell's edge swipe) shows the chrome.
 */
@Composable
fun EbookReaderScreen(
    viewModel: EbookReaderViewModel,
    onChromeVisibleChange: (Boolean) -> Unit,
    showChromeRequest: Int = 0,
    /** The page's colour in the reading theme, for the shell to paint the rail's place in while it's hidden. */
    onPageColorChange: (Color) -> Unit = {},
    /** How Define works. */
    dictionary: DictionaryChoice = DictionaryChoice.None,
    onSearchText: (String) -> Unit = {},
    /** Research about the selection, with the book and the selection's href and progression in it. */
    onResearch: (text: String, bookId: String, title: String, href: String, progression: Double) -> Unit = { _, _, _, _, _ -> },
    /** Opens the word's Wiktionary entry in [packId]; false when it has none. */
    onLookUp: suspend (packId: String, word: String) -> Boolean = { _, _ -> false },
    onBack: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var navigator by remember { mutableStateOf<EpubNavigatorFragment?>(null) }
    val appContext = LocalContext.current.applicationContext
    // Outlives a rotation inside the restored navigator, so it holds nothing tied to an activity.
    val listener = remember(appContext) { ExternalLinkListener(appContext) }
    val snackbar = remember { SnackbarHostState() }
    val currentDictionary by rememberUpdatedState(dictionary)
    val searchText by rememberUpdatedState(onSearchText)
    val research by rememberUpdatedState(onResearch)
    val lookUp by rememberUpdatedState(onLookUp)
    // Holds nothing of this composition: the menu reaches the view model through SelectionBridge, so it still
    // works inside a navigator that rotation restored.
    val config = remember { readerConfiguration() }
    // Another reader may have taken the menu (or cleared it on its way out) while this one was underneath.
    SideEffect { viewModel.claimSelection() }

    // The shell's left-edge swipe asks for the chrome; only a request newer than the one seen at entry counts, so an old swipe isn't replayed.
    val requestAtEntry = remember { showChromeRequest }
    LaunchedEffect(showChromeRequest) { if (isNewChromeRequest(requestAtEntry, showChromeRequest)) viewModel.showChrome() }
    LaunchedEffect(state.chromeVisible) { onChromeVisibleChange(state.chromeVisible) }
    SystemBarsHidden(!state.chromeVisible)
    val pageColor = state.prefs.theme.pageColor
    LaunchedEffect(pageColor) { onPageColorChange(pageColor) }
    ChromeBackOnReturn(viewModel::onScreenLeft)
    // Backgrounded, or the screen left: write the place now rather than after the debounce.
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { viewModel.flush() }

    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(navigator, lifecycle) {
        navigator?.currentLocator?.collect { viewModel.onLocator(it, onScreen = lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) }
    }
    LaunchedEffect(navigator) {
        navigator?.let { fragment -> viewModel.navigation.collect { fragment.go(it, animated = false) } }
    }
    // The selection menu's taps: read the selection, act, then clear it and close the menu, then say what went wrong.
    LaunchedEffect(navigator) {
        val fragment = navigator ?: return@LaunchedEffect
        val effectScope = this
        viewModel.selectionActions.collect { request ->
            var message: String? = null
            try {
                val selection = fragment.currentSelection()
                val text = selection?.locator?.text?.highlight.orEmpty().trim()
                if (selection != null && text.isNotEmpty()) {
                    when (request.action) {
                        SelectionAction.COPY -> appContext.getSystemService(ClipboardManager::class.java)
                            ?.setPrimaryClip(ClipData.newPlainText("", text))
                        SelectionAction.HIGHLIGHT -> viewModel.highlight(selection.locator, text)
                        SelectionAction.NOTE -> viewModel.note(selection.locator, text)
                        SelectionAction.DEFINE -> message = define(appContext, currentDictionary, text, lookUp)
                        SelectionAction.SEARCH -> searchText(text.take(200))
                        SelectionAction.RESEARCH -> viewModel.bookId?.let { id ->
                            val at = selection.locator
                            // A decoded path, like the book index's hrefs that a cited passage opens at.
                            val href = at.href.path ?: at.href.toString()
                            research(text, id, state.title, href, at.locations.progression ?: 0.0)
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w("Granger", "Selection action ${request.action} failed", e)
            } finally {
                fragment.clearSelection()
                request.mode?.finish()
            }
            message?.let { effectScope.launch { snackbar.showSnackbar(it) } }
        }
    }
    // Highlights are drawn again whenever they change, and on every new navigator (rotation builds one).
    LaunchedEffect(navigator, state.decorations) {
        navigator?.applyDecorations(state.decorations, HIGHLIGHT_GROUP)
    }
    LaunchedEffect(navigator, state.searchHit) {
        val hit = state.searchHit?.let { Decoration("search-hit", it, Decoration.Style.Highlight(tint = SEARCH_HIT_TINT)) }
        navigator?.applyDecorations(listOfNotNull(hit), SEARCH_GROUP)
    }
    // One tap listener per navigator instance; a tap on a drawn highlight opens its sheet.
    DisposableEffect(navigator) {
        val fragment = navigator
        val listener = object : DecorableNavigator.Listener {
            override fun onDecorationActivated(event: DecorableNavigator.OnActivatedEvent): Boolean {
                val id = event.decoration.id.toLongOrNull() ?: return false
                viewModel.editHighlight(id)
                return true
            }
        }
        fragment?.addDecorationListener(HIGHLIGHT_GROUP, listener)
        onDispose { fragment?.removeDecorationListener(listener) }
    }
    // Back closes an open panel first, then leaves the reader.
    BackHandler(enabled = state.panel != null) { viewModel.open(null) }
    LaunchedEffect(navigator, state.prefs) {
        navigator?.submitPreferences(state.prefs.toEpub())
    }

    Box(Modifier.fillMaxSize().background(pageColor)) {
        // The page keeps one size: between the top and bottom bars' places, beside the rail's (the shell keeps the
        // rail's and the mini-player's places). A middle tap only shows or hides the bars over those margins, so
        // Readium never re-paginates; hidden, the margins show the page's background.
        Column(Modifier.fillMaxSize()) {
            ChromeSlot(state.chromeVisible) {
                EbookTopBar(
                    title = state.title,
                    onBack = onBack,
                    onContents = { viewModel.open(Panel.CONTENTS) },
                    onSearch = { viewModel.open(Panel.SEARCH) },
                )
            }
            // Readium's own cutout padding is off (readerConfiguration): the page is padded here, as the web reader's is.
            Box(Modifier.weight(1f).fillMaxWidth().windowInsetsPadding(readerPageInsets(WindowInsetsSides.Horizontal))) {
                val error = state.error
                val factory = viewModel.navigatorFactory
                val bookId = viewModel.bookId
                when {
                    error != null -> Box(
                        Modifier.fillMaxSize().windowInsetsPadding(SafeDrawingIgnoringBars.only(WindowInsetsSides.Bottom)).padding(32.dp),
                        Alignment.Center,
                    ) {
                        Text(error, textAlign = TextAlign.Center)
                    }
                    state.publication != null && factory != null && bookId != null -> NavigatorHost(
                        bookId = bookId,
                        factory = factory,
                        // The latest place, so a navigator rebuilt after rotation opens where the reader is.
                        initial = state.locator ?: state.initial,
                        prefs = state.prefs.toEpub(),
                        listener = listener,
                        config = config,
                        isCleared = { viewModel.isCleared },
                        onReady = { fragment ->
                            fragment.addInputListener(
                                DirectionalNavigationAdapter(
                                    fragment,
                                    minimumHorizontalEdgeSize = 0.0,
                                    horizontalEdgeThresholdPercent = EDGE_FRACTION,
                                    animatedTransition = true,
                                ),
                            )
                            fragment.addInputListener(MiddleTapListener(fragment, viewModel::toggleChrome))
                            navigator = fragment
                        },
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
            if (state.error == null) {
                ChromeSlot(state.chromeVisible) {
                    EbookBottomBar(
                        locator = state.locator ?: state.initial,
                        positionsCount = state.positionsCount,
                        bookmarked = state.isBookmarkedHere,
                        onSeek = viewModel::goToProgression,
                        onContents = { viewModel.open(Panel.CONTENTS) },
                        onTextSettings = { viewModel.open(Panel.SETTINGS) },
                        onBookmark = viewModel::toggleBookmark,
                    )
                }
            }
        }
        when (state.panel) {
            Panel.CONTENTS -> ContentsPanel(state, viewModel::goTo, viewModel::goToMark, onDismiss = { viewModel.open(null) })
            Panel.SETTINGS -> TextSettingsSheet(state.prefs, viewModel::updatePrefs, onDismiss = { viewModel.open(null) })
            Panel.SEARCH -> BookSearchPanel(state.search, viewModel::search, viewModel::goToHit, onDismiss = { viewModel.open(null) })
            null -> Unit
        }
        state.editingHighlight?.let { id ->
            state.highlights.firstOrNull { it.id == id }?.let { mark ->
                HighlightSheet(
                    mark = mark,
                    focusNote = state.focusNote,
                    onChange = viewModel::updateHighlight,
                    onDelete = { viewModel.deleteHighlight(id) },
                    onDismiss = { viewModel.editHighlight(null) },
                )
            }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
    }
}

/**
 * Define: the Wiktionary entry opened over the book, else the phone's dictionary app. Returns what to tell the
 * reader (null when it worked or there was nothing to look up); never throws.
 */
private suspend fun define(
    context: Context,
    choice: DictionaryChoice,
    selection: String,
    lookUp: suspend (String, String) -> Boolean,
): String? {
    val word = Dictionary.normalise(selection)
    if (word.isEmpty()) return null
    return try {
        when (choice) {
            is DictionaryChoice.Wiktionary -> if (lookUp(choice.packId, word)) null else "No entry for “$word” in Wiktionary"
            DictionaryChoice.ProcessText -> if (Dictionary.startProcessText(context, selection)) null else "No dictionary app is installed"
            DictionaryChoice.None -> null
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w("Granger", "Looking up “$word” failed", e)
        "Couldn’t look up “$word”"
    }
}

/** Toggles the chrome on a tap in the middle of the page; edge taps are left to page turning. */
private class MiddleTapListener(
    private val navigator: EpubNavigatorFragment,
    private val onToggle: () -> Unit,
) : InputListener {
    override fun onTap(event: TapEvent): Boolean {
        val width = navigator.publicationView.width
        if (width <= 0) return false
        val x = event.point.x / width
        if (x < EDGE_FRACTION || x > 1 - EDGE_FRACTION) return false
        onToggle()
        return true
    }
}

/** Links out of the book open in the phone's browser, when it has one. */
private class ExternalLinkListener(private val appContext: Context) : EpubNavigatorFragment.Listener {
    override fun onExternalLinkActivated(url: AbsoluteUrl) {
        try {
            appContext.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(url.toString())).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        } catch (e: ActivityNotFoundException) {
            Log.w("Granger", "No app can open $url")
        }
    }
}
