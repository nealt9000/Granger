# Plan H4: Readiness calibration — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** The readiness tiers reach "good levels across the domains" at about 90 GB (Neal, H1 walkthrough), and the numbers people see are honest. Today the Efficient start is 20 GB and the Comprehensive one 256 GB (each pack counted once; more with the doubles): Stack Overflow alone is 115 GB of Technology, WikiMed counts twice, and the Top pack counts beside the Wikipedia that holds it. After this plan: **Efficient 11.1 GB, Comprehensive 90.3 GB** (both plus the region's map), the tier bytes count one entry per ZIM, and a pack whose articles sit inside a bigger installed pack counts as held. Readiness (H1), the Home strip (H2) and onboarding's Pick a start (H3) all follow from the catalog, so one regeneration carries the change.

**Out of this plan:** Slim and Park (S1), new families or packs outside the tiers, the Kiwix screen's layout, Stack Overflow's domain (it stays `technology` for storage; it only leaves the tiers), a pictures-versus-no-pictures policy beyond the lists below, per-article deduplication between packs (containment is declared per ZIM, not computed per article).

**Architecture (what changes):**
- **`tools/catalog-gen`:** `curated.json` → `readiness` gets the new tier lists (Decisions 1–4) and a `contains` table (Decision 5). `readiness_block()` validates `contains` (every key and every plain value is a `zimName` in the catalog; a value ending in `*` is a prefix), writes it into `catalog["readiness"]`, and builds the presets with one pack per `zimName` (the flavour the larger tier lists; Decision 6) and without a pack whose ZIM is contained by another ZIM in the same preset. The Kiwix report gains the tier totals per domain.
- **`:core:catalog`:** `Readiness.contains: Map<String, List<String>>` (zimName → zimNames or `prefix*`), and `Readiness.containers(zimName): Set<String>`; the parser drops entries that name nothing.
- **`:app` readiness:** `ReadinessModel` counts tier bytes once per `zimName` (the largest listed flavour) and leaves out a contained ZIM when its container is in the same tier; a tier item is held when its ZIM or a container of it is installed. `installedBytes` is unchanged (it is what is on the phone).
- **`:app` onboarding:** `OnboardingViewModel.installedIds` adds every pack whose ZIM is contained by an installed ZIM, so Pick a start, the use cases and the review show it as "on the phone".

**Tech Stack:** unchanged: Kotlin 2.4.21, kotlinx.serialization (`ignoreUnknownKeys`, so an older app reading the new catalog ignores `contains`), JUnit 4 + Truth, Robolectric 4.17; Python 3.14 in `~/.venvs/granger-catalog`. Build env: `JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home`, `ANDROID_HOME=/opt/homebrew/share/android-commandlinetools`. No migration, no new dependency.

**Roadmap:** `docs/superpowers/plans/2026-10-15-granger-v1-roadmap.md`, new row H4 after H3 (from "Carried forward from H1": Neal's calibration notes; "Carried forward from H3": what the Comprehensive start should count).

## What the code looks like now (surveyed 2026-10-22, `main` at `52efe56`)

- **Tiers** (`curated.json` → `readiness.domains[]`: `id`, `title`, `essential`, `comprehensive`; tokens are catalog ids, `@<collection>`, `family:<name>`). `readiness_block()` folds essential into comprehensive, checks every token, and emits `efficient` (360 items) and `comprehensive` (431) presets by expanding tokens in order and de-duplicating ids only: both WikiMed flavours (`wikipedia_en_medicine_nopic` essential, `_maxi` comprehensive) end up in the comprehensive preset.
- **Sizes today, one entry per ZIM** (2026-10-22 catalog): Efficient 20.2 GB, of which Wiktionary is 9.2 GB; Comprehensive 256 GB: Technology 126 (Stack Overflow 114.9), Reference 68.5 (full Wikipedia without pictures 52.7, Wiktionary 9.2, Top 2.75), Science 22.7 (math.stackexchange 7.4, stats 1.6), Literature 20.7 (Gutenberg LCC-PR 16.2, no full-text index), Making 12.4, Food 4.9, Medicine 4.4, Maps 4.0 (plus the region), Thought 2.6.
- **`ReadinessModel.Resolved.token()`:** a pack token is held when its `zimName` is installed (any flavour); its bytes are the listed entry's `sizeBytes`, summed per tier, so two flavours of one ZIM count twice. `family:` contributes its smallest pack. `essentialBytes` / `comprehensiveBytes` feed the bar's "of X" and the "the essentials are X" line.
- **`OnboardingModel.lines()`** already lists one line per ZIM (the larger flavour) on the phone (H3 walkthrough fix); `installedIds` is the Kiwix screen's rule plus every flavour of an installed ZIM.
- Tests: 1936 JVM tests (`./gradlew testDebugUnitTest` plus `./gradlew :core:research:test`), 44 catalog-gen tests (`~/.venvs/granger-catalog/bin/pytest tools/catalog-gen`).

## What was measured for this plan

Kiwix builds Wikipedia selections from the WP1 lists (`download.openzim.org/wp1/enwiki/`, the 2026-10 lists). Against `tops/1000000.tsv`:

| Selection | Articles | Share of articles in Top 1M | Share of page views in Top 1M |
| --- | --- | --- | --- |
| Top (`tops/50000`) | 50,000 | 100% | 100% |
| Medicine (`customs/medicine`) | 96,225 | 25.7% | 90.7% |
| Physics | 17,842 | 38.6% | 91.6% |
| Chemistry | 19,804 | 19.7% | 89.9% |
| Mathematics | 22,716 | 33.0% | 91.4% |
| Molecular & cell biology | 30,207 | 17.6% | 87.8% |
| Computer | 649,008 | 29.0% | 83.1% |
| Astronomy (`projects/Astronomy`) | 28,261 | 24.9% | 93.1% |
| History (`projects/History`) | 48,733 | 32.3% | 93.4% |
| Geography (`projects/Geography`) | 91,990 | 7.6% | 93.5% |

So Top 1M **contains** the Top pack, and covers what people read of each subject pack but not most of its articles (nor its pictures): the subject packs stay in their tiers. Full Wikipedia contains every English Wikipedia selection. The packs' snapshots differ from the lists by up to six months, so "contains" is near, not exact; readiness already treats any flavour as the same item (H1 Decision 4), and this is the same kind of rounding.

## Decisions

1. **Stack Overflow leaves the tiers** (115 GB; Neal: "it should not count that way", and its topics run well past technology). It stays in the catalog under `technology` for storage and on the Kiwix screen, one tap away.
2. **Reference's comprehensive Wikipedia is Top 1M without pictures** (`wikipedia_en_top1m_nopic`, 17.1 GB) in place of the full Wikipedia (52.7 GB). Neal, 2026-10-22: "go with 1 for now" after asking whether the packs are redundant (see the measurements above). Full Wikipedia is one tap away on the Kiwix screen.
3. **Wiktionary moves from essential to comprehensive** (Neal, 2026-10-22). Efficient drops from 20.2 to 11.1 GB. Define works from Wiktionary when it is installed and hands the word to another app when it isn't, as today.
4. **Three more cuts:** Gutenberg LCC-PR (16.2 GB; no full-text index, so Search and Research can't reach it; the Granger Classics cover that ground), math.stackexchange (7.4 GB) and stats.stackexchange (1.6 GB) leave Science's comprehensive tier. They stay in the catalog.
   The resulting tiers (sizes per domain, one entry per ZIM, contained ZIMs left out):

   | Domain | Essential | Comprehensive (incl. essential) |
   | --- | --- | --- |
   | Medicine & first aid | 1.0 GB, unchanged | 4.4 GB, unchanged |
   | Food, water & survival | 0.1 GB, unchanged | 4.9 GB, unchanged |
   | Making, repair & engineering | 3.6 GB, unchanged | 12.4 GB, unchanged |
   | Science & math | 2.3 GB, unchanged | 13.7 GB (− math SE, stats SE) |
   | Technology & computing | 0.9 GB, unchanged | 10.9 GB (− Stack Overflow) |
   | Maps, places & travel | 0.3 GB + the region | 4.0 GB + the region |
   | General reference | 2.7 GB (Top only) | 32.9 GB (Wiktionary, Top 1M, Wikibooks, Wikiversity, Citizendium, English and ELL SE; Top is inside Top 1M) |
   | Literature & the arts | Classics | 4.6 GB (− Gutenberg LCC-PR) |
   | Religion, philosophy & history | Classics | 2.6 GB, unchanged |
   | **All** | **11.1 GB** | **90.3 GB** |

5. **Containment is a curated table.** `readiness.contains` maps a ZIM name to the ZIM names it holds, with `*` as a trailing prefix wildcard:
   ```json
   "contains": {
     "wikipedia_en_all": ["wikipedia_en_*"],
     "wikipedia_en_top1m": ["wikipedia_en_top", "wikipedia_en_100"],
     "wikipedia_en_top": ["wikipedia_en_100"]
   }
   ```
   A key never contains itself (`wikipedia_en_all` matches its own prefix and is skipped). The table is by ZIM, not by flavour: Top 1M without pictures holds the Top pack with pictures, as H1 already lets any flavour satisfy an item. It is data, so the next find (a Wikivoyage region inside the full Wikivoyage, a mini pack inside its maxi) is one line. The generator fails on a key or plain value that names no ZIM in the catalog (a renamed ZIM is caught on the monthly refresh).
6. **The presets carry one pack per ZIM, the one the bigger tier names.** Efficient takes the essential flavour (WikiMed without pictures); Comprehensive takes the comprehensive flavour (WikiMed with pictures) and leaves out the essential one, and leaves out a ZIM contained by another in the same preset (Top, under Top 1M). Onboarding's own flavour fold stays as a guard for older catalogs.
7. **What a contained pack contributes (Neal's open question from H1):** it counts as **held** when its container is installed (started and complete for the tier), and its bytes count **zero** in a tier that also lists its container. Installed bytes stay real: a phone that holds both Top and Top 1M shows both in Storage and in the domain's "held" figure, since both are on the phone.
8. **The Readiness bar's text doesn't change shape.** "X held of Y" and "the essentials are Z" keep their wording; only Y and Z get honest. A held-by-container item gives no "empty domain" signal.

## Global Constraints

- `./gradlew testDebugUnitTest` (plus `./gradlew :core:research:test`) for JVM tests; `~/.venvs/granger-catalog/bin/pytest tools/catalog-gen` for the generator.
- The catalog regeneration (Task 4) touches the same signed files as R1 Task 10 (`vectors`): ask the R1 session (granger-0d) before regenerating; whoever is second regenerates on top. The signed catalog goes live only once pushed to `main`; until then the phone gets it seeded into `files/catalog/` as in H1.
- The phone is shared: Task 5 asks on the channel and backs up both databases before installing; the build must be off `main` at or after `ba9a355` (library schema 12).
- Commit after every task with the message given.

---

### Task 1: The tiers and the containment table in the generator

**Files:** `tools/catalog-gen/curated.json`, `tools/catalog-gen/catalog_gen.py`, `tools/catalog-gen/test_catalog_gen.py`

- [ ] **Step 1: Failing tests.** In `test_catalog_gen.py`, with the existing fixture catalog helpers:
  - `test_readiness_contains_is_written_and_checked`: a `contains` naming two ZIMs present is copied to `catalog["readiness"]["contains"]`; a key or plain value naming no ZIM raises `SystemExit` with the name in the message; a `prefix*` value matching nothing is allowed (it may match later).
  - `test_presets_carry_one_flavour_per_zim`: essential `x_nopic`, comprehensive `x_maxi` (same `zimName`) → `efficient` has `x_nopic`, `comprehensive` has `x_maxi` and not `x_nopic`.
  - `test_comprehensive_preset_leaves_out_contained_zims`: essential `top_nopic` (zimName `wikipedia_en_top`), comprehensive `top1m_nopic` (`wikipedia_en_top1m`), `contains` top1m → top: `comprehensive` lacks `top_nopic`, `efficient` keeps it.
  - `test_real_curated_tiers_fit_the_target`: with the real `curated.json` and the committed `catalog/catalog.json`, the generated presets' unique sizes are under 12 GB (efficient) and 95 GB (comprehensive), and neither names `stackoverflow.com_en_all`. (Skips when the catalog file lacks `readiness`.)
- [ ] **Step 2: Run** `~/.venvs/granger-catalog/bin/pytest tools/catalog-gen -k "contains or flavour or contained or target"`: four failures.
- [ ] **Step 3: `readiness_block()`:**
  - Read `block.get("contains", {})`; build `zim_names = {p["zimName"] for p in packs if p.get("zimName")}`; for each key and each value without a trailing `*`, `SystemExit(f"readiness contains: unknown ZIM {name!r}")` if absent. Write it beside `domains`.
  - `holds(container, zim)`: `zim != container and any(zim == v or (v.endswith("*") and zim.startswith(v[:-1])) for v in contains.get(container, []))`.
  - After expanding a preset's tokens: keep, per `zimName`, the **last** pack id seen (comprehensive's tokens come after essential's, so the comprehensive flavour wins; in efficient only essential is there), keep its position at the first occurrence; then drop a pack whose `zimName` is held by another `zimName` in the same preset. Books and packs without a `zimName` pass through.
  - The Kiwix report: under "Listed, by readiness domain", one line per domain with its essential and comprehensive sizes computed the same way (one per ZIM, contained left out), and an "All" line.
- [ ] **Step 4: `curated.json`:** apply Decisions 1–5: technology drops `stackoverflow.com_en_all`; science drops `math.stackexchange.com_en_all` and `stats.stackexchange.com_en_all`; literature drops `gutenberg_en_lcc-pr`; reference essential is `["wikipedia_en_top_nopic"]`, comprehensive `["wiktionary_en_all_nopic", "wikipedia_en_top1m_nopic", "wikibooks_en_all_nopic", "wikiversity_en_all_nopic", "citizendium.org_en_all_maxi", "english.stackexchange.com_en_all", "ell.stackexchange.com_en_all"]`; add `contains` as in Decision 5. Update `_about` with one sentence on `contains`.
- [ ] **Step 5: Run** the full generator suite: all pass (48).
- [ ] **Step 6: Commit** `Catalog: readiness tiers calibrated to ~90 GB; one flavour per ZIM in the presets; the contains table`

### Task 2: `contains` in `:core:catalog`

**Files:** `core/catalog/src/main/kotlin/app/granger/core/catalog/Catalog.kt`, `CatalogParser.kt`, tests in `core/catalog/src/test/…/CatalogParserTest.kt` and `PublishedCatalogTest.kt`

- [ ] **Step 1: Failing tests.** `CatalogParserTest`: a readiness block with `contains` parses into `Readiness.contains`; an entry whose key names no pack's `zimName` is dropped; `containers("wikipedia_en_top")` returns `{"wikipedia_en_top1m", "wikipedia_en_all"}` for the Decision 5 table; a key never contains itself; a catalog without `contains` gives an empty map. `PublishedCatalogTest` (after Task 4's regeneration; write it now, it fails until then): the published catalog's `contains` has `wikipedia_en_top1m` → `wikipedia_en_top`, and the `comprehensive` preset doesn't name `stackoverflow.com_en_all`.
- [ ] **Step 2: Implement.** `Readiness(val domains: List<Domain> = emptyList(), val contains: Map<String, List<String>> = emptyMap())` with
  ```kotlin
  /** The ZIM names that hold [zimName]'s articles (Plan H4, Decision 5); never [zimName] itself. */
  fun containers(zimName: String): Set<String> = contains.filterTo(LinkedHashMap()) { (key, held) ->
      key != zimName && held.any { it == zimName || (it.endsWith("*") && zimName.startsWith(it.dropLast(1))) }
  }.keys
  ```
  The parser keeps entries whose key is some pack's `zimName`.
- [ ] **Step 3: Run** `./gradlew :core:catalog:test`: the parser tests pass; `PublishedCatalogTest`'s new case fails until Task 4 (mark it in the commit message).
- [ ] **Step 4: Commit** `Catalog: Readiness.contains and containers()`

### Task 3: Readiness and onboarding honour one-per-ZIM and containment

**Files:** `app/src/main/kotlin/app/granger/readiness/ReadinessModel.kt`, `app/src/main/kotlin/app/granger/onboarding/OnboardingViewModel.kt`, tests `ReadinessModelTest.kt`, `OnboardingViewModelTest.kt` (or the model test that covers `installedIds`)

- [ ] **Step 1: Failing tests** (`ReadinessModelTest`, with the existing catalog builders):
  - `tierBytesCountEachZimOnce`: essential `med_nopic` (1 GB), comprehensive `med_maxi` (2 GB), same `zimName` → `essentialBytes` 1 GB, `comprehensiveBytes` 2 GB (was 3).
  - `aContainedZimCountsZeroBesideItsContainer`: comprehensive lists `top_nopic` (2.75) and `top1m_nopic` (17.1) with top1m → top → `comprehensiveBytes` 17.1 GB; `essentialBytes` (Top alone) 2.75 GB.
  - `aContainerSatisfiesTheContainedItem`: Top 1M installed, Top not → the reference essential item is started; the domain is Basic; no empty-domain signal names Top.
  - `installedBytesStayReal`: both Top and Top 1M installed → `installedBytes` is their sum.
  - Onboarding: with Top 1M installed, Pick a start's Top line (Efficient) shows as installed.
- [ ] **Step 2: Implement in `Resolved`:**
  - `installedNames` gains, for each installed name, nothing new; add `fun held(zimName) = zimName in installedNames || catalog?.readiness?.containers(zimName).orEmpty().any { it in installedNames }` and use it in `token()` for packs.
  - Tier bytes: replace `sumOf { it.bytes }` with a helper over the tier's pack entries: group by `zimName` (largest listed flavour), drop a name whose `containers()` intersect the tier's names, sum; book and family tokens add as now. Keep `Token.bytes` for books and families; carry the `PackEntry` on pack tokens so the helper can group.
  - `OnboardingViewModel.installedIds`: after the flavour rule, add every catalog pack whose `zimName` has a container among the installed ZIM names.
- [ ] **Step 3: Run** `./gradlew testDebugUnitTest`: all pass but `PublishedCatalogTest`'s Task 2 case.
- [ ] **Step 4: Commit** `Readiness: tier bytes count each ZIM once; a pack inside an installed one counts as held`

### Task 4: Regenerate and sign the catalog

- [ ] **Step 1:** Ask granger-0d (R1) whether Task 10 has regenerated; if it has, rebase onto its commit first, so this regeneration carries `vectors`.
- [ ] **Step 2:** `~/.venvs/granger-catalog/bin/python tools/catalog-gen/catalog_gen.py build …` as in H3 Task 1, then `sign --key ~/.config/granger/catalog-signing.key`. Read the report's tier lines: Efficient about 11 GB, Comprehensive about 90 GB; if Kiwix retired a tier pack since 2026-10-22 the build names it, and the replacement is a one-line edit.
- [ ] **Step 3:** `./gradlew testDebugUnitTest` and the generator suite: all green, including `PublishedCatalogTest`.
- [ ] **Step 4: Commit** `Catalog: regenerated and signed with the calibrated tiers`

### Task 5: Pixel 6 walkthrough and the roadmap

- [ ] **Step 1:** Ask on the channel for the phone; back up both databases (`files/db-backup-<date>/`); install a debug build from this branch; seed the signed catalog into `files/catalog/` (the app fetches from `main`).
- [ ] **Step 2: Neal checks:**
  - Readiness: Technology's bar reads against about 11 GB, not 126; Medicine's comprehensive figure counts WikiMed once (4.4 GB); Reference's essentials are 2.7 GB.
  - Settings → Readiness → Set up suggestions again → Pick a start: Efficient about 11 GB, Comprehensive about 90 GB; Comprehensive lists Top 1M and not Top, WikiMed once, no Stack Overflow.
  - Home strip and Readiness levels: compare with the screenshot taken before installing. A domain can rise (a smaller comprehensive tier is easier to complete) but none should fall, since every essential tier either is unchanged or lost an item (Reference lost Wiktionary). Note any change in the roadmap.
- [ ] **Step 3: Roadmap:** add row H4 (Done, with the numbers), "Carried forward from H4", strike the calibration notes under H1 and H3's "what the Comprehensive start should count".
- [ ] **Step 4: Commit** `Roadmap: H4 done; carried forward from H4`, then merge to `main` and push when Neal says so (the catalog is live only then).
