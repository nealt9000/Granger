# Plan M2: Media sources and downloads Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** LibriVox audiobooks and archive.org audio (spoken word and music) can be found, downloaded and played. They download into Granger's own storage with a full origin record and land on the Audiobooks and Music shelves. An archive.org album stays one album, whatever its files' tags say. The Library's archive sources are grouped by media type, and, when the user turns it on, Granger's downloaded media show read-only in the Files app and in other apps' file pickers.

**Architecture:**
- **`:core:downloads`** (ledger v3): a new `AUDIO` kind and **download groups**. All the files of one book or album download one at a time, in order, as one WorkManager chain. A group is queued or refused as a whole, by one free-space check on the chosen volume.
- **`:core:archive`**: archive.org audio (a media kind on queries and shelves, the rolling public-domain filter, the choice of audio files) and a **`LibriVoxClient`** for the LibriVox catalog API. Every LibriVox file comes from the book's archive.org item, so the client lives here too.
- **`:core:library`**: an album key for "one origin item" (`Albums.itemKey`), `MediaLibrary.forget` for removed downloads, and playlist rows that keep their entry ordinals. No schema change; the library stays at v7.
- **`:core:media`**: a per-item **manifest** (the origin record) and a **`DownloadedMediaScanner`**. It turns manifests and finished files into `ScanResult`s for `MediaLibrary.sync(LIBRIVOX, …)` and `sync(ARCHIVE_ORG, …)`. It needs no audio permission.
- **`:app`**: `MediaDownloader` (get, cancel, remove, filing on install), a LibriVox source screen, archive.org audio views in the existing Archive.org screen, sources grouped by media type, and a read-only `DocumentsProvider` behind a Settings switch.

**Tech Stack:** Kotlin 2.4.21, coroutines 1.11.0, Room 2.8.5 (downloads DB AutoMigration 2→3), WorkManager 2.12.0 (unique work chains), OkHttp 5.5.0, kotlinx.serialization 1.11.0, Compose Material 3, Media3 1.11.1 (unchanged), TagLib through `:core:taglib` (unchanged), Android `DocumentsProvider`, Robolectric 4.17, JUnit 4 + Truth. No new dependencies.

**Spec:** `docs/superpowers/specs/2026-10-19-media-design.md` §3.2–§3.4, §4, with §5 (filing), §10 (origin records, ids), §11 (free), §12 (user agent, pacing, 429) and §13 (Done means). Roadmap: `docs/superpowers/plans/2026-10-15-granger-v1-roadmap.md` (the M2 row and "Carried forward from M1"). Parity: `docs/superpowers/plans/media-parity-checklist.md`. Earlier plans this builds on: `2026-10-17-plan-07-archive-org.md` (archive.org client, waiver, sha1/md5) and `2026-10-16-plan-03-catalog-downloads-readiness.md` (WorkManager, resume, Wi-Fi only, volumes).

## How Media is split

| Plan | Delivers | Spec sections |
|---|---|---|
| M1 (done) | Phone media, both players, mini-player, browsing, playlists, Recent plays | §2, §3.1, §5, §6, §7, §8, §10 (ids, playlists function, second-stream readiness) |
| **M2 (this plan)** | LibriVox, archive.org audio with the rolling filter, sources by media type, media downloads with origin records, "Show in other apps" | §3.2–§3.4, §4, the M2 items of §13 |
| M3 | Media index in Search, Research media passages + eval check, Android Auto, widget, final Done-means walkthrough | §6/§7 Auto and widget rows, §9, §13 |

M3 is written after M2 merges, against the code M2 actually produced. M2 schedules nothing from M3: no Search, Research, Android Auto or widget work.

## API assumptions (checked by hand on 2026-10-19, recorded as fixtures in Task 1)

The repo had no notes or fixtures about LibriVox. This plan is designed against the following, which a quick read-only look at the live APIs confirmed. Task 1 records them as fixtures before any code is written. If a recording differs, fix this section and the affected task before going on.

- **LibriVox catalog:** `GET https://librivox.org/api/feed/audiobooks/?format=json` with `id=<digits>`, `title=<text>` (`^` anchors at the start; LibriVox drops leading articles, so `^time machine` finds *Time Machine* and `^the time machine` finds nothing), `author=<last name>`, `genre=<name>`, `extended=1` (sections and readers), `coverart=1`, `limit`, `offset`, `fields={a,b}`, `sort_field=id&sort_order=desc` (newest first; `since=` does *not* sort by date).
  - `genres`, `url_iarchive`, `coverart_*` and `sections` come only with `extended=1`. A `fields` list that names `genres` without `extended=1` silently returns none (recorded 2026-10-19), so search results carry no genres.
  - An unknown `genre=` name is HTTP 500 with an empty body, not a 404. The six genre names in Builder's calls all answer 200.
  - The newest books can be catalogued before their archive.org item exists: `url_iarchive` "", `totaltime` "", `totaltimesecs` 0 (ids 23006–23008 on 2026-10-19). They read as `NotOnArchive`.
  - Book: `id`, `title`, `description` (HTML), `language`, `copyright_year`, `num_sections`, `totaltime` ("4:09:29"), `totaltimesecs` (number), `authors[{first_name,last_name}]`, `genres[{name}]`, `url_librivox`, `url_iarchive` (`https://www.archive.org/details/<identifier>`), `url_zip_file`, `coverart_jpg`/`coverart_thumbnail` (in a *separate* `LibrivoxCdCoverArt*` item), and `sections[]`.
  - Section: `id`, `section_number` (may start at 0), `title`, `listen_url` (`https://www.archive.org/download/<identifier>/<name>_64kb.mp3`), `playtime` (seconds, as a string), `file_name` (often null), `readers[{display_name}]`.
  - **No results is HTTP 404** with `{"error":"Audiobooks could not be found"}`. It must read as an empty page, not an error. Busy is HTTP 429 (spec §12).
- **A LibriVox book's archive.org item** (e.g. `time_machine_0805_librivox`): formats `64Kbps MP3` (derivative, `length` "19:32"), `VBR MP3` (the ~128 kbps originals, `length` "1172.32", `track` "1/13"), `Ogg Vorbis`, plus one `JPEG` cover (`Time_Machine_1112.jpg`) and an `Item Tile`. The VBR MP3s also carry a per-file `title` ("Chapter 01") and `album` ("The Time Machine"), and one `track` is a bare "7". Every file but `<identifier>_files.xml` has `sha1`, `md5` and `size`.
- **Archive.org audio items** (e.g. the Holst item): `mediatype` "audio", `collection` a string or a list, `files[]` with `format` ("VBR MP3", "Ogg Vorbis", "Flac", "<n>Kbps MP3", "PNG" waveforms, "Spectrogram", "Columbia Peaks"), `track` ("01", "1/13"), `length` (seconds or "mm:ss"), and sometimes `title`/`album`/`creator`. The Holst item's seven MP3 files have `track` 01–07 and **no `title` or `album`**. The item's title is its identifier, and its `date` is the 2025 upload date.
- **Archive.org search for audio:** music is `mediatype:audio AND collection:audio_music` (52,199 items with `year:[* TO 1925]`, 52,107 with `year:[1860 TO 1925]`). Spoken word is `mediatype:audio AND collection:audio_bookspoetry AND NOT collection:librivoxaudio` (428 with the filter, 53,721 without). Some items have **`year` 0** (e.g. `BennyBell`), which `[* TO 1925]` lets through.

## Decisions made with Neal (2026-10-19)

- **An album downloaded from archive.org stays one album in the library, whatever its embedded tags.** It's grouped by its origin item, with album and artist from the item's metadata. Real case: `holst-the-planets-holst-london-symphony-orchestra-1922-1924`, six untagged MP3s plus "Venus", whose album tag is its own title. A folder-merge rule for phone files was declined; it would merge unrelated singles in folders like `Download/`.
- Downloaded media are files Granger owns, inside its storage (spec §4). Phone media stays read-only and referenced in place.
- "Show in other apps" is off by default and read-only (spec §4, Claude's calls accepted by Neal). Music players that only read the shared media list won't list these files. Neal accepted that trade-off over copying files into shared storage.
- Rolling public-domain filter for archive.org music (spoken word: see below): before January 1 of (current year − 100) until the waiver. The waiver is the books one, and accepting it lifts the filter for audio too. Uploader licence tags are not trusted (spec §3.3).
- No public-domain filter for LibriVox (spec §3.2).
- Everything in Media is free (spec §11).
- **No storage cap** (Neal, 2026-10-19). There's no space limit except the phone's own storage. Media downloads are checked only for free space on the chosen volume (the existing `NoSpace` path). M2 adds no cap logic and doesn't touch the existing cap plumbing for packs and books (`Long.MAX_VALUE` in practice); removing it is left for later. This replaces spec §4's "do not count toward the storage cap".
- **A slug title reads from the file names** (Neal accepted Claude's improvement). When an archive.org item's `title` equals its identifier, the album or book title is the longest common prefix of its audio files' names: extensions dropped, cut back so it never ends inside a word, trailing " ", "_", "-", ":" and "." trimmed. It's used when it's at least 3 words or 12 characters and the item has at least two audio files. Otherwise the identifier reads as words, as before. A real title is never replaced. The Holst item, whose files are named "The Planets Op. 32, H. 125_ I. Mars, The Bringer of War.mp3" and so on, becomes "The Planets Op. 32, H. 125" (`MediaManifests.displayTitle`, Task 6). The Holst item still needs the waiver: its `date` is the 2025 upload date, so the rolling filter hides it, and since the filter reads `year` it will keep hiding it after 2027 too. It's tested with the waiver accepted.
- **Archive.org spoken word shows nothing before the waiver** (Claude's recommendation, accepted). For spoken-word items, archive.org's `year` is often the date of the *text*, not of the recording: a modern reading of an 1888 book is dated 1888 (seen on 2026-10-19: `theophane_venard_vie-et-correspondance_…`). That's the same failure as the books filter's licence tags in Plan 7. So before the waiver, Audiobooks → Archive.org runs no search and shows only a note that points to **LibriVox** and offers **Show everything** right there (Plan 7's waiver dialog and text, unchanged). After accepting, the results show. The switch is `ArchiveKind.SPOKEN_WORD.datedRecordings = false` (Task 3). This replaces spec §3.3's rolling filter for spoken word; music keeps it.
- **LibriVox downloads are 64 kbps** (`*_64kb.mp3`), with no setting. *The Time Machine* is about 120 MB at 64 kbps (240 MB at 128 kbps), and 64 kbps is what LibriVox itself streams.
- **Cancel on a half-downloaded book keeps the chapters already downloaded.** The book stays on the shelf ("5 of 13 chapters"), and Get fetches the rest. Remove deletes everything.
- **LibriVox shelves are Recently added plus six genres.** No "Most downloaded" shelf: the LibriVox API has no popularity or download counts, and one built from archive.org's `librivoxaudio` collection was declined.
- Same lending rules as books: "Borrow on archive.org", no lending accounts, never `private` or LCP files.

## Builder's calls in this plan (for Neal's review)

- **LibriVox code lives in `:core:archive`** (`LibriVoxClient.kt`, `LibriVoxModels.kt`, `LibriVoxOffers.kt`), not a new module. Every LibriVox download is an archive.org item, so it shares the item client, the file checks and the JSON helpers.
- **LibriVox browsing:** a search box plus fixed shelves, as in Plan 7 (decided with Neal: no "Most downloaded"). The shelves are Recently added (`sort_field=id&sort_order=desc`), Fiction (`genre=General Fiction`), Poetry, Children's (`Children's Fiction`), Science fiction, Mystery & detective (`Detective Fiction`) and Philosophy. Task 1 checked each genre name against the API: all six answer HTTP 200. The search box asks by title (`title=<text>`) and by author (`author=<last word>`), then merges, title matches first, deduplicated by id.
- **LibriVox requests are paced** at least 1 s apart (one `Mutex` in the client). A 429 shows "LibriVox is busy right now. Try again in a minute." with Retry. The user agent goes to `librivox.org` as well as archive.org hosts (`ArchiveUserAgent.isArchiveHost` is extended).
- **LibriVox files** (64 kbps, decided with Neal): each section's file is the basename of its `listen_url`, which must be a usable file in the item named by `url_iarchive` (verified by its sha1, else md5). If any section's file is missing there, the book isn't offered ("This book's files aren't all on archive.org."). The cover is the largest usable `JPEG` in that item, else its `Item Tile`. `coverart_jpg` is not used: it lives in another item and would need a second metadata fetch to verify.
- **Archive.org queries by media type** (`ArchiveKind`): texts as today; spoken word `mediatype:audio AND collection:audio_bookspoetry AND NOT collection:librivoxaudio` (LibriVox books have their own source, so they aren't listed twice); music `mediatype:audio AND collection:audio_music`. The shelves:
  - Spoken word: Most downloaded, Poetry (`subject:poetry`), Drama (`subject:(drama OR plays)`). They're searched only after the waiver (see Decisions).
  - Music: Most downloaded, 78 rpm records (`collection:78rpm`), Classical (`subject:classical`), Folk & field recordings (`subject:(folk OR "field recordings")`).
- **Rolling filter range** (music only; spoken word shows nothing before the waiver): `year:[1860 TO <current year − 101>]`, which is `year:[1860 TO 1925]` in 2026. The lower bound drops items dated 0 (seen live). 1860 is the oldest recorded sound. The same rule is checked again at Get from the item's metadata (`ArchiveOffers.isPublicDomainRecording`), and undated items fail it.
- **Archive.org audio format preference:** VBR MP3; then CBR MP3, highest bitrate first (320, 256, 192, 160, 128); then Ogg Vorbis, Flac, 24bit Flac; then 96 and 64 kbps MP3. All the item's usable files in the first format that has any become the tracks. They're ordered by `track` when every file has one, else in natural name order. The cover is the largest usable `JPEG`, else the `Item Tile`. Per-track `PNG`s (waveforms) and spectrograms are never covers.
- **Which shelf an archive.org item goes to** follows the view it was got from: Archive.org under Audiobooks gives an audiobook, under Music an album. The user can move it (existing Library move).
- **Album identity:** an archive.org album's tracks all get `albumKey = Albums.itemKey("archive-org", identifier)`. `album` is the item's title (a slug title reads from the file names, else as words; see Decisions) and `albumArtists` is the item's creators. A track's own title comes from archive.org's per-file `title`; else from the file's own TITLE tag (read once, on filing); else from the file name without its extension. Tags never decide grouping.
- **Ids** (spec §10, consistent with `book:<origin>:<ref>` and M1):
  - LibriVox: `audiobook:librivox:<librivox id>` and `track:librivox:<librivox id>:<section id>`.
  - Archive.org: `audiobook:archive-org:<identifier>` and `track:archive-org:<identifier>:<first 16 hex of sha1(remote file name)>`.
  - None contains `@`, so `MediaAddress` works unchanged.
- **Storage layout** (spec §4): `<volume>/audiobooks/librivox/<librivox id>/`, `<volume>/audiobooks/archive-org/<identifier>/` and `<volume>/music/archive-org/<identifier>/`, beside `packs/` and `books/`.
  - Folders are named by the stable ref. The Files app shows the item's display title instead (see below).
  - Local file names are `<NN> - <title>.<ext>`: two digits minimum, characters outside `[A-Za-z0-9 ._,'()&!\[\]-]` replaced by `_`, at most 120 characters. They're safe on FAT/exFAT SD cards and readable in the Files app. The cover is `cover.jpg`.
  - Each media root gets an empty `.nomedia`, so the system media list never indexes Granger's files (the phone scan already skips `Android/`).
- **Origin record (spec §4, §10):** each item folder holds `.granger-item.json` (`MediaManifest`): source, LibriVox id or identifier, archive.org identifier, page URL, title, creators, readers, description, language, genres, date, total time, and, per file, its local name, remote name, URL, size, checksum and algorithm. It's written before the download is queued, lives with the files, and is what the scanner files from. The downloads ledger mirrors it per file.
- **Tags of downloaded files** are read once, by the scanner, when the file is first filed: duration, ReplayGain, title and embedded cover. They're cached in the manifest (`CachedTags`), so later scans never open the files. They're read through the existing `TagReadGuard`, so a file that crashes TagLib is skipped next time, as for phone files.
- **Download groups:** all files of one item share `groupId` `media:<source>:<ref>` and run as one WorkManager unique chain (`APPEND_OR_REPLACE`), one file at a time, cover first, then in track order.
  - One file's failure doesn't stop the chain: a grouped worker reports success to WorkManager whatever its record says, and the record shows the failure with Retry.
  - Queueing checks space for the group's total once: everything is queued or nothing.
  - File item ids are `<groupId>/<NNN>`, which never start with `archive-org:`, so Plan 7's archive book code never sees them.
- **A book or album is filed as soon as one of its files is in.** Its chapters (or tracks) are the files present, in order. Chapters are added as they arrive, and a playing book picks them up the next time it's loaded. The book page and the source sheet say "Downloading: 3 of 13 chapters".
- **Remove** forgets the catalog rows (`MediaLibrary.forget`), then removes the group's files and records, then deletes the item folder. Positions, bookmarks, playlist entries and the user's shelf placement stay, so getting it again brings everything back. **Cancel** keeps finished files (decided with Neal). A partly downloaded item that isn't downloading shows `ItemStatus.Partial(done, total)` and offers "Get the rest".
- **Readiness** shows one row per media item (its files' sizes summed). Verify checks every file and Repair re-downloads only the damaged ones.
- **Show in other apps:** one `DocumentsProvider` root, "Granger", with **Music** and **Audiobooks** folders. Item folders are shown by their manifest's display title ("The Planets Op. 32, H. 125", "Time Machine"), files by their local names, plus `cover.jpg`.
  - Dotfiles (`.granger-item.json`, `.nomedia`) and `*.part` files are never listed.
  - No document advertises write, delete, rename or move. `openDocument` refuses any mode but `"r"`.
  - The provider component is disabled in the manifest and enabled by the Settings switch (`PackageManager.setComponentEnabledSetting`, `DONT_KILL_APP`), so with the switch off no other app can see or query it.
  - Document ids are `<volume key>:<relative path>`, where the volume key is the storage volume's UUID or `primary`.
- **When downloaded media are scanned:** on app start (with the phone scan), after each finished media file, after a removal, and from Settings' "Rescan now". The scan needs no audio permission.
- **Phone cover sweep** (from M1's carried-forward list): after a phone scan in which every source listed, cover files in `media-covers` that no track or audiobook row references are deleted. Downloaded covers live in their item folders and go with them, so they never orphan.

## Carried forward from M1: taken into M2, and left

Taken, because M2 touches the same code:
- Orphaned cover files in `media-covers` are never cleaned up → the phone cover sweep (Task 6).
- "A playlist row's position is its entry ordinal, which is right only while every entry has a track row." M2 deletes rows for removed downloads, so playlists now carry entry ordinals (Task 5).
- "Settings scrolls to Music & audiobooks by a fixed item index (5)." M2 adds rows above it, so it scrolls by key (Task 11).
- Parity "cover replacement for audiobooks": LibriVox and archive.org downloads bring their own covers (Tasks 4, 6). Picking a cover by hand stays open (row stays ❌).
- Device checks: resume after a reboot (with a LibriVox book, Neal reboots by hand), and deleting then restoring a phone file in the Files app (Task 12).
- Scan and sync interaction for the new origins: `LIBRIVOX` and `ARCHIVE_ORG` are synced only by `DownloadedMediaScanner`, never by the phone scanner. Losing the audio permission never touches them (Review Focus 4).

Left for M3 or later, with the reason:
- Queue tap-to-play, `onPlaybackResumption`, chapter metadata in the notification: M3, with the service work for Android Auto.
- `MediaControllerPlayerHub`/`PlaybackService` automated tests, the focus content type on mode switch, replaying a just-ended book, the finished flag after restore, the duplicated chapter-lookup rule, the 6 s worst case: player work, unchanged by M2.
- The TagLib skip list never resetting, the audio permission gating picked folders, null cursors from SAF providers: phone-scanner behaviour M2 doesn't change. The downloaded scanner uses its own guard file.
- Remove the unused storage-cap plumbing (`capBytes`, `OverCap`, `capped`): Neal dropped the cap on 2026-10-19.
- Original dates, folder exclusion, sort options for other tabs, "Add to playlist" in the player and queue, drag reorder, move audiobook from the shelf, collection-scoped album/artist pages, the "songs" Recent row, UI tests for music screens, feedback after adding a bookmark, a signal for a lost place: media polish after M3.
- Notification, Bluetooth, ReplayGain, equalizer and decoder checks one by one; SD cards (the Pixel 6 has no slot): device checks M2 doesn't affect, beyond noting SD behaviour for downloads.
- `gradle.properties` still has the Linux limits: builds pass on the Mac as they are. Raising them is housekeeping, not M2.

## Global Constraints

- `minSdk 29`, `compileSdk 37`, `targetSdk 36`, Java 17, as in every module today.
- License: the app is GPLv3. M2 adds no dependency. Don't add one with an incompatible license.
- **Everything in Media is free:** no Pro or entitlement checks anywhere in this plan. Media downloads are kind `AUDIO`.
- **No storage cap:** media downloads are checked only for free space on the chosen volume (`NoSpace`). Add no cap logic (Decisions).
- **Phone media stays read-only.** Nothing in M2 writes, renames or deletes a `content://` or SAF URI. Granger writes and deletes only inside its own media folders (`<volume>/music`, `<volume>/audiobooks`).
- **Network** (reuse Plans 3 and 7; nothing new):
  - Every download goes through `Downloads` and `DownloadWorker` (WorkManager, `NetworkType.UNMETERED` when "Wi-Fi only" is on, the default; `CONNECTED` otherwise), with Range resume and sha1/md5 verification.
  - The shared `OkHttpClient` in `AppContainer` and its `ArchiveUserAgent` network interceptor are used for every request.
  - Never skip verification: a file without sha1 or md5 isn't offered.
  - Never download a `private` file or an `LCP Encrypted` one.
  - Identifiers must pass `ArchiveQuery.isValidIdentifier`, and LibriVox ids must match `[0-9]+`, before any URL or path is built.
- Tests use recorded fixtures (Task 1) served by an OkHttp `Interceptor` fake, as in `core/archive`'s tests. **No test touches the network.** Earlier plans have no live smoke-test pattern, so M2 adds none.
- Ids and paths are exactly as in "Builder's calls". Collection names (exact): "LibriVox", "Archive.org", from the existing `Filing.MEDIA_COLLECTIONS`, stored as `audiobooks:librivox`, `audiobooks:archive-org` and `music:archive-org`.
- Copy (exact):
  - Music filter note: "Showing public-domain recordings only: published before 1926." (the year is computed: current year − 100), plus **Show everything**.
  - Spoken-word note before the waiver (no results): "Archive.org dates spoken-word recordings by their text, not the recording, so Granger can't tell which are in the public domain. LibriVox recordings all are." plus **LibriVox** and **Show everything** (Plan 7's waiver dialog).
  - Spoken-word Get before the waiver: "Show everything to download archive.org spoken word."
  - Audio not-public-domain notice at Get: "This recording wasn't published before 1926. Show everything to download it."
  - LibriVox footer: "LibriVox recordings are in the public domain. Files are served by the Internet Archive."
  - The Plan 7 waiver, unfiltered note and archive.org disclaimer are reused unchanged.
  - Settings switch "Show in other apps", supporting text: "Granger's downloaded music and audiobooks appear in the Files app and in other apps' file pickers, read-only. Music players that only read the phone's media list won't list them."
- The disk on the Mac is case-insensitive: no two files or classes whose names differ only in case (M1 renamed `BookMark` for this).
- Builds run on the Mac Studio in `~/Developer/Granger/Granger` (`tools/dev/MAC-SETUP.md`). Never work in the stale copy under `~/Desktop`.
- Device tests:
  - Never run `:app:connectedDebugAndroidTest`: it reinstalls the app and wipes Neal's ~25 GB library.
  - Library-module device tests (`:core:taglib:connectedAndroidTest`, `:core:player:connectedAndroidTest`) install only their own test APKs. Run them freely, but check with `adb shell pm list packages app.granger` that `app.granger` is still installed afterwards. M2 adds no device tests of its own.
  - Never touch the phone while one of Neal's personal apps is in front. Never `adb reboot` (the phone locks behind a screen only Neal can pass).
- Pushing to GitHub needs Neal's OK at that step.

## Review Focus

1. **The Holst album, untagged files plus one whose album tag is its own title.** Expected: one album of seven tracks, in track order, titled "The Planets Op. 32, H. 125" (from its file names, since its title is a slug) and credited to Gustav Holst. Venus keeps its own track title. Pinned in Task 6 (`archiveAlbumIsOneAlbumWhateverItsTags`) and Task 7 (`holstFixtureDownloadsAsOneSevenTrackAlbum`).
2. **The process dies between a verified file and its filing, or mid-group.** Expected: the next start's scan files what's on disk, the chain resumes from the `.part` file, and one failed file doesn't stop the others. Pinned in Task 6 (`startupScanFilesAFileInstalledBeforeProcessDeath`) and Task 2 (`failedFileInAGroupLetsTheRestContinue`).
3. **The rolling filter at its edges:** year 0, undated, December 31 to January 1, the waiver withdrawn between browsing and Get, and spoken word before the waiver. Expected: year 0 and undated hidden; the cutoff moves on January 1; Get re-checks with the live preference; spoken word shows only the note and refuses Get until the waiver. Pinned in Task 3 (`rollingCutoffMovesEachJanuary`, `yearZeroAndUndatedAreHidden`, `filteredSpokenWordIsNeverSearched`) and Task 8 (`filteredAudioGetRefusesARecordingAfterTheCutoff`, `spokenWordBeforeTheWaiverShowsOnlyTheNote`, `filteredSpokenWordGetIsRefused`).
4. **The audio permission is denied or revoked.** Expected: downloaded media stay OK and playable. The phone scan marks only `PHONE` rows missing. Pinned in Task 6 (`downloadedMediaIsFiledWithoutTheAudioPermission`).
5. **"Show in other apps" is truly read-only and shows only finished files.** Expected: write modes refused, no write/delete/rename flags, no `.part`, manifest or `.nomedia` listed, nothing visible while the switch is off. Pinned in Task 11 (`openForWriteIsRefused`, `noDocumentAdvertisesWriteOrDelete`, `partFilesAndHiddenFilesAreNotListed`, `switchOffDisablesTheProvider`).
6. **Remove, then get again.** Expected: no MISSING ghost on the shelf after Remove; after getting it again, the same id, position, bookmarks, playlist entries and the user's collection. Pinned in Task 5 (`forgetKeepsStatesBookmarksAndPlaylistEntries`) and Task 7 (`removeThenGetAgainRestoresPositionAndPlacement`).

---

### Task 1: Record LibriVox and archive.org audio fixtures; roadmap row

**Files:**
- Create: `core/archive/src/test/resources/librivox-search-time-machine.json`, `librivox-book-817.json`, `librivox-recent.json`, `librivox-not-found.json`, `item-librivox-time-machine.json`, `item-holst-planets.json`, `search-audio-music.json`, `search-audio-spoken.json`, `item-audio-lending.json`
- Modify: `docs/superpowers/plans/2026-10-15-granger-v1-roadmap.md` (M2 row → "Written: `2026-10-19-plan-m2-media-sources.md`")

**Interfaces:**
- Produces: the fixtures that Tasks 3, 4, 7, 8 and 9 read. Later tasks copy the ones they need into `app/src/test/resources/archive/`, as Plan 7 did.

- [ ] **Step 1: Record the LibriVox fixtures with curl.** Send Granger's user agent, `-A "Granger/<versionName> (+https://github.com/nealt9000/Granger)"`, and wait 1 s between calls.
  - `librivox-search-time-machine.json`: `https://librivox.org/api/feed/audiobooks/?title=%5Etime%20machine&format=json&limit=5&fields=%7Bid,title,authors,language,totaltime,totaltimesecs,num_sections%7D`
  - `librivox-book-817.json`: `…/?id=817&extended=1&coverart=1&format=json`
  - `librivox-recent.json`: `…/?format=json&limit=3&sort_field=id&sort_order=desc&fields=%7Bid,title,authors,totaltimesecs%7D`
  - `librivox-not-found.json`: `…/?title=%5Ezzzqqq&format=json`. Write the body; note the status (expected 404).
  - Then run every genre in Builder's calls once with `limit=1`. Expected: HTTP 200 for each. Replace any name that 404s with the closest genre the API knows, and update Builder's calls.
- [ ] **Step 2: Record the archive.org fixtures.**
  - `item-librivox-time-machine.json`: `https://archive.org/metadata/time_machine_0805_librivox`
  - `item-holst-planets.json`: `https://archive.org/metadata/holst-the-planets-holst-london-symphony-orchestra-1922-1924`
  - `search-audio-music.json` and `search-audio-spoken.json`: `advancedsearch.php` with the Task 3 queries (music filtered; spoken word unfiltered, the only way it's searched, Task 8; `rows=3`, the `fl[]` fields `ArchiveOrgClient.search` sends, plus `mediatype`).
  - `item-audio-lending.json`: the first result of `mediatype:audio AND access-restricted-item:true` (`rows=1`), then its `/metadata/<id>`.
- [ ] **Step 3: Check the assumptions.** Run `python3 -m json.tool` on each file. Then confirm by eye against "API assumptions":
  - the 13 sections of book 817 and the 13 `*_64kb.mp3` files in its item;
  - the Holst item's 7 `VBR MP3` files with `track` 01–07 and no `title`, every name starting "The Planets Op. 32, H. 125_ " (the album title, Task 6);
  - a `JPEG` file in both items.
  Write any difference into "API assumptions" and into the tasks it affects before Task 3.
- [ ] **Step 4: Roadmap.** Point the M2 row at this plan with status "Written".
- [ ] **Step 5: Commit**

```bash
git add core/archive/src/test/resources docs/superpowers/plans/2026-10-15-granger-v1-roadmap.md
git commit -m "Media M2: LibriVox and archive.org audio fixtures; roadmap row"
```

### Task 2: Download groups, the AUDIO kind and ledger v3

**Files:**
- Modify: `core/downloads/src/main/kotlin/app/granger/core/downloads/DownloadRecord.kt`, `Downloads.kt`, `DownloadDatabase.kt`, `DownloadWorker.kt`, `Volumes.kt`
- Modify: `core/downloads/src/test/kotlin/app/granger/core/downloads/TestDb.kt` (`FakeScheduler`), `app/src/test/kotlin/app/granger/DownloadFakes.kt` (`FakeDownloads`)
- Generated: `core/downloads/schemas/app.granger.core.downloads.DownloadDatabase/3.json`
- Test: `core/downloads/src/test/kotlin/app/granger/core/downloads/DownloadManagerTest.kt`, `WorkResultTest.kt`, `DownloadMigrationTest.kt`

**Interfaces:**
- Produces:
  ```kotlin
  enum class ItemKind { PACK, MODEL, BOOK, AUDIO }
  // DownloadRecord gains, after snapshotDate:
  //   @ColumnInfo(defaultValue = "NULL") val groupId: String? = null,
  // DownloadSpec gains, last:  val groupId: String? = null
  interface Downloads {
      // … existing members unchanged …
      /**
       * Queues [specs] (every one with groupId == [groupId]) as one, in list order. Specs whose exact hash is already
       * installed are skipped; one already active is left as it is. One space check covers the bytes still to fetch:
       * everything is queued or nothing. There's no cap: only free space on [volumeRoot] is checked.
       */
      suspend fun enqueueGroup(groupId: String, specs: List<DownloadSpec>, volumeRoot: File, wifiOnly: Boolean): EnqueueResult
      /** Stops the group's unfinished files and deletes their parts; installed files and their records stay. */
      suspend fun cancelGroup(groupId: String)
      /** Stops the group and deletes every file and record in it. */
      suspend fun removeGroup(groupId: String)
      /** Queues the group's failed files again, in order. */
      suspend fun retryGroup(groupId: String)
  }
  interface DownloadScheduler {
      fun schedule(record: DownloadRecord, replace: Boolean = false)
      fun cancel(itemId: String)
      /** Runs [records] one after another, in order, after whatever [groupId] already has queued; [replace] starts the chain afresh. */
      fun scheduleGroup(groupId: String, records: List<DownloadRecord>, replace: Boolean = false)
      fun cancelGroup(groupId: String)
  }
  // DownloadDao gains: @Query("SELECT * FROM downloads WHERE groupId = :groupId ORDER BY itemId") suspend fun forGroup(groupId: String): List<DownloadRecord>
  // DownloadWorker gains KEY_GROUP; fun RunOutcome.toWorkResult(grouped: Boolean): ListenableWorker.Result
  // Volume gains: val musicDir: File get() = File(root, "music"); val audiobooksDir: File get() = File(root, "audiobooks")
  ```
- Behaviour:
  - `WorkManagerScheduler.scheduleGroup` enqueues each record with `enqueueUniqueWork("download-group-$groupId", APPEND_OR_REPLACE, …)`, in order, carrying `KEY_GROUP`. With `replace`, the first uses `REPLACE` and the rest `APPEND`.
  - `cancelGroup` calls `cancelUniqueWork("download-group-$groupId")`.
  - `setWifiOnly` reschedules each group's active records with `scheduleGroup(…, replace = true)`, and ungrouped records as today.
  - A grouped worker returns `success()` for `FAILED` and after `giveUp`, so the chain goes on. The record holds the failure. `RETRY` stays `retry()`: the chain waits through the backoff, which keeps one file at a time.
  - `Volume.grangerBytes` also counts `music` and `audiobooks`.

- [ ] **Step 1: Write the failing tests.**

```kotlin
// DownloadManagerTest
@Test fun groupIsQueuedInOrderAsOneChain()        // enqueueGroup("g", [a, b, c]) → Queued; scheduler.groups == [("g", [a, b, c].itemIds, false)]; every record has groupId "g"
@Test fun groupThatDoesNotFitQueuesNothing()       // free space below the group's total → NoSpace; no records written; nothing scheduled
@Test fun groupSkipsFilesAlreadyInstalled()        // b INSTALLED with the same hash → only a and c queued, in that order
@Test fun cancelGroupKeepsInstalledFiles()         // a INSTALLED, b RUNNING → after cancelGroup: a's record and file stay; b's record and part are gone; scheduler.cancelledGroups == ["g"]
@Test fun removeGroupDeletesEveryFile()            // every record of "g" deleted, files and parts deleted; other groups untouched
@Test fun retryGroupRequeuesFailedInOrder()        // b, c FAILED → QUEUED; scheduleGroup("g", [b, c], replace = false)
@Test fun wifiOnlyChangeReschedulesGroupsAsChains()// setWifiOnly(false) → scheduleGroup("g", active records in order, replace = true)
// WorkResultTest
@Test fun failedFileInAGroupLetsTheRestContinue()  { assertThat(RunOutcome.FAILED.toWorkResult(grouped = true)).isEqualTo(ListenableWorker.Result.success()) }
@Test fun ungroupedFailureStillFails()             { assertThat(RunOutcome.FAILED.toWorkResult(grouped = false)).isEqualTo(ListenableWorker.Result.failure()) }
// DownloadMigrationTest
@Test fun version2RecordsHaveNoGroup()             // createDatabase(…, 2) with one INSTALLED row → runMigrationsAndValidate(…, 3, true); SELECT groupId → NULL
```

- [ ] **Step 2: Run to verify they fail.** Run: `./gradlew :core:downloads:testDebugUnitTest`. Expected: compile failure (`enqueueGroup`, `AUDIO`, `groupId` unresolved).
- [ ] **Step 3: Implement.**
  - `DownloadDatabase`: `version = 3`, add `AutoMigration(from = 2, to = 3)` (one nullable column).
  - `DownloadManager.enqueueGroup` checks only free space, the existing `NoSpace` step: the sum of the specs still to fetch, plus what's already queued on that volume, plus `QueuePolicy.RESERVE_BYTES`, against the volume's free bytes. Add no cap logic. Records are written in one pass, then `scheduler.scheduleGroup`.
  - `FakeScheduler` records `groups` and `cancelledGroups`. `FakeDownloads` gets the four group methods: it records calls and, for `enqueueGroup`, adds QUEUED records for every spec, as `enqueue(spec)` does.
- [ ] **Step 4: Run to verify they pass.** Run: `./gradlew :core:downloads:testDebugUnitTest :app:testDebugUnitTest`. Expected: all PASS, and `3.json` generated.
- [ ] **Step 5: Commit**

```bash
git add core/downloads app/src/test/kotlin/app/granger/DownloadFakes.kt
git commit -m "Media M2: download groups run one file at a time as one chain; AUDIO kind; ledger v3"
```

### Task 3: Archive.org audio: media kinds, the rolling filter, audio offers

**Files:**
- Modify: `core/archive/src/main/kotlin/app/granger/core/archive/ArchiveQuery.kt` (`ArchiveKind`, shelves by kind, `build` with kind), `ArchiveModels.kt` (audio fields), `ArchiveOrgClient.kt` (parse them; `mediatype` in search fields), `ArchiveOffer.kt` (`isPublicDomainRecording`, shared `isUsable`)
- Create: `core/archive/src/main/kotlin/app/granger/core/archive/PublicDomain.kt`, `ArchiveAudioOffer.kt`
- Modify: `app/src/main/kotlin/app/granger/archive/ArchiveScreen.kt` (its shelf chips list `ArchiveShelf.of(ArchiveKind.TEXTS)`, not `entries`, so the books view doesn't gain the audio shelves before Task 8)
- Test: `core/archive/src/test/kotlin/app/granger/core/archive/ArchiveQueryTest.kt`, `ArchiveOrgClientTest.kt`, `PublicDomainTest.kt`, `ArchiveAudioOfferTest.kt`

**Interfaces:**
- Produces:
  ```kotlin
  enum class ArchiveKind(val noun: String, val base: String, val datedRecordings: Boolean) {
      TEXTS("texts", "mediatype:texts", datedRecordings = false),
      // archive.org dates spoken word by its text, so it has no usable recording date: nothing is searched before the waiver (Decisions).
      SPOKEN_WORD("audiobooks", "mediatype:audio AND collection:audio_bookspoetry AND NOT collection:librivoxaudio", datedRecordings = false),
      MUSIC("music", "mediatype:audio AND collection:audio_music", datedRecordings = true),
  }
  enum class ArchiveShelf(val kind: ArchiveKind, val title: String, val clause: String) {
      MOST_DOWNLOADED(TEXTS, …), MANUALS(TEXTS, …), SCIENCE(TEXTS, …), LITERATURE(TEXTS, …),      // unchanged values
      SPOKEN_MOST_DOWNLOADED(SPOKEN_WORD, "Most downloaded", ""), SPOKEN_POETRY(SPOKEN_WORD, "Poetry", "subject:poetry"),
      SPOKEN_DRAMA(SPOKEN_WORD, "Drama", "subject:(drama OR plays)"),
      MUSIC_MOST_DOWNLOADED(MUSIC, "Most downloaded", ""), MUSIC_78(MUSIC, "78 rpm records", "collection:78rpm"),
      MUSIC_CLASSICAL(MUSIC, "Classical", "subject:classical"), MUSIC_FOLK(MUSIC, "Folk & field recordings", "subject:(folk OR \"field recordings\")");
      companion object { fun of(kind: ArchiveKind): List<ArchiveShelf>; fun first(kind: ArchiveKind): ArchiveShelf }
  }
  object PublicDomain {
      const val LAST_BOOK_YEAR = 1930
      const val EARLIEST_RECORDING_YEAR = 1860
      /** Recordings published before January 1 of (current year − 100): 1925 in 2026, 1926 in 2027. */
      fun lastRecordingYear(today: java.time.LocalDate): Int = today.year - 101
      /** TEXTS: "year:[* TO 1930]"; audio: "year:[1860 TO <lastRecordingYear>]". */
      fun clause(kind: ArchiveKind, today: java.time.LocalDate): String
  }
  // ArchiveQuery: PUBLIC_DOMAIN stays (texts). build gains two defaulted parameters:
  fun build(text: String, shelf: ArchiveShelf?, filtered: Boolean, kind: ArchiveKind = ArchiveKind.TEXTS, today: LocalDate = LocalDate.now()): String
  // ArchiveFile gains (defaulted, last): val title: String = "", val album: String = "", val creator: String = "", val track: Int? = null, val lengthMs: Long? = null
  // ArchiveItem gains (defaulted, last): val mediatype: String = "", val collections: List<String> = emptyList()
  // ArchiveDoc gains (defaulted, last): val mediatype: String = ""
  // ArchiveOffers gains: fun isPublicDomainRecording(item: ArchiveItem, today: LocalDate): Boolean
  //   = item.year != null && item.year in EARLIEST_RECORDING_YEAR..PublicDomain.lastRecordingYear(today)
  sealed interface AudioOffer {
      data class Tracks(val files: List<ArchiveFile>, val format: String, val cover: ArchiveFile?, val totalBytes: Long) : AudioOffer
      data object LendOnly : AudioOffer
      data object Nothing : AudioOffer
  }
  object ArchiveAudioOffers {
      val FORMATS: List<String>   // "VBR MP3", "320Kbps MP3", "256Kbps MP3", "192Kbps MP3", "160Kbps MP3", "128Kbps MP3", "Ogg Vorbis", "Flac", "24bit Flac", "96Kbps MP3", "64Kbps MP3"
      fun choose(item: ArchiveItem): AudioOffer
      /** From search formats, before metadata: Tracks with no files when any of FORMATS is listed; LendOnly when restricted; null otherwise. */
      fun preview(doc: ArchiveDoc): AudioOffer?
      fun cover(item: ArchiveItem): ArchiveFile?   // largest usable "JPEG", else "Item Tile"
  }
  ```
- Parsing rules:
  - `track`: "01" → 1, "7" → 7, "1/13" → 1, anything else → null.
  - `length`: "373.16" → 373_160 ms, "19:32" → 1_172_000, "1:02:03" → 3_723_000, anything else → null.
  - `collection` can be a string or a list.
- `build` for an audio kind: `<kind.base>`, then ` AND (<text>)`, then ` AND <shelf clause>`, then, when filtered, ` AND <PublicDomain.clause>`. A filtered audio kind without `datedRecordings` (spoken word) has nothing it may show, so `build` throws `IllegalArgumentException`: the view model never searches it before the waiver (Task 8). A shelf of another kind throws `IllegalArgumentException` too. `datedRecordings` is read only for audio kinds; texts keep Plan 7's filter.

- [ ] **Step 1: Write the failing tests.**

```kotlin
// PublicDomainTest
@Test fun rollingCutoffMovesEachJanuary() {
    assertThat(PublicDomain.lastRecordingYear(LocalDate.of(2026, 12, 31))).isEqualTo(1925)
    assertThat(PublicDomain.lastRecordingYear(LocalDate.of(2027, 1, 1))).isEqualTo(1926)
}
@Test fun yearZeroAndUndatedAreHidden()          // isPublicDomainRecording: year 0 → false, null → false, 1925 in 2026 → true, 1926 in 2026 → false, 1926 in 2027 → true
@Test fun holstIsDatedByItsUploadSoItNeedsTheWaiver() // item-holst-planets.json → year 2025 → false
// ArchiveQueryTest (the existing texts tests pass unchanged)
@Test fun filteredMusicUsesTheRollingRange() = assertThat(ArchiveQuery.build("", ArchiveShelf.MUSIC_MOST_DOWNLOADED, true, ArchiveKind.MUSIC, LocalDate.of(2026, 10, 19)))
    .isEqualTo("mediatype:audio AND collection:audio_music AND year:[1860 TO 1925]")
@Test fun spokenWordLeavesLibriVoxOut()          // build("poems", null, false, SPOKEN_WORD) == "mediatype:audio AND collection:audio_bookspoetry AND NOT collection:librivoxaudio AND (poems)"
@Test fun aShelfOfAnotherKindIsRefused()         // build("", MANUALS, true, MUSIC) throws IllegalArgumentException
@Test fun filteredSpokenWordIsNeverSearched()    // SPOKEN_WORD.datedRecordings is false; build("", SPOKEN_MOST_DOWNLOADED, true, SPOKEN_WORD) throws IllegalArgumentException
@Test fun shelvesBelongToTheirKind()             // ArchiveShelf.of(MUSIC).first() == MUSIC_MOST_DOWNLOADED; of(TEXTS) == [MOST_DOWNLOADED, MANUALS, SCIENCE, LITERATURE]
// ArchiveOrgClientTest
@Test fun itemParsesAudioFileFields()            // item-holst-planets: Mars .mp3 → track 1, lengthMs 373_160, format "VBR MP3"; mediatype "audio"; collections contains "hifidelity"
@Test fun lengthParsesSecondsAndClock()          // item-librivox-time-machine: "timemachinewells_01_ae_64kb.mp3" lengthMs 1_172_000; "timemachinewells_01_ae.mp3" track 1 (from "1/13")
// ArchiveAudioOfferTest
@Test fun holstOffersItsSevenVbrMp3sInTrackOrder() {
    val offer = ArchiveAudioOffers.choose(holst) as AudioOffer.Tracks
    assertThat(offer.format).isEqualTo("VBR MP3")
    assertThat(offer.files).hasSize(7)
    assertThat(offer.files.first().name).contains("I. Mars")
    assertThat(offer.files.last().name).contains("VII. Neptune")
    assertThat(offer.cover!!.format).isEqualTo("JPEG")
    assertThat(offer.totalBytes).isEqualTo(offer.files.sumOf { it.sizeBytes!! })
}
@Test fun oggWhenThereIsNoMp3()                  // hand-built item: Ogg Vorbis + Flac → Ogg Vorbis
@Test fun flacWhenThatIsAllThereIs()
@Test fun privateAndChecksumlessFilesAreSkipped()
@Test fun noTrackNumbersMeansNaturalNameOrder()  // "2.mp3" before "10.mp3"
@Test fun waveformPngsAreNeverCovers()           // only PNG + Spectrogram images → cover == Item Tile or null
@Test fun restrictedAudioIsLendOnly()            // item-audio-lending → LendOnly
@Test fun noAudioIsNothing()
```

- [ ] **Step 2: Run to verify they fail.** Run: `./gradlew :core:archive:testDebugUnitTest`. Expected: compile failure (`ArchiveKind`, `PublicDomain`, `ArchiveAudioOffers` unresolved).
- [ ] **Step 3: Implement.** Move `ArchiveOffers.isUsable` and `isSafeName` to `internal` top-level functions in `ArchiveOffer.kt` so both offer objects share them. Keep `ArchiveOffers.isPublicDomain(item)` (books) as it is. Search asks for `mediatype` as well (add it to `SEARCH_FIELDS`).
- [ ] **Step 4: Run to verify they pass.** Run: `./gradlew :core:archive:testDebugUnitTest :app:testDebugUnitTest`. Expected: all PASS; the Plan 7 tests are unchanged.
- [ ] **Step 5: Commit**

```bash
git add core/archive app/src/main/kotlin/app/granger/archive/ArchiveScreen.kt docs/superpowers/plans/2026-10-19-plan-m2-media-sources.md
git commit -m "Media M2: archive.org audio kinds and shelves, rolling public-domain filter, audio file choice"
```

### Task 4: The LibriVox client and what a LibriVox book offers

**Files:**
- Create: `core/archive/src/main/kotlin/app/granger/core/archive/LibriVoxModels.kt`, `LibriVoxClient.kt`, `LibriVoxOffers.kt`
- Modify: `app/src/main/kotlin/app/granger/archive/ArchiveUserAgent.kt` (`librivox.org` counts as an archive host)
- Test: `core/archive/src/test/kotlin/app/granger/core/archive/LibriVoxClientTest.kt`, `LibriVoxOffersTest.kt`; `app/src/test/kotlin/app/granger/archive/ArchiveUserAgentTest.kt` (add a case)

**Interfaces:**
- Consumes: `ArchiveItem`, `ArchiveFile`, the shared `isUsable` (Task 3).
- Produces:
  ```kotlin
  data class LibriVoxSection(val id: String, val number: Int, val title: String, val listenUrl: String, val playtimeMs: Long, val readers: List<String>)
  data class LibriVoxBook(
      val id: String, val title: String, val authors: List<String> /* "H. G. Wells" */, val description: String /* HTML stripped */,
      val language: String, val genres: List<String>, val totalTimeMs: Long, val copyrightYear: String,
      val archiveIdentifier: String? /* last segment of url_iarchive, validated */, val pageUrl: String /* url_librivox */,
      val sections: List<LibriVoxSection> /* empty in search results */,
  )
  data class LibriVoxPage(val books: List<LibriVoxBook>, val hasMore: Boolean)
  sealed interface LibriVoxQuery {
      data class Text(val text: String) : LibriVoxQuery
      data class Genre(val name: String) : LibriVoxQuery
      data object Recent : LibriVoxQuery
  }
  enum class LibriVoxShelf(val title: String, val query: LibriVoxQuery) { RECENT, FICTION, POETRY, CHILDREN, SCIENCE_FICTION, DETECTIVE, PHILOSOPHY }
  class LibriVoxError(message: String) : Exception(message)
  class LibriVoxClient(
      private val client: OkHttpClient, private val userAgent: String,
      private val baseUrl: String = "https://librivox.org", private val minGapMs: Long = 1_000,
      private val clock: () -> Long = System::currentTimeMillis,
      private val sleep: suspend (Long) -> Unit = { delay(it) },   // injected with clock so tests never wait (Task 4 addition)
  ) {
      suspend fun search(query: LibriVoxQuery, offset: Int, limit: Int = 50): LibriVoxPage
      suspend fun book(id: String): LibriVoxBook          // ?id=&extended=1&coverart=1
      companion object { fun isValidId(id: String): Boolean }   // [0-9]+
  }
  sealed interface LibriVoxOffer {
      /** [files] pairs each section, in order, with its archive.org file. */
      data class Files(val book: LibriVoxBook, val item: ArchiveItem, val files: List<Pair<LibriVoxSection, ArchiveFile>>,
                       val cover: ArchiveFile?, val totalBytes: Long) : LibriVoxOffer
      data class Incomplete(val missingSections: Int) : LibriVoxOffer
      data object NotOnArchive : LibriVoxOffer
  }
  /** Always the 64 kbps files (decided with Neal; no setting). */
  object LibriVoxOffers { fun choose(book: LibriVoxBook, item: ArchiveItem): LibriVoxOffer }
  ```
- Behaviour:
  - `Text` sends two requests, `title=<text>` and `author=<last word of text>`, merged with title matches first and duplicates dropped. `hasMore` is true when either request returned `limit` rows. A leading "The", "A" or "An" is dropped from the title request, since LibriVox titles drop it ("The Time Machine" asks `title=Time Machine`).
  - Search asks only for `fields={id,title,authors,language,totaltime,totaltimesecs,num_sections}`, without `extended=1`, so search results have empty `genres` (only `book(id)` fills them).
  - `url_iarchive` "" gives `archiveIdentifier` null (→ `NotOnArchive`); `totaltime` "" with `totaltimesecs` 0 gives `totalTimeMs` 0.
  - A 404 whose body says "could not be found" is an empty page.
  - A 429 throws `LibriVoxError("LibriVox is busy right now. Try again in a minute.")`. Another non-2xx throws `LibriVoxError("LibriVox answered <code>")`. An `IOException` throws `LibriVoxError("Couldn't reach LibriVox")`.
  - Requests run on `Dispatchers.IO`, at least `minGapMs` apart, send to send (one `Mutex`, then `sleep` until the gap has passed; `sleep` is `delay` in production).
  - As built: the GET itself (user agent, `Dispatchers.IO`, `IOException` mapping) is shared with `ArchiveOrgClient` through `internal suspend fun OkHttpClient.getText` in `Http.kt`; `book(id)` for an unknown id (404 "could not be found", or no matching book) throws `LibriVoxError("Not found on LibriVox")`; a book with no sections is `NotOnArchive`.

- [ ] **Step 1: Write the failing tests.**

```kotlin
// LibriVoxClientTest (Interceptor fake serving the Task 1 fixtures by query)
@Test fun searchParsesBooksAndSendsTheUserAgent()  // first book id "817", title "Time Machine", authors ["H. G. Wells"], sections empty; request has User-Agent
@Test fun bookParsesSectionsReadersAndArchiveId()  // librivox-book-817: 13 sections; [0].title "Chapter 1", readers ["Aaron Elliott"], playtimeMs 1_172_000;
                                                   // archiveIdentifier "time_machine_0805_librivox"; totalTimeMs 14_969_000; description has no "<"
@Test fun notFoundIsAnEmptyPage()                  // 404 + librivox-not-found.json → books empty, hasMore false
@Test fun rateLimitIsALibriVoxError()              // 429 → LibriVoxError whose message starts "LibriVox is busy"
@Test fun requestsArePacedOneSecondApart()         // a fake clock; two searches → the second request is sent ≥ 1000 ms after the first
@Test fun recentShelfAsksNewestFirst()             // request has sort_field=id and sort_order=desc
@Test fun textSearchAsksByTitleAndAuthorAndMerges()// two requests (title=…, author=…); a book in both appears once, title result first
@Test fun idsAreValidatedBeforeAnyRequest()        // book("../x") throws LibriVoxError; no request made
// LibriVoxOffersTest
@Test fun sectionsMapToTheItems64kbFiles()         // book-817 + item-librivox-time-machine → Files with 13 pairs; first file "timemachinewells_01_ae_64kb.mp3", format "64Kbps MP3";
                                                   // cover.name == "Time_Machine_1112.jpg"; totalBytes == the sum of the 13 audio files' sizes (the cover isn't counted)
@Test fun aSectionMissingFromTheItemIsIncomplete() // drop one file from the item → Incomplete(1)
@Test fun noArchiveItemIsNotOnArchive()            // archiveIdentifier null → NotOnArchive
// ArchiveUserAgentTest
@Test fun librivoxGetsTheUserAgent()               // isArchiveHost("librivox.org") is true; "librivox.org.evil.test" is false
```

- [ ] **Step 2: Run to verify they fail.** Run: `./gradlew :core:archive:testDebugUnitTest :app:testDebugUnitTest --tests 'app.granger.archive.ArchiveUserAgentTest'`. Expected: compile failure, then FAIL.
- [ ] **Step 3: Implement.** Share `ArchiveOrgClient`'s JSON helpers (`allStrings`, `firstString`, `stripHtml`) by moving them to `internal` functions in a new `core/archive/src/main/kotlin/app/granger/core/archive/Json.kt` (with `asInt`/`asLong`/`asBool`), and its request code to `Http.kt` (`getText`). `ArchiveUserAgent` keeps its name and gains `|| host == "librivox.org" || host.endsWith(".librivox.org")`.
- [ ] **Step 4: Run to verify they pass.** Same command. Expected: PASS.
- [ ] **Step 5: Commit**

```bash
git add core/archive app/src/main/kotlin/app/granger/archive/ArchiveUserAgent.kt app/src/test/kotlin/app/granger/archive
git commit -m "Media M2: LibriVox client (paced, 404 as empty, 429 as busy) and section-to-file mapping"
```

### Task 5: Library: one-item albums, forgetting removed downloads, playlist ordinals

**Files:**
- Modify: `core/library/src/main/kotlin/app/granger/core/library/Albums.kt`, `MediaLibrary.kt`, `MediaDaos.kt`, `Playlists.kt`
- Modify: `app/src/main/kotlin/app/granger/music/PlaylistScreen.kt`, `MusicViewModel.kt` (rows by entry ordinal)
- Test: `core/library/src/test/kotlin/app/granger/core/library/AlbumsTest.kt`, `MediaLibraryTest.kt`, `PlaylistsTest.kt`, `ShelvesTest.kt`; `app/src/test/kotlin/app/granger/music/MusicViewModelTest.kt`

**Interfaces:**
- Produces:
  ```kotlin
  // Albums
  /** An album that is one origin item, whatever its files' tags: "|item|<source>:<ref>". Never equal to a [key] result. */
  fun itemKey(source: String, ref: String): String
  // MediaLibrary
  /**
   * Deletes these catalog rows (tracks, audiobooks, and the audiobooks' chapters). Audiobook states, bookmarks, playlist
   * entries and placements stay, so the same ids coming back (a re-download) find them again.
   */
  suspend fun forget(trackIds: Collection<String>, audiobookIds: Collection<String>)
  // MediaDao gains: deleteTracks(ids: List<String>), deleteAudiobooks(ids: List<String>) (chunked under 999 variables)
  // Playlists
  data class PlaylistItem(val ordinal: Int, val track: Track)
  /** The playlist's entries that have a track row, with their entry ordinals (which [move] and [remove] take). */
  fun items(playlistId: Long): Flow<List<PlaylistItem>>
  ```
- No schema change: the library stays at v7.

- [ ] **Step 1: Write the failing tests.**

```kotlin
// AlbumsTest
@Test fun itemKeyNeverCollidesWithATagKey()      // itemKey("archive-org", "x") startsWith "|item|"; != key("archive-org", "x", "")
@Test fun oneItemKeyIsOneAlbum()                 // three tracks with albumKey itemKey(...) and album "The Planets" → one summary, trackCount 3
// MediaLibraryTest
@Test fun forgetDeletesCatalogRowsOnly()         // track, audiobook, chapters gone; tracksOf(LIBRIVOX) empty
@Test fun forgetKeepsStatesBookmarksAndPlaylistEntries() // state + bookmark + playlist entry kept; sync(LIBRIVOX, same ids) → state found again, playlist lists the track
@Test fun forgetLeavesOtherOriginsAlone()
// PlaylistsTest
@Test fun itemsKeepEntryOrdinalsWhenATrackRowIsGone() // entries 0,1,2; forget the track at 1 → items ordinals [0, 2]; remove(id, 2) removes the third entry
// ShelvesTest
@Test fun librivoxBooksFileUnderAudiobooksLibriVox()
@Test fun archiveAlbumsFileUnderMusicArchiveOrgAndSpokenWordUnderAudiobooks()
@Test fun archiveBooksAndArchiveAudioGetSeparateCollections() // literature "archive-org" vs "music:archive-org" vs "audiobooks:archive-org"
@Test fun movedDownloadedAlbumStaysMovedAfterRemoveAndGetAgain() // moveAlbum, forget, sync again → same collection
// MusicViewModelTest
@Test fun playlistRowsUseEntryOrdinals()
```

- [ ] **Step 2: Run to verify they fail.** Run: `./gradlew :core:library:testDebugUnitTest :app:testDebugUnitTest --tests 'app.granger.music.*'`. Expected: compile failure.
- [ ] **Step 3: Implement.** `items` is `dao.observeEntries(id)` combined with `observePlaylistTracks(id)` by track id; that keeps the existing query. `PlaylistScreen` passes `item.ordinal` to remove and move. Update the doc comment in `PlaylistScreen.kt` that says track rows are never deleted.
- [ ] **Step 4: Run to verify they pass.** Same command, then the whole `:core:library:testDebugUnitTest`. Expected: PASS.
- [ ] **Step 5: Commit**

```bash
git add core/library app/src/main/kotlin/app/granger/music app/src/test/kotlin/app/granger/music
git commit -m "Media M2: one-item album keys, forget removed downloads keeping their state, playlist rows by entry ordinal"
```

### Task 6: `:core:media` manifests and the downloaded-media scanner

**Files:**
- Modify: `core/media/build.gradle.kts` (the `kotlin.serialization` plugin; `implementation(libs.kotlinx.serialization.json)`)
- Create: `core/media/src/main/kotlin/app/granger/core/media/MediaManifest.kt`, `DownloadedIds.kt`, `DownloadedMediaScanner.kt`, `CoverSweep.kt`
- Modify: `core/media/src/main/kotlin/app/granger/core/media/PhoneScanner.kt` (cover sweep after a complete scan)
- Test: `core/media/src/test/kotlin/app/granger/core/media/MediaManifestTest.kt`, `DownloadedMediaScannerTest.kt` (Robolectric, temp folders, a fake `TagReader`), `PhoneScannerTest.kt` (add)

**Interfaces:**
- Consumes: `MediaLibrary.sync`, `Albums.itemKey` (Task 5); `TagReader`, `TagParser`, `TagReadGuard` (M1).
- Produces:
  ```kotlin
  @Serializable data class CachedTags(val title: String, val durationMs: Long, val rgTrackGain: Float?, val rgTrackPeak: Float?,
      val rgAlbumGain: Float?, val rgAlbumPeak: Float?, val embeddedCover: String? /* local file name, ".cover-<16 hex of sha1(image)>.jpg" */)
  @Serializable data class ManifestFile(
      val name: String /* local */, val remoteName: String, val url: String, val sizeBytes: Long, val hash: String,
      val hashAlgorithm: String /* HashAlgorithm name */, val title: String = "", val trackNo: Int? = null,
      val readers: List<String> = emptyList(), val durationMs: Long? = null, val sectionId: String? = null, val tags: CachedTags? = null)
  @Serializable data class MediaManifest(
      val version: Int = 1, val source: String /* LIBRIVOX | ARCHIVE_ORG */, val kind: String /* AUDIOBOOK | ALBUM */,
      val ref: String /* LibriVox id or identifier */, val archiveIdentifier: String, val pageUrl: String,
      val title: String, val creators: List<String>, val readers: List<String> = emptyList(), val description: String = "",
      val language: String = "", val genres: List<String> = emptyList(), val date: String = "", val totalTimeMs: Long = 0,
      val cover: ManifestFile? = null, val files: List<ManifestFile>, val gotAt: Long) {
      companion object { const val LIBRIVOX = "librivox"; const val ARCHIVE_ORG = "archive-org"; const val AUDIOBOOK = "audiobook"; const val ALBUM = "album" }
  }
  object MediaManifests {
      const val FILE_NAME = ".granger-item.json"
      fun read(dir: File): MediaManifest?                    // null when missing or unreadable
      fun write(dir: File, manifest: MediaManifest)          // temp file, then rename
      /** "audiobooks/librivox/<id>", "audiobooks/archive-org/<identifier>", "music/archive-org/<identifier>". */
      fun relativeDir(m: MediaManifest): String
      /** "<NN> - <title or remote stem>.<remote ext>", NN at least two digits, FAT-safe, ≤ 120 chars. */
      fun localName(ordinal: Int, title: String, remoteName: String): String
      /**
       * The title to show. A [title] equal to [identifier] (or blank) is a slug, and becomes the
       * longest common prefix of [fileNames] (the audio files' remote names): extensions dropped, cut back so it never ends inside a
       * word, trailing " ", "_", "-", ":", "." trimmed. The prefix is used when there are at least two names and it is at least
       * 3 words or 12 characters; otherwise the identifier reads as words, each capitalised. Any other title (even a slug-shaped one) is returned unchanged.
       */
      fun displayTitle(title: String, identifier: String, fileNames: List<String>): String
      /** [displayTitle] for [m]: its title, its ref, and its files' remote names (the cover not included). */
      fun displayTitle(m: MediaManifest): String
  }
  object DownloadedIds {
      fun origin(m: MediaManifest): MediaOrigin
      fun audiobookId(m: MediaManifest): String            // audiobook:librivox:<id> / audiobook:archive-org:<identifier>
      fun trackId(m: MediaManifest, f: ManifestFile): String  // track:librivox:<id>:<sectionId> / track:archive-org:<identifier>:<16 hex of sha1(remoteName)>
  }
  class DownloadedMediaScanner(
      private val roots: () -> List<File>,                  // mounted volume roots
      private val media: MediaLibrary,
      private val tagReader: TagReader = NativeTagReader,
      private val tagGuard: TagReadGuard = TagReadGuard.NONE,
  ) {
      /** Files every item from its manifest into LIBRIVOX and ARCHIVE_ORG. Mutex-guarded; a scan already running is joined. Returns files whose tags were read. */
      suspend fun scan(): Int
      /** Every item folder with a readable manifest, on every mounted volume. */
      suspend fun items(): List<Pair<File, MediaManifest>>
      /** Item folders whose manifest couldn't be read (torn, not JSON, another folder's, unsafe names) at the last scan. */
      val lastUnreadable: List<File>
  }
  object CoverSweep { fun sweep(coversDir: File, referenced: Set<String>): Int }   // deletes unreferenced *.jpg; returns how many
  ```
- Filing rules:
  - A file is **present** when `<dir>/<name>` exists and its length equals `sizeBytes`. `*.part` files never count.
  - Present files missing `tags` are opened read-only (`ParcelFileDescriptor.open(file, MODE_READ_ONLY)`), read with `tagReader` under `tagGuard`, and their `CachedTags` are written back to the manifest. An embedded cover is saved as `.cover-<16 hex of sha1(image)>.jpg` in the item folder, so art embedded in every track is stored once.
  - Track fields:
    - `uri` is `Uri.fromFile(file).toString()`, and `folder` is `relativeDir + "/"`.
    - `album` is `displayTitle(m)`, `albumKey` is `Albums.itemKey(source, ref)`, and `albumArtists` is the creators joined `"; "`.
    - `title` is the file's archive.org title, else the tag title, else the remote file name's stem (not the local "NN - " name).
    - `trackNo` is the manifest's, else the file's position plus one.
    - `artists` is the section readers (LibriVox), else the creators. `genres` is the manifest's.
    - `durationMs` is the tags' value, else the manifest's, else 0.
    - ReplayGain comes from the tags. `coverPath` is `cover.jpg` if present, else the embedded cover.
    - `audiobookId` is set for `AUDIOBOOK` items.
  - An `AUDIOBOOK` item with at least one present file becomes an `Audiobook`, titled `displayTitle(m)`:
    - `author` = creators, `narrator` = distinct readers, `description`, cover, and `durationMs` = the sum of the present files.
    - Its chapters are the present files in order, one chapter each, titled like the track.
  - Then `media.sync(LIBRIVOX, …)` and `media.sync(ARCHIVE_ORG, …)`, each with every item of that origin found on any volume. An item on an unmounted volume is absent, so its rows go MISSING with their state kept.
  - A manifest that can't be read skips only its item: its rows are left as they are (unknown, not gone) and its folder is listed in `lastUnreadable`.
- `PhoneScanner`: after `media.sync(PHONE, …)`, when no source failed, `CoverSweep.sweep(coversDir, every coverPath in tracks and audiobooks)`.

- [ ] **Step 1: Write the failing tests.**

```kotlin
// DownloadedMediaScannerTest
@Test fun archiveAlbumIsOneAlbumWhateverItsTags() {
    // Manifest shaped like the Holst item: source ARCHIVE_ORG, kind ALBUM, ref = title = "holst-the-planets-holst-london-symphony-orchestra-1922-1924",
    // creators ["Gustav Holst"], 7 files with trackNo 1..7, no title, and the item's remote names
    // ("The Planets Op. 32, H. 125_ I. Mars, The Bringer of War.mp3" … "_ VII. Neptune, The Mystic.mp3"). The fake TagReader returns empty tags for six files and,
    // for Venus, TITLE "Venus, The Bringer of Peace" and ALBUM "Venus".
    scanner.scan()
    val albums = Albums.summaries(media.tracks.first())
    assertThat(albums).hasSize(1)
    assertThat(albums.single().trackCount).isEqualTo(7)
    assertThat(albums.single().title).isEqualTo("The Planets Op. 32, H. 125")
    assertThat(albums.single().albumArtist).isEqualTo("Gustav Holst")
    assertThat(Albums.ordered(media.tracks.first()).map { it.title }[1]).isEqualTo("Venus, The Bringer of Peace")
}
@Test fun librivoxBookHasOneChapterPerSectionWithReaders()
@Test fun bookIsPlayableOnceItsFirstFileArrives()       // 3 files, only the first present → 1 chapter; second arrives → 2 chapters, same audiobook id
@Test fun startupScanFilesAFileInstalledBeforeProcessDeath() // manifest + finished file, no rows → after scan() the track exists and is OK
@Test fun tagsAreReadOnceAndCachedInTheManifest()       // second scan: the fake TagReader isn't called; manifest has tags
@Test fun missingVolumeMarksItsItemsMissingAndKeepsState()  // roots() drops the volume → rows MISSING; AudiobookState untouched
@Test fun downloadedMediaIsFiledWithoutTheAudioPermission() // PhoneScanner(hasPermission = { false }).scan() then scanner.scan() → LIBRIVOX rows OK, PHONE rows MISSING
@Test fun partFilesAndQueuedFilesAreNotFiled()          // "01 - x.mp3.part" present, "01 - x.mp3" absent → no track
@Test fun brokenManifestSkipsOnlyThatItem()
// MediaManifestTest
@Test fun manifestRoundTrips()
@Test fun localNamesAreFatSafeAndOrdered()               // localName(0, "Mars: Bringer?", "x.mp3") == "01 - Mars_ Bringer_.mp3"; ordinal 99 → "100 - …"; length ≤ 120
@Test fun slugTitleComesFromTheFileNames()               // displayTitle(holstSlug, holstSlug, the 7 Holst remote names) == "The Planets Op. 32, H. 125"
@Test fun slugWithNoCommonPrefixReadsAsWords()            // names "01.mp3", "02.mp3" (or a prefix under 3 words and 12 chars, or one file) → "Holst The Planets Holst London Symphony Orchestra 1922 1924"
@Test fun aRealTitleIsNeverReplaced()                     // displayTitle("The Time Machine", "x", the 7 Holst names) == "The Time Machine"
@Test fun relativeDirsFollowTheSpecLayout()
@Test fun idsAreStableAndHaveNoAt()                       // trackId has no '@'; same remoteName → same id
// PhoneScannerTest
@Test fun unreferencedCoversAreDeletedAfterAScan()
@Test fun sweepIsSkippedWhenASourceFailed()
```

- [ ] **Step 2: Run to verify they fail.** Run: `./gradlew :core:media:testDebugUnitTest`. Expected: compile failure.
- [ ] **Step 3: Implement** the files above. The scanner hands `tagReader.read` a `FoundFile` built from the local file (`uri` the `file://` URI, `displayName` the name, `folder` the relative dir, `systemAudiobook`/`inAudiobookFolder` false).
- [ ] **Step 4: Run to verify they pass.** Same command. Expected: PASS, with M1's `:core:media` tests unchanged.
- [ ] **Step 5: Commit**

```bash
git add core/media
git commit -m "Media M2: item manifests as origin records; downloaded media filed by origin item; phone cover sweep"
```

### Task 7: App wiring: `MediaDownloader`, filing on install, Readiness by item

**Files:**
- Create: `app/src/main/kotlin/app/granger/media/MediaDownloads.kt`, `app/src/main/kotlin/app/granger/media/MediaDownloader.kt`
- Modify: `app/src/main/kotlin/app/granger/GrangerApp.kt` (`AppContainer`: `librivoxClient`, `downloadedMedia`, `mediaDownloader`; `onInstalled` routes `ItemKind.AUDIO`; `startMedia` also runs `downloadedMedia.scan()`)
- Modify: `app/src/main/kotlin/app/granger/sources/ItemStatus.kt` (`Partial`), `sources/SourceScreen.kt` (the exhaustive `when`s)
- Modify: `app/src/main/kotlin/app/granger/readiness/ReadinessViewModel.kt`, `ReadinessScreen.kt` (one row per media group)
- Modify: `app/src/main/kotlin/app/granger/settings/SettingsViewModel.kt` ("Rescan now" also scans downloads)
- Copy: the Task 1 fixtures `item-holst-planets.json`, `item-librivox-time-machine.json`, `librivox-book-817.json` into `app/src/test/resources/archive/`
- Test: `app/src/test/kotlin/app/granger/media/MediaDownloadsTest.kt`, `MediaDownloaderTest.kt`; `app/src/test/kotlin/app/granger/readiness/ReadinessViewModelTest.kt` (add)

**Interfaces:**
- Consumes: Task 2 (`enqueueGroup`, `cancelGroup`, `removeGroup`, `retryGroup`, `ItemKind.AUDIO`); Tasks 3–4 (`AudioOffer.Tracks`, `LibriVoxOffer.Files`, `ArchiveKind`); Task 5 (`forget`); Task 6 (`MediaManifest`, `MediaManifests`, `DownloadedIds`, `DownloadedMediaScanner`).
- Produces:
  ```kotlin
  object MediaDownloads {
      const val PREFIX = "media:"
      fun groupId(source: String, ref: String): String = "media:$source:$ref"          // "media:librivox:817"
      fun refOf(groupId: String, source: String): String?
      fun fileItemId(groupId: String, ordinal: Int): String                              // "media:librivox:817/003"
      fun albumManifest(item: ArchiveItem, offer: AudioOffer.Tracks, kind: ArchiveKind, client: ArchiveOrgClient, now: Long): MediaManifest
      fun bookManifest(offer: LibriVoxOffer.Files, client: ArchiveOrgClient, now: Long): MediaManifest
      /** The cover first (if any), then every file in order: ItemKind.AUDIO, groupId set, title = MediaManifests.displayTitle(m), relativePath = relativeDir/name, sha1 else md5. */
      fun specs(m: MediaManifest): List<DownloadSpec>
      /** Active → Queued/Downloading(bytes done / total)/Failed; all files installed → Installed; some installed, none active → Partial; none → NotInstalled. */
      fun statusOf(groupId: String, fileCount: Int, records: List<DownloadRecord>): ItemStatus
  }
  // ItemStatus gains: data class Partial(val done: Int, val total: Int) : ItemStatus
  class MediaDownloader(
      private val downloads: Downloads, private val scanner: DownloadedMediaScanner, private val media: MediaLibrary,
      private val volumes: VolumeSource, private val prefs: DownloadPrefs, private val scope: CoroutineScope,
      private val io: CoroutineDispatcher = Dispatchers.IO,
  ) {
      /** Writes the manifest (and `.nomedia` in the media root), then queues the group. Returns the notice to show, or null. An item already on another volume is completed there. */
      suspend fun get(m: MediaManifest): String?
      suspend fun cancel(groupId: String)
      suspend fun retry(groupId: String)
      /** Forgets the item's rows (state kept), removes its group, deletes its folder, then rescans. */
      suspend fun remove(groupId: String)
      /** The runner's onInstalled for AUDIO records: one rescan at a time; never throws. */
      suspend fun onFileInstalled(record: DownloadRecord)
      /** Every downloaded-media scan (start, finished file, removal, "Rescan now") runs through these, one at a time. */
      suspend fun rescan()
      suspend fun rescanQuietly()
  }
  // As built: the cover's file id is always <groupId>/000 and the audio files <groupId>/001 on. Remove deletes the
  // folder, rescans, then forgets (manifest track ids + chapters + item-keyed tracks), so an in-flight scan can't
  // bring ghosts back. An item already on a mounted volume is completed there; one whose ledger rows point to an
  // unmounted volume is refused ("<title> is on storage that isn't available right now. Put it back to finish it,
  // or remove it first."). "Rescan now" is wired in GrangerNavHost's mediaSettings scanner lambda.
  // AppContainer gains: val librivoxClient: LibriVoxClient; val downloadedMedia: DownloadedMediaScanner; val mediaDownloader: MediaDownloader
  ```
- Notices (exact): no volume → "No storage is available right now."; `NoSpace` → "Not enough space on <label> for <title>: free up <size>." Free space is the only check (no cap).
- `DownloadSpec.title` for every file of a group is the item's display title, so the notification and Readiness show the book or album. `DownloadSpec`'s existing required `capped` field is left-over plumbing (see "left for later"): media specs pass `false`, and nothing in M2 reads it.

- [ ] **Step 1: Write the failing tests.**

```kotlin
// MediaDownloadsTest
@Test fun holstFixtureDownloadsAsOneSevenTrackAlbum() {
    // item-holst-planets.json → ArchiveAudioOffers.choose → albumManifest(kind MUSIC) → specs: 8 (cover + 7), groupId "media:archive-org:holst-the-planets-…",
    // relativePaths under "music/archive-org/holst-the-planets-…/", SHA1, every spec's title "The Planets Op. 32, H. 125". Write the 7 files (bytes of the
    // right size) and the manifest into a temp volume, scan with DownloadedMediaScanner (fake TagReader: Venus ALBUM "Venus")
    // → Albums.summaries(...) has one album of 7 tracks titled "The Planets Op. 32, H. 125", Mars first.
}
@Test fun librivoxManifestKeepsSectionIdsReadersAndCover() // book-817 + item → 13 files with sectionId, readers; cover "Time_Machine_1112.jpg"; relativeDir "audiobooks/librivox/817"
@Test fun audioSpecsAreGrouped()                          // every spec: kind AUDIO, groupId set, itemIds "…/000"…
@Test fun statusSaysPartialWhenSomeFilesAreIn()            // 13 files, 5 INSTALLED, none active → Partial(5, 13); one RUNNING → Downloading
// MediaDownloaderTest (FakeDownloads, a temp volume, an in-memory library)
@Test fun getWritesTheManifestBeforeQueueing()
@Test fun refusedGetLeavesNoFolder()                       // NoSpace → item folder deleted; notice names the volume
@Test fun fileInstalledFilesTheItem()                      // onFileInstalled → rows for the present files
@Test fun removeThenGetAgainRestoresPositionAndPlacement() // play state + moveAudiobook; remove → no row, no folder; get + files back + onFileInstalled → same id, state and collection
@Test fun cancelKeepsFinishedFiles()                       // cancelGroup called; installed files and rows stay
// ReadinessViewModelTest
@Test fun mediaFilesShowAsOneRowPerItem()                  // 13 INSTALLED AUDIO records of one group → one row, size = sum, title = the book's
```

- [ ] **Step 2: Run to verify they fail.** Run: `./gradlew :app:testDebugUnitTest --tests 'app.granger.media.*' --tests 'app.granger.readiness.*'`. Expected: compile failure.
- [ ] **Step 3: Implement.**
  - `AppContainer`:
    - `librivoxClient = LibriVoxClient(http, archiveUserAgent)`.
    - `downloadedMedia = DownloadedMediaScanner({ context.getExternalFilesDirs(null).filterNotNull() }, media, tagGuard = FileTagReadGuard(File(context.noBackupFilesDir, "media-downloads")))`.
    - In `downloadRunner.onInstalled`, add the branch `record.kind == ItemKind.AUDIO -> mediaDownloader.onFileInstalled(record)`.
  - `GrangerApp.startMedia` launches `container.downloadedMedia.scan()` in `appScope` after the phone scans start.
  - Readiness keys a group row `group:<groupId>`. Verify runs `IntegrityChecker.full` per file; Repair calls `downloads.repair` for each damaged file.
  - `SourceScreen`'s status `when`s show `Partial` as "Partly downloaded: 5 of 13" with **Get the rest**.
- [ ] **Step 4: Run to verify they pass.** Same command, then `./gradlew :app:testDebugUnitTest`. Expected: PASS.
- [ ] **Step 5: Commit**

```bash
git add app
git commit -m "Media M2: media downloads as groups with origin manifests; filing on install; Readiness by item"
```

### Task 8: Archive.org audio in the Archive.org screen

**Files:**
- Modify: `app/src/main/kotlin/app/granger/archive/ArchiveViewModel.kt` (a `kind`; the audio Get, status and Installed paths), `ArchiveScreen.kt` (title, search hint, shelves and filter note by kind), `ArchiveItemSheet.kt` (audio labels: "<n> tracks · VBR MP3", "Downloading: 3 of 7")
- Modify: `app/src/main/kotlin/app/granger/GrangerNavHost.kt` (`@Serializable data class ArchiveRoute(val kind: ArchiveKind = ArchiveKind.TEXTS)`; pass `container.mediaDownloader`)
- Copy: `search-audio-music.json`, `search-audio-spoken.json`, `item-audio-lending.json` into `app/src/test/resources/archive/`
- Test: `app/src/test/kotlin/app/granger/archive/ArchiveViewModelTest.kt` (add), `app/src/test/kotlin/app/granger/archive/ArchiveScreenTest.kt` (create; Robolectric Compose, like `MiniPlayerTest`)

**Interfaces:**
- Consumes: Task 3 (`ArchiveKind`, shelves, `ArchiveAudioOffers`, `isPublicDomainRecording`); Task 7 (`MediaDownloads`, `MediaDownloader`).
- Produces:
  - `ArchiveViewModel` gains constructor parameters, last and defaulted: `kind: ArchiveKind = ArchiveKind.TEXTS`, `media: MediaDownloader? = null` (required when `kind != TEXTS`), `today: () -> LocalDate = LocalDate::now`.
  - `ArchiveUi` gains `kind: ArchiveKind`, `filterYear: Int` (the first excluded year, 1926 in 2026), `sheetAudio: AudioOffer?`, and `waiverNeeded: Boolean` (true while filtered for a kind without `datedRecordings`: spoken word before the waiver).
  - `ArchiveScreen` gains `onOpenLibriVox: () -> Unit` (Task 9 passes the route; until then `{}`).
  - With an audio `kind`:
    - Search builds `ArchiveQuery.build(text, shelf, filtered, kind, today())` and starts on `ArchiveShelf.first(kind)`.
    - While `waiverNeeded`, no search is sent: rows are empty, `loading` and `error` are off, and the screen shows only the spoken-word note (Global Constraints) with **LibriVox** (`onOpenLibriVox`) and **Show everything** (Plan 7's `WaiverDialog`). Accepting the waiver reruns the search, as Plan 7's waiver change does, and the results show.
    - Rows preview with `ArchiveAudioOffers.preview`.
    - `openItem` fills `sheetAudio` with `ArchiveAudioOffers.choose`.
    - `get`: filtered (live `prefs.waiverAccepted`) and not `datedRecordings` gives the spoken-word Get notice, before any request. Otherwise fetch the item, then `choose`. Anything but `Tracks` gives "Nothing on this item can be downloaded." Filtered and not `isPublicDomainRecording(item, today())` gives the audio not-public-domain notice. Otherwise `media.get(MediaDownloads.albumManifest(item, offer, kind, client, now))`.
    - `cancel`, `retryDownload` and `remove` go to `MediaDownloader` with `groupId("archive-org", identifier)`.
    - Row status comes from `MediaDownloads.statusOf`.
    - The Installed tab lists `media:archive-org:` groups whose files lie under `music/` (MUSIC) or `audiobooks/` (SPOKEN_WORD).
  - Texts behave exactly as before. Plan 7's tests pass unchanged.
  - As built: `ArchiveRow` gains `audio: AudioOffer? = null` (the row preview) and `ItemSheet` gains `filesDone`/`filesTotal` ("Downloading: 3 of 7"); the view model also takes `now: () -> Long`. The screen's list, note and waiver dialog are `internal fun ArchiveContent(ui, …, onAcceptWaiver, onOpenLibriVox, …)`, which `ArchiveScreenTest` drives. A group's file count comes from the offer, else its manifest (read once), since a cancel drops unfinished records. `GrangerNavHost` passes `onOpenLibriVox = {}` until Task 9.

- [ ] **Step 1: Write the failing tests.**

```kotlin
@Test fun musicStartsOnItsMostDownloadedShelfFiltered()   // q == ArchiveQuery.build("", MUSIC_MOST_DOWNLOADED, true, MUSIC, 2026-10-19)
@Test fun acceptingTheWaiverLiftsTheAudioFilter()         // q has no "year:["
@Test fun filteredAudioGetRefusesARecordingAfterTheCutoff() // Holst (year 2025), filtered → notice "This recording wasn't published before 1926. Show everything to download it."; nothing queued
@Test fun waiverWithdrawnAfterBrowsingStillRefusesAtGet()   // rows loaded unfiltered, then withdraw() → get(Holst) refused
@Test fun unfilteredAudioGetQueuesOneGroup()               // waiver accepted → FakeDownloads got enqueueGroup("media:archive-org:holst-…", 8 specs)
@Test fun spokenWordBeforeTheWaiverShowsOnlyTheNote()     // kind SPOKEN_WORD, filtered → no search request; rows empty; waiverNeeded true; error null
@Test fun acceptingTheWaiverRevealsSpokenWordResults()     // then prefs.accept() → one request, q == build("", SPOKEN_MOST_DOWNLOADED, false, SPOKEN_WORD); rows from search-audio-spoken.json; waiverNeeded false
@Test fun filteredSpokenWordGetIsRefused()                 // rows loaded unfiltered, then withdraw() → get(id) → notice "Show everything to download archive.org spoken word."; no item request; nothing queued
@Test fun spokenWordGetMakesAnAudiobookManifest()          // waiver accepted, kind SPOKEN_WORD → manifest kind AUDIOBOOK, relativeDir "audiobooks/archive-org/…"
@Test fun lendingAudioOffersBorrowOnly()                   // item-audio-lending → sheetAudio LendOnly; get → "Nothing on this item can be downloaded."
@Test fun audioRowStatusFollowsTheGroup()                  // 3 of 8 INSTALLED, one RUNNING → Downloading
@Test fun textsAreUnchanged()                              // default kind: startsOnMostDownloadedFiltered still passes as written in Plan 7
// ArchiveScreenTest
@Test fun spokenWordNoteOffersLibriVoxAndTheWaiver()       // waiverNeeded → the note text, a "LibriVox" button (tap → onOpenLibriVox called) and "Show everything"
                                                           // (tap → the waiver dialog with Plan 7's title and body; "I accept" → onAcceptWaiver called); no result rows
```

- [ ] **Step 2: Run to verify they fail.** Run: `./gradlew :app:testDebugUnitTest --tests 'app.granger.archive.*'`. Expected: FAIL (`ArchiveScreenTest`: compile failure).
- [ ] **Step 3: Implement.**
  - Copy by kind:
    - Top bar "Archive.org".
    - Search hint "Search archive.org texts" / "Search archive.org audiobooks" / "Search archive.org music".
    - Filter notes as in Global Constraints. The year comes from `filterYear`, never a literal. Spoken word shows its own note while `waiverNeeded`, and the unfiltered note after the waiver.
  - `ArchiveScreen` reuses the sheet, the waiver dialog and the disclaimer. **Borrow on archive.org** and **View on archive.org** work as for texts.
- [ ] **Step 4: Run to verify they pass.** Same command, then `./gradlew :app:testDebugUnitTest`. Expected: PASS.
- [ ] **Step 5: Commit**

```bash
git add app
git commit -m "Media M2: archive.org spoken word and music: rolling filter, waiver, album and audiobook downloads"
```

### Task 9: The LibriVox source screen

**Files:**
- Create: `app/src/main/kotlin/app/granger/librivox/LibriVoxViewModel.kt`, `LibriVoxScreen.kt`, `LibriVoxSheet.kt`
- Modify: `app/src/main/kotlin/app/granger/GrangerNavHost.kt` (`@Serializable object LibriVoxRoute`; rail selection LIBRARY, like `ArchiveRoute`; `ArchiveRoute`'s `onOpenLibriVox` navigates to it, for the spoken-word note from Task 8)
- Copy: `librivox-search-time-machine.json`, `librivox-recent.json`, `librivox-not-found.json` into `app/src/test/resources/archive/`
- Test: `app/src/test/kotlin/app/granger/librivox/LibriVoxViewModelTest.kt`

**Interfaces:**
- Consumes: Task 4 (`LibriVoxClient`, `LibriVoxShelf`, `LibriVoxOffers`); `ArchiveOrgClient.item`; Task 7 (`MediaDownloader`, `MediaDownloads`).
- Produces:
  ```kotlin
  enum class LibriVoxTab(val title: String) { BROWSE("Browse"), INSTALLED("Installed") }
  data class LibriVoxRow(val id: String, val title: String, val details: String /* author · 4 h 9 min */, val status: ItemStatus)
  data class LibriVoxSheet(val id: String, val loading: Boolean, val book: LibriVoxBook?, val offer: LibriVoxOffer?,
      val sizeBytes: Long?, val error: String?, val status: ItemStatus)
  data class LibriVoxUi(val tab: LibriVoxTab = LibriVoxTab.BROWSE, val query: String = "", val shelf: LibriVoxShelf? = LibriVoxShelf.RECENT,
      val rows: List<LibriVoxRow> = emptyList(), val loading: Boolean = true, val canLoadMore: Boolean = false,
      val error: String? = null, val sheet: LibriVoxSheet? = null, val notice: String? = null)
  class LibriVoxViewModel(
      private val client: LibriVoxClient, private val archive: ArchiveOrgClient, private val media: MediaDownloader,
      private val downloads: Downloads, private val appScope: CoroutineScope,
      private val io: CoroutineDispatcher = Dispatchers.IO, private val now: () -> Long = System::currentTimeMillis,
  ) : ViewModel() {
      var searchText: String; private set                 // Compose state, written on every keystroke (Plan 7 lesson)
      fun setQuery(value: String); fun selectShelf(shelf: LibriVoxShelf); fun loadMore(); fun retry(); fun setTab(value: LibriVoxTab)
      fun openBook(id: String); fun closeBook(); fun get(id: String); fun cancel(id: String); fun retryDownload(id: String); fun remove(id: String)
      fun dismissNotice()
  }
  ```
- Behaviour:
  - It's the same shape as `ArchiveViewModel`: a 400 ms debounce, a generation number that drops stale responses, and a Browse tab built from the API with an Installed tab built offline from `media:librivox:` records.
  - `openBook` loads `client.book(id)`, then `archive.item(archiveIdentifier)`, then `LibriVoxOffers.choose`.
  - `get` writes `MediaDownloads.bookManifest` and calls `media.get`.
  - `Incomplete` gives the notice "This book's files aren't all on archive.org." and `NotOnArchive` gives "This book isn't on archive.org yet."
  - The sheet shows the cover (once downloaded), title, authors, readers (first 3, then "and N more"), language, total time, genres, description (6 lines), "<n> chapters · <size>", **Get**/Cancel/Retry/Remove/**Get the rest**, and **View on LibriVox** (`pageUrl` in the browser).
  - The footer is the LibriVox copy from Global Constraints.

- [ ] **Step 1: Write the failing tests.**

```kotlin
@Test fun startsOnRecentlyAdded()                     // first request has sort_field=id, sort_order=desc
@Test fun typingSearchesByTitleAndAuthorAfterTheDebounce()
@Test fun noResultsIsAnEmptyListNotAnError()          // 404 fixture → rows empty, error null
@Test fun busyShowsTheMessageWithRetry()              // 429 → error "LibriVox is busy right now. Try again in a minute."
@Test fun staleResultsAreDropped()
@Test fun openBookFillsTheSheetWithChaptersAndSize()  // 13 chapters; sizeBytes == offer.totalBytes
@Test fun getQueuesTheBookAsOneGroup()                // enqueueGroup("media:librivox:817", 14 specs: cover + 13)
@Test fun incompleteBookIsNotQueued()
@Test fun installedTabWorksOffline()                  // client throws; Installed lists the INSTALLED group's title
@Test fun partlyDownloadedOffersGetTheRest()          // status Partial(5, 13)
```

- [ ] **Step 2: Run to verify they fail.** Run: `./gradlew :app:testDebugUnitTest --tests 'app.granger.librivox.*'`. Expected: compile failure.
- [ ] **Step 3: Implement** the view model, screen and sheet. Call `beforeDownload()` (the notification permission ask) before Get, as `SourceScreen` does.
- [ ] **Step 4: Run to verify they pass.** Same command. Expected: PASS.
- [ ] **Step 5: Commit**

```bash
git add app
git commit -m "Media M2: LibriVox source: shelves, search, book sheet, chapter downloads"
```

### Task 10: Sources by media type, and downloads in the Library

**Files:**
- Modify: `app/src/main/kotlin/app/granger/library/LibraryViewModel.kt`, `LibraryScreen.kt` (grouped sources; the "No music yet" row also links to the sources)
- Modify: `app/src/main/kotlin/app/granger/GrangerNavHost.kt` (`onOpenSource` for every link)
- Modify: `app/src/main/kotlin/app/granger/music/MusicScreen.kt` and `audiobooks/AudiobookShelfScreen.kt` (an empty page links to its sources)
- Modify: `app/src/main/kotlin/app/granger/audiobooks/AudiobookScreen.kt`, `AudiobooksViewModel.kt`, `music/AlbumScreen.kt`, `music/MusicViewModel.kt` ("Downloading: 3 of 13 chapters", **Remove download**)
- Test: `app/src/test/kotlin/app/granger/library/LibraryViewModelTest.kt`, `app/src/test/kotlin/app/granger/audiobooks/AudiobooksViewModelTest.kt`, `app/src/test/kotlin/app/granger/music/MusicViewModelTest.kt`

**Interfaces:**
- Consumes: Task 7 (`MediaDownloads.statusOf`, `MediaDownloader.remove`), Task 8 (`ArchiveRoute(kind)`), Task 9 (`LibriVoxRoute`).
- Produces:
  ```kotlin
  enum class SourceLink(val title: String) {
      GRANGER("Granger"), KIWIX("Kiwix"), ARCHIVE_ORG("Archive.org"),                // literature (unchanged names)
      LIBRIVOX("LibriVox"), ARCHIVE_ORG_SPOKEN("Archive.org"), ARCHIVE_ORG_MUSIC("Archive.org"),
  }
  data class SourceGroup(val title: String /* "Literature" | "Audiobooks" | "Music" */, val links: List<SourceLink>)
  data class LibraryUi(val media: List<Shelf>, val sources: List<SourceGroup>, val emptyLibrary: Boolean)
  // AudiobooksViewModel and MusicViewModel gain an optional `downloads: Flow<List<DownloadRecord>>` and `remover: (groupId: String) -> Unit`;
  // AudiobookUi / album page state gain `download: ItemStatus?` (null for phone media) and `onRemoveDownload` (null for phone media).
  ```
- Navigation:
  - GRANGER and KIWIX open `SourceRoute`.
  - ARCHIVE_ORG opens `ArchiveRoute(TEXTS)`, ARCHIVE_ORG_SPOKEN `ArchiveRoute(SPOKEN_WORD)` and ARCHIVE_ORG_MUSIC `ArchiveRoute(MUSIC)`.
  - LIBRIVOX opens `LibriVoxRoute`.
- **Remove download** asks first: "Remove <title>? This deletes the downloaded files. Your place and bookmarks are kept." Phone media never shows it.

- [ ] **Step 1: Write the failing tests.**

```kotlin
// LibraryViewModelTest (replaces archiveSourcesListArchiveOrgLast)
@Test fun sourcesAreGroupedByMediaType()  // [Literature: GRANGER, KIWIX, ARCHIVE_ORG], [Audiobooks: LIBRIVOX, ARCHIVE_ORG_SPOKEN], [Music: ARCHIVE_ORG_MUSIC]
// AudiobooksViewModelTest
@Test fun downloadingBookShowsChaptersSoFar()     // 13-file group, 3 INSTALLED, 1 RUNNING → download == Downloading(…); chapter count 3
@Test fun phoneBookHasNoRemoveDownload()
@Test fun removeDownloadCallsTheRemoverWithTheGroup()  // "media:librivox:817"
// MusicViewModelTest
@Test fun downloadedAlbumOffersRemoveDownload()   // ARCHIVE_ORG album → remover("media:archive-org:<id>")
```

- [ ] **Step 2: Run to verify they fail.** Run: `./gradlew :app:testDebugUnitTest --tests 'app.granger.library.*' --tests 'app.granger.audiobooks.*' --tests 'app.granger.music.*'`. Expected: FAIL.
- [ ] **Step 3: Implement.**
  - Under "Archive sources", each group gets a small header ("Literature", "Audiobooks", "Music") and its links.
  - The empty Music page says "No music yet" with **Archive.org** and **Music & audiobooks settings**.
  - The empty Audiobooks shelf says "No audiobooks yet" with **LibriVox**, **Archive.org** and the settings link.
- [ ] **Step 4: Run to verify they pass.** Same command, then `./gradlew :app:testDebugUnitTest`. Expected: PASS.
- [ ] **Step 5: Commit**

```bash
git add app
git commit -m "Media M2: archive sources grouped by media type; download progress and Remove download on book and album pages"
```

### Task 11: Show in other apps

**Files:**
- Create: `app/src/main/kotlin/app/granger/media/MediaDocuments.kt` (pure mapping), `app/src/main/kotlin/app/granger/media/MediaDocumentsProvider.kt`
- Modify: `app/src/main/AndroidManifest.xml` (the provider, disabled)
- Modify: `app/src/main/kotlin/app/granger/settings/MediaSettingsSection.kt`, `SettingsViewModel.kt` (`MediaSettingsUi.showInOtherApps`, `MediaSettings.setShowInOtherApps`), `SettingsScreen.kt` (scroll to the media section by its key, not index 5)
- Test: `app/src/test/kotlin/app/granger/media/MediaDocumentsProviderTest.kt` (Robolectric `Robolectric.buildContentProvider`), `app/src/test/kotlin/app/granger/settings/MediaSettingsTest.kt` (add)

**Interfaces:**
- Consumes: `DownloadedMediaScanner.items()`, `MediaManifests` (Task 6); `VolumeSource` (Task 2's `musicDir`/`audiobooksDir`).
- Produces:
  ```kotlin
  /** Maps Granger's media folders to documents. Ids: "root", "music", "audiobooks", and "<volume key>:<relative path>". */
  class MediaDocuments(private val volumes: () -> List<Volume>, private val volumeKey: (File) -> String, private val titleOf: (File) -> String?) {
      data class Doc(val id: String, val displayName: String, val mimeType: String, val sizeBytes: Long?, val lastModified: Long?, val isDir: Boolean)
      fun root(): List<Doc>                    // Music, Audiobooks
      fun children(parentId: String): List<Doc>
      fun doc(id: String): Doc?
      fun file(id: String): File?              // null for folders, hidden files, *.part, or anything outside a media root (no "..")
      fun isChild(parentId: String, id: String): Boolean
  }
  class MediaDocumentsProvider : DocumentsProvider()   // authority "app.granger.media.documents"
  object MediaSharing {
      const val AUTHORITY = "app.granger.media.documents"
      fun setEnabled(context: Context, on: Boolean)    // setComponentEnabledSetting(…, DONT_KILL_APP); also notifyChange on the roots URI
      fun isEnabled(context: Context): Boolean
  }
  ```
- Manifest:
  ```xml
  <provider android:name=".media.MediaDocumentsProvider" android:authorities="app.granger.media.documents"
      android:exported="true" android:enabled="false" android:grantUriPermissions="true"
      android:permission="android.permission.MANAGE_DOCUMENTS">
      <intent-filter><action android:name="android.content.action.DOCUMENTS_PROVIDER" /></intent-filter>
  </provider>
  ```
- Provider rules (spec §4, read-only):
  - One root, title "Granger", summary "Music and audiobooks", flags `FLAG_LOCAL_ONLY | FLAG_SUPPORTS_IS_CHILD`, no `FLAG_SUPPORTS_CREATE`.
  - Documents carry only `FLAG_SUPPORTS_THUMBNAIL` on `cover.jpg`, and never write, delete, rename, move, copy or remove flags.
  - `openDocument` with any mode other than `"r"` throws `SecurityException`. Every other mutating method keeps the base class's `UnsupportedOperationException`.
  - Item folders are shown by `MediaManifests.displayTitle(manifest)`, files by their local names.
  - Dotfiles, `*.part` and anything outside `music/` and `audiobooks/` are never listed or opened.
  - After filing or removal, `MediaDownloader` calls `contentResolver.notifyChange(DocumentsContract.buildChildDocumentsUri(AUTHORITY, …))`.

- [ ] **Step 1: Write the failing tests.**

```kotlin
// MediaDocumentsProviderTest
@Test fun rootListsMusicAndAudiobooks()
@Test fun itemFoldersShowTheirTitles()                // "music/archive-org/holst-…" → displayName from the manifest
@Test fun openForWriteIsRefused()                     // openDocument(id, "w"), "rw", "wt" → SecurityException; "r" → a readable descriptor
@Test fun noDocumentAdvertisesWriteOrDelete()         // every COLUMN_FLAGS & (SUPPORTS_WRITE|DELETE|RENAME|MOVE|REMOVE|COPY) == 0; root has no FLAG_SUPPORTS_CREATE
@Test fun partFilesAndHiddenFilesAreNotListed()       // ".granger-item.json", ".nomedia", ".cover-01.jpg", "01 - x.mp3.part" absent from children
@Test fun pathsOutsideTheMediaRootsAreRefused()       // "primary:../packs/x.zim", "primary:books/x.epub" → FileNotFoundException
// MediaSettingsTest
@Test fun showInOtherAppsIsOffByDefault()
@Test fun switchOffDisablesTheProvider()              // setShowInOtherApps(true) → component ENABLED; false → DISABLED (PackageManager shadow)
```

- [ ] **Step 2: Run to verify they fail.** Run: `./gradlew :app:testDebugUnitTest --tests 'app.granger.media.MediaDocumentsProviderTest' --tests 'app.granger.settings.*'`. Expected: compile failure.
- [ ] **Step 3: Implement.**
  - The Settings row goes in "Music & audiobooks", below "Rescan now", with the exact copy from Global Constraints.
  - `SettingsScreen` finds the media section's index by `MEDIA_SECTION_KEY` in `listState.layoutInfo` (or a computed index from the same item list) instead of `MEDIA_SECTION_INDEX`. Delete the constant.
- [ ] **Step 4: Run to verify they pass.** Same command, then all unit suites: `./gradlew testDebugUnitTest`. Then `./gradlew :app:assembleDebug`. Expected: BUILD SUCCESSFUL.
- [ ] **Step 5: Commit**

```bash
git add app
git commit -m "Media M2: Show in other apps: a read-only documents provider for downloaded media, off by default"
```

### Task 12: Pixel 6 walkthrough, parity checklist, roadmap

**Files:**
- Modify: `docs/superpowers/plans/media-parity-checklist.md` (an M2 walkthrough section; update the "Resume positions" and "cover replacement" rows)
- Modify: `docs/superpowers/plans/2026-10-15-granger-v1-roadmap.md` (M2 → Done; "Carried forward from M2")

- [ ] **Step 1: Install over the existing app.** Back up the library database first, as in M1's walkthrough. Run: `./gradlew :app:installDebug`. Expected: it installs, and `adb shell pm list packages app.granger` still lists it. Check that `firstInstallTime` is unchanged and that Neal's library, packs, books and phone media are intact. Readiness still verifies one Classics book: the ledger 2→3 migration on a real phone.
- [ ] **Step 2: Walk the M2 items of spec §13 on the Pixel 6, with Neal present for anything audible.** Check that Granger is the app in front first. Record pass or fail for each.
  1. Library → Archive sources shows three groups: Literature (Granger, Kiwix, Archive.org), Audiobooks (LibriVox, Archive.org), Music (Archive.org). The empty Music and Audiobooks pages link to them.
  2. **LibriVox:** search "time machine", open it, Get.
     - The first file arrives and the book appears under Audiobooks → LibriVox with its cover.
     - Chapter 1 plays while the rest download; the book page says "Downloading: n of 13 chapters".
     - When it's done, `adb shell ls /sdcard/Android/data/app.granger/files/audiobooks/librivox/817/` lists `cover.jpg`, `01 - Chapter 01.mp3`… and `.granger-item.json`.
  3. **Resume:**
     - Mid-chapter 3, `adb shell am force-stop app.granger`, then reopen: same chapter, at most about 6 s behind.
     - Then **Neal reboots the phone by hand** and unlocks it: same place. This is M1's open reboot check. Never `adb reboot`.
  4. **Archive.org music, filtered:**
     - The shelves load with the "published before 1926" note, and every result's year is 1860–1925.
     - Search "holst planets": the Holst item doesn't appear (dated 2025).
     - Show everything → accept the waiver → Get the Holst item. It becomes **one album**, "The Planets Op. 32, H. 125", with 7 tracks, Mars → Neptune, Venus included, under Music → Archive.org, with its cover.
     - It plays in order; Neal listens across a track change. A short gap between 78 sides is in the recording; Granger adds none (M1's `GaplessDeviceTest`).
     - Withdraw the waiver in Settings: the album stays.
  5. **Archive.org spoken word:**
     - Before the waiver (withdraw it first): Audiobooks → Archive.org shows no results, only the note with **LibriVox** and **Show everything**. LibriVox opens the LibriVox source.
     - Show everything → accept: the results show. Get one short item. It files under Audiobooks → Archive.org.
  6. **Network** (Plan 3's note): Wi-Fi only is on.
     - Mid-download, `adb shell cmd connectivity airplane-mode enable` plus `adb shell svc wifi disable`: the item shows "Waiting for Wi-Fi" and the `.part` file holds its size.
     - Turn both back on: it resumes from the same size.
     - Kill the process mid-file (`adb shell am kill app.granger`, with the app in the background): it resumes after WorkManager's backoff and files.
  7. **Cancel and Remove:**
     - Cancel the half-downloaded book: its chapters stay, "Partly downloaded", then **Get the rest** finishes it.
     - Add a bookmark, then Remove download: the folder is gone and nothing is left on the shelf.
     - Get it again: the bookmark and position are back.
  8. **Show in other apps:**
     - Off: the Files app has no Granger root.
     - On: Files → Granger → Music → "The Planets Op. 32, H. 125" lists 7 MP3s and `cover.jpg`. One opens and plays in another app.
     - Delete and rename are not offered, and the long-press menu has no delete.
     - Off again: the root disappears.
  9. **Audio permission:** revoke it in system settings. Phone items go missing, while the LibriVox book and the Holst album still play. Grant it again.
  10. **M1's open check:** delete a phone audio file in the Files app (it shows missing in Granger), restore it (its position is back).
  11. **Readiness** lists one row per downloaded item, and Verify passes on the LibriVox book. **Settings** storage counts include media.
- [ ] **Step 3: Fix what fails** (systematic-debugging, each fix with a test), then commit each fix.
- [ ] **Step 4: Update the parity checklist.**
  - "Resume positions": after a reboot now checked.
  - "Builder's call: cover replacement": still ❌ (no picker), but note that downloads bring covers.
  - Add an "M2 walkthrough (date)" table like M1's.
- [ ] **Step 5: Update the roadmap.**
  - M2 → Done, with the date and the test count.
  - "Carried forward from M2", including:
    - The Holst item needing the waiver (dated by its upload).
    - Archive.org items with no versions (no updates; same as books).
    - Android 15's 6-hour `dataSync` limit on very long books.
    - SD cards (no slot on the Pixel 6).
    - The Play policy check for LibriVox and archive.org audio (Plan 11).
    - Anything from Step 2 left open.
- [ ] **Step 6: Commit**

```bash
git add docs/superpowers/plans
git commit -m "Media M2: Pixel 6 walkthrough, parity checklist, roadmap"
```
