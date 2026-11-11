# Plan LL0 + LL1: Sidecars, the QID index, and linked Classics

**Spec:** `../specs/2026-11-11-linked-library-design.md` §3.1 (reference links), §4 (folding in), §5 (care-loop rules), §6 (order).
**Branch:** `ll-linked-library` (worktree `.claude/worktrees/ll`).
**Goal:** names, places, works and events in the Classics become quiet links that open the right article in whatever Wikipedia is on the phone. Articles gain *In your books* and author → works. Underneath, one sidecar format and one delivery path replace the separate time-index and vector code, so the care loop has one kind of thing to rebuild and check.

## What it builds on
- **The Wikidata slice and dump** on `/Volumes/X10 Pro/granger-g1/`: `wdfilter.py` already matches every article to its item. `items.jsonl` (338,368 en) and `items-eswiki.jsonl` (116,165) are the starting point; the dump (2026-11-06) is there for a wider re-run.
- **`.gtime` delivery** (`TimeIndexDownloads`, `TimeIndexEntry`, `tools/timeindex/push.py`) and **`.gvec` delivery** (`VectorDownloads`, `VectorsEntry`, `tools/vectors/push.py`): two near-copies of one idea (61 and 62 lines) and the template for `Sidecars`.
- **Classics manifests** (`tools/pack-builder/classics/manifest*.json`) with Gutenberg IDs and people.
- **Readium decorations** (`app/.../ebook/BookAnnotations.kt`) already draw highlights from stored positions; links use the same placing.
- **`PassageAddress.Book`** for *In your books* rows that open at the passage.
- **The upkeep loop** (`tools/upkeep/`), which LL0 teaches to rebuild and check sidecars.

## Decisions (proposed; Neal to confirm at the checkpoints)
1. **The sidecar contract (spec §5.1).** A read-only SQLite file, `<zim stem>.<ext>`, with
   `meta(key TEXT PRIMARY KEY, value TEXT)` holding `kind`, `schemaVersion`, `sourceZim`, `sourceSha256`, `builder`, `builderVersion`, `builtAt`.
   - The catalog carries one list per item, `sidecars: [{kind, fileName, sizeBytes, sha256, url, schemaVersion}]`.
   - `vectors` and `timeIndex` stay readable in old catalogs for one release, then go.
2. **Kinds:** `gtime` (exists), `gvec` (exists; a flat binary, so its meta rides in the release JSON and catalog entry, not inside the file), `gqid` (LL0), `glink` (LL1).
3. **`.gqid`, one per Wikipedia pack:** `articles(path TEXT PRIMARY KEY, qid INTEGER, rank INTEGER, other TEXT)` plus `links(qid INTEGER, prop TEXT, value TEXT)` for the joins the folds need:
   - P50 author, P800 notable work, P629 edition of, P921 main subject, P2034 Gutenberg, P1899 LibriVox, P434 MusicBrainz artist, P724 archive.org, P625 coordinates;
   - an index on `qid`.
   - `.gtime` keeps its own table for now; it moves onto the QID index in the tech-debt session if that pays.
4. **`.glink`, one per Classics pack:** `mentions(book TEXT, href TEXT, start INTEGER, len INTEGER, quote TEXT, before TEXT, qid INTEGER, tier INTEGER, conf REAL)`.
   - `tier` 1 is Light, 2 is More.
   - The phone places a mention by `quote` + `before` within the chapter (Readium's text locator), so small reflow changes don't break it.
   - The reverse view is a query on the same table (`qid` index), so *In your books* needs no second file.
5. **What gets linked (Light):** people, places, works, events and organisations with a QID whose article is in at least Top English or Spanish Wikipedia.
   - The first mention per chapter only.
   - At most one link per ~150 words.
   - Rank (sitelinks) at or above a threshold the spike sets.
   - Never: common nouns, the book's own characters (fictional names that collide with real ones: "Elizabeth Bennet" isn't Elizabeth I), quoted dialogue tags.
   - **More** lifts the density cap and the rank floor, and links every chapter's first mention.
6. **How a link resolves:** QID → every installed pack's `.gqid`, in Wander's pack order. If none has it, a sheet offers Preview (library.kiwix.org) or Get for the smallest pack that has it, from the catalog's QID coverage (a count per pack in the release JSON, not a list).
7. **How links look:** a thin dotted underline in the theme's link tone, unchanged by read-aloud highlighting. A tap opens a peek card (title, the article's first sentence, thumbnail, Open). It never jumps straight out of the book.
8. **The book's own characters are excluded** with a desk-built per-book cast list (names that recur but have no confident match, or match a fictional-character item). The same list seeds LL4's companion.

## Tasks

### LL0 · Task 1: The sidecar contract (Python + Kotlin, tested)
- `tools/sidecar/` (new, small): `meta.py` (write and verify the meta table, compute `sourceSha256`), `release.py` (one release JSON shape for every kind), `push.py` (one push for every kind; replaces the two near-copies, which become thin wrappers).
- `:core:catalog`: `SidecarEntry` and `PackEntry.sidecars` / `BookEntry.sidecars`, still parsing the old `vectors` / `timeIndex` fields into it. `downloadBytes` sums sidecars. `catalog_gen.py` emits `sidecars`.
- **Tests:** a meta round trip in pytest; catalog parsing of old, new and mixed entries in JVM.

### LL0 · Task 2: One delivery path (Kotlin, tested)
- `:core:downloads`: `Sidecars`, the single `reconcile` from `TimeIndexDownloads` and `VectorDownloads` with the kind as data:
  - which phones get a kind (vectors keep SQ's gate, `GrangerApp.kt:608`; the rest go to every phone);
  - queue on the pack's volume, remove with the pack, prune old versions.
- The two classes go, and `ItemKind.TIME_INDEX` / vector kinds map to `ItemKind.SIDECAR` + kind, with a DB migration and its test.
- A sidecar whose `sourceSha256` isn't the installed pack's is ignored, and the feature's reader says "missing", not "error".
- **Coordinate with SQ before merging:** SQ Phase 6 moves the vector gate. Whichever lands second rebases. Agree the order with the SQ session first.
- **Tests:** the existing reconcile tests, moved onto `Sidecars` and run for both kinds; a migration test.

### LL0 · Task 3: The QID index (Python, tested)
- `tools/qindex/build.py ZIM ITEMS OUT` writes `.gqid` (Decision 3) from the ZIM's real articles joined to the items.
- `wdfilter.py` gains the Decision 3 properties (a re-run over the dump, en + es, a few hours on the X10).
- `check.py`: coverage (articles with a QID; target ≥ 99%, per living Home §11), 50 random rows for a hand check, and the counts per join property.
- **Built for the catalog's Wikipedia packs, en + es.** Sizes recorded here; expected a few MB per pack.

### LL0 · Task 4: The upkeep loop builds and checks sidecars
- `upkeep.py`: when a pack refreshes, list its sidecar kinds from the catalog, run each builder and its `check.py`, and hold the refresh if a check fails (the `--previous` hold-back rule).
- One builder registry (`tools/sidecar/kinds.json`: kind → build command, check command, inputs). Adding a kind is one row.
- **Test:** a dry run on one small pack rebuilds `gtime` + `gqid` and passes both checks.

### LL0 · Task 5: Reading `.gqid` on the phone (Kotlin, tested)
- `:core:library` `QidIndex`: `qidOf(packId, path)`, `pathsOf(qid)` across installed packs in Wander's order, `linksOf(qid, prop)`, `byProp(prop, value)` (Gutenberg ID → QID).
- **Tests** against a fixture `.gqid`.
- **LL0 checkpoint with Neal:** the sizes, coverage and a dev push; no visible change yet.

### LL1 · Task 6: The linking spike (Mac, 3 books, go/no-go)
- *Pride and Prejudice*, *War and Peace* (Maude), and one Spanish book (*Don Quijote*), from the pack builder's EPUBs.
- **The pipeline:**
  1. Entity spans with a small offline NER (spaCy `en_core_web_trf` / `es_core_news_lg`; the Mac only).
  2. Candidates from Wikipedia titles and redirects in the dump's sitelinks.
  3. Choose a candidate with EmbeddingGemma: the passage around the mention against each candidate's lead-chunk vector (we have them), plus rank as a prior.
  4. Exclude the cast list (Decision 8).
  5. Apply the Light/More rules (Decision 5).
- **The bar:** **≥ 95% precision at Light** on 200 hand-graded links (Neal grades a sample in a simple HTML page), and density Neal likes when reading a chapter on the Pixel.
- **A no-go** (precision under 90%) falls back to a curated list: only QIDs named in the manifest's people plus places with rank ≥ a high floor. That's narrower but safe.
- Findings and numbers recorded here.

### LL1 · Task 7: `.glink` for the Classics (Python, tested)
- `tools/links/build.py` runs the spike's pipeline over a Classics pack and writes `.glink` (Decision 4).
- `check.py` re-scores the fixed graded sample and fails under the bar, so the upkeep loop guards precision on every rebuild.
- Registered as a sidecar kind (Task 4); built for `classics` and `clasicos-es`.

### LL1 · Task 8: Links in the ebook reader (Kotlin + Compose, tested)
- `BookLinks` reads `.glink` for the open book's chapter and places mentions as Readium decorations (a link style beside the highlight style in `BookAnnotations`); unplaceable mentions are dropped silently.
- A tap opens the peek card (Decision 7): Open goes to the article in the web reader, with Back returning to the same spot in the book. An unresolved QID gets Preview / Get (Decision 6). For a work, the card says "In your library" when the work itself is installed.
- **Reading settings:** Reference links: Off / Light / More, **Light by default**, in English and Spanish.
- **Tests:** placing (quote found, quote moved, quote gone), tier filtering, resolve order, the setting.

### LL1 · Task 9: *In your books* and author → works (Kotlin + Compose, tested)
- **The web reader's article footer** gets a native section under the article, not injected into the HTML:
  - *In your books*: book title and count, then up to three passages each, each opening at its `PassageAddress.Book`.
  - *By this author in your library*: P50 / P800 → Gutenberg ID → installed books, with covers.
  - Hidden when empty.
- Queries go across installed `.glink` files by QID on IO and are cached per article.
- **Tests:** the queries on fixtures, and empty-state hiding.

### LL1 · Task 10: Phone check, Pixel 6 (then Pixel 7)
- Read a chapter of each spike book with Light, then More. Open links and come back. Check an unresolved link with Top only installed. Check *In your books* on London, Napoleon and Jane Austen. Repeat in Spanish (Cervantes, La Mancha).
- Sizes, timings (placing a chapter's links < 50 ms) and Neal's findings recorded here.
- **LL1 checkpoint with Neal:** density, look and the setting's wording, then the walkthrough and merge.

## Order and size
LL0 Tasks 1–2 first: they're the debt payback and touch shared code, so agree with SQ before merging. Task 3 can run on the X10 alongside them. The LL1 spike (Task 6) can start as soon as Task 3's data exists, before the phone side lands. About six to eight sessions in total.

## Not in LL0 + LL1
The article context panel, Related, the other-language link and web highlights (LL2); the Notebook (LL3); the companion and LibriVox (LL4); music links (LL2); the user's own EPUBs (later); the Atlas (LL6).
