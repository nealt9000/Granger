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
     *(Changed: at 360 dp the five actions squeezed the title to "Li…", so Readiness moved into the overflow: Readiness, then Import books… when import is on. The overflow always shows now.)*
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
   - *(Changed after Neal's walkthrough: a book showed twice, as its tab and as the voice's Reading row or a "Play again" of its reading. Now one entry per book: when the voice reads a source that is a tab on the list (matched by the voice's source key against the tab's `book:<id>` or `pack:<id>/<path>`), the tab tile wears a speaker badge and TalkBack adds "reading aloud"; the Reading row shows only when no tab on the list matches. A read-aloud play (`voice:<source key>`) whose source is a tab on the rail is left out, and the live source's own play waits until the voice is done. Removing an item no longer backfills from before a Clear history: see Decision 7.)*
7. **Clear all** (W14).
   - History screen overflow: **Clear history**, with a confirm. It deletes History rows and recent searches, and closes every unpinned reading tab that isn't on screen. So it clears the rail's recents too, except what's open now.
   - Ask's Chats sheet: **Delete all chats**, with a confirm.
   - New DAO calls: `deleteAllHistory()`, `deleteAllRecentSearches()`, `ResearchDao.deleteAllChats()` (passages, turns, then chats, in one transaction), `ReadingSessions.clearHistory(keepTabIds)`, `ResearchChats.deleteAll()`.
   - *(Changed after Neal's walkthrough: Clear history left Ask chats and plays on the rail, kept every reader tab in the back stack, and a later remove backfilled the rail from what had been cleared. Now Clear records a cut-off (`RailHidden.clearedAt`, in the `rail_hidden` prefs) and the rail and Home's Continue drop every unpinned item from before it, so a free slot fills only with something newer. It also forgets every play (`PlaybackDao.deleteRecentPlays()`), and keeps only the nearest reader tab under History (`HistoryViewModel.keptTab`) plus the pins. Reading places, bookmarks, Saved and the chats stay; chats leave the rail by the cut-off. The dialog: "This forgets what you've opened, searched and played, and empties the rail. Pinned items and the page you came from stay. Ask's chats stay in Ask.")*
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

- [x] Record the baseline: `./gradlew testDebugUnitTest :core:research:test`, with the count in the commit message.
- [x] `downloads/DownloadsViewModel` builds `DownloadsUi(now, stopped, doneToday)` from `downloads.records`, catalog titles and the library, as a pure `DownloadsModel.of(records, now, today)`:
  - groups fold into one row;
  - VECTORS fold into their parent;
  - queue order is by `createdAt`.
  *(Built: `DownloadsModel.of(records, since, packs, books)`: `since` is midnight, and the library's packs and books give titles and what Open opens. A group with any failed file is under Stopped, even with files still queued. Open is offered for packs and books only; a finished album is a row to look at.)*
- [x] `DownloadsScreen`: the three sections, row actions (Cancel, Retry, Open), and the empty state with Get more.
  *(Built: until Task 2 adds `GetMoreRoute`, "Get more →" opens `SourceRoute(KIWIX)`.)*
- [x] `DownloadsRoute` in the NavHost (rail tool LIBRARY).
  - The Library header icon with a badge.
  - Home's setup bar `onOpenSetup` → Downloads; `setupText` drops "tap to retry".
  *(Built: the parameter is now `onOpenDownloads`. The Downloads icon sits between History and Readiness until Task 3's header.)*
- [x] Notification deep link: `DownloadNotifications` gets the launch intent with `data = granger://downloads`; `MainActivity` / the NavHost's deep-link handling navigates there.
  *(Built: `downloadsIntent(launch)` in `DownloadWorker.kt` sets `DOWNLOADS_LINK` and SINGLE_TOP | CLEAR_TOP | NEW_TASK, so a running app gets it in `onNewIntent`. `MainActivity.opensDownloads` counts requests the way the Player widget's do. The intent is explicit, so there's no manifest change.)*
- [x] Tests:
  - `DownloadsModelTest`: sections, group fold, vector fold, done-today cut-off.
  - `DownloadsScreenTest`: actions and empty state.
  - `HomeScreenTest`: the setup tap now goes to Downloads.
  - A notification intent test that the data URI is set.
  *(Built: `DownloadNotificationsTest` in `:core:downloads`; `DownloadsViewModelTest` too.)*
- [x] **Commit.** `Downloads: one screen for what's coming, from Library, Home and the notification`

### Task 2: Get more, and Open on installed packs

- [x] `GetMoreScreen` (`GetMoreRoute`): the four sections of Decision 2, each row opening today's `SourceRoute` / `ArchiveRoute` / `LibriVoxRoute`.
  *(Built: `GetMoreLink` and `GET_MORE_SECTIONS` in `sources/GetMoreScreen.kt`, routed by `routeOf(GetMoreLink)` in the NavHost. Doors so far: a "+" "Get more" icon in the Library header (Task 3 settles the header) and Downloads' empty state. Readiness's See still opens Kiwix, as planned. Home has no suggestion line yet, so it has no door.)*
- [x] `railToolOf`: the source routes → LIBRARY (MODELS stays SETTINGS). Update `LibriVoxRouteTest` and `SourceRoutesTest`.
  *(Built: `railToolOf(destination, sourceScope)`: the NavHost passes a `SourceRoute`'s scope, so Models stays SETTINGS. LibriVox was already LIBRARY and `LibriVoxRouteTest` passes unchanged. The route cases are in `GetMoreScreenTest` rather than `SourceRoutesTest`, since they need a NavDestination.)*
- [x] `CatalogItemRow`: Installed → **Open** (primary), with Remove in a row overflow. Do the same for UpdateAvailable (Open, Update, overflow Remove). Move `openPack(packId)` to the NavHost, used by `CollectionViewModel` and `SourceScreen`.
  *(Built: `library/OpenPack.kt` holds `openPack(library, sessions, packId)`, and the NavHost's `openPack` uses it to show the tab. CollectionScreen, SourceScreen and Downloads all open packs through it. `CollectionViewModel.open` is gone, and its two tests moved to `OpenPackTest`. Open shows only on rows with a library pack. Installed Granger books, models and voices keep Remove.)*
- [x] The Ask line per pack (Decision 8): `askReach` and its row text. `AskReachTest` covers the three outcomes and maps.
  *(Built: `askReach(entry, installedVectorParents)`. A pack row in any scope shows it as its own line under the details. Maps are `family == "maps"`, as in Readiness.)*
- [x] Tests: `GetMoreScreenTest` (sections, each row's route); `SourceViewModelTest` (Open on installed rows).
  *(Built: `GetMoreScreenTest` also covers `CatalogItemRow`: Open, the overflow's Remove, Update.)*
- [x] **Commit.** `Get more: the sources in one place; installed packs open; Ask's reach on each pack`

### Task 3: The Library by kind, and the icons

- [x] `LibraryViewModel`: split Literature's rows into Encyclopedias & reference (packs, the Kiwix folder) and Books (book collections). Drop the Media heading and Archive sources. Keep collapse state per new row id.
  *(Built: `libraryKinds(shelves)` makes `KindRow`s. Encyclopedias & reference holds the Kiwix folder and any Literature collection with a pack in it, so Granger's own packs land there too. Books holds the rest of Literature. The order is Maps, Audiobooks, Music, then any other media type in its own order. `SOURCE_GROUPS` and `SourceGroup` are gone. `SourceLink` stays, because the empty Music and Audiobooks screens still link with it. An empty Library's line is now a "Get packs and books →" button to Get more.)*
- [x] Header: Search, Downloads (badge), Get more, Readiness (gauge, "Readiness"), overflow. Remove History from the header.
  *(Built: Readiness uses the existing `ic_speed` (Material "speed"). The screen is split into `LibraryScreen` and a stateless `LibraryContent` so it can be tested and shot. The title keeps to one line: at 360 dp the five actions leave it short and it broke "Libr/ary"; at 411 dp (Pixel 6) it fits whole.)*
- [x] Icons (Decision 5): the owl Home, `ic_library_books_open` for Library, menu_book for the pack's main page (labelled "<pack>: main page").
  *(Built: `ic_owl.xml` is generated by `tools/brand/make_drawables.py`: the themed icon's one-colour mark, its rim 22 dp across in a 24 dp canvas. A rerun leaves every other drawable unchanged. In ThemeShots (`library`, `rail`, both themes) the owl and the G read clearly at rail size. `ic_menu_book.xml` is Material "menu_book"; `mainPageLabel(packTitle)` reads "<pack>: main page", or "Main page" with no title.)*
- [x] Update `ReaderTopBarTest` (the label), `RailTest.homeIsTheFirstTool` (icon description unchanged, "Home") and `LibraryViewModelTest`.
  *(Built: new `LibraryScreenTest` covers the header order (no History), the badge, a kind folding, and the empty state's Get more. ThemeShots gains `library`.)*
- [x] **Commit.** `Library: what you hold, by kind; the owl is Home, an open book is the Library`

### Task 4: The rail holds what you're in now

- [x] `RailOrder` (SharedPreferences, `rail_order`): `admit(key)` (prepend if new, cap 4), `keys`, `forget(key)`. `RailViewModel` uses it for the unpinned entries instead of `sortedByDescending { at }.take(12)`. Entries that are gone or hidden drop out, and the next newest fills the slot.
  *(Built: `RailOrder` stores a `RailFour(keys, seenAt)` rather than offering admit/forget. `seenAt` is the newest `at` the rail has seen. `RailFour.next(candidates)` is pure: items no longer candidates (gone, hidden, pinned) leave; items newer than `seenAt` and not in the four enter at the top, oldest first, and the bottom drops past 4; a free slot is filled at the bottom by the newest candidate not in the four, so nothing shown moves. An item pushed out comes back at the top when it's opened again. The first run seeds the four with the newest four by `at`, so an existing rail keeps what it showed. `RailViewModel` saves the four only when it changes. The live voice row still heads the list. `RailUi.recent` (pins, then every unpinned item newest first, not cut to four) feeds Home's Continue, which keeps `CONTINUE = 8`. The full rail lists the same entries as the narrow one.)*
- [x] Narrow rail: a tile tap opens its entry (the full rail's open action); History opens `HistoryRoute`; the background tap and the edge swipe still open the full rail.
  *(Built: both rails share one `openAction(entry, …)`, so an unavailable tab says so from either. The tile's click label is now "Open".)*
- [x] Rewrite `RailTest.tappingATileOrHistoryInTheNarrowRailOpensTheFullRailInstead` as `tappingATileInTheNarrowRailOpensIt`, and add `historyOpensTheHistoryScreen`.
  *(Built: also `tappingTheNarrowRailsBackgroundOpensTheFullRail`. The edge swipe is already covered in `GrangerShellSideTest`.)*
- [x] `RailViewModelTest`:
  - opening an item already in the four doesn't reorder;
  - a fifth pushes the oldest out;
  - pins are unaffected;
  - a removed entry is replaced by the next newest.
  *(Built: plus the first-run seed (and `recent` uncut), a hidden chat leaving and coming back on top, and a saved order kept across launches. `unpinnedListIsCappedAtTwelve` and `entriesMergeAllKindsNewestFirst` became the seed test. `RailOrderTest` covers `SharedRailOrder` across launches and `RailFour.next` with several arriving at once.)*
- [ ] **Commit.** `Rail: the four things you're in, in a steady order, open on a tap`

### Task 5: Clear all

- [x] DAO and API calls (Decision 7), with tests in `DaosTest`, `ReadingSessionsTest` and `ResearchChatsTest`.
  *(Built: `SessionDao` gains `deleteAllHistory()`, `deleteAllRecentSearches(keep)` and `closeTabsExcept(keep)` (tabs leave the rail, as `closeTab` does; they aren't deleted). `ResearchDao` gains `deleteAllPassages`, `deleteAllTurns` and `deleteAllChats`, which `ResearchChats.deleteAll()` runs in one transaction, as `delete` does. `ReadingSessions.clearHistory(keepTabIds, keepSearches)` runs its three in one transaction.)*
- [x] History screen overflow: Clear history + confirm. `HistoryViewModel.clear(keepTabIds)` keeps pinned tabs and the one on screen.
  *(Built: `HistoryViewModel` takes `RailPins` and adds the pinned tabs. Pinned searches stay too, so a pin isn't lost to Clear. On History, `CurrentTab` is null (it follows the top of the stack), so the NavHost passes every reader tab in the back stack: the tabs Back returns to. Chats and plays aren't history and stay on the rail; the rail's four close up around what's left, which `RailViewModelTest.clearingHistoryEmptiesTheFourButWhatsKept` checks.)*
- [x] Ask's Chats sheet: Delete all chats + confirm. If the open chat is among them, Ask shows a fresh chat.
  *(Built: Delete all deletes every chat, the open one too, so Ask always shows a fresh chat after it. `ResearchViewModel.deleteAllChats()` shares `leaveChat()` with `deleteChat()`.)*
- [x] Tests: `HistoryViewModelTest.clearKeepsPinnedAndCurrent`; `AskLandingTest` for the sheet's Delete all.
  *(Built: also `ResearchViewModelTest.deleteAllChatsLeavesAFreshChat`.)*
- [ ] **Commit.** `Clear history and Delete all chats`

### Task 6: Settings in sections; part 1 on the phone

- [x] `SettingsScreen` in the five sections of Decision 9. `MediaSettingsRoute` hosts `mediaSettingsSection`; `SettingsRoute(media = true)` callers navigate there. The Readiness row goes. Update `SettingsViewModelTest` where it reads keys.
  *(Built: one `LazyColumn` item per section, each under a `SettingsHeader` (titleSmall in the primary colour, as Appearance and Music & audiobooks drew theirs). Storage's own "Storage & downloads" title goes, since Library & storage heads it. `SettingsRoute` is now an object: nothing else needed `media`, so `LEADING_KEYS`, `settingsIndexOf` and `MEDIA_SECTION_KEY` went too, and the section no longer draws its own heading (the screen's title says it). `MediaSettingsScreen` sits in `SettingsScreen.kt`, over its own `SettingsViewModel` with `mediaSettings`; Settings itself no longer builds that model. `MediaSettingsRoute`'s rail tool is SETTINGS. `SettingsViewModelTest` read no keys, so it's unchanged. The scroll tests in `MediaSettingsTest` became `musicAndAudiobooksIsItsOwnScreen`, and the new `SettingsScreenTest` checks the sections' order, their rows, that Readiness is gone, that Reading & listening hides with neither row, and Music & audiobooks' rail tool.)*
- [x] Install both builds and check on the Pixel:
  - Downloads from all three doors, with a real queued pack and a cancelled one;
  - Get more's sections and Open;
  - the Library's rows and header;
  - the owl and the open book;
  - the rail's order across five opens;
  - Clear history;
  - the Settings sections and Music & audiobooks.
  Note anything off in the commit message.
  *(Checked on the Pixel 2026-10-28 by the main session from 7397c75: Downloads from the Library (empty state, then a 28 MB pack under Done today with Open), Get more's sections and rows, Open on installed Kiwix rows with Ask's line, the Library by kind with Downloads / Get more / More, the owl and the open book, the rail's four (new items enter on top, a tile tap opens the e-book without reordering), History opening from the rail with Clear history's confirm (Kept), Settings in sections, Music & audiobooks as its own screen. Found: the 'Main Page' tab title, fixed in Task 8.)*
- [x] Full suite green. **Commit.** `Settings in sections; Music & audiobooks its own screen`
- [ ] Neal can merge part 1 here if he wants it on main before part 2.

## Part 2: finding things

### Task 7: Search by kind

- [x] `IndexPages.isIndex(path)`: confirm the SE ZIM paths on the phone first (`users/…`, `tags/…`, the questions and users index pages), then test the patterns in `IndexPagesTest`.
  *(Built: `CL/IndexPages.kt`. The paths were read with libzim on the Mac from the real packs (2026-10-28), not on the phone: Stack Exchange's `users`, `tags`, `questions` (also its main page), their `_page=N` pages, `users/…`, `tags/…` and the `a/<id>` answer stubs; iFixit's `User/…` people pages. A leading "/" or an old "A/" / "C/" namespace is ignored. The KDoc keeps the facts.)*
- [x] `ZimLibrarySearch`: filter `isIndex` from titles and full text. Rank titles: exact, then starts-with, then the rest. `ZimLibrarySearchTest`.
  *(Built: the ranking is a stable sort after the packs' turns, so within a rank today's order holds. Case and accents fold through a new `CL/SearchText` (`fold`, `words`). Feeds leave index pages out too (`FeedDrawer`: random pages and per-pack search), with `FeedDrawerTest.userAndTagPagesAreNotDrawn`.)*
- [x] Snippets: drop one without a query word (case- and accent-insensitive; the libkiwix `<b>` marks count). Book hits group by book (`SearchViewModel`), with the best snippet and a count. `SearchViewModelTest`, `SearchRowsTest`.
  *(Built: `SearchRows.snippetMatches(snippet, query)`: a non-empty `<b>` mark counts (libkiwix marks stemmed matches), else a query word in the text with its tags stripped. The count is `BookHit.matches`, set by `BookIndex.search` from all the book's matching chunks (of the first 400), since only three per book are fetched. The book row reads "Jane Austen · 3 matches"; Show all counts "books".)*
- [x] Headings: Titles, Articles, Books, Music, Audiobooks. `ImeAction.Search`; drop the keyboard on submit and on the first scroll.
  *(Built: the groups now show in that order, Music and Audiobooks last. A scroll that starts hides the keyboard and clears the field's focus; `SearchShowAllTest.searchingOrScrollingPutsTheKeyboardAway`.)*
- [x] **Commit.** `Search by kind: clean titles first, no index pages, books grouped, snippets that match`

### Task 8: Titles without their site

- [x] `PageTitles.clean(title, packTitle)` and `PageTitlesTest`. Cases:
  - Wikipedia, Based Cooking, Parenting Stack Exchange, iFixit;
  - a title that is only the site name stays;
  - a pack title as suffix.
  *(Built: `CL/PageTitles.kt`. The cases use the packs' real `<title>`s, read with libzim on the Mac: Based Cooking's are "Aljotta | Based Cooking" (its pack title is "based.cooking LukeSmith"), Stack Exchange's "… - Computer Science Stack Exchange", and iFixit's have no site at all ("1997-2001 Jeep Cherokee XJ"), so iFixit's case is that they stay as they are. A dash inside an SE question title stays.)*
- [x] Apply it in `ReaderViewModel.onPageShown` / `onPageVisited`, `FeedDrawer.pageTitle` (replacing its regex) and Search's title rows. `ReaderViewModelTest`, `FeedDrawerTest`.
  *(Built: `onPageVisited` stores the path's name, which has no site, so only `onPageShown` cleans, with the pack's title. Search's titles are cleaned in `ZimLibrarySearch` before the ranking, so "Apple Pie | Based Cooking" is an exact match for "apple pie" and the tab it opens stores the clean title; full-text titles are cleaned the same way. Feed cards from a pack's own search are cleaned too. Found on the Pixel: a pack opened from Get more stored the tab title "Main Page" (WikEM). So `clean(title, packTitle, mainPage)` calls the pack's main entry, or a page titled only "Main Page", "Home", "Home page" or "Index", by the pack's title; `onPageShown` passes `mainPage` when the path is the archive's main entry.)*
- [x] **Commit.** `Titles without their site`

### Task 9: Continue and Classics

- [x] `HomeViewModel.continueCards`: drop tabs on their pack's main page or an `IndexPages` page. A play's subtitle names its kind. `HomeViewModelTest`, `HomeScreenTest`.
  *(Built: `RailTabUi` now carries an article tab's `packId` and `path` (empty for a book, which is never dropped). Home reads each OK pack's `mainPath` on `io` and passes them to `continueCards(rail, mainPaths)`. A tab with no path yet is kept. `RailEntry.Play.kind` reads the key: "Album", "Audiobook", "Read aloud", and "Playlist", "Artist", "Genre", "Folder", "Songs" for the other music keys. The rail's TalkBack label still says "Play again: …".)*
- [x] Granger source: fold categories past 8 with "Show all N books". Preset lines count by kind with the status words of Decision 13. `SourceViewModelTest`.
  *(Built: `Section.noun` ("packs" or "books") names the foot row. Book categories fold on the Granger screen unless a search is typed; Granger's own pack categories don't fold, as before. `PresetRow` gains the counts by kind (packs, books, others for models and voices), the total size, `onPhone` and `downloading`; `PresetText.summary` reads "12 packs and 347 books · 17.7 GB" (a model counts as "1 model"), and `PresetText.status` "All on this phone", "3 downloading", "9 of 12 to get", or "3 downloading · 9 of 12 to get" when both. The headline stays "<title> set"; the two lines sit under it, and Get all shows while anything is left to get.)*
- [x] `CollectionScreen`: profile the Classics grid on the Pixel (frame stats via `adb shell dumpsys gfxinfo app.granger`, before and after). Decode covers off the main thread at cell size if that's the cost.
  *(Profiled on the Pixel 2026-10-28 at 0267461 (`dumpsys gfxinfo`, 12 fast flings): the Library's Classics grid 1053 frames, 0.57% janky, p90 12 ms, p99 19 ms; the Granger source screen 575 frames, 0.17% janky, p99 13 ms. Smooth, so nothing changed. Code side: Code side checked: `BookCover` already decodes through `rememberRailCover`, which reads bounds first and decodes on `Dispatchers.IO` with a power-of-two `inSampleSize` for the cell's width (so up to twice the cell), into a 4 MB `LruCache` shared with the rail. So nothing changed here. If the Pixel shows jank, the suspects are that cache (only a few cell-sized covers fit, so scrolling back re-decodes) and the power-of-two sample; an exact-size scale or a bigger cache would be the fix.)*
- [x] **Commit.** `Continue is what you read; Classics in pieces; presets counted by kind`

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
