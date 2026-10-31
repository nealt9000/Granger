package app.granger.core.books.pdfepub

import app.granger.core.books.OutlineEntry

/** Where a converted book's chapters came from, best first. */
enum class ChapterSource { OUTLINE, HEADINGS, PAGES }

/**
 * A chapter: its [title], its [items], the item that is its own title on the page ([titleItem], drawn as its heading),
 * and [sections] below it (title and the item they start at) from the outline's next level.
 */
internal class Chapter(val title: String, val items: List<Item>, val titleItem: Int?, val sections: List<Pair<String, Int>>)

/** Items into chapters (Plan IA converter stage 6): the outline's, else the headings', else every [PAGES_PER] pages. */
internal object PdfChapters {
    const val PAGES_PER = 20
    const val OPENING = "Opening pages"

    fun split(items: List<Item>, outline: List<OutlineEntry>, pageCount: Int): Pair<List<Chapter>, ChapterSource> {
        fromOutline(items, outline)?.let { return it to ChapterSource.OUTLINE }
        fromHeadings(items)?.let { return it to ChapterSource.HEADINGS }
        return byPages(items, pageCount) to ChapterSource.PAGES
    }

    /**
     * The shallowest outline level with at least three entries (and every entry above it) start chapters; the level
     * below starts sections. Each starts at the item on its page that reads as its title, else at the page's first item.
     * Null when the outline has fewer than two usable entries.
     */
    private fun fromOutline(items: List<Item>, outline: List<OutlineEntry>): List<Chapter>? {
        if (items.isEmpty()) return null
        val usable = outline.filter { it.page >= 1 && it.title.isNotBlank() }
        if (usable.size < 2) return null
        // An outline whose entries mostly point at one page (a broken export) is placed by its titles alone, in order.
        val byTitle = usable.map { it.page }.distinct().size * 2 < usable.size
        val level = if (byTitle) {
            usable.map { it.depth }.distinct().sorted().let { ds -> ds.firstOrNull { d -> usable.count { it.depth <= d } >= 3 } ?: ds.first() }
        } else {
            level(usable, items)
        }
        val tops = usable.filter { it.depth <= level }.let { if (byTitle) it else it.sortedBy { e -> e.page } }
        val starts = mutableListOf<Pair<OutlineEntry, Pair<Int, Boolean>>>()
        var from = 0
        for (entry in tops) {
            val at = (if (byTitle) findTitle(items, entry.title, from)?.let { it to true } else place(items, entry, from)) ?: continue
            starts += entry to at
            from = at.first + if (byTitle) 1 else 0
        }
        if (byTitle && starts.size * 5 < tops.size * 3) return null
        if (starts.size < 2) return null
        val sectionEntries = usable.filter { it.depth == level + 1 }
        val chapters = mutableListOf<Chapter>()
        opening(items, starts.first().second.first)?.let { chapters += it }
        for ((k, start) in starts.withIndex()) {
            val (entry, at) = start
            val end = starts.getOrNull(k + 1)?.second?.first ?: items.size
            if (end <= at.first) continue
            val slice = items.subList(at.first, end)
            val sections = sectionEntries.filter { it.page - 1 in slice.first().page..slice.last().page }
                .mapNotNull { s -> place(slice, s, 0)?.first?.takeIf { it > 0 }?.let { s.title.trim() to it } }
            chapters += Chapter(entry.title.trim(), slice, if (at.second) 0 else null, sections)
        }
        return chapters
    }

    /**
     * The outline level whose entries (with every entry above them) start chapters: the shallowest with at least three
     * entries where no chapter runs over [MAX_CHAPTER_SHARE] of the book or [MAX_CHAPTER_PAGES] pages, whichever is
     * more. The Odyssey's top level is Introduction, Maps, The Odyssey, Notes…, with the 24 books one level down:
     * "The Odyssey" alone would be 400 pages. Failing that, the deepest level with no more chapters than pages.
     */
    private fun level(outline: List<OutlineEntry>, items: List<Item>): Int {
        val first = items.first().page
        val last = items.last().page
        val pages = last - first + 1
        val limit = maxOf(MAX_CHAPTER_PAGES, (pages * MAX_CHAPTER_SHARE).toInt())
        val depths = outline.map { it.depth }.distinct().sorted()
        for (d in depths) {
            val starts = outline.filter { it.depth <= d }.map { it.page - 1 }.distinct().sorted()
            if (starts.size < 3) continue
            val bounds = listOf(first) + starts.filter { it > first } + (last + 1)
            val longest = bounds.zipWithNext { a, b -> b - a }.maxOrNull() ?: 0
            if (longest <= limit) return d
        }
        return depths.lastOrNull { d -> outline.count { it.depth <= d } <= pages } ?: depths.first()
    }

    private const val MAX_CHAPTER_PAGES = 50
    private const val MAX_CHAPTER_SHARE = 0.2f
    private const val MAX_OVERLONG_SHARE = 0.4f

    /** A heading that only labels the one below it: "Section 1", "Chapter IV", "3.". */
    private val LABEL = Regex("^(section|chapter|part|book)?\\s*[0-9ivxlc]+\\.?$", RegexOption.IGNORE_CASE)

    /** The item [entry] starts at (from [from] on), and whether it reads as the entry's title. */
    private fun place(items: List<Item>, entry: OutlineEntry, from: Int): Pair<Int, Boolean>? {
        val page = entry.page - 1
        val want = normal(entry.title)
        for (i in from until items.size) {
            val item = items[i]
            if (item.page < page) continue
            if (item.page > page + 1) break
            val text = when (item) {
                is Heading -> item.text
                is Paragraph -> item.text.take(200)
                else -> continue
            }
            val got = normal(text)
            if (want.length >= 3 && got.isNotEmpty() && (got.startsWith(want) || want.startsWith(got) && got.length >= 3)) return i to true
        }
        val first = (from until items.size).firstOrNull { items[it].page >= page } ?: return null
        return first to false
    }

    /** The first heading (else paragraph) from [from] on that starts with [title]'s words (its first 40 letters). */
    private fun findTitle(items: List<Item>, title: String, from: Int): Int? {
        val want = normal(title).take(40)
        if (want.length < 3) return null
        fun text(item: Item) = when (item) {
            is Heading -> item.text
            is Paragraph -> item.text.take(200)
            else -> null
        }
        return (from until items.size).firstOrNull { i -> items[i] is Heading && normal(text(items[i])!!).startsWith(want) }
            ?: (from until items.size).firstOrNull { i -> text(items[i])?.let { normal(it).startsWith(want) } == true }
    }

    private val NOT_WORD = Regex("[^\\p{L}\\p{N}]+")

    /** [text] lower-cased with only its letters and digits; cached for a heading or paragraph, which are matched many times. */
    private val normals = java.util.Collections.synchronizedMap(java.util.WeakHashMap<String, String>())

    private fun normal(text: String): String = normals.getOrPut(text) { text.lowercase().replace(NOT_WORD, "") }

    /**
     * Chapters at the biggest headings that come at least twice and no more than once a page, and leave no chapter over
     * [MAX_CHAPTER_SHARE] of the book or [MAX_CHAPTER_PAGES] pages: a title page's one big line doesn't count, a run of
     * section heads on every page is too many, and a report's two title lines don't make two 300-page chapters. Null
     * when no level does.
     */
    private fun fromHeadings(items: List<Item>): List<Chapter>? {
        val headings = items.withIndex().filter { it.value is Heading }
        if (headings.size < 2) return null
        val first = items.first().page
        val last = items.last().page
        val pages = (last - first + 1).coerceAtLeast(1)
        val limit = maxOf(MAX_CHAPTER_PAGES, (pages * MAX_CHAPTER_SHARE).toInt())
        val levels = headings.map { Math.round((it.value as Heading).size * 2f) / 2f }.distinct().sortedDescending()
        val found = levels.asSequence().map { level ->
            headings.filter { (it.value as Heading).size >= level - 0.25f }.map { it.index }
        }.firstOrNull { starts ->
            if (starts.size < 2 || starts.size > pages) return@firstOrNull false
            val bounds = listOf(first) + starts.map { items[it].page } + (last + 1)
            // One long stretch is allowed (a report's notes): no more than [MAX_OVERLONG_SHARE] of the book over the limit.
            val spans = bounds.zipWithNext { a, b -> b - a }
            spans.filter { it > limit }.sum() <= pages * MAX_OVERLONG_SHARE && spans.count { it > limit } <= 1
        } ?: return null
        // Heading lines one after another are one title: "Section 1" over "Introduction", a journal's name over three lines.
        val runs = mutableListOf<MutableList<Int>>()
        for (i in found) if (runs.lastOrNull()?.last() == i - 1) runs.last() += i else runs += mutableListOf(i)
        val chapters = mutableListOf<Chapter>()
        opening(items, runs.first().first())?.let { chapters += it }
        for ((k, run) in runs.withIndex()) {
            val end = runs.getOrNull(k + 1)?.first() ?: items.size
            val title = run.map { (items[it] as Heading).text.trim() }.reduce { a, b -> if (LABEL.matches(a)) "$a: $b" else "$a $b" }
            chapters += Chapter(title, items.subList(run.first(), end), 0, emptyList())
        }
        return chapters
    }

    /** "Pages 1–20", "Pages 21–40"…, by the pages the items are on. */
    private fun byPages(items: List<Item>, pageCount: Int): List<Chapter> {
        if (items.isEmpty()) return emptyList()
        return items.groupBy { it.page / PAGES_PER }.toSortedMap().map { (block, list) ->
            val first = block * PAGES_PER + 1
            val last = minOf(first + PAGES_PER - 1, pageCount)
            Chapter("Pages $first–$last", list, null, emptyList())
        }
    }

    /** What comes before the first chapter, when it holds any text: "Opening pages". */
    private fun opening(items: List<Item>, firstStart: Int): Chapter? {
        if (firstStart <= 0) return null
        val before = items.subList(0, firstStart)
        if (before.none { it is Paragraph || it is Verse || it is Heading || it is Picture }) return null
        return Chapter(OPENING, before, null, emptyList())
    }
}
