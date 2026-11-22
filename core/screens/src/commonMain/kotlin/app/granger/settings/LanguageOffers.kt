package app.granger.settings

import app.granger.core.catalog.ENGLISH
import app.granger.core.catalog.Languages

/**
 * The knowledge-language rules onboarding and Settings use on both phones (Plan I4b Task 8; [resolve], [toggle] and
 * [makeMain] since Plan I7 Task 1); Android's `KnowledgeLanguages` delegates to them.
 */
object LanguageOffers {
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

    /** [current] with [code] turned on (added last) or off; the last one on stays on. */
    fun toggle(current: List<String>, code: String): List<String> = when {
        code !in current -> current + code
        current.size > 1 -> current - code
        else -> current
    }

    /** [current] with [code] first: the main language. */
    fun makeMain(current: List<String>, code: String): List<String> = listOf(code) + (current - code)

    /** The supported languages the phone lists ([phone]: its language codes, in its order): [es, en] for Spanish then English. */
    fun phoneLanguages(phone: List<String>, supported: List<String>): List<String> =
        phone.map(Languages::normalise).filter { it in supported }.distinct()

    /**
     * The second language onboarding offers (Neal's call 4): when the phone lists more than one language the catalog
     * supports, the first of them that isn't the main knowledge language. Null otherwise.
     */
    fun secondOffer(knowledge: List<String>, phone: List<String>): String? =
        if (phone.size < 2) null else phone.firstOrNull { it != knowledge.firstOrNull() }
}
