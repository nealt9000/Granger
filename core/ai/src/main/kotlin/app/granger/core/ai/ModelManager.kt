package app.granger.core.ai

import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

enum class LlmSize { SMALL, LARGE }

/** Model files currently on disk; null means not installed. */
data class InstalledModels(val small: File? = null, val large: File? = null, val embedder: File? = null)

enum class ResearchReadiness { UNSUPPORTED, MODELS_MISSING, READY }

/** Loading a model failed. A null [size] means the embedder. */
class ModelLoadException(val size: LlmSize?, cause: Throwable) : Exception(cause)

/**
 * Keeps at most one LLM resident. The embedder may stay loaded only beside the small model; the large one
 * needs the memory. Everything is released after a quiet spell ([releaseSoon]).
 */
class ModelManager(
    private val tier: DeviceTier,
    private val installed: Flow<InstalledModels>,
    private val llms: LlmEngineFactory,
    private val embedders: TextEmbedderFactory,
    private val scope: CoroutineScope,
    private val useEmbedder: Boolean,
    private val backend: LlmBackend = LlmBackend.GPU,
    private val releaseAfterMs: Long = 120_000,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    private val supported = tier >= DeviceTier.ASK

    val readiness: StateFlow<ResearchReadiness> = installed
        .map {
            when {
                !supported -> ResearchReadiness.UNSUPPORTED
                it.small == null -> ResearchReadiness.MODELS_MISSING
                else -> ResearchReadiness.READY
            }
        }
        .stateIn(scope, SharingStarted.Eagerly, if (supported) ResearchReadiness.MODELS_MISSING else ResearchReadiness.UNSUPPORTED)

    val canThinkDeeper: StateFlow<Boolean> = installed
        .map { tier == DeviceTier.THINK_DEEPER && it.large != null }
        .stateIn(scope, SharingStarted.Eagerly, false)

    private val lock = Mutex()
    private var llm: LlmEngine? = null
    private var llmSize: LlmSize? = null
    private var embedder: TextEmbedder? = null
    @Volatile private var releaseJob: Job? = null

    /** The resident engine for [size], swapping out the other one first. Throws [ModelLoadException]. */
    suspend fun engine(size: LlmSize): LlmEngine {
        cancelRelease()
        return lock.withLock {
            val resident = llm
            if (resident != null && llmSize == size) return@withLock resident
            closeLlm()
            if (size == LlmSize.LARGE) closeEmbedder()
            val models = installed.first()
            val file = (if (size == LlmSize.SMALL) models.small else models.large)
                ?: throw ModelLoadException(size, IllegalStateException("$size model is not installed"))
            val loaded = try {
                withContext(io) {
                    val e = llms.create(file.path, backend)
                    try {
                        e.load()
                    } catch (t: Throwable) {
                        runCatching { e.close() }
                        throw t
                    }
                    e
                }
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                throw ModelLoadException(size, t)
            }
            llm = loaded
            llmSize = size
            loaded
        }
    }

    /** The embedder, or null (disabled, not installed, large model resident, or failed to load: callers rank lexically). */
    suspend fun embedder(): TextEmbedder? {
        if (!useEmbedder) return null
        cancelRelease()
        return lock.withLock {
            if (llmSize == LlmSize.LARGE) return@withLock null
            embedder?.let { return@withLock it }
            val file = installed.first().embedder ?: return@withLock null
            try {
                withContext(io) {
                    val e = embedders.create(file.path)
                    try {
                        e.load()
                    } catch (t: Throwable) {
                        runCatching { e.close() }
                        throw t
                    }
                    e
                }.also { embedder = it }
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                // Logging must never break ranking (android.util.Log is absent in unit tests).
                runCatching { android.util.Log.w("Granger", "Embedder failed to load; ranking lexically", ModelLoadException(null, t)) }
                null
            }
        }
    }

    /** Closes everything after [releaseAfterMs] unless [engine] or [embedder] is called first. */
    fun releaseSoon() {
        cancelRelease()
        releaseJob = scope.launch {
            delay(releaseAfterMs)
            lock.withLock { closeAll() }
        }
    }

    /** Closes everything now (once any load in progress finishes). */
    fun releaseNow() {
        cancelRelease()
        scope.launch { lock.withLock { closeAll() } }
    }

    private fun cancelRelease() {
        releaseJob?.cancel()
        releaseJob = null
    }

    private fun closeAll() {
        closeLlm()
        closeEmbedder()
    }

    private fun closeLlm() {
        runCatching { llm?.close() }
        llm = null
        llmSize = null
    }

    private fun closeEmbedder() {
        runCatching { embedder?.close() }
        embedder = null
    }
}
