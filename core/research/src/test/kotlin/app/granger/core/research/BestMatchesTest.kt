package app.granger.core.research

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Test

class BestMatchesTest {
    private fun passage(id: String, location: String, title: String, text: String, cosine: Double? = null) =
        Passage(SourceKind.ARTICLE, id, title, location = location, text = text, cosine = cosine, language = ResearchLanguage.EN)

    private val burnFirstAid = passage("wikimed", "Burn", "Burn",
        "Burns are common injuries. Cool the burn with running water for twenty minutes. Do not use ice.")
    private val burnMore = passage("wikimed", "Burn", "Burn", "A burn can be thermal, chemical or electrical.")
    private val scald = passage("wikimed", "Scald", "Scald", "A scald is a burn from hot liquid or steam.")

    @Test fun oneRowPerArticleBestPassageFirst() = runTest {
        val finder = PassageFinder { listOf(burnMore, burnFirstAid, scald) }
        val result = BestMatches(finder).find("how do I treat a burn")
        assertThat(result.keywords).isNotEmpty()
        assertThat(result.matches.map { it.passage.location }).containsNoDuplicates()
        assertThat(result.matches.map { it.passage.location }).contains("Burn")
        assertThat(result.byMeaning).isFalse()
    }

    @Test fun meaningHitsJoinTheKeywordOnes() = runTest {
        val dense = passage("wikem", "Thermal_burns", "Thermal burns", "Treat thermal burns by cooling them with water.", cosine = 0.86)
        val result = BestMatches(
            PassageFinder { listOf(scald) },
            dense = DenseFinder { listOf(dense) },
            embed = { FloatArray(4) },
        ).find("how do I treat a burn")
        assertThat(result.byMeaning).isTrue()
        assertThat(result.matches.first().passage.location).isEqualTo("Thermal_burns")
    }

    @Test fun unsureMeaningHitsStillListWhenNoKeywordPassageMatches() = runTest {
        // Below Ask's gate (0.78) and with no keyword passage: Ask would decline; Search lists its guesses.
        val dense = passage("wikipedia", "Buoyancy", "Buoyancy", "An object floats when it displaces its own weight of water.", cosine = 0.76)
        val result = BestMatches(PassageFinder { emptyList() }, dense = DenseFinder { listOf(dense) }, embed = { FloatArray(4) })
            .find("how can a huge steel ship float")
        assertThat(result.matches.map { it.passage.location }).containsExactly("Buoyancy")
    }

    @Test fun meaningHitsBelowTheFloorStillListAlone() = runTest {
        val dense = passage("wikipedia", "Buoyancy", "Buoyancy", "An object floats when it displaces its own weight of water.", cosine = 0.60)
        val result = BestMatches(PassageFinder { emptyList() }, dense = DenseFinder { listOf(dense) }, embed = { FloatArray(4) })
            .find("how can a huge steel ship float")
        assertThat(result.matches.map { it.passage.location }).containsExactly("Buoyancy")
    }

    @Test fun oneArticleInTwoPacksOfAFamilyIsOneRow() = runTest {
        val top = passage("wp-top", "Capillary_action", "Capillary action", "Capillary action draws water up a narrow tube.", cosine = 0.80)
        val physics = top.copy(sourceId = "wp-physics")
        val other = passage("wp-top", "Refraction", "Refraction", "Light bends as it passes from water into air.", cosine = 0.79)
        val search = BestMatches(
            PassageFinder { emptyList() }, dense = DenseFinder { listOf(top, physics, other) }, embed = { FloatArray(4) },
            articleKey = { { p: Passage -> (if (p.sourceId.startsWith("wp-")) "wikipedia_en" else p.sourceId) to p.location } },
        )
        assertThat(search.find("why does a straw look bent in water").matches.map { it.passage.location })
            .containsExactly("Capillary_action", "Refraction").inOrder()
    }

    @Test fun aFailingEmbedderLeavesTheKeywordSide() = runTest {
        val result = BestMatches(
            PassageFinder { listOf(burnFirstAid) },
            dense = DenseFinder { error("no vectors") },
            embed = { FloatArray(4) },
        ).find("treat a burn")
        assertThat(result.byMeaning).isFalse()
        assertThat(result.matches.map { it.passage.location }).containsExactly("Burn")
    }

    @Test fun theHighlightIsTheSentenceWithMostQueryWords() {
        val range = BestMatches.sentence(burnFirstAid, listOf("cool", "burn", "water"), Keywords.ENGLISH)!!
        assertThat(burnFirstAid.text.substring(range)).startsWith("Cool the burn with running water")
    }

    @Test fun meaningHitsComeFirstWhileTheKeywordSearchRuns() = runTest {
        val dense = passage("wikem", "Thermal_burns", "Thermal burns", "Treat thermal burns by cooling them with water.", cosine = 0.86)
        val slowWords = PassageFinder { kotlinx.coroutines.delay(1_000); listOf(burnFirstAid) }
        val steps = BestMatches(slowWords, dense = DenseFinder { listOf(dense) }, embed = { FloatArray(4) })
            .stream("how do I treat a burn").toList()
        assertThat(steps).hasSize(2)
        assertThat(steps[0].final).isFalse()
        assertThat(steps[0].matches.map { it.passage.location }).containsExactly("Thermal_burns")
        assertThat(steps[1].final).isTrue()
        assertThat(steps[1].matches.map { it.passage.location }).contains("Burn")
    }

    @Test fun withoutMeaningThereIsOneStep() = runTest {
        val steps = BestMatches(PassageFinder { listOf(burnFirstAid) }).stream("treat a burn").toList()
        assertThat(steps.map { it.final }).containsExactly(true)
    }
}
