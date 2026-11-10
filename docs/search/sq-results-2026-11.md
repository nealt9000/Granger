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
