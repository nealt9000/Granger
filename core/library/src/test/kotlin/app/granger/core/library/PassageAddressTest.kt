package app.granger.core.library

import app.granger.core.library.PassageAddress.Album
import app.granger.core.library.PassageAddress.Article
import app.granger.core.library.PassageAddress.Audiobook
import app.granger.core.library.PassageAddress.Book
import app.granger.core.library.PassageAddress.Pack
import app.granger.core.library.PassageAddress.Track
import app.granger.core.research.Passage
import app.granger.core.research.SourceKind
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test

class PassageAddressTest {
    private fun roundTrip(a: PassageAddress) = assertWithMessage(a.format()).that(PassageAddress.parse(a.format())).isEqualTo(a)

    @Test fun everyKindRoundTrips() {
        listOf(
            Article("wikipedia_en_top_nopic", "A/Rosetta_Stone", "Decipherment", "Champollion announced"),
            Article("wikipedia_en_top_nopic", "A/Rosetta_Stone"),
            Pack("wikem_en_all_maxi"),
            Book("book:granger:kjv", "OEBPS/matthew.xhtml", 0.42, "Take therefore no thought for the morrow"),
            Book("book:import:ab12", "", 0.0),
            Album("holst|the planets"),
            Track("track:phone:ab12", 151_000),
            Audiobook("audiobook:librivox:42", 3, 90_500),
            Audiobook("audiobook:librivox:42", 0),
            Audiobook("audiobook:librivox:42"),
        ).forEach(::roundTrip)
        assertThat(Audiobook("audiobook:librivox:42").format()).isEqualTo("audiobook:librivox:42")
        assertThat(Article("p", "A/X", "s", "q").format()).isEqualTo("article:p/A/X#s~q")
        assertThat(Book("book:granger:kjv", "t.xhtml", 0.5).format()).isEqualTo("book:granger:kjv/t.xhtml@0.5")
        assertThat(Pack("p").format()).isEqualTo("pack:p")
    }

    @Test fun awkwardIdsAndQuotesRoundTrip() {
        listOf(
            Book("book:archive-org:a/b:c", "Text/ch 1#x.xhtml", 1.0, "50% ~ at@home? #1"),
            Book("book:import:x", "a.xhtml", 0.0, "q"),
            Article("p%q", "A/100% cotton #2 ~ x@y?.html", "sec/1", "naïve ~ café @ noon?"),
            Article("p", "", null, null),
            Album("a/b@c~d"),
        ).forEach(::roundTrip)
        assertThat(Article("p", "A/a b", null, "x y").format()).isEqualTo("article:p/A/a b~x y")
    }

    @Test fun mediaAddressesPassThrough() {
        assertThat(Track("track:phone:ab12", 7).format()).isEqualTo(MediaAddress.track("track:phone:ab12", 7))
        assertThat(PassageAddress.parse("audiobook:phone:cd34@3/90500")).isEqualTo(Audiobook("audiobook:phone:cd34", 3, 90_500))
        assertThat(PassageAddress.parse("track:phone:ab12@151000")).isEqualTo(Track("track:phone:ab12", 151_000))
    }

    @Test fun junkParsesToNull() {
        for (s in listOf(
            "", "article:", "article:/A/X", "article:p", "article:p/A/%zz", "article:p/A/X~%4", "pack:", "album:",
            "book:", "book:x/a@0.5", "book:granger:k/a", "book:granger:k/a@2", "book:granger:k/a@-0.1",
            "book:granger:k/a@NaN", "book:granger:k/a@x", "track:x", "audiobook:x@1", "audiobook:", "nope:x", "x",
        )) {
            assertWithMessage(s).that(PassageAddress.parse(s)).isNull()
        }
    }

    @Test fun ofMapsEachPassageKind() {
        fun p(kind: SourceKind, id: String, location: String = "", anchor: String? = null, progression: Double = 0.0) =
            Passage(kind, id, "T", location = location, anchor = anchor, progression = progression, text = "x")
        assertThat(PassageAddress.of(p(SourceKind.ARTICLE, "pk", "A/X", "s"), "q")).isEqualTo(Article("pk", "A/X", "s", "q"))
        assertThat(PassageAddress.of(p(SourceKind.CARD, "pk"), "q")).isEqualTo(Pack("pk"))
        assertThat(PassageAddress.of(p(SourceKind.CARD, "book:granger:b"), "q")).isEqualTo(Book("book:granger:b", "", 0.0))
        assertThat(PassageAddress.of(p(SourceKind.BOOK, "book:granger:b", "c.xhtml", progression = 0.3), "q"))
            .isEqualTo(Book("book:granger:b", "c.xhtml", 0.3, "q"))
        assertThat(PassageAddress.of(p(SourceKind.SELECTION, "book:granger:b", "c.xhtml", progression = 0.3)))
            .isEqualTo(Book("book:granger:b", "c.xhtml", 0.3))
        assertThat(PassageAddress.of(p(SourceKind.SELECTION, "book:granger:b", progression = 0.3)))
            .isEqualTo(Book("book:granger:b", "", 0.0))
        assertThat(PassageAddress.of(p(SourceKind.SELECTION, "pk", "A/X", "s"))).isEqualTo(Article("pk", "A/X", "s"))
        assertThat(PassageAddress.of(p(SourceKind.MEDIA, "album:holst", "track:phone:t1"))).isEqualTo(Track("track:phone:t1"))
        assertThat(PassageAddress.of(p(SourceKind.MEDIA, "album:holst"))).isEqualTo(Album("holst"))
        assertThat(PassageAddress.of(p(SourceKind.MEDIA, "audiobook:librivox:42", "chapter:4"))).isEqualTo(Audiobook("audiobook:librivox:42", 4))
        assertThat(PassageAddress.of(p(SourceKind.MEDIA, "audiobook:librivox:42"))).isEqualTo(Audiobook("audiobook:librivox:42"))
    }

    @Test fun citedPicksTheQuoteForTextKindsOnly() {
        val text = "The stone was found in 1799. Champollion read its hieroglyphs in 1822 after years of work."
        val article = Passage(SourceKind.ARTICLE, "pk", "Rosetta Stone", location = "A/Rosetta_Stone", anchor = "s", text = text)
        assertThat(PassageAddress.cited(article, "Champollion read the hieroglyphs [1].", 1))
            .isEqualTo(Article("pk", "A/Rosetta_Stone", "s", "Champollion read its hieroglyphs in 1822 after years of work."))
        assertThat(PassageAddress.cited(article.copy(kind = SourceKind.CARD), "Champollion [1]", 1)).isEqualTo(Pack("pk"))
        val turn = ChatTurn(1, 1, "q", TurnOutcome.ANSWERED, "Found in 1799 [1]. Read in 1822 [2].", false, false, false,
            emptyList(), listOf(article, article.copy(sourceId = "pk2")), emptyList(), 0L)
        assertThat(turn.addresses().map { (it as Article).quote })
            .containsExactly("The stone was found in 1799.", "Champollion read its hieroglyphs in 1822 after years of work.").inOrder()
    }
}
