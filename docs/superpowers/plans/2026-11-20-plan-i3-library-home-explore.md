# Plan I3: The library store, keeping, Home and Explore — on both phones

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** The iPhone gets Android's library store and everything that stands on it: keeping articles and Saved for now with their marks; Local only greying; the online indexes; the topic page's records, broad topics, quilt and filtered Related; Home's feeds, Wander, the timeline and Day page, the games and Arrange Home; and Explore whole. Afterwards the parity rows for Wikipedia online and keeping, Home, Explore and the Home feeds are ticked (`docs/superpowers/ios-parity.md`).

**Spec:** `docs/superpowers/specs/2026-11-20-iphone-design.md` §3, §6 (I3). **Builds on:** I1 (shared core), I2 (`ios/Granger`). **Parity:** "Wikipedia online and keeping", "Home", "Explore", and the I3 parts of "Shell and look" and "First run, onboarding and settings".

**Architecture:**
- **Two halves, two merges.** **I3a** is shared Kotlin, and Android must not change for the user. **I3b** is the iPhone screens on top of it. I3a merges when its tests and a Pixel walkthrough pass, and I3b when its Simulator check passes.
- **Room on both phones (Room KMP).** `:core:library` becomes a multiplatform library (Android and iOS targets) with one schema and one migration chain. 185 of its 186 DAO functions are already `suspend` or `Flow`, which is what Room needs in shared code; the one blocking call becomes `suspend`. Android keeps the framework's SQLite (`AndroidSQLiteDriver`), so nothing changes on the phone; iOS uses the bundled SQLite (`BundledSQLiteDriver`). The 15 migrations move to Room's connection API.
- **The sidecar readers on the same API.** `QidIndex`, `TimeIndex`, `MentionIndex`, `BookLinks`, `ManifestBooks` and `NautilusDatabase` open `.gqid`/`.gtime`/link and manifest files with `android.database.sqlite` today. They move to `androidx.sqlite`'s driver API, which both phones run. `PdfCovers`/`PdfColour` (`android.graphics.pdf`) stay Android-only.
- **The screen models in shared Kotlin.** The models behind Home, Explore and the topic page are already Compose-free: `ExploreModel`, `TopicSource`, `BroadTopic`, `Quilt`, `HomeViewModel`'s logic, `FeedDrawer`, `WanderModel`, `TimelineModel`, `GameModel`, `FeedScope`, `ReadinessModel`/Outfitting, and the keeping rules. They move into a new multiplatform module **`:core:screens`**, keeping `androidx.lifecycle.ViewModel`, which runs on both. Android's Compose screens use them from there; the iPhone observes the same `StateFlow`s through SKIE (Swift's `AsyncSequence`). The iPhone can't drift from Android's behaviour because it runs the same code.
- **Topic records** (`TopicRecords`, `RemoteRecords`) move to `:core:online`, as I1 planned. `HttpFetch` gains request headers, HEAD and a no-redirect mode for the Range reads.
- **Saved for now** (`SavedStore`, a small SQLite table plus files) moves to the core on `androidx.sqlite`; both phones keep its cap and Clear.

**Tech stack:** Room 2.8.5 KMP + KSP for `iosArm64`/`iosSimulatorArm64`, `androidx.sqlite` (framework driver on Android, bundled on iOS), `androidx.lifecycle` 2.11 multiplatform ViewModel, AGP 9's `com.android.kotlin.multiplatform.library`, SKIE (Flow → AsyncSequence), SwiftUI, URLSession for the online indexes.

## Decisions made with Neal (2026-11-20)

- Build straight through to 1:1 with Android; Simulator checks per plan; Neal tests at the I7 hand-off; friends after (I8).
- Standing OK: merge and push each green plan (noreply check first), and publish a GrangerCore release whenever the core changes.
- I2's interim choices end here: Wander replaces the featured-only Home, and Related appears, filtered by the index's adult flags (I2 builder's calls 2 and 3).

## Builder's calls in this plan (all eight OK'd by Neal, 2026-11-20)

1. **Room KMP, not a second store on the iPhone.** One schema, one migration chain, and the DAOs' tests run once. The alternative was GRDB or SwiftData on the iPhone with its own schema, which means two stores to keep in step for every Android change.
2. **Android keeps its own SQLite through Room's framework driver.** The phone's database file, its SQLite version and its FTS4 behaviour stay exactly as today. Only the iPhone bundles SQLite.
3. **A new `:core:screens` module for the shared screen models,** with `ViewModel` kept as is. The Android screens change only their imports. This is the I1 spec's sharing rule ("pure state and rules in Kotlin, SwiftUI draws"), applied to the models that are already pure.
4. **Two merges (I3a, I3b).** I3a is a large Kotlin refactor under Android's feet. Merging it on its own, after a Pixel walkthrough of Home, Explore, the topic page and keeping, keeps any regression small and findable.
5. **The online indexes on the iPhone come by a plain URLSession download** at first run (Top `.gqid` + `.gtime`, about 20 MB, sha256-checked against the signed catalog), shown on Home like Android's index card. The resumable background downloader for packs is I4's.
6. **Games drawn natively.** The six games' rules and runs (`GameModel`) are shared; each board is drawn in SwiftUI.
7. **Arrange Home with SwiftUI's list editing** (drag handles, hide/show), storing the same order and hidden set as Android's.
8. **Reading place across launches comes with the store.** The iPhone keeps an article's scroll place and the open-items list in the same tables Android uses (rail pins and reading sessions), shown as **Recently open** in each tab's toolbar (parity row "Open items on the rail").

## Global Constraints

- **Android must not change for the user (I3a).** The whole JVM suite and the Room migration tests pass; `:app:bundleRelease` builds with R8; a Pixel 6 walkthrough of Home, Explore (landing, a room, a subject, the quilt, the timeline), an online and a pack topic page, Keep/Saved for now and Local only passes on a debug build installed **over** the phone's existing data (DB 17 must open unchanged; check `lastUpdateTime` and the DB version first, `adb -s` the Pixel's serial, start `app.granger/.MainActivity` explicitly).
- **No migration is added.** The schema stays at version 17, so I3a must not change the database. If BK lands migration 18 first, I3a rebases onto it and ports it like the others.
- **The repo is on the X10** and must be mounted. The Mac is shared: one Gradle or Kotlin/Native build at a time.
- **Pushing and releases:** standing OK; noreply author check before every push.
- Worktree `.claude/worktrees/i0`, branch `i3-library-home` off main da286c4c.

---

## I3a — the shared store and models

### Task 1: Room KMP for `:core:library`

**Files:** `core/library/build.gradle.kts` (Android-KMP plugin, iOS targets, KSP per target), `src/main` → `commonMain`/`androidMain`, `LibraryDatabase.kt` (`@ConstructedBy`, driver per platform), the migrations, `Daos.kt` (the one blocking DAO), tests → `androidHostTest`.

- [x] Read Room 2.8.5's KMP setup on the day and note anything that differs from the plan.
- [x] Convert the module: entities, DAOs and the database class in `commonMain`; `RoomLibrary` and anything `Context`-bound in `androidMain`; a `LibraryDatabase.build(path)` per platform (Android: framework driver and the existing file; iOS: bundled driver).
- [x] Migrations 1→17 rewritten from `SupportSQLiteDatabase` to `SQLiteConnection` (same SQL). The schema JSON files stay the source of truth. Room's migration tests pass on Android; an iOS test opens a version-1 database built from `schemas/` and migrates it to 17.
- [x] `BookIndex`'s raw FTS4 queries move to the connection API.
- [x] The whole JVM/Robolectric suite passes unchanged in count. Commit.
- *Done 2026-11-20, as a split like I1's (not an in-place conversion): a new KMP module **`:core:store`** (Android-KMP + iOS, KSP per target) holds `LibraryDatabase` (`@ConstructedBy`), the entities, the DAOs, `BookIds`, `BookPacks`, `GutenbergNames`, `FilingKeys` and `schemas/`; `:core:library` stays an Android library re-exporting it, so its 538 Robolectric tests and the migration tests run as before. Migrations and the seed callback are written once against `SQLiteConnection`; on Android, Room's framework-SQLite mode hands them a wrapped connection, and **all 538 library tests including MigrationTest pass**. `PackDao.get` is now `suspend`; Android's blocking callers use `getBlocking`. `inTransaction` (Android: `withTransaction`; iOS: `useWriterConnection { immediateTransaction }`) for shared code. `@RawQuery` takes `RoomRawQuery`. `Pack.from`/`unreadable` take a path and size (File overloads in `:core:library`). iOS tests (bundled SQLite): a version-1 database built from `schemas/1.json` migrates to 17 through Room; a fresh one is seeded with the five media types and `book_text`. Whole suite 3,846 tests; the only failures were two timing-sensitive `PlayerEngineTest` cases that pass on rerun (one was flaky in the I1 baseline).*

### Task 2: The sidecar readers on `androidx.sqlite`

**Files:** `QidIndex.kt`, `TimeIndex.kt`, `MentionIndex.kt`, `BookLinks.kt`, `ManifestBooks.kt`, `NautilusDatabase.kt`.

- [x] Each opens its file through `androidx.sqlite` (read-only), with the same queries. Their tests pass on the JVM and run on the iOS Simulator over the same fixture files.
- [x] A real Top `.gqid` and `.gtime` from the release are opened on the iOS Simulator: a subject's rooms, a person's years. Commit.
- *Done 2026-11-20 for what I3 needs: `QidIndex` and `TimeIndex` (with `ContentFlags`, `SidecarMeta`, `SubjectSet`) moved into `:core:store` over a new `SqlFile`/`Rows` (Android `Cursor` calls on `androidx.sqlite`; a pool of 3 connections per file, each behind its own lock, since Explore reads in parallel; Android opens through the framework driver with `query_only`, as read-only as before; iOS through the bundled driver read-only). `org.json` → kotlinx.serialization; `ConcurrentHashMap` → `ConcurrentCache`; `BitSet` → `Bits`; `File` overloads of `open` and `TimeIndexDirectory` stay in `:core:library` (`SidecarFiles.kt`). `MentionIndex`, `BookLinks`, `ManifestBooks` and `NautilusDatabase` move with books (I5) and packs (I4). JVM suite 3,846, only the known timing-flaky player test failed (6,990 ms vs 7,000). iOS: the release Top `.gqid` (v6) finds Moon = Q405 with its subjects and a random path; the Top `.gtime` dates the Battle of Hastings 1066 and gives a decade's top ten (`-PgqidTest`/`-PgtimeTest`, skipped without them).*

### Task 3: Topic records and Saved for now in the core

**Files:** `core/online` (`TopicRecords`, `RemoteRecords`, `HttpFetch` headers/HEAD/no-redirect, `SavedStore`), `core/library` (`TopicParts`, `ContentFlags` stay; records import them).

- [x] `HttpFetch.get(url, noCache, headers, followRedirects)` plus `head`; `okHttpFetch` and `UrlSessionFetch` implement them (a no-redirect `OkHttpClient` derived once; a URLSession delegate that refuses redirects).
- [x] `TopicRecords` (zlib and CRC32 through `expect`: `java.util.zip` / `platform.zlib`) and `RemoteRecords` (the release-URL resolution and Range reads, blocks into Saved for now) move; the app's `RecordsSource` wiring is unchanged.
- [x] `SavedStore` on `androidx.sqlite` and the core's file helpers; Android's `OfflineStores` keeps working. Tests move with the classes. Commit.
- *Done 2026-11-20, with two changes. (1) **A new `:core:records` module (JVM + iOS)** holds `TopicParts`, `ContentFlags` and the records format (`TopicRecords`, `RecordsDirectory`, `TopicRecord`), because `:core:online` (JVM) can't depend on `:core:store` (Android target); both depend on `:core:records`. zlib and CRC32 are `expect` (`java.util.zip` / iOS `platform.zlib`), `org.json` → kotlinx.serialization. `RemoteRecords` is in `:core:online` (`HttpFetch.send(HttpRequest)` with headers, HEAD, no-redirect: OkHttp's derived client on Android, a second `NSURLSession` refusing redirects on iOS; files through kotlinx-io; blocks through a `SavedBlocks` interface); the app keeps a factory with the old signature, so `GrangerApp` and `RemoteRecordsTest` are unchanged. (2) **Android's `SavedStore` doesn't move yet:** BK (another session) is editing it. The shared `SavedForNow` in `:core:store` has the identical table and file layout, used by the iPhone now; Android switches once BK lands (carried forward). Tests: records 7 JVM (moved off Robolectric) + 1 iOS over the same fixture (inflate, CRC, a corrupt block caught); `SavedForNow` on iOS (put/get/trim oldest-opened/reopen/clear); app `RemoteRecordsTest` and topic tests 36/36. Whole suite 3,846, 0 failures.*

### Task 4: `:core:screens`

**Files:** create `core/screens/` (KMP, Android + iOS); move `ExploreModel`, `TopicSource`, `BroadTopic`, `Quilt`, `QuoteSentence`, `FeedDrawer`, `FeedScope`, `WanderModel`, `TimelineModel`, `GameModel`, `ReadinessModel`, `ReadyOffline`, the keeping rules (`Keeper`, `KeptStore`'s logic, `ArticleCopies`), the Explore data (`rooms.json` as a resource) and the plate rule (v4) from `ArticleThumbnails`; `HomeViewModel`'s logic extracted from its Android parts.

- [ ] Each move lists what the class uses from `:app` first. Anything Android-bound is passed in as an interface: string lookups (`UiText` keys rather than `R` ids), the downloads card (I4 fills it on the iPhone), bitmaps (bytes in, platform decodes).
- [ ] Android's screens import from `:core:screens`; their tests move or stay and pass. Commit per group (Explore, topic, Home, readiness).
- *Done 2026-11-20, with what's in it and what waits named here. **Moved (package names kept, so Android's screens and tests import nothing new):** Explore (`ExploreModel` and view models, `Room`, rooms' descriptions read through a lambda, the map types, `Seen`/`SeenStore`), the topic page (`TopicSource`, `BroadTopic`, `Quilt`, `QuoteSentence`, `TopicArticle`, `TopicTitles`, `TopicTime`/`Related`, `BookMention`, the footer types), Home's `WanderModel` + `LeadLinks`, `TimeScale`/`TimelineModel`, `GameModel`, the keeping (`KeptStore` on kotlinx-io, `Keeper`, `ArticleCopies`), the picture rule (`PlateRule`, out of `ArticleThumbnails`; the app keeps the bitmap work), `Perf`/`side`/`Briefly`, and **`UiText` itself**. Shared models name strings by key (`UiText.Res("home_feed_day", …)`, the Android resource name and the iPhone's String Catalog key); Android code still writes `UiText.Res(R.string.x, …)` through a companion `invoke`, and resolves keys through `ScreenTextIds` (written by `tools/ios/make_text_ids.py`, explicit so resource shrinking keeps the strings; `ScreenTextIdsTest` fails on a missing key). Enum labels (`Subject`, `Era`) carry `labelKey`; Android maps them in `ExploreLabels.kt`. Platform pieces are `expect` in `app.granger.screens` (today, year, language, title collation, image size, IO dispatcher). `ZimPaths` (in `:core:text`) no longer uses `java.net.URI`: a JVM test holds it equal to `URI` on 500 ref × base pairs. `PlateRule` on Ksoup picked the same picture as the Jsoup rule on 3,000 random `wikipedia_en_all_maxi` pages (2,206 with a picture; `head()` too). iOS tests run the platform pieces, the look-behind regexes and Spanish collation on the Simulator. **Waiting, with the code they need:** `ReadinessModel` and `ReadyOffline` go with I4 (they read `KiwixList`, `CatalogScope`, `PackOffer`, byte and date formats: the Get more code I4 moves); `FeedScope`, `FeedDrawer` and `HomeViewModel` go with I4/I5 (their scopes are packs, book collections and media). The iPhone's Home in Task 8 builds from Wander, the timeline, the games and Continue, which are shared now. Whole suite 3,851, 0 failures.*

### Task 5: I3a check and merge

- [ ] Whole JVM suite; `:app:bundleRelease` with R8 (keep rules for Room's generated code if needed); the Pixel 6 walkthrough in the Global Constraints, on the phone's own data.
- [ ] Cut a GrangerCore release with `:core:library` and `:core:screens` exported; pin it. Merge and push (standing OK).

## I3b — the iPhone screens

### Task 6: The store and keeping on the iPhone

- [ ] The library database in Application Support (`LibraryDatabase.build`); the online indexes fetched at first run (builder's call 5) with Home's index card; `OnlineStores` over the shared `SavedStore` and kept articles.
- [ ] Keep (with or without pictures) from the topic page and the reader, Saved for now as you read, the marks (dot = on this phone, ring = saved for now) on search results, Continue and plates; kept-article updates; Settings → Offline pictures, Saved for now cap and Clear, content (adult flags).
- [ ] Local only: things that need the internet are greyed, with Android's hints.

### Task 7: The topic page whole

- [ ] Records: parts, rooms ("More in …"), "leads here", their time and the same time; broad topics with the quilt; Related (morelike, filtered by the records' and index's adult flags); Explore connections; Wander from here.

### Task 8: Home whole

- [ ] Android's feeds through the shared `HomeViewModel` logic: Continue, Wander (from the index, adult-filtered), the timeline card and Day page, games (the six, random on Home, the Games hub with Continue and runs of 10; builder's call 6), Ready offline, Add feed and scopes, Arrange Home (builder's call 7). The featured-article plate goes, as on Android.

### Task 9: Explore whole

- [ ] The landing (editorial, doors), rooms and the room screen, subject spreads, broad topic spreads, plates (rule v4, emblems whole), A–Z, the quilt map, Through time, Your sources, seen rooms. Explore becomes the third tab.
- [ ] Recently open (builder's call 8) in each tab's toolbar, and the reader's find in page and footer (In your books waits for books in I5).

### Task 10: Simulator check, parity rows, merge

- [ ] The UI smoke test grows: Keep an article, Local only shows it kept, Home's Wander and timeline, Explore → room → subject → topic, a game round, Arrange Home.
- [ ] Walkthrough with screenshots (Field, Night, Spanish, largest Dynamic Type, landscape, VoiceOver tree), side by side with the Android screens.
- [ ] Tick the I3 rows in `ios-parity.md`; carry anything left into the roadmap. GrangerCore release if the core changed; merge and push (standing OK); write Plan I4.

## Order and size

I3a: Task 1 is the largest (two to three days: Room KMP plus 15 migrations); Tasks 2–3 two days; Task 4 two to three days; Task 5 a day. I3b: Tasks 6–9 four to five days, mostly SwiftUI over the shared models; Task 10 a day. **About two and a half weeks**, the longest plan of the port, because it carries most of the app's model layer.
