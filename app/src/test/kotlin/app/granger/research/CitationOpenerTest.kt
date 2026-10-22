package app.granger.research

import app.granger.BookTarget
import app.granger.FakeBookshelf
import app.granger.FakeLibrary
import app.granger.FakeReadingSessions
import app.granger.FakeZimArchive
import app.granger.book
import app.granger.core.books.TextQuote
import app.granger.core.library.Audiobook
import app.granger.core.library.AudiobookChapter
import app.granger.core.library.MediaIndex
import app.granger.core.library.MediaOrigin
import app.granger.core.library.PackStatus
import app.granger.core.library.PassageAddress
import app.granger.core.research.Passage
import app.granger.core.research.SourceKind
import app.granger.music.FakeMediaLibrary
import app.granger.music.song
import app.granger.pack
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Test

class CitationOpenerTest {
    private val sessions = FakeReadingSessions()
    private val library = FakeLibrary()
    private val bookshelf = FakeBookshelf()
    private val media = FakeMediaLibrary()

    private fun TestScope.opener() =
        CitationOpener(sessions, library, bookshelf, MediaIndex(media, backgroundScope, UnconfinedTestDispatcher(testScheduler)), Dispatchers.Unconfined)

    private val rosetta = Passage(
        SourceKind.ARTICLE, "wiki", "Rosetta Stone", location = "A/Rosetta_Stone", anchor = "Decipherment",
        text = "The stone was found in 1799 by French soldiers. Champollion announced his decipherment of the hieroglyphs in 1822.",
    )

    private val emma = Passage(
        SourceKind.BOOK, "book:granger:emma", "Emma", location = "ch1.xhtml", progression = 0.25,
        text = "Emma Woodhouse, handsome, clever, and rich, seemed to unite some of the best blessings of existence. " +
            "She was the youngest of the two daughters of a most affectionate, indulgent father.",
    )

    private fun planets() = listOf("Mars, the Bringer of War", "Venus, the Bringer of Peace").mapIndexed { i, title ->
        song("p$i", title, album = "The Planets", albumArtists = "Gustav Holst")
    }

    private val jungleId = "audiobook:phone:jungle"

    private fun jungle(status: PackStatus = PackStatus.OK) {
        media.bookList.value = listOf(Audiobook(jungleId, MediaOrigin.PHONE, "jungle", "The Jungle Book", "Rudyard Kipling", "", "", null, 0L, status, 0L))
        media.chapterList.value = listOf(
            AudiobookChapter(jungleId, 0, "Mowgli's Brothers", "t1", 0L, 1L),
            AudiobookChapter(jungleId, 1, "Kaa's Hunting", "t2", 0L, 1L),
        )
    }

    @Test fun articleOpensAtItsSectionWithTheQuote() = runTest {
        library.packList.value = listOf(pack("wiki"))
        library.archives["wiki"] = FakeZimArchive("wiki", paths = setOf("A/Rosetta_Stone"))
        val target = opener().open(rosetta, "Champollion deciphered the hieroglyphs in 1822 [1].", 1) as CitationTarget.Article
        assertThat(target.fragment).isEqualTo("Decipherment")
        assertThat(target.quote).isEqualTo("Champollion announced his decipherment of the hieroglyphs in 1822.")
        assertThat(target.moved).isFalse()
        val tab = sessions.tabs.value.single { it.id == target.tabId }
        assertThat(tab.packId to tab.path).isEqualTo("wiki" to "A/Rosetta_Stone")
        assertThat(tab.title).isEqualTo("Rosetta Stone")
    }

    @Test fun aMovedArticleOpensThePackMainPageWithTheNote() = runTest {
        library.packList.value = listOf(pack("wiki", title = "Wikipedia"))
        library.archives["wiki"] = FakeZimArchive("wiki", paths = setOf("A/Other"))
        val target = opener().open(rosetta, "Champollion [1].", 1) as CitationTarget.Article
        assertThat(target).isEqualTo(CitationTarget.Article(target.tabId, null, null, moved = true))
        val tab = sessions.tabs.value.single { it.id == target.tabId }
        assertThat(tab.path to tab.title).isEqualTo("" to "Wikipedia")
    }

    @Test fun aCardOpensTheMainPage() = runTest {
        library.packList.value = listOf(pack("wiki"))
        val target = opener().open(rosetta.copy(kind = SourceKind.CARD), "[1]", 1) as CitationTarget.Article
        assertThat(target.fragment).isNull()
        assertThat(target.moved).isFalse()
        assertThat(sessions.tabs.value.single { it.id == target.tabId }.path).isEmpty()
    }

    @Test fun aBookOpensAtTheBestSentenceWithContext() = runTest {
        bookshelf.bookList.value = listOf(book("book:granger:emma", title = "Emma"))
        val target = opener().open(emma, "Emma was the youngest daughter of an indulgent father [1].", 1) as CitationTarget.Book
        val sentence = "She was the youngest of the two daughters of a most affectionate, indulgent father."
        val before = emma.text.substringBefore(sentence).takeLast(40)
        assertThat(target.at).isEqualTo(BookTarget("ch1.xhtml", 0.25, TextQuote(before, sentence, "")))
        // A bare address (from read-aloud or a plan) has no context, just the quote.
        val bare = opener().open(PassageAddress.Book("book:granger:emma", "ch1.xhtml", 0.25, sentence)) as CitationTarget.Book
        assertThat(bare.at?.quote).isEqualTo(TextQuote("", sentence, ""))
    }

    @Test fun anUnplacedBookOpensWhereItWasLeft() = runTest {
        bookshelf.bookList.value = listOf(book("book:granger:emma", title = "Emma"))
        val selection = emma.copy(kind = SourceKind.SELECTION, location = "")
        assertThat((opener().open(selection, "[1]", 1) as CitationTarget.Book).at).isNull()
        assertThat((opener().open(emma.copy(kind = SourceKind.CARD), "[1]", 1) as CitationTarget.Book).at).isNull()
    }

    @Test fun aSongOpensItsAlbumAtTheTrack() = runTest {
        media.trackList.value = planets()
        val key = planets().first().albumKey
        val card = Passage(SourceKind.MEDIA, "album:$key", "The Planets", location = planets()[1].id, text = "x")
        assertThat(opener().open(card, "[1]", 1)).isEqualTo(CitationTarget.Album(key, planets()[1].id))
        assertThat(opener().open(PassageAddress.Track(planets()[0].id))).isEqualTo(CitationTarget.Album(key, planets()[0].id))
        assertThat(opener().open(card.copy(location = ""), "[1]", 1)).isEqualTo(CitationTarget.Album(key))
    }

    @Test fun aGoneSongOpensTheAlbum() = runTest {
        media.trackList.value = planets()
        val key = planets().first().albumKey
        val card = Passage(SourceKind.MEDIA, "album:$key", "The Planets", location = "track:phone:gone", text = "x")
        // A bare address can't tell which album the song was on; the card can.
        assertThat(opener().open(PassageAddress.Track("track:phone:gone"))).isEqualTo(CitationTarget.Gone)
        assertThat(opener().open(card, "[1]", 1)).isEqualTo(CitationTarget.Album(key))
    }

    @Test fun aChapterOpensItsAudiobookAtTheChapter() = runTest {
        jungle()
        val card = Passage(SourceKind.MEDIA, jungleId, "The Jungle Book", location = "chapter:1", text = "x")
        assertThat(opener().open(card, "[1]", 1)).isEqualTo(CitationTarget.Audiobook(jungleId, 1))
        assertThat(opener().open(card.copy(location = "chapter:9"), "[1]", 1)).isEqualTo(CitationTarget.Audiobook(jungleId))
        assertThat(opener().open(card.copy(location = ""), "[1]", 1)).isEqualTo(CitationTarget.Audiobook(jungleId))
    }

    @Test fun aMissingSourceIsGone() = runTest {
        jungle(PackStatus.MISSING)
        library.packList.value = listOf(pack("wiki", status = PackStatus.MISSING))
        bookshelf.bookList.value = listOf(book("book:granger:emma", status = PackStatus.CORRUPT))
        val o = opener()
        assertThat(o.open(rosetta, "", 1)).isEqualTo(CitationTarget.Gone)
        assertThat(o.open(emma, "", 1)).isEqualTo(CitationTarget.Gone)
        assertThat(o.open(PassageAddress.Pack("nowhere"))).isEqualTo(CitationTarget.Gone)
        assertThat(o.open(PassageAddress.Album("nowhere"))).isEqualTo(CitationTarget.Gone)
        assertThat(o.open(PassageAddress.Audiobook(jungleId, 1))).isEqualTo(CitationTarget.Gone)
        assertThat(sessions.tabs.value).isEmpty()
    }
}
