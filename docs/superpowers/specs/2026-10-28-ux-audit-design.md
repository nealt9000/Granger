# The UX pass (U1): audit and spec

2026-10-28 · Neal Thompson (decisions) · walked and written up by Claude

Status: **approved**: D1–D6 decided (Neal, 2026-10-28, all as recommended; §7). U1 in the roadmap. It specs U2 (Listen, §6) and sets the work list for U3 (the overhaul, §5). Raw notes: `docs/superpowers/u1-notes.md`. Screenshots: `docs/superpowers/u1-shots/library/` (Neal's install, 35 GB, Field) and `u1-shots/fresh/` (Granger Fresh, empty, Night).

## 1. Why, and how it was walked

Features landed one plan at a time, and each plan put its entry point where it fit best that week. The result works, but a friend opening it cold meets three lists in the rail, two Homes, Sources inside the Library, music settings ahead of reading ones and a Research tool that can't answer. Friends should see an app about one thing: **own the world's knowledge, read it, ask it**.

Walked 2026-10-28 on the Pixel 6 at main `6dde87c` (T1 merged):
- **Library install** (`app.granger`): every rail tool, a Search, a Research question, Sources → Kiwix, Readiness, Player, Settings and Voice, the rail drawer, the wiki reader and the e-book reader.
- **Fresh install** (`app.granger.fresh`, built with `./gradlew installDebug -Pfresh`, added on this branch): onboarding end to end (a trimmed 318 MB start so the phone's 18 GB stays free), then Home, Research and Library on the new library.

Not walked: landscape, the widget, Android Auto, import, archive.org browse, the PDF reader, maps. U3's closing walkthrough covers those.

## 2. What works

Worth keeping as U3 changes things around them:
- **The Welcome screen** (owl, Spectral wordmark, tagline, one-line pitch). The brand reads at once.
- **Home after onboarding.** Within 30 s of Start: the status line, the next suggestion, a progress bar and three feeds with real lead images. It is the best first impression the app makes.
- **A Research answer.** "what causes the tides?" gave a correct, cited answer in about 20 s.
- **Readiness.** The nine domains, the deficiency lines with See, Storage by source. It answers "how ready am I?" plainly.
- **The e-book reader.** Spectral on paper, chapter and progress at the foot.
- **The Player** on its own: clean and complete.

## 3. Findings, ranked against the mission

Rank **A** stops a friend from owning, reading or asking in their first hour. **B** makes them hunt or doubt. **C** is polish. Shot names are in brackets.

### A. Stops the mission

| # | Finding | Where |
|---|---|---|
| A1 | **Research can't answer after onboarding.** Onboarding says "this phone can run it" and then never offers the model. Research opens on "Research needs the Gemma 4 E2B model. Get it in Settings → Models." | fresh 02, 13 |
| A2 | **First run shows "Refresh the catalog to see the choices."** before Pick a start. The catalog isn't fetched when onboarding opens. (Logged in H3 as unchecked; now seen on the phone.) | fresh 03 |
| A3 | **Search results are mostly noise.** Article snippets are page chrome (category and authority-control footers, IEP's menu, Stack Exchange profile stats) with no match shown. User pages rank high ("User apocalypse", "User kviiri"). "215 Cassiopeia solar apocalypse computer" sits above Wikipedia's "Apocalypse". The Titles and article blocks run together with no heading. Book hits repeat title and author per hit (The Idiot ×3). | library 04–05 |
| A4 | **The rail's recents can't be read.** 12 tiles under the tools: letters (U, H, H, M, C), four identical speech bubbles for chats, a speaker, a note. Each item you open jumps to the top, so the rail reorders under your thumb. With the keyboard up, the rail is cut off mid-tile. | library 01, 22 |
| A5 | **Tapping a recent in the narrow rail opens the drawer, not the item.** By design since P1 (`Rail.kt` `EntryTile`, locked in by `RailTest`), and first logged here as a bug, but nothing on the tile says so: it looks like a button for the item. D1 resolves it (open items open directly). | library 21 |
| A6 | **Installed packs can't be opened from where they're managed.** Sources → Kiwix → Installed gives each pack only **Remove**. To read it you go back out to Library → Media → Kiwix. | library 14 |

### B. Makes you hunt or doubt

| # | Finding | Where |
|---|---|---|
| B1 | **The Library does two jobs.** Media (what you hold) and Archive sources (where to get more) share one list. Kiwix (Wikipedia, Stack Exchange, iFixit) is filed under "Literature". Archive.org appears three times under sources, and Audiobooks get four rows for three books. | library 10–11 |
| B2 | **No downloads in one place** (H4 carried forward). Fresh Home shows a setup bar, but after setup a download is visible only on its own source row. | — |
| B3 | **New users are shown audiobooks and music first.** A fresh Library's "No music or audiobooks yet" block (LibriVox, Archive.org audiobooks, Archive.org music, Settings) is taller than the books above it. | fresh 14 |
| B4 | **Settings is one flat list** of roughly 30 controls. Music and audiobook options fill about half of it. Read and ask get two rows (Models, Voice). Readiness is both a Settings row and a Home strip. | library 18–19 |
| B5 | **Bug: Settings → Voice reads "No offline voice on this phone"** with three Granger voices installed and Readiness counting Voices at 67 MB. The summary in `GrangerNavHost.kt:902` reads `installedVoices.voices`, which is likely empty until something refreshes it. The voice list continues with raw system ids ("iob", "iog", "msm00013"). | library 18, 20 |
| B6 | **Reading chrome is never out of the way.** The mini player (~250 px, even when paused), the rail (~12% of the width) and one or two toolbars stay up while reading. The full-screen overlay is still on hold from P1. | library 22, 24 |
| B7 | **Read-aloud has four faces.** The wiki reader puts it in the overflow menu as Read aloud, the e-book reader has a headphones button, Research's answers also use headphones, and the rail's item uses a speaker. The headphones button stops rather than pauses (V1 carried forward). | library 09, 22–24 |
| B8 | **Two Homes.** The wiki reader's pack-home button uses the same house icon as the rail's Home tool. One recent is a pack page titled "Home". | library 21–22 |
| B9 | **Onboarding's Your situation is two screens in one.** It opens scrolled past its search field. The ~200-country list starts at Afghanistan with no guess from the phone's locale, and "What you'll use it for" sits under the whole list. | fresh 07–08 |
| B10 | **Research's landing doesn't say what it is.** "Recent chats" plus an input, with no line on what it reads, how long it takes, or that it can be wrong. The R3 issues stand: + doesn't start a clean chat, and Back leaves Research. | library 06 |
| B11 | **Home's Continue holds index pages** ("Users - Biblical Hermeneutics Stack Exchange", "Highest Voted Questions") and uses letter tiles. "Play again" is the subtitle on an article. | library 01 |

### C. Polish

- Titles keep site suffixes ("| Based Cooking", "- Parenting Stack Exchange"). Raw ZIM names show as pack names ("based.cooking LukeSmith"). (Home, Pick a start, Search)
- Feed headings change once drawn ("On this day · Best of Wikipedia" → "October 28"). On this day fed from Knots has no date link. (H2 carried forward)
- One lead image renders solid black (Catalan declaration of independence).
- Memory is "about 7 GB" in onboarding and "8.0 GB" in Readiness. Kiwix is 33 packs in Library and 36 items in Readiness. Setup is 318 MB at Review and 244 MB on Home.
- Review keeps the heading "Efficient start" after the picks are trimmed. Start gives no feedback for ~4 s.
- Onboarding's This phone says Research is "coming in a later release" beside a working Research tool (F1 settles the Preview line).
- The onboarding rail shows every tool and a lone History icon before anything is installed.
- The readiness dots have no labels. "Basic · 79 MB of 4.6 GB" calls 2% Basic without saying what the "of" counts.
- Library header icons have no labels (search, history, check-circle).
- The Enter key put a newline in Research's box over adb. Check with the real Send key.

From the roadmap's carried-forward lists, these fold into U3 with the findings above: Search grouping (Plan 5/6); edge-swipe reach and system-bar swipes (Plan 5); Back from a rail-opened book (Plan 6); Research + and Back (R3); full-screen reflow (M3/P1); the Player's inactive-tab controls and the mini player under the voice (M1/V2); hundreds of files in one source (H4); "Not in your library" for medical declines (Plan 9); "The model needs repair" for every failure (Plan 8); Markdown shown raw (Plan 8/9); "Loading the model…" on most questions (R3).

## 4. The navigation map

### Today

```
Rail:   Home · Search · Research · Library · Player · [12 recents] · Settings
Home:   status strip → Readiness · Continue · feeds
Library: Media (Literature, Audiobooks, Music, Maps) + Archive sources (per media type)
          header: search · history · readiness · overflow
Sources: per source (Kiwix: Browse / Installed / Updates; Remove only)
Readiness: from Home strip, Library header, Settings
Settings: flat (Models, Voice, Readiness, Set up again, Appearance, Storage, Music & audiobooks…)
Readers: rail + mini player + toolbar(s), always
```

### Proposed

```
Rail:   Home · Search · Ask · Library · Listen · ─ · [up to 4 open items, labelled] · Settings
Home:   status strip (tap → Readiness) · Continue (things you read or played, not index pages) · feeds
Ask:    (was Research) Preview tag, one line on what it does; chat list ↔ chat with working + and Back
Library: what you hold, by kind — Encyclopedias & references (Kiwix), Books, Maps, + Audiobooks/Music once any exist
          header: Get more · Downloads (badge while active) · Readiness
Get more: the sources (Kiwix, Granger Classics, Archive.org; LibriVox under Archive.org/Audiobooks), each pack row Open / Remove
Downloads: active, queued, failed, done today — one screen, from Library and the notification
Settings: Library & storage · Reading & listening (Voice, Music & audiobooks →) · Ask (Models) · Appearance · About & feedback
Readers: one toolbar pattern; chrome (rail, mini player, bars) slides away while reading, a tap brings it back
```

What moves:
- **Research → Ask** in the rail. It names the job, pairs with Search, and makes room for "Preview". (Decision D5.)
- **Player → Listen**, with Reading + Music, and Audiobooks only once one exists (U2, §6).
- **Sources leave the Library** for **Get more**, opened from the Library header, Readiness's See actions and Home's suggestion. Installed rows gain Open.
- **Downloads** gets one screen (B2), opened from the Library header with a badge and from the download notification.
- **Readiness** keeps two doors, the Home strip and a Library header button, and leaves Settings.
- **The rail's recents** shrink to what's open now (D1).
- **Settings** groups into five sections. Music & audiobooks becomes one row to a sub-screen.

## 5. U3: the overhaul, in order

Sized to land as three plans so each can ship to the phone and be walked:

**U3a: first hour (A1, A2, B3, B9, B10, onboarding polish, the B5 bug)** — plan `2026-10-28-plan-u3a-first-hour.md`
- Onboarding fetches the catalog when Welcome opens and shows a spinner on Pick a start if it hasn't arrived.
- On ASK-tier phones, Pick a start adds "Ask (Preview): the librarian, 2.x GB" ticked by default, with a "can be wrong" line (D4). This phone's line changes to match. Research's empty state gets one Get button that starts the download in place.
- Your situation splits into Region (preselected from the locale, larger areas first) and Uses. Review's heading follows the picks. Start shows progress at once.
- Fix the voice summary and the memory figure.

**U3b: find and hold (A3, A4, A5, A6, B1, B2, B4, B8, B11, C items on titles and counts)**
- Search: one heading per kind (Titles, Articles, Books, Music), books grouped by book ("The Idiot · 3 passages"), snippets from the matched sentence with chrome stripped, Stack Exchange user, tag and index pages filtered out of Search, Continue and Home, and site suffixes trimmed from titles everywhere. Keyboard drops on submit.
- The navigation map in §4: Library by kind, Get more, Downloads, Readiness doors, Settings sections, the rail (D1).

**U3c: reading (B6, B7, B8, reader carried-forward)**
- One reader toolbar for wiki, e-book and PDF: Back, title, Contents, Find, Aa, Listen, Bookmark, overflow. Listen is one icon everywhere, with pause and stop.
- Chrome that slides away on scroll and comes back on a tap, without reflowing the page (D3).
- Pack home gets a book icon or the pack's name, not a house.

U3 ends with a fresh-install walkthrough a friend could follow without Neal (the roadmap's bar), on the Pixel 6 and one other phone.

## 6. U2: Listen

From the roadmap row (Neal, 2026-10-27), shaped by what the walk showed:

- **The tool.** The Player becomes **Listen**, with tabs **Reading** and **Music**. **Audiobooks** is a third tab only once an audiobook is on the phone. The mini player shows whichever is playing.
- **Reading** is read-aloud of the open book or article as a full player: the current sentence large, chapters from the book's contents (or the article's sections), speed, voice, sleep timer, a saved place per text, and the music-under mix level. The rail's speaker recent goes away, because Reading is where that lives.
- **One Listen button** in every reader and on every Ask answer starts Reading at the first visible sentence. Pause and stop are separate (B7). The answer reads in place but shows in Reading too.
- **Audiobooks go passive.** Their tab, shelf, Home feed scope and default, library type, onboarding mentions and the fresh Library's promo block appear only when an audiobook exists. LibriVox stays as a source under Get more → Archive.org / Audiobooks.
- **Carry over** the audiobook player's chapters, bookmarks, sleep timer and speed to Reading, and fix the M1 inactive-tab controls while the tabs are rebuilt.
- **Voice.** Reading picks the best installed Granger voice. When V4's British voices land they join the list and Reading becomes the main way to listen. Settings → Voice drops the raw system ids behind a "System voices" fold.
- **Order with U3.** U2 changes the rail and the readers' toolbars, so it lands after U3a and with or just before U3c.

## 7. Decisions for Neal

| # | Decision | Recommendation · status |
|---|---|---|
| D1 | The rail's recents | Keep up to 4 **open** items (the book, article, chat or album you're in now), always labelled in the drawer, never reordering on open. History moves to Home's Continue and a History screen. **Decided (Neal, 2026-10-28).** |
| D2 | Where sources and downloads live | **Get more** and **Downloads** as screens off the Library header, with a downloads badge on the Library tool while something is in flight. Not rail tools. **Decided (Neal, 2026-10-28).** |
| D3 | Reading chrome | Slide away on scroll and back on tap, as an overlay with no reflow. Replaces P1's held full-screen task. **Decided (Neal, 2026-10-28).** |
| D4 | The model in onboarding | Ticked by default on ASK-tier phones as Ask (Preview). F1's release flag still decides whether friends see it. **Decided (Neal, 2026-10-28).** |
| D5 | The tool's name | "Ask" in the rail and on its screen. "Research" stays in code. **Decided (Neal, 2026-10-28).** |
| D6 | Search grouping (carried forward since Plan 5) | By kind (Titles, Articles, Books, Music), not by collection, with the source on each row. **Decided (Neal, 2026-10-28).** |

## 8. Neal's notes from the U3a walkthrough (2026-10-28), triaged

| # | Note | Where it goes |
|---|---|---|
| W1 | What's downloading or queued isn't visible once you leave onboarding; the notification shows only the current file. Tapping Home's "Setting up" bar opens Kiwix Browse. | U3b Downloads (D2). The setup bar and the notification open it. |
| W2 | Offline wikis without pictures are a much poorer read. Ask gains nothing from pictures, but readers do. No-pictures packs should be the space-saving fallback, not the default. | **Decision** on preset sizes (catalog-gen presets, then re-sign the catalog). |
| W3 | Two Home buttons in the pack reader (the pack's and the app's). Make the app's Home the owl. | U3b, with B8. |
| W4 | The Library icon reads as messages. | U3b nav map. |
| W5 | Archive sources still sit below the Library's contents. | U3b Get more (D2), as planned. |
| W6 | **Bug:** an archive.org graphic novel (V for Vendetta) downloaded as an EPUB with no comic images, not the scans the listing shows. | Investigate: format choice for comics (CBZ/CBR or the scan PDF over the derived EPUB). |
| W7 | Nothing says whether a pack will be searched by meaning. Checked: 33 of 926 catalog packs have vectors; Efficient leaves out 3 of its 12 (Water Treatment Library, Food for Preppers, Computer by Wikipedia), and Comprehensive 63 of 77. Packs without vectors are still keyword-searched, unless they have no full-text index. | U3b: an Ask line per pack row (meaning + keywords / keywords / not searchable). A vectors build for the Comprehensive packs is its own job (R-track). |
| W8 | Media with no artwork breaks the theme. | U3c: themed cover tiles for media (`GrangerExtras.coverTile`). |
| W9 | Granger Classics scrolls slowly; categories load too many books. The preset counts confuse: all 347, starter 351, efficient 359, comprehensive 424, because each counts every Classics book plus the packs and models. "Items are here or on their way" during setup. | U3b (paging, from H4 carried forward); presets counted in packs + "347 books". |
| W10 | No playing indicator on the album's track list or in the queue. Play on an album should start it and turn into Stop. | U2 (Listen rebuilds the player). |
| W11 | Granger Classics covers still carry the old brand: `tools/pack-builder/covers.py` bakes `#0E0E0E` / `#E8601C` into each EPUB. | Catalog job: re-render the covers in the field kit and re-release the 347 books (new hashes; installed copies re-download ~95 MB). |
| W12 | On a fresh install every system voice is listed (long), and no Granger voice was offered or made the default. | U3a addition (proposed): a voice line in Pick a start, default to the Granger voice once installed. System voices fold away in U2. |
| W13 | Several voice entries in the rail while reading an e-book. | U2 (Reading moves into Listen) and D1. |
| W14 | No way to clear all recents or chats. | U3b with D1 (History screen: Clear all; Ask landing: Clear chats). |
