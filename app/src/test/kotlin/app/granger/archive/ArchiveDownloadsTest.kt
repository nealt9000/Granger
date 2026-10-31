package app.granger.archive

import app.granger.core.archive.ArchiveFile
import app.granger.core.archive.ArchiveItem
import app.granger.core.archive.ArchiveOrgClient
import app.granger.core.archive.Offer
import app.granger.core.archive.TextKind
import app.granger.core.downloads.HashAlgorithm
import app.granger.core.downloads.ItemKind
import com.google.common.truth.Truth.assertThat
import java.io.File
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ArchiveDownloadsTest {
    @get:Rule val tmp = TemporaryFolder()
    private val id = "contributionletu0037vari_w0s3"

    private val fixture = Interceptor { chain ->
        val body = javaClass.classLoader!!.getResourceAsStream("archive/item-open-epub.json")!!.readBytes().decodeToString()
        Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).message("").code(200)
            .body(body.toResponseBody()).build()
    }
    private val client = ArchiveOrgClient(OkHttpClient.Builder().addInterceptor(fixture).build(), "Granger/1.0 (+test)")
    private val item: ArchiveItem = runBlocking { client.item(id) }
    private val epub: ArchiveFile = item.files.single { it.format == "EPUB" }

    @Test fun epubSpecUsesSha1AndTheArchivePath() {
        val spec = ArchiveDownloads.spec(item, Offer.Download(epub, TextKind.EPUB), client)
        assertThat(spec.itemId).isEqualTo("archive-org:$id")
        assertThat(spec.relativePath).isEqualTo("books/archive-org/$id.epub")
        assertThat(spec.hashAlgorithm).isEqualTo(HashAlgorithm.SHA1)
        assertThat(spec.hash).isEqualTo(epub.sha1)
        assertThat(spec.capped).isTrue()
        assertThat(spec.kind).isEqualTo(ItemKind.BOOK)
        assertThat(spec.snapshotDate).isEmpty()
        assertThat(spec.urls).containsExactly(client.downloadUrl(id, epub.name))
    }

    @Test fun md5IsUsedWhenThereIsNoSha1() {
        val file = epub.copy(sha1 = null)
        val spec = ArchiveDownloads.spec(item, Offer.Download(file, TextKind.EPUB), client)
        assertThat(spec.hashAlgorithm).isEqualTo(HashAlgorithm.MD5)
        assertThat(spec.hash).isEqualTo(epub.md5)
    }

    @Test fun ocrTextGoesToATxtPathAndSaysSoInTheDescription() {
        val spec = ArchiveDownloads.spec(item, Offer.Download(epub, TextKind.OCR_TEXT), client)
        assertThat(spec.relativePath).isEqualTo("books/archive-org/$id.txt")
        val details = ArchiveDownloads.details(item.copy(description = "A book."), TextKind.OCR_TEXT)
        assertThat(details.description).isEqualTo("OCR text from a scan: may have errors. A book.")
        assertThat(ArchiveDownloads.details(item.copy(description = "A book."), TextKind.EPUB).description).isEqualTo("A book.")
    }

    @Test fun aPdfOnlyItemDownloadsItsPdfAsTheItem() {
        val pdf = item.files.first { it.format == "Text PDF" }
        val spec = ArchiveDownloads.spec(item, Offer.Download(pdf, TextKind.PDF), client)
        assertThat(spec.itemId).isEqualTo("archive-org:$id")
        assertThat(spec.relativePath).isEqualTo("books/archive-org/$id.pdf")
        assertThat(spec.hash).isEqualTo(pdf.sha1)
        assertThat(spec.sizeBytes).isEqualTo(pdf.sizeBytes)
    }

    @Test fun thePdfBesideATextIsItsOwnDownloadAndBook() {
        val pdf = item.files.first { it.format == "Text PDF" }
        val spec = ArchiveDownloads.spec(item, Offer.Download(epub, TextKind.OCR_TEXT, pdf = pdf), client, pdfCopy = true)
        assertThat(spec.itemId).isEqualTo("archive-org:$id#pdf")
        assertThat(spec.relativePath).isEqualTo("books/archive-org/$id.pdf")
        assertThat(spec.urls).containsExactly(client.downloadUrl(id, pdf.name))
        assertThat(spec.title).endsWith(" (PDF)")
        assertThat(ArchiveDownloads.identifierOf(spec.itemId)).isEqualTo(id)
        assertThat(ArchiveDownloads.isPdfCopy(spec.itemId)).isTrue()
        assertThat(ArchiveDownloads.bookRefOf(spec.itemId)).isEqualTo("$id#pdf")
        assertThat(ArchiveDownloads.bookRefOf("archive-org:$id")).isEqualTo(id)
        val details = ArchiveDownloads.pdfCopyDetails(ArchiveDownloads.details(item.copy(title = "Steam", description = "A book."), TextKind.OCR_TEXT))
        assertThat(details.title).isEqualTo("Steam (PDF)")
        assertThat(details.description).isEqualTo("A book.")
    }

    @Test fun sidecarRoundTripsAPdfKind() {
        val dir = File(tmp.root, "books/archive-org")
        val file = ArchiveDownloads.writeSidecar(dir, item, TextKind.PDF)
        assertThat(ArchiveDownloads.readSidecar(file)!!.second).isEqualTo(TextKind.PDF)
    }

    @Test fun blankTitleFallsBackToTheIdentifier() {
        val spec = ArchiveDownloads.spec(item.copy(title = " "), Offer.Download(epub, TextKind.EPUB), client)
        assertThat(spec.title).isEqualTo(id)
    }

    @Test fun badIdentifierIsRefused() {
        assertThrows(IllegalArgumentException::class.java) {
            ArchiveDownloads.spec(item.copy(identifier = "../x"), Offer.Download(epub, TextKind.EPUB), client)
        }
    }

    @Test fun sidecarRoundTrips() {
        val dir = File(tmp.root, "books/archive-org")
        val rich = item.copy(title = "T", creator = "C", date = "1881", subjects = listOf("a", "b"), description = "D", language = "eng")
        val file = ArchiveDownloads.writeSidecar(dir, rich, TextKind.TEXT)
        assertThat(file.name).isEqualTo("$id.json")
        val (details, kind) = ArchiveDownloads.readSidecar(file)!!
        assertThat(kind).isEqualTo(TextKind.TEXT)
        assertThat(details.title).isEqualTo("T")
        assertThat(details.author).isEqualTo("C")
        assertThat(details.date).isEqualTo("1881")
        assertThat(details.subjects).containsExactly("a", "b").inOrder()
        assertThat(details.description).isEqualTo("D")
        assertThat(details.language).isEqualTo("eng")
    }

    @Test fun unreadableSidecarIsNull() {
        assertThat(ArchiveDownloads.readSidecar(File(tmp.root, "nope.json"))).isNull()
        val bad = tmp.newFile("bad.json").apply { writeText("{not json") }
        assertThat(ArchiveDownloads.readSidecar(bad)).isNull()
    }

    @Test fun identifierOfOnlyAcceptsThePrefix() {
        assertThat(ArchiveDownloads.identifierOf("archive-org:x")).isEqualTo("x")
        assertThat(ArchiveDownloads.identifierOf("pg1")).isNull()
    }
}

class ArchivePartIdsTest {
    @Test fun partKeysAndIds() {
        assertThat(ArchiveDownloads.partKey("run", 3, 4)).isEqualTo("run~03")
        assertThat(ArchiveDownloads.partKey("run", 7, 552)).isEqualTo("run~007")
        val id = ArchiveDownloads.itemId("run~03")
        assertThat(ArchiveDownloads.identifierOf(id)).isEqualTo("run")
        assertThat(ArchiveDownloads.partNumberOf(id)).isEqualTo(3)
        assertThat(ArchiveDownloads.bookRefOf(id)).isEqualTo("run~03")
        assertThat(ArchiveDownloads.sidecarKeyOf(id)).isEqualTo("run~03")
        // The item's own download and the PDF beside its text are unchanged.
        assertThat(ArchiveDownloads.partNumberOf("archive-org:run")).isNull()
        assertThat(ArchiveDownloads.bookRefOf("archive-org:run#pdf")).isEqualTo("run#pdf")
        assertThat(ArchiveDownloads.sidecarKeyOf("archive-org:run#pdf")).isEqualTo("run")
        assertThat(ArchiveDownloads.identifierOf("archive-org:run#pdf")).isEqualTo("run")
    }
}
