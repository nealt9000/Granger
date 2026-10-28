# Plan U3b: Find and hold — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** a friend can find what they hold and see what's coming. The Library shows only what's on the phone, by kind. Where to get more is one place (**Get more**). What's downloading is one place (**Downloads**). The rail holds the few things you're in now and opens them on a tap. Search answers with clean, grouped results. Settings reads in sections. Spec: `docs/superpowers/specs/2026-10-28-ux-audit-design.md` §4 (the map), §5 (U3b), D1, D2, D6, and Neal's walkthrough notes W1, W3–W5, W7, W9 and W14 (§8).

**Out of this plan:**
- The one reader toolbar, chrome that slides away (D3) and themed media art (W8): U3c.
- Listen, the playing indicator, voice entries in the rail (W10, W13) and the system-voice fold: U2.
- The comic format bug (W6): its own fix.
- The Classics covers (W11) and vectors for more packs: catalog jobs.

**Two halves.** Part 1 (Tasks 1–6) is the navigation map. It ends with a phone check and can merge alone. Part 2 (Tasks 7–10) is finding things: Search, titles, Continue and Classics. It ends with the walkthrough.

**Tech Stack:** unchanged (Kotlin 2.4.21, Compose Material 3, Room, WorkManager, Robolectric 4.17, JUnit 4 + Truth).
- Build env: `JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home`, `ANDROID_HOME=/opt/homebrew/share/android-commandlinetools`.
- The worktree `.claude/worktrees/u3b` needs `local.properties` and `git submodule update --init --recursive`.
- adb sees the Pixel twice: use `adb -s 21121FDF6007JN` or `ANDROID_SERIAL`.
- `./gradlew installDebug -Pfresh` installs Granger Fresh beside the library install.

**Roadmap:** row U3 in `docs/superpowers/plans/2026-10-15-granger-v1-roadmap.md`. U3a is done (main `58cdc78`). U2 and U3c follow.

## What the code looks like now (surveyed 2026-10-28, main `58cdc78`)

Paths: `A/` = `app/src/main/kotlin/app/granger/`, `CL/` = `core/library/src/main/kotlin/app/granger/core/library/`, `T/` = `app/src/test/kotlin/app/granger/`.

- **Rail** (`A/shell/`):
  - `RailViewModel` builds one list from `sessions.railTabs`, `recentSearchRows`, `chats.recent` and plays (`SCAN = 50`). Pins come first (`MAX_RAIL_PINS = 6`, `CL/RailPins.kt`). Then the rest, sorted by `at` descending, `take(UNPINNED = 12)`. `at` is the last open time, so the rail reorders on every open.
  - Only the reading tab knows it is current (`RailTabUi.current`, from `CurrentTab`, `GrangerNavHost.kt:367`). There's no current chat and no now-playing mark.
  - `NarrowRail` (`Rail.kt:142`): a tap on any tile, on History or on the background calls `onExpand`. Only the tool icons navigate. This is locked by `RailTest.tappingATileOrHistoryInTheNarrowRailOpensTheFullRailInstead`.
  - Tool icons (`Rail.kt:96`): HOME `Icons.Filled.Home`; LIBRARY `R.drawable.ic_library` (Material library_books), the same drawable as every Tab entry.
  - `RailHidden` hides Chat and Play entries removed from the rail.
- **History** (`A/shell/HistoryScreen.kt`, `HistoryViewModel`): History and Saved tabs. No clear action, and no delete-all in the DAOs (`CL/Daos.kt` has `deleteHistoryForPack`, `deleteOrphanHistory`, `deleteRecentSearch(query)`). `ResearchChats.delete(chatId)` only.
- **Library** (`A/library/LibraryScreen.kt`):
  - "Media" section: one collapsible row per `Shelf` (media type).
  - Literature holds every ZIM but maps (in the app-made folder `c:kiwix` "Kiwix") and every e-book collection (Granger Classics, my-books, archive-org).
  - Then "Archive sources" from `SOURCE_GROUPS` (`LibraryViewModel.kt:30`). Archive.org is listed three times.
  - Header: Search, History, a CheckCircle "Readiness", and an overflow ("Import books…").
- **Sources** (`A/sources/SourceScreen.kt`, `SourceViewModel.kt`, `CatalogScope { GRANGER, KIWIX, MODELS }`):
  - Row actions (`CatalogItemRow`, l.288): Installed → Remove only, with no Open, though `ItemRow.packId` is there. A pack opens via `CollectionViewModel.open` (`library.startPath` → `sessions.openTab` → `showTab`).
  - Granger books are unfoldable category `Section`s: all ~347 in one LazyColumn.
  - Preset text: "All N items are here or on their way."
  - `railToolOf` files `SourceRoute` under SETTINGS.
- **Downloads:**
  - The notification (`DownloadWorker.kt:148`) is one per item; its PendingIntent is the plain launch intent.
  - Home's setup bar (`HomeScreen.kt:109`) goes to `SourceRoute(KIWIX)`, while its text says "tap to retry".
  - The `Downloads` API has `records`, `cancel`, `retry` and the group calls. VECTORS records are folded into their parent only in Readiness (`ReadinessViewModel.vectorsOf`).
  - No Downloads screen.
- **Readiness doors:** the Home strip, the Library header, Settings, and Research's failure notice.
- **Search** (`A/search/`):
  - Groups: TITLES, ARTISTS, ALBUMS, SONGS, AUDIOBOOKS, FULL_TEXT ("In article text"), BOOKS ("In books").
  - Article snippets are libkiwix's (HTML with `<b>`, `KiwixZimArchive.kt:130`). Book snippets are ours (`Snippets`, `CL/BookIndex.kt`), up to 3 per book, one row each.
  - Titles come from round-robin `suggest`, 20 per pack, 50 total (`CL/LibrarySearch.kt:31`).
  - No path filtering. No `keyboardOptions` or `keyboardActions`: the keyboard never drops.
- **Titles:**
  - Reader tabs take the page `<title>` on load (`ReaderViewModel.onPageShown`, l.238).
  - Feed cards strip only " - Wikipedia" (`FeedDrawer.pageTitle`, l.155).
- **Home Continue** (`HomeViewModel.continueCards`, l.202): the rail's first 8 Tab/Play entries, with no content filter. Plays read "Play again".
- **Settings** (`A/settings/SettingsScreen.kt`):
  - Order (`LEADING_KEYS`, l.204): Models, Voice, Readiness, Set up suggestions again, Appearance, Storage, Wi-Fi, Archive. Then Music & audiobooks inline (`mediaSettingsSection`), then About.
  - `SettingsRoute(media = true)` only scrolls to that section.
- **Pack reader:** `ReaderTopBar` (`ReaderScreen.kt:396`) uses `Icons.Filled.Home` labelled "Home" for the pack's main page.
- **Ask's search per pack:**
  - By meaning: an installed `VECTORS` record whose `VectorDownloads.parentOf` is the pack's catalog id (with EmbeddingGemma installed), or `PackEntry.vectors != null` in the catalog.
  - By keyword: `hasFulltextIndex`.
- **Tests:** baseline 2101 JVM tests, 0 failed (main `58cdc78`). Main classes:
  - Rail: `RailTest`, `RailViewModelTest`, `HistoryViewModelTest`.
  - Library: `LibraryViewModelTest`, `CollectionViewModelTest`, `ShelvesTest`.
  - Sources: `SourceViewModelTest`, `SourceRoutesTest`, `LibriVoxRouteTest`.
  - Downloads: `DownloadManagerTest`.
  - Search: `SearchViewModelTest`, `SearchRowsTest`, `ZimLibrarySearchTest`, `BookIndexTest`.
  - Home and titles: `HomeScreenTest` (including `theSetupStripShowsProgressAndOpensTheDownloads`), `FeedDrawerTest`.
  - Reader: `ReaderTopBarTest`.
  - Settings: `SettingsViewModelTest`.

## Decisions

1. **Downloads is one screen** (`DownloadsRoute`, D2, W1).
   - **Sections:**
     - **Now**: running, then queued, in queue order, with progress and Cancel.
     - **Stopped**: failed, with Retry and Cancel.
     - **Done today**: installed since midnight, tap to open.
   - **Folding:**
     - A group shows as one row ("Setting up · 12 of 14 · 3.1 of 9.8 GB", an album) that opens to its files.
     - VECTORS records fold into their parent's row as "+ index for Ask". They are never rows of their own.
   - **Empty state:** "Nothing downloading. Get more →".
   - **Doors:**
     - the Library header's download icon, with a badge (count of Now + Stopped) while it isn't empty;
     - Home's setup bar (the text drops "tap to retry");
     - the download notification, via a `granger://downloads` deep link on its PendingIntent.
2. **Get more is where sources live** (`GetMoreRoute`, D2, W5). Sections:
   - **Knowledge packs**: Kiwix.
   - **Books**: Granger Classics; Archive.org books.
   - **Audiobooks & music**: LibriVox; Archive.org audiobooks; Archive.org music. Kept, since U2 makes audiobooks passive in Home and onboarding but leaves the sources here.
   - **Models & voices**: Settings → Models stays the way in; it's also listed here.

   Archive.org appears once per kind, with its kind in the name. Doors: the Library header (Get more, a "+" icon labelled "Get more"), Readiness's See actions (as today, to Kiwix), Home's suggestion line, Downloads' empty state. `railToolOf(SourceRoute, ArchiveRoute, LibriVoxRoute)` becomes LIBRARY (MODELS stays SETTINGS).
3. **Installed packs open where they're managed** (A6). An installed row gets **Open** as its primary action, with Remove moved to the row's overflow. It shares one `openPack(packId)` with `CollectionViewModel.open`, moved to the NavHost.
4. **The Library shows what you hold, by kind** (B1).
   - The "Media" heading and "Archive sources" go.
   - Rows, each only when non-empty:
     - **Encyclopedias & reference**: the `c:kiwix` folder's packs.
     - **Books**: Granger Classics, my books, archive.org books.
     - **Maps**.
     - **Audiobooks**, **Music**: unchanged.
   - Done in the screen and view model by splitting Literature's rows by `isFolder`/pack versus book collection. No schema change; the media types stay.
   - Header: Search, Downloads (with badge), Get more, Readiness (a gauge icon labelled "Readiness", not a check mark), overflow (Import books…). History moves to the rail's History icon.
5. **Icons say what they open** (W3, W4, B8).
   - The rail's **Home is the owl**: the monochrome mark from `ic_launcher_monochrome`, tinted like the other tools.
   - **Library is an open book**: a new `ic_library_books_open` vector, Material "auto_stories". `ic_library` stays for article tabs.
   - The pack reader's main-page button becomes a **book-with-bookmark icon** (Material "menu_book"), labelled "<pack title>: main page". It is no longer a house.
6. **The rail holds what you're in now** (D1, A4, A5).
   - Below the tools and pins: **up to 4 recent items, in the order they arrived**. A new item enters at the top and the oldest drops off. Opening an item already there doesn't move it.
   - The order is kept in `RailOrder` (SharedPreferences, like `RailHidden`): a list of keys, capped at 4. `UNPINNED` goes to 4.
   - **A tap on a narrow-rail tile opens its item** (reversing P1; `RailTest`'s lock is rewritten to say so). The full rail opens by edge swipe or a tap on the rail's empty space. **History** opens `HistoryRoute`.
   - Pins stay as they are, max 6. The current item keeps its accent border.
   - The live voice row stays (U2 replaces it).
7. **Clear all** (W14).
   - History screen overflow: **Clear history**, with a confirm. It deletes History rows and recent searches, and closes every unpinned reading tab that isn't on screen. So it clears the rail's recents too, except what's open now.
   - Ask's Chats sheet: **Delete all chats**, with a confirm.
   - New DAO calls: `deleteAllHistory()`, `deleteAllRecentSearches()`, `ResearchDao.deleteAllChats()` (passages, turns, then chats, in one transaction), `ReadingSessions.clearHistory(keepTabIds)`, `ResearchChats.deleteAll()`.
8. **Ask's line on each pack** (W7). Catalog and installed pack rows in Get more's sources add one of:
   - **Ask: by meaning and keywords** (the catalog entry has `vectors`, or an installed VECTORS record);
   - **Ask: keywords only** (`hasFulltextIndex`);
   - **Ask can't search this** (neither; maps say nothing).
   Pure function `askReach(entry, installedVectorIds)` in `sources/`.
9. **Settings in sections** (B4).
   - **Library & storage**: storage, Wi-Fi only, Ask before large downloads, Archive.org: show everything, Set up suggestions again.
   - **Reading & listening**: Voice →, Music & audiobooks → (a new `MediaSettingsRoute` sub-screen holding `mediaSettingsSection`; `SettingsRoute(media = true)` callers go there).
   - **Ask**: Models →.
   - **Appearance**: unchanged.
   - **About**: the footer.
   - The Readiness row leaves Settings.
10. **Search by kind** (D6, A3).
    - Headings: **Titles**, **Articles** (was "In article text"), **Books** (was "In books"), **Music**, **Audiobooks**.
    - **Books group by book**: one row per book with its best snippet and "N matches". The tap opens at the first match.
    - **Titles rank** exact match first, then starts-with, then the rest in today's order.
    - **Stack Exchange user and tag pages are left out** of Titles, Articles, Continue and feeds. The patterns are confirmed against the installed SE packs on the phone (`users/`, `tags/`, and their index pages) and kept in one `IndexPages.isIndex(path)`.
    - **A snippet that doesn't contain a query word is dropped**: the row shows title and pack only, not page chrome.
    - The field gets `ImeAction.Search`. Submit and the first scroll drop the keyboard.
11. **Titles without their site** (C). `PageTitles.clean(title, packTitle)`:
    - strips a trailing " - Wikipedia", " | <site>", " - <X> Stack Exchange", " - <pack title>";
    - is applied when a tab stores its title (`ReaderViewModel.onPageShown`), in `FeedDrawer.pageTitle` (replacing its one regex) and on Search's title rows.
    - Existing tabs keep their stored titles until next visited.
12. **Continue is things you read or played** (B11).
    - No tab whose path is its pack's main page or an `IndexPages` page.
    - A play's subtitle names its kind ("Album", "Audiobook", "Read aloud") instead of "Play again".
13. **Granger Classics loads in pieces** (W9).
    - The Granger source folds each book category past 8, like Kiwix's packs, with "Show all N books".
    - `CollectionScreen` keeps its lazy grid. Covers decode off the main thread at cell size, measured before and after on the Pixel.
    - Preset lines count by kind: "Efficient start: 12 packs and 347 books · 17.7 GB". Status: "All on this phone", "N downloading", or "N of M to get".

## Global Constraints

- `./gradlew testDebugUnitTest :core:research:test` stays green after every task. The count only grows.
- No schema migration: the new DAO calls are queries on existing tables. `RailOrder` is SharedPreferences.
- No catalog change.
- Every new icon is a vector drawable in `res/drawable`, Material-sourced (Apache-2.0), with no new dependency.
- Commit after every task with the message given, ending with the Co-Authored-By line.

## Review Focus

- Is any download (queued, running, failed) missing from Downloads, or shown twice (vectors, groups)?
- Can a user get from Home, Library, Readiness and the notification to Downloads and Get more?
- Rail: does opening an item ever reorder the 4? Does a narrow-rail tile open its item?
- Does Clear history leave the screen you're on intact?
- Search: is an SE user page anywhere in results, Continue or feeds? Does any snippet show text without a query word?

---

## Part 1: the map

### Task 1: Downloads

- [ ] Record the baseline: `./gradlew testDebugUnitTest :core:research:test`, with the count in the commit message.
- [ ] `downloads/DownloadsViewModel` builds `DownloadsUi(now, stopped, doneToday)` from `downloads.records`, catalog titles and the library, as a pure `DownloadsModel.of(records, now, today)`:
  - groups fold into one row;
  - VECTORS fold into their parent;
  - queue order is by `createdAt`.
- [ ] `DownloadsScreen`: the three sections, row actions (Cancel, Retry, Open), and the empty state with Get more.
- [ ] `DownloadsRoute` in the NavHost (rail tool LIBRARY).
  - The Library header icon with a badge.
  - Home's setup bar `onOpenSetup` → Downloads; `setupText` drops "tap to retry".
- [ ] Notification deep link: `DownloadNotifications` gets the launch intent with `data = granger://downloads`; `MainActivity` / the NavHost's deep-link handling navigates there.
- [ ] Tests:
  - `DownloadsModelTest`: sections, group fold, vector fold, done-today cut-off.
  - `DownloadsScreenTest`: actions and empty state.
  - `HomeScreenTest`: the setup tap now goes to Downloads.
  - A notification intent test that the data URI is set.
- [ ] **Commit.** `Downloads: one screen for what's coming, from Library, Home and the notification`

### Task 2: Get more, and Open on installed packs

- [ ] `GetMoreScreen` (`GetMoreRoute`): the four sections of Decision 2, each row opening today's `SourceRoute` / `ArchiveRoute` / `LibriVoxRoute`.
- [ ] `railToolOf`: the source routes → LIBRARY (MODELS stays SETTINGS). Update `LibriVoxRouteTest` and `SourceRoutesTest`.
- [ ] `CatalogItemRow`: Installed → **Open** (primary), with Remove in a row overflow. Do the same for UpdateAvailable (Open, Update, overflow Remove). Move `openPack(packId)` to the NavHost, used by `CollectionViewModel` and `SourceScreen`.
- [ ] The Ask line per pack (Decision 8): `askReach` and its row text. `AskReachTest` covers the three outcomes and maps.
- [ ] Tests: `GetMoreScreenTest` (sections, each row's route); `SourceViewModelTest` (Open on installed rows).
- [ ] **Commit.** `Get more: the sources in one place; installed packs open; Ask's reach on each pack`

### Task 3: The Library by kind, and the icons

- [ ] `LibraryViewModel`: split Literature's rows into Encyclopedias & reference (packs, the Kiwix folder) and Books (book collections). Drop the Media heading and Archive sources. Keep collapse state per new row id.
- [ ] Header: Search, Downloads (badge), Get more, Readiness (gauge, "Readiness"), overflow. Remove History from the header.
- [ ] Icons (Decision 5): the owl Home, `ic_library_books_open` for Library, menu_book for the pack's main page (labelled "<pack>: main page").
- [ ] Update `ReaderTopBarTest` (the label), `RailTest.homeIsTheFirstTool` (icon description unchanged, "Home") and `LibraryViewModelTest`.
- [ ] **Commit.** `Library: what you hold, by kind; the owl is Home, an open book is the Library`

### Task 4: The rail holds what you're in now

- [ ] `RailOrder` (SharedPreferences, `rail_order`): `admit(key)` (prepend if new, cap 4), `keys`, `forget(key)`. `RailViewModel` uses it for the unpinned entries instead of `sortedByDescending { at }.take(12)`. Entries that are gone or hidden drop out, and the next newest fills the slot.
- [ ] Narrow rail: a tile tap opens its entry (the full rail's open action); History opens `HistoryRoute`; the background tap and the edge swipe still open the full rail.
- [ ] Rewrite `RailTest.tappingATileOrHistoryInTheNarrowRailOpensTheFullRailInstead` as `tappingATileInTheNarrowRailOpensIt`, and add `historyOpensTheHistoryScreen`.
- [ ] `RailViewModelTest`:
  - opening an item already in the four doesn't reorder;
  - a fifth pushes the oldest out;
  - pins are unaffected;
  - a removed entry is replaced by the next newest.
- [ ] **Commit.** `Rail: the four things you're in, in a steady order, open on a tap`

### Task 5: Clear all

- [ ] DAO and API calls (Decision 7), with tests in `DaosTest`, `ReadingSessionsTest` and `ResearchChatsTest`.
- [ ] History screen overflow: Clear history + confirm. `HistoryViewModel.clear(keepTabIds)` keeps pinned tabs and the one on screen.
- [ ] Ask's Chats sheet: Delete all chats + confirm. If the open chat is among them, Ask shows a fresh chat.
- [ ] Tests: `HistoryViewModelTest.clearKeepsPinnedAndCurrent`; `AskLandingTest` for the sheet's Delete all.
- [ ] **Commit.** `Clear history and Delete all chats`

### Task 6: Settings in sections; part 1 on the phone

- [ ] `SettingsScreen` in the five sections of Decision 9. `MediaSettingsRoute` hosts `mediaSettingsSection`; `SettingsRoute(media = true)` callers navigate there. The Readiness row goes. Update `SettingsViewModelTest` where it reads keys.
- [ ] Install both builds and check on the Pixel:
  - Downloads from all three doors, with a real queued pack and a cancelled one;
  - Get more's sections and Open;
  - the Library's rows and header;
  - the owl and the open book;
  - the rail's order across five opens;
  - Clear history;
  - the Settings sections and Music & audiobooks.
  Note anything off in the commit message.
- [ ] Full suite green. **Commit.** `Settings in sections; Music & audiobooks its own screen`
- [ ] Neal can merge part 1 here if he wants it on main before part 2.

## Part 2: finding things

### Task 7: Search by kind

- [ ] `IndexPages.isIndex(path)`: confirm the SE ZIM paths on the phone first (`users/…`, `tags/…`, the questions and users index pages), then test the patterns in `IndexPagesTest`.
- [ ] `ZimLibrarySearch`: filter `isIndex` from titles and full text. Rank titles: exact, then starts-with, then the rest. `ZimLibrarySearchTest`.
- [ ] Snippets: drop one without a query word (case- and accent-insensitive; the libkiwix `<b>` marks count). Book hits group by book (`SearchViewModel`), with the best snippet and a count. `SearchViewModelTest`, `SearchRowsTest`.
- [ ] Headings: Titles, Articles, Books, Music, Audiobooks. `ImeAction.Search`; drop the keyboard on submit and on the first scroll.
- [ ] **Commit.** `Search by kind: clean titles first, no index pages, books grouped, snippets that match`

### Task 8: Titles without their site

- [ ] `PageTitles.clean(title, packTitle)` and `PageTitlesTest`. Cases:
  - Wikipedia, Based Cooking, Parenting Stack Exchange, iFixit;
  - a title that is only the site name stays;
  - a pack title as suffix.
- [ ] Apply it in `ReaderViewModel.onPageShown` / `onPageVisited`, `FeedDrawer.pageTitle` (replacing its regex) and Search's title rows. `ReaderViewModelTest`, `FeedDrawerTest`.
- [ ] **Commit.** `Titles without their site`

### Task 9: Continue and Classics

- [ ] `HomeViewModel.continueCards`: drop tabs on their pack's main page or an `IndexPages` page. A play's subtitle names its kind. `HomeViewModelTest`, `HomeScreenTest`.
- [ ] Granger source: fold categories past 8 with "Show all N books". Preset lines count by kind with the status words of Decision 13. `SourceViewModelTest`.
- [ ] `CollectionScreen`: profile the Classics grid on the Pixel (frame stats via `adb shell dumpsys gfxinfo app.granger`, before and after). Decode covers off the main thread at cell size if that's the cost.
- [ ] **Commit.** `Continue is what you read; Classics in pieces; presets counted by kind`

### Task 10: Walkthrough (Neal, Pixel 6)

- [ ] Library install, then Granger Fresh through onboarding, then:
  - Downloads (from every door);
  - Get more, Open from Kiwix;
  - the Library by kind;
  - the owl, the open book, the pack's main-page icon;
  - the rail's four across a session;
  - Clear history, Delete all chats;
  - Settings sections;
  - Search "apocalypse" (no user pages, Wikipedia's Apocalypse first, books grouped, no chrome snippets);
  - Continue after reading a few SE pages;
  - Classics scrolling.
- [ ] Record the result in the roadmap's U3 row and the notes in the spec. Merge `u3b-find-and-hold` into main and push if Neal passes it.
- [ ] **Commit.** `Roadmap: U3b done`
