package app.granger.core.ai

import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
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
            cacheDir = cacheDir,
        )
        engine = Engine(config).also { it.initialize() }
    }

    override fun generate(prompt: String): Flow<String> = flow {
        val e = checkNotNull(engine) { "load() first" }
        e.createConversation().use { conversation ->
            conversation.sendMessageAsync(prompt).collect { message ->
                emit(message.contents.contents.filterIsInstance<Content.Text>().joinToString("") { it.text })
            }
        }
    }

    override fun close() {
        engine?.close()
        engine = null
    }
}
