package app.granger.ebook

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import app.granger.core.library.Pack
import app.granger.core.library.PackStatus
import app.granger.core.zim.ZimHit

/** What "Define" does for the selected word. */
sealed interface DictionaryChoice {
    /** Look the word up in this installed Wiktionary pack. */
    data class Wiktionary(val packId: String) : DictionaryChoice

    /** Hand the word to an app on the phone that handles ACTION_PROCESS_TEXT. */
    data object ProcessText : DictionaryChoice

    /** Nothing can define a word; the menu hides Define. */
    data object None : DictionaryChoice
}

object Dictionary {
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
