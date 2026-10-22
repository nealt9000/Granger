package app.granger.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.provider.DocumentsContract
import app.granger.archive.ArchivePrefs
import app.granger.core.library.MediaFolder
import app.granger.core.library.MediaLibrary
import app.granger.core.library.PackStatus
import app.granger.core.library.PlaybackDao
import app.granger.core.player.PlayerSettingsKeys
import app.granger.core.player.ReplayGainMode
import app.granger.media.MediaPermissionStatus
import app.granger.media.MediaSharing
import app.granger.core.downloads.Downloads
import app.granger.core.downloads.Volume
import app.granger.core.downloads.VolumeSource
import app.granger.sources.DownloadPrefs
import app.granger.sources.VolumeRow
import app.granger.sources.volumeRows
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class SettingsUi(
    val volumes: List<VolumeRow> = emptyList(),
    val selectedVolume: String? = null,
    val wifiOnly: Boolean = true,
    /** The archive.org waiver is accepted: searches there show everything, not only public domain. */
    val archiveEverything: Boolean = false,
)

/**
 * Where downloads go and whether they may use mobile data (the choices the source screens used to carry), and the
 * rail's side, which the shell reads from [AppearancePrefs] and applies at once.
 */
class SettingsViewModel(
    private val downloads: Downloads,
    private val volumes: VolumeSource,
    private val prefs: DownloadPrefs,
    private val archivePrefs: ArchivePrefs,
    private val appearancePrefs: AppearancePrefs,
    private val appScope: CoroutineScope,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    /** Builds the "Music & audiobooks" section's model on this view model's scope; null hides the section. */
    mediaSettings: ((CoroutineScope) -> MediaSettings)? = null,
) : ViewModel() {
    val media: MediaSettings? = mediaSettings?.invoke(viewModelScope)

    private val volumeList = MutableStateFlow<List<Volume>>(emptyList())
    private val selectedPath = MutableStateFlow(prefs.volumePath)
    private val wifiOnly = MutableStateFlow(prefs.wifiOnly)

    val ui: StateFlow<SettingsUi> =
        combine(volumeList, downloads.records, selectedPath, wifiOnly, archivePrefs.waiverAccepted) { list, records, path, wifi, archive ->
            val rows = volumeRows(list, records)
            SettingsUi(rows, rows.firstOrNull { it.path == path }?.path ?: rows.firstOrNull()?.path, wifi, archive)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsUi())

    val railSide: StateFlow<RailSide> = appearancePrefs.railSide
    val openTo: StateFlow<OpenTo> = appearancePrefs.openTo

    init {
        refreshVolumes()
    }

    /** Free space changes outside the app; called on resume. */
    fun refreshVolumes() {
        viewModelScope.launch { volumeList.value = withContext(io) { volumes.list() } }
    }

    fun setWifiOnly(value: Boolean) {
        wifiOnly.value = value
        prefs.wifiOnly = value
        appScope.launch { downloads.setWifiOnly(value) }
    }

    fun selectVolume(path: String) {
        selectedPath.value = path
        prefs.volumePath = path
    }

    fun acceptArchiveWaiver() {
        archivePrefs.accept()
    }

    fun withdrawArchiveWaiver() {
        archivePrefs.withdraw()
    }

    fun setRailSide(side: RailSide) {
        appearancePrefs.setRailSide(side)
    }

    fun setOpenTo(openTo: OpenTo) {
        appearancePrefs.setOpenTo(openTo)
    }
}

/** The folders the user picked for the scanner (`media_folders`). */
interface MediaFolders {
    val all: Flow<List<MediaFolder>>
    suspend fun add(folder: MediaFolder)
    suspend fun remove(uri: String)
}

class DaoMediaFolders(private val dao: PlaybackDao) : MediaFolders {
    override val all: Flow<List<MediaFolder>> = dao.observeFolders()
    override suspend fun add(folder: MediaFolder) = dao.insertFolder(folder)
    override suspend fun remove(uri: String) = dao.deleteFolder(uri)
}

/** Persisted SAF grants on picked folders: read only, never write. */
interface FolderGrants {
    fun take(uri: String)
    fun release(uri: String)
}

class ResolverFolderGrants(private val resolver: ContentResolver) : FolderGrants {
    override fun take(uri: String) = resolver.takePersistableUriPermission(Uri.parse(uri), Intent.FLAG_GRANT_READ_URI_PERMISSION)

    /** A grant already gone (revoked, or the provider uninstalled) is nothing to release. */
    override fun release(uri: String) {
        try {
            resolver.releasePersistableUriPermission(Uri.parse(uri), Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (e: SecurityException) {
            android.util.Log.w("Granger", "No grant to release on $uri", e)
        }
    }
}

/** What the phone's library holds after a scan: playable songs and audiobooks. */
data class ScanSummary(val songs: Int, val audiobooks: Int) {
    companion object {
        suspend fun of(media: MediaLibrary) = ScanSummary(
            songs = media.tracks.first().count { it.status == PackStatus.OK },
            audiobooks = media.audiobooks.first().count { it.status == PackStatus.OK },
        )
    }
}

sealed interface RescanState {
    data object Idle : RescanState
    data object Running : RescanState
    data class Done(val summary: ScanSummary) : RescanState
    data object Failed : RescanState
}

/** A picked folder; [name] is its path within its storage volume ("Music/Rock"). */
data class FolderRow(val uri: String, val name: String, val audiobooks: Boolean)

data class MediaSettingsUi(
    val permission: MediaPermissionStatus = MediaPermissionStatus.DENIED,
    val folders: List<FolderRow> = emptyList(),
    val scan: RescanState = RescanState.Idle,
    val replayGain: ReplayGainMode = ReplayGainMode.AUTO,
    val skipBackS: Int = MediaSettings.DEFAULT_SKIP_S,
    val skipForwardS: Int = MediaSettings.DEFAULT_SKIP_S,
    val headsetAutoplay: Boolean = false,
    /** Downloaded media show read-only in the Files app and other apps' pickers (the documents provider is enabled). */
    val showInOtherApps: Boolean = false,
)

/** The "Show in other apps" switch: whether the documents provider is enabled. */
interface OtherAppsSharing {
    fun isEnabled(): Boolean
    fun setEnabled(on: Boolean)
}

/** [MediaSharing] for [context]: the provider component's enabled state is the setting itself. */
class ComponentOtherAppsSharing(private val context: Context) : OtherAppsSharing {
    override fun isEnabled() = MediaSharing.isEnabled(context)
    override fun setEnabled(on: Boolean) = MediaSharing.setEnabled(context, on)
}

/**
 * Settings → Music & audiobooks: the audio permission's status (the screen asks; this only shows it), the picked
 * folders, "Rescan now", and the player's settings in [prefs] ([PlayerSettingsKeys], read by the playback service).
 * [scope] holds the UI state; [work] runs folder changes and scans, which should finish after the screen is gone.
 */
class MediaSettings(
    private val folders: MediaFolders,
    private val grants: FolderGrants,
    private val prefs: SharedPreferences,
    private val sharing: OtherAppsSharing,
    private val scanner: suspend () -> ScanSummary,
    scope: CoroutineScope,
    private val work: CoroutineScope = scope,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private data class Player(val replayGain: ReplayGainMode, val skipBackS: Int, val skipForwardS: Int, val headsetAutoplay: Boolean)

    private val permission = MutableStateFlow(MediaPermissionStatus.DENIED)
    private val scan = MutableStateFlow<RescanState>(RescanState.Idle)
    private val player = MutableStateFlow(readPlayer())
    private val shared = MutableStateFlow(sharing.isEnabled())

    val ui: StateFlow<MediaSettingsUi> = combine(folders.all, permission, scan, player, shared) { list, status, scanState, p, on ->
        MediaSettingsUi(
            permission = status,
            folders = list.map { FolderRow(it.uri, folderName(it.uri), it.audiobooks) },
            scan = scanState,
            replayGain = p.replayGain,
            skipBackS = p.skipBackS,
            skipForwardS = p.skipForwardS,
            headsetAutoplay = p.headsetAutoplay,
            showInOtherApps = on,
        )
    }.stateIn(
        scope, SharingStarted.WhileSubscribed(5_000),
        MediaSettingsUi(replayGain = player.value.replayGain, showInOtherApps = shared.value),
    )

    private fun readPlayer() = Player(
        replayGain = runCatching { ReplayGainMode.valueOf(prefs.getString(PlayerSettingsKeys.REPLAYGAIN_MODE, null) ?: "AUTO") }
            .getOrDefault(ReplayGainMode.AUTO),
        skipBackS = prefs.getInt(PlayerSettingsKeys.SKIP_BACK_S, DEFAULT_SKIP_S),
        skipForwardS = prefs.getInt(PlayerSettingsKeys.SKIP_FORWARD_S, DEFAULT_SKIP_S),
        headsetAutoplay = prefs.getBoolean(PlayerSettingsKeys.HEADSET_AUTOPLAY, false),
    )

    /** The screen checked the permission (on resume, or after the prompt). */
    fun setPermission(status: MediaPermissionStatus) { permission.value = status }

    /** A folder from the system picker: a persisted read grant, then its row, then a scan that reads it. */
    fun addFolder(uri: String, audiobooks: Boolean) {
        work.launch {
            try {
                grants.take(uri)
            } catch (e: SecurityException) {
                android.util.Log.w("Granger", "No persistable grant on $uri", e)
                scan.value = RescanState.Failed
                return@launch
            }
            folders.add(MediaFolder(uri, audiobooks, now()))
            runRescan()
        }
    }

    /** Off the list, its grant given back, and a scan (its items go MISSING, their state kept). */
    fun removeFolder(uri: String) {
        work.launch {
            folders.remove(uri)
            grants.release(uri)
            runRescan()
        }
    }

    fun rescan() {
        work.launch { runRescan() }
    }

    private suspend fun runRescan() {
        scan.value = RescanState.Running
        scan.value = try {
            RescanState.Done(scanner())
        } catch (e: CancellationException) {
            scan.value = RescanState.Idle
            throw e
        } catch (e: Throwable) {
            // Errors too (out of memory, a native library that won't link): the button must never stay on Running.
            android.util.Log.e("Granger", "Rescan failed", e)
            RescanState.Failed
        }
    }

    fun setReplayGain(mode: ReplayGainMode) {
        prefs.edit().putString(PlayerSettingsKeys.REPLAYGAIN_MODE, mode.name).apply()
        player.value = player.value.copy(replayGain = mode)
    }

    fun setSkipBack(seconds: Int) {
        prefs.edit().putInt(PlayerSettingsKeys.SKIP_BACK_S, seconds).apply()
        player.value = player.value.copy(skipBackS = seconds)
    }

    fun setSkipForward(seconds: Int) {
        prefs.edit().putInt(PlayerSettingsKeys.SKIP_FORWARD_S, seconds).apply()
        player.value = player.value.copy(skipForwardS = seconds)
    }

    fun setHeadsetAutoplay(on: Boolean) {
        prefs.edit().putBoolean(PlayerSettingsKeys.HEADSET_AUTOPLAY, on).apply()
        player.value = player.value.copy(headsetAutoplay = on)
    }

    /** Enables or disables the documents provider; off, no other app can see or query it. */
    fun setShowInOtherApps(on: Boolean) {
        sharing.setEnabled(on)
        shared.value = sharing.isEnabled()
    }

    companion object {
        const val DEFAULT_SKIP_S = 30
        val SKIP_CHOICES = listOf(10, 15, 30, 60)

        /** "primary:Music/Rock" → "Music/Rock"; a volume's root is named by its volume. */
        fun folderName(uri: String): String {
            val id = try {
                DocumentsContract.getTreeDocumentId(Uri.parse(uri))
            } catch (e: IllegalArgumentException) {
                return Uri.parse(uri).lastPathSegment ?: uri
            }
            val path = id.substringAfter(':', id)
            return path.ifBlank { if (id.startsWith("primary")) "Internal storage" else id.substringBefore(':') }
        }
    }
}
