package app.granger.topic

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.granger.R
import app.granger.core.library.BookMention
import app.granger.home.yearWords
import app.granger.shell.rememberRailCover
import kotlinx.coroutines.flow.Flow
import kotlin.math.cos
import kotlin.math.sin

/** One circle on the connections map: an article (person, event, place, topic) or a book. */
data class MapNode(
    val kind: NodeKind,
    val title: String,
    val picture: String?,
    val packId: String? = null,
    val path: String? = null,
    val bookId: String? = null,
    val chapter: String? = null,
    val mention: BookMention? = null,
)

/**
 * The connections map's circles for a topic (Plan LL2 Task 5, layout C): the closest topics, the people of its time, its
 * events and the books near it, the most relevant first so the inner ring gets them, at most [MAX]. [filter] keeps one
 * kind. Pure.
 */
object TopicMapNodes {
    /** Six in the inner ring and four in the outer one's diagonal gaps: more crowd a phone. */
    const val MAX = 10
    const val INNER = 6

    fun of(topic: Topic, filter: NodeKind? = null): List<MapNode> {
        val related = topic.related.orEmpty().map { MapNode(it.kind, it.title, it.picture, it.packId, it.path) }
        val people = topic.time?.people.orEmpty().map { MapNode(NodeKind.PERSON, TopicSource.titleOf(it.path), topic.time?.pictures?.get(it.path), it.packId, it.path) }
        val events = topic.time?.events.orEmpty().map { MapNode(NodeKind.EVENT, TopicSource.titleOf(it.path), null, it.packId, it.path) }
        val books = topic.relatedBooks.orEmpty().map { MapNode(NodeKind.BOOK, it.book.title, it.book.cover, bookId = it.book.bookId, chapter = it.chapter) } +
            topic.books?.inBooks.orEmpty().map { MapNode(NodeKind.BOOK, it.book.title, it.book.cover, bookId = it.book.bookId, mention = it.passages.firstOrNull()) }
        // Turns: three closest topics, then one of each other kind, then the rest in the same turns.
        val queues = listOf(ArrayDeque(related), ArrayDeque(people), ArrayDeque(events), ArrayDeque(books.distinctBy { it.bookId }))
        val out = ArrayList<MapNode>()
        val seen = HashSet<String>()
        fun take(q: ArrayDeque<MapNode>) {
            while (q.isNotEmpty()) {
                val n = q.removeFirst()
                val key = n.path?.let { "${n.packId}/$it" } ?: "book:${n.bookId}"
                if (n.path == topic.path || !seen.add(key)) continue
                if (filter == null || n.kind == filter) out += n
                return
            }
        }
        while (queues.any { it.isNotEmpty() } && out.size < MAX) {
            repeat(if (filter == null) 3 else 1) { take(queues[0]) }
            for (q in queues.drop(1)) take(q)
        }
        return out.take(MAX)
    }
}

object TopicMapTags {
    const val MAP = "topic_map"
    const val NODE = "topic_map_node"
}

/**
 * Explore connections (Plan LL2 Task 5): the subject in the middle and its connections around it. A tap on an article's
 * circle centres the map on it, a long press opens its topic page, a book opens at the passage; Back steps back through
 * the centres, then closes the map.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TopicMap(
    start: Topic,
    topicOf: (packId: String, path: String) -> Flow<Topic>,
    onOpenArticle: (packId: String, path: String, title: String) -> Unit,
    onOpenBookAt: (bookId: String, chapter: String) -> Unit,
    onOpenPassage: (bookId: String, mention: BookMention) -> Unit,
    onClose: () -> Unit,
) {
    val centres = remember { mutableStateListOf(start.packId to start.path) }
    var filter by remember { mutableStateOf<NodeKind?>(null) }
    val centre = centres.last()
    val topic by (if (centre == start.packId to start.path) remember(start) { kotlinx.coroutines.flow.flowOf(start) } else remember(centre) { topicOf(centre.first, centre.second) })
        .collectAsState(initial = if (centre == start.packId to start.path) start else Topic(centre.first, centre.second))
    BackHandler { if (centres.size > 1) centres.removeAt(centres.lastIndex) else onClose() }
    val words = yearWords()
    val nodes = TopicMapNodes.of(topic, filter)
    Surface(Modifier.fillMaxSize().testTag(TopicMapTags.MAP), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.statusBarsPadding().navigationBarsPadding()) {
            Row(Modifier.fillMaxWidth().height(52.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { if (centres.size > 1) centres.removeAt(centres.lastIndex) else onClose() }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.common_back))
                }
                Text(topic.header?.title ?: TopicSource.titleOf(topic.path), style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                val all = TopicMapNodes.of(topic).map { it.kind }.toSet()
                FilterChip(selected = filter == null, onClick = { filter = null }, label = { Text(stringResource(R.string.topic_map_all)) })
                for ((kind, label) in FILTERS) {
                    if (kind in all) FilterChip(selected = filter == kind, onClick = { filter = if (filter == kind) null else kind }, label = { Text(stringResource(label)) })
                }
            }
            BoxWithConstraints(Modifier.fillMaxWidth().weight(1f)) {
                val w = maxWidth
                val h = maxHeight
                val cx = w / 2
                val cy = h / 2
                // Ovals, not circles: a phone's map is tall, and circles sized to its width crowd the labels together.
                val innerX = w * 0.31f
                val innerY = h * 0.22f
                val outerX = w * 0.38f
                val outerY = h * 0.43f
                val placed = nodes.mapIndexed { i, n ->
                    val ring = if (i < TopicMapNodes.INNER) 0 else 1
                    val count = minOf(nodes.size, TopicMapNodes.INNER)
                    // The outer ring takes the diagonals, above and below the inner ring, where a phone has the room.
                    val angle = Math.toRadians(if (ring == 0) -90.0 + 360.0 * i / count else OUTER_ANGLES[i - TopicMapNodes.INNER])
                    val x = (cx + (if (ring == 0) innerX else outerX) * cos(angle).toFloat()).coerceIn(NODE_WIDTH / 2, w - NODE_WIDTH / 2)
                    val y = (cy + (if (ring == 0) innerY else outerY) * sin(angle).toFloat()).coerceIn(NODE_SIZE / 2, h - NODE_SIZE)
                    Triple(n, x, y) to ring
                }
                val lineColour = MaterialTheme.colorScheme.outlineVariant
                val density = LocalDensity.current
                Canvas(Modifier.fillMaxSize()) {
                    val c = with(density) { Offset(cx.toPx(), cy.toPx()) }
                    for ((p, ring) in placed) {
                        val (_, x, y) = p
                        drawLine(lineColour, c, with(density) { Offset(x.toPx(), y.toPx()) }, strokeWidth = if (ring == 0) 3f else 2f)
                    }
                }
                Node(
                    MapNode(NodeKind.TOPIC, topic.header?.title ?: TopicSource.titleOf(topic.path), topic.header?.picture, topic.packId, topic.path),
                    size = CENTRE_SIZE, centre = true,
                    modifier = Modifier.offset(x = cx - NODE_WIDTH / 2 - (CENTRE_SIZE - NODE_SIZE) / 2, y = cy - CENTRE_SIZE / 2),
                    onTap = {}, onHold = { onOpenArticle(topic.packId, topic.path, topic.header?.title ?: TopicSource.titleOf(topic.path)) },
                )
                for ((p, ring) in placed) {
                    val (n, x, y) = p
                    val size = if (ring == 0) NODE_SIZE else SMALL_NODE
                    Node(
                        n, size, centre = false,
                        modifier = Modifier.offset(x = x - NODE_WIDTH / 2, y = y - size / 2).testTag(TopicMapTags.NODE),
                        onTap = {
                            when {
                                n.path != null && n.packId != null -> centres += n.packId to n.path
                                n.bookId != null && n.mention != null -> onOpenPassage(n.bookId, n.mention)
                                n.bookId != null -> onOpenBookAt(n.bookId, n.chapter.orEmpty())
                            }
                        },
                        onHold = { if (n.packId != null && n.path != null) onOpenArticle(n.packId, n.path, n.title) },
                    )
                }
            }
            Surface(color = MaterialTheme.colorScheme.surfaceContainerLowest, shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    topic.time?.mark?.let { Text(words.of(it), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary) }
                    topic.header?.lead?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodyMedium, maxLines = 3, overflow = TextOverflow.Ellipsis) }
                    Button(
                        // The map's first centre is the topic page it was opened from: back to it, not a second copy.
                        onClick = {
                            if (centres.size == 1) onClose()
                            else onOpenArticle(topic.packId, topic.path, topic.header?.title ?: TopicSource.titleOf(topic.path))
                        },
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) { Text(stringResource(R.string.topic_map_open)) }
                    Text(stringResource(R.string.topic_map_hint), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Node(n: MapNode, size: Dp, centre: Boolean, modifier: Modifier, onTap: () -> Unit, onHold: () -> Unit) {
    val ring = ringColour(n.kind, centre)
    val picture = rememberRailCover(n.picture, size)
    val book = n.kind == NodeKind.BOOK
    val width = if (centre) NODE_WIDTH + (CENTRE_SIZE - NODE_SIZE) else NODE_WIDTH
    Column(
        modifier.width(width).clip(RoundedCornerShape(12.dp)).combinedClickable(onClick = onTap, onLongClick = onHold)
            .semantics(mergeDescendants = true) { contentDescription = n.title },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        val shape = if (book) RoundedCornerShape(4.dp) else CircleShape
        Box(
            Modifier.size(width = if (book) size * 0.72f else size, height = size).clip(shape).border(3.dp, ring, shape)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh),
            contentAlignment = Alignment.Center,
        ) {
            if (picture != null) Image(picture, null, contentScale = ContentScale.Crop, alignment = Alignment.TopCenter, modifier = Modifier.fillMaxSize())
            else Text(n.title.take(1), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(n.title, style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun ringColour(kind: NodeKind, centre: Boolean): Color = when {
    centre -> MaterialTheme.colorScheme.primary
    kind == NodeKind.PERSON -> MaterialTheme.colorScheme.primary
    kind == NodeKind.EVENT -> MaterialTheme.colorScheme.tertiary
    kind == NodeKind.PLACE -> MaterialTheme.colorScheme.secondary
    kind == NodeKind.BOOK -> MaterialTheme.colorScheme.outline
    else -> MaterialTheme.colorScheme.outlineVariant
}

private val FILTERS = listOf(
    NodeKind.PERSON to R.string.topic_map_people,
    NodeKind.EVENT to R.string.topic_map_events,
    NodeKind.PLACE to R.string.topic_map_places,
    NodeKind.TOPIC to R.string.topic_map_topics,
    NodeKind.BOOK to R.string.topic_map_books,
)
private val OUTER_ANGLES = listOf(-125.0, -55.0, 125.0, 55.0)
private val NODE_SIZE = 56.dp
private val SMALL_NODE = 44.dp
private val CENTRE_SIZE = 88.dp
private val NODE_WIDTH = 84.dp
