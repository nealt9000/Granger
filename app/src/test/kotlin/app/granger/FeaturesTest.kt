package app.granger

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class FeaturesTest {
    @Test fun researchIsOffInEveryBuild() {
        // Ask rests from Plan SQ (Neal, 2026-11-10): no rail tool in any build.
        assertThat(Features.RESEARCH).isFalse()
        assertThat(app.granger.shell.RailTool.RESEARCH.shown).isFalse()
    }

    @Test fun playerIsOnWithItsRailTool() {
        assertThat(Features.PLAYER).isTrue()
        assertThat(app.granger.shell.RailTool.LISTEN.shown).isTrue()
    }
}
