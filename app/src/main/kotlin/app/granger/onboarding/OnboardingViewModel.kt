package app.granger.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.granger.core.ai.DeviceTier
import app.granger.core.catalog.Catalog
import app.granger.core.catalog.CatalogSource
import app.granger.core.downloads.DownloadRecord
import app.granger.core.downloads.Downloads
import app.granger.core.downloads.EnqueueResult
import app.granger.core.downloads.Volume
import app.granger.core.downloads.VolumeSource
import app.granger.core.downloads.downloadSpecOf
import app.granger.core.library.Library
import app.granger.core.library.Pack
import app.granger.formatBytes
import app.granger.sources.DownloadPrefs
import app.granger.sources.ItemStatus
import app.granger.sources.statusOf
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The five screens (spec §6). */
enum class Step { WELCOME, PHONE, START, SITUATION, REVIEW }

/** This phone (step 2). */
data class PhoneUi(
    val tierLine: String,
    /** Why Research is or isn't available; null when the tier can run it. */
    val researchLine: String?,
    val volumes: List<Volume>,
    val efficientBytes: Long,
    val comprehensiveBytes: Long,
)

data class OnboardingUi(
    val step: Step = Step.WELCOME,
    /** No cached catalog yet: Pick a start can only offer a refresh. */
    val noCatalog: Boolean = true,
    val refreshing: Boolean = false,
    val phone: PhoneUi = PhoneUi("", null, emptyList(), 0, 0),
    val starts: List<StartChoice> = emptyList(),
    /** [OnboardingModel.EFFICIENT], [OnboardingModel.COMPREHENSIVE], or null for "I'll choose later". */
    val startId: String? = OnboardingModel.EFFICIENT,
    val unticked: Set<String> = emptySet(),
    val regions: Regions = Regions(emptyList(), emptyList()),
    val regionQuery: String = "",
    val region: Region? = null,
    val additions: List<Addition> = emptyList(),
    val useCaseIds: Set<String> = emptySet(),
    val review: Review = Review(emptyList(), 0),
    val wifiOnly: Boolean = true,
    val volumePath: String? = null,
    val notice: String? = null,
) {
    val start: StartChoice? get() = starts.firstOrNull { it.id == startId }
    val chosenVolume: Volume? get() = phone.volumes.firstOrNull { it.root.path == volumePath } ?: phone.volumes.firstOrNull()
}

/**
 * The flow's state: a step and the choices, kept here so Back and rotation keep them and the review sees them all
 * (Decision 2). The installed set comes from the download ledger and the library, through the Kiwix screen's rule.
 */
class OnboardingViewModel(
    private val catalog: CatalogSource,
    library: Library,
    downloads: Downloads,
    private val volumes: VolumeSource,
    private val prefs: DownloadPrefs,
    private val tier: DeviceTier,
    private val totalRamBytes: Long,
    private val onboardingPrefs: OnboardingPrefs,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val now: () -> Long = System::currentTimeMillis,
    private val enqueue: suspend (groupId: String, items: List<app.granger.core.catalog.CatalogItem>, volume: Volume, wifiOnly: Boolean) -> EnqueueResult =
        { group, items, volume, wifi -> downloads.enqueueGroup(group, items.map { downloadSpecOf(it, group) }, volume.root, wifi) },
) : ViewModel() {
    private val step = MutableStateFlow(Step.WELCOME)
    private val startId = MutableStateFlow<String?>(OnboardingModel.EFFICIENT)
    private val unticked = MutableStateFlow<Set<String>>(emptySet())
    private val regionQuery = MutableStateFlow("")
    private val regionId = MutableStateFlow<String?>(null)
    private val useCaseIds = MutableStateFlow<Set<String>>(emptySet())
    private val wifiOnly = MutableStateFlow(prefs.wifiOnly)
    private val volumePath = MutableStateFlow(prefs.volumePath)
    private val notice = MutableStateFlow<String?>(null)
    private val volumeList = MutableStateFlow<List<Volume>>(emptyList())

    /** Set once Start or Skip has finished; the screen then leaves. */
    val finished: StateFlow<Boolean> get() = done
    private val done = MutableStateFlow(false)

    private class Facts(val catalog: Catalog?, val refreshing: Boolean, val installed: Set<String>, val volumes: List<Volume>)
    private class Choices(val step: Step, val startId: String?, val unticked: Set<String>, val regionQuery: String, val regionId: String?, val useCaseIds: Set<String>)
    private class Setup(val wifiOnly: Boolean, val volumePath: String?, val notice: String?)

    private val facts = combine(catalog.state, downloads.records, library.packs, volumeList) { c, records, packs, vols ->
        Facts(c.catalog, c.refreshing, c.catalog?.let { installedIds(it, records, packs) }.orEmpty(), vols)
    }
    private val choices = combine(step, startId, unticked, regionQuery, regionId, useCaseIds) { values ->
        @Suppress("UNCHECKED_CAST")
        Choices(values[0] as Step, values[1] as String?, values[2] as Set<String>, values[3] as String, values[4] as String?, values[5] as Set<String>)
    }
    private val setup = combine(wifiOnly, volumePath, notice, ::Setup)

    val ui: StateFlow<OnboardingUi> = combine(facts, choices, setup) { f, c, s -> build(f, c, s) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), OnboardingUi())

    init {
        viewModelScope.launch { catalog.load() }
        refreshVolumes()
    }

    /**
     * What the phone holds, by catalog id: the Kiwix screen's rule, plus every flavour of an installed ZIM (a pack with
     * pictures covers the one without, and the other way round), as the readiness model counts them.
     */
    private fun installedIds(catalog: Catalog, records: List<DownloadRecord>, packs: List<Pack>): Set<String> {
        val ids = catalog.items.filter { statusOf(it, records, packs, tier) == ItemStatus.Installed }.mapTo(HashSet()) { it.id }
        val names = packs.filter { it.status == app.granger.core.library.PackStatus.OK }.mapTo(HashSet()) { it.name }
        catalog.packs.filter { it.zimName.isNotBlank() && it.zimName in names }.mapTo(ids) { it.id }
        return ids
    }

    private fun build(f: Facts, c: Choices, s: Setup): OnboardingUi {
        val catalog = f.catalog
        val starts = catalog?.let { OnboardingModel.startChoices(it, f.installed) }.orEmpty()
        val start = starts.firstOrNull { it.id == c.startId }
        val regions = catalog?.let { OnboardingModel.regions(it, f.installed) } ?: Regions(emptyList(), emptyList())
        val region = (regions.countries + regions.larger).firstOrNull { it.pack.id == c.regionId }
        val chosen = start?.chosen(c.unticked).orEmpty().flatMap { it.items }.mapTo(HashSet()) { it.id }
        region?.let { chosen += it.pack.id }
        val additions = catalog?.let { OnboardingModel.additions(it, f.installed, chosen) }.orEmpty()
        val picked = additions.filter { it.useCase.id in c.useCaseIds }
        val volume = f.volumes.firstOrNull { it.root.path == s.volumePath } ?: f.volumes.firstOrNull()
        val review = OnboardingModel.review(start, c.unticked, region, picked, volume?.freeBytes ?: 0L)
        return OnboardingUi(
            step = c.step,
            noCatalog = catalog == null,
            refreshing = f.refreshing,
            phone = PhoneUi(
                tierLine = tierLine(),
                researchLine = researchLine(),
                volumes = f.volumes,
                efficientBytes = starts.firstOrNull { it.id == OnboardingModel.EFFICIENT }?.bytes() ?: 0L,
                comprehensiveBytes = starts.firstOrNull { it.id == OnboardingModel.COMPREHENSIVE }?.bytes() ?: 0L,
            ),
            starts = starts,
            startId = c.startId,
            unticked = c.unticked,
            regions = regions.matching(c.regionQuery),
            regionQuery = c.regionQuery,
            region = region,
            additions = additions,
            useCaseIds = c.useCaseIds,
            review = review,
            wifiOnly = s.wifiOnly,
            volumePath = volume?.root?.path,
            notice = s.notice,
        )
    }

    private fun tierLine(): String {
        val gb = (totalRamBytes / (1024.0 * 1024 * 1024)).let { kotlin.math.round(it).toInt() }
        return when (tier) {
            DeviceTier.LIBRARY_ONLY -> "A library phone, with about $gb GB of memory."
            DeviceTier.ASK -> "A library and research phone, with about $gb GB of memory."
            DeviceTier.THINK_DEEPER -> "A library and research phone that can also think deeper, with about $gb GB of memory."
        }
    }

    private fun researchLine(): String? = when (tier) {
        DeviceTier.LIBRARY_ONLY -> "Research, the AI librarian, needs a phone with 6 GB of memory, so on this one Granger is the library."
        DeviceTier.ASK, DeviceTier.THINK_DEEPER -> "Research, the AI librarian, is coming in a later release; this phone can run it."
    }

    fun refreshVolumes() {
        viewModelScope.launch { volumeList.value = withContext(io) { runCatching { volumes.list() }.getOrDefault(emptyList()) } }
    }

    fun refreshCatalog() {
        viewModelScope.launch { catalog.refresh() }
    }

    fun next() = step.update { Step.entries.getOrNull(it.ordinal + 1) ?: it }

    /** Back on the first step is Skip (Decision 1). */
    fun back() {
        if (step.value == Step.WELCOME) skip() else step.update { Step.entries[it.ordinal - 1] }
    }

    fun goTo(target: Step) { step.value = target }

    fun chooseStart(id: String?) { startId.value = id }

    fun toggleLine(lineId: String) = unticked.update { if (lineId in it) it - lineId else it + lineId }

    fun setRegionQuery(query: String) { regionQuery.value = query }

    fun chooseRegion(packId: String?) { regionId.value = packId }

    fun toggleUseCase(id: String) = useCaseIds.update { if (id in it) it - id else it + id }

    fun setWifiOnly(on: Boolean) {
        prefs.wifiOnly = on
        wifiOnly.value = on
    }

    fun chooseVolume(path: String) {
        prefs.volumePath = path
        volumePath.value = path
    }

    fun clearNotice() { notice.value = null }

    /** One tap, final: the flow doesn't come back on the next launch (Settings brings it back on purpose). */
    fun skip() {
        onboardingPrefs.done = true
        done.value = true
    }

    /** Queues the review as one group and finishes; a refusal stays on the review with its notice. */
    fun start() {
        viewModelScope.launch {
            val review = ui.value.review
            // The setting flows, not the built UI: a tap on the switch right before Start must count.
            val volume = volumeList.value.firstOrNull { it.root.path == volumePath.value } ?: volumeList.value.firstOrNull()
            if (review.isEmpty || volume == null) {
                skip()
                return@launch
            }
            val group = "onboarding:${now()}"
            val problem = when (val result = enqueue(group, review.items, volume, wifiOnly.value)) {
                EnqueueResult.Queued, EnqueueResult.AlreadyActive, EnqueueResult.AlreadyInstalled -> null
                is EnqueueResult.NoSpace -> "Not enough space on ${volume.label}: free up ${formatBytes(result.neededBytes)} or untick something."
                is EnqueueResult.OverCap -> "That would bring your downloads to ${formatBytes(result.wouldUseBytes)}, over the ${formatBytes(result.capBytes)} limit."
            }
            if (problem != null) {
                notice.value = problem
                return@launch
            }
            onboardingPrefs.done = true
            done.value = true
        }
    }

    companion object {
        /** The readiness domain a use case's "See" opens on the Kiwix screen. */
        val DOMAIN_OF = mapOf(
            "offgrid" to "food", "travel" to "maps", "study" to "science", "build" to "making",
            "medical" to "medicine", "software" to "technology", "faith" to "thought",
        )
    }
}
