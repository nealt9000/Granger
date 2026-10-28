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

    @Test fun aChatPickedOnTheLandingIsPushedSoBackReturnsThere() {
        val open = researchOpening(ResearchRoute(chatId = 7), researchOnTop = true, fromLanding = true)
        assertThat(open.route.chatId).isEqualTo(7)
        assertThat(open.replacesChatOnTop).isFalse()
    }

    @Test fun plusFromAChatReplacesItWithAFreshLanding() {
        // + in chat 7 (pushed over the landing): an empty landing takes its place, so Back goes to the first
        // landing and Back again leaves Ask.
        val open = researchOpening(ResearchRoute(), researchOnTop = true)
        assertThat(open.route).isEqualTo(ResearchRoute())
        assertThat(open.replacesChatOnTop).isTrue()
    }

    @Test fun aReadersQuoteIsPushedOverTheReader() {
        // "Ask about this" navigates ResearchRoute.about(...) over the reader; nothing of Ask's is on top to replace.
        assertThat(researchOpening(ResearchRoute(quote = "owls"), researchOnTop = false).replacesChatOnTop).isFalse()
    }
}
