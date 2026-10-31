package app.granger.core.research

import com.google.common.truth.Truth.assertWithMessage
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Test
import java.io.File

/**
 * The Spanish word lists (languages spec §6.2) leave English alone: every English eval question gets exactly what it got
 * before Spanish from each function that now takes the question's languages, whether it's given English alone (the
 * default) or what [ResearchLanguage.of] says.
 */
class LanguageLayerTest {
    private val sets = File("../../tools/eval")
    private val en = listOf(ResearchLanguage.EN)

    private fun questions(name: String): List<JsonObject> =
        Json.parseToJsonElement(File(sets, "dataset/$name").readText()).jsonArray.map { it.jsonObject }

    private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    private val english: List<String> by lazy {
        listOf("tune.json", "heldout.json", "media.json").flatMap(::questions)
            .flatMap { listOfNotNull(it.text("question"), it.text("priorQuestion")?.ifBlank { null }) }
            .distinct() + AMBIGUOUS.filter { ResearchLanguage.EN in ResearchLanguage.of(it) }
    }

    /** The year-question fixtures: English questions with the passages their answers had. */
    private val fixtures: List<Triple<String, List<String>, List<Passage>>> by lazy {
        val out = mutableListOf<Triple<String, List<String>, MutableList<Passage>>>()
        javaClass.getResource("/year-questions.tsv")!!.readText().lines().forEach { line ->
            val f = line.split('\t')
            when (f[0]) {
                "Q" -> out += Triple(f[2], f[3].split(',').filter { it.isNotBlank() }, mutableListOf())
                "P" -> out.last().third += Passage(
                    SourceKind.valueOf(f[1]), "fixture", f[2], location = f[2].replace(' ', '_'), heading = f[3],
                    text = f[4].replace("\\n", "\n").replace("\\\\", "\\"),
                )
            }
        }
        out
    }

    private companion object {
        /**
         * English questions with no English function word, which [ResearchLanguage.of] gives as either language, and
         * some with a Spanish-looking word: the eval's questions all have one, so these try the Spanish lists on English.
         */
        val AMBIGUOUS = listOf(
            "Han dynasty founder", "Lake Como villas", "Victorian era fashion", "hay fever remedies", "solo flight Lindbergh",
            "dime novel history", "Los Angeles population", "La Paz altitude", "El Niño effects", "Sin City cast",
            "Con Edison founded", "Sea of Japan depth", "Tan Dun operas", "Este Lauder", "Bajo California", "Ante Pavelić",
            "Todo Mundo song", "Nada Surf albums", "Mas Oyama karate", "Pues Bay", "Don Quixote windmills",
            "Simon Bolivar birthplace", "La Mancha region", "Rio de Janeiro carnival", "Una Stubbs", "Ella Fitzgerald albums",
            "Le Mans winners", "Los Lobos band", "Santa Fe trail", "Alamo siege 1836", "Mesa Verde cliff dwellings",
            "Sierra Nevada peaks", "Hecho Valley", "Mi Amigo radio", "Para handball", "Ole Miss football",
        )

        /**
         * Of [AMBIGUOUS], those [ResearchLanguage.of] reads as Spanish alone (one Spanish function word, no English one):
         * the detector's call, not the lists', so they're pinned here rather than held to English.
         */
        val READ_AS_SPANISH = listOf("Lake Como villas", "Victorian era fashion", "hay fever remedies", "El Niño effects", "Para handball")
    }

    @Test fun theDetectorsSpanishReadingsArePinned() {
        assertWithMessage("read as Spanish alone").that(AMBIGUOUS.filter { ResearchLanguage.EN !in ResearchLanguage.of(it) })
            .containsExactlyElementsIn(READ_AS_SPANISH)
    }

    @Test fun englishQuestionsAreUnchanged() {
        val changed = mutableListOf<String>()
        for (q in english) {
            val of = ResearchLanguage.of(q)
            fun <T> same(what: String, a: T, b: T) { if (a != b) changed += "$what: $q ($of): $a -> $b" }
            val keywords = Keywords.extract(q, languages = en)
            same("extract", keywords, Keywords.extract(q, languages = of))
            same("extract (default)", keywords, Keywords.extract(q))
            for (w in Keywords.words(q)) same("isStopword $w", Keywords.isStopword(w, en), Keywords.isStopword(w, of))
            same("refersBack", FollowUp.refersBack(q, en), FollowUp.refersBack(q, of))
            same("media", MediaCues.asked(q, en), MediaCues.asked(q, of))
            same("literary", FictionWeight.literary(q, en), FictionWeight.literary(q, of))
            same("entities asked", Entities.asked(q, en), Entities.asked(q, of))
            same("runs", Entities.runs(q, en), Entities.runs(q, of))
            val names = Entities.named(q, keywords, en)
            same("named", names, Entities.named(q, keywords, of))
            same("eventWords", YearFacts.eventWords(q, names, en), YearFacts.eventWords(q, names, of))
            val passage = Passage(SourceKind.ARTICLE, "q", q.take(30), location = "q", text = "$q $q")
            same("lexical", LexicalScore.of(passage, keywords + q, en), LexicalScore.of(passage, keywords + q, of))
            same("contentWords", CitedQuote.contentWords(q, en), CitedQuote.contentWords(q, of))
            same("citations", Citations.check(q, 3, en), Citations.check(q, 3, of))
            for (title in names.map { it.split(' ').joinToString(" ") { w -> w.replaceFirstChar(Char::uppercaseChar) } }) {
                for (name in names) same("match $title/$name", Entities.match(title, name, en), Entities.match(title, name, of))
            }
        }
        assertWithMessage(changed.joinToString("\n")).that(changed).isEmpty()
    }

    @Test fun englishFixturesAreUnchanged() {
        val changed = mutableListOf<String>()
        for ((q, keywords, passages) in fixtures) {
            val of = ResearchLanguage.of(q)
            fun <T> same(what: String, a: T, b: T) { if (a != b) changed += "$what: $q ($of): $a -> $b" }
            same("block", YearFacts.block(q, keywords, passages, en), YearFacts.block(q, keywords, passages, of))
            same("things", YearFacts.things(q, keywords, passages, en), YearFacts.things(q, keywords, passages, of))
            for (p in passages) {
                same("lifespan", YearFacts.lifespan(p.text, en), YearFacts.lifespan(p.text, of))
                same("pick", CitedQuote.pick(q, 1, p.text, en), CitedQuote.pick(q, 1, p.text, of))
                same("check", Citations.check(p.text, 4, en), Citations.check(p.text, 4, of))
                for (name in Entities.named(q, keywords, en)) {
                    same("match", Entities.match(p.sourceTitle, name, en), Entities.match(p.sourceTitle, name, of))
                }
            }
            val named = Entities.named(q, keywords, en)
            same("reserve", Entities.reserve(passages, passages, named, 4, languages = en), Entities.reserve(passages, passages, named, 4, languages = of))
            same("preselect", Ranking.preselect(passages, keywords, RankingConfig(), languages = en),
                Ranking.preselect(passages, keywords, RankingConfig(), languages = of))
        }
        assertWithMessage(changed.joinToString("\n")).that(changed).isEmpty()
    }
}
