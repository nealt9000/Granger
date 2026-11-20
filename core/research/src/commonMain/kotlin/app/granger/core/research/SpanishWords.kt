package app.granger.core.research

/**
 * Spanish words that are also English words or name parts ("son", "sea", "Los Angeles", "La Paz", "Con Edison", "dice",
 * "Lake Como"). In every Spanish list (stopwords, openers, cues, particles, event words), such an entry counts only
 * when Spanish leads the question ([ResearchLanguage.of] puts it first). A question that may be either language but
 * leads with English keeps what English gave it; a Spanish-led one gets the whole Spanish list.
 */
internal object SpanishWords {
    /** As typed, lower case: accented forms ("él", "más") are Spanish only, so they aren't here. */
    val ENGLISH_TOO = setOf(
        "a", "al", "alto", "acto", "ante", "bajo", "banda", "bien", "como", "con", "contra", "de", "del", "dice", "dime",
        "disco", "e", "el", "ella", "en", "entre", "era", "es", "esta", "este", "fin", "ha", "han", "has", "hasta", "hay",
        "he", "hecho", "la", "las", "le", "les", "lo", "los", "mas", "mayor", "me", "mi", "nada", "no", "o", "opera",
        "para", "pues", "sea", "sin", "sobre", "solo", "son", "su", "tan", "todo", "tu", "u", "un", "una", "uno", "verso",
        "viva", "vivo", "y", "ya", "yo",
    )

    fun leads(languages: List<ResearchLanguage>): Boolean = languages.firstOrNull() == ResearchLanguage.ES

    /** Whether a Spanish list's entry [word] (lower case, as typed or as listed) counts for [languages]. */
    fun counts(word: String, languages: List<ResearchLanguage>): Boolean =
        ResearchLanguage.ES in languages && (leads(languages) || word !in ENGLISH_TOO)

    /** A Spanish phrase counts if Spanish leads or any of its words isn't English too ("mayor que", "hay algo de"). */
    fun phraseCounts(phrase: String, languages: List<ResearchLanguage>): Boolean =
        ResearchLanguage.ES in languages && (leads(languages) || phrase.split(' ').any { it !in ENGLISH_TOO })
}
