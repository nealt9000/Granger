package app.granger.core.library

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ReadingSessionsTest {
    private val db = inMemoryDatabase()
    private var clock = 1_000L
    private val sessions = RoomReadingSessions(db) { clock++ }

    @After fun close() = db.close()

    @Test fun openingFromSearchAddsATab() = runTest {
        sessions.openTab("p", "Cat", "Cat")
        sessions.openTab("p", "Dog", "Dog")
        assertThat(sessions.railTabs().first().map { it.path }).containsExactly("Dog", "Cat").inOrder()
    }

    @Test fun reopeningTheSameArticleReusesItsTab() = runTest {
        val first = sessions.openTab("p", "Cat", "Cat")
        sessions.openTab("p", "Dog", "Dog")
        val again = sessions.openTab("p", "Cat", "Cat")
        assertThat(again).isEqualTo(first)
        assertThat(sessions.railTabs().first().map { it.path }).containsExactly("Cat", "Dog").inOrder()
        assertThat(sessions.history.first()).hasSize(2)
    }

    @Test fun navigatingWithinATabUpdatesIt() = runTest {
        val id = sessions.openTab("p", "Cat", "Cat")
        sessions.showInTab(id, "p", "Dog", "Dog")
        val tabs = sessions.railTabs().first()
        assertThat(tabs.map { it.id to it.path }).containsExactly(id to "Dog")
        assertThat(sessions.tab(id)?.title).isEqualTo("Dog")
        assertThat(sessions.history.first().map { it.path }).containsExactly("Dog", "Cat").inOrder()
    }

    @Test fun ninthTabDropsOffTheRailButStaysInHistory() = runTest {
        repeat(9) { sessions.openTab("p", "A$it", "A$it") }
        val rail = sessions.railTabs().first()
        assertThat(rail).hasSize(RAIL_LIMIT)
        assertThat(rail.map { it.path }).doesNotContain("A0")
        assertThat(sessions.history.first()).hasSize(9)
    }

    @Test fun closedTabLeavesTheRailButNotHistory() = runTest {
        val id = sessions.openTab("p", "Cat", "Cat")
        val other = sessions.openTab("p", "Dog", "Dog")
        sessions.closeTab(other)
        assertThat(sessions.railTabs().first().map { it.id }).containsExactly(id)
        assertThat(sessions.history.first()).hasSize(2)
        assertThat(sessions.lastTab()?.id).isEqualTo(id)
        // Opening the closed article again makes a fresh tab; the closed one is not revived.
        assertThat(sessions.openTab("p", "Dog", "Dog")).isNotEqualTo(other)
    }

    @Test fun recentSearchesAreNewestFirstAndDeduplicated() = runTest {
        sessions.recordSearch("cats")
        sessions.recordSearch("  dogs ")
        sessions.recordSearch("   ")
        sessions.recordSearch("cats")
        assertThat(sessions.recentSearches().first()).containsExactly("cats", "dogs").inOrder()
    }

    @Test fun recentSearchesAreTrimmedToFifty() = runTest {
        repeat(60) { sessions.recordSearch("q$it") }
        assertThat(db.sessionDao().observeRecentSearches(100).first()).hasSize(50)
        assertThat(sessions.recentSearches(1).first()).containsExactly("q59")
    }

    @Test fun trimIsExactWhenSearchesShareATimestamp() = runTest {
        val frozen = RoomReadingSessions(db) { 5L }
        repeat(60) { frozen.recordSearch("q%02d".format(it)) }
        assertThat(db.sessionDao().observeRecentSearches(100).first()).hasSize(50)
    }

    @Test fun recentSearchRowsCarryTimes() = runTest {
        sessions.recordSearch("cats")
        sessions.recordSearch("dogs")
        assertThat(sessions.recentSearchRows().first())
            .containsExactly(RecentSearch("dogs", 1_001L), RecentSearch("cats", 1_000L)).inOrder()
    }

    @Test fun openTabStartsANewStack() = runTest {
        val id = sessions.openTab("p", "Cat", "Cat")
        assertThat(sessions.tab(id)?.backStack).isEqualTo("Cat")
        assertThat(sessions.popPath(id)).isNull()
    }

    @Test fun pushPathAppendsAndCapsAtFifty() = runTest {
        val id = sessions.openTab("p", "A0", "A0")
        for (i in 1..59) sessions.pushPath(id, "A$i", "Article $i")
        val stack = sessions.tab(id)!!.backStack.split('\n')
        assertThat(stack).hasSize(MAX_BACK_STACK)
        assertThat(stack.first()).isEqualTo("A10")
        assertThat(stack.last()).isEqualTo("A59")
        // The tab shows the newest page, and history has it.
        assertThat(sessions.tab(id)?.path).isEqualTo("A59")
        assertThat(sessions.tab(id)?.title).isEqualTo("Article 59")
        assertThat(sessions.history.first().first().path).isEqualTo("A59")
    }

    @Test fun pushingTheShownPathAgainDoesNotRepeatIt() = runTest {
        // A reload, or the page shown after Back loaded the previous path.
        val id = sessions.openTab("p", "Cat", "Cat")
        sessions.pushPath(id, "Dog", "Dog")
        sessions.pushPath(id, "Dog", "Dog")
        assertThat(sessions.tab(id)?.backStack).isEqualTo("Cat\nDog")
    }

    @Test fun popPathReturnsPreviousAndShortens() = runTest {
        val id = sessions.openTab("p", "Cat", "Cat")
        sessions.pushPath(id, "Dog", "Dog")
        sessions.pushPath(id, "Eel", "Eel")
        assertThat(sessions.popPath(id)).isEqualTo("Dog")
        assertThat(sessions.tab(id)?.backStack).isEqualTo("Cat\nDog")
        // The page shown after Back pushes the path it went back to; that isn't added twice.
        sessions.pushPath(id, "Dog", "Dog")
        assertThat(sessions.popPath(id)).isEqualTo("Cat")
        assertThat(sessions.tab(id)?.backStack).isEqualTo("Cat")
    }

    @Test fun popAtFirstPageIsNull() = runTest {
        val id = sessions.openTab("p", "Cat", "Cat")
        assertThat(sessions.popPath(id)).isNull()
        assertThat(sessions.tab(id)?.backStack).isEqualTo("Cat")
        assertThat(sessions.popPath(404L)).isNull()
    }

    @Test fun reopeningATabKeepsItsStack() = runTest {
        val id = sessions.openTab("p", "Cat", "Cat")
        sessions.pushPath(id, "Dog", "Dog")
        assertThat(sessions.openTab("p", "Dog", "Dog")).isEqualTo(id)
        assertThat(sessions.popPath(id)).isEqualTo("Cat")
    }

    @Test fun aTabFromBeforeVersion9StartsItsStackAtItsPage() = runTest {
        // A migrated tab has an empty stack: the first page shown starts it.
        val id = db.sessionDao().insertTab(ReadingTab(0, "p", "Cat", "Cat", 1L, onRail = true))
        assertThat(sessions.popPath(id)).isNull()
        sessions.pushPath(id, "Cat", "Cat")
        sessions.pushPath(id, "Dog", "Dog")
        assertThat(sessions.popPath(id)).isEqualTo("Cat")
    }

    @Test fun forgetSearchDeletesOnlyThatQuery() = runTest {
        sessions.recordSearch("cats")
        sessions.recordSearch("dogs")
        sessions.forgetSearch(" cats ")
        sessions.forgetSearch("never searched")
        assertThat(sessions.recentSearches().first()).containsExactly("dogs")
    }
}
