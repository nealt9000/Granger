package app.granger.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.granger.UiText
import app.granger.core.catalog.CatalogSource
import app.granger.core.catalog.VoiceEntry
import app.granger.core.downloads.DownloadState
import app.granger.core.downloads.Downloads
import app.granger.core.downloads.EnqueueResult
import app.granger.core.speech.InstalledVoices
import app.granger.core.speech.Voice
import app.granger.core.speech.VoiceId
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem

/** Settings → Voice (Plan V1), shared since Plan I5 Task 8: Android keeps it in SharedPreferences, the iPhone in UserDefaults. */
interface VoicePrefs {
    /** The voice to read with; null means the first installed (a Granger voice before the system's). */
    val voiceId: StateFlow<VoiceId?>
    /** 0.7–2.0; 1.0 by default. */
    val speed: StateFlow<Float>
    /** Mark the sentence being read on the page; on by default. */
    val markSentence: StateFlow<Boolean>
    /** Read a Research answer aloud as it arrives (Plan V2); off by default (spec decision 2). */
    val speakAnswers: StateFlow<Boolean>
    fun setVoice(id: VoiceId?)
    fun setSpeed(speed: Float)
    fun setMarkSentence(on: Boolean)
    fun setSpeakAnswers(on: Boolean)

    companion object {
        const val MIN_SPEED = 0.7f
        const val MAX_SPEED = 2.0f
    }
}

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
    val notice: UiText? = null,
    /** Research reads a new answer aloud as it is written (Plan V2). */
    val speakAnswers: Boolean = false,
    /** Under 6 GB the Granger voices may stop partway (Plan E1 item 2): listed with a warning, not hidden. */
    val mayStop: Boolean = false,
) {
    /**
     * The voices to list first and the ones behind "System voices": every Granger voice, plus the voice in use when it's
     * a system one ([chosen] if it's installed, else the first voice, which is what reads then); every other system
     * voice goes second.
     */
    fun split(): Pair<List<Voice>, List<Voice>> = splitVoices(voices, chosen)
}

/** [VoiceSettingsUi.split]'s rule. */
fun splitVoices(voices: List<Voice>, chosen: VoiceId?): Pair<List<Voice>, List<Voice>> {
    val inUse = voices.firstOrNull { it.id == chosen } ?: voices.firstOrNull()
    val shown = voices.filter { it.id is VoiceId.Granger || it == inUse }
    return shown to voices.filterNot { it in shown }
}

/**
 * Settings → Voice: pick the voice, get or remove the catalog's voices, the speed default and the sentence mark.
 * Downloads go through the one ledger; the unpack happens when the zip installs (each phone's install hook), after which
 * [InstalledVoices] is refreshed and the new voice appears in the list. Shared since Plan I5 Task 8: [voiceRoot] is the
 * volume a voice downloads to (null with none), [voicesDirs] every volume's `voices/` folder.
 */
open class VoiceSettingsModel(
    private val prefs: VoicePrefs,
    private val installed: InstalledVoices,
    private val catalog: CatalogSource,
    private val downloads: Downloads,
    private val voiceRoot: suspend () -> String?,
    private val voicesDirs: suspend () -> List<String>,
    private val wifiOnly: () -> Boolean,
    private val appScope: CoroutineScope,
    private val io: CoroutineDispatcher = app.granger.screens.ioDispatcher,
    private val lowMemory: Boolean = false,
) : ViewModel() {
    private val notice = MutableStateFlow<UiText?>(null)

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
        VoiceSettingsUi(voices, chosen, speed, mark, rows, n, speakAnswers = speak, mayStop = lowMemory)
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
            val root = withContext(io) { voiceRoot() }
            if (root == null) { notice.value = UiText.Res("settings_voice_no_storage"); return@launch }
            notice.value = when (downloads.enqueue(entry, root, wifiOnly())) {
                EnqueueResult.Queued, EnqueueResult.AlreadyActive, EnqueueResult.AlreadyInstalled -> null
                is EnqueueResult.NoSpace -> UiText.Res("settings_voice_no_space", entry.title)
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
                for (dir in voicesDirs()) deleteTree(Path(dir, entry.id))
            }
            if ((prefs.voiceId.value as? VoiceId.Granger)?.dir == entry.id) prefs.setVoice(null)
            installed.refresh()
        }
    }

    private fun deleteTree(path: Path) {
        runCatching {
            val meta = SystemFileSystem.metadataOrNull(path) ?: return
            if (meta.isDirectory) SystemFileSystem.list(path).forEach(::deleteTree)
            SystemFileSystem.delete(path, mustExist = false)
        }
    }
}
