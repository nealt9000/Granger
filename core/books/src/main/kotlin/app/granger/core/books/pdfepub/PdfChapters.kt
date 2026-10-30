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
        val level = level(usable, items)
        val tops = usable.filter { it.depth <= level }.sortedBy { it.page }
        val starts = mutableListOf<Pair<OutlineEntry, Pair<Int, Boolean>>>()
        var from = 0
        for (entry in tops) {
            val at = place(items, entry, from) ?: continue
            starts += entry to at
            from = at.first
        }
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

    private fun normal(text: String): String = text.lowercase().replace(Regex("[^\\p{L}\\p{N}]+"), "")

    /**
     * Chapters at the biggest headings that come at least twice and no more than once a page: a title page's one big
     * line doesn't count, a run of section heads in every page is too many. Null without two.
     */
    private fun fromHeadings(items: List<Item>): List<Chapter>? {
        val headings = items.withIndex().filter { it.value is Heading }
        if (headings.size < 2) return null
        val pages = (items.maxOf { it.page } - items.minOf { it.page } + 1).coerceAtLeast(1)
        val bySize = headings.groupBy { (it.value as Heading).size.let { s -> Math.round(s * 2f) / 2f } }
        val level = bySize.keys.sortedDescending().firstOrNull { size ->
            val n = bySize.getValue(size).size
            n >= 2 && n <= pages
        } ?: return null
        val starts = headings.filter { (it.value as Heading).size >= level - 0.25f }.map { it.index }
        val chapters = mutableListOf<Chapter>()
        opening(items, starts.first())?.let { chapters += it }
        for ((k, start) in starts.withIndex()) {
            val end = starts.getOrNull(k + 1) ?: items.size
            val heading = items[start] as Heading
            chapters += Chapter(heading.text.trim(), items.subList(start, end), 0, emptyList())
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
