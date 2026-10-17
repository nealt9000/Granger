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

    @Test fun medicalLexicon() {
        assertThat(Medical.looksMedical("What dose of insulin is safe?")).isTrue()
        assertThat(Medical.looksMedical("How do I treat a Burn?")).isTrue()
        assertThat(Medical.looksMedical("How does a steam engine work?")).isFalse()
    }
}
