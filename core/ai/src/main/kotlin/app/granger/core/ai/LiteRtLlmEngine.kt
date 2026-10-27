// ExperimentalFlags: the benchmark switch, used by eval replays only (LlmOptions.benchmark).
@file:OptIn(ExperimentalApi::class)

package app.granger.core.ai

import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.ExperimentalApi
import com.google.ai.edge.litertlm.ExperimentalFlags
import com.google.ai.edge.litertlm.SamplerConfig
import com.google.ai.edge.litertlm.ThinkingConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

class LiteRtLlmEngine(
    private val modelPath: String,
    private val backend: LlmBackend,
    private val cacheDir: String,
    private val options: LlmOptions = LlmOptions(),
) : LlmEngine {
    private var engine: Engine? = null
    private var benchmarkBefore: Boolean? = null

    override var lastStats: GenerationStats? = null
        private set

    override fun load() {
        if (options.benchmark) {
            benchmarkBefore = ExperimentalFlags.enableBenchmark
            ExperimentalFlags.enableBenchmark = true
        }
        val config = EngineConfig(
            modelPath = modelPath,
            backend = when (backend) {
                LlmBackend.CPU -> Backend.CPU()
                LlmBackend.GPU -> Backend.GPU()
            },
            maxNumTokens = MAX_CONTEXT_TOKENS,
            cacheDir = cacheDir,
        )
        engine = Engine(config).also { it.initialize() }
    }

    override fun generate(prompt: String, maxTokens: Int): Flow<String> = flow {
        val e = checkNotNull(engine) { "load() first" }
        lastStats = null
        e.createConversation(conversationConfig(options)).use { conversation ->
            var finished = false
            try {
                conversation.sendMessageAsync(prompt, maxOutputToken = maxTokens).collect { message ->
                    val text = message.contents.contents.filterIsInstance<Content.Text>().joinToString("") { it.text }
                    if (text.isNotEmpty()) emit(text)
                }
                finished = true
                if (options.benchmark) {
                    lastStats = runCatching { conversation.getBenchmarkInfo() }.getOrNull()?.let {
                        GenerationStats(it.lastPrefillTokenCount, it.lastPrefillTokensPerSecond, it.lastDecodeTokenCount, it.lastDecodeTokensPerSecond)
                    }
                }
            } finally {
                // LiteRT-LM's flow keeps decoding natively when collection stops early; stop it before close().
                if (!finished) conversation.cancelProcess()
            }
        }
    }

    override fun close() {
        engine?.close()
        engine = null
        benchmarkBefore?.let { ExperimentalFlags.enableBenchmark = it }
        benchmarkBefore = null
    }

    internal companion object {
        /** Greedy, plus [overrides] of [options]. */
        fun conversationConfig(options: LlmOptions): ConversationConfig {
            val o = options.overrides()
            var config = ConversationConfig(samplerConfig = GREEDY)
            o.speculative?.let { config = config.copy(enableSpeculativeDecoding = it) }
            if (o.thinkingConfigOff) config = config.copy(thinkingConfig = ThinkingConfig(enableThinking = false))
            if (o.templateThinkingOff) config = config.copy(extraContext = mapOf("enable_thinking" to false))
            return config
        }

        // Prompt (~1.3k tokens with 4 passages, ~2.5k with 8) plus the reply must fit.
        const val MAX_CONTEXT_TOKENS = 4096

        // Always the most likely next token: the same prompt gets the same reply, which keeps answers close to the
        // passages and makes eval runs comparable. The model's own default samples at random.
        val GREEDY = SamplerConfig(topK = 1, topP = 1.0, temperature = 1.0, seed = 0)
    }
}
