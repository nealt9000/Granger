package app.granger.topic

import app.granger.core.library.TimeMark
import app.granger.core.library.TopicParts
import app.granger.side

/**
 * A broad topic's page (Plan EX round 2, D5: a spread), built by rule from the QID index's parts (tools/topics/parts.py)
 * and what the phone already holds (articles, pictures, the time index).
 */
data class BroadTopic(
    /** How many of the pack's articles are under the topic. */
    val under: Int,
    val groups: List<BroadGroup>,
    /** Its kinds by family: the largest groups, then (unnamed) the kinds outside them. */
    val families: List<BroadFamily>,
    val story: BroadStory?,
    val people: List<BroadPerson>,
    val chapters: List<BroadChapter>,
    /** Figures for the opening plates, from different parts of the article; the page takes two that aren't its lead picture. */
    val figures: List<BroadFigure> = emptyList(),
    /** The diagram plate: the first drawing among the figures outside the story. */
    val diagram: BroadFigure? = null,
    /** How many of the pack's articles link to the topic. */
    val inlinks: Int = 0,
    /** Where it hangs, and its neighbours there; null until read, or when it is in no subject. */
    val place: BroadPlace? = null,
    /** Side by side: this topic, then the room's two best-known other broad topics; empty when the room has no other. */
    val beside: List<BroadColumn> = emptyList(),
) {
    val hasKinds: Boolean get() = families.any { it.all.isNotEmpty() }
}

data class BroadGroup(val name: String, val count: Int)

/**
 * A family of kinds: its [name] (null for the kinds outside the named families), every kind of it on the phone ([all],
 * by title only), and the first few as plates with their pictures.
 */
data class BroadFamily(val name: String?, val plates: List<TopicArticle>, val all: List<TopicArticle>)

/** One of the article's figures: its picture (a cached file), its caption, and its section's title (null in the lead). */
data class BroadFigure(val picture: String, val caption: String, val chapter: String? = null)

/**
 * Where a broad topic hangs: its [subject] (an id), the first of the subject's rooms that holds it, and in that room,
 * best known first, the topics [before] and [after] it and where it stands ([position] of [total]).
 */
data class BroadPlace(
    val subject: String,
    val room: app.granger.core.library.Facet? = null,
    val before: TopicArticle? = null,
    val after: TopicArticle? = null,
    val position: Int = 0,
    val total: Int = 0,
)

/** One column of the side by side: a broad topic and what its page holds. */
data class BroadColumn(val article: TopicArticle, val kinds: Int, val families: List<String>, val people: List<String>, val chapters: Int, val inlinks: Int)

/** The topic's own story: the section with the most dated pictures and links, as a timeline. */
data class BroadStory(val title: String, val entries: List<StoryEntry>)

/** One step of the story: its year, the caption or linked title, its picture, and the article it opens (if any). */
data class StoryEntry(val year: Long, val text: String, val picture: String?, val opens: TopicArticle?)

data class BroadPerson(val article: TopicArticle, val mark: TimeMark?)

/** Another of the article's sections: its title, the articles it links, and its first picture. */
data class BroadChapter(val title: String, val links: List<TopicArticle>, val picture: String?, val caption: String?)

/**
 * Builds a [BroadTopic] from a topic's [parts]. [resolve] finds an item's article in the installed packs (null: none);
 * [card] reads an article's card (title, sentence, picture); [figure] reads one of the parts' pictures; [markOf] its
 * time mark. Blocking reads happen in the lambdas. With [pictures] false nothing is read for show (no cards' pictures
 * beyond the people's names, no figures): what the page holds is counted the same way.
 */
object BroadTopics {
    suspend fun build(
        parts: TopicParts,
        resolve: suspend (qid: Long) -> Pair<String, String>?,
        card: suspend (packId: String, path: String) -> TopicArticle,
        figure: suspend (image: String) -> String?,
        markOf: suspend (packId: String, path: String) -> TimeMark?,
        now: Int,
        pictures: Boolean = true,
    ): BroadTopic? {
        fun plain(at: Pair<String, String>) = TopicArticle(at.first, at.second, TopicTitles.titleOf(at.second), "", null)
        // The families: the largest groups, each with its kinds in order; then the kinds in no shown group.
        val named = parts.groups.withIndex().sortedByDescending { it.value.count }.take(FAMILIES).map { it.index }
        val byGroup = LinkedHashMap<Int, ArrayList<TopicArticle>>()
        for (k in parts.kinds) resolve(k.qid)?.let { at -> byGroup.getOrPut(if (k.group in named) k.group else -1) { ArrayList() } += plain(at) }
        suspend fun plates(all: List<TopicArticle>, n: Int) = if (!pictures) emptyList() else all.take(n).side { card(it.packId, it.path) }
        val families = named.mapNotNull { g -> byGroup[g]?.let { all -> BroadFamily(parts.groups[g].name, plates(all, FAMILY_PLATES), all) } } +
            listOfNotNull(byGroup[-1]?.let { all -> BroadFamily(null, plates(all, if (named.isEmpty()) FAMILY_PLATES * 2 else FAMILY_PLATES), all) })
        // The page shows a few people as plates: those are read as cards; the rest stand by name (the quilt reads a picture when it shows one).
        val people = parts.people.mapNotNull { q -> resolve(q) }.withIndex().toList()
            .side { (i, at) -> BroadPerson(if (pictures && i < PEOPLE_CARDS) card(at.first, at.second) else plain(at), markOf(at.first, at.second)) }

        // Each section's dated links and dated figures; the story is the section with most (at least STORY_MIN).
        val linksBySection = parts.links.groupBy { it.section }
        val figuresBySection = parts.figures.groupBy { it.section }
        val resolved = HashMap<Long, Pair<String, String>?>()
        suspend fun at(q: Long) = resolved.getOrPut(q) { resolve(q) }
        suspend fun shown(image: String) = if (pictures) figure(image) else null
        val dated = HashMap<Int, List<StoryEntry>>()
        for (s in parts.sections.indices) {
            val entries = ArrayList<StoryEntry>()
            for (f in figuresBySection[s].orEmpty()) {
                val y = yearIn(f.caption, now) ?: continue
                entries += StoryEntry(y, f.caption, shown(f.image), null)
            }
            for (l in linksBySection[s].orEmpty()) {
                val (p, path) = at(l.qid) ?: continue
                val m = markOf(p, path) ?: continue
                if (m.kind == app.granger.core.library.TimeKind.LIFE || m.y0 > now) continue
                entries += StoryEntry(m.y0, TopicTitles.titleOf(path), null, TopicArticle(p, path, TopicTitles.titleOf(path), "", null))
            }
            if (entries.size >= STORY_MIN) dated[s] = entries
        }
        val storyAt = dated.maxByOrNull { it.value.size }?.key
        val story = storyAt?.let { s ->
            // Pictures first where years tie; one step per year and title; at most STORY_MAX, spread over the span.
            val steps = dated.getValue(s).sortedWith(compareBy<StoryEntry> { it.year }.thenBy { it.picture == null })
                .distinctBy { it.year to it.text }.distinctBy { it.text }
            val kept = if (steps.size <= STORY_MAX) steps else spread(steps, STORY_MAX)
            // A linked step gets its own picture when it has one.
            BroadStory(parts.sections[s].title, kept.side { e -> if (pictures && e.picture == null && e.opens != null) e.copy(picture = card(e.opens.packId, e.opens.path).picture) else e })
        }

        val chapters = parts.sections.indices.filter { it != storyAt }.mapNotNull { s ->
            val links = linksBySection[s].orEmpty().mapNotNull { l -> at(l.qid)?.let { (p, path) -> TopicArticle(p, path, TopicTitles.titleOf(path), "", null) } }
                .take(CHAPTER_LINKS)
            if (links.size < CHAPTER_MIN) return@mapNotNull null
            val f = figuresBySection[s].orEmpty().firstNotNullOfOrNull { f -> shown(f.image)?.let { it to f.caption } }
            BroadChapter(parts.sections[s].title, links, f?.first, f?.second)
        }.take(CHAPTERS_MAX)

        if (families.isEmpty() && story == null && people.isEmpty() && chapters.isEmpty()) return null
        fun chapterOf(f: TopicParts.Figure) = parts.sections.getOrNull(f.section)?.title
        // The diagram: the first drawing (a figure that isn't a photograph's JPEG) outside the story, with something to say.
        suspend fun read(from: List<TopicParts.Figure>, n: Int, not: String?): List<BroadFigure> {
            val out = ArrayList<BroadFigure>()
            if (pictures) for (f in from.take(FIGURE_TRIES)) {
                val picture = figure(f.image) ?: continue
                if (picture == not || out.any { it.picture == picture }) continue
                out += BroadFigure(picture, app.granger.explore.captionOf(f.caption), chapterOf(f))
                if (out.size == n) break
            }
            return out
        }
        val captioned = parts.figures.filter { it.caption.isNotBlank() }
        val diagram = read(captioned.filter { it.section != storyAt && isDrawing(it.image) }, 1, null).firstOrNull()
        // The opening's figures: one a third of the way through the article and one two thirds, then the rest in order.
        val figures = read(app.granger.explore.figureOrder(captioned.size).map { captioned[it] }, OPENING_FIGURES, diagram?.picture)
        return BroadTopic(parts.under, parts.groups.map { BroadGroup(it.name, it.count) }, families, story, people, chapters, figures, diagram, parts.inlinks)
    }

    /** The first year a caption names, 1000 to [now] ("Otto Lilienthal in mid-flight, Berlin, c. 1895"); null if none. */
    fun yearIn(caption: String, now: Int): Long? =
        Regex("\\b(1\\d{3}|20\\d{2})\\b").findAll(caption).map { it.value.toLong() }.firstOrNull { it <= now }

    /** [n] of [steps] (in year order) spread evenly, the first and last kept. */
    fun <T> spread(steps: List<T>, n: Int): List<T> =
        if (steps.size <= n) steps else (0 until n).map { i -> steps[(i * (steps.size - 1) + (n - 1) / 2) / (n - 1)] }.distinct()

    /** Whether a figure's file is a drawing (a diagram, a map, a logo) rather than a photograph: anything but a JPEG. */
    fun isDrawing(image: String): Boolean = image.lowercase().removeSuffix(".webp").substringAfterLast('.', "") !in setOf("jpg", "jpeg")

    /** The people read as cards (the page's plates). */
    const val PEOPLE_CARDS = 6
    /** The named families shown, and the plates in each. */
    const val FAMILIES = 3
    const val FAMILY_PLATES = 3
    /** The opening's figures kept (the page shows two), and how many of the topic's figures are tried for a plate. */
    const val OPENING_FIGURES = 4
    const val FIGURE_TRIES = 8
    private const val STORY_MIN = 3
    const val STORY_MAX = 8
    private const val CHAPTER_MIN = 3
    private const val CHAPTER_LINKS = 8
    private const val CHAPTERS_MAX = 5
}

