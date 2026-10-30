package app.granger.player

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.StartOffset
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.granger.R
import app.granger.UiText
import app.granger.core.player.NowPlaying
import app.granger.shell.rememberRailCover
import app.granger.theme.MediaKind
import app.granger.theme.MediaTile
import app.granger.theme.readout

/** What both players show at the top: the cover and two lines. */
internal data class NowInfo(val title: String, val subtitle: String, val coverPath: String?)

@Composable
internal fun nowInfo(now: NowPlaying): NowInfo? = when (now) {
    is NowPlaying.Music -> NowInfo(now.track.title, now.track.artists.ifBlank { now.track.albumArtists }, now.track.coverPath)
    is NowPlaying.Book -> NowInfo(now.book.title, chapterName(now).resolve(), now.book.coverPath)
    is NowPlaying.Voice -> NowInfo(now.title, voiceState(now).resolve(), null)
    NowPlaying.Idle -> null
}

/** "Reading", with the section when the source has one; "Finished reading" once the voice ran out. */
internal fun voiceState(voice: NowPlaying.Voice): UiText = voiceState(voice.section, voice.ended)

internal fun voiceState(section: String?, ended: Boolean): UiText {
    val named = section?.takeIf { it.isNotBlank() }
    return when {
        named == null -> UiText.Res(if (ended) R.string.player_finished_reading else R.string.player_reading)
        else -> UiText.Res(if (ended) R.string.player_finished_reading_section else R.string.player_reading_section, named)
    }
}

/** A chapter's own title, or "Chapter 3" when it has none ([ordinal] counts from 0). */
internal fun chapterName(title: String, ordinal: Int): UiText =
    if (title.isBlank()) UiText.Res(R.string.player_chapter_number, ordinal + 1) else UiText.Raw(title)

internal fun chapterName(book: NowPlaying.Book): UiText = chapterName(book.chapter.title, book.chapter.ordinal)

internal fun fraction(positionMs: Long, durationMs: Long): Float =
    if (durationMs <= 0L) 0f else (positionMs.toFloat() / durationMs).coerceIn(0f, 1f)

/**
 * A square cover from [path], or the themed tile (Plan U3c Decision 6) with [title] and a music note (headphones for a
 * book, a speaker for the voice) when there's none.
 */
@Composable
internal fun PlayerCover(path: String?, title: String, book: Boolean, size: Dp, modifier: Modifier = Modifier, voice: Boolean = false) {
    val cover = rememberRailCover(path, size)
    val shape = RoundedCornerShape(if (size > 100.dp) 12.dp else 6.dp)
    Box(modifier.size(size).clip(shape), contentAlignment = Alignment.Center) {
        if (cover != null) {
            Image(cover, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else {
            MediaTile(title, if (voice) MediaKind.VOICE else if (book) MediaKind.AUDIOBOOK else MediaKind.MUSIC)
        }
    }
}

@Composable
internal fun PlayPauseIcon(playing: Boolean, modifier: Modifier = Modifier) {
    if (playing) {
        Icon(painterResource(R.drawable.ic_pause), contentDescription = stringResource(R.string.common_pause), modifier = modifier)
    } else {
        Icon(Icons.Filled.PlayArrow, contentDescription = stringResource(R.string.common_play), modifier = modifier)
    }
}

/**
 * A seek bar with elapsed and total times. It follows [positionMs] until the finger is down; [onSeek] is called once,
 * on release, with the position let go at.
 */
@Composable
internal fun SeekBar(positionMs: Long, durationMs: Long, onSeek: (Long) -> Unit, modifier: Modifier = Modifier, label: String = stringResource(R.string.player_seek)) {
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
                thumbColor = MaterialTheme.colorScheme.primary,
                activeTrackColor = MaterialTheme.colorScheme.primary,
                inactiveTrackColor = MaterialTheme.colorScheme.outlineVariant,
            ),
            modifier = Modifier.fillMaxWidth().testTag(PlayerTags.SEEK).semantics { contentDescription = label },
        )
        Row(Modifier.fillMaxWidth()) {
            val style = MaterialTheme.typography.labelMedium.readout
            Text(formatTime((shown * duration).toLong()), style = style, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.weight(1f))
            Text(formatTime(duration), style = style, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Each bar's half-cycle and its start offset (ms): out of step, so the three never move as one. */
private val BAR_TIMINGS = listOf(420 to 0, 560 to 180, 480 to 90)

/** The bars' heights while paused: a still equalizer. */
private val STILL_BARS = listOf(0.45f, 0.85f, 0.6f)

/**
 * The playing mark (Plan U2 Decision 8): an equalizer, its three bars rising and falling while [heard], still while
 * paused. The album's track list and the queue sheet put it on the current song.
 */
@Composable
fun PlayingBars(heard: Boolean, modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.primary) {
    val heights = if (heard) {
        val transition = rememberInfiniteTransition(label = "playing")
        BAR_TIMINGS.map { (ms, offset) ->
            transition.animateFloat(
                initialValue = 0.25f, targetValue = 1f,
                animationSpec = infiniteRepeatable(tween(ms, easing = LinearEasing), RepeatMode.Reverse, StartOffset(offset)),
                label = "bar",
            )
        }.map { it.value }
    } else {
        STILL_BARS
    }
    val state = stringResource(if (heard) R.string.player_playing else R.string.player_paused)
    Canvas(
        modifier.size(16.dp).testTag(PlayerTags.PLAYING).semantics { contentDescription = state },
    ) {
        val bar = size.width / 5
        heights.forEachIndexed { i, h ->
            val height = size.height * h
            drawRect(color, topLeft = Offset(bar * (i * 2), size.height - height), size = Size(bar, height))
        }
    }
}

object PlayerTags {
    const val SEEK = "player_seek"
    const val BOOK_PROGRESS = "player_book_progress"
    const val QUEUE_ROW = "queue_row"
    const val PLAYING = "player_playing_bars"
}
