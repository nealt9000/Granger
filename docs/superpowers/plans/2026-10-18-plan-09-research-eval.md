# Plan 9: Research eval and Best guess Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Measure Research on the Pixel 6 against Neal's real library with a ~300-question eval, fix what it shows until it meets the pass bar, add a labelled **Best guess** for questions the library can't answer, clear the Plan 8 leftovers, and flip `evalPassed` on the models that pass.

**Architecture:** The catalog gains seven Kiwix packs and a `nonCommercial` flag. `:core:research` gains the Best guess (a no-passage prompt and `ResearchPipeline.guess`). Kept chats store a guess on a declined turn (Room v6). A debug-only `EvalActivity` runs a dataset through the real `ResearchPipeline` and `LibraryPassageFinder` on the phone and writes JSONL records. `tools/eval` (Python), on the desktop, pushes the dataset over adb, pulls the records, and computes the metrics. It also writes the grading worksheet that Claude grades in-session, and renders the HTML report Neal reviews.

**Tech Stack:** Kotlin 2.4.21, coroutines 1.11.0, Room 2.8.5, Compose Material 3, Robolectric 4.17, JUnit 4 + Truth; Python 3 with pytest 8.4.2 and libzim 3.13.1 (as in `tools/pack-builder`); adb.

**Spec:** `docs/superpowers/specs/2026-10-17-research-eval-design.md` (amends the v1 spec §1/§2/§5 and the library redesign spec §9). Roadmap "For Plan 9" list: `docs/superpowers/plans/2026-10-15-granger-v1-roadmap.md`, "Carried forward from Plan 8". Plan 8: `docs/superpowers/plans/2026-10-17-plan-08-research.md`.

## Decisions made with Neal (2026-10-17/18)

- The eval targets Neal's library: Wikipedia best articles (maxi), WikiMed (maxi), Wiktionary, Geography, Physics, Knots, Chemistry, Mathematics, iFixit, and all of Granger Classics.
- Claude writes every question and grades every answer; Neal reviews all of it.
- His two questions go in verbatim: "Where in the bible does it talk about alcohol?" and "Who wrote the book of Romans?"
- Best guess is a **button** on a decline; the eval decides whether it uses E2B or E4B.
- Non-commercial packs that Granger only links to are allowed if they are never behind a payment, never re-hosted, and always show their license (spec §10a). Plan 10 implements the cap exemption.

## Builder's calls in this plan (for Neal's review)

- Eval code lives in `app/src/debug` (tests in `app/src/testDebug`), so release builds don't contain it.
- Dataset, grades, rubric and the checkpoint runs are committed under `tools/eval/`. Other runs are gitignored.
- The held-out third is written and graded by a separate subagent. The main session runs `tools/eval report --totals-only` on it and never opens its questions or records.
- Light English stemming is the first matching change tried (Task 8). It ships only if the eval shows it helps.

## Global Constraints

- Pass bar, on the held-out split:
  - ≥ 90% of unanswerable questions correctly handled (declined).
  - ≥ 80% of answerable questions grounded.
  - Medical questions are counted in those two bars by answerability, under the stricter medical rubric.
  - E4B gets `evalPassed` when Think deeper is ≥ 80% grounded on the held-out multi-step set.
- Best guess "good enough" for E2B, on held-out guess questions: ≤ 10% confidently wrong and ≥ 60% right or partly right. "Can't know" counts as right. Phones under 8 GB always use E2B.
- The held-out split is scored at most twice (spec §5), and held-out questions never become tuning data.
- No Best guess button on medical turns. A guess never shows source cards or [n] chips, and is never passed as a previous answer.
- Copy (exact):
  - Button: "Best guess"
  - Guess label: "I couldn't find this in your library. Here's my best guess, so take it with a grain of salt."
  - Large-model wait note: "This takes about half a minute"
  - Large-model fallback: "The large model couldn't load, so this guess is from the standard one."
  - License line for a non-commercial pack: "<license> · provided by Kiwix"
  - Existing copy (decline, Search instead, banner, stages) is unchanged.
- Limits: guess `maxTokens = 512`, the same as an answer. Guess prompt ≤ 400 estimated tokens.
- Builds on this machine: `./gradlew -Pkotlin.compiler.execution.strategy=in-process …`, one build at a time, then `./gradlew --stop`.
- Never run `connectedDebugAndroidTest`: it wipes the ~25 GB library. Never start an eval run, or touch the phone, while one of Neal's personal apps is in front.
- `adb reboot` leaves the phone locked behind a screen only Neal can pass. Don't reboot it.
- Pushing the catalog and the commits to GitHub needs Neal's OK at that step.

## Review Focus

1. **An eval run stops part-way** (the phone sleeps, the app is killed, USB is pulled). Expected: re-running resumes after the last finished question, with no duplicate or half-written records. Pinned in Task 5 (`resumesAfterLastRecordedItem`, `halfWrittenLastLineIsIgnored`).
2. **Another app is in front when a run starts.** Expected: `tools/eval run` refuses and names the app, unless it's Granger or the launcher. Pinned in Task 2 (`test_refuses_when_another_app_is_in_front`).
3. **A medical decline**, whether the flag came from the rewrite, the lexicon or a selection hint, including a medical turn stopped before its first words. Expected: no Best guess button. Pinned in Task 4 (`medicalDeclineOffersNoBestGuess`, `stoppedMedicalTurnOffersNoBestGuess`).
4. **The process dies mid-guess.** Expected: the turn stays "Not in your library." and the Best guess button is offered again; no guess is stuck "writing". Pinned in Task 4 (`guessInterruptedByProcessDeathOffersTheButtonAgain`).
5. **The large model won't load for a guess** (low memory, a damaged file). Expected: the small model guesses, with the fallback note. Pinned in Task 3 (`guessFallsBackToSmallWhenLargeWontLoad`).

---

### Task 1: Neal's packs in the catalog, with the non-commercial flag

**Files:**
- Modify: `tools/catalog-gen/curated.json`, `tools/catalog-gen/catalog_gen.py` (`pack_entry`), `tools/catalog-gen/test_catalog_gen.py`
- Modify: `core/catalog/src/main/kotlin/app/granger/core/catalog/Catalog.kt` (`PackEntry`), its parser test
- Modify: `app/src/main/kotlin/app/granger/sources/SourceViewModel.kt:260` (details line)
- Regenerate: `catalog/catalog.json`, `catalog/catalog.json.sig`

**Interfaces:**
- Produces: `PackEntry.nonCommercial: Boolean = false`. Catalog JSON key `"nonCommercial"` (omitted when false; older catalogs parse as false).

- [ ] **Step 1: Confirm iFixit's license.** Read iFixit's licensing page (`https://www.ifixit.com/Info/Licensing`) and record the exact license string. If it isn't non-commercial, list it as an ordinary pack. If it forbids redistribution or linking outright, stop and ask Neal.
- [ ] **Step 2: Write failing tests.**
  - `test_catalog_gen.py::test_non_commercial_flag_passes_through`: a curated pack with `"nonCommercial": true` gives `entry["nonCommercial"] is True`, and one without the key has no `nonCommercial` key.
  - Catalog parser test `nonCommercialDefaultsToFalse` / `nonCommercialIsRead`.
  - `SourceViewModelTest.nonCommercialPackShowsProvidedByKiwix`: the details line contains `"CC BY-NC-SA 3.0 · provided by Kiwix"` for a pack with that license and the flag set. An ordinary pack's line is unchanged.
- [ ] **Step 3: Run them** (`cd tools/catalog-gen && python -m pytest -q`; `./gradlew … :core:catalog:testDebugUnitTest :app:testDebugUnitTest --tests '*SourceViewModelTest*'`). Expected: the new tests FAIL.
- [ ] **Step 4: Implement.**
  - `pack_entry` copies `curated["nonCommercial"]` when true.
  - `PackEntry` gains the field.
  - The details line replaces `item.license` with `"${item.license} · provided by Kiwix"` when `nonCommercial`.
- [ ] **Step 5: Add the seven packs to `curated.json`:**

| name | flavour | category | license | title |
| --- | --- | --- | --- | --- |
| `wiktionary_en_all` | `nopic` | Reference | CC BY-SA 4.0 | Wiktionary |
| `wikipedia_en_geography` | `maxi` | Reference | CC BY-SA 4.0 | Geography (Wikipedia) |
| `wikipedia_en_physics` | `maxi` | Science & Math | CC BY-SA 4.0 | Physics (Wikipedia) |
| `wikipedia_en_chemistry` | `maxi` | Science & Math | CC BY-SA 4.0 | Chemistry (Wikipedia) |
| `wikipedia_en_mathematics` | `maxi` | Science & Math | CC BY-SA 4.0 | Mathematics (Wikipedia) |
| `wikipedia_en_knots` | `maxi` | How-to | CC BY-SA 4.0 | Knots (Wikipedia) |
| `ifixit_en_all` | — | How-to | from Step 1 | iFixit repair guides |

  iFixit gets `"nonCommercial": true` if Step 1 found a non-commercial license.

- [ ] **Step 6: Regenerate and check.** Run `python catalog_gen.py build` and sign as in Plan 3, with the key at `~/.config/granger/catalog-signing.key`. Every new pack must show `hasFulltextIndex: true`. If one doesn't, keep it in the catalog but tell Neal that Research can't search it.
- [ ] **Step 7: Run all tests.** The catalog-gen pytest suite, `:core:catalog`, and the SourceViewModel tests all pass. `PublishedCatalogTest` still passes, because the Starter preset is unchanged.
- [ ] **Step 8: Commit, then ask Neal before pushing.** Commit message: "Catalog: Neal's library packs; non-commercial packs say who provides them". Once it's pushed, Neal downloads the nine packs on the Pixel 6 (Settings → Sources → Kiwix). The runs in Tasks 7 and 9 need them.

### Task 2: `tools/eval`, the dataset format, metrics and report

**Files:**
- Create: `tools/eval/eval.py` (CLI), `tools/eval/dataset.py`, `tools/eval/metrics.py`, `tools/eval/grading.py`, `tools/eval/report.py`, `tools/eval/adb.py`, `tools/eval/requirements.txt` (`libzim==3.13.1`, `pytest==8.4.2`), `tools/eval/RUBRIC.md`, `tools/eval/.gitignore` (`runs/*` except `runs/checkpoints/`)
- Test: `tools/eval/tests/test_dataset.py`, `test_metrics.py`, `test_grading.py`, `test_adb.py`, `tests/fixtures/` (small dataset, results, grades)

**Interfaces (the contract Task 5's activity writes and Tasks 6–9 use):**

Dataset (`tools/eval/dataset/tune.json`, `tools/eval/dataset/heldout.json`), a JSON array of items:
```json
{"id": "bible-001", "group": "bible", "split": "tune", "answerable": true, "medical": false, "multiStep": false,
 "question": "Who wrote the book of Romans?", "priorQuestion": null,
 "answers": [{"source": "book:granger:<ref>", "location": "romans", "quote": "Paul, a servant of Jesus Christ"}],
 "reference": "Paul the Apostle", "goodGuess": null, "notes": ""}
```
- `source` is the pack's ZIM name (`wikipedia_en_top`) for articles, or the book id for books.
- `location` is matched case-insensitively as a substring of the passage's location.
- `priorQuestion` set means the runner first asks that question in the same chat, then asks this one as a follow-up.

Result record (`results.jsonl`, one line per item; `deeper` only for multi-step items, `guesses` only for items that ended declined):
```json
{"runId": "...", "id": "...", "keywords": ["..."], "rewriteFallback": false, "medical": false,
 "found": [{"kind": "BOOK", "source": "...", "title": "...", "location": "...", "heading": "...", "lexical": 0.41}],
 "chosen": [{"kind": "...", "source": "...", "title": "...", "location": "...", "heading": "...", "text": "..."}],
 "outcome": "answered|declined|unverified|failed", "reply": "...", "cited": [1],
 "ms": {"rewrite": 0, "search": 0, "rank": 0, "firstWords": 0, "total": 0},
 "deeper": {"outcome": "...", "reply": "...", "cited": [], "chosen": [], "ms": {}},
 "guesses": {"small": {"text": "...", "ms": 0, "fellBack": false}, "large": {"text": "...", "ms": 0, "fellBack": false}}}
```
- `found` is capped at 60 entries.

Grades (`tools/eval/grades/grades.jsonl`), one line per graded reply:
```json
{"id": "...", "kind": "answer|deeper|guess-small|guess-large", "replyHash": "<sha1 of reply>",
 "grade": "grounded|not-grounded|right|partly|wrong|confidently-wrong", "reason": "..."}
```

CLI commands:
- `eval.py validate <dataset> [--packs DIR] [--books DIR]`
- `eval.py run <dataset> --run-id ID [--ids …] [--groups …] [--guesses none|small|large|both]`
- `eval.py worksheet <run>`
- `eval.py report --dataset-only <dataset>…` (a readable page of the questions, for Neal's review)
- `eval.py report <run> [--totals-only] [--compare <run>]`
- `eval.py sweep <run> --lexical 0.20:0.50:0.02`

- [ ] **Step 1: Write failing tests:**
  - `test_dataset.py`:
    - `test_rejects_duplicate_ids`
    - `test_answerable_needs_an_answer_location`
    - `test_unknown_split_is_rejected`
    - `test_validate_finds_location_and_quote_in_zim`, which uses `testdata/granger-test.zim`: a good path passes, while a misspelled path and an absent quote fail with the item id in the message.
  - `test_metrics.py` (fixture of 10 items):
    - `test_unanswerable_handled_counts_only_declines`: unverified and answered both count as wrong.
    - `test_grounded_needs_answered_and_grounded_grade`
    - `test_recall_at_4_matches_source_and_location`
    - `test_found_but_not_chosen_is_split_from_never_found`
    - `test_medical_flag_accuracy_and_recall`: compares the record's `medical` flag with the item's.
    - `test_guess_grades_per_model`
    - `test_timings_median_and_p90`
    - `test_sweep_moves_declines_with_threshold`: a lexical threshold raised above an item's top `found` score turns it into a decline.
  - `test_grading.py`:
    - `test_worksheet_lists_only_ungraded_replies`: a reply whose hash already has a grade is left out.
    - `test_changed_reply_needs_a_new_grade`
  - `test_adb.py`:
    - `test_refuses_when_another_app_is_in_front`: fake `dumpsys` output with `com.whatsapp` in front gives a `SystemExit` naming it. The launcher or `app.granger` passes.
    - `test_run_pulls_results_and_stops_on_done_marker`
- [ ] **Step 2: Run them** (`cd tools/eval && python -m pytest -q`). Expected: all FAIL.
- [ ] **Step 3: Implement the modules.**
  - `adb.py` wraps `adb` with `subprocess` and parses `dumpsys activity activities` for `mResumedActivity`.
  - `run` does the following, in order:
    1. Checks what's in front.
    2. Pushes the dataset to `/sdcard/Android/data/app.granger/files/eval/dataset.json`.
    3. Starts `app.granger/.eval.EvalActivity` with extras `runId`, `ids`, `groups`, `guesses`.
    4. Polls every 30 s for `eval/<runId>/done`.
    5. Pulls `results.jsonl` to `runs/<runId>/`.
  - `report.py` writes one self-contained `runs/<runId>/report.html`:
    - the headline numbers against the bar
    - then, per question: keywords, found, chosen, reply, grade and reason
    - with `--compare`, which questions changed outcome or grade
  - `--totals-only` prints only the totals and writes no per-question page.
- [ ] **Step 4: Write `RUBRIC.md`** (the grading rules from spec §7):
  - **grounded:** answers the question, and every claim is supported by a passage it cites.
  - **medical:** grounded only if it adds nothing beyond the passages (no dose, advice or claim the passages lack).
  - **guess grades:** right, partly, wrong, confidently-wrong. "Can't know" on an unknowable question counts as right.
- [ ] **Step 5: Run the tests.** Expected: all PASS.
- [ ] **Step 6: Commit** ("tools/eval: dataset format, metrics, grading worksheet and report").

### Task 3: Best guess in `:core:research`

**Files:**
- Create: `core/research/src/main/kotlin/app/granger/core/research/GuessPrompt.kt`
- Modify: `core/research/src/main/kotlin/app/granger/core/research/ResearchPipeline.kt`
- Test: `core/research/src/test/kotlin/app/granger/core/research/GuessPromptTest.kt`, `ResearchPipelineTest.kt`

**Interfaces:**
- Produces:
  - `object GuessPrompt { fun build(question: String, priorQuestion: String?): String }`
  - `fun ResearchPipeline.guess(question: String, priorQuestion: String?, large: Boolean, stop: () -> Boolean = { false }): Flow<ResearchEvent>`
  - New outcome `Outcome.Guessed(text: String, usedLarge: Boolean, fellBack: Boolean)` with `medical = false` and `keywords = emptyList()`
  - New event `ResearchEvent.GuessFallback`

- [ ] **Step 1: Write failing tests:**
  - `GuessPromptTest.saysThereAreNoSourcesAndAsksForBrevity`: the prompt contains "no sources" and "say so" wording. With `priorQuestion`, it includes "Previous question:".
  - `GuessPromptTest.staysUnder400EstimatedTokens`: a 1,000-character question plus a prior stays ≤ 1,600 chars.
  - `ResearchPipelineTest.guessStreamsPartialsThenGuessed`
  - `ResearchPipelineTest.guessNeverCallsTheFinder`
  - `ResearchPipelineTest.guessFallsBackToSmallWhenLargeWontLoad`: emits `GuessFallback`, and `Guessed.fellBack` is true.
  - `ResearchPipelineTest.guessStopKeepsTheTextSoFar`
  - `ResearchPipelineTest.guessWithNoModelFails`: `Outcome.Failed(MODEL_UNAVAILABLE)`.
- [ ] **Step 2: Run** `./gradlew … :core:research:test`. Expected: FAIL.
- [ ] **Step 3: Implement.**
  - The prompt tells the model it has no sources, to answer in a few sentences, and to say plainly when it doesn't know or can't know. It uses no citation markers.
  - `guess` loads with `models.generator(deeper = large)` and falls back like `loadGenerator`. It streams with the answer token cap of 512 and honours `stop`.
- [ ] **Step 4: Run.** Expected: PASS.
- [ ] **Step 5: Commit** ("Research: a best guess from the model's own knowledge").

### Task 4: Best guess in the app

**Files:**
- Modify: `core/library/src/main/kotlin/app/granger/core/library/Entities.kt` (`ResearchTurn`), `LibraryDatabase.kt` (version 6, `MIGRATION_5_6`), `ResearchChats.kt`, `Daos.kt`
- Create: `core/library/schemas/app.granger.core.library.LibraryDatabase/6.json` (generated)
- Modify: `app/src/main/kotlin/app/granger/research/ResearchViewModel.kt`, `ResearchScreen.kt`
- Test: `core/library/src/test/.../RoomResearchChatsTest.kt`, the migration test, `app/src/test/kotlin/app/granger/research/ResearchViewModelTest.kt`

**Interfaces:**
- Consumes: `ResearchPipeline.guess`, `Outcome.Guessed`, and `ResearchEvent.GuessFallback` from Task 3.
- Produces:
  - `ResearchTurn` columns `guess TEXT NOT NULL DEFAULT ''`, `guessLarge INTEGER NOT NULL DEFAULT 0`, `guessFellBack INTEGER NOT NULL DEFAULT 0`
  - The same fields on `ChatTurn`
  - `suspend fun ResearchChats.setGuess(turnId: Long, text: String, usedLarge: Boolean, fellBack: Boolean)`
  - `fun ResearchViewModel.bestGuess(turnId: Long)`
  - `TurnUi.guessing: Boolean` and `TurnUi.offersGuess: Boolean`
  - `const val BEST_GUESS_USES_LARGE: Boolean` in `ResearchViewModel.kt`, starting at `true` and set by Task 9
  - The guess uses the large model only when `BEST_GUESS_USES_LARGE`, the tier is `THINK_DEEPER`, and the large model is installed

- [ ] **Step 1: Write failing tests:**
  - `RoomResearchChatsTest.setGuessIsReadBack`
  - Migration test `migration5To6KeepsChatsAndDefaultsGuessToEmpty`
  - `ResearchViewModelTest`:
    - `declineOffersBestGuess`
    - `medicalDeclineOffersNoBestGuess`
    - `stoppedMedicalTurnOffersNoBestGuess`
    - `bestGuessStoresTextAndHidesTheButton`
    - `guessShowsNoSourceCards`: the `TurnUi` has no shown passages.
    - `largeGuessShowsTheWaitNote`
    - `phoneBelowThinkDeeperTierGuessesWithSmall`
    - `guessFallbackShowsTheNote`
    - `stopDuringGuessKeepsTheTextSoFar`
    - `guessInterruptedByProcessDeathOffersTheButtonAgain`: a new view model over a turn with blank `guess` offers the button, and nothing reads as writing.
    - `followUpAfterGuessDoesNotPassGuessAsPriorAnswer`: the next request's `prior` is null, or the last *answered* turn.
- [ ] **Step 2: Run** `./gradlew … :core:library:testDebugUnitTest :app:testDebugUnitTest --tests '*Research*'`. Expected: FAIL.
- [ ] **Step 3: Implement storage.** Write `MIGRATION_5_6`, three `ALTER TABLE research_turns ADD COLUMN …` statements, and add it to `addMigrations`. Export the schema.
- [ ] **Step 4: Implement the view model and screen.**
  - Under a DECLINED, non-medical turn, the screen shows **Best guess** beside **Search instead**.
  - While a guess runs, it shows the stage text. Add "This takes about half a minute" when the guess is large, and the fallback note when it fell back.
  - The finished guess shows below "Not in your library." with the exact label, in a visually distinct style (an outlined card in the secondary container colour, no chips).
  - The guess runs through the same `begin`/Stop path as answers. It is saved on finish or Stop; on process death nothing is saved and the button returns.
- [ ] **Step 5: Run.** Expected: PASS. Then `./gradlew … :app:assembleDebug`.
- [ ] **Step 6: Commit** ("Research: Best guess on a decline, kept in the chat").

### Task 5: `EvalActivity` on the phone

**Files:**
- Create: `app/src/debug/kotlin/app/granger/eval/EvalActivity.kt`, `app/src/debug/kotlin/app/granger/eval/EvalRunner.kt`, `app/src/debug/kotlin/app/granger/eval/EvalJson.kt`
- Modify: `app/src/debug/AndroidManifest.xml` (register the activity, exported, like `ResearchBenchmarkActivity`)
- Modify: `app/src/debug/kotlin/app/granger/research/ResearchBenchmarkActivity.kt` (reuse the shared recording finder if it moves)
- Test: `app/src/testDebug/kotlin/app/granger/eval/EvalRunnerTest.kt` (Robolectric for `org.json`)

**Interfaces:**
- Consumes: `ResearchPipeline.run(request, stop)`, `ResearchPipeline.guess(...)` (Task 3), `ResearchModels`, `PassageFinder`, `Library.packs` for mapping a pack id to its ZIM name, and Task 2's record format.
- Produces:
  - `class EvalRunner(pipeline: ResearchPipeline, finder: RecordingFinder, sourceName: (Passage) -> String, clock: () -> Long)`
  - `suspend fun EvalRunner.run(items: List<EvalItem>, out: File, guesses: GuessModels)`
  - `enum class GuessModels { NONE, SMALL, LARGE, BOTH }`
- `RecordingFinder` wraps the real finder, keeps the last call's passages with their lexical scores, and supplies the `found` list.

- [ ] **Step 1: Write failing tests** in `EvalRunnerTest`, against fake `TextGenerator`s and a fake finder:
  - `writesOneRecordPerItemWithOutcomeAndTimings`
  - `resumesAfterLastRecordedItem`: an existing file with ids a and b, and items a, b, c, writes only c.
  - `halfWrittenLastLineIsIgnored`: a truncated last line is dropped and that item reruns.
  - `followUpRunsPriorQuestionFirstAndPassesPriorTurn`
  - `multiStepItemAlsoRunsThinkDeeperOnTheSamePool`
  - `declinedItemGetsRequestedGuesses`
  - `answeredItemGetsNoGuesses`
  - `foundIsCappedAt60`
  - `articleSourceIsTheZimNameNotTheUuid`
- [ ] **Step 2: Run** `./gradlew … :app:testDebugUnitTest --tests '*EvalRunnerTest*'`. Expected: FAIL.
- [ ] **Step 3: Implement `EvalRunner`.**
  - It appends each record and flushes before starting the next item.
  - It times the stages from the `Progress` events: rewrite = until SEARCHING, search = until RANKING, rank = until WRITING, firstWords = until the first `Partial`.
- [ ] **Step 4: Implement `EvalActivity`.**
  - It reads the extras, loads `files/eval/dataset.json`, and filters by `ids`/`groups`.
  - It keeps the screen on (`FLAG_KEEP_SCREEN_ON`) and shows progress as `n / total` plus the current id.
  - It writes `eval/<runId>/results.jsonl` and then `done`.
  - It logs `Eval:` lines under the `Granger` tag.
  - It uses the app's container (`passageFinder`, `rankingConfig`, `ModelsAdapter(c.modelManager)`), exactly as the benchmark does.
- [ ] **Step 5: Run the tests.** Expected: PASS. Then `./gradlew … :app:assembleDebug` builds.
- [ ] **Step 6: Smoke run on the phone.** Only when Neal says the phone is free. Run `tools/eval run tools/eval/tests/fixtures/smoke.json --run-id smoke` with three items. Expected: three records pulled, then `done`.
- [ ] **Step 7: Commit** ("Eval: a debug activity runs a dataset through Research and records each answer").

### Task 6: The dataset

**Files:**
- Create: `tools/eval/dataset/tune.json` (~200 items), `tools/eval/dataset/heldout.json` (~100 items, written by a separate subagent), `tools/eval/AUTHORING.md`

- [ ] **Step 1: Get the library on the desktop.**
  - Download the nine packs' ZIMs to `~/.cache/granger/eval-packs/` (about 25 GB; 101 GB is free), using the catalog URLs and checking their sha256.
  - The Classics EPUBs come from the `classics-2026-10-17` release, saved to `~/.cache/granger/eval-books/`.
  - Write the book ids exactly as the app derives them (`BookIds`).
- [ ] **Step 2: Write `AUTHORING.md`**, the instructions both authors follow. It covers:
  - The mix: ~40% answerable, ~30% unanswerable, ~20% medical, ~10% multi-step.
  - Answers come from every pack, with Classics weighted up.
  - The groups:
    - `bible`, with Neal's two questions verbatim
    - `vocab-gap`, modern words for ideas old texts word differently
    - `who-wrote`
    - `follow-up`, ~15 chats
    - `medical`, drawn from WikiMed
    - `unanswerable`: personal facts, the future, events after the packs' snapshot dates, local or private facts
  - Questions are phrased the way a person types them, not copied from article titles.
  - Every answerable item names its source, location and a quote.
  - Unanswerable items get a `goodGuess` where one exists.
- [ ] **Step 3: Write `tune.json`.** Check every answer against the desktop packs. `eval.py validate tools/eval/dataset/tune.json --packs ~/.cache/granger/eval-packs --books ~/.cache/granger/eval-books` passes.
- [ ] **Step 4: Dispatch a fresh subagent to write `heldout.json`** from `AUTHORING.md`, with the same validation. It reports only counts per group. From here on, the main session doesn't open `heldout.json`, its records or its worksheets.
- [ ] **Step 5: Neal reviews.** Render both files with `eval.py report --dataset-only` into a readable page and publish it to Neal as a private Artifact. Neal reviews everything, held-out included. Apply his feedback, revalidate, and loop until he approves.
- [ ] **Step 6: Commit** ("Eval: the ~300-question dataset, reviewed by Neal").

### Task 7: Baseline run

- [ ] **Step 1: Run the tuning split.** Neal's library must be on the phone and the phone on the charger. Run `tools/eval run tools/eval/dataset/tune.json --run-id baseline --guesses both`. Record the wall time, which tells later tasks how big a run they can afford.
- [ ] **Step 2: Grade.** Run `eval.py worksheet runs/baseline`, grade every reply by `RUBRIC.md` into `grades.jsonl`, then run `eval.py report runs/baseline`.
- [ ] **Step 3: Write the diagnosis** into `runs/checkpoints/baseline/NOTES.md`:
  - the headline numbers against the bar
  - the counts of wrong declines split into never found, found but not chosen, and model declined
  - what Neal's two Bible questions did
  - the top failure patterns, with question ids
- [ ] **Step 4: Neal reviews the report** (published as a private Artifact). Fold in his feedback on grades.
- [ ] **Step 5: Commit** `runs/checkpoints/baseline/` and the grades ("Eval: baseline on the tuning split").

### Task 8: Tune Research on the tuning split

This task is a loop. Each change is unit-tested, then measured on a run, and kept only if the tuning metrics improve without the unanswerable-handled number falling. Every kept change and its before/after numbers go into `runs/checkpoints/tuning/NOTES.md`. Order the work by Task 7's diagnosis. These are the expected candidates:

- [ ] **8a. Rewrite prompt** (`Rewrite.kt`): ask for the words the source text itself would use (synonyms, older wording, the names of works and people) rather than words about the question's form.
  - New tests: `RewriteTest.promptAsksForSourceWording` and `promptHasAnExample`.
  - Measure on the `bible`, `vocab-gap` and `who-wrote` groups first, then the full split.
  - Keep the 32-token cap unless the records show the keywords getting cut off.
- [ ] **8b. Word forms:** create `core/research/src/main/kotlin/app/granger/core/research/Stems.kt` with `object Stems { fun of(word: String): String }`, a light English suffix stripper (plural, -ing, -ed, -ness, -ful, -ly, -ation, -ity).
  - Tests:
    - `forgivenessMeetsForgive`
    - `drunkennessMeetsDrunken`
    - `shortWordsAreLeftAlone`: under 4 letters.
    - `properNamesSurviveIntact`: `Romans` → `roman`. Test both sides stem alike.
  - `LexicalScore` compares stems on both sides.
  - Whether ZIM and book queries also get stems depends on their indexes: check whether Xapian in libzim and the FTS4 tokenizer already stem, and record the answer.
- [ ] **8c. Passage choice:** use the records' `found` vs `chosen` to fix off-topic passages winning (Plan 8 saw Hank Crawford's references and a Ben Jonson introduction win). Candidates: penalise reference/bibliography sections in `HtmlPassages`, and weight headings. Each comes with a JVM test built from the offending passage text.
- [ ] **8d. Thresholds:** run `eval.py sweep` over `lexicalThreshold`, then set `RankingConfig.lexicalThreshold` at the value that meets ≥ 90% unanswerable-handled with the most grounded answers. Ties go to the lower value, since Best guess now softens a decline. `cosineThreshold` is only re-tuned if rerank comes back on (8f).
- [ ] **8e. Search instead** passes the keywords the library's Search can match: the first keyword, or the question's own content words. Test: `ResearchViewModelTest.searchInsteadUsesTheFirstKeyword`.
- [ ] **8f. Rerank** stays off unless lexical ranking can't reach the bar after 8a–8e. If it stays off, Settings → Models labels EmbeddingGemma "Not used yet" (test in the models screen's view model).
- [ ] **8g. Ranking test with the real corpus:** a JVM test where `corpus` is all the passages found (roadmap leftover), built from a recorded `found` list.
- [ ] **Exit:** the tuning split meets both bars, or 8a–8f plus one further round don't get there. In the second case, go to Task 9's stop path.
- [ ] **Commit after each kept change**, with its numbers in the message body.

### Task 9: Held-out checkpoint and the Best guess model

- [ ] **Step 1: Run the held-out split.** Run `tools/eval run tools/eval/dataset/heldout.json --run-id heldout-1 --guesses both`. Dispatch a fresh subagent with `RUBRIC.md` to grade it. The main session reads only `eval.py report runs/heldout-1 --totals-only`.
- [ ] **Step 2: Decide.**
  - If both bars pass, go to Step 4.
  - If they fail, do one more tuning round on the tuning split only (Task 8), then run `heldout-2`, graded the same way.
  - If that fails too: stop. Write `runs/checkpoints/heldout/NOTES.md` with the numbers, what was tried, and the options (fine-tuning, a lower bar, rerank back on). Bring it to Neal, leave `evalPassed` false, and skip Step 4.
- [ ] **Step 3: Choose the Best guess model** from the held-out guess grades (totals only). If E2B meets ≤ 10% confidently wrong and ≥ 60% right or partly right, set `BEST_GUESS_USES_LARGE = false`. Otherwise leave it `true`. Update the `ResearchViewModelTest` cases that depend on it.
- [ ] **Step 4: Flip `evalPassed`.**
  - E2B gets `evalPassed: true` in `curated.json` when both bars pass.
  - E4B gets it when Think deeper is ≥ 80% grounded on the held-out multi-step items and, if `BEST_GUESS_USES_LARGE`, its guesses meet the good-enough bar.
  - Rebuild and re-sign the catalog, and ask Neal before pushing.
- [ ] **Step 5: Commit** `runs/checkpoints/heldout*/` totals and grades ("Eval: held-out results; evalPassed for …").

### Task 10: Research reliability leftovers

Each item is from the roadmap's "For Plan 9" list. Each gets a failing test first, then the fix.

- [ ] **Separate failure messages.** Search and storage (IO) failures read "Couldn't search your library. Try again." with **Retry**. "The model needs repair" is kept for model failures.
  - Add `FailReason.SEARCH_FAILED` and catch the finder's exceptions in `ResearchPipeline`.
  - Tests: `ResearchPipelineTest.finderExceptionFailsWithSearchFailed`, `ResearchViewModelTest.searchFailureShowsRetryNotRepair`.
- [ ] **No crash on storage errors.** Guard `deleteChat` and the `turns`/`recent` flows behind `stateIn` against storage errors: log, then show the stored state or an empty list. Tests: `turnsFlowErrorDoesNotCrash`, `deleteChatErrorDoesNotCrash`.
- [ ] **Rewrite timeout starts once the model is free.** Today the 5 s timeout starts before the model lock is taken. Test: `rewriteTimeoutStartsAfterTheModelIsFree`. A fake model held busy for 6 s still gets its rewrite.
- [ ] **Stale background release.** A release requested mid-turn is cancelled when the app comes back to the front before the turn ends. Test (in `ModelManagerTest`): `releaseRequestedMidTurnIsDroppedWhenAppReturns`.
- [ ] **Medical flag on stopped turns.** A medical turn stopped or failed before its first words keeps the pipeline's medical flag once known. Test: `stoppedTurnKeepsPipelineMedicalFlag`.
- [ ] **Prompt budget at runtime.** `AnswerPrompt.build` trims passages from the end until the estimate is ≤ 3,400 tokens. Count with chars ÷ 3 for text that is mostly non-ASCII. Tests: `budgetEnforcedWithLongNonEnglishPassages`, `trimsLastPassageFirst`.
- [ ] **Missing JVM tests:** the citation tab-lookup catch, and the card-matching dispatcher move.
- [ ] **Run** `./gradlew … testDebugUnitTest`. Everything passes.
- [ ] **Commit** ("Research: tell search failures from model failures; guard storage; budget at runtime").

### Task 11: Research UI and polish leftovers, and the release gate

- [ ] **Rail Research tool always starts a new chat.** Tapping it while a kept chat is open opens a new chat, like `openResearch`. Test in the nav or rail test: `railResearchWhileChatOpenStartsNewChat`.
- [ ] **Markdown in answers.** `*italic*` and `**bold**` render as styles in `AnswerText`; other Markdown shows as typed. Tests: `AnswerTextTest.italicAndBoldRender`, `citationsInsideItalicStillLink`.
- [ ] **Last scroll waits for the source cards.** After a turn settles (Retry included), the screen scrolls only once the turn's cards are present. Test in `ResearchScreen`'s scroll logic, or a view model test of the settle signal.
- [ ] **Release gate.** `Features.RESEARCH = BuildConfig.DEBUG`, changed from `true`. Task 12 sets it back to `true` only if `evalPassed` was flipped. Test: `FeaturesTest.researchIsOnInDebug`.
- [ ] **Minor polish from the Plan 8 reviews** (roadmap list). Each gets one small test:
  - `--release 17` on `:core:research`
  - `HtmlPassages` splits on no-break spaces
  - `Rewrite.parse` caps at 6 keywords and rejects an echoed placeholder
  - `Keywords` keeps apostrophes as passage text does (O'Brien)
  - the prompt header is length-capped
  - `Ranking.cosine` checks vector sizes
  - `Citations.strip` keeps newlines
  - `ModelManager` checks the file exists before closing the resident model, and enforces the tier
  - a large-model reload failure mid-answer falls back to small
  - `BookIndex.passages` uses `ORDER BY`, and one damaged book no longer drops all book passages
  - a citation to a vanished reading tab says the source is gone
  - `ZimUrls.forEntry` encodes the fragment
- [ ] **Run** all unit suites and `:app:assembleDebug`. Everything passes.
- [ ] **Commit** ("Research: polish from the Plan 8 reviews; Research gated to debug until it passes").

### Task 12: Walkthrough, docs, and finishing

- [ ] **Step 1: Full check.** Run `./gradlew … testDebugUnitTest :app:assembleDebug` and `cd tools/eval && python -m pytest -q`; also run the catalog-gen and pack-builder pytest suites. All pass.
- [ ] **Step 2: Pixel 6 walkthrough** (with Neal's go-ahead; install over the top, never uninstall):
  - Neal's two Bible questions get cited answers.
  - An unanswerable question shows **Best guess**. The guess appears with the label, with no chips or cards, and survives a force-stop.
  - A medical decline has no button.
  - Stop mid-guess keeps the text.
  - A follow-up after a guess searches again.
  - The iFixit pack's page shows "· provided by Kiwix".
  - The seven new packs are filed under Wikipedia, Wiktionary and iFixit in the Library.
  - Time to first words is ≤ 9 s on three answerable questions.
- [ ] **Step 3: Update the docs.**
  - **Roadmap:** row 9 → Done, plus a "Carried forward from Plan 9" section with the measured numbers, the open items, and a Plan 10 note about the non-commercial cap exemption.
  - **v1 spec:** §1's Content row points to spec §10a. §7's lawyer-review item names non-commercial packs.
  - **Plan 11 note:** the Play policy check covers non-commercial packs.
  - **Still open, carried forward:** measuring the per-keyword ZIM search cost on a full Wikipedia on an SD card before the beta.
  - **Features:** `Features.RESEARCH = true` if `evalPassed` was flipped.
- [ ] **Step 4: Commit, then finish** with `superpowers:finishing-a-development-branch`.
