# Plan S1: Ask in small steps ("point, then say") — 2026-11-07

**Outcome (2026-11-07): stopped at the checkpoint.** No variant beat today's Ask (best 74 grounded / 53 declined against 83 / 51); see `docs/ask/step-back-2026-11-05.md` §10. Tasks 4–5 were not started.

Goal for the day: a better-working E2B Ask, as a debug build on the Pixel 6 (21121FDF6007JN) for Neal's evening test.

Starting point: `docs/ask/step-back-2026-11-05.md` §6–§9. E2B as a general reader of 4 passages is flat at 54–56%
grounded whatever the prompt shape or pool size, with 51/61 declines and 65% precision. Even with the answer forced in it
reaches only 76%. Retrieval tuning (question index) gained nothing significant. So today changes how E2B is asked, not what
it is given, by Neal's four rules for small models:

1. Small input
2. A specific operation
3. A strictly defined output format
4. Output that code can check

## The design

Today one prompt does everything: 4 passages (~1,300 tokens) in, a free-form cited answer out. S1 splits it:

| Step | Who | Input | Operation | Output | Check |
|---|---|---|---|---|---|
| 0. Find | code (as today) | question | rewrite, search, rank | ranked pool (up to 8) | — |
| 1. Point | E2B, once per passage | question + **one** passage split into numbered sentences (~250 tokens) | "Which sentence answers the question?" | `0`, or one or two sentence numbers (`3` / `3,4`) | regex + range; anything else counts as `0` |
| 2. Vet | code | picked sentences | off-topic / wrong year / no shared content word (`DeclineCheck`, `Entities`) | keep or drop | — |
| 3. Say | E2B *or* code | question + the picked sentences only (~100 tokens) | "Answer in one short sentence using only these sentences" | one sentence | every content word not in the question must be in the picked sentences (numbers: from `YearFacts`); a failure falls back to the quote |
| 4. Assemble | code | — | reply + `[n]` citations + cited quote | Answer / Declined | no passage points → decline (best-guess offer as today) |

Why it fits the rules: Point reads one passage and returns a number of 1–3 tokens, which is almost pure prefill, so a short
call is cheap on the Pixel 6 (~250 tokens ≈ 0.6 s, against ~5 s to decode today's answer). Its output is checked against
a gold sentence with no grader. Say only rewords two sentences, and its check is lexical. A decline is the absence of any
pointed sentence, not something E2B has to choose to say.

The hypothesis worth the day: **with small inputs, E2B can finally use more candidates.** Scanning 8 passages one at a time
costs about what one 4-passage prompt costs today. In the step-back, only the ideal reader gained from a bigger pool
(80% at 12), and E2B's long prompts never did.

## Tasks

Branch `s1-small-steps` in worktree `.claude/worktrees/s1` (from main 0b50667b). Offline work runs on the eval emulator
(`~/.cache/granger/emu-stage/start-emulator.sh`, `ANDROID_SERIAL=emulator-5580`) with E2B on CPU. Short prompts run about
1 s each there, so 209 × 8 Point prompts take ~30 min in the background.

### Task 1: Point tooling, offline (~1 h)
- `tools/eval/point.py build BASE NEW --pool 4|8 [--shape …]`: for each dev item, take the pool from `runs/dev-base-1`
  (the app's 4 from `prompts.jsonl`; for 8, the ranker's top 8 rebuilt from `found.jsonl` with `merge.py`, as scratch
  `poolvariant.py` did, folded into `passages.py` this time). Split each passage into sentences (same rule as
  `CitedQuote.sentences`, ported) and write one Point prompt per passage into `prompts.jsonl` for `answerers.py run`
  (`--max-tokens 8`).
- `point.py score NEW`: parse, then score each item with no graders. The gold sentence is one overlapping a dataset answer
  quote (`metrics.matches`). Count answerable items as hit / wrong pick / none, and unanswerable items as declined (all `0`)
  or not. Also score the early-stop variant (stop at the first passage that points), computed from the same replies.
- Unit tests for sentence splitting, parsing and scoring in `tools/eval/tests`.
- Caveat to keep in view: a literal-quote gold sees only 51 of 148 items in the app's top 4, while graders count 83
  grounded. So the auto score is a **lower-bound proxy** for comparing variants. The final verdict still needs graders.

### Task 2: Point runs (~1.5 h, mostly background)
- P4: plain Point prompt, the app's 4.
- P8: the same, the ranker's 8.
- One wording variant on the better pool, chosen from P4's failure modes (e.g. the passage header shown or hidden, or a
  YES/NO line before the number). No more than three runs, so the morning stays bounded.
- Read: hit rate, false-point rate on unanswerables, and E2B prefill/latency from `replay` timings.

### Task 3: Say and full-reply runs, graded (~1.5 h)
On the best Point variant, build complete replies two ways:
- **S-quote:** code only. The answer is the pointed sentence(s), quoted with `[n]`, in the manner of `MedicalQuotes`.
  It is grounded by construction, and the open question is whether it answers the question.
- **S-say:** a short E2B Say call over the picked sentences, plus the lexical check (fall back to S-quote on failure).

One blind Fable grader per run, the same rubric (`RUBRIC.md`), appended to `grades.jsonl`. Compare against dev-base-1
(83 / 51, 65%) and E4B (93 / 60, 83%).

### Checkpoint with Neal (~early afternoon)
Neal picks the shape for tonight. Suggested bar for "better": grounded ≥ 83, declines ≥ 55 of 61, precision clearly
above 65%, and estimated Pixel 6 first words no worse than today's ~12.7 s median plus ~3 s. If neither variant clears
it, ship nothing new tonight and write up why.

### Task 4: App (~2.5 h)
- `core/research`: `PointPrompt` (sentence numbering reuses `CitedQuote.sentences`), `PointReply.parse`, `SayPrompt`,
  `SayCheck`. In `ResearchPipeline`, a `smallSteps` flag. When on, the answer step runs Point over the pool in rank order
  (early stop if Task 2 says so), then Vet, Say and Assemble. The Outcomes stay the same (Answered / Declined /
  Unverified), so the screen, citations and best-guess offer don't change. Stage text stays "Reading…" while pointing.
- Medical: S-quote only, matching how medical answers already ship as quotes.
- Follow-ups: the prior question goes into Point's question line only (no prior answer), so the input stays small.
- `EvalRunner` records each Point call so a phone run can be scored the same way.
- Unit tests (JVM). Keep the full suite green.

### Task 5: Pixel 6 (~1 h, before the evening)
- Shared-phone rules first: `lastUpdateTime` as its own step, the DB version (never install below it), and a DB backup.
- Debug build with `smallSteps` on. Speed run on `sets/speed-10.json` (first words, total) against the current
  build's numbers.
- Hand Neal ~10 questions to try (answerable, unanswerable, one follow-up, one medical) and note what to look for.

### Deferred (not today)
Held-out validation (171 items) of whichever shape ships; Classics within-book second look; E4B speed on the Pixel 7.

## Risks
- **False points:** E2B may number a sentence for every passage. Vet and the unanswerable score show whether this
  happens. If it does, try a YES/NO line first, or ask for the sentence's opening words, which code can check.
- **Answers spread across sentences or passages** (multi-step, 21 dev items) may get worse. `YearFacts` already handles
  year sums in code, and Point allows two sentences.
- **Speed:** four to eight calls instead of one. The numbers above are estimates until Task 5 measures them, and early
  stop is the lever.
- **Dev set fatigue** (±4 noise): which is why held-out validation comes before any release, though not today.
