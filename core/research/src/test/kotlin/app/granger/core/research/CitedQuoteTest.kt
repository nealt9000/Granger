package app.granger.core.research

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class CitedQuoteTest {
    private val matthew =
        "Therefore I say unto you, Take no thought for your life, what ye shall eat, or what ye shall drink. " +
            "Behold the fowls of the air: for they sow not, neither do they reap, nor gather into barns. " +
            "Consider the lilies of the field, how they grow; they toil not, neither do they spin. " +
            "Take therefore no thought for the morrow: for the morrow shall take thought for the things of itself. " +
            "Sufficient unto the day is the evil thereof."

    @Test fun picksTheSentenceSharingTheClaimsWords() {
        val answer = "Jesus says to take no thought for the morrow, since the morrow will take care of itself [1]."
        assertThat(CitedQuote.pick(answer, 1, matthew)!!.sentence)
            .isEqualTo("Take therefore no thought for the morrow: for the morrow shall take thought for the things of itself.")
    }

    @Test fun onlySentencesCitingNCount() {
        val answer = "The lilies of the field neither toil nor spin [2]. Take no thought for the morrow [1]."
        assertThat(CitedQuote.pick(answer, 2, matthew)!!.sentence).startsWith("Consider the lilies")
        assertThat(CitedQuote.pick(answer, 1, matthew)!!.sentence).startsWith("Take therefore")
        assertThat(CitedQuote.claims("Lilies grow [1, 2].\n- Barns are empty [3]", 3)).containsExactly("- Barns are empty [3]")
    }

    @Test fun noCitingSentenceUsesTheWholeAnswer() {
        assertThat(CitedQuote.pick("The fowls of the air do not gather into barns.", 1, matthew)!!.sentence).startsWith("Behold the fowls")
    }

    @Test fun noOverlapFallsBackToTheFirstSentence() {
        assertThat(CitedQuote.pick("Quantum chromodynamics [1].", 1, matthew)!!.sentence).startsWith("Therefore I say unto you")
    }

    @Test fun shortSentencesAreSkipped() {
        val text = "Evil thereof. Sufficient unto the day is the evil thereof, said he."
        assertThat(CitedQuote.pick("Evil thereof [1].", 1, text)!!.sentence).startsWith("Sufficient unto")
    }

    @Test fun longQuotesAreCutAtAWord() {
        val long = (1..80).joinToString(" ") { "word$it" } + "."
        val q = CitedQuote.pick("word5 [1]", 1, long)!!.sentence
        assertThat(q.length).isAtMost(CitedQuote.MAX_CHARS + 1)
        assertThat(q).endsWith("…")
        assertThat(long).startsWith(q.removeSuffix("…"))
    }

    @Test fun contextIsFortyCharsEachSide() {
        val q = CitedQuote.pick("Consider the lilies of the field [1].", 1, matthew)!!
        assertThat(q.before).hasLength(CitedQuote.CONTEXT)
        assertThat(q.after).hasLength(CitedQuote.CONTEXT)
        assertThat(matthew).contains(q.before + q.sentence + q.after)
        val first = CitedQuote.pick("Quantum [1]", 1, matthew)!!
        assertThat(first.before).isEmpty()
    }

    @Test fun accentsAndCaseFold() {
        val text = "Le café était fermé ce matin-là. Les élèves attendaient devant l'école depuis longtemps."
        assertThat(CitedQuote.pick("The ELEVES waited at the ecole [1].", 1, text)!!.sentence).startsWith("Les élèves")
    }

    @Test fun noWordsIsNull() {
        assertThat(CitedQuote.pick("x [1]", 1, "  … ")).isNull()
    }
}
