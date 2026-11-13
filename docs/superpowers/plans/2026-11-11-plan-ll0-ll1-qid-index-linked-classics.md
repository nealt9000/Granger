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
- **Result (2026-11-12): GO.** Code in `tools/links/` (ner → link → rules); working files on the X10 (`granger-ll/ll1/`).
  - **Neal's grades, 200 random Light links (seed 20261112):** P&P 40/40, W&P 79/80, DQ 80/80 → 199/200 (99.5%). The one W&P "wrong", Moscow → Moscow (the city), was a slip; Neal corrected it to right.
  - **Corrected for known errors:** Neal graded the Spanish loosely. Four of the DQ links are wrong by my own check: Lanzarote (Lancelot, not the island), Arcadia → Arcadia Regional Unit, Teodora → Theodora, Santa Lucía → Saint Lucia. That makes DQ 76/80 (95%), right on the bar. English clears it easily (P&P 100%, W&P 99–100%).
  - **What changed from the plan:**
    - Step 3 is dropped. EmbeddingGemma similarity didn't separate wrong links from right ones (wrong 0.46–0.71, right median ~0.59).
    - Choosing comes from rules drawn from the book instead (`link.py`):
      - Social titles mark the cast.
      - A word the book also prints in lowercase is a common word.
      - A name the book mostly tags as a person isn't a place.
      - People and works must agree by name: the label, the surname, or a longer form.
      - Nobody born after year − 10 is linked, and no non-place founded after the book.
      - Spanish accents must match exactly.
      - Fictional items are never linked, nor the book itself.
    - Light uses the English *all* index at sitelinks ≥ 40, not Top-only, which would miss Kutuzov, Bagration and Smolensk.
  - **Density at Light:** P&P 0.9, W&P 2.0, DQ 1.3 links per 1,000 words (110 / 1,064 / 481 links; 19 / 167 / 169 items). How that feels is judged on the Pixel (Task 10).
  - **Fixes before Task 7, all in Spanish:**
    - Arthurian names: Lanzarote, Ginebra.
    - Bare place-like names that are saints or people in context: Santa Lucía, Teodora.
    - Arcadia: prefer the classical region over the modern regional unit.
  - **Fixed the same day:** three new book rules in `link.py`.
    - A name the book prints after a title ("reina Ginebra", "don Lanzarote") is a person there, not a place.
    - "Santa/San X" with a saint candidate isn't a place.
    - A one-word person (sitelinks under 120) whose name starts 30+ people's articles is a given name ("Teodora", "Jerome", "Titus").
    - Effect at Light:
      - DQ loses Lanzarote ×6, Ginebra→Geneva ×5, Teodora ×2, Santa Lucía, Maguncia→Mainz ×2. The rule also costs Adriano and Rodrigo, both right.
      - W&P loses Jerome and Titus, plus Attila, which was right.
      - P&P is unchanged.
    - Re-scoring the graded DQ sample gives 76/77 (99%); Arcadia → the regional unit is still wrong.
  - **English residue:** a surname redirect to a famous person, e.g. "Count Tolstóy" → Leo Tolstoy, and Glinka.
  - The book's year must come from the Classic's Wikidata QID. The manifest's `published` is the translation year.

### LL1 · Task 7: `.glink` for the Classics (Python, tested)
- `tools/links/build.py` runs the spike's pipeline over a Classics pack and writes `.glink` (Decision 4).
- `check.py` re-scores the fixed graded sample and fails under the bar, so the upkeep loop guards precision on every rebuild.
- Registered as a sidecar kind (Task 4); built for `classics` and `clasicos-es`.
- **As built (2026-11-12):**
  - **One `.glink` per book, not per collection.** Each Classic is its own catalog item with its own `.gvec`, so its links ride on it the same way: catalog_gen attaches by the EPUB's sha256, and the file comes and goes with the book.
  - **The file:** `meta` (contract), a one-row `book` table (`id`, `qid`, `year`), and `mentions` as in Decision 4, indexed on `qid` and `href`.
  - **`classics_qid.py`** decides each book's work and year once, into `tools/links/classics-qid.json`, which is committed and reviewable.
    - A match needs the title (exact, or a word prefix if the author agrees) and the author's surname in P50.
    - A work dated after the book's own edition is an adaptation, so it's rejected.
    - With no work, the year is the author's death year if that's earlier, else `published`.
    - English: 334 of 437 books matched a work. Spanish: 86 of 185.
  - **`build.py OUT EPUB…`** finds each book in the Classics manifests and caches NER per EPUB sha, so a rules-only rebuild takes seconds.
  - **`check.py`** verifies the contract and rows, and re-scores `graded-2026-11-12.json` (Neal's 200, with four desk corrections). It fails under 95%, per book with 20+ still linked or overall.
  - `glink` is in `tools/sidecar/kinds.json`.
  - **Full build (2026-11-13):** all 622 Classics, 31 MB, 82,055 Light + 23,050 More-only links. `check.py` passes at 99.5% of 195 graded links.
  - **Shelf scan fixes (builder v2):**
    - Roman numerals aren't names. Spanish chapter heads gave "LIII" → 53.
    - Taxa get their own class (P31 Q16521 / Q310890) and fit no NER label. "el Delfín" (the Dauphin) → Delphinidae, "Cempoal" → a marigold, "Aguacate", "Cuervo", "Reno" and about 40 more are gone.
    - A wider fix (the name-agrees rule for the catch-all class) was tried and dropped. It also cut right links: Españoles → Spaniards, Leteo → Lethe, Jove → Jupiter.
    - Net change from v1: −303 Light links, nearly all numerals and taxa. Right links lost: Cid → Rodrigo Díaz de Vivar ×3, Right Whale ×2.
    - Left as borderline, each the only candidate and its article covering the history: Achaia / Arcadia → the modern regional units, Ammon → Amman.

### LL1 · Task 8: Links in the ebook reader (Kotlin + Compose, tested)
- `BookLinks` reads `.glink` for the open book's chapter and places mentions as Readium decorations (a link style beside the highlight style in `BookAnnotations`); unplaceable mentions are dropped silently.
- A tap opens the peek card (Decision 7): Open goes to the article in the web reader, with Back returning to the same spot in the book. An unresolved QID gets Preview / Get (Decision 6). For a work, the card says "In your library" when the work itself is installed.
- **Reading settings:** Reference links: Off / Light / More, **Light by default**, in English and Spanish.
- **Tests:** placing (quote found, quote moved, quote gone), tier filtering, resolve order, the setting.
- **As built (2026-11-13):**
  - **`BookLinks`** (core:library) reads a `.glink`. It refuses another kind, a newer schema, or a file built from another EPUB than the installed one.
  - **`ReferenceLinksSource`** (the app's `BookLinkSource`):
    - It finds the book's installed `.glink` (`installedBookSidecar`) and opens the phone's QID indexes in Wander's pack order. The indexes are reopened when the installed set changes.
    - It keeps only the mentions whose QID an installed pack has. The card reads the first pack whose article opens: `LeadLinks` gives the title and opening lines, and `ArticleThumbnails` the picture.
  - **Drawing:**
    - Mentions become Readium decorations in their own `links` group.
    - Each is placed by its quote plus the last 32 characters of the same paragraph before it. This uses Readium's text-quote matching, as its own highlights do.
    - The look is a custom `ReferenceLinkStyle` template: a 1.5 px dotted bottom border in the book's link tone, one box per line.
    - The whole book's mentions load once per level. Readium sends each chapter its own.
  - **A tap** opens `LinkPeekSheet` (picture, title, opening lines, Open article). Open goes through `sessions.openTab` + `nav.showTab(keepCurrent = true)`, as Define does, so Back returns to the page.
  - **The setting:** Text settings → Reference links: Off / Light / More, stored in `ReadingPrefs`, Light by default. It appears only in a book that has links. Spanish wording: No / Pocos / Más.
  - `Features.LINKED_CLASSICS` is now on in debug builds, for Task 10. Release stays off.
  - **Changed from the plan (Neal approved both, 2026-11-13: "both changes are fine"):**
    - **Decision 6:** a link whose article no installed pack has isn't drawn, rather than offering Preview / Get. Every drawn link opens, and the catalog has no per-pack QID coverage yet to choose a pack to offer.
    - **"In your library" for a work** on the card moves to Task 9, with author → works, which uses the same joins.
  - **Tests:**
    - `BookLinksTest` (3).
    - `ReferenceLinksTest` (6): tiers and Off; a chapter this edition lacks; a book with no links; tap → card or "gone"; the source's resolve order, dropping unresolved QIDs, reopening after a pack goes, and wrong-EPUB and wrong-pack files ignored.
    - `ReadingPrefsTest`: the setting's default and storage.
    - Placing against real page text (quote moved or gone) is Readium's JavaScript, so it's checked on the phone in Task 10.

### LL1 · Task 9: *In your books* and author → works (Kotlin + Compose, tested)
- **The web reader's article footer** gets a native section under the article, not injected into the HTML:
  - *In your books*: book title and count, then up to three passages each, each opening at its `PassageAddress.Book`.
  - *By this author in your library*: P50 / P800 → Gutenberg ID → installed books, with covers.
  - Hidden when empty.
- Queries go across installed `.glink` files by QID on IO and are cached per article.
- **Tests:** the queries on fixtures, and empty-state hiding.
- **As built (2026-11-13):**
  - **Under the article.** The page is a WebView, so a native section can't follow its HTML. Instead, an article with a footer gets 96 dp more bottom padding, and once the page's end is in view a native card ("In your library", plus one summary line) fills that band. A tap opens the full sheet. A page too short to scroll shows the card at once.
  - **The sheet:**
    - *In your library*: this article's own work, when its book is on the phone, with Read.
    - *In your books*: each installed Classic that mentions the subject, most mentions first, with its count and first three passages. Each passage opens the book at that quote through `PassageAddress.Book` + `openCitation`.
    - *By this author in your library*: covers and titles, each opening the book at its saved place.
  - **Author → works** joins on the work QID each `.glink` carries (decided on the Mac, Task 7): the author's P800 plus every work naming them in P50, matched against the installed books' works. The plan's Gutenberg-ID route isn't used, because P2034 is rare on work items (LL0 finding).
  - **Queries: one local index, not a file per book** (Neal, 2026-11-13: "I generally download the whole pack"; the first version opened every installed `.glink` for each new article).
    - `MentionIndex` (core:library) keeps every installed Classic's mentions in one app-local SQLite file (`files/linked-library/mentions.sqlite`), indexed on QID.
    - `sync` copies a new or changed book in with SQLite's ATTACH and drops a removed one. It does nothing while the installed set is unchanged, including across restarts.
    - On the Mac, all 622 books: 105,105 mentions, a 30 MB index, built in 2.1 s. Five footer queries take 0.8 ms in all.
  - **Capped at eight books.** London is in 290 of the 622 Classics, so the sheet lists the eight books that mention a subject most, then "and N more of your books". The card counts them all.
  - **Pack splitting:** smaller, themed Classics packs may still be worth offering for choice, but speed no longer needs them.
  - **The peek card** (Task 8's moved item) says "In your library: <title>", with Read the book, when the link is a work whose book is on the phone.
  - **Tests:** `MentionIndexTest` (2: answers by QID; re-sync after a removal or an updated file; wrong files left out); the footer's three sections and the eight-book cap (`ReferenceLinksTest`, 8 in all now); `passagesOf` (`BookLinksTest`); the web reader asking once per article, keeping the footer on a jump within the page, and an empty footer showing nothing (`ReaderViewModelTest`, 2 new); the card's summary line.

### LL1 · Task 10: Phone check, Pixel 6 (then Pixel 7)
- Read a chapter of each spike book with Light, then More. Open links and come back. Check an unresolved link with Top only installed. Check *In your books* on London, Napoleon and Jane Austen. Repeat in Spanish (Cervantes, La Mancha).
- Sizes, timings (placing a chapter's links < 50 ms) and Neal's findings recorded here.
- **LL1 checkpoint with Neal:** density, look and the setting's wording, then the walkthrough and merge.

## Order and size
LL0 Tasks 1–2 first: they're the debt payback and touch shared code, so agree with SQ before merging. Task 3 can run on the X10 alongside them. The LL1 spike (Task 6) can start as soon as Task 3's data exists, before the phone side lands. About six to eight sessions in total.

## Not in LL0 + LL1
The article context panel, Related, the other-language link and web highlights (LL2); the Notebook (LL3); the companion and LibriVox (LL4); music links (LL2); the user's own EPUBs (later); the Atlas (LL6).

## Tasks 1–2 built (2026-11-11)
- **The catalog:** `SidecarEntry` (`kind`, `url`, `sha256`, `sizeBytes`, `schemaVersion`) as `sidecars: [...]` on packs and books. `SidecarEntry.KNOWN` (`gqid` 1, `glink` 1) is what this app reads; the parser drops other kinds, newer schemas, bad files and a second of a kind, keeping the entry. `downloadBytes` counts them.
- **`catalog_gen.py`:** `attach_sidecars` and a `sidecars` command, using one descriptor shape (`SIDECAR_KEYS`; `kind` stays pack/book as in the other descriptors, and `sidecar` names the kind). They attach to every flavour of a snapshot (as the time index does), books by exact file. Tier sizes and `check-links` count them. **`hold_back` waits for each sidecar kind a pack carried** to be rebuilt from the new file. `curated.json` gets its `sidecars` setting with the first published file (LL0 Task 3), so today's catalog builds don't change.
- **`tools/sidecar/meta.py`:** writes and checks the meta table and writes the release descriptor; every builder uses it.
- **One delivery path:** `Sidecars` + `SidecarKind` replace `VectorDownloads` and `TimeIndexDownloads`. **Changed from Decision 1 / Task 2:**
  - **No DB migration.** `gvec` and `gtime` keep their record kinds (`VECTORS`, `TIME_INDEX`), item ids (`.vectors`, `.timeindex`) and folders, so nothing on testers' phones moves. Later kinds are `ItemKind.SIDECAR` under `sidecars/` (kinds are stored by name).
  - **The catalog's `vectors` and `timeIndex` fields stay as they are** until Plan SQ has merged, because SQ changes the vector gate and the `.gvec` format. Moving them into `sidecars` is a tech-debt-session item. `followVectors` keeps its name for the same reason.
  - **One push tool waits for Task 5**, when there's a `.gqid` to push.
- **Tests:** catalog parsing (3 new), the reconcile tests moved onto `Sidecars` plus later kinds (1 new), catalog_gen (4 new), meta (3). The full JVM suite: 3,338 tests, 0 failures. Python: catalog_gen 110, timeindex 15, sidecar 3.
- **For the tech-debt session:** "sidecar" also names archive.org's metadata files (`ArchiveDownloads.sidecar*`, `archiveSidecarDir`). They're in different packages, but one of the two names should change.

## Tasks 3–5 built (2026-11-11)
- **The Wikidata pass:** one pass, en + es, every item with an article on either site, 43 min (`/Volumes/X10 Pro/granger-ll/items-enwiki.jsonl` 10.4 M items, 1.7 GB; `items-eswiki.jsonl` 2.7 M, 0.5 GB). LL1's link candidates come from the same files.
- **35 `.gqid` built and checked so far** (`/Volumes/X10 Pro/granger-ll/gqid/`, descriptors in `tools/sidecar/releases/`). **Coverage 99.0–100%** on every pack (the floor is 98%). Sizes: Top 5.6 MB (en and es), History 3.9 MB, Medicine 5.1 MB, most subject packs under 2 MB. The first 15 total 30 MB. **44 of 47 built by the afternoon:** the bigger packs check out too — en Top 1M 99.46% (95 MB), es all mini 99.50% (198 MB), en football / movies 99.9% (21 / 30 MB). en all mini 98.56% (7.23M of 7.33M articles) but **583 MB** — close to the floor and too big for a quiet extra download; LL1 should look at what the 105k misses are (likely pages renamed since the ZIM) and slim the file (e.g. links only for ranked items) before it ships. **All 47 built (2026-11-12), 2.2 GB in all.** en all nopic 98.27% (578 MB). en all maxi first failed at 97.61%: maxi carries 2.2M Category:/Portal: pages the shared walk counted as articles; `zim_articles.is_namespace_page` now skips them (en + es namespaces, Spanish Anexo: kept), and the rebuild passes at 99.63% (581 MB). Mini and nopic have no namespace pages, so their ~1.5% misses are something else (likely renames since the ZIM) — LL1 looks at them with the slimming. The time index walk uses the same function, so its next maxi rebuild drops those pages too.
- **Downloads (Neal: "put any needed downloads on the X10"):** one ZIM per catalog Wikipedia snapshot with no copy on the Mac, smallest flavour each, sha256-checked: 32 files, 214 GB, in `/Volumes/X10 Pro/granger-ll/sources/` (`fetch.sh`, `wanted.tsv`, `fetch.log`). English all needs all three flavours (14 + 53 + 127 GB), since each is pinned at its own snapshot. `build-downloaded.sh` builds whatever has arrived. Left out: Simple English and Swahili (no `simplewiki`/`swwiki` pass yet).
- **Finding for LL1: Gutenberg IDs (P2034) are rare on work articles' items** (76 values in English Top, 36 in History). Wikidata puts them on edition items. So a Classic's QID must be decided on the Mac (from the manifest's title, author and Gutenberg ID against the full items file, with editions → P629 → work) and stored in the `.glink`, not looked up on the phone through P2034. Author → works on the phone works through P50/P800, which are well filled (Top: 9,644 P800 values).
- **Phone:** `QidIndex` / `QidIndexes` (core:library) and `installedSidecars(kind)` (the container). A file whose meta names another pack file doesn't open. No screen reads it yet (LL1).
- **Next:** build the rest as downloads land, then Neal uploads the files to a `sidecars-<date>` release, `curated.json` gets `"sidecars": {"releases": "tools/sidecar/releases", "releaseUrl": …}`, and the catalog is re-signed (Neal's step, as for vectors and the time index).
