package app.granger

import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.provider.Settings
import app.granger.core.books.PdfSupport
import app.granger.core.ai.DeviceTier
import app.granger.core.ai.DeviceTiers
import app.granger.core.ai.InstalledModels
import app.granger.core.ai.LiteRtLlmEngine
import app.granger.core.ai.LiteRtTextEmbedder
import app.granger.core.ai.LlmBackend
import app.granger.core.ai.ModelManager
import app.granger.core.catalog.CatalogRepository
import app.granger.core.catalog.CatalogSource
import app.granger.core.catalog.ModelRole
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
import app.granger.core.downloads.VectorDownloads
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
import app.granger.core.speech.TextReadSource
import app.granger.core.speech.VoiceId
import app.granger.ebook.BookReadSource
import app.granger.reader.ArticleReadSource
import app.granger.reader.PackDocuments
import app.granger.settings.SharedVoicePrefs
import app.granger.settings.VoicePrefs
import android.util.Log
import app.granger.core.library.Book
import app.granger.core.library.BookIndex
import app.granger.core.library.BookDetails
import app.granger.core.library.BookOrigin
import app.granger.archive.ArchiveDownloads
import app.granger.archive.ArchivePrefs
import app.granger.archive.SharedArchivePrefs
import app.granger.settings.AppearancePrefs
import app.granger.settings.SharedAppearancePrefs
import app.granger.archive.ArchiveUserAgent
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
import app.granger.research.AnswerText
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
import kotlinx.coroutines.flow.Flow
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
import java.util.Base64
import java.util.concurrent.TimeUnit
import app.granger.core.library.BookIds
import app.granger.core.library.GutenbergBooks
import app.granger.core.library.isGutenberg
import app.granger.library.GutenbergCovers
import app.granger.core.library.DensePassageFinder
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

    override fun onCreate() {
        super.onCreate()
        // Only keeps the context: PdfBox reads its font metrics from assets on the first PDF.
        PdfSupport.init(this)
        if (Features.PLAYER) startMedia()
        startVectorDownloads()
        startGutenbergSync()
        resumeDownloads()
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
        WidgetUpdater(container.playerHub, container.appScope) { model ->
            container.widgetModel.value = model
            container.widgetStore.save(model)
            PlayerWidget().updateAll(this)
        }
        // Granger's own downloads: no permission needed. Quiet, on the app scope: a failure never crashes the start.
        container.appScope.launch { container.mediaDownloader.rescanQuietly() }
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
    val sidecar = identifier?.takeIf { ArchiveQuery.isValidIdentifier(it) }?.let { ArchiveDownloads.sidecarFile(sidecarDir, it) }
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

/** Where the embedding model runs: the GPU, about twice as fast as the CPU on the Pixel 6. */
val EMBEDDER_BACKEND = LlmBackend.GPU

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

    private val libraryDb = LibraryDatabase.create(context)
    // A removed pack takes its PDFs (pack documents, Plan P2) with it.
    val library: Library = RoomLibrary(libraryDb, KiwixZimOpener(context), packDirs, onRemoved = { bookshelf.removePackDocuments(it) })
    val bookshelf: Bookshelf = RoomBookshelf(
        libraryDb, bookDirs,
        importDir = { File(context.getExternalFilesDir(null) ?: context.filesDir, "books/imports") },
        coversDir = File(context.filesDir, "covers"),
    )
    val bookIndex = BookIndex(libraryDb)

    /** PDFs inside Kiwix packs, copied out to the internal volume's `books/packs` when tapped (Plan P2). */
    val packDocuments = PackDocuments(bookshelf, library::archive, dir = { File(bookDirs().first(), "packs") })

    /** The books of the installed Project Gutenberg packs (Plan GB), copied out to the internal volume's `books/gutenberg` when opened. */
    val gutenbergBooks = GutenbergBooks(
        libraryDb, library::archive, dir = { File(bookDirs().first(), "gutenberg") }, coversDir = File(context.filesDir, "covers"),
    )

    /** Gutenberg covers read from their packs before a book's first open, kept in the cache. */
    val gutenbergCovers = GutenbergCovers({ library.packs.first() }, library::archive, File(context.cacheDir, "gutenberg-covers"))

    /**
     * Brings back a book's missing file for the reader: a pack document copied out again, or a Gutenberg book copied out
     * of its pack, which is then indexed (Plan GB Decision 6: its full text joins Search and Ask after the first open).
     */
    suspend fun restoreBook(book: Book): Boolean =
        packDocuments.restore(book) || gutenbergBooks.restore(book).also { if (it) appScope.launch { bookIndex.indexPending() } }

    /**
     * Keeps the Gutenberg books' rows following the installed Gutenberg packs (Plan GB Decision 3): synced on start, on
     * an install, a new snapshot, a pack going missing and a removal, off the main thread.
     */
    fun followGutenberg() {
        appScope.launch {
            library.packs
                .map { packs -> packs.filter { it.isGutenberg } }
                .distinctUntilChanged { old, new -> old.map { it.id to it.status } == new.map { it.id to it.status } }
                .collectLatest { packs ->
                    try {
                        val started = SystemClock.elapsedRealtime()
                        gutenbergBooks.sync(packs)
                        if (packs.isNotEmpty()) {
                            Log.i("Granger", "Gutenberg sync: ${packs.size} packs in ${SystemClock.elapsedRealtime() - started} ms")
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.w("Granger", "Couldn't list the Gutenberg packs' books", e)
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
        InstalledVoices(dirs, { system.voices() }, SherpaSpeech.Factory(dirs), system)
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
            // A Research answer read aloud (Plan V2): `text:answer:<turnId>`, re-read from the kept chat.
            is ReadSourceKey.Text -> k.key.removePrefix("answer:").takeIf { it != k.key }?.toLongOrNull()?.let { id ->
                researchChats.turn(id)?.let { t ->
                    TextReadSource(key, t.question, AnswerText.spoken(t.answer.ifBlank { t.guess }, t.medical))
                }
            }
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

    /** The one way to the players (spec §8); made in [GrangerApp.onCreate], on the main thread. */
    val playerHub: PlayerHub by lazy {
        MediaControllerPlayerHub(
            context, media, playback, CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
            // A cover outside the provider's roots keeps its file:// URI, which the in-process notification can still read.
            artwork = { path -> artwork.uri(path) ?: Uri.fromFile(File(path)) },
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
        AutoBrowseTree(media, playlists, playback, mediaIndex, playResumer, songs, playerHub, artwork)
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
    val search: LibrarySearch = ZimLibrarySearch(library, bookIndex, bookshelf = bookshelf)
    val resolver = ZimResolver(library::archive, library::cachedArchive)

    val totalRamBytes: Long = ActivityManager.MemoryInfo()
        .also { context.getSystemService(ActivityManager::class.java).getMemoryInfo(it) }
        .totalMem
    val deviceTier: DeviceTier = DeviceTiers.forTotalRam(totalRamBytes)

    private val archiveUserAgent = "Granger/${BuildConfig.VERSION_NAME} (+https://github.com/nealt9000/Granger)"

    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        // A network interceptor sees each redirect hop, so archive.org file downloads carry it to the item's server.
        .addNetworkInterceptor(ArchiveUserAgent(archiveUserAgent))
        .build()

    val catalog: CatalogSource = CatalogRepository(
        client = http,
        url = BuildConfig.CATALOG_URL,
        publicKey = Base64.getDecoder().decode(BuildConfig.CATALOG_PUBLIC_KEY),
        cacheDir = File(context.filesDir, "catalog"),
        includeUnevaluatedModels = BuildConfig.DEBUG,
    )

    val archiveClient = ArchiveOrgClient(http, archiveUserAgent)
    val librivoxClient = LibriVoxClient(http, archiveUserAgent)
    val archivePrefs: ArchivePrefs = SharedArchivePrefs(context)
    val appearancePrefs: AppearancePrefs = SharedAppearancePrefs(context)
    val homePrefs: HomePrefs = SharedHomePrefs(context)
    val onboardingPrefs: OnboardingPrefs = SharedOnboardingPrefs(context)

    /** Where archive.org metadata sidecars live, whatever volume the download goes to. */
    val archiveSidecarDir: File = File(context.getExternalFilesDir(null) ?: context.filesDir, "books/archive-org")

    val volumes: VolumeSource = AndroidVolumes(context)
    val downloadPrefs: DownloadPrefs = SharedDownloadPrefs(context)

    private val downloadDao = DownloadDatabase.create(context).downloadDao()

    /** The one download queue (Plan DL Decision 1), drained by the `download-queue` worker. */
    val downloadQueue: DownloadQueue by lazy {
        DownloadQueue(
            downloadDao, downloadRunner, downloadPrefs, unmetered = { isUnmetered(context) }, elapsed = SystemClock::elapsedRealtime,
        )
    }

    val downloads: Downloads = DownloadManager(
        dao = downloadDao,
        scheduler = WorkManagerScheduler(context) { downloadQueue },
        prefs = downloadPrefs,
        // Deferred: the queue needs the runner, made below.
        progress = flow { emitAll(downloadQueue.progress) },
        unmetered = unmeteredFlow(context),
        // Every installed pack and book counts towards the cap, SD card included (spec "Storage cap").
        // Books are not in the library yet, so they come from the downloads ledger.
        installedPackBytes = {
            library.packs.first().sumOf { it.sizeBytes } +
                downloadDao.getAll().filter { it.kind == ItemKind.BOOK && it.state == DownloadState.INSTALLED }.sumOf { it.sizeBytes }
        },
        // Uncapped until Plan 8 wires :core:billing.
        capBytes = { Long.MAX_VALUE },
    )

    val downloadRunner = DownloadRunner(
        dao = downloadDao,
        downloader = FileDownloader(http),
        onInstalled = { record ->
            if (record.kind == ItemKind.PACK) {
                library.fileReplaced(record.filePath)
                library.refresh()
            } else if (record.kind == ItemKind.BOOK) {
                fileBookRecord(bookshelf, archiveSidecarDir, record) { appScope.launch { bookIndex.indexPending() } }
            } else if (record.kind == ItemKind.AUDIO) {
                mediaDownloader.onFileInstalled(record)
            } else if (record.kind == ItemKind.VOICE) {
                // The verified zip becomes voices/<id>/ and goes away; the voice list picks the new voice up.
                VoiceUnpacker.unpack(record.file).onFailure { Log.w("Granger", "Couldn't unpack the voice ${record.itemId}", it) }
                installedVoices.refresh()
            }
            // Its vectors go right behind it, before the queue moves on (Plan DL Decision 2).
            if (record.kind == ItemKind.PACK || record.kind == ItemKind.BOOK) {
                try {
                    vectorDownloads.reconcile(catalog.state.value.catalog, downloadDao.getAll())
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w("Granger", "Couldn't queue the search vectors of ${record.itemId}", e)
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
        appScope, useEmbedder = embedderWanted::get,
    )

    /** Ask's models, fetched from Ask's own empty state (Plan U3a). */
    val librarian = app.granger.research.Librarian(catalog, downloads, volumes, downloadPrefs, DENSE_RETRIEVAL)

    val passageFinder = LibraryPassageFinder(library, bookIndex, bookshelf, shelves, media = mediaIndex)

    /** Search vectors follow their pack or book (Plan R1 Task 10): queued when it installs, removed with it. */
    private val vectorDownloads = VectorDownloads(downloads) { downloadPrefs.wifiOnly }

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
                        vectorDownloads.reconcile(c, r)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.w("Granger", "Couldn't update the search vectors downloads", e)
                    }
                }
        }
    }

    /**
     * Plan R1's vector files: the installed vectors downloads (a pack's under its ZIM name, a Classic's under its book id;
     * the newest version of each), then any pushed to `files/vectors/` with an index for the eval.
     */
    val vectorSources = CombinedVectorSources(
        ListedVectorSources(::installedVectors, ::installedEmbedderSha),
        DirectoryVectorSources(File(context.getExternalFilesDir(null), "vectors")),
    )

    private suspend fun installedVectors(): List<ListedVectors> {
        val records = downloads.records.first().filter { it.state == DownloadState.INSTALLED }
        val byItem = records.groupBy { it.itemId }
        val packsByPath = library.packs.first().associateBy { it.filePath }
        return records.filter { it.kind == ItemKind.VECTORS }.groupBy { it.itemId }.mapNotNull { (itemId, versions) ->
            val parentId = VectorDownloads.parentOf(itemId) ?: return@mapNotNull null
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

    val denseFinder by lazy { DensePassageFinder(library, bookshelf, shelves, vectorSources, vectorPool) }

    /** The book ids of the Granger Classics the catalog files under literature: novels, drama, poetry and libretti (R0's fiction list). */
    fun literatureBooks(): Set<String> = catalog.state.value.catalog?.books.orEmpty()
        .filter { it.domain == "literature" }.mapTo(HashSet()) { BookIds.of(BookOrigin.GRANGER, it.id) }

    val fictionWeight = FictionWeight(::literatureBooks)

    /** Research's thresholds, set from the Pixel 6 measurements (Plan 8 Task 9) and Plan R1's merging sweep. */
    val rankingConfig = RankingConfig()

    val research = ResearchPipeline(
        passageFinder, ModelsAdapter(modelManager), rankingConfig,
        sourceWeight = fictionWeight, dense = if (DENSE_RETRIEVAL) denseFinder else null,
        reserveNamed = true, yearFacts = true,
    )
}
