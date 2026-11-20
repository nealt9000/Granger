package app.granger.core.library

import androidx.room.Dao
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/** One name from the library as stored: a [title] (pack, book, album, audiobook) or a person (author, artist). */
data class LibraryNameRow(val title: Boolean, val name: String)

/**
 * Every name in the library that a person might say (Plan A1: what the spoken title matcher matches against). One
 * query over packs, books, audiobooks and music, so Room re-emits whenever any of them changes. Missing files are
 * left out. Audiobook file tracks are not music and are left out too.
 */
@Dao
interface LibraryNameDao {
    @Query(
        """
        SELECT 1 AS title, title AS name FROM packs WHERE status = 'OK'
        UNION SELECT 1, title FROM books WHERE status = 'OK'
        UNION SELECT 0, author FROM books WHERE status = 'OK'
        UNION SELECT 1, title FROM audiobooks WHERE status = 'OK'
        UNION SELECT 0, author FROM audiobooks WHERE status = 'OK'
        UNION SELECT 1, album FROM tracks WHERE audiobookId IS NULL AND status = 'OK'
        UNION SELECT 0, artists FROM tracks WHERE audiobookId IS NULL AND status = 'OK'
        UNION SELECT 0, albumArtists FROM tracks WHERE audiobookId IS NULL AND status = 'OK'
        """,
    )
    fun observe(): Flow<List<LibraryNameRow>>
}

/**
 * Library names in the form a person says them (Plan A1), ported from `tools/asr/bias_list.py`: a title without its
 * subtitle, bracketed translator or file-name underscores; a person without a bracketed full name; multi-artist
 * fields (`"; "`-joined) split. Duplicates (ignoring case), names under three characters, names with no letter and
 * the placeholder artists are dropped.
 */
object LibraryNames {
    private val SUBTITLE = Regex("""\s*[:(\[]|\s+[—–-]\s+|\s+/\s+""")
    private val BRACKETED = Regex("""\([^)]*\)""")
    private val SPACES = Regex("""\s+""")
    private val PLACEHOLDERS = setOf("various", "various artists", "unknown", "unknown artist", "unknown album")

    fun from(rows: List<LibraryNameRow>): List<String> {
        val seen = HashSet<String>()
        val out = ArrayList<String>()
        for (row in rows) {
            val parts = if (row.title) listOf(shortTitle(row.name)) else row.name.split(';').map(::shortPerson)
            for (name in parts) {
                val key = name.lowercase()
                if (name.length >= 3 && name.any(Char::isLetter) && key !in PLACEHOLDERS && seen.add(key)) out += name
            }
        }
        return out
    }

    /** "The Republic: Book I (tr. Jowett)" → "The Republic". */
    fun shortTitle(title: String): String =
        title.replace('_', ' ').split(SUBTITLE, limit = 2)[0].replace(SPACES, " ").trim(' ', '.', ';', ',')

    /** "George T. (George Titus) Ferris" → "George T. Ferris". */
    fun shortPerson(person: String): String =
        person.replace(BRACKETED, "").replace(SPACES, " ").trim(' ', '.', ';', ',')
}
