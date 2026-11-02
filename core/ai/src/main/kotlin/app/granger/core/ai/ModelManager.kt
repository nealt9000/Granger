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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.fold
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

/** [FAILED_CHECK]: the installed model failed its self-check on this phone, so Ask is hidden ([ModelManager.checkFailure]). */
enum class ResearchReadiness { UNSUPPORTED, MODELS_MISSING, READY, FAILED_CHECK }

/** Loading the LLM or the embedder failed. */
class ModelLoadException(cause: Throwable) : Exception(cause)

/**
 * Keeps the LLM, and the embedder beside it, resident while in use and while an Ask screen is open ([keepWhileOpen]);
 * both are released a quiet spell after the last Ask screen closes ([releaseSoon]), or at once when the app is hidden
 * ([releaseNow]).
 */
class ModelManager(
    private val tier: DeviceTier,
    private val installed: Flow<InstalledModels>,
    private val llms: LlmEngineFactory,
    private val embedders: TextEmbedderFactory,
    private val scope: CoroutineScope,
    /** Read on each [embedder] call, so a debug run can turn dense retrieval on without a new manager. */
    private val useEmbedder: () -> Boolean,
    private val backend: LlmBackend = LlmBackend.GPU,
    /** Neal, 2026-11-02: five minutes after no Ask screen is open. */
    private val releaseAfterMs: Long = RELEASE_AFTER_MS,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    /** How long a load waits for its file to be listed: the list is empty until the catalog has loaded at startup. */
    private val installWaitMs: Long = 10_000,
    /**
     * The self-check (release checklist A): the first load of each model file on this phone answers [SelfCheck.PROMPT]
     * before it is used, with a marker on disk first, so a native crash shows at the next launch. A failure bars the
     * model ([ResearchReadiness.FAILED_CHECK]) until [retryCheck]. A store with no file checks nothing.
     */
    private val checks: ModelCheckStore = ModelCheckStore(null),
    private val checkTimeoutMs: Long = SelfCheck.TIMEOUT_MS,
) {
    private val supported = tier >= DeviceTier.ASK

    /** Bumped on every check result, so [checkFailure] and [readiness] follow the store. */
    private val checkVersion = MutableStateFlow(0)

    /** Why the installed model is barred on this phone, or null when it isn't (passed, unchecked, or none installed). */
    val checkFailure: StateFlow<CheckFailure?> = combine(installed, checkVersion) { models, _ ->
        models.llm?.let { (checks.get(ModelCheckStore.keyOf(it)) as? ModelCheckStore.State.Failed)?.why }
    }.stateIn(scope, SharingStarted.Eagerly, null)

    val readiness: StateFlow<ResearchReadiness> = combine(installed, checkFailure) { models, failure ->
        when {
            !supported -> ResearchReadiness.UNSUPPORTED
            models.llm == null -> ResearchReadiness.MODELS_MISSING
            failure != null -> ResearchReadiness.FAILED_CHECK
            else -> ResearchReadiness.READY
        }
    }.stateIn(scope, SharingStarted.Eagerly, if (supported) ResearchReadiness.MODELS_MISSING else ResearchReadiness.UNSUPPORTED)

    /** Try again: forgets the installed model's failed check, so the next question checks it afresh. */
    suspend fun retryCheck() {
        val file = installed.first().llm ?: return
        checks.clear(ModelCheckStore.keyOf(file))
        checkVersion.value++
    }

    private val lock = Mutex()
    private var llm: LlmEngine? = null
    private var embedder: TextEmbedder? = null
    @Volatile private var releaseJob: Job? = null

    private enum class Pending { NONE, SOON, NOW }
    private val sync = Any()
    private var holds = 0
    private var pending = Pending.NONE
    /** Ask screens open now ([keepWhileOpen]); a quiet-spell release waits until none is. */
    private var open = 0

    /** The resident engine, loading it first if needed. Throws [ModelLoadException]. */
    suspend fun engine(): LlmEngine {
        cancelRelease()
        return lock.withLock {
            llm?.let { return@withLock it }
            val file = installedFile()
                ?: throw ModelLoadException(IllegalStateException("The model is not installed"))
            val key = ModelCheckStore.keyOf(file)
            val known = if (checks.enabled) checks.get(key) else ModelCheckStore.State.Passed
            if (known is ModelCheckStore.State.Failed) throw ModelLoadException(ModelCheckFailedException(known.why))
            val checking = known != ModelCheckStore.State.Passed
            try {
                // Store inside the block: withContext drops its result if the caller was cancelled meanwhile,
                // and a blocking load can't be interrupted, so the loaded model must not be orphaned.
                withContext(io + NonCancellable) {
                    // On disk before the model is touched: a native crash from here on is found at the next launch.
                    if (checking) checks.set(key, ModelCheckStore.State.Running)
                    val e = llms.create(file.path, backend)
                    try {
                        e.load()
                    } catch (t: Throwable) {
                        runCatching { e.close() }
                        if (checking) fail(key, CheckFailure.LOAD_FAILED)
                        throw t
                    }
                    if (checking) {
                        val failure = selfCheck(e)
                        if (failure != null) {
                            runCatching { e.close() }
                            fail(key, failure)
                            throw ModelCheckFailedException(failure)
                        }
                        checks.set(key, ModelCheckStore.State.Passed)
                        checkVersion.value++
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

    /** Null when [engine] answers [SelfCheck.PROMPT] right, in time; else why not. */
    private suspend fun selfCheck(engine: LlmEngine): CheckFailure? {
        val reply = try {
            withTimeoutOrNull(checkTimeoutMs) {
                engine.generate(SelfCheck.PROMPT, SelfCheck.MAX_TOKENS).fold("") { acc, chunk -> acc + chunk }
            } ?: return CheckFailure.TOO_SLOW
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            return CheckFailure.WRONG_REPLY
        }
        runCatching { android.util.Log.i("Granger", "Model self-check replied: ${reply.take(40)}") }
        return if (SelfCheck.passes(reply)) null else CheckFailure.WRONG_REPLY
    }

    private fun fail(key: String, why: CheckFailure) {
        checks.set(key, ModelCheckStore.State.Failed(why))
        checkVersion.value++
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

    /**
     * An Ask screen is showing: the models stay loaded while it is (Neal, 2026-11-02), and a quiet-spell release starts
     * only when the returned function (idempotent) says the last one has closed. [releaseNow] still applies: a hidden
     * app gives its memory back.
     */
    fun keepWhileOpen(): () -> Unit {
        synchronized(sync) { open++ }
        cancelRelease()
        var closed = false
        return {
            val last = synchronized(sync) {
                if (closed) {
                    false
                } else {
                    closed = true
                    open--
                    open == 0
                }
            }
            if (last) releaseSoon()
        }
    }

    /**
     * Closes everything after [releaseAfterMs] unless [engine] or [embedder] is called first. With an Ask screen open
     * it does nothing: closing the last one asks again.
     */
    fun releaseSoon() {
        stopTimer()
        if (synchronized(sync) { open > 0 }) return
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
        // An Ask screen opened since the timer started keeps a quiet-spell release from happening.
        if (kind == Pending.SOON && synchronized(sync) { open > 0 }) return@withLock
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

    companion object {
        /** How long the models stay loaded after the last Ask screen closes. */
        const val RELEASE_AFTER_MS = 300_000L
    }
}
