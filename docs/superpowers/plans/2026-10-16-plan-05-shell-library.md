# Granger Plan 5: Shell + Library — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the flat pack list with the redesigned app: the Neural Flame theme and icon, a vertical side toolbar (rail) with tools, per-article bookmarks, History and recent searches, open-to-last-place on launch, a Library organised as media types → collections, Granger and Kiwix source screens that take over the Downloads screen, a Settings screen holding Models, Readiness and storage, and a polished, immersive web-style reader.

**Architecture:** `:core:library` gains schema v3 with two new façades beside `Library`: `Shelves` (media types, collections, where each pack is filed) and `ReadingSessions` (rail tabs, history, recent searches). Packs are filed by their ZIM `Name` (`Pack.name`, falling back to `Pack.id`) so a user's filing survives snapshot updates. `:app` gets a `GrangerShell` composable that wraps every screen with the rail; the reader runs immersive and asks the shell to show the rail on a middle tap. A small `LaunchDecider` picks the start screen from a clean-exit marker and the boot time. The Downloads view model becomes `SourceViewModel`, scoped to Granger, Kiwix or Models. No e-books, no archive.org, no Inquire & Abstract or player yet; their rail slots exist behind `Features` flags.

**Tech Stack:** Kotlin 2.4.21, Compose BOM 2026.09.00 (Material 3), Navigation Compose 2.10.2, Room 2.8.5 (KSP), Robolectric 4.17 for DAO/migration tests, `androidx.webkit:webkit` 1.14.0 (new: algorithmic darkening), jQuery 3.7.1 minified (MIT, new app asset). Test device: Pixel 6.

**Spec:** `docs/superpowers/specs/2026-10-16-library-redesign-design.md` §3 (shell), §4 (library), §5 (Granger + Kiwix only), §7 (web-style reader), §10 (brand), §11 (data model, ZIM part), §13 (cap). Base spec: `docs/superpowers/specs/2026-10-15-granger-v1-design.md`. Roadmap: `docs/superpowers/plans/2026-10-15-granger-v1-roadmap.md`.

## Global Constraints

- Android 10+ (`minSdk = 29`), `compileSdk = 37`, `targetSdk = 36`, Java 17 — unchanged.
- **The app opens to where you left off**: the last reading tab, at its place, immersive. **These open the Library instead: after a crash, after the phone rebooted, and the first open after install** (onboarding replaces this in Plan 10). Swipe-away and OS reclaim are normal opens.
- **Rail, top to bottom:** Tools (Search · Inquire & Abstract · Library · Player) → Bookmarks (open tabs, newest first, **each article its own spot**, about **8** shown) with a **History** button at the bottom of the section → Recent (recent searches; chats in Plan 8; media after v1) → Settings pinned at the bottom.
- **Inquire & Abstract and Player are hidden in this plan** (`Features.INQUIRE = false`, `Features.PLAYER = false`); their slots are code paths behind those flags, not missing code.
- **Rail widths on a phone:** narrow **56 dp** (icons, small covers/letter tiles, recent icons) and expanded **200 dp** over content with a scrim, opened by an edge swipe or tapping the rail. Always visible (narrow) on non-reading screens; hidden while reading until a middle tap.
- **Built-in media types:** Literature, Audiobooks, Music, Video, in that order. Empty ones are hidden. Built-in media types can't be deleted; a user one can be deleted once empty.
- **Archive sources in this plan:** Granger and Kiwix. Archive.org is Plan 7; leave no placeholder row for it.
- **Theme:** dark only, Material 3 tokens in one file. Flame orange accent **`#E8601C`**; near-black surfaces; warm off-white text. Tagline exactly **"Civilization, backed up."**
- **Free cap:** every source download counts (Plan 10 wires the real cap; `capBytes` stays `Long.MAX_VALUE` here).
- User-facing names: "Inquire & Abstract" (never "Ask"), "Library", "Search", "Player", "Settings", "History", "Bookmarks", "Recent", "Granger", "Kiwix", "Models", "Readiness".
- The reader never touches the network (`blockNetworkLoads = true`; app assets are served by interception, not loaded from the web).
- Every commit message ends with `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`. Pushing to `main` is outward-facing: ask Neal first.
- Device tests (`connectedDebugAndroidTest`) wipe downloaded packs on the phone; re-download after running them.

## Review Focus

1. **Library schema upgrade on a phone with real data.** A v2 database with packs, saved articles and reading positions must open as v3 with every row intact, built-in media types seeded once, and existing packs filed by the default rules, not left out of every collection → `version2DataSurvivesAndBuiltInsAreSeeded`, `packsWithoutAPlacementAreFiledOnFirstRead` (Tasks 2, 3).
2. **A rail tab whose pack is gone** (deleted, SD card out, replaced by a new snapshot with a different id). Launch must not crash or show a blank reader; it falls back to the Library with a notice, the tab stays on the rail greyed out, and a new snapshot inherits the old pack's tabs and history as it already does bookmarks → `launchWithMissingPackOpensLibraryWithNotice`, `newSnapshotInheritsTabsAndHistory` (Tasks 4, 5).
3. **Following links inside one article view** must update that tab, not add a new rail spot for every page, while opening from Library, Search or History adds a spot. The cap of 8 drops the oldest from the rail but never from History → `navigatingWithinATabUpdatesIt`, `openingFromSearchAddsATab`, `ninthTabDropsOffTheRailButStaysInHistory` (Tasks 4, 10).
4. **A crash during launch itself** (the reader crashes on restore) must not loop: the next launch lands on the Library because the clean-exit marker was never written → `crashBeforeCleanExitOpensLibrary` (Task 5).
5. **User filing across updates and odd names.** A pack moved to a user collection stays there after its snapshot updates; a pack with a blank `Name` (sideloaded or damaged file) still gets a collection ("Other") and can be moved; deleting a user collection that still has packs moves them back to their default collection → `movedPackKeepsItsPlaceAfterUpdate`, `blankNameFilesUnderOther`, `deletingACollectionRefilesItsPacks` (Task 3).

---

## File Structure

```
core/library/src/main/kotlin/app/granger/core/library/
  Entities.kt            + MediaType, ShelfCollection, Placement, ReadingTab, HistoryEntry, RecentSearch
  Daos.kt                + ShelfDao, SessionDao
  LibraryDatabase.kt     version 3, Migration2To3, built-in seed
  Filing.kt              (new) default media type + collection for a pack
  Shelves.kt             (new) Shelves interface + RoomShelves
  ReadingSessions.kt     (new) ReadingSessions interface + RoomReadingSessions
  RoomLibrary.kt         inherit(): also moves tabs and history
core/library/schemas/…/3.json  (generated)

app/src/main/kotlin/app/granger/
  theme/GrangerTheme.kt          (new) colour tokens + MaterialTheme wrapper
  Features.kt                    (new) INQUIRE, PLAYER flags
  launch/LaunchDecider.kt        (new) pure start-screen decision
  launch/SessionMarker.kt        (new) clean-exit flag + boot time in SharedPreferences
  shell/GrangerShell.kt          (new) rail + content + scrim, immersive control
  shell/Rail.kt                  (new) narrow and expanded rail composables
  shell/RailViewModel.kt         (new)
  shell/HistoryScreen.kt, HistoryViewModel.kt  (new) History + Saved tabs (replaces bookmarks/)
  library/LibraryScreen.kt       rewritten: Media + Archive sources
  library/LibraryViewModel.kt    rewritten
  library/CollectionScreen.kt, CollectionViewModel.kt  (new)
  library/OrganizeDialogs.kt     (new) move / create / rename / delete
  sources/SourceViewModel.kt     (from downloads/DownloadsViewModel.kt, scoped)
  sources/SourceScreen.kt        (from downloads/DownloadsScreen.kt)
  sources/CatalogScope.kt        (new) GRANGER, KIWIX, MODELS + entry → scope
  settings/SettingsScreen.kt     (new) Models, Readiness, Storage & downloads, About
  reader/…                       immersive, tabs, selection actions, find, darkening, jQuery
  search/…                       prefill, recent searches, open-as-new-tab
  GrangerNavHost.kt, MainActivity.kt, GrangerApp.kt
app/src/main/assets/reader/jquery-3.7.1.min.js  (new)
app/src/main/res/mipmap-anydpi/ic_launcher.xml, drawable/ic_launcher_*.xml, drawable/granger_mark.xml (new)
art/brand/neural-flame-concept-03.jpeg  (moved from repo root)
```

`bookmarks/` (BookmarksScreen, BookmarksViewModel and its test) is deleted in Task 6; its "Saved" list moves into HistoryScreen.

---

### Task 1: Theme, icon and brand assets

**Files:**
- Create: `app/src/main/kotlin/app/granger/theme/GrangerTheme.kt`, `app/src/main/res/drawable/granger_mark.xml`, `app/src/main/res/drawable/ic_launcher_foreground.xml`, `app/src/main/res/drawable/ic_launcher_monochrome.xml`, `app/src/main/res/values/colors.xml`, `app/src/main/res/mipmap-anydpi/ic_launcher.xml`, `app/src/test/kotlin/app/granger/theme/GrangerThemeTest.kt`
- Move: `Gemini_Generated_Image_fe0rssfe0rssfe0r.jpeg` → `art/brand/neural-flame-concept-03.jpeg`
- Modify: `app/src/main/AndroidManifest.xml` (`android:icon`, `android:roundIcon`), `MainActivity.kt` (`GrangerTheme` instead of `MaterialTheme`)

**Interfaces:**
- Produces: `object GrangerColors { val Flame: Color; val Surface: Color; val OnSurface: Color; … }`, `val GrangerColorScheme: ColorScheme` (dark), `@Composable fun GrangerTheme(content: @Composable () -> Unit)`, drawable `R.drawable.granger_mark` (the flame-over-book mark, single colour, tintable).

- [ ] **Step 1: Write the failing test** `GrangerThemeTest`:
  - `accentIsFlameOrange`: `GrangerColorScheme.primary == Color(0xFFE8601C)`.
  - `textIsReadableOnSurfaces`: WCAG contrast ratio ≥ 4.5 for `onSurface`/`surface`, `onBackground`/`background`, `onSurfaceVariant`/`surfaceVariant`, and ≥ 3.0 for `primary`/`surface`. Write a small `contrast(a: Color, b: Color): Double` helper in the test (relative luminance per WCAG 2.x).
- [ ] **Step 2:** `./gradlew :app:testDebugUnitTest --tests '*GrangerThemeTest*'` → FAIL (unresolved `GrangerColorScheme`).
- [ ] **Step 3: Implement** `GrangerTheme.kt` with `darkColorScheme(...)`: background ≈ `#0E0E0E`, surface ≈ `#141414`, surfaceVariant ≈ `#1D1D1D`, onSurface ≈ `#ECE6DA` (warm off-white), primary `#E8601C`, onPrimary near-black. Every colour lives in `GrangerColors` so a later light scheme only adds a second scheme.
- [ ] **Step 4:** Rerun → PASS.
- [ ] **Step 5: Icon.** `git mv` the concept image to `art/brand/`. Hand-trace the mark (flame outline with circuit traces and nodes over an open book; no wordmark) as a vector drawable `granger_mark.xml` on a 108 dp viewport inside the 66 dp safe zone. `ic_launcher_foreground` = the mark in `#E8601C` over transparent with the book in off-white; `ic_launcher_monochrome` = the mark in one colour; adaptive background = `@color/granger_background` (`#0E0E0E`). Point the manifest at `@mipmap/ic_launcher`.
- [ ] **Step 6:** `./gradlew :app:assembleDebug`, install on the Pixel 6, and check the launcher icon in normal and themed-icon modes. Expected: the flame-over-book mark is legible at launcher size and not clipped by the circle mask.
- [ ] **Step 7: Commit** `Theme and icon: Neural Flame mark, dark Material 3 scheme with flame accent`.

---

### Task 2: Library schema v3

**Files:**
- Modify: `core/library/.../Entities.kt`, `Daos.kt`, `LibraryDatabase.kt`
- Test: `core/library/src/test/.../MigrationTest.kt`, `DaosTest.kt`

**Interfaces:**
- Produces (entities; table names in backticks):
  - `MediaType(id: String, name: String, builtIn: Boolean, sortOrder: Int)` — `media_types`. Built-in ids: `literature`, `audiobooks`, `music`, `video`.
  - `ShelfCollection(id: String, name: String, mediaTypeId: String, userCreated: Boolean, defaultKey: String?)` — `collections`. `defaultKey` is the filing key (Task 3) for collections the app made (`"wikipedia"`, `"other"`, …); null for user collections. Unique index on `defaultKey`.
  - `Placement(packKey: String, collectionId: String, userSet: Boolean)` — `placements`, PK `packKey`. `packKey` = `Pack.name` if not blank, else `Pack.id`.
  - `ReadingTab(id: Long autogen, packId: String, path: String, title: String, openedAt: Long, onRail: Boolean)` — `reading_tabs`.
  - `HistoryEntry(packId: String, path: String, title: String, openedAt: Long)` — `history`, PK (`packId`, `path`), index on `openedAt`.
  - `RecentSearch(query: String, searchedAt: Long)` — `recent_searches`, PK `query`.
  - `ShelfDao`: observe/get/upsert/delete for media types, collections, placements; `collectionByDefaultKey(key)`; `placementsIn(collectionId)`.
  - `SessionDao`: `observeRailTabs(limit: Int): Flow<List<ReadingTab>>` (`onRail = 1 ORDER BY openedAt DESC LIMIT :limit`), `latestTab(): ReadingTab?`, `tab(id)`, `insertTab(tab): Long`, `updateTab(tab)`, `deleteTab(id)`, `moveTabsToPack(from, to)`, history upsert/observe (newest first)/`moveHistoryToPack(from, to)` (`UPDATE OR IGNORE`)/`deleteHistoryForPack`, recent-search upsert/observe(limit)/trim-to(limit).
  - `LibraryDatabase` version 3 with `MIGRATION_2_3` (hand-written: creates the six tables and inserts the four built-in media types). The `RoomDatabase.Callback.onCreate` inserts the same built-ins for fresh installs; both use one `seedBuiltIns(db: SupportSQLiteDatabase)`.

- [ ] **Step 1: Failing tests.**
  - `MigrationTest.version2DataSurvivesAndBuiltInsAreSeeded`: create v2 with one pack, one bookmark and one reading position; migrate to 3 with validation; assert the three rows remain and `SELECT id FROM media_types ORDER BY sortOrder` = `[literature, audiobooks, music, video]`.
  - `DaosTest.freshDatabaseHasBuiltInMediaTypes`: an in-memory v3 db lists the four built-ins once.
  - `DaosTest.railTabsAreNewestFirstAndLimited`: insert 10 tabs with increasing `openedAt`, one with `onRail = false`; `observeRailTabs(8).first()` returns the 8 newest rail tabs.
  - `DaosTest.historyUpsertKeepsOneRowPerArticle`: upserting the same (`packId`, `path`) twice keeps one row with the later `openedAt`.
- [ ] **Step 2:** `./gradlew :core:library:testDebugUnitTest` → the new tests FAIL.
- [ ] **Step 3: Implement** entities, DAOs, migration and seed; build once so KSP writes `schemas/…/3.json`, and commit that file.
- [ ] **Step 4:** Rerun → all `:core:library` tests PASS.
- [ ] **Step 5: Commit** `Library schema v3: media types, collections, placements, reading tabs, history, recent searches`.

---

### Task 3: Filing and `Shelves`

**Files:**
- Create: `core/library/.../Filing.kt`, `core/library/.../Shelves.kt`, test `core/library/src/test/.../ShelvesTest.kt`

**Interfaces:**
- Consumes: Task 2 entities and `ShelfDao`; `Library.packs`.
- Produces:
  - `object Filing { fun packKey(pack: Pack): String; fun defaultKey(pack: Pack): String; fun defaultName(key: String, pack: Pack): String; fun defaultMediaType(pack: Pack): String /* "literature" for every ZIM */ }`. The mapping table, prefix of `Pack.name` → key/name, kept in one `val RULES` list:

    | `Pack.name` starts with | key | collection name |
    | --- | --- | --- |
    | `wikipedia_` (includes `wikipedia_en_medicine`) | `wikipedia` | Wikipedia |
    | `wiktionary_` | `wiktionary` | Wiktionary |
    | `wikisource_` | `wikisource` | Wikisource |
    | `wikem_` | `wikem` | WikEM |
    | `gutenberg_` | `gutenberg` | Gutenberg |
    | `granger_classics` | `granger-classics` | Granger Classics |
    | `ifixit_` | `ifixit` | iFixit |
    | contains `stackexchange` or `stackoverflow` | `stack-exchange` | Stack Exchange |
    | anything else, non-blank name | `pack:<name>` | the pack's title |
    | blank name | `other` | Other |
  - `data class Shelf(val mediaType: MediaType, val collections: List<CollectionRow>)`, `data class CollectionRow(val collection: ShelfCollection, val packs: List<Pack>)`.
  - `interface Shelves { val shelves: Flow<List<Shelf>> /* media types with ≥1 pack, in sortOrder; collections sorted by name */; fun collection(id: String): Flow<CollectionRow?>; suspend fun movePack(pack: Pack, toCollectionId: String); suspend fun moveCollection(collectionId: String, toMediaTypeId: String); suspend fun createMediaType(name: String): String; suspend fun deleteMediaType(id: String): Boolean /* false if built-in or not empty */; suspend fun createCollection(name: String, mediaTypeId: String): String; suspend fun renameCollection(id: String, name: String); suspend fun deleteCollection(id: String) }`
  - `class RoomShelves(db: LibraryDatabase, library: Library, now: () -> Long = …) : Shelves`. When `shelves` is computed, any pack without a placement is filed: find or create the collection with its `defaultKey` (under `defaultMediaType`) and insert `Placement(userSet = false)`. Collections the app made that end up empty are hidden, not deleted. `deleteCollection` re-files its packs to their default collections, whatever their `userSet`.

- [ ] **Step 1: Failing tests** in `ShelvesTest` (Robolectric, in-memory db, `FakeZimOpener`-backed `RoomLibrary` or a stub `Library` exposing `packs`):
  - `packsWithoutAPlacementAreFiledOnFirstRead`: packs named `wikipedia_en_top`, `wikipedia_en_medicine`, `ifixit_en_all` → one Literature shelf with collections `Wikipedia` (2 packs) and `iFixit` (1).
  - `blankNameFilesUnderOther`.
  - `movedPackKeepsItsPlaceAfterUpdate`: move `wikipedia_en_top` (id `u1`) to a user collection; replace it with id `u2`, same name → still in the user collection.
  - `moveCollectionToNewMediaType`: create media type "Reference"; move `Wikipedia` there → two shelves, Literature and Reference.
  - `builtInMediaTypeCannotBeDeleted` and `nonEmptyUserMediaTypeCannotBeDeleted` (both return false).
  - `deletingACollectionRefilesItsPacks`.
  - `emptyMediaTypesAreHidden`: with only ZIM packs, `shelves` has no Audiobooks/Music/Video.
- [ ] **Step 2:** Run → FAIL.
- [ ] **Step 3: Implement** `Filing` and `RoomShelves`; writes inside `db.withTransaction`.
- [ ] **Step 4:** Run → PASS.
- [ ] **Step 5: Commit** `Shelves: file packs into media types and collections; user moves survive updates`.

---

### Task 4: `ReadingSessions` (tabs, history, recent searches)

**Files:**
- Create: `core/library/.../ReadingSessions.kt`, test `ReadingSessionsTest.kt`
- Modify: `RoomLibrary.kt` (`inherit()` also calls `sessionDao.moveTabsToPack` and `moveHistoryToPack`; `remove()` deletes the pack's history and tabs)

**Interfaces:**
- Produces:
  - `interface ReadingSessions { fun railTabs(limit: Int = RAIL_LIMIT): Flow<List<ReadingTab>>; val history: Flow<List<HistoryEntry>>; fun recentSearches(limit: Int = 5): Flow<List<String>>; suspend fun openTab(packId: String, path: String, title: String): Long; suspend fun showInTab(tabId: Long, packId: String, path: String, title: String); suspend fun closeTab(tabId: Long); suspend fun lastTab(): ReadingTab?; suspend fun tab(id: Long): ReadingTab?; suspend fun recordSearch(query: String) }` with `const val RAIL_LIMIT = 8`.
  - `openTab` inserts an on-rail tab and a history row, and returns the tab id. If an on-rail tab already shows the same (`packId`, `path`), it bumps that tab's `openedAt` and returns its id. `showInTab` updates the tab's path/title/`openedAt` and upserts history. `closeTab` sets `onRail = false`; a tab off the rail is never shown again. `recordSearch` trims the query, ignores blanks, upserts and trims the table to 50 rows.
  - `class RoomReadingSessions(db: LibraryDatabase, now: () -> Long = System::currentTimeMillis) : ReadingSessions`.

- [ ] **Step 1: Failing tests:**
  - `openingFromSearchAddsATab` (two different articles → two rail tabs, newest first).
  - `reopeningTheSameArticleReusesItsTab`.
  - `navigatingWithinATabUpdatesIt` (one tab, path changes, history has both articles).
  - `ninthTabDropsOffTheRailButStaysInHistory` (9 opens → `railTabs()` has 8, `history` has 9).
  - `closedTabLeavesTheRailButNotHistory`.
  - `recentSearchesAreNewestFirstAndDeduplicated` (blank query ignored).
  - In `RoomLibraryTest`: `newSnapshotInheritsTabsAndHistory` (the existing snapshot-inherit fixture, plus a tab and a history row on the old id → both on the new id).
- [ ] **Step 2:** Run → FAIL.
- [ ] **Step 3: Implement.**
- [ ] **Step 4:** `./gradlew :core:library:testDebugUnitTest` → PASS.
- [ ] **Step 5: Commit** `Reading sessions: rail tabs per article, history, recent searches`.

---

### Task 5: Launch decision

**Files:**
- Create: `app/src/main/kotlin/app/granger/launch/LaunchDecider.kt`, `launch/SessionMarker.kt`, test `app/src/test/kotlin/app/granger/launch/LaunchDeciderTest.kt`

**Interfaces:**
- Produces:
  - `sealed interface StartScreen { data class Reader(val tabId: Long) : StartScreen; data class Library(val notice: String? = null) : StartScreen }`
  - `data class LaunchFacts(val previousExitClean: Boolean, val bootedSinceLastSession: Boolean, val firstRun: Boolean, val lastTab: ReadingTab?, val lastTabPackOk: Boolean)`
  - `object LaunchDecider { fun decide(f: LaunchFacts): StartScreen }`. Library when `firstRun || !previousExitClean || bootedSinceLastSession || lastTab == null`. If the last tab's pack isn't OK → `Library(MISSING_NOTICE)` where `MISSING_NOTICE = "The last thing you were reading isn't available. Check that its pack is still on the phone or SD card."`. Otherwise `Reader(lastTab.id)`.
  - `class SessionMarker(prefs: SharedPreferences, bootTimeMillis: () -> Long = { System.currentTimeMillis() - SystemClock.elapsedRealtime() })` with `fun readAndStartSession(): Triple<Boolean /*clean*/, Boolean /*booted*/, Boolean /*firstRun*/>` and `fun markCleanExit()` / `fun markDirty()`. Boot comparison tolerates ±5 s of clock jitter. `readAndStartSession` records the session as dirty until `markCleanExit`.
  - Wiring (Task 6): `MainActivity.onStop` → `markCleanExit()`; `onStart` → `markDirty()`. A process killed while backgrounded has already passed `onStop`, so it counts as clean.

- [ ] **Step 1: Failing tests:** `normalOpenResumesLastTab`, `firstRunOpensLibrary`, `crashBeforeCleanExitOpensLibrary`, `rebootOpensLibrary`, `noTabsOpensLibrary`, `launchWithMissingPackOpensLibraryWithNotice`. For `SessionMarker` (Robolectric `SharedPreferences`): `firstEverReadIsFirstRun`, `cleanExitThenReadIsClean`, `startWithoutCleanExitIsDirty`, `bootTimeChangeBeyondToleranceIsReboot`.
- [ ] **Step 2:** Run → FAIL.
- [ ] **Step 3: Implement.**
- [ ] **Step 4:** Run → PASS.
- [ ] **Step 5: Commit** `Launch decision: resume the last tab unless first run, crash or reboot`.

---

### Task 6: Shell, rail, navigation and History

**Files:**
- Create: `Features.kt`, `shell/GrangerShell.kt`, `shell/Rail.kt`, `shell/RailViewModel.kt`, `shell/HistoryScreen.kt`, `shell/HistoryViewModel.kt`, tests `shell/RailViewModelTest.kt`, `shell/HistoryViewModelTest.kt`
- Modify: `GrangerNavHost.kt`, `MainActivity.kt`, `GrangerApp.kt` (`shelves`, `sessions`, `sessionMarker` in `AppContainer`), `app/src/test/.../Fakes.kt` (`FakeShelves`, `FakeReadingSessions`)
- Delete: `bookmarks/BookmarksScreen.kt`, `bookmarks/BookmarksViewModel.kt`, `app/src/test/.../bookmarks/BookmarksViewModelTest.kt`

**Interfaces:**
- Consumes: `ReadingSessions` (Task 4), `Library.packs`, `LaunchDecider`/`SessionMarker` (Task 5), `granger_mark` (Task 1).
- Produces:
  - `object Features { const val INQUIRE = false; const val PLAYER = false }`
  - Routes (`@Serializable`): `LibraryRoute(val notice: String? = null)`, `ReaderRoute(val tabId: Long)`, `SearchRoute(val query: String = "")`, `HistoryRoute`, `ReadinessRoute`, plus `DownloadsRoute` kept as-is until Task 8. `BookmarksRoute` is removed. Tasks 7 and 8 add `CollectionRoute(val collectionId: String)`, `SourceRoute(val scope: CatalogScope)` and `SettingsRoute`; until Task 8, the rail's Settings tool opens `DownloadsRoute`.
  - Until Task 9 changes `ReaderViewModel`, the `ReaderRoute(tabId)` destination looks up the tab with `sessions.tab(tabId)` and builds today's `ReaderViewModel(tab.packId, tab.path, …)`. The existing Library screen's pack tap calls `sessions.openTab(pack.id, library.startPath(pack.id) ?: "", pack.title)` and then navigates to `ReaderRoute`.
  - `data class RailTabUi(val tabId: Long, val title: String, val subtitle: String /* pack title */, val initial: Char, val available: Boolean, val current: Boolean)`; `data class RailUi(val tabs: List<RailTabUi>, val recentSearches: List<String>)`. `class RailViewModel(sessions: ReadingSessions, library: Library, currentTabId: StateFlow<Long?>) { val ui: StateFlow<RailUi>; fun closeTab(tabId: Long) }`.
  - `@Composable fun GrangerShell(rail: RailUi, expanded: Boolean, railVisible: Boolean, selected: RailTool?, onToggleExpanded, onTool: (RailTool) -> Unit, onTab: (Long) -> Unit, onCloseTab: (Long) -> Unit, onHistory, onRecentSearch: (String) -> Unit, miniPlayer: (@Composable () -> Unit)? = null, content: @Composable () -> Unit)`. `miniPlayer` is drawn above `content` in the content column, pushing it down. It is always null in this plan, and it's the post-v1 player's slot and `enum class RailTool { SEARCH, INQUIRE, LIBRARY, PLAYER, SETTINGS }`. Tools whose flag is false are not drawn. The narrow rail shows bookmark tabs as letter tiles (`initial`, current one outlined in flame) because ZIM articles have no covers. The expanded rail shows title + pack title, then "History", then "Recent" with search text. Edge swipe (from x < 24 dp) or tapping the rail background expands it; tapping the scrim or choosing an item collapses it. Long-press a tab → "Remove from bookmarks".
  - `class ShellState { var railVisible: Boolean; var expanded: Boolean }` held in `GrangerNavHost`. Non-reader destinations force `railVisible = true`. The reader sets it through a callback (Task 9).
  - `HistoryViewModel(sessions, library)` with two lists: History (every `HistoryEntry`, newest first, with pack title and availability) and Saved (the existing `Library.bookmarks`, starred articles). Tapping a row opens it with `sessions.openTab`, then `ReaderRoute`.
  - Start destination: `MainActivity` calls `sessionMarker.readAndStartSession()` and `sessions.lastTab()`, resolves `lastTabPackOk` from `library.packs.first()`, runs `LaunchDecider.decide`, and passes the result as `GrangerNavHost(container, start)`. A `ReaderRoute` start has `LibraryRoute` beneath it on the back stack, so Back from a resumed book lands on the Library.

- [ ] **Step 1: Failing tests:**
  - `RailViewModelTest.tabsShowPackTitleAndAvailability` (a tab whose pack is MISSING → `available = false`).
  - `currentTabIsMarked`.
  - `closeTabCallsSessions`.
  - `recentSearchesPassThrough`.
  - `HistoryViewModelTest.historyNewestFirstWithPackTitles`.
  - `savedListsStarredArticles`.
  - `openingFromHistoryAddsATab`.
- [ ] **Step 2:** Run → FAIL.
- [ ] **Step 3: Implement** the view models, the shell and rail composables, the routes, the start decision and the `onStart`/`onStop` marker calls. Move the old `BookmarksViewModel` "Removed pack" handling into `HistoryViewModel`.
- [ ] **Step 4:** `./gradlew :app:testDebugUnitTest` → PASS. (The existing Library, Downloads and Reader tests still pass unchanged: those screens are migrated in Tasks 7–9.)
- [ ] **Step 5: Device check:** install on the Pixel 6.
  - Expected on a non-reading screen: the narrow rail shows Search, Library and Settings at the bottom, with no Inquire & Abstract or Player.
  - An edge swipe expands the rail with a scrim.
  - Rotating keeps the expanded/collapsed state.
- [ ] **Step 6: Commit** `Shell: side rail with tools, per-article bookmarks, History and recent searches; launch resumes the last tab`.

---

### Task 7: Library and collection screens

**Files:**
- Rewrite: `library/LibraryScreen.kt`, `library/LibraryViewModel.kt`, test `library/LibraryViewModelTest.kt`
- Create: `library/CollectionScreen.kt`, `library/CollectionViewModel.kt`, `library/OrganizeDialogs.kt`, test `library/CollectionViewModelTest.kt`

**Interfaces:**
- Consumes: `Shelves` (Task 3), `ReadingSessions.openTab` (Task 4), `Library.refresh/startPath/remove`, `CatalogScope` (Task 8 defines the enum; this task needs only `GRANGER` and `KIWIX`, so define the enum here in `sources/CatalogScope.kt`, and Task 8 adds behaviour).
- Produces:
  - `LibraryViewModel(shelves, library)` with `val ui: StateFlow<LibraryUi?>`, where `LibraryUi(val media: List<Shelf>, val sources: List<CatalogScope> /* [GRANGER, KIWIX] */, val emptyLibrary: Boolean)`. It refreshes on resume, as today. The empty state keeps "Or copy .zim files into <packs folder>" and adds a "Get packs from Granger or Kiwix" pointer to the sources below.
  - `LibraryScreen`: a "Media" header, then each shelf as an expandable row showing the media type name and its collections with counts ("3 packs"). Then an "Archive sources" header with Granger and Kiwix rows. A notice from `LibraryRoute.notice` appears as a snackbar.
  - `CollectionViewModel(collectionId, shelves, library, sessions)` with `ui: StateFlow<CollectionRow?>` and `fun open(pack: Pack): Long /* tab id */`. `open` calls `library.startPath`, then `sessions.openTab(pack.id, path, title)`, where the title is the pack title until the page reports its own. Organize actions pass through to `Shelves`.
  - `CollectionScreen`: a list of packs using the existing `PackRow` details/problem text (moved here). The overflow menu has Move collection to…, Rename (user collections only) and Delete (user collections only). Long-press a pack for Move to collection… and Remove from phone (`library.remove`, with confirm). Media type creation is offered inside the "Move to…" dialogs ("New media type…").

- [ ] **Step 1: Failing tests:**
  - `LibraryViewModelTest.showsShelvesAndBothSources`.
  - `emptyLibraryIsFlagged`.
  - `refreshOnResume` (keep the existing one).
  - `CollectionViewModelTest.openStartsATabAtTheLastReadPath`.
  - `openUnavailablePackDoesNothing`.
  - `renameAndDeleteOnlyForUserCollections` (the UI flags come from `userCreated`).
- [ ] **Step 2:** Run → FAIL.
- [ ] **Step 3: Implement.**
- [ ] **Step 4:** Run → PASS.
- [ ] **Step 5: Commit** `Library: media types and collections, archive sources, organise packs`.

---

### Task 8: Source screens and Settings

**Files:**
- Move and modify: `downloads/DownloadsViewModel.kt` → `sources/SourceViewModel.kt`; `downloads/DownloadsScreen.kt` → `sources/SourceScreen.kt`; `downloads/ItemStatus.kt`, `DownloadPrefs.kt` → `sources/`; tests → `app/src/test/.../sources/`
- Modify: `sources/CatalogScope.kt` (Task 7 created the enum; add `scopeOf`)
- Create: `settings/SettingsScreen.kt`, test `sources/CatalogScopeTest.kt`
- Modify: `GrangerNavHost.kt` (`SourceRoute`, `SettingsRoute`, `ReadinessRoute` reached from Settings)

**Interfaces:**
- Produces:
  - `enum class CatalogScope(val title: String) { GRANGER("Granger"), KIWIX("Kiwix"), MODELS("Models") }` and `fun scopeOf(item: CatalogItem): CatalogScope`. `ModelEntry` → MODELS. A `PackEntry` with any URL host ending `kiwix.org` → KIWIX; otherwise → GRANGER. (The Classics release URL is `github.com/nealt9000/Granger/releases/...`.)
  - `SourceViewModel(scope, catalog, downloads, library, volumes, prefs, tier, appScope)` keeps every existing behaviour, filtered to `scopeOf(item) == scope`.
    - Presets appear only for GRANGER.
    - New state: `tab: SourceTab` (`BROWSE`, `INSTALLED`, `UPDATES`) and `query: String`.
    - `BROWSE` shows everything in scope.
    - `INSTALLED` shows status `Installed`, `UpdateAvailable` or an active download.
    - `UPDATES` shows `UpdateAvailable`.
    - `query` filters by case-insensitive substring of title or description.
    - Sections still group by category, in the existing order.
  - `SourceScreen(viewModel, title)`:
    - Top: a search field, the tabs, and a one-line storage summary for the selected volume ("38.2 GB free on Phone · Granger uses 4.1 GB").
    - Wi-Fi-only and volume choice move to Settings.
    - Rows, presets, notices and the "before download" confirmation are unchanged.
  - `SettingsScreen` sections:
    - **Models**: the `SourceScreen` content for MODELS, inline or as its own route.
    - **Readiness**: navigates to the existing Readiness screen.
    - **Storage & downloads**: the existing `StorageSection` with volume choice, plus the Wi-Fi-only switch.
    - **About**: `granger_mark`, "GRANGER", "Civilization, backed up.", version, and "GPLv3 · source on GitHub".

- [ ] **Step 1: Failing tests:**
  - `CatalogScopeTest.kiwixHostIsKiwix`, `githubReleaseIsGranger` and `modelsAreModels`.
  - In `SourceViewModelTest` (renamed `DownloadsViewModelTest`, existing tests kept with `scope` set to the scope their items belong to):
    - `onlyItemsInScopeAreListed`.
    - `presetsOnlyOnGranger`.
    - `installedTabShowsInstalledUpdatesAndActive`.
    - `updatesTabShowsOnlyUpdates`.
    - `queryFiltersTitleAndDescription`.
- [ ] **Step 2:** Run → FAIL.
- [ ] **Step 3: Implement.** Delete the old `downloads/` package once everything compiles against `sources/`.
- [ ] **Step 4:** `./gradlew :app:testDebugUnitTest` → PASS.
- [ ] **Step 5: Commit** `Archive sources: Granger and Kiwix screens with browse, installed, updates and search; Settings holds Models, Readiness and storage`.

---

### Task 9: Immersive, polished web-style reader

**Files:**
- Modify: `reader/ReaderScreen.kt`, `reader/ReaderViewModel.kt`, `reader/ReaderWebView.kt`, `reader/ZimWebViewClient.kt`, tests `reader/ReaderViewModelTest.kt`
- Create: `reader/ReaderPageScript.kt` (JS injected after each page load, with the `JavascriptInterface` bridge), `reader/MainPageFixes.kt` + test `reader/MainPageFixesTest.kt`, `app/src/main/assets/reader/jquery-3.7.1.min.js`
- Modify: `gradle/libs.versions.toml`, `app/build.gradle.kts` (`androidx.webkit:webkit:1.14.0`)
- Test (device): `app/src/androidTest/.../reader/ReaderWebViewTest.kt`

**Interfaces:**
- Consumes: `ReadingSessions.tab/showInTab` (Task 4), `ShellState` callback (Task 6), `SearchRoute(query)`.
- Produces:
  - `ReaderViewModel(tabId: Long, library, sessions, appScope)`. On start it reads the tab and resolves the pack's archive and the tab's path. It keeps today's redirect resolution and error messages, adding `TAB_GONE = "This bookmark was removed."`. `onPageShown` also calls `sessions.showInTab(tabId, packId, path, title)`. `state` gains `chromeVisible: Boolean` (starts false) and `findQuery: String?`. Methods: `toggleChrome()`, `startFind()`, `endFind()`.
  - `ReaderScreen(viewModel, resolver, onChromeVisibleChange: (Boolean) -> Unit, onSearchText: (String) -> Unit, onBack)`.
    - Full screen, with the system bars hidden while chrome is hidden (`WindowInsetsControllerCompat`, transient bars by swipe).
    - When `chromeVisible`: a top bar (back, title, find-in-page, star) and the shell rail, via `onChromeVisibleChange`.
  - **Middle tap:** `ReaderPageScript` adds a click listener. A click whose target has no `a`, `button`, `input`, `summary` or `[onclick]` ancestor, landing in the middle 60 % of the width, calls `GrangerBridge.toggleChrome()`. The bridge is added with `addJavascriptInterface` under the name `GrangerBridge`. It exposes only `toggleChrome()` and posts to the main thread.
  - **Selection actions:** `ReaderWebView` overrides `startActionMode` to add "Search" (and "Inquire & Abstract" when `Features.INQUIRE`) to the system text-selection menu, which already has Copy. Each action reads the selection via `evaluateJavascript("window.getSelection().toString()")`. Search opens `SearchRoute(query = selection.take(200))`.
  - **Find in page:** `WebView.findAllAsync` / `findNext`, from a find bar in the chrome showing "3 of 12".
  - **Dark pages:** with `WebSettingsCompat.setAlgorithmicDarkeningAllowed(settings, true)` when `WebViewFeature.isFeatureSupported(ALGORITHMIC_DARKENING)`, plus the `GrangerTheme` dark `forceDark`/`isLightTheme=false` app theme attribute so WebView darkens pages that don't style themselves.
  - **Main-page jQuery fix:** `object MainPageFixes { fun patch(html: String): String }` inserts `<script src="https://zim.granger.invalid/_granger/jquery-3.7.1.min.js"></script>` immediately before the first `<script` whose `src` ends with `_res_/script.js`, but only when the HTML has no `<script` whose `src` contains `jquery`. Otherwise it returns `html` unchanged. `ZimResolver.response` (or `ZimWebViewClient`) applies it to `text/html` responses. The pseudo-pack id `_granger` is answered from `assets/reader/` by `ZimWebViewClient` before ZIM lookup, and only for files that exist there.

- [ ] **Step 1: Failing tests:**
  - `ReaderViewModelTest.opensTheTabsPath`.
  - `pageShownUpdatesTheTab`.
  - `missingTabShowsTabGone`.
  - `chromeStartsHiddenAndToggles`.
  - `MainPageFixesTest.insertsJqueryBeforeResScript`.
  - `leavesPagesThatLoadJqueryAlone`.
  - `leavesPagesWithoutResScriptAlone`.
- [ ] **Step 2:** Run → FAIL.
- [ ] **Step 3: Implement.** Download `jquery-3.7.1.min.js` from `https://code.jquery.com/jquery-3.7.1.min.js` and check it against its published SRI hash, `sha256-/JqT3SQfawRcv/BIHPThkBvs0OEvtFFmqPF/lYI/Cxo=`. Keep its license comment.
- [ ] **Step 4:** `./gradlew :app:testDebugUnitTest` → PASS.
- [ ] **Step 5: Device test** in `ReaderWebViewTest`: `tapInTheMiddleOfTextTogglesChrome` and `tapOnALinkDoesNotToggleChrome`, both against the committed test ZIM. Run `./gradlew :app:connectedDebugAndroidTest` → PASS.
- [ ] **Step 6: Device check** on the Pixel 6, with the Ray Charles sample and WikiMed:
  - The Ray Charles main page lays out its article list.
  - A middle tap shows the top bar and rail; a second tap hides them.
  - Selecting a word, then Search, opens Search with that word.
  - Find shows match counts.
  - Pages render dark.
- [ ] **Step 7: Commit** `Reader: immersive with middle-tap chrome, tabs, selection to Search, find in page, dark pages, main-page jQuery`.

---

### Task 10: Search into tabs, recent searches

**Files:**
- Modify: `search/SearchViewModel.kt`, `search/SearchScreen.kt`, test `search/SearchViewModelTest.kt`

**Interfaces:**
- Consumes: `ReadingSessions.openTab/recordSearch/recentSearches` (Task 4), `SearchRoute(query)` (Task 6).
- Produces:
  - `SearchViewModel(search, sessions, initialQuery: String)`. Behaviour:
    - Starts with `initialQuery` already searched.
    - `fun open(hit: SearchHit): Long` records the current query as a recent search, then opens a new tab.
    - When the field is empty, the state exposes `recent: List<String>`.
  - Results are grouped by pack under the existing "Titles" and "In articles" sections, with the pack (collection) title shown per hit, as today.

- [ ] **Step 1: Failing tests:**
  - `initialQueryIsSearchedImmediately`.
  - `openingAHitRecordsTheSearchAndOpensATab`.
  - `emptyFieldShowsRecentSearches`.
  - Keep the existing tests.
- [ ] **Step 2:** Run → FAIL.
- [ ] **Step 3: Implement.**
- [ ] **Step 4:** Run → PASS.
- [ ] **Step 5: Commit** `Search: start from selected text, open results as bookmarks, remember recent searches`.

---

### Task 11: Whole-app check and roadmap

**Files:**
- Modify: `docs/superpowers/plans/2026-10-15-granger-v1-roadmap.md` (Plan 5 → **Done**, carry-forwards)

- [ ] **Step 1:** `./gradlew testDebugUnitTest :app:assembleDebug` → BUILD SUCCESSFUL, all tests pass.
- [ ] **Step 2: Pixel 6 walkthrough.** Install over the Plan 4 build without uninstalling, so the v2 → v3 migration runs on real data. Then check:
  1. The first open after the upgrade lands on the Library. Classics, WikiMed and Ray Charles are filed under Literature (Granger Classics, Wikipedia ×2 packs), and the earlier starred articles appear under History → Saved.
  2. Open three different Wikipedia articles from Search → three rail spots. Follow a link in one → still three spots.
  3. Swipe the app away and reopen → back in the last article at its scroll position, immersive.
  4. `adb reboot`, reopen → Library.
  5. `adb shell am crash app.granger` while reading, reopen → Library.
  6. Move Wikipedia to a new media type "Reference" → Library shows Literature and Reference.
  7. Granger source lists Classics and the Starter preset; Kiwix lists the curated Kiwix packs; Settings → Models lists models (debug build); download and remove a small pack from Kiwix.
  8. The launcher icon and the About screen show the flame mark and tagline.
- [ ] **Step 3: Update the roadmap.** Mark Plan 5 **Done** with this plan's filename. Under "Carried forward from Plan 5", add anything found in Step 2, plus:
  - Rail letter tiles become covers in Plan 6.
  - The Inquire & Abstract slot is switched on in Plan 8.
  - The Classics ZIM stays filed as "Granger Classics" until Plan 6 replaces it with e-books.
- [ ] **Step 4: Commit** `Plan 5 done: shell and library verified on the Pixel 6; roadmap updated`.
