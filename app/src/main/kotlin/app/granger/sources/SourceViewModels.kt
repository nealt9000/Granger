package app.granger.sources

import androidx.annotation.StringRes
import app.granger.DENSE_RETRIEVAL
import app.granger.ScreenTextIds
import app.granger.core.ai.DeviceTier
import app.granger.core.catalog.CatalogSource
import app.granger.core.catalog.ModelRole
import app.granger.core.downloads.DownloadRecord
import app.granger.core.downloads.DownloadVolume
import app.granger.core.downloads.DownloadVolumes
import app.granger.core.downloads.Downloads
import app.granger.core.downloads.Volume
import app.granger.core.downloads.VolumeSource
import app.granger.core.library.Bookshelf
import app.granger.core.library.Library
import app.granger.core.speech.SherpaSpeech
import app.granger.core.speech.VoiceTitles
import app.granger.library.countsDocuments
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

// Android's side of the shared Get more model (core:screens, Plan I4b Task 6): the factory every screen and test calls,
// with the app's own collaborators, and the resource ids the shared enums name by key.

/** Get more's model on Android: [bookshelf], [volumes] and [tier] become the shared model's parameters. */
fun SourceViewModel(
    scope: CatalogScope,
    catalog: CatalogSource,
    downloads: Downloads,
    library: Library,
    bookshelf: Bookshelf,
    volumes: VolumeSource,
    prefs: DownloadPrefs,
    tier: DeviceTier,
    appScope: CoroutineScope,
    io: CoroutineDispatcher = Dispatchers.IO,
    /** Ask runs here (the app's `askOn`): off, rows say nothing of how Ask would search, and Ask's model isn't offered. */
    askOn: Boolean = tier >= DeviceTier.ASK,
    /** Search by meaning runs here (the app's `meaningSearch`, Plan SQ Phase 6): the embedder's line says Search uses it. */
    meaningSearch: Boolean = DENSE_RETRIEVAL && tier.canRun(ModelRole.EMBEDDER),
    domain: String? = null,
    family: String? = null,
    knowledge: Flow<List<String>> = flowOf(listOf(app.granger.core.catalog.ENGLISH)),
    /** How the app names Granger voices ("Voz de Granger (Owl)"); null keeps the catalog's English title. */
    voiceTitles: VoiceTitles? = null,
    bookSet: String? = null,
): SourceViewModel = SourceViewModel(
    scope = scope,
    catalog = catalog,
    downloads = downloads,
    library = library,
    volumes = volumes.asDownloadVolumes(),
    prefs = prefs,
    appScope = appScope,
    removeBook = { id -> bookshelf.remove(id) },
    canRun = tier::canRun,
    askOn = askOn,
    meaningSearch = meaningSearch,
    countsDocuments = ::countsDocuments,
    io = io,
    domain = domain,
    family = family,
    knowledge = knowledge,
    voiceTitle = voiceTitles?.let { titles -> { id: String -> SherpaSpeech.title(id, titles) } },
    bookSet = bookSet,
)

/** A volume as the shared screens see it: its root as a path. */
fun Volume.toDownloadVolume(): DownloadVolume = DownloadVolume(root.path, label, removable, freeBytes, totalBytes, grangerBytes)

/** The volumes, listed afresh on each call, as the shared screens see them. */
fun VolumeSource.asDownloadVolumes(): DownloadVolumes = DownloadVolumes { list().map { it.toDownloadVolume() } }

/** One row per volume, with what is still to download onto it. */
@JvmName("volumeRowsOfVolumes")
fun volumeRows(volumes: List<Volume>, records: List<DownloadRecord>): List<VolumeRow> =
    volumeRows(volumes.map { it.toDownloadVolume() }, records)

/** The Remove dialog's words as an Android resource. */
@get:StringRes
val RemoveKind.body: Int get() = ScreenTextIds.string(bodyKey)

/** The tab's title as an Android resource. */
@get:StringRes
val SourceTab.title: Int get() = ScreenTextIds.string(titleKey)

/** A Books shelf's heading as an Android resource. */
@StringRes
fun BookShelves.heading(shelf: String): Int = ScreenTextIds.string(headingKey(shelf))
