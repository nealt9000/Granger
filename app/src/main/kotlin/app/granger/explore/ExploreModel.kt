package app.granger.explore

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.granger.R
import app.granger.core.library.Pack
import app.granger.core.library.QidArticle
import app.granger.core.library.QidIndexes
import app.granger.core.library.SubjectTime
import app.granger.core.library.TimeIndexes
import app.granger.core.library.TimeMark
import kotlin.random.Random
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The subjects (tools/subjects/subjects.json; ids locked in v1, 2026-11-16), in the order the doors take when two hold
 * as many articles. Every article in a Wikipedia pack carries up to three of them in its QID index (Plan EX Task 1).
 */
enum class Subject(
    val id: String,
    @StringRes val label: Int,
    /**
     * The subject's own articles (subjects.json `namesakes`): their lead picture and opening sentence stand for the whole
     * subject on the landing (Neal, 2026-11-16: pictures chosen to represent the whole subject), first that the phone has.
     */
    val namesakes: List<Long> = emptyList(),
) {
    HISTORY("history", R.string.explore_subject_history, listOf(309L)),
    SOCIETY("society", R.string.explore_subject_society, listOf(8425L, 7163L)),
    SCIENCE("science", R.string.explore_subject_science, listOf(336L, 395L)),
    PLACES("places", R.string.explore_subject_places, listOf(1071L)),
    NATURE("nature", R.string.explore_subject_nature, listOf(7860L)),
    HEALTH("health", R.string.explore_subject_health, listOf(11190L, 12147L)),
    TECHNOLOGY("technology", R.string.explore_subject_technology, listOf(11016L)),
    BUSINESS("business", R.string.explore_subject_business, listOf(8134L, 159810L)),
    ART("art", R.string.explore_subject_art, listOf(735L, 12271L)),
    LITERATURE("literature", R.string.explore_subject_literature, listOf(8242L)),
    MUSIC("music", R.string.explore_subject_music, listOf(638L)),
    FILM("film", R.string.explore_subject_film, listOf(11424L, 289L)),
    SPORTS("sports", R.string.explore_subject_sports, listOf(349L)),
    GAMES("games", R.string.explore_subject_games, listOf(11410L)),
    RELIGION("religion", R.string.explore_subject_religion, listOf(9174L, 5891L)),
    LANGUAGES("languages", R.string.explore_subject_languages, listOf(315L)),
    FOOD("food", R.string.explore_subject_food, listOf(2095L, 11451L)),
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
    @StringRes val label: Int,
    @StringRes val span: Int,
    /** The era's own articles (Middle Ages, 19th century…), whose picture stands for it, first that the phone has. */
    val articles: List<Long> = emptyList(),
) {
    PREHISTORY(Long.MIN_VALUE / 2, -3001, R.string.explore_era_prehistory, R.string.explore_era_prehistory_span, listOf(11756L)),
    ANCIENT(-3000, 499, R.string.explore_era_ancient, R.string.explore_era_ancient_span, listOf(41493L)),
    MIDDLE_AGES(500, 1499, R.string.explore_era_middle_ages, R.string.explore_era_middle_ages_span, listOf(12554L)),
    EARLY_MODERN(1500, 1799, R.string.explore_era_early_modern, R.string.explore_era_early_modern_span, listOf(5308718L)),
    C19(1800, 1899, R.string.explore_era_c19, R.string.explore_era_c19_span, listOf(6955L)),
    WARS(1900, 1945, R.string.explore_era_wars, R.string.explore_era_wars_span, listOf(361L, 362L)),
    RECENT(1946, Long.MAX_VALUE / 2, R.string.explore_era_recent, R.string.explore_era_recent_span, listOf(186075L)),
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
data class ExploreArticle(val packId: String, val path: String, val title: String, val picture: String? = null, val mark: TimeMark? = null)

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
) {
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
)

/** An article as a feature shows it: its lead picture, larger than a thumbnail, and its opening sentence. */
data class ArticleLook(val picture: String?, val sentence: String?)

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
fun featuredIndex(day: Long, doors: Int, pool: Int = 8): Int = if (doors <= 0) -1 else Math.floorMod(day, minOf(doors, pool).toLong()).toInt()

/** Explore's landing, built off the main thread whenever the packs change; null until the first build. */
class ExploreViewModel(private val sources: ExploreSources) : ViewModel() {
    private val state = MutableStateFlow<ExploreUi?>(null)
    val ui: StateFlow<ExploreUi?> = state.asStateFlow()

    private var packs: List<Pair<Pack, String?>>? = null
    private var collections: Map<String, List<ExploreCollection>> = emptyMap()

    init {
        viewModelScope.launch { runCatching { sources.load() } }
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

    /** Builds again on coming back (a QID or time index may have arrived since); the indexes reopen only when they changed. */
    fun refresh() {
        val now = packs ?: return
        viewModelScope.launch { rebuild(now) }
    }

    private suspend fun rebuild(list: List<Pair<Pack, String?>>) {
        val built = withContext(Dispatchers.IO) { build(list) }
        // Unchanged since last time (pictures aside): keep what's on screen, pictures and all.
        if (state.value?.let { shown -> withoutPictures(shown) == built } == true) return
        state.value = built
        // Pictures second: the doors and eras show at once, then fill in one by one, the page's top first (all at once
        // took 10–14 s on a cold Pixel).
        withContext(Dispatchers.IO) { withPictures(built) { ui -> state.value = ui } }
    }

    private suspend fun build(packs: List<Pair<Pack, String?>>): ExploreUi {
        val qids = sources.qids()
        val counts = if (qids.hasSubjects) qids.subjectCounts(Subject.entries.map { it.id }) else emptyMap()
        val articles = if (qids.hasSubjects) qids.itemCount() else 0
        val others = packs.filterNot { sources.isWikipedia(it.first) }
        // The subject's own article (History in History) stands for the door already; examples are what it holds.
        val doors = subjectDoors(counts, { s ->
            qids.topIn(s.id, EXAMPLES + s.namesakes.size, mainOnly = true).filter { it.qid !in s.namesakes }.take(EXAMPLES).map { it.toExplore() }
        }, others, collections)
        val times = sources.times()
        val eras = if (times.isEmpty) emptyList() else Era.entries.map { era ->
            EraRow(era, times.countActive(era.from, era.to), eraExamples(times.topActive(era.from, era.to, ERA_POOL), qids::subjectsOf))
        }.filter { it.count > 0 }
        return ExploreUi(articles, doors, eras, packs.map { it.first.title }.sortedBy { it.lowercase() })
    }

    private fun withoutPictures(ui: ExploreUi) = ui.copy(doors = ui.doors.map { it.copy(picture = null, lead = null) }, eras = ui.eras.map { it.copy(picture = null, lead = null) })

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
        var ui = start
        // The landing's order: today's subject, the blocks, then Through time, then the rest of the subjects.
        val featured = featuredIndex(java.time.LocalDate.now().toEpochDay(), ui.doors.size)
        val blocks = 6    // two blocks of three (ExploreScreen BLOCK)
        val doorOrder = (listOf(featured) + ui.doors.indices.filter { it != featured }).filter { it >= 0 }
        fun door(i: Int) {
            val d = ui.doors[i]
            val l = own(d.subject.namesakes)
            ui = ui.copy(doors = ui.doors.toMutableList().also { it[i] = d.copy(picture = l?.picture ?: first(d.examples), lead = l?.sentence?.let(::firstSentence)) })
            shown(ui)
        }
        doorOrder.take(1 + blocks).forEach(::door)
        for (i in ui.eras.indices) {
            val e = ui.eras[i]
            val l = own(e.era.articles)
            ui = ui.copy(eras = ui.eras.toMutableList().also { it[i] = e.copy(picture = l?.picture ?: first(e.examples), lead = l?.sentence?.let(::firstSentence)) })
            shown(ui)
        }
        doorOrder.drop(1 + blocks).forEach(::door)
    }

    /**
     * The library map's ring inside [centre] (Plan EX Task 5): a subject's or an era's best-known articles, as its examples
     * are chosen, with their pictures.
     */
    suspend fun inside(centre: MapCentre): List<ExploreArticle> = withContext(Dispatchers.IO) {
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
    }
}

/** A book on the shelf with a Wikidata work (from its links file): In your books on a subject page. */
data class ExploreBook(val bookId: String, val title: String, val cover: String?, val work: Long)

/** The order a subject's articles take: most linked (main subject first), or oldest first (dated ones only). */
enum class SubjectSort { LINKED, OLDEST }

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
) {
    /** Enough dated main-subject articles for a run of the time games. */
    val canQuiz: Boolean get() = datedMain >= SubjectViewModel.QUIZ_MIN
    val canWander: Boolean get() = count > 0
}

/** What a subject page reads, beyond the landing's sources: the books on the shelf that have a Wikidata work. */
class SubjectViewModel(
    private val subject: Subject,
    private val sources: ExploreSources,
    private val books: suspend () -> List<ExploreBook> = { emptyList() },
) : ViewModel() {
    private val state = MutableStateFlow<SubjectUi?>(null)
    val ui: StateFlow<SubjectUi?> = state.asStateFlow()

    /** The subject's dated items, once read; null before (the eras and the oldest-first order need it). */
    private var time: SubjectTime? = null
    /** The current view's order when it comes from [time] (an era chosen, or oldest first); null for most linked overall. */
    private var order: List<Int>? = null
    private var viewing: Job? = null

    init {
        viewModelScope.launch {
            kotlinx.coroutines.flow.combine(sources.packs, sources.collections) { p, c -> p to c }.collect { (packs, c) ->
                val shelved = c[subject.id].orEmpty()
                val others = packs.filterNot { sources.isWikipedia(it.first) }
                    .filter { Subject.ofDomain(it.second) == subject }.map { it.first }.sortedBy { it.title.lowercase() }
                val first = withContext(Dispatchers.IO) {
                    val qids = sources.qids()
                    val count = if (qids.hasSubjects) qids.subjectCounts(listOf(subject.id))[subject.id] ?: 0 else 0
                    val page = qids.topIn(subject.id, PAGE).map { it.toExplore() }
                    SubjectUi(subject, count, page, others, more = page.size < count, collections = shelved)
                }
                val was = state.value
                state.value = was?.copy(count = first.count, packs = first.packs, collections = shelved) ?: first
                if (was == null) launch { details() }
            }
        }
    }

    /** The eras (one pass over the time indexes) and In your books, after the first page shows. */
    private suspend fun details() {
        val (t, eras) = withContext(Dispatchers.IO) {
            val qids = sources.qids()
            val times = sources.times()
            if (!qids.hasSubjects || times.isEmpty) return@withContext null to emptyList()
            val t = times.datedIn(qids.subjectSet(subject.id))
            t to Era.entries.map { it to t.count(it.from, it.to) }.filter { it.second > 0 }
        }
        time = t
        state.value = state.value?.copy(eras = eras, dated = t?.size ?: 0, datedMain = t?.main?.count { it } ?: 0)
        val shelf = withContext(Dispatchers.IO) { runCatching { inYourBooks() }.getOrDefault(emptyList()) }
        state.value = state.value?.copy(books = shelf)
    }

    /** The shelf's books whose work has this subject, best-known work first. */
    private suspend fun inYourBooks(): List<ExploreBook> {
        val qids = sources.qids()
        if (!qids.hasSubjects) return emptyList()
        return books().filter { subject.id in qids.subjectsOf(it.work) }
            .sortedWith(compareByDescending<ExploreBook> { b -> qids.articlesOf(b.work).maxOfOrNull { it.rank } ?: 0 }.thenBy { it.title.lowercase() })
    }

    /** Shows [era] (null: every era); choosing the one showing goes back to every era. */
    fun choose(era: Era?) {
        val now = state.value ?: return
        show(if (era == now.era) null else era, now.sort)
    }

    fun sort(by: SubjectSort) {
        val now = state.value ?: return
        if (by != now.sort) show(now.era, by)
    }

    private fun show(era: Era?, sort: SubjectSort) {
        val now = state.value ?: return
        state.value = now.copy(era = era, sort = sort, loading = true)
        viewing?.cancel()
        viewing = viewModelScope.launch {
            val (list, page, total) = withContext(Dispatchers.IO) {
                val t = time
                if (era == null && sort == SubjectSort.LINKED || t == null) {
                    Triple(null, sources.qids().topIn(subject.id, PAGE).map { it.toExplore() }, now.count)
                } else {
                    val o = t.order(era?.from ?: Long.MIN_VALUE, era?.to ?: Long.MAX_VALUE, sort == SubjectSort.OLDEST)
                    Triple(o, sources.times().marksOf(t, o.take(PAGE)).map { it.toExplore() }, o.size)
                }
            }
            order = list
            state.value = state.value?.copy(articles = page, more = page.size < total, loading = false)
        }
    }

    /** The next page of articles. */
    fun more() {
        val now = state.value ?: return
        if (!now.more || now.loading) return
        viewModelScope.launch {
            val o = order
            val t = time
            val next = withContext(Dispatchers.IO) {
                if (o != null && t != null) sources.times().marksOf(t, o.drop(now.articles.size).take(PAGE)).map { it.toExplore() }
                else sources.qids().topIn(subject.id, PAGE, offset = now.articles.size).map { it.toExplore() }
            }
            val all = now.articles + next
            state.value = state.value?.copy(articles = all, more = next.isNotEmpty() && all.size < (o?.size ?: now.count))
        }
    }

    /** A well-known article of this subject, at random, for Wander to start at; null without any. */
    suspend fun wanderStart(random: Random = Random.Default): ExploreArticle? = withContext(Dispatchers.IO) {
        sources.qids().topIn(subject.id, WANDER_POOL, mainOnly = true).takeIf { it.isNotEmpty() }?.random(random)?.toExplore()
    }

    /** The article's picture, cached; null when it has none. Blocking. */
    fun picture(a: ExploreArticle): String? = runCatching { sources.picture(a.packId, a.path) }.getOrNull()

    companion object {
        const val PAGE = 60

        /** Dated articles a subject needs before it offers Quiz (the games need a spread of years to deal from). */
        const val QUIZ_MIN = 40

        /** Wander starts among the subject's best-known articles, not its stubs. */
        const val WANDER_POOL = 300
    }
}
