package app.granger.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/*
 * Explore's "Your sources" look (Plan EX mockups, canvas HG4yrYyYPFssmRvsYscdvq, ExploreSources), shared so Get more →
 * Books (Plan KB2 Task 4.1) and Explore's Sources draw the same: a small teal label above a rounded block of rows with
 * hairline dividers, each row a title, one meta line and a chevron; Spectral section headers with a text action; 36 dp
 * outlined chips, filled teal when chosen.
 */

/** The teal of labels and chosen chips: Enamel (#2E4A45) in Field, Phosphor at Night, where primary is Lantern. */
val groupTeal: Color @Composable @ReadOnlyComposable get() = GrangerTheme.extras.status

/** The block's surface (#FAF7F1 in Field) and its hairlines: a step lighter than the page, in both themes. */
private val blockColor: Color
    @Composable @ReadOnlyComposable get() =
        if (GrangerTheme.extras.night) MaterialTheme.colorScheme.surfaceContainerLow else MaterialTheme.colorScheme.surfaceContainerLowest

private val hairlineColor: Color
    @Composable @ReadOnlyComposable get() =
        if (GrangerTheme.extras.night) MaterialTheme.colorScheme.background else MaterialTheme.colorScheme.surfaceContainerLow

/** A group's label: Roboto 500 13 sp in teal, read by TalkBack as a heading. */
@Composable
fun GroupLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge.copy(fontFamily = FontFamily.Default, fontWeight = FontWeight.Medium, fontSize = 13.sp),
        color = groupTeal,
        modifier = modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 4.dp).semantics { heading() },
    )
}

/** A section header: Spectral 600 20 sp, a heading, with an optional right-aligned text action ("All 9 packs"). */
@Composable
fun SectionHeader(text: String, modifier: Modifier = Modifier, action: String? = null, onAction: () -> Unit = {}) {
    Row(
        modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 20.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text,
            style = MaterialTheme.typography.titleLarge.copy(fontSize = 20.sp),
            modifier = Modifier.weight(1f).semantics { heading() },
        )
        if (action != null) TextButton(onClick = onAction) { Text(action, color = groupTeal) }
    }
}

/** A rounded block of [rows] (16 dp corners), a hairline between each; 12 dp in from the page's edges. */
@Composable
fun GroupedRows(rows: List<@Composable () -> Unit>, modifier: Modifier = Modifier) {
    if (rows.isEmpty()) return
    Column(
        modifier.fillMaxWidth().padding(horizontal = 12.dp).clip(RoundedCornerShape(16.dp)).background(blockColor),
    ) {
        rows.forEachIndexed { i, row ->
            if (i > 0) HorizontalDivider(thickness = 1.dp, color = hairlineColor)
            row()
        }
    }
}

/**
 * One row of a block: [title], one [meta] line, and [trailing] (a chevron by default: the row opens something). [below]
 * goes under the meta line (a download's progress). With [onClick] null the row isn't clickable (its trailing buttons are).
 */
@Composable
fun GroupedRow(
    title: String,
    meta: String?,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    below: (@Composable () -> Unit)? = null,
    trailing: @Composable () -> Unit = { Chevron() },
) {
    Row(
        modifier.fillMaxWidth().heightIn(min = 56.dp)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium), maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (!meta.isNullOrBlank()) {
                Text(meta, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            below?.invoke()
        }
        trailing()
    }
}

/** The row's chevron: it opens something; decorative to TalkBack (the row says what it is). */
@Composable
fun Chevron() {
    Icon(
        Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null,
        tint = MaterialTheme.colorScheme.outline, modifier = Modifier.padding(end = 4.dp).size(20.dp),
    )
}

/** A 36 dp outlined chip, filled teal when [selected]. */
@Composable
fun OutlineChip(label: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label) },
        modifier = modifier.heightIn(min = 36.dp),
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = groupTeal,
            selectedLabelColor = MaterialTheme.colorScheme.surface,
        ),
    )
}
