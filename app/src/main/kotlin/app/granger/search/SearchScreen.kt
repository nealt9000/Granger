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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.fromHtml
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.granger.voiceinput.Dictation
import app.granger.voiceinput.MicButton
import app.granger.voiceinput.VoiceInputPrefs
import app.granger.BookTarget
import app.granger.R
import app.granger.UiText
import app.granger.audiobooks.AudiobookCover
import app.granger.core.books.PdfText
import app.granger.core.library.AlbumSummary
import app.granger.core.library.Albums
import app.granger.core.library.ArtistHit
import app.granger.core.library.Audiobook
import app.granger.core.library.BookHit
import app.granger.core.library.SearchHit
import app.granger.core.library.SearchText
import app.granger.core.library.Track
import app.granger.music.AlbumCover
import app.granger.music.MusicCatalog
import app.granger.theme.MediaKind
import kotlinx.coroutines.flow.filter
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
    /** Opens a Best match at its passage (Plan SQ), as Ask's source cards open. */
    onOpenMatch: (app.granger.core.research.BestMatches.Match) -> Unit = {},
    /** Plays the song within its album (`SongPlayer`). */
    onPlaySong: (Track) -> Unit,
    onBack: () -> Unit,
    /** Voice input's settings (Plan A1), for the mic's permission prompt; null hides the mic. */
    voicePrefs: VoiceInputPrefs? = null,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    // Back from a book opened since (and so indexed) searches again, so the book is found (Plan GB walkthrough).
    LifecycleResumeEffect(viewModel) {
        viewModel.onShown()
        onPauseOrDispose {}
    }
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
    // Voice input (Plan A1): the mic in the field, while the phone can listen in the app's language.
    val dictation = viewModel.dictation
    val micShown by viewModel.micShown.collectAsStateWithLifecycle()
    val dictationState = dictation?.state?.collectAsStateWithLifecycle()?.value
    val listening = dictationState is Dictation.State.Listening
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    /** Submitting or scrolling puts the keyboard away, so the results have the screen (Plan U3b Decision 10). */
    fun dropKeyboard() {
        keyboard?.hide()
        focusManager.clearFocus()
    }
    LaunchedEffect(list) {
        snapshotFlow { list.isScrollInProgress }.filter { it }.collect { dropKeyboard() }
    }
    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.common_back)) }
                },
                title = {
                    TextField(
                        value = viewModel.searchText,
                        onValueChange = viewModel::onQueryChange,
                        placeholder = { Text(stringResource(if (listening) R.string.voice_input_listening else R.string.search_placeholder)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { dropKeyboard(); viewModel.refresh() }),
                        trailingIcon = if (micShown && dictation != null && voicePrefs != null) {
                            {
                                MicButton(
                                    listening = listening,
                                    prefs = voicePrefs,
                                    onStart = { dropKeyboard(); viewModel.speak() },
                                    onStop = dictation::stop,
                                )
                            }
                        } else {
                            null
                        },
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
                Column(Modifier.align(Alignment.Center).padding(24.dp)) {
                    Text(stringResource(R.string.search_nothing_found))
                    SearchRows.titleOnly(state.titleOnly)?.let {
                        Text(it.resolve(), Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            LazyColumn(Modifier.fillMaxSize(), state = list) {
                (dictationState as? Dictation.State.Failed)?.let { failed ->
                    item(key = "voice-failed") {
                        Text(failed.message.resolve(), Modifier.padding(horizontal = 16.dp, vertical = 8.dp), style = MaterialTheme.typography.bodyMedium)
                    }
                }
                state.didYouMean?.let { name ->
                    item(key = "did-you-mean") {
                        ListItem(
                            modifier = Modifier.clickable { viewModel.acceptSuggestion() },
                            headlineContent = { Text(stringResource(R.string.search_did_you_mean, name)) },
                        )
                    }
                }
                if (state.query.isBlank() && state.recent.isNotEmpty()) {
                    item { SectionHeader(stringResource(R.string.search_recent)) }
                    items(state.recent, key = { "r:$it" }) { recent ->
                        ListItem(
                            modifier = Modifier.clickable { viewModel.onQueryChange(recent) },
                            headlineContent = { Text(recent) },
                        )
                    }
                }
                // Plan SQ: Best matches lead, each its article or chapter with the sentence that matched.
                if (state.best.isNotEmpty() || state.bestLoading) item(key = "best-header") { SectionHeader(stringResource(R.string.search_best)) }
                if (state.bestLoading && state.best.isEmpty()) {
                    item(key = "best-looking") {
                        Text(
                            stringResource(R.string.search_best_looking), Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                val bestShown = state.shown(SearchGroup.BEST, state.best)
                itemsIndexed(bestShown, key = { _, m -> "bm:${m.passage.sourceId}/${m.passage.location}" }) { _, match ->
                    val p = match.passage
                    ListItem(
                        modifier = Modifier.clickable { scope.launch { viewModel.recordSearch() }; onOpenMatch(match) }.testTag(SearchTags.BEST_ROW),
                        headlineContent = { Text(p.sourceTitle, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                        supportingContent = {
                            Column {
                                val sentence = match.sentence?.let { p.text.substring(it).trim() } ?: p.text.take(220)
                                Text("“$sentence”", maxLines = 3, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                                Text(p.packTitle.ifBlank { p.meta.collection }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        },
                    )
                }
                if (state.best.size > SearchGroup.BEST.preview) {
                    item(key = moreKey(SearchGroup.BEST)) {
                        val open = SearchGroup.BEST in state.expanded
                        ListItem(
                            modifier = Modifier.clickable { viewModel.toggle(SearchGroup.BEST) },
                            headlineContent = {
                                Text(stringResource(if (open) R.string.search_best_fewer else R.string.search_best_more), color = MaterialTheme.colorScheme.primary)
                            },
                        )
                    }
                }
                if (state.titles.isNotEmpty()) item { SectionHeader(stringResource(R.string.search_titles)) }
                results(state, SearchGroup.TITLES, state.titles, { _, hit -> "t:${hit.packId}/${hit.path}" }, ::toggle) { hit ->
                    val openIn = stringResource(R.string.search_open_in)
                    ListItem(
                        modifier = Modifier.clickable { open(hit) }.semantics {
                            // The "Also in" links, for TalkBack: the row reads as one, its other copies as actions.
                            if (hit.also.isNotEmpty()) {
                                customActions = hit.also.map { other ->
                                    CustomAccessibilityAction(openIn.format(other.packTitle.ifBlank { other.author }.ifBlank { other.title })) { open(other); true }
                                }
                            }
                        },
                        headlineContent = { Text(hit.title) },
                        // A book reads "J. M. Barrie · Granger Classics".
                        supportingContent = {
                            Column {
                                Text(listOf(hit.author, hit.packTitle).filter { it.isNotBlank() }.joinToString(" · "))
                                if (hit.also.isNotEmpty()) AlsoIn(hit.also, ::open)
                            }
                        },
                    )
                }
                if (state.fullText.isNotEmpty()) item { SectionHeader(stringResource(R.string.search_articles)) }
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
                if (state.books.isNotEmpty()) item { SectionHeader(stringResource(R.string.search_books)) }
                results(state, SearchGroup.BOOKS, state.books, { _, hit -> "b:${hit.bookId}" }, ::toggle) { hit ->
                    ListItem(
                        modifier = Modifier.clickable { open(hit) },
                        headlineContent = { Text(hit.bookTitle) },
                        supportingContent = {
                            Column {
                                SearchRows.bookLine(hit)?.let { Text(it.resolve()) }
                                PdfText.pageOf(hit.href)?.let { Text(stringResource(R.string.search_page, it)) }
                                if (hit.snippet.isNotBlank()) {
                                    Text(AnnotatedString.fromHtml(hit.snippet), maxLines = 3, overflow = TextOverflow.Ellipsis)
                                }
                            }
                        },
                    )
                }
                val music = state.media
                if (music.artists.isNotEmpty() || music.albums.isNotEmpty() || music.songs.isNotEmpty()) {
                    item { SectionHeader(stringResource(R.string.search_music)) }
                }
                results(state, SearchGroup.ARTISTS, music.artists, { _, artist -> "ma:${artist.name}" }, ::toggle) { artist ->
                    MediaRow(artist.name, SearchRows.artist(artist).resolve(), { AlbumCover(artist.coverPath, artist.name, it, MediaKind.ARTIST) }) {
                        media { onOpenArtist(artist.name) }
                    }
                }
                results(state, SearchGroup.ALBUMS, music.albums, { _, album -> "mb:${album.key}" }, ::toggle) { album ->
                    MediaRow(album.title, SearchRows.album(album).resolve(), { AlbumCover(album.coverPath, album.title, it) }) {
                        media { onOpenAlbum(album.key) }
                    }
                }
                results(state, SearchGroup.SONGS, music.songs, { _, track -> "ms:${track.id}" }, ::toggle) { track ->
                    MediaRow(track.title, SearchRows.song(track).resolve(), { AlbumCover(track.coverPath, track.title, it) }) {
                        media { onPlaySong(track) }
                    }
                }
                if (music.audiobooks.isNotEmpty()) item { SectionHeader(stringResource(R.string.search_audiobooks)) }
                results(state, SearchGroup.AUDIOBOOKS, music.audiobooks, { _, book -> "mk:${book.id}" }, ::toggle) { book ->
                    MediaRow(book.title, SearchRows.audiobook(book).resolve(), { AudiobookCover(book.coverPath, book.title, it) }) {
                        media { onOpenAudiobook(book.id) }
                    }
                }
                if (!nothingFound && state.query.isNotBlank() && !state.searching) {
                    SearchRows.titleOnly(state.titleOnly)?.let { note ->
                        item(key = "title-only") {
                            Text(
                                note.resolve(),
                                Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Test tags on Search's rows. */
object SearchTags {
    const val BEST_ROW = "search-best-row"
    const val ALSO_IN = "search-also-in"
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
                headlineContent = { Text(text.resolve(), color = MaterialTheme.colorScheme.primary) },
            )
        }
    }
}

/** The second line of each music and audiobook result (the copy is fixed by plan M3), and the Show all row. */
internal object SearchRows {
    fun artist(a: ArtistHit): UiText = UiText.Plural(R.plurals.search_artist_line, a.albumCount, a.albumCount)
    fun album(a: AlbumSummary): UiText = UiText.Res(R.string.search_album_line, app.granger.music.shownNameText(a.albumArtist))
    fun song(t: Track): UiText = UiText.Res(
        R.string.search_song_line,
        MusicCatalog.trackArtistsOf(t).joinToString(", ").ifBlank { null }?.let { app.granger.music.shownNameText(it) }
            ?: UiText.Res(R.string.music_unknown_artist),
        app.granger.music.shownNameText(Albums.title(t)),
    )
    fun audiobook(b: Audiobook): UiText {
        val author: UiText = b.author.ifBlank { null }?.let { UiText.Raw(it) } ?: UiText.Res(R.string.audiobooks_unknown_author)
        return if (b.narrator.isBlank()) author else UiText.Res(R.string.search_audiobook_read_by, author, b.narrator)
    }

    /** A [capped] group may hold more than were fetched, so it doesn't claim to show them all. */
    fun showAll(group: SearchGroup, count: Int, capped: Boolean): UiText {
        val id = if (capped) {
            when (group) {
                SearchGroup.TITLES -> R.plurals.search_show_first_titles
                SearchGroup.ARTISTS -> R.plurals.search_show_first_artists
                SearchGroup.ALBUMS -> R.plurals.search_show_first_albums
                SearchGroup.SONGS -> R.plurals.search_show_first_songs
                SearchGroup.AUDIOBOOKS -> R.plurals.search_show_first_audiobooks
                SearchGroup.FULL_TEXT, SearchGroup.BEST -> R.plurals.search_show_first_articles
                SearchGroup.BOOKS -> R.plurals.search_show_first_books
            }
        } else {
            when (group) {
                SearchGroup.TITLES -> R.plurals.search_show_all_titles
                SearchGroup.ARTISTS -> R.plurals.search_show_all_artists
                SearchGroup.ALBUMS -> R.plurals.search_show_all_albums
                SearchGroup.SONGS -> R.plurals.search_show_all_songs
                SearchGroup.AUDIOBOOKS -> R.plurals.search_show_all_audiobooks
                SearchGroup.FULL_TEXT, SearchGroup.BEST -> R.plurals.search_show_all_articles
                SearchGroup.BOOKS -> R.plurals.search_show_all_books
            }
        }
        return UiText.Plural(id, count, count)
    }

    fun showFewer(group: SearchGroup): UiText = UiText.Res(
        when (group) {
            SearchGroup.TITLES -> R.string.search_show_fewer_titles
            SearchGroup.ARTISTS -> R.string.search_show_fewer_artists
            SearchGroup.ALBUMS -> R.string.search_show_fewer_albums
            SearchGroup.SONGS -> R.string.search_show_fewer_songs
            SearchGroup.AUDIOBOOKS -> R.string.search_show_fewer_audiobooks
            SearchGroup.FULL_TEXT, SearchGroup.BEST -> R.string.search_show_fewer_articles
            SearchGroup.BOOKS -> R.string.search_show_fewer_books
        },
    )

    /**
     * A book result's line under its title: the author, and "3 matches" when the book matched in more than one place;
     * null when it has neither.
     */
    fun bookLine(hit: BookHit): UiText? = when {
        hit.matches > 1 && hit.author.isNotBlank() -> UiText.Plural(R.plurals.search_book_matches_by, hit.matches, hit.matches, hit.author)
        hit.matches > 1 -> UiText.Plural(R.plurals.search_book_matches, hit.matches, hit.matches)
        hit.author.isNotBlank() -> UiText.Raw(hit.author)
        else -> null
    }

    /**
     * Under the results: the packs searched by title only (no full-text index), three named and the rest counted; null
     * when there are none.
     */
    fun titleOnly(titles: List<String>): UiText? {
        if (titles.isEmpty()) return null
        val named = titles.take(3).joinToString(", ")
        val rest = titles.size - 3
        return if (rest > 0) UiText.Plural(R.plurals.search_title_only_more, rest, named, rest)
        else UiText.Plural(R.plurals.search_title_only, titles.size, named)
    }

    /**
     * Whether an article's [snippet] shows the match, so it's worth showing: it holds one of [query]'s words, case and
     * accents aside, or a libkiwix `<b>` mark (which also marks a stemmed match, "apocalyptic" for "apocalypse"). A
     * snippet of page chrome (category footers, menus, profile stats) has neither, and the row shows title and pack only
     * (Plan U3b Decision 10).
     */
    fun snippetMatches(snippet: String, query: String): Boolean {
        if (BOLD.containsMatchIn(snippet)) return true
        val text = SearchText.fold(plainText(snippet))
        return SearchText.words(query).any { it in text }
    }

    private val BOLD = Regex("<b>\\s*[^<\\s]", RegexOption.IGNORE_CASE)
    private val TAG = Regex("<[^>]*>")
    private val ENTITIES = mapOf("&amp;" to "&", "&lt;" to "<", "&gt;" to ">", "&quot;" to "\"", "&#39;" to "'", "&nbsp;" to " ")

    private fun plainText(html: String): String =
        ENTITIES.entries.fold(TAG.replace(html, " ")) { text, (entity, char) -> text.replace(entity, char) }
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

/**
 * Under a title row, the same title in other packs or on the shelf (Plan SQ: one row per title), each pack's name a link
 * that opens its copy: "Also in Wikivoyage, Granger Classics".
 */
@Composable
private fun AlsoIn(also: List<SearchHit>, open: (SearchHit) -> Unit) {
    val linkColor = MaterialTheme.colorScheme.primary
    val template = stringResource(R.string.search_also_in, "\u0000")
    val text = remember(also, template, linkColor) {
        val (before, after) = template.split("\u0000").let { it.first() to it.getOrElse(1) { "" } }
        buildAnnotatedString {
            append(before)
            also.forEachIndexed { i, hit ->
                if (i > 0) append(", ")
                val name = hit.packTitle.ifBlank { hit.author }.ifBlank { hit.title }
                withLink(LinkAnnotation.Clickable("also:${hit.packId}/${hit.path}", TextLinkStyles(SpanStyle(color = linkColor))) { open(hit) }) {
                    append(name)
                }
            }
            append(after)
        }
    }
    Text(text, modifier = Modifier.testTag(SearchTags.ALSO_IN), style = MaterialTheme.typography.bodySmall)
}
