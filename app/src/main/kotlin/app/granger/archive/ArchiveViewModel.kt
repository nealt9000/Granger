package app.granger.archive

import androidx.annotation.StringRes
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.granger.ScreenTextIds
import app.granger.core.archive.ArchiveKind
import app.granger.core.archive.ArchiveOrgClient
import app.granger.core.downloads.Downloads
import app.granger.core.downloads.VolumeSource
import app.granger.core.library.Bookshelf
import app.granger.home.toCivilDate
import app.granger.media.MediaDownloader
import app.granger.sources.DownloadPrefs
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
