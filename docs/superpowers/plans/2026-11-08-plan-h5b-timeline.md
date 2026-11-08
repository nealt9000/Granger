# Plan H5b: the time index and the Home timeline

**Spec:** `../specs/2026-11-07-living-home-design.md` §3 (separation rule), §3.1 revised (the Home timeline, *Reaching one article*), §4 (G1), §5.1–5.2 (axis, lanes), §9.4 (lanes), §11 (measured coverage).
**Branch:** `h5b-timeline` (worktree `.claude/worktrees/h5`).
**Goal:** Home opens on a river of time holding every dated article on the phone, across every English Wikipedia pack, each shown once. Category chips turn lanes on and off. You scrub, pinch, and step mark to mark, and reach any one article in a tap or two. The data comes from a small per-pack time index built offline from Wikidata and delivered beside the pack, the way search vectors are.

## What it builds on
- **The measurement (spec §11):** 99.9% of real articles match a Wikidata item by ZIM path. About 85,000 dated articles across the Pixel 6's eight English packs: Top 54% and History 68% dated, the subject packs much less.
- **The filtered Wikidata slice** on `/Volumes/X10 Pro/granger-g1/`: `items.jsonl` (338,368 items: dates, coordinates, P31/P106/P136/P279, sitelink count, eswiki title) and `classes.jsonl` (4.5 M classes with P279 parents). The 146 GB dump stays there (Neal, for the home server project).
- **The search vectors' path** (Plan R1) from release JSON to catalog to download to on-phone lookup; Task 3 mirrors it.
- **Wander (H5a):** `WanderModel` / `WanderSection` as the shape for a Home section; `encyclopediasOf` for pack order; `ArticleThumbnails` for pictures; `onOpenCard(CardTarget.Article)` to open.

## Decisions (proposed; Neal to confirm at the phone check)
1. **One file per pack, `.gtime`:** a read-only SQLite file named after the pack's ZIM (`wikipedia_en_top_maxi_2026-09-14.gtime`), valid only for that ZIM, like a `.gvec`. One table:
   `marks(qid INTEGER, path TEXT, title TEXT, kind INTEGER, y0 INTEGER, md0 INTEGER, y1 INTEGER, md1 INTEGER, prec INTEGER, lanes INTEGER, rank INTEGER, lat REAL, lon REAL)`
   - `y0`/`y1` are astronomical years (1 BC = 0, 500 BC = −499), so BC sorts and subtracts correctly. `md` is month×100+day, or 0.
   - `prec` is Wikidata's precision (11 day, 10 month, 9 year, 8 decade, 7 century, 6 millennium, lower for deep time).
   - `lanes` is a bitmask in the order of Decision 4. `rank` is the item's sitelink count, the notability measure (an article in 200 languages outranks one in 3).
   - Indexes on `(y0)` and `(rank DESC)`. A `meta` table holds the format version, the lanes version, the source dump date and the ZIM's checksum.
   - Coordinates ride along now so the Atlas can add an R-tree later without rebuilding.
2. **Which date, by kind:**
   - **Lifetime** (instance of human): birth to death (P569–P570); still living runs to the dump's year.
   - **Span:** start to end (P580–P582), or inception to dissolution (P571–P576) when both exist.
   - **Point:** point in time (P585), else publication (P577), else inception alone (P571).
   - Preferred-rank statements first, as the filter already does. Julian dates keep their year; day-precise Julian dates show the day as written (spec §4's date-error check).
3. **What's left out:**
   - redirect stubs;
   - time pages: years, decades, centuries, calendar years, "events in a specific year or time period";
   - lists and Wikimedia pages;
   - items whose only date is coarser than a millennium, except where they're geological or prehistoric spans (the axis has room for them).
4. **Lanes (spec §9.4), in bit order:**
   1. Philosophy & religion
   2. Civilisations, cultures & empires
   3. Languages
   4. Film & music
   5. Art & literature
   6. Science & invention
   7. Wars
   8. Events & politics
   9. People

   The roots live in `tools/timeindex/lanes.json` (QID plus label), not in code. A build check fails if any root's label in `classes.jsonl` differs from the file, the guard against the "entity" mistake (§11).
   - People take their lanes from their **first two occupations** only.
   - Languages take human languages and scripts only, with programming languages excluded by root.
   - Events & politics roots: treaty, revolution, election, political party, sovereign state, government agency, disease outbreak, law, protest, coronation.
   - Every root is checked by label and a traced sample of 20, as in §11.
5. **Each article once:** the phone merges all packs' marks and keeps one per QID, preferring the pack Wander prefers (a whole encyclopedia in the main language before subject packs), so a mark opens the fullest copy.
6. **Home order (spec §9.2):**
   - the timeline at the very top, then Wander;
   - then the notices (crash, downloads, tidy offers);
   - then Continue;
   - then the readiness strip, compacted to one row;
   - then the feeds.

   The strip leaves the top. Each section gets the separation treatment from spec §3: its own heading row and surface, and gestures that stay inside it.
7. **Gestures stay in the river:** a horizontal drag scrubs, two fingers pinch, and a vertical drag goes to the list, so Home still scrolls past the timeline. A tap selects; the lens card's Open opens.
8. **Density:** at any zoom the river draws at most about 300 marks: the highest-ranked in the visible window, plus everything within the lens's few years. Queries run on IO, debounced at 60 ms while dragging, and marks are drawn in one Canvas.
9. **No index, no timeline:** a phone with no `.gtime` for any of its packs sees no timeline section, not an empty one. Wander still leads.

## Tasks

### Task 1: The build tool (Python, tested)
`tools/timeindex/`:
- **Inputs:** `lanes.json` (Decision 4) and `build.py ZIM ITEMS CLASSES LANES OUT`.
  - Walks the ZIM's real articles (stub test from `g1measure.py`), joins `items.jsonl` by path, and writes `OUT/<zim stem>.gtime` per Decisions 1–3.
  - The title comes from the ZIM entry, so it matches what the reader shows.
- **`check.py GTIME`:** coverage by lane and kind, plus 100 random rows printed with year, precision, lanes and title for a hand check, and the root label check.
- **`releases/<pack id>.json`:** `fileName`, `sizeBytes`, `sha256`, `count`, `lanesVersion`, `sourceSha256` (the ZIM's), as vectors' releases do.
- **Tests** (`tools/timeindex/test_build.py`, pytest, tiny fixture ZIM made with libzim's writer):
  - a person becomes a lifetime;
  - a battle becomes a point with day precision;
  - an empire becomes a span;
  - 500 BC is stored as −499;
  - a year page is dropped;
  - a stub is skipped;
  - occupations past the second don't add lanes;
  - a bad root label fails the build.
- **Run:** the eight English packs on the Pixel 6. Report sizes and the hand check in this plan.

### Task 2: Reading the index on the phone (Kotlin, tested)
A new `core/timeindex` module, read-only `SQLiteDatabase`, no Room:
- **`TimeIndex.open(file)`**, then `marks(from, to, laneMask, limit)`, `around(year, laneMask, n)` (the lens's neighbours), `count(laneMask)` and `meta`.
- **`TimeIndexes`** finds the `.gtime` for each OK pack (the same lookup as vectors) and merges across packs with one mark per QID (Decision 5).
- **`TimeScale`:** a pure piecewise scale (compressed before about 3000 BC, linear after), with `toX(year)`, `toYear(x)`, `zoom(focus, factor)` and labels by span (millions of years, millennia, centuries, decades, years).
- **Tests:**
  - the BC/AD round trip;
  - scale continuity at the join;
  - the merge keeps the preferred pack;
  - a lane mask filters;
  - the limit keeps the highest ranked;
  - a missing or old-format file is ignored.

### Task 3: Delivery: catalog, download, dev push
The time index follows the search vectors' path step for step (traced 2026-11-08), with one difference: vectors are dropped on phones too small for Ask (`forBuild(includeVectors)`), but **every phone gets the time index**.
- **Catalog generator** (`tools/catalog-gen/catalog_gen.py`):
  - `TIMEINDEX_KEYS` and `timeindex_descriptors()` / `attach_timeindex()` beside `VECTOR_KEYS` / `attach_vectors()` (518, 616–663).
  - Matching: a descriptor matches the pack whose `sha256 == sourceSha256` and copies to that ZIM's other flavours with the same snapshot, except `_mini`, as vectors do.
  - Writes `entry["timeIndex"] = {url, sha256, sizeBytes, count, lanesVersion}`.
  - `curated.json` gets `"timeIndex": {releases, releaseUrl}`, and there's a `timeindex` subcommand.
  - Sizes count in the tier sums (897–899); urls are in `catalog_urls()` for the link check.
  - Tests in `test_catalog_gen.py`.
- **Catalog model** (`core/catalog`):
  - `TimeIndexEntry` on `PackEntry` (Catalog.kt beside `VectorsEntry`), with every field defaulted.
  - In `CatalogParser`: the retry-without-field path, and an invalid entry becomes null while the pack is kept.
  - `downloadBytes` adds it. Not touched by `forBuild`.
  - Tests in `CatalogParserTest`, and a `PublishedCatalogTest` case once published.
- **Download** (`core/downloads`):
  - `ItemKind.TIME_INDEX`, stored by name, so no migration.
  - `TimeIndexDownloads` is a copy of `VectorDownloads` (62 lines). It reconciles against the catalog and records, enqueues when the parent pack is INSTALLED at the matching sha, writes to `timeindex/<itemId>_<sha12>.gtime`, removes old versions, and drops the file when its parent goes.
  - It's started from `GrangerApp` beside `followVectors()` (792–805).
  - The same exclusions as VECTORS: `Downloads.isFromCatalog`, the queue summary's file count.
  - Test: `TimeIndexDownloadsTest`, mirroring `VectorDownloadsTest`.
- **App side:**
  - In the readiness and downloads screens, the time index folds under its pack exactly as vectors do (`ReadinessModel` 308–312, `ReadinessViewModel` `filesOf` 170–185, `DownloadsModel` 131–157) and is deleted with it.
  - Onboarding's `indexBytes` counts it.
  - A shared helper for "companion kinds" (VECTORS, TIME_INDEX) replaces the scattered `== VECTORS` checks where they're the same rule.
- **On-phone lookup:** `TimeIndexes` (Task 2) is fed by installed TIME_INDEX records mapped to their pack (like `installedVectors()`, `GrangerApp` 843–857), plus `files/timeindex/index.tsv` for the dev push.
- **Dev push:** `tools/timeindex/push.py` (a copy of `tools/vectors/push.py`) puts `.gtime` files and `index.tsv` (`file\tpack name`) in `/sdcard/Android/data/app.granger/files/timeindex`, for the phone check before anything is published.
- **Publishing** (a GitHub release `timeindex-<date>/` with the files, then `catalog_gen.py timeindex`, `check-links` and `sign`, then pushing the catalog) is outward-facing, so it waits for Neal's OK after the phone check. Until then only the dev push puts indexes on a phone.

### Task 4: The river (Compose)
`app/.../home/TimelineCard.kt` + `TimelineModel.kt` (owned by `HomeViewModel`, like `WanderModel`):
- **The view:**
  - **Chips** above the river: All plus the nine lanes, showing only lanes with marks on this phone. Multi-select; one or two or three lanes on stack as rows.
  - **The river:** one Canvas. Points are dots, spans are bars, lifetimes are thin bars, and the era shading shows density.
  - **The ruler** labels by zoom.
- **Gestures:** a lens under the thumb, horizontal drag to scrub, pinch to zoom (Decisions 7–8).
- **Reaching one article (spec §3.1):**
  - **The loupe** while dragging: the few years around the lens spread out and labelled above the finger.
  - **The lens card:** year (with "c." and the century wording by precision), title, thumbnail, ‹ › step buttons, the Nearby list of up to five titles, and Open.
  - **Targets:** a 48 dp catch area. On lift it snaps to the nearest mark, preferring one with a picture within a few pixels. When marks collide, the higher rank draws on top.
- **The first frame:** all of history, with the lens on the highest-ranked mark of a random century, so Home doesn't open on the same thing each day.
- **State:** survives rotation and leaving Home; not persisted.
- **Accessibility:**
  - the lens card is the TalkBack target and the step buttons move it;
  - "Show as list" lists the window's marks in time order;
  - with animations off, the lens jumps.
- **Night:** marks and lanes take theme colours and keep contrast in Field and Night.
- **Strings:** English and Spanish (`strings_home.xml`): heading, lane names, "All", "Show as list", "Nearby", "c.", "BC"/"a. C." and so on.
- **Tests:**
  - **Model:** chips change the query, step moves by one, and nearby is ordered by distance.
  - **Compose (Robolectric):** a tap on a step button moves the lens card, Open opens the article, and the section is hidden without an index.

### Task 5: Home order and separation (Decision 6)
- **The order:** timeline, Wander, then the notices, Continue, the compact readiness strip, and the feeds.
- **Separation:** every new section is a surface with its own heading row, with the same spacing band between sections.
- **The compact strip:** one row, the score and the domain icons, tap for Readiness.
- **Test:** a `HomeContent` test of the order, and that a vertical swipe starting on the river scrolls the list.

### Task 6: Phone check, Pixel 6
Shared-phone rules: check `lastUpdateTime` and DB versions first, and never install below the phone's. Push the eight `.gtime` files (dev push, Task 3). Screenshot before each tap.
1. **First draw** within about a second, from all eight packs.
2. **Reaching one article:**
   - scrub to 1066 and open it;
   - pinch from all history to one century;
   - step ‹ › through a crowded year;
   - open from Nearby.
3. **Lanes:** Philosophy & religion with Civilisations stacked; Film & music alone; Events & politics.
4. **Separation:** a vertical swipe on the river scrolls Home, and sections read apart.
5. **TalkBack:** the order, the step buttons and Show as list.
6. **Night, rotation, animations off.**
7. **Smoothness:** 60 fps on scrub and pinch (GPU profile bars).

Findings go into this plan. Then hand to Neal before merging.

## Findings (2026-11-08)
- **Task 1 run** (eight English packs, dump 2026-11-06): 72,032 articles once across packs; 7.0 MB in all (Top 2.3 MB, History 2.1 MB, the rest under 1 MB).
  - Rows: Top 26,599, History 23,056, Physics 7,963, Geography 5,714, Medicine 5,197, Mathematics 5,092, Chemistry 549, Knots 11.
  - **Drop-root fix:** "Wikimedia article page" reached legislation through a bad Wikidata link ("item that needs to be split") and dropped the Treason Act 1351, Salic law and the Code of Justinian. Removed; lists stay dropped by "Wikimedia list article". History's drops (7,784) are now year, decade and century pages and "Deaths in May 2008" lists.
  - **Known lane noise, for v2:** scientific journals count as Art & literature (Chemistry 205); some medieval scholars count as Science.
- **Reader and placement:**
  - The reader lives in `core/library` (Android SQLite; a plain JVM module can't open it), not a new module.
  - Integers go into the SQL text, because Android binds arguments as text and `COALESCE(...)` has no affinity to turn them back into numbers.
  - The readiness strip is already short, so "compact" means moving it below Continue with a "Readiness" heading.
  - The crash notice and the downloads card now sit below the timeline and Wander, per Decision 6. **Check with Neal:** the crash notice is asked once and could be missed down there.
- **Pixel 6, build 1212 and up, after a dev push of the eight indexes:**
  - **Fixed on the phone:**
    - it opened on the whole axis, which squeezed recorded history into a quarter; it now opens on 3000 BC to now;
    - ruler labels collided, and a tick read "2,001 BC"; ticks are now round as written, and a label that would touch its neighbour is skipped;
    - century-precision years read "c. 301"; rough dates now round to their unit (c. 300);
    - the loupe pushed the lens card down; it now floats;
    - the river is taller when it's one row.
  - **Seen working:** the first draw from all eight packs (Mexico 1810, United States 1784, San Marino 301 with pictures); a scrub with the loupe (Andorra 1278 … Amsterdam 1300), settling on Manchester; the Philosophy & religion lane; ‹ › stepping (Hustler 1784 → Wat Phra Kaew 1785); Nearby across a crowded year.
  - **Not yet checked:** pinch and the two-finger pan (adb can't send multi-touch; Neal's hands); ruler pan; TalkBack; rotation; animations off; Field theme; fps.
  - **Feel notes for Neal:** after a lane change, the lens settles on the nearest mark in time, which can be obscure (Hustler); rank could weigh more.

## Not in H5b
- **Read aloud (the owl):** moves to H5c with the games, so one voice button serves the timeline's lens, Wander and the games.
- **Spanish time indexes:** they need the dump re-filtered by eswiki sitelinks; the UI strings are in now.
- **The full-screen Timeline** with search and Show in time (spec §5).
- **The Atlas.**
