package app.granger.search

import app.granger.invoke
import app.granger.resolve
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.TextButton
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.text.font.FontWeight
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
import app.granger.core.library.ArtistHit
import app.granger.core.library.Audiobook
import app.granger.core.library.BookHit
import app.granger.core.library.SearchHit
import app.granger.core.library.Track
import app.granger.music.AlbumCover
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
    /** The empty state's Get more (Plan SQ Task 5.2); null hides it. */
    onGetMore: (() -> Unit)? = null,
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
                actions = { app.granger.online.LocalOnlyButton() },
            )
        },
    ) { padding ->
        // Offline (Plan O3): a line under the phone's results; only then, so the list is unchanged online.
        val offlineNow = !app.granger.online.isOnlineNow()
        val nothingFound = state.nothingFound
        Box(Modifier.padding(padding).fillMaxSize()) {
            if (state.searching) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (nothingFound) {
                Column(Modifier.align(Alignment.Center).padding(24.dp)) {
                    Text(stringResource(R.string.search_nothing_found))
                    onGetMore?.let { getMore ->
                        TextButton(onClick = getMore, modifier = Modifier.padding(top = 4.dp), contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 0.dp)) { Text(stringResource(R.string.search_get_more)) }
                    }
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
                // Plan SQ Task 5.3: chips narrow the results to one kind, once the query found more than one.
                val kinds = state.kinds
                if (state.query.isNotBlank() && (kinds.size > 1 || state.filter != SearchFilter.ALL)) {
                    item(key = "filters") { FilterChips(state.filter, kinds, viewModel::setFilter) }
                }
                // Plan SQ Decision 8: a medical query's results carry Ask's banner; Search only ever quotes.
                if (state.medical && !nothingFound) item(key = "medical") { MedicalBanner() }
                // Plan SQ Task 5.4: a single word's dictionary entry.
                state.define?.takeIf { state.showing(SearchFilter.ARTICLES) }?.let { entry ->
                    item(key = "define") {
                        DefineCard(entry) { scope.launch { viewModel.recordSearch() }; onOpenMatch(app.granger.core.research.BestMatches.Match(entry, null)) }
                    }
                }
                // Plan SQ: Best matches lead, each its article or chapter with the sentence that matched.
                val bestAll = state.bestFiltered
                val bestOn = state.filter != SearchFilter.MEDIA
                if (bestOn && (bestAll.isNotEmpty() || state.bestLoading)) item(key = "best-header") { SectionHeader(stringResource(R.string.search_best)) }
                if (bestOn && state.bestLoading && bestAll.isEmpty()) {
                    item(key = "best-looking") {
                        Text(
                            stringResource(R.string.search_best_looking), Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                val bestShown = state.shown(SearchGroup.BEST, bestAll)
                itemsIndexed(bestShown, key = { _, m -> "bm:${m.passage.sourceId}/${m.passage.location}" }) { _, match ->
                    val p = match.passage
                    ListItem(
                        modifier = Modifier.clickable { scope.launch { viewModel.recordSearch() }; onOpenMatch(match) }.testTag(SearchTags.BEST_ROW),
                        leadingContent = { KindIcon(SearchLines.matchKind(p)) },
                        headlineContent = { Text(p.sourceTitle, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                        supportingContent = {
                            Column {
                                val line = SearchLines.matchLine(match)
                                val shown = remember(line, state.bestWords) { SearchRows.highlight(line, state.bestWords) }
                                Text(shown, maxLines = 3, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                                // A media card's "Your music" / "Your audiobooks" in the phone's words (Plan I7 Task 7c).
                                Text(SearchLines.matchSource(p).resolve(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        },
                    )
                }
                if (bestAll.size > SearchGroup.BEST.preview) {
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
                val titles = state.titlesFiltered
                if (titles.isNotEmpty()) item { SectionHeader(stringResource(R.string.search_titles)) }
                results(state, SearchGroup.TITLES, titles, { _, hit -> "t:${hit.packId}/${hit.path}" }, ::toggle) { hit ->
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
                        headlineContent = { HitTitle(hit) },
                        // A book reads "J. M. Barrie · Granger Classics".
                        supportingContent = {
                            Column {
                                Text(listOf(hit.author, hit.packTitle).filter { it.isNotBlank() }.joinToString(" · "))
                                if (hit.also.isNotEmpty()) AlsoIn(hit.also, ::open)
                            }
                        },
                    )
                }
                val fullText = if (state.showing(SearchFilter.ARTICLES)) state.articles else emptyList()
                if (fullText.isNotEmpty()) item { SectionHeader(stringResource(R.string.search_articles)) }
                results(state, SearchGroup.FULL_TEXT, fullText, { _, hit -> "f:${hit.packId}/${hit.path}" }, ::toggle) { hit ->
                    ListItem(
                        modifier = Modifier.clickable { open(hit) },
                        headlineContent = { HitTitle(hit) },
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
                // Offline: the phone's results above, and a line that online ones come with a connection (Plan O3).
                if (offlineNow && state.query.isNotBlank()) item(key = "offline-line") { app.granger.online.OfflineSearchLine() }
                val books = if (state.showing(SearchFilter.BOOKS)) state.books else emptyList()
                if (books.isNotEmpty()) item { SectionHeader(stringResource(R.string.search_books)) }
                results(state, SearchGroup.BOOKS, books, { _, hit -> "b:${hit.bookId}" }, ::toggle) { hit ->
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
                val music = if (state.showing(SearchFilter.MEDIA)) state.media else app.granger.core.library.MediaResults()
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
    const val FILTER = "search-filter"
    const val MEDICAL = "search-medical"
    const val DEFINE = "search-define"

    /** A Best match's kind icon: `search-kind-article`, `-book`, `-music`, `-audiobook`. */
    fun kind(kind: SearchLines.MatchKind) = "search-kind-${kind.name.lowercase()}"
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

/**
 * The second line of each music and audiobook result (the copy is fixed by plan M3), and the Show all row. Shared with
 * the iPhone since Plan I7 Task 7c ([SearchLines]); this hands on, and draws the bold words as Compose wants them.
 */
internal object SearchRows {
    /** A title as words, to tell the same article in two places (online and on the phone). */
    fun titleKey(title: String): String = SearchLines.titleKey(title)

    fun artist(a: ArtistHit): UiText = SearchLines.artist(a)
    fun album(a: AlbumSummary): UiText = SearchLines.album(a)
    fun song(t: Track): UiText = SearchLines.song(t)
    fun audiobook(b: Audiobook): UiText = SearchLines.audiobook(b)

    /** A [capped] group may hold more than were fetched, so it doesn't claim to show them all. */
    fun showAll(group: SearchGroup, count: Int, capped: Boolean): UiText = SearchLines.showAll(group, count, capped)

    fun showFewer(group: SearchGroup): UiText = SearchLines.showFewer(group)

    /** A book pack's text hit as a book's ([SearchLines.bookHitOf]). */
    fun bookHitOf(hit: SearchHit, query: String): BookHit = SearchLines.bookHitOf(hit, query)

    /** A book result's line under its title ([SearchLines.bookLine]). */
    fun bookLine(hit: BookHit): UiText? = SearchLines.bookLine(hit)

    /** Under the results: the packs searched by title only ([SearchLines.titleOnly]). */
    fun titleOnly(titles: List<String>): UiText? = SearchLines.titleOnly(titles)

    /** Whether an article's [snippet] shows the match ([SearchLines.snippetMatches]). */
    fun snippetMatches(snippet: String, query: String): Boolean = SearchLines.snippetMatches(snippet, query)

    /**
     * [sentence] with the words that match [keywords] in bold (Plan SQ Task 5.1, [SearchLines.highlights]): a word matches
     * when, case and accents aside, it is a keyword's word or begins with one of three letters or more ("burns" for "burn").
     */
    fun highlight(sentence: String, keywords: List<String>): AnnotatedString {
        val bold = SearchLines.highlights(sentence, keywords)
        if (bold.isEmpty()) return AnnotatedString(sentence)
        return buildAnnotatedString {
            append(sentence)
            bold.forEach { r -> addStyle(SpanStyle(fontWeight = FontWeight.SemiBold), r.first, r.last + 1) }
        }
    }

    /** The Define card's text from a dictionary entry (shared since Plan I7 Task 7b: [app.granger.search.SearchDefine.text]). */
    fun defineText(entry: String): String = SearchDefine.text(entry)
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
        // A heading for TalkBack, so its reader can jump group to group.
        modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp).semantics { heading() },
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

/** The chips over the results (Plan SQ Task 5.3): All, then each kind the query found, and the one picked. */
@Composable
private fun FilterChips(selected: SearchFilter, kinds: Set<SearchFilter>, onSelect: (SearchFilter) -> Unit) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        SearchFilter.entries.filter { it == SearchFilter.ALL || it in kinds || it == selected }.forEach { filter ->
            FilterChip(
                selected = filter == selected,
                onClick = { onSelect(filter) },
                label = {
                    Text(
                        stringResource(
                            when (filter) {
                                SearchFilter.ALL -> R.string.search_filter_all
                                SearchFilter.ARTICLES -> R.string.search_filter_articles
                                SearchFilter.BOOKS -> R.string.search_filter_books
                                SearchFilter.MEDIA -> R.string.search_filter_media
                            },
                        ),
                    )
                },
                modifier = Modifier.testTag(SearchTags.FILTER),
            )
        }
    }
}

/** Ask's medical banner, over a medical query's results. */
@Composable
private fun MedicalBanner() {
    Text(
        stringResource(R.string.research_medical),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onTertiaryContainer,
        modifier = Modifier
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.tertiaryContainer, RoundedCornerShape(8.dp))
            .padding(12.dp)
            .testTag(SearchTags.MEDICAL),
    )
}

/**
 * A Best match's kind: an article, a book, or (Plan I7 Task 7c) an album or audiobook's media card with Listen's
 * headphones; read out by TalkBack with the row.
 */
@Composable
private fun KindIcon(kind: SearchLines.MatchKind) {
    Icon(
        painterResource(
            when (kind) {
                SearchLines.MatchKind.BOOK -> R.drawable.ic_menu_book
                SearchLines.MatchKind.MUSIC, SearchLines.MatchKind.AUDIOBOOK -> R.drawable.ic_headphones
                SearchLines.MatchKind.ARTICLE -> R.drawable.ic_article
            },
        ),
        contentDescription = SearchLines.kindLabel(kind).resolve(),
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.testTag(SearchTags.kind(kind)),
    )
}

/** A single word's dictionary entry (Plan SQ Task 5.4): the headword and the start of its senses; tapping opens it. */
@Composable
private fun DefineCard(entry: app.granger.core.research.Passage, onOpen: () -> Unit) {
    Column(
        Modifier
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(12.dp))
            .clickable(onClick = onOpen)
            .padding(12.dp)
            .testTag(SearchTags.DEFINE),
    ) {
        Text(stringResource(R.string.search_define), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
        Text(entry.sourceTitle, style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
        Text(SearchRows.defineText(entry.text), maxLines = 4, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
        Text(
            stringResource(R.string.search_define_source, entry.packTitle.ifBlank { "Wiktionary" }),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

/** A result's title with its mark: on this phone or saved for now (Plan O2 Task 4); a book's title alone. */
@Composable
private fun HitTitle(hit: SearchHit) {
    androidx.compose.foundation.layout.Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)) {
        Text(hit.title, Modifier.weight(1f, fill = false))
        if (!app.granger.core.library.BookIds.isBook(hit.packId)) app.granger.online.AvailabilityMark(hit.packId, hit.path)
    }
}
