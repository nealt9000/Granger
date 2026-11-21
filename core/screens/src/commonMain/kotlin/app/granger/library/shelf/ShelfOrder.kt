package app.granger.library.shelf

import app.granger.core.library.ShelfKey

// Shared since Plan I4b Task 6 (Get more's A–Z lists); the shelf screens' sections stay in the app.

/**
 * This list A–Z by [ShelfKey], with names filed under "#" (digits, other scripts, blank) after Z, as the letter headers
 * come; [then] orders those with the same key. [keyOf] gives an item's [ShelfKey.of], and runs once per item.
 */
fun <T> List<T>.sortedByShelfKey(keyOf: (T) -> String, then: Comparator<in T>? = null): List<T> {
    class Keyed(val value: T, val key: String, val other: Boolean)
    val byKey = compareBy<Keyed>({ it.other }, { it.key })
    val order = if (then == null) byKey else byKey.thenComparator { a, b -> then.compare(a.value, b.value) }
    return map { v -> keyOf(v).let { Keyed(v, it, ShelfKey.letter(it) == ShelfKey.OTHER) } }.sortedWith(order).map { it.value }
}
