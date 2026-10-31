package app.granger.settings

import android.app.LocaleManager
import android.content.Context
import android.content.SharedPreferences
import android.content.res.Configuration
import android.content.res.Resources
import android.os.Build
import android.os.LocaleList
import androidx.annotation.RequiresApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.Locale

/** The app's own language (Plan L2 Task 3): the phone's, or English or Spanish whatever the phone is set to. */
enum class AppLanguage(val language: String?) {
    SYSTEM(null), ENGLISH("en"), SPANISH("es");

    companion object {
        /** The choice a stored or platform tag stands for ("es-MX" is Spanish); anything else follows the phone. */
        fun of(tag: String?): AppLanguage {
            val language = tag?.takeIf { it.isNotBlank() }?.let { Locale.forLanguageTag(it).language } ?: return SYSTEM
            return entries.firstOrNull { it.language == language } ?: SYSTEM
        }

        /**
         * The locale a choice runs in, or null to follow the phone. The phone's own locale of that language when it has
         * one, so a phone in es-MX or en-GB keeps its region's dates and numbers; else the plain language.
         */
        fun localeFor(choice: AppLanguage, phone: LocaleList): Locale? {
            val language = choice.language ?: return null
            return (0 until phone.size()).map(phone::get).firstOrNull { it.language == language } ?: Locale.forLanguageTag(language)
        }
    }
}

/** Android 13+'s per-app language (LocaleManager), behind an interface so tests can see the calls. */
interface PlatformLocales {
    /** The app's own locales, empty when it follows the phone. */
    var appLocales: LocaleList

    /** The phone's languages, whatever the app is set to. */
    val phoneLocales: LocaleList
}

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
class LocaleManagerLocales(context: Context) : PlatformLocales {
    private val manager = context.getSystemService(LocaleManager::class.java)

    override var appLocales: LocaleList
        get() = manager.applicationLocales
        set(value) { manager.applicationLocales = value }

    override val phoneLocales: LocaleList get() = manager.systemLocales
}

/**
 * Settings → Appearance → Language, and how the choice reaches the app.
 *
 * - **API 33+** ([platform] given): Android keeps the choice. Setting it recreates the activity, and services, the
 *   widget and notifications follow on their own. The phone's Settings → Apps → Granger → Language changes the same
 *   setting, so [refresh] reads it back.
 * - **API 29–32** ([platform] null): the choice is stored here. [wrap] puts it on `MainActivity`'s context,
 *   [localizedResources] on the application's (downloads, the widget, view models), and the playback service follows
 *   the application. A change calls [onLegacyChange] (the application's resources) and the activity recreates itself.
 *
 * AppCompat's `setApplicationLocales` was the other choice: below 33 it reaches only `AppCompatActivity`, and
 * `MainActivity` is a `FragmentActivity` (Readium's navigator), so it would need the activity converted.
 */
class AppLanguages(
    private val prefs: SharedPreferences,
    private val platform: PlatformLocales?,
    private val legacyPhoneLocales: () -> LocaleList = { Resources.getSystem().configuration.locales },
    private val onLegacyChange: () -> Unit = {},
) {
    private val state = MutableStateFlow(read())

    /** [AppLanguage.SYSTEM] by default. */
    val language: StateFlow<AppLanguage> = state

    fun set(choice: AppLanguage) {
        if (choice.language == null) prefs.edit().remove(KEY).apply() else prefs.edit().putString(KEY, choice.name).apply()
        if (platform != null) {
            val locale = AppLanguage.localeFor(choice, platform.phoneLocales)
            platform.appLocales = if (locale == null) LocaleList.getEmptyLocaleList() else LocaleList(locale)
        } else {
            onLegacyChange()
        }
        state.value = choice
    }

    /** Reads the choice again: on 33+ the phone's own Language screen may have changed it. */
    fun refresh() {
        state.value = read()
    }

    /** The phone's first language, for "Follow the phone (Español)". */
    fun phoneLocale(): Locale = (platform?.phoneLocales ?: legacyPhoneLocales()).let { if (it.isEmpty) Locale.getDefault() else it[0] }

    private fun read(): AppLanguage =
        if (platform != null) {
            AppLanguage.of(platform.appLocales.takeUnless { it.isEmpty }?.get(0)?.toLanguageTag())
        } else {
            stored(prefs)
        }

    companion object {
        const val PREFS = "app_language"
        private const val KEY = "language"

        fun create(context: Context, onLegacyChange: () -> Unit = {}): AppLanguages = AppLanguages(
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) LocaleManagerLocales(context) else null,
            onLegacyChange = onLegacyChange,
        )

        private fun stored(prefs: SharedPreferences): AppLanguage =
            prefs.getString(KEY, null)?.let { name -> AppLanguage.entries.firstOrNull { it.name == name } } ?: AppLanguage.SYSTEM

        /**
         * Below API 33, the locale the stored choice runs in, or null to follow the phone. Also sets the process's
         * default locale, which date and number formats read, back to the phone's when following it.
         */
        fun legacyLocale(base: Context, sdk: Int = Build.VERSION.SDK_INT): Locale? {
            if (sdk >= Build.VERSION_CODES.TIRAMISU) return null
            val phone = Resources.getSystem().configuration.locales
            val locale = AppLanguage.localeFor(stored(base.getSharedPreferences(PREFS, Context.MODE_PRIVATE)), phone)
            if (locale != null) Locale.setDefault(locale) else if (!phone.isEmpty) LocaleList.setDefault(phone)
            return locale
        }

        /** [base] in the stored language, for an activity's `attachBaseContext`; [base] itself on API 33+ or following the phone. */
        fun wrap(base: Context, sdk: Int = Build.VERSION.SDK_INT): Context =
            legacyLocale(base, sdk)?.let { localized(base, it) } ?: base

        /** The application's resources in the stored language, or null to use its own (API 33+, or following the phone). */
        fun localizedResources(base: Context, sdk: Int = Build.VERSION.SDK_INT): Resources? =
            legacyLocale(base, sdk)?.let { localized(base, it).resources }

        private fun localized(base: Context, locale: Locale): Context =
            base.createConfigurationContext(Configuration(base.resources.configuration).apply { setLocales(LocaleList(locale)) })
    }
}
