package app.granger.core.library

import app.granger.core.research.Keywords
import app.granger.core.research.Passage
import app.granger.core.research.SourceKind
import app.granger.core.research.SourceMeta

/**
 * Research's catalogue card for an album or audiobook: one per work, never per song. "Label: value" lines, blank values
 * left off, with the track titles ("Tracks") or chapter titles ("Chapters") cut at [TRACKS_CHARS] and the description at [ABOUT_CHARS].
 *
 * A card can focus on one song or chapter ([bestPart]): it leads with "Song: <title> (<i> of <n>)" or "Chapter: …",
 * and its location is the part's id, so the citation opens and plays at that part (research spec §7).
 */
object MediaCards {
    private const val TRACKS_CHARS = 400
    private const val ABOUT_CHARS = 600
    private val SPACE = Regex("\\s+")

    /** [part] indexes [MediaWork.partTitles]; null (or out of range) is the whole work. */
    fun passage(work: MediaWork, part: Int? = null): Passage {
        val focus = part?.takeIf { it in work.partTitles.indices && it in work.partIds.indices }
        val creator = names(MediaNames.split(work.creator).filter { it != Albums.UNKNOWN_ARTIST })
        val text = listOfNotNull(
            focus?.let { (if (work.isAlbum) "Song" else "Chapter") to "${work.partTitles[it]} (${it + 1} of ${work.partTitles.size})" },
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
            location = focus?.let { work.partIds[it] } ?: "",
            heading = focus?.let { oneLine(work.partTitles[it]) } ?: "",
            text = text,
            meta = SourceMeta(author = creator),
        )
    }

    /**
     * The song or chapter whose title holds the most of the keywords' words (whole words, folded; stopwords and words
     * under three letters left out); ties go to the earliest, and null when no title holds any (only the album or book
     * matched).
     */
    fun bestPart(work: MediaWork, keywords: List<String>): Int? {
        val wanted = keywords.flatMap(MediaText::words).filter { it.length >= 3 && !Keywords.isStopword(it) }.toSet()
        if (wanted.isEmpty()) return null
        var best: Int? = null
        var bestCount = 0
        work.partTitles.forEachIndexed { i, title ->
            val count = MediaText.words(title).toSet().count { it in wanted }
            if (count > bestCount) {
                best = i
                bestCount = count
            }
        }
        return best
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
