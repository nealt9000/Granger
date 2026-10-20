# Plan P1: Polish — brand, wiki reader bar and Back, one rail list with pins, rail side, full screen — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A short polish pass between Media (done 2026-10-20) and the next project. Five items, Neal's list (2026-10-20):
1. **Brand:** the logomark becomes da Vinci's *Vitruvian Man* rising out of the fire of a burning civilization and book, in the Neural Flame style (`art/brand/neural-flame-concept-03.jpeg`).
2. **Wiki reader:** Back steps back through the articles read in that tab, not out of the app. The top bar loses the page title. It reads: Home, a "Search <pack>" box, the bookmark star, and **⋯** (Random article, Find in page).
3. **One rail list:** the rail's Bookmarks and Recent sections become one list, newest opened first. A long press pins an item to the top of that list.
4. **Rail side:** a setting puts the rail on the left or the right.
5. **Full screen, settled:** the page never reflows when the bars show or hide (see Decision 5).

**Architecture:**
- **`art/brand/`, `tools/brand/`:** a reproducible build of the mark from the public-domain drawing: fetch → trace → simplify → compose. The outputs are checked in: the master SVG, the Android vector drawables (launcher foreground, monochrome, `granger_mark`) and the 512 px Play icon (for Plan 11).
- **`:core:library`:** library schema **v9**: `rail_pins` (any rail item, by kind and key) and a stored back stack per reading tab (`reading_tabs.backStack`). `ReadingSessions` gains pin and back-stack calls.
- **`:app` shell:** `RailViewModel` merges tabs, searches, chats and plays into one `RailEntry` list, pinned first. `GrangerShell` takes a `RailSide`. The rail, the expanded rail's slide, the scrim, the edge swipe and its gesture-exclusion strip all follow the side.
- **`:app` readers:** the web reader's new top bar and tab-scoped Back. Both readers draw their chrome **over** the page instead of beside it (Decision 5).

**Tech Stack:** unchanged: Kotlin 2.4.21, Compose Material 3, Room 2.8.5 (manual migration 8→9), Readium, Robolectric 4.17, JUnit 4 + Truth. Brand tooling: Python 3 with `potrace` (Homebrew) and `picosvg`/`svgo` for cleanup, run on the Mac only; nothing new ships in the APK.

**Roadmap:** `docs/superpowers/plans/2026-10-15-granger-v1-roadmap.md` (the "Readers and Search after the follow-ups" item on full screen, and Plan 5's open items on Back and the rail). Spec context: `2026-10-16-library-redesign-design.md` §(rail, reader polish list).

## What the code looks like now (surveyed 2026-10-20, `main` at `6489d16`)

- **Icon:** `app/src/main/res/mipmap-anydpi/ic_launcher.xml` is an adaptive icon: `@color/granger_background` behind `drawable/ic_launcher_foreground.xml`, a line drawing of a small figure in a flame over an open book (flame `#E8601C`, book `#ECE6DA`, 2.4 stroke), plus `ic_launcher_monochrome.xml`. `drawable/granger_mark.xml` is drawn at 56 dp in Settings → About (`SettingsScreen.kt:245`). The only brand art is `art/brand/neural-flame-concept-03.jpeg`. There is no splash drawable of our own (Android 12+ uses the launcher icon).
- **Web reader** (`app/.../reader/ReaderScreen.kt`): `ReaderTopBar` is a `TopAppBar` with ← (calls `onBack`, which **pops the reader**), the page title, Search (opens `PackSearchPanel` over the page), the star, and ⋯ with Home, Random article and Find in page. System Back goes back in the WebView only while `canGoBack`, which is set in `onPageShown` (`onPageFinished`). The WebView's history lives in `ReaderViewModel.webViewState` (a `Bundle`), so it's lost whenever the reader's destination is cleared: opening a tab from the rail replaces the destination (rail destinations replace each other), and process death clears it too. In practice Back then leaves the reader after a single page.
- **E-book reader** (`app/.../ebook/EbookReaderScreen.kt`): a `Column` with the fading `EbookTopBar`, the page padded by `readerPageInsets(chromeVisible, …)`, and the fading `EbookBottomBar`. Showing or hiding the chrome changes the page's size, so Readium re-paginates.
- **Full screen** (both readers): `chromeVisible` in each view model. `SystemBarsHidden`, `BarsHideRequests` and `readerPageInsets` are in `ReaderScreen.kt`. The shell hides the rail through `onChromeVisibleChange` → `ShellState.railVisible`, which takes the rail out of the `Row` and so changes the page's width.
- **Rail** (`app/.../shell/Rail.kt`, `RailViewModel.kt`, `GrangerShell.kt`): `RailUi(tabs, recent)`. Tabs are `reading_tabs` rows with `onRail = 1`, newest `openedAt` first, at most `RAIL_LIMIT` = 8. `recent` is searches, chats and plays merged by time, at most 5. The narrow rail shows letter or cover tiles, then History, then small recent icons. The expanded rail has the sections "Bookmarks", History and "Recent". A long press on a tab offers "Remove from bookmarks". The rail is hard-wired to the start edge: `RailInsets` (Start), `consumeWindowInsets(Start)`, `slideInHorizontally { -it }`, `edgeSwipe` (x < 24 dp) and the 24×200 dp exclusion strip at `CenterStart`.
- **Settings** (`app/.../settings/SettingsScreen.kt`, `SettingsViewModel.kt`): a `LazyColumn` of keyed sections. Prefs are `SharedPreferences`-backed classes (`DownloadPrefs`, `ArchivePrefs`, the player prefs).
- **Library DB:** version 8, manual migrations through `MIGRATION_7_8`, schemas exported.

## Decisions

Neal's (2026-10-20):
1. **Logo source:** trace da Vinci's original drawing (public domain, about 1490; a Wikimedia Commons scan). Vectorize and simplify it, and set it rising from the flame and book in the Neural Flame style. There are two levels of detail: the **full mark** (About, the Play listing, the 512 px icon) and a **simplified glyph** for the launcher and monochrome icons, which must read at 48 dp.
2. **Wiki top bar:** no page title. In order: ← (in-tab Back), Home, a search box ("Search <pack>"), the star, ⋯ (Random article, Find in page).
3. **Rail list:** one list of everything opened (articles, books, searches, Research chats, plays), newest first. A long press → Pin to top / Unpin, and Remove (removing an article or book takes it off the rail; history keeps it, as today). History stays as its own entry below the list.
4. **Rail side:** a Settings choice, Left (default) or Right.
5. **Full screen: on hold (Neal, 2026-10-20); Task 7 isn't built in this plan's first pass.** The proposed approach is an **overlay**, as in Play Books and Kindle. The page always fills the screen and is laid out once. A middle tap fades the top bar, the rail (and the e-book's bottom bar) and the system bars in **over** the page, and out again; the text never moves. The web reader also hides the chrome when you scroll down and shows it when you scroll up, like Chrome. To keep the first lines readable under the bar, each article page gets a top spacer the bar's height (CSS, inside the page, so it scrolls away). Alternatives Neal can pick instead: scroll-linked bars only, with no tap toggle and no e-book full screen; or no full screen at all. Both earlier attempts are reverted and not repeated (`6479788`, `be0d3bf`).

Builder's calls (for Neal's review):
- The rail list holds the **12** newest unpinned items (it was 8 tabs + 5 recents) plus up to **6** pins, in the order they were pinned (newest pin on top). A 7th pin asks to unpin one first.
- Pins are stored by **kind and key** (`tab:<id>`, `search:<query>`, `chat:<id>`, `play:<key>`), so a pinned item survives being pushed off the list by newer ones. A pin whose item is gone (a closed tab, a deleted chat, a play whose album is gone) is dropped when the list is built, and its row is deleted.
- In the narrow rail every item is a tile: covers and letter tiles for articles and books, icons for the rest. A pin shows as a small flame dot on its tile. Tapping still opens the full rail (the M1 rule).
- The tab's back stack is stored as the paths visited in that tab (newest last, at most **50**), so Back survives rail switches and process death. The WebView's own history is still used while it's alive. The stored stack is the fallback when it's gone.
- Rail on the right: the e-book reader's tap zones stay as they are (left 20% = previous page). The rail is outside the page, and the overlay doesn't take taps while hidden.
- The brand build is a script in `tools/brand/`, so the mark can be re-made. The traced source is kept in `art/brand/source/` with its Commons URL and licence note (public domain).

## Global Constraints

- **Don't change the library's data for users:** v8 → v9 adds a table and a column only. Existing tabs keep their order and become the newest unpinned items. Run the migration test against an exported v8 schema.
- **Words in the UI** (match the app's voice): "Pin to top", "Unpin", "Remove from rail", "Search Wikipedia" (the pack title, ellipsized), "Random article", "Find in page", Settings → Appearance → "Toolbar side: Left / Right".
- Every rail action stays reachable with TalkBack (`onClickLabel`, `onLongClickLabel` on every tile and row).
- Don't regress: the left-edge swipe in readers (now the *rail-side* edge swipe); collapsible Wikipedia headings not toggling the chrome; the reader's selection menus; Readium's edge taps.
- JVM tests: `./gradlew testDebugUnitTest` and `./gradlew :core:research:test` both pass (1533 before this plan). No `connectedDebugAndroidTest` without Neal's go-ahead (it wipes packs and models).
- Device checks go on the Pixel 6, installed over the app (`./gradlew :app:installDebug`), with the library DB backed up first.
- In a new agent worktree: `git submodule update --init --recursive` before building (taglib).

## Review Focus

- The v9 migration and the pin rows' life cycle (orphans, the 6-pin limit).
- Back in the web reader: WebView history first, then the stored stack, then leaving the reader. Nothing is lost on rotation, a rail switch or process death.
- The rail on the right: insets in landscape with the cutout on either side, the gesture-exclusion strip, the expanded rail's slide direction and its scrim.
- Full screen: no layout pass on toggle (the page's size is constant). Test it with a layout counter, not only by eye.
- The launcher icon at 48 dp and in themed (monochrome) mode.

---

### Task 1: The mark — trace, simplify, compose

**Files:**
- Create: `tools/brand/build_mark.py`, `tools/brand/README.md`
- Create: `art/brand/source/vitruvian-commons.{jpg,txt}` (the scan, its URL, "public domain"), `art/brand/granger-mark.svg` (full), `art/brand/granger-glyph.svg` (simplified), `art/brand/granger-play-512.png`
- Modify: `app/src/main/res/drawable/ic_launcher_foreground.xml`, `ic_launcher_monochrome.xml`, `granger_mark.xml`; `app/src/main/res/values/colors.xml` only if the background changes

- [ ] **Step 1: Source.** Download the Commons scan of the *Vitruvian Man* (the Luc Viatour photograph, public domain) into `art/brand/source/`. Record the URL and licence in the `.txt` file.
- [ ] **Step 2: Trace.** `build_mark.py` thresholds the figure (the circle and square line work and the four-armed, four-legged figure; no handwriting), runs `potrace`, and keeps the largest paths. It writes an intermediate SVG.
- [ ] **Step 3: Compose the full mark.** The figure rises from the Neural Flame: flame strokes `#E8601C` rise behind and around the lower body and dissolve into the circuit-node tips. The open book `#ECE6DA` sits at the base, and a broken skyline (columns, a dome) burns along the flame's foot for "a burning civilization". Line weights follow the concept art. Output `granger-mark.svg`.
- [ ] **Step 4: The glyph.** For the launcher and monochrome icons, hand-simplify: the circle, the figure's outline with the outstretched arms and spread legs (one pose, not da Vinci's two), three flame tongues and the book. No skyline, no circuit nodes. Keep inside the adaptive icon's 66 dp safe zone, with a stroke of at least 2.4 at 108 viewport units.
- [ ] **Step 5: Android drawables.** Convert the SVGs to vector drawables (Android Studio's importer, or `vd-tool`): the foreground and monochrome from the glyph, and `granger_mark` from the full mark (simplify paths so it stays under 30 KB of XML).
- [ ] **Step 6: Check.** Render the foreground at 48, 72 and 192 px, and the monochrome in themed mode, into the scratchpad. Show Neal the full mark, the glyph and the launcher renders side by side **before** committing. Revise until approved.
- [ ] **Step 7: Commit.** `Brand: the Vitruvian Man rising from the Neural Flame; launcher, monochrome and About mark`

### Task 2: Library v9 — rail pins and tab back stacks

**Files:**
- Modify: `core/library/.../Entities.kt` (`RailPin`; `ReadingTab.backStack`), `Daos.kt`, `LibraryDatabase.kt` (version 9, `MIGRATION_8_9`), `ReadingSessions.kt`
- Create: `core/library/.../RailPins.kt`
- Test: `core/library/src/test/.../RailPinsTest.kt`, `ReadingSessionsTest.kt` (additions), `MigrationTest.kt` (8→9)

**Interfaces:**
```kotlin
@Entity(tableName = "rail_pins")
data class RailPin(@PrimaryKey val key: String, val pinnedAt: Long)   // "tab:12", "search:holst", "chat:3", "play:album:…"

interface RailPins {
    val pins: Flow<List<RailPin>>                // newest pin first
    suspend fun pin(key: String): PinResult      // Pinned | AlreadyPinned | Full (6)
    suspend fun unpin(key: String)
    suspend fun dropMissing(keys: Collection<String>)
}

// ReadingTab gains: val backStack: String = ""   // "\n"-joined paths, oldest first, ≤ 50
// ReadingSessions gains:
suspend fun pushPath(tabId: Long, path: String, title: String)   // showInTab + push
suspend fun popPath(tabId: Long): String?                        // the previous path, or null at the first page
```

- [ ] **Step 1: Failing tests:** `pinThenListNewestFirst`, `seventhPinIsRefused`, `unpinRemoves`, `dropMissingDeletesOnlyThoseKeys`, `pushPathAppendsAndCapsAtFifty`, `popPathReturnsPreviousAndShortens`, `popAtFirstPageIsNull`, `openTabStartsANewStack`, `migration8To9KeepsTabsAndAddsEmptyStacks`.
- [ ] **Step 2:** Run `./gradlew :core:library:testDebugUnitTest`. Expected: compile failure.
- [ ] **Step 3: Implement.** `MIGRATION_8_9`: `CREATE TABLE rail_pins (key TEXT PRIMARY KEY NOT NULL, pinnedAt INTEGER NOT NULL)` and `ALTER TABLE reading_tabs ADD COLUMN backStack TEXT NOT NULL DEFAULT ''`. Export the v9 schema.
- [ ] **Step 4:** The same command, then all of `testDebugUnitTest`. Expected: PASS.
- [ ] **Step 5: Commit.** `Library v9: rail pins and a back stack per reading tab`

### Task 3: Wiki reader — Back within the tab

**Files:**
- Modify: `app/.../reader/ReaderViewModel.kt`, `ReaderScreen.kt`, `ZimWebViewClient.kt` (report same-document navigations too: `doUpdateVisitedHistory`)
- Test: `app/src/test/.../reader/ReaderViewModelTest.kt`, `ReaderBackTest.kt` (Robolectric)

- [ ] **Step 1: Reproduce on the Pixel 6 first.** Open an article from the Library, follow two links, then press system Back and the ← arrow. Do the same after opening the tab from the rail, and after `run-as … kill -9`. Write down which cases leave the app. This confirms the cause in the survey: the WebView state is lost with the destination, and ← always pops.
- [ ] **Step 2: Failing tests:** `followingALinkPushesThePath`, `backWithWebHistoryGoesBackInTheWebView`, `backWithoutWebHistoryLoadsTheStoredPreviousPath`, `backAtTheFirstPageLeavesTheReader`, `arrowAndSystemBackDoTheSame`, `homeAndRandomPushLikeLinks`, `restoredTabKeepsItsStack`.
- [ ] **Step 3: Implement.** One `goBack()` in the screen, for both ← and `BackHandler`: if `webView.canGoBack()`, use the WebView's history. Otherwise, if `viewModel.previousPath()` returns a path, load it **without** pushing. Otherwise call `onBack()`. Every shown page calls `sessions.pushPath` (dedupe a reload of the same path). The `BackHandler` is always enabled in the reader; the find bar and pack search keep priority as today.
- [ ] **Step 4:** Run `./gradlew :app:testDebugUnitTest --tests 'app.granger.reader.*'`, then everything. Expected: PASS.
- [ ] **Step 5: Commit.** `Wiki reader: Back steps back through the tab's articles, then leaves`

### Task 4: Wiki reader — the new top bar

**Files:**
- Modify: `app/.../reader/ReaderScreen.kt` (`ReaderTopBar`, `PackSearchPanel`)
- Test: `app/src/test/.../reader/ReaderTopBarTest.kt` (Compose, Robolectric)

- [ ] **Step 1: Failing tests:** `noPageTitleIsShown`, `barOrderIsBackHomeSearchStarMore`, `searchBoxSaysSearchAndThePackTitle`, `tappingTheSearchBoxOpensThePackSearchWithFocus`, `moreHoldsRandomAndFindOnly`, `homeHiddenWhenThePackHasNone`.
- [ ] **Step 2: Implement.** A custom `Row` bar (not a `TopAppBar` title slot), under the same insets: ←, Home (`Icons.Filled.Home`, shown when `hasHome`), a rounded search field (`SurfaceHigh`, "Search <pack>", read-only; a tap opens `PackSearchPanel` with the query focused), the star, then ⋯ with **Random article** and **Find in page**. The page title is still used for History, the rail and TalkBack (the bar's `contentDescription`).
- [ ] **Step 3:** Run the reader tests, then everything. Expected: PASS.
- [ ] **Step 4: Commit.** `Wiki reader: Home, the pack search box and the star in the bar; Random and Find under ⋯`

### Task 5: One rail list, with pins

**Files:**
- Modify: `app/.../shell/RailViewModel.kt` (`RailEntry`, merge, pins), `Rail.kt` (both rails), `GrangerShell.kt`, `GrangerNavHost.kt` (callbacks)
- Test: `app/src/test/.../shell/RailViewModelTest.kt`, `RailTest.kt` (Compose)

**Interfaces:**
```kotlin
sealed interface RailEntry {
    val key: String; val at: Long; val pinned: Boolean
    data class Tab(val tab: RailTabUi, …) : RailEntry
    data class Search(…) : RailEntry; data class Chat(…) : RailEntry; data class Play(…) : RailEntry
}
data class RailUi(val entries: List<RailEntry>)   // pins (newest pin first), then the 12 newest others
// RailViewModel gains: pin(key), unpin(key), remove(entry)
```

- [ ] **Step 1: Failing tests:** `entriesMergeAllKindsNewestFirst`, `pinnedComeFirstInPinOrder`, `pinnedItemSurvivesNewerItems`, `unpinnedListIsCappedAtTwelve`, `goneItemsDropTheirPins`, `seventhPinShowsTheLimitNotice`, `removeTabClosesItButHistoryKeepsIt`, `removeSearchDeletesTheRecentSearch` (new `ReadingSessions.forgetSearch`), `longPressOffersPinOrUnpinAndRemove`.
- [ ] **Step 2: Implement.** Merge in `RailViewModel`: tabs use `openedAt`, searches `searchedAt`, chats `updatedAt`, plays `playedAt`. In the expanded rail: one unlabelled list (no "Bookmarks"/"Recent" headers), pins under a thin "Pinned" label when there are any, then History. In the narrow rail: one column of tiles with the flame dot on pins. A long press menu on every row and tile offers Pin to top / Unpin, then Remove from rail.
- [ ] **Step 3:** Run the shell tests, then everything. Expected: PASS.
- [ ] **Step 4: Commit.** `Rail: one list of everything opened, newest first; long press to pin to the top`

### Task 6: The rail on the left or the right

**Files:**
- Create: `app/.../settings/AppearancePrefs.kt` (`RailSide { LEFT, RIGHT }`, SharedPreferences, a `StateFlow`)
- Modify: `app/.../settings/SettingsScreen.kt`, `SettingsViewModel.kt` (an "Appearance" section, before Storage), `shell/GrangerShell.kt`, `shell/Rail.kt`, `MainActivity.kt`/`GrangerNavHost.kt` (wiring)
- Test: `AppearancePrefsTest.kt`, `GrangerShellSideTest.kt` (Compose), `SettingsViewModelTest.kt` (additions)

- [ ] **Step 1: Failing tests:** `defaultIsLeft`, `choiceIsSavedAndRead`, `rightPutsTheRailAfterTheContent`, `rightRailPadsTheEndInset`, `expandedRailSlidesFromTheRight`, `edgeSwipeFromTheRightEdgeExpands`, `exclusionStripIsOnTheRailSide`.
- [ ] **Step 2: Implement.** `GrangerShell(side: RailSide, …)`: a `Row` ordered by side; `RailInsets` and the consumed inset become End for the right; the slide is `±it`; `edgeSwipe` measures from the rail's edge (`x > width − 24 dp`, dragging left); the exclusion strip aligns `CenterEnd`. Don't flip the layout direction (text and icons stay LTR). Settings: "Toolbar side" with two radio rows, applied at once.
- [ ] **Step 3:** Run the tests, then everything. Expected: PASS.
- [ ] **Step 4: Commit.** `Settings: the toolbar on the left or the right`

### Task 7: Full screen, settled — ON HOLD

**On hold (Neal, 2026-10-20): he's thinking it over. Don't start this task.** P1 can finish without it. The steps below are the overlay proposal from Decision 5, kept as a draft. Rewrite them to match whatever Neal decides.

**Files:**
- Modify: `app/.../reader/ReaderScreen.kt` (layout: page full size in a `Box`, chrome above it), `ReaderPageScript.kt` (top spacer; scroll direction to Kotlin), `ReaderWebView.kt`, `ReaderViewModel.kt` (`onScroll(dy)`)
- Modify: `app/.../ebook/EbookReaderScreen.kt` (the same `Box` layering; the top and bottom bars over the page)
- Modify: `app/.../shell/GrangerShell.kt` (in a reader, the narrow rail is drawn **over** the content, not in the `Row`, so hiding it doesn't change the page's width; outside readers nothing changes)
- Test: `ReaderFullScreenTest.kt`, `EbookFullScreenTest.kt` (Compose, Robolectric: the page's measured size is the same with the chrome shown and hidden, counted with an `onSizeChanged` counter), `ReaderViewModelTest.kt` (`scrollDownHidesScrollUpShows`, `smallScrollsAreIgnored`, `nearTheTopAlwaysShows`)

- [ ] **Step 1: Failing tests** as listed, plus `middleTapStillToggles`, `rotationKeepsFullScreen`, `rightSideRailOverlaysToo`.
- [ ] **Step 2: Implement.** The page fills the screen less the cutout only, and is laid out once. The chrome (top bar, rail, the e-book's bottom bar, system bars) fades in and out over it. The web page script adds `body::before` spacer at the bar's height (passed in as px), and reports scroll deltas. Past a threshold of 24 px, down hides the chrome and up shows it; within the first screen it always shows. The e-book keeps tap-only toggling. Its bars cover the first and last lines while shown, as in Play Books, and Readium is **not** re-paginated.
- [ ] **Step 3:** Run the reader, e-book and shell tests, then everything. Expected: PASS.
- [ ] **Step 4: Commit.** `Readers: the chrome shows over the page; the text never moves`

### Task 8: Pixel 6 walkthrough; roadmap

- [ ] Install over the app (DB backed up first; v8 → v9 on first open).
- [ ] Check:
  - [ ] **Icon:** the launcher at the home screen's size, in themed icons mode, in Settings → About, and on the app's splash.
  - [ ] **Wiki Back:**
    - [ ] Three links deep, both ← and system Back step back one article at a time, then leave the reader.
    - [ ] The same after switching to another tab on the rail and back.
    - [ ] The same after killing the process.
  - [ ] **Wiki top bar:** the new bar's order. Home, the search box, the star, Random and Find in page all work.
  - [ ] **Rail list:**
    - [ ] Items of every kind appear in one list, in the order opened.
    - [ ] A pin survives opening 12 new things.
    - [ ] Unpin works, and Remove works.
    - [ ] A pinned chat that's deleted drops off the list.
  - [ ] **Rail on the right:** portrait and landscape (cutout on each side), the edge swipe, the expanded rail and its scrim, and in both readers.
  - [ ] **Full screen:**
    - [ ] A middle tap in a Wikipedia article and in an e-book doesn't move the text.
    - [ ] Scrolling hides and shows the bars in an article.
    - [ ] Collapsible headings still don't toggle the chrome.
    - [ ] Readium's edge taps still turn pages.
- [ ] Update the roadmap: a P1 row and a "Carried forward from P1" section (what passed, what's open). Close the full-screen and rail items it settles.
- [ ] **Commit.** `Roadmap: P1 polish done`

## Order and size

Tasks 2–6 are independent of Task 1 and can run in parallel with it. Task 1 waits on Neal's approval of the renders. Task 7 is on hold, and Task 8's full-screen checks are skipped until it's built. Rough size: Task 1 is the most open-ended (iterating on art); Tasks 2–6 are about a day of agent work together; Task 7 is half a day plus device tuning.
