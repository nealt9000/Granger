package app.granger.topic

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.granger.R
import app.granger.core.library.BookMention
import app.granger.explore.Eyebrow
import app.granger.explore.FramedPicture
import app.granger.explore.SpreadColors
import app.granger.explore.SpreadType
import app.granger.explore.Trail
import app.granger.explore.TrailStep
import app.granger.explore.WholePicture
import app.granger.home.ArticleThumbnails
import app.granger.home.yearWords
import app.granger.theme.FieldKit
import kotlinx.coroutines.flow.Flow

object QuiltTags {
    const val QUILT = "topic_quilt"
    const val FINDING = "quilt_finding"
    const val CENTRE = "quilt_centre"
    fun plate(key: String) = "quilt_plate_$key"
    fun step(i: Int) = "quilt_step_$i"
    fun group(i: Int) = "quilt_group_$i"
    fun line(key: String) = "quilt_line_$key"
    const val LABEL = "quilt_label"
    const val STAND = "quilt_stand"
    const val READ = "quilt_read"
    const val BACK = "quilt_back"
}

/** One step of the walk: a topic the quilt has stood round. */
private data class Step(val packId: String, val path: String, val title: String, val picture: String?)

/** What a quilt does with the world outside it. */
class QuiltActions(
    val onOpenArticle: (packId: String, path: String, title: String) -> Unit,
    val onOpenBookAt: (bookId: String, chapter: String) -> Unit,
    val onOpenPassage: (bookId: String, mention: BookMention) -> Unit,
    /** A room in Explore (its subject's id and its own); null leaves room plates still. */
    val onOpenPlace: ((subject: String?, room: String?) -> Unit)? = null,
    val onClose: () -> Unit,
)

/**
 * Connections as a quilt (Plan EX round 2, D4): the topic in the middle, and touching it the plates it is connected to,
 * group by group, each group starting with a paper tab. Touching a plate re-forms the quilt round it, and the walk
 * leaves a thread along the top; a step in the thread goes back to it. A corner number says how many other topics
 * lead to a plate; a small square marks a plate that was on the last quilt too. Holding a plate shows its label: where
 * the link was found, its dates and rooms, its opening sentence and the topics that lead on from it. Under the quilt
 * every group is listed with its count, and opens in full as a list. To TalkBack each plate is a button that says its
 * group and what leads to it, with "Show its label" as an action.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TopicQuilt(
    start: Topic,
    topicOf: (packId: String, path: String) -> Flow<Topic>,
    around: suspend (packId: String, path: String, plates: List<Pair<String, String>>) -> QuiltAround,
    cardOf: suspend (packId: String, path: String) -> TopicArticle?,
    actions: QuiltActions,
) {
    val startTitle = start.header?.title ?: TopicSource.titleOf(start.path)
    val walk = remember { mutableStateListOf(Step(start.packId, start.path, startTitle, start.header?.picture)) }
    val here = walk.last()
    val first = walk.size == 1
    val topic by (if (first) remember(start) { kotlinx.coroutines.flow.flowOf(start) } else remember(here) { topicOf(here.packId, here.path) })
        .collectAsState(initial = if (first) start else Topic(here.packId, here.path))
    // The last quilt's plates, and whose it was: a plate that was on it is marked on this one.
    var last by remember { mutableStateOf<Pair<String, Set<String>>?>(null) }
    var held by remember { mutableStateOf<Pair<QuiltPlate, Int>?>(null) }
    var open by remember { mutableStateOf<Int?>(null) }
    fun back() {
        when {
            held != null -> held = null
            walk.size > 1 -> { walk.removeAt(walk.lastIndex); last = null; open = null }
            else -> actions.onClose()
        }
    }
    BackHandler { back() }

    val words = yearWords()
    val language = LocalConfiguration.current.locales[0].language
    val title = topic.header?.title ?: here.title
    val picture = topic.header?.picture ?: here.picture
    val base by produceState<QuiltAround?>(null, here) { value = around(here.packId, here.path, emptyList()) }
    val groups = remember(topic, base, language) { Quilts.groups(topic, base, language) { words.of(it) } }
    val cells = remember(groups) { Quilts.cells(groups) }
    val asked = remember(cells) { cells.mapNotNull { c -> c.plate.packId?.let { p -> c.plate.path?.let { p to it } } } }
    // Ready once the page's own parts are in: its closest in meaning come from a slower search and join when they arrive.
    val ready = base != null && (topic.done || (topic.header != null && !topic.broadPending))
    val more by produceState(emptyMap<String, Int>(), here, asked, ready) {
        value = if (ready && asked.isNotEmpty()) around(here.packId, here.path, asked).more else emptyMap()
    }
    val shared = last?.second.orEmpty()

    fun touch(p: QuiltPlate) {
        held = null
        when {
            p.packId != null && p.path != null -> {
                if (p.packId == here.packId && p.path == here.path) return
                last = title to cells.mapTo(HashSet()) { it.plate.key }
                open = null
                // The step being left keeps the name and picture its page gave it.
                walk[walk.lastIndex] = here.copy(title = title, picture = picture ?: here.picture)
                walk += Step(p.packId, p.path, p.title, p.picture)
            }
            p.room != null -> actions.onOpenPlace?.invoke(p.room.subject, p.room.id)
            p.bookId != null && p.mention != null -> actions.onOpenPassage(p.bookId, p.mention)
            p.bookId != null -> actions.onOpenBookAt(p.bookId, p.chapter.orEmpty())
        }
    }

    Surface(Modifier.fillMaxSize().testTag(QuiltTags.QUILT), color = MaterialTheme.colorScheme.background) {
        Box(Modifier.statusBarsPadding().navigationBarsPadding()) {
            Column(Modifier.fillMaxSize()) {
                Trail(listOf(TrailStep(startTitle, actions.onClose)), here = stringResource(R.string.topic_spread_connections))
                val scroll = rememberScrollState()
                LaunchedEffect(here) { scroll.scrollTo(0) }
                Column(Modifier.weight(1f).verticalScroll(scroll).padding(bottom = 28.dp)) {
                    if (walk.size > 1) Thread(walk.dropLast(1) + here.copy(title = title, picture = picture ?: here.picture)) { i -> while (walk.size > i + 1) walk.removeAt(walk.lastIndex); last = null; open = null; held = null }
                    Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 2.dp, bottom = 14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Eyebrow(stringResource(R.string.topic_quilt_around, title))
                        val onLast = cells.count { it.plate.key in shared }
                        val finding = when {
                            !ready -> stringResource(R.string.topic_quilt_looking, title)
                            groups.isEmpty() -> stringResource(R.string.topic_quilt_none)
                            onLast > 0 -> pluralStringResource(R.plurals.topic_quilt_shared, onLast, onLast, last?.first.orEmpty())
                            else -> {
                                val n = Quilts.connections(groups)
                                val held = pluralStringResource(R.plurals.topic_quilt_connections, n, n)
                                val inGroups = pluralStringResource(R.plurals.topic_quilt_groups, groups.size, groups.size)
                                if (groups.size > 1) stringResource(R.string.topic_quilt_finding, held, inGroups, groups.first().label(), groups.first().plates.size)
                                else stringResource(R.string.topic_quilt_finding_one, held, inGroups)
                            }
                        }
                        Text(
                            finding, style = SpreadType.display.copy(fontSize = 21.sp, lineHeight = 26.sp), color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.semantics { heading() }.testTag(QuiltTags.FINDING),
                        )
                    }
                    Patchwork(
                        Step(here.packId, here.path, title, picture), cells, groups, more, shared, held?.first?.key, cardOf,
                        onTouch = ::touch, onHold = { cell -> if (cell.plate.isArticle) held = cell.plate to cell.group else touch(cell.plate) },
                        onCentre = { if (first) actions.onClose() else actions.onOpenArticle(here.packId, here.path, title) },
                    )
                    if (cells.isNotEmpty()) Key(numbers = more.isNotEmpty(), marks = cells.any { it.plate.key in shared })
                    if (groups.isNotEmpty()) Index(groups, open, onOpen = { open = if (open == it) null else it }, onTouch = ::touch)
                    if (!first) {
                        Row(Modifier.padding(start = 16.dp, end = 16.dp, top = 18.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = { actions.onOpenArticle(here.packId, here.path, title) }, shape = RoundedCornerShape(2.dp),
                                modifier = Modifier.heightIn(min = 48.dp).weight(1f, fill = false).testTag(QuiltTags.READ),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = SpreadColors.enamel,
                                    contentColor = if (app.granger.theme.GrangerTheme.extras.night) MaterialTheme.colorScheme.onSecondary else MaterialTheme.colorScheme.onPrimary,
                                ),
                            ) { Text(stringResource(R.string.topic_quilt_read, title), style = SpreadType.uiMedium.copy(fontSize = 14.sp), maxLines = 1, overflow = TextOverflow.Ellipsis) }
                            app.granger.explore.DoButton(stringResource(R.string.topic_quilt_back), QuiltTags.BACK) { back() }
                        }
                    }
                }
            }
            held?.let { (plate, group) ->
                Label(
                    plate, where = groups.getOrNull(group)?.found(title), more = more[plate.key], centre = Quilts.key(here.packId, here.path), language = language,
                    around = around, cardOf = cardOf, onStand = { touch(plate) }, onTouch = ::touch,
                    onRead = { held = null; plate.packId?.let { p -> plate.path?.let { actions.onOpenArticle(p, it, plate.title) } } },
                    onDismiss = { held = null }, modifier = Modifier.align(Alignment.BottomCenter),
                )
            }
        }
    }
}

/** A group's name as its tab and the index say it: a chapter's own title, else what the group is. */
@Composable
private fun QuiltGroup.label(): String = name ?: stringResource(
    when (kind) {
        QuiltKind.KINDS -> R.string.topic_broad_kinds
        QuiltKind.PEOPLE -> R.string.topic_broad_people
        QuiltKind.CLOSE -> R.string.topic_quilt_close
        QuiltKind.LEADS -> R.string.topic_quilt_leads
        QuiltKind.LIVES -> R.string.topic_quilt_lives
        QuiltKind.EVENTS -> R.string.topic_quilt_events
        QuiltKind.ROOMS -> R.string.topic_quilt_rooms
        QuiltKind.BOOKS -> R.string.topic_in_books
        QuiltKind.STORY, QuiltKind.CHAPTER -> R.string.topic_spread_chapters
    },
)

/** Where a plate's link to [centre] was found, for its label: "From the History chapter of Airplane". */
@Composable
private fun QuiltGroup.found(centre: String): String = when (kind) {
    QuiltKind.KINDS -> stringResource(R.string.topic_quilt_found_kind, centre)
    QuiltKind.PEOPLE -> stringResource(R.string.topic_quilt_found_person, centre)
    QuiltKind.STORY, QuiltKind.CHAPTER -> stringResource(R.string.topic_quilt_found_chapter, name.orEmpty(), centre)
    QuiltKind.CLOSE -> stringResource(R.string.topic_quilt_found_close, centre)
    QuiltKind.LEADS -> stringResource(R.string.topic_quilt_found_leads, centre)
    QuiltKind.LIVES, QuiltKind.EVENTS -> stringResource(R.string.topic_quilt_found_years, centre)
    QuiltKind.ROOMS, QuiltKind.BOOKS -> label()
}

/** The walk so far, a step each: its picture and name. A step goes back to it; the last is where the quilt stands. */
@Composable
private fun Thread(walk: List<Step>, onStep: (Int) -> Unit) {
    val reads = stringResource(R.string.topic_quilt_thread)
    Row(
        Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp).semantics { contentDescription = reads },
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        walk.forEachIndexed { i, step ->
            val lastStep = i == walk.lastIndex
            Row(
                Modifier.heightIn(min = 48.dp).then(if (lastStep) Modifier else Modifier.clickable(role = Role.Button) { onStep(i) }).testTag(QuiltTags.step(i)),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                val frame = Modifier.size(36.dp).then(if (lastStep) Modifier.border(2.dp, SpreadColors.accent) else Modifier).background(MaterialTheme.colorScheme.surfaceContainer)
                FramedPicture(step.picture, 64.dp, frame.clearAndSetSemantics {})
                Text(
                    step.title, style = (if (lastStep) SpreadType.uiMedium else SpreadType.ui).copy(fontSize = 12.sp),
                    color = if (lastStep) MaterialTheme.colorScheme.onSurface else SpreadColors.accent, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 132.dp),
                )
            }
            if (!lastStep) Box(Modifier.width(14.dp).height(1.5.dp).background(SpreadColors.accent))
        }
    }
}

/** The quilt: three plates above the middle one, two beside it, three below, four along the foot, 2 dp of page between. */
@Composable
private fun Patchwork(
    centre: Step, cells: List<QuiltCell>, groups: List<QuiltGroup>, more: Map<String, Int>, shared: Set<String>, held: String?,
    cardOf: suspend (packId: String, path: String) -> TopicArticle?, onTouch: (QuiltPlate) -> Unit, onHold: (QuiltCell) -> Unit, onCentre: () -> Unit,
) {
    val layout = Quilts.layout(cells)
    val shown = cells.groupingBy { it.group }.eachCount()
    @Composable fun tile(cell: QuiltCell, modifier: Modifier, size: TextUnit, small: Boolean = false) {
        val g = groups[cell.group]
        val name = g.label()
        Tile(
            cell.plate, modifier, size,
            // The tab carries the group's count when it holds more than the quilt shows and there is room for it: not on a
            // small plate, nor beside a corner number unless the name is short (the index under the quilt has every count).
            tab = when {
                !cell.tab -> null
                small || g.plates.size <= (shown[cell.group] ?: 0) || (cell.plate.key in more && name.length > SHORT_NAME) -> name
                else -> stringResource(R.string.topic_quilt_tab, name, g.plates.size)
            },
            group = name, more = more[cell.plate.key] ?: 0, shared = cell.plate.key in shared, dim = held != null && held != cell.plate.key, small = small,
            cardOf = cardOf, onTouch = { onTouch(cell.plate) }, onHold = { onHold(cell) },
        )
    }
    @Composable fun row(some: List<QuiltCell>, ratio: Float, size: TextUnit, small: Boolean = false) {
        if (some.isEmpty()) return
        Row(Modifier.fillMaxWidth().aspectRatio(ratio), horizontalArrangement = Arrangement.spacedBy(2.dp)) { for (c in some) tile(c, Modifier.weight(1f).fillMaxHeight(), size, small) }
    }
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        row(layout.above, 334f / 96f, 12.5.sp)
        Row(Modifier.fillMaxWidth().aspectRatio(334f / 170f), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            Tile(
                QuiltPlate(Quilts.key(centre.packId, centre.path), centre.title, centre.picture, packId = centre.packId, path = centre.path),
                Modifier.weight(222f).fillMaxHeight().testTag(QuiltTags.CENTRE), 22.sp, tab = null, group = null, more = 0, shared = false, dim = held != null,
                centre = true, cardOf = cardOf, onTouch = onCentre, onHold = onCentre,
            )
            if (layout.beside.isNotEmpty()) {
                Column(Modifier.weight(110f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    for (c in layout.beside) tile(c, Modifier.weight(1f).fillMaxWidth(), 12.5.sp)
                }
            }
        }
        row(layout.below, 334f / 96f, 12.5.sp)
        row(layout.foot, 334f / 82f, 11.5.sp, small = true)
    }
}

/**
 * One plate: its picture with its title over a fade at the foot (a drawing whole on white; with no picture, its title
 * as a word). Over it: its group's [tab] where the group starts, the corner number ([more] topics lead here), the
 * square for a plate that was on the last quilt, and a ring round the middle plate. A touch re-forms the quilt; a hold
 * shows its label.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Tile(
    plate: QuiltPlate, modifier: Modifier, size: TextUnit, tab: String?, group: String?, more: Int, shared: Boolean, dim: Boolean,
    cardOf: suspend (packId: String, path: String) -> TopicArticle?, onTouch: () -> Unit, onHold: () -> Unit, centre: Boolean = false, small: Boolean = false,
) {
    // Read for this plate alone: when the quilt re-forms, a place's next plate never keeps the last one's picture.
    val read by androidx.compose.runtime.key(plate.key) {
        produceState<String?>(null, plate.key) {
            if (plate.picture == null && plate.packId != null && plate.path != null) value = cardOf(plate.packId, plate.path)?.picture
        }
    }
    val picture = plate.picture ?: read
    val leads = if (more > 0) pluralStringResource(R.plurals.topic_quilt_more, more, more) else null
    val was = if (shared) stringResource(R.string.topic_quilt_key_shared) else null
    val reads = listOfNotNull(plate.title, plate.sub, group, leads, was).joinToString(", ")
    val show = stringResource(R.string.topic_quilt_show_label)
    Box(
        modifier.testTag(QuiltTags.plate(plate.key)).alpha(if (dim) 0.28f else 1f)
            .combinedClickable(role = Role.Button, onClick = onTouch, onLongClick = onHold)
            .semantics {
                contentDescription = reads
                if (!centre && plate.isArticle) customActions = listOf(CustomAccessibilityAction(show) { onHold(); true })
            },
    ) {
        Box(Modifier.fillMaxSize().clearAndSetSemantics {}) {
            val file = picture
            when {
                file == null -> Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainer).padding(8.dp), verticalArrangement = Arrangement.Bottom) {
                    Text(plate.title, style = SpreadType.display.copy(fontSize = size, lineHeight = size * 1.12f, hyphens = androidx.compose.ui.text.style.Hyphens.Auto), color = MaterialTheme.colorScheme.onSurface, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    plate.sub?.let { Text(it, style = SpreadType.ui.copy(fontSize = 10.5.sp, lineHeight = 13.sp), color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1) }
                }
                ArticleThumbnails.isWhole(file) -> Column(Modifier.fillMaxSize().background(Color.White)) {
                    WholePicture(file, 240.dp, Modifier.weight(1f).fillMaxWidth().padding(start = 6.dp, end = 6.dp, top = if (tab != null) 20.dp else 6.dp, bottom = 2.dp))
                    Text(plate.title, style = SpreadType.display.copy(fontSize = size, lineHeight = size * 1.12f, hyphens = androidx.compose.ui.text.style.Hyphens.Auto), color = FieldKit.Ink, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 7.dp, end = 7.dp, bottom = 6.dp))
                }
                else -> {
                    FramedPicture(file, 240.dp, Modifier.fillMaxSize())
                    Column(
                        Modifier.fillMaxWidth().align(Alignment.BottomCenter).background(Brush.verticalGradient(listOf(Color.Transparent, FADE)))
                            .padding(start = 7.dp, end = 7.dp, top = 22.dp, bottom = 6.dp),
                    ) {
                        Text(plate.title, style = SpreadType.display.copy(fontSize = size, lineHeight = size * 1.12f, hyphens = androidx.compose.ui.text.style.Hyphens.Auto), color = Color.White, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        plate.sub?.let { Text(it, style = SpreadType.ui.copy(fontSize = 10.5.sp, lineHeight = 13.sp), color = Color.White.copy(alpha = 0.92f), maxLines = 1) }
                    }
                }
            }
            val numbered = more > 0 && !(tab != null && small)
            if (tab != null) {
                // The tab stops short of the corner number.
                Box(Modifier.align(Alignment.TopStart).fillMaxWidth().padding(end = if (numbered) 36.dp else 0.dp)) {
                    Text(
                        tab.uppercase(), style = SpreadType.eyebrow.copy(fontSize = 9.sp, lineHeight = 11.sp), color = SpreadColors.accent, maxLines = 1,
                        overflow = TextOverflow.Ellipsis, modifier = Modifier.background(MaterialTheme.colorScheme.background).padding(start = 6.dp, end = 6.dp, top = 4.dp, bottom = 3.dp),
                    )
                }
            }
            // A small plate with a tab has no room for the number beside it.
            if (numbered) {
                Box(Modifier.align(Alignment.TopEnd).height(18.dp).widthIn(min = 20.dp).background(CHIP).padding(horizontal = 5.dp), contentAlignment = Alignment.Center) {
                    Text("+$more", style = SpreadType.uiMedium.copy(fontSize = 10.5.sp, lineHeight = 12.sp), color = Color.White)    // l10n-ignore: a number
                }
            }
            if (shared) Box(Modifier.align(Alignment.BottomEnd).padding(6.dp).size(10.dp).background(Color.White).padding(1.5.dp).background(SpreadColors.accent))
            if (centre) Box(Modifier.fillMaxSize().border(3.dp, SpreadColors.accent))
        }
    }
}

/** A group's name this long or shorter leaves room for its count beside a corner number. */
private const val SHORT_NAME = 6

/** The fade a title sits on, and the corner number's ground: near black, warm. */
private val FADE = Color(0xD10C0A08)
private val CHIP = Color(0xB80C0A08)

/** The key, drawn with the marks themselves: the corner number, the square, and how to see a label. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Key(numbers: Boolean, marks: Boolean) {
    val style = SpreadType.ui.copy(fontSize = 12.sp, lineHeight = 16.sp)
    FlowRow(
        Modifier.padding(start = 16.dp, end = 16.dp, top = 10.dp), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (numbers) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Box(Modifier.height(18.dp).background(CHIP).padding(horizontal = 5.dp).clearAndSetSemantics {}, contentAlignment = Alignment.Center) {
                Text("+6", style = SpreadType.uiMedium.copy(fontSize = 10.5.sp, lineHeight = 12.sp), color = Color.White)    // l10n-ignore: a sample of the mark
            }
            Text(stringResource(R.string.topic_quilt_key_more), style = style, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (marks) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Box(Modifier.size(10.dp).background(MaterialTheme.colorScheme.onSurface).padding(1.5.dp).background(SpreadColors.accent))
            Text(stringResource(R.string.topic_quilt_key_shared), style = style, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(stringResource(R.string.topic_quilt_key_hold), style = style, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Every group with its count, in two columns, largest first; a group opens in full as a list under the index. */
@Composable
private fun Index(groups: List<QuiltGroup>, open: Int?, onOpen: (Int) -> Unit, onTouch: (QuiltPlate) -> Unit) {
    Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 22.dp)) {
        Eyebrow(stringResource(R.string.topic_quilt_index), Modifier.padding(bottom = 8.dp).semantics { heading() }, color = MaterialTheme.colorScheme.onSurfaceVariant)
        for (pair in groups.indices.chunked(2)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (i in pair) {
                    val g = groups[i]
                    Column(Modifier.weight(1f)) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        Row(
                            Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(role = Role.Button) { onOpen(i) }.semantics(mergeDescendants = true) {}.testTag(QuiltTags.group(i)),
                            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(
                                g.label(), style = SpreadType.serif.copy(fontSize = 14.5.sp, lineHeight = 17.sp),
                                color = if (open == i) SpreadColors.accent else MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f),
                            )
                            Text(app.granger.explore.count(g.plates.size), style = SpreadType.uiMedium.copy(fontSize = 12.5.sp, fontFeatureSettings = "tnum"), color = SpreadColors.accent)
                        }
                    }
                }
                if (pair.size == 1) Spacer(Modifier.weight(1f))
            }
        }
        open?.let { groups.getOrNull(it) }?.let { g ->
            Eyebrow(g.label(), Modifier.padding(top = 20.dp, bottom = 6.dp).semantics { heading() })
            for (p in g.plates) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(role = Role.Button) { onTouch(p) }.semantics(mergeDescendants = true) {}.testTag(QuiltTags.line(p.key)),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(p.title, style = SpreadType.serif.copy(fontSize = 15.sp, lineHeight = 20.sp), color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
                    p.sub?.let { Text(it, style = SpreadType.ui.copy(fontSize = 12.sp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
            }
        }
    }
}

/**
 * A held plate's label, on a dark band over the foot of the quilt: where the link was found, the plate's name, its
 * dates and rooms, its opening sentence, the topics that lead on from it, and the two things to do with it.
 */
@Composable
private fun Label(
    plate: QuiltPlate, where: String?, more: Int?, centre: String, language: String,
    around: suspend (packId: String, path: String, plates: List<Pair<String, String>>) -> QuiltAround, cardOf: suspend (packId: String, path: String) -> TopicArticle?,
    onStand: () -> Unit, onTouch: (QuiltPlate) -> Unit, onRead: () -> Unit, onDismiss: () -> Unit, modifier: Modifier = Modifier,
) {
    val words = yearWords()
    val read by produceState<QuiltAround?>(null, plate.key) {
        value = if (plate.packId != null && plate.path != null) around(plate.packId, plate.path, emptyList()) else QuiltAround()
    }
    val ground = MaterialTheme.colorScheme.inverseSurface
    val ink = MaterialTheme.colorScheme.inverseOnSurface
    val bright = MaterialTheme.colorScheme.inversePrimary
    Column(
        modifier.fillMaxWidth().background(ground).clickable(onClickLabel = stringResource(R.string.common_close), onClick = onDismiss)
            .padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 18.dp).testTag(QuiltTags.LABEL),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            val file = plate.picture ?: read?.card?.picture
            if (file != null) {
                if (ArticleThumbnails.isWhole(file)) WholePicture(file, 200.dp, Modifier.size(88.dp).background(Color.White).padding(4.dp).clearAndSetSemantics {})
                else FramedPicture(file, 200.dp, Modifier.size(88.dp).clearAndSetSemantics {})
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                where?.let { Eyebrow(it, color = bright) }
                Text(plate.title, style = SpreadType.display.copy(fontSize = 22.sp, lineHeight = 25.sp), color = ink, modifier = Modifier.semantics { heading() })
                val facts = listOfNotNull(read?.mark?.let { words.of(it) } ?: plate.sub) + read?.rooms.orEmpty().take(2).map { it.name(language) }
                if (facts.isNotEmpty()) Text(facts.joinToString(" · "), style = SpreadType.ui.copy(fontSize = 12.5.sp), color = ink.copy(alpha = 0.85f))
            }
        }
        read?.card?.blurb?.takeIf { it.isNotBlank() }?.let {
            Text(it, style = SpreadType.serif.copy(fontSize = 15.sp, lineHeight = 22.sp), color = ink, maxLines = 5, overflow = TextOverflow.Ellipsis)
        }
        val on = read?.leads.orEmpty().filter { Quilts.key(it.packId, it.path) != centre }
        if (on.isNotEmpty()) {
            val n = maxOf(more ?: 0, on.size)
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Eyebrow(pluralStringResource(R.plurals.topic_quilt_more, n, n), color = bright)
                Row(Modifier.fillMaxWidth().height(74.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    for (a in on.take(3)) {
                        val p = QuiltPlate(Quilts.key(a.packId, a.path), a.title, a.picture, packId = a.packId, path = a.path)
                        Tile(p, Modifier.weight(1f).fillMaxHeight(), 12.sp, tab = null, group = null, more = 0, shared = false, dim = false, cardOf = cardOf, onTouch = { onTouch(p) }, onHold = { onTouch(p) })
                    }
                    repeat(3 - minOf(3, on.size)) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = onStand, shape = RoundedCornerShape(2.dp), modifier = Modifier.heightIn(min = 48.dp).testTag(QuiltTags.STAND),
                colors = ButtonDefaults.buttonColors(containerColor = bright, contentColor = ground),
            ) { Text(stringResource(R.string.topic_quilt_stand), style = SpreadType.uiMedium.copy(fontSize = 14.sp)) }
            OutlinedButton(
                onClick = onRead, shape = RoundedCornerShape(2.dp), modifier = Modifier.heightIn(min = 48.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = ink), border = androidx.compose.foundation.BorderStroke(1.dp, ink),
            ) { Text(stringResource(R.string.topic_spread_read), style = SpreadType.uiMedium.copy(fontSize = 14.sp)) }
        }
    }
}
