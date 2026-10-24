package app.granger.core.ai

/**
 * Plan R4's test knobs. The defaults leave the model as the app runs it: no speculative-decoding override, the model's own
 * thinking behavior, no benchmark.
 */
data class LlmOptions(
    /** Speculative decoding (MTP drafters) on or off; null keeps the runtime's default. */
    val speculative: Boolean? = null,
    /** How to switch a reasoning model's thinking off; null leaves it as the model does. */
    val thinkingOff: ThinkingOff? = null,
    /** Measures each reply's token counts and rates ([LlmEngine.lastStats]). Eval only: it's a process-wide flag. */
    val benchmark: Boolean = false,
)

/** The runtime's thinking switch, the chat template's `enable_thinking=false`, or both. Models honor different ones. */
enum class ThinkingOff { CONFIG, TEMPLATE, BOTH }

/** One reply's prompt (prefill) and reply (decode) tokens and their rates. */
data class GenerationStats(
    val prefillTokens: Int,
    val prefillTokensPerSecond: Double,
    val decodeTokens: Int,
    val decodeTokensPerSecond: Double,
)

fun interface LlmEngineFactory {
    fun create(modelPath: String, backend: LlmBackend): LlmEngine
}

/** What [LlmOptions] changes in a conversation; everything unset keeps the runtime's default. */
internal data class Overrides(val speculative: Boolean?, val thinkingConfigOff: Boolean, val templateThinkingOff: Boolean)

// Apart from LiteRtLlmEngine so JVM tests (JDK 17) can check it: LiteRT-LM's classes need Java 21.
internal fun LlmOptions.overrides() = Overrides(
    speculative = speculative,
    thinkingConfigOff = thinkingOff == ThinkingOff.CONFIG || thinkingOff == ThinkingOff.BOTH,
    templateThinkingOff = thinkingOff == ThinkingOff.TEMPLATE || thinkingOff == ThinkingOff.BOTH,
)
