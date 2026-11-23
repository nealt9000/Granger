package app.granger.core.library

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first

/**
 * Android's Research passages: the shared [PackPassageFinder] (Plan I4b Task 9) over the book index, the shelf, the
 * shelves, the media index ([mediaCardPassages]) and the Wiktionary reader ([DictionaryEntry]), all three shared with the
 * iPhone since Plan I7 Task 7b.
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
    bookSearch = BookIndexPassageSearch(bookIndex),
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
)
