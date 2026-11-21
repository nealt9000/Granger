package app.granger.core.speech

import app.granger.core.speech.QuoteSpans.Span
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class QuoteSpansTest {
    @Test
    fun narrationThenAQuote() {
        assertEquals(listOf(Span("Margaret stepped forward.", false), Span("Edith is asleep. Is it anything I can do?", true)), (QuoteSpans.split("Margaret stepped forward. \"Edith is asleep. Is it anything I can do?\"")).toList())
    }

    @Test
    fun aQuoteThenNarrationThenAQuote() {
        assertEquals(listOf(Span("Odd,", true), Span("said he,", false), Span("very odd.", true)), (QuoteSpans.split("\"Odd,\" said he, \"very odd.\"")).toList())
    }

    @Test
    fun curlyQuotesSayWhichWayTheyGo() {
        assertEquals(listOf(Span("Can you hear that?", true), Span("she asked.", false)), (QuoteSpans.split("“Can you hear that?” she asked.")).toList())
    }

    @Test
    fun backToBackQuotesAreTwoSpeeches() {
        assertEquals(listOf(Span("Why?", true), Span("Because.", true)), (QuoteSpans.split("\"Why?\" \"Because.\"")).toList())
    }

    @Test
    fun noQuotesIsOneNarrationSpan() {
        assertEquals(listOf(Span("The water was calm.", false)).toSet(), (QuoteSpans.split("The water was calm.")).toSet())
    }

    @Test
    fun aSentenceThatStartsInsideAQuote() {
        assertEquals(listOf(Span("You stay.", true), Span("He left.", false)), (QuoteSpans.split("You stay.\" He left.", open = true)).toList())
    }

    @Test
    fun apostrophesAndBareMarksAreNotSpans() {
        assertEquals(listOf(Span("I'd never noticed it,", true), Span("— she said.", false)), (QuoteSpans.split("\"I'd never noticed it,\" — she said.", open = false)).toList())
        assertTrue((QuoteSpans.split("\"\" .")).isEmpty())
    }

    @Test
    fun quoteStateCarriesAcrossAParagraphsSentences() {
        assertEquals(listOf(false, true, false), (QuoteSpans.openAtStart(listOf("\"I'll go.", "You stay.\"", "He left."))).toList())
    }

    @Test
    fun aParagraphWhoseQuoteNeverClosesIsAllNarration() {
        assertEquals(listOf(false, false, false), (QuoteSpans.openAtStart(listOf("\"I'll go.", "You stay.", "He left."))).toList())
    }
}
