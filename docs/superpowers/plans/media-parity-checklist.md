# Media parity checklist: Auxio and Voice

Spec: `docs/superpowers/specs/2026-10-19-media-design.md` §6 (music player, match Auxio), §7 (audiobook player, match Voice), §13 (Done means). Plan: `2026-10-19-plan-m1-media-players.md`, Task 13. Checked 2026-10-19 against the code on `plan-m1-media-players`.

- ✅ done in M1, with where it lives (a file, a test, or the walkthrough). Every ✅ was checked in the code, not taken from the plan.
- ⏭ comes in a later Media plan, by design (M3: Android Auto, widget, Search). M3 closed all four (2026-10-20).
- ❌ a gap: what's missing, why, and the plan that takes it.

Paths are short: `core/player/…` is `core/player/src/main/kotlin/app/granger/core/player/…`, and the same for the other modules and `app/`.

**Totals: 51 rows. ✅ 43, ⏭ 0, ❌ 8** (after M3; M1 ended at ✅ 39, ⏭ 4). Music: 37 rows (✅ 30, ❌ 7). Audiobooks: 14 rows (✅ 13, ❌ 1).

## Music player (spec §6, Auxio)

| Auxio feature | | Where, or why not |
| --- | --- | --- |
| Media3 ExoPlayer playback | ✅ | One ExoPlayer in `core/player/PlayerEngine.kt`, wrapped by `PlaybackService.kt` (a `MediaLibraryService`). Media3 1.11.1. `PlayerEngineTest` |
| Automatic gapless playback | ✅ | The whole queue, or every file of a book, is one `setMediaItems` playlist (`PlayerEngine.kt`; `queueIsOnePlaylist`, `queueEditsDoNotRebuildThePlaylist`). MP3 LAME delay/padding: `GaplessDeviceTest` 2/2 on the Pixel 6. `ReplayGainProcessor.kt` takes the 529-sample MPEG decoder delay off the encoder delay for `c2.android.mp3.decoder`, which already drops it. Walkthrough: The Wall FLAC seamless. On the device only that MP3 decoder and FLAC were checked; Ogg/Opus/M4A rely on Media3's extractors |
| Full ReplayGain (MP3, FLAC, OGG, Opus, MP4), track/album modes | ✅ | `ReplayGainMath.kt` (Off / Track / Album / Auto, peak clamp, pre-amp), `ReplayGainProcessor.kt`; tags in `core/media/TagParser.kt` (`parsesReplayGainDbAndPeaks`, `convertsOpusR128ToReplayGain`). `ReplayGainMathTest`, `ReplayGainProcessorTest`, `replayGainModeChangeAppliesToThePlayingQueue`. Mode in Settings → Music & audiobooks. Not listened to on the device |
| Tags read from the files with TagLib (JNI) | ✅ | `:core:taglib` (TagLib v2.3.2 submodule, static, `taglib_jni.cpp`). `TagLibDeviceTest` 5/5 on the Pixel 6. The JVM fallback wasn't needed |
| Disc numbers | ✅ | `parsesTrackAndDiscWithTotals`, `orderedSortsByDiscThenTrack`; `app/music/AlbumScreen.kt` numbers tracks `disc.track` on multi-disc albums |
| Multiple artists | ✅ | `splitsMultiValueArtistsOnSemicolonsAndSlashes` (`TagParserTest`) |
| Release types | ✅ | `releaseTypeFromMusicBrainzOrReleaseType`; an artist page groups albums by release type (`MusicViewModel.releaseGroup`) |
| Exact dates | ✅ | `Track.date`; an album's year is its tracks' earliest date (`core/library/Albums.kt`, `summaryTakesYearAndCoverFromItsTracks`) |
| Original dates | ❌ | Read and stored (`Track.originalDate`), but nothing shows it or sorts by it; album years use `date` only. Later (media polish) |
| Sort tags | ✅ | Songs sort with the sort tags (`songsSortByTheChosenKeyUsingSortTags`); albums sort by `sortAlbumArtist`, year, `sortAlbum` (`Albums.summaries`). The artist list uses an artist sort tag only from single-artist tracks |
| Artists merged with album artists | ✅ | `artistsMergeAlbumArtistsAndTrackArtists` (`MusicViewModelTest`) |
| Folder management: add folders | ✅ | Picked SAF folders, music or audiobook kind: `core/media/FolderSource.kt`, `app/settings/MediaSettingsSection.kt` (`addingAFolderTakesAReadGrantStoresItAndRescans`, `removingAFolderReleasesItsGrantAndRescans`, `folderGrantLostMarksOnlyThatFoldersItemsMissing`). Folders tab: `app/music/FolderScreen.kt` |
| Folder management: exclude folders | ❌ | Auxio can leave folders out; Granger has no setting. The scan only skips `Android/` and recordings by rule (`skipsFilesInAppOwnedAndroidFolders`, `skipsRecordingsWhereTheColumnExists`). Later (media polish) |
| SD cards | ✅ | `MediaStoreSource.kt` queries `MediaStore.Audio.Media.EXTERNAL_CONTENT_URI` (every external volume), and picked folders can be on any volume. Not checked on a device: the Pixel 6 has no slot |
| Dependable playlists: create, reorder, survive rescans | ✅ | `core/library/Playlists.kt` (`RoomPlaylists`, also the app-level function for project 4). `PlaylistsTest`: `playlistSurvivesTrackGoingMissingAndBack`, `createWithTracksKeepsOrder`, `moveReordersOrdinals`, `removeKeepsOrdinalsDense`, `renameAndDelete`. Reorder is Move up / Move down, no drag |
| Playlists: add from anywhere | ❌ | "Add to playlist…" is on every song and album menu (`app/music/MusicParts.kt`), but not in the full player or the queue. Later (media polish) |
| Saved playback state: queue, position, shuffle/repeat | ✅ | `MusicState` row (queue, index, position, shuffled order, repeat); `stateRoundTrip`, `restoresAudiobookAfterServiceRecreated`, `activatingMusicRestoresTheSavedQueueAfterSavingTheAudiobook`. Walkthrough: resume after force-stop |
| Search | ✅ | M3: `core/library/MediaIndex.kt` (in memory, folded whole words, last word a prefix), Music and Audiobooks groups after Titles (`app/search/`); a song plays its album from that song (`app/media/SongPlayer.kt`). `MediaIndexTest`, `SearchRowsTest`. M3 walkthrough PASS |
| External equalizers | ✅ | `core/player/EffectsSession.kt` sends `ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION` / `CLOSE_…` with the session id; wired in `PlaybackService.kt`. Not tried with an equalizer app on the device |
| Embedded cover art | ✅ | First embedded picture from TagLib, stored once per image by a hash of its bytes (`embeddedCoverIsStoredOncePerImage`); `folder.jpg` / `cover.jpg` when none is embedded (`PhoneScanner.kt`) |
| Square covers | ✅ | `aspectRatio(1f)` covers in `app/music/MusicParts.kt` and `app/audiobooks/AudiobookShelfScreen.kt` |
| Autoplay on headset connect (setting, off by default) | ✅ | `core/player/HeadsetAutoplay.kt` (`AudioDeviceCallback`, wired headset or A2DP), started in `PlaybackService.kt`; default off (`playerSettingsWriteThePlayerPrefsWithDefaults`). Not tried on the device |
| Android Auto | ✅ | M3: `app/media/AutoBrowseTree.kt` behind `core/player/MediaBrowseTree.kt` (Recent, Music, Audiobooks; albums, artists, playlists), search and voice play, covers through `ArtworkProvider`. `AutoBrowseTreeTest`, `PlaybackCallbackTest`. Desktop Head Unit 2.1 PASS (2026-10-20) |
| Resizable widgets | ✅ | M3: Glance `app/widget/PlayerWidget.kt` (small and large layouts, whichever player is current, play/pause and the second button, resumes after a kill). `WidgetModelTest`, `WidgetUpdaterTest`. Pixel 6 PASS |
| Edge-to-edge, Material design, private and offline | ✅ | Granger's shell (Plan 5). The players draw in the theme's content colour (`PlayerContentColorTest`; walkthrough) |
| Queue view and editing | ✅ | `app/player/QueueSheet.kt`: long-press drag to reorder, remove. `moveKeepsCurrentTrackCurrent`, `removeCurrentMovesToWhatWouldHaveBeenNext`, `playNextInsertsAfterCurrent`, `queueAndBookActionsGoToTheHub` |
| Queue: tap a row to play it | ❌ | Tapping a queue row does nothing: `PlayerHub` has no play-at-index call. M3 (Android Auto's queue needs the same call) |
| Shuffle | ✅ | `shuffleKeepsCurrentTrackFirst`, `shuffleOffReturnsToOriginalOrderAtCurrentTrack`, `exoPlayerShuffleOrderMatchesTheQueue` |
| Repeat (off / all / one) | ✅ | `repeatAllWraps`, `repeatOneReturnsSameIndex`, `nextUnderRepeatOneAdvancesButAutoAdvanceRepeats` |
| Lock-screen and notification controls | ✅ | Media3's notification over the `MediaLibrarySession` in `PlaybackService.kt`. Not walked item by item on the device |
| Notification and lock screen for audiobooks | ❌ | A single-file book shows no next/previous chapter there, and the title is the book's, not the chapter's. M3 (Android Auto shows the same session metadata) |
| Bluetooth and headset buttons | ✅ | Through the media session; a pause from Bluetooth, the notification or the lock screen cancels a pending focus resume (`userPauseDuringATransientLossCancelsTheResume`) |
| Headset play after the process has died | ❌ | No `onPlaybackResumption`, so a headset or Bluetooth play press after process death does nothing. M3 (with the service work for Auto) |
| Audio focus: pause for calls and navigation prompts | ✅ | `core/player/FocusPolicy.kt`: `transientFocusLossPausesAndResumesWithRewind`, `phoneCallThroughTheSystemFocusPathResumes`, `playDuringACallPlaysWhenTheCallEnds`, `duckThroughTheSystemFocusPathRestoresTheVolume`, `permanentFocusLossStaysPaused`. Walkthrough: phone call PASS (round 2) |
| Builder's call: sort options (Songs) | ✅ | Title, artist, album, year, date added (`songsSortByTheChosenKeyUsingSortTags`) |
| Builder's call: sort options (other tabs) | ❌ | Albums, Artists, Genres and Folders have one fixed order each; Auxio lets every tab be sorted. Later (media polish) |
| Builder's call: remember shuffle | ✅ | Shuffle and the shuffled order are saved in `MusicState` (`stateRoundTrip`) |

## Audiobook player (spec §7, Voice)

| Voice feature | | Where, or why not |
| --- | --- | --- |
| Resume positions (anti-goal) | ✅ | `core/player/PositionSaver.kt`: every 5 s while playing, and at every pause, seek, chapter change, track change and stop (`savesEveryFiveSecondsWhilePlaying`, `everyEventWritesImmediately`, `audiobookPositionSavedOnPauseAndEveryFiveSeconds`). `restoresAudiobookAfterServiceRecreated`, `noSaveBeforeTheRestoreSeekCompletes`, `unresolvedSavedTrackIsNotOverwrittenUntilTheUserPicksAPlace`. Walkthrough: force-stop PASS. Worst case about 6 s lost. After a reboot: PASS in M2 (LibriVox *The Time Machine*, Neal rebooted by hand) |
| Chapter navigation | ✅ | Embedded chapters: m4b `chpl` and the QuickTime chapter track, MP3 ID3 `CHAP` (`core/media/ChapterReader.kt`, `ChapterReaderTest`); folder books one chapter per file (`folderOfFilesIsOneBookInNaturalOrder`). `core/player/ChapterMap.kt`, `app/player/ChaptersSheet.kt`, `bookmarkAndChapterSeekUseTheChapterMap`, `chapterStepsStopAtEitherEnd`. LibriVox sections come with M2 |
| Sleep timer with fade-out, including end of chapter | ✅ | `core/player/SleepTimer.kt` (linear fade over the last 10 s), `app/player/SleepTimerSheet.kt` (5–60 min, end of chapter). `sleepTimerFadesOverLastTenSeconds`, `endOfChapterExpiresAtChapterEnd`, `sleepTimerPausesAtEndOfChapter` |
| Bookmarks with optional notes, listed per book | ✅ | `app/player/AudioBookmarksSheet.kt`; the book page lists them, edits notes, deletes (`app/audiobooks/AudiobookScreen.kt`); `chapterAndBookmarkTapsStartThereInOneCall` |
| Playback speed, per book | ✅ | `AudiobookState.speed`, `app/player/SpeedSheet.kt` (0.5–3.0×, 0.1 steps), `PlayerEngine.setSpeed`; `speedsAndSleepChoicesMatchTheSpec` |
| Silence skipping, per book | ✅ | `AudiobookState.skipSilence`, `PlayerEngine.setSkipSilence` (`skipSilenceEnabled`) |
| Auto-rewind after pauses | ✅ | `core/player/AutoRewind.kt` (under 10 s none, 5 min 3 s, 1 h 10 s, else 30 s): `rewindSteps`, `boundariesBelongToTheLongerStep`, `transientFocusLossPausesAndResumesWithRewind`. Walkthrough PASS |
| Volume boost | ✅ | `LoudnessEnhancer` in `core/player/EffectsSession.kt`, per book in `AudiobookState.volumeBoostMb`; books only (music sets 0). Not listened to on the device |
| Android Auto | ✅ | M3, the same browse tree: the Audiobooks tab, last played first; a book resumes at its place. Desktop Head Unit PASS |
| Controls close at hand: big skips, chapter list one tap away | ✅ | 30 s back / 30 s forward by default, 10/15/30/60 s in Settings (`skipLabelsFollowTheSettings`, `playerSettingsWriteThePlayerPrefsWithDefaults`); the chapters button in `app/player/AudiobookPlayerScreen.kt`; the mini-player's second button is back 30 s for books (`secondaryButtonCallsNextForMusicAndSkipBackForBooks`) |
| Formats: M4B, MP3, M4A, OGG, OGA, OPUS | ✅ | Tags: `readsEveryFormat`, `opusKeepsR128Gain` (`TagLibDeviceTest`); `.m4b` is always an audiobook (`m4bAndAudiobooksFolderAndSystemFlagAreAudiobooks`); picked folders accept all six by extension (`FolderSource.AUDIO_EXTENSIONS`); playback is Media3's. On the device only an MP3 folder book was played |
| Builder's call: marking finished | ✅ | "Mark as finished" / "Mark as unfinished" on the book page: `markFinishedThenResumeStartsFromBeginning`, `markUnfinishedRewindsOnlyABookLeftAtItsEnd`, `finishedBookResumesFromTheStartAndIsUnfinishedOncePlaying`, `restoredFinishedBookStaysFinishedUntilPlayed` |
| Builder's call: shelf by last played, with progress | ✅ | `shelfOrdersByLastPlayedThenUnplayedThenFinished`, `progressIsChapterOffsetPlusPositionOverDuration` (`AudiobooksViewModelTest`) |
| Builder's call: cover replacement | ❌ | No way to pick a book's cover; a book with no embedded art and no folder image shows the placeholder (the walkthrough's LibriVox zip). Still no picker after M2, but LibriVox and archive.org downloads bring their own `cover.jpg` (checked on the Pixel 6) |

## Pixel 6 walkthrough (2026-10-19)

The spec §13 items M1 covers, from the walkthrough log. Neal listened for everything audible. Installed over the existing app with `./gradlew :app:installDebug` (no uninstall, no app-data wipe); the library database was backed up first (v6, 10 packs).

| §13 item | Result |
| --- | --- |
| Install over the existing app keeps the library | PASS: `firstInstallTime` unchanged, Neal's library intact |
| Phone music and audiobooks appear | PASS after a fix: 63 WhatsApp Business audio files under `Android/media/…` first showed as music. The scan now leaves out `Android/` and recordings (`623faa5`), and MISSING tracks are hidden from music browsing (`3866dbb`) |
| …without being moved | PASS: the 91 files under `Music/` and `Audiobooks/` had the same size, mtime and md5 before the first scan and after the walkthrough |
| Deleting a file in the Files app marks it missing; restoring it brings back its position | SKIPPED by Neal. Covered by `syncInsertsThenMarksAbsentRowsMissing` and `missingRowComingBackIsOkWithStateKept` |
| An audiobook resumes at the right spot after force-stop | PASS: killed at about 1:02–1:04 in chapter 2, reopened paused at 0:58 in chapter 2. After a reboot: not checked (no `adb reboot`; M2's Done-means) |
| Audiobook → music → audiobook loses no position | PASS |
| A gapless album plays without gaps | PASS for FLAC: *The Wall*, "The Happiest Days of Our Lives" → "Another Brick in the Wall, Part 2", seamless. MP3: a short gap in *The Dark Side of the Moon*, "Brain Damage" → "Eclipse", comes from the source file: `Eclipse.mp3` starts with 2,950 zero samples (about 67 ms) after the decoder's skip. Not a player bug |
| The mini-player is the same for both, across Library, Search, Research and immersive reading | PASS |
| A phone call mid-audiobook pauses and resumes with rewind | FAIL, then PASS. Round 1: paused on `LOSS_TRANSIENT` (−2) but didn't resume on `GAIN` 5 s later. Round 2 (`c41b868`): paused, then resumed on `GAIN` 10 s later (`GrangerFocus` log). The first failure's cause wasn't reproduced; likely play pressed while ringing, which `c41b868` handles |
| Full players readable | FAIL, then PASS: transport icons and text were near-black on the dark background; fixed in `57350ab` |

Also checked: the narrow rail's recents, bookmarks and History open the full rail (`e995da9`, `b9b15ec`); the Player's Music | Audiobooks tabs, switching, shelf links, Albums-first music tabs and untagged albums named after their folder (`3ab5838`, `d873097`, `dff6f60`, `54cca7d`), all PASS. The LibriVox test book has no embedded art or image, so it shows the placeholder cover, as expected.

Data finding: Holst's *The Planets* (LSO, 1922–24, from archive.org) splits in two: six files have no tags and group by folder, and "Venus" has its own title as its album. That's the files, not Granger. Neal's call: an archive.org album stays one album by its origin item, whatever the tags say (M2). A folder-merge rule for phone files was declined; it would merge unrelated singles in folders like `Download/`.

The rest of §13 is for later plans: LibriVox download and reboot resume, archive.org album and the rolling filter, "Show in other apps" (M2); Search, Research and the eval check, Android Auto, widget (M3).

## M2 Pixel 6 walkthrough (2026-10-19)

The spec §13 items M2 covers. Installed over the existing app with `./gradlew :app:installDebug` (no uninstall, no app-data wipe); the library and downloads databases were backed up first (library v7: 10 packs, 142 tracks; downloads v2: 364 records). Neal listened for everything audible.

| §13 item | Result |
| --- | --- |
| Install over the existing app keeps the library; the ledger 2→3 migration | PASS: `firstInstallTime` unchanged; library, packs, books and phone media intact; Readiness verified a Classics book. The 91 phone media files were unchanged (md5) |
| Archive sources grouped by media type | PASS |
| LibriVox: search, Get, the book appears with its cover after the first file, plays while the rest download, "Downloading: n of 13" | PASS. All 13 files and `cover.jpg` in `audiobooks/librivox/817/`. The book page's Play button showed Resume while the book played and reloaded it from its last save (a small jump back): fixed in `08a26f1` (Pause while this book plays) |
| Resume after force-stop | PASS: Neal saw 0:45 in chapter 3; reopened at 0:50, then the 3 s auto-rewind to 0:47 on play |
| Resume after a reboot (M1's open check) | PASS: Neal rebooted by hand; same place, auto-rewind on play |
| Archive.org music, filtered: the note, years 1860–1925, the Holst item hidden before the waiver | PASS |
| Holst after the waiver: one album, "The Planets Op. 32, H. 125", 7 tracks in order, cover; plays in order; withdrawing the waiver keeps it | PASS. Neal removed it afterwards (a noisy recording); Remove worked |
| Archive.org spoken word: before the waiver only the note with LibriVox and Show everything; after it, Get a short item, filed under Audiobooks → Archive.org, plays | PASS |
| Waiver searches find what the website finds | FAIL, then PASS (`b4fc1f9`): "John Williams" listed *AMSCO AP World History* first and "wicked audiobook" found only Plato. Free text matched every field, ranked by downloads, and searches covered only the curated collections. Audio searches now match titles and creators and, with the waiver, all archive.org audio less podcasts, LibriVox and the other kind's collections (Neal's call). Shelves stay curated |
| Network lost mid-file: "Waiting for Wi-Fi", the `.part` holds its size, resumes | Download PASS: the `.part` froze at 21,004,252 bytes and resumed; the finished file's md5 matched archive.org's. Page FAIL, then PASS: it said "Downloading" throughout (`4b664bb`, then `aa2050e`: the stopped file speaks for its group); now "Waiting for Wi-Fi" |
| Process killed mid-file resumes after backoff | PASS. `am kill` was refused (the download runs as a foreground service), so the process was killed with `run-as app.granger kill -9`; a new process resumed the `.part` about 40 s later |
| Cancel keeps chapters ("Partly downloaded"), Get the rest; Remove deletes the folder; bookmark and position kept | PASS (Get again after Remove skipped by Neal; covered by `removeThenGetAgainRestoresPositionAndPlacement`). A book removed while playing stayed in the player, unplayable: fixed in `3e8dd71` (`PlayerHub.unload`), retest PASS |
| Show in other apps: off, on, read-only, off again | PASS in Android's file browser (DocumentsUI): Music and Audiobooks, folders by title, files and `cover.jpg`, no delete or rename; a file opened in Files by Google plays. Two notes: Files by Google's "Other storage" opens the app rather than browsing it (it picked the debug-only Spike launcher); tapping a file sends it to Drive's quick viewer, which adds `?cpn=…` to the link and so loses its read grant ("problem with file"): Drive's, not Granger's |
| Audio permission revoked: phone items missing, downloads still play | PASS |
| Readiness lists each downloaded item, Verify passes; Settings storage includes media | PASS |
| Deleting a phone file in the Files app, then restoring it | SKIPPED again by Neal (covered by tests, as in M1) |

Also changed at Neal's request: album pages number tracks plainly, with "Disc n" headers on multi-disc albums, blank for untagged tracks, and a fixed-width number column (`5e559ff`).

## M3 walkthrough (2026-10-20)

Pixel 6 and the Desktop Head Unit, with the M3 build installed over the app (no uninstall; `firstInstallTime` still 2026-10-16). The library and downloads databases were backed up first (library v7 → v8 on first open: 154 tracks, 2 audiobooks). Neal did every step on the phone and the head unit and listened for everything audible.

| Item | Result |
| --- | --- |
| Search: "Holst" shows the Music group; "comfortably" shows the song and plays *The Wall* from it; album and artist results open their pages; "wicked" shows the audiobook and opens its page | PASS. Neal's note: with a full library, each group (Titles too) should show its first few results with a way to see them all |
| Research: "Do I have any Holst?" cites the Holst album and the citation opens it; "How far is Mars from the Sun?" cites no media | PASS. Neal's note: the Mars answer cited *Metamorphoses* and *War of the Worlds* before the encyclopedia; ranking doesn't weigh fiction differently from reference works on factual questions |
| Notification and lock screen: chapter title, changing at the boundary with no break across a file change (Wicked, multi-file); next and previous step chapters; music shows the track | PASS |
| Queue: tapping a row plays it | PASS |
| Resumption: force-stop, then a play press | FAIL, then PASS (`320021b`): no `MediaButtonReceiver` was declared, so the system had nowhere to send the press and `onPlaybackResumption` was never reached. With the receiver, a play press after a force-stop restarted Granger and resumed *The Wall* where it was paused (0:29) |
| Widget: picker preview, resize small → large, current player, play/pause and the second button, switching players updates it, resumes after the process is swiped away | PASS |
| Android Auto (Desktop Head Unit): Recent, Music and Audiobooks; play an album and an audiobook; search across both | PASS. DHU 2.0 (the stable channel) disconnects from Android Auto 17.8 right after the TLS handshake; 2.1 (`sdkmanager --channel=1`) connects (`tools/dev/MAC-SETUP.md`) |
| Phone media unchanged | PASS: the 91 files under `Music/` and `Audiobooks/` have the same size, mtime and md5 as before M2 |
| LibriVox book by chapter, resumes after a force-stop; an archive.org album plays gaplessly; audiobook → music → audiobook keeps its place; the mini-player the same everywhere; "Show in other apps" read-only | PASS. Neal's note: going back to an e-book or Wikipedia reader tab goes straight to full screen; it should keep the toolbar showing, as the rest of the app does, with a tap in the middle for full screen |

Eval (Task 10, `tools/eval/runs/checkpoints/m3/NOTES.md`): media cited 9/10, intrusion 0/3; tuning split 91% declined, 44% grounded (tune-5: 91%, 45%).

