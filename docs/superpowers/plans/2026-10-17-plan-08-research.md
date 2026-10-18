# Plan 8: Research (was Ask / Inquire & Abstract) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** **Research**, the on-device librarian, answers a question from the user's library with numbered citations, or says "Not in your library." It works over ZIM articles, e-books and the library's metadata. Each chat is a kept thread with follow-ups, listed in the rail's Recent section. A chat can start from text selected in either reader. Think deeper reruns an answer on the large model.

**Architecture:** A new pure-Kotlin JVM module, `:core:research`, holds the logic that Plan 9's desktop eval harness will reuse: keyword fallback, the rewrite and answer prompts, HTML passage splitting, lexical scoring, ranking and the decline threshold, citation validation, and `ResearchPipeline`, which turns a question into a stream of events. `:core:ai` gains a `ModelManager`: one LLM resident at a time, the embedder alongside the small model, models released when idle. `:core:library` gains kept chats (Room v5) and `LibraryPassageFinder`, which finds candidate passages in ZIM full-text results, the book index, and book and pack metadata. The app adds the Research screen and its view model, the rail tool and Recent chats, the selection entry in both readers, and opening a citation at its place.

**Tech Stack:** Kotlin 2.4.21 (JVM module via `org.jetbrains.kotlin.jvm`), coroutines 1.11.0, jsoup 1.21.2, LiteRT-LM 0.18.0, Room 2.8.5, Jetpack Compose (Material 3), Robolectric 4.17, JUnit 4 + Truth.

**Spec:** `docs/superpowers/specs/2026-10-16-library-redesign-design.md` §9 (and §3 rail, §6/§7 selection), and `docs/superpowers/specs/2026-10-15-granger-v1-design.md` §2 "Ask pipeline", "ModelManager", §4 error handling. Measured numbers: `docs/superpowers/spikes/2026-10-model-spike-results.md`. Roadmap: `docs/superpowers/plans/2026-10-15-granger-v1-roadmap.md`.

## Decisions made with Neal for this plan (2026-10-17)

- **The feature is renamed "Research"** everywhere in the UI: the rail tool, both selection menus, the screen title and Readiness. In code, `INQUIRE` becomes `RESEARCH`. Spec §9 and the roadmap are updated in Task 9.
- **Chats are threads with follow-ups.** Each follow-up searches the library again, using the new question plus the previous question. The model sees the previous question and a short form of its answer. Every turn still cites or declines.
- **Research from a selection:** opens a new chat with the selection attached as a quote and the cursor in the question box. Sending with the box empty asks "Explain this passage and how it connects to the rest of my library." The quoted passage counts as source [1].

## Builder's calls in this plan (for Neal's review)

- The Research tool shows on every phone at the ASK tier or above, even before the models are installed. The screen then explains what to get, with a button to Settings → Models. The v1 spec said to hide it, but a hidden tool can't be discovered.
- Models are released 2 minutes after leaving the Research screen, or at once when the app goes to the background. Reopening costs a model load, which the screen shows as "Loading the model…".
- **Metadata grounding:** each passage reaches the model with its title, author, date and collection. Book and pack *catalogue cards* are also passages, so "which books by Darwin do I have?" can be answered and cited.
- **Cross-source connections:** at most 3 passages per source reach ranking, so several works reach the model, and the prompt asks it to point out where they bear on each other.
- Embedding rerank stays only if the Pixel 6 embeds a query plus 12 passages in ≤ 3,000 ms on its faster backend (Task 9). Otherwise ranking is lexical only, as the v1 spec allows. The thresholds are first guesses that Plan 9's eval tunes.

## Global Constraints

- Governing rule: the model only words answers. Every answer cites at least one supplied passage, or the app declines. When no passage passes the threshold, **the app declines in code and the LLM is not called.**
- Device tiers (existing `DeviceTiers`): `LIBRARY_ONLY` hides Research, `ASK` adds Research on E2B, `THINK_DEEPER` adds Think deeper on E4B. At most one LLM is resident. The embedder may co-reside only with E2B.
- Model files come from the downloads ledger: `INSTALLED` records of `ItemKind.MODEL`, with the role taken from the catalog's `ModelEntry` of the same id (`small`, `large`, `embedder`). Research needs `small`. `embedder` is optional; `large` enables Think deeper.
- LiteRT-LM context is `MAX_CONTEXT_TOKENS = 4096`. The prompt must stay ≤ 3,400 estimated tokens (chars ÷ 4) so the reply fits.
- Limits: rewrite `maxTokens = 32` with a 5,000 ms timeout, then keyword fallback; answer `maxTokens = 512`; up to 8 ZIM documents; preselect ≤ 12 passages, ≤ 3 per source; 4 passages to E2B, 8 to E4B; each passage cut to 1,200 chars in the prompt; quote cut to 1,500 chars; previous answer cut to 600 chars.
- Initial thresholds: cosine ≥ `0.40` with the embedder; lexical ≥ `0.50` without it (both are constants in `RankingConfig`). (Task 9 ruling: 0.32 with idf-weighted partial credit)
- Copy (exact):
  - Tool, menu item and screen title: "Research"
  - Input placeholder: "Ask about anything in your library"
  - Empty-quote question: "Explain this passage and how it connects to the rest of my library."
  - Decline: "Not in your library." with a **Search instead** button (opens Search with the keywords)
  - Unverified: "Couldn't answer from these passages." followed by the passages
  - Medical banner: "Not medical advice. If you can reach emergency services or a professional, do so."
  - Think deeper button: "Think deeper (about 30 s)"
  - Large-model fallback notice: "Think deeper couldn't load the large model, so this answer is from the standard one."
  - Stages: "Loading the model…" · "Searching your library…" · "Ranking passages…" · "Writing…"
  - Models missing: "Research needs the Gemma 4 E2B model. Get it in Settings → Models." button **Get models**
  - Model failed: "The model needs repair. Check it in Readiness." button **Readiness**
  - Interrupted turn: "Stopped before an answer." button **Retry**
  - Gone source: "That source is no longer in your library."
  - Readiness tier line: "Library, Research and Think deeper" / "Library and Research"
- Builds on this 5.7 GB machine: `./gradlew -Pkotlin.compiler.execution.strategy=in-process …`, one build at a time, then `./gradlew --stop`.
- Don't run `connectedDebugAndroidTest`, because it wipes the phone's packs. Never touch the phone while a personal app is in front.

## Review Focus

1. **The process dies, or the user leaves the screen, mid-answer.** Expected: the turn isn't left spinning forever. It reads "Stopped before an answer." with Retry. Pinned in Task 7 (`pendingTurnFromAnEarlierProcessShowsInterrupted`, `leavingMidAnswerMarksTheTurnInterrupted`).
2. **A library with nothing searchable:** only packs without a full-text index (Kiwix Gutenberg), or books still indexing. Expected: "Not in your library." quickly, no LLM call, no crash. Pinned in Task 3 (`noPassagesDeclinesWithoutCallingTheModel`) and Task 6 (`packWithoutFulltextIndexIsSkipped`).
3. **A cited source is removed later** (a pack deleted, an SD card pulled, a book removed). Expected: the kept chat still renders from stored passage text, and tapping the citation says "That source is no longer in your library." Pinned in Task 7 (`openingACitationWhoseSourceIsGoneSaysSo`).
4. **The model cites badly or is cut off:** `[7]` with 4 passages, no citations, or a reply stopped by the token cap or Stop. Expected: an answer appears only with valid citations; otherwise "Couldn't answer from these passages." and the passages. Pinned in Task 2 (`outOfRangeCitationIsInvalid`, `noCitationIsInvalid`) and Task 3 (`stoppedReplyWithoutCitationsFallsBackToPassages`).
5. **Prompt overflow:** 8 long book chunks, a long selection and a long previous answer. Expected: the prompt stays ≤ 3,400 estimated tokens. Pinned in Task 2 (`promptStaysUnderBudgetAtTheLimits`).

---

### Task 1: `:core:research` module: passages, keywords, rewrite, HTML passages, lexical score

**Files:**
- Modify: `gradle/libs.versions.toml` (plugin `kotlin-jvm = { id = "org.jetbrains.kotlin.jvm", version.ref = "kotlin" }`; library `kotlinx-coroutines-core = { module = "org.jetbrains.kotlinx:kotlinx-coroutines-core", version.ref = "coroutines" }`), `build.gradle.kts` (`alias(libs.plugins.kotlin.jvm) apply false`), `settings.gradle.kts` (include `:core:research`)
- Create: `core/research/build.gradle.kts` (kotlin-jvm, `jvmToolchain(17)`; `api(libs.kotlinx.coroutines.core)`, `implementation(libs.jsoup)`; tests junit, truth, coroutines-test)
- Create: `core/research/src/main/kotlin/app/granger/core/research/{Passage,Keywords,Rewrite,HtmlPassages,LexicalScore}.kt`
- Test: `core/research/src/test/kotlin/app/granger/core/research/{KeywordsTest,RewriteTest,HtmlPassagesTest,LexicalScoreTest}.kt`

**Interfaces:**
- Produces:
  - `enum class SourceKind { ARTICLE, BOOK, CARD, SELECTION }`
  - `data class SourceMeta(val author: String = "", val date: String = "", val subjects: String = "", val collection: String = "")`
  - `data class Passage(val kind: SourceKind, val sourceId: String, val sourceTitle: String, val packTitle: String = "", val location: String = "", val anchor: String? = null, val progression: Double = 0.0, val heading: String = "", val text: String, val meta: SourceMeta = SourceMeta(), val searchScore: Double = 0.0)`. `sourceId` is the pack id or book id. `location` is the entry path (ARTICLE) or spine href (BOOK).
  - `object Keywords { fun extract(question: String, max: Int = 6): List<String> }`: lowercases, drops a fixed English stopword list and words under 3 chars, dedupes, keeps order.
  - `object Medical { fun looksMedical(text: String): Boolean }`: a small fixed lexicon (symptom, dose, dosage, medication, overdose, bleeding, fracture, burn, poison, infection, fever, pregnan, allerg, chest pain, diabet, insulin, seizure, CPR, wound). It is used only when the rewrite fails.
  - `data class RewriteResult(val keywords: List<String>, val medical: Boolean)`
  - `object Rewrite { fun prompt(question: String, previousQuestion: String?): String; fun parse(reply: String): RewriteResult? }`: `parse` is null when there is no `keywords:` line with at least one keyword.
  - `data class HtmlPassage(val heading: String, val anchor: String?, val text: String)`
  - `object HtmlPassages { fun split(html: String, targetWords: Int = 200, maxWords: Int = 300): List<HtmlPassage> }`
  - `object LexicalScore { fun of(passage: Passage, keywords: List<String>): Double }`

Rewrite prompt (exact):

```
Turn the question into search keywords for a library catalogue.
Reply in exactly two lines:
keywords: <3 to 6 keywords or short phrases, comma-separated>
medical: <yes or no>
{if previousQuestion != null}Previous question: {previousQuestion}
{end}Question: {question}
```

`HtmlPassages.split` removes `script, style, sup.reference, .mw-editsection, .navbox, table, nav, footer`, and then walks `h1–h4, p, li, dd, blockquote, pre`. A heading starts a new passage and sets `heading` (its text) and `anchor`: the heading's own `id`, else the first descendant `[id]`, else the `id` of an enclosing `section`/`details`. Paragraphs accumulate until `targetWords`. A single block over `maxWords` is cut at word boundaries. Empty passages are dropped.

`LexicalScore.of`: the fraction of keywords found, case-insensitively, as whole words or phrases in `heading + text`, plus `0.1` if any keyword is in `sourceTitle`.

- [ ] **Step 1: Write the failing tests**

```kotlin
// KeywordsTest
@Test fun dropsStopwordsAndShortWords() =
    assertThat(Keywords.extract("What is the boiling point of water at altitude?")).containsExactly("boiling", "point", "water", "altitude").inOrder()
@Test fun capsAndDedupes() = assertThat(Keywords.extract("steam steam engine boiler valve piston gauge governor", max = 6)).hasSize(6)

// RewriteTest
@Test fun parsesBothLines() = assertThat(Rewrite.parse("keywords: steam engine, boiler pressure\nmedical: no"))
    .isEqualTo(RewriteResult(listOf("steam engine", "boiler pressure"), medical = false))
@Test fun medicalYes() = assertThat(Rewrite.parse("keywords: burn, first aid\nmedical: yes")!!.medical).isTrue()
@Test fun noKeywordsIsNull() = assertThat(Rewrite.parse("I'm sorry, I can't help")).isNull()
@Test fun toleratesCaseAndExtraText() = assertThat(Rewrite.parse("Keywords: Darwin, finches\nMedical: No\n")!!.keywords).containsExactly("Darwin", "finches")
@Test fun promptCarriesThePreviousQuestion() = assertThat(Rewrite.prompt("and his finches?", "who was Darwin")).contains("Previous question: who was Darwin")

// HtmlPassagesTest
@Test fun splitsAtHeadingsWithAnchors()   // <h2 id="History">History</h2><p>…</p><h2><span id="Use">Use</span></h2><p>…</p> → headings ["History","Use"], anchors ["History","Use"]
@Test fun dropsReferencesNavboxesAndTables() // "[1]", navbox text and table cell text absent from all passages
@Test fun longParagraphIsCutUnderMaxWords()  // one 700-word <p> → every passage ≤ 300 words, text rejoined == original words
@Test fun accumulatesShortParagraphsToTarget() // ten 30-word <p> → 2 passages

// LexicalScoreTest
@Test fun fractionOfKeywordsPresent() // text has 2 of 4 keywords → 0.5
@Test fun titleBonus()                // keyword only in sourceTitle → 0.1; in text and title → 1.1 for one keyword
@Test fun wholeWordsOnly()            // keyword "cat" doesn't match "concatenate"
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew -Pkotlin.compiler.execution.strategy=in-process :core:research:test; ./gradlew --stop`
Expected: FAIL (the module or classes don't exist yet).

- [ ] **Step 3: Implement the module and the five files as specified above**

- [ ] **Step 4: Run the tests to verify they pass**

Run: same as Step 2. Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add gradle/libs.versions.toml build.gradle.kts settings.gradle.kts core/research
git commit -m "Research logic module: passages, keyword fallback, rewrite prompt, HTML passages, lexical score"
```

---

### Task 2: Ranking, the answer prompt, citation validation

**Files:**
- Create: `core/research/src/main/kotlin/app/granger/core/research/{Ranking,AnswerPrompt,Citations}.kt`
- Test: `core/research/src/test/kotlin/app/granger/core/research/{RankingTest,AnswerPromptTest,CitationsTest}.kt`

**Interfaces:**
- Consumes: `Passage`, `LexicalScore` (Task 1).
- Produces:
  - `data class RankingConfig(val preselect: Int = 12, val perSource: Int = 3, val cosineThreshold: Float = 0.40f, val lexicalThreshold: Double = 0.50, val small: Int = 4, val large: Int = 8)` (Task 9 ruling: 0.32 with idf-weighted partial credit)
  - `object Ranking { fun preselect(passages: List<Passage>, keywords: List<String>, config: RankingConfig): List<Passage>; fun cosine(a: FloatArray, b: FloatArray): Float; fun byEmbedding(candidates: List<Passage>, query: FloatArray, vectors: List<FloatArray>, config: RankingConfig): List<Passage>; fun byLexical(candidates: List<Passage>, keywords: List<String>, config: RankingConfig): List<Passage> }`. `preselect` sorts by `LexicalScore` (descending), then `searchScore`, keeps ≤ `perSource` per `sourceId`, and takes `preselect`. `byEmbedding` and `byLexical` return the passages that pass the threshold, best first, at most `config.large` of them. A caller takes the first `small` for E2B.
  - `data class PriorTurn(val question: String, val answer: String)`
  - `object AnswerPrompt { fun build(question: String, passages: List<Passage>, medical: Boolean, prior: PriorTurn?, hasQuote: Boolean): String; const val DECLINE_TOKEN = "NOT IN LIBRARY"; fun estimateTokens(prompt: String): Int = prompt.length / 4 }`
  - `sealed interface CitationCheck { data class Valid(val cited: Set<Int>) : CitationCheck; data object Invalid : CitationCheck; data object ModelDeclined : CitationCheck }`
  - `object Citations { fun check(answer: String, passageCount: Int): CitationCheck; fun strip(answer: String): String }`. `check` reads `[n]` and `[n, m]` groups. It is `ModelDeclined` when the trimmed answer starts with `DECLINE_TOKEN`, `Invalid` when there are no citations or any number is outside `1..passageCount`, else `Valid`. `strip` removes the citation groups (used for the short form of the previous answer).

Answer prompt (exact; `{…}` are substitutions, `{if}` lines appear only when true):

```
You are Granger's research librarian. Answer the question using only the numbered passages from the user's library below.
Rules:
- Put the passage number in square brackets after every claim it supports, like [2]. Cite only the numbers listed.
- If the passages don't answer the question, reply exactly: NOT IN LIBRARY
- Use the source details (title, author, date, collection) when they matter to the question.
- When passages from different works bear on each other, say how, citing each.
- Keep to what the passages say. Don't add facts from memory.
{if medical}- This is a medical question. Quote the passages closely and add nothing beyond them.
{if prior}
Earlier in this conversation:
Q: {prior.question}
A: {Citations.strip(prior.answer) cut to 600 chars}
{end}
Passages:
[{n}] {sourceTitle}{ — author if any}{ (date) if any}{ · collection if any}{ · heading if any}
{text cut to 1,200 chars (or the quote cut to 1,500 chars for a SELECTION passage)}
… one block per passage, separated by a blank line …
{if hasQuote}The user selected passage [1] and is asking about it.
Question: {question}
Answer:
```

Cuts end at a word boundary and append `…`.

- [ ] **Step 1: Write the failing tests**

```kotlin
// RankingTest
@Test fun preselectCapsPerSource()           // 6 passages of source A scoring 1.0, 2 of B scoring 0.5 → 3 A + 2 B
@Test fun preselectKeepsTwelve()
@Test fun cosineOfParallelIsOne() = assertThat(Ranking.cosine(floatArrayOf(1f, 2f), floatArrayOf(2f, 4f))).isWithin(1e-6f).of(1f)
@Test fun embeddingRankDropsBelowThreshold() // sims 0.9, 0.5, 0.3 → first two, best first
@Test fun lexicalRankDropsBelowThreshold()
@Test fun nothingPassesGivesEmpty()

// AnswerPromptTest
@Test fun numbersPassagesWithTheirMetadata() // contains "[1] On the Origin of Species — Charles Darwin (1859) · Granger Classics · Chapter IV"
@Test fun medicalRuleOnlyWhenMedical()
@Test fun priorTurnIsStrippedAndCut()        // prior answer of 2,000 chars with [1] markers → "A: " line has no "[1]" and ≤ 601 chars
@Test fun quoteLineWhenQuoted()
@Test fun promptStaysUnderBudgetAtTheLimits() // 8 passages of 2,000 chars, a 5,000-char SELECTION, 3,000-char prior → estimateTokens ≤ 3_400

// CitationsTest
@Test fun validCitations() = assertThat(Citations.check("Steam expands [1] and cools [2, 3].", 4)).isEqualTo(CitationCheck.Valid(setOf(1, 2, 3)))
@Test fun outOfRangeCitationIsInvalid() = assertThat(Citations.check("It does [7].", 4)).isEqualTo(CitationCheck.Invalid)
@Test fun noCitationIsInvalid() = assertThat(Citations.check("It does.", 4)).isEqualTo(CitationCheck.Invalid)
@Test fun declineToken() = assertThat(Citations.check("NOT IN LIBRARY", 4)).isEqualTo(CitationCheck.ModelDeclined)
@Test fun stripRemovesGroups() = assertThat(Citations.strip("A [1] b [2, 3].")).isEqualTo("A b.")
```

- [ ] **Step 2: Run the tests to verify they fail** (`:core:research:test`, as in Task 1)
- [ ] **Step 3: Implement the three files**
- [ ] **Step 4: Run the tests to verify they pass**
- [ ] **Step 5: Commit** `git commit -m "Research ranking with a per-source cap, answer prompt with metadata and history, citation checks"`

---

### Task 3: `ResearchPipeline`

**Files:**
- Create: `core/research/src/main/kotlin/app/granger/core/research/ResearchPipeline.kt`
- Test: `core/research/src/test/kotlin/app/granger/core/research/ResearchPipelineTest.kt` (fakes for the three interfaces below)

**Interfaces:**
- Consumes: Tasks 1–2.
- Produces:
  - `fun interface PassageFinder { suspend fun find(keywords: List<String>): List<Passage> }`
  - `fun interface TextGenerator { fun generate(prompt: String, maxTokens: Int): Flow<String> }`
  - `fun interface PassageEmbedder { suspend fun embed(texts: List<String>, isQuery: Boolean): List<FloatArray> }`
  - `interface ResearchModels { suspend fun generator(deeper: Boolean): TextGenerator; suspend fun embedder(): PassageEmbedder? }`. `generator` throws `ModelUnavailableException` when the model can't load.
  - `class ModelUnavailableException(message: String, cause: Throwable? = null) : Exception(message, cause)`
  - `enum class Stage { LOADING_MODEL, SEARCHING, RANKING, WRITING }`
  - `data class ResearchRequest(val question: String, val quote: Passage? = null, val prior: PriorTurn? = null, val priorQuestion: String? = null, val deeperFrom: List<Passage>? = null, val medicalHint: Boolean = false)`. With `deeperFrom` set, retrieval is skipped and those passages (up to 8) go to the large model.
  - `sealed interface Outcome { val medical: Boolean; val keywords: List<String> }`. Every subclass also carries `override val medical: Boolean, override val keywords: List<String>` as its last two parameters:
    - `data class Answered(val text: String, val passages: List<Passage>, val cited: Set<Int>, val candidates: List<Passage>, val usedLarge: Boolean, …)`
    - `data class Declined(…)`
    - `data class Unverified(val text: String, val passages: List<Passage>, val candidates: List<Passage>, val usedLarge: Boolean, …)`
    - `data class Failed(val reason: FailReason, …)` with `enum class FailReason { MODEL_UNAVAILABLE }`
    - `passages` are the numbered ones in the prompt. `candidates` are the ranked passages beyond them, so `passages + candidates` is at most 8, for a later Think deeper.
  - `sealed interface ResearchEvent { data class Progress(val stage: Stage); data class Partial(val text: String); data object LargeFallback; data class Done(val outcome: Outcome) }`
  - `class ResearchPipeline(finder: PassageFinder, models: ResearchModels, config: RankingConfig = RankingConfig(), rewriteTimeoutMs: Long = 5_000) { fun run(request: ResearchRequest, stop: () -> Boolean = { false }): Flow<ResearchEvent> }`

Order in `run`:
1. `LOADING_MODEL`: `models.generator(deeper = request.deeperFrom != null)`. If the large model throws, emit `LargeFallback` and use `generator(false)`. If the small model throws, emit `Done(Failed(MODEL_UNAVAILABLE))`.
2. Without `deeperFrom`: run the rewrite (`Rewrite.prompt`, `maxTokens = 32`, `withTimeoutOrNull(rewriteTimeoutMs)`). If it fails, use `Keywords.extract(question + " " + priorQuestion)` and `Medical.looksMedical`. Then `SEARCHING`: `finder.find(keywords)`, and `Ranking.preselect`.
3. `RANKING`: if `models.embedder()` is non-null, embed the question (as query) and the candidates, then `byEmbedding`; else `byLexical`. A `quote` is always passage [1], ahead of the ranked ones and outside the threshold.
4. If there's no quote and nothing passed, emit `Done(Declined)` **without calling the generator for the answer**.
5. `WRITING`: generate with `AnswerPrompt.build(...)` (4 passages small / 8 large, quote included) and `maxTokens = 512`. Emit `Partial(accumulated)` per chunk. Stop collecting when `stop()` is true.
6. Run `Citations.check`: `Valid` gives `Answered`, `ModelDeclined` gives `Declined`, and `Invalid` gives `Unverified(passages)`.

- [ ] **Step 1: Write the failing tests**

```kotlin
@Test fun answersWithValidCitations()                    // generator yields "Water boils at 100 °C [1]." → Done(Answered(cited = {1}))
@Test fun noPassagesDeclinesWithoutCallingTheModel()     // finder returns empty → Declined; fake generator saw only the rewrite prompt (1 call)
@Test fun belowThresholdDeclines()
@Test fun invalidCitationsGiveUnverifiedWithPassages()
@Test fun stoppedReplyWithoutCitationsFallsBackToPassages() // stop() true after 3 chunks with no "[n]" → Unverified
@Test fun modelDeclineTokenIsDeclined()
@Test fun rewriteTimeoutFallsBackToKeywords()             // rewrite generator never emits; finder receives Keywords.extract(question)
@Test fun medicalFromRewrite()                           // "medical: yes" → outcome.medical
@Test fun lexicalRankingWhenNoEmbedder()
@Test fun quoteIsPassageOneEvenWithNoOtherHits()          // quote present, finder empty → Answered allowed; prompt has "[1]" = quote and the quote line
@Test fun followUpRewriteSeesPreviousQuestion()           // rewrite prompt contains "Previous question:"
@Test fun deeperSkipsRetrievalAndUsesEight()              // deeperFrom 8 passages → finder not called, prompt has [8], generator(deeper = true)
@Test fun largeLoadFailureFallsBackToSmall()              // generator(true) throws → events contain LargeFallback, then Answered(usedLarge = false)
@Test fun smallLoadFailureFails()                         // Done(Failed(MODEL_UNAVAILABLE))
@Test fun stagesInOrder()                                 // LOADING_MODEL, SEARCHING, RANKING, WRITING
```

- [ ] **Step 2: Run the tests to verify they fail**
- [ ] **Step 3: Implement `ResearchPipeline.kt`**
- [ ] **Step 4: Run the tests to verify they pass**
- [ ] **Step 5: Commit** `git commit -m "ResearchPipeline: rewrite or keyword fallback, find, rank, decline in code, cited answer or passages, Think deeper"`

---

### Task 4: `ModelManager` in `:core:ai`

**Files:**
- Create: `core/ai/src/main/kotlin/app/granger/core/ai/ModelManager.kt`
- Modify: `core/ai/src/main/kotlin/app/granger/core/ai/LiteRtTextEmbedder.kt` (add `backend: LlmBackend = LlmBackend.CPU`)
- Test: `core/ai/src/test/kotlin/app/granger/core/ai/ModelManagerTest.kt` (reuse `Fakes.kt`'s fake engine; add a fake embedder that records load/close)

**Interfaces:**
- Produces:
  - `enum class LlmSize { SMALL, LARGE }`
  - `data class InstalledModels(val small: File? = null, val large: File? = null, val embedder: File? = null)`
  - `enum class ResearchReadiness { UNSUPPORTED, MODELS_MISSING, READY }`
  - `class ModelLoadException(val size: LlmSize?, cause: Throwable) : Exception(cause)`. A null `size` means the embedder.
  - `class ModelManager(tier: DeviceTier, installed: Flow<InstalledModels>, llms: LlmEngineFactory, embedders: TextEmbedderFactory, scope: CoroutineScope, useEmbedder: Boolean, backend: LlmBackend = LlmBackend.GPU, releaseAfterMs: Long = 120_000, io: CoroutineDispatcher = Dispatchers.IO)` with:
    - `val readiness: StateFlow<ResearchReadiness>`: `UNSUPPORTED` below `ASK`; `MODELS_MISSING` without `small`; else `READY`
    - `val canThinkDeeper: StateFlow<Boolean>`: tier `THINK_DEEPER` and `large` installed
    - `suspend fun engine(size: LlmSize): LlmEngine`: under a mutex. It cancels any pending release, returns the resident engine if it's the same size, else closes the resident LLM (and the embedder when switching to LARGE) and loads the other on `io`. Any `Throwable` from loading closes the half-loaded engine and throws `ModelLoadException`.
    - `suspend fun embedder(): TextEmbedder?`: null unless `useEmbedder`, the embedder is installed, and the resident LLM isn't LARGE. A load failure gives null (logged), never an exception: ranking falls back to lexical.
    - `fun releaseSoon()`: closes everything after `releaseAfterMs` unless `engine`/`embedder` is called first
    - `fun releaseNow()`

- [ ] **Step 1: Write the failing tests**

```kotlin
@Test fun readinessFollowsTierAndFiles()          // LIBRARY_ONLY → UNSUPPORTED; ASK without small → MODELS_MISSING; with small → READY
@Test fun thinkDeeperNeedsTierAndLargeFile()
@Test fun sameSizeIsReused()                      // engine(SMALL) twice → factory called once
@Test fun swappingClosesTheOldModelFirst()        // SMALL then LARGE → small closed before large.load() (order recorded)
@Test fun largeClosesTheEmbedder()
@Test fun embedderOnlyAlongsideSmall()            // after engine(LARGE), embedder() == null
@Test fun loadFailureThrowsModelLoadExceptionAndLeavesNothingResident()
@Test fun releaseSoonClosesAfterTheDelay()        // runTest virtual time: advanceTimeBy(120_001) → closed
@Test fun useBeforeTheDelayCancelsRelease()
@Test fun embedderDisabledGivesNull()
```

- [ ] **Step 2: Run the tests to verify they fail**: `./gradlew -Pkotlin.compiler.execution.strategy=in-process :core:ai:testDebugUnitTest; ./gradlew --stop`
- [ ] **Step 3: Implement `ModelManager.kt` and the embedder backend parameter**
- [ ] **Step 4: Run the tests to verify they pass**
- [ ] **Step 5: Commit** `git commit -m "ModelManager: one LLM resident, embedder beside the small model, idle release, readiness"`

---

### Task 5: Kept chats in `:core:library` (Room v5) and recent-search rows

**Files:**
- Modify: `core/library/build.gradle.kts` (`api(project(":core:research"))`)
- Modify: `core/library/src/main/kotlin/app/granger/core/library/{Entities,Daos,LibraryDatabase,ReadingSessions}.kt`
- Create: `core/library/src/main/kotlin/app/granger/core/library/ResearchChats.kt`
- Generated: `core/library/schemas/app.granger.core.library.LibraryDatabase/5.json`
- Test: `core/library/src/test/kotlin/app/granger/core/library/{ResearchChatsTest,MigrationTest,ReadingSessionsTest}.kt`

**Interfaces:**
- Consumes: `Passage`, `SourceKind`, `SourceMeta` (Task 1).
- Produces:
  - Entities:
    - `research_chats(id INTEGER PK AUTOINCREMENT, title TEXT, createdAt INTEGER, updatedAt INTEGER)`
    - `research_turns(id INTEGER PK AUTOINCREMENT, chatId INTEGER, question TEXT, outcome TEXT, answer TEXT, medical INTEGER, usedLarge INTEGER, askedDeeper INTEGER, keywords TEXT, createdAt INTEGER)`, indexed on `chatId`. `keywords` is `"; "`-joined.
    - `research_passages(turnId INTEGER, ordinal INTEGER, role TEXT, kind TEXT, sourceId TEXT, sourceTitle TEXT, packTitle TEXT, location TEXT, anchor TEXT NULL, progression REAL, heading TEXT, text TEXT, author TEXT, date TEXT, subjects TEXT, collection TEXT, PRIMARY KEY(turnId, ordinal))`
    - `MIGRATION_4_5` creates all three, and the database becomes `version = 5`.
  - `enum class TurnOutcome { PENDING, ANSWERED, DECLINED, UNVERIFIED, FAILED, INTERRUPTED }`. The large-model fallback notice is derived from `askedDeeper && !usedLarge`.
  - `enum class PassageRole { SHOWN, CANDIDATE }`. SHOWN passages are the numbered ones given to the model, in order. CANDIDATE passages are the extras kept for Think deeper.
  - `data class ChatTurn(val id: Long, val chatId: Long, val question: String, val outcome: TurnOutcome, val answer: String, val medical: Boolean, val usedLarge: Boolean, val askedDeeper: Boolean, val keywords: List<String>, val shown: List<Passage>, val candidates: List<Passage>, val createdAt: Long)`
  - `data class ChatSummary(val id: Long, val title: String, val updatedAt: Long)`
  - `interface ResearchChats`:
    - `fun recent(limit: Int): Flow<List<ChatSummary>>`
    - `fun turns(chatId: Long): Flow<List<ChatTurn>>`
    - `suspend fun chat(chatId: Long): ChatSummary?`
    - `suspend fun create(title: String): Long`
    - `suspend fun startTurn(chatId: Long, question: String, askedDeeper: Boolean, quote: Passage?): Long`: a PENDING turn; the quote is stored as SHOWN ordinal 1
    - `suspend fun finishTurn(turnId: Long, outcome: TurnOutcome, answer: String, medical: Boolean, usedLarge: Boolean, keywords: List<String>, shown: List<Passage>, candidates: List<Passage>)`: replaces the turn's passages and bumps the chat's `updatedAt`
    - `suspend fun interruptPending(): Int`: turns every PENDING turn INTERRUPTED; the count
    - `suspend fun delete(chatId: Long)`: removes the chat, its turns and passages
  - `class RoomResearchChats(db: LibraryDatabase, now: () -> Long = System::currentTimeMillis) : ResearchChats`. The chat title is `question` cut to 60 chars at a word boundary; the caller passes it.
  - `ReadingSessions.recentSearchRows(limit: Int = 5): Flow<List<RecentSearch>>` (the timestamped rows behind `recentSearches`).

- [ ] **Step 1: Write the failing tests**

```kotlin
// ResearchChatsTest (Robolectric, in-memory db with SeedCallback, as in ReadingSessionsTest)
@Test fun turnRoundTripsWithPassagesInOrder()       // finishTurn with 4 SHOWN (ARTICLE with anchor, BOOK with progression, CARD, SELECTION) + 2 CANDIDATE → turns() equals
@Test fun quoteIsStoredAsPassageOne()
@Test fun recentIsNewestUpdatedFirst()
@Test fun finishingATurnBumpsTheChat()
@Test fun interruptPendingMarksOnlyPending()
@Test fun deleteRemovesTurnsAndPassages()

// MigrationTest
@Test fun migrate4To5KeepsBooksTabsAndAddsChats() // MigrationTestHelper: v4 db with a book, a tab and a recent search → v5 opens, rows intact, research_chats empty

// ReadingSessionsTest
@Test fun recentSearchRowsCarryTimes()
```

- [ ] **Step 2: Run the tests to verify they fail**: `./gradlew -Pkotlin.compiler.execution.strategy=in-process :core:library:testDebugUnitTest; ./gradlew --stop`
- [ ] **Step 3: Implement the entities, DAO, migration (`.addMigrations(MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)`), `RoomResearchChats` and `recentSearchRows`**
- [ ] **Step 4: Run the tests to verify they pass**, and check that the `5.json` schema was generated
- [ ] **Step 5: Commit** `git commit -m "Kept Research chats: Room v5 chats, turns and cited passages; timestamped recent searches"`

---

### Task 6: `LibraryPassageFinder`: articles, book chunks, catalogue cards

**Files:**
- Modify: `core/library/src/main/kotlin/app/granger/core/library/BookIndex.kt` (add `passages`)
- Create: `core/library/src/main/kotlin/app/granger/core/library/LibraryPassageFinder.kt`
- Test: `core/library/src/test/kotlin/app/granger/core/library/{BookIndexTest,LibraryPassageFinderTest}.kt` (fake `Library` with a fake `ZimArchive` from `Fakes.kt`; real `BookIndex` with the `:core:books` test-fixture EPUBs)

**Interfaces:**
- Consumes: `Passage`, `SourceKind`, `SourceMeta`, `HtmlPassages`, `PassageFinder` (Tasks 1, 3); `Library`, `BookIndex`, `Bookshelf`, `Shelves`.
- Produces:
  - `data class BookPassage(val book: Book, val href: String, val progression: Double, val text: String)`
  - `suspend fun BookIndex.passages(keywords: List<String>, limit: Int = 40): List<BookPassage>`. The match is the keywords OR'd (`"steam engine" OR boiler`, each quoted). A bad query gives empty. Text is re-read with the existing `chunkTexts`.
  - `class LibraryPassageFinder(library: Library, bookIndex: BookIndex, bookshelf: Bookshelf, shelves: Shelves, io: CoroutineDispatcher = Dispatchers.IO, docs: Int = 8, perPack: Int = 8, cards: Int = 3) : PassageFinder`
    - **Articles:** for each `PackStatus.OK` pack with `hasFulltextIndex`, `archive.search(keywords.joinToString(" "), perPack)`. Keep the top `docs` hits overall by `score`. `read` each one (skipping non-HTML and failures), split with `HtmlPassages`, and give each passage `kind = ARTICLE`, `sourceId = packId`, `sourceTitle = hit.title`, `packTitle = pack.title`, `location = resolved path`, `anchor`, `searchScore = hit.score`, and `meta = SourceMeta(date = pack.date, collection = …)`.
    - **Books:** `bookIndex.passages(keywords)` gives `kind = BOOK`, `sourceId = book.id`, `location = href`, `progression`, `meta = SourceMeta(book.author, book.date, book.subjects, collection)`.
    - **Cards:** books and OK packs whose title, author, subjects or description contain a keyword (case-insensitive, whole word) are scored with `LexicalScore`, and the best `cards` are kept. Their text, one `Label: value` per line with blank values left out:
      - Book: `Title`, `Author`, `Translator`, `Published`, `Subjects`, `Collection`, `In your library as: e-book`
      - Pack: `Title`, `Description`, `Date`, `Collection`, `In your library as: web pack`
    - Collection names come from `shelves.shelves.first()`: each `CollectionRow`'s packs and books map to `collection.name`.
    - A pack or book that throws is skipped and the rest still answer.

- [ ] **Step 1: Write the failing tests**

```kotlin
// BookIndexTest
@Test fun passagesMatchAnyKeyword()       // index the fixture book; passages(listOf("whale", "nonexistentword")) non-empty, text contains "whale"
@Test fun passagesCarryTheBook()
@Test fun passagesOfNothingIsEmpty()      // passages(emptyList()) and passages(listOf("\"")) → empty

// LibraryPassageFinderTest
@Test fun articlePassagesCarryPackAnchorAndCollection()
@Test fun packWithoutFulltextIndexIsSkipped()   // its archive.search is never called
@Test fun missingPackIsSkipped()
@Test fun keepsTheTopDocsOverall()              // two packs × 8 hits, docs = 8 → 8 articles read, highest scores
@Test fun bookCardForAnAuthorQuery()            // keywords ["darwin"] with a Darwin book → CARD passage with "Author: Charles Darwin" and "Collection: Granger Classics"
@Test fun throwingArchiveDoesNotSinkTheRest()
```

- [ ] **Step 2: Run the tests to verify they fail** (`:core:library:testDebugUnitTest`)
- [ ] **Step 3: Implement `BookIndex.passages` and `LibraryPassageFinder`**
- [ ] **Step 4: Run the tests to verify they pass**
- [ ] **Step 5: Commit** `git commit -m "LibraryPassageFinder: Research passages from ZIM full text, the book index and catalogue cards"`

---

### Task 7: `ResearchViewModel` and the app wiring

**Files:**
- Modify: `app/build.gradle.kts` (`implementation(project(":core:research"))`)
- Modify: `app/src/main/kotlin/app/granger/GrangerApp.kt` (container: `researchChats`, `modelManager`, `research: ResearchPipeline`; `installedModels` flow; `RERANK_WITH_EMBEDDER` constant, `true` until Task 9 decides)
- Modify: `app/src/main/kotlin/app/granger/MainActivity.kt` (`onTrimMemory(TRIM_MEMORY_UI_HIDDEN)` → `modelManager.releaseNow()`; at activity start, `appScope.launch { researchChats.interruptPending() }` once per process, next to the book scan)
- Create: `app/src/main/kotlin/app/granger/research/{ResearchViewModel,ModelsAdapter,CitationTarget}.kt`
- Test: `app/src/test/kotlin/app/granger/research/{ResearchViewModelTest,InstalledModelsTest}.kt`

**Interfaces:**
- Consumes: Tasks 3–6. `Downloads.records`, `CatalogSource.state`, `ReadingSessions.openTab`, `Bookshelf.book`, `Library.packs`.
- Produces:
  - `fun installedModelsOf(records: List<DownloadRecord>, models: List<ModelEntry>, exists: (File) -> Boolean = File::isFile): InstalledModels`: `INSTALLED` `MODEL` records are matched to a catalog `ModelEntry` by `itemId`. When an itemId has several installed records, the newest `updatedAt` wins.
  - `class ModelsAdapter(manager: ModelManager) : ResearchModels`: `generator(deeper)` maps to `engine(LARGE|SMALL)`, wrapping `ModelLoadException` as `ModelUnavailableException`; `embedder()` wraps `TextEmbedder.embed` on `Dispatchers.IO`.
  - `sealed interface CitationTarget { data class Article(val tabId: Long, val fragment: String?); data class Book(val tabId: Long, val at: BookTarget?); data object Gone }`. A null `at` opens the book where it was left.
  - `class ResearchViewModel(chatId: Long?, quote: Passage?, chats: ResearchChats, pipeline: ResearchPipeline, models: ModelManager, sessions: ReadingSessions, library: Library, bookshelf: Bookshelf, appScope: CoroutineScope)` with:
    - `data class TurnUi(val turn: ChatTurn, val partial: String?, val stage: Stage?, val largeFallback: Boolean)`
    - `data class State(val chatId: Long?, val title: String, val turns: List<TurnUi>, val running: Boolean, val readiness: ResearchReadiness, val canThinkDeeper: Boolean, val quote: Passage?, val recentChats: List<ChatSummary>)`. `recentChats` holds the last 10 and is shown when the chat is empty.
    - `var input by mutableStateOf("")`, the Compose-state field (the same pattern as `SearchViewModel.searchText`)
    - `fun send()`: a blank input with a quote sends the empty-quote question; a blank input without one does nothing. It creates the chat on the first send, titled from the question (or from "About: " + the quote's first 40 chars when the question is the default). It then calls `startTurn`, runs the pipeline with `prior` = the last ANSWERED turn and `priorQuestion` = the last turn's question, and calls `finishTurn` on `Done`. The quote is used only by the first turn.
    - `fun stop()`
    - `fun thinkDeeper(turnId: Long)`: for the last turn when it is ANSWERED or UNVERIFIED and `canThinkDeeper`. It starts a new turn with the same question, `askedDeeper = true` and `deeperFrom = shown + candidates` (quote passages excluded), cut to 8.
    - `fun retry(turnId: Long)`: re-asks an INTERRUPTED or FAILED turn's question as a new turn
    - `suspend fun open(passage: Passage): CitationTarget`
      - ARTICLE (or SELECTION/CARD from a pack): if the pack is gone or not OK, `Gone`; else `Article(sessions.openTab(packId, location, sourceTitle), anchor)`. A pack card opens its main page: `location = ""`.
      - BOOK: if the book is gone or not OK, `Gone`; else `Book(openTab(bookId, "", title), BookTarget(href, progression, TextQuote("", first ≤ 80 chars of the passage text cut at a word, "")))`.
      - A book card (or a SELECTION from a book, which has no href) opens the book where it was left: `Book(tabId, at = null)`. A SELECTION with an href goes to that place, like BOOK.
    - `fun deleteChat()`
    - `onCleared()`: cancels a running turn and marks it INTERRUPTED via `appScope`, then calls `models.releaseSoon()`
    - A turn whose job is cancelled for any reason ends INTERRUPTED, never PENDING.

- [ ] **Step 1: Write the failing tests**

```kotlin
// InstalledModelsTest
@Test fun mapsRolesByCatalogId()
@Test fun ignoresUnfinishedAndMissingFiles()
@Test fun newestInstalledVersionWins()

// ResearchViewModelTest (fake ResearchChats in memory; a ResearchPipeline over fake finder/models; ModelManager over fakes)
@Test fun firstSendCreatesTheChatTitledFromTheQuestion()
@Test fun answeredTurnIsStoredWithItsPassages()
@Test fun followUpPassesThePriorTurn()                   // the second send's rewrite prompt contains "Previous question:" and the answer prompt has "Earlier in this conversation"
@Test fun blankSendWithQuoteAsksTheDefaultQuestion()
@Test fun blankSendWithoutQuoteDoesNothing()
@Test fun quoteOnlyOnTheFirstTurn()
@Test fun stopKeepsAValidlyCitedPartial()
@Test fun leavingMidAnswerMarksTheTurnInterrupted()      // onCleared() during WRITING → stored outcome INTERRUPTED
@Test fun pendingTurnFromAnEarlierProcessShowsInterrupted() // chats.interruptPending() then open the chat → INTERRUPTED with Retry available
@Test fun thinkDeeperUsesShownAndCandidates()            // the new turn has askedDeeper, the finder isn't called, 8 passages
@Test fun thinkDeeperFallbackIsFlagged()                 // large load throws → turn usedLarge = false, askedDeeper = true; TurnUi.largeFallback
@Test fun openingACitationWhoseSourceIsGoneSaysSo()      // book removed → CitationTarget.Gone
@Test fun articleCitationCarriesTheAnchor()
@Test fun modelsMissingReadinessIsExposed()
```

- [ ] **Step 2: Run the tests to verify they fail**: `./gradlew -Pkotlin.compiler.execution.strategy=in-process :app:testDebugUnitTest --tests 'app.granger.research.*'; ./gradlew --stop`
- [ ] **Step 3: Implement the files and the container wiring.** `installedModels` = `combine(downloads.records, catalog.state.map { it.catalog?.models.orEmpty() }) { r, m -> installedModelsOf(r, m) }`. Read the real field name off `CatalogState`. `ModelManager(deviceTier, installedModels, { path, backend -> LiteRtLlmEngine(path, backend, cacheDir) }, { path -> LiteRtTextEmbedder(path, cacheDir, EMBEDDER_BACKEND) }, appScope, useEmbedder = RERANK_WITH_EMBEDDER)`, with `cacheDir = context.cacheDir.path`.
- [ ] **Step 4: Run the tests to verify they pass**
- [ ] **Step 5: Commit** `git commit -m "ResearchViewModel: threaded chats, follow-ups, stop, Think deeper, interrupted turns, opening citations"`

---

### Task 8: Research screen, rail, selection entry, rename

**Files:**
- Create: `app/src/main/kotlin/app/granger/research/{ResearchScreen,AnswerText}.kt`
- Modify: `app/src/main/kotlin/app/granger/Features.kt` (`INQUIRE` → `RESEARCH = true`)
- Modify: `app/src/main/kotlin/app/granger/GrangerNavHost.kt` (`ResearchRoute`; the `RailTool.RESEARCH` destination; `ReaderRoute(tabId, fragment: String? = null)`; `NavController.showTab(…, fragment: String? = null)`; Recent chats; selection callbacks)
- Modify: `app/src/main/kotlin/app/granger/reader/{ReaderViewModel,ReaderScreen,ReaderWebView}.kt` (the first load uses `ZimUrls.forEntry(packId, path, fragment)`; `SelectionAction.RESEARCH("Research")`; `onResearch(text, packId, path, title)`)
- Modify: `app/src/main/kotlin/app/granger/ebook/{SelectionMenu,EbookReaderScreen}.kt` (`SelectionAction.RESEARCH("Research")`; `onResearch` with the selection locator's href and progression)
- Modify: `app/src/main/kotlin/app/granger/ebook/SelectionMenu.kt` / `SelectionBridge` (`researchAvailable: Boolean`, set from `deviceTier >= ASK`; both menus filter `RESEARCH` on `Features.RESEARCH && SelectionBridge.researchAvailable`)
- Modify: `app/src/main/kotlin/app/granger/shell/{Rail,RailViewModel,GrangerShell}.kt` (`RailTool.RESEARCH("Research", Features.RESEARCH)`; `GrangerShell(showResearch: Boolean)` hides it below ASK; `RailUi.recent: List<RecentItem>`)
- Modify: `app/src/main/kotlin/app/granger/readiness/ReadinessScreen.kt` (tier copy)
- Test: `app/src/test/kotlin/app/granger/shell/RailViewModelTest.kt`, `app/src/test/kotlin/app/granger/ebook/SelectionMenuTest.kt`, `app/src/test/kotlin/app/granger/reader/ReaderViewModelTest.kt`, `app/src/test/kotlin/app/granger/research/AnswerTextTest.kt`

**Interfaces:**
- Consumes: Task 7's `ResearchViewModel`, `CitationTarget`.
- Produces:
  - `@Serializable data class ResearchRoute(val chatId: Long = 0, val quote: String? = null, val quoteSourceId: String? = null, val quoteTitle: String? = null, val quoteLocation: String? = null, val quoteProgression: Double? = null)`. `chatId = 0` means a new chat. The quote is cut to 2,000 chars before navigating. The route builds `Passage(SELECTION, …)`.
  - `sealed interface RecentItem { val at: Long; data class Search(val query: String, override val at: Long); data class Chat(val chatId: Long, val title: String, override val at: Long) }`. `RailUi(tabs, recent: List<RecentItem>)` merges `recentSearchRows(5)` and `researchChats.recent(5)` newest first and keeps 5. `RailViewModel` gains a `ResearchChats` parameter.
  - `object AnswerText { fun segments(answer: String): List<Segment> }` with `sealed interface Segment { data class Text(val text: String); data class Cite(val n: Int) }`. The screen draws `Cite` as a tappable superscript chip.

Screen layout (builder's choice where unstated):
- Top bar: "Research", plus a **New chat** action and an overflow with **Delete chat**.
- A thread of turns. Each turn has a question bubble (with a quote card above the first turn when there's a quote). If `medical`, the banner. The answer is drawn as segments: a tapped `[n]` scrolls to and briefly outlines source card n. Below it, the numbered source cards (title · author/pack · heading, the first 3 lines of text), each opening via `viewModel.open` (`Gone` gives the snackbar copy).
- Declined: the copy plus **Search instead** → `SearchRoute(keywords.joinToString(" "))`. Unverified: the copy plus the source cards. Interrupted or Failed: the copy plus **Retry** or **Readiness**.
- The last turn shows **Think deeper (about 30 s)** when the view model allows it. A deeper turn shows the fallback notice when `largeFallback`.
- While running: the stage line, and the partial answer streaming. **Stop** replaces Send.
- Readiness `MODELS_MISSING` shows the copy and **Get models** → `SourceRoute(CatalogScope.MODELS)`.
- An empty chat lists `recentChats` under "Recent chats".
- The input at the bottom, with the placeholder copy. It's focused on open when the route has a quote.

- [ ] **Step 1: Write the failing tests**

```kotlin
// AnswerTextTest
@Test fun splitsCitations() = assertThat(AnswerText.segments("A [1] b [2, 3].")).containsExactly(
    Segment.Text("A "), Segment.Cite(1), Segment.Text(" b "), Segment.Cite(2), Segment.Cite(3), Segment.Text(".")).inOrder()

// RailViewModelTest
@Test fun recentMergesChatsAndSearchesNewestFirst()
@Test fun recentKeepsFive()

// SelectionMenuTest
@Test fun researchActionShownOnlyWhenAvailable()  // SelectionBridge.researchAvailable false → no "Research" item; true → "Research" item
@Test fun noInquireLabelLeft()                    // no action label contains "Inquire"

// ReaderViewModelTest
@Test fun fragmentGoesIntoTheFirstUrl()           // ReaderViewModel(tab, fragment = "History") → state.url ends "#History"
```

- [ ] **Step 2: Run the tests to verify they fail**: `./gradlew -Pkotlin.compiler.execution.strategy=in-process :app:testDebugUnitTest; ./gradlew --stop`
- [ ] **Step 3: Implement the screen, the routes, the rail, the selection entry and the rename.** Check with `grep -rn "Inquire" app/src core` that only comments about the rename are left.
- [ ] **Step 4: Run the whole app unit-test suite and `:app:assembleDebug`**, and expect both to pass.
- [ ] **Step 5: Commit** `git commit -m "Research screen, rail tool and Recent chats, selection entry in both readers, citations open at their place; Inquire & Abstract renamed Research"`

---

### Task 9: Pixel 6 measurements, calibration, walkthrough, docs

**Files:**
- Modify: `app/src/main/kotlin/app/granger/GrangerApp.kt` (`RERANK_WITH_EMBEDDER`, `EMBEDDER_BACKEND`, and if calibrated, `RankingConfig(cosineThreshold = …)`)
- Create: `app/src/debug/kotlin/app/granger/research/ResearchBenchmark.kt`, a debug-only button on the existing spike screen (or a debug `adb shell am start` activity) that logs `Research:` lines. It measures:
  - (a) embed of a query plus 12 real passages on CPU and on GPU
  - (b) for 6 fixed questions, the time from Send to the first answer chunk, with the model already loaded
  - (c) each top passage's cosine
- Modify: `docs/superpowers/specs/2026-10-16-library-redesign-design.md` (§9 heading and text → "Research"; §3 tools; §12 table; §14 line "Ask renamed to Research everywhere; chats kept, with threaded follow-ups")
- Modify: `docs/superpowers/plans/2026-10-15-granger-v1-roadmap.md` (Plan 8 row "Research" **Done**; add "Carried forward from Plan 8" with the measured numbers and open items)

The questions for (b)/(c), against what is on the phone (Wikipedia pack + Granger Classics). Three answerable: "Who wrote Hamlet?", "What is the boiling point of water?", "What does Portia say about mercy?". Three unanswerable: "What is my bank's routing number?", "Who won the 2031 World Cup?", "What is the Wi-Fi password at the library?".

- [ ] **Step 1: Install the debug build and get the models.** Run `./gradlew -Pkotlin.compiler.execution.strategy=in-process :app:installDebug; ./gradlew --stop`. In Settings → Models, get E2B and EmbeddingGemma (and E4B, for Think deeper). Ask Neal before starting multi-GB downloads if they aren't on the phone already (`adb shell ls /sdcard/Android/data/app.granger/files/models`).
- [ ] **Step 2: Run the benchmark and record its logcat lines** (`adb logcat -s Granger:I | grep Research:`)
- [ ] **Step 3: Decide and set the constants:**
  - `RERANK_WITH_EMBEDDER = true` with the faster backend when (a) is ≤ 3,000 ms on it; else `false`.
  - If reranking, set `cosineThreshold` to the midpoint between the lowest top-passage cosine of the answerable questions and the highest of the unanswerable ones. If they overlap, keep `0.40` and note it for Plan 9.
  - Record whether (b) meets the 9.0 s target.
- [ ] **Step 4: Walk through on the phone** (phone unlocked, Granger in front). Each of these should pass:
  - The Research tool shows, and the empty screen lists no chats.
  - "Who wrote Hamlet?" answers with [n] chips; a chip outlines its card; the card opens the article or book at its place.
  - A follow-up "When was it first performed?" uses the context.
  - An unanswerable question says "Not in your library." and **Search instead** works.
  - "How do I treat a burn?" shows the medical banner.
  - Stop mid-answer.
  - Think deeper shows its wait and answers (or shows the fallback notice).
  - Select text in a book, choose Research, and send it empty: it answers about the quote.
  - The same from a Wikipedia article.
  - The chat appears in the rail's Recent and reopens intact after force-stop.
  - Kill the process mid-answer (`adb shell am kill app.granger` with the app in the background) and reopen: "Stopped before an answer." with Retry.
  - Rotate while streaming.
  - Remove a cited book, then tap its citation: the gone message.
  Fix what fails (TDD for logic fixes), and note what's left.
- [ ] **Step 5: Run the full unit-test suites** (`:core:research:test :core:ai:testDebugUnitTest :core:library:testDebugUnitTest :app:testDebugUnitTest`, one at a time, then `./gradlew --stop`). Expect all to pass.
- [ ] **Step 6: Update the spec and the roadmap** with the rename, the measured numbers, the decisions, and "Carried forward from Plan 8". Plan 9 needs: the thresholds to tune, the rerank decision, the 9 s result, and `evalPassed` still false.
- [ ] **Step 7: Commit** `git commit -m "Plan 8 done: Research measured and verified on the Pixel 6; spec and roadmap updated"`
