package app.granger.core.library

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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/**
 * Finds passages for Research in three places: full-text hits in ZIM articles, matching chunks of e-books, and short
 * "catalogue cards" built from book and pack metadata (so "books by Darwin" has something to cite).
 *
 * @param docs how many articles are read, overall; @param perPack how many hits each pack is asked for;
 * @param cards how many catalogue cards are kept.
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
) : PassageFinder {
    override suspend fun find(keywords: List<String>): List<Passage> {
        val words = keywords.map { it.trim() }.filter { it.isNotEmpty() }
        if (words.isEmpty()) return emptyList()
        val collections = collectionNames()
        val packs = library.packs.first().filter { it.status == PackStatus.OK }
        return articles(words, packs, collections) + books(words, collections) + cards(words, packs, collections)
    }

    /** Pack ids and book ids to the name of the collection they are filed in. */
    private suspend fun collectionNames(): Map<String, String> = try {
        buildMap {
            for (row in shelves.shelves.first().flatMap { it.collections }) {
                row.packs.forEach { put(it.id, row.collection.name) }
                row.books.forEach { put(it.id, row.collection.name) }
            }
        }
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        emptyMap()
    }

    /** Each keyword is searched on its own (the full-text engine wants every word of a query); an article found twice keeps its best score. */
    private suspend fun articles(words: List<String>, packs: List<Pack>, collections: Map<String, String>): List<Passage> =
        withContext(io) {
            val hits = packs.filter { it.hasFulltextIndex }.flatMap { pack ->
                try {
                    val archive = library.archive(pack.id) ?: return@flatMap emptyList()
                    words.flatMap { archive.search(it, perPack) }
                        .groupBy { it.path }.values.map { same -> Triple(pack, archive, same.maxBy { it.score }) }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    emptyList()
                }
            }
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
            bookIndex.passages(words).map {
                Passage(
                    kind = SourceKind.BOOK,
                    sourceId = it.book.id,
                    sourceTitle = it.book.title,
                    location = it.href,
                    progression = it.progression,
                    text = it.text,
                    meta = SourceMeta(it.book.author, it.book.date, it.book.subjects, collections[it.book.id].orEmpty()),
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            emptyList()
        }

    private suspend fun cards(words: List<String>, packs: List<Pack>, collections: Map<String, String>): List<Passage> {
        val books = try {
            bookshelf.books.first().filter { it.status == PackStatus.OK }
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
                    "In your library as" to "e-book",
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
}
