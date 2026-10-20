package app.granger.core.library

import app.granger.core.research.Passage
import app.granger.core.research.SourceKind
import app.granger.core.research.SourceMeta

/**
 * Research's catalogue card for an album or audiobook: one per work, never per song. "Label: value" lines, blank values
 * left off, with the track titles ("Tracks") or chapter titles ("Chapters") cut at [TRACKS_CHARS] and the description at [ABOUT_CHARS].
 */
object MediaCards {
    private const val TRACKS_CHARS = 400
    private const val ABOUT_CHARS = 600
    private val SPACE = Regex("\\s+")

    fun passage(work: MediaWork): Passage {
        val creator = names(MediaNames.split(work.creator).filter { it != Albums.UNKNOWN_ARTIST })
        val text = listOf(
            (if (work.isAlbum) "Album" else "Audiobook") to work.title,
            (if (work.isAlbum) "Artist" else "Author") to creator,
            "Composer" to names(work.composers),
            "Narrator" to names(work.narrators),
            "Genre" to names(work.genres),
            "Year" to work.year,
            (if (work.isAlbum) "Tracks" else "Chapters") to cut(work.partTitles.map(::oneLine).filter { it.isNotEmpty() }.joinToString("; "), TRACKS_CHARS),
            "About" to cut(oneLine(work.description), ABOUT_CHARS),
            "In your library as" to if (work.isAlbum) "Music album" else "Audiobook",
        ).map { (label, value) -> label to oneLine(value) }
            .filter { it.second.isNotEmpty() }
            .joinToString("\n") { (label, value) -> "$label: $value" }
        return Passage(
            kind = SourceKind.MEDIA,
            sourceId = work.id,
            sourceTitle = work.title,
            packTitle = if (work.isAlbum) "Your music" else "Your audiobooks",
            location = "",
            text = text,
            meta = SourceMeta(author = creator),
        )
    }

    private fun names(list: List<String>) = list.map(::oneLine).filter { it.isNotEmpty() }.joinToString(", ")

    private fun oneLine(s: String) = SPACE.replace(s, " ").trim()

    /** At most [max] characters, cut at a word boundary, with "…" when cut. */
    private fun cut(text: String, max: Int): String {
        if (text.length <= max) return text
        val head = text.substring(0, max)
        val space = head.indexOfLast { it.isWhitespace() }
        return (if (space > 0) head.substring(0, space) else head).trimEnd().trimEnd(';', ',') + "…"
    }
}
