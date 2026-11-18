package app.granger.topic

import app.granger.core.library.BookMention
import app.granger.core.library.Facet
import app.granger.core.library.TimeMark

/** What a group of a quilt's plates is to the topic in the middle. */
enum class QuiltKind { KINDS, PEOPLE, STORY, CHAPTER, CLOSE, LEADS, LIVES, EVENTS, ROOMS, BOOKS }

/**
 * One plate of a quilt: an article (touching it re-forms the quilt round it), a room, or a book at a passage. [key] is
 * the same for the same thing wherever it appears, so a plate can be found again on the next quilt.
 */
data class QuiltPlate(
    val key: String,
    val title: String,
    val picture: String? = null,
    /** A line under the title: years. */
    val sub: String? = null,
    val packId: String? = null,
    val path: String? = null,
    val room: Facet? = null,
    val bookId: String? = null,
    val mention: BookMention? = null,
    val chapter: String? = null,
) {
    val isArticle: Boolean get() = packId != null && path != null
}

/** A group of plates round a topic: what they are to it, its own name when it has one (a chapter's title), and all of them. */
data class QuiltGroup(val kind: QuiltKind, val name: String?, val plates: List<QuiltPlate>)

/** One place on the quilt: a plate, the group it stands for ([group], an index), and whether its group's tab starts here. */
data class QuiltCell(val group: Int, val plate: QuiltPlate, val tab: Boolean)

/**
 * What stands round a topic that its page doesn't carry: the broad topics that lead to it ([leads]), its [rooms], for
 * each plate asked about how many other broad topics lead to it ([more], by `packId/path`), and its own card and dates
 * (for a held plate's label).
 */
data class QuiltAround(
    val leads: List<TopicArticle> = emptyList(),
    val rooms: List<Facet> = emptyList(),
    val more: Map<String, Int> = emptyMap(),
    val card: TopicArticle? = null,
    val mark: TimeMark? = null,
)

/**
 * The connections quilt (Plan EX round 2, D4): the plates that touch the middle plate are connected to it, group by
 * group. Pure: the groups come from a topic's page ([Topic]) and what stands round it ([QuiltAround]).
 */
object Quilts {
    /** Plates round the middle: three above, two beside, three below, four along the foot. */
    const val CELLS = 12

    /** How many plates each group gets on its first turn, the largest group first; the turns go round again while there is room. */
    private val TURNS = listOf(3, 2, 3, 2, 2)

    fun key(packId: String, path: String) = "$packId/$path"

    private fun plate(a: TopicArticle, sub: String? = null) = QuiltPlate(key(a.packId, a.path), a.title, a.picture, sub, a.packId, a.path)

    /**
     * A topic's groups, the largest first (ties: [QuiltKind]'s order), empty ones left out, the topic itself in none. A
     * broad topic gives its kinds, its people, its story and its chapters; any topic its closest in meaning, the topics
     * that lead to it, the lives and events of its years, its rooms and the reader's books that name it.
     */
    fun groups(topic: Topic, around: QuiltAround?, language: String, years: (TimeMark) -> String): List<QuiltGroup> {
        val me = key(topic.packId, topic.path)
        val out = ArrayList<QuiltGroup>()
        fun add(kind: QuiltKind, name: String?, plates: List<QuiltPlate>) {
            val kept = plates.filter { it.key != me }.distinctBy { it.key }
            if (kept.isNotEmpty()) out += QuiltGroup(kind, name, kept)
        }
        topic.broad?.let { b ->
            // A kind read as a plate on the page brings its picture with it.
            val pictured = b.families.flatMap { it.plates }.associateBy { key(it.packId, it.path) }
            add(QuiltKind.KINDS, null, b.families.flatMap { it.all }.map { plate(pictured[key(it.packId, it.path)] ?: it) })
            add(QuiltKind.PEOPLE, null, b.people.map { p -> plate(p.article, p.mark?.let(years)) })
            b.story?.let { s -> add(QuiltKind.STORY, s.title, s.entries.mapNotNull { e -> e.opens?.let { plate(it.copy(picture = e.picture)) } }) }
            for (c in b.chapters) add(QuiltKind.CHAPTER, c.title, c.links.map { plate(it) })
        }
        add(QuiltKind.CLOSE, null, topic.related.orEmpty().map { plate(it) })
        add(QuiltKind.LEADS, null, around?.leads.orEmpty().map { plate(it) })
        topic.time?.let { t ->
            add(QuiltKind.LIVES, null, t.people.map { m -> QuiltPlate(key(m.packId, m.path), TopicSource.titleOf(m.path), t.pictures[m.path], years(m), m.packId, m.path) })
            add(QuiltKind.EVENTS, null, t.events.map { m -> QuiltPlate(key(m.packId, m.path), TopicSource.titleOf(m.path), null, years(m), m.packId, m.path) })
        }
        add(QuiltKind.ROOMS, null, around?.rooms.orEmpty().map { f -> QuiltPlate("room:${f.key}", f.name(language), room = f) })
        val books = topic.books?.inBooks.orEmpty().map { h -> QuiltPlate("book:${h.book.bookId}", h.book.title, h.book.cover, bookId = h.book.bookId, mention = h.passages.firstOrNull()) } +
            topic.relatedBooks.orEmpty().map { b -> QuiltPlate("book:${b.book.bookId}", b.book.title, b.book.cover, bookId = b.book.bookId, chapter = b.chapter) }
        add(QuiltKind.BOOKS, null, books)
        return out.withIndex().sortedWith(compareByDescending<IndexedValue<QuiltGroup>> { it.value.plates.size }.thenBy { it.value.kind.ordinal }.thenBy { it.index }).map { it.value }
    }

    /** How many different things the groups hold between them. */
    fun connections(groups: List<QuiltGroup>): Int = groups.flatMap { g -> g.plates.map { it.key } }.distinct().size

    /**
     * The quilt's plates for [groups] in their order, at most [max]: each group takes a turn (three plates, two, three,
     * two, two), then the turns go round again while there is room. A thing in two groups stands once, with the first. A
     * group's tab is on the first plate of each of its runs.
     */
    fun cells(groups: List<QuiltGroup>, max: Int = CELLS): List<QuiltCell> {
        val out = ArrayList<QuiltCell>()
        val placed = HashSet<String>()
        val next = IntArray(groups.size)
        fun take(g: Int, n: Int) {
            var taken = 0
            while (taken < n && out.size < max && next[g] < groups[g].plates.size) {
                val p = groups[g].plates[next[g]++]
                if (!placed.add(p.key)) continue
                out += QuiltCell(g, p, tab = out.lastOrNull()?.group != g)
                taken++
            }
        }
        for (g in groups.indices.take(TURNS.size)) take(g, TURNS[g])
        // Round again, a plate a group, until the quilt is full or nothing is left.
        var grew = true
        while (out.size < max && grew) {
            val before = out.size
            for (g in groups.indices) take(g, 1)
            grew = out.size > before
        }
        return out
    }

    /** How the cells stand round the middle plate: the row above it, the two beside it, the row below, and the foot. */
    data class Layout(val above: List<QuiltCell>, val beside: List<QuiltCell>, val below: List<QuiltCell>, val foot: List<QuiltCell>)

    /**
     * [cells] laid round the middle (pure, for tests). Three above; two beside when there are five or more (the middle
     * plate runs the whole width otherwise); three below; up to four along the foot.
     */
    fun layout(cells: List<QuiltCell>): Layout {
        val above = cells.take(3)
        val beside = if (cells.size >= 5) cells.subList(3, 5) else emptyList()
        val rest = cells.drop(above.size + beside.size)
        return Layout(above, beside, rest.take(3), rest.drop(3).take(4))
    }
}
