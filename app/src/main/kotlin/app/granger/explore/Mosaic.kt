package app.granger.explore

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridItemSpan
import androidx.compose.foundation.lazy.staggeredgrid.itemsIndexed
import androidx.compose.foundation.lazy.staggeredgrid.rememberLazyStaggeredGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.granger.R
import kotlinx.coroutines.launch

@Composable
fun TopicsScreen(viewModel: SubjectViewModel, actions: ExploreActions, subjectActions: SubjectActions) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val all = SubjectActions(
        onMore = viewModel::more,
        onEra = viewModel::choose,
        onSort = viewModel::sort,
        onJump = viewModel::jump,
        onWander = { scope.launch { viewModel.wanderStart()?.let { subjectActions.onWanderAt(it.packId, it.path) } } },
        onWanderAt = subjectActions.onWanderAt,
        onOpenExplore = subjectActions.onOpenExplore,
        onOpenSubject = subjectActions.onOpenSubject,
        onOpenRoom = subjectActions.onOpenRoom,
    )
    // A room's spread (Plan EX round 2, D3); the whole subject's listing is the same spread without a room's own parts.
    RoomContent(ui, actions, all)
}

/**
 * The A–Z rail: the letters the listing's titles start with, top to bottom. A tap, or a drag along the rail, jumps; the
 * letter under the finger shows large beside the rail while dragging. Each letter is its own button for TalkBack.
 */
@Composable
internal fun LetterRail(letters: List<String>, current: String?, onJump: (String) -> Unit, modifier: Modifier = Modifier) {
    var height by remember { mutableIntStateOf(0) }
    var dragging by remember { mutableStateOf<String?>(null) }
    fun at(y: Float): String? = if (height <= 0) null else letters[(y / height * letters.size).toInt().coerceIn(0, letters.lastIndex)]
    Box(modifier.padding(end = 2.dp)) {
        Column(
            Modifier.width(32.dp).onSizeChanged { height = it.height }.testTag(SubjectTags.RAIL)
                .clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.92f))
                .pointerInput(letters) {
                    detectVerticalDragGestures(
                        onDragStart = { o -> at(o.y)?.let { dragging = it; onJump(it) } },
                        onDragEnd = { dragging = null },
                        onDragCancel = { dragging = null },
                    ) { change, _ -> at(change.position.y)?.takeIf { it != dragging }?.let { dragging = it; onJump(it) } }
                }
                .padding(vertical = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            for (l in letters) {
                val label = stringResource(R.string.explore_jump_letter, l)
                Text(
                    l,
                    style = MaterialTheme.typography.labelMedium,
                    color = if (l == (dragging ?: current)) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = if (l == (dragging ?: current)) FontWeight.Bold else null,
                    modifier = Modifier.clickable(onClickLabel = label) { onJump(l) }.semantics { contentDescription = label }
                        .padding(vertical = 1.dp, horizontal = 8.dp).testTag(SubjectTags.letter(l)),
                )
            }
        }
        dragging?.let { l ->
            Box(Modifier.align(Alignment.CenterStart).offset(x = (-72).dp).size(56.dp).clip(RoundedCornerShape(28.dp))
                .background(MaterialTheme.colorScheme.primary), contentAlignment = Alignment.Center) {
                Text(l, style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.onPrimary)
            }
        }
    }
}
