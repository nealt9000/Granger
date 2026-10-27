package app.granger.ebook

import android.content.Context
import androidx.compose.ui.graphics.toArgb
import androidx.test.core.app.ApplicationProvider
import app.granger.theme.FieldColors
import app.granger.theme.NightColors
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

    @Test fun toPdfiumMapsPagesFitAndNight() {
        val paged = ReadingPrefs().toPdfium()
        assertThat(paged.scroll).isFalse()
        assertThat(paged.scrollAxis).isEqualTo(org.readium.r2.navigator.preferences.Axis.HORIZONTAL)
        assertThat(paged.fit).isEqualTo(org.readium.r2.navigator.preferences.Fit.CONTAIN)
        val scrolled = ReadingPrefs(pdfScroll = true, pdfFitWidth = true).toPdfium()
        assertThat(scrolled.scroll).isTrue()
        assertThat(scrolled.scrollAxis).isEqualTo(org.readium.r2.navigator.preferences.Axis.VERTICAL)
        assertThat(scrolled.fit).isEqualTo(org.readium.r2.navigator.preferences.Fit.WIDTH)
        assertThat(ReadingPrefs(theme = ReadingTheme.NIGHT).pdfNight).isTrue()
        assertThat(ReadingPrefs(theme = ReadingTheme.SEPIA).pdfNight).isFalse()
        // Night and Fit are read only at layout, so either one changes the look and rebuilds the navigator.
        val day = ReadingPrefs(theme = ReadingTheme.DAY)
        assertThat(day.copy(theme = ReadingTheme.NIGHT).pdfLook).isNotEqualTo(day.pdfLook)
        assertThat(day.copy(pdfFitWidth = true).pdfLook).isNotEqualTo(day.pdfLook)
        assertThat(day.copy(pdfScroll = true).pdfLook).isEqualTo(day.pdfLook)
    }

    @Test fun pdfPrefsSurviveTheStore() {
        val store = ReadingPrefsStore(shared)
        store.update(ReadingPrefs(pdfScroll = true, pdfFitWidth = true))
        val again = ReadingPrefsStore(shared).prefs.value
        assertThat(again.pdfScroll).isTrue()
        assertThat(again.pdfFitWidth).isTrue()
    }

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
        assertThat(epub.backgroundColor).isEqualTo(Color(NightColors.background.toArgb()))
        assertThat(epub.textColor).isEqualTo(Color(NightColors.onSurface.toArgb()))
        // Day and sepia keep Readium's own theme colours.
        assertThat(ReadingPrefs(theme = ReadingTheme.DAY).toEpub().backgroundColor).isNull()
    }

    @Test fun matchAppFollowsTheAppsTheme() {
        assertThat(ReadingPrefs().theme).isEqualTo(ReadingTheme.APP)
        val night = ReadingPrefs(theme = ReadingTheme.APP, appNight = true)
        assertThat(night.toEpub().theme).isEqualTo(Theme.DARK)
        assertThat(night.toEpub().backgroundColor).isEqualTo(Color(NightColors.background.toArgb()))
        assertThat(night.pdfNight).isTrue()
        // In Field the page is the app's paper and ink, not Readium's white.
        val field = night.copy(appNight = false)
        assertThat(field.toEpub().theme).isEqualTo(Theme.LIGHT)
        assertThat(field.toEpub().backgroundColor).isEqualTo(Color(FieldColors.background.toArgb()))
        assertThat(field.toEpub().textColor).isEqualTo(Color(FieldColors.onSurface.toArgb()))
        assertThat(field.pdfNight).isFalse()
        assertThat(field.pdfLook).isNotEqualTo(night.pdfLook)
        // The reader's own choices ignore the app.
        assertThat(ReadingPrefs(theme = ReadingTheme.NIGHT, appNight = false).pdfNight).isTrue()
        assertThat(ReadingPrefs(theme = ReadingTheme.DAY, appNight = true).toEpub().theme).isEqualTo(Theme.LIGHT)
    }

    @Test fun storeTracksTheAppWithoutSavingIt() {
        val store = ReadingPrefsStore(shared)
        store.setAppNight(false)
        assertThat(store.prefs.value.appNight).isFalse()
        // A change in the reader keeps the app's state, and a new store starts from its default again.
        store.update(store.prefs.value.copy(fontScale = 1.5))
        assertThat(store.prefs.value.appNight).isFalse()
        assertThat(ReadingPrefsStore(shared).prefs.value.appNight).isTrue()
    }

    @Test fun linksUseTheBookLinkColourInEveryTheme() {
        // ReadiumCSS night mode would otherwise paint links #63caff: blue on black.
        val accent = org.readium.r2.navigator.epub.css.Color.Int(BookLink)
        val css = readerConfiguration().readiumCssRsProperties
        assertThat(css.linkColor).isEqualTo(accent)
        assertThat(css.visitedColor).isEqualTo(accent)
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
