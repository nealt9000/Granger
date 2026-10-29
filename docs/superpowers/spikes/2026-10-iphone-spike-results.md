# I0: the iPhone spike — results

Plan: `docs/superpowers/plans/2026-10-28-plan-i0-iphone-spike.md`. Desk research read 2026-10-28; every claim links its source. Sections 4 (shared core trial), 5 (UI route trial) and 7 (iPhone numbers) are pending Xcode and the iPhone 16 Plus. The summary and decisions (Task 8) are written once those land.

## Desk findings at a glance (2026-10-28)

- **Highest risks:** the App Store and third-party GPL code (libkiwix GPL-3.0, libzim GPL-2.0, Xapian, espeak-ng): Neal's call, best after asking Kiwix, whose own app ships on the App Store. Then downloads: background URLSession dies on a user force-quit and resume needs ETag/Last-Modified. Then whether our pinned E2B `.litertlm` loads on LiteRT-LM's iOS Metal backend ("Early Preview", v0.18.1).
- **Wrap, not rewrite:** CoreKiwix.xcframework (same libkiwix 14.2.1), LiteRT-LM Swift (E2B + EmbeddingGemma), sherpa-onnx 1.13.8 xcframework (Piper voices unchanged), Room KMP (FTS4 included), CryptoKit for the catalog signature, Ksoup / JetBrains markdown / Ktor Darwin.
- **Rewrite:** the reader glue over Readium Swift 3.11 (~2.9k lines), the players (AVFoundation), the download transport and worker, the WebView host (`zim:` scheme instead of intercepted https).
- **Shared Kotlin today:** `:core:text`, `:core:research`, `:core:vectors` (2,173 lines) after swapping jsoup, `Normalizer`, `BreakIterator`, `java.nio`; plus most of `:core:catalog`, ~1,060 lines of `:core:library` logic, ~620 lines of download rules, `ReadinessModel`. `:app` is 25,572 lines.
- **Minimum iPhone:** ASK tier = 8 GB iPhones with the increased-memory-limit entitlement (iPhone 15 Pro and every iPhone 16 onwards); gate on `os_proc_available_memory()` at run time. READ tier = everything the minimum iOS runs. Proposed minimum iOS 26.0.
- **Later, not I1:** CarPlay (request the entitlement during I1), the widget, the Music library, "Show in other apps", speech input (with A1).
- **Apple:** US$99/yr; an individual account shows Neal's legal name as seller, "Granger" needs a legal entity + D-U-N-S; friends join TestFlight as external testers (one review on the first build, 90-day builds).


## 1. Inventory

Read from the worktree off main 58cdc78, 2026-10-28. Line counts are `*.kt` under `src/main` only.

### 1.1 Modules

| Module | Main lines | Plugin | Role |
|---|---:|---|---|
| `:app` | 25,572 | android app | All Compose UI: rail, Home, readers (ZIM WebView, Readium e-books/PDF), Ask, player screens, Settings, onboarding, readiness, widget |
| `:core:ai` | 783 | android lib | LiteRT-LM wrappers: `LiteRtLlmEngine` (E2B) and `LiteRtTextEmbedder` (EmbeddingGemma) |
| `:core:zim` | 419 | android lib | libkiwix wrapper: `KiwixZimArchive`, `KiwixZimOpener` (open, entries, search) |
| `:core:library` | 4,895 | android lib | Room library DB (books, media, shelves, chats, Home feeds, pins), FTS book index, passage finders, filing |
| `:core:catalog` | 513 | android lib | Signed catalog fetch, parse, Ed25519 check |
| `:core:downloads` | 1,343 | android lib | WorkManager download queue, OkHttp ranged fetch, sha256, voice unzip, volumes |
| `:core:books` | 768 | android lib | EPUB/PDF inspection, DRM check, PDF text (pdfbox), text/Markdown→EPUB |
| `:core:archive` | 810 | android lib | archive.org and LibriVox clients (OkHttp + serialization) |
| `:core:research` | 1,704 | **kotlin-jvm** | Ask pipeline logic: rewrite, entities, `YearFacts`, cited quotes, follow-ups, media cues |
| `:core:taglib` | 47 (+C++) | android lib + NDK | JNI tag reader over vendored TagLib |
| `:core:media` | 1,801 | android lib | Phone/own media scanning (MediaStore, picked folders), tags, `FoundFile` |
| `:core:player` | 3,790 | android lib | Media3 player, `PlaybackService` (MediaLibraryService), Android Auto browse tree, ReplayGain, voice mode |
| `:core:speech` | 1,043 | android lib | sherpa-onnx Piper TTS (`SherpaTts`), read-aloud |
| `:core:text` | 255 | **kotlin-jvm** | HTML/XHTML → passages and chunks (jsoup), text cuts |
| `:core:vectors` | 214 | **kotlin-jvm** | `.gvec` file reader (`VectorFile`) and parallel top-k search (`VectorSearch`) |
| `:tools:chunker` | 60 | kotlin-jvm | Desktop CLI for building chunks (not shipped) |

Total shipped Kotlin outside `:app`: about 18,400 lines; of that only `:core:text/research/vectors` (2,173) are plain JVM today.

### 1.2 Libraries and vendored binaries (main source sets only)

| Library | Version | Modules | Used for |
|---|---|---|---|
| LiteRT-LM (`litertlm-android`) | 0.18.0 | ai | E2B chat engine and EmbeddingGemma embedder, both `.litertlm` |
| libkiwix (`org.kiwix:libkiwix`) | 2.6.0 | zim | Opening ZIMs, entry reads, title/full-text search (GPL, native) |
| Readium shared/streamer/navigator | 3.4.0 | app | EPUB reader (`ReadiumBooks`, `EbookReaderScreen`, `ReadingPrefs`), selection menu, decorations |
| readium-adapter-pdfium + AndroidPdfViewer (marain87) | 3.4.0 / 3.2.8 | app | PDF rendering in the Readium navigator; AndroidPdfViewer only to compile against `PDFView.Configurator` |
| pdfbox-android | 2.0.27.0 | books | PDF page text and metadata (`PdfText`, `PdfInspector`) |
| Room runtime/ktx (+KSP compiler) | 2.8.5 | library, downloads (media, player, app use runtime types) | Library DB and download DB, FTS4 book index |
| WorkManager (`work-runtime-ktx`) | 2.12.0 | downloads | `DownloadWorker` as foreground (dataSync) work |
| OkHttp | 5.5.0 | catalog, downloads, archive (app via `Http.kt`) | Catalog fetch, ranged downloads, archive.org/LibriVox APIs |
| BouncyCastle `bcprov-jdk18on` | 1.86 | catalog | Ed25519 verify of the catalog `.sig` (`CatalogSignature`, 23 lines) |
| jsoup | 1.21.2 | text, research, library, books, app | HTML parsing: passages, article text, XHTML chunks, EPUB inspection, DRM check, thumbnails, Home feeds (`FeedDrawer`, `DayPage`) |
| commonmark | 0.30.0 | books | Markdown → EPUB (`TextToEpub`) |
| Media3 exoplayer/session/common | 1.11.1 | player, app | Playback, MediaLibraryService/session, Auto browse tree, ReplayGain processor; app's `MediaControllerPlayerHub` |
| Glance appwidget | 1.2.0 | app | Player widget (`PlayerWidget`, `PlayerWidgetReceiver`, `WidgetActions`) |
| androidx.webkit | 1.14.0 | app | `ReaderWebView` (WebViewCompat features) |
| kotlinx-serialization-json | 1.11.0 | app, catalog, archive, media, chunker | Catalog JSON, archive APIs, media sidecars |
| kotlinx-coroutines | 1.11.0 | all | Everywhere (Flow/StateFlow in repositories) |
| Compose BOM / material3 / icons, activity-compose, navigation-compose, lifecycle, fragment-compose | 2026.09.00 / 1.13.0 / 2.10.2 / 2.11.0 / 1.9.0 | app | UI; fragment-compose hosts Readium's fragment navigator |
| desugar_jdk_libs | 2.1.5 | app | java.time etc. on minSdk 29 |
| **sherpa-onnx AAR (vendored)** | 1.13.8, `core/speech/libs/sherpa-onnx-1.13.8-static-arm64-x86_64.aar` | speech | Piper VITS TTS (`SherpaTts`); static onnxruntime inside, arm64 + x86_64 |
| **TagLib (vendored source)** | `core/taglib/src/main/cpp/taglib` | taglib | Built from source by CMake through AGP `externalNativeBuild` (NDK 30.0.16248370, arm64-v8a + x86_64): `add_subdirectory(taglib)` + `granger_taglib` SHARED from `taglib_jni.cpp` |

### 1.3 Android platform APIs features depend on

| Feature | API | Where |
|---|---|---|
| ZIM reader | `WebView` + `shouldInterceptRequest` serving ZIM entries | `app/.../reader/ZimWebViewClient.kt`, `ReaderWebView.kt` |
| Page scripts | Injected JS held as Kotlin strings: `ReaderPageScript.SOURCE` (48 lines; taps → chrome toggle, scroll reports) and `ReadAloudScript.SOURCE` (184 lines; read-aloud sentence marks, `firstVisible`, R3 `cite`/`clearCite` passage highlight, `clear`) run by `evaluateJavascript`; results parsed with `JSONTokener`. Plus `app/src/main/assets/reader/jquery-3.7.1.min.js`; `DevDocsFixes.kt`, `MainPageFixes.kt` patch pages | `app/.../reader/` |
| JS bridge | `addJavascriptInterface(GrangerBridge(onToggleChrome, onUserScroll), BRIDGE_NAME)` with `@JavascriptInterface` methods — two callbacks only | `ReaderWebView.kt:65` |
| E-book selection actions | Readium selection + custom menu (`SelectionMenu.kt`); Define via `Intent.ACTION_PROCESS_TEXT` query (manifest `<queries>`) | `app/.../ebook/Dictionary.kt`, `SelectionMenu.kt` |
| Immersive reading | `WindowInsetsControllerCompat` `BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE` | `ReaderScreen.kt:578` |
| Phone media | `MediaStore` audio query, `READ_MEDIA_AUDIO`; picked folders via `OpenDocumentTree` (SAF) | `core/media/.../MediaStoreSource.kt`, `FoundFile.kt`, `app/.../media/PhoneMediaScans.kt`, `SettingsScreen.kt:221` |
| Playback + Auto | Media3 `MediaLibraryService` (`PlaybackService`), `MediaBrowserService` intent, MEDIA_BUTTON receiver, FGS mediaPlayback, `AutoBrowseTree`/`MediaBrowseTree` | `core/player/src/main/AndroidManifest.xml`, `PlaybackService.kt` |
| Cover art | `ArtworkProvider` ContentProvider (Auto, widget, session) | `app/.../media/`, manifest |
| Downloads | WorkManager foreground worker, FGS `dataSync`, `POST_NOTIFICATIONS`, notification channel | `core/downloads/.../DownloadWorker.kt`, its manifest |
| Storage volumes | `StorageManager`, `Environment` (SD card choice) | `core/downloads/.../Volumes.kt` |
| "Show in other apps" | `MediaDocumentsProvider` (DocumentsProvider, disabled until the Settings switch) | `app/.../media/MediaDocumentsProvider.kt`, `MediaDocuments.kt` |
| Widget | Glance `GlanceAppWidget` + `APPWIDGET_UPDATE` receiver | `app/.../widget/` |
| Themed icon | adaptive icon with `<monochrome>` | `app/src/main/res/mipmap-anydpi/ic_launcher.xml` |
| Links out | `Intent.ACTION_VIEW` to the browser (archive.org, LibriVox, e-book links) | `ReaderScreen.kt:630`, `EbookReaderScreen.kt:407`, `ArchiveScreen.kt`, `LibriVoxScreen.kt` |
| Speech input | `SpeechRecognizer`: not used (A1 not built) | — |

### 1.4 JVM-only imports in the pure-Kotlin cores

| Module / file | JVM imports | Used for |
|---|---|---|
| text/`ArticleText.kt` | `org.jsoup.Jsoup`, `nodes.Element` | Article body text from ZIM HTML |
| text/`HtmlPassages.kt` | `org.jsoup.Jsoup`, `nodes.Element` | Splitting HTML into passages |
| text/`XhtmlChunks.kt` | `org.jsoup` Jsoup, Element, TextNode, `parser.Parser` | EPUB XHTML → chunks (XML parser mode) |
| text/`Chunkers.kt` | `java.security.MessageDigest` | Chunk ids/hashes |
| text/`TextCuts.kt` | (`Character.isHighSurrogate`, no import) | Surrogate-safe cuts |
| research/`CitedQuote.kt` | `java.text.BreakIterator`, `java.text.Normalizer`, `java.util.Locale` | Sentence splitting, accent folding for quote matching |
| research/`Entities.kt` | `Normalizer`, `Locale` | Entity name folding |
| research/`YearFacts.kt` | `Normalizer`, `Locale` (+ ~11 `Regex`) | Year fact extraction; regex flavour must hold on Native |
| research/`MediaCues.kt` | `Normalizer`, `Locale` | Cue matching |
| research/`FollowUp.kt` | `Locale` | Case folding |
| vectors/`VectorFile.kt` | `java.io` Closeable, File, IOException, RandomAccessFile, ByteArrayOutputStream; `java.nio` ByteBuffer, ByteOrder, `channels.FileChannel`; `String.format` ×2 | Memory-mapped `.gvec` reads |
| vectors/`VectorSearch.kt` | `java.util.PriorityQueue`, `concurrent.Callable`, `ExecutorService` | Parallel top-k over shards |

No `synchronized`/reflection found in these three. Normalizer is the most widespread (4 files); jsoup is in 3 text files.

### 1.5 Shareable logic vs Android glue

**`:core:catalog` (513)** — almost all shareable.

| Shareable | Glue / JVM |
|---|---|
| `Catalog.kt` 212 (model, readiness tiers), `CatalogParser.kt` 144 (kotlinx JSON), `CatalogSignature.kt` 23 (BouncyCastle Ed25519 + `java.util.Base64` → CryptoKit or a KMP Ed25519) | `CatalogRepository.kt` 134: OkHttp fetch, `java.io.File` cache, `java.time.Instant`; no Android imports |

**`:core:library` (4,895)** — Room is the spine; logic is mixed into Room-using classes.

| Shareable (pure, ~1,060) | Room-bound but logic-heavy (~2,100) | Pure Room glue (~1,020) |
|---|---|---|
| `PassageAddress` 175, `Filing` 115, `Albums` 112, `DirectoryVectorSources` 93, `MediaCards` 81, `MediaAddress` 55, `Library` 46, `MediaNames` 37, `BookIds` 21, `MediaText` 16, `LibrarySearch` 87 (ranking), `LibraryPassageFinder` 335 (`ConcurrentHashMap` only), `DensePassageFinder` 160 (ExecutorService, ZipFile, jsoup Parser) | `Bookshelf` 506 (java.nio Files, MessageDigest, UUID, `withTransaction`), `BookIndex` 320 (FTS4 via `SimpleSQLiteQuery`, `ZipFile`), `Shelves` 284, `MediaIndex` 254, `ResearchChats` 190, `MediaLibrary` 155, `ReadingSessions` 141, `Playlists` 103, `HomeFeeds` 71, `RailPins` 52, `PdfCovers` 41 (android.graphics) | `Daos` 379, `Entities` 278, `LibraryDatabase` 240 (schema/migrations), `MediaDaos` 211, `RoomLibrary` 184, `MediaEntities` 153 |

If Room KMP fits, most of the middle column moves with it; otherwise the DAO calls need an interface seam.

**`:core:downloads` (1,343)**

| Shareable (~620) | Glue (~720) |
|---|---|
| `Downloads.kt` 356 (queue/state rules, readiness), `QueuePolicy` 41, `VectorDownloads` 63, `DownloadRecord` 54 (Room entity, logic otherwise), `IntegrityChecker` 36 + `Sha256` 29 (MessageDigest), `VoiceUnpacker` 103 (ZipFile) | `DownloadWorker` 186 (WorkManager, FGS, notification), `FileDownloader` 179 (OkHttp ranged + RandomAccessFile), `DownloadRunner` 122, `DownloadDatabase` 114 (Room), `Volumes` 60 (Context, StorageManager) |

Readiness rules themselves live mostly in `:app` (`app/.../readiness/ReadinessModel.kt`, 279 lines, plain Kotlin) plus `Catalog.kt`/`Downloads.kt`; the Home feed engine is split between `:app` (`FeedDrawer`, `DayPage`, jsoup) and `HomeFeeds.kt`.


## 2a. iOS paths: native libraries and models

Desk research, read 2026-10-28. Every source below was read that day unless another date is given. GitHub release dates are shown without a year on the release pages; the year comes from the GitHub API (`published_at` or `pushed_at`) where it's quoted. Nothing was downloaded, built or run.

"What Granger uses" comes from the i0 worktree (off main 58cdc78): `gradle/libs.versions.toml`, `core/ai`, `core/zim`, `core/books` with `app/.../ebook/*` (2,945 lines), `core/speech`, `core/taglib` and `core/media`.

---

### 2a.1 libkiwix / libzim (ZIM reading and search)

**What Granger uses.** The Java wrapper `org.kiwix:libkiwix:2.6.0` uses `org.kiwix.libzim.Archive`, `Searcher`, `Query`, `SuggestionSearcher`, plus `JNIKiwix`. Per the [java-libkiwix releases](https://github.com/kiwix/java-libkiwix/releases), 2.6.0 bundles **libkiwix 14.2.1 and libzim 9.7.0**. 2.7.0 came out on 21 Oct. ZIM pages render in a WebView, with `ZimWebViewClient` serving the archive's entries.

**iOS path.** Kiwix publishes a prebuilt **`CoreKiwix.xcframework`** with libkiwix, libzim and their static dependencies.
- Archives sit at [mirror.download.kiwix.org/release/libkiwix/](https://mirror.download.kiwix.org/release/libkiwix/). The latest is `libkiwix_xcframework-14.2.1-2.tar.gz` (58 MB, 27 May 2026), and `14.2.1` itself is from 11 May 2026. That's the **same libkiwix 14.2.1** our Android build has, so search behaviour should match.
- [kiwix/kiwix-apple](https://github.com/kiwix/kiwix-apple) pulls that archive in from its [Brewfile](https://raw.githubusercontent.com/kiwix/kiwix-apple/main/Brewfile) at version 14.2.1-2.
- The Brewfile then copies `Support/CoreKiwix.modulemap` into each slice's headers. That's how Swift's **C++ interoperability** gets to call libkiwix/libzim directly.
- The [README](https://raw.githubusercontent.com/kiwix/kiwix-apple/main/README.md) also shows the from-source route: `kiwix-build --config apple_all_static libkiwix`.
- kiwix-apple's [project.yml](https://raw.githubusercontent.com/kiwix/kiwix-apple/main/project.yml) sets **iOS 17.6 / macOS 14.6** deployment targets at app version 3.18.0 (build 250). It links CoreKiwix as a non-embedded, code-signed framework.

**The API on iOS is C++, not Java.** Our Kotlin `org.kiwix.libzim.*` calls don't carry over. The libzim C++ classes (`zim::Archive`, `zim::Searcher`, `zim::SuggestionSearcher`) are the same concepts, so the mapping is one to one.
- Under architecture C (Swift app) or A/B with the reader in Swift, we call them from Swift through the same modulemap Kiwix uses.
- Under a KMP core, Kotlin/Native can't call C++ directly. It would need a thin C or Objective-C++ shim for cinterop, or the ZIM layer stays on the Swift side behind an interface.
- Serving pages changes too. Android's `shouldInterceptRequest` becomes a `WKURLSchemeHandler` on `WKWebView`, which is what kiwix-apple does (to confirm in its source during the Mac trial).

**Licence.**
- libkiwix: **GPL-3.0** ([GitHub API](https://api.github.com/repos/kiwix/libkiwix)). Distributions label it GPL-3.0-or-later.
- libzim: **GPL-2.0** ([GitHub API](https://api.github.com/repos/openzim/libzim); "or later" to confirm in its COPYING).
- Xapian, which libzim's full-text search links: **GPL v2+** ([xapian.org](https://xapian.org/)).
- kiwix-apple: **GPL-3.0-or-later** (README).

**The App Store / GPL question** (for Neal; this is a reading, not legal advice):
- **The conflict.** Apple's [standard Licensed Application EULA](https://www.apple.com/legal/internet-services/itunes/dev/stdeula/) (no date on the page; © 2026) limits use to "Apple-branded products that you own or control" and says users "may not transfer, redistribute or sublicense" or "modify, or create derivative works". The FSF's position is that terms like these are further restrictions the GPL forbids. In 2010–11 that got VLC pulled after a VLC copyright holder complained ([Engadget, 2011-01-09](https://www.engadget.com/2011-01-09-the-gpl-the-app-store-and-you.html); [InformationWeek](https://www.informationweek.com/it-leadership/apple-pulls-vlc-player-from-app-store)). VideoLAN's own developers disputed that reading ([vlc-devel, 2010-11](https://mailman.videolan.org/pipermail/vlc-devel/2010-November/076998.html)).
- **What has changed.** The same EULA page now says the licence is subject to the standard EULA *or* "a custom end user license agreement between you and the Application Provider". It also excepts copying and modification "where … open-source license terms allow". A developer can name the GPL as the custom EULA. That's the usual mitigation, but it isn't a settled answer.
- **What Kiwix does.** Kiwix ships GPLv3 kiwix-apple, linked statically with GPLv3 libkiwix, GPLv2 libzim and GPLv2+ Xapian, on the App Store ([Kiwix, id997079563](https://apps.apple.com/us/app/kiwix/id997079563)). The README says the apps are "made available primarily via the App Store and Mac App Store". Kiwix holds the copyright in libkiwix and kiwix-apple and evidently consents. It does **not** hold the copyright in Xapian. Kiwix hasn't published a GPL/App Store statement in the repo or README. None was found.
- **What it means for Granger.**
  - Granger's own code is no problem: Neal holds the copyright and can add a GPLv3 §7 additional permission for App Store distribution.
  - Static libkiwix/libzim/Xapian inside a Granger IPA is the **same exposure Kiwix has carried for years without a takedown**. The risk is that a copyright holder of those libraries complains, which is what happened to VLC.
  - **Cheapest de-risking:** ask Kiwix (the copyright holder for libkiwix and a big part of libzim) for written permission, or for their view, before the first public App Store build. TestFlight to friends has the same terms question, but much less exposure.
  - This applies to **espeak-ng too** (GPL-3.0-or-later, see 2a.4). It's in our Android sherpa-onnx 1.13.8 build and would be in an iOS 1.13.x build.

**Maturity.** libkiwix 14.2.1 (11 May 2026, [releases](https://github.com/kiwix/libkiwix/releases)), last push 2026-10-27. libzim last push 2026-10-19. kiwix-apple last push 2026-10-26, 802 stars. **Shipping app:** Kiwix for iOS/macOS on the App Store.

**Effort: wrap.** Drop in the prebuilt xcframework and write a Swift (or C-shim) wrapper of about the size of our `core/zim` Kotlin glue. The WebView serving becomes a `WKURLSchemeHandler`.

**Risk.**
- Low technically: the same library version, with Kiwix's own app as the reference.
- Medium on licence (above).
- Small on size: about 58 MB compressed for every slice. The device slice in the IPA will be smaller, to measure.

**Open questions.**
- (Mac) Does the 14.2.1-2 xcframework link into a SwiftUI shell with the modulemap trick, and how big is the arm64 slice?
- (Mac) Look in kiwix-apple's source for its `WKURLSchemeHandler` and search wrapper, to borrow.
- (iPhone harness) Open a small ZIM, then time title suggestions and full-text search against the Pixel 6.
- (Neal) Ask Kiwix about App Store distribution of a third-party GPL app linking CoreKiwix.

---

### 2a.2 Readium (EPUB and PDF reader)

**What Granger uses.** Readium Kotlin **3.4.0** (11 Sep, still "Latest" on [kotlin-toolkit releases](https://github.com/readium/kotlin-toolkit/releases)):
- `EpubNavigatorFragment`.
- `Decoration` with a `Highlight` style for three groups: the voice's current sentence, search hits, and the user's annotations (`BookAnnotations.kt`).
- `SearchIterator`.
- `HtmlResourceContentIterator` and `DefaultContentService`, which feed our own read-aloud pipeline (sherpa-onnx, not Readium's TTS).
- A custom `PerResourcePositionsService`.
- `evaluateJavascript` for `FirstVisibleText`.
- `PdfNavigatorFragment` through `readium-adapter-pdfium`, plus pdfbox-android for PDF page text.

**iOS path.** The **Readium Swift toolkit** ([readium/swift-toolkit](https://github.com/readium/swift-toolkit)), by SPM.
- Latest stable is **3.11.1 (2026-10-16)**. A 3.9.0 was also tagged on 2026-10-16, and **4.0.0-alpha.2** came out 2026-09-18 ([releases API](https://api.github.com/repos/readium/swift-toolkit/releases?per_page=6)).
- The Swift and Kotlin version numbers are **not aligned**: Swift 3.11 isn't "ahead" of Kotlin 3.4 feature for feature.
- Minimum **iOS 15.0** for 3.8.0+ and `develop`. `develop` wants Swift 6.2 and Xcode 26.4 ([README](https://raw.githubusercontent.com/readium/swift-toolkit/develop/README.md)).

**Parity with what we use** (README feature table: EPUB reflow / FXL / PDF):

| Feature | Swift toolkit | Granger's need |
|---|---|---|
| Pagination and scrolling | ✅ / ✅ / ✅ | ✅ parity |
| Search in text | ✅ / ✅ / ✅. 3.9.0 adds PDF text extraction and search through the Content API, and a `ContentSearchService` | ✅ parity, and Content-API PDF text **can replace pdfbox-android** |
| Decorations (highlights) | ✅ / ✅ / **👀 not on PDF** | EPUB voice, search-hit and annotation highlights ✅. PDF highlights aren't there (check if we decorate PDFs on Android; the code shows decorations only on the EPUB path) |
| Content iterator | `HTMLResourceContentIterator` (the 3.9.0 notes fix O(N²) selector generation in it) | ✅ our sentence feed |
| TTS | `PublicationSpeechSynthesizer` with a pluggable `TTSEngine` (default `AVTTSEngine`); utterances carry locators, and the guide shows a `Decoration` on the utterance locator ([TTS guide](https://github.com/readium/swift-toolkit/blob/develop/docs/Guides/TTS.md)) | We can either keep our own pipeline over the content iterator, or plug sherpa-onnx in as a `TTSEngine`. The second could save code. |
| PDF | Readium's own PDF navigator. 3.9.0: no HTTP server needed and faster opening. Its engine isn't named in the README; it's expected to be PDFKit (to confirm) | ✅, with no PDFium adapter needed |
| JS into the page | `EPUBNavigatorViewController` evaluates JS (to confirm the exact API in the Mac trial) | `FirstVisibleText` |

**Licence.** BSD-3-Clause ([API](https://api.github.com/repos/readium/swift-toolkit)). LCP isn't used.

**Maturity.** Last push 2026-10-23, with releases every 1–3 months. **Shipping apps:** the Palace Project library app, which Lyrasis says is "based on" Readium Mobile ([readium.org news](https://readium.org/news/_posts/lyrasis-member)). The Readium Swift test app is the other reference.

**Effort: rewrite the glue.** About 2,900 lines of `app/.../ebook` (navigator host, preferences, annotations, contents panel, start locator, read source) become Swift view controllers wrapped for SwiftUI or Compose's `UIKitView`. The Readium concepts are the same: `Locator`, `Publication`, `Decoration`, the content iterator and preferences. `core/books` (inspection and chunking via jsoup) is separate and belongs to the KMP question.

**Risk.**
- Medium. The two toolkits differ in detail: the preferences API, the positions service (our custom `PerResourcePositionsService` may have no hook), the async model (Swift concurrency), and Swift 6 strictness.
- 4.0 is in alpha, so I1 should decide whether to start on 3.11 or wait for 4.0.

**Open questions.**
- (Mac) Can a custom positions service be plugged in? Is there an equivalent for every Kotlin preference we expose (theme, font family, text align, colours)?
- (Mac) Does `evaluateJavaScript` return values the way `FirstVisibleText` needs?
- (Mac) Does the PDF navigator use PDFKit, and is 3.9's Content-API PDF text good enough to drop pdfbox (compare on two of our PDFs)?
- (Neal or I1) Readium 3.11 or wait for 4.0?

---

### 2a.3 E2B and EmbeddingGemma (Ask, rerank, semantic search)

**What Granger uses.** LiteRT-LM **0.18.0** (`litertlm-android`): `Engine`, `ConversationConfig`, `SamplerConfig`, `ThinkingConfig`, `ExperimentalFlags`, a GPU backend, `MAX_CONTEXT_TOKENS = 4096`, and `EmbeddingEngine` for EmbeddingGemma. The catalog pins two files:
- `litert-community/gemma-4-E2B-it-litert-lm` @ `b3ca0d2…`: **`gemma-4-E2B-it-gpu.litertlm`**, 2,008,432,640 B.
- `litert-community/embeddinggemma-2-text-270m-litert-lm` @ `9be6e8b…`: **`embeddinggemma-2-text-270m.litertlm`**, 164,626,432 B.

The R5 pipeline (rewrite, entities, `YearFacts`, best guess) lives in `:core:research` (pure Kotlin) behind `LlmEngine` (`load()`, `generate(prompt, maxTokens): Flow<String>`).

**iOS path 1: LiteRT-LM Swift (recommended to trial first).**
- **It runs on iOS today.**
  - The [LiteRT-LM README](https://github.com/google-ai-edge/LiteRT-LM) lists Android, iOS, Web, Desktop and IoT. Swift is **"Early Preview"**; C++ and Kotlin are "Stable".
  - The [overview](https://developers.google.com/edge/litert-lm/overview) (updated 2026-10-06) marks iOS **CPU: Supported, GPU: Supported, NPU: Not supported**, with "specialized Metal support".
- **How it's packaged.**
  - [`Package.swift`](https://raw.githubusercontent.com/google-ai-edge/LiteRT-LM/main/Package.swift) on main: product `LiteRTLM`, **iOS 15** minimum, binary target `CLiteRTLM.xcframework` from release **v0.18.1**. v0.18.1 came out 20 Oct and fixed an ABI issue with EmbeddingGemma 2 when building from source ([releases](https://github.com/google-ai-edge/LiteRT-LM/releases)).
  - v0.17.0 (09 Sep) added "Metal residency support … on Apple devices".
- **The API mirrors ours.**
  - The [Swift API page](https://developers.google.com/edge/litert-lm/swift) (updated 2026-09-04) has `EngineConfig(modelPath: …litertlm, backend: .gpu /* Metal */ | .cpu(), maxNumTokens:, cacheDir:)`.
  - Then `createConversation()` and `sendMessageStream` (`for try await`).
  - `ConversationConfig` has `systemMessage`, `samplerConfig`, `tools` and `thinkingConfig`, and `ExperimentalFlags`.
  - [`swift/`](https://github.com/google-ai-edge/LiteRT-LM/tree/main/swift) contains `EmbeddingEngine.swift`, `EmbeddingEngineConfig.swift` and their tests. The v0.18.0 notes say EmbeddingGemma 2 ships "across Python, Kotlin, **Swift**, Web, and C++".
- **Same files?**
  - The model card ([HF card](https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm)) names `gemma-4-E2B-it.litertlm` (2,588,147,712 B) as the file for "Android, iOS, Desktop, IoT". Its iPhone 17 Pro rows (1024 prefill / 256 decode / 2048 context) are:
    - **GPU:** 2,878 tok/s prefill, 56.5 tok/s decode, 0.3 s TTFT (load time excluded), **1,450 MB** CPU/GPU memory.
    - **CPU:** 532 / 25.0 / 1.9 s / 607 MB.
  - **The `-gpu.litertlm` file we pin (2.0 GB, same size as the `-web` file) isn't named for iOS.** Whether it loads on the Metal backend is the first thing to check. If it doesn't, the iOS catalog entry is the 2.59 GB generic file.
  - EmbeddingGemma's [repo](https://huggingface.co/api/models/litert-community/embeddinggemma-2-text-270m-litert-lm/tree/main) has one generic file plus SoC-specific NPU files (none for Apple). The generic file we pin should serve both platforms.
- **Shipping app.** Google's **AI Edge Gallery** on the App Store (the overview links it; the app is described as experimental). Third-party reports say it runs Gemma 4 E2B on iPhone ([MindStudio](https://www.mindstudio.ai/blog/google-ai-edge-gallery-run-llms-offline-iphone); these are secondary).
- **An independent iPhone 17 Pro comparison** (iOS 26.4.2, [DEV, 2 Jun 2026](https://dev.to/john-rocky/on-device-llm-on-iphone-which-runtime-is-fastest-mlx-vs-llamacpp-vs-litert-lm-vs-coreml-1b42)) has Gemma 4 E2B decode at:
  - **LiteRT-LM 55.4 tok/s, peak 641 MB**
  - MLX-Swift 47.5 tok/s, 2,900 MB
  - llama.cpp 37.8 tok/s, 3,156 MB
  - Core ML/ANE 33.4 tok/s, 1,187 MB

  One author, one device, no load times. Still, the ranking matches Google's.
- **Catalog.** If the pinned files load, `ModelEntry` needs **no platform field**. If only the generic E2B loads on iOS, the entry gains a per-platform `fileName`/`url`/`sha256`/`sizeBytes`, or the catalog moves both platforms to the generic file (it would need an Android check on the Pixel 6: speed and the R5 eval).
- **R5 pipeline: moves unchanged.** Prompts, sampler and thinking options map one to one, and the model file carries the chat template. The R5 eval (grounded 91/126) should be re-run once on iPhone, in case the Metal kernels differ numerically. Kotlin can't call the Swift API directly, though. Under KMP the engine sits behind `LlmEngine`, implemented in Swift and handed to Kotlin, or Kotlin uses cinterop over `CLiteRTLM`'s C header (if it exposes one; to check).

**iOS path 2: MediaPipe LLM Inference (`.task`). Not recommended.**
- The [iOS guide](https://developers.google.com/edge/mediapipe/solutions/genai/llm_inference/ios) (updated 2026-10-20): "The MediaPipe LLM Inference API is in maintenance-only mode. … We recommend migrating your iOS projects to LiteRT-LM Swift API."
- A `gemma-4-E2B-it-web.task` exists (2,003,697,664 B) but is aimed at the web.
- It has no EmbeddingGemma route. Moving to it would need a second catalog file per model.

**iOS path 3: llama.cpp (GGUF, Metal). The fallback.**
- MIT ([API](https://api.github.com/repos/ggml-org/llama.cpp)), pushed 2026-10-28.
- Every build publishes an iOS **`llama-bNNNNN-xcframework.zip`** (b11963 on 28 Oct; [releases](https://github.com/ggml-org/llama.cpp/releases)).
- Gemma 4 E2B GGUFs exist only from the community, for example [Mungert](https://huggingface.co/Mungert/gemma-4-E2B-it-GGUF). There's no Google-published GGUF.
- It means a **second model file per platform** (`ModelEntry` gains a platform), our own chat template and sampler mapping, **a full R5 re-eval** (a different quantization changes grounding), and roughly **5× the memory** in the comparison above.
- EmbeddingGemma would also need a GGUF build (to check).

**The memory ceiling.**
- **The entitlement.** `com.apple.developer.kernel.increased-memory-limit` exists from iOS 15.0 ([Apple docs](https://developer.apple.com/tutorials/data/documentation/bundleresources/entitlements/com.apple.developer.kernel.increased-memory-limit.md)). It's "only available on some device models", and apps should "call `os_proc_available_memory`". It must come through the provisioning profile.
  - An Apple DTS engineer warns that test-device limits overstate real-world ones, because other apps compete for memory ([forum 770868, Dec 2024](https://developer.apple.com/forums/thread/770868)). In that thread an iPad (16 GB) reached about 15 GB in a dev build but about 6 GB from the App Store. The likely cause was a signing slip.
- **Measured ceilings.** About **6,144 MB per process** on an iPhone 16 Pro Max (8 GB) and an iPhone 17 Pro Max (12 GB), measured with `os_proc_available_memory` and `phys_footprint`. The entitlement didn't raise it on the 17 Pro Max ([forum 834805, Jun 2026](https://developer.apple.com/forums/thread/834805); no Apple reply, FB23183521). An older report has 4 GB iPhone 12-class phones killed at an **ActiveHard 2,098 MB** limit ([forum 688973, 2021](https://developer.apple.com/forums/thread/688973)). Task 3 owns the per-model table.
- **The Pixel 6 budget** we'd need to fit:
  - E2B (Google build, GPU): **2.4 GB** peak ([research spike](../2026-10-research-spike-results.md), R0 table; RSS misses GPU memory, so it's a lower bound).
  - E2B with the embedder co-resident: **2,193 MB** peak RSS ([model spike](../2026-10-model-spike-results.md), check 4).
  - A Piper voice while speaking: **+420 MB** ONNX Runtime arena. Beside E2B the process peaks at **2.3 GB** ([voice spike](../2026-10-voice-spike-results.md)).
  - Swapping to E4B peaked at 4.4–4.7 GB (model spike). R4 saw a CPU candidate killed at 4.7 GB RSS ([plan R4](../../plans/2026-10-23-plan-r4-answer-model.md)).
- **iOS estimate.** About **1.45 GB** for E2B on GPU (Google's iPhone row) + about **0.2 GB** for EmbeddingGemma (165 MB file) + about **0.45 GB** for Piper + the app and reader. WKWebView content runs in its own process and isn't counted against ours. That comes to about **2.3–2.6 GB**: under 6 GB on an 8 GB iPhone with room to spare, and not dependent on the entitlement.
  - Memory-mapped weights that are clean pages should count less toward `phys_footprint` than Metal buffers do. To measure, not assume.
  - A 6 GB iPhone's ceiling, probably about 3 GB (Task 3), is where it gets tight.

**Licence.** LiteRT-LM is Apache-2.0. The Gemma models are under Gemma terms (unchanged from Android). llama.cpp is MIT.

**Effort.** **Wrap** with LiteRT-LM Swift: the same files, about 300 lines of Swift for the engine and embedder, plus the Kotlin bridge if KMP. **Rewrite and re-eval** with llama.cpp.

**Risk.** Medium.
- The Swift API is labelled Early Preview.
- Our `-gpu` file may not be the iOS file.
- iOS phones vary more in RAM than our single Pixel 6.
- The upside: Google ships E2B on iPhone in its own App Store app.

**Open questions.**
- (Mac) Does `LiteRTLM` 0.18.1 build in a SwiftUI shell? Does `CLiteRTLM` expose a C header Kotlin/Native could cinterop?
- (Mac) Do both pinned files load in the macOS slice with `.gpu` (Metal) as a proxy?
- (iPhone harness) Load time, first words on the R5 question (bar: ≤ 12 s), tok/s and `phys_footprint` for both the `-gpu` and the generic E2B file.
- (iPhone harness) EmbeddingGemma query embed, cold and warm.
- (iPhone harness) `os_proc_available_memory()` with and without the entitlement.

---

### 2a.4 sherpa-onnx + Piper (the reading voice)

**What Granger uses.** A vendored `sherpa-onnx-1.13.8-static-arm64-x86_64.aar` (no Maven artifact), with `OfflineTts`, `OfflineTtsVitsModelConfig` (plus Kokoro config classes, not shipped). The Piper voices use espeak-ng phonemes: `phoneme_type: espeak`, each with an 18 MB `espeak-ng-data` ([voice spike](../2026-10-voice-spike-results.md)).

**iOS path.** sherpa-onnx publishes **iOS xcframeworks and an SPM package**.
- [`Package.swift`](https://raw.githubusercontent.com/k2-fsa/sherpa-onnx/master/Package.swift) on master: **iOS 15** minimum.
  - Products `sherpa-onnx` (static) and `sherpa-onnx-shared`.
  - Binary targets `sherpa-onnx-v2.0.1-ios-static.xcframework.zip` / `-ios-shared…`.
  - Depends on `onnxruntime-libs` 1.28.2.
- The [`xcframework` release](https://github.com/k2-fsa/sherpa-onnx/releases/expanded_assets/xcframework) keeps every version: **`sherpa-onnx-v1.13.8-ios-static.xcframework.zip` (16.2 MB, 2026-09-10)**, the exact version we ship on Android, through v2.0.1 (2026-10-26).
- **The Swift API is a thin wrapper over the C API** ([`swift-api-examples/SherpaOnnx.swift`](https://raw.githubusercontent.com/k2-fsa/sherpa-onnx/master/swift-api-examples/SherpaOnnx.swift)):
  - `sherpaOnnxOfflineTtsVitsModelConfig(model:lexicon:tokens:dataDir:…)` and `sherpaOnnxOfflineTtsConfig(…maxNumSentences: 1)`.
  - `SherpaOnnxOfflineTtsWrapper.generate(text:sid:speed:)`.
  - `generateWithCallbackWithArg` (a per-chunk callback, like the Android `generateWithCallback` our first-audio timing relies on).
  - `generateWithConfig`.
- SwiftUI TTS examples are in [`ios-swiftui/`](https://github.com/k2-fsa/sherpa-onnx/tree/master/ios-swiftui) (`SherpaOnnxTtsLexicon`, `SherpaOnnxTtsPhonemize`).
- The C API is plain C, so **Kotlin/Native cinterop is straightforward** if the voice logic should live in KMP.

**A change since our pin: sherpa-onnx 2.0 dropped espeak-ng.**
- [PR #4055](https://github.com/k2-fsa/sherpa-onnx/pull/4055) (merged 2026-10-20) and [issue #3731](https://github.com/k2-fsa/sherpa-onnx/issues/3731), released in **v2.0.0 (23 Oct)** ([notes](https://github.com/k2-fsa/sherpa-onnx/releases/tag/v2.0.0)). Its effects:
  - "espeak-ng and piper-phonemize are no longer built into sherpa-onnx". The reason is licence ("sherpa-onnx is GPL-free after this PR").
  - `dataDir` is deprecated and ignored.
  - Piper models now need either a `lexicon.txt` (the maintainer says one will be added to each model directory) or an **external phonemizer** whose output goes in through the new `phonemeCodepoints` / `tokens` fields of `SherpaOnnxGenerationConfigSwift`.
- **Do our voices load unchanged?**
  - **Yes on iOS 1.13.8**: the same config, with `espeak-ng-data` as `dataDir`.
  - **No on 2.x**: our own voices (`granger_libritts`, `ljspeech`, and V4's `en-gb`) would need a lexicon file or our own phonemizer. A lexicon can't phonemize words outside it, so names would get worse, and names are already Piper's weak spot.
- **The licence angle cuts both ways.** Staying on 1.13.x keeps GPL-3.0 espeak-ng ([espeak-ng](https://github.com/espeak-ng/espeak-ng)) statically in the iOS app. That adds a second third-party GPL component to the App Store question in 2a.1. Moving to 2.x makes sherpa-onnx itself Apache-2.0, but we'd likely link espeak-ng ourselves to keep the quality, so the exposure is the same. **The same choice faces Android** at its next sherpa-onnx bump.

**Audio route.**
- Granger's voice and music both live **inside our app**, so they mix in our own graph: an `AVAudioEngine` with an `AVAudioPlayerNode` for voice PCM next to the music player, or two players. The session category is about **other** apps.
- **For reading in the background or with the screen off:** category `.playback` plus the **Audio background mode** ("Audio, AirPlay, and Picture in Picture"), per [Configuring your app for media playback](https://developer.apple.com/tutorials/data/documentation/avfoundation/configuring-your-app-for-media-playback.md). The default session is silenced when the phone locks.
- **Under someone else's music** (the Music app): `.mixWithOthers` "mixes its audio with audio playing in background apps, such as the Music app" and applies to `.playback`, `.playAndRecord` and `.multiRoute` ([mixWithOthers](https://developer.apple.com/tutorials/data/documentation/avfaudio/avaudiosession/categoryoptions-swift.struct/mixwithothers.md)).
  - `.duckOthers` lowers the other audio, but Apple says use it "on a temporary basis only … not … for more than a few seconds" ([duckOthers](https://developer.apple.com/tutorials/data/documentation/avfaudio/avaudiosession/categoryoptions-swift.struct/duckothers.md)). So for long reading it's mix, not duck.
  - `interruptSpokenAudioAndMixWithOthers` pauses podcasts and audiobooks but mixes with music. It may fit the voice best.
  - A mixable session generally can't be the Now Playing app (lock-screen controls). That's widely reported but not confirmed in the Apple pages read today. Plan for switching the category per mode: non-mixable `.playback` when Granger is the main player, mixable when it reads over other music.
- **Screen off speed.** On the Pixel the voice cost RTF 0.17 against 0.12 with the screen on, under a foreground service (voice spike, the screen-off run). iOS has no foreground service. With the audio background mode and an active session the app keeps running, but the CPU scheduling for ONNX Runtime with the screen off is unknown.

**Licence.** sherpa-onnx is Apache-2.0 ([API](https://api.github.com/repos/k2-fsa/sherpa-onnx)). espeak-ng is GPL-3.0-or-later (if bundled). ONNX Runtime is MIT. Voice licences are unchanged from Android.

**Maturity.** v2.0.1 (26 Oct). Pushed 2026-10-28, 15.6k stars. New versions come about every 1–3 weeks, each with iOS xcframeworks. No App Store app using sherpa-onnx TTS was confirmed in this pass (to find).

**Effort: drop-in / wrap** on 1.13.8 (the same files and config, about 200 lines of Swift for the engine plus the AVAudioEngine player). **Wrap plus a phonemizer** on 2.x.

**Risk.** Medium-low on function; the voice config is identical on 1.13.8.
- The version fork: 1.13.x will stop getting fixes.
- The screen-off RTF and the audio-session modes are where iOS differs.
- The ONNX Runtime arena (+420 MB) counts against the memory budget in 2a.3.

**Open questions.**
- (Mac) Does the 1.13.8 iOS static xcframework link in a SwiftUI shell, and run a Piper voice with `espeak-ng-data` in the macOS slice?
- (Mac) Is 2.x plus our own espeak-ng phonemize step (into `phonemeCodepoints`) worth the change?
- (iPhone harness) The Granger voice's RTF and first audio on V0's passages, screen on and **screen locked** with the audio background mode (bar: RTF ≤ 0.5).
- (iPhone harness) Mixing over the Music app with `.mixWithOthers` and with `interruptSpokenAudioAndMixWithOthers`, and whether lock-screen controls survive.

---

### 2a.5 TagLib (audio tags)

**What Granger uses.**
- TagLib as a git submodule (`core/taglib/src/main/cpp/taglib`) built by CMake into `libgranger_taglib` with a JNI shim (`taglib_jni.cpp`).
- The Kotlin `TagLib.read(fd, fileNameHint)` returns the PropertyMap, duration, sample rate, bitrate, channels and the first embedded cover.
- `core/media` (`TagReader`, `TagParser`, `PhoneScanner`, `TagReadGuard`) uses it for phone and downloaded audio. Its extensions: mp3, m4a, m4b, aac, flac, ogg, opus, wav, wma. Chapters come from our own `ChapterReader`.

**Does iOS need it?** Less than Android, but probably yes.
- There's no MediaStore, so the "phone music" scan becomes:
  - Files imports and picked folders (`UIDocumentPickerViewController`), which hold arbitrary files whose tags we'd read ourselves.
  - The Music library through MPMediaLibrary (2a's sister section). MPMediaItem already exposes its own metadata, so TagLib isn't needed there.
- **AVFoundation's** `AVAsset` metadata covers mp3, m4a/m4b (including chapters) and wav. It's the no-dependency option, and enough for audiobooks from archive.org in mp3/m4b.
- Whether AVFoundation reads FLAC Vorbis comments, Ogg/Opus tags and WMA wasn't confirmed in this pass. TagLib reads all of them, and **ReplayGain keys** (which our PropertyMap path uses) are TagLib territory.
- **Recommendation:** start I1 on AVFoundation metadata plus MPMediaItem. Bring TagLib in only if the Files-import formats need it.

**iOS path if needed.**
- TagLib is plain C++ with CMake and zlib (which iOS ships). It builds for iOS with CMake's iOS support (`-DCMAKE_SYSTEM_NAME=iOS`) or as an SPM C++ target.
- There are community SPM wrappers: [TagLibKit](https://www.github.com/hisaac/TagLibKit) ships a prebuilt xcframework (its TagLib version unknown). Swift Package Index logs show CXXTagLib and spfk-taglib packages building for iOS ([example](https://swiftpackageindex.com/builds/FF19F067-CE4E-48EB-B4AC-CECA49CC3992)).
- A [2020 taglib-devel thread](https://mail.kde.org/pipermail/taglib-devel/2020-November/003003.html) describes an xcframework build for iOS and macOS.
- Our JNI shim becomes a small C or Objective-C++ shim. That's about the same size, and cinterop-friendly if it should be KMP.

**Licence.** **LGPL-2.1** ([API](https://api.github.com/repos/taglib/taglib); TagLib is dual LGPL-2.1/MPL-1.1 upstream). Static linking into a GPLv3 app is fine under the LGPL. On the App Store the same EULA question as GPL applies in principle, but it's weaker (the LGPL's relinking terms are the issue). MPL-1.1 is the cleaner choice there, and TagLib offers it.

**Maturity.** v2.3.2 (2026-09-05, [latest release](https://api.github.com/repos/taglib/taglib/releases/latest)) hardened parsing against crafted files and fixed data races. Pushed 2026-10-28. It's widely used on desktop; no iOS App Store app using it was confirmed in this pass.

**Effort.** None if AVFoundation is enough. **Wrap** (CMake or SPM build plus a shim) if not.

**Risk.** Low. It's optional, and the fallback is a system framework.

**Open questions.**
- (Mac) What `AVAsset` metadata and `AVAsset.chapterMetadataGroups` return on a sample of our formats (FLAC, Ogg Vorbis, Opus, WMA with ReplayGain and an embedded cover). That decides TagLib in or out for I1.

---

### 2a.6 Summary

| Dependency | iOS path | Licence | Maturity (read 2026-10-28) | Effort | Risk |
|---|---|---|---|---|---|
| libkiwix / libzim | Prebuilt `CoreKiwix.xcframework` 14.2.1-2 (same libkiwix as Android 2.6.0), Swift C++ interop via modulemap; `WKURLSchemeHandler` for pages | GPL-3.0 (libkiwix), GPL-2.0 (libzim), GPL-2+ (Xapian) | libkiwix 14.2.1 (May 2026), pushed 2026-10-27; ships in Kiwix on the App Store (iOS 17.6+) | Wrap | Low technical; **medium licence** (App Store vs third-party GPL; ask Kiwix) |
| Readium | Swift toolkit 3.11.1 by SPM (4.0 in alpha), iOS 15+ | BSD-3-Clause | 3.11.1 (2026-10-16), pushed 2026-10-23; Palace Project | Rewrite the reader glue (about 2.9k lines) | Medium (API differences, positions service, no PDF decorations) |
| E2B + EmbeddingGemma | LiteRT-LM Swift (`LiteRTLM` SPM, `CLiteRTLM.xcframework` v0.18.1, Metal GPU, `EmbeddingEngine`), iOS 15+; fallback llama.cpp GGUF; MediaPipe is maintenance-only | Apache-2.0; Gemma terms | v0.18.1 (20 Oct); Swift "Early Preview"; Google AI Edge Gallery on the App Store; iPhone 17 Pro 56.5 tok/s, 1.45 GB | Wrap (same `.litertlm` if the `-gpu` file loads; else a per-platform file) | Medium (preview API, which file, RAM across iPhones) |
| sherpa-onnx + Piper | `sherpa-onnx-v1.13.8-ios-static.xcframework` (our exact version) or SPM v2.0.1; C/Swift API; `AVAudioEngine` + `.playback`, audio background mode, `.mixWithOthers` | Apache-2.0; espeak-ng GPL-3.0+ if kept | v2.0.1 (26 Oct), pushed 2026-10-28; 2.0 **dropped espeak-ng** | Drop-in/wrap on 1.13.8; wrap + phonemizer on 2.x | Medium-low (version fork, screen-off speed, session modes) |
| TagLib | Likely not needed at first (AVFoundation + MPMediaItem). If needed, CMake/SPM C++ build + shim | LGPL-2.1 / MPL-1.1 | v2.3.2 (2026-09-05), pushed 2026-10-28 | None, or wrap | Low |

**Biggest risk:** the licence and the model file, not the code. All five have a working iOS build today. The open points:
- **Licence.** Third-party GPL code (libkiwix/libzim/Xapian, and espeak-ng if Piper keeps its phonemizer) inside an App Store binary needs a decision from Neal and ideally a word from Kiwix.
- **Model file.** Whether our pinned `gemma-4-E2B-it-gpu.litertlm` loads on the iOS Metal backend decides if the catalog needs a second E2B file.


## 2b. iOS paths: platform services and smaller swaps

All sources were read on 2026-10-28. For Apple docs, the version is the platform availability the page states. For libraries, it's the latest release or tag that day. "What Granger uses" comes from the i0 worktree (off main 58cdc78). Effort means: **drop-in** (same API or a thin adapter), **wrap** (a platform API behind an existing Kotlin seam), or **rewrite** (the logic or the shape of the code changes).

### 2b.1 Storage and data

**What Granger uses.** Room 2.8.5 with KSP serves two databases. `LibraryDatabase` is v13 with `exportSchema = true` and 11 hand-written migrations. `DownloadDatabase` is v3. The e-book index is a contentless FTS4 table that Room doesn't manage: `CREATE VIRTUAL TABLE book_text USING fts4(content="", text, tokenize=unicode61)` (`core/library/.../LibraryDatabase.kt:213`). Our own BM25 is computed from `matchinfo(book_text,'pcnx')` (`BookIndex.kt:143`). Several Android-only APIs are in the way:
- every `Migration.migrate(SupportSQLiteDatabase)` and the `onCreate` callback;
- `@RawQuery fun chunksMatching(SupportSQLiteQuery)` (`Daos.kt:292`);
- `SimpleSQLiteQuery`;
- `db.openHelper.readableDatabase.query(...)` with a `Cursor` (`BookIndex.kt:136`).

**iOS path, option 1: Room KMP in the shared core (recommended for A and B).**
- Room has listed iOS among its KMP targets since 2.7.0: "Android, iOS, JVM (Desktop), native Mac and native Linux" ([Room release notes](https://developer.android.com/jetpack/androidx/releases/room); stable 2.8.5, 2026-09-09).
- Room 3.0 is now stable: 3.0.3 shipped 2026-09-09, and 3.1.0-alpha03 shipped 2026-10-21. It's KMP-first, uses the package `androidx.room3`, and "no longer references SupportSQLite types such as SupportSQLiteDatabase or Android types such as Cursor" ([Room 3 release notes](https://developer.android.com/jetpack/androidx/releases/room3)).
- In KMP, `@RawQuery` takes a `RoomRawQuery`. Migrations and callbacks take `SQLiteConnection`. DAO functions must be `suspend` or return `Flow`. `createFromAsset` and multi-instance invalidation aren't available ([Room KMP guide](https://developer.android.com/kotlin/multiplatform/room), updated 2026-10-21).
- **Bundled SQLite and FTS4.** `BundledSQLiteDriver` (`androidx.sqlite:sqlite-bundled`) compiles SQLite **3.53.4** with `SQLITE_ENABLE_FTS3`, `FTS3_PARENTHESIS`, `FTS4` and `FTS5`, plus `JSON1`, `RTREE` and `MATH_FUNCTIONS` ([sqlite-bundled build.gradle at HEAD](https://android.googlesource.com/platform/frameworks/support/+/HEAD/sqlite/sqlite-bundled/build.gradle)). So the contentless FTS4 table, `unicode61` and `matchinfo` all exist on iOS, and they behave the same on both platforms if Android also moves to the bundled driver.
  - One check for the trial: `FTS3_PARENTHESIS` turns on FTS "enhanced query syntax". Our MATCH strings quote every word and join with spaces (implicit AND), so the result should be the same, but Task 4 should run the `BookIndex` tests on both drivers.
- **Can the schema be shared as is?** The tables, entities and `schemas/*.json` can. The migration *bodies* need porting from `SupportSQLiteDatabase.execSQL` to `SQLiteConnection.execSQL`, which is mechanical. So do `BookIndex`'s two raw cursor queries (to `RoomRawQuery` or `useReaderConnection { prepare(...) }`). The `room-sqlite-wrapper` / `getSupportWrapper()` artefact can carry the Android side during the move.
- **Licence:** Apache-2.0.
- **Effort:** wrap. The entities and DAOs move to `commonMain`. Migrations, `BookIndex` and the database builder need edits, about 500 lines of mechanical change. Moving to Room 3 at the same time adds a package rename.
- **Risk: low to medium.** Room KMP is mature, but the bundled driver changes Android's SQLite. It would no longer be the system one. Run the e-book index tests (and the R5 eval's book retrieval) on the bundled driver before switching Android.

**Option 2: GRDB (for architecture C, Swift only).**
- GRDB.swift v7.11.1 was released 2026-06-18 under MIT ([releases](https://api.github.com/repos/groue/GRDB.swift/releases/latest)).
- Its FTS API covers FTS3, FTS4 and FTS5, and it shows FTS4 with `content = ""` ([FullTextSearch.md](https://github.com/groue/GRDB.swift/blob/master/Documentation/FullTextSearch.md)). By default it uses the system SQLite. FTS5 "needs a custom SQLite build" per that doc. FTS4 works on the system library, but no Apple page states its compile options.
- **Effort:** rewrite. The schema would be re-declared in Swift migrations, the DAOs rewritten, and the two schemas kept in step by hand.
- **Risk:** medium, from schema drift between two codebases.

**Option 3: SwiftData.** No path for FTS4 or `matchinfo`. Rejected.

**Storage location (a new iOS concern).** Apple says large re-downloadable content (its example is HD movies) should go under a `Library/<bundle-id>` folder with `isExcludedFromBackup = true`. The flag is "not a mechanism to guarantee those items never appear in a backup", and some file operations reset it ([Optimizing your app's data for iCloud backup](https://developer.apple.com/documentation/foundation/optimizing-your-app-s-data-for-icloud-backup)). ZIMs, models and `.gvec` files go there. User-imported books and music should **not** be excluded. This replaces Android's `Volumes`/SD-card logic: iOS has one volume.

### 2b.2 Downloads

**What Granger uses.**
- `FileDownloader` (OkHttp) writes a `.part` file with HTTP `Range` resume. It falls back across mirrors, keeps a streaming SHA-256 on every byte (re-hashing the part file on resume), and checks free space before every write with a 64 MB reserve. It sorts errors into permanent and retryable.
- `DownloadWorker` runs under WorkManager as a foreground worker with a notification and `NetworkType` constraints.
- Each download carries a `wifiOnly` flag (`DownloadRecord.wifiOnly`), and it can be changed for active downloads.

**iOS path: URLSession background sessions, with consequences.**
- **Survives suspend and system termination, not a user force-quit.** If the app is suspended, the download runs "in another process", and iOS calls `handleEventsForBackgroundURLSession` when it's done. If the system terminated the app, it relaunches it in the background, and the app must recreate the session with the same identifier ([Downloading files in the background](https://developer.apple.com/documentation/foundation/downloading-files-in-the-background)). If the user swipes the app away, "the background session persists but all of the tasks are cancelled", and the app isn't relaunched (Apple DTS, [forum 14855](https://developer.apple.com/forums/thread/14855), edited 2023-07-12).
- **No foreground-service equivalent.**
  - Tasks started while the app is in the background are treated as `isDiscretionary`.
  - A rate limiter delays new background tasks, and the delay grows with each relaunch. It resets when the user opens the app.
  - Apple advises one session with many tasks (same Apple article).
  - New in iOS 26: `BGContinuedProcessingTask` starts in the foreground, keeps running in the background, and shows progress in a system Live Activity that the user can cancel. It must report progress, and it can be terminated "abruptly" under resource pressure ([BGContinuedProcessingTask](https://developer.apple.com/documentation/backgroundtasks/bgcontinuedprocessingtask), iOS 26.0+). Apple doesn't document whether it suits network transfers. That's a trial item.
- **Resume is opaque.** A failed task may carry `NSURLSessionDownloadTaskResumeData` ([Pausing and resuming downloads](https://developer.apple.com/documentation/foundation/pausing-and-resuming-downloads)). Resuming needs all of these: an unchanged GET resource, an `ETag` or `Last-Modified` header, byte-range support, and a temp file the system hasn't purged ([cancel(byProducingResumeData:)](https://developer.apple.com/documentation/foundation/urlsessiondownloadtask/cancel(byproducingresumedata:))). There's no `.part` file the app owns, so our "hash what's there, then Range from that offset" logic doesn't carry over. Whether the Kiwix mirrors and our own hosts send `ETag`/`Last-Modified` needs checking with a `curl -I` in I1.
- **SHA-256 moves to after the download.** The finished file exists only "until your delegate method returns", so the app must move it first ([same Apple article](https://developer.apple.com/documentation/foundation/downloading-files-in-the-background)). The hash then runs over the whole multi-GB file, in the foreground or under a background task, not streamed as on Android. CommonCrypto's C API is callable from Kotlin/Native. CryptoKit isn't (see §2b.4).
- **No per-write free-space check.** The system writes the temp file. Granger can only check space before enqueueing, and then handle the failure.
- **Mirror fallback** means creating a new task after a failure, which is subject to the background rate limiter.
- **Cellular versus Wi-Fi can be enforced, per request.**
  - `URLRequest.allowsCellularAccess = false` "ensures that the request will never run over cellular" ([URLRequest.allowsCellularAccess](https://developer.apple.com/documentation/foundation/urlrequest/allowscellularaccess), iOS 8+).
  - The session-level equivalent is `URLSessionConfiguration.allowsCellularAccess` ([doc](https://developer.apple.com/documentation/foundation/urlsessionconfiguration/allowscellularaccess)).
  - Apple suggests basing the policy on `allowsConstrainedNetworkAccess` (Low Data Mode) and `allowsExpensiveNetworkAccess`, together with `waitsForConnectivity` ([doc](https://developer.apple.com/documentation/foundation/urlsessionconfiguration/allowsexpensivenetworkaccess), iOS 13+).
  - "Change Wi-Fi-only for active downloads" becomes: cancel the task with resume data, then re-enqueue it with the new flag.
- **Shareable logic:** the queue and state machine, `DownloadDatabase` (Room KMP), grouping, `VoiceUnpacker`, and the catalog→spec mapping. The transport is platform glue.
- **Licence:** system framework.
- **Effort: rewrite of the transport and worker.** About 300 lines of Android (`FileDownloader` + `DownloadWorker`) become a URLSession delegate (Swift or K/N) feeding the shared queue.
- **Risk: high**, the biggest in this section. Multi-GB ZIMs plus a user who swipes the app away mean a cancelled download, and resume depends on server headers. Proposal for I1:
  - Use a foreground-first design: a streaming data task with our own Range and hash logic while the app is open. This keeps `FileDownloader`'s semantics, and Ktor Darwin or NSURLSession can host it.
  - Hand off to a background download task when the app is backgrounded.
  - Trial `BGContinuedProcessingTask` for user-started downloads on iOS 26.

### 2b.3 Players

**What Granger uses.**
- `PlayerEngine` (1,557 lines) runs over Media3 ExoPlayer 1.11.1. `ReplayGainProcessor` is a gapless-safe Media3 `AudioProcessor`. There's also skip-silence, playback speed, `LoudnessEnhancer` for the audiobook boost, external EQ session broadcasts and `FocusPolicy` (audio focus).
- `PlaybackService` is a `MediaLibraryService` with a `MediaBrowseTree`. The same tree serves Android Auto (`automotive_app_desc.xml`), the notification, lock screen and Bluetooth.
- The read-aloud voice is a third player mode (`core/player/voice`).
- The Glance widget `PlayerWidget` comes in three sizes, with `actionRunCallback` play/pause and secondary buttons.
- Folder import accepts `mp3 m4a m4b aac flac wav ogg oga opus mka ape wma` (`FolderSource.kt:101`).

**AVFoundation + MediaPlayer against Media3.**
- **System controls.** `MPNowPlayingInfoCenter` puts track info on the Lock Screen, in Control Center, and on accessories "such as in a car" (iOS 5+, [doc](https://developer.apple.com/documentation/mediaplayer/mpnowplayinginfocenter)). With `MPRemoteCommandCenter` handlers it's the counterpart of the session ([Becoming a now playable app](https://developer.apple.com/documentation/mediaplayer/becoming-a-now-playable-app)).
- **Interruptions replace audio focus.** Through iOS 26 this is `AVAudioSession.interruptionNotification` with `.shouldResume`. iOS 27 deprecates it in favour of life-cycle notifications and `resumptionRecommendationNotification` ([Handling audio interruptions](https://developer.apple.com/documentation/avfaudio/handling-audio-interruptions)). `FocusPolicy`'s four outcomes map onto this.
- **Queue and gain.** `AVQueuePlayer` (iOS 4.1+) handles queues. Its doc says nothing about gapless ([doc](https://developer.apple.com/documentation/avfoundation/avqueueplayer)). ReplayGain can be a per-item volume via `AVMutableAudioMixInputParameters.setVolume(_:at:)` ([doc](https://developer.apple.com/documentation/avfoundation/avmutableaudiomixinputparameters/setvolume(_:at:))).
- **No built-in equivalent:** skip-silence, `LoudnessEnhancer`, and system-wide EQ apps (iOS has none). True gapless plus ReplayGain plus skip-silence points to an `AVAudioEngine` graph (player nodes + EQ/limiter), which is also where the Piper voice mixes (§2a).
- **Formats.** Apple doesn't document Ogg support for AVPlayer. WebKit added "Ogg container support for both Opus and Vorbis audio on … iOS 18.4" ([WebKit features in Safari 18.4](https://webkit.org/blog/16574/webkit-features-in-safari-18-4/)). Whether AVFoundation plays `.ogg`/`.opus` files outside WebKit is unverified. Also: Opus in MP4 works on iOS 17 per a [developer forum report](https://developer.apple.com/forums/thread/722399), and AVPlayer doesn't play WebM ([forum 775937](https://developer.apple.com/forums/thread/775937)). No source suggests `wma`, `ape` or `mka` play. These formats would need FFmpeg or libopus decoders, or the iPhone just drops them. LibriVox and archive.org audiobooks come as MP3, which is fine.
- **Shareable logic:** `MusicQueue`, `SleepTimer`, `SavedModesReader`, `MediaBrowseTree` (as data) and the hub's state model. The engine and service are platform code.
- **Licence:** system frameworks.
- **Effort: rewrite** of the engine and service (about 2,000 lines, Android-specific). The queue, modes and timer can be shared.
- **Risk: medium.** Achieving gapless playback with ReplayGain and skip-silence on `AVAudioEngine` is real work, and formats outside AVFoundation are a product decision.

**CarPlay against Android Auto.**
- Audio apps need the `com.apple.developer.carplay-audio` entitlement. It's requested at CarPlay Contact Us under the CarPlay Entitlement Addendum, and "Apple reviews each application using predefined criteria" ([Requesting CarPlay entitlements](https://developer.apple.com/documentation/carplay/requesting-carplay-entitlements)).
- UI comes from templates. `CPNowPlayingTemplate` (iOS 14+) "displays information from MPNowPlayingInfoCenter" and "is only available in apps with the audio entitlement" ([doc](https://developer.apple.com/documentation/carplay/cpnowplayingtemplate)). Our `MediaBrowseTree` maps onto `CPListTemplate`/`CPTabBarTemplate`.
- CarPlay audio apps also work on Apple Home "without requiring you to make any modifications" ([developer.apple.com/carplay](https://developer.apple.com/carplay/)).
- **Effort:** wrap (a template scene over the shared browse tree), small once the player exists.
- **Risk: medium**, because the entitlement is gated by Apple's review and the approval timing is unknown.
- **Suggestion: later.** Request the entitlement during I1 so it's in hand, and build CarPlay after the player is solid.

**WidgetKit + App Intents against Glance.**
- Interactive widget buttons need iOS 17 (the doc's sample is gated `#available(iOS 17.0, *)`). After an intent's `perform()` returns, the system reloads the widget's timeline, and button interactions "always guarantee a timeline reload" ([Adding interactivity to widgets](https://developer.apple.com/documentation/widgetkit/adding-interactivity-to-widgets-and-live-activities)).
- Play/pause uses an `AudioPlaybackIntent`. It runs in the app's process, so it goes in the app target (same page; [AudioPlaybackIntent](https://developer.apple.com/documentation/appintents/audioplaybackintent), iOS 17+).
- The three sizes map onto widget families. The Lock Screen and Control Center already show Now Playing for free.
- **Effort:** rewrite of the UI (SwiftUI only; Compose Multiplatform can't draw widgets). The widget model (`WidgetUpdater`'s model) can be shared.
- **Risk: low.**
- **Suggestion: later.** iOS's Now Playing controls cover most of the widget's job at launch.

### 2b.4 Smaller swaps

**OkHttp → Ktor (Darwin engine) or URLSession.**
- **What Granger uses:** OkHttp 5.5.0 in `CatalogRepository`, `FileDownloader`, `core/archive` (`ArchiveOrgClient`, `LibriVoxClient`) and `app/home` (`DayPage`, `ArticleThumbnails`).
- Ktor 3.6.0's Darwin engine "uses NSURLSession under the hood", with `configureRequest` and `configureSession` hooks. Its docs say nothing about background sessions ([Ktor client engines](https://ktor.io/docs/client-engines.html), 2026-08-28). Licence Apache-2.0 ([repo](https://github.com/ktorio/ktor)).
- **Effort:** drop-in to wrap for the small JSON and HTML clients (catalog, archive.org, LibriVox, home feeds), all of which could share code through Ktor. Big downloads stay on native URLSession (§2b.2).
- **Risk: low.**

**BouncyCastle Ed25519 → CryptoKit `Curve25519.Signing`.**
- **Our format**, from `core/catalog/.../CatalogSignature.kt` and `tools/catalog-gen/catalog_gen.py`:
  - pure Ed25519 (RFC 8032, no prehash) over the raw catalog bytes;
  - a 32-byte raw public key;
  - a `.sig` file holding the base64 of a 64-byte signature plus a newline.
  - The signer is Python `cryptography` 50.0.2 `Ed25519PrivateKey.sign`, and the verifier is BC `Ed25519Signer` (pure mode).
- **CryptoKit:**
  - `Curve25519.Signing` is "a mechanism used to create or verify a cryptographic signature using Ed25519" ([doc](https://developer.apple.com/documentation/cryptokit/curve25519/signing)).
  - `PublicKey.init(rawRepresentation:)` and `isValidSignature(_:for:)` "Verifies an EdDSA signature over Curve25519", on iOS 13+ ([PublicKey](https://developer.apple.com/documentation/cryptokit/curve25519/signing/publickey)).
  - So the iOS check is: base64-decode the trimmed `.sig`, check it's 64 bytes, build the key from the 32 raw bytes in `public-key.txt`, then call `isValidSignature(sig, for: catalogBytes)`. The format verifies unchanged, and nothing in the signer or catalog changes. Task 4 can confirm this with the existing test vector.
- **Catch:** CryptoKit is a pure-Swift API. Kotlin can only call Swift that is exported with `@objc`: "Pure Swift modules are not yet supported" ([Kotlin/Native ObjC interop](https://kotlinlang.org/docs/native-objc-interop.html), 2026-08-12). In a KMP core this becomes an `expect fun verifyEd25519` with a 10-line `@objc` Swift shim, or a KMP crypto library, not checked here.
- **Effort:** drop-in in Swift, wrap from KMP.
- **Risk: low.**

**jsoup → Ksoup or a SwiftSoup boundary.**
- **What Granger uses:** jsoup 1.21.2 in the cores slated for sharing (`:core:text`'s `HtmlPassages`, `ArticleText`, `XhtmlChunks`), plus `EpubInspector`, `DrmCheck`, `TextToEpub`, `DensePassageFinder` and the home feeds.
- **Ksoup** 0.2.6 (2026-02-19), MIT, is "a Kotlin Multiplatform port of jsoup" whose code is in line with jsoup 1.22.1. Its badges list iOS, and "Most of the APIs work without any changes" ([repo](https://github.com/fleeksoft/ksoup), [release](https://api.github.com/repos/fleeksoft/ksoup/releases/latest)).
- **SwiftSoup** 2.14.3 (2026-10-16), MIT ([release](https://api.github.com/repos/scinfu/SwiftSoup/releases/latest)), suits only architecture C.
- **Effort:** drop-in. It's a package swap, and ArticleText's output must be byte-identical: the R3 passage addresses and read-aloud block ids depend on it, so run the `:core:text` golden tests.
- **Risk: low to medium.** Ksoup is pre-1.0, with one main maintainer.

**pdfbox-android page text → PDFKit.**
- **What Granger uses:** `PdfText` uses `PDFTextStripper` with `sortByPosition = true` one page at a time, then strips running headers and footers by comparing lines with nearby pages (`core/books/.../PdfText.kt:88`).
- `PDFPage.string` "Returns an NSString object representing the text on the page" (iOS 11+). It says nothing about reading order or position sorting ([doc](https://developer.apple.com/documentation/pdfkit/pdfpage/string)).
- **Effort:** wrap. Only the page-text source changes, and the header/footer logic is shared.
- **Risk: medium.** Column and line order may differ from PdfBox's `sortByPosition`, which changes chunk text and the book index for PDFs. Compare on the P2 test PDFs.
- **Licence:** system framework (pdfbox-android is Apache-2.0 and drops out).

**commonmark → a KMP Markdown parser or swift-markdown.**
- **What Granger uses:** only `TextToEpub` (`core/books`). It parses `.md`, walks `Heading` and `Image` nodes, and renders HTML.
- **JetBrains `markdown`** 0.7.18 (Maven `markdown-iosarm64`, 2026-10-15), Apache-2.0. It defaults to CommonMark, includes GFM, and has an `HtmlGenerator` ([repo](https://github.com/JetBrains/markdown), [Maven metadata](https://repo1.maven.org/maven2/org/jetbrains/markdown-iosarm64/maven-metadata.xml)). It fits a shared `TextToEpub`.
- **swift-markdown** (Apache-2.0, cmark-gfm based, tags are Swift dev snapshots as of 2026-10-17) suits C only, and its README doesn't mention an HTML formatter ([repo](https://github.com/swiftlang/swift-markdown)).
- **Effort:** wrap (the AST visitor is rewritten against JetBrains' tree).
- **Risk: low.**

**WebView → `WKWebView` with the same page scripts.**
- **The bridge we have:**
  1. `addJavascriptInterface(GrangerBridge)` with two JS→app calls: `toggleChrome()` and `onUserScroll()` (`ReaderPageScript.kt:38`).
  2. App→JS calls through `evaluateJavascript`: `ReaderPageScript.SOURCE` and `ReadAloudScript.SOURCE` injected in `onPageFinished`; `__grangerRead.firstVisible/selectionBlock/mark/clear/cite/clearCite`, returning JSON; `scrollTo`; `window.getSelection()`; and the e-book `FirstVisibleText.JS`.
  3. `shouldInterceptRequest`, which serves every ZIM URL (`https://zim.granger.invalid/<pack>/<path>`) and `assets/reader/*` from libkiwix, patching HTML on the way.
  4. A custom selection menu (Research, Read aloud, Search) through `ActionMode.Callback2`.
  5. `blockNetworkLoads = true`, plus algorithmic darkening.
- **What WebKit covers:**
  - (1) `window.webkit.messageHandlers.<name>.postMessage(...)` with `WKScriptMessageHandler`, or `WKScriptMessageHandlerWithReply` for answers ([doc](https://developer.apple.com/documentation/webkit/wkscriptmessagehandlerwithreply)). The page script's `GrangerBridge.toggleChrome()` call needs a one-line shim, or a small edit to the script.
  - (2) `WKUserContentController.addUserScript` injects the scripts (iOS 8+, [doc](https://developer.apple.com/documentation/webkit/wkusercontentcontroller/adduserscript(_:))). `evaluateJavaScript` or `callAsyncJavaScript` (iOS 14+) returns values as Foundation objects rather than double-encoded JSON strings ([doc](https://developer.apple.com/documentation/webkit/wkwebview/callasyncjavascript(_:arguments:in:in:completionhandler:))). The highlights use the CSS Custom Highlight API, in WebKit since Safari 17.2 ([WebKit 17.2](https://webkit.org/blog/14787/webkit-features-in-safari-17-2/)), and the script already falls back to a class without it.
  - (3) **Changes on iOS.** `setURLSchemeHandler` can't take `https`: "It is a programmer error to register a handler for a scheme WebKit already handles, such as https" ([doc](https://developer.apple.com/documentation/webkit/wkwebviewconfiguration/seturlschemehandler(_:forurlscheme:)), iOS 11+). `ZimUrls` needs a custom scheme on iOS. Kiwix's own app does exactly this: `KiwixURLSchemeHandler: WKURLSchemeHandler` with `ZIMScheme = "zim"` (kiwix-apple `Model/Utilities/WebKitHandler.swift`, main f410135, 2026-10-23). The URL format is shared code, so `ZimUrls` takes the scheme as a parameter. Saved positions and citations that store full URLs would need normalising.
  - (4) No official API for adding items to WKWebView's text-selection menu. The working pattern is subclassing `WKWebView` and overriding `buildMenu(with:)`, and an iOS 18.2 beta regression broke it for view-controller overrides ([forum 770127](https://developer.apple.com/forums/thread/770127), [WebKit bug 244149](https://bugs.webkit.org/show_bug.cgi?id=244149)). `webView(_:willPresentEditMenuWithAnimator:)` (iOS 16.4+) only observes the menu ([doc](https://developer.apple.com/documentation/webkit/wkuidelegate/webview(_:willpresenteditmenuwithanimator:))).
  - (5) `WKContentRuleList` rules can block http(s) loads (iOS 11+, [doc](https://developer.apple.com/documentation/webkit/wkcontentrulelist)). Night mode needs the CSS-only route, since WebKit has no algorithmic darkening.
- **Effort:** wrap. The JS files are shared unchanged, apart from the bridge call and the URL scheme.
- **Risk: medium.** The selection menu is fragile, and the URL-scheme change touches stored data.

### 2b.5 Speech input (A1, not yet built on Android)

- **`SpeechAnalyzer` + `SpeechTranscriber` (iOS 26.0+).**
  - Apple's WWDC25 session: "transcription is entirely on device but the models need to be fetched" through `AssetInventory`. The model "is retained in system storage and does not increase the download or storage size of your application, nor does it increase the run-time memory size", which matters beside E2B ([WWDC25 session 277](https://developer.apple.com/videos/play/wwdc2025/277/)).
  - Device support is "certain hardware requirements", unlisted. Check `SpeechTranscriber.isAvailable` and `supportedLocales`/`installedLocales` at run time ([SpeechTranscriber](https://developer.apple.com/documentation/speech/speechtranscriber), [SpeechAnalyzer](https://developer.apple.com/documentation/speech/speechanalyzer)).
  - `DictationTranscriber` is the fallback, "compatible with older devices". Per the WWDC session it covers the same languages and devices as on-device `SFSpeechRecognizer`, without the user enabling Siri or dictation.
- **Contextual strings.** `AnalysisContext.contextualStrings` is "Words or phrases, grouped by tag, that should be recognized even if they are not in the system vocabulary" ([doc](https://developer.apple.com/documentation/speech/analysiscontext)). The doc doesn't say which transcriber honours it, so that's a trial item. It's the counterpart of `EXTRA_BIASING_STRINGS` (pack titles, entity names).
- **`SFSpeechRecognizer` (iOS 13+ for on-device).**
  - `requiresOnDeviceRecognition` "prevent[s] … sending audio over the network", but "only honors this setting if supportsOnDeviceRecognition is also true", and on-device "won't be as accurate" ([doc](https://developer.apple.com/documentation/speech/sfspeechrecognitionrequest/requiresondevicerecognition)).
  - `contextualStrings` takes up to 100 short phrases ([doc](https://developer.apple.com/documentation/speech/sfspeechrecognitionrequest/contextualstrings), iOS 10+).
  - Apple publishes no per-locale list for on-device support. The app must check `supportsOnDeviceRecognition` per locale and hide the mic when it's false, which keeps Neal's rule of the phone's own recognizer only, never the network.
- **Licence:** system. **Effort:** wrap, behind the same `SpeechInput` seam A1 will define. **Risk:** low to medium, since device and language coverage is only known at run time.
- **Suggestion: later (port with A1).** Build it when A1 is built: `SpeechTranscriber` on iOS 26 devices, with a `DictationTranscriber` fallback. Keep `SFSpeechRecognizer` only if the minimum iOS stays below 26.

### 2b.6 Phone media and sharing

- **What Granger uses:**
  - `PhoneScanner` and `MediaStoreSource` read MediaStore.
  - `FolderSource` reads SAF trees (`DocumentsContract`).
  - TagLib supplies the tags.
  - `MediaDocumentsProvider`, a `DocumentsProvider` that's off by default behind a Settings switch, shows downloaded media to other apps read-only ("Show in other apps").
- **Folders (replaces SAF trees).** `UIDocumentPickerViewController(forOpeningContentTypes: [.folder])` (iOS 13+) returns a security-scoped URL covering the folder, its subfolders and later additions. Access goes through `startAccessingSecurityScopedResource` and `NSFileCoordinator`, and persists via `bookmarkData(.minimalBookmark)`. Users can revoke access in Settings > Privacy > Files and Folders, and bookmarks can go stale ([Providing access to directories](https://developer.apple.com/documentation/uikit/providing-access-to-directories)). This is the closest match to `FolderSource` and works for iCloud Drive and third-party providers.
  - **Effort:** wrap (the scanner logic is shared; enumeration is platform). **Risk:** medium (stale bookmarks, files not downloaded locally from iCloud).
  - **Suggestion: port with I1**, folder import only.
- **Music library (replaces MediaStore audio).** `MPMediaItem.assetURL` can feed AVFoundation but is optional (iOS 8+, [doc](https://developer.apple.com/documentation/mediaplayer/mpmediaitem/asseturl)). It comes back nil for Apple Music (DRM) items even when downloaded. `hasProtectedAsset` exists but has been reported unreliable ([forum 7791](https://developer.apple.com/forums/thread/7791), [hasProtectedAsset](https://developer.apple.com/documentation/mediaplayer/mpmediaitem/hasprotectedasset)). Only the user's own purchased or ripped tracks would play.
  - **Effort:** wrap. **Risk:** medium (it needs an `NSAppleMusicUsageDescription` prompt, and the behaviour is anecdotal).
  - **Suggestion: later.**
- **"Show in other apps" (replaces `MediaDocumentsProvider`).**
  - `UIFileSharingEnabled = YES` lets the app share files placed in its `Documents` folder ([doc](https://developer.apple.com/documentation/bundleresources/information-property-list/uifilesharingenabled)).
  - `LSSupportsOpeningDocumentsInPlace` lets the app open originals from file providers ([doc](https://developer.apple.com/documentation/bundleresources/information-property-list/lssupportsopeningdocumentsinplace)).
  - A developer-forum post (not Apple docs) lists both keys being true, plus a non-empty Documents folder, as the conditions for an "On My iPhone > Granger" folder in Files ([forum 765329](https://developer.apple.com/forums/thread/765329)).
  - Two differences from Android: it isn't a switch (it's an Info.plist constant), and anything in Documents is visible *and deletable* in Files. So Granger would put only user-facing media (audiobooks, music downloads, imported books) in Documents, keep packs and models in `Library/` excluded from backup (§2b.1), and handle files vanishing underneath it.
  - **Effort:** drop-in (two plist keys and a folder choice). **Risk:** low.
  - **Suggestion: later.** It's cheap, but the "user can delete it" case needs design, so it isn't for I1.

### 2b.7 Summary

| Item | iOS path | Licence | Effort | Risk | Port call |
|---|---|---|---|---|---|
| Room / SQLite | Room KMP 2.8.5 (or 3.0.3) + `BundledSQLiteDriver` (SQLite 3.53.4, FTS4 + FTS5 on) | Apache-2.0 | wrap (migrations, `BookIndex` raw queries) | low–med (Android switches SQLite) | I1 |
| (alt, C only) | GRDB 7.11.1 on system SQLite (FTS4) | MIT | rewrite | med (schema drift) | — |
| FTS4 contentless + matchinfo BM25 | same SQL on the bundled driver | — | drop-in | low (check `FTS3_PARENTHESIS` parity) | I1 |
| Storage location | `Library/<id>/` with `isExcludedFromBackup` for packs and models | system | wrap | low | I1 |
| Downloads | URLSession background download tasks + foreground streaming task; `BGContinuedProcessingTask` trial (iOS 26) | system | rewrite of transport and worker | **high** (force-quit cancels; resume needs ETag/Last-Modified; hash after, not streamed) | I1 |
| Wi-Fi only | `URLRequest.allowsCellularAccess=false`, or `allowsExpensive/ConstrainedNetworkAccess` | system | wrap | low | I1 |
| Player engine | AVFoundation / `AVAudioEngine`, `AVQueuePlayer`, audio-mix volume (ReplayGain) | system | rewrite (queue, modes, timer shared) | med (gapless + RG + skip-silence; no `wma`/`ape`/`mka`; Ogg unverified outside WebKit) | I1 |
| System controls | `MPNowPlayingInfoCenter` + `MPRemoteCommandCenter`; interruptions replace focus | system | wrap | low | I1 |
| CarPlay | `com.apple.developer.carplay-audio`, `CPNowPlayingTemplate` + list templates over the browse tree | system | wrap | med (Apple-reviewed entitlement) | **later** (request it in I1) |
| Widget | WidgetKit + `AudioPlaybackIntent` (iOS 17+) | system | rewrite (SwiftUI only) | low | **later** |
| OkHttp | Ktor 3.6.0 Darwin for small clients | Apache-2.0 | drop-in/wrap | low | I1 |
| Ed25519 catalog check | CryptoKit `Curve25519.Signing.PublicKey(rawRepresentation:)`.`isValidSignature`; our raw-key + base64-sig format verifies unchanged | system | drop-in (Swift) / wrap (`@objc` shim from KMP) | low | I1 |
| jsoup | Ksoup 0.2.6 (KMP jsoup port) / SwiftSoup 2.14.3 for C | MIT | drop-in | low–med (pre-1.0; golden-test `ArticleText`) | I1 |
| pdfbox page text | PDFKit `PDFPage.string` | system | wrap | med (reading order against `sortByPosition`) | I1 |
| commonmark | JetBrains `markdown` 0.7.18 (iOS artefacts) / swift-markdown for C | Apache-2.0 | wrap | low | I1 |
| WebView + bridge | `WKWebView`: user scripts, `messageHandlers`, `evaluateJavaScript`/`callAsyncJavaScript`, `WKURLSchemeHandler` on a custom `zim:` scheme, `WKContentRuleList`; `buildMenu` for selection actions | system | wrap (JS shared) | med (selection menu fragile; https→custom scheme touches stored URLs) | I1 |
| Speech input (A1) | `SpeechAnalyzer`/`SpeechTranscriber` (iOS 26) → `DictationTranscriber` → `SFSpeechRecognizer` on-device; `contextualStrings` | system | wrap | low–med (coverage known only at run time) | **later** (with A1) |
| Phone folders | `UIDocumentPickerViewController([.folder])` + security-scoped bookmarks | system | wrap | med | **I1** (folder import) |
| Music library | `MPMediaLibrary`, `assetURL` (nil for DRM) | system | wrap | med | **later** |
| "Show in other apps" | `UIFileSharingEnabled` + `LSSupportsOpeningDocumentsInPlace`, Documents for user media only | system | drop-in | low | **later** |

**Biggest risks:**
1. Downloads: no foreground service, a user force-quit cancels transfers, resume depends on server headers, and the SHA-256 runs over the whole file afterwards.
2. The player engine: Media3's AudioProcessor-based gapless ReplayGain and skip-silence have no AVPlayer equivalent, and some formats Granger accepts won't play.
3. The WebView: `https://zim.granger.invalid` can't be intercepted on iOS (the scheme must change), and the custom selection menu has no official API.
4. Room on the bundled driver changes Android's SQLite too, if the core is shared. Test the FTS4 book index on it first.


# I0 parts: §3 The minimum iPhone, §6 The Apple side

Desk research, 2026-10-28. Every web source below was read on **2026-10-28** unless a different date is given. Apple
documentation was read through its `developer.apple.com/tutorials/data/documentation/....md` mirror where the HTML page
didn't render. "Inference" marks a conclusion of mine that no source states.

## 3. The minimum iPhone

### 3.1 What iOS is current, and which iPhones run it

- **iOS 27 shipped on 2026-09-14** (announced 2026-06-08, public beta 2026-07-13), per
  [EveryMac, iOS 27 supported devices](https://everymac.com/systems/apple/iphone/iphone-faq/iphone-ios-27-supported-devices-features.html).
- **iOS 27 runs on exactly the iPhones iOS 26 runs on:** "Apple says that iOS 27 is compatible with all of the same iPhone
  models" ([9to5Mac, 2026-06-08](https://9to5mac.com/2026/06/08/ios-27-here-are-all-the-compatible-iphone-models/)).
  That's the iPhone 11 family, the iPhone SE (2nd generation) and everything newer. EveryMac's list adds the iPhone 17e
  and the iPhone 18 Pro / Pro Max / Duo.
- **iOS 26 dropped the iPhone XS, XS Max and XR.** 74% of iPhones introduced in the last four years ran iOS 26 by
  2026-02-12, and 66% of all iPhones did. Those are Apple's figures, reported by
  [9to5Mac, 2026-02-13](https://9to5mac.com/2026/02/13/apple-announces-ios-26-usage-numbers-heres-how-they-compare/)
  and [Daring Fireball](https://daringfireball.net/2026/02/apple_releases_ios_26_adoption_rates).

### 3.2 RAM per supported iPhone

Apple doesn't publish iPhone RAM. The figures come from two places:

- **Xcode's device data,** as read by MacRumors. Apple's own tool lists each device's memory, and MacRumors notes it
  "has accurately revealed RAM for many past iPhone generations."
- **Teardowns and supply-chain reports,** collected by The iPhone Wiki and Mactracker. 9to5Mac's table is built from
  these.

| iPhone (runs iOS 26/27) | RAM | Source |
|---|---|---|
| SE (2nd gen, 2020) | 3 GB | [9to5Mac RAM list, 2023-11-18](https://9to5mac.com/2023/11/18/iphone-ram-list/) (from The iPhone Wiki / Mactracker) |
| 11, 11 Pro, 11 Pro Max (2019) | 4 GB | same |
| 12, 12 mini | 4 GB | same |
| 12 Pro, 12 Pro Max | 6 GB | same |
| 13, 13 mini, SE (3rd gen) | 4 GB | same |
| 13 Pro, 13 Pro Max | 6 GB | same |
| 14, 14 Plus, 14 Pro, 14 Pro Max | 6 GB | same |
| 15, 15 Plus | 6 GB | same |
| **15 Pro, 15 Pro Max (2023)** | **8 GB** | same |
| 16, 16 Plus, 16 Pro, 16 Pro Max | 8 GB | [MacRumors, 2025-09-09](https://www.macrumors.com/2025/09/09/iphone-17-pro-iphone-air-ram-amounts/) (Xcode 26) |
| 16e | 8 GB | [MacRumors, 2025-02-19](https://macrumors.com/2025/02/19/iphone-16e-has-8gb-of-ram) (Xcode) |
| 17 | 8 GB | MacRumors 2025-09-09 (Xcode 26) |
| 17e | 8 GB | [MacRumors, 2026-03-05](https://www.macrumors.com/2026/03/05/iphone-17e-has-8gb-of-ram/) (Xcode) |
| Air, 17 Pro, 17 Pro Max | 12 GB | MacRumors 2025-09-09 (Xcode 26) |
| 18 Pro, 18 Pro Max | 12 GB | [MacRumors, 2026-09-09](https://macrumors.com/2026/09/09/iphone-18-pro-and-pro-max-ram/) (Xcode 27); the Duo isn't in Xcode yet |

Some sources disagree:

- [iphonescompare.com](https://iphonescompare.com/iphone-ram-comparison) lists the Air at 8 GB. MacRumors's Xcode
  reading says 12 GB, and the Xcode reading wins.
- [A zenn.dev article (2026-06-18)](https://zenn.dev/mtfum/articles/ios_memory_entitlements?locale=en) calls the 17 Pro
  "8GB". Xcode says 12 GB.

Neal's first test phone, an iPhone 16 Plus or Pro Max, has **8 GB**, the same as the Pixel 6.

### 3.3 Per-app memory ceilings people have measured

Apple publishes no ceilings. The entitlement page says only that the increased limit "is only available on some device
models", that apps should call `os_proc_available_memory` to find what they have, and that the app "still behaves
correctly if additional memory isn't available". The entitlement exists from iOS 15
([entitlement doc](https://developer.apple.com/tutorials/data/documentation/bundleresources/entitlements/com.apple.developer.kernel.increased-memory-limit.md)).
An Apple DTS engineer adds three things
([forum 770868, Dec 2024](https://developer.apple.com/forums/thread/770868)):

- There are three overlapping entitlements: `increased-memory-limit`, `increased-debugging-memory-limit` (never in
  release builds) and `extended-virtual-addressing`.
- When the entitlement seems to do nothing in an App Store build, the first suspect is a signing problem.
- Test devices overstate the real headroom, because a real phone runs other apps.

| Device (RAM) | iOS | No entitlement | With `increased-memory-limit` | Source and how it was measured |
|---|---|---|---|---|
| iPhone 15 Pro Max (8 GB) | 27.0 | **≈ 3.3 GB** | **≈ 6 GB** | [SideStore issue #1616](https://github.com/SideStore/SideStore/issues/1616), opened 2026-09-24; `os_proc_available_memory()`. A second commenter (2026-10-23, device not named) saw ≈ 3.3 GiB → ≈ 8 GiB |
| iPhone 16 Pro Max (8 GB) | not given | — | **≈ 6144 MB** | [Apple forum 834805, June 2026](https://developer.apple.com/forums/thread/834805); `os_proc_available_memory()` + `phys_footprint` |
| iPhone 17 Pro Max (12 GB) | not given | — | **≈ 6144 MB** (EVA adds nothing; the debugging entitlement gives 6656 MB) | same thread; no Apple reply; FB23183521 |
| iPhone 17 Pro (stated as 8 GB, see above) | 26.x | Gemma 4 E4B (3.65 GB file) **crashes** | loads, peak 4,651 MB (EVA only: 4,775 MB) | [zenn.dev, 2026-06-18](https://zenn.dev/mtfum/articles/ios_memory_entitlements?locale=en) |
| iPhone 12 / 12 mini (4 GB) | 14/15 era | jetsam "ActiveHard **2098 MB**" | not tested | [Apple forum 688973, Aug 2021](https://developer.apple.com/forums/thread/688973) |
| 3 GB devices | 26.4 | ≈ 1.5 GB usable (≈ 0.8 GB with JavaScriptCore linked, a 26.4 regression) | — | [Apple forum 826949, May–Jul 2026](https://developer.apple.com/forums/thread/826949) |
| 8 GB iPad | iPadOS 26.0–26.3 vs 26.4–26.5 | ≈ 6 GB → ≈ 3 GB (address-space regression, fixed from 26.6 beta 5) | — | [Apple forum 826325, May–Sep 2026](https://developer.apple.com/forums/thread/826325) |

I found no measurement for a 6 GB iPhone (12 Pro, 13 Pro, 14, 15). A Stack Overflow table, seen only through a 2023
offline mirror because stackoverflow.com couldn't be fetched, gives the iPhone 12 Pro at "3054/5703" (iOS 16.1) and the
iPhone 11 at "2068/3844" (iOS 13). That's about 54% of RAM, but the mirror doesn't label the columns
([mirror](https://kaweah.freedombox.rocks/kiwix/stackoverflow.com_en_all_2023-11/a/15200855)). Treat it as a lead,
not a number.

**Reading:**

- **Without the entitlement,** an 8 GB iPhone gives an app about 3.3 GB.
- **With it,** an 8 GB iPhone gives about 6 GB, and so does a 12 GB iPhone: iPhones cap near 6 GB. A free Apple ID
  (Personal Team) can carry the entitlement through Xcode (SideStore #1616), so Task 7's harness can measure with it
  before the paid account is live.
- **The 26.4 regressions show the ceiling moves between point releases.** So Granger's tier check should read
  `os_proc_available_memory()` at launch, not trust a model table.

### 3.4 The Pixel 6 budget set against those ceilings

The Pixel 6 numbers come from this worktree:

| Component | Pixel 6 figure | Where |
|---|---|---|
| E2B on the GPU, loaded | 1,717 MB PSS after load, 1,210 MB of it graphics | `docs/superpowers/plans/2026-10-23-plan-r4-answer-model.md` (smoke test, 2026-10-24) |
| E2B GPU, R4 speed screen | 1.6 GB PSS / 1.2 GB GPU after load | R4 plan, Task 3 table |
| E2B answering, peak | 2.4 GB (E4B: 3.5 GB) | `docs/superpowers/spikes/2026-10-research-spike-results.md` |
| E2B + EmbeddingGemma co-resident, answering | 2,193–2,198 MB peak RSS on GPU (2,405–2,489 on CPU) | `docs/superpowers/spikes/2026-10-model-spike-results.md`. That doc says "RSS probably does not include GPU allocations … treat the GPU peak figures as lower bounds" |
| Piper voice speaking | +420–450 MB (ONNX Runtime arena); process peak 770–790 MB alone | `docs/superpowers/spikes/2026-10-voice-spike-results.md` |
| E2B resident + Piper speaking | 2.3 GB peak RSS (a lower bound, as above) | voice results; V0 plan |
| App with no engines (reader, libkiwix, UI) | ≈ 310–350 MB | voice results ("the activity alone") |
| Swap to E4B | 4.4–4.7 GB | model results |

The Android tiers, from `core/ai/src/main/kotlin/app/granger/core/ai/DeviceTier.kt`:

- **`ASK`** when `totalMem ≥ 4.5 GiB`, which means phones marketed at 6 GB and up.
- **`LIBRARY_ONLY`** otherwise.

**The iPhone Ask budget (inference).** On iOS, Metal buffers count toward the app's `phys_footprint`, since memory is
unified. Task 7 confirms this on the device. The fair comparison is the PSS-with-graphics figure plus the RSS of the
rest:

| Ask budget part | Size |
|---|---|
| E2B with its GPU share | ≈ 1.7 GB |
| Answer-time growth | ≈ +0.7 GB, to the 2.4 GB peak |
| EmbeddingGemma | ≈ +0.2 GB |
| Piper while speaking | ≈ +0.45 GB |
| App and reader | ≈ +0.35 GB |
| **Total** | **≈ 3.0–3.5 GB** |

Set against the ceilings:

- **8 GB iPhone, no entitlement (≈ 3.3 GB):** doesn't fit with any margin. It's the Kotlin/Native or Swift runtime
  plus the reader that tips it over.
- **8 GB iPhone with the entitlement (≈ 6 GB):** fits with about 2.5 GB to spare. The E4B swap (4.4–4.7 GB) would also
  fit, though R4 already ruled E4B out for speed.
- **6 GB iPhone, no entitlement (≈ 3 GB if the 54% lead holds):** doesn't fit. With the entitlement: unknown. Nobody
  has published a number.
- **4 GB iPhone (≈ 2.1 GB):** E2B alone fills it. No Ask.

**The Read budget:** the reader, libkiwix keyword search and a Piper voice come to ≈ 0.8–1.0 GB on the Pixel 6.

- **4 GB iPhones (≈ 2.1 GB):** fits easily.
- **3 GB SE 2nd gen (≈ 1.5 GB, and less on 26.4 when JavaScriptCore is linked):** fits, tightly while the voice
  speaks. The reader's WKWebView renders in a separate WebContent process. If that process's memory doesn't count
  against the app's limit, the reader is cheaper still (inference, to check in Task 7).

### 3.5 Proposed tiers (mirroring `DeviceTier`)

**ASK tier (runs Ask): 8 GB iPhones with the `increased-memory-limit` entitlement.**

- **The oldest is the iPhone 15 Pro / 15 Pro Max (2023).**
- **Every later model qualifies:** all iPhone 16s including the 16e, the 17 and 17e, the Air, the 17 Pro, and the 18 Pro.
- **The test phone fits:** Neal's iPhone 16 Plus or Pro Max is in this tier and is a fair stand-in for its floor.
- **Users already know this line.** It's the same 8 GB line Apple draws for Apple Intelligence.
- **The gate:** `ProcessInfo.physicalMemory ≥ ~7 GiB` *and* `os_proc_available_memory() ≥ ~4.5 GB` at launch. It's the
  iOS twin of `DeviceTiers.forTotalRam`. The second check catches a missing entitlement and iOS regressions like
  26.4's.
- **iOS is stricter than Android here.** Android gives Ask to 6 GB phones, but the 6 GB iPhones (12 Pro to 15/15 Plus)
  stay READ until Task 7 or a borrowed 6 GB iPhone shows ≥ 4.5 GB available with the entitlement.

**READ tier (everything but Ask): every iPhone the minimum iOS allows.**

- **With iOS 26 as the minimum, the oldest is the iPhone 11 (4 GB) and the iPhone SE (2nd gen, 3 GB).**
- **The App Store can't exclude devices by RAM.** The minimum iOS is therefore the real READ floor, and the 3 GB SE
  must work: free the voice engine after a pause, as the V0 results already suggest.

**Minimum iOS: propose iOS 26.0.** Dependency research (Task 2) may revise this.

- **Costs nothing in reach.** It supports the same iPhones as iOS 27 and loses only the XS, XS Max and XR against
  iOS 18.
- **iOS-26-only APIs Granger would use:**
  - `SpeechAnalyzer` for A1 ([doc](https://developer.apple.com/tutorials/data/documentation/speech/speechanalyzer.md),
    iOS 26.0+).
  - `BGContinuedProcessingTask` for long user-started jobs like unpacking or indexing a pack
    ([doc](https://developer.apple.com/tutorials/data/documentation/backgroundtasks/bgcontinuedprocessingtask.md),
    iOS 26.0+).
  - `AssetPackManager` for Apple-hosted asset packs
    ([doc](https://developer.apple.com/tutorials/data/documentation/backgroundassets/assetpackmanager.md), iOS 26.0+).
- **Fallback:** Kiwix's own app requires iOS 17.6
  ([App Store listing](https://apps.apple.com/us/app/kiwix/id997079563)). If a dependency forces it, iOS 17.6 or 18
  is possible, with `SFSpeechRecognizer` in place of `SpeechAnalyzer`.

**What Task 7 must measure on the iPhone 16:**

- `os_proc_available_memory()` with and without the entitlement.
- `phys_footprint` after E2B loads on Metal, while answering, and with EmbeddingGemma and Piper added.
- Whether WKWebView's memory counts against the app.

## 6. The Apple side

### 6.1 Apple Developer Program

- **Cost:** US$99 per membership year, in local currency where available. The Enterprise program is US$299 and not
  relevant. Fee waivers exist only for nonprofits, accredited schools and government
  ([Enroll](https://developer.apple.com/programs/enroll/);
  [Program enrollment help](https://developer.apple.com/help/account/membership/program-enrollment/)).
- **Both kinds of enrolment need:** an Apple Account with two-factor authentication, and the legal age of majority.
- **Individual:**
  - "your personal legal name will be listed as the seller on the App Store."
  - Covers sole proprietors and one-person businesses.
  - Use your legal first and last name. An alias, nickname or company name delays approval.
  - Apple confirms name, email, phone and address, and P.O. boxes aren't accepted.
- **Organisation:**
  - "the legal entity name will be listed as the seller" (e.g. "Seller: ABC Company, Inc.").
  - The organisation must be a legal entity that can sign contracts. "We don't accept DBAs, fictitious businesses, trade
    names, or branches."
  - It needs a **D-U-N-S Number**, which is "free in most jurisdictions".
  - It needs a work email on the organisation's domain and a public, working website. Social pages and placeholder
    sites are refused.
  - The enrolling person needs binding authority.
- **Switching later:** an individual account converts to an organisation only by contacting Apple. There's no
  self-service switch (same help page).
- **Free Apple ID (Personal Team), for comparison:**
  - 7-day provisioning profiles, up to 3 devices, 3 apps per device and 10 App IDs.
  - No TestFlight and no App Store distribution
    ([Compare memberships](https://developer.apple.com/support/compare-memberships/)).
- **Commission:** with the App Store Small Business Program, Supporter purchases pay 15% instead of 30%. The program is
  open to new developers and anyone under US$1M in proceeds a year, and needs a short enrolment form
  ([Small Business Program](https://developer.apple.com/app-store/small-business-program/); terms in
  [Schedule 2, v126, 2025-12-17](https://developer.apple.com/support/downloads/terms/schedules/Schedule-2-and-3-English.pdf)).
- **EU Digital Services Act trader status:** every developer must declare it in App Store Connect, even one who doesn't
  distribute in the EU
  ([DSA trader requirements](https://developer.apple.com/help/app-store-connect/manage-compliance-information/manage-european-union-digital-services-act-trader-requirements)).
  - A trader's contact details are shown on EU product pages. An individual shows an address or P.O. box, a phone
    number and an email. An organisation shows its D-U-N-S address.
  - In-app-purchase revenue is "one factor, not a conclusive test". A hobbyist without commercial intent is "unlikely
    to be a trader".
  - TestFlight-only distribution isn't trading on the App Store.

**Notes for Neal:**

- **Enrolling as yourself is the quick path,** and right for TestFlight. Friends and, later, buyers then see your legal
  name as the seller, not "Granger".
- **To show "Granger" (or a Grange-led company name),** you'd first form a legal entity such as an LLC. Then you'd get
  its free D-U-N-S number, which can take days, a domain email and a live website. After that you'd enrol as an
  organisation, or ask Apple to convert the individual account.
- **The Supporter purchase may make you a DSA trader.** If so, an individual's address and phone appear on EU product
  pages. That's a reason to settle the entity question before the App Store, not before TestFlight.

### 6.2 TestFlight

Sources: the [TestFlight overview](https://developer.apple.com/testflight/), the
[App Store Connect TestFlight overview](https://developer.apple.com/help/app-store-connect/test-a-beta-version/testflight-overview),
[Invite external testers](https://developer.apple.com/help/app-store-connect/test-a-beta-version/invite-external-testers)
and [Add internal testers](https://developer.apple.com/help/app-store-connect/test-a-beta-version/add-internal-testers).

- **Internal testers:**
  - Up to 100, and each must be an App Store Connect user on Neal's team with a role on the app.
  - No review needed.
  - A friend would have to be added as a team user first, which is fine for one or two trusted people and awkward
    beyond that.
- **External testers:**
  - Up to 10,000 per app.
  - The first build added to an external group goes to **TestFlight App Review** ("Beta App Review"). Later builds of
    the same version "might not" need a full review.
  - At most 6 review submissions in 24 hours, and one build per version in review at a time.
  - It needs a beta app description, review information, and test notes with a feedback email.
  - Significant updates should go back through review (guideline 2.2, below).
- **Build expiry:** "You can test a build for up to 90 days." After that the build becomes unavailable to testers.
- **How a friend joins:**
  - Either way, they install the free **TestFlight** app.
  - **Email invite:** Neal adds their address, or imports a CSV. Their name and email show in App Store Connect.
  - **Public link:** anyone with the link can join. It can be capped at 1–10,000 testers and filtered by device or OS
    version. Joiners show as "anonymous", though installs, sessions and crashes are still reported. Neal can turn the
    link off at any time.
  - A tester can use up to 30 devices.
- **Guideline 2.2** ([App Review Guidelines](https://developer.apple.com/app-store/review/guidelines/), last updated
  2026-10-16): a TestFlight build "should be intended for public distribution and should comply with the App Review
  Guidelines", and testers can't be given "compensation of any kind, including as a reward for crowd-sourced funding".
  So no Supporter perk may be tied to beta access.
- **Recommendation (inference):** an external group with email invites for friend round 1. Names make the email
  feedback (F1) traceable. A public link capped at a few dozen fits later rounds.

### 6.3 App Review risks for Granger

The guidelines are quoted from the [App Review Guidelines](https://developer.apple.com/app-store/review/guidelines/),
"Last Updated: October 16, 2026". The
[2026-10-16 change notes](https://developer.apple.com/news/?id=92th7e5i) say the update clarified 1.2 and added 2.5.19.

**1. Content downloaded after install (2.5.2, 4.2.3, 2.1). Risk: low on the rule, medium on the review.**

- **2.5.2 forbids downloading code,** not data: apps may not "download, install, or execute code which introduces or
  changes features or functionality of the app".
- **ZIMs, model weights and voices are data.** Apple's own Background Assets doc lists "machine learning models" among
  the assets it will host, up to 200 GB per app
  ([Downloading Apple-hosted asset packs](https://developer.apple.com/tutorials/data/documentation/backgroundassets/downloading-apple-hosted-asset-packs.md);
  [size limits](https://developer.apple.com/help/app-store-connect/reference/apple-hosted-asset-pack-size-limits):
  200 GB total and 200 packs per app record).
- **There's a precedent:** Kiwix's App Store app (seller Wikimedia CH, 19 MB) downloads multi-GB ZIMs
  ([listing](https://apps.apple.com/us/app/kiwix/id997079563)).
- **ZIM pages carry HTML and some JavaScript, which runs in WKWebView.** Keep that content-only: no JS bridge that adds
  app features. That's inference, and it matches 4.7.2's spirit of not exposing native APIs to downloaded software.
- **4.2.3(ii):** "If your app needs to download additional resources in order to function on initial launch, disclose
  the size of the download and prompt users before doing so." Granger's onboarding already shows sizes, and this
  makes that mandatory.
- **2.1(a), the real risk:** reviewers reject apps that seem incomplete. A reviewer who won't wait for a 2 GB model and
  a 1 GB ZIM may see an empty app. Ship a small bundled starter pack (the binary may be up to 4 GB uncompressed,
  [max build sizes](https://developer.apple.com/help/app-store-connect/reference/maximum-build-file-sizes)), and say
  in Review Notes what to download for Ask and how big it is.
- **Option:** Apple-hosted asset packs (iOS 26+) could carry Granger's own model and voice files, with Apple's CDN,
  background updates and no server of our own. ZIMs from Kiwix mirrors stay on URLSession.

**2. Ask, the on-device LLM (1.2, 1.4.1, 2.5.19, age rating). Risk: medium.**

- **1.2 now names "AI content creation apps".** These need a filter, a way to report offensive content, a way to block
  abusive users, and published contact information. Ask is grounded Q&A, not content creation, but a reviewer may
  apply 1.2 anyway.
  - Cheap mitigation: a **Report** action on each answer, as a mailto in F1's feedback-email style; contact details in
    the app and on the listing; and the existing "Preview" label plus citations.
  - Developer blogs claim reviewers expect Report on every model reply
    ([ptkd.com](https://ptkd.com/journal/guideline-1-2-user-generated-content-ugc-for-ai-chat)). That's secondary and
    not Apple's text.
  - One blog says 2.1 requires AI disclosure
    ([dev.to](https://dev.to/alichherawalla/enterprise-app-store-rejection-playbook-how-to-ship-ai-features-without-getting-blocked-in-2026-17ko)).
    It doesn't. I checked 2.1(a), and it has no AI text.
- **4.7 covers "chatbots"** as software "not embedded in the binary". Granger's model is a file, not a hosted chatbot,
  so 4.7 probably doesn't apply (inference). If it did, 4.7.1 asks the same filter and report items.
- **1.4.1:** medical information apps "should remind users to check with a doctor". The eval set has medical
  questions, so add that line to Ask answers on health topics, or to the Ask disclaimer.
- **2.5.19 (new):** "Don't include any hidden, dormant, or undocumented features". Ask is absent on READ-tier phones
  and appears only after a model download, so Review Notes must explain both.
- **Age rating:** Apple says "consider how all app features, including AI assistants and chatbot functionality, impact
  the frequency of sensitive content"
  ([age ratings news, 2025-07-24](https://developer.apple.com/news/?id=ks775ehf)). Expect a 13+ rating or so (inference).
- **5.1.2(i)** (sharing personal data "with third-party AI") doesn't apply, because nothing leaves the phone.

**3. Third-party content, archive.org and non-commercial packs (5.2.1–5.2.3). Risk: low to medium.**

- **5.2.2:** if the app "uses, accesses, monetizes access to, or displays content from a third-party service", it must
  be "specifically permitted … under the service's terms", and "Authorization must be provided upon request".
- **5.2.3:** no saving or downloading "media from third-party sources … without explicit authorization". Audiobooks from
  archive.org/LibriVox are public domain, which is the defence. Keep the licence and terms evidence in the catalog
  (it's already signed) and be ready to show it.
- **Keep NC content away from money (inference):** don't tie non-commercial packs to the Supporter purchase, and say in
  the app that Supporter buys nothing but thanks.

**4. The Supporter purchase (3.1.1, 3.2.1, 2.1(b)). Risk: low if it's in-app purchase.**

- **3.1.1:** "Apps may use in-app purchase currencies to enable customers to 'tip' the developer". Anything that
  unlocks features or content must be in-app purchase. A one-time non-consumable or consumable IAP called "Supporter"
  fits.
- **Kiwix's in-app Apple Pay donations rely on 3.2.1(vi),** which is for approved nonprofits only. Granger can't copy
  that. 3.2.2(iv) bars collecting funds in-app otherwise.
- **2.1(b):** the IAP must be "visible to the reviewer and functional".

**5. Background work (2.5.4, 2.4.2). Risk: low if the right API serves each job.**

- **2.5.4:** "Multitasking apps may only use background services for their intended purposes: VoIP, audio playback,
  location, task completion, local notifications, etc."
- **Downloads go through a URLSession background session.** It needs no `UIBackgroundModes` entry, and transfers
  "continue even when the app itself is suspended or terminated". But "if the user terminates the app from the
  multitasking screen, the system cancels all of the session's background transfers". Apple encourages
  `isDiscretionary` for large transfers
  ([background(withIdentifier:)](https://developer.apple.com/tutorials/data/documentation/foundation/urlsessionconfiguration/background(withidentifier:).md)).
  Tell users not to swipe Granger away mid-download.
- **Listen's Piper playback** fits the `audio` background mode, which is its intended purpose.
- **Unpacking or indexing:** `BGContinuedProcessingTask` (iOS 26). It must start from a user action, shows a Live
  Activity, and the user can cancel it.
- **No model inference in the background (inference).** 2.4.2 also warns against apps that "generate excessive heat".

**6. Licensing (5.2.1).** Apps "should be submitted by the person or legal entity that owns or has licensed the
intellectual property". The licence evidence (Gemma terms, voice licences) belongs in Review Notes if asked.

### 6.4 Privacy

**The privacy manifest** ([Privacy manifest files](https://developer.apple.com/tutorials/data/documentation/bundleresources/privacy-manifest-files.md)):

- **Contents:** `PrivacyInfo.xcprivacy` declares `NSPrivacyTracking`, `NSPrivacyTrackingDomains`,
  `NSPrivacyCollectedDataTypes` and `NSPrivacyAccessedAPITypes`.
- **"Starting May 1, 2024, apps that don't describe their use of required reason API in their privacy manifest file
  aren't accepted by App Store Connect."**
- **Each framework needs its own:** "For each executable or dynamic library … that uses a required reason API, the
  bundle that includes the executable or dynamic library needs to include a privacy manifest"
  ([Describing use of required reason API](https://developer.apple.com/tutorials/data/documentation/bundleresources/describing-use-of-required-reason-api.md)).
  That covers each xcframework: libkiwix, sherpa-onnx, the LiteRT-LM engine and the KMP framework.
- **None of Granger's likely dependencies is on Apple's list** of SDKs that must ship a signed manifest. The list does
  name OpenSSL, BoringSSL, Abseil, Protobuf, Flutter and Firebase. LiteRT and ONNX Runtime builds may vendor Abseil or
  Protobuf, so check with Task 2
  ([third-party SDK requirements](https://developer.apple.com/support/third-party-SDK-requirements/)).

**The likely Granger manifest:** tracking false, no collected data types, and these required-reason entries. The reason
codes are from a secondary summary
([tessl.io](https://tessl.io/registry/dpearson2699/swift-ios-skills/3.6.0/files/skills/app-store-review/references/privacy-manifest.md)),
matched against Apple's category names. Re-check against Apple's list before submitting.

| Category | Granger's use | Reason |
|---|---|---|
| `FileTimestamp` (`stat`/`fstat`, file dates) | libkiwix/libzim and the library scan stat files in the app container | `C617.1` (files in the app or app-group container); `0A2A.1` for a third-party SDK wrapper |
| `DiskSpace` | free-space check before multi-GB downloads | `E174.1` (check space before writes); `85F4.1` if Storage shows it to the user |
| `UserDefaults` | settings | `CA92.1` (app-only data) |
| `SystemBootTime` (`mach_absolute_time`) | timing; Kotlin/Native and Compose use it | `35F9.1` (elapsed time between events) |

Kotlin's own guide says Compose Multiplatform pulls in `stat`/`fstat` (reason `0A2A.1`) and `mach_absolute_time`
(`35F9.1`). JetBrains ships a `required_reason_finder.py` script and an `apple-privacy-manifests` Gradle plugin, 1.0.0
([Kotlin docs, 2025-05-16](https://kotlinlang.org/docs/multiplatform/multiplatform-privacy-manifest.html)).

**The App Privacy label** ([App privacy details](https://developer.apple.com/app-store/app-privacy-details/)):

- **"Collect" means** "transmitting data off the device in a way that allows you and/or your third-party partners to
  access it" beyond serving the request in real time.
- **On-device processing isn't collection:** "Data that is processed only on device is not 'collected' and does not
  need to be disclosed."
- **Email feedback can stay off the label.** Optional feedback "unrelated to the primary purpose of the app" that the
  user sends deliberately "may not need to be disclosed" if it meets all the criteria. F1's email feedback fits.
- **So Granger qualifies for "Data Not Collected",** as Kiwix's listing shows
  ([listing](https://apps.apple.com/us/app/kiwix/id997079563)), with two conditions (inference):
  - Granger's own catalog host must keep no logs that link requests to people.
  - No crash reporter that uploads anything. Apple's opt-in crash sharing through TestFlight and App Store Connect is
    Apple's, not the app's.
- **A privacy policy URL is still required,** both in App Store Connect and inside the app (5.1.1(i)). The page can be
  a short "we collect nothing" statement that names the download hosts the phone contacts.

### 6.5 App Store vs GPLv3, in brief (libkiwix itself is in §2)

- **The FSF's position (2010):** Apple's usage rules impose "further restrictions" the GPL forbids, and "the same
  analysis would apply to all versions of the GNU GPL"
  ([FSF, 2010-05-26](https://www.fsf.org/blogs/licensing/more-about-the-app-store-gpl-enforcement)). GPLv3 §10:
  "You may not impose any further restrictions on the exercise of the rights granted or affirmed under this License"
  ([GPLv3](https://www.gnu.org/licenses/gpl-3.0.en.html)).
- **VideoLAN's counter-argument (2010):** Apple's terms yield to a developer's own EULA, so the conflict may no longer
  hold ([vlc-devel, Nov 2010](https://mailman.videolan.org/pipermail/vlc-devel/2010-November/076998.html)). Neither side
  has a court ruling.
- **GPLv3 §6 "Installation Information" for "User Products"** has an exception where "neither you nor any third party
  retains the ability to install modified object code". Whether an App Store download is conveyed "in, with, or
  specifically for use in" a User Product is unsettled (inference).
- **In practice, the copyright holder decides.** Granger's own code is Neal's, so Neal can distribute it through the
  App Store and, if he wants, add a GPLv3 §7 additional permission for App Store distribution. Third-party GPL code
  whose copyright Granger doesn't hold (libkiwix) needs its holders' tolerance or permission. Kiwix ships its own
  GPLv3 app ([kiwix-apple](https://github.com/kiwix/kiwix-apple), "GPLv3 or later") because it holds that copyright.
  Neal decides.


## 4. The shared core trial (2026-10-28, Xcode 27.1, Kotlin 2.4.21)

**Verdict: the three pure-Kotlin cores share cleanly. All of `:core:text`, `:core:research` and `:core:vectors` compile for `iosArm64` and `iosSimulatorArm64` after about 160 lines of platform code and small edits, every original JVM test still passes, and the iOS Simulator gives the same chunk hashes, parses and folds as the JVM.** Trial code: `spikes/i0/kmp-core/` (a standalone Gradle build over copies of the sources; the real modules are untouched). Run with `./gradlew -p spikes/i0/kmp-core :vectors:iosSimulatorArm64Test :text-research:iosSimulatorArm64Test :vectors:jvmTest :text-research:jvmTest`.

**As copied, before any change:** 210 errors in 13 of 43 files; 30 files compiled for iOS untouched. By cause: `java.nio` / `RandomAccessFile` in `VectorFile` (76), jsoup in `ArticleText`, `HtmlPassages`, `XhtmlChunks` (66), `ExecutorService` / `PriorityQueue` in `VectorSearch` (24), `Normalizer` + `Locale` in five research files (about 30), `BreakIterator` in `CitedQuote`, `MessageDigest` + `String.format` in `Chunkers`, `Map.merge` in `Ranking`, `Character.isHighSurrogate` in `TextCuts`.

**The fixes:**

| Cause | Replacement | Size |
| --- | --- | --- |
| `java.nio` mapped reads, `RandomAccessFile`, `File` | `expect class MappedFile` (JVM: `FileChannel.map`; iOS: POSIX `mmap`) with little-endian reads in common code; `writeFileAtomically` (JVM `File`; iOS `fopen` + `rename`); a `LeBytes` builder for the writer; `File` overloads kept in `jvmMain` | `VectorFile` rewritten in place (same layout, same checks); 34 common + 51 JVM + 40 iOS lines |
| `ExecutorService` + `Callable` | `suspend fun top(..., dispatcher)` with `async`; the blocking `ExecutorService` form kept in `jvmMain` for Android callers | ~20 lines |
| `PriorityQueue` | a 50-line `TopK` min-heap in common code | 50 lines |
| jsoup | **Ksoup 0.2.6** (`com.fleeksoft.ksoup`): same API, import swap, `Jsoup.parse(stream, …, xmlParser())` → `Ksoup.parse(html = …, parser = Parser.xmlParser())` | ~12 lines over 3 files |
| `Normalizer` (NFD / NFKD) | `expect fun decompose(s, compat)` (iOS: `decomposedStringWith{Canonical,Compatibility}Mapping`) | 1 + 1 + 2 lines |
| `lowercase(Locale.ROOT)` | `lowercase()`: Kotlin's is already locale-invariant | deletions |
| `BreakIterator` | `expect fun sentenceBreaks` (iOS: `enumerateSubstringsInRange` by sentences, enclosing ranges) | 8 + 10 lines |
| `MessageDigest` SHA-1, `"%02x".format` | `expect fun sha1` (iOS: CommonCrypto `CC_SHA1`) and a common `hex()` | 3 + 8 lines |
| `Map.merge`, `Character.isHighSurrogate` | a `bump()` helper; `Char.isHighSurrogate()` | 4 lines |

**Tests:**

| Module | JVM (original tests, now over the common code) | iOS Simulator |
| --- | --- | --- |
| text + research | 223 original + 2 parity, all pass (on Ksoup, not jsoup) | 2 parity tests pass |
| vectors | 7 pass, bench skipped as on main; includes the byte-for-byte match of the writer with `tools/vectors`' Python `tiny.gvec` | 5 pass: the four `VectorFile` tests ported to `kotlin.test` (mmap reads, Python fixture, truncation, byte-identical writer) and a 70,000-row search across two ranges |

**Parity (JVM vs iOS):** `ParityTest` runs one fingerprint through both platforms: chunk hashes of seven tricky strings (empty, accents, the `ﬁ` ligature, mathematical letters, NBSP, CJK), `ArticleText.blocks`, `HtmlPassages.split`, `XhtmlChunks.of`, `cutAtSpaces` with an emoji, accent folding (Greek, `Å`, full-width), `MediaCues`/`Entities` cues, `Entities.runs`, `YearFacts.lifespan`. **Identical on both**, so `.gvec` files built on the Mac match iOS's chunk hashes. **One difference:** sentence breaks. The JVM's `BreakIterator` ends a sentence at "Dr."; iOS doesn't. Only `CitedQuote` (the quote shown under an answer) uses them, and nothing is stored from it. A port can keep the platform splitters or write one in common Kotlin so both phones quote the same sentence (preferred: the Mac's `tools/chunker` runs on the JVM and needs nothing from it).

**Caveats:** the JVM golden comes from the common code on Ksoup. The 223 original jsoup-era tests passing is the evidence that Ksoup parses as jsoup did; a check of the real `.gvec` builds against Ksoup chunks belongs in I1. `Regex` on Kotlin/Native is its own engine; the fingerprint's chunking matched, but `Chunkers.SPACE`-style Unicode classes need a dedicated test in I1. Nothing was timed: the Simulator runs on the M2 Max, not an iPhone.

**The rest of the shared logic (estimate, from §1):** `:core:catalog`'s parser and model (plain kotlinx) plus the signature check through a CryptoKit `@objc` shim, about a day. `:core:library`'s ~1,060 pure lines (filing, passage finders, ranking, `DirectoryVectorSources`) the same way as here, a day or two; its ~2,100 Room-tangled lines and ~1,020 DAO/entity lines move only with Room KMP (§2b), several days including the 11 migrations and `BookIndex`'s raw FTS4 queries. The ~620 lines of download rules and `ReadinessModel` (279), about a day. So a shared core of roughly 6–8k lines is a one-to-two-week plan, not a rewrite.

## 5. The UI route trial (2026-10-28, Xcode 27.1, iOS 27.1 Simulator, Compose Multiplatform 1.12.2)

Two shells with the same content: a Library list in Field colours with Spectral, a "Search by meaning" box that calls the shared core (`CoreDemo` in `spikes/i0/kmp-core/shared`: `VectorSearch` over a 20,000-row, 256-dim `.gvec` it writes on first use, `ArticleText.blocks`, `YearFacts.lifespan`), and a reader holding a `WKWebView` with a ZIM-like article. Code: `spikes/i0/ui-swiftui/` (A) and `spikes/i0/ui-compose/` + `spikes/i0/kmp-core/compose-shell/` (B); see `spikes/i0/README.md`. Shots (iPhone 17 Simulator; the iOS 27.1 runtime won't create an iPhone 16 Plus simulator, which says nothing about the real phone): `docs/superpowers/spikes/i0-shots/`.

| | A: SwiftUI over a KMP framework | B: Compose Multiplatform |
| --- | --- | --- |
| Builds and runs | Yes | Yes, after adding `CADisableMinimumFrameDurationOnPhone` to Info.plist (Compose aborts at launch without it) |
| Shared core from the UI | Plain Objective-C export: `List<String>` → `[String]`, nullability kept, a `suspend fun` → a completion handler that Swift calls with `try await`; Kotlin default arguments are lost. Workable as is; SKIE would add Swift enums, sealed classes and `Flow` → `AsyncSequence` | Direct Kotlin calls, and `suspend` in `LaunchedEffect` / `rememberCoroutineScope`, as on Android |
| Search (20,000 rows, Simulator) | 40 ms | 35 ms (same core) |
| The reader (`WKWebView`) | `UIViewRepresentable`, ~10 lines | `UIKitView`, ~10 lines; renders the same |
| Navigation and Back | The system bar: iOS 27's round back button, swipe-from-edge back, large titles, for free | Ours: a `TopAppBar` and a text "‹ Library"; swipe-back, large titles and the system look must be built or imitated |
| Fonts and theme | Spectral via `UIAppFonts`; the system grouped-list style shows through | Spectral via Compose resources; looks like the Android app (Material 3 on Field colours) |
| Release size (arm64, unsigned) | **5.6 MB binary, 1.8 MB zipped** | **35.6 MB binary, 10.5 MB zipped**: Compose's renderer (Skia) adds ~30 MB, ~9 MB of download |
| Build | Gradle for the framework, Xcode for the app | The same; the release link needs a 6 GB Gradle heap (2 GB ran out) |

**How much Android UI carries over:** `:app` is 25,572 lines. Files with no Android-platform imports (no `android.*`, Media3, WebKit, WorkManager, Room, Glance, Readium, libkiwix, LiteRT) hold about 14,300 lines; files importing Compose hold about 15,400. Under B most of those 14k lines (screens, view models, theme, navigation) move to common code with `R.*` resources and a few Android calls swapped; under A they are rewritten in SwiftUI. Either way the platform-bound parts are native: the web reader host (`WKWebView` + a `zim:` scheme), the e-book reader (Readium Swift), the players (AVFoundation), downloads, and the system integrations (§2).

**Builder's lean (Neal decides in Task 8):** B, Compose Multiplatform for the screens, with native views for the readers and the player. One developer keeps one UI, and U3 is still reshaping it; the field-kit brand is a custom look anyway, so "not native" costs less here than in a stock-iOS app. Its costs are real: ~9 MB more download, the back-swipe and navigation feel to rebuild (try it on the iPhone 16 Plus), and Compose-on-iOS text selection and accessibility (VoiceOver) to verify before committing. A wins if the iPhone version should feel like an iOS app first, and it costs a second UI kept in step with every Android change.

**Neal's impression (2026-10-29, Simulator):** A feels clearly better. The swipes, taps and screen transitions behave the way someone expects on an iPhone, and the SwiftUI shell keeps the Granger theme (Field colours, Spectral titles) without fighting it. His leaning moves from B to A, from the feel side; the cost side (a second UI) is weighed in Task 8, where the way to keep A cheap is to share the screen logic (state and view models) in the Kotlin core so SwiftUI only draws.

## 7. iPhone numbers

*Pending the iPhone 16 Plus.*

## 8. Summary and decisions

*Written after Tasks 4, 5 and 7.*
