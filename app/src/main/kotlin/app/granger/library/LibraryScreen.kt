package app.granger.library

import android.text.format.Formatter
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.granger.R
import app.granger.core.library.Pack
import app.granger.core.library.PackStatus

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    viewModel: LibraryViewModel,
    packsFolder: String,
    /** Shown once as a snackbar, e.g. why the app didn't resume reading. */
    notice: String?,
    onOpenPack: (Pack) -> Unit,
    onSearch: () -> Unit,
    onHistory: () -> Unit,
    onDownloads: () -> Unit,
    onReadiness: () -> Unit,
) {
    LifecycleResumeEffect(Unit) {
        viewModel.refresh()
        onPauseOrDispose {}
    }
    val packs by viewModel.packs.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var noticeShown by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(notice) {
        if (notice != null && !noticeShown) {
            noticeShown = true
            snackbar.showSnackbar(notice)
        }
    }
    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text("Library") },
                actions = {
                    IconButton(onClick = onSearch) { Icon(Icons.Filled.Search, contentDescription = "Search") }
                    IconButton(onClick = onHistory) { Icon(painterResource(R.drawable.ic_history), contentDescription = "History") }
                    IconButton(onClick = onDownloads) { Icon(Icons.Filled.Add, contentDescription = "Downloads") }
                    IconButton(onClick = onReadiness) { Icon(Icons.Filled.CheckCircle, contentDescription = "Readiness") }
                },
            )
        },
    ) { padding ->
        val list = packs
        when {
            list == null -> Unit
            list.isEmpty() -> Box(Modifier.padding(padding).fillMaxSize().padding(24.dp), Alignment.Center) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("No packs installed yet.", style = MaterialTheme.typography.titleMedium)
                    Button(onClick = onDownloads) { Text("Get packs") }
                    Text("Or copy .zim files into $packsFolder", style = MaterialTheme.typography.bodySmall)
                }
            }
            else -> LazyColumn(Modifier.padding(padding).fillMaxSize()) {
                items(list, key = { it.id }) { pack ->
                    PackRow(pack, onClick = { onOpenPack(pack) })
                    HorizontalDivider()
                }
            }
        }
    }
}

@Composable
private fun PackRow(pack: Pack, onClick: () -> Unit) {
    val context = LocalContext.current
    val details = listOf(pack.language, pack.date, Formatter.formatShortFileSize(context, pack.sizeBytes))
        .filter { it.isNotBlank() }
        .joinToString(" · ")
    val problem = when (pack.status) {
        PackStatus.OK -> null
        PackStatus.MISSING -> "File not found. Reinsert the SD card or download the pack again."
        PackStatus.CORRUPT -> "This file is damaged and can't be opened."
    }
    ListItem(
        modifier = if (pack.status == PackStatus.OK) Modifier.clickable(onClick = onClick) else Modifier,
        headlineContent = { Text(pack.title) },
        supportingContent = {
            Column {
                Text(details)
                if (problem != null) Text(problem, color = MaterialTheme.colorScheme.error)
            }
        },
    )
}
