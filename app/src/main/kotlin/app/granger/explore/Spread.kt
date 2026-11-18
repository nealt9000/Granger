package app.granger.explore

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import app.granger.R
import app.granger.theme.FieldKit
import app.granger.theme.GrangerTheme
import app.granger.theme.Spectral

/**
 * The type of an Explore spread (Plan EX round 2, G0): Spectral for what is read (titles, standfirsts, captions), Roboto
 * for what is used (eyebrows, links, counts).
 */
object SpreadType {
    val display = TextStyle(fontFamily = Spectral, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.005).em)
    val serif = TextStyle(fontFamily = Spectral, fontWeight = FontWeight.Normal)
    val italic = TextStyle(fontFamily = Spectral, fontWeight = FontWeight.Normal, fontStyle = FontStyle.Italic)
    val eyebrow = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.Medium, fontSize = 11.sp, lineHeight = 14.sp, letterSpacing = 0.11.em)
    val ui = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.Normal, fontSize = 13.sp, lineHeight = 18.sp)
    val uiMedium = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.Medium, fontSize = 13.sp, lineHeight = 17.sp)
}

/** A spread's colours the scheme has no one slot for in both themes. */
object SpreadColors {
    /** Eyebrows, links, plate numbers in a caption: brown ink by day, lantern at night. */
    val accent: Color @Composable get() = if (GrangerTheme.extras.night) FieldKit.Lantern else MaterialTheme.colorScheme.tertiary

    /** Counts and bars: enamel by day, phosphor at night. */
    val enamel: Color @Composable get() = if (GrangerTheme.extras.night) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.primary

    /** The doors bar's ground, the rail's own colour. */
    val band: Color @Composable get() = if (GrangerTheme.extras.night) MaterialTheme.colorScheme.surfaceContainerLowest else MaterialTheme.colorScheme.surfaceContainer
}

object SpreadTags {
    const val TRAIL = "spread_trail"
    const val TRAIL_WHOLE = "spread_trail_whole"
    fun trailStep(i: Int) = "spread_trail_$i"
    const val DOOR_BEFORE = "spread_door_before"
    const val DOOR_AFTER = "spread_door_after"
    const val SEEN = "spread_seen"
    fun way(i: Int) = "spread_way_$i"
}

/** A small capital line over a heading or a group. Set in capitals for the eye; TalkBack gets the words as written. */
@Composable
fun Eyebrow(text: String, modifier: Modifier = Modifier, color: Color = SpreadColors.accent) {
    Text(text.uppercase(), style = SpreadType.eyebrow, color = color, modifier = modifier.semantics { contentDescription = text })
}

/** A spread's or a section's head: an eyebrow, a title that states something, and (optional) a standfirst under it. */
@Composable
fun SpreadHead(eyebrow: String?, title: String, modifier: Modifier = Modifier, standfirst: String? = null, large: Boolean = false) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        eyebrow?.let { Eyebrow(it) }
        Text(
            title, style = SpreadType.display.copy(fontSize = if (large) 36.sp else 26.sp, lineHeight = if (large) 38.sp else 29.sp),
            color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.semantics { heading() },
        )
        standfirst?.let { Text(it, style = SpreadType.serif.copy(fontSize = if (large) 17.sp else 15.sp, lineHeight = if (large) 25.sp else 22.sp), color = if (large) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

/** The captions of a numbered group of plates, in the plates' order: each plate's number, then what it shows. */
@Composable
fun Captions(captions: List<String>, modifier: Modifier = Modifier, firstNumber: Int = 1) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(3.dp)) {
        for ((i, caption) in captions.withIndex()) {
            Row(Modifier.semantics(mergeDescendants = true) {}, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text((firstNumber + i).toString(), style = SpreadType.uiMedium.copy(fontSize = 11.sp), color = SpreadColors.accent, modifier = Modifier.width(12.dp).padding(top = 2.dp))
                Text(caption, style = SpreadType.italic.copy(fontSize = 13.5.sp, lineHeight = 18.sp), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** One step of the trail behind the reader: its name and the way back to it. */
data class TrailStep(val label: String, val onOpen: () -> Unit)

/**
 * Which of a trail's [count] places are drawn, in order (pure, for tests); [TRAIL_GAP] stands for the ones left out. Up to
 * three places are all drawn; past three, the first, a gap, and the last two.
 */
fun trailShown(count: Int): List<Int> = if (count <= 3) (0 until count).toList() else listOf(0, TRAIL_GAP, count - 2, count - 1)

const val TRAIL_GAP = -1

/**
 * The trail (Explore › Technology › Cars, planes & ships): where the reader is and how they came. Every step behind is
 * a way back; the last, [here], is where they stand. Past three places the middle folds into "…", which opens the whole
 * trail as a list.
 */
@Composable
fun Trail(steps: List<TrailStep>, here: String, modifier: Modifier = Modifier) {
    var whole by remember { mutableStateOf(false) }
    val accent = SpreadColors.accent
    val link = SpreadType.ui.copy(fontSize = 12.5.sp)
    Row(modifier.fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = 16.dp).testTag(SpreadTags.TRAIL), verticalAlignment = Alignment.CenterVertically) {
        @Composable fun mark() = Text("›", style = link, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 6.dp).clearAndSetSemantics {})
        for (i in trailShown(steps.size + 1)) {
            when {
                i == TRAIL_GAP -> {
                    val label = stringResource(R.string.explore_trail_whole)
                    Box {
                        Box(
                            Modifier.heightIn(min = 48.dp).widthIn(min = 32.dp).clickable(role = Role.Button) { whole = true }
                                .semantics { contentDescription = label }.testTag(SpreadTags.TRAIL_WHOLE),
                            contentAlignment = Alignment.Center,
                        ) { Text("…", style = link, color = accent, modifier = Modifier.clearAndSetSemantics {}) }
                        DropdownMenu(whole, onDismissRequest = { whole = false }) {
                            for (step in steps) DropdownMenuItem(text = { Text(step.label) }, onClick = { whole = false; step.onOpen() })
                        }
                    }
                    mark()
                }
                i < steps.size -> {
                    Box(Modifier.heightIn(min = 48.dp).widthIn(max = 132.dp).clickable(role = Role.Button, onClick = steps[i].onOpen).testTag(SpreadTags.trailStep(i)), contentAlignment = Alignment.CenterStart) {
                        Text(steps[i].label, style = link, color = accent, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
                    }
                    mark()
                }
                else -> {
                    val label = stringResource(R.string.explore_trail_here, here)
                    Text(
                        here, style = link.copy(fontWeight = FontWeight.Medium), color = MaterialTheme.colorScheme.onSurface, maxLines = 1, softWrap = false,
                        overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false).semantics { contentDescription = label },
                    )
                }
            }
        }
    }
}

/** What the doors of a doors bar lead between. */
enum class DoorKind(val before: Int, val after: Int, val place: Int) {
    ROOM(R.string.explore_door_room_before, R.string.explore_door_room_after, R.string.explore_place_room),
    SUBJECT(R.string.explore_door_subject_before, R.string.explore_door_subject_after, R.string.explore_place_subject),
    TOPIC(R.string.explore_door_topic_before, R.string.explore_door_topic_after, R.string.explore_place_topic),
}

/** A door: the neighbour's name and the way through. */
data class Door(val name: String, val onOpen: () -> Unit)

/**
 * The doors bar at the foot of a spread: the neighbour before on the left, the one after on the right, and between them
 * where this one stands among its [total] ([position] from 1). A side with no door is left empty. TalkBack reads a door
 * as "The room before, The internet".
 */
@Composable
fun DoorsBar(kind: DoorKind, before: Door?, after: Door?, position: Int, total: Int, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().background(SpreadColors.band)) {
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Row(Modifier.fillMaxWidth().height(64.dp)) {
            DoorSide(stringResource(kind.before), before, end = false, SpreadTags.DOOR_BEFORE)
            VerticalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            val place = stringResource(kind.place, count(position), count(total))
            Box(Modifier.width(80.dp).fillMaxHeight().padding(horizontal = 4.dp), contentAlignment = Alignment.Center) {
                Text(place.uppercase(), style = SpreadType.eyebrow.copy(fontSize = 10.sp, lineHeight = 13.sp), color = SpreadColors.accent, textAlign = TextAlign.Center,
                    modifier = Modifier.semantics { contentDescription = place })
            }
            VerticalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            DoorSide(stringResource(kind.after), after, end = true, SpreadTags.DOOR_AFTER)
        }
    }
}

@Composable
private fun RowScope.DoorSide(what: String, door: Door?, end: Boolean, tag: String) {
    if (door == null) {
        Box(Modifier.weight(1f))
        return
    }
    val label = stringResource(R.string.explore_door_reads, what, door.name)
    Row(
        Modifier.weight(1f).fillMaxHeight().clickable(role = Role.Button, onClick = door.onOpen).semantics { contentDescription = label }.testTag(tag).padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        val arrow = @Composable {
            Icon(if (end) Icons.AutoMirrored.Filled.KeyboardArrowRight else Icons.AutoMirrored.Filled.KeyboardArrowLeft, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (!end) arrow()
        Column(Modifier.weight(1f).clearAndSetSemantics {}, horizontalAlignment = if (end) Alignment.End else Alignment.Start) {
            // Shrinks before it cuts: Spanish runs longer (LA SALA SIGUIENTE).
            Text(
                what.uppercase(), style = SpreadType.eyebrow.copy(fontSize = 9.5.sp, lineHeight = 12.sp, letterSpacing = 0.06.em), color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis,
                autoSize = androidx.compose.foundation.text.TextAutoSize.StepBased(minFontSize = 7.sp, maxFontSize = 9.5.sp, stepSize = 0.5.sp),
            )
            Text(
                door.name, style = SpreadType.display.copy(fontSize = 13.5.sp, lineHeight = 15.sp), color = MaterialTheme.colorScheme.onSurface, maxLines = 2,
                overflow = TextOverflow.Ellipsis, textAlign = if (end) TextAlign.End else TextAlign.Start,
            )
        }
        if (end) arrow()
    }
}

/** One way on from a spread: what kind of step it is ([kicker]) and where it goes. */
data class WayOn(val kicker: String, val name: String, val onOpen: () -> Unit)

/** The ways on at the foot of a spread (three, as a rule): each a ruled line, its kicker over its name. */
@Composable
fun WaysOn(title: String, ways: List<WayOn>, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Eyebrow(title, Modifier.padding(bottom = 8.dp).semantics { heading() }, color = MaterialTheme.colorScheme.onSurfaceVariant)
        for ((i, way) in ways.withIndex()) {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Row(
                Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(role = Role.Button, onClick = way.onOpen).semantics(mergeDescendants = true) {}.testTag(SpreadTags.way(i)).padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                    Eyebrow(way.kicker, color = SpreadColors.accent)
                    Text(way.name, style = SpreadType.display.copy(fontSize = 18.sp, lineHeight = 22.sp), color = MaterialTheme.colorScheme.onSurface)
                }
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/**
 * The rooms-seen line (the quiet game layer: rooms only, never articles): [label], how many of how many, and a thin bar
 * filled that far. One thing to TalkBack: "Rooms you have seen, 14 of 108". With [onOpen] it is a button.
 */
@Composable
fun RoomsSeen(label: String, seen: Int, of: Int, modifier: Modifier = Modifier, onOpen: (() -> Unit)? = null) {
    val tally = stringResource(R.string.explore_seen_of, count(seen), count(of))
    val reads = stringResource(R.string.explore_door_reads, label, tally)
    val part = if (of <= 0) 0f else (seen.toFloat() / of).coerceIn(0f, 1f)
    Box(
        modifier.fillMaxWidth().then(if (onOpen != null) Modifier.clickable(role = Role.Button, onClick = onOpen) else Modifier)
            .semantics { contentDescription = reads }.testTag(SpreadTags.SEEN),
    ) {
        Column(
            Modifier.fillMaxWidth().clearAndSetSemantics {}.heightIn(min = 48.dp).padding(horizontal = 16.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterVertically),
        ) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(label, style = SpreadType.ui, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                Text(tally, style = SpreadType.uiMedium, color = SpreadColors.accent)
            }
            Box(Modifier.fillMaxWidth().height(3.dp).background(MaterialTheme.colorScheme.outlineVariant)) {
                if (part > 0f) Box(Modifier.fillMaxWidth(part).height(3.dp).background(SpreadColors.accent))
            }
        }
    }
}
