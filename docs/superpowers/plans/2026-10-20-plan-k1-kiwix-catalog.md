# Plan K1: The full Kiwix catalog (English) — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Before the project 2 spike, the Kiwix source lists Kiwix's whole English catalog instead of the 18 hand-picked packs. The hand-picked packs stay on top as "Recommended". Video packs and packs with no known license are left out for now.

**Architecture:**
- **`tools/catalog-gen`:** a new family table, `kiwix_families.json`, maps each Kiwix pack family (by name prefix) to a Granger category, a license, a non-commercial flag and a hold (video). `catalog_gen.py build` makes one bulk OPDS fetch, keeps English packs whose family has a row and no hold, pins each one through its metalink (fetched in parallel), and lays the `curated.json` rows over it. The build prints a report of what it kept and what it left out, and why.
- **`:core:catalog`:** `PackEntry` gains `featured` (and `family`). The cache cap rises from 4 MB to 16 MB, for when the other languages come.
- **`:app` sources:** the Kiwix screen shows Recommended first, then categories. Each category shows its first 8 packs with "Show all N", as Search does. Per-row status is worked out from an index rather than a scan per row.

**Tech Stack:** unchanged. Python 3 (stdlib + `cryptography`, already in `requirements.txt`; the parallel fetch uses `concurrent.futures`). Kotlin/Compose/Robolectric as before. No new app dependencies.

**Roadmap:** `docs/superpowers/plans/2026-10-15-granger-v1-roadmap.md` (Plan 3's "regenerate and re-sign monthly" note; the P1 open items). Spec change: `2026-10-16-library-redesign-design.md` §5 "Kiwix" and §11 said v1 lists the curated packs only. Neal reversed that on 2026-10-20 for English.

## What the catalog and the code look like now (surveyed 2026-10-20, `main` at `10fe7fb`)

**Kiwix's catalog** (`https://library.kiwix.org/catalog/v2/entries?count=-1`, fetched 2026-10-20; the old host now 301s, so follow redirects):
- **3,657 entries**, one per name and flavour (no duplicates), **5.45 TB** in all. They come in 707 language codes; 1,311 include `eng`, and 378 list several languages (`eng,fra`).
- Kiwix categories: wikipedia 1,227, none 877, ted 357, stack_exchange 181, wikibooks 152, wiktionary 148, wikiquote 135, wikisource 132, phet 114, other 112, gutenberg 103, and smaller ones. The uncategorised long tail is mostly `devdocs_*` (243), `maps_*` (193), `avanti_*`, `freecodecamp_*`, `libretexts.org_*`, plus one-off community wikis.
- **There's no license field.** Hence the family table.
- **`_videos:yes` can't be trusted:** packs with no video carry it, including `gutenberg_en_lcc-b` (in the catalog today), `devdocs_*` and `libretexts.org_*`. Video packs are held back by **family** instead (TED, C'est pas sorcier, VOA, coreyms, canadian-prepper and others; Task 1 checks each one).
- `_ftindex:yes` is on 2,710 of 3,657.
- One metalink fetch takes about 0.5–1 s. Wikipedia metalinks run up to 155 KB because of their mirror lists.
- English packs with no video tag: 532 (0.9 TB). Counting the mis-tagged families (devdocs, Gutenberg, LibreTexts), English before the license gate is about **830**.

**The generator** (`tools/catalog-gen/catalog_gen.py`): `pack_entry` makes **one OPDS query per curated row** (`?name=…&count=50`), takes the newest match, fetches its metalink, checks its size against OPDS, and pins the sha256 with up to 3 https URLs (canonical plus your.org and wikimedia mirrors). `title`, `category` and `license` come from the curated row. `build` checks `SAFE_NAME` ids, unique ids and presets. The output is `catalog/catalog.json`, **246 KB**: 18 packs, 3 models, 347 books and 2 presets. A pack entry is about 700–800 bytes.

**The app:**
- `CatalogRepository` (`core/catalog/.../CatalogRepository.kt`) refuses catalogs over **`MAX_BYTES` = 4 MB**. The parser uses `ignoreUnknownKeys`, so new fields are safe for builds already installed.
- `scopeOf` (`app/.../sources/CatalogScope.kt`) treats any pack with a `kiwix.org` URL as Kiwix's, so new packs land on the Kiwix screen with no change.
- `SourceViewModel.buildUi` groups packs by category in one `LazyColumn` (`SourceScreen.kt`). It calls `statusOf` twice per item on every emission (download progress included), and each call filters every download record. `CATEGORY_ORDER` names "Science" and "Repair", which the catalog doesn't use (it uses "Science & Math" and "How-to"), so those sort last.
- `statusOf` and `installedPackId` match on `id` (`name_flavour`), `sha256` and `zimUuid`. Ids from the bulk path use the same scheme, so the installed curated packs keep matching.
- `Filing` (`core/library/.../Filing.kt`) files Wikipedia, Wikisource, WikEM, Gutenberg, iFixit and Stack Exchange into family collections. Every other pack gets its own `pack:<name>` collection.
- Research's catalogue cards come from **installed** packs (`ResearchViewModel.kt:327`), not from the catalog. A bigger catalog shouldn't reach Research; Task 4 confirms this.

## Decisions

Neal's (2026-10-20):
1. **English only for now.** A pack is in if its language list includes `eng`. Other languages arrive with app-wide language support in their own delivery. The generator takes the languages as a setting (`"languages": ["eng"]`), so that delivery changes one line.
2. **No video packs yet.** They're held back by a `hold: "video"` row in the family table, not dropped silently. When the video media type is built, that project starts from the held list (see Task 7).
3. **Licenses come from a family table; a pack whose family has no row is left out.** Nothing ships with an unknown license before Plan 11's Play policy check. The build report lists the families left out, so adding one later means adding a row.

Builder's calls (for Neal's review):
- **The curated rows win.** For the same name and flavour, the curated title, category and license replace the family table's, and the entry is `featured: true`. The 18 packs keep their ids, titles and categories exactly.
- **Recommended:** the featured packs are their own top section, "Recommended", on the Kiwix screen, in curated order. They don't appear again under their categories, so no pack shows twice.
- **Description:** Kiwix's `summary` as it is. A curated `description` (new, optional) overrides it. That's the fix for iFixit's "Let's …" (P1 open item).
- **Categories:** the family table gives each family a Granger category from the existing set (Medical, Reference, Religion & Philosophy, Literature & Drama, Science & Math, How-to, Regional) plus **Q&A** (Stack Exchange), **Programming** (devdocs, if kept) and **Maps**. A pack whose family has no category falls under "Other".
- **Section size:** each category shows its first **8** packs, ordered by title, with "Show all N" (Search's pattern). A search term opens every section.
- **Filing:** add family collections for Wiktionary, Wikivoyage, Wikibooks, Wikiquote, Wikiversity, PhET, Maps and devdocs, in `Filing.RULES`. Everything else keeps its own `pack:` collection.
- **One file, not a separate Kiwix catalog:** about 830 entries make roughly 0.9 MB, which fits under the 4 MB cap that installed builds have. The cap rises to 16 MB now, so the all-languages delivery (about 3 MB) has room without splitting the file.
- **Build speed:** one bulk OPDS fetch replaces the per-row queries. Metalinks are fetched with **8 workers** and 3 retries, about 2 minutes in all. A pack whose metalink fails after its retries is left out and reported. The build doesn't fail unless a **curated** pack fails.

## Draft family table (Task 1 verifies every row against the source's own license page)

| Family (name prefix) | Category | License | NC | Hold |
| --- | --- | --- | --- | --- |
| `wikipedia_` (incl. `wikipedia_en_medicine`) | Reference (Medical for medicine) | CC BY-SA 4.0 | | |
| `wiktionary_`, `wikiquote_`, `wikibooks_`, `wikiversity_`, `wikispecies_` | Reference | CC BY-SA 4.0 | | |
| `wikisource_` | Literature & Drama | CC BY-SA 4.0 | | |
| `wikivoyage_` | Regional | CC BY-SA 4.0 | | |
| `wikem_` | Medical | CC BY-SA 3.0 | | |
| `mdwiki_` | Medical | CC BY-SA 4.0 (to verify) | | |
| `gutenberg_` | Literature & Drama / Religion & Philosophy | Public domain (US) | | |
| Stack Exchange (category `stack_exchange`) | Q&A | CC BY-SA 4.0 (older posts 3.0/2.5) | | |
| `phet_` | Science & Math | CC BY 4.0 | | |
| `ifixit_` | How-to | CC BY-NC-SA 3.0 | ✓ | |
| `libretexts.org_` | Science & Math | mostly CC BY-NC-SA (per book; to verify) | ✓ | |
| `maps_` | Maps | ODbL (OpenStreetMap) | | |
| `devdocs_` | Programming | **mixed, per project**: left out unless Task 1 finds per-pack licenses | | |
| `vikidia_` | Reference | CC BY-SA (to verify) | | |
| `ted_`, `cest-pas-sorcier_`, `voa_`, `coreyms_`, `canadian-prepper_`, `phzh_`, others with real video | — | — | | video |
| one-off community wikis (`rationalwiki`, `archlinux`, `uesp.net`, game wikis…) | per row | per wiki's footer | | |

Anything not in the table is left out and listed in the report.

## Global Constraints

- **Don't break installed packs or downloads.** Every curated pack keeps its `id`, `zimUuid` match and title. The catalog can never step backwards (`OLDER_THAN_CACHED`). Builds already on phones must parse the new catalog, so new fields are optional with defaults, and the file stays under 4 MB.
- **Signed as before:** build, then `sign --key ~/.config/granger/catalog-signing.key`, then `verify` against `catalog/public-key.txt`. Never commit the key.
- **Publishing is Neal's go-ahead:** pushing `catalog/catalog.json` to `main` updates every installed app within minutes (raw.githubusercontent). Ask before that push.
- **Words in the UI:** "Recommended", "Show all N packs", "Q&A", "Maps", "Programming". Non-commercial packs keep "· provided by Kiwix".
- JVM tests: `./gradlew testDebugUnitTest` and `./gradlew :core:research:test` pass (1593 before this plan). Python: `python3 -m pytest tools/catalog-gen` in the eval venv (the Mac's system Python has no pytest). No network in tests: fixtures under `tools/catalog-gen/testdata/`.
- Build env: `JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home`, `ANDROID_HOME=/opt/homebrew/share/android-commandlinetools`. In a new worktree, copy `local.properties` and run `git submodule update --init --recursive`.

## Review Focus

- Every license row has a source link in the table file (`"source": "<url>"`). No row is guessed.
- The diff of `catalog.json` for the 18 curated packs is empty apart from `featured` (and the iFixit description).
- The Kiwix screen stays smooth with about 800 rows during a running download (status indexing).

### Task 1: The family table

**Files:** create `tools/catalog-gen/kiwix_families.json`. Report in the task notes.

- [ ] List every English family in today's OPDS feed (prefix, count, size, a sample title) as a scratch report.
- [ ] For each family: open one pack's source site and record its license with the URL. Decide its category, and its hold if it has real video (play one sample in a desktop Kiwix reader, or check the ZIM for `.webm`/`.mp4` entries).
- [ ] Write the table: `{"families": [{"match": "^wikipedia_", "family": "wikipedia", "category": "Reference", "license": "CC BY-SA 4.0", "nonCommercial": false, "hold": null, "source": "https://…"}, …]}`. The first match wins; `match` is a regex on the ZIM name, or `{"kiwixCategory": "stack_exchange"}`.
- [ ] **Neal reviews the table** before Task 2 lands (devdocs, LibreTexts, maps and the community wikis especially).

### Task 2: The generator builds from the whole feed

**Files:** `tools/catalog-gen/catalog_gen.py`, `curated.json`, `test_catalog_gen.py`, `testdata/` (an OPDS fixture with about 12 entries covering each case, plus metalink fixtures).

- [ ] `curated.json` gains `"kiwix": {"languages": ["eng"], "families": "tools/catalog-gen/kiwix_families.json"}`. Curated pack rows may add `description`.
- [ ] `kiwix_entries(config, fetch)`: one fetch of `entries?count=-1` (following redirects), parsed with the existing `opds_entries`, plus a `category` field. Keep an entry when a configured language is in its comma-split `language` and its family has a row with no hold.
- [ ] Refactor `pack_entry` into `pin(entry, overrides)`: the metalink, the size check and the URLs, with title, category, license and NC from the curated row, else the family row. Curated rows still go through the bulk list. A curated row missing from the feed still fails the build.
- [ ] Parallel metalinks (`ThreadPoolExecutor(8)`, 3 tries with backoff). A failed non-curated pack is reported and skipped. An unsafe id is reported and skipped.
- [ ] Output: packs sorted with the featured ones first (in curated order), then by category and title. `featured` and `family` on every Kiwix entry.
- [ ] `build` prints the report: kept per family and category, held (video) per family, no-row families with counts, and failures. It also writes it to `catalog/kiwix-report.txt` (committed, so the next language or video project has a starting point).
- [ ] Tests: language filter (including multi-language), family match order, hold, no-row skip, curated override and `featured`, a curated pack missing from the feed fails, a metalink failure skips, unsafe id, sorted output, and the description override.

### Task 3: The catalog model

**Files:** `core/catalog/.../Catalog.kt`, `CatalogRepository.kt`, tests in `core/catalog/src/test` (`PublishedCatalogTest`, `CatalogParserTest`, `TestCatalogs.kt`).

- [ ] `PackEntry`: `featured: Boolean = false`, `family: String = ""`.
- [ ] `MAX_BYTES` from 4 MB to 16 MB.
- [ ] Tests: an old catalog without the fields parses; a 1,000-entry synthetic catalog parses in well under a second on the JVM; `PublishedCatalogTest` expects the new counts (it reads the checked-in file, so it runs after Task 5's build).

### Task 4: The Kiwix screen at size

**Files:** `app/.../sources/SourceViewModel.kt`, `SourceScreen.kt`, `SourceViewModelTest.kt`, `CatalogScopeTest.kt`.

- [ ] Index the records by `itemId` and the packs by `id` once per emission, and work out each item's status once (it's used twice now).
- [ ] Sections: "Recommended" (featured, Kiwix scope only), then categories in `CATEGORY_ORDER`, which is fixed to the real names plus Q&A, Maps, Programming and Other. Each shows 8 with "Show all N packs". A query opens all of them. Search also matches `zimName` (so "stackoverflow" finds Stack Overflow).
- [ ] Granger and Models screens are unchanged (test).
- [ ] Confirm by test or by reading the code that Research and Search use installed packs only, and record where.
- [ ] Tests: Recommended first, with no pack shown twice; the fold and Show all; a query unfolds; status still right for installed, update-available and downloading packs; the category order.

### Task 5: Filing for the new families

**Files:** `core/library/.../Filing.kt`, its test.

- [ ] Add the family rules (Decision list above). Existing filed packs are untouched: rules apply to new packs only, as today.
- [ ] Tests for each new rule, and that a Wikipedia subset still lands in Wikipedia.

### Task 6: Build, sign, publish

- [ ] Run the build. Review `kiwix-report.txt` and the catalog's size (expect about 0.9 MB) with Neal.
- [ ] Diff the 18 curated entries against today's catalog: only `featured`, `family` and the iFixit description change (snapshots may have moved, which is fine).
- [ ] Sign and verify. Run `PublishedCatalogTest` and the full JVM suites.
- [ ] **With Neal's go-ahead**, commit and push the catalog to `main`.

### Task 7: Pixel 6 walkthrough; roadmap

Installed over the app (`./gradlew :app:installDebug`), library DB backed up first.
- [ ] The Kiwix screen: Recommended on top; scroll through every category with a download running (no jank); Show all; search "python", "stackoverflow", "chemistry".
- [ ] Download and open one small pack from each new kind: a Stack Exchange site, a `maps_` pack (does the map viewer work offline in the WebView?), a PhET pack (simulations are HTML5), and a community wiki. Check where each one is filed.
- [ ] An installed curated pack (WikiMed) still shows as Installed; Updates is right.
- [ ] Offline with the old cached catalog, then online: the new one replaces it.
- [ ] Roadmap: add a K1 row and a "Carried forward from K1" section with the counts, the families left out, and anything the walkthrough found. Under the video work, list the held video families (from `kiwix-report.txt`) and what including them would take: the hold rows come out; the WebView plays the ZIM's HTML5 video (TED packs use video.js with `.webm`), to be checked on the Pixel; `Filing.defaultMediaType` would return video for those families; and the sizes are big (TED English alone is hundreds of packs). Update `2026-10-16-library-redesign-design.md` §5 and §11 to point at this plan.

## Order and size

Task 1 comes first and needs Neal's review. Tasks 2–5 can then go in parallel (2 is the largest; 3, 4 and 5 are small). Task 6 needs 2–5; Task 7 needs 6. That's about a day of building plus Neal's table review and the walkthrough.
