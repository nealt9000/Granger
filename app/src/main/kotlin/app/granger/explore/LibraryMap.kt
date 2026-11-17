package app.granger.explore

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.granger.R
import app.granger.topic.MAP_NODE_WIDTH
import app.granger.topic.MapCircle
import java.text.NumberFormat
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** How the library map arranges the library around its centre (mockup C's tabs; Places waits for the Atlas, LL6). */
enum class MapArrange { SUBJECTS, TIME }

/** Where the library map is centred: the whole library, one subject, or one era. */
sealed interface MapCentre {
    data object Library : MapCentre
    data class OfSubject(val subject: Subject) : MapCentre
    data class OfEra(val era: Era) : MapCentre
}

/** One circle on the library map: a subject, an era or an article, with its count when it stands for many. */
data class MapSpot(val key: String, val title: String, val picture: String?, val count: String? = null, val weight: Double = 1.0)

/**
 * Where the library map puts its circles (pure, for tests): up to [INNER] on an inner oval from the top, clockwise; then
 * up to [OUTER] on an outer one's diagonals, where a phone has room: the topic map's ten (and mockup C's). More crowd a
 * phone (16 and 12 did, on the Pixel): the rest go below the map. Each place is (ring, angle in degrees).
 */
object LibraryMapLayout {
    const val INNER = 6
    private val OUTER_ANGLES = listOf(-135.0, -45.0, 135.0, 45.0)
    val OUTER = OUTER_ANGLES.size
    val MAX = INNER + OUTER

    /** [ring]: how many the inner ring takes (the eras' seven go round one ring, in order). */
    fun places(n: Int, ring: Int = INNER): List<Pair<Int, Double>> {
        val inner = minOf(n, ring)
        val outer = minOf(n - inner, OUTER)
        return List(inner) { i -> 0 to -90.0 + 360.0 * i / inner } + List(outer) { i -> 1 to OUTER_ANGLES[i] }
    }

    /** A circle's size by its [weight] against the largest [max]: area follows the count, between [MIN] and [BIG]. */
    fun size(weight: Double, max: Double): Dp {
        if (max <= 0.0) return MIN
        val f = sqrt((weight / max).coerceIn(0.0, 1.0))
        return MIN + (BIG - MIN) * f.toFloat()
    }

    val MIN = 40.dp
    val BIG = 68.dp
}

object LibraryMapTags {
    const val MAP = "library_map"
    const val SPOT = "library_map_spot"
    const val TIME = "library_map_time"
    const val SUBJECTS = "library_map_subjects"
    const val OPEN = "library_map_open"
}


/**
 * The library as a map (Plan EX Task 5, mockup C): your library in the middle, its subjects (or its eras) around it,
 * sized by how many articles each holds. A tap steps inside one, its best-known articles taking the ring; a long press,
 * or the button below, opens its list (an article's topic page). Back steps out, then closes the map.
 */
@Composable
fun LibraryMap(
    ui: ExploreUi,
    inside: suspend (MapCentre) -> List<ExploreArticle>,
    actions: ExploreActions,
    onClose: () -> Unit,
) {
    var arrange by rememberSaveable { mutableStateOf(MapArrange.SUBJECTS) }
    val centres = remember { mutableStateListOf<MapCentre>(MapCentre.Library) }
    val centre = centres.last()
    fun back() = if (centres.size > 1) centres.removeAt(centres.lastIndex).let { } else onClose()
    BackHandler { back() }

    val centreName = when (centre) {
        MapCentre.Library -> ""
        is MapCentre.OfSubject -> stringResource(centre.subject.label)
        is MapCentre.OfEra -> stringResource(centre.era.label)
    }
    // The article named like the centre (History inside History) would only repeat it.
    val articles by produceState<List<ExploreArticle>?>(null, centre) {
        value = if (centre == MapCentre.Library) emptyList() else inside(centre).filterNot { it.title.equals(centreName, ignoreCase = true) }
    }
    val allDoors = ui.doors.filter { it.count > 0 }
    val doors = allDoors.take(LibraryMapLayout.MAX)
    val rest = allDoors.drop(LibraryMapLayout.MAX)
    val spots: List<MapSpot> = when (centre) {
        MapCentre.Library -> if (arrange == MapArrange.SUBJECTS) {
            doors.map { MapSpot(it.subject.id, stringResource(it.subject.label), it.picture, count(it.count), it.count.toDouble()) }
        } else {
            ui.eras.map { MapSpot(it.era.name, stringResource(it.era.label), it.picture, count(it.count), it.count.toDouble()) }
        }
        else -> articles.orEmpty().map { MapSpot(it.packId + "/" + it.path, it.title, it.picture) }
    }
    val max = spots.maxOfOrNull { it.weight } ?: 1.0

    fun open(c: MapCentre) = when (c) {
        is MapCentre.OfSubject -> actions.onOpenSubject(c.subject)
        is MapCentre.OfEra -> {
            val first = ui.eras.firstOrNull { it.era == c.era }?.examples?.firstOrNull()
            actions.onOpenTimeline(first?.packId, first?.path)
        }
        MapCentre.Library -> Unit
    }
    fun tap(i: Int) {
        when (centre) {
            MapCentre.Library -> centres += if (arrange == MapArrange.SUBJECTS) MapCentre.OfSubject(doors[i].subject) else MapCentre.OfEra(ui.eras[i].era)
            else -> articles?.getOrNull(i)?.let { actions.onOpenArticle(it.packId, it.path, it.title) }
        }
    }
    fun hold(i: Int) {
        when (centre) {
            MapCentre.Library -> open(if (arrange == MapArrange.SUBJECTS) MapCentre.OfSubject(doors[i].subject) else MapCentre.OfEra(ui.eras[i].era))
            else -> articles?.getOrNull(i)?.let { actions.onOpenArticle(it.packId, it.path, it.title) }
        }
    }

    val centreTitle = when (centre) {
        MapCentre.Library -> stringResource(R.string.explore_map_library)
        is MapCentre.OfSubject -> stringResource(centre.subject.label)
        is MapCentre.OfEra -> stringResource(centre.era.label)
    }
    val centreCount = when (centre) {
        MapCentre.Library -> pluralStringResource(R.plurals.explore_topics, ui.articles, count(ui.articles))
        is MapCentre.OfSubject -> ui.doors.firstOrNull { it.subject == centre.subject }?.let { pluralStringResource(R.plurals.explore_topics, it.count, count(it.count)) }
        is MapCentre.OfEra -> ui.eras.firstOrNull { it.era == centre.era }?.let { pluralStringResource(R.plurals.explore_topics, it.count, count(it.count)) }
    }
    val centrePicture = when (centre) {
        MapCentre.Library -> null
        is MapCentre.OfSubject -> ui.doors.firstOrNull { it.subject == centre.subject }?.picture
        is MapCentre.OfEra -> ui.eras.firstOrNull { it.era == centre.era }?.picture
    }

    Surface(Modifier.fillMaxSize().testTag(LibraryMapTags.MAP), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.statusBarsPadding().navigationBarsPadding()) {
            Row(Modifier.fillMaxWidth().height(52.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = ::back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.common_back)) }
                Text(centreTitle, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (centre == MapCentre.Library) {
                Row(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(arrange == MapArrange.SUBJECTS, onClick = { arrange = MapArrange.SUBJECTS },
                        label = { Text(stringResource(R.string.explore_map_subjects)) }, modifier = Modifier.testTag(LibraryMapTags.SUBJECTS))
                    if (ui.eras.isNotEmpty()) FilterChip(arrange == MapArrange.TIME, onClick = { arrange = MapArrange.TIME },
                        label = { Text(stringResource(R.string.explore_map_time)) }, modifier = Modifier.testTag(LibraryMapTags.TIME))
                }
            }
            BoxWithConstraints(Modifier.fillMaxWidth().weight(1f)) {
                val w = maxWidth
                val h = maxHeight
                val cx = w / 2
                val cy = h * 0.47f
                // Ovals, as on the topic map: a phone's map is tall, and the diagonals sit well clear of the inner ring's
                // labels, which hang below their circles.
                val radii = listOf(w * 0.30f to h * 0.26f, w * 0.40f to h * 0.52f)
                val timeRing = centre == MapCentre.Library && arrange == MapArrange.TIME
                val places = LibraryMapLayout.places(spots.size, if (timeRing) spots.size else LibraryMapLayout.INNER)
                val placed = spots.zip(places).map { (spot, place) ->
                    val (ring, deg) = place
                    val size = if (centre == MapCentre.Library) LibraryMapLayout.size(spot.weight, max) else if (ring == 0) 52.dp else 44.dp
                    val a = Math.toRadians(deg)
                    val (rx, ry) = radii[ring]
                    val x = (cx + rx * cos(a).toFloat()).coerceIn(MAP_NODE_WIDTH / 2, w - MAP_NODE_WIDTH / 2)
                    val y = (cy + ry * sin(a).toFloat()).coerceIn(size / 2, h - size / 2 - 44.dp)
                    Triple(spot, x to y, size) to ring
                }
                val line = MaterialTheme.colorScheme.outlineVariant
                val density = LocalDensity.current
                Canvas(Modifier.fillMaxSize()) {
                    val c = with(density) { Offset(cx.toPx(), cy.toPx()) }
                    for ((p, ring) in placed) {
                        val (x, y) = p.second
                        drawLine(line, c, with(density) { Offset(x.toPx(), y.toPx()) }, strokeWidth = if (ring == 0) 3f else 2f)
                    }
                }
                MapCircle(
                    centreTitle, centreCount, centrePicture, CENTRE, MaterialTheme.colorScheme.primary, book = false, centre = true,
                    modifier = Modifier.offset(x = cx - MAP_NODE_WIDTH / 2 - (CENTRE - 56.dp) / 2, y = cy - CENTRE / 2),
                    onTap = {}, onHold = { open(centre) },
                    painter = if (centre == MapCentre.Library) androidx.compose.ui.res.painterResource(R.drawable.granger_mark) else null,
                )
                placed.forEachIndexed { i, (p, ring) ->
                    val (spot, xy, size) = p
                    MapCircle(
                        spot.title, spot.count, spot.picture, size,
                        if (ring == 0) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.outlineVariant,
                        book = false, centre = false,
                        modifier = Modifier.offset(x = xy.first - MAP_NODE_WIDTH / 2, y = xy.second - size / 2).testTag(LibraryMapTags.SPOT),
                        onTap = { tap(i) }, onHold = { hold(i) },
                    )
                }
            }
            Surface(color = MaterialTheme.colorScheme.surfaceContainerLowest, shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (centre == MapCentre.Library && arrange == MapArrange.SUBJECTS && rest.isNotEmpty()) {
                        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(stringResource(R.string.explore_map_more), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            for (d in rest) AssistChip(onClick = { centres += MapCentre.OfSubject(d.subject) }, label = { Text(stringResource(R.string.explore_subject_era_chip, stringResource(d.subject.label), count(d.count))) })
                        }
                    }
                    if (centre != MapCentre.Library) {
                        Button(onClick = { open(centre) }, modifier = Modifier.heightIn(min = 48.dp).testTag(LibraryMapTags.OPEN)) {
                            Text(stringResource(if (centre is MapCentre.OfEra) R.string.explore_map_open_era else R.string.explore_map_open_subject))
                        }
                    }
                    Text(
                        stringResource(if (centre == MapCentre.Library) R.string.explore_map_hint else R.string.explore_map_hint_inside),
                        style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

private val CENTRE = 88.dp
