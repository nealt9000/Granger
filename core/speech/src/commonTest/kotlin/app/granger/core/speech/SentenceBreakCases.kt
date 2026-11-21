package app.granger.core.speech

/**
 * The same paragraphs on both phones (Plan I5, call 4): ICU's raw sentence spans and the sentences the voice reads,
 * as Android's `BreakIterator` gives them (the reference; recorded under Robolectric). `SentenceBreaksIcuTest` checks
 * them on the JVM host with ICU, `SentenceBreaksIosTest` on the Simulator with `CFStringTokenizer`.
 */
object SentenceBreakCases {
    /**
     * A paragraph (already folded: one space between words), its language, ICU's span starts and the voice's sentences
     * on Android; [iosStarts] and [iosSentences] are where the iPhone's `CFStringTokenizer` differs (null: the same).
     */
    data class Case(
        val text: String,
        val languageTag: String,
        val starts: List<Int>,
        val sentences: List<String>,
        val iosStarts: List<Int>? = null,
        val iosSentences: List<String>? = null,
    )

    val paragraphs: List<Pair<String, String>> = listOf(
        "en-US" to "Mr. Smith went to Washington. He arrived at 3.5 p.m. on Tuesday.",
        "en-US" to "Dr. Watson said, \"It's elementary.\" Then he left... Nobody followed.",
        "en-US" to "The value is 3.5 percent. It rose by 0.25 points! Did it? Yes.",
        "en-US" to "J. R. R. Tolkien wrote it in 1937. The U.S. edition came later.",
        "en-US" to "\"Wait,\" she said. \"Who's there?\" No answer came.",
        "en-US" to "He paused... then went on. (This is an aside.) The end.",
        "en-US" to "Mrs. Jones met St. John at 10 a.m. They talked for 2.5 hours. Then they parted.",
        "en-US" to "It cost \$4.99, i.e. almost five dollars. That's all…",
        "es-419" to "El Sr. García llegó a las 3.5 horas. ¿Quién lo sabe? ¡Nadie!",
        "es-419" to "La Dra. López dijo: «Es elemental». Luego se fue... Nadie la siguió.",
        "es-419" to "Vale 3,5 euros. Subió un 0,25 %. ¿Y qué?",
        "es-419" to "—¿Vienes? —preguntó Ana. —Sí —dijo él.",
    )

    val cases: List<Case> = listOf(
        // Recorded from Android's ICU under Robolectric (SDK 35), 2026-11-21. "La Dra." splitting is Android's behaviour
        // today (the abbreviation list is English); it is kept as the reference, not fixed here.
        // The iPhone (Simulator, iOS 26) differs where noted: CFStringTokenizer's ICU uses the standard abbreviation
        // suppressions, so it never breaks after "Mr.", "Dr.", "Mrs.", "St.", "Sr." or "Dra.", nor after "a.m." before
        // a capital. The first is invisible once Sentences joins titles; the voice hears two differences: "10 a.m. They
        // talked…" stays one sentence, and "La Dra. López…" isn't split (better than Android).
        Case("Mr. Smith went to Washington. He arrived at 3.5 p.m. on Tuesday.", "en-US", listOf(0, 4, 30), listOf("Mr. Smith went to Washington.", "He arrived at 3.5 p.m. on Tuesday."),
            iosStarts = listOf(0, 30),
        ),
        Case("Dr. Watson said, \"It's elementary.\" Then he left... Nobody followed.", "en-US", listOf(0, 4, 36, 52), listOf("Dr. Watson said, \"It's elementary.\"", "Then he left...", "Nobody followed."),
            iosStarts = listOf(0, 36, 52),
        ),
        Case("The value is 3.5 percent. It rose by 0.25 points! Did it? Yes.", "en-US", listOf(0, 26, 50, 58), listOf("The value is 3.5 percent.", "It rose by 0.25 points!", "Did it?", "Yes.")),
        Case("J. R. R. Tolkien wrote it in 1937. The U.S. edition came later.", "en-US", listOf(0, 3, 6, 9, 35), listOf("J. R. R. Tolkien wrote it in 1937.", "The U.S. edition came later.")),
        Case("\"Wait,\" she said. \"Who's there?\" No answer came.", "en-US", listOf(0, 18, 33), listOf("\"Wait,\" she said.", "\"Who's there?\"", "No answer came.")),
        Case("He paused... then went on. (This is an aside.) The end.", "en-US", listOf(0, 27, 47), listOf("He paused... then went on.", "(This is an aside.)", "The end.")),
        Case("Mrs. Jones met St. John at 10 a.m. They talked for 2.5 hours. Then they parted.", "en-US", listOf(0, 5, 19, 35, 62), listOf("Mrs. Jones met St. John at 10 a.m.", "They talked for 2.5 hours.", "Then they parted."),
            iosStarts = listOf(0, 19, 62),
            iosSentences = listOf("Mrs. Jones met St. John at 10 a.m. They talked for 2.5 hours.", "Then they parted."),
        ),
        Case("It cost \$4.99, i.e. almost five dollars. That's all…", "en-US", listOf(0, 41), listOf("It cost \$4.99, i.e. almost five dollars.", "That's all…")),
        Case("El Sr. García llegó a las 3.5 horas. ¿Quién lo sabe? ¡Nadie!", "es-419", listOf(0, 7, 37, 53), listOf("El Sr. García llegó a las 3.5 horas.", "¿Quién lo sabe?", "¡Nadie!"),
            iosStarts = listOf(0, 37, 53),
        ),
        Case("La Dra. López dijo: «Es elemental». Luego se fue... Nadie la siguió.", "es-419", listOf(0, 8, 36, 52), listOf("La Dra.", "López dijo: «Es elemental».", "Luego se fue...", "Nadie la siguió."),
            iosStarts = listOf(0, 36, 52),
            iosSentences = listOf("La Dra. López dijo: «Es elemental».", "Luego se fue...", "Nadie la siguió."),
        ),
        Case("Vale 3,5 euros. Subió un 0,25 %. ¿Y qué?", "es-419", listOf(0, 16, 33), listOf("Vale 3,5 euros.", "Subió un 0,25 %.", "¿Y qué?")),
        Case("—¿Vienes? —preguntó Ana. —Sí —dijo él.", "es-419", listOf(0), listOf("—¿Vienes? —preguntó Ana. —Sí —dijo él.")),
    )

    /**
     * What differs from what this phone should give (Android's breaks, or on the iPhone [ios], the recorded iPhone
     * ones), one line per difference; empty when all agree.
     */
    fun differences(ios: Boolean): List<String> = cases.flatMap { c ->
        val out = mutableListOf<String>()
        val wantStarts = if (ios) c.iosStarts ?: c.starts else c.starts
        val wantSentences = if (ios) c.iosSentences ?: c.sentences else c.sentences
        val folded = Sentences.fold(c.text)
        val starts = Sentences.breaks(folded, c.languageTag).map { it.first }
        if (starts != wantStarts) out += "spans of \"${c.text}\" (${c.languageTag}): expected starts $wantStarts, got $starts"
        val sentences = Sentences.split(c.text, c.languageTag)
        if (sentences != wantSentences) out += "sentences of \"${c.text}\" (${c.languageTag}): expected $wantSentences, got $sentences"
        out
    }

    /** The cases as this phone sees them, as Kotlin source, for recording the reference. */
    fun record(): String = paragraphs.joinToString("\n") { (tag, text) ->
        val folded = Sentences.fold(text)
        val starts = Sentences.breaks(folded, tag).map { it.first }
        val sentences = Sentences.split(text, tag)
        "        Case(${quote(text)}, \"$tag\", listOf(${starts.joinToString()}), listOf(${sentences.joinToString { quote(it) }})),"
    }

    private fun quote(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("$", "\\$") + "\""
}
