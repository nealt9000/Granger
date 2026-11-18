package app.granger.explore

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.granger.shell.rememberRailCover

/**
 * One circle of Explore's library map (Plan EX Task 5): a picture in a coloured ring, or the
 * title's first letter, with the title under it and, if given, a [subtitle] (a count). A [book] is a cover, not a circle.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun MapCircle(
    title: String,
    subtitle: String?,
    pictureFile: String?,
    size: Dp,
    ring: Color,
    book: Boolean,
    centre: Boolean,
    modifier: Modifier,
    onTap: () -> Unit,
    onHold: () -> Unit,
    /** A drawn mark instead of a picture (the library map's centre). */
    painter: androidx.compose.ui.graphics.painter.Painter? = null,
) {
    val picture = rememberRailCover(pictureFile, size)
    val width = if (centre) MAP_NODE_WIDTH + 32.dp else MAP_NODE_WIDTH
    Column(
        modifier.width(width).clip(RoundedCornerShape(12.dp)).combinedClickable(onClick = onTap, onLongClick = onHold)
            .semantics(mergeDescendants = true) { contentDescription = listOfNotNull(title, subtitle).joinToString(", ") },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        val shape = if (book) RoundedCornerShape(4.dp) else CircleShape
        Box(
            Modifier.size(width = if (book) size * 0.72f else size, height = size).clip(shape).border(3.dp, ring, shape)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh),
            contentAlignment = Alignment.Center,
        ) {
            if (painter != null) Image(painter, null, modifier = Modifier.fillMaxSize().padding(size * 0.18f))
            else if (picture != null) Image(picture, null, contentScale = ContentScale.Crop, alignment = Alignment.TopCenter, modifier = Modifier.fillMaxSize())
            else Text(title.take(1), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(title, style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis)
        if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
    }
}

/** A map circle's label width. */
internal val MAP_NODE_WIDTH = 84.dp
