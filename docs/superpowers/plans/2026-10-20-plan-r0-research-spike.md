# Plan R0: Project 2 spike — retrieval and the answerer

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Before project 2 ("Research deep links + knowing your library") is planned, measure which retrieval changes and which answer model close the gap from 44% answerable grounded toward the 80% bar, and at what latency. The spike ends with numbers and decisions in `docs/superpowers/spikes/2026-10-research-spike-results.md`, not with shipped features.

**Architecture:**
- **Offline, on the Mac (`tools/eval/replay.py`):** a Python port of `Ranking` and `LexicalScore` replays the recorded `found.jsonl` corpus of `m3-tune-1` (202 tuning questions, the current build). Ranking ideas (EmbeddingGemma rerank, hybrid scores, source-kind weights) are scored there in seconds instead of a 4-hour phone run.
- **On the phone (`app/src/debug`):** `EvalActivity` gains two debug-only modes. *Rewrite variant* runs a different rewrite prompt and records what it finds. *Prompt replay* sends fixed answer prompts to a chosen model and records replies and timings, with no retrieval.
- **Grading:** as in Plan 9. Claude grades replies in-session against `tools/eval/RUBRIC.md`, and grades are keyed by reply hash in `grades/grades.jsonl`.

**Tech Stack:** unchanged. Python 3 with pytest 8.4.2 (`tools/eval`). For offline embeddings, the Hugging Face weights of the EmbeddingGemma model the app ships. Kotlin, LiteRT-LM 0.18.0, and adb over Wi-Fi (`tools/dev/phone-wifi.sh`).

**Roadmap:** `docs/superpowers/plans/2026-10-15-granger-v1-roadmap.md`, "Carried forward from Plan 9" (the options list) and "Carried forward from M3" (fiction ranks as reference). Eval spec: `docs/superpowers/specs/2026-10-17-research-eval-design.md`.

## Decisions made with Neal (2026-10-20)

- The spike covers retrieval **and** the answerer: offline ranking experiments on the Mac, then E2B, E4B and 2–3 Hugging Face models compared as answerers on the Pixel 6.
- Deep links (opening at a passage, track or section) are engineering, not research. They go straight into the project 2 plan.

## Builder's calls in this plan (for Neal's review)

- The baseline is `m3-tune-1` with `m3-tune-2`'s two items (91% declined, 44% grounded), not Plan 9's tune-5, whose run files aren't on this Mac.
- **The held-out split is not touched.** It has had both of its allowed scorings. Project 2 will need a fresh held-out set, written and graded by a separate subagent, before any pass-bar claim. That set is written in the project 2 plan, not here.
- Proxies stand in for full grading where possible. **Recall@4** is the share of answerable questions with an answer passage among the 4 chosen (`metrics.matches`). **Empty rate** is the share of unanswerable questions with no passage above the threshold. Only Tasks 5 and 6 need graded replies.
- Spike code lives in `tools/eval` and `app/src/debug`. Nothing reaches a release build.

## Global Constraints

- **A change is adopted for project 2 only if it, on the tuning split:**
  - lifts recall@4 (Tasks 2–4) or answerable grounded (Tasks 5–6) by at least 5 points;
  - keeps unanswerable declined at 89% or more;
  - doesn't make send-to-first-words slower than today's 14 s median, unless the gain is at least 15 points, in which case Neal decides.
- An answer model must load and answer on the Pixel 6 (8 GB) alongside the app, as a file of 3.5 GB or less.
- Phone rules from Plan 9 still apply:
  - never run `connectedDebugAndroidTest` (it wipes the ~25 GB library);
  - never `adb reboot`;
  - never touch the phone while one of Neal's personal apps is in front;
  - one Gradle build at a time with `-Pkotlin.compiler.execution.strategy=in-process`, then `./gradlew --stop`.
- Downloading model weights from Hugging Face is fine. Pushing commits or any catalog change needs Neal's OK at that step.

---

### Task 1: The replay harness

**Files:** create `tools/eval/replay.py` and `tools/eval/tests/test_replay.py`. Reference: `core/research/.../Ranking.kt` and `LexicalScore.kt`.

- [ ] Port `Ranking` to Python: preselect, lexical score (keyword word credit, `LEAD_BONUS` 0.3, `TITLE_BONUS` 0.1), `perDocument` 3, `lexicalThreshold` 0.6, the media cap and `liftMedia`. Write tests from the Kotlin tests' cases.
- [ ] `replay.py run CORPUS --config NAME` prints recall@4 and empty rate. It writes the chosen passages per item for later tasks.
- [ ] **Gate:** on `m3-tune-1`, the port reproduces the app's `chosen` exactly for at least 200 of 202 items. Note why any item differs.
- [ ] Record the baseline recall@4 and empty rate.

### Task 2: EmbeddingGemma rerank, offline

- [ ] Embed every passage and question in the corpus with the Hugging Face weights of the shipped EmbeddingGemma. Cache the vectors in `runs/<id>/emb.npy`, which is gitignored.
- [ ] Check the Mac against the phone: embed 10 passages in `ResearchBenchmarkActivity`. Expect a cosine correlation of at least 0.99; if it's lower, say so in the results and use phone vectors for the finalists.
- [ ] Configs to replay:
  - cosine only;
  - hybrid `α·lexical + (1−α)·cosine`, with α swept from 0 to 1 in steps of 0.1;
  - preselect 12 against 24.

  For each config, sweep the cosine threshold. Report recall@4 and empty rate.
- [ ] Latency cost: Plan 8 measured 4.2 s for 12 passages on the GPU. Price each config by its passage count.

### Task 3: Source-kind weighting, offline

- [ ] Tag each corpus passage as reference (ZIM articles, Wiktionary), scripture, fiction/literature (Classics works by subject), or media.
- [ ] Try a fiction weight below 1 on questions that aren't literature questions, deciding by the rewrite's keywords and a small cue list. *Metamorphoses* and *War of the Worlds* (M3 walkthrough) are the motivating cases.
- [ ] Replay on top of the best Task 2 config and on the baseline.

### Task 4: Vocabulary gap, on the phone

The recorded corpus only holds what keyword search found, so these questions need new searches.

- [ ] `EvalActivity` gets a debug `rewriteVariant` extra. The variant prompt asks for the keywords plus up to 3 words the *source* would use ("alcohol" → wine, strong drink).
- [ ] Run the `vocab-gap`, `bible`, `words` and `literature` groups (31 questions) with the variant, recording `found.jsonl`. Replay that corpus with the baseline and the best offline ranking.
- [ ] Record the rewrite's added time (it's about 2.7 s today).
- [ ] Optional, only if this falls short by more than 10 points: offline dense retrieval over Classics book chunks for the `bible` group. It tells project 2 whether an on-phone vector index is worth its storage.

### Task 5: The answerer, on the phone

- [ ] Pick 2–3 candidates from Hugging Face that meet the size rule and support the chat template the pipeline uses. **1–2 of them should be recent small models distilled from frontier models** (Neal, 2026-10-20). If such a model has no LiteRT-LM build, convert it and note the effort. Note each one's license; it has to allow redistribution in the catalog.
- [ ] `EvalActivity` prompt-replay mode: given a JSONL of `{id, prompt}`, it loads the named model, uses greedy decoding, and records the reply, load time, time to first words and peak memory.
- [ ] Prompt set: the answer prompts the best ranking (Tasks 2–4) builds for every tuning item that reaches the model. This covers all 126 answerable questions plus the unanswerable ones with passages.
- [ ] Run E2B, E4B and each candidate. Grade every reply in-session (about 150 replies per model).
- [ ] Report grounded, declined, confidently wrong, first words and load time for each model, plus the multi-step group alone.

### Task 6: The combined run

- [ ] Build the best ranking and the best answerer behind debug flags. Run the full tuning split on the phone, grade it, and compare with `m3-tune-1` (`eval.py report --compare`).
- [ ] This number is the project 2 starting point. It is a tuning-split number, not a pass.

### Task 7: Results and decisions

- [ ] Write `docs/superpowers/spikes/2026-10-research-spike-results.md`:
  - every table;
  - what's adopted against the constraints;
  - what failed and why;
  - a fine-tuning verdict from the remaining failures (whether it's needed, and on what data);
  - "knowing your library": what an index built at download time would cost and buy, from Task 4's numbers.
- [ ] Add a project 2 row to the roadmap pointing at the results, and at the fresh held-out set the project 2 plan has to write.
- [ ] Revert or leave behind debug flags only; nothing changes for release users.
