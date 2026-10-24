package app.granger.core.library

import app.granger.core.books.PdfText
import app.granger.core.research.HtmlPassages
import app.granger.core.research.Keywords
import app.granger.core.research.LexicalScore
import app.granger.core.research.Passage
import app.granger.core.research.PassageFinder
import app.granger.core.research.SourceKind
import app.granger.core.research.SourceMeta
import app.granger.core.zim.ZimArchive
import app.granger.core.zim.ZimHit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/**
 * Finds passages for Research in four places: full-text hits in ZIM articles, matching chunks of e-books, short
 * "catalogue cards" built from book and pack metadata (so "books by Darwin" has something to cite), and media cards for
 * albums and audiobooks ([MediaCards]) from [media].
 *
 * @param docs how many articles are read, overall; @param perPack how many hits each pack is asked for;
 * @param cards how many catalogue cards are kept; @param bookLimit how many chunks the general book search keeps;
 * @param mediaCards how many media cards are kept; @param searchParallelism how many packs are searched at once.
 *
 * A question that names a work ("does the bible say ...") also searches inside the named books, for the other keywords.
 */
class LibraryPassageFinder(
    private val library: Library,
    private val bookIndex: BookIndex,
    private val bookshelf: Bookshelf,
    private val shelves: Shelves,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val docs: Int = 8,
    private val perPack: Int = 8,
    private val cards: Int = 3,
    private val bookLimit: Int = 40,
    private val media: MediaIndex? = null,
    private val mediaCards: Int = 2,
    private val searchParallelism: Int = 4,
) : PassageFinder {
    override suspend fun find(keywords: List<String>): List<Passage> {
        val words = keywords.map { it.trim() }.filter { it.isNotEmpty() }
        if (words.isEmpty()) return emptyList()
        val collections = shelves.collectionNames()
        val packs = library.packs.first().filter { it.status == PackStatus.OK }
        val articles = withoutClassicsTwins(articles(words, packs, collections), packs)
        return articles + books(words, collections) + cards(words, packs, collections) + mediaCards(words)
    }

    /**
     * Granger Classics ships as a ZIM and as EPUBs. A work whose EPUB is installed is found through the book (and its
     * vectors), so the ZIM's pages for that work (`<slug>` and `<slug>/<page>`) are dropped rather than take a second slot.
     */
    private suspend fun withoutClassicsTwins(articles: List<Passage>, packs: List<Pack>): List<Passage> {
        val classics = packs.filter { it.name == CLASSICS_ZIM }.map { it.id }.toSet()
        if (classics.isEmpty() || articles.none { it.sourceId in classics }) return articles
        val installed = try {
            bookshelf.books.first().filter { it.status == PackStatus.OK }.map { it.id }.toSet()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return articles
        }
        return articles.filterNot { it.sourceId in classics && BookIds.of(BookOrigin.GRANGER, "gc-" + it.location.substringBefore('/')) in installed }
    }

    /**
     * Albums and audiobooks that hold every word of some keyword as whole words (the cards' rule), best by the
     * keywords' lexical score, at most [mediaCards]. A keyword of several words that matches nothing is tried word by
     * word, stopwords left out: the rewrite's "money from" still finds the song *Money*.
     */
    private suspend fun mediaCards(words: List<String>): List<Passage> {
        val index = media ?: return emptyList()
        if (mediaCards <= 0) return emptyList()
        return try {
            val works = words.flatMap { keyword ->
                index.works(listOf(keyword), MEDIA_PER_KEYWORD).ifEmpty {
                    val parts = Keywords.words(keyword)
                    if (parts.size < 2) emptyList()
                    else parts.filterNot(Keywords::isStopword).flatMap { index.works(listOf(it), MEDIA_PER_KEYWORD) }
                }
            }.distinctBy { it.id }
            val score = LexicalScore.scorer(words)
            // Stable: equal scores keep the index's order. Scored as whole works, so focusing a card on a song or
            // chapter that matched doesn't change which works are kept.
            works.map { it to score(MediaCards.passage(it)) }.sortedByDescending { it.second }.take(mediaCards)
                .map { (work, _) -> MediaCards.passage(work, MediaCards.bestPart(work, words)) }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            emptyList()
        }
    }

    /** Pack ids and book ids to the name of the collection they are filed in (a folder's child by its own name). */
    /** Each keyword is searched on its own (the full-text engine wants every word of a query); an article found twice keeps its best score. */
    private suspend fun articles(words: List<String>, packs: List<Pack>, collections: Map<String, String>): List<Passage> =
        withContext(io) {
            // Packs are searched [searchParallelism] at a time: one after another, search time grew with every pack added.
            val searching = io.limitedParallelism(searchParallelism)
            val hits = packs.filter { it.hasFulltextIndex }.map { pack ->
                async(searching) {
                    try {
                        val archive = library.archive(pack.id) ?: return@async emptyList()
                        words.flatMap { archive.search(it, perPack) }
                            .groupBy { it.path }.values.map { same -> Triple(pack, archive, same.maxBy { it.score }) }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        emptyList()
                    }
                }
            }.awaitAll().flatten()
            hits.sortedByDescending { it.third.score }.take(docs).flatMap { (pack, archive, hit) ->
                try {
                    articlePassages(pack, archive, hit, collections[pack.id].orEmpty())
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    emptyList()
                }
            }
        }

    private fun articlePassages(pack: Pack, archive: ZimArchive, hit: ZimHit, collection: String): List<Passage> {
        val item = archive.read(hit.path) ?: return emptyList()
        if (!item.mimeType.startsWith("text/html") && !item.mimeType.startsWith("application/xhtml")) return emptyList()
        val path = archive.resolvePath(hit.path) ?: hit.path
        return HtmlPassages.split(item.bytes.toString(Charsets.UTF_8)).map {
            Passage(
                kind = SourceKind.ARTICLE,
                sourceId = pack.id,
                sourceTitle = hit.title,
                packTitle = pack.title,
                location = path,
                anchor = it.anchor,
                heading = it.heading,
                text = it.text,
                meta = SourceMeta(date = pack.date, collection = collection),
                searchScore = hit.score.toDouble(),
            )
        }
    }

    private suspend fun books(words: List<String>, collections: Map<String, String>): List<Passage> =
        try {
            val named = try {
                namedBookPassages(words)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                emptyList()
            }
            val found = bookIndex.passages(words, limit = bookLimit) + named
            found.distinctBy { Triple(it.book.id, it.href, it.text) }.map {
                Passage(
                    kind = SourceKind.BOOK,
                    sourceId = it.book.id,
                    sourceTitle = it.book.title,
                    location = it.href,
                    progression = it.progression,
                    // A PDF's page is its source card's heading, so the citation says where it is.
                    heading = if (it.book.format == BookFormat.PDF) PdfText.pageOf(it.href)?.let { n -> "p. $n" }.orEmpty() else "",
                    text = it.text,
                    meta = SourceMeta(it.book.author, it.book.date, it.book.subjects, collections[it.book.id].orEmpty()),
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            emptyList()
        }

    /** For up to [NAMED_BOOKS] books the keywords name, a search inside the book for the keywords that don't. */
    private suspend fun namedBookPassages(words: List<String>): List<BookPassage> {
        val keywordWords = words.flatMap { Keywords.words(it) }.map { it.lowercase() }.filterNot(Keywords::isStopword).toSet()
        if (keywordWords.isEmpty()) return emptyList()
        val books = try {
            bookshelf.books.first().filter { it.status == PackStatus.OK && it.onShelf }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return emptyList()
        }
        val named = books.mapNotNull { book ->
            val titleWords = Keywords.words(book.title).map { it.lowercase() }.filter { it !in GENERIC_TITLE_WORDS && !Keywords.isStopword(it) }.toSet()
            val naming = keywordWords.filter { word -> Keywords.alternatives(word).any { it in titleWords } }.toSet()
            if (naming.isEmpty()) null else book to naming
        }.sortedByDescending { it.second.size }.take(NAMED_BOOKS)
        return named.flatMap { (book, naming) ->
            val rest = words.map { k -> Keywords.words(k).filter { it.lowercase() !in naming }.joinToString(" ") }.filter { it.isNotEmpty() }
            if (rest.isEmpty()) emptyList() else bookIndex.passagesIn(book.id, rest)
        }
    }

    private suspend fun cards(words: List<String>, packs: List<Pack>, collections: Map<String, String>): List<Passage> {
        val books = try {
            bookshelf.books.first().filter { it.status == PackStatus.OK && it.onShelf }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            emptyList()
        }
        // Regex matching over every book and pack: off the caller's (main) thread.
        return withContext(io) { matchCards(words, books, packs, collections) }
    }

    private fun matchCards(words: List<String>, books: List<Book>, packs: List<Pack>, collections: Map<String, String>): List<Passage> {
        val made = books.mapNotNull { book ->
            attempt {
                if (!mentions(words, book.title, book.author, book.subjects, book.description)) return@attempt null
                val text = lines(
                    "Title" to book.title, "Author" to book.author, "Translator" to book.translator,
                    "Published" to book.date, "Subjects" to book.subjects, "Collection" to collections[book.id].orEmpty(),
                    "In your library as" to if (book.format == BookFormat.PDF) "PDF" else "e-book",
                )
                Passage(
                    SourceKind.CARD, book.id, book.title, location = "", text = text,
                    meta = SourceMeta(book.author, book.date, book.subjects, collections[book.id].orEmpty()),
                )
            }
        } + packs.mapNotNull { pack ->
            attempt {
                if (!mentions(words, pack.title, pack.description)) return@attempt null
                val text = lines(
                    "Title" to pack.title, "Description" to pack.description, "Date" to pack.date,
                    "Collection" to collections[pack.id].orEmpty(), "In your library as" to "web pack",
                )
                Passage(SourceKind.CARD, pack.id, pack.title, packTitle = pack.title, text = text, meta = SourceMeta(date = pack.date, collection = collections[pack.id].orEmpty()))
            }
        }
        val score = LexicalScore.scorer(words)
        return made.map { it to score(it) }.sortedByDescending { it.second }.take(cards).map { it.first }
    }

    private inline fun <T> attempt(block: () -> T?): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }

    private fun lines(vararg fields: Pair<String, String>): String =
        fields.filter { it.second.isNotBlank() }.joinToString("\n") { (label, value) -> "$label: ${value.trim()}" }

    /** Whether every word of some keyword appears in the fields, as whole words. */
    private fun mentions(keywords: List<String>, vararg fields: String): Boolean =
        keywords.any { keyword ->
            val words = Keywords.words(keyword)
            words.isNotEmpty() && words.all { word ->
                val whole = Regex("(?<![\\p{L}\\p{N}])${Regex.escape(word)}(?![\\p{L}\\p{N}])", RegexOption.IGNORE_CASE)
                fields.any { whole.containsMatchIn(it) }
            }
        }

    private companion object {
        const val NAMED_BOOKS = 3

        /** The Granger Classics ZIM's `Name`. */
        const val CLASSICS_ZIM = "granger_classics_en"

        /** Works asked of the media index for each keyword, before the best [mediaCards] are picked. */
        const val MEDIA_PER_KEYWORD = 6

        /** Title words that don't name a work: "The Complete Works of X" is not named by "complete". */
        val GENERIC_TITLE_WORDS = setOf(
            "book", "books", "complete", "works", "volume", "vol", "collected", "selected", "edition",
            "translated", "tr", "english", "version", "standard",
        )
    }
}

/** Each pack's and book's collection name, by id; empty when the shelves can't be read. */
internal suspend fun Shelves.collectionNames(): Map<String, String> = try {
    buildMap {
        for (row in shelves.first().flatMap { it.allCollections }) {
            row.packs.forEach { put(it.id, row.collection.name) }
            row.books.forEach { put(it.id, row.collection.name) }
        }
    }
} catch (e: CancellationException) {
    throw e
} catch (_: Exception) {
    emptyMap()
}
