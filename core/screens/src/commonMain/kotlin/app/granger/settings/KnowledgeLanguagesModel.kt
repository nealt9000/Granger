package app.granger.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.granger.UiText
import app.granger.core.catalog.Languages
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.stateIn

/** Where the knowledge languages are kept: Android's SharedPreferences `"knowledge"`, the iPhone's UserDefaults. */
interface KnowledgePrefs {
    /** The stored choice, main first; null when the user hasn't chosen. */
    var languages: List<String>?
}

/**
 * The languages of the user's library (Plan L3 Task 6, shared since Plan I7 Task 1): an ordered list of primary codes,
 * the first the main one. Pick a start offers the main language's starts, Outfitting measures each domain over all of
 * them, and Get more lists their packs. Android's `KnowledgeLanguages` is a thin subclass over SharedPreferences.
 *
 * Nothing is stored until the user chooses: until then the list is the app language when the catalog has tiers for it,
 * else English ([LanguageOffers.resolve]). A stored list keeps only the languages the catalog has tiers for, once it
 * has loaded.
 */
open class KnowledgeLanguagesModel(
    private val prefs: KnowledgePrefs,
    /** The app's own language code ("en", "es"). */
    appLanguage: Flow<String>,
    /** The codes the catalog has tiers for (`Catalog.languages`); empty before it loads. */
    supported: Flow<List<String>>,
) {
    private val stored = MutableStateFlow(prefs.languages?.filter { it.isNotBlank() }?.ifEmpty { null })

    /** The knowledge languages, main first; never empty. */
    val languages: Flow<List<String>> =
        combine(stored, appLanguage, supported) { s, app, langs -> LanguageOffers.resolve(s, app, langs) }.distinctUntilChanged()

    /** The codes the catalog has tiers for, the choices Settings offers. */
    val offered: Flow<List<String>> = supported

    /** Stores [codes] (main first); an empty list goes back to the default. */
    fun set(codes: List<String>) {
        val clean = codes.map(Languages::normalise).filter { it.isNotEmpty() }.distinct()
        prefs.languages = clean.ifEmpty { null }
        stored.value = clean.ifEmpty { null }
    }
}

/** One language in Settings' "Your library's languages", named by the phone from [code]. */
data class LibraryLanguageRow(
    val code: String,
    /** Ticked: one of the library's languages. */
    val on: Boolean,
    /** The last one ticked can't be unticked. */
    val locked: Boolean,
    /** "Main language" under the main one, when more than one is ticked; null otherwise. */
    val note: UiText?,
    /** Ticked but not main: "Make main" is offered. */
    val canMakeMain: Boolean,
)

/**
 * The Language screen's knowledge section (Plan L3, shared since Plan I7 Task 1): the knowledge languages [chosen]
 * (main first) of those the catalog [offered]s. The app language is each phone's own (Android's `AppLanguage`,
 * iOS's per-app language in the system Settings), so it isn't here.
 */
data class LanguageSettingsUi(val chosen: List<String>, val offered: List<String>) {
    /** The section shows when the catalog has tiers in more than one language. */
    val showLibrary: Boolean get() = offered.size > 1

    /** Each offered language, ticked when it's one of the library's; the first ticked is the main one. */
    val rows: List<LibraryLanguageRow> get() = offered.map { code ->
        val on = code in chosen
        val main = chosen.firstOrNull() == code
        LibraryLanguageRow(
            code = code,
            on = on,
            locked = on && chosen.size == 1,
            note = if (main && chosen.size > 1) UiText.Res("settings_language_main") else null,
            canMakeMain = on && !main,
        )
    }

    val heading: UiText get() = UiText.Res("settings_language_library")
    val blurb: UiText get() = UiText.Res("settings_language_library_blurb")
    val makeMainLabel: UiText get() = UiText.Res("settings_language_make_main")
}

/** Settings → Language's knowledge section as a model (Plan I7 Task 1), over [knowledge]. */
class LanguageSettingsModel(private val knowledge: KnowledgeLanguagesModel) : ViewModel() {
    /** Empty until the knowledge languages and the catalog's have been read (the section hides). */
    val ui: StateFlow<LanguageSettingsUi> = combine(knowledge.languages, knowledge.offered) { chosen, offered ->
        LanguageSettingsUi(chosen, offered)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LanguageSettingsUi(emptyList(), emptyList()))

    /** Turns [code] on (added last) or off; the last one on stays on. */
    fun toggle(code: String) = knowledge.set(LanguageOffers.toggle(ui.value.chosen, code))

    /** Makes [code] the main language. */
    fun makeMain(code: String) = knowledge.set(LanguageOffers.makeMain(ui.value.chosen, code))

    /** Stores [codes] as they are (main first). */
    fun set(codes: List<String>) = knowledge.set(codes)
}
