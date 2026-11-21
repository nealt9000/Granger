# Plan I4: packs and downloads on the iPhone

Spec: `docs/superpowers/specs/2026-11-20-iphone-design.md` (§ I4 Packs and downloads). Parity list: `docs/superpowers/ios-parity.md`. Follows I3 (merged main f9c751f6, release ios-core-2026-11-20-5).

**Goal.** The iPhone holds Kiwix packs the way Android does: Get more from the signed catalog, one download queue that survives the app being closed, packs read and searched with libkiwix, a pack's own files, import from Files, book packs as collections, onboarding's start sets, Outfitting and Ready offline, Home's feeds and the Day page. When I4 merges, every parity row marked I4 is ticked or carries an agreed reason.

**Shape (as I3).** I4a moves Android's pack, catalog and download rules into the shared core with no change for the Android user, checked on the Pixel 6, and merges on its own. I4b builds the iPhone side and merges after the Simulator check.

## Builder's calls in this plan (await Neal)

1. **CoreKiwix from Kiwix's prebuilt xcframework (libkiwix 14.2.1), as a second binary target in `ios/GrangerCore`.** The same libkiwix version as Android's java-libkiwix 2.6.0 line, called from Swift through C++ interop. A small Swift class implements the Kotlin `ZimArchive` interface (open, resolve, read, random, search, suggestions), so all shared code reads a pack the same way on both phones. The alternative, building libkiwix from source with kiwix-build, is slower to set up and gains nothing until we need a patch. Licence: the same GPL exposure the Kiwix app carries on the App Store; that question stays with I6/I8, as the spec says (no change for dev friends building from source).
2. **Packs live in `Documents/Packs`, visible in Files ("On My iPhone › Granger"), excluded from iCloud backup.** That's the iPhone's version of Android's "Show in other apps": the files are yours, and you can see, copy and delete them. Indexes, saved and kept stay in Application Support as now.
3. **Downloads on a URLSession background session**, one item at a time in Android's queue order. Pause, resume and Pause all; speed and time left; the Wi-Fi rule as `allowsExpensiveNetworkAccess = false`; sha256 checked before a file is moved in. A transfer the system ends when the app is force-quit resumes by HTTP Range with the ETag on the next start, and the downloads card says so.
4. **The queue's rules move to the shared core; the transfer stays native.** `DownloadRecord`, the queue order, `QueuePolicy`, `Sidecars` (which sidecars follow a pack), `OnlineIndexes` (the online index set, which I3 fetched with a plain download and now joins the queue) and the integrity rules go to a KMP module. Android keeps its WorkManager runner; the iPhone gets a URLSession runner. The records table is the same Room table on both.
5. **Get more, Outfitting and Ready offline share their models.** `KiwixList`, `CatalogScope`, `PackOffer`, `WouldTake`, `ReadinessModel`, `ReadyOffline` and Outfitting's model move to `:core:screens` (the code I3 listed as waiting), so the lists, plain words, sizes and the nine domains are the same; SwiftUI draws them.
6. **Import a pack with the Files picker, copied in** (not opened in place). A copy in `Documents/Packs` can't vanish when another app tidies up, and it shows in Files beside the downloaded ones.
7. **Onboarding's start sets in I4, its voice step in I5.** The first run is the brand, language, Pick a start (Starter / Efficient / Comprehensive, Starter the default) and quick wins, as Android's. The Granger voice step joins when the voices do.
8. **Home's feeds, Add feed, scopes and the Day page come with packs.** Their scopes are packs and collections, and the Day page reads a pack's day article. Ready offline and its Outfitting link join Home in the order Arrange Home already keeps.

## Global constraints

- **Android must not change for the user (I4a).** The whole JVM suite passes, `:app:bundleRelease` builds with R8, and a Pixel 6 walkthrough of Get more, a download (pause and resume), a pack article, pack search, Outfitting and Ready offline passes on a debug build installed over the phone's data. Check `lastUpdateTime` and the DB version first, use `adb -s` with the Pixel's serial, and start `app.granger/.MainActivity` explicitly.
- **No migration unless a task needs one.** If one does (the records table moving), it's ported like 18 and named here first.
- The repo is on the X10 and must be mounted. One Gradle or Kotlin/Native build at a time.
- Pushing and releases have standing OK, after the noreply author check.
- Branch `i4-packs` off main f9c751f6, worktree `.claude/worktrees/i0`.

## I4a: the shared rules

### Task 1: Downloads to the core
- [x] A KMP `:core:downloads-api` (Android + iOS): records, queue order, QueuePolicy, the sidecar rules, OnlineIndexes, integrity (sha256 via the shared `sha256`), TransferRate. `:core:downloads` keeps the Android runner and re-exports the API. The tests move with the code.
  - Done. `:core:downloads-api` holds the Room `downloads` table (DB 4, schemas moved, no migration), `DownloadManager`, `DownloadQueue`, `DownloadRunner`, `QueuePolicy`, `TransferRate`, `Sidecars`, `EmbedderDownloads`, the queue summary and `Integrity.quickFile`. Paths are strings; Android's `file`/`partFile` and File-taking `enqueue`/`enqueueGroup` live in `:core:downloads` (`DownloadFiles.kt`). The transfer is the new `FileTransfer` interface: Android's OkHttp `FileDownloader` implements it, the iPhone's URLSession transfer will (Task 6). `QueueText` (Android wording), the WorkManager worker, voices, volumes and the local-file copy stay Android. `OnlineIndexes` moved to `:core:screens`. The DAO's progress write is now `suspend` (the runner calls it with `runBlocking` from the transfer's thread, as before). The JVM tests stay in `:core:downloads` (159/159, migrations included) and app download tests pass (458/458); `QueueIosTest` runs the manager, queue and runner on the Simulator (2/2).

### Task 2: Catalog lists and readiness to the core
- [x] KiwixList, CatalogScope, PackOffer, WouldTake, ReadinessModel, ReadyOffline and Outfitting's model to `:core:screens`; string keys through ScreenTextIds and ScreenText.
  - Done. Also RoomOffer/RoomOffers/KeepProgress (`online/Offers.kt`, with WouldTake and OutfittingState/Actions) and `SearchText` (to `:core:text`). New shared platform words: `formatFixed`, `formatInteger`, `languageDisplayName`, `monthYear`, `mediumDate`, plus `CivilDate` (no java.time in common) and `bytesText` (Android's `formatBytes` words). `CatalogScope` and `Source` name their titles by key; Android's `title` extensions resolve them. `ReadinessModel.report` takes today as an epoch day. `make_text_ids.py` now also maps keys the shared code names as plain literals (a flavour label, a Folded noun), so a key passed through a `when` can't miss its Android id. Library 534/534, app readiness/sources/online/home/onboarding 543/543, Simulator `CatalogWordsIosTest` 3/3.

### Task 3: Library and search over any ZimArchive
- [x] The parts of `:core:library` the iPhone needs (pack rows, LibrarySearch across packs and online, IndexPages, ManifestBooks, MentionIndex, NautilusDatabase, BookLinks) on `ZimArchive` and the shared store, not java-libkiwix types.
  - Done for pack rows and search. `PackLibrary` (was RoomLibrary: scan, open by path, rows, inherit, remove, supersede, the archive cache) and `PackSearch` (was ZimLibrarySearch: titles, near titles, full text, title-only packs, slices, book packs' hits as books) are in `:core:store` with `Library`, `TitleMatch`, `BookTitleSearch`, `SliceDedupe`, `NautilusDatabase` and `GutenbergPaths`. Android's `RoomLibrary` and `ZimLibrarySearch` are thin subclasses keeping their constructors; `LibrarySearch` adds book text (BookIndex) to the shared `TitleSearch`; `Filing` stays Android (albums, manifests), passed to the search as `collectionOf`. IndexPages was already shared (I3). Moved later with their screens: `BookManifest`/`ManifestBooks` (Android SQLite → the store's `SqlFile`) to Task 8 with book packs as collections; `MentionIndex` and `BookLinks` to I5 with Linked Classics. Library 534/534, app search/library/reader/sources 467/467, Simulator `PackLibraryIosTest` (scan, a damaged file, open, titles, full text, remove) 1/1.

### Task 4: I4a check and merge
- [x] JVM suite, bundleRelease with R8, the Pixel 6 walkthrough above. GrangerCore release, pinned. Merge and push.
  - JVM 3863/3863 (testDebugUnitTest + jvmTest), bundleRelease with R8 builds. Pixel 6 on debug 1647 over its data (DB 18, downloads 4, unchanged), walked 2026-11-20: Home's Ready offline and feeds, Outfitting (Your use, kept, Saved for now, storage, By source), Search (pack titles and full text with snippets), a pack article's topic page (trail from the QID index) and reader, Get books (catalog lists), a 43 MB Prunelle pack queued, run, verified and installed as a book collection, a 307 MB one paused (59 MB kept), resumed from its part file, cancelled (part file gone), the first removed. No crash in the log.
  - **Found on the way, fixed on main 136151e2 (an I3 bug):** Outfitting called the three big Wikipedias "Incomplete · Download again". Since I3, Android opened sidecars read-write with `query_only`, and the framework wrote its `android_metadata` table into each .gqid and .gtime on first read: each grew a page, left a journal and no longer matched its checksum. Now `OPEN_READONLY | NO_LOCALIZED_COLLATORS` through `AndroidSQLiteConnection`, as before I3; `SidecarReadOnlyTest` fails without it. Only the Pixel 6 had I3-era builds (the Pixel 7's 1625 predates I3; granger-62 checked, no journals). Its six altered sidecars were restored from their releases (sha256 checked) and stay unchanged after the fixed build reads them; Outfitting shows Kiwix · 7 items, none needing attention.

## I4b: the iPhone

### Task 5: CoreKiwix and the pack reader
- [ ] CoreKiwix binary target; a Swift `KiwixArchive` for the Kotlin `ZimArchive`; packs opened from `Documents/Packs`; the reader serves pack entries through `granger-zim:` (redirects, pictures, PDF/EPUB inside packs handed on); pack search and suggestions in Search's Best matches, Titles, Articles and By kind.
  - Spike passed 2026-11-20 (well inside the hour): Kiwix's prebuilt `CoreKiwix.xcframework` 14.2.1-2, fetched and sha256-checked by `tools/ios/fetch-corekiwix.sh` into git-ignored `ios/Vendor` (130 MB of static libraries stay out of git; a SwiftPM binary target would need it re-zipped and re-hosted), linked by the app target, bridged by an Objective-C++ `GZimBridge` (recursive lock, cached searchers, as Android's) under the Swift `KiwixArchive`. `KiwixArchiveTests` on a real pack.
  - Done: packs in `Documents/Packs` (excluded from backup) through the shared `PackLibrary`; the reader serves pack pages and pictures (redirects resolve); a pack article's topic page (title, lead, plate picture from its HTML; no Keep); `packTopic`/`packReader` routes; Search lists pack titles and full text first, also with Local only on, then Wikipedia online's. UI test `testPackSearchTopicAndReader` (`-debugPack`). Found on the way: a topic opened at launch could come before the index set and never get its trail and years; it now loads again when the index set opens.
  - Left for Task 9: Best matches and By kind in Search, PDF/EPUB inside packs handed on.

### Task 6: Get more and downloads
- [ ] Get more (the Kiwix list with flavours, sizes and plain words; the Wikipedia family by how much × pictures; subject add-ons). The URLSession runner on the shared queue: order, Pause all, speed and time left, the Wi-Fi rule, sha256, resume after force-quit. The downloads card on Home. The online index set moves into the queue.
  - Done: `SourceViewModel` (Get more) moved to `:core:screens` (Android unchanged, 461/461; `CatalogState`/`CatalogSource` to `:core:catalog-api`, `DownloadVolume(s)` to `:core:downloads-api`). The iPhone's `UrlSessionTransfer` (Kotlin, a background URLSession: tasks tagged by part path, resume data kept on Pause or Cancel and when the system ends a task, sha256 by CommonCrypto, the Wi-Fi rule per request) runs under the shared runner and queue, drained while the app runs; `DownloadLayout` puts packs in `Documents/Packs` and their sidecars under a hidden `Documents/.granger/` (Android unchanged). `IosCatalogSource` over Swift's verified catalog (release build's filter: evaluated models, no vectors). SwiftUI: Get more hub (Kiwix, Books by kind), the source screen over the shared model, Downloads (Now, Up next with reasons, Stopped, Done today; Pause all / Resume), Home's downloads card, Settings' Download on Wi-Fi only, NWPathMonitor, the app delegate's background-session hook. UI test `testGetMoreDownloadPauseResume`: a 43 MB pack from the live catalog paused at 25 MB, resumed, installed with its book manifest, removed.
  - Left: the Wikipedia set row's slice and picture choices drawn on the iPhone, the online index set joining the queue, a force-quit resume check by hand.

### Task 7: Packs in Files, import, Browse
- [x] Packs visible in Files; Import a pack (Files picker, copied in); Your sources and Browse a pack's files; greyed pack tiles under Local only.
  - Done: Documents shows in the Files app (`UIFileSharingEnabled`, `LSSupportsOpeningDocumentsInPlace`): packs in On My iPhone › Granger › Packs, their sidecars hidden under `.granger/`. Import a pack: the picked .zim is opened (a pack? already here? room?) and copied into Documents/Packs; a .zim put there from Files is listed when Granger next comes to the front (no download record: the library lists the folder, and Readiness counts it as Android counts a hand-pushed pack). Your sources (Explore's Ways on): Wikipedia online (greyed with Local only on), then the packs by knowledge area, Other, Maps; each opens its main page, Files in this pack (PDFs and EPUBs, `BookFiles`/`PackFiles` now shared; a file opens in Quick Look), Remove. Tests: `PackImportTests`, `testYourSources`; library 535/535.
  - The last good catalog (and its signature) is now kept in Application Support/granger/catalog and verified again when read, as Android's cache; offline it names the packs' areas and Get more's lists. Found on the way: Swift's catalog fetch didn't ask the gate (Get more's refresh could go out under Local only); it does now.

### Task 8: Onboarding's sets, Outfitting, Ready offline, feeds
- [x] The first run's Pick a start with quick wins; Outfitting (your use and the nine domains, would-take sizes, kept-article updates); Ready offline on Home; feeds, Add feed, scopes and the Day page; book packs as collections (reading waits for I5); a broad topic's figures from packs.
  - Done (models moved by subagents with Android unchanged: `OnboardingViewModel` 220/220, Home feeds/Add feed/Day page 152/152 + HomeFeeds 4/4; `KeptUpdates` moved here):
    - **First run:** full-screen before the privacy line. Start reading now (to Explore) or Outfit for offline; Pick a start (Starter by default); a second language, the region map and quick wins; Review with free space and the Wi-Fi rule; Start or Skip. The voice step stays off until I5.
    - **Ready offline on Home:** in Arrange Home's readiness slot.
    - **Outfitting:** Try Local only, Your use, kept articles with "N have a newer version · Update all", Saved for now, the knowledge domains from the shared ReadinessModel (Get the missing essential), and storage by source.
    - **Feeds:** drawn where Arrange Home places them (at most two); the defaults wait for their packs. Home's + is Add feed, as on Android's header, and Get more moves to the download icon. The Day page model feeds the "On this day" feed.
    - **Broad topics:** figures come from a pack's own pictures.
    - **Book packs as collections:**
      - `BookManifest`, `GutenbergList` and the new `PackBookList` were moved by a subagent, Android unchanged: library 535/535, app library 103/103, store iOS 9/9.
      - The iPhone attaches verified `.gbooks` manifests (and pure containers) to their packs as Android's BookManifests does.
      - Your sources has a Books section; each book pack opens as its books by section, and a book opens in Quick Look (Granger's readers are I5).
    - Tests: `testReadyOfflineAndOutfitting`, `testHomeFeeds`, `testOnboardingPickAStart`, `testBookPackCollection`.

### Task 9: Simulator check, parity rows, merge
- [ ] The UI smoke test grows: get a small pack (a subject add-on), pause and resume, open an article from it, find it in Search, see it in Files, Outfitting's sizes. Walkthrough with screenshots (Field, Night, largest Dynamic Type, landscape, VoiceOver tree). Tick the I4 rows, GrangerCore release, merge and push, write Plan I5.
  - **Done:**
    - **Search and the reader:** Best matches over packs (keyword-only; the shared `PackPassageFinder`, moved by a subagent: library 535/535, search 63/63). A pack's PDF or EPUB link opens in Quick Look.
    - **Downloads:** resume after the app is killed passes (`testDownloadSurvivesTheAppBeingKilled`).
    - **Tests:** iPhone 24/24 (get, pause, resume, install and remove a live 43 MB pack; pack search, topic and reader; Files import; Your sources; book packs; Get more's Wikipedia set; first run; Ready offline and Outfitting; feeds). JVM 3864/3864, bundleRelease with R8.
    - **Night and the largest Dynamic Type:** walked; text scales and wraps, no broken words. Three tests stop at their scroll limits at that size, which is test reach, not layout.
    - **Parity:** I4 rows ticked.
  - **Left, with reasons:**
    - The online index set still comes by the iPhone's own sha256-checked fetch, not the queue; it works and the queue gains nothing the user sees but a line in Downloads.
    - Books and By kind in Search, and search by meaning: I5 (books) and an embedder.
    - VoiceOver: checked by tree only, as before.

## Order and size

I4a is mostly moving code behind interfaces that already exist (I1's `ZimArchive`, I3's store), so it's smaller than I3a. I4b's risk is CoreKiwix: Task 5 opens with a one-hour spike (open a pack, read an entry, search) before anything else is built on it, and the plan stops there to report if it fails.

**Pixel 6 check of the moved Android code (2026-11-21, debug 1669, DB 18/4):** passed. Home (Continue, Ready offline, feeds), Add feed, Search (Best matches, pack titles, book-pack titles), Library's book-pack collections, Get more → Kiwix (flavours, sizes, storage line), a 21 MB download kept, installed and removed, Settings → Outfit for offline → Review. One fix: a pack book's title row named its pack by file name (`zimgit-knots_en`); it was the same on main before the move, fixed in `60dc9016` with a test, released as `ios-core-2026-11-21-2`. Noted, not fixed (same on main): Add feed opens above the list's place, so it looks as if nothing happened until you scroll up; its scope list repeats names where a pack and a collection share one ("Public Domain Recipes") and the two "On this phone" collections.
