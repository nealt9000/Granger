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

- [ ] **Task 0.1: verify the survey's UX findings.** Confirm or drop each item in design §4 against main, with a test or a
  file:line. #4.4 (the nautilus Read-as-ebook restore bug) is already confirmed. Write a failing test for it now.
- [ ] **Task 0.2: `ZimArchive.list(mimes)`.**
  - Wrap libkiwix `iterByPath()` in `KiwixZimArchive`: path, title, mime and size for entries of the given mimes,
    streamed.
  - Test against `testdata` ZIMs.
  - Time it on the phone over a large pack (Gutenberg en_all, 60k EPUBs) and record it. This is the fallback's cost.
- [ ] **Task 0.3: extractor spike on the Mac.** Write `tools/bookpacks/build.py` with `kolibri` and `zimit-index`
  extractors, over `zim_probe.Reader`.
  - Build manifests for African Storybook, Military Medicine, SMBC, Algorithms, Milne and Survivor Library (its 194
    category pages give the groups).
  - Grade titles by hand on 30 random items per pack.
  - **Pass:** 95% or more readable titles after overrides, 0 wrong languages in African Storybook, and parts folded
    for Military Medicine's "Full text / Part 1–3".
  - Record the result in the plan. **Stop and report if a shape fails.**

## Phase 1: manifests and upkeep (tools)

- [ ] **Task 1.1: format + check.**
  - Write `tools/bookpacks/schema.md` (design §3).
  - Write `check.py`: every path resolves in the source ZIM, keys are unique, titles aren't empty, and a drift report
    against the previous manifest (keys lost, count delta, share of file-name titles).
  - Add tests with a fixture ZIM.
- [ ] **Task 1.2: `book_packs.json`.** Per pack: extractor, filters, group rules, `noun`, provider, licence and `overrides`.
  Start with the round-one packs from D1.
- [ ] **Task 1.3: sidecar kind `gbooks`.**
  - Add it to `tools/sidecar/kinds.json` (build and check commands, `gpu: false`).
  - Write release descriptors under `tools/sidecar/releases`.
  - Make `catalog_gen.py attach_sidecars` attach them to the pack entries.
  - Use `build --previous` hold-back to hold a pack whose `.gbooks` would drop (`snapshot-held-sidecar`).
  - Tests.
- [ ] **Task 1.4: catalog `content`.**
  - Manifest-backed packs get `content: "books"` and a `shelf` (Literature / Children / Manuals / Learning).
  - Gutenberg and nautilus packs get their shelf too.
  - Add African Storybook to `curated.json` if D2 says ZIM, or don't list it if D2 says sets.
  - `FORMAT_VERSION` stays 1.
- [ ] **Task 1.5: upkeep findings.** `upkeep.py check` adds:
  - `bookpack-drift`, from `check.py` on any rebuilt manifest;
  - `bookpack-candidate`: `zim_probe probe` on new or changed packs with the audit's rules (3 or more docs; doc bytes
    at least 50% of the pack, or a doc/HTML ratio of at least 0.5; not already in `book_packs.json`; not class E in
    the audit's list).
  - Add playbooks to `tools/upkeep/README.md`, and tests.
- [ ] **Task 1.6: build, check, release prep.**
  - Build the round-one `.gbooks` and run the checks.
  - Write an upload script, resumable, batched about 10 per upload with sleeps.
  - **Neal uploads.**
  - Attach the manifests to the catalog, unsigned, then run `check-links`. **Neal signs.**

## Phase 2: the phone reads manifests

- [ ] **Task 2.1: `ManifestSource` over `PackBooks`.**
  - A new `BookOrigin.KIWIX` (`book:kiwix:<zimName>/<key>`), `isPackBook`.
  - Download the `.gbooks` with its pack, as `.gqid` files are.
  - `Pack.bookSource` is KIWIX when the pack has a manifest.
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
