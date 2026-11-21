package app.granger.ebook

import app.granger.UiText
import app.granger.core.catalog.Languages
import app.granger.core.library.Library
import app.granger.core.library.Pack
import app.granger.core.library.PackStatus
import app.granger.core.zim.ZimHit
import app.granger.screens.ioDispatcher
import app.granger.screens.languageNameIn
import app.granger.sources.KiwixList
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/** What "Define" does for the selected word. */
sealed interface DictionaryChoice {
    /** Look the word up in this installed Wiktionary pack. */
    data class Wiktionary(val packId: String) : DictionaryChoice

    /**
     * Hand the word to the phone's own dictionary ([SystemDictionary]): on Android an app that handles
     * ACTION_PROCESS_TEXT, on the iPhone the system dictionary (Plan I5 call 8).
     */
    data object ProcessText : DictionaryChoice

    /** Nothing can define a word; the menu hides Define. */
    data object None : DictionaryChoice
}

/**
 * The phone's own dictionary, for when no Wiktionary is installed (Plan I5 call 8): Android hands the word to an app
 * that handles ACTION_PROCESS_TEXT (found through the PackageManager); the iPhone shows `UIReferenceLibraryViewController`.
 */
interface SystemDictionary {
    /** Whether something on the phone can define a word. May ask the system: call off the main thread. */
    fun available(): Boolean

    /** Shows [text]'s definition; false when nothing could take it. */
    fun define(text: String): Boolean
}

/**
 * The Wiktionary a word in a book is looked up in (Plan L3): [packId], whose own language is [wiktionaryLanguage], at
 * [anchor] (the book language's section, "Spanish" in English Wiktionary, "Español" in Spanish Wiktionary). [fallback]
 * when it's neither the main language's nor the book's: the reader says which Wiktionary it was.
 */
data class Definer(val packId: String, val wiktionaryLanguage: String, val anchor: String, val fallback: Boolean)

/** What Define found: the entry is open ([note] to show with it, or null), or there is none. */
sealed interface LookUp {
    data class Found(val note: UiText? = null) : LookUp

    data object NotFound : LookUp
}

/**
 * A word's Wiktionary entry, to open over the book: the page [path] titled [title] in [packId], at [anchor] (the book
 * language's section; null when no Wiktionary was chosen for it). [note] says which Wiktionary it was when it's a
 * fallback ("Defined in French Wiktionary").
 */
data class DictionaryEntry(val packId: String, val path: String, val title: String, val anchor: String?, val note: UiText?)

/** Define in the e-book reader: which dictionary, and the Wiktionary lookup. Shared since Plan I5 Task 2. */
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
    fun anchor(bookLanguage: String, wiktionaryLanguage: String): String =
        languageNameIn(bookLanguage, wiktionaryLanguage).replace(' ', '_')

    /** An OK `wiktionary_*` pack (the newest snapshot), else the phone's own dictionary ([SystemDictionary]), else nothing. */
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
        selection.trim().split(WHITESPACE).firstOrNull().orEmpty()
            .trim { !it.isLetterOrDigit() }
            .lowercase()

    /** A hit titled exactly [word] (ignoring case), else the first hit, else null. */
    fun pick(hits: List<ZimHit>, word: String): ZimHit? =
        hits.firstOrNull { it.title.equals(word, ignoreCase = true) } ?: hits.firstOrNull()

    /**
     * [word]'s entry for a book in [bookLanguage] (Plan L3): in the Wiktionary [choose] picks for it, or [packId] when it
     * picks none; null when the pack has no such word. The pack's titles are read on [io].
     */
    suspend fun entry(
        library: Library, packId: String, word: String, bookLanguage: String?, knowledgeLanguages: List<String>,
        io: CoroutineDispatcher = ioDispatcher,
    ): DictionaryEntry? {
        val definer = choose(library.packs.first(), bookLanguage, knowledgeLanguages)
        val id = definer?.packId ?: packId
        val hit = withContext(io) { library.archive(id)?.suggest(word, 5) }?.let { pick(it, word) } ?: return null
        val note = definer?.takeIf { it.fallback }?.let { UiText.Res("ebook_defined_in", KiwixList.languageName(it.wiktionaryLanguage)) }
        return DictionaryEntry(id, hit.path, hit.title, definer?.anchor, note)
    }

    private val WHITESPACE = Regex("\\s+")
}
