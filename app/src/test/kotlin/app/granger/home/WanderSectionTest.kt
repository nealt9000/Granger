package app.granger.home

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.performTextInput
import app.granger.core.library.SearchHit
import app.granger.readiness.ReadinessReport
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w400dp-h800dp")
class WanderSectionTest {
    @get:Rule val compose = createComposeRule()

    private fun node(path: String, onPhone: Boolean = true) = WanderNode("top", path, path.replace('_', ' '), onPhone = onPhone)
    private val sophia = node("Hagia_Sophia")
    private val links = mapOf(
        "Hagia_Sophia" to listOf(node("Istanbul"), node("Justinian_I"), node("Byzantine_Empire")),
        "Istanbul" to listOf(node("Hagia_Sophia"), node("Bosphorus"), node("Turkey")),
    )

    /** A stand-in for [WanderModel]: the same moves, synchronously. */
    private class Harness(start: WanderUi) { var ui by mutableStateOf(start) }

    private fun show(h: Harness, opened: MutableList<String> = mutableListOf(), picked: MutableList<String> = mutableListOf()) {
        val actions = WanderActions(
            onGo = { n -> h.ui = h.ui.copy(centre = n, orbit = links[n.path].orEmpty(), trail = h.ui.trail + h.ui.centre!!) },
            onBack = { i -> val n = h.ui.trail[i]; h.ui = h.ui.copy(centre = n, orbit = links[n.path].orEmpty(), trail = h.ui.trail.take(i)) },
            onQuery = { q -> h.ui = h.ui.copy(query = q, hits = if (q.isBlank()) emptyList() else listOf(SearchHit("top", "Wikipedia", "Ada_Lovelace", "Ada Lovelace"))) },
            onPick = { hit -> picked += hit.path; h.ui = h.ui.copy(query = "", hits = emptyList()) },
        )
        compose.setContent {
            HomeContent(HomeUi(strip = ReadinessReport.EMPTY), onReadiness = {}, onOpenTab = {}, onPlay = {}, onOpenCard = { opened += (it.target as CardTarget.Article).path }, wander = h.ui, wanderActions = actions)
        }
    }

    @Test fun tappingALinkMovesItToTheCentreAndTheCrumbGoesBack() {
        val h = Harness(WanderUi(centre = sophia, summary = "Hagia Sophia is a mosque in Istanbul.", orbit = links.getValue("Hagia_Sophia")))
        show(h)
        compose.onNodeWithTag("home-list").performScrollToNode(hasTestTag("wander-orbit"))
        compose.onNodeWithContentDescription("Go to Istanbul").performClick()
        compose.waitForIdle()
        assertThat(h.ui.centre?.path).isEqualTo("Istanbul")
        compose.onNodeWithContentDescription("Go to Bosphorus").assertExists()
        compose.onNodeWithTag("home-list").performScrollToNode(hasTestTag("wander-trail"))
        compose.onNodeWithTag("wander-crumb-0").performClick()
        compose.waitForIdle()
        assertThat(h.ui.centre?.path).isEqualTo("Hagia_Sophia")
        assertThat(h.ui.trail).isEmpty()
    }

    @Test fun openOpensTheCentreAndAMissingLinkDoesNothing() {
        val opened = mutableListOf<String>()
        val h = Harness(WanderUi(centre = sophia, orbit = listOf(node("Nowhere", onPhone = false))))
        show(h, opened)
        compose.onNodeWithContentDescription("Nowhere, not on this phone").performClick()
        assertThat(h.ui.centre?.path).isEqualTo("Hagia_Sophia")
        compose.onNodeWithTag("home-list").performScrollToNode(hasTestTag("wander-open"))
        compose.onNodeWithTag("wander-open").performClick()
        assertThat(opened).containsExactly("Hagia_Sophia")
    }

    @Test fun searchingListsHitsAndPickingOneStartsThere() {
        val picked = mutableListOf<String>()
        val h = Harness(WanderUi(centre = sophia, orbit = links.getValue("Hagia_Sophia")))
        show(h, picked = picked)
        compose.onNodeWithTag("wander-search").performTextInput("ada")
        compose.onNodeWithText("Ada Lovelace").assertIsDisplayed().performClick()
        assertThat(picked).containsExactly("Ada_Lovelace")
        assertThat(h.ui.query).isEmpty()
    }

    @Test fun wanderIsHiddenWithoutAnEncyclopedia() {
        show(Harness(WanderUi(available = false)))
        compose.onNodeWithTag("wander").assertDoesNotExist()
    }

    @Test fun initialsSkipBracketsAndKeepTwoLetters() {
        assertThat(initials("Byzantine Empire (330–1453)")).isEqualTo("BE")
        assertThat(initials("Istanbul")).isEqualTo("I")
    }
}
