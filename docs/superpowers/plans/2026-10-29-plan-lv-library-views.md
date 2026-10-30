# Plan LV: Library views — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** every shelf in the Library and Listen shows its items the same way, from one shared component.
- **The default view** is an alphabetical list you can jump through with a letter strip.
- **The other view** is a cover grid.
- The choice of view and sort is remembered per shelf.

Neal, U3 closing walkthrough (2026-10-29): "We should offer a couple of different views, and the default should be a list that you can jump to things quickly alphabetically, and that be something we standardize across all the library shelves."

**Settled with Neal, 2026-10-29:**
- **Scope:** books, music and audiobooks (Decision 1).
- **Sorting:** titles skip a leading The, A or An (Decision 3).
- **Audiobooks:** keep a Continue row above the A–Z list (Decision 7).

**Out of this plan:**
- Browse and the catalogs (Get more, Kiwix, archive.org, LibriVox). They're short or already grouped.
- Playlists, album pages and an artist's discography, which keep their own order.
- Downloads (roadmap DL). Podcasts (PC).

**Tech Stack:** unchanged (Kotlin 2.4.21, Compose Material 3, Room, Robolectric 4.17, JUnit 4 + Truth). No schema migration and no catalog change.
- **Build env:** `JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home ANDROID_HOME=/opt/homebrew/share/android-commandlinetools`.
- **Worktree:** `.claude/worktrees/lv` (branch `lv-library-views`, off main `9479962`), with submodules and `local.properties` already set up.
- **Phone:** the Pixel is on Wi-Fi as `adb-21121FDF6007JN-gtbanj._adb-tls-connect._tcp`. One app on the phone: install over `app.granger` only.

**Roadmap:** the LV row in `docs/superpowers/plans/2026-10-15-granger-v1-roadmap.md`. DL follows.

## What the code looks like now (surveyed 2026-10-29, main `9479962`)

Paths are under `app/src/main/kotlin/app/granger/`. Nothing in the app has a grid/list toggle, a fast scroller, a letter index or sticky headers.

**Books**
- `library/CollectionScreen.kt` `CollectionScreen` covers Classics (about 350 books), My books, Archive.org texts, imports and user collections.
  - Layout: one `LazyVerticalGrid(GridCells.Adaptive(104.dp))`:
    1. Pack rows spanning the full width.
    2. "Find a title or author".
    3. A `LazyRow` of sort `FilterChip`s (`BookSort { TITLE, AUTHOR, RECENT }`).
    4. Category chips (Classics only).
    5. `BookCell`s with a long-press Move/Remove.
  - The pure function `collectionBooks(...)` (`library/CollectionBooks.kt`) filters and sorts off the main thread.
  - Query, sort and category live in `SavedStateHandle` (session only).
  - Covers: `BookCover` (`library/BookCover.kt`), falling back to `MediaTile`.
- **Title sort:** `SearchText.fold(title)` (core/library `SearchText.kt`) strips accents and lowercases. Nothing skips articles.
- **Author sort:** `authorKey` (`CollectionBooks.kt:61`), surname first; a blank author goes last.

**Music**
- **`music/MusicScreen.kt`:** tabs Songs, Albums, Artists, Genres, Playlists, Folders.
  - **Songs:** a `LazyColumn` of `SongRow` with a "Sort: X" dropdown over `SongSort { TITLE, ARTIST, ALBUM, YEAR, DATE_ADDED }`. The sort is a `MutableStateFlow` in `MusicViewModel` (L322), so it resets on every visit.
  - **Albums:** `AlbumGrid`, a `LazyVerticalGrid(Adaptive(150.dp))`. `Albums.summaries` sorts by album artist, then year, then album (core/library `Albums.kt:71`).
  - **Artists, Genres, Folders:** `LazyColumn`s of `ListItem`.
  - Names sort by `sortName(sort, name)`: the tag's sort field (e.g. "Beatles, The"), else the name, lowercased.
- **Artist, Genre, Folder, Playlist and album pages:** their own layouts and orders (out of scope).
- **Covers:** `AlbumCover(path, title, kind)` (`music/MusicParts.kt:192`), falling back to `MediaTile`.
- **Android Auto:** `media/AutoBrowseTree.kt:76` sorts albums by `title.lowercase()`.

**Audiobooks**
- `audiobooks/AudiobookShelfScreen.kt`: a `LazyVerticalGrid(Adaptive(150.dp))` of `ShelfCell`. A missing book is dimmed.
- Its order is fixed (`AudiobooksViewModel.kt:123`): in progress by last played, then unplayed by title, then finished. There are no headers.

**Prefs:** SharedPreferences wrapped in `StateFlow`s (`AppearancePrefs`, `RailOrder`, `playerPrefs` in `GrangerApp.kt:402`). There is no DataStore.

**Tests:**
- `library/CollectionViewModelTest.kt`, `music/MusicTabTest.kt`, `MusicViewModelTest.kt`, `audiobooks/AudiobooksViewModelTest.kt`, `theme/ThemeShots.kt`.
- There are no instrumented tests.

## Decisions

1. **Which shelves.**
   - **The full shared view** (A–Z list + letter strip, or the cover grid):
     - every book collection (Classics, My books, Archive.org texts, imports, user collections);
     - music's **Songs**, **Albums** and **Artists**;
     - the **audiobook shelf**.
   - **Genres and Folders** are short lists. They get the same A–Z sort key and no strip or grid.
   - **Playlists, album pages, Artist and Genre pages** keep their own order.
2. **Two views, List and Grid. List is the default everywhere.**
   - **List:** one row per item with:
     - a 48 dp cover (the same `BookCover` / `AlbumCover` / `AudiobookCover`, so `MediaTile` stands in for missing art);
     - the title (two lines at most);
     - a second line (author, artist, or artist · year);
     - for a long list sorted by name, **sticky letter headers** (A, B, C … #).
   - **Grid:** today's cover cells (books 104 dp, albums and audiobooks 150 dp). Letter headers span the full width when sorted by name.
   - **The toggle:** one icon button in the shelf's header, showing the view you'd switch to (grid icon in List, list icon in Grid).
3. **One sort key, articles skipped.**
   - `ShelfKey.of(title, sortTag)` lives in core/library next to `SearchText`:
     - it uses the tag's sort field when there is one (music);
     - else it drops a leading "The ", "A " or "An " (case-insensitive, English only), then `SearchText.fold`s.
   - `ShelfKey.letter(key)` is A–Z for a key starting with a letter, and "#" for digits and everything else.
   - **Where it's used:**
     - title sorts for books, albums, songs and audiobooks;
     - artist, genre and folder sorts;
     - Android Auto's album order.
   - **Author sort** keeps `authorKey` (surname); its letter is the surname's.
4. **The letter strip.**
   - **Where:** a slim column on the edge away from the rail (the right, unless the rail is set to the right).
   - **When:** only in a list or grid sorted by name (title, author, artist) and longer than 30 items.
   - **Which letters:** only the ones present, spread over the height. On a short screen, letters are dropped evenly and a dot stands in for them.
   - **Tap or drag:** jumps to that letter's header. While dragging, a bubble shows the letter beside your thumb, with a light haptic tick on each change.
   - **Not shown** for Recent, Year or Date added. Those sorts have no letter headers either.
   - **Accessibility:** each letter has a content description ("Jump to M"), and the strip is reachable by TalkBack.
5. **Sorts per shelf, remembered.**
   - **Books:** Title (default), Author, Recent: the existing chips, unchanged.
   - **Songs:** Title (default), Artist, Album, Year, Date added: the existing dropdown.
   - **Albums:** Title (default), Artist, Year. Artist keeps today's artist → year → album order.
   - **Artists:** by name only.
   - **Audiobooks:** Title (default), Author, Recent.
   - **Storage:** a new `LibraryViewPrefs` (SharedPreferences `"library_views"`) remembers the view and the sort **per shelf kind**: `books`, `songs`, `albums`, `artists` and `audiobooks`.
     - Every book collection shares `books`, so Classics and My books look alike. This is the "standardize" Neal asked for.
     - The query and Classics' category stay in `SavedStateHandle` for the session.
6. **The shared component.**
   - **Data:** `library/shelf/ShelfView.kt` holds the generic shelf:
     - `ShelfItem` (key, title, subtitle, letter);
     - `ShelfSections` (items grouped by letter, computed off the main thread);
     - `ShelfList` and `ShelfGrid`, which take slots for the cover and the row/cell content, so each shelf keeps its own tap, long-press and dimming;
     - `LetterStrip`.
   - **Header:** `ShelfHeader` has the sort control and the view toggle. Books keep their chips above it; music's Songs keeps the dropdown.
   - **Scroll:** jumps use the `LazyListState` / `LazyGridState` and a letter → index map. Each item's `key` stays stable, so the scroll position survives a sort change back to the same sort.
7. **Audiobooks: a Continue row.**
   - **What:** a horizontal row of at most 4 books in progress (by last played), above the A–Z list and only when there are any. It's titled "Continue".
   - **The list below** has every audiobook, including those books.
   - **Finished books** carry a small "Finished" tick in their row. They're no longer sorted to the end.
8. **Big shelves stay smooth.**
   - Grouping and the letter → index map are built off the main thread, in the view model's flow (as `collectionBooks` already is).
   - Rows use stable keys and `contentType`.
   - Profile Classics (about 350) and Songs at thousands. A seeded `MusicFakes` library of 5,000 tracks runs in a Robolectric timing test, and the real check is on the phone. U3b's 0.17 % jank on Classics is the bar.

## Global Constraints

- `./gradlew testDebugUnitTest :core:research:test` stays green after every task, and the count only grows. The baseline is 2410 (18 skipped).
- No schema migration and no catalog change.
- Commit after every task with the message given, ending with the Co-Authored-By line.
- One app on the phone.

## Review Focus

- Does every in-scope shelf open in List, sorted A–Z, with letter headers and the strip, and does it remember a switch to Grid or another sort?
- Does "The Odyssey" file under O, "1984" under #, and "Émile" under E?
- Does dragging the strip land on the right header in both views, at the right edge and away from the rail?
- At 360 dp, do the header's controls fit with Classics' find field and chips?
- Is Songs smooth at 5,000 tracks?

---

### Task 1: The sort key and the view prefs

- [ ] Record the baseline in the commit message.
- [ ] `ShelfKey` in core/library (Decision 3):
  - `of(title, sortTag = null)` and `letter(key)`;
  - unit tests for articles, case, accents, digits, punctuation, an empty title, and a sort tag winning.
- [ ] `LibraryViewPrefs` (Decision 5):
  - `ShelfKind { BOOKS, SONGS, ALBUMS, ARTISTS, AUDIOBOOKS }`, `ShelfView { LIST, GRID }`, and a sort id per kind;
  - built in `GrangerApp`'s container beside `readingPrefs`;
  - `LibraryViewPrefsTest` (defaults List and Title; a change survives a new store on the same prefs).
- [ ] **Commit.** `Shelves: one A–Z key that skips The/A/An, and remembered views`

### Task 2: The shared shelf

- [ ] `library/shelf/`:
  - `ShelfSections` (pure; tests for grouping and the letter → index map);
  - `ShelfList` with sticky headers;
  - `ShelfGrid` with full-width headers;
  - `LetterStrip`;
  - `ShelfHeader` (Decisions 2, 4 and 6).
- [ ] The strip goes on the edge away from the rail, reading the rail's side from the shell (the same source the reader insets use).
- [ ] Tests:
  - Robolectric: tapping M scrolls the M header into view in both views;
  - the strip hides under 31 items and for Recent;
  - each letter's content description.
- [ ] ThemeShots: a 60-item list and grid, light and dark, at 360 dp, to the scratchpad. Review the renders before Task 3.
- [ ] **Commit.** `Shelves: a shared list and grid with letter headers and a letter strip`

### Task 3: Books

- [ ] `CollectionScreen` uses the shared shelf for its books:
  - the pack rows, find field and chips stay above;
  - `BookCell` is the grid cell;
  - a new `BookRow` (cover, title, author, the format label) is the list row;
  - long-press Move/Remove works in both views.
- [ ] `collectionBooks` sorts with `ShelfKey` and returns the letter sections.
- [ ] The sort chips read and write `LibraryViewPrefs` (`books`), and the view toggle sits in the top bar.
- [ ] Tests: update `CollectionViewModelTest`, and add a screen test (List by default; toggling to Grid survives leaving and coming back; "The Odyssey" under O).
- [ ] **Commit.** `Books: A–Z list by default, with the cover grid a tap away`

### Task 4: Music

- [ ] **Songs:** the shared list, with the strip on Title, Artist and Album. The dropdown's sort persists in `LibraryViewPrefs` (`songs`). A grid isn't offered for songs (a song has no cover of its own), so its view toggle is hidden.
- [ ] **Albums:** List (default) or Grid. Sorts Title, Artist, Year. `AlbumCell` in the grid; a row with a 48 dp cover and "artist · year" in the list.
- [ ] **Artists:** the shared list (name, "n albums · m songs"), with the strip. No grid.
- [ ] **Genres and Folders:** sort by `ShelfKey`. **Android Auto's album order:** `ShelfKey`.
- [ ] Tests:
  - update `MusicTabTest` and `MusicViewModelTest`;
  - a 5,000-track `MusicFakes` timing test: sections built in under 200 ms on the JVM.
- [ ] **Commit.** `Music: songs, albums and artists A–Z with the letter strip`

### Task 5: Audiobooks

- [ ] `AudiobookShelfScreen` uses the shared shelf (Title default, Author, Recent; List or Grid).
- [ ] It adds the Continue row (Decision 7). Missing books stay dimmed in both views.
- [ ] Tests: update `AudiobooksViewModelTest` (A–Z order, Continue holds the in-progress books by last played, at most 4).
- [ ] **Commit.** `Audiobooks: A–Z with a Continue row on top`

### Task 6: Walkthrough (Neal, Pixel 6)

- [ ] Install over `app.granger`.
- [ ] Profile Classics and Songs scrolling and a strip drag: `dumpsys gfxinfo app.granger`, compared with U3b's 0.17 %.
- [ ] Neal checks:
  - Classics, My books, Songs, Albums, Artists and Audiobooks open as A–Z lists with headers;
  - the strip jumps (tap and drag), and stays away from the rail;
  - Grid and a sort change are remembered after leaving and coming back;
  - "The …" titles file under their second word;
  - the Continue row on Audiobooks;
  - 360 dp fit.
- [ ] Fix what the walkthrough finds. Record the result in the roadmap's LV row and a "Carried forward from LV" section.
- [ ] Merge and push only on Neal's say-so.
