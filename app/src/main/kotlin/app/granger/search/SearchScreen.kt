package app.granger.search

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.fromHtml
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.granger.BookTarget
import app.granger.audiobooks.AudiobookCover
import app.granger.audiobooks.UNKNOWN_AUTHOR
import app.granger.core.books.PdfText
import app.granger.core.library.AlbumSummary
import app.granger.core.library.Albums
import app.granger.core.library.ArtistHit
import app.granger.core.library.Audiobook
import app.granger.core.library.BookHit
import app.granger.core.library.SearchHit
import app.granger.core.library.Track
import app.granger.music.AlbumCover
import app.granger.music.MusicCatalog
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(
    viewModel: SearchViewModel,
    onOpenTab: (Long) -> Unit,
    onOpenBook: (tabId: Long, at: BookTarget) -> Unit,
    onOpenAlbum: (albumKey: String) -> Unit,
    onOpenArtist: (name: String) -> Unit,
    onOpenAudiobook: (audiobookId: String) -> Unit,
    /** Plays the song within its album (`SongPlayer`). */
    onPlaySong: (Track) -> Unit,
    onBack: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    fun open(hit: SearchHit) { scope.launch { onOpenTab(viewModel.open(hit)) } }
    fun open(hit: BookHit) { scope.launch { viewModel.openBook(hit).let { (tab, at) -> onOpenBook(tab, at) } } }
    /** A music or audiobook result: the query is remembered as for an article, then [go] opens or plays it. */
    fun media(go: () -> Unit) { scope.launch { viewModel.recordSearch(); go() } }
    val list = rememberLazyListState()
    /** Show all or Show fewer for [group] of [rows]; folding a group the list has scrolled through brings its top back. */
    fun toggle(group: SearchGroup, rows: Int) {
        val folding = group in state.expanded
        viewModel.toggle(group)
        if (!folding) return
        val foot = list.layoutInfo.visibleItemsInfo.firstOrNull { it.key == moreKey(group) } ?: return
        // The rows above the group keep their places, so its header (or the row before it) is just above its first row.
        val top = (foot.index - rows - 1).coerceAtLeast(0)
        if (top < list.firstVisibleItemIndex) scope.launch { list.scrollToItem(top) }
    }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
                title = {
                    TextField(
                        value = viewModel.searchText,
                        onValueChange = viewModel::onQueryChange,
                        placeholder = { Text("Search your library") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().focusRequester(focus),
                    )
                },
            )
        },
    ) { padding ->
        val nothingFound = state.query.isNotBlank() && !state.searching &&
            state.titles.isEmpty() && state.media.isEmpty && state.fullText.isEmpty() && state.books.isEmpty()
        Box(Modifier.padding(padding).fillMaxSize()) {
            if (state.searching) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (nothingFound) {
                Text("Nothing in your library matches.", Modifier.align(Alignment.Center).padding(24.dp))
            }
            LazyColumn(Modifier.fillMaxSize(), state = list) {
                if (state.query.isBlank() && state.recent.isNotEmpty()) {
                    item { SectionHeader("Recent") }
                    items(state.recent, key = { "r:$it" }) { recent ->
                        ListItem(
                            modifier = Modifier.clickable { viewModel.onQueryChange(recent) },
                            headlineContent = { Text(recent) },
                        )
                    }
                }
                if (state.titles.isNotEmpty()) item { SectionHeader("Titles") }
                results(state, SearchGroup.TITLES, state.titles, { _, hit -> "t:${hit.packId}/${hit.path}" }, ::toggle) { hit ->
                    ListItem(
                        modifier = Modifier.clickable { open(hit) },
                        headlineContent = { Text(hit.title) },
                        supportingContent = { Text(hit.packTitle) },
                    )
                }
                val music = state.media
                if (music.artists.isNotEmpty() || music.albums.isNotEmpty() || music.songs.isNotEmpty()) {
                    item { SectionHeader("Music") }
                }
                results(state, SearchGroup.ARTISTS, music.artists, { _, artist -> "ma:${artist.name}" }, ::toggle) { artist ->
                    MediaRow(artist.name, SearchRows.artist(artist), { AlbumCover(artist.coverPath, it) }) {
                        media { onOpenArtist(artist.name) }
                    }
                }
                results(state, SearchGroup.ALBUMS, music.albums, { _, album -> "mb:${album.key}" }, ::toggle) { album ->
                    MediaRow(album.title, SearchRows.album(album), { AlbumCover(album.coverPath, it) }) {
                        media { onOpenAlbum(album.key) }
                    }
                }
                results(state, SearchGroup.SONGS, music.songs, { _, track -> "ms:${track.id}" }, ::toggle) { track ->
                    MediaRow(track.title, SearchRows.song(track), { AlbumCover(track.coverPath, it) }) {
                        media { onPlaySong(track) }
                    }
                }
                if (music.audiobooks.isNotEmpty()) item { SectionHeader("Audiobooks") }
                results(state, SearchGroup.AUDIOBOOKS, music.audiobooks, { _, book -> "mk:${book.id}" }, ::toggle) { book ->
                    MediaRow(book.title, SearchRows.audiobook(book), { AudiobookCover(book.coverPath, it) }) {
                        media { onOpenAudiobook(book.id) }
                    }
                }
                if (state.fullText.isNotEmpty()) item { SectionHeader("In article text") }
                results(state, SearchGroup.FULL_TEXT, state.fullText, { _, hit -> "f:${hit.packId}/${hit.path}" }, ::toggle) { hit ->
                    ListItem(
                        modifier = Modifier.clickable { open(hit) },
                        headlineContent = { Text(hit.title) },
                        supportingContent = {
                            Column {
                                hit.snippet?.let {
                                    Text(AnnotatedString.fromHtml(it), maxLines = 3, overflow = TextOverflow.Ellipsis)
                                }
                                Text(hit.packTitle)
                            }
                        },
                    )
                }
                if (state.books.isNotEmpty()) item { SectionHeader("In books") }
                results(state, SearchGroup.BOOKS, state.books, { index, _ -> "b:$index" }, ::toggle) { hit ->
                    ListItem(
                        modifier = Modifier.clickable { open(hit) },
                        headlineContent = { Text(hit.bookTitle) },
                        supportingContent = {
                            Column {
                                if (hit.author.isNotBlank()) Text(hit.author)
                                PdfText.pageOf(hit.href)?.let { Text("p. $it") }
                                if (hit.snippet.isNotBlank()) {
                                    Text(AnnotatedString.fromHtml(hit.snippet), maxLines = 3, overflow = TextOverflow.Ellipsis)
                                }
                            }
                        },
                    )
                }
            }
        }
    }
}

private fun moreKey(group: SearchGroup) = "more:${group.name}"

/**
 * [group]'s rows as [SearchViewModel.State.shown] has them, then, when it has more than its preview, a row at its foot
 * that shows them all or folds them back.
 */
private fun <T> LazyListScope.results(
    state: SearchViewModel.State,
    group: SearchGroup,
    all: List<T>,
    key: (Int, T) -> Any,
    onToggle: (SearchGroup, rows: Int) -> Unit,
    row: @Composable (T) -> Unit,
) {
    val shown = state.shown(group, all)
    itemsIndexed(shown, key = key) { _, result -> row(result) }
    if (all.size > group.preview) {
        item(key = moreKey(group)) {
            val text = if (group in state.expanded) SearchRows.showFewer(group) else SearchRows.showAll(group, all.size, group in state.capped)
            ListItem(
                modifier = Modifier.clickable { onToggle(group, shown.size) },
                headlineContent = { Text(text, color = MaterialTheme.colorScheme.primary) },
            )
        }
    }
}

/** The second line of each music and audiobook result (the copy is fixed by plan M3), and the Show all row. */
internal object SearchRows {
    fun artist(a: ArtistHit) = "Artist · ${a.albumCount} ${if (a.albumCount == 1) "album" else "albums"}"
    fun album(a: AlbumSummary) = "Album · ${a.albumArtist}"
    fun song(t: Track) = "Song · ${MusicCatalog.trackArtistsOf(t).joinToString(", ").ifBlank { Albums.UNKNOWN_ARTIST }} · ${Albums.title(t)}"
    fun audiobook(b: Audiobook): String {
        val author = b.author.ifBlank { UNKNOWN_AUTHOR }
        return if (b.narrator.isBlank()) author else "$author · read by ${b.narrator}"
    }

    /** A [capped] group may hold more than were fetched, so it doesn't claim to show them all. */
    fun showAll(group: SearchGroup, count: Int, capped: Boolean) =
        if (capped) "Show the first $count ${group.noun}" else "Show all $count ${group.noun}"

    fun showFewer(group: SearchGroup) = "Show fewer ${group.noun}"
}

@Composable
private fun MediaRow(
    title: String,
    line: String,
    cover: @Composable (Modifier) -> Unit,
    onClick: () -> Unit,
) {
    ListItem(
        modifier = Modifier.clickable(onClick = onClick),
        leadingContent = { cover(Modifier.size(48.dp)) },
        headlineContent = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = { Text(line, maxLines = 1, overflow = TextOverflow.Ellipsis) },
    )
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp),
    )
}
