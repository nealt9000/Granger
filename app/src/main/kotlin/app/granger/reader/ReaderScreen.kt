package app.granger.reader

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.ContextThemeWrapper
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.areSystemBarsVisible
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.fromHtml
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.granger.Features
import app.granger.theme.GrangerTheme
import app.granger.R
import app.granger.core.zim.ZimHit
import app.granger.ebook.WikiTextSheet
import app.granger.player.VoiceButton
import app.granger.core.zim.ZimResolver
import app.granger.core.zim.ZimUrls
import app.granger.shell.ChromeSlideMs
import app.granger.shell.LocalShellOverlay
import app.granger.shell.SafeDrawingIgnoringBars
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

/** Find-in-page results: [active] is 0-based. */
private data class FindMatches(val active: Int, val count: Int, val done: Boolean)

/**
 * The web-style reader. It opens (and comes back) with its chrome up: the bars here and the shell's rail and
 * mini-player (through [onChromeVisibleChange]), over the page, which is padded for them. The chrome and the system
 * bars slide away on a scroll down by hand and come back on a scroll up, a middle tap (which also hides them), a
 * system-bar swipe or the shell's edge swipe; the page is never laid out again for them. Selecting text offers
 * Search ([onSearchText]) and Research ([onResearch], with the article it was selected in); a rising [showChromeRequest] (the shell's edge swipe) shows the chrome.
 * The bars are the readers' shared ones ([wikiBarActions]): the page's title, Main page, Listen and ⋯ above; Contents,
 * the pack's search, Aa and the bookmark below.
 * Back, the ← arrow or the system's, steps back through the pages read in this tab; at its first page it calls [onBack].
 */
@Composable
fun ReaderScreen(
    viewModel: ReaderViewModel,
    resolver: ZimResolver,
    onChromeVisibleChange: (Boolean) -> Unit,
    showChromeRequest: Int = 0,
    onSearchText: (String) -> Unit,
    onResearch: (text: String, packId: String, path: String, title: String) -> Unit = { _, _, _, _ -> },
    /** A tapped PDF in the pack (Plan P2): opened in the e-book reader over this page; [linkText] may name it. */
    onOpenDocument: (packId: String, path: String, linkText: String) -> Unit = { _, _, _ -> },
    onBack: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val night = GrangerTheme.extras.night
    var webView by remember { mutableStateOf<ReaderWebView?>(null) }
    // Set when Back loads the tab's previous page from its stored stack: once shown, the WebView's history is cleared
    // so it holds only that page, and the next Back goes on down the stored stack instead of forward again.
    var clearHistoryOnShow by remember { mutableStateOf(false) }
    var matches by remember { mutableStateOf<FindMatches?>(null) }
    val searchText by rememberUpdatedState(onSearchText)
    val research by rememberUpdatedState(onResearch)

    fun saveScroll() {
        webView?.let { viewModel.savePosition(it.url, it.scrollFraction()) }
    }

    /** Loads [url] in the same WebView, so Back returns to the page being left. */
    fun navigate(url: String) {
        val view = webView ?: return
        saveScroll()
        view.loadUrl(url)
    }

    /** ← and system Back: back through the tab's pages (see [ReaderViewModel.back]), then out of the reader. */
    fun goBack() {
        val view = webView
        val history = view?.copyBackForwardList()
        val previous = if (view?.canGoBack() == true) history?.getItemAtIndex(history.currentIndex - 1)?.url.orEmpty() else null
        val current = history?.currentItem?.url
        scope.launch {
            when (val step = viewModel.back(current, previous)) {
                ReaderViewModel.BackStep.InWebView -> {
                    saveScroll()
                    view?.goBack()
                }
                is ReaderViewModel.BackStep.Load -> {
                    clearHistoryOnShow = true
                    navigate(step.url)
                }
                ReaderViewModel.BackStep.Leave -> onBack()
            }
        }
    }

    // The shell's left-edge swipe asks for the chrome; only a request newer than the one seen at entry counts, so an old swipe isn't replayed.
    val requestAtEntry = remember { showChromeRequest }
    LaunchedEffect(showChromeRequest) { if (isNewChromeRequest(requestAtEntry, showChromeRequest)) viewModel.showChrome() }
    LaunchedEffect(state.chromeVisible) { onChromeVisibleChange(state.chromeVisible) }
    SystemBarsHidden(!state.chromeVisible, onSwipedBack = viewModel::showChrome)
    ChromeBackOnReturn(viewModel::onScreenLeft)

    BackHandler { goBack() }
    // Declared later, so it takes Back first while the find bar is open.
    BackHandler(enabled = state.findQuery != null) { viewModel.endFind() }
    BackHandler(enabled = state.packSearch != null) { viewModel.endPackSearch() }
    BackHandler(enabled = state.panel != null) { viewModel.open(null) }
    // Fires when the app goes to the background and when this screen is left or popped.
    LifecycleEventEffect(Lifecycle.Event.ON_PAUSE) { saveScroll() }

    val findQuery = state.findQuery
    LaunchedEffect(findQuery, webView) {
        val view = webView ?: return@LaunchedEffect
        if (findQuery.isNullOrEmpty()) {
            view.clearMatches()
            matches = null
        } else {
            view.findAllAsync(findQuery)
        }
    }

    val prefs by viewModel.prefs.collectAsStateWithLifecycle()
    // The reader theme (Aa): Match app follows the app; Day and Night are the page's own.
    val pageNight = prefs.wikiNight(night)
    val zoom = prefs.wikiTextZoom
    LaunchedEffect(zoom, webView) { webView?.settings?.textZoom = zoom }

    val bars = wikiBarActions(
        packTitle = state.packTitle,
        hasPack = state.error == null,
        hasPage = state.page != null,
        hasHome = state.hasHome,
        bookmarked = state.bookmarked,
        onBack = ::goBack,
        onHome = { scope.launch { viewModel.homeUrl()?.let(::navigate) } },
        onRandom = {
            scope.launch {
                val url = viewModel.randomUrl()
                if (url != null) navigate(url) else Toast.makeText(context, NO_RANDOM_ARTICLE, Toast.LENGTH_SHORT).show()
            }
        },
        onFind = viewModel::startFind,
        onSearchPack = viewModel::startPackSearch,
        onContents = { viewModel.open(ReaderViewModel.Panel.CONTENTS) },
        onTextSettings = { viewModel.open(ReaderViewModel.Panel.TEXT) },
        onToggleBookmark = viewModel::toggleBookmark,
        voice = state.voice,
        onPauseResume = viewModel::pauseResume,
        onStop = viewModel::stopReading,
        onListen = if (!Features.READ_ALOUD || !Features.PLAYER) null else ({
            val view = webView
            if (view == null) viewModel.readAloud(null)
            else view.evaluateJavascript(ReadAloudScript.call("firstVisible")) { json -> viewModel.readAloud(BlockRef.parse(json)) }
        }),
    )

    // The bars' heights with their insets, for the page's padding: the nominal ones until the bars are measured, so a
    // page loading before then is padded already. Kept while the bars are away, so a toggle doesn't change them.
    val density = LocalDensity.current
    val nominalTop = ReaderTopBarInsets.getTop(density) + with(density) { ReaderTopBarHeight.roundToPx() }
    val nominalBottom = ReaderBottomBarInsets.getBottom(density) + with(density) { ReaderBottomBarHeight.roundToPx() }
    var topBarPx by remember { mutableIntStateOf(nominalTop) }
    var bottomBarPx by remember { mutableIntStateOf(nominalBottom) }
    val padTop = with(density) { topBarPx.toDp().value.roundToInt() }
    val padBottom = with(density) { bottomBarPx.toDp().value.roundToInt() }
    // The rail's place over the page, past what the page box already clears (a cutout): constant, as the shell keeps
    // it while the rail is away, so the lines' starts clear the rail at rest and nothing reflows when it slides.
    val direction = LocalLayoutDirection.current
    val overlay = LocalShellOverlay.current
    val pageInsets = ReaderPageInsets
    val padLeft = with(density) { (overlay.getLeft(density, direction) - pageInsets.getLeft(density, direction)).coerceAtLeast(0).toDp().value.roundToInt() }
    val padRight = with(density) { (overlay.getRight(density, direction) - pageInsets.getRight(density, direction)).coerceAtLeast(0).toDp().value.roundToInt() }
    LaunchedEffect(webView, padTop, padBottom, padLeft, padRight) { webView?.setChromePadding(padTop, padBottom, padLeft, padRight) }

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        // The page has the whole screen whether the chrome shows or not (Plan U3c Decision 5): the bars here, and the
        // shell's rail and mini-player, slide over it and away, and the page is padded inside for them.
        Box(
            Modifier
                .fillMaxSize()
                .testTag(ReaderTags.PAGE)
                // An error has no bottom bar, so its message keeps clear of the navigation bar itself.
                .windowInsetsPadding(if (state.error != null) SafeDrawingIgnoringBars.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal) else ReaderPageInsets),
        ) {
            val error = state.error
            val startUrl = state.url
            when {
                error != null -> Box(Modifier.fillMaxSize().padding(32.dp), Alignment.Center) {
                    Text(error, textAlign = TextAlign.Center)
                }
                // A new reader theme builds the WebView again (its history kept, as for a rotation): a WebView
                // darkens pages only when its context's theme is dark.
                startUrl != null -> key(pageNight) { AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { activity ->
                        val context = ContextThemeWrapper(activity, if (pageNight) R.style.Theme_Granger_Night else R.style.Theme_Granger_Field)
                        val client = ZimWebViewClient(
                            resolver = resolver,
                            assets = context.assets,
                            onExternalLink = { url -> openExternal(context, url) },
                            onLeavingPage = { view -> viewModel.savePosition(view.url, (view as ReaderWebView).scrollFraction()) },
                            onPageShown = { view, url ->
                                if (clearHistoryOnShow) {
                                    clearHistoryOnShow = false
                                    view.clearHistory()
                                }
                                // Before the place is restored, so it is restored on the padded page.
                                (view as ReaderWebView).applyChromePadding()
                                val title = view.title?.takeUnless { it == url }
                                scope.launch {
                                    viewModel.onPageShown(url, title)?.let { (view as ReaderWebView).scrollToFraction(it) }
                                }
                            },
                            onVisited = viewModel::onPageVisited,
                            onOpenDocument = { packId, path, linkText ->
                                // Leaving the article for the document: keep the place on the page, as any link does.
                                webView?.let { viewModel.savePosition(it.url, it.scrollFraction()) }
                                onOpenDocument(packId, path, linkText)
                            },
                        )
                        ReaderWebView(
                            context = context,
                            client = client,
                            night = pageNight,
                            onToggleChrome = viewModel::toggleChrome,
                            onSelectionAction = { action, text ->
                                when (action) {
                                    SelectionAction.SEARCH -> searchText(text.take(200))
                                    SelectionAction.RESEARCH -> {
                                        val page = viewModel.state.value.page
                                        val pack = viewModel.packId
                                        if (page != null && pack != null) research(text, pack, page.path, page.title)
                                    }
                                    SelectionAction.READ_ALOUD -> BlockRef.parse(text)?.let(viewModel::readFrom)
                                }
                            },
                            onUserScroll = viewModel::onUserScroll,
                        ).also {
                            it.setFindListener { active, count, done -> matches = FindMatches(active, count, done) }
                            it.settings.textZoom = zoom
                            it.setChromePadding(padTop, padBottom, padLeft, padRight)
                            webView = it
                            val saved = viewModel.webViewState
                            if (saved == null || it.restoreState(saved) == null) it.loadUrl(startUrl)
                        }
                    },
                    onRelease = {
                        viewModel.savePosition(it.url, it.scrollFraction())
                        viewModel.webViewState = Bundle().apply { it.saveState(this) }
                        it.destroy()
                    },
                ) }
            }
            // A cited quote is marked once its page has loaded (the page script is in by then).
            val cite = state.cite
            val citedPage = state.page?.path
            LaunchedEffect(cite, citedPage, webView) {
                val view = webView ?: return@LaunchedEffect
                if (cite == null) view.evaluateJavascript(ReadAloudScript.call("clearCite"), null)
                else if (citedPage != null) view.evaluateJavascript(ReadAloudScript.call("cite", cite.blockId, cite.prefix, cite.start, cite.end), null)
            }
            // The voice's sentence is marked in the page and scrolled into view while the page follows it.
            val reading = state.reading
            LaunchedEffect(reading, webView) {
                val view = webView ?: return@LaunchedEffect
                if (reading == null) {
                    view.evaluateJavascript(ReadAloudScript.call("clear"), null)
                } else {
                    view.evaluateJavascript(
                        ReadAloudScript.call("mark", reading.address.blockId, reading.prefix, reading.start, reading.end, reading.following), null,
                    )
                }
            }
            if (reading != null && !reading.following) {
                val followLift by animateDpAsState(
                    with(density) { if (state.chromeVisible) bottomBarPx.toDp() else ReaderBottomBarInsets.getBottom(density).toDp() },
                    tween(ChromeSlideMs),
                    label = "follow",
                )
                AssistChip(
                    onClick = viewModel::follow,
                    label = { Text("Follow") },
                    leadingIcon = { Icon(painterResource(R.drawable.ic_replay), contentDescription = null, Modifier.size(18.dp)) },
                    // Above the bottom bar while it shows.
                    modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = followLift + 16.dp).testTag(ReaderTags.FOLLOW),
                )
            }
        }
        SlidingBar(state.chromeVisible, top = true, onHeight = { topBarPx = it }) {
            if (findQuery != null) {
                FindBar(
                    query = findQuery,
                    matches = matches,
                    onQueryChange = viewModel::setFindQuery,
                    onNext = { forward -> webView?.findNext(forward) },
                    onClose = viewModel::endFind,
                )
            } else {
                ReaderTopBar(title = state.page?.title ?: state.packTitle.orEmpty(), actions = bars)
            }
        }
        if (state.error == null) SlidingBar(state.chromeVisible, top = false, onHeight = { bottomBarPx = it }) { ReaderBottomBar(bars) }
        // The search and the panels over the page and its bars, clear of the shell's rail and mini-player above them.
        ClearOfShell {
            val packSearch = state.packSearch
            if (state.chromeVisible && packSearch != null) {
                PackSearchPanel(
                    search = packSearch,
                    packTitle = state.packTitle,
                    onQueryChange = viewModel::setPackQuery,
                    onSubmit = viewModel::submitPackSearch,
                    onOpen = { hit ->
                        viewModel.packId?.let { navigate(ZimUrls.forEntry(it, hit.path, null)) }
                        viewModel.endPackSearch()
                    },
                    onClose = viewModel::endPackSearch,
                )
            }
            when (state.panel) {
                ReaderViewModel.Panel.CONTENTS -> WikiContentsPanel(
                    sections = state.contents,
                    onJump = { section ->
                        webView?.evaluateJavascript(ReadAloudScript.call("jump", section.blockId, section.prefix), null)
                        viewModel.open(null)
                    },
                    onDismiss = { viewModel.open(null) },
                )
                ReaderViewModel.Panel.TEXT -> WikiTextSheet(prefs, viewModel::updatePrefs, onDismiss = { viewModel.open(null) })
                null -> Unit
            }
        }
    }
}

/**
 * The wiki's bars (Plan U3c Decision 4). Top: ←, the page's title, the pack's main page (a button since the walkthrough:
 * one tap home), Listen, and ⋯ with Random article and Find in page. While the page is read aloud, Pause and Stop need
 * the room, and Main page moves to the top of ⋯. Bottom: Contents, Search (the pack), Aa and the bookmark. With an
 * error ([hasPack] false) only ← is left; the page's own actions (Listen, Contents, Aa, the star, Find) wait for a page
 * ([hasPage]).
 */
internal fun wikiBarActions(
    packTitle: String?,
    hasPack: Boolean,
    hasPage: Boolean,
    hasHome: Boolean,
    bookmarked: Boolean,
    onBack: () -> Unit,
    onHome: () -> Unit = {},
    onRandom: () -> Unit = {},
    onFind: () -> Unit = {},
    onSearchPack: () -> Unit = {},
    onContents: () -> Unit = {},
    onTextSettings: () -> Unit = {},
    onToggleBookmark: () -> Unit = {},
    /** Starts the voice on this page (Plan V1); null leaves Listen out. */
    onListen: (() -> Unit)? = null,
    voice: VoiceButton = VoiceButton.IDLE,
    onPauseResume: () -> Unit = {},
    onStop: () -> Unit = {},
): ReaderBarActions {
    // While this page is read, Pause and Stop take Listen's place and Main page waits in ⋯: a usual title stays whole at 360 dp.
    val voiceHere = onListen != null && hasPage && voice != VoiceButton.IDLE
    return ReaderBarActions(
        onBack = onBack,
        onMainPage = onHome.takeIf { hasPack && hasHome && !voiceHere },
        onListen = onListen?.takeIf { hasPage },
        voice = voice,
        onPauseResume = onPauseResume,
        onStop = onStop,
        menu = if (!hasPack) emptyList() else listOfNotNull(
            ReaderMenuItem(MAIN_PAGE, onHome).takeIf { hasHome && voiceHere },
            ReaderMenuItem("Random article", onRandom),
            ReaderMenuItem("Find in page", onFind).takeIf { hasPage },
        ),
        onContents = onContents.takeIf { hasPage },
        onSearch = onSearchPack.takeIf { hasPack },
        searchLabel = searchLabel(packTitle),
        onTextSettings = onTextSettings.takeIf { hasPage },
        onBookmark = onToggleBookmark.takeIf { hasPage },
        bookmarked = bookmarked,
    )
}

/** The pack search's prompt: "Search Wikipedia", or "Search this pack" when the pack's title isn't known. */
internal fun searchLabel(packTitle: String?) = if (packTitle != null) "Search $packTitle" else "Search this pack"

/** The search field in place of the top bar, with the results listed under it. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PackSearchPanel(
    search: ReaderViewModel.PackSearch,
    packTitle: String?,
    onQueryChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onOpen: (ZimHit) -> Unit,
    onClose: () -> Unit,
) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        TopAppBar(
            title = {
                TextField(
                    value = search.query,
                    onValueChange = onQueryChange,
                    modifier = Modifier.fillMaxWidth().focusRequester(focus),
                    placeholder = { Text(searchLabel(packTitle), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { onSubmit() }),
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                    ),
                )
            },
            navigationIcon = {
                IconButton(onClick = onClose) { Icon(Icons.Filled.Close, contentDescription = "Close search") }
            },
        )
        if (search.searched && search.hits.isEmpty()) {
            Text("No matches in this pack.", Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium)
        }
        LazyColumn(Modifier.fillMaxWidth().imePadding().navigationBarsPadding()) {
            items(search.hits, key = { it.path }) { hit ->
                ListItem(
                    headlineContent = { Text(hit.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    supportingContent = hit.snippet?.let { snippet ->
                        { Text(AnnotatedString.fromHtml(snippet), maxLines = 3, overflow = TextOverflow.Ellipsis) }
                    },
                    modifier = Modifier.clickable { onOpen(hit) },
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FindBar(
    query: String,
    matches: FindMatches?,
    onQueryChange: (String) -> Unit,
    onNext: (forward: Boolean) -> Unit,
    onClose: () -> Unit,
) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    TopAppBar(
        windowInsets = ReaderTopBarInsets,
        title = {
            TextField(
                value = query,
                onValueChange = onQueryChange,
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
                placeholder = { Text("Find in page") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { onNext(true) }),
                suffix = {
                    val count = when {
                        query.isEmpty() || matches == null -> null
                        matches.count > 0 -> "${matches.active + 1} of ${matches.count}"
                        matches.done -> "No matches"
                        else -> null
                    }
                    if (count != null) Text(count, style = MaterialTheme.typography.bodySmall)
                },
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                ),
            )
        },
        navigationIcon = {
            IconButton(onClick = onClose) { Icon(Icons.Filled.Close, contentDescription = "Close find") }
        },
        actions = {
            val any = (matches?.count ?: 0) > 0
            IconButton(onClick = { onNext(false) }, enabled = any) {
                Icon(Icons.Filled.KeyboardArrowUp, contentDescription = "Previous match")
            }
            IconButton(onClick = { onNext(true) }, enabled = any) {
                Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "Next match")
            }
        },
    )
}

/**
 * Hides the status and navigation bars while [hidden]; a swipe from an edge shows them briefly, and calls
 * [onSwipedBack] so the reader can bring its chrome back with them. Readers on screen at once (a crossfade between
 * two) share one count of hide requests, so the outgoing reader's cleanup can't show the bars the incoming one just hid.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SystemBarsHidden(hidden: Boolean, onSwipedBack: () -> Unit = {}) {
    val view = LocalView.current
    DisposableEffect(view, hidden) {
        val window = view.context.findActivity()?.window ?: return@DisposableEffect onDispose {}
        val controller = WindowCompat.getInsetsController(window, view)
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        if (hidden) BarsHideRequests.count++
        BarsHideRequests.apply(controller)
        onDispose {
            if (hidden) BarsHideRequests.count--
            BarsHideRequests.apply(controller)
        }
    }
    // The transient bars a swipe brings: seen as the bars turning visible again after they went while hidden.
    val barsVisible = WindowInsets.areSystemBarsVisible
    val swipedBack by rememberUpdatedState(onSwipedBack)
    val watch = remember { TransientBarsWatch() }
    LaunchedEffect(hidden, barsVisible) { if (watch.swipedBack(hidden, barsVisible)) swipedBack() }
}

/**
 * Tells a system-bar swipe from the bars just being slow to go: while the reader wants them hidden, they must first
 * be seen hidden, and then come back. Asking for them hidden again starts over.
 */
internal class TransientBarsWatch {
    private var seenHidden = false

    /** The bars' visibility now; true once when, after hiding, they show while still [hidden]. */
    fun swipedBack(hidden: Boolean, barsVisible: Boolean): Boolean {
        if (!hidden) {
            seenHidden = false
            return false
        }
        if (!barsVisible) {
            seenHidden = true
            return false
        }
        if (!seenHidden) return false
        seenHidden = false
        return true
    }
}

/** How many readers currently want the system bars hidden. Touched only on the main thread. */
internal object BarsHideRequests {
    var count = 0

    fun apply(controller: WindowInsetsControllerCompat) {
        if (count > 0) controller.hide(WindowInsetsCompat.Type.systemBars()) else controller.show(WindowInsetsCompat.Type.systemBars())
    }
}

/**
 * Calls [onLeft] when a reader's screen goes away (left, or another screen put on top), but not for a rotation:
 * the view model, and with it full-screen reading, carries over that.
 */
@Composable
internal fun ChromeBackOnReturn(onLeft: () -> Unit) {
    val activity = LocalContext.current.findActivity()
    val left by rememberUpdatedState(onLeft)
    DisposableEffect(activity) {
        onDispose { if (activity?.isChangingConfigurations != true) left() }
    }
}

internal tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

private fun openExternal(context: Context, url: String) {
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    } catch (e: ActivityNotFoundException) {
        Toast.makeText(context, "No app on this phone can open that link.", Toast.LENGTH_SHORT).show()
    }
}

private const val NO_RANDOM_ARTICLE = "This pack has no articles to pick from."

/** A shell edge-swipe request counts only when it is newer than the counter value this reader started with. */
internal fun isNewChromeRequest(atEntry: Int, current: Int): Boolean = current > atEntry

object ReaderTags {
    const val PAGE = "reader_page"
    const val FOLLOW = "reader_follow"
}
