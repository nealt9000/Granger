package app.granger.sources

import androidx.compose.foundation.clickable
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
import androidx.compose.ui.unit.dp

/** One place to get more from. Archive.org is one row per kind, with its kind in the name (Plan U3b Decision 2). */
enum class GetMoreLink(val title: String, val subtitle: String) {
    KIWIX("Kiwix", "Wikipedia, Stack Exchange, medicine, how-to and more, offline"),
    GRANGER_CLASSICS("Granger Classics", "Public-domain e-books, ready to read"),
    GUTENBERG("Project Gutenberg", "Public-domain books by subject, in packs from Kiwix"),
    KIWIX_BOOKS("Kiwix libraries", "Guides and handbooks: food, water, medicine, knots and more"),
    ARCHIVE_ORG_BOOKS("Archive.org books", "Out-of-copyright books from the Internet Archive"),
    LIBRIVOX("LibriVox", "Public-domain audiobooks, read by volunteers"),
    ARCHIVE_ORG_AUDIOBOOKS("Archive.org audiobooks", "Spoken word from the Internet Archive"),
    ARCHIVE_ORG_MUSIC("Archive.org music", "Recordings from the Internet Archive"),
    MODELS("Models and voices", "What Ask and Read aloud run on"),
}

/** Get more's sections, in order. Models stay in Settings too. */
val GET_MORE_SECTIONS: List<Pair<String, List<GetMoreLink>>> = listOf(
    "Knowledge packs" to listOf(GetMoreLink.KIWIX),
    "Books" to listOf(GetMoreLink.GRANGER_CLASSICS, GetMoreLink.GUTENBERG, GetMoreLink.KIWIX_BOOKS, GetMoreLink.ARCHIVE_ORG_BOOKS),
    "Audiobooks & music" to listOf(GetMoreLink.LIBRIVOX, GetMoreLink.ARCHIVE_ORG_AUDIOBOOKS, GetMoreLink.ARCHIVE_ORG_MUSIC),
    "Models & voices" to listOf(GetMoreLink.MODELS),
)

/**
 * Whose + opened Get more (Plan KB walkthrough 2): the Library's offers only book sources, and importing your own;
 * Listen's only audiobooks and music. Browse's + opens Kiwix itself. Null is every source (Downloads).
 */
enum class GetMoreFor(val title: String, val heading: String) {
    BOOKS("Get books", "Books"),
    LISTEN("Get audiobooks & music", "Audiobooks & music"),
}

/** Get more's sections for [only]: its one section, or all of them. */
fun getMoreSections(only: GetMoreFor?): List<Pair<String, List<GetMoreLink>>> =
    if (only == null) GET_MORE_SECTIONS else GET_MORE_SECTIONS.filter { it.first == only.heading }

const val IMPORT_YOUR_OWN = "Import your own"
const val IMPORT_YOUR_OWN_SUBTITLE = "EPUB, PDF, TXT or Markdown books from this phone"

/**
 * Where sources live (Plan U3b Decision 2): each row opens its source's screen. [only] narrows it to one tool's sources;
 * [onImport], when given, adds "Import your own" under the books.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GetMoreScreen(onOpen: (GetMoreLink) -> Unit, onBack: () -> Unit, only: GetMoreFor? = null, onImport: (() -> Unit)? = null) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(only?.title ?: "Get more") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
            )
        },
    ) { padding ->
        LazyColumn(Modifier.padding(padding).fillMaxSize()) {
            for ((heading, links) in getMoreSections(only)) {
                item(key = "heading-$heading") {
                    Text(
                        heading,
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(start = 16.dp, top = 24.dp, bottom = 4.dp),
                    )
                }
                items(links, key = { it.name }) { link ->
                    ListItem(
                        headlineContent = { Text(link.title) },
                        supportingContent = { Text(link.subtitle) },
                        modifier = Modifier.clickable { onOpen(link) },
                    )
                    HorizontalDivider()
                }
                if (heading == GetMoreFor.BOOKS.heading && onImport != null) {
                    item(key = "import") {
                        ListItem(
                            headlineContent = { Text(IMPORT_YOUR_OWN) },
                            supportingContent = { Text(IMPORT_YOUR_OWN_SUBTITLE) },
                            modifier = Modifier.clickable(onClick = onImport),
                        )
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}
