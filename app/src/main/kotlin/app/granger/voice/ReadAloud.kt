package app.granger.voice

import app.granger.core.player.PlayerHub
import app.granger.core.speech.ReadAddress
import app.granger.core.speech.ReadSource
import app.granger.core.speech.ReadSourceKey
import app.granger.core.speech.VoiceId
import app.granger.ebook.BookReadSource
import app.granger.research.ResearchViewModel

/** What [ReadAloud.start] can read: an article, a book (or one of its chapters, by the contents entry's href), a Research answer. */
sealed interface ReadTarget {
    /** [title] names the article in the player and the rail; the path's last segment stands in when it is unknown. */
    data class Article(val packId: String, val path: String, val title: String? = null) : ReadTarget

    data class Book(val bookId: String, val chapterHref: String? = null) : ReadTarget

    data class Answer(val turnId: Long) : ReadTarget
}

/**
 * The app-level way to start the voice on something (spec §7, Plan V2), for project 4's actions and anything else
 * that isn't a reader with the page in front of it ("Read the book of Mark" is `start(Book(id, chapterHref))`). The
 * target's source is opened the way a restore opens it ([sources], the container's `readSources`) and handed to the
 * player, which saves its place and shows it as it does any read.
 */
class ReadAloud(
    private val hub: PlayerHub,
    private val sources: suspend (key: String, title: String) -> ReadSource?,
) {
    /** True when the voice was asked to read; false when the target can't be opened (gone, or not a readable kind). */
    suspend fun start(target: ReadTarget, from: ReadAddress? = null, voice: VoiceId? = null): Boolean {
        val (key, title) = when (target) {
            is ReadTarget.Article -> ReadSourceKey.article(target.packId, target.path) to
                (target.title ?: target.path.substringAfterLast('/').substringBefore('#').replace('_', ' '))
            is ReadTarget.Book -> ReadSourceKey.book(target.bookId) to ""
            is ReadTarget.Answer -> ResearchViewModel.answerKey(target.turnId) to ""
        }
        val source = runCatching { sources(key, title) }.getOrNull() ?: return false
        val at = from ?: (target as? ReadTarget.Book)?.chapterHref?.let { href -> (source as? BookReadSource)?.addressAtHref(href) }
        hub.readAloud(source, at, voice)
        return true
    }
}

/**
 * Where a reader's Listen starts on a text (Plan U2): at its saved place ([saved], the voice's `voice_states` row) when
 * the page is at or before it, so coming back to a text goes on where the voice stopped; when the page is past it
 * (scrolled or turned on), the first visible sentence ([visible]) wins. A saved place the text no longer has, or one
 * it can't place against the page, leaves [visible].
 */
suspend fun listenStart(source: ReadSource, visible: ReadAddress, saved: ReadAddress?): ReadAddress {
    if (saved == null) return visible
    val located = runCatching { source.locate(saved) }.getOrNull() ?: return visible
    val order = runCatching { source.compare(visible, located) }.getOrNull() ?: return visible
    return if (order <= 0) located else visible
}
