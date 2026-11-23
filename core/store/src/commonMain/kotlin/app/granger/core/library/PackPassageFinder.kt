package app.granger.core.library

import app.granger.core.research.HtmlPassages
import app.granger.core.research.Keywords
import app.granger.core.research.LexicalScore
import app.granger.core.research.Passage
import app.granger.core.research.PassageFinder
import app.granger.core.research.ResearchLanguage
import app.granger.core.research.SourceKind
import app.granger.core.research.SourceMeta
import app.granger.core.text.Spellings
import app.granger.core.zim.ZimArchive
import app.granger.core.zim.ZimHit
import kotlin.concurrent.Volatile
import kotlin.time.TimeSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/**
 * One [PackPassageFinder.find]'s time in milliseconds (Plan R5 Task 1): the full-text search of every pack, reading
 * and splitting the chosen articles, the book search, the catalogue cards and the media cards; and each pack's own
 * search time by pack name (packs run [PackPassageFinder]'s `searchParallelism` at a time, so these overlap).
 */
data class KeywordTimings(
    val fulltextMs: Long, val readMs: Long, val booksMs: Long, val cardsMs: Long, val mediaMs: Long,
    val packMs: Map<String, Long>,
)

/** A book chunk that matches some of the words asked about, with its text. */
data class BookPassage(val book: Book, val href: String, val progression: Double, val text: String)

/** The search inside books [PackPassageFinder] asks (Android: `BookIndex`). */
interface BookPassageSearch {
    /** The best [limit] chunks for the keywords, over every book. */
    suspend fun passages(keywords: List<String>, limit: Int): List<BookPassage>

    /** The best chunks for the keywords within one book. */
    suspend fun passagesIn(bookId: String, keywords: List<String>): List<BookPassage>

    /** The chunks holding [query] as a quote; empty when it isn't one. */
    suspend fun quoted(query: String): List<BookPassage>
}

/** A word's entry in a Wiktionary pack, as one passage's text. */
data class DictionaryHit(val path: String, val title: String, val text: String)

/** Milliseconds on a monotonic clock, from when it was made. */
internal fun monotonicMillis(): () -> Long {
    val start = TimeSource.Monotonic.markNow()
    return { start.elapsedNow().inWholeMilliseconds }
}

/** A PDF chunk's page number from its href (`page/12`), as Android's `PdfText.pageOf` reads it. */
private fun pdfPageOf(href: String): Int? = href.removePrefix("page/").takeIf { it != href }?.toIntOrNull()

/**
 * Finds passages for Research in four places: full-text hits in ZIM articles, matching chunks of e-books, short
 * "catalogue cards" built from book and pack metadata (so "books by Darwin" has something to cite), and media cards for
 * albums and audiobooks from [media].
 *
 * @param docs how many articles are read, overall; @param perPack how many hits each pack is asked for;
 * @param cards how many catalogue cards are kept; @param bookLimit how many chunks the general book search keeps;
 * @param searchParallelism how many packs are searched at once.
 *
 * A question that names a work ("does the bible say ...") also searches inside the named books, for the other keywords.
 *
 * Shared by both phones (Plan I4b Task 9): Android's `LibraryPassageFinder` builds it over its book index, shelf,
 * shelves, media index and Wiktionary reader; the iPhone over its packs and its book index ([BookIndexPassageSearch],
 * Plan I7 Task 7a; no [media] or [dictionary] yet), which is Best matches' keyword side there.
 *
 * @param books the shelf's books (Android: `Bookshelf.books`), for named works, catalogue cards and Classics twins;
 * @param collections each pack's and book's collection name by id (Android: `Shelves.collectionNames`);
 * @param media the media cards for the keywords, at most as many as are wanted, or null for none;
 * @param dictionary a word's entry in one Wiktionary pack, or null when there's no reader for them.
 */
open class PackPassageFinder(
    private val library: Library,
    private val bookSearch: BookPassageSearch? = null,
    private val books: suspend () -> List<Book> = { emptyList() },
    private val collections: suspend () -> Map<String, String> = { emptyMap() },
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val docs: Int = 16,
    private val perPack: Int = 8,
    private val cards: Int = 3,
    private val bookLimit: Int = 40,
    private val media: (suspend (keywords: List<String>, languages: List<ResearchLanguage>) -> List<Passage>)? = null,
    private val searchParallelism: Int = 4,
    private val clock: () -> Long = monotonicMillis(),
    /** The sets the packs are slices of, so a smaller slice's articles don't double a bigger one's (Plan WF Decision 6). */
    private val slices: PackSlices? = null,
    private val dictionary: ((ZimArchive, String) -> DictionaryHit?)? = null,
) : PassageFinder {
    /** The last [find]'s timings (Plan R5 Task 1); null before the first, or when it had no keywords. */
    @Volatile var lastTimings: KeywordTimings? = null
        private set

    private val packMs = HashMap<String, Long>()
    private val packMsLock = TryLock()
    @Volatile private var fulltextMs = 0L

    override suspend fun find(keywords: List<String>): List<Passage> = find(keywords, Keywords.ENGLISH)

    /** [languages] are the question's: whose stopwords the book, card and media matching leave out (English by default). */
    override suspend fun find(keywords: List<String>, languages: List<ResearchLanguage>): List<Passage> {
        lastTimings = null
        val words = keywords.map { it.trim() }.filter { it.isNotEmpty() }
        if (words.isEmpty()) return emptyList()
        withPackMs { clear() }
        val collections = collections()
        val packs = library.packs.first().filter { it.status == PackStatus.OK }
        // The four searches don't depend on each other: books, cards and media run alongside the packs' full-text search.
        return coroutineScope {
            val articles = async {
                timed {
                    val found = articles(words, packs, collections)
                    SliceDedupe.apply(found, packs, slices, library::archive, { p -> p.sourceId.takeIf { p.kind == SourceKind.ARTICLE } }, { it.location })
                }
            }
            val books = async { timed { books(words, collections, languages) } }
            val cards = async { timed { cards(words, packs, collections, languages) } }
            val media = async { timed { mediaCards(words, languages) } }
            val (a, b, c, m) = awaitAll(articles, books, cards, media)
            lastTimings = KeywordTimings(fulltextMs, a.second - fulltextMs, b.second, c.second, m.second, withPackMs { toMap() })
            a.first + b.first + c.first + m.first
        }
    }

    /** The first installed Wiktionary with [word] ([DictionaryEntry]); one in the question's main language is tried first. */
    override suspend fun entry(word: String, languages: List<ResearchLanguage>): Passage? = withContext(io) {
        val lookUp = dictionary ?: return@withContext null
        val collections = collections()
        val dictionaries = library.packs.first()
            .filter { it.status == PackStatus.OK && it.name.startsWith(DICTIONARY_PREFIX) }
            .sortedBy { if (ResearchLanguage.ofCode(it.language) == languages.firstOrNull()) 0 else 1 }
        for (pack in dictionaries) {
            val found = try {
                library.archive(pack.id)?.let { lookUp(it, word) }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            } ?: continue
            return@withContext Passage(
                kind = SourceKind.ARTICLE,
                sourceId = pack.id,
                sourceTitle = found.title,
                packTitle = pack.title,
                location = found.path,
                text = found.text,
                meta = SourceMeta(date = pack.date, collection = collections[pack.id].orEmpty()),
                language = ResearchLanguage.ofCode(pack.language),
            )
        }
        null
    }

    private inline fun <T> withPackMs(block: HashMap<String, Long>.() -> T): T {
        packMsLock.lock()
        try { return packMs.block() } finally { packMsLock.unlock() }
    }

    private suspend fun timed(block: suspend () -> List<Passage>): Pair<List<Passage>, Long> {
        val start = clock()
        return block().let { it to clock() - start }
    }

    /**
     * Granger Classics ships as a ZIM and as EPUBs. A work whose EPUB is installed is found through the book (and its
     * vectors), so the ZIM's pages for that work (`<slug>` and `<slug>/<page>`) are skipped rather than take a place.
     */
    private suspend fun classicsTwins(packs: List<Pack>): (Pack, String) -> Boolean {
        val classics = packs.filter { it.name == CLASSICS_ZIM }.map { it.id }.toSet()
        if (classics.isEmpty()) return { _, _ -> false }
        val installed = try {
            books().filter { it.status == PackStatus.OK }.map { it.id }.toSet()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return { _, _ -> false }
        }
        return { pack, path ->
            pack.id in classics && BookIds.of(BookOrigin.GRANGER, "gc-" + path.removePrefix("A/").substringBefore('/')) in installed
        }
    }

    /** [media]'s cards for the keywords; none without it, or on any failure. */
    private suspend fun mediaCards(words: List<String>, languages: List<ResearchLanguage>): List<Passage> {
        val cards = media ?: return emptyList()
        return try {
            cards(words, languages)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            emptyList()
        }
    }

    /** Pack ids and book ids to the name of the collection they are filed in (a folder's child by its own name). */
    /** Each keyword is searched on its own (the full-text engine wants every word of a query); an article found twice keeps its best score. */
    /** One article a pack's search found: by how many of the keywords, its best place among their hits, its best score. */
    private class ArticleHit(val pack: Pack, val archive: ZimArchive, val hit: ZimHit, val order: Int, val keywords: Int, val rank: Int)

    /**
     * The [docs] articles to read, of every pack's top [perPack] hits for each keyword. Kiwix's score is a percentage
     * and the top hits tie at 100 (R2: 154 of 159 tuning questions read eight 100s), so on the score alone the packs'
     * order decided ("the bible on alcohol" read Appropedia and Based Cooking first). Instead: an article whose title
     * holds a whole keyword first, then the one more keywords found, then its best place in a keyword's hits, then the
     * score and the packs' order (`tools/eval/experiments/article_cut.py`: the answer's article read for 46 of 95
     * tuning questions at 8, 54 at 16, against 36). Classics ZIM pages of works installed as books are dropped before
     * the cut, not after, so they don't take a place.
     */
    private suspend fun articles(words: List<String>, packs: List<Pack>, collections: Map<String, String>): List<Passage> =
        withContext(io) {
            // Packs are searched [searchParallelism] at a time: one after another, search time grew with every pack added.
            val searching = io.limitedParallelism(searchParallelism)
            val searchStart = clock()
            val twins = classicsTwins(packs)
            val hits = packs.filter { it.hasFulltextIndex }.mapIndexed { order, pack ->
                async(searching) {
                    try {
                        val packStart = clock()
                        val archive = library.archive(pack.id) ?: return@async emptyList()
                        // Research never shows a snippet, and libzim builds each one from the article.
                        // Each spelling of a keyword ("grey horse", "gray horse") is searched; its hits count as that keyword's.
                        words.flatMapIndexed { k, word ->
                            Spellings.forms(word).flatMap { form -> archive.searchWithoutSnippets(form, perPack).mapIndexed { rank, hit -> Triple(k, rank, hit) } }
                        }
                            .groupBy { it.third.path }.values
                            .filterNot { same -> twins(pack, same.first().third.path) }
                            .map { same ->
                                ArticleHit(
                                    pack, archive, same.maxBy { it.third.score }.third, order,
                                    keywords = same.map { it.first }.distinct().size, rank = same.minOf { it.second },
                                )
                            }
                            .also { val ms = clock() - packStart; withPackMs { put(pack.name, ms) } }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        emptyList()
                    }
                }
            }.awaitAll().flatten()
            fulltextMs = clock() - searchStart
            val titled = titleMatcher(words)
            hits.sortedWith(
                compareByDescending<ArticleHit> { titled(it.hit.title) }
                    .thenByDescending { it.keywords }
                    .thenBy { it.rank }
                    .thenByDescending { it.hit.score }
                    .thenBy { it.order },
            ).take(docs).flatMap { h ->
                try {
                    articlePassages(h.pack, h.archive, h.hit, collections[h.pack.id].orEmpty())
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    emptyList()
                }
            }
        }

    /** Whether a title holds every word of one of [words] (stopwords aside): "The King James Bible" for "bible". */
    private fun titleMatcher(words: List<String>): (String) -> Boolean {
        val keywords = words.map { k -> Keywords.words(k).map { it.lowercase() }.filterNot { Keywords.isStopword(it) }.toSet() }.filter { it.isNotEmpty() }
        return { title ->
            val t = Keywords.words(title).map { it.lowercase() }.toSet()
            keywords.any { t.containsAll(it) }
        }
    }

    private fun articlePassages(pack: Pack, archive: ZimArchive, hit: ZimHit, collection: String): List<Passage> {
        val item = archive.read(hit.path) ?: return emptyList()
        if (!item.mimeType.startsWith("text/html") && !item.mimeType.startsWith("application/xhtml")) return emptyList()
        val path = archive.resolvePath(hit.path) ?: hit.path
        return HtmlPassages.split(item.bytes.decodeToString()).map {
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
                language = ResearchLanguage.ofCode(pack.language),
            )
        }
    }

    private suspend fun books(words: List<String>, collections: Map<String, String>, languages: List<ResearchLanguage>): List<Passage> =
        try {
            val named = try {
                namedBookPassages(words, languages)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                emptyList()
            }
            val found = (bookSearch?.passages(words, bookLimit) ?: emptyList()) + named
            found.distinctBy { Triple(it.book.id, it.href, it.text) }.map { bookPassage(it, collections) }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            emptyList()
        }

    /**
     * Plan SQ Step 4: the book chunks holding [query] as a quote ([BookIndex.quoted]), for Best matches to put first;
     * empty when it isn't one, or on any failure.
     */
    suspend fun quoted(query: String): List<Passage> =
        try {
            val found = bookSearch?.quoted(query).orEmpty()
            if (found.isEmpty()) emptyList() else collections().let { c -> found.map { bookPassage(it, c) } }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            emptyList()
        }

    private fun bookPassage(it: BookPassage, collections: Map<String, String>) = Passage(
        kind = SourceKind.BOOK,
        sourceId = it.book.id,
        sourceTitle = it.book.title,
        location = it.href,
        progression = it.progression,
        // A PDF's page is its source card's heading, so the citation says where it is.
        heading = if (it.book.format == BookFormat.PDF) pdfPageOf(it.href)?.let { n -> "p. $n" }.orEmpty() else "",
        text = it.text,
        meta = SourceMeta(it.book.author, it.book.date, it.book.subjects, collections[it.book.id].orEmpty()),
        language = ResearchLanguage.ofCode(it.book.language),
    )

    /**
     * For up to [NAMED_BOOKS] books the keywords name, a search inside the book for the keywords that don't. For a
     * Spanish question, keyword and title words meet with accented vowels folded, and Spanish generic title words
     * ([GENERIC_TITLE_WORDS_ES]) don't name a book either.
     */
    private suspend fun namedBookPassages(words: List<String>, languages: List<ResearchLanguage>): List<BookPassage> {
        val fold = ResearchLanguage.foldsVowels(languages)
        val folded: (String) -> String = if (fold) ResearchLanguage::foldVowels else { w -> w }
        val generic = if (fold) GENERIC_TITLE_WORDS + GENERIC_TITLE_WORDS_ES else GENERIC_TITLE_WORDS
        val keywordWords = words.flatMap { Keywords.words(it) }.map { it.lowercase() }.filterNot { Keywords.isStopword(it, languages) }.map(folded).toSet()
        if (keywordWords.isEmpty()) return emptyList()
        val books = try {
            books().filter { it.status == PackStatus.OK && it.onShelf }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return emptyList()
        }
        val named = books.mapNotNull { book ->
            val titleWords = Keywords.words(book.title).map { it.lowercase() }.filter { !Keywords.isStopword(it, languages) }.map(folded).filter { it !in generic }.toSet()
            val naming = keywordWords.filter { word -> Keywords.alternatives(word).any { it in titleWords } }.toSet()
            if (naming.isEmpty()) null else book to naming
        }.sortedByDescending { it.second.size }.take(NAMED_BOOKS)
        return named.flatMap { (book, naming) ->
            val rest = words.map { k -> Keywords.words(k).filter { folded(it.lowercase()) !in naming }.joinToString(" ") }.filter { it.isNotEmpty() }
            if (rest.isEmpty()) emptyList() else bookSearch?.passagesIn(book.id, rest).orEmpty()
        }
    }

    private suspend fun cards(words: List<String>, packs: List<Pack>, collections: Map<String, String>, languages: List<ResearchLanguage>): List<Passage> {
        val books = try {
            books().filter { it.status == PackStatus.OK && it.onShelf }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            emptyList()
        }
        // Regex matching over every book and pack: off the caller's (main) thread.
        return withContext(io) { matchCards(words, books, packs, collections, languages) }
    }

    private fun matchCards(words: List<String>, books: List<Book>, packs: List<Pack>, collections: Map<String, String>, languages: List<ResearchLanguage>): List<Passage> {
        val mentions = mentions(words, languages)
        val made = books.mapNotNull { book ->
            attempt {
                if (!mentions(arrayOf(book.title, book.author, book.subjects, book.description))) return@attempt null
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
                if (!mentions(arrayOf(pack.title, pack.description))) return@attempt null
                val text = lines(
                    "Title" to pack.title, "Description" to pack.description, "Date" to pack.date,
                    "Collection" to collections[pack.id].orEmpty(), "In your library as" to "web pack",
                )
                Passage(SourceKind.CARD, pack.id, pack.title, packTitle = pack.title, text = text, meta = SourceMeta(date = pack.date, collection = collections[pack.id].orEmpty()))
            }
        }
        val score = LexicalScore.scorer(words, languages)
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

    /**
     * Whether every word of some keyword appears in the fields, as whole words. The patterns are compiled once per
     * search, not once per book and field. For a Spanish question ([ResearchLanguage.foldsVowels]) both sides have
     * their accented vowels folded, each field once per book.
     */
    private fun mentions(keywords: List<String>, languages: List<ResearchLanguage>): (Array<out String>) -> Boolean {
        val fold = ResearchLanguage.foldsVowels(languages)
        val patterns = keywords.map { keyword ->
            Keywords.words(keyword).map { word ->
                val w = if (fold) ResearchLanguage.foldVowels(word) else word
                Regex("(?<![\\p{L}\\p{N}])${Regex.escape(w)}(?![\\p{L}\\p{N}])", RegexOption.IGNORE_CASE)
            }
        }.filter { it.isNotEmpty() }
        if (!fold) return { fields -> patterns.any { words -> words.all { whole -> fields.any { whole.containsMatchIn(it) } } } }
        return { fields ->
            val texts = fields.map(ResearchLanguage::foldVowels)
            patterns.any { words -> words.all { whole -> texts.any { whole.containsMatchIn(it) } } }
        }
    }

    companion object {
        /** A Wiktionary pack's `Name` starts with this ([dictionary] reads its entries). */
        const val DICTIONARY_PREFIX = "wiktionary_"

        private const val NAMED_BOOKS = 3

        /** The Granger Classics ZIM's `Name`. */
        private const val CLASSICS_ZIM = "granger_classics_en"

        /** Title words that don't name a work: "The Complete Works of X" is not named by "complete". */
        private val GENERIC_TITLE_WORDS = setOf(
            "book", "books", "complete", "works", "volume", "vol", "collected", "selected", "edition",
            "translated", "tr", "english", "version", "standard",
        )

        /**
         * [GENERIC_TITLE_WORDS]' Spanish counterparts, vowels folded ([ResearchLanguage.foldVowels]), for a Spanish
         * question: "Obras completas de X" is not named by "obras". The kinds of book (antología, poesías, cuentos,
         * novelas) are here too, as Spanish collections are titled by them.
         */
        private val GENERIC_TITLE_WORDS_ES = setOf(
            "libro", "libros", "obra", "obras", "completa", "completas", "completo", "completos", "tomo", "tomos",
            "volumen", "volumenes", "reunidas", "coleccion", "selectas", "escogidas", "seleccion", "edicion", "traducido",
            "traducida", "traduccion", "trad", "espanol", "castellano", "version", "antologia", "poesias", "cuentos",
            "novelas",
        )
    }
}

/** The book index as [PackPassageFinder]'s book search (Android's `LibraryPassageFinder` until Plan I7 Task 7a). */
class BookIndexPassageSearch(private val index: BookIndex) : BookPassageSearch {
    override suspend fun passages(keywords: List<String>, limit: Int) = index.passages(keywords, limit = limit)
    override suspend fun passagesIn(bookId: String, keywords: List<String>) = index.passagesIn(bookId, keywords)
    override suspend fun quoted(query: String) = index.quoted(query)
}

/** Each pack's and book's collection name, by id; empty when the shelves can't be read. */
suspend fun Shelves.collectionNames(): Map<String, String> = try {
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
