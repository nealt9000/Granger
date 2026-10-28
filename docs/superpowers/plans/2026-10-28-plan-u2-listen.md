# Plan U2: Finding books, and Listen — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:**
- **Part 1, finding books:** a book you hold is found as easily as an article. Searching "peter pan" puts the Granger Classics *Peter Pan* first. The Classics collection can be searched, sorted and browsed by kind.
- **Part 2, Listen:** the Player tool becomes **Listen**: your player *and* your audio collection.
  - The **Music** and **Audiobooks** shelves move out of the Library into Listen. Each shows only when it holds something; with nothing yet, Listen offers the audio sources and "Find music on this phone". The Library keeps text and maps.
  - The full player has **two tabs**: **Reader** (read-aloud as a full player, with sentences, chapters, speed, voice, sleep and music under it) and the media tab, which is **Music** or **Audiobook**, whichever you last played from the shelves.
  - Every reader and Ask answer has one Listen button with pause and stop. Albums and the queue show what's playing.
  - Podcasts get a shelf later (roadmap).

Spec: `docs/superpowers/specs/2026-10-28-ux-audit-design.md` §6 (U2), revised by Neal on 2026-10-28 (two tabs, Reader and Music or Audiobook; the audio shelves move to Listen; an icon for the player and the collection), plus Neal's walkthrough notes W10 and W13 and the system-voice fold (W12). The roadmap row is U2, and the books-search note is Neal's (2026-10-28).

**Out of this plan:**
- The one reader toolbar and chrome that slides away: U3c. Part 2 adds the Listen button to today's toolbars, and U3c restyles them.
- V4's British voices. Reader uses the best installed voice; new voices join the list when they ship.
- Bookmarks *inside* the read-aloud player. The readers' own Saved bookmarks stay where they are; a read-aloud bookmark would duplicate them.

**Tech Stack:** unchanged (Kotlin 2.4.21, Compose Material 3, Room, Media3, sherpa-onnx, Robolectric 4.17, JUnit 4 + Truth).
- Build env as U3b. The worktree is `.claude/worktrees/u2` (branch `u2-listen`, off main `9b71fa5`) and needs `local.properties` and `git submodule update --init --recursive`.
- The Pixel is on Wi-Fi as `adb-21121FDF6007JN-gtbanj._adb-tls-connect._tcp`.
- **One app on the phone:** install over `app.granger` only (Neal, 2026-10-28).

**Roadmap:** row U2 in `docs/superpowers/plans/2026-10-15-granger-v1-roadmap.md`, after U3b (main `9b71fa5`). U3c follows.

## What the code looks like now (surveyed 2026-10-28, main `9b71fa5`)

Paths: `A/` = `app/src/main/kotlin/app/granger/`, `CL/` = `core/library/src/main/kotlin/app/granger/core/library/`, `CP/` = `core/player/src/main/kotlin/app/granger/core/player/`, `CS/` = `core/speech/src/main/kotlin/app/granger/core/speech/`.

**Finding books**
- **Search never looks at book titles.** `ZimLibrarySearch.titles()` (`CL/LibrarySearch.kt:41`) only calls `archive.suggest()` on ZIM packs. Nothing in Search matches bookshelf titles or authors; `MediaIndex` covers music and audiobooks.
- **Book text search can miss the book entirely.** `BookIndex.search(query, 30, perBook = 3)` (`CL/BookIndex.kt:79–119`):
  - turns "peter pan" into `"peter" "pan*"`: AND of two words, with a prefix that also matches *panic*, *pane*, …;
  - runs `… WHERE book_text MATCH ? LIMIT 400` with **no ORDER BY**, so it takes the first 400 matching chunks in indexing order;
  - only then groups by book.

  A book indexed late is cut before it's seen. BM25 ranking exists only in `rankedPassages()` (l.134, used by Ask). `SearchViewModel` runs books last (after titles, media and full text), and Books draws third.
- **The Classics collection has no way in.** `CollectionScreen` (`A/library/CollectionScreen.kt:61`) is a `LazyVerticalGrid` of `BookCell`s (cover, title, no author), ordered by title (`BookDao.observeAll`, `Daos.kt:218`). There's no filter, sort or grouping. `Book` has `subjects` but no category. The catalog's `BookEntry` has `category` and `author`.
- **The Granger source's search** (`SourceViewModel.shown`, l.398) matches title and description, not author.

**Listen**
- **The player:**
  - `PlayerRoute`, `PlayerScreen` (`A/player/PlayerScreen.kt:52`). Tabs come from `PlayerMode.entries` (`CP/PlayerHub.kt:52`: `MUSIC, AUDIOBOOK`; the voice has no tab).
  - `playerTab(picked, now, lastMode)` (`PlayerViewModel.kt:88`).
  - Rail tool `RailTool.PLAYER("Player")` with `Icons.Filled.PlayArrow` (`Rail.kt:72,101`). `togglePlayer()` is at `GrangerNavHost.kt:319`.
- **Read-aloud:**
  - `ReadSource` / `ReadSourceKey` (`CS/ReadSource.kt`), `ReadAddress` (`CS/Utterance.kt`).
  - Hub API: `readAloud`, `stopVoice`, paragraph and sentence steps, `jumpTo`, mix, `setVoice`, `voiceProgress` (`CP/PlayerHub.kt:108–145`).
  - Engine: `CP/PlayerEngine.kt:510–1050`. Music under the voice runs on a second player.
  - The saved place is `voice_states` (`MediaEntities.kt:93`), but readers don't resume from it; only "Play again" and the engine's restore read it.
- **VoiceSheet** (`A/player/VoiceSheet.kt:81`), a bottom sheet:
  - Previous, current and next sentences.
  - Previous paragraph, back a sentence, play/pause, next paragraph. **No next sentence.**
  - Speed slider and presets; voice chips; the music row; Sleep; Stop reading; Open.
- **Sleep bug:** "End of chapter" is offered for the voice, but `untilSleepTargetEnds()` returns null in VOICE mode (`PlayerEngine.kt:1437`), so it never fires.
- **Mini player** (`A/player/MiniPlayer.kt`): for the voice it shows "Reading · section", and a tap opens VoiceSheet (NavHost l.670).
- **Rail:** `RailEntry.Reading`, with one entry per book since U3b (the tab is badged instead).
- **Chapters:**
  - E-books: `BookReadSource.tocTitles` and `addressAtHref(href)` (`A/ebook/BookReadSource.kt:67,136`); the TOC comes from Readium (`EbookReaderViewModel.toc`, l.234).
  - Articles: `ArticleReadSource` (`A/reader/ArticleReadSource.kt`) makes HEADING utterances, and `sectionTitle()` gives the last heading. No API lists sections (`all()` is private).
- **Entry points:**
  - Wiki reader: the overflow's "Read aloud", "Pause/Resume reading" and "Stop reading" (`ReaderScreen.kt:376–428`).
  - E-book reader: `VoiceBarButton`, headphones; a long press stops (`ebook/ReaderControls.kt:74`).
  - Ask: `ReadAloudRow` (`ResearchScreen.kt:466`).
  - `VoiceButton.of(ui, sourceKey)` gives IDLE / READING / PAUSED.
- **Audiobook tools:** `AudiobookPlayer` (`A/player/AudiobookPlayerScreen.kt:63`). Its sheets are `ChaptersSheet`, `SpeedSheet`, `SleepTimerSheet` and `AudioBookmarksSheet`; `SleepSetting.EndOfChapter` is in `CP/SleepTimer.kt`.
- **Audiobooks visibility:**
  - The Library row and Home's Add-feed choice already hide without audiobooks.
  - The Player's Audiobooks tab always shows.
  - Settings → Music & audiobooks and Get more's "Audiobooks & music" are always there.
  - There's no count flow; use `MediaLibrary.audiobooks` (status OK), as Home does.
- **Music play state:**
  - `AlbumScreen` / `SongRow` (`A/music/AlbumScreen.kt`, `MusicParts.kt:123`) don't mark the playing track. The album's Play (`PlayShuffleRow`, `MusicParts.kt:225`) always restarts from the top.
  - `QueueSheet` marks the current row by colour only (`QueueSheet.kt:134,204`).
  - The current track is `(ui.now as NowPlaying.Music).track.id`; `PlayerUi.playing` is true when the player wants to play.
- **Voice settings** (`A/settings/VoiceSettingsScreen.kt`): "Reads with" shows every Granger voice plus device-language system voices, with the rest behind "Other system voices (N)" (`splitVoices`, l.176).
- **Tests:** baseline 2200 JVM tests, 0 failed (main `9b71fa5`). Main classes:
  - Player: `PlayerTabsTest`, `PlayerViewModelTest`, `MiniPlayerTest`, `VoiceSheetTest`, `VoiceButtonTest`.
  - Core: `PlayerEngineVoiceTest`, `SleepTimerTest`.
  - Music: `MusicViewModelTest`, `AlbumRowsTest`.
  - Elsewhere: `SplitVoicesTest`, `ReaderTopBarTest`, `ArticleReadSourceTest`, `BookReadSourceTest`, `BookIndexTest`, `ZimLibrarySearchTest`, `SearchViewModelTest`, `CollectionViewModelTest`, `SourceViewModelTest`, `RailTest`, `HomeViewModelTest`.

## Decisions

1. **Books are titles too.** Search's Titles group adds the books on the shelf:
   - **Matching:** title or author, case- and accent-folded with `SearchText`.
   - **Order:** exact title first, then starts-with, then title contains, then author matches, then pack titles in their U3b order. A book row reads "Peter Pan · J. M. Barrie · Granger Classics" and opens the book.
   - **Where:** the query runs in `ZimLibrarySearch` (or beside it) over `bookshelf.books`.
   - **Speed:** it needs no ZIM, so it comes back with the first results.
2. **Book text search ranks.** `BookIndex.search`:
   - quotes a multi-word query as a phrase ("peter pan"), with a prefix only on the last word;
   - orders matching chunks by `rankedPassages`' BM25 (one ranking for Search and Ask) before grouping, with no unordered `LIMIT 400`;
   - takes the top 30 chunks by rank across books, up to 3 per book.

   `SearchViewModel` runs books before full text, so the Books group fills first. The groups' order on screen stays Titles, Articles, Books, Music, Audiobooks.
3. **A collection can be searched, sorted and browsed.** `CollectionScreen` for a book collection gets:
   - a **Find** field (title or author);
   - **sort** chips: Title, Author, Recently read (`book_positions` / reading tabs);
   - for Granger Classics, **category** chips from the catalog's `BookEntry.category`, mapped by book id;
   - the **author** under each cell's title.

   The Granger source's search matches author too.
4. **Listen is the tool: your player and your collection** (Neal, 2026-10-28).
   - `RailTool.PLAYER` becomes **Listen**. Its icon says "player and collection": Material **library_music** (a stack of records with a note), as a vector `ic_listen.xml`. If it doesn't read well at rail size, a headphones-over-a-device glyph traced from Material parts is the fallback; check it in `ThemeShots`. **Picked (Neal, after the render): plain headphones (`ic_headphones`), for now.**
   - **Listen's home** (`ListenRoute`, the tool's landing) has:
     - a **Now playing** card at the top, for the live or last item: voice, music or audiobook, opening the player;
     - the **shelves**: Music and Audiobooks, plus Podcasts once it exists (roadmap). Each appears only when it holds something, with the same rows the Library has today ("All music", collections, folders, playlists; "All audiobooks", collections), the same screens and the same collection features.
     - **With no audio at all:** "Nothing to listen to yet", then the audio sources (LibriVox, Archive.org audiobooks, Archive.org music, each opening its source) and **Find music on this phone** (the media permission and folders, Settings → Music).
   - **The Library loses Music and Audiobooks**: Encyclopedias & reference, Books, Maps. Every music and audiobook route (`MusicRoute`, `AlbumRoute`, `ArtistRoute`, `GenreRoute`, `PlaylistRoute`, `FolderRoute`, `AudiobookShelfRoute`, `AudiobookRoute`, `LibriVoxRoute`, the audio `ArchiveRoute`s) lights the Listen tool. Get more keeps listing every source; Listen's empty state links the audio ones.
   - **The full player** (`ListenPlayerRoute`, today's `PlayerRoute`) has **two tabs**: **Reader** and the **media tab**. The media tab is titled **Music** or **Audiobook** after the hub's live media mode, else the last one played from a shelf (`SavedModes`); with neither ever played, it's **Music**. `PlayerMode` stays in core, and an app-side `ListenTab { READER, MEDIA }` drives the UI. Switching music ↔ audiobook happens on the shelves, not with a third tab.
   - The tab opens on what's live: the voice → Reader, music or an audiobook → the media tab, else the last used.
   - The mini player's tap opens the player on the live tab, so VoiceSheet goes.
5. **Reader is the read-aloud player.** It's VoiceSheet's content as a full tab:
   - title and section; the previous, current (large) and next sentences;
   - controls: previous paragraph, back a sentence, play/pause, **next sentence**, next paragraph;
   - **Chapters**: the book's TOC, or the article's sections from a new `ArticleReadSource.sections()`. Picking one `jumpTo`s its first sentence.
   - **Speed**, **Voice** and **Sleep** (with End of chapter working for the voice: the end of the current TOC chapter or article section);
   - the **music under it** row; **Stop**; **Open** (the source in its reader, at the sentence).

   With nothing loaded, Reader shows the last read ("Resume *Peter Pan* · Chapter 3") from `voice_states`, plus "Open a book or article and tap Listen". A text's saved place is `voice_states`: Resume and a reader's Listen on that text start there, unless the reader is scrolled elsewhere. In that case the first visible sentence wins, as today.
6. **One Listen button.**
   - The wiki reader gets a toolbar headphones button (out of the overflow); the e-book reader keeps its bar button; Ask's answers keep theirs.
   - While reading, each shows **Pause** and a separate **Stop**: no long-press-to-stop, no stop hidden in a menu. Same icons everywhere: headphones to start, pause/play, stop.
   - `VoiceButton` stays the state source.
7. **Audiobooks are passive.** Without an audiobook on the phone:
   - Listen has no Audiobooks shelf, and the media tab is never titled Audiobook;
   - Settings' row reads **Music** ("Music & audiobooks" once one exists);
   - the audiobook-only settings (skip back/forward) hide;
   - LibriVox stays in Get more and in Listen's empty state; it's the way to add one.

   Home and onboarding already say nothing about them. The signal is one `audioShelves: Flow<AudioShelves(music, audiobooks)>` from the shelves (non-empty, status OK).
8. **Albums show what's playing** (W10).
   - The album's track list marks the playing track: an animated equalizer icon in place of the number, title in the accent colour.
   - The album's **Play** becomes **Stop** while this album is the queue's source and playing; Stop stops (`hub.stop`, not pause) and the button returns to Play. Shuffle stays.
   - The queue sheet's current row gets the same equalizer icon.
9. **System voices fold away.** Voice settings' "Reads with" shows the Granger voices, plus the chosen voice if it's a system one. Every system voice is behind **System voices (N)**.

## Global Constraints

- `./gradlew testDebugUnitTest :core:research:test` stays green after every task. The count only grows.
- No schema migration: the book search changes are queries; categories come from the catalog.
- No catalog change.
- Commit after every task with the message given, ending with the Co-Authored-By line.
- One app on the phone.

## Review Focus

- "peter pan": is the book first in Titles, and *Peter Pan* first in Books? Do "barrie" and "pan" still work?
- Does anything still open VoiceSheet? Do Music and Audiobooks show in Listen (and not the Library) only when they hold something, and does the empty state offer the sources and the phone? Is the media tab titled after what you last played?
- Does End of chapter stop the voice at a chapter or section end?
- Album: does Play → Stop → Play behave? Is the playing track marked, including after Next from the mini player?

---

## Part 1: finding books

### Task 1: Search finds books

- [ ] Record the baseline: `./gradlew testDebugUnitTest :core:research:test`, with the count in the commit message.
- [ ] Book titles in Titles (Decision 1): `BookTitleSearch` (pure, over `List<Book>` + the query), merged into `TitleResults` ahead of pack titles by rank. A book row opens its tab (`sessions.openTab(bookId, "")` as Downloads does).
- [ ] `BookIndex.search` (Decision 2): phrase query, BM25 order (shared with `rankedPassages`), top 30 by rank, 3 per book. Books are fetched before full text in `SearchViewModel`.
- [ ] Tests:
  - `BookTitleSearchTest`: exact, starts-with, author, accents;
  - `BookIndexTest`: a late-indexed book with the phrase ranks above earlier books that only have both words apart; prefix on the last word only;
  - `SearchViewModelTest`: "peter pan" puts the book first in Titles.
- [ ] **Commit.** `Search finds books: titles and authors in Titles, phrase and rank in Books`

### Task 2: A collection you can search and sort

- [ ] `CollectionViewModel`: the query (title or author), sort (Title / Author / Recently read) and category (Granger Classics only; from `catalog.books` by id), all saved per collection in `SavedStateHandle`.
- [ ] `CollectionScreen` for book collections: a Find field, the sort chips, the category chips (a scrolling row), and the author under each `BookCell` title (one line, ellipsis).
- [ ] `SourceViewModel.shown`: a book matches on author too.
- [ ] Tests: `CollectionViewModelTest` (filter, each sort, category, empty result); `SourceViewModelTest` (author match).
- [ ] **Commit.** `Collections: find, sort and browse by kind; authors under the covers`

## Part 2: Listen

### Task 3: Listen, your player and your collection

- [ ] The tool (Decision 4): `RailTool.LISTEN("Listen")` with `ic_listen` (library_music; check it in `ThemeShots` beside the owl and the open book, and use the fallback glyph if it doesn't read). `ListenRoute` is the tool's landing; `railToolOf` maps every music, audiobook and audio-source route to LISTEN.
- [ ] Listen's home: the Now playing card, the Music and Audiobooks shelves (move the Library's music and audiobook rows here: "All music", collections, folders, playlists, "All audiobooks", using the same `Shelves` data and row composables), and the empty state with the audio sources and Find music on this phone. `ListenViewModel` builds `ListenUi` from `shelves.shelves` (audio media types only), `SavedModes` and the hub.
- [ ] The Library: drop the music and audiobook kinds from `libraryKinds` (`FOLLOWING` keeps maps). Its empty state stays text-only.
- [ ] Tests: `ListenViewModelTest` (shelves appear only with content; empty state; Now playing); `LibraryViewModelTest` (no audio kinds); `RailTest` (Listen label and icon); the route → tool tests.
- [ ] **Commit.** `Listen: your player and your collection; music and audiobooks leave the Library`

### Task 3b: The player's two tabs, with Reader

- [ ] `ListenPlayerRoute` (today's `PlayerRoute`) with `ListenTab { READER, MEDIA }`. The media tab is titled Music or Audiobook after the live media mode, else the last played (`SavedModes.lastMode`), else Music. Today's Music and Audiobook players render inside it, as now.
- [ ] Delete VoiceSheet and its NavHost state. The mini player opens the player on the live tab. Move VoiceSheet's pieces (sentences, speed row, voice row, music row) into `player/Reader*.kt`.
- [ ] Reader tab content (Decision 5), without Chapters yet: the sentences, the five controls (add `nextSentence` to the hub or engine if missing), speed, voice, music under it, Sleep, Stop, Open. The nothing-loaded state shows Resume from `SavedModes.voice`.
- [ ] Tests:
  - `ListenTabsTest` (replaces `PlayerTabsTest`): two tabs; the media tab's title follows live, then last, then Music; the opening tab follows what's live.
  - `ReaderTabTest` (replaces `VoiceSheetTest`): controls, Resume.
  - `MiniPlayerTest`: the tap target.
- [ ] **Commit.** `The player's two tabs: Reader for read-aloud, and Music or Audiobook`

### Task 4: Chapters and sleep for every text

- [ ] `ArticleReadSource.sections()`: the HEADING utterances' addresses and titles. `BookReadSource.chapters()`: the TOC rows with their `addressAtHref`. A `ReadSource.chapters()` default returns empty for plain text.
- [ ] Reader's **Chapters** sheet (reuse `ChaptersSheet`'s look): the current chapter marked; pick → `jumpTo`.
- [ ] Engine: `untilSleepTargetEnds()` for VOICE is the time to the end of the current chapter. Estimate it from the remaining utterances to the next chapter start at the current speed (sentence count × measured average utterance ms). The timer re-checks at each utterance and stops at the boundary itself, not at the estimate.
- [ ] Saved place: Reader's Resume and a reader's Listen start at `voice_states`' address when the reader is at (or before) it, else at the first visible sentence. `ReadAloudTest` / `EbookReaderViewModelTest` / `ReaderViewModelTest`.
- [ ] Tests: `ArticleReadSourceTest.sections`, `BookReadSourceTest.chapters`, `PlayerEngineVoiceTest` (End of chapter stops at the boundary), `SleepTimerTest`.
- [ ] **Commit.** `Reader: chapters for books and articles, End of chapter that works, a saved place per text`

### Task 5: One Listen button, pause and stop

- [ ] Wiki reader: a toolbar headphones button (Read aloud → Pause/Play), plus a Stop button while reading. Out of the overflow. `ReaderTopBarTest` (order: Back, main page, search, Listen, star, More).
- [ ] E-book reader: `VoiceBarButton` → Listen / Pause / Play plus a Stop button while reading; no long press. Ask's `ReadAloudRow`: the same pair.
- [ ] Tests: `VoiceButtonTest`, the readers' bar tests, `AskLandingTest` / `ResearchViewModelTest` where the row is covered.
- [ ] **Commit.** `One Listen button in every reader and answer, with Pause and Stop`

### Task 6: Audiobooks passive; albums and the queue show what's playing; system voices fold

- [ ] Audiobooks passive (Decision 7): `audioShelves` drives Listen's shelves (Task 3), the media tab's title, Settings' row title, and the skip settings. `SettingsScreenTest`, `MediaSettingsTest`, `ListenTabsTest`.
- [ ] Albums (Decision 8): `MusicViewModel` reads `hub.ui` for the current track id, playing state and queue source. `SongRow` gets a `playing` state (equalizer icon, accent title). `PlayShuffleRow`'s Play ↔ Stop. `QueueRow` gets the icon. `MusicViewModelTest`, `AlbumRowsTest`, a `SongRow` Compose test.
- [ ] Voice settings (Decision 9): `splitVoices` keeps Granger voices plus a chosen system voice; every other system voice is behind "System voices (N)". `SplitVoicesTest`.
- [ ] **Commit.** `Audiobooks only when you have one; albums and the queue show what's playing; system voices fold away`

### Task 7: Walkthrough (Neal, Pixel 6)

- [ ] Install over `app.granger`. Check:
  - **Search:** "peter pan" (the book first in Titles and Books), "barrie", an author.
  - **Granger Classics:** Find, sort and categories.
  - **Listen's home:** the Now playing card; the Music and Audiobooks shelves there and gone from the Library; the icon.
  - **The player:** two tabs, Reader and Music or Audiobook (play an album, then an audiobook, and watch the tab's title).
  - **Reader:**
    - read an article and a book, with every control, Chapters, and End of chapter;
    - music under the voice; Stop; Open;
    - Resume from the empty Reader tab.
  - **The Listen button** in the wiki reader, the e-book reader and an Ask answer (Pause and Stop).
  - **Audiobooks:** no shelf without them. Add one LibriVox book, see the shelf and the Settings row appear, then remove it.
  - **An album:** the playing track marked, Play ↔ Stop, the queue's marked row.
  - **Voice settings:** the fold.
- [ ] Record the result in the roadmap's U2 row and a "Carried forward from U2" section. Merge `u2-listen` into main and push if Neal passes it.
- [ ] **Commit.** `Roadmap: U2 done`
