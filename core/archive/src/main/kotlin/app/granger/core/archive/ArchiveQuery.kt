package app.granger.core.archive

import java.time.LocalDate

/**
 * What an archive.org view lists. [noun] names it in copy; [base] is its clause: archive.org's curated collections.
 * [wideBase], for an audio kind, is what a search with the waiver accepted covers: all of archive.org's audio, community
 * uploads included, less podcasts, LibriVox (its own source) and the other audio kind's curated collections. Shelves
 * keep [base]: browsing all audio by downloads is mostly sermons and recitations (Pixel 6 walkthrough, 2026-10-19).
 */
enum class ArchiveKind(val noun: String, val base: String, val datedRecordings: Boolean, val wideBase: String = base) {
    TEXTS("texts", "mediatype:texts", datedRecordings = false),
    // archive.org dates spoken word by its text, so it has no usable recording date: nothing is searched before the waiver (Decisions).
    SPOKEN_WORD(
        "audiobooks", "mediatype:audio AND collection:audio_bookspoetry AND NOT collection:librivoxaudio", datedRecordings = false,
        wideBase = "mediatype:audio AND NOT collection:podcasts* AND NOT collection:librivoxaudio AND NOT collection:audio_music",
    ),
    MUSIC(
        "music", "mediatype:audio AND collection:audio_music", datedRecordings = true,
        wideBase = "mediatype:audio AND NOT collection:podcasts* AND NOT collection:librivoxaudio AND NOT collection:audio_bookspoetry" +
            " AND NOT collection:*audiobooks*",
    ),
}

enum class ArchiveShelf(val kind: ArchiveKind, val title: String, val clause: String) {
    MOST_DOWNLOADED(ArchiveKind.TEXTS, "Most downloaded", ""),
    MANUALS(ArchiveKind.TEXTS, "Technical manuals", "collection:manuals"),
    SCIENCE(ArchiveKind.TEXTS, "Science & engineering", "subject:(science OR engineering OR mechanics OR chemistry OR physics)"),
    LITERATURE(ArchiveKind.TEXTS, "Literature", "subject:(fiction OR literature OR poetry OR drama)"),
    SPOKEN_MOST_DOWNLOADED(ArchiveKind.SPOKEN_WORD, "Most downloaded", ""),
    SPOKEN_POETRY(ArchiveKind.SPOKEN_WORD, "Poetry", "subject:poetry"),
    SPOKEN_DRAMA(ArchiveKind.SPOKEN_WORD, "Drama", "subject:(drama OR plays)"),
    MUSIC_MOST_DOWNLOADED(ArchiveKind.MUSIC, "Most downloaded", ""),
    MUSIC_78(ArchiveKind.MUSIC, "78 rpm records", "collection:78rpm"),
    MUSIC_CLASSICAL(ArchiveKind.MUSIC, "Classical", "subject:classical"),
    MUSIC_FOLK(ArchiveKind.MUSIC, "Folk & field recordings", "subject:(folk OR \"field recordings\")"),
    ;

    companion object {
        fun of(kind: ArchiveKind): List<ArchiveShelf> = entries.filter { it.kind == kind }
        fun first(kind: ArchiveKind): ArchiveShelf = of(kind).first()
    }
}

object ArchiveQuery {
    /**
     * Date only: published 1930 or earlier. Changed 2026-10-17 after the Pixel 6 walkthrough: uploader licence tags
     * are not trusted (modern books tagged public domain by their uploaders showed up in the filtered view).
     */
    const val PUBLIC_DOMAIN = "year:[* TO ${PublicDomain.LAST_BOOK_YEAR}]"

    private val identifier = Regex("[A-Za-z0-9._-]+")

    /**
     * Texts keep Plan 7's date-only filter. An audio kind is filtered by the rolling recording range for [today]; a
     * filtered audio kind without [ArchiveKind.datedRecordings] (spoken word) has nothing it may show, so it throws,
     * as does a [shelf] of another kind. An audio kind's [text] matches titles and creators only (matched against every
     * field and ranked by downloads, popular items that merely mention the words came first), and unfiltered it searches
     * [ArchiveKind.wideBase].
     */
    fun build(
        text: String,
        shelf: ArchiveShelf?,
        filtered: Boolean,
        kind: ArchiveKind = ArchiveKind.TEXTS,
        today: LocalDate = LocalDate.now(),
    ): String {
        require(shelf == null || shelf.kind == kind) { "Shelf ${shelf?.name} isn't a ${kind.noun} shelf" }
        require(!filtered || kind == ArchiveKind.TEXTS || kind.datedRecordings) {
            "archive.org ${kind.noun} have no recording date to filter by"
        }
        val cleaned = balance(text).trim()
        val audio = kind != ArchiveKind.TEXTS
        return buildString {
            append(if (audio && !filtered && cleaned.isNotEmpty()) kind.wideBase else kind.base)
            if (cleaned.isNotEmpty()) {
                if (audio) append(" AND (title:(").append(cleaned).append(") OR creator:(").append(cleaned).append("))")
                else append(" AND (").append(cleaned).append(')')
            }
            if (shelf != null && shelf.clause.isNotEmpty()) append(" AND ").append(shelf.clause)
            if (filtered) append(" AND ").append(PublicDomain.clause(kind, today))
        }
    }

    fun isValidIdentifier(id: String): Boolean = identifier.matches(id) && !id.startsWith(".")

    /**
     * Drops all quotes if they don't pair up, and every parenthesis: counting them can't tell one inside a quoted
     * phrase from one outside, so any of them could close the `AND (…)` group early.
     */
    private fun balance(text: String): String {
        var out = text.replace("(", "").replace(")", "")
        if (out.count { it == '"' } % 2 != 0) out = out.replace("\"", "")
        return out
    }
}
