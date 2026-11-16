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
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
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

/** The same subject in the other language, in an installed pack. */
data class TopicOther(val language: String, val packId: String, val path: String, val title: String)

/** One closest article, as its card shows it. */
data class TopicArticle(val packId: String, val path: String, val title: String, val blurb: String, val picture: String?)

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
    /** True once every part has arrived. */
    val done: Boolean = false,
) {
    val hasTime: Boolean get() = time != null
}

/** Where a topic page gets its topic; the app's is [TopicSource]. */
fun interface TopicProvider {
    fun topic(packId: String, path: String): Flow<Topic>
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
    private val languageOf: suspend (packId: String) -> String?,
    /** The installed packs' ids by ZIM name: vectors are keyed by name. */
    private val packIds: suspend () -> Map<String, String>,
    private val footer: ArticleFooterSource?,
    /** Related for an article (pack name, path), with a test for "the same subject under another title". */
    private val related: (suspend (packName: String, path: String, sameItem: (String, String) -> Boolean) -> Related)?,
    private val bookOf: suspend (bookId: String) -> LibraryBook?,
    private val now: () -> Int = { java.time.Year.now().value },
    /** The library's languages (Settings): only their books are listed. Null lists every book. */
    private val libraryLanguages: suspend () -> Set<String>? = { null },
    /** The sentence a book's mention sits in, read from the book; null when it can't be. */
    private val sentence: (suspend (bookId: String, mention: app.granger.core.library.BookMention) -> String?)? = null,
    /** The languages whose packs the other-language link may point to. */
    private val languages: List<String> = listOf("en", "es"),
) : TopicProvider {
    private val lock = Mutex()
    private val cache = object : LinkedHashMap<Pair<String, String>, Topic>(CACHED, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Pair<String, String>, Topic>?) = size > CACHED
    }

    override fun topic(packId: String, path: String): Flow<Topic> = channelFlow {
        lock.withLock { cache[packId to path] }?.let { send(it); return@channelFlow }
        var topic = Topic(packId, path)
        val sending = Mutex()
        suspend fun update(change: (Topic) -> Topic) = sending.withLock { topic = change(topic); send(topic) }

        coroutineScope {
            launch { val h = header(packId, path) ?: TopicHeader(titleOf(path), "", null); update { it.copy(header = h) } }
            val qid = async { attempt { qidOf(packId, path) } }
            launch { val t = attempt { timeOf(packId, path) }; update { it.copy(time = t) } }
            launch { val o = attempt { otherOf(packId, qid.await()) }; update { it.copy(other = o) } }
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
                val (articles, books) = attempt { relatedOf(packId, path, qid.await()) } ?: (emptyList<TopicArticle>() to emptyList())
                update { it.copy(related = articles, relatedBooks = books) }
            }
        }
        update { it.copy(done = true) }
        lock.withLock { cache[packId to path] = topic }
    }

    private suspend fun qidOf(packId: String, path: String): Long? {
        val all = qids()
        return all.qidOf(packId, path) ?: if (all.has(packId)) null else languageOf(packId)?.let { all.qidOfTitle(path, it) }
    }

    private suspend fun timeOf(packId: String, path: String): TopicTimeView? {
        val index = times()
        if (index.isEmpty) return null
        val mark = index.find(packId, path) ?: return null
        val year = now()
        val window = TopicTime.window(mark, year)
        val around = index.marks(window.first, window.last, emptySet(), AROUND, year, prefer = mark.packId)
        val people = TopicTime.contemporaries(mark, around, year)
        val pictures = (listOf(mark) + people).mapNotNull { p -> lead(p.packId, p.path)?.thumbnail?.let { p.path to it } }.toMap()
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
        val articles = r.articles.take(SHOWN).mapNotNull { h ->
            val id = ids[h.target] ?: return@mapNotNull null
            val l = lead(id, h.key)
            TopicArticle(id, h.key, l?.title ?: titleOf(h.key), blurb(l?.sentence.orEmpty()), l?.thumbnail)
        }
        val books = r.books.mapNotNull { h -> bookOf(h.target)?.let { TopicBook(it, h.key) } }
        return articles to books
    }

    /** [block]'s value, or null when it fails; a cancellation still cancels. A part that fails is left out, not the page. */
    private suspend fun <T> attempt(block: suspend () -> T): T? = try {
        block()
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        if (app.granger.BuildConfig.DEBUG) android.util.Log.w("GrangerTopic", "a topic part failed", e)
        null
    }

    companion object {
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

        fun titleOf(path: String): String = app.granger.core.library.PageTitles.clean(path.substringAfterLast('/').replace('_', ' '))
    }
}
