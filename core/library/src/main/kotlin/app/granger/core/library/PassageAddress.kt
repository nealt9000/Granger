package app.granger.core.library

import app.granger.core.research.CitedQuote
import app.granger.core.research.Passage
import app.granger.core.research.SourceKind

/**
 * Where a cited passage lives (research spec §7), one string per passage, reused by read-aloud and by plans and
 * playlists:
 *
 * - `article:<packId>/<path>#<anchor>~<quote>` (anchor and quote optional), `pack:<packId>` for a pack's main page;
 * - `book:<origin>:<ref>/<href>@<progression>~<quote>` (quote optional; an empty href is "where it was left");
 * - `track:<id>@<ms>` and `audiobook:<id>@<chapter>/<ms>` ([MediaAddress]); `album:<albumKey>` and the bare
 *   `audiobook:<id>` for a whole album or audiobook (which resumes where it was left).
 *
 * Every field is percent-encoded for `%`, `#`, `~`, `@` and `?` (and `/`, except inside paths and hrefs), so ids holding
 * `:` and paths holding `/` split cleanly.
 */
sealed interface PassageAddress {
    fun format(): String

    data class Article(val packId: String, val path: String, val anchor: String? = null, val quote: String? = null) : PassageAddress {
        override fun format() = buildString {
            append(ARTICLE).append(enc(packId)).append('/').append(encPath(path))
            if (!anchor.isNullOrEmpty()) append('#').append(enc(anchor))
            if (!quote.isNullOrEmpty()) append('~').append(enc(quote))
        }
    }

    data class Pack(val packId: String) : PassageAddress {
        override fun format() = PACK + enc(packId)
    }

    /** [bookId] is the full `book:<origin>:<ref>`; [href] "" when the place isn't known. */
    data class Book(val bookId: String, val href: String, val progression: Double, val quote: String? = null) : PassageAddress {
        override fun format() = buildString {
            append(BOOK).append(enc(bookId.removePrefix(BOOK))).append('/').append(encPath(href)).append('@').append(progression)
            if (!quote.isNullOrEmpty()) append('~').append(enc(quote))
        }
    }

    data class Album(val albumKey: String) : PassageAddress {
        override fun format() = ALBUM + enc(albumKey)
    }

    data class Track(val id: String, val ms: Long = 0) : PassageAddress {
        override fun format() = MediaAddress.track(id, ms)
    }

    /** [chapter] null is the whole audiobook. */
    data class Audiobook(val id: String, val chapter: Int? = null, val ms: Long = 0) : PassageAddress {
        override fun format() = if (chapter == null) id else MediaAddress.audiobook(id, chapter, ms)
    }

    companion object {
        private const val ARTICLE = "article:"
        private const val PACK = "pack:"
        private const val BOOK = "book:"
        private const val ALBUM = "album:"
        private const val TRACK = "track:"
        private const val AUDIOBOOK = "audiobook:"
        private const val CHAPTER = "chapter:"
        private const val RESERVED = "%#~@?/"
        private const val RESERVED_IN_PATH = "%#~@?"

        /** The address of [passage]; [quote] is the cited sentence, when one was picked. */
        fun of(passage: Passage, quote: String? = null): PassageAddress = when (passage.kind) {
            SourceKind.ARTICLE -> Article(passage.sourceId, passage.location, passage.anchor, quote)
            SourceKind.CARD -> if (BookIds.isBook(passage.sourceId)) Book(passage.sourceId, "", 0.0) else Pack(passage.sourceId)
            SourceKind.BOOK -> Book(passage.sourceId, passage.location, passage.progression, quote)
            SourceKind.SELECTION ->
                if (!BookIds.isBook(passage.sourceId)) Article(passage.sourceId, passage.location, passage.anchor, quote)
                else if (passage.location.isEmpty()) Book(passage.sourceId, "", 0.0, quote)
                else Book(passage.sourceId, passage.location, passage.progression, quote)
            SourceKind.MEDIA -> media(passage)
        }

        /**
         * The sentence of [passage] that [answer] used, citing it as `[n]`, with its context; null for the kinds that
         * don't open at text (cards and media).
         */
        fun quoteOf(passage: Passage, answer: String, n: Int): CitedQuote.Quote? =
            if (passage.kind in QUOTED) CitedQuote.pick(answer, n, passage.text) else null

        /** [passage]'s address as cited `[n]` in [answer]. */
        fun cited(passage: Passage, answer: String, n: Int): PassageAddress = of(passage, quoteOf(passage, answer, n)?.sentence)

        private val QUOTED = setOf(SourceKind.ARTICLE, SourceKind.BOOK, SourceKind.SELECTION)

        /** A media card focused on a part has `location` = the track id or `chapter:<ordinal>`; a whole work has "". */
        private fun media(passage: Passage): PassageAddress {
            val location = passage.location
            return when {
                location.startsWith(TRACK) -> Track(location)
                location.startsWith(CHAPTER) -> Audiobook(passage.sourceId, location.removePrefix(CHAPTER).toIntOrNull()?.coerceAtLeast(0) ?: 0)
                passage.sourceId.startsWith(ALBUM) -> Album(passage.sourceId.removePrefix(ALBUM))
                else -> Audiobook(passage.sourceId)
            }
        }

        /** Null for anything that isn't a well-formed address. */
        fun parse(s: String): PassageAddress? = try {
            when {
                s.startsWith(ARTICLE) -> article(s.removePrefix(ARTICLE))
                s.startsWith(PACK) -> dec(s.removePrefix(PACK))?.takeIf { it.isNotEmpty() }?.let(::Pack)
                s.startsWith(BOOK) -> book(s.removePrefix(BOOK))
                s.startsWith(ALBUM) -> dec(s.removePrefix(ALBUM))?.takeIf { it.isNotEmpty() }?.let(::Album)
                s.startsWith(AUDIOBOOK) && '@' !in s && s.length > AUDIOBOOK.length -> Audiobook(s)
                else -> when (val m = MediaAddress.parse(s)) {
                    is MediaAddress.Parsed.TrackAt -> Track(m.id, m.ms)
                    is MediaAddress.Parsed.AudiobookAt -> Audiobook(m.id, m.chapter, m.ms)
                    null -> null
                }
            }
        } catch (_: IllegalArgumentException) {
            null
        }

        private fun article(rest: String): Article? {
            val slash = rest.indexOf('/')
            if (slash <= 0) return null
            val packId = dec(rest.substring(0, slash)) ?: return null
            var tail = rest.substring(slash + 1)
            val quote = tail.substringAfter('~', "").let { if ('~' in tail) dec(it) ?: return null else null }
            tail = tail.substringBefore('~')
            val anchor = tail.substringAfter('#', "").let { if ('#' in tail) dec(it) ?: return null else null }
            val path = dec(tail.substringBefore('#')) ?: return null
            return Article(packId, path, anchor?.ifEmpty { null }, quote?.ifEmpty { null })
        }

        private fun book(rest: String): Book? {
            val slash = rest.indexOf('/')
            if (slash <= 0) return null
            val id = dec(rest.substring(0, slash)) ?: return null
            if (':' !in id) return null
            val tail = rest.substring(slash + 1)
            val at = tail.indexOf('@')
            if (at < 0) return null
            val href = dec(tail.substring(0, at)) ?: return null
            val position = tail.substring(at + 1)
            val quote = if ('~' in position) dec(position.substringAfter('~')) ?: return null else null
            val progression = position.substringBefore('~').toDoubleOrNull()?.takeIf { it.isFinite() && it in 0.0..1.0 } ?: return null
            return Book(BOOK + id, href, progression, quote?.ifEmpty { null })
        }

        private fun enc(s: String) = encode(s, RESERVED)

        private fun encPath(s: String) = encode(s, RESERVED_IN_PATH)

        private fun encode(s: String, reserved: String) = buildString {
            for (c in s) if (c in reserved) append('%').append("%02X".format(c.code)) else append(c)
        }

        /** Decodes `%XX` escapes (any byte, as UTF-8); null for a broken escape. */
        private fun dec(s: String): String? {
            if ('%' !in s) return s
            val out = java.io.ByteArrayOutputStream()
            var i = 0
            while (i < s.length) {
                val c = s[i]
                if (c == '%') {
                    val hex = s.substring(i + 1, (i + 3).coerceAtMost(s.length))
                    if (hex.length != 2 || !hex.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) return null
                    out.write(hex.toInt(16))
                    i += 3
                } else {
                    val end = s.indexOf('%', i).let { if (it < 0) s.length else it }
                    out.write(s.substring(i, end).toByteArray(Charsets.UTF_8))
                    i = end
                }
            }
            return out.toString(Charsets.UTF_8.name())
        }
    }
}
