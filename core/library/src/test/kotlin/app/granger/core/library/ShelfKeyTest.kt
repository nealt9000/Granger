package app.granger.core.library

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ShelfKeyTest {
    private fun letterOf(title: String, tag: String? = null) = ShelfKey.letter(ShelfKey.of(title, tag))

    @Test fun aLeadingArticleIsSkipped() {
        assertThat(ShelfKey.of("The Odyssey")).isEqualTo("odyssey")
        assertThat(ShelfKey.of("A Tale of Two Cities")).isEqualTo("tale of two cities")
        assertThat(ShelfKey.of("An Essay on Man")).isEqualTo("essay on man")
        assertThat(letterOf("The Odyssey")).isEqualTo("O")
    }

    @Test fun onlyALeadingWholeWordArticleIsSkipped() {
        assertThat(ShelfKey.of("Theogony")).isEqualTo("theogony")
        assertThat(ShelfKey.of("Animal Farm")).isEqualTo("animal farm")
        assertThat(ShelfKey.of("Of Mice and the Men")).isEqualTo("of mice and the men")
        // An article alone is the title.
        assertThat(ShelfKey.of("The")).isEqualTo("the")
        assertThat(ShelfKey.of("A")).isEqualTo("a")
    }

    @Test fun caseDoesNotCount() {
        assertThat(ShelfKey.of("THE ODYSSEY")).isEqualTo(ShelfKey.of("the odyssey"))
        assertThat(ShelfKey.of("tHe Odyssey")).isEqualTo("odyssey")
    }

    @Test fun accentsFoldToTheirLetter() {
        assertThat(ShelfKey.of("Émile")).isEqualTo("emile")
        assertThat(letterOf("Émile")).isEqualTo("E")
        assertThat(letterOf("Über Alles")).isEqualTo("U")
    }

    @Test fun digitsAndOtherScriptsFileUnderHash() {
        assertThat(letterOf("1984")).isEqualTo("#")
        assertThat(letterOf("The 39 Steps")).isEqualTo("#")
        assertThat(letterOf("Война и мир")).isEqualTo("#")
    }

    @Test fun leadingMarksAreSkipped() {
        assertThat(ShelfKey.of("'Salem's Lot")).isEqualTo("salem's lot")
        assertThat(ShelfKey.of("\"The Raven\"")).isEqualTo("raven\"")
        assertThat(letterOf("...And Justice for All")).isEqualTo("A")
        assertThat(letterOf("(What's the Story) Morning Glory?")).isEqualTo("W")
    }

    @Test fun anEmptyTitleFilesUnderHash() {
        assertThat(ShelfKey.of("")).isEmpty()
        assertThat(ShelfKey.of("   ")).isEmpty()
        assertThat(ShelfKey.letter("")).isEqualTo("#")
        assertThat(letterOf("?!")).isEqualTo("#")
    }

    @Test fun aSortTagWins() {
        assertThat(ShelfKey.of("The Beatles", "Beatles, The")).isEqualTo("beatles, the")
        // The tag is taken as written: its own article stays.
        assertThat(ShelfKey.of("Beatles", "The Beatles")).isEqualTo("the beatles")
        assertThat(ShelfKey.of("Björk", "Bjork")).isEqualTo("bjork")
        // A blank tag is no tag.
        assertThat(ShelfKey.of("The Who", "  ")).isEqualTo("who")
        assertThat(ShelfKey.of("The Who", null)).isEqualTo("who")
    }
}
