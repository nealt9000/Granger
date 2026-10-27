package app.granger.core.ai

import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext
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
import kotlinx.coroutines.withTimeoutOrNull

/** Model files currently on disk; null means not installed. */
data class InstalledModels(val llm: File? = null, val embedder: File? = null)

enum class ResearchReadiness { UNSUPPORTED, MODELS_MISSING, READY }

/** Loading the LLM or the embedder failed. */
class ModelLoadException(cause: Throwable) : Exception(cause)

/** Keeps the LLM, and the embedder beside it, resident while in use; both are released after a quiet spell ([releaseSoon]). */
class ModelManager(
    private val tier: DeviceTier,
    private val installed: Flow<InstalledModels>,
    private val llms: LlmEngineFactory,
    private val embedders: TextEmbedderFactory,
    private val scope: CoroutineScope,
    /** Read on each [embedder] call, so a debug run can turn dense retrieval on without a new manager. */
    private val useEmbedder: () -> Boolean,
    private val backend: LlmBackend = LlmBackend.GPU,
    private val releaseAfterMs: Long = 120_000,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    /** How long a load waits for its file to be listed: the list is empty until the catalog has loaded at startup. */
    private val installWaitMs: Long = 10_000,
) {
    private val supported = tier >= DeviceTier.ASK

    val readiness: StateFlow<ResearchReadiness> = installed
        .map {
            when {
                !supported -> ResearchReadiness.UNSUPPORTED
                it.llm == null -> ResearchReadiness.MODELS_MISSING
                else -> ResearchReadiness.READY
            }
        }
        .stateIn(scope, SharingStarted.Eagerly, if (supported) ResearchReadiness.MODELS_MISSING else ResearchReadiness.UNSUPPORTED)

    private val lock = Mutex()
    private var llm: LlmEngine? = null
    private var embedder: TextEmbedder? = null
    @Volatile private var releaseJob: Job? = null

    private enum class Pending { NONE, SOON, NOW }
    private val sync = Any()
    private var holds = 0
    private var pending = Pending.NONE

    /** The resident engine, loading it first if needed. Throws [ModelLoadException]. */
    suspend fun engine(): LlmEngine {
        cancelRelease()
        return lock.withLock {
            llm?.let { return@withLock it }
            val file = installedFile()
                ?: throw ModelLoadException(IllegalStateException("The model is not installed"))
            try {
                // Store inside the block: withContext drops its result if the caller was cancelled meanwhile,
                // and a blocking load can't be interrupted, so the loaded model must not be orphaned.
                withContext(io + NonCancellable) {
                    val e = llms.create(file.path, backend)
                    try {
                        e.load()
                    } catch (t: Throwable) {
                        runCatching { e.close() }
                        throw t
                    }
                    llm = e
                }
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                throw ModelLoadException(t)
            }
            coroutineContext.ensureActive()
            checkNotNull(llm)
        }
    }

    /** The model's file once it is listed, or null when it isn't within [installWaitMs] (a question asked right after launch waits). */
    private suspend fun installedFile(): File? =
        withTimeoutOrNull(installWaitMs) { installed.first { it.llm != null }.llm }

    /** The embedder, or null (disabled, not installed, or failed to load: callers rank lexically). */
    suspend fun embedder(): TextEmbedder? {
        if (!useEmbedder()) return null
        cancelRelease()
        return lock.withLock {
            embedder?.let { return@withLock it }
            val file = installed.first().embedder ?: return@withLock null
            try {
                withContext(io + NonCancellable) {
                    val e = embedders.create(file.path)
                    try {
                        e.load()
                    } catch (t: Throwable) {
                        runCatching { e.close() }
                        throw t
                    }
                    embedder = e
                }
                coroutineContext.ensureActive()
                embedder
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                // Logging must never break ranking (android.util.Log is absent in unit tests).
                runCatching { android.util.Log.w("Granger", "Embedder failed to load; ranking lexically", ModelLoadException(t)) }
                null
            }
        }
    }

    /** Marks an engine/embedder as in use; releases wait until the returned let-go function (idempotent) is called. */
    fun hold(): () -> Unit {
        synchronized(sync) { holds++ }
        var letGo = false
        return {
            val run = synchronized(sync) {
                if (letGo) {
                    null
                } else {
                    letGo = true
                    holds--
                    if (holds == 0) pending.also { pending = Pending.NONE } else null
                }
            }
            when (run) {
                Pending.SOON -> releaseSoon()
                Pending.NOW -> releaseNow()
                else -> Unit
            }
        }
    }

    /** Closes everything after [releaseAfterMs] unless [engine] or [embedder] is called first. */
    fun releaseSoon() {
        stopTimer()
        if (deferIfHeld(Pending.SOON)) return
        releaseJob = scope.launch {
            delay(releaseAfterMs)
            closeIfFree(Pending.SOON)
        }
    }

    /** Closes everything now (once any load in progress finishes, and nothing is held). */
    fun releaseNow() {
        stopTimer()
        if (deferIfHeld(Pending.NOW)) return
        releaseJob = scope.launch { closeIfFree(Pending.NOW) }
    }

    /**
     * The app is back in front: a [releaseNow] still waiting on a hold (asked for when it went to the background) is
     * dropped, so the turn's next question doesn't reload the models. A quiet-spell release stays.
     */
    fun appReturned() {
        synchronized(sync) { if (pending == Pending.NOW) pending = Pending.SOON }
    }

    private fun deferIfHeld(kind: Pending): Boolean = synchronized(sync) {
        if (holds > 0) {
            if (pending != Pending.NOW) pending = kind
            true
        } else {
            false
        }
    }

    private suspend fun closeIfFree(kind: Pending) = lock.withLock {
        if (!deferIfHeld(kind)) closeAll()
    }

    /**
     * A use cancels any release, including a quiet-spell one deferred by a hold. A deferred [releaseNow] stays: the app
     * went to the background mid-turn, and the turn's later stages must not undo that, so the last let-go releases.
     */
    private fun cancelRelease() {
        synchronized(sync) { if (pending == Pending.SOON) pending = Pending.NONE }
        stopTimer()
    }

    private fun stopTimer() {
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
    }

    private fun closeEmbedder() {
        runCatching { embedder?.close() }
        embedder = null
    }
}
