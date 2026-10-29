package app.granger.research

import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextIndent
import androidx.compose.ui.unit.em
import app.granger.research.AnswerText.Block
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

    // Plan U3c Decision 8: headings, lists and code, laid out.

    @Test fun headingsAreBoldLinesWithoutTheirHashes() {
        assertThat(AnswerText.blocks("## How it works\nLight [1].")).containsExactly(
            Block.Heading(listOf(Segment.Text("How it works"))),
            Block.Line(listOf(Segment.Text("Light "), Segment.Cite(1), Segment.Text("."))),
        ).inOrder()
        // No space after the hashes, or four spaces before: not a heading.
        assertThat(AnswerText.blocks("#1 seed")).containsExactly(Block.Line(listOf(Segment.Text("#1 seed"))))
    }

    @Test fun bulletsOfEveryKindBecomeOneMarker() {
        assertThat(AnswerText.blocks("- Light\n* Water\n• Air\n+ Soil")).containsExactly(
            Block.Item("•", 0, listOf(Segment.Text("Light"))),
            Block.Item("•", 0, listOf(Segment.Text("Water"))),
            Block.Item("•", 0, listOf(Segment.Text("Air"))),
            Block.Item("•", 0, listOf(Segment.Text("Soil"))),
        ).inOrder()
    }

    @Test fun numbersStayAsTypedAndNestingIsTwoSpacesALevel() {
        assertThat(AnswerText.blocks("1. Gather\n  - dry wood\n    - tinder\n2) Light")).containsExactly(
            Block.Item("1.", 0, listOf(Segment.Text("Gather"))),
            Block.Item("•", 1, listOf(Segment.Text("dry wood"))),
            Block.Item("•", 2, listOf(Segment.Text("tinder"))),
            Block.Item("2)", 0, listOf(Segment.Text("Light"))),
        ).inOrder()
    }

    @Test fun emphasisAtAnItemsStartIsNotABullet() {
        assertThat(AnswerText.blocks("*Note* this\n**Bold** start\n2 * 3 = 6")).containsExactly(
            Block.Line(listOf(Segment.Text("Note", italic = true), Segment.Text(" this"))),
            Block.Line(listOf(Segment.Text("Bold", bold = true), Segment.Text(" start"))),
            Block.Line(listOf(Segment.Text("2 * 3 = 6"))),
        ).inOrder()
    }

    @Test fun citationsInListItemsAreUntouched() {
        assertThat(AnswerText.blocks("- *Oak* [1, 2]\n3. Ash [3].")).containsExactly(
            Block.Item("•", 0, listOf(Segment.Text("Oak", italic = true), Segment.Text(" "), Segment.Cite(1), Segment.Cite(2))),
            Block.Item("3.", 0, listOf(Segment.Text("Ash "), Segment.Cite(3), Segment.Text("."))),
        ).inOrder()
    }

    @Test fun inlineCodeIsTakenAsTyped() {
        assertThat(AnswerText.blocks("Run `ls *.txt` or `a[1]` then [2]; a lone ` stays")).containsExactly(
            Block.Line(
                listOf(
                    Segment.Text("Run "), Segment.Text("ls *.txt", code = true), Segment.Text(" or "),
                    Segment.Text("a[1]", code = true), Segment.Text(" then "), Segment.Cite(2), Segment.Text("; a lone ` stays"),
                ),
            ),
        )
    }

    @Test fun theVoiceReadsAsBefore() {
        // spoken() doesn't look for code: the backticks it has always kept are kept.
        assertThat(AnswerText.spoken("## Tools\n- Use `grep` [1].\n2. Then **sed**.")).isEqualTo("Tools\n\nUse `grep`.\n\nThen sed.")
        assertThat(AnswerText.segments("Use `grep` [1]")).containsExactly(Segment.Text("Use `grep` "), Segment.Cite(1)).inOrder()
    }

    @Test fun eachItemHangsFromItsMarker() {
        val code = SpanStyle(fontFamily = FontFamily.Monospace)
        val text = annotatedAnswer("## Steps\nFirst:\n1. Cut `wood`\n  - dry [1]\nDone.", SpanStyle(), code) {}
        assertThat(text.text).isEqualTo("Steps\nFirst:1.Cut wood•dry  1 Done.")
        val items = text.paragraphStyles.map { text.text.substring(it.start, it.end) to it.item.textIndent }
        assertThat(items).containsExactly(
            "1.Cut wood" to TextIndent(0.em, 1.6.em),
            "•dry  1 " to TextIndent(1.25.em, 2.85.em),
        ).inOrder()
        val styled = text.spanStyles.map { text.text.substring(it.start, it.end) to it.item }
        assertThat(styled).contains("Steps" to SpanStyle(fontWeight = FontWeight.Bold))
        assertThat(styled).contains("wood" to code)
        assertThat(text.getLinkAnnotations(0, text.length).map { it.item.toString() }.single()).contains("cite:1")
    }
}
