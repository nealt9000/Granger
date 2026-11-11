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

### 4.4 Names and Best matches, round 2 (runs `sq-t7-ki`, `sq-best-dev5`, emulator)

**Known items (201):**

| | Before (`sq-t4-ki`) | Now (`sq-t7-ki`) | Bar |
|---|---|---|---|
| Right item first | 78% | **90%** | 85% ✓ |
| Top 3 | 91% | **93%** | 95% ✗ |
| Typos in top 3 (en / es) | 68% / 64% | 72% / 82% | 70% ✓ |
| Prefixes in top 3 (en / es) | 67% / 50% | 67% / 83% | 75% (en ✗) |
| Spanish right first (vs English) | 61% (85%) | 86% (92%) | within 5 points (✗ by 1) |

- **One row per title** (d14aad78, Neal's pick): the same folded title from several packs or the shelf is one row, the
  others named under it ("Also in Wikivoyage (ES)"), each a link and a TalkBack action. ~20 of the 45 first-place
  misses were such ties ("Turingia" in Spanish Wikipedia and Wikivoyage; "Jacob's Room" on the shelf and in Classics).
  The scorer finds a target anywhere in its row.
- **Typo probes** (5d03e6ef): one-letter words are left out ("Wyne's World" asked for "s world"), and each long word's
  initial is tried. Near titles count words of two letters or more.
- **Not done:** one-word typos ("Misoula", "Tzone", "Kanai") are most of the 14 left at 3. libkiwix's SpellingsDB
  (`getSpellingCorrections`) would reach them, but building it on the emulator ran the app out of native memory (Scudo
  map failure) on the big packs; dropped. Several of the rest are arguable targets ("flatus" → Flatulence, Wikipedia's
  own redirect, ahead of "Flatus bag").

**Best matches** (6edfdd7b): Ask's gate is off (gated, "how can a huge steel ship float" lost its dense hits to an
empty keyword list), keyword-only passages score with cosine 0.66 (`merge.py` on the recorded dev retrieval: @5 68% →
70%, @10 72% → 77%), the dense ranking stands when nothing clears the floor, and one article in two packs of a family is
one row. The scorer now counts the answer's article in any pack of its family (as known items do); on that count the
first step's lead over the final ranking (68% vs 64%) was the same article in another Wikipedia pack.

| Dev questions (148) | `sq-best-dev` (rescored) | `sq-best-dev5` | Bar |
|---|---|---|---|
| Best @5 | 101 (68%) | **102 (69%)** | 50% ✓ |
| Best @10 | 113 (76%) | **115 (78%)** | 65% ✓ |
| First step @5 | 101 (68%) | 101 (68%) | |

## Step 4: quotes and indexing gaps

### 4.5 Quotes from books (runs `sq-q1`–`sq-q4`, emulator; set `sets/quotes.json`)

Neal tries lines from the Classics books and found Search hit or miss on them. `tools/eval/quotes.py` builds 127 quotes
from the books as the app has them, each with its book and chapter: famous lines (25 found in the collection, plus 2
controls that aren't in it: "Of all the gin joints", "Elementary, my dear Watson"), exact lines (8–12 words), fragments
(4–5 words from inside a sentence), loose lines (one inner word left out) and plain lines (lower case, no punctuation);
20 are Spanish. `search_score.py score` scores a quote set by book and chapter in Books and in Best matches.

**Books was right but slow.** The book was in Books' first three for 95% of quotes, but Books took 11.6 s at the median
and 413 s at p95 (27 min at worst, "this foreseeing does not in"). The FTS query itself took 0.2 s; the time was
`matchCounts`, whose plain JOIN let SQLite walk the books' chunks and re-run the whole match for each (55 s on the Mac
for one quote). Any search whose phrase found fewer than three places hit it. A CROSS JOIN keeps the match first
(873f46cb): Books 278 ms median, 1.08 s p95.

**Best matches missed them.** Ranked by keywords and meaning, a quote lost to articles sharing one word ("Alas, poor
Yorick" gave "Working poor"; a Hardy line gave "She Loves You"). The quote step (e2e07276): `BookIndex.quoted` finds the
chunks holding the query as a phrase, else with each word within two of the next (a word misremembered), one per book,
the oldest book first ("Alas, poor Yorick" is in Tristram Shandy, Hard Times and The Brothers Karamazov too, once each),
and none when over 20 chunks match (the words are too common to point anywhere). Best matches lists them first, or
third for a three-word query: a three-word phrase in a book is as often a topic ("speed of light", "life of napoleon"
each match two or four chunks) as a quote.

| Quotes (125 with a book) | Books @3 | Best @5, before (`sq-q2`) | Best @5, now (`sq-q4`) |
|---|---|---|---|
| All | 95% | 20% | **98%** |
| Famous | 96% | 16% | **100%** |
| Exact | 100% | 20% | **100%** |
| Fragment | 96% | 8% | **96%** |
| Loose (a word left out) | 92% | 24% | **100%** |
| Plain (lower case, no punctuation) | 92% | 32% | **92%** |
| Spanish | 100% | 30% | **100%** |

The dev questions are unchanged (`sq-best-dev6`: Best @5 102/148, @10 115/148); Best matches is ~70 ms slower at the
median. Left: "thought he had been" (too common to point at one book: arguably right), two plain lines that find
nothing or the wrong book, and one control that still lists books in Books (Best matches shows no book for it).

**The Pixel 6** (build e2e07276, version 1 vectors, runs `px-q1`, `px-dev-q`, `px-ki-q`): quotes Best @5 (book) 121/125
(97%), Books @3 94%, Spanish 20/20; dev questions Best @5 19/30, @10 22/30; English known items @1 26/29 (was 24/29;
the Pixel has no Spanish packs, so the Spanish 12 can't score). Speed isn't comparable with §2.3, which ran version 2
vectors: Best's first step settled at 2–3 s, but took 5–18 s for the first ~50 quotes, the first ~12 minutes after the
install, while the phone caught up on its own work (most likely the book vectors' rebuild). Worth checking with version
2 vectors on the phone before release; an update shouldn't make Search slow for its first minutes.

### 3.1 Indexing gaps: measured, not worth a rebuild now

Ask's step-back (§9) counted 23 dev answers in no vector chunk. Re-checked against the packs (`dev-base-1`'s
`qindex-ranks.json`, each answer's article re-chunked with the app's chunker): 25 answers on 17 questions.

| Why the answer has no chunk | Answers |
|---|---|
| Not a gap: the quote is in the chunk, but the dataset's quote has stray spaces ("headache , dizziness") | 7 |
| Past the 12-chunk cap (Common cold 14th chunk, Burn 17th, Buoyancy 21st, Helium 39th, Haitian Revolution 18th, Boston Tea Party 15th, Meiji era 18th) | 7 |
| In a table or infobox (Wikivoyage's plug table, an Altitude sickness box, Copper's and Aluminium's melting points) | 4 |
| A Classics book (Kidnapped), and the rest of one multi-part answer | 7 |

**Cost of lifting the cap** (`counts.json` against `counts-trimmed.json`): the 12-chunk Wikipedia packs grow by 365 MB,
+17% of the set (Top 1M 162 → 396 MB, History 87 → 146 MB, Medicine 104 → 141 MB), and ~11 h more embedding on the Mac.
Infobox text would mean a chunker change (tables are left out by `ArticleText.NOISE`, which the reader and the voice share),
so a full rebuild.

**Gain:** Best matches ranks articles, and the keyword side already searches past the cap, so 6 of the 11 real gaps
already have their article in the first five (`sq-best-dev6`: Haitian Revolution 1st, Boston Tea Party 1st, Burn 2nd,
Electrical systems 3rd, Altitude sickness 4th). At most four or five dev questions could rise (Common cold, now 8th;
Buoyancy; Meiji era; Copper and Aluminium), about 3% of 148; Helium's answer is past any cap short of 40.

**Decision:** not now. Lifting the cap and adding infobox text stay open for a vectors release that rebuilds the set for
another reason. What the cap costs is mostly the highlighted sentence: the row is right, but it shows an early passage
rather than the answer.

## Phase 5: the results page

Built on top of Task 5.1's Best matches block (`SearchScreen`, `SearchViewModel`); ThemeShots `search-best`,
`search-best-landscape`, `search-define`, `search-empty`, in English and Spanish (`-PthemeShots`).

- **Rows (5.1).** Each Best match has a kind icon (article or book, read out by TalkBack), and its sentence shows the
  searched words in bold, case and accents aside ("burns", "BURN" for *burn*; words of one or two letters whole only).
- **States (5.2).** "Nothing found" now waits for Best matches too: with vectors on, meaning search nearly always lists
  something, so the empty state is rare. The empty state is one plain line ("Nothing in your library for that. Try fewer
  words.", Decision 11) and a Get more button. The `titleOnly` note stays.
- **Filters (5.3).** Chips All · Articles · Books · Media, shown once a query finds more than one kind. A filter
  narrows every group (Best matches by passage kind, Titles by shelf vs pack) and stays from query to query. Not done:
  "In: <pack>": nothing opens Search from a pack today, so it waits for a pack-level Search entry.
- **Define (5.4).** A single word (letters, hyphen or apostrophe; 2+ letters) is looked up with `PassageFinder.entry`
  (the installed Wiktionary in the word's language first) beside the other searches; an entry shows as a small card above
  Best matches (the senses, the etymology left out), and opens the entry. It is hidden under Books and Media.
- **Medical (Decision 8).** A query that reads as medical (`Medical.looksMedical` or `PersonalMedical.strong`, English
  and Spanish lexicons) shows Ask's banner over the results. Search only quotes, so nothing else changes.
- **Typed "Did you mean" (4.5).** When a typed query's keyword groups (titles, article text, books, media) find
  nothing, the library name `SpokenTitleMatcher` likes best is offered in the same row the mic's uses; it is never
  applied by itself, and a spoken query keeps its own suggestion. The word-level fix from the title vocabulary is not
  built: typo titles (§4.3b) already cover most of it.

## Phase 6: search by meaning, off Ask's switch

**The bug it fixes.** `meaningSearch` was `askOn`, and Phase 0 turned Ask off (`Features.RESEARCH = false`), so a release
build fetched no vectors, no embedder and embedded no books on any phone: Best matches were keyword-only everywhere. The
Pixel runs had dense hits only because that phone kept the vectors and embedder from before.

- **Which phones.** `meaningSearch = DENSE_RETRIEVAL && deviceTier.canRun(EMBEDDER)`: phones of Ask's memory tier
  (`ASK_MIN_BYTES`, marketed 6 GB), whether Ask is on or not. The catalog lists vectors (and onboarding counts them) on
  those phones only, as before. **4 GB phones (E1, the Xperia 10 II) stay keyword-only**: none has been measured, and E1
  is with its tester. Measuring one is the open item before widening the line.
- **The embedder follows the vectors.** Nothing fetched EmbeddingGemma once Ask's line left onboarding.
  `EmbedderDownloads` queues it (165 MB) on the volume of the phone's first vectors download, once: a marker file keeps a
  removal in Models. Onboarding's Review counts it in the total and in "Includes … for search by meaning".
- **A speed guard** (`EmbedSpeed`). On the Pixel 6 the query's embedding is ~0.12 s (p50 113 ms, p95 117–130 ms over
  `px-v2e-*`, `px-dev-q`); the dense search after it is most of the 1.2 s first step. Search times embedding + dense
  search per query; when the median of the last five (the first after a start left out, as it loads the model) is over
  4 s, twice the Pixel's p95, Best matches go keyword-only, and try meaning again every 20 searches.
- **Words.** "Index for Ask" → "Search index"; Review's "indexes for Ask" → "for search by meaning"; the embedder in
  Models: "Used by Search to find passages by meaning". Spanish too.
- **Sizes.** On 6 GB phones the starts count the vectors again, as they did before Phase 0 (Efficient carried 787 MB of
  them, Plan E1), plus the embedder's 165 MB. The presets themselves (Neal's ~20 GB middle tier) are unchanged.
