package app.granger.ebook

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.granger.reader.ReaderTopBarInsets
import app.granger.shell.SafeDrawingIgnoringBars
import app.granger.theme.GrangerColors
import org.readium.r2.shared.publication.Locator

/** Back, the book's title, Contents and Search. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EbookTopBar(title: String, onBack: () -> Unit, onContents: () -> Unit, onSearch: () -> Unit) {
    TopAppBar(
        windowInsets = ReaderTopBarInsets,
        title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium) },
        navigationIcon = {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
        },
        actions = {
            IconButton(onClick = onContents) { Icon(Icons.Filled.Menu, contentDescription = "Contents") }
            IconButton(onClick = onSearch) { Icon(Icons.Filled.Search, contentDescription = "Search in book") }
        },
    )
}

/**
 * The progress label over a slider through the whole book, and the reader bar under it. The slider follows
 * [locator] until the finger is down; the jump happens once, on release.
 */
@Composable
fun EbookBottomBar(
    locator: Locator?,
    positionsCount: Int,
    bookmarked: Boolean,
    onSeek: (Double) -> Unit,
    onContents: () -> Unit,
    onTextSettings: () -> Unit,
    onBookmark: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // The released value stays shown until the navigator reports a new place, so the thumb doesn't snap back.
    var dragging by remember { mutableStateOf<Float?>(null) }
    var released by remember { mutableStateOf(false) }
    LaunchedEffect(locator) {
        if (released) {
            dragging = null
            released = false
        }
    }
    val followed = (locator?.locations?.totalProgression ?: 0.0).toFloat().coerceIn(0f, 1f)
    Surface(modifier = modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surface) {
        // Clear of the navigation bar, and in landscape of the cutout beside the rail, which pads for it too.
        Column(Modifier.windowInsetsPadding(SafeDrawingIgnoringBars.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom)).padding(horizontal = 16.dp, vertical = 8.dp)) {
            if (locator != null) {
                Text(
                    ProgressLabel.of(locator, positionsCount),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Slider(
                value = dragging ?: followed,
                onValueChange = { dragging = it; released = false },
                onValueChangeFinished = {
                    dragging?.let {
                        released = true
                        onSeek(it.toDouble())
                    }
                },
                colors = SliderDefaults.colors(thumbColor = GrangerColors.Flame, activeTrackColor = GrangerColors.Flame),
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onContents) { Icon(Icons.Filled.Menu, contentDescription = "Contents") }
                IconButton(onClick = onTextSettings) { Text("Aa", style = MaterialTheme.typography.titleMedium) }
                // The read-aloud button arrives with Features.READ_ALOUD.
                IconButton(onClick = onBookmark) {
                    if (bookmarked) {
                        Icon(Icons.Filled.Star, contentDescription = "Remove bookmark", tint = GrangerColors.Flame)
                    } else {
                        Icon(Icons.Outlined.Star, contentDescription = "Add bookmark")
                    }
                }
            }
        }
    }
}
