package app.granger.settings

import app.granger.core.catalog.CatalogSource
import app.granger.core.downloads.Downloads
import app.granger.core.downloads.VolumeSource
import app.granger.core.speech.InstalledVoices
import app.granger.sources.DownloadPrefs
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers

// Settings → Voice is shared since Plan I5 Task 8 (core:screens VoiceSettingsModel, with CatalogVoiceRow and
// VoiceSettingsUi); Android's keeps its constructor over its volumes.

/** Settings → Voice on Android: the shared [VoiceSettingsModel] over the phone's volumes (the chosen one, else the first). */
class VoiceSettingsViewModel(
    prefs: VoicePrefs,
    installed: InstalledVoices,
    catalog: CatalogSource,
    downloads: Downloads,
    volumes: VolumeSource,
    downloadPrefs: DownloadPrefs,
    appScope: CoroutineScope,
    io: CoroutineDispatcher = Dispatchers.IO,
    lowMemory: Boolean = false,
) : VoiceSettingsModel(
    prefs, installed, catalog, downloads,
    voiceRoot = {
        val list = volumes.list()
        (list.firstOrNull { it.root.path == downloadPrefs.volumePath }?.root ?: list.firstOrNull()?.root)?.path
    },
    voicesDirs = { volumes.list().map { it.voicesDir.path } },
    wifiOnly = { downloadPrefs.wifiOnly },
    appScope = appScope, io = io, lowMemory = lowMemory,
)
