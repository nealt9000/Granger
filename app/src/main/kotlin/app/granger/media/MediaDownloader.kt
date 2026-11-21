package app.granger.media

import app.granger.core.downloads.Downloads
import app.granger.core.downloads.VolumeSource
import app.granger.core.library.MediaLibrary
import app.granger.core.media.DownloadedMediaScanner
import app.granger.sources.DownloadPrefs
import app.granger.sources.asDownloadVolumes
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers

/**
 * Android's media downloader: the shared [MediaItemDownloader] (Plan I6 Task 3) over the app's volumes and its
 * downloaded-media scanner, with the constructor it had before.
 */
class MediaDownloader(
    downloads: Downloads,
    scanner: DownloadedMediaScanner,
    media: MediaLibrary,
    volumes: VolumeSource,
    prefs: DownloadPrefs,
    scope: CoroutineScope,
    io: CoroutineDispatcher = Dispatchers.IO,
    /** After every downloaded-media scan (a filing, a removal, "Rescan now"): tells other apps' views to refresh. */
    onChanged: () -> Unit = {},
    /** Before a removal deletes anything: the player lets go of the book or tracks (`PlayerHub.unload`). */
    onRemoving: (audiobookId: String, trackIds: Set<String>) -> Unit = { _, _ -> },
) : MediaItemDownloader(downloads, scanner, media, volumes.asDownloadVolumes(), prefs, scope, io, onChanged, onRemoving)
