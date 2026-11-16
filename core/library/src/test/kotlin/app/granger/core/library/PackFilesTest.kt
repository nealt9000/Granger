package app.granger.core.library

import app.granger.core.zim.ZimEntry
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** Plan KB2 Task 4.6: a website pack's PDFs and EPUBs, counted from its Counter and listed by name. */
class PackFilesTest {
    @Test fun theCounterSaysHowManyDocumentsThereAre() {
        assertThat(PackFiles.count("text/html=830;application/pdf=839;image/png=12")).isEqualTo(839)
        assertThat(PackFiles.count("text/html=12;application/epub+zip=2;application/pdf=1")).isEqualTo(3)
        assertThat(PackFiles.count("text/html=12")).isEqualTo(0)
        assertThat(PackFiles.count("")).isEqualTo(0)
    }

    @Test fun theFilesAreListedByTitleFromTheirNames() {
        val archive = FakeZimArchive(
            "web",
            listed = listOf(
                ZimEntry("files/Zebra_care.pdf", "", "application/pdf", 2_000),
                ZimEntry("index.html", "Home", "text/html", 100),
                ZimEntry("books/A%20Treatise_on_Hedges.epub", "", "application/epub+zip", 5_000),
            ),
        )
        assertThat(PackFiles.list(archive)).containsExactly(
            PackFile("books/A%20Treatise_on_Hedges.epub", "A Treatise on Hedges", "application/epub+zip", 5_000),
            PackFile("files/Zebra_care.pdf", "Zebra care", "application/pdf", 2_000),
        ).inOrder()
        assertThat(PackFiles.list(FakeZimArchive("web"))).isNull()
    }
}
