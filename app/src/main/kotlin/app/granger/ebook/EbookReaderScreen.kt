package app.granger.ebook

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.granger.reader.SystemBarsHidden
import app.granger.reader.isNewChromeRequest
import app.granger.shell.NarrowRailWidth
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
    onBack: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var navigator by remember { mutableStateOf<EpubNavigatorFragment?>(null) }
    val appContext = LocalContext.current.applicationContext
    // Outlives a rotation inside the restored navigator, so it holds nothing tied to an activity.
    val listener = remember(appContext) { ExternalLinkListener(appContext) }
    val config = remember { readerConfiguration() }

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
            state.publication != null && factory != null && bookId != null -> NavigatorHost(
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
        AnimatedVisibility(
            visible = state.chromeVisible,
            // The shell's rail is drawn over the reader's left edge while the chrome shows.
            modifier = Modifier.padding(start = NarrowRailWidth),
            enter = fadeIn() + slideInVertically { -it },
            exit = fadeOut() + slideOutVertically { -it },
        ) {
            EbookTopBar(title = state.title, chapter = state.locator?.title.orEmpty(), onBack = onBack)
        }
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EbookTopBar(title: String, chapter: String, onBack: () -> Unit) {
    TopAppBar(
        title = {
            Text(
                chapter.ifEmpty { title },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.titleMedium,
            )
        },
        navigationIcon = {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
        },
    )
}
