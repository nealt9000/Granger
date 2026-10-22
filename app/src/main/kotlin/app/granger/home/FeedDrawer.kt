package app.granger.home

import app.granger.core.library.BookChunk
import app.granger.core.library.BookHit
import app.granger.core.library.FeedKind
import app.granger.core.library.HomeFeed
import app.granger.core.library.LibrarySearch
import app.granger.core.library.Pack
import app.granger.core.zim.ZimArchive
import java.time.LocalDate
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
) {
    /** The feed's row for the day, or null when it has nothing to show. */
    suspend fun draw(feed: HomeFeed, scope: Resolved, day: LocalDate): Drawn? {
        val random = Random(seed(day, feed.id))
        val drawn = when (feed.kind) {
            FeedKind.RANDOM -> Drawn("Random from ${scope.name}", random(scope, random))
            FeedKind.ON_THIS_DAY -> Drawn(DayPage.title(day), onThisDay(scope, day, random))
            FeedKind.SEARCH -> {
                val query = feed.query.trim()
                if (query.isEmpty()) return null
                Drawn("$query · ${scope.name}", search(scope, query, random))
            }
        }
        return drawn.takeIf { it.cards.isNotEmpty() }
    }

    private suspend fun random(scope: Resolved, random: Random): List<Card> = when {
        scope.books.isNotEmpty() -> scope.books.shuffled(random).take(CARDS).map { book ->
            val chunks = chunksOf(book.id)
            val chunk = chunks.takeIf { it.isNotEmpty() }?.let { it[random.nextInt(it.size)] }
            Card(
                title = book.title,
                subtitle = listOfNotNull(chunk?.let { "Chapter ${it.ordinal + 1}" }, book.author.ifBlank { null }).joinToString(" · "),
                target = CardTarget.Book(book.id, chunk?.href, chunk?.progression ?: 0.0),
            )
        }
        scope.packs.isNotEmpty() -> {
            val cards = LinkedHashMap<String, Card>()
            repeat(CARDS * 2) {
                if (cards.size >= CARDS) return@repeat
                val pack = scope.packs[random.nextInt(scope.packs.size)]
                val archive = archiveOf(pack) ?: return@repeat
                val path = archive.randomPath() ?: return@repeat
                val key = "${pack.id}/$path"
                if (key in cards) return@repeat
                cards[key] = Card(pageTitle(archive, path), pack.title, CardTarget.Article(pack.id, path))
            }
            cards.values.toList()
        }
        scope.albums.isNotEmpty() -> scope.albums.shuffled(random).take(CARDS).map {
            Card(it.title, it.albumArtist, CardTarget.Album(it.key))
        }
        else -> scope.audiobooks.shuffled(random).take(CARDS).map { Card(it.title, it.author, CardTarget.Audiobook(it.id)) }
    }

    private suspend fun onThisDay(scope: Resolved, day: LocalDate, random: Random): List<Card> {
        val title = DayPage.title(day)
        val pack = scope.packs.firstOrNull()
        if (pack == null) {
            // Books only: the date in the text.
            return bookSearch(title).shuffled(random).take(CARDS).map { bookCard(it) }
        }
        val archive = archiveOf(pack) ?: return emptyList()
        val path = DayPage.paths(day).firstNotNullOfOrNull { archive.resolvePath(it) }
        val page = path?.let { archive.read(it) }?.let { DayPage.parse(String(it.bytes, Charsets.UTF_8)) }
        if (path == null || page == null || page.isEmpty) {
            return archive.search(title, SEARCH_LIMIT).shuffled(random).take(CARDS).map {
                Card(it.title, pack.title, CardTarget.Article(pack.id, it.path))
            }
        }
        fun pick(lines: List<String>, n: Int, section: String) =
            lines.shuffled(random).take(n).map { Card(it, section, CardTarget.Article(pack.id, path, fragment = section)) }
        return pick(page.events, 3, "Events") + pick(page.births, 1, "Births") + pick(page.deaths, 1, "Deaths")
    }

    private suspend fun search(scope: Resolved, query: String, random: Random): List<Card> {
        val cards = ArrayList<Card>()
        if (scope.packs.size == 1 || (scope.packs.isNotEmpty() && scope.books.isEmpty())) {
            for (pack in scope.packs) {
                val archive = archiveOf(pack) ?: continue
                archive.search(query, SEARCH_LIMIT).mapTo(cards) { Card(it.title, pack.title, CardTarget.Article(pack.id, it.path)) }
            }
        } else if (scope.packs.isNotEmpty()) {
            // The whole library: every pack at once, through the library search.
            val packTitles = scope.packs.associate { it.id to it.title }
            librarySearch.fullText(query).mapTo(cards) {
                Card(it.title, packTitles[it.packId] ?: it.packTitle, CardTarget.Article(it.packId, it.path))
            }
        }
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

    /** The page's `<title>` (Wikipedia's suffix dropped), else its path with the underscores spaced. */
    private fun pageTitle(archive: ZimArchive, path: String): String {
        val item = archive.read(path) ?: return pathTitle(path)
        val html = String(item.bytes, Charsets.UTF_8)
        val doc = Jsoup.parse(html)
        val title = doc.title().ifBlank { doc.selectFirst("h1")?.text().orEmpty() }
            .replace(Regex("\\s+[-–—]\\s+Wikipedia$"), "").trim()
        return title.ifBlank { pathTitle(path) }
    }

    private fun pathTitle(path: String) = path.substringAfterLast('/').replace('_', ' ')

    private fun plain(html: String): String = if (html.isBlank()) "" else Jsoup.parse(html).text().trim()

    companion object {
        const val CARDS = 5
        private const val SEARCH_LIMIT = 30

        /** The same day and feed draw the same cards; another day draws others. */
        fun seed(day: LocalDate, feedId: Long): Long = day.toEpochDay() xor (feedId * 0x9E3779B97F4A7C15uL.toLong())
    }
}
