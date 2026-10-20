package app.granger.core.research

import java.text.Normalizer
import java.util.Locale

/** Whether a question asks about music or audiobooks, which lets more than one media passage reach the answer. */
object MediaCues {
    private val CUES = setOf(
        "music", "album", "albums", "song", "songs", "track", "tracks", "listen", "listening", "recording", "recordings",
        // Not "score": "the score of the dodgers game" lifted a film score's card (m3-tune-1, unanswerable-031 and -033).
        "record", "records", "soundtrack", "symphony", "composer", "composed", "band", "singer",
        "audiobook", "audiobooks", "narrator", "narrated",
    )
    private val WORD = Regex("[\\p{L}\\p{N}]+")
    private val MARKS = Regex("\\p{M}+")

    /** Phrases that ask what's on the phone: "Do I have any Holst?" names no media word. */
    private val PHRASES = listOf(listOf("read", "by"), listOf("do", "i", "have"), listOf("have", "i", "got"))

    /** A cue word as a whole word (accents and case folded), or one of the [PHRASES]. */
    fun asked(question: String): Boolean {
        val folded = MARKS.replace(Normalizer.normalize(question, Normalizer.Form.NFKD), "").lowercase(Locale.ROOT)
        val words = WORD.findAll(folded).map { it.value }.toList()
        return words.any { it in CUES } || PHRASES.any { p -> words.windowed(p.size).any { it == p } }
    }
}
