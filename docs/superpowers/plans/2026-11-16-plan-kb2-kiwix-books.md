# Plan KB2: Kiwix books, round 2 — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** every Kiwix pack that is really a container of books shows as a book collection. One mechanism, the book
manifest, covers the scraped-site packs KB left out. African Storybook arrives as per-language sets. Books from every
source behave the same: one Books screen, one vocabulary, one PDF rule, places that survive updates. The upkeep loop
keeps it current.

**Design:** `docs/superpowers/specs/2026-11-16-kiwix-books-design.md` (the audit, §3 manifests, §4 UX, §5 upkeep, §6
decisions). Audit data: `docs/superpowers/spikes/2026-11-16-kiwix-books-audit/`.

**Status:** decisions settled 2026-11-16 (design §6):
- round one is African Storybook, Military Medicine, SMBC, Algorithms, Milne and Survivor Library;
- African Storybook ships as per-language sets;
- "Read as ebook" stays a choice;
- the Books screen is organised by kind;
- the new collections are counted in Readiness.

**Coordination with the LL session (granger-d0, notes 2026-11-16):**
- Don't touch: app `reader/*` (ReaderScreen, ReaderViewModel, ArticleFooterViews, ReaderWebView,
  PackDocuments-adjacent reader code), `Features.kt`, QidIndexes, TimeIndex, MentionIndex, ReferenceLinksSource,
  `VectorFile.kt`, `EbookReaderViewModel`, `ebook/ReferenceLinks*`, `app/src/debug`.
- §4.9 small print: **granger-d0 will fix both** (`ebook_book_gone` in en and es, and the ReaderControls KDoc) on
  ll-linked-library, and will say when they land (d0, 2026-11-16).
- The "Ebook ready" chip: message granger-d0 before adding it. The e-book reader is where LL1's links live.
- **Migration 15 → 16 is KB2's if needed** (d0: article_marks moved to LL3, weeks out). Still message before taking it.
- **Joining the Linked Library** (d0):
  - Related keys books by their `.gvec`'s target, the book id, and drops chapters named cover, colophon, titlepage,
    toc, nav, imprint or copyright.
  - "In your books" reads the work's QID from the book's metadata.
  - So a new book needs a `.gvec` and, for mentions, a `.glink`, both keyed by the same id.
  - Keep the African Storybook EPUB chapters named in that convention.
- New catalog fields leave room for LL's planned "which subject packs contain which Wikidata items" lookup. Manifests
  carry an optional `qid` per item.
- If both branches change `catalog.json`, whoever merges second rebases, reruns `check-links`, and Neal signs again.

**Tech Stack:** unchanged (Kotlin 2.4.21, Compose M3, Room, Readium, libkiwix 2.6.0, Robolectric, JUnit 4 + Truth;
Python for tools).
- **Venvs:** `~/.venvs/granger-catalog` (probe, catalog), `~/.venvs/granger-pack` (EPUB building).
- **Build env:** `JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home ANDROID_HOME=/opt/homebrew/share/android-commandlinetools`.
- **Worktree:** `tools/dev/new-worktree.sh kb2 kb2-kiwix-books`, off main.
- **Phone:** Pixel 6 is shared.
  - Check `dumpsys package app.granger | grep lastUpdateTime` as its own step first.
  - Never install below the phone's DB version.
  - Never run `connectedDebugAndroidTest`; never `adb reboot`.

## Phase 0: proof (no app changes)

- [x] **Task 0.1: verify the survey's UX findings.** Confirm or drop each item in design §4 against main, with a test or a
  file:line. #4.4 (the nautilus Read-as-ebook restore bug) is already confirmed. Write a failing test for it now.
  - **Done 2026-11-16** (main 33a4f60b). Paths under `app/src/main` and `core/library/src/main` unless given.
  - 4.1 split by source: **CONFIRMED**. `sources/GetMoreScreen.kt:36-39` (Classics, Gutenberg, Kiwix libraries,
    Archive.org books as separate rows).
  - 4.2 vocabulary: **CONFIRMED**. "Kiwix libraries" `strings_sources.xml:29,50`; "Kiwix library · N documents"
    `CollectionViewModel.kt:255-263`, `strings_library.xml:22,28-31`; "books" in the Make-searchable dialog
    `strings_library.xml:78-82`.
  - 4.3 Open/Remove:
    - Classics have no Open: **CONFIRMED**. `SourceScreen.kt:681,719-725` (Open only when `row.packId != null`).
    - archive.org texts have no Open: **CONFIRMED**. `ArchiveScreen.kt:428` (Installed → Remove only; Open is only
      for `InLibrary` audio, :433).
    - Downloads → Open on a book pack shows web pages: **CONFIRMED**. `GrangerNavHost.kt:1554` uses `openPack`;
      Get more uses `openPackOrCollection` (:1296, :793).
    - `sources_remove_pack_body` says places are deleted: **PARTLY**. It's shown for every pack in Get more
      (`SourceScreen.kt:339`, `strings_sources.xml:108`). It's true for a web pack's article bookmarks and places
      (`RoomLibrary.kt:125-126`). It's wrong for a book pack, whose books' places and marks stay (`PackBooks.kt:85`),
      as the Library's own dialog says (`strings_library.xml:65`, `CollectionScreen.kt:325`).
    - Removed pack books leave "Removed book" tabs: **CONFIRMED**. `PackBooks.sync` deletes gone rows without their
      tabs (`PackBooks.kt:137-149`); the rail shows `REMOVED_BOOK` (`shell/RailViewModel.kt:313`).
  - 4.4 one PDF rule:
    - archive.org converts at install: **CONFIRMED** `Bookshelf.kt:152`.
    - imports and nautilus offer Read as ebook, pack documents can't: **CONFIRMED**. `readAsEbook` needs `onShelf`
      (`Bookshelf.kt:253`), false for PACK and PAGES (`BookIds.kt:46`); the menu is the same test
      (`ebook/EbookReaderViewModel.kt:315`).
    - nautilus restore bug: **CONFIRMED by test**. `readAsEbook` re-registers the row as EPUB at `x.pdf.epub`
      (`Bookshelf.kt:255,267`). `PackBooks.restore` then copies the PDF entry to that path and the EPUB check fails
      (`PackBooks.kt:166-193`). The reader then says `ebook_book_gone` (`EbookReaderViewModel.kt:274`). `sync`
      deletes only `filePath` (`PackBooks.kt:146`), so the `.pdf` and the PAGES row stay.
    - Tests: `NautilusBooksTest.aNautilusPdfReadAsAnEbookComesBackAfterItsCopyIsGone` and
      `aRemovedPackTakesAPdfReadAsAnEbookWithItsPages`, `@Ignore("KB2 Task 2.5: …")`. Both fail when un-ignored, at
      `restore(...)` false and at the PAGES row still there.
  - 4.5 pack documents keyed by UUID, lost on supersede: **CONFIRMED**. `ref = "$zimId/$path"` with the pack id
    (`Bookshelf.kt:461`). `supersede` calls `onRemoved(oldPackId)` (`RoomLibrary.kt:168`), which is
    `removePackDocuments` (`GrangerApp.kt:357`): place, marks, tabs and history go (`Bookshelf.kt:470-486`).
  - 4.6 search: **PARTLY**.
    - Book-pack ZIMs are searched like any pack (`LibrarySearch.kt:229`, no `isBookPack` filter), so full-text hits
      from them are article hits (`LibrarySearch.kt:175-185`).
    - Title search already folds a book and an article of the same title into one row (Plan SQ `oneRowPerTitle`,
      `LibrarySearch.kt:100-103`, cdf5940e). The doubling is mainly a full-text problem, and a title-row problem only
      where the titles differ.
    - "Searchable in full" is shown: **CONFIRMED** `CollectionViewModel.kt:267`.
  - 4.7 Files in this pack: a new feature, nothing to check. `ZimArchive` has no listing until Task 0.2.
  - 4.8 Gutenberg filed under one pack: **CONFIRMED**. `_all` packs sort last (`GutenbergBooks.kt:89`). A book is
    one row filed with the first pack holding it (`PackBooks.kt:107-112`). Its collection comes from that one pack
    name (`Filing.kt:151`).
  - 4.9 small print:
    - `ebook_book_gone` "download it again from Granger": **CONFIRMED** `strings_ebook.xml:112`.
    - ReaderControls' "found from the library index" is untrue for pack documents: **CONFIRMED**. `ReaderControls.kt:24`;
      PACK rows are never indexed (`core/library/.../Daos.kt:255`).
    - Both are d0's to fix.
- [ ] **Task 0.2: `ZimArchive.list(mimes)`.**
  - Wrap libkiwix `iterByPath()` in `KiwixZimArchive`: path, title, mime and size for entries of the given mimes,
    streamed.
  - Test against `testdata` ZIMs.
  - Time it on the phone over a large pack (Gutenberg en_all, 60k EPUBs) and record it. This is the fallback's cost.
  - **Code done 2026-11-16; phone timing still to do.**
    - API: `ZimArchive.list(mimes: Set<String>, each: (ZimEntry) -> Boolean): Boolean`.
      - It's a callback: nothing is gathered, and the native `EntryIterator` is disposed in `finally` under the read
        lock. A lazy Sequence would keep the iterator and the lock open between steps, and leave an abandoned
        iterator to a finalizer (the R5 dispose crash).
      - `each` returning false stops the walk.
      - It returns false for "can't list": the interface default (so fakes compile), a closed archive, or a native
        error.
    - `KiwixZimArchive`: skips redirects, takes the mime from `getItem(false)` and reads `size` only for matches.
    - Tests:
      - JVM `ZimArchiveListTest` covers the default.
      - The device test `KiwixZimArchiveTest.listsTheEntriesOfTheGivenTypesInPathOrder` checks `granger-test.zim`'s
        PDF and PNG in path order, the redirect left out, stop-early and after-close. It compiles; it wasn't run (no
        instrumented runs).
    - **Still to do (Neal's phone):** run that device test, and time `list(setOf("application/epub+zip"))` over
      Gutenberg en_all (60k EPUBs).
- [x] **Task 0.3: extractor spike on the Mac.** Write `tools/bookpacks/build.py` with `kolibri` and `zimit-index`
  extractors, over `zim_probe.Reader`.
  - Build manifests for African Storybook, Military Medicine, SMBC, Algorithms, Milne and Survivor Library (its 194
    category pages give the groups).
  - Grade titles by hand on 30 random items per pack.
  - **Pass:** 95% or more readable titles after overrides, 0 wrong languages in African Storybook, and parts folded
    for Military Medicine's "Full text / Part 1–3".
  - Record the result in the plan. **Stop and report if a shape fails.**
  - **Result (2026-11-16): PASS on all six.** The samples are in
    `docs/superpowers/spikes/2026-11-16-kiwix-books-audit/grading.txt`. The settings are in
    `tools/bookpacks/book_packs.json`.

    | Pack | Items | Readable | Build | Title sources |
    |---|---|---|---|---|
    | Military Medicine | 24 | 24/24 (whole pack) | 1 MB fetched | 18 link, 6 PDF Info |
    | SMBC | 9 | 9/9 (whole pack) | 1.3 MB | 8 heading, 1 override |
    | Milne Open Textbooks | 15 | 15/15 (whole pack) | 2 MB | 15 page, via one-hop links |
    | Algorithms | 696 | 30/30 sampled | 0.4 MB | 644 path rules, 49 link, 2 overrides |
    | Survivor Library | 11,802 | 30/30 sampled | 3.7 MB, 25 s | 11,793 table row, 168 groups |
    | African Storybook | 5,165 | 30/30 sampled | 2.5 MB | 5,165 page |

    - **African Storybook:** 693 English books (1.1 GB) and 427 Kiswahili (0.7 GB), in levels 1–5. Every book has an
      author and "CC BY by African Storybook Initiative". Languages were spot-checked on 10 recognisable titles across
      af, en, sw, pt and zu: 0 wrong.
    - **Parts:** Military Medicine's Hospital Corpsman and Special Forces Medical Handbook each fold into one `work`
      with parts 1–3 in order. The pack has no whole-book file for either.
    - **Milne:** Milne also links 11 files under 200 KB (`minBytes`). Its textbook and lab manual on one page
      ("X + Lab Manual") are told apart by each one's link ("X", "X · Lab manual").
  - **What the spike settled for Phase 1:**
    - Titles need a ranked mix:
      1. override;
      2. path rule;
      3. specific link text;
      4. the table row or list item around a generic link, tidied (no date-added column or sizes,
         `Title_With_Underscores_1785` becomes "Title With Underscores (1785)");
      5. the title of a page serving one file;
      6. a fallback path rule;
      7. the PDF Info/XMP title (only the document dictionary, not outline or structure `/Title`s);
      8. heading plus qualifier;
      9. the file name.
    - Generic and boilerplate link texts are filtered: "Download PDF (13MB)", "please click here to start it
      manually", "Download Page".
    - A file served by a page of its own is named by the links that lead to that page (one hop back).
    - Keys keep the query parameters that name a file (`viewcontent.cgi?article=1028`) and drop the ones that change
      between snapshots (`tmstv`, nonces, `last`).
    - PDF bytes are read only when the pages give no title, at most `pdfProbeMax` (3,000) per pack. The first
      Survivor run hit the 1 GB fetch budget before the row-context fix. So `pdfKind` is mostly "unknown" in these
      manifests: the phone's `PdfOrigin` decides, which Decision 3 makes enough.
    - **Algorithms is pack-specific work:** 650 of its 696 files are homework, labs and exams, named only by
      their paths. The path rules in `book_packs.json` turn `hwex/f24/hw/f24-hw2.pdf` into "Fall 2024 · Homework 2".
      Course files outnumber the book 643 to 53, so the collection screen should fold "Course materials".

## Phase 1: manifests and upkeep (tools)

- [x] **Task 1.1: format + check.** Done 3b29d60f.
  - **The sidecar is SQLite, not JSON.** The sidecar contract (`tools/sidecar/meta.py`) is a read-only SQLite file
    with a `meta` table, so `.gbooks` keeps it and the catalog, hold-back and upkeep take it as they take `.gqid`.
    Tables: `meta`, `collection`, `groups`, `items` (`tools/bookpacks/schema.md`).
  - `build.py` writes `.gbooks` (or `.json` for review), and reads a previous one to keep the keys of moved files.
  - `check.py` exits 1 on a problem (meta contract, empty title or bad format, a path the ZIM lacks) and 2 on drift
    (books lost, count >10%, file-name titles >5% and rising).
  - Fixture ZIMs come from `tools/test-zim/make_bookpacks_zim.py` (a scraped site in every shape the audit met, and a
    Kolibri pack); 29 tests.
  - The tests found and fixed: single words counted as generic link text; language names; brackets inside PDF
    strings; averaged sizes for files sharing a cluster; hyphens in dates and volume ranges.
- [x] **Task 1.2: `book_packs.json`.** Done.
  - The six round-one packs.
  - `shelves`: prefixes for list-carrying packs: `gutenberg_` → literature, `zimgit-` and `usda-` → manuals,
    `prunelle_` → children.
  - `notBooks`: the audit's reviewed sites, scores and later passes, with reasons.
- [x] **Task 1.3: sidecar kind `gbooks`.** Done d4ad0ae8.
  - `kinds.json` has the build and check commands, and `gpu: false`.
  - `build.py auto <zim>` names the pack from its file.
  - `--catalog` gives the source sha256 and the published `.gbooks` as the previous manifest.
  - `meta.py release --url` handles a sidecar in a release of its own (as `.glink` files are): `bookpacks-<date>`.
  - `attach_sidecars` and the hold-back rule needed no change.
- [x] **Task 1.4: catalog `shelf`.** Done d4ad0ae8. **Changed from the draft:** `content` stays as it is.
  - Today's app (in testers' hands) lists `content: "books"` packs under Books but opens a non-Gutenberg,
    non-nautilus pack as a website, so marking the new packs would misfile them.
  - **The new app knows a manifest pack by its `gbooks` sidecar** (Task 2.1). Old apps drop sidecar kinds they don't
    know (`SidecarEntry.KNOWN`).
  - A new `shelf` key, which old apps ignore, comes from `book_packs.json` and is stamped by `catalog_gen.py shelves`.
    It's set on 56 packs: 43 literature, 8 manuals, 3 children, 2 learning.
  - African Storybook isn't listed (Decision 2: sets).
- [x] **Task 1.5: upkeep findings.** Done 89ad6def.
  - `bookpack-drift`: a pack held for its `gbooks` is rebuilt in memory and checked against the published manifest.
  - `bookpack-candidate`: new packs and new snapshots are probed and judged by the audit's Counter rule.
  - Hold records now carry `toUrl` and `toSha256`.
  - Both playbooks are in the README; the gbooks rebuild needs no ZIM download.
  - Smoke-tested live: SMBC is judged a book bundle, and a Military Medicine dry rebuild gives 24 items with no drift.
- [x] **Task 1.6: build, check, release prep.**
  - [x] Built and checked all five catalog packs' `.gbooks` from their pinned files (exit 0), into
    `~/Developer/Granger/vectors-work/bookpacks-2026-11-16/`, 4.6 MB in all (Survivor 4.25 MB).
  - [x] Wrote the descriptors (`tools/sidecar/releases/*.gbooks.json`, release `bookpacks-2026-11-16`) and attached
    them to the catalog with the shelves, unsigned. A semantic diff shows only the 5 `gbooks` sidecars, the 56
    `shelf` keys and `generatedAt`.
  - [x] Wrote the upload script: `tools/bookpacks/upload_release.sh`, resumable, 10 per batch.
  - [x] Neal uploaded release `bookpacks-2026-11-16` (2026-11-16). All five downloads match their descriptors'
    sha256.
  - [x] `check-links`: all 3,226 links answer. Neal signed; `verify` passes, and `:core:catalog` gives 76 tests and
    0 failures.

## Phase 2: the phone reads manifests

- [x] **Task 2.1: `ManifestSource` over `PackBooks`.**
  - A new `BookOrigin.KIWIX` (`book:kiwix:<zimName>/<key>`), `isPackBook`.
  - Download the `.gbooks` with its pack, as `.gqid` files are.
  - `Pack.bookSource` is KIWIX when the pack has a `gbooks` sidecar (catalog) or a downloaded `.gbooks` file: not
    `content`, which stays blank for these packs (Task 1.4). Add `"gbooks" to 1` to `SidecarEntry.KNOWN`.
  - Rows carry group, order, level, language and parts. Restore picks the inspector by format.
  - Tests: fixture manifest, parts folded, snapshot change keeps ids, fallback when the manifest is missing.
  - **Done 2026-11-16** (this commit).
    - Catalog: `gbooks` in `KNOWN` (`SidecarEntry.GBOOKS`); `PackEntry.isBookPack` is `content == "books"` or a
      `gbooks` sidecar, so the five packs move from the Kiwix web list to Kiwix libraries (scope `KIWIX_BOOKS`) and
      Readiness still counts them (`PACK_SCOPES`). Downloads: `SidecarKind.BOOK_MANIFEST` (`sidecars/<id>_<sha12>.gbooks`),
      always wanted; `Sidecars.reconcile` fetches and removes it with its pack.
    - **Migration 15 → 16** (`MIGRATION_15_16`, schema `16.json`): `packs.bookManifest TEXT` (the verified `.gbooks`
      path, `"files"` = `Pack.BOOK_FILES`, or null) and, on `books`, `groupName TEXT`, `ord INTEGER`, `workKey TEXT`,
      `workTitle TEXT`, `partNo INTEGER`. All nullable.
    - `Pack.bookSource` → KIWIX when `bookManifest != null`, after the gutenberg/nautilus scraper rules.
      `RoomLibrary.refresh` keeps `bookManifest` for the same ZIM UUID (the file doesn't carry it; without this every
      refresh would null it and flap the collection).
    - **Who sets the column:** `BookManifests.attach(installed)` (core/library), driven by a new
      `AppContainer.followBookManifests()` that watches the gbooks download records and the packs (id, status, path)
      and passes `installedSidecars(BOOK_MANIFEST)`. A pack gets the path only when the file's `meta` is kind `gbooks`,
      schema 1 and `sourceSha256` equals the pack's download hash (spec §5.2; cached per file). Gutenberg/nautilus packs
      are cleared; a MISSING/CORRUPT pack keeps its value, so its books stay while the card is out.
    - `ManifestSource`/`ManifestBooks` (core/library `ManifestBooks.kt`): one row per item, ref `<pack name>/<key>`,
      `sourceRef` `<pack name>/<path>` (the path keeps its query; `packNameOf`/`restore` split at the first `/`, which
      pack names never contain). Items are trusted from the verified manifest without a per-entry lookup (Survivor has
      11,802). Language: the item's, else the row's, else the pack's. Copies go to `books/kiwix/<sha1(ref)>.pdf|.epub`;
      restore uses `PackBooks.restore` unchanged (inspector by format, PDF first-page cover). `PackBooks.held` now
      caches per pack id **and** manifest, and `follow()` re-syncs when `bookManifest` changes.
    - **Parts (decision):** one Book row per file. `workKey` = the manifest's `work_key`, or `part_of` (the whole's
      key) for parts of a whole file, and the whole file gets its own key as `workKey`, so the collection screen folds
      rows by `workKey` (whole = the row whose key equals it).
    - Filing: `kiwixbooks-<pack name>` like nautilus, named by the manifest's `collection.title`
      (`BookManifest.titleOf`, cached per file), else the pack title. Out of Browse and the Kiwix folder via
      `isBookPack` (verified in a test: `Filing.folderKey` null).
    - GrangerApp: `manifestBooks` in `PackBookLinks`, both restore chains, and `follow(manifestBooks, "Kiwix books")`.
      Link taps resolve by exact path, then key, then key without query (`BookManifest.keyOf`).
    - Task 2.2's fallback code (`BookFiles`, the `"files"` branch, `ZimMetadata.counter`) landed in this commit too,
      since `attach` decides both; Task 2.2 adds its header and tests.
    - Known gap: a new snapshot is not KIWIX until its own manifest downloads, so for that moment its rows go (places
      and marks stay; the same keys bring them back). And a pure container that gets a manifest later changes keys
      (path → manifest key).
    - Tests: `ManifestBooksTest` (12: rows/titles/groups/order/parts, Kolibri EPUBs + languages + restore, wrong sha
      ignored, manifest gone, new snapshot keeps ids and place, removal, missing pack, restore with `?tmstv=…`, link
      taps, gutenberg left alone, bookSource rules), `MigrationTest` 15→16, `RoomLibraryTest` keep-on-refresh,
      `SidecarsTimeIndexTest.aBookManifestFollowsItsPack`, `CatalogParserTest`, `CatalogScopeTest`, `ReadinessModelTest`;
      `PublishedCatalogTest` and `SourceViewModelTest` counts 50→55 / 9→14 book packs.
    - Suites: `:core:catalog` 77, `:core:downloads` 158, `:core:library` 491, `:core:zim` 31, `:app` 1799 (112 skipped),
      0 failures.
- [x] **Task 2.2: fallback.**
  - When a pack looks like a book bundle (`content: "books"` or the audit's rules on its Counter) but has no manifest,
    list it with `ZimArchive.list`, titled by file name.
  - The header says "Titles from file names".
  - **Done 2026-11-16** (this commit; the code came with Task 2.1's, as `BookManifests.attach` decides both).
    - The rule is the Counter's alone, on the phone: `ZimMetadata.counter` (libkiwix `Counter`), at least 3
      PDF/EPUB entries and **at most 10** `text/html*` entries (`BookFiles.isContainer`). Not loosened: the World
      Factbook (839 PDFs, 830 pages) stays a web pack (tested). `content: "books"` isn't used: every such pack today is
      nautilus or Gutenberg, with lists of their own. Read once per snapshot (cached by pack id); a pack that can't be
      opened keeps what its row says.
    - Such a pack gets `bookManifest = "files"`: `ManifestSource` walks `ZimArchive.list({pdf, epub})` in path order,
      key = the entry path, title = the file name without query or extension, `%xx` decoded (a `+` stays), underscores
      as spaces (`BookFiles.titleOf`). No sections; `ord` is the walk order. A verified manifest takes over when it
      arrives (keys change from paths to manifest keys).
    - The collection header adds "Titles from file names" (`library_titles_from_file_names`, en + es "Títulos tomados
      de los nombres de archivo"), as a third line under the provenance; the collection is named by the pack title.
    - Tests: `ManifestBooksTest` +4 (a container listed by names, link tap, restore, a manifest taking over; Factbook
      and too-few-documents stay websites, Counter parsing; a pack that can't be opened keeps "files"; file-name
      titles), `CollectionViewModelTest.aCollectionListedByFileNamesSaysSo`. `:core:library` 495, `:app` 1800 (112
      skipped), 0 failures.
- [x] **Task 2.3: groups in the collection screen.**
  - Section headers from manifest groups (Level 1–5; Handbooks; The book / Course materials).
  - A language chip when a collection holds more than one language.
  - The ThemeShots get the new states.
  - **Done 2026-11-16** (this commit).
    - **When the collection's own order shows (decision):** a manifest collection (its rows carry `ord`) with the
      Title sort (the default) and no Find text. Author, Recent or a Find query list the books flat as before, parts
      as separate rows. So the Title chip means "the collection's order" here; within a section books follow the
      manifest's `ord`, not A–Z. The sort is a shared preference, and a fourth chip for one kind of collection seemed
      worse.
    - `sectionRows` (app `library/CollectionSections.kt`, pure): books with an empty group first with no header, then
      each group under a heading in the manifest's `groups` order (a group it doesn't name follows, by its first book).
      A section of more than 50 rows (a work counts once) folds behind a button header "Course materials · 643";
      Algorithms' 643 course files sit folded ahead of the book's chapters, as the manifest orders its groups.
    - **Parts (decision):** a work is one row ("Hospital Corpsman", author, "3 parts", an arrow). A tap shows its files
      indented beneath it (the whole file first, when the pack has one, then the parts by `part_no`); another tap hides
      them. Expanding is the one pattern for both shapes (whole + parts, parts only) and never opens a part the reader
      didn't choose. A "work" of one file is just that book. The grid does the same with a cover cell.
    - Language chips ("All", then each language's name via `KiwixList.languageName`, so `eng` and `en` are one
      "English") when a manifest collection holds more than one; they filter both layouts.
    - Header: "<provider> · N <noun>" from the manifest (`ManifestCollection`, read off the main thread by
      `CollectionViewModel.manifest`, `manifestCollectionOf`): `library_books_counted` or `library_documents`
      (existing plurals, en + es), a work counted once; "Kiwix library" when the manifest names no provider.
    - Open sections, open works and the chosen language live in `SavedStateHandle`.
    - TalkBack: section names are headings; a folded header is a `Role.Button` heading with `stateDescription`
      Expanded/Collapsed and an Expand/Collapse click label; a work row is a button with the same state. New strings
      `library_expanded`, `library_collapsed`, plurals `library_parts` (en + es "Expandido", "Contraído", "N partes").
    - ThemeShots: `kb2-sections` (list: ungrouped books, Handbooks with a parts row, language chips), `kb2-parts` (the
      work open), `kb2-sections-grid`, `kb2-folded` (Algorithms: "Course materials · 643" folded above "The book").
      Rendered and checked by eye in Night and Field.
    - Tests: `CollectionSectionsTest` (4: order and unnamed groups, folding at 50, parts with no whole, a whole heading
      its parts), `CollectionViewModelTest` +2 (sections, parts, folding, language filter, header provider/noun/count,
      Find going flat; other collections unchanged).
    - Suites: `:core:catalog` 77, `:core:downloads` 158, `:core:library` 495, `:core:zim` 31, `:app` 1814 (ThemeShots
      skipped without `-PthemeShots`), 0 failures; `:app:assembleDebug` builds.
- [x] **Task 2.4: pack documents keyed by name (design §4.5).**
  - `book:pack:<zimName>/<path>`.
  - Adopt old UUID rows with place, marks, tabs and history (the KB Decision 5 pattern).
  - Supersede no longer deletes them.
  - Migration 16 if a column is needed; tell granger-d0 first.
  - **Done 2026-11-16** (this commit). No migration. `registerPackDocument` keys by the pack's name (UUID when it has
    none) and drops a stale copy when reopened from a new snapshot. `RoomLibrary.onRemoved` now passes the `Pack`;
    `removePackDocuments(id, name)` keeps (and files by name) the documents when another pack of that name is
    installed, else removes as before. UUID rows are adopted on `refresh`, on open and on supersede via a shared
    `handOverBook` (also used by `NautilusBooks.adopt`, which now adopts name-keyed rows too). New
    `Bookshelf.packDocumentSource` finds the installed snapshot for `PackDocuments.restore` (one-line change there).
    A cross-name flavour switch still removes them. 6 new tests. `:core:library` 477/0 fail, `:app` 1797/0 fail
    (112 skipped).
- [x] **Task 2.5: fix the nautilus Read-as-ebook bug (design §4.4).**
  - `restore` copies to the PDF path and re-runs the conversion when the row's EPUB is gone.
  - Sync removes the `.pdf`, the `.pdf.epub` and the PAGES row together.
  - The Task 0.1 test now passes.
  - **Done 2026-11-16** (this commit). Generic in `PackBooks`, so Gutenberg too. New `PdfEbooks` (one conversion for
    `readAsEbook` and `restore`). `restore` of an `x.pdf.epub` row copies the entry to `x.pdf` (or uses the PDF still
    there) and makes the ebook again, place and marks kept; if it no longer passes, the row falls back to the PDF and
    the pages' place and marks come back to it. `sync` drops the `.pdf` and the PAGES row (its place and marks stay,
    as a pack book's do). Both Task 0.1 tests un-ignored, 2 new. `:core:library` 471/0 fail, `:app` 1797/0 fail
    (112 skipped).

- **Carried to Phase 3's catalog change** (found in the Task 2.3 screenshots, 2026-11-16):
  - The builder ordered groups A–Z, which put Algorithms' "Course materials" above "The book" and sorted chapters by
    title. Configured groups now keep their order, and `"order": "path"` keeps the site's file order (Algorithms
    uses it); the fix is in the builder, with a test.
  - **Rebuild `jeffe.cs.illinois.edu_en_all`'s `.gbooks` and upload it with the African Storybook release**, so Neal
    uploads and signs once.
  - Also: after a pack is removed and reinstalled, a book once read as an ebook comes back as a PDF, while its kept
    place is an EPUB locator (Task 2.5 edge). Handle this when Phase 4 touches PDF rows.

- **Phase 2 emulator check, 2026-11-16: PASS.** Neal chose the emulator over the shared Pixel 6, whose library was
  at database 15 with granger-d0's build 1374; ours migrates to 16, which would have blocked d0's installs.
  - Setup: debug 1375 on `emulator-5580`, built with `-PcatalogUrl=http://localhost:8765/catalog.json`. The branch's
    signed catalog was served by `python3 -m http.server --directory catalog` with `adb reverse tcp:8765 tcp:8765`.
  - Database 15 → 16 migrated with the library intact.
  - Get more → Kiwix libraries lists Military Medicine, SMBC and Algorithms. Get queued them behind 77 Classics
    `.glink` downloads.
  - Each pack's `.gbooks` installed with it.
  - Library: "Military Medicine · Kiwix library · 24 documents" and "SMBC COVID-19 Book Pack · 9 documents". The row
    wording is Task 4.2's.
  - Collection: real titles, header "Federation of American Scientists · 20 books" (works counted once), and "US
    Army Special Forces Medical Handbook · 3 parts" as one row.
  - Emergency Childbirth copied out on first open and opened at p. 1 of 13, with its first-page cover on the rail.
    "Read as ebook" is in the menu.
  - Browse no longer lists either pack.
  - The emulator is now at database 16 (d0 told).

## Phase 3: African Storybook (D2: per-language sets)

- [ ] **Task 3.1: the set builder.**
  - `tools/pack-builder/africanstorybook.py`, from the Task 0.3 manifest.
  - Pull each language's EPUBs by range read and run epubcheck.
  - Add a colophon with CC BY attribution, the author and the ASB link.
  - Make covers from the EPUB's own.
  - Ids are `asb-<lang>-<node>`.
  - Write release manifests for en and sw, with per-level sets.
- [ ] **Task 3.2: catalog.**
  - Catalog `books` entries.
  - Presets: "African Storybook · English" (all levels), with Level 1–5 sets.
  - Spanish copy for titles and levels; book text stays in its own language.
  - Children's shelf.
  - `check-links`, then **Neal uploads and signs.**
- [ ] **Task 3.3: upkeep.**
  - An `asb-snapshot` finding (the ZIM's `Date` changed) and a `rebuild-release` playbook: rebuild, diff ids, and
    report lost and added books.
  - Neal uploads.

## Phase 4: one model for books (design §4)

**Keep in step with Explore** (Neal, 2026-11-16: "keep this design in-step to the direction that Browse has taken on
in the other session (an explore of topics launch point, with actual kiwix pack library browsing 1 additional tap in
now)").
- **Source:** granger-d0's plan `docs/superpowers/plans/2026-11-16-plan-ex-explore.md` on `ll-linked-library`
  (075f3266, unmerged; no code yet, and its Task 0 subjects spike gates the UI). Mockups are the second row of canvas
  HG4yrYyYPFssmRvsYscdvq (ExploreA, ExploreSubject, ExploreB, ExploreMap, ExploreSources). Re-read both before
  starting Phase 4; they may have moved on.
- **Explore's shape:**
  - The rail's Browse becomes **Explore** (compass icon; RailTool keeps its id).
  - The landing shows the title with a **+ (Get more)**, a summary line, "Start with a subject" (a 2-column grid of
    subject doors), "Through time", and "Your sources" (one line, plus "All N packs").
  - **Your sources** is the old Browse list grouped by domain: Encyclopedias, Health, Cooking & food, Skills, Books.
    Each row is a title, a meta line (language · size) and a chevron. Its top bar has + (Get more) and an overflow
    menu (Downloads, Readiness, Import). Its **Books** group is one row that goes to the Library.
  - Book collections reach Explore through a Books door and "In your Classics" on a subject screen, by QID or
    subject.
- **What Phase 4 adopts:**
  - **Words:**
    - "pack" for a ZIM or a download;
    - "Your sources" for the installed packs;
    - "subject" for a door;
    - "Get more" for the catalog.
    - Explore avoids the word "collection". So Task 4.2's vocabulary changes: **no visible "collection".** A group of
      books is named by its title, with "N books" or "N documents" and the provider ("Military Medicine · 24 books ·
      from Kiwix"). "Collection" stays an internal word only.
  - **Components:** Get more → Books (Task 4.1) uses Sources' style rather than its own:
    - a small teal label (Roboto 500 13 px, #2E4A45) above a rounded #FAF7F1 block of rows with hairline dividers;
    - each row a title plus a meta line and a chevron;
    - Spectral 600 20 px section headers with a right-aligned text action;
    - 36 px outlined chips, filled teal when selected.
  - **Shelves and subjects:** the catalog `shelf` (literature / children / manuals / learning) stays the Books screen's
    grouping by kind. Each book collection also gets **subject ids from Explore's list** (history, society, business,
    places, science, nature, health, technology, art, literature, music, film, sports, religion, languages, food), so
    Explore's subject screens can show them as "In your books". This is new **Task 4.8**.
- **Hand-offs to granger-d0 (message, don't edit their files):**
  - Your sources' Books row should count every book collection on the phone (Classics, Gutenberg, Kiwix libraries,
    manifest packs, African Storybook), not only the Classics.
  - Its pack rows already exclude book packs (`isBookPack`); KIWIX packs are included in that.
  - Tell them the subject-id field's name once Task 4.8 settles it.

- [x] **Task 4.1: Books screen by kind (D4).**
  - Get more → Books: shelves Literature / Children / Manuals & references / Learning, from catalog `shelf`.
  - Search all books.
  - Archive.org stays a search entry.
  - Provider line on each row.
  - Keep KiwixList's 10 GB set cap and the preset "N to update" behaviour.
  - Spanish strings (L1 rules).
  - **Done 2026-11-16** (this commit, worktree kb2ux).
    - `PackEntry.shelf` (default "") and `Shelves` (literature, children, manuals, learning) in core/catalog.
    - **One screen:** the Library's + (`GetMoreRoute(BOOKS)`) is `BooksScreen`, over `SourceViewModel(CatalogScope.BOOKS)`
      (a view: `scopeOf` never returns it; `BookShelves.isBookItem` = Granger books, Gutenberg packs, book packs). Get
      more's Books group is now "Books by kind" (→ the same screen) and "Archive.org books"; the empty Library links the
      same two. The Granger Classics / Project Gutenberg / Books from Kiwix links and their strings are gone.
    - **Shelves** (teal labels, rounded blocks): Literature = Granger sets (presets, "Granger Classics · 437 books ·
      257 MB", "· N to update" once any is here; Clásicos with All languages, as before) opening Granger's screen, then
      **Project Gutenberg folded** ("39 subjects · From Kiwix · 1 on this phone", opens today's Gutenberg list), then
      SMBC; Children's books (Prunelle), Manuals & references (zimgit, USDA, Military Medicine, Survivor Library),
      Learning (Algorithms, Milne). A shelf with nothing is left out; a book pack with no known shelf goes under "More
      books" (seam: African Storybook arrives with `shelf: children`, no code change). Each Kiwix pack row:
      "65 MB · From Kiwix · irp.fas.org" + Get / Open+overflow Remove / progress (`RowActions`, size check, remove
      dialog shared with SourceScreen).
    - **Provider (decision):** the catalog has no provider field, so `BookShelves.provider` uses Project Gutenberg, a
      family that names a site (irp.fas.org) or a one-word maker (Prunelle); else "From Kiwix" alone.
    - **Find a book** (Spectral header): Search all books (a search mode over catalog titles/authors/descriptions of
      every book source, one "Books" block of results), Archive.org books, Import your own.
    - **Shared look:** `app/.../theme/GroupedRows.kt`: `GroupLabel` (heading semantics), `SectionHeader`,
      `GroupedRows`, `GroupedRow`, `Chevron`, `OutlineChip` (36 dp, teal when chosen). Teal is `extras.status` (Enamel in
      Field, Phosphor at Night); block = surfaceContainerLowest (Field #FAF7F1) / surfaceContainerLow (Night). For
      Explore's Sources to reuse.
    - Polish: a collection header whose every book is the exception says "Titles only until opened" / "Scans: titles
      only" without the count (`library_all_*`); the Library ThemeShot now has Military Medicine's row.
    - Tests: `BooksScreenTest` (2), `SourceViewModelTest` books by kind over the published catalog, on-the-phone counts,
      preset meta; `CollectionViewModelTest.anExceptionForEveryBookIsSaidWithoutItsCount`; `PublishedCatalogTest` shelves;
      GetMore/Library tests updated. ThemeShot `books-by-kind` (Field + Night) checked. `:core:catalog` 77, `:app` 1826
      (122 skipped), 0 failures.
- [x] **Task 4.2: one vocabulary.** No visible "collection" (Explore avoids it): a group of books is its title, plus
  the manifest `noun` count and the provider, in Get more, the header, the Make-searchable dialog and the Remove
  dialogs. Fix `sources_remove_pack_body`, which wrongly says places are deleted.
  - **Done 2026-11-16** (this commit, worktree kb2ux).
    - **Pattern (decision):** count first, then where from. Provider line "From Kiwix · <provider>" for every Kiwix book
      pack (`library_from_kiwix_by`; Gutenberg "From Kiwix · Project Gutenberg", a manifest's provider, else "From
      Kiwix" alone, `library_from_kiwix`; es "De Kiwix"). Library row "24 books · From Kiwix · Federation of American
      Scientists"; header two lines (count, then provider). Classics, My books and Archive.org stay a bare count: their
      title already names the source. "Kiwix library" (`library_kiwix_library`) is gone.
    - The Library row reads the manifest noun: `LibraryViewModel` reads each KIWIX pack's manifest off the main thread
      (`LibraryUi.manifests`, by collection key; `BookManifest.collectionOf` caches per file), so Military Medicine says
      "books", a work counted once. Nautilus and file-name packs say documents.
    - Get more: "Kiwix libraries" → "Books from Kiwix" (es "Libros de Kiwix").
    - Make all searchable: documents plurals for a documents collection (`library_make_searchable_body_documents`,
      `library_searchable_no_room_documents`).
    - Remove dialogs, by type: Get more's `SourceViewModel.removeKind` → `RemoveKind` (FILE "This deletes the file.",
      BOOK, WEB_PACK keeps `sources_remove_pack_body`, BOOK_PACK / DOCUMENT_PACK "This deletes the pack and its books.
      Your places, bookmarks and highlights are kept."). Library: `library_remove_pack_body` (web pack) now says its
      bookmarks and places go; `library_remove_book_pack_body` / new `library_remove_document_pack_body` end "Your
      places, bookmarks and highlights are kept."; `archive_remove_text_body` adds bookmarks. Single-book bodies all end
      "Your place, bookmarks and highlights are kept."
    - Left: the organising menus ("Move to collection…", "Rename collection", "New collection") keep the word; they
      name the user's own groups and weren't in scope.
    - Tests: `SourceViewModelTest.removeSaysWhatGoesWithTheFile`, `LibraryViewModelTest.aManifestPacksRowCountsInItsNounAndNamesItsProvider`,
      wording updates. ThemeShots `library-gutenberg` adds Military Medicine. `:core:library` 495, `:app` 1816 (120
      skipped), 0 failures.
- [x] **Task 4.3: Open and Remove everywhere.**
  - Open on installed Classics and archive.org texts.
  - Downloads → Open on a book pack opens its collection.
  - Remove one pack book: delete the copy and hide the row, with "Show removed".
  - Rail tabs of removed rows go.
  - **Done 2026-11-16** (this commit, worktree kb2ux).
    - Get more: an installed Granger book's row carries `ItemRow.bookId` (`book:granger:<id>`); `RowActions` opens on
      `row.opens` (pack or book), so a book reads "Open" with Remove in its overflow, as a pack does.
      `SourceScreen(onOpenBook)` opens it in the reader.
    - archive.org texts: `ArchiveViewModel.openTarget` → `ArchiveOpen.Book` (the text, else the PDF beside it) or
      `ArchiveOpen.Collection` (`c:archive-org:<identifier>` for a set). Installed rows show Open + overflow Remove
      (`RemoveMenu`, now shared); the sheet shows Remove + Open. Audio views unchanged.
    - Downloads → Open uses `openPackOrCollection`, as Get more does.
    - **Hide mechanism (decision):** a new column in the existing migration: `books.hidden INTEGER NOT NULL DEFAULT 0`
      (`Book.hidden`), added to `MIGRATION_15_16` and `16.json` (identity hash changed: the emulator at database 16 from
      the Phase 2 check needs its data cleared or a reinstall). `onShelf` is false for a hidden book, so the shelves,
      Home, title search and passage finders leave it out with no other change. `RoomBookshelf.remove` of a pack book
      deletes the copy, cover, search text and its original pages' row (their place and marks kept now, as a pack's
      removal keeps them), sets `hidden`, and deletes its rail tabs. `Bookshelf.unhide(ids)` brings rows back;
      `PackBooks.restore` (opening it, e.g. from a link) clears `hidden` too. A sync keeps the flag (rows are copied).
    - Collection: every book's menu has "Remove from phone"; a pack book's dialog says "… will be deleted from this
      phone and taken off this list; Show removed brings it back. Your place, bookmarks and highlights are kept."
      (`library_remove_pack_book_body`). The overflow shows "Show removed" (`library_show_removed`, es "Mostrar los
      quitados") when `CollectionViewModel.removed` (hidden books of this collection) isn't empty.
    - Rail: `PackBooks.sync` deletes the tabs of the rows it drops (and their pages' tabs), so a removed pack leaves no
      "Removed book" tabs. History entries stay.
    - Tests: `GutenbergBooksTest` +2 (hide/tabs/marks/unhide/restore; a removed pack's tabs), `MigrationTest` 15→16
      hidden = 0, `CollectionViewModelTest.aRemovedPackBookIsListedForShowRemovedWhichPutsItBack`,
      `ArchiveViewModelTest.openReadsTheTextItsPdfOrASetsCollection`, `SourceViewModelTest` book Open,
      `GutenbergCollectionScreenTest` Remove on a pack book. `:core:library` 497, `:app` 1818 (120 skipped), 0 failures.
- [ ] **Task 4.4: the PDF rule (D3: Read as ebook stays a choice).**
  - "Read as ebook" is offered for every text PDF from every source: pack books, imports and pack documents (today
    `onShelf` hides it for pack documents).
  - It's hidden for `pdfKind` scan or comic. Where there's no manifest, `PdfOrigin` decides on the phone.
  - It needs `canReadAsEbook` in `EbookReaderViewModel`, which is LL's file. Message granger-d0 before the change, or
    have them make it.
  - archive.org's convert-at-install stays (Plan IA), noted as the one exception.
- [x] **Task 4.5: search de-dup.**
  - Book-pack ZIM hits that map to a shelf row show as the book; the rest are dropped.
  - Show only the exceptions ("Titles only until opened", "A scan: titles only").
  - **Done 2026-11-16** (this commit, worktree kb2ux).
    - `ZimLibrarySearch.across` maps every book pack's hit (titles, near titles and full text alike) to its shelf book
      (`bookHits`): a Gutenberg page by the id in its path (`GutenbergList.idOfPage`: `Title.12.html`, `Title_cover.12`,
      `.epub`), a nautilus or manifest file by `<pack name>/<path>` = `sourceRef`. The hit becomes a book hit (book id,
      no path, its collection's name, author, the page's snippet), once per book; anything else from a book pack (index,
      author lists, a hidden or missing book, or no shelf at all) is dropped. Title rows then fold with the shelf's own
      book hit (same id and path), so Plan SQ's one row per title holds.
    - Search screen: `SearchViewModel` moves full-text hits that are books into the Books group (`SearchRows.bookHitOf`,
      href "" so the reader opens at the saved place; snippet kept only if it shows the query), after the book index's
      hits, one per book. Articles keep only articles.
    - Searchable in full is the default and unsaid. Header: "N titles only until opened" and/or "N scans: titles only"
      (`library_titles_only_until_opened`, `library_scans_titles_only`; works counted once), only when non-zero. The
      Library row drops its searchable count. Book rows lose the "Full text" mark (`FullTextMark` removed); a scan's
      label reads "PDF · A scan: title only". The book menu shows only an exception: "Title only until opened" or "A scan:
      title only". "Make all searchable…" stays. Removed: `library_searchable_in_full`, `library_searchable_full`,
      `library_full_text`.
    - Tests: `ZimLibrarySearchTest.aBookPacksHitsAreItsBooksOnceAndTheRestAreDropped`,
      `SearchViewModelTest.aBookPacksTextHitJoinsTheBooksOnce`, header/row/menu/format wording updates. ThemeShots
      (`kiwixbooks-list`, `gutenberg-list`, `kb2-*`) checked: "10 titles only until opened", "7 titles only until opened
      · 1 scan: title only". `:core:library` 498, `:app` 1819 (120 skipped), 0 failures.
- [x] **Task 4.6: Files in this pack.** A Files row on web packs with documents, from the manifest or `ZimArchive.list`.
  - **Done 2026-11-16** (this commit, worktree kb2ux).
    - **Where (decision):** Browse's pack row long press (its only menu, beside Remove from phone): "Files in this pack
      (N)", shown only for an OK, non-book pack whose ZIM `Counter` holds PDFs/EPUBs. Opens `PackFilesRoute(packId)` (rail:
      Browse). Explore's Sources should keep the entry when it takes over the pack rows (hand-off).
    - `core/library` `PackFiles`: `count(counter)` (the cheap check, `BookFiles.parseCounter`) and `list(archive)`
      (`ZimArchive.list({pdf, epub})`, titles from `BookFiles.titleOf`, A–Z; null when it can't list). No manifest path:
      web packs have none today.
    - `BrowseViewModel` reads each website pack's Counter once (cached by pack id, off the main thread) into
      `PackItem.files`.
    - `PackFilesScreen`: the pack's title, a "Files in this pack" label, the files as grouped rows ("PDF · 2.0 MB"), drawn
      item by item (`GroupedItem`, new in theme/GroupedRows.kt) so the Factbook's 839 stay lazy. A tap calls the nav
      host's `openPackDocument(packId, path, title)` (no reader files touched).
    - Strings: `browse_pack_files_count`, `browse_pack_files`, `browse_pack_files_none` (en + es).
    - Tests: `PackFilesTest` (2), `BrowseViewModelTest.aWebPackCountsItsFilesFromItsCounter`,
      `BrowseScreenTest.aLongPressOffersTheFilesInThePackWhenItHasSome`, `PackFilesScreenTest` (3). `:core:library` 501,
      `:app` 1831 (122 skipped), 0 failures; `:app:assembleDebug` builds.
- [x] **Task 4.7: per-pack membership.** Gutenberg books held by two packs list in both collections.
  - **Done 2026-11-16** (this commit, worktree kb2ux).
    - **Mechanism (decision):** a column, not a table: `books.memberOf TEXT` (`Book.memberOf`, the ZIM names of every
      readable installed pack listing the book, `\n`-joined; null for other books), added to the existing
      `MIGRATION_15_16` and `16.json`. `PackBooks.sync` writes it from the holders it already gathers (the first sync
      after upgrading rewrites each pack book once). Still one Book row and one placement per id.
    - `Filing.memberKeys(book)` turns it into collection keys; `Filing.inBookPackCollection(book, key)`.
    - `RoomShelves`: a pack book is in the collection it's placed in plus every book pack collection whose pack lists it,
      unless the user moved it by hand (then only where they put it). Each installed book pack's collection is made
      even when all its books are filed by other packs (`packCollections`), so "Every English book" exists and lists
      them. Headers and Library rows count `row.books`, so they include members. `CollectionViewModel.removed` uses
      membership too.
    - Tests: `ShelvesTest.aBookTwoPacksListIsInBothCollections` (both collections, one placement each, a hand move),
      `GutenbergBooksTest` memberOf for one and two packs, `MigrationTest` memberOf null. `:core:library` 499, `:app`
      1819 (120 skipped), 0 failures; `:app:assembleDebug` builds.

- [ ] **Task 4.8: subjects for book collections (Explore).**
  - `book_packs.json` (and a `subjects` map for list-carrying packs by prefix) gives each collection one to three
    subject ids from Explore's list. Proposed:
    - Military Medicine → health;
    - Survivor Library → technology, food, health;
    - Algorithms → science, technology;
    - Milne → technology, science;
    - SMBC → literature;
    - African Storybook → literature, languages;
    - zimgit packs → health / food;
    - Gutenberg subjects by LCC class.
  - **Blocked on granger-d0's subjects spike (EX Task 0).** The ids are draft (e.g. Food may become "Food &
    farming"); the canonical list is `tools/subjects/subjects.json` on ll-linked-library. d0 will message when it
    settles. Neither session adds the field before then. Agreed with d0 2026-11-16: `subjects: ["health", …]` on pack
    and book entries.
  - The catalog gets a `subjects` field on book packs and books. Agree the field name with granger-d0, whose LL2
    Task 4 adds an item → subject-packs sidecar.
  - The phone exposes it to Explore's subject screens through one function in core/library. Explore's UI is d0's.

- **From the Books screen review (2026-11-16), for the Task 3.2 catalog change and Phase 5:**
  - Stamp a `provider` field from `book_packs.json` (as `shelf` is) so rows read "From Kiwix · Federation of American
    Scientists", not "irp.fas.org"; add providers for the list-carrying packs (zimgit, USDA, Prunelle). Old apps ignore
    the key.
  - Phase 5: confirm the >10 GB size check (KB Decision 9) fires from the Books screen's Get on Survivor Library
    (220 GB).

## Phase 5: check and merge

- [ ] **Task 5.1: emulator pass.**
  - Every source: install, open, remove, update a snapshot (supersede), and search.
  - Spanish UI.
  - TalkBack on the new screens.
- [ ] **Task 5.2: Pixel 6 walkthrough.** Follow the install rules above. Use African Storybook English Level 1, Military
  Medicine, and an archive.org text PDF for the one PDF rule.
- [ ] **Task 5.3: merge.**
  - Rebase on main.
  - `check-links`; **Neal signs, merges and pushes.**
  - Update the roadmap row, and the upkeep README's finding kinds.
  - **Message granger-d0** once it's on main, with the migration 16 column's final name. LL2 Task 4 (an item →
    subject-packs sidecar) builds on the gbooks kind and `meta.py --url`, and LL3's article_marks takes 17 or later.

## Later (not this plan)

- Bookdash as fixed-layout picture books (design §1 B).
- Army Publishing: second pass (D1). It needs filtering to field manuals and grouping by series.
- archive.org's convert-at-install is the one exception to "Read as ebook is a choice" (D3). Revisit with Neal.
- A "Scores" collection type for sheet-music packs (design §1 D).
- `.glink` reference links and prebuilt `.gvec` vectors for the new books, so they join the Linked Library (tools/links
  is heavy, so do it after LL2).
- French packs (Prunelle fr, BNR), if Granger adds French.
