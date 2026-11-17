package app.granger.browse

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.granger.R
import app.granger.core.library.Library
import app.granger.core.library.PackFile
import app.granger.core.library.PackFiles
import app.granger.formatBytes
import app.granger.theme.GroupLabel
import app.granger.theme.GroupedItem
import app.granger.theme.GroupedRow
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** A pack's files: [files] null while listing; empty when none could be listed. */
data class PackFilesUi(val title: String = "", val files: List<PackFile>? = null)

/** Files in this pack (Plan KB2 Task 4.6): the PDFs and EPUBs of website pack [packId], listed once from its ZIM. */
class PackFilesViewModel(
    private val packId: String,
    private val library: Library,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {
    private val state = MutableStateFlow(PackFilesUi())
    val ui: StateFlow<PackFilesUi> = state

    init {
        viewModelScope.launch {
            val title = library.packs.first().firstOrNull { it.id == packId }?.title.orEmpty()
            state.value = PackFilesUi(title)
            val files = withContext(io) { library.archive(packId)?.let(PackFiles::list) }.orEmpty()
            state.value = PackFilesUi(title, files)
        }
    }
}

/** "PDF · 2.0 MB", "EPUB · 340 KB". */
internal fun fileMeta(file: PackFile): String {
    val kind = when (file.mimeType) {
        "application/epub+zip" -> "EPUB"
        "application/pdf" -> "PDF"
        else -> file.path.substringAfterLast('.', "").uppercase()
    }
    return listOf(kind, formatBytes(file.sizeBytes)).filter { it.isNotBlank() }.joinToString(" · ")
}

/** The pack's files A–Z by title, in Explore's grouped rows; a tap opens one as a pack document. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PackFilesScreen(viewModel: PackFilesViewModel, onBack: () -> Unit, onOpen: (path: String, title: String) -> Unit) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(ui.title.ifEmpty { stringResource(R.string.browse_pack_files) }, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.common_back)) }
                },
            )
        },
    ) { padding ->
        val files = ui.files
        LazyColumn(Modifier.padding(padding).fillMaxSize()) {
            item(key = "label") { GroupLabel(stringResource(R.string.browse_pack_files)) }
            when {
                files == null -> item(key = "loading") { Box(Modifier.fillMaxWidth().padding(32.dp), Alignment.Center) { CircularProgressIndicator() } }
                files.isEmpty() -> item(key = "none") {
                    Text(stringResource(R.string.browse_pack_files_none), color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(16.dp))
                }
                else -> itemsIndexed(files, key = { _, f -> f.path }) { i, file ->
                    GroupedItem(i, files.size) { GroupedRow(file.title, fileMeta(file), onClick = { onOpen(file.path, file.title) }) }
                }
            }
            item(key = "foot") { Spacer(Modifier.height(24.dp)) }
        }
    }
}
