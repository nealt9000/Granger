package app.granger.settings

import app.granger.core.catalog.Languages

/**
 * The knowledge-language rules onboarding uses on both phones (Plan I4b Task 8); Android's `KnowledgeLanguages`
 * delegates to them.
 */
object LanguageOffers {
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
