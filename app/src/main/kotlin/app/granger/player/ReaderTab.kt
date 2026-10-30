package app.granger.player

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material3.Button
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.granger.R
import app.granger.core.player.NowPlaying
import app.granger.core.player.PlayerUi
import app.granger.core.player.SavedModes
import app.granger.core.player.SavedVoice
import app.granger.core.player.SleepSetting
import app.granger.core.player.VoiceChapters
import app.granger.core.player.voice.VoiceProgress
import app.granger.core.speech.Voice
import app.granger.core.speech.VoiceId

/** Reader's buttons: the transport and settings go to the hub, [onOpen] and [onResume] to the app. */
data class ReaderControls(
    val onPlayPause: () -> Unit = {},
    val onPreviousParagraph: () -> Unit = {},
    val onBackSentence: () -> Unit = {},
    val onNextSentence: () -> Unit = {},
    val onNextParagraph: () -> Unit = {},
    val onSpeed: (Float) -> Unit = {},
    val onVoice: (VoiceId) -> Unit = {},
    val onMix: (Float) -> Unit = {},
    val onPlayPauseMusic: () -> Unit = {},
    val onNextMusic: () -> Unit = {},
    /** The text's chapters and the current one, read from the voice's source; null when there are none to show. */
    val chapters: suspend () -> VoiceChapters? = { null },
    val onChapter: (Int) -> Unit = {},
    val onSleep: (SleepSetting) -> Unit = {},
    val onCancelSleep: () -> Unit = {},
    val onStop: () -> Unit = {},
    /** The page being read, in its reader at the sentence. */
    val onOpen: (NowPlaying.Voice) -> Unit = {},
    /** The last thing read, from its saved place. */
    val onResume: (SavedVoice) -> Unit = {},
)

/** [Reader]'s controls through [PlayerViewModel], with the app's [onOpen] and [onResume]. */
fun PlayerViewModel.readerControls(onOpen: (NowPlaying.Voice) -> Unit, onResume: (SavedVoice) -> Unit) = ReaderControls(
    onPlayPause = ::playPause, onPreviousParagraph = ::previousParagraph, onBackSentence = ::previousSentence,
    onNextSentence = ::nextSentence, onNextParagraph = ::nextParagraph, onSpeed = ::setSpeed, onVoice = ::setVoice,
    onMix = ::setMixLevel, onPlayPauseMusic = ::playPauseMusic, onNextMusic = ::nextMusic,
    chapters = ::voiceChapters, onChapter = ::jumpToChapter,
    onSleep = ::startSleepTimer, onCancelSleep = ::cancelSleepTimer, onStop = ::stopVoice, onOpen = onOpen, onResume = onResume,
)

/**
 * The player's Reader tab (Plan U2 Decision 5), the voice's full player: the title and section; the sentence being
 * read in large type with the ones around it dimmed; previous paragraph, back a sentence, play/pause, next sentence,
 * next paragraph, with Stop and Open (to the page being read) under them; the text's chapters (a book's contents, an
 * article's sections); the speed and the voice (a dropdown); the music under the voice with its level and its own
 * play/pause and next, when music plays; and the sleep timer. With the voice not loaded it offers to
 * resume the last thing read ([SavedModes.voice]) and says how to start one.
 */
@Composable
fun Reader(ui: PlayerUi, progress: VoiceProgress?, saved: SavedModes, voices: List<Voice>, mixLevel: Float, controls: ReaderControls) {
    val voice = ui.now as? NowPlaying.Voice
    if (voice == null) {
        // Until the db is read there's nothing to say: no flash of "nothing read" before the Resume.
        if (saved.loaded) NothingRead(saved.voice, controls.onResume)
        return
    }
    var sleepSheet by remember { mutableStateOf(false) }
    var chaptersSheet by remember { mutableStateOf(false) }
    // Re-read as the section changes, so the row and the sheet's mark follow the voice.
    var chapters by remember(voice.sourceKey) { mutableStateOf<VoiceChapters?>(null) }
    LaunchedEffect(voice.sourceKey, voice.section) { chapters = controls.chapters() }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).playerInsets().padding(horizontal = 24.dp).padding(bottom = 16.dp)
            .testTag(ReaderTags.ROOT),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            listOfNotNull(voice.title, voice.section?.takeIf { it.isNotBlank() }).joinToString(" · "),
            style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp),
        )
        Sentences(voice, progress)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = controls.onPreviousParagraph) { Icon(painterResource(R.drawable.ic_skip_previous), contentDescription = "Previous paragraph") }
            IconButton(onClick = controls.onBackSentence) { Icon(painterResource(R.drawable.ic_replay), contentDescription = "Back a sentence") }
            FilledIconButton(
                onClick = controls.onPlayPause,
                modifier = Modifier.size(64.dp).testTag(ReaderTags.PLAY_PAUSE),
                colors = IconButtonDefaults.filledIconButtonColors(containerColor = MaterialTheme.colorScheme.primary),
            ) { PlayPauseIcon(ui.playing) }
            IconButton(onClick = controls.onNextSentence, modifier = Modifier.testTag(ReaderTags.NEXT_SENTENCE)) {
                Icon(painterResource(R.drawable.ic_forward), contentDescription = "Next sentence")
            }
            IconButton(onClick = controls.onNextParagraph) { Icon(painterResource(R.drawable.ic_skip_next), contentDescription = "Next paragraph") }
        }
        // Stop and Open sit with the transport, not under the settings below.
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = controls.onStop, modifier = Modifier.weight(1f).testTag(ReaderTags.STOP)) { Text("Stop") }
            Button(onClick = { controls.onOpen(voice) }, modifier = Modifier.weight(1f).testTag(ReaderTags.OPEN)) { Text("Open") }
        }
        chapters?.takeIf { it.chapters.isNotEmpty() }?.let { c ->
            HorizontalDivider()
            Row(
                Modifier.fillMaxWidth().clickable { chaptersSheet = true }.padding(vertical = 8.dp).testTag(ReaderTags.CHAPTERS),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.AutoMirrored.Filled.List, contentDescription = null)
                Spacer(Modifier.size(12.dp))
                Text("Chapters", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                c.current?.let { i -> c.chapters.getOrNull(i)?.let { i to it } }?.let { (i, here) ->
                    Text(
                        here.title.ifBlank { "Chapter ${i + 1}" }, style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                }
            }
        }
        HorizontalDivider()
        SpeedRow(voice.speed, controls.onSpeed)
        if (voices.isNotEmpty()) VoiceRow(voices, voice.voiceId, controls.onVoice)
        voice.music?.let { music ->
            HorizontalDivider()
            MusicRow(music, voice.musicPlaying, mixLevel, controls.onMix, controls.onPlayPauseMusic, controls.onNextMusic)
        }
        HorizontalDivider()
        Row(
            Modifier.fillMaxWidth().clickable { sleepSheet = true }.padding(vertical = 8.dp).testTag(ReaderTags.SLEEP),
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
    }
    val shown = chapters
    if (chaptersSheet && shown != null) {
        ReadChaptersSheet(
            shown.chapters, shown.current,
            onPick = { controls.onChapter(it); chaptersSheet = false },
            onDismiss = { chaptersSheet = false },
        )
    }
    if (sleepSheet) {
        SleepTimerSheet(
            remainingMs = ui.sleepRemainingMs,
            onStart = { controls.onSleep(it); sleepSheet = false },
            onCancel = controls.onCancelSleep,
            onDismiss = { sleepSheet = false },
        )
    }
}

/** The voice isn't loaded: Resume the last thing read, where it stopped, and how to start reading something else. */
@Composable
private fun NothingRead(last: SavedVoice?, onResume: (SavedVoice) -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(24.dp).testTag(ReaderTags.NOTHING),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
    ) {
        if (last != null) {
            Button(onClick = { onResume(last) }, modifier = Modifier.testTag(ReaderTags.RESUME)) {
                Icon(painterResource(R.drawable.ic_headphones), contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.size(8.dp))
                Text(resumeLabel(last), maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        Text(
            "Open a book or article and tap Listen",
            style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center,
        )
    }
}

/** "Resume Peter Pan · Chapter 3": the last thing read and its section, when it has one. */
internal fun resumeLabel(last: SavedVoice): String =
    "Resume " + listOfNotNull(last.title, last.section?.takeIf { it.isNotBlank() }).joinToString(" · ")
