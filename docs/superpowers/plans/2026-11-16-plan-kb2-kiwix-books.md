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
  - **Done 2026-11-16 (built, not uploaded).** `tools/pack-builder/africanstorybook.py build --date 2026-11-16
    --epubcheck` (resumable; `--no-pull` reuses the originals; `describe` rewrites the descriptors offline).
    - **Source:** the `kolibri` extractor over range reads of `africanstorybook.org_mul_all_2025-01.zim` (Date
      2025-01-11): 5,165 items, 6.4 MB fetched for the list. Each EPUB sits alone in a stored cluster, so it's one
      range read: 1.9 GB fetched for the 1,120 books, about 25 minutes with 8 threads.
    - **Ids:** `asb-<lang>-<node>`, the Kolibri node id whole (32 hex, 39 characters). Classics ids have no length
      rule (the longest is 63), and the whole node id needs no collision check. Files are `<id>.epub`.
    - **Releases:** one per language, `asb-en-2026-11-16` and `asb-sw-2026-11-16`, not one `asb-<date>`. A GitHub
      release holds at most 1,000 assets, and both languages together are 1,120. URLs:
      `…/releases/download/asb-<lang>-<date>/<id>.epub`.
    - **What the EPUBs carry:** each is African Storybook's own EPUB 3: `cover-image` (`images/cover.png`), one page
      per spread, and a back cover giving title, author, illustrator, language, level, "© …", "Creative Commons:
      Attribution 4.0", "Source www.africanstorybook.org" and the ASB logo. That already meets CC BY, so **no colophon
      is added**. The OPF has title, creators with roles (aut, ill, adp), language and date, but no `dc:rights`.
    - **Modify or not: modified, minimally.** Every original fails epubcheck 5.4.0 (3 errors each; 67 had more), so
      shipping them untouched would break the Classics' epubcheck-clean bar. `fix_epub` is deterministic (same input,
      same bytes) and idempotent. Each descriptor entry keeps `sourceSha256`, the sha256 of Kiwix's original, for the
      upkeep diff. What it changes:
      - In all 1,120:
        - the OPF's misspelt "africanstroybook.org" and "African Stroybook Initiative" are corrected, which fixes
          NCX-001 (identifier mismatch);
        - `direction` and `unicode-bidi` are dropped from `epub.css` (CSS-001; only the unused `.alignRight` class
          has them);
        - `0.xhtml` becomes `cover.xhtml` and the back cover becomes `colophon.xhtml` (OPF, nav and NCX updated), so
          Related skips them;
        - `dc:rights` gets the back cover's credit line, e.g. "© African Storybook Initiative 2015 · Creative Commons:
          Attribution 4.0 · Source: www.africanstorybook.org".
      - In some:
        - 46 had a **0-byte `cover.png`** (28 en, 18 sw; MED-004/PKG-021). They now use the first page's picture.
        - 27 had an empty `dc:creator` (no illustrator, RSC-005). It's removed with its role.
        - 12 had XML that doesn't parse (RSC-016): a 0x0B control character in 4, a bare `&` in 7 ("Romeo &
          Basetsana", "Angie & Upesh") and an unclosed `<br>` in 1. These are mended.
    - **epubcheck:** **0 failures out of 1,120** after the fix (693 en, 427 sw; 0 errors, 0 warnings). Before the
      fix, all 1,120 failed.
    - **Checks against the Kolibri page:** every EPUB's `dc:language` matches (0 wrong). Every book has a cover, an
      author and a year. 12 titles differ from the EPUB's: 8 only by double spaces, 1 by a comma, and 3 are real differences ("Cows
      at the market" / EPUB "Market cows"; "Young Pasitau" / "Young Palinyang’"; "Hare and Hyena fight"). The
      descriptor uses the page's title, as the manifest does. Details are in `report.json`.
    - **Numbers:**
      - **English:** 693 books, 1.17 GB (largest 4.4 MB). Levels 1–5: 151 / 179 / 179 / 119 / 65.
      - **Kiswahili:** 427 books, 0.74 GB (largest 3.4 MB). Levels 1–5: 141 / 97 / 89 / 71 / 29.
      - En has 19 repeated titles and sw 10: different stories or versions with the same name, different nodes.
    - **Release descriptors:** `tools/pack-builder/releases/african_storybook_{en,sw}_books.json`, in the Classics'
      shape (`releaseDate`, `collection` = `african-storybook-<lang>`, `books[]` with id, title, author, translator,
      published, section = "Level n", language, sizeBytes, sha256, urls, domain `literature`). Extra fields:
      `description` (the Kolibri summary), `level`, `illustrator`, `cover`, `node`, `sourcePath` and `sourceSha256`.
      The top level also has title, provider, license, source and level counts. They're 684 KB and 414 KB. **In the
      catalog the books add about 0.8 MB to today's 1.9 MB `catalog.json`,** mostly descriptions.
    - **Files:** `~/Developer/Granger/vectors-work/asb-2026-11-16/`:
      - `en/` (693) and `sw/` (427): the files to upload;
      - `source/<lang>/`: Kiwix's originals, 1.8 GB, kept for `--no-pull` and diffs; deletable once uploaded;
      - `manifest.json`, `report.json` (changes, mismatches, epubcheck), and `build.log` / `build2.log`.
    - **Neal runs:** `tools/pack-builder/upload_asb.sh en 2026-11-16`, then `… sw 2026-11-16`. Each is resumable and
      uploads 10 files per `gh release upload`, with a 20 s pause.
    - **Tests:** `tools/pack-builder/tests/test_africanstorybook.py`, 27 tests: ids, levels, release names, choice,
      each fix, determinism and idempotence, facts, and the descriptor. The pack-builder suite has 315, all passing
      (`~/.venvs/granger-pack/bin/python -m pytest tools/pack-builder/tests`).
    - **Not done:**
      - Each EPUB embeds about 830 KB of Noto fonts (Ethiopic and Arabic Kufi included), which en and sw never use.
        Dropping them would save roughly 0.2–0.3 GB, but it's a bigger change than epubcheck needs. Left for Neal.
      - `.gvec`/`.glink` for these books: see "Later".
- [ ] **Task 3.2: catalog.**
  - **Decided 2026-11-16 (after Task 3.1 found the blocker):** the African Storybook books do **not** go in the catalog's
    `books` array.
    - Today's app files every `books` entry into Granger Classics (`CatalogScope.kt:34`, `Filing.kt:139`), and one
      catalog serves every app version. So putting them there would put 1,120 children's books into testers' Granger
      Classics, whatever build ships later.
    - They go in a **new top-level array** that old apps ignore (`ignoreUnknownKeys`), for example `bookSets`: one set
      per language with its shelf, titles, level presets and books. This is the same principle as Task 1.4: book-ness
      that old apps can't see.
    - The new app reads the array, files `asb-` books into their own collections (African Storybook · English /
      Kiswahili) with Level sections, and lists the sets on the Books screen's Children's shelf.
    - This is app work, so it follows Task 4.1 on `kb2-ux` and ships in the same catalog change as the rebuilt
      Algorithms `.gbooks`.
    - The prep below (`curated.json` `books` rows) changes to match: same rows, emitted into the new array.
  - ~~Catalog `books` entries.~~
  - Presets: "African Storybook · English" (all levels), with Level 1–5 sets.
  - Spanish copy for titles and levels; book text stays in its own language.
  - Children's shelf.
  - `check-links`, then **Neal uploads and signs.**
  - **Prep (2026-11-16, Task 3.1's session; nothing published, `catalog.json` untouched).** The exact change:
    - **How the books get in: `curated.json` `books` rows, like the Classics.** No new source in catalog_gen. Two
      rows after Clásicos Granger's:
      ```json
      {"bookRelease": "tools/pack-builder/releases/african_storybook_en_books.json",
       "license": "CC BY 4.0, African Storybook Initiative", "title": "African Storybook · English",
       "language": "en", "shelf": "children",
       "i18n": {"es": {"title": "African Storybook · inglés"}},
       "levelSets": {"label": "Level {n}", "i18n": {"es": "Nivel {n}"}}},
      {"bookRelease": "tools/pack-builder/releases/african_storybook_sw_books.json",
       "license": "CC BY 4.0, African Storybook Initiative", "title": "African Storybook · Kiswahili",
       "language": "sw", "shelf": "children",
       "i18n": {"es": {"title": "African Storybook · suajili"}},
       "levelSets": {"label": "Level {n}", "i18n": {"es": "Nivel {n}"}}}
      ```
      The project name stays untranslated (it's the provider's name); the language and "Nivel" are Spanish. The book
      text stays in its own language. `language: "sw"` puts Kiswahili under a language Granger has no UI for: check
      that Get more's language chip shows it (`KiwixList.languageName("sw")`), or the set is hidden for es/en users.
    - **catalog_gen side: done in this commit, with tests** (`test_catalog_gen.py`, 4 new, 116 pass).
      - `book_entries` now takes a book's `description` from its descriptor (the Kolibri summary; Classics have none,
        so theirs stay `""`), and adds `level` and the row's `shelf` when given. Old apps ignore both keys
        (`ignoreUnknownKeys`).
      - `level_presets`: a row with `levelSets` gets, beside its all-books preset, `<collection>-level-<n>` presets
        (e.g. `african-storybook-en-level-1`, "African Storybook · English: Level 1", es "African Storybook · inglés:
        Nivel 1"), with `level` and `setOf` keys. A book without a level fails the build.
      - Presets: `african-storybook-en` (693) + `-level-1..5` (151/179/179/119/65); `african-storybook-sw` (427) +
        `-level-1..5` (141/97/89/71/29). Category ("Level 2") is the descriptor's `section`.
      - Not added to Starter, Readiness or onboarding (they're opt-in; Decision 5 counts manual collections, not these).
    - **Shelf "children":** stamped per book by the row. Task 4.1's Books screen reads it; until then it's inert.
    - **App work this needs before the catalog goes out (found while preparing; not Python):**
      - Today's app (testers' builds) treats **every** `BookEntry` as Granger's own: `CatalogScope.scopeOf` puts it
        under Granger Classics (`sources/CatalogScope.kt:34`), and `Filing.grangerKey` files any id that isn't
        `gc-es-…` into Granger Classics (`core/library/.../Filing.kt:139`). So publishing these books before the app
        knows them puts 1,120 children's books into Granger Classics on every tester's phone, and the Granger Classics
        screen shows all 12 new presets.
      - So 3.2 needs, in the app: `asb-` ids file into their own collection per language (key = the catalog
        `collection`, `african-storybook-en|sw`, named by the preset title; a `Filing.grangerKey` rule on the `asb-`
        prefix, as `gc-es-` has), and Get more lists them under Kiwix books / Children (Task 4.1) rather than Granger
        Classics; the level presets show as sets inside that row.
      - And the catalog change waits for the tester build that has this, or the books go in a key old apps ignore.
        **Neal decides.** Recommended: ship them with the Task 4.1 build (the Books screen by kind), one catalog change.
    - **Same catalog change, carried from Phase 2:** the rebuilt Algorithms manifest. Built 2026-11-16 into
      `~/Developer/Granger/vectors-work/bookpacks-2026-11-16b/jeffe.cs.illinois.edu_en_all_2026-09.gbooks` (696 items;
      groups "The book" then "Course materials", chapters in path order; `check.py --catalog` passes, no drift).
      - It can't reuse `bookpacks-2026-11-16`: that release already holds a file of the same name, which the signed
        catalog pins by sha256. Upload it as a new release `bookpacks-<date>` (`tools/bookpacks/upload_release.sh
        bookpacks-<date> ~/Developer/Granger/vectors-work/bookpacks-2026-11-16b`), then rewrite its descriptor:
        `tools/sidecar/meta.py release <file> --id jeffe.cs.illinois.edu_en_all --url
        https://github.com/nealt9000/Granger/releases/download/bookpacks-<date>/jeffe.cs.illinois.edu_en_all_2026-09.gbooks`.
    - **Order of work for 3.2:** Neal runs `upload_asb.sh en 2026-11-16`, `upload_asb.sh sw 2026-11-16` and the
      bookpacks upload → add the two curated rows and the jeffe descriptor → `catalog_gen.py build --previous` (book
      sizes are HEAD-checked against the descriptors) → semantic diff (1,120 books, 12 presets, 1 sidecar, generatedAt)
      → `check-links` → `:core:catalog` tests (PublishedCatalogTest counts) → Neal signs.
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

- [ ] **Task 4.1: Books screen by kind (D4).**
  - Get more → Books: shelves Literature / Children / Manuals & references / Learning, from catalog `shelf`.
  - Search all books.
  - Archive.org stays a search entry.
  - Provider line on each row.
  - Keep KiwixList's 10 GB set cap and the preset "N to update" behaviour.
  - Spanish strings (L1 rules).
- [ ] **Task 4.2: one vocabulary.** No visible "collection" (Explore avoids it): a group of books is its title, plus
  the manifest `noun` count and the provider, in Get more, the header, the Make-searchable dialog and the Remove
  dialogs. Fix `sources_remove_pack_body`, which wrongly says places are deleted.
- [ ] **Task 4.3: Open and Remove everywhere.**
  - Open on installed Classics and archive.org texts.
  - Downloads → Open on a book pack opens its collection.
  - Remove one pack book: delete the copy and hide the row, with "Show removed".
  - Rail tabs of removed rows go.
- [ ] **Task 4.4: the PDF rule (D3: Read as ebook stays a choice).**
  - "Read as ebook" is offered for every text PDF from every source: pack books, imports and pack documents (today
    `onShelf` hides it for pack documents).
  - It's hidden for `pdfKind` scan or comic. Where there's no manifest, `PdfOrigin` decides on the phone.
  - It needs `canReadAsEbook` in `EbookReaderViewModel`, which is LL's file. Message granger-d0 before the change, or
    have them make it.
  - archive.org's convert-at-install stays (Plan IA), noted as the one exception.
- [ ] **Task 4.5: search de-dup.**
  - Book-pack ZIM hits that map to a shelf row show as the book; the rest are dropped.
  - Show only the exceptions ("Titles only until opened", "A scan: titles only").
- [ ] **Task 4.6: Files in this pack.** A Files row on web packs with documents, from the manifest or `ZimArchive.list`.
- [ ] **Task 4.7: per-pack membership.** Gutenberg books held by two packs list in both collections.

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
