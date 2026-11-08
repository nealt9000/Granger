package app.granger

import android.content.Context
import android.text.format.Formatter
import androidx.test.core.app.ApplicationProvider
import app.granger.core.downloads.QueueText
import app.granger.sources.KiwixList
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/**
 * Plan L2 Task 4 Step 2: dates, numbers, sizes and times in Spanish. They follow the locale: plain `es` writes
 * "1,5 GB" and "27.700"; an es-419 country may group and point differently, and that's right too.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "es")
class SpanishFormatsTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val es = Locale.forLanguageTag("es")

    @Test fun theProcessRunsInSpanish() {
        assertThat(Locale.getDefault().language).isEqualTo("es")
    }

    @Test fun sizesTakeADecimalComma() {
        assertThat(formatBytes(1_500_000_000, es)).isEqualTo("1,5 GB")
        assertThat(formatBytes(612_000_000, es)).isEqualTo("612 MB")
        assertThat(Formatter.formatShortFileSize(context, 2_000_000_000)).isEqualTo("2,0 GB")
    }

    @Test fun countsGroupWithAPoint() {
        assertThat(java.text.NumberFormat.getIntegerInstance(es).format(27_700)).isEqualTo("27.700")
    }

    @Test fun catalogCategoriesAndTheClassicsLicenceReadInSpanish() {
        // The catalog keeps English names; the chips and headings say them in the app language (L1 walkthrough).
        assertThat(KiwixList.category("Medical").resolve(context)).isEqualTo("Medicina")
        assertThat(KiwixList.category("Literature & Drama").resolve(context)).isEqualTo("Literatura y teatro")
        assertThat(KiwixList.category("Scripture & religion").resolve(context)).isEqualTo("Escritura y religión")
        // Clásicos Granger's own sections are Spanish already and stay as they are.
        assertThat(KiwixList.category("Poesía").resolve(context)).isEqualTo("Poesía")
        assertThat(KiwixList.bookLicence("Public domain (US)").resolve(context)).isEqualTo("Dominio público (EE. UU.)")
        assertThat(KiwixList.bookLicence("CC BY 4.0").resolve(context)).isEqualTo("CC BY 4.0")
    }

    @Test fun aGrangerVoiceIsNamedInSpanish() {
        val titles = app.granger.core.speech.VoiceTitles.of(context)
        assertThat(app.granger.core.speech.SherpaSpeech.title("vits-piper-en_GB-granger_owl-medium", titles)).isEqualTo("Voz de Granger (Owl)")
    }

    @Test fun datesReadInSpanish() {
        // Readiness's "is from" date.
        assertThat(LocalDate.of(2025, 1, 10).format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(es))).isEqualTo("10 ene 2025")
        // A pack's "Updated" month.
        assertThat(KiwixList.updated("2026-03-05")!!.resolve(context)).isEqualTo("Actualizado: marzo 2026")
        // On this day's feed title.
        assertThat(context.getString(R.string.home_feed_day, "octubre", 26)).isEqualTo("26 de octubre")
    }

    @Test fun timesLeftAndPlacesInLine() {
        val res = context.resources
        assertThat(QueueText.timeLeft(res, 5 * 60_000L)).isEqualTo("unos 5 min")
        assertThat(QueueText.timeLeft(res, 20_000)).isEqualTo("menos de un minuto")
        assertThat(QueueText.inTime(res, 110_000)).isEqualTo("en 2 min")
        assertThat(QueueText.place(res, 2)).isEqualTo("2.º en la cola")
    }
}
