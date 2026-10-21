# R0 checkpoint: the project 2 spike (Plan R0)

Plan: `docs/superpowers/plans/2026-10-20-plan-r0-research-spike.md`. Tuning split only; the held-out split is not run or opened.

## The baseline corpus, `r0-base`

`runs/r0-base` is `m3-tune-1` with `m3-tune-2`'s two items (unanswerable-031 and -033) laid over it. That's the final M3 build
on all 202 tuning questions. It isn't in git (runs are ignored); rebuild it from those two runs if it's missing.

## Task 1: the replay harness

`replay.py check r0-base`: the port chooses exactly what the phone chose on **202 of 202** items. The phone's `chosen` is
compared as a prefix of the port's top 4, since `AnswerPrompt.fit` can trim from the end. On `m3-tune-1` alone it's 200 of 202:
the two misses are the "score" questions, which that build still lifted a film-score card for.

Baseline, `replay.py run r0-base`:

| | |
|---|---|
| recall@4, an answer location among the 4 | 40/126 (32%) |
| recall@4, an answer quote in the 4 | 48/126 (38%) |
| an answer passage found by the search at all (the ceiling for reranking) | 77/126 (61%) |
| unanswerable with no passage over the threshold | 13/76 (17%) |

What it says for the spike:
- Graded grounded is 44%, above both recall numbers, because answers can be grounded in passages the dataset didn't list.
- **Reranking can at most reach 61%.** For 49 answerable questions the search never found an answer passage, so Task 4
  (finding more) matters at least as much as Tasks 2–3 (ordering better).
- Ranking empties only 17% of unanswerable questions. The model declines the rest (91% overall), so a ranking change that
  lets more passages through puts more weight on the answerer declining.

## Task 2: EmbeddingGemma rerank, offline

Vectors from `google/embeddinggemma-2` (Hugging Face, full precision) on the Mac's GPU; the phone runs its int4 text-only build.
`rerank.py sweep` scores `alpha·lexical + (1−alpha)·cosine` over the top `n` of preselect, for every combination of:
- passage format: `plain` (the app's, "title: none | text: …") or `titled` ("title: <title> — <heading> | text: …");
- query prompt: `search` (the app's) or `qa` (the card's question-answering task).

Each config is reported with no threshold and at the lowest threshold that empties at least the baseline's 13/76 unanswerable questions.

**The Mac against the phone** (`rerank.py phone`; 10 tuning questions × 12 preselected passages, app formats):

| Backend | Pearson r | Mean abs. diff | Same top 4 |
|---|---|---|---|
| CPU | 0.981 | 0.015 | 6 of 10 |
| GPU | 0.982 | 0.014 | 6 of 10 |

That's under the plan's 0.99, likely from the phone's int4 weights. The Mac is good enough to rank configs against each other, but
a small gain here may not carry over to the phone.

**Best rows** (recall@4 by location / by quote, of 126; unanswerable empty, of 76):

| Config | Preselect | alpha | Threshold | Location | Quote | Empty |
|---|---|---|---|---|---|---|
| Baseline (the app, lexical only) | 12 | 1.0 | lexical 0.6 | 40 | 48 | 13 |
| plain + search (app formats) | 12 | 0.0 | 0.59 | 46 | 52 | 15 |
| plain + search | 24 | 0.1 | 0.58 | 47 | 52 | 13 |
| plain + qa | 12 | 0.2 | 0.59 | 45 | 52 | 13 |
| **titled + search** | 12 | 0.2 | 0.59 | 46 | **54** | 14 |
| **titled + search** | 24 | 0.1 | 0.59 | **49** | **54** | 13 |
| titled + qa | all | 0.0 | 0.61 | 50 | 53 | 13 |
| (ceiling: an answer passage found at all) | | | | 77 | | |

What it shows:
- **The gain is small:** +6 to +9 location (5–7 points) and +4 to +6 quote (3–5 points). It's at or just under the plan's 5-point
  bar, and the Mac/phone gap could eat part of it.
- **Giving the passage its title helps** (+2 quote over plain). The query prompt doesn't matter (qa is within ±1 of search).
- **Mixing in a little lexical score (alpha 0.1–0.2) beats either alone.** Reranking more than 24 passages adds nothing.
- **A cosine threshold doesn't help the declines.** Holding the unanswerable empty rate at the baseline's needs a threshold near
  0.59, and recall barely moves between no threshold and that one. The answerer still carries the declines.
- **Latency rules it out as built.** The phone embedded a question plus 12 passages in about 3–4 s on the GPU (here: 10 questions
  in 40 s including the load; Plan 8: 4.2 s), and 24 would take about 7–8 s. Send to first words is 14 s now. The plan's rule allows
  no slowdown for a gain under 15 points.

Decision: **don't adopt rerank at question time.** The embedding is only worth its cost if passage vectors already exist when the
question is asked, so that only the query is embedded (well under a second). That fits project 2's "knowing your library" work:
an index built when a book or pack is added. Books and Classics can be embedded on the phone once; Wikipedia-size ZIMs can't be
embedded whole on the phone, so for them the vectors would have to be precomputed elsewhere or skipped. Task 4 then shows whether
an index that also *finds* passages (not just reorders them) is worth it, since 49 of 126 answers are never found at all.

Not done: re-checking a finalist with the phone's own vectors over the whole corpus (about 32k passages, roughly 3 hours on the
phone). Rerank fails on latency whatever the exact gain, so it wouldn't change the decision.

## Task 3: source-kind weighting, offline

`kinds.py sweep` tags each passage from the Classics manifest's sections:
- **fiction:** novels, drama, poetry, libretti;
- **scripture**, **philosophy**;
- **reference:** ZIM articles and cards;
- **media**.

On a question that isn't about literature, a fiction passage's score is multiplied by a weight below 1. A question counts as literary
if it has a cue word (novel, poem, says, character, verse, author, book…) or a keyword names a fiction work the search found. By that
rule, 62 of 202 tuning questions are literary. `order` uses the weight for ordering only; `filter` also thresholds the weighted score.

Fiction on factual questions counts answerable questions outside literature, who-wrote and words with a fiction passage among the 4.

| Base | Weight, mode | Location | Quote | Empty | Fiction on factual questions |
|---|---|---|---|---|---|
| App (lexical) | 1.0 | 40 | 48 | 13 | 9 |
| App (lexical) | 0.7 or lower, either mode | 40 | 48 | 13 | 5 |
| Task 2 rerank (titled, 24, alpha 0.1) | 1.0 | 49 | 54 | 13 | 14 |
| Task 2 rerank | 0.7 or lower, order | 49 | 54 | 13 | 6 |
| Task 2 rerank | 0.7 or lower, filter | 49 | 54 | 15 | 6 |

Literature, who-wrote and words recall are unchanged at every weight, so the rule doesn't cost the questions that want fiction.

What it shows:
- **Recall doesn't move at all.** Weighting removes fiction from science-002 ("how far away is the moon": *Paradise Lost*,
  *Dracula*), knots-001, multi-step-015 and multi-step-017. In each case the passage that moves up isn't an answer either, because the
  search never found the reference passage that answers it (for the moon question it found "Exploration of the Moon", not "Moon").
- **Embedding rerank pulls in more fiction** (14 against 9). Novels read as close in meaning to almost any question, so a weight
  matters more if a rerank is ever adopted. In filter mode it also empties 2 more unanswerable questions.
- The 5 that remain are questions the rule calls literary ("verse", "author", "old novels", *Hamlet*); that's defensible.

Decision: **not adopted on recall** (0 points against the 5-point bar). It's cheap and removes misleading passages from the prompt,
so it's worth carrying into project 2 as part of any rerank (weight 0.7, filter mode), and Task 6's graded run can show whether
fewer novels in the prompt means fewer wrong answers. On its own, the M3 walkthrough's "fiction ranks like reference" complaint is
a symptom: the reference passage that should outrank the novel was never found. That's Task 4 again.

## Task 4: the vocabulary gap

### 4a. A rewrite with alternates, on the phone (`r0-alt-1`)

A debug-only variant (`ResearchPipeline(rewriteAlternates = true)`, `eval.py run --rewrite alternates`):
- **The prompt** lets each keyword carry up to 2 words the source might use instead, in brackets.
- **The finder** searches every alternate on its own.
- **Ranking** scores a group (`wine|strong drink`) by its best alternative, so adding alternates can't lower a passage's score.
  Plain keywords score exactly as before; the port still reproduces the phone 202/202 with the change.

It ran on the vocab-gap, bible, words and literature groups (31 questions, E2B, greedy) against the same 31 in `r0-base`:

| | r0-base | r0-alt-1 |
|---|---|---|
| Answer passage found at all | 14 | 16 |
| Answer in the top 4 (location / quote) | 1 / 0 | 1 / 0 |
| Rewrite, median | 2.2 s | 2.3 s |
| Search, median | 8.7 s | 11.9 s |
| Send to first words, median | 13.8 s | 16.9 s |

What went wrong:
- **Brackets:** E2B used square brackets in 12 of 31 replies ("evil [malice, sin]"), which the parser didn't expect, so
  those keywords were searched broken.
- **The alternates themselves are the real problem.** They're modern synonyms or categories, not the source's words:
  - alcohol → "intoxication" (the King James Bible says wine, strong drink);
  - tattoos → "body markings";
  - wherefore → "why";
  - betimes → "when";
  - *Pride and Prejudice* → "[novel]".

  Checked offline with the brackets fixed: in only 2 of 17 questions does an alternate appear in the answer quote where no
  original keyword does (Gita "fruit", Thoreau "simplicity"). A rerun with the parser fixed could add at most 2 found answers,
  so it wasn't run.

Decision: **not adopted.** Search gets 3 s slower and E2B doesn't know the source's vocabulary well enough to bridge it with
keywords. That meets the plan's trigger for the optional dense-retrieval check (4b).

### 4b. Dense retrieval over the books, offline (`dense.py`)

The 350 EPUBs in the phone's library (Classics plus the archive.org books) were pulled and chunked as `XhtmlText` does
(400–2000 characters): **249,391 chunks**, 186 M characters. Each was embedded with `google/embeddinggemma-2` on the Mac
("title: <book> | text: …"), which took 2 h 6 min on the GPU. The raw question is the query (search prompt, no rewrite).
The comparison covers the 35 tuning questions with a book answer. A hit is an answer location or quote among the passages,
judged the same way for both searches.

| | Of 35 |
|---|---|
| Keyword search, answer in the top 4 (the app) | 3 |
| Keyword search, answer found at all | 19 |
| **Dense, answer in the top 4** | **20** (13 with the quote itself in the chunk) |
| Dense, top 12 / top 50 | 25 / 30 |
| Either one's top 4 | 21 |

Examples where dense ranks first and keyword search never found the passage:
- "is there a verse about not worrying about tomorrow" → Matthew 6:34, "Take therefore no thought for the morrow";
- "what does the art of war say about intelligence gathering" → the chapter on spies;
- "How does Captain Ahab die?" → the harpoon line running foul;
- Adam Smith's pin-maker.

Where it misses:
- **Follow-ups** ("Who's telling the story?"): the raw question has no context. A real version would embed the rewrite,
  or the question plus the previous one.
- A few bible and vocab-gap questions sit at ranks 7–50.

Caveats:
- This searches books only; in the app, dense results would compete with ZIM articles and cards for the 4 slots.
- These are the Mac's full-precision vectors; the phone's int4 embedder agreed at r ≈ 0.98 (Task 2).
- 35 questions, chosen in M3 partly *because* keyword search failed them.

Costs, for project 2:
- **Building the index on the phone is out.** About 0.3 s per chunk on the phone GPU means roughly 20 hours for this library.
  The vectors have to be built off the phone. For Classics, that means at pack-build time, shipped with the pack.
  Books a user adds one at a time (a few hundred to a few thousand chunks each) could be embedded on the phone in minutes.
- **Storage:** 768 dims as float16 is 383 MB for this library (about 1.5 KB per chunk). int8 halves that. Truncating to
  256 dims (EmbeddingGemma is trained Matryoshka-style), still to be measured for recall, brings it to about 64 MB.
- **Query time:** brute-force search over 249k float16 vectors takes 0.29 s on the Mac, plus one query embedding
  (about 0.3 s on the phone). The phone should land around 1 s or under with int8 and fewer dims. That fits the latency rule.

Decision: **this is the biggest lever the spike has found.** On book questions it takes answer-in-top-4 from 3 to 20 of 35
(+49 points), well past the 15-point bar, and it addresses the vocabulary gap directly. It isn't adopted inside the spike,
because it's engineering:
- a pack-time vector build;
- a vector file format;
- an on-phone index;
- merging dense hits with keyword hits.

It goes into the project 2 plan as the core of "knowing your library". Open questions for that plan:
- dims and quantization against recall;
- how to merge with ZIM keyword results (for example, reserve 1–2 of the 4 slots for dense book hits above a cosine floor);
- whether to embed the rewrite or the raw question;
- ZIMs stay keyword-only unless someone precomputes Wikipedia vectors.

Task 6's combined run can't include it without that engineering, so Task 6 measures ranking plus answerer only. Dense is
reported here as an offline number.

## Task 5: The answerer, on the phone

`answerers.py` replays `r0-base`'s recorded answer prompts (the same 4 passages per item) through another model with
`EvalActivity`'s replay mode (`PromptReplay`: greedy, 512 tokens, thinking stripped before citations are checked). Only the
model changes. Sanity check: E2B's replay reproduced all 189 `r0-base` replies byte for byte, so M3's grades carry over.

**Full set, E2B against E4B** (189 prompts; 126 answerable, 76 unanswerable incl. 13 that never reach a model):

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

E4B trades wrong answers for declines; it grounds no more. Four times slower to first words and 1 GB more memory: not worth it.

**Candidates, on a fixed sample of 40 prompts** (`--sample 40`, seed 5: 28 answerable, 12 unanswerable, plus the 13 that
never reach a model). Converted models were built with john-rocky/hf-to-litertlm (int4, blockwise-32 OCTAV), all on GPU unless noted:

| | grounded | not grounded | answerable declined | unverified | unanswerable declined | first words | load | memory |
|---|---|---|---|---|---|---|---|---|
| E2B (Google's build) | 11/28 | 11 | 6 | 0 | 24/25 | 4.0 s | 15 s | 2.4 GB |
| E4B (Google's build) | 10/28 | 7 | 11 | 0 | 25/25 | 16.9 s | 75 s | 3.5 GB |
| **E2B QAT** (converted) | **11/28** | 9 | 6 | 2 | **25/25** | 5.6 s | 26 s | **1.9 GB** |
| E2B stock (converted, control) | 6/28 | 4 | 18 | 0 | 25/25 | 3.8 s | 23 s | 2.0 GB |
| Fable 5 distill of E2B (ermiaazarkhalili) | 3/28 | 16 | 0 | 14 | 13/25 | 4.8 s | 23 s | 2.0 GB |
| Opus distill of E2B (armand0e) | not graded | | 2 | 24 | 17/25 | 60 s | 22 s | 1.8 GB |
| Fable 5 distill of Qwen3-4B (Parable) | 9/28 | 6 | 11 | 2 | 25/25 | 108 s | 76 s | 2.4 GB |
| Opus distill of Qwen3.5-2B (Jackrong), CPU | not graded | | 5 | 24 | 13/25 | 30 s | 30 s | 3.1 GB |
| Qwen3.5-2B (litert-community), CPU | not graded | | 20 | 11 | 18/25 | 30 s | 59 s | 4.0 GB |
| Ministral-3-3B (litert-community) | stopped after 4 | | | | | 34–62 s | 74 s | 1.4 GB |

Not graded: disqualified on the other columns first. Qwen3.5-2B doesn't fit the Pixel 6 GPU (killed during load), so both Qwen3.5
models ran on CPU.

What we learned:
- **Nothing beats E2B on quality.** Every model fails the same questions (Gilbert and Sullivan for Matthew 6:34, the opium-den
  line for Holmes's first words, Koch's bacillus as the disease's name): the misses are retrieval and passage reading.
- **Frontier distills don't help here.** Reasoning distills think before answering: 60–110 s to first words, and often all
  512 tokens spent thinking (empty replies). The non-thinking Fable distill of E2B lost the base model's instruction
  following: it never declines, invents details and loops. Thinking traces taught Parable to decline carefully, not to read
  passages better. All of them also carry the Claude-output terms-of-service risk for the catalog.
- **Qwen3.5 echoes the prompt** ("Passages: [1]…", "Earlier in this conversation…") on the phone, stock and distill alike;
  likely its template in LiteRT-LM. Not pursued.
- **The converter's int4 hurts plain E2B** (control: 18 answerable declined against 6 for Google's build). **QAT recovers it**
  under the same converter (6 declined, 11 grounded) and uses 0.5 GB less memory than Google's E2B, but our file is 4.1 GB
  against Google's 2.0 GB and 1.6 s slower to first words.

Decision: **keep E2B as the answerer.** E4B's place as Think deeper is left to Task 7: it grounds no more, but declines
more safely. For project 2: a Google-built (or Google-recipe) LiteRT-LM file of the QAT checkpoint is worth chasing for the memory
saving at equal quality. The 40-item sample is small: differences of 1–2 are noise.

## Task 6: the combined run

Tasks 2–5 adopted nothing (no ranking change met the 5-point bar; E2B stays the answerer), so the only change left to
combine is Task 3's fiction weighting, which was kept aside to see whether fewer novels among the passages means fewer
wrong answers. It runs on the phone behind debug flags: `ResearchPipeline(sourceWeight =)` (null in the app), the debug-only
`FictionWeight` (0.7 on fiction books for questions that aren't about literature, ported from `kinds.py`), and
`eval.py run --fiction runs/checkpoints/r0/fiction.txt` (285 fiction book ids).

**Library drift.** A Hermeneutics Stack Exchange pack was added to the phone after `m3-tune-1` (= r0-base). It now feeds
Bible and theology questions and changed the passages of two control questions, so the weighting's effect can't be
measured against `m3-tune-1`. Instead:
- `r0-combined`: all 202 tuning questions on today's library, weighted.
- Replaying its `found.jsonl` offline (`kinds.lexical_ranker` at 0.7 and 1.0) lists the 16 questions whose 4 passages the
  weighting changed. The port agrees with the phone's chosen passages on 201 of 202. `words-002` was ranked unweighted on the
  phone though `FictionWeight` returns 0.7 for its inputs in a unit test; unexplained, and it changes no outcome.
- `r0-combined-base`: those 16 on the phone unweighted. The other 186 get the same passages either way.

| | m3-tune-1 | r0-combined (weighted) | same library, unweighted |
|---|---|---|---|
| answerable grounded | 56/126 (44%) | 57/126 (45%) | 57/126 (45%) |
| answerable not grounded | 41 | 39 | 41 |
| unanswerable declined | 70/76 (92%) | 69/76 (91%) | 69/76 (91%) |
| Think deeper grounded | 3/12 | 4/12 | |
| recall@4 | 32% | 29% | |
| send to first words, median / p90 | 14.6 / 19.3 s | 17.4 / 23.7 s | |

On the 16, the weighting turned two wrong answers into declines (multi-step-008: "Lincoln was already president", citing
*Main Street*; knots-001) and changed no other outcome. It costs nothing measurable (ranking stays 7 ms median).

The slower first words are the library, not the change: median search went from 8.9 s to 10.9 s with the new pack
(rewrite 2.5 → 2.8 s). Recall@4 fell for the same reason (new passages compete), and grounded held.

Verdict: **the fiction weighting misses the adoption bar** (+0 grounded, not +5). It's a small, free safety gain (2 of 126
fewer confidently cited wrong answers); worth carrying into project 2 as a ranking default only alongside the bigger
retrieval work, not on its own. **Project 2's starting point is 45% answerable grounded, 91% unanswerable declined, on the
tuning split with today's library** (a tuning number, not a pass).

Graded replies: 44 new for `r0-combined` (24 grounded, 20 not), 1 for `r0-combined-base`. Most of the run's replies matched
earlier grades by hash.
