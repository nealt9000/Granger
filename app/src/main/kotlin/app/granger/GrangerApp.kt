package app.granger

import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.content.res.Configuration
import android.content.res.Resources
import android.net.Uri
import android.provider.Settings
import app.granger.core.books.PdfSupport
import app.granger.core.ai.DeviceTier
import app.granger.core.ai.DeviceTiers
import app.granger.core.ai.InstalledModels
import app.granger.core.ai.LiteRtLlmEngine
import app.granger.core.ai.LiteRtTextEmbedder
import app.granger.core.ai.LlmBackend
import app.granger.core.ai.ModelCheckStore
import app.granger.core.ai.ModelManager
import app.granger.core.catalog.CatalogRepository
import app.granger.core.catalog.CatalogSource
import app.granger.core.catalog.ModelRole
import app.granger.sources.canRun
import app.granger.core.downloads.AndroidVolumes
import app.granger.core.downloads.DownloadDatabase
import app.granger.core.downloads.DownloadManager
import app.granger.core.downloads.DownloadRecord
import app.granger.core.downloads.DownloadQueue
import app.granger.core.downloads.DownloadRunner
import app.granger.core.downloads.DownloadState
import app.granger.core.downloads.Downloads
import app.granger.core.downloads.DownloadsHost
import app.granger.core.downloads.FileDownloader
import app.granger.core.downloads.ItemKind
import app.granger.core.downloads.SidecarKind
import app.granger.core.downloads.Sidecars
import app.granger.core.downloads.VoiceUnpacker
import app.granger.core.downloads.VolumeSource
import app.granger.core.downloads.file
import app.granger.core.downloads.WorkManagerScheduler
import app.granger.core.downloads.isUnmetered
import app.granger.core.downloads.unmeteredFlow
import app.granger.core.speech.InstalledVoices
import app.granger.core.speech.ReadAddress
import app.granger.core.speech.ReadSource
import app.granger.core.speech.ReadSourceKey
import app.granger.core.speech.SherpaSpeech
import app.granger.core.speech.SystemSpeech
import app.granger.core.speech.VoiceId
import app.granger.ebook.BookReadSource
import app.granger.player.PlayerFocus
import app.granger.reader.ArticleReadSource
import app.granger.reader.PackDocuments
import app.granger.settings.SharedVoicePrefs
import app.granger.settings.VoicePrefs
import android.util.Log
import app.granger.core.library.withoutHidden
import app.granger.core.library.Book
import app.granger.core.library.BookIndex
import app.granger.core.library.BookDetails
import app.granger.core.library.BookOrigin
import app.granger.archive.ArchiveDownloads
import app.granger.archive.ArchivePrefs
import app.granger.archive.FixedFilterArchivePrefs
import app.granger.archive.SharedArchivePrefs
import app.granger.settings.AppLanguages
import app.granger.settings.KnowledgeLanguages
import app.granger.settings.AppearancePrefs
import app.granger.settings.SharedAppearancePrefs
import app.granger.core.archive.ArchiveOrgClient
import app.granger.core.archive.ArchiveQuery
import app.granger.core.library.Bookshelf
import app.granger.core.library.Library
import app.granger.core.library.LibraryDatabase
import app.granger.core.library.LibraryPassageFinder
import app.granger.core.library.LibrarySearch
import app.granger.core.library.MediaIndex
import app.granger.core.library.MediaLibrary
import app.granger.core.library.PlaybackDao
import app.granger.core.library.Playlists
import app.granger.core.library.RoomPlaylists
import app.granger.core.player.MediaControllerPlayerHub
import app.granger.core.player.PlayerHub
import app.granger.core.player.PlayerSettingsKeys
import app.granger.core.library.ReadingSessions
import app.granger.core.library.RoomBookshelf
import app.granger.core.library.RoomLibrary
import app.granger.core.library.RoomMediaLibrary
import app.granger.core.library.ResearchChats
import app.granger.core.library.RoomReadingSessions
import app.granger.core.library.RoomResearchChats
import app.granger.core.library.RailPins
import app.granger.core.library.HomeFeeds
import app.granger.home.HomePrefs
import app.granger.onboarding.OnboardingPrefs
import app.granger.onboarding.SharedOnboardingPrefs
import app.granger.home.SharedHomePrefs
import app.granger.core.library.RoomHomeFeeds
import app.granger.core.library.RoomRailPins
import app.granger.shell.RailHidden
import app.granger.shell.RailOrder
import app.granger.shell.SharedRailHidden
import app.granger.shell.SharedRailOrder
import app.granger.core.library.RoomShelves
import app.granger.core.library.Shelves
import app.granger.core.library.ZimLibrarySearch
import app.granger.core.media.ContentResolverOpener
import app.granger.core.media.FileTagReadGuard
import app.granger.core.media.FolderSource
import app.granger.core.media.MediaStoreSource
import app.granger.core.media.PhoneScanner
import app.granger.media.Artwork
import app.granger.media.AutoBrowseTree
import app.granger.media.PlayResumer
import app.granger.media.SongPlayer
import app.granger.media.MediaDownloader
import app.granger.media.MediaSharing
import app.granger.media.PhoneMediaScans
import app.granger.core.archive.LibriVoxClient
import app.granger.core.media.DownloadedMediaScanner
import app.granger.media.hasAudioPermission
import app.granger.core.zim.KiwixZimOpener
import app.granger.core.research.RankingConfig
import app.granger.core.research.ResearchPipeline
import app.granger.core.research.TopicCentroids
import app.granger.core.zim.ZimResolver
import app.granger.ebook.BookOpener
import app.granger.ebook.ReadingPrefsStore
import app.granger.ebook.ReadiumBookOpener
import app.granger.sources.DownloadPrefs
import app.granger.sources.SharedDownloadPrefs
import app.granger.launch.SessionMarker
import app.granger.library.shelf.LibraryViewPrefs
import app.granger.widget.PlayerWidget
import app.granger.widget.WidgetModel
import app.granger.widget.WidgetModelStore
import app.granger.widget.WidgetUpdater
import androidx.glance.appwidget.updateAll
import app.granger.research.ModelsAdapter
import app.granger.research.installedModelsOf
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import android.os.SystemClock
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import org.readium.r2.shared.publication.Publication
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.io.File
import app.granger.feedback.BuildFacts
import app.granger.feedback.CrashLog
import java.util.Base64
import java.util.concurrent.TimeUnit
import app.granger.core.library.BookIds
import app.granger.core.library.GutenbergBooks
import app.granger.core.library.NautilusBooks
import app.granger.core.library.BookManifests
import app.granger.core.library.ManifestBooks
import app.granger.core.library.ManifestFile
import app.granger.core.library.PackBookLinks
import app.granger.core.library.PackBooks
import app.granger.core.library.bookSource
import app.granger.library.GutenbergCovers
import app.granger.library.GutenbergShelf
import app.granger.core.library.DensePassageFinder
import app.granger.core.library.BookVectors
import kotlinx.coroutines.sync.withLock
import app.granger.core.library.CombinedVectorSources
import app.granger.core.library.DirectoryVectorSources
import app.granger.core.library.ListedVectorSources
import app.granger.core.library.ListedVectors
import app.granger.core.research.SourceKind
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import app.granger.core.research.FictionWeight
import java.util.concurrent.Executors

open class GrangerApp : Application(), DownloadsHost {
    val container: AppContainer by lazy { AppContainer(this) }

    /** Below API 33, the app's resources in the language chosen in Settings (Plan L2 Task 3); null follows the phone. */
    @Volatile private var localized: Resources? = null

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)
        relocalize()
    }

    /** Below API 33: downloads, the widget and view models read the application's resources, so they carry the choice. */
    override fun getResources(): Resources = localized ?: super.getResources()

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // The system has put back the phone's locale and changed the rest (night mode, size); build ours again.
        relocalize()
    }

    /** Builds [localized] again from the stored language choice; a no-op on API 33+, where Android does it. */
    fun relocalize() {
        localized = AppLanguages.localizedResources(baseContext)
    }

    /** The local crash log (Plan F1 part 2); outside the container so a crash while the container is made is caught. */
    val crashLog: CrashLog by lazy { CrashLog(File(filesDir, "crashes"), BuildFacts.ofThisPhone()) }

    override fun onCreate() {
        super.onCreate()
        startCrashLog()
        // Only keeps the context: PdfBox reads its font metrics from assets on the first PDF.
        PdfSupport.init(this)
        if (Features.PLAYER) startMedia()
        startVectorDownloads()
        startGutenbergSync()
        resumeDownloads()
    }

    /**
     * Java crashes are written as they happen, then Android's own handler runs. Native crashes and ANRs are read from
     * the system's exit records on the next start, off the main thread. JVM tests swap this out (`TestGrangerApp`).
     */
    protected open fun startCrashLog() {
        Thread.setDefaultUncaughtExceptionHandler(crashLog.handler(Thread.getDefaultUncaughtExceptionHandler()))
        Thread({ runCatching { crashLog.recordExits(CrashLog.pastExits(this)) } }, "crash-log").start()
    }

    /** Gutenberg packs' books follow their packs (Plan GB). JVM tests swap this out (`TestGrangerApp`). */
    protected open fun startGutenbergSync() = container.followGutenberg()

    /** Search vectors follow their pack or book (Plan R1 Task 10). JVM tests swap this out (`TestGrangerApp`). */
    protected open fun startVectorDownloads() = container.followVectors()

    /** Downloads left queued without a job start again. JVM tests swap this out (`TestGrangerApp`). */
    protected open fun resumeDownloads() = container.resumeDownloads()

    /**
     * The player hub is made here, not on first use: a playback service the system restarts on its own stops at once
     * unless the hub has installed its dependencies. Then the phone scans start (without the audio permission they
     * only mark phone items missing; nothing asks). JVM tests swap this out (`TestGrangerApp`).
     */
    protected open fun startMedia() {
        container.playerHub
        container.phoneMediaScans.start()
        // The home-screen widget follows the player: re-rendered on changes, never on a clock.
        WidgetUpdater(container.playerHub, container.appScope, container.playerFocus) { model ->
            container.widgetModel.value = model
            container.widgetStore.save(model)
            PlayerWidget().updateAll(this)
        }
        // Granger's own downloads: no permission needed. Quiet, on the app scope: a failure never crashes the start.
        container.appScope.launch { container.mediaDownloader.rescanQuietly() }
        container.appScope.launch(Dispatchers.IO) { container.artwork.warm() }
    }

    override val downloadQueue: DownloadQueue get() = container.downloadQueue
    override val downloads: Downloads get() = container.downloads
}

/**
 * Files a finished book download. Never throws: the runner would turn an exception into FAILED for a download whose
 * file is already installed, and a book that didn't register is found by the next start's scan.
 */
internal suspend fun onBookInstalled(
    bookshelf: Bookshelf,
    file: File,
    origin: BookOrigin,
    ref: String,
    details: BookDetails?,
    startIndexing: () -> Unit,
) {
    try {
        if (bookshelf.addDownloaded(file, origin, ref, details) == null) {
            android.util.Log.w("Granger", "Downloaded book $ref is not a readable EPUB or PDF")
            return
        }
        startIndexing()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        android.util.Log.e("Granger", "Filing downloaded book $ref failed", e)
    }
}

/**
 * Files a finished book download: archive.org records with their sidecar's metadata, the rest as Granger books.
 * The sidecar is kept: a Readiness repair re-files the same record later and needs it. It goes when the item is
 * removed or cancelled ([ArchiveDownloads.deleteSidecar]).
 */
internal suspend fun fileBookRecord(bookshelf: Bookshelf, sidecarDir: File, record: DownloadRecord, startIndexing: () -> Unit) {
    val identifier = ArchiveDownloads.identifierOf(record.itemId)
    // A part has its own sidecar (titled for it); the PDF beside a text shares the text's.
    val sidecar = identifier?.takeIf { ArchiveQuery.isValidIdentifier(it) }
        ?.let { ArchiveDownloads.sidecarFile(sidecarDir, ArchiveDownloads.sidecarKeyOf(record.itemId) ?: it) }
    val details = sidecar?.let { ArchiveDownloads.readSidecar(it)?.first }
    onBookInstalled(
        bookshelf, File(record.filePath),
        if (identifier != null) BookOrigin.ARCHIVE_ORG else BookOrigin.GRANGER,
        ArchiveDownloads.bookRefOf(record.itemId) ?: record.itemId,
        if (ArchiveDownloads.isPdfCopy(record.itemId)) details?.let(ArchiveDownloads::pdfCopyDetails) else details,
        startIndexing,
    )
}

/**
 * Whether Research also searches by meaning (Plan R1): the question is embedded (0.12 s on the Pixel 6's GPU) and
 * scanned against the installed packs' and books' vector files, alongside the keyword search, and [Ranking.hybrid]
 * merges the two. On from Plan R5 Task 7: every R5 measurement ran with it. Reranking found passages with the
 * embedder stays off: a query plus 12 passages took 4.2–4.7 s on the GPU (Plan 8 Task 9).
 */
const val DENSE_RETRIEVAL = true

/**
 * Whether this process runs on the Android emulator ("ranchu" is its virtual board, "goldfish" the older one). False
 * in JVM tests, where [android.os.Build] is a stub.
 */
val ON_EMULATOR: Boolean = runCatching { android.os.Build.HARDWARE }.getOrNull()?.let { it == "goldfish" || it.startsWith("ranchu") } ?: false

/**
 * Where the models run: the GPU (the embedder is about twice as fast there as on the CPU on the Pixel 6). A debug build
 * on the emulator uses the CPU, since the emulator has no OpenCL: the eval runs on the Mac that way (Plan R2, 2026-11-04).
 */
val MODEL_BACKEND: LlmBackend = if (BuildConfig.DEBUG && ON_EMULATOR) LlmBackend.CPU else LlmBackend.GPU

/** Where the embedding model runs: with the answer model, [MODEL_BACKEND]. */
val EMBEDDER_BACKEND: LlmBackend = MODEL_BACKEND

/** Hand-wired dependencies; one instance per process. */
class AppContainer(context: Context) {
    /** For writes that must finish even if the screen that started them is gone. */
    val appScope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default +
            CoroutineExceptionHandler { _, e -> android.util.Log.e("Granger", "Background action failed", e) },
    )

    /** `<volume>/Android/data/app.granger/files/packs` on internal storage and each SD card. */
    val packDirs: () -> List<File> = {
        context.getExternalFilesDirs(null).filterNotNull().map { File(it, "packs").apply { mkdirs() } }
    }

    /** `<volume>/Android/data/app.granger/files/books` on internal storage and each SD card. */
    val bookDirs: () -> List<File> = {
        context.getExternalFilesDirs(null).filterNotNull().map { File(it, "books").apply { mkdirs() } }
    }

    private val archiveUserAgent = app.granger.archive.GrangerUserAgent.of(BuildConfig.VERSION_NAME)

    /** Online, Local only or Never (online/offline spec §7, §9); the gate below reads it on every request. */
    val networkPolicy = app.granger.online.NetworkPolicy(context)
    val networkGate = app.granger.core.downloads.NetworkGate(networkPolicy::now)

    /**
     * The app's one HTTP client: every feature that goes online goes through it (Plan O1 Task 1; NetworkAuditTest keeps
     * it so). The gate comes first, so Local only refuses before any connection.
     */
    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        // Finds an HTTP/2 connection the network dropped before a request hangs on it (archive.org, 2026-10-30).
        .pingInterval(30, TimeUnit.SECONDS)
        .addInterceptor(networkGate)
        .apply { if (BuildConfig.DEBUG && BuildConfig.DEV_FILES_URL.isNotEmpty()) addInterceptor(app.granger.online.DevFiles(BuildConfig.DEV_FILES_URL)) }
        // A network interceptor sees each redirect hop, so file downloads carry the agent to the file's server.
        .addNetworkInterceptor(app.granger.archive.GrangerUserAgent(archiveUserAgent))
        .build()

    private val libraryDb = LibraryDatabase.create(context)
    // A removed pack takes its PDFs (pack documents, Plan P2) with it; a new snapshot of it keeps them (Plan KB2).
    val library: Library = RoomLibrary(libraryDb, KiwixZimOpener(context), packDirs, onRemoved = { bookshelf.removePackDocuments(it.id, it.name) })
        .let { room ->
            if (!Features.ONLINE) room
            else app.granger.online.OnlineLibrary(room, if (BuildConfig.DEBUG) networkPolicy.previewNoWikipedia else kotlinx.coroutines.flow.flowOf(false)) { onlineSources.archives() }
        }
    val bookshelf: Bookshelf = RoomBookshelf(
        libraryDb, bookDirs,
        importDir = { File(context.getExternalFilesDir(null) ?: context.filesDir, "books/imports") },
        coversDir = File(context.filesDir, "covers"),
    )
    val bookIndex = BookIndex(libraryDb)

    /** The library's names for the spoken title matcher (Plan A1). */
    val spokenTitles = app.granger.voiceinput.SpokenTitles(libraryDb.libraryNameDao().observe(), appScope)

    /** PDFs inside Kiwix packs, copied out to the internal volume's `books/packs` when tapped (Plan P2). */
    val packDocuments = PackDocuments(bookshelf, library::archive, dir = { File(bookDirs().first(), "packs") })

    /** The books of the installed Project Gutenberg packs (Plan GB), copied out to the internal volume's `books/gutenberg` when opened. */
    val gutenbergBooks = GutenbergBooks(
        libraryDb, library::archive, dir = { File(bookDirs().first(), "gutenberg") }, coversDir = File(context.filesDir, "covers"),
    )

    /**
     * The documents of the installed nautilus packs (Plan KB: Food for Preppers, Water, Prunelle's books), copied out to
     * the internal volume's `books/nautilus` when opened; earlier pack documents of the same files are adopted.
     */
    val nautilusBooks = NautilusBooks(
        libraryDb, library::archive, dir = { File(bookDirs().first(), "nautilus") }, coversDir = File(context.filesDir, "covers"),
    )

    /**
     * The books of the Kiwix packs with a book manifest, or listed by their files' names (Plan KB2: Military Medicine,
     * Survivor Library…), copied out to the internal volume's `books/kiwix` when opened.
     */
    val manifestBooks = ManifestBooks(
        libraryDb, library::archive, dir = { File(bookDirs().first(), "kiwix") }, coversDir = File(context.filesDir, "covers"),
    )

    /** Which packs are manifest packs: [Pack.bookManifest] kept in step with the installed `.gbooks` (Plan KB2). */
    private val bookManifests = BookManifests(libraryDb, library::archive)

    /** A link on a book pack's own page opens the pack's row for it (Plan KB Decision 4). */
    val packBookLinks = PackBookLinks({ library.packs.first() }, listOf(gutenbergBooks, nautilusBooks, manifestBooks))

    /**
     * "Make all searchable…" for book pack collections (Plan GB, Plan KB): runs on the app scope, so leaving the screen
     * doesn't stop it. The copies go to the internal volume, where both sources keep theirs.
     */
    val gutenbergShelf by lazy {
        GutenbergShelf(
            { book -> gutenbergBooks.restore(book) || nautilusBooks.restore(book) || manifestBooks.restore(book) }, bookIndex::indexPending,
            dir = { File(bookDirs().first(), "gutenberg") }, appScope,
        )
    }

    /** Gutenberg covers read from their packs before a book's first open, kept in the cache. */
    val gutenbergCovers = GutenbergCovers({ library.packs.first() }, library::archive, File(context.cacheDir, "gutenberg-covers"))

    /**
     * Brings back a book's missing file for the reader: a pack document copied out again, or a Gutenberg book copied out
     * of its pack, which is then indexed (Plan GB Decision 6: its full text joins Search and Ask after the first open).
     */
    suspend fun restoreBook(book: Book): Boolean =
        packDocuments.restore(book) ||
            (gutenbergBooks.restore(book) || nautilusBooks.restore(book) || manifestBooks.restore(book) || restoreOnline(book))
                .also { if (it) appScope.launch { bookIndex.indexPending() } }

    /**
     * Plan BK: a pack book read online whose saved copy has gone (Saved for now trimmed it) comes down again from the
     * pack file, when online. False for any other book, or offline.
     */
    private suspend fun restoreOnline(book: Book): Boolean {
        if (book.loose != app.granger.core.library.Loose.SAVED || File(book.filePath).isFile) return false
        val zimName = app.granger.core.library.PackBooks.packNameOf(book)
        val entry = catalog.state.value.catalog?.packs?.firstOrNull { p ->
            p.zimName == zimName && p.sidecars.any { it.kind == app.granger.core.catalog.SidecarEntry.GBOOKS }
        } ?: return false
        val remote = packBookReads.collection(entry, canFetchOnline()) ?: return false
        val item = remote.itemOf(book.id) ?: return false
        return readPackBookOnline(entry.id, item.key) != null
    }

    /**
     * Keeps the Gutenberg books' rows following the installed Gutenberg packs (Plan GB Decision 3): synced on start, on
     * an install, a new snapshot, a pack going missing and a removal, off the main thread.
     */
    fun followGutenberg() {
        follow(gutenbergBooks, "Gutenberg")
        // Plan KB: the nautilus packs' documents, the same way; a pack's earlier PDFs are adopted, and indexed once moved.
        follow(nautilusBooks, "Nautilus") { if (nautilusBooks.adopted > 0) bookIndex.indexPending() }
        // Plan KB2: the manifest packs' books, once their manifests are attached.
        followBookManifests()
        follow(manifestBooks, "Kiwix books")
    }

    /**
     * Keeps each pack's [app.granger.core.library.Pack.bookManifest] in step with the installed `.gbooks` sidecars
     * (Plan KB2): on start, and whenever a pack or a download changes.
     */
    private fun followBookManifests() {
        appScope.launch {
            combine(
                downloads.records.map { r -> r.filter { SidecarKind.BOOK_MANIFEST.parentOf(it.itemId) != null }.map { it.itemId to it.state } }
                    .distinctUntilChanged(),
                library.packs.map { packs -> packs.map { Triple(it.id, it.status, it.filePath) } }.distinctUntilChanged(),
            ) { a, b -> a to b }
                .collectLatest {
                    try {
                        val installed = installedSidecars(SidecarKind.BOOK_MANIFEST).mapValues { ManifestFile(it.value.file, it.value.packSha256) }
                        val changed = bookManifests.attach(installed)
                        if (changed > 0) Log.i("Granger", "Book manifests: $changed packs changed")
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.w("Granger", "Couldn't attach the book manifests", e)
                    }
                }
        }
    }

    private fun follow(books: PackBooks, label: String, after: suspend () -> Unit = {}) {
        appScope.launch {
            library.packs
                .map { packs -> packs.filter { it.bookSource == books.source.origin } }
                // A manifest arriving (or changing) re-lists the pack's books (Plan KB2).
                .distinctUntilChanged { old, new ->
                    old.map { Triple(it.id, it.status, it.bookManifest) } == new.map { Triple(it.id, it.status, it.bookManifest) }
                }
                .collectLatest { packs ->
                    try {
                        val started = SystemClock.elapsedRealtime()
                        books.sync(packs)
                        if (packs.isNotEmpty()) {
                            Log.i("Granger", "$label sync: ${packs.size} packs in ${SystemClock.elapsedRealtime() - started} ms")
                        }
                        after()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.w("Granger", "Couldn't list the $label packs' books", e)
                    }
                }
        }
    }

    /** Set by the first activity start in this process: the book scan and index pass run once per process. */
    val booksScannedThisProcess = AtomicBoolean(false)
    /** Phone music and audiobooks. */
    val media: MediaLibrary = RoomMediaLibrary(libraryDb)

    /** Music and audiobooks in memory, for Search, Research and Android Auto; follows [media]'s rows. */
    val mediaIndex = MediaIndex(media, appScope)

    /** Reads the phone's music and audiobooks in place, read-only; started from [GrangerApp] behind Features.PLAYER. */
    val phoneScanner: PhoneScanner by lazy {
        PhoneScanner(
            sources = listOf(MediaStoreSource(context), FolderSource(context, { playback.observeFolders().first() })),
            open = ContentResolverOpener(context.contentResolver),
            media = media,
            playback = playback,
            coversDir = File(context.filesDir, "media-covers"),
            hasPermission = { hasAudioPermission(context) },
            tagGuard = FileTagReadGuard(File(context.noBackupFilesDir, "media-scan")),
        )
    }
    val phoneMediaScans: PhoneMediaScans by lazy { PhoneMediaScans(context, appScope, phoneScanner) }

    /** Positions, bookmarks and the rest of the players' state. */
    val playback: PlaybackDao = libraryDb.playbackDao()

    /** Cover art as content:// URIs ([ArtworkProvider]), for the session's items, Android Auto and the widget. */
    val artwork: Artwork = Artwork.forContext(context)

    // ---- the voice (Plan V1) ----

    /** Settings → Voice: the chosen voice, speed and the sentence mark. */
    val voicePrefs: VoicePrefs = SharedVoicePrefs(context)

    /** What can speak now: Granger voices under every volume's `voices/`, then the system engine's offline English voices. */
    val installedVoices: InstalledVoices by lazy {
        val dirs = { context.getExternalFilesDirs(null).filterNotNull().map { File(it, "voices") } }
        val system = SystemSpeech.Factory(context)
        InstalledVoices(dirs, { system.voices() }, SherpaSpeech.Factory(dirs), system, titles = app.granger.core.speech.VoiceTitles.of(context))
    }

    /**
     * Reopens what the voice was reading for a restore after process death: an article from its pack, a book through
     * the opener (that publication stays open for the process; one book at most).
     */
    val readSources: suspend (key: String, title: String) -> ReadSource? = { key, title ->
        when (val k = ReadSourceKey.parse(key)) {
            is ReadSourceKey.Article -> library.archive(k.packId)?.let { ArticleReadSource(it, k.packId, k.path, title) }
            is ReadSourceKey.Book -> bookshelf.book(k.bookId)?.let { book ->
                val file = File(book.filePath)
                if (!file.isFile) return@let null
                // One restored book at a time: the same book is reopened from the copy already open; another book
                // closes it (the reader keeps its own copy, so this never touches a page on screen).
                val kept = restoredBook?.takeIf { it.first == k.bookId }?.second
                val opened = kept ?: bookOpener.open(file)?.also { fresh ->
                    restoredBook?.second?.let { old -> withContext(Dispatchers.IO) { runCatching { old.close() } } }
                    restoredBook = k.bookId to fresh
                } ?: return@let null
                BookReadSource(opened, k.bookId, book.title)
            }
            // A spoken Ask reply ends with its chat: one saved by an older build is gone, so the restore forgets it.
            is ReadSourceKey.Text -> null
            null -> null
        }
    }

    /** The book the voice's restore last opened, kept open for the process (Plan V2: one at most). */
    private var restoredBook: Pair<String, Publication>? = null

    /** A text's saved place (`voice_states`), for a reader's Listen (Plan U2, [listenStart]). */
    val voicePlace: suspend (sourceKey: String) -> ReadAddress? = { key -> playback.voiceState(key)?.address?.let(ReadAddress::decode) }

    /** Resumes a `voice:` play from the rail: the saved place, read again in the chosen voice (else the text's own). */
    private val resumeVoice: suspend (String) -> Boolean = { sourceKey ->
        val state = playback.voiceState(sourceKey)
        val source = state?.let { readSources(sourceKey, it.title) }
        if (source == null) false else {
            val voice = installedVoices.forResume(voicePrefs.voiceId.value, VoiceId.decode(state.voiceId))
            playerHub.readAloud(source, state.address.let(ReadAddress::decode), voice)
            true
        }
    }

    /** Which side the mini-player, Listen's card and the widget show while the voice has music under it. */
    val playerFocus = PlayerFocus()

    /** The one way to the players (spec §8); made in [GrangerApp.onCreate], on the main thread. */
    val playerHub: PlayerHub by lazy {
        MediaControllerPlayerHub(
            context, media, playback, CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
            // A cover outside the provider's roots keeps its file:// URI, which the in-process notification can still read.
            artwork = { path -> artwork.uriFromPath(path) ?: Uri.fromFile(File(path)) },
            // Made on first use (the tree plays through this hub): Android Auto's first browse.
            browse = { autoBrowse },
            voices = if (Features.READ_ALOUD) installedVoices else null,
            readSources = readSources,
            preferredVoice = { voicePrefs.voiceId.value },
            voiceSpeed = { voicePrefs.speed.value },
        )
    }

    /** What the home-screen widget shows; null until [WidgetUpdater] has published once. */
    val widgetModel = MutableStateFlow<WidgetModel?>(null)

    /** The widget's last render, for the next process's first draw. */
    val widgetStore = WidgetModelStore(context.getSharedPreferences(WidgetModelStore.PREFS, Context.MODE_PRIVATE))

    /** Plays a song in its album, from that song (Search, Android Auto). */
    val songs: SongPlayer by lazy { SongPlayer(media, playerHub) }

    /** What Android Auto browses, searches and plays; reaches the session through [playerHub]. */
    val autoBrowse: AutoBrowseTree by lazy {
        AutoBrowseTree(media, playlists, playback, mediaIndex, playResumer, songs, playerHub, artwork, context::getString)
    }

    /** Plays a recent play again (the rail, Android Auto); voices through [resumeVoice]. */
    val playResumer: PlayResumer by lazy { PlayResumer(media, playlists, playerHub, resumeVoice) }

    /** The player's settings (skip intervals, ReplayGain, headset autoplay), shared with the playback service. */
    val playerPrefs: SharedPreferences = context.getSharedPreferences(PlayerSettingsKeys.PREFS, Context.MODE_PRIVATE)
    val shelves: Shelves = RoomShelves(libraryDb, library, bookshelf, media)

    /** The user's music playlists. */
    val playlists: Playlists = RoomPlaylists(libraryDb)
    val sessions: ReadingSessions = RoomReadingSessions(libraryDb)
    val bookOpener: BookOpener = ReadiumBookOpener(context)

    /** Each library shelf kind's view (list or grid) and sort (Plan LV). */
    val libraryViews = LibraryViewPrefs(context)

    /** How books look; one set for every book. */
    val readingPrefs = ReadingPrefsStore(context.getSharedPreferences("reading", Context.MODE_PRIVATE))

    /** Clean-exit and reboot tracking for the launch decision; BOOT_COUNT is -1 (unavailable) on odd builds. */
    val sessionMarker = SessionMarker(
        prefs = context.getSharedPreferences("session", Context.MODE_PRIVATE),
        bootCount = { Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, -1) },
    )
    /** Each installed pack's set and rank from the catalog, for Search and Ask's slice dedupe (Plan WF Decision 6). */
    val packSlices: app.granger.core.library.PackSlices = app.granger.sources.CatalogPackSlices { catalog.state.value.catalog }
    val search: LibrarySearch = ZimLibrarySearch(library, bookIndex, bookshelf = bookshelf, slices = packSlices, languages = { knowledgeNow.value.toSet() })
    val resolver = ZimResolver(library::archive, library::cachedArchive, inApp = { onlineSources.inApp(it) })

    val totalRamBytes: Long = BuildConfig.FAKE_RAM_BYTES.takeIf { it > 0 } ?: ActivityManager.MemoryInfo()
        .also { context.getSystemService(ActivityManager::class.java).getMemoryInfo(it) }
        .totalMem
    val deviceTier: DeviceTier = DeviceTiers.forTotalRam(totalRamBytes)
    /** Ask runs on this phone: the feature is on ([Features.RESEARCH]) and the phone has the memory for it. */
    val askOn: Boolean = Features.RESEARCH && deviceTier >= DeviceTier.ASK
    /**
     * Search by meaning (Plan SQ Phase 6): search vectors are fetched, the embedder follows them, books you add are
     * embedded, and Best matches embed the query. On whether Ask is or not: phones with the memory Ask's tier asks for
     * (6 GB marketed), measured at speed on the Pixel 6. A 4 GB phone keeps keyword-only Best matches until one is
     * measured; a slow phone above the line drops to them by itself ([EmbedSpeed]).
     */
    val meaningSearch: Boolean = DENSE_RETRIEVAL && deviceTier.canRun(ModelRole.EMBEDDER)
    /** Where the phone is, for the suggested map: network, then SIM, then locale (Plan E1 item 1). */
    private val appContext: Context = context.applicationContext
    val phoneCountry: String get() = app.granger.device.PhoneCountry.of(appContext)


    val catalog: CatalogSource = CatalogRepository(
        client = http,
        url = BuildConfig.CATALOG_URL,
        publicKey = Base64.getDecoder().decode(BuildConfig.CATALOG_PUBLIC_KEY),
        cacheDir = File(context.filesDir, "catalog"),
        includeUnevaluatedModels = BuildConfig.DEBUG,
        // Search vectors serve search by meaning: a phone without it neither counts nor downloads them (Plan E1 phone
        // check). Vectors already installed stay (VectorDownloads removes them only with their pack).
        includeVectors = meaningSearch,
    )

    val archiveClient = ArchiveOrgClient(http, archiveUserAgent)
    val librivoxClient = LibriVoxClient(http, archiveUserAgent)
    val archivePrefs: ArchivePrefs = if (Features.ARCHIVE_WAIVER) SharedArchivePrefs(context) else FixedFilterArchivePrefs
    val appearancePrefs: AppearancePrefs = SharedAppearancePrefs(context)

    /** Explore's rooms seen (Plan EX round 2, R), in a preferences file of its own. */
    val seen: app.granger.explore.SeenStore by lazy {
        app.granger.explore.SeenStore.Prefs(context.getSharedPreferences("explore_seen", android.content.Context.MODE_PRIVATE))
    }

    /** Settings' switch for adult topics and pictures on the screens Granger fills by itself, in a preferences file of its own. */
    val contentPrefs: app.granger.settings.ContentPrefs by lazy {
        app.granger.settings.ContentPrefs.Prefs(context.getSharedPreferences("content", android.content.Context.MODE_PRIVATE))
    }

    /** The adult-content flags as volunteered screens apply them (the QID indexes' flags, while [contentPrefs] says so). */
    val contentFilter: app.granger.core.library.ContentFilter by lazy {
        app.granger.core.library.ContentFilter { contentPrefs.keepOff.value }
    }
    val appLanguages: AppLanguages = AppLanguages.create(context, onLegacyChange = { (context as? GrangerApp)?.relocalize() })

    /** Voice input (Plan A1): the phone's on-device recognizer, in the app's language; read-aloud stops before a take. */
    val voiceInput by lazy {
        app.granger.voiceinput.VoiceInput(
            speech = app.granger.core.speech.OnDeviceSpeechInput(app.granger.core.speech.AndroidRecognizerBackend(context)),
            prefs = app.granger.voiceinput.SharedVoiceInputPrefs(context),
            appLanguage = appLanguages.code,
            stopReadAloud = { playerHub.stopVoice() },
            scope = appScope,
        )
    }

    /** The languages of the user's library (Plan L3): the app language's by default, when the catalog has tiers for it. */
    val knowledgeLanguages: KnowledgeLanguages = KnowledgeLanguages.create(
        context, appLanguages.code, catalog.state.map { it.catalog?.languages.orEmpty() }.distinctUntilChanged(),
    )

    /**
     * Wikipedia online (Plan O1): its own client derived from the shared one (the gate and the agent hold), with an
     * interim 50 MB cache so Back and a second visit don't fetch again (builder's call 3; O2's Saved for now replaces it).
     */
    val wikimedia: app.granger.core.online.WikimediaClient by lazy {
        // The shared online code (Plan I1) logs as the app's did: warnings always, timings in debug builds.
        app.granger.core.online.OnlineLog.warn = { android.util.Log.w(app.granger.core.online.OnlineWikipediaArchive.TAG, it) }
        app.granger.core.online.OnlineLog.info = { if (BuildConfig.DEBUG) android.util.Log.i(app.granger.core.online.OnlineWikipediaArchive.TAG, it) }
        // O1's interim HTTP cache is gone: Saved for now holds what was read (Plan O2 Task 1). Its folder goes once.
        File(appContext.cacheDir, "wikimedia").takeIf { it.exists() }?.let { old -> appScope.launch(Dispatchers.IO) { old.deleteRecursively() } }
        app.granger.core.online.WikimediaClient(
            http, onMainThread = { BuildConfig.DEBUG && android.os.Looper.myLooper() == android.os.Looper.getMainLooper() },
            allowed = { networkPolicy.privacySeen.value },
        )
    }

    /** Whether the internet can be reached now: the policy and Android's connection (Plan O3 Task 1). */
    val reach by lazy { app.granger.online.Reach(appContext, networkPolicy.mode, appScope) }

    /** One-line hints shown once each (Plan OB call 5): Keep and the marks, Local only. */
    val hints by lazy { app.granger.online.Hints(app.granger.online.SharedHintStore(appContext)) }

    /** The Local only switch's and banner's control (Plan O3); null while Wikipedia online is off. */
    val reachControl: app.granger.online.ReachControl? = if (!Features.ONLINE) null else object : app.granger.online.ReachControl {
        override val state get() = reach.state
        override fun setLocalOnly(on: Boolean) {
            // Never go online can't be turned off from here (Plan O5).
            if (networkPolicy.now() == app.granger.core.downloads.NetworkMode.NEVER) return
            networkPolicy.set(if (on) app.granger.core.downloads.NetworkMode.LOCAL_ONLY else app.granger.core.downloads.NetworkMode.ONLINE)
        }
        override fun setNever(on: Boolean) =
            networkPolicy.set(if (on) app.granger.core.downloads.NetworkMode.NEVER else app.granger.core.downloads.NetworkMode.ONLINE)
    }

    /**
     * The online sources while the internet can't be reached: what the volunteered screens (Wander, games, the Timeline,
     * Continue) leave out, since their articles wouldn't open (Plan O3 Task 5). Empty while online.
     */
    fun offlineSources(): Set<String> =
        if (!Features.ONLINE || reach.now.online) emptySet() else onlineSources.archives().keys

    /** Whether an article opens now: on the phone, kept, saved for now, or online while online (Plan O3). */
    fun opensNow(packId: String, path: String): Boolean {
        val lang = app.granger.core.online.OnlineWikipediaArchive.languageOf(packId) ?: return true
        if (reach.now.online) return true
        return keptStore.get(lang, path) != null || savedStore.has(app.granger.online.SavedStore.pageKey(lang, path))
    }

    /** What it would take to have [path] when it can't open now (Plan O3 Task 6): its keep, and the pack that holds it. */
    suspend fun wouldTake(packId: String, path: String, title: String): app.granger.online.WouldTake {
        val each = if (onlinePrefs.offlinePictures.value == app.granger.online.OnlinePrefs.OfflinePictures.SAVE) app.granger.online.RoomOffers.EACH_WITH_PICTURES else app.granger.online.RoomOffers.EACH_TEXT
        val lang = app.granger.core.online.OnlineWikipediaArchive.languageOf(packId)
        // The online source's index set is Top's: an article it holds is in the Top pack (its smallest flavour offered).
        val inTop = lang != null && runCatching { referenceLinks.qidIndexes().qidOf(packId, path) != null }.getOrDefault(false)
        val installed = library.packs.first().mapTo(HashSet()) { it.name }
        val pack = if (!inTop) null else catalog.state.value.catalog?.packs.orEmpty()
            .filter { it.id.startsWith("wikipedia_${lang}_top_") && !it.id.startsWith("wikipedia_${lang}_top1m") && it.zimName !in installed }
            .minByOrNull { it.sizeBytes }?.let { app.granger.online.PackOffer(it.id, it.title, it.sizeBytes) }
        return app.granger.online.WouldTake(packId, path, title, each, pack)
    }

    /** Keeps an article as soon as the internet is back (Plan O3 Task 6): the keep job, which waits while offline. */
    fun keepLater(packId: String, path: String) {
        val lang = app.granger.core.online.OnlineWikipediaArchive.languageOf(packId) ?: return
        app.granger.online.KeepWorker.start(appContext, "article:$lang:$path", listOf(lang to path))
    }

    /** Gets a catalog pack (the sheet's and Keep this room's offer). */
    fun getPack(id: String) = roomKeep?.getPack(id)

    /**
     * Home's Ready offline (Plan O3 Task 8): the articles read in the last 30 days (history never leaves the phone), where
     * each can be read from, its subject, and what would fill the gaps. Null with nothing read lately.
     */
    suspend fun readyOffline(subjects: Int = app.granger.online.ReadyOffline.SUBJECTS): app.granger.online.ReadyOfflineUi? = kotlinx.coroutines.withContext(Dispatchers.IO) {
        val since = System.currentTimeMillis() - 30L * 24 * 3600 * 1000
        val recent = sessions.history.first().filter { it.openedAt >= since && it.path.isNotEmpty() && !app.granger.core.library.BookIds.isBook(it.packId) }
            .distinctBy { it.packId to it.path }.take(READY_RECENT)
        if (recent.isEmpty()) return@withContext null
        val qids = runCatching { referenceLinks.qidIndexes() }.getOrNull()
        val packs = knownPacks.first()
        val reads = recent.mapNotNull { h ->
            val pack = packs.firstOrNull { it.id == h.packId } ?: return@mapNotNull null
            if (!pack.name.startsWith("wikipedia_")) return@mapNotNull null
            val lang = app.granger.online.ArticleCopies.languageOf(h.packId, pack)
            val qid = qids?.let { q -> q.qidOf(h.packId, h.path) ?: lang?.let { q.qidOfTitle(h.path, it) } }
            app.granger.online.RecentRead(
                h.packId, h.path, availability.of(h.packId, h.path), qid?.let { qids.subjectsOf(it).firstOrNull() },
                inPack = app.granger.online.OnlineSources.isOnline(h.packId) && qids?.qidOf(h.packId, h.path) != null,
            )
        }
        if (reads.isEmpty()) return@withContext null
        val each = if (onlinePrefs.offlinePictures.value == app.granger.online.OnlinePrefs.OfflinePictures.SAVE) app.granger.online.RoomOffers.EACH_WITH_PICTURES else app.granger.online.RoomOffers.EACH_TEXT
        val lang = knowledgeNow.value.firstOrNull() ?: "en"
        val installed = library.packs.first().mapTo(HashSet()) { it.name }
        val top = catalog.state.value.catalog?.packs.orEmpty()
            .filter { it.id.startsWith("wikipedia_${lang}_top_") && it.zimName !in installed }
            .minByOrNull { it.sizeBytes }?.let { app.granger.online.PackOffer(it.id, it.title, it.sizeBytes) }
        // Subject packs the catalog has and the phone hasn't, for Outfitting's suggestions (Plan O4 builder's call 3).
        val subjectPacks = catalog.state.value.catalog?.packs.orEmpty()
            .filter { it.zimName !in installed && !it.id.startsWith("wikipedia_${lang}_top") }
            .mapNotNull { e -> app.granger.explore.Subject.ofDomain(e.domain)?.let { e to it.id } }
            .groupBy { it.second }.mapNotNull { (_, es) -> es.minByOrNull { it.first.sizeBytes } }
            .map { (e, subject) -> app.granger.online.PackOffer(e.id, e.title, e.sizeBytes) to subject }
        app.granger.online.ReadyOffline.of(reads, each, top, subjects, subjectPacks)
    }

    /** Keeps the online-only articles you read lately (Ready offline's suggestion): one background job. */
    fun keepTheRead(articles: List<Pair<String, String>>) = app.granger.online.KeepWorker.start(appContext, "recent-reads", articles)

    /** Offline pictures and Saved for now's cap (Plan O2 Task 2). */
    val onlinePrefs = app.granger.online.OnlinePrefs(context)

    /** Saved for now (Plan O2 Task 1): what was read online, oldest-opened out first past the cap. */
    val savedStore by lazy { app.granger.online.SavedStore(File(appContext.filesDir, "saved")) }

    /** Articles kept from Wikipedia online (Plan O2 Task 3). */
    val keptStore by lazy { app.granger.online.KeptStore(File(appContext.filesDir, "kept"), libraryDb.keptArticleDao(), appScope) }

    private val offlineStores by lazy {
        app.granger.online.OfflineStores(
            savedStore, keptStore,
            savePictures = { onlinePrefs.offlinePictures.value == app.granger.online.OnlinePrefs.OfflinePictures.SAVE },
            capBytes = { onlinePrefs.savedCapMb.value * 1024L * 1024 },
            pageSaved = { markVersion.value++ },
        )
    }

    /** Wikipedia online in each knowledge language, while [Features.ONLINE] is on (Plan O1 Task 4). */
    /**
     * Topic records read online (Plan O6): full topic pages for articles the phone's indexes don't hold, from the catalog's
     * records for the language. Fetches only while online, Local only off and the privacy line seen; blocks it reads are
     * Saved for now.
     */
    val remoteRecords: app.granger.online.RemoteRecords by lazy {
        app.granger.online.RemoteRecords(
            http,
            entryFor = { lang ->
                catalog.state.value.catalog?.packs.orEmpty().firstOrNull { it.records != null && app.granger.core.catalog.Languages.codes(it.language).firstOrNull() == lang }?.records
            },
            dirFolder = File(appContext.filesDir, "records"),
            saved = savedStore,
            canFetch = { reach.now.online && networkPolicy.privacySeen.value },
            trim = { savedStore.trim(onlinePrefs.savedCapMb.value * 1024L * 1024) },
        )
    }

    val onlineSources: app.granger.online.OnlineSources by lazy {
        // Every language Granger supports, not only the knowledge languages: a Spanish tab names and opens itself (Plan O3
        // builder's call 8). The index set and Search keep to the knowledge languages.
        app.granger.online.OnlineSources(
            wikimedia, knowledgeLanguages.languages.map { (it + ONLINE_LANGUAGES).distinct() }, { (knowledgeNow.value + ONLINE_LANGUAGES).distinct() }, { Features.ONLINE },
            // No signal or Local only: the article from a kept copy instead (spec §4, Plan O1 Task 5).
            fallback = { lang, title ->
                val copies = kotlinx.coroutines.runBlocking {
                    runCatching { articleCopies.copies(app.granger.core.online.OnlineWikipediaArchive.packName(lang), title, lang, knownPacks.first()) }.getOrNull()
                }
                copies?.filter { it.kind != app.granger.online.ArticleCopy.Kind.ONLINE }?.sortedBy { it.kind }
                    ?.firstNotNullOfOrNull { c -> library.archive(c.packId)?.let { a -> a.resolvePath(c.path)?.let(a::read) } }
                    ?.takeIf { it.mimeType.startsWith("text/html") }
            },
            indexRandom = { id -> kotlinx.coroutines.runBlocking { runCatching { referenceLinks.qidIndexes().randomPath(id) }.getOrNull() } },
            stores = offlineStores,
        )
    }

    /** Which copy of an article opens when it's opened by its topic (Plan O1 Task 5). */
    val articleCopies: app.granger.online.ArticleCopies by lazy {
        app.granger.online.ArticleCopies(
            qids = { kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { referenceLinks.qidIndexes() } },
            packs = { knownPacks.first() },
            online = { Features.ONLINE && networkGate.open },
            onlineLanguages = { if (Features.ONLINE) knowledgeNow.value.toSet() else emptySet() },
            kept = { lang, title -> if (Features.ONLINE) keptStore.get(lang, title)?.withPictures else null },
        )
    }

    /**
     * Wikipedia online's titles and full-text hits for a query, in each knowledge language (Plan O1 Task 7). Nothing when
     * the gate is shut or there's no signal.
     */
    suspend fun searchOnline(query: String): Pair<List<app.granger.core.library.SearchHit>, List<app.granger.core.library.SearchHit>> =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val langs = knowledgeNow.value.filter { onlineSources.archive(it) != null }
            if (langs.isEmpty() || !networkGate.open) return@withContext emptyList<app.granger.core.library.SearchHit>() to emptyList()
            fun hit(lang: String, h: app.granger.core.online.WikimediaClient.Hit, snippet: Boolean) = app.granger.core.library.SearchHit(
                app.granger.core.online.OnlineWikipediaArchive.packName(lang), if (langs.size > 1) "Wikipedia · ${java.util.Locale(lang).getDisplayLanguage(java.util.Locale(lang)).replaceFirstChar { it.uppercase() }}" else "Wikipedia",
                h.key, h.title, snippet = if (snippet) h.excerpt else null,
            )
            kotlinx.coroutines.coroutineScope {
                val scope = this
                val titles = langs.map { l -> scope.async { wikimedia.titles(l, query, ONLINE_HITS).map { hit(l, it, false) } } }
                val pages = langs.map { l -> scope.async { wikimedia.pages(l, query, ONLINE_HITS).map { hit(l, it, true) } } }
                // Interleaved, so a second language's hits aren't all behind Show all (Plan O2 Task 9).
                app.granger.online.interleave(titles.map { it.await() }) to app.granger.online.interleave(pages.map { it.await() })
            }
        }

    /** Updates for kept articles (Plan O5 Task 4): checked once a day while online, when Outfitting shows. */
    val keptUpdates by lazy {
        val prefs = appContext.getSharedPreferences("online", Context.MODE_PRIVATE)
        app.granger.online.KeptUpdates(
            revisions = { lang, titles -> wikimedia.revisions(lang, titles) },
            lastCheck = { prefs.getLong("kept_updates_checked", 0L) },
            setLastCheck = { prefs.edit().putLong("kept_updates_checked", it).apply() },
        )
    }

    /** Re-keeps the kept articles that have a newer version (Outfitting's Update / Update all). */
    suspend fun updateKept(articles: List<app.granger.core.library.KeptArticle>) {
        for (a in articles) if (keeper.keep(a.lang, a.title) != null) keptUpdates.done(a.lang, a.title)
    }

    /** Keeps and removes online articles (Plan O2 Tasks 3 and 5). */
    val keeper by lazy {
        app.granger.online.Keeper(
            { lang -> onlineSources.archive(lang) }, keptStore,
            withPictures = { onlinePrefs.offlinePictures.value == app.granger.online.OnlinePrefs.OfflinePictures.SAVE },
            changed = { markVersion.value++ },
        )
    }

    private val markVersion = kotlinx.coroutines.flow.MutableStateFlow(0)

    /** What every mark asks (Plan O2 Task 4); null while Wikipedia online is off, so no marks draw. */
    val marks: app.granger.online.MarkSource? = if (!Features.ONLINE) null else object : app.granger.online.MarkSource {
        override suspend fun of(packId: String, path: String) = availability.of(packId, path)
        override val version = markVersion
        override fun kept(packId: String, path: String) =
            app.granger.core.online.OnlineWikipediaArchive.languageOf(packId)?.let { keptStore.get(it, path)?.withPictures }
        override fun keepsPictures() = onlinePrefs.offlinePictures.value == app.granger.online.OnlinePrefs.OfflinePictures.SAVE
        override suspend fun keepSize(packId: String, path: String): Long? {
            val lang = app.granger.core.online.OnlineWikipediaArchive.languageOf(packId) ?: return null
            // What's saved for now says it: the page, and under Save pictures the pictures it shows that are saved too.
            val key = app.granger.online.SavedStore.pageKey(lang, path)
            val page = savedStore.bytesOf(key) ?: return null
            if (!keepsPictures()) return page
            val html = savedStore.get(key)?.bytes?.let { String(it) } ?: return page
            return page + app.granger.core.online.OnlineWikipediaArchive.picturesOf(html).sumOf { savedStore.bytesOf(app.granger.online.SavedStore.pictureKey(it)) ?: 0L }
        }
        override suspend fun keep(packId: String, path: String): Boolean =
            app.granger.core.online.OnlineWikipediaArchive.languageOf(packId)?.let { keeper.keep(it, path) } != null
        override suspend fun remove(packId: String, path: String) {
            app.granger.core.online.OnlineWikipediaArchive.languageOf(packId)?.let { keeper.remove(it, path) }
        }
        override fun queued(packId: String, path: String) = app.granger.online.KeepWorker.progress(
            appContext, "article:${app.granger.core.online.OnlineWikipediaArchive.languageOf(packId)}:$path",
        ).map { it?.running == true }
    }

    /** Keep a room or subject (Plan O2 Task 6): its online articles in the background, or a pack that covers it. */
    val roomKeep: app.granger.online.RoomKeepSource? = if (!Features.ONLINE) null else object : app.granger.online.RoomKeepSource {
        override suspend fun offer(subjectId: String, facetId: String?): app.granger.online.RoomOffer? {
            val qids = referenceLinks.qidIndexes()
            val ranked = if (facetId != null) qids.topInFacet("$subjectId.$facetId", ROOM_KEEP) else qids.topIn(subjectId, ROOM_KEEP, mainOnly = true)
            val installed = library.packs.first().mapTo(HashSet()) { it.name }
            return app.granger.online.RoomOffers.build(
                ranked.map { it.packId to it.path }, isKept = { l, t -> keptStore.get(l, t) != null },
                keepsPictures = onlinePrefs.offlinePictures.value == app.granger.online.OnlinePrefs.OfflinePictures.SAVE,
                packs = catalog.state.value.catalog?.packs.orEmpty().filter { it.zimName !in installed }
                    .map { Triple(it.id, it.title, it.sizeBytes) to app.granger.explore.Subject.ofDomain(it.domain)?.id },
                subjectId = subjectId,
            )
        }
        override fun keep(roomKey: String, name: String, articles: List<Pair<String, String>>) = app.granger.online.KeepWorker.start(appContext, roomKey, articles)
        override fun cancel(roomKey: String) { app.granger.online.KeepWorker.cancel(appContext, roomKey) }
        override fun getPack(id: String) {
            appScope.launch {
                val item = catalog.state.value.catalog?.packs?.firstOrNull { it.id == id } ?: return@launch
                val root = volumes.list().firstOrNull()?.root ?: return@launch
                downloads.enqueue(item, root, downloadPrefs.wifiOnly)
            }
        }
        override fun progress(roomKey: String) = app.granger.online.KeepWorker.progress(appContext, roomKey)
    }

    /**
     * Opens a Granger Classics or Gutenberg book without keeping it (Plan O2 Task 8; builder's call 8): its EPUB is fetched
     * into Saved for now (checked against the catalog's hash) and registered off the shelf, under the id a download would
     * give it, so Keep (an ordinary download) takes over the same book with its place and highlights. Returns the book's
     * id, or null when it can't be had.
     */
    suspend fun readWithoutKeeping(itemId: String): String? = kotlinx.coroutines.withContext(Dispatchers.IO) {
        val id = app.granger.core.library.BookIds.of(app.granger.core.library.BookOrigin.GRANGER, itemId)
        bookshelf.book(id)?.takeIf { it.status == app.granger.core.library.PackStatus.OK && File(it.filePath).isFile }?.let { return@withContext it.id }
        val entry = catalog.state.value.catalog?.books?.firstOrNull { it.id == itemId } ?: return@withContext null
        val key = app.granger.online.SavedStore.bookKey(itemId)
        val file = savedStore.file(key) ?: run {
            val tmp = File(appContext.cacheDir, "reading-$itemId.part")
            val ok = entry.urls.any { url ->
                runCatching {
                    http.newCall(okhttp3.Request.Builder().url(url).build()).execute().use { r ->
                        if (!r.isSuccessful) return@use false
                        tmp.outputStream().use { out -> r.body.byteStream().copyTo(out) }
                        java.security.MessageDigest.getInstance("SHA-256").digest(tmp.readBytes()).joinToString("") { "%02x".format(it) }.equals(entry.sha256, ignoreCase = true)
                    }
                }.getOrDefault(false)
            }
            if (!ok) { tmp.delete(); return@withContext null }
            if (!savedStore.putFile(key, app.granger.online.SavedStore.Kind.BOOK, tmp, "application/epub+zip")) return@withContext null
            savedStore.trim(onlinePrefs.savedCapMb.value * 1024L * 1024)
            savedStore.file(key) ?: return@withContext null
        }
        val book = bookshelf.addDownloaded(file, app.granger.core.library.BookOrigin.GRANGER, itemId) ?: return@withContext null
        bookshelf.hide(listOf(book.id))
        book.id
    }

    /** Keeps a book opened without keeping: the ordinary download, which takes over the same book (Plan O2 Task 8). */
    suspend fun keepBook(itemId: String): Boolean {
        val entry = catalog.state.value.catalog?.books?.firstOrNull { it.id == itemId } ?: return false
        val root = kotlinx.coroutines.withContext(Dispatchers.IO) { volumes.list().firstOrNull()?.root } ?: return false
        downloads.enqueue(entry, root, downloadPrefs.wifiOnly)
        return true
    }

    /** Plan BK: book packs' books read one at a time from the pack file on Kiwix's mirrors. */
    val packBookReads by lazy {
        app.granger.online.PackBookReads(http, savedStore, File(appContext.cacheDir, "reading"), trim = { savedStore.trim(onlinePrefs.savedCapMb.value * 1024L * 1024) })
    }

    /** Whether something may be fetched now: online, Local only off, the privacy line seen. */
    fun canFetchOnline(): Boolean = reach.now.online && networkPolicy.privacySeen.value

    /** Plan BK: the catalog pack [packEntryId]'s collection, read online (or from Saved for now); null without one. */
    suspend fun remoteCollection(packEntryId: String): app.granger.online.RemoteCollection? {
        val entry = catalog.state.value.catalog?.packs?.firstOrNull { it.id == packEntryId } ?: return null
        return packBookReads.collection(entry, canFetchOnline())
    }

    /**
     * Plan BK: opens the book [key] of the catalog pack [packEntryId] without the pack: read from the pack file by range
     * into Saved for now, as the row the pack would give it (hidden: read, not kept). Its id, or null when it can't be
     * had. A book already here (kept, or its pack installed and copied out) opens as it is.
     */
    suspend fun readPackBookOnline(packEntryId: String, key: String, progress: (Long, Long) -> Unit = { _, _ -> }): String? {
        val remote = remoteCollection(packEntryId) ?: return null
        val item = remote.items.firstOrNull { it.key == key } ?: return null
        val id = remote.bookId(item)
        bookshelf.book(id)?.takeIf { it.status == app.granger.core.library.PackStatus.OK && File(it.filePath).isFile }?.let { return it.id }
        val file = packBookReads.book(remote, item, canFetchOnline(), progress) ?: return null
        val pack = remote.pack
        val details = app.granger.core.library.BookDetails(
            title = item.title, author = item.author, date = "", subjects = emptyList(), description = item.description,
            language = item.language.ifBlank { pack.language },
        )
        val place = app.granger.core.library.PackItem(
            ref = remote.ref(item), path = item.path, title = item.title, author = item.author, format = item.format,
            sizeBytes = item.bytes, groupName = item.groupName, ord = item.ord,
            workKey = item.partOf ?: item.workKey, workTitle = item.workTitle, partNo = item.partNo,
        )
        return bookshelf.addLoose(id, remote.origin, "${pack.zimName}/${item.path}", file, details, place)?.id
    }

    /**
     * Plan BK: keeps a pack book read online on its own, without its pack: its file moves out of Saved for now into
     * `books/kept`, and it joins the shelf in its pack's collection. True when it's kept (or already was).
     */
    suspend fun keepPackBook(bookId: String): Boolean {
        val book = bookshelf.book(bookId) ?: return false
        if (book.loose == app.granger.core.library.Loose.KEPT) return true
        if (book.loose != app.granger.core.library.Loose.SAVED) return false
        val ext = if (book.format == app.granger.core.library.BookFormat.PDF) "pdf" else "epub"
        val dir = kotlinx.coroutines.withContext(Dispatchers.IO) { File(bookDirs().first(), "kept") }
        val name = java.security.MessageDigest.getInstance("SHA-1").digest(bookId.toByteArray()).joinToString("") { "%02x".format(it) }
        bookshelf.keepLoose(bookId, File(dir, "$name.$ext")) ?: return false
        savedStore.remove(app.granger.online.SavedStore.bookKey(bookId))
        return true
    }

    /** Plan BK: keeps a whole book pack (the ordinary download). True when it's queued, coming or already here. */
    suspend fun keepPack(packEntryId: String): Boolean {
        val entry = catalog.state.value.catalog?.packs?.firstOrNull { it.id == packEntryId } ?: return false
        val root = kotlinx.coroutines.withContext(Dispatchers.IO) { volumes.list().firstOrNull()?.root } ?: return false
        return when (downloads.enqueue(entry, root, downloadPrefs.wifiOnly)) {
            app.granger.core.downloads.EnqueueResult.Queued, app.granger.core.downloads.EnqueueResult.AlreadyActive,
            app.granger.core.downloads.EnqueueResult.AlreadyInstalled -> true
            is app.granger.core.downloads.EnqueueResult.NoSpace -> false
        }
    }

    /** Debug only (Plan O2 Pixel pass): removes every kept article (`--es kept_clear all`). */
    fun debugClearKept() {
        appScope.launch { for (a in libraryDb.keptArticleDao().all()) keeper.remove(a.lang, a.title) }
    }

    /** Where each article can be read from (Plan O1 Task 9): what O2's marks will read. */
    val availability: app.granger.online.ArticleAvailability by lazy { app.granger.online.ArticleAvailability(articleCopies, { knownPacks.first() }) { lang, title ->
            Features.ONLINE && savedStore.has(app.granger.online.SavedStore.pageKey(lang, title))
        } }

    /**
     * Opens an article chosen by its topic (an Explore plate, a topic page's links, Wander, the Timeline, a game, a Home
     * card) in a new tab, in the copy [articleCopies] picks; returns the tab's id.
     */
    suspend fun openArticle(packId: String, path: String, title: String): Long {
        val copy = runCatching { articleCopies.choose(packId, path) }.getOrNull()
        return sessions.openTab(copy?.packId ?: packId, copy?.path ?: path, title)
    }

    /** The packs on the phone, then the online sources: for the places that name or pick a source of articles. */
    val knownPacks: kotlinx.coroutines.flow.Flow<List<app.granger.core.library.Pack>> by lazy { onlineSources.known(library.packs) }

    /** [knowledgeLanguages] now, for code that reads it at a moment (an archive.org search, LibriVox's list). */
    val knowledgeNow: StateFlow<List<String>> by lazy {
        knowledgeLanguages.languages.stateIn(appScope, SharingStarted.Eagerly, listOf(app.granger.core.catalog.ENGLISH))
    }
    val homePrefs: HomePrefs = SharedHomePrefs(context)
    /** The tidy offers Keep both answered (Plan WF Decision 5), shared by Home and Downloads. */
    val tidyDismissals = app.granger.sources.TidyDismissals.shared(context.getSharedPreferences("home", Context.MODE_PRIVATE))
    val onboardingPrefs: OnboardingPrefs = SharedOnboardingPrefs(context)

    /** Where archive.org metadata sidecars live, whatever volume the download goes to. */
    val archiveSidecarDir: File = File(context.getExternalFilesDir(null) ?: context.filesDir, "books/archive-org")

    val volumes: VolumeSource = AndroidVolumes(context)

    /** Add a pack from a file (Plan E1 item 4). */
    val packImports: app.granger.packs.PackImports by lazy {
        app.granger.packs.PackImports.forContext(
            context.applicationContext, { catalog.state.value.catalog }, { library.packs.first() }, downloads, volumes, downloadPrefs,
        )
    }
    val downloadPrefs: DownloadPrefs = SharedDownloadPrefs(context)

    private val downloadDao = DownloadDatabase.create(context).downloadDao()

    /** The one download queue (Plan DL Decision 1), drained by the `download-queue` worker. */
    val downloadQueue: DownloadQueue by lazy {
        DownloadQueue(
            downloadDao, downloadRunner, downloadPrefs, unmetered = { isUnmetered(context) }, elapsed = SystemClock::elapsedRealtime,
            connected = { app.granger.core.downloads.isConnected(context) },
        )
    }

    /** Whether the network now is unmetered (Wi-Fi), as the download queue sees it. */
    val unmetered: kotlinx.coroutines.flow.Flow<Boolean> by lazy { unmeteredFlow(appContext) }

    val downloads: Downloads = DownloadManager(
        dao = downloadDao,
        scheduler = WorkManagerScheduler(context) { downloadQueue },
        prefs = downloadPrefs,
        // Deferred: the queue needs the runner, made below.
        progress = flow { emitAll(downloadQueue.progress) },
        unmetered = unmeteredFlow(context),
        meteredWifi = app.granger.core.downloads.meteredWifiFlow(context),
    )

    val downloadRunner = DownloadRunner(
        dao = downloadDao,
        downloader = FileDownloader(http, openLocal = { url, offset -> app.granger.core.downloads.LocalFiles.open(context, url, offset) }),
        onInstalled = { record ->
            if (record.kind == ItemKind.PACK) {
                library.fileReplaced(record.filePath)
                library.refresh()
                // A Switch: the pack's other flavour gives way to this one, keeping what was saved in it (Plan KB).
                try {
                    app.granger.sources.FlavourSwitch.settle(record.itemId, catalog.state.value.catalog, downloads, library)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w("Granger", "Couldn't remove the other flavour of ${record.itemId}", e)
                }
            } else if (record.kind == ItemKind.BOOK) {
                fileBookRecord(bookshelf, archiveSidecarDir, record) { appScope.launch { bookIndex.indexPending() } }
            } else if (record.kind == ItemKind.AUDIO) {
                mediaDownloader.onFileInstalled(record)
            } else if (record.kind == ItemKind.VOICE) {
                // The verified zip becomes voices/<id>/ and goes away; the voice list picks the new voice up.
                VoiceUnpacker.unpack(record.file).onFailure { Log.w("Granger", "Couldn't unpack the voice ${record.itemId}", it) }
                installedVoices.refresh()
            }
            // Its sidecars go right behind it, before the queue moves on (Plan DL Decision 2).
            if (record.kind == ItemKind.PACK || record.kind == ItemKind.BOOK) {
                try {
                    sidecars.reconcile(catalog.state.value.catalog, downloadDao.getAll())
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w("Granger", "Couldn't queue the sidecars of ${record.itemId}", e)
                }
            }
        },
    )

    /** Files Granger's downloaded music and audiobooks from their item folders' manifests (no audio permission). */
    val downloadedMedia: DownloadedMediaScanner by lazy {
        DownloadedMediaScanner(
            { context.getExternalFilesDirs(null).filterNotNull() }, media,
            tagGuard = FileTagReadGuard(File(context.noBackupFilesDir, "media-downloads")),
        )
    }

    /** Gets, cancels and removes LibriVox and archive.org audio; every downloaded-media scan runs through it. */
    val mediaDownloader: MediaDownloader by lazy {
        MediaDownloader(
            downloads, downloadedMedia, media, volumes, downloadPrefs, appScope,
            // The Files app refreshes "Show in other apps" after each filing or removal.
            onChanged = { MediaSharing.notifyChanged(context) },
            // Removing what's playing empties the player instead of leaving it there, unplayable (Pixel 6, M2).
            onRemoving = { audiobookId, trackIds -> playerHub.unload(audiobookId, trackIds) },
        )
    }

    val researchChats: ResearchChats = RoomResearchChats(libraryDb)

    /** Items pinned to the top of the rail's list. */
    val railPins: RailPins = RoomRailPins(libraryDb)
    val homeFeeds: HomeFeeds = RoomHomeFeeds(libraryDb)

    /** Chats and plays taken off the rail until they're used again. */
    val railHidden: RailHidden = SharedRailHidden(context)

    /** The rail's four recent items, in the order they arrived (Plan U3b Decision 6). */
    val railOrder: RailOrder = SharedRailOrder(context)

    /** Set by the first activity start in this process: turns left PENDING by a dead process are settled once. */
    val chatsSettledThisProcess = AtomicBoolean(false)

    /** The model files on disk, by catalog role; empty until the catalog has loaded from its cache. */
    val installedModels: Flow<InstalledModels> =
        combine(downloads.records, catalog.state.map { it.catalog?.models.orEmpty() }) { r, m -> installedModelsOf(r, m) }
            .flowOn(Dispatchers.IO)

    private val modelCacheDir = context.cacheDir.path

    /** Whether the model manager loads the embedder: [DENSE_RETRIEVAL], or a debug eval run that turns it on. */
    val embedderWanted = AtomicBoolean(DENSE_RETRIEVAL)

    val modelManager = ModelManager(
        deviceTier, installedModels,
        { path, backend -> LiteRtLlmEngine(path, backend, modelCacheDir) },
        { path -> LiteRtTextEmbedder(path, modelCacheDir, EMBEDDER_BACKEND) },
        appScope, useEmbedder = embedderWanted::get, backend = MODEL_BACKEND,
        // The self-check (release checklist A): kept in files/, so a crash mid-check is found at the next launch.
        checks = ModelCheckStore(java.io.File(context.filesDir, "model-check.txt")),
    )

    /** Ask's models, fetched from Ask's own empty state (Plan U3a). */
    val librarian = app.granger.research.Librarian(catalog, downloads, volumes, downloadPrefs, DENSE_RETRIEVAL)

    val passageFinder = LibraryPassageFinder(library, bookIndex, bookshelf, shelves, media = mediaIndex, slices = packSlices)

    /** Sidecars (search vectors, the time index, the QID index…) follow their pack or book: queued when it installs, removed with it. */
    private val sidecars = Sidecars(
        downloads, wifiOnly = { downloadPrefs.wifiOnly }, wanted = ::sidecarWanted,
        // Wikipedia online's index set has no pack record to follow (Plan O1 Task 8); OnlineIndexes keeps it.
        keepParent = app.granger.online.OnlineSources::isOnline,
    )

    /** Wikipedia online's small index set: queued once per knowledge language, kept while its source is on (Plan O1 Task 8). */
    val onlineIndexes = app.granger.online.OnlineIndexes(
        downloads, volumeRoot = { volumes.list().firstOrNull()?.root }, wifiOnly = { downloadPrefs.wifiOnly },
        title = { appContext.getString(R.string.online_index_title) },
    )

    /** A sidecar kind downloads only once a feature reads it (Linked Library spec §5.8). */
    private fun sidecarWanted(kind: SidecarKind): Boolean = when (kind) {
        // A book manifest is small, and it's what makes its pack a collection (Plan KB2).
        SidecarKind.VECTORS, SidecarKind.TIME_INDEX, SidecarKind.BOOK_MANIFEST -> true
        SidecarKind.QID_INDEX, SidecarKind.LINKS -> Features.LINKED_CLASSICS
    }

    /** The embedder follows the first search vectors, once (Plan SQ Phase 6); the marker keeps a removal in Models. */
    private val embedderFollowedMarker = File(context.filesDir, "embedder-followed")
    private val embedderDownloads = app.granger.core.downloads.EmbedderDownloads(
        downloads, wifiOnly = { downloadPrefs.wifiOnly },
        followed = { embedderFollowedMarker.exists() }, markFollowed = { embedderFollowedMarker.createNewFile() },
    )

    /** Starts the download queue, recovering a file left running by a run that died; see [Downloads.recover]. */
    fun resumeDownloads() {
        appScope.launch {
            try {
                downloads.recover()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w("Granger", "Couldn't resume the downloads", e)
            }
        }
    }

    /** Keeps the vectors downloads matching the installed items and the catalog, from the cached catalog on. */
    fun followVectors() {
        appScope.launch {
            catalog.load()
            combine(catalog.state.map { it.catalog }.distinctUntilChanged(), downloads.records) { c, r -> c to r }
                .collectLatest { (c, r) ->
                    try {
                        sidecars.reconcile(c, r)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.w("Granger", "Couldn't update the sidecar downloads", e)
                    }
                    if (meaningSearch) {
                        try {
                            embedderDownloads.reconcile(c, r)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            Log.w("Granger", "Couldn't queue the embedder", e)
                        }
                    }
                }
        }
        // Wikipedia online's small index set (Plan O1 Task 8): follows the catalog, the records, the knowledge languages
        // and whether the app may go online.
        if (Features.ONLINE) appScope.launch {
            combine(
                catalog.state.map { it.catalog }.distinctUntilChanged(), downloads.records,
                knowledgeLanguages.languages.distinctUntilChanged(), networkPolicy.mode,
            ) { c, r, langs, mode -> Triple(c, r, langs) to (mode == app.granger.core.downloads.NetworkMode.ONLINE) }
                .collectLatest { (t, online) ->
                    try {
                        onlineIndexes.reconcile(t.first, t.second, t.third, Features.ONLINE, online, library.packs.first().mapTo(HashSet()) { it.filePath })
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.w("Granger", "Couldn't update Wikipedia online's indexes", e)
                    }
                }
        }
    }

    /**
     * Plan R2 Task 9: vectors for the books you add, made on the phone in the background, a few chunks per turn of the
     * model, so a question asked meanwhile waits for one batch at most.
     */
    val bookVectors = BookVectors(
        bookshelf, File(context.getExternalFilesDir(null), "vectors-own"),
        embed = { docs ->
            app.granger.research.ModelsAdapter.MODELS_IN_USE.withLock {
                modelManager.embedder()?.let { e -> withContext(Dispatchers.IO) { e.embedDocuments(docs) } }
            }
        },
        // At a cold start the catalog loads from its cache a moment after the books are scanned: wait for it.
        embedderSha = {
            kotlinx.coroutines.withTimeoutOrNull(30_000) { catalog.state.first { it.catalog != null } }
            installedEmbedderSha()
        },
        log = { Log.i("Granger", it) },
    )

    /** A new or found book: its words are indexed first, so the keyword search has it at once; its vectors follow. */
    suspend fun prepareBooks() {
        bookIndex.indexPending()
        if (meaningSearch) bookVectors.embedPending()
    }

    /**
     * Plan R1's vector files: the installed vectors downloads (a pack's under its ZIM name, a Classic's under its book id;
     * the newest version of each), then any pushed to `files/vectors/` with an index for the eval.
     */
    val vectorSources = CombinedVectorSources(
        ListedVectorSources(::installedVectors, ::installedEmbedderSha),
        bookVectors.sources,
        DirectoryVectorSources(File(context.getExternalFilesDir(null), "vectors")),
    )

    /** Each installed pack's time index (Plan H5b), by library pack id: the newest installed version of each. */
    suspend fun installedTimeIndexes(): Map<String, File> = installedSidecars(SidecarKind.TIME_INDEX).mapValues { it.value.file }

    /**
     * Each installed pack's sidecar of [kind] (Linked Library spec §5.1), by library pack id: the newest installed version,
     * with the sha256 of the pack file it belongs to, which a reader checks against the sidecar's own meta (spec §5.2).
     */
    suspend fun installedSidecars(kind: SidecarKind): Map<String, InstalledSidecar> {
        val records = downloads.records.first().filter { it.state == DownloadState.INSTALLED }
        val byItem = records.groupBy { it.itemId }
        val packsByPath = library.packs.first().associateBy { it.filePath }
        val ofKind = records.filter { it.kind == kind.itemKind && kind.parentOf(it.itemId) != null }.groupBy { it.itemId }
        val packs = ofKind.mapNotNull { (itemId, versions) ->
            val parentId = kind.parentOf(itemId) ?: return@mapNotNull null
            val parent = byItem[parentId]?.maxByOrNull { it.createdAt } ?: return@mapNotNull null
            val pack = packsByPath[parent.filePath] ?: return@mapNotNull null
            val newest = versions.maxBy { it.createdAt }
            Triple(pack.id, InstalledSidecar(newest.file, parent.hash, File(pack.filePath).name), newest.hash)
        }
        // Wikipedia online's small index set (Plan O1 Task 8), bound to its source; not when a pack already holds the
        // same file, whose copy then speaks for it (one topic, one set of marks on the Timeline).
        val inPacks = packs.mapTo(HashSet()) { it.third }
        val online = if (!Features.ONLINE) emptyList() else ofKind.mapNotNull { (itemId, versions) ->
            val source = kind.parentOf(itemId)?.takeIf(app.granger.online.OnlineSources::isOnline) ?: return@mapNotNull null
            if (source !in onlineSources.archives()) return@mapNotNull null
            val newest = versions.maxBy { it.createdAt }
            if (newest.hash in inPacks) null else source to InstalledSidecar(newest.file, "", null)
        }
        return packs.associate { it.first to it.second } + online
    }

    /**
     * Each installed Classic's sidecar of [kind] (its `.glink`, Plan LL1), by the bookshelf's id for it, with the sha256
     * of the EPUB it must have been built from: the newest installed version of each.
     */
    suspend fun installedBookSidecars(kind: SidecarKind): Map<String, InstalledSidecar> {
        val records = downloads.records.first().filter { it.state == DownloadState.INSTALLED }
        val books = records.filter { it.kind == ItemKind.BOOK && it.hash.isNotEmpty() }.groupBy { it.itemId }
        return records.filter { it.kind == kind.itemKind }.groupBy { it.itemId }.mapNotNull { (itemId, versions) ->
            val parentId = kind.parentOf(itemId) ?: return@mapNotNull null
            val book = books[parentId]?.maxByOrNull { it.createdAt } ?: return@mapNotNull null
            BookIds.of(BookOrigin.GRANGER, parentId) to InstalledSidecar(versions.maxBy { it.createdAt }.file, book.hash)
        }.toMap()
    }

    /** Reference links in the Classics (Plan LL1 Task 8): each book's `.glink`, resolved through the packs' QID indexes. */
    val referenceLinks: app.granger.ebook.ReferenceLinksSource by lazy {
        val thumbs = app.granger.home.ArticleThumbnails(File(context.cacheDir, "home-thumbs"))
        app.granger.ebook.ReferenceLinksSource(
            linksFiles = { installedBookSidecars(SidecarKind.LINKS) },
            qidFiles = {
                val installed = installedSidecars(SidecarKind.QID_INDEX)
                app.granger.home.WanderModel.encyclopediasOf(knownPacks.first(), knowledgeNow.value)
                    .mapNotNull { pack -> installed[pack.id]?.let { pack.id to it } }
            },
            lead = { packId, path -> articleLead(packId, path, thumbs) },
            bookOf = { id -> bookshelf.book(id)?.takeIf { it.status == app.granger.core.library.PackStatus.OK }?.let { app.granger.ebook.LibraryBook(it.id, it.title, it.coverPath) } },
            index = app.granger.core.library.MentionIndex(File(context.filesDir, "linked-library/mentions.sqlite")),
            background = appScope,
            wikipediaLanguage = { id ->
                knownPacks.first().firstOrNull { it.id == id }?.name?.let { WIKIPEDIA_LANGUAGE.find(it)?.groupValues?.get(1) }
            },
            bookLanguage = { id -> bookLanguages[id] ?: bookshelf.book(id)?.language?.let(::twoLetter)?.also { bookLanguages[id] = it } },
            contentFilter = contentFilter,
        )
    }

    /** An article's title, opening lines and picture, read out of its pack for a link's card. Blocking. */
    private fun articleLead(packId: String, path: String, thumbs: app.granger.home.ArticleThumbnails): app.granger.ebook.ArticleLead? {
        val archive = library.archive(packId) ?: return null
        val card = Perf.time("lead $path") { articleCards.card(archive, path) } ?: return null
        val title = card.title.ifBlank { path.substringAfterLast('/').replace('_', ' ') }
        return app.granger.ebook.ArticleLead(app.granger.core.library.PageTitles.clean(title), card.summary, card.thumbnail)
    }

    /** Article cards, remembered (the performance pass, Plan EX): what the topic pages, Explore and the quilt show of an article. */
    val articleCards: app.granger.home.ArticleCards by lazy {
        app.granger.home.ArticleCards(File(context.cacheDir, "article-cards.db"), app.granger.home.ArticleThumbnails(File(context.cacheDir, "home-thumbs")))
    }

    private val timeIndexCache = app.granger.core.library.TimeIndexCache()

    /** The phone's time indexes in Wander's pack order (Plan H5b), opened once and reopened when the packs or files change. */
    suspend fun timeIndexes(): app.granger.core.library.TimeIndexes = timeIndexesNow.get { listedTimeIndexes() }

    private val timeIndexesNow = Briefly<app.granger.core.library.TimeIndexes>()

    private suspend fun listedTimeIndexes(): app.granger.core.library.TimeIndexes {
        // Downloaded with each pack from the catalog (Plan H5b Task 3); files pushed for development fill any gap.
        val installed = installedTimeIndexes()
        val listed = app.granger.core.library.TimeIndexDirectory.listed(File(appContext.getExternalFilesDir(null), "timeindex"))
        val packs = app.granger.home.WanderModel.encyclopediasOf(knownPacks.first(), knowledgeNow.value)
        val files = packs.mapNotNull { pack -> (installed[pack.id] ?: listed[pack.name])?.let { pack to it } }
        return timeIndexCache.get(files.map { (p, f) -> "${p.id}|${f.path}|${f.lastModified()}" }) {
            app.granger.core.library.TimeIndexes(files.mapNotNull { (pack, file) -> app.granger.core.library.TimeIndex.open(file, pack.id) })
        }.also { t -> t.hide = { contentFilter.hides(it) } }
    }

    /** A vector source's language, for Related to stay in the page's (Plan LL2): a Wikipedia's from its name, a book's from the shelf. */
    private val bookLanguages = java.util.concurrent.ConcurrentHashMap<String, String>()

    private suspend fun sourceLanguage(source: app.granger.core.library.VectorSource): String? = when (source.kind) {
        app.granger.core.research.SourceKind.ARTICLE -> WIKIPEDIA_LANGUAGE.find(source.target)?.groupValues?.get(1)
        app.granger.core.research.SourceKind.BOOK -> bookLanguages[source.target]
            ?: bookshelf.book(source.target)?.language?.let(::twoLetter)?.also { bookLanguages[source.target] = it }
        else -> null
    }

    /** Related for a topic page (Plan LL2 Task 1's search): the installed vectors, on the dense search's threads. */
    val relatedFinder by lazy { app.granger.core.library.RelatedFinder(vectorSources, vectorPool, ::sourceLanguage) }

    /** A topic page's Wander from here (Plan LL2): the article Home's Wander starts at next, taken once. */
    val wanderFrom = kotlinx.coroutines.flow.MutableStateFlow<Pair<String, String>?>(null)

    /** The topic page in front of each Wikipedia article (Plan LL2). */
    val topics: app.granger.topic.TopicSource by lazy {
        val thumbs = app.granger.home.ArticleThumbnails(File(context.cacheDir, "home-thumbs"))
        val io = kotlinx.coroutines.Dispatchers.IO
        app.granger.topic.TopicSource(
            header = { packId, path -> kotlinx.coroutines.withContext(io) { topicHeader(packId, path, thumbs) } },
            lead = { packId, path ->
                kotlinx.coroutines.withContext(io) {
                    articleLead(packId, path, thumbs)?.let { if (contentFilter.noPicture(packId, path)) it.copy(thumbnail = null) else it }
                }
            },
            times = { kotlinx.coroutines.withContext(io) { timeIndexes() } },
            qids = { kotlinx.coroutines.withContext(io) { referenceLinks.qidIndexes() } },
            languageOf = { id -> knownPacks.first().firstOrNull { it.id == id }?.name?.let { WIKIPEDIA_LANGUAGE.find(it)?.groupValues?.get(1) } },
            packIds = { knownPacks.first().filter { it.name.isNotBlank() }.associate { it.name to it.id } },
            footer = if (Features.LINKED_CLASSICS) referenceLinks else null,
            related = { name, path, same -> relatedFor(name, path, same) },
            bookOf = { id -> bookshelf.book(id)?.takeIf { it.status == app.granger.core.library.PackStatus.OK }?.let { app.granger.ebook.LibraryBook(it.id, it.title, it.coverPath) } },
            libraryLanguages = { knowledgeNow.value.toSet() },
            sentence = { bookId, mention -> kotlinx.coroutines.withContext(io) { bookSentence(bookId, mention) } },
            figure = { packId, image -> kotlinx.coroutines.withContext(io) { Perf.time("figure") { library.archive(packId)?.let { thumbs.picture(it, image, TOPIC_PICTURE_PX) } } } },
            prefetch = { packId, paths ->
                kotlinx.coroutines.withContext(io) { Perf.time("prefetch ${paths.size}") { (library.archive(packId) as? app.granger.core.online.OnlineWikipediaArchive)?.prefetch(paths) } }
            },
            onlineQid = { packId, path -> kotlinx.coroutines.withContext(io) { (library.archive(packId) as? app.granger.core.online.OnlineWikipediaArchive)?.summary(path)?.qid } },
            records = if (!Features.ONLINE) null else { packId, lang, qid ->
                kotlinx.coroutines.withContext(io) {
                    // A topic page opened straight after a cold start comes before any screen has read the catalog (Pixel 6, Plan O6).
                    if (catalog.state.value.catalog == null) catalog.load()
                    remoteRecords.record(lang, qid, packId)?.withoutHidden(contentFilter.isOn)
                }
            },
        )
    }

    /**
     * Related for a topic page (Plan O1 builder's call 4): an online page's from Wikipedia's `morelike:`; a pack page's
     * from the vectors, as before, or from `morelike:` when it has none and Wikipedia online is on.
     */
    private suspend fun relatedFor(name: String, path: String, same: (String, String) -> Boolean): app.granger.core.library.Related {
        app.granger.core.online.OnlineWikipediaArchive.languageOf(name)?.let { return onlineRelated(it, path) }
        val local = relatedFinder.related(name, path, same)
        if (local.articles.isNotEmpty() || !Features.ONLINE) return local
        val lang = WIKIPEDIA_LANGUAGE.find(name)?.groupValues?.get(1) ?: return local
        if (onlineSources.archive(lang) == null) return local
        return onlineRelated(lang, path.substringAfterLast('/')).copy(books = local.books)
    }

    private suspend fun onlineRelated(lang: String, title: String): app.granger.core.library.Related = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val source = app.granger.core.online.OnlineWikipediaArchive.packName(lang)
        val hits = wikimedia.related(lang, title, app.granger.topic.TopicSource.SHOWN * 2)
            // Related is volunteered: an article the adult-content filter hides isn't offered.
            .filterNot { r -> r.qid?.let(contentFilter::hides) == true }
            .mapIndexed { i, r -> app.granger.core.library.RelatedHit(app.granger.core.research.SourceKind.ARTICLE, source, r.key, 1f - i / 100f) }
        app.granger.core.library.Related(hits, emptyList())
    }

    /** The sentence a mention sits in, read from its chapter in the EPUB (Plan LL2). Blocking. */
    private suspend fun bookSentence(bookId: String, mention: app.granger.core.library.BookMention): String? {
        val file = bookshelf.book(bookId)?.filePath?.let(::File)?.takeIf { it.isFile } ?: return null
        return runCatching {
            java.util.zip.ZipFile(file).use { zip ->
                val text = app.granger.core.books.XhtmlText.chunks(zip, mention.href).joinToString("\n") { it.text }
                app.granger.topic.QuoteSentence.around(text, mention.before, mention.quote)
            }
        }.getOrNull()
    }

    /** A topic page's header: the article's title, its lead's first sentences, and its picture at header size. Blocking. */
    private fun topicHeader(packId: String, path: String, thumbs: app.granger.home.ArticleThumbnails): app.granger.topic.TopicHeader? {
        val archive = library.archive(packId) ?: return null
        val card = articleCards.card(archive, path, TOPIC_PICTURE_PX, sentences = 3, maxChars = TOPIC_LEAD_CHARS) ?: return null
        val title = card.title.ifBlank { path.substringAfterLast('/').replace('_', ' ') }
        // The lead without its bracketed asides (birth names, dates and pronunciations the page shows already).
        return app.granger.topic.TopicHeader(app.granger.core.library.PageTitles.clean(title), app.granger.topic.TopicSource.blurb(card.summary), card.thumbnail)
    }

    private suspend fun installedVectors(): List<ListedVectors> {
        val records = downloads.records.first().filter { it.state == DownloadState.INSTALLED }
        val byItem = records.groupBy { it.itemId }
        val packsByPath = library.packs.first().associateBy { it.filePath }
        return records.filter { it.kind == ItemKind.VECTORS }.groupBy { it.itemId }.mapNotNull { (itemId, versions) ->
            val parentId = SidecarKind.VECTORS.parentOf(itemId) ?: return@mapNotNull null
            val parent = byItem[parentId]?.maxByOrNull { it.createdAt } ?: return@mapNotNull null
            val file = versions.maxBy { it.createdAt }.file
            when (parent.kind) {
                ItemKind.PACK -> packsByPath[parent.filePath]?.name?.ifBlank { null }?.let { ListedVectors(file, SourceKind.ARTICLE, it) }
                ItemKind.BOOK -> ListedVectors(file, SourceKind.BOOK, BookIds.of(BookOrigin.GRANGER, parentId))
                else -> null
            }
        }
    }

    /** The installed EmbeddingGemma's sha256, which a vector file must have been embedded with; null if unknown. */
    private suspend fun installedEmbedderSha(): String? {
        val embedders = catalog.state.value.catalog?.models.orEmpty().filter { it.role == ModelRole.EMBEDDER }.mapTo(HashSet()) { it.id }
        return downloads.records.first()
            .firstOrNull { it.kind == ItemKind.MODEL && it.state == DownloadState.INSTALLED && it.itemId in embedders }?.hash
    }

    /** The scan's threads: each file is split into ranges searched at once. */
    private val vectorPool by lazy { Executors.newFixedThreadPool(4) }

    val denseFinder by lazy { DensePassageFinder(library, bookshelf, shelves, vectorSources, vectorPool, slices = packSlices) }

    /** The book ids of the Granger Classics the catalog files under literature: novels, drama, poetry and libretti (R0's fiction list). */
    fun literatureBooks(): Set<String> = catalog.state.value.catalog?.books.orEmpty()
        .filter { it.domain == "literature" }.mapTo(HashSet()) { BookIds.of(BookOrigin.GRANGER, it.id) }

    val fictionWeight = FictionWeight(::literatureBooks)

    /** Research's thresholds, set from the Pixel 6 measurements (Plan 8 Task 9) and Plan R1's merging sweep. */
    val rankingConfig = RankingConfig()

    /**
     * Search's Best matches (Plan SQ Phase 4): Ask's keyword and dense retrieval, ranked as Ask ranks, with no model. The
     * dense side runs where the embedder and vectors are installed; elsewhere the keyword side alone.
     */
    /** How long search by meaning takes here; a slow phone's Best matches go keyword-only (Plan SQ Phase 6). */
    val embedSpeed = app.granger.search.EmbedSpeed()

    /** Best matches for Search, each query's meaning step timed into [embedSpeed]. */
    fun bestStream(query: String): kotlinx.coroutines.flow.Flow<app.granger.core.research.BestMatches.Result> = kotlinx.coroutines.flow.flow {
        var timed = false
        // Best matches keep to the library's languages too (Neal, 2026-11-16), as Titles and Articles do.
        val allowed = knowledgeNow.value.toSet()
        val packLanguages = library.packs.first().associate { it.id to it.language }
        suspend fun allowedSource(p: app.granger.core.research.Passage): Boolean = when (p.kind) {
            app.granger.core.research.SourceKind.ARTICLE -> packLanguages[p.sourceId]?.let { l -> allowed.any { app.granger.core.library.TitleMatch.inLanguage(l, it) } } ?: true
            app.granger.core.research.SourceKind.BOOK -> (bookLanguages[p.sourceId] ?: bookshelf.book(p.sourceId)?.language?.let(::twoLetter)?.also { bookLanguages[p.sourceId] = it })
                ?.let { it in allowed } ?: true
            else -> true
        }
        bestMatches.stream(query).collect { result ->
            if (!timed && result.byMeaning && result.embedMs + result.denseMs > 0) {
                embedSpeed.record(result.embedMs + result.denseMs)
                timed = true
            }
            emit(result.copy(matches = result.matches.filter { allowedSource(it.passage) }))
        }
    }

    val bestMatches by lazy {
        app.granger.core.research.BestMatches(
            passageFinder, denseFinder,
            // Plan SQ Phase 6: no embedder on a phone without search by meaning, and none while it is too slow here.
            embed = { q ->
                if (!meaningSearch || !embedSpeed.useMeaning()) null
                else app.granger.research.ModelsAdapter(modelManager).embedder()?.embed(listOf(q), isQuery = true)?.single()
            },
            config = rankingConfig, sourceWeight = fictionWeight,
            articleKey = {
                // A pack's family ("wikipedia_en"), so one article in two Wikipedia packs is one row; a book is its own.
                val families = library.packs.first().filter { it.name.isNotBlank() }.associate { it.id to app.granger.core.library.TitleMatch.family(it.name) }
                ({ p: app.granger.core.research.Passage ->
                    (if (p.kind == app.granger.core.research.SourceKind.ARTICLE) families[p.sourceId] ?: p.sourceId else p.sourceId) to p.location
                })
            },
            quoted = passageFinder::quoted,
        )
    }

    /** The owl's reaction topics (assets/owl/topics.txt), read once; null when unreadable. */
    private val owlTopics by lazy {
        runCatching { context.assets.open("owl/topics.txt").bufferedReader().use { TopicCentroids.parse(it.readText()) } }
            .onFailure { android.util.Log.w("Granger", "Owl topics unreadable", it) }
            .getOrNull()
    }

    /** The topics, when they were built from the embedder installed now; else the owl reacts with its default lines. */
    private suspend fun owlCentroids(): TopicCentroids? {
        val parsed = owlTopics ?: return null
        return parsed.centroids.takeIf { installedEmbedderSha() == parsed.embedderSha }
    }

    val research = ResearchPipeline(
        passageFinder, ModelsAdapter(modelManager), rankingConfig,
        sourceWeight = fictionWeight, dense = if (DENSE_RETRIEVAL) denseFinder else null,
        reserveNamed = true, yearFacts = true, olderWords = true, reactions = ::owlCentroids,
        // Ask Preview: a medical question shows the banner and quoted passages, no generated prose (Neal, 2026-11-02).
        medicalQuotes = true,
    )
}

/** A sidecar file on the phone and the sha256 of the pack file it must have been built from (and that file's name). */
data class InstalledSidecar(val file: File, val packSha256: String, val packName: String? = null)

/** `wikipedia_en_history_maxi_2026-09.zim` -> `en`: a Wikipedia pack's language, as its QID index names it. */
private val WIKIPEDIA_LANGUAGE = Regex("^wikipedia_([a-z]{2,3})_")

/** A topic page's lead and header picture (Plan LL2). */
private const val TOPIC_LEAD_CHARS = 420
private const val TOPIC_PICTURE_PX = 900

/** The Wikipedias online Granger reads, whatever the knowledge languages (Plan O3 builder's call 8). */
private val ONLINE_LANGUAGES = listOf("en", "es")

/** Recent reads Ready offline looks at, at most (Plan O3 Task 8). */
private const val READY_RECENT = 60

/** A room's best-known articles offered to keep (Plan O2 Task 6). */
private const val ROOM_KEEP = 60

/** Wikipedia online's hits per group and language in Search (Plan O1 Task 7). */
private const val ONLINE_HITS = 8

/** A book's language as two letters: `en` from `en`, `eng` or `en-GB`; `es` from `spa`. */
internal fun twoLetter(language: String): String? {
    val l = language.lowercase().substringBefore('-').substringBefore('_')
    return when {
        l.isBlank() -> null
        l.length == 2 -> l
        else -> java.util.Locale.forLanguageTag(l).language.takeIf { it.length == 2 } ?: mapOf("eng" to "en", "spa" to "es")[l]
    }
}
