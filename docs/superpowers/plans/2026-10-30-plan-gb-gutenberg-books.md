# Plan GB: Gutenberg packs as book collections — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** a Project Gutenberg Kiwix pack becomes a book collection.
- **On the shelf:** it sits in the Library beside Granger Classics, named for its subject.
- **Reading:** its books open in the e-book reader.
- **Getting them:** its packs are offered under Books in Get more, not among the Kiwix knowledge packs.

Neal, DL walkthrough (2026-10-30): "I downloaded a Project Gutenberg Library pack with music education books… in order to get to a book I had to tap one, then tap a '5' button on the page, which then presented the whole e-book on one long page. We need a pragmatic way to handle kiwix packs full of ebooks, so they present like a collection in the book library… Also, all the Gutenberg packs are titled 'Project Gutenberg Library' in our app instead of the name describing what they are." And: "We should also make the GB packs of epub files show in the books 'get more' instead of the zim browser area."

**Settled with Neal, 2026-10-30:**
- **Name:** the collection is called by its subject ("Music and books on music"), with a small "Project Gutenberg" line under it. Elsewhere the pack reads "Music and books on music · Project Gutenberg" (Decision 2).
- **Search:** titles and authors are searchable at once, and a book's full text after its first open. The collection says how much of it is searchable in full, and each book shows whether it is (Decision 6).

**Out of this plan:**
- Other Kiwix packs of e-books, if any turn up (the Gutenberg scraper is the only known one).
- Vectors for Gutenberg books.
- Anything special for the two everything-packs (221 GB and 253 GB) beyond listing them last with their size.

**Tech Stack:** unchanged (Kotlin 2.4.21, Compose Material 3, Room, Readium, libkiwix, Robolectric 4.17, JUnit 4 + Truth).
- **Schema:** one library migration, 13 → 14.
- **Catalog:** one change, re-signed after `check-links`.
- **Build env:** `JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home ANDROID_HOME=/opt/homebrew/share/android-commandlinetools`.
- **Worktree:** `.claude/worktrees/gb` (branch `gb-gutenberg`, off main `4e1fbb5d`), with submodules and `local.properties` already set up.
- **Phone:** the Pixel is on Wi-Fi as `adb-21121FDF6007JN-gtbanj._adb-tls-connect._tcp`, and has `gutenberg_en_lcc-m_2026-03-05.zim` (3.99 GB, Music) installed. One app on the phone.

**Roadmap:** a new GB row in `docs/superpowers/plans/2026-10-15-granger-v1-roadmap.md`, before F1.

## What a Gutenberg pack holds (inspected 2026-10-30, `gutenberg_en_lcc-pf_2026-03.zim`)

- **Scraper:** `gutenberg2zim-3.0.1`. Metadata: Title "Project Gutenberg Library", Name `gutenberg_en_lcc-pf`, Description the subject ("West Germanic languages"), LongDescription a sentence about it.
- **One EPUB per book** at `{title}.{id}.epub` (application/epub+zip). The HTML page is `{title}.{id}`, with a cover page `{title}_cover.{id}`. Cover images are named `{id}_cover.jpg`, `{id}_{id}-cover.png` or `{id}_new-cover.jpg`; not every book has one.
- **`full_by_title.js`** holds `var json_data = [[title, author, "110", id, "LCC"], …]`: every book with its author and Gutenberg id. Author pages (`{author}.{authorId}`) and per-shelf/per-author JS lists sit beside it.
- A title can contain ":", "'", "(" and so on, so the EPUB path is built from the listed title exactly, then checked with `entry()`. If that misses, fall back to a `suggest()` on the id.

## What the code looks like now (surveyed 2026-10-30, main `4e1fbb5d`)

- **Packs:** `Pack.title` is the ZIM Title (`Entities.kt:36-48`, `KiwixZimArchive.kt:27-33`), so 39 of the catalog's 41 Gutenberg packs show "Project Gutenberg Library" in about 40 places.
  - `Filing.kt:13` files every `gutenberg_` pack in one collection, `c:gutenberg` "Gutenberg", inside the Kiwix folder (`folderKey`, :60).
  - Browse lists every installed pack by readiness domain (`BrowseViewModel.kt:63-100`), so Gutenberg packs land under Literature.
- **Books:** `Book` (`Entities.kt:132-155`) has id `book:<origin>:<ref>`, a real `filePath`, `coverPath`, format and `searchable`.
  - `BookOrigin` is GRANGER, IMPORT, ARCHIVE_ORG or PACK (`BookIds.kt`). `onShelf` = origin != PACK.
  - `Filing.BOOK_COLLECTIONS` (:63-67) maps an origin to its collection, and `defaultKey(book)` throws for an unmapped origin (:72).
  - The Library shows only collections that hold a book (`LibraryViewModel.kt:52-60`).
- **The nearest precedent:** a PDF inside a ZIM (Plan P2, `reader/PackDocuments.kt:41-85`).
  - It's copied out on open to `<books>/packs/<packId>/<sha1(path)>.pdf`, with a free-space check, `.incoming-` and rename.
  - It's registered as a hidden PACK-origin book keyed by the ZIM UUID (`Bookshelf.registerPackDocument`, PDF-only).
  - It's copied out again if missing (`restore`). The reader opens a `File` (`ReadiumBookOpener`, `EpubInspector` both need one).
- **ZIM access:** `ZimArchive` has `read`, `entry`, `copyTo` (a byte-range transfer when stored, else whole in memory), `suggest` and `search`, but no way to list entries. `ZimResolver.linkAction` sends only PDFs to the e-book reader, so an `.epub` link loads in the WebView.
- **Search and Ask:** `BookIndex.indexPending` indexes OK books with origin != PACK (`Daos.kt:236-238`). `BookTitleSearch` and `LibraryPassageFinder` see only `onShelf` books. No Gutenberg pack has a full-text index or vectors.
- **Get more:** `GET_MORE_SECTIONS` (`sources/GetMoreScreen.kt:35-39`): Knowledge packs = [KIWIX]; Books = [GRANGER_CLASSICS, ARCHIVE_ORG_BOOKS].
  - `CatalogScope.scopeOf` (`sources/CatalogScope.kt:11-19`) makes any kiwix.org pack KIWIX. `ReadinessModel` uses it too (:252, :265).
- **Catalog generation:** `catalog_gen.py` `pin()` (:137-171) uses the OPDS title unless a curated row overrides it. Bulk family rows can't override it. curated.json retitles lcc-b and lcc-pr only. Family `gutenberg` is in `kiwix_families.json:821-831` (domain literature).

## Decisions

1. **A Gutenberg pack is a book source, not a reading pack.**
   - It's recognised by catalog `family == "gutenberg"`, or failing that, a `Pack.name` starting `gutenberg_`.
   - The pack row stays in `packs`, because it's still the file on disk, the download and the storage. It's no longer shown in Browse or the Kiwix folder; its books are the way in.
   - The collection's overflow keeps "Open the pack's own pages" for the scraper's website.

2. **Names.**
   - **Catalog:** `catalog_gen.py` titles every `gutenberg` family row "{Subject} · Project Gutenberg", from the OPDS summary with its first letter capitalised. The curated lcc-b and lcc-pr follow the same pattern. The two everything-packs read "Every English book · Project Gutenberg" and "Every book, all languages · Project Gutenberg".
   - **Installed packs:** `Pack.from` gives a Gutenberg pack the same title, from its ZIM Description. Migration 13 → 14 rewrites existing rows (`name LIKE 'gutenberg\_%'` with a description), so the phone's Music pack is renamed without a re-download.
   - **The collection** is titled with the subject alone ("Music and books on music"), with "Project Gutenberg" as its subtitle line.

3. **Books are rows, copied out only when opened.**
   - A new `BookOrigin.GUTENBERG`, with id `book:gutenberg:<gutenbergId>`, so the same book from a newer snapshot, or from another subject pack, is one row.
   - `sourceRef` = `<pack name>/<epub path>`. The pack name, not the UUID, survives a snapshot update.
   - **On install and on refresh,** `GutenbergBooks.sync(pack)` reads `full_by_title.js` once and upserts a row per book: title, author, `filePath` = `<books>/gutenberg/<id>.epub` (not yet present), and format EPUB. Rows from a removed pack go, unless another installed pack still has them. Positions and marks are kept by id, so a reinstall picks up where you were.
   - **On open,** `EbookReaderViewModel`'s missing-file `restore` path (generalised from `PackDocuments.restore`) copies the EPUB out with the free-space check, `.incoming-` and rename, then inspects it for a cover and table of contents.
   - **Covers before the first open** come straight from the pack (the `{id}_cover.*` names above), read on demand by a small Coil fetcher and cached as thumbnails. Nothing is copied at sync.
   - A book whose EPUB isn't in the pack (rare) isn't listed.

4. **One collection per pack, at the top level.**
   - `Filing` gives each Gutenberg pack's books the collection `c:gutenberg-<lcc>` (from the pack name), titled per Decision 2. It's not in the Kiwix folder: it sits with Granger Classics and My books.
   - Migration 13 → 14 drops the old `c:gutenberg` collection's pack placements (packs no longer show there) unless the user moved them themselves (`userSet`).
   - `Filing.BOOK_COLLECTIONS` maps GUTENBERG through the pack, so `defaultKey` never throws.

5. **The collection screen** is the LV shared shelf, as the Classics have.
   - A–Z list by default, a grid, Title / Author / Recent sorts, and the letter strip.
   - **Header:** "Project Gutenberg · 1,284 books · 37 searchable in full", with an overflow of:
     - "Make all searchable…": copies every EPUB out and indexes it, after a dialog giving the size ("about 3.9 GB more"), and runs in the background;
     - "Open the pack's own pages";
     - Remove pack.
   - An `.epub` link inside the pack's HTML pages opens that book in the e-book reader (`ZimResolver.linkAction` learns EPUB), through the same book row.

6. **Searchable, and shown to be** (Neal's choice).
   - Titles and authors are searchable from the moment a pack syncs: GUTENBERG is `onShelf`, so `BookTitleSearch`, Library search and Ask's named-book cards see it.
   - **Full text** joins Search and Ask once the EPUB has been copied out. `BookIndex.indexPending` takes GUTENBERG rows whose file exists, and runs after the first open.
   - **Shown on the shelf:**
     - the collection header gives "n searchable in full";
     - each book row and grid cell carries a small "Full text" mark when it's indexed, with a TalkBack description;
     - the book's long-press sheet says "Searchable in full" or "Titles only until you open it".

7. **Get more.**
   - "Project Gutenberg" is a new link in Get more's Books section, after Granger Classics.
   - It opens a source screen for a new `CatalogScope.GUTENBERG`, listing the subject packs A–Z with their size and install state, and the two everything-packs last under "Everything", each with its size.
   - `scopeOf` puts the `gutenberg` family in GUTENBERG, so the Kiwix list no longer shows them.
   - `ReadinessModel` counts Gutenberg packs toward Literature as before.

## Global Constraints

- `./gradlew testDebugUnitTest :core:research:test` stays green after every task, and the count only grows. The baseline is 2520 (35 skipped).
- One library migration (13 → 14), with `MigrationTest` cases. One catalog change, re-signed after `check-links` passes.
- Never copy a whole pack's EPUBs except through "Make all searchable…".
- A position or bookmark in a Gutenberg book survives a pack update to a new snapshot.
- Commit after every task with the message given, ending with the Co-Authored-By line.
- One app on the phone.

## Review Focus

- Does the phone's Music pack appear in the Library as "Music and books on music", next to Granger Classics, with an A–Z list of its books and their covers?
- Does a tap open the book in the e-book reader, with chapters, read-aloud and a saved place?
- Do title search and Ask find a Gutenberg book by title at once, and by its text after it's been opened? Does the "Full text" mark appear?
- Are the Gutenberg packs gone from Browse and Kiwix's Get more, and listed under Books → Project Gutenberg?
- Does "Make all searchable…" give its size first, and can you leave it running?
- Does a sync of a pack with thousands of books stay off the main thread and finish in seconds?

---

### Task 1: Names

- [ ] Record the baseline in the commit message.
- [ ] `catalog_gen.py`:
  - the gutenberg family title rule (Decision 2), with tests in `test_catalog_gen.py`;
  - update curated.json for lcc-b and lcc-pr;
  - regenerate only the Gutenberg rows' titles in `catalog.json` (as the WikEM removal did, edit in place rather than a full rebuild);
  - `check-links`, then re-sign. Update `PublishedCatalogTest` if a count or title changes.
- [ ] `Pack.from` and migration 13 → 14's title rewrite. Tests in `RoomLibraryTest` and `MigrationTest`.
- [ ] **Commit.** `Gutenberg packs are named for their subject`

### Task 2: Books from a Gutenberg pack

- [ ] `BookOrigin.GUTENBERG`, `GutenbergBooks`:
  - `parse` of `full_by_title.js`;
  - the EPUB path and its fallback;
  - `sync(pack)` and removal;
  - the restore path that copies a book out on open (Decision 3).
- [ ] The cover fetcher.
- [ ] Wire sync into the pack install and refresh, off the main thread.
- [ ] Tests:
  - a `FakeZimArchive` Gutenberg pack fixture built from the inspected layout;
  - parse, including quotes and colons in titles;
  - sync upsert and removal, with a book shared by two packs;
  - a position surviving a new snapshot;
  - open copies out once;
  - a missing EPUB isn't listed;
  - 5,000 rows sync in under 2 s on the JVM.
- [ ] **Commit.** `Gutenberg packs: their books become shelf rows, copied out when opened`

### Task 3: The collection

- [ ] Filing and the migration's placement change (Decision 4).
- [ ] The collection header, the overflow and "Make all searchable…" (Decision 5).
- [ ] The "Full text" marks (Decision 6).
- [ ] The pack hidden from Browse and the Kiwix folder.
- [ ] EPUB links in `ZimResolver`.
- [ ] Tests:
  - Library shows the collection beside the Classics;
  - Browse no longer lists the pack;
  - the header counts;
  - the marks;
  - "Make all searchable…" shows the size and queues the work;
  - an `.epub` link opens the reader.
- [ ] ThemeShots: the collection (list and grid, a few books marked), 360 dp, Field and Night. Review the renders.
- [ ] **Commit.** `Gutenberg collections sit beside the Classics, with what's searchable shown`

### Task 4: Search and Ask

- [ ] GUTENBERG is `onShelf`; `indexPending` takes rows whose file exists; indexing runs after the first open.
- [ ] Tests in `BookTitleSearchTest`, `BookIndexTest` and `LibraryPassageFinderTest`:
  - found by title before opening;
  - found by text only after.
- [ ] **Commit.** `Gutenberg books: titles searchable at once, full text once opened`

### Task 5: Get more

- [ ] `CatalogScope.GUTENBERG`, the Get more link and the source screen (Decision 7).
- [ ] Tests in `CatalogScopeTest`, `SourceViewModelTest`, `GetMoreScreenTest` and `ReadinessModelTest`.
- [ ] Full suite, then build.
- [ ] **Commit.** `Get more: Project Gutenberg under Books`

### Task 6: Walkthrough (Neal, Pixel 6)

- [ ] Install over `app.granger`. The Music pack is already on the phone, which is the real test of the migration and sync.
- [ ] Neal checks:
  - the Library shows "Music and books on music" beside the Classics;
  - its A–Z list and covers;
  - open a book: the reader, chapters, read-aloud, leaving and coming back to the same place;
  - title search finds an unopened book, and text search finds an opened one, whose "Full text" mark shows;
  - the collection header's count;
  - "Make all searchable…" dialog (cancel or run);
  - Browse no longer lists the pack;
  - Get more → Books → Project Gutenberg lists the subjects with sizes, and Kiwix's list doesn't.
- [ ] Fix what the walkthrough finds. Record the result in the roadmap's GB row and a "Carried forward from GB" section.
- [ ] Merge and push only on Neal's say-so.
