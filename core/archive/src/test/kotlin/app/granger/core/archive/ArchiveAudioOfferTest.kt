package app.granger.core.archive

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ArchiveAudioOfferTest {
    private val holst = fixtureItem(HOLST, "item-holst-planets.json")

    private fun file(
        name: String,
        format: String,
        sha1: String? = "abc",
        md5: String? = null,
        size: Long? = 100,
        isPrivate: Boolean = false,
        track: Int? = null,
    ) = ArchiveFile(name, format, size, sha1, md5, "original", isPrivate, track = track)

    private fun item(vararg files: ArchiveFile, restricted: Boolean = false) =
        ArchiveItem("x", "T", "", "", 1920, emptyList(), "", "", "", restricted, files.toList(), mediatype = "audio")

    private fun doc(formats: List<String>, restricted: Boolean = false) =
        ArchiveDoc("x", "T", "", 1920, formats, restricted, 0, mediatype = "audio")

    @Test fun holstOffersItsSevenVbrMp3sInTrackOrder() {
        val offer = ArchiveAudioOffers.choose(holst) as AudioOffer.Tracks
        assertThat(offer.format).isEqualTo("VBR MP3")
        assertThat(offer.files).hasSize(7)
        assertThat(offer.files.first().name).contains("I. Mars")
        assertThat(offer.files.last().name).contains("VII. Neptune")
        assertThat(offer.files.map { it.track }).containsExactly(1, 2, 3, 4, 5, 6, 7).inOrder()
        assertThat(offer.cover!!.format).isEqualTo("JPEG")
        assertThat(offer.totalBytes).isEqualTo(offer.files.sumOf { it.sizeBytes!! })
    }

    @Test fun librivoxItemOrdersByTrackEvenWithABareNumber() {
        val offer = ArchiveAudioOffers.choose(fixtureItem("time_machine_0805_librivox", "item-librivox-time-machine.json")) as AudioOffer.Tracks
        assertThat(offer.format).isEqualTo("VBR MP3")
        assertThat(offer.files.map { it.track }).containsExactlyElementsIn(1..13).inOrder()
        assertThat(offer.files[6].name).isEqualTo("timemachinewells_06.mp3")
        assertThat(offer.cover!!.name).isEqualTo("Time_Machine_1112.jpg")
    }

    @Test fun oggWhenThereIsNoMp3() {
        val offer = ArchiveAudioOffers.choose(item(file("a.flac", "Flac"), file("a.ogg", "Ogg Vorbis"))) as AudioOffer.Tracks
        assertThat(offer.format).isEqualTo("Ogg Vorbis")
        assertThat(offer.files.map { it.name }).containsExactly("a.ogg")
    }

    @Test fun flacWhenThatIsAllThereIs() {
        val offer = ArchiveAudioOffers.choose(item(file("b.flac", "Flac"), file("a.flac", "Flac"), file("x.png", "PNG"))) as AudioOffer.Tracks
        assertThat(offer.format).isEqualTo("Flac")
        assertThat(offer.files.map { it.name }).containsExactly("a.flac", "b.flac").inOrder()
    }

    @Test fun higherBitrateMp3BeatsLowerAndOgg() {
        val offer = ArchiveAudioOffers.choose(
            item(file("a_64kb.mp3", "64Kbps MP3"), file("a.ogg", "Ogg Vorbis"), file("a_128.mp3", "128Kbps MP3"), file("a_256.mp3", "256Kbps MP3")),
        ) as AudioOffer.Tracks
        assertThat(offer.format).isEqualTo("256Kbps MP3")
        assertThat((ArchiveAudioOffers.choose(item(file("a_64kb.mp3", "64Kbps MP3"), file("a.ogg", "Ogg Vorbis"))) as AudioOffer.Tracks).format)
            .isEqualTo("Ogg Vorbis")
    }

    @Test fun privateAndChecksumlessFilesAreSkipped() {
        val offer = ArchiveAudioOffers.choose(
            item(
                file("secret.mp3", "VBR MP3", isPrivate = true),
                file("nosum.mp3", "320Kbps MP3", sha1 = null, md5 = null),
                file("empty.mp3", "256Kbps MP3", size = 0),
                file("../escape.mp3", "192Kbps MP3"),
                file("md5only.ogg", "Ogg Vorbis", sha1 = null, md5 = "def"),
            ),
        ) as AudioOffer.Tracks
        assertThat(offer.format).isEqualTo("Ogg Vorbis")
        assertThat(offer.files.map { it.name }).containsExactly("md5only.ogg")
    }

    @Test fun onlyPrivateAudioIsLendOnly() {
        assertThat(ArchiveAudioOffers.choose(item(file("secret.mp3", "VBR MP3", isPrivate = true), file("__ia_thumb.jpg", "Item Tile"))))
            .isEqualTo(AudioOffer.LendOnly)
    }

    @Test fun noTrackNumbersMeansNaturalNameOrder() {
        val offer = ArchiveAudioOffers.choose(item(file("10.mp3", "VBR MP3"), file("2.mp3", "VBR MP3"), file("1.mp3", "VBR MP3"))) as AudioOffer.Tracks
        assertThat(offer.files.map { it.name }).containsExactly("1.mp3", "2.mp3", "10.mp3").inOrder()
    }

    @Test fun someTrackNumbersMissingMeansNaturalNameOrder() {
        val offer = ArchiveAudioOffers.choose(
            item(file("b 2.mp3", "VBR MP3", track = 1), file("b 10.mp3", "VBR MP3"), file("a.mp3", "VBR MP3", track = 2)),
        ) as AudioOffer.Tracks
        assertThat(offer.files.map { it.name }).containsExactly("a.mp3", "b 2.mp3", "b 10.mp3").inOrder()
    }

    @Test fun waveformPngsAreNeverCovers() {
        val withTile = item(file("a.mp3", "VBR MP3"), file("a.png", "PNG", size = 9999), file("a_spectrogram.png", "Spectrogram", size = 9999), file("__ia_thumb.jpg", "Item Tile"))
        assertThat(ArchiveAudioOffers.cover(withTile)!!.name).isEqualTo("__ia_thumb.jpg")
        val withoutTile = item(file("a.mp3", "VBR MP3"), file("a.png", "PNG"), file("a_spectrogram.png", "Spectrogram"))
        assertThat(ArchiveAudioOffers.cover(withoutTile)).isNull()
        assertThat((ArchiveAudioOffers.choose(withoutTile) as AudioOffer.Tracks).cover).isNull()
    }

    @Test fun largestUsableJpegIsTheCover() {
        val item = item(
            file("small.jpg", "JPEG", size = 10), file("big.jpg", "JPEG", size = 50), file("huge.jpg", "JPEG", size = 90, isPrivate = true),
            file("x_thumb.jpg", "JPEG Thumb", size = 999), file("__ia_thumb.jpg", "Item Tile", size = 999),
        )
        assertThat(ArchiveAudioOffers.cover(item)!!.name).isEqualTo("big.jpg")
    }

    @Test fun restrictedAudioIsLendOnly() {
        assertThat(ArchiveAudioOffers.choose(fixtureItem("rvji2imcowl7mslliunbjf94m7meqr7isqpghxlm", "item-audio-lending.json")))
            .isEqualTo(AudioOffer.LendOnly)
    }

    @Test fun noAudioIsNothing() {
        assertThat(ArchiveAudioOffers.choose(item(file("a.png", "PNG"), file("a.txt", "Text")))).isEqualTo(AudioOffer.Nothing)
        assertThat(ArchiveAudioOffers.choose(item())).isEqualTo(AudioOffer.Nothing)
    }

    @Test fun previewGuessesFromSearchFormats() {
        val tracks = ArchiveAudioOffers.preview(doc(listOf("64Kbps MP3", "Ogg Vorbis", "PNG"))) as AudioOffer.Tracks
        assertThat(tracks.files).isEmpty()
        assertThat(tracks.format).isEqualTo("Ogg Vorbis")
        assertThat(ArchiveAudioOffers.preview(doc(listOf("VBR MP3"), restricted = true))).isEqualTo(AudioOffer.LendOnly)
        assertThat(ArchiveAudioOffers.preview(doc(listOf("PNG", "Metadata")))).isNull()
    }
}
