package app.granger.core.library

import app.granger.core.research.Keywords
import app.granger.core.research.ResearchLanguage
import app.granger.core.text.Spellings
import app.granger.core.zim.ZimArchive
import app.granger.core.zim.ZimHit
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/**
 * One search result. [snippet] is HTML with `<b>` highlights, set only for full-text results. A book in Titles has its
 * id as [packId], an empty [path], its collection as [packTitle] and its [author].
 */
data class SearchHit(
    val packId: String,
    val packTitle: String,
    val path: String,
    val title: String,
    val snippet: String? = null,
    val author: String = "",
    /** The same title from other packs or the shelf, listed under this one's row (Plan SQ: one row per title). */
    val also: List<SearchHit> = emptyList(),
)

/**
 * Title hits, and [more] when the search stopped short, so more titles may match than are listed. The first [ranked] hits
 * match the query exactly or begin with it; [LibrarySearch.nearTitles] go after those, before the rest.
 */
data class TitleResults(val hits: List<SearchHit>, val more: Boolean = false, val ranked: Int = 0) {
    /** [near] titles placed after the ranked ones, one row per article. */
    fun withNear(near: List<SearchHit>): List<SearchHit> =
        if (near.isEmpty()) hits else (hits.take(ranked) + near + hits.drop(ranked)).distinctBy { it.packId to it.path }
}

/** Searches every OK pack. Plan 5 replaces the implementation with `:core:search` sources. */
interface LibrarySearch {
    suspend fun titles(query: String): TitleResults

    /** Opens what the first [titles] would (each pack and its title index) before anything is typed; nothing by default. */
    suspend fun warm() {}
    suspend fun fullText(query: String): List<SearchHit>

    /**
     * Titles a slip or two from [query] (Plan SQ: typos), for when none of [shown] matches it exactly. Slower than
     * [titles], so Search asks for them beside the other groups rather than on each keystroke's first answer.
     */
    suspend fun nearTitles(query: String, shown: List<SearchHit>): List<SearchHit> = emptyList()

    /** Full-text hits inside books; empty where books aren't searchable. */
    suspend fun books(query: String): List<BookHit> = emptyList()

    /**
     * The titles of OK packs with no full-text index, so only their titles are searched ("bowline" finds nothing in
     * A Library of Knots; the fresh-install walkthrough, 2026-11-02). Maps are left out: nobody searches a map's text.
     */
    suspend fun titleOnly(): List<String> = emptyList()
}

class ZimLibrarySearch(
    private val library: Library,
    private val bookIndex: BookIndex? = null,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val titlesPerPack: Int = TITLES_PER_PACK,
    private val fullTextLimit: Int = FULL_TEXT_LIMIT,
    private val titleLimit: Int = TITLE_LIMIT,
    /** The books Titles lists by title or author; none when null. */
    private val bookshelf: Bookshelf? = null,
    /** The sets the packs are slices of, so a smaller slice's hits don't double a bigger one's (Plan WF Decision 6). */
    private val slices: PackSlices? = null,
) : LibrarySearch {
    // The packs take turns, each in its own order, so a big pack can't push the others below the first few or past the limit.
    // Then a title that is the query comes first, and one that starts with it next (Plan U3b Decision 10). Books on the
    // shelf go ahead of pack titles of the same rank, and a book found by its author after any title containing the
    // query (Plan U2 Decision 1).
    // Plan SQ Task 4.3: a title matches on folded words, on what was typed or the article it leads to ("heart attack"
    // finds Myocardial infarction, shown under its own name); within a rank, dictionaries and translated copies come
    // after the rest, and packs in another language than a plainly English or Spanish query's ([TitleMatch.prior]).
    // Titles a slip away are [nearTitles], asked for separately: they cost too much for every keystroke.
    override suspend fun titles(query: String): TitleResults {
        if (query.isBlank()) return TitleResults(emptyList())
        val books = bookTitles(query)
        val packs = across(query, suggesting = true) { it.suggest(query, titlesPerPack) }
        val q = TitleMatch.key(query)
        val ranked = ranked(packs, q, queryLanguage(query))
        val unranked = BookTitleSearch.Rank.entries.size
        // Stable: books before packs within a rank, then by prior, and pack titles that are neither last, in turn order.
        val family = families()
        val first = (books.map { Triple(it.first, 0, it.second) } + ranked.filter { it.first != null })
            .sortedWith(compareBy<Triple<BookTitleSearch.Rank?, Int, SearchHit>> { it.first?.ordinal ?: unranked }.thenBy { it.second })
            .map { it.third }.distinctBy { family(it) to it.path }
        val rest = ranked.filter { it.first == null }.sortedBy { it.second }.map { it.third }
        val merged = (first + rest).distinctBy { family(it) to it.path }
        // One row per title: "Turingia" in Wikipedia and Wikivoyage, or a book on the shelf and in Classics, is one row
        // led by the better ranked, the others under it.
        val rows = TitleMatch.oneRowPerTitle(merged)
        val firstKeys = first.mapTo(HashSet()) { it.packId to it.path }
        return TitleResults(
            rows.take(titleLimit),
            more = rows.size > titleLimit || packs.any { it.returned >= titlesPerPack },
            ranked = minOf(rows.count { (it.packId to it.path) in firstKeys }, titleLimit),
        )
    }

    // Plan SQ, the Pixel 6: the first title search after launch took 1.4 s against ~0.1 s after, opening every pack and its
    // suggester. A one-letter query asks each pack for one title (dictionaries skip it: [TitleMatch.skipsSuggest]).
    override suspend fun warm() {
        across("e", suggesting = true) { it.suggest("e", 1) }
    }

    override suspend fun nearTitles(query: String, shown: List<SearchHit>): List<SearchHit> {
        val q = TitleMatch.key(query)
        if (q.isEmpty() || shown.any { TitleMatch.rank(it.title, q) == BookTitleSearch.Rank.EXACT }) return emptyList()
        // A name is a few words; a longer query is a question, which Best matches answer (the probes took 5 s at p95 on
        // the dev questions, for nothing).
        if (q.split(' ').count { it.length > 1 } > NEAR_MAX_WORDS) return emptyList()
        val family = families()
        return fuzzy(query, q, shown.map { it.packId to it.path }.toSet()).distinctBy { family(it) to it.path }
    }

    /** The query's language when it is plainly one ([ResearchLanguage.of] gives it alone), else null. */
    private fun queryLanguage(query: String): ResearchLanguage? = ResearchLanguage.of(query).singleOrNull()

    /** Each pack's hits taken in turns, each with its rank against [q] and its place among the same rank (lower first). */
    private fun ranked(packs: List<PackHits>, q: String, language: ResearchLanguage?): List<Triple<BookTitleSearch.Rank?, Int, SearchHit>> {
        val turns = packs.maxOfOrNull { it.hits.size } ?: 0
        return (0 until turns).flatMap { turn ->
            packs.mapNotNull { pack ->
                pack.hits.getOrNull(turn)?.let { (zim, hit) ->
                    // The better of what was typed (a redirect's own title) and the article's; at the same rank an article
                    // matched under its own title goes first ("Turingia" before English Wikipedia's redirect to Thuringia).
                    val own = TitleMatch.rank(hit.title, q)
                    val rank = listOfNotNull(TitleMatch.rank(zim.title, q), own).minOrNull()
                    val viaRedirect = if (rank != null && own != rank) 1 else 0
                    val otherLanguage = if (language != null && !TitleMatch.inLanguage(pack.language, language.tag)) 1 else 0
                    Triple(rank, 4 * TitleMatch.prior(pack.name, hit.path, hit.title) + 2 * otherLanguage + viaRedirect, hit)
                }
            }
        }
    }

    /**
     * Titles one or two slips from [query] ([TitleMatch.budget]), nearest first: each pack is asked for the
     * [TitleMatch.probes], and a title is kept when its folded words are within the budget of the query's (or its
     * start is, for a longer title). Ones already listed ([seen]) are left out.
     */
    private suspend fun fuzzy(query: String, q: String, seen: Set<Pair<String, String>>): List<SearchHit> {
        val budget = TitleMatch.budget(q)
        if (budget == 0) return emptyList()
        val found = TitleMatch.probes(query).flatMap { probe -> across(probe, suggesting = true) { it.suggest(probe, FUZZY_PER_PACK) }.flatMap { it.hits } }
        return found.mapNotNull { (zim, hit) ->
            if ((hit.packId to hit.path) in seen) return@mapNotNull null
            val d = listOf(zim.title, hit.title).minOf { title ->
                val t = TitleMatch.key(title)
                minOf(TitleMatch.distance(t, q, budget), TitleMatch.distance(t.take(q.length), q, budget) + 1)
            }
            if (d <= budget) d to hit else null
        }.sortedBy { it.first }.map { it.second }.distinctBy { it.packId to it.path }
    }

    /** The shelf's books matching [query] by title or author, as title hits that open the book. */
    private suspend fun bookTitles(query: String): List<Pair<BookTitleSearch.Rank?, SearchHit>> {
        val shelf = bookshelf?.books?.first() ?: return emptyList()
        return BookTitleSearch.search(shelf, query).map { (rank, book) ->
            rank to SearchHit(book.id, Filing.defaultName(Filing.defaultKey(book), book), "", book.title, author = book.author)
        }
    }

    override suspend fun fullText(query: String): List<SearchHit> {
        val family = families()
        return relaxed(query, { family(it) to it.path }) { q ->
            // Each spelling of the query ("dapple grey horse", "dapple gray horse") is searched; most queries have one.
            Spellings.forms(q).flatMap { form -> across(form) { if (it.hasFulltextIndex) it.search(form, fullTextLimit) else emptyList() } }
                .flatMap { it.hits }
                .sortedByDescending { it.first.score }
                .map { it.second }
                .distinctBy { family(it) to it.path }
                .take(fullTextLimit)
        }
    }

    /**
     * A hit's pack family: the packs that share article paths, so one article in two of them is listed once (Plan SQ: the
     * subject packs aren't slices of Wikipedia top, and "Thuringia" showed three times). From the pack's name, its first two
     * parts ("wikipedia_en"); a book (no path) is its own.
     */
    private suspend fun families(): (SearchHit) -> String {
        val names = library.packs.first().associate { it.id to it.name }
        return { hit -> names[hit.packId]?.takeIf { it.isNotBlank() && hit.path.isNotEmpty() }?.let(TitleMatch::family) ?: hit.packId }
    }

    override suspend fun books(query: String): List<BookHit> =
        bookIndex?.let { index -> relaxed(query, { it.bookId to it.href }) { index.search(it) } } ?: emptyList()

    /**
     * Plan SQ Task 4.1: [run] on the query as typed and, when that finds fewer than [ENOUGH], again on its keywords, the
     * second run's hits after the first's. Article text and book text match every word, so a question's small words sank
     * it ("who chopped off holofernes head" found nothing; "holofernes head" finds the passage). One variant per keyword:
     * every variant would have to match too.
     */
    private suspend fun <T> relaxed(query: String, key: (T) -> Any, run: suspend (String) -> List<T>): List<T> {
        val typed = run(query)
        if (typed.size >= ENOUGH) return typed
        val keywords = keywordQuery(query) ?: return typed
        return (typed + run(keywords)).distinctBy(key)
    }

    override suspend fun titleOnly(): List<String> = library.packs.first()
        .filter { it.status == PackStatus.OK && !it.hasFulltextIndex && !it.name.startsWith("maps_") }
        .map { it.title }
        .distinct()
        .sortedBy { it.lowercase() }

    /** One pack's hits; [returned] counts them as the pack gave them, before redirects are merged. [name] is the pack's. */
    private class PackHits(val hits: List<Pair<ZimHit, SearchHit>>, val returned: Int, val name: String = "", val language: String = "")

    /**
     * Runs [run] on each OK pack, in library order; a pack that fails is skipped so the others still answer. Index pages
     * (user and tag lists, [IndexPages]) are left out, and titles lose their site ([PageTitles]) before they're ranked.
     */
    private suspend fun across(query: String, suggesting: Boolean = false, run: (ZimArchive) -> List<ZimHit>): List<PackHits> {
        if (query.isBlank()) return emptyList()
        val packs = library.packs.first().filter { it.status == PackStatus.OK }
        val asked = packs.filterNot { suggesting && TitleMatch.skipsSuggest(it.name, query) }
        // A few packs at a time, in their order (each archive reads under its own lock): one after another, 31 packs' title
        // suggestions added up to 1–2 s on the emulator.
        val gate = Semaphore(PARALLEL_PACKS)
        return withContext(io) {
            asked.map { pack -> async { gate.withPermit { packHits(pack, packs, run) } } }.awaitAll().filterNotNull()
        }
    }

    /** One pack's hits for [across]; [packs] are all the readable packs, for [SliceDedupe]. Null when it can't be opened. */
    private suspend fun packHits(pack: Pack, packs: List<Pack>, run: (ZimArchive) -> List<ZimHit>): PackHits? {
        val archive = library.archive(pack.id) ?: return null
        val hits = try { run(archive) } catch (e: Exception) { emptyList() }
        // Title suggestions are often redirects; point them at the article itself so the
        // reader keys bookmarks and positions by the real path, and list each article once.
        // A redirect is shown under the article's own title (Plan SQ: three slices listed "Turingia", not Thuringia);
        // the ZimHit keeps the title that matched.
        val found = hits.map { hit -> hit to (archive.resolvePath(hit.path) ?: hit.path) }
            .distinctBy { it.second }
            .filterNot { IndexPages.isIndex(it.second) }
            .map { (hit, path) ->
                val title = if (path == hit.path) hit.title else archive.entry(path)?.title?.ifBlank { null } ?: hit.title
                hit.copy(path = path) to SearchHit(pack.id, pack.title, path, PageTitles.clean(title, pack.title), hit.snippet)
            }
        return PackHits(SliceDedupe.apply(found, packs, slices, library::archive, { it.second.packId }, { it.second.path }), returned = hits.size, name = pack.name, language = pack.language)
    }

    companion object {
        /** Fewer article or book hits than this, and Search tries the query's keywords too ([relaxed]). */
        const val ENOUGH = 3

        /** The query's keywords as one query, or null when they are the query's own words (nothing to relax). */
        fun keywordQuery(query: String): String? {
            val words = Keywords.extract(query).mapNotNull { Keywords.variants(it).firstOrNull() }
            val joined = words.joinToString(" ")
            return joined.takeIf { it.isNotBlank() && SearchText.words(it) != SearchText.words(query) }
        }

        /** The most titles returned over every pack. */
        const val TITLE_LIMIT = 50
        /**
         * The titles asked of each pack: enough that a library of one or two packs has more than Search's first few to
         * show, without asking every pack for the whole [TITLE_LIMIT] on each keystroke.
         */
        const val TITLES_PER_PACK = 20
        const val FULL_TEXT_LIMIT = 30
        /** Longer queries get no titles a slip away ([nearTitles]); a one-letter word (the s of "Laymen's") isn't counted. */
        const val NEAR_MAX_WORDS = 4
        /** Titles asked of each pack for each typo probe ([TitleMatch.probes]). */
        const val FUZZY_PER_PACK = 10
        /** Packs searched at once. */
        const val PARALLEL_PACKS = 4
    }
}
