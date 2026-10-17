package app.granger.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.granger.core.downloads.Downloads
import app.granger.core.downloads.Volume
import app.granger.core.downloads.VolumeSource
import app.granger.sources.DownloadPrefs
import app.granger.sources.VolumeRow
import app.granger.sources.volumeRows
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class SettingsUi(
    val volumes: List<VolumeRow> = emptyList(),
    val selectedVolume: String? = null,
    val wifiOnly: Boolean = true,
)

/** Where downloads go and whether they may use mobile data; the choices the source screens used to carry. */
class SettingsViewModel(
    private val downloads: Downloads,
    private val volumes: VolumeSource,
    private val prefs: DownloadPrefs,
    private val appScope: CoroutineScope,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {
    private val volumeList = MutableStateFlow<List<Volume>>(emptyList())
    private val selectedPath = MutableStateFlow(prefs.volumePath)
    private val wifiOnly = MutableStateFlow(prefs.wifiOnly)

    val ui: StateFlow<SettingsUi> =
        combine(volumeList, downloads.records, selectedPath, wifiOnly) { list, records, path, wifi ->
            val rows = volumeRows(list, records)
            SettingsUi(rows, rows.firstOrNull { it.path == path }?.path ?: rows.firstOrNull()?.path, wifi)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsUi())

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
}
