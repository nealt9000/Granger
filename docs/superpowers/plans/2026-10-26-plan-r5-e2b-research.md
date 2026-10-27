# Plan R5: Research built around E2B

Date: 2026-10-26 · Worktree `.claude/worktrees/r4`, branch `r4-answer-model` · Follows R4, whose speed screen left only E2B.

## Why

R4's speed screen (Task 3) settled the model for the Pixel 6: E2B is the only model that reaches first words in under
12 s. It reads the answer prompt at 451 tok/s, and the next best reads at 107. Every 3–4B model was killed for memory
while loading or took 15–146 s. MTP doesn't help on this GPU, and no small model can take the rewrite. So the round
turns around: instead of a stronger model, give E2B work it does well.

**What E2B does well:**
- reads fast, so short extra passes are cheap;
- answers well when the right passage is in front of it: 68% grounded in r1-dense-1;
- follows the `[n]` and `NOT IN LIBRARY` format reliably.

**Where it fails:**
- answers from off-topic passages: 9 unanswerables answered;
- arithmetic and comparisons, even with the facts given;
- multi-step questions.

**Where the time goes** (r1-dense-1, medians):
- 16.5 s from send to first words, made of:
  - the rewrite: 3.3 s;
  - search: 8.3 s for keyword search, with dense search (2.0 s) running alongside;
  - E2B's own first words: about 3 s.
- Dense search with the whole vector set on the phone (1.5 GB) was 13.8–14.9 s (R1 `r1-t10-check`), so it would become
  the longest step.

The answer prompt is about 5,500 characters (about 1,250 tokens): 4 passages of about 1,250 characters each, after
about 900 characters of instructions.

## Neal's decisions (2026-10-26)

1. Build around E2B; no other answer model on the Pixel 6 this round.
2. Arithmetic in code only where it's cheap and exact: year arithmetic, after measuring how often the passages hold
   both facts. No question parsing: facts are extracted from the passages and code computes from them.
3. Think deeper goes (R4 decision 4 stands; R4 Task 8 moves here as Task 8).

## Fixed points

- Carried from R4:
  - the tuning split (202 items) and in-session grading per `tools/eval/RUBRIC.md`;
  - greedy decoding;
  - the pass bars: 80% answerable grounded, 90% unanswerable declined (89% gate);
  - the held-out set stays closed.
- Every change is a debug flag first, measured against r1-dense-1, and kept only if it clears its own gate.
- Measure on sample-40 first (`tools/eval/sets/sample-40.json`; E2B: 14/28 grounded, 3/12 unanswerables declined),
  then on the whole split for what survives. Differences of 1–2 on sample-40 are noise.
- **The phone:**
  - back up both databases before any install;
  - candidate model files go in `eval/models` and come out afterwards, along with their weight caches in the app's cache
    dir (R4 Task 3 filled the phone).
- Record `ms` per stage in every full-pipeline run.

---

## Task 1: where the 16 s goes, and dense latency

- [x] One full-pipeline run on the tuning split with dense on and the whole vector set (the phone has it since R1
  Task 10). Record each stage: rewrite, keyword search, dense search, rank, first words.
- [x] Profile dense search on the phone: time to read the files from storage against the cosine work itself (cold and
  warm), per pack.
- [ ] Try the cheap fixes first, in order, and keep the first that brings dense under 3 s with recall@4 within 1 point:
  - keep the vector files open and warm between questions;
  - a 64-dim first pass over all chunks, then 256 dims on the top 2,000;
  - search only the packs the keywords hit, plus books.
- [x] Profile the 8.3 s keyword search the same way. It may be the bigger win.
- **Gate:** dense retrieval can be on by default when median send to first words with it is no slower than without it
  in r1-dense-1 (16.5 s).

### Done (2026-10-26)

The **gate is met:** send to first words is 12.7 s median with dense on and the whole vector set. Keyword search is the
step that changed, not dense.

**Runs:**
- `r5-t1-base`: the instrumented build, before the fix;
- `r5-t1-kw` / `r5-t1-kw2`: sample-40, before and after;
- `r5-t1-fast`: the whole split, after the fix.

| Median (p90), whole split | r1-dense-1 | r5-t1-base | r5-t1-fast |
|---|---|---|---|
| Rewrite | 3.1 s (4.7) | 3.9 s (5.1) | 4.0 s (5.1) |
| Keyword search | — | 8.8 s (11.6) | **1.8 s (2.3)** |
| Dense search, alongside | 2.2 s (3.5) | 3.0 s (4.5) | 2.4 s (3.6) |
| Search (both) | 8.6 s (11.5) | 8.8 s (11.7) | **2.7 s (4.8)** |
| Send to first words | 16.5 s (21.2) | 18.3 s (23.2) | **12.7 s (16.6)** |

**Dense search was never the problem.** The 14 s in R1's `r1-t10-check` doesn't reproduce. With all 1.6 GB of vectors
it takes 2.4 s, made of:
- 0.9 s for the scan over about 5.5 million vectors;
- 1.2 s for rebuilding passages from ZIM articles and EPUB chapters;
- 0.2 s for listing the files.

In `r5-t1-base` dense finished before keyword search on every question. The cheap dense fixes weren't tried.

**Keyword search was the slow step.** It cost about 2 s per keyword: 5.5 s with 2 keywords, 10.8 s with 5. On
sample-40 the split was:
- full-text search of 33 packs, 4 at a time: 4.6 s, of which Wikipedia Top took 2.4 s;
- catalogue cards: 1.7 s;
- book search: 1.4 s;
- reading the chosen articles: 0.5 s.

**Fixes** (commit e336100; this is the app's own path, not a debug flag):
- ZIM full-text hits are fetched without snippets. Research never shows them, and libzim builds each one from the
  article. The Search screen and Home keep theirs.
- Books, catalogue cards and media cards run alongside the packs' full-text search.
- The card patterns are compiled once per search instead of once per book and field.

**Result:**
- Keyword search went from 8.8 s to 1.8 s.
- 176 questions got the same keywords from the rewrite in both runs, and all 176 found exactly the same passages.
- Dense is now the longer of the two searches on 161 of 191 questions.

**A crash found along the way:** a SIGSEGV in `Xapian::Database::get_doccount` under `Search.getResults` (sample-40,
question 31). libzim's `Query`, `Search` and `SearchIterator` were left to their finalizers, which free them on the
finalizer thread outside the archive's search lock. They are now disposed under the lock. No crash since, over the whole
split (about 2,000 searches). This also affects the release app.

**What's left in the 12.7 s:**
- the rewrite, 4.0 s: Task 6;
- search, 2.7 s, now dense-bound: scan plus rebuild;
- E2B from the prompt to first words, about 5 s: Task 3's shorter prompt.

The phone's rewrite and answer were about 0.8 s slower in both of today's runs than in r1-dense-1.

## Task 2: how often do the passages hold the answer? (offline)

This is the ceiling for Tasks 3–5.

- [x] For each answerable item of r1-dense-1, mark whether the chosen passages contain the reference answer (the
  dataset's `answers`, matched loosely, by hand where unsure).
- [x] For the 20 multi-step items, mark each fact separately (both people's dates, both events' years).
- [x] Table: answer in passages and grounded / not grounded / declined; for multi-step, both facts present, one, or
  none.
- **Read-out:**
  - if the answer is usually there when E2B misses, the work is in Tasks 3–5;
  - if multi-step usually has only one fact, per-entity search (one query per named person or work) comes before Task
    4.

### Done (2026-10-26)

**Method:**
- `tools/eval/coverage.py r1-dense-1` matches each dataset quote, folded, against the chosen passages.
- A grounded single-fact reply counts as holding the answer, since the rubric requires every claim to be supported by a
  cited passage.
- The rest were marked by hand in `sets/coverage-hand.jsonl`: 19 single-fact items and all 20 multi-step items, fact by
  fact. Literal matching was far too strict: the answer is often in other words, or in the same article from another
  pack.

**Single fact (106):**

| Answer | Grounded | Not grounded | Declined | Other | Total |
|---|---|---|---|---|---|
| In a chosen passage | 82 | 9 | 0 | 0 | 91 |
| Right article or book, wrong part | 0 | 4 | 0 | 0 | 4 |
| Not there | 0 | 7 | 3 | 1 | 11 |
| Total | 82 | 20 | 3 | 1 | 106 |

**Multi-step (20)**, by the kind of combination:

| Facts in passages | Grounded | Not grounded | Declined | Total | Of which years |
|---|---|---|---|---|---|
| Both | 4 | 4 | 1 | 9 | 8 (3 grounded) |
| One | 0 | 4 | 5 | 9 | 6 |
| None | 0 | 1 | 1 | 2 | 1 |

The 20 split into 15 years, 3 quantities and 2 chains.

**Read-out:**
- **When the answer is there, E2B mostly gets it:** 82 of 91 single-fact items. Its 9 misreadings:
  - wrong number or ranking from the passage: Curie's "five Nobels", China first, "two other novels";
  - leading with an off-topic passage: vocab-gap-001, bible-005;
  - not giving the literal answer: who-wrote-006 and -009, medical-019;
  - a false "the passages don't say": science-006.
- **When the answer isn't there, E2B answers anyway:** 11 of 15 such items got a not-grounded reply, and only 3
  declined. This is the unanswerables' failure on answerable questions, so Tasks 3 and 5 aim at both.
- **The 80% bar can't be reached by the answer step alone.** Answerable grounded is 86/126 (68%) today. If E2B were
  right on every item whose passages hold the answer, it would be at most 91 + 9 = 100/126 (79%), just under the 101
  needed. So retrieval has to improve too:
  - the 4 "right book, wrong part" items are long chapters, from Gulliver, Tom Sawyer, Sun Tzu and Nineteen Eighty-Four;
  - the 11 misses are mostly word definitions (words-001, -002), novel openings (literature-001, -002) and Bible verse
    locations.
- **Multi-step splits into two halves:**
  - 8 of the 15 year questions have both facts, and E2B is grounded on 3. Year arithmetic in code (Task 4) can reach at
    most 5 more, which would give multi-step 9/20 against today's 4/20.
  - The other 7 year questions have one fact or none. They need per-entity search (one query per named person or work)
    before Task 4 can help them.
  - Both are worth doing. Per-entity search is added to Task 4 as its first step.

## Task 3: fewer, tighter passages

- [x] **Relevance gate:** drop a chosen passage whose dense cosine to the question is under a threshold (set on the
  tuning split so no grounded answer loses its passage). Count how many unanswerables then reach the model with no
  passages and decline.
- [x] **Sentence trimming:** keep each passage's best sentences by the embedder's cosine to the question, plus one
  either side, up to about 500 characters, so the prompt drops from about 1,250 tokens to about 600.
  - This needs the embedder on the trimmed sentences at question time. Time it: the cost the spec turned down was about
    4 s for 12 passages, and here it is 4 passages split into sentences.
- [x] Debug flags `--es passages gate|trim|both`; replay sample-40 through the full pipeline for each.
  - Done offline instead: `passages.py` rebuilds r1-dense-1's answer prompts and `answerers.py` replays them on E2B.
    Only the answer step changes, so a pipeline flag would have bought nothing but time.
- **Gate:** keep a variant if grounded doesn't drop and unanswerables declined rises, or if grounded rises by 3 or more
  of 28. Report first words too; a shorter prompt should save about 1.5 s.

### Done (2026-10-26)

Scores are EmbeddingGemma's on the Mac (`passages.py score r1-dense-1`). Passage cosines are bunched: the lowest cited
passage of a grounded reply is 0.7316, and answered unanswerables have a median top cosine of 0.805 against 0.835 for
grounded replies. The gate's threshold (0.7315) drops 56 passages on the split and empties 2 items (1 answerable).

Sample-40 on E2B (28 answerable, 12 unanswerable; 27 new replies graded):

| Variant | Grounded | Not grounded | Answerable declined | Unanswerables declined | Prompt tokens (median) |
|---|---|---|---|---|---|
| base (= r1-dense-1) | 14 | 11 | 3 | 3/12 | 1,262 |
| gate | 14 | 10 | 4 | 4/12 | 1,244 |
| trim (500 chars) | 14 | 10 | 4 | 5/12 | 724 |
| both | 12 | 12 | 4 | 5/12 | 691 |

- **Gate: dropped.** One more decline, within noise. The cosine doesn't separate a passage that answers from one that
  is merely on topic, so no threshold that keeps the grounded replies' passages removes much.
- **Trim: passes, narrowly.** Grounded holds at 14 but through swaps: who-wrote-009 and science-006 become grounded;
  history-005 (E2B read "Thomas Young" as "young scholars" once the sentence lost its context) and multi-step-001 (a
  citation slip) are lost. Two more unanswerables decline. All of it is within the 1–2 noise band. On the whole split
  the literal answer quote falls out of the trimmed text in 7 of 66 items.
- **Both: fails** (grounded −2).
- **Speed** (speed-10, phone cooled to under 37 °C before each run): send-to-first-words of the model step falls from
  2.61 s to 1.68 s median (prefill 1,257 → 680 tokens; 520 → 459 tok/s, as a shorter prompt carries the fixed cost over
  fewer tokens). So trim saves about 0.9 s, not 1.5 s. The first sample-40 runs were throttled (206–267 tok/s), so
  their times aren't used.
- **Not yet measured: the embedder on the phone.** Trimming needs every sentence of the 4 passages embedded at question
  time (about 30 short texts). From the spec's 4 s for 12 passages, a rough guess is about 1 s, which would cancel the
  saving. It has to be timed before trim goes into the app; if it costs more than about 0.5 s, trim buys only the
  declines, which are within noise.

## Task 4: year arithmetic in code

Only if Task 2 shows that multi-step questions usually have both facts in the passages, or once per-entity search makes
them so.

- [x] **Per-entity search first** (Task 2: 7 of the 15 year questions have only one fact in the passages): for a
  question with cue words, one extra search per named person or work (from the rewrite's keywords), and each entity's
  best passage kept among the chosen ones. Measure on the 20 multi-step items how many then have both facts.
- [x] **Facts from the passages:**
  - regex for the regular cases: Wikipedia lead lifespans (`Name (30 August 1797 – 1 February 1851)`), "born / died /
    published / founded / built in <year>";
  - E2B for the rest, as a short extraction pass: `name | event | year` lines from the passages, about 30 tokens.
- [x] **When to add them:** cue words ("how old", "years after / before", "how long", "older", "younger", "alive when",
  "before or after", "by how much", "lived longer"), kept loose: an unneeded facts block costs a few tokens.
- [x] **Code computes** every difference, order and "alive at that date" between the extracted facts and adds them as a
  numbered facts block, citing the passage each year came from: "Frankenstein (1818) [2] came out after Jane Austen died
  (1817) [1]: 1 year after." E2B cites the block's sources.
- [x] Unit tests for the extractor and the arithmetic on real passage text.
- **Gate:** multi-step grounded on the tuning split (r1-dense-1: 4/20), with no regressions on the other items.
- Out: quantities with units (ocean depth against Everest, light to the Moon) and chains; E2B keeps those.

### Done (2026-10-27)

**Result** (the 23 questions with cue words, dense on, `--entities on --facts on`; runs `r5-t4-facts`, `facts2` for the
last fixes):

| | r1-dense-1 | entities only (`ent3`) | entities + facts |
|---|---|---|---|
| Multi-step grounded (18 cued) | 3 | 2 | **9** |
| Multi-step declined | 7 | 3 | 2 |
| The 7 other cued questions | 6 grounded, 1 declined | same | same |

- The other 179 questions have no cue words, so neither flag touches them. Multi-step on the whole tuning split goes
  from 4/20 to **10/20** (016, not cued, grounded as before). Gate met.
- Still wrong: 004 ("Lincoln was older", with "born on the same day" spelled out in the block), 003 (no year for the
  King James Bible), 010 (Noah's 950 is only in Genesis), 015 (light to the Moon, out of scope), 018 (the periodic
  table's 1869 isn't in the passages). 002 and 012 decline.
- First words: the block is about 100–150 tokens, under 0.5 s of prefill.

**`YearFacts.kt`** (in `core/research`; tests on the real passages in `src/test/resources/year-questions.tsv`):
- Names are the question's runs of capitals (the rewrite's keywords for a lower-case question, only those in the
  question). Each content word belongs to the name it follows ("Columbus reached"; "publishing X" to the name after).
- A person's dates: the lifespan in their article's lead, brackets and "[O.S. …]" handled.
- An event: the sentence sharing the name's words (word groups: published/came out, reached/landfall/voyages, fell,
  proof, won, president/serving, sealed, periodic table…), in the name's own article or, in another, the first year
  after the name. An end word takes a range's last year ("from 1961 to 1989"). A work with no such sentence takes its
  lead's first year. Years BC and the person's own birth and death years are skipped.
- If the question asks for a name's event and the passages don't date it, there is no block: the first run said "In
  1911, Mendeleev had died" for 018 and compared deaths for 011, and E2B followed both.
- Code then writes the gaps: event to event ("Mayflower (1620) came about 128 years after Christopher Columbus
  (1492) [1][2]"), a person at an event (alive and aged, not yet born, or dead N years), and two lifespans (born,
  died, ages at death). Exact when both dates have a day, "about" otherwise.

### Progress (2026-10-27)

**Per-entity passages** (`Entities.kt`, debug flag `--entities on`; runs `r5-t4-ent` → `ent3`, 23 cued questions).
No extra search was needed: in 5 of the 7 year questions missing a fact, the named article was already among the
keyword passages and ranking dropped it. So a question with cue words gets up to 2 of its 4 places reserved: for each
name (runs of capitals in the question, then the rewrite's keywords), the article whose title names it (exact, or a
proper name ending in it: "Dmitri Mendeleev"), and that article's *first* opening passage, where a Wikipedia lead's
dates are. Two fixes on the way: the first pick took whichever lead passage ranked best (Magna Carta's 1216 one, not
1215), and an e-book of *Frankenstein* counted as the article.

- Year questions with both facts in the passages: **8 → 11 of 15** (008, 014, 019, 020 gained; 003 still lacks
  Shakespeare's death, 010 Noah's 950 is only in Genesis, 012 is declined by the gates before any pick, 018's 1869 is
  in another article).
- No change on the 7 cued questions that aren't multi-step (all 7 grounded or declined as before).
- Alone it doesn't raise grounded (multi-step on the cued 18: 3 → 2). E2B now answers where it declined (008, 014,
  019), but does the arithmetic wrong: 1492 → 1620 is "20 years", 1215 → 1346 "about 100".

**The facts block's ceiling** (hand-written, as a perfect extractor would; `oracle.py` in the session scratchpad, runs
`r5-t4-oracle-e2b` and `r5-t4-oracle2-e2b`): one line before the question, "Worked out from the passages: X (year) [n];
Y (year) [m]: N years later."
- On r1-dense-1's passages (years-8): grounded 3 → 6 of 8.
- On the entity passages (years-11): grounded 2 → **9 of 11**. Only 002 and 020 (both "could X have…") still decline.
- First words unchanged (the block is about 40 tokens).
- With the other multi-step items as in r1-dense-1, multi-step would go from 4/20 to about 10/20.

**What a real extractor has to find**, from these 11: lifespans (the lead's "(30 August 1797 – 1 February 1851)") for 7,
and an event year for 10: Frankenstein 1818, *Origin* 1859, Lincoln president 1861, Columbus 1492, Mayflower 1620,
Magna Carta 1215, Black Death 1346, Fermat proof 1994, Berlin Wall 1989, periodic table 1869. The lead's first year is
right for 5 of the 10; the others need the sentence that shares the question's words ("reached", "fell", "proof").

## Task 5: a check pass

E2B's fast reading makes a second short call affordable.

- [x] One call after ranking: the question and the numbered (trimmed) passages, "Which passages answer the question?
  Reply with their numbers, or none." About 2 s at 600 tokens. "none" declines without the answer call; otherwise only
  those passages go on.
- [x] Compare with Task 3's gate alone on sample-40: unanswerables declined, grounded, first words.
- **Gate:** at least 3 more unanswerables declined of 12, with grounded no lower. Otherwise drop it: it costs a call on
  every question.

### Done (2026-10-27): dropped

Measured offline on sample-40 like Task 3 (`passages.py check` and `filter`, replayed with `answerers.py`; runs
`r5-t5-check-e2b`, `r5-t5-filter-e2b`). The check prompt is the answer prompt's passages and question (with the earlier
question for a follow-up), "Which passages contain the answer to the question, or facts needed to work it out? Reply
with only their numbers, comma-separated, or the word none."

- **It mostly names the passages the answer would cite.** It said "none" on 5 of 40, and 3 of those were already
  declined by the answer step. Of the 9 unanswerables E2B answered, it named passages for 8.
- **Unanswerables declined: 3 → 4 of 12** (medical-029). The gate needs +3.
- Three more answerable questions declined (vocab-gap-009, multi-step-007, multi-step-017; none was grounded before).
  The rest weren't graded: the decline count already fails the gate.
- **Cost: about 2.9 s on every question** (2.8 s to its first token: it reads the same ~1,250 tokens again).

So E2B can't tell an on-topic passage from one that answers any better when asked separately than when answering.

## Task 6: the rewrite without the model

R4 Task 5 offline found:
- the embedder's medical flag (centroid over labels) with the lexicon catches 40/40 against E2B's 39/40, but with almost
  no margin;
- plain keywords keep 94% of E2B's question words but lose its added names and terms.

No small rewrite model works (R4 Task 3).

- [x] Score the medical flag with the phone's own query vectors (the int4 embedder) and check the 6 margin questions
  stay above 0. If they do, put it in the pipeline in place of the model's `medical:` line.
- [x] Full-pipeline run with `--es rewrite plain` (first questions plain, follow-ups still on E2B): recall@4 and time
  against r1-dense-1. Then a variant where follow-ups are plain too (keywords from the question and the prior question);
  dense already embeds both.
- **Gate:** recall@4 within 2 points of r1-dense-1 for about 3 s saved. If plain loses more, keep E2B's rewrite.

### Done (2026-10-27): E2B's rewrite stays

**Medical flag on the phone's vectors** (run `r5-t6-embed`: the 212 labels and 202 questions embedded on the Pixel by
the debug `embed` mode). Lexicon, or strong, or centroid above 0 catches 39 of 40 with no false flags; E2B's `medical:`
line catches 39 with 4 false flags. But medical-033 ("what did apoplexy mean in old books") scores −0.012, so one of the 6
margin questions falls below 0. The other medical questions with no lexicon word score +0.004 to +0.006, and the top
non-medical question (knots-002) scores −0.001. That margin is too thin to put in the pipeline. Without it, the plain run's
lexicon-only flag catches 34 of 40 (85%).

**Plain keywords, full split** (`r5-t6-plain`; follow-ups still on E2B). Against `r5-t1-fast`, which runs the same code
with E2B's rewrite:

| | recall@4 (126) | rewrite median | first words median / p90 |
|---|---|---|---|
| r1-dense-1 | 65 (51.6%) | 3.1 s | 16.5 / 22.2 s |
| r5-t1-fast (E2B) | 61 (48.4%) | 4.0 s | 12.7 / 16.9 s |
| r5-t6-plain | 58 (46.0%) | 0 | **8.2 / 13.8 s** |

- **It saves about 4.5 s to first words**, under the 9 s target for the first time.
- **But recall is 2.4 points below the same code, and 5.6 below r1-dense-1.** It lost 7 and gained 4. The losses are
  mostly names split into words (berlin wall, king james bible, treasure island, half life) and the terms E2B adds
  ("rayleigh scattering", "iron oxide"). Question filler words stay in too (mean, actually, already, still, called).
- **Follow-ups plain too** (`r5-t6-plainall`, the 11 follow-ups): 7 of 10 against 8, and about 2 s more saved. It lost
  follow-up-006: "who first climbed it" needs E2B to resolve the prior "tallest mountain in the world" to Everest.

As written, the gate fails, so E2B's rewrite stays. A second try would need plain keywords to keep names whole (capital
runs, or adjacent words that make a title) and drop the filler words, plus a medical flag with a real margin.

## Task 7: the whole split, together

- [x] The kept variants together on the tuning split: grounded, declined, multi-step, medical, send to first words
  median and p90. Against the pass bars and r1-dense-1.
- [ ] Neal's feel test on the phone with the release build path (no debug flags): 15 questions of his own.

### Run (2026-10-27)

What was kept: Task 4's entity passages and year facts, with dense on and E2B's rewrite. The gate is out (Task 3), and so
are the check pass (Task 5) and plain keywords (Task 6). **Trim is out too.** Its embed cost, timed on the Pixel (debug
`embed` mode, 336 sentences from 10 real answer prompts, about 34 a question), is 1.9 s per batch of 16 when cool and
5.6 s when warm. That's about 4 s a question to save 0.9 s.

Run `r5-t7-all` (full tuning split, `--dense on --entities on --facts on`; 48 new replies graded):

| | r1-dense-1 | r5-t7-all | bar |
|---|---|---|---|
| Answerable grounded | 86/126 (68%) | **91/126 (72%)** | 80% |
| Multi-step grounded | 4/20 | **11/20** | |
| Unanswerables declined | 67/76 (88%) | 63/76 (83%) | 90% |
| Medical flag recall | 98% | 95% | aim 95% |
| Recall@4 | 52% | 48% | |
| First words, median / p90 | 16.5 / 22.2 s | 12.1 / 23.7 s | 9 s |
| First words without the Think deeper reloads | 16.2 / 19.1 s | **11.9 / 13.9 s** | 9 s |

- **Gains (11):** 7 multi-step, plus bible-005, vocab-gap-001, medical-018 and medical-019.
- **Losses (6):** history-001, geography-general-001, geography-003, medical-002, knots-001 and knots-002. Two of them
  are strict calls: "torn down" for "fell", and one wrong passage number for the Tibetan name. They were graded by
  different graders from r1-dense-1's, so ±2 is noise.
- **Declines down 4:** r5-t1-fast (same search, no entities or facts) also declines 63. This comes from Task 1's search
  changes, not from Task 4. The unanswerables now answered are 001, 035, 057, 058 and 059.
- **The p90 is Think deeper's.** The rewrite that follows a Think deeper question waits for E2B to load again after E4B:
  14.1 s median against 3.8 s. Leave those 18 out and p90 is 13.9 s. Task 8 removes the cost along with the button.

The bars still aren't met: grounded 72% (80%), declines 83% (90%), first words 11.9 s (9 s).

For the feel test, the app now runs what was measured: `DENSE_RETRIEVAL = true`, with entity passages and year facts on
in `GrangerApp`.

### Feel test, first pass (2026-10-27)

Neal asked 21 questions in one chat: "mostly poor", with many easy ones declined. Response time "totally usable".

- **10 declines were a bug.** The app sends every question after a chat's first with the one before it, and dense
  embedded the two together. A question that stood alone was searched under the prior topic, and ranking kept nothing.
  Replayed on their own (`r5-t7-feel`), 9 of the 10 answered. Replayed with their prior question (`r5-t7-chain`),
  all 10 declined. The eval's follow-ups all lean on their prior question, so it never showed.
- **Fix:** `FollowUp.refersBack` (a pronoun that points back, or "and…" / "what about…") decides whether dense adds the
  prior question. With the fix (`r5-t7-chain2`), 8 of the 10 answer; the eval's 11 follow-ups are unchanged
  (`r5-t7-fu`). E2B's rewrite still sees the prior question.
- **Replies once fixed:** about 11 of 21 good (Oswald, Fahrenheit 451, 165°F, Charlotte's Web, The Lion King, ethanol
  conversion), about 7 poor (Venera 8 for the first landing, the 1994 album, the national anthem without its lyrics,
  Gandalf, a red-headed orphan as Red Riding Hood).
- **Neal's idea:** when nothing is found, offer "nothing in your library, but my best guess is…". The Best guess button
  already does this on a tap; E2B's guess for the snakes question was sensible.

## Task 8: Think deeper removed (from R4 Task 8)

- [x] Remove the button, the second pass and E4B as a model role. The catalog keeps E4B's entry until Neal says otherwise.

### Done (2026-10-27)

- **Gone:** the button and `thinkDeeper()`; the pipeline's `deeperFrom` path; `medicalHint` (only Think deeper set it); the
  large-model fallbacks and their banners; the guess with the large model. Best guess stays, on E2B.
- **Models:** one LLM. `LlmSize` is gone and `InstalledModels` is `(llm, embedder)`, so the embedder always sits beside
  E2B. `installedModelsOf` ignores LARGE-role entries.
- **Tiers:** LIBRARY_ONLY and ASK. The onboarding and readiness strings follow.
- **Sources:** E4B's row says "Not used" and can't be downloaded on any phone; an installed copy can still be deleted.
  The catalog entry stays.
- **Storage:** the API drops `askedDeeper`, `usedLarge`, `guessLarge` and `guessFellBack`. The columns stay, written
  false, so there's no migration.
- **Eval:** no deeper phase; `--guesses` is none|small. The report still reads old runs' deeper records.

## Not in this plan

- A different answer model for newer phones (R4 decision 1: later).
- Fine-tuning E2B.
- Quantities with units, chains, and general question parsing.
