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

    /** The Spanish cues, folded: "música", "canción", "banda sonora" (by "banda"), "audiolibro". */
    private val CUES_ES = setOf(
        "musica", "album", "albumes", "cancion", "canciones", "grabacion", "grabaciones", "disco", "discos", "banda",
        "bandas", "cantante", "cantantes", "compositor", "compositora", "sinfonia", "sinfonias", "audiolibro",
        "audiolibros", "narrador", "narradora", "narrado", "narrada", "escuchar",
    )
    /** "¿Tengo algo de Holst?", "¿Hay algo de Mozart?", "leído por". A first "tengo" is in [tengo]. */
    private val PHRASES_ES = listOf(listOf("leido", "por"), listOf("leida", "por"), listOf("tengo", "algo", "de"), listOf("hay", "algo", "de"))

    private val BANDWIDTH_ES = Regex("\\bancho de banda\\b|\\bbanda ancha\\b")

    /** After a first "tengo", the words that ask what's on the phone: "¿Tengo algún disco…?", "¿Tengo a Mozart?". */
    private val AFTER_TENGO = setOf("algo", "algun", "alguna", "algunos", "algunas", "a", "de")

    /**
     * A cue word as a whole word (accents and case folded), or one of the [PHRASES]; for Spanish, of [CUES_ES] or
     * [PHRASES_ES], or [tengo]. [languages] are the question's ([ResearchLanguage.of]).
     */
    fun asked(question: String, languages: List<ResearchLanguage> = ResearchLanguage.of(question)): Boolean {
        val folded = MARKS.replace(Normalizer.normalize(question, Normalizer.Form.NFKD), "").lowercase(Locale.ROOT)
        val words = WORD.findAll(folded).map { it.value }.toList()
        if (ResearchLanguage.EN in languages && (words.any { it in CUES } || PHRASES.any { p -> words.windowed(p.size).any { it == p } })) {
            return true
        }
        if (ResearchLanguage.ES !in languages) return false
        // "Ancho de banda" and "banda ancha" are bandwidth and broadband, not a band.
        val spanish = WORD.findAll(BANDWIDTH_ES.replace(folded, " ")).map { it.value }.toList()
        return spanish.any { it in CUES_ES && SpanishWords.counts(it, languages) } ||
            PHRASES_ES.any { p -> words.windowed(p.size).any { it == p } && SpanishWords.phraseCounts(p.joinToString(" "), languages) } ||
            tengo(question, words)
    }

    /**
     * "Tengo" first, then a word of [AFTER_TENGO] or a name ("¿Tengo Holst?"): not "tengo un lunar", which is about
     * the asker.
     */
    private fun tengo(question: String, words: List<String>): Boolean {
        if (words.firstOrNull() != "tengo" || words.size < 2) return false
        val second = WORD.findAll(question).drop(1).firstOrNull()?.value ?: return false
        return words[1] in AFTER_TENGO || second.first().isUpperCase()
    }
}
