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
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
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
import app.granger.R
import app.granger.core.zim.ZimResolver
import app.granger.shell.NarrowRailWidth
import kotlinx.coroutines.launch

/** Find-in-page results: [active] is 0-based. */
private data class FindMatches(val active: Int, val count: Int, val done: Boolean)

/**
 * The web-style reader, full screen. A middle tap on the page shows or hides the chrome: the top bar here and the
 * shell's rail (through [onChromeVisibleChange]), with the system bars hidden alongside. Selecting text offers
 * Search ([onSearchText]) and Research ([onResearch], with the article it was selected in); a rising [showChromeRequest] (the shell's edge swipe) shows the chrome; the top bar offers find in page and the bookmark star.
 */
@Composable
fun ReaderScreen(
    viewModel: ReaderViewModel,
    resolver: ZimResolver,
    onChromeVisibleChange: (Boolean) -> Unit,
    showChromeRequest: Int = 0,
    onSearchText: (String) -> Unit,
    onResearch: (text: String, packId: String, path: String, title: String) -> Unit = { _, _, _, _ -> },
    onBack: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var webView by remember { mutableStateOf<ReaderWebView?>(null) }
    var canGoBack by remember { mutableStateOf(false) }
    var matches by remember { mutableStateOf<FindMatches?>(null) }
    val searchText by rememberUpdatedState(onSearchText)
    val research by rememberUpdatedState(onResearch)

    fun saveScroll() {
        webView?.let { viewModel.savePosition(it.url, it.scrollFraction()) }
    }

    // The shell's left-edge swipe asks for the chrome; only a request newer than the one seen at entry counts, so a fresh screen stays immersive.
    val requestAtEntry = remember { showChromeRequest }
    LaunchedEffect(showChromeRequest) { if (isNewChromeRequest(requestAtEntry, showChromeRequest)) viewModel.showChrome() }
    LaunchedEffect(state.chromeVisible) { onChromeVisibleChange(state.chromeVisible) }
    SystemBarsHidden(!state.chromeVisible)

    BackHandler(enabled = canGoBack) {
        saveScroll()
        webView?.goBack()
    }
    // Declared later, so it takes Back first while the find bar is open.
    BackHandler(enabled = state.findQuery != null) { viewModel.endFind() }
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
        val error = state.error
        val startUrl = state.url
        when {
            error != null -> Box(Modifier.fillMaxSize().padding(start = NarrowRailWidth).padding(32.dp), Alignment.Center) {
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
                            canGoBack = view.canGoBack()
                            val title = view.title?.takeUnless { it == url }
                            scope.launch {
                                viewModel.onPageShown(url, title)?.let { (view as ReaderWebView).scrollToFraction(it) }
                            }
                        },
                    )
                    ReaderWebView(
                        context = context,
                        client = client,
                        onToggleChrome = viewModel::toggleChrome,
                        onSelectionAction = { action, text ->
                            when (action) {
                                SelectionAction.SEARCH -> searchText(text.take(200))
                                SelectionAction.RESEARCH -> {
                                    val page = viewModel.state.value.page
                                    val pack = viewModel.packId
                                    if (page != null && pack != null) research(text, pack, page.path, page.title)
                                }
                            }
                        },
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
        AnimatedVisibility(
            visible = state.chromeVisible,
            // The shell's rail is drawn over the reader's left edge while the chrome shows.
            modifier = Modifier.padding(start = NarrowRailWidth),
            enter = fadeIn() + slideInVertically { -it },
            exit = fadeOut() + slideOutVertically { -it },
        ) {
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
                    title = state.page?.title.orEmpty(),
                    showPageActions = state.page != null,
                    bookmarked = state.bookmarked,
                    onBack = onBack,
                    onFind = viewModel::startFind,
                    onToggleBookmark = viewModel::toggleBookmark,
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReaderTopBar(
    title: String,
    showPageActions: Boolean,
    bookmarked: Boolean,
    onBack: () -> Unit,
    onFind: () -> Unit,
    onToggleBookmark: () -> Unit,
) {
    TopAppBar(
        title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium) },
        navigationIcon = {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
        },
        actions = {
            if (showPageActions) {
                IconButton(onClick = onFind) { Icon(Icons.Filled.Search, contentDescription = "Find in page") }
                IconButton(onClick = onToggleBookmark) {
                    if (bookmarked) {
                        Icon(Icons.Filled.Star, contentDescription = "Remove bookmark", tint = MaterialTheme.colorScheme.primary)
                    } else {
                        Icon(painterResource(R.drawable.ic_star_border), contentDescription = "Add bookmark")
                    }
                }
            }
        },
    )
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

private tailrec fun Context.findActivity(): Activity? = when (this) {
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

/** A shell edge-swipe request counts only when it is newer than the counter value this reader started with. */
internal fun isNewChromeRequest(atEntry: Int, current: Int): Boolean = current > atEntry
