package app.granger.player

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.granger.core.library.Track
import app.granger.core.player.NowPlaying
import app.granger.core.player.PlayerMode
import app.granger.core.player.PlayerSettingsKeys
import app.granger.core.player.PlayerUi
import app.granger.core.player.SavedModes
import app.granger.core.player.SavedVoice
import app.granger.core.speech.Voice

/**
 * [ListenPlayerRoute][app.granger.ListenPlayerRoute], the full player (Plan U2 Decision 4): two tabs, [Reader] for
 * the voice and the media tab, Music or Audiobook ([mediaMode]: the live one, else the last played). It opens on
 * what's live (or the tab last used). The media tab is the live player when its mode is the active one; otherwise
 * it shows that mode's saved state, paused, until one of its controls makes it the active mode (spec §8). Switching
 * tabs plays, stops and saves nothing. The media tab ends with a link to its shelf ([onShelf]). Reader picks from
 * [voices], starts the music under the voice at [mixLevel], and goes to the app for [onOpenSource] and [onResume].
 */
@Composable
fun ListenPlayerScreen(
    viewModel: PlayerViewModel,
    onClose: () -> Unit,
    onShelf: (PlayerMode) -> Unit = {},
    voices: List<Voice> = emptyList(),
    mixLevel: Float = PlayerSettingsKeys.DEFAULT_MIX,
    onOpenSource: (NowPlaying.Voice) -> Unit = {},
    onResume: (SavedVoice) -> Unit = {},
) {
    // Opening the player (not a rotation, which keeps the tab) shows what's live.
    rememberSaveable { viewModel.resetTab(); true }
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val queue by viewModel.queue.collectAsStateWithLifecycle()
    val saved by viewModel.saved.collectAsStateWithLifecycle()
    val picked by viewModel.pickedTab.collectAsStateWithLifecycle()
    val audiobooks by viewModel.hasAudiobooks.collectAsStateWithLifecycle()
    val tab = listenTab(picked, ui.now, saved)
    val media = mediaMode(ui.now, saved, audiobooks)
    val live = modeOf(ui.now) == media
    // A Surface, like the Scaffold every other screen sits in: it provides onBackground as LocalContentColor, without
    // which every untinted Icon and uncoloured Text here falls back to black on the dark background.
    Surface(
        Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
        contentColor = MaterialTheme.colorScheme.onBackground,
    ) {
        Column(Modifier.fillMaxSize()) {
            PlayerTopBar(topLabel(tab, media, live, ui.now, saved), onClose)
            ListenTabs(tab, media, viewModel::selectTab)
            Box(Modifier.fillMaxWidth().weight(1f)) {
                when (tab) {
                    ListenTab.READER -> {
                        val progress by viewModel.voiceProgress.collectAsStateWithLifecycle()
                        val controls = remember(viewModel, onOpenSource, onResume) { viewModel.readerControls(onOpenSource, onResume) }
                        Reader(ui, progress, saved, voices, mixLevel, controls)
                    }
                    ListenTab.MEDIA -> MediaTab(media, live, ui, queue, saved, viewModel, onShelf)
                }
            }
        }
    }
}

/** The media tab's player: [mode]'s live player, or its saved state, paused, or that nothing was played yet. */
@Composable
private fun MediaTab(mode: PlayerMode, live: Boolean, ui: PlayerUi, queue: List<Track>, saved: SavedModes, viewModel: PlayerViewModel, onShelf: (PlayerMode) -> Unit) {
    when (mode) {
        PlayerMode.MUSIC -> {
            val music = saved.music
            val under = (ui.now as? NowPlaying.Voice)?.takeIf { it.music != null }
            when {
                live -> MusicPlayer(ui, queue, live = true, viewModel) { onShelf(PlayerMode.MUSIC) }
                // Music under the voice (Plan V2): shown live with its own transport (the view model routes it there).
                under != null -> MusicPlayer(under.musicUi(ui), queue, live = true, viewModel) { onShelf(PlayerMode.MUSIC) }
                music != null -> MusicPlayer(music.toUi(), music.queue, live = false, viewModel) { onShelf(PlayerMode.MUSIC) }
                saved.loaded -> NothingPlayed(PlayerMode.MUSIC) { onShelf(PlayerMode.MUSIC) }
            }
        }
        PlayerMode.AUDIOBOOK -> {
            val book = saved.book
            when {
                live -> {
                    val chapters by viewModel.bookChapters.collectAsStateWithLifecycle()
                    val bookmarks by viewModel.bookBookmarks.collectAsStateWithLifecycle()
                    val progress by viewModel.progress.collectAsStateWithLifecycle()
                    AudiobookPlayer(ui, chapters, bookmarks, progress, live = true, viewModel) { onShelf(PlayerMode.AUDIOBOOK) }
                }
                book != null -> AudiobookPlayer(book.toUi(), book.chapters, emptyList(), book.progress(), live = false, viewModel) {
                    onShelf(PlayerMode.AUDIOBOOK)
                }
                saved.loaded -> NothingPlayed(PlayerMode.AUDIOBOOK) { onShelf(PlayerMode.AUDIOBOOK) }
            }
        }
    }
}

/** The music under the voice as the Music tab shows it: its track, whether it is heard, its place in the track. */
internal fun NowPlaying.Voice.musicUi(ui: PlayerUi): PlayerUi =
    PlayerUi(music!!, playing = musicPlaying, positionMs = musicPositionMs, durationMs = musicDurationMs, sleepRemainingMs = ui.sleepRemainingMs)

private fun topLabel(tab: ListenTab, media: PlayerMode, live: Boolean, now: NowPlaying, saved: SavedModes): String = when {
    tab == ListenTab.READER -> when {
        now is NowPlaying.Voice -> if (now.ended) "Read aloud · finished" else "Reading aloud"
        else -> "Read aloud"
    }
    media == PlayerMode.MUSIC -> when {
        live && now is NowPlaying.Music -> "Now playing · ${now.index + 1} of ${now.queueSize}"
        now is NowPlaying.Voice && now.music != null -> now.music.let { m -> "Under the voice · ${m!!.index + 1} of ${m.queueSize}" }
        saved.music != null -> saved.music!!.let { "Paused · ${it.index + 1} of ${it.queue.size}" }
        else -> "Music"
    }
    else -> when {
        live -> "Audiobook"
        saved.book != null -> "Audiobook · paused"
        else -> "Audiobooks"
    }
}

object ListenTabTags {
    const val READER = "listen_tab_reader"
    const val MEDIA = "listen_tab_media"
}

/** Reader | Music (or Audiobook). Picking one only changes what is shown. */
@Composable
private fun ListenTabs(tab: ListenTab, media: PlayerMode, onSelect: (ListenTab) -> Unit) {
    PrimaryTabRow(selectedTabIndex = tab.ordinal, containerColor = MaterialTheme.colorScheme.background) {
        for (t in ListenTab.entries) {
            Tab(
                selected = tab == t, onClick = { onSelect(t) }, text = { Text(tabTitle(t, media)) },
                modifier = Modifier.testTag(if (t == ListenTab.READER) ListenTabTags.READER else ListenTabTags.MEDIA),
            )
        }
    }
}

internal fun tabTitle(tab: ListenTab, media: PlayerMode): String = when (tab) {
    ListenTab.READER -> "Reader"
    ListenTab.MEDIA -> when (media) {
        PlayerMode.MUSIC -> "Music"
        PlayerMode.AUDIOBOOK -> "Audiobook"
    }
}

/** A mode with no saved state, and the way to its shelf. */
@Composable
private fun NothingPlayed(mode: PlayerMode, onShelf: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("Nothing played yet", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        ShelfLink(mode, onShelf)
    }
}

/** The link at the bottom of each player to its shelf: All music, or all audiobooks. */
@Composable
internal fun ShelfLink(mode: PlayerMode, onClick: () -> Unit) {
    TextButton(onClick = onClick) {
        Text(shelfLabel(mode))
        Spacer(Modifier.size(4.dp))
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
    }
}

internal fun shelfLabel(mode: PlayerMode): String = when (mode) {
    PlayerMode.MUSIC -> "Music shelf"
    PlayerMode.AUDIOBOOK -> "Audiobooks shelf"
}

/** The player's bar: close (back to the mini-player) and a small label. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PlayerTopBar(label: String, onClose: () -> Unit) {
    TopAppBar(
        title = { Text(label, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) },
        navigationIcon = {
            IconButton(onClick = onClose) { Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "Close player") }
        },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
    )
}

/** The big accent play/pause button both players centre their controls on. */
@Composable
internal fun BigPlayPause(playing: Boolean, onClick: () -> Unit, size: Dp = 72.dp) {
    FilledIconButton(
        onClick = onClick,
        modifier = Modifier.size(size),
        shape = CircleShape,
        colors = IconButtonDefaults.filledIconButtonColors(containerColor = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary),
    ) {
        PlayPauseIcon(playing, Modifier.size(size / 2))
    }
}

/** Keeps the players' content clear of the navigation bar and an end-side cutout. */
@Composable
internal fun Modifier.playerInsets(): Modifier = windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.End))
