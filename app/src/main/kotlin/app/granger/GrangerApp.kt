package app.granger

import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.provider.Settings
import app.granger.core.ai.DeviceTier
import app.granger.core.ai.DeviceTiers
import app.granger.core.ai.InstalledModels
import app.granger.core.ai.LiteRtLlmEngine
import app.granger.core.ai.LiteRtTextEmbedder
import app.granger.core.ai.LlmBackend
import app.granger.core.ai.ModelManager
import app.granger.core.catalog.CatalogRepository
import app.granger.core.catalog.CatalogSource
import app.granger.core.downloads.AndroidVolumes
import app.granger.core.downloads.DownloadDatabase
import app.granger.core.downloads.DownloadManager
import app.granger.core.downloads.DownloadRecord
import app.granger.core.downloads.DownloadRunner
import app.granger.core.downloads.DownloadState
import app.granger.core.downloads.Downloads
import app.granger.core.downloads.DownloadsHost
import app.granger.core.downloads.FileDownloader
import app.granger.core.downloads.ItemKind
import app.granger.core.downloads.VolumeSource
import app.granger.core.downloads.WorkManagerScheduler
import app.granger.core.library.BookIndex
import app.granger.core.library.BookDetails
import app.granger.core.library.BookOrigin
import app.granger.archive.ArchiveDownloads
import app.granger.archive.ArchivePrefs
import app.granger.archive.SharedArchivePrefs
import app.granger.archive.ArchiveUserAgent
import app.granger.core.archive.ArchiveOrgClient
import app.granger.core.archive.ArchiveQuery
import app.granger.core.library.Bookshelf
import app.granger.core.library.Library
import app.granger.core.library.LibraryDatabase
import app.granger.core.library.LibraryPassageFinder
import app.granger.core.library.LibrarySearch
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
import app.granger.core.library.RoomShelves
import app.granger.core.library.Shelves
import app.granger.core.library.ZimLibrarySearch
import app.granger.core.media.ContentResolverOpener
import app.granger.core.media.FileTagReadGuard
import app.granger.core.media.FolderSource
import app.granger.core.media.MediaStoreSource
import app.granger.core.media.PhoneScanner
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
import app.granger.research.ModelsAdapter
import app.granger.research.installedModelsOf
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.io.File
import java.util.Base64
import java.util.concurrent.TimeUnit

open class GrangerApp : Application(), DownloadsHost {
    val container: AppContainer by lazy { AppContainer(this) }

    override fun onCreate() {
        super.onCreate()
        if (Features.PLAYER) startMedia()
    }

    /**
     * The player hub is made here, not on first use: a playback service the system restarts on its own stops at once
     * unless the hub has installed its dependencies. Then the phone scans start (without the audio permission they
     * only mark phone items missing; nothing asks). JVM tests swap this out (`TestGrangerApp`).
     */
    protected open fun startMedia() {
        container.playerHub
        container.phoneMediaScans.start()
        // Granger's own downloads: no permission needed. Quiet, on the app scope: a failure never crashes the start.
        container.appScope.launch { container.mediaDownloader.rescanQuietly() }
    }

    override val downloadRunner: DownloadRunner get() = container.downloadRunner
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
            android.util.Log.w("Granger", "Downloaded book $ref is not a readable EPUB")
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
    onBookInstalled(
        bookshelf, File(record.filePath),
        if (identifier != null) BookOrigin.ARCHIVE_ORG else BookOrigin.GRANGER,
        identifier ?: record.itemId,
        sidecar?.let { ArchiveDownloads.readSidecar(it)?.first },
        startIndexing,
    )
}

/**
 * Whether Research reranks passages with the embedding model. Off: on the Pixel 6 a query plus 12 passages took
 * 4.2–4.7 s on the GPU (9.6–10.2 s on the CPU), over the 3 s budget, so ranking is lexical (Plan 8 Task 9).
 */
const val RERANK_WITH_EMBEDDER = false

/** Where the embedding model runs when reranking is on: the GPU, about twice as fast as the CPU on the Pixel 6. */
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
    val library: Library = RoomLibrary(libraryDb, KiwixZimOpener(context), packDirs)
    val bookshelf: Bookshelf = RoomBookshelf(
        libraryDb, bookDirs,
        importDir = { File(context.getExternalFilesDir(null) ?: context.filesDir, "books/imports") },
        coversDir = File(context.filesDir, "covers"),
    )
    val bookIndex = BookIndex(libraryDb)

    /** Set by the first activity start in this process: the book scan and index pass run once per process. */
    val booksScannedThisProcess = AtomicBoolean(false)
    /** Phone music and audiobooks. */
    val media: MediaLibrary = RoomMediaLibrary(libraryDb)

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

    /** The one way to the players (spec §8); made in [GrangerApp.onCreate], on the main thread. */
    val playerHub: PlayerHub by lazy {
        MediaControllerPlayerHub(context, media, playback, CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate))
    }

    /** The player's settings (skip intervals, ReplayGain, headset autoplay), shared with the playback service. */
    val playerPrefs: SharedPreferences = context.getSharedPreferences(PlayerSettingsKeys.PREFS, Context.MODE_PRIVATE)
    val shelves: Shelves = RoomShelves(libraryDb, library, bookshelf, media)

    /** The user's music playlists. */
    val playlists: Playlists = RoomPlaylists(libraryDb)
    val sessions: ReadingSessions = RoomReadingSessions(libraryDb)
    val bookOpener: BookOpener = ReadiumBookOpener(context)

    /** How books look; one set for every book. */
    val readingPrefs = ReadingPrefsStore(context.getSharedPreferences("reading", Context.MODE_PRIVATE))

    /** Clean-exit and reboot tracking for the launch decision; BOOT_COUNT is -1 (unavailable) on odd builds. */
    val sessionMarker = SessionMarker(
        prefs = context.getSharedPreferences("session", Context.MODE_PRIVATE),
        bootCount = { Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, -1) },
    )
    val search: LibrarySearch = ZimLibrarySearch(library, bookIndex)
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

    /** Where archive.org metadata sidecars live, whatever volume the download goes to. */
    val archiveSidecarDir: File = File(context.getExternalFilesDir(null) ?: context.filesDir, "books/archive-org")

    val volumes: VolumeSource = AndroidVolumes(context)
    val downloadPrefs: DownloadPrefs = SharedDownloadPrefs(context)

    private val downloadDao = DownloadDatabase.create(context).downloadDao()

    val downloads: Downloads = DownloadManager(
        dao = downloadDao,
        scheduler = WorkManagerScheduler(context),
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

    /** Set by the first activity start in this process: turns left PENDING by a dead process are settled once. */
    val chatsSettledThisProcess = AtomicBoolean(false)

    /** The model files on disk, by catalog role; empty until the catalog has loaded from its cache. */
    val installedModels: Flow<InstalledModels> =
        combine(downloads.records, catalog.state.map { it.catalog?.models.orEmpty() }) { r, m -> installedModelsOf(r, m) }
            .flowOn(Dispatchers.IO)

    private val modelCacheDir = context.cacheDir.path

    val modelManager = ModelManager(
        deviceTier, installedModels,
        { path, backend -> LiteRtLlmEngine(path, backend, modelCacheDir) },
        { path -> LiteRtTextEmbedder(path, modelCacheDir, EMBEDDER_BACKEND) },
        appScope, useEmbedder = RERANK_WITH_EMBEDDER,
    )

    val passageFinder = LibraryPassageFinder(library, bookIndex, bookshelf, shelves)

    /** Research's thresholds, set from the Pixel 6 measurements (Plan 8 Task 9). */
    val rankingConfig = RankingConfig()

    val research = ResearchPipeline(passageFinder, ModelsAdapter(modelManager), rankingConfig)
}
