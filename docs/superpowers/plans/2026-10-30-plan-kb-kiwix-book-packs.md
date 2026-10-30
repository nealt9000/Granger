# Plan KB: Kiwix book packs, and a friendlier Kiwix catalog — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** two things.
- **Book packs:** every Kiwix pack that is really a bundle of books is treated like the Gutenberg packs. It shows under Books in Get more and opens as a collection in the Library.
- **The Kiwix catalog:** the rest of the catalog is easier to find your way around. There's one row per pack, it's written in plain words, the big families are folded away, and search matches words in any order.

Neal, GB walkthrough (2026-10-30): "we'll need to review all the kiwix packs for ones that are ebook bundles and treat them like the GB ones. The kiwix catelog in books should only show the book packs, with the rest showing in the Browse kiwix catelog." And, from U3c (2026-10-29): "a big value add to our app is making this kiwix catelog much more user friendly and accessible."

**Settled with Neal, 2026-10-30:**
- **Which packs:** the bundles that carry their own list of books, which are the 41 Gutenberg packs and 9 "nautilus" packs. The PDF-heavy website copies stay in Browse for now (Decision 1).
- **Essentials:** Food for Preppers, Water, Knots and the like show under Books, but Readiness, onboarding and the domain views still list and count them (Decision 6).
- **Friendlier catalog, all four parts:**
  - one row per pack (Decision 8);
  - plain words and a size check (Decision 9);
  - fold the big families (Decision 10);
  - better search (Decision 11).

**Out of this plan:**
- The PDF-heavy website copies:
  - Survivor Library (220 GB), Military Medicine, Algorithms, the SMBC book pack, Army Publishing and Music by Dalitz.
  - Turning them into collections needs a way to list a pack's files on the phone. Their PDFs keep opening in the PDF reader as today.
- Borderline packs (World Factbook, Alexandria, Lua.org, Tom Lehrer's Songs) stay web.
- Friendlier titles for opaque bulk packs ("The Infosphere").

**Tech Stack:** unchanged (Kotlin 2.4.21, Compose Material 3, Room, Readium, libkiwix, Robolectric 4.17, JUnit 4 + Truth; Python for catalog-gen).
- **Schema:** one library migration, 14 → 15.
- **Catalog:** one regeneration of fields, re-signed after `check-links`. `FORMAT_VERSION` stays 1; new fields rely on `ignoreUnknownKeys`.
- **Build env:** `JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home ANDROID_HOME=/opt/homebrew/share/android-commandlinetools`.
- **Worktree:** `.claude/worktrees/kb` (branch `kb-kiwix-books`, off main `a9a36dda`), with submodules and `local.properties` already set up.
- **Phone:** the Pixel is on Wi-Fi as `adb-21121FDF6007JN-gtbanj._adb-tls-connect._tcp`. One app on the phone. It has five nautilus packs installed:
  - `zimgit-food-preparation_en`, `zimgit-medicine_en`, `zimgit-post-disaster_en`, `zimgit-water_en`;
  - `prunelle_en_interactive-books`.

**Roadmap:** the KB row in `docs/superpowers/plans/2026-10-15-granger-v1-roadmap.md`. F1 follows.

## The audit (2026-10-30, all 924 catalog packs, nothing downloaded)

- **Tool:** `zim_probe.py` (scratchpad `kb/`) reads a ZIM's header, mime list and `M/` metadata (`Counter`, `Scraper`, `Title`, `Description`, `Name`, `Tags`) over HTTP Range requests.
  - It decompresses only the metadata cluster: a median 447 KB and 20 requests per pack, 924 of 924 in 464 s.
  - It was validated byte for byte against libzim on a local Gutenberg pack.
  - `byte_share.py` samples a pack's clusters to estimate what share of its bytes are PDF, EPUB or media (within about ±5 %).
- **Results:**

  | Class | Packs |
  |---|---|
  | epub-books | 42 |
  | pdf-docs | 18 |
  | media | 5 |
  | web | 859 |

- **The scraper is the reliable signal:**
  - `gutenberg2zim` → 41 packs, all EPUB bundles.
  - `nautiluszim` → 9 packs, all document bundles with a `database.js` list:
    - zimgit food-preparation, knots, medicine, post-disaster, water;
    - usda-2015;
    - prunelle budding-authors and draw-your-african-story (PDF);
    - prunelle interactive-books (EPUB).
  - `zimit` is the only scraper that can't be judged by name. Its PDF-heavy packs are out of this plan.
  - Tags alone wouldn't do: no tag marks a PDF bundle, and `preppers` also covers 12 ordinary sites.
- **PDFs as attachments:** byte share alone would misjudge wikibooks, wikiversity and energypedia (mostly PDF bytes, but 100k pages). The PDF/HTML count ratio keeps them web.
- **Files:** in `/private/tmp/claude-501/-Users-s-nealthompson-Developer-Granger/46d9a9ac-c5ce-492a-b216-25219dba510b/scratchpad/kb/`:
  - `probe.jsonl`, `final.json` and `final.txt` (every pack's class and counts);
  - `zim_probe.py`, `byte_share.py` and `final.py` (the rules).

## What the code looks like now (surveyed 2026-10-30, main `a9a36dda`)

- **GB, the template:**
  - **Detection:** two predicates, `isGutenberg(PackEntry)` (`sources/CatalogScope.kt:35`) and `Pack.isGutenberg` by name (`core/library/.../GutenbergNames.kt:19,32`). `Pack` has no family, scraper or content column (`Entities.kt:20-34`), and Filing, Shelves and Browse decide synchronously from the `Pack` row.
  - **Generic parts, to lift:**
    - `GutenbergBooks.sync` (:122-182);
    - `restore` (:190-228), apart from its use of `EpubInspector`;
    - the name-keyed `sourceRef`;
    - `bookFor` for tapped links;
    - the `held()` cache;
    - `GutenbergShelf` (`cost`, `makeSearchable`, `progress`);
    - the collection header and the "Make all searchable" dialog (`CollectionViewModel.kt:124-151`, `CollectionScreen.kt:206-207,317`);
    - the Library row text (`LibraryScreen.kt:274-283`).
  - **Gutenberg-only parts:** `full_by_title.js` parsing, the EPUB path and cover names, `GutenbergNames.PROVIDER`, `Filing` keys `gutenberg-<lcc>`, `Book.searchableInFull` hard-coding GUTENBERG, and `openPackDocument` routing by the `.epub` suffix (`GrangerNavHost.kt:620-639`).
- **PDF in a ZIM (P2):**
  - `PackDocuments.open` copies a tapped PDF to `<books>/packs/<uuid>/<sha1(path)>.pdf` and registers a hidden `BookOrigin.PACK` row, `book:pack:<uuid>/<path>`, keyed by **UUID**. It isn't indexed and isn't on the shelf.
  - `NautilusDatabase.parse` (`core/library/.../NautilusDatabase.kt:20-78`) keeps only `fp` → `ti`. It drops `_id`, description, author, order, and which files belong to one record.
- **Kiwix catalog:** `SourceViewModel` KIWIX scope (`sources/SourceViewModel.kt:355-495`) has Recommended (15 featured), category chips and sections folded at 8.
  - Search is a substring match on title, description and `zimName` (:411-417).
  - Rows (`SourceScreen.kt:296-342`) show "`<size> · eng · Updated 2026-03-05 · <licence>`" and Get starts straight away.
  - 883 packs in scope: Programming 277 (devdocs 243), Maps 195, Q&A 181. There are 93 maxi, 50 nopic and 10 mini flavours, each its own row, and 46 titles repeat.
  - 30 packs are over 10 GB.
- **Catalog generation:**
  - Bulk rows forward only `family, category, license, nonCommercial, domain` into `pin()` (`catalog_gen.py:234-235`), so a new field must be added there and on the curated path (:351-360, `release_entry` :289-306).
  - `PackEntry` (`core/catalog/.../Catalog.kt:41-70`); `FORMAT_VERSION = 1` must not change (`CatalogParser.kt:20,39`).
- **Readiness:**
  - The zimgit packs are food essentials (water, food-preparation), in the efficient and comprehensive presets, and in the offgrid and medical use cases.
  - `SourceViewModel.shown` drops anything outside the current scope (:402).
  - `ReadinessModel.kt:252,265` attributes storage by `scopeOf`.

## Decisions

1. **Book packs are the listed bundles** (Neal's choice): scraper `gutenberg2zim` → EPUB books; `nautiluszim` → documents (PDF or EPUB, per file). Nothing else.

2. **The catalog says so.**
   - `PackEntry` gains `content: String = ""` ("books" for these 50, else blank) and `flavour: String = ""` ("maxi", "nopic", "mini" or blank).
   - `catalog_gen.py` sets them:
     - `content` from a checked-in `tools/catalog-gen/pack_contents.json` (pack name → scraper, from the probe);
     - `flavour` from the ZIM name's suffix.
   - The probe moves into the repo as `tools/catalog-gen/zim_probe.py`, with a `probe` command that refreshes `pack_contents.json`, and tests against a small fixture ZIM.

3. **The phone knows without the catalog.**
   - Migration 14 → 15 adds `packs.scraper TEXT` (nullable).
   - `Pack.from` fills it from the ZIM's `Scraper` metadata, and the next library refresh backfills existing rows.
   - `Pack.bookSource` is GUTENBERG for `gutenberg2zim` or a `gutenberg_` name, NAUTILUS for `nautiluszim`, else null.
   - That works for sideloaded packs and with no catalog. Filing, Shelves, Browse and the collection screens ask `bookSource`, not `isGutenberg`.

4. **One "pack books" machine, two sources.**
   - GB's generic parts become `PackBooks`. It takes a `PackBookSource` (list a pack's items, find an item's file and cover, inspector by format).
   - `GutenbergSource` is today's behaviour, and Gutenberg's tests stay green unchanged.
   - `NautilusSource`:
     - `NautilusDatabase.parse` returns records (`_id`, `ti`, `dsc`, `aut` where present, its `fp` list, order). Check the real `database.js` of all 9 packs, fetched with the probe, before fixing the shape.
     - **One row per file,** titled by the record (a record with several files gets "Title (2 of 3)").
     - **Ids:** `BookOrigin.NAUTILUS`, id `book:nautilus:<packName>/<fp>`, `sourceRef = <packName>/files/<fp>`, format by file type.
     - **Covers:** a PDF's first page after copy-out (`PdfCovers`), and a title tile before.
   - `restore` picks `PdfInspector` or `EpubInspector` by format.
   - "Searchable in full" counts `searchable` (a scanned PDF isn't).
   - `openPackDocument` asks the pack's `bookSource`: a link inside a book pack opens its collection row, whatever the file type.

5. **Old PDF rows are adopted.**
   - When a nautilus pack syncs, any `BookOrigin.PACK` row for the same pack, matched by its UUID's `files/<fp>`, hands its position, marks, tabs and history to the new row.
   - The PACK row and its copy are then removed (the copy moves if it's the same file).
   - Food for Preppers' earlier reading on the phone carries over.

6. **Books, but still counted** (Neal's choice).
   - Book packs show only under Books in Get more and in the Library. They're not in Browse, the Kiwix folder or the Kiwix list.
   - Readiness's domain views, onboarding, presets, use cases and storage attribution look across scopes, so Food's essentials still list Water and Food for Preppers. Their rows open the pack's collection once installed.

7. **Collections and Get more.**
   - Each nautilus pack is its own collection, `c:kiwixbooks-<zimName>`, titled with the pack title ("Food for Preppers") and subtitled "Kiwix library · n documents · m searchable in full". It sits beside Granger Classics and the Gutenberg collections.
   - Get more's Books section gains **"Kiwix libraries"**, after Project Gutenberg. It opens `CatalogScope.KIWIX_BOOKS`, which lists the nautilus packs A–Z with size and install state. Open goes to the collection.

8. **One row per pack** (Neal's choice).
   - The Kiwix list groups entries by `zimName`.
   - A row shows the pack once, with a choice of "With pictures 6.9 GB · No pictures 2.1 GB · Mini 0.4 GB". The default is the curated or featured flavour, else the largest that fits.
   - An installed flavour says "Installed: no pictures", with Update or Switch.
   - Search, chips and Recommended work on the grouped rows.

9. **Plain words and a size check** (Neal's choice).
   - Language codes become names through `Locale` ("English", "English, Russian").
   - "Updated March 2026".
   - Licences get short names from a small table ("CC BY-SA", "Open licences", "Licence not stated"), with the full string in the row's details.
   - Get on anything over 10 GB asks first: "Survivor Library is 220 GB. You have 41 GB free on Phone." It offers Get, or a smaller flavour where there is one. The 100 MB `askBeforeLarge` dialog is reused where it applies.

10. **Fold the big families** (Neal's choice).
    - In the Kiwix list, devdocs (243) and stack_exchange (181) are one row each: "Developer docs · 243 packs" and "Q&A sites · 181 packs".
    - Each opens an A–Z list on the LV shared shelf, with the letter strip and its own search.
    - Maps (193) leave the Kiwix list. A single "Maps · 193 regions" row opens the A–Z map list, with a search field on top.
    - **Folding is driven by a `family` → label table, so it's easy to extend.**

11. **Better search** (Neal's choice).
    - Words in any order; every word must match somewhere.
    - It searches the title, description, `zimName`, category, family label and language name.
    - Accents fold (`SearchText.fold`). Title hits rank first.
    - A search also looks inside folded families and lists matching packs directly, so "python" finds Python Docs.

## Global Constraints

- `./gradlew testDebugUnitTest :core:research:test` stays green after every task, and the count only grows. The baseline is 2598 (38 skipped).
- One library migration (14 → 15), with `MigrationTest` cases. The catalog keeps `FORMAT_VERSION` 1, and is re-signed only after `check-links` passes. Gutenberg behaviour doesn't change.
- Reading places, bookmarks and history in a nautilus pack's PDFs survive the move from PACK rows.
- Commit after every task with the message given, ending with the Co-Authored-By line.
- One app on the phone.

## Review Focus

- Do all 9 nautilus packs, and only they and the 41 Gutenberg packs, have `content: "books"`?
- On the phone, do Food for Preppers, Medical, Post Disaster, Water and Prunelle become Library collections, with no reading lost from Food for Preppers?
- Does a PDF or EPUB link inside a nautilus pack's own page open the collection's book?
- Does Readiness's Food still list Water and Food for Preppers?
- Is the Kiwix list free of book packs, with one row per pack, plain words, folded families, Maps as one row, and any-order search?
- Does a Get over 10 GB ask first?

---

### Task 1: The catalog knows book packs and flavours

- [ ] Record the baseline in the commit message.
- [ ] Move `zim_probe.py` into `tools/catalog-gen/`:
  - a `probe` command writing `pack_contents.json` (name → scraper);
  - tests against a fixture ZIM made by `tools/test-zim` or a tiny generated one;
  - commit `pack_contents.json` for all 924 packs.
- [ ] Fetch the 9 nautilus packs' `database.js` with the probe. Save them as test fixtures (they're small), and record their record shapes in the plan's Decision 4 notes.
- [ ] `content` and `flavour` in `pin()`, the bulk `fields`, the curated path and `release_entry`, plus `PackEntry`. Tests in `test_catalog_gen.py` and `CatalogParserTest`.
- [ ] Set the new fields in `catalog.json` (in place, as GB did), then `check-links`, re-sign and `verify`. Update `PublishedCatalogTest` (50 book packs).
- [ ] **Commit.** `Catalog: which packs are books, and each pack's flavour`

### Task 2: Pack books, generalised

- [ ] Migration 14 → 15 (`packs.scraper`), the `Pack.from` fill, the refresh backfill and `Pack.bookSource` (Decision 3).
- [ ] Lift GB's generic parts into `PackBooks` with `GutenbergSource`; Filing, Shelves, Browse and the collection screens use `bookSource`.
- [ ] Gutenberg tests unchanged and green. Add `MigrationTest` cases and a sideloaded-pack test (no catalog).
- [ ] **Commit.** `Pack books: one machine for any pack that is a bundle of books`

### Task 3: Nautilus packs as collections

- [ ] `NautilusDatabase` records, `NautilusSource`, `BookOrigin.NAUTILUS`, restore by format and covers (Decision 4).
- [ ] PACK-row adoption (Decision 5) and `openPackDocument` routing.
- [ ] Collections named and subtitled (Decision 7); the pack hidden from Browse and the Kiwix folder.
- [ ] Tests, using the real `database.js` fixtures:
  - a multi-file record;
  - an EPUB in Prunelle;
  - adoption keeps a position and a bookmark;
  - a link in the pack page opens the row;
  - a scanned PDF isn't counted as searchable.
- [ ] ThemeShots: Food for Preppers' collection, 360 dp, Field and Night. Review the renders.
- [ ] **Commit.** `Kiwix libraries: nautilus packs become book collections`

### Task 4: Books in Get more, and still counted

- [ ] `CatalogScope.KIWIX_BOOKS`, the "Kiwix libraries" link and its source screen. `scopeOf` puts `content == "books"` packs under Gutenberg or Kiwix libraries.
- [ ] Readiness, onboarding, presets, use cases and storage look across scopes (Decision 6).
- [ ] Tests:
  - `CatalogScopeTest`;
  - `SourceViewModelTest` (the Kiwix list has no book packs; Kiwix libraries lists the 9);
  - `ReadinessModelTest` (Food's essentials still list Water and Food for Preppers);
  - `GetMoreScreenTest`.
- [ ] **Commit.** `Get more: Kiwix libraries under Books; essentials still counted`

### Task 5: One row per pack, in plain words

- [ ] Grouping by `zimName` with the flavour choice (Decision 8).
- [ ] Language names, month-year dates and short licences; the 10 GB check (Decision 9).
- [ ] Tests in `SourceViewModelTest` and `SourceScreenTest`:
  - one row for three Wikipedia flavours;
  - Get takes the chosen flavour;
  - "English, Russian";
  - "March 2026";
  - the 10 GB dialog with free space and a smaller-flavour offer.
- [ ] ThemeShots of a grouped row and the dialog. Review them.
- [ ] **Commit.** `Kiwix catalog: one row per pack, in plain words, with a size check`

### Task 6: Folded families and better search

- [ ] The family → label table, folded rows and their A–Z lists, and the Maps row (Decision 10).
- [ ] Any-order, all-words, accent-folded search across the fields, with matches from inside folded families (Decision 11).
- [ ] Tests:
  - folded rows and counts;
  - "python" finds Python Docs from the top list;
  - "medicine wiki" in either order;
  - "Encyclopédie" with or without its accent;
  - Maps leaves the list.
- [ ] Full suite, then build.
- [ ] **Commit.** `Kiwix catalog: big families folded, search in any order`

### Task 7: Walkthrough (Neal, Pixel 6)

- [ ] Install over `app.granger`. The five nautilus packs on the phone test the migration, the scraper backfill and adoption for real.
- [ ] Neal checks:
  - the Library shows Food for Preppers, Medical Library, Post Disaster, Water and Prunelle as collections, and a PDF opens, with any earlier place kept;
  - a link inside the pack page opens the same book;
  - Readiness's Food still lists them;
  - Get more → Books → Kiwix libraries;
  - in Browse's Kiwix list:
    - no book packs;
    - one row per pack with its flavour choice;
    - plain words;
    - Developer docs, Q&A and Maps folded;
    - "python" and two-word searches;
    - a Get over 10 GB asks first.
- [ ] Fix what the walkthrough finds. Record the result in the roadmap's KB row and a "Carried forward from KB" section.
- [ ] Merge and push only on Neal's say-so.
