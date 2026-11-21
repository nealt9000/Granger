package app.granger.librivox

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.granger.core.archive.ArchiveOrgClient
import app.granger.core.archive.LibriVoxClient
import app.granger.core.downloads.Downloads
import app.granger.media.MediaDownloader
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers

/**
 * Android's LibriVox model: the shared [LibriVoxModel] (Plan I6 Task 2) with the app's media downloader, and the search
 * field's text as Compose state, written synchronously on every keystroke (Plan 7 lesson).
 */
class LibriVoxViewModel(
    client: LibriVoxClient,
    archive: ArchiveOrgClient,
    media: MediaDownloader,
    downloads: Downloads,
    appScope: CoroutineScope,
    io: CoroutineDispatcher = Dispatchers.IO,
    now: () -> Long = System::currentTimeMillis,
    /** The library's languages, main first, read at each page (Plan L3): the list keeps their books. */
    languages: () -> List<String> = { listOf("en") },
) : LibriVoxModel(client, archive, media, downloads, appScope, io, now, languages) {
    override var searchText by mutableStateOf("")
        protected set
}
