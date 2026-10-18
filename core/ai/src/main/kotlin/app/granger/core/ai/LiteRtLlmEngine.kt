package app.granger.core.ai

import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.SamplerConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

class LiteRtLlmEngine(
    private val modelPath: String,
    private val backend: LlmBackend,
    private val cacheDir: String,
) : LlmEngine {
    private var engine: Engine? = null

    override fun load() {
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
        e.createConversation(ConversationConfig(samplerConfig = GREEDY)).use { conversation ->
            var finished = false
            try {
                conversation.sendMessageAsync(prompt, maxOutputToken = maxTokens).collect { message ->
                    val text = message.contents.contents.filterIsInstance<Content.Text>().joinToString("") { it.text }
                    if (text.isNotEmpty()) emit(text)
                }
                finished = true
            } finally {
                // LiteRT-LM's flow keeps decoding natively when collection stops early; stop it before close().
                if (!finished) conversation.cancelProcess()
            }
        }
    }

    override fun close() {
        engine?.close()
        engine = null
    }

    private companion object {
        // Prompt (~1.3k tokens with 4 passages, ~2.5k with 8) plus the reply must fit.
        const val MAX_CONTEXT_TOKENS = 4096

        // Always the most likely next token: the same prompt gets the same reply, which keeps answers close to the
        // passages and makes eval runs comparable. The model's own default samples at random.
        val GREEDY = SamplerConfig(topK = 1, topP = 1.0, temperature = 1.0, seed = 0)
    }
}
