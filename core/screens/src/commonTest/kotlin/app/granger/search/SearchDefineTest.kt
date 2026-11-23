package app.granger.search

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The Define card's rules, shared with the iPhone (Plan I7 Task 7b); Android's SearchResultsPageTest checks them too. */
class SearchDefineTest {
    @Test fun oneWordOfLettersIsLookedUp() {
        assertTrue(SearchDefine.isOneWord("café"))
        assertTrue(SearchDefine.isOneWord("x-ray"))
        assertTrue(SearchDefine.isOneWord("o’clock"))
        assertFalse(SearchDefine.isOneWord("a"))
        assertFalse(SearchDefine.isOneWord("1984"))
        assertFalse(SearchDefine.isOneWord("two words"))
    }

    @Test fun theCardLeavesOutTheEtymologyUnlessItIsAll() {
        assertEquals("Noun: a happy accident 1. Luck.", SearchDefine.text("Etymology: From Latin.\nNoun: a happy accident\n1. Luck."))
        assertEquals("Etimología: Del latín.", SearchDefine.text("Etimología: Del latín."))
        assertEquals("Verbo: Levantarse al alba.", SearchDefine.text("Etimologia: Del castellano.\n\n  Verbo: Levantarse al alba.  "))
    }
}
