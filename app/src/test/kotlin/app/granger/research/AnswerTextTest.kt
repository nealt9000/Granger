package app.granger.research

import app.granger.research.AnswerText.Segment
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class AnswerTextTest {
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
}
