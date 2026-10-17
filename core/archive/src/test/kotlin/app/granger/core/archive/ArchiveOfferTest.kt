package app.granger.core.archive

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Test

class ArchiveOfferTest {
    private fun fixtureItem(id: String, resource: String): ArchiveItem {
        val body = javaClass.classLoader!!.getResourceAsStream(resource)!!.readBytes().decodeToString()
        val http = OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).message("")
                .code(200).body(body.toResponseBody()).build()
        }).build()
        return runBlocking { ArchiveOrgClient(http, "Granger/1.0 (+test)").item(id) }
    }

    private fun file(
        name: String,
        format: String,
        source: String = "original",
        sha1: String? = "abc",
        md5: String? = null,
        size: Long? = 100,
        isPrivate: Boolean = false,
    ) = ArchiveFile(name, format, size, sha1, md5, source, isPrivate)

    private fun item(vararg files: ArchiveFile, restricted: Boolean = false, year: Int? = 1900, license: String = "") =
        ArchiveItem("x", "T", "", "", year, emptyList(), "", "", license, restricted, files.toList())

    private fun doc(formats: List<String>, restricted: Boolean = false) =
        ArchiveDoc("x", "T", "", null, formats, restricted, 0)

    @Test fun openItemOffersItsEpub() {
        val offer = ArchiveOffers.choose(fixtureItem("contributionletu0037vari_w0s3", "item-open-epub.json"))
        assertThat(offer).isInstanceOf(Offer.Download::class.java)
        offer as Offer.Download
        assertThat(offer.kind).isEqualTo(TextKind.EPUB)
        assertThat(offer.file.name).endsWith(".epub")
        assertThat(offer.file.sha1).isNotNull()
    }

    @Test fun lendingItemOffersNothingToDownload() {
        val offer = ArchiveOffers.choose(fixtureItem("lbg.vol.xviii.p.1.agriculturaljour0000unse_b7i9", "item-lending.json"))
        assertThat(offer).isEqualTo(Offer.LendOnly)
    }

    @Test fun scanOnlyItemOffersOcrTextAndSaysPdf() {
        val offer = ArchiveOffers.choose(fixtureItem("scientific-american-1881-10-15", "item-scan-only.json"))
        assertThat(offer).isInstanceOf(Offer.Download::class.java)
        offer as Offer.Download
        assertThat(offer.kind).isEqualTo(TextKind.OCR_TEXT)
        assertThat(offer.alsoPdf).isTrue()
        assertThat(offer.file.name).endsWith("_djvu.txt")
    }

    @Test fun originalTextBeatsOcr() {
        val offer = ArchiveOffers.choose(
            item(file("a_djvu.txt", "DjVuTXT", "derivative"), file("a.txt", "Text"), file("a.pdf", "Text PDF", "derivative")),
        ) as Offer.Download
        assertThat(offer.kind).isEqualTo(TextKind.TEXT)
        assertThat(offer.file.name).isEqualTo("a.txt")
        assertThat(offer.alsoPdf).isTrue()
    }

    @Test fun largestEpubWins() {
        val offer = ArchiveOffers.choose(
            item(file("small.epub", "EPUB", size = 10), file("big.epub", "EPUB", size = 50)),
        ) as Offer.Download
        assertThat(offer.file.name).isEqualTo("big.epub")
        assertThat(offer.alsoPdf).isFalse()
    }

    @Test fun md5AloneIsEnough() {
        val offer = ArchiveOffers.choose(item(file("a.epub", "EPUB", sha1 = null, md5 = "m")))
        assertThat(offer).isInstanceOf(Offer.Download::class.java)
    }

    @Test fun fileWithoutAnyChecksumIsNotOffered() {
        assertThat(ArchiveOffers.choose(item(file("a.epub", "EPUB", sha1 = null, md5 = null)))).isEqualTo(Offer.Nothing)
        assertThat(
            ArchiveOffers.choose(item(file("a.epub", "EPUB", sha1 = null), file("a.pdf", "Text PDF"))),
        ).isEqualTo(Offer.PdfOnly)
    }

    @Test fun fileWithoutASizeIsNotOffered() {
        assertThat(ArchiveOffers.choose(item(file("a.epub", "EPUB", size = 0)))).isEqualTo(Offer.Nothing)
        assertThat(ArchiveOffers.choose(item(file("a.epub", "EPUB", size = null)))).isEqualTo(Offer.Nothing)
    }

    @Test fun fileWithADotDotSegmentIsNotOffered() {
        for (name in listOf("../a.epub", "a/../b.epub", "a//b.epub", "./a.epub", "/a.epub", "a/b/", "")) {
            assertThat(ArchiveOffers.choose(item(file(name, "EPUB")))).isEqualTo(Offer.Nothing)
        }
        val ok = ArchiveOffers.choose(item(file("sub/a.epub", "EPUB"))) as Offer.Download
        assertThat(ok.file.name).isEqualTo("sub/a.epub")
    }

    @Test fun privateAndLcpFilesAreNeverOffered() {
        assertThat(ArchiveOffers.choose(item(file("a.epub", "EPUB", isPrivate = true)))).isEqualTo(Offer.LendOnly)
        assertThat(ArchiveOffers.choose(item(file("a_lcp.epub", "LCP Encrypted EPUB")))).isEqualTo(Offer.Nothing)
    }

    @Test fun restrictedItemWithoutOpenTextIsLendOnly() {
        assertThat(ArchiveOffers.choose(item(file("a.pdf", "Text PDF"), restricted = true))).isEqualTo(Offer.LendOnly)
    }

    @Test fun restrictedItemIsLendOnlyEvenWithAnOpenEpub() {
        // Spec §5: an item archive.org restricts to lending is "Borrow on archive.org", whatever its files.
        assertThat(ArchiveOffers.choose(item(file("a.epub", "EPUB"), restricted = true))).isEqualTo(Offer.LendOnly)
        assertThat(ArchiveOffers.choose(item(file("a_djvu.txt", "DjVuTXT"), restricted = true))).isEqualTo(Offer.LendOnly)
        assertThat(ArchiveOffers.preview(doc(listOf("EPUB", "DjVuTXT"), restricted = true))).isEqualTo(Offer.LendOnly)
    }

    @Test fun textTooBigToConvertIsNotOfferedAsText() {
        val limit = 1_000L
        val big = ArchiveOffers.choose(
            item(file("a.txt", "Text", size = 1_001), file("a_djvu.txt", "DjVuTXT", "derivative", size = 2_000), file("a.pdf", "Text PDF")),
            maxTextBytes = limit,
        )
        assertThat(big).isEqualTo(Offer.PdfOnly)
        assertThat(ArchiveOffers.choose(item(file("a_djvu.txt", "DjVuTXT", size = 1_001)), maxTextBytes = limit)).isEqualTo(Offer.Nothing)
        val atLimit = ArchiveOffers.choose(item(file("a_djvu.txt", "DjVuTXT", size = 1_000)), maxTextBytes = limit) as Offer.Download
        assertThat(atLimit.kind).isEqualTo(TextKind.OCR_TEXT)
        // An oversized original text falls through to an OCR text that fits.
        val ocr = ArchiveOffers.choose(
            item(file("a.txt", "Text", size = 1_001), file("a_djvu.txt", "DjVuTXT", "derivative", size = 900)), maxTextBytes = limit,
        ) as Offer.Download
        assertThat(ocr.kind).isEqualTo(TextKind.OCR_TEXT)
        // EPUBs aren't converted, so the limit doesn't apply to them.
        assertThat(ArchiveOffers.choose(item(file("a.epub", "EPUB", size = 5_000)), maxTextBytes = limit)).isInstanceOf(Offer.Download::class.java)
        // The default is 20 MB.
        assertThat(ArchiveOffers.choose(item(file("a_djvu.txt", "DjVuTXT", size = 20L * 1024 * 1024 + 1)))).isEqualTo(Offer.Nothing)
        assertThat(ArchiveOffers.choose(item(file("a_djvu.txt", "DjVuTXT", size = 20L * 1024 * 1024)))).isInstanceOf(Offer.Download::class.java)
    }

    @Test fun plainTextFormatIsNotATextOffer() {
        assertThat(ArchiveOffers.choose(item(file("a.txt", "Plain Text")))).isEqualTo(Offer.Nothing)
    }

    @Test fun aPrivateMetadataFileDoesNotMakeAnItemLendOnly() {
        assertThat(ArchiveOffers.choose(item(file("x_meta.txt", "Metadata", isPrivate = true), file("a.pdf", "Text PDF"))))
            .isEqualTo(Offer.PdfOnly)
        assertThat(ArchiveOffers.choose(item(file("a.txt", "Text", isPrivate = true)))).isEqualTo(Offer.LendOnly)
    }

    @Test fun pdfOnlyItem() {
        assertThat(ArchiveOffers.choose(item(file("a.pdf", "Text PDF")))).isEqualTo(Offer.PdfOnly)
    }

    @Test fun noFilesOffersNothing() {
        assertThat(ArchiveOffers.choose(item())).isEqualTo(Offer.Nothing)
    }

    @Test fun isPublicDomainByYearOrLicence() {
        assertThat(ArchiveOffers.isPublicDomain(item(year = 1930))).isTrue()
        assertThat(ArchiveOffers.isPublicDomain(item(year = 1931))).isFalse()
        assertThat(ArchiveOffers.isPublicDomain(item(year = null, license = "https://creativecommons.org/publicdomain/zero/1.0/"))).isTrue()
        assertThat(ArchiveOffers.isPublicDomain(item(year = null, license = "https://creativecommons.org/PublicDomain/mark/1.0/"))).isTrue()
        assertThat(ArchiveOffers.isPublicDomain(item(year = null, license = ""))).isFalse()
    }

    @Test fun previewFromSearchFormats() {
        assertThat((ArchiveOffers.preview(doc(listOf("EPUB"))) as Offer.Download).kind).isEqualTo(TextKind.EPUB)
        val ocr = ArchiveOffers.preview(doc(listOf("DjVuTXT", "Text PDF"))) as Offer.Download
        assertThat(ocr.kind).isEqualTo(TextKind.OCR_TEXT)
        assertThat(ocr.alsoPdf).isTrue()
        assertThat(ArchiveOffers.preview(doc(listOf("EPUB"), restricted = true))).isEqualTo(Offer.LendOnly)
        assertThat(ArchiveOffers.preview(doc(listOf("Text PDF")))).isEqualTo(Offer.PdfOnly)
        assertThat(ArchiveOffers.preview(doc(emptyList()))).isNull()
    }
}
