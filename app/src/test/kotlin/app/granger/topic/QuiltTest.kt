package app.granger.topic

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import app.granger.core.library.Facet
import app.granger.core.library.TimeKind
import app.granger.core.library.TimeMark
import app.granger.theme.GrangerTheme
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.flowOf
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w360dp-h1400dp")
class QuiltTest {
    @get:Rule val compose = createComposeRule()

    private fun a(title: String) = TopicArticle("w", title.replace(' ', '_'), title, "", null)
    private fun life(title: String, y0: Long, y1: Long) = TimeMark(1, "w", title.replace(' ', '_'), title, TimeKind.LIFE, y0, 0, y1, null, 9, emptySet(), 1)

    // Airplane: five kinds in two families, two people, a story with two linked steps, a chapter of three, one close.
    private val broad = BroadTopic(
        under = 60,
        groups = listOf(BroadGroup("Jets", 3), BroadGroup("Gliders", 2)),
        families = listOf(
            BroadFamily("Jets", listOf(a("Boeing 747").copy(picture = "pic:747")), listOf(a("Boeing 747"), a("Concorde"), a("F-16"))),
            BroadFamily("Gliders", emptyList(), listOf(a("Hang glider"), a("Sailplane"))),
        ),
        story = BroadStory("History", listOf(
            StoryEntry(1903, "The Wright Flyer", "fig:flyer", null), StoryEntry(1914, "World War I", null, a("World War I")), StoryEntry(1940, "Battle of Britain", null, a("Battle of Britain")),
        )),
        people = listOf(BroadPerson(a("Charles Lindbergh"), life("Charles Lindbergh", 1902, 1974)), BroadPerson(a("Chuck Yeager"), null)),
        chapters = listOf(BroadChapter("Propulsion", listOf(a("Engine"), a("Propeller"), a("Concorde")), null, null)),
    )
    private val airplane = Topic("w", "Airplane", header = TopicHeader("Airplane", "A fixed-wing aircraft.", null), broad = broad,
        related = listOf(a("Helicopter")), relatedBooks = emptyList(), done = true)
    private val years: (TimeMark) -> String = { "${it.y0} – ${it.y1}" }

    @Test fun aTopicsGroupsAreWhatItsPageHoldsAndWhatStandsRoundItLargestFirst() {
        val around = QuiltAround(leads = listOf(a("Aviation")), rooms = listOf(Facet("technology", "transport", "Cars, planes & ships", "Autos")))
        val groups = Quilts.groups(airplane, around, "en", years)
        assertThat(groups.map { it.kind to it.plates.size }).containsExactly(
            QuiltKind.KINDS to 5, QuiltKind.CHAPTER to 3, QuiltKind.PEOPLE to 2, QuiltKind.STORY to 2, QuiltKind.CLOSE to 1, QuiltKind.LEADS to 1, QuiltKind.ROOMS to 1,
        ).inOrder()
        // A kind read as a plate keeps its picture; a person carries their years; a chapter its own name; a room is a room.
        assertThat(groups[0].plates.first().picture).isEqualTo("pic:747")
        assertThat(groups[2].plates.first().sub).isEqualTo("1902 – 1974")
        assertThat(groups[1].name).isEqualTo("Propulsion")
        assertThat(groups.last().plates.single().room?.id).isEqualTo("transport")
        assertThat(groups.last().plates.single().isArticle).isFalse()
        // Concorde is a kind and a link: one thing.
        assertThat(Quilts.connections(groups)).isEqualTo(14)
        // A topic that isn't broad has what stands round it only; nothing gives no groups.
        assertThat(Quilts.groups(Topic("w", "Helicopter", related = listOf(a("Airplane"), a("Helicopter"))), null, "en", years).map { it.kind }).containsExactly(QuiltKind.CLOSE)
        assertThat(Quilts.groups(Topic("w", "Helicopter"), null, "en", years)).isEmpty()
    }

    @Test fun theQuiltsPlatesTakeTurnsByGroupAndStandRoundTheMiddle() {
        val groups = Quilts.groups(airplane, QuiltAround(leads = listOf(a("Aviation"))), "en", years)
        val cells = Quilts.cells(groups)
        // Turns of three, two, three, two, two, then round again: Concorde stands once, with the kinds.
        assertThat(cells.map { it.plate.title }).containsExactly(
            "Boeing 747", "Concorde", "F-16", "Engine", "Propeller", "Charles Lindbergh", "Chuck Yeager", "World War I", "Battle of Britain", "Helicopter", "Hang glider", "Aviation",
        ).inOrder()
        assertThat(cells.filter { it.tab }.map { it.plate.title }).containsExactly("Boeing 747", "Engine", "Charles Lindbergh", "World War I", "Helicopter", "Hang glider", "Aviation").inOrder()
        val l = Quilts.layout(cells)
        assertThat(listOf(l.above.size, l.beside.size, l.below.size, l.foot.size)).containsExactly(3, 2, 3, 4).inOrder()
        // Few connections: the middle plate runs the width, and no row is cut short of what there is.
        val few = Quilts.layout(cells.take(4))
        assertThat(listOf(few.above.size, few.beside.size, few.below.size, few.foot.size)).containsExactly(3, 0, 1, 0).inOrder()
        assertThat(Quilts.cells(emptyList())).isEmpty()
    }

    @Test fun touchingAPlateReFormsTheQuiltTheThreadGoesBackAndAHeldPlateShowsItsLabel() {
        val lindbergh = Topic("w", "Charles_Lindbergh", header = TopicHeader("Charles Lindbergh", "An American aviator.", null),
            related = listOf(a("Chuck Yeager"), a("Amelia Earhart")), relatedBooks = emptyList(), done = true)
        val events = mutableListOf<String>()
        compose.setContent {
            GrangerTheme {
                TopicQuilt(
                    airplane,
                    topicOf = { _, path -> flowOf(if (path == "Charles_Lindbergh") lindbergh else Topic("w", path, done = true)) },
                    around = { _, path, plates ->
                        when {
                            path == "Airplane" -> QuiltAround(more = if (plates.isEmpty()) emptyMap() else mapOf("w/World_War_I" to 102))
                            path == "Battle_of_Britain" -> QuiltAround(
                                leads = listOf(a("Airplane"), a("World War II"), a("Radar")), rooms = listOf(Facet("history", "war", "Wars & battles", "Guerras")),
                                card = a("Battle of Britain").copy(blurb = "A military campaign of the Second World War."), mark = TimeMark(2, "w", "Battle_of_Britain", "Battle of Britain", TimeKind.POINT, 1940, 0, null, null, 9, emptySet(), 1),
                            )
                            else -> QuiltAround()
                        }
                    },
                    cardOf = { _, _ -> null },
                    actions = QuiltActions(onOpenArticle = { _, path, _ -> events += "read:$path" }, onOpenBookAt = { _, _ -> }, onOpenPassage = { _, _ -> }, onClose = { events += "close" }),
                )
            }
        }
        // One finding says what the quilt shows; a plate says its group and how many other topics lead to it.
        compose.onNodeWithTag(QuiltTags.FINDING).assertExists()
        compose.onNodeWithText("12 connections in 5 groups. Kinds is the largest, with 5.").assertExists()
        compose.onNodeWithContentDescription("Boeing 747, Kinds").assertHasClickAction()
        compose.onNodeWithContentDescription("World War I, History, 102 more topics lead here").assertExists()
        // To TalkBack a plate's label is an action.
        val actions = compose.onNodeWithTag(QuiltTags.plate("w/Battle_of_Britain")).fetchSemanticsNode().config.getOrNull(SemanticsActions.CustomActions)
        assertThat(actions!!.map { it.label }).containsExactly("Show its label")
        // Hold a plate: where the link was found, its date and room, its sentence, what else leads to it.
        compose.onNodeWithTag(QuiltTags.plate("w/Battle_of_Britain")).performTouchInput { longClick() }
        compose.onNodeWithTag(QuiltTags.LABEL).assertExists()
        compose.onNodeWithContentDescription("From the History chapter of Airplane").assertExists()
        compose.onNodeWithText("1940 · Wars & battles").assertExists()
        compose.onNodeWithText("A military campaign of the Second World War.").assertExists()
        compose.onNodeWithContentDescription("2 more topics lead here").assertExists()
        compose.onNodeWithText("Read the article").performClick()
        compose.onNodeWithTag(QuiltTags.LABEL).assertDoesNotExist()
        // Every group in full is one tap away, as a list.
        compose.onNodeWithTag(QuiltTags.group(0)).performScrollTo().performClick()
        compose.onNodeWithTag(QuiltTags.line("w/Sailplane")).performScrollTo().assertHasClickAction()
        // Touch a plate: the quilt re-forms round it, the thread keeps the walk, and the plates that were on the last quilt are marked.
        compose.onNodeWithTag(QuiltTags.plate("w/Charles_Lindbergh")).performScrollTo().performClick()
        compose.onNodeWithContentDescription("Around Charles Lindbergh").assertExists()
        compose.onNodeWithText("1 of these plates stood round Airplane too.").assertExists()
        compose.onNodeWithContentDescription("Chuck Yeager, Close kin, also on the last quilt").assertExists()
        compose.onNodeWithContentDescription("Amelia Earhart, Close kin").assertExists()
        compose.onNodeWithTag(QuiltTags.READ).performScrollTo().performClick()
        // A step in the thread goes back to it.
        compose.onNodeWithTag(QuiltTags.step(0)).performScrollTo().performClick()
        compose.onNodeWithContentDescription("Around Airplane").assertExists()
        compose.onNodeWithTag(QuiltTags.step(0)).assertDoesNotExist()
        // The middle plate of the first quilt is the page it was opened from.
        compose.onNodeWithTag(QuiltTags.CENTRE).performClick()
        assertThat(events).containsExactly("read:Battle_of_Britain", "read:Charles_Lindbergh", "close").inOrder()
    }
}
