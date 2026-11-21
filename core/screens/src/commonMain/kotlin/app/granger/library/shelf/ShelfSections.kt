package app.granger.library.shelf

// Shared since Plan I5 Task 2 (immutable; Android's Compose reads them as stable through app/compose-stability.conf).

import app.granger.core.library.ShelfKey

/** One item on a shelf: [key] is its stable lazy-list key, [letter] the header it files under ("A"…"Z", "#"). */
data class ShelfItem<out T>(val key: String, val letter: String, val value: T)

/** The items under one letter header. */
data class ShelfSection<out T>(val letter: String, val items: List<ShelfItem<T>>)

/**
 * A shelf's items in the order shown (Plan LV Decision 6). Sorted by name and longer than [STRIP_MIN] items, they are
 * [lettered]: grouped under letter headers, A–Z then "#", with the letter strip beside them. Otherwise (a short shelf,
 * or a sort with no letters: Recent, Year, Date added) there is one section with no header. Pure, so a view model
 * builds it off the main thread.
 *
 * The lazy list or grid shows, after any header items of the screen's own, each section's header (when [lettered])
 * then its items; [headerIndex] is where each letter's header falls in that run, which the strip scrolls to.
 */
class ShelfSections<out T> private constructor(val sections: List<ShelfSection<T>>, val lettered: Boolean) {
    /** Every item, in the order shown: what a tap plays or opens from. */
    val items: List<ShelfItem<T>> = sections.flatMap { it.items }

    /** The letters present, in order. */
    val letters: List<String> = if (lettered) sections.map { it.letter } else emptyList()

    /** Each letter's header, as an index into the run of headers and items. */
    val headerIndex: Map<String, Int> = buildMap {
        if (!lettered) return@buildMap
        var at = 0
        for (section in sections) {
            put(section.letter, at)
            at += 1 + section.items.size
        }
    }

    /** The rows the shelf adds to its lazy list: items, and a header per section when [lettered]. */
    val rowCount: Int = items.size + if (lettered) sections.size else 0

    val isEmpty: Boolean get() = items.isEmpty()

    companion object {
        /** A shelf this long or shorter has no letter headers and no strip. */
        const val STRIP_MIN = 30

        private val EMPTY = ShelfSections<Nothing>(emptyList(), lettered = false)

        fun <T> empty(): ShelfSections<T> = EMPTY

        /**
         * [values] as a shelf. With [byName] (the sort is by title, author, artist or name) and more than [STRIP_MIN]
         * of them, they are grouped by [letter], keeping [values]' order within each letter, letters A–Z and "#" last;
         * otherwise they stay in [values]' order under no header. [key] must be unique and stable across sorts.
         */
        fun <T> of(values: List<T>, byName: Boolean, key: (T) -> String, letter: (T) -> String): ShelfSections<T> {
            val items = values.map { ShelfItem(key(it), letter(it), it) }
            if (!byName || items.size <= STRIP_MIN) return ShelfSections(listOf(ShelfSection("", items)), lettered = false)
            val groups = items.groupBy { it.letter }
            val order = groups.keys.sortedWith(compareBy({ it == ShelfKey.OTHER }, { it }))
            return ShelfSections(order.map { ShelfSection(it, groups.getValue(it)) }, lettered = true)
        }
    }
}
