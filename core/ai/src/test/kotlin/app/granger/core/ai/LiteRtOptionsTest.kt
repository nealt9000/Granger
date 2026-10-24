package app.granger.core.ai

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class LiteRtOptionsTest {
    @Test
    fun defaultsOverrideNothing() {
        assertThat(LlmOptions().overrides()).isEqualTo(Overrides(null, thinkingConfigOff = false, templateThinkingOff = false))
    }

    @Test
    fun speculativeDecodingIsPassedOn() {
        assertThat(LlmOptions(speculative = true).overrides().speculative).isTrue()
        assertThat(LlmOptions(speculative = false).overrides().speculative).isFalse()
    }

    @Test
    fun thinkingOffUsesTheChosenSwitch() {
        assertThat(LlmOptions(thinkingOff = ThinkingOff.CONFIG).overrides())
            .isEqualTo(Overrides(null, thinkingConfigOff = true, templateThinkingOff = false))
        assertThat(LlmOptions(thinkingOff = ThinkingOff.TEMPLATE).overrides())
            .isEqualTo(Overrides(null, thinkingConfigOff = false, templateThinkingOff = true))
        assertThat(LlmOptions(thinkingOff = ThinkingOff.BOTH).overrides())
            .isEqualTo(Overrides(null, thinkingConfigOff = true, templateThinkingOff = true))
    }
}
