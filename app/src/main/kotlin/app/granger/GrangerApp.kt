package app.granger

import android.app.ActivityManager
import android.app.Application
import android.content.Context
import app.granger.core.ai.DeviceTier
import app.granger.core.ai.DeviceTiers
import app.granger.core.catalog.CatalogRepository
import app.granger.core.catalog.CatalogSource
import app.granger.core.downloads.AndroidVolumes
import app.granger.core.downloads.DownloadDatabase
import app.granger.core.downloads.DownloadManager
import app.granger.core.downloads.DownloadRunner
import app.granger.core.downloads.Downloads
import app.granger.core.downloads.DownloadsHost
import app.granger.core.downloads.FileDownloader
import app.granger.core.downloads.ItemKind
import app.granger.core.downloads.VolumeSource
import app.granger.core.downloads.WorkManagerScheduler
import app.granger.core.library.Library
import app.granger.core.library.LibraryDatabase
import app.granger.core.library.LibrarySearch
import app.granger.core.library.RoomLibrary
import app.granger.core.library.ZimLibrarySearch
import app.granger.core.zim.KiwixZimOpener
import app.granger.core.zim.ZimResolver
import app.granger.downloads.DownloadPrefs
import app.granger.downloads.SharedDownloadPrefs
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import okhttp3.OkHttpClient
import java.io.File
import java.util.Base64
import java.util.concurrent.TimeUnit

class GrangerApp : Application(), DownloadsHost {
    val container: AppContainer by lazy { AppContainer(this) }

    override val downloadRunner: DownloadRunner get() = container.downloadRunner
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

    val library: Library = RoomLibrary(LibraryDatabase.create(context), KiwixZimOpener(context), packDirs)
    val search: LibrarySearch = ZimLibrarySearch(library)
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
        // Every installed pack counts towards the cap, SD card included (spec "Storage cap").
        installedPackBytes = { library.packs.first().sumOf { it.sizeBytes } },
        // Uncapped until Plan 8 wires :core:billing.
        capBytes = { Long.MAX_VALUE },
    )

    val downloadRunner = DownloadRunner(
        dao = downloadDao,
        downloader = FileDownloader(http),
        onInstalled = { record -> if (record.kind == ItemKind.PACK) library.refresh() },
    )
}
