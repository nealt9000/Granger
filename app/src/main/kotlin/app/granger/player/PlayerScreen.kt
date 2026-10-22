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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.granger.core.player.NowPlaying
import app.granger.core.player.PlayerMode
import app.granger.core.player.PlayerUi
import app.granger.core.player.SavedModes
import app.granger.theme.GrangerColors

/**
 * [PlayerRoute][app.granger.PlayerRoute]: a Music and an Audiobooks tab (spec §8: one player, one active mode, each
 * mode with its own saved state). It opens on the active mode (or the last one used). The active mode's tab is the
 * live player; the other shows that mode's saved state, paused, until one of its controls makes it the active mode.
 * Switching tabs plays, stops and saves nothing. Each tab ends with a link to its shelf ([onShelf]).
 */
@Composable
fun PlayerScreen(viewModel: PlayerViewModel, onClose: () -> Unit, onShelf: (PlayerMode) -> Unit = {}) {
    // Opening the player (not a rotation, which keeps the tab) shows the active mode.
    rememberSaveable { viewModel.resetTab(); true }
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val queue by viewModel.queue.collectAsStateWithLifecycle()
    val saved by viewModel.saved.collectAsStateWithLifecycle()
    val picked by viewModel.pickedTab.collectAsStateWithLifecycle()
    val tab = playerTab(picked, ui.now, saved.lastMode)
    val live = modeOf(ui.now) == tab
    // A Surface, like the Scaffold every other screen sits in: it provides onBackground as LocalContentColor, without
    // which every untinted Icon and uncoloured Text here falls back to black on the dark background.
    Surface(
        Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
        contentColor = MaterialTheme.colorScheme.onBackground,
    ) {
        Column(Modifier.fillMaxSize()) {
            PlayerTopBar(topLabel(tab, live, ui.now, saved), onClose)
            PlayerTabs(tab, viewModel::selectTab)
            Box(Modifier.fillMaxWidth().weight(1f)) {
                when (tab) {
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
        }
    }
}

/** The music under the voice as the Music tab shows it: its track, whether it is heard, its place in the track. */
internal fun NowPlaying.Voice.musicUi(ui: PlayerUi): PlayerUi =
    PlayerUi(music!!, playing = musicPlaying, positionMs = musicPositionMs, durationMs = musicDurationMs, sleepRemainingMs = ui.sleepRemainingMs)

private fun topLabel(tab: PlayerMode, live: Boolean, now: NowPlaying, saved: SavedModes): String = when (tab) {
    PlayerMode.MUSIC -> when {
        live && now is NowPlaying.Music -> "Now playing · ${now.index + 1} of ${now.queueSize}"
        now is NowPlaying.Voice && now.music != null -> now.music.let { m -> "Under the voice · ${m!!.index + 1} of ${m.queueSize}" }
        saved.music != null -> saved.music!!.let { "Paused · ${it.index + 1} of ${it.queue.size}" }
        else -> "Music"
    }
    PlayerMode.AUDIOBOOK -> when {
        live -> "Audiobook"
        saved.book != null -> "Audiobook · paused"
        else -> "Audiobooks"
    }
}

/** Music | Audiobooks. Picking one only changes what is shown. */
@Composable
private fun PlayerTabs(tab: PlayerMode, onSelect: (PlayerMode) -> Unit) {
    PrimaryTabRow(selectedTabIndex = tab.ordinal, containerColor = MaterialTheme.colorScheme.background) {
        for (mode in PlayerMode.entries) {
            Tab(selected = tab == mode, onClick = { onSelect(mode) }, text = { Text(tabTitle(mode)) })
        }
    }
}

internal fun tabTitle(mode: PlayerMode): String = when (mode) {
    PlayerMode.MUSIC -> "Music"
    PlayerMode.AUDIOBOOK -> "Audiobooks"
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

/** Both players' bar: close (back to the mini-player) and a small label. */
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

/** The big flame play/pause button both players centre their controls on. */
@Composable
internal fun BigPlayPause(playing: Boolean, onClick: () -> Unit, size: Dp = 72.dp) {
    FilledIconButton(
        onClick = onClick,
        modifier = Modifier.size(size),
        shape = CircleShape,
        colors = IconButtonDefaults.filledIconButtonColors(containerColor = GrangerColors.Flame, contentColor = GrangerColors.OnFlame),
    ) {
        PlayPauseIcon(playing, Modifier.size(size / 2))
    }
}

/** Keeps the players' content clear of the navigation bar and an end-side cutout. */
@Composable
internal fun Modifier.playerInsets(): Modifier = windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.End))
