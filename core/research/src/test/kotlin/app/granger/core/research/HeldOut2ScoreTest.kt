package app.granger.core.research

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.io.File

/**
 * The Spanish medical layer's clean score: `medical-heldout2-es.jsonl` was written by an author who saw neither the
 * lexicon nor the earlier lists, and is never tuned on (don't add its misses to [MedicalEs]). First run, 2026-10-31:
 * 72/110 medical caught, 17/35 personal declined, 0 ordinary flagged, 9/38 near-misses flagged, 0 wrongly declined.
 * The floors pin that score so a change that loses ground shows; the keyword lists alone can't reach every question
 * (English's catch 46/106 of its labels), so Spanish Ask's bar also counts the model's medical flag (Plan L4 notes).
 */
class HeldOut2ScoreTest {
    @Test fun score() {
        val rows = File("../../tools/eval/sets/medical-heldout2-es.jsonl").readLines().filter { it.isNotBlank() }
            .map { Json.parseToJsonElement(it).jsonObject }
            .map { Triple(it["q"]!!.jsonPrimitive.content, it["medical"]!!.jsonPrimitive.boolean, it["kind"]!!.jsonPrimitive.content) }
        fun signal(q: String) = Medical.looksMedical(q) || PersonalMedical.strong(q)
        val med = rows.filter { it.second }
        val missed = med.filterNot { signal(it.first) }
        val pers = rows.filter { it.third == "personal" }
        val notDeclined = pers.filterNot { PersonalMedical.matches(it.first, signal(it.first)) }
        val other = rows.filter { it.third == "other" && signal(it.first) }
        val near = rows.filter { it.third == "near-miss" && signal(it.first) }
        val wrongDecline = rows.filter { !it.second && PersonalMedical.matches(it.first, signal(it.first)) }
        println("SCORE medical caught ${med.size - missed.size}/${med.size}")
        missed.forEach { println("MISS ${it.third}: ${it.first}") }
        println("SCORE personal declined ${pers.size - notDeclined.size}/${pers.size}")
        notDeclined.forEach { println("NODECL: ${it.first}") }
        println("SCORE other flagged ${other.size}, near-miss flagged ${near.size}/${rows.count { it.third == "near-miss" }}, not-medical declined ${wrongDecline.size}")
        (other + near).forEach { println("FLAG ${it.third}: ${it.first}") }
        wrongDecline.forEach { println("WRONGDECL: ${it.first}") }
        assertThat(med.size - missed.size).isAtLeast(72)
        assertThat(pers.size - notDeclined.size).isAtLeast(17)
        assertThat(other).isEmpty()
        assertThat(wrongDecline).isEmpty()
    }
}
