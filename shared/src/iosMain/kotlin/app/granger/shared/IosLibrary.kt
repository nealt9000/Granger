package app.granger.shared

import app.granger.core.catalog.Catalog
import app.granger.core.catalog.timeIndex
import app.granger.core.library.KeptArticle
import app.granger.core.library.LibraryDatabase
import app.granger.core.library.QidIndex
import app.granger.core.library.QidIndexes
import app.granger.core.library.SavedForNow
import app.granger.core.library.TimeIndex
import app.granger.core.library.TimeIndexes
import app.granger.core.library.openLibraryDatabase
import app.granger.core.online.OnlineStores
import app.granger.core.online.OnlineWikipediaArchive
import app.granger.core.online.WikimediaClient
import app.granger.online.ArticleAvailability
import app.granger.online.ArticleCopies
import app.granger.online.Availability
import app.granger.online.KeptStore
import app.granger.online.Keeper
import app.granger.online.OfflineStores
import app.granger.online.OnlineSources
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first

/**
 * The iPhone's library (Plan I3 Task 6), wired as `GrangerApp` wires Android's: the library database, Saved for now,
 * kept articles, Wikipedia online read through them, the marks, and the online index set once it's on the phone.
 * Everything lives under [dir] (Application Support), in the same layout as Android's files folder.
 *
 * Swift reads settings through the closures, so a change in Settings takes effect on the next call.
 */
class IosLibrary(
    private val dir: String,
    private val client: WikimediaClient,
    /** The languages Wikipedia online reads (the knowledge languages; on the iPhone, Settings' Wikipedia). */
    private val languages: () -> List<String>,
    /** The gate is open: the privacy line seen and Local only off. */
    private val gateOpen: () -> Boolean,
    /** Offline pictures: true for Save pictures (the default), false for Text only. */
    private val savePictures: () -> Boolean,
    /** Saved for now's cap, in megabytes. */
    private val savedCapMb: () -> Long,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val database: LibraryDatabase = openLibraryDatabase("$dir/library.db")
    val saved = SavedForNow("$dir/saved") { app.granger.screens.nowMillis() }
    val kept = KeptStore("$dir/kept", database.keptArticleDao(), scope)

    private val _version = MutableStateFlow(0)
    /** Moves whenever something is kept, removed or saved, so marks on screen are worked out again. */
    val version: StateFlow<Int> get() = _version

    private val stores = OfflineStores(
        saved, kept, savePictures = savePictures, capBytes = { savedCapMb() * 1024L * 1024 }, pageSaved = { _version.value++ },
    )

    private val sources = OnlineSources(
        client, kotlinx.coroutines.flow.flowOf(emptyList()), languages, { true },
        indexRandom = { id -> runCatching { indexes?.randomPath(id) }.getOrNull() },
        stores = stores,
    )

    /** Wikipedia in [lang] read online, through Saved for now and the kept copies. */
    fun wikipedia(lang: String): OnlineWikipediaArchive =
        sources.archive(lang) ?: OnlineWikipediaArchive(lang, client, stores = stores)

    private val keeper = Keeper({ wikipedia(it) }, kept, withPictures = savePictures, changed = { _version.value++ })

    // The online index set (Top .gqid + .gtime per language), opened once Swift has it on the phone.
    private var indexes: QidIndexes? = null
    private var times: TimeIndexes? = null

    /** The index set's files the catalog offers for [lang]: the Top entry's QID index and time index. */
    fun indexFiles(catalog: Catalog, lang: String): List<IndexFile> {
        val item = catalog.packs.firstOrNull { it.id == "wikipedia_${lang}_top_mini" } ?: return emptyList()
        val source = OnlineWikipediaArchive.packName(lang)
        return listOfNotNull(
            item.sidecars.firstOrNull { it.kind == "gqid" }?.let { IndexFile(IndexKind.QID, source, it.url, it.sha256, it.sizeBytes, "$source.gqid") },
            item.timeIndex?.let { IndexFile(IndexKind.TIME, source, it.url, it.sha256, it.sizeBytes, "$source.gtime") },
        )
    }

    /** Opens the index files at [paths] (pairs of kind and path); false when none of them opens. */
    fun openIndexes(files: List<OpenIndex>): Boolean {
        val q = files.filter { it.kind == IndexKind.QID }.mapNotNull { QidIndex.open(it.path, it.source) }
        val t = files.filter { it.kind == IndexKind.TIME }.mapNotNull { TimeIndex.open(it.path, it.source) }
        indexes = if (q.isEmpty()) null else QidIndexes(q)
        times = if (t.isEmpty()) null else TimeIndexes(t)
        _version.value++
        return q.isNotEmpty() || t.isNotEmpty()
    }

    val hasIndexes: Boolean get() = indexes != null
    fun qidIndexes(): QidIndexes? = indexes
    fun timeIndexes(): TimeIndexes? = times

    private val copies = ArticleCopies(
        qids = { indexes ?: QidIndexes(emptyList()) },
        packs = { languages().map(OnlineSources::row) },
        online = gateOpen,
        onlineLanguages = { languages().toSet() },
        kept = { lang, title -> kept.get(lang, title)?.withPictures },
    )
    private val availability = ArticleAvailability(copies, { languages().map(OnlineSources::row) }) { lang, title ->
        saved.has(OnlineStores.pageKey(lang, title))
    }

    /** An article's mark (spec §3): a dot for on this phone, a ring for saved for now, none for online only. */
    suspend fun mark(lang: String, title: String): Mark = when (val a = availability.of(OnlineWikipediaArchive.packName(lang), title)) {
        is Availability.OnPhone -> if (a.full) Mark.ON_PHONE else Mark.ON_PHONE_SHORTER
        Availability.Saved -> Mark.SAVED
        else -> Mark.NONE
    }

    /** Marks for a screen's worth of articles, keyed "lang:title". */
    suspend fun marks(articles: List<String>): Map<String, Mark> = articles.associateWith { key ->
        val lang = key.substringBefore(':')
        mark(lang, key.substringAfter(':'))
    }

    /** Whether [title] is kept, and with its pictures; null when it isn't. */
    fun keptState(lang: String, title: String): KeptArticle? = kept.get(lang, title)

    /**
     * About what keeping it takes, in bytes, under the setting in force (Android's `MarkSource.keepSize`): the page
     * saved for now and, under Save pictures, the saved pictures it shows; null when nothing is saved yet.
     */
    fun keepSize(lang: String, title: String): Long? {
        val key = OnlineStores.pageKey(lang, title)
        val page = saved.bytesOf(key) ?: return null
        if (!savePictures()) return page
        val html = saved.get(key)?.bytes?.decodeToString() ?: return page
        return page + OnlineWikipediaArchive.picturesOf(html).sumOf { saved.bytesOf(OnlineStores.pictureKey(it)) ?: 0L }
    }

    /** Keeps an online article; false when it couldn't be had (no signal and nothing saved). */
    suspend fun keep(lang: String, title: String): Boolean = keeper.keep(lang, title) != null

    suspend fun remove(lang: String, title: String) = keeper.remove(lang, title)

    /** Everything kept, newest first (Outfitting's list, Settings' count). */
    suspend fun keptArticles(): List<KeptArticle> = database.keptArticleDao().observeAll().first()

    /** Saved for now's size in bytes, and Clear. */
    fun savedBytes(): Long = saved.size()
    fun clearSaved() { saved.clear(); _version.value++ }
    /** Holds Saved for now to the cap (after the cap is lowered). */
    fun trimSaved() { saved.trim(savedCapMb() * 1024L * 1024); _version.value++ }
}

enum class Mark { ON_PHONE, ON_PHONE_SHORTER, SAVED, NONE }
enum class IndexKind { QID, TIME }

/** One file of the online index set: where to fetch it, its sha256 and size, and the name to keep it under. */
data class IndexFile(val kind: IndexKind, val source: String, val url: String, val sha256: String, val sizeBytes: Long, val fileName: String)

/** An index file on the phone, to open. */
data class OpenIndex(val kind: IndexKind, val source: String, val path: String)
