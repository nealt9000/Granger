package app.granger.reader

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.granger.R
import app.granger.core.zim.ZimResolver
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderScreen(
    viewModel: ReaderViewModel,
    resolver: ZimResolver,
    onBack: () -> Unit,
    onSearch: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var webView by remember { mutableStateOf<ReaderWebView?>(null) }
    var canGoBack by remember { mutableStateOf(false) }

    fun saveScroll() {
        webView?.let { viewModel.savePosition(it.url, it.scrollFraction()) }
    }

    BackHandler(enabled = canGoBack) {
        saveScroll()
        webView?.goBack()
    }
    // Fires when the app goes to the background and when this screen is left or popped.
    LifecycleEventEffect(Lifecycle.Event.ON_PAUSE) { saveScroll() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(state.page?.title.orEmpty(), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back to library")
                    }
                },
                actions = {
                    IconButton(onClick = onSearch) { Icon(Icons.Filled.Search, contentDescription = "Search") }
                    if (state.page != null) {
                        IconButton(onClick = viewModel::toggleBookmark) {
                            if (state.bookmarked) {
                                Icon(Icons.Filled.Star, contentDescription = "Remove bookmark")
                            } else {
                                Icon(painterResource(R.drawable.ic_star_border), contentDescription = "Add bookmark")
                            }
                        }
                    }
                },
            )
        },
    ) { padding ->
        val error = state.error
        val startUrl = state.url
        when {
            error != null -> Box(Modifier.padding(padding).fillMaxSize().padding(24.dp), Alignment.Center) {
                Text(error)
            }
            startUrl != null -> AndroidView(
                modifier = Modifier.padding(padding).fillMaxSize(),
                factory = { context ->
                    val client = ZimWebViewClient(
                        resolver = resolver,
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
                    ReaderWebView(context, client).also {
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
    }
}

private fun openExternal(context: Context, url: String) {
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    } catch (e: ActivityNotFoundException) {
        Toast.makeText(context, "No app on this phone can open that link.", Toast.LENGTH_SHORT).show()
    }
}
