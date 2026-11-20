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
- [ ] A KMP `:core:downloads-api` (Android + iOS): records, queue order, QueuePolicy, the sidecar rules, OnlineIndexes, integrity (sha256 via the shared `sha256`), TransferRate. `:core:downloads` keeps the Android runner and re-exports the API. The tests move with the code.

### Task 2: Catalog lists and readiness to the core
- [ ] KiwixList, CatalogScope, PackOffer, WouldTake, ReadinessModel, ReadyOffline and Outfitting's model to `:core:screens`; string keys through ScreenTextIds and ScreenText.

### Task 3: Library and search over any ZimArchive
- [ ] The parts of `:core:library` the iPhone needs (pack rows, LibrarySearch across packs and online, IndexPages, ManifestBooks, MentionIndex, NautilusDatabase, BookLinks) on `ZimArchive` and the shared store, not java-libkiwix types.

### Task 4: I4a check and merge
- [ ] JVM suite, bundleRelease with R8, the Pixel 6 walkthrough above. GrangerCore release, pinned. Merge and push.

## I4b: the iPhone

### Task 5: CoreKiwix and the pack reader
- [ ] CoreKiwix binary target; a Swift `KiwixArchive` for the Kotlin `ZimArchive`; packs opened from `Documents/Packs`; the reader serves pack entries through `granger-zim:` (redirects, pictures, PDF/EPUB inside packs handed on); pack search and suggestions in Search's Best matches, Titles, Articles and By kind.

### Task 6: Get more and downloads
- [ ] Get more (the Kiwix list with flavours, sizes and plain words; the Wikipedia family by how much × pictures; subject add-ons). The URLSession runner on the shared queue: order, Pause all, speed and time left, the Wi-Fi rule, sha256, resume after force-quit. The downloads card on Home. The online index set moves into the queue.

### Task 7: Packs in Files, import, Browse
- [ ] Packs visible in Files; Import a pack (Files picker, copied in); Your sources and Browse a pack's files; greyed pack tiles under Local only.

### Task 8: Onboarding's sets, Outfitting, Ready offline, feeds
- [ ] The first run's Pick a start with quick wins; Outfitting (your use and the nine domains, would-take sizes, kept-article updates); Ready offline on Home; feeds, Add feed, scopes and the Day page; book packs as collections (reading waits for I5); a broad topic's figures from packs.

### Task 9: Simulator check, parity rows, merge
- [ ] The UI smoke test grows: get a small pack (a subject add-on), pause and resume, open an article from it, find it in Search, see it in Files, Outfitting's sizes. Walkthrough with screenshots (Field, Night, largest Dynamic Type, landscape, VoiceOver tree). Tick the I4 rows, GrangerCore release, merge and push, write Plan I5.

## Order and size

I4a is mostly moving code behind interfaces that already exist (I1's `ZimArchive`, I3's store), so it's smaller than I3a. I4b's risk is CoreKiwix: Task 5 opens with a one-hour spike (open a pack, read an entry, search) before anything else is built on it, and the plan stops there to report if it fails.
