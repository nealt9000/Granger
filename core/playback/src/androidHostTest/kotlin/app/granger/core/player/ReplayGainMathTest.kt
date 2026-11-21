package app.granger.core.player

import app.granger.core.library.MediaOrigin
import app.granger.core.library.PackStatus
import app.granger.core.library.Track
import com.google.common.truth.Truth.assertThat
import kotlin.math.pow
import org.junit.Test

class ReplayGainMathTest {
    private fun track(trackGain: Float? = null, trackPeak: Float? = null, albumGain: Float? = null, albumPeak: Float? = null) = Track(
        id = "track:phone:1", origin = MediaOrigin.PHONE, uri = "content://1", folder = "Music/", title = "T", album = "A",
        albumKey = "a", albumArtists = "", artists = "", composers = "", genres = "", disc = null, trackNo = null, date = "",
        originalDate = "", releaseType = "", sortTitle = "", sortAlbum = "", sortArtist = "", sortAlbumArtist = "",
        durationMs = 1_000L, rgTrackGain = trackGain, rgTrackPeak = trackPeak, rgAlbumGain = albumGain, rgAlbumPeak = albumPeak,
        coverPath = null, audiobookId = null, status = PackStatus.OK, addedAt = 0L,
    )

    private fun db(gain: Float) = 10.0.pow(gain / 20.0).toFloat()

    @Test fun replayGainAlbumModeUsesAlbumGainAndClampsToPeak() {
        val t = track(trackGain = -3f, trackPeak = 0.5f, albumGain = 6f, albumPeak = 0.8f)
        // +6 dB is ×1.995, but the album peak 0.8 allows at most ×1.25.
        assertThat(ReplayGainMath.factor(t, ReplayGainMode.ALBUM, playingAlbumInOrder = false)).isWithin(1e-5f).of(1.25f)
        val quiet = track(trackGain = -3f, albumGain = -6f, albumPeak = 0.9f)
        assertThat(ReplayGainMath.factor(quiet, ReplayGainMode.ALBUM, false)).isWithin(1e-5f).of(db(-6f))
    }

    @Test fun replayGainMissingTagsIsUnity() {
        ReplayGainMode.entries.forEach { mode ->
            assertThat(ReplayGainMath.factor(track(), mode, true, preampDb = 3f)).isEqualTo(1f)
        }
        assertThat(ReplayGainMath.factor(track(trackGain = -6f, albumGain = -3f), ReplayGainMode.OFF, true)).isEqualTo(1f)
    }

    @Test fun autoModeUsesTrackGainWhenShuffled() {
        val t = track(trackGain = -8f, albumGain = -4f)
        assertThat(ReplayGainMath.factor(t, ReplayGainMode.AUTO, playingAlbumInOrder = false)).isWithin(1e-5f).of(db(-8f))
        assertThat(ReplayGainMath.factor(t, ReplayGainMode.AUTO, playingAlbumInOrder = true)).isWithin(1e-5f).of(db(-4f))
    }

    @Test fun eachModeFallsBackToTheOtherGain() {
        val onlyAlbum = track(albumGain = -4f, albumPeak = 0.5f)
        val onlyTrack = track(trackGain = -8f, trackPeak = 0.5f)
        assertThat(ReplayGainMath.factor(onlyAlbum, ReplayGainMode.TRACK, false)).isWithin(1e-5f).of(db(-4f))
        assertThat(ReplayGainMath.factor(onlyTrack, ReplayGainMode.ALBUM, false)).isWithin(1e-5f).of(db(-8f))
    }

    @Test fun preampAddsBeforeThePeakClamp() {
        val t = track(trackGain = -6f)
        assertThat(ReplayGainMath.factor(t, ReplayGainMode.TRACK, false, preampDb = 2f)).isWithin(1e-5f).of(db(-4f))
        // Unknown peak (null or 0): no clamp.
        assertThat(ReplayGainMath.factor(track(trackGain = 6f, trackPeak = 0f), ReplayGainMode.TRACK, false)).isWithin(1e-5f).of(db(6f))
        assertThat(ReplayGainMath.factor(track(trackGain = 0f, trackPeak = 0.5f), ReplayGainMode.TRACK, false, preampDb = 12f))
            .isWithin(1e-5f).of(2f)
    }
}
