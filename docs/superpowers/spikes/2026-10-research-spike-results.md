# Research Spike Results — project 2 (Plan R0)

Date: 2026-10-21 · Plan: `docs/superpowers/plans/2026-10-20-plan-r0-research-spike.md` · Device: Pixel 6 (8 GB), LiteRT-LM 0.18.0 ·
Working notes with every run's detail: `tools/eval/runs/checkpoints/r0/NOTES.md`.

All numbers are on the **tuning split** (202 questions: 126 answerable, 76 unanswerable). The held-out split was not run or
opened. Project 2 must write a fresh held-out set (a separate subagent writes and grades it) before any pass-bar claim.

## Summary

- **Starting point for project 2: 45% answerable grounded (57/126), 91% unanswerable declined (69/76),** on today's phone
  library, E2B, the app's ranking. Plan 9's bar is 80% / 90%.
- **Nothing tried in the spike is adopted as is.** Each change either missed the 5-point bar or broke the latency rule.
- **The one big lever is dense retrieval over the books.** An index built ahead of time takes book questions from 3 to 20 of 35
  with the answer in the top 4. It's engineering, not a setting, so it becomes the core of project 2's "knowing your library".
- **Keep E2B as the answerer.** No Hugging Face model or frontier distill did better on the Pixel 6, and most were much slower.
- **Fine-tuning isn't the next step.** 49 of the 69 failed answerable questions fail before the model sees the answer.

## Where the failures are (the combined run)

The 126 answerable questions in `r0-combined`, by whether retrieval put an answer passage in front of the model:

| Answer passage | Grounded | Answered, not grounded | Declined | Total |
|---|---|---|---|---|
| among the 4 chosen | 35 | 15 | 5 | 55 |
| found by search, not chosen | 8 | 9 | 5 | 22 |
| never found by search | 14 | 15 | 20 | 49 |
| **Total** | **57** | **39** | **30** | 126 |

"Grounded" without the listed answer passage happens: some answers are supported by passages the dataset didn't list.

Failures (69): **35 never found, 14 found but not chosen, 20 with the answer in the 4** (15 wrong, 5 declined; all 5
declines are multi-step). With today's search, a perfect ranker and a perfect answerer would still top out near 61%
(77/126). The 80% bar can't be reached without finding more.

## Task 1: the replay harness

`tools/eval/replay.py` ports `Ranking` and `LexicalScore` to Python and replays recorded `found.jsonl` corpora. It chooses
exactly what the phone chose on **202 of 202** `r0-base` items (`m3-tune-1` with `m3-tune-2`'s two items), and on 201 of 202 in
the Task 6 run (see Task 6).

Baseline (`r0-base`):

| | |
|---|---|
| recall@4, an answer location among the 4 | 40/126 (32%) |
| recall@4, an answer quote among the 4 | 48/126 (38%) |
| an answer passage found by the search at all | 77/126 (61%) |
| unanswerable with no passage over the threshold | 13/76 (17%) |

## Task 2: EmbeddingGemma rerank (offline)

`alpha·lexical + (1−alpha)·cosine` over the preselected passages, with Mac vectors from `google/embeddinggemma-2`.
The Mac against the phone's int4 embedder: Pearson r 0.98 (under the plan's 0.99), with the same top 4 on 6 of 10 questions.

| Config | Preselect | alpha | Threshold | Location | Quote | Empty |
|---|---|---|---|---|---|---|
| Baseline (lexical only) | 12 | 1.0 | lexical 0.6 | 40 | 48 | 13 |
| plain + search (app formats) | 12 | 0.0 | 0.59 | 46 | 52 | 15 |
| plain + search | 24 | 0.1 | 0.58 | 47 | 52 | 13 |
| plain + qa | 12 | 0.2 | 0.59 | 45 | 52 | 13 |
| titled + search | 12 | 0.2 | 0.59 | 46 | 54 | 14 |
| **titled + search** | 24 | 0.1 | 0.59 | **49** | **54** | 13 |
| titled + qa | all | 0.0 | 0.61 | 50 | 53 | 13 |

**Not adopted.**
- **Gain:** +5 to +7 points, at the bar's edge, and the Mac/phone gap could eat part of it.
- **Latency:** embedding 12 passages at question time costs 3–4 s on the phone GPU, and 24 would take 7–8 s. The rule allows no
  slowdown for a gain under 15 points.
- **What we learned:** a passage's title helps the embedding, and a little lexical score mixed in (alpha 0.1–0.2) beats either
  score alone. A cosine threshold doesn't help the declines.

## Task 3: source-kind weighting (offline)

Fiction (Classics novels, drama, poetry and libretti) is weighted below 1 on questions that aren't about literature. A question
is about literature if it has a cue word or a keyword names a fiction work the search found; 62 of 202 are.

| Base | Weight, mode | Location | Quote | Empty | Fiction on factual questions |
|---|---|---|---|---|---|
| App (lexical) | 1.0 | 40 | 48 | 13 | 9 |
| App (lexical) | ≤ 0.7, either mode | 40 | 48 | 13 | 5 |
| Task 2 rerank (titled, 24, 0.1) | 1.0 | 49 | 54 | 13 | 14 |
| Task 2 rerank | ≤ 0.7, order | 49 | 54 | 13 | 6 |
| Task 2 rerank | ≤ 0.7, filter | 49 | 54 | 15 | 6 |

**Not adopted on recall.** It doesn't move recall. When a novel drops out, the passage that replaces it isn't an answer either:
the reference passage that should outrank the novel was never found. An embedding rerank pulls in more fiction (14 against 9),
so the weight matters more if a rerank is ever adopted. Task 6 measured the graded effect.

## Task 4: the vocabulary gap

### 4a. A rewrite with alternates (on the phone, 31 questions)

| | r0-base | r0-alt-1 |
|---|---|---|
| Answer passage found at all | 14 | 16 |
| Answer among the 4 (location / quote) | 1 / 0 | 1 / 0 |
| Rewrite, median | 2.2 s | 2.3 s |
| Search, median | 8.7 s | 11.9 s |
| Send to first words, median | 13.8 s | 16.9 s |

**Not adopted.** E2B's alternates are modern synonyms, not the source's words:
- alcohol → "intoxication", where the King James Bible says wine, strong drink;
- wherefore → "why";
- *Pride and Prejudice* → "novel".

Fixing the bracket parsing could add at most 2 found answers. Search got 3 s slower.

### 4b. Dense retrieval over the books (offline)

The library's 350 EPUBs (Classics and archive.org) were cut into 249,391 chunks, as `XhtmlText` cuts them, and embedded on the
Mac. The raw question is the query. The test set is the 35 tuning questions with a book answer.

| | Of 35 |
|---|---|
| Keyword search, answer among the 4 (the app) | 3 |
| Keyword search, answer found at all | 19 |
| **Dense, answer among the 4** | **20** (13 with the quote itself in the chunk) |
| Dense, top 12 / top 50 | 25 / 30 |

Dense finds what keywords can't:
- "a verse about not worrying about tomorrow" → Matthew 6:34;
- the spies chapter of *The Art of War*;
- Ahab's death;
- Smith's pin-maker.

It misses follow-ups, whose raw question has no context. The caveats:
- it's books only, so in the app dense hits would compete with articles for the 4 slots;
- the vectors are the Mac's, at full precision;
- the 35 questions were picked in M3 partly because keyword search failed them.

**+49 points on book questions: the spike's biggest lever.** Not adopted inside the spike, because it needs engineering (below).

## Task 5: the answerer (on the phone)

Each model was replayed on the same recorded answer prompts, so only the model changes. E2B's replay matched all 189 of the
original replies byte for byte.

**Full set, E2B against E4B** (189 prompts):

| | E2B | E4B |
|---|---|---|
| grounded | 56/126 | 54/126 |
| answered, not grounded | 41 | 26 |
| answerable declined | 29 | 46 |
| unanswerable declined | 69/76 | 75/76 |
| multi-step grounded | 2/20 | 3/20 |
| model first words, median | 4.2 s | 16.3 s |
| send to first words, median | 16.2 s | 28.4 s |
| load | 15 s | 75 s |
| peak memory | 2.4 GB | 3.5 GB |

**Candidates, on a fixed 40-prompt sample** (28 answerable, 12 unanswerable, plus 13 that never reach a model). The converted
models were built with john-rocky/hf-to-litertlm (int4), on the GPU unless noted:

| | grounded | not grounded | answerable declined | unverified | unanswerable declined | first words | load | memory |
|---|---|---|---|---|---|---|---|---|
| E2B (Google's build) | 11/28 | 11 | 6 | 0 | 24/25 | 4.0 s | 15 s | 2.4 GB |
| E4B (Google's build) | 10/28 | 7 | 11 | 0 | 25/25 | 16.9 s | 75 s | 3.5 GB |
| E2B QAT (converted) | 11/28 | 9 | 6 | 2 | 25/25 | 5.6 s | 26 s | 1.9 GB |
| E2B stock (converted, control) | 6/28 | 4 | 18 | 0 | 25/25 | 3.8 s | 23 s | 2.0 GB |
| Fable 5 distill of E2B | 3/28 | 16 | 0 | 14 | 13/25 | 4.8 s | 23 s | 2.0 GB |
| Opus distill of E2B | not graded | | 2 | 24 | 17/25 | 60 s | 22 s | 1.8 GB |
| Parable (Fable 5 distill of Qwen3-4B) | 9/28 | 6 | 11 | 2 | 25/25 | 108 s | 76 s | 2.4 GB |
| Opus distill of Qwen3.5-2B, CPU | not graded | | 5 | 24 | 13/25 | 30 s | 30 s | 3.1 GB |
| Qwen3.5-2B, CPU | not graded | | 20 | 11 | 18/25 | 30 s | 59 s | 4.0 GB |
| Ministral-3-3B | stopped after 4 | | | | | 34–62 s | 74 s | 1.4 GB |

**What went wrong:**
- **The reasoning distills** spend 60–110 s, often all 512 tokens, thinking.
- **The non-thinking Fable distill of E2B** lost E2B's instruction following: it never declines.
- **Qwen3.5** echoes the prompt on the phone and doesn't fit the GPU.
- **The converter's int4** hurts stock E2B (18 declined against 6). QAT recovers it at 0.5 GB less memory, but the file is
  4.1 GB against Google's 2.0 GB.
- **Licensing:** all the Claude distills carry terms-of-service risk for the catalog.
- **Shared misses:** every model fails the same questions, so the misses are retrieval and passage reading, not the model.

**Decision: keep E2B as the answerer and E4B as Think deeper.** E4B grounds no more, but it gives 15 fewer wrong answers and
declines 75 of 76 unanswerable questions, which suits a slower, deliberate mode.

## Task 6: the combined run (on the phone)

Since Tasks 2–5 adopted nothing, the combined run is today's app plus Task 3's fiction weighting (0.7, filter mode). The
weighting runs behind a debug flag. A Hermeneutics Stack Exchange pack was added to the phone after the baseline, so the
weighting's own effect was measured on today's library: all 202 questions weighted, then unweighted re-runs of the 16 questions
whose passages it changes (found by offline replay; the other 186 get the same passages either way).

| | m3-tune-1 (old library) | Weighted (today's library) | Unweighted (today's library) |
|---|---|---|---|
| answerable grounded | 56/126 (44%) | **57/126 (45%)** | 57/126 (45%) |
| answerable, answered wrongly | 41 | 39 | 41 |
| unanswerable declined | 70/76 (92%) | 69/76 (91%) | 69/76 (91%) |
| Think deeper grounded | 3/12 | 4/12 | — |
| recall@4 (location) | 32% | 29% | — |
| send to first words, median / p90 | 14.6 / 19.3 s | 17.4 / 23.7 s | — |

- **What the weighting changed:** it turned 2 wrong answers into declines. One was "Lincoln was already president", cited to the
  novel *Main Street*; the other was knots-001. It changed nothing else and costs no measurable time.
  **Not adopted on its own** (+0 grounded); carry it as a default alongside project 2's retrieval work.
- **First words slowed with the library, not with the weighting:** median search went from 8.9 s to 10.9 s after the new pack.
  Per-pack search cost grows with the library, which matters for "knowing your library".
- **One unexplained replay mismatch:** words-002 was ranked unweighted on the phone, though the weighting returns 0.7 for its
  inputs in a unit test. It changes no outcome.

## What's adopted, against the constraints

| Change | Gain (bar: +5) | Unanswerable declined (bar: ≥ 89%) | Latency (bar: no slower than 14 s) | Verdict |
|---|---|---|---|---|
| EmbeddingGemma rerank at question time | +5 to +7 recall | holds | +3–8 s | ❌ latency |
| Fiction weighting | +0 recall, +0 grounded, 2 fewer wrong | holds | +0 | ❌ alone; carry along |
| Rewrite alternates | +0 recall | not measured | +3 s | ❌ |
| E4B as the answerer | −2 grounded | 99% | +12 s | ❌ (stays Think deeper) |
| Hugging Face candidates | ≤ +0 grounded | | slower or equal | ❌ |
| **Dense book index (built ahead of time)** | **+49 points on book questions** | not measured | about +1 s (estimated) | ✅ for project 2, as engineering |

## Fine-tuning verdict

**Not now.** Of 69 failed answerable questions, 49 fail in retrieval, and only 20 had the answer in front of the model. Even
a perfect answerer would reach about 61% on today's retrieval. Retrieval comes first. Fine-tuning becomes worth revisiting once
dense retrieval raises the share of questions with the answer in the 4, because the model's misses then matter more.

If it's done later:
- **What to teach:** the 15 wrong answers with the answer present are mostly:
  - multi-step date arithmetic, such as "33 years from 1859 to 1882" and Noah's age at the Flood taken as his lifespan;
  - misread passages, such as a translator's role or a height read off the wrong line;
  - citing the wrong passage for a claim.
- **Data:** grounded question–passage–cited answer triples drawn from the library's own packs, with date arithmetic among
  them. Generate them with a model whose outputs may be used for training (Gemma, not Claude, per the terms-of-service risk
  in Task 5). Grade them with the same rubric.
- **Prerequisite:** a conversion path that doesn't lose quality. Task 5's control shows a third-party int4 conversion cost stock
  E2B 5 of 11 grounded answers. Starting from the QAT checkpoint fixed that.

## Knowing your library: what an index built at download time would cost and buy

From Task 4b (books only):
- **Buys:** answer among the 4 for 20 of 35 book questions, against 3 today. It bridges the vocabulary gap ("not worrying about
  tomorrow" → "take no thought for the morrow") that keyword rewriting couldn't (Task 4a).
- **Build cost:**
  - Embedding the phone's 249k book chunks on the phone would take about 20 hours, so Classics vectors have to be built at pack
    time and shipped with the pack.
  - A book a user adds (hundreds to a few thousand chunks) can be embedded on the phone in minutes.
  - Wikipedia-size ZIMs stay keyword-only unless someone precomputes their vectors.
- **Storage:**
  - 768 dims as float16: 383 MB for this library, about 1.5 KB a chunk.
  - int8: about half that.
  - 256 dims (Matryoshka): about 64 MB; its recall is still to be measured.
- **Query cost:** one query embedding (about 0.3 s on the phone) plus a brute-force search (0.29 s over 249k vectors on the Mac),
  so about 1 s or under on the phone with int8 and fewer dims. That fits the latency rule.
- **Open questions for the project 2 plan:**
  - dims and quantization against recall;
  - merging dense hits with ZIM keyword hits, for example 1–2 of the 4 slots reserved for dense book hits above a cosine floor;
  - embedding the rewrite or the raw question, since follow-ups need context;
  - the vector file format and the pack-builder step.

## Left behind

- **Debug-only code:**
  - `EvalActivity` modes for the rewrite variant, prompt replay and fiction weighting;
  - `PromptReplay`, `FictionWeight`, and the `ResearchBenchmarkActivity` embedding check.
- **Core code:** two `ResearchPipeline` parameters (`rewriteAlternates`, `sourceWeight`) that default off and are set only from
  debug code. Release behavior is unchanged: with them off, the replay port matches the phone's chosen passages.
- **Eval tools:** `replay.py`, `rerank.py`, `kinds.py`, `dense.py` and `answerers.py`, plus the grades in `grades/grades.jsonl`.
- **Cleanup:** the candidate models were removed from the phone and the Mac (the converted bundles, the Hugging Face
  downloads, the converter and its caches). `~/.cache/huggingface` was left alone.
