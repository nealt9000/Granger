package app.granger.ebook

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import app.granger.player.VoiceButton
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
import androidx.compose.material3.LocalContentColor
import androidx.compose.ui.res.painterResource
import app.granger.R
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
import org.readium.r2.shared.publication.Locator

/** Back, the book's title, Contents and Search. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EbookTopBar(title: String, onBack: () -> Unit, onContents: () -> Unit, onSearch: (() -> Unit)?) {
    TopAppBar(
        windowInsets = ReaderTopBarInsets,
        title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium) },
        navigationIcon = {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
        },
        actions = {
            IconButton(onClick = onContents) { Icon(Icons.Filled.Menu, contentDescription = "Contents") }
            // No in-book search for a PDF (Plan P2): Search finds its pages from the library index instead.
            if (onSearch != null) IconButton(onClick = onSearch) { Icon(Icons.Filled.Search, contentDescription = "Search in book") }
        },
    )
}

/**
 * Headphones to start the voice; while this book is read, a pause or play glyph in the accent: a tap pauses or resumes at
 * the sentence, a long press stops (Plan V2). Not an [IconButton]: it has no long press.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun VoiceBarButton(voice: VoiceButton, onReadAloud: () -> Unit, onPauseResume: () -> Unit, onStop: () -> Unit) {
    val (icon, label) = when (voice) {
        VoiceButton.IDLE -> R.drawable.ic_headphones to "Read aloud"
        VoiceButton.READING -> R.drawable.ic_pause to "Pause reading"
        VoiceButton.PAUSED -> R.drawable.ic_play to "Resume reading"
    }
    Box(
        Modifier
            .minimumInteractiveComponentSize()
            .size(40.dp)
            .clip(CircleShape)
            .combinedClickable(
                role = Role.Button,
                onClick = { if (voice == VoiceButton.IDLE) onReadAloud() else onPauseResume() },
                onLongClick = if (voice == VoiceButton.IDLE) null else onStop,
                onLongClickLabel = "Stop reading",
            )
            .testTag(EbookTags.VOICE),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painterResource(icon),
            contentDescription = label,
            tint = if (voice == VoiceButton.IDLE) LocalContentColor.current else MaterialTheme.colorScheme.primary,
        )
    }
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
    /** Starts the voice on this page (Plan V1); null leaves the slot empty. */
    onReadAloud: (() -> Unit)? = null,
    /** What the button does while this book is read (Plan V2): a tap pauses or resumes, a long press stops. */
    voice: VoiceButton = VoiceButton.IDLE,
    onPauseResume: () -> Unit = {},
    onStop: () -> Unit = {},
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
                colors = SliderDefaults.colors(thumbColor = MaterialTheme.colorScheme.primary, activeTrackColor = MaterialTheme.colorScheme.primary),
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onContents) { Icon(Icons.Filled.Menu, contentDescription = "Contents") }
                IconButton(onClick = onTextSettings) { Text("Aa", style = MaterialTheme.typography.titleMedium) }
                if (onReadAloud != null) VoiceBarButton(voice, onReadAloud, onPauseResume, onStop)
                IconButton(onClick = onBookmark) {
                    if (bookmarked) {
                        Icon(Icons.Filled.Star, contentDescription = "Remove bookmark", tint = MaterialTheme.colorScheme.primary)
                    } else {
                        Icon(Icons.Outlined.Star, contentDescription = "Add bookmark")
                    }
                }
            }
        }
    }
}
