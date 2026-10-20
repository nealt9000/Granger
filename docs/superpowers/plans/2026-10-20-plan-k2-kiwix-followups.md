# Plan K2: Kiwix follow-ups — category filter, DevDocs in portrait, the Kiwix folder, Maps — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Neal's points from the K1 walkthrough (2026-10-20):
1. The Kiwix list (927 packs) needs a **category filter** at the top.
2. **DevDocs** packs: the pack's own left nav takes half the page in portrait.
3. **The Kiwix folder:** every Kiwix pack goes in a Kiwix folder under Literature, so the Library's top level stays clean.
4. **Maps** become their own media type.

**Architecture:**
- **`:app` sources:** a row of filter chips on the Kiwix screen. The selected category is held in `SourceViewModel`, beside the query.
- **`:app` reader:** `DevDocsFixes` patches DevDocs pages in `ZimWebViewClient`, next to `MainPageFixes`. On narrow screens the nav becomes a drawer behind a "Contents" button.
- **`:core:library`:** library schema **v10**: `collections.parentId` and a built-in `maps` media type. App-made collections for Kiwix packs sit under an app-made `c:kiwix` folder. `Shelves` returns the children nested in their folder's row. The Library screen shows the folder as one row, which opens a folder screen.

**Tech Stack:** unchanged (Kotlin 2.4.21, Compose M3, Room 2.8.5 with a manual migration 9→10, Robolectric 4.17, JUnit 4 + Truth).

**Roadmap:** "Carried forward from K1" in `2026-10-15-granger-v1-roadmap.md`.

## What the code looks like now (surveyed 2026-10-20, `main` at `fe1acd0`)

- **Kiwix screen:** `SourceViewModel.buildUi` gives Recommended, then categories folded at 8 (`FOLDED_ROWS`) with Show all. A query unfolds them all. No filter.
- **DevDocs pages:** `<devdocs-navbar>` is a Vue custom element with **no shadow DOM** (`shadowRoot:false` in `assets/index.js`). It renders `<section class="_sidebar">` with `_list` and `_list-item` links. The pack's `application.css` lays out `._sidebar` and `._container` (`margin-left: var(--sidebarWidth)` and its variants), with no rule for narrow portrait screens. The reader patches every `text/html` response in `ZimWebViewClient.patched` (`MainPageFixes.patch`).
- **Library:**
  - `media_types` (`id`, `name`, `builtIn`, `sortOrder`), `collections` (`id`, `name`, `mediaTypeId`, `userCreated`, `defaultKey` unique) and `placements` (`packKey`, `collectionId`, `userSet`), in DB version 9. Built-ins are seeded in `seedBuiltIns()`: literature, audiobooks, music, video.
  - Filing (`RoomShelves.settled` → `fileInto`) runs on every read for items with no placement. It reuses a collection with the same `defaultKey` whatever its media type, so changing `Filing.defaultMediaType` alone moves nothing that is already filed.
  - `Shelf(mediaType, collections)` is flat. `LibraryScreen` lists a type's collections, and `CollectionScreen` shows one collection.
  - Nothing nests today. `LibraryPassageFinder` uses the collection's own name for Research, and nothing else reads collections (Auto, widget, Search and the rail don't).
  - Packs have no origin field. Kiwix-ness is the catalog's call (`CatalogScope`, by URL).
- **Tests that pin today's shape:** `MigrationTest.version2DataSurvivesAndBuiltInsAreSeeded` and `DaosTest.freshDatabaseHasBuiltInMediaTypes` assert exactly four built-in types. `ShelvesTest` covers filing. `FakeShelves` is in `app/src/test/.../Fakes.kt`.

## Decisions

Neal's (2026-10-20):
1. **Every Kiwix pack** goes in the Kiwix folder: Wikipedia, Wiktionary, Gutenberg, DevDocs, Stack Exchange, the one-off wikis, and so on. Literature's top level keeps Granger Classics, My books, Archive.org and the user's own collections.
2. **Maps is a media type**, beside Literature, Music and Audiobooks.

Builder's calls (for Neal's review):
- **Which packs are Kiwix's:** every ZIM except Granger's own (`granger_classics*`) and nameless ones ("Other"). That covers sideloaded Kiwix ZIMs too, with no origin field needed. Maps packs go to the Maps type, not the folder.
- **The folder** is a collection row (`c:kiwix`, `defaultKey` "kiwix", app-made, no items of its own) that other collections point at with `parentId`. Only one level. Users can't make folders in K2, and a user-created collection is never put in one.
- **Moving:** moving a collection to another media type clears its `parentId`. Moving a pack into a child collection works as today. The move dialog lists children indented under "Kiwix".
- **The Library row** reads "Kiwix" with the total count of its children. Tapping it opens a folder screen listing the child collections with their counts, sorted by name, and each opens the usual collection screen. A folder whose children are all empty is hidden, like an empty app-made collection.
- **Maps:** one app-made collection, "OpenStreetMap", under the Maps type. Existing maps packs move with their collection in the migration, unless Neal moved the collection himself (it's no longer in Literature).
- **Category filter:** a horizontally scrolling row of chips: "All" (the default), then every category present, in `CATEGORY_ORDER`. Choosing a category shows only that section, unfolded. "All" brings back Recommended and the folds. A query searches within the chosen category. The filter resets when the screen is left.
- **DevDocs:** below 720 px of CSS width, the nav is hidden and the content takes the full width. A small "☰ Contents" button, fixed top-left inside the page, opens the nav as a drawer (85% wide, scrollable) over the page; tapping outside it closes it. A link loads a new page, which closes the drawer. Landscape and wide screens are untouched.

## Global Constraints

- **Don't change the user's library data** except as stated: v9 → v10 adds a column, a media type and the folder row. It sets `parentId` only on app-made, pack-holding Literature collections. It moves only the app-made `c:maps` collection, and only if it's still in Literature. Write the migration test against the exported v9 schema.
- Back up both databases before installing on the Pixel. Install over the app with `tools/dev/phone-wifi.sh install`.
- JVM tests: `./gradlew testDebugUnitTest` and `./gradlew :core:research:test` (1602 before this plan). No `connectedDebugAndroidTest`.
- Build env: `JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home`, `ANDROID_HOME=/opt/homebrew/share/android-commandlinetools`. A new worktree needs `local.properties` copied in and `git submodule update --init --recursive`.
- Words in the UI: "All", "Kiwix", "Maps", "OpenStreetMap", "☰ Contents".

### Task 1: Category filter on the Kiwix screen

**Files:** `app/.../sources/SourceViewModel.kt`, `SourceScreen.kt`, `SourceViewModelTest.kt`.
- [ ] `SourceUi.categories` (in order) and `SourceUi.category` (null = All). `setCategory(name?)`.
- [ ] With a category chosen: only that category's section, unfolded; no Recommended section. Featured packs in that category show inside it.
- [ ] The chip row sits under the search field, Kiwix scope only, as a `LazyRow` of `FilterChip`s.
- [ ] Tests: the chip list and its order; filtering shows one unfolded section, featured packs included; "All" restores Recommended and the folds; a query inside a category; the Granger screen has no chips.

### Task 2: DevDocs in portrait

**Files:** create `app/.../reader/DevDocsFixes.kt` and `app/src/test/.../reader/DevDocsFixesTest.kt`; edit `ZimWebViewClient.kt`.
- [ ] `DevDocsFixes.patch(html)`: when the page has `<devdocs-navbar`, insert a `<style>` and a small `<script>` before `</head>` (or at the start of `<body>` if there's no head). Any other page comes back unchanged.
- [ ] CSS inside `@media (max-width: 720px)`: hide `devdocs-navbar ._sidebar` and zero `._container`'s left margin. `.granger-nav-open` shows the sidebar fixed over the page (85vw, full height, scrollable, the pack's `--sidebarBackground`). The button stays fixed top-left, small and translucent.
- [ ] Script: adds the button. A tap toggles `granger-nav-open` on `<html>`; a tap outside the drawer closes it.
- [ ] Tests: patched only when the navbar is present; inserted once (a second patch is a no-op); works with no `</head>`.
- [ ] Pixel check (Task 4): the Python and PyTorch packs in portrait and landscape.

### Task 3: Library v10 — the Kiwix folder and the Maps type

**Files:** `core/library/.../Entities.kt`, `LibraryDatabase.kt` (MIGRATION_9_10, seed), `Daos.kt`, `Filing.kt`, `Shelves.kt`, `schemas/…/10.json`; tests `MigrationTest.kt`, `DaosTest.kt`, `ShelvesTest.kt`. App: `LibraryScreen.kt`, `LibraryViewModel.kt`, `CollectionScreen.kt`/`CollectionViewModel.kt` (folder view), `OrganizeDialogs.kt`, `GrangerNavHost.kt` if a route is needed, `Fakes.kt`, and their tests.
- [ ] `ShelfCollection.parentId: String?`. Seed `maps`/"Maps" (sortOrder 4) as a built-in.
- [ ] `Filing.isKiwix(pack)` (named, not Granger's own). `Filing.defaultMediaType(pack)` returns "maps" for `maps_*`. The maps collection is named "OpenStreetMap". `Filing.FOLDER_KEY = "kiwix"`.
- [ ] `fileInto`: a new app-made collection for a Kiwix pack (not maps) gets `parentId = c:kiwix`, creating the folder row (`c:kiwix`, "Kiwix", literature) when it's missing.
- [ ] `MIGRATION_9_10`:
  - add the column;
  - insert the maps type;
  - insert `c:kiwix` if any collection will point at it;
  - set `parentId = 'c:kiwix'` for `userCreated = 0 AND mediaTypeId = 'literature' AND defaultKey NOT IN ('granger-classics', 'my-books', 'archive-org', 'other', 'maps', 'kiwix')`, excluding book and media keys;
  - move `c:maps` to `maps` (and rename it "OpenStreetMap") when it's app-made and still in literature.
- [ ] `Shelves`: `CollectionRow.children`. A type's rows are its top-level collections, with folders carrying their non-empty children. A folder is shown when any child has items. `moveCollection` clears `parentId`. `deleteCollection` of a folder isn't offered (app-made).
- [ ] App:
  - the Library row for a folder (count = the sum of its children) opens a folder screen, with the children listed as in the Library;
  - the move dialog indents children under their folder;
  - `FakeShelves` follows.
- [ ] Tests:
  - migration 9→10 on a v9 database holding Wikipedia, DevDocs (`pack:` and family), Maps, Granger Classics, My books, a user collection and a user-moved maps collection: each lands where it should;
  - fresh databases have five built-ins;
  - filing a new Kiwix pack creates or uses the folder;
  - a maps pack files under Maps;
  - moving a child to another type clears the parent;
  - the Library and folder screens' view models.

### Task 4: Pixel 6 walkthrough; roadmap

Installed with `tools/dev/phone-wifi.sh install`, both databases backed up first.
- [ ] Library: Literature shows Kiwix (with the right count), Granger Classics and the rest. Inside Kiwix: Wikipedia, Stack Exchange, DevDocs and the community wikis, each opening its packs. Maps shows OpenStreetMap with the Haiti pack. The reading position and bookmarks are intact.
- [ ] Moving a pack and moving a collection still work. Research still cites with the collection name.
- [ ] The Kiwix screen: the chips filter; All restores the list; search within a chip.
- [ ] DevDocs Python and PyTorch: portrait has full-width content and a working Contents drawer; landscape is as before.
- [ ] Roadmap: a K2 row and a "Carried forward from K2" section.

## Order and size

Tasks 1, 2 and 3 are independent and can be built in parallel. Task 3 is the largest: a schema change plus a new screen. Task 4 needs all three.
