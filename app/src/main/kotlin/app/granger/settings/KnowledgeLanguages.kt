package app.granger.settings

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.Flow
import java.util.Locale

/** The knowledge languages in SharedPreferences `"knowledge"`, a comma-joined list under `languages`. */
class SharedKnowledgePrefs(private val prefs: SharedPreferences) : KnowledgePrefs {
    override var languages: List<String>?
        get() = prefs.getString(KEY, null)?.split(',')?.filter { it.isNotBlank() }?.ifEmpty { null }
        set(value) {
            if (value.isNullOrEmpty()) prefs.edit().remove(KEY).apply() else prefs.edit().putString(KEY, value.joinToString(",")).apply()
        }

    private companion object {
        const val KEY = "languages"
    }
}

/**
 * The languages of the user's library (Plan L3 Task 6) on Android: the shared [KnowledgeLanguagesModel] (Plan I7 Task 1)
 * over SharedPreferences, with the rules where Android's code has always called them.
 */
class KnowledgeLanguages(
    prefs: SharedPreferences,
    /** The app's own language code ("en", "es"), as [AppLanguages.code] gives it. */
    appLanguage: Flow<String>,
    /** The codes the catalog has tiers for ([app.granger.core.catalog.Catalog.languages]); empty before it loads. */
    supported: Flow<List<String>>,
) : KnowledgeLanguagesModel(SharedKnowledgePrefs(prefs), appLanguage, supported) {

    companion object {
        const val PREFS = "knowledge"

        fun create(context: Context, appLanguage: Flow<String>, supported: Flow<List<String>>) =
            KnowledgeLanguages(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE), appLanguage, supported)

        /** [LanguageOffers.resolve]. */
        fun resolve(stored: List<String>?, appLanguage: String, supported: List<String>): List<String> =
            LanguageOffers.resolve(stored, appLanguage, supported)

        /** The supported languages the phone lists, in the phone's order: [es, en] for a phone in Spanish then English. */
        fun phoneLanguages(phone: List<Locale>, supported: List<String>): List<String> =
            LanguageOffers.phoneLanguages(phone.map { it.language }, supported)

        /** [LanguageOffers.secondOffer]. */
        fun secondOffer(knowledge: List<String>, phone: List<String>): String? = LanguageOffers.secondOffer(knowledge, phone)

        /** [LanguageOffers.toggle]. */
        fun toggle(current: List<String>, code: String): List<String> = LanguageOffers.toggle(current, code)

        /** [LanguageOffers.makeMain]. */
        fun makeMain(current: List<String>, code: String): List<String> = LanguageOffers.makeMain(current, code)
    }
}
