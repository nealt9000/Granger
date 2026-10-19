package app.granger.player

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.granger.R
import app.granger.core.library.AudioBookmark
import app.granger.core.library.AudiobookChapter
import app.granger.core.player.NowPlaying
import app.granger.core.player.PlayerMode
import app.granger.core.player.PlayerUi
import app.granger.theme.GrangerColors

/** The sheets the audiobook player opens; saved so a rotation keeps the open one. */
private enum class BookSheet { CHAPTERS, SPEED, SLEEP, BOOKMARKS }

/**
 * The audiobook player (Voice-style): cover, book and chapter, a seek bar within the chapter and the whole book's
 * progress under it, big skip back/forward, chapter steps, and one tap to chapters, speed, the sleep timer and
 * bookmarks. Skip silence and volume boost are per-book toggles.
 *
 * [live]: the audiobook is the active mode and [ui] is the player's. Otherwise [ui], [chapters] and [progress] are the
 * last book's saved state ([SavedBook.toUi][app.granger.core.player.SavedBook]), shown paused: any transport control
 * first makes the audiobook the active mode, playing from there, and the per-book actions are left out.
 */
@Composable
internal fun AudiobookPlayer(
    ui: PlayerUi,
    chapters: List<AudiobookChapter>,
    bookmarks: List<AudioBookmark>,
    progress: BookProgress?,
    live: Boolean,
    viewModel: PlayerViewModel,
    onShelf: () -> Unit,
) {
    val book = ui.now as? NowPlaying.Book ?: return
    var sheet by rememberSaveable { mutableStateOf<BookSheet?>(null) }
    val back = viewModel.skipBackS()
    val forward = viewModel.skipForwardS()
    fun control(action: PlayerViewModel.() -> Unit) = if (live) viewModel.action() else viewModel.activate(PlayerMode.AUDIOBOOK, action)
    // The saved book's chapter steps go by its own list; the live one's by the player's ([PlayerViewModel.nextChapter]).
    val at = chapters.indexOfFirst { it.ordinal == book.chapter.ordinal }
    fun stepChapter(by: Int) = when {
        live -> if (by < 0) viewModel.previousChapter() else viewModel.nextChapter()
        else -> chapters.getOrNull(at + by)?.let { c -> viewModel.activate(PlayerMode.AUDIOBOOK) { seekToChapter(c.ordinal) } } ?: Unit
    }

    BoxWithConstraints(Modifier.fillMaxSize().playerInsets()) {
        val coverSize = minOf(maxWidth - 48.dp, maxHeight * 0.42f, 320.dp)
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            PlayerCover(book.book.coverPath, book = true, size = coverSize)
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    book.book.title, style = MaterialTheme.typography.titleLarge, maxLines = 2,
                    overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
                )
                if (book.book.author.isNotBlank()) {
                    Text(book.book.author, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                }
                Text(
                    "${chapterTitle(book)} · ${book.chapter.ordinal + 1} of ${book.chapterCount}",
                    style = MaterialTheme.typography.bodyLarge, color = GrangerColors.Flame, maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            SeekBar(ui.positionMs, ui.durationMs, { p -> control { seekTo(p) } }, label = "Seek in chapter")
            progress?.let { BookProgressLine(it) }
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = { stepChapter(-1) }, enabled = book.chapter.ordinal > 0) {
                    Icon(painterResource(R.drawable.ic_skip_previous), contentDescription = "Previous chapter")
                }
                IconButton(onClick = { control { skipBack() } }, modifier = Modifier.size(64.dp)) {
                    Icon(painterResource(R.drawable.ic_replay), contentDescription = "Back $back seconds", modifier = Modifier.size(40.dp))
                }
                BigPlayPause(ui.playing, { if (live) viewModel.playPause() else viewModel.activate(PlayerMode.AUDIOBOOK) })
                IconButton(onClick = { control { skipForward() } }, modifier = Modifier.size(64.dp)) {
                    Icon(painterResource(R.drawable.ic_forward), contentDescription = "Forward $forward seconds", modifier = Modifier.size(40.dp))
                }
                IconButton(onClick = { stepChapter(+1) }, enabled = book.chapter.ordinal < book.chapterCount - 1) {
                    Icon(painterResource(R.drawable.ic_skip_next), contentDescription = "Next chapter")
                }
            }
            if (live) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    PlayerAction(rememberVectorPainter(Icons.AutoMirrored.Filled.List), "Chapters", "Chapters") { sheet = BookSheet.CHAPTERS }
                    PlayerAction(painterResource(R.drawable.ic_speed), speedLabel(book.speed), "Playback speed ${speedLabel(book.speed)}") {
                        sheet = BookSheet.SPEED
                    }
                    val sleep = ui.sleepRemainingMs
                    PlayerAction(
                        painterResource(R.drawable.ic_timer),
                        if (sleep != null) formatTime(sleep) else "Sleep",
                        if (sleep != null) "Sleep timer, ${formatTime(sleep)} left" else "Sleep timer",
                        active = sleep != null,
                    ) { sheet = BookSheet.SLEEP }
                    PlayerAction(painterResource(R.drawable.ic_bookmark_border), "Bookmark", "Add bookmark", onClick = viewModel::addBookmark)
                    PlayerAction(rememberVectorPainter(Icons.Filled.Star), "Bookmarks", "Bookmarks") { sheet = BookSheet.BOOKMARKS }
                }
                ToggleRow("Skip silence", book.skipSilence, viewModel::setSkipSilence)
                ToggleRow("Volume boost", book.volumeBoostMb > 0, viewModel::setVolumeBoost)
            }
            ShelfLink(PlayerMode.AUDIOBOOK, onShelf)
            Spacer(Modifier.height(8.dp))
        }
    }

    if (!live) return
    when (sheet) {
        BookSheet.CHAPTERS -> ChaptersSheet(chapters, book.chapter.ordinal, onPick = { viewModel.seekToChapter(it); sheet = null }, onDismiss = { sheet = null })
        BookSheet.SPEED -> SpeedSheet(book.speed, viewModel::setSpeed, onDismiss = { sheet = null })
        BookSheet.SLEEP -> SleepTimerSheet(
            remainingMs = ui.sleepRemainingMs,
            onStart = { viewModel.startSleepTimer(it); sheet = null },
            onCancel = viewModel::cancelSleepTimer,
            onDismiss = { sheet = null },
        )
        BookSheet.BOOKMARKS -> AudioBookmarksSheet(
            bookmarks,
            onAdd = viewModel::addBookmark,
            onPlay = { viewModel.playBookmark(it); sheet = null },
            onDelete = viewModel::removeBookmark,
            onDismiss = { sheet = null },
        )
        null -> Unit
    }
}

internal fun speedLabel(speed: Float): String = "%.1f×".format(speed)

@Composable
private fun BookProgressLine(progress: BookProgress) {
    Column(Modifier.fillMaxWidth().testTag(PlayerTags.BOOK_PROGRESS)) {
        LinearProgressIndicator(
            progress = { fraction(progress.positionMs, progress.durationMs) },
            modifier = Modifier.fillMaxWidth().height(3.dp),
            color = GrangerColors.Tertiary,
            trackColor = GrangerColors.OutlineVariant,
            drawStopIndicator = {},
            gapSize = 0.dp,
        )
        Spacer(Modifier.height(4.dp))
        val percent = (fraction(progress.positionMs, progress.durationMs) * 100).toInt()
        Text(
            "Book: ${formatTime(progress.positionMs)} of ${formatTime(progress.durationMs)} · $percent%",
            style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** An icon button with a short label under it. */
@Composable
private fun PlayerAction(icon: Painter, label: String, description: String, active: Boolean = false, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        IconButton(onClick = onClick) {
            Icon(icon, contentDescription = description, tint = if (active) GrangerColors.Flame else LocalContentColor.current)
        }
        Text(label, style = MaterialTheme.typography.labelSmall, color = if (active) GrangerColors.Flame else MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ToggleRow(label: String, on: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().toggleable(value = on, role = Role.Switch, onValueChange = onChange).padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Switch(
            checked = on,
            onCheckedChange = null,
            colors = SwitchDefaults.colors(checkedTrackColor = GrangerColors.Flame, checkedThumbColor = GrangerColors.OnFlame),
        )
    }
}
