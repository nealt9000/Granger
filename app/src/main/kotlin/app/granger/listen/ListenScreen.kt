package app.granger.listen

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.granger.library.kindRows
import app.granger.player.PlayPauseIcon
import app.granger.player.PlayerCover
import app.granger.sources.GetMoreLink

/** What Listen's home can open; the NavHost wires each to its route. */
class ListenActions(
    /** The full player, on what the card shows: Reader for the voice, else Music or Audiobook. */
    val onOpenPlayer: () -> Unit = {},
    /** The card's play/pause: the live item, or what the card says would resume. */
    val onPlayPause: () -> Unit = {},
    /** A Music collection, or "All music" (null). */
    val onOpenMusic: (collectionId: String?) -> Unit = {},
    /** An Audiobooks collection's shelf, or "All audiobooks" (null). */
    val onOpenAudiobooks: (collectionId: String?) -> Unit = {},
    /** A folder of collections. */
    val onOpenCollection: (collectionId: String) -> Unit = {},
    val onOpenSource: (GetMoreLink) -> Unit = {},
    /** Settings → Music & audiobooks: the media permission, folders and Rescan now. */
    val onFindOnPhone: () -> Unit = {},
    val onGetMore: () -> Unit = {},
)

/** The audio sources an empty Listen links to: Get more's own rows (Plan U2 Decision 4). Podcasts join them once they exist. */
internal val AUDIO_SOURCES = listOf(GetMoreLink.LIBRIVOX, GetMoreLink.ARCHIVE_ORG_AUDIOBOOKS, GetMoreLink.ARCHIVE_ORG_MUSIC)

const val FIND_ON_PHONE = "Find music on this phone"
const val NOW_PLAYING_TAG = "listenNowPlaying"

@Composable
fun ListenScreen(viewModel: ListenViewModel, actions: ListenActions) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    ListenContent(ui, actions)
}

/**
 * Listen's home (Plan U2 Decision 4): your player and your collection. The Now playing card on top, then the Music
 * and Audiobooks shelves with the Library's rows; with no audio yet, "Nothing to listen to yet", the audio sources
 * and [FIND_ON_PHONE]. Null [ui] is still loading.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ListenContent(ui: ListenUi?, actions: ListenActions) {
    var menuOpen by remember { mutableStateOf(false) }
    // Shelf ids the user has collapsed; all start expanded, as in the Library.
    var collapsed by rememberSaveable { mutableStateOf(emptyList<String>()) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Listen", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                actions = {
                    IconButton(onClick = actions.onGetMore) { Icon(Icons.Filled.Add, contentDescription = "Get more") }
                    Box {
                        IconButton(onClick = { menuOpen = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "More") }
                        DropdownMenu(menuOpen, { menuOpen = false }) {
                            DropdownMenuItem(text = { Text(FIND_ON_PHONE) }, onClick = { menuOpen = false; actions.onFindOnPhone() })
                        }
                    }
                },
            )
        },
    ) { padding ->
        val loaded = ui ?: return@Scaffold
        LazyColumn(Modifier.padding(padding).fillMaxSize()) {
            loaded.nowPlaying?.let { card -> item(key = "now-playing") { NowPlayingCardRow(card, actions) } }
            if (loaded.empty) {
                item(key = "empty") {
                    Text(
                        "Nothing to listen to yet",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp),
                    )
                }
                for (source in AUDIO_SOURCES) {
                    item(key = "source:$source") {
                        ListItem(
                            modifier = Modifier.clickable { actions.onOpenSource(source) },
                            headlineContent = { Text(source.title) },
                            supportingContent = { Text(source.subtitle) },
                        )
                    }
                }
                item(key = "find-on-phone") {
                    OutlinedButton(onClick = actions.onFindOnPhone, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                        Text(FIND_ON_PHONE)
                    }
                }
            }
            for (kind in loaded.shelves) {
                kindRows(
                    kind,
                    open = kind.id !in collapsed,
                    onToggle = { open -> collapsed = if (open) collapsed + kind.id else collapsed - kind.id },
                    onOpenCollection = actions.onOpenCollection,
                    onOpenMusic = actions.onOpenMusic,
                    onOpenAudiobooks = actions.onOpenAudiobooks,
                )
            }
        }
    }
}

/** The card: cover, what it is ("Now playing"), the two lines, and play/pause. A tap on the rest opens the player. */
@Composable
private fun NowPlayingCardRow(card: NowPlayingCard, actions: ListenActions) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).testTag(NOW_PLAYING_TAG),
    ) {
        Row(
            Modifier.clickable(onClickLabel = "Open player", onClick = actions.onOpenPlayer).padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PlayerCover(card.coverPath, book = card.kind == CardKind.AUDIOBOOK, size = 56.dp, voice = card.kind == CardKind.VOICE)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(card.label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                Text(card.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    card.subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            IconButton(onClick = actions.onPlayPause) { PlayPauseIcon(card.playing) }
        }
    }
}
