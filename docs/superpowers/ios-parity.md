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
| Launcher icon (owl mark), themed icon | AppIcon traced from the launcher mark; ≈ iOS tinted/dark icon variants | I2 | [ ] I2 part ✓ (icon; tinted/dark variants to do) |
| Mini player above the content | Mini player above the tab bar | I5 (the voice), I6 (music, audiobooks) | [ ] I5 part ✓ (Task 8: iOS 26's tab bar accessory while the voice is loaded: title, Reading/Paused · section, play/pause, a tap opens Listen); I6 Task 7 built: one mini player for the voice (music under it on a third line), a song (next track) and a book (back the skip interval), a tap opening the music or audiobook player; with nothing loaded the last song or book from the saved state, paused, as Android's restore(). It still shows over the full players (Android hides it there): Task 9 with the I5 layout finding; ticked at Task 9 |
| TalkBack, font scale, landscape | VoiceOver, Dynamic Type, landscape | every plan | [ ] I2 ✓ for its screens |

## First run, onboarding and settings

| Android | iPhone | Plan | |
| --- | --- | --- | --- |
| Privacy line before any online read (O5) | Same words, same rule | I2 | [x] |
| Onboarding: brand, language, "Pick a start" sets (Starter/Efficient/Comprehensive), the Granger voice, quick wins first | Same flow | I4 (sets, downloads), I5 (voice) | [x] (I5 Task 8: the shared Read aloud line (the Owl) in Pick a start, downloaded first with the quick wins and unpacked on install) |
| Settings: appearance, text, content (adult flags), Local only, offline pictures, Saved for now cap and Clear | Same | I2 (basics), I3 (content, keeping) | [x] |
| App language (English/Español) and knowledge languages | Per-app language, knowledge languages | I7 | [ ] |
| Voice settings (voice, pace, downloads) | Same | I5 | [x] (I5 Task 8: the shared VoiceSettingsModel: Reads with (Granger voices, the system voice in use, System voices (n)), Granger voices to Get/Cancel/Retry/Remove through the shared queue with licence and attribution, Speed 0.7–2.0×, Mark the sentence being read; also Get more › Models & voices) |
| Media settings (music folders, scans) | ≈ Files imports and the Music library | I6 | [ ] Task 6 built (Music library access, picked folders, Import from Files, Rescan now, ReplayGain, skip once there's an audiobook; no Show in other apps: Documents is always in Files; no headset autoplay, call 10); ticked at Task 9 |
| Local only Quick Settings tile | ≈ Control Center control (iOS 18+ `ControlWidget`) | I6 | [ ] |

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
| Landing (editorial, doors), rooms, room screen, subject spreads, broad topic spread, plates, A–Z, quilt map, Through time, Your sources, seen rooms | Same | I3 (Your sources lists packs: I4) | [x] (Your sources: Wikipedia online, packs by area, Maps, Books) |
| Browse a pack's files | Same | I4 | [x] |

## Search

| Android | iPhone | Plan | |
| --- | --- | --- | --- |
| Online titles and articles, recent searches | Same | I2 | [x] |
| Best matches across packs and books, Titles, Articles, Books, By kind filters, Show all / fewer | Same | I4 (packs), I5 (books) | [ ] I4 part ✓ (Best matches keyword-only, Titles, Articles over packs and Wikipedia online, Show all / fewer; Books and By kind with books, I5; search by meaning waits for an embedder) |
| Mic (on-device dictation, title matcher, Did you mean) | `SFSpeechRecognizer` on device / `SpeechAnalyzer`, the same matcher (core) | I6 | [ ] |
| Selected text → Search in Granger (`PROCESS_TEXT`) | ≈ a Share extension | I6 | [ ] |

## Packs and downloads

| Android | iPhone | Plan | |
| --- | --- | --- | --- |
| Get more: Kiwix list (flavours, sizes, plain words), Wikipedia family (how much × pictures), subjects add-ons, signed catalog | Same, CoreKiwix for reading | I4 | [x] (the shared SourceViewModel: Kiwix list, flavours, the Wikipedia set by slice × pictures, subject add-ons, presets) |
| Downloads: one queue, order, Pause all, speed and time left, Home card, Wi-Fi rule, sha256 check | URLSession background sessions, resume by Range/ETag | I4 | [x] (resume after the app is killed checked in the Simulator) |
| Pack reader and pack search (libkiwix), redirects, pack documents (PDF/EPUB in packs) | Same | I4 | [x] (since I5 Task 5 a pack's PDF or listed book opens in Granger's readers, copied out of the pack in chunks) |
| Import a pack from storage | ≈ Files import, packs visible in Files | I4 | [x] |
| Book packs as collections (Gutenberg, nautilus, African Storybook), manifests | Same | I4 (packs), I5 (reading) | [x] (I5 Task 6: a book pack opens as its Library collection, with provenance, manifest sections and works in parts; its books open in the reader) |

## Books

| Android | iPhone | Plan | |
| --- | --- | --- | --- |
| Library: reading shelf, collections, Classics shelf, list/grid views, letter strip, organize, covers | Same | I5 | [x] (Task 6 over the shared LibraryModel/CollectionModel; touch and hold for a book's menu, Android's long press. Task 7: Every collection under the shelf while online, a book pack's collection read online over its `.gbooks` (range reads from Kiwix's mirrors in the shared RemoteBookReads, on Ktor), Keep this book, Keep the whole collection, reading editions with Original scan) |
| E-book reader: pages, contents, search in book, highlights and notes, dictionary, text settings, link peek, fixed layout, PDF | Readium Swift (+ PDFKit) | I5 | [x] (Task 5; link peek in Task 7: reference links drawn as a Readium decoration group with Android's dotted underline, the card with Open article and Read the book, Reference links Off/Light/More in Aa for a book with links) |
| Granger Classics, Clásicos Granger, reference links (Linked Classics), In your books | Same | I5 | [x] (I5 Task 7: the catalog's Classics in Books by kind with Keep through the shared queue (the download is filed onto the shelf), Read now without keeping (Saved for now, checked against the catalog's hash) and Open; the `.glink` and `.gqid` sidecars follow under IosFeatures.LINKED_CLASSICS (on, as Android's Features); MentionIndex and BookLinks behind the shared ReferenceLinksSource, Wikipedia online's Top index included) |
| archive.org: search, item sheet, sets, text PDF → ebook, 1930 filter (Play) | Same (App Store rules as Play's) | I5 | [x] (I5 Task 7: the shared ArchiveModel on Ktor with FixedFilterArchivePrefs: texts only before 1930, no Show everything (call 9); search, shelves, Browse / On this phone, the item sheet with its PDF beside it, a set's parts in order, Open as a book or the set's collection; a finished download is filed by the shared shelf, a born-digital PDF becoming an EPUB through PDFKit's glyphs, pictures and the CGPDFScanner born-digital check. archive.org's audio views come with Listen in I6). I6 Task 7: spoken word and music on the same model with the media downloads (MediaArchiveAudio), Android's release rules: recordings before the filter year, spoken word only its note and the way to LibriVox; Music's empty page links Archive.org music |
| Gutenberg shelf and covers | Same | I5 | [x] (Task 6: covers from the pack before first open, Make all searchable…, Open the pack's own pages, Show removed) |
| Import your own books (EPUB, PDF, CBR) | ≈ Files import | I5 | ≈ [x] (Task 6: EPUB, PDF, TXT, Markdown, CBZ and CBR from Files through the shared FileImports; CBR by libarchive 3.8.1, Markdown by the shared PlainMarkdown; a comic is a PDF of its pages before it's imported, where Android's import takes comics only from archive.org) |

## Listen and audio

| Android | iPhone | Plan | |
| --- | --- | --- | --- |
| Read aloud in both readers (Piper voices: Owl, Nanny, Professor; system voices), sentence marks | sherpa-onnx Piper + AVSpeechSynthesizer | I5 | [x] (I5 Task 8: sherpa-onnx 1.13.8's iOS framework through the shared ReadAloudEngine into AVAudioEngine; system voices by AVSpeechSynthesizer's buffers; articles marked by Android's page script, books by a Readium decoration, both followed until a scroll or turn by hand; listen starts at the page's first words or the saved place; ≈ a book's sentences are walked from its XHTML by the shared EpubReadSource, where Android walks Readium Kotlin's content iterator; PDFs and fixed-layout books aren't read, as on Android) |
| Listen: Reading tab (chapters, speed, sleep, saved place), music under it | Same | I5 | [ ] I5 part ✓ (Task 8: the sentence between its neighbours, paragraph/sentence transport, Stop, Open, Chapters, speed with presets, the voice, the sleep timer (5–60 min with the fade, End of chapter), Resume the last thing read at its sentence; music under the voice and the audio shelves with I6, said on the screen); I6 Task 7: Music under the voice in the Reading part (the level, the track, its own play/pause and next), the "later update" line gone; ticked at Task 9 |
| Music: albums, artists, genres, folders, playlists, queue | ≈ Files imports + the Music library's non-DRM items | I6 | [ ] Task 6 built (the shared scan over Documents/Music, picked folders and the Music library; tags by AVAsset, TagLib for MP3; Ogg Vorbis left out: iOS can't decode it); the Music library path is untested until a device (I8); ticked at Task 9 |
| Audiobooks and LibriVox (passive until one is on the phone), bookmarks | Same | I6 | [ ] Task 7 built: the shared AudiobooksViewModel's shelf (Continue, Title/Author/Recent, list or grid, finished and missing), the book page (Play/Resume, Mark as finished, chapters, bookmarks with notes, a download's Remove), the audiobook player (chapter seek and the book's progress, skip ±, chapter steps, Chapters, speed 0.5–3.0×, sleep timer, bookmarks, Volume boost; Skip silence kept but not applied, call 10); M4B chapters and folders grouped as one book by the shared scan; LibriVox on the shared LibriVoxModel, its books got through the shared queue into the hidden Documents/.granger/media and filed by the shared downloaded-media scan; Listen and Get more show audiobooks only once there are some; ticked at Task 9 |
| Lock screen / notification controls, media session | Now Playing + remote commands | I5 (reading), I6 (music) | [ ] I5 part ✓ (Task 8: MPNowPlayingInfoCenter "Reading · title" with the section, play/pause/toggle/stop and next/previous sentence; background audio mode, AVAudioSession playback/spoken audio; a call or headphones out pauses) |
| Android Auto | ≈ CarPlay audio (Apple entitlement requested in I6) | I6 | [ ] |
| Player widget | ≈ WidgetKit widget | I6 | [ ] |
| Show in other apps (documents provider) | ≈ Files visibility (`UIFileSharingEnabled`) | I4 | [x] |

## Feedback and support

| Android | iPhone | Plan | |
| --- | --- | --- | --- |
| Send feedback (email with device, build, log), Report sheet | Mail compose with the same contents | I7 | [ ] |
| Crash log (newest five) | ≈ MetricKit crash and hang reports | I7 | [ ] |
| No telemetry | Same; App Privacy "Data not collected", privacy manifest | I7 | [ ] |

## Not in either build

| Android | iPhone | |
| --- | --- | --- |
| Ask (Research), off in every Android build since SQ | Follows Android if it's turned back on | — |
| archive.org "Show everything" waiver (F-Droid/direct builds only) | No App Store equivalent | — |
