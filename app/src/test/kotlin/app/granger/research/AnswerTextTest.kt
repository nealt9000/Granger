package app.granger.research

import app.granger.research.AnswerText.Segment
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class AnswerTextTest {
    @Test fun spokenDropsChipsMarksAndListMarkers() {
        val answer = "Plants make **sugar** from light [1]. They use *chlorophyll* [2, 3].\n\n## How it works\n\n- Light is absorbed.\n- Water is split.\n1. Oxygen leaves."
        assertThat(AnswerText.spoken(answer)).isEqualTo(
            "Plants make sugar from light. They use chlorophyll.\n\nHow it works\n\nLight is absorbed.\n\nWater is split.\n\nOxygen leaves.",
        )
    }

    @Test fun medicalBannerIsSpokenFirstOnce() {
        assertThat(AnswerText.spoken("Rest and drink water [1].", medical = true))
            .isEqualTo("${AnswerText.MEDICAL}\n\nRest and drink water.")
        assertThat(AnswerText.spoken("Rest.", medical = false)).isEqualTo("Rest.")
    }

    @Test fun theWeakMatchHedgeIsSpokenAfterTheMedicalBanner() {
        assertThat(AnswerText.spoken("Rest [1].", medical = true, weak = true))
            .isEqualTo("${AnswerText.MEDICAL}\n\n${AnswerText.WEAK}\n\nRest.")
        assertThat(AnswerText.spoken("Rest.", weak = true)).isEqualTo("${AnswerText.WEAK}\n\nRest.")
    }

    @Test fun splitsCitations() {
        assertThat(AnswerText.segments("A [1] b [2, 3].")).containsExactly(
            Segment.Text("A "), Segment.Cite(1), Segment.Text(" b "), Segment.Cite(2), Segment.Cite(3), Segment.Text("."),
        ).inOrder()
    }

    @Test fun plainTextIsOneSegment() {
        assertThat(AnswerText.segments("No citations here")).containsExactly(Segment.Text("No citations here"))
    }

    @Test fun bracketsWithoutNumbersStayText() {
        assertThat(AnswerText.segments("See [a] and [1]")).containsExactly(
            Segment.Text("See [a] and "), Segment.Cite(1),
        ).inOrder()
    }

    @Test fun oversizedNumberStaysText() {
        assertThat(AnswerText.segments("ISBN [9780140449136] and [1, 99999999999] [2]")).containsExactly(
            Segment.Text("ISBN [9780140449136] and [1, 99999999999] "), Segment.Cite(2),
        ).inOrder()
    }

    @Test fun italicAndBoldRender() {
        assertThat(AnswerText.segments("Read *Hamlet* and **Lear** now, 2 * 3 and # head `x`")).containsExactly(
            Segment.Text("Read "), Segment.Text("Hamlet", italic = true), Segment.Text(" and "),
            Segment.Text("Lear", bold = true), Segment.Text(" now, 2 * 3 and # head `x`"),
        ).inOrder()
    }

    @Test fun citationsInsideItalicStillLink() {
        assertThat(AnswerText.segments("*As said [1] here* ok")).containsExactly(
            Segment.Text("As said ", italic = true), Segment.Cite(1, italic = true),
            Segment.Text(" here", italic = true), Segment.Text(" ok"),
        ).inOrder()
    }
}
