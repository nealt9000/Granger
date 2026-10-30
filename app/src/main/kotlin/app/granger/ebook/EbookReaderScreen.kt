@file:OptIn(org.readium.r2.shared.ExperimentalReadiumApi::class)

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
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.material3.AssistChip
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.testTag
import app.granger.Features
import app.granger.reader.ChromeBackOnReturn
import app.granger.reader.ClearOfShell
import app.granger.reader.ReaderBarActions
import app.granger.reader.ReaderBottomBar
import app.granger.reader.ReaderBookPageInsets
import app.granger.reader.ReaderTopBar
import app.granger.reader.SlidingBar
import app.granger.reader.SystemBarsHidden
import app.granger.reader.isNewChromeRequest
import app.granger.shell.SafeDrawingIgnoringBars
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.readium.adapter.pdfium.navigator.PdfiumNavigatorFragment
import org.readium.r2.navigator.DecorableNavigator
import org.readium.r2.navigator.Decoration
import org.readium.r2.navigator.OverflowableNavigator
import org.readium.r2.navigator.VisualNavigator
import org.readium.r2.navigator.epub.EpubNavigatorFragment
import org.readium.r2.navigator.pdf.PdfNavigatorFragment
import org.readium.r2.navigator.input.InputListener
import org.readium.r2.navigator.input.TapEvent
import org.readium.r2.navigator.util.DirectionalNavigationAdapter
import org.readium.r2.shared.util.AbsoluteUrl

/** Taps this far in from either side turn the page; taps between show or hide the chrome. */
private const val EDGE_FRACTION = 0.2

/** A locator change this soon after the voice's own jump is the jump, not the user. */
private const val VOICE_LOG = "GrangerVoice"

object EbookTags {
    const val FOLLOW = "ebook_follow"
    const val PAGE = "ebook_page"
}

/**
 * The e-book reader, paginated. It opens (and comes back) with its chrome up: the top and bottom bars here and the
 * shell's rail and mini-player (through [onChromeVisibleChange]), over the page. Edge taps and swipes turn pages, and
 * a turn slides the chrome and the system bars away; a tap in the middle toggles them. A system-bar swipe or a rising
 * [showChromeRequest] (the shell's edge swipe) shows the chrome. The navigator is never resized for any of it.
 */
@Composable
fun EbookReaderScreen(
    viewModel: EbookReaderViewModel,
    onChromeVisibleChange: (Boolean) -> Unit,
    showChromeRequest: Int = 0,
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
    var navigator by remember { mutableStateOf<VisualNavigator?>(null) }
    // Decorations, the selection and page JavaScript are the EPUB navigator's only (Plan P2): null for a PDF.
    val epub = navigator as? EpubNavigatorFragment
    val scope = rememberCoroutineScope()
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
    SystemBarsHidden(!state.chromeVisible, onSwipedBack = viewModel::showChrome)
    ChromeBackOnReturn(viewModel::onScreenLeft)
    // Backgrounded, or the screen left: write the place now rather than after the debounce.
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { viewModel.flush() }

    val lifecycle = LocalLifecycleOwner.current.lifecycle
    // A page change is the user's unless the voice just turned the page (the view model keeps that moment: this
    // screen can be composed again while the voice reads on).
    LaunchedEffect(navigator, lifecycle) {
        navigator?.currentLocator?.collect {
            viewModel.onLocator(it, onScreen = lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))
            Log.d(VOICE_LOG, "Voice: page ${it.href} p=${it.locations.progression}")
            viewModel.onUserMoved()
        }
    }
    // The voice's sentence: decorated, and the page turned to it while the reader follows. Every sentence is asked
    // for (a paragraph can run over the page): a sentence already on the page moves nothing.
    LaunchedEffect(epub, state.reading?.locator) {
        val reading = state.reading
        epub?.applyDecorations(
            listOfNotNull(reading?.let { Decoration("voice", it.locator, Decoration.Style.Highlight(tint = READING_TINT)) }), VOICE_GROUP,
        )
    }
    LaunchedEffect(navigator, state.reading?.locator, state.reading?.following) {
        val reading = state.reading ?: return@LaunchedEffect
        if (!reading.following) return@LaunchedEffect
        viewModel.voiceMoved()
        val went = navigator?.go(reading.locator, animated = false)
        Log.d(VOICE_LOG, "Voice: follow ¶${reading.paragraph} go=$went ${reading.locator.href} \"${reading.locator.text.highlight?.take(30)}\"")
    }
    LaunchedEffect(navigator) {
        navigator?.let { fragment -> viewModel.navigation.collect { fragment.go(it, animated = false) } }
    }
    // The selection menu's taps: read the selection, act, then clear it and close the menu, then say what went wrong.
    LaunchedEffect(epub) {
        val fragment = epub ?: return@LaunchedEffect
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
                        SelectionAction.READ_ALOUD -> viewModel.readFrom(selection.locator)
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
    LaunchedEffect(epub, state.decorations) {
        epub?.applyDecorations(state.decorations, HIGHLIGHT_GROUP)
    }
    LaunchedEffect(epub, state.searchHit) {
        val hit = state.searchHit?.let { Decoration("search-hit", it, Decoration.Style.Highlight(tint = SEARCH_HIT_TINT)) }
        epub?.applyDecorations(listOfNotNull(hit), SEARCH_GROUP)
    }
    // One tap listener per navigator instance; a tap on a drawn highlight opens its sheet.
    // A link on a PDF page: one to the web opens in the browser, one within the PDF jumps to its page.
    val pdf = viewModel.navigatorFactory as? BookNavigator.Pdf
    DisposableEffect(pdf, appContext) {
        pdf?.links?.onLink = { tap ->
            val uri = tap.uri
            if (!uri.isNullOrBlank()) openInBrowser(appContext, uri) else viewModel.onPdfLink(tap)
        }
        onDispose { pdf?.links?.onLink = null }
    }
    DisposableEffect(epub) {
        val fragment = epub
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
        when (val shown = navigator) {
            is EpubNavigatorFragment -> shown.submitPreferences(state.prefs.toEpub())
            // The only PDF navigator the reader builds is PDFium's (BookNavigator.Pdf).
            is PdfNavigatorFragment<*, *> -> @Suppress("UNCHECKED_CAST") (shown as PdfiumNavigatorFragment).submitPreferences(state.prefs.toPdfium())
            else -> Unit
        }
    }

    val bars = bookBarActions(
        isPdf = state.isPdf,
        bookmarked = state.isBookmarkedHere,
        onBack = onBack,
        onContents = { viewModel.open(Panel.CONTENTS) },
        onSearch = { viewModel.open(Panel.SEARCH) },
        onTextSettings = { viewModel.open(Panel.SETTINGS) },
        onBookmark = viewModel::toggleBookmark,
        voice = state.voice,
        onListen = if (!Features.READ_ALOUD || !Features.PLAYER) null else ({
            val fragment = epub
            scope.launch {
                // The page's first words place the voice; Readium's first visible element is the
                // fallback (it can be the paragraph that ran over from the page before).
                val words = fragment?.let { runCatching { FirstVisibleText.parse(it.evaluateJavascript(FirstVisibleText.JS)) }.getOrNull() }
                val page = if (words != null) fragment.currentLocator.value else fragment?.let { runCatching { it.firstVisibleElementLocator() }.getOrNull() }
                viewModel.readAloud(page, words)
            }
        }),
    )

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        // The navigator has the whole screen whether the chrome shows or not (Plan U3c Decision 5), so Readium never
        // lays the book out again for it: the bars here, and the shell's rail and mini-player, slide over it and away.
        // Readium's own cutout padding is off (readerConfiguration): the page is padded here, for the cutout and the rail.
        Box(
            Modifier
                .fillMaxSize()
                .testTag(EbookTags.PAGE)
                .windowInsetsPadding(ReaderBookPageInsets)
                .pageTouches(viewModel::onPageTouch),
        ) {
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
                    fragments = navigatorFragments(factory, state.locator ?: state.initial, state.prefs, listener, config),
                    // PDFium inverts a page only as it lays the document out: Night on a PDF needs a fresh navigator.
                    look = if (factory is BookNavigator.Pdf) state.prefs.pdfLook else "",
                    isCleared = { viewModel.isCleared },
                    onReady = { fragment ->
                        val taps = listOf(
                            DirectionalNavigationAdapter(
                                fragment as OverflowableNavigator,
                                minimumHorizontalEdgeSize = 0.0,
                                horizontalEdgeThresholdPercent = EDGE_FRACTION,
                                animatedTransition = true,
                            ),
                            MiddleTapListener(fragment, viewModel::toggleChrome),
                        )
                        // A PDF's links are found after the tap is handed over: its turns and chrome wait for that.
                        if (factory is BookNavigator.Pdf) {
                            fragment.addInputListener(LinkAwareTaps(taps, factory.links))
                        } else {
                            taps.forEach(fragment::addInputListener)
                        }
                        navigator = fragment
                    },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        // An error leaves only ←, as the wiki's does.
        SlidingBar(state.chromeVisible, top = true) {
            ReaderTopBar(state.title, if (state.error == null) bars else ReaderBarActions(onBack = onBack))
        }
        if (state.error == null) {
            SlidingBar(state.chromeVisible, top = false) {
                ReaderBottomBar(bars) {
                    BookProgress(state.locator ?: state.initial, state.positionsCount, viewModel::goToProgression)
                }
            }
        }
        val reading = state.reading
        if (reading != null && !reading.following) {
            AssistChip(
                onClick = viewModel::follow,
                label = { Text("Follow") },
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 96.dp).testTag(EbookTags.FOLLOW),
            )
        }
        // Clear of the shell's rail and mini-player, which are drawn over the screen.
        ClearOfShell {
            when (state.panel) {
                Panel.CONTENTS -> ContentsPanel(state, viewModel::goTo, viewModel::goToMark, onDismiss = { viewModel.open(null) })
                Panel.SETTINGS -> if (state.isPdf) {
                    PdfSettingsSheet(state.prefs, viewModel::updatePrefs, onDismiss = { viewModel.open(null) })
                } else {
                    TextSettingsSheet(state.prefs, viewModel::updatePrefs, onDismiss = { viewModel.open(null) })
                }
                Panel.SEARCH -> BookSearchPanel(state.search, viewModel::search, viewModel::goToHit, onDismiss = { viewModel.open(null) })
                null -> Unit
            }
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

/**
 * What builds the navigator for [factory]'s format, at [initial]. Remembered on its inputs, so it is rebuilt
 * whenever the place changes and a navigator restored after rotation opens there.
 */
@Composable
private fun navigatorFragments(
    factory: BookNavigator,
    initial: org.readium.r2.shared.publication.Locator?,
    prefs: ReadingPrefs,
    listener: EpubNavigatorFragment.Listener,
    config: EpubNavigatorFragment.Configuration,
): androidx.fragment.app.FragmentFactory = remember(factory, initial, prefs, listener, config) {
    when (factory) {
        is BookNavigator.Epub -> factory.factory.createFragmentFactory(
            initialLocator = initial,
            initialPreferences = prefs.toEpub(),
            listener = listener,
            configuration = config,
        )
        is BookNavigator.Pdf -> factory.factory.createFragmentFactory(initialLocator = initial, initialPreferences = prefs.toPdfium())
    }
}

/**
 * Tells [onTouch] when a finger goes down on the page (true) and when the last one lifts (false). It only watches:
 * the navigator underneath still gets every touch.
 */
private fun Modifier.pageTouches(onTouch: (down: Boolean) -> Unit): Modifier = pointerInput(Unit) {
    awaitEachGesture {
        // Initial pass: seen before the navigator's view takes the touch.
        awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        onTouch(true)
        do {
            val event = awaitPointerEvent(PointerEventPass.Initial)
        } while (event.changes.any { it.pressed })
        onTouch(false)
    }
}

/** Toggles the chrome on a tap in the middle of the page; edge taps are left to page turning. */
private class MiddleTapListener(
    private val navigator: VisualNavigator,
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
    override fun onExternalLinkActivated(url: AbsoluteUrl) = openInBrowser(appContext, url.toString())
}

/** Opens [url] in the phone's browser, when it has one. */
private fun openInBrowser(appContext: Context, url: String) {
    try {
        appContext.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (e: ActivityNotFoundException) {
        Log.w("Granger", "No app can open $url")
    }
}
