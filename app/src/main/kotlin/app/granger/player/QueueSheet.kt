package app.granger.player

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import app.granger.R
import app.granger.core.library.Track

/** A queue row; [key] stays with the song while it is dragged, even when the same song is queued twice. */
private data class QueueItem(val key: Int, val track: Track)

/**
 * The music queue in play order. Long-press a song and drag to move it ([onMove] with play-order positions, once, on
 * release); the cross removes it ([onRemove]), except the last song; a tap plays it ([onPlay]). [current] is the
 * playing song's position. Not [editable] (the saved queue of the inactive tab): just the list.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QueueSheet(
    queue: List<Track>,
    current: Int,
    onMove: (from: Int, to: Int) -> Unit,
    onRemove: (Int) -> Unit,
    onDismiss: () -> Unit,
    editable: Boolean = true,
    onPlay: (Int) -> Unit = {},
) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.navigationBarsPadding()) {
            Text("Queue", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
            if (editable) ReorderableQueue(queue, current, onMove, onRemove, onPlay) else ReadOnlyQueue(queue, current)
        }
    }
}

@Composable
private fun ReorderableQueue(
    queue: List<Track>, current: Int, onMove: (Int, Int) -> Unit, onRemove: (Int) -> Unit, onPlay: (Int) -> Unit,
) {
    var items by remember { mutableStateOf(queue.mapIndexed { i, t -> QueueItem(i, t) }) }
    var dragKey by remember { mutableStateOf<Int?>(null) }
    var dragFrom by remember { mutableStateOf(-1) }
    var dragOffset by remember { mutableFloatStateOf(0f) }
    // A new queue from the player replaces the local order, except mid-drag.
    LaunchedEffect(queue) { if (dragKey == null) items = queue.mapIndexed { i, t -> QueueItem(i, t) } }
    val list = rememberLazyListState(initialFirstVisibleItemIndex = current.coerceIn(0, (queue.size - 1).coerceAtLeast(0)))
    val move by rememberUpdatedState(onMove)

    fun swap(from: Int, to: Int) {
        items = items.toMutableList().apply { add(to, removeAt(from)) }
    }

    LazyColumn(
        state = list,
        modifier = Modifier.fillMaxWidth().pointerInput(Unit) {
            detectDragGesturesAfterLongPress(
                onDragStart = { at ->
                    val hit = list.itemAt(at.y.toInt()) ?: return@detectDragGesturesAfterLongPress
                    dragKey = hit.key as Int
                    dragFrom = hit.index
                    dragOffset = 0f
                },
                onDrag = { change, amount ->
                    val key = dragKey ?: return@detectDragGesturesAfterLongPress
                    change.consume()
                    dragOffset += amount.y
                    val info = list.layoutInfo.visibleItemsInfo
                    val dragged = info.firstOrNull { it.key == key } ?: return@detectDragGesturesAfterLongPress
                    val middle = dragged.offset + dragOffset + dragged.size / 2
                    val target = info.firstOrNull { it.key != key && middle.toInt() in it.offset until it.offset + it.size }
                    if (target != null) {
                        // The dragged row takes the target's place; keep it under the finger.
                        dragOffset += (dragged.offset - target.offset)
                        swap(dragged.index, target.index)
                    }
                },
                onDragEnd = {
                    val key = dragKey
                    val to = items.indexOfFirst { it.key == key }
                    if (key != null && dragFrom >= 0 && to >= 0 && to != dragFrom) move(dragFrom, to)
                    dragKey = null
                    dragOffset = 0f
                },
                onDragCancel = {
                    items = queue.mapIndexed { i, t -> QueueItem(i, t) }
                    dragKey = null
                    dragOffset = 0f
                },
            )
        },
    ) {
        itemsIndexed(items, key = { _, item -> item.key }) { index, item ->
            val dragging = item.key == dragKey
            val playing = dragKey == null && index == current
            QueueRow(
                item.track,
                playing = playing,
                canRemove = items.size > 1,
                canMoveUp = index > 0,
                canMoveDown = index < items.size - 1,
                onMoveBy = { by -> move(index, index + by) },
                onRemove = { onRemove(index) },
                onPlay = { if (dragKey == null) onPlay(index) },
                modifier = Modifier
                    .then(if (dragging) Modifier.zIndex(1f).graphicsLayer { translationY = dragOffset } else Modifier.animateItem())
                    .background(if (dragging) MaterialTheme.colorScheme.surfaceContainerHigh else MaterialTheme.colorScheme.surfaceContainerLow),
            )
        }
    }
}

@Composable
private fun ReadOnlyQueue(queue: List<Track>, current: Int) {
    val list = rememberLazyListState(initialFirstVisibleItemIndex = current.coerceIn(0, (queue.size - 1).coerceAtLeast(0)))
    LazyColumn(state = list, modifier = Modifier.fillMaxWidth()) {
        itemsIndexed(queue) { index, track ->
            QueueRow(
                track, playing = index == current, editable = false, canRemove = false, canMoveUp = false, canMoveDown = false,
                onMoveBy = {}, onRemove = {}, modifier = Modifier.background(MaterialTheme.colorScheme.surfaceContainerLow),
            )
        }
    }
}

private fun LazyListState.itemAt(y: Int) = layoutInfo.visibleItemsInfo.firstOrNull { y in it.offset until it.offset + it.size }

@Composable
private fun QueueRow(
    track: Track,
    playing: Boolean,
    editable: Boolean = true,
    canRemove: Boolean,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onMoveBy: (Int) -> Unit,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
    onPlay: (() -> Unit)? = null,
) {
    Row(
        modifier
            .fillMaxWidth()
            .then(if (onPlay != null) Modifier.clickable(onClickLabel = "Play", onClick = onPlay) else Modifier)
            .testTag(PlayerTags.QUEUE_ROW)
            .semantics {
                // Dragging has no screen-reader equivalent; these do the same one step at a time.
                customActions = buildList {
                    if (canMoveUp) add(CustomAccessibilityAction("Move up") { onMoveBy(-1); true })
                    if (canMoveDown) add(CustomAccessibilityAction("Move down") { onMoveBy(1); true })
                }
            }
            .padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (editable) {
            Icon(painterResource(R.drawable.ic_drag_handle), contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(12.dp))
        }
        PlayerCover(track.coverPath, book = false, size = 40.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                track.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge,
                color = if (playing) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            )
            Text(
                track.artists.ifBlank { track.albumArtists }, maxLines = 1, overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (editable) {
            IconButton(onClick = onRemove, enabled = canRemove) {
                Icon(Icons.Filled.Close, contentDescription = "Remove ${track.title} from queue")
            }
        }
    }
}
