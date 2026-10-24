# Plan R4: Research's answer model — one round of tests on the Pixel 6

Date: 2026-10-23 · Worktree `.claude/worktrees/r4`, branch `r4-answer-model` (off main b9634c6) · Follows R1 (dense retrieval).

## Why

R1 doubled recall@4 (25% → 52%) and took grounded answers from 42% to 68% (r1-dense-1). The misses that are left look like
the answer model's judgment, not retrieval:
- E2B answers from off-topic passages (9 of the unanswerable questions);
- it botches arithmetic and comparisons even when given the worked-out facts (ms-3: 232 beside "132");
- it splits multi-step questions poorly (ms-2, ms-3: no gain);
- Think deeper (E4B) grounded 3 of 13 in r1-dense-1, and Neal rarely finds it worth the wait.

R0 kept E2B because no other model did better on the Pixel 6. Three things have changed since then:
- `litert-community` now hosts official `.litertlm` builds of non-Gemma models. R0's non-Gemma failures (Qwen3.5 repeating
  the prompt, int4 losing quality) came from a third-party converter.
- Gemma 4 has MTP drafters. LiteRT-LM 0.18 turns on speculative decoding with `ConversationConfig.enableSpeculativeDecoding`.
  On an S26 Ultra it takes E4B from 22 to 37–49 tok/s on the GPU, with no quality loss (the main model checks every
  token). The catalog's E2B and E4B files (commits of 2026-08-31 and 2026-08-07) are after the 2026-05-05 re-upload that MTP needs.
- The answer now has the passage in front of it more often, so the model's misses cost more.

## Neal's decisions (2026-10-23)

1. **The Pixel 6 is the minimum phone.** Newer phones may get better models later, but every choice must first work on the
   Pixel 6. Picking the model per phone is out of scope for this plan.
2. **Latency:** no fixed number. A slower, better model is likely acceptable; Neal decides by feel on the phone (Task 7).
3. **Licences:** anything is fine while in development. Shipping a model under a non-Apache/MIT licence (LFM's) needs his
   word at the time.
4. **Think deeper goes.** No button and no second pass. One answer model for every question (Task 8), unless Task 4b
   shows that picking the model per question is worth it (2026-10-24):
   - the pick happens once, when the first prompt is sent, since that's when the model loads today anyway;
   - it can't use the answer model (that's what's being chosen): the embedder, which is already loaded for the query,
     or plain rules;
   - one model is loaded at a time; a follow-up in the same chat stays on the loaded model unless it clearly needs the
     other one.
5. **The test round covers three directions together:**
   - A: a better small answerer at about E2B's speed;
   - B: one stronger answerer (about 4B), sped up with MTP where it exists;
   - C: the rewrite (keywords and the medical flag) taken off the answer model, to win back time for B.

## Fixed points

- The vectors stay. Only the embedder, chunker v1, 256 dims and the title-prefixed document format bind them;
  EmbeddingGemma 2 stays on the phone for the query.
- The eval set is the tuning split (202: 126 answerable, 76 unanswerable). The held-out set stays closed (R2 writes it).
- Grading is in-session per `tools/eval/RUBRIC.md`.
- Greedy decoding, as today, so runs are comparable.
- **The phone:** nothing runs on the Pixel until R1 is merged and its session hands the phone over. Back up both databases
  before any install. R4's code tasks rebase onto main after R1 merges.
- Pass bars, as before: 80% answerable grounded, 90% unanswerable declined (89% gate).
- **Dense latency is part of the decision.** With the whole vector set on the phone (about 1.5 GB), `ms.dense` was
  13.8–14.9 s per question (R1 `r1-t10-check`, 2026-10-24); it was 2.2 s at 420 MB. `DENSE_RETRIEVAL` stays off until
  that comes down. Record `ms.dense` in every full-pipeline run here.

## Candidates

All from `litert-community` on Hugging Face. Sizes are the files.

| Model | File | Size | Licence | Direction |
|---|---|---|---|---|
| Gemma 4 E2B (today, control) | `gemma-4-E2B-it-gpu.litertlm` | 2.0 GB | Apache | — |
| Gemma 4 E2B + MTP | same | 2.0 GB | Apache | A (speed for C's sake) |
| Gemma 4 E4B + MTP | `gemma-4-E4B-it-gpu.litertlm` | 3.0 GB | Apache | B |
| Qwen3.5-2B | `Qwen3.5-2B_int8.litertlm` | 2.1 GB | Apache | A |
| MiniCPM5-2B | `MiniCPM5-2B_int4.litertlm` | 1.55 GB | Apache | A |
| LFM2.5-2.6B | `LFM2.5-2.6B_int4.litertlm` | 1.7 GB | LFM licence | A |
| Qwen3.5-4B | `Qwen3.5-4B_mixed_int4.litertlm` | 2.75 GB | Apache | B |
| SmolLM3-3B | `SmolLM3-3B_q4_block32_ekv4096.litertlm` | 2.0 GB | Apache | B |
| Ministral-3-3B | `Ministral-3-3B-Instruct-2512_q4_block32_ekv4096.litertlm` | 2.3 GB | Apache | B |
| Phi-4-mini | `Phi-4-mini-instruct_multi-prefill-seq_q8_ekv4096.litertlm` | 3.9 GB | MIT | B (if memory allows) |

For the rewrite only (C), small enough to stay loaded next to the answer model:
- Qwen3.5-0.8B (Apache);
- LFM2.5-350M (LFM licence);
- Qwen3-0.6B (Apache).

Thinking models (Qwen3.5, SmolLM3, Ministral) run with thinking off: R0's reasoning distills spent 60–110 s thinking.
Pin each file by commit and sha256 the way `tools/spike/fetch-models.sh` does.

---

## Task 1: replay knobs in `EvalActivity`

The replay path (`EvalActivity --es replay`, `PromptReplay`, `tools/eval/answerers.py`) already scores any `.litertlm`
on recorded prompts without app changes. Add what this round needs:

- [x] `LiteRtLlmEngine` takes `LlmOptions` (default: nothing overridden, so release behavior is unchanged):
  - `speculative: Boolean?` → `ConversationConfig.enableSpeculativeDecoding`;
  - `thinkingOff`: `CONFIG` (`ThinkingConfig(enableThinking = false)`), `TEMPLATE` (`extraContext` `enable_thinking=false`)
    or `BOTH`. Which one each model honors is found in Task 3, on the models that think, and recorded there;
  - `benchmark`: turns on `ExperimentalFlags.enableBenchmark` while the engine is loaded (restored on close), and each
    reply's `Conversation.getBenchmarkInfo()` becomes `LlmEngine.lastStats`. Replay only.
- [x] `EvalActivity` replay extras: `--es speculative on|off`, `--es thinking off|config|template`, `--ei maxTokens`.
- [x] `PromptReplay` records per item: load ms (first item), first words ms, `prefillTokens`, `prefillTokensPerSecond`,
  `outputTokens`, `decodeTokensPerSecond`, peak RSS; the first item also has `memBeforeLoad` / `memAfterLoad`
  (`Debug.MemoryInfo`: total PSS and its graphics share, as `dumpsys meminfo` shows them; RSS misses the GPU).
- [x] `answerers.py run` passes `--speculative`, `--thinking`, `--max-tokens` through. `answerers.py summary` adds the
  model's first words median / p90, decode tok/s median / p90 (the slow end) and output tokens.
- [x] Unit tests: options reach the overrides (`LiteRtOptionsTest`; LiteRT-LM's classes are Java 21, so JVM tests check
  the pure mapping, not `ConversationConfig`); replay records and extras (`PromptReplayTest`); old replays still build
  (`test_answerers.py`). 2,070 Gradle tests and 105 eval tests green.
- **Smoke test on the Pixel 6** (2026-10-24, E2B GPU, 2 prompts, runs `r4-smoke` and `r4-smoke-off`): load 7.0–7.1 s;
  memory 134 → 1,717 MB PSS after load, 1,210 MB of it graphics; prefill 400–525 tok/s; decode 13.0–16.2 tok/s.
  **Speculative on vs off made no clear difference** (168-token reply: 14.1 vs 13.6 tok/s; identical replies). Task 3
  checks whether E2B's MTP drafter is active on the Pixel 6 GPU at all, before counting on it for E4B.

## Task 2: the prompt sets

From r1-dense-1's recorded prompts (`PromptRecorder` stages), so every model sees the passages R1's app chose. Each set is
an id list in `tools/eval/sets/<name>.json` (`{base, stage, ids, note}`), replayed with `answerers.py run r1-dense-1 NEW
--set <name>`. A rewrite set gets the pipeline's 32-token limit and only times the model (nothing to build).

- [x] **speed-10:** the 10 answer prompts nearest the median length (6,335 characters): vocab-gap-005, who-wrote-007,
  history-004, geography-general-001, multi-step-007, medical-002, medical-003, medical-011, physics-001, physics-002.
  **speed-rewrite:** 5 first-question rewrite prompts nearest the median and 5 follow-ups: follow-up-001, -004, -005,
  -008, -010, medical-020, medical-029, medical-036, unanswerable-045, unanswerable-055.
- [x] **sample-40** (28 answerable, 12 unanswerable). R0's `--sample 40` ids (seed 5) kept where they still reach a model
  in r1-dense-1: 32 of 40 (the 8 lost are unanswerables declined before the model). Out: 4 easy grounded answerables
  (bible-004, chemistry-002, follow-up-010, history-001). In: multi-step-002, -003, -004, -010, and the unanswerables
  E2B answered that weren't in it (follow-up-011, medical-027, medical-029, unanswerable-025, -026, -041, -042, -054).
  It holds 7 multi-step, 6 arithmetic or comparison (multi-step-001, -002, -003, -004, -010, -017), 5 medical
  answerables (medical-012, -016, -018, -020, -035) and all 9 unanswerables E2B answered. The ids:
  bible-001, bible-002, bible-008, vocab-gap-004, vocab-gap-009, who-wrote-007, who-wrote-009, follow-up-002,
  follow-up-003, follow-up-005, follow-up-011, history-005, science-006, literature-002, geography-general-003,
  multi-step-001, -002, -003, -004, -007, -010, -017, medical-012, -016, -018, -020, -027, -029, -035, unanswerable-017,
  -025, -026, -041, -042, -045, -050, -054, -055, words-003, chemistry-001.
  **E2B in r1-dense-1 on it:** 14/28 grounded, 11 not grounded, 2 declined, 1 unverified; **3/12 unanswerables
  declined** (by construction: 9 of the 12 are ones it answered). Task 4's rule "no more than 1 unanswerable answered
  of 12" is therefore a high bar against a control at 9; it stands unless Neal says otherwise.
- [x] **full:** the 142 answer prompts of r1-dense-1 (not 189: 60 items were declined before the model, with no passages).
- [x] The recorded prompt is model-neutral: plain instructions, passages and `Question: … Answer:`, with no chat-template
  tokens (`<start_of_turn>`, `<|turn>`, `<bos>`, `<|im_start|>`); LiteRT-LM applies each model's template from its file.
- `answerers.py`: `--set` (exclusive with `--sample`), `prompts(ids=, stage=)`; tests for both and for the committed
  sets' shape. 107 eval tests green.

## Task 3: the speed screen (Pixel 6)

- [ ] Run speed-10 on every candidate, GPU first; CPU too where GPU fails or is slower (E4B decoded faster on CPU in the
  model spike). E2B and E4B with and without MTP.
- [ ] Table: load, first words median / p90, tok/s, output tokens, peak memory, errors, and whether the output is usable
  (no prompt echo, no thinking text, follows the `NOT IN LIBRARY` and `[n]` format).
- [ ] **Cut:** a model whose answer first words median is over 12 s (E2B today: about 4 s; this leaves room for Neal's
  "slower but better"), whose peak memory with the embedder loaded is over 4.5 GB (56% of the Pixel's 8 GB), or whose
  output isn't usable after one prompt fix. At most 5 go on.
- [ ] Rewrite prompts on the small rewrite candidates: time and keyword quality by eye against E2B's.

## Task 4: quality on sample-40

- [ ] Replay sample-40 on the survivors and E2B (control; must match r1-dense-1 byte for byte).
- [ ] Grade per RUBRIC.md. Table: grounded, answered wrongly, answerable declined, unanswerable declined, multi-step,
  arithmetic/comparison, medical (stays inside the passages), first words.
- [ ] Prompt fixes are allowed per model family (each gets the same 2 tries at most), and are written down.
- [ ] **Cut to 2** (plus E2B). The rule: more grounded than E2B by 3 or more of 28, and no more than 1 unanswerable answered
  of 12. If none clears it, say so and stop at Task 5 for Neal.

## Task 4b: is a per-question model pick worth it? (Neal, 2026-10-24)

Decide from Task 4's grades before building anything.

- [ ] **Best case:** from sample-40's per-question grades for E2B and each finalist, count grounded answers and
  unanswerables answered if every question went to whichever model did better (ties go to the faster one). Compare
  with each model on its own.
- [ ] **Stop here** if the best case beats the best single model by fewer than 3 of 28 grounded, or answers more
  unanswerables. Then Task 8 goes ahead with one model.
- [ ] **The signal**, only if the best case is worth it. Label each tuning question "fast model is enough" or "needs the
  stronger one" from the grades, then try:
  - the embedder: nearest-centroid or a cosine threshold, as Task 5's medical flag does (the two can share the code);
  - plain rules: comparison and arithmetic words, numbers, more than one named entity, length.
  Report each signal's routed result against the best case and against the best single model, offline.
- [ ] **Cost on the phone:** the load time of each model from cold (Task 3 measures it), and how often a follow-up
  would have to switch models on the tuning split's follow-up chains.
- [ ] **For Neal:** a table (one model vs routed, grounded, unanswerables answered, first words median / p90, switches).
  If he takes routing, Task 8 keeps two model paths behind the pick instead of removing the second one.

## Task 5: the rewrite without the answer model (offline first)

Today E2B writes keywords and a medical flag before every search (about 2.2 s, about 60 tokens). Measure whether the
answer model can be freed of it:

- [ ] **Medical flag by the embedder:** EmbeddingGemma is already loaded for the query. Nearest-centroid (or a cosine
  threshold) over labeled medical / not-medical questions. Labels: the tuning split's questions plus about 100 written
  ones per side (a subagent writes them; not the tuning questions). Compare with E2B's flags on the tuning split.
  The bar: it misses no medical question E2B catches.
- [ ] **Keywords without a model** for first questions: stop words out, noun phrases and names kept, the question's
  quoted titles kept. Compare the keyword lists with E2B's on the tuning split, then on the phone: a full-pipeline run
  (`--es rewrite plain`, a new mode) and recall@4 against r1-dense-1. Dense retrieval embeds the raw question, so the
  keyword search matters less than it did in R0.
- [ ] **Follow-ups** still need a model to fold in the earlier turn. Options, timed in Task 3: the answer model itself,
  or a small rewrite model kept loaded next to it (memory measured with both loaded).
- [ ] Decision table for C: time saved at the median, recall change, medical flag misses.

## Task 6: the full set for the 2 finalists

- [ ] The full-pipeline run on the tuning split for each finalist, with Task 5's rewrite if it passed (else E2B's rewrite
  stays and the finalist is timed with the swap cost, measured).
- [ ] Graded per RUBRIC.md. Table against r1-dense-1: grounded, declined, recall@4, send to first words median / p90,
  load, peak memory.

## Task 7: Neal's feel test

- [ ] A debug-only answer model picker (Settings → Debug), so Neal can switch between E2B and the 2 finalists on the
  Pixel without reinstalling. Models side-loaded under `files/models/`.
- [ ] Neal uses Research with each for his own questions. He picks one, or none.

## Task 8: one answer model, Think deeper removed

After Neal's pick:

- [ ] The catalog: the chosen model as the one Research model (`role` small), pinned by commit and sha256; the E4B
  `large` entry removed; `curated.json` presets updated; re-signed (whoever is second regenerates on top).
- [ ] Remove Think deeper: the button, the `THINK_DEEPER` tier, the `deeper` and `guess-large` eval phases. Keep the
  best-guess path on the one model. The swap path in `ModelsAdapter` goes too, unless Neal took routing in Task 4b; then it
  becomes the per-question pick, at first send only.
- [ ] If Task 5 passed: the medical flag and first-question keywords move off the model in `ResearchPipeline`.
- [ ] `ResearchScreen`'s "needs the Gemma 4 E2B model" message follows the model.
- [ ] Roadmap: an R4 row; "Carried forward from R4" (per-phone models for newer phones, the licence call if an LFM
  model won, anything Task 4 showed about arithmetic).
- [ ] Pixel 6 walkthrough by Neal.

## Not in this plan

- A different embedder (it would invalidate every vector file, about 2 days of Mac GPU).
- Models picked per phone (after the Pixel 6 works).
- Fine-tuning (R0's verdict stands until this round shows what the best stock model misses).
