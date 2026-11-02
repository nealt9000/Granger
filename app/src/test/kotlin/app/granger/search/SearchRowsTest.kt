package app.granger.search

import app.granger.core.library.ArtistHit
import app.granger.core.library.Audiobook
import app.granger.core.library.BookHit
import app.granger.core.library.MediaNames
import app.granger.core.library.MediaOrigin
import app.granger.core.library.PackStatus
import app.granger.music.song
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.granger.UiText
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The second lines of music and audiobook results, as plan M3's copy fixes them. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SearchRowsTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private fun UiText?.text() = this?.resolve(context).orEmpty()

    @Test fun rowsUseThePlannedCopy() {
        assertThat(SearchRows.artist(ArtistHit("Gustav Holst", 3, null)).text()).isEqualTo("Artist · 3 albums")
        assertThat(SearchRows.artist(ArtistHit("Gustav Holst", 1, null)).text()).isEqualTo("Artist · 1 album")
        val mars = song("1", "Mars", album = "The Planets", albumArtists = "Gustav Holst", artists = "London Symphony Orchestra")
        assertThat(MediaNames.albums(listOf(mars)).single().let(SearchRows::album).text()).isEqualTo("Album · Gustav Holst")
        assertThat(SearchRows.song(mars).text()).isEqualTo("Song · London Symphony Orchestra · The Planets")
        val book = Audiobook("audiobook:phone:1", MediaOrigin.PHONE, "1", "The Jungle Book", "Rudyard Kipling", "", "", null, 0L, PackStatus.OK, 0L)
        assertThat(SearchRows.audiobook(book).text()).isEqualTo("Rudyard Kipling")
        assertThat(SearchRows.audiobook(book.copy(narrator = "Kara Shallenberg")).text()).isEqualTo("Rudyard Kipling · read by Kara Shallenberg")
    }

    @Test fun titleOnlyNamesThreeAndCountsTheRest() {
        assertThat(SearchRows.titleOnly(emptyList())).isNull()
        assertThat(SearchRows.titleOnly(listOf("A Library of Knots")).text())
            .isEqualTo("A Library of Knots has no text search, so only its titles were searched.")
        assertThat(SearchRows.titleOnly(listOf("A", "B")).text())
            .isEqualTo("A, B have no text search, so only their titles were searched.")
        assertThat(SearchRows.titleOnly(listOf("A", "B", "C", "D", "E")).text())
            .isEqualTo("A, B, C and 2 more have no text search, so only their titles were searched.")
    }

    @Test fun showAllSaysHowManyAndOwnsUpToACap() {
        assertThat(SearchRows.showAll(SearchGroup.TITLES, 12, capped = false).text()).isEqualTo("Show all 12 titles")
        assertThat(SearchRows.showAll(SearchGroup.SONGS, 25, capped = true).text()).isEqualTo("Show the first 25 songs")
        assertThat(SearchRows.showFewer(SearchGroup.FULL_TEXT).text()).isEqualTo("Show fewer article matches")
        assertThat(SearchRows.showAll(SearchGroup.BOOKS, 9, capped = false).text()).isEqualTo("Show all 9 books")
    }

    @Test fun aBookSaysHowManyPlacesMatched() {
        val emma = BookHit("b:emma", "Emma", "Jane Austen", "ch2", 0.0, "", matches = 3)
        assertThat(SearchRows.bookLine(emma).text()).isEqualTo("Jane Austen · 3 matches")
        assertThat(SearchRows.bookLine(emma.copy(matches = 1)).text()).isEqualTo("Jane Austen")
        assertThat(SearchRows.bookLine(emma.copy(author = "")).text()).isEqualTo("3 matches")
    }

    @Test fun aSnippetMatchesWhenItHoldsAQueryWordOrAMark() {
        // libkiwix's mark counts, stemmed or not.
        assertThat(SearchRows.snippetMatches("the <b>apocalyptic</b> visions", "apocalypse")).isTrue()
        // Case and accents don't count, and tags don't hide a word.
        assertThat(SearchRows.snippetMatches("Ève <i>Apocalypse</i> now", "apocalypse eve")).isTrue()
        assertThat(SearchRows.snippetMatches("APOCALYPSE", "apocalypse")).isTrue()
        assertThat(SearchRows.snippetMatches("Caf&eacute; &amp; apocalypse", "Apocalypse")).isTrue()
        // Page chrome without the query is not a match.
        assertThat(SearchRows.snippetMatches("Categories: 1st-century books | Authority control: VIAF", "apocalypse")).isFalse()
        assertThat(SearchRows.snippetMatches("Reputation 1,204 <span>Badges</span>", "apocalypse")).isFalse()
        assertThat(SearchRows.snippetMatches("<b> </b>menu", "apocalypse")).isFalse()
    }
}
