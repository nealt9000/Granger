# Plan M3: Media in Search and Research, Android Auto, widget — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Music and audiobooks are found by Granger's Search ("Holst" finds *The Planets*), Research can find and cite them without crowding out books and articles, and the player reaches outside the app: Android Auto browses and plays both players, one resizable home-screen widget shows whichever player is current, and the notification shows the chapter, offers next and previous for every book, and resumes after the app has been killed. M3 ends with the spec's whole Done-means walkthrough on the Pixel 6, which closes the Media project.

**Architecture:**
- **`:core:library`**: an in-memory **`MediaIndex`** over the media rows (titles, albums, artists, album artists, composers, genres, track titles, authors, narrators, descriptions), used by Search, by Research and by Android Auto's search. **Album notes** (library v8): the description an archive.org album's manifest carries, which tracks have no column for. Media **catalogue cards** for Research, beside the book and pack cards in `LibraryPassageFinder`. The artist and genre name rules move down from `:app`'s `MusicCatalog` (`MediaNames`), so the index, Auto and the music screens agree.
- **`:core:research`**: `SourceKind.MEDIA` and a **media cap** in ranking: at most one media passage reaches the answer unless the question asks about music or audiobooks.
- **`:core:player`**: the session tells the truth about the player. The chapter title shows as the title, next and previous work for every book, a queue row can be played, playback resumes after process death (`onPlaybackResumption`), and an Android Auto browse tree is served through a **`MediaBrowseTree`** that `:app` implements.
- **`:app`**: Music and Audiobooks groups in Search, media citations that open their pages, the `AutoBrowseTree`, a read-only **artwork provider** (Auto and the launcher can't read `file://` covers), and a **Glance widget**.
- **`tools/eval`**: a small media question set with a "media cited" and a "media intrusion" metric. The Plan 9 tuning split is re-run as the regression check.

**Tech Stack:** Kotlin 2.4.21, coroutines 1.11.0, Room 2.8.5 (library DB manual migration 7→8), Media3 1.11.1 (`MediaLibraryService`, unchanged version), Compose Material 3, **Glance 1.2.0** (`androidx.glance:glance-appwidget`, `glance-appwidget-testing`; Apache-2.0, the one new dependency), Robolectric 4.17, JUnit 4 + Truth, Python 3 for `tools/eval` (pytest 8.4.2, unchanged). The Android Auto Desktop Head Unit 2.0 (`sdkmanager "extras;google;auto"`) for the device walkthrough.

**Spec:** `docs/superpowers/specs/2026-10-19-media-design.md` §6 and §7 (the Android Auto and widget rows), §8 (notification, lock screen, widget and Auto reflect the active player), §9 (Search and Research), §10 (ids) and §13 (Done means, all of it). Roadmap: `docs/superpowers/plans/2026-10-15-granger-v1-roadmap.md` (the M3 row, "Carried forward from M1" and "Carried forward from M2"). Parity: `docs/superpowers/plans/media-parity-checklist.md` (the ⏭ rows: Android Auto ×2, widget, Search). Earlier plans this builds on: `2026-10-17-plan-08-research.md` (pipeline, cards, citations), `2026-10-18-plan-09-research-eval.md` (the eval harness and its rules), `2026-10-19-plan-m1-media-players.md` (the engine and session), `2026-10-19-plan-m2-media-sources.md` (manifests, downloaded media).

## How Media is split

| Plan | Delivers | Spec sections |
|---|---|---|
| M1 (done) | Phone media, both players, mini-player, browsing, playlists, Recent plays | §2, §3.1, §5, §6, §7, §8, §10 |
| M2 (done) | LibriVox, archive.org audio with the rolling filter, sources by media type, media downloads with origin records, "Show in other apps" | §3.2–§3.4, §4 |
| **M3 (this plan)** | Media in Search, Research media passages + eval check, Android Auto, widget, the notification's carried-forward gaps, the final Done-means walkthrough | §6/§7 Auto and widget rows, §8, §9, §13 |

## What the code looks like now (surveyed 2026-10-19, after M2 merged at `dea308e`)

- **Search** (`app/.../search/SearchViewModel.kt`, `SearchScreen.kt`): one `State` with a list per group (`titles`, `fullText`, `books`), searched in that order after a 250 ms debounce, through `LibrarySearch` (`core/library/.../LibrarySearch.kt`, implemented by `ZimLibrarySearch`). No media. Tapping a result opens a reader tab.
- **Research** (`core/research`): `enum class SourceKind { ARTICLE, BOOK, CARD, SELECTION }` with no exhaustive `when` anywhere; `LibraryPassageFinder` (`core/library`) returns articles, book passages and up to 3 **catalogue cards** (`matchCards`: "Label: value" lines, gated by `mentions`, every word of some keyword as a whole word). `Ranking` has no per-kind caps. A citation opens through `ResearchViewModel.target()` → `CitationTarget { Article, Book, Gone }`; a sourceId that isn't a book is treated as a pack.
- **Eval** (`tools/eval`, Python): `dataset/tune.json` (202) and `dataset/heldout.json` (100). Plan 9's tuned run, **tune-5: 91% declined (69/76), 45% grounded (57/126)** (`tools/eval/runs/checkpoints/tuning/NOTES.md`). Its `results.jsonl` isn't in the repo (`runs/*` is ignored), but `grades/grades.jsonl` keeps every grade by reply hash, so a re-run only needs the changed replies graded. A tune run takes about 2.5–3 hours on the Pixel 6.
- **Player session** (`core/player/.../PlaybackService.kt`): already a `MediaLibraryService`, exported with both browse intent filters. Its `LibraryCallback` is a stub (`root` with no children); no `onPlaybackResumption`, `onSetMediaItems`, `onSearch`. `SessionPlayer : ForwardingPlayer` routes play/pause/next/previous/seek back/forward to the engine, but not `seekTo(index, …)`, `setMediaItems` or the available commands. `PlayerEngine.item()` sets the book's title on every chapter and `file://` artwork.
- **Library** (`core/library`): v7, manual migrations, schemas exported. Tracks have no description column; an archive.org album's manifest description is dropped on filing (`DownloadedMediaScanner`). The artist, genre and source rules (`MusicCatalog`, `MusicSources`) and `PlayResumer` live in `:app`.
- **Widget**: nothing exists.

## Decisions made with Neal (earlier, still in force)

- Media is free; nothing is gated (spec §11). No storage cap (2026-10-19).
- Search: media gets its own groups; tapping a song plays it, tapping an album, artist or audiobook opens its page (spec §9, Claude's call accepted).
- Research: media is a passage source; it must not push book and article passages out when the question isn't about media; play buttons inside answers and opening at a track position are project 2 (spec §9).
- Regression check: the Plan 9 **held-out** set is not touched again. Media changes are checked against the **tuning** split: declined and grounded may not drop more than 2 points below tune-5 (so **≥ 89% declined, ≥ 43% grounded**), plus a small media question set (spec §9).
- Android Auto: a browse tree over both players, tested on the Desktop Head Unit. One widget that shows whichever player is current (spec §6, Neal's calls).
- Research stays debug-only (`Features.RESEARCH = BuildConfig.DEBUG`); M3's Research work ships behind the same flag.
- **Answered for M3 (Neal, 2026-10-19):** tapping a song in Search plays its album from that song; the Music and Audiobooks groups come after Titles; the eval runs in Task 10 use the phone for about 3.5 hours, and Neal leaves it open for them to run into the night.

## Builder's calls in this plan (for Neal's review)

**Search**
- **The media index is in memory, not a new FTS table.** A phone library is thousands of rows of short metadata; matching them in memory takes milliseconds, needs no triggers to keep it in step with every sync, and can be shared by Search, Research and Auto. It is rebuilt (on `Dispatchers.Default`) whenever the media rows change. If a library ever reaches ~50,000 tracks and the index shows in a profile, an FTS4 table like `book_text` is the fallback.
- **Matching:** text is folded (NFKD, combining marks dropped, `lowercase(Locale.ROOT)`) and split into words (`[\p{L}\p{N}]+`). Every query word must match a word of the item: whole word, except the last query word, which may be a prefix ("plan" finds *Planets*), as `BookIndex` does. **Score:** per query word, the best field it matched, weighted title 3, album / artist / album artist / composer / author / narrator 2, track titles and genres 1, description 0.5; plus 2 when the whole query appears in the title as written (after folding). Ties by title, then id. MISSING items are left out.
- **Groups and limits:** a **Music** group (artists first, up to 3; then albums, up to 5; then songs, up to 8) and an **Audiobooks** group (up to 5). They show **after Titles and before "In article text"**, because they're the quickest results and a name like "Holst" is most likely a person or work.
- **Tapping a song plays its album from that song** (`PlaySource` of the album), as Auxio does from an album list, so the music carries on. It starts a new queue, unshuffled, exactly as tapping a song on the album page does. Tapping an artist opens the artist page; an album, the album page; an audiobook, the book page (a page, not autoplay; spec §9).
- **Downloaded album descriptions are kept** (library v8, `album_notes(albumKey, description)`), written by the downloaded-media sync and deleted with `forget`. Phone albums have none.

**Research**
- **One media card per album or audiobook, never per song.** The card's text: `Album:` / `Audiobook:`, `Artist:` / `Author:`, `Composer:`, `Narrator:`, `Genre:`, `Year:`, `Tracks:` / `Chapters:` (song titles for an album, chapter titles for an audiobook, joined by "; ", cut at 400 characters), `About:` (the description, cut at 600), and `In your library as: Music album` or `Audiobook`. `sourceId` is `album:<albumKey>` or the audiobook id; `sourceTitle` the album or book title; `packTitle` "Your music" or "Your audiobooks"; `meta.author` the artist or author; `location` "". Gate: the cards' existing `mentions` rule. At most 2 media cards per `find`.
- **The media cap:** `RankingConfig.mediaCap = 1`. When the question asks about media, it's 2. "Asks about media" means its words include one of: music, album, albums, song, songs, track, tracks, listen, listening, recording, recordings, record, records, soundtrack, symphony, composer, composed, band, singer, audiobook, audiobooks, narrator, narrated, "read by", "do I have", "have I got" (Task 10 dropped "score" and added the last two). Applied after ranking, before the pool is cut to `large`; a capped media passage gives its place to the next non-media passage. Media passages also never take one of preselect's 12 slots: the other passages are preselected exactly as before and the media ones (at most 2) are carried alongside. Why a cap and not a weight: Plan 9's lexical threshold is tuned; a cap leaves every non-media score as it was, so the tuning split can only move by the slots media takes. **Task 10 added a lift:** on a media question, the finder's best media card goes first (m3-media-1 cited media on 3 of 10 without it; see `tools/eval/runs/checkpoints/m3/NOTES.md`).
- **A media citation opens the album or audiobook page** (`CitationTarget.Album(albumKey)`, `CitationTarget.Audiobook(id)`); if the item is gone it's `Gone`, as for books. The source card's byline is the artist or author, then "Album" or "Audiobook".

**Eval**
- **A media set:** `tools/eval/dataset/media.json`, split `"media"`, 13 items: 10 that should cite media and 3 controls that mustn't (questions about Mars, the Moon and Venus, where Holst and Pink Floyd are traps). They're written in Task 5 against what's on Neal's phone now: *The Secret of NIMH* (Jerry Goldsmith), *The Dark Side of the Moon* and *The Wall* (Pink Floyd), Holst's *The Planets* (the folder-named phone album and the separate "Venus" album), *The Jungle Book* (phone audiobook, Kipling) and *Wicked* (archive.org audiobook, Gregory Maguire). The expected source is the card's sourceId with location "".
- **Bars:** media cited (an expected media source among the cited passages) on **≥ 8 of 10**; media cited on **0 of 3** controls; the tuning split **≥ 89% declined and ≥ 43% grounded**. Grounded on the media set is reported, not barred.
- **If the tuning split misses a floor,** the media cap and the cue list are tuned on the tuning split only (never the held-out), then re-run. If it can't be met, Media in Research is switched off behind its own flag and the miss is reported; Search and the rest of M3 don't wait on it.

**Player and session**
- **The chapter shows as the title** in the notification, the lock screen, Auto and the widget: the engine replaces the current media item's metadata when the chapter changes (`player.replaceMediaItem` with the same URI and new `MediaMetadata`: title = chapter, artist = author, album title = book). Media3 updates a progressive item's metadata without re-preparing it, so playback doesn't stop; Task 6 pins that.
- **Next and previous for every book:** `SessionPlayer` reports `COMMAND_SEEK_TO_NEXT`, `…_PREVIOUS` (and the `_MEDIA_ITEM` pair) as available whenever a book with more than one chapter is loaded, and announces the change to the listeners it was given. They already step chapters through the engine.
- **Queue tap-to-play:** `PlayerHub.playQueueItem(position)`; the queue sheet's rows call it, and `SessionPlayer.seekTo(index, ms)` / `seekToDefaultPosition(index)` route to it, so Auto's queue works too.
- **Playback resumption** (a headset or Bluetooth play press, or Auto, after the process died): `onPlaybackResumption` restores the last active player paused, from its saved state, and returns its items and position. A restore never writes a position before its seek completes (M1's `noSaveBeforeTheRestoreSeekCompletes` rule).
- **Items handed to the session by a controller** (`onSetMediaItems`, `onAddMediaItems`, from Auto) are resolved by id through `MediaBrowseTree.play` and started by the engine; `SessionPlayer.setMediaItems` and `addMediaItems` then do nothing when the items are already the engine's. The engine stays the only thing that loads the player.

**Android Auto**
- **Browse tree** (ids are stable strings; none contains `@`, so `MediaAddress` still works):
  - Root tabs (3): **Recent** (`recent`), **Music** (`music`), **Audiobooks** (`audiobooks`, a grid of covers, last played first, as the shelf).
  - Music → **Albums** (`music/albums`, grid, by title), **Artists** (`music/artists`), **Playlists** (`music/playlists`).
  - Browsable: `album:<albumKey>` (its songs), `artist:<name>` (its albums), `playlist:<id>` (its songs).
  - Playable: `album:<albumKey>#<trackId>` plays the album from that song; `playlist:<id>#<ordinal>`; `audiobook:<id>` resumes the book; `recent:<recent key>` resumes it through `PlayResumer`. A whole album or playlist plays from its first song.
  - Lists are paged by Auto's page and page size. MISSING items are left out.
- **Search** in Auto (typed or voice) goes through `MediaIndex`: results are the top artists, albums, songs and audiobooks, as playable items. A voice "play …" (an item with an empty id and a search query) plays the best hit: an album, else an audiobook, else a song (in its album), else an artist's songs.
- **Artwork** goes through an exported, read-only `ArtworkProvider` (`content://app.granger.artwork/<token>`). The token is a URL-safe Base64 of the cover's path relative to one of its allowed roots, and the provider serves only image files (`.jpg`, `.jpeg`, `.png`, `.webp`) whose canonical path is under `filesDir/media-covers` or a volume's `music/` or `audiobooks/` media roots. It allows no query or write. The session's items, Auto and the widget use it. Covers aren't secret, but nothing else is reachable through it.
- `res/xml/automotive_app_desc.xml` (`<uses name="media"/>`) and the `com.google.android.gms.car.application` meta-data in the app manifest.

**Widget**
- **One Glance widget, "Granger player"**, resizable from 2×1 to 4×2 cells (`SizeMode.Responsive`: small = cover + play/pause; medium = cover, title, subtitle, play/pause, the secondary button; large adds a progress line). The secondary button is next for music and back 30 s (the skip setting) for books, as in the mini-player. Tapping the cover or text opens Granger at the Player.
- **What it shows:** the active player; when nothing is loaded, the last active player's saved item (from `SavedModes`), and play resumes it (`activate(lastMode)`). With nothing ever played: "Nothing playing" and "Open Granger".
- **It updates on changes, not on a clock:** the widget re-renders when the item, play state, chapter or saved state changes. Progress is drawn as of the last update (it doesn't tick), so the widget never wakes the phone every second.

## Carried forward: taken into M3, and left

Taken, because M3 touches the same code:
- M1: queue tap-to-play; `onPlaybackResumption`; a single-file book shows no next/previous in the notification, which shows the book's title, not the chapter's (Task 6).
- M1: `MediaControllerPlayerHub` and `PlaybackService` have no automated test: the session callbacks and `SessionPlayer` get Robolectric tests (Tasks 6, 8). The hub's binding stays device-only.
- M2: Search over texts ranks every field by downloads; M3 doesn't change texts, but the media index's title-first scoring is the model for that later fix.
- The device checks still open from M1: the notification, lock screen, Bluetooth and headset buttons, one by one; deleting and restoring a phone file (Neal skipped it twice; it stays optional) (Task 11).

Left, with the reason:
- Album, artist, genre, playlist and folder pages scoped to a collection; drag reorder; "Add to playlist" in the player; sort options; feedback after adding a bookmark; a signal for a lost place: media polish, after M3 (the roadmap's UX list).
- The focus content type on mode switch, replaying a just-ended book, the finished flag after restore, the duplicated chapter-lookup rule, the 6 s worst case: player behaviour M3 doesn't change.
- Texts search ranking, LibriVox HTML entities, Browse rows always offering Get, the shared group id for an item got under both kinds: sources work, not M3's.
- `PlayerHub.unload` emptying the whole queue: unchanged.
- The unused cap plumbing: Plan 10.

## Global Constraints

- `minSdk 29`, `compileSdk 37`, `targetSdk 36`, Java 17, as in every module today.
- License: the app is GPLv3. The only new dependency is Glance 1.2.0 (Apache-2.0). Add no other.
- **Everything in Media is free:** no Pro or entitlement checks.
- **Phone media stays read-only.** Nothing in M3 writes, renames or deletes a `content://` or SAF URI. The artwork provider is read-only.
- **The held-out split is never run, read or graded in M3.** Only `dataset/tune.json` and the new `dataset/media.json` are run. Media questions never use held-out questions or their wording.
- **The engine is the only thing that loads the player.** Session callbacks, Auto and the widget go through `PlayerHub`/`PlayerEngine` calls, never `player.setMediaItems` themselves.
- **Anti-goals still hold:** no lost audiobook position (a restore or a controller's request never saves before its seek completes); no gap added between songs (the chapter metadata change must not re-prepare the item).
- Tests use fakes and in-memory Room, as today. **No test touches the network or the phone**, except the eval runs (Task 10), which run only with Neal's OK.
- Copy (exact):
  - Search section headers: "Music", "Audiobooks". Rows: artist "Artist · 3 albums" (or "1 album"); album "Album · <album artist>"; song "Song · <artist> · <album>"; audiobook "<author>" (the narrator after " · read by " when there is one).
  - Research card last line: "In your library as: Music album" / "In your library as: Audiobook". Source-card byline second part: "Album" / "Audiobook".
  - Auto: "Recent", "Music", "Audiobooks", "Albums", "Artists", "Playlists"; an empty list's item: "Nothing here yet".
  - Widget: name "Granger player", description "Shows and controls what's playing"; empty "Nothing playing" / "Open Granger".
- The disk on the Mac is case-insensitive: no two files or classes whose names differ only in case.
- Builds run on the Mac Studio in `~/Developer/Granger/Granger` (`tools/dev/MAC-SETUP.md`). Never work in the stale copy under `~/Desktop`.
- Device rules (unchanged):
  - Never run `:app:connectedDebugAndroidTest` (it reinstalls the app and wipes Neal's library). `:core:player:connectedAndroidTest` installs only its own APK and may be run; check afterwards that `app.granger` is still installed.
  - Never touch the phone while one of Neal's personal apps is in front. Never `adb reboot`.
  - The eval needs the debug build, the charger and the phone free for about 3 hours a run.
- Pushing to GitHub needs Neal's OK at that step.

## Review Focus

1. **Media doesn't crowd out books and articles.** A non-media question whose keywords happen to name a track ("Mars", "Money", "Time") gets at most one media passage, and the rest of the pool is what it was before M3. Pinned in Task 3 (`mediaCapKeepsOneMediaPassageForANonMediaQuestion`, `mediaCuesRaiseTheCapToTwo`, `cappedMediaGivesItsPlaceToTheNextPassage`) and measured in Task 10 (controls, tuning floor).
2. **A controller's request doesn't fight the engine.** Auto picks an album: the engine loads it once, `SessionPlayer.setMediaItems` is a no-op, the saved state is the album's. Pinned in Task 8 (`setMediaItemsFromAutoLoadsOnceThroughTheEngine`, `voicePlayPicksTheBestHit`).
3. **The artwork provider serves covers and nothing else.** Paths outside the roots, `..`, symlinks out, non-image names, write modes and queries are refused. Pinned in Task 7.
4. **Resumption after process death keeps the place.** `onPlaybackResumption` restores paused at the saved position and writes nothing before the seek completes. Pinned in Task 6 (`resumptionRestoresTheSavedBookWithoutSavingFirst`).
5. **The chapter title change doesn't interrupt sound.** Replacing the item's metadata mid-file keeps the playback state READY and the position continuous. Pinned in Task 6 (`chapterChangeUpdatesTheTitleWithoutRepreparing`).
6. **The widget doesn't tick.** Position-only changes don't re-render it. Pinned in Task 9 (`positionTicksDoNotUpdateTheWidget`).

---

### Task 1: The media index and album notes

**Files:**
- Create: `core/library/src/main/kotlin/app/granger/core/library/MediaIndex.kt`, `MediaNames.kt`, `MediaText.kt`
- Modify: `core/library/src/main/kotlin/app/granger/core/library/LibraryDatabase.kt` (v8, `MIGRATION_7_8`), `MediaEntities.kt` (`AlbumNote`), `MediaDaos.kt`, `MediaLibrary.kt` (`ScanResult.albumNotes`, `albumNotes` flow, `forget` deletes notes)
- Modify: `core/media/src/main/kotlin/app/granger/core/media/DownloadedMediaScanner.kt` (an album manifest's description → `ScanResult.albumNotes`)
- Modify: `app/src/main/kotlin/app/granger/music/MusicViewModel.kt` (`MusicCatalog` delegates its name rules to `MediaNames`)
- Modify: `app/src/main/kotlin/app/granger/GrangerApp.kt` (`val mediaIndex = MediaIndex(media, appScope)`)
- Test: `core/library/src/test/kotlin/app/granger/core/library/MediaIndexTest.kt`, `MediaTextTest.kt`, `MigrationTest.kt`, `MediaLibraryTest.kt`; `core/media/src/test/.../DownloadedMediaScannerTest.kt`; `app/src/test/kotlin/app/granger/music/MusicViewModelTest.kt` (unchanged behaviour)

**Interfaces:**
- Produces:
  ```kotlin
  // MediaText
  object MediaText {
      /** NFKD, combining marks dropped, lowercase(Locale.ROOT). */
      fun fold(s: String): String
      /** fold(s) split into [\p{L}\p{N}]+ words. */
      fun words(s: String): List<String>
  }
  // MediaNames (moved from MusicCatalog; MusicCatalog calls these)
  object MediaNames {
      fun split(joined: String): List<String>          // "; "-joined tag values, trimmed, blanks dropped
      fun albumArtistsOf(t: Track): List<String>       // album artists, else artists
      fun trackArtistsOf(t: Track): List<String>
      fun genresOf(t: Track): List<String>
      fun nameKey(name: String): String                // trim().lowercase(Locale.ROOT)
  }
  // Entities (library v8)
  @Entity(tableName = "album_notes") data class AlbumNote(@PrimaryKey val albumKey: String, val description: String)
  // ScanResult gains: val albumNotes: List<AlbumNote> = emptyList()
  // MediaLibrary gains: val albumNotes: Flow<List<AlbumNote>>
  // MediaIndex
  data class ArtistHit(val name: String, val albumCount: Int, val coverPath: String?)
  data class MediaResults(
      val artists: List<ArtistHit> = emptyList(),
      val albums: List<AlbumSummary> = emptyList(),
      val songs: List<Track> = emptyList(),
      val audiobooks: List<Audiobook> = emptyList(),
  ) { val isEmpty: Boolean }
  /** An album or audiobook with the text Research and Auto need about it. */
  data class MediaWork(
      val id: String,                 // "album:<albumKey>" or the audiobook id
      val isAlbum: Boolean,
      val title: String,
      val creator: String,            // album artist or author
      val composers: List<String>, val narrators: List<String>, val genres: List<String>,
      val year: String,
      val partTitles: List<String>,   // songs in album order, or chapters
      val description: String,
      val origin: MediaOrigin,
      val coverPath: String?,
  )
  class MediaIndex(media: MediaLibrary, scope: CoroutineScope, compute: CoroutineDispatcher = Dispatchers.Default) {
      suspend fun search(query: String, artists: Int = 3, albums: Int = 5, songs: Int = 8, audiobooks: Int = 5): MediaResults
      /** Albums and audiobooks whose text holds every word of [words] as whole words (Research's gate), best first. */
      suspend fun works(words: List<String>, limit: Int): List<MediaWork>
      suspend fun work(id: String): MediaWork?
  }
  ```
- `MIGRATION_7_8`: `CREATE TABLE IF NOT EXISTS album_notes (albumKey TEXT NOT NULL PRIMARY KEY, description TEXT NOT NULL)`. Export schema 8.

- [ ] **Step 1: Write the failing tests.**

```kotlin
// MediaTextTest
@Test fun foldDropsAccentsAndCase()                  // "Dvořák Ñandú" → "dvorak nandu"
@Test fun wordsSplitOnPunctuation()                   // "Op. 32, H. 125_ I. Mars" → [op, 32, h, 125, i, mars]
// MediaIndexTest (FakeMediaLibrary-style in-memory fake in core/library tests, or RoomMediaLibrary in memory)
@Test fun holstFindsTheAlbumAndTheArtist()           // album "The Planets", albumArtists "Gustav Holst" → artists [Gustav Holst], albums [The Planets]
@Test fun lastWordIsAPrefixOthersAreWhole()          // "the plan" finds "The Planets"; "plan planets" doesn't (plan isn't a whole word)
@Test fun everyQueryWordMustMatch()                  // "holst wall" finds nothing
@Test fun titleOutranksDescription()                 // query in one album's title, another's description → title first
@Test fun songsMatchTheirOwnTitleAndAlbum()          // "comfortably" → song "Comfortably Numb"
@Test fun audiobooksMatchAuthorNarratorAndDescription()
@Test fun missingItemsAreLeftOut()
@Test fun limitsApplyPerGroup()
@Test fun indexFollowsLibraryChanges()               // sync a new album → next search finds it
@Test fun worksGateOnWholeWords()                    // works(["holst"]) → The Planets; works(["hol"]) → none
@Test fun workCarriesPartsAndTheAlbumNote()          // partTitles in album order; description from album_notes
// MigrationTest
@Test fun migrate7To8KeepsDataAndCreatesAlbumNotes()
// MediaLibraryTest
@Test fun syncWritesAlbumNotesAndForgetDeletesThem()
// DownloadedMediaScannerTest
@Test fun albumManifestDescriptionBecomesAnAlbumNote()   // Holst-shaped manifest with description → AlbumNote(itemKey, description)
```

- [ ] **Step 2: Run to verify they fail.** Run: `./gradlew :core:library:testDebugUnitTest :core:media:testDebugUnitTest`. Expected: compile failure.
- [ ] **Step 3: Implement.** `MediaIndex` keeps an immutable snapshot (`combine(media.tracks, media.audiobooks, media.allChapters, media.albumNotes)` → built on `compute`, `stateIn(scope, Eagerly, null)`; `search` waits for the first snapshot). Each document stores its folded field words once. Albums come from `Albums.summaries`, artists from `MediaNames.albumArtistsOf` across OK tracks. Move `MusicCatalog`'s private name helpers to `MediaNames` and have `MusicCatalog` call them; its tests must pass unchanged.
- [ ] **Step 4: Run to verify they pass.** Same command, then `./gradlew :app:testDebugUnitTest --tests 'app.granger.music.*'`. Expected: PASS.
- [ ] **Step 5: Commit**

```bash
git add core/library core/media app/src/main/kotlin/app/granger/music app/src/main/kotlin/app/granger/GrangerApp.kt
git commit -m "Media M3: in-memory media index, album notes (library v8), media name rules in core"
```

### Task 2: Music and Audiobooks in Search

**Files:**
- Modify: `app/src/main/kotlin/app/granger/search/SearchViewModel.kt`, `SearchScreen.kt`
- Create: `app/src/main/kotlin/app/granger/media/SongPlayer.kt` (plays a song within its album)
- Modify: `app/src/main/kotlin/app/granger/GrangerNavHost.kt` (Search callbacks to `AlbumRoute`, `ArtistRoute`, `AudiobookRoute`; the song player)
- Test: `app/src/test/kotlin/app/granger/search/SearchViewModelTest.kt`, `app/src/test/kotlin/app/granger/media/SongPlayerTest.kt`

**Interfaces:**
- Consumes: `MediaIndex.search` (Task 1), `PlayerHub.playTracks`, `MusicCatalog.playable`, `MusicSources.album`.
- Produces:
  ```kotlin
  fun interface MediaSearch { suspend fun search(query: String): MediaResults }
  // SearchViewModel(search, sessions, initialQuery, media: MediaSearch = MediaSearch { MediaResults() })
  // State gains: val media: MediaResults = MediaResults()
  class SongPlayer(private val media: MediaLibrary, private val hub: PlayerHub) {
      /** The song's album (its OK tracks in album order) from that song, as the album's PlaySource. */
      suspend fun play(track: Track)
  }
  // SearchScreen gains: onOpenAlbum(albumKey), onOpenArtist(name), onOpenAudiobook(id), onPlaySong(track)
  ```

- [ ] **Step 1: Write the failing tests.**

```kotlin
// SearchViewModelTest
@Test fun mediaResultsArriveBeforeArticleText()      // media searched right after titles; state has media before fullText is set
@Test fun blankQueryClearsMedia()
@Test fun mediaFailureLeavesTheOtherGroups()         // MediaSearch throws → media empty, titles/fullText/books still shown
// SongPlayerTest
@Test fun songPlaysItsAlbumFromThatSong()            // album of 3, tap the 2nd → playTracks([1,2,3], 1, false, album source)
@Test fun missingSongsInTheAlbumAreSkipped()
```

- [ ] **Step 2: Run to verify they fail.** Run: `./gradlew :app:testDebugUnitTest --tests 'app.granger.search.*' --tests 'app.granger.media.SongPlayerTest'`. Expected: compile failure.
- [ ] **Step 3: Implement.** In `SearchScreen`, a "Music" section (artists, albums, songs, with the row copy in Global Constraints and the album cover as the leading image) and an "Audiobooks" section, between "Titles" and "In article text". Opening a media result records the search like an article does (`sessions.recordSearch`). "Nothing in your library matches." shows only when every group, media included, is empty.
- [ ] **Step 4: Run to verify they pass.** Same command, then all of `:app:testDebugUnitTest`. Expected: PASS.
- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/app/granger/search app/src/main/kotlin/app/granger/media/SongPlayer.kt app/src/main/kotlin/app/granger/GrangerNavHost.kt app/src/test
git commit -m "Media M3: Music and Audiobooks groups in Search; a song plays its album from there"
```

### Task 3: Media passages in Research, and the media cap

**Files:**
- Modify: `core/research/src/main/kotlin/app/granger/core/research/Passage.kt` (`SourceKind.MEDIA`), `Ranking.kt` (`mediaCap`, `capMedia`), `ResearchPipeline.kt` (apply the cap with the question's cue), `AnswerPrompt.kt` (MEDIA uses the 1,200-char cap; check it)
- Create: `core/research/src/main/kotlin/app/granger/core/research/MediaCues.kt`
- Modify: `core/library/src/main/kotlin/app/granger/core/library/LibraryPassageFinder.kt` (media cards from `MediaIndex.works`)
- Create: `core/library/src/main/kotlin/app/granger/core/library/MediaCards.kt` (card text)
- Modify: `app/src/main/kotlin/app/granger/GrangerApp.kt` (finder gets `mediaIndex`)
- Test: `core/research/src/test/.../RankingTest.kt`, `MediaCuesTest.kt`, `ResearchPipelineTest.kt`; `core/library/src/test/.../LibraryPassageFinderTest.kt`, `MediaCardsTest.kt`, `ResearchChatsTest.kt` (MEDIA round-trips)

**Interfaces:**
- Produces:
  ```kotlin
  enum class SourceKind { ARTICLE, BOOK, CARD, SELECTION, MEDIA }
  // RankingConfig gains: val mediaCap: Int = 1, val mediaCapAsked: Int = 2
  object Ranking { fun capMedia(passages: List<Passage>, asked: Boolean, config: RankingConfig): List<Passage> }
  object MediaCues { fun asked(question: String): Boolean }   // the cue list in Builder's calls; whole words, folded; "read by" as a phrase
  object MediaCards { fun passage(work: MediaWork): Passage } // the card text in Builder's calls
  // LibraryPassageFinder(..., media: MediaIndex? = null, mediaCards: Int = 2)
  ```
- No schema change: `ResearchPassage.kind` is stored by name.

- [ ] **Step 1: Write the failing tests.**

```kotlin
// MediaCuesTest
@Test fun mediaWordsAreCues()                        // "What's on the album The Wall?" → true; "Who narrated Wicked?" → true; "read by" phrase → true
@Test fun ordinaryQuestionsAreNot()                  // "How far is Mars from the Sun?" → false; "recorder" ≠ "record" → false
// RankingTest
@Test fun mediaCapKeepsOneMediaPassageForANonMediaQuestion()
@Test fun mediaCuesRaiseTheCapToTwo()
@Test fun cappedMediaGivesItsPlaceToTheNextPassage() // 8 ranked incl. 3 media → 1 media + 7 others, order otherwise unchanged
@Test fun capLeavesOtherKindsUntouched()             // no media in → output == input
// ResearchPipelineTest
@Test fun nonMediaQuestionPoolMatchesThePoolWithoutMedia() // same finder output minus all but the best media passage
// MediaCardsTest
@Test fun albumCardHasTheLabelsInOrder()             // Album/Artist/Composer/Genre/Year/Tracks/About/In your library as
@Test fun tracksAndAboutAreCut()                     // 400 and 600 chars, at a word boundary with "…"
@Test fun audiobookCardSaysAudiobookAndNarrator()
// LibraryPassageFinderTest
@Test fun holstQuestionFindsTheMediaCard()           // keywords ["holst"] → a MEDIA passage, sourceId "album:<key>", location ""
@Test fun atMostTwoMediaCards()
@Test fun noMediaIndexMeansNoMediaPassages()
// ResearchChatsTest
@Test fun mediaPassagesRoundTrip()
```

- [ ] **Step 2: Run to verify they fail.** Run: `./gradlew :core:research:test :core:library:testDebugUnitTest`. Expected: compile failure. (`:core:research` is a plain JVM module: its task is `test`, and `testDebugUnitTest` does not run it.)
- [ ] **Step 3: Implement.** In `ResearchPipeline.run`, after `rank()` and before `.take(config.large)`, apply `Ranking.capMedia(ranked, MediaCues.asked(request.question), config)`. Check every `SourceKind` use the survey listed (`AnswerPrompt.kt:58`, `LexicalScore.kt:40`, `ResearchViewModel.kt:241, 320, 330, 482`, `ResearchScreen.kt:139`, `GrangerNavHost.kt:293`, `EvalActivity.kt:86`) and say in the commit message which needed a change.
- [ ] **Step 4: Run to verify they pass.** Same command. Expected: PASS.
- [ ] **Step 5: Commit**

```bash
git add core/research core/library app/src/main/kotlin/app/granger/GrangerApp.kt
git commit -m "Media M3: media catalogue cards in Research, capped so they don't crowd out books and articles"
```

### Task 4: Media citations open their pages

**Files:**
- Modify: `app/src/main/kotlin/app/granger/research/CitationTarget.kt` (`Album`, `Audiobook`), `ResearchViewModel.kt` (`target()` for MEDIA; constructor gets `MediaIndex`, whose `work(id)` is null for a gone or MISSING item), `ResearchScreen.kt` (source card byline for MEDIA)
- Modify: `app/src/main/kotlin/app/granger/GrangerNavHost.kt` (`onOpenCitation` navigates to `AlbumRoute` / `AudiobookRoute`)
- Test: `app/src/test/kotlin/app/granger/research/ResearchViewModelTest.kt`, `ResearchFakes.kt` (a `media(n)` passage helper)

**Interfaces:**
- Produces:
  ```kotlin
  sealed interface CitationTarget {
      data class Article(val tabId: Long, val fragment: String?) : CitationTarget
      data class Book(val tabId: Long, val at: BookTarget?) : CitationTarget
      data class Album(val albumKey: String) : CitationTarget
      data class Audiobook(val audiobookId: String) : CitationTarget
      data object Gone : CitationTarget
  }
  ```

- [ ] **Step 1: Write the failing tests.**

```kotlin
// ResearchViewModelTest
@Test fun mediaAlbumCitationOpensTheAlbum()          // MEDIA "album:<key>", album present → Album(key); no tab opened
@Test fun mediaAudiobookCitationOpensTheBook()
@Test fun removedMediaCitationIsGone()               // album key no longer has OK tracks → Gone
@Test fun mediaIsNeverTreatedAsAPack()               // regression: MEDIA never reaches the pack branch
```

- [ ] **Step 2: Run to verify they fail.** Run: `./gradlew :app:testDebugUnitTest --tests 'app.granger.research.*'`. Expected: compile failure.
- [ ] **Step 3: Implement.** `target()` checks `kind == MEDIA` first. The source card shows "<artist or author> · Album" / "· Audiobook" for MEDIA.
- [ ] **Step 4: Run to verify they pass.** Same command. Expected: PASS.
- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/app/granger/research app/src/main/kotlin/app/granger/GrangerNavHost.kt app/src/test/kotlin/app/granger/research
git commit -m "Media M3: a media citation opens its album or audiobook page"
```

### Task 5: The media eval set and its metrics

**Files:**
- Create: `tools/eval/dataset/media.json` (13 items)
- Modify: `tools/eval/dataset.py` (split `"media"`; a media source is `album:…` or `audiobook:…`, location ""), `tools/eval/metrics.py` (`mediaCited`, `mediaIntrusion`), `tools/eval/report.py` (show them), `tools/eval/eval.py` (`validate --media <library.json>`: checks media sources against an export of the phone's library), `tools/eval/AUTHORING.md` (the media set's rules)
- Create: `tools/eval/media_library.py` (reads the phone's library DB copy into `{albums: {key: title}, audiobooks: {id: title}}`)
- Modify: `app/src/debug/kotlin/app/granger/eval/EvalActivity.kt` (`sourceName` returns `sourceId` for MEDIA; already the default branch, check it), `EvalRunner.kt` (records `kind` "MEDIA"; check)
- Test: `tools/eval/tests/test_dataset.py`, `test_metrics.py`, `test_report.py`; `app/src/testDebug/kotlin/app/granger/eval/EvalRunnerTest.kt`

**Interfaces:**
- Produces: `media.json` items in the existing format, with `"split": "media"`, `"group": "media"` or `"media-control"`, `"answerable": true`, `"answers": [{"source": "album:<albumKey>", "location": "", "quote": ""}]` (controls: `"answers": []` and `"notes": "must not cite media"`). Metrics: `mediaCited = cited ∩ expected media sources ≠ ∅` over `group == "media"`; `mediaIntrusion = any cited MEDIA passage` over `group == "media-control"`.
- The items (album keys are read from the phone's `library.db` when the file is written; titles as on the phone):
  1. "Who composed the music for The Secret of NIMH?" → NIMH album
  2. "Which Pink Floyd album has Comfortably Numb on it?" → *The Wall*
  3. "What songs are on The Dark Side of the Moon?" → *The Dark Side of the Moon*
  4. "Do I have any Holst?" → the folder-named Holst album and the "Venus" album (either)
  5. "Which album is the song Money from?" → *The Dark Side of the Moon*
  6. "Is there an audiobook of The Jungle Book in my library?" → *The Jungle Book* audiobook
  7. "Who wrote Wicked?" → *Wicked*
  8. "What is the Wicked audiobook about?" → *Wicked*
  9. "Which album has Another Brick in the Wall?" → *The Wall*
  10. "What Jerry Goldsmith music do I have?" → NIMH album
  - Controls: "How far is Mars from the Sun?", "Why do we always see the same side of the Moon?", "Who was Venus in Roman religion?"

- [ ] **Step 1: Write the failing tests.**

```python
# test_dataset.py
def test_media_split_loads_and_media_sources_match_by_source_only(): ...
def test_validate_checks_media_sources_against_the_library_export(): ...
# test_metrics.py
def test_media_cited_counts_an_expected_media_source_among_cited(): ...
def test_media_intrusion_counts_any_cited_media_on_controls(): ...
def test_media_metrics_absent_without_media_items(): ...   # tune.json reports are unchanged
```

```kotlin
// EvalRunnerTest
@Test fun mediaPassagesAreRecordedWithTheirSourceId()
```

- [ ] **Step 2: Run to verify they fail.** Run: `cd tools/eval && python -m pytest -q`, and `./gradlew :app:testDebugUnitTest --tests 'app.granger.eval.*'`. Expected: failures.
- [ ] **Step 3: Implement**, then write `media.json`: copy the library DB off the phone (`adb exec-out run-as app.granger cat databases/library.db` plus `-wal`/`-shm`, into the scratchpad), read the album keys and audiobook ids with `media_library.py`, and fill the sources. `python3 eval.py validate dataset/media.json --media <export>` must pass.
- [ ] **Step 4: Run to verify they pass.** Same commands. Expected: PASS.
- [ ] **Step 5: Commit**

```bash
git add tools/eval app/src/debug app/src/testDebug
git commit -m "Media M3: media eval set (10 media questions, 3 controls) with media-cited and intrusion metrics"
```

### Task 6: The session tells the truth: chapter titles, book next/previous, queue taps, resumption

**Files:**
- Modify: `core/player/src/main/kotlin/app/granger/core/player/PlayerEngine.kt` (chapter metadata on change; `playQueueItem`; `resumption()`), `PlayerHub.kt` (`playQueueItem`), `PlaybackService.kt` (`SessionPlayer`: available commands, `seekTo(index, ms)`, `seekToDefaultPosition(index)`, listeners kept; `onPlaybackResumption` in the callback), `MediaControllerPlayerHub.kt` (`playQueueItem`)
- Modify: `app/src/main/kotlin/app/granger/player/QueueSheet.kt` (a row tap plays it), `app/src/test/kotlin/app/granger/player/PlayerFakes.kt`
- Test: `core/player/src/test/kotlin/app/granger/core/player/PlayerEngineTest.kt`, `SessionPlayerTest.kt` (new), `PlaybackCallbackTest.kt` (new); `app/src/test/kotlin/app/granger/player/PlayerViewModelTest.kt`

**Interfaces:**
- Produces:
  ```kotlin
  // PlayerHub
  /** Plays the queue's [position] (play order, as the queue sheet lists it) from its start. Music only. */
  fun playQueueItem(position: Int)
  // PlayerEngine
  /** Loads the last active player paused from its saved state if nothing is loaded; then its items, index and position. */
  suspend fun resumption(): MediaSession.MediaItemsWithStartPosition?
  ```
- `onPlaybackResumption(session, controller, isForPlayback)` (both overloads in Media3 1.11.1: implement the three-argument one and have the two-argument one call it) returns `engine.resumption()` as an immediate future, or a failed future when there's nothing saved.

- [ ] **Step 1: Write the failing tests.**

```kotlin
// PlayerEngineTest
@Test fun chapterChangeUpdatesTheTitleWithoutRepreparing()  // chapterBook(): cross chapter 1→2 → currentMediaItem title "Chapter 2"; playbackState stays READY; position continuous (±20 ms)
@Test fun bookItemsCarryAuthorAndBookAsArtistAndAlbum()
@Test fun playQueueItemPlaysThatPositionInPlayOrder()      // shuffled queue: position 2 → playOrder[2]'s item, from 0
@Test fun resumptionRestoresTheSavedBookWithoutSavingFirst() // nothing loaded, saved book at 0:50 → items + position 0:50; no state write before READY
@Test fun resumptionWithNothingSavedIsNull()
// SessionPlayerTest
@Test fun singleFileBookOffersNextAndPrevious()            // chapterBook loaded → isCommandAvailable(COMMAND_SEEK_TO_NEXT) true; next → engine chapter step
@Test fun availableCommandsChangeIsAnnounced()             // listener added through the session gets onAvailableCommandsChanged when a book loads
@Test fun seekToIndexRoutesToTheQueue()                    // music: seekTo(3, 0) → playQueueItem position of item 3
// PlaybackCallbackTest
@Test fun playbackResumptionReturnsTheEngineItems()
// PlayerViewModelTest
@Test fun tappingAQueueRowPlaysIt()
```

- [ ] **Step 2: Run to verify they fail.** Run: `./gradlew :core:player:testDebugUnitTest :app:testDebugUnitTest --tests 'app.granger.player.*'`. Expected: compile failure.
- [ ] **Step 3: Implement.** The chapter title is set with `player.replaceMediaItem(index, current.buildUpon().setMediaMetadata(…).build())` from the engine's chapter check, only when the chapter changes. If `replaceMediaItem` re-prepares the item in the test (playback state leaves READY or the position jumps), stop and report: the fallback is `SessionPlayer.getMediaMetadata` with announced metadata changes, decided with Neal's reviewer before going on.
- [ ] **Step 4: Run to verify they pass.** Same command, then all of `:core:player:testDebugUnitTest`. Expected: PASS. Then, with the phone free, `./gradlew :core:player:connectedAndroidTest` (installs only its own APK; check `adb shell pm list packages app.granger` afterwards). Expected: `GaplessDeviceTest` 2/2.
- [ ] **Step 5: Commit**

```bash
git add core/player app/src/main/kotlin/app/granger/player app/src/test/kotlin/app/granger/player
git commit -m "Media M3: chapter titles and book next/previous in the session, queue tap-to-play, playback resumption"
```

### Task 7: The artwork provider

**Files:**
- Create: `app/src/main/kotlin/app/granger/media/ArtworkProvider.kt`, `Artwork.kt` (token ↔ path, the allowed roots)
- Modify: `app/src/main/AndroidManifest.xml` (provider `app.granger.artwork`, exported, no permission, `grantUriPermissions="false"`)
- Modify: `core/player/src/main/kotlin/app/granger/core/player/PlaybackService.kt` (`Dependencies` gains `artwork: (String) -> Uri`, default `Uri.fromFile`), `PlayerEngine.kt` (`item()` uses it)
- Modify: `app/src/main/kotlin/app/granger/GrangerApp.kt` (wires `Artwork::uri`)
- Test: `app/src/test/kotlin/app/granger/media/ArtworkProviderTest.kt`, `ArtworkTest.kt`

**Interfaces:**
- Produces:
  ```kotlin
  class Artwork(private val roots: () -> List<File>) {   // filesDir/media-covers + every volume's music/ and audiobooks/
      /** content://app.granger.artwork/<token> for a cover under a root, else null. */
      fun uri(coverPath: String): Uri?
      /** The file a token names, if it's an image under a root (canonical path checked), else null. */
      fun file(token: String): File?
  }
  ```

- [ ] **Step 1: Write the failing tests.**

```kotlin
// ArtworkTest
@Test fun coverUnderARootRoundTrips()
@Test fun pathsOutsideTheRootsHaveNoUri()
@Test fun dotDotAndSymlinksOutAreRefused()
@Test fun onlyImageNamesAreServed()                 // .granger-item.json, .mp3, .part → null
// ArtworkProviderTest
@Test fun openFileReadsTheCover()
@Test fun writeModesAreRefused()                    // "w", "rw", "wt" → SecurityException or FileNotFoundException
@Test fun queryInsertUpdateDeleteDoNothing()
@Test fun getTypeIsTheImageType()
```

- [ ] **Step 2: Run to verify they fail.** Run: `./gradlew :app:testDebugUnitTest --tests 'app.granger.media.Artwork*'`. Expected: compile failure.
- [ ] **Step 3: Implement.** Reuse the canonical-path check pattern of `MediaDocumentsProvider`. The token is URL-safe Base64 (no padding) of `<root index>/<relative path>`.
- [ ] **Step 4: Run to verify they pass.** Same command, then all of `:app:testDebugUnitTest` and `:core:player:testDebugUnitTest`. Expected: PASS.
- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/app/granger/media/Artwork*.kt app/src/main/AndroidManifest.xml core/player app/src/main/kotlin/app/granger/GrangerApp.kt app/src/test/kotlin/app/granger/media
git commit -m "Media M3: a read-only artwork provider for the session, Android Auto and the widget"
```

### Task 8: Android Auto: browse tree, search, playing from Auto

**Files:**
- Create: `core/player/src/main/kotlin/app/granger/core/player/MediaBrowseTree.kt` (interface)
- Modify: `core/player/src/main/kotlin/app/granger/core/player/PlaybackService.kt` (`LibraryCallback`: `onGetLibraryRoot`, `onGetChildren`, `onGetItem`, `onSearch`, `onGetSearchResult`, `onSetMediaItems`, `onAddMediaItems`; `SessionPlayer.addMediaItems` no-op for the engine's own items (Task 6 already made `setMediaItems`/`setMediaItem` no-ops for them, for resumption); `LibraryCallback` is already an `internal class LibraryCallback(engine, scope)` with `onPlaybackResumption` (Task 6), so it gains the tree as a constructor argument; `Dependencies` gains `browse: MediaBrowseTree?`)
- Create: `app/src/main/kotlin/app/granger/media/AutoBrowseTree.kt`
- Create: `app/src/main/res/xml/automotive_app_desc.xml`; Modify: `app/src/main/AndroidManifest.xml` (car meta-data)
- Modify: `app/src/main/kotlin/app/granger/GrangerApp.kt` (`MediaControllerPlayerHub` installs the tree)
- Test: `app/src/test/kotlin/app/granger/media/AutoBrowseTreeTest.kt`; `core/player/src/test/kotlin/app/granger/core/player/PlaybackCallbackTest.kt`

**Interfaces:**
- Produces:
  ```kotlin
  interface MediaBrowseTree {
      fun root(): MediaItem
      /** Null for an unknown id. [page] and [pageSize] as Auto asks. */
      suspend fun children(parentId: String, page: Int, pageSize: Int): List<MediaItem>?
      suspend fun item(id: String): MediaItem?
      suspend fun search(query: String): List<MediaItem>
      /** Starts what [id] names through the hub (an empty id with [searchQuery] plays the best hit). False if nothing plays. */
      suspend fun play(id: String, searchQuery: String? = null): Boolean
      /** Appends what [id] names to the music queue (an audiobook or recent, or anything with no music loaded, plays instead). */
      suspend fun add(id: String): Boolean   // added in review: Auto's add appends
  }
  class AutoBrowseTree(
      media: MediaLibrary, playlists: Playlists, playback: PlaybackDao, index: MediaIndex,
      resumer: PlayResumer, songs: SongPlayer, hub: PlayerHub, artwork: Artwork,
  ) : MediaBrowseTree
  ```
- Items: browsable folders set `isBrowsable = true, isPlayable = false, mediaType` (`MEDIA_TYPE_FOLDER_ALBUMS`, `_ARTISTS`, `_PLAYLISTS`, `_AUDIO_BOOKS`); albums and audiobooks get `MediaConstants.EXTRAS_KEY_CONTENT_STYLE_BROWSABLE/PLAYABLE = CONTENT_STYLE_GRID`; every item with a cover sets `artworkUri` from `Artwork.uri`.
- `onSetMediaItems(session, controller, items, startIndex, startPositionMs)`: `browse.play(items[startIndex].mediaId, items[startIndex].requestMetadata.searchQuery)`, then waits (at most 5 s) until the engine's `ui` shows the requested item loaded, and returns the engine's items, index and position. On failure, a failed future (`SessionError.ERROR_BAD_VALUE`). *(As built: the wait is `PlayerEngine.loadedItems()`, a call that runs in turn after the hub call the tree made and answers with the engine's items, index and position, under a 5 s timeout; a failure is a failed future. `MediaControllerPlayerHub` takes the tree as a provider, `browse: (() -> MediaBrowseTree)?`, to break the hub ↔ tree cycle; `Dependencies.browse` stays `MediaBrowseTree?`.)*

- [ ] **Step 1: Write the failing tests.**

```kotlin
// AutoBrowseTreeTest (FakeMediaLibrary, FakePlaylists, FakePlayerHub)
@Test fun rootHasRecentMusicAndAudiobooks()
@Test fun musicHasAlbumsArtistsPlaylists()
@Test fun albumsAreAGridByTitleWithArtwork()
@Test fun albumChildrenAreItsSongsPlayableFromThere() // "album:<key>#<trackId>"
@Test fun artistChildrenAreItsAlbums()
@Test fun audiobooksAreLastPlayedFirst()
@Test fun pagingFollowsPageAndPageSize()
@Test fun missingItemsAreLeftOut()
@Test fun unknownIdIsNull()
@Test fun playingASongPlaysItsAlbumFromIt()
@Test fun playingAnAudiobookResumesIt()              // hub.playAudiobook(id, null)
@Test fun playingARecentResumesIt()                  // PlayResumer
@Test fun voicePlayPicksTheBestHit()                 // "" + "holst" → album; "wicked" → audiobook
@Test fun searchReturnsPlayableHits()
// PlaybackCallbackTest
@Test fun setMediaItemsFromAutoLoadsOnceThroughTheEngine() // engine loads the album once; SessionPlayer.setMediaItems with those items does nothing
@Test fun unknownSetMediaItemsFails()
@Test fun childrenOfAnUnknownParentIsBadValue()
```

- [ ] **Step 2: Run to verify they fail.** Run: `./gradlew :app:testDebugUnitTest --tests 'app.granger.media.AutoBrowseTreeTest' :core:player:testDebugUnitTest`. Expected: compile failure.
- [ ] **Step 3: Implement.** If Media3 1.11.1 has no test-only `ControllerInfo` factory reachable from Robolectric, test the callback's logic through a small internal function that takes the tree and the engine, and leave the callback a thin adapter; say so in the commit message.
- [ ] **Step 4: Run to verify they pass.** Same command, then all of `:app:testDebugUnitTest` and `:core:player:testDebugUnitTest`. Expected: PASS.
- [ ] **Step 5: Commit**

```bash
git add core/player app/src/main/kotlin/app/granger/media/AutoBrowseTree.kt app/src/main/res/xml app/src/main/AndroidManifest.xml app/src/main/kotlin/app/granger/GrangerApp.kt app/src/test
git commit -m "Media M3: Android Auto browse tree, search and playing from Auto, through the engine"
```

### Task 9: The home-screen widget

**Files:**
- Modify: `gradle/libs.versions.toml` (`glance = "1.2.0"`, `androidx-glance-appwidget`, `androidx-glance-appwidget-testing`), `app/build.gradle.kts`
- Create: `app/src/main/kotlin/app/granger/widget/PlayerWidget.kt` (`GlanceAppWidget`), `PlayerWidgetReceiver.kt`, `WidgetModel.kt` (pure), `WidgetActions.kt` (`ActionCallback`s), `WidgetUpdater.kt`
- Create: `app/src/main/res/xml/player_widget_info.xml`; Modify: `app/src/main/AndroidManifest.xml` (receiver), `app/src/main/res/values/strings.xml`
- Modify: `app/src/main/kotlin/app/granger/MainActivity.kt` (an intent extra opens the Player), `GrangerApp.kt` (start the updater with the player)
- Test: `app/src/test/kotlin/app/granger/widget/WidgetModelTest.kt`, `WidgetUpdaterTest.kt`, `PlayerWidgetTest.kt` (`runGlanceAppWidgetUnitTest`)

**Interfaces:**
- Produces:
  ```kotlin
  enum class Secondary { NEXT, BACK, NONE }
  data class WidgetModel(
      val title: String, val subtitle: String, val coverPath: String?,
      val playing: Boolean, val secondary: Secondary,
      val progress: Float?,           // null when nothing is loaded or saved
      val empty: Boolean,             // nothing ever played: "Nothing playing" / "Open Granger"
  ) {
      companion object { fun of(ui: PlayerUi, saved: SavedModes): WidgetModel }
  }
  /** Re-renders the widget when the model changes, ignoring position-only changes. */
  class WidgetUpdater(hub: PlayerHub, scope: CoroutineScope, render: suspend (WidgetModel) -> Unit)
  ```

- [ ] **Step 1: Write the failing tests.**

```kotlin
// WidgetModelTest
@Test fun musicShowsTrackArtistAndNext()
@Test fun bookShowsChapterBookAndBack()
@Test fun nothingLoadedShowsTheSavedPlayer()        // SavedModes(lastMode = AUDIOBOOK, book = …) → book title, playing false
@Test fun neverPlayedIsEmpty()
// WidgetUpdaterTest
@Test fun positionTicksDoNotUpdateTheWidget()      // ui position 1 s → 2 s → no render; play→pause → render
@Test fun switchingPlayersUpdatesTheWidget()
// PlayerWidgetTest (Glance unit test)
@Test fun smallSizeShowsCoverAndPlay()
@Test fun mediumSizeShowsTitleAndSecondary()
@Test fun largeSizeShowsProgress()
@Test fun emptyShowsOpenGranger()
```

- [ ] **Step 2: Run to verify they fail.** Run: `./gradlew :app:testDebugUnitTest --tests 'app.granger.widget.*'`. Expected: compile failure.
- [ ] **Step 3: Implement.** Covers are decoded in-process from `coverPath`, scaled to at most 300 px, for `ImageProvider(bitmap)`. The play action: when nothing is loaded, `hub.activate(saved.lastMode)` (which resumes); else `hub.playPause()`. The secondary action: `next()` or `skipBack()`. The body opens `MainActivity` with the Player extra.
- [ ] **Step 4: Run to verify they pass.** Same command, then all of `:app:testDebugUnitTest`. Expected: PASS.
- [ ] **Step 5: Commit**

```bash
git add gradle/libs.versions.toml app/build.gradle.kts app/src/main/kotlin/app/granger/widget app/src/main/res app/src/main/AndroidManifest.xml app/src/main/kotlin/app/granger/MainActivity.kt app/src/main/kotlin/app/granger/GrangerApp.kt app/src/test/kotlin/app/granger/widget
git commit -m "Media M3: one resizable home-screen widget for whichever player is current"
```

### Task 10: Eval runs on the Pixel 6 (with Neal's OK)

**Files:**
- Create: `tools/eval/runs/checkpoints/m3/NOTES.md` (totals, the comparison with tune-5, what changed)
- Modify: `tools/eval/grades/grades.jsonl` (grades for changed replies only)

- [ ] **Step 1: Ask Neal for the phone** for about 3.5 hours (charger, the debug build installed over the app, nothing of his in front). Back up the library database first, as in M1 and M2.
- [ ] **Step 2: Media set.** From `tools/eval`: `python3 eval.py run dataset/media.json --run-id m3-media-1 --guesses none`, then `python3 eval.py worksheet m3-media-1`, grade per `RUBRIC.md`, `python3 eval.py report m3-media-1`. Expected: media cited ≥ 8/10, intrusion 0/3.
- [ ] **Step 3: Tuning split.** `python3 eval.py run dataset/tune.json --run-id m3-tune-1 --guesses none`, worksheet (only replies whose text changed since tune-5 need grades), report with `--compare` against the tune-5 totals in `runs/checkpoints/tuning/NOTES.md`. Expected: declined ≥ 89%, grounded ≥ 43%.
- [ ] **Step 4: If a bar is missed,** follow Builder's calls (tune the cap and cue list on the tuning split only, re-run; else the Research media flag off and the miss reported). Never the held-out split.
- [ ] **Step 5: Record and commit.** Write `runs/checkpoints/m3/NOTES.md` with both runs' totals and the decision.

```bash
git add tools/eval/runs/checkpoints/m3 tools/eval/grades/grades.jsonl
git commit -m "Media M3: eval on the Pixel 6: media set and the tuning-split regression check"
```

### Task 11: Done-means walkthrough on the Pixel 6 and the Desktop Head Unit; parity checklist, roadmap

**Files:**
- Modify: `docs/superpowers/plans/media-parity-checklist.md` (the ⏭ rows: Android Auto ×2, widget, Search; an "M3 walkthrough (date)" section)
- Modify: `docs/superpowers/plans/2026-10-15-granger-v1-roadmap.md` (M3 → Done; the Media project done; "Carried forward from M3")
- Modify: `tools/dev/MAC-SETUP.md` (the Desktop Head Unit)

- [ ] **Step 1: Install over the existing app** after backing up the library and downloads databases. Check `firstInstallTime` is unchanged and the library intact.
- [ ] **Step 2: Desktop Head Unit setup** (record it in `MAC-SETUP.md`): `sdkmanager "extras;google;auto"`. On the phone (Neal): Android Auto settings → tap the version ten times → developer settings → **Unknown sources** on (Granger isn't from Play) → **Start head unit server**. Then `adb forward tcp:5277 tcp:5277` and run `$ANDROID_HOME/extras/google/auto/desktop-head-unit`.
- [ ] **Step 3: Walk spec §13 and M3's items, with Neal present for anything audible.** Check that Granger is the app in front first. Record pass or fail for each.
  1. **Search:** "Holst" shows the Music group (artist, albums); "comfortably" shows the song, and tapping it plays *The Wall* from "Comfortably Numb"; tapping an album or artist opens its page; "wicked" shows the audiobook and opens its page.
  2. **Research** (debug build): "Do I have any Holst?" cites the Holst album, and tapping the citation opens it; "How far is Mars from the Sun?" cites no media.
  3. **Notification and lock screen:** a book's chapter title shows and changes at the chapter boundary with no break in sound (Neal listens across a file change in a multi-file book too; the title change replaces the current item, which tests pin only for a single file); next and previous step chapters, on a single-file book too if Neal has one (else covered by tests); music shows the track.
  4. **Queue:** tapping a queue row plays it.
  5. **Resumption:** force-stop Granger, then press play on Bluetooth headphones or a headset (or `adb shell input keyevent KEYCODE_MEDIA_PLAY` with Neal's OK): the last player resumes at its place.
  6. **Widget:** add "Granger player" to the home screen; resize it small → large; it shows the current player, play/pause and the secondary button work, switching players updates it, and after the process is killed (swipe Granger away from Recents, or `adb shell am kill app.granger` with it in the background; not a force-stop, which disables the package's PendingIntents until the app runs again) its play button resumes at the saved place. Also check that the widget picker shows a usable preview.
  7. **Android Auto on the DHU:** Recent, Music and Audiobooks with covers; play an album, a playlist and an audiobook (it resumes at its place); next/previous; the queue; search "holst" and play the hit; switching music ↔ audiobook loses no position.
  8. **The rest of spec §13, once more on this build:** phone media unchanged (md5 of the 91 files), a LibriVox book plays by chapter and resumes after a force-stop, an archive.org album plays gaplessly, audiobook → music → audiobook keeps its place, the mini-player the same everywhere, "Show in other apps" read-only in the system file browser.
- [ ] **Step 4: Fix what fails** (systematic-debugging, each fix with a test), then commit each fix.
- [ ] **Step 5: Update the parity checklist:** Android Auto (music and audiobooks), widget and Search rows ✅ or ❌ with the reason; add the M3 walkthrough table.
- [ ] **Step 6: Update the roadmap:** M3 → Done with the date and the test count; the Media project (spec §1, project 1) done; "Carried forward from M3", including the eval results and anything from Step 3 left open.
- [ ] **Step 7: Commit**

```bash
git add docs/superpowers/plans tools/dev/MAC-SETUP.md
git commit -m "Media M3: Done-means walkthrough on the Pixel 6 and the Desktop Head Unit, parity checklist, roadmap"
```
