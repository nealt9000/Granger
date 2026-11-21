package app.granger.onboarding

import app.granger.core.ai.DeviceTier
import app.granger.core.catalog.Catalog
import app.granger.core.catalog.CatalogItem
import app.granger.core.catalog.CatalogSource
import app.granger.core.catalog.ENGLISH
import app.granger.core.downloads.Downloads
import app.granger.core.downloads.EnqueueResult
import app.granger.core.downloads.Volume
import app.granger.core.downloads.VolumeSource
import app.granger.core.downloads.downloadSpecOf
import app.granger.core.downloads.enqueueGroup
import app.granger.core.library.Library
import app.granger.sources.DownloadPrefs
import app.granger.sources.asDownloadVolumes
import app.granger.sources.canRun
import app.granger.sources.toDownloadVolume
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import java.io.File

// Android's side of the shared onboarding model (core:screens, Plan I4b Task 8): the factory GrangerNavHost and the
// tests call, with the app's own collaborators (the device tier, VolumeSource, Features, the phone's Locales).

/** Onboarding's model on Android: [volumes], [tier] and the app's feature flags become the shared model's parameters. */
fun OnboardingViewModel(
    catalog: CatalogSource,
    library: Library,
    downloads: Downloads,
    volumes: VolumeSource,
    prefs: DownloadPrefs,
    tier: DeviceTier,
    totalRamBytes: Long,
    onboardingPrefs: OnboardingPrefs,
    /** Ask runs here (the app's `askOn`): off, onboarding words the phone as a library and offers no Ask line. */
    askOn: Boolean = tier >= DeviceTier.ASK,
    dense: Boolean = app.granger.DENSE_RETRIEVAL,
    countryCode: String = java.util.Locale.getDefault().country,
    offerVoice: Boolean = app.granger.Features.READ_ALOUD && app.granger.Features.PLAYER,
    io: CoroutineDispatcher = Dispatchers.IO,
    now: () -> Long = System::currentTimeMillis,
    /** The library's languages (Plan L3), main first, and how Welcome's offer changes them. */
    knowledge: Flow<List<String>> = flowOf(listOf(ENGLISH)),
    setKnowledge: (List<String>) -> Unit = {},
    /** The phone's languages in order, whatever the app is set to. */
    phoneLocales: () -> List<java.util.Locale> = { emptyList() },
    /** The app's language, for the catalog's copy (Plan L3). */
    appLanguage: () -> String = { ENGLISH },
    enqueue: suspend (groupId: String, items: List<CatalogItem>, volume: Volume, wifiOnly: Boolean) -> EnqueueResult =
        { group, items, volume, wifi -> downloads.enqueueGroup(group, items.map { downloadSpecOf(it, group) }, volume.root, wifi) },
    /** Wikipedia online is on (Plan O5): Welcome offers Start reading now beside Outfit for offline. Off, it's Get started. */
    chooseOnline: Boolean = false,
    /** The privacy line has been shown (Plan O5 builder's call 2). */
    onPrivacySeen: () -> Unit = {},
    /** Where the flow opens: Welcome on first run, the Outfit page from Settings (Plan OB call 4). */
    startAt: Step = Step.WELCOME,
): OnboardingViewModel = OnboardingViewModel(
    catalog = catalog,
    library = library,
    downloads = downloads,
    volumes = volumes.asDownloadVolumes(),
    prefs = prefs,
    askTier = tier >= DeviceTier.ASK,
    canRun = tier::canRun,
    totalRamBytes = totalRamBytes,
    onboardingPrefs = onboardingPrefs,
    askOn = askOn,
    research = app.granger.Features.RESEARCH,
    dense = dense,
    countryCode = countryCode,
    offerVoice = offerVoice,
    io = io,
    now = now,
    knowledge = knowledge,
    setKnowledge = setKnowledge,
    phoneLanguages = { phoneLocales().map { it.language } },
    appLanguage = appLanguage,
    enqueue = { group, items, volume, wifi ->
        // The volume the shared model picked, as Android's own (a File root) for the app's enqueue.
        enqueue(group, items, Volume(File(volume.root), volume.label, volume.removable, volume.freeBytes, volume.totalBytes, volume.grangerBytes), wifi)
    },
    chooseOnline = chooseOnline,
    onPrivacySeen = onPrivacySeen,
    startAt = startAt,
)

/** Ask's models as one line, from Android's device tier. */
fun OnboardingModel.askLine(catalog: Catalog, tier: DeviceTier, dense: Boolean, installed: Set<String>): Line? =
    askLine(catalog, tier >= DeviceTier.ASK, dense, installed)

/** Where the start goes when nothing was chosen, among Android's volumes. */
fun OnboardingModel.defaultVolume(volumes: List<Volume>): Volume? {
    val chosen = defaultVolume(volumes.map { it.toDownloadVolume() }) ?: return null
    return volumes.first { it.root.path == chosen.root }
}
