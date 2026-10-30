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

/** The six screens (spec §6; Region and Uses split in Plan U3a). */
enum class Step { WELCOME, PHONE, START, REGION, USES, REVIEW }

/** This phone (step 2). */
data class PhoneUi(
    val tierLine: String,
    /** Whether this phone can run Ask, and where to get it; null when there's nothing to say. */
    val researchLine: String?,
    val volumes: List<Volume>,
    val efficientBytes: Long,
    val comprehensiveBytes: Long,
)

data class OnboardingUi(
    val step: Step = Step.WELCOME,
    /** No catalog yet: Pick a start shows the fetch (fresh install) or why it failed. */
    val noCatalog: Boolean = true,
    val refreshing: Boolean = false,
    /** The fetch onboarding started failed; Pick a start offers Try again, and Next still works. */
    val catalogFailed: Boolean = false,
    val phone: PhoneUi = PhoneUi("", null, emptyList(), 0, 0),
    val starts: List<StartChoice> = emptyList(),
    /** [OnboardingModel.EFFICIENT], [OnboardingModel.COMPREHENSIVE], or null for "I'll choose later". */
    val startId: String? = OnboardingModel.EFFICIENT,
    val unticked: Set<String> = emptySet(),
    val regions: Regions = Regions(emptyList(), emptyList()),
    val regionQuery: String = "",
    val region: Region? = null,
    /** [region] is the one suggested from the phone's country, not one the user picked. */
    val regionSuggested: Boolean = false,
    /** The phone's country's map, offered first in the list when it was too big to preselect (or was cleared). */
    val suggestion: Region? = null,
    val additions: List<Addition> = emptyList(),
    val useCaseIds: Set<String> = emptySet(),
    /** Ask's models, offered under the start choices; null where Ask can't run or the catalog has none. */
    val askLine: Line? = null,
    val askChosen: Boolean = true,
    /** Encyclopedias without their pictures: the space-saving fallback. */
    val saveSpace: Boolean = false,
    /** A Granger voice for reading aloud, offered beside Ask; null where read-aloud is off or one is installed. */
    val voiceLine: Line? = null,
    val voiceChosen: Boolean = true,
    val review: Review = Review(emptyList(), 0),
    val wifiOnly: Boolean = true,
    val volumePath: String? = null,
    val notice: String? = null,
    /** Start was tapped and the downloads are being written; the button waits. */
    val starting: Boolean = false,
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
    private val downloads: Downloads,
    private val volumes: VolumeSource,
    private val prefs: DownloadPrefs,
    private val tier: DeviceTier,
    private val totalRamBytes: Long,
    private val onboardingPrefs: OnboardingPrefs,
    private val dense: Boolean = app.granger.DENSE_RETRIEVAL,
    private val countryCode: String = java.util.Locale.getDefault().country,
    private val offerVoice: Boolean = app.granger.Features.READ_ALOUD && app.granger.Features.PLAYER,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val now: () -> Long = System::currentTimeMillis,
    private val enqueue: suspend (groupId: String, items: List<app.granger.core.catalog.CatalogItem>, volume: Volume, wifiOnly: Boolean) -> EnqueueResult =
        { group, items, volume, wifi -> downloads.enqueueGroup(group, items.map { downloadSpecOf(it, group) }, volume.root, wifi) },
) : ViewModel() {
    private val step = MutableStateFlow(Step.WELCOME)
    private val startId = MutableStateFlow<String?>(OnboardingModel.EFFICIENT)
    private val unticked = MutableStateFlow<Set<String>>(emptySet())
    private val regionQuery = MutableStateFlow("")
    /** The user's pick, or [UNCHOSEN] until they pick (the suggestion stands until then; null is "No region"). */
    private val regionId = MutableStateFlow<String?>(UNCHOSEN)
    private val useCaseIds = MutableStateFlow<Set<String>>(emptySet())
    private val askChosen = MutableStateFlow(true)
    private val voiceChosen = MutableStateFlow(true)
    private val saveSpace = MutableStateFlow(false)
    private val wifiOnly = MutableStateFlow(prefs.wifiOnly)
    private val volumePath = MutableStateFlow(prefs.volumePath)
    private val notice = MutableStateFlow<String?>(null)
    private val starting = MutableStateFlow(false)
    private val volumeList = MutableStateFlow<List<Volume>>(emptyList())

    /** Set once Start or Skip has finished; the screen then leaves. */
    val finished: StateFlow<Boolean> get() = done
    private val done = MutableStateFlow(false)

    private class Facts(val catalog: Catalog?, val refreshing: Boolean, val failed: Boolean, val installed: Set<String>, val volumes: List<Volume>)
    private class Choices(val step: Step, val startId: String?, val unticked: Set<String>, val regionQuery: String, val regionId: String?, val useCaseIds: Set<String>, val askChosen: Boolean, val voiceChosen: Boolean, val saveSpace: Boolean)
    private class Setup(val wifiOnly: Boolean, val volumePath: String?, val notice: String?, val starting: Boolean)

    private val facts = combine(catalog.state, downloads.records, library.packs, volumeList) { c, records, packs, vols ->
        Facts(c.catalog, c.refreshing, c.catalog == null && !c.refreshing && c.error != null, c.catalog?.let { installedIds(it, records, packs) }.orEmpty(), vols)
    }
    private val choices = combine(step, startId, unticked, regionQuery, regionId, useCaseIds, askChosen, voiceChosen, saveSpace) { values ->
        @Suppress("UNCHECKED_CAST")
        Choices(values[0] as Step, values[1] as String?, values[2] as Set<String>, values[3] as String, values[4] as String?, values[5] as Set<String>, values[6] as Boolean, values[7] as Boolean, values[8] as Boolean)
    }
    private val setup = combine(wifiOnly, volumePath, notice, starting, ::Setup)

    val ui: StateFlow<OnboardingUi> = combine(facts, choices, setup) { f, c, s -> build(f, c, s) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), OnboardingUi())

    init {
        viewModelScope.launch {
            catalog.load()
            // A fresh install has no cache: fetch it once (Plan U3a Decision 1). A cached one is never refreshed here,
            // so Set up suggestions again stays offline.
            if (catalog.state.value.catalog == null) catalog.refresh()
        }
        refreshVolumes()
    }

    /**
     * What the phone holds, by catalog id: the Kiwix screen's rule, plus every flavour of an installed ZIM (a pack with
     * pictures covers the one without, and the other way round), plus every pack an installed ZIM contains, as the
     * readiness model counts them.
     */
    private fun installedIds(catalog: Catalog, records: List<DownloadRecord>, packs: List<Pack>): Set<String> {
        val ids = catalog.items.filter { statusOf(it, records, packs, tier) == ItemStatus.Installed }.mapTo(HashSet()) { it.id }
        val names = packs.filter { it.status == app.granger.core.library.PackStatus.OK }.mapTo(HashSet()) { it.name }
        catalog.packs.filter { it.zimName.isNotBlank() && it.zimName in names }.mapTo(ids) { it.id }
        // A pack whose articles an installed ZIM holds (Top inside Top 1M) is held too (Plan H4).
        val readiness = catalog.readiness ?: return ids
        catalog.packs.filter { it.zimName.isNotBlank() && readiness.containers(it.zimName).any { c -> c in names } }.mapTo(ids) { it.id }
        return ids
    }

    private fun build(f: Facts, c: Choices, s: Setup): OnboardingUi {
        val catalog = f.catalog
        val starts = catalog?.let { OnboardingModel.startChoices(it, f.installed, c.saveSpace) }.orEmpty()
        val start = starts.firstOrNull { it.id == c.startId }
        val regions = catalog?.let { OnboardingModel.regions(it, f.installed) } ?: Regions(emptyList(), emptyList())
        val suggestion = OnboardingModel.suggestedRegion(regions, countryCode)
        val suggested = c.regionId == UNCHOSEN
        val region = if (suggested) suggestion?.takeIf { it.bytes <= OnboardingModel.PRESELECT_MAX_BYTES }
            else (regions.countries + regions.larger).firstOrNull { it.pack.id == c.regionId }
        val chosen = start?.chosen(c.unticked).orEmpty().flatMap { it.items }.mapTo(HashSet()) { it.id }
        region?.let { chosen += it.pack.id }
        val additions = catalog?.let { OnboardingModel.additions(it, f.installed, chosen) }.orEmpty()
        val picked = additions.filter { it.useCase.id in c.useCaseIds }
        val volume = f.volumes.firstOrNull { it.root.path == s.volumePath } ?: f.volumes.firstOrNull()
        val askLine = catalog?.let { OnboardingModel.askLine(it, tier, dense, f.installed) }
        val voiceLine = catalog?.takeIf { offerVoice }?.let { OnboardingModel.voiceLine(it, f.installed) }
        val review = OnboardingModel.review(
            start, c.unticked, region, picked, volume?.freeBytes ?: 0L, askLine?.takeIf { c.askChosen }, voiceLine?.takeIf { c.voiceChosen },
        )
        return OnboardingUi(
            step = c.step,
            noCatalog = catalog == null,
            refreshing = f.refreshing,
            catalogFailed = f.failed,
            phone = PhoneUi(
                tierLine = tierLine(),
                researchLine = researchLine(offered = askLine != null),
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
            regionSuggested = suggested && region != null,
            suggestion = suggestion,
            additions = additions,
            useCaseIds = c.useCaseIds,
            askLine = askLine,
            askChosen = c.askChosen,
            saveSpace = c.saveSpace,
            voiceLine = voiceLine,
            voiceChosen = c.voiceChosen,
            review = review,
            wifiOnly = s.wifiOnly,
            volumePath = volume?.root?.path,
            notice = s.notice,
            starting = s.starting,
        )
    }

    /** Memory as Readiness words it ([formatBytes]), so the two never disagree. */
    private fun tierLine(): String = when (tier) {
        DeviceTier.LIBRARY_ONLY -> "A library phone, with ${formatBytes(totalRamBytes)} of memory."
        DeviceTier.ASK -> "A library phone that can run Ask, with ${formatBytes(totalRamBytes)} of memory."
    }

    /** [offered]: Pick a start offers Ask's models. */
    private fun researchLine(offered: Boolean): String = when (tier) {
        DeviceTier.LIBRARY_ONLY -> "Ask, the AI librarian, needs a phone with 6 GB of memory, so on this one Granger is the library."
        DeviceTier.ASK -> if (offered) "Ask, the librarian, runs on this phone. You can get it on the next screen." else "Ask, the librarian, runs on this phone."
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

    fun toggleAsk() = askChosen.update { !it }

    fun toggleVoice() = voiceChosen.update { !it }

    fun toggleSaveSpace() = saveSpace.update { !it }

    fun toggleUseCase(id: String) = useCaseIds.update { if (id in it) it - id else it + id }

    /** Also applies to what's already downloading, as Settings' switch does. */
    fun setWifiOnly(on: Boolean) {
        prefs.wifiOnly = on
        wifiOnly.value = on
        viewModelScope.launch { downloads.setWifiOnly(on) }
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
        if (starting.value) return
        viewModelScope.launch {
            val review = ui.value.review
            // The setting flows, not the built UI: a tap on the switch right before Start must count.
            val volume = volumeList.value.firstOrNull { it.root.path == volumePath.value } ?: volumeList.value.firstOrNull()
            if (review.isEmpty || volume == null) {
                skip()
                return@launch
            }
            val group = "onboarding:${now()}"
            starting.value = true
            // Quick wins first (Plan DL Decision 5): the queue takes the group in this order.
            val domains = catalog.state.value.catalog?.readiness?.domains.orEmpty().map { it.id }
            val order = OnboardingModel.downloadOrder(review.items, domains, ui.value.region?.takeUnless { it.installed }?.pack?.id)
            val problem = when (val result = enqueue(group, order, volume, wifiOnly.value)) {
                EnqueueResult.Queued, EnqueueResult.AlreadyActive, EnqueueResult.AlreadyInstalled -> null
                is EnqueueResult.NoSpace -> "Not enough space on ${volume.label}: free up ${formatBytes(result.neededBytes)} or untick something."
                is EnqueueResult.OverCap -> "That would bring your downloads to ${formatBytes(result.wouldUseBytes)}, over the ${formatBytes(result.capBytes)} limit."
            }
            if (problem != null) {
                starting.value = false
                notice.value = problem
                return@launch
            }
            onboardingPrefs.done = true
            done.value = true
        }
    }

    companion object {
        /** [regionId] before the user has picked: the phone's country stands. */
        private const val UNCHOSEN = "\u0000unchosen"

        /** The readiness domain a use case's "See" opens on the Kiwix screen. */
        val DOMAIN_OF = mapOf(
            "offgrid" to "food", "travel" to "maps", "study" to "science", "build" to "making",
            "medical" to "medicine", "software" to "technology", "faith" to "thought",
        )
    }
}
