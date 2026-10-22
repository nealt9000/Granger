package app.granger.player

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.granger.R
import app.granger.core.player.NowPlaying
import app.granger.shell.rememberRailCover
import app.granger.theme.GrangerColors

/** What both players show at the top: the cover and two lines. */
internal data class NowInfo(val title: String, val subtitle: String, val coverPath: String?)

internal fun nowInfo(now: NowPlaying): NowInfo? = when (now) {
    is NowPlaying.Music -> NowInfo(now.track.title, now.track.artists.ifBlank { now.track.albumArtists }, now.track.coverPath)
    is NowPlaying.Book -> NowInfo(now.book.title, chapterTitle(now), now.book.coverPath)
    is NowPlaying.Voice -> NowInfo(now.title, voiceSubtitle(now), null)
    NowPlaying.Idle -> null
}

/** "Reading", with the section when the source has one; "Finished reading" once the voice ran out. */
internal fun voiceSubtitle(voice: NowPlaying.Voice): String =
    listOfNotNull(if (voice.ended) "Finished reading" else "Reading", voice.section?.takeIf { it.isNotBlank() }).joinToString(" · ")

internal fun chapterTitle(book: NowPlaying.Book): String = book.chapter.title.ifBlank { "Chapter ${book.chapter.ordinal + 1}" }

internal fun fraction(positionMs: Long, durationMs: Long): Float =
    if (durationMs <= 0L) 0f else (positionMs.toFloat() / durationMs).coerceIn(0f, 1f)

/** A square cover from [path], or a music note (headphones for a book, a page for the voice) on a plain tile when there's none. */
@Composable
internal fun PlayerCover(path: String?, book: Boolean, size: Dp, modifier: Modifier = Modifier, voice: Boolean = false) {
    val cover = rememberRailCover(path, size)
    val shape = RoundedCornerShape(if (size > 100.dp) 12.dp else 6.dp)
    Box(
        modifier.size(size).clip(shape).background(GrangerColors.SurfaceHigh),
        contentAlignment = Alignment.Center,
    ) {
        if (cover != null) {
            Image(cover, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else {
            Icon(
                painterResource(if (voice) R.drawable.ic_library else if (book) R.drawable.ic_headphones else R.drawable.ic_music_note),
                contentDescription = null,
                tint = GrangerColors.OnSurfaceVariant,
                modifier = Modifier.size(size / 2),
            )
        }
    }
}

@Composable
internal fun PlayPauseIcon(playing: Boolean, modifier: Modifier = Modifier) {
    if (playing) {
        Icon(painterResource(R.drawable.ic_pause), contentDescription = "Pause", modifier = modifier)
    } else {
        Icon(Icons.Filled.PlayArrow, contentDescription = "Play", modifier = modifier)
    }
}

/**
 * A seek bar with elapsed and total times. It follows [positionMs] until the finger is down; [onSeek] is called once,
 * on release, with the position let go at.
 */
@Composable
internal fun SeekBar(positionMs: Long, durationMs: Long, onSeek: (Long) -> Unit, modifier: Modifier = Modifier, label: String = "Seek") {
    var dragging by remember { mutableStateOf<Float?>(null) }
    val duration = durationMs.coerceAtLeast(0L)
    val shown = dragging ?: fraction(positionMs, duration)
    Column(modifier.fillMaxWidth()) {
        Slider(
            value = shown,
            onValueChange = { dragging = it },
            onValueChangeFinished = {
                dragging?.let { onSeek((it * duration).toLong()) }
                dragging = null
            },
            enabled = duration > 0L,
            colors = SliderDefaults.colors(
                thumbColor = GrangerColors.Flame,
                activeTrackColor = GrangerColors.Flame,
                inactiveTrackColor = GrangerColors.OutlineVariant,
            ),
            modifier = Modifier.fillMaxWidth().testTag(PlayerTags.SEEK).semantics { contentDescription = label },
        )
        Row(Modifier.fillMaxWidth()) {
            val style = MaterialTheme.typography.labelMedium
            Text(formatTime((shown * duration).toLong()), style = style, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.weight(1f))
            Text(formatTime(duration), style = style, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

object PlayerTags {
    const val SEEK = "player_seek"
    const val BOOK_PROGRESS = "player_book_progress"
    const val QUEUE_ROW = "queue_row"
}
