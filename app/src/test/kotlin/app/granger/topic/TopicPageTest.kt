package app.granger.topic

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
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
}
