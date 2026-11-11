# Plan SQ: Search results (before and after)

Device: eval emulator `emulator-5580` (granger-eval-1, CPU), the 33-pack eval library (`~/.cache/granger/emu-stage`).
Latency here is the emulator's, not the Pixel 6's; phone timings come at the walkthrough. Tools: `tools/eval/known_items.py`,
`tools/eval/search_score.py`; runs under `tools/eval/runs/`.

## Baseline, 2026-11-10 (main 180a8876 + Phase 0, Search unchanged)

### Known items (`sets/known-items.json`, seed 11, 201 queries) — run `sq-base-ki`

| Slice | n | Titles @1 | Titles @3 | MRR | In Titles | Articles @3 |
|---|---|---|---|---|---|---|
| all | 201 | 127 (63%) | 153 (76%) | 0.70 | 160 (80%) | 48 (24%) |
| en | 142 | 98 (69%) | 111 (78%) | 0.74 | 114 (80%) | 32 (23%) |
| es | 59 | 29 (49%) | 42 (71%) | 0.60 | 46 (78%) | 16 (27%) |
| en exact / lower / alias | 105 | 93 (89%) | 104 (99%) | — | — | — |
| en typo | 25 | 0 | 0 | 0.00 | 0 | 0 |
| en prefix | 12 | 5 (42%) | 7 (58%) | 0.52 | 9 (75%) | 0 |
| es exact | 18 | 8 (44%) | 16 (89%) | 0.65 | 18 (100%) | 5 |
| es typo | 11 | 0 | 0 | 0.00 | 0 | 0 |
| es prefix | 6 | 2 | 2 | 0.40 | 4 | 0 |

Latency p50 / p95 (ms): titles 45 / 432, books 8 / 1,231, full text 1,148 / 4,958.

### Questions (Ask's `dataset/dev.json`, 148 answerable) — run `sq-base-dev`

Article level: is an answer's article or book in a group's first rows.

| Slice | n | Titles @5 | Books @5 | Articles @5 | Articles @10 | Page @5 |
|---|---|---|---|---|---|---|
| all | 148 | 1 (1%) | 1 (1%) | 18 (12%) | 24 (16%) | 19 (13%) |
| classics | 44 | 0 | 1 | 3 | 3 | 4 |
| reference | 12 | 0 | 0 | 6 | 6 | 6 |
| maps | 12 | 0 | 0 | 2 | 6 | 2 |
| medicine | 20 | 0 | 0 | 1 | 2 | 1 |

Latency p50 / p95 (ms): titles 38 / 79, books 82 / 243, full text 896 / 3,981.
For comparison, Ask's ranker (keywords + dense) put the answer *passage* in its top 4 for 51/148 (step-back §9).

### What fails, and why

1. **Questions find nothing.** Book search and full text match every word, so a question's small words sink it:
   "who chopped off holofernes head" → 0 book hits and 0 article hits; "holofernes" → 22 and 30. Ask avoided this by
   extracting keywords (`Keywords`); Search never did. The biggest and cheapest fix.
2. **No typo tolerance at all:** 0 of 36 typo queries reach Titles, in either language.
3. **No ranking between packs for the same title.** "Maputo" is six rows (Wikipedia en, es, Wiktionary es, Wikivoyage es,
   en, Wiktionary en) in library order; the Spanish Wikivoyage article the query meant is 4th. Spanish queries meet
   English packs first: the library's language order isn't used.
4. **Duplicates and noise.** Three slices of English Wikipedia each list "Turingia", titled with the redirect's text
   rather than the article's ("Thuringia"); WikEM's translated pages (`/de`, `/es`, `/fr`…) crowd a medical title.
5. **Prefixes** (typing in progress) reach the top 3 for 58% (en) and 33% (es): partial words aren't completed by rank.
6. **Full text is slow:** p95 ~4–5 s on the emulator, and it returns 30 rows whose order means little across packs.

### Set notes

The known-item targets are a uniform sample, so many are obscure (a fair test of the mechanism, not of popularity).
Dictionaries are left out of the set. Same-title items across sets (Maputo in Wikipedia and Wikivoyage) are counted
strictly by the target's set; Phase 4's grouping of one title across packs changes how those should be scored.

## Phase 4 steps

### 4.1 Keywords when the typed query finds too little (runs `sq-kw-dev`, `sq-kw-ki`)

Questions page @5: 13% → **17%** (articles @5 22, @10 29, books @5 3). Known items unchanged (63% / 76%). All keywords must
still match, so most questions stay short of their article.

### Offline: Ask's retrieval as Best matches (`tools/eval/ask_articles.py`, recorded `dev-base-1`)

Ask's own finder and ranker on the same 148 questions, deep pool, one row per article:

| Ranker | Article @5 | Article @10 |
|---|---|---|
| Keyword only (Ask's per-word search + lexical rank) | 37 (25%) | 42 (28%) |
| Hybrid, keyword + dense (α 0.1, floor 0.74) | **102 (68%)** | **109 (73%)** |
| The app's gated hybrid | 102 (68%) | 108 (72%) |

The question bars (50% / 65%) are reachable with what exists, **if dense retrieval runs**; keyword-only Best matches
would stop near 25%. Phase 2 (dense under 1 s on the Pixel 6) is the critical path. Caveat: recorded hits from the
full vector set on the emulator; the dev set has been used heavily, so held-out decides.

### 4.3 Titles: folded matching, redirects, priors, typos, one row per article (runs `sq-t1-ki`, `sq-t2-ki`)

| | Baseline | t1 (match, priors, typos) | t2 (+ family dedupe, own-title tie-break, drop-word probe) | Bar |
|---|---|---|---|---|
| Titles @1 | 63% | 72% | **78%** (en 85%, es 61%) | 85% |
| Titles @3 | 76% | 87% | **91%** | 95% |
| Typos @3 en / es | 0 / 0 | 52% / 45% | **68% / 64%** | 70% |
| Prefixes @3 en / es | 58% / 33% | 67% / 33% | 67% / 50% | 75% |
| Titles p95 (emulator) | 0.43 s | 0.64 s | **1.54 s** | 0.3 s (Pixel 6) |

Left: Spanish ties with English packs (a language prior is next); the typo probes cost Titles' p95, so they move out of
the keystroke path (typed titles first, near titles a moment later, Phase 5); prefixes need popularity we don't have.

## Phase 2 spike: clustered index, offline (`tools/vectors/ivf_spike.py`)

The eval library's 401 vector files (5,191,645 rows × 256 dims, int8), 202 tune-set queries as the phone embedded them
(`vectors-work/r1-queries.phone.json`). Files of 4,000 rows or more clustered into lists of ~1,000 rows (4,930 lists over
28 files); the 373 smaller files (4.8% of rows) are always read. Against the phone's brute force:

| Lists probed | Rows read | Recall@24 | Recall@4 | Best row kept |
|---|---|---|---|---|
| 32 | 5.4% | 79.8% | 83.8% | 82% |
| 64 | 6.1% | 86.4% | 89.5% | 88% |
| 128 | 7.3% | 90.7% | 92.5% | 92% |
| 256 | 9.7% | 94.5% | 95.5% | 95% |
| 512 | 14.6% | 97.3% | 97.5% | 98% |

So ~95% recall reads a tenth of the rows: ~1.4 s on the Pixel 6 if time scales with rows read from today's ~14 s
(Phase 2's bar is 1 s; tuning list size and the small-file floor comes next, then a phone measurement).

**Found on the way:** `wikipedia_en_mathematics_maxi` vectors (released, sha 95ff40ac62d4) hold 31 empty chunks whose
all-zero vectors were written with a NaN scale. A NaN compares as the largest score, so on the phone such a row could
hold one of a range's top-k slots. `VectorSearch` now skips non-finite scores; the builder should not write them
(`tools/vectors/build.py`, at the next vectors release).

### 4.3b Typo titles out of the keystroke path (run `sq-t3-ki`)

Same accuracy as t2 (Titles @1 78%, @3 91%; typos @3 68% en / 64% es). Titles p95 **293 ms** (was 1,540): the typed titles
answer at once and the near titles (p95 1.2 s) are placed after the ranked ones when they arrive. The language prior
changed nothing measurable: most Spanish names carry no language signal. Near titles are skipped for queries over four
words (no typo query in the set has more; on questions they cost 5 s at p95 for nothing).

### 4.2 Best matches in the app (run `sq-best-dev`, the dev questions)

| Slice | n | Today's page @5 | **Best @5** | **Best @10** |
|---|---|---|---|---|
| all | 148 | 22 (15%) | **94 (64%)** | **109 (74%)** |
| classics | 44 | 6 | 22 (50%) | 27 (61%) |
| food | 12 | 1 | 10 | 10 |
| making | 12 | 0 | 9 | 10 |
| maps | 12 | 4 | 11 | 12 |
| medicine | 20 | 0 | 11 (55%) | 14 (70%) |
| reference | 12 | 6 | 9 | 11 |
| science | 12 | 0 | 6 | 7 |
| technology | 12 | 2 | 8 | 8 |
| thought | 12 | 3 | 8 | 10 |

**Both question bars cleared on dev** (50% / 65%), live through `BestMatches` with dense on, scored as `ask_articles.py`
scores (a book answer needs its chapter). Emulator latency: Best matches 1.1 s p50 / 1.6 s p95 (the emulator holds the
vectors in memory; the Pixel 6 reads them from flash, which is what the clustered index is for). Held-out is scored once,
at the end.

### 2.2 Clustered vector files built (`tools/vectors/ivf.py`)

The eval library's 401 files convert in 20 s, same size (1.5 GB): 20,466 lists of ~250 rows, files under 500 rows left
version 1. The phone reads both; DensePassageFinder probes 1,024 lists. Recall on the phone and the speed-up wait for a
Pixel measurement and a vectors release (version 2 files; app builds before this one read only version 1, and only Ask
used vectors there).

### 2.3 The Pixel 6: speed bars met (runs `px-v2e-dev`, `px-v2e-ki`, build 98620bd6, version 2 vectors)

| | p50 | p95 | Bar (p95) |
|---|---|---|---|
| Best matches, first step (questions) | 1.19 s | **2.12 s** | < 2.5 s ✓ |
| Best matches, first step (known items) | 1.31 s | **1.67 s** | < 2.5 s ✓ |
| Best matches, final (questions) | 2.94 s | 3.98 s | |
| Titles per keystroke (questions) | 0.11 s | **0.18 s** | < 0.3 s ✓ |
| Titles per keystroke (known items) | 0.09 s | **0.27 s** | < 0.3 s ✓ |

Quality on the phone sets unchanged: Best @5 18/30, first step @5 17/30; known items (English) @1 24/29, @3 26/29.
The phone has no Spanish Wikipedia or Wikivoyage, so its 12 Spanish known items aren't measurable there.

What got it there, from the first streaming run (`px-v2c-*`: first step p95 4.0 s, titles p95 2.1 s):
- **Titles** (f364b4c7, 98620bd6): libkiwix expands a short last word over English Wiktionary's titles, 0.5–3 s a call
  for no hits ("paradise r", "sea walls of con"); dictionaries now skip suggestions while the last word is one or two
  letters, or three after other words. Packs are asked four at a time. Known items unchanged (201: @1 78%, @3 91%).
- **Best matches** (6e7d8175): the dense hits' articles are rebuilt four at a time (1.1–2.9 s one after another), and
  Search loads the embedder and each pack's title index when it opens (the first query's embedding took 2.6 s, its titles
  1.4 s). Emulator dev questions unchanged (Best @5 94/148); first step @5 there is 99/148, above the final ranking's 94.
