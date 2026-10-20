package app.granger

import app.granger.core.archive.ArchiveKind
import app.granger.sources.CatalogScope
import app.granger.core.library.Albums
import app.granger.library.SourceLink
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SourceRoutesTest {
    @Test fun everySourceLinkOpensItsView() {
        assertThat(routeOf(SourceLink.GRANGER)).isEqualTo(SourceRoute(CatalogScope.GRANGER))
        assertThat(routeOf(SourceLink.KIWIX)).isEqualTo(SourceRoute(CatalogScope.KIWIX))
        assertThat(routeOf(SourceLink.ARCHIVE_ORG)).isEqualTo(ArchiveRoute(ArchiveKind.TEXTS))
        assertThat(routeOf(SourceLink.ARCHIVE_ORG_SPOKEN)).isEqualTo(ArchiveRoute(ArchiveKind.SPOKEN_WORD))
        assertThat(routeOf(SourceLink.ARCHIVE_ORG_MUSIC)).isEqualTo(ArchiveRoute(ArchiveKind.MUSIC))
        assertThat(routeOf(SourceLink.LIBRIVOX)).isEqualTo(LibriVoxRoute)
    }

    /** "In your Music" opens the album; "In your Audiobooks" the book: the other view's shelf, by the item's ids. */
    @Test fun anItemInTheOtherShelfOpensThere() {
        assertThat(inLibraryRoute(ArchiveKind.SPOKEN_WORD, "holst")).isEqualTo(AlbumRoute(Albums.itemKey("archive-org", "holst")))
        assertThat(inLibraryRoute(ArchiveKind.MUSIC, "holst")).isEqualTo(AudiobookRoute("audiobook:archive-org:holst"))
    }
}
