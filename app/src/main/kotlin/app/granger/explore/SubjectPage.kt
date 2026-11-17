package app.granger.explore

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.granger.R

/**
 * A subject's page (Plan EX editorial pass; Neal, 2026-11-17: curated, editorialized, its topics broken out by
 * sub-category): the subject's own picture and opening sentence; Wander and Quiz; its sub-categories as an asymmetric
 * flow of picture cards, the largest leading; See all its topics; then its book collections and the shelf's books on it.
 * A sub-category opens its listing ([TopicsContent]). With an index from before sub-categories, its best-known articles.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SubjectPageContent(ui: SubjectUi?, actions: ExploreActions, subject: SubjectActions = SubjectActions()) {
    val language = LocalConfiguration.current.locales[0].language
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(ui?.let { stringResource(it.subject.label) }.orEmpty(), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = { IconButton(onClick = actions.onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.common_back)) } },
            )
        },
    ) { padding ->
        val loaded = ui ?: return@Scaffold
        val words = app.granger.home.yearWords()
        LazyColumn(Modifier.padding(padding).fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
            item(key = "hero") {
                Column(Modifier.padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (loaded.hero?.picture != null) {
                        FramedPicture(loaded.hero.picture, 400.dp, Modifier.fillMaxWidth().height(210.dp).clip(RoundedCornerShape(20.dp)).clearAndSetSemantics {})
                    }
                    loaded.hero?.sentence?.let {
                        Text(firstSentence(it, 240), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(horizontal = 4.dp))
                    }
                    if (loaded.count > 0) {
                        val all = pluralStringResource(R.plurals.explore_topics, loaded.count, count(loaded.count))
                        val text = if (loaded.dated > 0) stringResource(R.string.explore_subject_counts, all, pluralStringResource(R.plurals.explore_dated, loaded.dated, count(loaded.dated))) else all
                        Text(text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(horizontal = 4.dp))
                    }
                }
            }
            if (loaded.canWander || loaded.canQuiz) {
                item(key = "do") {
                    Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (loaded.canWander) FilledTonalButton(onClick = subject.onWander, modifier = Modifier.testTag(SubjectTags.WANDER)) { Text(stringResource(R.string.explore_subject_wander)) }
                        if (loaded.canQuiz) FilledTonalButton(onClick = subject.onQuiz, modifier = Modifier.testTag(SubjectTags.QUIZ)) { Text(stringResource(R.string.explore_subject_quiz)) }
                    }
                }
            }
            val cards = loaded.facets
            if (cards.isNotEmpty()) {
                // The flow: the largest-curated first sub-category wide, then blocks of three (one large beside two small,
                // mirrored each time), then any left over as picture-led rows.
                item(key = "facet-lead") { FacetWide(cards.first(), language) { subject.onOpenFacet(cards.first().facet.id) } }
                val rest = cards.drop(1)
                val blocks = rest.chunked(3).filter { it.size == 3 }
                blocks.forEachIndexed { b, block ->
                    item(key = "facet-block:" + block.joinToString { it.facet.id }) {
                        FacetBlock(block, bigFirst = b % 2 == 1, language) { subject.onOpenFacet(it) }
                    }
                }
                items(rest.drop(blocks.size * 3), key = { "facet-band:" + it.facet.id }) { c ->
                    FacetBand(c, language) { subject.onOpenFacet(c.facet.id) }
                }
            } else if (loaded.articles.isNotEmpty()) {
                // An index from before sub-categories: the best-known articles, as the page was.
                item(key = "articles-head") { SectionHead(stringResource(R.string.explore_subject_articles), Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp)) }
                items(loaded.articles.take(FALLBACK_ARTICLES), key = { "a:" + it.packId + "/" + it.path }) { a ->
                    ArticleRow(a, words, subject.picture) { actions.onOpenArticle(a.packId, a.path, a.title) }
                }
            }
            if (loaded.count > 0) {
                item(key = "see-all") {
                    OutlinedButton(onClick = { subject.onOpenFacet(null) }, modifier = Modifier.padding(start = 16.dp, top = 16.dp).testTag(SubjectTags.SEE_ALL)) {
                        Text(pluralStringResource(R.plurals.explore_see_all, loaded.count, count(loaded.count)))
                    }
                }
            }
            if (loaded.collections.isNotEmpty()) {
                item(key = "collections-head") { SectionHead(stringResource(R.string.explore_subject_collections), Modifier.padding(start = 16.dp, end = 16.dp, top = 20.dp)) }
                items(loaded.collections, key = { "collection:" + it.id }) { c ->
                    ListItem(
                        headlineContent = { Text(c.title) },
                        supportingContent = if (c.books > 0) ({ Text(pluralStringResource(R.plurals.explore_collection_books, c.books, count(c.books))) }) else null,
                        modifier = Modifier.testTag(SubjectTags.collection(c.id)).clickable { subject.onOpenCollection(c.id) },
                    )
                }
            }
            if (loaded.books.isNotEmpty()) {
                item(key = "books-head") {
                    SectionHead(pluralStringResource(R.plurals.explore_subject_books, loaded.books.size, count(loaded.books.size)), Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 8.dp))
                }
                item(key = "books") {
                    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        items(loaded.books.take(BOOKS_ON_PAGE), key = { it.bookId }) { BookCover(it) { subject.onOpenBook(it.bookId) } }
                    }
                }
            }
            if (loaded.packs.isNotEmpty()) {
                item(key = "packs-head") { SectionHead(stringResource(R.string.explore_subject_packs), Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp)) }
                items(loaded.packs, key = { "pack:" + it.id }) { pack ->
                    ListItem(headlineContent = { Text(pack.title) }, modifier = Modifier.clickable { actions.onOpenPack(pack.id) })
                }
            }
            if (loaded.count == 0 && loaded.packs.isEmpty() && loaded.collections.isEmpty()) {
                item(key = "empty") { Text(stringResource(R.string.explore_nothing_here), modifier = Modifier.padding(16.dp)) }
            }
        }
    }
}

private const val FALLBACK_ARTICLES = 12
private const val BOOKS_ON_PAGE = 24

private fun examples(c: FacetCard) = c.examples.joinToString(" · ") { it.title }

@Composable
private fun facetCount(c: FacetCard) = pluralStringResource(R.plurals.explore_topics, c.count, count(c.count))

/** The lead sub-category: a wide picture, its name, count and best-known few. */
@Composable
private fun FacetWide(c: FacetCard, language: String, onOpen: () -> Unit) {
    Card(onClick = onOpen, modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 12.dp).fillMaxWidth().testTag(SubjectTags.facet(c.facet.id)),
        shape = RoundedCornerShape(18.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        FramedPicture(c.picture, 400.dp, Modifier.fillMaxWidth().height(170.dp).clearAndSetSemantics {})
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(c.facet.name(language), style = MaterialTheme.typography.headlineSmall)
            Text(facetCount(c), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            Text(examples(c), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** Three sub-categories: one large with its best-known few, two small stacked beside it; [bigFirst] puts the large left. */
@Composable
private fun FacetBlock(cards: List<FacetCard>, bigFirst: Boolean, language: String, onOpen: (String) -> Unit) {
    val (big, a, b) = cards
    Row(Modifier.padding(start = 12.dp, end = 12.dp, top = 12.dp).fillMaxWidth().height(290.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        @Composable fun smalls() = Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            FacetSmall(a, language, Modifier.weight(1f)) { onOpen(a.facet.id) }
            FacetSmall(b, language, Modifier.weight(1f)) { onOpen(b.facet.id) }
        }
        if (!bigFirst) smalls()
        Card(onClick = { onOpen(big.facet.id) }, modifier = Modifier.weight(1.35f).fillMaxHeight().testTag(SubjectTags.facet(big.facet.id)),
            shape = RoundedCornerShape(18.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
            FramedPicture(big.picture, 240.dp, Modifier.fillMaxWidth().height(140.dp).clearAndSetSemantics {})
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(big.facet.name(language), style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(facetCount(big), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                Text(examples(big), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
        }
        if (bigFirst) smalls()
    }
}

@Composable
private fun FacetSmall(c: FacetCard, language: String, modifier: Modifier, onOpen: () -> Unit) {
    Card(onClick = onOpen, modifier = modifier.fillMaxWidth().testTag(SubjectTags.facet(c.facet.id)), shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        // The picture takes what the name and count leave, so a two-line name never hides the count.
        FramedPicture(c.picture, 160.dp, Modifier.fillMaxWidth().weight(1f).clearAndSetSemantics {})
        Column(Modifier.padding(horizontal = 10.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(c.facet.name(language), style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(facetCount(c), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
        }
    }
}

/** A sub-category left over from the blocks, as a row: its picture beside its name, count and best-known few. */
@Composable
private fun FacetBand(c: FacetCard, language: String, onOpen: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onOpen).padding(horizontal = 16.dp, vertical = 8.dp).testTag(SubjectTags.facet(c.facet.id)),
        horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically,
    ) {
        FramedPicture(c.picture, 72.dp, Modifier.size(72.dp).clip(RoundedCornerShape(14.dp)).clearAndSetSemantics {})
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(c.facet.name(language), style = MaterialTheme.typography.titleMedium)
            Text(facetCount(c), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            Text(examples(c), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}
