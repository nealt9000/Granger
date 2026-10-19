# Held-out checkpoint: the stop path (Plan 9, Task 9)

`heldout-1`: the 100 held-out questions on the Pixel 6, with the tuned build (tune-5 configuration, `bd72c3a` + `dfeed99`) and
`--guesses both`. A separate subagent graded it blind with `RUBRIC.md`. The tuning side has seen only these totals
(`eval.py report heldout-1 --totals-only`).

## Numbers

| Metric | Held-out | Tuning split (tune-5) | Bar |
|---|---|---|---|
| Unanswerable declined | **92%** (35/38) | 91% (69/76) | ≥ 90% ✅ |
| Answerable grounded | **37%** (23/62) | 45% (57/126) | ≥ 80% ❌ |
| Medical flag accuracy / recall | 96% / 95% | 97% / 98% | recall aim 95% |
| Think deeper grounded (E4B, multi-step) | 0/7 | 3/12 | ≥ 80% ❌ |
| Best guess E2B: confidently wrong / right or partly | 20% / 71% | 14% / 80% (baseline) | ≤ 10% / ≥ 60% ❌ |
| Best guess E4B: confidently wrong / right or partly | 13% / 82% | 11% / 88% (baseline) | ≤ 10% / ≥ 60% ❌ |
| Send to first words, median | 21.6 s* | 14.0 s | 9 s |

\* The held-out run also wrote Best guesses with both models after each decline. That swaps E4B in and out, so the next question
reloads E2B. The tuning runs had no guesses, so they don't show this. Plain Research latency is the tune-5 figure.

The 8-point drop from tuning to held-out is overfitting, partly the lead bonus and the named-work rules. It is smaller than the
gap to the bar.

## Decisions taken by the plan

- **`evalPassed` stays false** for E2B and E4B. The catalog is not re-signed or pushed.
- **`Features.RESEARCH` stays `BuildConfig.DEBUG`.** Research is in debug builds only, as the release gate (Task 11) set it.
- **`BEST_GUESS_USES_LARGE` stays `true`.** Neither model meets the good-enough bar, and E4B is better on both measures (13% vs 20%
  confidently wrong). The button stays labelled "take it with a grain of salt".

## What was tried (tuning split, `runs/checkpoints/tuning/NOTES.md`)

| Run | Unanswerable declined | Answerable grounded |
|---|---|---|
| baseline | 89% | 36% |
| tune-3 | 91% | 44% |
| tune-5 | 91% | 45% |

Changes, in order:
1. Answer prompt (answer first, combine facts, prose declines count as declines; plain decline rule; passages about another
   year or person don't answer).
2. Personal medical questions decline, with wider medical detection: flag recall from 57% to 98%.
3. **Greedy decoding.** The model sampled at random by default, so runs couldn't be compared. This also cut the rewrite from 9.9 s to 2.7 s.
4. Rewrite in the source's own wording, with meta-words dropped.
5. Bibliography sections give no passages.
6. Book search ranked by BM25. It used to return chunks in index order, so the King James Bible gave 0–1 passages for 9/10 Bible questions.
7. Ranking without retrieved-set idf, with a lead bonus and a cap per article. Quote-level recall@4 went from 26/99 to 43/99 offline.
8. Threshold 0.6 from a sweep.
9. A named work is searched inside.

Real bugs fixed on the way:
- the cold-start "model won't load" race;
- the eval screen not loading the catalog;
- a leftover eval screen swallowing the run intent.

## Why it stops short

1. **Vocabulary gap.** Keyword search can't connect "alcohol" to "wine, strong drink", "tattoos" to "cuttings… marks", or "taxes" to
   "tribute". The small model's rewrite doesn't supply the source's words reliably.
2. **The small model doesn't reason over passages.** On multi-step questions the right articles are chosen 15 times in 20, but E2B
   rarely combines the facts correctly. Think deeper (E4B) grounds 0/7 on held-out.
3. **Cites without answering.** Some answers cite the right passage but leave out the fact asked for, or cite a passage that doesn't say
   the claim.
4. **Retrieval ceiling.** On the tuning split the passage holding the answer is retrieved for about half the questions. Ranking already
   gets about two thirds of those into the top 4.

## Options for Neal

1. **Turn on embedding rerank (8f, EmbeddingGemma).** Embeddings bridge vocabulary gaps (alcohol ≈ wine) that keywords can't. It costs
   latency and memory on the phone. It can be tried offline first on the recorded corpora (`found.jsonl`), as the lexical work was.
2. **Source-vocabulary expansion for named works.** When a question names a work, ask the small model "what words would *the King James
   Bible* use for alcohol?" and search with those. This is cheap, about one extra rewrite (~3 s), and aimed squarely at failure 1.
3. **Answer with E4B by default** on phones that can run it, accepting a 60–80 s answer. E4B's grounding as the main answerer hasn't
   been measured. One eval run would tell.
4. **Re-scope the bar.** For example, ship Research as "find the passages" (search plus cited excerpts, with the model's answer
   secondary), or measure "the answer passage is shown" instead of "the model's sentence is grounded". The decline bar already passes.
5. **Fine-tune** E2B on grounded-answer data. This is the largest effort, and outside v1.
6. **Ship as is, debug only, and come back after Media**, as planned. Research stays a debug feature, and the eval harness, datasets,
   grades and recorded corpora are ready for the next attempt.

The next planning session already has Neal's question on how the models become expert on each user's own library. Options 1 and 2
belong there.
