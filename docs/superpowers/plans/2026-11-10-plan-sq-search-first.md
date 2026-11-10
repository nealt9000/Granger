# Plan SQ: Search first (Ask off) — 2026-11-10

**Why:** Ask stays off until edge models improve; check back in a few months (Neal, 2026-11-10). Search becomes the way
to ask the library, so its quality and experience must improve a lot. Ask's retrieval work (vectors, embedder, hybrid
ranker, keyword extraction, passage deep links, dictionary entry) moves into Search. **No generated text in Search.**

## Decisions

Neal's calls, 2026-11-10:

1. **Results page: Best matches on top.** One ranked block leads the page. For a name, it shows the right article or
   book. For a question, it shows the best passages with the matching sentence highlighted, opening at that spot. Today's
   groups (Titles, In books, Articles, Music, Audiobooks) follow below.
2. **Meaning search: yes, made fast.** Dense retrieval in Search, behind a speed spike with a go/no-go: under 1 s on the
   Pixel 6 (today brute force takes ~10–14 s on the full set). It runs on a typing pause or Enter, and joins the results
   as they come in.
3. **Ask's parts: hidden, E2B dropped.** Ask's rail tool and entry points are hidden behind a flag. E2B (~2.5 GB) leaves
   downloads, readiness tiers and onboarding. The code, the eval tools and saved chats stay, so Ask can come back. The
   site, store listing and tester notes are updated.
4. **The bar: two eval sets plus a feel test.** A known-item set and a question set (the Ask dev and held-out sets), with
   numbers before and after, then Neal's Pixel walkthrough.

Defaults I took (Neal can override):

5. **Two speeds.** Titles stay instant as you type, as today. Best matches (keywords + meaning) run after a ~600 ms pause
   or on Enter, with a quiet progress line. Groups fill in as they do now.
6. **Query kind decides what leads.** A short query that matches a title exactly or by prefix leads with that item. A
   question or a multi-word phrase leads with passages. A single word with a dictionary entry gets a small Define card
   (`PassageFinder.entry` already exists).
7. **Vectors and the embedder go to every phone that can embed a query at speed**, not only ASK-tier phones
   (`GrangerApp.kt:608` gates them today). If E1-class phones can't, they keep keyword-only Best matches. The readiness and
   onboarding wording changes from "Ask's search indexes" to "Search by meaning".
8. **The medical rule carries over.** Medical queries show the banner. Search only quotes anyway.
9. **Spanish parity** for everything here (Keywords, stopwords, the Spanish medical layer and vectors already exist).
10. **The feature freeze:** Ask's exemption moves to Search for this plan.
11. **Owl lines stay with Ask.** At most one plain line when nothing is found ("Nothing in your library for that. Try
    fewer words, or get more in Library.").

## Where we start (measured)

- Titles: each pack's own suggestions, taken in turns. Exact match first, then prefix match. There is no source priority.
- Article text: per-pack libkiwix full text, merged by raw per-pack scores, which can't be compared across packs.
- Ask's ranker over the same library: the answer's passage is in the top 4 for **51/148** dev questions, top 12 for 64,
  top 24 for 86 (`step-back-2026-11-05.md` §9; a literal-quote proxy, so a lower bound).
- **23/148 answers are not indexed at all**: the 12-chunk cap per article, and infobox text left out (§9.3).
- Dense brute force (`VectorSearch.top`): ~10–14 s per query on the Pixel 6 over the ~1.5 GB set. Keyword passage
  search takes ~1.8 s (R5 Task 1).

## Phases and tasks

Branch `sq-search-first`, worktree `.claude/worktrees/sq`. Each phase merges on its own.

### Phase 0: Ask off (ships first, small)
- **Task 0.1, the flag.** One `askEnabled` (false in release and by default in debug, with a debug-only toggle). It
  hides Ask's rail tool (`GrangerShell.kt:124` gate), the reader's selection-menu Ask, the Home and Search "Ask"
  entry points, and Ask lines in onboarding (`OnboardingModel.ASK`) and readiness. Saved chats stay in the DB, unseen.
- **Task 0.2, models.** E2B leaves the catalog's default sets, the tiers and Get more. On a phone that already has
  E2B, Storage shows a one-time "Ask is paused: free 2.5 GB?" with Remove / Keep. Nothing is deleted without a tap. The
  embedder stays (Search uses it). Re-sign the catalog.
- **Task 0.3, words outside the app.** Site (opengranger.org: hero, Why, librarian lines, /test, /prueba), the Play
  listing (en + es-419), tester notes, privacy page if it names Ask. A short "Ask is resting" note for testers.
  Neal approves the copy before anything is published.
- Done: release build with no Ask anywhere; tests green; the Pixel 6 walk shows none.

### Phase 1: The baseline (measure before changing)
- **Task 1.1, known-item set.** About 150 English and 75 Spanish queries, each with its target item: article titles
  (exact, partial, unaccented, misspelt by one or two letters, lower case), book titles and authors, songs/albums,
  pack-specific pages (WikiMed, iFixit, Wikivoyage). Written by a separate subagent from the installed library, then
  checked by Neal on a sample.
- **Task 1.2, harness.** A debug `SearchBenchmarkActivity` (the `ResearchBenchmarkActivity` pattern) that runs a set
  through the real Search on the eval emulator or the Pixel 6 and writes JSONL. `tools/eval/search_score.py` scores:
  known-item @1, @3 and MRR; questions: answer passage in Best matches top 5 and top 10 (literal-quote proxy from
  `metrics.matches`, plus graders on a sample); latency p50/p95 for titles and for Best matches.
- **Task 1.3, run the baseline** on today's Search (questions scored on the Articles group, standing in for Best matches)
  and on Ask's ranker as a Best-matches stand-in. **Checkpoint with Neal: set the numeric bars from the baseline.**
  Proposed starting bars: known-item @1 ≥ 85%, @3 ≥ 95%; questions top 5 ≥ 55%, top 10 ≥ 70%; titles < 300 ms per
  keystroke; Best matches < 2.5 s after Enter on the Pixel 6.

### Phase 2: Fast meaning search (spike, go/no-go)
- **Task 2.1, try the cheap route first: shorter vectors.** Score every row on its first 128–256 dims (EmbeddingGemma is
  Matryoshka-trained), then rescore the top ~500 at full dims. No re-release needed. Measure recall@50 against brute force
  on the dev queries, and time on the Pixel 6.
- **Task 2.2, if 2.1 misses: clustered index (IVF).** `tools/vectors/build.py` clusters rows (k-means, ~√N lists) and
  writes centroids plus rows grouped by cluster (a new `.gvec` version, read alongside v1). The phone probes the nearest
  n lists. Costs a re-release of every vector file.
- **Task 2.3, embedder warm-up.** Load EmbeddingGemma when Search opens (not at app start); measure the cold and warm
  query embed on the Pixel 6 and E1.
- **Go:** < 1 s dense on the Pixel 6, recall@50 ≥ 95% of brute force. **No-go:** Best matches ships keyword-only and
  dense stays a later item.

### Phase 3: Close the indexing gaps
- **Task 3.1.** Measure what lifting the 12-chunk cap and adding infobox text costs (vector size per pack, build time) and
  gains (of the 23 unindexed answers, how many come back). The chunker is shared between phone and Mac
  (`:core:text`), so both change together.
- **Task 3.2, if worth it.** Rebuild and release the vectors (together with Task 2.2 if that runs, one re-release).
  **Checkpoint with Neal** if total vector size grows by more than ~25%.

### Phase 4: The Best-matches ranker
- **Task 4.1, query kind.** A name vs a question vs a single word (`Keywords`, `WordQuestion`, `ResearchLanguage`).
  Pure Kotlin, unit-tested from the eval sets.
- **Task 4.2, one candidate pool.** Title hits, libkiwix full text, book FTS hits, Ask's keyword `PassageFinder`, dense
  hits. Deduped to one entry per article or chapter, keeping its best passage.
- **Task 4.3, one score.** Reuse `Ranking`'s hybrid with `SourceWeight`/`FictionWeight`. Add title-match strength
  (exact/prefix/fuzzy), a source prior from the catalog's set and rank (`PackSlices`, the curated tiers), and the slice
  dedupe already in Search. Replace the cross-pack raw-score sort. Tune only on the dev splits; held-out is scored once
  at the end.
- **Task 4.4, the highlighted sentence.** Within the chosen passage, pick the sentence by keyword overlap plus cosine
  (sentence split as `CitedQuote.sentences`). It opens at that spot through `CitationOpener`/`CitationTarget`.
- **Task 4.5, typed "Did you mean".** `SpokenTitleMatcher` for typed queries with few or no results, plus a word-level
  fix from the packs' title vocabulary. Only offered, never applied silently.
- Each task is scored on the dev sets before the next one starts.

### Phase 5: The results page
- **Task 5.1, layout.** The Best matches block (3 rows, Show more to 10). Each row: source, title, the highlighted
  sentence in context, a kind icon. Groups below it stay as they are.
- **Task 5.2, progress and states.** Titles as you type. "Looking through your library…" until Best matches lands. An
  empty state with the one plain line and a Get more link. The `titleOnly` note stays.
- **Task 5.3, filters.** Chips: All · Articles · Books · Media, plus "In: <pack>" when Search is opened from a pack.
- **Task 5.4, Define card** for single words with a dictionary entry.
- **Task 5.5, the medical banner, TalkBack, landscape, Spanish strings, en-XA pseudo-locale.** ThemeShots for the new
  screens.

### Phase 6: Vectors beyond ASK-tier phones
- **Task 6.1.** Drop the `includeVectors = deviceTier >= DeviceTier.ASK` gate in favour of an embed-speed check.
  Measure on E1 (economy). Update readiness and onboarding sizes and labels, and the default set sizes (~20 GB middle
  tier, Neal).

### Phase 7: Verdict and release
- **Task 7.1.** Score the held-out questions and the full known-item sets once. A table before and after per domain and
  language goes in `docs/search/sq-results-2026-11.md`.
- **Task 7.2.** Neal's Pixel 6 walkthrough from a fresh install and from his library; Pixel 7 check.
- **Task 7.3.** Merge, build the bundle, internal track, then closed track with release notes ("Ask is resting; Search
  now finds passages").

## Order and size

Phase 0 (≈½ day) → Phase 1 (≈1 day) → Phase 2 spike (≈1 day) → Phase 4 (≈2 days, can start with keyword-only
while Phase 2 runs) → Phase 3 (≈1 day + rebuild time) → Phase 5 (≈1–2 days) → Phase 6 (≈½ day) → Phase 7.

## Checkpoints with Neal

1. Ask-off copy for the site, listing and testers (Phase 0.3).
2. Numeric bars after the baseline (Phase 1.3).
3. Phase 2 go/no-go result.
4. Vector size growth, if more than ~25% (Phase 3.2).
5. A look at the Best matches layout in ThemeShots before the walkthrough (Phase 5).

## Out of scope

Generated answers or summaries of any kind; a question index (failed, §9); Ask R&D. That restarts with the check-back in a
few months, with E4B speed on newer phones as the first measure.
