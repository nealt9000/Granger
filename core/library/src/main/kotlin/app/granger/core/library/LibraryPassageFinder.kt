package app.granger.core.library

import app.granger.core.research.Keywords
import app.granger.core.research.LexicalScore
import app.granger.core.research.Passage
import app.granger.core.research.ResearchLanguage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first

/**
 * Android's Research passages: the shared [PackPassageFinder] (Plan I4b Task 9) over the book index, the shelf, the
 * shelves, the media index and the Wiktionary reader ([DictionaryEntry]).
 *
 * @param docs how many articles are read, overall; @param perPack how many hits each pack is asked for;
 * @param cards how many catalogue cards are kept; @param bookLimit how many chunks the general book search keeps;
 * @param mediaCards how many media cards are kept; @param searchParallelism how many packs are searched at once.
 */
class LibraryPassageFinder(
    library: Library,
    bookIndex: BookIndex,
    bookshelf: Bookshelf,
    shelves: Shelves,
    io: CoroutineDispatcher = Dispatchers.IO,
    docs: Int = 16,
    perPack: Int = 8,
    cards: Int = 3,
    bookLimit: Int = 40,
    media: MediaIndex? = null,
    mediaCards: Int = 2,
    searchParallelism: Int = 4,
    clock: () -> Long = { System.nanoTime() / 1_000_000 },
    /** The sets the packs are slices of, so a smaller slice's articles don't double a bigger one's (Plan WF Decision 6). */
    slices: PackSlices? = null,
) : PackPassageFinder(
    library = library,
    bookSearch = IndexSearch(bookIndex),
    books = { bookshelf.books.first() },
    collections = { shelves.collectionNames() },
    io = io,
    docs = docs,
    perPack = perPack,
    cards = cards,
    bookLimit = bookLimit,
    media = media?.let { index -> { words, languages -> mediaCardPassages(index, mediaCards, words, languages) } },
    searchParallelism = searchParallelism,
    clock = clock,
    slices = slices,
    dictionary = { archive, word -> DictionaryEntry.find(archive, word)?.let { DictionaryHit(it.path, it.title, it.text) } },
) {
    private class IndexSearch(private val index: BookIndex) : BookPassageSearch {
        override suspend fun passages(keywords: List<String>, limit: Int) = index.passages(keywords, limit = limit)
        override suspend fun passagesIn(bookId: String, keywords: List<String>) = index.passagesIn(bookId, keywords)
        override suspend fun quoted(query: String) = index.quoted(query)
    }
}

/** Works asked of the media index for each keyword, before the best [mediaCardPassages] are picked. */
private const val MEDIA_PER_KEYWORD = 6

/**
 * Albums and audiobooks that hold every word of some keyword as whole words (the cards' rule), best by the
 * keywords' lexical score, at most [mediaCards]. A keyword of several words that matches nothing is tried word by
 * word, stopwords left out: the rewrite's "money from" still finds the song *Money*.
 */
private suspend fun mediaCardPassages(index: MediaIndex, mediaCards: Int, words: List<String>, languages: List<ResearchLanguage>): List<Passage> {
    if (mediaCards <= 0) return emptyList()
    return try {
        val works = words.flatMap { keyword ->
            index.works(listOf(keyword), MEDIA_PER_KEYWORD).ifEmpty {
                val parts = Keywords.words(keyword)
                if (parts.size < 2) emptyList()
                else parts.filterNot { Keywords.isStopword(it, languages) }.flatMap { index.works(listOf(it), MEDIA_PER_KEYWORD) }
            }
        }.distinctBy { it.id }
        val score = LexicalScore.scorer(words, languages)
        // Stable: equal scores keep the index's order. Scored as whole works, so focusing a card on a song or
        // chapter that matched doesn't change which works are kept.
        works.map { it to score(MediaCards.passage(it)) }.sortedByDescending { it.second }.take(mediaCards)
            .map { (work, _) -> MediaCards.passage(work, MediaCards.bestPart(work, words, languages)) }
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        emptyList()
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
