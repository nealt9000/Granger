# The iPhone app: picking it up

This page is for someone opening the iPhone app cold: what to install, how to build it and run it in the Simulator,
how the tests and checks work, how a core release goes out, what to try, and what only a real iPhone can show. What
"finished" means is `docs/superpowers/ios-parity.md`, the Android app row by row; the design is
`docs/superpowers/specs/2026-11-20-iphone-design.md`.

## How it's put together

- `ios/Granger/`: the app, SwiftUI, iOS 26. The Xcode project is generated from `project.yml` by XcodeGen and
  committed, so you need XcodeGen only when you change `project.yml` or add or remove a file.
- `ios/GrangerCore/`: a Swift package holding the shared Kotlin core as one binary, `GrangerCore.xcframework`
  (Kotlin Multiplatform, exported through SKIE), and `GrangerCoreKit`, its small Swift half. Most of what a screen
  shows comes from the same Kotlin view models Android uses; the Swift side draws them.
- `ios/Vendor/`: native libraries fetched or built by scripts, not in git (see below).
- Words: Android's `app/src/main/res/values*/strings*.xml` are the one source of English and Spanish.
  `tools/ios/make_text_ids.py` writes `ScreenText.swift` (the shared models' keys) and the String Catalog
  `ios/Granger/Resources/Localizable.xcstrings`; words only the iPhone has are in
  `ios/Granger/Resources/ios-only-strings.json`, English and Spanish side by side. Never edit the catalog by hand.
  In Swift, any word on screen that isn't a SwiftUI literal goes through `L("…")`.

## What you need

- A Mac with Apple silicon, Xcode 27 (27.1 used here), and its iOS 26 Simulator runtime.
- For building the Kotlin core (`core-local`, below), which any working branch needs:
  - JDK 17. `tools/ios/core-local.sh` looks for Homebrew's (`brew install openjdk@17`) unless `JAVA_HOME` is set.
  - The Android SDK, because the shared modules also build for Android: Android Studio's, or the command-line tools.
    Point Gradle at it with `ANDROID_HOME`, or a git-ignored `local.properties` at the repo's root holding
    `sdk.dir=/path/to/Android/sdk`. Without it the build stops at once with "SDK location not found". Gradle fetches
    the platform it needs on the first build once the SDK's licences are accepted (`sdkmanager --licenses`).
- `cmake` (two of the vendor libraries are built from source) and `xcodegen`: `brew install cmake xcodegen`.
- About 15 GB free: Gradle's and Kotlin/Native's caches, DerivedData and the vendor libraries.
- To install on an iPhone: an Apple developer team. The Simulator needs none.

## First build, from a fresh clone

```sh
git clone https://github.com/nealt9000/Granger.git && cd Granger
git submodule update --init --recursive core/taglib/src/main/cpp/taglib   # TagLib and its utfcpp, shared with Android

sh tools/ios/fetch-corekiwix.sh     # Kiwix's CoreKiwix.xcframework (pack reading), ~130 MB
sh tools/ios/fetch-sherpa-onnx.sh   # sherpa-onnx + onnxruntime (the Piper voices)
sh tools/ios/fetch-litertlm.sh      # LiteRT-LM (search by meaning)
sh tools/ios/fetch-libarchive.sh    # libarchive, built (CBR comics); needs cmake
sh tools/ios/build-taglib.sh        # TagLib, built from the submodule (MP3 tags); needs cmake
```

Each script checks what it downloads against a sha256 and does nothing when its library is already in `ios/Vendor`
at the right version. Run them again whenever one of them changes.

Then the core. There are two ways to get it:

- **The released core** (the default): `ios/GrangerCore/Package.swift` names a GitHub release
  (`ios-core-<date>`) and its checksum, and Xcode downloads it. Nothing to build, but it only fits the Swift code of
  the commit that pinned it. Use it on `main` right after a release.
- **This checkout's Kotlin** (`core-local`): `sh tools/ios/core-local.sh` builds `GrangerCore.xcframework` into
  `ios/GrangerCore/Local/` (about 6 minutes on an M-series Mac with Gradle's and Kotlin/Native's caches warm; the
  first run on a new machine downloads Gradle, the Kotlin/Native toolchain and the dependencies, and takes much longer),
  and every build then needs
  `GRANGER_CORE_LOCAL=1` in its environment. Use it on any branch whose Kotlin moved since the last release, which
  is any working branch. Run it again after every checkout of another commit, and after any change under `core/` or
  `shared/`.

To build and run from the command line on the Simulator (in Xcode, open `ios/Granger/Granger.xcodeproj` and run the
`Granger` scheme; for `core-local`, launch Xcode from a shell with `GRANGER_CORE_LOCAL=1` set, or use
`launchctl setenv GRANGER_CORE_LOCAL 1` before opening it):

```sh
cd ios/Granger
GRANGER_CORE_LOCAL=1 xcodebuild build -project Granger.xcodeproj -scheme Granger \
  -destination 'platform=iOS Simulator,name=iPhone 17' -derivedDataPath ../../build/ios-dd
xcrun simctl boot "iPhone 17" 2>/dev/null; open -a Simulator
xcrun simctl install "iPhone 17" ../../build/ios-dd/Build/Products/Debug-iphonesimulator/Granger.app
xcrun simctl launch "iPhone 17" app.granger
```

The first build also resolves the Swift packages (Readium, SQLite.swift and the rest) from GitHub; after that a clean
build takes under a minute. For your own iPhone: `cp ios/Local.xcconfig.example ios/Local.xcconfig`, set your team and a bundle prefix of your
own, and run on the device from Xcode.

## Tests

```sh
sh tools/ios/test-fixtures.sh ~/granger-fixtures          # once: ~230 MB, about 2 minutes, sha-checked
xcrun simctl uninstall "iPhone 17" app.granger            # before a full run
sh tools/ios/test.sh ~/granger-fixtures "iPhone 17"       # the whole suite, about 35 minutes
sh tools/ios/test.sh ~/granger-fixtures "iPhone 17" -only-testing:GrangerUITests/SmokeTests
```

- Two targets: `GrangerTests` (unit and spike tests in the host app) and `GrangerUITests` (XCUITest).
- **Fixtures.** The Simulator can't read every disk, so tests read their files from paths in environment variables:
  `GRANGER_TEST_ZIM` (a small pack), `GRANGER_TEST_BOOKPACK` (a book pack), `GRANGER_TEST_EPUB`, `GRANGER_TEST_PDF`,
  `GRANGER_TEST_VOICE` (the Owl voice, unpacked), `GRANGER_TEST_AUDIO` (testdata/audio), `GRANGER_TEST_SEARCH`
  (testdata/ios-search: a test Wiktionary and two slices of a set, made by `tools/test-zim/make_ios_search_zims.py`) and
  `GRANGER_TEST_EMBEDDER`. xcodebuild passes a variable to the tests when it's set as `TEST_RUNNER_<name>`;
  `tools/ios/test.sh` sets them all from the fixtures folder. A test whose fixture isn't set skips itself.
  `GRANGER_SHOTS=<folder>` makes some tests save screenshots there.
- Expect 3 skips: one M4B test and the two speech-recognition spikes, which need a real iPhone.
- Most UI tests need the internet (Wikipedia online, the catalog).
- **Clean data.** Every UI test starts from empty app data: `CleanData.args` passes a new `-resetData` token, and the
  app clears its data when it sees a token it hasn't had before. A relaunch inside a test keeps what the test made.
  Debug-only launch arguments (`-debugPack`, `-debugBook`, `-debugOpen`, `-debugCrash`…) set a test's scene up; they
  are read in `GrangerApp.swift` and do nothing in a release build.
- **The mini player** covers the foot of a page while XCTest still calls buttons under it hittable; scroll a
  control above it first (`SmokeTests.scrollAboveMiniPlayer`).
- **The JVM suite** (Android and the shared core), when Kotlin changed:
  `./gradlew test testAndroidHostTest jvmTest --continue` (plain `gradlew test` skips the multiplatform modules'
  Android-host tests). The Kotlin core's own iOS tests: `./gradlew iosSimulatorArm64Test`.

- Tried from a fresh clone (Plan I7 Task 6): the fetch scripts, `core-local`, the build, a launch, the fixtures
  script and a run of the fixture tests through `tools/ios/test.sh` all worked as written here.

## The checks

Run before every test build by the `GrangerTests` target, so a test run fails when one fails:

- `python3 tools/ios/make_text_ids.py --check`: every word on screen has Spanish, and `ScreenText.swift`, the String
  Catalog, `InfoPlist.xcstrings` and Android's `ScreenTextIds.kt` are up to date. Run it without `--check` to
  regenerate them after changing words (after a build of this checkout: it reads the literals the Swift compiler
  extracted, from the newest Granger build in DerivedData; pass `--build <dir>` to name one).
- `python3 tools/ios/make_licences.py --check`: every Xcode package and every `tools/ios/fetch-*.sh` /
  `build-*.sh` library is listed in `tools/ios/licences/licences.json`, and the generated `Licences.json` is up to
  date. Add an entry and rerun without `--check` when you bring a library in.

By hand: `python3 tools/ios/make_palette.py --check` (the colours match Android's theme), and
`tools/ios/make_appicon.py` / `make_appicon_variants.py` when the brand mark changes.

## Releasing the core

`sh tools/ios/release-core.sh` builds the Release core, zips it and prints what it would publish (a dry run).
`--publish` creates the GitHub release `ios-core-<date>` (or `-2`, `-3`… on the same day), uploads the zip and writes
its URL and checksum into `ios/GrangerCore/Package.swift`; commit that. It's a push to GitHub, so it needs the
maintainer's OK each time. Release when a branch that changed Kotlin merges, so `main` builds without `core-local`.

## What to try

In the order Android's walkthroughs go. The parity list says what each area should do.

1. **First run.** Brand, language (the knowledge language; the app language is iOS's own, Settings › Granger ›
   Language), Pick a start, the Granger voice, the privacy line before anything is read online.
2. **Home.** Continue, Ready offline, the Timeline, feeds (+ Add feed), Arrange Home, a game.
3. **Search.** Online titles and articles, a pack's Best matches (by meaning once the embedder is on the phone),
   Books, By kind, Show all / fewer.
4. **Wikipedia online.** A topic page (picture, lead, Read the full article, Keep, Read in the other language), the
   reader (Contents, text size, find, Listen), keeping and Local only.
5. **Explore.** The landing spread, a subject, a room, a broad topic, the quilt, A–Z, Through time, Your sources,
   Wander, Games.
6. **Get more and downloads.** Kiwix's list, the Wikipedia set, Granger Classics and its volumes, a download paused
   and resumed, Outfitting.
7. **Library.** The shelf, a collection, a book pack, Import from Files (EPUB, PDF, CBZ/CBR), archive.org, the
   e-book reader (contents, search, highlights, text settings, link peek), the PDF reader.
8. **Listen.** Read aloud in both readers, the sleep timer, music from Files, an audiobook, LibriVox, the mini
   player, lock-screen controls.
9. **Settings.** Appearance and text, Language, Content, Local only, Voice, Media, Models, About (Send feedback,
   Open-source licences).
10. **Spanish.** Settings › Granger › Language › Español, and the knowledge language moved to Spanish.
11. **Accessibility.** The largest text size (Settings › Accessibility › Display & Text Size › Larger Text), landscape,
    Night (dark appearance), VoiceOver.

## What only a real iPhone can show (I8)

- The mic: on-device speech recognition has no model in the Simulator (`SpeechSpikeTests` run on a device).
- CarPlay on a car (Apple's CarPlay audio entitlement, requested) and in the CarPlay Simulator window.
- The Music library's own songs, and which of them play.
- Hardware remote buttons, headphones unplugged, a phone call pausing playback.
- LibriVox and archive.org audio against the live services (tests use stubs).
- The app group on signed builds (the widget and Control Center's Local only control), and placing them by hand.
- MetricKit's crash, hang and disk-write reports, which iOS never delivers in the Simulator.
- Search by meaning's speed on a phone, and the 6 GB memory rule on real hardware.
- The App Store upload's privacy report (ITMS-91053) and the App Privacy answers ("Data Not Collected").

## Sending findings

Email feedback@opengranger.org, or Settings › About › Send feedback in the app (on the Simulator it opens the share
sheet, since Mail isn't set up there), or a GitHub issue. Say the screen, what you did, what you expected, and the text
size and language if they weren't the defaults; a screenshot helps (⌘S in the Simulator).
