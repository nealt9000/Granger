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

- [ ] For each answerable item of r1-dense-1, mark whether the chosen passages contain the reference answer (the
  dataset's `answers`, matched loosely, by hand where unsure).
- [ ] For the 20 multi-step items, mark each fact separately (both people's dates, both events' years).
- [ ] Table: answer in passages and grounded / not grounded / declined; for multi-step, both facts present, one, or
  none.
- **Read-out:**
  - if the answer is usually there when E2B misses, the work is in Tasks 3–5;
  - if multi-step usually has only one fact, per-entity search (one query per named person or work) comes before Task
    4.

## Task 3: fewer, tighter passages

- [ ] **Relevance gate:** drop a chosen passage whose dense cosine to the question is under a threshold (set on the
  tuning split so no grounded answer loses its passage). Count how many unanswerables then reach the model with no
  passages and decline.
- [ ] **Sentence trimming:** keep each passage's best sentences by the embedder's cosine to the question, plus one
  either side, up to about 500 characters, so the prompt drops from about 1,250 tokens to about 600.
  - This needs the embedder on the trimmed sentences at question time. Time it: the cost the spec turned down was about
    4 s for 12 passages, and here it is 4 passages split into sentences.
- [ ] Debug flags `--es passages gate|trim|both`; replay sample-40 through the full pipeline for each.
- **Gate:** keep a variant if grounded doesn't drop and unanswerables declined rises, or if grounded rises by 3 or more
  of 28. Report first words too; a shorter prompt should save about 1.5 s.

## Task 4: year arithmetic in code

Only if Task 2 shows that multi-step questions usually have both facts in the passages, or once per-entity search makes
them so.

- [ ] **Facts from the passages:**
  - regex for the regular cases: Wikipedia lead lifespans (`Name (30 August 1797 – 1 February 1851)`), "born / died /
    published / founded / built in <year>";
  - E2B for the rest, as a short extraction pass: `name | event | year` lines from the passages, about 30 tokens.
- [ ] **When to add them:** cue words ("how old", "years after / before", "how long", "older", "younger", "alive when",
  "before or after", "by how much", "lived longer"), kept loose: an unneeded facts block costs a few tokens.
- [ ] **Code computes** every difference, order and "alive at that date" between the extracted facts and adds them as a
  numbered facts block, citing the passage each year came from: "Frankenstein (1818) [2] came out after Jane Austen died
  (1817) [1]: 1 year after." E2B cites the block's sources.
- [ ] Unit tests for the extractor and the arithmetic on real passage text.
- **Gate:** multi-step grounded on the tuning split (r1-dense-1: 4/20), with no regressions on the other items.
- Out: quantities with units (ocean depth against Everest, light to the Moon) and chains; E2B keeps those.

## Task 5: a check pass

E2B's fast reading makes a second short call affordable.

- [ ] One call after ranking: the question and the numbered (trimmed) passages, "Which passages answer the question?
  Reply with their numbers, or none." About 2 s at 600 tokens. "none" declines without the answer call; otherwise only
  those passages go on.
- [ ] Compare with Task 3's gate alone on sample-40: unanswerables declined, grounded, first words.
- **Gate:** at least 3 more unanswerables declined of 12, with grounded no lower. Otherwise drop it: it costs a call on
  every question.

## Task 6: the rewrite without the model

R4 Task 5 offline found:
- the embedder's medical flag (centroid over labels) with the lexicon catches 40/40 against E2B's 39/40, but with almost
  no margin;
- plain keywords keep 94% of E2B's question words but lose its added names and terms.

No small rewrite model works (R4 Task 3).

- [ ] Score the medical flag with the phone's own query vectors (the int4 embedder) and check the 6 margin questions
  stay above 0. If they do, put it in the pipeline in place of the model's `medical:` line.
- [ ] Full-pipeline run with `--es rewrite plain` (first questions plain, follow-ups still on E2B): recall@4 and time
  against r1-dense-1. Then a variant where follow-ups are plain too (keywords from the question and the prior question);
  dense already embeds both.
- **Gate:** recall@4 within 2 points of r1-dense-1 for about 3 s saved. If plain loses more, keep E2B's rewrite.

## Task 7: the whole split, together

- [ ] The kept variants together on the tuning split: grounded, declined, multi-step, medical, send to first words
  median and p90. Against the pass bars and r1-dense-1.
- [ ] Neal's feel test on the phone with the release build path (no debug flags): 15 questions of his own.

## Task 8: Think deeper removed (from R4 Task 8)

- [ ] Remove the button, the second pass and E4B as a model role. The catalog keeps E4B's entry until Neal says otherwise.

## Not in this plan

- A different answer model for newer phones (R4 decision 1: later).
- Fine-tuning E2B.
- Quantities with units, chains, and general question parsing.
