package app.granger.explore

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.granger.theme.FieldKit

/**
 * One plate of a mosaic (Plan EX round 2, G0): a topic, a room or a subject by its picture, or by its word when it has
 * none. [key] is unique within a mosaic.
 */
data class Plate(
    val key: String,
    val title: String,
    /** The picture's file, or null: the plate is then set as a word. */
    val picture: String? = null,
    /** The picture's width over its height; 0 when not known. */
    val ratio: Float = 0f,
    /** A flag, seal, logo, map or diagram: shown whole on a paper ground, never cropped (Plan EX P0). */
    val whole: Boolean = false,
    /** A line under the title (dates). */
    val sub: String? = null,
    /** A word plate's opening line. */
    val blurb: String? = null,
)

/** How a row of the mosaic is cut. In [LEAD], [LEAD_MIRROR] and [OPENING] the first plate is the large one. */
enum class PlateRowKind(val ratio: Float) {
    /** A large plate with two stacked beside it on the right. */
    LEAD(334f / 134f),
    /** Two stacked on the left, the large plate on the right. */
    LEAD_MIRROR(334f / 162f),
    /** Three squares. */
    THREE(334f / 110f),
    /** Three upright plates, for portraits. */
    THREE_TALL(334f / 132f),
    /** Two side by side. */
    PAIR(334f / 120f),
    /** One across the whole width. */
    SINGLE(334f / 150f),
    /** A spread's opening group: one large plate and two beside it, numbered for the captions under them. */
    OPENING(334f / 170f),
}

data class PlateRow(val kind: PlateRowKind, val plates: List<Plate>)

private fun Plate.upright() = picture != null && ratio > 0f && ratio <= UPRIGHT
private fun Plate.canLead() = picture != null && !upright()

/** Narrower than this (width over height), a picture is upright: a portrait. */
private const val UPRIGHT = 0.87f

/**
 * The mosaic's rows for [plates] in their order (pure, for tests): the row patterns come by rule from how many there are
 * and what shape their pictures have. Threes alternate between a led row (one large plate and two small, the large side
 * swapping each time) and three across. A led row needs a picture that isn't upright to lead; the first such of the
 * three leads, a photograph before an emblem. Two or more portraits in a three stand upright, three across. A mosaic that
 * opens on two plates with no picture sets them side by side, as words; two left over go side by side too, and one left
 * over runs the whole width. With more still to load
 * ([complete] false) the leftover waits, so no row is re-cut when the rest arrives.
 */
fun plateRows(plates: List<Plate>, complete: Boolean = true): List<PlateRow> {
    val out = ArrayList<PlateRow>()
    var i = 0
    var slot = 0
    var leads = 0
    while (i < plates.size) {
        val left = plates.size - i
        if (!complete && left < 3) break
        if (left < 3 || (left == 4 && complete)) {
            if (left == 1) out += PlateRow(PlateRowKind.SINGLE, plates.subList(i, i + 1)) else out += PlateRow(PlateRowKind.PAIR, plates.subList(i, i + 2))
            i += if (left == 1) 1 else 2
            continue
        }
        val three = plates.subList(i, i + 3)
        if (i == 0 && three[0].picture == null && three[1].picture == null) {
            out += PlateRow(PlateRowKind.PAIR, three.subList(0, 2))
            i += 2
            slot++
            continue
        }
        val lead = if (slot % 2 == 0 && three.count { it.upright() } < 2) three.firstOrNull { it.canLead() && !it.whole } ?: three.firstOrNull { it.canLead() } else null
        out += when {
            lead != null -> PlateRow(if (leads++ % 2 == 0) PlateRowKind.LEAD else PlateRowKind.LEAD_MIRROR, listOf(lead) + three.filter { it !== lead })
            three.count { it.upright() } >= 2 -> PlateRow(PlateRowKind.THREE_TALL, three)
            else -> PlateRow(PlateRowKind.THREE, three)
        }
        slot++
        i += 3
    }
    return out
}

/** A spread's opening group from its first pictures: three as [PlateRowKind.OPENING], two side by side, one alone. */
fun openingRow(plates: List<Plate>): PlateRow? = when {
    plates.isEmpty() -> null
    plates.size >= 3 -> PlateRow(PlateRowKind.OPENING, plates.take(3))
    plates.size == 2 -> PlateRow(PlateRowKind.PAIR, plates)
    else -> PlateRow(PlateRowKind.SINGLE, plates)
}

object PlateTags {
    fun plate(key: String) = "plate_$key"
    const val NUMBER = "plate_number"
}

/** The seam between plates: the page shows through. */
private val SEAM = 2.dp

/**
 * One row of the mosaic: square-edged plates that touch but for a [SEAM]. With [firstNumber], the plates are numbered from
 * it in their order (the captions under the group carry their words, so no title is drawn); the numbers and the seams
 * are nothing to TalkBack, for which each plate is one button with its title.
 */
@Composable
fun PlateRowView(row: PlateRow, onOpen: (Plate) -> Unit, modifier: Modifier = Modifier, firstNumber: Int? = null) {
    val p = row.plates
    fun n(i: Int) = firstNumber?.plus(i)
    Row(modifier.fillMaxWidth().aspectRatio(row.kind.ratio), horizontalArrangement = Arrangement.spacedBy(SEAM)) {
        when (row.kind) {
            PlateRowKind.LEAD, PlateRowKind.OPENING -> {
                val wide = if (row.kind == PlateRowKind.OPENING) 222f else 200f
                Cell(p[0], wide, 17.sp, n(0), onOpen)
                Stack(p, 334f - wide, ::n, onOpen)
            }
            PlateRowKind.LEAD_MIRROR -> {
                Stack(p, 132f, ::n, onOpen)
                Cell(p[0], 200f, 17.sp, n(0), onOpen)
            }
            PlateRowKind.THREE, PlateRowKind.THREE_TALL -> for ((i, plate) in p.withIndex()) Cell(plate, 1f, 14.sp, n(i), onOpen)
            PlateRowKind.PAIR -> for ((i, plate) in p.withIndex()) Cell(plate, 1f, 16.sp, n(i), onOpen, wordSize = 24.sp)
            PlateRowKind.SINGLE -> Cell(p[0], 1f, 17.sp, n(0), onOpen, wordSize = 24.sp)
        }
    }
}

@Composable
private fun RowScope.Cell(plate: Plate, weight: Float, size: TextUnit, number: Int?, onOpen: (Plate) -> Unit, wordSize: TextUnit = 17.sp) {
    PlateView(plate, size, number, Modifier.weight(weight).fillMaxHeight(), wordSize = wordSize) { onOpen(plate) }
}

/** The two small plates of a led row, one over the other. */
@Composable
private fun RowScope.Stack(p: List<Plate>, weight: Float, n: (Int) -> Int?, onOpen: (Plate) -> Unit) {
    Column(Modifier.weight(weight).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(SEAM)) {
        for (i in 1..2) PlateView(p[i], 13.sp, n(i), Modifier.weight(1f).fillMaxWidth(), short = true) { onOpen(p[i]) }
    }
}

/** The mosaic for [rows], one under the other with a seam between: for a group short enough not to need a lazy list. */
@Composable
fun PlateMosaic(rows: List<PlateRow>, onOpen: (Plate) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(SEAM)) { for (row in rows) PlateRowView(row, onOpen) }
}

/**
 * One plate, filling [modifier]'s box. A picture fills it, cropped round its subject, its title over a dark fade at the
 * foot; an emblem ([Plate.whole]) sits whole on white paper with its title in ink beneath; with no picture the title is
 * the plate, its opening line under it when there's room ([short] plates have none). [number] replaces the title with a
 * small figure in the corner.
 */
@Composable
fun PlateView(
    plate: Plate, size: TextUnit, number: Int?, modifier: Modifier = Modifier, short: Boolean = false, wordSize: TextUnit = 17.sp, onOpen: () -> Unit,
) {
    val label = listOfNotNull(plate.title, plate.sub).joinToString(", ")
    Box(modifier.testTag(PlateTags.plate(plate.key)).clickable(role = Role.Button, onClick = onOpen).semantics { contentDescription = label }) {
        // Everything drawn is the button's own: TalkBack reads the label above, once.
        Box(Modifier.fillMaxSize().clearAndSetSemantics {}) {
            when {
                plate.picture == null -> WordPlate(plate, if (short) 15.sp else wordSize, blurb = !short && number == null)
                plate.whole -> WholePlate(plate, size, titled = number == null)
                else -> {
                    FramedPicture(plate.picture, 240.dp, Modifier.fillMaxSize())
                    if (number == null) Foot(plate, size)
                }
            }
            if (number != null) {
                Box(Modifier.size(18.dp).background(MaterialTheme.colorScheme.background).testTag(PlateTags.NUMBER), contentAlignment = Alignment.Center) {
                    Text(number.toString(), style = SpreadType.uiMedium.copy(fontSize = 11.sp), color = MaterialTheme.colorScheme.onBackground)
                }
            }
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.BoxScope.Foot(plate: Plate, size: TextUnit) {
    Column(
        Modifier.fillMaxWidth().align(Alignment.BottomCenter)
            .background(Brush.verticalGradient(listOf(Color.Transparent, FOOT)))
            .padding(start = 8.dp, end = 8.dp, top = 26.dp, bottom = 7.dp),
    ) {
        Text(plate.title, style = SpreadType.display.copy(fontSize = size, lineHeight = size * 1.15f, hyphens = androidx.compose.ui.text.style.Hyphens.Auto), color = Color.White, maxLines = 2, overflow = TextOverflow.Ellipsis)
        plate.sub?.let { Text(it, style = SpreadType.ui.copy(fontSize = 11.sp), color = Color.White.copy(alpha = 0.92f), maxLines = 1, overflow = TextOverflow.Ellipsis) }
    }
}

/** The fade a title sits on: near black, warm. */
private val FOOT = Color(0xD10C0A08)

@Composable
private fun WholePlate(plate: Plate, size: TextUnit, titled: Boolean) {
    // White in Field and in Night: an emblem keeps its own ground, as a photograph keeps its sky.
    Column(Modifier.fillMaxSize().background(Color.White)) {
        WholePicture(plate.picture, 240.dp, Modifier.weight(1f).fillMaxWidth().padding(start = 8.dp, end = 8.dp, top = 8.dp, bottom = if (titled) 2.dp else 8.dp))
        if (titled) {
            Text(
                plate.title, style = SpreadType.display.copy(fontSize = minOf(size.value, 13f).sp, lineHeight = 15.sp), color = FieldKit.Ink, maxLines = 2,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 8.dp, end = 8.dp, bottom = 7.dp),
            )
        }
    }
}

@Composable
private fun WordPlate(plate: Plate, size: TextUnit, blurb: Boolean) {
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainer).padding(10.dp), verticalArrangement = Arrangement.SpaceBetween) {
        // One word never breaks inside itself: it shrinks to the plate's width instead (Interjection, three across).
        val oneWord = plate.title.none { it.isWhitespace() || it == '-' }
        Text(
            plate.title, style = SpreadType.display.copy(fontSize = size, lineHeight = size * 1.08f), color = MaterialTheme.colorScheme.onSurface,
            maxLines = if (oneWord) 1 else if (blurb && plate.blurb != null) 2 else 3, softWrap = !oneWord, overflow = TextOverflow.Ellipsis,
            autoSize = if (oneWord) androidx.compose.foundation.text.TextAutoSize.StepBased(minFontSize = 10.sp, maxFontSize = size, stepSize = 0.5.sp) else null,
        )
        val under = if (blurb) plate.blurb ?: plate.sub else plate.sub
        under?.let {
            Text(
                it, style = SpreadType.serif.copy(fontSize = 11.5.sp, lineHeight = 15.sp), color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = if (blurb && plate.blurb != null) 3 else 1, overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
