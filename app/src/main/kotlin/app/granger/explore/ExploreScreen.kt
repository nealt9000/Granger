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
    const val FEATURED = "explore_featured"
}

internal fun count(n: Int): String = NumberFormat.getIntegerInstance().format(n)

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
fun ExploreContent(ui: ExploreUi?, actions: ExploreActions, onMap: () -> Unit = {}, day: Long = java.time.LocalDate.now().toEpochDay()) {
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
            // The editorial flow (Neal, 2026-11-16: not a grid; read like a page): today's subject as the lead, two
            // asymmetric blocks of three, Through time, then the rest of the subjects as picture-led rows.
            val lead = loaded.featured?.let { f -> loaded.doors.indexOfFirst { it.subject == f.subject } }?.takeIf { it >= 0 }
                ?: featuredIndex(day, loaded.doors.size)
            val featured = loaded.doors.getOrNull(lead)
            val rest = loaded.doors.filterIndexed { i, _ -> i != lead }
            val blocks = rest.take(BLOCK * 2).chunked(BLOCK).filter { it.size == BLOCK }
            val tail = rest.drop(blocks.size * BLOCK)
            val topic = loaded.featured
            if (topic != null && featured != null) {
                item(key = "featured") {
                    TopicFeature(topic, featured, onOpenTopic = { actions.onOpenArticle(topic.article.packId, topic.article.path, topic.article.title) },
                        onOpenSubject = { actions.onOpenSubject(topic.subject) })
                }
            } else if (featured != null) {
                item(key = "featured") { FeatureCard(featured) { actions.onOpenSubject(featured.subject) } }
            }
            blocks.forEachIndexed { b, block ->
                item(key = "block:" + block.joinToString { it.subject.id }) {
                    Block(block, bigFirst = b % 2 == 0) { actions.onOpenSubject(it) }
                }
            }
            if (loaded.eras.isNotEmpty()) {
                item(key = "time-head") {
                    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 24.dp), verticalAlignment = Alignment.CenterVertically) {
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
            if (tail.isNotEmpty()) {
                item(key = "more-head") { SectionHead(stringResource(R.string.explore_more_subjects), Modifier.padding(start = 16.dp, end = 16.dp, top = 24.dp, bottom = 4.dp)) }
                items(tail, key = { "band:" + it.subject.id }) { door -> Band(door) { actions.onOpenSubject(door.subject) } }
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
internal fun SectionHead(text: String, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.titleLarge, modifier = modifier.semantics { heading() })
}

/** Subjects in each asymmetric block: one big, two small. */
private const val BLOCK = 3

/** A door's count line: its articles, else its one pack or collection's name, else how many. */
@Composable
private fun countLine(door: SubjectDoor): String {
    val names = door.packs.map { it.title } + door.collections.map { it.title }
    return when {
        door.count > 0 -> pluralStringResource(R.plurals.explore_topics, door.count, count(door.count))
        names.size == 1 -> names.single()
        else -> pluralStringResource(R.plurals.explore_packs, names.size, names.size)
    }
}

private fun examplesLine(door: SubjectDoor) =
    if (door.examples.isNotEmpty()) door.examples.joinToString(" · ") { it.title }
    else (door.packs.map { it.title } + door.collections.map { it.title }).joinToString(" · ")

/** Today's subject: the landing's lead, a large picture framed on its subject, the subject's own opening sentence. */
@Composable
private fun FeatureCard(door: SubjectDoor, onOpen: () -> Unit) {
    Card(
        onClick = onOpen,
        modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 12.dp).fillMaxWidth().testTag(ExploreTags.door(door.subject)),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        FramedPicture(door.picture, 400.dp, Modifier.fillMaxWidth().height(230.dp).clearAndSetSemantics {})
        Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(R.string.explore_featured).uppercase(), style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary, letterSpacing = androidx.compose.ui.unit.TextUnit(1.5f, androidx.compose.ui.unit.TextUnitType.Sp))
            Text(stringResource(door.subject.label), style = MaterialTheme.typography.headlineMedium)
            door.lead?.let { Text(it, style = MaterialTheme.typography.bodyLarge, maxLines = 4, overflow = TextOverflow.Ellipsis) }
            Text(countLine(door) + " · " + examplesLine(door), style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

/**
 * Today's subject with one of its topics (Neal, 2026-11-17: highlight a topic behind the subject, so each day is new): the
 * topic's picture, large and framed on its subject; the subject named above the topic's title; the topic's opening
 * sentence; and a way on into the subject.
 */
@Composable
private fun TopicFeature(topic: FeaturedTopic, door: SubjectDoor, onOpenTopic: () -> Unit, onOpenSubject: () -> Unit) {
    val subject = stringResource(topic.subject.label)
    Card(
        onClick = onOpenTopic,
        modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 12.dp).fillMaxWidth().testTag(ExploreTags.FEATURED),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        FramedPicture(topic.article.picture, 400.dp, Modifier.fillMaxWidth().height(250.dp).clearAndSetSemantics {})
        Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(R.string.explore_featured_in, subject).uppercase(), style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary, letterSpacing = androidx.compose.ui.unit.TextUnit(1.5f, androidx.compose.ui.unit.TextUnitType.Sp))
            Text(topic.article.title, style = MaterialTheme.typography.headlineMedium)
            topic.sentence?.let { Text(it, style = MaterialTheme.typography.bodyLarge, maxLines = 4, overflow = TextOverflow.Ellipsis) }
        }
        TextButton(onClick = onOpenSubject, modifier = Modifier.padding(start = 4.dp, bottom = 4.dp).testTag(ExploreTags.door(door.subject))) {
            Text(stringResource(R.string.explore_featured_more, subject, pluralStringResource(R.plurals.explore_topics, door.count, count(door.count))))
        }
    }
}

/** Three subjects: one large with its opening sentence, two small stacked beside it; [bigFirst] puts the large one left. */
@Composable
private fun Block(doors: List<SubjectDoor>, bigFirst: Boolean, onOpen: (Subject) -> Unit) {
    val (big, a, b) = doors
    Row(Modifier.padding(start = 12.dp, end = 12.dp, top = 12.dp).fillMaxWidth().height(BLOCK_HEIGHT), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        @Composable fun smalls() = Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            SmallTile(a, Modifier.weight(1f)) { onOpen(a.subject) }
            SmallTile(b, Modifier.weight(1f)) { onOpen(b.subject) }
        }
        if (!bigFirst) smalls()
        BigTile(big, Modifier.weight(1.35f).fillMaxHeight()) { onOpen(big.subject) }
        if (bigFirst) smalls()
    }
}

private val BLOCK_HEIGHT = 320.dp

@Composable
private fun BigTile(door: SubjectDoor, modifier: Modifier, onOpen: () -> Unit) {
    Card(onClick = onOpen, modifier = modifier.testTag(ExploreTags.door(door.subject)), shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        FramedPicture(door.picture, 240.dp, Modifier.fillMaxWidth().height(150.dp).clearAndSetSemantics {})
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(door.subject.label), style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(countLine(door), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            Text(door.lead ?: examplesLine(door), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 4, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun SmallTile(door: SubjectDoor, modifier: Modifier, onOpen: () -> Unit) {
    Card(onClick = onOpen, modifier = modifier.fillMaxWidth().testTag(ExploreTags.door(door.subject)), shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        FramedPicture(door.picture, 160.dp, Modifier.fillMaxWidth().height(84.dp).clearAndSetSemantics {})
        Column(Modifier.padding(horizontal = 10.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(stringResource(door.subject.label), style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(countLine(door), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
        }
    }
}

/** A smaller subject as a row: its picture beside its name, count and best-known articles. */
@Composable
private fun Band(door: SubjectDoor, onOpen: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onOpen).padding(horizontal = 16.dp, vertical = 8.dp).testTag(ExploreTags.door(door.subject)),
        horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically,
    ) {
        FramedPicture(door.picture, 72.dp, Modifier.size(72.dp).clip(RoundedCornerShape(14.dp)).clearAndSetSemantics {})
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(stringResource(door.subject.label), style = MaterialTheme.typography.titleMedium)
            Text(countLine(door), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            Text(examplesLine(door), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** One era on the vertical timeline: the line and its dot, a picture, the era's name, count, years and best-known three. */
@Composable
private fun EraLine(row: EraRow, first: Boolean, last: Boolean, onOpen: () -> Unit) {
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
            FramedPicture(row.picture, 64.dp, Modifier.size(64.dp).clip(RoundedCornerShape(10.dp)).clearAndSetSemantics {})
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
    SubjectPageContent(
        ui, actions,
        SubjectActions(
            onMore = viewModel::more,
            onEra = viewModel::choose,
            onSort = viewModel::sort,
            onWander = { scope.launch { viewModel.wanderStart()?.let { subjectActions.onWanderAt(it.packId, it.path) } } },
            onQuiz = subjectActions.onQuiz,
            onOpenBook = subjectActions.onOpenBook,
            onOpenCollection = subjectActions.onOpenCollection,
            onOpenFacet = subjectActions.onOpenFacet,
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
    /** In A–Z order: the list from a letter on (the rail). */
    val onJump: (letter: String) -> Unit = {},
    val onWander: () -> Unit = {},
    /** Wander from an article: Home's Wander, started there. */
    val onWanderAt: (packId: String, path: String) -> Unit = { _, _ -> },
    val onQuiz: () -> Unit = {},
    val onOpenBook: (bookId: String) -> Unit = {},
    /** A book collection's Library page (`c:<key>`). */
    val onOpenCollection: (collectionId: String) -> Unit = {},
    /** A sub-category's listing (its facet id), or the whole subject's (null). */
    val onOpenFacet: (facetId: String?) -> Unit = {},
    /** An article's cached picture; null when it has none. Blocking. */
    val picture: (ExploreArticle) -> String? = { null },
)

object SubjectTags {
    const val WANDER = "subject_wander"
    const val QUIZ = "subject_quiz"
    const val ALL_ERAS = "subject_era_all"
    fun era(era: Era) = "subject_era_${era.name.lowercase()}"
    fun sort(sort: SubjectSort) = "subject_sort_${sort.name.lowercase()}"
    const val RAIL = "subject_az_rail"
    fun letter(l: String) = "subject_az_$l"
    fun book(id: String) = "subject_book_$id"
    fun collection(id: String) = "subject_collection_$id"
    fun facet(id: String) = "subject_facet_$id"
    const val SEE_ALL = "subject_see_all"
}


@Composable
internal fun ArticleRow(a: ExploreArticle, words: app.granger.home.YearWords, pictureOf: (ExploreArticle) -> String?, onOpen: () -> Unit) {
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
internal fun BookCover(book: ExploreBook, onOpen: () -> Unit) {
    val cover = rememberRailCover(book.cover, 120.dp)
    Column(Modifier.width(84.dp).clickable(onClick = onOpen).testTag(SubjectTags.book(book.bookId))) {
        Box(Modifier.width(84.dp).height(120.dp).clip(RoundedCornerShape(6.dp)).background(MaterialTheme.colorScheme.surfaceContainerHighest)) {
            if (cover != null) Image(cover, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        }
        Text(book.title, style = MaterialTheme.typography.labelMedium, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
    }
}
