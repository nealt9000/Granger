package app.granger.topic

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.granger.R
import app.granger.core.library.BookMention
import app.granger.core.library.TimeKind
import app.granger.core.library.TimeMark
import app.granger.ebook.ArticleFooter
import app.granger.home.yearWords
import app.granger.reader.FooterSummary
import app.granger.shell.rememberRailCover
import app.granger.theme.Spectral

object TopicPageTags {
    const val PAGE = "topic_page"
    const val READ_FULL = "topic_read_full"
    const val OTHER = "topic_other_language"
    const val TIME = "topic_time"
    const val BOOKS = "topic_books"
    const val RELATED = "topic_related"
    const val FOUND = "topic_found"
    const val WANDER = "topic_wander"
    const val EXPLORE = "topic_explore"
}

/**
 * The topic page (Plan LL2, layout A as Neal chose it on 2026-11-16): the article's picture, title, years and lead, the
 * way into the full article and the other language, then its time, the reader's books that name it, and the closest
 * topics. A section appears only when it has something; the parts fill in as they arrive. A broad topic (Plan EX:
 * Airplane, Mountain, Economics) adds its kinds, its story, its people and its chapters after the lead, and "As a map"
 * opens them as a ring.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TopicPage(
    topic: Topic,
    packTitle: String?,
    onBack: () -> Unit,
    onReadFull: () -> Unit,
    onOther: (TopicOther) -> Unit,
    onOpenArticle: (packId: String, path: String, title: String) -> Unit,
    onOpenPassage: (bookId: String, mention: BookMention) -> Unit,
    onOpenBookAt: (bookId: String, chapter: String) -> Unit,
    onAllBooks: () -> Unit,
    onShowInTime: (() -> Unit)?,
    modifier: Modifier = Modifier,
    /** The passage a search found in this article: shown first, with the way to it. */
    found: String? = null,
    onWander: (() -> Unit)? = null,
    /** Opens Explore connections (the map); null leaves the button out. */
    onExplore: (() -> Unit)? = null,
) {
    val header = topic.header
    val words = yearWords()
    val list = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var ring by remember { mutableStateOf(false) }
    // Each list item's place, by key, recorded as the list is laid out: the ring scrolls to a part by it.
    val places = remember { HashMap<String, Int>() }
    Surface(modifier.fillMaxSize().testTag(TopicPageTags.PAGE), color = MaterialTheme.colorScheme.background) {
        LazyColumn(state = list, contentPadding = PaddingValues(bottom = 24.dp), modifier = Modifier.statusBarsPadding().navigationBarsPadding()) {
            var at = 0
            places.clear()
            at++
            item {
                Row(Modifier.fillMaxWidth().height(52.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.common_back)) }
                    Text(packTitle.orEmpty(), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            at++
            item {
                // The packs' pictures are often small: a large one fills the width; a small one sits beside the title
                // at a size it can bear, rather than blown up and blurred.
                val picture = rememberRailCover(header?.picture, 480.dp)
                val wide = picture != null && picture.width >= WIDE_PICTURE_PX
                if (wide) {
                    Image(
                        picture!!, contentDescription = null, contentScale = ContentScale.Crop, alignment = Alignment.TopCenter,
                        modifier = Modifier.fillMaxWidth().aspectRatio(PICTURE_RATIO).background(MaterialTheme.colorScheme.surfaceContainer),
                    )
                }
                Row(Modifier.padding(horizontal = 16.dp).padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    if (picture != null && !wide) {
                        Image(
                            picture, contentDescription = null, contentScale = ContentScale.Crop, alignment = Alignment.TopCenter,
                            modifier = Modifier.width(SMALL_PICTURE).aspectRatio(0.75f).clip(RoundedCornerShape(12.dp)),
                        )
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(header?.title ?: TopicSource.titleOf(topic.path), style = MaterialTheme.typography.headlineMedium, modifier = Modifier.semantics { heading() })
                        topic.time?.mark?.let { Span(it, words.of(it)) }
                    }
                }
            }
            at++
            item {
                Column(Modifier.padding(horizontal = 16.dp).padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    topic.broad?.let { BroadSummary(it) }
                    if (!header?.lead.isNullOrBlank()) Text(header!!.lead, style = MaterialTheme.typography.bodyLarge)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = onReadFull, modifier = Modifier.heightIn(min = 48.dp).testTag(TopicPageTags.READ_FULL)) {
                            Text(stringResource(R.string.topic_read_full))
                        }
                        topic.other?.let { other ->
                            OutlinedButton(onClick = { onOther(other) }, modifier = Modifier.heightIn(min = 48.dp).testTag(TopicPageTags.OTHER)) {
                                Text(stringResource(if (other.language == "es") R.string.topic_read_in_spanish else R.string.topic_read_in_english))
                            }
                        }
                        if (topic.broad != null) {
                            OutlinedButton(onClick = { ring = true }, modifier = Modifier.heightIn(min = 48.dp).testTag(BroadTags.MAP)) {
                                Text(stringResource(R.string.topic_broad_map))
                            }
                        }
                        val connections = (topic.related?.size ?: 0) + (topic.time?.people?.size ?: 0) + (topic.time?.events?.size ?: 0)
                        if (onExplore != null && connections >= MIN_CONNECTIONS) {
                            OutlinedButton(onClick = onExplore, modifier = Modifier.heightIn(min = 48.dp).testTag(TopicPageTags.EXPLORE)) {
                                Text(stringResource(R.string.topic_explore))
                            }
                        }
                    }
                }
            }
            found?.let { passage ->
                at++
                item {
                    Card(TopicPageTags.FOUND, R.string.topic_found) {
                        Text("“$passage”", style = MaterialTheme.typography.bodyMedium.copy(fontFamily = Spectral, fontStyle = FontStyle.Italic), maxLines = 5, overflow = TextOverflow.Ellipsis)
                        TextButton(onClick = onReadFull) { Text(stringResource(R.string.topic_found_go)) }
                    }
                }
            }
            topic.broad?.let { broad ->
                val first = at
                broadSections(broad, words, onOpenArticle)
                // The broad sections' places, in the order broadSections adds them.
                var i = first
                if (broad.kinds.isNotEmpty()) places["${BroadPart.KINDS}:0"] = i++
                if (broad.story != null) places["${BroadPart.STORY}:0"] = i++
                if (broad.people.isNotEmpty()) places["${BroadPart.PEOPLE}:0"] = i++
                broad.chapters.indices.forEach { c -> places["${BroadPart.CHAPTER}:$c"] = i++ }
                at = i
            }
            topic.time?.takeIf { it.people.isNotEmpty() || it.events.isNotEmpty() }?.let { time ->
                at++
                item { TimeCard(time, words, onOpenArticle, onShowInTime) }
            }
            topic.books?.takeIf { it.inBooks.isNotEmpty() }?.let { books ->
                at++
                item { BooksCard(books, topic.quotes, onOpenPassage, onAllBooks) }
            }
            val related = topic.related.orEmpty()
            val relatedBooks = topic.relatedBooks.orEmpty()
            if (related.isNotEmpty() || relatedBooks.isNotEmpty()) {
                places["${BroadPart.CLOSE}:0"] = at++
                item { RelatedCard(related, relatedBooks, onOpenArticle, onOpenBookAt) }
            }
            onWander?.let { wander ->
                item {
                    OutlinedButton(onClick = wander, modifier = Modifier.padding(horizontal = 12.dp).padding(top = 14.dp).heightIn(min = 48.dp).testTag(TopicPageTags.WANDER)) {
                        Text(stringResource(R.string.topic_wander))
                    }
                }
            }
        }
        val broad = topic.broad
        if (ring && broad != null) {
            BroadRing(
                header?.title ?: TopicSource.titleOf(topic.path), header?.picture, ringStops(broad, topic.related.orEmpty()),
                onStop = { s -> ring = false; places["${s.part}:${s.index}"]?.let { i -> scope.launch { list.animateScrollToItem(i) } } },
                onDismiss = { ring = false },
            )
        }
    }
}

/** A life or span as a line of years with a bar under it: "1769 – 1821 · aged 51". */
@Composable
private fun Span(mark: TimeMark, years: String) {
    val age = if (mark.kind == TimeKind.LIFE) mark.y1?.let { end -> ageOf(mark.y0, mark.md0, end, mark.md1 ?: 0) } else null
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            if (age != null) stringResource(R.string.topic_years_aged, years, age) else years,
            style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary,
        )
        if (mark.kind != TimeKind.POINT) {
            Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(MaterialTheme.colorScheme.primary))
        }
    }
}

@Composable
private fun Card(tag: String, title: Int, action: (@Composable () -> Unit)? = null, content: @Composable () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp).padding(top = 14.dp).testTag(tag),
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(title), style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f).semantics { heading() })
                action?.invoke()
            }
            content()
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TimeCard(
    time: TopicTimeView,
    words: app.granger.home.YearWords,
    onOpenArticle: (packId: String, path: String, title: String) -> Unit,
    onShowInTime: (() -> Unit)?,
) {
    val title = if (time.mark.kind == TimeKind.LIFE) R.string.topic_their_time else R.string.topic_same_time
    Card(TopicPageTags.TIME, title, action = onShowInTime?.let { open -> { TextButton(onClick = open) { Text(stringResource(R.string.topic_open_timeline)) } } }) {
        if (time.people.isNotEmpty()) {
            Text(stringResource(R.string.topic_people_overlapped), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            val rows = (if (time.mark.kind == TimeKind.LIFE) listOf(time.mark) else emptyList()) + time.people
            val now = java.time.Year.now().value
            val from = rows.minOf { it.y0 }
            val to = rows.maxOf { it.end(now) }.coerceAtLeast(from + 1)
            for (p in rows) {
                val me = p.qid == time.mark.qid
                val label = "${TopicSource.titleOf(p.path)}, ${words.of(p)}"
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 40.dp).clickable(enabled = !me) { onOpenArticle(p.packId, p.path, p.title) }
                        .semantics(mergeDescendants = true) { contentDescription = label },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Face(time.pictures[p.path], p.title)
                    Text(
                        TopicSource.titleOf(p.path), style = MaterialTheme.typography.bodySmall,
                        fontWeight = if (me) FontWeight.SemiBold else FontWeight.Normal,
                        maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.width(92.dp).clearAndSetSemantics {},
                    )
                    BoxWithConstraints(Modifier.weight(1f).height(10.dp).clip(RoundedCornerShape(5.dp)).background(MaterialTheme.colorScheme.surfaceContainer).clearAndSetSemantics {}) {
                        val start = maxWidth * ((p.y0 - from).toFloat() / (to - from))
                        val width = maxWidth * ((p.end(now) - p.y0).toFloat() / (to - from))
                        Box(
                            Modifier.offset(x = start).width(width.coerceAtLeast(4.dp)).height(10.dp).clip(RoundedCornerShape(5.dp))
                                .background(if (me) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.tertiary),
                        )
                    }
                }
            }
        }
        if (time.events.isNotEmpty()) {
            Text(stringResource(R.string.topic_events), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                for (e in time.events) {
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.heightIn(min = 44.dp).clip(RoundedCornerShape(10.dp)).clickable { onOpenArticle(e.packId, e.path, e.title) },
                    ) {
                        Column(Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
                            Text(TopicSource.titleOf(e.path), style = MaterialTheme.typography.labelLarge)
                            Text(words.of(e), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Face(path: String?, title: String) {
    val picture = rememberRailCover(path, 32.dp)
    Box(Modifier.size(28.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainerHighest), contentAlignment = Alignment.Center) {
        if (picture != null) Image(picture, null, contentScale = ContentScale.Crop, alignment = Alignment.TopCenter, modifier = Modifier.size(28.dp))
        else Text(title.take(1), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun BooksCard(books: ArticleFooter, quotes: Map<String, String>, onOpenPassage: (bookId: String, mention: BookMention) -> Unit, onAllBooks: () -> Unit) {
    Card(TopicPageTags.BOOKS, R.string.topic_in_books, action = { TextButton(onClick = onAllBooks) { Text(stringResource(R.string.topic_all_books, books.inBooksTotal)) } }) {
        Text(
            pluralStringResource(R.plurals.topic_named_in_books, books.inBooksTotal, books.inBooksTotal),
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        for (hit in books.inBooks.take(TopicSource.QUOTES)) {
            val m = hit.passages.firstOrNull() ?: continue
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable { onOpenPassage(hit.book.bookId, m) }.padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                val cover = rememberRailCover(hit.book.cover, 52.dp)
                Box(Modifier.size(width = 52.dp, height = 78.dp).clip(RoundedCornerShape(4.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh)) {
                    if (cover != null) Image(cover, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        quotes[hit.book.bookId]?.let { "“$it”" } ?: "“${FooterSummary.lead(m)}${m.quote}…”",
                        style = MaterialTheme.typography.bodyMedium.copy(fontFamily = Spectral, fontStyle = FontStyle.Italic),
                        maxLines = 4, overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        "${hit.book.title} · ${pluralStringResource(R.plurals.reader_footer_mentions, hit.count, hit.count)}",
                        style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun RelatedCard(
    related: List<TopicArticle>,
    books: List<TopicBook>,
    onOpenArticle: (packId: String, path: String, title: String) -> Unit,
    onOpenBookAt: (bookId: String, chapter: String) -> Unit,
) {
    Card(TopicPageTags.RELATED, R.string.topic_closest) {
        for (pair in related.take(RELATED_SHOWN).chunked(2)) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                for (a in pair) RelatedTile(a, Modifier.weight(1f)) { onOpenArticle(a.packId, a.path, a.title) }
                if (pair.size == 1) Spacer(Modifier.weight(1f))
            }
        }
        if (books.isNotEmpty()) {
            Text(stringResource(R.string.topic_near_in_books), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            for (b in books) {
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(RoundedCornerShape(8.dp)).clickable { onOpenBookAt(b.book.bookId, b.chapter) },
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    val cover = rememberRailCover(b.book.cover, 32.dp)
                    Box(Modifier.size(width = 32.dp, height = 48.dp).clip(RoundedCornerShape(3.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh)) {
                        if (cover != null) Image(cover, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                    }
                    Text(b.book.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

@Composable
private fun RelatedTile(a: TopicArticle, modifier: Modifier, onOpen: () -> Unit) {
    Column(modifier.clip(RoundedCornerShape(10.dp)).clickable(onClick = onOpen).padding(bottom = 4.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        val picture = rememberRailCover(a.picture, 160.dp)
        Box(Modifier.fillMaxWidth().height(84.dp).clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh), contentAlignment = Alignment.Center) {
            if (picture != null) Image(picture, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            else Text(a.title.take(1), style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(a.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
        if (a.blurb.isNotBlank()) {
            Text(a.blurb, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 3, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** Age at death from birth and death years and month×100+day (0 when unknown): Napoleon, 15 Aug 1769 – 5 May 1821, is 51. */
internal fun ageOf(y0: Long, md0: Int, y1: Long, md1: Int): Int {
    // Years are astronomical (1 BC = 0), so the difference is right across the era.
    val years = (y1 - y0).toInt()
    return if (md0 != 0 && md1 != 0 && md1 < md0) years - 1 else years
}

private const val PICTURE_RATIO = 1.15f
/** The map needs a few circles to be worth opening. */
private const val MIN_CONNECTIONS = 3
/** A picture at least this wide (px, as loaded) fills the width; a smaller one sits beside the title. */
private const val WIDE_PICTURE_PX = 600
private val SMALL_PICTURE = 112.dp
private const val RELATED_SHOWN = 6
