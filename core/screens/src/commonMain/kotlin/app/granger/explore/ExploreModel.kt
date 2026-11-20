package app.granger.explore

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.granger.core.library.Pack
import app.granger.core.library.QidArticle
import app.granger.core.library.QidIndexes
import app.granger.core.library.SubjectTime
import app.granger.core.library.TimeIndexes
import app.granger.core.library.TimeMark
import app.granger.side
import kotlin.random.Random
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * The subjects (tools/subjects/subjects.json; ids locked in v1, 2026-11-16), in the order the doors take when two hold
 * as many articles. Every article in a Wikipedia pack carries up to three of them in its QID index (Plan EX Task 1).
 */
enum class Subject(
    val id: String,
    /** The label's string resource name (Android maps it to `R.string`, `ExploreLabels.kt`; iOS's String Catalog keys). */
    val labelKey: String,
    /**
     * The subject's own articles (subjects.json `namesakes`): their lead picture and opening sentence stand for the whole
     * subject on the landing (Neal, 2026-11-16: pictures chosen to represent the whole subject), first that the phone has.
     */
    val namesakes: List<Long> = emptyList(),
) {
    HISTORY("history", "explore_subject_history", listOf(309L)),
    SOCIETY("society", "explore_subject_society", listOf(8425L, 7163L)),
    SCIENCE("science", "explore_subject_science", listOf(336L, 395L)),
    PLACES("places", "explore_subject_places", listOf(1071L)),
    NATURE("nature", "explore_subject_nature", listOf(7860L)),
    HEALTH("health", "explore_subject_health", listOf(11190L, 12147L)),
    TECHNOLOGY("technology", "explore_subject_technology", listOf(11016L)),
    BUSINESS("business", "explore_subject_business", listOf(8134L, 159810L)),
    ART("art", "explore_subject_art", listOf(735L, 12271L)),
    LITERATURE("literature", "explore_subject_literature", listOf(8242L)),
    MUSIC("music", "explore_subject_music", listOf(638L)),
    FILM("film", "explore_subject_film", listOf(11424L, 289L)),
    SPORTS("sports", "explore_subject_sports", listOf(349L)),
    GAMES("games", "explore_subject_games", listOf(11410L)),
    RELIGION("religion", "explore_subject_religion", listOf(9174L, 5891L)),
    LANGUAGES("languages", "explore_subject_languages", listOf(315L)),
    FOOD("food", "explore_subject_food", listOf(2095L, 11451L)),
    ;

    companion object {
        fun of(id: String): Subject? = entries.firstOrNull { it.id == id }

        /**
         * A website pack's subject from its catalog knowledge area (Neal, 2026-11-16: packs become subject doors by their
         * domain). The catalog's `subjects` field tags book packs, Classics and book sets only (KB2), never websites, so
         * this stays: book collections join by their own subjects ([ExploreCollection]). General reference and anything
         * unknown join no door; they stay under Your sources.
         */
        fun ofDomain(domain: String?): Subject? = when (domain) {
            "medicine" -> HEALTH
            "food" -> FOOD
            "making", "technology" -> TECHNOLOGY
            "science" -> SCIENCE
            "maps" -> PLACES
            "literature" -> LITERATURE
            "thought" -> RELIGION
            else -> null
        }
    }
}

/**
 * Through time's eras (the mockup's B timeline), by year (astronomical: 1 BC is 0): each counts the dated items that fall
 * in it, a life at about forty ([app.granger.core.library.TimeIndex.ACTIVE]). [to] is inclusive; the last runs to the far future.
 */
enum class Era(
    val from: Long,
    val to: Long,
    /** The name's and the span's string resource names (see [Subject.labelKey]). */
    val labelKey: String,
    val spanKey: String,
    /** The era's own articles (Middle Ages, 19th century…), whose picture stands for it, first that the phone has. */
    val articles: List<Long> = emptyList(),
) {
    PREHISTORY(Long.MIN_VALUE / 2, -3001, "explore_era_prehistory", "explore_era_prehistory_span", listOf(11756L)),
    ANCIENT(-3000, 499, "explore_era_ancient", "explore_era_ancient_span", listOf(41493L)),
    MIDDLE_AGES(500, 1499, "explore_era_middle_ages", "explore_era_middle_ages_span", listOf(12554L)),
    EARLY_MODERN(1500, 1799, "explore_era_early_modern", "explore_era_early_modern_span", listOf(5308718L)),
    C19(1800, 1899, "explore_era_c19", "explore_era_c19_span", listOf(6955L)),
    WARS(1900, 1945, "explore_era_wars", "explore_era_wars_span", listOf(361L, 362L)),
    RECENT(1946, Long.MAX_VALUE / 2, "explore_era_recent", "explore_era_recent_span", listOf(186075L)),
}

/**
 * An installed book collection on a subject (Plan EX, KB2's catalog `subjects`): a book pack, Granger Classics, Clásicos
 * Granger or a book set, opened as its Library collection [id] (`c:<key>`). [books] counts its books with the subject; 0
 * for a book pack, whose books live inside its file.
 */
data class ExploreCollection(val id: String, val title: String, val books: Int = 0)

/** One installed book or book pack and its catalog subjects (main first), for [collectionsBySubject]. */
data class CollectionMember(val collectionId: String, val title: String, val subjects: List<String>, val pack: Boolean = false)

/**
 * Each subject id's book collections (pure, for tests): the members grouped by collection under every subject they
 * carry. A collection with more books on a subject comes first; packs, then titles, break ties.
 */
fun collectionsBySubject(members: List<CollectionMember>): Map<String, List<ExploreCollection>> {
    val out = HashMap<String, LinkedHashMap<String, ExploreCollection>>()
    for (m in members) for (sub in m.subjects.distinct()) {
        val byId = out.getOrPut(sub) { LinkedHashMap() }
        val was = byId[m.collectionId]
        byId[m.collectionId] = if (m.pack) was ?: ExploreCollection(m.collectionId, m.title)
        else (was ?: ExploreCollection(m.collectionId, m.title)).let { it.copy(books = it.books + 1) }
    }
    return out.mapValues { (_, byId) ->
        byId.values.sortedWith(compareByDescending<ExploreCollection> { it.books }.thenBy { it.title.lowercase() })
    }
}

/** An article Explore shows: where to open it, its title, (when loaded) its picture, and its dates when it has them. */
data class ExploreArticle(
    val packId: String,
    val path: String,
    val title: String,
    val picture: String? = null,
    val mark: TimeMark? = null,
    /** The picture's width over its height, once read (the mosaic shapes its tile by it); 0 when unknown or none. */
    val ratio: Float = 0f,
    /** The picture is a drawing or an emblem (a flag, a seal, a map): shown whole, never cropped. */
    val whole: Boolean = false,
)

/**
 * Today's spread (Neal, 2026-11-17: highlight one topic behind a subject, so every day is different; Plan EX round 2,
 * D1): the day's subject and one of its broad topics, with its picture, its opening sentence, two of its article's
 * [figures], its [room] and what its page holds ([kinds], [people], [chapters]). A subject with no broad topic fit for
 * a spread gives one of its best-known articles, alone. [candidates] are the day's picks in order; the first with a
 * picture is [article].
 */
data class FeaturedTopic(
    val subject: Subject,
    val count: Int,
    val article: ExploreArticle,
    val candidates: List<ExploreArticle>,
    val sentence: String? = null,
    /** The first of the subject's rooms that holds the topic. */
    val room: app.granger.core.library.Facet? = null,
    val figures: List<SpreadFigure> = emptyList(),
    /** As the topic's own page counts them; 0 for a topic that isn't broad, or until read. */
    val kinds: Int = 0,
    val people: Int = 0,
    val chapters: Int = 0,
)

/** A figure from a broad topic's article: its picture (a cached file) and its caption. */
data class SpreadFigure(val picture: String, val caption: String)

/**
 * The order to try a topic's [n] figures in for a spread's two small plates (pure, for tests): one a third of the way
 * through and one two thirds, so they come from different parts of the article, then the rest as they stand.
 */
fun figureOrder(n: Int): List<Int> = if (n <= 0) emptyList() else (listOf(n / 3, 2 * n / 3) + (0 until n)).distinct()

/** A figure's caption as a spread sets it: its first sentence, without the article's footnote marks ("per million people[62]"). */
fun captionOf(caption: String): String = firstSentence(caption.replace(Regex("\\[[^\\]\\s]{1,12}\\]"), ""), 140).trimEnd('.')

/** Whether two cached pictures are the same picture of a pack (one may be kept as a drawing, the other not). */
fun samePicture(a: String?, b: String?): Boolean {
    fun stem(p: String) = p.substringAfterLast('/').substringBefore('.')
    return a != null && b != null && stem(a) == stem(b)
}

/** How a row of the landing's subject mosaic is cut. */
enum class DoorRowKind(val size: Int) { WIDE_FIRST(2), THREE(3), WIDE_LAST(2), PAIR(2), SINGLE(1) }

/**
 * The rows of the subject mosaic for [n] doors in their order (pure, for tests): a wide plate and a narrow one, three
 * across, a narrow and a wide, three across, and round again; two left over stand side by side and one runs the width.
 */
fun doorRows(n: Int): List<DoorRowKind> {
    val cycle = listOf(DoorRowKind.WIDE_FIRST, DoorRowKind.THREE, DoorRowKind.WIDE_LAST, DoorRowKind.THREE)
    val out = ArrayList<DoorRowKind>()
    var left = n
    while (left > 0) {
        val next = cycle[out.size % cycle.size]
        val kind = when {
            left == 1 -> DoorRowKind.SINGLE
            left < next.size || left == 2 -> DoorRowKind.PAIR
            else -> next
        }
        out += kind
        left -= kind.size
    }
    return out
}

/**
 * The era that holds the most of the dated topics and its share of them in words (pure, for tests); null when too few are
 * dated or the share isn't worth saying.
 */
fun busiestEra(eras: List<EraRow>): Pair<EraRow, Share>? {
    val total = eras.sumOf { it.count }
    val top = eras.maxByOrNull { it.count } ?: return null
    if (total < RoomFinding.SAYABLE) return null
    return shareOf(top.count, total)?.let { top to it }
}

/**
 * Today's topic in a subject (pure, for tests): the subject is today's door ([featuredIndex]); within it, the day's place
 * in a pool of its best-known articles, stepping once each time the doors come round, so a (subject, topic) pair comes back
 * only after doors × pool days. A few after it are kept in case it has no picture.
 */
fun featuredCandidates(day: Long, doors: Int, pool: List<ExploreArticle>, keep: Int = 8, rotation: Int = 8): List<ExploreArticle> {
    if (pool.isEmpty() || doors <= 0) return emptyList()
    val turn = day.floorDiv(minOf(doors, rotation).toLong())
    val start = turn.mod(pool.size.toLong()).toInt()
    return (0 until minOf(keep, pool.size)).map { pool[(start + it) % pool.size] }
}

/** A subject's door: how many articles it holds across the packs, its best-known three, and the packs on it. */
data class SubjectDoor(
    val subject: Subject,
    val count: Int,
    val examples: List<ExploreArticle>,
    val packs: List<Pack>,
    val picture: String? = null,
    /** The subject's own article's opening sentence, for the landing's features; null until read, or with none. */
    val lead: String? = null,
    /** The installed book collections on it. */
    val collections: List<ExploreCollection> = emptyList(),
)

/** An era's row: how many dated articles start in it, its best-known three, and where the Timeline opens for it. */
data class EraRow(val era: Era, val count: Int, val examples: List<ExploreArticle>, val picture: String? = null, val lead: String? = null)

/** Explore's landing (Plan EX Task 2, Neal's layout A): subject doors, Through time, then Your sources as one line. */
data class ExploreUi(
    /** Articles across the Wikipedia packs, each item once; 0 without a QID index carrying subjects. */
    val articles: Int,
    val doors: List<SubjectDoor>,
    val eras: List<EraRow>,
    /** Every pack Your sources lists (Browse's), by title. */
    val sources: List<String>,
    /** Today's subject and its topic; null with no subjects. */
    val featured: FeaturedTopic? = null,
    /** How many rooms the doors' subjects hold between them. */
    val rooms: Int = 0,
    /** How many times the reader has asked for another spread: 0 is today's. */
    val skipped: Int = 0,
    /** Every room of the doors' subjects (`subject.room`), and those the reader has seen; [seen] is null when that isn't kept. */
    val roomKeys: Set<String> = emptySet(),
    val seen: Set<String>? = null,
) {
    /** How many of the rooms there are the reader has seen; null when that isn't kept. */
    val roomsSeen: Int? get() = seen?.count { it in roomKeys }

    /** No subjects to show: no Wikipedia, or one whose QID index predates subjects (it updates with the catalog). */
    val noSubjects: Boolean get() = doors.none { it.count > 0 }
}

/** A QID article as Explore shows it: its path's title (World_War_II -> World War II). */
fun QidArticle.toExplore() = ExploreArticle(packId, path, titleOf(path))

/** A Wikipedia path's title: underscores to spaces, the last segment of a nested path. */
fun titleOf(path: String) = path.substringAfterLast('/').replace('_', ' ')

/** A time mark as Explore shows it. */
fun TimeMark.toExplore() = ExploreArticle(packId, path, title, mark = this)

/**
 * The doors (pure, for tests): each subject with any article or pack, the most articles first ([Subject] order breaking
 * ties), its three best-known articles, and the non-Wikipedia [packs] its catalog domain puts on it.
 */
fun subjectDoors(
    counts: Map<String, Int>,
    examples: (Subject) -> List<ExploreArticle>,
    packs: List<Pair<Pack, String?>>,
    collections: Map<String, List<ExploreCollection>> = emptyMap(),
): List<SubjectDoor> {
    val bySubject = packs.mapNotNull { (pack, domain) -> Subject.ofDomain(domain)?.let { it to pack } }.groupBy({ it.first }, { it.second })
    return Subject.entries
        .map { s -> s to (counts[s.id] ?: 0) }
        .filter { (s, n) -> n > 0 || bySubject[s].orEmpty().isNotEmpty() || collections[s.id].orEmpty().isNotEmpty() }
        .sortedWith(compareByDescending<Pair<Subject, Int>> { it.second }.thenBy { it.first.ordinal })
        .map { (s, n) ->
            SubjectDoor(s, n, if (n > 0) examples(s) else emptyList(), bySubject[s].orEmpty().sortedBy { it.title.lowercase() },
                collections = collections[s.id].orEmpty())
        }
}

/** Main subjects that don't make an era's example: the best-known items dated in an era are otherwise countries, cities and tamed animals. */
private val NOT_ERA_EXAMPLES = setOf("places", "nature")

/**
 * An era's examples (pure, for tests): its best-known [marks] whose main subject (from the QID index) isn't a place or
 * a creature, [ExploreViewModel.EXAMPLES] of them. With no subjects at all, the best-known marks as they come.
 */
fun eraExamples(marks: List<TimeMark>, subjectsOf: (Long) -> List<String>, n: Int = ExploreViewModel.EXAMPLES): List<ExploreArticle> {
    val kept = marks.filter { subjectsOf(it.qid).firstOrNull() !in NOT_ERA_EXAMPLES }
    return kept.take(n).map { it.toExplore() }
}

/** What Explore reads; the app's comes from the container (QID and time indexes, thumbnails, the shelves). */
class ExploreSources(
    val qids: suspend () -> QidIndexes,
    val times: suspend () -> TimeIndexes,
    /** The article's picture, cached; null when it has none. Blocking. */
    val picture: (packId: String, path: String) -> String?,
    /** Every installed pack that isn't a book pack, with its catalog knowledge area (null when unknown), as Browse lists them. */
    val packs: Flow<List<Pair<Pack, String?>>>,
    /** Whether [pack] is a Wikipedia (its articles are in the doors already, through its QID index). */
    val isWikipedia: (Pack) -> Boolean,
    /** Loads the catalog, as Browse does, so the QID indexes with subjects arrive (the sidecars follow the catalog). */
    val load: suspend () -> Unit = {},
    /** An article's large picture (cropped later, around its subject) and opening sentence; null when unreadable. Blocking. */
    val look: (packId: String, path: String) -> ArticleLook? = { _, _ -> null },
    /** The installed book collections by subject id ([collectionsBySubject]). */
    val collections: Flow<Map<String, List<ExploreCollection>>> = kotlinx.coroutines.flow.flowOf(emptyMap()),
    /** A room's one-line description (`subject.lens`) in a language, from the app's own list; null when it has none. */
    val roomDescription: (key: String, language: String) -> String? = { _, _ -> null },
    /** One of a pack's pictures by its path in the ZIM (a broad topic's figure), cached; null when it's absent. Blocking. */
    val figure: (packId: String, image: String) -> String? = { _, _ -> null },
    /** Where the reader has been (rooms, subjects, eras), kept on this phone. */
    val seen: SeenStore = SeenStore.Memory(),
    /** Wikipedia online's index set on its way (Plan O1 Task 8): what the landing says while it comes. */
    val onlineIndex: Flow<app.granger.online.IndexWait> = kotlinx.coroutines.flow.flowOf(app.granger.online.IndexWait.NONE),
)

/** An article as a feature shows it: its lead picture, larger than a thumbnail, and its opening sentence. */
data class ArticleLook(val picture: String?, val sentence: String?)

/** A picture file's width over its height, from its header only; 0 when it won't read. */
fun ratioOf(path: String): Float = runCatching {
    val (w, h) = app.granger.screens.imageSize(path) ?: return@runCatching 0f
    if (w > 0 && h > 0) w.toFloat() / h else 0f
}.getOrDefault(0f)

/** The first sentence of [text], at most about [max] characters (cut at a word, with an ellipsis). */
fun firstSentence(text: String, max: Int = 180): String {
    val t = text.trim().replace(Regex("\\s+"), " ")
    // A full stop ends a sentence when a space and a capital follow ("c. 500" and "U.S." don't end one).
    val end = Regex("[.!?](?=\\s+[\\p{Lu}¿¡\"“])").find(t)?.range?.first
    val one = if (end != null) t.substring(0, end + 1) else t
    if (one.length <= max) return one
    val cut = one.lastIndexOf(' ', max).takeIf { it > max / 2 } ?: max
    return one.substring(0, cut).trimEnd(',', ';', ':') + "…"
}

/** Which door the landing features today: a different one of the biggest [pool] each day, the same all day. */
fun featuredIndex(day: Long, doors: Int, pool: Int = 8): Int = if (doors <= 0) -1 else day.mod(minOf(doors, pool).toLong()).toInt()

/** Explore's landing, built off the main thread whenever the packs change; null until the first build. */
class ExploreViewModel(
    private val sources: ExploreSources,
    /** Today, as days since 1970: today's subject and topic follow it. */
    private val today: () -> Long = { app.granger.screens.todayEpochDay() },
    /** The reader's language: today's spread comes from its packs when they have one. */
    private val language: () -> String = { app.granger.screens.phoneLanguage() },
) : ViewModel() {
    private val state = MutableStateFlow<ExploreUi?>(null)
    val ui: StateFlow<ExploreUi?> = state.asStateFlow()

    /** Wikipedia online's index set on its way; the landing builds again once it has come. */
    val onlineIndex: StateFlow<app.granger.online.IndexWait> =
        sources.onlineIndex.stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.Eagerly, app.granger.online.IndexWait.NONE)

    private var packs: List<Pair<Pack, String?>>? = null
    private var collections: Map<String, List<ExploreCollection>> = emptyMap()
    private val building = kotlinx.coroutines.sync.Mutex()
    /** Spreads asked for past today's ([another]): each is the next day's. */
    private var skipped = 0
    private var filling: Job? = null

    init {
        viewModelScope.launch { runCatching { sources.load() } }
        // Rooms seen follow the store: a room opened and come back from is counted at once.
        viewModelScope.launch { sources.seen.state.collect { s -> state.value = state.value?.copy(seen = s.ids(SeenKind.ROOM)) } }
        viewModelScope.launch {
            kotlinx.coroutines.flow.combine(
                sources.packs.map { list -> list.sortedBy { it.first.id } }.distinctUntilChanged(),
                sources.collections.distinctUntilChanged(),
            ) { list, c -> list to c }.collect { (list, c) ->
                packs = list
                collections = c
                rebuild(list)
            }
        }
    }

    init {
        viewModelScope.launch {
            var before = app.granger.online.IndexWait.NONE
            onlineIndex.collect { now ->
                if (before != app.granger.online.IndexWait.NONE && now == app.granger.online.IndexWait.NONE) packs?.let { rebuild(it) }
                before = now
            }
        }
    }

    /** Builds again on coming back (a QID or time index may have arrived since); the indexes reopen only when they changed. */
    fun refresh() {
        val now = packs ?: return
        viewModelScope.launch { rebuild(now) }
    }

    private suspend fun rebuild(list: List<Pair<Pack, String?>>) {
        val started = app.granger.Perf.now()
        // One build at a time: a second asked for meanwhile finds the first's counts already made.
        val built = building.withLock { withContext(app.granger.screens.ioDispatcher) { build(list) } }
        app.granger.Perf.mark("landing.build", started)
        // Unchanged since last time (pictures aside): keep what's on screen, pictures and all.
        if (state.value?.let { shown -> withoutPictures(shown) == built } == true) return
        state.value = built
        // Pictures second: the doors and eras show at once, then fill in one by one, the page's top first (all at once
        // took 10–14 s on a cold Pixel).
        filling?.cancel()
        withContext(app.granger.screens.ioDispatcher) {
            withPictures(built) { ui ->
                // Another spread asked for meanwhile stays: only the rest of the page is this pass's to fill.
                val now = state.value
                state.value = if (now != null && now.skipped != ui.skipped) ui.copy(featured = now.featured, skipped = now.skipped) else ui
            }
        }
    }

    /** Another spread: tomorrow's, then the day after's, so each press changes the subject as well as the topic. */
    fun another() {
        val shown = state.value ?: return
        filling?.cancel()
        filling = viewModelScope.launch {
            skipped++
            val next = withContext(app.granger.screens.ioDispatcher) { featuredOf(today() + skipped, shown.doors, sources.qids()) } ?: return@launch
            state.value = (state.value ?: shown).copy(featured = next, skipped = skipped)
            withContext(app.granger.screens.ioDispatcher) { spread(next) { f -> state.value?.let { ui -> if (ui.featured?.candidates == f.candidates) state.value = ui.copy(featured = f) } } }
        }
    }

    /** A well-known article from anywhere in the library, at random, for Wander to start at; null without any. */
    suspend fun wanderStart(random: Random = Random.Default): ExploreArticle? = withContext(app.granger.screens.ioDispatcher) {
        val door = state.value?.doors?.filter { it.count > 0 }?.takeIf { it.isNotEmpty() }?.random(random) ?: return@withContext null
        sources.qids().topIn(door.subject.id, SubjectViewModel.WANDER_POOL, mainOnly = true).takeIf { it.isNotEmpty() }?.random(random)?.toExplore()
    }

    /**
     * The spread for [day]: the day's door, and the day's place among its broad topics that can fill a spread (enough
     * kinds and figures), best known first; a subject with none takes one of its best-known articles.
     */
    private fun featuredOf(day: Long, doors: List<SubjectDoor>, qids: QidIndexes): FeaturedTopic? {
        val d = doors.getOrNull(featuredIndex(day, doors.size))?.takeIf { it.count > 0 } ?: return null
        val own = d.subject.namesakes
        val broad = qids.broadIn(d.subject.id, BROAD_KINDS, BROAD_FIGURES, BROAD_POOL + own.size, language()).filter { it.qid !in own }.take(BROAD_POOL)
        val pool = broad.ifEmpty { qids.topIn(d.subject.id, FEATURE_POOL + own.size, mainOnly = true).filter { it.qid !in own } }.map { it.toExplore() }
        return featuredCandidates(day, doors.size, pool).takeIf { it.isNotEmpty() }?.let { FeaturedTopic(d.subject, d.count, it.first(), it) }
    }

    /**
     * Fills a spread, each part [shown] as it arrives: the first of the day's picks with a picture and its opening
     * sentence; then two of its figures; then its room and what its page holds.
     */
    private suspend fun spread(start: FeaturedTopic, shown: (FeaturedTopic) -> Unit) {
        val qids = sources.qids()
        val pick = start.candidates.asSequence().mapNotNull { a ->
            runCatching { sources.look(a.packId, a.path) }.getOrNull()?.takeIf { it.picture != null }?.let { a to it }
        }.firstOrNull()
        var f = start
        if (pick != null) {
            val p = pick.second.picture
            f = f.copy(
                article = pick.first.copy(picture = p, ratio = p?.let(::ratioOf) ?: 0f, whole = ThumbnailNames.isWhole(p)),
                sentence = pick.second.sentence?.let(::firstSentence),
            )
            shown(f)
        }
        val a = f.article
        val qid = runCatching { qids.qidOf(a.packId, a.path) }.getOrNull() ?: return
        val parts = runCatching { qids.topicParts(qid, a.packId) }.getOrNull()
        if (parts != null && f.article.picture != null) {
            val figures = figureOrder(parts.figures.size).asSequence().take(FIGURE_TRIES).map { parts.figures[it] }.filter { it.caption.isNotBlank() }
                .mapNotNull { fig -> runCatching { sources.figure(parts.packId, fig.image) }.getOrNull()?.let { SpreadFigure(it, captionOf(fig.caption)) } }
                // The article's own picture is often one of its figures too: it is on the spread already.
                .filter { samePicture(it.picture, a.picture).not() }.distinctBy { it.picture }
                .take(FIGURES).toList()
            // Two or none: the opening group is one plate or three.
            if (figures.size == FIGURES) { f = f.copy(figures = figures); shown(f) }
        }
        val room = runCatching { qids.facetOf(qid, f.subject.id) }.getOrNull()
        val broad = parts?.let { p ->
            val times = runCatching { sources.times() }.getOrNull()
            // The page's own rule, without its pictures: the landing says what the page will show.
            runCatching {
                app.granger.topic.BroadTopics.build(
                    p,
                    resolve = { q -> qids.firstArticleOf(q)?.let { it.packId to it.path } },
                    card = { packId, path -> app.granger.topic.TopicArticle(packId, path, titleOf(path), "", null) },
                    figure = { null },
                    markOf = { packId, path -> runCatching { times?.find(packId, path) }.getOrNull() },
                    now = app.granger.screens.currentYear(), pictures = false,
                )
            }.getOrNull()
        }
        f = f.copy(
            room = room,
            kinds = if (broad != null && broad.hasKinds) broad.under else 0,
            people = broad?.people?.size ?: 0,
            chapters = broad?.let { it.chapters.size + if (it.story != null) 1 else 0 } ?: 0,
        )
        shown(f)
    }

    private suspend fun build(packs: List<Pair<Pack, String?>>): ExploreUi = kotlinx.coroutines.coroutineScope {
        val qids = sources.qids()
        val subjects = qids.hasSubjects
        // The counts, the doors' examples and the eras are read side by side: each is its own pass over the indexes, and
        // one after another they were a second and a half on a cold start.
        val counting = async { app.granger.Perf.time("build.counts") { if (subjects) Subject.entries.side(SUBJECTS_AT_ONCE) { it.id to qids.subjectCounts(listOf(it.id)).getValue(it.id) }.toMap() else emptyMap() } }
        val items = async { app.granger.Perf.time("build.items") { if (subjects) qids.itemCount() else 0 } }
        // The subject's own article (History in History) stands for the door already; examples are what it holds.
        val examples = async {
            app.granger.Perf.time("build.examples") {
                if (!subjects) emptyMap() else Subject.entries.side(SUBJECTS_AT_ONCE) { s ->
                    s to qids.topIn(s.id, EXAMPLES + s.namesakes.size, mainOnly = true).filter { it.qid !in s.namesakes }.take(EXAMPLES).map { it.toExplore() }
                }.toMap()
            }
        }
        val dated = async {
            val times = sources.times()
            app.granger.Perf.time("build.eras") {
                if (times.isEmpty) emptyList() else Era.entries.side { era ->
                    EraRow(era, times.countActive(era.from, era.to), eraExamples(times.topActive(era.from, era.to, ERA_POOL), qids::subjectsOf))
                }.filter { it.count > 0 }
            }
        }
        val others = packs.filterNot { sources.isWikipedia(it.first) }
        val found = examples.await()
        val doors = subjectDoors(counting.await(), { s -> found[s].orEmpty() }, others, collections)
        val featured = featuredOf(today() + skipped, doors, qids)
        val roomKeys = doors.flatMapTo(HashSet()) { d -> qids.facets(d.subject.id).map { it.key } }
        ExploreUi(
            items.await(), doors, dated.await(), packs.map { it.first.title }.sortedBy { it.lowercase() }, featured, roomKeys.size, skipped,
            roomKeys, sources.seen.state.value.ids(SeenKind.ROOM),
        )
    }

    /** An era opened from the landing: it is seen. */
    fun sawEra(era: Era) = sources.seen.mark(SeenKind.ERA, era.name.lowercase())

    private fun withoutPictures(ui: ExploreUi) = ui.copy(
        doors = ui.doors.map { it.copy(picture = null, lead = null) },
        eras = ui.eras.map { it.copy(picture = null, lead = null) },
        featured = ui.featured?.let { f -> FeaturedTopic(f.subject, f.count, f.candidates.first(), f.candidates) },
    )

    /**
     * Each door and era gets its own article's picture and opening sentence (History's for History, the Middle Ages' for
     * the Middle Ages), so the picture stands for the whole of it; without one, the first picture among its examples.
     */
    private suspend fun withPictures(start: ExploreUi, shown: (ExploreUi) -> Unit) {
        val qids = sources.qids()
        fun first(examples: List<ExploreArticle>) = examples.firstNotNullOfOrNull { a -> runCatching { sources.picture(a.packId, a.path) }.getOrNull() }
        fun own(items: List<Long>): ArticleLook? = items.asSequence()
            .mapNotNull { q -> qids.articlesOf(q).firstOrNull() }
            .mapNotNull { a -> runCatching { sources.look(a.packId, a.path) }.getOrNull() }
            .firstOrNull { it.picture != null }
        val started = app.granger.Perf.now()
        var ui = start
        val lock = app.granger.core.library.TryLock()
        fun change(with: (ExploreUi) -> ExploreUi) {
            lock.lock()
            try { ui = with(ui); shown(ui) } finally { lock.unlock() }
        }
        // Today's spread (its picture, large, with its opening sentence, then its figures and its counts) and the
        // subjects' pictures are read at the same time: neither waits for the other.
        kotlinx.coroutines.coroutineScope {
            launch {
                start.featured?.let { f -> spread(f) { read -> change { it.copy(featured = read) } } }
                app.granger.Perf.mark("landing.spread", started)
            }
            launch {
                // The subjects, in the mosaic's order, a row or two at a time, read side by side. (Through time is bars
                // now: the eras' pictures aren't read.)
                for (some in start.doors.indices.chunked(DOORS_AT_ONCE)) {
                    val read = some.side { i ->
                        val d = start.doors[i]
                        val l = own(d.subject.namesakes)
                        d.copy(picture = l?.picture ?: first(d.examples), lead = l?.sentence?.let(::firstSentence))
                    }
                    change { now -> now.copy(doors = now.doors.toMutableList().also { all -> some.forEachIndexed { at, i -> all[i] = read[at] } }) }
                }
                app.granger.Perf.mark("landing.doors", started)
            }
        }
    }

    /**
     * The library map's ring inside [centre] (Plan EX Task 5): a subject's or an era's best-known articles, as its examples
     * are chosen, with their pictures.
     */
    suspend fun inside(centre: MapCentre): List<ExploreArticle> = withContext(app.granger.screens.ioDispatcher) {
        val qids = sources.qids()
        val found = when (centre) {
            is MapCentre.OfSubject -> qids.topIn(centre.subject.id, MAP_RING + centre.subject.namesakes.size, mainOnly = true)
                .filter { it.qid !in centre.subject.namesakes }.take(MAP_RING).map { it.toExplore() }
            is MapCentre.OfEra -> {
                val times = sources.times()
                if (times.isEmpty) emptyList() else eraExamples(times.topActive(centre.era.from, centre.era.to, ERA_POOL * 3), qids::subjectsOf, MAP_RING)
            }
            MapCentre.Library -> emptyList()
        }
        found.map { a -> a.copy(picture = runCatching { sources.picture(a.packId, a.path) }.getOrNull()) }
    }

    companion object {
        /** Articles around a subject or era on the library map: an inner ring of six and four outside, as on the topic map. */
        const val MAP_RING = 10

        /** Examples per door and per era (the mockup's three). */
        const val EXAMPLES = 3

        /** How many of an era's best-known marks [eraExamples] picks from. */
        const val ERA_POOL = 40

        /** Today's topic comes from this many of the subject's best-known articles. */
        const val FEATURE_POOL = 60

        /** A broad topic fills a spread with at least this many articles under it and this many figures. */
        const val BROAD_KINDS = 8
        const val BROAD_FIGURES = 2

        /** Today's spread comes from this many of the subject's best-known broad topics. */
        const val BROAD_POOL = 12

        /**
         * Reads what the landing counts (each subject's size and best known, the library's size, each era's) so the
         * index snapshots hold them before Explore is first opened: on a cold start the counting was a second of the
         * landing's wait. Run once, a moment after the app starts, off the main thread.
         */
        suspend fun warm(sources: ExploreSources) = kotlinx.coroutines.coroutineScope {
            val qids = sources.qids()
            if (!qids.hasSubjects) return@coroutineScope
            launch { Subject.entries.side(SUBJECTS_AT_ONCE) { qids.subjectCounts(listOf(it.id)) } }
            launch { qids.itemCount() }
            launch { Subject.entries.side(SUBJECTS_AT_ONCE) { s -> qids.topIn(s.id, EXAMPLES + s.namesakes.size, mainOnly = true) } }
            launch {
                val times = sources.times()
                if (!times.isEmpty) Era.entries.side { era -> times.countActive(era.from, era.to); times.topActive(era.from, era.to, ERA_POOL) }
            }
        }

        /** Subjects counted, and read for their examples, together. */
        const val SUBJECTS_AT_ONCE = 6

        /** Doors whose pictures are read together. */
        const val DOORS_AT_ONCE = 5

        /** A spread's small plates, and how many of the topic's figures are tried for them. */
        const val FIGURES = 2
        const val FIGURE_TRIES = 8
    }
}

/** A book on the shelf with a Wikidata work (from its links file): In your books on a subject page. */
data class ExploreBook(val bookId: String, val title: String, val cover: String?, val work: Long)

/** The order a subject's articles take: most linked (main subject first), or oldest first (dated ones only). */
enum class SubjectSort { LINKED, OLDEST, AZ }

/**
 * A title's letter on the A–Z rail (Neal's review 2026-11-17): its first letter without accents, upper case ("Ángel" is
 * A, "Œdipus" is O); "#" for a digit or symbol.
 */
fun railLetter(title: String): String {
    val c = app.granger.core.text.decompose(title.trimStart().take(2), compat = false)
        .firstOrNull { it.isLetterOrDigit() } ?: return "#"
    if (!c.isLetter()) return "#"
    return when (val u = c.uppercaseChar()) { 'Œ' -> "O"; 'Æ' -> "A"; 'Ø' -> "O"; 'ß' -> "S"; else -> u.toString() }
}

/** [articles] in A–Z order by title, for [locale]'s alphabet, accents and case set aside (ties: path). */
fun alphabetical(articles: List<ExploreArticle>, language: String): List<ExploreArticle> {
    val c = app.granger.screens.titleComparator(language)
    return articles.sortedWith(Comparator<ExploreArticle> { a, b -> c.compare(a.title, b.title) }.thenBy { it.path })
}

/**
 * One of a subject's sub-categories as its page shows it (Plan EX editorial pass): the facet, how many articles it holds,
 * its best-known few, and the first of their pictures (large, cropped round its subject later).
 */
data class FacetCard(
    val facet: app.granger.core.library.Facet,
    val count: Int,
    val examples: List<ExploreArticle>,
    val picture: String? = null,
    /** The room's one-line description (the app's own list); null when it has none. */
    val description: String? = null,
    /** What the room's dates say, as its own page says it; null with none dated, or until read ([told]). */
    val finding: RoomFinding? = null,
    /** The room's dates have been read, so its sentence is final (its finding, else its description). */
    val told: Boolean = false,
)

/** A subject's doors: the subjects [before] and [after] it in the library's order (the landing's), where it stands [position] of [total]. */
data class SubjectDoors(val before: Subject?, val after: Subject?, val position: Int, val total: Int)

/**
 * One subject opened (Plan EX Task 3): its eras with counts, its articles most linked or oldest first, a page at a time,
 * In your books, its packs, and Wander and Quiz. [eras] and [dated] arrive after the articles (one pass over the time
 * indexes); [era] null is every era.
 */
data class SubjectUi(
    val subject: Subject,
    val count: Int,
    val articles: List<ExploreArticle>,
    val packs: List<Pack>,
    val more: Boolean,
    val eras: List<Pair<Era, Int>> = emptyList(),
    val dated: Int = 0,
    /** Dated articles whose main subject this is: the quiz deals only these. */
    val datedMain: Int = 0,
    val era: Era? = null,
    val sort: SubjectSort = SubjectSort.LINKED,
    val books: List<ExploreBook> = emptyList(),
    /** The installed book collections on this subject. */
    val collections: List<ExploreCollection> = emptyList(),
    /** Still reading this view's articles (a chip or sort just changed). */
    val loading: Boolean = false,
    /** The sub-category this list is (its listing page); null for the whole subject. */
    val facet: app.granger.core.library.Facet? = null,
    /** The subject page's sub-categories, in their curated order (Plan EX editorial pass); empty on a listing. */
    val facets: List<FacetCard> = emptyList(),
    /** The subject's own article's picture and opening sentence, for the page's head; null until read. */
    val hero: ArticleLook? = null,
    /** In A–Z order: the letters its titles start with, for the rail; empty in the other orders. */
    val letters: List<String> = emptyList(),
    /** In A–Z order: the letter the list now starts at; null at the start. */
    val letter: String? = null,
    /** A room's own parts (its description, finding, plates through time, overlaps, doors); null on other pages. */
    val room: RoomUi? = null,
    /** The subject page's opening plates: its own article, then its best known with a photograph; up to three. */
    val opening: List<ExploreArticle> = emptyList(),
    /** What the subject's dates say (its busiest century), for the heading over its rooms; null until read. */
    val finding: RoomFinding? = null,
    /** The subject page's doors; null on a listing, or when the subject stands alone. */
    val doors: SubjectDoors? = null,
    /** The rooms (`subject.room`) the reader has seen; null when that isn't kept. */
    val seen: Set<String>? = null,
    /** The subject page's opening plates, and its rooms, are still being read: their places are kept, and nothing stands in for them. */
    val openingPending: Boolean = false,
    val roomsPending: Boolean = false,
) {
    /** Enough dated main-subject articles for a run of the time games. */
    val canQuiz: Boolean get() = datedMain >= SubjectViewModel.QUIZ_MIN
    val canWander: Boolean get() = count > 0
}

/**
 * A subject opened. The subject's page (Plan EX editorial pass: its head, Wander and Quiz, its sub-categories, its books)
 * when [listing] is false; else a listing of its articles, the whole subject's or [facet]'s, with eras and order, each
 * page's pictures read before it shows (the mosaic shapes its tiles by them).
 */
class SubjectViewModel(
    private val subject: Subject,
    private val sources: ExploreSources,
    private val books: suspend () -> List<ExploreBook> = { emptyList() },
    /** The sub-category (`subject.facet` key's facet) to list; null for the whole subject. */
    private val facetId: String? = null,
    private val listing: Boolean = facetId != null,
    /** The alphabet A–Z follows (the app's language). */
    private val language: String = app.granger.screens.phoneLanguage(),
) : ViewModel() {
    private val state = MutableStateFlow<SubjectUi?>(null)
    val ui: StateFlow<SubjectUi?> = state.asStateFlow()

    /** The subject's dated items, once read; null before (the eras and the oldest-first order need it). */
    private var time: SubjectTime? = null
    /** The current view's order when it comes from [time] (an era chosen, or oldest first); null for most linked overall. */
    private var order: List<Int>? = null
    /** In A–Z order: every article of the view, alphabetical, and where the list now starts in it. */
    private var az: List<ExploreArticle>? = null
    private var azStart = 0
    private var viewing: Job? = null
    private var seen: Set<String>? = null

    /** A listing shows fewer at a time, so its foot (where its topics also hang, the ways on) stays in reach. */
    private val pageSize = if (listing) ROOM_PAGE else PAGE

    init {
        // Opening a subject's page, or a room, is seeing it (never an article, and not the whole-subject listing).
        if (!listing) sources.seen.mark(SeenKind.SUBJECT, subject.id) else if (facetId != null) sources.seen.mark(SeenKind.ROOM, "${subject.id}.$facetId")
        viewModelScope.launch { sources.seen.state.collect { s -> seen = s.ids(SeenKind.ROOM); state.value = state.value?.copy(seen = seen) } }
        viewModelScope.launch {
            kotlinx.coroutines.flow.combine(sources.packs, sources.collections) { p, c -> p to c }.collect { (packs, c) ->
                val shelved = c[subject.id].orEmpty()
                val others = packs.filterNot { sources.isWikipedia(it.first) }
                    .filter { Subject.ofDomain(it.second) == subject }.map { it.first }.sortedBy { it.title.lowercase() }
                val first = withContext(app.granger.screens.ioDispatcher) {
                    val qids = sources.qids()
                    val facet = facetId?.let { id -> qids.facets(subject.id).firstOrNull { it.id == id } }
                    val count = when {
                        facet != null -> qids.facetCounts(subject.id)[facet.id] ?: 0
                        qids.hasSubjects -> qids.subjectCounts(listOf(subject.id))[subject.id] ?: 0
                        else -> 0
                    }
                    val page = linked(facet, 0)
                    val room = if (listing && facet != null) {
                        val counts = qids.facetCounts(subject.id)
                        val (before, at, after) = neighbours(qids.facets(subject.id).filter { (counts[it.id] ?: 0) > 0 }) { it.id == facet.id }
                        RoomUi(sources.roomDescription(facet.key, language), before = before, after = after, position = at, total = counts.count { it.value > 0 }, storyPending = true)
                    } else null
                    // The doors run in the landing's order: the subjects with anything on them, the largest first.
                    val doors = if (!listing && qids.hasSubjects) {
                        val all = subjectDoors(qids.subjectCounts(Subject.entries.map { it.id }), { emptyList() }, packs.filterNot { sources.isWikipedia(it.first) }, c).map { it.subject }
                        val (before, at, after) = neighbours(all) { it == subject }
                        if (at > 0 && all.size > 1) SubjectDoors(before, after, at, all.size) else null
                    } else null
                    SubjectUi(subject, count, if (listing) emptyList() else page, others, more = page.size < count,
                        collections = if (listing) emptyList() else shelved, facet = facet, loading = listing, room = room, doors = doors,
                        openingPending = !listing, roomsPending = !listing, seen = seen) to page
                }
                val was = state.value
                state.value = was?.copy(count = first.first.count, packs = first.first.packs, collections = if (listing) emptyList() else shelved, doors = first.first.doors)
                    ?: first.first
                if (was == null) {
                    if (listing) launch { appendWithPictures(first.second, total = first.first.count) }
                    launch { details() }
                    if (!listing) launch { page() }
                }
            }
        }
    }

    /** The articles most linked first, from [offset]: the facet's, or the subject's (main subject first). Blocking. */
    private suspend fun linked(facet: app.granger.core.library.Facet?, offset: Int): List<ExploreArticle> {
        val qids = sources.qids()
        return if (facet != null) qids.topInFacet(facet.key, pageSize, offset).map { it.toExplore() }
        else qids.topIn(subject.id, pageSize, offset).map { it.toExplore() }
    }

    /** The eras (one pass over the time indexes) and In your books, after the first page shows. */
    private suspend fun details() {
        val facet = state.value?.facet
        val (t, eras) = withContext(app.granger.screens.ioDispatcher) {
            val qids = sources.qids()
            val times = sources.times()
            if (!qids.hasSubjects || times.isEmpty) return@withContext null to emptyList()
            val t = times.datedIn(if (facet != null) qids.facetSet(facet.key) else qids.subjectSet(subject.id))
            t to Era.entries.map { it to t.count(it.from, it.to) }.filter { it.second > 0 }
        }
        time = t
        state.value = state.value?.copy(eras = eras, dated = t?.size ?: 0, datedMain = t?.main?.count { it } ?: 0)
        if (listing) {
            if (facet != null) room(facet, t)
            return
        }
        if (t != null) findingOf(t)?.let { f -> state.value = state.value?.copy(finding = f) }
        val shelf = withContext(app.granger.screens.ioDispatcher) { runCatching { inYourBooks() }.getOrDefault(emptyList()) }
        state.value = state.value?.copy(books = shelf)
    }

    /**
     * A room's finding (its oldest dated topic, its busiest century), its plates through time (each era's best-known dated
     * topic, one with a picture when the era's best few have any), and where its topics also hang: the other rooms that
     * share the most of them, each with its best-known shared few. Each part shows as it is ready.
     */
    private suspend fun room(facet: app.granger.core.library.Facet, t: SubjectTime?) {
        fun update(change: (RoomUi) -> RoomUi) { state.value = state.value?.let { ui -> ui.copy(room = ui.room?.let(change)) } }
        if (t != null && t.size > 0) {
            val finding = findingOf(t)
            update { it.copy(finding = finding) }
            // Each era's best few, then its plate: the best known of them with a picture, else the best known.
            val eras = withContext(app.granger.screens.ioDispatcher) {
                Era.entries.mapNotNull { era ->
                    val best = (0 until t.size).filter { t.year[it] in era.from..era.to }.sortedWith(compareByDescending<Int> { t.rank[it] }.thenBy { t.qid[it] }).take(STORY_TRIES)
                    sources.times().marksOf(t, best).map { it.toExplore() }.takeIf { it.isNotEmpty() }
                }
            }
            val story = withContext(app.granger.screens.ioDispatcher) { eras.side { marks -> marks.asSequence().map { withPicture(it) }.firstOrNull { it.picture != null } ?: marks.first() } }
            update { it.copy(story = story, storyPending = false) }
        } else update { it.copy(storyPending = false) }
        val overlaps = withContext(app.granger.screens.ioDispatcher) {
            val qids = sources.qids()
            val mine = qids.facetSet(facet.key)
            val top = Subject.entries.flatMap { s -> qids.facets(s.id).filter { it.key != facet.key }.map { s to it } }
                .map { (s, f) -> Triple(s, f, mine.shared(qids.facetSet(f.key))) }.filter { it.third >= OVERLAP_MIN }
                .sortedWith(compareByDescending<Triple<Subject, app.granger.core.library.Facet, Int>> { it.third }.thenBy { it.second.key }).take(OVERLAPS)
            if (top.isEmpty()) return@withContext emptyList()
            val ranked = qids.topInFacet(facet.key, mine.size)
            top.map { (s, f, n) ->
                val theirs = qids.facetSet(f.key)
                RoomOverlap(s, f, n, ranked.asSequence().filter { it.qid in theirs }.take(OVERLAP_EXAMPLES).map { titleOf(it.path) }.toList())
            }
        }
        update { it.copy(overlaps = overlaps) }
    }

    /** What [t]'s dates say: the oldest dated topic and the busiest century; null with nothing dated. */
    private suspend fun findingOf(t: SubjectTime): RoomFinding? = if (t.size == 0) null else withContext(app.granger.screens.ioDispatcher) {
        val oldest = (0 until t.size).minWithOrNull(compareBy<Int> { t.year[it] }.thenByDescending { t.rank[it] })
        val busiest = busiestCentury(t.year)
        RoomFinding(oldest?.let { sources.times().marksOf(t, listOf(it)).firstOrNull()?.toExplore() }, t.size, busiest?.first, busiest?.second ?: 0)
    }

    /**
     * The page's head and its sub-categories: the subject's own article's look (as the landing's door), then each facet
     * with its count and best-known few, then their pictures, one by one.
     */
    private suspend fun page() {
        val started = app.granger.Perf.now()
        val own = withContext(app.granger.screens.ioDispatcher) {
            val qids = sources.qids()
            subject.namesakes.asSequence().mapNotNull { q -> qids.articlesOf(q).firstOrNull() }
                .mapNotNull { a -> runCatching { sources.look(a.packId, a.path) }.getOrNull()?.let { a to it } }.firstOrNull { it.second.picture != null }
        }
        // The opening plates: the subject's own picture leads, then its best known that have a photograph.
        val lead = own?.let { (a, look) ->
            a.toExplore().copy(picture = look.picture, ratio = look.picture?.let(::ratioOf) ?: 0f, whole = ThumbnailNames.isWhole(look.picture))
        }
        // The plates show together, once: a group that grew from one plate to three would change shape under the reader.
        state.value = state.value?.copy(hero = own?.second)
        val opening = withContext(app.granger.screens.ioDispatcher) {
            val qids = sources.qids()
            if (!qids.hasSubjects) return@withContext listOfNotNull(lead)
            // The best known are read together; the first few with a photograph stand.
            listOfNotNull(lead) + qids.topIn(subject.id, OPENING_TRIES + subject.namesakes.size, mainOnly = true)
                .filter { it.qid !in subject.namesakes }.side { withPicture(it.toExplore()) }.filter { it.picture != null && !it.whole }
                .take(OPENING - (if (lead != null) 1 else 0))
        }
        state.value = state.value?.copy(opening = opening, openingPending = false)
        app.granger.Perf.mark("subject.opening", started)
        var cards = withContext(app.granger.screens.ioDispatcher) {
            val qids = sources.qids()
            if (!qids.hasFacets) return@withContext emptyList()
            val counts = qids.facetCounts(subject.id)
            qids.facets(subject.id).mapNotNull { f ->
                val n = counts[f.id] ?: 0
                if (n == 0) null else FacetCard(f, n, qids.topInFacet(f.key, FACET_EXAMPLES).map { it.toExplore() }, description = sources.roomDescription(f.key, language))
            }
        }
        state.value = state.value?.copy(facets = cards, roomsPending = false)
        app.granger.Perf.mark("subject.rooms", started)
        // Read ahead, side by side: the rooms take their pictures one after another below (each avoids the ones before
        // it), and find these already read.
        withContext(app.granger.screens.ioDispatcher) { cards.flatMap { it.examples }.distinctBy { it.packId to it.path }.side { a -> runCatching { sources.look(a.packId, a.path) } } }
        // Rooms overlap (Napoleon leads Rulers and Wars): each card's picture comes from an article no earlier card, and
        // no opening plate, used. Then the room's sentence, from its dates, as its own page says it.
        val used = opening.mapTo(HashSet()) { it.path }
        for (i in cards.indices) {
            val picture = withContext(app.granger.screens.ioDispatcher) {
                val looks = cards[i].examples.asSequence().mapNotNull { a ->
                    runCatching { sources.look(a.packId, a.path) }.getOrNull()?.picture?.let { a.path to it }
                }
                (looks.firstOrNull { it.first !in used } ?: looks.firstOrNull())?.also { used += it.first }?.second
            }
            cards = cards.toMutableList().also { it[i] = it[i].copy(picture = picture) }
            state.value = state.value?.copy(facets = cards)
            val finding = withContext(app.granger.screens.ioDispatcher) {
                val times = app.granger.Perf.time("told.times") { sources.times() }
                val q = app.granger.Perf.time("told.qids") { sources.qids() }
                val set = app.granger.Perf.time("told.set") { q.facetSet(cards[i].facet.key) }
                if (times.isEmpty) null else app.granger.Perf.time("told.dated") { times.datedIn(set) }
            }?.let { app.granger.Perf.time("told.finding") { findingOf(it) } }
            cards = cards.toMutableList().also { it[i] = it[i].copy(finding = finding, told = true) }
            state.value = state.value?.copy(facets = cards)
        }
        app.granger.Perf.mark("subject.told", started)
    }

    /**
     * A listing's next articles, shown a few at a time as their pictures are read (with each picture's shape), so the
     * mosaic's first tiles come quickly and no tile changes shape once shown.
     */
    private suspend fun appendWithPictures(next: List<ExploreArticle>, total: Int, replace: Boolean = false) {
        if (replace) state.value = state.value?.copy(articles = emptyList())
        for (chunk in next.chunked(PICTURE_CHUNK)) {
            val shown = withContext(app.granger.screens.ioDispatcher) { chunk.side { a -> withPicture(a) } }
            val all = (state.value?.articles ?: emptyList()) + shown
            state.value = state.value?.copy(articles = all, loading = false)
        }
        val now = state.value ?: return
        state.value = now.copy(more = next.isNotEmpty() && now.articles.size < total, loading = false)
    }

    /** [a] with its picture and that picture's shape. Blocking. */
    private fun withPicture(a: ExploreArticle): ExploreArticle {
        val p = runCatching { sources.picture(a.packId, a.path) }.getOrNull() ?: return a
        return a.copy(picture = p, ratio = ratioOf(p), whole = ThumbnailNames.isWhole(p))
    }

    /** The shelf's books whose work has this subject, best-known work first. */
    private suspend fun inYourBooks(): List<ExploreBook> {
        val qids = sources.qids()
        if (!qids.hasSubjects) return emptyList()
        return books().filter { subject.id in qids.subjectsOf(it.work) }
            .sortedWith(compareByDescending<ExploreBook> { b -> qids.articlesOf(b.work).maxOfOrNull { it.rank } ?: 0 }.thenBy { it.title.lowercase() })
    }

    /** Shows [era] (null: every era); choosing the one showing goes back to every era. An era from A–Z: most linked. */
    fun choose(era: Era?) {
        val now = state.value ?: return
        val next = if (era == now.era) null else era
        show(next, if (next != null && now.sort == SubjectSort.AZ) SubjectSort.LINKED else now.sort)
    }

    /** In A–Z order: the list from [letter]'s first title on (the rail). */
    fun jump(letter: String) {
        val now = state.value ?: return
        val all = az ?: return
        if (now.sort != SubjectSort.AZ) return
        val at = all.indexOfFirst { railLetter(it.title) == letter }.takeIf { it >= 0 } ?: return
        azStart = at
        state.value = now.copy(letter = letter, loading = true)
        viewing?.cancel()
        viewing = viewModelScope.launch {
            val page = all.drop(at).take(pageSize)
            if (listing) appendWithPictures(page, all.size - at, replace = true)
            else state.value = state.value?.copy(articles = page, more = page.size < all.size - at, loading = false)
        }
    }

    fun sort(by: SubjectSort) {
        val now = state.value ?: return
        if (by != now.sort) show(now.era, by)
    }

    private fun show(era: Era?, sort: SubjectSort) {
        val now = state.value ?: return
        val toAz = sort == SubjectSort.AZ
        state.value = now.copy(era = if (toAz) null else era, sort = sort, loading = true, letters = if (toAz) now.letters else emptyList(), letter = null)
        viewing?.cancel()
        viewing = viewModelScope.launch {
            if (toAz) {
                val all = az ?: withContext(app.granger.screens.ioDispatcher) {
                    val qids = sources.qids()
                    val every = if (now.facet != null) qids.topInFacet(now.facet.key, now.count) else qids.topIn(subject.id, now.count)
                    alphabetical(every.map { it.toExplore() }, language)
                }.also { az = it }
                order = null
                azStart = 0
                state.value = state.value?.copy(letters = all.map { railLetter(it.title) }.distinct())
                val page = all.take(pageSize)
                if (listing) appendWithPictures(page, all.size, replace = true)
                else state.value = state.value?.copy(articles = page, more = page.size < all.size, loading = false)
                return@launch
            }
            val (list, page, total) = withContext(app.granger.screens.ioDispatcher) {
                val t = time
                if (era == null && sort == SubjectSort.LINKED || t == null) {
                    Triple(null, linked(now.facet, 0), now.count)
                } else {
                    val o = t.order(era?.from ?: Long.MIN_VALUE, era?.to ?: Long.MAX_VALUE, sort == SubjectSort.OLDEST)
                    Triple(o, sources.times().marksOf(t, o.take(pageSize)).map { it.toExplore() }, o.size)
                }
            }
            order = list
            if (listing) appendWithPictures(page, total, replace = true)
            else state.value = state.value?.copy(articles = page, more = page.size < total, loading = false)
        }
    }

    /** The next page of articles. */
    fun more() {
        val now = state.value ?: return
        if (!now.more || now.loading) return
        state.value = now.copy(loading = true)
        viewing = viewModelScope.launch {
            val o = order
            val t = time
            val all = az.takeIf { now.sort == SubjectSort.AZ }
            val next = withContext(app.granger.screens.ioDispatcher) {
                when {
                    all != null -> all.drop(azStart + now.articles.size).take(pageSize)
                    o != null && t != null -> sources.times().marksOf(t, o.drop(now.articles.size).take(pageSize)).map { it.toExplore() }
                    else -> linked(now.facet, now.articles.size)
                }
            }
            if (all != null) {
                if (listing) appendWithPictures(next, all.size - azStart)
                else {
                    val shown = now.articles + next
                    state.value = state.value?.copy(articles = shown, more = next.isNotEmpty() && shown.size < all.size - azStart, loading = false)
                }
                return@launch
            }
            if (listing) appendWithPictures(next, o?.size ?: now.count)
            else {
                val all = now.articles + next
                state.value = state.value?.copy(articles = all, more = next.isNotEmpty() && all.size < (o?.size ?: now.count), loading = false)
            }
        }
    }

    /** A well-known article of this subject, at random, for Wander to start at; null without any. */
    suspend fun wanderStart(random: Random = Random.Default): ExploreArticle? = withContext(app.granger.screens.ioDispatcher) {
        val facet = state.value?.facet
        val pool = if (facet != null) sources.qids().topInFacet(facet.key, WANDER_POOL) else sources.qids().topIn(subject.id, WANDER_POOL, mainOnly = true)
        pool.takeIf { it.isNotEmpty() }?.random(random)?.toExplore()
    }

    /** The article's picture, cached; null when it has none. Blocking. */
    fun picture(a: ExploreArticle): String? = runCatching { sources.picture(a.packId, a.path) }.getOrNull()

    companion object {
        const val PAGE = 60

        /** A room's page: eight rows of plates. */
        const val ROOM_PAGE = 24

        /** A room's plate for an era is the first with a picture among this many of the era's best known. */
        const val STORY_TRIES = 4

        /** Where a room's topics also hang: this many other rooms, each sharing at least [OVERLAP_MIN], with a few named. */
        const val OVERLAPS = 4
        const val OVERLAP_MIN = 3
        const val OVERLAP_EXAMPLES = 3

        /** Dated articles a subject needs before it offers Quiz (the games need a spread of years to deal from). */
        const val QUIZ_MIN = 40

        /** Wander starts among the subject's best-known articles, not its stubs. */
        const val WANDER_POOL = 300

        /** A subject page opens on this many plates, the others found among this many of its best known. */
        const val OPENING = 3
        const val OPENING_TRIES = 12

        /** Each sub-category's best-known articles on the subject page. */
        const val FACET_EXAMPLES = 4

        /** A listing shows its articles this many at a time as their pictures are read. */
        const val PICTURE_CHUNK = 12
    }
}
