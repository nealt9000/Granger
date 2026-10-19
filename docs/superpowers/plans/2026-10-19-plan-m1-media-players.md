# Plan M1: Media players and phone media Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Music and audiobooks already on the phone appear in the Library, referenced in place. They play in an Auxio-grade music player and a Voice-grade audiobook player, behind one mini-player and the Player rail tool.

**Architecture:**
- **`:core:taglib`** (new, NDK): reads tags with TagLib through a small JNI wrapper.
- **`:core:media`** (new): turns raw tags into typed tags, reads chapters, and scans the phone (MediaStore plus user-picked folders) into `ScannedItem`s.
- **`:core:library`** (Room v7): stores tracks, audiobooks, chapters, playback state, bookmarks, playlists and recent plays. It files albums and audiobooks into the existing media type and collection shelves.
- **`:core:player`** (new, Media3): owns one ExoPlayer inside a `MediaLibraryService`, with music and audiobook modes. The app talks to it only through `PlayerHub`.
- **`:app`**: adds the mini-player, the two full-screen players, music and audiobook browsing, and media settings.

**Tech Stack:** Kotlin 2.4.21, coroutines 1.11.0, Room 2.8.5, Compose Material 3, AndroidX Media3 (ExoPlayer, session; version pinned in Task 1), TagLib 2.x (C++, through the NDK and CMake), Robolectric 4.17, JUnit 4 + Truth, ffmpeg (fixtures only).

**Spec:** `docs/superpowers/specs/2026-10-19-media-design.md`. Shell slots are in `docs/superpowers/specs/2026-10-16-library-redesign-design.md` §3. Roadmap: `docs/superpowers/plans/2026-10-15-granger-v1-roadmap.md`.

## How Media is split

| Plan | Delivers | Spec sections |
|---|---|---|
| **M1 (this plan)** | Phone media, both players, mini-player, browsing, playlists, Recent plays | §2, §3.1, §5, §6, §7, §8, §10 (ids, playlists function, second-stream readiness) |
| M2 | LibriVox, archive.org audio with the rolling filter, sources by media type, media downloads with origin records, "Show in other apps" | §3.2–§3.4, §4 |
| M3 | Media index in Search, Research media passages + eval check, Android Auto, widget, final Done-means walkthrough | §6/§7 Auto and widget rows, §9, §13 |

M2 and M3 are written after M1 merges, against the code M1 actually produced.

## Decisions made with Neal (2026-10-19)

- Phone media is referenced in place: never moved, copied, renamed or deleted by Granger.
- Match Auxio (music) and Voice (audiobooks), using the spec §6/§7 checklists. Android Auto and widgets are in (M3).
- Everything in Media is free. No Pro checks.
- Anti-goals: losing an audiobook position; unintended gaps between songs; a mini-player that differs between music and audiobooks.
- Tags are read with TagLib (native). The fallback, only if the native build proves unworkable, is a JVM reader with the gaps listed.
- Development moves to Neal's Mac Studio (M2 Max, 64 GB) from Task 1.

## Builder's calls in this plan (for Neal's review)

- **Phone track identity** is a content fingerprint, not the file path, so moving or renaming a file keeps its id, position and playlist entries: sha1 over the size plus the first and last 64 KiB, first 16 hex chars.
- **Auto-rewind on resume:** paused under 10 s → none; under 5 min → 3 s; under 1 h → 10 s; 1 h or more → 30 s.
- **Position saves:** audiobooks save every 5 s while playing, plus on every pause, seek, chapter change, track change and stop. Music state saves on the same events and every 15 s.
- **Sleep timer fade:** linear over the last 10 s.
- **Skip intervals:** audiobooks 30 s back / 30 s forward, adjustable in Settings (10/15/30/60 s).
- **ReplayGain default:** "Album when playing an album in order, else track", as in Auxio, with 0 dB pre-amp and peak-based clipping protection.
- **Music collections:** the Music shelf shows an **All music** row first, then its collections. Each opens the Auxio-style tabs (Songs, Albums, Artists, Genres, Playlists, Folders) scoped to that collection ("All music" isn't scoped). Playlists are global and always show every playlist.
- **Library modules:** `:core:media` depends on `:core:library`, not the other way round, so the scanner can hand `ScannedItem`s straight to `MediaLibrary.sync`.

## Global Constraints

- `minSdk 29`, `compileSdk 37`, `targetSdk 36`, Java 17, as in every module today.
- License: the app is GPLv3. TagLib (LGPL-2.1/MPL-1.1) and Media3 (Apache-2.0) are compatible. Don't add a dependency with an incompatible license.
- The phone's media is read-only to Granger. No code path writes, renames or deletes a `content://` or SAF URI. The scanner opens files read-only (`"r"`).
- Permissions:
  - `READ_MEDIA_AUDIO` on API 33+, and `READ_EXTERNAL_STORAGE` with `maxSdkVersion="32"` below that.
  - `FOREGROUND_SERVICE` and `FOREGROUND_SERVICE_MEDIA_PLAYBACK`.
  - The app is fully usable with the audio permission denied.
- Collection names (exact): "On this phone", "All music". Media type ids are the existing seeded `"music"` and `"audiobooks"`.
- Ids:
  - Tracks: `track:<origin>:<ref>`. Phone tracks: `track:phone:<fingerprint>`.
  - Audiobooks: `audiobook:<origin>:<ref>`. Phone audiobooks: `audiobook:phone:<fingerprint of first file in order>`.
  - Placement keys: `album:<albumKey>` and the audiobook's id.
  - Position addresses for later plans (spec §10): `track:<id>@<ms>` and `audiobook:<id>@<chapter>/<ms>`. Format them through `MediaAddress` (Task 4).
- One ExoPlayer instance, one active mode (music or audiobook) at a time. Each mode keeps its own state (spec §8).
- Don't assume speech will never play alongside music (spec §10). Keep audio focus handling in one place (`FocusPolicy`, Task 8) so project 3 can add a second stream.
- Builds run on the Mac Studio. The old in-process/one-build rule applies only if work goes back to the Linux machine.
- Device tests:
  - Never run `:app:connectedDebugAndroidTest`: it reinstalls the app and wipes Neal's ~25 GB library.
  - Library-module device tests (`:core:taglib:connectedAndroidTest`, `:core:player:connectedAndroidTest`) install only their own test APKs. Run them freely, but check with `adb shell pm list packages app.granger` that `app.granger` is still installed afterwards.
  - Never touch the phone while one of Neal's personal apps is in front. Never `adb reboot` (the phone locks behind a screen only Neal can pass).
- Pushing to GitHub needs Neal's OK at that step.

## Review Focus

1. **Audio permission denied, or revoked after a scan.** Expected: the app keeps working. Phone items go MISSING (not deleted), with positions, bookmarks and playlist entries kept, and Settings offers to grant again. Pinned in Task 6 (`permissionLostMarksPhoneItemsMissingAndKeepsState`).
2. **A phone file moved or renamed between scans.** Expected: same track id, same audiobook position, same playlist entries. Pinned in Task 6 (`movedFileKeepsIdAndPosition`).
3. **The process is killed mid-audiobook** (swipe-away, OOM, crash). Expected: on reopen, the position is at most 5 s behind, then auto-rewind applies. Pinned in Task 7 (`savesEveryFiveSecondsWhilePlaying`) and Task 8 (`restoresAudiobookAfterServiceRecreated`).
4. **Audio focus lost mid-audiobook** (a phone call). Expected: pause, then resume when focus returns, with auto-rewind and no position lost. A permanent loss (another player starts) pauses and stays paused. Pinned in Task 8 (`transientFocusLossPausesAndResumesWithRewind`, `permanentFocusLossStaysPaused`).
5. **A file with no tags, or broken tags.** Expected: title from the file name, album "Unknown album", artist "Unknown artist". No crash, and the rest of the scan continues. Pinned in Task 3 (`emptyTagsFallBackToFileName`) and Task 6 (`unreadableFileIsSkippedAndScanContinues`).

---

### Task 1: Move to the Mac Studio and pin the toolchain

**Files:**
- Modify: `gradle/libs.versions.toml` (add `media3`, `ndk` version entries)
- Modify: `docs/superpowers/plans/2026-10-15-granger-v1-roadmap.md` (Media rows; see Step 6)
- Create: `tools/dev/MAC-SETUP.md`

**Interfaces:**
- Produces: version-catalog entries `libs.media3.exoplayer`, `libs.media3.session`, `libs.media3.common`, `libs.media3.test.utils`, `libs.media3.test.utils.robolectric`, and a `ndkVersion` string that Task 2 uses.

- [ ] **Step 1: Clone and open on the Mac.** `git clone` the repo (with Neal's OK on the remote), then install Android Studio's SDK (platform 37, build-tools), the NDK (latest LTS side-by-side), CMake 3.22+, JDK 17 and ffmpeg (`brew install ffmpeg`). Record exact versions in `tools/dev/MAC-SETUP.md`.
- [ ] **Step 2: Copy the debug keystore** from the Linux machine (`~/.android/debug.keystore`) so a debug build installs over Neal's existing app without uninstalling it. Verify: `keytool -list -v -keystore ~/.android/debug.keystore -storepass android` shows the same SHA-256 on both machines.
- [ ] **Step 3: Baseline.** Run: `./gradlew testDebugUnitTest`. Expected: BUILD SUCCESSFUL, same test count as the last Linux run (see the Plan 9 ledger).
- [ ] **Step 4: Phone.** Run: `adb devices`. Expected: the Pixel 6 listed as `device`. Run `./gradlew :app:installDebug`. Expected: it installs over the existing app, and `adb shell pm list packages app.granger` still lists it. Ask Neal to confirm his library is intact.
- [ ] **Step 5: Pin Media3.** Pick the newest stable `androidx.media3` release on maven.google.com and add `media3 = "<x.y.z>"` and the five library entries above. Add `ndk = "<installed LTS version>"`. Run `./gradlew help`. Expected: success.
- [ ] **Step 6: Roadmap.** Replace the "After v1: Media" row with three rows (M1/M2/M3 from the split table above). Point M1 at this plan and the spec, and add the four-project order from spec §1.
- [ ] **Step 7: Commit**

```bash
git add gradle/libs.versions.toml tools/dev/MAC-SETUP.md docs/superpowers/plans/2026-10-15-granger-v1-roadmap.md
git commit -m "Media M1: Mac toolchain, Media3 and NDK pins, roadmap rows"
```

### Task 2: Audio fixtures and the `:core:taglib` native module

**Files:**
- Create: `tools/dev/make-audio-fixtures.sh`
- Create: `testdata/audio/` (generated fixtures, committed)
- Create: `core/taglib/build.gradle.kts`, `core/taglib/src/main/cpp/CMakeLists.txt`, `core/taglib/src/main/cpp/taglib_jni.cpp`
- Create: git submodule `core/taglib/src/main/cpp/taglib` (TagLib, newest 2.x tag, cloned recursively so `3rdparty/utfcpp` comes too)
- Create: `core/taglib/src/main/kotlin/app/granger/core/taglib/TagLib.kt`
- Test: `core/taglib/src/androidTest/kotlin/app/granger/core/taglib/TagLibDeviceTest.kt`
- Modify: `settings.gradle.kts` (include `:core:taglib`)

**Interfaces:**
- Produces:
  ```kotlin
  data class RawTags(
      /** TagLib PropertyMap: upper-case keys (TITLE, ALBUMARTIST, REPLAYGAIN_TRACK_GAIN, …) to values. */
      val properties: Map<String, List<String>>,
      val durationMs: Long,
      val sampleRate: Int,
      val bitrateKbps: Int,
      val channels: Int,
      /** The first embedded picture (front cover preferred), or null. */
      val cover: ByteArray?,
  )
  object TagLib {
      /** Reads [fd] (opened read-only by the caller; not closed here). Null if TagLib can't parse it. */
      fun read(fd: Int, fileNameHint: String): RawTags?
  }
  ```
- Fixtures (each about 2–3 s, under 100 KB), produced by `make-audio-fixtures.sh` with ffmpeg:
  - `tagged.mp3` (ID3v2.4: title, album, album artist, two artists, composer, genre, disc 1/2, track 3/10, date, original date, sort tags, ReplayGain track/album)
  - `tagged.flac`, `tagged.ogg`, `tagged.opus` (with `R128_TRACK_GAIN`), `tagged.m4a`
  - `untagged.mp3`
  - `gapless-1.mp3` and `gapless-2.mp3` (one continuous sine split in two, LAME-encoded so the encoder delay/padding header is present)
  - `chapters.m4b` (3 chapters)
  - `chapters.mp3` (ID3 CHAP/CTOC, 3 chapters)
  - `broken.mp3` (random bytes)

- [ ] **Step 1: Write the fixture script and generate the fixtures.** Run `tools/dev/make-audio-fixtures.sh`. Expected: the files above exist in `testdata/audio/`, and `ffprobe -show_chapters testdata/audio/chapters.m4b` lists 3 chapters.
- [ ] **Step 2: Write the failing device test** `TagLibDeviceTest`. It copies fixtures from the test assets (`testdata/audio` added as androidTest assets) to the cache dir and opens them with `ParcelFileDescriptor.open(file, MODE_READ_ONLY)`:

```kotlin
@Test fun readsMp3Properties() {
    val t = read("tagged.mp3")!!
    assertThat(t.properties["TITLE"]).containsExactly("Mars, the Bringer of War")
    assertThat(t.properties["ALBUMARTIST"]).containsExactly("Gustav Holst")
    assertThat(t.properties["ARTIST"]).containsExactly("London Symphony Orchestra", "Gustav Holst")
    assertThat(t.properties["REPLAYGAIN_TRACK_GAIN"]).containsExactly("-6.50 dB")
    assertThat(t.durationMs).isIn(Range.closed(1900L, 3100L))
    assertThat(t.cover).isNotNull()
}
@Test fun readsEveryFormat() { listOf("tagged.flac", "tagged.ogg", "tagged.opus", "tagged.m4a").forEach { assertThat(read(it)!!.properties["TITLE"]).isNotEmpty() } }
@Test fun opusKeepsR128Gain() { assertThat(read("tagged.opus")!!.properties["R128_TRACK_GAIN"]).isNotEmpty() }
@Test fun untaggedFileReadsWithEmptyProperties() { assertThat(read("untagged.mp3")!!.properties).isEmpty() }
@Test fun brokenFileReturnsNull() { assertThat(read("broken.mp3")).isNull() }
```

- [ ] **Step 3: Run it to make sure it fails.** Run: `./gradlew :core:taglib:connectedAndroidTest`. Expected: compile failure (`TagLib` is undefined).
- [ ] **Step 4: Implement.**
  - `CMakeLists.txt` builds TagLib as a static library: `BUILD_SHARED_LIBS=OFF`, `BUILD_TESTING=OFF`, `BUILD_EXAMPLES=OFF`, `WITH_ZLIB=OFF`.
  - `taglib_jni.cpp` exposes `Java_app_granger_core_taglib_TagLib_nativeRead(fd, hint)`. It builds `TagLib::FileStream(fd, true)` and `TagLib::FileRef(&stream, true, Average)`, returns properties, audio properties and the first picture (via `complexProperties("PICTURE")`), and catches every C++ exception (returns null).
  - `build.gradle.kts`: ABIs `arm64-v8a` and `x86_64`, `ndkVersion` from Task 1, `externalNativeBuild.cmake.path`.
- [ ] **Step 5: Run the test to verify it passes.** Run: `./gradlew :core:taglib:connectedAndroidTest`. Expected: 5 tests PASS. Then check that `adb shell pm list packages app.granger` still lists `app.granger`.
- [ ] **Step 6: Commit**

```bash
git add .gitmodules core/taglib settings.gradle.kts tools/dev/make-audio-fixtures.sh testdata/audio
git commit -m "Media M1: TagLib JNI module and audio fixtures"
```

### Task 3: `:core:media` tag parsing and chapter reading

**Files:**
- Create: `core/media/build.gradle.kts` (Android library; depends on `:core:taglib`, `api(project(":core:library"))`)
- Create: `core/media/src/main/kotlin/app/granger/core/media/AudioTags.kt`, `TagParser.kt`, `ChapterReader.kt`
- Test: `core/media/src/test/kotlin/app/granger/core/media/TagParserTest.kt`, `ChapterReaderTest.kt` (JVM; fixtures read from `testdata/audio` via a test source-set resources dir)
- Modify: `settings.gradle.kts`

**Interfaces:**
- Consumes: `RawTags` (Task 2).
- Produces:
  ```kotlin
  data class ReplayGain(val trackGainDb: Float?, val trackPeak: Float?, val albumGainDb: Float?, val albumPeak: Float?)
  data class SortTags(val title: String, val album: String, val artist: String, val albumArtist: String)
  data class AudioTags(
      val title: String, val album: String, val albumArtists: List<String>, val artists: List<String>,
      val composers: List<String>, val genres: List<String>, val disc: Int?, val discTotal: Int?,
      val track: Int?, val trackTotal: Int?, val date: String, val originalDate: String,
      /** Lower-case MusicBrainz release type: "album", "ep", "single", "compilation", "live", "soundtrack"…; "" if unknown. */
      val releaseType: String, val sort: SortTags, val replayGain: ReplayGain?, val durationMs: Long,
  )
  object TagParser { fun parse(raw: RawTags?, fileName: String, durationMsFallback: Long): AudioTags }
  data class Chapter(val title: String, val startMs: Long)
  object ChapterReader { fun read(file: java.io.RandomAccessFile): List<Chapter> }   // MP4 (Nero `chpl` + QuickTime chapter track) and ID3v2 CHAP
  ```

- [ ] **Step 1: Write the failing tests.**

```kotlin
// TagParserTest: build RawTags maps by hand (no native code on the JVM)
@Test fun splitsMultiValueArtistsOnSemicolonsAndSlashes()   // "A; B" and "A / B" → [A, B]; also a multi-entry list
@Test fun parsesTrackAndDiscWithTotals()                      // "3/10" → 3, 10
@Test fun parsesReplayGainDbAndPeaks()                        // "-6.50 dB" → -6.5f
@Test fun convertsOpusR128ToReplayGain()                      // R128_TRACK_GAIN "-1280" (Q7.8) → -5.0 dB, plus +5 dB to LUFS-89 reference = 0.0
@Test fun releaseTypeFromMusicBrainzOrReleaseType()           // RELEASETYPE / MUSICBRAINZ_ALBUMTYPE, lower-cased, first value
@Test fun emptyTagsFallBackToFileName()                       // parse(null, "03 - Jupiter.mp3", 1000) → title "03 - Jupiter", album "", artists []
@Test fun albumArtistFallsBackToArtists()                     // no ALBUMARTIST → albumArtists == artists
// ChapterReaderTest (fixtures from Task 2)
@Test fun readsM4bChapters()   { assertThat(read("chapters.m4b").map { it.title }).containsExactly("One", "Two", "Three").inOrder() }
@Test fun readsMp3ChapFrames() { assertThat(read("chapters.mp3")).hasSize(3); assertThat(read("chapters.mp3")[1].startMs).isGreaterThan(0) }
@Test fun noChaptersIsEmpty()  { assertThat(read("tagged.mp3")).isEmpty() }
@Test fun brokenFileIsEmptyNotThrown() { assertThat(read("broken.mp3")).isEmpty() }
```

- [ ] **Step 2: Run to verify they fail.** Run: `./gradlew :core:media:testDebugUnitTest`. Expected: compile failure.
- [ ] **Step 3: Implement `TagParser.parse` and `ChapterReader.read`.** The parser knows the PropertyMap key names. "Unknown album" / "Unknown artist" are display-time fallbacks (Task 5), not stored here. `ChapterReader` walks MP4 boxes (`moov/udta/chpl`, else the `trak` referenced by `tref/chap`) and ID3v2 frames itself. No new dependency.
- [ ] **Step 4: Run to verify they pass.** Run: `./gradlew :core:media:testDebugUnitTest`. Expected: all PASS.
- [ ] **Step 5: Commit**

```bash
git add core/media settings.gradle.kts
git commit -m "Media M1: tag parsing and chapter reading"
```

### Task 4: Library schema v7: media entities, DAOs, `MediaLibrary`

**Files:**
- Modify: `core/library/src/main/kotlin/app/granger/core/library/Entities.kt` (new entities), `LibraryDatabase.kt` (v7, `MIGRATION_6_7`, `mediaDao()`, `playbackDao()`)
- Create: `core/library/src/main/kotlin/app/granger/core/library/MediaEntities.kt`, `MediaDaos.kt`, `MediaLibrary.kt`, `MediaAddress.kt`
- Test: `core/library/src/test/kotlin/app/granger/core/library/MigrationTest.kt` (add 6→7), `MediaLibraryTest.kt`, `MediaAddressTest.kt`
- Generated: `core/library/schemas/app.granger.core.library.LibraryDatabase/7.json`

**Interfaces:**
- Produces (entities, in `MediaEntities.kt`):
  ```kotlin
  enum class MediaOrigin { PHONE, LIBRIVOX, ARCHIVE_ORG }
  @Entity(tableName = "tracks", indices = [Index("albumKey"), Index("audiobookId")])
  data class Track(@PrimaryKey val id: String, val origin: MediaOrigin, val uri: String, val folder: String,
      val title: String, val album: String, val albumKey: String, val albumArtists: String /* "; "-joined */,
      val artists: String, val composers: String, val genres: String, val disc: Int?, val trackNo: Int?,
      val date: String, val originalDate: String, val releaseType: String,
      val sortTitle: String, val sortAlbum: String, val sortArtist: String, val sortAlbumArtist: String,
      val durationMs: Long, val rgTrackGain: Float?, val rgTrackPeak: Float?, val rgAlbumGain: Float?, val rgAlbumPeak: Float?,
      val coverPath: String?, val audiobookId: String?, val status: PackStatus, val addedAt: Long)
  @Entity(tableName = "audiobooks")
  data class Audiobook(@PrimaryKey val id: String, val origin: MediaOrigin, val sourceRef: String, val title: String,
      val author: String, val narrator: String, val description: String, val coverPath: String?,
      val durationMs: Long, val status: PackStatus, val addedAt: Long)
  @Entity(tableName = "audiobook_chapters", primaryKeys = ["audiobookId", "ordinal"])
  data class AudiobookChapter(val audiobookId: String, val ordinal: Int, val title: String, val trackId: String, val startMs: Long, val durationMs: Long)
  @Entity(tableName = "audiobook_states")
  data class AudiobookState(@PrimaryKey val audiobookId: String, val trackId: String, val positionMs: Long, val speed: Float,
      val skipSilence: Boolean, val volumeBoostMb: Int, val finished: Boolean, val updatedAt: Long)
  @Entity(tableName = "audio_bookmarks", indices = [Index("audiobookId")])
  data class AudioBookmark(@PrimaryKey(autoGenerate = true) val id: Long, val audiobookId: String, val trackId: String,
      val positionMs: Long, val title: String, val note: String, val createdAt: Long)
  @Entity(tableName = "playlists") data class Playlist(@PrimaryKey(autoGenerate = true) val id: Long, val name: String, val createdAt: Long, val updatedAt: Long)
  @Entity(tableName = "playlist_entries", primaryKeys = ["playlistId", "ordinal"]) data class PlaylistEntry(val playlistId: Long, val ordinal: Int, val trackId: String)
  /** Single row (id = 0). [queue] and [shuffledOrder] are "\n"-joined track ids / indices. repeatMode: 0 off, 1 all, 2 one. */
  @Entity(tableName = "music_state") data class MusicState(@PrimaryKey val id: Int = 0, val queue: String, val index: Int,
      val positionMs: Long, val shuffle: Boolean, val shuffledOrder: String, val repeatMode: Int, val sourceKey: String, val updatedAt: Long)
  /** [key]: "album:<albumKey>", "audiobook:<id>", "playlist:<id>", "artist:<name>", "genre:<name>", "songs". */
  @Entity(tableName = "recent_plays", indices = [Index("playedAt")]) data class RecentPlay(@PrimaryKey val key: String, val title: String, val playedAt: Long)
  @Entity(tableName = "media_folders") data class MediaFolder(@PrimaryKey val uri: String, val audiobooks: Boolean, val addedAt: Long)
  /** Scanner cache: skip re-fingerprinting and re-tagging an unchanged file. */
  @Entity(tableName = "scan_cache") data class ScanCacheEntry(@PrimaryKey val uri: String, val sizeBytes: Long, val modifiedMs: Long, val fingerprint: String)
  ```
- Produces (`MediaLibrary.kt`):
  ```kotlin
  /** :core:library can't see :core:media's Chapter; the scanner maps each Chapter to a ScannedChapter. */
  data class ScannedChapter(val title: String, val startMs: Long)
  data class ScannedAudiobook(val book: Audiobook, val trackIds: List<String>, val chapters: List<Pair<String, ScannedChapter>>)
  data class ScanResult(val tracks: List<Track>, val audiobooks: List<ScannedAudiobook>)
  interface MediaLibrary {
      val tracks: Flow<List<Track>>            // music only (audiobookId == null), all statuses
      val audiobooks: Flow<List<Audiobook>>
      fun chapters(audiobookId: String): Flow<List<AudiobookChapter>>
      suspend fun track(id: String): Track?
      suspend fun audiobook(id: String): Audiobook?
      /** Replaces [origin]'s catalog with [result]: new rows inserted, changed rows updated, absent rows set MISSING.
       *  Never deletes rows, states, bookmarks or playlist entries. A MISSING row that reappears goes back to OK. */
      suspend fun sync(origin: MediaOrigin, result: ScanResult)
      /** Sets every [origin] row MISSING (permission lost). */
      suspend fun markAllMissing(origin: MediaOrigin)
  }
  class RoomMediaLibrary(db: LibraryDatabase, now: () -> Long = System::currentTimeMillis) : MediaLibrary
  ```
  `ScanResult.tracks` holds every scanned track, audiobook files included (with `audiobookId` set). `ScannedAudiobook.chapters` pairs each chapter with its track id. A file without embedded chapters gives one chapter, titled from its title tag.
- Produces (`MediaDaos.kt`): `PlaybackDao`, with suspend get/upsert for `AudiobookState`, `MusicState` and `ScanCacheEntry`; insert/update/delete plus a `Flow` per audiobook for `AudioBookmark`; upsert plus a `Flow` of the newest N for `RecentPlay`; insert/delete plus a `Flow` for `MediaFolder`. Later tasks call these by these entity names.
- Produces (`MediaAddress.kt`): `object MediaAddress { fun track(id: String, ms: Long): String; fun audiobook(id: String, chapter: Int, ms: Long): String; fun parse(s: String): Parsed? }`, with `sealed interface Parsed { data class TrackAt(val id: String, val ms: Long); data class AudiobookAt(val id: String, val chapter: Int, val ms: Long) }`.

- [ ] **Step 1: Write the failing tests.**

```kotlin
// MigrationTest
@Test fun migrate6To7KeepsDataAndCreatesMediaTables()  // v6 db with a pack + a research turn → after MIGRATION_6_7 all rows intact, the 11 new tables exist, validated against 7.json
// MediaLibraryTest (Robolectric, in-memory db as in TestDatabase.kt)
@Test fun syncInsertsThenMarksAbsentRowsMissing()
@Test fun missingRowComingBackIsOkWithStateKept()       // state + bookmark + playlist entry survive MISSING→OK
@Test fun syncNeverTouchesOtherOrigins()
@Test fun markAllMissingKeepsEverything()
@Test fun tracksFlowExcludesAudiobookFiles()
// MediaAddressTest
@Test fun roundTrips() { assertThat(MediaAddress.parse(MediaAddress.audiobook("audiobook:phone:ab12", 3, 90_500))).isEqualTo(Parsed.AudiobookAt("audiobook:phone:ab12", 3, 90_500)) }
@Test fun rejectsGarbage() { assertThat(MediaAddress.parse("track:x@")).isNull() }
```

- [ ] **Step 2: Run to verify they fail.** Run: `./gradlew :core:library:testDebugUnitTest`. Expected: compile failure.
- [ ] **Step 3: Implement** the entities, `MediaDao` (catalog) and `PlaybackDao` (states, bookmarks, playlists, music state, recents, folders, scan cache), plus `MIGRATION_6_7` (hand-written `CREATE TABLE` statements matching the generated schema, as in `MIGRATION_4_5`), `RoomMediaLibrary` and `MediaAddress`. Add `MIGRATION_6_7` to `LibraryDatabase.create`.
- [ ] **Step 4: Run to verify they pass.** Run: `./gradlew :core:library:testDebugUnitTest`. Expected: all PASS, with the existing tests unchanged.
- [ ] **Step 5: Commit**

```bash
git add core/library
git commit -m "Media M1: library schema v7 for tracks, audiobooks and playback state"
```

### Task 5: Filing albums and audiobooks on the shelves

**Files:**
- Modify: `core/library/src/main/kotlin/app/granger/core/library/Filing.kt`, `Shelves.kt` (`CollectionRow`, `RoomShelves`)
- Create: `core/library/src/main/kotlin/app/granger/core/library/Albums.kt`
- Test: `core/library/src/test/kotlin/app/granger/core/library/ShelvesTest.kt` (add cases), `AlbumsTest.kt`

**Interfaces:**
- Consumes: `MediaLibrary`, `Track`, `Audiobook` (Task 4).
- Produces:
  ```kotlin
  data class AlbumSummary(val key: String, val title: String, val albumArtist: String, val year: String,
      val releaseType: String, val trackCount: Int, val durationMs: Long, val coverPath: String?, val status: PackStatus)
  object Albums {
      /** "<normalised album artist>|<normalised album>"; untitled albums: "|unknown|<folder>". Normalised = trimmed, lower-cased, NFKC. */
      fun key(albumArtist: String, album: String, folder: String): String
      fun summaries(tracks: List<Track>): List<AlbumSummary>   // sorted by sortAlbumArtist then year then sortAlbum
      /** Disc then track number then title. */
      fun ordered(tracks: List<Track>): List<Track>
      const val UNKNOWN_ALBUM = "Unknown album"; const val UNKNOWN_ARTIST = "Unknown artist"
  }
  // CollectionRow gains: val albums: List<AlbumSummary> = emptyList(), val audiobooks: List<Audiobook> = emptyList(); isEmpty covers them
  // Filing gains: fun albumKey(a: AlbumSummary) = "album:${a.key}"; fun defaultKey(origin: MediaOrigin): String ("on-this-phone" for PHONE);
  //   fun defaultName(key) → "On this phone"; defaultMediaType for albums = "music", for audiobooks = "audiobooks"
  // Shelves gains: suspend fun moveAlbum(album: AlbumSummary, toCollectionId: String); suspend fun moveAudiobook(book: Audiobook, toCollectionId: String)
  // RoomShelves constructor gains media: MediaLibrary
  ```

- [ ] **Step 1: Write the failing tests.**

```kotlin
@Test fun phoneAlbumsFileUnderMusicOnThisPhone()
@Test fun phoneAudiobooksFileUnderAudiobooksOnThisPhone()
@Test fun movedAlbumStaysMovedAfterResync()
@Test fun musicShelfHiddenWhenNoTracks()            // existing "hide empty media types" rule holds
@Test fun albumKeyIgnoresCaseAndSpacing()           // ("Holst ", "The Planets") == ("holst", "the planets")
@Test fun untitledTracksGroupByFolder()
@Test fun orderedSortsByDiscThenTrack()
```

- [ ] **Step 2: Run to verify they fail.** Run: `./gradlew :core:library:testDebugUnitTest --tests '*Shelves*' --tests '*Albums*'`. Expected: FAIL.
- [ ] **Step 3: Implement.** Extend `RoomShelves.filables` with albums (from `Albums.summaries(media.tracks)`) and audiobooks, and `shelves` with the new row fields.
- [ ] **Step 4: Run to verify they pass.** Same command, plus the whole `:core:library:testDebugUnitTest`. Expected: PASS.
- [ ] **Step 5: Commit**

```bash
git add core/library
git commit -m "Media M1: file albums and audiobooks into Music and Audiobooks"
```

### Task 6: The phone scanner

**Files:**
- Create: `core/media/src/main/kotlin/app/granger/core/media/FoundFile.kt`, `MediaClassifier.kt`, `AudiobookGrouper.kt`, `Fingerprint.kt`, `MediaStoreSource.kt`, `FolderSource.kt`, `PhoneScanner.kt`
- Test: `core/media/src/test/kotlin/app/granger/core/media/MediaClassifierTest.kt`, `AudiobookGrouperTest.kt`, `FingerprintTest.kt`, `PhoneScannerTest.kt` (Robolectric with fake sources)

**Interfaces:**
- Consumes: `TagLib.read`, `TagParser`, `ChapterReader` (Tasks 2–3); `MediaLibrary.sync`, `markAllMissing`, `PlaybackDao` scan cache and folders (Task 4).
- Produces:
  ```kotlin
  data class FoundFile(val uri: String, val displayName: String, val folder: String /* relative path, e.g. "Audiobooks/Dune/" */,
      val sizeBytes: Long, val modifiedMs: Long, val durationMs: Long, val systemAudiobook: Boolean, val inAudiobookFolder: Boolean)
  interface AudioSource { suspend fun list(): List<FoundFile> }          // MediaStoreSource(context), FolderSource(context, folders)
  object MediaClassifier { fun isAudiobook(f: FoundFile): Boolean }      // systemAudiobook || .m4b || a path segment equal to "Audiobooks" (any case) || inAudiobookFolder
  object AudiobookGrouper {
      /** A file with ≥2 chapters is one book; other audiobook files group by folder into one book, ordered by track tag then natural file-name order. */
      fun group(files: List<Pair<FoundFile, AudioTags>>, chapters: (FoundFile) -> List<Chapter>): List<List<FoundFile>>
  }
  object Fingerprint { fun of(sizeBytes: Long, head: ByteArray, tail: ByteArray): String }  // sha1("$size:" + head + tail), 16 hex chars
  class PhoneScanner(private val sources: List<AudioSource>, private val open: (String) -> ParcelFileDescriptor?,
      private val media: MediaLibrary, private val playback: PlaybackDao, private val coversDir: File,
      private val hasPermission: () -> Boolean) {
      /** Runs one scan; returns how many files were read. Mutex-guarded; a scan already running is joined, not repeated. */
      suspend fun scan(): Int
  }
  ```
- Scan triggers (app side, in this task): on app start; a `ContentObserver` on `MediaStore.Audio.Media.EXTERNAL_CONTENT_URI`, debounced 2 s; a "Rescan" button (Task 12).

- [ ] **Step 1: Write the failing tests.**

```kotlin
@Test fun m4bAndAudiobooksFolderAndSystemFlagAreAudiobooks()
@Test fun plainMp3InMusicIsMusic()
@Test fun folderOfFilesIsOneBookInNaturalOrder()          // "2.mp3" before "10.mp3"
@Test fun singleChapteredFileIsItsOwnBook()
@Test fun fingerprintIsStableAndSizeSensitive()
@Test fun movedFileKeepsIdAndPosition()                    // same bytes, new uri/folder → same track id; AudiobookState untouched
@Test fun unchangedFileIsNotReopened()                     // scan_cache hit → open() not called
@Test fun unreadableFileIsSkippedAndScanContinues()        // open() throws for one file → others synced
@Test fun permissionLostMarksPhoneItemsMissingAndKeepsState()  // hasPermission=false → markAllMissing(PHONE), no source queried
@Test fun folderGrantLostMarksOnlyThatFoldersItemsMissing()
@Test fun scannerNeverOpensForWrite()                      // the fake open() records modes; all are "r"
```

- [ ] **Step 2: Run to verify they fail.** Run: `./gradlew :core:media:testDebugUnitTest`. Expected: FAIL.
- [ ] **Step 3: Implement.**
  - `MediaStoreSource` queries `MediaStore.Audio.Media` (columns `_ID`, `DISPLAY_NAME`, `RELATIVE_PATH`, `SIZE`, `DATE_MODIFIED`, `DURATION`, `IS_AUDIOBOOK`, `IS_MUSIC`, `IS_PODCAST`). It skips ringtones, alarms, notifications and recordings (`IS_MUSIC or IS_AUDIOBOOK or IS_PODCAST` only).
  - `FolderSource` walks persisted SAF trees with `DocumentsContract` children queries.
  - Covers are written to `coversDir/<fingerprint>.jpg`; if none is embedded, `folder.jpg` or `cover.jpg` in the same folder is used.
  - The scanner produces a `ScanResult` and calls `media.sync(MediaOrigin.PHONE, …)`.
- [ ] **Step 4: Run to verify they pass.** Same command. Expected: PASS.
- [ ] **Step 5: Commit**

```bash
git add core/media
git commit -m "Media M1: scan phone music and audiobooks in place"
```

### Task 7: `:core:player` playback rules (pure logic)

**Files:**
- Create: `core/player/build.gradle.kts` (Android library; Media3 deps; `api(project(":core:library"))`)
- Create: `core/player/src/main/kotlin/app/granger/core/player/AutoRewind.kt`, `SleepTimer.kt`, `PositionSaver.kt`, `ReplayGainMath.kt`, `MusicQueue.kt`
- Test: matching `*Test.kt` files under `core/player/src/test/kotlin/app/granger/core/player/`
- Modify: `settings.gradle.kts`

**Interfaces:**
- Produces:
  ```kotlin
  object AutoRewind { fun rewindMs(pausedForMs: Long): Long }   // <10 s → 0; <5 min → 3000; <1 h → 10_000; else 30_000
  sealed interface SleepSetting { data class After(val ms: Long) : SleepSetting; data object EndOfChapter : SleepSetting }
  class SleepTimer(private val now: () -> Long) {
      fun start(setting: SleepSetting, chapterEndsInMs: () -> Long?); fun cancel()
      /** Volume multiplier 0..1 for the current moment: 1 until the last 10 s, then linear to 0; null when no timer. */
      fun volume(): Float?; fun expired(): Boolean; val remainingMs: Long?
  }
  class PositionSaver(private val now: () -> Long, private val write: suspend (SaveReason) -> Unit) {
      enum class SaveReason { TICK, PAUSE, SEEK, CHAPTER, TRACK, STOP }
      /** Called every second while playing; writes on TICK every [intervalMs] (5000 for books, 15_000 for music). */
      suspend fun onTick(intervalMs: Long); suspend fun onEvent(reason: SaveReason)
  }
  enum class ReplayGainMode { OFF, TRACK, ALBUM, AUTO }
  object ReplayGainMath {
      /** Linear gain for [t] under [mode]; AUTO uses album gain when [playingAlbumInOrder]. Clamps so gain × peak ≤ 1. Missing tags → 1f. */
      fun factor(t: Track, mode: ReplayGainMode, playingAlbumInOrder: Boolean, preampDb: Float = 0f): Float
  }
  class MusicQueue(ids: List<String>, index: Int, private val random: kotlin.random.Random) {
      val ids: List<String>; val index: Int; var shuffle: Boolean; var repeat: Int
      fun next(): Int?; fun previous(positionMs: Long): Int    // previous restarts the track if positionMs > 3000
      fun setShuffle(on: Boolean)                              // current track stays current; the rest are shuffled
      fun move(from: Int, to: Int); fun remove(i: Int); fun playNext(newIds: List<String>); fun add(newIds: List<String>)
      fun toState(positionMs: Long, sourceKey: String, now: Long): MusicState
      companion object { fun fromState(s: MusicState, random: kotlin.random.Random): MusicQueue }
  }
  ```

- [ ] **Step 1: Write the failing tests.**

```kotlin
@Test fun rewindSteps() { assertThat(listOf(5_000L, 60_000L, 1_800_000L, 7_200_000L).map(AutoRewind::rewindMs)).containsExactly(0L, 3_000L, 10_000L, 30_000L).inOrder() }
@Test fun sleepTimerFadesOverLastTenSeconds()          // at remaining 5 s → volume 0.5
@Test fun endOfChapterExpiresAtChapterEnd()
@Test fun savesEveryFiveSecondsWhilePlaying()           // 12 ticks of 1 s at 5000 → 2 TICK writes
@Test fun everyEventWritesImmediately()
@Test fun replayGainAlbumModeUsesAlbumGainAndClampsToPeak()
@Test fun replayGainMissingTagsIsUnity()
@Test fun autoModeUsesTrackGainWhenShuffled()
@Test fun shuffleKeepsCurrentTrackFirst()
@Test fun repeatOneReturnsSameIndex(); @Test fun repeatAllWraps(); @Test fun previousRestartsAfterThreeSeconds()
@Test fun stateRoundTrip()                              // toState → fromState gives same ids, index, shuffle order, repeat
```

- [ ] **Step 2: Run to verify they fail.** Run: `./gradlew :core:player:testDebugUnitTest`. Expected: compile failure.
- [ ] **Step 3: Implement** the five files.
- [ ] **Step 4: Run to verify they pass.** Same command. Expected: PASS.
- [ ] **Step 5: Commit**

```bash
git add core/player settings.gradle.kts
git commit -m "Media M1: playback rules: rewind, sleep timer, saves, ReplayGain, queue"
```

### Task 8: The playback service and `PlayerHub`

**Files:**
- Create: `core/player/src/main/kotlin/app/granger/core/player/PlaybackService.kt` (`MediaLibraryService`), `PlayerHub.kt`, `PlayerEngine.kt`, `ReplayGainProcessor.kt`, `FocusPolicy.kt`, `HeadsetAutoplay.kt`, `EffectsSession.kt`
- Create: `core/player/src/main/AndroidManifest.xml` (service with `foregroundServiceType="mediaPlayback"`, `androidx.media3.session.MediaLibraryService` intent filter, permissions `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_MEDIA_PLAYBACK`)
- Test: `core/player/src/test/kotlin/app/granger/core/player/PlayerEngineTest.kt` (Robolectric + `media3-test-utils-robolectric`: `TestExoPlayerBuilder`, `FakeClock`, `TestPlayerRunHelper`)
- Test: `core/player/src/androidTest/kotlin/app/granger/core/player/GaplessDeviceTest.kt`

**Interfaces:**
- Consumes: Task 7 classes; `MediaLibrary`, `PlaybackDao` (Task 4); `Albums.ordered` (Task 5).
- Produces:
  ```kotlin
  data class PlaySource(val key: String, val title: String)   // written to recent_plays
  sealed interface NowPlaying {
      data object Idle : NowPlaying
      data class Music(val track: Track, val index: Int, val queueSize: Int, val shuffle: Boolean, val repeat: Int) : NowPlaying
      data class Book(val book: Audiobook, val chapter: AudiobookChapter, val chapterCount: Int, val speed: Float,
                      val skipSilence: Boolean, val volumeBoostMb: Int) : NowPlaying
  }
  data class PlayerUi(val now: NowPlaying, val playing: Boolean, val positionMs: Long, val durationMs: Long, val sleepRemainingMs: Long?)
  interface PlayerHub {
      val ui: StateFlow<PlayerUi>
      val queue: StateFlow<List<Track>>
      fun playTracks(trackIds: List<String>, startIndex: Int, shuffle: Boolean, source: PlaySource)
      fun playAudiobook(id: String, at: AudioBookmark? = null)
      fun playPause(); fun next(); fun previous()
      /** Audiobooks: ± the skip interval; music: no-op. */
      fun skipBack(); fun skipForward()
      fun seekTo(positionMs: Long); fun seekToChapter(ordinal: Int)
      fun setShuffle(on: Boolean); fun cycleRepeat(); fun moveInQueue(from: Int, to: Int); fun removeFromQueue(i: Int)
      fun playNext(trackIds: List<String>); fun addToQueue(trackIds: List<String>)
      fun setSpeed(speed: Float); fun setSkipSilence(on: Boolean); fun setVolumeBoost(mb: Int)
      fun startSleepTimer(setting: SleepSetting); fun cancelSleepTimer()
      suspend fun addBookmark(title: String): Long
  }
  class MediaControllerPlayerHub(context: Context, media: MediaLibrary, playback: PlaybackDao, scope: CoroutineScope) : PlayerHub
  object PlayerSettingsKeys { /* SharedPreferences keys: "replaygain_mode", "skip_back_s", "skip_forward_s", "headset_autoplay" */ }
  ```
- Behaviour pinned here:
  - One ExoPlayer, created with `handleAudioFocus = false`. `FocusPolicy` owns focus: a transient loss pauses and a regain resumes with `AutoRewind`; a permanent loss pauses and stays paused; ducking lowers the volume.
  - Gapless: the whole queue (or every file of a book) goes in as one `setMediaItems` playlist. Never one player per track.
  - `ReplayGainProcessor` applies `ReplayGainMath.factor` per item.
  - Volume boost uses `android.media.audiofx.LoudnessEnhancer` on the session id.
  - Skip silence uses `exoPlayer.skipSilenceEnabled`.
  - `EffectsSession` broadcasts `AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION` / `CLOSE_…` for external equalizers.
  - Switching modes saves the current mode's state first (`PositionSaver.onEvent(STOP)`), then loads the other's.
  - The service restores the last mode's state on recreation, paused.
  - `HeadsetAutoplay` (`AudioDeviceCallback`, wired headset or Bluetooth A2DP) resumes only when the setting is on (default off).

- [ ] **Step 1: Write the failing tests.**

```kotlin
// PlayerEngineTest (Robolectric, FakeClock, fixtures as file:// items)
@Test fun audiobookPositionSavedOnPauseAndEveryFiveSeconds()
@Test fun restoresAudiobookAfterServiceRecreated()        // new engine on the same db → same track + position, paused
@Test fun switchingToMusicAndBackLosesNoAudiobookPosition()
@Test fun transientFocusLossPausesAndResumesWithRewind()
@Test fun permanentFocusLossStaysPaused()
@Test fun queueIsOnePlaylist()                            // player.mediaItemCount == queue size after playTracks
@Test fun sleepTimerPausesAtEndOfChapter()
@Test fun recentPlayWrittenOnStart()
// GaplessDeviceTest (real decoder on the Pixel 6)
@Test fun lameHeaderGivesEncoderDelayAndPadding()          // Format.encoderDelay > 0 && encoderPadding > 0 for gapless-1.mp3
@Test fun transitionBetweenGaplessFilesHasNoSilence()      // capture PCM via a TeeAudioProcessor; no run of >5 ms of zero samples at the boundary
```

- [ ] **Step 2: Run to verify they fail.** Run: `./gradlew :core:player:testDebugUnitTest`. Expected: compile failure.
- [ ] **Step 3: Implement** the service, engine, hub and helpers. `PlayerEngine` holds the logic and takes an `ExoPlayer` so tests can inject `TestExoPlayerBuilder`. `PlaybackService` wraps it with a `MediaLibrarySession`. Leave the browse tree empty here; M3 fills it for Android Auto.
- [ ] **Step 4: Run to verify they pass.** Run: `./gradlew :core:player:testDebugUnitTest` (PASS), then `./gradlew :core:player:connectedAndroidTest` (2 PASS). Then check that `adb shell pm list packages app.granger` still lists it.
- [ ] **Step 5: Commit**

```bash
git add core/player
git commit -m "Media M1: playback service, focus, gapless, ReplayGain, PlayerHub"
```

### Task 9: Mini-player, full-screen players and the Player rail tool

**Files:**
- Create: `app/src/main/kotlin/app/granger/player/PlayerViewModel.kt`, `MiniPlayer.kt`, `MusicPlayerScreen.kt`, `AudiobookPlayerScreen.kt`, `QueueSheet.kt`, `ChaptersSheet.kt`, `SleepTimerSheet.kt`, `SpeedSheet.kt`, `AudioBookmarksSheet.kt`
- Modify: `app/src/main/kotlin/app/granger/Features.kt` (`PLAYER = true`), `GrangerApp.kt` (wire `MediaLibrary`, `PhoneScanner`, `PlayerHub`), `GrangerNavHost.kt` (`PlayerRoute`, Player tool toggles, `miniPlayer` slot), `shell/GrangerShell.kt` (pass the slot), `app/build.gradle.kts` (depend on `:core:media`, `:core:player`)
- Test: `app/src/test/kotlin/app/granger/player/PlayerViewModelTest.kt`, `MiniPlayerTest.kt` (Robolectric Compose)

**Interfaces:**
- Consumes: `PlayerHub`, `PlayerUi`, `NowPlaying` (Task 8).
- Produces: `@Serializable object PlayerRoute`, and `PlayerViewModel(hub: PlayerHub)` exposing `ui`, `queue` and actions. `MiniPlayer(ui: PlayerUi, onPlayPause, onSecondary, onOpen)`, where the secondary button is next track for music and skip back for books.
- Mini-player rules (spec §8):
  - It's the same composable for both modes, at the same height (64 dp, Claude's call), showing cover, title, subtitle, play/pause, a progress line and one secondary button.
  - It's shown on non-reading screens whenever `now != Idle`. In immersive reading it shows with the reader controls (Plan 5 rule).
  - The Player tool opens `PlayerRoute`. Tapping it again, or Back, returns to the mini-player.

- [ ] **Step 1: Write the failing tests.**

```kotlin
@Test fun miniPlayerHiddenWhenIdle()
@Test fun miniPlayerSameLayoutForMusicAndBook()       // same test tags present for both; only the secondary icon's content description differs ("Next track" / "Back 30 seconds")
@Test fun secondaryButtonCallsNextForMusicAndSkipBackForBooks()
@Test fun playerToolOpensFullPlayerAndToggles()
```

- [ ] **Step 2: Run to verify they fail.** Run: `./gradlew :app:testDebugUnitTest --tests '*player*'`. Expected: FAIL.
- [ ] **Step 3: Implement.**
  - Full music player: cover, title/artist/album, seek bar, previous/play/next, shuffle, repeat, queue sheet with drag-reorder and remove.
  - Full audiobook player: cover, book/chapter title, seek bar within the chapter plus overall progress, skip back/forward, previous/next chapter, chapters sheet, speed sheet (0.5–3.0×, 0.1 steps), skip-silence and volume-boost toggles, sleep timer sheet (5/10/15/30/45/60 min, end of chapter), bookmark button and list.
  - Visuals follow the Neural Flame theme.
- [ ] **Step 4: Run to verify they pass.** Same command, plus the whole `:app:testDebugUnitTest`. Expected: PASS.
- [ ] **Step 5: Commit**

```bash
git add app
git commit -m "Media M1: mini-player, music and audiobook players, Player tool"
```

### Task 10: Music browsing and playlists

**Files:**
- Create: `app/src/main/kotlin/app/granger/music/MusicViewModel.kt`, `MusicScreen.kt` (tabs), `AlbumScreen.kt`, `ArtistScreen.kt`, `GenreScreen.kt`, `PlaylistScreen.kt`, `FolderScreen.kt`, `AddToPlaylistSheet.kt`
- Create: `core/library/src/main/kotlin/app/granger/core/library/Playlists.kt`
- Modify: `GrangerNavHost.kt` (routes), `library/CollectionScreen.kt` / `LibraryScreen.kt` (Music shelf: "All music" row first; a Music collection opens `MusicRoute(collectionId)`)
- Test: `core/library/src/test/kotlin/app/granger/core/library/PlaylistsTest.kt`, `app/src/test/kotlin/app/granger/music/MusicViewModelTest.kt`

**Interfaces:**
- Consumes: `MediaLibrary`, `Albums`, `Shelves` (Tasks 4–5); `PlayerHub.playTracks`, `playNext`, `addToQueue` (Task 8).
- Produces:
  ```kotlin
  interface Playlists {   // app-level function surface for project 4 (spec §10)
      val all: Flow<List<Playlist>>
      fun tracks(playlistId: Long): Flow<List<Track>>
      suspend fun create(name: String, trackIds: List<String> = emptyList()): Long
      suspend fun rename(id: Long, name: String); suspend fun delete(id: Long)
      suspend fun add(id: Long, trackIds: List<String>); suspend fun move(id: Long, from: Int, to: Int); suspend fun remove(id: Long, ordinal: Int)
  }
  class RoomPlaylists(db: LibraryDatabase, now: () -> Long = System::currentTimeMillis) : Playlists
  @Serializable data class MusicRoute(val collectionId: String? = null)
  @Serializable data class AlbumRoute(val albumKey: String)
  @Serializable data class ArtistRoute(val name: String)
  @Serializable data class GenreRoute(val name: String)
  @Serializable data class PlaylistRoute(val playlistId: Long)
  @Serializable data class FolderRoute(val folder: String)
  ```
- Artists merge album artists with track artists (Auxio rule). An artist page lists their albums grouped by release type (Albums, EPs, Singles, Compilations, Live…, from `Track.releaseType`; untyped → Albums), then "Appears on".
- MISSING tracks show greyed out, aren't playable, and are skipped when queueing.

- [ ] **Step 1: Write the failing tests.**

```kotlin
@Test fun playlistSurvivesTrackGoingMissingAndBack()
@Test fun createWithTracksKeepsOrder()
@Test fun moveReordersOrdinals()
@Test fun allMusicIgnoresCollectionsButCollectionScopes()
@Test fun artistsMergeAlbumArtistsAndTrackArtists()
@Test fun missingTracksAreSkippedWhenPlayingAnAlbum()
```

- [ ] **Step 2: Run to verify they fail.** Run: `./gradlew :core:library:testDebugUnitTest --tests '*Playlists*' :app:testDebugUnitTest --tests '*music*'`. Expected: FAIL.
- [ ] **Step 3: Implement** the screens:
  - Songs list with sort (title, artist, album, year, date added), album grid with square covers, artist list, genre list, playlist list (create/rename/delete), folder list.
  - Long-press menus: play next, add to queue, add to playlist, go to album/artist, move (album → collection).
- [ ] **Step 4: Run to verify they pass.** Same command. Expected: PASS.
- [ ] **Step 5: Commit**

```bash
git add core/library app
git commit -m "Media M1: music browsing (songs, albums, artists, genres, folders) and playlists"
```

### Task 11: Audiobook shelf and book page

**Files:**
- Create: `app/src/main/kotlin/app/granger/audiobooks/AudiobooksViewModel.kt`, `AudiobookShelfScreen.kt`, `AudiobookScreen.kt`
- Modify: `GrangerNavHost.kt` (routes), `library/CollectionScreen.kt` (an Audiobooks collection opens the shelf)
- Test: `app/src/test/kotlin/app/granger/audiobooks/AudiobooksViewModelTest.kt`

**Interfaces:**
- Consumes: `MediaLibrary.audiobooks`, `chapters`; `PlaybackDao` states and bookmarks (Task 4); `PlayerHub.playAudiobook` (Task 8).
- Produces: `@Serializable data class AudiobookShelfRoute(val collectionId: String? = null)`, `@Serializable data class AudiobookRoute(val audiobookId: String)`. `AudiobooksViewModel` sorts the shelf by last played (from `audiobook_states.updatedAt`), then title. Unplayed books come after played ones, finished books last.
- Book page: cover, title, author, narrator, progress, Play/Resume, chapter list (tap to play from that chapter), bookmarks (tap to play, edit note, delete), and "Mark as finished" / "Mark as unfinished".

- [ ] **Step 1: Write the failing tests.**

```kotlin
@Test fun shelfOrdersByLastPlayedThenUnplayedThenFinished()
@Test fun progressIsChapterOffsetPlusPositionOverDuration()
@Test fun markFinishedThenResumeStartsFromBeginning()
@Test fun missingBookShowsMissingAndCannotPlay()
```

- [ ] **Step 2: Run to verify they fail.** Run: `./gradlew :app:testDebugUnitTest --tests '*audiobooks*'`. Expected: FAIL.
- [ ] **Step 3: Implement** the screens and view model.
- [ ] **Step 4: Run to verify they pass.** Same command. Expected: PASS.
- [ ] **Step 5: Commit**

```bash
git add app
git commit -m "Media M1: audiobook shelf and book page"
```

### Task 12: Media settings, permission flow and Recent plays

**Files:**
- Create: `app/src/main/kotlin/app/granger/settings/MediaSettingsSection.kt`, `app/src/main/kotlin/app/granger/media/MediaPermission.kt`
- Modify: `settings/SettingsScreen.kt`, `settings/SettingsViewModel.kt`, `shell/RailViewModel.kt` (`RecentItem.Play`), `shell/Rail.kt`, `app/src/main/AndroidManifest.xml` (permissions)
- Test: `app/src/test/kotlin/app/granger/shell/RailViewModelTest.kt` (add), `app/src/test/kotlin/app/granger/settings/MediaSettingsTest.kt`

**Interfaces:**
- Consumes: `PhoneScanner.scan`, `PlaybackDao` folders and recents (Tasks 4, 6); `PlayerHub` (Task 8).
- Produces: `RecentItem.Play(val key: String, val title: String, override val at: Long)`. Tapping it resumes: `audiobook:` → `playAudiobook`; `album:`/`playlist:`/`artist:`/`genre:`/`songs` → rebuild that list and `playTracks`. If the source no longer exists, it opens the Library with a notice.
- Settings → **Music & audiobooks**:
  - "Find music and audiobooks on this phone": the permission request, with the current status.
  - "Add a folder", in two kinds: Music folder and Audiobooks folder. Uses `ACTION_OPEN_DOCUMENT_TREE` with `takePersistableUriPermission(READ)`. A list with remove.
  - "Rescan now".
  - ReplayGain mode (Off / Track / Album / Auto), skip back and forward intervals, and headset autoplay.
- The Library's empty Music/Audiobooks state links to this section. M2 adds the source links.

- [ ] **Step 1: Write the failing tests.**

```kotlin
@Test fun recentMixesPlaysWithSearchesAndChatsNewestFirst()
@Test fun recentPlayForRemovedSourceOpensLibraryWithNotice()
@Test fun removingAFolderReleasesItsGrantAndRescans()
@Test fun deniedPermissionShowsGrantButtonAndNoCrash()
```

- [ ] **Step 2: Run to verify they fail.** Run: `./gradlew :app:testDebugUnitTest --tests '*Rail*' --tests '*MediaSettings*'`. Expected: FAIL.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run to verify they pass.** Same command, then all unit suites: `./gradlew testDebugUnitTest`. Expected: PASS.
- [ ] **Step 5: Commit**

```bash
git add app
git commit -m "Media M1: media settings, folders, permission flow, Recent plays"
```

### Task 13: Pixel 6 walkthrough, parity checklist, roadmap

**Files:**
- Create: `docs/superpowers/plans/media-parity-checklist.md` (every row of spec §6 and §7: ✅ done in M1 / ⏭ M3 (Auto, widget, Search) / ❌ gap with reason)
- Modify: `docs/superpowers/plans/2026-10-15-granger-v1-roadmap.md` (M1 Done, "Carried forward from M1")

- [ ] **Step 1: Install over the existing app.** Run: `./gradlew :app:installDebug`. Expected: installs, and Neal's library is still there.
- [ ] **Step 2: Walk the spec §13 items that M1 covers, on the Pixel 6, with Neal present for anything audible.** Put a few music albums and one folder audiobook (including a gapless album, e.g. a classical work with attacca movements) on the phone first. For each check, record pass or fail in the checklist file.
  - Phone media appears without being moved: compare `adb shell ls -l` on the files before and after.
  - Deleting a file in the Files app marks it missing. Restoring it brings back its position.
  - An audiobook resumes at the right spot after `adb shell am force-stop app.granger`.
  - Switching audiobook → music → audiobook loses no position.
  - A gapless album plays without gaps (Neal listens).
  - The mini-player is the same for both, across Library, Search, Research and immersive reading.
  - A phone call (or a voice-assistant prompt) mid-audiobook pauses and resumes with rewind.
- [ ] **Step 3: Fill in the checklist.** Each ❌ needs a one-line reason and a plan (M2, M3 or later).
- [ ] **Step 4: Update the roadmap.** M1 → Done, plus a "Carried forward from M1" list with every open item.
- [ ] **Step 5: Commit**

```bash
git add docs/superpowers/plans
git commit -m "Media M1: Pixel 6 walkthrough, parity checklist, roadmap"
```
