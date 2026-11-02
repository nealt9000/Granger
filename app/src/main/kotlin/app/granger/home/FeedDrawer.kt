package app.granger.home

import app.granger.R
import app.granger.UiText
import app.granger.core.library.Book
import app.granger.core.library.BookChunk
import app.granger.core.library.BookHit
import app.granger.core.library.chunkLabel
import app.granger.core.library.FeedKind
import app.granger.core.library.HomeFeed
import app.granger.core.library.IndexPages
import app.granger.core.library.LibrarySearch
import app.granger.core.library.Pack
import app.granger.core.library.PageTitles
import app.granger.core.zim.ZimArchive
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale
import kotlin.random.Random
import org.jsoup.Jsoup

/**
 * Draws a feed's cards for a day (Plan H2, Decisions 5 and 7–9). The day and the feed id seed the choices, so a
 * redraw on the same day gives the same cards; a new day gives new ones. Reads archives and the book index only;
 * no network. Call on an I/O dispatcher.
 */
class FeedDrawer(
    private val archiveOf: (Pack) -> ZimArchive?,
    /** A book's chunks (spine items) in order; empty until it is indexed. */
    private val chunksOf: suspend (bookId: String) -> List<BookChunk>,
    /** Full-text hits inside books. */
    private val bookSearch: suspend (query: String) -> List<BookHit>,
    private val librarySearch: LibrarySearch,
    /** The library's languages, main first (Plan L3): On this day reads the first one's encyclopedia. */
    private val languages: () -> List<String> = { listOf("en") },
    /** A cached thumbnail for an article (its lead image), or null; see [ArticleThumbnails]. Last, for a trailing lambda. */
    private val thumbnail: (archive: ZimArchive, path: String, html: String) -> String? = { _, _, _ -> null },
) {
    /** The feed's row for the day, or null when it has nothing to show. */
    suspend fun draw(feed: HomeFeed, scope: Resolved, day: LocalDate): Drawn? {
        val random = Random(seed(day, feed.id))
        val drawn = when (feed.kind) {
            FeedKind.RANDOM -> Drawn(random(scope, random))
            FeedKind.ON_THIS_DAY -> onThisDay(scope, day, random)
            FeedKind.SEARCH -> {
                val query = feed.query.trim()
                if (query.isEmpty()) return null
                Drawn(search(scope, query, random))
            }
        }
        return drawn.takeIf { it.cards.isNotEmpty() }
    }

    private suspend fun random(scope: Resolved, random: Random): List<Card> = when {
        scope.books.isNotEmpty() -> scope.books.shuffled(random).take(CARDS).map { book ->
            // Poetry, scripture and philosophy are for dipping into: a random chapter. A novel or a play is a
            // suggestion: it opens at the start, or where the reader left off (Neal, 2026-10-22).
            val chunks = if (dipsIn(book)) chunksOf(book.id) else emptyList()
            val chunk = chunks.takeIf { it.isNotEmpty() }?.let { it[random.nextInt(it.size)] }
            Card(
                title = book.title,
                subtitle = listOfNotNull(chunk?.let { chunkLabel(book, it.href, chunks) }, book.author.ifBlank { null }).joinToString(" · "),
                target = CardTarget.Book(book.id, chunk?.href, chunk?.progression ?: 0.0),
                imagePath = book.coverPath,
            )
        }
        scope.packs.isNotEmpty() -> {
            val cards = LinkedHashMap<String, Card>()
            repeat(CARDS * 2) {
                if (cards.size >= CARDS) return@repeat
                val pack = scope.packs[random.nextInt(scope.packs.size)]
                val archive = archiveOf(pack) ?: return@repeat
                val path = archive.randomPath() ?: return@repeat
                // A user or tag list isn't a page to suggest (Plan U3b Decision 10).
                if (IndexPages.isIndex(path)) return@repeat
                val key = "${pack.id}/$path"
                if (key in cards) return@repeat
                cards[key] = articleCard(archive, pack, path)
            }
            cards.values.toList()
        }
        scope.albums.isNotEmpty() -> scope.albums.shuffled(random).take(CARDS).map {
            Card(it.title, it.albumArtist, CardTarget.Album(it.key), imagePath = it.coverPath)
        }
        else -> scope.audiobooks.shuffled(random).take(CARDS).map { Card(it.title, it.author, CardTarget.Audiobook(it.id), imagePath = it.coverPath) }
    }

    /** A random chapter suits a book one dips into; by the shelf's subjects (the Classics' category, or an import's). */
    private fun dipsIn(book: Book): Boolean {
        val subjects = book.subjects.lowercase()
        return DIP_IN.any { it in subjects }
    }

    /** An article card with its title read from the page and, when the page has one, its lead image. */
    private fun articleCard(archive: ZimArchive, pack: Pack, path: String): Card {
        val html = archive.read(path)?.let { String(it.bytes, Charsets.UTF_8) }
        return Card(
            title = html?.let { pageTitle(it, pack.title) } ?: pathTitle(path),
            subtitle = pack.title,
            target = CardTarget.Article(pack.id, path),
            imagePath = html?.let { thumbnail(archive, path, it) },
        )
    }

    /**
     * The day's page from the encyclopedias in [scope], in the library's languages first ([encyclopedias]); every pack
     * is tried, not only the first (a full Wikipedia has the page, a "Best of" slice may not). With none, articles that
     * mention the date, marked [Drawn.mentions] so the row says so rather than passing them off as the day's own page;
     * books only search their text the same way.
     */
    private suspend fun onThisDay(scope: Resolved, day: LocalDate, random: Random): Drawn {
        val langs = languages().ifEmpty { listOf("en") }
        val candidates = encyclopedias(scope.packs, langs)
        if (candidates.isEmpty()) {
            // Books only: the date in the text, as the main language writes it.
            val title = DayPage.title(day, dayLanguage(langs.first()))
            return Drawn(bookSearch(title).shuffled(random).take(CARDS).map { bookCard(it) }, mentions = true)
        }
        for ((pack, lang) in candidates) {
            val archive = archiveOf(pack) ?: continue
            val path = DayPage.paths(day, lang).firstNotNullOfOrNull { archive.resolvePath(it) } ?: continue
            val page = archive.read(path)?.let { DayPage.parse(String(it.bytes, Charsets.UTF_8), lang) } ?: continue
            if (page.isEmpty) continue
            fun pick(lines: List<String>, n: Int, section: String) =
                lines.shuffled(random).take(n).map { Card(it, section, CardTarget.Article(pack.id, path, fragment = section)) }
            // The section names are the pack's Wikipedia's (the card's line and its anchor): data, not UI (Plan L3).
            val (events, births, deaths) = DayPage.sections(lang)
            return Drawn(pick(page.events, 3, events) + pick(page.births, 1, births) + pick(page.deaths, 1, deaths))
        }
        val (pack, lang) = candidates.first()
        val archive = archiveOf(pack) ?: return Drawn(emptyList(), mentions = true)
        return Drawn(
            archive.search(DayPage.title(day, lang), SEARCH_LIMIT).filterNot { IndexPages.isIndex(it.path) }.shuffled(random).take(CARDS)
                .map { articleCard(archive, pack, it.path) },
            mentions = true,
        )
    }

    /**
     * The encyclopedias On this day reads (Plan L3), each with the language its day article is in, in the order tried:
     * the packs in each library language in turn, then the rest in their own. A general one (Everything, Top) before a
     * subject.
     */
    private fun encyclopedias(packs: List<Pack>, langs: List<String>): List<Pair<Pack, String>> {
        val ordered = packs.sortedBy { !GENERAL.containsMatchIn(it.name) }
        val chosen = LinkedHashMap<Pack, String>()
        for (lang in langs) {
            ordered.filter { app.granger.core.catalog.Languages.has(it.language, lang) }.forEach { chosen.putIfAbsent(it, dayLanguage(lang)) }
        }
        ordered.forEach { chosen.putIfAbsent(it, dayLanguage(app.granger.core.catalog.Languages.codes(it.language).firstOrNull() ?: "en")) }
        return chosen.toList()
    }

    /** [lang] when its Wikipedia's day articles are read here, else English. */
    private fun dayLanguage(lang: String) = if (lang in DayPage.LANGUAGES) lang else "en"

    private suspend fun search(scope: Resolved, query: String, random: Random): List<Card> {
        val cards = ArrayList<Card>()
        if (scope.packs.size == 1 || (scope.packs.isNotEmpty() && scope.books.isEmpty())) {
            for (pack in scope.packs) {
                val archive = archiveOf(pack) ?: continue
                archive.search(query, SEARCH_LIMIT).filterNot { IndexPages.isIndex(it.path) }.mapTo(cards) {
                    Card(PageTitles.clean(it.title, pack.title), pack.title, CardTarget.Article(pack.id, it.path))
                }
            }
        } else if (scope.packs.isNotEmpty()) {
            // The whole library: every pack at once, through the library search.
            val packTitles = scope.packs.associate { it.id to it.title }
            librarySearch.fullText(query).mapTo(cards) {
                Card(it.title, packTitles[it.packId] ?: it.packTitle, CardTarget.Article(it.packId, it.path))
            }
        }
        val chosen = pickSearch(scope, cards, query, random)
        // Thumbnails for the five kept article hits only; reading thirty pages for five cards would be waste.
        val packsById = scope.packs.associateBy { it.id }
        return chosen.map { card ->
            val target = card.target as? CardTarget.Article ?: return@map card
            val pack = packsById[target.packId] ?: return@map card
            val archive = archiveOf(pack) ?: return@map card
            val html = archive.read(target.path)?.let { String(it.bytes, Charsets.UTF_8) } ?: return@map card
            card.copy(imagePath = thumbnail(archive, target.path, html))
        }
    }

    private suspend fun pickSearch(scope: Resolved, cards: ArrayList<Card>, query: String, random: Random): List<Card> {
        if (scope.books.isNotEmpty()) {
            val ids = scope.books.mapTo(HashSet()) { it.id }
            bookSearch(query).filter { it.bookId in ids }.mapTo(cards) { bookCard(it) }
        }
        scope.albums.filter { query in it.title || query in it.albumArtist }.mapTo(cards) { Card(it.title, it.albumArtist, CardTarget.Album(it.key)) }
        scope.audiobooks.filter { query in it.title || query in it.author }.mapTo(cards) { Card(it.title, it.author, CardTarget.Audiobook(it.id)) }
        return cards.shuffled(random).take(CARDS)
    }

    private fun bookCard(hit: BookHit) = Card(
        title = hit.bookTitle,
        subtitle = plain(hit.snippet).ifBlank { hit.author },
        target = CardTarget.Book(hit.bookId, hit.href, hit.progression),
    )

    /** The page's `<title>` without its site ([PageTitles]), else null. */
    private fun pageTitle(html: String, packTitle: String): String? {
        val doc = Jsoup.parse(html)
        return doc.title().ifBlank { doc.selectFirst("h1")?.text().orEmpty() }.trim().ifBlank { null }?.let { PageTitles.clean(it, packTitle) }
    }

    private fun pathTitle(path: String) = path.substringAfterLast('/').replace('_', ' ')

    private fun plain(html: String): String = if (html.isBlank()) "" else Jsoup.parse(html).text().trim()

    companion object {
        const val CARDS = 5
        private const val SEARCH_LIMIT = 30

        /** A whole encyclopedia's ZIM name, not a subject's: `wikipedia_es_all`, `wikipedia_en_top`. */
        private val GENERAL = Regex("^wikipedia_[a-z]{2,3}_(all|top|top1m|100)(_|$)")

        /** Subjects (lower-cased substrings) of books one dips into rather than reads through. */
        val DIP_IN = listOf("poetry", "poem", "scripture", "religion", "philosophy", "quotation", "aphorism", "essay", "proverb")

        /**
         * A feed's row title for [day]: "Random from Wikipedia", "October 22", "cats · Wikipedia"; "Mentions October 22"
         * when the day's cards are only [mentions] of it.
         */
        fun title(feed: HomeFeed, scope: Resolved, day: LocalDate, mentions: Boolean = false): UiText = when (feed.kind) {
            FeedKind.RANDOM ->
                if (scope.name == LIBRARY_NAME) UiText.Res(R.string.home_feed_random_from_library) else UiText.Res(R.string.home_feed_random_from, scope.name)
            // In the app's language; DayPage.title is the English page title it looks up.
            FeedKind.ON_THIS_DAY -> UiText.Res(
                if (mentions) R.string.home_feed_day_mentions else R.string.home_feed_day,
                day.month.getDisplayName(TextStyle.FULL, Locale.getDefault()), day.dayOfMonth,
            )
            FeedKind.SEARCH -> UiText.Res(R.string.home_feed_search, feed.query.trim(), scope.name)
        }

        /** The same day and feed draw the same cards; another day draws others. */
        fun seed(day: LocalDate, feedId: Long): Long = day.toEpochDay() xor (feedId * 0x9E3779B97F4A7C15uL.toLong())
    }
}
