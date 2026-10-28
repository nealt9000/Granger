package app.granger.reader

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
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
import app.granger.player.VoiceButton
import app.granger.core.zim.ZimResolver
import app.granger.core.zim.ZimUrls
import app.granger.shell.SafeDrawingIgnoringBars
import kotlinx.coroutines.launch

/** Find-in-page results: [active] is 0-based. */
private data class FindMatches(val active: Int, val count: Int, val done: Boolean)

/**
 * The web-style reader. It opens (and comes back) with its chrome up: the top bar here and the shell's rail (through
 * [onChromeVisibleChange]), with the page fitted below and beside them. A middle tap on the page hides the chrome and
 * the system bars for full-screen reading; another brings them back. Selecting text offers
 * Search ([onSearchText]) and Research ([onResearch], with the article it was selected in); a rising [showChromeRequest] (the shell's edge swipe) shows the chrome; the top bar offers search inside the pack, the bookmark star and a menu with Home, Random article and find in page.
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
    /** A tapped PDF in the pack (Plan P2): opened in the e-book reader over this page. */
    onOpenDocument: (packId: String, path: String) -> Unit = { _, _ -> },
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
    SystemBarsHidden(!state.chromeVisible)
    ChromeBackOnReturn(viewModel::onScreenLeft)

    BackHandler { goBack() }
    // Declared later, so it takes Back first while the find bar is open.
    BackHandler(enabled = state.findQuery != null) { viewModel.endFind() }
    BackHandler(enabled = state.packSearch != null) { viewModel.endPackSearch() }
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

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        // With the chrome up the page is laid out below the top bar and above the navigation bar, like any other
        // screen (the shell puts the rail and the mini-player beside and above it); hidden, it fills the screen.
        Column(Modifier.fillMaxSize()) {
            // Fades only, so the page is resized once per tap rather than on every frame of a slide.
            AnimatedVisibility(visible = state.chromeVisible, enter = fadeIn(), exit = fadeOut()) {
                if (findQuery != null) {
                    FindBar(
                        query = findQuery,
                        matches = matches,
                        onQueryChange = viewModel::setFindQuery,
                        onNext = { forward -> webView?.findNext(forward) },
                        onClose = viewModel::endFind,
                    )
                } else {
                    ReaderTopBar(
                        pageTitle = state.page?.title.orEmpty(),
                        packTitle = state.packTitle,
                        showPageActions = state.page != null,
                        bookmarked = state.bookmarked,
                        showMenu = state.error == null,
                        hasHome = state.hasHome,
                        onBack = ::goBack,
                        onSearchPack = viewModel::startPackSearch,
                        onHome = { scope.launch { viewModel.homeUrl()?.let(::navigate) } },
                        onRandom = {
                            scope.launch {
                                val url = viewModel.randomUrl()
                                if (url != null) navigate(url) else Toast.makeText(context, NO_RANDOM_ARTICLE, Toast.LENGTH_SHORT).show()
                            }
                        },
                        onFind = viewModel::startFind,
                        onToggleBookmark = viewModel::toggleBookmark,
                        voice = state.voice,
                        onPauseResume = viewModel::pauseResume,
                        onStop = viewModel::stopReading,
                        onReadAloud = if (!Features.READ_ALOUD || !Features.PLAYER) null else ({
                            val view = webView
                            if (view == null) viewModel.readAloud(null)
                            else view.evaluateJavascript(ReadAloudScript.call("firstVisible")) { json -> viewModel.readAloud(BlockRef.parse(json)) }
                        }),
                    )
                }
            }
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .testTag(ReaderTags.PAGE)
                    .windowInsetsPadding(readerPageInsets(state.chromeVisible, WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal)),
            ) {
                val error = state.error
                val startUrl = state.url
                when {
                    error != null -> Box(Modifier.fillMaxSize().padding(32.dp), Alignment.Center) {
                        Text(error, textAlign = TextAlign.Center)
                    }
                    startUrl != null -> AndroidView(
                        modifier = Modifier.fillMaxSize(),
                        factory = { context ->
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
                                    val title = view.title?.takeUnless { it == url }
                                    scope.launch {
                                        viewModel.onPageShown(url, title)?.let { (view as ReaderWebView).scrollToFraction(it) }
                                    }
                                },
                                onVisited = viewModel::onPageVisited,
                                onOpenDocument = { packId, path ->
                                    // Leaving the article for the document: keep the place on the page, as any link does.
                                    webView?.let { viewModel.savePosition(it.url, it.scrollFraction()) }
                                    onOpenDocument(packId, path)
                                },
                            )
                            ReaderWebView(
                                context = context,
                                client = client,
                                night = night,
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
                                webView = it
                                val saved = viewModel.webViewState
                                if (saved == null || it.restoreState(saved) == null) it.loadUrl(startUrl)
                            }
                        },
                        onRelease = {
                            viewModel.webViewState = Bundle().apply { it.saveState(this) }
                            it.destroy()
                        },
                    )
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
                    AssistChip(
                        onClick = viewModel::follow,
                        label = { Text("Follow") },
                        leadingIcon = { Icon(painterResource(R.drawable.ic_replay), contentDescription = null, Modifier.size(18.dp)) },
                        modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp).testTag(ReaderTags.FOLLOW),
                    )
                }
            }
        }
        // Over the page and its top bar, so opening the search doesn't resize the page underneath.
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
    }
}

/**
 * The reader's top bar, in order: ← (Back within the tab), Home when the pack has a main page, the pack's search box,
 * the bookmark star and ⋯ with Random article and Find in page. It shows no page title: [pageTitle] is what TalkBack
 * reads for the bar. With an error ([showMenu] false) only ← is left. The star and Find wait for a page
 * ([showPageActions]).
 */
@Composable
internal fun ReaderTopBar(
    pageTitle: String,
    packTitle: String?,
    showPageActions: Boolean,
    bookmarked: Boolean,
    showMenu: Boolean,
    hasHome: Boolean,
    onBack: () -> Unit,
    onSearchPack: () -> Unit,
    onHome: () -> Unit,
    onRandom: () -> Unit,
    onFind: () -> Unit,
    onToggleBookmark: () -> Unit,
    /** The ⋯ menu's voice entries (Plan V2): Read aloud, or Pause / Resume reading plus Stop reading. */
    voice: VoiceButton = VoiceButton.IDLE,
    /** Starts the voice on this page (Plan V1); null leaves the entries out. */
    onReadAloud: (() -> Unit)? = null,
    onPauseResume: () -> Unit = {},
    onStop: () -> Unit = {},
) {
    var menuOpen by remember { mutableStateOf(false) }
    Surface(color = MaterialTheme.colorScheme.surface) {
        Row(
            Modifier
                .fillMaxWidth()
                .windowInsetsPadding(ReaderTopBarInsets)
                .height(64.dp)
                .padding(horizontal = 4.dp)
                .semantics { if (pageTitle.isNotEmpty()) contentDescription = pageTitle },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
            if (showMenu && hasHome) {
                // The pack's main page: a book, not a house, which is the app's Home (Plan U3b Decision 5).
                IconButton(onClick = onHome) { Icon(painterResource(R.drawable.ic_menu_book), contentDescription = mainPageLabel(packTitle)) }
            }
            if (showMenu) {
                PackSearchBox(searchLabel(packTitle), onSearchPack, Modifier.weight(1f).padding(horizontal = 4.dp))
            } else {
                Spacer(Modifier.weight(1f))
            }
            if (showPageActions) {
                IconButton(onClick = onToggleBookmark) {
                    if (bookmarked) {
                        Icon(Icons.Filled.Star, contentDescription = "Remove bookmark", tint = MaterialTheme.colorScheme.primary)
                    } else {
                        Icon(painterResource(R.drawable.ic_star_border), contentDescription = "Add bookmark")
                    }
                }
            }
            if (showMenu) Box {
                IconButton(onClick = { menuOpen = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "More") }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(text = { Text("Random article") }, onClick = { menuOpen = false; onRandom() })
                    if (showPageActions) {
                        DropdownMenuItem(text = { Text("Find in page") }, onClick = { menuOpen = false; onFind() })
                    }
                    if (showPageActions && onReadAloud != null) {
                        if (voice == VoiceButton.IDLE) {
                            DropdownMenuItem(text = { Text("Read aloud") }, onClick = { menuOpen = false; onReadAloud() })
                        } else {
                            DropdownMenuItem(
                                text = { Text(if (voice == VoiceButton.READING) "Pause reading" else "Resume reading") },
                                onClick = { menuOpen = false; onPauseResume() },
                            )
                            DropdownMenuItem(text = { Text("Stop reading") }, onClick = { menuOpen = false; onStop() })
                        }
                    }
                }
            }
        }
    }
}

/** "Search <pack>" in a rounded box; read-only, a tap opens the pack search (see [PackSearchPanel]). */
@Composable
private fun PackSearchBox(label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier
            .height(40.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .clickable(onClickLabel = "Search", role = Role.Button, onClick = onClick)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.Search, contentDescription = null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(8.dp))
        Text(
            label,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** The pack search's prompt: "Search Wikipedia", or "Search this pack" when the pack's title isn't known. */
/** What TalkBack reads for the main-page button: "Wikipedia: main page". */
internal fun mainPageLabel(packTitle: String?) = if (packTitle != null) "$packTitle: main page" else "Main page"

private fun searchLabel(packTitle: String?) = if (packTitle != null) "Search $packTitle" else "Search this pack"

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
 * Hides the status and navigation bars while [hidden]; a swipe from an edge shows them briefly. Readers on screen at
 * once (a crossfade between two) share one count of hide requests, so the outgoing reader's cleanup can't show the
 * bars the incoming one just hid.
 */
@Composable
internal fun SystemBarsHidden(hidden: Boolean) {
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
}

/** How many readers currently want the system bars hidden. Touched only on the main thread. */
internal object BarsHideRequests {
    var count = 0

    fun apply(controller: WindowInsetsControllerCompat) {
        if (count > 0) controller.hide(WindowInsetsCompat.Type.systemBars()) else controller.show(WindowInsetsCompat.Type.systemBars())
    }
}

/**
 * What a reader's page keeps clear of. With the chrome up: the bars' places on [sides] (the top bar takes the top,
 * and the e-book's bottom bar its bottom). In full screen: only a camera cutout. Both ignore whether the system bars
 * are showing, so the page is laid out once per middle tap, not along the bars' animation.
 */
@Composable
internal fun readerPageInsets(chromeVisible: Boolean, sides: WindowInsetsSides): WindowInsets =
    if (chromeVisible) SafeDrawingIgnoringBars.only(sides) else WindowInsets.displayCutout

/** A reader's top bar: below the status bar's place (or the pushing mini-player) even while the bars animate. */
internal val ReaderTopBarInsets @Composable get() = SafeDrawingIgnoringBars.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)

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
