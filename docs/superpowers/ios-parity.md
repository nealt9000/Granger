# iPhone ↔ Android parity checklist

Neal, 2026-11-20: the iPhone app is built straight through until it matches the Android app 1:1. Then it's handed to Neal, who tests it on the Mac's Simulator, and only after that does it go to friends. This list is what "1:1" means. It covers **what Android's release build ships today** (`Features.kt`: Ask/Research off, everything else on), grouped by area, with the iPhone equivalent and the plan that builds it.

Each plan ticks its rows when its Simulator check passes. Plan I7 is the audit: every row ticked, or marked with an agreed reason, before the hand-off. When Android gains a feature while this runs, add a row.

Legend: **[ ]** to do · **[x]** done on the iPhone · **≈** done with the iOS way of doing it (noted) · **—** no iPhone equivalent (reason given, agreed with Neal)

## Shell and look

| Android | iPhone | Plan | |
| --- | --- | --- | --- |
| Rail: Home, Search, Explore, Library, Listen; Settings | Tab bar with the same five; Settings from Home's header (spec §5) | I2 (Home, Search), I3 (Explore), I5 (Library, Listen) | [ ] I2 part ✓ (Home, Search, Settings) |
| Open items on the rail (up to 4 articles/books, pin, remove) | ≈ Recently open, from each tab's toolbar | I3 | [ ] |
| Field and Night palettes, System; Spectral titles | Colour assets from `GrangerTheme.kt` (tested equal), Spectral | I2 | [x] |
| Launcher icon (owl mark), themed icon | AppIcon traced from the launcher mark; ≈ iOS tinted/dark icon variants | I2 | [ ] I2 part ✓ (icon; tinted/dark variants to do) |
| Mini player above the content | Mini player above the tab bar | I6 | [ ] |
| TalkBack, font scale, landscape | VoiceOver, Dynamic Type, landscape | every plan | [ ] I2 ✓ for its screens |

## First run, onboarding and settings

| Android | iPhone | Plan | |
| --- | --- | --- | --- |
| Privacy line before any online read (O5) | Same words, same rule | I2 | [x] |
| Onboarding: brand, language, "Pick a start" sets (Starter/Efficient/Comprehensive), the Granger voice, quick wins first | Same flow | I4 (sets, downloads), I5 (voice) | [ ] |
| Settings: appearance, text, content (adult flags), Local only, offline pictures, Saved for now cap and Clear | Same | I2 (basics), I3 (content, keeping) | [ ] I2 part ✓ (appearance, text, Local only) |
| App language (English/Español) and knowledge languages | Per-app language, knowledge languages | I7 | [ ] |
| Voice settings (voice, pace, downloads) | Same | I5 | [ ] |
| Media settings (music folders, scans) | ≈ Files imports and the Music library | I6 | [ ] |
| Local only Quick Settings tile | ≈ Control Center control (iOS 18+ `ControlWidget`) | I6 | [ ] |

## Wikipedia online and keeping

| Android | iPhone | Plan | |
| --- | --- | --- | --- |
| Online articles in Granger's look (`online.css`), pictures from Commons, links | `WKWebView` + `granger-zim:` scheme, shared CSS and scripts | I2 | [x] |
| Topic page in front of the article: picture, summary, Read the full article, Read in other language | Same | I2 | [x] |
| Topic page: records (parts, rooms, "leads here"), broad topics, quilt, Related (filtered) | Same | I3 | [ ] |
| Keep (with/without pictures), Saved for now, marks (dot, ring), kept-article updates | Same | I3 | [ ] |
| Local only (one gate), greyed tiles, hints | Same | I2 (gate), I3 (greying, hints) | [ ] I2 part ✓ (the gate) |
| Online indexes (Top `.gqid`/`.gtime`) fetched at first run | Same | I3 | [ ] |
| Outfitting (your use + nine domains), would-take sizes | Same | I3 (online), I4 (packs) | [ ] |
| Reader: Contents, text size, tap to hide bars, find in page, Listen | Same | I2 (Contents, size, bars), I3 (find), I5 (Listen) | [ ] I2 part ✓ (Contents, size, bars) |
| Reader footer (In your books, article footer views), quotes | Same | I3 | [ ] |
| Adult flags on default screens (Plan AF) | Same | I3 | [ ] |

## Home

| Android | iPhone | Plan | |
| --- | --- | --- | --- |
| Feeds (Continue, featured, readiness strip, downloads card), Add feed, scopes | Same | I2 (featured + Recently read, interim), I3 (feeds) | [ ] I2 part ✓ (interim: featured, Continue) |
| Wander (well-known articles from the index) | Same (replaces I2's featured-only Home) | I3 | [ ] |
| Timeline card, Day page, time scale, Show in time | Same | I3 | [ ] |
| Games (6, random on Home, Games hub, runs of 10) | Same | I3 | [ ] |
| Arrange Home | Same | I3 | [ ] |

## Explore

| Android | iPhone | Plan | |
| --- | --- | --- | --- |
| Landing (editorial, doors), rooms, room screen, subject spreads, broad topic spread, plates, A–Z, quilt map, Through time, Your sources, seen rooms | Same | I3 | [ ] |
| Browse a pack's files | Same | I4 | [ ] |

## Search

| Android | iPhone | Plan | |
| --- | --- | --- | --- |
| Online titles and articles, recent searches | Same | I2 | [x] |
| Best matches across packs and books, Titles, Articles, Books, By kind filters, Show all / fewer | Same | I4 (packs), I5 (books) | [ ] |
| Mic (on-device dictation, title matcher, Did you mean) | `SFSpeechRecognizer` on device / `SpeechAnalyzer`, the same matcher (core) | I6 | [ ] |
| Selected text → Search in Granger (`PROCESS_TEXT`) | ≈ a Share extension | I6 | [ ] |

## Packs and downloads

| Android | iPhone | Plan | |
| --- | --- | --- | --- |
| Get more: Kiwix list (flavours, sizes, plain words), Wikipedia family (how much × pictures), subjects add-ons, signed catalog | Same, CoreKiwix for reading | I4 | [ ] |
| Downloads: one queue, order, Pause all, speed and time left, Home card, Wi-Fi rule, sha256 check | URLSession background sessions, resume by Range/ETag | I4 | [ ] |
| Pack reader and pack search (libkiwix), redirects, pack documents (PDF/EPUB in packs) | Same | I4 | [ ] |
| Import a pack from storage | ≈ Files import, packs visible in Files | I4 | [ ] |
| Book packs as collections (Gutenberg, nautilus, African Storybook), manifests | Same | I4 (packs), I5 (reading) | [ ] |

## Books

| Android | iPhone | Plan | |
| --- | --- | --- | --- |
| Library: reading shelf, collections, Classics shelf, list/grid views, letter strip, organize, covers | Same | I5 | [ ] |
| E-book reader: pages, contents, search in book, highlights and notes, dictionary, text settings, link peek, fixed layout, PDF | Readium Swift (+ PDFKit) | I5 | [ ] |
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
| Show in other apps (documents provider) | ≈ Files visibility (`UIFileSharingEnabled`) | I4 | [ ] |

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
