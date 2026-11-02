package app.granger.settings

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Locale

/** The library's languages (Plan L3 Task 6). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class KnowledgeLanguagesTest {
    private val both = listOf("en", "es")

    @Test fun theDefaultFollowsTheAppLanguage() {
        assertThat(KnowledgeLanguages.resolve(null, "es", both)).containsExactly("es")
        assertThat(KnowledgeLanguages.resolve(null, "en", both)).containsExactly("en")
        // A region or an ISO 639-2 code is the same language.
        assertThat(KnowledgeLanguages.resolve(null, "es-MX", both)).containsExactly("es")
    }

    @Test fun anAppLanguageWithoutTiersFallsBackToEnglish() {
        assertThat(KnowledgeLanguages.resolve(null, "fr", both)).containsExactly("en")
        // An English-only catalog (today's): a Spanish app's library is English.
        assertThat(KnowledgeLanguages.resolve(null, "es", listOf("en"))).containsExactly("en")
        // Before the catalog loads there is nothing to match.
        assertThat(KnowledgeLanguages.resolve(null, "es", emptyList())).containsExactly("en")
    }

    @Test fun aChoiceKeepsItsOrderAndOnlyWhatTheCatalogHas() {
        assertThat(KnowledgeLanguages.resolve(listOf("es", "en"), "en", both)).containsExactly("es", "en").inOrder()
        assertThat(KnowledgeLanguages.resolve(listOf("es", "en"), "en", listOf("en"))).containsExactly("en")
        assertThat(KnowledgeLanguages.resolve(listOf("fr"), "es", both)).containsExactly("es")
        // Not yet loaded: the choice stands.
        assertThat(KnowledgeLanguages.resolve(listOf("es"), "en", emptyList())).containsExactly("es")
    }

    @Test fun theLastLanguageStaysOnAndMainMovesFirst() {
        assertThat(KnowledgeLanguages.toggle(listOf("es"), "es")).containsExactly("es")
        assertThat(KnowledgeLanguages.toggle(listOf("es"), "en")).containsExactly("es", "en").inOrder()
        assertThat(KnowledgeLanguages.toggle(listOf("es", "en"), "es")).containsExactly("en")
        assertThat(KnowledgeLanguages.makeMain(listOf("es", "en"), "en")).containsExactly("en", "es").inOrder()
    }

    @Test fun theOfferNeedsTwoSupportedPhoneLanguages() {
        val phone = KnowledgeLanguages.phoneLanguages(listOf(Locale.forLanguageTag("es-MX"), Locale.FRENCH, Locale.US), both)
        assertThat(phone).containsExactly("es", "en").inOrder()
        assertThat(KnowledgeLanguages.secondOffer(listOf("es"), phone)).isEqualTo("en")
        assertThat(KnowledgeLanguages.secondOffer(listOf("en"), phone)).isEqualTo("es")
        val one = KnowledgeLanguages.phoneLanguages(listOf(Locale.forLanguageTag("es-MX"), Locale.FRENCH), both)
        assertThat(KnowledgeLanguages.secondOffer(listOf("es"), one)).isNull()
    }

    @Test fun theChoicePersistsAndClears() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val app = MutableStateFlow("es")
        val supported = MutableStateFlow(both)
        val first = KnowledgeLanguages.create(context, app, supported)
        assertThat(first.languages.first()).containsExactly("es")
        first.set(listOf("es", "en"))
        val again = KnowledgeLanguages.create(context, app, supported)
        assertThat(again.languages.first()).containsExactly("es", "en").inOrder()
        again.set(emptyList())
        app.value = "en"
        assertThat(KnowledgeLanguages.create(context, app, supported).languages.first()).containsExactly("en")
    }
}
