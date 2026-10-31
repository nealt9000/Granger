package app.granger.ebook

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import app.granger.core.library.Pack
import app.granger.core.library.PackStatus
import app.granger.core.catalog.Languages
import app.granger.core.zim.ZimHit
import java.util.Locale

/** What "Define" does for the selected word. */
sealed interface DictionaryChoice {
    /** Look the word up in this installed Wiktionary pack. */
    data class Wiktionary(val packId: String) : DictionaryChoice

    /** Hand the word to an app on the phone that handles ACTION_PROCESS_TEXT. */
    data object ProcessText : DictionaryChoice

    /** Nothing can define a word; the menu hides Define. */
    data object None : DictionaryChoice
}

/**
 * The Wiktionary a word in a book is looked up in (Plan L3): [packId], whose own language is [wiktionaryLanguage], at
 * [anchor] (the book language's section, "Spanish" in English Wiktionary, "Español" in Spanish Wiktionary). [fallback]
 * when it's neither the main language's nor the book's: the reader says which Wiktionary it was.
 */
data class Definer(val packId: String, val wiktionaryLanguage: String, val anchor: String, val fallback: Boolean)

/** What Define found: the entry is open ([note] to show with it, or null), or there is none. */
sealed interface LookUp {
    data class Found(val note: app.granger.UiText? = null) : LookUp

    data object NotFound : LookUp
}

object Dictionary {
    /**
     * Which installed Wiktionary defines a word in a book in [bookLanguage] (Plan L3), for a library in
     * [knowledgeLanguages] (main first), in this order:
     * 1. the main language's Wiktionary, when the book is in another language (the learner: English Wiktionary for a
     *    Spanish book);
     * 2. the book's language's Wiktionary;
     * 3. any Wiktionary (the main language's first, else the newest), with [Definer.fallback].
     * A book that doesn't say its language is taken to be in the main language. Null with no Wiktionary.
     */
    fun choose(packs: List<Pack>, bookLanguage: String?, knowledgeLanguages: List<String>): Definer? {
        val byLanguage = packs.filter { it.status == PackStatus.OK && it.name.startsWith("wiktionary_") }
            .groupBy { wiktionaryLanguage(it) }.mapValues { (_, list) -> list.maxBy { it.date } }
        if (byLanguage.isEmpty()) return null
        val main = knowledgeLanguages.firstOrNull()?.let(Languages::normalise)?.ifEmpty { null } ?: "en"
        val book = bookLanguage?.let { Languages.codes(it).firstOrNull() } ?: main
        fun definer(lang: String, fallback: Boolean) = byLanguage[lang]?.let { Definer(it.id, lang, anchor(book, lang), fallback) }
        if (book != main) definer(main, false)?.let { return it }
        definer(book, false)?.let { return it }
        val any = byLanguage[main]?.let { main } ?: byLanguage.maxBy { it.value.date }.key
        return definer(any, fallback = true)
    }

    /** A Wiktionary pack's own language: its ZIM name's (`wiktionary_es_all` → es), else its metadata's. */
    fun wiktionaryLanguage(pack: Pack): String =
        pack.name.split('_').getOrNull(1)?.takeIf { it.length in 2..3 }?.let(Languages::normalise)
            ?: Languages.codes(pack.language).firstOrNull() ?: "en"

    /**
     * The section of a word's entry for [bookLanguage] in a Wiktionary in [wiktionaryLanguage]: the language's name in
     * that Wiktionary's language, as its headings say it ("Spanish" in English Wiktionary, "Español" in Spanish).
     */
    fun anchor(bookLanguage: String, wiktionaryLanguage: String): String {
        val locale = Locale.forLanguageTag(wiktionaryLanguage)
        return Locale.forLanguageTag(bookLanguage).getDisplayLanguage(locale).replaceFirstChar { it.titlecase(locale) }.replace(' ', '_')
    }

    /** An OK `wiktionary_*` pack (the newest snapshot), else an app that handles process-text, else nothing. */
    fun choose(packs: List<Pack>, hasProcessTextHandler: Boolean): DictionaryChoice {
        val wiktionary = packs
            .filter { it.status == PackStatus.OK && it.name.startsWith("wiktionary_") }
            .maxByOrNull { it.date }
        return when {
            wiktionary != null -> DictionaryChoice.Wiktionary(wiktionary.id)
            hasProcessTextHandler -> DictionaryChoice.ProcessText
            else -> DictionaryChoice.None
        }
    }

    /** The first word of [selection], lower-cased and stripped of surrounding punctuation; empty if there is none. */
    fun normalise(selection: String): String =
        selection.trim().split(Regex("\\s+")).firstOrNull().orEmpty()
            .trim { !it.isLetterOrDigit() }
            .lowercase()

    /** A hit titled exactly [word] (ignoring case), else the first hit, else null. */
    fun pick(hits: List<ZimHit>, word: String): ZimHit? =
        hits.firstOrNull { it.title.equals(word, ignoreCase = true) } ?: hits.firstOrNull()

    private fun processTextIntent(): Intent = Intent(Intent.ACTION_PROCESS_TEXT).setType("text/plain")

    /** Whether some app handles process-text. Queries the package manager: call off the main thread. */
    fun hasProcessTextHandler(context: Context): Boolean =
        context.packageManager.queryIntentActivities(processTextIntent(), PackageManager.MATCH_DEFAULT_ONLY).isNotEmpty()

    /** Hands [text] to the process-text app, read-only. Returns false when none can take it. */
    fun startProcessText(context: Context, text: String): Boolean = try {
        context.startActivity(
            processTextIntent()
                .putExtra(Intent.EXTRA_PROCESS_TEXT, text)
                .putExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        true
    } catch (e: android.content.ActivityNotFoundException) {
        false
    }
}
