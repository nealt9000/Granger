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

- [ ] **Task 2.1: `ManifestSource` over `PackBooks`.**
  - A new `BookOrigin.KIWIX` (`book:kiwix:<zimName>/<key>`), `isPackBook`.
  - Download the `.gbooks` with its pack, as `.gqid` files are.
  - `Pack.bookSource` is KIWIX when the pack has a `gbooks` sidecar (catalog) or a downloaded `.gbooks` file: not
    `content`, which stays blank for these packs (Task 1.4). Add `"gbooks" to 1` to `SidecarEntry.KNOWN`.
  - Rows carry group, order, level, language and parts. Restore picks the inspector by format.
  - Tests: fixture manifest, parts folded, snapshot change keeps ids, fallback when the manifest is missing.
- [ ] **Task 2.2: fallback.**
  - When a pack looks like a book bundle (`content: "books"` or the audit's rules on its Counter) but has no manifest,
    list it with `ZimArchive.list`, titled by file name.
  - The header says "Titles from file names".
- [ ] **Task 2.3: groups in the collection screen.**
  - Section headers from manifest groups (Level 1–5; Handbooks; The book / Course materials).
  - A language chip when a collection holds more than one language.
  - The ThemeShots get the new states.
- [ ] **Task 2.4: pack documents keyed by name (design §4.5).**
  - `book:pack:<zimName>/<path>`.
  - Adopt old UUID rows with place, marks, tabs and history (the KB Decision 5 pattern).
  - Supersede no longer deletes them.
  - Migration 16 if a column is needed; tell granger-d0 first.
- [ ] **Task 2.5: fix the nautilus Read-as-ebook bug (design §4.4).**
  - `restore` copies to the PDF path and re-runs the conversion when the row's EPUB is gone.
  - Sync removes the `.pdf`, the `.pdf.epub` and the PAGES row together.
  - The Task 0.1 test now passes.

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

- [ ] **Task 4.1: Books screen by kind (D4).**
  - Get more → Books: shelves Literature / Children / Manuals & references / Learning, from catalog `shelf`.
  - Search all books.
  - Archive.org stays a search entry.
  - Provider line on each row.
  - Keep KiwixList's 10 GB set cap and the preset "N to update" behaviour.
  - Spanish strings (L1 rules).
- [ ] **Task 4.2: one vocabulary.** "Collection", plus the manifest `noun` in Get more, the header, the Make-searchable
  dialog and the Remove dialogs. Fix `sources_remove_pack_body`, which wrongly says places are deleted.
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

## Later (not this plan)

- Bookdash as fixed-layout picture books (design §1 B).
- Army Publishing: second pass (D1). It needs filtering to field manuals and grouping by series.
- archive.org's convert-at-install is the one exception to "Read as ebook is a choice" (D3). Revisit with Neal.
- A "Scores" collection type for sheet-music packs (design §1 D).
- `.glink` reference links and prebuilt `.gvec` vectors for the new books, so they join the Linked Library (tools/links
  is heavy, so do it after LL2).
- French packs (Prunelle fr, BNR), if Granger adds French.
