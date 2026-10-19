package app.granger.research

import app.granger.core.ai.ResearchReadiness
import app.granger.core.library.ChatTurn
import app.granger.core.library.TurnOutcome
import app.granger.core.research.Passage
import app.granger.core.research.SourceKind
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SettledTest {
    private val card = Passage(SourceKind.BOOK, "1", "Hamlet", location = "c1", text = "To be.")

    private fun state(outcome: TurnOutcome, shown: List<Passage>, running: Boolean = false): ResearchViewModel.State {
        val turn = ChatTurn(1, 1, "q", outcome, "a [1]", false, false, false, emptyList(), shown, emptyList(), 0)
        val ui = ResearchViewModel.TurnUi(turn, null, null, largeFallback = false, medical = false)
        return ResearchViewModel.State(1, "t", listOf(ui), running, ResearchReadiness.READY, false, null, emptyList())
    }

    @Test fun lastScrollWaitsForTheSourceCards() {
        assertThat(state(TurnOutcome.ANSWERED, emptyList()).settled).isTrue() // a final row with no cards (old rows)
        assertThat(state(TurnOutcome.PENDING, emptyList()).settled).isFalse()
        assertThat(state(TurnOutcome.ANSWERED, listOf(card), running = true).settled).isFalse()
        assertThat(state(TurnOutcome.ANSWERED, listOf(card)).settled).isTrue()
        assertThat(state(TurnOutcome.DECLINED, emptyList()).settled).isTrue()
    }
}
