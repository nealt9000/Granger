# Plan U3c: Browse, and reading — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:**
- **Part 1, Browse:** wiki and Kiwix packs (and maps) leave the Library for their own rail tool, **Browse**, above the Library. Browsing a linked site and reading a book through are different experiences, as Listen showed for audio (Neal, U2 walkthrough). The Library becomes books.
- **Part 2, reading:**
  - one reader bar for wiki, e-book and PDF;
  - chrome that slides away while you read and comes back on a tap, over the page rather than reflowing it (D3);
  - themed cover tiles wherever art is missing (W8);
  - the reader items carried forward since Plan 5.
- U3 ends with a fresh-install walkthrough a friend could follow without Neal.

Spec: `docs/superpowers/specs/2026-10-28-ux-audit-design.md` §5 (U3c, with Browse added 2026-10-28), D3, B6, B7, B8, W8 and the carried-forward list in §3. Neal settled Browse's questions on 2026-10-28: the name **Browse**, **maps go to Browse**, and on short phones **the open items shrink** rather than a tool going.

**Out of this plan:**
- W6, archive.org comics as text EPUBs; W11, the Classics covers' old palette (a catalog re-release); vectors for more packs. These are separate jobs.
- Markdown beyond what Ask's answers use. The full-screen PDF annotation and highlights.
- Podcasts (roadmap PC).

**Tech Stack:** unchanged (Kotlin 2.4.21, Compose Material 3, Room, Readium, WebView, Robolectric 4.17, JUnit 4 + Truth).
- Build env as U2. The worktree is `.claude/worktrees/u3c` (branch `u3c-reading`, off main `3fdbc3f`), with `local.properties` and submodules already set up.
- The Pixel is on Wi-Fi as `adb-21121FDF6007JN-gtbanj._adb-tls-connect._tcp`.
- **One app on the phone:** install over `app.granger` only. The fresh-install walkthrough (Task 7) uninstalls and reinstalls it, with Neal's OK at the time.

**Roadmap:** the U3 row in `docs/superpowers/plans/2026-10-15-granger-v1-roadmap.md`, after U2 (main `3fdbc3f`). F1 follows.

## What the code looks like now (surveyed 2026-10-28, main `3fdbc3f`)

Paths are under `app/src/main/kotlin/app/granger/`.

**The rail and the Library**
- `shell/Rail.kt`:
  - `RailTool` (L67) has HOME, SEARCH, RESEARCH ("Ask"), LIBRARY, LISTEN and SETTINGS. `drawable()` is at L101.
  - `NarrowRail` (56 dp, L152) and `ExpandedRail` (L202) draw Settings at the foot.
  - The open items are `RailFour` (`shell/RailOrder.kt`), at most 4.
- `GrangerNavHost.kt`:
  - `railToolOf` (L305) lights LIBRARY for the Library, `CollectionRoute`, Downloads, Get more, Readiness, `SourceRoute` and `ArchiveRoute(TEXTS)`. LISTEN covers the audio routes.
  - `ReaderRoute` and `BookRoute` light nothing.
  - `onTool` is at L640, and `ListenRoute` (L320, composable L1019) is the model for a new tool.
- `library/LibraryViewModel.kt`:
  - `libraryKinds()` (L52) gives "Encyclopedias & reference" (`REFERENCE_KIND`: the Kiwix folder or collections holding packs), "Books", "maps" (`FOLLOWING`), then any other type. `listenKinds()` (L72) is U2's split for audio.
  - `library/LibraryScreen.kt` `kindRows` (L192) draws the rows for both the Library and Listen.
- **Packs:**
  - Kiwix is one folder row that opens `CollectionScreen`; a pack opens through `library/OpenPack.kt` `openPack()` into `ReaderRoute`.
  - **Maps are Kiwix ZIMs** (family "maps") under the built-in "maps" type ("OpenStreetMap", `core/library/.../LibraryDatabase.kt` L131). They open in the wiki reader. There is no separate map viewer.

**The readers**
- **Wiki** (`reader/ReaderScreen.kt`):
  - `ReaderTopBar` (L365): Back, main page (`ic_menu_book`), `PackSearchBox` (the longest label that fits), `ListenButtons`, star, ⋯ (Random article, Find in page, plus the bookmark while listening).
  - There's no page title, Contents or text size.
  - `FindBar` (L536) uses WebView find. `ReaderTopBarFitTest` checks the bar at 360 dp.
- **E-book and PDF**, one screen (`ebook/EbookReaderScreen.kt`, `ebook/ReaderControls.kt`):
  - `EbookTopBar` (L45) has Back, the title, Contents and Search in book (not for PDF).
  - `EbookBottomBar` (L65) has the progress label and slider, then Contents (again), Aa, `ListenButtons` (EPUB only) and the star. There's no overflow.
  - The panels are `Panel { CONTENTS, SETTINGS, SEARCH }`. PDF uses `PdfSettingsSheet`.
- **Chrome:**
  - `chromeVisible` lives in `ReaderViewModel` (L82) and `EbookReaderViewModel` (L61). A middle tap toggles the bars, the rail (`shell.railVisible`), the mini player and the system bars (`SystemBarsHidden`).
  - **Everything is padding, not overlay.** The rail is in `GrangerShell`'s Row (L101), and the mini player pushes the content down (`miniPlayerPlacement()` gives `HIDDEN` or `PUSH`). So each toggle reflows the page (`readerPageInsets()` L622).
  - Nothing is scroll-linked.
  - The wiki's JS bridge already reports `onUserScroll` (`reader/ReaderPageScript.kt` L35), used only to stop the voice following. The EPUB is paginated (Readium `DirectionalNavigationAdapter`, `MiddleTapListener`).
  - P1 Task 7 (full screen) is on hold, replaced by D3.

**Covers**
- `GrangerExtras.coverTile`, `coverText` and `coverRule` (`theme/GrangerTheme.kt` L124) are used only by `library/BookCover.kt` `TitleTile`: the title in serif over a lantern rule.
- Missing art elsewhere is a grey tile with an icon or a letter:
  - `music/MusicParts.kt` `AlbumCover` (L190);
  - `audiobooks/AudiobookShelfScreen.kt` `AudiobookCover` (L49);
  - `player/PlayerParts.kt` `PlayerCover` (L72; the mini player, Listen's card, the players, the queue);
  - the rail's `LetterTile` / `IconTile` (Rail.kt L342, L383);
  - Home's `TabCard` letter tile (home/HomeScreen.kt L213).

**Carried forward**
- **Back from a reader opened from the rail:** `showTab` (NavHost L216) replaces a reader already on top, so Back skips the reader you were in and lands on whatever was under the first one.
- **System-bar swipes** don't bring the chrome back.
- **Ask's answers** style only `*italic*` and `**bold**` (`research/AnswerText.kt` L21). Headings and lists show as typed.

## Decisions

1. **Browse, a rail tool for packs.**
   - **Where:** `RailTool.BROWSE("Browse")`, between Ask and Library: Home, Search, Ask, Browse, Library, Listen, then Settings at the foot.
   - **Icon:** a globe (Material `public`). The fallback is a compass (`explore`). Render it beside the others in `ThemeShots` before it's wired, as Listen's was.
   - **Browse's home** (`BrowseRoute`, `browse/BrowseScreen.kt` + `BrowseViewModel`):
     - **Encyclopedias & reference:** the packs themselves as rows (the pack's illustration, title, size and the U3b Ask line), not one Kiwix folder. A tap opens the pack's main page (`openPack`).
     - **Maps:** the map packs, the same way.
     - Each section shows only when it holds something.
     - Header: Get more (opens Get more on Kiwix) and ⋯ (Downloads, Readiness).
   - **Empty state:** "Nothing to browse yet", with Pick a start's suggestion (the region's encyclopedia and map) and **Get packs** (Kiwix in Get more).
   - **What lights Browse:**
     - `BrowseRoute`;
     - `ReaderRoute` (reading a pack);
     - `SourceRoute(KIWIX)`;
     - a `CollectionRoute` that holds packs.

     `BookRoute` lights **Library** while you read.
2. **The Library is books.**
   - It holds Granger Classics, My books, Archive.org texts, imported PDFs and EPUBs, user collections of books, and any other non-audio, non-pack type (video).
   - `libraryKinds` drops the reference and maps kinds, and `browseKinds` takes them, as U2 split `listenKinds`.
   - The header keeps Search, Downloads, Get more and ⋯ (Readiness, Import).
   - Readiness and Downloads keep lighting Library. They cover everything, and the Library is where you manage what you hold.
3. **Open items shrink on short rails.**
   - Seven tools always show. The open items take what's left, from 4 down to 0, with History always reachable.
   - `RailFour` keeps four, and the narrow rail shows the first *n* that fit its measured height (each tile is 56 dp). The expanded rail lists all four.
   - Check at the Pixel 6 height with the keyboard down and with the keyboard up (U1 A4), and at a 640 dp-tall profile.
4. **One reader bar** for wiki, e-book and PDF, in two parts:
   - **Top:** Back, the title (one line, ellipsis), `ListenButtons`, ⋯.
     - The wiki's title is the page title; its pack's main page and Random article move into ⋯.
     - The wiki's pack search moves from a box to the bottom bar's Search, which opens the existing `PackSearchPanel`.
   - **Bottom:** Contents, Search, Aa, Bookmark, with the book's progress label and slider above them for e-books and PDFs.
     - **Contents:** for the wiki, the article's sections, from U2's `ArticleReadSource` headings, jumping to the anchor. For books, today's panel.
     - **Search:** pack search for the wiki, search in book for EPUB, hidden for PDF. **Find in page** joins ⋯ for the wiki.
     - **Aa:** for the wiki, new: text size (WebView `textZoom`, 80–160 %, saved in `ebook/ReadingPrefs.kt`'s `ReadingPrefsStore`) and the reader theme. E-book and PDF keep their sheets.
     - **Bookmark:** the star, the same in all three.
   - **Listen** stays in the top bar. While reading aloud, Stop sits beside Pause, and nothing needs to move (the U2 star swap goes).
   - **Shared code:** a new `reader/ReaderBars.kt` (`ReaderTopBar`, `ReaderBottomBar`, `ReaderBarActions`), used by both screens. `ReaderTopBarFitTest` keeps 360 dp honest.
5. **Chrome over the page, sliding away (D3).**
   - **In a reader:** the rail and the mini player are overlays, not part of the layout. The page always has the full width, and the reader's bars overlay it.
   - The chrome (top bar, bottom bar, rail, mini player) **slides away** on reading and **comes back** on a middle tap, a system-bar swipe, or the edge swipe.
     - **Wiki:** it hides on a scroll down of more than 24 dp and shows on a scroll up of more than 48 dp. The bridge reports direction and distance.
     - **E-book and PDF:** it hides on a page turn and shows on a tap.
   - **No reflow.** The page keeps its size. The wiki page gets a top and bottom CSS padding equal to the bars, so the first and last lines aren't under them at rest. Readium's margins already cover the e-book.
   - **The system bars** hide and show with the chrome, as now.
   - The chrome comes back when you leave the screen (`ChromeBackOnReturn`).
   - **Outside the readers** the shell is unchanged: the rail and mini player stay in the layout.
6. **Themed cover tiles (W8).**
   - One `MediaTile` (`theme/MediaTile.kt`) for missing art, in `GrangerExtras.coverTile`. It shows the title's first word or initial in serif over the lantern rule (the `TitleTile` look), with a small kind glyph in the corner: note, headphones, page or speech.
   - It replaces the grey placeholders in `AlbumCover`, `AudiobookCover`, `PlayerCover`, the rail's `LetterTile`, Home's `TabCard` letter tile and the search rows. Real art is still shown when it exists.
7. **Back goes back.** Opening an item from the rail, Home or Search while you're in a reader keeps that reader underneath (`showTab(keepCurrent = true)`), so Back returns to it. The rail's tools still clear to their own landing.
8. **Ask's answers read cleanly.** `AnswerText` renders headings (as bold lines), bullet and numbered lists (with hanging indents) and `inline code`, as well as italic and bold. Links stay as the citations already do.

## Global Constraints

- `./gradlew testDebugUnitTest :core:research:test` stays green after every task, and the count only grows. The baseline is about 2277; record it in Task 1.
- No schema migration: the wiki text size joins `ReadingPrefsStore` (SharedPreferences). No catalog change.
- Commit after every task with the message given, ending with the Co-Authored-By line.
- One app on the phone.

## Review Focus

- Does Browse hold every pack and map, and the Library only books? Does reading a pack light Browse, and reading a book Library?
- At 360 dp: do both bars fit, with Listen and Stop together?
- **Wiki:** does the chrome slide away on a scroll down and back on a scroll up or a tap, without the text jumping? Is the first line visible at rest?
- **E-book:** does a page turn hide the chrome, and a tap bring it back, without reflow?
- On a short rail, do the open items drop before anything else?
- Does every missing cover use the themed tile?

---

## Part 1: Browse

### Task 1: Browse, the packs' own tool

- [ ] Record the baseline (`./gradlew testDebugUnitTest :core:research:test`), with the count in the commit message.
- [ ] The icon (Decision 1): add `ic_browse` (Material `public`) and the candidate `ic_browse_alt` (`explore`). Render both in a `ThemeShots` comparison beside the other tools, light and dark, to the scratchpad. Wire `ic_browse`; Neal may switch.
- [ ] `RailTool.BROWSE`, `BrowseRoute`, `onTool` (pop back to Browse's home if it's in the stack), and `railToolOf` (Decision 1's list; `BookRoute` → LIBRARY).
- [ ] `BrowseViewModel` / `BrowseScreen`:
  - the reference and maps sections as pack rows (illustration, title, size, Ask line), each opening the pack;
  - the empty state;
  - the header.
- [ ] The Library loses the reference and maps kinds (Decision 2). Its empty state reads "No books yet", with Granger Classics and Archive.org texts.
- [ ] Tests:
  - `BrowseViewModelTest` (sections only with packs, maps under Maps, the empty state);
  - `LibraryViewModelTest` (no reference or maps);
  - `RailTest` (the order, the label, the icon);
  - `BrowseRoutesTest` (what lights Browse and what lights Library).
- [ ] **Commit.** `Browse: packs and maps get their own tool; the Library is books`

### Task 2: Open items shrink on short rails

- [ ] `NarrowRail` measures the height left between the tools and Settings, and shows the first *n* of `RailFour` that fit, plus History when there's room for one tile.
- [ ] Tests: `RailTest` at 640 dp, at Pixel 6 height and with the keyboard up (a short height). The tools and Settings always show, and items drop from the end.
- [ ] **Commit.** `The rail keeps every tool on short phones; open items make room`

## Part 2: reading

### Task 3: Themed cover tiles

- [ ] `MediaTile` (Decision 6), with a size-aware layout. Small sizes (the rail, the mini player) show the initial and the glyph; large ones show the first words and the rule.
- [ ] Swap it in at every placeholder in Decision 6's list.
- [ ] Tests:
  - `MediaTileTest` (initial vs words by size; the glyph per kind);
  - the existing cover tests updated;
  - `ThemeShots` of an album grid with no art, light and dark.
- [ ] **Commit.** `Missing art gets a themed tile everywhere`

### Task 4: One reader bar

- [ ] `reader/ReaderBars.kt` (Decision 4): `ReaderTopBar` and `ReaderBottomBar`, taking `ReaderBarActions` (nulls hide a button).
- [ ] **Wiki:**
  - the page title in the bar;
  - main page, Random and Find in page in ⋯;
  - Search opens `PackSearchPanel`;
  - **Contents** lists the sections (from `ArticleReadSource`'s headings) and jumps to the anchor;
  - **Aa** opens a sheet with text size (`textZoom`, saved) and the reader theme.
- [ ] **E-book and PDF:** the same bars. Contents appears once (bottom), Search in book is in the bottom bar, and the top bar has ⋯ (Book details, Remove download where it exists today; otherwise ⋯ is hidden).
- [ ] Tests:
  - `ReaderTopBarTest`, `ReaderTopBarFitTest` (360 dp, idle and reading) and `EbookBottomBarTest`, rewritten for the shared bars;
  - `WikiContentsTest` (sections, jump);
  - `WikiTextSizeTest` (the zoom is applied and saved).
- [ ] **Commit.** `One reader bar for wiki, e-book and PDF: contents, search, Aa and bookmark below`

### Task 5: Chrome over the page, sliding away

- [ ] Shell: in a reader (`ReaderRoute`, `BookRoute`), `GrangerShell` draws the rail and the mini player as overlays (`miniPlayerPlacement()` gains `OVERLAY`). Elsewhere they're unchanged.
- [ ] **Wiki:**
  - the bridge reports scroll deltas;
  - `ReaderViewModel` hides the chrome past 24 dp down and shows it past 48 dp up;
  - the bars are overlaid with a slide animation;
  - the page gets CSS top and bottom padding for the bars, set once, not per toggle.
- [ ] **E-book and PDF:** a page turn (a locator change not caused by a tap on the chrome) hides the chrome. A middle tap toggles it. The bars overlay the navigator.
- [ ] A system-bar swipe (`WindowInsets` visibility change while hidden) shows the chrome.
- [ ] Tests:
  - `ReaderScreenChromeTest` (scroll hides, scroll up and tap show, no size change of the page node);
  - `EbookChromeTest` (a page turn hides, a tap shows);
  - `GrangerShellSideTest` / `ShellMiniPlayerTest` (overlay in readers, push elsewhere);
  - `ChromeRequestTest`.
- [ ] Profile one wiki scroll and one page turn on the phone (frame time). Compare with U3b's numbers (0.17% janky in the Granger source).
- [ ] **Commit.** `Reading chrome slides away over the page and comes back on a tap`

### Task 6: Back goes back; Ask's answers read cleanly

- [ ] `showTab(keepCurrent = true)` when a reader is on top and the item comes from the rail, Home or Search (Decision 7). Test it in `ReaderBackTest`.
- [ ] `AnswerText` (Decision 8): headings, lists and code. `AnswerTextTest` (each form; the citations untouched; `spoken()` unchanged).
- [ ] **Commit.** `Back returns to the reader you were in; Ask's answers render headings and lists`

### Task 7: Walkthrough (Neal, Pixel 6), then a fresh install

- [ ] Install over `app.granger`. Check:
  - **Browse:** the packs and maps there, and gone from the Library; the icon; reading a pack lights Browse, reading a book lights Library.
  - **The rail** with the keyboard up.
  - **The bars** in an article, an EPUB and a PDF: Contents, Search, Aa, Bookmark, Listen with Pause and Stop.
  - **Chrome:** scroll an article and turn book pages. Does it slide away and come back without the text jumping?
  - **Covers:** an album with no art, the mini player, a rail tile.
  - **Back** from a book opened from the rail while in an article.
  - **An Ask answer** with a list.
- [ ] **The U3 closing walkthrough** (with Neal's OK to uninstall): a fresh install of `app.granger`, onboarding to a first article, a book, an answer and a song. Then a short pass on one other phone (or a 360 dp emulator profile if none is to hand). Note anything a friend would trip on.
- [ ] Record the result in the roadmap's U3 row and a "Carried forward from U3" section. Merge `u3c-reading` into main and push if Neal passes it.
- [ ] **Commit.** `Roadmap: U3 done`
