package app.granger.explore

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.granger.R
import app.granger.core.library.Pack
import app.granger.core.library.QidArticle
import app.granger.core.library.QidIndexes
import app.granger.core.library.TimeIndexes
import app.granger.core.library.TimeMark
import kotlinx.coroutines.Dispatchers
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
enum class Subject(val id: String, @StringRes val label: Int) {
    HISTORY("history", R.string.explore_subject_history),
    SOCIETY("society", R.string.explore_subject_society),
    SCIENCE("science", R.string.explore_subject_science),
    PLACES("places", R.string.explore_subject_places),
    NATURE("nature", R.string.explore_subject_nature),
    HEALTH("health", R.string.explore_subject_health),
    TECHNOLOGY("technology", R.string.explore_subject_technology),
    BUSINESS("business", R.string.explore_subject_business),
    ART("art", R.string.explore_subject_art),
    LITERATURE("literature", R.string.explore_subject_literature),
    MUSIC("music", R.string.explore_subject_music),
    FILM("film", R.string.explore_subject_film),
    SPORTS("sports", R.string.explore_subject_sports),
    GAMES("games", R.string.explore_subject_games),
    RELIGION("religion", R.string.explore_subject_religion),
    LANGUAGES("languages", R.string.explore_subject_languages),
    FOOD("food", R.string.explore_subject_food),
    ;

    companion object {
        fun of(id: String): Subject? = entries.firstOrNull { it.id == id }

        /**
         * A non-Wikipedia pack's subject from its catalog knowledge area (Neal, 2026-11-16: packs become subject doors by
         * their domain). For now: once KB2's catalog `subjects` field is merged, a pack's own subjects replace this.
         * General reference and anything unknown join no door; they stay under Your sources.
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
 * Through time's eras (the mockup's B timeline), by start year (astronomical: 1 BC is 0): each counts the dated items
 * starting in it. [to] is inclusive; the last runs to the far future.
 */
enum class Era(val from: Int, val to: Int, @StringRes val label: Int, @StringRes val span: Int) {
    PREHISTORY(Int.MIN_VALUE / 2, -3001, R.string.explore_era_prehistory, R.string.explore_era_prehistory_span),
    ANCIENT(-3000, 499, R.string.explore_era_ancient, R.string.explore_era_ancient_span),
    MIDDLE_AGES(500, 1499, R.string.explore_era_middle_ages, R.string.explore_era_middle_ages_span),
    EARLY_MODERN(1500, 1799, R.string.explore_era_early_modern, R.string.explore_era_early_modern_span),
    C19(1800, 1899, R.string.explore_era_c19, R.string.explore_era_c19_span),
    WARS(1900, 1945, R.string.explore_era_wars, R.string.explore_era_wars_span),
    RECENT(1946, Int.MAX_VALUE / 2, R.string.explore_era_recent, R.string.explore_era_recent_span),
}

/** An article Explore shows: where to open it, its title and (when loaded) its picture. */
data class ExploreArticle(val packId: String, val path: String, val title: String, val picture: String? = null)

/** A subject's door: how many articles it holds across the packs, its best-known three, and the packs on it. */
data class SubjectDoor(val subject: Subject, val count: Int, val examples: List<ExploreArticle>, val packs: List<Pack>, val picture: String? = null)

/** An era's row: how many dated articles start in it, its best-known three, and where the Timeline opens for it. */
data class EraRow(val era: Era, val count: Int, val examples: List<ExploreArticle>, val picture: String? = null)

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
fun TimeMark.toExplore() = ExploreArticle(packId, path, title)

/**
 * The doors (pure, for tests): each subject with any article or pack, the most articles first ([Subject] order breaking
 * ties), its three best-known articles, and the non-Wikipedia [packs] its catalog domain puts on it.
 */
fun subjectDoors(counts: Map<String, Int>, examples: (Subject) -> List<ExploreArticle>, packs: List<Pair<Pack, String?>>): List<SubjectDoor> {
    val bySubject = packs.mapNotNull { (pack, domain) -> Subject.ofDomain(domain)?.let { it to pack } }.groupBy({ it.first }, { it.second })
    return Subject.entries
        .map { s -> s to (counts[s.id] ?: 0) }
        .filter { (s, n) -> n > 0 || bySubject[s].orEmpty().isNotEmpty() }
        .sortedWith(compareByDescending<Pair<Subject, Int>> { it.second }.thenBy { it.first.ordinal })
        .map { (s, n) -> SubjectDoor(s, n, if (n > 0) examples(s) else emptyList(), bySubject[s].orEmpty().sortedBy { it.title.lowercase() }) }
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
)

/** Explore's landing, built off the main thread whenever the packs change; null until the first build. */
class ExploreViewModel(private val sources: ExploreSources) : ViewModel() {
    private val state = MutableStateFlow<ExploreUi?>(null)
    val ui: StateFlow<ExploreUi?> = state.asStateFlow()

    init {
        viewModelScope.launch {
            sources.packs.map { list -> list.sortedBy { it.first.id } }.distinctUntilChanged().collect { packs ->
                state.value = withContext(Dispatchers.IO) { build(packs) }
                // Pictures second: the doors and eras show at once, then fill in.
                state.value = state.value?.let { withContext(Dispatchers.IO) { withPictures(it) } }
            }
        }
    }

    private suspend fun build(packs: List<Pair<Pack, String?>>): ExploreUi {
        val qids = sources.qids()
        val counts = if (qids.hasSubjects) qids.subjectCounts(Subject.entries.map { it.id }) else emptyMap()
        val articles = if (qids.hasSubjects) qids.itemCount() else 0
        val others = packs.filterNot { sources.isWikipedia(it.first) }
        val doors = subjectDoors(counts, { s -> qids.topIn(s.id, EXAMPLES).map { it.toExplore() } }, others)
        val times = sources.times()
        val eras = if (times.isEmpty) emptyList() else Era.entries.map { era ->
            EraRow(era, times.countStarting(era.from, era.to), times.top(era.from, era.to, emptySet(), EXAMPLES).map { it.toExplore() })
        }.filter { it.count > 0 }
        return ExploreUi(articles, doors, eras, packs.map { it.first.title }.sortedBy { it.lowercase() })
    }

    /** Each door and era gets the first picture among its examples. */
    private fun withPictures(ui: ExploreUi): ExploreUi {
        fun first(examples: List<ExploreArticle>) = examples.firstNotNullOfOrNull { a -> runCatching { sources.picture(a.packId, a.path) }.getOrNull() }
        return ui.copy(
            doors = ui.doors.map { it.copy(picture = first(it.examples)) },
            eras = ui.eras.map { it.copy(picture = first(it.examples)) },
        )
    }

    companion object {
        /** Examples per door and per era (the mockup's three). */
        const val EXAMPLES = 3
    }
}

/** One subject opened (Plan EX Task 3 builds it out): its articles most-linked first, a page at a time, and its packs. */
data class SubjectUi(val subject: Subject, val count: Int, val articles: List<ExploreArticle>, val packs: List<Pack>, val more: Boolean)

class SubjectViewModel(private val subject: Subject, private val sources: ExploreSources) : ViewModel() {
    private val state = MutableStateFlow<SubjectUi?>(null)
    val ui: StateFlow<SubjectUi?> = state.asStateFlow()

    init {
        viewModelScope.launch {
            sources.packs.collect { packs ->
                val others = packs.filterNot { sources.isWikipedia(it.first) }
                    .filter { Subject.ofDomain(it.second) == subject }.map { it.first }.sortedBy { it.title.lowercase() }
                state.value = withContext(Dispatchers.IO) {
                    val qids = sources.qids()
                    val count = if (qids.hasSubjects) qids.subjectCounts(listOf(subject.id))[subject.id] ?: 0 else 0
                    val page = qids.topIn(subject.id, PAGE).map { it.toExplore() }
                    SubjectUi(subject, count, page, others, more = page.size < count)
                }
            }
        }
    }

    /** The next page of articles. */
    fun more() {
        val now = state.value ?: return
        if (!now.more) return
        viewModelScope.launch {
            val next = withContext(Dispatchers.IO) { sources.qids().topIn(subject.id, PAGE, offset = now.articles.size).map { it.toExplore() } }
            val all = now.articles + next
            state.value = now.copy(articles = all, more = next.isNotEmpty() && all.size < now.count)
        }
    }

    companion object {
        const val PAGE = 60
    }
}
