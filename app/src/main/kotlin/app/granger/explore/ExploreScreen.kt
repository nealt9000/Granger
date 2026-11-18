package app.granger.explore

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import app.granger.R
import app.granger.shell.rememberRailCover
import java.text.NumberFormat

/** What Explore's rows lead to. */
class ExploreActions(
    val onOpenSubject: (Subject) -> Unit = {},
    val onOpenArticle: (packId: String, path: String, title: String) -> Unit = { _, _, _ -> },
    /** The Timeline, at an article (an era's best-known), or where it last was with null. */
    val onOpenTimeline: (packId: String?, path: String?) -> Unit = { _, _ -> },
    /** Your sources: the packs as Browse lists them. */
    val onOpenSources: () -> Unit = {},
    val onOpenPack: (packId: String) -> Unit = {},
    /** Get more: the catalog's packs. */
    val onGetMore: () -> Unit = {},
    val onBack: () -> Unit = {},
    /** Wander from an article: Home's Wander, started there. */
    val onWanderAt: (packId: String, path: String) -> Unit = { _, _ -> },
    /** The Games screen. */
    val onOpenGames: () -> Unit = {},
)

object ExploreTags {
    /** A subject's plate in the landing's mosaic. */
    fun door(subject: Subject) = PlateTags.plate("door:${subject.id}")
    fun era(era: Era) = "explore_era_${era.name.lowercase()}"
    const val NO_SUBJECTS = "explore_no_subjects"
    const val GET_MORE = "explore_get_more"
    /** Today's spread: its plates (each opens it), its button, and the way to another. */
    fun spreadPlate(i: Int) = PlateTags.plate("spread:$i")
    const val OPEN_SPREAD = "explore_open_spread"
    const val ANOTHER = "explore_another"
    const val TIMELINE = "explore_timeline"
}

internal fun count(n: Int): String = NumberFormat.getIntegerInstance().format(n)

@Composable
fun ExploreScreen(viewModel: ExploreViewModel, actions: ExploreActions) {
    androidx.lifecycle.compose.LifecycleResumeEffect(Unit) {
        viewModel.refresh()
        onPauseOrDispose {}
    }
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    ExploreContent(
        ui, actions, onAnother = viewModel::another,
        onWander = { scope.launch { viewModel.wanderStart()?.let { actions.onWanderAt(it.packId, it.path) } } },
    )
}

@Composable
fun SubjectScreen(viewModel: SubjectViewModel, actions: ExploreActions, subjectActions: SubjectActions) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    SubjectPageContent(
        ui, actions,
        SubjectActions(
            onMore = viewModel::more,
            onEra = viewModel::choose,
            onSort = viewModel::sort,
            onWander = { scope.launch { viewModel.wanderStart()?.let { subjectActions.onWanderAt(it.packId, it.path) } } },
            onQuiz = subjectActions.onQuiz,
            onOpenBook = subjectActions.onOpenBook,
            onOpenCollection = subjectActions.onOpenCollection,
            onOpenFacet = subjectActions.onOpenFacet,
            onWanderAt = subjectActions.onWanderAt,
            picture = viewModel::picture,
            onOpenExplore = subjectActions.onOpenExplore,
            onOpenDoor = subjectActions.onOpenDoor,
        ),
    )
}

/** What a subject page's own controls do; [SubjectScreen] fills the paging and filters from its view model. */
class SubjectActions(
    val onMore: () -> Unit = {},
    val onEra: (Era?) -> Unit = {},
    val onSort: (SubjectSort) -> Unit = {},
    /** In A–Z order: the list from a letter on (the rail). */
    val onJump: (letter: String) -> Unit = {},
    val onWander: () -> Unit = {},
    /** Wander from an article: Home's Wander, started there. */
    val onWanderAt: (packId: String, path: String) -> Unit = { _, _ -> },
    val onQuiz: () -> Unit = {},
    val onOpenBook: (bookId: String) -> Unit = {},
    /** A book collection's Library page (`c:<key>`). */
    val onOpenCollection: (collectionId: String) -> Unit = {},
    /** A sub-category's listing (its facet id), or the whole subject's (null). */
    val onOpenFacet: (facetId: String?) -> Unit = {},
    /** An article's cached picture; null when it has none. Blocking. */
    val picture: (ExploreArticle) -> String? = { null },
    /** Back along the trail: Explore's landing, and this subject's page. */
    val onOpenExplore: () -> Unit = {},
    val onOpenSubject: () -> Unit = {},
    /** Another room. [through] a door (the neighbour takes this room's place) or by a link (it opens on top). */
    val onOpenRoom: (subject: String, facet: String, through: Boolean) -> Unit = { _, _, _ -> },
    /** Through a subject page's door: the neighbouring subject takes this one's place. */
    val onOpenDoor: (subject: String) -> Unit = {},
)

object SubjectTags {
    const val WANDER = "subject_wander"
    const val QUIZ = "subject_quiz"
    const val ALL_ERAS = "subject_era_all"
    fun era(era: Era) = "subject_era_${era.name.lowercase()}"
    fun sort(sort: SubjectSort) = "subject_sort_${sort.name.lowercase()}"
    const val RAIL = "subject_az_rail"
    fun letter(l: String) = "subject_az_$l"
    fun book(id: String) = "subject_book_$id"
    fun collection(id: String) = "subject_collection_$id"
    /** A room's plate on the subject page. */
    fun facet(id: String) = PlateTags.plate("room:$id")
    fun opening(path: String) = PlateTags.plate("opening:$path")
    const val SEE_ALL = "subject_see_all"
}


@Composable
internal fun ArticleRow(a: ExploreArticle, words: app.granger.home.YearWords, pictureOf: (ExploreArticle) -> String?, onOpen: () -> Unit) {
    val path by androidx.compose.runtime.produceState<String?>(a.picture, a) { if (value == null) value = withContext(Dispatchers.IO) { pictureOf(a) } }
    val picture = rememberRailCover(path, 48.dp)
    ListItem(
        headlineContent = { Text(a.title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        supportingContent = a.mark?.let { m -> { Text(words.of(m)) } },
        leadingContent = {
            Box(Modifier.size(48.dp).clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceContainerHighest)) {
                if (picture != null) Image(picture, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            }
        },
        modifier = Modifier.clickable(onClick = onOpen),
    )
}

@Composable
internal fun BookCover(book: ExploreBook, onOpen: () -> Unit) {
    val cover = rememberRailCover(book.cover, 120.dp)
    Column(Modifier.width(84.dp).clickable(onClick = onOpen).testTag(SubjectTags.book(book.bookId))) {
        Box(Modifier.width(84.dp).height(120.dp).clip(RoundedCornerShape(6.dp)).background(MaterialTheme.colorScheme.surfaceContainerHighest)) {
            if (cover != null) Image(cover, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        }
        Text(book.title, style = MaterialTheme.typography.labelMedium, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
    }
}
