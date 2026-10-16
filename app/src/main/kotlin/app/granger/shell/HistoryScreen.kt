package app.granger.shell

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch

/** Everything opened (History) and the starred articles (Saved). Tapping a row opens it in a rail tab. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(viewModel: HistoryViewModel, onOpenTab: (Long) -> Unit, onBack: () -> Unit) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var page by rememberSaveable { mutableIntStateOf(0) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("History") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            PrimaryTabRow(selectedTabIndex = page) {
                Tab(selected = page == 0, onClick = { page = 0 }, text = { Text("History") })
                Tab(selected = page == 1, onClick = { page = 1 }, text = { Text("Saved") })
            }
            val loaded = ui ?: return@Column
            val rows = if (page == 0) loaded.history else loaded.saved
            if (rows.isEmpty()) {
                Box(Modifier.fillMaxSize().padding(24.dp), Alignment.Center) {
                    Text(
                        if (page == 0) "Nothing opened yet." else "No saved articles yet. Tap the star while reading to save one.",
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
                                if (row.available) row.packTitle else "${row.packTitle} · not available",
                                color = if (row.available) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
                            )
                        },
                        trailingContent = if (page == 1) {
                            {
                                IconButton(onClick = { viewModel.unsave(row) }) {
                                    Icon(Icons.Filled.Delete, contentDescription = "Remove from saved")
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
