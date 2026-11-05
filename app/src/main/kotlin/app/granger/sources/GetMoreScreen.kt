package app.granger.sources

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.annotation.StringRes
import app.granger.R

/** One place to get more from. Archive.org is one row per kind, with its kind in the name (Plan U3b Decision 2). */
enum class GetMoreLink(@StringRes val title: Int, @StringRes val subtitle: Int) {
    KIWIX(R.string.sources_link_kiwix, R.string.sources_link_kiwix_sub),
    GRANGER_CLASSICS(R.string.sources_link_classics, R.string.sources_link_classics_sub),
    GUTENBERG(R.string.sources_link_gutenberg, R.string.sources_link_gutenberg_sub),
    KIWIX_BOOKS(R.string.sources_link_kiwix_books, R.string.sources_link_kiwix_books_sub),
    ARCHIVE_ORG_BOOKS(R.string.sources_link_archive_books, R.string.sources_link_archive_books_sub),
    LIBRIVOX(R.string.sources_link_librivox, R.string.sources_link_librivox_sub),
    ARCHIVE_ORG_AUDIOBOOKS(R.string.sources_link_archive_audiobooks, R.string.sources_link_archive_audiobooks_sub),
    ARCHIVE_ORG_MUSIC(R.string.sources_link_archive_music, R.string.sources_link_archive_music_sub),
    MODELS(R.string.sources_link_models, R.string.sources_link_models_sub),
}

/** Get more's sections, each with its heading. */
enum class GetMoreGroup(@StringRes val heading: Int) {
    KNOWLEDGE(R.string.sources_group_knowledge), BOOKS(R.string.sources_group_books),
    AUDIO(R.string.sources_group_audio), MODELS(R.string.sources_group_models),
}

/** Get more's sections, in order. Models stay in Settings too. */
val GET_MORE_SECTIONS: List<Pair<GetMoreGroup, List<GetMoreLink>>> = listOf(
    GetMoreGroup.KNOWLEDGE to listOf(GetMoreLink.KIWIX),
    GetMoreGroup.BOOKS to listOf(GetMoreLink.GRANGER_CLASSICS, GetMoreLink.GUTENBERG, GetMoreLink.KIWIX_BOOKS, GetMoreLink.ARCHIVE_ORG_BOOKS),
    GetMoreGroup.AUDIO to listOf(GetMoreLink.LIBRIVOX, GetMoreLink.ARCHIVE_ORG_AUDIOBOOKS, GetMoreLink.ARCHIVE_ORG_MUSIC),
    GetMoreGroup.MODELS to listOf(GetMoreLink.MODELS),
)

/**
 * Whose + opened Get more (Plan KB walkthrough 2): the Library's offers only book sources, and importing your own;
 * Listen's only audiobooks and music. Browse's + opens Kiwix itself. Null is every source (Downloads).
 */
enum class GetMoreFor(@StringRes val title: Int, val group: GetMoreGroup) {
    BOOKS(R.string.sources_get_books, GetMoreGroup.BOOKS),
    LISTEN(R.string.sources_get_audio, GetMoreGroup.AUDIO),
}

/** Get more's sections for [only]: its one section, or all of them. */
fun getMoreSections(only: GetMoreFor?): List<Pair<GetMoreGroup, List<GetMoreLink>>> =
    if (only == null) GET_MORE_SECTIONS else GET_MORE_SECTIONS.filter { it.first == only.group }

/**
 * Where sources live (Plan U3b Decision 2): each row opens its source's screen. [only] narrows it to one tool's sources;
 * [onImport], when given, adds "Import your own" under the books.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GetMoreScreen(
    onOpen: (GetMoreLink) -> Unit, onBack: () -> Unit, only: GetMoreFor? = null, onImport: (() -> Unit)? = null,
    /** Add a pack from a file (Plan E1 item 4): the picked file, and what came of it for a snackbar. */
    onImportPack: (suspend (android.net.Uri) -> app.granger.UiText)? = null,
) {
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current
    // A ZIM has no registered type, so any file can be picked; the header check says if it isn't one.
    val packPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null && onImportPack != null) scope.launch { snackbar.showSnackbar(onImportPack(uri).resolve(context)) }
    }
    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(only?.title ?: R.string.sources_get_more)) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.common_back)) }
                },
            )
        },
    ) { padding ->
        LazyColumn(Modifier.padding(padding).fillMaxSize()) {
            for ((group, links) in getMoreSections(only)) {
                item(key = "heading-$group") {
                    Text(
                        stringResource(group.heading),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(start = 16.dp, top = 24.dp, bottom = 4.dp),
                    )
                }
                items(links, key = { it.name }) { link ->
                    ListItem(
                        headlineContent = { Text(stringResource(link.title)) },
                        supportingContent = { Text(stringResource(link.subtitle)) },
                        modifier = Modifier.clickable { onOpen(link) },
                    )
                    HorizontalDivider()
                }
                if (group == GetMoreGroup.KNOWLEDGE && onImportPack != null) {
                    item(key = "import-pack") {
                        ListItem(
                            headlineContent = { Text(stringResource(R.string.packs_import)) },
                            supportingContent = { Text(stringResource(R.string.packs_import_sub)) },
                            modifier = Modifier.clickable { packPicker.launch(arrayOf("*/*")) },
                        )
                        HorizontalDivider()
                    }
                }
                if (group == GetMoreGroup.BOOKS && onImport != null) {
                    item(key = "import") {
                        ListItem(
                            headlineContent = { Text(stringResource(R.string.sources_import_own)) },
                            supportingContent = { Text(stringResource(R.string.sources_import_own_sub)) },
                            modifier = Modifier.clickable(onClick = onImport),
                        )
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}
