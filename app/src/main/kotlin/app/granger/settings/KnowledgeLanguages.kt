package app.granger.settings

import android.content.Context
import android.content.SharedPreferences
import app.granger.core.catalog.ENGLISH
import app.granger.core.catalog.Languages
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import java.util.Locale

/**
 * The languages of the user's library (Plan L3 Task 6): an ordered list of primary codes, the first the main one. Pick
 * a start offers the main language's starts, Readiness measures each domain over all of them, and Get more lists their
 * packs.
 *
 * Nothing is stored until the user chooses: until then the list is the app language when the catalog has tiers for it,
 * else English ([resolve]). A stored list keeps only the languages the catalog has tiers for, once it has loaded.
 */
class KnowledgeLanguages(
    private val prefs: SharedPreferences,
    /** The app's own language code ("en", "es"), as [AppLanguages.code] gives it. */
    appLanguage: Flow<String>,
    /** The codes the catalog has tiers for ([app.granger.core.catalog.Catalog.languages]); empty before it loads. */
    supported: Flow<List<String>>,
) {
    private val stored = MutableStateFlow(read())

    /** The knowledge languages, main first; never empty. */
    val languages: Flow<List<String>> =
        combine(stored, appLanguage, supported) { s, app, langs -> resolve(s, app, langs) }.distinctUntilChanged()

    /** The codes the catalog has tiers for, the choices Settings offers. */
    val offered: Flow<List<String>> = supported

    /** Stores [codes] (main first); an empty list goes back to the default. */
    fun set(codes: List<String>) {
        val clean = codes.map(Languages::normalise).filter { it.isNotEmpty() }.distinct()
        if (clean.isEmpty()) prefs.edit().remove(KEY).apply() else prefs.edit().putString(KEY, clean.joinToString(",")).apply()
        stored.value = clean.ifEmpty { null }
    }

    private fun read(): List<String>? = prefs.getString(KEY, null)?.split(',')?.filter { it.isNotBlank() }?.ifEmpty { null }

    companion object {
        const val PREFS = "knowledge"
        private const val KEY = "languages"

        fun create(context: Context, appLanguage: Flow<String>, supported: Flow<List<String>>) =
            KnowledgeLanguages(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE), appLanguage, supported)

        /**
         * The knowledge languages from the [stored] choice (null when there's none), the app language and the codes the
         * catalog [supported]s. Without a choice: the app language when the catalog has tiers for it, else English. A
         * choice keeps its supported languages in order; with none left it falls back the same way. Before the catalog
         * has loaded ([supported] empty) a choice stands as it is.
         */
        fun resolve(stored: List<String>?, appLanguage: String, supported: List<String>): List<String> {
            val app = Languages.normalise(appLanguage)
            val default = listOf(if (app in supported) app else ENGLISH)
            val chosen = stored.orEmpty().map(Languages::normalise).filter { it.isNotEmpty() }.distinct()
            if (chosen.isEmpty()) return default
            if (supported.isEmpty()) return chosen
            return chosen.filter { it in supported }.ifEmpty { default }
        }

        /** The supported languages the phone lists, in the phone's order: [es, en] for a phone in Spanish then English. */
        fun phoneLanguages(phone: List<Locale>, supported: List<String>): List<String> =
            phone.map { Languages.normalise(it.language) }.filter { it in supported }.distinct()

        /**
         * The second language onboarding offers (Neal's call 4): when the phone lists more than one language the catalog
         * supports, the first of them that isn't the main knowledge language. Null otherwise.
         */
        fun secondOffer(knowledge: List<String>, phone: List<String>): String? =
            if (phone.size < 2) null else phone.firstOrNull { it != knowledge.firstOrNull() }

        /** [current] with [code] turned on (added last) or off; the last one on stays on. */
        fun toggle(current: List<String>, code: String): List<String> = when {
            code !in current -> current + code
            current.size > 1 -> current - code
            else -> current
        }

        /** [current] with [code] first: the main language. */
        fun makeMain(current: List<String>, code: String): List<String> = listOf(code) + (current - code)
    }
}
