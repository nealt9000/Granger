package app.granger.core.archive

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ArchivePartsTest {
    private fun file(name: String, format: String, source: String = "original", size: Long = 100) =
        ArchiveFile(name = name, format = format, sizeBytes = size, sha1 = "abc", md5 = null, source = source, isPrivate = false)

    private fun item(vararg files: ArchiveFile, title: String = "T") =
        ArchiveItem("x", title, "", "", 1900, emptyList(), "", "", "", false, files.toList())

    @Test fun aComicRunIsItsIssuesInOrder() {
        val offer = ArchiveOffers.choose(fixtureItem("BatmanTheDarkKnightReturns03of041986", "item-comic-run.json")) as Offer.Download
        assertThat(offer.kind).isEqualTo(TextKind.COMIC)
        assertThat(offer.isSet).isTrue()
        assertThat(offer.parts.map { it.number }).containsExactly(1, 2, 3, 4).inOrder()
        assertThat(offer.parts.map { it.title }).containsExactly(
            "01 (of 04) (1986)", "02 (of 04) (1986)", "03 (of 04) (1986)", "04 (of 04) (1986)",
        ).inOrder()
        assertThat(offer.file).isEqualTo(offer.parts.first().file)
        assertThat(offer.totalBytes).isEqualTo(offer.parts.sumOf { it.sizeBytes })
        assertThat(offer.parts.none { it.file.source == "derivative" }).isTrue()
    }

    @Test fun aMagazineRunIsItsIssuesByDate() {
        val offer = ArchiveOffers.choose(fixtureItem("national-geographic-magazine-complete-pdf", "item-magazine-run.json")) as Offer.Download
        assertThat(offer.kind).isEqualTo(TextKind.PDF)
        val titles = offer.parts.map { it.title }
        assertThat(titles).containsAtLeast("1888-01 Oct 001-1", "1889-01 001-2", "1889-02 001-3").inOrder()
        assertThat(titles.first()).isEqualTo("1888-01 Oct 001-1")
        assertThat(titles).contains("Special Issue - The Civil War")
        assertThat(offer.parts.all { it.file.format.contains("PDF") }).isTrue()   // never archive.org's EPUBs
        assertThat(offer.parts.map { it.number }).isEqualTo((1..offer.parts.size).toList())
    }

    @Test fun aSmallExtraIsntAPart() {
        val offer = ArchiveOffers.choose(
            item(
                file("vol1.pdf", "Text PDF", size = 30_000_000),
                file("vol2.pdf", "Text PDF", size = 28_000_000),
                file("errata.pdf", "Text PDF", size = 200_000),
            ),
        ) as Offer.Download
        assertThat(offer.parts.map { it.file.name }).containsExactly("vol1.pdf", "vol2.pdf").inOrder()
    }

    @Test fun oneFileIsAnOrdinaryItem() {
        val offer = ArchiveOffers.choose(item(file("book.pdf", "Text PDF"), file("book.epub", "EPUB", "derivative"))) as Offer.Download
        assertThat(offer.isSet).isFalse()
        assertThat(offer.parts).isEmpty()
        assertThat(offer.file.name).isEqualTo("book.pdf")
    }

    @Test fun theSamePdfUploadedTwiceIsOnePart() {
        val offer = ArchiveOffers.choose(
            item(file("a.pdf", "Text PDF", size = 50), file("a.PDF", "Image Container PDF", size = 90)),
        ) as Offer.Download
        assertThat(offer.isSet).isFalse()
    }

    @Test fun partTitles() {
        val t = ArchiveParts::title
        assertThat(t("Batman The Dark Knight Returns (1986)",
            "Batman The Dark Knight Returns/Batman - The Dark Knight Returns 02 (of 04) (1986) (digital) (Minutemen-InnerDemons).cbr"))
            .isEqualTo("02 (of 04) (1986)")
        assertThat(t("National Geographic Magazine complete pdf", "National Geographic 1889-02 001-3.pdf")).isEqualTo("1889-02 001-3")
        assertThat(t("Collected Works", "Collected_Works_Vol_2 [c2c].pdf")).isEqualTo("Vol 2")
        assertThat(t("Anything", "Other Title 3.epub")).isEqualTo("Other Title 3")
        assertThat(t("Same Name", "Same Name.pdf")).isEqualTo("Same Name")    // the whole title stays rather than nothing
    }
}
