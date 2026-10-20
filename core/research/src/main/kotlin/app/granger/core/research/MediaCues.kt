package app.granger.core.research

import java.text.Normalizer
import java.util.Locale

/** Whether a question asks about music or audiobooks, which lets more than one media passage reach the answer. */
object MediaCues {
    private val CUES = setOf(
        "music", "album", "albums", "song", "songs", "track", "tracks", "listen", "listening", "recording", "recordings",
        "record", "records", "soundtrack", "score", "symphony", "composer", "composed", "band", "singer",
        "audiobook", "audiobooks", "narrator", "narrated",
    )
    private val WORD = Regex("[\\p{L}\\p{N}]+")
    private val MARKS = Regex("\\p{M}+")

    /** A cue word as a whole word (accents and case folded), or the phrase "read by". */
    fun asked(question: String): Boolean {
        val folded = MARKS.replace(Normalizer.normalize(question, Normalizer.Form.NFKD), "").lowercase(Locale.ROOT)
        val words = WORD.findAll(folded).map { it.value }.toList()
        return words.any { it in CUES } || words.zipWithNext().any { (a, b) -> a == "read" && b == "by" }
    }
}
