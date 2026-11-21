package app.granger.core.speech

import app.granger.core.speech.QuoteSpans.Span
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class QuoteSpansTest {
    @Test
    fun narrationThenAQuote() {
        assertThat(QuoteSpans.split("Margaret stepped forward. \"Edith is asleep. Is it anything I can do?\""))
            .containsExactly(Span("Margaret stepped forward.", false), Span("Edith is asleep. Is it anything I can do?", true)).inOrder()
    }

    @Test
    fun aQuoteThenNarrationThenAQuote() {
        assertThat(QuoteSpans.split("\"Odd,\" said he, \"very odd.\""))
            .containsExactly(Span("Odd,", true), Span("said he,", false), Span("very odd.", true)).inOrder()
    }

    @Test
    fun curlyQuotesSayWhichWayTheyGo() {
        assertThat(QuoteSpans.split("“Can you hear that?” she asked."))
            .containsExactly(Span("Can you hear that?", true), Span("she asked.", false)).inOrder()
    }

    @Test
    fun backToBackQuotesAreTwoSpeeches() {
        assertThat(QuoteSpans.split("\"Why?\" \"Because.\""))
            .containsExactly(Span("Why?", true), Span("Because.", true)).inOrder()
    }

    @Test
    fun noQuotesIsOneNarrationSpan() {
        assertThat(QuoteSpans.split("The water was calm.")).containsExactly(Span("The water was calm.", false))
    }

    @Test
    fun aSentenceThatStartsInsideAQuote() {
        assertThat(QuoteSpans.split("You stay.\" He left.", open = true))
            .containsExactly(Span("You stay.", true), Span("He left.", false)).inOrder()
    }

    @Test
    fun apostrophesAndBareMarksAreNotSpans() {
        assertThat(QuoteSpans.split("\"I'd never noticed it,\" — she said.", open = false))
            .containsExactly(Span("I'd never noticed it,", true), Span("— she said.", false)).inOrder()
        assertThat(QuoteSpans.split("\"\" .")).isEmpty()
    }

    @Test
    fun quoteStateCarriesAcrossAParagraphsSentences() {
        assertThat(QuoteSpans.openAtStart(listOf("\"I'll go.", "You stay.\"", "He left."))).containsExactly(false, true, false).inOrder()
    }

    @Test
    fun aParagraphWhoseQuoteNeverClosesIsAllNarration() {
        assertThat(QuoteSpans.openAtStart(listOf("\"I'll go.", "You stay.", "He left."))).containsExactly(false, false, false).inOrder()
    }
}
