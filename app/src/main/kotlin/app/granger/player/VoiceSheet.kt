package app.granger.player

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
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
import app.granger.core.player.PlayerUi
import app.granger.core.player.SleepSetting
import app.granger.core.player.voice.VoiceProgress
import app.granger.core.speech.Voice
import app.granger.core.speech.VoiceId
import kotlin.math.roundToInt

object VoiceSheetTags {
    const val ROOT = "voice_sheet"
    const val PREVIOUS = "voice_sheet_previous"
    const val SENTENCE = "voice_sheet_sentence"
    const val NEXT = "voice_sheet_next"
    const val PLAY_PAUSE = "voice_sheet_play_pause"
    const val MIX = "voice_sheet_mix"
    const val MUSIC_PLAY_PAUSE = "voice_sheet_music_play_pause"
    const val MUSIC_NEXT = "voice_sheet_music_next"
    const val VOICE_CHIP = "voice_sheet_voice"
    const val SPEED = "voice_sheet_speed"
    const val STOP = "voice_sheet_stop"
    const val OPEN = "voice_sheet_open"
    const val SLEEP = "voice_sheet_sleep"
}

/** [speed] snapped to the voice's 0.1 steps within 0.7–2.0×. */
fun snapVoiceSpeed(speed: Float): Float = ((speed * 10).roundToInt().coerceIn(7, 20)) / 10f

/**
 * The voice's full view (spec §6, Plan V2): a sheet over whatever is on screen, not a Player tab. The sentence being
 * read in large type with the ones around it dimmed; previous and next paragraph, back a sentence, play/pause; the
 * speed and the voice; the music under the voice with its level and its own play/pause and next, when music plays;
 * the sleep timer; Stop reading; and Open, to the page being read. Nothing is drawn unless [ui] is the voice.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VoiceSheet(
    ui: PlayerUi,
    progress: VoiceProgress?,
    voices: List<Voice>,
    mixLevel: Float,
    onPlayPause: () -> Unit,
    onPreviousParagraph: () -> Unit,
    onNextParagraph: () -> Unit,
    onBackSentence: () -> Unit,
    onSpeed: (Float) -> Unit,
    onVoice: (VoiceId) -> Unit,
    onMix: (Float) -> Unit,
    onPlayPauseMusic: () -> Unit,
    onNextMusic: () -> Unit,
    onSleep: (SleepSetting) -> Unit,
    onCancelSleep: () -> Unit,
    onStop: () -> Unit,
    onOpen: () -> Unit,
    onDismiss: () -> Unit,
) {
    val voice = ui.now as? NowPlaying.Voice ?: return
    var sleepSheet by remember { mutableStateOf(false) }
    // Opens fully: it is the voice's whole view, and half of it would hide the music row and the buttons.
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier.navigationBarsPadding().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = 16.dp).testTag(VoiceSheetTags.ROOT),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                listOfNotNull(voice.title, voice.section?.takeIf { it.isNotBlank() }).joinToString(" · "),
                style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
            Sentences(voice, progress)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onPreviousParagraph) { Icon(painterResource(R.drawable.ic_skip_previous), contentDescription = "Previous paragraph") }
                IconButton(onClick = onBackSentence) { Icon(painterResource(R.drawable.ic_replay), contentDescription = "Back a sentence") }
                FilledIconButton(
                    onClick = onPlayPause,
                    modifier = Modifier.size(64.dp).testTag(VoiceSheetTags.PLAY_PAUSE),
                    colors = IconButtonDefaults.filledIconButtonColors(containerColor = MaterialTheme.colorScheme.primary),
                ) { PlayPauseIcon(ui.playing) }
                IconButton(onClick = onNextParagraph) { Icon(painterResource(R.drawable.ic_skip_next), contentDescription = "Next paragraph") }
            }
            HorizontalDivider()
            SpeedRow(voice.speed, onSpeed)
            if (voices.isNotEmpty()) VoiceRow(voices, voice.voiceId, onVoice)
            voice.music?.let { music ->
                HorizontalDivider()
                MusicRow(music, voice.musicPlaying, mixLevel, onMix, onPlayPauseMusic, onNextMusic)
            }
            HorizontalDivider()
            Row(
                Modifier.fillMaxWidth().clickable { sleepSheet = true }.padding(vertical = 8.dp).testTag(VoiceSheetTags.SLEEP),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(painterResource(R.drawable.ic_timer), contentDescription = null)
                Spacer(Modifier.size(12.dp))
                Text("Sleep timer", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                Text(
                    ui.sleepRemainingMs?.let { "Stops in ${formatTime(it)}" } ?: "Off",
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (ui.sleepRemainingMs != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = onStop, modifier = Modifier.weight(1f).testTag(VoiceSheetTags.STOP)) { Text("Stop reading") }
                Button(onClick = onOpen, modifier = Modifier.weight(1f).testTag(VoiceSheetTags.OPEN)) { Text("Open") }
            }
        }
    }
    if (sleepSheet) {
        SleepTimerSheet(
            remainingMs = ui.sleepRemainingMs,
            onStart = { onSleep(it); sleepSheet = false },
            onCancel = onCancelSleep,
            onDismiss = { sleepSheet = false },
        )
    }
}

/** The sentence being read, large, between the one before and the one after, dimmed. */
@Composable
private fun Sentences(voice: NowPlaying.Voice, progress: VoiceProgress?) {
    val current = progress?.utterance?.text?.takeIf { it.isNotBlank() } ?: voice.sentence
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            progress?.previous.orEmpty(), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.fillMaxWidth().testTag(VoiceSheetTags.PREVIOUS),
        )
        Text(
            if (voice.ended) "Finished reading" else current, style = MaterialTheme.typography.titleLarge,
            maxLines = 6, overflow = TextOverflow.Ellipsis, modifier = Modifier.fillMaxWidth().testTag(VoiceSheetTags.SENTENCE),
        )
        Text(
            progress?.next.orEmpty(), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.fillMaxWidth().testTag(VoiceSheetTags.NEXT),
        )
    }
}

/** The voice's speed, 0.7× to 2.0×: a slider and the presets, applied on release. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SpeedRow(speed: Float, onSpeed: (Float) -> Unit) {
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
        modifier = Modifier.fillMaxWidth().testTag(VoiceSheetTags.SPEED).semantics { contentDescription = "Reading speed" },
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
private fun VoiceRow(voices: List<Voice>, chosen: VoiceId, onVoice: (VoiceId) -> Unit) {
    Text("Voice", style = MaterialTheme.typography.bodyLarge)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        voices.forEach { v ->
            FilterChip(
                selected = v.id == chosen,
                onClick = { if (v.id != chosen) onVoice(v.id) },
                label = { Text(v.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                modifier = Modifier.testTag(VoiceSheetTags.VOICE_CHIP),
            )
        }
    }
}

/** The music under the voice: its level, and the track with its own play/pause and next (spec decision 1). */
@Composable
private fun MusicRow(music: NowPlaying.Music, playing: Boolean, mixLevel: Float, onMix: (Float) -> Unit, onPlayPause: () -> Unit, onNext: () -> Unit) {
    var level by remember(mixLevel) { mutableFloatStateOf(mixLevel.coerceIn(0f, 1f)) }
    Text("Music under the voice", style = MaterialTheme.typography.bodyLarge)
    Slider(
        value = level,
        onValueChange = { level = it; onMix(it) },
        valueRange = 0f..1f,
        colors = SliderDefaults.colors(thumbColor = MaterialTheme.colorScheme.primary, activeTrackColor = MaterialTheme.colorScheme.primary),
        modifier = Modifier.fillMaxWidth().testTag(VoiceSheetTags.MIX).semantics { contentDescription = "Music under the voice" },
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
        IconButton(onClick = onPlayPause, modifier = Modifier.testTag(VoiceSheetTags.MUSIC_PLAY_PAUSE)) {
            Icon(painterResource(if (playing) R.drawable.ic_pause else R.drawable.ic_play), contentDescription = if (playing) "Pause music" else "Play music")
        }
        IconButton(onClick = onNext, modifier = Modifier.testTag(VoiceSheetTags.MUSIC_NEXT)) {
            Icon(painterResource(R.drawable.ic_skip_next), contentDescription = "Next track")
        }
    }
    Spacer(Modifier.height(0.dp))
}
