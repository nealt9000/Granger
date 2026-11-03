package app.granger.core.research

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test
import java.io.File

/**
 * The Spanish medical layer (languages spec §6.2) against the eval's written labels: every Spanish medical question is
 * caught, and every English question gets exactly what it got before Spanish.
 */
class MedicalEsTest {
    private val sets = File("../../tools/eval")

    private data class Label(val q: String, val medical: Boolean, val kind: String)

    private fun labels(name: String): List<Label> =
        File(sets, "sets/$name").readLines().filter { it.isNotBlank() }.map { Json.parseToJsonElement(it).jsonObject }
            .map { Label(it.text("q")!!, it["medical"]!!.jsonPrimitive.boolean, it.text("kind")!!) }

    private fun questions(name: String): List<JsonObject> =
        Json.parseToJsonElement(File(sets, "dataset/$name").readText()).jsonArray.map { it.jsonObject }

    private fun JsonObject.text(key: String): String? = (this[key] as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { it.isString }?.content

    private fun JsonObject.medical(): Boolean = (this["medical"] as? kotlinx.serialization.json.JsonPrimitive)?.booleanOrNull == true

    private fun signal(q: String) = Medical.looksMedical(q) || PersonalMedical.strong(q)

    private fun checkSpanish(name: String) {
        val all = labels(name)
        val missed = all.filter { it.medical && !signal(it.q) }.map { it.q }
        assertWithMessage("$name: medical questions not flagged").that(missed).isEmpty()
        val notPersonal = all.filter { it.kind == "personal" && !PersonalMedical.matches(it.q, signal(it.q)) }.map { it.q }
        assertWithMessage("$name: personal questions not declined").that(notPersonal).isEmpty()
        val flaggedOther = all.filter { it.kind == "other" && signal(it.q) }.map { it.q }
        assertWithMessage("$name: ordinary questions flagged").that(flaggedOther).isEmpty()
        val declinedNot = all.filter { !it.medical && PersonalMedical.matches(it.q, signal(it.q)) }.map { it.q }
        assertWithMessage("$name: not-medical questions declined").that(declinedNot).isEmpty()
    }

    @Test fun catchesEverySpanishTuningLabel() = checkSpanish("medical-labels-es.jsonl")

    @Test fun catchesEverySpanishHeldOutLabel() = checkSpanish("medical-heldout-es.jsonl")

    /** Near-misses may raise the banner (a banner too many costs little); the list is pinned so a change shows. */
    @Test fun nearMissesFlagged() {
        val flagged = (labels("medical-labels-es.jsonl") + labels("medical-heldout-es.jsonl"))
            .filter { it.kind == "near-miss" && signal(it.q) }.map { it.q }
        println("near-misses flagged: ${flagged.size}\n" + flagged.joinToString("\n"))
        assertThat(flagged.size).isAtMost(NEAR_MISS_BUDGET)
    }

    @Test fun spanishEvalMedicalQuestionsAreFlagged() {
        val missed = listOf("tune-es.json", "heldout-es.json", "mixed-es.json").flatMap(::questions)
            .filter { it.medical() && ResearchLanguage.main(it.text("question")!!) == ResearchLanguage.ES }
            .map { it.text("question")!! }.filterNot(::signal)
        assertThat(missed).isEmpty()
    }

    @Test fun englishQuestionsAreUnchanged() {
        val english = labels("medical-labels.jsonl").map { it.q } +
            listOf("tune.json", "heldout.json", "media.json").flatMap(::questions)
                .flatMap { listOfNotNull(it.text("question"), it.text("priorQuestion")?.ifBlank { null }) }
        for (q in english) {
            assertWithMessage(q).that(ResearchLanguage.of(q)).contains(ResearchLanguage.EN)
            assertWithMessage("medical: $q").that(Medical.looksMedical(q)).isEqualTo(Medical.english(q))
            val signal = Medical.english(q)
            assertWithMessage("personal: $q").that(PersonalMedical.matches(q, signal)).isEqualTo(PersonalMedical.english(q, signal))
        }
    }

    @Test fun spanishEvalQuestionsAreSpanish() {
        val qs = listOf("tune-es.json", "heldout-es.json").flatMap(::questions).map { it.text("question")!! }
        val notSpanish = qs.filter { ResearchLanguage.main(it) != ResearchLanguage.ES }
        println("Spanish eval questions not detected as Spanish: ${notSpanish.size}/${qs.size}\n" + notSpanish.joinToString("\n"))
        assertThat(notSpanish.size).isAtMost(qs.size / 20)
    }

    @Test fun foldedMatching() {
        assertThat(Medical.looksMedical("fiebre en bebés")).isTrue()
        assertThat(Medical.looksMedical("fiebre en bebes")).isTrue()
        assertThat(Medical.looksMedical("¿Qué es la Migraña?")).isTrue()
        assertThat(Medical.looksMedical("cómo cambiar las pastillas de freno")).isFalse()
        assertThat(Medical.looksMedical("qué fue la fiebre del oro")).isFalse()
        // Spanish "mole" is a sauce: the English lexicon is off for a clearly Spanish question. A fragment whose only
        // Spanish words are English too ("receta del mole") keeps it on: a stray banner beats losing "hay fever".
        assertThat(Medical.looksMedical("¿cómo se hace el mole poblano?")).isFalse()
        assertThat(Medical.looksMedical("hay fever remedies")).isTrue()
        assertThat(Medical.looksMedical("How do I remove a mole?")).isTrue()
    }

    @Test fun personalSpanish() {
        assertThat(PersonalMedical.strong("¿Le puedo dar ibuprofeno a mi hijo de 20 kilos?")).isTrue()
        assertThat(PersonalMedical.strong("mi niño se tragó una pila de botón")).isTrue()
        assertThat(PersonalMedical.matches("¿puedo tomar el tren a Cusco?", Medical.looksMedical("¿puedo tomar el tren a Cusco?"))).isFalse()
        // tune-es es-medical-017 was answered on the phone (l4-tune-es-1): a result as a number, and "para mí".
        val tsh = "mi TSH salió en 6.2, ¿qué significa para mí?"
        assertThat(PersonalMedical.matches(tsh, signal(tsh))).isTrue()
        assertThat(PersonalMedical.matches("¿qué significa para mí tener el colesterol alto?", true)).isTrue()
        // Not medical, so the generic phrasings don't count.
        val book = "mi libro favorito salió en 1967, ¿qué significa para mí releerlo?"
        assertThat(PersonalMedical.matches(book, signal(book))).isFalse()
    }

    private companion object {
        const val NEAR_MISS_BUDGET = 20
    }
}
