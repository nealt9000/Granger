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

- [x] Written blind on Fable (2026-11-02): `tools/eval/dataset/heldout2.json` (171 items) and `heldout2-own.json`
  (8 on imported books). 127 answerable, 52 unanswerable (29%), 27 medical (16 answerable), 19 multi-step, 5 follow-ups.
  Answerable per domain: classics 33 (27 books), medicine 16, food, making, science, technology, maps, reference and
  thought 10 each, own-book 8. Validator 0 problems. The newest snapshot is now 2026-11-02 (foss.cooking), so its
  after-snapshot items are after that or live. The writer's subagents were briefed the same way and every quote was
  re-checked. Only these totals reached the tuning session.
- [ ] Own-book EPUBs to import to the test phone, stems kept (sources are `book:import:<stem>`):
  `~/.cache/granger/eval-books/own/pg55.epub` (The Wonderful Wizard of Oz), `pg23.epub` (Narrative of the Life of
  Frederick Douglass), `pg201.epub` (Flatland). Map the source when scoring if the app ids them differently.
- [ ] The eval library on the Mac: 18 pack links added to `~/.cache/granger/eval-packs/`; the Classics release in
  `~/.cache/granger/eval-books/dl/`, linked into `by-ref/`.
- [x] Neal reviewed it (2026-11-02): "looks good". Fixes, if any later, go back through the author, not this session.
- [ ] The test library on the Pixel 6 matches the set: every pack and snapshot it cites, the Classics release, the own-book
  EPUBs imported. Recorded in the set's notes.
- [ ] A media group (track and chapter citations, spec §8) needs the phone's library export; written by the same author
  later, or the existing `dataset/media.json` stands in for this round. Neal's call.

## Task 2: the decline check

Deterministic declines, in `DeclineCheck` (`:core:research`), wired into `ResearchPipeline`.

- [x] **Before search** (`DeclineCheck.unknowable`, English only): somewhere near the asker ("near me", "down the street",
  "my street / town", opening hours), the asker's own records ("my" within two words of password, birthday, package,
  flight, representative, insurance and the like), or live ("right now", "tonight", "last night", "this season / weekend",
  "next week"). It declines at once, with no search or model run. Kept narrow on purpose, and each exclusion is tested:
  "my" alone ("fix my bike chain", "my car's oil", "my IP address", "my wifi"), "nearby" (stars), street names (Baker
  Street, Abbey Road, *On the Road*) and "when will" (Halley's comet).
- [x] **After the answer** (`DeclineCheck.offTopic`): the question names a year that none of the cited passages has, so the
  answer is another year's event. Questions that work with dates (`Entities.asked`) are left out.
- [x] **"…, but the passages do not specify"**: a lack that comes after the turn now declines (`Citations`).
- [x] `PersonalMedical` covers one person's body ("why does my left knee click", "my back hurts"), with the medical signal.
- [x] Tuning split (`DeclineCheckTest.tuningSplit`): `unknowable` catches 31 of 76 unanswerables and 0 of 126 answerables;
  the floor is pinned.
- [x] Replay over `r5-t7-all`'s recorded replies: **8 more unanswerables declined** (001, 026, 035, 041, 054, 057, 058,
  059), **0 answerables changed**. Declined 63 → 71/76 (93%) on the tuning split. medical-027 should follow through
  `PersonalMedical`, but it depends on the rewrite's flag, so only a phone run shows it. The rules were written from
  these questions, so the held-out set decides what they're worth.
- [ ] Phone check: a check-declined turn reads as an ordinary decline, Best guess is offered, and the guess says it
  can't be known.
- [ ] Not caught, left to the model or later rules: 025 (the 2026 World Series answered from a passage that has "2026"),
  medical-029 ("when will there be a cure"), follow-up-011, 045.

## Task 3: the dates block says its conclusion

`YearFacts` gives E2B both dates; E2B still drew the wrong conclusion twice (multi-step-002, -004).

- [x] The block ends with "So, for the question: …" for the comparison the question asks: who was older (or neither,
  same day), who died first, who lived longer, and could / did / was X (alive at the event, not yet born, or had died).
  English only. **On R5 decision 2 ("no question parsing"):** this reads only the comparison words the block already
  treats as cues (older, younger, died first, lived longer, and a leading could / did / was), no other parsing; Neal to
  confirm it fits the rule.
- [x] Rebuilt from `r5-t7-all`'s recorded passages, every block matches what was recorded, and 4 prompts gain a verdict:
  002 ("No: … Jane Austen had already died"), 004 ("Neither is older: … the same day"), 011 and 020 (both "Yes, as far
  as the dates go", both already grounded).
- [x] Phone replay: `answerers.py run r2-t3-base r2-t3 --set r2-verdict --model <E2B>` (base `r2-t3-base` is r5-t7-all
  with those 4 prompts; set `sets/r2-verdict.json`). Gate: 002 and 004 grounded, 011 and 020 still grounded.
  **Result 2026-11-02: half met.** 004 now answers "Neither … born on the same day, February 12, 1809 [3][4]"; 011 and
  020 stay grounded. 002 (could Jane Austen have read Frankenstein) still says NOT IN LIBRARY, under the verdict as
  built and two rewordings tried on the same prompt (`r2-t3a`, `r2-t3b`); the most explicit drew "the passages do not
  state whether Jane Austen could have read Frankenstein". E2B reads "could she have…" as asking whether a passage
  says so; no further wording tuned to one question. The verdict stays: +1 grounded, no losses.
- [ ] A reply that contradicts the block's verdict is flagged in the eval report (not hidden in the app this round).

## Task 4: ranking

- [x] **A replay of the app's own ranking on the Mac**: `PipelineReplayTest` runs the real `ResearchPipeline`, configured as
  `GrangerApp` builds it, on a run's recorded searches (`found.jsonl`, dense hits with cosines, the rewrite's keywords),
  with a fake model that captures the prompt. It reports passages chosen as recorded and recall by quote (an answer
  quote in the prompt). `GRANGER_REPLAY=r5-t7-all ./gradlew :core:research:test --tests '*PipelineReplayTest'`, about
  30 s. On `r5-t7-all`: 193/202 choose the recorded passages (the 9 others are Task 2's new declines); recall by quote
  **65/126**. Tuning runs only (it refuses a held-out dataset).
- [x] Keywords that only ask how long or whether ("years passed", "how old": `YearFacts.cueOnly`) don't count toward
  ranking. multi-step-012 goes from no passages to its answer; recall by quote 65 → **66/126**, no other question's
  passage count or quote changes.
- [x] Looked at and left to other tasks, since a rule for each would fit the tuning split and not much else:
  literature-006 (the book says "whitewash", the question "painting") and vocab-gap-009 ("social tyranny" for "peer
  pressure") are wording gaps (Task 6); literature-001 ("how does X start") and -002 (Holmes's first words) want the
  opening and the first meeting (Task 7's cards); multi-step-010 (Methuselah and Noah) has its verses only among the
  dense hits at 0.80 and 0.74, while a "Methuselah Foundation" article takes a slot (two-fact retrieval, after this round).

## Task 5: eval fixes

- [x] **Recall by quote**: `metrics.quote_in` counts a passage as found when an answer quote is in its text (words only:
  accents, case, punctuation and spacing folded, so "hamilton , james" matches "Hamilton, James"). The report shows it
  first and keeps recall by location, marked as overstating. `PipelineReplayTest` folds the same way. On `r5-t7-all`:
  **63/126 (50%) by quote** in the first 4, 48% by location: close in total, but not the same questions.
- [x] **Domains**: every tuning item has a `domain` (from its first answer's source; unanswerables medicine or reference),
  and the report shows grounded and declined per domain. `r5-t7-all` by domain:

  | Domain | Grounded | Declined |
  |---|---|---|
  | classics | 21/32 (66%) | – |
  | reference | 36/55 (65%) | 50/61 (82%) |
  | medicine | 23/25 (92%) | 13/15 (87%) |
  | science | 11/12 (92%) | – |
  | making | 0/2 | – |

- [x] The four "strict" grades (history-001, geography-general-001, who-wrote-009, medical-002) were reread against
  `RUBRIC.md`: each applies it as written ("torn down" isn't what [1] says; a wrong passage number is not-grounded).
  They stand. medical-029 ("when will there be a cure for Alzheimer's") is a future question, rightly unanswerable.
- [ ] **The tuning split is blind in five domains**: it has no food, technology, maps or thought questions and two in
  making (spec §8 asks the tuning session to add them). Write about 10 per domain into `tune.json`, from the packs on
  disk, before tuning anything those domains depend on.

## Task 6: search reaches older wording and dictionary entries

These need full phone runs, so they're batched into one run.

- [x] `OlderWords`: about 30 well-known pairs, modern → the Classics' words (alcohol → wine, strong drink; immigrants →
  strangers, sojourners; taxes → tribute; pregnant → with child; suicide → self-slaughter; slaves → bondservants) and
  old disease names → modern ones (dropsy → edema, consumption → tuberculosis, apoplexy → stroke, ague → malaria).
  A general list, not the misses alone. An entry matches a **whole keyword only** ("energy consumption" isn't
  tuberculosis), and is added as alternates (`alcohol|wine|strong drink`), which the search already handles.
- [x] Behind a debug flag: `ResearchPipeline(olderWords = …)`, `eval.py run … --older on`, off in the app until measured.
  8 tuning questions get alternates (bible-001, vocab-gap-001 to -004 and -010, medical-032, -033). Mac replay on
  recorded searches: no change in recall by quote (64/126; the alternates' own search hits need the phone), and no
  loss among passages already found.
- [ ] Phone run of the tuning split with `--older on` against the same build without it. Gate: grounded up, no loss on
  the 8, declines unchanged.
- [x] **Found: the phone's test library lacks Wiktionary and Knots.** No passage from either appears in any
  `r5-t7-all` search, so 6 tuning answerables (words-001 to -004, knots-001, -002) could never be answered there: the
  ceiling was 120/126. The dictionary-headword lookup waits until Wiktionary is on the phone; the held-out set has 3
  knots questions, so Knots (with its full-text index) must be on the phone before held-out scoring.
- [x] **Dictionary lookup by headword, measured and on (2026-11-03).** `Headword.word` names the word a question asks the
  meaning or origin of ("what does X mean", "the word X", "qué significa X", "la palabra X"; not "comes from" alone, not
  "what did my doctor mean"); `LibraryPassageFinder.headword` reads its entry from the question's Wiktionary by path, the
  language's section only, without pronunciation and translations (at most 2 passages: etymology, senses); they go
  first. Wiktionary EN (`wiktionary_en_all_nopic_2026-08`, 8.5 GB, sha256 checked) is on the test phone.
  `r2-t6-head` against `r2-t6-lib` (same build and library, headwords off): grounded 98 → **100/126**, declined 71/76
  both, no losses; words-001 (wherefore: "why, for what reason") and words-002 (betimes) now grounded from the entry.
  The first run found nothing: the eval's `RecordingFinder` didn't pass `headword` through (fixed, 7201f545); the app
  calls `LibraryPassageFinder` directly. `headwords = true` in `GrangerApp`; the eval keeps `--headwords on` explicit.
- [x] **"What did I search for"** declines before search (`DeclineCheck`, the asker's own searches and lookups):
  unknowable catches 32/76 tuning unanswerables, 0 answerables. With the trimmed library, `r2-t6-lib` declines
  **71/76 (93%)**.
- [ ] The media cue words "movement", "piece", "suite" and "chapter": with media questions for them and the
  media-intrusion check (bar 0), which need the phone's library export (`dataset/media.json`). Not started.
- [x] **Measured and on (2026-11-02).** `r2-t6-older`: the 8 tuning questions whose rewrite keywords hit the table, run
  with `--older on` on the same build and library as `r2-t5`. +2 grounded, no losses: vocab-gap-002 (immigrants; the
  Douay-Rheims Leviticus 19:33–34, was NOT IN LIBRARY) and vocab-gap-010 (intelligence gathering; "spies and spies
  alone", was not-grounded; the grader calls it borderline, the words are a commentator's in that edition). The other 6
  unchanged in grade. `olderWords = true` in `GrangerApp`; the eval keeps the flag explicit (`--older on`).

## Task 7: Classics plot and opening cards

- [ ] Per Classics book, a short card: what happens, the main characters, the first line. Written on the Mac from the text,
  checked against it, shipped with the vectors. Covers "how does X begin", "who is X" (the P&P Darcy/Bingley mix-up)
  and the literature misses. Start with the 50 most read; measure before doing the rest.
- [x] **The 50 cards** (2026-11-02, 04665c9d): the 50 narrative Classics with the most Gutenberg downloads (novels,
  plays, epics; philosophy and scripture left out). `tools/pack-builder/classics/cards/<slug>.json`, built by
  `cards.py build` into one EPUB, *Granger Classics: Book Notes*, three passages a book that each name it: how it begins
  (the first line verbatim, checked mechanically), what happens, who's who. Written by five subagents from the text,
  then checked claim by claim by five others (819 claims: 46 wrong and 20 unfound, all corrected; logs in `verify/`).
  A translated work gives both dates (War and Peace "1865–1869; tr. Aylmer Maude and Louise Maude, 1923").
- [x] **Shipping, decided for the measurement:** a separate notes book, not a chapter added to each Classics EPUB. It
  measures with no release and no app change: `eval.py import` (a debug import over adb) puts it in the phone's library
  and indexes it, and `tools/vectors/book.py` embeds it on the CPU for the pushed-vectors directory. If it passes, it
  ships as one more catalog book with its own vectors.
- [x] Measured (2026-11-03), against a new baseline `r2-t7-base` on the restored library (below), both with older words
  on: grounded 97 → 98 of 126 (classics 20 → 21 of 32), declined 68/76 both, no losses. Only 3 replies changed: "how
  does Pride and Prejudice start" is now grounded from the notes (first line verbatim), Scrooge's partner and Odysseus's
  journey reworded and still grounded. The notes were found for 23 questions but chosen for 2. The classics questions
  still failing are mostly scripture and detail (the fence, Holmes's first words, Lilliputian height) that a card
  doesn't cover; "Who's telling the story?" found the notes and still declined. Small gain, no harm: ship as a catalog
  book is reasonable but won't move the bar alone.

**`r2-t5`, the full tuning split on Tasks 2–4 (2026-11-02):** declined 69/76 (91%), grounded 93/126 (74%), recall by
quote 64/126. Not comparable with `r5-t7-all` (63/76, 91/126): the phone's library changed between them. Gone: IEP,
WikEM, devdocs C/Git/JavaScript/Python, six Stack Exchange sites, foss.cooking, Wikipedia Geography and the Classics
ZIM; new: Knots, Wikipedia Computer and Movies, Spanish packs. Attributable to the code: 9 unanswerables newly declined
by the decline check (none of them answerable) and multi-step-004 by the verdict. Neal to decide the test library
before held-out scoring.

**Test library restored (2026-11-03), then trimmed:** 20 packs no eval set cites or lists and that gave `r5-t7-all`
no passage were removed through the app (`eval.py remove`): six Stack Exchange religion and philosophy sites, Wikipedia
Computer, Movies and Spanish climate change, Gutenberg LCC-M, the Central America maps, the OSM wiki, prunelle, ready.gov,
tonedear, trueprepper and the four zimgit packs. foss.cooking and devdocs Python went to the held-out set's snapshots
(2026-11-02, 2026-10-31) and devdocs HTTP and SQLite were added, matching `~/.cache/granger/eval-packs`. Before that: IEP, WikEM, the Classics ZIM (`classics-2026-10-16` release, sha256 checked),
devdocs C/Git/JavaScript/Python, foss.cooking, cs/unix/softwareengineering/reverseengineering Stack Exchange pushed back
from `vectors-work/sources` with their vectors (`files/vectors/index.tsv`); engineering and hermeneutics Stack Exchange and
Wikipedia Geography (maxi) re-downloaded from Kiwix (2026-10 builds, sha256 checked), no vectors, as before. The packs
added since stay. Baseline on it, `r2-t7-base`: declined 68/76 (89%), grounded 97/126 (77%). The book notes stay
imported for held-out scoring (Neal, 2026-11-03), and the own-book group's EPUBs (`pg55`, `pg23`, `pg201`) are imported
too (keyword search only until Task 9).
- [ ] Neal reads a sample of cards before they ship.

## Task 8: prompt rules for misreads

- [x] Superlatives ("a passage calling X the second longest means X isn't the longest") and round trips. A/B on the
  phone by prompt replay over the misread set and sample-40; gate: no grounded or decline loss.
  **Result 2026-11-03: not met; nothing ships.** The misread set is `r2-t7-notes`'s 12 answerables graded wrong with the
  answer in the prompt (China "most populous" when [4] says second, Yangtze "longest" when [4] says third, Sahara without
  its "excluding ice deserts", "the passages don't say" beside the blue whale, Curie's three Nobels, the Moon's round trip,
  the thunder rule, three date sums, "the love of money", the teacher chain); `sets/r2-misread.json` adds sample-40.
  The replay is deterministic: the control (`r2-t8-ctl`, prompts as recorded) gave all 45 replies as recorded.
  - Variant a (`r2-t8a`): two rules after "Don't add facts" (keep a passage's ranking and its conditions; work out one
    part, one way or one case from a total, round trip or rule). 0 of 12 fixed; the thunder reply got worse ("six miles
    or nine kilometers") and the teacher chain named Anaximenes.
  - Variant b (`r2-t8b`): one check just before the question. 0 of 12 fixed; the biggest animal became the woolly
    mammoth.
  - E2B doesn't act on reading rules, wherever they sit. Stopped there rather than tune wording to 12 questions. What's
    left for misreads is the model (the LoRA, or a newer one) or code that does the reading, which R5 ruled out beyond
    the year cues.

**A deeper dense search (2026-11-03, Mac only).** granger-1a's `runs/dims-256v768`: 768 dims isn't worth it (with the
keyword passages, 92 vs 93 of 126 answers found at k = 24), but at 256 a deeper k finds more (92, 93, 98 at k 24, 48,
100). Does it reach the prompt? `tools/eval/experiments/dense_k.py` rebuilt `r2-t7-base`'s dense hits offline at k 24,
48 and 100, and `PipelineReplayTest` ranked them as the app does: an answer quote in the prompt 62, 63, 63 of 126. The
one gain is multi-step-015 (light to the Moon), a misread. The extra hits rank below the top 4, so `k = 24` stays.

**A named-source boost (2026-11-03, Mac only; reverted).** Every question not about dates got a passage from each work
or article it names among its first 4 (`Entities.boostNamed`), names from the question's capitals, or those and the
rewrite's keywords. Replay of `r2-t7-base`: quote in the prompt 64 → 64 either way (keywords: +geography-general-002,
-medical-009, 45 prompts changed). The misses it aimed at already had the named source in the prompt, just the wrong
part: four Kalevala passages without the answer, the Gita's and Moby-Dick's wrong chapters, Sale's Koran instead of
Rodwell's. Of 90 answerables whose quote is found, 64 reach the prompt; what's left is "right source, wrong passage",
which lexical and embedding scores don't separate. A reranker that reads question and passage together is the next test
(offline first).

**Cross-encoder rerankers (2026-11-03, Mac only; not adopted).** `tools/eval/experiments/rerank_ce.py` over `r2-t7-base`:
every passage the search found (about 211 per question) scored against the question, best 4 to the "prompt"; the app gets
an answer quote there for 65 of 126 by this count. MiniLM-L6 46, mxbai-rerank-xsmall 54, bge-reranker-base 53: each
gains 7–9 right-source-wrong-passage items (follow-up-004, -005, literature-004, medical-002, multi-step-010…) and loses
18–27, mostly two-fact and medical questions where the app's own rules choose the mix. Keeping the app's first 3 and
letting the reranker fill the 4th: MiniLM 61, bge 65 (6 gained, 6 lost). No net gain, for a 22–278M model and 1.7–7.8 s
of Mac CPU per question (more on the phone). Ranking is left as it is.

## Task 9: books you add (the own-book group)

- [x] **Done (2026-11-03).** `BookVectors` (`:core:library`) makes the vectors of every book you import, on the phone:
  the keyword index's chunks (an EPUB's chapters through `XhtmlText`, a PDF's pages through `PdfText`), embedded as
  `build.py` embeds a catalog book (`title: <book title> | text: <chunk>`, `TextEmbedder.embedDocuments`), 8 at a time,
  each batch taking the model's turn (`ModelsAdapter.MODELS_IN_USE`), so a question asked meanwhile waits one batch at
  most. Written whole to `files/vectors-own/import-<sha>.gvec` (R1's format, 256 dims) and listed under the book's id
  for the installed embedder only (a new embedder means new vectors). `DensePassageFinder` rebuilds a PDF hit from its
  page. Tests: `BookVectorsTest` (5: an EPUB found by meaning, once per embedder, nothing without one, only imports, a
  PDF by page).
- [x] **Builder's call, when it runs:** right after an import is indexed, and at app start for any book still without
  vectors (`GrangerApp.prepareBooks`: words first, so a new book is searchable by keyword at once, then vectors), while
  the app is open; no charging or idle condition, since a typical book takes a minute or two. **Progress:** a
  `StateFlow` (`BookVectors.progress`: title, chunks done of total, books left) and a log line per book; no UI yet.
- [x] **On the Pixel 6** (`eval.py embed-books`): the 3 own-book EPUBs and the Classics notes, 1,063 chunks in 248 s,
  about 0.23 s a chunk (Flatland 286 in 60 s, Douglass 215 in 72 s, Oz 412 in 87 s, the notes 150 in 27 s). Parity with
  the Mac (the notes, embedded by both): the same 150 chunks and hashes, cosine 0.966–0.980 between the two, and every
  phone vector's nearest Mac vector is its own chunk. A probe question about Douglass gets 12 of its 24 dense hits from
  the imported book.
- [x] Found on the way: at a cold start the catalog loads after the books are scanned, so the installed embedder was
  unknown and the job stopped; it now waits up to 30 s for the catalog.
- Other books you add (archive.org, Gutenberg) aren't embedded yet: `BookVectors(origins = …)` covers them when wanted.

## Task 10: scoring

- [x] A full tuning-split run with everything kept (grounded, declined, multi-step, medical, latency per stage).
  **Done 2026-11-03, `r2-t10-all`** (dense, entities, year facts, older words, headwords; own-book vectors on the
  phone): **grounded 100/126 (79%), declined 71/76 (93%)**, the same as `r2-t6-head`. Two items swapped: science-006
  gained, literature-007 lost (the right passage was chosen; the reply has Ahab die on the first day). Multi-step
  10/20; medicine 24/25 grounded, 14/15 declined; classics 20/32; reference 44/55. Recall@4 by quote 69/126. First
  words median 12.8 s (p90 17.1 s), total median 16.8 s. Imported-book passages were chosen in 3 replies (the
  Classics notes; all grounded), so the own-book vectors cost nothing. Latency per stage isn't in the report; the
  total and first-words times stand in.
- [x] **Multi-step, before scoring (2026-11-03, Neal's call).** Of `r2-t10-all`'s 10 multi-step misses, 7 lacked one
  of the two facts in the prompt and 3 had them but reasoned wrongly. Four changes to YearFacts and the pipeline:
  an English "the fall of the Berlin Wall" gives "fall" to the name after "of"; an end with no word for it takes its
  article's range end (1989), not a passage's first year (1961); when the four passages can't date every named thing,
  `YearFacts.complete` brings in one that can from the things' own articles or the search by meaning's hits (keyword
  hits only naming them took Columbus, Ohio's 1831), and a person's event must be a sentence they're in (not Lothar
  Meyer's 1864 in Mendeleev's article); and when the model declines with a date verdict in front of it, the verdict is
  the answer. Mac replay: only 009's and 018's passages change; recall by quote unchanged (67/126). Phone,
  `r2-ms-dates` (the 27 tuning items with date cues): **multi-step 10 → 13 of 20** (002, 009, 012), no losses, the one
  unanswerable still declined. 018 now counts from the 1871 table (not 1869; graded not grounded). Left: 003 (the KJV
  passage outranked by Shakespeare's), 010 (Noah's age is a count, not a date), 006 and 007 (second hop), 015 and 017
  (physics arithmetic). With the rest of `r2-t10-all` unchanged, the tuning estimate is 103/126 (82%).
- [x] **Which 8 articles the keyword search reads (2026-11-03, diagnosis).** `LibraryPassageFinder` reads `docs` = 8
  articles: every pack's top 8 hits per keyword, sorted by Kiwix's score and cut. The score is a percentage and the
  top hits tie at 100 (154 of 159 tuning questions read eight 100s), so the packs' alphabetical order decides:
  Appropedia and Based Cooking read first ("the bible on alcohol" read Limoncello and Solar drying in Uganda).
  `tools/eval/experiments/article_cut.py` replays the recorded keywords through libzim against the phone's 32 packs
  (1,270 of the 1,271 articles the phone read are among its hits). The answer's article is among the hits for 79 of
  the 95 answerables with an article answer, but among the 8 read for 36. Title match first (an article whose title
  holds a whole keyword), then most keywords, then rank: 46 at 8 (+16, -6), 50 at 12, 54 at 16, 60 at 24. Most
  keywords first: 37 at 8; best rank first: 40. Reading 8 articles is 0.47 s median, alongside the books' 1.2 s.
- [x] **Title first, 16 articles, Classics twins dropped before the cut; full tuning run `r2-t10-cut` (2026-11-03).**
  101/126 grounded (80%, bar 80%), 71/76 declined (93%). Against `r2-t10-all` (100): +5 (literature-005,
  literature-007, multi-step-002, -003, -012), -4 (vocab-gap-002, science-006, multi-step-001, -008), all four lost
  with the answer in the prompt (model misreads: a misquoted verse, "not stated" beside the blue whale, an uncited
  "elected 1860", "20 or 21"). The multi-step gains are the date work; multi-step-009 fell back to 127 years. The cut
  moved answers from never found (48 to 45) to found but not chosen (12 to 17); in the first 4 by quote 68 (69). Cost:
  keyword search 1.49 to 2.21 s median (reading 16 articles, 0.47 to 1.36 s), now longer than the books' 1.2 s; first
  words 12.2 s median (12.8). Run-to-run noise on answered items is about 4 either way.
- [x] **Held-out scoring 1 of 2** (2026-11-03): the whole set on the Pixel 6 with the `r2-t10-cut` build and flags plus
  `--guesses small` (`r2-h2-1`, `r2-h2-1-own`), graded blind by a Fable agent per `RUBRIC.md`; only totals reached this
  session. **Fails both bars:** answerable grounded 85/127 (67%; main 79/119, own-book 6/8), unanswerable declined
  44/52 (85%). Recall@4 by quote 47/119 (39%; tuning 54%); found but not chosen / never found 12 / 51. Per domain
  grounded: classics 19/33, food 6/10, making 7/10, maps 8/10, medicine 11/16, reference 7/10, science 6/10,
  technology 7/10, thought 8/10; declined: medicine 8/11, reference 12/14, classics 3/4, making 2/3, technology 3/4,
  the rest all. Best guess (E2B): confidently wrong 2/51 (4%), right or partly 41/51 (80%), passes. First words 12.6 s
  median. Grader's note: most misses are replies that answer an adjacent question, misread a number or skip a
  multi-step's second half, not invented claims. The own-book recall-by-location (0%) is a source-id mapping artefact
  (sha256 ids vs `pg` stems); grades unaffected.
  What it taught us and the questions for the restart: `docs/ask/r2-heldout-1-lessons.md`.
- [ ] **Best guess** re-scored on E2B: confidently wrong ≤ 10% (eval spec; E2B was 20% on the Plan 9 held-out set, and
  E4B, which wrote guesses then, is gone).
- [ ] Neal's feel test, redone on the release build (R5 Task 7's was "mostly poor" before the follow-up fix and was never
  repeated).

## Task 11: pass or stop

**Neal's decisions (2026-11-04), after scoring 1 of 2 failed.** The fail path's "one more round on the tuning
split" is not taken as written: the tuning split is a training set now (`docs/ask/r2-heldout-1-lessons.md`).
Instead:

1. **A development set, written blind** (`docs/ask/dev-set-brief.md`): `dataset/dev.json` (209: 148 answerable,
   61 unanswerable, 33 medical, 21 multi-step, 6 follow-ups; 42 Classics books) and `dev-own.json` (10), split `dev`,
   written 2026-11-04 by a separate Fable session that read only the brief, the authoring guide and the library.
   Scored freely; never tuned on item by item. Every change is judged by kind on this set, and only changes that
   hold on it go to scoring 2 of 2. Neal reviews the set before its first scoring.
2. **The held-out misses by kind** (`docs/ask/r2-heldout-1-by-kind.md`, blind, leak-checked): retrieval 25 of 42
   (never found 11, found but not chosen 14), of which 9 of the 14 were in the dense top 3; a further 6 "adjacent"
   misses had the right chapter or article but the wrong excerpt. Classics novels 3 of 13 grounded against 16 of 20
   for the rest of classics. Multi-step 5 of 19. Wrong declines 15, 6 of them before the answer stage (the ranking
   gate) with the passage found by a search in 4; on tuning the gate never declined an answerable. Model-side kinds
   (misread 1, partial 1, invented 2) are inside noise.
3. **E2B stays the answer model** (Neal: within its scope; the misses are what reaches it, not what it does with it).
   The LoRA stays blocked (LiteRT-LM #3173, no maintainer reply).
4. **Phone time is shared** with the efficient-tier and Spanish workstreams; the dev set's baseline run (about 4 h)
   is scheduled with Neal.

**Levers to test on the dev set, by kind** (Mac replay on `r2-t10-cut` first, no loss there; then the dev set):
the ranking gate (`denseGate` 0.78: on tuning, 0.74 passes 15 more unanswerables to the model for +1 recall, so a
trade to be judged with both halves of the dev set); the fiction weight on dense hits (fiction-source questions with
no literary cue word grounded 1 of 9 held-out, 6 of 8 tuning); the per-article cap (3 chunks of one encyclopedia
article can fill 3 of the 4 slots over the work's own text); excerpt choice within a found chapter or article; the
Classics notes, found for 13 of 31 held-out classics questions and chosen for 2.

- [x] **Replay on `r2-t10-cut` (2026-11-04, `merge.py` variants).** The fiction weight off the dense side (`lexfw`):
  no change at all (61/126 by location, 65 by quote, 47/76 empty, 2 intrusions), so the tuning split never trips it
  and only the dev set can show it. Per-article cap 2: no change. Cap 1: +3 by location, −10 by quote (the chunk
  with the answer sentence is dropped for another of the same article), not adopted. The gate grid is in Task 10's
  sweep: 0.74 passes 15 more unanswerables to the model for +1 recall.
- [x] **The eval on the Mac (Neal, 2026-11-04).** An arm64 emulator (API 36, 8 GB, AVD on the X10 Pro SSD) with a
  copy of the phone's library and E2B's CPU bundle (the GPU bundle holds no CPU model; a debug build on the emulator
  picks `MODEL_BACKEND = CPU`). The embedder's cosines are within 0.01 of the GPU's; first words 4–6 s, a turn 5–8 s,
  so a dev-set run is about an hour and the phone stays free. Dev and tuning scoring run there; scoring 2 of 2 stays on
  the phone. Parity against `r2-t10-cut`: `emu-t10-cut-1` (the whole tuning split in 20 min): **102/126 grounded (81%),
  72/76 declined (95%)** against the phone's 101 and 71; recall@4 by quote 67 (68), found-not-chosen / never found
  16 / 46 (17 / 45); same outcome on 197 of 202, the same 4 passages for 125, 80% passage overlap; the keyword
  rewrite differs on about half the questions (CPU and GPU numerics). Notes: `~/.cache/granger/emu-stage/`.
- [x] **Dev set baseline on the emulator, `dev-base-1` + `-own` (2026-11-04, the held-out run's flags, 25 min).**
  **Graded 2026-11-04 (Neal reading the set alongside): grounded 83/148 (56%), own-book 6/10; declined 51/61 (84%);
  best guess confidently wrong 13%, right or partly 75%.** Per domain grounded: classics 19/44, food 6/12, making 5/12,
  maps 9/12, medicine 15/20, reference 9/12, science 6/12, technology 8/12, thought 6/12. The 69 misses by kind:
  **in the prompt and not grounded 25, in the prompt and declined 7** (the model, 46%); gate-declined 8, never found
  11, found but not chosen 18 (retrieval, 54%). Classics: 10 of its 25 misses had the answer in the prompt.
  Mechanical numbers from the run: declined 51/61 (84%); recall@4 by quote
  68/148 (46%), by location 57%; first words 5.6 s median. Misses by kind over the 148 answerables, with "found"
  meaning either search's full hit list by location or quote: in the prompt 90, **found but not chosen 43**, never
  found 15; 8 declined by the gate before the model, 6 of those with the passage found. Classics: 20 of 44 found but
  not chosen; fiction-source questions with no literary cue word 13 of 20 missed against 4 of 9 with one. Of the
  43, Wikipedia articles about the work fill most slots; in 27 misses (dev; 19 tuning, 30 held-out) the right book
  or article was chosen but another chunk of it, all 79 such chunks from the dense list across 67 chapters, and the
  answer's chunk scores lower lexically than the chosen ones in 15 of 16 (other chapters use the question's words,
  the answer's doesn't). Spanish-pack passages sat among the 4 for 11 dev, 17 tuning and 10 held-out questions.
- [x] **Replay on `dev-base-1` and `r2-t10-cut` (2026-11-04).** Assumed cosine for keyword-only passages (0.66 to
  0.72): +1 by location on dev, nothing else. Fiction weight off the dense side: nothing. Dropping passages in a
  language the question isn't in: +2 location on dev, +1/+1 on tuning, no losses: **adopted** (`ResearchPipeline`,
  by `Passage.language`). **More passages in the prompt**: recall by quote at 4 / 6 / 8 is 69 / 76 / 79 on dev and
  65 / 70 / 74 on tuning (location 84 / 98 / 104 and 61 / 68 / 70): the one lever by replay that clears noise on both
  sets, with a prompt-length, latency and misread cost to measure on a run (`eval.py run --passages 6`).
  **The gate stays:** on dev, 0.74 recovers no answerable (the 8 gate-declined answerables don't have the passage
  in their top 4 ungated either) and turns 11 of 25 gate declines into model turns; 0.78 with the lexical gate at
  1.3 declines 5 more on dev at no cost there but −3 recall on tuning (noise). Per-article cap 2: +3 location, +1
  quote on dev, 0 on tuning (noise; revisit with 6 passages).
- [x] **Six passages in the prompt, tuning on the emulator, `emu-t10-p6` (2026-11-04): not adopted.** Against
  `emu-t10-cut-1` (4 passages): recall by quote over the chosen 67 → 70, **grounded 102 → 100 (79%)**, not-grounded
  15 → 24, **declined 72 → 69** (four unanswerables the model declined with 4 passages it answers with 6), prompt
  5,540 → 7,580 characters median, first words 5.9 → 7.2 s. What retrieval gains, the model loses in misreads of a
  longer prompt. The model's reading, not the passage count, bounds this lever; stays at 4.

- [x] **The model half, the night of 4–5 November** (`docs/ask/r2-findings-2026-11-04.md` §8–§12). The export gap
  is Google's closed GPU executor and weight format, not a recipe: our export of stock E2B decodes at 9.7 tok/s on the
  Pixel 6 against the bundle's 14.5. Training pairs come from the tuning runs only (`tools/lora/pairs.py`; dev and
  held-out out). Two rank-16 attention adapters were trained on the Mac (`tools/lora/train_lora.py`), merged, exported
  and run on the dev set against a control export of stock E2B on identical prompts: 44/148 and **70/148 grounded
  against the control's 71** (Google's bundle 83). The adapter moves the decline rate only. No post-training
  quantization in litert-torch 0.10.0 gives a phone-usable E2B: block-32 int4 keeps the model and won't load on the
  Pixel 6 GPU; channel-wise int4 loads and wrecks it; int8 loads at 7.2 tok/s, damaged. No LoRA option has landed in
  LiteRT-LM 0.19.0 or litert-torch; #3173 is idle. Both model routes are closed.

- [ ] **Pass**: the spec's pass path; nothing to flip (F1 part 2 already flipped it). Record the numbers in the
  roadmap and the store listing's Preview wording stays or goes, Neal's call.
- [x] **Fail** (2026-11-05): the round was run on the dev set, not the tuning split (a training set now), and no
  lever cleared ±4 there (findings §3, §7, §12). Scoring 2 of 2 waits for a change that holds on dev; on the current
  pipeline it would confirm scoring 1 at the cost of the last blind set. **Open for Neal:** Ask as a Preview with the
  numbers on record (amend spec §8 and §11, which still say Ask leaves debug only on a pass), or off in release until
  a run-time adapter, a usable converter or a newer model.
- [ ] Either way, write the decision into the spec, and say plainly in the roadmap that the Preview shipped before the
  bar was measured. 2026-11-05: the roadmap says it (the R2 row and checklist B); the spec §8 carries the status and
  the open decision; the decision line itself waits for Neal.

## Task 12: housekeeping from the audit

- [ ] "Carried forward from R1, R4 and R5" sections in the roadmap (none exist): R1's results write-up (Task 12) and
  what it handed on; R4 Task 8's per-phone-model item; R5's open items.
- [ ] The E4B catalog entry: remove it, or keep it with a reason (R4 said removed, R5 said kept "until Neal says
  otherwise").
- [ ] Watch for a Google-built E2B QAT file (R0: same quality, 0.5 GB less memory). 2026-11-05: the shipped GPU bundle
  is already quantization-aware-trained in a closed format; our converter can't make one (findings §10, §12).
- [ ] The latency target (9 s; 12 s median now): R5 Task 6's plain-keywords retry was the one route under 9 s found;
  revisit after Tasks 2–8, not before.
- [ ] Stale roadmap lines: "Gate `Features.RESEARCH` … before any release build" and "deleteChat … no error guard".

## Not in this plan

- The behaviour LoRA (waits on LiteRT-LM #3173; tried outside this plan's tasks on 4–5 November, findings §9–§12), second-hop retrieval for two-fact questions (the most effort and
  latency per question; after this round's numbers), "What do I have on X?" (a fast follow after launch), Spanish Ask
  (Plan L4's bar).
