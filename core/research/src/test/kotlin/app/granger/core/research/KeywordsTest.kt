package app.granger.core.research

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class KeywordsTest {
    @Test fun dropsStopwordsAndShortWords() =
        assertThat(Keywords.extract("What is the boiling point of water at altitude?"))
            .containsExactly("boiling", "point", "water", "altitude").inOrder()

    @Test fun capsAndDedupes() =
        assertThat(Keywords.extract("steam steam engine boiler valve piston gauge governor", max = 6)).hasSize(6)

    @Test fun dedupeKeepsFirstOccurrence() =
        assertThat(Keywords.extract("steam engine steam")).containsExactly("steam", "engine").inOrder()

    @Test fun possessiveIsStripped() {
        assertThat(Keywords.extract("What are Darwin's finches?")).containsExactly("darwin", "finches").inOrder()
    }

    @Test fun curlyApostropheDoesNotLeaveFragments() {
        val k = Keywords.extract("Why don\u2019t birds migrate?")
        assertThat(k).doesNotContain("don")
        assertThat(k).containsExactly("birds", "migrate").inOrder()
        assertThat(Keywords.extract("Darwin\u2019s finches")).containsExactly("darwin", "finches").inOrder()
    }

    @Test fun normalizeHandlesPossessivesPerPhrase() {
        assertThat(Keywords.normalize("Darwin\u2019s Finches")).isEqualTo("darwin finches")
        assertThat(Keywords.normalize(" Darwin's ")).isEqualTo("darwin")
        assertThat(Keywords.normalize("rock 'n' roll")).isEqualTo("rock n roll")
    }

    @Test fun medicalLexicon() {
        assertThat(Medical.looksMedical("What dose of insulin is safe?")).isTrue()
        assertThat(Medical.looksMedical("How do I treat a Burn?")).isTrue()
        assertThat(Medical.looksMedical("How does a steam engine work?")).isFalse()
    }
}
