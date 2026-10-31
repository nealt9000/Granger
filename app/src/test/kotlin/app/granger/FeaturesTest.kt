package app.granger

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class FeaturesTest {
    @Test fun researchIsOnInEveryBuild() {
        // The friend build ships Ask as a Preview (Plan F1 part 2); the device tier gates it, not the build type.
        assertThat(Features.RESEARCH).isTrue()
        assertThat(app.granger.shell.RailTool.RESEARCH.shown).isTrue()
    }

    @Test fun playerIsOnWithItsRailTool() {
        assertThat(Features.PLAYER).isTrue()
        assertThat(app.granger.shell.RailTool.LISTEN.shown).isTrue()
    }
}
