package app.granger.explore

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import app.granger.R
import app.granger.shell.rememberRailCover
import java.text.NumberFormat

/** What Explore's rows lead to. */
class ExploreActions(
    val onOpenSubject: (Subject) -> Unit = {},
    val onOpenArticle: (packId: String, path: String, title: String) -> Unit = { _, _, _ -> },
    /** The Timeline, at an article (an era's best-known), or where it last was with null. */
    val onOpenTimeline: (packId: String?, path: String?) -> Unit = { _, _ -> },
    /** Your sources: the packs as Browse lists them. */
    val onOpenSources: () -> Unit = {},
    val onOpenPack: (packId: String) -> Unit = {},
    /** Get more: the catalog's packs. */
    val onGetMore: () -> Unit = {},
    val onBack: () -> Unit = {},
)

object ExploreTags {
    fun door(subject: Subject) = "explore_door_${subject.id}"
    fun era(era: Era) = "explore_era_${era.name.lowercase()}"
    const val SOURCES = "explore_sources"
    const val NO_SUBJECTS = "explore_no_subjects"
    const val MAP = "explore_map"
}

private fun count(n: Int): String = NumberFormat.getIntegerInstance().format(n)

@Composable
fun ExploreScreen(viewModel: ExploreViewModel, actions: ExploreActions) {
    androidx.lifecycle.compose.LifecycleResumeEffect(Unit) {
        viewModel.refresh()
        onPauseOrDispose {}
    }
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    var map by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf(false) }
    ExploreContent(ui, actions, onMap = { map = true })
    val shown = ui
    if (map && shown != null) LibraryMap(shown, viewModel::inside, actions, onClose = { map = false })
}

/** Explore's landing (Plan EX Task 2): subject doors, Through time as a vertical timeline, Your sources. Null [ui] is loading. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExploreContent(ui: ExploreUi?, actions: ExploreActions, onMap: () -> Unit = {}) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.explore_title), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                actions = {
                    IconButton(onClick = actions.onGetMore) { Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.explore_get_more)) }
                },
            )
        },
    ) { padding ->
        val loaded = ui ?: return@Scaffold
        LazyColumn(Modifier.padding(padding).fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 24.dp)) {
            if (loaded.articles > 0) {
                item(key = "summary") {
                    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            stringResource(R.string.explore_summary, count(loaded.articles), pluralStringResource(R.plurals.explore_packs, loaded.sources.size, loaded.sources.size)),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                        )
                        // The library as a map (Plan EX Task 5, mockup C), opened from here as the topic map is from a topic page.
                        if (!loaded.noSubjects) TextButton(onClick = onMap, modifier = Modifier.testTag(ExploreTags.MAP)) { Text(stringResource(R.string.explore_as_map)) }
                    }
                }
            }
            if (loaded.noSubjects) {
                item(key = "no-subjects") {
                    Column(Modifier.padding(16.dp).testTag(ExploreTags.NO_SUBJECTS)) {
                        Text(stringResource(R.string.explore_no_subjects), style = MaterialTheme.typography.bodyLarge)
                        Button(onClick = actions.onGetMore, modifier = Modifier.padding(top = 8.dp)) { Text(stringResource(R.string.explore_get_wikipedia)) }
                    }
                }
            }
            if (loaded.doors.isNotEmpty()) {
                item(key = "subjects-head") { SectionHead(stringResource(R.string.explore_start), Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 8.dp)) }
                items(loaded.doors.chunked(2), key = { row -> "doors:" + row.joinToString { it.subject.id } }) { row ->
                    Row(Modifier.padding(horizontal = 12.dp, vertical = 5.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        for (door in row) DoorCard(door, Modifier.weight(1f)) { actions.onOpenSubject(door.subject) }
                        if (row.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
            }
            if (loaded.eras.isNotEmpty()) {
                item(key = "time-head") {
                    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                        SectionHead(stringResource(R.string.explore_time), Modifier.weight(1f))
                        TextButton(onClick = { actions.onOpenTimeline(null, null) }) { Text(stringResource(R.string.explore_open_timeline)) }
                    }
                }
                items(loaded.eras, key = { "era:" + it.era.name }) { row ->
                    EraLine(row, first = row == loaded.eras.first(), last = row == loaded.eras.last()) {
                        val top = row.examples.firstOrNull()
                        actions.onOpenTimeline(top?.packId, top?.path)
                    }
                }
            }
            if (loaded.sources.isNotEmpty()) {
                item(key = "sources") {
                    OutlinedCard(
                        onClick = actions.onOpenSources,
                        modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 20.dp).fillMaxWidth().testTag(ExploreTags.SOURCES),
                        shape = RoundedCornerShape(16.dp),
                    ) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(stringResource(R.string.explore_sources), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f).semantics { heading() })
                                Text(
                                    pluralStringResource(R.plurals.explore_all_packs, loaded.sources.size, loaded.sources.size),
                                    style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary,
                                )
                            }
                            Text(loaded.sources.joinToString(" · "), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionHead(text: String, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.titleLarge, modifier = modifier.semantics { heading() })
}

/** A subject's door: its picture, name, count (or its packs) and best-known three. */
@Composable
private fun DoorCard(door: SubjectDoor, modifier: Modifier, onOpen: () -> Unit) {
    val picture = rememberRailCover(door.picture, 160.dp)
    Card(
        onClick = onOpen,
        modifier = modifier.testTag(ExploreTags.door(door.subject)),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Box(Modifier.fillMaxWidth().height(78.dp).background(MaterialTheme.colorScheme.surfaceContainerHighest)) {
            if (picture != null) Image(picture, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        }
        Column(Modifier.padding(start = 10.dp, end = 10.dp, top = 9.dp, bottom = 11.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(stringResource(door.subject.label), style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            val line = when {
                door.count > 0 -> pluralStringResource(R.plurals.explore_topics, door.count, count(door.count))
                door.packs.size == 1 -> door.packs.single().title
                else -> pluralStringResource(R.plurals.explore_packs, door.packs.size, door.packs.size)
            }
            Text(line, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            val examples = if (door.examples.isNotEmpty()) door.examples.joinToString(", ") { it.title } else door.packs.joinToString(", ") { it.title }
            Text(examples, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 3, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** One era on the vertical timeline: the line and its dot, a picture, the era's name, count, years and best-known three. */
@Composable
private fun EraLine(row: EraRow, first: Boolean, last: Boolean, onOpen: () -> Unit) {
    val picture = rememberRailCover(row.picture, 64.dp)
    val line = MaterialTheme.colorScheme.outlineVariant
    Row(
        Modifier.fillMaxWidth().height(androidx.compose.foundation.layout.IntrinsicSize.Min).heightIn(min = 78.dp)
            .clickable(onClick = onOpen).padding(horizontal = 16.dp).testTag(ExploreTags.era(row.era)),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.width(14.dp).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.width(2.dp).weight(1f).background(if (first) androidx.compose.ui.graphics.Color.Transparent else line))
            Box(Modifier.size(10.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary))
            Box(Modifier.width(2.dp).weight(1f).background(if (last) androidx.compose.ui.graphics.Color.Transparent else line))
        }
        Row(Modifier.weight(1f).padding(vertical = 7.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.size(64.dp).clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.surfaceContainerHighest).clearAndSetSemantics {}) {
                if (picture != null) Image(picture, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            }
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.Bottom) {
                    Text(stringResource(row.era.label), style = MaterialTheme.typography.titleSmall)
                    Text(pluralStringResource(R.plurals.explore_topics, row.count, count(row.count)), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                }
                Text(stringResource(row.era.span), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(row.examples.joinToString(" · ") { it.title }, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
fun SubjectScreen(viewModel: SubjectViewModel, actions: ExploreActions, subjectActions: SubjectActions) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    SubjectContent(
        ui, actions,
        SubjectActions(
            onMore = viewModel::more,
            onEra = viewModel::choose,
            onSort = viewModel::sort,
            onWander = { scope.launch { viewModel.wanderStart()?.let { subjectActions.onWanderAt(it.packId, it.path) } } },
            onQuiz = subjectActions.onQuiz,
            onOpenBook = subjectActions.onOpenBook,
            onWanderAt = subjectActions.onWanderAt,
            picture = viewModel::picture,
        ),
    )
}

/** What a subject page's own controls do; [SubjectScreen] fills the paging and filters from its view model. */
class SubjectActions(
    val onMore: () -> Unit = {},
    val onEra: (Era?) -> Unit = {},
    val onSort: (SubjectSort) -> Unit = {},
    val onWander: () -> Unit = {},
    /** Wander from an article: Home's Wander, started there. */
    val onWanderAt: (packId: String, path: String) -> Unit = { _, _ -> },
    val onQuiz: () -> Unit = {},
    val onOpenBook: (bookId: String) -> Unit = {},
    /** An article's cached picture; null when it has none. Blocking. */
    val picture: (ExploreArticle) -> String? = { null },
)

object SubjectTags {
    const val WANDER = "subject_wander"
    const val QUIZ = "subject_quiz"
    const val ALL_ERAS = "subject_era_all"
    fun era(era: Era) = "subject_era_${era.name.lowercase()}"
    fun sort(sort: SubjectSort) = "subject_sort_${sort.name.lowercase()}"
    fun book(id: String) = "subject_book_$id"
}

/**
 * One subject opened (Plan EX Task 3): Wander and Quiz, its eras as chips with counts, most linked or oldest first, In
 * your books, the packs on it, then its articles, each opening its topic page.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SubjectContent(ui: SubjectUi?, actions: ExploreActions, subject: SubjectActions = SubjectActions()) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(ui?.let { stringResource(it.subject.label) }.orEmpty(), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = { IconButton(onClick = actions.onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.common_back)) } },
            )
        },
    ) { padding ->
        val loaded = ui ?: return@Scaffold
        val words = app.granger.home.yearWords()
        LazyColumn(Modifier.padding(padding).fillMaxSize()) {
            if (loaded.count > 0) {
                item(key = "count") {
                    val all = pluralStringResource(R.plurals.explore_topics, loaded.count, count(loaded.count))
                    val text = if (loaded.dated > 0) stringResource(R.string.explore_subject_counts, all, pluralStringResource(R.plurals.explore_dated, loaded.dated, count(loaded.dated))) else all
                    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp))
                }
            }
            if (loaded.canWander || loaded.canQuiz) {
                item(key = "do") {
                    Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (loaded.canWander) FilledTonalButton(onClick = subject.onWander, modifier = Modifier.testTag(SubjectTags.WANDER)) { Text(stringResource(R.string.explore_subject_wander)) }
                        if (loaded.canQuiz) FilledTonalButton(onClick = subject.onQuiz, modifier = Modifier.testTag(SubjectTags.QUIZ)) { Text(stringResource(R.string.explore_subject_quiz)) }
                    }
                }
            }
            if (loaded.eras.isNotEmpty()) {
                item(key = "eras") {
                    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        item(key = "all") {
                            FilterChip(loaded.era == null, onClick = { subject.onEra(null) }, label = { Text(stringResource(R.string.explore_subject_all_eras)) },
                                modifier = Modifier.testTag(SubjectTags.ALL_ERAS))
                        }
                        items(loaded.eras, key = { it.first.name }) { (era, n) ->
                            FilterChip(loaded.era == era, onClick = { subject.onEra(era) },
                                label = { Text(stringResource(R.string.explore_subject_era_chip, stringResource(era.label), count(n))) },
                                modifier = Modifier.testTag(SubjectTags.era(era)))
                        }
                    }
                }
                item(key = "sort") {
                    Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.explore_subject_sort), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        for (s in SubjectSort.entries) {
                            FilterChip(loaded.sort == s, onClick = { subject.onSort(s) },
                                label = { Text(stringResource(if (s == SubjectSort.LINKED) R.string.explore_subject_sort_linked else R.string.explore_subject_sort_oldest)) },
                                modifier = Modifier.testTag(SubjectTags.sort(s)))
                        }
                    }
                }
            }
            if (loaded.books.isNotEmpty()) {
                item(key = "books-head") {
                    SectionHead(pluralStringResource(R.plurals.explore_subject_books, loaded.books.size, count(loaded.books.size)), Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 8.dp))
                }
                item(key = "books") {
                    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        items(loaded.books.take(BOOKS_SHOWN), key = { it.bookId }) { BookCover(it) { subject.onOpenBook(it.bookId) } }
                    }
                }
            }
            if (loaded.packs.isNotEmpty()) {
                item(key = "packs-head") { SectionHead(stringResource(R.string.explore_subject_packs), Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp)) }
                items(loaded.packs, key = { "pack:" + it.id }) { pack ->
                    ListItem(headlineContent = { Text(pack.title) }, modifier = Modifier.clickable { actions.onOpenPack(pack.id) })
                }
            }
            if (loaded.articles.isNotEmpty()) {
                item(key = "articles-head") {
                    val head = loaded.era?.let { stringResource(R.string.explore_subject_in_era, stringResource(it.label)) } ?: stringResource(R.string.explore_subject_articles)
                    SectionHead(head, Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp))
                }
            }
            if (loaded.articles.isEmpty() && loaded.packs.isEmpty() && !loaded.loading) {
                item(key = "empty") { Text(stringResource(R.string.explore_nothing_here), modifier = Modifier.padding(16.dp)) }
            }
            items(loaded.articles, key = { "a:" + it.packId + "/" + it.path }) { a ->
                ArticleRow(a, words, subject.picture) { actions.onOpenArticle(a.packId, a.path, a.title) }
            }
            if (loaded.more) {
                item(key = "more") {
                    TextButton(onClick = subject.onMore, modifier = Modifier.padding(start = 8.dp)) { Text(stringResource(R.string.explore_show_more)) }
                }
            }
        }
    }
}

/** Covers shown in In your books; the shelf holds the rest. */
private const val BOOKS_SHOWN = 24

@Composable
private fun ArticleRow(a: ExploreArticle, words: app.granger.home.YearWords, pictureOf: (ExploreArticle) -> String?, onOpen: () -> Unit) {
    val path by androidx.compose.runtime.produceState<String?>(a.picture, a) { if (value == null) value = withContext(Dispatchers.IO) { pictureOf(a) } }
    val picture = rememberRailCover(path, 48.dp)
    ListItem(
        headlineContent = { Text(a.title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        supportingContent = a.mark?.let { m -> { Text(words.of(m)) } },
        leadingContent = {
            Box(Modifier.size(48.dp).clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceContainerHighest)) {
                if (picture != null) Image(picture, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            }
        },
        modifier = Modifier.clickable(onClick = onOpen),
    )
}

@Composable
private fun BookCover(book: ExploreBook, onOpen: () -> Unit) {
    val cover = rememberRailCover(book.cover, 120.dp)
    Column(Modifier.width(84.dp).clickable(onClick = onOpen).testTag(SubjectTags.book(book.bookId))) {
        Box(Modifier.width(84.dp).height(120.dp).clip(RoundedCornerShape(6.dp)).background(MaterialTheme.colorScheme.surfaceContainerHighest)) {
            if (cover != null) Image(cover, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        }
        Text(book.title, style = MaterialTheme.typography.labelMedium, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
    }
}
