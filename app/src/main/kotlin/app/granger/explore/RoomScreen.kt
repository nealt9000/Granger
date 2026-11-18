package app.granger.explore

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
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
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.granger.R

object RoomTags {
    const val LIST = "room_list"
    const val STORY = "room_story"
    fun story(path: String) = "room_story_$path"
    fun view(sort: SubjectSort) = "room_view_${sort.name.lowercase()}"
    const val NARROW = "room_narrow"
    const val MORE = "room_more"
    fun also(key: String) = "room_also_$key"
}

/** Fewer topics than this and a room is called small. */
private const val SMALL_ROOM = 30

@Composable
internal fun Share.dated(): String = stringResource(
    when (this) {
        Share.NEARLY_ALL -> R.string.explore_dated_nearly_all
        Share.THREE_IN_FOUR -> R.string.explore_dated_three_in_four
        Share.TWO_IN_THREE -> R.string.explore_dated_two_in_three
        Share.HALF -> R.string.explore_dated_half
        Share.ONE_IN_THREE -> R.string.explore_dated_one_in_three
        Share.ONE_IN_FOUR -> R.string.explore_dated_one_in_four
        Share.ONE_IN_FIVE -> R.string.explore_dated_one_in_five
        Share.ONE_IN_TEN -> R.string.explore_dated_one_in_ten
    },
)

@Composable
private fun Share.words(): String = stringResource(
    when (this) {
        Share.NEARLY_ALL -> R.string.explore_share_nearly_all
        Share.THREE_IN_FOUR -> R.string.explore_share_three_in_four
        Share.TWO_IN_THREE -> R.string.explore_share_two_in_three
        Share.HALF -> R.string.explore_share_half
        Share.ONE_IN_THREE -> R.string.explore_share_one_in_three
        Share.ONE_IN_FOUR -> R.string.explore_share_one_in_four
        Share.ONE_IN_FIVE -> R.string.explore_share_one_in_five
        Share.ONE_IN_TEN -> R.string.explore_share_one_in_ten
    },
)

/**
 * A room's standfirst: its description, how many topics it holds, then what its dates say (its oldest dated topic; the
 * share of its dated topics in their busiest century). Every sentence but the description is filled from the index.
 */
@Composable
private fun standfirst(ui: SubjectUi, words: app.granger.home.YearWords): String {
    val parts = ArrayList<String>()
    ui.room?.description?.let { parts += it }
    if (ui.count > 0) parts += pluralStringResource(R.plurals.explore_topics, ui.count, count(ui.count)) + "."
    ui.room?.finding?.let { parts += it.sentences(words) }
    return parts.joinToString(" ")
}

/** What a finding says, a sentence each: the oldest dated topic; the share of the dated topics in their busiest century. */
@Composable
internal fun RoomFinding.sentences(words: app.granger.home.YearWords, oldest: Boolean = true, short: Boolean = false): List<String> {
    val parts = ArrayList<String>()
    // [short]: under a room's plate, one of several side by side, the oldest is a label and not a sentence.
    if (oldest) this.oldest?.let { o -> o.mark?.let { m -> parts += stringResource(if (short) R.string.explore_room_oldest_short else R.string.explore_room_oldest, o.title, words.of(m)) } }
    val share = share
    if (share != null && centuryStart != null) {
        // English names a century by its years (the 1900s); Spanish counts it (el siglo XX).
        val spanish = LocalConfiguration.current.locales[0].language == "es"
        val century = stringResource(R.string.explore_century, if (spanish) romanCentury(centuryStart) else centuryStart.toString())
        parts += stringResource(R.string.explore_room_century, share.dated(), century)
    }
    return parts
}

private fun ExploreArticle.plate(dates: String?) = Plate("$packId/$path", title, picture, ratio, whole, sub = dates)

/**
 * A room (Plan EX round 2, D3): the trail at the head; its name over a standfirst that teaches before a tap; the room
 * through time, one plate an era; its views (Plates, Through time, A–Z) over the mosaic, a page at a time; where its
 * topics also hang; the ways on; and, always at the foot, the doors to the rooms either side.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RoomContent(ui: SubjectUi?, actions: ExploreActions, subject: SubjectActions = SubjectActions()) {
    val language = LocalConfiguration.current.locales[0].language
    val subjectName = ui?.let { stringResource(it.subject.label) }.orEmpty()
    // The whole subject's listing has a room's views and none of a room's own parts.
    val whole = ui != null && ui.facet == null
    val name = if (whole) stringResource(R.string.explore_all_in, subjectName) else ui?.facet?.name(language).orEmpty()
    Scaffold(
        topBar = {
            Box(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.background).windowInsetsPadding(TopAppBarDefaults.windowInsets)) {
                if (ui != null) {
                    Trail(listOf(TrailStep(stringResource(R.string.explore_title), subject.onOpenExplore), TrailStep(subjectName, subject.onOpenSubject)), here = name)
                }
            }
        },
        bottomBar = {
            val room = ui?.room
            if (room != null && room.total > 1) {
                fun door(f: app.granger.core.library.Facet?) = f?.let { Door(it.name(language)) { subject.onOpenRoom(it.subject, it.id, true) } }
                DoorsBar(DoorKind.ROOM, door(room.before), door(room.after), room.position, room.total)
            }
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        val loaded = ui ?: return@Scaffold
        val words = app.granger.home.yearWords()
        val list = rememberLazyListState()
        var narrow by rememberSaveable { mutableStateOf(false) }
        val rail = loaded.sort == SubjectSort.AZ && loaded.letters.size > 1
        // A letter chosen on the rail brings the list back to the views, over the letter's first plates.
        val viewsAt = if ((loaded.room?.story?.size ?: 0) > 1 || loaded.room?.storyPending == true) 2 else 1
        LaunchedEffect(loaded.letter) { if (loaded.letter != null) list.scrollToItem(viewsAt) }
        val rows = plateRows(loaded.articles.map { a -> a.plate(a.mark?.let { words.of(it) }) }, complete = !loaded.more && !loaded.loading)
        val byKey = loaded.articles.associateBy { "${it.packId}/${it.path}" }
        val open: (Plate) -> Unit = { p -> byKey[p.key]?.let { actions.onOpenArticle(it.packId, it.path, it.title) } }
        Box(Modifier.padding(padding).fillMaxSize()) {
            // In A–Z order the list keeps clear of the letter rail, so nothing runs under it.
            LazyColumn(Modifier.fillMaxSize().testTag(RoomTags.LIST), state = list, contentPadding = PaddingValues(bottom = 24.dp, end = if (rail) 36.dp else 0.dp)) {
                item(key = "head") {
                    SpreadHead(
                        if (whole) stringResource(R.string.explore_way_subject)
                        else stringResource(if (loaded.count in 1 until SMALL_ROOM) R.string.explore_room_small_in else R.string.explore_room_in, subjectName),
                        name, Modifier.padding(start = 16.dp, end = 16.dp, top = 4.dp), standfirst = standfirst(loaded, words), large = true,
                    )
                }
                val story = loaded.room?.story.orEmpty()
                if (loaded.room?.storyPending == true) {
                    // The strip's place, while its plates are read: the wall of plates under it stays where it is.
                    item(key = "story") { Box(Modifier.padding(top = 22.dp).fillMaxWidth().height(STORY_HEIGHT)) }
                } else if (story.size > 1) {
                    item(key = "story") {
                        Column(Modifier.padding(top = 22.dp).heightIn(min = STORY_HEIGHT), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Eyebrow(pluralStringResource(R.plurals.explore_room_story, story.size, story.size), Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                            LazyRow(Modifier.testTag(RoomTags.STORY), contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                                items(story, key = { it.packId + "/" + it.path }) { a -> StoryPlate(a, a.mark?.let { words.of(it) }) { actions.onOpenArticle(a.packId, a.path, a.title) } }
                            }
                        }
                    }
                }
                item(key = "views") {
                    Column(Modifier.padding(top = 18.dp)) {
                        Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            ViewTab(stringResource(R.string.explore_view_plates), SubjectSort.LINKED, loaded.sort, subject.onSort)
                            if (loaded.eras.isNotEmpty()) ViewTab(stringResource(R.string.explore_view_time), SubjectSort.OLDEST, loaded.sort, subject.onSort)
                            if (loaded.count > 0) ViewTab(stringResource(R.string.explore_view_az), SubjectSort.AZ, loaded.sort, subject.onSort)
                        }
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    }
                }
                if (loaded.sort != SubjectSort.AZ && loaded.eras.isNotEmpty()) {
                    item(key = "order") {
                        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            val order = stringResource(if (loaded.sort == SubjectSort.OLDEST) R.string.explore_order_oldest else R.string.explore_order_known)
                            val era = loaded.era?.let { stringResource(it.label) } ?: stringResource(R.string.explore_subject_all_eras)
                            Text(stringResource(R.string.explore_order_line, order, era), style = SpreadType.ui, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                            Box(
                                Modifier.heightIn(min = 48.dp).clickable(role = Role.Button) { narrow = !narrow }.padding(horizontal = 12.dp).testTag(RoomTags.NARROW),
                                contentAlignment = Alignment.Center,
                            ) { Text(stringResource(R.string.explore_narrow), style = SpreadType.uiMedium, color = SpreadColors.accent) }
                        }
                    }
                    if (narrow || loaded.era != null) {
                        item(key = "eras") {
                            LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 8.dp)) {
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
                } else {
                    item(key = "order-gap") { Box(Modifier.height(12.dp)) }
                }
                items(rows, key = { "row:" + it.plates.first().key }) { row ->
                    PlateRowView(row, open, Modifier.padding(bottom = 2.dp, end = if (rail) 36.dp else 0.dp))
                }
                when {
                    loaded.loading -> item(key = "loading") {
                        Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.size(28.dp)) }
                    }
                    loaded.more -> item(key = "more") {
                        val total = if (loaded.era != null) loaded.eras.firstOrNull { it.first == loaded.era }?.second ?: loaded.count else if (loaded.sort == SubjectSort.OLDEST) loaded.dated else loaded.count
                        val next = minOf(SubjectViewModel.ROOM_PAGE, (total - loaded.articles.size).coerceAtLeast(1))
                        Box(
                            Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(role = Role.Button, onClick = subject.onMore).padding(horizontal = 16.dp).testTag(RoomTags.MORE),
                            contentAlignment = Alignment.CenterStart,
                        ) { Text(stringResource(R.string.explore_show_next, count(next), count(total)), style = SpreadType.uiMedium.copy(fontSize = 14.sp), color = SpreadColors.accent) }
                    }
                    loaded.articles.isEmpty() -> item(key = "empty") { Text(stringResource(R.string.explore_nothing_here), modifier = Modifier.padding(16.dp)) }
                }
                val overlaps = loaded.room?.overlaps.orEmpty()
                if (overlaps.isNotEmpty()) {
                    item(key = "also") { AlsoHang(overlaps, loaded.count, language) { subject.onOpenRoom(it.facet.subject, it.facet.id, false) } }
                }
                item(key = "ways") {
                    WaysOn(
                        stringResource(R.string.explore_ways_on),
                        listOfNotNull(
                            if (loaded.canWander) WayOn(stringResource(if (whole) R.string.explore_way_anywhere_subject else R.string.explore_way_anywhere), stringResource(R.string.explore_subject_wander), subject.onWander) else null,
                            WayOn(stringResource(if (whole) R.string.explore_way_rooms else R.string.explore_way_subject), subjectName, subject.onOpenSubject),
                        ),
                        Modifier.padding(top = 28.dp),
                    )
                }
            }
            if (rail) LetterRail(loaded.letters, loaded.letter, subject.onJump, Modifier.align(Alignment.CenterEnd))
        }
    }
}

/** One view's tab: its name, underlined when it is the view showing. */
@Composable
private fun ViewTab(label: String, sort: SubjectSort, current: SubjectSort, onSort: (SubjectSort) -> Unit) {
    val on = sort == current
    Column(
        Modifier.width(androidx.compose.foundation.layout.IntrinsicSize.Max).clickable(role = Role.Tab) { onSort(sort) }.semantics { selected = on }.testTag(RoomTags.view(sort)),
    ) {
        Box(Modifier.heightIn(min = 46.dp).padding(horizontal = 2.dp), contentAlignment = Alignment.Center) {
            Text(label, style = SpreadType.uiMedium.copy(fontSize = 13.5.sp), color = if (on) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        }
        Box(Modifier.fillMaxWidth().height(2.dp).background(if (on) SpreadColors.accent else androidx.compose.ui.graphics.Color.Transparent))
    }
}

/** The strip's height: its eyebrow, a plate, its date and two lines of title. */
private val STORY_HEIGHT = 185.dp

/** One era's plate in the room through time: the picture, its date, its title. One button for TalkBack. */
@Composable
private fun StoryPlate(a: ExploreArticle, dates: String?, onOpen: () -> Unit) {
    val label = listOfNotNull(a.title, dates).joinToString(", ")
    Column(
        Modifier.width(150.dp).clickable(role = Role.Button, onClick = onOpen).semantics { contentDescription = label }.testTag(RoomTags.story(a.path)),
    ) {
        Column(Modifier.clearAndSetSemantics {}, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            when {
                a.picture == null -> Box(Modifier.fillMaxWidth().height(100.dp).background(MaterialTheme.colorScheme.surfaceContainer))
                a.whole -> WholePicture(a.picture, 240.dp, Modifier.fillMaxWidth().height(100.dp).background(androidx.compose.ui.graphics.Color.White).padding(6.dp))
                else -> FramedPicture(a.picture, 240.dp, Modifier.fillMaxWidth().height(100.dp))
            }
            dates?.let { Text(it, style = SpreadType.uiMedium.copy(fontSize = 11.sp), color = SpreadColors.accent, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(end = 6.dp)) }
            Text(a.title, style = SpreadType.display.copy(fontSize = 15.sp, lineHeight = 17.sp), color = MaterialTheme.colorScheme.onSurface, minLines = 2, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(end = 6.dp))
        }
    }
}

/**
 * Where a room's topics also hang: a heading that states the largest overlap (as a share of the room when it is one in
 * ten or more, else as a count), then each other room on a ruled line: how many, its name, its subject and a few of
 * the shared topics. A line opens that room.
 */
@Composable
private fun AlsoHang(overlaps: List<RoomOverlap>, count: Int, language: String, onOpen: (RoomOverlap) -> Unit) {
    val lead = overlaps.first()
    Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 28.dp)) {
        val amount = shareOf(lead.shared, count)?.words() ?: count(lead.shared)
        SpreadHead(stringResource(R.string.explore_also_eyebrow), stringResource(R.string.explore_also_heading, amount, lead.facet.name(language)), Modifier.padding(bottom = 10.dp))
        for (o in overlaps) {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Row(
                Modifier.fillMaxWidth().heightIn(min = 60.dp).clickable(role = Role.Button) { onOpen(o) }.semantics(mergeDescendants = true) {}.testTag(RoomTags.also(o.facet.key)).padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(count(o.shared), style = SpreadType.display.copy(fontSize = 22.sp), color = SpreadColors.enamel, modifier = Modifier.width(52.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                    Text(stringResource(R.string.explore_also_in, o.facet.name(language)), style = SpreadType.uiMedium.copy(fontSize = 14.sp), color = MaterialTheme.colorScheme.onSurface)
                    val subject = Subject.of(o.facet.subject)?.let { stringResource(it.label) }.orEmpty()
                    Text(
                        if (o.examples.isEmpty()) subject else stringResource(R.string.explore_also_detail, subject, o.examples.joinToString(", ")),
                        style = SpreadType.ui.copy(fontSize = 12.sp, lineHeight = 16.sp), color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}
