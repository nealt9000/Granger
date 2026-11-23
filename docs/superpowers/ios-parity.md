# iPhone ↔ Android parity checklist

Neal, 2026-11-20: the iPhone app is built straight through until it matches the Android app 1:1. Then it's handed to Neal, who tests it on the Mac's Simulator, and only after that does it go to friends. This list is what "1:1" means. It covers **what Android's release build ships today** (`Features.kt`: Ask/Research off, everything else on), grouped by area, with the iPhone equivalent and the plan that builds it.

Each plan ticks its rows when its Simulator check passes. Plan I7 is the audit: every row ticked, or marked with an agreed reason, before the hand-off. When Android gains a feature while this runs, add a row.

Legend: **[ ]** to do · **[x]** done on the iPhone · **≈** done with the iOS way of doing it (noted) · **—** no iPhone equivalent (reason given, agreed with Neal)

## Shell and look

| Android | iPhone | Plan | |
| --- | --- | --- | --- |
| Rail: Home, Search, Explore, Library, Listen; Settings | Tab bar with the same five; Settings from Home's header (spec §5) | I2 (Home, Search), I3 (Explore), I5 (Library, Listen) | [x] (Listen the fifth tab since I5 Task 8) |
| Open items on the rail (up to 4 articles/books, pin, remove) | ≈ Recently open, from each tab's toolbar | I3 (articles), I5 (books, reading place) | ≈ [x] (the last four articles and books, newest first, in Home, Search, Explore and Library; a book's row says its place, "Chapter · 42%", and reopens there; no pin/remove, as the iPhone has no rail) |
| Field and Night palettes, System; Spectral titles | Colour assets from `GrangerTheme.kt` (tested equal), Spectral | I2 | [x] |
| Launcher icon (owl mark), themed icon | AppIcon traced from the launcher mark; ≈ iOS tinted/dark icon variants | I2, I7 | [x] (I2: the icon; I7 Task 5: dark and tinted variants from the mark's own layers (`tools/ios/make_appicon_variants.py`), Neal picked tinted A, Android's themed-icon layer; both in `AppIcon.appiconset`, checked in the compiled `Assets.car`) |
| Mini player above the content | Mini player above the tab bar | I5 (the voice), I6 (music, audiobooks) | [x] (iOS 26's tab bar accessory: one mini player for the voice (music under it on a third line), a song (next track) and a book (back the skip interval), a tap opening Listen at the music or audiobook player; with nothing loaded the last song or book from the saved state, paused, as Android's restore(). Hidden while the full music or audiobook player is on screen, as Android hides it there (I6 Task 9); its text stops growing at xLarge, as the system's accessories do, so it no longer spills over the page above it at the accessibility sizes) |
| TalkBack, font scale, landscape | VoiceOver, Dynamic Type, landscape | every plan | [x] (I7 Task 6, call 9: every UI test run once at accessibility XXXL and every screen they reach looked at in screenshots; landscape by `AccessibilityTests` turned sideways (Home, Search, Explore, a topic page, the reader, Library, Listen, Settings, Get more) and `testLandscape`, though XCTest's landscape screenshots show only part of the screen; what was cut or broke mid-word fixed (Local only's banner, the timeline's heading and caption, a collection's grid, Explore's plate captions, Settings' pickers, the e-book text size, Listen's speed presets, the onboarding wordmark), and half sheets open whole at the accessibility sizes. VoiceOver by the accessibility tree, not by ear: `AccessibilityTests` runs Xcode's audit over the main screens and fails on anything VoiceOver can't describe (only the article's own image links in the web view are left, as on Android); icon-only buttons are labelled, radio and check images hidden behind their rows' Selected trait. Still the system's: segmented controls don't grow with the text, shelf chips scroll sideways) |

## First run, onboarding and settings

| Android | iPhone | Plan | |
| --- | --- | --- | --- |
| Privacy line before any online read (O5) | Same words, same rule | I2 | [x] |
| Onboarding: brand, language, "Pick a start" sets (Starter/Efficient/Comprehensive), the Granger voice, quick wins first | Same flow | I4 (sets, downloads), I5 (voice) | [x] (I5 Task 8: the shared Read aloud line (the Owl) in Pick a start, downloaded first with the quick wins and unpacked on install) |
| Settings: appearance, text, content (adult flags), Local only, offline pictures, Saved for now cap and Clear | Same | I2 (basics), I3 (content, keeping) | [x] |
| App language (English/Español) and knowledge languages | Per-app language, knowledge languages | I7 | ≈ [x] (I7 Tasks 3–4: the app language is iOS's own per-app language, Settings › Granger › Language, which the app's Language row names and opens (call 2), no in-app switch; the knowledge languages on the shared `LanguageSettingsModel` with Android's rules (tick, lock the last, Make main), and onboarding's Welcome sets them; Spanish Wikipedia, packs, Clásicos, the time index and topic records follow the main one) |
| Voice settings (voice, pace, downloads) | Same | I5 | [x] (I5 Task 8: the shared VoiceSettingsModel: Reads with (Granger voices, the system voice in use, System voices (n)), Granger voices to Get/Cancel/Retry/Remove through the shared queue with licence and attribution, Speed 0.7–2.0×, Mark the sentence being read; also Get more › Models & voices) |
| Media settings (music folders, scans) | ≈ Files imports and the Music library | I6 | ≈ [x] (Music library access, picked folders through security-scoped bookmarks, Import from Files into Documents/Music, Rescan now, ReplayGain, the skips once there's an audiobook. No Show in other apps: Documents is always in Files. No Play when headphones connect: iOS doesn't let an app start itself when headphones connect (call 10)) |
| Settings › Models (the embedder, the voices) | Same, over the shared SourceViewModel | I7 | [x] (I7 Task 5: the embedder says "Used by Search to find passages by meaning"; Task 6: a source screen is titled by its scope's name as on Android (Models, Granger, Kiwix, Get books), where it said Get more) |
| About: GPLv3 · source on GitHub | Same, plus Open-source licences (the iPhone bundles libraries Android doesn't) | I7 | [x] (I7 Task 5: generated from `tools/ios/licences/` by `make_licences.py`, whose `--check` fails a test build when an Xcode package or vendor script isn't listed; the GPL's §7 App Store permission is a placeholder until I8) |
| Local only Quick Settings tile | ≈ Control Center control (iOS 18+ `ControlWidget`) | I6 | ≈ [x] (I6 Task 8: a `ControlWidgetToggle` in the widget extension; Local only lives in the app group's defaults as `network.localOnly` and the app follows a flip through a Darwin notification; the intent checked by UI test, the control itself not yet placed in the Simulator's Control Center by hand) |

## Wikipedia online and keeping

| Android | iPhone | Plan | |
| --- | --- | --- | --- |
| Online articles in Granger's look (`online.css`), pictures from Commons, links | `WKWebView` + `granger-zim:` scheme, shared CSS and scripts | I2 | [x] |
| Topic page in front of the article: picture, summary, Read the full article, Read in other language | Same | I2 | [x] |
| Topic page: records (parts, rooms, "leads here"), broad topics, quilt, Related (filtered) | Same | I3 | [x] (a broad topic's figures come from packs, I4, as on Android online) |
| Keep (with/without pictures), Saved for now, marks (dot, ring), kept-article updates | Same | I3 (keeping), I4 (updates, with Outfitting) | [x] (kept-article updates in Outfitting, I4) |
| Local only (one gate), greyed tiles, hints | Same | I2 (gate), I3 (banner, hints), I4 (greyed pack tiles) | [x] (Wikipedia online greyed in Your sources under Local only; packs always open) |
| Online indexes (Top `.gqid`/`.gtime`) fetched at first run | Same | I3 | [x] |
| Outfitting (your use + nine domains), would-take sizes | Same | I3 (online), I4 (packs) | [x] |
| Reader: Contents, text size, tap to hide bars, find in page, Listen | Same | I2 (Contents, size, bars), I3 (find), I5 (Listen) | [x] (Listen since I5 Task 8, with Read from here in the selection menu) |
| Reader footer (In your books, article footer views), quotes | Same | I5 (books) | ≈ [x] (I5 Task 7: the shared ReferenceLinksSource's footer under an article: In your library, the passages in your books opening the book there, the author's books; the topic page's quotes from the same source. ≈ the card rides with the bars, where Android shows it once the page is read to its end) |
| Adult flags on default screens (Plan AF) | Same | I3 | [x] |

## Home

| Android | iPhone | Plan | |
| --- | --- | --- | --- |
| Home as your desk (Plan O3): Continue, Ready offline, the Timeline, up to two feeds, Add feed, scopes, Day page | Same | I3 (Continue, Timeline, Arrange), I4 (Ready offline, feeds, Day page) | [x] (Ready offline, up to two feeds where Arrange Home puts them, Add feed on +, the day's Mentions feed) |
| Wander (well-known articles from the index; in Explore's Ways on since Plan O3) | Same | I3 | [x] ≈ lead links as a grid, not an orbit |
| Timeline card, full timeline, time scale, Show in time | Same | I3 | [x] |
| Games (6, Games hub in Explore's Ways on, runs of 10, a subject's quiz) | Same | I3 | [x] |
| Arrange Home | Same (SwiftUI list editing, Android's tokens) | I3 | [x] |

## Explore

| Android | iPhone | Plan | |
| --- | --- | --- | --- |
| Landing (editorial, doors), rooms, room screen, subject spreads, broad topic spread, plates, A–Z, quilt map, Through time, Your sources, seen rooms | Same | I3 (Your sources lists packs: I4) | [x] (Your sources: Wikipedia online, packs by area, Maps, Books. I7 Task 6: a broad topic's spread has Read in the other language, as Android's `BroadSpread`; it was missing, so a Spanish spread's topic page had no Read in English) |
| Browse a pack's files | Same | I4 | [x] |

## Search

| Android | iPhone | Plan | |
| --- | --- | --- | --- |
| Online titles and articles, recent searches | Same | I2 | [x] |
| Best matches across packs and books, Titles, Articles, Books, By kind filters, Show all / fewer | Same | I4 (packs), I5 (books) | [ ] open, packs only: Android's Best matches also search the books (its `LibraryPassageFinder`: the book index's passages and books' vectors, with `FictionWeight`, and only the library's languages); the iPhone's search the packs only. Not closed in the audit: it needs the shared finder wired with the book index in `IosLibrary`, a core rebuild and a Best-match row that opens a book at its passage (more than an hour). What is built: I4 part ✓ (Best matches keyword-only, Titles, Articles over packs and Wikipedia online, Show all / fewer; Books and By kind with books, I5; search by meaning: I7 Task 5, EmbeddingGemma 2 on LiteRT-LM 0.18.0's prebuilt iOS framework, query vectors cosine 1.000 against Android's engine on 20 queries; the dense half over the packs' `.gvec` files merged as Android merges it, the vectors following their packs and the embedder the first vectors (`EmbedderDownloads`) on an iPhone with Android's 6 GB rule, the slow-phone fallback (`EmbedSpeed`); Best matches over books' vectors waits for books in iPhone Best matches; speed on a phone waits for a device, I8) |
| A pack book's title hit names its pack by title, not its id (2026-11-21) | Same (shared `PackSearch`) | I7 (audit) | [x] (comes with the shared core) |
| Mic (on-device dictation, title matcher, Did you mean) | `SpeechAnalyzer` on device, the same matcher (core) | I6 → I8 | waits for a device (I8): I6 Task 8's spike found no on-device recognizer model in the Simulator ("Model not available"), so nothing shows SpeechAnalyzer and the shared `SpokenTitleMatcher` working together; `SpeechSpikeTests` run for real on an iPhone. The backend, the Search mic and Voice input in Settings are built after that check (I8). `VoiceInput`, `Dictation` and `SpokenTitles` are already shared |

## Packs and downloads

| Android | iPhone | Plan | |
| --- | --- | --- | --- |
| Get more: Kiwix list (flavours, sizes, plain words), Wikipedia family (how much × pictures), subjects add-ons, signed catalog | Same, CoreKiwix for reading | I4 | [x] (the shared SourceViewModel: Kiwix list, flavours, the Wikipedia set by slice × pictures, subject add-ons, presets) |
| Downloads: one queue, order, Pause all, speed and time left, Home card, Wi-Fi rule, sha256 check | URLSession background sessions, resume by Range/ETag | I4 | [x] (resume after the app is killed checked in the Simulator) |
| Pack reader and pack search (libkiwix), redirects, pack documents (PDF/EPUB in packs) | Same | I4 | [x] (since I5 Task 5 a pack's PDF or listed book opens in Granger's readers, copied out of the pack in chunks) |
| Import a pack from storage | ≈ Files import, packs visible in Files | I4 | [x] |
| Book packs as collections (Gutenberg, nautilus, African Storybook), manifests | Same | I4 (packs), I5 (reading) | [x] (I5 Task 6: a book pack opens as its Library collection, with provenance, manifest sections and works in parts; its books open in the reader) |
| Classics volumes (2026-11-21): Granger Classics in 10 volumes, Clásicos Granger in 8, each under its collection on Granger's screen with its own Get; Books lists only the collections; an onboarding line of one or two volumes named by them | Same | I7 (audit) | [x] (I7 Task 6: the shared SourceViewModel nests the volumes and OnboardingModel names the lines; the iPhone's preset row indents a volume as Android's `PresetItem`. `ClassicsUITests.testClassicsVolumesSitUnderTheirCollection` against the live catalog) |

## Books

| Android | iPhone | Plan | |
| --- | --- | --- | --- |
| Library: reading shelf, collections, Classics shelf, list/grid views, letter strip, organize, covers | Same | I5 | [x] (Task 6 over the shared LibraryModel/CollectionModel; touch and hold for a book's menu, Android's long press. Task 7: Every collection under the shelf while online, a book pack's collection read online over its `.gbooks` (range reads from Kiwix's mirrors in the shared RemoteBookReads, on Ktor), Keep this book, Keep the whole collection, reading editions with Original scan) |
| E-book reader: pages, contents, search in book, highlights and notes, dictionary, text settings, link peek, fixed layout, PDF | Readium Swift (+ PDFKit) | I5 | [x] (Task 5; link peek in Task 7: reference links drawn as a Readium decoration group with Android's dotted underline, the card with Open article and Read the book, Reference links Off/Light/More in Aa for a book with links) |
| Search in a book: the words before a hit cut to a short lead (2026-11-21) | Same (shared `SearchRows.lead`) | I7 (audit) | [x] (the iPhone's search panel draws the shared rows' `before`) |
| Granger Classics, Clásicos Granger, reference links (Linked Classics), In your books | Same | I5 | [x] (I5 Task 7: the catalog's Classics in Books by kind with Keep through the shared queue (the download is filed onto the shelf), Read now without keeping (Saved for now, checked against the catalog's hash) and Open; the `.glink` and `.gqid` sidecars follow under IosFeatures.LINKED_CLASSICS (on, as Android's Features); MentionIndex and BookLinks behind the shared ReferenceLinksSource, Wikipedia online's Top index included) |
| archive.org: search, item sheet, sets, text PDF → ebook, 1930 filter (Play) | Same (App Store rules as Play's) | I5 | [x] (I5 Task 7: the shared ArchiveModel on Ktor with FixedFilterArchivePrefs: texts only before 1930, no Show everything (call 9); search, shelves, Browse / On this phone, the item sheet with its PDF beside it, a set's parts in order, Open as a book or the set's collection; a finished download is filed by the shared shelf, a born-digital PDF becoming an EPUB through PDFKit's glyphs, pictures and the CGPDFScanner born-digital check. archive.org's audio views come with Listen in I6). I6 Task 7: spoken word and music on the same model with the media downloads (MediaArchiveAudio), Android's release rules: recordings before the filter year, spoken word only its note and the way to LibriVox; Music's empty page links Archive.org music |
| Gutenberg shelf and covers | Same | I5 | [x] (Task 6: covers from the pack before first open, Make all searchable…, Open the pack's own pages, Show removed) |
| A book pack that opens online: an arrow beside Keep (2026-11-20) | Same | I7 (audit) | ≈ [x] (I5 Task 7 already drew the row's chevron in its label, before Keep, where Android puts it after) |
| Import your own books (EPUB, PDF, CBR) | ≈ Files import | I5 | ≈ [x] (Task 6: EPUB, PDF, TXT, Markdown, CBZ and CBR from Files through the shared FileImports; CBR by libarchive 3.8.1, Markdown by the shared PlainMarkdown; a comic is a PDF of its pages before it's imported, where Android's import takes comics only from archive.org) |

## Listen and audio

| Android | iPhone | Plan | |
| --- | --- | --- | --- |
| Read aloud in both readers (Piper voices: Owl, Nanny, Professor; system voices), sentence marks | sherpa-onnx Piper + AVSpeechSynthesizer | I5 | [x] (I5 Task 8: sherpa-onnx 1.13.8's iOS framework through the shared ReadAloudEngine into AVAudioEngine; system voices by AVSpeechSynthesizer's buffers; articles marked by Android's page script, books by a Readium decoration, both followed until a scroll or turn by hand; listen starts at the page's first words or the saved place; ≈ a book's sentences are walked from its XHTML by the shared EpubReadSource, where Android walks Readium Kotlin's content iterator; PDFs and fixed-layout books aren't read, as on Android) |
| Listen: Reading tab (chapters, speed, sleep, saved place), music under it | Same | I5, I6 | [x] (I5 Task 8: the sentence between its neighbours, paragraph/sentence transport, Stop, Open, Chapters, speed with presets, the voice, the sleep timer (5–60 min with the fade, End of chapter), Resume the last thing read at its sentence. I6 Task 7: Music under the voice in the Reading part (the level, the track, its own play/pause and next), at Android's `MIX_LEVEL`) |
| Music: albums, artists, genres, folders, playlists, queue | ≈ Files imports + the Music library's non-DRM items | I6 | ≈ [x] (I6 Task 6: the shared scan over Documents/Music, picked folders and the Music library; tags by AVAsset, TagLib for MP3; gapless on one `AVAudioEngine`. Ogg Vorbis is left out of the scan: iOS can't decode it (an Opus file in Ogg plays); a libvorbis decoder waits on Neal. The Music library path is checked only on a device (I8): a song whose `ipod-library://` URL `AVAudioFile` can't open is left out by `canPlay`) |
| Audiobooks and LibriVox (passive until one is on the phone), bookmarks | Same | I6 | ≈ [x] (I6 Task 7: the shared AudiobooksViewModel's shelf (Continue, Title/Author/Recent, list or grid, finished and missing), the book page, the audiobook player (chapter seek, skip ±, chapter steps, speed 0.5–3.0×, sleep timer, bookmarks with notes, Volume boost); M4B chapters and folders grouped as one book by the shared scan; LibriVox on the shared LibriVoxModel through the shared queue; Listen and Get more show audiobooks only once there are some. ≈ Skip silence is "later": `AVAudioEngine` has nothing like ExoPlayer's silence skipping (call 10), so the book's setting is kept as stored but not applied. ≈ Downloads land hidden in Documents/.granger/media (not Files-visible Audiobooks/Music folders, which a case-blind disk would scan twice), as Android hides them with `.nomedia`; removed in the app. LibriVox and archive.org checked against stubs; the live services on a device (I8)) |
| Lock screen / notification controls, media session | Now Playing + remote commands | I5 (reading), I6 (music) | [x] (I5 Task 8: "Reading · title" with the section, next/previous sentence. I6 Task 5: songs (title, artist, album, cover, queue place) and books (chapter, book, author, cover, chapter count, speed as rate); play, pause, toggle, stop, next/previous, skip ±30 s for books, the position bar; a call or headphones out pause. A hardware remote press checked on a device (I8)) |
| Android Auto | ≈ CarPlay audio | I6 → I8 | ≈ waits for a device (I8): built (I6 Task 8: the shared browse tree, Recent, Music by albums, artists and playlists, Audiobooks, in `CPListTemplate`s, CarPlay's own Now Playing; `CarPlayTests`). Shows on a car only once Apple grants the CarPlay audio entitlement, requested by Neal under AETHERBOUND LLC (I8); not yet looked at in the CarPlay Simulator window; no search or Siri yet (`INPlayMediaIntent`, I8) |
| Player widget | ≈ WidgetKit widget | I6 | ≈ [x] (I6 Task 8: small and medium, the cover, title, progress, play/pause and next or back as `AudioPlaybackIntent`s over a snapshot in the app group; checked by UI test through the snapshot, not yet placed on a home screen by hand; play from a voice that was last isn't offered, as the iPhone has no voice resumer yet) |
| Play when headphones connect (headset autoplay) | — | I6 | — (call 10: iOS doesn't let an app start itself when headphones connect) |
| External equalizer apps (audio-session broadcast) | — | I6 | — (call 10: iOS has no such hook; Android ships no equalizer of its own either) |
| Show in other apps (documents provider) | ≈ Files visibility (`UIFileSharingEnabled`) | I4 | [x] |

## Feedback and support

| Android | iPhone | Plan | |
| --- | --- | --- | --- |
| Send feedback (email with device, build, log), Report sheet | Mail compose with the same contents | I7 | [x] (I7 Task 4: Android's sheet and the shared `FeedbackReport` (subject, the facts, `granger-report.txt`), `MFMailComposeViewController` to feedback@opengranger.org, the share sheet when Mail isn't set up (the Simulator); the Report sheet on the shared `AskReport`, reached only by a debug hook while Ask is off, as in Android's release) |
| Crash log (newest five) | ≈ MetricKit crash and hang reports | I7 | ≈ [x] (I7 Task 4: Kotlin's unhandled-exception hook and an `NSException` handler write a report at once, as Android's `CrashLog`; the shared `CrashRecords` keeps the newest five, Home's crash line, Not now / Send. MetricKit's crash, hang and disk-write payloads are kept too, but iOS never delivers them in the Simulator: their conversion waits for a device (I8)) |
| No telemetry | Same; App Privacy "Data not collected", privacy manifest | I7 | [x] (I7 Task 4: no analytics or tracking; `PrivacyInfo.xcprivacy` for the app and the widget (no tracking, no collected data, the required-reason APIs); the App Privacy answers written in the plan's Task 4 note for I8's App Store Connect form; the upload's privacy report (ITMS-91053) is checked on the first signed build, I8) |

## Not in either build

| Android | iPhone | |
| --- | --- | --- |
| Ask (Research), off in every Android build since SQ | Follows Android if it's turned back on | — |
| archive.org "Show everything" waiver (F-Droid/direct builds only) | No App Store equivalent | — |
| "Selected text → Search in Granger" (`PROCESS_TEXT`): Android's build never receives it; its manifest only queries for it so Define can send a word to a dictionary app | The system dictionary covers Define (I5 call 8) | — (row dropped, I6 call 9) |
