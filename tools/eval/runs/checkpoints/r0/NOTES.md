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
