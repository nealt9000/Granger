package app.granger.core.archive

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Test

class LibriVoxOffersTest {
    private val item = fixtureItem("time_machine_0805_librivox", "item-librivox-time-machine.json")
    private val book = run {
        val body = javaClass.classLoader!!.getResourceAsStream("librivox-book-817.json")!!.readBytes().decodeToString()
        val http = OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).message("")
                .code(200).body(body.toResponseBody()).build()
        }).build()
        runBlocking { LibriVoxClient(http, "Granger/1.0 (+test)").book("817") }
    }

    @Test fun sectionsMapToTheItems64kbFiles() {
        val offer = LibriVoxOffers.choose(book, item) as LibriVoxOffer.Files
        assertThat(offer.files).hasSize(13)
        assertThat(offer.files.map { it.first }).isEqualTo(book.sections)
        val (section, file) = offer.files.first()
        assertThat(section.id).isEqualTo("133434")
        assertThat(file.name).isEqualTo("timemachinewells_01_ae_64kb.mp3")
        assertThat(file.format).isEqualTo("64Kbps MP3")
        assertThat(offer.files.last().second.name).isEqualTo("timemachinewells_12__64kb.mp3")
        assertThat(offer.files.all { it.second.format == "64Kbps MP3" }).isTrue()
        assertThat(offer.cover!!.name).isEqualTo("Time_Machine_1112.jpg")
        assertThat(offer.totalBytes).isEqualTo(119_820_931L)
        assertThat(offer.totalBytes).isEqualTo(offer.files.sumOf { it.second.sizeBytes!! })
        assertThat(offer.book).isEqualTo(book)
        assertThat(offer.item).isEqualTo(item)
    }

    @Test fun aSectionMissingFromTheItemIsIncomplete() {
        val dropped = item.copy(files = item.files.filterNot { it.name == "timemachinewells_05_64kb.mp3" })
        assertThat(LibriVoxOffers.choose(book, dropped)).isEqualTo(LibriVoxOffer.Incomplete(1))
    }

    @Test fun anUnverifiableFileCountsAsMissing() {
        val unverifiable = item.copy(files = item.files.map {
            if (it.name.endsWith("_64kb.mp3") && "_0" in it.name) it.copy(sha1 = null, md5 = null) else it
        })
        assertThat(LibriVoxOffers.choose(book, unverifiable)).isEqualTo(LibriVoxOffer.Incomplete(10))
    }

    @Test fun noArchiveItemIsNotOnArchive() {
        assertThat(LibriVoxOffers.choose(book.copy(archiveIdentifier = null), item)).isEqualTo(LibriVoxOffer.NotOnArchive)
        assertThat(LibriVoxOffers.choose(book.copy(sections = emptyList()), item)).isEqualTo(LibriVoxOffer.NotOnArchive)
    }
}
