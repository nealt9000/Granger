package app.granger.search

import app.granger.UiText
import app.granger.core.library.BookHit
import app.granger.core.research.BestMatches
import app.granger.core.research.Passage
import app.granger.core.research.SourceKind
import app.granger.core.research.SourceMeta
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Search's rows in words, shared with the iPhone since Plan I7 Task 7c (Android's own tests still run through SearchRows). */
class SearchLinesTest {
    private fun media(id: String) = Passage(SourceKind.MEDIA, id, "The Planets", packTitle = "Your music", location = "", text = "Album: The Planets")

    @Test fun aMediaCardSaysWhereItIsInThePhonesWordsAndHasItsOwnKind() {
        assertEquals(UiText.Res("search_your_music"), SearchLines.matchSource(media("album:holst|the planets")))
        assertEquals(UiText.Res("search_your_audiobooks"), SearchLines.matchSource(media("librivox-123")))
        assertEquals(SearchLines.MatchKind.MUSIC, SearchLines.matchKind(media("album:holst|the planets")))
        assertEquals(SearchLines.MatchKind.AUDIOBOOK, SearchLines.matchKind(media("librivox-123")))
        assertEquals(UiText.Res("search_kind_music"), SearchLines.kindLabel(SearchLines.MatchKind.MUSIC))
        assertEquals(UiText.Res("search_kind_audiobook"), SearchLines.kindLabel(SearchLines.MatchKind.AUDIOBOOK))
        // An article keeps its pack, a book its collection when it has no pack.
        val article = Passage(SourceKind.ARTICLE, "wikimed", "Burn", packTitle = "WikiMed", location = "Burn", text = "x")
        assertEquals(UiText.Raw("WikiMed"), SearchLines.matchSource(article))
        assertEquals(SearchLines.MatchKind.ARTICLE, SearchLines.matchKind(article))
        val book = Passage(SourceKind.BOOK, "book:1", "Emma", packTitle = "", location = "ch1", text = "x", meta = SourceMeta(collection = "My books"))
        assertEquals(UiText.Raw("My books"), SearchLines.matchSource(book))
        assertEquals(SearchLines.MatchKind.BOOK, SearchLines.matchKind(book))
    }

    @Test fun aMatchsLineIsItsSentenceInQuotesWithTheKeywordsBold() {
        val text = "Intro. Cool the burn with running water."
        val match = BestMatches.Match(Passage(SourceKind.ARTICLE, "p", "Burn", location = "Burn", text = text), text.indexOf("Cool") until text.length)
        val line = SearchLines.matchLine(match)
        assertEquals("“Cool the burn with running water.”", line)
        val bold = SearchLines.highlights(line, listOf("burn", "cafe"))
        assertEquals(listOf("burn"), bold.map { line.substring(it) })
        assertEquals(listOf("Burns", "BURN", "café"), SearchLines.highlights("Burns heal; the BURN of a café.", listOf("burn", "cafe")).map { "Burns heal; the BURN of a café.".substring(it) })
        // No sentence: the passage's first 220 characters.
        val long = "a".repeat(300)
        assertEquals("“${"a".repeat(220)}”", SearchLines.matchLine(BestMatches.Match(Passage(SourceKind.ARTICLE, "p", "A", location = "A", text = long), null)))
    }

    @Test fun aBookRowsLinesAndASnippetsBoldWords() {
        val emma = BookHit("book:1", "Emma", "Jane Austen", "page/12", 0.0, "", matches = 3)
        assertEquals(UiText.Plural("search_book_matches_by", 3, 3, "Jane Austen"), SearchLines.bookLine(emma))
        assertEquals(UiText.Res("search_page", 12), SearchLines.bookPage(emma))
        assertNull(SearchLines.bookPage(emma.copy(href = "ch2.xhtml")))
        val (plain, bold) = SearchLines.snippetBold("the <b>apocalyptic</b> visions &amp; more")
        assertEquals("the apocalyptic visions & more", plain)
        assertEquals(listOf("apocalyptic"), bold.map { plain.substring(it) })
        assertTrue(SearchLines.snippetMatches("the <b>apocalyptic</b> visions", "apocalypse"))
        assertFalse(SearchLines.snippetMatches("Categories: 1st-century books", "apocalypse"))
        assertEquals(UiText.Res("search_best_more"), SearchLines.bestMore(open = false))
        assertEquals(UiText.Res("search_did_you_mean", "Dhammapada"), SearchLines.didYouMean("Dhammapada"))
    }
}
