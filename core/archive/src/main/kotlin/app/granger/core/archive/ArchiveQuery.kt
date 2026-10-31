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

/**
 * [title] is the chip's string resource. [spanish] are the subject words a Spanish library adds to a `subject:` shelf
 * (Plan L3): archive.org's Spanish items carry Spanish subjects.
 */
enum class ArchiveShelf(val kind: ArchiveKind, val title: Int, val clause: String, val spanish: String = "") {
    MOST_DOWNLOADED(ArchiveKind.TEXTS, R.string.archive_shelf_most_downloaded, ""),
    MANUALS(ArchiveKind.TEXTS, R.string.archive_shelf_manuals, "collection:manuals"),
    SCIENCE(
        ArchiveKind.TEXTS, R.string.archive_shelf_science, "subject:(science OR engineering OR mechanics OR chemistry OR physics)",
        spanish = "ciencia OR ciencias OR ingeniería OR mecánica OR química OR física",
    ),
    LITERATURE(
        ArchiveKind.TEXTS, R.string.archive_shelf_literature, "subject:(fiction OR literature OR poetry OR drama)",
        spanish = "literatura OR novela OR cuentos OR poesía OR teatro",
    ),
    SPOKEN_MOST_DOWNLOADED(ArchiveKind.SPOKEN_WORD, R.string.archive_shelf_most_downloaded, ""),
    SPOKEN_POETRY(ArchiveKind.SPOKEN_WORD, R.string.archive_shelf_poetry, "subject:poetry", spanish = "poesía"),
    SPOKEN_DRAMA(ArchiveKind.SPOKEN_WORD, R.string.archive_shelf_drama, "subject:(drama OR plays)", spanish = "teatro"),
    MUSIC_MOST_DOWNLOADED(ArchiveKind.MUSIC, R.string.archive_shelf_most_downloaded, ""),
    MUSIC_78(ArchiveKind.MUSIC, R.string.archive_shelf_78, "collection:78rpm"),
    MUSIC_CLASSICAL(ArchiveKind.MUSIC, R.string.archive_shelf_classical, "subject:classical"),
    MUSIC_FOLK(ArchiveKind.MUSIC, R.string.archive_shelf_folk, "subject:(folk OR \"field recordings\")"),
    ;

    /** The shelf's clause for a library in [languages] (Plan L3): with Spanish, its subject words join the English ones. */
    fun clause(languages: List<String>): String {
        if (spanish.isEmpty() || "es" !in languages) return clause
        val subjects = clause.removePrefix("subject:").removePrefix("(").removeSuffix(")")
        return "subject:($subjects OR $spanish)"
    }

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

    /** archive.org's `language` values per knowledge language: the MARC code and the English name both occur. */
    private val LANGUAGE_TERMS = linkedMapOf("en" to listOf("eng", "English"), "es" to listOf("spa", "Spanish"))

    /**
     * The `language:` clause for a library in [languages] (Plan L3), or null: an English-only library asks as before, with
     * no clause (many English items carry no language). With Spanish: `language:(spa OR Spanish)`, and with English too
     * `language:(eng OR English OR spa OR Spanish)`, in the library's order. Music has no language to ask by.
     */
    fun languageClause(languages: List<String>, kind: ArchiveKind = ArchiveKind.TEXTS): String? {
        if (kind == ArchiveKind.MUSIC || languages.none { it != "en" && it in LANGUAGE_TERMS }) return null
        val terms = languages.distinct().flatMap { LANGUAGE_TERMS[it].orEmpty() }
        return "language:(${terms.joinToString(" OR ")})"
    }

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
        /** The library's languages, main first (Plan L3): they add a `language:` clause and the shelves' Spanish words. */
        languages: List<String> = listOf("en"),
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
            if (shelf != null && shelf.clause.isNotEmpty()) append(" AND ").append(shelf.clause(languages))
            languageClause(languages, kind)?.let { append(" AND ").append(it) }
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
