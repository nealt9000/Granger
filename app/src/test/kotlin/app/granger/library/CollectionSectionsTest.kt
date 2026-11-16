package app.granger.library

import app.granger.book
import app.granger.core.library.Book
import app.granger.core.library.BookOrigin
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** Plan KB2 Task 2.3: a manifest collection in its own order, with sections, folded sections and works in parts. */
class CollectionSectionsTest {
    private fun kb(key: String, title: String, group: String = "", ord: Int, workKey: String? = null, workTitle: String? = null, partNo: Int? = null, language: String = "en"): Book =
        book("book:kiwix:p_en_all/$key", title).copy(
            origin = BookOrigin.KIWIX, sourceRef = "p_en_all/$key", groupName = group, ord = ord, workKey = workKey,
            workTitle = workTitle, partNo = partNo, language = language,
        )

    private fun SectionRow.label(): String = when (this) {
        is SectionRow.Header -> "# $name ($count)${if (foldable) if (expanded) " open" else " folded" else ""}"
        is SectionRow.Item -> (if (part) "  - " else "") + book.title
        is SectionRow.Work -> "${title} [${members.size}]${if (expanded) " open" else ""}"
    }

    @Test fun ungroupedBooksComeFirstThenSectionsInTheManifestsOrder() {
        val books = listOf(
            kb("b", "Bee Keeping", "Farming", 6), kb("r", "Field Radio", ord = 0), kb("h", "Hedges", "Farming", 5),
            kb("k", "Knots", ord = 1), kb("a", "Archery", "Archery", 7),
        )
        val rows = sectionRows(books, groups = listOf("Archery", "Farming"), openSections = emptySet(), openWorks = emptySet())
        assertThat(rows.map { it.label() }).containsExactly(
            "Field Radio", "Knots", "# Archery (1)", "Archery", "# Farming (2)", "Hedges", "Bee Keeping",
        ).inOrder()
        // A section the manifest's list doesn't name follows, by its first book.
        assertThat(sectionRows(books, groups = listOf("Farming"), emptySet(), emptySet()).filterIsInstance<SectionRow.Header>().map { it.name })
            .containsExactly("Farming", "Archery").inOrder()
        assertThat(hasOwnOrder(books)).isTrue()
        assertThat(hasOwnOrder(listOf(book("x")))).isFalse()
    }

    @Test fun aSectionOfMoreThanFiftyFoldsUntilOpened() {
        val course = (1..643).map { kb("c$it", "Homework $it", "Course materials", it) }
        val book = (1..53).map { kb("b$it", "Chapter $it", "The book", 1000 + it) }
        val folded = sectionRows(course + book, listOf("Course materials", "The book"), emptySet(), emptySet())
        assertThat(folded.first().label()).isEqualTo("# Course materials (643) folded")
        assertThat(folded[1].label()).isEqualTo("# The book (53) folded")
        assertThat(folded).hasSize(2)
        val open = sectionRows(course + book, listOf("Course materials", "The book"), setOf("The book"), emptySet())
        assertThat(open.map { it.label() }.take(3)).containsExactly("# Course materials (643) folded", "# The book (53) open", "Chapter 1").inOrder()
        assertThat(open).hasSize(2 + 53)
        val small = sectionRows((1..50).map { kb("s$it", "S $it", "Small", it) }, emptyList(), emptySet(), emptySet())
        assertThat(small.first().label()).isEqualTo("# Small (50)")
    }

    @Test fun partsOfAWorkAreOneRowThatOpensToItsParts() {
        val books = listOf(
            kb("sf3", "Special Forces (Part 3)", "Handbooks", 3, workKey = "sf", workTitle = "Special Forces Medical Handbook", partNo = 3),
            kb("sf1", "Special Forces (Part 1)", "Handbooks", 1, workKey = "sf", workTitle = "Special Forces Medical Handbook", partNo = 1),
            kb("sf2", "Special Forces (Part 2)", "Handbooks", 2, workKey = "sf", workTitle = "Special Forces Medical Handbook", partNo = 2),
            kb("fa", "First Aid", "Handbooks", 4),
        )
        val closed = sectionRows(books, listOf("Handbooks"), emptySet(), emptySet())
        assertThat(closed.map { it.label() }).containsExactly("# Handbooks (2)", "Special Forces Medical Handbook [3]", "First Aid").inOrder()
        val work = closed[1] as SectionRow.Work
        assertThat(work.whole).isNull()
        assertThat(work.parts.map { it.partNo }).containsExactly(1, 2, 3).inOrder()
        assertThat(work.first.title).isEqualTo("Special Forces (Part 1)")
        val open = sectionRows(books, listOf("Handbooks"), emptySet(), setOf("sf"))
        assertThat(open.map { it.label() }).containsExactly(
            "# Handbooks (2)", "Special Forces Medical Handbook [3] open", "  - Special Forces (Part 1)", "  - Special Forces (Part 2)",
            "  - Special Forces (Part 3)", "First Aid",
        ).inOrder()
    }

    @Test fun aWholeFileHeadsItsParts() {
        // ManifestSource marks a whole file with its own key, and its parts (part_of) with the whole's.
        val books = listOf(
            kb("hc", "Hospital Corpsman (full text)", ord = 0, workKey = "hc"),
            kb("hc-2", "Hospital Corpsman, Part 2", ord = 2, workKey = "hc", partNo = 2),
            kb("hc-1", "Hospital Corpsman, Part 1", ord = 1, workKey = "hc", partNo = 1),
        )
        val work = sectionRows(books, emptyList(), emptySet(), setOf("hc"))
        assertThat(work.map { it.label() }).containsExactly(
            "Hospital Corpsman (full text) [3] open", "  - Hospital Corpsman (full text)", "  - Hospital Corpsman, Part 1",
            "  - Hospital Corpsman, Part 2",
        ).inOrder()
        assertThat((work.first() as SectionRow.Work).whole?.title).isEqualTo("Hospital Corpsman (full text)")
        // A "work" of one file is just the book.
        assertThat(sectionRows(books.take(1), emptyList(), emptySet(), emptySet()).single()).isInstanceOf(SectionRow.Item::class.java)
    }
}
