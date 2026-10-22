# Plan H3: Onboarding — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A fresh install reaches a downloading library in five screens, or Home in one tap of Skip (spec §9). The screens: Welcome (the brand, one line), This phone (the tier, free storage, what that means), Pick a start (Efficient or Comprehensive from the readiness model, each pack untickable, the total against free space), Your situation (a region → its map; use-case chips → their packs, each with what it adds and its size), Review and start (the list, Wi-Fi only, Start). Start lands on Home with the downloads running and a progress strip. "Set up suggestions again" in Settings re-runs the flow. The region and use-case tables are catalog data, so one regeneration carries them. Spec: `docs/superpowers/specs/2026-10-21-home-readiness-onboarding-design.md` §6, §7–§10.

**Out of this plan:** billing and the telemetry opt-in (Plan 10 slots them into this flow later), offering AI models (Research hasn't shipped; one line says it is coming), a state-level region list (the catalog's maps are countries and larger areas; see Decision 3), the brand itself (Task 5 swaps one composable and its assets once Neal decides), Slim and Park (S1), any network call beyond the downloads the user starts.

**Architecture (what changes):**
- **`tools/catalog-gen`:** `curated.json` gains `onboarding`: `useCases` (id, title, one line, `items` as readiness-style tokens: catalog ids, `@<collection>`, `family:<name>`) and `regions.larger` (the map packs that are continents or multi-country areas, so the picker can list countries first). `catalog_gen.py` validates every token with the readiness validator and writes `catalog["onboarding"]`.
- **`:core:catalog`:** `Catalog.onboarding: Onboarding?` with `UseCase(id, title, blurb, items)` and `larger: List<String>`; the parser drops unknown tokens, as it does for readiness.
- **`:app` onboarding (new package):** `OnboardingModel` (pure: the start choices from the `efficient` and `comprehensive` presets with books folded into one line; the regions from the maps family split into countries and larger areas; each use case's additions against what is already chosen or installed, with sizes; the review list and the total against free space), `OnboardingViewModel`, `OnboardingScreen` (five steps in one route, Skip always visible, Back steps back), `BrandLockup` (the Welcome screen's mark and name, a placeholder until Task 5), `OnboardingPrefs` (`done`).
- **Launch, Home, Settings:** `StartScreen.Onboarding`; `LaunchFacts.onboarded`; the nav host opens Home then pushes Onboarding on a first run (or when the mark is unset), and Start or Skip pops back to Home. Home shows a progress strip for the onboarding download group. Settings → "Set up suggestions again".

**Tech Stack:** unchanged: Kotlin 2.4.21, Compose Material 3, kotlinx.serialization (`ignoreUnknownKeys`), Room untouched (no migration), Robolectric 4.17, JUnit 4 + Truth; Python 3.14 in `~/.venvs/granger-catalog` for the generator. Build env: `JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home`, `ANDROID_HOME=/opt/homebrew/share/android-commandlinetools`; a new worktree needs `local.properties` copied in and `git submodule update --init --recursive`.

**Roadmap:** `docs/superpowers/plans/2026-10-15-granger-v1-roadmap.md`, row H3, after H2.

## What the code looks like now (surveyed 2026-10-22, `main` at `ba9a355`)

- **Catalog:** 927 packs, 347 books; `readiness.domains` with `essential` / `comprehensive` tokens; generated presets `efficient` (360 items: the essential tier of every domain, `@granger-classics` expanded to the 347 books; `family:` tokens skipped) and `comprehensive` (431), beside `starter` and `granger-classics`. `PackEntry.family`, `.domain`, `.sizeBytes`; `BookEntry.collection`, `.domain`. The **maps family** has 193 packs, one per country or area, titled by name ("France", "Nordics", "Andean countries", "Africa", "World"); 15 are over 4 GB (continents, the US, Canada, China, France, Russia, Norway, Kiribati, Nordics, World). Wikivoyage: `wikivoyage_en_all_nopic` (0.27 GB, in the maps essential tier) and `_maxi` (1.07 GB). The maps essential tier is `family:maps` + `wikivoyage_en_all_nopic`: the region's map is the one thing Efficient can't pick by itself.
- **Generator:** `curated.json` keys `kiwix`, `packs`, `books`, `models`, `presets`, `voices`, `readiness`; `readiness_block()` validates tokens (`SystemExit` on an unknown id or collection), folds tiers and emits presets. `test_catalog_gen.py` has 40 tests; `~/.venvs/granger-catalog/bin/pytest tools/catalog-gen`. A build is about five minutes over the network; `sign --key ~/.config/granger/catalog-signing.key`; live only once pushed to `main`.
- **Downloads:** `Downloads.enqueue(item: CatalogItem, volumeRoot, wifiOnly): EnqueueResult`, `enqueueGroup(groupId, specs, volumeRoot, wifiOnly)`, `cancelGroup`, `removeGroup`; `DownloadRecord(itemId, kind, title, sizeBytes, bytesDone, state QUEUED/RUNNING/FAILED/INSTALLED, groupId, wifiOnly)`; `downloads.records: Flow<List<DownloadRecord>>`. `SourceViewModel.enqueue` picks the volume by `prefs.volumePath` and reports `EnqueueResult` problems as notices; `DownloadPrefs(wifiOnly, volumePath)`; `VolumeSource.list(): List<Volume(path, label, freeBytes, totalBytes)>`.
- **Device:** `container.deviceTier` (`LIBRARY_ONLY`, `ASK`, `THINK_DEEPER`), `container.totalRamBytes`; Readiness words them as "Library only: Research needs a phone with 6 GB of memory" etc.
- **Launch:** `LaunchDecider.decide(LaunchFacts(previousExitClean, bootedSinceLastSession, firstRun, lastTab, lastTabPackOk, openTo))` → `Reader` / `Library(notice)` / `Home`; `SessionMarker.readAndStartSession()` gives `firstRun`. The nav host's root is `LibraryRoute`; a `Home` start pushes `HomeRoute` with the first transition skipped (Plan H2, Decision 1).
- **Home:** `HomeContent(ui, …)` with the strip, Continue and feeds; `HomeUi.strip: ReadinessReport`; `ReadinessStrip`; the default feeds seed on first Home and resolve as packs arrive (Plan H2, Decision 4).
- **Settings:** a keyed list (`LEADING_KEYS` = models, voice, readiness, appearance, storage, wifi, archive); `AppearanceSection` is the radio-group pattern; `SettingsViewModel` takes prefs objects.
- **Kiwix screen:** `SourceRoute(scope, domain)` filters to a readiness domain; the plan's "see what this adds" reuses it.
- **Brand:** two candidates wait on Neal: the Vitruvian draft on `p1-brand` (worktree `agent-aab2c27bc7b888a77`, `tools/brand/*.py`) and the fan-mark package at `art/android-rebrand/` (`brand/granger-{icon,lockup,mark}-{dark,light}.svg`, mono variants, an `app/src` drop-in). The launcher icon today is the pre-brand one.
- Tests: 1914 JVM tests (`./gradlew testDebugUnitTest` plus `./gradlew :core:research:test`).

## Decisions

1. **Onboarding is a route over Home, not a start destination.** On a first run (or an unset `onboarding.done`) the nav host opens Home as H2 does and pushes `OnboardingRoute` at once with the transition skipped. Start and Skip set the mark and pop to Home; Back from the first step is Skip. Home is already there when the flow ends, so the progress strip and the seeded feeds need no hand-off.
2. **One route, five steps.** `OnboardingRoute` holds a `step` in the view model (saved), not five routes: Back steps back, rotation keeps the choices, and the review step sees every earlier choice without passing state through navigation.
3. **Regions are the maps family, countries first.** The picker lists the maps packs by title with a search field; the packs named in `onboarding.regions.larger` (continents, World, multi-country areas) sit under a "Larger areas" heading, the rest are countries. The catalog has no state-level maps, so the spec's state list waits until Kiwix publishes them; the United States is one 4+ GB pack. One region at a time (the common case); a second is one tap away on the Kiwix screen.
4. **Use cases are tokens, like tiers.** Each use case's `items` use the readiness grammar, validated by the same generator code. What a chip adds is computed on the phone: the items minus what the start choice already holds minus what is installed, with the sum of sizes; a `family:` item contributes its smallest pack for the sum and is listed as "a <family> pack" (Travel's map is the region's). The first lists (sizes from the 2026-10-22 catalog; Task 1 validates every id):
   - **Off-grid & emergencies:** `zimgit-post-disaster_en`, `ready.gov_en_all`, `trueprepper.com_en_all`, `appropedia_en_all_maxi`, `zimgit-water_en`, `zimgit-food-preparation_en`, `zimgit-medicine_en`, `wikem_en_all_maxi`.
   - **Travel:** `wikivoyage_en_all_maxi`, `openstreetmap-wiki_en_all_maxi` (the region's map comes from the region picker).
   - **Study:** `wikibooks_en_all_maxi`, `wikiversity_en_all_maxi`, `wikipedia_en_mathematics_maxi`, `wikipedia_en_physics_maxi`, `wikipedia_en_chemistry_maxi`, `oer4schools_en_all`, `milneopentextbooks.org_en_all`.
   - **Build & repair:** `ifixit_en_all`, `wikipedia_en_knots_maxi`, `solar.lowtechmagazine.com_mul_all`, `cd3wdproject.org_en_all`, `diy.stackexchange.com_en_all`, `electronics.stackexchange.com_en_all`, `family:energypedia`.
   - **Medical work:** `wikipedia_en_medicine_maxi`, `wikem_en_all_maxi`, `medlineplus.gov_en_all`, `zimgit-medicine_en`, `wwwnc.cdc.gov_en_all`, `family:mdwiki`.
   - **Software:** `wikipedia_en_computer_maxi`, `docs.python.org_en_all`, `superuser.com_en_all`, `unix.stackexchange.com_en_all`, `openwrt.org_en_all`, `family:mediawiki` (Stack Overflow at 115 GB stays out; it is one tap away on the Kiwix screen).
   - **Faith & philosophy:** `@granger-classics` (the books whose domain is `thought`), `wikipedia_en_history_maxi`, `philosophy.stackexchange.com_en_all`, `christianity.stackexchange.com_en_all`, `islam.stackexchange.com_en_all`, `judaism.stackexchange.com_en_all`, `buddhism.stackexchange.com_en_all`, `hinduism.stackexchange.com_en_all` (`gotquestions.org` at 15 GB stays out).
   Every id above was checked against the 2026-10-22 catalog (three were corrected on the way: appropedia's `_maxi`, lowtechmagazine's `_mul_all`, the World map's id); a later catalog that drops one fails the generator, which names it.
5. **Pick a start uses the generated presets.** Efficient = `presets.efficient`, Comprehensive = `presets.comprehensive`; packs are listed one per line and untickable, the 347 Classics as one line ("Granger Classics · 347 books · 179 MB") that unticks together. Neither is required: "Neither, I'll choose later" is a third choice. The total is shown against the chosen volume's free space and turns red past it; Start is still allowed (the queue policy refuses what won't fit, with its notice).
6. **Start enqueues one group.** `enqueueGroup("onboarding:<epoch>", specs, volume, wifiOnly)` with the packs and books in one group, so Home can show one progress strip ("Setting up · 3 of 14 · 1.2 GB of 9.8 GB") from the group's records and the user can cancel it from the Kiwix screen's Installed tab as today. Books are catalog items and take the same path as a Kiwix download.
7. **The flow re-runs from Settings** ("Set up suggestions again", under Readiness): the same five screens; a pack already installed is listed as "on the phone" and can't be chosen again; Start enqueues only what is new.
8. **This phone says what the tier means in one sentence**, from `DeviceTier`: Library only ("Research, the AI librarian, needs 6 GB of memory; this phone has 4 GB, so Granger is the library"), ASK, THINK_DEEPER; free space from the internal volume (and the card, when there is one), and "a full Wikipedia is 100 GB; the Efficient start is 12 GB" with the real numbers from the catalog.
9. **The brand is one composable.** `BrandLockup()` draws the app icon and the name; Task 5 replaces its assets with the chosen mark and lockup. Nothing else in the flow depends on the decision, so Tasks 1–4 start now.
10. **No new dependency, no migration.** The mark is SharedPreferences `"onboarding"`; choices live in the view model's saved state; the enqueued group is the download ledger's.

## Global Constraints

- `./gradlew testDebugUnitTest` for JVM tests; `~/.venvs/granger-catalog/bin/pytest tools/catalog-gen` for the generator.
- The catalog regeneration (Task 1) touches the same signed files as R1 Task 10 (`vectors`): ask the R1 session (granger-0d) before regenerating; whoever is second regenerates on top. The signed catalog is live only once pushed to `main`.
- Nothing in the flow calls the network except the downloads the user starts; the catalog is the cached one (a fresh install has none until the first refresh: the flow's Pick a start then says "Refresh the catalog to see the choices" with a Refresh button, as Readiness does).
- The phone is shared: Task 6 asks on the channel and backs up both databases before installing. A first-run check must not wipe Neal's phone: the first-run path is exercised on the emulator with a fresh install, the re-run path on the phone from Settings.
- Commit after every task with the message given.

## Review Focus

- Can Skip ever leave the mark unset, so the flow comes back on the next launch? (One tap of Skip must be final; Settings brings it back on purpose.)
- Does the review total count anything already installed, or a pack twice (chosen by the start and by a use case)?
- Does a catalog without `onboarding` (an older cache) still let the flow run with the start choices alone?
- Does the brand placeholder render on a 4" phone without clipping, so Task 5 is a swap and not a layout fix?

---

### Task 1: The onboarding block in the catalog

**Files:**
- Modify: `tools/catalog-gen/curated.json` (`onboarding`: `useCases`, `regions.larger`), `tools/catalog-gen/catalog_gen.py` (`onboarding_block()` reusing the token validator; `catalog["onboarding"]`), `tools/catalog-gen/test_catalog_gen.py`
- Modify: `core/catalog/src/main/kotlin/app/granger/core/catalog/Catalog.kt` (`Onboarding`, `UseCase`, `Catalog.onboarding`), `CatalogParser.kt`, tests (`CatalogParserTest`, `PublishedCatalogTest`: seven use cases, every token resolves, every `larger` id is a maps pack)
- Regenerate: `catalog/catalog.json`, `.sig`, `kiwix-report.txt` (after checking with granger-0d)

- [x] `curated.json` `onboarding` per Decision 4 with `_about`; `regions.larger` = `maps_en_all`, `maps_en_africa`, `maps_en_asia`, `maps_en_europe`, `maps_en_north-america`, `maps_en_south-america`, `maps_en_australia-oceania`, `maps_en_central-america`, `maps_en_nordics`, `maps_en_andes` (ids checked against the built catalog).
- [x] The generator validates every token and every `larger` id (a `SystemExit` names the bad one), writes the block, and the report lists each use case with its item count and size.
- [x] `:core:catalog` parses it, dropping unknown tokens; older catalogs give `onboarding == null`.
- [x] Regenerate, sign, verify; `PublishedCatalogTest` passes against the signed file.
- [x] **Commit.** `Catalog: the onboarding use cases and the larger map areas`

### Task 2: The onboarding model

**Files:**
- Create: `app/src/main/kotlin/app/granger/onboarding/OnboardingModel.kt`, `app/src/test/kotlin/app/granger/onboarding/OnboardingModelTest.kt`

- [x] `StartChoice(id, title, lines: List<Line>, bytes)` from a preset: packs one per line; books of one collection folded into one line with the count; a line carries its items so unticking removes them all. `installed` lines are marked and excluded from the total.
- [x] `Regions(countries, larger)` from the maps family and `onboarding.regions.larger`, sorted by title, each with its size; a search filter.
- [x] `UseCaseAddition(useCase, items, bytes)` = the use case's resolved items minus the start's chosen items minus installed; `family:` contributes its smallest pack and a "a <family> pack" line.
- [x] `Review(groups: Start / Region / Use cases, items distinct, bytes, freeBytes, fits)`; `specs()` for the enqueue.
- [x] Tests: no double counting; installed excluded; a `family:` line; an older catalog (no `onboarding`) gives a review from the start alone; the Classics fold.
- [x] **Commit.** `Onboarding: the model — start choices, regions, use-case additions, the review`

### Task 3: The screens

**Files:**
- Create: `app/src/main/kotlin/app/granger/onboarding/OnboardingViewModel.kt`, `OnboardingScreen.kt` (the five steps), `BrandLockup.kt`, `OnboardingPrefs.kt`; tests `OnboardingViewModelTest.kt`, `OnboardingScreenTest.kt`

- [x] `OnboardingViewModel(catalog, library, bookshelf, downloads, volumes, prefs: DownloadPrefs, tier, totalRamBytes, onboardingPrefs, io)`: `step`, the choices (saved), `ui` per step from the model; `start()` enqueues the group per Decision 6 and returns the notice or null; `skip()` and `start()` set the mark.
- [x] Welcome: `BrandLockup` + one line + Get started / Skip. This phone: Decision 8. Pick a start: three radio choices, the chosen one's lines with checkboxes, the total vs free. Your situation: the region search and list, the seven chips with "+ 1.2 GB · 4 packs" under each and a "See" link to the Kiwix screen filtered to the matching domain. Review and start: the groups, the total, Wi-Fi only, the volume when there are two, Start.
- [x] Skip in the top bar on every step; Back steps back; the Research line on This phone when the tier is LIBRARY_ONLY or ASK.
- [x] Tests: each step renders its data; unticking a line changes the total; Skip and Start call the view model; a notice from `start()` shows.
- [x] **Commit.** `Onboarding: the five screens`

### Task 4: Launch, Home's progress strip, Settings

**Files:**
- Modify: `launch/LaunchDecider.kt` (`StartScreen.Onboarding`, `LaunchFacts.onboarded`), `MainActivity.kt`, `GrangerNavHost.kt` (`OnboardingRoute`; a Home start that pushes Onboarding; Start/Skip pop to Home), `GrangerApp.kt` (`onboardingPrefs`), `home/HomeViewModel.kt` + `HomeScreen.kt` (the group's progress under the strip; tap → the Kiwix screen), `settings/SettingsScreen.kt` + `SettingsViewModel.kt` ("Set up suggestions again")
- Tests: `LaunchDeciderTest`, `HomeViewModelTest`, `HomeScreenTest`, the settings test

- [ ] `decide`: `!onboarded` → `Onboarding` (before every other rule, so a first run never resumes a tab from a restored backup either).
- [ ] The nav host: `StartScreen.Onboarding` → `HomeRoute` then `OnboardingRoute`, both with the transition skipped; `onDone` pops to Home.
- [ ] Home: `HomeUi.setup: SetupProgress?` from records whose `groupId` starts with `onboarding:` and are not all installed; a `LinearProgressIndicator` and one line under the strip; gone when the group finishes or is cancelled.
- [ ] Settings: the row under Readiness; it navigates to `OnboardingRoute` (the mark stays set).
- [ ] Full JVM suite green.
- [ ] **Commit.** `Onboarding: a first run opens it over Home; the setup strip; Set up suggestions again`

### Task 5: The brand (after Neal's decision)

- [ ] Replace `BrandLockup`'s assets with the chosen mark and lockup (dark and light), and the launcher icon if the decision includes it; the Welcome line.
- [ ] **Commit.** `Onboarding: the brand on Welcome`

### Task 6: Walkthrough; roadmap

Waits for the phone (ask on the channel; back up both databases before installing). The first-run path runs on the emulator (`emulator-5562`, a fresh install); the phone exercises Settings → Set up suggestions again.

- [ ] Emulator, fresh install, no catalog cached: Pick a start says to refresh; after a refresh the three choices show with real sizes; Skip from Welcome lands on Home and the flow doesn't return on the next launch.
- [ ] Phone, from Settings: This phone says THINK_DEEPER and the real free space; Efficient lists Neal's installed packs as on the phone; the region search finds "Peru" and "Andean countries"; the chips show what they add; Review totals match; Start with one small item enqueues it in a group and Home's strip shows it until it lands.
- [ ] Roadmap: H3 → Done with the date and the test count; "Carried forward from H3" with what was measured, builder's calls, and what Plan 10 slots in (billing, telemetry).
- [ ] **Commit.** `Roadmap: H3 done; carried forward from H3`

## Order and size

Task 1 is a morning (the lists, the generator, the catalog build and signing; it needs the R1 session's word on the catalog). Task 2 is pure and quick. Task 3 is the longest (five screens). Task 4 is wiring. Task 5 is an hour once the brand is chosen and can land any time after Task 3. Task 6 waits for the phone.
