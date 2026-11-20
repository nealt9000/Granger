# Plan I1: The shared core — Kotlin Multiplatform in place, `GrangerCore` for Swift

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** The Kotlin the iPhone needs first builds for iOS from the real modules, not copies, and is handed to Swift as one framework, `GrangerCore`. Android builds and behaves exactly as before. At the end an empty iOS app links `GrangerCore` and opens an online Wikipedia article through it, and the dev-friend route (a prebuilt framework from a GitHub release) works from a clean clone.

**Spec:** `docs/superpowers/specs/2026-11-20-iphone-design.md` §3, §4, §6 (I1). **Results:** `docs/superpowers/spikes/2026-10-iphone-spike-results.md` §4 (the trial this plan makes real). **Roadmap:** the I1+ row.

**Architecture:**
- **Convert in place.** `:core:text`, `:core:research`, `:core:vectors` move from `kotlin.jvm` to `kotlin.multiplatform` with `jvm()`, `iosArm64()`, `iosSimulatorArm64()`. The I0 trial's platform code (`MappedFile`, `decompose`, `sentenceBreaks`, `sha1`, `TopK`, Ksoup) is reapplied to today's sources, which have moved on since 2026-10-28 (53 commits, ~2,500 lines).
- **Split, don't rewrite, the Android-library cores.** `:core:catalog` and `:core:zim` become KMP modules with an Android target (AGP 9's `com.android.kotlin.multiplatform.library`) and iOS targets. What is pure moves to `commonMain`; what needs Android or a JVM file API stays in `androidMain` under the same package and class names, so `:app` imports don't change.
- **A new `:core:online`** (KMP) takes the network and parsing half of the online layer out of `:app/online`: `WikimediaClient`, the `ZimArchive` over Wikipedia (`OnlineWikipedia`), `RemoteRecords`, and `TopicRecords` (from `:core:library`). Its HTTP is Ktor; on Android the engine is the app's own `OkHttpClient` (`OkHttp { preconfigured = … }`), so the `NetworkGate` interceptor and Local only keep working unchanged. The Compose screens, WorkManager jobs, `SharedPreferences` and Room parts of `:app/online` stay where they are.
- **`:shared`** depends on the iOS-ready modules and exports them as the `GrangerCore` XCFramework, with SKIE for Swift-friendly types.
- **The dev-friend route:** `tools/ios/release-core.sh` builds `GrangerCore.xcframework.zip`, uploads it to a GitHub release `ios-core-<date>`, and writes its URL and checksum into `ios/GrangerCore/Package.swift`, a Swift package with one binary target. A switch in that package points at a locally built framework for anyone changing Kotlin.

**Tech stack:** Kotlin 2.4.21 Multiplatform, AGP 9.4.1 (Android-KMP library plugin), Ksoup (jsoup's API for KMP), Ktor client (OkHttp + Darwin engines), kotlinx.serialization (in place of `org.json` in the moved code), kotlinx-io or `expect` file access where needed, SKIE, Xcode 27.1, Swift Package Manager.

## Decisions made with Neal (2026-11-20)

- SwiftUI over a shared Kotlin core; the core holds logic and pure screen state, SwiftUI draws (spec §3).
- The first iPhone build is online Granger plus Kiwix packs; dev friends build from the public repo with a **prebuilt `GrangerCore` from a GitHub release**; the Xcode project is committed (spec §4).
- Copyright holder AETHERBOUND LLC; bundle ID `app.granger` (spec §9). Neither touches I1's code.

## Builder's calls in this plan (all seven OK'd by Neal, 2026-11-20)

1. **Ksoup replaces jsoup on Android too**, in the converted modules, rather than keeping jsoup behind an `expect`. One parser for both phones is what makes chunk hashes match; Task 3 proves it on real `.gvec` builds before anything ships. If Task 3 finds a mismatch Ksoup can't be fixed for, jsoup stays on the JVM side behind an `expect` and the iPhone accepts rebuilt hashes for its own added books only.
2. **`:tools:chunker` (the Mac's vector builds) follows the cores onto Ksoup.** Published `.gvec` files are not rebuilt; Task 3 is the check that they needn't be.
3. **Sentence breaks:** one splitter in common Kotlin for `CitedQuote` (I0 §4's one difference), so both phones quote the same sentence. Ask is off, so this is cheap insurance, not a user-visible change.
4. **The catalog's signature check on iOS.** CryptoKit is Swift-only, so Kotlin/Native can't call it, and Apple's C crypto (CommonCrypto, Security) has no Ed25519. So either the iOS `actual` uses a maintained pure-Kotlin Ed25519 verifier, or the Swift app hands the core a CryptoKit verify function at start (an injected verifier, about ten lines of Swift). Task 5 picks: the injected verifier unless a pure-Kotlin library is clearly maintained.
5. **The `ZimArchive` interface loses its `java.io.File` members in common code.** `copyTo(path, File)` and `storedRange` (which returns a `File`) move to an Android-only sub-interface `FileZimArchive` that `KiwixZimArchive` implements; `:app` callers that need them ask for it. iOS gets its own file-backed archive in I4.
6. **No Room in I1.** `:core:library` stays Android until I3. `TopicRecords` leaves it now because it's pure and the online layer needs it.
7. **`GrangerCore` releases are made on the Mac by the script**, not CI (spec §4). Uploading a release is a push, so it needs Neal's OK each time.

## Global Constraints

- **Android must not change for the user.** Every JVM and Robolectric test passes before each commit that touches a converted module (`./gradlew test` at the end of each task, plus the module's own `iosSimulatorArm64Test`). The Android release bundle builds and R8 passes (Task 9).
- **The Mac is shared.** One Gradle or Kotlin/Native build at a time from this session; `-Pkotlin.compiler.execution.strategy=in-process`; never `./gradlew --stop` while another session's daemon may be in use (check `jps` first). No model training or generation.
- **The phones are shared.** Task 9's smoke test uses the Pixel 6 with `adb -s` its serial only (KB2-style sessions use emulators). Check `lastUpdateTime` and the installed DB version as their own step first; never install a build whose DB version is below the phone's (`LibraryDatabase` version 17 on main).
- **Worktree:** `.claude/worktrees/i0`, branch `i0-iphone-spike` (rebased on main e43258cd 2026-11-20). Before Gradle: copy `local.properties` in, `git submodule update --init --recursive`, `JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home`.
- **The I0 trial under `spikes/i0/` is the reference, not the destination.** Read its diffs; write the change into the real module. `spikes/i0/` is deleted in Task 10 once nothing points at it.
- **Pushing and GitHub releases need Neal's OK.**
- Commits end with the Co-Authored-By line from the session's attribution reminder.

---

### Task 1: Versions and the build skeleton

**Files:** `gradle/libs.versions.toml`, root `build.gradle.kts`, `gradle.properties`.

- [x] Read on the day and record in the plan: the newest **Ksoup**, **Ktor**, **SKIE** and **kotlinx-io** releases, and which Kotlin each supports. SKIE must support Kotlin 2.4.21; if it doesn't yet, `GrangerCore` ships on the plain Objective-C export (it worked in I0 §5) and SKIE comes in when it catches up.
- [x] Add the plugins to the catalog: `kotlin-multiplatform`, `android-kotlin-multiplatform-library`, `skie`; libraries `ksoup`, `ktor-client-core`, `ktor-client-okhttp`, `ktor-client-darwin`, `ktor-client-mock` (tests), and whatever Task 5 needs.
- [x] `gradle.properties`: `kotlin.native.ignoreDisabledTargets=true` so a Linux CI or a Mac without Xcode still builds Android; Kotlin/Native caches in the default place.
- [x] Prove the skeleton: an empty KMP module builds for all three targets and `:app:assembleDebug` is untouched. Delete the empty module. Commit.
- *Done 2026-11-20: Ksoup 0.2.6, Ktor 3.6.1, SKIE 0.10.16 (supports Kotlin 2.4.21, released 2026-11-10), kotlinx-io 0.9.3. AGP 9.4.1's `com.android.kotlin.multiplatform.library` with `jvm()` and iOS targets compiled in a probe module (deleted). Baseline before any change: 3,824 JVM tests, 1 failure (`PlayerEngineTest.audiobookPositionSavedOnPauseAndEveryFiveSeconds`, timing; passed on the next run).*

### Task 2: `:core:text`, `:core:research`, `:core:vectors` to KMP

**Files:** the three modules' `build.gradle.kts`; `src/main` → `src/commonMain` (+ `jvmMain`, `iosMain`); `src/test` → `src/jvmTest` (+ `commonTest` / `iosTest` for the ported tests).

- [x] Move sources with `git mv` so history follows. Apply I0's fixes to today's code: `MappedFile` (JVM `FileChannel.map`, iOS `mmap`), `writeFileAtomically`, `TopK`, `suspend top(…, dispatcher)` beside the JVM `ExecutorService` form, `decompose`, `sha1` + `hex()`, `bump()`, `Char.isHighSurrogate()`, `lowercase()` for `lowercase(Locale.ROOT)`. Check each file changed since 58cdc78 for new JVM-only calls (`git diff 58cdc78 main -- core/{text,research,vectors}/src/main`).
- [x] jsoup → Ksoup (builder's call 1). Sentence breaks → one common splitter (builder's call 3), with the existing `CitedQuoteTest` cases plus "Dr." and "U.S." cases.
- [x] `:core:research`'s callers in `:app` and `:core:library` compile unchanged (they consume the `jvm` variant). If an Android module can't resolve the `jvm` variant, add `android` targets via the Android-KMP plugin instead and note it.
- [x] Port I0's `ParityTest` into `commonTest` so the fingerprint runs on both targets on every build. Port `VectorFile` tests to `kotlin.test` in `commonTest`; the 70,000-row search test in `iosTest`.
- [x] `./gradlew :core:text:allTests :core:research:allTests :core:vectors:allTests test` passes. Record counts (JVM before/after must match: same tests, same results). Commit per module.
- *Done 2026-11-20: all three compile for jvm, iosArm64, iosSimulatorArm64. New since I0: `LanguageGuess` (ISO codes → `iso639Alpha2` expect), `WordQuestion` (NFC → `compose`), `BestMatches` (`TimeSource`, `kotlin.concurrent.Volatile`), `OwlLines` (`mod`), v2 IVF lists in `VectorFile`/`VectorSearch` (generic `TopK`). `MappedFile` reads ints/floats as members so the JVM keeps `ByteBuffer` speed. Android callers import the jvmMain `File` overloads and the blocking `ExecutorService` `top`. JVM: text 39, research 347, vectors 10 (baseline 32/345/10 plus 9 new common tests), all pass; Simulator: text 7, research 2 (parity fingerprint identical, same quote sentence), vectors 6. `./gradlew test` runs `jvmTest` in these modules (aliased). Whole suite 3,833 tests, 0 failures; `:app:assembleDebug` builds.*

### Task 3: Ksoup against the published vectors

**Files:** `tools/chunker` (builder's call 2); a check script under `tools/chunker/`; results appended to the plan.

- [ ] Move `:tools:chunker` onto the converted `:core:text` (it already depends on it; confirm it now runs on Ksoup).
- [ ] For three published `.gvec` files of different kinds (a Wikipedia pack, a Gutenberg pack, a Classics book set), re-chunk the same source with today's chunker and compare every chunk hash with the hashes stored in the `.gvec`. Pass = identical. Record counts and any mismatch with its text.
- [ ] Add a regression test with a handful of the trickiest real passages (tables, verse, footnotes, nested lists) whose expected hashes come from the published files.
- [ ] Stop and show Neal if anything differs. Commit.

### Task 4: `:core:zim` split

**Files:** `core/zim/build.gradle.kts` (Android-KMP + iOS targets); `ZimArchive.kt`, `ZimUrls.kt`, `ZimResolver.kt` → `commonMain`; `KiwixZimArchive.kt`, `KiwixZimOpener.kt`, `FileRangeInputStream`, `ZimRange` → `androidMain`.

- [x] Common `ZimArchive` without `java.io` (builder's call 5): `Closeable` → `AutoCloseable` (Kotlin common), `ByteArrayOutputStream` in `ZimUrls` → a `ByteArray` builder. Add `FileZimArchive : ZimArchive` in `androidMain` with `copyTo` and `storedRange`; `KiwixZimArchive` implements it.
- [x] Update the `:app` callers of `copyTo` / `storedRange` (find them with grep) to check for `FileZimArchive`. The online archive never offered either, so behaviour is unchanged.
- [x] `ZimResolver`'s tests move to `commonTest` where they don't need libkiwix. All tests pass. Commit.
- *Done 2026-11-20, as a split rather than an in-place conversion: a new KMP module `:core:zim-api` (jvm + iOS) holds `ZimArchive`, its data types and `ZimUrls` (made common); `:core:zim` stays an Android library and re-exports it with `api(...)`, so callers and the instrumented tests are untouched. `storedRange`/`copyTo` moved to an Android `FileZimArchive` plus `ZimArchive` extensions that fall back to `read()`. JVM: zim 21 + zim-api 10 (was 31).*

### Task 5: `:core:catalog` split

**Files:** `core/catalog/build.gradle.kts`; `Catalog.kt`, `CatalogParser.kt`, `Subjects.kt`, `Languages.kt` → `commonMain`; `CatalogSignature.kt` → `expect` in common with `actual`s; `CatalogRepository.kt` (OkHttp, files, `Dispatchers.IO`) → `androidMain`.

- [x] Replace `java.time` / `java.util` uses in the moved files with `kotlinx-datetime` or plain strings, whichever the code needs (look at each use; the catalog's dates are ISO strings).
- [x] Signature (builder's call 4): JVM/Android keeps BouncyCastle. For iOS, check the newest pure-Kotlin multiplatform Ed25519 libraries on the day (maintenance, audits, downloads); if none is clearly maintained, make `CatalogSignature` take an injected `verify(message, signature, publicKey): Boolean` on iOS, which the Swift app implements with CryptoKit `Curve25519.Signing.PublicKey.isValidSignature` in I2. Either way a common test verifies the real `catalog/catalog.json` against `catalog.json.sig` with the published key (JVM now; iOS in Task 8's link test when injected).
- [x] `PublishedCatalogTest` and every catalog test pass on the JVM; the parser's tests also run on `iosSimulatorArm64Test` over the real `catalog.json` (copied in as a test resource). Commit.
- *Done 2026-11-20, the same way: new KMP `:core:catalog-api` (model, parser, subjects, languages, signature); `:core:catalog` keeps `CatalogRepository` and re-exports it. Signature: `CatalogSignature.verifier` (BouncyCastle on the JVM; on iOS it fails closed until the app sets a CryptoKit one). `Instant` → `kotlin.time.Instant`; languages through `iso639Alpha2` (cached on the JVM). JVM: catalog-api 73 + catalog 13 (was 86); iOS Simulator: the published catalog parses, and fails closed without a verifier. Whole suite 3,833, 0 failures; debug APK builds.*

### Task 6: `:core:online`

**Files:** create `core/online/` (KMP: Android-KMP + iOS). Move from `:app/online`: `WikimediaClient.kt`, `OnlineWikipedia.kt`, `RemoteRecords.kt`, `Availability.kt`, `ReadyOffline.kt` (if pure); from `:core:library`: `TopicRecords.kt`. Their tests move with them.

- [ ] Before moving, list every symbol each file uses from `:app` and `:core:library` (`Library`, `QidIndexes`, `CorruptBlock`, `RecordsDirectory`, the R strings). Anything Android-bound is passed in as an interface or a value, not imported. Write the list into this task before coding.
- [ ] `WikimediaClient`: OkHttp → Ktor. Android builds the Ktor client from the app's existing `OkHttpClient` (`preconfigured`), so the `NetworkGate` interceptor, the cache and the user agent are the same objects. iOS uses Darwin with the same user agent string and the same per-host limit (`PER_HOST = 4`, a `Semaphore` per host from `kotlinx.coroutines.sync`). `org.json` → kotlinx.serialization. `URLEncoder` / `URLDecoder` → Ktor's `encodeURLPathPart` / `decodeURLPart`, with a test of the titles that broke before (slashes, `?`, `&`, accents, parentheses).
- [ ] `OnlineWikipedia`: jsoup → Ksoup, `android.util.Log` → a small common logger. It implements the common `ZimArchive` (Task 4). `remote = true` and the no-fetch `entry()` rule stay.
- [ ] `TopicRecords`: `ByteBuffer` → little-endian reads (as `VectorFile`), `CRC32` and `Inflater` → `expect` (JVM `java.util.zip`; iOS `platform.zlib`, which Kotlin/Native ships). `org.json` → kotlinx.serialization. `RemoteRecords`: OkHttp → Ktor range requests, `MessageDigest` → the common `sha256` (add beside `sha1`), `java.io.File` cache → a small `expect` file store or kotlinx-io.
- [ ] Tests with Ktor's `MockEngine`: summary, mobile HTML, search, morelike, a records range read with a CRC failure. The existing `:app` tests of these classes move and keep passing.
- [ ] `:app` now depends on `:core:online`; its imports change package only where a file moved. `:core:library` loses `TopicRecords`; `TopicSource` imports it from `:core:online`. All tests pass. Commit.

### Task 7: `:shared` and `GrangerCore`

**Files:** create `shared/` (KMP, iOS targets only); `settings.gradle.kts`.

- [ ] `:shared` exports `:core:text`, `:core:research`, `:core:vectors`, `:core:catalog`, `:core:zim`, `:core:online` as the `GrangerCore` XCFramework (`iosArm64` + `iosSimulatorArm64`), static, bundle ID `app.granger.core`, minimum iOS 26.0 (spec §8.3).
- [ ] SKIE on if Task 1 found it compatible. A small `GrangerCoreEntry` object gives Swift one place to start: build the Wikimedia client with a user agent, open the online Wikipedia archive for a language, parse and verify a catalog.
- [ ] `./gradlew :shared:assembleGrangerCoreReleaseXCFramework` builds. Record the zipped size and the link time. Commit.

### Task 8: The link test and the dev-friend route

**Files:** `ios/GrangerCore/Package.swift`; `ios/CoreSmoke/` (a minimal SwiftUI app, kept for I2 to replace or absorb); `tools/ios/release-core.sh`; `ios/Local.xcconfig.example`; `.gitignore`.

- [ ] `ios/GrangerCore/Package.swift`: one `binaryTarget` with `url` + `checksum`; with the environment variable `GRANGER_CORE_LOCAL=1` it points at `../../shared/build/XCFrameworks/release/GrangerCore.xcframework` instead.
- [ ] `ios/CoreSmoke/`: one screen that opens "Moon" in English through `GrangerCoreEntry`, shows its title, its summary and the first section's text, and verifies the bundled catalog's signature (with the CryptoKit verifier if Task 5 injected one). Run on the iOS 27.1 Simulator. Signing from `ios/Local.xcconfig` (git-ignored; `DEVELOPMENT_TEAM`, bundle-ID prefix), the example file committed.
- [ ] `tools/ios/release-core.sh`: build, zip, `swift package compute-checksum`, `gh release create ios-core-<date>` with the zip, rewrite `Package.swift`'s URL and checksum, print the commit to make. **Dry-run only** until Neal OKs a release.
- [ ] **With Neal's OK:** cut the first `ios-core-<date>` release; then from a fresh clone in the scratchpad, with no JDK on `PATH`, open `ios/CoreSmoke` in Xcode and run it. That is the dev-friend route proven. Commit.

### Task 9: Android unchanged

- [ ] `./gradlew test` (whole JVM + Robolectric suite): the count matches main's plus the moved and new tests, with none removed. Record both counts.
- [ ] `./gradlew :app:bundleRelease` builds; R8 keeps what Ktor, Ksoup and kotlinx.serialization need (`app/proguard-rules.pro`); install the release-signed universal APK from the bundle on the Pixel 6 (following the shared-phone rules) and smoke it: open an online Wikipedia article with pictures, its topic page and Related, Search online, a kept article in Local only, a Kiwix pack article and pack search, the catalog loads (Get more lists packs), a Classics book opens. Then restore the debug build the phone had.
- [ ] Note anything that changed. Fix before merge.

### Task 10: Close

- [ ] Delete `spikes/i0/` (its findings live in the results doc; the git history keeps the code).
- [ ] Update the roadmap I1+ row and the spec if anything moved. Write `ios/README.md`'s first section: what's there today (CoreSmoke) and the GrangerCore switch.
- [ ] Merge `i0-iphone-spike` into main (fast-forward after a rebase), with Neal's OK; push with Neal's OK.

## Order and size

Tasks 1–2 are the I0 trial redone on today's code: about a day. Task 3 is a few hours of Mac time (chunking three packs). Tasks 4–6 are the new work, about two to three days, Task 6 the largest. Tasks 7–8 take a day, gated on Neal's OK for the first release. Task 9 needs the Pixel 6 for under an hour. **About a week of work**; I2 (the iPhone app proper) can start once Task 7 builds.
