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
}
