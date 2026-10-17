package app.granger

import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.provider.Settings
import app.granger.core.ai.DeviceTier
import app.granger.core.ai.DeviceTiers
import app.granger.core.catalog.CatalogRepository
import app.granger.core.catalog.CatalogSource
import app.granger.core.downloads.AndroidVolumes
import app.granger.core.downloads.DownloadDatabase
import app.granger.core.downloads.DownloadManager
import app.granger.core.downloads.DownloadRunner
import app.granger.core.downloads.DownloadState
import app.granger.core.downloads.Downloads
import app.granger.core.downloads.DownloadsHost
import app.granger.core.downloads.FileDownloader
import app.granger.core.downloads.ItemKind
import app.granger.core.downloads.VolumeSource
import app.granger.core.downloads.WorkManagerScheduler
import app.granger.core.library.BookIndex
import app.granger.core.library.Bookshelf
import app.granger.core.library.Library
import app.granger.core.library.LibraryDatabase
import app.granger.core.library.LibrarySearch
import app.granger.core.library.ReadingSessions
import app.granger.core.library.RoomBookshelf
import app.granger.core.library.RoomLibrary
import app.granger.core.library.RoomReadingSessions
import app.granger.core.library.RoomShelves
import app.granger.core.library.Shelves
import app.granger.core.library.ZimLibrarySearch
import app.granger.core.zim.KiwixZimOpener
import app.granger.core.zim.ZimResolver
import app.granger.sources.DownloadPrefs
import app.granger.sources.SharedDownloadPrefs
import app.granger.launch.SessionMarker
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.io.File
import java.util.Base64
import java.util.concurrent.TimeUnit

class GrangerApp : Application(), DownloadsHost {
    val container: AppContainer by lazy { AppContainer(this) }

    override val downloadRunner: DownloadRunner get() = container.downloadRunner
}

/**
 * Files a finished book download. Never throws: the runner would turn an exception into FAILED for a download whose
 * file is already installed, and a book that didn't register is found by the next start's scan.
 */
internal suspend fun onBookInstalled(bookshelf: Bookshelf, file: File, itemId: String, startIndexing: () -> Unit) {
    try {
        if (bookshelf.addDownloaded(file, itemId) == null) {
            android.util.Log.w("Granger", "Downloaded book $itemId is not a readable EPUB")
            return
        }
        startIndexing()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        android.util.Log.e("Granger", "Filing downloaded book $itemId failed", e)
    }
}

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
    val shelves: Shelves = RoomShelves(libraryDb, library, bookshelf)
    val sessions: ReadingSessions = RoomReadingSessions(libraryDb)

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

    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    val catalog: CatalogSource = CatalogRepository(
        client = http,
        url = BuildConfig.CATALOG_URL,
        publicKey = Base64.getDecoder().decode(BuildConfig.CATALOG_PUBLIC_KEY),
        cacheDir = File(context.filesDir, "catalog"),
        includeUnevaluatedModels = BuildConfig.DEBUG,
    )

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
                onBookInstalled(bookshelf, File(record.filePath), record.itemId) {
                    appScope.launch { bookIndex.indexPending() }
                }
            }
        },
    )
}
