package app.granger.shell

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.granger.R
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch

/**
 * Everything opened (History) and the starred articles (Saved). Tapping a row opens it in a rail tab. The overflow's
 * Clear history asks first, then keeps the pinned tabs and [keepTabIds] (the page you came from, when that was a reader).
 * [onCleared] gets the tabs it left on the rail once it's done.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(
    viewModel: HistoryViewModel,
    onOpenTab: (Long) -> Unit,
    onBack: () -> Unit,
    keepTabIds: () -> Set<Long> = { emptySet() },
    onCleared: (keptTabIds: Set<Long>) -> Unit = {},
) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var filter by rememberSaveable { mutableStateOf("") }
    LaunchedEffect(Unit) { viewModel.setFilter(filter) }
    var page by rememberSaveable { mutableIntStateOf(0) }
    var confirmClear by rememberSaveable { mutableStateOf(false) }
    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text(stringResource(R.string.shell_clear_history_title)) },
            text = { Text(stringResource(R.string.shell_clear_history_body)) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.clear(keepTabIds(), onCleared)
                    confirmClear = false
                }) { Text(stringResource(R.string.shell_clear)) }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text(stringResource(R.string.common_keep)) } },
        )
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.shell_history)) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.common_back)) }
                },
                actions = {
                    var menu by remember { mutableStateOf(false) }
                    Box {
                        IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.common_more)) }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.shell_clear_history)) },
                                onClick = {
                                    menu = false
                                    confirmClear = true
                                },
                            )
                        }
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            PrimaryTabRow(selectedTabIndex = page) {
                Tab(selected = page == 0, onClick = { page = 0 }, text = { Text(stringResource(R.string.shell_history)) })
                Tab(selected = page == 1, onClick = { page = 1 }, text = { Text(stringResource(R.string.shell_saved)) })
            }
            if (page == 0) {
                OutlinedTextField(
                    value = filter,
                    onValueChange = { filter = it; viewModel.setFilter(it) },
                    placeholder = { Text(stringResource(R.string.shell_filter_history)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
            val loaded = ui ?: return@Column
            val rows = if (page == 0) loaded.history else loaded.saved
            if (rows.isEmpty()) {
                Box(Modifier.fillMaxSize().padding(24.dp), Alignment.Center) {
                    Text(
                        when {
                            page == 1 -> stringResource(R.string.shell_no_saved)
                            filter.isBlank() -> stringResource(R.string.shell_nothing_opened)
                            else -> stringResource(R.string.shell_no_history_match)
                        },
                    )
                }
                return@Column
            }
            LazyColumn(Modifier.fillMaxSize()) {
                items(rows, key = { "${it.packId}/${it.path}" }) { row ->
                    ListItem(
                        modifier = if (row.available) {
                            Modifier.clickable { scope.launch { onOpenTab(viewModel.open(row)) } }
                        } else {
                            Modifier
                        },
                        headlineContent = { Text(row.title) },
                        supportingContent = {
                            Text(
                                if (row.available) row.packTitle.resolve()
                                else stringResource(R.string.shell_subtitle_not_available, row.packTitle.resolve()),
                                color = if (row.available) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
                            )
                        },
                        trailingContent = if (page == 1) {
                            {
                                IconButton(onClick = { viewModel.unsave(row) }) {
                                    Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.shell_remove_from_saved))
                                }
                            }
                        } else {
                            null
                        },
                    )
                    HorizontalDivider()
                }
            }
        }
    }
}
