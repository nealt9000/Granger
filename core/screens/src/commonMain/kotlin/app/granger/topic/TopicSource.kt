package app.granger.topic

import app.granger.core.library.QidIndexes
import app.granger.core.library.Related
import app.granger.core.library.TimeIndexes
import app.granger.core.library.TimeMark
import app.granger.core.library.TopicTime
import app.granger.ebook.ArticleFooter
import app.granger.ebook.ArticleFooterSource
import app.granger.ebook.ArticleLead
import app.granger.ebook.LibraryBook
import app.granger.side
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** A topic page's opening, read out of the article itself: its title, a few sentences of its lead, and its picture. */
data class TopicHeader(val title: String, val lead: String, val picture: String?)

/** Its place in time: the page's own mark, the people alive with it and the events of its span (Decision 2). */
data class TopicTimeView(
    val mark: TimeMark,
    val people: List<TimeMark>,
    val events: List<TimeMark>,
    /** Each person's picture (a cached file) by path, where the article has one. */
    val pictures: Map<String, String> = emptyMap(),
)

/**
 * Where a topic that isn't a spread sits in Explore (Plan O6, Neal 2026-11-19): its subject, its room, and the room's
 * best-known articles. From the phone's index, or from the article's topic record when no index holds it.
 */
data class TopicPlace(val subject: String, val room: app.granger.core.library.Facet?, val more: List<TopicArticle> = emptyList())

/** The same subject in the other language, in an installed pack. */
data class TopicOther(val language: String, val packId: String, val path: String, val title: String)

/** One book near in meaning, opened at [chapter]. */
data class TopicBook(val book: LibraryBook, val chapter: String)

/**
 * Everything a topic page shows (Plan LL2), filled in as each part arrives: [header] first, then the rest. A part that's
 * null hasn't arrived; one that's empty (or [ArticleFooter.NONE]) has nothing, and the page leaves its section out.
 */
data class Topic(
    val packId: String,
    val path: String,
    val header: TopicHeader? = null,
    val time: TopicTimeView? = null,
    val other: TopicOther? = null,
    val books: ArticleFooter? = null,
    val related: List<TopicArticle>? = null,
    val relatedBooks: List<TopicBook>? = null,
    /** The whole sentence each shown book's first mention sits in, by book id; a book missing here shows the link's words. */
    val quotes: Map<String, String> = emptyMap(),
    /** Its subject and room, for a topic that isn't a spread (a spread has its own place); null without either. */
    val place: TopicPlace? = null,
    /** A broad topic's editorial parts (Plan EX); null for any other topic, or until they arrive. */
    val broad: BroadTopic? = null,
    /** It is a broad topic whose parts are still being read: the page is laid out as a spread from the start. */
    val broadPending: Boolean = false,
    /** True once every part has arrived. */
    val done: Boolean = false,
) {
    val hasTime: Boolean get() = time != null
}

/** Where a topic page gets its topic; the app's is [TopicSource]. */
fun interface TopicProvider {
    fun topic(packId: String, path: String): Flow<Topic>

    /** What stands round a topic for its quilt ([QuiltAround]); [plates] are the articles (pack, path) to count the leads of. */
    suspend fun around(packId: String, path: String, plates: List<Pair<String, String>> = emptyList()): QuiltAround = QuiltAround()

    /** An article's card (its title, opening sentence and picture); null when it can't be read. */
    suspend fun card(packId: String, path: String): TopicArticle? = null
}

/**
 * Builds a topic page (Plan LL2 Task 2) from what the phone already has: the article for its header, the time index,
 * the QID indexes for the other language, the Linked Classics for "In your books", and the vectors for Related. Each
 * part is read on its own and sent as soon as it's ready; a finished topic is cached per page. Blocking reads happen in
 * the lambdas, which the app runs on IO.
 */
class TopicSource(
    /** The article's header; null when it can't be read. */
    private val header: suspend (packId: String, path: String) -> TopicHeader?,
    /** A related article's card: title, first sentences, picture. */
    private val lead: suspend (packId: String, path: String) -> ArticleLead?,
    private val times: suspend () -> TimeIndexes,
    private val qids: suspend () -> QidIndexes,
    /** The pack's Wikipedia language (`en`), or null for a pack that isn't a Wikipedia. */
    languageOf: suspend (packId: String) -> String?,
    /** The installed packs' ids by ZIM name: vectors are keyed by name. */
    private val packIds: suspend () -> Map<String, String>,
    private val footer: ArticleFooterSource?,
    /** Related for an article (pack name, path), with a test for "the same subject under another title". */
    private val related: (suspend (packName: String, path: String, sameItem: (String, String) -> Boolean) -> Related)?,
    private val bookOf: suspend (bookId: String) -> LibraryBook?,
    private val now: () -> Int = { app.granger.screens.currentYear() },
    /** The library's languages (Settings): only their books are listed. Null lists every book. */
    private val libraryLanguages: suspend () -> Set<String>? = { null },
    /** The sentence a book's mention sits in, read from the book; null when it can't be. */
    private val sentence: (suspend (bookId: String, mention: app.granger.core.library.BookMention) -> String?)? = null,
    /** The languages whose packs the other-language link may point to. */
    private val languages: List<String> = listOf("en", "es"),
    /** One of a pack's pictures by its path in the ZIM (a broad topic's figures), cached; null when it has none. */
    private val figure: (suspend (packId: String, image: String) -> String?)? = null,
    /** Many articles' cards asked for at once before they're made one by one (Wikipedia online's batched summaries). */
    private val prefetch: (suspend (packId: String, paths: List<String>) -> Unit)? = null,
    /** An online article's QID from its summary, for articles no local index holds (Plan O6 builder's call 8). */
    private val onlineQid: (suspend (packId: String, path: String) -> Long?)? = null,
    /**
     * The article's topic record (Plan O6), read online by range, for an online article whose QID no local index holds:
     * its parts, what leads to it, its subjects and rooms, its other-language title. Null without records.
     */
    private val records: (suspend (packId: String, language: String, qid: Long) -> app.granger.core.library.TopicRecord?)? = null,
) : TopicProvider {
    /** A pack's language doesn't change: asked once (each ask read the packs from the database, and a page asks many times). */
    private val packLanguages = app.granger.core.library.ConcurrentCache<String, String>()
    private val languageOf: suspend (String) -> String? = { packId -> packLanguages[packId] ?: languageOf(packId)?.also { packLanguages[packId] = it } }

    private val lock = Mutex()
    private val cache = app.granger.core.library.Lru<Pair<String, String>, Topic>(CACHED)

    override fun topic(packId: String, path: String): Flow<Topic> = channelFlow {
        lock.withLock { cache[packId to path] }?.let { send(it); return@channelFlow }
        var topic = Topic(packId, path)
        val sending = Mutex()
        suspend fun update(change: (Topic) -> Topic) = sending.withLock { topic = change(topic); send(topic) }

        coroutineScope {
            val qid = async { attempt { qidOf(packId, path) } }
            // Plan O6: a record when no local index holds the article; it never holds up the header.
            val record = async { attempt { recordOf(packId, qid.await()) } }
            val localParts = async { attempt { qid.await()?.let { q -> qids().topicParts(q, packId, languageOf(packId)) } } }
            val parts = async { localParts.await() ?: record.await()?.parts }
            var broadRead = false    // changed and read only inside update, one at a time
            launch {
                val h = app.granger.Perf.time("topic.header $path") { header(packId, path) } ?: TopicHeader(titleOf(path), "", null)
                // Whether it is a broad topic is known before its header shows, so the page never changes its layout; a
                // record gets a short wait for that, then the header goes ahead.
                val pending = localParts.await() != null || withTimeoutOrNull(RECORD_WAIT_MS) { record.await() }?.parts != null
                update { it.copy(header = h, broadPending = pending && !broadRead) }
            }
            launch { val t = app.granger.Perf.time("topic.time $path") { attempt { timeOf(packId, path) } }; update { it.copy(time = t) } }
            launch { val o = attempt { otherOf(packId, qid.await()) ?: otherOfRecord(packId, record.await()) }; update { it.copy(other = o) } }
            launch {
                val language = languageOf(packId)
                val b = footer?.let { f -> attempt { f.footer(packId, path, libraryLanguages(), language) } } ?: ArticleFooter.NONE
                update { it.copy(books = b) }
                val read = sentence ?: return@launch
                val quotes = b.inBooks.take(QUOTES).mapNotNull { hit ->
                    val m = hit.passages.firstOrNull() ?: return@mapNotNull null
                    attempt { read(hit.book.bookId, m) }?.let { hit.book.bookId to it }
                }.toMap()
                if (quotes.isNotEmpty()) update { it.copy(quotes = quotes) }
            }
            launch {
                // Its place, for a topic that isn't a spread: the room line and "More in this room".
                if (parts.await() != null) return@launch
                val p = app.granger.Perf.time("topic.place $path") { attempt { placeOf(packId, qid.await(), record.await()) } }
                if (p != null) update { it.copy(place = p) }
            }
            launch {
                val (articles, books) = app.granger.Perf.time("topic.related $path") { attempt { relatedOf(packId, path, qid.await()) } } ?: (emptyList<TopicArticle>() to emptyList())
                update { it.copy(related = articles, relatedBooks = books) }
            }
            launch {
                val p = parts.await()
                // Online, the page's words first (no request: titles and parts from the index), its pictures after.
                val items = if (localParts.await() == null) record.await()?.items.orEmpty() else emptyMap()
                if (p != null && (app.granger.core.online.OnlineWikipediaArchive.languageOf(packId) != null)) {
                    app.granger.Perf.time("topic.broad.words $path") { attempt { broadOf(packId, p, pictures = false, items = items) } }
                        ?.let { words -> update { broadRead = true; it.copy(broad = words, broadPending = false) } }
                }
                val b = p?.let { app.granger.Perf.time("topic.broad $path") { attempt { broadOf(packId, it, items = items) } } }
                update { broadRead = true; it.copy(broad = b ?: it.broad, broadPending = false) }
                // Where it hangs and what stands beside it come after the page's own parts.
                if (b != null && p != null) app.granger.Perf.time("topic.placed $path") { attempt { placed(b, p, packId, path, record.await()) } }?.let { full -> update { it.copy(broad = full) } }
            }
        }
        update { it.copy(done = true) }
        lock.withLock { cache[packId to path] = topic }
    }

    private suspend fun qidOf(packId: String, path: String): Long? {
        val all = qids()
        return all.qidOf(packId, path) ?: (if (all.has(packId)) null else languageOf(packId)?.let { all.qidOfTitle(path, it) })
            ?: onlineQid?.takeIf { (app.granger.core.online.OnlineWikipediaArchive.languageOf(packId) != null) }?.let { find -> attempt { find(packId, path) } }
    }

    /** Records are for what the phone's indexes don't hold (builder's call 7): an online article whose QID none of them has. */
    private val recordCache = app.granger.core.library.ConcurrentCache<Pair<String, Long>, app.granger.core.library.TopicRecord>()

    private suspend fun recordOf(packId: String, qid: Long?): app.granger.core.library.TopicRecord? {
        val read = records ?: return null
        qid ?: return null
        if (!(app.granger.core.online.OnlineWikipediaArchive.languageOf(packId) != null)) return null
        val language = languageOf(packId) ?: return null
        recordCache[packId to qid]?.let { return it }
        val all = qids()
        if (all.articlesOf(qid, language).any { languageOf(it.packId) == language }) return null
        return app.granger.Perf.time("topic.record $qid") { read(packId, language, qid) }?.also { recordCache[packId to qid] = it }
    }

    /** Its subject, its room and the room's best-known other articles; null when neither index nor record gives a subject. */
    private suspend fun placeOf(packId: String, qid: Long?, record: app.granger.core.library.TopicRecord?): TopicPlace? {
        val all = qids()
        val subject = qid?.let { q -> all.subjectsOf(q).firstOrNull() } ?: record?.subjects?.firstOrNull() ?: return null
        val room = qid?.let { q -> all.facetOf(q, subject) }
            ?: record?.rooms?.firstOrNull { it.startsWith("$subject.") }?.let { key -> all.facets(subject).firstOrNull { it.key == key } }
        val more = room?.let { r -> all.topInFacet(r.key, ROOM_MORE + 1).filter { it.qid != qid }.take(ROOM_MORE) }.orEmpty()
            .side { a -> attempt { cardOf(a.packId, a.path) } }.filterNotNull()
        return TopicPlace(subject, room, more)
    }

    /** "Read in …" from a record's other-language title, for an online article (the other language's Wikipedia online). */
    private suspend fun otherOfRecord(packId: String, record: app.granger.core.library.TopicRecord?): TopicOther? {
        val title = record?.other ?: return null
        val language = languageOf(packId) ?: return null
        val other = languages.firstOrNull { it != language } ?: return null
        val path = title.replace(' ', '_')
        return TopicOther(other, app.granger.core.online.OnlineWikipediaArchive.packName(other), path, titleOf(path))
    }

    /** A broad topic's parts (Plan EX T1), each item resolved in the installed packs, the page's language first. */
    private suspend fun broadOf(
        packId: String, parts: app.granger.core.library.TopicParts, pictures: Boolean = true,
        /** A record's items (Plan O6): what no local index can resolve, it names by path in this online source. */
        items: Map<Long, app.granger.core.library.RecordRef> = emptyMap(),
    ): BroadTopic? {
        val all = qids()
        val language = languageOf(packId)
        fun resolved(q: Long): Pair<String, String>? = all.firstArticleOf(q, language)?.let { it.packId to it.path }
            ?: items[q]?.path?.takeIf { (app.granger.core.online.OnlineWikipediaArchive.languageOf(packId) != null) }?.let { packId to it }
        val times = attempt { times() }
        suspend fun fetch(image: String) = figure?.let { f -> attempt { f(packId, image) } ?: attempt { f(parts.packId, image) } }
        // Online (Plan O1 Pixel pass), what the page will read is asked for together rather than one by one, figures and
        // cards at the same time: each figure is a round trip to Commons (13 in a row took 9 s of a broad topic's 11), and
        // the cards (its kinds' plates, its people) come in batched summaries of 20.
        val online = pictures && (app.granger.core.online.OnlineWikipediaArchive.languageOf(packId) != null)
        val early: Map<String, String?> = if (!online) emptyMap() else coroutineScope {
            val cards = launch {
                val find = prefetch ?: return@launch
                val items = (parts.kinds.map { it.qid } + parts.people).asSequence()
                    .mapNotNull(::resolved).filter { it.first == packId }.map { it.second }.distinct().take(PREFETCH_CARDS).toList()
                items.chunked(20).map { chunk -> async { attempt { find(packId, chunk) } } }.awaitAll()
            }
            val figures = parts.figures.map { it.image }.distinct().take(PREFETCH_FIGURES).map { image -> async { image to fetch(image) } }.awaitAll().toMap()
            cards.join()
            figures
        }
        return BroadTopics.build(
            parts,
            resolve = ::resolved,
            card = { p, path -> cardOf(p, path) },
            figure = { image -> if (image in early) early[image] else fetch(image) },
            markOf = { p, path -> runCatching { times?.find(p, path) }.getOrNull() },
            now = now(),
            pictures = pictures,
        )
    }

    override suspend fun card(packId: String, path: String): TopicArticle? = attempt { cardOf(packId, path) }

    override suspend fun around(packId: String, path: String, plates: List<Pair<String, String>>): QuiltAround = app.granger.Perf.time("quilt.around $path (${plates.size})") { aroundOf(packId, path, plates) }

    private suspend fun aroundOf(packId: String, path: String, plates: List<Pair<String, String>>): QuiltAround {
        val all = attempt { qids() } ?: return QuiltAround()
        val qid = attempt { qidOf(packId, path) }
        // What leads to it, its rooms, its card and its dates don't depend on the plates asked about: read once, kept.
        val base = lock.withLock { rounds[packId to path] } ?: run {
            val language = languageOf(packId)
            fun plain(a: app.granger.core.library.QidArticle) = TopicArticle(a.packId, a.path, titleOf(a.path), "", null)
            val record = attempt { recordOf(packId, qid) }
            val leads = (qid?.let { q -> attempt { all.holders(q, LEADS, language) } }.orEmpty().takeIf { it.isNotEmpty() }
                ?: record?.holders?.mapNotNull { h -> h.path?.let { app.granger.core.library.QidArticle(h.qid, packId, it, h.rank) } }.orEmpty()).map(::plain)
            val rooms = (qid?.let { q -> attempt { all.roomsOf(q) } }.orEmpty().takeIf { it.isNotEmpty() }
                ?: record?.let { r -> r.rooms.mapNotNull { key -> all.facets(key.substringBefore('.')).firstOrNull { it.key == key } } }.orEmpty())
            val mark = attempt { times().takeIf { !it.isEmpty }?.find(packId, path) }
            QuiltAround(leads, rooms, emptyMap(), card(packId, path), mark).also { lock.withLock { rounds[packId to path] = it } }
        }
        if (plates.isEmpty()) return base
        val byQid = plates.mapNotNull { (p, at) -> attempt { all.qidOf(p, at) }?.let { it to Quilts.key(p, at) } }
        val held = attempt { all.heldBy(byQid.map { it.first }.distinct(), qid) }.orEmpty()
        return base.copy(more = byQid.mapNotNull { (q, key) -> held[q]?.let { key to it } }.toMap())
    }

    private val rounds = app.granger.core.library.Lru<Pair<String, String>, QuiltAround>(ROUNDS)

    private suspend fun cardOf(packId: String, path: String): TopicArticle =
        lead(packId, path).let { l -> TopicArticle(packId, path, l?.title ?: titleOf(path), blurb(l?.sentence.orEmpty()), l?.thumbnail) }

    /**
     * [broad] with its place (its subject, its room, the topics before and after it there, best known first) and the
     * room's two best-known other broad topics set beside it, each counted by its own page's rule.
     */
    private suspend fun placed(
        broad: BroadTopic, parts: app.granger.core.library.TopicParts, packId: String, path: String,
        record: app.granger.core.library.TopicRecord? = null,
    ): BroadTopic {
        val all = qids()
        val language = languageOf(packId)
        val subject = all.subjectsOf(parts.qid).firstOrNull() ?: record?.subjects?.firstOrNull() ?: return broad
        val room = all.facetOf(parts.qid, subject)
            ?: record?.rooms?.firstOrNull { it.startsWith("$subject.") }?.let { key -> all.facets(subject).firstOrNull { it.key == key } }
            ?: return broad.copy(place = BroadPlace(subject))
        fun plain(a: app.granger.core.library.QidArticle) = TopicArticle(a.packId, a.path, titleOf(a.path), "", null)
        val ranked = all.topInFacet(room.key, ROOM_MAX)
        val (before, at, after) = app.granger.explore.neighbours(ranked) { it.qid == parts.qid }
        val place = BroadPlace(subject, room, before?.let(::plain), after?.let(::plain), at, ranked.size)
        fun column(a: TopicArticle, b: BroadTopic) = BroadColumn(
            a, if (b.hasKinds) b.under else 0, b.groups.sortedByDescending { it.count }.take(BroadTopics.FAMILIES).map { it.name },
            b.people.take(COLUMN_PEOPLE).map { it.article.title }, b.chapters.size + (if (b.story != null) 1 else 0), b.inlinks,
        )
        val others = all.broadInFacet(room.key, BESIDE + 1, language).filter { it.qid != parts.qid }.take(BESIDE).side { o ->
            val theirs = all.topicParts(o.qid, o.packId, language) ?: return@side null
            broadOf(o.packId, theirs, pictures = false)?.let { column(cardOf(o.packId, o.path), it) }
        }.filterNotNull()
        val mine = TopicArticle(packId, path, titleOf(path), "", null)
        return broad.copy(place = place, beside = if (others.isEmpty()) emptyList() else listOf(column(mine, broad)) + others)
    }

    private suspend fun timeOf(packId: String, path: String): TopicTimeView? {
        val index = times()
        if (index.isEmpty) return null
        val mark = index.find(packId, path) ?: return null
        val year = now()
        val window = TopicTime.window(mark, year)
        val around = index.marks(window.first, window.last, emptySet(), AROUND, year, prefer = mark.packId)
        val people = TopicTime.contemporaries(mark, around, year)
        val pictures = (listOf(mark) + people).side { p -> lead(p.packId, p.path)?.thumbnail?.let { p.path to it } }.filterNotNull().toMap()
        return TopicTimeView(mark, people, TopicTime.events(mark, around, year), pictures)
    }

    private suspend fun otherOf(packId: String, qid: Long?): TopicOther? {
        qid ?: return null
        val language = languageOf(packId) ?: return null
        val all = qids()
        for (other in languages.filter { it != language }) {
            // articlesOf puts the language's packs first but returns the others too: check each pack's own language.
            for (article in all.articlesOf(qid, other)) {
                if (languageOf(article.packId) == other) return TopicOther(other, article.packId, article.path, titleOf(article.path))
            }
        }
        return null
    }

    private suspend fun relatedOf(packId: String, path: String, qid: Long?): Pair<List<TopicArticle>, List<TopicBook>> {
        val find = related ?: return emptyList<TopicArticle>() to emptyList()
        val ids = packIds()
        val name = ids.entries.firstOrNull { it.value == packId }?.key ?: return emptyList<TopicArticle>() to emptyList()
        val all = qids()
        // The same subject under another title: a redirect's target, or the article in another pack.
        val r = find(name, path) { packName, key -> qid != null && ids[packName]?.let { all.qidOf(it, key) } == qid }
        val times = attempt { times() }
        // Related is volunteered: an item the adult-content filter hides isn't offered (the indexes' own filter, QidIndexes.hide).
        val shown = r.articles.filterNot { h -> ids[h.target]?.let { all.qidOf(it, h.key) }?.let(all.hide) == true }
        val articles = shown.take(SHOWN).side { h ->
            val id = ids[h.target] ?: return@side null
            val l = lead(id, h.key)
            TopicArticle(id, h.key, l?.title ?: titleOf(h.key), blurb(l?.sentence.orEmpty()), l?.thumbnail, kindOf(id, h.key, times, all))
        }.filterNotNull()
        val books = r.books.mapNotNull { h -> bookOf(h.target)?.let { TopicBook(it, h.key) } }
        return articles to books
    }

    /** A life is a person, a dated span or battle an event; an item with coordinates a place; anything else a topic. */
    private fun kindOf(packId: String, path: String, times: TimeIndexes?, qids: QidIndexes): NodeKind {
        val mark = runCatching { times?.find(packId, path) }.getOrNull()
        // An older QID index has no coordinates table; that's "not a place", never a lost Related.
        val placed = runCatching { qids.qidOf(packId, path)?.let { qids.coordinates(it) } != null }.getOrDefault(false)
        return when {
            mark?.kind == app.granger.core.library.TimeKind.LIFE -> NodeKind.PERSON
            mark != null && (mark.kind == app.granger.core.library.TimeKind.SPAN ||
                app.granger.core.library.Lane.WARS in mark.lanes || app.granger.core.library.Lane.EVENTS_POLITICS in mark.lanes) &&
                !placed -> NodeKind.EVENT
            placed -> NodeKind.PLACE
            else -> NodeKind.TOPIC
        }
    }

    /** [block]'s value, or null when it fails; a cancellation still cancels. A part that fails is left out, not the page. */
    private suspend fun <T> attempt(block: suspend () -> T): T? = try {
        block()
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        app.granger.Perf.warn?.invoke("a topic part failed", e)
        null
    }

    companion object {
        /** Topics whose surroundings are kept, for the quilt's walk and its labels. */
        private const val ROUNDS = 48

        /** The broad topics that lead to a topic, as its quilt and a held plate's label show them. */
        private const val LEADS = 12

        /** A room's topics read to find a topic's neighbours in it. */
        private const val ROOM_MAX = 50_000
        /** The other broad topics set beside one, and the people named in each column. */
        private const val BESIDE = 2
        private const val COLUMN_PEOPLE = 3

        /** Figures asked for together for an online broad topic: about what its story, figures and diagram read. */
        private const val PREFETCH_FIGURES = 16
        /** Cards asked for together for an online broad topic (its plates are spread through its kinds): ten batches at most. */
        private const val PREFETCH_CARDS = 200

        /** A room's best-known articles shown under a topic that isn't a spread. */
        const val ROOM_MORE = 6

        /** How long the header waits for a record to say whether the page is a spread (Plan O6): the layout, not the words. */
        private const val RECORD_WAIT_MS = 600L

        /** Topics kept, so Back to a topic page is instant. */
        private const val CACHED = 16
        /** Marks read around a subject's span, from which contemporaries and events are picked. */
        private const val AROUND = 400
        /** Closest articles shown on the page (the map takes them too). */
        const val SHOWN = 8
        /** Book quotes shown on the page, each read whole from its book. */
        const val QUOTES = 3

        /** A card's first sentence without its bracketed asides: birth names, pronunciations, Greek. */
        fun blurb(sentence: String): String {
            var s = sentence
            repeat(3) { s = s.replace(Regex("\\s*[(\\[][^()\\[\\]]*[)\\]]"), "") }
            return s.replace(Regex("\\s+([,.;:])"), "$1").replace(Regex("\\s{2,}"), " ").trim()
        }

        fun titleOf(path: String): String = TopicTitles.titleOf(path)
    }
}
