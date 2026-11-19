package app.granger.topic

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.granger.R
import app.granger.core.library.BookMention
import app.granger.explore.Captions
import app.granger.explore.DoButton
import app.granger.explore.Door
import app.granger.explore.DoorKind
import app.granger.explore.DoorsBar
import app.granger.explore.Eyebrow
import app.granger.explore.FramedPicture
import app.granger.explore.Plate
import app.granger.explore.PlateRowKind
import app.granger.explore.PlateRowView
import app.granger.explore.PlateView
import app.granger.explore.SpreadColors
import app.granger.explore.SpreadHead
import app.granger.explore.SpreadType
import app.granger.explore.Subject
import app.granger.explore.Trail
import app.granger.explore.TrailStep
import app.granger.explore.WayOn
import app.granger.explore.WaysOn
import app.granger.explore.WholePicture
import app.granger.explore.firstSentence
import app.granger.explore.openingRow
import app.granger.explore.samePicture
import app.granger.home.ArticleThumbnails
import app.granger.home.yearWords
import app.granger.theme.GrangerTheme

object BroadTags {
    const val DIAGRAM = "broad_diagram"
    const val KINDS = "broad_kinds"
    const val STORY = "broad_story"
    const val PEOPLE = "broad_people"
    const val BESIDE = "broad_beside"
    const val CHAPTERS = "broad_chapters"
    fun familyAll(i: Int) = "broad_family_all_$i"
    fun column(i: Int) = "broad_column_$i"
    fun link(path: String) = "broad_link_$path"
}

/**
 * A broad topic as a spread (Plan EX round 2, D5): the trail; its picture and two of its figures as numbered plates;
 * its name, opening sentence and captions; a diagram from the article; its kinds by family; its story by year; the
 * people near it; the room's other broad topics side by side; its other chapters; the reader's books that name it;
 * ways on; and, at the foot, the doors to the topics before and after it in its room. Each part appears when it has
 * something, as it arrives; every heading is filled from the index.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun BroadSpread(
    topic: Topic,
    onReadFull: () -> Unit,
    onOther: (TopicOther) -> Unit,
    onOpenArticle: (packId: String, path: String, title: String) -> Unit,
    onOpenPassage: (bookId: String, mention: BookMention) -> Unit,
    onAllBooks: () -> Unit,
    modifier: Modifier = Modifier,
    found: String? = null,
    onWander: (() -> Unit)? = null,
    onExplore: (() -> Unit)? = null,
    onOpenPlace: ((subject: String?, room: String?) -> Unit)? = null,
) {
    val header = topic.header
    val broad = topic.broad
    val title = header?.title ?: TopicSource.titleOf(topic.path)
    val words = yearWords()
    val locale = LocalConfiguration.current.locales[0]
    val place = broad?.place
    val open: (TopicArticle) -> Unit = { a -> onOpenArticle(a.packId, a.path, a.title) }
    Surface(modifier.fillMaxSize().testTag(TopicPageTags.PAGE), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.statusBarsPadding().navigationBarsPadding()) {
            val steps = buildList {
                if (onOpenPlace != null) {
                    add(TrailStep(stringResource(R.string.explore_title)) { onOpenPlace(null, null) })
                    val subject = place?.let { Subject.of(it.subject) }
                    if (place != null && subject != null) {
                        add(TrailStep(stringResource(subject.label)) { onOpenPlace(place.subject, null) })
                        place.room?.let { room -> add(TrailStep(room.name(locale.language)) { onOpenPlace(place.subject, room.id) }) }
                    }
                }
            }
            Trail(steps, here = title)
            LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 28.dp)) {
                // The opening: the topic's own picture, and two of its figures that aren't that picture again.
                val lead = header?.picture
                val figures = broad?.figures.orEmpty().filterNot { samePicture(it.picture, lead) }.take(2).takeIf { it.size == 2 }.orEmpty()
                val plates = listOfNotNull(lead?.let { Plate("lead", title, it, whole = ArticleThumbnails.isWhole(it)) }) +
                    figures.mapIndexed { i, f -> Plate("figure:$i", f.caption, f.picture, whole = ArticleThumbnails.isWhole(f.picture)) }
                if (header == null || topic.broadPending) {
                    // The plates' place, while they are read.
                    item(key = "opening") { Box(Modifier.fillMaxWidth().aspectRatio(PlateRowKind.OPENING.ratio).background(MaterialTheme.colorScheme.surfaceContainer)) }
                } else openingRow(plates)?.let { row ->
                    item(key = "opening") { PlateRowView(row, onOpen = { onReadFull() }, firstNumber = if (plates.size > 1) 1 else null) }
                }
                item(key = "head") {
                    Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        SpreadHead(
                            topic.time?.mark?.let { words.of(it) }, title,
                            standfirst = header?.lead?.takeIf { it.isNotBlank() }?.let { firstSentence(it, 240) }, large = true,
                        )
                        if (plates.size > 1) Captions(plates.map { it.title })
                        app.granger.online.KeepHint(topic.packId, topic.path)
                        FlowRow(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = onReadFull, shape = RoundedCornerShape(2.dp), modifier = Modifier.heightIn(min = 48.dp).testTag(TopicPageTags.READ_FULL),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = SpreadColors.enamel,
                                    contentColor = if (GrangerTheme.extras.night) MaterialTheme.colorScheme.onSecondary else MaterialTheme.colorScheme.onPrimary,
                                ),
                            ) { Text(stringResource(R.string.topic_spread_read), style = SpreadType.uiMedium.copy(fontSize = 14.sp)) }
                            val connections = (topic.related?.size ?: 0) + (topic.time?.people?.size ?: 0) + (topic.time?.events?.size ?: 0)
                            // A broad topic always has something round it: its own parts.
                            if (onExplore != null && (broad != null || connections >= MIN_CONNECTIONS)) DoButton(stringResource(R.string.topic_spread_connections), TopicPageTags.EXPLORE, onExplore)
                            topic.other?.let { other ->
                                DoButton(stringResource(if (other.language == "es") R.string.topic_read_in_spanish else R.string.topic_read_in_english), TopicPageTags.OTHER) { onOther(other) }
                            }
                            app.granger.online.KeepButton(topic.packId, topic.path)
                            app.granger.online.AvailabilityMark(topic.packId, topic.path, Modifier.align(Alignment.CenterVertically))
                        }
                    }
                }
                found?.let { passage ->
                    item(key = "found") {
                        Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 26.dp).testTag(TopicPageTags.FOUND), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Eyebrow(stringResource(R.string.topic_found))
                            Text("“$passage”", style = SpreadType.italic.copy(fontSize = 15.sp, lineHeight = 22.sp), maxLines = 5, overflow = TextOverflow.Ellipsis)
                            Link(stringResource(R.string.topic_found_go), onReadFull)
                        }
                    }
                }
                if (broad != null) {
                    broad.diagram?.let { d -> item(key = "diagram") { Diagram(d, number = plates.size + 1) } }
                    kinds(broad, open)
                    broad.story?.let { s -> item(key = "story") { Story(s, words, open) } }
                    if (broad.people.isNotEmpty()) item(key = "people") { People(broad.people, words, open) }
                    if (broad.beside.size > 1) item(key = "beside") { Beside(broad.beside, title, lead, open) }
                    if (broad.chapters.isNotEmpty()) item(key = "chapters") { Chapters(broad.chapters, open) }
                }
                topic.books?.takeIf { it.inBooks.isNotEmpty() }?.let { books ->
                    item(key = "books") { BooksCard(books, topic.quotes, onOpenPassage, onAllBooks) }
                }
                item(key = "ways") {
                    val close = topic.related?.firstOrNull()
                    val step = broad?.story?.let { s -> s.entries.firstNotNullOfOrNull { it.opens }?.let { s.title to it } }
                    val ways = buildList {
                        close?.let { add(WayOn(stringResource(R.string.topic_spread_way_close), it.title) { open(it) }) }
                        step?.let { (chapter, a) -> add(WayOn(stringResource(R.string.topic_spread_way_chapter, chapter), a.title) { open(a) }) }
                        onWander?.let { add(WayOn(stringResource(R.string.topic_spread_way_here), stringResource(R.string.explore_subject_wander), it)) }
                    }
                    if (ways.isNotEmpty()) WaysOn(stringResource(R.string.explore_ways_on), ways, Modifier.padding(top = 30.dp))
                }
            }
            if (place != null && place.total > 1 && (place.before != null || place.after != null)) {
                DoorsBar(
                    DoorKind.TOPIC, place.before?.let { a -> Door(a.title) { open(a) } }, place.after?.let { a -> Door(a.title) { open(a) } },
                    position = place.position, total = place.total,
                )
            }
        }
    }
}

/** The topic's map needs a few circles to be worth opening. */
private const val MIN_CONNECTIONS = 3

@Composable
private fun Link(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(modifier.heightIn(min = 48.dp).clickable(role = Role.Button, onClick = onClick), contentAlignment = Alignment.CenterStart) {
        Text(text, style = SpreadType.uiMedium.copy(fontSize = 14.sp), color = SpreadColors.accent)
    }
}

/** The diagram plate: a drawing from the article, whole on white paper, numbered after the opening plates, with its caption. */
@Composable
private fun Diagram(d: BroadFigure, number: Int) {
    Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 32.dp).testTag(BroadTags.DIAGRAM), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Eyebrow(d.chapter?.let { stringResource(R.string.topic_spread_plate_from, number, it) } ?: stringResource(R.string.topic_spread_plate, number))
        // White in Field and in Night: a drawing keeps its own ground.
        Box(Modifier.fillMaxWidth().height(190.dp).background(Color.White).padding(10.dp).clearAndSetSemantics {}) { WholePicture(d.picture, 320.dp, Modifier.fillMaxSize()) }
        Text(d.caption, style = SpreadType.italic.copy(fontSize = 14.sp, lineHeight = 20.sp), color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Names as a sentence lists them in the reader's language ("jets, transports and combat airplanes"). */
private fun listed(names: List<String>, locale: java.util.Locale): String =
    runCatching { android.icu.text.ListFormatter.getInstance(locale).format(names) }.getOrNull()?.takeIf { it.isNotBlank() } ?: names.joinToString(", ")

/** The kinds: a head that says how many the library holds and names the largest families, then each family's plates. */
private fun LazyListScope.kinds(broad: BroadTopic, open: (TopicArticle) -> Unit) {
    if (!broad.hasKinds) return
    item(key = "kinds") {
        val locale = LocalConfiguration.current.locales[0]
        val named = broad.families.mapNotNull { it.name }
        val families = pluralStringResource(R.plurals.topic_spread_families, broad.groups.size, broad.groups.size)
        val heading = when {
            named.isEmpty() -> pluralStringResource(R.plurals.topic_broad_kinds_count, broad.under, broad.under)
            broad.groups.size <= named.size -> stringResource(R.string.topic_spread_families_named, families, listed(named, locale))
            else -> stringResource(R.string.topic_spread_families_largest, families, listed(named, locale))
        }
        SpreadHead(
            pluralStringResource(R.plurals.topic_spread_kinds_eyebrow, broad.under, broad.under), heading,
            Modifier.padding(start = 16.dp, end = 16.dp, top = 34.dp, bottom = 4.dp).testTag(BroadTags.KINDS),
        )
    }
    broad.families.forEachIndexed { i, family ->
        if (family.all.isNotEmpty()) item(key = "family:$i") { Family(family, i, named = broad.families.any { it.name != null }, open) }
    }
}

/** One family: its name, the way to all of it, and its first kinds as plates; all of it opens as a list of names under them. */
@Composable
private fun Family(family: BroadFamily, index: Int, named: Boolean, open: (TopicArticle) -> Unit) {
    var all by rememberSaveable { mutableStateOf(false) }
    val more = family.all.size > family.plates.size
    Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                family.name ?: stringResource(if (named) R.string.topic_spread_family_others else R.string.topic_broad_kinds),
                style = SpreadType.display.copy(fontSize = 17.sp, lineHeight = 21.sp), color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f).semantics { heading() },
            )
            if (more) {
                Link(
                    if (all) stringResource(R.string.topic_spread_family_fewer) else stringResource(R.string.topic_spread_family_all, family.all.size),
                    { all = !all }, Modifier.testTag(BroadTags.familyAll(index)),
                )
            }
        }
        for (row in family.plates.chunked(BroadTopics.FAMILY_PLATES)) {
            Row(Modifier.fillMaxWidth().padding(bottom = 2.dp).aspectRatio(334f / 82f), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                for (a in row) {
                    val plate = Plate("kind:${a.packId}/${a.path}", a.title, a.picture, whole = ArticleThumbnails.isWhole(a.picture), article = a.packId to a.path)
                    PlateView(plate, 12.sp, null, Modifier.weight(1f).fillMaxHeight(), short = true) { open(a) }
                }
                repeat(BroadTopics.FAMILY_PLATES - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
        if (all) Column(Modifier.padding(horizontal = 16.dp)) {
            for (a in family.all) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Box(Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(role = Role.Button) { open(a) }.testTag(BroadTags.link(a.path)), contentAlignment = Alignment.CenterStart) {
                    Text(a.title, style = SpreadType.serif.copy(fontSize = 15.sp, lineHeight = 20.sp), color = MaterialTheme.colorScheme.onSurface)
                }
            }
        }
    }
}

/** The story: the article's most dated chapter, a line a year: the year, its picture, and the caption or the linked topic. */
@Composable
private fun Story(story: BroadStory, words: app.granger.home.YearWords, open: (TopicArticle) -> Unit) {
    Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 34.dp).testTag(BroadTags.STORY), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        val first = story.entries.first().year
        val last = story.entries.last().year
        SpreadHead(
            stringResource(R.string.topic_spread_chapter, story.title),
            if (first != last) stringResource(R.string.topic_spread_years, words.year(first), words.year(last)) else story.title,
        )
        for (e in story.entries) {
            val year = words.year(e.year)
            Row(
                Modifier.fillMaxWidth().heightIn(min = 48.dp)
                    .then(if (e.opens != null) Modifier.clickable(role = Role.Button) { open(e.opens) } else Modifier)
                    .semantics(mergeDescendants = true) {},
                horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top,
            ) {
                Text(year, style = SpreadType.display.copy(fontSize = 14.sp, lineHeight = 18.sp), color = SpreadColors.accent, textAlign = TextAlign.End, modifier = Modifier.width(64.dp).padding(top = 2.dp))
                if (e.picture != null) {
                    val frame = Modifier.width(112.dp).height(76.dp).clearAndSetSemantics {}
                    if (ArticleThumbnails.isWhole(e.picture)) WholePicture(e.picture, 200.dp, frame.background(Color.White).padding(4.dp)) else FramedPicture(e.picture, 200.dp, frame)
                }
                Text(
                    e.text, style = (if (e.opens == null) SpreadType.italic else SpreadType.serif).copy(fontSize = 13.5.sp, lineHeight = 18.sp),
                    color = if (e.opens == null) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                    maxLines = 5, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

/** The people near the topic: how many, then the best known as upright plates with their years. */
@Composable
private fun People(people: List<BroadPerson>, words: app.granger.home.YearWords, open: (TopicArticle) -> Unit) {
    Column(Modifier.padding(top = 34.dp).testTag(BroadTags.PEOPLE)) {
        SpreadHead(
            stringResource(R.string.topic_broad_people), pluralStringResource(R.plurals.topic_spread_people, people.size, people.size),
            Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
        )
        for (row in people.take(PEOPLE_SHOWN).chunked(3)) {
            Row(Modifier.fillMaxWidth().padding(bottom = 2.dp).aspectRatio(PlateRowKind.THREE_TALL.ratio), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                for (p in row) {
                    val a = p.article
                    val plate = Plate("person:${a.packId}/${a.path}", a.title, a.picture, whole = ArticleThumbnails.isWhole(a.picture), sub = p.mark?.let { words.of(it) }, article = a.packId to a.path)
                    PlateView(plate, 12.5.sp, null, Modifier.weight(1f).fillMaxHeight(), short = true) { open(a) }
                }
                repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

private const val PEOPLE_SHOWN = 6

/**
 * Side by side: this topic and the room's two best-known other broad topics, a column each, a row for each thing a
 * broad topic's page holds. The head names the one with the most kinds. A column opens its topic.
 */
@Composable
private fun Beside(columns: List<BroadColumn>, title: String, picture: String?, open: (TopicArticle) -> Unit) {
    val locale = LocalConfiguration.current.locales[0]
    // The first column is this topic: its name and picture are the page's own.
    val heads = columns.mapIndexed { i, c -> if (i == 0) c.article.copy(title = title, picture = picture) else c.article }
    val most = columns.indices.maxByOrNull { columns[it].kinds }?.takeIf { columns[it].kinds > 0 }
    Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 34.dp).testTag(BroadTags.BESIDE)) {
        SpreadHead(
            stringResource(R.string.topic_spread_beside),
            if (most != null) stringResource(R.string.topic_spread_beside_most, heads[most].title, columns[most].kinds) else stringResource(R.string.topic_spread_beside_plain),
            Modifier.padding(bottom = 14.dp),
        )
        Row(Modifier.padding(start = LABEL + 6.dp, bottom = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            heads.forEachIndexed { i, a ->
                Column(
                    Modifier.weight(1f).heightIn(min = 48.dp).then(if (i == 0) Modifier else Modifier.clickable(role = Role.Button) { open(a) })
                        .semantics(mergeDescendants = true) {}.testTag(BroadTags.column(i)),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    if (ArticleThumbnails.isWhole(a.picture)) WholePicture(a.picture, 160.dp, Modifier.fillMaxWidth().height(52.dp).background(Color.White).padding(3.dp).clearAndSetSemantics {})
                    else FramedPicture(a.picture, 160.dp, Modifier.fillMaxWidth().height(52.dp).background(MaterialTheme.colorScheme.surfaceContainer).clearAndSetSemantics {})
                    Text(a.title, style = SpreadType.display.copy(fontSize = 15.sp, lineHeight = 18.sp), color = MaterialTheme.colorScheme.onSurface, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        @Composable fun line(label: Int, values: List<String>, big: Boolean) {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Row(Modifier.padding(vertical = 8.dp).semantics(mergeDescendants = true) {}, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(stringResource(label), style = SpreadType.ui.copy(fontSize = 11.sp, lineHeight = 14.sp), color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(LABEL).padding(top = if (big) 6.dp else 1.dp))
                for (v in values) {
                    Text(
                        v, style = if (big) SpreadType.display.copy(fontSize = 20.sp, lineHeight = 24.sp) else SpreadType.serif.copy(fontSize = 12.sp, lineHeight = 16.sp),
                        color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f),
                    )
                }
            }
        }
        fun n(i: Int) = app.granger.explore.count(i)
        val none = "–"
        line(R.string.topic_spread_row_kinds, columns.map { n(it.kinds) }, big = true)
        if (columns.any { it.families.isNotEmpty() }) line(R.string.topic_spread_row_families, columns.map { it.families.takeIf { f -> f.isNotEmpty() }?.let { f -> listed(f, locale) } ?: none }, big = false)
        if (columns.any { it.people.isNotEmpty() }) line(R.string.topic_spread_row_people, columns.map { it.people.takeIf { p -> p.isNotEmpty() }?.joinToString(", ") ?: none }, big = false)
        line(R.string.topic_spread_row_chapters, columns.map { n(it.chapters) }, big = true)
        line(R.string.topic_spread_row_inlinks, columns.map { n(it.inlinks) }, big = true)
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Text(stringResource(R.string.topic_spread_beside_rule), style = SpreadType.ui.copy(fontSize = 12.sp, lineHeight = 17.sp), color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
    }
}

private val LABEL = 72.dp

/** The article's other chapters: each its title on a ruled line, and the topics it links, each a way on. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Chapters(chapters: List<BroadChapter>, open: (TopicArticle) -> Unit) {
    Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 34.dp).testTag(BroadTags.CHAPTERS)) {
        SpreadHead(stringResource(R.string.topic_spread_chapters), stringResource(R.string.topic_spread_chapters_title), Modifier.padding(bottom = 10.dp))
        for (c in chapters) {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Text(c.title, style = SpreadType.display.copy(fontSize = 17.sp, lineHeight = 21.sp), color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.padding(top = 10.dp).semantics { heading() })
            FlowRow(Modifier.padding(bottom = 4.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                for (l in c.links) {
                    Box(Modifier.heightIn(min = 48.dp).clickable(role = Role.Button) { open(l) }.testTag(BroadTags.link(l.path)), contentAlignment = Alignment.Center) {
                        Text(l.title, style = SpreadType.uiMedium.copy(fontSize = 13.5.sp), color = SpreadColors.accent)
                    }
                }
            }
        }
    }
}
