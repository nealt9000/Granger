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
import kotlinx.coroutines.launch
import app.granger.core.library.isBookPack
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import app.granger.core.library.withoutHidden
import app.granger.core.text.hex
import app.granger.core.text.sha1
import kotlinx.io.buffered
import kotlinx.io.files.SystemFileSystem
import kotlinx.coroutines.flow.distinctUntilChanged

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
    /** Opens a pack file by its path: CoreKiwix through Swift's KiwixArchive (Plan I4 Task 5). */
    private val opener: (path: String) -> app.granger.core.zim.ZimOpenResult = { app.granger.core.zim.ZimOpenResult.Failed("no pack reader") },
    /** Where packs live: Documents/Packs, shown in the Files app (Plan I4 builder's call 2). */
    private val packsDir: String = "$dir/Packs",
    /** Whether the network now is Wi-Fi (unmetered), and whether any is up: the download queue's network rule. */
    private val unmetered: () -> Boolean = { true },
    private val connected: () -> Boolean = { true },
    /** Big downloads on Wi-Fi only (Settings; on by default, as Android's). */
    private val wifiOnly: () -> Boolean = { true },
    /**
     * Where books live (Plan I5 Task 5): Documents/Books, shown in the Files app as packs are, since books are yours
     * (Android keeps them in its files folder on each volume, `books/`). Imports go in `imports/`, pack copies in
     * `packs/`, `gutenberg/`, `nautilus/` and `kiwix/`.
     */
    private val booksDir: String = "$dir/books",
    /** Page 1 of a PDF as a PNG, for its cover (PDFKit from Swift). */
    private val pdfCovers: app.granger.core.library.PdfCovers = app.granger.core.library.PdfCovers.NONE,
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

    // ---- Packs (Plan I4 Task 5): the shared PackLibrary over Documents/Packs, as Android's RoomLibrary over its folders.

    val packLibrary = app.granger.core.library.PackLibrary(database, opener, { listOf(packsDir) }, now = { app.granger.screens.nowMillis() })

    /** The library's packs, every status, by title. */
    val packs: kotlinx.coroutines.flow.Flow<List<app.granger.core.library.Pack>> get() = packLibrary.packs

    /** Looks at Documents/Packs again: new files are opened and listed, gone ones marked missing. */
    suspend fun refreshPacks() {
        packLibrary.refresh()
        runCatching { attachBookManifests(downloads.records.first()) }
    }

    // ---- Downloads (Plan I4 Task 6): the shared queue, records and manager; the transfer is a URLSession background session.

    /** Where downloads go: Documents, with packs in Packs/ and what follows them under a hidden .granger/ (builder's call 2). */
    val downloadRoot: String = packsDir.trimEnd('/').substringBeforeLast('/')

    init {
        app.granger.core.downloads.DownloadLayout.packs = packsDir.trimEnd('/').substringAfterLast('/')
        app.granger.core.downloads.DownloadLayout.companions = ".granger/"
    }

    private val downloadDb = app.granger.core.downloads.openDownloadDatabase("$dir/downloads.db")
    private val downloadDao = downloadDb.downloadDao()

    /** The app's completion handler for the background session's events (UIApplication hands one to the app delegate). */
    var backgroundEventsDone: (() -> Unit)? = null

    val transfer = app.granger.core.downloads.UrlSessionTransfer(eventsDone = {
        val done = backgroundEventsDone
        backgroundEventsDone = null
        if (done != null) kotlinx.coroutines.MainScope().launch { done() }
    })

    private val queuePrefs = DefaultsQueuePrefs("downloads")

    private val runner = app.granger.core.downloads.DownloadRunner(downloadDao, transfer, onInstalled = { record ->
        packLibrary.fileReplaced(record.filePath)
        packLibrary.refresh()
    })

    private val queue = app.granger.core.downloads.DownloadQueue(downloadDao, runner, queuePrefs, unmetered, connected = connected)

    private val scheduler = object : app.granger.core.downloads.DownloadScheduler {
        private var job: kotlinx.coroutines.Job? = null
        override fun ensure(unmeteredOnly: Boolean, offline: Boolean) {
            if (!queue.wake()) return
            if (job?.isActive == true) return
            job = scope.launch {
                // While the app runs, the queue works through its records, looking again when a retry or Wi-Fi is due.
                while (true) {
                    when (val rest = queue.drain()) {
                        is app.granger.core.downloads.QueueRest.Later -> kotlinx.coroutines.delay(rest.delayMillis.coerceIn(2_000, 60_000))
                        else -> break
                    }
                }
            }
        }
        override suspend fun stopRun(itemId: String) = queue.stopRun(itemId)
        override suspend fun stopQueue() = queue.stopRun(null)
        override fun isRunning(itemId: String) = queue.isRunning(itemId)
    }

    private val unmeteredNow = MutableStateFlow(unmetered())

    /** The download manager the screens use (Get more, the downloads card, Outfitting), as Android's DownloadManager. */
    val downloads: app.granger.core.downloads.Downloads = app.granger.core.downloads.DownloadManager(
        downloadDao, scheduler, queuePrefs, queue.progress, unmeteredNow,
    )

    private val sidecars = app.granger.core.downloads.Sidecars(downloads, wifiOnly)

    /** The queue as the downloads card shows it: what downloads now, what waits, how much, how fast and how long. */
    val queueSummary: kotlinx.coroutines.flow.Flow<app.granger.core.downloads.QueueSummary> =
        kotlinx.coroutines.flow.combine(downloads.records, downloads.live) { records, live ->
            app.granger.core.downloads.DownloadQueueSummary.of(records, live, app.granger.screens.nowMillis(), mapOf(downloadRoot to app.granger.core.downloads.freeBytesAt(downloadRoot)))
        }

    /** Free bytes where downloads go. */
    fun freeBytes(): Long = app.granger.core.downloads.freeBytesAt(downloadRoot)

    /** Swift tells the queue the network changed (NWPathMonitor). */
    fun networkChanged() {
        unmeteredNow.value = unmetered()
        scheduler.ensure(false)
    }

    /**
     * At start: the background session reconnected (tasks the system finished while the app was gone deliver now),
     * records left running go back to waiting and the queue starts; sidecars follow the packs whenever the records change.
     */
    fun startDownloads() {
        transfer.connect()
        scope.launch { runCatching { downloads.recover() } }
        scope.launch {
            downloads.records.collect { records ->
                runCatching { sidecars.reconcile(catalog, records) }
                runCatching { attachBookManifests(records) }
            }
        }
    }

    private val downloadPrefs = DefaultsDownloadPrefs("downloads")

    /** The one place downloads go on the iPhone: Documents (its free space and Granger's share of it). */
    @OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
    private val volumes = app.granger.core.downloads.DownloadVolumes {
        val fm = platform.Foundation.NSFileManager.defaultManager
        val attrs = fm.attributesOfFileSystemForPath(downloadRoot, null)
        val total = (attrs?.get(platform.Foundation.NSFileSystemSize) as? platform.Foundation.NSNumber)?.longLongValue ?: 0L
        val granger = kotlinx.coroutines.runBlocking { downloads.records.first() }
            .filter { it.state == app.granger.core.downloads.DownloadState.INSTALLED }.sumOf { it.sizeBytes }
        listOf(app.granger.core.downloads.DownloadVolume(downloadRoot, "This iPhone", false, freeBytes(), total, granger))
    }

    /**
     * Get more's source screen for [scope] (Plan I4 Task 6): the shared SourceViewModel Android's Get more runs, over the
     * iPhone's catalog, queue, packs and Documents. [domain] and [family] narrow it as Android's links do.
     */
    fun source(scope: app.granger.sources.CatalogScope, domain: String?, family: String?, bookSet: String?): OwnedViewModel<app.granger.sources.SourceViewModel> =
        OwnedViewModel {
            app.granger.sources.SourceViewModel(
                scope = scope, catalog = catalogSource, downloads = downloads, library = packLibrary, volumes = volumes,
                prefs = downloadPrefs, appScope = scope_, removeBook = { },
                // The iPhone runs no on-device model yet (Ask is off in every build; voices come in I5).
                canRun = { false }, askOn = false, meaningSearch = false,
                countsDocuments = { pack -> pack.isBookPack },
                io = app.granger.screens.ioDispatcher, domain = domain, family = family,
                knowledge = kotlinx.coroutines.flow.flowOf(languages()), voiceTitle = null, bookSet = bookSet,
            )
        }

    private val scope_: CoroutineScope get() = scope

    // ---- Your sources (Plan I4 Task 7): Explore's list of what the library reads, as Android's Browse lists it.

    /**
     * The packs by knowledge area (the catalog entry's readiness domain, in the catalog's order), then Other, then map
     * files last; book packs are left out (their way in is their books). Each with how many PDFs and EPUBs it holds.
     * Blocking: opens each pack to read its counts.
     */
    fun yourSources(): List<SourceGroup> {
        val packs = kotlinx.coroutines.runBlocking { packLibrary.packs.first() }.filterNot { it.isBookPack }
        val c = catalog
        val domains = c?.readiness?.domains.orEmpty()
        fun domainOf(p: app.granger.core.library.Pack): String? =
            (c?.packs?.firstOrNull { it.zimUuid == p.id } ?: c?.packs?.firstOrNull { p.name.isNotBlank() && it.zimName == p.name })?.domain?.ifBlank { null }
        fun row(p: app.granger.core.library.Pack) = SourceRow(
            p, files = if (p.status == app.granger.core.library.PackStatus.OK) packLibrary.archive(p.id)?.metadata?.counter?.let(app.granger.core.library.PackFiles::count) ?: 0 else 0,
            map = p.name.startsWith("maps_"),
        )
        val (maps, rest) = packs.partition { it.name.startsWith("maps_") }
        val known = domains.mapTo(HashSet()) { it.id }
        val language = languages().firstOrNull() ?: "en"
        val byDomain = rest.groupBy { domainOf(it)?.takeIf { d -> d in known } ?: "other" }
        return (domains.map { d -> SourceGroup(d.id, d.title(language), byDomain[d.id].orEmpty().sortedBy { it.title.lowercase() }.map(::row)) } +
            SourceGroup("other", "Other", byDomain["other"].orEmpty().sortedBy { it.title.lowercase() }.map(::row)) +
            SourceGroup("map-files", "Maps", maps.sortedBy { it.title.lowercase() }.map(::row))
            ).filter { it.rows.isNotEmpty() }
    }

    /** A pack's PDFs and EPUBs, A–Z (Files in this pack); null when it can't be listed. Blocking: walks the whole ZIM. */
    fun packFiles(packId: String): List<app.granger.core.library.PackFile>? = packLibrary.archive(packId)?.let(app.granger.core.library.PackFiles::list)

    /** Removes a pack: its file, row, bookmarks and places (Android's Remove in Browse). */
    suspend fun removePack(packId: String): Boolean = packLibrary.remove(packId)

    /**
     * Which packs are book collections (Android's BookManifests.attach, Plan KB2): a pack whose installed `.gbooks`
     * sidecar passes its check against the pack's checksum gets that manifest; a pack with none that is plainly a
     * container of PDFs and EPUBs is listed by its files; any other keeps none.
     */
    suspend fun attachBookManifests(records: List<app.granger.core.downloads.DownloadRecord>) {
        val installed = records.filter { it.state == app.granger.core.downloads.DownloadState.INSTALLED }
        val byItem = installed.associateBy { it.itemId }
        val manifests = installed.filter { app.granger.core.downloads.SidecarKind.ofItem(it.itemId) == app.granger.core.downloads.SidecarKind.BOOK_MANIFEST }
            .mapNotNull { m ->
                val parent = app.granger.core.downloads.SidecarKind.BOOK_MANIFEST.parentOf(m.itemId)?.let(byItem::get) ?: return@mapNotNull null
                if (!app.granger.core.library.BookManifest.verify(m.filePath, parent.hash)) return@mapNotNull null
                parent.filePath to m.filePath
            }.toMap()
        val dao = database.packDao()
        for (pack in packLibrary.packs.first()) {
            val want = manifests[pack.filePath] ?: run {
                if (pack.status != app.granger.core.library.PackStatus.OK) return@run pack.bookManifest
                // Gutenberg and nautilus packs are book packs by their scraper; only a plain Kiwix website can be a container.
                if (pack.copy(bookManifest = null).isBookPack) return@run null
                val counter = packLibrary.archive(pack.id)?.metadata?.counter.orEmpty()
                if (app.granger.core.library.BookFiles.isContainer(counter)) app.granger.core.library.Pack.BOOK_FILES else null
            }
            if (want != pack.bookManifest) dao.setBookManifest(pack.id, want)
        }
    }

    /** A book pack's books (Gutenberg, nautilus, a manifest's or a container's files), in its own order; null otherwise. */
    fun packBooks(packId: String): List<app.granger.core.library.PackBookEntry>? {
        val pack = kotlinx.coroutines.runBlocking { packLibrary.packs.first() }.firstOrNull { it.id == packId } ?: return null
        val archive = packLibrary.archive(packId) ?: return null
        return app.granger.core.library.PackBookList.list(pack, archive)
    }

    /** The book packs on this iPhone (shown as collections of their books; reading them comes with the Library, I5). */
    fun bookPacks(): List<app.granger.core.library.Pack> =
        kotlinx.coroutines.runBlocking { packLibrary.packs.first() }.filter { it.isBookPack && it.status == app.granger.core.library.PackStatus.OK }.sortedBy { it.title.lowercase() }

    /**
     * Search's Best matches over the packs (Plan SQ, I4 Task 9): Ask's keyword retrieval ranked as Android ranks it, one
     * row per article (a pack family's copies are one row). Keyword-only: search by meaning waits for an embedder here.
     */
    private val bestMatches by lazy {
        app.granger.core.research.BestMatches(
            finder = app.granger.core.library.PackPassageFinder(library = packLibrary),
            dense = null, embed = null,
            articleKey = {
                val families = packLibrary.packs.first().filter { it.name.isNotBlank() }.associate { it.id to app.granger.core.library.TitleMatch.family(it.name) }
                ({ p: app.granger.core.research.Passage ->
                    (if (p.kind == app.granger.core.research.SourceKind.ARTICLE) families[p.sourceId] ?: p.sourceId else p.sourceId) to p.location
                })
            },
        )
    }

    /** The Best matches for [query] over the packs; empty with no pack. */
    suspend fun best(query: String): List<app.granger.core.research.BestMatches.Match> =
        if (packLibrary.packs.first().none { it.status == app.granger.core.library.PackStatus.OK }) emptyList()
        else runCatching { bestMatches.find(query).matches }.getOrDefault(emptyList())

    /**
     * Search over the packs (titles, near titles, full text) and the shelf's books (titles, authors and their words,
     * Plan I5 Task 5), in the library's languages, as Android's.
     */
    val packSearch: app.granger.core.library.PackSearch by lazy {
        app.granger.core.library.PackSearch(
            packLibrary, shelf = { bookshelf.books.first() },
            collectionOf = { book, packs -> app.granger.core.library.Filing.defaultName(app.granger.core.library.Filing.defaultKey(book), book, packs) },
            languages = { languages().toSet() }, bookIndex = bookIndex,
        )
    }

    // ---- Books (Plan I5 Task 5): the shared shelf over the iPhone's library database, as Android's GrangerApp wires it.

    private val coversDir = "$dir/covers"

    /** The shelf: imports, downloads, pack documents and book packs' books, with places and marks. */
    val bookshelf: app.granger.core.library.BookshelfStore = app.granger.core.library.BookshelfStore(
        database, { listOf(booksDir) }, { "$booksDir/imports" }, coversDir,
        io = app.granger.screens.ioDispatcher, now = { app.granger.screens.nowMillis() }, pdfCovers = pdfCovers,
    )

    /** The books' words, for Search's Books and Ask's passages. */
    val bookIndex = app.granger.core.library.BookIndex(database, app.granger.screens.ioDispatcher)

    /** The rail's open items (books open as tabs, as on Android: the pack id is the book's id, the path empty). */
    val sessions: app.granger.core.library.ReadingSessions = app.granger.core.library.RoomReadingSessions(database) { app.granger.screens.nowMillis() }

    /** How books look (global), in UserDefaults; Swift tells it when the app's theme changes ([ReadingPrefsStore.setAppNight]). */
    val readingPrefs: app.granger.ebook.ReadingPrefsStore = defaultsReadingPrefs()

    private val packDocuments = IosPackDocuments(
        bookshelf, { packLibrary.archive(it) }, { "$booksDir/packs" }, app.granger.screens.ioDispatcher, { app.granger.core.downloads.freeBytesAt(it) },
    )

    private val gutenbergBooks = app.granger.core.library.PackBooks(
        database, app.granger.core.library.GutenbergSource, { packLibrary.archive(it) }, { "$booksDir/gutenberg" }, coversDir,
        app.granger.screens.ioDispatcher, { app.granger.screens.nowMillis() }, pdfCovers, ::copyOutOfPack,
    )
    private val nautilusBooks = app.granger.core.library.NautilusPackBooks(
        database, { packLibrary.archive(it) }, { "$booksDir/nautilus" }, coversDir,
        app.granger.screens.ioDispatcher, { app.granger.screens.nowMillis() }, pdfCovers, ::copyOutOfPack,
    )
    private val manifestBooks = app.granger.core.library.PackBooks(
        database, app.granger.core.library.ManifestSource, { packLibrary.archive(it) }, { "$booksDir/kiwix" }, coversDir,
        app.granger.screens.ioDispatcher, { app.granger.screens.nowMillis() }, pdfCovers, ::copyOutOfPack,
    )
    private val packBookSources = listOf(gutenbergBooks, nautilusBooks, manifestBooks)

    /** A link on a book pack's own page (or a row of its list) opens the pack's row for that book (Plan KB Decision 4). */
    private val packBookLinks = app.granger.core.library.PackBookLinks({ packLibrary.packs.first() }, packBookSources)

    /** The shelf's books, every one (hidden pack documents too), as the database has them. */
    val books: kotlinx.coroutines.flow.Flow<List<app.granger.core.library.Book>> get() = bookshelf.books

    suspend fun book(id: String): app.granger.core.library.Book? = bookshelf.book(id)

    /**
     * At start (Android's startBookWork and followGutenberg): the shelf looks at its folders again (files gone are
     * marked, files dropped into Documents/Books/imports are added), the book packs' rows follow the packs, and new
     * books' words are indexed. Off the main thread; a failure waits for the next launch.
     */
    fun startBooks() {
        scope.launch {
            runCatching { bookshelf.refresh() }
            runCatching { bookIndex.indexPending() }
        }
        scope.launch {
            packLibrary.packs.map { packs -> packs.map { Triple(it.id, it.status, it.bookManifest) } }.distinctUntilChanged().collect {
                val all = packLibrary.packs.first()
                for (source in packBookSources) runCatching { source.sync(all) }
                if (nautilusBooks.adopted > 0) runCatching { bookIndex.indexPending() }
            }
        }
    }

    /**
     * Imports the file at [path] (named [displayName] where it came from) into the shelf, copied in as Android does;
     * its words are indexed after. What happened, for Swift to say.
     */
    suspend fun importBook(path: String, displayName: String): app.granger.core.library.ImportResult {
        val result = bookshelf.importFrom(displayName) { SystemFileSystem.source(kotlinx.io.files.Path(path)).buffered() }
        if (result is app.granger.core.library.ImportResult.Imported) scope.launch { runCatching { bookIndex.indexPending() } }
        return result
    }

    /**
     * The book [id] ready to open: its file there, or brought back (a pack document copied out of its pack again, a book
     * pack's book copied out on its first open, then indexed); null when it can't be had.
     */
    suspend fun openableBook(id: String): app.granger.core.library.Book? {
        val book = bookshelf.book(id) ?: return null
        if (kotlinx.io.files.SystemFileSystem.metadataOrNull(kotlinx.io.files.Path(book.filePath))?.isRegularFile == true) return book
        val restored = packDocuments.restore(book) || packBookSources.any { runCatching { it.restore(book) }.getOrDefault(false) }
        if (!restored) return null
        scope.launch { runCatching { bookIndex.indexPending() } }
        return bookshelf.book(id)
    }

    /**
     * A PDF or EPUB in a pack, as Android's `openPackDocument`: a book pack's file is its listed row; an EPUB in another
     * pack is a Gutenberg row when it is one; any PDF is copied out as a pack document. Null `book` with [problem] when it
     * can't be opened (an EPUB a pack doesn't list: [notListed]).
     */
    suspend fun openPackDocument(packId: String, path: String, linkText: String): PackDocumentResult {
        packBookLinks.bookFor(packId, path)?.let { return PackDocumentResult(it, null, false) }
        if (path.substringBefore('?').endsWith(".epub", ignoreCase = true)) {
            return gutenbergBooks.bookFor(packId, path)?.let { PackDocumentResult(it, null, false) } ?: PackDocumentResult(null, null, true)
        }
        return when (val opened = packDocuments.open(packId, path, linkText)) {
            is PackDocumentOpened.Ready -> PackDocumentResult(opened.book, null, false)
            is PackDocumentOpened.Failed -> PackDocumentResult(null, opened.problem, false)
        }
    }

    /** The book is open (Android shows it in a rail tab): its tab is made or brought forward. */
    suspend fun bookOpened(book: app.granger.core.library.Book) {
        sessions.openTab(book.id, "", book.title)
    }

    /** The e-book reader's shared model ([EbookReaderModel.openBook] starts it); [close] clears it. */
    fun ebookReader(): OwnedViewModel<app.granger.ebook.EbookReaderModel> =
        OwnedViewModel { app.granger.ebook.EbookReaderModel(bookshelf, readingPrefs, scope) }

    /** Saves the reader's place in [bookId]: Readium's locator JSON, as Android stores it. */
    suspend fun savePosition(bookId: String, locatorJson: String, totalProgression: Double, chapterTitle: String) =
        bookshelf.savePosition(bookId, locatorJson, totalProgression, chapterTitle)

    suspend fun position(bookId: String): app.granger.core.library.BookPosition? = bookshelf.position(bookId)

    /**
     * What Define does for a word now (Plan I5 call 8): the newest Wiktionary pack, else the system dictionary when
     * [systemDictionary] can define, else nothing.
     */
    suspend fun dictionaryChoice(systemDictionary: Boolean): app.granger.ebook.DictionaryChoice =
        app.granger.ebook.Dictionary.choose(packLibrary.packs.first(), systemDictionary)

    /** [word]'s Wiktionary entry for a book in [bookLanguage] (Plan L3), in [packId] when nothing better is installed. */
    suspend fun define(packId: String, word: String, bookLanguage: String?): app.granger.ebook.DictionaryEntry? =
        app.granger.ebook.Dictionary.entry(packLibrary, packId, app.granger.ebook.Dictionary.normalise(word), bookLanguage, languages())

    /** Any source's archive: Wikipedia online by its language, else an installed pack. Blocking. */
    fun archive(packId: String): app.granger.core.zim.ZimArchive? = langOf(packId)?.let { wikipedia(it) } ?: packLibrary.archive(packId)

    /** A pack's Wikipedia language (`wikipedia_en_top_maxi` → `en`), or an online source's; null for another pack. */
    fun languageOf(packId: String): String? = langOf(packId) ?: runBlockingPack(packId)?.name?.let { WIKIPEDIA_LANGUAGE.find(it)?.groupValues?.get(1) }

    /** A pack's title for a screen's bar (Wikipedia online's sources are "Wikipedia"). Blocking. */
    fun packTitle(packId: String): String = if (langOf(packId) != null) "Wikipedia" else runBlockingPack(packId)?.title.orEmpty().ifBlank { "Wikipedia" }

    private fun runBlockingPack(packId: String) = kotlinx.coroutines.runBlocking { packLibrary.packs.first().firstOrNull { it.id == packId } }

    /** A pack article's card from its HTML (Android's ArticleCards.card for a pack): title, lead and plate picture. */
    private fun packCard(packId: String, path: String, sentences: Int, maxChars: Int): Triple<String, String, String?>? {
        val archive = packLibrary.archive(packId) ?: return null
        val resolved = archive.resolvePath(path) ?: return null
        val item = archive.read(resolved)?.takeIf { it.mimeType.startsWith("text/html") } ?: return null
        val html = app.granger.home.PlateRule.head(item.bytes.decodeToString())
        val lead = app.granger.home.LeadLinks.parse(html, resolved, sentences, maxChars)
        val picture = if (contentFilter.noPicture(packId, resolved)) null else thumbnail(archive, resolved, html)
        return Triple(lead.title.ifBlank { resolved.substringAfterLast('/').replace('_', ' ') }, lead.summary, picture)
    }

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

    // ---- Ready offline (Plan I4 Task 8), as Android's GrangerApp.readyOffline: the last month's reads, where each can be
    // read from, its subject, and what would fill the gaps.

    /**
     * Ready offline for [reads] (pack id, path; newest first, the last 30 days, history never leaves the phone): null
     * with nothing read lately. Each keep is costed at Save pictures' or Text only's size; the Top pack and one subject
     * pack per subject the phone lacks are the offers.
     */
    suspend fun readyOffline(reads: List<ArticleRef>): app.granger.online.ReadyOfflineUi? {
        val recent = reads.distinctBy { it.packId to it.path }.take(READY_RECENT)
        if (recent.isEmpty()) return null
        val q = indexes
        val counted = recent.mapNotNull { r ->
            val lang = langOf(r.packId) ?: return@mapNotNull null
            val qid = q?.let { it.qidOf(r.packId, r.path) ?: it.qidOfTitle(r.path, lang) }
            app.granger.online.RecentRead(
                r.packId, r.path, availability.of(r.packId, r.path), qid?.let { q.subjectsOf(it).firstOrNull() },
                inPack = q?.qidOf(r.packId, r.path) != null,
            )
        }
        if (counted.isEmpty()) return null
        val each = if (savePictures()) app.granger.online.RoomOffers.EACH_WITH_PICTURES else app.granger.online.RoomOffers.EACH_TEXT
        val lang = languages().firstOrNull() ?: "en"
        val installed = packLibrary.packs.first().mapTo(HashSet()) { it.name }
        val packs = catalog?.packs.orEmpty()
        val top = packs.filter { it.id.startsWith("wikipedia_${lang}_top_") && it.zimName !in installed }
            .minByOrNull { it.sizeBytes }?.let { app.granger.online.PackOffer(it.id, it.title, it.sizeBytes) }
        val subjectPacks = packs.filter { it.zimName !in installed && !it.id.startsWith("wikipedia_${lang}_top") }
            .mapNotNull { e -> app.granger.explore.Subject.ofDomain(e.domain)?.let { e to it.id } }
            .groupBy { it.second }.mapNotNull { (_, es) -> es.minByOrNull { it.first.sizeBytes } }
            .map { (e, subject) -> app.granger.online.PackOffer(e.id, e.title, e.sizeBytes) to subject }
        return app.granger.online.ReadyOffline.of(counted, each, top, app.granger.online.ReadyOffline.SUBJECTS, subjectPacks)
    }

    /** Keeps the online-only articles you read lately (Ready offline's suggestion: language and title), one after another. */
    fun keepTheRead(articles: List<ArticleRef>) {
        scope.launch { for (a in articles) runCatching { keeper.keep(a.packId, a.path) } }
    }

    // ---- Outfitting (Plan I4 Task 8): Readiness's report (the knowledge domains, gaps, storage by source) over the
    // catalog, the packs and the download ledger, as Android's ReadinessViewModel computes it; books join in I5.

    /** The readiness report now. Blocking reads of the packs and records. */
    suspend fun readinessReport(): app.granger.readiness.ReadinessReport = app.granger.readiness.ReadinessModel.report(
        catalog, packLibrary.packs.first(), emptyList(), downloads.records.first(), app.granger.screens.todayEpochDay(),
        languages(), app.granger.screens.phoneLanguage(),
    )

    /** Updates for kept articles (Plan O5 Task 4): checked once a day while online, when Outfitting shows. */
    val keptUpdates = app.granger.online.KeptUpdates(
        revisions = { lang, titles -> client.revisions(lang, titles) },
        lastCheck = { platform.Foundation.NSUserDefaults.standardUserDefaults.doubleForKey("online.keptUpdatesChecked").toLong() },
        setLastCheck = { platform.Foundation.NSUserDefaults.standardUserDefaults.setDouble(it.toDouble(), "online.keptUpdatesChecked") },
    )

    /** Checks the kept articles for newer versions when a day has passed (blocking; off the main thread). */
    suspend fun checkKeptUpdates() {
        val all = database.keptArticleDao().all()
        kotlinx.coroutines.withContext(app.granger.screens.ioDispatcher) { keptUpdates.check(all, gateOpen()) }
    }

    /** Re-keeps the kept articles that have a newer version (Outfitting's Update / Update all). */
    suspend fun updateKept(articles: List<app.granger.core.library.KeptArticle>) {
        for (a in articles) if (keeper.keep(a.lang, a.title) != null) keptUpdates.done(a.lang, a.title)
    }

    /** The articles you kept, newest first. */
    val keptArticles: kotlinx.coroutines.flow.Flow<List<app.granger.core.library.KeptArticle>> get() = kept.articles

        // ---- Onboarding (Plan I4 Task 8): the shared first run, Pick a start and quick wins; the voice step waits for voices (I5).

    /** Whether the first run was finished or skipped once. */
    val onboardingPrefs = DefaultsOnboardingPrefs()

    /**
     * The first run's model, from [startAt] (WELCOME on a first start; OUTFIT from Settings' Outfit for offline). Swift
     * keeps the knowledge language ([setKnowledge]) and the privacy line ([onPrivacySeen]).
     */
    fun onboarding(
        startAt: app.granger.onboarding.Step, phoneLanguages: List<String>, countryCode: String,
        setKnowledge: (List<String>) -> Unit, onPrivacySeen: () -> Unit,
    ): OwnedViewModel<app.granger.onboarding.OnboardingViewModel> {
        val knowledge = MutableStateFlow(languages())
        return OwnedViewModel {
            app.granger.onboarding.OnboardingViewModel(
                catalog = catalogSource, library = packLibrary, downloads = downloads, volumes = volumes, prefs = downloadPrefs,
                askTier = false, canRun = { false }, totalRamBytes = platform.Foundation.NSProcessInfo.processInfo.physicalMemory.toLong(),
                onboardingPrefs = onboardingPrefs, askOn = false, research = false, dense = false, countryCode = countryCode,
                offerVoice = false, io = app.granger.screens.ioDispatcher, now = { app.granger.screens.nowMillis() },
                knowledge = knowledge, setKnowledge = { langs -> knowledge.value = langs; setKnowledge(langs) },
                phoneLanguages = { phoneLanguages }, appLanguage = { app.granger.screens.phoneLanguage() },
                enqueue = { group, items, volume, wifi ->
                    downloads.enqueueGroup(group, items.map { app.granger.core.downloads.downloadSpecOf(it, group) }, volume.root, wifi)
                },
                chooseOnline = true, onPrivacySeen = onPrivacySeen, startAt = startAt,
            )
        }
    }

    // ---- Home's feeds (Plan I4 Task 8): the shared feed engine over the packs (books join in I5).

    private val homeFeedsStore = app.granger.core.library.RoomHomeFeeds(database)

    private val feedDrawer by lazy {
        app.granger.home.FeedDrawer(
        archiveOf = { packLibrary.archive(it.id) },
        chunksOf = { emptyList() },
        bookSearch = { emptyList() },
        librarySearch = packSearch,
        languages = { languages() },
        hides = contentFilter::hides,
        noPicture = contentFilter::noPicture,
        thumbnail = { archive, path, html -> thumbnail(archive, path, html) },
        )
    }

    private val feedSnapshot = packLibrary.packs.map { packs ->
        app.granger.home.LibrarySnapshot(
            packs.filter { it.status == app.granger.core.library.PackStatus.OK }, emptyList(), emptyList(), emptyList(), emptyList(), catalog,
        )
    }

    /** Home's feeds: each feed's cards, Add feed's scopes; the defaults are added once, the first time. */
    fun homeFeeds(): Owned<app.granger.home.HomeFeedsModel> = owned { scope ->
        app.granger.home.HomeFeedsModel(scope, homeFeedsStore, feedDrawer, feedSnapshot, app.granger.screens.ioDispatcher, { app.granger.screens.CivilDate.today() })
            .also { model ->
                val d = platform.Foundation.NSUserDefaults.standardUserDefaults
                scope.launch {
                    model.seedDefaults({ d.boolForKey("home.feedsSeeded") }, { d.setBool(true, "home.feedsSeeded") }, { languages() })
                }
            }
    }

        /** Gets a catalog pack (Ready offline's and Outfitting's offers): queued on this iPhone, under the Wi-Fi rule. */
    suspend fun getPack(id: String): app.granger.core.downloads.EnqueueResult? {
        val item = catalog?.let { c -> (c.packs + c.books).firstOrNull { it.id == id } } ?: return null
        return downloads.enqueue(item, downloadRoot, wifiOnly())
    }

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

    /** The verified catalog, once Swift has it: the topic records' entries come from it, and Get more lists it. */
    var catalog: Catalog? = null
        set(value) {
            field = value
            catalogSource.publish(value)
        }

    /** The catalog as Get more's shared model reads it (Android's CatalogRepository); Swift fetches and verifies it. */
    val catalogSource = IosCatalogSource()

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
            val lang = langOf(packId) ?: return@TopicSource packCard(packId, path, 3, TOPIC_LEAD_CHARS)?.let { (title, summary, picture) ->
                app.granger.topic.TopicHeader(app.granger.core.library.PageTitles.clean(title), app.granger.topic.TopicSource.blurb(summary), picture)
            }
            val s = wikipedia(lang).summary(path) ?: return@TopicSource null
            app.granger.topic.TopicHeader(
                app.granger.core.library.PageTitles.clean(s.title),
                app.granger.topic.TopicSource.blurb(app.granger.home.LeadLinks.summaryOf(s.extract, 3, TOPIC_LEAD_CHARS)),
                s.thumbnail?.let { picture(lang, it) },
            )
        },
        lead = { packId, path ->
            val lang = langOf(packId) ?: return@TopicSource packCard(packId, path, 2, app.granger.home.LeadLinks.MAX_SUMMARY)?.let { (title, summary, picture) ->
                app.granger.ebook.ArticleLead(app.granger.core.library.PageTitles.clean(title), summary, picture)
            }
            val s = wikipedia(lang).summary(path) ?: return@TopicSource null
            val thumb = if (contentFilter.noPicture(packId, path) || (s.qid?.let(contentFilter::noPicture) == true)) null else s.thumbnail?.let { picture(lang, it) }
            app.granger.ebook.ArticleLead(app.granger.core.library.PageTitles.clean(s.title), app.granger.home.LeadLinks.summaryOf(s.extract), thumb)
        },
        times = { times ?: TimeIndexes(emptyList()) },
        qids = { indexes ?: QidIndexes(emptyList()) },
        languageOf = { id -> languageOf(id) },
        packIds = {
            languages().associate { OnlineWikipediaArchive.packName(it) to OnlineWikipediaArchive.packName(it) } +
                packLibrary.packs.first().filter { it.name.isNotBlank() }.associate { it.name to it.id }
        },
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
        // A broad topic's figures (Plan EX D5) from a pack's own pictures, cached as files (Plan I4 Task 8).
        figure = { packId, image -> if (langOf(packId) != null) null else packLibrary.archive(packId)?.let { archivePicture(it, image, whole = false) } },
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
/**
 * The catalog for the shared screens (Plan I4 Task 6). Swift downloads and verifies it (CryptoKit) and hands it over
 * through [publish]; [load] and [refresh] ask Swift for it through [fetch]. As a release build without Ask on Android
 * lists it: only evaluated models, no search vectors (the iPhone runs neither yet).
 */
class IosCatalogSource : app.granger.core.catalog.CatalogSource {
    private val _state = MutableStateFlow(app.granger.core.catalog.CatalogState())
    override val state: StateFlow<app.granger.core.catalog.CatalogState> get() = _state

    /** Swift's fetch (LibraryModel.loadCatalog), started on its own task; it calls [publish] when done. */
    var fetch: (refresh: Boolean) -> Unit = {}

    fun publish(catalog: Catalog?) {
        _state.value = if (catalog == null) _state.value.copy(refreshing = false)
        else app.granger.core.catalog.CatalogState(catalog.forBuild(includeUnevaluatedModels = false, includeVectors = false), app.granger.screens.nowMillis())
    }

    override suspend fun load() { if (_state.value.catalog == null) fetch(false) }

    override suspend fun refresh() {
        _state.value = _state.value.copy(refreshing = true)
        fetch(true)
    }
}

/** One of Your sources' sections: a knowledge area, Other, or Maps. */
data class SourceGroup(val id: String, val title: String, val rows: List<SourceRow>)

/** A pack in Your sources, with how many PDFs and EPUBs it holds (Files in this pack). */
data class SourceRow(val pack: app.granger.core.library.Pack, val files: Int, val map: Boolean)

/** Onboarding's marks in UserDefaults (Android's SharedPreferences "onboarding"). */
class DefaultsOnboardingPrefs : app.granger.onboarding.OnboardingPrefs {
    private val d = platform.Foundation.NSUserDefaults.standardUserDefaults
    override var done: Boolean
        get() = d.boolForKey("onboarding.done")
        set(value) = d.setBool(value, "onboarding.done")
    override var outfitGroup: String?
        get() = d.stringForKey("onboarding.outfitGroup")
        set(value) { if (value == null) d.removeObjectForKey("onboarding.outfitGroup") else d.setObject(value, "onboarding.outfitGroup") }
}

/** Get more's settings in UserDefaults: Wi-Fi only (Settings' own key), the volume (one on the iPhone), ask before large. */
class DefaultsDownloadPrefs(prefix: String) : app.granger.sources.DownloadPrefs, app.granger.core.downloads.QueuePrefs by DefaultsQueuePrefs(prefix) {
    private val d = platform.Foundation.NSUserDefaults.standardUserDefaults
    private val askKey = "$prefix.askBeforeLarge"
    override var wifiOnly: Boolean
        get() = d.objectForKey("wifiOnly")?.let { d.boolForKey("wifiOnly") } ?: true
        set(value) = d.setBool(value, "wifiOnly")
    override var volumePath: String? = null
    override var askBeforeLarge: Boolean
        get() = d.objectForKey(askKey)?.let { d.boolForKey(askKey) } ?: true
        set(value) = d.setBool(value, askKey)
}

/** The queue's own settings (Pause all, where this round began) in UserDefaults, as Android's DownloadPrefs. */
class DefaultsQueuePrefs(private val prefix: String) : app.granger.core.downloads.QueuePrefs {
    private val d = platform.Foundation.NSUserDefaults.standardUserDefaults
    override var paused: Boolean
        get() = d.boolForKey("$prefix.paused")
        set(value) = d.setBool(value, "$prefix.paused")
    override var batchStartedAt: Long
        get() = d.doubleForKey("$prefix.batchStartedAt").toLong()
        set(value) = d.setDouble(value.toDouble(), "$prefix.batchStartedAt")
}

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

/** The recent reads Ready offline counts at most (Android's READY_RECENT). */
private const val READY_RECENT = 60

/** A Wikipedia pack's language in its ZIM name (`wikipedia_en_top_maxi`), as Android's GrangerApp reads it. */
private val WIKIPEDIA_LANGUAGE = Regex("^wikipedia_([a-z]{2,3})_")

enum class Mark { ON_PHONE, ON_PHONE_SHORTER, SAVED, NONE }
enum class IndexKind { QID, TIME }

/** One file of the online index set: where to fetch it, its sha256 and size, and the name to keep it under. */
data class IndexFile(val kind: IndexKind, val source: String, val url: String, val sha256: String, val sizeBytes: Long, val fileName: String)

/** An index file on the phone, to open. */
/** An article by its source and path (for Ready offline's keeps: language and title). */
data class ArticleRef(val packId: String, val path: String)

/** A pack's PDF or EPUB opened: its [book], or why not ([problem], or [notListed] for an EPUB its pack doesn't list). */
data class PackDocumentResult(val book: app.granger.core.library.Book?, val problem: PackDocumentProblem?, val notListed: Boolean)

data class OpenIndex(val kind: IndexKind, val source: String, val path: String)
