package app.granger.theme

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.unit.dp
import app.granger.shell.RailEntry
import app.granger.shell.RailTabUi
import app.granger.shell.mediaKind
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MediaTileTest {
    @get:Rule val compose = createComposeRule()

    @Test fun smallTilesShowTheInitialAndLargeOnesTheWords() {
        assertThat(mediaTileText("Kind of Blue", 44.dp)).isEqualTo("K")
        assertThat(mediaTileText("  the wind", 36.dp)).isEqualTo("T")
        assertThat(mediaTileText("(What's the Story)", 48.dp)).isEqualTo("W")
        assertThat(mediaTileText("1984", 40.dp)).isEqualTo("1")
        assertThat(mediaTileText("Kind of Blue", 64.dp)).isEqualTo("Kind of Blue")
        assertThat(mediaTileText("Kind of Blue", 160.dp)).isEqualTo("Kind of Blue")
    }

    @Test fun noTitleShowsNothingButTheGlyph() {
        assertThat(mediaTileText(null, 36.dp)).isNull()
        assertThat(mediaTileText("  ", 160.dp)).isNull()
    }

    private fun show(title: String?, kind: MediaKind?, size: Int) = compose.setContent {
        GrangerTheme { Box(Modifier.size(size.dp)) { MediaTile(title, kind) } }
    }

    private fun tile(shown: String, kind: String) =
        hasTestTag(MEDIA_TILE_TAG) and SemanticsMatcher.expectValue(MediaTileShown, shown) and SemanticsMatcher.expectValue(MediaTileKind, kind)

    @Test fun theMiniPlayersTileIsTheInitialAndANote() {
        show("Blue in Green", MediaKind.MUSIC, 44)
        compose.onAllNodes(tile("B", "MUSIC")).assertCountEquals(1)
    }

    @Test fun anAlbumCellsTileIsTheTitle() {
        show("Blue in Green", MediaKind.MUSIC, 160)
        compose.onAllNodes(tile("Blue in Green", "MUSIC")).assertCountEquals(1)
    }

    @Test fun aSearchsTileIsItsGlyphAlone() {
        show(null, MediaKind.SEARCH, 36)
        compose.onAllNodes(tile("", "SEARCH")).assertCountEquals(1)
    }

    /** The row beside a tile already says the title; TalkBack shouldn't read it twice. */
    @Test fun theTileIsSilent() {
        show("Blue in Green", MediaKind.MUSIC, 160)
        compose.onAllNodesWithText("Blue in Green").assertCountEquals(0)
    }

    @Test fun everyKindHasAGlyph() {
        compose.setContent { GrangerTheme { MediaKind.entries.forEach { it.glyph() } } }
        compose.waitForIdle()
    }

    @Test fun railTabsAndPlaysPickTheirGlyph() {
        val article = RailTabUi(1L, "Owls", "Wikipedia", 'O', available = true, current = false, packId = "wiki", path = "A/Owl")
        val book = RailTabUi(2L, "Emma", "Jane Austen", 'E', available = true, current = false)
        assertThat(article.mediaKind()).isEqualTo(MediaKind.ARTICLE)
        assertThat(book.mediaKind()).isEqualTo(MediaKind.BOOK)
        val plays = mapOf(
            "album:Blue" to MediaKind.MUSIC, "songs" to MediaKind.MUSIC, "genre:Jazz" to MediaKind.MUSIC,
            "audiobook:t" to MediaKind.AUDIOBOOK, "playlist:1" to MediaKind.PLAYLIST, "artist:Miles" to MediaKind.ARTIST,
            "folder:x" to MediaKind.FOLDER, "voice:book:pan" to MediaKind.VOICE,
        )
        for ((key, kind) in plays) assertThat(RailEntry.Play(key, "T", at = 0L).mediaKind()).isEqualTo(kind)
    }
}
