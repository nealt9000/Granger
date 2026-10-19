package app.granger.core.library

/**
 * Position addresses for deep links (spec §10): `<track id>@<ms>` and `<audiobook id>@<chapter>/<ms>`, so
 * `track:phone:ab12@151000` and `audiobook:phone:cd34@3/90500`. Ids contain colons but never `@`, so the address
 * splits on its last `@`.
 */
object MediaAddress {
    private const val TRACK = "track:"
    private const val AUDIOBOOK = "audiobook:"

    sealed interface Parsed {
        data class TrackAt(val id: String, val ms: Long) : Parsed
        data class AudiobookAt(val id: String, val chapter: Int, val ms: Long) : Parsed
    }

    fun track(id: String, ms: Long): String {
        requireId(id, TRACK)
        require(ms >= 0) { "Negative position: $ms" }
        return "$id@$ms"
    }

    fun audiobook(id: String, chapter: Int, ms: Long): String {
        requireId(id, AUDIOBOOK)
        require(chapter >= 0) { "Negative chapter: $chapter" }
        require(ms >= 0) { "Negative position: $ms" }
        return "$id@$chapter/$ms"
    }

    /** Null for anything that isn't a well-formed address. */
    fun parse(s: String): Parsed? {
        val at = s.lastIndexOf('@')
        if (at < 0) return null
        val id = s.substring(0, at)
        val position = s.substring(at + 1)
        return when {
            isId(id, TRACK) -> number(position)?.let { Parsed.TrackAt(id, it) }
            isId(id, AUDIOBOOK) -> {
                val parts = position.split('/')
                if (parts.size != 2) return null
                val chapter = number(parts[0])?.takeIf { it <= Int.MAX_VALUE } ?: return null
                val ms = number(parts[1]) ?: return null
                Parsed.AudiobookAt(id, chapter.toInt(), ms)
            }
            else -> null
        }
    }

    private fun isId(id: String, prefix: String) = id.length > prefix.length && id.startsWith(prefix) && '@' !in id

    private fun requireId(id: String, prefix: String) = require(isId(id, prefix)) { "Not a ${prefix.dropLast(1)} id: $id" }

    /** Plain decimal digits only: no sign, no spaces, and it must fit a Long. */
    private fun number(s: String): Long? = if (s.isNotEmpty() && s.all { it in '0'..'9' }) s.toLongOrNull() else null
}
