package app.granger.home

import android.content.res.Resources
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import app.granger.UiText
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.granger.R
import app.granger.core.library.FeedKind
import app.granger.shell.RailEntry
import app.granger.shell.mediaKind
import app.granger.shell.rememberRailCover
import app.granger.theme.MediaTile

/** What Home's downloads card can do (Plan DL Decision 7); a tap on it opens Downloads. */
class DownloadsCardActions(
    val onPause: () -> Unit = {},
    val onResume: () -> Unit = {},
    val onRetry: (DownloadsCard.Stopped) -> Unit = {},
    val onDismiss: (DownloadsCard.Stopped) -> Unit = {},
    /** A tidy offer's Remove and Keep both (Plan WF Decision 5). */
    val onTidyRemove: (app.granger.sources.TidyOffer) -> Unit = {},
    val onTidyKeep: (app.granger.sources.TidyOffer) -> Unit = {},
)

/** What the screen can do for a feed. */
class FeedActions(
    val onAdd: (FeedScope, FeedKind, String) -> Unit = { _, _, _ -> },
    val onRemove: (Long) -> Unit = {},
    val onMove: (Long, Boolean) -> Unit = { _, _ -> },
)

/** [onOpenTab] shows a reading tab; [onPlay] plays a rail item again; [onOpenCard] opens a feed card's target. */
@Composable
fun HomeScreen(
    viewModel: HomeViewModel,
    onReadiness: () -> Unit,
    onOpenTab: (Long) -> Unit,
    onPlay: (RailEntry.Play) -> Unit,
    onOpenCard: (Card) -> Unit,
    /** The downloads card opens Downloads, where the whole queue is (Plan DL Decision 7). */
    onOpenDownloads: () -> Unit = {},
    crashNotice: CrashNotice? = null,
    onOpenTimeline: (app.granger.core.library.TimeMark?) -> Unit = {},
    onOpenGames: () -> Unit = {},
) {
    LifecycleResumeEffect(Unit) {
        viewModel.refresh()
        onPauseOrDispose {}
    }
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val actions = remember(viewModel) { FeedActions(viewModel::addFeed, viewModel::removeFeed, viewModel::moveFeed) }
    val cardActions = remember(viewModel) {
        DownloadsCardActions(
            viewModel::pauseDownloads, viewModel::resumeDownloads, viewModel::retryStopped, viewModel::dismissStopped,
            viewModel::removeSmaller, viewModel::keepBoth,
        )
    }
    val wanderModel = viewModel.wander
    val wander = wanderModel?.ui?.collectAsStateWithLifecycle()?.value
    val wanderActions = remember(wanderModel) {
        if (wanderModel == null) WanderActions() else WanderActions(
            onShown = wanderModel::ensureStarted, onGo = wanderModel::go, onBack = wanderModel::back,
            onElsewhere = { wanderModel.start() }, onQuery = wanderModel::search, onPick = wanderModel::pick,
        )
    }
    val timelineModel = viewModel.timeline
    val timeline = timelineModel?.ui?.collectAsStateWithLifecycle()?.value
    val timelineActions = remember(timelineModel) { timelineModel?.let(::timelineActions) ?: TimelineActions() }
    val gameModel = viewModel.games
    val game = gameModel?.ui?.collectAsStateWithLifecycle()?.value
    val gameActions = remember(gameModel) { gameModel?.let(::gameActions) ?: GameActions() }
    HomeContent(
        ui, onReadiness, onOpenTab, onPlay, onOpenCard, actions, onOpenDownloads, cardActions, crashNotice,
        wander = wander, wanderActions = wanderActions, timeline = timeline, timelineActions = timelineActions,
        onOpenTimeline = onOpenTimeline, game = game, gameActions = gameActions, onOpenGames = onOpenGames,
        arrangeActions = remember(viewModel) { ArrangeActions(viewModel::arrange) },
    )
}

/** After a crash, Home asks once (Plan F1 part 2 Decision 3): Send report opens Send feedback; Not now lets it be. */
class CrashNotice(val onSend: () -> Unit, val onDismiss: () -> Unit)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeContent(
    ui: HomeUi,
    onReadiness: () -> Unit,
    onOpenTab: (Long) -> Unit,
    onPlay: (RailEntry.Play) -> Unit,
    onOpenCard: (Card) -> Unit = {},
    actions: FeedActions = FeedActions(),
    /** The downloads card opens Downloads, where the whole queue is (Plan DL Decision 7). */
    onOpenDownloads: () -> Unit = {},
    downloadsActions: DownloadsCardActions = DownloadsCardActions(),
    crashNotice: CrashNotice? = null,
    /** Wander (Plan H5a); null hides it. */
    wander: WanderUi? = null,
    wanderActions: WanderActions = WanderActions(),
    /** The timeline (Plan H5b); null hides it. */
    timeline: TimelineUi? = null,
    timelineActions: TimelineActions = TimelineActions(),
    /** Opens the full-screen Timeline on the mark Home's lens is on (Neal, 2026-11-08). */
    onOpenTimeline: (app.granger.core.library.TimeMark?) -> Unit = {},
    /** Play (Plan H5c); null hides it. */
    game: GameUi? = null,
    gameActions: GameActions = GameActions(),
    onOpenGames: () -> Unit = {},
    arrangeActions: ArrangeActions = ArrangeActions(),
) {
    var adding by rememberSaveable { mutableStateOf(false) }
    var arranging by rememberSaveable { mutableStateOf(false) }
    val feedIds = ui.feeds.map { it.feed.id }
    val parts = ui.layout.visible(feedIds)
    val openMark: (app.granger.core.library.TimeMark) -> Unit = { mark -> onOpenCard(Card(mark.title, "", CardTarget.Article(mark.packId, mark.path))) }
    if (arranging) {
        ArrangeHome(
            parts = ui.layout.parts(feedIds),
            hidden = ui.layout.hidden,
            label = { part -> partLabel(part, ui.feeds) },
            onDone = { arranged ->
                arrangeActions.onArrange(arranged)
                arranging = false
            },
            defaults = HomeLayout().parts(feedIds),
            onCancel = { arranging = false },
        )
        return
    }
    val listState = rememberLazyListState()
    // Sections arrive at different times (Continue after the timeline, a downloads card later still). Until the user
    // scrolls, Home stays at its top, so a section landing above doesn't push the first one off screen (Neal, 2026-11-10:
    // Home opened on the timeline with Continue just above it).
    var touched by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(listState) {
        listState.interactionSource.interactions.collect { if (it is androidx.compose.foundation.interaction.DragInteraction.Start) touched = true }
    }
    val keys = listOfNotNull(crashNotice?.let { "crash" }, ui.downloads?.let { "downloads" }) + ui.tidy.map { it.key } + parts.map { it.token }
    LaunchedEffect(keys) {
        if (!touched && (listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 0)) listState.scrollToItem(0)
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.home_title)) },
                actions = {
                    IconButton(onClick = { arranging = true }, modifier = Modifier.testTag("home-arrange")) {
                        Icon(Icons.Filled.Edit, contentDescription = stringResource(R.string.home_arrange))
                    }
                    IconButton(onClick = { adding = true }) { Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.home_add_feed)) }
                },
            )
        },
    ) { padding ->
        LazyColumn(Modifier.padding(padding).fillMaxSize().testTag("home-list"), state = listState) {
            if (adding) {
                item(key = "add-feed") {
                    AddFeedPanel(
                        ui.scopeChoices,
                        onAdd = { scope, kind, query ->
                            actions.onAdd(scope, kind, query)
                            adding = false
                        },
                        onCancel = { adding = false },
                    )
                }
            }
            // What needs acting on stays at the top; everything else comes in the order the user arranged (Plan HY),
            // by default Continue, Timeline, Play, Wander, the feeds and Readiness (Neal, 2026-11-08).
            crashNotice?.let { notice -> item(key = "crash") { HomeSection("crash-section", bottom = 0.dp) { CrashNoticeRow(notice) } } }
            ui.downloads?.let { card ->
                item(key = "downloads") { HomeSection("downloads-section", bottom = 0.dp) { DownloadsCardView(card, onOpenDownloads, downloadsActions) } }
            }
            for (offer in ui.tidy) {
                item(key = "tidy-${offer.key}") {
                    app.granger.sources.TidyOfferCard(offer, { downloadsActions.onTidyRemove(offer) }, { downloadsActions.onTidyKeep(offer) })
                }
            }
            val feedsById = ui.feeds.associateBy { it.feed.id }
            for ((i, part) in parts.withIndex()) {
                when (part) {
                    HomePart.Continue -> if (ui.continueCards.isNotEmpty()) item(key = "continue") {
                        HomeSection("continue-section") {
                            Heading(stringResource(R.string.home_continue))
                            CardRow(ui.continueCards, key = { it.key }, tag = "continue-row") { entry ->
                                when (entry) {
                                    is RailEntry.Tab -> TabCard(entry) { onOpenTab(entry.tab.tabId) }
                                    is RailEntry.Play -> PlayCard(entry) { onPlay(entry) }
                                    else -> {}
                                }
                            }
                        }
                    }
                    HomePart.Timeline -> if (timeline != null && timeline.available) item(key = "timeline") {
                        HomeSection("timeline-section") { TimelineSection(timeline, timelineActions, onOpen = openMark, onExpand = onOpenTimeline) }
                    }
                    HomePart.Play -> if (game != null && game.available) item(key = "play") {
                        HomeSection("play-section") { PlaySection(game, gameActions, onOpen = openMark, onMore = onOpenGames) }
                    }
                    HomePart.Wander -> if (wander != null && wander.available) item(key = "wander") {
                        HomeSection("wander-section") {
                            WanderSection(wander, wanderActions) { node -> onOpenCard(Card(node.title, "", CardTarget.Article(node.packId, node.path))) }
                        }
                    }
                    HomePart.Readiness -> item(key = "strip") {
                        HomeSection("readiness-section", bottom = 0.dp) {
                            SectionHeading(
                                stringResource(R.string.readiness_title),
                                action = stringResource(R.string.home_readiness_see_all),
                                onAction = onReadiness,
                                actionTag = "readiness-see-all",
                            )
                            ReadinessStrip(ui.strip, onReadiness)
                        }
                    }
                    is HomePart.Feed -> feedsById[part.id]?.let { row ->
                        item(key = "feed-${row.feed.id}") {
                            HomeSection("feed-section-${row.feed.id}") {
                                FeedHeading(
                                    title = if (row.drawn != null) row.title.resolve() else stringResource(R.string.home_feed_pending, stringResource(row.feed.kind.label), row.scopeName.resolve()),
                                    first = i == 0,
                                    last = i == parts.lastIndex,
                                    onRemove = { actions.onRemove(row.feed.id) },
                                    onMove = { up -> actions.onMove(row.feed.id, up) },
                                    onArrange = { arranging = true },
                                )
                                val drawn = row.drawn
                                if (drawn == null) {
                                    Text(
                                        stringResource(R.string.home_drawing),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                                    )
                                } else {
                                    CardRow(drawn.cards, key = { "${it.title}|${it.target}" }, tag = "feed-row-${row.feed.id}") { card ->
                                        FeedCard(card) { onOpenCard(card) }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            item(key = "end") { Box(Modifier.height(16.dp)) }
        }
    }
}

/** What Arrange does: save the order and hidden sections (Plan HY). */
class ArrangeActions(val onArrange: (HomeLayout) -> Unit = {})

/** A section's name in Arrange: its heading on Home, a feed's title. */
@Composable
private fun partLabel(part: HomePart, feeds: List<FeedUi>): String = when (part) {
    HomePart.Continue -> stringResource(R.string.home_continue)
    HomePart.Timeline -> stringResource(R.string.home_timeline)
    HomePart.Play -> stringResource(R.string.home_play)
    HomePart.Wander -> stringResource(R.string.home_wander)
    HomePart.Readiness -> stringResource(R.string.readiness_title)
    is HomePart.Feed -> feeds.firstOrNull { it.feed.id == part.id }?.let { row ->
        if (row.drawn != null) row.title.resolve() else stringResource(R.string.home_feed_pending, stringResource(row.feed.kind.label), row.scopeName.resolve())
    }.orEmpty()
}

@Composable
private fun <T> CardRow(items: List<T>, key: (T) -> Any, tag: String, card: @Composable (T) -> Unit) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.testTag(tag),
    ) {
        items(items, key = key) { card(it) }
    }
}

/** One of Home's new pieces on its own surface, so a section never runs into the next (spec §3, Neal 2026-11-07). */
@Composable
internal fun HomeSection(tag: String, bottom: androidx.compose.ui.unit.Dp = 16.dp, content: @Composable () -> Unit) {
    androidx.compose.material3.Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp).testTag(tag),
    ) {
        androidx.compose.foundation.layout.Column(Modifier.padding(bottom = bottom)) { content() }
    }
}

/** A section's title, the same on every section of Home. */
@Composable
internal fun Heading(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier.padding(start = 16.dp, top = 16.dp, bottom = 8.dp),
    )
}

/** A heading with a "More ›" on the right (Full timeline, See all), lined up with the heading. */
@Composable
internal fun SectionHeading(text: String, action: String, onAction: () -> Unit, actionTag: String) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(end = 4.dp)) {
        Heading(text, Modifier.weight(1f).padding(bottom = 0.dp))
        TextButton(onClick = onAction, modifier = Modifier.padding(top = 8.dp).testTag(actionTag)) {
            Text(action, style = MaterialTheme.typography.labelLarge)
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, Modifier.padding(start = 2.dp).size(18.dp))
        }
    }
}

/** A feed's title; a long press offers Move up, Move down and Remove. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FeedHeading(title: String, first: Boolean, last: Boolean, onRemove: () -> Unit, onMove: (up: Boolean) -> Unit, onArrange: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Box {
        Heading(title, Modifier.combinedClickable(onClick = {}, onLongClick = { menu = true }))
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            if (!first) DropdownMenuItem(text = { Text(stringResource(R.string.home_move_up)) }, onClick = { menu = false; onMove(true) })
            if (!last) DropdownMenuItem(text = { Text(stringResource(R.string.home_move_down)) }, onClick = { menu = false; onMove(false) })
            DropdownMenuItem(text = { Text(stringResource(R.string.home_arrange)) }, onClick = { menu = false; onArrange() })
            DropdownMenuItem(text = { Text(stringResource(R.string.common_remove)) }, onClick = { menu = false; onRemove() })
        }
    }
}

private val CardWidth = 132.dp
private val TileHeight = 88.dp

/** A reading tab: its cover when it has one, else the themed tile (Plan U3c Decision 6); greyed when its pack is gone. */
@Composable
private fun TabCard(entry: RailEntry.Tab, onClick: () -> Unit) {
    val tab = entry.tab
    Card(title = tab.title, subtitle = tab.subtitle.resolve(), enabled = tab.available, onClick = onClick) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val cover = rememberRailCover(tab.coverPath, maxWidth)
            when {
                cover != null -> Image(cover, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                // An article without a picture: its icon, not its title a second time over the title (Neal, 2026-11-08).
                tab.path.isNotEmpty() -> IconTile(painterResource(R.drawable.ic_library))
                else -> MediaTile(tab.title, tab.mediaKind())
            }
        }
    }
}

/** Something played: the themed tile with its title and what it played (Continue keeps no covers for plays). */
@Composable
private fun PlayCard(entry: RailEntry.Play, onClick: () -> Unit) {
    Card(title = entry.title, subtitle = entry.kind, enabled = true, onClick = onClick) {
        MediaTile(entry.title, entry.mediaKind())
    }
}

/** A feed card: its picture when it has one, else an icon for what it opens; the line; where it is from. */
@Composable
private fun FeedCard(card: Card, onClick: () -> Unit) {
    Card(title = card.title, subtitle = card.subtitle, enabled = true, onClick = onClick) {
        if (card.imagePath != null) {
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val image = rememberRailCover(card.imagePath, maxWidth)
                if (image != null) {
                    Image(image, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                } else {
                    TargetIcon(card)
                }
            }
        } else {
            TargetIcon(card)
        }
    }
}

@Composable
private fun TargetIcon(card: Card) {
    IconTile(
        when (card.target) {
            is CardTarget.Article -> painterResource(R.drawable.ic_library)
            is CardTarget.Book -> painterResource(R.drawable.ic_library)
            is CardTarget.Album -> painterResource(R.drawable.ic_music_note)
            is CardTarget.Audiobook -> painterResource(R.drawable.ic_headphones)
            is CardTarget.Search -> rememberVectorPainter(Icons.Filled.Search)
        },
    )
}

@Composable
private fun IconTile(painter: Painter) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Icon(painter, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** A card in a row: a tile on top, a title and a subtitle under it. */
@Composable
internal fun Card(title: String, subtitle: String, enabled: Boolean, onClick: () -> Unit, tile: @Composable () -> Unit) {
    Column(
        Modifier
            .width(CardWidth)
            .alpha(if (enabled) 1f else 0.4f)
            .clickable(enabled = enabled, onClick = onClick),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(TileHeight)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
        ) { tile() }
        Text(
            title,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 6.dp),
        )
        if (subtitle.isNotBlank()) {
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * Home's one card for downloads, where the setup strip was: the file downloading, the round's bar and figures, and
 * Pause or Resume; or "2 stopped · Retry" with a close. A tap opens Downloads.
 */
@Composable
private fun DownloadsCardView(card: DownloadsCard, onOpen: () -> Unit, actions: DownloadsCardActions) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onOpen).padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp)
            .testTag("downloads-card"),
    ) {
        when (card) {
            is DownloadsCard.Active -> {
                Column(Modifier.weight(1f).padding(vertical = 4.dp)) {
                    Text(
                        homeDownloadsTitle(card).resolve(),
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    LinearProgressIndicator(progress = { card.fraction }, modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp))
                    Text(
                        homeDownloadsText(LocalContext.current.resources, card).resolve(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (card.paused) {
                    IconButton(onClick = actions.onResume) { Icon(Icons.Filled.PlayArrow, contentDescription = stringResource(R.string.home_downloads_resume)) }
                } else {
                    IconButton(onClick = actions.onPause) { Icon(painterResource(R.drawable.ic_pause), contentDescription = stringResource(R.string.home_downloads_pause)) }
                }
            }
            is DownloadsCard.Stopped -> {
                Text(
                    pluralStringResource(R.plurals.home_downloads_stopped, card.count, card.count),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.weight(1f),
                )
                if (card.retriable.isNotEmpty()) TextButton(onClick = { actions.onRetry(card) }) { Text(stringResource(R.string.common_retry)) }
                IconButton(onClick = { actions.onDismiss(card) }) { Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.common_close)) }
            }
        }
    }
}

/** The file downloading now; "Downloads paused" while paused. */
fun homeDownloadsTitle(card: DownloadsCard.Active): UiText = when {
    card.paused -> UiText.Res(R.string.home_downloads_paused)
    else -> card.title?.let { UiText.Res(R.string.common_downloading_named, it) } ?: UiText.Res(R.string.common_downloading)
}

/** "3 of 14 · 1.2 GB of 9.8 GB · about 25 min left", or "Paused · 11 waiting". */
fun homeDownloadsText(res: Resources, card: DownloadsCard.Active): UiText {
    if (card.paused) return UiText.Res(R.string.home_downloads_paused_waiting, card.waiting)
    val done = app.granger.formatBytes(card.bytesDone)
    val total = app.granger.formatBytes(card.bytes)
    return card.timeLeftMillis?.let {
        UiText.Res(R.string.home_downloads_progress_left, card.filesDone, card.files, done, total, app.granger.core.downloads.QueueText.timeLeft(res, it))
    } ?: UiText.Res(R.string.home_downloads_progress, card.filesDone, card.files, done, total)
}

@Composable
private fun CrashNoticeRow(notice: CrashNotice) {
    Row(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp).testTag("crash-notice"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(stringResource(R.string.home_crash_notice), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        TextButton(onClick = notice.onDismiss) { Text(stringResource(R.string.home_crash_not_now)) }
        TextButton(onClick = notice.onSend) { Text(stringResource(R.string.home_crash_send)) }
    }
}
