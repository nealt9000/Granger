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
import app.granger.core.library.withoutHidden
import app.granger.core.text.hex
import app.granger.core.text.sha1
import kotlinx.io.buffered

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
    /** Keep adult topics out of suggestions (Plan AF; on by default). */
    private val keepAdultOff: () -> Boolean = { true },
    /** Where pictures for cards are cached (Caches/granger). */
    private val cacheDir: String = "$dir/cache",
    /** The app's version, for the User-Agent of the records' requests. */
    private val appVersion: String = "ios",
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
        contentFilter.indexes = indexes
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

    // ---- Topic pages (Plan I3 Task 7), wired as GrangerApp wires Android's TopicSource for online articles.

    /** The adult flags as the volunteered screens apply them (Related, Explore, Home, games). */
    val contentFilter = app.granger.core.library.ContentFilter(keepAdultOff)

    /** The verified catalog, once Swift has it: the topic records' entries come from it. */
    var catalog: Catalog? = null

    private val remoteRecords = app.granger.core.online.RemoteRecords(
        app.granger.core.online.UrlSessionFetch(userAgent = GrangerCoreEntry.userAgent(appVersion)),
        entryFor = { lang ->
            catalog?.packs.orEmpty().firstOrNull { it.records != null && app.granger.core.catalog.Languages.codes(it.language).firstOrNull() == lang }?.records
        },
        dirFolder = "$dir/records",
        saved = object : app.granger.core.online.SavedBlocks {
            override fun get(key: String) = saved.get(key)?.bytes
            override fun put(key: String, bytes: ByteArray) { saved.put(key, "RECORDS", bytes, "application/octet-stream"); trimSaved() }
            override fun remove(key: String) = saved.remove(key)
        },
        canFetch = gateOpen,
    )

    /** A picture of an online article cached as a file (Android's ArticleThumbnails.picture, without the resize). */
    fun picture(lang: String, url: String): String? {
        val name = sha1(url.encodeToByteArray()).hex()
        val path = kotlinx.io.files.Path(cacheDir, "pictures", name)
        if (kotlinx.io.files.SystemFileSystem.metadataOrNull(path)?.isRegularFile == true) return path.toString()
        val item = wikipedia(lang).read(OnlineWikipediaArchive.IMG + url.substringAfter("://")) ?: return null
        return runCatching {
            kotlinx.io.files.SystemFileSystem.createDirectories(kotlinx.io.files.Path(cacheDir, "pictures"))
            kotlinx.io.files.SystemFileSystem.sink(path).buffered().use { it.write(item.bytes) }
            path.toString()
        }.getOrNull()
    }

    private fun langOf(packId: String) = OnlineWikipediaArchive.languageOf(packId)

    val topics: app.granger.topic.TopicSource = app.granger.topic.TopicSource(
        header = { packId, path ->
            val lang = langOf(packId) ?: return@TopicSource null
            val s = wikipedia(lang).summary(path) ?: return@TopicSource null
            app.granger.topic.TopicHeader(
                app.granger.core.library.PageTitles.clean(s.title),
                app.granger.topic.TopicSource.blurb(app.granger.home.LeadLinks.summaryOf(s.extract, 3, TOPIC_LEAD_CHARS)),
                s.thumbnail?.let { picture(lang, it) },
            )
        },
        lead = { packId, path ->
            val lang = langOf(packId) ?: return@TopicSource null
            val s = wikipedia(lang).summary(path) ?: return@TopicSource null
            val thumb = if (contentFilter.noPicture(packId, path) || (s.qid?.let(contentFilter::noPicture) == true)) null else s.thumbnail?.let { picture(lang, it) }
            app.granger.ebook.ArticleLead(app.granger.core.library.PageTitles.clean(s.title), app.granger.home.LeadLinks.summaryOf(s.extract), thumb)
        },
        times = { times ?: TimeIndexes(emptyList()) },
        qids = { indexes ?: QidIndexes(emptyList()) },
        languageOf = { id -> langOf(id) },
        packIds = { languages().associate { OnlineWikipediaArchive.packName(it) to OnlineWikipediaArchive.packName(it) } },
        footer = null,
        related = { name, path, _ ->
            val lang = langOf(name)
            if (lang == null) app.granger.core.library.Related(emptyList(), emptyList()) else {
                val source = OnlineWikipediaArchive.packName(lang)
                val hits = client.related(lang, path.substringAfterLast('/'), app.granger.topic.TopicSource.SHOWN * 2)
                    // Related is volunteered: an article the adult-content filter hides isn't offered.
                    .filterNot { r -> r.qid?.let(contentFilter::hides) == true }
                    .mapIndexed { i, r -> app.granger.core.library.RelatedHit(app.granger.core.research.SourceKind.ARTICLE, source, r.key, 1f - i / 100f) }
                app.granger.core.library.Related(hits, emptyList())
            }
        },
        bookOf = { null },
        libraryLanguages = { languages().toSet() },
        prefetch = { packId, paths -> langOf(packId)?.let { wikipedia(it).prefetch(paths) } },
        onlineQid = { packId, path -> langOf(packId)?.let { wikipedia(it).summary(path)?.qid } },
        records = { packId, lang, qid -> remoteRecords.record(lang, qid, packId)?.withoutHidden(contentFilter.isOn) },
    )

    // ---- Home (Plan I3 Task 8): Wander, the timeline and the games, wired as GrangerNavHost wires Android's.

    /** The online sources as pack rows, while they can be read (offline, Wander and the timeline walk only what opens). */
    private fun openSources(): List<app.granger.core.library.Pack> = if (gateOpen()) languages().map(OnlineSources::row) else emptyList()

    /** The time indexes a volunteered screen may use: none of an online source while the gate is shut. */
    suspend fun volunteeredTimes(): TimeIndexes {
        val t = times ?: return TimeIndexes(emptyList())
        return if (gateOpen()) t else t.without(languages().map(OnlineWikipediaArchive::packName).toSet())
    }

    /** One of an archive's pictures, cached as a file; a drawing or emblem ([whole]) is named so screens don't crop it. */
    fun archivePicture(archive: app.granger.core.zim.ZimArchive, imagePath: String, whole: Boolean): String? = runCatching {
        val name = sha1("${archive.uuid}/$imagePath".encodeToByteArray()).hex() + if (whole) app.granger.explore.ThumbnailNames.WHOLE_SUFFIX else ".jpg"
        val path = kotlinx.io.files.Path(cacheDir, "thumbs", name)
        if (kotlinx.io.files.SystemFileSystem.metadataOrNull(path)?.isRegularFile == true) return@runCatching path.toString()
        val item = archive.read(imagePath) ?: return@runCatching null
        kotlinx.io.files.SystemFileSystem.createDirectories(kotlinx.io.files.Path(cacheDir, "thumbs"))
        kotlinx.io.files.SystemFileSystem.sink(path).buffered().use { it.write(item.bytes) }
        path.toString()
    }.getOrNull()

    /** An article's plate picture by the picture rule (Plan EX P0), as Android's ArticleThumbnails.thumbnail. */
    fun thumbnail(archive: app.granger.core.zim.ZimArchive, articlePath: String, html: String): String? = runCatching {
        app.granger.home.PlateRule.ranked(html, articlePath).asSequence()
            .mapNotNull { (path, whole) -> archive.resolvePath(path)?.let { it to whole } }.distinctBy { it.first }
            .take(app.granger.home.PlateRule.MAX_TRIED)
            .firstNotNullOfOrNull { (path, whole) -> archivePicture(archive, path, whole) }
    }.getOrNull()

    /** An article's picture for a screen Granger fills by itself: the summary's online; none the adult flags leave out. */
    fun volunteeredPicture(packId: String, path: String): String? {
        if (contentFilter.noPicture(packId, path)) return null
        val lang = langOf(packId) ?: return null
        return wikipedia(lang).summary(path)?.thumbnail?.let { picture(lang, it) }
    }

    /** Title search in the online Wikipedias (Wander's start search). */
    private fun titles(query: String): List<app.granger.core.library.SearchHit> = languages().flatMap { lang ->
        runCatching { client.titles(lang, query, 10) }.getOrDefault(emptyList()).map {
            app.granger.core.library.SearchHit(OnlineWikipediaArchive.packName(lang), "Wikipedia", it.key, it.title)
        }
    }

    fun wander(): Owned<app.granger.home.WanderModel> = owned { scope ->
        app.granger.home.WanderModel(
            scope = scope,
            encyclopedias = { openSources() },
            archiveOf = { wikipedia(OnlineWikipediaArchive.languageOf(it.id) ?: "en") },
            thumbnail = ::thumbnail,
            titles = { titles(it) },
            hides = contentFilter::hides,
            noPicture = contentFilter::noPicture,
        )
    }

    /** The timeline, opened at [packId]/[path] when given (Show in time), else a random century's best known. */
    fun timeline(packId: String?, path: String?): Owned<app.granger.home.TimelineModel> = owned { scope ->
        app.granger.home.TimelineModel(
            scope = scope,
            indexes = { volunteeredTimes() },
            picture = { p, a -> volunteeredPicture(p, a) },
            startAt = if (packId != null && path != null) packId to path else null,
        )
    }

    /** Home's mixed games ([mixed]) or the Games screen's runs of one game. */
    fun games(mixed: Boolean, start: app.granger.home.GameKind?): Owned<app.granger.home.GameModel> = owned { scope ->
        app.granger.home.GameModel(
            scope = scope,
            indexes = { volunteeredTimes() },
            picture = { p, a -> volunteeredPicture(p, a) },
            store = gameStore,
            mixed = mixed,
            start = start,
        )
    }

    private val gameStore: app.granger.home.GameStore = DefaultsGameStore("games")

    // ---- Explore (Plan I3 Task 9), wired as GrangerNavHost wires Android's ExploreSources for Wikipedia online.

    /** The rooms' one-line descriptions: the bundled `rooms.json`, read by Swift. */
    var roomsJson: () -> String = { "{}" }
    private val roomDescriptions by lazy { app.granger.explore.RoomDescriptions { roomsJson() } }

    /** Wikipedia online's index set on its way, as Swift sees it (Explore says so while it comes). */
    val indexWait = MutableStateFlow(app.granger.online.IndexWait.NONE)
    fun setIndexComing(coming: Boolean) {
        indexWait.value = if (coming) app.granger.online.IndexWait.COMING else app.granger.online.IndexWait.NONE
    }

    val seen: app.granger.explore.SeenStore = DefaultsSeenStore("explore.seen")

    val exploreSources = app.granger.explore.ExploreSources(
        qids = { indexes ?: QidIndexes(emptyList()) },
        times = { times ?: TimeIndexes(emptyList()) },
        picture = { packId, path -> volunteeredPicture(packId, path) },
        packs = kotlinx.coroutines.flow.flow { emit(languages().map { OnlineSources.row(it) to "general" }) },
        isWikipedia = { it.name.startsWith("wikipedia_") },
        look = { packId, path ->
            val lang = langOf(packId)
            val s = lang?.let { wikipedia(it).summary(path) }
            if (s == null) null else app.granger.explore.ArticleLook(
                if (contentFilter.noPicture(packId, path)) null else s.thumbnail?.let { picture(lang, it) },
                app.granger.explore.firstSentence(s.extract),
            )
        },
        roomDescription = { key, language -> roomDescriptions.of(key, language) },
        seen = seen,
        onlineIndex = indexWait,
    )

    /** Explore's landing model; [close] clears it. */
    fun explore(): OwnedViewModel<app.granger.explore.ExploreViewModel> =
        OwnedViewModel { app.granger.explore.ExploreViewModel(exploreSources, language = { languages().firstOrNull() ?: "en" }) }

    /** A subject's page ([facetId] null, [listing] false), a room ([facetId]), or a whole-subject listing. */
    fun subject(subjectId: String, facetId: String?, listing: Boolean): OwnedViewModel<app.granger.explore.SubjectViewModel>? {
        val subject = app.granger.explore.Subject.of(subjectId) ?: return null
        return OwnedViewModel {
            app.granger.explore.SubjectViewModel(subject, exploreSources, facetId = facetId, listing = listing || facetId != null, language = languages().firstOrNull() ?: "en")
        }
    }

    /**
     * A subject's quiz (Plan EX Task 3): the same games, dealt only from the dated articles whose main subject it is; its
     * own count and best runs.
     */
    fun subjectGames(subjectId: String): Owned<app.granger.home.GameModel> = owned { scope ->
        var set: app.granger.core.library.SubjectSet? = null
        app.granger.home.GameModel(
            scope = scope,
            indexes = {
                val s = set ?: (indexes ?: QidIndexes(emptyList())).subjectSet(subjectId).also { set = it }
                volunteeredTimes().only { s.isMain(it) }
            },
            picture = { p, a -> volunteeredPicture(p, a) },
            store = DefaultsGameStore("games_$subjectId"),
            mixed = false,
        )
    }

    /** Where Wander starts in a subject, or in one of its rooms ([facetId]): one of its articles at random. */
    suspend fun wanderStart(subjectId: String, facetId: String?): app.granger.explore.ExploreArticle? {
        val q = indexes ?: return null
        val pool = if (facetId != null) q.topInFacet("$subjectId.$facetId", WANDER_POOL) else q.topIn(subjectId, WANDER_POOL, mainOnly = true)
        return pool.filterNot { contentFilter.hides(it.packId, it.path) }.takeIf { it.isNotEmpty() }?.random()?.let { app.granger.explore.ExploreArticle(it.packId, it.path, app.granger.explore.titleOf(it.path)) }
    }

    /** Explore's broad topics and the quilt read through the topic source. */
    suspend fun around(packId: String, path: String, plates: List<String>): app.granger.topic.QuiltAround =
        topics.around(packId, path, plates.map { packId to it })

    private fun <T : Any> owned(make: (CoroutineScope) -> T): Owned<T> {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        return Owned(make(scope), scope)
    }
}

/** A model with the scope it runs in: Swift closes it when its screen goes. */
class Owned<T : Any>(val model: T, private val scope: CoroutineScope) {
    fun close() = scope.coroutineContext[kotlinx.coroutines.Job]?.cancel() ?: Unit
}

/** A view model kept in its own store, so Swift can clear it (its scope ends) when its screen goes. */
class OwnedViewModel<T : androidx.lifecycle.ViewModel>(make: () -> T) {
    private val store = androidx.lifecycle.ViewModelStore()
    val model: T = make().also { store.put("model", it) }
    fun close() = store.clear()
}

/** Where the reader has been in Explore, in NSUserDefaults as JSON (Android keeps it in a prefs file). */
class DefaultsSeenStore(private val key: String) : app.granger.explore.SeenStore {
    private val d = platform.Foundation.NSUserDefaults.standardUserDefaults
    private val flow = MutableStateFlow(load())
    override val state: StateFlow<app.granger.explore.Seen> get() = flow

    private fun load(): app.granger.explore.Seen = runCatching {
        val o = kotlinx.serialization.json.Json.parseToJsonElement(d.stringForKey(key) ?: return app.granger.explore.Seen()) as kotlinx.serialization.json.JsonObject
        val on = (o["on"] as? kotlinx.serialization.json.JsonPrimitive)?.content != "false"
        val first = (o["first"] as? kotlinx.serialization.json.JsonObject).orEmpty().mapValues { (it.value as kotlinx.serialization.json.JsonPrimitive).content.toLong() }
        app.granger.explore.Seen(on, first)
    }.getOrDefault(app.granger.explore.Seen())

    private fun save(s: app.granger.explore.Seen) {
        flow.value = s
        val json = kotlinx.serialization.json.buildJsonObject {
            put("on", kotlinx.serialization.json.JsonPrimitive(s.on))
            put("first", kotlinx.serialization.json.buildJsonObject { s.first.forEach { (k, v) -> put(k, kotlinx.serialization.json.JsonPrimitive(v)) } })
        }
        d.setObject(json.toString(), key)
    }

    override fun mark(kind: app.granger.explore.SeenKind, id: String) {
        val s = flow.value
        if (s.on && !s.has(kind, id)) save(s.copy(first = s.first + (app.granger.explore.Seen.key(kind, id) to app.granger.screens.nowMillis())))
    }
    override fun setOn(on: Boolean) = save(flow.value.copy(on = on))
    override fun clear() = save(flow.value.copy(first = emptyMap()))
}

/** Rounds played, the level and best runs in NSUserDefaults (Android keeps them in its prefs). */
class DefaultsGameStore(private val prefix: String) : app.granger.home.GameStore {
    private val d = platform.Foundation.NSUserDefaults.standardUserDefaults
    override var played: Int
        get() = d.integerForKey("$prefix.played").toInt()
        set(v) = d.setInteger(v.toLong(), "$prefix.played")
    override var level: Int
        get() = d.integerForKey("$prefix.level").toInt()
        set(v) = d.setInteger(v.toLong(), "$prefix.level")
    override fun best(kind: app.granger.home.GameKind): Int = d.integerForKey("$prefix.best.${kind.name}").toInt()
    override fun setBest(kind: app.granger.home.GameKind, score: Int) = d.setInteger(score.toLong(), "$prefix.best.${kind.name}")
}

/** Topic pages, Explore and Home read through this (Plan I3 Tasks 7–9). */
private const val TOPIC_LEAD_CHARS = 420
/** Wander's start pool in a subject or room (Android's SubjectViewModel.WANDER_POOL). */
private const val WANDER_POOL = 300

enum class Mark { ON_PHONE, ON_PHONE_SHORTER, SAVED, NONE }
enum class IndexKind { QID, TIME }

/** One file of the online index set: where to fetch it, its sha256 and size, and the name to keep it under. */
data class IndexFile(val kind: IndexKind, val source: String, val url: String, val sha256: String, val sizeBytes: Long, val fileName: String)

/** An index file on the phone, to open. */
data class OpenIndex(val kind: IndexKind, val source: String, val path: String)
