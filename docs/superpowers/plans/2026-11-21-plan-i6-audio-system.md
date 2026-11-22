# Plan I6: music, audiobooks and the system on the iPhone

Spec: `docs/superpowers/specs/2026-11-20-iphone-design.md` (§6, I6). Parity list: `docs/superpowers/ios-parity.md`. Follows I5 (merged and pushed main d93ca251, release ios-core-2026-11-21-4).

**Goal.** The iPhone plays and finds audio the way Android does:
- music (albums, artists, genres, folders, playlists, the queue) from Files and the Music library;
- audiobooks with chapters, bookmarks and the saved place, and LibriVox and archive.org's spoken word and music;
- the full player: the mini player for music and audiobooks, music under the voice, Now Playing and remote commands for everything, and Media settings;
- the system pieces: CarPlay, a player widget, the Local only control in Control Center, and the mic in Search with the spoken-title matcher.

When I6 merges, every parity row marked I6 is ticked or carries an agreed reason.

**Shape (as I4 and I5).** I6a moves Android's media rules into the shared core with no change for the Android user. It's checked on the Pixel 6 and merges on its own. I6b builds the iPhone side and merges after the Simulator check. Three native pieces each open with a one-hour spike: the player engine (Task 5), tags (Task 6) and speech recognition (Task 8). The plan stops to report if one fails.

**What's already shared** (from the I6 survey, main d93ca251):
- All 13 audio tables and their DAOs are in `:core:store` at DB 18 (`tracks`, `audiobooks`, `audiobook_chapters`, `audiobook_states`, `audio_bookmarks`, `playlists`, `playlist_entries`, `music_state`, `recent_plays`, `media_folders`, `scan_cache`, `album_notes`, `voice_states`), with `MediaLibrary`, `Albums`, `AudioShelves` and `TitleMatch`.
- `:core:playback` holds the sleep timer, chapter map, place saver, auto-rewind and `ReadAloudEngine`. `VoicePlayer` (screens) is the player's voice mode.
- `ArchiveModel` already handles spoken word and music behind an `ArchiveAudio` interface. The iPhone passes none, so it shows texts only.
- `LibriVoxClient` and the archive.org audio offers are in `:core:archive`.
- `SpeechInput`, `OnDeviceSpeechInput` (over a `RecognizerBackend`) and `SpokenTitleMatcher` are in commonMain.

**Still Android-only:**
- `:core:media`: the scanner, MediaStore and SAF sources, TagLib over JNI, the chapter reader and the audiobook grouper.
- `:core:player`: `PlayerEngine`, 1,765 lines on Media3 ExoPlayer, and `PlaybackService`.
- `:core:library`'s media logic: `RoomMediaLibrary`, `Playlists`, `MediaIndex` and names.
- The app's view models for music, audiobooks, LibriVox and the player; `MediaDownloader`; the Auto browse tree; the Glance widget; the tile; and the mic's `VoiceInput`.

## Builder's calls in this plan (all 10 OK, Neal 2026-11-21)

1. **One iPhone audio engine on `AVAudioEngine`, behind the shared `PlayerHub`.**
   - The voice already plays through `AVAudioEngine` (I5). Music and audiobooks join it as `AVAudioPlayerNode`s:
     - gapless, because the next file is scheduled back to back;
     - music under the voice, as a second node at Android's `MIX_LEVEL`;
     - ReplayGain and the audiobook volume boost as node gain (a boost above 1.0 is possible here and isn't with `AVPlayer`);
     - speed with `AVAudioUnitTimePitch`.
   - What stays shared: the queue, the shuffle order, the saved place, the modes and Now Playing's state (`PlayerHub`, `MusicQueue`, `SavedModes`, `ReplayGainMath`, `PlayerNotices`, moved to `:core:playback`).
   - Android keeps ExoPlayer and is unchanged.
   - The alternative, `AVQueuePlayer`, is simpler, but it can't boost, mixes badly with the voice's engine, and has small gaps.
2. **Music comes from three places, as the parity row says:**
   - files imported from Files, copied into `Documents/Music`, which is visible in the Files app as Books and Packs are;
   - folders you pick in Files, read in place through security-scoped bookmarks (the iPhone form of Android's picked SAF folders);
   - the Music library's own non-DRM songs (`MPMediaLibrary`; a song whose `assetURL` is nil is DRM or cloud-only and is left out, with a line saying why).
   - Media settings gets phone access (Music library permission), the picked folders and Rescan now, as on Android.
3. **Tags through `AVAsset` metadata, with TagLib only if the spike shows a gap.**
   - The spike runs Android's tag fixtures (MP3 ID3v2, M4A/M4B, FLAC, Ogg/Opus) through `AVAsset` and compares them with what TagLib reads on Android: title, artist, album artist, album, disc and track, year, genre, ReplayGain, chapters and the cover.
   - If a format misses a field Android uses, a script builds TagLib for iOS (as libarchive) for that format only.
   - The pure readers move to commonMain: `TagParser`, `ChapterReader` (MP3 CHAP and M4B), `AudiobookGrouper`, `Fingerprint`, `MediaManifest` and the downloaded-media scanner.
4. **The media library's rules move to the shared core, and each phone finds its files its own way.**
   - `:core:media` becomes KMP. Its androidMain keeps MediaStore, SAF and TagLib; its iosMain gets the three sources from call 2.
   - `RoomMediaLibrary`, `Playlists`, `MediaIndex`, `MediaNames`, `MediaText` and `MediaCards` move to commonMain:
     - `withTransaction` becomes Room KMP's transaction;
     - `Locale` and `Normalizer` become the shared text fold.
   - The music, audiobook, LibriVox and player view models move to `:core:screens` (Compose state becomes `StateFlow`), as Library's did in I5.
5. **LibriVox and archive.org audio download through the shared queue.**
   - `MediaDownloader` and `MediaDownloads` move into downloads-api: a book's chapter MP3s are one group, `media:librivox:<id>`, as now.
   - On the iPhone, the files land under `Documents/Audiobooks` and `Documents/Music` and are filed by the shared downloaded-media scanner.
   - archive.org's spoken word and music then show on the iPhone, with the same rules as Android's release build: no waiver, and spoken word before 1930 only, as call 9 of I5 set for texts.
6. **CarPlay audio, as Android Auto.**
   - The browse tree (Recent; Music by albums, artists and playlists; Audiobooks) moves to commonMain from `AutoBrowseTree`, minus its Media3 types.
   - CarPlay's list templates draw it, with Now Playing as CarPlay's own.
   - It's built and checked in Xcode's CarPlay Simulator. Apple's CarPlay audio entitlement is requested under AETHERBOUND LLC before TestFlight (I8); Neal sends the request.
7. **One widget extension holds the player widget and the Local only control.**
   - The player widget uses WidgetKit (small and medium: the title, the cover, play/pause, and the next item through an `AppIntent`).
   - The Local only control is iOS 18's `ControlWidget`, the iPhone form of Android's Quick Settings tile.
   - Both read a small snapshot the app writes to the app group `group.app.granger`, as Android's `WidgetModelStore` does. The extension doesn't load the Kotlin framework (memory and size).
   - `AppSettings.localOnly` moves into the app group's defaults so the control can flip it.
8. **The mic in Search on Apple's on-device recognizer, behind the shared `RecognizerBackend`.**
   - iOS 26 is the floor, so this is `SpeechAnalyzer`, on device only (`AssetInventory` installs the language model; no audio leaves the phone, as on Android).
   - The shared `SpokenTitleMatcher` turns what was heard into a library title, and Did you mean stays the typed one.
   - `VoiceInput` and `Dictation` move to commonMain. The mic shows only when the language is installed, as on Android.
   - Mic and speech usage strings go in `Info.plist`.
9. **The parity row "Selected text → Search in Granger" is dropped.**
   - Android's build never receives `PROCESS_TEXT`. Its manifest only queries for it, so Define can send a word *to* a dictionary app, and the iPhone already covers that with the system dictionary (I5 call 8).
   - The row becomes "— (Android has no such feature)" rather than a new Share extension.
10. **Where Android's behaviour has no iPhone form, the row says so:**
    - **Headset autoplay:** iOS doesn't let an app start itself when headphones connect.
    - **External equalizer apps** (Android's audio-session broadcast): iOS has no such hook. The iPhone gets no equalizer of its own in I6, as Android ships none either.
    - **Skip silence:** if the engine spike can't do it cleanly (Android's ExoPlayer does it), it becomes "≈ later" with that reason, not a blocker.

## Global constraints

- **Android must not change for the user (I6a).**
  - The whole JVM suite passes and `:app:bundleRelease` builds with R8.
  - A Pixel 6 walkthrough passes on a debug build installed over the phone's data. Check `lastUpdateTime` and the DB version first, use `adb -s` with the Pixel's serial, and start `app.granger/.MainActivity` explicitly.
  - The walkthrough covers music (album, queue, playlist), an audiobook (chapter, bookmark, saved place), a LibriVox download, archive.org audio, music under the voice, the widget, the tile and the mic.
  - `PlayerEngineTest` and the other player tests stay where they are. Only pure code moves out of `:core:player`.
- **No migration.** The audio tables are already at DB 18.
- The repo is on the X10, which must be mounted. One Gradle, Kotlin/Native or xcodebuild run at a time.
- Pushing and GrangerCore releases have standing OK, after the noreply author check.
- Branch `i6-audio` off main d93ca251, in worktree `.claude/worktrees/i0`.
- **Test fixtures:**
  - Audio test files are small and generated, or taken from Android's existing tag fixtures, never copyrighted music.
  - LibriVox and archive.org tests use stubs, as in I5.
- **Licences for I7's licence screen:** TagLib (LGPL-2.1/MPL-1.1) only if call 3 brings it in. CarPlay and WidgetKit are Apple's own.
- **The extension targets** (widget, control) are new Xcode targets in `project.yml`, with the app group on the app and the extension.

## I6a: the shared rules

### Task 1: The media library to the core
- [ ] Make `:core:media` KMP.
  - commonMain gets `TagParser`, `ChapterReader`, `AudiobookGrouper`, `Fingerprint`, `MediaManifest` and `DownloadedMediaScanner`, on kotlinx-io where they used `java.io`.
  - androidMain keeps `PhoneScanner`'s MediaStore and SAF sources, `TagReader` over `:core:taglib`, and `ScanTriggers`.
- [ ] Move `RoomMediaLibrary`, `Playlists`, `MediaIndex`, `MediaNames`, `MediaText`, `MediaCards` and `MediaAddress` from `:core:library` to `:core:store` commonMain:
  - Room KMP transactions;
  - the shared text fold in place of `Locale` and `Normalizer`.
- [ ] Android keeps its constructors through thin wrappers. Same-named top-level-function files in `:app` and the shared modules are renamed first (the JVM file-class clash).
- [ ] Tests: the existing media and library tests run unchanged. Add a commonMain test for the fold-based index that matches Android's results on its fixtures.
  - Done 2026-11-21 (83ddf3f3, acee004c).
    - `:core:media` is KMP. commonMain: `TagParser` over `RawTags` (moved from `:core:taglib`, same package), `ChapterReader` over a new `ChapterBytes` (UTF-16 decoded as java.nio does), `AudiobookGrouper`, `Fingerprint` (core:text `sha1`), `DownloadedIds`, `MediaManifest`/`MediaManifests` by path, `TagReadGuard`, `FileTags`, `PathTagReader`, and the scanner as `DownloadedMediaScan`. Files go through `MediaDisk` (java.io one for one on Android; kotlinx-io and Foundation on the iPhone). androidMain keeps `PhoneScanner`, MediaStore and SAF sources, `TagLib` (its Kotlin side moved here; the JNI name and the `.so` in `:core:taglib` are unchanged), `FileTagReadGuard`, `CoverSweep`, `ScanTriggers`. Android's `DownloadedMediaScanner` is a thin `File` subclass; the `RandomAccessFile`/`FileChannel`/`File` forms of `ChapterReader.read`, `Fingerprint.of` and `MediaManifests.read/write` are extensions (app callers import `app.granger.core.media.read`/`write`). GrangerCore exports `:core:media`.
    - `RoomMediaLibrary`, `RoomPlaylists`, `MediaIndex`, `MediaNames`, `MediaText`, `MediaCards`, `MediaAddress` are in `:core:store` commonMain, same names and constructors. Writes use `inTransaction` (Room's `withTransaction` on Android, exactly as before). `MediaText.fold` is core:text `decompose` (NFKD) + `lowercase()` (Locale.ROOT).
    - Tests: media host tests 83/83 and core:library 535/535 unchanged. New `MediaIndexCommonTest` (core:store commonTest, which now also runs on the JVM host): Android's folds (İstanbul, ligatures, fullwidth, Ⅻ) and MediaIndexTest's searches, green on the JVM and the Simulator.

### Task 2: The player's rules, the screens' models and the mic to the core
- [ ] Move `PlayerHub` (`NowPlaying`, `PlayerUi`, `SavedMusic`, `SavedBook`, `SavedModes`), `MusicQueue`, `ReplayGainMath` and `PlayerNotices` to `:core:playback` commonMain.
  - `SavedModesReader` reads through a small prefs interface; Android's form is over `SharedPreferences`.
- [ ] Move the browse tree's logic from `AutoBrowseTree` to commonMain as plain nodes. Android maps them to Media3 `MediaItem`s.
- [ ] Move `WidgetModel` to commonMain.
- [ ] Move the music, audiobook shelf and book, LibriVox, archive.org audio and player view models' rules to `:core:screens`:
  - `UiText.Res` keys;
  - `StateFlow` in place of Compose state.
  - Android's view models become thin wrappers.
- [ ] Move `VoiceInput`, `Dictation` and `VoiceInputPrefs`' rules to commonMain. `AndroidRecognizerBackend` stays.
- [ ] Tests: `MusicQueueTest` moves with its code. `PlayerEngineTest`, `PlayerEngineVoiceTest` and `SessionPlayerTest` stay in `:core:player` and pass unchanged.
  - Done 2026-11-21 (7e1c5e84, d205d427, eb01f3ad, a06ae652, dfcef902, 99dd9b22, a71cb4fe, 01a1991a, 2f34cd3e).
    - `:core:playback`: `PlayerHub` and its states, `PlayerSettingsKeys` (the engine's keys public now), `MusicQueue`, `ReplayGainMath`, `PlayerNotices`, `SavedModesReader` over a new `PlayerPrefs`. Android's `SharedPlayerPrefs` (core:player) is its SharedPreferences, with `SavedModesReader(…, SharedPreferences)`/`lastAudiobookId` overloads so `PlayerEngine` and `MediaControllerPlayerHub` are untouched. `MusicQueueTest` and `ReplayGainMathTest` moved to playback's host tests unchanged.
    - `:core:screens`, same packages and constructors (Android's files deleted, no wrapper needed): `PlayerViewModel` and `PlayerFocus` with `bookProgress`, `chapterAt`, `formatTime`, `playerNotice`, `chapterName`, `voiceState`, `fraction`; `AudiobooksViewModel`/`AudiobookShelf`; `MusicViewModel`/`MusicCatalog`/`MusicSources` (+ `trackTitle`); `WidgetModel`; `VoiceInput`, `Dictation`, `VoiceInputPrefs`, `SpokenTitles`; `MediaBrowse` (the browse tree as `BrowseNode`s, with `PlayResumer` and `SongPlayer`). LibriVox is `LibriVoxModel`; Android's `LibriVoxViewModel` is a thin subclass keeping the search text as Compose state (as ArchiveViewModel). Android's `AutoBrowseTree` keeps its constructor and maps nodes to Media3 `MediaItem`s (media types, grid extras, Artwork covers). archive.org audio's rules were already shared (`ArchiveModel`).
    - Words are `UiText.Res` keys (no new strings; make_text_ids rerun). Sort/tab/release-group labels and `AudiobookPage.playLabel` are keys the Compose screens resolve. Tests now compare keys where they compared ids.
    - Found on the way: `chapterName` going keyed would have made `WidgetModelStore` drop the widget's saved copy for an untitled chapter (it matched by id). It now matches by `resId` (id or key); new `WidgetCacheTest` case.
    - `PlayerEngineTest`, `PlayerEngineVoiceTest`, `SessionPlayerTest` stay in `:core:player`: 91/91.

### Task 3: Media downloads on the shared queue
- [ ] Move `MediaDownloader` and `MediaDownloads` into downloads-api: group downloads with a manifest, filed by the shared `DownloadedMediaScanner`.
- [ ] Move `MediaArchiveAudio` (Android's `ArchiveAudio`) to the shared code, so the iPhone's `archiveOrg()` can pass one.
- [ ] Tests: a LibriVox book's group download files into `tracks` and `audiobooks` with origin `LIBRIVOX`, against a stub.
  - Done 2026-11-21 (9d6cabce).
    - In `:core:screens` (package `app.granger.media`), not downloads-api: the group's status is the screens' `ItemStatus`, and the manifest needs `:core:archive` and `:core:media`; downloads-api can't depend on those without a cycle. Technical placement only.
    - `MediaDownloads` and the downloader as `MediaItemDownloader` over `DownloadVolumes` and the shared `DownloadedMediaScan`; item folders through `MediaDisk` (public now; `deleteTree` never follows a link). Android's `MediaDownloader` keeps its `VolumeSource` constructor as a thin subclass. `MediaArchiveAudio` sits beside `ArchiveModel` (takes a `MediaItemDownloader`). `decodeEntities` is shared (`LibriVoxText.kt`).
    - Test: `LibriVoxGroupDownloadChecks` (commonTest): a stubbed queue "downloads" a two-chapter LibriVox book got as `media:librivox:817`; the scanner files two tracks and one audiobook, origin LIBRIVOX, with its title, author, reader and chapters. Run on the JVM under Robolectric (`LibriVoxGroupDownloadTest`, core:screens host tests are new) and on the Simulator (`LibriVoxGroupDownloadIosTest`).
  - Gates for Tasks 1–3 (2026-11-21): JVM suite 3914 tests, 0 failures (`./gradlew test`); `:app:bundleRelease` builds with R8; `tools/ios/core-local.sh` builds GrangerCore with `:core:media` exported (no shared type renamed by SKIE) and the app builds for testing against it with no Swift changes. The Simulator suite wasn't rerun here (Task 4). Left for Task 4: the Pixel 6 walk. No DB migration (DB 18), no new strings.

### Task 4: I6a check and merge
- [ ] JVM suite, `bundleRelease` with R8, and the iPhone suite (53 tests) still green.
- [ ] The Pixel 6 walkthrough (see Global constraints).
- [ ] Publish a GrangerCore release and pin it. Merge and push. Tell the peer sessions what moved.
  - Done 2026-11-21, after rebasing onto main 4038b4f8 (Classics volumes; no conflicts, text ids unchanged on regeneration).
    - JVM: 3917 tests, 0 failures, run as `./gradlew test testAndroidHostTest jvmTest --continue`. Plain `./gradlew test` skips the Android-target KMP modules (store, media, playback, books, speech, archive…), which have no `test` task; earlier totals taken that way undercounted. `bundleRelease` builds with R8.
    - iPhone: 52/53 on the first full run. The read-aloud book test timed out once waiting for Pause, and the sleep timer test tapped its sheet before it appeared (fixed: it waits for each button). The read-aloud tests then passed twice in a row. The app builds against release ios-core-2026-11-21-5, which also carries Preset.setOf from the Classics volumes work.
    - Pixel 6 on debug 1738 over the phone's data (DB 18/4):
      - TagLib's JNI library loads after the move (Rescan now).
      - Music: albums, the sort label, an artist page's Albums group, a song, the queue.
      - Audiobook: the page (Resume, 56%, chapters), resume in its chapter, pause.
      - LibriVox: The Raven searched, kept (4.6 MB), on the Audiobooks shelf, removed with its files.
      - archive.org spoken word: list and item sheet.
      - Music under the voice: The Wall under read aloud, the row and its slider. Stopping the voice leaves the music playing alone, as `PlayerEngine` intends.
      - Mic: Speak, then "Didn't catch that".
      - No crashes.
      - Not walked: the widget (none placed on the phone's home screen; `WidgetCacheTest` covers the fixed case) and the tile (unchanged).
      - Side effect: The Gift of Fire audiobook was started by a mis-tap for about 15 s and stepped back into "24. Kin".

## I6b: the iPhone

### Task 5: The audio engine
- [ ] **Spike, one hour; stop and report if it fails.** On `AVAudioEngine` in the Simulator, show each of these:
  - two tracks played gaplessly;
  - a second node mixed under the I5 voice;
  - gain above 1.0;
  - speed through `AVAudioUnitTimePitch`;
  - Now Playing with remote next/previous;
  - playback going on in the background.
  - Also time how long a 300 MB M4B takes to start.
  - Spike passed 2026-11-21 (about 15 minutes). `AudioEngineSpikeTests` (unit, offline manual rendering, so measured exactly) and `PlayerUITests` (background), every fixture a generated sine.
    - Gapless: two 2 s files scheduled back to back on one `AVAudioPlayerNode`, longest near-silence where they meet 0.02 ms (one sample, the sine's zero crossing), for 16-bit PCM and for AAC in M4A alike. `AVAudioFile` already trims AAC's encoder priming (an AAC 2 s file reads as exactly 88,200 frames).
    - Mix: music on a second node at `MIX_LEVEL` 0.3 measures 0.300 of full level, under a mono 22,050 Hz node (the Piper voice's format) on the same main mixer; with the voice the sum is heard over it (RMS 0.226 against 0.106).
    - Gain above 1.0: `AVAudioUnitEQ`'s global gain at +6 dB gives 1.995× (10^(6/20)). A node's `volume` of 2.0 also gave 2.000× in the Simulator, but Apple documents 0–1, so the boost and ReplayGain's +dB go through the EQ.
    - Speed: `AVAudioUnitTimePitch` at 2× plays a 2 s file in 1.01 s, at 0.5× in 4.02 s, pitch kept.
    - Now Playing: `MPNowPlayingInfoCenter` takes and returns music's info; next/previous track and skip ±30 s take targets and enable. The Simulator can't press a remote button, so the targets themselves are called through the same closures the voice's next/previous sentence already use (I5); a hardware press waits for a device (I8).
    - Background: a 60 s tone through `-debugPlay` (a playback session, `UIBackgroundModes: audio`) played 9.8 s while the app was 9.6 s on the home screen.
    - Long M4B: a 302 MB, 2.61 h AAC M4B (afconvert from a 330 Hz tone, CBR 256 kbps) opens with its length in 51 ms, and a segment from its middle is heard 50 ms after `play()`. About 0.1 s in all.
    - Skip silence (call 10): `AVAudioEngine` has nothing like ExoPlayer's silence skipping. Doing it means decoding to buffers, cutting quiet runs out and scheduling the rest, with a clock that maps heard time back to the file's for the saved place and chapters. That isn't clean, so it becomes "≈ later" in the parity row; the iPhone keeps the book's `skipSilence` value untouched so Android's choice survives a backup.
- [ ] Build `IosPlayerHub` over the engine: the music queue, an audiobook's chapters and place, the modes (shuffle, repeat, speed, boost, ReplayGain), the sleep timer, and auto-rewind. Skip silence per call 10.
- [ ] Bring Now Playing and remote commands up to music and audiobooks, including skip ±30 s and chapter marks. A call or unplugged headphones pause playback, as with the voice.
- [ ] Unit tests on the engine (gapless scheduling, the saved place, the mix level). Fixtures are generated tones.
  - Built 2026-11-21 (dca64c69 spike, 26bef201).
    - iPhone Kotlin (shared iosMain, no common or Android code touched): `IosAudio.kt` has `AudioGraph`, the app's one `AVAudioEngine`: the voice node → main mixer in the voice's format; the media node → `AVAudioUnitTimePitch` (speed) → `AVAudioUnitEQ` (ReplayGain and the boost as dB, above 1.0 too) → main mixer at a fixed 44.1 kHz stereo. The player node converts any file's rate and channels (checked: a mono 44.1 kHz file on a 48 kHz stereo connection keeps its timing), so the media chain never reconnects. A reconnect while running raises (-10868, checked), so a voice of another rate stops the engine, reconnects and starts it again, and the media is put back where it was; a route change (`AVAudioEngineConfigurationChange`) does the same. `MediaFilePlayer` plays files on the media node, the next one scheduled back to back; the place comes from the node's own clock through the scheduled segments (kept across a pause, when the node gives no time), and played-back callbacks say when the next file is heard. `AvAudioSpeechOutput` now writes into the graph's voice node (its `close` stops the node only, so music goes on).
    - `IosPlayer.kt`: `IosPlayerHub` implements the shared `PlayerHub`, PlayerEngine's rules without ExoPlayer: the shared `MusicQueue` (shuffle, repeat, play next, add, move, remove; queue edits re-key the file heard, so it plays on without a restart), ReplayGain per song by `ReplayGainMath` and the Media settings mode, failures skipped with `PlayerNotices`; audiobooks over `ChapterMap` (chapter steps, previous restarts after 3 s, ±30 s across files, seek in the chapter, the saved place in `audiobook_states` by `PositionSaver`: books 5 s, music 15 s, and every pause, seek, chapter and stop; a finished book starts over; place-lost notice), speed 0.5–3.0, the boost, auto-rewind after a pause (`PAUSED_AT`), `LAST_MODE`/`LAST_MEDIA`/`LAST_AUDIOBOOK`, recent plays, bookmarks, `setFinished`, `unload`, `activate`, `saved` (the shared `SavedModesReader`); the sleep timer (After with the fade, End of chapter by the chapter or the song). `DefaultsPlayerPrefs` is the "player" prefs in UserDefaults (`player.<key>`). The voice is I5's `VoicePlayer`, writing into the same graph: the hub watches its state, so music playing when the voice starts (from any reader) goes on under it at `MIX_LEVEL`, music started while the voice reads goes under it, a paused voice gives way to music, and an audiobook gives way to the voice. Voice calls on the hub go to the `VoicePlayer`.
    - Swift: `VoiceCenter` is now the whole player's centre: the hub's state beside the voice's; the audio session (spoken audio for the voice and books, default for music); Now Playing for a song (title, artist, album, cover, queue place, elapsed and length) and a book (the chapter's name, book, author, cover, chapter number and count, the speed as rate); remote play, pause, toggle, stop, next/previous (the voice's sentence, a song, a book's chapter), skip ±30 s for books (intervals from the prefs), and the position bar; a call or headphones out pause the voice and the media together, and a call's end with "resume" plays again. The `-debugPlay "<file>|tone:<seconds>"` hook plays files through the hub, with a hidden `debug-play-position` label.
    - Skip silence (call 10): "≈ later". `AVAudioEngine` has no silence skipping; doing it means decoding to buffers, cutting quiet runs and mapping heard time back to the file for the place and chapters. The book's `skipSilence` is kept and saved as stored (so Android's choice survives a backup) but not applied. Headset autoplay stays "— (iOS has no such hook)" per call 10.
    - Tests (Simulator, generated tones): `PlayerEngineTests` (4): gapless on the media node, offline, 0.02 ms where a PCM file meets an AAC one, and the player follows into the second file; the hub moves through a two-song queue by itself (real time); music under the voice at 0.300 of full in the rendered output, the voice node heard over it, full again when the voice stops; an audiobook's place written on pause (file 2, 1983 ms, in the third chapter) and a new hub over the same store opening it in the same chapter at the same place. `AudioEngineSpikeTests` (6, the long M4B skipped without GRANGER_TEST_M4B) and `PlayerUITests` (1, the background check, now through the hub). The I5 read-aloud tests pass with the voice in the shared graph.
    - Found on the way: offline, the media node's clock reads about 90 ms ahead of what is rendered (the time-pitch unit pulls ahead; its reported latency is 0), so the place shown can lead the sound by that much. Harmless for saving; noted for the music screen's bar.
    - Left: the played-back callbacks don't fire in offline rendering, so the Advanced/Ended path is covered by the real-time queue test only. Remote next/previous and skip were registered and enabled, not pressed (the Simulator has no remote; I8 on a device). Music under the voice keeps playing when the voice is paused by its own button, as on Android; a call pauses both. The hub doesn't restore the last mode at launch (Android's `restore()`): nothing shows on the lock screen until something plays, and Tasks 6–7's screens use `saved`. The music and audiobook screens are Tasks 6 and 7. The JVM suite and `bundleRelease` weren't rerun: no common or Android code changed.
    - Checked by the lead 2026-11-21: clean tree, noreply authors, no new crash reports. Full Simulator rerun 62 passed, 1 skipped (M4B), 1 failed: `BookReaderTests.testEpubReader` ("the note is listed"), failing the same on main 53c8af32. Cause: UI tests don't reset app data, so highlights left by earlier failed runs pile up in the book. With the app uninstalled first it passes. Carried to Task 9: give the UI tests a clean-data launch argument.

### Task 6: Music
- [ ] **Spike, one hour.** Run Android's tag fixtures through `AVAsset`, comparing the fields listed in call 3. If a field Android uses is missing, the TagLib script goes in for that format and the spike says which.
- [ ] Bring in the three sources from call 2:
  - Files import into `Documents/Music`;
  - picked folders through security-scoped bookmarks;
  - the Music library's non-DRM songs.
  - All three feed the shared scanner and `RoomMediaLibrary` (replacing I5's read-only `IosMediaLibrary`).
- [ ] Build Listen's Music: albums, artists, genres, folders, playlists (make, add, reorder, remove), the album and artist screens, the queue sheet, covers, and the music player screen.
- [ ] Build Media settings: Music library access, the picked folders, Rescan now and the player settings, in Android's words.
- [ ] UI tests: import two tracks, play an album, add to a playlist, reorder the queue, and see Rescan find a new file.
  - Built 2026-11-21 (565575c7).
    - **Tags spike: passed in about 20 minutes, TagLib for MP3 only.** Android's fixtures (testdata/audio) through `AVAsset` on the iOS 26 Simulator: M4A/M4B, FLAC and Opus give every field call 3 lists (title, artists as two values, album artist, album, disc and track with totals (M4A's `trkn`/`disk` as bytes), year, original date, genre, sort tags, ReplayGain (M4A free-form; ID3 `TXXX` by its `info` attribute), Opus's R128, the cover, and chapters for both test books). The gap: MP3's ID3v2.4 multi-value frames come back as their first value only (TPE1 "London Symphony Orchestra\0Gustav Holst" gives the orchestra alone), so MP3s go through TagLib: `tools/ios/build-taglib.sh` builds a static `taglib.xcframework` from Android's own submodule (2.3.2, nothing downloaded; ~3 MB a slice, not in git), `GTagLib.mm` mirrors Android's JNI bridge (PropertyMap, Average properties, front cover, same unparseable rule). Licence for I7: TagLib LGPL-2.1/MPL-1.1, static under the MPL. Found on the way: **iOS can't decode Ogg Vorbis at all** (AVAsset and AVAudioFile fail, `fmt?`; macOS can), so TagLib wouldn't help: `.ogg` Vorbis files are left out of the scan (an Opus file in Ogg plays). Chapters stay the shared `ChapterReader`'s. `MusicTagTests` (2) keeps the spike as a test.
    - Shared (common and Android touched): `PhoneScanner`'s rules moved to `:core:media` commonMain as `PhoneMediaScan` over a `PhoneFiles`/`OpenedFile` interface (paths as strings, covers through `MediaDisk`); Android's `PhoneScanner` keeps its constructor as a thin subclass over its `ParcelFileDescriptor`s and keeps `CoverSweep` (PhoneScannerTest and the 83 media host tests unchanged).
    - iPhone Kotlin (`IosMusic.kt`): the three sources (call 2) into `PhoneMediaScan` and the library's `RoomMediaLibrary` (now `IosLibrary.media`, which the shelves and the hub share; `IosMediaLibrary` is gone) and `RoomPlaylists`: `FolderTree` for Documents/Music ("Music/…") and each picked folder (natural order, `folder.jpg`/`cover.jpg`, a folder that can't be listed is unknown, not empty), `LibrarySource` for MPMediaLibrary (needs its permission, as Android's media store needs the audio permission; the persistent id stands in for the fingerprint), `IosPhoneFiles` (fingerprint by `pread`, a file `AVAudioFile` can't open is left out), `FileTagGuard` (Android's TagLib crash guard), the music and player models, and the player settings in the hub's prefs. A scan asked while one runs joins it and then runs again (an import just copied would otherwise be missed). Swift: `MusicNativesImpl` (AVAsset turned into TagLib's PropertyMap keys, TagLib for `.mp3`, `MPMediaQuery.songs()`: no asset URL, protected or cloud-only songs left out and counted), `MusicCenter` (bookmarks in UserDefaults, resolved and held open; imports copied into Documents/Music; library access; Rescan state; scans at start, on foreground and on `MPMediaLibraryDidChange`).
    - Screens, in Android's words: Music's tabs (Albums list/grid with Title/Artist/Year, Artists and Songs under letters with the strip, Genres, Playlists with New/Rename/Delete, Folders; "No music yet" with Music & audiobooks settings), the album page (disc headers, track numbers, Play/Stop/resume, the playing bars), artist (release groups, Appears on, Songs), genre, folder and playlist pages (Move up/down, Remove from playlist), the song and album menus (Play next, Add to queue, Add to playlist…, Go to album/artist, Move to collection…), Add to playlist with New playlist, the music player (cover, seek, shuffle/previous/play/next/repeat, Queue, Add to playlist, Music shelf; the saved queue shown paused, a control activating music, which is how music "restores": no launch-time `restore()` was needed), and the queue sheet (drag to reorder, remove, tap to play, VoiceOver Move up/down). Listen gains the music card (Now playing/Paused/Last played, play/pause, opens the player) and the Music shelf (All music, collections), plus More › Find music on this phone. Settings › Music & audiobooks: Music library access (Allowed / Allow access / Open app settings, and how many library songs can't play), Add a folder (Music/Audiobooks folder, Remove), Import from Files, Rescan now with Android's Scanning…/Found… lines, ReplayGain, the skips once there's an audiobook. Not on the iPhone: Show in other apps (Documents is always in Files) and Play when headphones connect (call 10). The media clock's ~90 ms lead isn't corrected: the bar shows whole seconds, updated each second.
    - Tests: `MusicUITests` (3): two MP3s imported through `-debugImportMusic` make "Gapless Test" with its artist (TagLib), it plays (Stop, the bars) and stops; a song goes into a new playlist, the album plays, the music player opens from Listen's card and the queue's first song is dragged below the second; a picked folder (`-debugMusicFolder`, for that launch only) gains a file and Rescan now finds it. Tones are written by the tests (AAC, never committed). The Simulator can't read the X10 volume the checkout is on (reads hang), so the audio fixtures are copied beside the others and named by `GRANGER_TEST_AUDIO` (`TEST_RUNNER_GRANGER_TEST_AUDIO=$S/audio`, a copy of testdata/audio); without it those tests skip. iPhone, full run with the app uninstalled first: 69 tests (64 + `MusicTagTests` 2 + `MusicUITests` 3), 66 passed, 1 skipped (M4B), 2 failed: `SmokeTests.testBookPackCollection` and `BookReaderTests.testPackBookOpensInTheReader`, both at Explore's "Your sources", which a fresh install in Local only doesn't show until an online test has fetched the indexes (alphabetically those two run first); after a failed run the pack's books are left "File not found". Uninstalled again, `testExploreWalk` then `testPackBookOpensInTheReader` both pass, and `testBookPackCollection` passes once the indexes are there. Test order and leftover data, nothing in music: carried to Task 9's clean-data launch argument (it should also give Explore its indexes or not depend on them). JVM: 3917 tests, 0 failures (`./gradlew test testAndroidHostTest jvmTest --continue`); `:app:bundleRelease` builds with R8. `make_text_ids.py --check` clean (no shared strings added). No new crash reports.
    - Left, with reasons: the Music library source can't be exercised in the Simulator (no library there); whether `AVAudioFile` opens `ipod-library://` URLs is checked only on a device (I8): if it doesn't, those songs are left out by `canPlay` and the fix is an export to a file. Archive.org music on the empty Music page and Listen's audiobooks are Task 7 (the line on Listen now names audiobooks only). The mini player is still the voice's (Task 7). Ogg Vorbis needs a decoder (libvorbis) if it's wanted: a call for Neal.

### Task 7: Audiobooks, LibriVox and archive.org audio
- [ ] Build audiobooks: the shelf (Continue, Recent, finished), the book screen, chapters, bookmarks, speed and boost, and the saved place.
  - Passive: no audiobooks, no mention, as Android.
  - An M4B's chapters, or a folder of MP3s grouped as one book.
- [ ] Build LibriVox: search and the book page, then download through the shared queue, and the book appears on the shelf.
- [ ] Turn on archive.org's spoken word and music in `ArchiveScreen`, using the iPhone's `ArchiveAudio`.
- [ ] Bring the mini player up to music and audiobooks, and add music under the voice (Listen's control, as Android).
- [ ] Update Listen's shelves and remove the "later update" line.
- [ ] UI tests:
  - an audiobook opens at its saved place after a relaunch;
  - a bookmark is set and returned to;
  - a stubbed LibriVox download lands on the shelf;
  - music plays under the voice.

### Task 8: CarPlay, the widget, the Local only control and the mic
- [ ] Build CarPlay: the shared browse tree in list templates, and Now Playing. Check it in the CarPlay Simulator; the entitlement is noted for I8 (call 6).
- [ ] Add the widget extension with the app group (call 7):
  - the player widget, small and medium, with play/pause and next through `AppIntent`s;
  - the Local only `ControlWidget`;
  - the app writes the snapshot when playback or Local only changes.
- [ ] **Spike, one hour; stop the mic piece (not the task) if it fails.** `SpeechAnalyzer` recognises a recorded phrase on device in the Simulator, and the shared matcher turns it into a library title.
- [ ] Build the mic in Search: the button only when the language is installed, live words, the matched title opened, and Voice input in Settings, as Android.
- [ ] UI tests: the Local only control's intent flips the setting and the app follows it; the widget's snapshot updates when a track starts. The mic is tested with a recorded file through the backend, not the microphone.

### Task 9: I6b check and merge
- [ ] Run the whole Simulator suite and the JVM suite.
- [ ] UI tests start from clean app data (a launch argument that clears the library DB and prefs), so a failed run can't leave highlights or notes that break the next (found in Task 5's check: `testEpubReader`).
- [ ] Walk it in Night and at the largest text size, including the two layout findings carried from I5 (the mini player over Listen's content, and the Library's huge titles). Fix them here, since Listen grows in this plan.
- [ ] Tick the I6 rows in `ios-parity.md`, with reasons where they differ, and drop the `PROCESS_TEXT` row per call 9.
- [ ] Publish a GrangerCore release and pin it. Merge and push. Tell the peer sessions what moved.

## Order and size

I6 moves about 6,000 lines of Android code (media, library, player rules and the view models), and builds about the same again on the iPhone. `PlayerEngine` itself doesn't move, because it's ExoPlayer through and through.
- I6a is moving code, as I5a was. The scanner split and `RoomMediaLibrary`'s transactions are the riskiest parts, so each goes in its own commit with its tests.
- I6b's risks are the three spikes (engine, tags, speech). Nothing is built on any of them until its spike passes.
- If I6 runs long, it splits at Task 8: audio first (Tasks 5–7), then the system pieces (Task 8) as their own merge.
