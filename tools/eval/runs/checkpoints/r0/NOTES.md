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
