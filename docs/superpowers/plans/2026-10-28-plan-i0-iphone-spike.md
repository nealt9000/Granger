# Plan I0: The iPhone spike — each dependency's iOS path, the architecture, the minimum iPhone

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Before any iPhone code is written, find out how Granger gets to the iPhone. That means each Android dependency's iOS path, how much code can be shared, which UI route fits one developer, the minimum iPhone that plays the Pixel 6's role, and what Apple requires before friends get a TestFlight build. The spike ends with numbers and decisions in `docs/superpowers/spikes/2026-10-iphone-spike-results.md` and a short spec that I1+ plans are written from. It doesn't end with shipped features, and nothing touches the Android app.

**Architecture:**
- **Desk research first.** Each dependency's iOS path comes from its own repository, releases and docs, read on the day. The answer doesn't come from memory: these projects change fast (LiteRT-LM's iOS support, sherpa-onnx's xcframework, Room's KMP targets). Every claim in the results carries a link and the version or date it was read at.
- **Mac trials second, small and thrown away.** These are a KMP compile of the pure-Kotlin cores for `iosArm64` and `iosSimulatorArm64`, plus two hello-world shells (SwiftUI over a KMP framework, and Compose Multiplatform with one `UIKitView`). Both run in the iOS Simulator. Trial code lives under `spikes/i0/` on this branch and never merges into the Android modules.
- **The iPhone measurements last.** A small harness app measures E2B first words, the EmbeddingGemma query embed, Piper's real-time factor and a libkiwix search on a real iPhone. It's built on the Mac and checked in the Simulator for function only. The numbers wait for an iPhone, which Neal decides on in Task 0.

**Tech Stack:** Xcode (current release) and the iOS Simulator, Kotlin 2.4.21 Multiplatform (`iosArm64`, `iosSimulatorArm64`), Compose Multiplatform (the version matching Kotlin 2.4), Swift/SwiftUI, Swift Package Manager. The candidates under study: CoreKiwix / libkiwix, Readium Swift toolkit, LiteRT-LM or MediaPipe LLM Inference or llama.cpp, sherpa-onnx iOS, Room KMP / SQLite, URLSession background sessions, AVFoundation + MediaPlayer, WidgetKit, CarPlay, Speech framework, PDFKit, CryptoKit.

**Roadmap:** `docs/superpowers/plans/2026-10-15-granger-v1-roadmap.md`, the I0 row and "Next phase: friend testing" step 2.

## Decisions made with Neal (2026-10-27, 2026-10-28)

- The iPhone is on the roadmap. I0 runs now, beside the UX work. The port (I1+) comes after friend round 1 (F2) and is built on the overhauled UI.
- The architecture is open: a shared Kotlin core through Kotlin Multiplatform with SwiftUI screens, or Compose Multiplatform screens. `:core:research`, `:core:text` and `:core:vectors` are already pure Kotlin.
- Speech input (A1) uses the phone's own on-device recognizer, never a bundled model. On iOS that's `SFSpeechRecognizer` with `requiresOnDeviceRecognition`, or `SpeechAnalyzer`.
- No telemetry. Feedback goes by email, as on Android (F1).
- 2026-10-28: F1's build work (signing, R8, Play Console) starts once U3b is merged. I0 is this session's work until then.

## Builder's calls in this plan (for Neal's review)

- **Results path:** `docs/superpowers/spikes/2026-10-iphone-spike-results.md`. The I1+ spec is written at `docs/superpowers/specs/<date>-iphone-design.md`, only after Neal has read the results.
- **Three architectures are compared, not two:** (A) a KMP core with SwiftUI screens, (B) a KMP core with Compose Multiplatform screens, and (C) a separate Swift app sharing only data formats (catalog JSON, `.gvec`, the pack manifest, the eval set). C is the baseline the other two have to beat.
- **Things the iPhone needn't match at first are named, not designed:** Android Auto (CarPlay), the widget (WidgetKit), phone media (no MediaStore) and "Show in other apps". Each gets an iOS path and a "port with I1 / later / never" call, and nothing more.
- **The trials are throwaway.** A KMP conversion that compiles in the trial doesn't change `core/*/build.gradle.kts` on this branch. The Android build stays exactly as on main.

## Global Constraints

- **The Pixel 6 is not used.** U3b (another session) needs it for its walkthrough. I0 never runs adb.
- **The Mac is shared.** V4's round 2 generation is running (Qwen3-TTS, about 1.2× real time), and U3b runs Gradle builds. The rules: one Gradle or Kotlin/Native build at a time from this session, `-Pkotlin.compiler.execution.strategy=in-process` and `./gradlew --stop` afterwards, as in earlier plans. No model generation or training on the Mac in this plan. Don't touch `voice-train-2026-10-22/` or the `v4`/`u3b` worktrees.
- **Stop for Neal at these steps:** installing Xcode (App Store, Apple ID, about 15 GB plus a simulator runtime), enrolling in the Apple Developer Program (US$99 a year), and buying or borrowing an iPhone. Pushing commits needs Neal's OK.
- **Licences:** Granger is GPLv3 (v1 spec §License). Every iOS dependency's licence is recorded. So is the App Store / GPL question: whether the Apple terms conflict with GPL code whose copyright Granger doesn't hold (libkiwix, and anything else GPL), and what Kiwix's own App Store app does about it. The answer goes in the results, and Neal decides.
- **Downloaded files are untrusted:** each archive goes in its own new directory under the scratchpad. Nothing from a download is run except through Xcode or Gradle builds of code this plan names.
- Worktree: `.claude/worktrees/i0`, branch `i0-iphone-spike`, off main 58cdc78. For Gradle, `JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home`, copy `local.properties` in, and run `git submodule update --init --recursive` before a Gradle build.

---

### Task 0: Neal's three calls (ask before Task 4)

- [x] **Xcode:** this Mac has only the Command Line Tools (`xcodebuild` fails), and Tasks 4–6 need full Xcode. Neal installs it from the App Store, or OKs `xcodes`. Disk is fine: 126 GB free. *Neal, 2026-10-28: install it (from the App Store).*
- [x] **Apple Developer Program:** enrol now (needed to install the harness on a real iPhone for more than 7 days, and for TestFlight), or put it off to I1. A free Apple ID can sideload to one's own iPhone with 7-day profiles, which is enough for Task 7. *Neal, 2026-10-28: yes, enrol now (Neal enrols on developer.apple.com; identity checks can take a day or two).*
- [x] **The iPhone:** which one is used for Task 7. Task 3 says which model is the minimum. Until there's an answer, Task 7 is planned and its harness is built, but it doesn't run. *Neal, 2026-10-28: the first will likely be an **iPhone 16 Plus** (confirmed). Every iPhone 16 has 8 GB, like the Pixel 6, so it's a fair stand-in for the ASK tier. Task 3 still names the oldest iPhone that qualifies.*

### Task 1: The Android dependency inventory

**Files:** create `docs/superpowers/spikes/2026-10-iphone-spike-results.md` (§1 Inventory).

- [x] List every module in `settings.gradle.kts` (`:app` plus 14 under `core/` and `:tools:chunker`) with its line count and its role in one line.
- [x] List every library in `gradle/libs.versions.toml` and every vendored binary (`core/speech/libs/sherpa-onnx-*.aar`, TagLib under `core/taglib`), with what Granger uses it for. Starting list: LiteRT-LM 0.18.0 (E2B and `LiteRtTextEmbedder`: EmbeddingGemma, both `.litertlm`), libkiwix 2.6.0, Readium 3.4.0 (+ the PDFium adapter), pdfbox-android, Room 2.8.5, WorkManager, OkHttp, BouncyCastle (Ed25519 catalog check), jsoup, commonmark, Media3 (ExoPlayer, session), Glance, sherpa-onnx 1.13.8, TagLib, kotlinx serialization and coroutines, Compose, Navigation.
- [x] List every Android platform API a feature depends on: WebView with the page scripts (read-aloud sentence marks, R3 passage highlights, selection actions), MediaStore and picked folders, MediaSession and Android Auto, the foreground download service and notification, `SpeechRecognizer` (A1, not built), file providers ("Show in other apps"), the themed icon, immersive mode, `ACTION_PROCESS_TEXT` and similar intents.
- [x] For `:core:text`, `:core:research` and `:core:vectors` (2,173 lines), list the JVM-only imports by file. Found so far: `org.jsoup` (`HtmlPassages`, `ArticleText`, `XhtmlChunks`), `java.text.Normalizer` and `BreakIterator`, `java.util.Locale`, `java.nio` `FileChannel`/`ByteBuffer` and `RandomAccessFile` (`VectorFile`), `MessageDigest`, `ExecutorService`, `PriorityQueue`. These are the KMP work in Task 4.
- [x] Then the same check for the Android-library cores that are mostly logic: `:core:catalog` (parsing, signature check), `:core:library` (Room DAOs, filing, `DirectoryVectorSources`), `:core:downloads`. Separate the logic that could be shared from the Android glue around it.
- *Done 2026-10-28 (desk research, subagents): results §1.*

### Task 2: Each dependency's iOS path (desk research)

**Files:** results §2, one row per dependency: the iOS path, its licence, its maturity (release, last commit, an app shipping it), the effort (drop-in / wrap / rewrite) and the risk.

- [x] **libkiwix / libzim:** how Kiwix's iOS app (`kiwix/kiwix-apple`) builds and links it (CoreKiwix.xcframework or similar), whether a prebuilt framework is published, the licence (GPLv3), and the App Store / GPL question from the Global Constraints.
- [x] **Readium:** the Swift toolkit's version against Kotlin 3.4.0, whether EPUB pagination, highlights, the content iterator (for read-aloud sentence marks), decorations and PDF reach parity, and what our Kotlin glue (`:core:books`, the reader screens) maps to.
- [x] **E2B and EmbeddingGemma:** whether LiteRT-LM runs on iOS today (a Swift or C++ API, Metal GPU) and reads the same `.litertlm` files. The fallbacks are MediaPipe LLM Inference (`.task`) and llama.cpp (GGUF, Metal). For each one, record the model file format, whether the catalog needs a second file per model (`ModelEntry` gains a platform), and whether the R5 prompt pipeline (rewrite, entities, `YearFacts`, best guess) moves over unchanged. Also the memory ceiling: the iOS per-app limit, the `com.apple.developer.kernel.increased-memory-limit` entitlement, and what E2B plus EmbeddingGemma plus Piper need together (the Pixel 6 numbers are in the R4/R5 results and V0).
- [x] **sherpa-onnx and Piper:** the iOS xcframework and its Swift API, whether our Piper voices (`granger_libritts`, `ljspeech`, and V4's `en-gb` ones) load unchanged, and the audio route: `AVAudioEngine` under the music, with `AVAudioSession` categories for the mix and screen-off reading.
- [x] **TagLib:** a C++ build for iOS through CMake or SPM, and whether iOS even needs it (no MediaStore: a user's music comes from Files imports or the Music library).
- [x] **Storage and data:** Room KMP (2.7+ lists iOS targets) against GRDB or SwiftData; the bundled SQLite and its FTS4 (the e-book index is contentless FTS4); whether the library schema (v13+) can be shared as is.
- [x] **Downloads:** URLSession background sessions against WorkManager (multi-GB ZIMs, resume, sha256 after, the app suspended or killed). Also iOS's limits: no persistent foreground service, system-scheduled resumes, and whether cellular-versus-Wi-Fi rules can be enforced.
- [x] **Players:** AVFoundation plus MPNowPlayingInfoCenter and MPRemoteCommandCenter against Media3, the voice as a third player mode, the CarPlay audio entitlement against Android Auto, and WidgetKit plus App Intents against the Glance widget.
- [x] **Smaller swaps:** OkHttp → Ktor (Darwin engine) or URLSession; BouncyCastle Ed25519 → CryptoKit `Curve25519.Signing` (check it verifies our catalog signature format); jsoup → Ksoup or a SwiftSoup boundary; pdfbox-android page text → PDFKit `PDFPage.string`; commonmark → a KMP Markdown parser or swift-markdown; the WebView → `WKWebView` with the same page scripts (check `evaluateJavaScript` and the message handlers cover what our JS bridge does).
- [x] **Speech input (A1):** `SpeechAnalyzer` (iOS 26) and `SFSpeechRecognizer` with `requiresOnDeviceRecognition`: which iPhones and languages work offline, and contextual strings against `EXTRA_BIASING_STRINGS`.
- [x] **Phone media and sharing:** what replaces MediaStore (the Files app, `UIDocumentPickerViewController` for folders, MPMediaLibrary for non-DRM Music items) and what replaces "Show in other apps" (`UIFileSharingEnabled` / `LSSupportsOpeningDocumentsInPlace`, so packs show up in Files).
- *Done 2026-10-28 (desk research, subagents): results §2a, 2b.*

### Task 3: The minimum iPhone

**Files:** results §3.

- [x] Find the RAM per iPhone model still supported by the current iOS (Apple doesn't publish RAM, so use teardown or benchmark sources and say which). Find the per-app memory ceilings people have measured, with and without the increased-memory-limit entitlement.
- [x] Set the Pixel 6's budget against them: E2B resident on the GPU, EmbeddingGemma for the query, a Piper voice, the reader and libkiwix. Use the Pixel 6's peak RSS figures from V0, R4 and R5.
- [x] Propose the tiers to mirror `DeviceTier`: an ASK-tier iPhone (runs Ask) and a READ-tier iPhone (everything but Ask), with the oldest model in each. Also propose the minimum iOS version, from what the dependencies in Task 2 require.
- *Done 2026-10-28 (desk research, subagents): results §3.*

### Task 4: The shared core trial (Mac, needs Xcode)

**Files:** `spikes/i0/kmp-core/` (a standalone Gradle build that includes copies of the three cores' sources; the real modules stay untouched). Results §4.

- [x] Make a standalone KMP build with `jvm()`, `iosArm64()` and `iosSimulatorArm64()` targets over copies of `core/text`, `core/research` and `core/vectors` `src/main` in `commonMain`.
- [x] Compile for iOS and record every error, grouped by cause: the Task 1 imports, plus anything else (`String.format`, `synchronized`, reflection, `java.util.regex` differences).
- [x] For each cause, write down the replacement and its size, for example `expect`/`actual` `Normalizer` and `BreakIterator` (ICU on iOS through `NSString`), Ksoup for jsoup, and okio or kotlinx-io for `VectorFile`'s memory-mapped reads. Fix enough to get `:core:vectors` compiling for iOS, as a sample of the real work.
- [x] Run the existing JVM tests of the converted module under the KMP build's `jvmTest`, and the same tests under `iosSimulatorArm64Test` if they compile. Record pass counts. That shows whether "shared" holds on both sides.
- [x] Estimate the work for the rest of the logic that could be shared (Task 1's last step): catalog parsing and the signature check, the library DAOs if Room KMP fits, filing, readiness, the Home feed engine.
- *Done 2026-10-28: all three cores compile for iOS; 225 + 7 JVM tests and 2 + 5 Simulator tests pass; one parity difference (sentence breaks at abbreviations). Results §4.*

### Task 5: The UI route trial (Mac, needs Xcode)

**Files:** `spikes/i0/ui-swiftui/`, `spikes/i0/ui-compose/`. Results §5.

- [ ] **SwiftUI over KMP (A):** an Xcode project that links the `kmp-core` framework from Task 4 and calls one real function, for example `VectorFile` search over a small `.gvec` or `SpokenText` on a sentence. Note how Kotlin types, `suspend` functions and `Flow` look from Swift (SKIE or KMP-NativeCoroutines against the plain export).
- [ ] **Compose Multiplatform (B):** one screen with the Field and Night colours and Spectral, plus a `UIKitView` holding a `WKWebView` that shows a ZIM-like HTML page. Note scrolling, text selection, the keyboard and Back gestures, which are where Compose on iOS has usually felt foreign.
- [ ] Count the Android UI code: `:app` is 25,572 lines. Estimate how much of it B could carry over (Compose screens with Android-only parts swapped out) against A's rewrite. Name the screens that are mostly platform views either way (the readers, the player).
- [ ] Neal looks at both shells in the Simulator. His impression goes in the results; the decision is in Task 8.

### Task 6: The Apple side

**Files:** results §6.

- [x] Apple Developer Program: cost, enrolment as an individual or organisation, and what each shows on the App Store listing (the name a friend sees).
- [x] TestFlight: internal testers (on the team, no review) against external testers (up to 10,000, Beta App Review on the first build), build expiry, and how a friend joins with a public link against an email invite.
- [x] App Review risks for Granger: downloading large content after install (ZIMs, models, voices: data, not code, guideline 2.5.2), the LLM disclaimer (Ask as a Preview), archive.org and non-commercial packs that Granger links to, the Supporter purchase (in-app purchase rules, Plan 10), and the background download modes.
- [x] Privacy: the privacy manifest (`PrivacyInfo.xcprivacy`) and required-reason APIs our dependencies touch, and the App Privacy "Data not collected" label, which fits the no-telemetry brand.
- *Done 2026-10-28 (desk research, subagents): results §6.*

### Task 7: The iPhone harness (built on the Mac; the numbers wait for an iPhone)

**Files:** `spikes/i0/harness/` (a SwiftUI app). Results §7.

- [ ] Build an app with four buttons and a log, mirroring the Pixel 6 spikes: **E2B** (load time, first words, tokens per second on one R5 tuning question with its passages, peak memory), **EmbeddingGemma** (one query embed, cold and warm), **Piper** (the Granger voice: RTF on V0's passages, first audio), **libkiwix** (open a small ZIM, title search and full-text search times). Each uses the runtime Task 2 picked. Models and the ZIM are copied in through Finder or the Files app, not bundled.
- [ ] Check every button in the Simulator for function only, never for numbers.
- [ ] **When Neal has an iPhone:** sideload it, then run each test from a force-quit app, on power, screen on, one cold and one warm run, decisions on the warm run (Plan 1's rules). Record the model, iOS version and free memory.
- [ ] **Adopt against these rules** (the Pixel 6's bar from the friend build): E2B first words ≤ 12 s on the R5 question, Piper RTF ≤ 0.5 screen on, and no memory kill with E2B, EmbeddingGemma and Piper loaded in turn. An iPhone that misses the E2B rule is READ-tier.

### Task 8: Results and decisions

**Files:** finish the results doc; modify the roadmap (the I0 row, and the I1+ row's delivers line).

- [ ] Write a summary up front: the architecture recommended (A, B or C) and why, the dependencies that are drop-in, wrap or rewrite, the hardest three, the minimum iPhone and iOS, the Apple costs and review risks, and a rough size of I1+ in plans.
- [ ] List the decisions for Neal: the architecture, the App Store / GPL answer, enrolment timing, the iPhone tiers, and what's in the first iPhone friend build versus later (CarPlay, widget, phone media, A1).
- [ ] Once Neal decides, write the I1+ spec and split it into plans (likely I1 shared core + storage + catalog, I2 the readers, I3 Ask and the voice, I4 Listen + downloads + TestFlight). Update the roadmap rows. Then commit: `I0: the iPhone spike, results and decisions`.

## Order and size

Tasks 1–3 and 6 are desk research and can run now, in parallel subagents, with no Xcode, phone or Mac load. Tasks 4 and 5 need Xcode (Task 0) and take a few hours of Mac time between U3b's builds. Task 7's harness is built along with Task 5; its numbers wait for an iPhone. If no iPhone arrives, Task 8 is written with Task 7 marked pending, and the tier rules stand on the desk figures from Task 3 until it's measured. Expect about a day of work before any iPhone is involved.
