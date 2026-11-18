package app.granger.explore

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.granger.R

/**
 * A subject (Plan EX round 2, D2): the trail at the head; up to three opening plates, numbered, over the subject's name,
 * its own article's opening sentence and the plates' captions; Wander and Quiz; its rooms as plates in pairs, the wide
 * side swapping row by row, each over a sentence from the room's own dates (its description when it has none), under a
 * heading that states what the subject's dates say; the way into all its topics; in your books; and, always at the
 * foot, the doors to the subjects either side. With an index from before rooms, its best-known articles.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SubjectPageContent(ui: SubjectUi?, actions: ExploreActions, subject: SubjectActions = SubjectActions()) {
    val language = LocalConfiguration.current.locales[0].language
    val name = ui?.let { stringResource(it.subject.label) }.orEmpty()
    Scaffold(
        topBar = {
            Box(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.background).windowInsetsPadding(TopAppBarDefaults.windowInsets)) {
                if (ui != null) Trail(listOf(TrailStep(stringResource(R.string.explore_title), subject.onOpenExplore)), here = name)
            }
        },
        bottomBar = {
            val doors = ui?.doors
            if (doors != null && doors.total > 1) {
                @Composable fun door(s: Subject?) = s?.let { Door(stringResource(it.label)) { subject.onOpenDoor(it.id) } }
                DoorsBar(DoorKind.SUBJECT, door(doors.before), door(doors.after), doors.position, doors.total)
            }
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        val loaded = ui ?: return@Scaffold
        val words = app.granger.home.yearWords()
        val cards = loaded.facets
        LazyColumn(Modifier.padding(padding).fillMaxSize(), contentPadding = PaddingValues(bottom = 28.dp)) {
            val opening = loaded.opening.map { a -> Plate("opening:" + a.path, a.title, a.picture, a.ratio, a.whole) }
            openingRow(opening)?.let { row ->
                item(key = "opening") {
                    val byKey = loaded.opening.associateBy { "opening:" + it.path }
                    PlateRowView(row, { p -> byKey[p.key]?.let { actions.onOpenArticle(it.packId, it.path, it.title) } }, firstNumber = 1)
                }
            }
            item(key = "head") {
                Column(Modifier.padding(start = 16.dp, end = 16.dp, top = if (opening.isEmpty()) 4.dp else 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    val eyebrow = if (cards.isEmpty()) stringResource(R.string.explore_subject_a)
                    else stringResource(R.string.explore_subject_a_rooms, pluralStringResource(R.plurals.explore_rooms, cards.size, count(cards.size)))
                    SpreadHead(eyebrow, name, standfirst = loaded.hero?.sentence?.let { firstSentence(it, 240) }, large = true)
                    if (opening.isNotEmpty()) Captions(opening.map { it.title })
                    if (loaded.canWander || loaded.canQuiz) {
                        Row(Modifier.padding(top = 2.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (loaded.canWander) DoButton(stringResource(R.string.explore_subject_wander), SubjectTags.WANDER, subject.onWander)
                            if (loaded.canQuiz) DoButton(stringResource(R.string.explore_subject_quiz), SubjectTags.QUIZ, subject.onQuiz)
                        }
                    }
                }
            }
            if (cards.isNotEmpty()) {
                item(key = "rooms-head") {
                    val rooms = pluralStringResource(R.plurals.explore_rooms, cards.size, count(cards.size))
                    // What the subject's dates say, when they say anything; else how much the rooms hold.
                    val said = loaded.finding?.sentences(words, oldest = false)?.firstOrNull()
                    val heading = if (said != null) stringResource(R.string.explore_subject_rooms_finding, rooms, said)
                    else stringResource(R.string.explore_subject_rooms_topics, rooms, pluralStringResource(R.plurals.explore_topics, loaded.count, count(loaded.count)))
                    SpreadHead(stringResource(R.string.explore_subject_rooms_eyebrow), heading, Modifier.padding(start = 16.dp, end = 16.dp, top = 30.dp, bottom = 12.dp))
                }
                val pairs = cards.chunked(2)
                items(pairs.size, key = { "rooms:" + pairs[it].first().facet.id }) { i ->
                    RoomPair(pairs[i], wideFirst = i % 2 == 0, language, words) { subject.onOpenFacet(it) }
                }
            } else if (loaded.articles.isNotEmpty()) {
                // An index from before rooms: the best-known articles, as the page was.
                item(key = "articles-head") { Eyebrow(stringResource(R.string.explore_subject_articles), Modifier.padding(start = 16.dp, end = 16.dp, top = 28.dp, bottom = 4.dp).semantics { heading() }) }
                items(loaded.articles.take(FALLBACK_ARTICLES), key = { "a:" + it.packId + "/" + it.path }) { a ->
                    ArticleRow(a, words, subject.picture) { actions.onOpenArticle(a.packId, a.path, a.title) }
                }
            }
            if (loaded.count > 0) {
                item(key = "see-all") {
                    Box(
                        Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(role = Role.Button) { subject.onOpenFacet(null) }.padding(horizontal = 16.dp).testTag(SubjectTags.SEE_ALL),
                        contentAlignment = Alignment.CenterStart,
                    ) { Text(pluralStringResource(R.plurals.explore_see_all, loaded.count, count(loaded.count)), style = SpreadType.uiMedium.copy(fontSize = 14.sp), color = SpreadColors.accent) }
                }
            }
            if (loaded.books.isNotEmpty()) {
                item(key = "books-head") {
                    Eyebrow(
                        pluralStringResource(R.plurals.explore_subject_books, loaded.books.size, count(loaded.books.size)),
                        Modifier.padding(start = 16.dp, end = 16.dp, top = 24.dp, bottom = 10.dp).semantics { heading() }, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                item(key = "books") {
                    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        items(loaded.books.take(BOOKS_ON_PAGE), key = { it.bookId }) { BookCover(it) { subject.onOpenBook(it.bookId) } }
                    }
                }
            }
            if (loaded.collections.isNotEmpty()) {
                item(key = "collections") {
                    Lines(stringResource(R.string.explore_subject_collections), loaded.collections.map { c ->
                        Line(c.title, if (c.books > 0) pluralStringResource(R.plurals.explore_collection_books, c.books, count(c.books)) else null, SubjectTags.collection(c.id)) { subject.onOpenCollection(c.id) }
                    })
                }
            }
            if (loaded.packs.isNotEmpty()) {
                item(key = "packs") {
                    Lines(stringResource(R.string.explore_subject_packs), loaded.packs.map { pack -> Line(pack.title, null, "subject_pack_" + pack.id) { actions.onOpenPack(pack.id) } })
                }
            }
            if (loaded.count == 0 && loaded.packs.isEmpty() && loaded.collections.isEmpty()) {
                item(key = "empty") { Text(stringResource(R.string.explore_nothing_here), style = SpreadType.serif.copy(fontSize = 15.sp), modifier = Modifier.padding(16.dp)) }
            }
        }
    }
}

private const val FALLBACK_ARTICLES = 12
private const val BOOKS_ON_PAGE = 24

/** Wander or Quiz: a square-cornered outlined button in the spread's own type. */
@Composable
internal fun DoButton(label: String, tag: String, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, shape = RoundedCornerShape(2.dp), modifier = Modifier.heightIn(min = 48.dp).testTag(tag)) {
        Text(label, style = SpreadType.uiMedium.copy(fontSize = 14.sp), color = MaterialTheme.colorScheme.onSurface)
    }
}

/**
 * Two rooms side by side (one alone runs the width): each a plate with its name and how many topics it holds, and under
 * it the room's sentence. The wide side swaps from row to row.
 */
@Composable
private fun RoomPair(cards: List<FacetCard>, wideFirst: Boolean, language: String, words: app.granger.home.YearWords, onOpen: (String) -> Unit) {
    val weights = when {
        cards.size == 1 -> listOf(1f)
        wideFirst -> listOf(190f, 142f)
        else -> listOf(142f, 190f)
    }
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().height(if (wideFirst) 150.dp else 128.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            for ((i, c) in cards.withIndex()) {
                val plate = Plate("room:" + c.facet.id, c.facet.name(language), c.picture, sub = pluralStringResource(R.plurals.explore_topics, c.count, count(c.count)))
                PlateView(plate, 16.sp, null, Modifier.weight(weights[i]).fillMaxHeight(), wordSize = 20.sp) { onOpen(c.facet.id) }
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 18.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            for ((i, c) in cards.withIndex()) {
                // Its dates' sentences, as the room's own page says them; a room with nothing dated says what it is. Until
                // its dates are read the lines are kept, so nothing below moves when the sentence comes.
                val said = if (!c.told) "" else c.finding?.sentences(words, short = true)?.takeIf { it.isNotEmpty() }?.joinToString(" ") ?: c.description.orEmpty()
                Text(
                    said, style = SpreadType.serif.copy(fontSize = 12.5.sp, lineHeight = 17.sp), color = MaterialTheme.colorScheme.onSurfaceVariant, minLines = if (cards.size == 1) 1 else 3,
                    modifier = Modifier.weight(weights[i]).padding(start = if (i == 0) 16.dp else 8.dp, end = if (i == cards.lastIndex) 16.dp else 8.dp),
                )
            }
        }
    }
}

private class Line(val title: String, val sub: String?, val tag: String, val onOpen: () -> Unit)

/** A group of ruled lines under an eyebrow (book collections, packs): each a title, a line under it, and a way in. */
@Composable
private fun Lines(title: String, lines: List<Line>) {
    Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 24.dp)) {
        Eyebrow(title, Modifier.padding(bottom = 8.dp).semantics { heading() }, color = MaterialTheme.colorScheme.onSurfaceVariant)
        for (line in lines) {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Column(
                Modifier.fillMaxWidth().heightIn(min = 52.dp).clickable(role = Role.Button, onClick = line.onOpen).semantics(mergeDescendants = true) {}.testTag(line.tag).padding(vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(1.dp, Alignment.CenterVertically),
            ) {
                Text(line.title, style = SpreadType.display.copy(fontSize = 17.sp, lineHeight = 21.sp), color = MaterialTheme.colorScheme.onSurface)
                line.sub?.let { Text(it, style = SpreadType.ui.copy(fontSize = 12.sp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }
    }
}
