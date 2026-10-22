# Plan H1: The readiness model — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Readiness answers "what knowledge do I hold offline?". Nine knowledge domains, each with a status (None, Basic, Comprehensive), a bar of installed bytes against the comprehensive tier, and one-sentence signals with an action; storage by source under it; the integrity rows kept under "Files". The model is catalog data: the family table says which domain each Kiwix family serves, `curated.json` says which packs make the essential and comprehensive tiers, the generator writes both into the signed catalog, and the app reads them from the cache. Tapping a domain opens the Kiwix screen filtered to it. Spec: `docs/superpowers/specs/2026-10-21-home-readiness-onboarding-design.md` §3, §4, §7–§10.

**Out of this plan:** Home (H2), onboarding (H3), the region and use-case tables (H3), enqueuing downloads from Readiness (signals navigate), Slim and Park (S1), video families, the monthly Kiwix refresh (it rides along when the catalog is next regenerated).

**Architecture (what changes):**
- **`tools/catalog-gen`:** `kiwix_families.json` rows gain `domain` (one of nine ids or `null`); Stack Exchange gets per-site rows before its catch-all. `curated.json` gains `readiness` (domains in order; `essential` and `comprehensive` items per domain as ids, `@collection` or `family:` tokens). `catalog_gen.py` writes `domain` on every pack (from its row) and book (from its Classics category), a top-level `readiness` object with the tokens validated and the essential items folded into comprehensive, and two generated presets, `efficient` and `comprehensive`.
- **`:core:catalog`:** `PackEntry.domain`, `BookEntry.domain` (blank in older catalogs), `Catalog.readiness: Readiness?` with `Domain(id, title, essential, comprehensive)`; the parser drops tokens that name nothing in the catalog.
- **`:app` readiness:** `ReadinessModel` (a pure function over the catalog, the library's packs and books, and the download records) gives `DomainCoverage` per domain, `Signal`s and `StorageBySource`; `ReadinessViewModel` combines it with the integrity rows; `ReadinessScreen` draws the chart, signals, storage by source, then "Files".
- **`:app` sources:** `SourceRoute(scope, domain)`; `SourceViewModel` filters the Kiwix catalog to a domain and shows its tier items first.
- **`catalog/`:** regenerated and re-signed, with the new fields.

**Tech Stack:** unchanged: Kotlin 2.4.21, Compose Material 3, kotlinx.serialization (`ignoreUnknownKeys`), Room 2.8.5 (no migration: nothing new is stored on the phone), Robolectric 4.17, JUnit 4 + Truth; Python 3.14 in `~/.venvs/granger-catalog` (`cryptography`, `pytest`). Build env: `JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home`, `ANDROID_HOME=/opt/homebrew/share/android-commandlinetools`; a new worktree needs `local.properties` copied in and `git submodule update --init --recursive`.

**Roadmap:** `docs/superpowers/plans/2026-10-15-granger-v1-roadmap.md`, row H1, between V2 and Actions.

## What the code looks like now (surveyed 2026-10-22, `main` at `b04f9d7`)

- **Family table:** `tools/catalog-gen/kiwix_families.json` has 182 rows (`family`, `match` regex or `{"kiwixCategory": …}`, `category`, `license`, `nonCommercial`, `noDerivatives`, `hold`, `source`, `note`). `family_of(entry, families)` returns the first matching row. `kiwix_entries` pins every English pack whose row has a license and no hold; curated packs are pinned with `featured: True` and their row's `family`. `pin()` copies `category`, `license`, `nonCommercial`, `featured`, `family` into the entry. Categories: Programming 32 families, Games & Hobbies 31, How-to 31, Science & Math 21, Reference 19, Literature & Drama 15, Medical 12, Other 12, Regional 4, Maps 2, Religion & Philosophy 2, Q&A 1 (Stack Exchange, 181 packs under one row).
- **Curated:** `curated.json` has `kiwix` (languages, families path), 18 `packs`, one `books` row (the Classics release descriptor; 347 EPUBs with categories Novels & stories 161, Drama 80, Philosophy 51, Epic & poetry 30, Opera libretti 13, Scripture & religion 12), 3 `models`, the `starter` preset (models + WikiMed nopic + Wikipedia top nopic + `@granger-classics`), one voice. `build()` expands `@collection` tokens in presets and adds a `granger-classics` preset.
- **Catalog:** 927 packs, 347 books, 3 models, 1 voice, 2 presets; 0.92 MB; `formatVersion` 1. `PackEntry` has `category`, `family`, `zimName`, `zimUuid`, `hasFulltextIndex`, `snapshotDate`, `featured`, `nonCommercial`; `BookEntry` has `category`, `collection`. `CatalogParser` uses `ignoreUnknownKeys`, drops invalid entries, and trims presets to known ids. Nothing in the app reads `family`.
- **Readiness:** `ReadinessViewModel(downloads, library, volumes, tier, totalRamBytes, appScope)` combines `downloads.records`, `library.packs`, volumes, progress and results into `ReadinessUi(tier, totalRamBytes, volumes, models, voices, packs, media)` of `CheckRow`s; the quick integrity check runs inside the `combine` on `io`. `ReadinessScreen` lists This phone, Storage, AI models, Voices, Packs, Music & audiobooks. Tests: `ReadinessViewModelTest` (12 tests) on `FakeDownloads`, `FakeLibrary`, `FakeVolumes`.
- **Installed ↔ catalog:** `statusOf(item, records, packs, tier)` and `installedPackId(entry, records, packs)` in `sources/ItemStatus.kt`: a pack is installed when a record carries its sha256 or a library `Pack` has its `zimUuid`. Library `Pack.name` is the ZIM `Name` (= `zimName`). `Bookshelf.books` gives `Book(origin: GRANGER | IMPORT | ARCHIVE_ORG, sourceRef, sizeBytes, status)`.
- **Kiwix screen:** `SourceRoute(scope: CatalogScope)`; `SourceViewModel` builds `SourceUi(categories, category, sections…)`; the chip row is `FilterChip`s in `SourceScreen.kt` (lines 134–150); `setCategory(String?)`. Recommended = `featured` packs when no chip is chosen.
- **Container:** `AppContainer.catalog: CatalogSource` (cached catalog under `files/catalog/`), `library`, `bookshelf`, `downloads`, `volumes`, `deviceTier`, `totalRamBytes`, `appScope`. Readiness is wired in `GrangerNavHost.kt` (`composable<ReadinessRoute>`); `onReadiness` comes from Settings and the Library top bar.
- Tests: 1730+ JVM tests pass (`./gradlew testDebugUnitTest` plus `./gradlew :core:research:test`); 28 catalog-gen tests (`~/.venvs/granger-catalog/bin/pytest tools/catalog-gen`).

## Decisions

1. **Tiers live in `curated.json` by pack id; domains live in the family table.** A family spans tiers (Wikipedia), a tier item is one pack; the table can't carry it. Both are data: no domain or pack id is hard-coded in the app.
2. **Three token kinds** in a tier list: a catalog id; `@<collection>` (the collection's books whose own `domain` is this domain); `family:<name>` (any pack of the family). The generator validates ids and collections at build time; the app validates again at parse time and drops what it doesn't know.
3. **Comprehensive contains essential.** The generator folds the essential items into the comprehensive list, so the app checks one list for Comprehensive and one for Basic.
4. **Flavours satisfy each other.** A tier item `wikipedia_en_medicine_nopic` is satisfied by any installed pack whose `zimName` is `wikipedia_en_medicine`; the bytes are the installed file's. Resolution of an installed pack to a catalog entry: `zimUuid`, then `zimName` (first catalog pack with that name), then the family regexes are **not** re-run on the phone (the catalog's `zimName` match covers every flavour); a pack with no match is "Other".
5. **Only whole files count.** Packs with status OK, books with status OK, records `INSTALLED`. Queued, missing and corrupt files count for nothing; a corrupt pack shows in Files as today.
6. **Signals navigate, never download.** The Readiness screen stays read-only; the action opens the Kiwix screen at the domain (the item in view). Enqueuing from Readiness would duplicate `SourceViewModel.enqueue`'s volume and Wi-Fi rules.
7. **The chart is Compose, not Canvas:** a `Box` with two weighted fills and a 1 dp tick. No chart library.
8. **Classics as one item per domain**: `@granger-classics` under `literature` is satisfied for Basic by one installed Classics book with domain `literature`, for Comprehensive by all of them. Imported and archive.org books never count toward a domain (spec §3.3).
9. **Format version stays 1.** Every new field has a default; an older catalog in the cache shows "Refresh the catalog to see your coverage." in place of the rows.
10. **Catalog contention:** R1 Task 10 adds `vectors` to the same files. Whoever regenerates second builds on the other's `curated.json`; the R1 session is told when Task 6 lands. The signing key is on the Mac (`~/.config/granger/catalog-signing.key`); a re-sign is live only once pushed to `main`.

## Global Constraints

- Use `./gradlew testDebugUnitTest` for JVM tests (`:core:catalog`, `:app`); `~/.venvs/granger-catalog/bin/pytest tools/catalog-gen` for the generator.
- The Readiness computation runs inside the existing `combine` on `io`; it must not touch the network or the disk beyond what the quick check already does.
- No schema migration. No new dependency.
- The project 2 spec's domain ids are fixed: `medicine, food, making, science, technology, maps, reference, literature, thought`. Renaming one means telling Neal and the R1 session.
- The phone is held by other sessions; nothing in Tasks 1–6 needs it. Task 7 waits for it.
- Commit after every task with the message given.

## Review Focus

- Does every family row have a `domain` key, and does every Stack Exchange site land in a sensible domain? (A data review, not a code review: `pytest` checks the keys, a person checks the sense.)
- Does `ReadinessModel` ever count a file that isn't whole and installed?
- Does an older cached catalog (no `domain`, no `readiness`) still show the integrity rows and a clear message?
- Does the Kiwix screen's domain filter leave the category chips exactly as K2 left them when no domain is set?

---

### Task 1: The family table, the readiness block and the generator

**Files:**
- Modify: `tools/catalog-gen/kiwix_families.json` (`domain` on all 182 rows; new Stack Exchange rows before the `kiwixCategory` catch-all; `_about` updated), `tools/catalog-gen/curated.json` (`readiness` block per spec §3.5), `tools/catalog-gen/catalog_gen.py`, `tools/catalog-gen/test_catalog_gen.py`

**Data (spec §3.5, §3.6):**
```json
"readiness": {
  "domains": [
    {"id": "medicine", "title": "Medicine & first aid",
     "essential": ["wikipedia_en_medicine_nopic"],
     "comprehensive": ["wikipedia_en_medicine_maxi", "wikem_en_all_maxi", "medlineplus.gov_en_all", "zimgit-medicine_en", "wwwnc.cdc.gov_en_all"]},
    {"id": "maps", "title": "Maps, places & travel",
     "essential": ["family:maps", "wikivoyage_en_all_nopic"], "comprehensive": ["wikivoyage_en_all_maxi", "openstreetmap-wiki_en_all_maxi", "wikipedia_en_geography_maxi", "travel.stackexchange.com_en_all"]},
    {"id": "literature", "title": "Literature & the arts",
     "essential": ["@granger-classics"], "comprehensive": ["wikiquote_en_all_nopic", "wikisource_en_all_nopic", "gutenberg_en_lcc-pr", "literature.stackexchange.com_en_all", "writing.stackexchange.com_en_all"]}
  ]
}
```
A Stack Exchange row: `{"family": "stack_exchange", "match": "^(cooking|gardening|homebrew|outdoors|vegetarianism|coffee|alcohol|sustainability)\\.stackexchange\\.com_", "category": "Q&A", "license": "CC BY-SA 4.0", "nonCommercial": false, "noDerivatives": false, "hold": null, "source": "<same as the catch-all>", "domain": "food"}`; the catch-all keeps `{"kiwixCategory": "stack_exchange"}` with `"domain": "technology"`.

**Generator:**
- `pin()` copies `row.get("domain")` into the entry as `domain` (a string; `null` rows write `""`). Curated packs take the domain of the family row their sample matches (as `family` is taken today).
- `book_entries()` sets `domain` per book: category `Scripture & religion` or `Philosophy` → `thought`, else `literature` (a `BOOK_DOMAINS` table at the top of the file, so the rule is one place).
- `build()` reads `curated["readiness"]`, checks every id token is a catalog id, every `@` token a known collection, every `family:` token a family with at least one listed pack (else `SystemExit` naming the token and domain); folds `essential` into `comprehensive` (order kept, no duplicates); writes `catalog["readiness"] = {"domains": [...]}`; generates presets `efficient` ("Efficient start": every essential id token plus the `@collection` tokens expanded to the collection's books **of that domain**; `family:` tokens left out) and `comprehensive` ("Comprehensive library": the same over the comprehensive lists). Domain ids must be unique and non-empty; a pack whose `domain` is not one of the block's ids (or blank) fails the build, so a typo in the table is caught.
- The Kiwix report gains a "By domain" section: packs listed per domain, and the families with `domain: null`.

- [ ] **Step 1: Failing tests** in `test_catalog_gen.py`: `test_pin_carries_the_rows_domain` (a row with `domain: "medicine"` → entry `domain == "medicine"`; a row with `domain: None` → `""`), `test_stack_exchange_sites_split_by_their_own_rows` (feed entries `cooking.stackexchange.com_en_all` and `python.stackexchange.com_en_all` under Kiwix category `stack_exchange`; the first row matches cooking → `food`, the catch-all gives `technology`; both keep `family == "stack_exchange"`), `test_books_take_their_domain_from_their_category` (Scripture → `thought`, Philosophy → `thought`, Drama → `literature`), `test_readiness_block_is_written_with_essential_folded_into_comprehensive`, `test_readiness_rejects_an_unknown_token` (`SystemExit` matching the token), `test_readiness_rejects_a_pack_with_an_unknown_domain`, `test_efficient_and_comprehensive_presets_come_from_the_tiers` (the Classics token expands to the domain's books only; `family:` tokens are left out), `test_real_family_table_has_a_domain_on_every_row` (loads the repo's `kiwix_families.json`: every row has a `domain` key whose value is one of the nine ids or `None`; the Stack Exchange catch-all is the last `stack_exchange` row), `test_real_curated_readiness_names_nine_domains_in_order` (ids and order equal the spec's; every domain has at least one essential item).
- [ ] **Step 2:** `~/.venvs/granger-catalog/bin/pytest tools/catalog-gen -q`. Expected: the new tests fail (KeyError on `domain`, missing `readiness`).
- [ ] **Step 3: Implement.** The table edit is a script run once (`tools/catalog-gen/add_domains.py` is **not** committed; the edited JSON is): assign by the spec §3.6 rules, then hand-check the How-to, Other and Games & Hobbies rows and write the Stack Exchange rows. Keep the file's key order and 2-space indent so the diff reads. Then `curated.json`'s `readiness` block (spec §3.5 in full), then `catalog_gen.py`.
- [ ] **Step 4:** `pytest -q`. Expected: PASS (37 tests).
- [ ] **Step 5:** A local build to prove the data against the live Kiwix feed, without signing: `~/.venvs/granger-catalog/bin/python tools/catalog-gen/catalog_gen.py build --curated tools/catalog-gen/curated.json --out /tmp/granger-h1/catalog.json` (about 5 minutes; network). Read the report's "By domain" section; fix any family whose packs land oddly. Don't commit the built catalog yet (Task 6 does, after the app side is in).
- [ ] **Step 6: Commit.** `Catalog-gen: a domain per family, the readiness tiers, efficient and comprehensive presets`

### Task 2: `:core:catalog` — `domain` and `readiness`

**Files:**
- Modify: `core/catalog/src/main/kotlin/app/granger/core/catalog/Catalog.kt`, `CatalogParser.kt`
- Modify tests: `core/catalog/src/test/kotlin/app/granger/core/catalog/CatalogParserTest.kt`, `TestCatalogs.kt` (`packExtra` already exists; add a `readiness` argument to `catalogJson`), `PublishedCatalogTest.kt` (the real `catalog/catalog.json` parses with `readiness` once Task 6 lands; until then the test only checks the fields are optional)

**Interfaces:**
```kotlin
@Serializable data class PackEntry(…, /** One of the readiness domain ids, or blank: counts in storage only (spec §3.3). Absent in older catalogs. */ val domain: String = "")
@Serializable data class BookEntry(…, val domain: String = "")

/** The readiness model (spec §3): domains in screen order, each with its tier item tokens. */
@Serializable data class Readiness(val domains: List<Domain> = emptyList())
@Serializable data class Domain(
    val id: String, val title: String,
    /** Tokens: a catalog id, "@<collection>" or "family:<name>" (spec §3.2). */
    val essential: List<String> = emptyList(),
    /** Includes [essential] (the generator folds them in). */
    val comprehensive: List<String> = emptyList(),
)
@Serializable data class Catalog(…, /** Absent in older catalogs. */ val readiness: Readiness? = null)
```
The parser keeps a domain only if its `id` and `title` are non-blank and the id is unique; a token is kept if it names a catalog pack or book id, a collection with at least one book, or a family with at least one pack; essential tokens missing from comprehensive are added. A `readiness` with no domains left becomes null.

- [ ] **Step 1: Failing tests:** `packsAndBooksCarryTheirDomain`, `olderCatalogsHaveBlankDomainsAndNoReadiness`, `readinessKeepsOnlyTokensTheCatalogKnows` (an unknown id, an empty collection and an unknown family are dropped; a known id, `@granger-classics` and `family:maps` are kept), `readinessFoldsEssentialIntoComprehensive`, `readinessDropsDomainsWithBlankIdsOrDuplicates`, `readinessWithNothingLeftIsNull`.
- [ ] **Step 2:** `./gradlew :core:catalog:testDebugUnitTest`. Expected: compile failure.
- [ ] **Step 3: Implement** the data classes and the parser's `readiness(root, packs, books)` step after the presets.
- [ ] **Step 4:** `./gradlew :core:catalog:testDebugUnitTest`. Expected: PASS.
- [ ] **Step 5: Commit.** `Catalog: a domain on every pack and book; the readiness model`

### Task 3: `ReadinessModel` — coverage, signals, storage by source

**Files:**
- Create: `app/src/main/kotlin/app/granger/readiness/ReadinessModel.kt`
- Create tests: `app/src/test/kotlin/app/granger/readiness/ReadinessModelTest.kt`

**Interfaces:**
```kotlin
enum class Coverage { NONE, BASIC, COMPREHENSIVE }

data class DomainCoverage(
    val id: String, val title: String, val coverage: Coverage,
    val installedBytes: Long, val essentialBytes: Long, val comprehensiveBytes: Long,
    /** Essential items not installed, as catalog items, smallest first (for the first signal). */
    val missingEssential: List<CatalogItem>,
)

sealed interface Signal {
    val text: String
    /** An empty domain: "No medical reference. Get WikiMed (no pictures), 1.05 GB." */
    data class EmptyDomain(val domain: String, val item: CatalogItem, override val text: String) : Signal
    /** An installed pack over a year old with a newer snapshot in the catalog. */
    data class Stale(val domain: String?, val packId: String, override val text: String) : Signal
    /** An installed pack with no full-text index. */
    data class Unsearchable(val packId: String, override val text: String) : Signal
}

enum class Source(val title: String) { KIWIX("Kiwix"), GRANGER("Granger"), ARCHIVE_ORG("Archive.org"), LIBRIVOX("LibriVox"), MY_BOOKS("My books"), MODELS("AI models"), VOICES("Voices"), PHONE_MEDIA("Music & audiobooks on the phone") }
data class StorageRow(val source: Source, val bytes: Long, val items: Int, /** True for PHONE_MEDIA: referenced, not stored. */ val referencedOnly: Boolean = false)

data class ReadinessReport(val domains: List<DomainCoverage>, val signals: List<Signal>, val storage: List<StorageRow>, /** The catalog predates the model. */ val needsCatalogRefresh: Boolean)

object ReadinessModel {
    fun report(catalog: Catalog?, packs: List<Pack>, books: List<Book>, records: List<DownloadRecord>, today: LocalDate): ReadinessReport
}
```
Rules (spec §3.3–§3.4, Decisions 4, 5, 8): an installed pack resolves to a catalog `PackEntry` by `zimUuid`, then by `name == zimName`; its domain is the entry's; its bytes are `Pack.sizeBytes`. A Granger book (`origin == GRANGER`, status OK) resolves by `sourceRef == BookEntry.id`. A tier token is satisfied as in Decision 2. Essential and comprehensive bytes sum the catalog sizes of the tokens (a `family:` token counts the smallest pack of the family; an `@collection` token the domain's books). Signals: one `EmptyDomain` per NONE domain whose essential list has a pack or book (the smallest missing essential named), in domain order; `Stale` for an installed pack whose record `snapshotDate` is over 365 days before `today` and whose catalog entry has a later `snapshotDate`; `Unsearchable` for an installed pack with `hasFulltextIndex == false`. Storage: records `INSTALLED` by kind and URL host (`kiwix.org` → KIWIX; `github.com/nealt9000` → GRANGER; `archive.org` with `groupId` starting `media:librivox:` → LIBRIVOX, other `archive.org` → ARCHIVE_ORG; MODEL → MODELS; VOICE → VOICES), plus library books with `origin == IMPORT` → MY_BOOKS, plus by-hand packs (no record) → GRANGER if their `zimName` is in the catalog's Granger scope, else KIWIX. PHONE_MEDIA is a fixed row with 0 bytes (the scanner's items aren't stored by Granger). `needsCatalogRefresh` is true when `catalog?.readiness == null`.

- [ ] **Step 1: Failing tests** (build a `Catalog` through `CatalogParser` with a JSON fixture of 3 domains, 6 packs across two flavours, 4 Classics books in two domains, a `family:maps` token and two map packs): `nothingInstalledIsNoneEverywhereWithEmptyDomainSignals`, `oneEssentialPackMakesBasic`, `theOtherFlavourOfAnEssentialPackCounts`, `everyComprehensiveItemMakesComprehensive`, `aMissingOrCorruptPackCountsForNothing`, `aFamilyTokenIsSatisfiedByAnyPackOfTheFamily`, `oneClassicsBookOfTheDomainMakesBasicAllMakeComprehensive`, `importedAndArchiveBooksNeverCountTowardADomain`, `bytesCountEveryInstalledItemOfTheDomain`, `aPackUnknownToTheCatalogIsStorageOnly`, `staleAndUnsearchableSignals`, `signalsAreOrderedEmptyThenStaleThenUnsearchable`, `storageBySourceSplitsByKindAndHost`, `anOlderCatalogAsksForARefresh`.
- [ ] **Step 2:** `./gradlew :app:testDebugUnitTest --tests '*ReadinessModelTest*'`. Expected: compile failure.
- [ ] **Step 3: Implement** `ReadinessModel` as pure Kotlin (no Android imports beyond the data classes).
- [ ] **Step 4:** run the tests. Expected: PASS.
- [ ] **Step 5: Commit.** `Readiness: the model — coverage per domain, signals, storage by source`

### Task 4: The Readiness screen

**Files:**
- Modify: `app/src/main/kotlin/app/granger/readiness/ReadinessViewModel.kt` (new constructor parameters `catalog: CatalogSource`, `bookshelf: Bookshelf`, `today: () -> LocalDate = LocalDate::now`; `ReadinessUi` gains `report: ReadinessReport?`), `ReadinessScreen.kt` (new sections; `onDomain: (String) -> Unit`, `onPack: (String) -> Unit`), `app/src/main/kotlin/app/granger/GrangerNavHost.kt` (pass `container.catalog`, `container.bookshelf`; `onDomain = { nav.navigate(SourceRoute(CatalogScope.KIWIX, domain = it)) }`)
- Create: `app/src/main/kotlin/app/granger/readiness/DomainBar.kt` (the bar composable)
- Modify tests: `app/src/test/kotlin/app/granger/readiness/ReadinessViewModelTest.kt` (`FakeCatalogSource`, `FakeBookshelf` from `Fakes.kt`; the existing 12 tests pass unchanged with an empty catalog)

**Screen (spec §4):** "This phone" as today → **"What you hold offline"**: nine `ListItem`s (title; `DomainBar` with installed over comprehensive, the essential tick; supporting text "Basic · 1.1 GB of 4.3 GB" / "Nothing yet · the essential pack is 1.05 GB" / "Comprehensive · 5.2 GB"); clickable → `onDomain(id)`. With `needsCatalogRefresh`: one line, "Refresh the catalog to see your coverage." Then **signals**: up to three `ListItem`s with the sentence and a trailing "See" button (`EmptyDomain` → `onDomain`; `Stale` → `onDomain` or `onPack`; `Unsearchable` → none); "Show all N" when more. Then **Storage**: the volumes as today, then "By source" rows (`StorageRow`s with bytes; PHONE_MEDIA reads "not stored by Granger"). Then **Files** heading over the integrity sections as today.

- [ ] **Step 1: Failing tests:** `coverageRowsComeFromTheCatalogAndTheLibrary` (a catalog with two domains; one installed pack → one BASIC, one NONE; `ui.report` matches), `anEmptyCatalogAsksForARefreshAndKeepsTheFileRows`, `signalsAreCappedAtThreeWithACount` (the UI exposes `report.signals`; the cap is in the screen, so test the view model passes all and a small pure helper `shownSignals(report, showAll)` caps), `storageBySourceIsOnTheUi`. Plus a Robolectric Compose test `ReadinessScreenTest`: `tappingADomainOpensIt` (`onDomain` called with the id), `anOlderCatalogShowsTheRefreshLine`.
- [ ] **Step 2:** run `ReadinessViewModelTest` and `ReadinessScreenTest`. Expected: compile failure.
- [ ] **Step 3: Implement.** The view model adds `catalog.state` and `bookshelf.books` to the `combine` (a 7-way combine: split into two `combine`s). `DomainBar`: a 6 dp rounded `Box`; the fill is `installed / comprehensive` clamped to 1 in `primary`, the essential tick a 1 dp line at `essential / comprehensive` in `onSurfaceVariant`; `Coverage.COMPREHENSIVE` fills fully. Text through `formatBytes`.
- [ ] **Step 4:** `./gradlew :app:testDebugUnitTest`. Expected: PASS.
- [ ] **Step 5: Commit.** `Readiness: what you hold offline — the nine domains, signals, storage by source; Files below`

### Task 5: The Kiwix screen filtered to a domain

**Files:**
- Modify: `app/src/main/kotlin/app/granger/GrangerNavHost.kt` (`SourceRoute(val scope: CatalogScope, val domain: String? = null)`; pass `domain` into the view model), `app/src/main/kotlin/app/granger/sources/SourceViewModel.kt` (`domain` constructor parameter; `SourceUi.domainTitle: String?`; `clearDomain()`; filtering and the Recommended rule), `SourceScreen.kt` (the domain chip in place of the category row)
- Modify tests: `app/src/test/kotlin/app/granger/sources/SourceViewModelTest.kt`, `SourceRoutesTest.kt`

**Rules (spec §4.1):** with a domain set, `shown()` also requires `pack.domain == domain`; the category chips are hidden and one selected `FilterChip` shows the domain's title with a close icon (`clearDomain()` → the chips come back, the list is the whole Kiwix catalog); Recommended lists the domain's tier items in the catalog's order (essential first, then the rest of comprehensive), uninstalled before installed, instead of `featured`; the other sections are the domain's remaining packs by category, unfolded (the list is short). The domain's title comes from `catalog.readiness`; an unknown domain id shows the whole catalog (no chip).

- [ ] **Step 1: Failing tests:** `aDomainFiltersTheKiwixListAndHidesTheCategoryChips`, `recommendedInADomainIsItsTierItemsUninstalledFirst`, `clearingTheDomainBringsTheChipsBack`, `anUnknownDomainShowsEverything`, `sourceRouteCarriesAnOptionalDomain` (serialization round trip).
- [ ] **Step 2:** run `SourceViewModelTest`, `SourceRoutesTest`. Expected: compile failure.
- [ ] **Step 3: Implement.**
- [ ] **Step 4:** `./gradlew :app:testDebugUnitTest`. Expected: PASS.
- [ ] **Step 5: Commit.** `Kiwix screen: filtered to a readiness domain, its tier items first`

### Task 6: Regenerate, sign and publish the catalog

- [ ] Tell the R1 session (granger-0d) the catalog is about to be regenerated and ask whether its `vectors` edit to `curated.json` is committed anywhere; rebase or fold as needed.
- [ ] `catalog_gen.py build --curated tools/catalog-gen/curated.json --out catalog/catalog.json` then `catalog_gen.py sign --key ~/.config/granger/catalog-signing.key catalog/catalog.json` then `verify`. Confirm: every pack and book has `domain`; `readiness.domains` has nine entries; `efficient` and `comprehensive` presets exist; `PublishedCatalogTest` passes against the new file; the Kiwix report's "By domain" section reads right.
- [ ] `./gradlew testDebugUnitTest` and `./gradlew :core:research:test`: all green. Record the count.
- [ ] **Commit.** `Catalog: a domain on every pack and book, the readiness tiers, efficient and comprehensive presets; re-signed`
- [ ] Merge the branch into `main` at Neal's word and push (the app fetches the catalog from `main`).

### Task 7: Pixel 6 walkthrough; roadmap

Waits for the phone (held by the voice and R1 sessions; ask before taking it; back up both databases before installing).

- [ ] Install over the app. Refresh the catalog from the Granger or Kiwix screen.
- [ ] Readiness: nine rows with Neal's real library; the statuses and sizes match what is installed; tapping Medicine opens the Kiwix screen with the Medicine chip and WikiMed first; ✕ brings the category chips back; the signals that apply appear (iFixit unsearchable is expected); storage by source adds up; Files works as before (Check, Download again).
- [ ] Settings → Readiness and the Library top bar both open it.
- [ ] Roadmap: H1 → Done with the date and the test count; "Carried forward from H1" with what was measured, builder's calls, and what H2 needs.
- [ ] **Commit.** `Roadmap: H1 done; carried forward from H1`

## Order and size

Tasks 1–5 are independent of the phone and take a day of agent work; Task 1 is the longest (the 182-row table is hand-checked). Task 2 can start alongside Task 1 once the field names are fixed (they are, above). Task 6 is half an hour plus the build. Task 7 waits for the phone.
