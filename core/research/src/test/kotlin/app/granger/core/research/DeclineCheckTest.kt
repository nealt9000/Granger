package app.granger.core.research

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test
import java.io.File

class DeclineCheckTest {
    private fun passage(text: String, title: String = "Untitled", heading: String = "") =
        Passage(SourceKind.ARTICLE, "id", title, heading = heading, text = text)

    @Test fun nearbyPersonalAndLiveQuestionsAreUnknowable() {
        listOf(
            "Where's the cheapest gas near me?",
            "Is the pizza place down the street open on Sundays?",
            "Is the downtown library open late on Thursdays?",
            "What time does the Walgreens on Main Street close",
            "what day is garbage pickup on my street",
            "What's my library's wifi password?",
            "Who is my state representative?",
            "when is my mom's birthday",
            "Is my Amazon package going to arrive today?",
            "Will the Lakers make the playoffs this season?",
            "what's the price of bitcoin right now",
            "What was the final score of last night's NFL game?",
            "where did i park my car this morning",
        ).forEach { assertWithMessage(it).that(DeclineCheck.unknowable(it)).isTrue() }
    }

    @Test fun everydayQuestionsWithMyOrPlacesStayAnswerable() {
        listOf(
            "how do I fix my bike chain",
            "how do I change my car's oil",
            "how do I find my IP address on linux",
            "how do I undo my last git commit",
            "how do I fix my wifi",
            "will antibiotics help my cold",
            "when will Halley's comet return",
            "which stars are nearby the sun",
            "who wrote On the Road",
            "who lived at 221B Baker Street",
            "what is my blood type made of",
            "what time does the sun rise at the equator",
            "what happened on the night of the Titanic",
        ).forEach { assertWithMessage(it).that(DeclineCheck.unknowable(it)).isFalse() }
    }

    @Test fun aSpanishQuestionKeepsTheModelsJudgment() {
        assertThat(DeclineCheck.unknowable("¿Dónde hay gasolina barata near me?", listOf(ResearchLanguage.ES))).isFalse()
    }

    @Test fun anAnswerFromAnotherYearsEventIsOffTopic() {
        val q = "Who won the 2026 World Series?"
        assertThat(DeclineCheck.offTopic(q, listOf(passage("The Cubs won.", title = "2016 World Series")))).isTrue()
        assertThat(DeclineCheck.offTopic(q, listOf(passage("In 2026 the Dodgers won.", title = "World Series")))).isFalse()
        // No year asked, or a question that works with dates: left to the model.
        assertThat(DeclineCheck.offTopic("Who won the World Series?", listOf(passage("The Cubs won.", title = "2016 World Series")))).isFalse()
        assertThat(DeclineCheck.offTopic("How old was Lincoln in 1860?", listOf(passage("Lincoln was born in 1809.")))).isFalse()
    }

    /**
     * Over the whole tuning split: no answerable question is declined unasked, and the unanswerables caught stay caught
     * (2026-11-02: 30 of 76). Tuning data only; the held-out set is never read here.
     */
    @Test fun tuningSplit() {
        val items = Json.parseToJsonElement(File("../../tools/eval/dataset/tune.json").readText()).jsonArray.map { it.jsonObject }
        fun q(it: kotlinx.serialization.json.JsonObject) = it["question"]!!.jsonPrimitive.content
        val answerable = items.filter { it["answerable"]!!.jsonPrimitive.boolean }
        val unanswerable = items.filterNot { it["answerable"]!!.jsonPrimitive.boolean }
        val wrong = answerable.map(::q).filter { DeclineCheck.unknowable(it) }
        val caught = unanswerable.map(::q).filter { DeclineCheck.unknowable(it) }
        println("SCORE unknowable: ${caught.size}/${unanswerable.size} unanswerables, ${wrong.size}/${answerable.size} answerables")
        assertThat(wrong).isEmpty()
        assertThat(caught.size).isAtLeast(CAUGHT)
    }

    private companion object {
        const val CAUGHT = 31
    }
}
