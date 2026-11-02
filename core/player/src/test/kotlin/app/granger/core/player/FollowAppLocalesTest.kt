package app.granger.core.player

import android.content.Context
import android.content.ContextWrapper
import android.content.res.Configuration
import android.os.LocaleList
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Plan L2 Task 3: below API 33 the playback service follows the language the app put on its application. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class FollowAppLocalesTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test fun theSameLocalesLeaveTheContextAlone() {
        assertThat(followAppLocales(context)).isSameInstanceAs(context)
    }

    @Test fun theServiceTakesTheApplicationsLanguage() {
        val spanishApp = context.createConfigurationContext(
            Configuration(context.resources.configuration).apply { setLocales(LocaleList.forLanguageTags("es")) },
        )
        val base = object : ContextWrapper(context) {
            override fun getApplicationContext(): Context = spanishApp
        }
        val followed = followAppLocales(base)
        assertThat(followed.resources.configuration.locales.get(0).language).isEqualTo("es")
        assertThat(followed.getString(R.string.player_reading_title, "Emma")).isEqualTo(spanishApp.getString(R.string.player_reading_title, "Emma"))
        assertThat(followed.getString(R.string.player_reading_title, "Emma")).isNotEqualTo(context.getString(R.string.player_reading_title, "Emma"))
    }
}
