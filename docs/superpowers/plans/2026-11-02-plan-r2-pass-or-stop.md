# Plan R2: Ask against its bar, on a fresh held-out set

Date: 2026-11-02 · Branch `r2-pass-or-stop` · Follows R5 and the 2026-11-02 Ask review. "Research" in older plans is Ask.

## Why

Ask has never been measured against its bar. The 72% grounded / 83% declined numbers (R5 `r5-t7-all`) come from the
**tuning** split, the questions the pipeline was tuned on. The bar (spec `2026-10-21-research-library-design.md` §8,
Neal 2026-10-21) is ≥ 80% answerable grounded and ≥ 90% unanswerable declined **on a fresh held-out set**. R2 was meant
to write that set and score it, and never got a plan. Meanwhile F1 part 2 shipped Ask as a Preview on the tuning-split
grounded number alone (`2026-10-31-plan-f1b-friend-build.md`:15), with declines at 83%, under both the 90% bar and
the 89% gate R1, R4 and R5 carried.

The Plan 9 held-out set (`dataset/heldout.json`) can't stand in. Neal reviewed every question, its per-question grades
are committed (`tools/eval/grades/grades.jsonl`), and it predates the vector set (no food, making, technology, maps or
thought questions; no own-book or media group).

**Where r5-t7-all's 48 misses came from** (2026-11-02 analysis; scratch scripts, not committed):

| Answerable not grounded (35) | n |
|---|---|
| Search never found the passage (all are in the library: older wording, dictionary entries, the second fact of a two-fact question) | 14 |
| Found, but ranked out of the prompt | 6 |
| The answer was in the prompt and E2B misread it (wrong passage, superlatives, arithmetic) | 10 |
| The answer was in the prompt and E2B declined | 1 |
| Strict grading calls (within the ±2 grader noise) | 4 |

Of the 13 unanswerables answered, 10 fit a deterministic rule: a personal, local or future cue in the question ("my",
"near me", "this season", "when will"), or a cited title whose year differs from the question's. On the recorded replies
those rules decline 10 and cost 1 grounded answer ("will antibiotics help my cold"). The cue words were chosen from
these same questions, which is why only the held-out set can say what they're worth.

## Neal's decisions (2026-11-02)

1. Write R2 now, while the closed test is prepared, not after F2.
2. A fresh held-out set, written blind by a separate session on Fable.
3. Start with the decline check.

## Fixed points

- The bar is the spec's: ≥ 80% grounded, ≥ 90% declined, on the fresh held-out set. Per-domain grounded is reported.
- **Blindness.** The tuning session (this plan's builder) never reads `heldout2.json`'s questions or per-question
  results, only totals. The set is scored at most twice (eval spec §5). Neal reviews it before the first scoring.
- Every change is tuned on the tuning split (`dataset/tune.json`) and gated there before the held-out set sees it.
- E2B is the only answer model (R5 decision 1). The behaviour LoRA waits on LiteRT-LM #3173 and is not in this plan;
  when it comes back it is scored on this held-out set, not the tuning split.
- Phone rules from R5 (back up both databases before any install; never `connectedDebugAndroidTest`; never
  `adb reboot`). The Mac's GPU is shared with the Research session: ask before long MPS jobs.
- How to iterate:
  - ranking and post-checks: replayed on the Mac from `found.jsonl` and `results.jsonl` (`replay.py`, `merge.py`),
    seconds per run;
  - the answer step: recorded prompts replayed on the phone (`answerers.py run`, about 7 s a question, deterministic);
  - the rewrite or keyword changes: a full phone run (about 70 s a question).

---

## Task 1: the fresh held-out set

- [ ] Written blind on Fable from `tools/eval/AUTHORING.md` and spec §8: `tools/eval/dataset/heldout2.json`, split
  `heldout2`, ids `h2-<group>-NNN`, a `domain` on every item. At least 10 answerable per domain with content on disk,
  about a third unanswerable, medical, multi-step and follow-ups as in Plan 9, and an own-book group on 2–3 imported
  public-domain EPUBs (not Classics). Validator: 0 problems.
- [ ] Neal reviews it (as with Plan 9's set). Fixes go back through the author, not this session.
- [ ] The test library on the Pixel 6 matches the set: every pack and snapshot it cites, the Classics release, the own-book
  EPUBs imported. Recorded in the set's notes.
- [ ] A media group (track and chapter citations, spec §8) needs the phone's library export; written by the same author
  later, or the existing `dataset/media.json` stands in for this round. Neal's call.

## Task 2: the decline check

A deterministic check after the answer, before it's shown: an answer that cites only passages that can't be about the
question becomes "Not in your library". In `:core:research`, pure, run in `ResearchPipeline` after `Citations.check`.

- [ ] Rules, each its own function with tests, each measured alone on the recorded tuning replies:
  - **Question cues**: personal ("my", "our", "near me", "here"), local, live or future ("this season", "tonight",
    "right now", "when will", "next year") that no library can answer. A medical question `PersonalMedical` already
    routes is left to it, so "will antibiotics help my cold" still answers.
  - **Year mismatch**: the question names a year, and no cited passage's title or heading has it while some has another.
  - **Place mismatch**: the question names a place no cited passage names (prototype catches 2; check it costs nothing).
  - **"but … the passages do not"**: a first sentence that hedges and then declines (`Citations.goesOnToAnswer` lets
    it through today).
- [ ] `PersonalMedical` misses "my left knee" style questions: widen it with tests.
- [ ] Mac replay over `r5-t7-all`: declines gained, grounded lost (gate: no grounded lost that a rule could avoid).
- [ ] Spoken and on-screen copy: a check-declined turn reads as an ordinary decline, and Best guess stays offered.

## Task 3: the dates block says its conclusion

`YearFacts` gives E2B both dates; E2B still drew the wrong conclusion twice (multi-step-002, -004).

- [ ] The block ends with the comparison in words ("So: Austen died a year before Frankenstein was published"; "So:
  born the same day"). Unit tests on the recorded passages.
- [ ] A reply that contradicts the block's verdict is flagged in the eval report (not hidden in the app this round).
- [ ] Phone replay of the multi-step prompts; gate: no multi-step loss.

## Task 4: ranking

- [ ] Entity cue words ("years passed", "how long") don't count toward the all-keywords gate (multi-step-012).
- [ ] "How does X start / begin / open" ranks the book's first chapter (literature-001).
- [ ] Within a found chapter, passages that match the question's terms rank above the chapter's opening
  (literature-006, vocab-gap-009).
- [ ] Mac replay on the tuning split; gate: recall@4 by quote (not by chapter, below) up, no grounded lost.

## Task 5: eval fixes

- [ ] Recall by quote: a passage counts as found when it contains an answer quote, not when its chapter or article
  matches (location matching overstates recall@4: who-wrote-004, literature-005 and -006).
- [ ] A second grader on the 4 borderline tuning grades (history-001, geography-general-001, who-wrote-009,
  medical-002); fix tune items whose expected answers are wrong (medical-029's "no known cure" is grounded). Tuning
  split only.
- [ ] The report shows grounded per domain (spec §8; R1 Task 11 never produced it).

## Task 6: search reaches older wording and dictionary entries

These need full phone runs, so they're batched into one run.

- [ ] A small term table for the rewrite: modern → older words the Classics use (alcohol → wine, strong drink;
  immigrant → stranger; tax → tribute; intelligence → spies; dropsy → edema), and common knot names to their articles.
  Built from the tuning misses plus a general list, so it isn't fitted to the split alone.
- [ ] "What does X mean" and "what is a X" look up the exact Wiktionary headword first (words-001, -002).
- [ ] The media cue words "movement", "piece", "suite" and "chapter" (R3's walkthrough), with tuning questions
  for them and the media-intrusion check (bar 0).

## Task 7: Classics plot and opening cards

- [ ] Per Classics book, a short card: what happens, the main characters, the first line. Written on the Mac from the text,
  checked against it, shipped with the vectors. Covers "how does X begin", "who is X" (the P&P Darcy/Bingley mix-up)
  and the literature misses. Start with the 50 most read; measure before doing the rest.

## Task 8: prompt rules for misreads

- [ ] Superlatives ("a passage calling X the second longest means X isn't the longest") and round trips. A/B on the
  phone by prompt replay over the misread set and sample-40; gate: no grounded or decline loss.

## Task 9: books you add (the own-book group)

- [ ] Imported EPUBs are chunked with `:core:text` and embedded on the phone with EmbeddingGemma, in the background,
  stored beside the catalog vectors (R1's format). PDFs' text chunks too (`PdfText`). Builder's call: when it runs
  (charging, idle) and how progress shows.
- [ ] Without this the own-book group can only be answered by keyword search; it's scored and reported separately
  until this lands.

## Task 10: scoring

- [ ] A full tuning-split run with everything kept (grounded, declined, multi-step, medical, latency per stage).
- [ ] **Held-out scoring 1 of 2**: the whole set on the Pixel 6, graded by a separate session per `RUBRIC.md`. Totals and
  per-domain numbers only reach this session.
- [ ] **Best guess** re-scored on E2B: confidently wrong ≤ 10% (eval spec; E2B was 20% on the Plan 9 held-out set, and
  E4B, which wrote guesses then, is gone).
- [ ] Neal's feel test, redone on the release build (R5 Task 7's was "mostly poor" before the follow-up fix and was never
  repeated).

## Task 11: pass or stop

- [ ] **Pass**: the spec's pass path; nothing to flip (F1 part 2 already flipped it). Record the numbers in the
  roadmap and the store listing's Preview wording stays or goes, Neal's call.
- [ ] **Fail**: one more round on the tuning split, then scoring 2 of 2. If it fails again, Neal decides between Ask as
  a Preview with the numbers on record (amend spec §8 and §11, which still say Ask leaves debug only on a pass) and
  turning Ask off in release until the LoRA or a newer model.
- [ ] Either way, write the decision into the spec, and say plainly in the roadmap that the Preview shipped before the
  bar was measured.

## Task 12: housekeeping from the audit

- [ ] "Carried forward from R1, R4 and R5" sections in the roadmap (none exist): R1's results write-up (Task 12) and
  what it handed on; R4 Task 8's per-phone-model item; R5's open items.
- [ ] The E4B catalog entry: remove it, or keep it with a reason (R4 said removed, R5 said kept "until Neal says
  otherwise").
- [ ] Watch for a Google-built E2B QAT file (R0: same quality, 0.5 GB less memory).
- [ ] The latency target (9 s; 12 s median now): R5 Task 6's plain-keywords retry was the one route under 9 s found;
  revisit after Tasks 2–8, not before.
- [ ] Stale roadmap lines: "Gate `Features.RESEARCH` … before any release build" and "deleteChat … no error guard".

## Not in this plan

- The behaviour LoRA (waits on LiteRT-LM #3173), second-hop retrieval for two-fact questions (the most effort and
  latency per question; after this round's numbers), "What do I have on X?" (a fast follow after launch), Spanish Ask
  (Plan L4's bar).
