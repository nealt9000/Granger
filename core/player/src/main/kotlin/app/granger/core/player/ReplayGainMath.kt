package app.granger.core.player

import app.granger.core.library.Track
import kotlin.math.pow

/** Which ReplayGain value to apply. AUTO (the default) is Auxio's: album gain when an album plays in order, else track. */
enum class ReplayGainMode { OFF, TRACK, ALBUM, AUTO }

object ReplayGainMath {
    /**
     * Linear gain for [t] under [mode]; AUTO uses album gain when [playingAlbumInOrder]. Clamps so gain × peak ≤ 1.
     * Missing tags → 1f.
     *
     * The gain is 10^((gainDb + [preampDb]) / 20). TRACK falls back to the album gain when the track has none, and
     * ALBUM to the track gain. The clamp uses the peak that goes with the gain used, and only when it is known (> 0).
     * OFF, or no gain at all, gives exactly 1f (the pre-amp is not applied to untagged files).
     */
    fun factor(t: Track, mode: ReplayGainMode, playingAlbumInOrder: Boolean, preampDb: Float = 0f): Float {
        val album = t.rgAlbumGain?.let { it to t.rgAlbumPeak }
        val track = t.rgTrackGain?.let { it to t.rgTrackPeak }
        val useAlbum = when (mode) {
            ReplayGainMode.OFF -> return 1f
            ReplayGainMode.TRACK -> false
            ReplayGainMode.ALBUM -> true
            ReplayGainMode.AUTO -> playingAlbumInOrder
        }
        val (gainDb, peak) = (if (useAlbum) album ?: track else track ?: album) ?: return 1f
        val gain = 10.0.pow((gainDb + preampDb) / 20.0).toFloat()
        return if (peak != null && peak > 0f) minOf(gain, 1f / peak) else gain
    }
}
