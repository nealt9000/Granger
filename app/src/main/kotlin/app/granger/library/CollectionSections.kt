package app.granger.library

import app.granger.core.library.Book
import app.granger.core.library.PackBooks
import app.granger.core.library.SearchText

/**
 * One row of a book collection shown in its own order (Plan KB2 Task 2.3): a section's header, a book, or a work in
 * parts. [key] is the row's lazy-list key, stable while the collection is open.
 */
sealed interface SectionRow {
    val key: String

    /**
     * A section's header: its [name] and how many rows it holds ([count], a work counted once). A [foldable] section
     * (more than [FOLD_OVER] rows) shows only this header until [expanded].
     */
    data class Header(val name: String, val count: Int, val foldable: Boolean, val expanded: Boolean) : SectionRow {
        override val key: String get() = "section:$name"
    }

    /** A book; [part] when it's shown under its open work. */
    data class Item(val book: Book, val part: Boolean = false) : SectionRow {
        override val key: String get() = bookKey(book)
    }

    /**
     * A work in parts, as one row: titled by its whole file, else the manifest's work title. [whole] is the file of the
     * whole work when the pack has one, [parts] the parts in order; tapping the row shows them ([expanded]).
     */
    data class Work(val workKey: String, val title: String, val author: String, val whole: Book?, val parts: List<Book>, val expanded: Boolean) : SectionRow {
        override val key: String get() = "work:$workKey"

        /** Its files as the open row lists them: the whole first, then the parts. */
        val members: List<Book> get() = listOfNotNull(whole) + parts

        /** The book whose cover stands for the work. */
        val first: Book get() = whole ?: parts.first()
    }

    companion object {
        /** A section with more rows than this is folded until opened ("Course materials · 643"). */
        const val FOLD_OVER = 50
    }
}

/** Whether [books] are a collection with an order of its own: a manifest's (Plan KB2), whose rows carry `ord`. */
fun hasOwnOrder(books: List<Book>): Boolean = books.isNotEmpty() && books.any { it.ord != null }

/**
 * [books] as the manifest orders them: the books with no section first (no header), then each section under its header,
 * in [groups]' order (a section the list doesn't name follows, by its first book), books by `ord` within each. Files of
 * one work fold into a [SectionRow.Work]; a section of more than [SectionRow.FOLD_OVER] rows folds unless its name is in
 * [openSections], and a work shows its files once its key is in [openWorks]. Pure, so it runs off the main thread.
 */
fun sectionRows(
    books: List<Book>,
    groups: List<String>,
    openSections: Set<String>,
    openWorks: Set<String>,
): List<SectionRow> {
    val byOrder = compareBy<Book>({ it.ord ?: Int.MAX_VALUE }, { SearchText.fold(it.title) }, { it.id })
    val sections = books.sortedWith(byOrder).groupBy { it.groupName.orEmpty() }
    val firstOrd = sections.mapValues { (_, list) -> list.minOf { it.ord ?: Int.MAX_VALUE } }
    val named = groups.withIndex().associate { (i, name) -> name to i }
    val order = sections.keys.sortedWith(
        compareBy<String>({ it.isNotEmpty() }, { named[it] ?: Int.MAX_VALUE }, { firstOrd[it] ?: Int.MAX_VALUE }, { it }),
    )
    val rows = ArrayList<SectionRow>()
    for (name in order) {
        val entries = entriesOf(sections.getValue(name), openWorks)
        if (name.isNotEmpty()) {
            val foldable = entries.size > SectionRow.FOLD_OVER
            val expanded = !foldable || name in openSections
            rows += SectionRow.Header(name, entries.size, foldable, expanded)
            if (!expanded) continue
        }
        for (entry in entries) {
            rows += entry
            if (entry is SectionRow.Work && entry.expanded) entry.members.mapTo(rows) { SectionRow.Item(it, part = true) }
        }
    }
    return rows
}

/** One section's books as rows, in order, each work folded at its first file. A "work" of one file is just that book. */
private fun entriesOf(books: List<Book>, openWorks: Set<String>): List<SectionRow> {
    val works = books.filter { it.workKey != null }.groupBy { it.workKey!! }
    val placed = HashSet<String>()
    val out = ArrayList<SectionRow>()
    for (book in books) {
        val key = book.workKey
        val members = key?.let(works::get)
        if (key == null || members == null || members.size < 2) {
            out += SectionRow.Item(book)
            continue
        }
        if (!placed.add(key)) continue
        val whole = members.firstOrNull { keyOf(it) == key }
        val parts = (members - setOfNotNull(whole)).sortedWith(compareBy<Book>({ it.partNo ?: Int.MAX_VALUE }, { it.ord ?: Int.MAX_VALUE }, { it.id }))
        out += SectionRow.Work(
            workKey = key,
            title = whole?.title ?: members.firstNotNullOfOrNull { it.workTitle } ?: parts.first().title,
            author = (whole ?: parts.first()).author,
            whole = whole,
            parts = parts,
            expanded = key in openWorks,
        )
    }
    return out
}

/** A manifest book's key: its ref after the pack name (`<pack name>/<key>`). */
private fun keyOf(book: Book): String = PackBooks.refOf(book).substringAfter('/')
