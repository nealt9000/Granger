package app.granger.home

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.granger.R
import app.granger.core.library.SearchHit
import app.granger.shell.rememberRailCover
import app.granger.theme.Spectral
import kotlinx.coroutines.launch
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/** What Wander's controls do; [onShown] starts it the first time Home shows it. */
class WanderActions(
    val onShown: () -> Unit = {},
    val onGo: (WanderNode) -> Unit = {},
    val onBack: (Int) -> Unit = {},
    val onElsewhere: () -> Unit = {},
    val onQuery: (String) -> Unit = {},
    val onPick: (SearchHit) -> Unit = {},
)

private val CentreSize = 92.dp
private val LabelWidth = 96.dp
private val MaxSide = 380.dp

/** An orbit at least this wide has room for all eight links; a narrower one shows [NARROW_ORBIT]. */
private val WideSide = 360.dp
private const val NARROW_ORBIT = 6

/** Under the lowest disc: its half, the gap and two lines of label. */
private val LabelRoom = 70.dp

/** The orbit's radius, as a share of the square's side. */
private const val RADIUS = 0.39f

/**
 * The lowest disc's centre, as a share of the side, for [n] links placed half a step round: the middle plus the radius
 * (with its outward nudge) times the largest sine any link reaches.
 */
internal fun orbitBottom(n: Int): Float {
    if (n == 0) return 0.5f
    val step = 2 * Math.PI / n
    val lowest = (0 until n).maxOf { sin(-Math.PI / 2 + step / 2 + it * step) }.toFloat()
    return 0.5f + (RADIUS + 0.03f) * lowest
}

/** Orbit discs shrink by rank, the closest link largest (Plan H5a Task 3). */
private fun orbitSize(rank: Int): Dp = (62 - rank * 3).coerceAtLeast(44).dp

/**
 * Wander (Plan H5a): the start search, the constellation, then the centre's summary, Open and the trail. [onOpen]
 * opens an article in the reader.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun WanderSection(ui: WanderUi, actions: WanderActions, onOpen: (WanderNode) -> Unit) {
    LaunchedEffect(Unit) { actions.onShown() }
    Heading(stringResource(R.string.home_wander))
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).testTag("wander"), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        StartSearch(ui, actions)
        val centre = ui.centre
        if (centre == null) {
            Text(
                stringResource(R.string.home_wander_finding),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@Column
        }
        Constellation(ui, actions, Modifier.align(Alignment.CenterHorizontally))
        // The centre's title sits here, beside Open, not under its disc where the orbit's labels ran into it (Neal,
        // 2026-11-08); the summary is a taste of the article, three lines at most.
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                centre.title,
                style = MaterialTheme.typography.titleMedium.copy(fontFamily = Spectral),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).testTag("wander-title"),
            )
            FilledTonalButton(onClick = { onOpen(centre) }, modifier = Modifier.testTag("wander-open")) { Text(stringResource(R.string.home_wander_open)) }
        }
        if (ui.summary.isNotBlank()) {
            Text(
                ui.summary,
                style = MaterialTheme.typography.bodyMedium.copy(fontFamily = Spectral),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.testTag("wander-summary"),
            )
        }
        if (ui.trail.isNotEmpty()) {
            FlowRow(verticalArrangement = Arrangement.Center, modifier = Modifier.testTag("wander-trail")) {
                Text(
                    stringResource(R.string.home_wander_path),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.align(Alignment.CenterVertically).padding(end = 4.dp),
                )
                ui.trail.forEachIndexed { i, crumb ->
                    TextButton(onClick = { actions.onBack(i) }, modifier = Modifier.testTag("wander-crumb-$i")) {
                        Text(crumb.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
                    }
                    Text("→", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.align(Alignment.CenterVertically))
                }
                Text(
                    centre.title,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.align(Alignment.CenterVertically).padding(start = 8.dp),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** The search field with Somewhere else beside it; up to six title hits under it (Plan H5a Task 4). */
@Composable
private fun StartSearch(ui: WanderUi, actions: WanderActions) {
    val focus = androidx.compose.ui.platform.LocalFocusManager.current
    // Picking a start puts the keyboard away, so the constellation it starts is in view.
    val pick: (SearchHit) -> Unit = { focus.clearFocus(); actions.onPick(it) }
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = ui.query,
                onValueChange = actions.onQuery,
                singleLine = true,
                placeholder = { Text(stringResource(R.string.home_wander_search)) },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                trailingIcon = if (ui.query.isEmpty()) null else {
                    { IconButton(onClick = { actions.onQuery("") }) { Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.library_clear)) } }
                },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { ui.hits.firstOrNull()?.let(pick) }),
                modifier = Modifier.weight(1f).testTag("wander-search"),
            )
            IconButton(onClick = { focus.clearFocus(); actions.onElsewhere() }, modifier = Modifier.testTag("wander-elsewhere")) {
                Icon(Icons.Filled.Refresh, contentDescription = stringResource(R.string.home_wander_elsewhere))
            }
        }
        if (ui.query.isNotBlank()) {
            if (ui.hits.isEmpty()) {
                Text(
                    stringResource(R.string.home_wander_no_match),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 16.dp, top = 4.dp),
                )
            }
            for ((i, hit) in ui.hits.withIndex()) {
                Text(
                    hit.title,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth().clickable { pick(hit) }.padding(horizontal = 16.dp, vertical = 10.dp).testTag("wander-hit-$i"),
                )
            }
        }
    }
}

/**
 * The centre and its orbit in a square. Every node is keyed by its article, so the one you tap keeps its identity as
 * it moves to the middle; new links grow out from the centre, a few milliseconds apart (Decision 8). With animations
 * off, Compose's animations finish at once.
 */
@Composable
private fun Constellation(ui: WanderUi, actions: WanderActions, modifier: Modifier = Modifier) {
    val ring = MaterialTheme.colorScheme.outlineVariant
    BoxWithConstraints(modifier.widthIn(max = MaxSide).fillMaxWidth()) {
      val side = maxWidth
      // Only as tall as the lowest disc and its label need: the square's bottom was empty space (Neal, 2026-11-08).
      // On a phone-width orbit, eight links' labels run into their neighbours' discs (2026-11-08); the six closest fit.
      val orbit = if (side < WideSide) ui.orbit.take(NARROW_ORBIT) else ui.orbit
      Box(Modifier.width(side).height(side * orbitBottom(orbit.size) + LabelRoom).testTag("wander-orbit")) {
        Canvas(Modifier.width(side).height(side)) {
            drawCircle(ring, radius = size.minDimension * RADIUS, center = center, style = Stroke(width = 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 8f))))
        }
        val centre = ui.centre ?: return@Box
        val nodes = listOf(centre) + orbit
        nodes.forEachIndexed { i, node ->
            val isCentre = i == 0
            val rank = i - 1
            val target = if (isCentre) Offset(0.5f, 0.5f) else {
                // Half a step round, so no link sits right above the centre or on its title below.
                val step = 2 * Math.PI / orbit.size
                val angle = -Math.PI / 2 + step / 2 + rank * step
                val r = RADIUS + if (rank % 2 == 1) 0.03f else -0.02f
                Offset(0.5f + r * cos(angle).toFloat(), 0.5f + r * sin(angle).toFloat())
            }
            key(node.packId + "/" + node.path) {
                OrbitNode(
                    node = node,
                    isCentre = isCentre,
                    target = target,
                    side = side,
                    size = if (isCentre) CentreSize else orbitSize(rank),
                    delayMillis = if (isCentre) 0 else rank * 30,
                    onClick = { if (!isCentre) actions.onGo(node) },
                )
            }
        }
      }
    }
}

@Composable
private fun OrbitNode(node: WanderNode, isCentre: Boolean, target: Offset, side: Dp, size: Dp, delayMillis: Int, onClick: () -> Unit) {
    // A new node starts at the middle, small and clear; the tapped one starts where it was.
    val x = remember { Animatable(0.5f) }
    val y = remember { Animatable(0.5f) }
    val shown = remember { Animatable(if (isCentre) 1f else 0f) }
    LaunchedEffect(target) {
        kotlinx.coroutines.delay(delayMillis.toLong())
        kotlinx.coroutines.coroutineScope {
            launch { x.animateTo(target.x, spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessLow)) }
            launch { y.animateTo(target.y, spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessLow)) }
            launch { shown.animateTo(1f, tween(250)) }
        }
    }
    val disc by animateDpAsState(size, spring(stiffness = Spring.StiffnessLow), label = "disc")
    val width = if (isCentre) disc else maxOf(disc, LabelWidth)
    val label = if (!node.onPhone) stringResource(R.string.home_wander_not_here, node.title)
        else if (isCentre) node.title else stringResource(R.string.home_wander_go_to, node.title)
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier
            .offset {
                // A label near the edge stays inside the orbit rather than off the card ("Government of India", 2026-11-08).
                val left = (side * x.value - width / 2).roundToPx().coerceIn(0, (side - width).roundToPx().coerceAtLeast(0))
                IntOffset(left, ((side * y.value) - disc / 2).roundToPx())
            }
            .width(width)
            .alpha(shown.value * if (node.onPhone) 1f else 0.6f)
            .then(if (isCentre) Modifier else Modifier.clickable(enabled = node.onPhone, onClick = onClick))
            .semantics(mergeDescendants = true) { contentDescription = label }
            .testTag(if (isCentre) "wander-centre" else "wander-node-${node.path}"),
    ) {
        Disc(node, disc, isCentre)
        // The centre's title is under the orbit, beside Open (WanderSection).
        if (!isCentre) {
            Text(
                node.title,
                style = MaterialTheme.typography.labelMedium,
                fontSize = 11.5.sp,
                lineHeight = 13.sp,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                color = if (node.onPhone) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun Disc(node: WanderNode, size: Dp, isCentre: Boolean) {
    val colors = MaterialTheme.colorScheme
    val picture = rememberRailCover(node.imagePath, size)
    val edge = when {
        isCentre -> Modifier.border(3.dp, colors.primary, CircleShape)
        !node.onPhone -> Modifier.border(1.5.dp, colors.outline, CircleShape)
        else -> Modifier.border(2.dp, colors.surface, CircleShape)
    }
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.size(size).clip(CircleShape).background(if (node.onPhone) colors.surfaceContainerHigh else colors.surface).then(edge),
    ) {
        if (picture != null) {
            Image(picture, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else {
            Text(initials(node.title), fontFamily = Spectral, fontSize = (size.value * 0.32f).sp, color = colors.onSurfaceVariant)
        }
    }
}

/** Up to two initials of a title, brackets left out: "Byzantine Empire (330–1453)" → "BE". */
internal fun initials(title: String): String =
    title.replace(Regex("\\(.*?\\)"), " ").split(Regex("[\\s_-]+")).filter { it.isNotEmpty() && it[0].isLetter() }.take(2)
        .joinToString("") { it.take(1).uppercase() }
