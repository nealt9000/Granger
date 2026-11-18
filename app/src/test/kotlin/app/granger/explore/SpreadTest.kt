package app.granger.explore

import androidx.compose.foundation.layout.Column
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import app.granger.theme.GrangerTheme
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Plan EX round 2, G0: the shared pieces of a spread. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w320dp-h1200dp")
class SpreadTest {
    @get:Rule val compose = createComposeRule()
    private val calls = mutableListOf<String>()

    private fun photo(key: String, ratio: Float = 1.4f) = Plate(key, key, picture = "/none/$key.jpg", ratio = ratio)
    private fun word(key: String) = Plate(key, key)
    private fun kinds(plates: List<Plate>, complete: Boolean = true) = plateRows(plates, complete).map { it.kind }

    @Test fun threesAlternateALedRowWithThreeAcrossAndTheLargeSideSwaps() {
        val rows = plateRows((1..12).map { photo("p$it") })
        assertThat(rows.map { it.kind }).containsExactly(PlateRowKind.LEAD, PlateRowKind.THREE, PlateRowKind.LEAD_MIRROR, PlateRowKind.THREE).inOrder()
        // Every plate once, in order: best known first stays best known first.
        assertThat(rows.flatMap { it.plates }.map { it.key }).containsExactlyElementsIn((1..12).map { "p$it" }).inOrder()
    }

    @Test fun aLedRowIsLedByItsFirstPictureThatIsNotUprightAPhotographBeforeAnEmblem() {
        val portrait = photo("portrait", 0.7f)
        val flag = photo("flag", 1.5f).copy(whole = true)
        val ship = photo("ship")
        assertThat(plateRows(listOf(portrait, flag, ship)).single()).isEqualTo(PlateRow(PlateRowKind.LEAD, listOf(ship, portrait, flag)))
        assertThat(plateRows(listOf(portrait, flag, word("noun"))).single()).isEqualTo(PlateRow(PlateRowKind.LEAD, listOf(flag, portrait, word("noun"))))
        // Nothing can lead: three across.
        assertThat(kinds(listOf(portrait, word("a"), photo("tall", 0.6f)))).containsExactly(PlateRowKind.THREE_TALL)
    }

    @Test fun portraitsStandUprightAndWordsGoSideBySide() {
        assertThat(kinds(listOf(photo("a", 0.75f), photo("b", 0.8f), photo("c")))).containsExactly(PlateRowKind.THREE_TALL)
        // Words & grammar: two words, three portraits, three words, three portraits.
        val thin = listOf(word("Noun"), word("Adjective")) + (1..3).map { photo("p$it", 0.75f) } + (1..3).map { word("w$it") } + (4..6).map { photo("p$it", 0.75f) }
        assertThat(kinds(thin)).containsExactly(PlateRowKind.PAIR, PlateRowKind.THREE_TALL, PlateRowKind.THREE, PlateRowKind.THREE_TALL).inOrder()
    }

    @Test fun leftoversPairOrRunTheWidthAndWaitWhileMoreIsLoading() {
        assertThat(kinds(listOf(photo("a")))).containsExactly(PlateRowKind.SINGLE)
        assertThat(kinds(listOf(photo("a"), photo("b")))).containsExactly(PlateRowKind.PAIR)
        assertThat(kinds((1..4).map { photo("p$it") })).containsExactly(PlateRowKind.PAIR, PlateRowKind.PAIR).inOrder()
        assertThat(kinds((1..5).map { photo("p$it") })).containsExactly(PlateRowKind.LEAD, PlateRowKind.PAIR).inOrder()
        assertThat(kinds((1..7).map { photo("p$it") })).containsExactly(PlateRowKind.LEAD, PlateRowKind.PAIR, PlateRowKind.PAIR).inOrder()
        // More to come: the rows already cut are the rows that stay.
        val loading = plateRows((1..5).map { photo("p$it") }, complete = false)
        assertThat(loading.map { it.kind }).containsExactly(PlateRowKind.LEAD)
        assertThat(plateRows((1..6).map { photo("p$it") }).first()).isEqualTo(loading.first())
        assertThat(plateRows(emptyList())).isEmpty()
    }

    @Test fun anOpeningGroupIsThreeTwoOrOne() {
        val p = (1..4).map { photo("p$it") }
        assertThat(openingRow(p)).isEqualTo(PlateRow(PlateRowKind.OPENING, p.take(3)))
        assertThat(openingRow(p.take(2))?.kind).isEqualTo(PlateRowKind.PAIR)
        assertThat(openingRow(p.take(1))?.kind).isEqualTo(PlateRowKind.SINGLE)
        assertThat(openingRow(emptyList())).isNull()
    }

    @Test fun theTrailFoldsItsMiddlePastThreePlaces() {
        assertThat(trailShown(1)).containsExactly(0)
        assertThat(trailShown(3)).containsExactly(0, 1, 2).inOrder()
        assertThat(trailShown(4)).containsExactly(0, TRAIL_GAP, 2, 3).inOrder()
        assertThat(trailShown(6)).containsExactly(0, TRAIL_GAP, 4, 5).inOrder()
    }

    @Test fun eachPlateIsOneButtonWithItsTitleAndNumbersAreNotRead() {
        val plates = listOf(photo("Car"), photo("Train").copy(sub = "1804"), word("Bicycle").copy(blurb = "A bicycle is a pedal-driven vehicle."))
        compose.setContent {
            GrangerTheme(night = false) {
                Column {
                    PlateRowView(plateRows(plates).single(), onOpen = { calls += it.key })
                    PlateRowView(openingRow(plates)!!, onOpen = { calls += "opening:" + it.key }, firstNumber = 1)
                }
            }
        }
        // One node each per row, by its title (and dates); nothing else of the plate is in the tree for TalkBack.
        compose.onAllNodesWithContentDescription("Car").assertCountEquals(2)
        compose.onAllNodesWithContentDescription("Train, 1804").assertCountEquals(2)
        compose.onAllNodesWithContentDescription("Bicycle").assertCountEquals(2)
        compose.onAllNodesWithText("A bicycle is a pedal-driven vehicle.").assertCountEquals(0)
        compose.onAllNodesWithText("1").assertCountEquals(0)
        compose.onAllNodesWithTag(PlateTags.NUMBER, useUnmergedTree = true).assertCountEquals(3)
        // The smallest plate (one of the two stacked) is still a full touch target.
        for (node in listOf(compose.onAllNodesWithContentDescription("Train, 1804")[0], compose.onAllNodesWithContentDescription("Bicycle")[0])) {
            node.assertHasClickAction().assertHeightIsAtLeast(48.dp).assertWidthIsAtLeast(48.dp)
        }
        compose.onAllNodesWithContentDescription("Train, 1804")[0].performClick()
        compose.onAllNodesWithContentDescription("Car")[1].performClick()
        assertThat(calls).containsExactly("Train", "opening:Car").inOrder()
    }

    @Test fun theTrailLeadsBackAndItsFoldOpensTheWholeOfIt() {
        val steps = listOf("Explore", "Technology", "Cars, planes & ships").map { s -> TrailStep(s) { calls += s } }
        compose.setContent { GrangerTheme(night = false) { Trail(steps, here = "Airplane") } }
        compose.onNodeWithContentDescription("You are here: Airplane").assertExists()
        compose.onNodeWithText("Technology").assertDoesNotExist()
        compose.onNodeWithText("Cars, planes & ships").assertHasClickAction().assertHeightIsAtLeast(48.dp).performClick()
        compose.onNodeWithContentDescription("Show the whole trail").assertHeightIsAtLeast(48.dp).performClick()
        compose.onNodeWithText("Technology").performClick()
        assertThat(calls).containsExactly("Cars, planes & ships", "Technology").inOrder()
    }

    @Test fun aDoorReadsAsWhatItIsAndWhereItLeads() {
        compose.setContent {
            GrangerTheme(night = false) {
                Column {
                    DoorsBar(DoorKind.ROOM, Door("The internet") { calls += "before" }, Door("Spaceflight") { calls += "after" }, position = 3, total = 8)
                    DoorsBar(DoorKind.TOPIC, null, Door("Ship") { calls += "ship" }, position = 1, total = 1_882)
                }
            }
        }
        compose.onNodeWithContentDescription("The room before, The internet").assertHasClickAction().assertHeightIsAtLeast(48.dp).performClick()
        compose.onNodeWithContentDescription("The room after, Spaceflight").performClick()
        compose.onNodeWithContentDescription("Room 3\u00A0of\u00A08").assertExists()
        compose.onNodeWithContentDescription("1 of 1,882").assertExists()
        // The first topic has no door before it.
        compose.onAllNodesWithTag(SpreadTags.DOOR_BEFORE).assertCountEquals(1)
        compose.onNodeWithContentDescription("The topic after, Ship").performClick()
        assertThat(calls).containsExactly("before", "after", "ship").inOrder()
    }

    @Test fun waysOnAndRoomsSeenAreOneThingEachToTalkBack() {
        compose.setContent {
            GrangerTheme(night = false) {
                Column {
                    WaysOn("Three ways on", listOf(WayOn("Close in meaning", "Helicopter") { calls += "helicopter" }, WayOn("Anywhere in this room", "Wander") { calls += "wander" }))
                    RoomsSeen("Rooms you have seen", 14, 108) { calls += "seen" }
                    Captions(listOf("A United Airlines Boeing 777.", "The Wright Flyer."))
                }
            }
        }
        compose.onNodeWithTag(SpreadTags.way(0)).assertHasClickAction().assertHeightIsAtLeast(56.dp).performClick()
        compose.onNodeWithContentDescription("Rooms you have seen, 14 of 108").assertHeightIsAtLeast(48.dp).performClick()
        compose.onNodeWithText("The Wright Flyer.", useUnmergedTree = true).assertExists()
        assertThat(calls).containsExactly("helicopter", "seen").inOrder()
    }
}
