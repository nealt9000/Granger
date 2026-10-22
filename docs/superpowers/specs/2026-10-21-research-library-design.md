# Research: knowing your library and deep links — design

2026-10-21 · Neal Thompson (decisions) · written up by Claude

Status: approved by Neal 2026-10-21. This is project 2 of four (media spec §1). It builds on the R0 spike
(`docs/superpowers/spikes/2026-10-research-spike-results.md`). Plans R1–R3 follow (§13).

## 1. Why

Research is still debug-only. Plan 9 ended at 37% grounded on the held-out set, and R0 starts project 2 at 45% grounded and
91% declined on the tuning split, against a bar of 80% / 90%. R0 found where the misses are: 49 of 69 failed answerable
questions fail before the model sees the answer. Keyword search can't bridge the gap between a question's words and the
source's words ("not worrying about tomorrow" → "take no thought for the morrow").

R0's one big lever was **dense retrieval over an index built ahead of time**: book questions went from 3 to 20 of 35 with the
answer in the top 4. This project makes that index real, and makes every answer open at the place it came from.

"Knowing your library" means two things:
- **Out of the box**, Granger knows its default library well. That library is a fair spread across the nine readiness domains
  (§3), not only books and Wikipedia.
- **As you add things**, Granger learns each book you add, on the phone, with nothing leaving the phone.

## 2. Goals, non-goals, anti-goals

**Goals**
- A **vector set**: precomputed EmbeddingGemma vectors for Granger Classics and a chosen set of Kiwix packs that covers the
  nine readiness domains (§3). Vectors are built on the Mac at pack time and shipped through the catalog.
- **Books you add** (imports, archive.org) are embedded on the phone after download (§5).
- **Hybrid retrieval**: dense hits and keyword hits share the answer slots (§6). Fiction weighting is on by default.
- **Deep links**: every citation opens at its passage, highlighted: an article section, a book passage, a track or an
  audiobook chapter. Media citations get a play button (§7).
- **Latency**: search no longer grows pack by pack with the library (§6.4).
- **A fresh held-out set** written and graded by a separate session, spread across the domains, and judged against Plan 9's
  bar (§8).

**Non-goals (this project)**
- **Cloud training on a user's library as a paid service.** Neal (2026-10-21): it needs more thought first. Nothing here
  uploads library content. §10 lists only what this design leaves open for it.
- Fine-tuning the answerer (R0: retrieval comes first).
- Vectors for every catalog pack. Packs outside the vector set stay keyword-only.
- Vectors for Wiktionary (a dictionary is lexical; keyword search fits it) and for map tiles (no text).
- Read-aloud (project 3) and plans/playlists built by Research (project 4).
- The readiness model itself: domains, tiers and the Efficient set are plan H1 of the Home/Readiness spec (§3.1).

**Anti-goals**
- Question-time embedding of found passages (R0: +3–8 s for +5–7 points).
- A vector set chosen only by what the eval asks. The domains decide it, and the eval follows the domains.
- Any reading content leaving the phone.

## 3. The vector set: what Granger knows out of the box

### 3.1 The nine domains

From the Home/Readiness work (session granger-1d, 2026-10-21). Neal approved the shape ("the nine domains are fine"). The ids
are drafts, and nothing in the repo uses them yet.

| Domain | Covers |
|---|---|
| `medicine` | Medicine & first aid: diagnosis, treatment, drugs, first aid, nursing |
| `food` | Food, water & survival: growing, preserving, cooking, water, shelter |
| `making` | Making, repair & engineering: tools, repair, construction, electronics, machines |
| `science` | Science & math: physics, chemistry, biology, mathematics, textbooks |
| `technology` | Technology & computing: programming, software, networks, documentation |
| `maps` | Maps, places & travel: maps, gazetteers, travel guides |
| `reference` | General reference: encyclopedia, dictionary, quotes |
| `literature` | Literature & the arts: fiction, poetry, drama, music, art |
| `thought` | Religion, philosophy & history: scripture, philosophy, history, biography |

H1 adds `domain` and `tier` (essential / comprehensive) to every Kiwix family in `tools/catalog-gen/kiwix_families.json`, and
`catalog_gen.py` writes them into the catalog. Classics take theirs from their `curated.json` section: scripture and
philosophy go to `thought`, the rest to `literature`. H1 is a week or more from landing. **Until then, the vector set's own
metadata carries a domain per pack** (by family), and it's reconciled with H1's names when H1 lands.

### 3.2 The proposed set

Based on H1's proposed Efficient tier (WikiMed no-pictures, Wikipedia best articles no-pictures, Wiktionary, Granger
Classics, Knots, iFixit, the user's region map). Packs were added where that tier is thin: food, science, technology and
thought. Approved by Neal (2026-10-21), who added the *Algorithms* textbook for technology and the Milne Open Textbooks.

| Domain | Packs with vectors | Size (GB) |
|---|---|---|
| medicine | `wikipedia_en_medicine_nopic`, `wikem_en_all_maxi` | 1.05, 0.37 |
| food | `appropedia_en_all_maxi`, `based.cooking_en_all`, `foss.cooking_en_all` | 0.58, 0.02, 0.02 |
| making | `ifixit_en_all`, `wikipedia_en_knots_maxi` | 3.57, 0.02 |
| science | `libretexts.org_en_k12`, `wikipedia_en_physics_maxi`, `_chemistry_maxi`, `_mathematics_maxi` | 0.37, 0.91, 0.38, 1.00 |
| technology | `jeffe.cs.illinois.edu_en_all` (Jeff Erickson's *Algorithms*) | 0.52 |
| maps | `wikivoyage_en_all_nopic` | 0.27 |
| reference | `wikipedia_en_top_nopic`, `milneopentextbooks.org_en_all` (Milne Open Textbooks) | 2.75, 0.42 |
| literature | Granger Classics EPUBs (literature sections), `wikiquote_en_all_nopic` | —, 0.40 |
| thought | Classics scripture and philosophy, `internet-encyclopedia-philosophy_en_all`, `wikipedia_en_history_nopic` | —, 0.13, 0.77 |

Notes:
- **Milne Open Textbooks spans several domains** (SUNY textbooks across many subjects). It's filed under reference as a whole
  pack, and its questions in the eval are tagged by each question's own domain, not the pack's.
- **iFixit has no full-text index today**, so Research can't search it (Plan 9). With vectors it becomes searchable.
- **Classics ships twice**, as the `granger_classics_en` ZIM and as 347 EPUBs. Vectors go on the EPUBs only. When a work's EPUB
  is installed, its ZIM passages are dropped from Research so the same text doesn't take two slots. *Builder's call: matching
  a ZIM article to its EPUB by work id.*
- **The set is data, not code.** It's the list of catalog entries that carry vectors (§4), so it can grow or change with H1's
  Efficient tier without an app release.

### 3.3 Fair spread

The rule: **every domain has at least one vectored pack in the set**, and the eval has questions in every domain (§8). Domain
shares aren't forced to be equal: Wikipedia stays the largest because reference questions are the most common. Each domain
gets its own grounded number in the eval report, so a weak domain is visible instead of being averaged away.

## 4. Vectors in the catalog

**Building (Mac, `tools/pack-builder`, new `build-vectors` step):**
- Chunks are cut exactly as the phone cuts them: `XhtmlText` for EPUBs (`dense.py` already ports it), and `HtmlPassages` for
  ZIM articles (to be ported). **Gate:** the port and the Kotlin chunker give identical chunks on fixtures and on a sample of
  real articles from each vectored pack.
- Each chunk is keyed so the phone can rebuild its text without storing it: `(bookId, href, ordinal)` for books, and
  `(path, section ordinal)` for articles. A short hash of the chunk text goes with each key. When the phone's chunk doesn't
  match the hash, that vector is skipped and counted in a debug log.
- Documents are embedded titled (`title: <title — heading> | text: …`), which R0 found embeds better. The phone's query
  format stays `task: search result | query: …`.

**The file:** one vector file per pack or book. A header names the embedder file's sha256, the chunker version, dims, dtype
and the source file's sha256. Then come int8 vectors (one scale per vector), followed by the keys. It's memory-mapped on the
phone.
- **Dims and quantization (Claude's proposal): 256 dims (Matryoshka), int8.** That's about 64 MB for R0's 249k book chunks,
  against 383 MB at 768 float16. R1 measures recall against 768 dims, and the set falls back to 768 int8 if 256 loses more than
  2 of the 35 book questions.
- A vector file is valid only for its exact source file and embedder. When Kiwix publishes a new snapshot of a pack in the
  set, its vectors are rebuilt before the catalog points at the new file.

**Catalog:**
- `PackEntry` and `BookEntry` gain an optional `vectors` object: url, sha256, sizeBytes, embedder sha256, dims, dtype and
  chunker version. Older parsers ignore it.
- Vectors download with their item automatically, and their size is shown in the item's size. An item that's already
  installed gets its vectors on the next catalog refresh.
- Vectors are hosted as GitHub release assets, like the Classics. A single asset must be under 2 GB.
- **Licenses:** vectors are derived from their pack and carry its license and attribution. iFixit's are non-commercial,
  like iFixit itself.
- Re-signing the catalog needs Neal's OK at that step, as always.

**Phone vs Mac:** the phone's embedder is int4. Its vectors correlate with the Mac's at r 0.98, not 1. Queries are embedded on
the phone, so recall must be measured with **phone query vectors against Mac document vectors** before the set is built. If
the gap costs more than 2 of 35, document vectors are built with the same LiteRT-LM file the phone runs. *Builder's call: on
the Mac, or on the phone overnight for the smaller packs.*

## 5. Books you add

- Imported and archive.org books are embedded on the phone after `BookIndex` indexes them. They use the same chunker, format
  and key scheme as pack-time vectors, and the file is stored beside the book.
- R0 measured a few hundred to a few thousand chunks a book, which takes minutes on the GPU. The work runs in WorkManager. It
  pauses while Research or a model is in use, and it resumes after a restart.
- Classics EPUBs never embed on the phone: theirs come from the catalog (about 20 hours on the phone otherwise).
- Kiwix packs outside the set, phone media and LibriVox stay as they are: keyword search and metadata cards.
- Phones without the embedder (it needs 6 GB of RAM, `minRamGb`) stay keyword-only. Research says so on its info sheet.

That's how Research becomes expert on any user's library: every book they own gets vectors, the default library ships with
them, and everything else is still found by keyword.

## 6. Retrieval

### 6.1 The query

The raw question is embedded **while the rewrite runs**, so the embedding adds almost no time. For a follow-up, the prior
question is prepended, since R0's misses on follow-ups came from a query with no context. R1 compares raw, rewrite keywords and
prior-plus-question on the tuning split. *Builder's call: the winner.*

### 6.2 Search

- **Dense:** a brute-force int8 scan over every installed vector file, in parallel across files, returns the top K (K is a
  builder's call, about 24). Budget: 1 s on the Pixel 6 for the whole set.
- **Keyword:** unchanged (Xapian for ZIMs, FTS4 for books). It still covers packs outside the set and exact names, dates and
  numbers, which dense misses.

### 6.3 Merging

- Dense hits and keyword hits are pooled and deduplicated by passage key. A passage that has a vector is scored
  `α·lexical + (1−α)·cosine` (R0: α 0.1–0.2). A passage without one keeps its lexical score.
- Keyword-only passages mustn't be shut out because they lack a cosine. R1 compares three ways to share the slots on replay:
  calibrated scores, reciprocal rank fusion, and 1–2 of the 4 slots reserved for dense hits above a cosine floor. *Builder's
  call, by the numbers.*
- Fiction weighting (0.7 on non-literature questions) moves from the debug flag to the default.
- The media cap, `perDocument` 3 and the 4 / 8 slots (Think deeper) stay.
- **Declines:** the unanswerable bar is ≥ 90%. Dense search always returns something, so a cosine floor has to be set for the
  dense side. R0 found a cosine threshold doesn't help declines on its own, so this is measured, not assumed.

### 6.4 Search that doesn't grow with the library

Today every pack × every keyword runs one after another under a per-archive lock, which is why one more pack put 2 s on the
median search. In project 2:
- keyword searches run in parallel across archives, with a bounded pool;
- a vectored pack still runs its keyword search, but with fewer terms (*builder's call: which, measured*);
- chunk text is rebuilt only for passages that reach preselect.

**Latency rule:** median send to first words no slower than R0's 17.4 s, aiming at 14 s. The v1 target stays 9 s.

## 7. Deep links

Every passage gets one **address**, stored with the Research turn and reused by projects 3 and 4:

| Kind | Address | Opens at |
|---|---|---|
| Article | `article:<packId>/<path>#<anchor>~<quote>` | The section, scrolled, with the passage highlighted |
| Book | `book:<origin>:<ref>/<href>@<progression>~<quote>` | The passage, highlighted (Readium text locator, falling back to progression) |
| Track | `track:<id>@<ms>` (`MediaAddress`) | The album with the track playing |
| Audiobook | `audiobook:<id>@<chapter>/<ms>` | The chapter, playing |

- **Articles:** today a citation lands on the section heading. The reader gets a highlight. It finds the quote in the section
  (whitespace- and accent-folded) and marks it, falling back to the heading.
- **Books:** today the quote is the chunk's first 80 characters. It becomes the sentence in the passage that best matches the
  answer's cited claim, so the highlight is what the answer used. *Builder's call: the matching rule.*
- **Media:** `MediaAddress` exists but nothing opens one. Project 2 adds the route and wires it to `PlayerHub`
  (`playTracks` from the track's index, `playAudiobook` at a chapter). Media cards gain per-track and per-chapter entries, so
  Research can cite "Mars, the Bringer of War" and not just the album. A media citation shows a **play button** in the answer.
- An address that no longer resolves (a deleted book, a moved file) opens the nearest thing that does: the item, then the
  pack, then a "no longer in your library" note.

## 8. The eval

- **Fresh held-out set.** A separate session writes it from `tools/eval/AUTHORING.md`. The tuning session never reads its
  questions or per-question results, only totals. It's scored at most twice (eval spec §5). Neal reviews it.
  - At least 10 answerable questions in each domain that has content in the test library, about a third unanswerable, and
    multi-step questions and follow-ups as in Plan 9.
  - A "your own book" group: questions on a few books imported to the test phone that aren't Classics, so on-phone vectors are
    tested.
  - A media group that checks track and chapter citations.
- **Tuning split:** the 202 questions get a domain tag. The tuning session adds questions for domains it doesn't cover
  (likely food, making, maps and technology) so tuning isn't blind there.
- **The test library** on the Pixel 6 has the whole vector set installed.
- **Bar (Neal, 2026-10-21): Plan 9's bar, unchanged.** That's ≥ 80% answerable grounded and ≥ 90% unanswerable declined on the
  held-out set, with the stop path:
  - pass: `evalPassed` flips for E2B (and E4B if it meets its bar), `Features.RESEARCH` leaves debug, and the catalog is
    re-signed;
  - fail twice: Research stays debug-only, and the plan ends with numbers and options, as Plan 9 did. Vectors and deep links
    ship either way, because Search and the readers can use them.
- The report shows grounded per domain, recall@4 dense against keyword, and latency.

## 9. Constraints

- Offline. Nothing leaves the phone. Questions, answers and library content are never collected.
- The Pixel 6 (8 GB) is the test phone. The embedder (165 MB) loads alongside E2B, and loading E4B for Think deeper still
  closes it. That means Think deeper uses the query vector computed before E4B loads.
- Phone rules from Plan 9: never run `connectedDebugAndroidTest` (it wipes the library), never `adb reboot`, never touch the
  phone while one of Neal's apps is in front, and one Gradle build at a time.
- Catalog changes and pushes need Neal's OK at that step.
- Storage: vectors are counted in item sizes. There's no cap beyond the phone's free space.

## 10. Built so later projects fit

- **Project 3 (voice):** reads aloud from an address ("read the passage you cited").
- **Project 4 (actions):** reading plans and playlists are lists of addresses.
- **A later cloud service (not designed):** the vector file format and key scheme don't assume where vectors were built, so a
  vector file made off the phone would load like any other. Whether to offer such a service, and on what privacy terms, is
  Neal's to decide later.
- **H1:** the vector set's domains switch to the catalog's `domain` field when it lands.

## 11. Done means

- The vector set is built, published and installed on the Pixel 6. Every domain has a vectored pack, and the chunker parity
  gate passes.
- A book imported on the phone gets vectors and is found by meaning ("a verse about not worrying about tomorrow").
- Every citation kind opens at its address with the passage highlighted, and media citations play. A Pixel 6 walkthrough
  checks each kind.
- Median send to first words is 17.4 s or less on the tuning split.
- The fresh held-out set is written, scored, and reported per domain. Research either passes the bar and leaves debug, or
  stops with numbers and options.

## 12. Decisions (Neal, 2026-10-21)

- Spec first, then plans.
- The vector set covers the readiness domains fairly. It's built from the Granger collection and the default Kiwix selection.
- Keep the 80% / 90% bar with the stop path.
- Cloud training on a user's library is a possible paid service, but it needs more thought. It's out of scope here.
- Carried from R0: E2B answers and E4B is Think deeper; no question-time rerank; no fine-tuning yet; fiction weighting is
  carried as a default; deep links are engineering.

- The vector set is §3.2's list, with Jeff Erickson's *Algorithms* for technology and the Milne Open Textbooks added. The
  Wikipedia science packs stay alongside LibreTexts.

**Open for Neal:** 256 against 768 dims, if R1's recall numbers make it a close call.

## 13. Order

| Plan | Delivers |
|---|---|
| R1 | Chunker parity, the `build-vectors` step, the vector file and phone store, dense search, merging on replay and then on the phone, fiction weighting by default. Measured on the tuning split |
| R2 | Books you add are embedded on the phone, parallel keyword search, tuning-split additions per domain, the fresh held-out set, the pass-or-stop scoring |
| R3 | Addresses, article highlights, better book quotes, media addresses and play buttons, the walkthrough |

R3 doesn't depend on R1 or R2. It can run alongside them if a second session takes it.
