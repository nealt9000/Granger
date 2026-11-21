# iPhone ↔ Android parity checklist

Neal, 2026-11-20: the iPhone app is built straight through until it matches the Android app 1:1. Then it's handed to Neal, who tests it on the Mac's Simulator, and only after that does it go to friends. This list is what "1:1" means. It covers **what Android's release build ships today** (`Features.kt`: Ask/Research off, everything else on), grouped by area, with the iPhone equivalent and the plan that builds it.

Each plan ticks its rows when its Simulator check passes. Plan I7 is the audit: every row ticked, or marked with an agreed reason, before the hand-off. When Android gains a feature while this runs, add a row.

Legend: **[ ]** to do · **[x]** done on the iPhone · **≈** done with the iOS way of doing it (noted) · **—** no iPhone equivalent (reason given, agreed with Neal)

## Shell and look

| Android | iPhone | Plan | |
| --- | --- | --- | --- |
| Rail: Home, Search, Explore, Library, Listen; Settings | Tab bar with the same five; Settings from Home's header (spec §5) | I2 (Home, Search), I3 (Explore), I5 (Library, Listen) | [ ] I2 + I3 part ✓ (Home, Search, Explore, Settings) |
| Open items on the rail (up to 4 articles/books, pin, remove) | ≈ Recently open, from each tab's toolbar | I3 (articles), I5 (books, reading place) | [ ] I3 part ✓ (last four articles in Home, Search, Explore) |
| Field and Night palettes, System; Spectral titles | Colour assets from `GrangerTheme.kt` (tested equal), Spectral | I2 | [x] |
| Launcher icon (owl mark), themed icon | AppIcon traced from the launcher mark; ≈ iOS tinted/dark icon variants | I2 | [ ] I2 part ✓ (icon; tinted/dark variants to do) |
| Mini player above the content | Mini player above the tab bar | I6 | [ ] |
| TalkBack, font scale, landscape | VoiceOver, Dynamic Type, landscape | every plan | [ ] I2 ✓ for its screens |

## First run, onboarding and settings

| Android | iPhone | Plan | |
| --- | --- | --- | --- |
| Privacy line before any online read (O5) | Same words, same rule | I2 | [x] |
| Onboarding: brand, language, "Pick a start" sets (Starter/Efficient/Comprehensive), the Granger voice, quick wins first | Same flow | I4 (sets, downloads), I5 (voice) | [ ] I4 part ✓ (brand, language, Pick a start, region, quick wins, review; voice step with the voices, I5) |
| Settings: appearance, text, content (adult flags), Local only, offline pictures, Saved for now cap and Clear | Same | I2 (basics), I3 (content, keeping) | [x] |
| App language (English/Español) and knowledge languages | Per-app language, knowledge languages | I7 | [ ] |
| Voice settings (voice, pace, downloads) | Same | I5 | [ ] |
| Media settings (music folders, scans) | ≈ Files imports and the Music library | I6 | [ ] |
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
| Reader: Contents, text size, tap to hide bars, find in page, Listen | Same | I2 (Contents, size, bars), I3 (find), I5 (Listen) | [ ] I2 + I3 part ✓ (Contents, size, bars, find) |
| Reader footer (In your books, article footer views), quotes | Same | I5 (books) | [ ] |
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
| Book packs as collections (Gutenberg, nautilus, African Storybook), manifests | Same | I4 (packs), I5 (reading) | [ ] I4 part ✓ (book packs listed as their books in Your sources; reading in I5) |

## Books

| Android | iPhone | Plan | |
| --- | --- | --- | --- |
| Library: reading shelf, collections, Classics shelf, list/grid views, letter strip, organize, covers | Same | I5 | [ ] |
| E-book reader: pages, contents, search in book, highlights and notes, dictionary, text settings, link peek, fixed layout, PDF | Readium Swift (+ PDFKit) | I5 | [ ] (Task 5 built all but link peek, which comes with the Classics' links in Task 7) |
| Granger Classics, Clásicos Granger, reference links (Linked Classics), In your books | Same | I5 | [ ] |
| archive.org: search, item sheet, sets, text PDF → ebook, 1930 filter (Play) | Same (App Store rules as Play's) | I5 | [ ] |
| Gutenberg shelf and covers | Same | I5 | [ ] |
| Import your own books (EPUB, PDF, CBR) | ≈ Files import | I5 | [ ] |

## Listen and audio

| Android | iPhone | Plan | |
| --- | --- | --- | --- |
| Read aloud in both readers (Piper voices: Owl, Nanny, Professor; system voices), sentence marks | sherpa-onnx Piper + AVSpeechSynthesizer | I5 | [ ] |
| Listen: Reading tab (chapters, speed, sleep, saved place), music under it | Same | I5 | [ ] |
| Music: albums, artists, genres, folders, playlists, queue | ≈ Files imports + the Music library's non-DRM items | I6 | [ ] |
| Audiobooks and LibriVox (passive until one is on the phone), bookmarks | Same | I6 | [ ] |
| Lock screen / notification controls, media session | Now Playing + remote commands | I5 (reading), I6 (music) | [ ] |
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
