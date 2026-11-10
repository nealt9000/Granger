package app.granger.home

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import app.granger.R

private val RowHeight = 60.dp
private val RowGap = 8.dp

/**
 * Arrange Home (Plan HY, Neal 2026-11-10): every section as a row, dragged by its handle to a new place and switched off
 * to hide it. TalkBack moves a row with Move up / Move down. Nothing is saved until Done; Reset puts back [defaults].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ArrangeHome(
    parts: List<HomePart>,
    hidden: Set<String>,
    label: @Composable (HomePart) -> String,
    defaults: List<HomePart>,
    onDone: (HomeLayout) -> Unit,
    onCancel: () -> Unit,
) {
    var order by remember { mutableStateOf(parts) }
    var off by remember { mutableStateOf(hidden) }
    var dragging by remember { mutableStateOf<String?>(null) }
    var offset by remember { mutableFloatStateOf(0f) }
    val step = with(LocalDensity.current) { (RowHeight + RowGap).toPx() }
    BackHandler(onBack = onCancel)

    fun move(token: String, by: Int) {
        val at = order.indexOfFirst { it.token == token }
        val to = at + by
        if (at < 0 || to !in order.indices) return
        order = order.toMutableList().also { it.add(to, it.removeAt(at)) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.home_arrange)) },
                navigationIcon = { IconButton(onClick = onCancel) { Icon(Icons.Filled.Close, stringResource(R.string.common_cancel)) } },
                actions = {
                    TextButton(onClick = { order = defaults; off = emptySet() }, modifier = Modifier.testTag("arrange-reset")) {
                        Text(stringResource(R.string.home_arrange_reset))
                    }
                    Button(onClick = { onDone(HomeLayout.arranged(order, off)) }, modifier = Modifier.padding(end = 8.dp).testTag("arrange-done")) {
                        Text(stringResource(R.string.common_done))
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp).testTag("arrange-list"),
            verticalArrangement = Arrangement.spacedBy(RowGap),
        ) {
            Text(
                stringResource(R.string.home_arrange_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 4.dp),
            )
            val moveUp = stringResource(R.string.home_move_up)
            val moveDown = stringResource(R.string.home_move_down)
            val shownText = stringResource(R.string.home_arrange_shown)
            val hiddenText = stringResource(R.string.home_arrange_hidden)
            for ((i, part) in order.withIndex()) {
                key(part.token) {
                    val token = part.token
                    val lifted = dragging == token
                    val shown = token !in off
                    val name = label(part)
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = if (lifted) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(RowHeight)
                            .zIndex(if (lifted) 1f else 0f)
                            .graphicsLayer { translationY = if (lifted) offset else 0f }
                            .then(if (lifted) Modifier.shadow(6.dp, RoundedCornerShape(14.dp)) else Modifier)
                            .semantics(mergeDescendants = true) {
                                stateDescription = if (shown) shownText else hiddenText
                                customActions = listOfNotNull(
                                    if (i > 0) CustomAccessibilityAction(moveUp) { move(token, -1); true } else null,
                                    if (i < order.lastIndex) CustomAccessibilityAction(moveDown) { move(token, 1); true } else null,
                                )
                            }
                            .testTag("arrange-row-$token"),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(end = 12.dp)) {
                            Icon(
                                Icons.Filled.Menu, null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier
                                    .padding(horizontal = 4.dp)
                                    .size(48.dp)
                                    .padding(12.dp)
                                    .pointerInput(token) {
                                        detectDragGestures(
                                            onDragStart = { dragging = token; offset = 0f },
                                            onDragEnd = { dragging = null; offset = 0f },
                                            onDragCancel = { dragging = null; offset = 0f },
                                        ) { change, drag ->
                                            change.consume()
                                            offset += drag.y
                                            // Past half a row, the row swaps with its neighbour and the offset carries on from there.
                                            if (offset > step / 2 && order.indexOfFirst { it.token == token } < order.lastIndex) {
                                                move(token, 1); offset -= step
                                            } else if (offset < -step / 2 && order.indexOfFirst { it.token == token } > 0) {
                                                move(token, -1); offset += step
                                            }
                                        }
                                    }
                                    .testTag("arrange-handle-$token"),
                            )
                            Text(
                                name,
                                style = MaterialTheme.typography.titleMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f).alpha(if (shown) 1f else 0.5f),
                            )
                            Switch(
                                checked = shown,
                                onCheckedChange = { on -> off = if (on) off - token else off + token },
                                modifier = Modifier.testTag("arrange-switch-$token"),
                            )
                        }
                    }
                }
            }
        }
    }
}
