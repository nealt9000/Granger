package app.granger.topic

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.granger.R
import app.granger.core.library.TimeMark
import app.granger.core.library.TopicParts
import app.granger.shell.rememberRailCover
import app.granger.theme.Spectral
import kotlin.math.cos
import kotlin.math.sin

/**
 * A broad topic's page (Plan EX, Neal's pick 2026-11-17: layout A, with C's ring as a map), built by rule from the QID
 * index's parts (tools/topics/parts.py) and what the phone already holds (articles, pictures, the time index).
 */
data class BroadTopic(
    /** How many of the pack's articles are under the topic. */
    val under: Int,
    val groups: List<BroadGroup>,
    /** The best-known kinds, as cards, each with its group (an index into [groups]) or -1. */
    val kinds: List<Pair<TopicArticle, Int>>,
    val story: BroadStory?,
    val people: List<BroadPerson>,
    val chapters: List<BroadChapter>,
)

data class BroadGroup(val name: String, val count: Int)

/** The topic's own story: the section with the most dated pictures and links, as a timeline. */
data class BroadStory(val title: String, val entries: List<StoryEntry>)

/** One step of the story: its year, the caption or linked title, its picture, and the article it opens (if any). */
data class StoryEntry(val year: Long, val text: String, val picture: String?, val opens: TopicArticle?)

data class BroadPerson(val article: TopicArticle, val mark: TimeMark?)

/** Another of the article's sections: its title, the articles it links, and its first picture. */
data class BroadChapter(val title: String, val links: List<TopicArticle>, val picture: String?, val caption: String?)

/**
 * Builds a [BroadTopic] from a topic's [parts]. [resolve] finds an item's article in the installed packs (null: none);
 * [card] reads an article's card (title, sentence, picture); [figure] reads one of the parts' pictures; [markOf] its
 * time mark. Blocking reads happen in the lambdas.
 */
object BroadTopics {
    suspend fun build(
        parts: TopicParts,
        resolve: suspend (qid: Long) -> Pair<String, String>?,
        card: suspend (packId: String, path: String) -> TopicArticle,
        figure: suspend (image: String) -> String?,
        markOf: suspend (packId: String, path: String) -> TimeMark?,
        now: Int,
    ): BroadTopic? {
        val kinds = parts.kinds.take(KINDS_READ).mapNotNull { k -> resolve(k.qid)?.let { (p, path) -> card(p, path) to k.group } }
        val people = parts.people.mapNotNull { q -> resolve(q)?.let { (p, path) -> BroadPerson(card(p, path), markOf(p, path)) } }

        // Each section's dated links and dated figures; the story is the section with most (at least STORY_MIN).
        val linksBySection = parts.links.groupBy { it.section }
        val figuresBySection = parts.figures.groupBy { it.section }
        val resolved = HashMap<Long, Pair<String, String>?>()
        suspend fun at(q: Long) = resolved.getOrPut(q) { resolve(q) }
        val dated = HashMap<Int, List<StoryEntry>>()
        for (s in parts.sections.indices) {
            val entries = ArrayList<StoryEntry>()
            for (f in figuresBySection[s].orEmpty()) {
                val y = yearIn(f.caption, now) ?: continue
                entries += StoryEntry(y, f.caption, figure(f.image), null)
            }
            for (l in linksBySection[s].orEmpty()) {
                val (p, path) = at(l.qid) ?: continue
                val m = markOf(p, path) ?: continue
                if (m.kind == app.granger.core.library.TimeKind.LIFE || m.y0 > now) continue
                entries += StoryEntry(m.y0, TopicSource.titleOf(path), null, TopicArticle(p, path, TopicSource.titleOf(path), "", null))
            }
            if (entries.size >= STORY_MIN) dated[s] = entries
        }
        val storyAt = dated.maxByOrNull { it.value.size }?.key
        val story = storyAt?.let { s ->
            // Pictures first where years tie; one step per year and title; at most STORY_MAX, spread over the span.
            val steps = dated.getValue(s).sortedWith(compareBy<StoryEntry> { it.year }.thenBy { it.picture == null })
                .distinctBy { it.year to it.text }.distinctBy { it.text }
            val kept = if (steps.size <= STORY_MAX) steps else spread(steps, STORY_MAX)
            // A linked step gets its own picture when it has one.
            BroadStory(parts.sections[s].title, kept.map { e -> if (e.picture == null && e.opens != null) e.copy(picture = card(e.opens.packId, e.opens.path).picture) else e })
        }

        val chapters = parts.sections.indices.filter { it != storyAt }.mapNotNull { s ->
            val links = linksBySection[s].orEmpty().mapNotNull { l -> at(l.qid)?.let { (p, path) -> TopicArticle(p, path, TopicSource.titleOf(path), "", null) } }
                .take(CHAPTER_LINKS)
            if (links.size < CHAPTER_MIN) return@mapNotNull null
            val f = figuresBySection[s].orEmpty().firstNotNullOfOrNull { f -> figure(f.image)?.let { it to f.caption } }
            BroadChapter(parts.sections[s].title, links, f?.first, f?.second)
        }.take(CHAPTERS_MAX)

        if (kinds.isEmpty() && story == null && people.isEmpty() && chapters.isEmpty()) return null
        return BroadTopic(parts.under, parts.groups.map { BroadGroup(it.name, it.count) }, kinds, story, people, chapters)
    }

    /** The first year a caption names, 1000 to [now] ("Otto Lilienthal in mid-flight, Berlin, c. 1895"); null if none. */
    fun yearIn(caption: String, now: Int): Long? =
        Regex("\\b(1\\d{3}|20\\d{2})\\b").findAll(caption).map { it.value.toLong() }.firstOrNull { it <= now }

    /** [n] of [steps] (in year order) spread evenly, the first and last kept. */
    fun <T> spread(steps: List<T>, n: Int): List<T> =
        if (steps.size <= n) steps else (0 until n).map { i -> steps[(i * (steps.size - 1) + (n - 1) / 2) / (n - 1)] }.distinct()

    /** Kinds read as cards (the mosaic shows up to nine of a group). */
    const val KINDS_READ = 18
    private const val STORY_MIN = 3
    const val STORY_MAX = 8
    private const val CHAPTER_MIN = 3
    private const val CHAPTER_LINKS = 8
    private const val CHAPTERS_MAX = 5
}

object BroadTags {
    const val KINDS = "broad_kinds"
    const val STORY = "broad_story"
    const val PEOPLE = "broad_people"
    const val MAP = "broad_map"
    const val RING = "broad_ring"
    fun group(i: Int) = "broad_group_$i"
    fun chapter(i: Int) = "broad_chapter_$i"
    fun ring(i: Int) = "broad_ring_$i"
}

/** The page's parts in order, for the ring and for scrolling to one: their keys in the page's list. */
enum class BroadPart { KINDS, STORY, PEOPLE, CHAPTER, CLOSE }

/** One line under the lead: "72 kinds · 12 people · 5 chapters". */
@Composable
fun BroadSummary(broad: BroadTopic) {
    val bits = buildList {
        if (broad.kinds.isNotEmpty()) add(pluralStringResource(R.plurals.topic_broad_kinds_count, broad.under, broad.under))
        if (broad.people.isNotEmpty()) add(pluralStringResource(R.plurals.topic_broad_people_count, broad.people.size, broad.people.size))
        val chapters = broad.chapters.size + (if (broad.story != null) 1 else 0)
        if (chapters > 0) add(pluralStringResource(R.plurals.topic_broad_chapters_count, chapters, chapters))
    }
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(stringResource(R.string.topic_broad_kicker), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        if (bits.isNotEmpty()) Text(bits.joinToString(" · "), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/**
 * The broad topic's sections, added to the topic page's list: kinds (a mosaic, by group), the story (a dark timeline),
 * people, then the other chapters, one list item each in that order (the page counts them for the ring).
 */
internal fun LazyListScope.broadSections(
    broad: BroadTopic,
    words: app.granger.home.YearWords,
    onOpenArticle: (packId: String, path: String, title: String) -> Unit,
) {
    if (broad.kinds.isNotEmpty()) item(key = "broad-kinds") { Kinds(broad, onOpenArticle) }
    broad.story?.let { s -> item(key = "broad-story") { Story(s, words, onOpenArticle) } }
    if (broad.people.isNotEmpty()) item(key = "broad-people") { People(broad.people, words, onOpenArticle) }
    broad.chapters.forEachIndexed { i, c -> item(key = "broad-chapter-$i") { Chapter(c, i, onOpenArticle) } }
}

@Composable
private fun Section(tag: String, title: String, dark: Boolean = false, content: @Composable () -> Unit) {
    Surface(
        color = if (dark) INK else MaterialTheme.colorScheme.surfaceContainerLowest,
        contentColor = if (dark) Color(0xFFF3EEE4) else MaterialTheme.colorScheme.onSurface,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp).padding(top = 14.dp).testTag(tag),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge.copy(fontFamily = Spectral), modifier = Modifier.semantics { heading() })
            content()
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Kinds(broad: BroadTopic, onOpenArticle: (packId: String, path: String, title: String) -> Unit) {
    var group by remember { mutableIntStateOf(-1) }
    Section(BroadTags.KINDS, stringResource(R.string.topic_broad_kinds_title, broad.under)) {
        if (broad.groups.isNotEmpty()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(group == -1, onClick = { group = -1 }, label = { Text(stringResource(R.string.topic_broad_all)) }, modifier = Modifier.testTag(BroadTags.group(-1)))
                broad.groups.forEachIndexed { i, g ->
                    FilterChip(group == i, onClick = { group = if (group == i) -1 else i }, label = { Text("${g.name} ${g.count}") }, modifier = Modifier.testTag(BroadTags.group(i)))
                }
            }
        }
        val shown = broad.kinds.filter { group == -1 || it.second == group }.map { it.first }.take(KINDS_SHOWN)
        // The first larger, across two columns, then a grid of three: the mockup's editorial mosaic.
        shown.firstOrNull()?.let { first ->
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                KindTile(first, Modifier.weight(2f).height(160.dp), onOpenArticle)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    shown.getOrNull(1)?.let { KindTile(it, Modifier.fillMaxWidth().height(78.dp), onOpenArticle) }
                    shown.getOrNull(2)?.let { KindTile(it, Modifier.fillMaxWidth().height(78.dp), onOpenArticle) }
                }
            }
        }
        for (row in shown.drop(3).chunked(3)) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                for (a in row) KindTile(a, Modifier.weight(1f).height(96.dp), onOpenArticle)
                repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun KindTile(a: TopicArticle, modifier: Modifier, onOpenArticle: (packId: String, path: String, title: String) -> Unit) {
    Box(
        modifier.clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .clickable(onClickLabel = a.title) { onOpenArticle(a.packId, a.path, a.title) }.semantics(mergeDescendants = true) { contentDescription = a.title },
    ) {
        val picture = rememberRailCover(a.picture, 200.dp)
        if (picture != null) Image(picture, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        Box(
            Modifier.fillMaxWidth().align(Alignment.BottomCenter)
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = if (picture != null) 0.78f else 0.35f))))
                .padding(start = 6.dp, end = 6.dp, top = 16.dp, bottom = 5.dp),
        ) {
            Text(a.title, style = MaterialTheme.typography.labelMedium, color = Color.White, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.clearAndSetSemantics {})
        }
    }
}

@Composable
private fun Story(story: BroadStory, words: app.granger.home.YearWords, onOpenArticle: (packId: String, path: String, title: String) -> Unit) {
    Section(BroadTags.STORY, story.title, dark = true) {
        for (e in story.entries) {
            Row(
                Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(RoundedCornerShape(8.dp))
                    .then(if (e.opens != null) Modifier.clickable { onOpenArticle(e.opens.packId, e.opens.path, e.opens.title) } else Modifier)
                    .semantics(mergeDescendants = true) {},
                horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top,
            ) {
                Text(words.year(e.year), style = MaterialTheme.typography.titleSmall.copy(fontFamily = Spectral), color = EMBER, modifier = Modifier.width(52.dp))
                Box(Modifier.width(2.dp).height(56.dp).background(Color(0x33F3EEE4)))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    val picture = rememberRailCover(e.picture, 300.dp)
                    if (picture != null) {
                        Image(picture, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxWidth().height(120.dp).clip(RoundedCornerShape(8.dp)))
                    }
                    Text(
                        e.text,
                        style = if (e.opens == null) MaterialTheme.typography.bodyMedium.copy(fontFamily = Spectral, fontStyle = FontStyle.Italic) else MaterialTheme.typography.bodyMedium,
                        maxLines = 4, overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun People(people: List<BroadPerson>, words: app.granger.home.YearWords, onOpenArticle: (packId: String, path: String, title: String) -> Unit) {
    Section(BroadTags.PEOPLE, stringResource(R.string.topic_broad_people)) {
        for (row in people.take(PEOPLE_SHOWN).chunked(3)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (p in row) {
                    val a = p.article
                    val years = p.mark?.let { words.of(it) }
                    Column(
                        Modifier.weight(1f).clip(RoundedCornerShape(10.dp)).clickable { onOpenArticle(a.packId, a.path, a.title) }
                            .semantics(mergeDescendants = true) { contentDescription = listOfNotNull(a.title, years).joinToString(", ") }.padding(vertical = 4.dp),
                        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        val picture = rememberRailCover(a.picture, 96.dp)
                        Box(Modifier.size(72.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainerHigh), contentAlignment = Alignment.Center) {
                            if (picture != null) Image(picture, null, contentScale = ContentScale.Crop, alignment = Alignment.TopCenter, modifier = Modifier.fillMaxSize())
                            else Text(a.title.take(1), style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Text(a.title, style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.clearAndSetSemantics {})
                        years?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center, modifier = Modifier.clearAndSetSemantics {}) }
                    }
                }
                repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Chapter(c: BroadChapter, index: Int, onOpenArticle: (packId: String, path: String, title: String) -> Unit) {
    Section(BroadTags.chapter(index), c.title) {
        val picture = rememberRailCover(c.picture, 360.dp)
        if (picture != null) {
            Image(picture, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(10.dp)))
            c.caption?.takeIf { it.isNotBlank() }?.let {
                Text(it, style = MaterialTheme.typography.bodySmall.copy(fontFamily = Spectral, fontStyle = FontStyle.Italic), color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            for (l in c.links) {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.heightIn(min = 44.dp).clip(RoundedCornerShape(10.dp)).clickable { onOpenArticle(l.packId, l.path, l.title) },
                ) {
                    Box(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), contentAlignment = Alignment.Center) {
                        Text(l.title, style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
        }
    }
}

/** One disc on the ring: what it opens, its label and picture. */
data class RingStop(val part: BroadPart, val index: Int, val label: String, val picture: String?)

/** The ring's stops, in the page's order: kinds, the story, people, each chapter, close kin. */
@Composable
fun ringStops(broad: BroadTopic, related: List<TopicArticle>): List<RingStop> = buildList {
    if (broad.kinds.isNotEmpty()) add(RingStop(BroadPart.KINDS, 0, stringResource(R.string.topic_broad_kinds), broad.kinds.firstNotNullOfOrNull { it.first.picture }))
    broad.story?.let { add(RingStop(BroadPart.STORY, 0, it.title, it.entries.firstNotNullOfOrNull { e -> e.picture })) }
    if (broad.people.isNotEmpty()) add(RingStop(BroadPart.PEOPLE, 0, stringResource(R.string.topic_broad_people), broad.people.firstNotNullOfOrNull { it.article.picture }))
    broad.chapters.forEachIndexed { i, c -> add(RingStop(BroadPart.CHAPTER, i, c.title, c.picture)) }
    if (related.isNotEmpty()) add(RingStop(BroadPart.CLOSE, 0, stringResource(R.string.topic_closest), related.firstNotNullOfOrNull { it.picture }))
}.take(RING_MAX)

/**
 * C's ring, as a map of the page (Neal's pick: it opens from "As a map"): the topic in the middle, its parts around it;
 * a disc closes the map and goes to that part.
 */
@Composable
fun BroadRing(title: String, picture: String?, stops: List<RingStop>, onStop: (RingStop) -> Unit, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surface, modifier = Modifier.padding(16.dp).testTag(BroadTags.RING)) {
            Column(Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.topic_broad_map_title, title), style = MaterialTheme.typography.titleLarge.copy(fontFamily = Spectral), modifier = Modifier.semantics { heading() })
                val box = 320.dp
                val radius = 116.dp
                Box(Modifier.size(box)) {
                    Box(Modifier.size(box).border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape).padding(48.dp).clearAndSetSemantics {})
                    Disc(picture, 96.dp, Modifier.align(Alignment.Center), selected = true)
                    stops.forEachIndexed { i, s ->
                        val a = -Math.PI / 2 + i * 2 * Math.PI / stops.size
                        val x = radius * cos(a).toFloat()
                        val y = radius * sin(a).toFloat()
                        Column(
                            Modifier.align(Alignment.Center).offset(x = x, y = y).width(84.dp).clip(RoundedCornerShape(12.dp))
                                .clickable { onStop(s) }.semantics(mergeDescendants = true) { role = Role.Button; contentDescription = s.label }
                                .testTag(BroadTags.ring(i)),
                            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            Disc(s.picture, 56.dp)
                            Text(s.label, style = MaterialTheme.typography.labelMedium.copy(fontFamily = Spectral), textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.clearAndSetSemantics {})
                        }
                    }
                }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_close)) }
            }
        }
    }
}

@Composable
private fun Disc(path: String?, size: Dp, modifier: Modifier = Modifier, selected: Boolean = false) {
    val picture = rememberRailCover(path, size)
    Box(
        modifier.size(size).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .border(if (selected) 3.dp else 2.dp, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface, CircleShape),
    ) {
        if (picture != null) Image(picture, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
    }
}

private const val KINDS_SHOWN = 9
private const val PEOPLE_SHOWN = 9
private const val RING_MAX = 8
/** The story's dark ground and its year colour (the brand's ink and ember). */
private val INK = Color(0xFF231C17)
private val EMBER = Color(0xFFE0A86A)
