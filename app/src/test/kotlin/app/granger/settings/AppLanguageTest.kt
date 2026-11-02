package app.granger.settings

import android.app.LocaleManager
import android.content.Context
import android.os.LocaleList
import androidx.test.core.app.ApplicationProvider
import app.granger.GrangerApp
import app.granger.R
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Locale

/** Plan L2 Task 3: the choice, how it persists, and how it reaches the app on API 33+ and on 29–32. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AppLanguageTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val prefs = context.getSharedPreferences(AppLanguages.PREFS, Context.MODE_PRIVATE)
    private lateinit var defaultLocale: Locale

    @Before fun saveDefault() {
        defaultLocale = Locale.getDefault()
    }

    @After fun restoreDefault() {
        Locale.setDefault(defaultLocale)
    }

    // The choice itself.

    @Test fun tagsReadAsChoices() {
        assertThat(AppLanguage.of("es")).isEqualTo(AppLanguage.SPANISH)
        assertThat(AppLanguage.of("es-MX")).isEqualTo(AppLanguage.SPANISH)
        assertThat(AppLanguage.of("en-GB")).isEqualTo(AppLanguage.ENGLISH)
        assertThat(AppLanguage.of("fr")).isEqualTo(AppLanguage.SYSTEM)
        assertThat(AppLanguage.of("")).isEqualTo(AppLanguage.SYSTEM)
        assertThat(AppLanguage.of(null)).isEqualTo(AppLanguage.SYSTEM)
    }

    @Test fun aChoiceKeepsThePhonesRegion() {
        val phone = LocaleList.forLanguageTags("en-GB,es-MX")
        assertThat(AppLanguage.localeFor(AppLanguage.SPANISH, phone)).isEqualTo(Locale.forLanguageTag("es-MX"))
        assertThat(AppLanguage.localeFor(AppLanguage.ENGLISH, phone)).isEqualTo(Locale.forLanguageTag("en-GB"))
        assertThat(AppLanguage.localeFor(AppLanguage.SPANISH, LocaleList.forLanguageTags("en-US"))).isEqualTo(Locale.forLanguageTag("es"))
        assertThat(AppLanguage.localeFor(AppLanguage.SYSTEM, phone)).isNull()
    }

    @Test fun choicesAreNamedInThemselves() {
        assertThat(languageName(Locale.forLanguageTag("es-MX"))).isEqualTo("Español")
        assertThat(languageName(Locale.forLanguageTag("en-US"))).isEqualTo("English")
    }

    // API 33+: Android keeps it.

    @Config(sdk = [35])
    @Test fun onApi33ThePlatformIsTold() {
        val platform = FakePlatform(phone = LocaleList.forLanguageTags("en-US"))
        val languages = AppLanguages(prefs, platform)
        languages.set(AppLanguage.SPANISH)
        assertThat(platform.appLocales.toLanguageTags()).isEqualTo("es")
        assertThat(languages.language.value).isEqualTo(AppLanguage.SPANISH)
        // Read back from the platform, as after a restart.
        assertThat(AppLanguages(prefs, platform).language.value).isEqualTo(AppLanguage.SPANISH)
    }

    @Config(sdk = [35])
    @Test fun onApi33FollowThePhoneClearsTheOverride() {
        val platform = FakePlatform(phone = LocaleList.forLanguageTags("en-US"))
        val languages = AppLanguages(prefs, platform)
        languages.set(AppLanguage.SPANISH)
        languages.set(AppLanguage.SYSTEM)
        assertThat(platform.appLocales.isEmpty).isTrue()
        assertThat(AppLanguages(prefs, platform).language.value).isEqualTo(AppLanguage.SYSTEM)
    }

    @Config(sdk = [35])
    @Test fun onApi33ThePhonesLanguageScreenIsReadBack() {
        val platform = FakePlatform(phone = LocaleList.forLanguageTags("en-US"))
        val languages = AppLanguages(prefs, platform)
        platform.appLocales = LocaleList.forLanguageTags("es-419")
        languages.refresh()
        assertThat(languages.language.value).isEqualTo(AppLanguage.SPANISH)
    }

    @Config(sdk = [35])
    @Test fun onApi33ItIsLocaleManager() {
        val languages = AppLanguages.create(context)
        languages.set(AppLanguage.SPANISH)
        val manager = context.getSystemService(LocaleManager::class.java)
        assertThat(manager.applicationLocales.get(0).language).isEqualTo("es")
        assertThat(AppLanguages.create(context).language.value).isEqualTo(AppLanguage.SPANISH)
        languages.set(AppLanguage.SYSTEM)
        assertThat(manager.applicationLocales.isEmpty).isTrue()
    }

    @Config(sdk = [35])
    @Test fun onApi33NothingIsWrapped() {
        AppLanguages.create(context).set(AppLanguage.SPANISH)
        // Even a choice stored by an older Android isn't applied by hand: the platform does it.
        prefs.edit().putString("language", AppLanguage.SPANISH.name).commit()
        assertThat(AppLanguages.wrap(context)).isSameInstanceAs(context)
        assertThat(AppLanguages.localizedResources(context)).isNull()
    }

    // API 29–32: stored here and wrapped.

    @Config(sdk = [29])
    @Test fun onApi29TheChoicePersists() {
        var changes = 0
        val languages = AppLanguages.create(context) { changes++ }
        languages.set(AppLanguage.SPANISH)
        assertThat(changes).isEqualTo(1)
        assertThat(AppLanguages.create(context).language.value).isEqualTo(AppLanguage.SPANISH)
        languages.set(AppLanguage.ENGLISH)
        assertThat(AppLanguages.create(context).language.value).isEqualTo(AppLanguage.ENGLISH)
    }

    @Config(sdk = [29])
    @Test fun onApi29TheWrapperSetsTheLocale() {
        AppLanguages.create(context).set(AppLanguage.SPANISH)
        val wrapped = AppLanguages.wrap(context)
        assertThat(wrapped.resources.configuration.locales.get(0).language).isEqualTo("es")
        assertThat(wrapped.getString(R.string.common_settings)).isEqualTo("Ajustes")
        // Dates and numbers follow it too.
        assertThat(Locale.getDefault().language).isEqualTo("es")
    }

    @Config(sdk = [29])
    @Test fun onApi29FollowThePhoneClearsTheOverride() {
        val languages = AppLanguages.create(context)
        languages.set(AppLanguage.SPANISH)
        languages.set(AppLanguage.SYSTEM)
        assertThat(prefs.contains("language")).isFalse()
        assertThat(AppLanguages.wrap(context)).isSameInstanceAs(context)
        assertThat(context.getString(R.string.common_settings)).isEqualTo("Settings")
    }

    @Config(sdk = [29])
    @Test fun onApi29TheApplicationFollowsAtOnce() {
        val app = context as GrangerApp
        // As the container builds it.
        val languages = AppLanguages.create(app, onLegacyChange = app::relocalize)
        languages.set(AppLanguage.SPANISH)
        // Downloads, the widget and view models read the application's resources.
        assertThat(app.getString(R.string.common_settings)).isEqualTo("Ajustes")
        languages.set(AppLanguage.SYSTEM)
        assertThat(app.getString(R.string.common_settings)).isEqualTo("Settings")
    }

    private class FakePlatform(phone: LocaleList) : PlatformLocales {
        override val phoneLocales: LocaleList = phone
        override var appLocales: LocaleList = LocaleList.getEmptyLocaleList()
    }
}
