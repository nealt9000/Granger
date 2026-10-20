package app.granger.core.library

import java.text.Normalizer
import java.util.Locale

/**
 * An album as the library shows it, built from its present tracks (MISSING ones aren't part of it). [title] is
 * [Albums.title] (an untagged album is named after its folder) and [albumArtist] carries the "Unknown artist" fallback. [status] is OK while any track is OK.
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

    /**
     * "<normalised album artist>|<normalised album>"; untitled albums: "|unknown|<folder>". Normalised = trimmed, lower-cased, NFKC.
     * A tagged key that would start with [itemKey]'s prefix (no album artist, an album tag "item|...") gets a leading
     * space, which no other key has, so a phone album can never join a downloaded one.
     */
    fun key(albumArtist: String, album: String, folder: String): String {
        val title = normalise(album)
        if (title.isEmpty()) return "$UNTAGGED$folder"
        val key = "${normalise(albumArtist)}|$title"
        return if (key.startsWith(ITEM)) " $key" else key
    }

    /**
     * An album that is one origin item, whatever its files' tags: "|item|<source>:<ref>" ("|item|archive-org:<identifier>").
     * Never equal to a [key] result. Downloaded albums are grouped by this alone: their tags never split or merge them.
     */
    fun itemKey(source: String, ref: String): String = "$ITEM$source:$ref"

    /**
     * An album's title wherever it is shown: its tag, else (an untagged album, grouped by folder) the folder's name,
     * "Music/Holst - The Planets (LSO 1922-24)/" → "Holst - The Planets (LSO 1922-24)"; [UNKNOWN_ALBUM] only when the
     * folder is blank too. Display only: [key] is unchanged.
     */
    fun title(album: String, folder: String): String =
        album.trim().ifEmpty { folder.trim().trimEnd('/').substringAfterLast('/').trim().ifEmpty { UNKNOWN_ALBUM } }

    fun title(track: Track): String = title(track.album, track.folder)

    /** For an untagged album's [key] ("|unknown|<folder>") its [title]; null for a tagged one (its key holds no title). */
    fun untaggedTitle(key: String): String? = key.takeIf { it.startsWith(UNTAGGED) }?.let { title("", it.removePrefix(UNTAGGED)) }

    private const val UNTAGGED = "|unknown|"
    private const val ITEM = "|item|"

    /** Tracks the library shows: MISSING ones are left out (their rows stay, and come back when the file does). */
    fun present(tracks: List<Track>): List<Track> = tracks.filter { it.status != PackStatus.MISSING }

    /**
     * One summary per album key, sorted by sortAlbumArtist then year then sortAlbum. Takes music tracks of any status
     * and counts only [present] ones, so an album whose tracks are all MISSING isn't there.
     */
    fun summaries(tracks: List<Track>): List<AlbumSummary> =
        present(tracks).groupBy { it.albumKey }
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
            title = title(ordered.firstOrNull { it.album.isNotBlank() } ?: first),
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
