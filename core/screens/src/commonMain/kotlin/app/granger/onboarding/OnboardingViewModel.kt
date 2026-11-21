package app.granger.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.granger.UiText
import app.granger.core.catalog.Catalog
import app.granger.core.catalog.CatalogItem
import app.granger.core.catalog.CatalogSource
import app.granger.core.catalog.ENGLISH
import app.granger.core.catalog.ModelRole
import app.granger.core.downloads.DownloadRecord
import app.granger.core.downloads.DownloadVolume
import app.granger.core.downloads.DownloadVolumes
import app.granger.core.downloads.Downloads
import app.granger.core.downloads.EnqueueResult
import app.granger.core.downloads.downloadSpecOf
import app.granger.core.library.Library
import app.granger.core.library.Pack
import app.granger.core.library.PackStatus
import app.granger.screens.bytesText
import app.granger.screens.ioDispatcher
import app.granger.screens.nowMillis
import app.granger.settings.LanguageOffers
import app.granger.sources.DownloadPrefs
import app.granger.sources.ItemStatus
import app.granger.sources.statusOf
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// Shared since Plan I4b Task 8: the first run's model for both phones (Welcome, Pick a start, the region, quick wins,
// Review). Android builds it through the app's `OnboardingViewModel(…, volumes, prefs, tier, …)` factory
// (OnboardingViewModels.kt), which supplies the Android-only collaborators; the iPhone calls this constructor directly,
// with `offerVoice` off (no Granger voices there yet), so Pick a start offers no voice line.

/** The six screens (spec §6; Region and Uses split in Plan U3a). */
/** Plan OB: Welcome holds the choice; Outfit for offline is one page, then Review. */
enum class Step { WELCOME, OUTFIT, REVIEW }

/** This phone (step 2). */
data class PhoneUi(
    val tierLine: UiText,
    /** Whether this phone can run Ask, and where to get it; null when there's nothing to say. */
    val researchLine: UiText?,
    val volumes: List<DownloadVolume>,
    /** The smallest start offered (Starter, or a language's Efficient start), and what it would download. */
    val smallestTitle: String,
    val smallestBytes: Long,
    val comprehensiveBytes: Long,
)

data class OnboardingUi(
    val step: Step = Step.WELCOME,
    /** Wikipedia online is on (Plan OB): Welcome holds Start reading now and Outfit for offline. */
    val online: Boolean = false,
    /** Welcome's line: a phone too small for Ask isn't promised a librarian (Plan E1 phone check). */
    val intro: UiText = UiText.Res("onboarding_intro"),
    /** No catalog yet: Pick a start shows the fetch (fresh install) or why it failed. */
    val noCatalog: Boolean = true,
    val refreshing: Boolean = false,
    /** The fetch onboarding started failed; Pick a start offers Try again, and Next still works. */
    val catalogFailed: Boolean = false,
    val phone: PhoneUi = PhoneUi(UiText.Raw(""), null, emptyList(), "", 0, 0),
    val starts: List<StartChoice> = emptyList(),
    /** A start choice's id (the first, smallest, until the user picks), or null for "I'll choose later". */
    val startId: String? = null,
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
    val notice: UiText? = null,
    /** Start was tapped and the downloads are being written; the button waits. */
    val starting: Boolean = false,
    /** The library's languages (Plan L3), main first. */
    val languages: List<String> = listOf(ENGLISH),
    /**
     * The second language Welcome offers ("Also Spanish?"): only when the phone lists more than one language the catalog
     * has tiers for (Neal's call 4); null otherwise.
     */
    val alsoLanguage: String? = null,
    /** A second library language's encyclopedia, offered under the start choices; null with one language. */
    val secondLine: Line? = null,
    val secondChosen: Boolean = true,
) {
    /** Whether [alsoLanguage] is one of the library's languages. */
    val alsoOn: Boolean get() = alsoLanguage != null && alsoLanguage in languages

    val start: StartChoice? get() = starts.firstOrNull { it.id == startId }
    val chosenVolume: DownloadVolume? get() = phone.volumes.firstOrNull { it.root == volumePath } ?: phone.volumes.firstOrNull()
}

/**
 * The flow's state: a step and the choices, kept here so Back and rotation keep them and the review sees them all
 * (Decision 2). The installed set comes from the download ledger and the library, through the Kiwix screen's rule.
 */
class OnboardingViewModel(
    private val catalog: CatalogSource,
    library: Library,
    private val downloads: Downloads,
    private val volumes: DownloadVolumes,
    private val prefs: DownloadPrefs,
    /** The phone has the memory for Ask and the Granger voices (Android: `DeviceTier.ASK` and up). */
    private val askTier: Boolean,
    /** Which catalog models this phone can run (Android: `DeviceTier.canRun`): what counts as installed follows it. */
    private val canRun: (ModelRole) -> Boolean,
    private val totalRamBytes: Long,
    private val onboardingPrefs: OnboardingPrefs,
    /** Ask runs here (the app's `askOn`): off, onboarding words the phone as a library and offers no Ask line. */
    private val askOn: Boolean = askTier,
    /** Ask is a feature of this build (Android: `Features.RESEARCH`): off with [askOn] off, nothing is said of it. */
    private val research: Boolean = false,
    private val dense: Boolean = false,
    /** The phone's country ("PE"), for the suggested map; blank for none. */
    private val countryCode: String = "",
    /**
     * Pick a start offers a Granger voice (Android: `Features.READ_ALOUD && Features.PLAYER`). Off on the iPhone until
     * voices come there: no voice line, nothing of it in Review or the queue.
     */
    private val offerVoice: Boolean = false,
    private val io: CoroutineDispatcher = ioDispatcher,
    private val now: () -> Long = ::nowMillis,
    /** The library's languages (Plan L3), main first, and how Welcome's offer changes them. */
    knowledge: Flow<List<String>> = flowOf(listOf(ENGLISH)),
    private val setKnowledge: (List<String>) -> Unit = {},
    /** The phone's languages in order ("es", "en"), whatever the app is set to. */
    private val phoneLanguages: () -> List<String> = { emptyList() },
    /** The app's language, for the catalog's copy (Plan L3). */
    private val appLanguage: () -> String = { ENGLISH },
    private val enqueue: suspend (groupId: String, items: List<CatalogItem>, volume: DownloadVolume, wifiOnly: Boolean) -> EnqueueResult =
        { group, items, volume, wifi -> downloads.enqueueGroup(group, items.map { downloadSpecOf(it, group) }, volume.root, wifi) },
    /** Wikipedia online is on (Plan O5): Welcome offers Start reading now beside Outfit for offline. Off, it's Get started. */
    private val chooseOnline: Boolean = false,
    /** The privacy line has been shown (Plan O5 builder's call 2): both of Welcome's choices sit over it (Plan OB). */
    private val onPrivacySeen: () -> Unit = {},
    /** Where the flow opens: Welcome on first run, the Outfit page from Settings (Plan OB call 4), Back from it leaving. */
    private val startAt: Step = Step.WELCOME,
) : ViewModel() {
    private val step = MutableStateFlow(startAt)

    /** Start reading now was chosen: onboarding ends on Explore (Plan O5 Task 1). */
    private val readNow = MutableStateFlow(false)
    val readingNow: StateFlow<Boolean> = readNow
    private val startId = MutableStateFlow<String?>(OnboardingModel.DEFAULT_START)
    private val unticked = MutableStateFlow<Set<String>>(emptySet())
    private val regionQuery = MutableStateFlow("")
    /** The user's pick, or [UNCHOSEN] until they pick (the suggestion stands until then; null is "No region"). */
    private val regionId = MutableStateFlow<String?>(UNCHOSEN)
    private val useCaseIds = MutableStateFlow<Set<String>>(emptySet())
    private val askChosen = MutableStateFlow(true)
    private val voiceChosen = MutableStateFlow(true)
    /** Null until the user flips it: then the default decides (Plan E1 item 5). */
    private val saveSpace = MutableStateFlow<Boolean?>(null)
    private val secondChosen = MutableStateFlow(true)
    private val wifiOnly = MutableStateFlow(prefs.wifiOnly)
    private val volumePath = MutableStateFlow(prefs.volumePath)
    private val notice = MutableStateFlow<UiText?>(null)
    private val starting = MutableStateFlow(false)
    private val volumeList = MutableStateFlow<List<DownloadVolume>>(emptyList())

    /** Set once Start or Skip has finished; the screen then leaves. */
    val finished: StateFlow<Boolean> get() = done
    private val done = MutableStateFlow(false)

    private class Facts(
        val catalog: Catalog?, val refreshing: Boolean, val failed: Boolean, val installed: Set<String>, val volumes: List<DownloadVolume>,
        val languages: List<String>,
    )
    private class Choices(val step: Step, val startId: String?, val unticked: Set<String>, val regionQuery: String, val regionId: String?, val useCaseIds: Set<String>, val askChosen: Boolean, val voiceChosen: Boolean, val saveSpace: Boolean?, val secondChosen: Boolean)
    private class Setup(val wifiOnly: Boolean, val volumePath: String?, val notice: UiText?, val starting: Boolean)

    private val facts = combine(catalog.state, downloads.records, library.packs, volumeList, knowledge) { c, records, packs, vols, langs ->
        Facts(
            c.catalog, c.refreshing, c.catalog == null && !c.refreshing && c.error != null, c.catalog?.let { installedIds(it, records, packs) }.orEmpty(), vols,
            langs.ifEmpty { listOf(ENGLISH) },
        )
    }
    private val choices = combine(step, startId, unticked, regionQuery, regionId, useCaseIds, askChosen, voiceChosen, saveSpace, secondChosen) { values ->
        @Suppress("UNCHECKED_CAST")
        Choices(values[0] as Step, values[1] as String?, values[2] as Set<String>, values[3] as String, values[4] as String?, values[5] as Set<String>, values[6] as Boolean, values[7] as Boolean, values[8] as Boolean?, values[9] as Boolean)
    }
    private val setup = combine(wifiOnly, volumePath, notice, starting, ::Setup)

    val ui: StateFlow<OnboardingUi> = combine(facts, choices, setup) { f, c, s -> build(f, c, s) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), OnboardingUi(intro = intro()))

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
        val ids = catalog.items.filter { statusOf(it, records, packs, canRun) == ItemStatus.Installed }.mapTo(HashSet()) { it.id }
        val names = packs.filter { it.status == PackStatus.OK }.mapTo(HashSet()) { it.name }
        catalog.packs.filter { it.zimName.isNotBlank() && it.zimName in names }.mapTo(ids) { it.id }
        // A pack whose articles an installed ZIM holds (Top inside Top 1M) is held too (Plan H4, Plan WF Decision 5).
        val containment = app.granger.core.catalog.Containment(catalog)
        catalog.packs.filter { it.zimName.isNotBlank() && containment.containers(it.zimName).any { c -> c in names } }.mapTo(ids) { it.id }
        return ids
    }

    private fun build(f: Facts, c: Choices, s: Setup): OnboardingUi {
        val catalog = f.catalog
        val appLang = appLanguage()
        // The card when it has more room, unless a place was chosen (Plan E1 item 5).
        val volume = f.volumes.firstOrNull { it.root == s.volumePath } ?: OnboardingModel.defaultVolume(f.volumes)
        // Save space on by default when the start with pictures would leave little room and the one without fits.
        val saveSpace = c.saveSpace ?: catalog?.let { cat ->
            fun sized(save: Boolean) = OnboardingModel.startChoices(cat, f.installed, save, f.languages.first(), appLang)
                .let { list -> list.firstOrNull { it.id == c.startId } ?: list.firstOrNull() }?.bytes(c.unticked) ?: 0L
            val pics = sized(false)
            val nopic = sized(true)
            OnboardingModel.saveSpaceByDefault(pics, nopic, volume?.freeBytes)
        } ?: false
        // The main library language's starts; a second language adds its encyclopedia as its own line (Plan L3).
        val starts = catalog?.let { OnboardingModel.startChoices(it, f.installed, saveSpace, f.languages.first(), appLang) }.orEmpty()
        val secondLine = f.languages.getOrNull(1)?.let { lang -> catalog?.let { OnboardingModel.secondLine(it, lang, f.installed, saveSpace) } }
        val startId = if (c.startId == OnboardingModel.DEFAULT_START) starts.firstOrNull()?.id else c.startId
        val start = starts.firstOrNull { it.id == startId }
        val regions = catalog?.let { OnboardingModel.regions(it, f.installed) } ?: Regions(emptyList(), emptyList())
        val suggestion = OnboardingModel.suggestedRegion(regions, countryCode)
        val suggested = c.regionId == UNCHOSEN
        val region = if (suggested) suggestion?.takeIf { it.bytes <= OnboardingModel.PRESELECT_MAX_BYTES }
            else (regions.countries + regions.larger).firstOrNull { it.pack.id == c.regionId }
        val chosen = start?.chosen(c.unticked).orEmpty().flatMap { it.items }.mapTo(HashSet()) { it.id }
        region?.let { chosen += it.pack.id }
        secondLine?.takeIf { c.secondChosen }?.items?.mapTo(chosen) { it.id }
        val additions = catalog?.let { OnboardingModel.additions(it, f.installed, chosen, f.languages, appLang) }.orEmpty()
        val picked = additions.filter { it.useCase.id in c.useCaseIds }
        val askLine = catalog?.takeIf { askOn }?.let { OnboardingModel.askLine(it, askTier, dense, f.installed) }
        // Under 6 GB the Granger voices may stop mid-read (ONNX Runtime keeps ~420 MB): the phone's voice instead (Plan E1 item 2).
        val voiceLine = catalog?.takeIf { offerVoice && askTier }?.let { OnboardingModel.voiceLine(it, f.installed) }
        val review = OnboardingModel.review(
            start, c.unticked, region, picked, volume?.freeBytes ?: 0L, askLine?.takeIf { c.askChosen }, voiceLine?.takeIf { c.voiceChosen },
            containment = catalog?.let { app.granger.core.catalog.Containment(it) },
            second = secondLine?.takeIf { c.secondChosen },
        ).let { r ->
            // Search by meaning (Plan SQ Phase 6): the catalog lists indexes only where it runs, and the embedder follows them.
            val embedder = catalog?.models?.firstOrNull { it.role == app.granger.core.catalog.ModelRole.EMBEDDER }
            if (embedder == null || embedder.id in f.installed || r.items.any { it.id == embedder.id }) r else r.copy(embedderBytes = embedder.sizeBytes)
        }
        val phoneLanguages = LanguageOffers.phoneLanguages(phoneLanguages(), catalog?.languages.orEmpty())
        return OnboardingUi(
            intro = intro(),
            languages = f.languages,
            alsoLanguage = LanguageOffers.secondOffer(f.languages, phoneLanguages),
            secondLine = secondLine,
            secondChosen = c.secondChosen,
            step = c.step,
            online = chooseOnline,
            noCatalog = catalog == null,
            refreshing = f.refreshing,
            catalogFailed = f.failed,
            phone = PhoneUi(
                tierLine = tierLine(),
                researchLine = researchLine(offered = askLine != null),
                volumes = f.volumes,
                smallestTitle = starts.firstOrNull()?.title.orEmpty(),
                smallestBytes = starts.firstOrNull()?.bytes() ?: 0L,
                comprehensiveBytes = starts.firstOrNull { it.id == OnboardingModel.COMPREHENSIVE }?.bytes() ?: 0L,
            ),
            starts = starts,
            startId = startId,
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
            saveSpace = saveSpace,
            voiceLine = voiceLine,
            voiceChosen = c.voiceChosen,
            review = review,
            wifiOnly = s.wifiOnly,
            volumePath = volume?.root,
            notice = s.notice,
            starting = s.starting,
        )
    }

    private fun intro(): UiText = when {
        chooseOnline -> UiText.Res("onboarding_intro_online")
        askOn -> UiText.Res("onboarding_intro")
        else -> UiText.Res("onboarding_intro_library")
    }

    /** Memory as Readiness words it ([bytesText]), so the two never disagree. */
    private fun tierLine(): UiText =
        if (askOn) UiText.Res("onboarding_tier_ask", bytesText(totalRamBytes))
        else UiText.Res("onboarding_tier_library", bytesText(totalRamBytes))

    /** [offered]: Pick a start offers Ask's models. None while Ask is off: it isn't the phone's memory that stops it. */
    private fun researchLine(offered: Boolean): UiText? = when {
        !askOn && !research -> null
        !askTier -> UiText.Res("onboarding_research_needs_memory")
        offered -> UiText.Res("onboarding_research_offered")
        else -> UiText.Res("onboarding_research_runs")
    }

    fun refreshVolumes() {
        viewModelScope.launch { volumeList.value = withContext(io) { runCatching { volumes.list() }.getOrDefault(emptyList()) } }
    }

    fun refreshCatalog() {
        viewModelScope.launch { catalog.refresh() }
    }

    fun next() = step.update { Step.entries.getOrNull(it.ordinal + 1) ?: it }

    /** Back on the step the flow opened at leaves it (Decision 1; from Settings, back to Settings). */
    fun back() {
        if (step.value == startAt) skip() else step.update { Step.entries[it.ordinal - 1] }
    }

    /**
     * How do you want to start? (Plan O5 builder's call 1): Start reading now ends onboarding online, nothing downloaded
     * beyond the small index set (which follows on its own), on Explore; the privacy line under it has been seen.
     */
    fun startReadingNow() {
        onPrivacySeen()
        readNow.value = true
        skip()
    }

    /** Outfit for offline: the one Outfit page (Plan OB). The privacy line under it has been seen too. */
    fun outfit() {
        onPrivacySeen()
        step.value = Step.OUTFIT
    }

    /** The Outfit page's Not now: reading now on first run with online on (Plan OB call 2); from Settings, back there. */
    fun notNow() = if (chooseOnline && startAt == Step.WELCOME) startReadingNow() else skip()

    fun goTo(target: Step) { step.value = target }

    fun chooseStart(id: String?) { startId.value = id }

    fun toggleLine(lineId: String) = unticked.update { if (lineId in it) it - lineId else it + lineId }

    fun setRegionQuery(query: String) { regionQuery.value = query }

    fun chooseRegion(packId: String?) { regionId.value = packId }

    fun toggleAsk() = askChosen.update { !it }

    fun toggleVoice() = voiceChosen.update { !it }

    fun toggleSaveSpace() = saveSpace.update { !(it ?: ui.value.saveSpace) }

    fun toggleSecond() = secondChosen.update { !it }

    /** Welcome's "Also Spanish?": adds the offered language to the library's languages, after the main one, or takes it off. */
    fun toggleAlso() {
        val ui = ui.value
        val also = ui.alsoLanguage ?: return
        setKnowledge(if (ui.alsoOn) ui.languages - also else ui.languages + also)
    }

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
            val volume = volumeList.value.firstOrNull { it.root == volumePath.value } ?: OnboardingModel.defaultVolume(volumeList.value)
            if (review.isEmpty || volume == null) {
                skip()
                return@launch
            }
            val group = "onboarding:${now()}"
            starting.value = true
            // Later downloads go where the start went (the card, if it was the default).
            if (prefs.volumePath == null) prefs.volumePath = volume.root
            // Quick wins first (Plan DL Decision 5): the queue takes the group in this order.
            val domains = catalog.state.value.catalog?.readiness?.domains.orEmpty().map { it.id }
            val order = OnboardingModel.downloadOrder(review.items, domains, ui.value.region?.takeUnless { it.installed }?.pack?.id)
            val problem = when (val result = enqueue(group, order, volume, wifiOnly.value)) {
                EnqueueResult.Queued, EnqueueResult.AlreadyActive, EnqueueResult.AlreadyInstalled -> null
                is EnqueueResult.NoSpace -> UiText.Res("onboarding_no_space", volume.label, bytesText(result.neededBytes))
            }
            if (problem != null) {
                starting.value = false
                notice.value = problem
                return@launch
            }
            onboardingPrefs.done = true
            // Plan OB call 3: Start ends on Explore, reading online while the packs come down, which its line follows.
            if (chooseOnline) {
                onboardingPrefs.outfitGroup = group
                readNow.value = true
            }
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
