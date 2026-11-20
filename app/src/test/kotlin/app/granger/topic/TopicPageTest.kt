package app.granger.topic

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import app.granger.core.library.BookMention
import app.granger.core.library.Lane
import app.granger.core.library.TimeKind
import app.granger.core.library.TimeMark
import app.granger.ebook.ArticleFooter
import app.granger.ebook.InYourBook
import app.granger.ebook.LibraryBook
import app.granger.theme.GrangerTheme
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TopicPageTest {
    @get:Rule val compose = createComposeRule()

    private fun life(qid: Long, path: String, y0: Int, y1: Int) =
        TimeMark(qid, "w", path, path.replace('_', ' '), TimeKind.LIFE, y0.toLong(), 0, y1.toLong(), null, 9, setOf(Lane.PEOPLE), 100)

    private val napoleon = life(517, "Napoleon", 1769, 1821)
    private val warAndPeace = LibraryBook("book:granger:gc-war-and-peace", "War and Peace", null)
    private val mention = BookMention("c1.xhtml", 10, "Napoleon", "In the midst of a conversation about ", 517, 1)

    private val full = Topic(
        "w", "Napoleon",
        header = TopicHeader("Napoleon", "Emperor of the French.", null),
        time = TopicTimeView(napoleon, listOf(life(1, "Goethe", 1749, 1832)), listOf(TimeMark(2, "w", "French_Revolution", "French Revolution", TimeKind.SPAN, 1789, 0, 1799, null, 9, emptySet(), 50))),
        other = TopicOther("es", "es", "Napoleón_Bonaparte", "Napoleón Bonaparte"),
        books = ArticleFooter(emptyList(), listOf(InYourBook(warAndPeace, 111, listOf(mention))), emptyList(), inBooksTotal = 74),
        related = listOf(TopicArticle("w", "Napoleonic_Wars", "Napoleonic Wars", "A series of conflicts.", null)),
        relatedBooks = emptyList(),
        done = true,
    )

    private val events = mutableListOf<String>()

    private fun show(topic: Topic, inTime: Boolean = true) = compose.setContent {
        GrangerTheme {
            TopicPage(
                topic = topic, packTitle = "Wikipedia",
                onBack = { events += "back" },
                onReadFull = { events += "full" },
                onOther = { events += "other:${it.path}" },
                onOpenArticle = { _, path, _ -> events += "article:$path" },
                onOpenPassage = { id, m -> events += "passage:$id:${m.href}" },
                onOpenBookAt = { id, ch -> events += "book:$id:$ch" },
                onAllBooks = { events += "all" },
                onShowInTime = if (inTime) ({ events += "time" }) else null,
                onOpenPlace = { subject, room -> events += "place:$subject:$room" },
            )
        }
    }

    @Test fun everySectionShowsWhenItHasSomething() {
        show(full)
        compose.onNodeWithText("Napoleon", useUnmergedTree = true).assertExists()
        compose.onNodeWithText("1769 – 1821 · aged 52").assertExists()   // no days in this mark, so years only
        compose.onNodeWithTag(TopicPageTags.READ_FULL).performClick()
        compose.onNodeWithTag(TopicPageTags.OTHER).performClick()
        val list = compose.onNodeWithTag(TopicPageTags.PAGE).let { compose.onNode(androidx.compose.ui.test.hasScrollAction()) }
        list.performScrollToNode(hasTestTag(TopicPageTags.TIME))
        compose.onNodeWithContentDescription("Goethe", substring = true).performClick()
        list.performScrollToNode(hasTestTag(TopicPageTags.BOOKS))
        compose.onNodeWithText("Named in 74 of your books").assertExists()
        compose.onNodeWithText("All 74").performClick()
        list.performScrollToNode(hasTestTag(TopicPageTags.RELATED))
        compose.onNodeWithText("Napoleonic Wars").performClick()
        assertThat(events).containsExactly("full", "other:Napoleón_Bonaparte", "article:Goethe", "all", "article:Napoleonic_Wars").inOrder()
    }

    @Test fun aThinPageShowsOnlyWhatItHas() {
        show(Topic("w", "Photosynthesis", header = TopicHeader("Photosynthesis", "A biological process.", null), books = ArticleFooter.NONE, related = emptyList(), relatedBooks = emptyList(), done = true))
        compose.onNodeWithText("Photosynthesis").assertExists()
        compose.onAllNodes(hasTestTag(TopicPageTags.TIME)).assertCountEquals(0)
        compose.onAllNodes(hasTestTag(TopicPageTags.BOOKS)).assertCountEquals(0)
        compose.onAllNodes(hasTestTag(TopicPageTags.RELATED)).assertCountEquals(0)
        compose.onAllNodes(hasTestTag(TopicPageTags.OTHER)).assertCountEquals(0)
    }

    @Test fun beforeTheHeaderArrivesThePageShowsItsTitleFromThePath() {
        show(Topic("w", "Jane_Austen"))
        compose.onNodeWithText("Jane Austen").assertExists()
        compose.onNodeWithTag(TopicPageTags.READ_FULL).assertExists()
    }

    @Test fun aTopicsRoomShowsAsATrailAndMoreInIt() {
        // Plan O6: Explore › Places › Lakes at the top, and "More in Lakes" with All.
        val lakes = app.granger.core.library.Facet("places", "lakes", "Lakes", "Lagos")
        val more = listOf(TopicArticle("en-top", "Lake_Chad", "Lake Chad", "A lake.", null), TopicArticle("en-top", "Lake_Victoria", "Lake Victoria", "", null))
        show(Topic("wikipedia_en_online", "Lake_Turkana", header = TopicHeader("Lake Turkana", "A lake.", null), place = TopicPlace("places", lakes, more), done = true))
        compose.onNodeWithTag(TopicPageTags.TRAIL).assertExists()
        compose.onNodeWithText("Lakes").performClick()
        compose.onNodeWithText("More in Lakes").performScrollTo().assertExists()
        compose.onNodeWithText("Lake Chad").performClick()
        compose.onNodeWithText("All").performClick()
        assertThat(events).containsExactly("place:places:lakes", "article:Lake_Chad", "place:places:lakes").inOrder()
    }

    private class Marks(val states: Map<String, app.granger.online.Availability>) : app.granger.online.MarkSource {
        override val version = kotlinx.coroutines.flow.MutableStateFlow(0)
        override suspend fun of(packId: String, path: String) = states[path] ?: app.granger.online.Availability.Online
    }

    private class Reach(start: app.granger.online.ReachState) : app.granger.online.ReachControl {
        override val state = kotlinx.coroutines.flow.MutableStateFlow(start)
        override fun setLocalOnly(on: Boolean) {}
    }

    private fun showWithReach(topic: Topic, reach: app.granger.online.ReachState) = compose.setContent {
        GrangerTheme {
            androidx.compose.runtime.CompositionLocalProvider(
                app.granger.online.LocalMarks provides Marks(mapOf("Lake_Victoria" to app.granger.online.Availability.Saved, "Goethe" to app.granger.online.Availability.OnPhone(true))),
                app.granger.online.LocalReach provides Reach(reach),
            ) {
                TopicPage(
                    topic = topic, packTitle = "Wikipedia", onBack = {}, onReadFull = {}, onOther = {},
                    onOpenArticle = { _, path, _ -> events += "article:$path" },
                    onOpenPassage = { _, _ -> }, onOpenBookAt = { _, _ -> }, onAllBooks = {}, onShowInTime = null, onOpenPlace = { _, _ -> },
                )
            }
        }
    }

    private val lakePage = Topic(
        "wikipedia_en_online", "Lake_Turkana", header = TopicHeader("Lake Turkana", "A lake.", null),
        place = TopicPlace("places", app.granger.core.library.Facet("places", "lakes", "Lakes", "Lagos"), listOf(
            TopicArticle("wikipedia_en_online", "Lake_Chad", "Lake Chad", "A lake.", null),
            TopicArticle("wikipedia_en_online", "Lake_Victoria", "Lake Victoria", "", null),
        )),
        related = listOf(TopicArticle("wikipedia_en_online", "Rift_Valley", "Rift Valley", "", null)),
        time = full.time, relatedBooks = emptyList(), books = ArticleFooter.NONE, done = true,
    )

    @Test fun offlineTilesThatWontOpenAreGreyedAndStillTapThrough() {
        // Plan O7 call 1: Lake Chad and Rift Valley are online only; Lake Victoria is saved, Goethe on the phone.
        showWithReach(lakePage, app.granger.online.ReachState.LOCAL_ONLY)
        compose.waitForIdle()
        val list = compose.onNode(androidx.compose.ui.test.hasScrollAction())
        list.performScrollToNode(hasTestTag(TopicPageTags.tile("Lake_Chad")))
        compose.onNode(hasTestTag(app.granger.online.MarkTags.NEEDS_INTERNET) and androidx.compose.ui.test.hasAnyAncestor(hasTestTag(TopicPageTags.tile("Lake_Chad"))), useUnmergedTree = true).assertExists()
        compose.onAllNodes(hasTestTag(app.granger.online.MarkTags.NEEDS_INTERNET) and androidx.compose.ui.test.hasAnyAncestor(hasTestTag(TopicPageTags.tile("Lake_Victoria"))), useUnmergedTree = true).assertCountEquals(0)
        compose.onNodeWithTag(TopicPageTags.tile("Lake_Chad")).performClick()
        list.performScrollToNode(hasTestTag(TopicPageTags.tile("Rift_Valley")))
        compose.onNode(hasTestTag(app.granger.online.MarkTags.NEEDS_INTERNET) and androidx.compose.ui.test.hasAnyAncestor(hasTestTag(TopicPageTags.tile("Rift_Valley"))), useUnmergedTree = true).assertExists()
        // The time card: French Revolution (an event) greys; Goethe (on the phone) doesn't.
        list.performScrollToNode(hasTestTag(TopicPageTags.TIME))
        compose.onAllNodes(hasTestTag(TopicPageTags.GREYED), useUnmergedTree = true).assertCountEquals(1)
        compose.onNodeWithText("Needs internet", substring = true, useUnmergedTree = true).assertExists()
        assertThat(events).containsExactly("article:Lake_Chad")
    }

    @Test fun onlineNoTileGreys() {
        showWithReach(lakePage, app.granger.online.ReachState.ONLINE)
        compose.waitForIdle()
        compose.onAllNodes(hasTestTag(app.granger.online.MarkTags.NEEDS_INTERNET), useUnmergedTree = true).assertCountEquals(0)
        compose.onAllNodes(hasTestTag(TopicPageTags.GREYED), useUnmergedTree = true).assertCountEquals(0)
    }
}
