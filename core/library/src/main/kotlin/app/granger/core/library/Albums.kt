package app.granger.core.library

import java.text.Normalizer
import java.util.Locale

/**
 * An album as the library shows it, built from its tracks. [title] and [albumArtist] already carry the
 * "Unknown album" / "Unknown artist" fallbacks. [status] is OK while any track is OK.
 */
data class AlbumSummary(
    val key: String,
    val title: String,
    val albumArtist: String,
    val year: String,
    val releaseType: String,
    val trackCount: Int,
    val durationMs: Long,
    val coverPath: String?,
    val status: PackStatus,
)

/** Album identity, grouping and track order. */
object Albums {
    const val UNKNOWN_ALBUM = "Unknown album"
    const val UNKNOWN_ARTIST = "Unknown artist"

    private val WHITESPACE = Regex("\\s+")

    /** NFKC, internal whitespace runs collapsed to one space, trimmed, lower-cased. */
    private fun normalise(s: String): String =
        Normalizer.normalize(s, Normalizer.Form.NFKC).replace(WHITESPACE, " ").trim().lowercase(Locale.ROOT)

    /** "<normalised album artist>|<normalised album>"; untitled albums: "|unknown|<folder>". Normalised = trimmed, lower-cased, NFKC. */
    fun key(albumArtist: String, album: String, folder: String): String {
        val title = normalise(album)
        return if (title.isEmpty()) "|unknown|$folder" else "${normalise(albumArtist)}|$title"
    }

    /** One summary per album key, sorted by sortAlbumArtist then year then sortAlbum. Takes music tracks of any status. */
    fun summaries(tracks: List<Track>): List<AlbumSummary> =
        tracks.groupBy { it.albumKey }
            .map { (key, group) -> summary(key, ordered(group)) }
            .sortedWith(compareBy<Pair<AlbumSummary, Track>>(
                { sortName(it.second.sortAlbumArtist, it.first.albumArtist) },
                { it.first.year.ifEmpty { "￿" } },
                { sortName(it.second.sortAlbum, it.first.title) },
                { it.first.key },
            ))
            .map { it.first }

    /** Disc then track number then title; a missing disc counts as disc 1, a missing track number sorts last. */
    fun ordered(tracks: List<Track>): List<Track> =
        tracks.sortedWith(compareBy(
            { it.disc ?: 1 },
            { it.trackNo ?: Int.MAX_VALUE },
            { sortName(it.sortTitle, it.title) },
            { it.id },
        ))

    private fun sortName(sort: String, name: String) = sort.ifBlank { name }.lowercase(Locale.ROOT)

    /** The summary, paired with the first track (which holds the album's sort tags). */
    private fun summary(key: String, ordered: List<Track>): Pair<AlbumSummary, Track> {
        val first = ordered.first()
        val summary = AlbumSummary(
            key = key,
            title = ordered.firstOrNull { it.album.isNotBlank() }?.album?.trim() ?: UNKNOWN_ALBUM,
            albumArtist = ordered.firstOrNull { it.albumArtists.isNotBlank() }?.albumArtists?.trim() ?: UNKNOWN_ARTIST,
            year = ordered.map { it.date.trim() }.filter { it.isNotEmpty() }.minOrNull()?.take(4).orEmpty(),
            releaseType = ordered.firstOrNull { it.releaseType.isNotBlank() }?.releaseType.orEmpty(),
            trackCount = ordered.size,
            durationMs = ordered.sumOf { it.durationMs },
            coverPath = ordered.firstNotNullOfOrNull { it.coverPath },
            status = if (ordered.any { it.status == PackStatus.OK }) PackStatus.OK else PackStatus.MISSING,
        )
        return summary to first
    }
}
