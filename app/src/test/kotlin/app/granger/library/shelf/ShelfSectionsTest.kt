package app.granger.library.shelf

import app.granger.core.library.ShelfKey
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ShelfSectionsTest {
    private fun shelf(titles: List<String>, byName: Boolean = true) =
        ShelfSections.of(titles.sortedByShelfKey({ ShelfKey.of(it) }), byName, key = { it }, letter = { ShelfKey.letter(ShelfKey.of(it)) })

    /** [n] titles, a few per letter, with some digits and articles among them. */
    private fun titles(n: Int) = (0 until n).map { i -> if (i % 10 == 9) "$i Steps" else "The ${'A' + i % 26}book $i" }

    @Test fun aLongShelfSortedByNameIsGroupedAToZThenHash() {
        val shelf = shelf(titles(60) + listOf("The Odyssey", "1984", "Émile"))
        assertThat(shelf.lettered).isTrue()
        assertThat(shelf.letters.first()).isEqualTo("A")
        assertThat(shelf.letters.last()).isEqualTo("#")
        assertThat(shelf.letters.dropLast(1)).isInOrder()
        assertThat(shelf.sections.first { it.letter == "O" }.items.map { it.value }).contains("The Odyssey")
        assertThat(shelf.sections.first { it.letter == "E" }.items.map { it.value }).contains("Émile")
        assertThat(shelf.sections.first { it.letter == "#" }.items.map { it.value }).contains("1984")
        // Every item is in one section, in the order shown.
        assertThat(shelf.items.map { it.value }).isEqualTo(shelf.sections.flatMap { s -> s.items.map { it.value } })
        assertThat(shelf.items).hasSize(63)
    }

    @Test fun theHeaderIndexCountsTheHeadersAndItemsBefore() {
        val shelf = shelf(List(20) { "Apple $it" } + List(5) { "Banana $it" } + List(10) { "Cherry $it" })
        assertThat(shelf.headerIndex).containsExactly("A", 0, "B", 21, "C", 27).inOrder()
        assertThat(shelf.rowCount).isEqualTo(35 + 3)
    }

    @Test fun aShortShelfHasNoHeaders() {
        val shelf = shelf(titles(30))
        assertThat(shelf.lettered).isFalse()
        assertThat(shelf.letters).isEmpty()
        assertThat(shelf.headerIndex).isEmpty()
        assertThat(shelf.sections).hasSize(1)
        assertThat(shelf.rowCount).isEqualTo(30)
        assertThat(shelf(titles(31)).lettered).isTrue()
    }

    @Test fun aSortWithoutLettersKeepsItsOrderUnderNoHeader() {
        val recent = listOf("Zebra", "1984", "Apple") + titles(40)
        val shelf = ShelfSections.of(recent, byName = false, key = { it }, letter = { ShelfKey.letter(ShelfKey.of(it)) })
        assertThat(shelf.lettered).isFalse()
        assertThat(shelf.items.map { it.value }).isEqualTo(recent)
    }

    @Test fun theOrderPutsHashLastAndTiesKeepTheirTieBreak() {
        val sorted = listOf("2001", "the zebra", "Apple", "an apple", "Émile")
            .sortedByShelfKey({ ShelfKey.of(it) }, then = compareBy { it })
        assertThat(sorted).containsExactly("Apple", "an apple", "Émile", "the zebra", "2001").inOrder()
    }

    @Test fun stripEntriesDropLettersEvenlyForDots() {
        val az = ('A'..'Z').map { it.toString() }
        assertThat(stripEntries(az, 30)).isEqualTo(az)
        val nine = stripEntries(az, 9)
        assertThat(nine.size).isAtMost(9)
        assertThat(nine.first()).isEqualTo("A")
        assertThat(nine.last()).isEqualTo("Z")
        // Letters and dots alternate.
        assertThat(nine.filterIndexed { i, _ -> i % 2 == 1 }.all { it == null }).isTrue()
        assertThat(stripEntries(listOf("A", "B", "C"), 1)).containsExactly("A", null, "C").inOrder()
    }
}
