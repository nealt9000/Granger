package app.granger

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ResearchOpeningTest {
    @Test fun railResearchWhileChatOpenStartsNewChat() {
        // The rail's Research tool opens ResearchRoute() (chat 0), replacing the open chat instead of keeping it.
        val open = researchOpening(ResearchRoute(), researchOnTop = true)
        assertThat(open.route.chatId).isEqualTo(0)
        assertThat(open.replacesChatOnTop).isTrue()
        assertThat(researchOpening(ResearchRoute(), researchOnTop = false).replacesChatOnTop).isFalse()
    }

    @Test fun aChatPickedFromTheChatsSheetIsPushedSoBackReturnsThere() {
        val open = researchOpening(ResearchRoute(chatId = 7), researchOnTop = true, fromChats = true)
        assertThat(open.route.chatId).isEqualTo(7)
        assertThat(open.replacesChatOnTop).isFalse()
    }

    @Test fun plusReplacesTheChatWithAFreshEmptyOne() {
        // + in chat 7: a fresh, empty chat takes its place on top.
        val open = researchOpening(ResearchRoute(), researchOnTop = true)
        assertThat(open.route).isEqualTo(ResearchRoute())
        assertThat(open.replacesChatOnTop).isTrue()
    }

    @Test fun aReadersQuoteIsPushedOverTheReader() {
        // "Ask about this" navigates ResearchRoute.about(...) over the reader; nothing of Ask's is on top to replace.
        assertThat(researchOpening(ResearchRoute(quote = "owls"), researchOnTop = false).replacesChatOnTop).isFalse()
    }
}
