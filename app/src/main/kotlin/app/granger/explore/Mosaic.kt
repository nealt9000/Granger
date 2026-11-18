package app.granger.explore

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridItemSpan
import androidx.compose.foundation.lazy.staggeredgrid.itemsIndexed
import androidx.compose.foundation.lazy.staggeredgrid.rememberLazyStaggeredGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.granger.R
import kotlinx.coroutines.launch

/** A tile's shape in the mosaic: its width over its height, and whether it spans both columns. */
data class TileShape(val ratio: Float, val wide: Boolean = false)

/**
 * The mosaic's tile for article [index] (pure, for tests; Neal, 2026-11-17: procedural, dynamic with the pictures): its
 * picture's own shape, rounded to landscape 4:3, square or portrait 3:4; a landscape picture now and then (every
 * [WIDE_EVERY]th place, never the first two rows' worth) spans both columns at 16:9. No picture: a square text tile.
 */
fun tileShape(index: Int, ratio: Float, hasPicture: Boolean): TileShape = when {
    !hasPicture || ratio <= 0f -> TileShape(1f)
    ratio >= 1.3f && index >= WIDE_FROM && index % WIDE_EVERY == WIDE_FROM % WIDE_EVERY -> TileShape(16f / 9f, wide = true)
    ratio >= 1.15f -> TileShape(4f / 3f)
    ratio <= 0.87f -> TileShape(3f / 4f)
    else -> TileShape(1f)
}

private const val WIDE_EVERY = 7

/** A text tile's title longer than this is set a size smaller, so it fits. */
private const val LONG_TITLE = 14
private const val WIDE_FROM = 4

object TopicsTags {
    const val GRID = "topics_grid"
    fun tile(path: String) = "topics_tile_$path"
}

@Composable
fun TopicsScreen(viewModel: SubjectViewModel, actions: ExploreActions, subjectActions: SubjectActions, room: Boolean = false) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val all = SubjectActions(
        onMore = viewModel::more,
        onEra = viewModel::choose,
        onSort = viewModel::sort,
        onJump = viewModel::jump,
        onWander = { scope.launch { viewModel.wanderStart()?.let { subjectActions.onWanderAt(it.packId, it.path) } } },
        onWanderAt = subjectActions.onWanderAt,
        onOpenExplore = subjectActions.onOpenExplore,
        onOpenSubject = subjectActions.onOpenSubject,
        onOpenRoom = subjectActions.onOpenRoom,
    )
    // A sub-category is a room, with a room's spread (Plan EX round 2, D3); the whole subject keeps its listing.
    if (room) RoomContent(ui, actions, all) else TopicsContent(ui, actions, all)
}

/**
 * A listing: a sub-category's articles, or the whole subject's (Plan EX editorial pass), as a picture mosaic, titles over
 * the foot of each picture, the subject page's look carried on but laid out by the pictures themselves. Its count and
 * Wander, then its eras as chips and the order (most linked, oldest first, A–Z); more load as the end nears. In A–Z
 * order a rail of letters runs down the edge: a tap or a drag along it starts the list at that letter.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TopicsContent(ui: SubjectUi?, actions: ExploreActions, subject: SubjectActions = SubjectActions()) {
    val language = LocalConfiguration.current.locales[0].language
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    val name = ui?.let { u -> u.facet?.name(language) ?: stringResource(R.string.explore_all_in, stringResource(u.subject.label)) }
                    Text(name.orEmpty(), maxLines = 1, overflow = TextOverflow.Ellipsis)
                },
                navigationIcon = { IconButton(onClick = actions.onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.common_back)) } },
            )
        },
    ) { padding ->
        val loaded = ui ?: return@Scaffold
        val words = app.granger.home.yearWords()
        val grid = rememberLazyStaggeredGridState()
        // More as the end nears: within a screen of the last tile.
        val nearEnd by remember { derivedStateOf { grid.layoutInfo.visibleItemsInfo.lastOrNull()?.index?.let { it >= grid.layoutInfo.totalItemsCount - 8 } == true } }
        LaunchedEffect(nearEnd, loaded.articles.size, loaded.more) { if (nearEnd && loaded.more && !loaded.loading) subject.onMore() }
        val rail = loaded.sort == SubjectSort.AZ && loaded.letters.size > 1
        // A letter chosen: back to the top of the list, where that letter now starts.
        LaunchedEffect(loaded.letter) { if (loaded.letter != null) grid.scrollToItem(0) }
        Box(Modifier.padding(padding).fillMaxSize()) {
        LazyVerticalStaggeredGrid(
            columns = StaggeredGridCells.Fixed(2),
            state = grid,
            modifier = Modifier.fillMaxSize().testTag(TopicsTags.GRID),
            contentPadding = PaddingValues(start = 12.dp, end = if (rail) 36.dp else 12.dp, bottom = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalItemSpacing = 8.dp,
        ) {
            item(key = "head", span = StaggeredGridItemSpan.FullLine) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    val all = pluralStringResource(R.plurals.explore_topics, loaded.count, count(loaded.count))
                    Text(
                        if (loaded.facet != null) stringResource(R.string.explore_in_subject, all, stringResource(loaded.subject.label)) else all,
                        style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f),
                    )
                    if (loaded.canWander) FilledTonalButton(onClick = subject.onWander, modifier = Modifier.testTag(SubjectTags.WANDER)) { Text(stringResource(R.string.explore_subject_wander)) }
                }
            }
            if (loaded.eras.isNotEmpty() && loaded.sort != SubjectSort.AZ) {
                item(key = "eras", span = StaggeredGridItemSpan.FullLine) {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
            }
            if (loaded.count > 0) {
                item(key = "sort", span = StaggeredGridItemSpan.FullLine) {
                    // Scrolls sideways on a narrow screen: three orders and the word don't always fit (A–Z wrapped on a Pixel 6).
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.explore_subject_sort), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        // Oldest first needs dated articles; A–Z and most linked always work.
                        for (s in SubjectSort.entries.filter { it != SubjectSort.OLDEST || loaded.eras.isNotEmpty() }) {
                            FilterChip(loaded.sort == s, onClick = { subject.onSort(s) },
                                label = { Text(stringResource(when (s) {
                                    SubjectSort.LINKED -> R.string.explore_subject_sort_linked
                                    SubjectSort.OLDEST -> R.string.explore_subject_sort_oldest
                                    SubjectSort.AZ -> R.string.explore_subject_sort_az
                                }), maxLines = 1, softWrap = false) },
                                modifier = Modifier.testTag(SubjectTags.sort(s)))
                        }
                    }
                }
            }
            itemsIndexed(
                loaded.articles,
                key = { _, a -> "a:" + a.packId + "/" + a.path },
                span = { i, a -> if (tileShape(i, a.ratio, a.picture != null).wide) StaggeredGridItemSpan.FullLine else StaggeredGridItemSpan.SingleLane },
            ) { i, a ->
                MosaicTile(a, tileShape(i, a.ratio, a.picture != null), a.mark?.let { words.of(it) }) { actions.onOpenArticle(a.packId, a.path, a.title) }
            }
            if (loaded.loading || loaded.more) {
                item(key = "loading", span = StaggeredGridItemSpan.FullLine) {
                    Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.size(28.dp)) }
                }
            } else if (loaded.articles.isEmpty()) {
                item(key = "empty", span = StaggeredGridItemSpan.FullLine) { Text(stringResource(R.string.explore_nothing_here), modifier = Modifier.padding(8.dp)) }
            }
        }
        if (rail) LetterRail(loaded.letters, loaded.letter, subject.onJump, Modifier.align(Alignment.CenterEnd))
        }
    }
}

/**
 * The A–Z rail: the letters the listing's titles start with, top to bottom. A tap, or a drag along the rail, jumps; the
 * letter under the finger shows large beside the rail while dragging. Each letter is its own button for TalkBack.
 */
@Composable
internal fun LetterRail(letters: List<String>, current: String?, onJump: (String) -> Unit, modifier: Modifier = Modifier) {
    var height by remember { mutableIntStateOf(0) }
    var dragging by remember { mutableStateOf<String?>(null) }
    fun at(y: Float): String? = if (height <= 0) null else letters[(y / height * letters.size).toInt().coerceIn(0, letters.lastIndex)]
    Box(modifier.padding(end = 2.dp)) {
        Column(
            Modifier.width(32.dp).onSizeChanged { height = it.height }.testTag(SubjectTags.RAIL)
                .clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.92f))
                .pointerInput(letters) {
                    detectVerticalDragGestures(
                        onDragStart = { o -> at(o.y)?.let { dragging = it; onJump(it) } },
                        onDragEnd = { dragging = null },
                        onDragCancel = { dragging = null },
                    ) { change, _ -> at(change.position.y)?.takeIf { it != dragging }?.let { dragging = it; onJump(it) } }
                }
                .padding(vertical = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            for (l in letters) {
                val label = stringResource(R.string.explore_jump_letter, l)
                Text(
                    l,
                    style = MaterialTheme.typography.labelMedium,
                    color = if (l == (dragging ?: current)) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = if (l == (dragging ?: current)) FontWeight.Bold else null,
                    modifier = Modifier.clickable(onClickLabel = label) { onJump(l) }.semantics { contentDescription = label }
                        .padding(vertical = 1.dp, horizontal = 8.dp).testTag(SubjectTags.letter(l)),
                )
            }
        }
        dragging?.let { l ->
            Box(Modifier.align(Alignment.CenterStart).offset(x = (-72).dp).size(56.dp).clip(RoundedCornerShape(28.dp))
                .background(MaterialTheme.colorScheme.primary), contentAlignment = Alignment.Center) {
                Text(l, style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.onPrimary)
            }
        }
    }
}

/**
 * One article in the mosaic: its picture filling the tile, cropped round its subject; its title (and dates) over a dark
 * fade at the foot, Spectral as on the landing. Without a picture, the title set large on the tile itself.
 */
@Composable
private fun MosaicTile(a: ExploreArticle, shape: TileShape, dates: String?, onOpen: () -> Unit) {
    Card(onClick = onOpen, modifier = Modifier.fillMaxWidth().aspectRatio(shape.ratio).testTag(TopicsTags.tile(a.path)), shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
        if (a.picture == null) {
            Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.Bottom) {
                Text(a.title, style = if (a.title.length > LONG_TITLE) MaterialTheme.typography.titleMedium else MaterialTheme.typography.titleLarge,
                    maxLines = 4, overflow = TextOverflow.Ellipsis)
                dates?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            return@Card
        }
        Box(Modifier.fillMaxSize()) {
            FramedPicture(a.picture, if (shape.wide) 400.dp else 240.dp, Modifier.fillMaxSize().clearAndSetSemantics {})
            Box(Modifier.fillMaxWidth().align(Alignment.BottomCenter)
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.72f))))
                .padding(start = 10.dp, end = 10.dp, top = 28.dp, bottom = 9.dp)) {
                Column {
                    Text(a.title, style = if (shape.wide) MaterialTheme.typography.titleLarge else MaterialTheme.typography.titleSmall,
                        color = Color.White, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    dates?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.82f)) }
                }
            }
        }
    }
}
