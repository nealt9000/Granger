# Media: music and audiobooks — design

2026-10-19 · Neal Thompson (decisions) · written up by Claude

Status: approved by Neal 2026-10-19. This is the first of four projects. It replaces the roadmap row "After v1: Media" and is built before Plans 10 (Monetization) and 11 (Beta).

## 1. Why

Granger's mission is to keep low-friction access to knowledge when the internet is out: look something up, open a book, an album or an article right away. Media is about moving through and consuming your local library without friction. Music and audiobooks join books and articles as first-class parts of the library.

This project is the base for where Granger is headed: a library you can browse, search, listen to, have read aloud, and ask Research about, all as one experience. Neal's target examples, for later projects:
- "Build me a reading plan on the progression of civil rights across world history, with a playlist to read along to." The result is an ordered list of books and articles, each opening at a highlighted passage, plus a playlist.
- "Read the book of Mark." Research opens the e-reader at Mark and starts reading it aloud.
- "What most influenced John Williams' Star Wars score?" The answer links articles and offers a button to play Holst's *The Planets* from your library.

### The four projects, in order

| # | Project | What it adds |
|---|---|---|
| 1 | **Media (this document)** | Music and audiobook players, mini-player, phone media, LibriVox and archive.org audio, media in Search and Research retrieval |
| 2 | Research deep links + knowing your library | Results open at the exact passage, track or section. Grounding quality (Plan 9 ended at 37% grounded). How Research becomes expert on any user's library |
| 3 | The voice | One on-device speech engine for read-aloud in both readers and for Research answers; music/voice mix |
| 4 | Actions | Research builds reading plans and playlists, and opens, reads aloud or plays things |

Neal set the order of 1, 2 and 4. Putting the voice third was Claude's call: "read me Mark" needs it before project 4, and nothing before that does.

## 2. Goals, non-goals, anti-goals

**Goals**
- A music player that matches **Auxio** and an audiobook player that matches **Voice** (feature checklists in §6 and §7).
- One player shell: the Player rail button, a full-screen player and the top mini-player, all reserved in the Plan 5 layout and now switched on.
- Music and audiobooks already on the phone show in the Library and are **referenced in place, never moved, copied or renamed**.
- New sources: **LibriVox** (audiobooks) and **archive.org audio** (audiobooks and music).
- Media is in the same search as everything else. "Holst" finds *The Planets*. Research can retrieve and cite media.
- Granger's own downloaded media can be shown to other apps through the Files app and file pickers when the user turns that on.

**Non-goals (this project)**
- Video, and the PDF pass.
- Read-aloud and the music/voice mix (project 3).
- Play buttons inside Research answers, and opening a citation at an exact track position (project 2).
- Research building playlists or plans (project 4).
- The navigation/UX polish list for Library, Search and the web reader. It's tracked separately and done after Media lands.
- Library compression (future; see §10).
- Any Free/Pro gating. Neal is rethinking Pro (§11), and all of Media is free.

**Anti-goals: failures even if it technically works**
- **Losing your place in an audiobook**: after a crash, a reboot, the OS killing the app, switching to music and back, or the file moving between scans.
- **Unintended gaps between songs.** Albums meant to flow (classical movements, live albums, concept albums) must play gaplessly.
- **A mini-player that feels different for music and audiobooks.** One mini-player, in the same place, with the same core controls. Only the details differ.
- Granger moving, renaming, copying or deleting media it didn't download.
- Granger implying it vouches for archive.org audio, or helping get around lending restrictions (same rule as books).

## 3. Where media comes from

### 3.1 Media already on the phone
- With the user's permission (Android's audio media permission), Granger reads the phone's shared media list, the same list Auxio uses, and lists what it finds.
- The user can also **add folders** (Android's folder picker; Granger keeps the permission across reboots) for audiobooks or music kept outside the shared media list. Voice works this way.
- Files are **referenced in place**: Granger stores a pointer, never a copy. If a file disappears, its entry shows as missing, like a pack whose SD card is out. Positions and bookmarks are kept so they come back when the file returns.
- Rescans run when the app opens, when the system reports media changes, and from a button. *Builder's call: the exact triggers and how often.*
- **Music or audiobook?** A file counts as an audiobook when the system marks it as one, when it's an `.m4b`, when it sits under an `Audiobooks` folder, or when it's in a folder the user added as an audiobook folder. Everything else is music. The user can move items between the two (existing Library move).
- **What makes one audiobook:** a single file with embedded chapters, or a folder of files in order. *Builder's call: the grouping rules, following Voice's documented behaviour.*

### 3.2 LibriVox
- A new source. It searches and browses the LibriVox catalog API (`librivox.org/api/feed/audiobooks`, JSON, `extended=1` for sections and the archive.org link) by title, author and genre. *Builder's call:* fixed browse shelves like archive.org's (Plan 7), e.g. popular, recently added, and a few genres.
- **No public-domain filter.** LibriVox recordings are public domain by design.
- Downloads fetch the per-section MP3s from the book's archive.org item, verified against archive.org's sha1 as in Plan 7. Not the single zip, so a download can resume and a book can start playing once its first chapter arrives. *Builder's call: which bitrate (LibriVox offers 64 kbps and 128 kbps).*
- Kept metadata: title, authors, readers (per section where LibriVox gives them), description, language, genres, total time, cover art, LibriVox and archive.org ids.

### 3.3 Archive.org audio
- The existing archive.org source gains audio: audiobooks (spoken word) and music (78s, classical, field recordings…).
- **Rolling public-domain filter (Neal's call):** until the user accepts the waiver, audio shows only recordings published before **January 1 of (current year − 100)**. That's before 1926 in 2026, and the cutoff moves forward every January. Items with no date stay hidden. The waiver is the same one books use (Plan 7), and accepting it lifts the filter for audio too.
  - Why a separate rule from books' ≤1930: in the US a recording has its own copyright, apart from the composition, and pre-1972 recordings enter the public domain on this rolling schedule. Uploader licence tags are not trusted, the same lesson as books.
  - Consequence: before the waiver, archive.org music is mostly early recordings. Holst conducting *The Planets* (1926) clears in January 2027.
- Lending-only and private files: same rules as books ("Borrow on archive.org" label, no lending accounts).
- Downloads are verified by archive.org's sha1. *Builder's call: format preference per item.* Suggested: VBR MP3 when offered, else Ogg, else FLAC; an archive.org album item becomes an album with its files as tracks in archive.org's order.

### 3.4 Sources by media type
This folds in Neal's Plan 7 note. The Library's **Archive sources** list is grouped by media type, and a source appears under each type it serves:
- **Literature:** Granger, Kiwix, Archive.org (texts)
- **Audiobooks:** LibriVox, Archive.org (spoken word)
- **Music:** Archive.org (music)

Opening Archive.org from under a media type opens it filtered to that type. The empty Audiobooks and Music pages also link to their sources. Later sources slot in the same way. *Builder's call: the exact archive.org mediatype/collection queries for each type.*

## 4. Storage and sharing out

- Downloaded media lives **inside Granger's storage**, on the volume the user picked, like packs and books (`music/…`, `audiobooks/…` beside `packs/` and `books/`). SD cards work as they do for packs.
- Every downloaded item keeps its **origin**: source, identifier, file URL, size and checksum. That's enough for the future compression feature to fetch or stream it again.
- **Show in other apps** (Settings, **off by default**, Claude's call): Granger publishes its Music and Audiobooks folders through Android's document-provider system. They appear in the Files app and in any app's file picker. Access is **read-only** (Claude's call): other apps can open and copy files but not change or delete them. Music players that only scan the shared media list (Auxio, YouTube Music) won't list them on their own. Neal accepted that trade-off over copying files into shared storage.
- Media downloads **do not count toward the storage cap**. The cap code from Plans 3–7 stays for now; Plan 10 decides its fate (§11).

## 5. In the Library

- **Audiobooks** and **Music** media types appear (built-ins already reserved in Plan 5). Video stays hidden.
- **Filing** follows the existing rules, in the existing `Filing` table:
  - LibriVox → Audiobooks / *LibriVox*
  - archive.org spoken word → Audiobooks / *Archive.org*; archive.org music → Music / *Archive.org*
  - phone media → its type / *On this phone*
  - User moves, user collections and user media types work as today, and survive re-scans and re-downloads.
- **Inside Music**, browsing works like Auxio: songs, albums, artists, genres, playlists, folders. **Inside Audiobooks**, it works like Voice: a shelf of covers with progress, sorted by last played. *Builder's call: how collections sit alongside Auxio-style tabs.* Suggested: collections first, then the tabs inside a collection and an "All music" view across collections.
- **Rail:** the **Player** tool is shown (`Features.PLAYER` on). **Recent** lists what was played: albums, playlists, audiobooks. Tapping one resumes it.

## 6. The music player (match Auxio)

Auxio's README feature list, and what this project does with each:

| Auxio feature | In Granger |
|---|---|
| Media3 ExoPlayer playback | Yes. Media3 for both players |
| **Automatic gapless playback** | Yes, **required** (anti-goal). Includes MP3 encoder delay/padding (LAME/iTunes info) and FLAC/Ogg/Opus/M4A |
| Full ReplayGain (MP3, FLAC, OGG, Opus, MP4) | Yes, with track/album modes as in Auxio |
| Disc numbers, multiple artists, release types, exact/original dates, sort tags | Yes. Tags are read from the files themselves, not only from the system media list's fields. **Claude's call:** use TagLib through a JNI wrapper, as Auxio does (its GPL `musikr` module is a reference). This adds the repo's first NDK/CMake build. The work moves to Neal's Mac Studio (§12), so build memory is no longer the limit. Fallback if the native build proves unworkable: a pure-JVM tag reader plus Media3's metadata extraction, with any gaps against Auxio listed (§13) |
| Artists merged with album artists | Yes |
| Folder management, SD cards | Yes (§3.1) |
| Dependable playlists | Yes. User playlists, reorder, add from anywhere; they survive rescans |
| Saved playback state | Yes. Queue, position and shuffle/repeat survive restarts |
| Search | Yes, through Granger's Search (§8) |
| External equalizers (e.g. Wavelet) | Yes. Expose the audio session as Auxio does |
| Embedded cover art; square covers | Yes |
| Autoplay on headset connect | Yes, as a setting (off by default, as in Auxio) |
| Android Auto | Yes (Neal's call). A media-library browse tree over both players, tested on the Desktop Head Unit |
| Resizable widgets | Yes (Neal's call). One widget that shows whichever player is current |
| Edge-to-edge, Material design, private and offline | Already Granger's baseline |

Also standard and expected: queue view and editing, shuffle, repeat (off/all/one), lock-screen and notification controls, Bluetooth/headset buttons, audio focus (pause for calls and navigation prompts).

*Builder's call:* anything in Auxio's settings beyond the README list (e.g. sort options, "remember shuffle"), using Auxio as the reference.

## 7. The audiobook player (match Voice)

| Voice feature | In Granger |
|---|---|
| **Resume positions** | Yes, **required** (anti-goal). Position saved every few seconds while playing and at every pause, stop, seek and chapter change; written so a crash can't lose more than a few seconds |
| Chapter navigation | Yes. Embedded chapters (m4b/mp4, MP3 chapter tags), or one chapter per file for folder books and LibriVox sections |
| Sleep timer with fade-out | Yes, including "end of chapter" |
| Bookmarks | Yes, with optional notes; listed per book |
| Playback speed | Yes, per book |
| Silence skipping | Yes, per book |
| Auto-rewind after pauses | Yes. A few seconds back on resume, longer after long pauses |
| Volume boost | Yes |
| Android Auto | Yes (same service as music) |
| Controls close at hand | Big skip-back/forward (default 30 s back, 30 s forward, adjustable), the chapter list one tap away |
| Formats: M4B, MP3, M4A, OGG, OGA, OPUS | Yes |

*Builder's call:* other Voice settings and behaviours (cover replacement, marking finished), using Voice as the reference.

## 8. One player shell

- **One active player at a time.** Starting an audiobook pauses music and the other way round. Each keeps its own state: music its queue and position, each audiobook its position. Switching back resumes exactly where it was.
- **Mini-player** (Plan 5 slot, top of the content column, pushes content down): the same height, place and core layout for both: cover, title/subtitle, play-pause, a progress line, and one secondary button. That button is **next track** for music and **skip back 30 s** for audiobooks. Tapping it opens the full-screen player. It shows on every non-reading screen whenever something is loaded. In immersive reading it appears with the reader controls and the rail (Plan 5 rule).
- **Player rail button** toggles between the full-screen player and the mini-player.
- **Full-screen player:** one layout family for both players, with type-specific controls (music: queue, shuffle/repeat; audiobooks: chapters, speed, sleep timer, bookmarks).
- Notification, lock screen, widget and Android Auto all reflect whichever player is active.
- *Builder's call:* visual design, within the Neural Flame theme and the Plan 5 shell.

## 9. Search and Research

- **Search:** a new media index alongside the book index, over titles, albums, artists, album artists, composers, genres, narrators/readers, authors and descriptions (LibriVox/archive.org). Results get their own group (Music, Audiobooks). Tapping a song plays it. Tapping an album, artist or audiobook opens its page (Claude's call: a page, not autoplay, so a mis-tap doesn't start sound).
- **Research retrieval:** media becomes a passage source (a new `SourceKind`, e.g. `MEDIA`). Its text is the item's metadata plus any description. Research can then find and cite "Holst – *The Planets* (in your Music)". Tapping a media citation opens the album or audiobook page. **Play buttons inside answers and opening at a track position are project 2.** Media passages must not push book/article passages out of the answer when the question isn't about media. *Builder's call: ranking weight and a cap on media passages.* **Regression check (Claude's call):** the Plan 9 held-out set has had its two allowed scorings and is not touched again. Media changes are checked against the Plan 9 **tuning** split instead: declined and grounded may not drop more than 2 points below Plan 9's final tuning run (91% / 45%). A small new set of media questions (works present in the test library, e.g. Holst, a LibriVox title) checks that media gets cited when it should.

## 10. Built so later projects fit

- **Compression (future):** every Granger-downloaded item keeps full origin info (§4). Phone media is never compressed.
- **Deep links (project 2):** every playable thing has a stable id and can be addressed with a position: `track:<id>@<ms>`, `audiobook:<id>@<chapter>/<ms>`. Projects 2 and 4 can then open "this track at 2:31". *Builder's call: the id scheme, consistent with `book:<origin>:<ref>`.*
- **Voice / read-aloud (project 3):** the player service is built so a second audio stream (speech) can play with music later. Audio focus and the mini-player shouldn't assume only one stream exists. Nothing is built for it now.
- **Actions (project 4):** playlists can be created through a plain app-level function, not only through the UI, so Research can call it later.

## 11. Money

Neal is rethinking Pro (2026-10-19): drop the Free data cap; Pro becomes Research made expert on your own collection, plus library compression; Free Research is tuned to a default "Granger Collection". That's Plan 10's job. For this project: **everything in Media is free**, nothing is gated, and media doesn't count toward the cap.

## 12. Constraints

- GPLv3 app: Media3 (Apache-2.0) and the tag reader must be GPL-compatible.
- Android audio permission handling across versions (API 33+ `READ_MEDIA_AUDIO`; older `READ_EXTERNAL_STORAGE`), with the app fully usable if it's denied.
- Build machine: implementation moves to Neal's Mac Studio (M2 Max, 64 GB) once the plan is written. The 5.7 GB Linux machine's Gradle limits don't apply there. The plan should include a setup task: clone, Android SDK/NDK, signing/debug keystore, adb to the Pixel 6.
- Device tests on the Pixel 6 wipe app data, so they run only with Neal's OK (existing rule).
- Archive.org and LibriVox requests use the existing archive user agent and polite pacing. LibriVox may return HTTP 429.

## 13. Done means

On the Pixel 6:
- Phone music and audiobooks appear without being moved. Deleting one in the Files app marks it missing in Granger; restoring it brings back its position.
- A LibriVox book downloads, plays by chapter and resumes at the right spot after force-stopping the app and after a reboot.
- An archive.org album downloads (filtered view shows only pre-1926 recordings; the waiver lifts it) and plays gaplessly. Check with a known gapless test album (encoded MP3 and FLAC) as well.
- Switching audiobook → music → audiobook loses no position.
- The mini-player looks and behaves the same for both, across Library, Search, Research and immersive reading.
- "Holst" (or any present artist/composer) shows media in Search. A Research question about a present work cites it, and the Plan 9 tuning split stays within 2 points (§9).
- With "Show in other apps" on, Granger's media folders appear read-only in the Files app.
- Auxio and Voice checklists (§6, §7) ticked, or each gap listed with a reason.
