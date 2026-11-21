package app.granger.core.archive

/**
 * The date rules Granger applies before the waiver. Uploader licence tags are never trusted (spec §3.3). Shared since
 * Plan I5 (and on the App Store, call 9: books published before 1930 only); the rules take this year, which Android's
 * `LocalDate` forms pass on (androidMain).
 */
object PublicDomain {
    const val LAST_BOOK_YEAR = 1930
    /** The oldest recorded sound; it also drops items archive.org dates 0. */
    const val EARLIEST_RECORDING_YEAR = 1860

    /** Recordings published before January 1 of (current year − 100): 1925 in 2026, 1926 in 2027. */
    fun lastRecordingYear(year: Int): Int = year - 101

    /** TEXTS: "year:[* TO 1930]"; audio: "year:[1860 TO <lastRecordingYear>]". */
    fun clause(kind: ArchiveKind, year: Int): String = when (kind) {
        ArchiveKind.TEXTS -> "year:[* TO $LAST_BOOK_YEAR]"
        ArchiveKind.SPOKEN_WORD, ArchiveKind.MUSIC -> "year:[$EARLIEST_RECORDING_YEAR TO ${lastRecordingYear(year)}]"
    }
}
