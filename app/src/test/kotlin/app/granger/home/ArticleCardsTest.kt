package app.granger.home

import app.granger.Briefly
import app.granger.FakeZimArchive
import app.granger.core.zim.ZimArchive
import app.granger.core.zim.ZimItem
import app.granger.side
import com.google.common.truth.Truth.assertThat
import java.io.File
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ArticleCardsTest {
    @get:Rule val tmp = TemporaryFolder()

    private val fake = FakeZimArchive(
        uuid = "top", redirects = mapOf("Aeroplane" to "Airplane"),
        contents = mapOf("Airplane" to "<html><head><title>Airplane</title></head><body><p>An airplane is a fixed-wing aircraft. It flies. It lands. It taxis.</p></body></html>"),
    )
    private var reads = 0
    private val archive = object : ZimArchive by fake {
        override fun read(path: String): ZimItem? { reads++; return fake.read(path) }
    }

    private fun cards() = ArticleCards(File(tmp.root, "cards.db"), ArticleThumbnails(File(tmp.root, "thumbs")))

    @Test fun aCardIsMadeOnceAndRememberedAcrossRunsOfTheApp() {
        val first = cards()
        val card = first.card(archive, "Airplane")!!
        assertThat(card.title).isEqualTo("Airplane")
        assertThat(card.summary).isEqualTo("An airplane is a fixed-wing aircraft. It flies.")
        assertThat(card.thumbnail).isNull()
        assertThat(first.card(archive, "Airplane")).isEqualTo(card)
        assertThat(reads).isEqualTo(1)
        // The app starts again: the card comes from the file, and the article isn't read.
        assertThat(cards().card(archive, "Airplane")).isEqualTo(card)
        assertThat(reads).isEqualTo(1)
        // A longer summary, or another picture size, is another card.
        assertThat(cards().card(archive, "Airplane", sentences = 3)!!.summary).isEqualTo("An airplane is a fixed-wing aircraft. It flies. It lands.")
        assertThat(reads).isEqualTo(2)
        // An article that can't be read gives nothing, and nothing is kept for it.
        assertThat(cards().card(archive, "Nowhere")).isNull()
    }

    @Test fun readsRunSideBySideInOrderAndAnIndexListIsKeptForAMoment() = runBlocking<Unit> {
        var running = 0
        var most = 0
        val out = (1..12).toList().side(limit = 3) { n ->
            synchronized(this@ArticleCardsTest) { running++; most = maxOf(most, running) }
            delay(20)
            synchronized(this@ArticleCardsTest) { running-- }
            n * 2
        }
        assertThat(out).containsExactlyElementsIn((1..12).map { it * 2 }).inOrder()
        assertThat(most).isAtMost(3)
        assertThat(most).isGreaterThan(1)

        var now = 0L
        var made = 0
        val brief = Briefly<String>(millis = 3_000) { now }
        assertThat(brief.get { made++; "a" }).isEqualTo("a")
        now = 2_999
        assertThat(brief.get { made++; "b" }).isEqualTo("a")
        now = 3_000
        assertThat(brief.get { made++; "c" }).isEqualTo("c")
        assertThat(made).isEqualTo(2)
    }
}
