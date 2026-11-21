package app.granger.archive

import androidx.annotation.StringRes
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.granger.ScreenTextIds
import app.granger.UiText
import app.granger.core.archive.ArchiveItem
import app.granger.core.archive.ArchiveKind
import app.granger.core.archive.ArchiveOrgClient
import app.granger.core.archive.AudioOffer
import app.granger.core.downloads.DownloadRecord
import app.granger.core.downloads.Downloads
import app.granger.core.downloads.VolumeSource
import app.granger.core.library.Bookshelf
import app.granger.core.media.MediaManifest
import app.granger.core.media.MediaManifests
import app.granger.home.toCivilDate
import app.granger.media.MediaDownloader
import app.granger.media.MediaDownloads
import app.granger.sources.DownloadPrefs
import app.granger.sources.ItemStatus
import app.granger.sources.asDownloadVolumes
import java.io.File
import java.time.LocalDate
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers

/**
 * Android's archive.org model: the shared [ArchiveModel] (Plan I5 Task 2) with the app's collaborators, as it was built
 * before ([sidecarDir] a `File`, [volumes] the app's, [media] its `MediaDownloader`, [today] a `LocalDate`), and the
 * search field's text as Compose state, written synchronously on every keystroke.
 */
class ArchiveViewModel(
    client: ArchiveOrgClient,
    downloads: Downloads,
    bookshelf: Bookshelf,
    prefs: ArchivePrefs,
    volumes: VolumeSource,
    downloadPrefs: DownloadPrefs,
    /** The internal volume's `books/archive-org`, where every sidecar lives (R2). */
    sidecarDir: () -> File,
    appScope: CoroutineScope,
    io: CoroutineDispatcher = Dispatchers.IO,
    kind: ArchiveKind = ArchiveKind.TEXTS,
    /** Required for an audio [kind]. */
    media: MediaDownloader? = null,
    today: () -> LocalDate = LocalDate::now,
    now: () -> Long = System::currentTimeMillis,
    /** The library's languages, main first, read at each search (Plan L3): they add archive.org's `language:` clause. */
    languages: () -> List<String> = { listOf("en") },
) : ArchiveModel(
    client, downloads, bookshelf, prefs, volumes.asDownloadVolumes(), downloadPrefs, { sidecarDir().path }, appScope, io, kind,
    media?.let(::MediaArchiveAudio), { today().toCivilDate() }, now, languages,
) {
    /**
     * The search field's text, as Compose state written synchronously on every keystroke. The field must not read its
     * value from a flow: a value that lags a dispatch behind the keyboard is pushed back into the field and scrambles
     * fast typing (seen on the Pixel 6, 2026-10-17).
     */
    override var searchText by mutableStateOf("")
        protected set
}

/** The tab's title as an Android resource. */
@get:StringRes
val ArchiveTab.title: Int get() = ScreenTextIds.string(titleKey)

/** archive.org's audio views on the app's media downloads (Plan I5: the shared model's [ArchiveAudio]). */
class MediaArchiveAudio(private val media: MediaDownloader) : ArchiveAudio {
    override fun groupOf(identifier: String) = MediaDownloads.groupId(MediaManifest.ARCHIVE_ORG, identifier)

    override fun identifierOf(groupId: String) = MediaDownloads.refOf(groupId, MediaManifest.ARCHIVE_ORG)

    override suspend fun get(
        item: ArchiveItem, offer: AudioOffer.Tracks, kind: ArchiveKind, client: ArchiveOrgClient, now: Long, fileCount: (Int) -> Unit,
    ): UiText? {
        val manifest = MediaDownloads.albumManifest(item, offer, kind, client, now)
        fileCount(manifest.files.size)
        return media.get(manifest)
    }

    override suspend fun cancel(groupId: String) = media.cancel(groupId)

    override suspend fun retry(groupId: String) {
        media.retry(groupId)
    }

    override suspend fun remove(groupId: String) = media.remove(groupId)

    override suspend fun mediaRootOf(groupId: String) = media.mediaRootOf(groupId)

    override fun isCover(record: DownloadRecord) = MediaDownloads.isCover(record)

    override fun statusOf(groupId: String, fileCount: Int, records: List<DownloadRecord>): ItemStatus =
        MediaDownloads.statusOf(groupId, fileCount, records)

    override fun manifestFileCount(filePath: String): Int? = File(filePath).parentFile?.let(MediaManifests::read)?.files?.size
}
