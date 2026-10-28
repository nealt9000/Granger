package app.granger.home

import app.granger.FakeZimArchive
import app.granger.book
import app.granger.core.library.AlbumSummary
import app.granger.core.library.BookChunk
import app.granger.core.library.BookHit
import app.granger.core.library.FeedKind
import app.granger.core.library.HomeFeed
import app.granger.core.library.LibrarySearch
import app.granger.core.library.PackStatus
import app.granger.core.library.SearchHit
import app.granger.core.library.TitleResults
import app.granger.core.zim.ZimHit
import app.granger.pack
import com.google.common.truth.Truth.assertThat
import java.time.LocalDate
import kotlinx.coroutines.test.runTest
import org.junit.Test

class FeedDrawerTest {
    private val day = LocalDate.of(2026, 10, 22)
    private val wikipedia = pack("u1", "Wikipedia").copy(name = "wikipedia_en_all")
    private val hamlet = book("book:granger:1", "Hamlet", author = "Shakespeare").copy(subjects = "Drama")
    private val odyssey = book("book:granger:2", "The Odyssey", author = "Homer").copy(subjects = "Epic & poetry")
    private val leaves = book("book:granger:3", "Leaves of Grass", author = "Whitman", coverPath = "/covers/leaves.jpg").copy(subjects = "Epic & poetry")

    private val dayHtml = """<html><head><title>October 22</title></head><body>
        <h2 id="Events">Events</h2><ul>${(1..6).joinToString("") { "<li>Event number $it happened.</li>" }}</ul>
        <h2 id="Births">Births</h2><ul><li>Birth one here.</li><li>Birth two here.</li></ul>
        <h2 id="Deaths">Deaths</h2><ul><li>Death one here.</li></ul></body></html>"""
    private val archive = FakeZimArchive(
        uuid = "u1",
        paths = setOf("index", "Cat"),
        random = "Cat",
        results = (1..8).map { ZimHit("Hit_$it", "Hit $it") },
        contents = mapOf("October_22" to dayHtml, "Cat" to "<html><head><title>Cat - Wikipedia</title></head><body></body></html>"),
    )
    private val chunks = mapOf(
        hamlet.id to (0..4).map { BookChunk(it.toLong(), hamlet.id, "ch$it.xhtml", it, it / 5.0) },
        leaves.id to (0..4).map { BookChunk(10L + it, leaves.id, "p$it.xhtml", it, it / 5.0) },
    )
    private var bookHits = listOf(
        BookHit(hamlet.id, "Hamlet", "Shakespeare", "ch2.xhtml", 0.4, "<b>stoic</b> calm"),
        BookHit(odyssey.id, "The Odyssey", "Homer", "ch1.xhtml", 0.1, ""),
    )
    private val librarySearch = object : LibrarySearch {
        override suspend fun titles(query: String) = TitleResults(emptyList())
        override suspend fun fullText(query: String) = listOf(SearchHit("u1", "Wikipedia", "Stoicism", "Stoicism"))
    }
    private val drawer = FeedDrawer(
        archiveOf = { if (it.id == "u1") archive else null },
        chunksOf = { chunks[it].orEmpty() },
        bookSearch = { bookHits },
        librarySearch = librarySearch,
    )

    private fun feed(kind: FeedKind, query: String = "", id: Long = 1) = HomeFeed(id, "x", kind, query, 0, 0)

    @Test fun randomFromAPackReadsTheTitleAndDropsWikipediasSuffix() = runTest {
        val drawn = drawer.draw(feed(FeedKind.RANDOM), Resolved("Wikipedia", packs = listOf(wikipedia)), day)!!
        assertThat(drawn.title).isEqualTo("Random from Wikipedia")
        // One random path from the fake, so one distinct card.
        assertThat(drawn.cards).containsExactly(Card("Cat", "Wikipedia", CardTarget.Article("u1", "Cat")))
    }

    @Test fun randomFromBooksDipsIntoPoetryAndSuggestsANovelFromTheStart() = runTest {
        val scope = Resolved("Granger Classics", books = listOf(hamlet, odyssey, leaves))
        val first = drawer.draw(feed(FeedKind.RANDOM), scope, day)!!
        val again = drawer.draw(feed(FeedKind.RANDOM), scope, day)!!
        assertThat(again).isEqualTo(first)
        assertThat(first.cards).hasSize(3)
        // A play is a suggestion: no chapter, so the reader opens it at the start or where it was left.
        val hamletCard = first.cards.first { it.title == "Hamlet" }
        assertThat(hamletCard.subtitle).isEqualTo("Shakespeare")
        assertThat(hamletCard.target).isEqualTo(CardTarget.Book(hamlet.id))
        // Poetry is for dipping into: a random chapter, and the cover as the card's picture.
        val leavesCard = first.cards.first { it.title == "Leaves of Grass" }
        assertThat(leavesCard.subtitle).matches("Chapter [1-5] · Whitman")
        assertThat((leavesCard.target as CardTarget.Book).href).matches("p[0-4]\\.xhtml")
        assertThat(leavesCard.imagePath).isEqualTo("/covers/leaves.jpg")
        val odysseyCard = first.cards.first { it.title == "The Odyssey" }
        assertThat(odysseyCard.subtitle).isEqualTo("Homer") // Not indexed yet: no chapter.
        assertThat(odysseyCard.target).isEqualTo(CardTarget.Book(odyssey.id))
        // Another day (or another feed) can draw a different chapter; the seeds differ.
        assertThat(FeedDrawer.seed(day, 1)).isNotEqualTo(FeedDrawer.seed(day.plusDays(1), 1))
        assertThat(FeedDrawer.seed(day, 1)).isNotEqualTo(FeedDrawer.seed(day, 2))
    }

    @Test fun onThisDayReadsTheDayArticlesSections() = runTest {
        val drawn = drawer.draw(feed(FeedKind.ON_THIS_DAY), Resolved("Wikipedia", packs = listOf(wikipedia)), day)!!
        assertThat(drawn.title).isEqualTo("October 22")
        assertThat(drawn.cards.map { it.subtitle }).containsExactly("Events", "Events", "Events", "Births", "Deaths").inOrder()
        assertThat(drawn.cards.map { it.title }.distinct()).hasSize(5)
        assertThat(drawn.cards.first().target).isEqualTo(CardTarget.Article("u1", "October_22", fragment = "Events"))
        assertThat(drawn.cards.last().title).isEqualTo("Death one here.")
        assertThat(drawer.draw(feed(FeedKind.ON_THIS_DAY), Resolved("Wikipedia", packs = listOf(wikipedia)), day)).isEqualTo(drawn)
    }

    @Test fun aPackWithoutTheDayArticleFallsBackToSearch() = runTest {
        val plain = FakeZimArchive(uuid = "u2", paths = setOf("index"), results = (1..8).map { ZimHit("Hit_$it", "Hit $it") })
        val drawer = FeedDrawer({ plain }, { emptyList() }, { emptyList() }, librarySearch)
        val pack = pack("u2", "Wikiquote")
        val drawn = drawer.draw(feed(FeedKind.ON_THIS_DAY), Resolved("Wikiquote", packs = listOf(pack)), day)!!
        assertThat(drawn.cards).hasSize(FeedDrawer.CARDS)
        assertThat(drawn.cards.map { it.subtitle }.distinct()).containsExactly("Wikiquote")
        assertThat(drawn.cards.map { it.title }.toSet()).hasSize(FeedDrawer.CARDS)
    }

    @Test fun userAndTagPagesAreNotDrawn() = runTest {
        val se = FakeZimArchive(
            uuid = "u3", paths = setOf("index", "users/7/apocalypse"), random = "users/7/apocalypse",
            results = listOf(ZimHit("users/7/apocalypse", "User apocalypse"), ZimHit("tags/end", "end"), ZimHit("questions/1/end", "The end")),
        )
        val drawer = FeedDrawer({ se }, { emptyList() }, { emptyList() }, librarySearch)
        val scope = Resolved("Worldbuilding", packs = listOf(pack("u3", "Worldbuilding")))
        assertThat(drawer.draw(feed(FeedKind.RANDOM), scope, day)).isNull()
        assertThat(drawer.draw(feed(FeedKind.SEARCH, "end"), scope, day)!!.cards.map { it.title }).containsExactly("The end")
    }

    @Test fun searchRotatesWithTheDayAndFiltersBooksToTheScope() = runTest {
        val scope = Resolved("Wikipedia", packs = listOf(wikipedia))
        val today = drawer.draw(feed(FeedKind.SEARCH, "stoicism"), scope, day)!!
        assertThat(today.title).isEqualTo("stoicism · Wikipedia")
        assertThat(today.cards).hasSize(FeedDrawer.CARDS)
        val tomorrow = drawer.draw(feed(FeedKind.SEARCH, "stoicism"), scope, day.plusDays(1))!!
        assertThat(tomorrow.cards).isNotEqualTo(today.cards)
        assertThat(drawer.draw(feed(FeedKind.SEARCH, "stoicism"), scope, day)).isEqualTo(today)

        val books = drawer.draw(feed(FeedKind.SEARCH, "stoicism"), Resolved("Classics", books = listOf(hamlet)), day)!!
        assertThat(books.cards).containsExactly(Card("Hamlet", "stoic calm", CardTarget.Book(hamlet.id, "ch2.xhtml", 0.4)))

        assertThat(drawer.draw(feed(FeedKind.SEARCH, "  "), scope, day)).isNull()
    }

    @Test fun theWholeLibrarySearchesPacksTogetherAndBooks() = runTest {
        val scope = Resolved("the library", packs = listOf(wikipedia, pack("u9", "Other")), books = listOf(hamlet, odyssey))
        val drawn = drawer.draw(feed(FeedKind.SEARCH, "stoicism"), scope, day)!!
        assertThat(drawn.cards.map { it.title }).containsExactly("Stoicism", "Hamlet", "The Odyssey")
    }

    @Test fun musicAndAudiobooksDrawAlbumsAndBooks() = runTest {
        val albums = (1..7).map { AlbumSummary("album:$it", "Album $it", "Artist", "", "", 1, 1, null, PackStatus.OK) }
        val drawn = drawer.draw(feed(FeedKind.RANDOM), Resolved("Music", albums = albums), day)!!
        assertThat(drawn.cards).hasSize(FeedDrawer.CARDS)
        assertThat(drawn.cards.map { it.target }.filterIsInstance<CardTarget.Album>()).hasSize(FeedDrawer.CARDS)
        val found = drawer.draw(feed(FeedKind.SEARCH, "Album 3"), Resolved("Music", albums = albums), day)!!
        assertThat(found.cards.map { it.title }).containsExactly("Album 3")
    }

    @Test fun articleCardsCarryTheThumbnailTheDrawerIsGiven() = runTest {
        val seen = mutableListOf<String>()
        val drawer = FeedDrawer({ archive }, { emptyList() }, { emptyList() }, librarySearch) { _, path, html ->
            seen += path
            if ("<title>" in html) "/cache/$path.jpg" else null
        }
        val scope = Resolved("Wikipedia", packs = listOf(wikipedia))
        val random = drawer.draw(feed(FeedKind.RANDOM), scope, day)!!
        assertThat(random.cards.single().imagePath).isEqualTo("/cache/Cat.jpg")
        val search = drawer.draw(feed(FeedKind.SEARCH, "owls"), scope, day)!!
        // Only the five kept hits are read for a picture; the fake's hit pages are empty, so none has one.
        assertThat(search.cards).hasSize(FeedDrawer.CARDS)
        assertThat(search.cards.map { it.imagePath }.toSet()).containsExactly(null)
        assertThat(seen.filter { it.startsWith("Hit_") }).hasSize(0)
    }

    @Test fun cardsRoundTripThroughJson() {
        val drawn = Drawn("October 22", listOf(Card("A", "Events", CardTarget.Article("u1", "October_22", "Events")), Card("B", "", CardTarget.Search("owls"))))
        assertThat(Cards.decode(Cards.encode(drawn))).isEqualTo(drawn.copy(version = Cards.VERSION))
        // A draw from before thumbnails (no version) is redrawn rather than shown.
        assertThat(Cards.decode("{\"title\":\"x\",\"cards\":[]}")).isNull()
        assertThat(Cards.decode("{\"title\":\"x\",\"cards\":[{\"title\":\"a\",\"subtitle\":\"\",\"target\":{\"kind\":\"Digest\"}}]}")).isNull()
        assertThat(Cards.decode("not json")).isNull()
    }
}
