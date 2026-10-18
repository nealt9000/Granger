# Research eval and Best guess (Plan 9): design

Amends `2026-10-15-granger-v1-design.md` §1 (success criteria), §2 (`tools/eval`) and §5 (Ask eval), and the library redesign spec §9 (Research). Where they differ, this document wins.

## 1. Why

Research (Plan 8) works end to end, but Neal's first real use found that most questions come back "Not in your library", including questions the library can answer. With all of Granger Classics installed, he asked:

- "Where in the bible does it talk about alcohol?"
- "Who wrote the book of Romans?"

Both declined, although *The King James Bible* is in Classics. The first fails because the KJV never says "alcohol" (it says "wine", "strong drink", "drunkenness"). The second probably fails because the search looks for "author"/"wrote" rather than the book itself. The thresholds behind the decline were set from nine questions on a phone that holds only Classics and a 3 MB test pack, and the margin between answerable and unanswerable was 0.30 against 0.35.

Neal also wants the dead end gone: when the library truly has nothing, the user should be able to ask for the model's own best guess, clearly labelled as such.

Plan 9 measures Research against the library Neal will actually carry, fixes what the measurements show, and adds the Best guess.

## 2. Goals

1. **Neal's library in the catalog.** The seven packs he wants that aren't there yet.
2. **An eval that runs on the Pixel 6**, driven from the desktop over adb, through the real Research pipeline and the real library.
3. **A ~300-question dataset** written by Claude against that library, reviewed in full by Neal.
4. **Research fixes driven by the eval** (rewrite, matching, thresholds, passage choice) until it meets the pass bar (§7).
5. **The Best guess button** (§6), with the small-vs-large model choice decided by the eval.
6. **The roadmap's "For Plan 9" leftovers** (roadmap, "Carried forward from Plan 8").
7. **`evalPassed` flipped** for the models that pass.

## 3. Non-goals and anti-goals

Non-goals:
- Fine-tuning. The spec keeps it as a last resort; if prompts, matching and thresholds can't reach the bar, stop and bring the numbers to Neal (§9).
- The media work. It comes right after Plan 9, before Plans 10–11 (Neal, 2026-10-17), and gets its own spec.
- A desktop model runtime. The v1 spec's "desktop LiteRT-LM" option is dropped (§4).

Anti-goals (a failure even if the numbers pass):
- **A guess that looks sourced.** A Best guess must never carry source cards or [n] chips, and a cited answer must never be built from the model's own knowledge.
- **A guess on a medical question.** Medical questions get no Best guess button.
- **Tuning to the test.** Numbers that pass only on the questions they were tuned on (see the held-out split, §5).
- **Breaking Neal's phone setup.** The library is about 25 GB; nothing in Plan 9 may wipe it (no `connectedDebugAndroidTest` without Neal's OK), and the eval never takes the screen while one of Neal's personal apps is in front.

## 4. Where the eval runs

**On the Pixel 6, driven from the desktop.** This machine has 5.7 GB of RAM and can't run E4B reliably, and phone timings are what matter.

- **On the phone:** a debug-only eval activity, in the same style as `ResearchBenchmarkActivity`. It reads a dataset file pushed into the app's files, runs each question through the real `ResearchPipeline` with the app's real `PassageFinder` and models, and writes one result record per question. It keeps the screen on, survives being restarted part-way (resumes after the last finished question), and can run a subset by id or group.
- **On the desktop:** `tools/eval` (Python, like the other tools). It pushes the dataset, checks what is in front on the phone before starting, starts the activity, waits, pulls the results, computes the metrics and renders the report.
- **Each result record holds** the question id, the rewrite's keywords and medical flag, every passage found (source, article or chapter, lexical score), the passages chosen for the prompt, the outcome (answered / declined / unverified / failed), the reply text, cited numbers, and timings per stage. For decline cases it also holds the Best guess replies (§6).
- Because every found passage and its score are recorded, threshold sweeps can be computed on the desktop from one run without re-running the models. The builder decides how far to lean on that.

The spec's `:core:ask-logic` is `:core:research` today; it stays a pure-Kotlin module shared by the app and the eval activity.

A full run is long (roughly 300 questions at 5–10 s, plus Best guesses that each load E4B). The builder measures it and plans around it: subsets while iterating, full runs at checkpoints, phone on the charger.

## 5. The dataset

**Library it targets** (Neal, 2026-10-17): Wikipedia best articles with pictures, WikiMed, Wiktionary, Geography, Physics, Knots, Chemistry, Mathematics, iFixit, and all of Granger Classics. About 25 GB. The desktop has 101 GB free, so Claude can download the same packs and use python-libzim (already used by `tools/pack-builder`) and the Classics EPUBs to find and check answers.

**Size and mix:** about 300 questions, keeping the v1 spec's mix:
- ~40% answerable
- ~30% unanswerable
- ~20% medical
- ~10% multi-step (Think deeper)

Inside those:
- **Neal's two Bible questions, verbatim**, plus a Bible/religious-texts group.
- **Vocabulary gaps:** modern words for ideas that old texts word differently.
- **"Who wrote / what is this" questions** about books and works.
- **Every pack gets questions**, with Classics weighted up since it's the one library Neal has tested.
- **A small follow-up group** (*Claude's call:* ~15 two-question chats), since Research is threaded.

**Unanswerable** now means questions no library could answer offline: personal details ("my library's Wi-Fi password"), the future, events after the packs' snapshot dates, private or local facts. With a library this broad, everyday questions should rarely fall here, which is Neal's expectation and something the eval checks.

**Each question records:**
- its id and group
- whether it's answerable, and if so where the answer lives (pack or book, plus article or chapter, plus a short quote), and a reference answer
- whether it's medical
- for unanswerable ones, what a good guess would be, where one exists

**Held-out split (*Claude's call*):** about a third of the questions are held out. The pass bar is judged on the held-out set; the rest is for tuning.
- The held-out third is written by a separate Claude session from the same instructions, and the session doing the tuning doesn't read those questions or their per-question results, only the held-out totals. Neal still reviews them.
- The held-out set is scored at most twice: once at the checkpoint where the tuning split passes, and once more after one further tuning round if that first score fails. A second failure goes to "stop" (§9). Held-out questions never become tuning data.

**Authorship and review:** Claude writes every question (the held-out third as above) and grades every answer; Neal reviews all of it and gives feedback, and Claude revises. The dataset (JSON) and grades live in `tools/eval/` in the repo.

## 6. Best guess

**What the user sees.** When Research declines (nothing close enough, or the model said `NOT IN LIBRARY`), the turn reads "Not in your library." with two buttons: **Search instead** (as now) and **Best guess**. Tapping Best guess:

- asks a model to answer from what it already knows, with no passages
- shows the reply labelled "I couldn't find this in your library. Here's my best guess, so take it with a grain of salt."
- shows no source cards and no [n] chips, and looks different from a cited answer
- can be stopped, like any answer
- is kept in the chat as part of that turn, so it survives a force-stop and shows when the chat reopens

The button is Neal's call: a guess can take half a minute, so the user chooses to wait.

**Medical questions** (flagged by the rewrite, the lexicon or the hint) get no Best guess button.

**Which model.** The eval asks both models for a guess on every unanswerable question and on answerable questions that were wrongly declined, and grades each guess as right, partly right, wrong, or confidently wrong. Then:
- If E2B's guesses are good enough, the button uses E2B, which replies in a few seconds.
- Otherwise it uses E4B, with a "This takes about half a minute" note while it loads (about 15 s to swap in and about 20 s to the first words, measured in Plan 1).
- Phones under 8 GB always use E2B.

**"Good enough" (*proposed by Claude, for Neal to approve*):** on the held-out guess questions, E2B is confidently wrong on at most 10% and right or partly right on at least 60%. A guess that says it can't know (the future, personal facts) counts as right.

**The guess prompt** tells the model it has no sources, to answer briefly, and to say plainly when it doesn't know or can't know. The answer token cap stays.

**Follow-ups (*Claude's call*):** a follow-up after a guess searches the library as usual. The previous question is passed along as it is now, but the guess text is not passed as the previous answer; a guess never feeds a later answer as if it were sourced.

Storing a guess needs a schema change to the kept-chat turns (a guess text on a declined turn, or a new turn kind). The builder writes the migration and keeps existing chats intact.

## 7. Pass bar and metrics

**Pass bar** (judged on the held-out set; the decline line is reworded from the v1 spec because declines now carry a Best guess):

- **≥ 90% correctly handled unanswerable questions.** Correct means declined ("Not in your library." with the button). Wrong means a cited answer or an unverified reply.
- **≥ 80% grounded on answerable questions.** Grounded means a cited answer that answers the question, where every claim is supported by the passages it cites. Wrong declines, unverified replies and answers citing the wrong passage all count against it.
- **Medical questions (*Claude's call*)** are split by answerability and counted in the two bars above. A medical answer is graded more strictly: it must show the banner and stay close to the quoted source (no advice, dose or claim beyond what the passages say), otherwise it isn't grounded. A medical decline must show no Best guess button.
- **E4B (*Claude's call*):** gets `evalPassed` when, on the held-out multi-step set, Think deeper is ≥ 80% grounded under the same grading. Its Best guess grades are reported; if the Best guess uses E4B (§6), its guesses must also meet §6's "good enough" bar. E2B can pass while E4B fails; then Think deeper stays and only E2B is flipped, and Neal decides whether Think deeper ships.

**Also reported, not gating:**
- retrieval recall@4 (did the passage holding the answer reach the prompt), split from "found but not chosen" and "never found"
- medical-flag accuracy (*proposed by Claude:* treat a missed medical flag as a bug to fix, aiming for ≥ 95% recall)
- Think deeper on the multi-step set
- Best guess grades per model (§6)
- timings: send to first words (target ≤ 9 s on the Pixel 6), plus Best guess wait

**Grading.** Outcome, citations and retrieval recall are computed by `tools/eval`. Whether an answer is grounded and whether a guess is right is graded by Claude in a Claude Code session (no API key or API cost): `tools/eval` writes a grading worksheet per run with each reply, its passages and the reference answer, and reads back a grades file. Each grade carries a one-line reason. Grades are keyed to the question and the reply text, so a re-run only needs new grades for replies that changed.

**The report** is one readable page per run: the headline numbers against the bar, then every question with its keywords, what was found, what was chosen, the reply, the grade and why. Neal reviews this and sends feedback. Comparing two runs shows which questions changed.

## 8. Changes the eval is expected to drive

These are starting hypotheses, not orders. The eval decides what actually changes.

- **The rewrite** asks for the words the source text would use (synonyms, older wording, names of works and people), not words about the question's form.
- **Word forms:** "forgiveness" should meet "forgive". Light stemming in lexical scoring and in the book and ZIM queries, where the indexes allow it.
- **Passage choice:** Plan 8 saw off-topic passages reaching the model (Ray Charles answered from the Hank Crawford article's references; the Hamlet follow-up from a Ben Jonson introduction).
- **Thresholds:** `lexicalThreshold` (0.32) and `cosineThreshold` (0.40), tuned on the tuning split.
- **Rerank:** stays off unless lexical ranking can't reach the bar; the embedder costs 4.2 s for 12 passages on the GPU. If it stays off, Settings → Models hides EmbeddingGemma or says it's unused (a roadmap leftover).
- **Search instead** sends keywords the library's Search can actually match.

## 9. When to stop

- **Pass:** both bars met on the held-out set and Neal has reviewed the report. Flip `evalPassed` for E2B, and for E4B if it meets its bar (§7). Re-sign the catalog.
- **No pass after the hypotheses in §8 and a reasonable round beyond them:** stop and bring Neal the numbers, what was tried, and the options (fine-tuning, a smaller bar, the embedder back on). Don't flip `evalPassed`.
- Until `evalPassed` is flipped, `Features.RESEARCH` stays on only in debug builds (roadmap leftover: gate it before any release build).

## 10. Catalog additions

Added to `tools/catalog-gen/curated.json` from Kiwix, then the catalog is rebuilt and re-signed:

| Pack | Kiwix name | Flavour | Size |
| --- | --- | --- | --- |
| Wiktionary | `wiktionary_en_all` | nopic (only flavour) | 9.2 GB |
| Geography (Wikipedia) | `wikipedia_en_geography` | maxi | 1.2 GB |
| Physics (Wikipedia) | `wikipedia_en_physics` | maxi | 0.9 GB |
| Knots (Wikipedia) | `wikipedia_en_knots` | maxi | 0.02 GB |
| Chemistry (Wikipedia) | `wikipedia_en_chemistry` | maxi | 0.4 GB |
| Mathematics (Wikipedia) | `wikipedia_en_mathematics` | maxi | 1.0 GB |
| iFixit | `ifixit_en_all` | — | 3.6 GB |

- Wikipedia best articles (maxi) and WikiMed (maxi) are already in the catalog.
- Each new pack's `hasFulltextIndex` is checked; a pack without one can't be searched by Research, so it's flagged to Neal.
- Catalog categories, which group the Kiwix source screen (*Claude's call*): Wiktionary and Geography under Reference; Physics, Chemistry and Mathematics under a new "Science & Math"; iFixit and Knots under a new "How-to". Filing in the Library is unchanged: the Wikipedia topic packs join the Wikipedia collection, and Wiktionary and iFixit get their existing collections.
- iFixit's license is checked from the pack itself before it is listed (believed to be CC BY-NC-SA). If non-commercial, it is listed under the content rule in §10a.
- Kiwix's separate "A Library of Knots" (`zimgit-knots_en`, 30 MB) isn't added unless Neal asks.
- Neal then downloads the library on the Pixel 6 himself; the 10 GB cap isn't enforced yet (Plan 10).

## 10a. Content rule for non-commercial packs (Neal, 2026-10-18)

Amends the v1 spec's Content row ("public domain or commercially redistributable licenses only"). Neal's reasoning: all content is available in Free and Pro alike, Granger makes no money reselling content, and anything a web browser can freely download from Kiwix's catalog, Granger should be able to fetch too. The rule is now in two parts:

- **Content Granger builds or hosts** (Granger Classics, anything on `nealt9000/Granger` releases) must still be public domain or allow commercial redistribution.
- **Packs Granger only links to** (hosted by Kiwix, fetched from Kiwix's URLs) may carry a non-commercial license such as CC BY-NC-SA, on three conditions:
  1. **Never behind a payment:** they never count toward the Free cap, in Free or Pro. This is the one place the cap would otherwise charge for access to them.
  2. **Never built, changed or re-hosted** by Granger.
  3. **License and attribution always shown** on the pack's page, for example "iFixit · CC BY-NC-SA · provided by Kiwix".

Plan 9 marks such packs as non-commercial in the catalog and shows the license line. Plan 10, which builds the cap, implements the cap exemption. This case is added to the v1 spec's open item "lawyer review before public launch" and to Plan 11's Play policy check.

## 11. Constraints

- Gradle on this machine: `-Pkotlin.compiler.execution.strategy=in-process`, one build at a time, then `./gradlew --stop` (5.7 GB RAM; the session has crashed during builds).
- No `connectedDebugAndroidTest` without Neal's OK: it uninstalls the app and wipes the ~25 GB library.
- `adb reboot` leaves the phone on a lock screen only Neal can pass.
- Never drive the phone while a personal app is in front; the eval tool checks before it starts.
- The eval activity and dataset hooks are debug-only and absent from release builds.

## 12. Left to the builder

- The eval activity's internals, the result file format and the report's look.
- How the Best guess turn is stored, and the migration.
- Which §8 changes to make and in what order, guided by the numbers.
- How runs are split into subsets while iterating.
- Order and grouping of the roadmap's "For Plan 9" leftovers.
