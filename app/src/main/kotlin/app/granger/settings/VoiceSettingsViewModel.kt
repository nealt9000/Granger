package app.granger.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.granger.core.catalog.CatalogSource
import app.granger.core.catalog.VoiceEntry
import app.granger.core.downloads.DownloadState
import app.granger.core.downloads.Downloads
import app.granger.core.downloads.EnqueueResult
import app.granger.core.downloads.VolumeSource
import app.granger.core.speech.InstalledVoices
import app.granger.core.speech.Voice
import app.granger.core.speech.VoiceId
import app.granger.sources.DownloadPrefs
import java.io.File
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** A catalog voice as the screen lists it: installed, downloading (with progress), or available to get. */
data class CatalogVoiceRow(
    val entry: VoiceEntry,
    val installed: Boolean,
    val progress: Float?,          // 0..1 while downloading, null otherwise
    val queued: Boolean,
    val error: String?,
)

data class VoiceSettingsUi(
    /** What can speak now: the Granger voices first, then the system engine's offline English voices. */
    val voices: List<Voice> = emptyList(),
    /** The chosen voice, or null for the first installed. */
    val chosen: VoiceId? = null,
    val speed: Float = 1f,
    val markSentence: Boolean = true,
    val catalog: List<CatalogVoiceRow> = emptyList(),
    val notice: String? = null,
    /** Research reads a new answer aloud as it is written (Plan V2). */
    val speakAnswers: Boolean = false,
)

/**
 * Settings → Voice: pick the voice, get or remove the catalog's voices, the speed default and the sentence mark.
 * Downloads go through the one ledger; the unpack happens when the zip installs (the app's install hook), after which
 * [InstalledVoices] is refreshed and the new voice appears in the list.
 */
class VoiceSettingsViewModel(
    private val prefs: VoicePrefs,
    private val installed: InstalledVoices,
    private val catalog: CatalogSource,
    private val downloads: Downloads,
    private val volumes: VolumeSource,
    private val downloadPrefs: DownloadPrefs,
    private val appScope: CoroutineScope,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {
    private val notice = MutableStateFlow<String?>(null)

    val ui: StateFlow<VoiceSettingsUi> = combine(
        installed.voices, prefs.voiceId, prefs.speed, combine(prefs.markSentence, prefs.speakAnswers) { mark, speak -> mark to speak },
        combine(catalog.state.map { it.catalog?.voices.orEmpty() }, downloads.records, notice) { entries, records, n -> Triple(entries, records, n) },
    ) { voices, chosen, speed, (mark, speak), (entries, records, n) ->
        val rows = entries.map { entry ->
            val versions = records.filter { it.itemId == entry.id }
            val active = versions.firstOrNull { it.state != DownloadState.INSTALLED }
            CatalogVoiceRow(
                entry = entry,
                installed = versions.any { it.hash == entry.sha256 && it.state == DownloadState.INSTALLED },
                progress = active?.takeIf { it.state == DownloadState.RUNNING }?.let { if (it.sizeBytes > 0) it.bytesDone.toFloat() / it.sizeBytes else 0f },
                queued = active?.state == DownloadState.QUEUED,
                error = active?.takeIf { it.state == DownloadState.FAILED }?.error,
            )
        }
        VoiceSettingsUi(voices, chosen, speed, mark, rows, n, speakAnswers = speak)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), VoiceSettingsUi())

    init {
        viewModelScope.launch { installed.refresh() }
    }

    fun choose(id: VoiceId?) = prefs.setVoice(id)
    fun setSpeed(speed: Float) = prefs.setSpeed(speed)
    fun setMarkSentence(on: Boolean) = prefs.setMarkSentence(on)
    fun setSpeakAnswers(on: Boolean) = prefs.setSpeakAnswers(on)
    fun dismissNotice() { notice.value = null }

    fun get(entry: VoiceEntry) {
        appScope.launch {
            val root = volumeFor()
            if (root == null) { notice.value = "No storage to download to."; return@launch }
            notice.value = when (downloads.enqueue(entry, root, downloadPrefs.wifiOnly)) {
                EnqueueResult.Queued, EnqueueResult.AlreadyActive, EnqueueResult.AlreadyInstalled -> null
                is EnqueueResult.NoSpace -> "Not enough free space for ${entry.title}."
                is EnqueueResult.OverCap -> "Over the storage cap."
            }
        }
    }

    fun cancel(entry: VoiceEntry) { appScope.launch { downloads.cancel(entry.id) } }
    fun retry(entry: VoiceEntry) { appScope.launch { downloads.retry(entry.id) } }

    /** Deletes the ledger row and the unpacked directory; the chosen voice falls back to whatever is left. */
    fun remove(entry: VoiceEntry) {
        appScope.launch {
            downloads.remove(entry.id)
            withContext(io) {
                for (dir in installedDirs()) File(dir, entry.id).deleteRecursively()
            }
            if (prefs.voiceId.value == VoiceId.Granger(entry.id)) prefs.setVoice(null)
            installed.refresh()
        }
    }

    private suspend fun volumeFor(): File? = withContext(io) {
        val list = volumes.list()
        list.firstOrNull { it.root.path == downloadPrefs.volumePath }?.root ?: list.firstOrNull()?.root
    }

    private suspend fun installedDirs(): List<File> = withContext(io) { volumes.list().map { it.voicesDir } }
}
