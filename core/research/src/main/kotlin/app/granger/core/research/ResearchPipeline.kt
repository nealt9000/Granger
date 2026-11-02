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

/**
 * Searches the library for passages matching the keywords. [languages] are the question's ([ResearchLanguage.of]):
 * whose stopwords a finder leaves out when it matches words itself. A finder that matches no words of its own needs
 * only the one-argument [find]; the pipeline calls the two-argument one, which is the English [find] unless overridden.
 * (A fun interface's one abstract method can't have a default value, hence the two.)
 */
fun interface PassageFinder {
    suspend fun find(keywords: List<String>): List<Passage>

    suspend fun find(keywords: List<String>, languages: List<ResearchLanguage>): List<Passage> = find(keywords)
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
    suspend fun generator(): TextGenerator
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

/** One question. [quote] is the user's selection and always becomes passage [1]. */
data class ResearchRequest(
    val question: String,
    val quote: Passage? = null,
    val prior: PriorTurn? = null,
    val priorQuestion: String? = null,
)

/** How a question ended. `passages` are the numbered ones in the prompt; `candidates` are the ranked rest of the pool. */
sealed interface Outcome {
    val medical: Boolean
    val keywords: List<String>

    data class Answered(
        val text: String,
        val passages: List<Passage>,
        val cited: Set<Int>,
        val candidates: List<Passage>,
        override val medical: Boolean,
        override val keywords: List<String>,
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
        override val medical: Boolean,
        override val keywords: List<String>,
    ) : Outcome

    /** A best guess from the model's own knowledge: no passages, no citations. */
    data class Guessed(val text: String) : Outcome {
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
    /** The owl's instant reaction: the question's topic, first of all a run's events, before the model loads. */
    data class Reaction(val topic: ReactionTopic) : ResearchEvent
    /** The search by meaning's first titles, best first (up to [LOOKING_IN_TITLES]), while the model is still at work. */
    data class LookingIn(val titles: List<String>) : ResearchEvent
    /** How many passages the answer is written from, just before [Stage.WRITING]: the owl's "Reading 4 passages…". */
    data class Reading(val passages: Int) : ResearchEvent
    data class Progress(val stage: Stage) : ResearchEvent
    /** The reply so far. */
    data class Partial(val text: String) : ResearchEvent
    /** The question is medical, known before the reply is written, so the answer shows its banner as it streams. */
    data object Medical : ResearchEvent
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
    /**
     * Plan R4's debug variant: a first question gets [Keywords.extract] and the medical lexicon, with no model call; a
     * follow-up still asks the model, to fold in the earlier turn. Off in the app.
     */
    private val rewritePlain: Boolean = false,
    /** Plan R5 Task 6: with [rewritePlain], a follow-up too gets [Keywords.extract] of it and its prior question. */
    private val rewritePlainFollowUps: Boolean = false,
    /** See [SourceWeight]; the app passes [FictionWeight]. */
    private val sourceWeight: SourceWeight? = null,
    /**
     * Dense retrieval (Plan R1): the question (after the prior one, when it [FollowUp.refersBack]) is embedded and searched by
     * meaning alongside the keyword search, and [Ranking.hybrid] merges the two. Without it, or when the embedder or
     * the vectors aren't there, ranking is the keyword ranking.
     */
    private val dense: DenseFinder? = null,
    /**
     * Plan R5 Task 4: a question that compares dates gives each thing it names a place among the
     * answer's passages ([Entities.reserve]). On in the app from Plan R5 Task 7.
     */
    private val reserveNamed: Boolean = false,
    /**
     * Plan R5 Task 4: a question that compares dates gets the dates of the things it names and
     * the sums between them worked out in code ([YearFacts]), after the passages. On in the app from Plan R5 Task 7.
     */
    private val yearFacts: Boolean = false,
    /**
     * The owl's reaction topics. With them and [dense], a question the lexicon doesn't place ([OwlReaction.lexical]) is
     * embedded first, before the model loads, and its vector is the dense query too when that is the question alone.
     * Without them (or when the provider has none for the installed embedder), such a question reacts as
     * [ReactionTopic.DEFAULT].
     */
    private val reactions: (suspend () -> TopicCentroids?)? = null,
    /**
     * Ask Preview's medical rule (Neal, 2026-11-02): a medical question's reply is written out of sight, with no
     * [ResearchEvent.Partial]s, and an answer becomes the cited passages' own sentences ([MedicalQuotes]). A decline
     * stays a decline. On in the app; the eval harness leaves it off to score the prose.
     */
    private val medicalQuotes: Boolean = false,
) {
    /**
     * [stop] is polled after each chunk of the reply; when it turns true the reply so far is checked and finished.
     *
     * With dense retrieval the search by meaning runs first, before the model loads, and its titles come out as
     * [ResearchEvent.LookingIn]. Not alongside the model: on the Pixel 6 the vector scan took ~8 s instead of ~0.9 s
     * while the model loaded and rewrote (run ob-looking-1, 2026-11-02); the GPU and CPU share memory bandwidth.
     */
    fun run(request: ResearchRequest, stop: () -> Boolean = { false }): Flow<ResearchEvent> = flow { answer(request, stop) }

    private suspend fun FlowCollector<ResearchEvent>.answer(request: ResearchRequest, stop: () -> Boolean) {
        var questionVector: FloatArray? = null
        val topic = OwlReaction.lexical(request.question, request.priorQuestion) ?: run {
            val centroids = if (dense == null) null else reactions?.invoke()
            if (centroids == null) return@run ReactionTopic.DEFAULT
            questionVector = embedQuery(request.question)
            questionVector?.let(centroids::classify) ?: ReactionTopic.DEFAULT
        }
        emit(ResearchEvent.Reaction(topic))
        // The question's languages, worked out once: they pick every word list below (languages spec §6.2).
        val languages = ResearchLanguage.of(request.question)
        // The search by meaning, before the model loads. A follow-up that leans on the question before it is searched
        // with that question first (Plan R1 Task 3); otherwise the reaction's vector is reused.
        val byMeaning = dense?.let { finder ->
            val prior = request.priorQuestion?.takeIf { FollowUp.refersBack(request.question, languages) }
            val query = questionVector?.takeIf { prior == null } ?: embedQuery(listOfNotNull(prior, request.question).joinToString(" "))
            query?.let { searchByMeaning(finder, it) }
        }
        byMeaning?.let(::lookingInTitles)?.takeIf { it.isNotEmpty() }?.let { emit(ResearchEvent.LookingIn(it)) }
        emit(ResearchEvent.Progress(Stage.LOADING_MODEL))
        val model = try {
            models.generator()
        } catch (e: ModelUnavailableException) {
            emit(ResearchEvent.Done(Outcome.Failed(FailReason.MODEL_UNAVAILABLE, false, emptyList(), e)))
            return
        }

        val asked = MediaCues.asked(request.question, languages)
        val rewrite = rewrite(model, request, languages)
        val medical = rewrite.medical == true || Medical.looksMedical(request.question) || PersonalMedical.strong(request.question)
        if (medical) emit(ResearchEvent.Medical)
        val keywords = rewrite.keywords
        if (request.quote == null && PersonalMedical.matches(request.question, medical)) {
            // One person's own health: the app's medical copy, whatever the library holds. No search, no answer.
            emit(ResearchEvent.Done(Outcome.Declined(medical = true, keywords)))
            return
        }
        emit(ResearchEvent.Progress(Stage.SEARCHING))
        val hits: List<Passage>? = byMeaning
        val all = try {
            finder.find(Keywords.searchTerms(keywords), languages)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            emit(ResearchEvent.Done(Outcome.Failed(FailReason.SEARCH_FAILED, medical, keywords, e)))
            return
        }
        val weight = sourceWeight?.of(request.question, keywords, all + hits.orEmpty()) ?: Ranking.NO_WEIGHT
        val found = Ranking.preselect(all, keywords, config, weight, languages)
        emit(ResearchEvent.Progress(Stage.RANKING))
        val ranked = try {
            val merged = hits?.let { Ranking.hybrid(found, it, keywords, config, weight, languages) }
            val lifted = Ranking.liftMedia(merged ?: rank(request.question, found, keywords, weight, languages), found, asked)
            if (reserveNamed && Entities.asked(request.question, languages)) {
                Entities.reserve(
                    lifted, all + hits.orEmpty(), Entities.named(request.question, keywords, languages), config.small,
                    languages = languages,
                )
            } else {
                lifted
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // The embedder failed: a model failure, reported with what the pipeline knows.
            emit(ResearchEvent.Done(Outcome.Failed(FailReason.MODEL_UNAVAILABLE, medical, keywords, e)))
            return
        }

        val quote = request.quote
        // The media cap is applied before the cut, so a dropped media passage gives its place to the next one.
        val capped = Ranking.capMedia(ranked, asked, config)
        if (quote == null && capped.isEmpty()) {
            emit(ResearchEvent.Done(Outcome.Declined(medical, keywords)))
            return
        }
        val pool = (listOfNotNull(quote) + capped.filter { it != quote }).take(config.large)
        fun worked(passages: List<Passage>): String? =
            if (yearFacts && quote == null && Entities.asked(request.question, languages)) {
                YearFacts.block(request.question, keywords, passages, languages)
            } else {
                null
            }
        val wanted = pool.take(config.small)
        // Dropped for the prompt's budget, a passage can't be cited; the rest of the pool stays as candidates.
        val passages = AnswerPrompt.fit(request.question, wanted, medical, request.prior, hasQuote = quote != null, worked(wanted))
        val candidates = pool.drop(passages.size)

        emit(ResearchEvent.Reading(passages.size))
        emit(ResearchEvent.Progress(Stage.WRITING))
        val quoted = medical && medicalQuotes
        val prompt = AnswerPrompt.build(request.question, passages, medical, request.prior, hasQuote = quote != null, worked(passages))
        val streamed = streamReply(model, prompt, stop, show = !quoted)
        val reply = streamed.text
        if (reply == null) {
            // Reported here, not thrown: a failure would reach the collector without the flags the pipeline knows.
            emit(ResearchEvent.Done(Outcome.Failed(FailReason.MODEL_UNAVAILABLE, medical, keywords, streamed.cause)))
            return
        }

        val outcome = when (val check = Citations.check(reply, passages.size, languages)) {
            is CitationCheck.Valid if quoted -> {
                val text = MedicalQuotes.build(reply, passages, check.cited, languages)
                if (text.isBlank()) {
                    Outcome.Unverified(reply, passages, candidates, medical, keywords)
                } else {
                    // One piece, so the screen and "Speak answers as they arrive" have it before the turn settles.
                    emit(ResearchEvent.Partial(text))
                    Outcome.Answered(text, passages, check.cited, candidates, medical, keywords)
                }
            }
            is CitationCheck.Valid ->
                Outcome.Answered(reply, passages, check.cited, candidates, medical, keywords)
            CitationCheck.ModelDeclined -> Outcome.Declined(medical, keywords, passages, reply)
            CitationCheck.Invalid -> Outcome.Unverified(reply, passages, candidates, medical, keywords)
        }
        emit(ResearchEvent.Done(outcome))
    }

    /** A best guess with no passages: loads the model, streams the reply, and ends with [Outcome.Guessed]. */
    fun guess(question: String, priorQuestion: String?, stop: () -> Boolean = { false }): Flow<ResearchEvent> = flow {
        emit(ResearchEvent.Progress(Stage.LOADING_MODEL))
        val model = try {
            models.generator()
        } catch (e: ModelUnavailableException) {
            emit(ResearchEvent.Done(Outcome.Failed(FailReason.MODEL_UNAVAILABLE, false, emptyList(), e)))
            return@flow
        }
        emit(ResearchEvent.Progress(Stage.WRITING))
        val streamed = streamReply(model, GuessPrompt.build(question, priorQuestion), stop)
        val reply = streamed.text
        if (reply == null) {
            emit(ResearchEvent.Done(Outcome.Failed(FailReason.MODEL_UNAVAILABLE, false, emptyList(), streamed.cause)))
            return@flow
        }
        emit(ResearchEvent.Done(Outcome.Guessed(reply)))
    }

    /**
     * Streams the reply as [ResearchEvent.Partial]s (none when not [show]), polling [stop] after each chunk; returns the
     * text so far, or the failure.
     */
    private suspend fun FlowCollector<ResearchEvent>.streamReply(model: TextGenerator, prompt: String, stop: () -> Boolean, show: Boolean = true): StreamResult {
        var failure: Throwable? = null
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
                val text = soFar + chunk
                if (show) emit(ResearchEvent.Partial(text))
                text
            }
        return if (failure != null) StreamResult(null, failure) else StreamResult(text, null)
    }

    /** [text] is null when the model failed with [cause]. */
    private class StreamResult(val text: String?, val cause: Throwable?)

    /**
     * The model's keywords, or the plain keyword fallback when it times out, errors, or replies with nothing usable.
     * The medical lexicon decides whenever the model didn't say.
     */
    private suspend fun rewrite(generator: TextGenerator, request: ResearchRequest, languages: List<ResearchLanguage>): RewriteResult {
        if (rewritePlain && request.priorQuestion == null) {
            return RewriteResult(Keywords.extract(request.question, languages = languages), Medical.looksMedical(request.question))
        }
        if (rewritePlain && rewritePlainFollowUps) {
            val text = listOfNotNull(request.question, request.priorQuestion).joinToString(" ")
            return RewriteResult(Keywords.extract(text, languages = languages), Medical.looksMedical(text))
        }
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
        return RewriteResult(Keywords.extract(text, languages = languages), Medical.looksMedical(text))
    }

    /**
     * The dense hits for [request], or null for keyword ranking alone: no embedder, no vector files, or a failure.
     * Dense retrieval only ever adds; a question it can't help with is ranked as it was without it.
     */
    /** The passages nearest [query] by meaning; null when there are none or the search fails (keywords carry on alone). */
    private suspend fun searchByMeaning(finder: DenseFinder, query: FloatArray): List<Passage>? = try {
        finder.find(query).takeIf { it.isNotEmpty() }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }

    /** [text]'s query vector, or null when there is no embedder or it fails. */
    private suspend fun embedQuery(text: String): FloatArray? = try {
        models.embedder()?.embed(listOf(text), isQuery = true)?.single()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }

    private suspend fun rank(
        question: String,
        candidates: List<Passage>,
        keywords: List<String>,
        weight: (Passage) -> Double,
        languages: List<ResearchLanguage>,
    ): List<Passage> {
        if (candidates.isEmpty()) return emptyList()
        // Every candidate that passes is kept: the pool is cut to `large` after the media cap. With dense retrieval the
        // embedder is for the query alone: reranking 12 passages with it takes 4 s on the Pixel 6.
        val embedder = (if (dense == null) models.embedder() else null)
            ?: return Ranking.byLexical(candidates, keywords, config, limit = candidates.size, weight = weight, languages = languages)
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

/** How many titles [ResearchEvent.LookingIn] names. */
const val LOOKING_IN_TITLES = 3
private const val LOOKING_IN_CHARS = 48

/** The first [LOOKING_IN_TITLES] different source titles among [hits], each cut to a readable length. */
internal fun lookingInTitles(hits: List<Passage>): List<String> =
    hits.map { it.sourceTitle.trim() }.filter { it.isNotEmpty() }.distinct().take(LOOKING_IN_TITLES)
        .map { if (it.length <= LOOKING_IN_CHARS) it else it.take(LOOKING_IN_CHARS - 1).trimEnd() + "…" }
