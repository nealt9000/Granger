# Plan F1 part 2: the friend build — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task by task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** what the build friends get on Play internal testing needs beyond the pipeline (F1 part 1, merged 2026-10-31):
- Ask, the librarian, on in release builds as a Preview, on ASK-tier phones, with its models offered.
- A local crash log, kept on the phone.
- Settings → Send feedback: an email with the device, the build and the log, which the user sees and sends. No telemetry.
- A tester note: what to try, known issues, sizes before mobile-data downloads.
- The Play policy check, and the Console forms it feeds (data safety, content rating, target audience, app access).
- A fresh-install walkthrough of the release build from Play on a second phone.

Neal's decisions, 2026-10-31:
- Feedback goes to his Gmail (the account's address), in a `BuildConfig` field.
- Models: mark Gemma 4 E2B and EmbeddingGemma `evalPassed: true` in the catalog and re-sign it. R5 Task 7's grounded run (91/126) is the bar for a Preview. E4B stays `false`, so it drops out of release builds too. The app keeps honoring `evalPassed` (`includeUnevaluatedModels = BuildConfig.DEBUG` stays).
- The second phone is his wife's Pixel 7 (`panther`, 8 GB, Android 16), already on the internal track.

**Out of this plan:** friend round 1 itself (F2); telemetry (Plan 10's opt-in); the iPhone; Spanish (L1–L5).

**Tech stack:** unchanged (Kotlin 2.4.21, Compose Material 3, Room, Robolectric 4.17, JUnit 4 + Truth). No Room migration.
- **Build env:** `JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home`, `ANDROID_HOME=/opt/homebrew/share/android-commandlinetools`. A new worktree needs `local.properties` and `git submodule update --init --recursive`.
- **Release:** `./gradlew :app:bundleRelease` signs with the upload key (`~/.config/granger/upload.properties`). Upload the bundle and `app/build/outputs/mapping/release/mapping.txt` together. Version code = commit count; 746 and 747 are used.
- **Phones:** the Pixel 6 is shared (debug `app.granger`; see the shared-phone rule: check `lastUpdateTime` first). The Pixel 7 is paired for wireless debugging as `adb-27221FDH2005FL-u5dudA._adb-tls-connect._tcp`. It has a work profile: use `--user 0` with `pm`, and its policy locks the screen after 5 minutes at most. Granger there came from Play, signed by Play's key: a local release build can't install over it without uninstalling, so test release builds there through Play, or uninstall first with Neal's OK.
- **Strings:** main still writes UI text as Kotlin literals; L1 (`l1-strings`) moves them to resources. New text follows main's idiom and is listed in this plan's last task so L1 Task 8 picks it up.

**Roadmap:** the F1 row in `docs/superpowers/plans/2026-10-15-granger-v1-roadmap.md`.

## What the code looks like now (surveyed 2026-10-31, main after the F1 part 1 merge)

- **Ask's gate:** `Features.RESEARCH = BuildConfig.DEBUG` (`app/.../Features.kt`). Read by the rail (`Rail.kt:74`, `RESEARCH("Ask", Features.RESEARCH)`) and the readers' selection menu (`SelectionMenu.offersResearch`). The tier gate is separate: `GrangerNavHost.kt:711` `showResearch = container.deviceTier >= DeviceTier.ASK`; `ItemStatus.kt:68` offers SMALL and EMBEDDER models only at ASK or better.
- **Preview already there:** `AskTitle()` (`ResearchScreen.kt:633`) shows "Ask" with a Preview tag, and `INTRO` says "It's a preview: it can be wrong, so check the sources." (Plan U3a Decisions 7–8.)
- **Models in release:** `CatalogRepository(includeUnevaluatedModels = BuildConfig.DEBUG)` (`GrangerApp.kt:543`) → `Catalog.forBuild()` keeps only `evalPassed` models. In `tools/catalog-gen/curated.json` all three models are `"evalPassed": false` (E2B, E4B, EmbeddingGemma), so a release build offers none.
- **Catalog tool:** `tools/catalog-gen/catalog_gen.py build --curated … --out …`, then `check-links`, then `sign` (key `~/.config/granger/catalog-signing.key`), committed on main where the app fetches it.
- **Crashes:** nothing catches them. `GrangerApp.onCreate()` starts PdfBox, media, vectors, Gutenberg sync and downloads. Native aborts (the R8 sherpa crash of 2026-10-30 was a SIGABRT) never reach a Java handler.
- **Feedback:** nothing yet. Settings' About (`SettingsScreen.kt` `About()`) shows the lockup, the tagline, "Version ${BuildConfig.VERSION_NAME}" and "GPLv3 · source on GitHub". There is no FileProvider in the manifest.

## Decisions

1. **The crash log is a folder of small text files**, `filesDir/crashes/`, newest five kept. Each holds the time, build (version name and code), device, Android version, thread and stack trace.
   - Java crashes: a `Thread.setDefaultUncaughtExceptionHandler` installed first in `onCreate`, which writes the file and then calls the previous handler, so Android still shows its dialog and kills the process.
   - Native crashes and ANRs: on the next start, `ActivityManager.getHistoricalProcessExitReasons` (API 30+; minSdk is 29, so skipped on Android 10) for `REASON_CRASH_NATIVE` and `REASON_ANR` exits newer than the last one recorded, with `traceInputStream` when there is one (the tombstone or the ANR trace), capped at 64 KB each.
   - Nothing leaves the phone except through Send feedback.
2. **Send feedback is an email the user writes and sends from their own mail app.** `ACTION_SEND` with `EXTRA_EMAIL` (`BuildConfig.FEEDBACK_EMAIL`), a subject "Granger feedback · 0.1.N (code)", a body that starts empty for their words and then a short device block. Crash files go as one `granger-report.txt` attachment through a FileProvider (`${applicationId}.reports`, `cache/reports/` only).
   - Before the mail app opens, a sheet shows exactly what's included: device model, Android version, build, tier, free space, the count of packs and books (no titles), and the crash log (with a View button). A switch leaves the crash log out.
   - No search history, no titles, no chats, no location.
3. **Where:** Settings → About, a "Send feedback" row, plus "Crash reports" when there are any (View, Clear). After a crash, on the next start, Home shows one quiet line, "Granger closed unexpectedly. Send a report?", which opens the same sheet, and goes away when sent or dismissed.
4. **Ask's gate** becomes `Features.RESEARCH = true`. The tier gate keeps it to ASK phones, so a phone under 6 GB still never sees Ask.
5. **The tester note** lives in `docs/friend-testing/round-1-tester-note.md`, and is published as a private page Neal can share. A ≤500-character version goes in the Play release's "What's new". It covers what to try, known issues, what to expect from Ask, sizes (the app is ~51 MB; Efficient start 18 GB; turn on "Download on Wi-Fi only" before big picks; it's on by default), and how to send feedback.
6. **The policy check is a written note,** `docs/friend-testing/play-policy-check.md`, with the answers for each Console form and the risks it found, for Neal to enter. Areas: user data and the data safety form (no collection; the feedback email is user-initiated in another app), content rating (reference content, a Bible and Koran, medical encyclopedias, maps; user-chosen archive.org items behind the waiver), target audience (adults, 18+, so no Families policy), app access (no login), the archive.org "show everything" waiver and copyright, non-commercial packs (CC BY-NC sources: Granger is free, no ads, no purchases, so NC holds for now; Plan 10's Supporter tier must revisit), models (Gemma 4 is Apache-2.0; on-device generative AI and Play's AI-generated content policy: a way to report offensive output, which Send feedback covers), downloading executable code (models and packs are data, not code), and the foreground service types declared.

## Tasks

### Task 1: models in release builds
- [ ] In `tools/catalog-gen/curated.json`, set `"evalPassed": true` for `gemma-4-e2b-gpu` and `embeddinggemma-2-text-270m`; leave `gemma-4-e4b-gpu` false.
- [ ] Rebuild, check links, sign; `verify` against `catalog/public-key.txt`. Run `pytest tools/catalog-gen`.
- [ ] `CatalogTest`: a release-shaped `forBuild(false)` over the committed catalog keeps exactly E2B and EmbeddingGemma.
- [ ] Commit. (The live catalog changes when main is pushed; debug builds already show all models, so nothing changes for them.)

### Task 2: Ask on in release
- [ ] `Features.RESEARCH = true`, with its doc comment updated (the tier gate keeps it to ASK phones).
- [ ] `FeaturesTest`: Research is on; a below-ASK tier still hides the rail tool and the selection menu's Ask (existing tier tests cover it; add one if not).
- [ ] Run the unit tests (`./gradlew testDebugUnitTest`).

### Task 3: the crash log
- [ ] `app/.../feedback/CrashLog.kt`: `record(thread, throwable)`, `recordExits(exits)` (pure over a small `ExitRecord` list), `list()`, `clear()`, newest five kept, 64 KB cap per file, last-seen exit time in a small prefs file.
- [ ] `GrangerApp.onCreate()`: install the handler first, chaining to the previous one; after start, read past exits on API 30+ off the main thread.
- [ ] Tests (Robolectric): a recorded throwable writes one file with the build and trace; six records keep five; exits newer than last-seen are recorded once; older ones are skipped; the chained handler is called.

### Task 4: Send feedback
- [ ] `BuildConfig.FEEDBACK_EMAIL` in `app/build.gradle.kts`.
- [ ] `FeedbackReport` (pure): builds the subject, the device block and the attachment text from a `FeedbackFacts` (model, Android, build, tier, free bytes, pack and book counts, crash files, include-crashes flag). Tests pin the format and that no titles appear.
- [ ] FileProvider `${applicationId}.reports` with `res/xml/report_paths.xml` (`cache-path reports/`); the attachment is written fresh each send.
- [ ] The sheet (Decision 2) and the Settings → About rows (Decision 3); Home's after-a-crash line.
- [ ] Compose tests: the sheet lists what's included; turning off the crash log drops the attachment; the intent carries the address, subject and stream with read permission.

### Task 5: release check on the Pixel 7
- [ ] `bundleRelease`; check the R8 seeds for LiteRT-LM (kept whole) and the new classes.
- [ ] Neal uploads to internal testing (bundle + mapping). On the Pixel 7, after Play updates it: Ask appears with Preview; Settings → Models offers E2B and EmbeddingGemma and not E4B; get both; ask a question over Knots and one over WikiMed if present; open a citation; Send feedback opens Gmail with the attachment; `adb shell am crash app.granger` once, then on the next start the after-a-crash line appears and the crash is under Crash reports. No crash path is added to the app.
- [ ] Fix anything R8 broke (keep rules with a line saying why), commit, upload again.

### Task 6: the policy check
- [ ] Read the current Play policies for each area in Decision 6 (Developer Program Policy, User Data, Data safety, AI-Generated Content, Intellectual Property, Device and Network Abuse on downloading code, Foreground services, Target audience). Quote what applies and give each Console answer.
- [ ] List any risk that needs a change before friends (in the app or the catalog) and raise it with Neal.

### Task 7: the tester note
- [ ] Write `docs/friend-testing/round-1-tester-note.md` (Decision 5); publish it as a private page for Neal to share; the "What's new" text.

### Task 8: the fresh-install walkthrough
- [ ] With Neal's OK (it erases what she's downloaded): `pm clear --user 0 app.granger` on the Pixel 7, then the first run from onboarding on: Pick a start, region, uses, review; a download over Wi-Fi; search; an article; Listen; Ask with its Preview; Send feedback.
- [ ] Note what a friend would trip on; fix the small ones here, put the rest in the roadmap for F2.

### Task 9: wrap up
- [ ] New UI text listed for L1 Task 8 (in the L1 plan's carried-forward list).
- [ ] Roadmap: F1 row done, with what was carried forward; F2 next.
- [ ] Merge to main, push.
