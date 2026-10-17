package app.granger.ebook

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.granger.reader.SystemBarsHidden
import app.granger.reader.isNewChromeRequest
import app.granger.shell.NarrowRailWidth
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
 * The e-book reader, full screen and paginated. Edge taps and swipes turn pages; a tap in the middle shows or
 * hides the chrome: the top bar here and the shell's rail (through [onChromeVisibleChange]), with the system bars
 * hidden alongside. A rising [showChromeRequest] (the shell's edge swipe) shows the chrome.
 */
@Composable
fun EbookReaderScreen(
    viewModel: EbookReaderViewModel,
    onChromeVisibleChange: (Boolean) -> Unit,
    showChromeRequest: Int = 0,
    /** How Define works; null while that is still being worked out, and the book waits for it. */
    dictionary: DictionaryChoice? = DictionaryChoice.None,
    onSearchText: (String) -> Unit = {},
    /** Opens the word's Wiktionary entry in [packId]; false when it has none. */
    onLookUp: suspend (packId: String, word: String) -> Boolean = { _, _ -> false },
    onBack: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var navigator by remember { mutableStateOf<EpubNavigatorFragment?>(null) }
    val appContext = LocalContext.current.applicationContext
    // Outlives a rotation inside the restored navigator, so it holds nothing tied to an activity.
    val listener = remember(appContext) { ExternalLinkListener(appContext) }
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val currentDictionary by rememberUpdatedState(dictionary)
    val searchText by rememberUpdatedState(onSearchText)
    val lookUp by rememberUpdatedState(onLookUp)
    val openNavigator by rememberUpdatedState(navigator)
    // The menu's tap: read the selection, act, then clear it. Reached through rememberUpdatedState so the
    // configuration (kept inside restored navigators) never holds this composition.
    val onSelection = rememberUpdatedState { action: SelectionAction ->
        val fragment = openNavigator
        if (fragment != null) {
            scope.launch {
                val selection = fragment.currentSelection() ?: return@launch
                val text = selection.locator.text.highlight.orEmpty().trim()
                try {
                    if (text.isNotEmpty()) {
                        when (action) {
                            SelectionAction.COPY -> appContext.getSystemService(ClipboardManager::class.java)
                                ?.setPrimaryClip(ClipData.newPlainText("", text))
                            SelectionAction.HIGHLIGHT -> viewModel.highlight(selection.locator, text)
                            SelectionAction.NOTE -> viewModel.note(selection.locator, text)
                            SelectionAction.DEFINE -> define(appContext, currentDictionary, text, lookUp, snackbar)
                            SelectionAction.SEARCH -> searchText(text.take(200))
                            SelectionAction.INQUIRE -> Unit
                        }
                    }
                } finally {
                    fragment.clearSelection()
                }
            }
        }
    }
    val dictionaryAvailable = dictionary != null && dictionary != DictionaryChoice.None
    val config = remember(dictionaryAvailable) { readerConfiguration({ onSelection.value(it) }, dictionaryAvailable) }

    // The shell's left-edge swipe asks for the chrome; only a request newer than the one seen at entry counts.
    val requestAtEntry = remember { showChromeRequest }
    LaunchedEffect(showChromeRequest) { if (isNewChromeRequest(requestAtEntry, showChromeRequest)) viewModel.showChrome() }
    LaunchedEffect(state.chromeVisible) { onChromeVisibleChange(state.chromeVisible) }
    SystemBarsHidden(!state.chromeVisible)
    // Backgrounded, or the screen left: write the place now rather than after the debounce.
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { viewModel.flush() }

    LaunchedEffect(navigator) {
        navigator?.currentLocator?.collect(viewModel::onLocator)
    }
    LaunchedEffect(navigator) {
        navigator?.let { fragment -> viewModel.navigation.collect { fragment.go(it, animated = false) } }
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

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        val error = state.error
        val factory = viewModel.navigatorFactory
        val bookId = viewModel.bookId
        when {
            error != null -> Box(Modifier.fillMaxSize().padding(start = NarrowRailWidth).padding(32.dp), Alignment.Center) {
                Text(error, textAlign = TextAlign.Center)
            }
            state.publication != null && factory != null && bookId != null && dictionary != null -> NavigatorHost(
                bookId = bookId,
                factory = factory,
                // The latest place, so a navigator rebuilt after rotation opens where the reader is.
                initial = state.locator ?: state.initial,
                prefs = state.prefs.toEpub(),
                listener = listener,
                config = config,
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
        // The shell's rail is drawn over the reader's left edge while the chrome shows.
        AnimatedVisibility(
            visible = state.chromeVisible,
            modifier = Modifier.padding(start = NarrowRailWidth).align(Alignment.TopCenter),
            enter = fadeIn() + slideInVertically { -it },
            exit = fadeOut() + slideOutVertically { -it },
        ) {
            EbookTopBar(
                title = state.title,
                onBack = onBack,
                onContents = { viewModel.open(Panel.CONTENTS) },
                onSearch = { viewModel.open(Panel.SEARCH) },
            )
        }
        if (state.error == null) {
            AnimatedVisibility(
                visible = state.chromeVisible,
                modifier = Modifier.padding(start = NarrowRailWidth).align(Alignment.BottomCenter),
                enter = fadeIn() + slideInVertically { it },
                exit = fadeOut() + slideOutVertically { it },
            ) {
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
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(start = NarrowRailWidth))
    }
}

/** Define: the Wiktionary entry opened over the book, else the phone's dictionary app; a snackbar when neither can. */
private suspend fun define(
    context: Context,
    choice: DictionaryChoice?,
    selection: String,
    lookUp: suspend (String, String) -> Boolean,
    snackbar: SnackbarHostState,
) {
    when (choice) {
        is DictionaryChoice.Wiktionary -> {
            val word = Dictionary.normalise(selection)
            if (word.isEmpty() || !lookUp(choice.packId, word)) {
                snackbar.showSnackbar("No entry for “$word” in Wiktionary")
            }
        }
        DictionaryChoice.ProcessText -> if (!Dictionary.startProcessText(context, selection)) {
            snackbar.showSnackbar("No dictionary app is installed")
        }
        DictionaryChoice.None, null -> Unit
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
