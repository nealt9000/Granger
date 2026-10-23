package app.granger.core.research

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.fold
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.transformWhile
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException

/** Finds passages by meaning: the [query] vector against precomputed passage vectors, best first. */
fun interface DenseFinder {
    suspend fun find(query: FloatArray): List<Passage>
}

/** Searches the library for passages matching the keywords. */
fun interface PassageFinder {
    suspend fun find(keywords: List<String>): List<Passage>
}

/** Streams a model's reply to [prompt] in chunks. */
fun interface TextGenerator {
    fun generate(prompt: String, maxTokens: Int): Flow<String>

    /**
     * As [generate], and calls [onReady] when the model is free to start: a generator that waits its turn for the
     * model (another chat may hold it) calls it once the wait is over. The default has no wait.
     */
    fun generate(prompt: String, maxTokens: Int, onReady: () -> Unit): Flow<String> =
        generate(prompt, maxTokens).onStart { onReady() }
}

/** Turns texts into vectors; [isQuery] marks the question side for models that embed queries differently. */
fun interface PassageEmbedder {
    suspend fun embed(texts: List<String>, isQuery: Boolean): List<FloatArray>
}

/** Loads the on-device models. [generator] throws [ModelUnavailableException] when the model can't load. */
interface ResearchModels {
    suspend fun generator(deeper: Boolean): TextGenerator
    suspend fun embedder(): PassageEmbedder?
}

/**
 * Source weighting: given the question, the keywords and every passage found, a multiplier for each passage's lexical
 * score (and its cosine, with dense retrieval). The app's is [FictionWeight].
 */
fun interface SourceWeight {
    fun of(question: String, keywords: List<String>, found: List<Passage>): (Passage) -> Double
}

class ModelUnavailableException(message: String, cause: Throwable? = null) : Exception(message, cause)

enum class Stage { LOADING_MODEL, SEARCHING, RANKING, WRITING }

/** [SEARCH_FAILED] is the library search or its storage failing; [MODEL_UNAVAILABLE] is the model. */
enum class FailReason { MODEL_UNAVAILABLE, SEARCH_FAILED }

/**
 * One question. [quote] is the user's selection and always becomes passage [1]. With [deeperFrom] set, retrieval
 * is skipped and those passages (up to 8) go to the large model.
 */
data class ResearchRequest(
    val question: String,
    val quote: Passage? = null,
    val prior: PriorTurn? = null,
    val priorQuestion: String? = null,
    val deeperFrom: List<Passage>? = null,
    val medicalHint: Boolean = false,
    /** Think deeper's: the turn it deepens was a weak match, and so is this one (its passages are the same). */
    val weakHint: Boolean = false,
)

/** How a question ended. `passages` are the numbered ones in the prompt; `candidates` are the ranked rest, kept for Think deeper. */
sealed interface Outcome {
    val medical: Boolean
    val keywords: List<String>

    /** [weak]: the library only loosely matched the question (see [RankingConfig.weakBelow]), so the UI hedges it. */
    data class Answered(
        val text: String,
        val passages: List<Passage>,
        val cited: Set<Int>,
        val candidates: List<Passage>,
        val usedLarge: Boolean,
        override val medical: Boolean,
        override val keywords: List<String>,
        val weak: Boolean = false,
    ) : Outcome

    /**
     * [passages] and [reply] are what the model was given and said when it declined; empty for a decline in code
     * (nothing found or ranked), before the model was asked.
     */
    data class Declined(
        override val medical: Boolean,
        override val keywords: List<String>,
        val passages: List<Passage> = emptyList(),
        val reply: String = "",
    ) : Outcome

    /** The reply's citations didn't check out; [text] is the raw reply, and the UI shows [passages] instead. */
    data class Unverified(
        val text: String,
        val passages: List<Passage>,
        val candidates: List<Passage>,
        val usedLarge: Boolean,
        override val medical: Boolean,
        override val keywords: List<String>,
        val weak: Boolean = false,
    ) : Outcome

    /** A best guess from the model's own knowledge: no passages, no citations. [fellBack] is true when the large model wouldn't load. */
    data class Guessed(val text: String, val usedLarge: Boolean, val fellBack: Boolean) : Outcome {
        override val medical: Boolean get() = false
        override val keywords: List<String> get() = emptyList()
    }

    data class Failed(
        val reason: FailReason,
        override val medical: Boolean,
        override val keywords: List<String>,
        /** The exception that was turned into this failure, when there was one. */
        val cause: Throwable? = null,
    ) : Outcome
}

sealed interface ResearchEvent {
    data class Progress(val stage: Stage) : ResearchEvent
    /** The reply so far. */
    data class Partial(val text: String) : ResearchEvent
    /** The question is medical, known before the reply is written, so the answer shows its banner as it streams. */
    data object Medical : ResearchEvent
    /** The large model wouldn't load, so the small one answers. */
    data object LargeFallback : ResearchEvent
    /** The large model wouldn't load for a best guess, so the small one writes it. */
    data object GuessFallback : ResearchEvent
    /** The library only loosely matches the question, known before the reply is written, so it streams under the hedge. */
    data object WeakMatch : ResearchEvent
    data class Done(val outcome: Outcome) : ResearchEvent
}

/** Question in, events out: load model, rewrite (or keyword fallback), find, rank, then decline or answer with citations. */
class ResearchPipeline(
    private val finder: PassageFinder,
    private val models: ResearchModels,
    private val config: RankingConfig = RankingConfig(),
    private val rewriteTimeoutMs: Long = 5_000,
    /** Plan R0's debug variant: the rewrite may give each keyword alternates (see [Rewrite.prompt]). Off in the app. */
    private val rewriteAlternates: Boolean = false,
    /** See [SourceWeight]; the app passes [FictionWeight]. */
    private val sourceWeight: SourceWeight? = null,
    /**
     * Dense retrieval (Plan R1): the question (after the prior one, when there is one) is embedded and searched by
     * meaning alongside the keyword search, and [Ranking.hybrid] merges the two. Without it, or when the embedder or
     * the vectors aren't there, ranking is the keyword ranking.
     */
    private val dense: DenseFinder? = null,
) {
    /** [stop] is polled after each chunk of the reply; when it turns true the reply so far is checked and finished. */
    fun run(request: ResearchRequest, stop: () -> Boolean = { false }): Flow<ResearchEvent> = flow {
        emit(ResearchEvent.Progress(Stage.LOADING_MODEL))
        val deeper = request.deeperFrom != null
        var loadFailure: Throwable? = null
        val generator = loadGenerator(deeper, onFailure = { loadFailure = it })
        if (generator == null) {
            emit(ResearchEvent.Done(Outcome.Failed(FailReason.MODEL_UNAVAILABLE, request.medicalHint, emptyList(), loadFailure)))
            return@flow
        }
        var usedLarge = deeper && generator.large
        var model = generator.model

        val medical: Boolean
        val keywords: List<String>
        val ranked: List<Passage>
        var weak = false
        val asked = MediaCues.asked(request.question)
        if (request.deeperFrom != null) {
            medical = request.medicalHint
            if (medical) emit(ResearchEvent.Medical)
            keywords = emptyList()
            ranked = request.deeperFrom
            weak = request.weakHint
        } else {
            val rewrite = rewrite(generator.model, request)
            medical = rewrite.medical == true || request.medicalHint ||
                Medical.looksMedical(request.question) || PersonalMedical.strong(request.question)
            if (medical) emit(ResearchEvent.Medical)
            keywords = rewrite.keywords
            if (request.quote == null && PersonalMedical.matches(request.question, medical)) {
                // One person's own health: the app's medical copy, whatever the library holds. No search, no answer.
                emit(ResearchEvent.Done(Outcome.Declined(medical = true, keywords)))
                return@flow
            }
            emit(ResearchEvent.Progress(Stage.SEARCHING))
            var hits: List<Passage>? = null
            val all = try {
                coroutineScope {
                    val byMeaning = dense?.let { d -> async { denseHits(d, request) } }
                    finder.find(Keywords.searchTerms(keywords)).also { hits = byMeaning?.await() }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                emit(ResearchEvent.Done(Outcome.Failed(FailReason.SEARCH_FAILED, medical, keywords, e)))
                return@flow
            }
            // A question about the user's selection is answered from it, however its library search went.
            weak = request.quote == null && hits?.let { h -> h.maxOf { it.cosine ?: 0.0 } < config.weakBelow } == true
            val weight = sourceWeight?.of(request.question, keywords, all + hits.orEmpty()) ?: Ranking.NO_WEIGHT
            val found = Ranking.preselect(all, keywords, config, weight)
            emit(ResearchEvent.Progress(Stage.RANKING))
            ranked = try {
                val merged = hits?.let { Ranking.hybrid(found, it, keywords, config, weight) }
                Ranking.liftMedia(merged ?: rank(request.question, found, keywords, weight), found, asked)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // The embedder failed: a model failure, reported with what the pipeline knows.
                emit(ResearchEvent.Done(Outcome.Failed(FailReason.MODEL_UNAVAILABLE, medical, keywords, e)))
                return@flow
            }
        }

        val quote = request.quote
        // The media cap is applied before the cut, so a dropped media passage gives its place to the next one.
        val capped = Ranking.capMedia(ranked, asked, config)
        if (quote == null && capped.isEmpty()) {
            emit(ResearchEvent.Done(Outcome.Declined(medical, keywords)))
            return@flow
        }
        val pool = (listOfNotNull(quote) + capped.filter { it != quote }).take(config.large)
        /** The passages that fit the prompt for the large or the small model; the rest stay candidates for Think deeper. */
        fun fit(large: Boolean): Pair<List<Passage>, List<Passage>> {
            val wanted = pool.take(if (large) config.large else config.small)
            // Dropped for the prompt's budget, a passage can't be cited.
            val fitted = AnswerPrompt.fit(request.question, wanted, medical, request.prior, hasQuote = quote != null)
            return fitted to pool.drop(fitted.size)
        }
        var (passages, candidates) = fit(usedLarge)

        if (weak) emit(ResearchEvent.WeakMatch)
        emit(ResearchEvent.Progress(Stage.WRITING))
        var streamed = streamReply(model, AnswerPrompt.build(request.question, passages, medical, request.prior, hasQuote = quote != null), stop)
        if (usedLarge && streamed.text == null && streamed.chunks == 0 && streamed.cause is ModelUnavailableException) {
            // The large model wouldn't reload for this reply (it can be released between the rewrite and the answer):
            // answer with the small one, as when it fails to load at the start.
            val small = try {
                models.generator(deeper = false)
            } catch (e: ModelUnavailableException) {
                emit(ResearchEvent.Done(Outcome.Failed(FailReason.MODEL_UNAVAILABLE, medical, keywords, e)))
                return@flow
            }
            emit(ResearchEvent.LargeFallback)
            usedLarge = false
            model = small
            fit(false).let { passages = it.first; candidates = it.second }
            streamed = streamReply(model, AnswerPrompt.build(request.question, passages, medical, request.prior, hasQuote = quote != null), stop)
        }
        val reply = streamed.text
        if (reply == null) {
            // Reported here, not thrown: a failure would reach the collector without the flags the pipeline knows.
            emit(ResearchEvent.Done(Outcome.Failed(FailReason.MODEL_UNAVAILABLE, medical, keywords, streamed.cause)))
            return@flow
        }

        val outcome = when (val check = Citations.check(reply, passages.size)) {
            is CitationCheck.Valid ->
                Outcome.Answered(reply, passages, check.cited, candidates, usedLarge, medical, keywords, weak)
            CitationCheck.ModelDeclined -> Outcome.Declined(medical, keywords, passages, reply)
            CitationCheck.Invalid -> Outcome.Unverified(reply, passages, candidates, usedLarge, medical, keywords, weak)
        }
        emit(ResearchEvent.Done(outcome))
    }

    /** A best guess with no passages: loads the model, streams the reply, and ends with [Outcome.Guessed]. */
    fun guess(question: String, priorQuestion: String?, large: Boolean, stop: () -> Boolean = { false }): Flow<ResearchEvent> = flow {
        emit(ResearchEvent.Progress(Stage.LOADING_MODEL))
        var fellBack = false
        var loadFailure: Throwable? = null
        val generator = loadGenerator(large, onFailure = { loadFailure = it }) { fellBack = true; emit(ResearchEvent.GuessFallback) }
        if (generator == null) {
            emit(ResearchEvent.Done(Outcome.Failed(FailReason.MODEL_UNAVAILABLE, false, emptyList(), loadFailure)))
            return@flow
        }
        emit(ResearchEvent.Progress(Stage.WRITING))
        val streamed = streamReply(generator.model, GuessPrompt.build(question, priorQuestion), stop)
        val reply = streamed.text
        if (reply == null) {
            emit(ResearchEvent.Done(Outcome.Failed(FailReason.MODEL_UNAVAILABLE, false, emptyList(), streamed.cause)))
            return@flow
        }
        emit(ResearchEvent.Done(Outcome.Guessed(reply, generator.large, fellBack)))
    }

    /** Streams the reply as [ResearchEvent.Partial]s, polling [stop] after each chunk; returns the text so far, or the failure. */
    private suspend fun FlowCollector<ResearchEvent>.streamReply(model: TextGenerator, prompt: String, stop: () -> Boolean): StreamResult {
        var failure: Throwable? = null
        var chunks = 0
        val text = model.generate(prompt, ANSWER_TOKENS)
            .catch { e ->
                if (e is CancellationException) throw e
                failure = e
            }
            .transformWhile { chunk ->
                emit(chunk)
                !stop()
            }
            .fold("") { soFar, chunk ->
                chunks++
                val text = soFar + chunk
                emit(ResearchEvent.Partial(text))
                text
            }
        return if (failure != null) StreamResult(null, failure, chunks) else StreamResult(text, null, chunks)
    }

    /** [text] is null when the model failed with [cause]; [chunks] is how many pieces of the reply arrived first. */
    private class StreamResult(val text: String?, val cause: Throwable?, val chunks: Int)

    private class Loaded(val model: TextGenerator, val large: Boolean)

    /** Null when even the small model won't load. */
    private suspend fun FlowCollector<ResearchEvent>.loadGenerator(
        deeper: Boolean,
        onFailure: (Throwable) -> Unit = {},
        onFallback: suspend () -> Unit = { emit(ResearchEvent.LargeFallback) },
    ): Loaded? {
        if (deeper) {
            try {
                return Loaded(models.generator(deeper = true), large = true)
            } catch (e: ModelUnavailableException) {
                onFallback()
            }
        }
        return try {
            Loaded(models.generator(deeper = false), large = false)
        } catch (e: ModelUnavailableException) {
            onFailure(e)
            null
        }
    }

    /**
     * The model's keywords, or the plain keyword fallback when it times out, errors, or replies with nothing usable.
     * The medical lexicon decides whenever the model didn't say.
     */
    private suspend fun rewrite(generator: TextGenerator, request: ResearchRequest): RewriteResult {
        val parsed = try {
            var result: RewriteResult? = null
            // The timeout runs from the model being free to start, not from asking: another chat may hold it first.
            coroutineScope {
                val ready = CompletableDeferred<Unit>()
                val work = launch {
                    val prompt = Rewrite.prompt(request.question, request.priorQuestion, rewriteAlternates)
                    val tokens = if (rewriteAlternates) REWRITE_TOKENS_ALTERNATES else REWRITE_TOKENS
                    val reply = generator.generate(prompt, tokens) { ready.complete(Unit) }
                        .fold("") { acc, chunk -> acc + chunk }
                    result = Rewrite.parse(reply, rewriteAlternates)
                }
                val timer = launch {
                    ready.await()
                    delay(if (rewriteAlternates) rewriteTimeoutMs + ALTERNATES_EXTRA_MS else rewriteTimeoutMs)
                    work.cancel()
                }
                work.join()
                timer.cancel()
            }
            result
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
        val keywords = parsed?.keywords.orEmpty().map(Keywords::normalize).filter { it.isNotBlank() }.distinct()
        val text = listOfNotNull(request.question, request.priorQuestion).joinToString(" ")
        if (parsed != null && keywords.isNotEmpty()) return RewriteResult(keywords, parsed.medical ?: Medical.looksMedical(text))
        return RewriteResult(Keywords.extract(text), Medical.looksMedical(text))
    }

    /**
     * The dense hits for [request], or null for keyword ranking alone: no embedder, no vector files, or a failure.
     * Dense retrieval only ever adds; a question it can't help with is ranked as it was without it.
     */
    private suspend fun denseHits(finder: DenseFinder, request: ResearchRequest): List<Passage>? = try {
        val embedder = models.embedder()
        if (embedder == null) {
            null
        } else {
            val text = listOfNotNull(request.priorQuestion, request.question).joinToString(" ")
            finder.find(embedder.embed(listOf(text), isQuery = true).single()).takeIf { it.isNotEmpty() }
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }

    private suspend fun rank(question: String, candidates: List<Passage>, keywords: List<String>, weight: (Passage) -> Double): List<Passage> {
        if (candidates.isEmpty()) return emptyList()
        // Every candidate that passes is kept: the pool is cut to `large` after the media cap. With dense retrieval the
        // embedder is for the query alone: reranking 12 passages with it takes 4 s on the Pixel 6.
        val embedder = (if (dense == null) models.embedder() else null)
            ?: return Ranking.byLexical(candidates, keywords, config, limit = candidates.size, weight = weight)
        val query = embedder.embed(listOf(question), isQuery = true).single()
        val vectors = embedder.embed(candidates.map { it.text }, isQuery = false)
        return Ranking.byEmbedding(candidates, query, vectors, config, limit = candidates.size)
    }

    private companion object {
        const val REWRITE_TOKENS = 32
        /** Room for up to two alternates per keyword, and the time to write them. */
        const val REWRITE_TOKENS_ALTERNATES = 64
        const val ALTERNATES_EXTRA_MS = 3_000L
        const val ANSWER_TOKENS = 512
    }
}
