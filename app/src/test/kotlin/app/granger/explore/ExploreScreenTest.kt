package app.granger.explore

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import app.granger.pack
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w360dp-h1600dp")
class ExploreScreenTest {
    @get:Rule val compose = createComposeRule()
    private val calls = mutableListOf<String>()

    private val actions = ExploreActions(
        onOpenSubject = { calls += "subject:${it.id}" },
        onOpenArticle = { p, path, _ -> calls += "article:$p/$path" },
        onOpenTimeline = { p, path -> calls += "timeline:$p/$path" },
        onOpenSources = { calls += "sources" },
        onOpenPack = { calls += "pack:$it" },
        onGetMore = { calls += "get" },
    )

    private fun a(path: String) = ExploreArticle("top", path, titleOf(path))

    @Test fun theDoorsAreBySizeAndAPackOnlySubjectNamesItsPacks() {
        val cooking = pack("c", "based.cooking")
        val doors = subjectDoors(
            mapOf("history" to 3_531, "science" to 5_233, "games" to 0),
            { s -> listOf(a(if (s == Subject.SCIENCE) "Physics" else "World_War_II")) },
            listOf(cooking to "food", pack("r", "Wiktionary") to "reference", pack("t", "Top") to null),
        )
        assertThat(doors.map { it.subject }).containsExactly(Subject.SCIENCE, Subject.HISTORY, Subject.FOOD).inOrder()
        assertThat(doors.last().packs).containsExactly(cooking)
        assertThat(doors.last().examples).isEmpty()
        assertThat(Subject.ofDomain("thought")).isEqualTo(Subject.RELIGION)
        assertThat(Subject.ofDomain("reference")).isNull()
    }

    @Test fun theLandingShowsDoorsErasAndSourcesAndEachLeadsOn() {
        val ui = ExploreUi(
            articles = 77_019,
            doors = listOf(
                SubjectDoor(Subject.SCIENCE, 5_233, listOf(a("Physics"), a("DNA"), a("Moon")), emptyList()),
                SubjectDoor(Subject.FOOD, 0, emptyList(), listOf(pack("c", "based.cooking"))),
            ),
            eras = listOf(EraRow(Era.MIDDLE_AGES, 1_159, listOf(a("Islam"), a("Dante_Alighieri"))), EraRow(Era.RECENT, 14_970, listOf(a("The_Beatles")))),
            sources = listOf("based.cooking", "Best of Wikipedia"),
        )
        compose.setContent { ExploreContent(ui, actions) }
        compose.onNodeWithText("77,019 articles · 2 packs").assertExists()
        compose.onNodeWithText("Science & maths").assertExists()
        compose.onNodeWithText("5,233 topics").assertExists()
        compose.onNodeWithText("Physics, DNA, Moon").assertExists()
        compose.onNodeWithText("Food & farming").assertExists()
        compose.onNodeWithTag(ExploreTags.door(Subject.SCIENCE)).performClick()
        compose.onNodeWithText("Middle Ages").assertExists()
        compose.onNodeWithText("Islam · Dante Alighieri").assertExists()
        compose.onNodeWithTag(ExploreTags.era(Era.MIDDLE_AGES)).performScrollTo().performClick()
        compose.onNodeWithText("Open the Timeline").performScrollTo().performClick()
        compose.onNodeWithText("All 2 packs").assertExists()
        compose.onNodeWithTag(ExploreTags.SOURCES).performScrollTo().performClick()
        compose.onNodeWithTag(ExploreTags.NO_SUBJECTS).assertDoesNotExist()
        assertThat(calls).containsExactly("subject:science", "timeline:top/Islam", "timeline:null/null", "sources").inOrder()
    }

    @Test fun withoutSubjectsItSaysHowToGetThem() {
        compose.setContent { ExploreContent(ExploreUi(0, emptyList(), emptyList(), listOf("based.cooking")), actions) }
        compose.onNodeWithTag(ExploreTags.NO_SUBJECTS).assertExists()
        compose.onNodeWithText("Get a Wikipedia").performClick()
        assertThat(calls).containsExactly("get")
    }

    @Test fun aSubjectListsItsPacksThenItsArticlesAndPagesOn() {
        var more = 0
        val ui = SubjectUi(Subject.FOOD, 658, listOf(a("Pizza"), a("Coffee")), listOf(pack("c", "based.cooking")), more = true)
        compose.setContent { SubjectContent(ui, actions) { more++ } }
        compose.onNodeWithText("658 topics").assertExists()
        compose.onNodeWithText("based.cooking").performClick()
        compose.onNodeWithText("Coffee").performClick()
        compose.onNodeWithText("Show more").performClick()
        assertThat(calls).containsExactly("pack:c", "article:top/Coffee").inOrder()
        assertThat(more).isEqualTo(1)
    }
}
