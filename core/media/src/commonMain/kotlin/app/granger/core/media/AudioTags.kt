package app.granger.core.media

/** ReplayGain values in dB (ReplayGain 2.0 reference, -18 LUFS) and linear peaks; null where the file has none. */
data class ReplayGain(val trackGainDb: Float?, val trackPeak: Float?, val albumGainDb: Float?, val albumPeak: Float?)

/** Sort-order tags; "" where the file has none. */
data class SortTags(val title: String, val album: String, val artist: String, val albumArtist: String)

/**
 * The typed tags of one audio file. Strings are "" and lists are empty where the file has no value;
 * "Unknown album" / "Unknown artist" are display-time fallbacks, not stored here.
 */
data class AudioTags(
    val title: String, val album: String, val albumArtists: List<String>, val artists: List<String>,
    val composers: List<String>, val genres: List<String>, val disc: Int?, val discTotal: Int?,
    val track: Int?, val trackTotal: Int?, val date: String, val originalDate: String,
    /** Lower-case MusicBrainz release type: "album", "ep", "single", "compilation", "live", "soundtrack"…; "" if unknown. */
    val releaseType: String, val sort: SortTags, val replayGain: ReplayGain?, val durationMs: Long,
)
