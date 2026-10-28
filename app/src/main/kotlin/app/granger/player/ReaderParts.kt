package app.granger.player

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.granger.R
import app.granger.core.player.NowPlaying
import app.granger.core.player.voice.VoiceProgress
import app.granger.core.speech.Voice
import app.granger.core.speech.VoiceId
import kotlin.math.roundToInt

// The pieces of the player's Reader tab (Plan U2 Decision 5), once the voice's sheet (Plan V2).

object ReaderTags {
    const val ROOT = "reader_tab"
    const val PREVIOUS = "reader_previous"
    const val SENTENCE = "reader_sentence"
    const val NEXT = "reader_next"
    const val PLAY_PAUSE = "reader_play_pause"
    const val NEXT_SENTENCE = "reader_next_sentence"
    const val MIX = "reader_mix"
    const val MUSIC_PLAY_PAUSE = "reader_music_play_pause"
    const val MUSIC_NEXT = "reader_music_next"
    const val VOICE_CHIP = "reader_voice"
    const val SPEED = "reader_speed"
    const val STOP = "reader_stop"
    const val OPEN = "reader_open"
    const val SLEEP = "reader_sleep"
    const val NOTHING = "reader_nothing"
    const val RESUME = "reader_resume"
}

/** [speed] snapped to the voice's 0.1 steps within 0.7–2.0×. */
fun snapVoiceSpeed(speed: Float): Float = ((speed * 10).roundToInt().coerceIn(7, 20)) / 10f

/** The sentence being read, large, between the one before and the one after, dimmed. */
@Composable
internal fun Sentences(voice: NowPlaying.Voice, progress: VoiceProgress?) {
    val current = progress?.utterance?.text?.takeIf { it.isNotBlank() } ?: voice.sentence
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            progress?.previous.orEmpty(), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.fillMaxWidth().testTag(ReaderTags.PREVIOUS),
        )
        Text(
            if (voice.ended) "Finished reading" else current, style = MaterialTheme.typography.titleLarge,
            maxLines = 6, overflow = TextOverflow.Ellipsis, modifier = Modifier.fillMaxWidth().testTag(ReaderTags.SENTENCE),
        )
        Text(
            progress?.next.orEmpty(), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.fillMaxWidth().testTag(ReaderTags.NEXT),
        )
    }
}

/** The voice's speed, 0.7× to 2.0×: a slider and the presets, applied on release. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SpeedRow(speed: Float, onSpeed: (Float) -> Unit) {
    var value by remember(speed) { mutableFloatStateOf(snapVoiceSpeed(speed)) }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Speed", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Text(speedLabel(value), style = MaterialTheme.typography.titleMedium)
    }
    Slider(
        value = value,
        onValueChange = { value = snapVoiceSpeed(it) },
        onValueChangeFinished = { onSpeed(value) },
        valueRange = PlayerViewModel.VOICE_SPEEDS.first()..PlayerViewModel.VOICE_SPEEDS.last(),
        steps = PlayerViewModel.VOICE_SPEEDS.size - 2,
        colors = SliderDefaults.colors(thumbColor = MaterialTheme.colorScheme.primary, activeTrackColor = MaterialTheme.colorScheme.primary),
        modifier = Modifier.fillMaxWidth().testTag(ReaderTags.SPEED).semantics { contentDescription = "Reading speed" },
    )
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        PlayerViewModel.VOICE_PRESETS.forEach { preset ->
            FilterChip(selected = value == preset, onClick = { onSpeed(preset) }, label = { Text(speedLabel(preset)) })
        }
    }
}

/** The installed voices as chips; the one reading is selected. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun VoiceRow(voices: List<Voice>, chosen: VoiceId, onVoice: (VoiceId) -> Unit) {
    Text("Voice", style = MaterialTheme.typography.bodyLarge)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        voices.forEach { v ->
            FilterChip(
                selected = v.id == chosen,
                onClick = { if (v.id != chosen) onVoice(v.id) },
                label = { Text(v.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                modifier = Modifier.testTag(ReaderTags.VOICE_CHIP),
            )
        }
    }
}

/** The music under the voice: its level, and the track with its own play/pause and next (spec decision 1). */
@Composable
internal fun MusicRow(music: NowPlaying.Music, playing: Boolean, mixLevel: Float, onMix: (Float) -> Unit, onPlayPause: () -> Unit, onNext: () -> Unit) {
    var level by remember(mixLevel) { mutableFloatStateOf(mixLevel.coerceIn(0f, 1f)) }
    Text("Music under the voice", style = MaterialTheme.typography.bodyLarge)
    Slider(
        value = level,
        onValueChange = { level = it; onMix(it) },
        valueRange = 0f..1f,
        colors = SliderDefaults.colors(thumbColor = MaterialTheme.colorScheme.primary, activeTrackColor = MaterialTheme.colorScheme.primary),
        modifier = Modifier.fillMaxWidth().testTag(ReaderTags.MIX).semantics { contentDescription = "Music under the voice" },
    )
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Icon(painterResource(R.drawable.ic_music_note), contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.size(12.dp))
        Column(Modifier.weight(1f)) {
            Text(music.track.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
            Text(
                music.track.artists.ifBlank { music.track.albumArtists }, maxLines = 1, overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(onClick = onPlayPause, modifier = Modifier.testTag(ReaderTags.MUSIC_PLAY_PAUSE)) {
            Icon(painterResource(if (playing) R.drawable.ic_pause else R.drawable.ic_play), contentDescription = if (playing) "Pause music" else "Play music")
        }
        IconButton(onClick = onNext, modifier = Modifier.testTag(ReaderTags.MUSIC_NEXT)) {
            Icon(painterResource(R.drawable.ic_skip_next), contentDescription = "Next track")
        }
    }
    Spacer(Modifier.height(0.dp))
}
