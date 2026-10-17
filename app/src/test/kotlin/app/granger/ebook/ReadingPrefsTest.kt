package app.granger.ebook

import android.content.Context
import androidx.compose.ui.graphics.toArgb
import androidx.test.core.app.ApplicationProvider
import app.granger.theme.GrangerColors
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.readium.r2.navigator.preferences.Color
import org.readium.r2.navigator.preferences.FontFamily
import org.readium.r2.navigator.preferences.TextAlign
import org.readium.r2.navigator.preferences.Theme
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ReadingPrefsTest {
    private val shared = ApplicationProvider.getApplicationContext<Context>()
        .getSharedPreferences("reading-test", Context.MODE_PRIVATE)

    @Test fun toEpubOverridesPublisherStylesAndPaginates() {
        val epub = ReadingPrefs(
            typeface = Typeface.SANS, fontScale = 1.25, lineHeight = 1.8, margins = 1.5,
            align = Align.JUSTIFY, theme = ReadingTheme.SEPIA,
        ).toEpub()
        assertThat(epub.publisherStyles).isFalse()
        assertThat(epub.scroll).isFalse()
        assertThat(epub.fontFamily).isEqualTo(FontFamily.SANS_SERIF)
        assertThat(epub.fontSize).isEqualTo(1.25)
        assertThat(epub.lineHeight).isEqualTo(1.8)
        assertThat(epub.pageMargins).isEqualTo(1.5)
        assertThat(epub.textAlign).isEqualTo(TextAlign.JUSTIFY)
        assertThat(epub.theme).isEqualTo(Theme.SEPIA)

        val defaults = ReadingPrefs().toEpub()
        assertThat(defaults.fontFamily).isEqualTo(FontFamily.SERIF)
        assertThat(defaults.textAlign).isEqualTo(TextAlign.START)
        assertThat(ReadingPrefs(theme = ReadingTheme.DAY).toEpub().theme).isEqualTo(Theme.LIGHT)
    }

    @Test fun nightUsesAppColours() {
        val epub = ReadingPrefs(theme = ReadingTheme.NIGHT).toEpub()
        assertThat(epub.theme).isEqualTo(Theme.DARK)
        assertThat(epub.backgroundColor).isEqualTo(Color(GrangerColors.Background.toArgb()))
        assertThat(epub.textColor).isEqualTo(Color(GrangerColors.OnSurface.toArgb()))
        // Day and sepia keep Readium's own theme colours.
        assertThat(ReadingPrefs(theme = ReadingTheme.DAY).toEpub().backgroundColor).isNull()
    }

    @Test fun storeClampsOutOfRangeValues() {
        val store = ReadingPrefsStore(shared)
        assertThat(store.prefs.value).isEqualTo(ReadingPrefs())

        store.update(ReadingPrefs(fontScale = 9.0, lineHeight = 0.1, margins = 7.0, theme = ReadingTheme.DAY))
        assertThat(store.prefs.value).isEqualTo(
            ReadingPrefs(fontScale = 2.5, lineHeight = 1.2, margins = 2.0, theme = ReadingTheme.DAY),
        )
        store.update(ReadingPrefs(fontScale = 0.1, lineHeight = 9.0, margins = 0.0))
        assertThat(store.prefs.value).isEqualTo(ReadingPrefs(fontScale = 0.75, lineHeight = 2.0, margins = 0.5))
        // Font scale moves in eighths.
        store.update(ReadingPrefs(fontScale = 1.3))
        assertThat(store.prefs.value.fontScale).isEqualTo(1.25)

        // Global and persistent: a new store reads what the last one wrote.
        store.update(ReadingPrefs(typeface = Typeface.SANS, align = Align.JUSTIFY, theme = ReadingTheme.SEPIA, fontScale = 1.5))
        assertThat(ReadingPrefsStore(shared).prefs.value)
            .isEqualTo(ReadingPrefs(typeface = Typeface.SANS, align = Align.JUSTIFY, theme = ReadingTheme.SEPIA, fontScale = 1.5))
    }
}
