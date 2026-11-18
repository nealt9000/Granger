package app.granger.explore

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.granger.R
import app.granger.theme.GrangerTheme

/**
 * Explore's landing (Plan EX round 2, D1): today's spread (a broad topic in three numbered plates, with what its page
 * holds), the subjects as a mosaic under a heading that says which are largest, Through time as a bar for each era, and
 * three ways on. Every sentence is filled from the indexes. Null [ui] is loading.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExploreContent(
    ui: ExploreUi?, actions: ExploreActions, onAnother: () -> Unit = {}, onWander: () -> Unit = {}, day: Long = java.time.LocalDate.now().toEpochDay(),
) {
    Scaffold(
        topBar = {
            Row(
                Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.background).windowInsetsPadding(TopAppBarDefaults.windowInsets).height(56.dp)
                    .padding(start = 16.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(R.string.explore_title), style = SpreadType.display.copy(fontSize = 26.sp, lineHeight = 30.sp), color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).semantics { heading() },
                )
                IconButton(onClick = actions.onGetMore, modifier = Modifier.testTag(ExploreTags.GET_MORE)) { Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.explore_get_more)) }
            }
        },
    ) { padding ->
        val loaded = ui ?: return@Scaffold
        val doors = loaded.doors
        val rows = remember(doors.size) { doorRows(doors.size) }
        LazyColumn(Modifier.padding(padding).fillMaxSize(), contentPadding = PaddingValues(bottom = 28.dp)) {
            if (loaded.noSubjects) {
                item(key = "no-subjects") {
                    Column(Modifier.padding(16.dp).testTag(ExploreTags.NO_SUBJECTS)) {
                        Text(stringResource(R.string.explore_no_subjects), style = SpreadType.serif.copy(fontSize = 17.sp, lineHeight = 25.sp))
                        Button(onClick = actions.onGetMore, shape = RoundedCornerShape(2.dp), modifier = Modifier.padding(top = 8.dp).heightIn(min = 48.dp)) {
                            Text(stringResource(R.string.explore_get_wikipedia))
                        }
                    }
                }
            }
            loaded.featured?.let { topic ->
                val open = { actions.onOpenArticle(topic.article.packId, topic.article.path, topic.article.title) }
                item(key = "spread-eyebrow") {
                    Eyebrow(
                        if (loaded.skipped == 0) stringResource(R.string.explore_spread_today, dateWords(day)) else stringResource(R.string.explore_spread_another),
                        Modifier.padding(start = 16.dp, end = 16.dp, top = 2.dp, bottom = 10.dp), color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                item(key = "spread-plates") { SpreadPlates(topic, open) }
                item(key = "spread-head") { SpreadWords(topic, open, onAnother) }
            }
            if (doors.isNotEmpty()) {
                item(key = "library-head") { LibraryHead(loaded) }
                var at = 0
                for (kind in rows) {
                    val row = doors.subList(at, at + kind.size)
                    at += kind.size
                    item(key = "doors:" + row.first().subject.id) { DoorRow(kind, row) { actions.onOpenSubject(it) } }
                }
            }
            if (loaded.eras.isNotEmpty()) {
                item(key = "time-head") { TimeHead(loaded.eras) }
                val most = loaded.eras.maxOf { it.count }
                items(loaded.eras, key = { "era:" + it.era.name }) { row ->
                    EraBar(row, most) {
                        val top = row.examples.firstOrNull()
                        actions.onOpenTimeline(top?.packId, top?.path)
                    }
                }
                item(key = "timeline") {
                    Column(Modifier.padding(horizontal = 16.dp)) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        Box(
                            Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(role = Role.Button) { actions.onOpenTimeline(null, null) }.testTag(ExploreTags.TIMELINE),
                            contentAlignment = Alignment.CenterStart,
                        ) { Text(stringResource(R.string.explore_open_timeline), style = SpreadType.uiMedium.copy(fontSize = 14.sp), color = SpreadColors.accent) }
                    }
                }
            }
            item(key = "ways") {
                val games = app.granger.home.GameKind.entries.size
                val ways = buildList {
                    if (!loaded.noSubjects) add(WayOn(stringResource(R.string.explore_way_anywhere_library), stringResource(R.string.explore_subject_wander), onWander))
                    if (loaded.eras.isNotEmpty()) add(WayOn(pluralStringResource(R.plurals.explore_way_games, games, games), stringResource(R.string.games_title), actions.onOpenGames))
                    if (loaded.sources.isNotEmpty()) {
                        val packs = pluralStringResource(R.plurals.explore_packs, loaded.sources.size, loaded.sources.size)
                        add(WayOn(if (loaded.articles > 0) stringResource(R.string.explore_summary, count(loaded.articles), packs) else packs, stringResource(R.string.explore_sources), actions.onOpenSources))
                    }
                }
                if (ways.isNotEmpty()) WaysOn(stringResource(R.string.explore_ways_on), ways, Modifier.padding(top = 30.dp))
            }
        }
    }
}

/** A day as the spread's eyebrow says it: "Tuesday 17 November", in the reader's language and order. */
@Composable
private fun dateWords(day: Long): String {
    val locale = LocalConfiguration.current.locales[0]
    return remember(day, locale) {
        val pattern = android.text.format.DateFormat.getBestDateTimePattern(locale, "EEEEdMMMM")
        java.time.format.DateTimeFormatter.ofPattern(pattern, locale).format(java.time.LocalDate.ofEpochDay(day))
    }
}

/** The spread's plates: the topic's own picture with two of its figures beside it, numbered for the captions; or its picture alone. */
@Composable
private fun SpreadPlates(topic: FeaturedTopic, onOpen: () -> Unit) {
    val a = topic.article
    val plates = listOf(Plate("spread:0", a.title, a.picture, a.ratio, a.whole)) +
        topic.figures.mapIndexed { i, f -> Plate("spread:${i + 1}", f.caption, f.picture, whole = app.granger.home.ArticleThumbnails.isWhole(f.picture)) }
    val row = openingRow(plates) ?: return
    PlateRowView(row, onOpen = { onOpen() }, firstNumber = if (plates.size > 1) 1 else null)
}

/** Under the plates: where the topic hangs, its name, its opening sentence, the plates' captions, what its page holds, and the ways in. */
@Composable
private fun SpreadWords(topic: FeaturedTopic, onOpen: () -> Unit, onAnother: () -> Unit) {
    val language = LocalConfiguration.current.locales[0].language
    val subject = stringResource(topic.subject.label)
    Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SpreadHead(
            eyebrow = topic.room?.let { stringResource(R.string.explore_spread_in_room, subject, it.name(language)) } ?: subject,
            title = topic.article.title, standfirst = topic.sentence, large = true,
        )
        if (topic.figures.isNotEmpty()) Captions(listOf(topic.article.title) + topic.figures.map { it.caption })
        val holds = buildList {
            if (topic.kinds > 0) add(pluralStringResource(R.plurals.topic_broad_kinds_count, topic.kinds, topic.kinds))
            if (topic.people > 0) add(pluralStringResource(R.plurals.topic_broad_people_count, topic.people, topic.people))
            if (topic.chapters > 0) add(pluralStringResource(R.plurals.topic_broad_chapters_count, topic.chapters, topic.chapters))
        }
        if (holds.isNotEmpty()) {
            Text(stringResource(R.string.explore_spread_holds, holds.joinToString(", ")), style = SpreadType.ui, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val night = GrangerTheme.extras.night
            Button(
                onClick = onOpen, shape = RoundedCornerShape(2.dp), modifier = Modifier.heightIn(min = 48.dp).testTag(ExploreTags.OPEN_SPREAD),
                colors = ButtonDefaults.buttonColors(
                    containerColor = SpreadColors.enamel, contentColor = if (night) MaterialTheme.colorScheme.onSecondary else MaterialTheme.colorScheme.onPrimary,
                ),
            ) { Text(stringResource(R.string.explore_spread_open), style = SpreadType.uiMedium.copy(fontSize = 14.sp)) }
            DoButton(stringResource(R.string.explore_spread_next), ExploreTags.ANOTHER, onAnother)
        }
    }
}

/** The head over the subject mosaic: how many subjects and rooms, which three are largest, and how the smallest compares. */
@Composable
private fun LibraryHead(ui: ExploreUi) {
    val counted = ui.doors.filter { it.count > 0 }
    val subjects = pluralStringResource(R.plurals.explore_subjects_count, ui.doors.size, ui.doors.size)
    val eyebrow = if (ui.rooms > 0) stringResource(R.string.explore_library_rooms, subjects, pluralStringResource(R.plurals.explore_rooms, ui.rooms, count(ui.rooms)))
    else stringResource(R.string.explore_library, subjects)
    // With four or more there is something to say: the three largest, and the smallest beside the largest.
    val finding = counted.size >= 4
    val title = if (finding) stringResource(R.string.explore_library_largest, stringResource(counted[0].subject.label), stringResource(counted[1].subject.label), stringResource(counted[2].subject.label))
    else stringResource(R.string.explore_map_subjects)
    val standfirst = buildList {
        if (finding) {
            val small = counted.last()
            val name = stringResource(small.subject.label)
            val topics = pluralStringResource(R.plurals.explore_topics, small.count, count(small.count))
            val times = Math.round(counted.first().count.toDouble() / small.count).toInt()
            add(
                if (times >= 2) stringResource(R.string.explore_library_smallest_times, name, topics, stringResource(counted.first().subject.label), count(times))
                else stringResource(R.string.explore_library_smallest, name, topics),
            )
        }
        add(stringResource(R.string.explore_library_opens))
    }.joinToString(" ")
    SpreadHead(eyebrow, title, Modifier.padding(start = 16.dp, end = 16.dp, top = 36.dp, bottom = 12.dp), standfirst = standfirst)
}

/** What a door's plate says under its name: its topics, else its one pack or collection, else how many. */
@Composable
private fun countLine(door: SubjectDoor): String {
    val names = door.packs.map { it.title } + door.collections.map { it.title }
    return when {
        door.count > 0 -> pluralStringResource(R.plurals.explore_topics, door.count, count(door.count))
        names.size == 1 -> names.single()
        else -> pluralStringResource(R.plurals.explore_packs, names.size, names.size)
    }
}

/** One row of the subject mosaic: each subject a plate, by its own article's picture (filling the plate, a map included), that opens it. */
@Composable
private fun DoorRow(kind: DoorRowKind, doors: List<SubjectDoor>, onOpen: (Subject) -> Unit) {
    val ratio = when (kind) {
        DoorRowKind.WIDE_FIRST, DoorRowKind.WIDE_LAST -> 334f / 150f
        DoorRowKind.THREE -> 334f / 116f
        DoorRowKind.PAIR -> 334f / 104f
        DoorRowKind.SINGLE -> 334f / 132f
    }
    Row(Modifier.fillMaxWidth().padding(bottom = 2.dp).aspectRatio(ratio), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        for ((i, door) in doors.withIndex()) {
            val wide = (kind == DoorRowKind.WIDE_FIRST && i == 0) || (kind == DoorRowKind.WIDE_LAST && i == 1)
            val plate = Plate("door:${door.subject.id}", stringResource(door.subject.label), door.picture, sub = countLine(door))
            PlateView(plate, if (wide || kind == DoorRowKind.PAIR || kind == DoorRowKind.SINGLE) 16.sp else 13.5.sp, null, Modifier.weight(if (wide) 2f else 1f).fillMaxHeight(), short = true) {
                onOpen(door.subject)
            }
        }
    }
}

/** An era as the busiest-era sentence names it: "the Middle Ages", "1946 or later". */
private val Era.fromWords: Int
    get() = when (this) {
        Era.PREHISTORY -> R.string.explore_era_prehistory_from
        Era.ANCIENT -> R.string.explore_era_ancient_from
        Era.MIDDLE_AGES -> R.string.explore_era_middle_ages_from
        Era.EARLY_MODERN -> R.string.explore_era_early_modern_from
        Era.C19 -> R.string.explore_era_c19_from
        Era.WARS -> R.string.explore_era_wars_from
        Era.RECENT -> R.string.explore_era_recent_from
    }

/** The head over Through time: the era that holds the most of the dated topics, and its share in words. */
@Composable
private fun TimeHead(eras: List<EraRow>) {
    val name = stringResource(R.string.explore_time)
    val busiest = busiestEra(eras)
    val modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 36.dp, bottom = 10.dp)
    val opens = stringResource(R.string.explore_time_opens)
    if (busiest == null) SpreadHead(null, name, modifier, standfirst = opens)
    else SpreadHead(name, stringResource(R.string.explore_room_century, busiest.second.dated(), stringResource(busiest.first.era.fromWords)).trimEnd('.'), modifier, standfirst = opens)
}

/** One era: its name and years, a bar as long as its share of the fullest era, and how many dated topics it holds. It opens the Timeline there. */
@Composable
private fun EraBar(row: EraRow, most: Int, onOpen: () -> Unit) {
    val name = stringResource(row.era.label)
    val span = stringResource(row.era.span)
    val topics = pluralStringResource(R.plurals.explore_topics, row.count, count(row.count))
    val reads = stringResource(R.string.explore_door_reads, name, stringResource(R.string.explore_door_reads, span, topics))
    Column(Modifier.padding(horizontal = 16.dp)) {
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Row(
            Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(role = Role.Button, onClick = onOpen).semantics { contentDescription = reads }
                .testTag(ExploreTags.era(row.era)).padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Column(Modifier.width(150.dp).clearAndSetSemantics {}) {
                Text(name, style = SpreadType.display.copy(fontSize = 15.sp, lineHeight = 18.sp), color = MaterialTheme.colorScheme.onSurface)
                Text(span, style = SpreadType.ui.copy(fontSize = 11.sp, lineHeight = 14.sp), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Box(Modifier.weight(1f).height(10.dp)) {
                // Never nothing: the thinnest era still shows a mark.
                Box(Modifier.fillMaxHeight().fillMaxWidth((row.count.toFloat() / most.coerceAtLeast(1)).coerceIn(0.025f, 1f)).background(SpreadColors.enamel))
            }
            Text(
                count(row.count), style = SpreadType.ui.copy(fontSize = 11.5.sp, fontFeatureSettings = "tnum"), color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = androidx.compose.ui.text.style.TextAlign.End, modifier = Modifier.width(48.dp).clearAndSetSemantics {},
            )
        }
    }
}
