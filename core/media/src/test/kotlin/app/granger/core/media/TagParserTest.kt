package app.granger.core.media

import app.granger.core.taglib.RawTags
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class TagParserTest {

    private fun raw(vararg props: Pair<String, List<String>>, durationMs: Long = 2500) =
        RawTags(props.toMap(), durationMs, sampleRate = 44100, bitrateKbps = 64, channels = 1, cover = null)

    private fun parse(vararg props: Pair<String, List<String>>) = TagParser.parse(raw(*props), "file.mp3", 0)

    @Test fun splitsMultiValueArtistsOnSemicolonsAndSlashes() {
        assertThat(parse("ARTIST" to listOf("A; B")).artists).containsExactly("A", "B").inOrder()
        assertThat(parse("ARTIST" to listOf("A / B")).artists).containsExactly("A", "B").inOrder()
        assertThat(parse("ARTIST" to listOf("London Symphony Orchestra", "Gustav Holst")).artists)
            .containsExactly("London Symphony Orchestra", "Gustav Holst").inOrder()
        assertThat(parse("GENRE" to listOf("Rock;Pop", "Rock")).genres).containsExactly("Rock", "Pop").inOrder()
        // A slash without spaces is part of a name.
        assertThat(parse("ARTIST" to listOf("AC/DC")).artists).containsExactly("AC/DC")
    }

    @Test fun parsesTrackAndDiscWithTotals() {
        val t = parse("TRACKNUMBER" to listOf("3/10"), "DISCNUMBER" to listOf("1/2"))
        assertThat(listOf(t.track, t.trackTotal, t.disc, t.discTotal)).containsExactly(3, 10, 1, 2).inOrder()
    }

    @Test fun trackWithoutSlashUsesSeparateTotalKeys() {
        val vorbis = parse(
            "TRACKNUMBER" to listOf("03"), "TRACKTOTAL" to listOf("10"),
            "DISCNUMBER" to listOf("1"), "TOTALDISCS" to listOf("2"),
        )
        assertThat(listOf(vorbis.track, vorbis.trackTotal, vorbis.disc, vorbis.discTotal))
            .containsExactly(3, 10, 1, 2).inOrder()
        val bare = parse("TRACKNUMBER" to listOf("3"))
        assertThat(bare.track).isEqualTo(3)
        assertThat(bare.trackTotal).isNull()
        assertThat(bare.disc).isNull()
        assertThat(parse("TRACKNUMBER" to listOf("x/y")).track).isNull()
    }

    @Test fun parsesReplayGainDbAndPeaks() {
        val rg = parse(
            "REPLAYGAIN_TRACK_GAIN" to listOf("-6.50 dB"), "REPLAYGAIN_TRACK_PEAK" to listOf("0.988525"),
            "REPLAYGAIN_ALBUM_GAIN" to listOf("+1.2dB"), "REPLAYGAIN_ALBUM_PEAK" to listOf("0.999969"),
        ).replayGain!!
        assertThat(rg.trackGainDb).isEqualTo(-6.5f)
        assertThat(rg.trackPeak).isEqualTo(0.988525f)
        assertThat(rg.albumGainDb).isEqualTo(1.2f)
        assertThat(rg.albumPeak).isEqualTo(0.999969f)
        assertThat(parse("TITLE" to listOf("x")).replayGain).isNull()
        assertThat(parse("REPLAYGAIN_TRACK_GAIN" to listOf("loud")).replayGain).isNull()
    }

    @Test fun convertsOpusR128ToReplayGain() {
        val rg = parse("R128_TRACK_GAIN" to listOf("-1280"), "R128_ALBUM_GAIN" to listOf("-1690")).replayGain!!
        // Q7.8: -1280 / 256 = -5 dB against -23 LUFS; +5 dB moves it to the ReplayGain reference.
        assertThat(rg.trackGainDb).isEqualTo(0.0f)
        assertThat(rg.albumGainDb).isWithin(1e-4f).of(-1690 / 256f + 5f)
        assertThat(rg.trackPeak).isNull()
    }

    @Test fun releaseTypeFromMusicBrainzOrReleaseType() {
        assertThat(parse("RELEASETYPE" to listOf("Album; Live")).releaseType).isEqualTo("album")
        assertThat(parse("MUSICBRAINZ_ALBUMTYPE" to listOf("EP")).releaseType).isEqualTo("ep")
        assertThat(parse("TITLE" to listOf("x")).releaseType).isEmpty()
    }

    @Test fun emptyTagsFallBackToFileName() {
        val t = TagParser.parse(null, "03 - Jupiter.mp3", 1000)
        assertThat(t.title).isEqualTo("03 - Jupiter")
        assertThat(t.album).isEmpty()
        assertThat(t.artists).isEmpty()
        assertThat(t.albumArtists).isEmpty()
        assertThat(t.durationMs).isEqualTo(1000)
        assertThat(t.replayGain).isNull()
        assertThat(t.sort).isEqualTo(SortTags("", "", "", ""))
    }

    @Test fun blankTitleAndZeroDurationFallBack() {
        val t = TagParser.parse(raw("TITLE" to listOf("  "), durationMs = 0), "Track.Two.flac", 4200)
        assertThat(t.title).isEqualTo("Track.Two")
        assertThat(t.durationMs).isEqualTo(4200)
        assertThat(TagParser.parse(raw(durationMs = 2500), "a.mp3", 4200).durationMs).isEqualTo(2500)
    }

    @Test fun albumArtistFallsBackToArtists() {
        assertThat(parse("ARTIST" to listOf("A; B")).albumArtists).containsExactly("A", "B").inOrder()
        assertThat(parse("ARTIST" to listOf("A"), "ALBUMARTIST" to listOf("C")).albumArtists).containsExactly("C")
    }

    @Test fun readsTheOtherFields() {
        val t = parse(
            "TITLE" to listOf("Mars, the Bringer of War"), "ALBUM" to listOf("The Planets"),
            "COMPOSER" to listOf("Gustav Holst"), "DATE" to listOf("1926"), "ORIGINALDATE" to listOf("1916"),
            "TITLESORT" to listOf("Mars"), "ALBUMSORT" to listOf("Planets"),
            "ARTISTSORT" to listOf("London Symphony Orchestra"), "ALBUMARTISTSORT" to listOf("Holst, Gustav"),
        )
        assertThat(t.title).isEqualTo("Mars, the Bringer of War")
        assertThat(t.album).isEqualTo("The Planets")
        assertThat(t.composers).containsExactly("Gustav Holst")
        assertThat(t.date).isEqualTo("1926")
        assertThat(t.originalDate).isEqualTo("1916")
        assertThat(t.sort).isEqualTo(SortTags("Mars", "Planets", "London Symphony Orchestra", "Holst, Gustav"))
    }
}
