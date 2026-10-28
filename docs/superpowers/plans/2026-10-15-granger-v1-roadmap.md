# Granger v1 — Plan Roadmap

Spec: `docs/superpowers/specs/2026-10-15-granger-v1-design.md`

The spec spans nine milestones across independent subsystems. Each milestone gets its own implementation plan, written when the previous one has landed, so every plan builds on real code and measured numbers instead of guesses. Each plan ends with working, tested software.

| # | Plan | Delivers | Status |
| --- | --- | --- | --- |
| 1 | Foundation + model spike | Toolchain, Gradle project, `:app` + `:core:ai`, `DeviceTier`, `LlmEngine` over LiteRT-LM, debug spike screen, measured Pixel 6 results and decisions | **Written:** `2026-10-15-plan-01-foundation-model-spike.md` |
| 2 | Reader | `:core:zim` (java-libkiwix `org.kiwix:libkiwix`), test ZIM, `:core:library` (Room), Library screen, WebView reader, title + full-text search, bookmarks, reading position | **Done:** `2026-10-15-plan-02-reader.md` |
| 3 | Catalog + downloads + Readiness | `tools/catalog-gen`, Ed25519-signed catalog, `:core:catalog`, `:core:downloads` (WorkManager, resume, sha256, volumes), Downloads + Readiness screens | **Done:** `2026-10-16-plan-03-catalog-downloads-readiness.md` |
| 4 | Pack builder + Granger Classics | `tools/pack-builder` (python-libzim), Classics manifest, Classics pack on a GitHub release, catalog entry | **Done:** `2026-10-16-plan-04-pack-builder-classics.md` |
| 5 | Shell + Library | Flame theme + icon, side rail (bookmarks per article, History, Recent), immersive mode, open-to-last-place, media types / collections / filing, Library screens, Granger + Kiwix source screens, Settings (Models, Readiness), polished web-style reader with selection actions, schema migration | **Done:** `2026-10-16-plan-05-shell-library.md` (reader device tests pending) |
| 6 | E-book reader + Classics EPUBs | Readium reader (pagination, text settings, highlights, notes, dictionary, in-book search), `:core:books` (EPUB/TXT/MD, DRM detection; search index is contentless FTS4), My books, pack-builder EPUB output, Classics re-release, Search over e-books | **Done:** `2026-10-17-plan-06-ebook-reader-classics.md` (the 347-book "Download all" not yet run on a device) |
| 7 | Archive.org source | Public-domain filter (1930 or earlier) until waiver, shelves + search, open-only downloads (EPUB, else text / OCR text) with sha1/md5 verify, filing, metadata | **Done:** `2026-10-17-plan-07-archive-org.md` |
| 8 | Research (was Inquire & Abstract) | Old Ask pipeline (rewrite → find → chunk → rank → answer/decline, citation validation, medical banner, Think deeper) plus metadata grounding, cross-source connections, selection entry, kept chats with threaded follow-ups | **Done:** `2026-10-17-plan-08-research.md` |
| 9 | Eval harness | `tools/eval`, ~300-question dataset, metrics, threshold/prompt tuning to the pass bar | **Done, bar not reached:** `2026-10-18-plan-09-research-eval.md` (Research stays debug-only; see below) |
| M1 | Media: players and phone media | Phone media (MediaStore + picked folders) referenced in place, TagLib tags, music player (Auxio-grade), audiobook player (Voice-grade), one mini-player + Player rail tool, music browsing, playlists, Recent plays | **Done:** `2026-10-19-plan-m1-media-players.md` (spec `docs/superpowers/specs/2026-10-19-media-design.md` §2, §3.1, §5–§8, §10; parity `media-parity-checklist.md`: ✅ 39, ⏭ 4, ❌ 8) |
| M2 | Media: sources and downloads | LibriVox, archive.org audio with the rolling filter, sources by media type, media downloads with origin records, "Show in other apps" | **Done:** `2026-10-19-plan-m2-media-sources.md` (2026-10-19; 1236 JVM tests; Pixel 6 walkthrough in `media-parity-checklist.md`) |
| M3 | Media: Search, Research, Auto, widget | Media index in Search, Research media passages + eval check, Android Auto, widget, final Done-means walkthrough | **Done:** `2026-10-19-plan-m3-media-search-research-auto.md` (2026-10-20; 1512 JVM tests; eval and walkthrough below and in `media-parity-checklist.md`: ✅ 43, ⏭ 0, ❌ 8). **The Media project is done.** |
| P1 | Polish | Wiki Back within the tab, the new wiki top bar, one rail list with pins, the toolbar on the left or right; library v9. The logo and full screen are held back (see "Carried forward from P1") | **Done (partly):** `2026-10-20-plan-p1-polish.md` (2026-10-20; 1593 JVM tests; Pixel 6 walkthrough all PASS) |
| K1 | The full Kiwix catalog (English) | Family table with licenses, bulk generator with a report, Recommended + folded categories on the Kiwix screen, filing for new families, 16 MB catalog cap; Wi-Fi installs (`tools/dev/phone-wifi.sh`) | **Done:** `2026-10-20-plan-k1-kiwix-catalog.md` (2026-10-20; 927 packs published; 1602 JVM tests; Pixel 6 walkthrough all PASS). Follow-ups in K2 |
| K2 | Kiwix follow-ups | Category chips on the Kiwix screen, DevDocs nav as a Contents drawer in portrait, library v10: the Kiwix folder and the Maps media type | **Done:** `2026-10-20-plan-k2-kiwix-followups.md` (2026-10-20; 1620 JVM tests; Pixel 6 walkthrough all PASS) |
| R0 | Project 2 spike: retrieval + answerer | Offline ranking replay on `m3-tune-1` (EmbeddingGemma rerank, hybrid, source-kind weights), vocabulary-gap rewrite on the phone, E2B / E4B / Hugging Face answerers compared on the Pixel 6, results and decisions for the project 2 plan | **Done:** `2026-10-20-plan-r0-research-spike.md` (2026-10-21; results in `docs/superpowers/spikes/2026-10-research-spike-results.md`: nothing adopted as is; a dense book index is project 2's lever; tuning-split start 45% grounded / 91% declined; project 2 must write a fresh held-out set) |
| V0 | The voice: engine spike | `:core:speech` with the vendored sherpa-onnx AAR, `VoiceSpikeActivity`: the system voice, Piper (ljspeech, libritts_r) and Kokoro measured on the Pixel 6 (first audio, real-time factor, memory beside E2B, the music mix), a listening session, results and decisions for V1 | **Done:** `2026-10-21-plan-v0-voice-spike.md` (2026-10-21, measured and listened to; results `docs/superpowers/spikes/2026-10-voice-spike-results.md`; spec `docs/superpowers/specs/2026-10-21-voice-design.md`) |
| V1 | The voice: read-aloud in both readers, through the player | `Speech` with the system and sherpa engines, utterances and read sources, `ReadAloudEngine` on a second `AudioTrack`, the voice as a third player mode (carrier item, focus, saved place, library v11), the web reader's page script and the e-book reader's Readium content iterator with sentence marks and Follow, voices in the catalog with unpack, Settings → Voice, Readiness rows, the screen-off acceptance run | **Done:** `2026-10-21-plan-v1-voice.md` (2026-10-22; screen-off run passed, results doc; Neal's Pixel 6 walkthrough passed after three fix rounds: voice ranking, e-book follow, Read aloud from the page's first visible sentence; `Features.READ_ALOUD` on; the ljspeech zip is on release `voices-v1`, catalog re-sign pending) |
| V2 | The voice: a better voice, the mix, the full view, spoken answers | `SpokenText` (years, decades, ranges, units before Piper), the second catalog voice after a licence check and a listening session (clean candidates: `libritts-high` CC BY 4.0 from scratch; `norman`, `kristin`, `kusal`, `john` public domain) with named speakers, pause/stop in the readers, music under the voice with the mix level and the transport driving the voice (`SessionPlayer` over `ForwardingSimpleBasePlayer`), the full-view sheet, spoken Research answers (streamed with the setting on), `ReadAloud.start` for project 4, the rail's live entry, the widget's voice resume | **Done:** `2026-10-22-plan-v2-voice.md` (2026-10-22; 1789 JVM tests; Neal's walkthrough passed after two fixes: the sherpa engine freed under a sentence in flight on a voice switch, and the Music tab's play button stuck on play under the voice. Task 3, the second catalog voice, moved to V3: `libritts-high` is eight times too slow and the medium LibriVox voices sound no better than `ljspeech`) |
| V3 | The voice: a Granger voice of our own | Spike with a trained artefact: a medium Piper model fine-tuned from the public-domain `ljspeech` checkpoint on LibriTTS-R speakers (CC BY 4.0), to get the LibriTTS sound Neal rated best at `ljspeech`'s speed (`libritts-high` is clean but eight times too slow on the Pixel 6; `libritts_r` is `lessac`-derived). Recipe under `tools/voices/train/`, GPU fine-tune, Pixel 6 numbers, listening, then the catalog through V2 Task 3 | **Done:** `2026-10-22-plan-v3-trained-voice.md` (2026-10-26). `vits-piper-en_US-granger_libritts-medium` on `voices-v1` and in the catalog: two speakers, **Maggie** and **Martin** (LibriTTS-R readers 3922, 4535), round 2's epoch 796 (two rounds on RunPod 4090s); Pixel 6 RTF 0.16 screen off, 0.10 on; Neal: good enough to ship, still short of `libritts` on inflection and flow. Speakers through V2 Task 3's plumbing (`granger:<dir>@<n>`) |
| V4 | The voice: a British voice from a teacher model | A voice designed in Qwen3-TTS (Apache-2.0) on the Mac, cloned for steady speaker identity, then distilled into the Piper medium model with `en-gb` phonemes; Neal's pilot picks NorthLondonNanny and SouthLondonProfessor; ships as a new voice id. These are the high-quality voices the Reading tab (U2) waits on to take over audiobook duties | **In progress:** branch `v4-teacher-voice` (pilot tools f9c653b, c0041be); no plan file yet. Runs on the Mac beside the UX work |
| R1 | Project 2: dense retrieval | `:core:text` (one chunker for phone and Mac), `tools/vectors` (the vector set across the nine domains, built on the Mac), `:core:vectors` (int8 search on the phone), `DensePassageFinder`, hybrid ranking, fiction weighting on by default, parallel keyword search, vectors in the catalog. Spec: `docs/superpowers/specs/2026-10-21-research-library-design.md` | **Done:** `2026-10-21-plan-r1-dense-retrieval.md` (2026-10-24; all 20 `.gvec` built; merged and pushed; dense search about 14 s on the full set) |
| R2 | Project 2: books you add, the held-out set, pass or stop | Books imported on the phone are embedded on the phone, the parallel keyword search tuned, tuning-split additions per domain, a fresh held-out set written and graded by a separate subagent, the pass-or-stop scoring against the 80% / 90% bar (spec §8, §13), the media cue words "movement", "piece", "suite" and "chapter" (from R3's walkthrough) | After R1 |
| R3 | Project 2: deep links | An address for every citation kind, article highlights at the passage, better book quotes, media addresses and play buttons, the Pixel 6 walkthrough (spec §7, §13). Independent of R1 and R2; can run in a second session | **Done:** `2026-10-22-plan-r3-deep-links.md` (2026-10-22; 1871 JVM tests; Neal's Pixel 6 walkthrough passed for every citation kind but the moved-page note; findings in "Carried forward from R3") |
| R4 | Project 2: the answer model | Speculative decoding (MTP), thinking off, and a speed screen of the `litert-community` 2–4B models (Qwen3.5, MiniCPM5, SmolLM3, Ministral, Phi-4-mini) against E2B on the Pixel 6 | **Done to Task 3:** `2026-10-23-plan-r4-answer-model.md`. Only E2B reaches first words under 12 s; every 3–4B model was killed for memory or took 15–146 s; MTP doesn't help on this GPU. R5 took over |
| R5 | Project 2: Research built around E2B | Keyword search 8.8 → 1.8 s, the answer in the passages, entities and `YearFacts` (multi-step 4 → 10 of 20), a question that stands alone searched by meaning alone, Think deeper and E4B removed, auto best guess after a decline | **Done:** `2026-10-26-plan-r5-e2b-research.md` (grounded 91/126 on the whole split; merged with R4 and pushed, c2a52eb, 2026-10-27). Check pass dropped; plain rewrite failed the gate, so E2B's rewrite stays |
| H1 | Readiness model | `domain` and `tier` per family in `tools/catalog-gen/kiwix_families.json` (nine domains; essential / comprehensive), Granger Classics by section, `catalog_gen.py` writes them, `:core:catalog` parses them, the Readiness coverage chart (one bar per domain: None / Basic / Comprehensive, tap → Kiwix filtered to the domain), Storage by source, deficiency sentences (old snapshot, no full-text index, empty domain), the integrity rows under "Files". Spec: `docs/superpowers/specs/2026-10-21-home-readiness-onboarding-design.md` | **Done:** `2026-10-22-plan-h1-readiness-model.md` (2026-10-22; 1825 JVM tests, 40 catalog-gen tests; Neal's Pixel 6 walkthrough passed: "solid improvement to Readiness' usefulness". The tiers get calibrated in a later session with the catalog's target size, about 90 GB; see Carried forward; done in H4) |
| H2 | Home | Home as a rail tool (opening the app still resumes; first run, crash and reboot land on Home), the Open-to setting, `home_feeds` (scope × kind × query), Random / On this day / Search feeds redrawn once a day, Continue cards, the readiness strip, first-run defaults | **Done:** `2026-10-22-plan-h2-home.md` (2026-10-22; 1914 JVM tests; Neal's Pixel 6 walkthrough passed: "Nice!", with two changes made on the spot: book cards open a novel from the start and poetry at a random chapter, and Kiwix cards carry the article's lead image) |
| H3 | Onboarding | Welcome (the brand), This phone, Pick a start (Efficient / Comprehensive from the readiness model), Your situation (region → maps and Wikivoyage; use-case chips), Review and start → Home; "Set up suggestions again" in Settings. Billing and the telemetry opt-in stay in Plan 10 | **Done:** `2026-10-22-plan-h3-onboarding.md` (2026-10-22; 1936 JVM tests; Neal's Pixel 6 walkthrough passed every step with two fixes: one line per ZIM flavour, an installed flavour covers the other). Task 5, the Welcome brand, shipped 2026-10-26 with the mark from Neal's sketch C (launcher, themed icon, Welcome, About); the catalog with the onboarding tables is signed on the branch |
| H4 | Readiness calibration | The tiers at about 90 GB (Comprehensive 90.3 GB, Efficient 11.1 GB, plus the region's map): Stack Overflow, Gutenberg LCC-PR and math/stats Stack Exchange leave the tiers, Top 1M replaces the full Wikipedia, Wiktionary moves to comprehensive; tier bytes count one entry per ZIM; `readiness.contains` (a pack inside an installed one counts as held); the presets carry one flavour per ZIM. From "Carried forward from H1" and H3. The walkthrough added: each By source row opens to its own files (the Files lists go) | **Done:** `2026-10-22-plan-h4-readiness-calibration.md` (2026-10-23; 1949 JVM tests, 50 catalog-gen tests; Neal's Pixel 6 walkthrough: "looking better across the board"; see Carried forward) |
| T1 | Field and Night | The brand brief's theme pass (§10 step 3): Field (paper, ink, enamel) and Night (night, gunmetal, lantern, phosphor) as two schemes, Settings → Appearance → Theme (follow the phone by default), every screen and the widget on the scheme, the flame palette gone, Spectral and Courier Prime bundled, the status voice | **Done 2026-10-27:** `2026-10-26-plan-t1-field-and-night.md`. Pixel walkthrough passed; with it came the owl mark (launcher, themed icon, status bar), the Spectral wordmark and tagline, Courier kept for the status line and readouts, the e-reader's Match app theme, and the revised palette (A Field, C Night). 1,861 JVM tests |
| S1 | Slim and Park | Slim (swap a pack for a smaller flavour of the same family, same `zimName`, bookmarks and places kept); Park only if Neal is sold on it (2026-10-21: not yet; the anti-goal is a user thinking they hold something they don't). Spec to write | Needs a decision |
| P2 | The PDF pass | PDFs in the e-book reader (Readium's PDFium adapter: page, outline, bookmarks, place; no selection or highlights), PDF import (encrypted refused), archive.org Get PDF (Text PDF first) and PDF-only items with a 100 MB prompt and Don't ask again, PDFs in Kiwix packs as pack documents, page text in the book index for Search and Research (scans marked Not searchable); library v13 | **Done:** `2026-10-23-plan-p2-pdf.md` (2026-10-24; 2063 JVM tests after the rebase on R1; a 318-page archive.org Text PDF indexes in 31 s on the Pixel 6; merged to main) |
| U1 | The UX pass: audit and spec | Every screen walked on the Pixel 6 with screenshots, both from a fresh install and from Neal's library. Lists the awkward cases from features added one plan at a time, ranks them against the core mission (own the world's knowledge, read it, ask it) and redraws the navigation map: rail tools, Home, Library, Sources, Readiness, Settings depth. It folds in the rough spots already logged in Carried forward (nothing shows active downloads in one place; sources with hundreds of files; Search grouping; edge-swipe discoverability; the Player's tabs) and specs U2 | **Done 2026-10-28:** spec `2026-10-28-ux-audit-design.md` (D1–D6 decided by Neal: rail holds up to 4 open items; Get more + Downloads off the Library header; reading chrome slides away; the model ticked in onboarding as Ask (Preview); Research → Ask; Search by kind). Walk on the Pixel 6, library install + Granger Fresh (`installDebug -Pfresh`); notes and shots in `docs/superpowers/u1-notes.md`, `u1-shots/` |
| U2 | Listen: the reader takes the Player's place | The Player tool becomes **Listen**, with a **Reading** tab and **Music**. Reading is read-aloud of the open book or article as a full player: the sentences, chapters from the book's contents, speed, voice, sleep timer, a saved place per book, music under it. **Audiobooks become passive:** their tab, shelf, Home feed scope, Home default and library type appear only once an audiobook is on the phone. LibriVox stays under Sources as the way to add one, but Home, onboarding and the Player stop promoting it. The audiobook player's tools (chapters, bookmarks, sleep, speed) carry over to every text in the app, and Reading becomes the main way to listen as V4's voices land | After U1's spec |
| U3 | The UX overhaul | The fixes U1 ranks, in one plan or several. It ends with a fresh-install walkthrough that a friend could follow without Neal | Three plans: **U3a first hour** (`2026-10-28-plan-u3a-first-hour.md`, **Next**), U3b find and hold, U3c reading (spec §5) |
| F1 | Friend build | Release signing (the upload key backed up like the catalog key), a version code from the build, and R8 checked against Readium, libkiwix, sherpa-onnx and LiteRT-LM. A Play Console app with an **internal testing** track (up to 100 testers). **Research on in release builds as a Preview**: a Preview tag and a "can be wrong" line, on ASK-tier phones only, with the models listed in release (`includeUnevaluatedModels`, or `evalPassed` for the friend catalog). A local crash log and Settings → Send feedback (an email with the device, the build and the log; no telemetry). A tester note: what to try, known issues, sizes before mobile-data downloads. The Play policy check for archive.org and non-commercial packs moves here from Plan 11. A fresh-install run on a second phone that isn't a Pixel 6 | After U3 (Neal, 2026-10-27: Play internal testing, Research as Preview) |
| F2 | Friend round 1 | Friends on the internal track, one place for their feedback, findings triaged into this roadmap, round 2 scoped | After F1 |
| I0 | iPhone: spike | Each dependency's iOS path: libkiwix (Kiwix's own iOS app ships it), Readium's Swift toolkit, Gemma E2B on iOS (LiteRT-LM or MediaPipe), sherpa-onnx, Room/SQLite, background downloads (URLSession), AVFoundation players, phone media (no MediaStore: Files imports and the Music library's non-DRM items), CarPlay, WidgetKit. The architecture choice: a shared Kotlin core through Kotlin Multiplatform (`:core:research`, `:core:text` and `:core:vectors` are already pure Kotlin) with SwiftUI or Compose Multiplatform screens. The minimum iPhone (the Pixel 6's role), the Apple Developer Program and TestFlight. E2B and the voice measured on a real iPhone. Spec and decisions | **Now**, beside U1 (Neal, 2026-10-27). Desk research needs no phone; the measurements need an iPhone |
| I1+ | iPhone: the port | Plans written from I0's decisions, built on the overhauled UI; TestFlight for friends | After F2 |
| 10 | Monetization + onboarding + telemetry | `:core:billing` (Play + F-Droid flavors), **Supporter instead of Pro** (brand brief §6, 2026-10-23: one-time, extras only, import free for everyone; replaces import gating), onboarding, `:core:telemetry`; no storage cap (Neal, 2026-10-19: no limit but the phone's storage; remove the unused cap plumbing) | |
| 11 | Beta | The Play listing and closed or open testing on to production. F1 takes signing, the internal track and the policy check. Check the current rule for new personal developer accounts: a closed test with a minimum number of testers for a minimum number of days before production access | After F2 and Plan 10 |

Media spec (2026-10-19): `docs/superpowers/specs/2026-10-19-media-design.md` replaces the old "After v1: Media" row. Media (M1–M3) is built before Plans 10 and 11. It is the first of four projects, in this order (spec §1):

1. **Media** (done 2026-10-20, M1–M3): music and audiobook players, mini-player, phone media, LibriVox and archive.org audio, media in Search and Research retrieval.
2. **Research deep links + knowing your library**: results open at the exact passage, track or section; grounding quality (Plan 9 ended at 37% grounded); how Research becomes expert on any user's library. Spec (approved 2026-10-21): `docs/superpowers/specs/2026-10-21-research-library-design.md`; plans R1–R3.
3. **The voice**: one on-device speech engine for read-aloud in both readers and for Research answers; music/voice mix. Spec (2026-10-21): `docs/superpowers/specs/2026-10-21-voice-design.md`; Neal moved it ahead of the Home, Readiness and Onboarding work (2026-10-21). V0 is the engine spike (done); V1 is done (2026-10-22); V2 is written: `2026-10-22-plan-v2-voice.md`.
4. **Actions**: Research builds reading plans and playlists, and opens, reads aloud or plays things.

Between projects 3 and 4 (Neal, 2026-10-21): **Home, Readiness and Onboarding** (spec `docs/superpowers/specs/2026-10-21-home-readiness-onboarding-design.md`; plans H1–H3), then **Slim and Park** (S1) if chosen. Onboarding left Plan 10 on 2026-10-21; billing and the telemetry opt-in stay there. The brand decision (the Vitruvian draft on `p1-brand` against the fan-mark package at `art/android-rebrand/`) gates H3's Welcome screen. **2026-10-23:** the brand and vision brief (`docs/superpowers/specs/2026-10-23-brand-vision-design.md`) sets the direction: ownership first, preparedness as the proof, field-kit visuals (enamel, gunmetal, field paper, lantern, phosphor) from the field-recorder concept; both drafts are retired; the mark is next in Claude Design. **2026-10-26:** the mark is chosen (Neal's sketch C, redrawn) and H3 Task 5 put it on the launcher and Welcome; the theme pass is next. Brand art moved from `art/` to dated folders in `docs/superpowers/brand/`. Its §10 orders what follows: H3 Task 5, a theme pass (Field and Night), the Plan 10 rewrite, Plan 11's listing, then Basecamp.

Video and the PDF pass, from the old row, are not part of these four projects (spec §2 non-goals).

### Next phase: friend testing (roadmap review, 2026-10-27)

Neal's goal is an app that's shareable for friend testing, with the iPhone on the roadmap. Many features have landed one plan at a time and left awkward UI cases, so a UX pass comes before any friend sees the app. The order:

1. **T1 walkthrough and merge.** The UX pass builds on the Field and Night theme and the owl mark, not the flame palette.
2. **U1, the UX audit and spec**, with **I0, the iPhone spike**, alongside it. I0 is desk research and Mac work until it needs an iPhone, so it doesn't compete for the Pixel 6.
3. **U2 (Listen) and U3 (the overhaul).** U2 is Neal's call from this review: audiobooks become a passive feature that only shows up once a user adds one, and their place goes to the reader's voice. That voice takes over most audiobook duties across books and all other text in the app once the high-quality voices (V4) are ready.
4. **F1, the friend build, then F2, friend round 1**, on Play internal testing with Research on as a Preview.
5. Then the iPhone port (I1+), Plan 10, Plan 11, R2 and S1, in an order F2's findings set. Project 4 (Actions) comes after the beta.

V4 continues on the Mac throughout. Its voice reaches the Reading tab whenever it ships, as a new voice id. Plan 10's telemetry opt-in stays in Plan 10. F1's crash log and Send feedback stay on the phone until the user sends them.

Development moved to Neal's Mac Studio with M1 (setup: `tools/dev/MAC-SETUP.md`).

Library redesign spec (2026-10-16): `docs/superpowers/specs/2026-10-16-library-redesign-design.md` amends the v1 spec and set this order.

Plan 1's measurements feed directly into Plans 6–8 (backend choice, model files in the catalog, the latency target, embedder co-residency).

## Carried forward from Plan 3

- The catalog lives at `https://raw.githubusercontent.com/nealt9000/Granger/main/catalog/catalog.json`, signed with the key at `~/.config/granger/catalog-signing.key` (not in the repo; backed up by Neal). Plan 4 adds Granger Classics and OpenStax to `tools/catalog-gen/curated.json`; their packs need a host (a GitHub release asset is the default) since Kiwix doesn't carry them.
- Models are `evalPassed: false`, so release builds list none until Plan 7 flips them.
- Kiwix's Gutenberg packs (`gutenberg_en_lcc-b`, `lcc-pr`) have no full-text index (`hasFulltextIndex: false`, checked 2026-10-16), so Ask can't search them; Granger Classics (Plan 4, built with the index on) covers that ground.
- Plan 6's `ModelManager` finds installed models through the `downloads` ledger: an `INSTALLED` record of kind `MODEL`, file at `filePath`.
- Plan 8 replaces `capBytes = { Long.MAX_VALUE }` in `AppContainer` with `:core:billing`'s cap, and shows the Pro upsell for `EnqueueResult.OverCap`.
- No longer planned (2026-10-19): Neal dropped the storage cap, so there's no limit but the phone's storage. `capBytes` stays `Long.MAX_VALUE` until the unused cap plumbing is removed (left for later in `2026-10-19-plan-m2-media-sources.md`).
- SD cards were tested only in JVM tests (the Pixel 6 has no slot). Try a phone with one before the beta.
- Android 15 limits `dataSync` foreground services to 6 hours a day; a very slow multi-GB download pauses at the limit and resumes when WorkManager next runs it.
- The Ray Charles sample pack's main page (`index`) renders blank: the ZIM's own `_res_/script.js` throws `$ is not defined` because the page never loads jQuery, so its masonry article list never lays out. Articles open and render fine. Reader item for a later plan (Kiwix injects jQuery for such main pages).
- `connectedDebugAndroidTest` uninstalls the app and wipes downloaded packs and models on the phone; re-download after running device tests.
- Device testing a network drop: `adb shell cmd connectivity airplane-mode enable` alone did not stop an in-flight download on the Pixel 6 (it kept growing); add `adb shell svc wifi disable`. With both off the row shows "Waiting for Wi-Fi" and the `.part` file holds its size; on reconnect it resumes from the same size. A force-stop plus relaunch also resumes by itself.
- Kiwix retires snapshots; the catalog pins exact URLs and hashes, so regenerate and re-sign it at least monthly (or schedule it). Downloads of a retired snapshot now fail with a reason and a Retry button.
- Plan 8: `installedPackBytes` counts every pack row, including MISSING ones (e.g. a removed SD card); decide whether the cap should.
- raw.githubusercontent.com caches `catalog.json` and `.sig` separately; for a few minutes after a push the app may report a failed security check and keep the last good catalog.

## Carried forward from Plan 4

- Physics pack: OpenStax *University Physics* is now CC BY-NC-SA 4.0, so it's out. A curation spike picks a replacement before it's built.
- Rebuilding Classics means a new release tag (`classics-<date>`), `pack_builder.py describe` into `tools/pack-builder/releases/granger_classics_en.json`, then a catalog rebuild and re-sign. The monthly Kiwix regeneration leaves the Classics entry alone.
- The Gutenberg cache (HTML + RDF from the aleph.pglaf.org mirror) lives at `~/.cache/granger/gutenberg/`; a rebuild from a warm cache takes a few minutes and fetches nothing.
- Measured: the first Classics build is 83.7 MB, 347 works, 13,194 entries, no page over 150 KB, under the 300–600 MB target because it's text-only. There's room to add works.
- ZIM full-text search has no phrase search (libzim indexes without positions): a famous line like "the quality of mercy" finds its play, but not first; a distinctive word (`Portia mercy`) does.
- Classics presentation defects found on the Pixel 6 (2026-10-16), left unfixed pending the library redesign below: the two Korans both read "The Koran" on the main page (translator not shown when there's no author); some books' Gutenberg CSS overrides the reader theme (`body { background: initial; color: initial }` and link colours load after `classics.css`, so Hamlet shows default blue links on near-black; `.pagenum` sits past the right edge, clipping "[Pg 3]"); full-text snippets start with the nav bar text ("Hamlet‹ PreviousNext ›"); some merged heading-only titles read oddly ("by William Shakespeare — Contents").
- Deferred minors from the Plan 4 review: anchor ids are lost when their `<a>` is unwrapped (65 TOC links land at page top); size-splitting a giant `<pre>`/`<table>` drops the wrapper; build failures other than `CleaningError` don't name the work; seven Canterbury Tales titles show literal `<1>` markers.

### Design pivot raised after Plan 4 (designed: see the library redesign spec)

Neal's direction, 2026-10-16, to brainstorm before the next plan: the library will hold more media types from more sources (Kiwix, Granger Classics, archive.org with a legal disclaimer, the user's own collections). Instead of everything landing flat in the Library, content is grouped into collections: Wikipedia packs nest in a Wikipedia area, and Granger Classics becomes a collection whose works are individual e-books rather than one ZIM read as a website. All e-books, whether Classics, archive.org or the user's own, open in one shared e-book reader. Finished-app picture: opening the app shows where you left off in the book you were reading; a vertical side toolbar has a section at the top with bookmarks to recently opened books and media. This likely reshapes Plan 5 (Import) and the Classics pack format, so the four Classics defects above wait for it.

Deferred, low priority (from Plan 2):
- ~~Opening the reader after the OS killed the app shows the article it was first opened on, not the last page.~~ Fixed by Plan 5: tabs keep the path and scroll position.
- Large media entries are read whole into memory.
- No device test yet for a ZIM truncated or ejected (SD card) while it is open.

## Carried forward from Plan 5

- ~~Rail letter tiles become covers in Plan 6.~~ Done in Plan 6.
- ~~The Inquire & Abstract slot in the rail is switched on in Plan 8.~~ Done in Plan 8, as Research.
- ~~The Classics ZIM stays filed as "Granger Classics" until Plan 6 replaces it with e-books.~~ Plan 6: the ZIM is retired from the catalog; an installed copy shows "Replaced by the Granger Classics e-books" with "Get the e-books".
- The three `ReaderWebViewTest` device tests are written but unrun, pending the owner's go-ahead: `connectedDebugAndroidTest` uninstalls the app and wipes downloaded packs and models.
- Pixel 6 walkthrough, after unlock: moving Wikipedia into a new "Reference" media type and back, the Granger, Kiwix and Models source screens, and About all passed. Not checked on a device:
  - Reboot → Library: a reboot leaves the phone on a secure lock screen that adb can't pass. The unit tests cover BOOT_COUNT.
  - A small-pack download and remove: no uninstalled pack is under 50 MB; the next smallest is 272 MB.
- The rail's edge swipe works only inside a 24 dp wide, 200 dp tall strip at the left-middle (Android's gesture-navigation exclusion limit), and, after the final review fix, also in the immersive reader, where it shows the chrome and rail. Judge its discoverability on a device; tapping the narrow rail's background is the other way to expand it, and since M1 so is tapping its recents, bookmarks or History.
- On Kiwix main pages every tile is a link, and the reader's middle tap ignores links, so a tap on a tile navigates instead of summoning the chrome. By reading the code, the gaps between tiles still toggle it, but nothing else does (Back only goes back a page). Fixed in the final review (see below). On the device, the left-edge swipe brings up the chrome in the reader, and later readers still open immersive.
- `PublishedCatalogTest` expected a four-item Starter preset; it has had five since the Classics entry was added (Plan 4). The assertion is now five.
- The schema-migration check covered the end state only (the migrated database opens and reads). The first open after the upgrade was not reproduced.
- Search groups results as Titles / In article text with the pack shown on each hit, rather than grouping by collection as spec section 4 says. This is a plan-level deviation for Neal to decide: keep it, or regroup by collection.
- The Readiness screen's Library top-bar entry is left as it is: the owner's call.
- System-bar swipes (the transient bars) do not show the reader chrome; only the left-edge swipe and the middle tap do.

### Fixed in the final whole-branch review of Plan 5

- Immersive reader: the left-edge swipe and its gesture-exclusion strip now stay active in the reader and show the chrome and rail, so a main page of links can't strand the user. The page script's click listener runs in the capture phase and ignores handled clicks, headings and `[role=button]`, so collapsible Wikipedia headings don't also toggle the chrome.
- Launch decision: resume requires the pack's file to exist, not only an OK row from the last scan, and `library.refresh()` runs at startup, so a pulled SD card lands on the Library with the missing-pack notice.
- Theme: the remaining Material purple-tinted roles (inverse, surface containers, dim/bright, secondary and tertiary containers, scrim) are warm neutrals and flame tones; tests check contrast and that no role keeps the baseline.
- Follow-up: the edge-swipe request is one-shot, so a reader opened after a swipe starts immersive (checked on the Pixel 6, including rotation).
- Resume no longer fades the Library into the book; a recent search from the rail replaces only a Search that is on top (a reader opened from Search stays on the stack); a tap on a greyed rail tile explains why nothing opened; unused coroutine scope and import removed.

## Carried forward from Plan 6

Released and measured:
- Release `classics-2026-10-17`: 347 EPUB 3 files, 95.2 MB in total, the largest *Les Misérables* at 1.7 MB. epubcheck 5.2.1 reports 0 errors and 0 warnings for every file. The catalog has a `books` array (347 entries) and a `granger-classics` preset, "Granger Classics: all books"; the Starter preset now has 351 items. The old `classics-2026-10-16` ZIM release and its descriptor are left in place, unreferenced, so older builds' cached catalogs still resolve.
- Library database on the Pixel 6 with 5 books indexed: 3,876 KB (library.db 2,972 KB + wal 406 KB + shm 32 KB).
- **Not measured:** "Get all" (347 downloads, 95 MB) time over Wi-Fi and the index size afterwards. The automated tap on "Get all" was blocked by the session's permission check; run it by hand and record the numbers here.

Pixel 6 walkthrough (2026-10-17), after the fixes below: the upgrade kept packs, tabs, History and saved articles; cover grid; Night links in the flame accent (no blue); tap zones 20/60/20, a link in the middle band follows the link; slider label; contents, bookmarks, highlights, notes, colours legible in Day/Sepia/Night; selection menu (Copy · Highlight · Note · Define · Search) closes after each action and works after rotation; in-book search jumps and marks; Search "quality of mercy" → *The Merchant of Venice* under "In books", opens at the passage and marks it; rotation and process death keep the page; imports of TXT (Windows-1252 and UTF-16), Markdown (script shown as text, `javascript:` link inert) succeed, and a PDF renamed `.epub` and a DRM EPUB are refused with their messages; removing a book shows "Removed book" on the rail and in History.

Fixed after the walkthrough (merged `f8082c9`): a crash on the first launch after process death with a book open; the Contents panel's tabs under the status bar in portrait; reopening a book one page early; a search hit landing a page late and unmarked; the rail clipped in landscape (a Plan 5 bug: the cutout inset was taken out of the rail's width); the status bar left showing after switching readers through the rail.

Still open:
- Define with no Wiktionary pack hands the word to another app (`PROCESS_TEXT`); not exercised on the device. Define through a Wiktionary pack is untested on a device too.
- Classics pushed by hand into `books/granger/` show "Get", not installed: installed status comes from download records only.
- A page turn within ~0.3 s before Back is lost (the outgoing reader's reports are ignored once it leaves the screen).
- System Back from a book opened through the rail goes to the Library, not the previous book (rail destinations replace each other).
- Contents panel tab labels are cut off in landscape.
- Imported text books are titled by file name, not by their first heading.
- Book search results are unranked (`LIMIT 400`); TXT files with single-newline paragraphs become one paragraph per chapter; the shared cover cache (4 MB) re-decodes while scrolling a long grid.
- Plan 10: import is Pro-only; queueing the whole Classics preset must be checked against the cap as one group (spec §13); `Features.IMPORT` becomes the entitlement check.
- ~~Plan 7: archive.org books go to `books/archive-org/` with origin `ARCHIVE_ORG`; `Bookshelf.addDownloaded` needs an origin parameter then.~~ Done in Plan 7.
- ~~Plan 8: `BookIndex` and `BookHit` are the e-book retrieval path for Inquire & Abstract; the `INQUIRE` selection action is already wired.~~ Done in Plan 8 (`BookIndex.passages`; the action is now `RESEARCH`).
- The search-grouping call from Plan 5 is still open.
- The four Classics presentation defects from Plan 4 are fixed in the EPUB build (translator in the Koran titles, publisher CSS dropped, page numbers not clipped, clean titles).

## Carried forward from Plan 7

Neal's calls (2026-10-17):
- Items with no EPUB download their text: an uploaded `.txt` first, else archive.org's OCR text (`*_djvu.txt`), labelled "OCR text: may have errors" and converted to EPUB beside it. About 470k public-domain texts have an EPUB; about 9 million have only scans plus OCR text.
- Browse is four fixed shelves: Most downloaded, Technical manuals (`collection:manuals`), Science & engineering, Literature.
- **The public-domain filter is date only (published 1930 or earlier).** The spec's "or marked public domain / CC0" was dropped after the Pixel 6 walkthrough: uploader licence tags put Diary of a Wimpy Kid (2007), a modern surgery textbook and a 2009 solutions manual in the filtered view. Spec §5 and §14 are updated.
- ~~Later idea, not planned: data sources per library category, with archive.org under several (texts, audio, video).~~ Now planned: the Media spec's sources by media type (§3.4), built in M2. Video is still out.

Builder's calls made while building:
- Items archive.org restricts to lending are lend-only ("Borrow on archive.org") even if a file is served openly (spec §5 wording).
- Each archive.org book keeps a small `<identifier>.json` with its archive.org metadata in the internal `books/archive-org/` until it's removed, so a Readiness repair keeps the title and details.
- Removing a book made from text also deletes its `.txt`; text over 20 MB isn't offered (it can't be converted).
- Every request to archive.org hosts, file downloads included, sends `User-Agent: Granger/<version> (+https://github.com/nealt9000/Granger)`.
- The download ledger is Room version 2: `sha256` is renamed `hash`, and `hashAlgorithm` (SHA256/SHA1/MD5) is added; old records migrate as SHA256.

Pixel 6 walkthrough (2026-10-17), all passed after the fixes below:
- The upgrade kept packs, books, rail and resume; a migrated pack record re-verified in Readiness.
- An OCR-text book (History of the Frick Company, 81 KB) and a 62 MB scan EPUB (Steam-engine principles and practice) downloaded, verified, filed under Literature → Archive.org with archive.org's titles, opened, and the OCR text is found by Search under "In books".
- A lending item shows only Borrow, which opens Chrome; the waiver dialog, the unfiltered rerun and withdrawing it in Settings (files stay) work; offline shows "Couldn't reach archive.org" with Retry and the Installed tab still lists downloads.
- Remove deletes the `.txt`, `.epub` and `.json` and the rail shows the book as not available; the item sheet survives rotation.
- Killing the process at 6.8 of 9 MB: WorkManager restarted it after its backoff (about a minute), it resumed from the `.part` file, verified with sha1, and filed the book.

Fixed after the walkthrough:
- The filter change above.
- Search fields (Archive.org, Granger/Kiwix sources, library Search) scrambled and dropped fast typing: their text came back through the view model's combined flow a frame late. They now read Compose state set on each keystroke.
- The Installed tab called OCR downloads "Plain text"; it reads the kind from the metadata file.
- In scan EPUBs a page-wide image inside an indented paragraph ran off the page, and Readium's edge tap couldn't leave that page. Every book page gets one rule, `p:has(img) { text-indent: 0 !important }`; books' own image sizes are left alone.

Still open:
- Readium's edge tap can still be trapped by other content that overflows by less than a page (a wide table, `<pre>`); swiping works.
- Image width relies on ReadiumCSS's non-important `max-width: 100%`; a book that sets `max-width: none` on images can overflow.
- OCR text is plain: page headers and hyphenation from the scan stay inline. PDF-only items wait for the PDF pass (done in P2: Get PDF). No thumbnails in results.
- Archive.org items have no versions, so there is no Updates tab; a file archive.org re-derives later is not offered as an update.
- Plan 11: the Play policy check for this source.
- Minor polish deferred from the reviews: the filtered view's Get re-check and query both use `year`, which some items lack (they need the waiver); the archive tests wait in real time on the IO dispatcher; silent failure when no browser is installed; "PDF: coming soon" sits below the buttons rather than beside Get.

## Carried forward from Plan 8

Neal's calls (2026-10-17):
- **Inquire & Abstract is renamed Research** everywhere in the UI (rail tool, both selection menus, screen title, Readiness); in code `INQUIRE` became `RESEARCH`. Spec §9 is updated.
- Chats are threads with follow-ups: each follow-up searches again with the new question plus the previous one, and the model sees the previous question and a short form of its answer.
- Research from a selection opens a new chat with the selection quoted as source [1]; sending empty asks "Explain this passage and how it connects to the rest of my library."

Builder's calls made while building:
- The Research tool shows at the ASK tier and up even before the models are installed; the screen points to Settings → Models.
- Models are released 2 minutes after leaving Research, or at once when the app goes to the background.
- Book and pack catalogue cards are passages, so metadata questions can be cited; at most 3 passages per source reach ranking.
- **Rerank is off** (`RERANK_WITH_EMBEDDER = false`, `EMBEDDER_BACKEND = GPU` for when it comes back): the rule was "on only if a query plus 12 passages embeds in ≤ 3,000 ms on the faster backend".
- A rewrite keyword phrase ("portia mercy") matches its words in any order, in book chunks, ZIM full text (one search per keyword) and catalogue cards. Before this, phrases had to occur verbatim and most questions found nothing.
- **Lexical scoring (ruled 2026-10-17, Task 9 fix round 1; replaced in Plan 9, see below):** each keyword gets partial credit, the share of its words present as whole words; stopwords don't count. Over the passages a search found, words are weighted by a smoothed idf (`ln((N+1)/(df+1)) + 1`) and each keyword by the weight of its words, so keywords about the question's form ("literature analysis") count for little. The title bonus stays. `lexicalThreshold` is **0.32**. Partial credit alone left the two sets overlapping (Wi-Fi 0.50, Portia 0.50).
- When the rewrite is cut off before its `medical:` line, the medical lexicon decides. A reply containing `NOT IN LIBRARY` anywhere is a decline.

Pixel 6 measurements (2026-10-17; `ResearchBenchmarkActivity`, debug only, `adb shell am start -n app.granger/.research.ResearchBenchmarkActivity`, logcat tag `Granger`, lines `Research:`):
- Embedder, "Who wrote Hamlet?" plus 12 real passages: GPU 4,218 / 4,375 / 4,667 ms (load 2.2 s warm, 6.9 s cold); CPU 9,663 / 9,575 / 10,222 ms (load 1.1 s warm, 3.5 s cold). With 6 passages: GPU 1,959–1,973 ms, CPU 4,014–4,210 ms.
- Send to first answer words, E2B already loaded (7.7–8.1 s to load), lexical ranking: "Who wrote Hamlet?" **6,782 ms, inside the 9.0 s target** (rewrite 2.2 s, search 1.4 s, ranking under 0.1 s, then 3.2 s to the first words). Declines in code take 3.5–4.1 s; declines by the model about 4.5–4.7 s.
- Top cosine of the passages each question found (embedder on): answerable "Who wrote Hamlet?" 0.774, "What does Portia say about mercy?" 0.830, "What is the boiling point of water?" 0.635; unanswerable "routing number" 0.564, "2031 World Cup" 0.637, "library Wi-Fi password" 0.666. **They overlap**, so `cosineThreshold` stays 0.40. Leaving out the boiling-point question, which the phone's library can't really answer (the model declined it), the gap would be 0.666–0.774.
- Top lexical scores. Plan 8's first scoring (whole keywords only): Hamlet 0.50, boiling point 0.50, Portia 0.20, Ray Charles's life 0.40, Ray banned in Georgia 0.17, Hamlet follow-up 0.00; routing number 0.17, World Cup 0.17, Wi-Fi 0.50. Partial credit: 0.75 / 0.50 / 0.50 / 0.78 / 0.33 / 0.50; 0.37 / 0.28 / 0.50. **Final, idf-weighted:** 0.64 / 0.59 / 0.46 / 0.74 / 0.35 / 0.55; 0.27 / 0.28 / 0.30. With the threshold at 0.32 every answerable case reaches the model and the three unanswerable ones decline in ranking. The margin is thin (0.30 against 0.35) and comes from one phone's library.
- The phone has no real Wikipedia pack, only the 3 MB Ray Charles test ZIM and the retired Classics ZIM (still searched, so Classics texts can come back twice: as ZIM articles and as e-book chunks).

Pixel 6 walkthrough (2026-10-17): the Research tool and empty screen; "Who wrote Hamlet?" answered with a [n] chip that outlines its card, and cards open the book or article at the passage; a follow-up carries the previous question into its keywords; an unanswerable question says "Not in your library." and Search instead opens Search with the keywords; the medical banner (after the fix above); Stop keeps what was written; Think deeper shows "Loading the model…" and E4B replies (it quoted the passage and then declined, which led to the decline fix below); Research from a book selection and from an article selection, sent empty, explain the quote; chats are in Recent and reopen intact after a force-stop; killing the process mid-answer leaves "Stopped before an answer." and Retry works; rotating while streaming keeps the turn; a removed book's citation says "That source is no longer in your library." (Hamlet was removed and got again).

Fixed after the walkthrough: the phrase matching, lexical scoring, medical and decline fixes above; preselect scored every passage inside its sort comparator, compiling regexes on each comparison (5–17 s of ranking with about 200 candidates; now under 0.1 s); the last scroll after a turn ran before the stored answer and its cards arrived, leaving the list above the new answer.

For Plan 9 (all done in Plan 9 except the SD-card measurement, which is carried forward below):
- Lexical ranking reaches the model on the right questions but sometimes with off-topic passages: "Tell me about the life of Ray Charles" was answered from the Hank Crawford article's references, and the Hamlet follow-up from a Ben Jonson introduction. "Why was Ray banned from playing in Georgia?" got a misread rewrite (`ray turner, georgia baseball, ban reason…`) and the model declined it. Rewrite quality and passage choice are for the eval.
- Tune `lexicalThreshold` (0.32) and `cosineThreshold` (0.40) on the eval set; the rerank decision above; the 9 s target is met only with lexical ranking (6.4–7 s to the first words for "Who wrote Hamlet?" after this change, once the benchmark's own recording time is taken off).
- Models are still `evalPassed: false`.
- "Search instead" passes every rewrite keyword joined, which the library's Search rarely matches ("world cup 2031 football soccer…" found nothing).
- With rerank off, EmbeddingGemma (165 MB) is still listed in Settings → Models but unused; hide it or say so.
- Answers can carry Markdown (`*Hamlet*`), shown as typed.
- The last scroll once stopped short of a finished turn's source cards (after Retry).
- Any failure reads "The model needs repair". Tell search and storage (IO) failures apart from the model's.
- Enforce the prompt budget at runtime; chars/4 undercounts tokens in non-English text.
- The rail's Research tool does nothing while a kept chat is open (`launchSingleTop` keeps it); only **+** starts a new chat, unlike `openResearch`.
- Gate `Features.RESEARCH`, or flip `evalPassed`, before any release build.
- Measure the per-keyword ZIM search cost on a full Wikipedia on the SD card before the beta.
- Add a JVM test for ranking with `corpus` = all the passages found.
- Only one model use runs at a time, and the wait for it counts toward the rewrite's 5 s timeout, so a question asked while another chat answers can fall back to plain keywords. Start the timeout once the model is free.
- A background release requested mid-turn still runs at the turn's end even if the app is back in front, so the next question reloads the model.
- A medical turn that is stopped or fails before its first words stores the question's medical hint rather than the pipeline's flag, so its banner can vanish once settled.
- `deleteChat` and the Room `turns`/`recent` flows behind `stateIn` still have no error guard; a storage failure there can crash the app.
- No JVM tests for the citation tab-lookup catch or the card-matching dispatcher move.
- Minor polish deferred from the reviews: no `--release 17` on `:core:research`; `HtmlPassages` doesn't split on no-break spaces; `Rewrite.parse` doesn't cap at 6 keywords or reject an echoed placeholder; `Keywords` drops apostrophes (O'Brien → obrien) that passage text keeps; the question is capped at 1,000 characters, but the prompt header isn't length-capped; `Ranking.cosine` doesn't check vector sizes; `Citations.strip` eats newlines before a group; `ModelManager` closes the resident model before checking the requested file exists and doesn't enforce the tier; a large-model reload failure mid-answer fails the turn instead of falling back; `BookIndex.passages` takes its rows without `ORDER BY` and one damaged book drops all book passages; a citation to a vanished reading tab does nothing; `ZimUrls.forEntry` doesn't encode the fragment.

## Carried forward from Plan 9

Results (Pixel 6, Neal's library, graded against `tools/eval/RUBRIC.md`; full notes in `tools/eval/runs/checkpoints/`):

| Metric | Baseline | Tuned (tuning split) | Held-out | Bar |
| --- | --- | --- | --- | --- |
| Unanswerable declined | 89% | 91% | **92%** | ≥ 90% ✅ |
| Answerable grounded | 36% | 45% | **37%** | ≥ 80% ❌ |
| Medical flag recall | 57% | 98% | 95% | 95% |
| Think deeper grounded (multi-step) | — | 3/12 | 0/7 | ≥ 80% ❌ |
| Best guess confidently wrong, E2B / E4B | 14% / 11% | — | 20% / 13% | ≤ 10% ❌ |
| Send to first words, median | 21.8 s | 14.0 s | — | 9 s ❌ |

Decisions (the plan's stop path):
- `evalPassed` stays false for both models; the catalog was not re-signed.
- `Features.RESEARCH = BuildConfig.DEBUG`: Research is in debug builds only.
- `BEST_GUESS_USES_LARGE = true`: neither model meets the good-enough bar, and E4B is closer.
- Rerank stays off (`RERANK_WITH_EMBEDDER = false`); Settings → Models labels EmbeddingGemma "Not used yet".

What Plan 9 changed in Research:
- Greedy decoding (`topK = 1`): runs are reproducible, and the rewrite takes about 2.7 s instead of 9.9 s.
- Ranking: every keyword word weighs 1 (the idf over retrieved passages was removed), an article's opening passage gets `LEAD_BONUS` 0.3 when it matches, the cap is 3 passages per article or chapter (`perDocument`), and `lexicalThreshold` is 0.6. A book's title is only the `TITLE_BONUS` 0.1.
- Book search is BM25 over FTS4 `matchinfo`, at most 4 passages per book; a question that names a book also searches inside it.
- Personal medical questions decline before the search; the medical lexicon matches at word starts.
- The answer prompt answers first, may combine facts, and declines on passages about a different person, year, place or event; prose declines ("the passages do not say…") count as declines.
- Bibliography sections give no passages; the rewrite drops source meta-words (definition, scripture, quote…).
- Startup bug fixed: Research right after launch could say the model won't load, because the installed-models list was still empty.

Why it stops short, and the options for the next planning session (Neal, 2026-10-19: brought there together with how the models become expert on each user's own library, likely trying more model variants from Hugging Face):
- Keyword search can't bridge vocabulary gaps (alcohol ↔ wine, taxes ↔ tribute); options: EmbeddingGemma rerank (test offline first on the recorded `found.jsonl` corpora) and source-vocabulary expansion for named works.
- E2B rarely combines facts across passages; options: E4B as the answerer (unmeasured), other models, fine-tuning.
- Or re-scope the bar to "the answer passage is shown".

Still open:
- Measure the per-keyword ZIM search cost on a full Wikipedia on an SD card before the beta.
- iFixit (`ifixit_en_all`) has no full-text index, so Research can't search it.
- A personal medical decline shows "Not in your library."; it should say to ask a professional.
- Latency is 14 s to the first words against the 9 s target. With both Best guess models in use the next question reloads E2B (21.6 s).
- Plan 10: packs marked non-commercial (spec §10a) never count toward the Free cap.
- Plan 11: the Play policy check covers non-commercial packs as well as the archive.org source.
- Minor findings from the final review (Research ones are debug-only):
  - `Citations` treats a first sentence like "The ruins were not found until 1911 [1]." as a decline; skip the decline phrases when the sentence carries a citation.
  - The wider medical lexicon flags "Doctor Zhivago", "computer virus" and "Tropic of Cancer", which then get the banner and no Best guess.
  - `AnswerText` italicises the middle of "2*3*4" and drops the asterisks.
  - iFixit's catalog description is Kiwix's summary cut off at "Let's …", now visible in the Kiwix listing; give it a curated description.
- The eval needs the phone: `tools/eval` runs over adb (Wi-Fi works), about 70 s a question. The held-out split stays unread by whoever tunes.


## Carried forward from M1

Built and measured (2026-10-19, on the Mac Studio; parity checklist: `media-parity-checklist.md`):
- **1046 JVM tests pass** (`./gradlew testDebugUnitTest`; 835 before M1's code, after the rename below).
- Device tests on the Pixel 6, which install only their own test APKs (`app.granger` stayed installed each time): `:core:taglib:connectedAndroidTest` **5/5** (`TagLibDeviceTest`: MP3, FLAC, Ogg, Opus, M4A, untagged, broken); `:core:player:connectedAndroidTest` **2/2** (`GaplessDeviceTest`: the LAME header gives encoder delay and padding; no run of silence over 5 ms at the boundary).
- Pins: Media3 1.11.1, NDK 30.0.16248370, TagLib v2.3.2 (git submodule, built static). Library schema v7: 11 new tables, `MIGRATION_6_7`.
- Parity with Auxio and Voice (spec §6, §7): 51 rows, ✅ 39, ⏭ 4 (Android Auto ×2, widget, Search: M3), ❌ 8 (listed below).

Neal's calls (2026-10-19, during the walkthrough):
- The Player has **Music | Audiobooks tabs**. Each shows its own saved state; only one plays at a time, and pressing play on the other tab switches to it. Each player links to its shelf at the bottom.
- Music tabs run Albums, Artists, Songs, Genres, Playlists, Folders, and **Albums is the default** (the plan had Songs first).
- In the narrow rail, tapping the recents, the bookmark tiles or History **opens the full rail**, where they have titles; only the tools go straight to their screens. A long press on a bookmark tile still offers to remove it.
- **M2 requirement: an album downloaded from archive.org stays one album by its origin item** (the item's album and artist metadata), whatever its files' tags say. Test case: Holst, *The Planets*, LSO 1922–24. Its six untagged files group by folder and "Venus" carries its own title as its album, so on the phone it splits in two. A folder-merge rule for phone files was declined: it would merge unrelated singles in folders like `Download/`.

Builder's calls and deviations from the plan:
- `BookMark` was renamed `BookAnnotation` before any Media code: on a case-insensitive macOS volume `Bookmark` and `BookMark` overwrote each other's `.class` files. The table stays `book_marks`; the Room schema is unchanged.
- An untagged album is named after its folder ("Unknown album" only when the folder is blank), not "Unknown album".
- MISSING tracks are hidden from music browsing (the plan greyed them). Playlists still list them, dimmed; audiobooks are unchanged; nothing is deleted.
- The phone scan leaves out everything under `Android/` and, on API 31+, recordings: MediaStore flagged 63 WhatsApp Business voice notes as music. A folder picked inside `Android/media` is still scanned.
- Embedded covers are stored once, named by a hash of their bytes (not `<fingerprint>.jpg`). A file that crashed TagLib is never read for tags again.
- Android's `c2.android.mp3.decoder` already drops the 529-sample MPEG decoder delay, so `ReplayGainProcessor` takes it off the encoder delay for that decoder.
- The reader's mini-player is drawn over the page while the reader chrome shows (the spec says the slot pushes content down). Agreed with Neal.
- Review fixes: a restored book keeps its finished mark until it's played; a saved book position whose file is lost is kept until the user picks a place; a queue where every item fails in a row stops instead of looping, and a failing book pauses; a rescan that dies with an `Error` ends as Failed; play pressed during a transient loss asks for focus again.

Pixel 6 walkthrough (2026-10-19), after the fixes below; Neal listened for everything audible. Installing over the existing app kept his library (DB backed up first). The phone's music and audiobook appeared, and the 91 files under `Music/` and `Audiobooks/` were unchanged (size, mtime, md5). The audiobook resumed after a force-stop (killed at about 1:02 in chapter 2, reopened paused at 0:58). Audiobook → music → audiobook lost no position. *The Wall* (FLAC) played seamlessly. The mini-player was the same across Library, Search, Research and the reader. A phone call paused the book and the end of the call resumed it. The full players were readable, and the Player tabs, shelf links, Albums-first tabs and folder-named albums all passed. Skipped by Neal: deleting and restoring a file in the Files app (covered by `syncInsertsThenMarksAbsentRowsMissing` and `missingRowComingBackIsOkWithStateKept`). The short gap heard in *The Dark Side of the Moon*, "Brain Damage" → "Eclipse", is in the source: `Eclipse.mp3` starts with 2,950 zero samples (about 67 ms) after the decoder's skip.

Fixed after the walkthrough: the players' icons and text were near-black on the dark background (`57350ab`); WhatsApp audio listed as music (`623faa5`), then showing greyed after the rescan (`3866dbb`); the end of a call didn't resume the book (`c41b868`: the system focus path is tested end to end, a delayed grant is accepted, a user pause cancels the resume, and every focus decision is logged under `GrangerFocus`; the first failure's cause wasn't reproduced); narrow-rail recents (`e995da9`) and bookmarks and History (`b9b15ec`) jumped straight in; a flaky restore test's shared clock (`337376b`). Then Neal's tabs build: `3ab5838`, `d873097`, `dff6f60`, `54cca7d`.

Still open:

Data and scanner:
- Losing the audio permission marks items from picked folders MISSING too (the plan's rule), although SAF grants don't need it; and picked folders aren't scanned at all while the permission is denied (the scanner's gate; Settings says so).
- A third-party SAF provider that returns a null cursor on a passing error leaves that folder's items MISSING until the next good scan (nothing is deleted).
- A file moved within a single scan shows up once, under its new uri. Fine; noted only.
- A playlist row's position is its entry ordinal, which is right only while every entry has a track row (true today: the scanner never deletes rows).
- The artist list takes an artist sort tag only from single-artist tracks.
- Orphaned cover files are never cleaned up (old `<fingerprint>.jpg` files and shared covers nothing uses).
- The TagLib skip list never resets, so a file whose tag read was cut short by any process death is skipped for tags forever.
- Parity gaps, later: original dates are read and stored but not shown or sorted by; no way to exclude folders from the scan.

Player and engine:
- Parity gaps for M3, with the service work for Android Auto: tapping a queue row doesn't play it (`PlayerHub` has no play-at-index call); no `onPlaybackResumption`, so a headset play press after process death does nothing; a single-file book shows no next/previous in the notification, which shows the book's title, not the chapter's.
- `MediaControllerPlayerHub` and `PlaybackService` have no automated test; they're covered only through `PlayerEngine`.
- The focus content type (speech or music) isn't requested again when the mode switches while focus is held.
- Pressing play in the mini-player on a book that just ended, without leaving it, hits the end again at once; reloading it from the book page restarts it.
- A finished book restored after process death loads at the start, but the database says finished until the next save.
- The chapter-lookup rule exists twice, in the app (`chapterAt`) and in the engine (`ChapterMap`, internal); keep them in step.
- Worst-case position loss after process death is about 6 s (a 1 s tick on a 5 s interval).
- ~~After play is refused mid-call, the end of the call doesn't resume.~~ Fixed in `c41b868` (`playDuringACallPlaysWhenTheCallEnds`).

UI:
- No signal when a book's saved place can't be found, or when a queue stops because every item failed.
- Adding a bookmark gives no feedback beyond the list.
- On the Player's inactive tab, starting music writes no Recent row; shuffle, repeat, speed, sleep timer and bookmarks can't be changed there; and a saved book's chapters are read again on each state change rather than live.
- Album, artist, genre, playlist and folder pages aren't scoped to a collection (their routes carry no `collectionId`).
- Playlists reorder through Move up / Move down only, no drag.
- No "move audiobook to collection" from the shelf.
- The "songs" Recent row replays all music by title (its key has no collection or sort).
- Settings scrolls to Music & audiobooks by a fixed item index (5).
- The music Compose screens have no UI tests (view model and DAO only).
- Parity gaps, later: "Add to playlist" isn't in the full player or the queue; only Songs can be sorted; no cover replacement for audiobooks (M2's LibriVox downloads bring their own covers).

Device checks still to do:
- Deleting a file in the Files app, then restoring it (skipped in the walkthrough).
- ~~Resume after a reboot (M2's Done-means, with a LibriVox book; never `adb reboot`).~~ PASS in M2's walkthrough.
- With nothing loaded, no media notification or foreground service appears (the hub binds the playback service at launch).
- The media notification relies on Android 13+'s media-session exemption: `POST_NOTIFICATIONS` is only requested before downloads.
- The notification and lock screen, Bluetooth and headset buttons, headset autoplay, ReplayGain, volume boost and an external equalizer haven't been tried one by one; nor the queue drag and the chapters, speed, sleep and bookmark sheets, nor the audiobook shelf and book page beyond the walkthrough's resume checks.
- Gapless was checked only with `c2.android.mp3.decoder` and FLAC; the 529-sample fix is keyed to that one decoder, and other phones' decoders are untested.
- If a call fails to resume again, read the `GrangerFocus` log.
- SD cards (the Pixel 6 has no slot), as for packs.

Environment:
- Development is in `~/Developer/Granger/Granger`. The old copy on `~/Desktop` (perhaps iCloud-synced) is stale: it spawned "X 2" duplicate files in `build/` directories that broke dexing.
- `gradle.properties` still has the Linux machine's limits (2 workers, 2 GB heap, in-process Kotlin); they could be raised on the Mac.
- `:core:zim`'s androidTest assets now include `testdata/audio` (about 200 KB). Harmless.

## Carried forward from M2

Built and measured (2026-10-19, on the Mac Studio):
- **1236 JVM tests pass** (`./gradlew testDebugUnitTest`; 1046 after M1). No new device tests.
- Downloads ledger schema v3 (an AutoMigration adds `groupId`); one WorkManager chain per book or album.

Neal's calls (2026-10-19):
- A slug title (an archive.org item whose title is its identifier) reads from the longest common prefix of its file names: Holst becomes "The Planets Op. 32, H. 125".
- Archive.org spoken word before the waiver shows no results, only a note pointing to LibriVox that also offers Show everything.
- LibriVox at 64 kbps, no setting. Cancel keeps the chapters already in; Remove deletes everything. LibriVox shelves: Recently added plus six genres.
- **No storage cap**: the only limit is the phone's free space.
- During the walkthrough: with the waiver accepted, archive.org audio **searches** cover all archive.org audio, community uploads included (less podcasts, LibriVox and the other kind's curated collections); shelves and filtered searches stay on the curated collections. Album pages number tracks plainly, with "Disc n" headers.

Fixed after the walkthrough: the book page's Play button now pauses the book that's playing (`08a26f1`); album numbering (`5e559ff`); waiver searches match titles and creators and cover all audio (`b4fc1f9`); the book and album pages say "Waiting for Wi-Fi", "Waiting for a connection" or "Retrying" (`4b664bb`, `aa2050e`); removing a download empties the player if it's loaded (`3e8dd71`).

Still open:

Sources and search:
- The Holst item needs the waiver: archive.org dates it by its 2025 upload, and the rolling filter reads `year`, so it stays hidden after 2027 too.
- Book (texts) search still matches every field and ranks by downloads, the pattern that broke audio search; consider title and creator matching for texts.
- Audio search ranks matches by downloads, not the website's relevance, so its order can differ from archive.org's.
- Archive.org track titles keep the album prefix ("The Planets Op. 32, H. 125_ I. Mars…"); trimming it was offered, not chosen.
- Archive.org items have no versions, so no updates (as for books).
- LibriVox descriptions keep HTML entities (`&amp;`); tags are stripped, entities aren't decoded.
- An empty text query isn't treated specially; `choose()` doesn't check that the fetched item's id is the identifier asked for.
- A Browse row always offers Get (search results don't say whether an item has files); an item without them gets a notice on tap.
- The same archive.org identifier got under both Music and Audiobooks shares one group id (rare, unhandled). An item got from Music shows as downloaded in Audiobooks' Browse too (status per group); Open on one still downloading in the other view can land on an empty page until its first file is filed.

Downloads:
- `WorkManagerScheduler` has no test (no work-testing dependency). Cancelling or removing one file of a group by its item id doesn't stop a running chain member (its record is gone, so the worker ends GONE and deletes the file).
- A queued file that hasn't started (no bytes, no error) when the network drops shows "Waiting" only if nothing in the group has come in yet; between files the page reads "Downloading".
- Android 15's 6-hour `dataSync` limit on very long books.
- SD cards (the Pixel 6 has no slot); two volumes whose `StorageVolume` is null both key as `primary` (the first wins). The same item on two volumes: the first root wins.
- A crash can leave a stray `.granger-item.json.*.tmp` (a dotfile, harmless). Embedded chapters in downloaded files are ignored (one file, one chapter).
- Readiness's row text says "the file", not "a file".
- The left-over cap plumbing (`capBytes`, `OverCap`) is still there, unused (Plan 10 removes it).

Library and player:
- `MediaLibrary.forget()` deletes a book's file tracks only when the caller passes their ids (`MediaDownloader.remove` does).
- `PlayerHub.unload` empties the whole music queue when any removed track is in it, rather than taking those tracks out.
- An empty Music collection shows "No music yet" on every tab except Playlists. The LibriVox screen and sheet have no Compose tests beyond navigation.
- M1's `unknown|<folder>` album-key collision corner case is left as is.

Show in other apps:
- Files by Google lists Granger under "Other storage" but opens the app instead of browsing it; in debug builds it opens the Spike launcher. Browsing works in Android's own file browser and any file picker.
- Tapping a file there sends it to Drive's quick viewer, which adds `?cpn=…` to the link and so loses its read grant ("problem with file"). Open with another app works. Drive's behaviour, not Granger's.
- The provider root has no search. The canonical-path check isn't pinned by its own test (the shelf-prefix check catches symlinks).

Device checks still to do:
- Deleting a phone file in the Files app, then restoring it (skipped again).
- The Play policy check for LibriVox and archive.org audio (Plan 11).

## Carried forward from M3

Built and measured (2026-10-20, on the Mac Studio):
- **1512 JVM tests pass**: `./gradlew testDebugUnitTest` plus `./gradlew :core:research:test`. `:core:research` is a plain JVM module, and `testDebugUnitTest` doesn't run it. The M1 and M2 counts (1046, 1236) left it out; it has 164 tests now. No new device tests.
- Library schema v8 (`album_notes`). New dependency: Glance 1.2.0 (the widget).
- Eval (`tools/eval/runs/checkpoints/m3/NOTES.md`): media cited 9/10, intrusion 0/3. Tuning split 91% declined, 44% grounded (tune-5: 91%, 45%). The held-out split was not touched.

Neal's calls (2026-10-19 and 2026-10-20):
- A song tapped in Search plays its album from that song. The Music and Audiobooks groups come after Titles.
- Task 10 tuned on the media set and the tuning split only:
  - On a media question, the best media card goes first.
  - "do I have" and "have I got" are now cues; "score" isn't.
  - A multi-word keyword that matches no work is tried word by word.

Fixed in the walkthrough: Media3's `MediaButtonReceiver` was never declared, so a play press after the process died went nowhere (`320021b`).

Built after the walkthrough, at Neal's request (branch `m3-followups-search-reader`, 1533 JVM tests):
- **Search groups show their first results, with Show all** (`04ff58c`, `93d9e53`): 8 for Titles, article text and books; artists 3, albums 5, songs 5, audiobooks 5. The button says "Show all N …", or "Show the first N …" when a fetch limit was reached; a new query folds every group. Titles: each pack is asked for 20 and the packs take turns, up to 50, so the first 8 mix packs.
- **Readers open with the toolbar showing** (`c5546c8`, `47c1864`): opening a reader or coming back to its tab shows the top bar, the collapsed rail and the system bars, with the page fitted beside the rail and below the bars. A middle tap goes full screen; rotation keeps full screen. The rail and mini-player sit beside the page, no longer over it. The insets don't follow the system bars' animation. Readium's own inset padding is off, and the e-book panels no longer leave a strip by the rail.
- Tried and reverted (Neal: both just as jarring on the phone):
  - fixed margins, where the page keeps one size and the bars show over its margins (`6479788`, reverted in `2b7578b`);
  - keeping the reading spot across the resize: a scroll anchor in the WebView, and Readium's locator restored after re-pagination (`be0d3bf`, reverted).

  Toggling full screen still reflows the page, so the text moves on each tap.

Research, raised in the walkthrough (not built): on factual questions, fiction (*Metamorphoses*, *War of the Worlds*) ranks the same as reference works. Weighing the kind of source is project 2's grounding work, measured on the tuning split.

Still open:

Research and the eval:
- "Who wrote Wicked?" answers from the musical's article; there's no "who wrote" cue, because it would also catch the tuning split's who-wrote group.
- unanswerable-031 (an NFL score) answers with an unrelated game since "score" left the cues. That's how it behaved before media.
- The word-by-word fallback runs for every question. Watch for media intrusion in later runs.
- Folder-named albums have no artist, so the model adds "Gustav" to "Holst" from its own knowledge.
- The cue list also catches non-media phrasings ("composed of", "world record", "track and field", "rubber band"). That means a cap of 2 and a lifted card there. Cues read only the question, so follow-ups stay at a cap of 1.
- A card over 1,200 characters loses its tail, including "In your library as", to `AnswerPrompt`'s cut.
- `LexicalScore` doesn't fold accents, but the media gate does.
- A CORRUPT (not MISSING) audiobook still opens from a citation.
- `media.json` has to be re-exported and validated if Holst is re-tagged or moved, or the album-key rules change. The "Venus" album's artist tag is "Release - Topic".
- The Mac's system Python has no pytest. `tools/dev` should say how to make the eval venv.

Search and the media index:
- After a library change, a search can briefly use the previous snapshot. If the library flow itself fails, the index keeps its last snapshot until restart.
- Artist hits come only from album artists. `MediaWork.creator` is raw "; "-joined text.
- There's no Compose UI test for Search's media groups.
- A song tap gives no feedback beyond the mini-player.
- On a cold start, a Search with a query waits for the index's first build.

Player, session and Auto:
- In book mode, a controller's `seekTo(index, ms)` goes straight to ExoPlayer.
- `SessionPlayer.isEngines` compares media ids only, so a re-send of the same queue with another start index is swallowed.
- Tapping the current song's queue row restarts it from 0.
- The queue-row tap has no Compose UI test.
- Artwork tokens depend on the order of the roots, and the key changes on reinstall or clear data, so URIs cached by Auto or the launcher can stop resolving.
- A cover written after the queue loads stays `file://` until the items are rebuilt.
- `Artwork.uri` touches the disk on the main thread.
- Auto doesn't get `notifyChildrenChanged` when the library changes; it refreshes when it browses again.
- Recents whose source is gone are left out. An empty playlist recent is still listed.
- If the engine takes over 5 s to load, Auto reports a failure while playback still starts.
- Only an album, an audiobook and search were played on the head unit. A playlist, next and previous, the queue, and switching players were checked on the phone but not on the head unit.

Widget:
- No `previewLayout`; the picker uses the generic preview, which Neal found usable.
- The cover is decoded on the drawing path (cached, ≤ 300 px). Every change also calls `updateAll` (not profiled).
- The second button's label is "Back" and doesn't follow the skip setting.
- A boot or resize update starts the process, which binds the service and restores the player paused.

Readers and Search after the follow-ups:
- Toggling full screen reflows the page (see above). A better feel may need a different model: a bar that slides with scrolling, or an animated resize. Not chosen yet.
- Titles ask every pack for 20 suggestions per keystroke; not timed on the Pixel.
- `ArchiveViewModelTest.overCapShowsTheLimitNotice` failed once ("Main looper not mocked") and passed on reruns. It looks flaky, and the cap code is due to go in Plan 10.
- Agent worktrees don't check out the taglib submodule: run `git submodule update --init --recursive` in a new worktree before building.

Device checks still to do:
- Deleting a phone file in the Files app, then restoring it (skipped in M1, M2 and M3).
- The Play policy check for LibriVox and archive.org audio (Plan 11).


## Carried forward from P1

Built and measured (2026-10-20, on the Mac Studio):
- **1593 JVM tests pass** (`./gradlew testDebugUnitTest` plus `./gradlew :core:research:test`). No new device tests.
- Library schema v9: `rail_pins`, and `reading_tabs.backStack` (`MIGRATION_8_9`). On the Pixel 6 the real library went from v8 to v9 on first open; both databases were backed up first.
- Built: Task 2 (library v9), Task 3 (wiki Back within the tab), Task 4 (the wiki top bar), Task 5 (one rail list with pins), Task 6 (the toolbar on the left or right).

Pixel 6 walkthrough (2026-10-20), installed over the app (`firstInstallTime` still 2026-10-16). Neal did every step; **all PASS**:
- Back with ← and system Back steps back one article at a time, then leaves the reader. This also holds after a rail switch, after a link to a section of the same article, and after the app is swiped away.
- The wiki top bar: ←, Home, "Search Wikipedia…", ☆, ⋯ (Random article, Find in page), with no page title; it fits beside the rail.
- The rail: one list newest first, pin with the flame dot and the "Pinned" label, the pin survives a dozen newer items, Unpin and Remove for each kind (a removed article stays in History).
- The toolbar on the right: it moves at once, the right-edge swipe opens it, the left edge is still Back, landscape, both readers, and it is kept after a restart.

Builder's calls and deviations (see the plan and its task reports):
- Home sits in the wiki bar itself, not under ⋯. The search box shows while a page loads, so the bar keeps its shape.
- A 7th pin shows a notice ("6 items are pinned already. Unpin one first.") rather than asking which to unpin.
- Remove works for every kind of item. A removed chat or play is hidden (`SharedRailHidden`, SharedPreferences) until it is used again.
- A pinned play is dropped only when its play record is gone, not when its album is; tapping one whose album is gone shows the "can't be played" notice.
- Start/End insets stand for left/right because the app has no `supportsRtl`. Revisit this if RTL is ever added.

Held back:
- **Logo (Task 1):** a draft is on the unmerged branch `p1-brand`: the traced Vitruvian Man in the Neural Flame, the launcher glyph, monochrome, About mark, the 512 px Play icon and `tools/brand/`. Neal is continuing the design in Claude Design. The 6.1 MB Commons scan is not in git (it is ignored; `build_mark.py fetch` downloads it again). Claude's review of the draft: the glyph works at 48 px, but a figure with spread legs in a circle can read as a peace sign. The full mark's trace is scratchy next to the clean lines, the fire sits between the legs rather than under the figure, and the open circle reads as a light bulb. At About's 56 dp it is too busy.
- **Full screen (Task 7):** on hold; Neal is thinking it over. The plan keeps the overlay proposal as a draft. Toggling full screen still reflows the page.

Still open:
- Build setup: Java and the Android SDK aren't on the agent shells' PATH. Use `JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home` and `ANDROID_HOME=/opt/homebrew/share/android-commandlinetools`. A new worktree needs `local.properties` copied in and `git submodule update --init --recursive`.
- Robolectric's WebView doesn't fire page callbacks. The step that clears the WebView's history after loading from the stored stack is covered only by the device walkthrough.
- The rail's scroll position resets when the side is switched (expected).


## Carried forward from K1

Built and published (2026-10-20, on the Mac Studio):
- **The catalog lists 927 Kiwix packs**: the 18 curated ones under Recommended, plus 909 more from 1,311 English entries. It is 0.92 MB, re-signed and live. 384 video packs are held back. `catalog/kiwix-report.txt` lists every family kept or held.
- The family table is `tools/catalog-gen/kiwix_families.json`: 182 families, each license checked at the source page that states it, with that URL. Neal's calls (2026-10-20): DevDocs is in; families with no stated license are listed as "Not stated by the source"; WikEM is CC BY-SA 4.0.
- **1602 JVM tests pass**, and 28 catalog-gen tests (pytest in a scratch venv; `tools/dev` still doesn't say how to make one).
- The catalog signing key is now on the Mac (`~/.config/granger/catalog-signing.key`, mode 600). It was copied from the Linux machine and checked against `catalog/public-key.txt`.
- Builds go to the Pixel over Wi-Fi: `tools/dev/phone-wifi.sh install` (paired 2026-10-20; MAC-SETUP.md, "Wi-Fi installs").

Pixel 6 walkthrough (2026-10-20), installed over Wi-Fi with both databases backed up first; **all PASS**:
- The Kiwix screen: Recommended, categories, Show all / Show fewer, search, scrolling during a download, Installed and Updates.
- A Stack Exchange site, a maps pack, two DevDocs packs and a community wiki downloaded and opened. Neal: the maps work "impressively well out of the box, full screen or no".

Raised in the walkthrough (planned in K2):
- The Kiwix list needs a category filter at the top.
- DevDocs packs: the pack's own left nav bar takes half the page in portrait (fine in landscape).
- The Library: every Kiwix pack goes in a Kiwix folder, so the top level stays clean. Maps get their own media type (Neal, 2026-10-20).

Still open:
- **Video:** the held families are in `catalog/kiwix-report.txt` (TED alone has 357 packs; also CrashCourse, Khan Academy, VOA, the YouTube channels). To list them:
  - remove their `hold`;
  - check on the Pixel that the WebView plays their HTML5 video (TED packs use video.js with `.webm`);
  - have `Filing.defaultMediaType` return the video type for them.
  
  Kiwix's `_videos:yes` tag can't be trusted (most packs carry it), so the family table decides.
- Kiwix's content server (library.kiwix.org) puts an anti-crawler confirmation in front of pages, so packs can't be inspected online. Download a pack and read it with python-libzim instead.
- The catalog has to be regenerated monthly (Kiwix retires snapshots). The build takes about 5 minutes.


## Carried forward from K2

Built and measured (2026-10-20, on the Mac Studio):
- **1620 JVM tests pass** (`./gradlew testDebugUnitTest` plus `./gradlew :core:research:test`).
- **Library schema v10:** `collections.parentId`, the built-in Maps type and the Kiwix folder (`MIGRATION_9_10`). Before installing, the migration's SQL was run on a copy of Neal's real database. Literature's top level became Archive.org, Granger Classics, My books and Kiwix, with nine collections in the folder. Maps → OpenStreetMap; no placement changed. Both databases were backed up before installing.
- Maps takes the next free sort place, because a type Neal made ("Reference") already held 4.
- Task 3 was built by an agent in a worktree (branch `k2-task3`, merged).

Pixel 6 walkthrough (2026-10-20), over Wi-Fi; **all PASS**:
- The Kiwix chips, and search within a chip.
- DevDocs in portrait: the Contents drawer, with the page starting below the button (the first build covered the title).
- The Library:
  - Literature, the Kiwix folder and its collections, Maps → OpenStreetMap;
  - reading places and bookmarks intact;
  - the indented move dialog; moving a collection out of the folder;
  - a new download filing into the folder.

Builder's calls (see the plan and the Task 3 report):
- The folder opens through `CollectionRoute` and lists its children. It has no menu: the folder can't be moved, renamed or deleted.
- Empty app-made Kiwix collections go in the folder too, so later packs don't land at the top level.
- A child moved out of the folder and back into Literature stays at the top level; filing never re-parents.
- Users can't make folders yet.

Still open:
- Some Kiwix packs are mostly PDFs (irp.fas.org military medicine). They open, but show little until the reader handles PDFs (done in P2: a tapped PDF opens in the e-book reader).
- The DevDocs Contents button stays pinned over the page once you scroll.

## Carried forward from R0

Results: `docs/superpowers/spikes/2026-10-research-spike-results.md` (2026-10-21; working notes in `tools/eval/runs/checkpoints/r0/NOTES.md`).

**Project 2's starting point:** 57/126 answerable grounded (45%) and 69/76 unanswerable declined (91%), on the tuning split with
today's phone library, E2B and the app's ranking. This is a tuning number, not a pass. Of the 69 failed answerable questions,
49 fail in retrieval (35 never found, 14 found but not chosen); a perfect answerer would reach about 61%.

For the project 2 plan:
- **The core of "knowing your library" is a dense book index built ahead of time.** Offline it takes answer-in-top-4 from 3 to
  20 of 35 book questions.
  - Classics vectors are built at pack time and shipped (on the phone the build would take about 20 hours).
  - Books a user adds are embedded on the phone.
  - ZIMs stay keyword-only.
  - Open: dims and quantization against recall, how dense hits share the 4 slots with ZIM hits, and query text (rewrite or raw).
- **The project 2 plan must write a fresh held-out set** (a separate subagent writes and grades it) before any pass-bar claim; the old held-out split is used up.
- **Keep E2B as the answerer and E4B as Think deeper.** No Hugging Face candidate or frontier distill did better on the Pixel 6.
  A Google-built LiteRT-LM file of the E2B QAT checkpoint is worth watching for: equal quality at 0.5 GB less memory.
- **Fiction weighting** (0.7 on non-literature questions) turned 2 wrong answers into declines and changed nothing else. Carry it
  as a ranking default alongside the dense work; it's behind a debug flag now (`ResearchPipeline(sourceWeight =)`).
- **Ruled out:**
  - question-time EmbeddingGemma rerank (+5–7 points for 3–8 s);
  - rewrite alternates (E2B gives modern synonyms, not the source's words);
  - fine-tuning before retrieval is fixed.
- **Deep links** (open at the passage, track or section) go straight into the plan as engineering.

Still open:
- Search time grows with the library: median search went from 8.9 s to 10.9 s after the Hermeneutics Stack Exchange pack was
  added, and send to first words is now 17.4 s against the 9 s target.
- One unexplained replay mismatch (words-002 ranked unweighted on the phone); no outcome changed.
## Carried forward from V0

Measured on the Pixel 6 (2026-10-21, `docs/superpowers/spikes/2026-10-voice-spike-results.md`): Piper through sherpa-onnx at 2 threads has a real-time factor of 0.11–0.14 (0.14–0.15 with E2B resident on the GPU, 2.3 GB peak), first audio 250–400 ms for ordinary sentences and up to 1.6 s for 190-character ones, +420 MB of process memory while speaking; the system engine (Google TTS, 29 offline English voices) is at 0.06 and ≤ 573 ms; Kokoro is 1.5–2.2× slower than real time and slower with more threads. 1638 JVM tests pass.

What V1 carries:
- **The screen-off run is V1's acceptance test.** With the screen dark and no foreground service the process is confined to the little cores and every number was five to ten times worse (Piper at RTF 0.6–2.3). Read-aloud must synthesize under `PlaybackService`'s foreground service and be measured there on the three spike passages before it is called done; if the restriction stays, the look-ahead grows from a sentence to a paragraph.
- **Engines:** system `TextToSpeech` default plus Piper via sherpa-onnx; **Kokoro dropped** for this device class. Threads fixed at 2. Look-ahead one sentence (two when memory allows), optional clause split for a read's first sentence.
- **sherpa-onnx callbacks must be object expressions**, not lambdas: the JNI looks up `invoke([F)Ljava/lang/Integer;` by reflection and Kotlin 2's invokedynamic lambdas lack it (the first run died with `SIGABRT`). `SherpaTts` does this; anything new handed to sherpa must too.
- **Memory:** ONNX Runtime's arena grows to about +420 MB during synthesis and stays until the engine is freed. Gate voice downloads by device tier like models; consider freeing the engine after a long pause; measure on a 4 GB phone when one is at hand.
- **Voices and licences:** the catalog voice is `ljspeech` (public domain). `libritts_r`, `arctic`, `ryan`, `amy` and `hfc_*` are fine-tuned from the non-commercial `lessac` and are out; the second voice is chosen in V1 after a licence check. Each Piper voice carries its own 18 MB `espeak-ng-data`; ship one shared copy. The voice download group is model, `tokens.txt` and the shared `espeak-ng-data`.
- **Listening (Neal, 2026-10-21):** system 2/4/4 (naturalness, names and numbers, pacing), ljspeech 4/2/5, libritts_r 5/3/2 (speaker 2 is male), Kokoro 2/3/3 with a high-pitched noise. So the downloadable Piper voice is the product and the system voice the fallback. Every Piper voice said "rossetter" for Rosetta: the shared espeak-ng phonemizer, so **V1 needs a pronunciation override path** (espeak-ng extra dictionary in the shipped `espeak-ng-data`, or word-level overrides before synthesis). libritts_r pauses at numbers; ljspeech's pacing suits Research answers. The best-sounding voice is the lessac-derived one we cannot ship, which raises the stakes on V1's second-voice licence search.
- **System engine:** `synthesizeToFile` to the cache dir plus `Wav.read` worked for every sentence and `onAudioAvailable` gave real first-audio times, so the file path stays; no need for the pipe variant.
- **Music under the voice** cost more CPU than E2B did (`MediaPlayer` decoding took the voice from RTF 0.11 to 0.18); the voice still has four times the headroom it needs. The mix is heard on the phone only (Task 5).
- **Tooling kept:** `VoiceSpikeActivity` takes intent extras for unattended runs, `tools/spike/voice/run-voice.sh` drives one run, `tabulate.py` makes the tables; the activity keeps the screen on and shows over the lock screen. The activity opens a new on-phone log file on every `onCreate`, so a recreated activity splits its log; logcat's ring buffer dropped the first lines of the eight-minute Kokoro runs. Listening session (Task 5) and Neal's confirmation of the decisions are open; the WAVs are at `/Users/s.nealthompson/Developer/Granger/voice-spike-2026-10-21/wav/`.

## Carried forward from V1

Built 2026-10-21/22 on `worktree-voice-v1` (`2026-10-21-plan-v1-voice.md`; 1730+ JVM tests pass). Neal's Pixel 6 walkthrough (2026-10-22) passed after three fix rounds, each found from the device log: a Kokoro spike build outranked the catalog voice (ranking fixed); the e-book stopped following after one page (the voice-move grace lived in the Compose screen and read zero; a reader opened onto an already-reading voice built no read source); and Read aloud restarted from the paragraph before the page or the chapter's start (Readium's `firstVisibleElementLocator()` is the first element that *touches* the page and its content iterator positions only by that element's selector; `FirstVisibleText` now asks the WebView for the first word inside the page). Neal's own finding: `libritts_r` at 0.8× reads books most naturally; it is the spike's unshippable copy on his phone, which keeps the second-voice licence search alive.

What V1 measured:
- **Screen-off run passed.** Under `PlaybackService`'s foreground service with the screen dozing for eight minutes, Piper ljspeech read 56 sentences at RTF 0.17 (worst 0.21) and the system voice 54 at 0.05 (worst 0.09), with no stall and one sentence of look-ahead. The foreground service lifts the little-core confinement V0 hit.
- **Audio focus from the background is refused** (Android 14+: the app must be on top or already hold a foreground service). The voice starts from a reader on screen, so the first play is safe; anything that starts it from the background (a restore then play, project 4's actions) must go through the media session's play.

What V2 carries:
- **The mix and the transport routing:** music under the voice at the remembered level, transport driving the voice in `SessionPlayer` while music plays, perhaps a notification provider of our own. In V1 starting the voice pauses music.
- **The full-view sheet, the rail entry for a live voice session, `ReadAloud.start` for project 4, spoken Research answers** (the `TextReadSource` is there; `AnswerText.segments` strips the markers).
- **The second catalog voice** after a licence check (from-scratch or permissively trained; `libritts_r` sounded best, reads books best at 0.8×, and cannot ship as is).
- **Text normalisation before Piper, years first:** espeak-ng reads "1799" as "one thousand seven hundred ninety nine" and "1962" as "nineteen hundred sixty-two"; a year pass ("seventeen ninety-nine", "nineteen sixty-two", "two thousand five") is the broad win for articles. `Pronunciations` (respellings) stays but its built-in table is empty: "Rosetta" and "Rozetta" phonemize identically (`espeak-ng -v en-us --ipa`), and every respelling Neal listened to kept the same hard final "ah", which is the ljspeech model, not the phonemes. The espeak-ng extra-dictionary route remains for names.
- **The bottom bar's headphones button is stop, not pause:** Neal used it as pause and got a restart from the page. Worth a pause/stop pair or a label once the full-view sheet lands.
- **Signing:** the ljspeech zip is on release `voices-v1` (2026-10-22, via `gh release create`); the catalog rebuild and re-sign (`tools/catalog-gen/catalog_gen.py build` then `sign`, committed on main where the app fetches it) is the last step for the Get button.
- **Small debts:** the section title of a book comes from the locator's title (the test publication has none); a restored book's `Publication` stays open for the process; the `recent` cache in `ReadAloudEngine` re-synthesizes after a speed change; `advanceUntilIdle` skips background-scope work in kotlinx-coroutines 1.11 (tests advance time explicitly); Readium parses on real threads, so its view-model tests poll; ICU is a stub off Robolectric, so `Sentences` falls back to a punctuation split.
- **Rail and widget:** a `voice:` recent play resumes through `PlayResumer`; the widget's resume ignores the voice (its saved state has no tab).

## Carried forward from V2

Built 2026-10-22 on `worktree-voice-v2` (`2026-10-22-plan-v2-voice.md`), merged the same day after Neal's walkthrough ("years sound better now"; music under the voice works; the Music tab's play button stuck on play and couldn't pause the music under the voice, fixed; "the rest is fine for now").

What V2 settled:
- **No `ForwardingSimpleBasePlayer`.** Its public `seekToNext()` is final and only reaches `handleSeek` when the reported playlist has a next item, so a one-item playlist (the voice's carrier, a single-file audiobook) would swallow next and previous from the notification and the headset. Music under the voice plays on a **second ExoPlayer** instead (`musicUnderFactory`, `musicUnder.volume = mix × duck × fade`); `SessionPlayer` is V1's. The queue moves between the players with its index and position (a short gap at the hand-over).
- **The second voice is not a Piper pick.** Licence check: `libritts-high` (CC BY 4.0, from scratch) is the only clean LibriTTS voice and is eight times too slow (RTF 0.99 screen on, 1.38 off; int8 slower; 4 threads no help); the four clean medium LibriVox voices (`norman`, `kusal`, `kristin`, `john`) sound no better than `ljspeech` to Neal. So V2's Task 3 (the catalog entry) waits for **V3**, a medium model fine-tuned from the `ljspeech` checkpoint on LibriTTS-R.
- **Never free the sherpa engine under a sentence in flight:** a voice switch mid-sentence crashed the process (SIGSEGV in `libsherpa-onnx-jni.so`); `ReadAloudEngine` retires an engine after its producer ends and `SherpaTts` gates generate and free on one lock.
- The text pass is **US spelling** ("kilometers"); bare four-digit numbers 1000–2099 are years unless a unit, a per-cent sign, an ordinal suffix, a currency sign, "#" or "No." says otherwise.

What V3 and later carry:
- **"Australiar", "Rosettar":** words ending in "a" come out with an "r" from `ljspeech`; the phonemes are right (V1), so it is the model. V3's listening check must include a sentence with Australia, Rosetta and area.
- **Music under the voice, the UX:** the mini-player is clunky when both play (Neal); on the Music tab the music under the voice has play/pause and next only (previous, seeking, shuffle, repeat and the queue sheet are inert in that state); the music is not saved on a tick while under the voice (only at the hand-over and the switch), so a crash loses its position since then; a restore after process death brings the voice back alone (Decision 4).
- **Spoken answers:** the medical banner is spoken first only when the pipeline's Medical event came before the first Partial; the last sentence of a streaming answer is held until the text is complete (the spoken form is trimmed, so the trailing-space signal comes from the raw text).
- **Multi-speaker voices:** done in V3 (below).
- **A newer-engine spike** (KittenTTS v0.8, Pocket TTS) stays parked; Supertonic is out (OpenRAIL-M).
- **R3 (deep links, `r3-deep-links`)** touches `ResearchViewModel`, `ResearchScreen` and `GrangerNavHost` beside V2's hunks and adds the same `player: PlayerHub? = null` parameter; it rebases onto this merge.

## Carried forward from V3

Built 2026-10-22 to 2026-10-26 on `worktree-trained-voice` (`2026-10-22-plan-v3-trained-voice.md`; recipe and training story in `tools/voices/train/README.md`).

What V3 settled:
- **Speakers are named by the voice itself:** the model's `.onnx.json` `speaker_id_map` (`export_voice.py --speakers 3922=Maggie`). A voice whose speakers have names is listed once per speaker ("Granger voice (Maggie)"), saved as `granger:<dir>@<n>` (speaker 0 stays `granger:<dir>`, so V1's choices decode); reader numbers are not names, so a LibriTTS voice as trained is listed once. A saved speaker the voice lost falls back to the same voice. The catalog needs no speaker list.
- **The catalog was patched, not rebuilt:** the voice was copied from `curated.json` into the built `catalog.json` and re-signed, so the Kiwix snapshots and R1's vectors didn't move. The next full `build` picks it up from `curated.json`.
- **RunPod:** official PyTorch template with an empty start command; the network volume refuses chmod and Unix sockets (venv and data on the container disk, `runs/` on the volume); a read/write API key on the pod lets it remove itself. About $15 of credit is left.

What V3 carries:
- **A third fine-tune round (Neal, 2026-10-26: "plan for another fine-tune session later"):** the voice is short of `libritts` on inflection and flow. Not the phone: the same samples come out on any hardware. Candidates, cheapest first: a two-speaker stage from epoch 796 on Maggie and Martin alone (the 40 others were for timing and stress); more audio per speaker (the 29 and 19 minutes are all `train-clean-360` has of them; LibriTTS-R's `train-other-500` may hold more, or a reader with more audio takes Martin's place); a larger model would cost the phone speed it now has (0.16 against the 0.5 bar). A new round ships as a new voice id (a catalog asset never changes).
- **Default voice:** `ljspeech` stays first when nothing is chosen (`InstalledVoices.CATALOG_VOICES`); whether Maggie should be the default is Neal's call after living with it.

## Carried forward from H1

Built 2026-10-22 on `h1-readiness-model` (`2026-10-22-plan-h1-readiness-model.md`; spec `docs/superpowers/specs/2026-10-21-home-readiness-onboarding-design.md`). **1824 JVM tests pass** (after the rebase onto the V2 merge, 432dd3d) (`./gradlew testDebugUnitTest` plus `./gradlew :core:research:test`) and 40 catalog-gen tests (`~/.venvs/granger-catalog/bin/pytest tools/catalog-gen`). No schema change, no new dependency. Neal's Pixel 6 walkthrough passed the same day (below).

What landed:
- **The family table** (`tools/catalog-gen/kiwix_families.json`, now 203 rows) carries a `domain` on every row: the nine ids or `null` for storage-only families (games and fan wikis, comics). Sub-rows split Wikipedia's subject packs (medicine, the sciences, history → thought, geography → maps, computer → technology, knots → making, cinema → literature, sports → none), LibreTexts (med, eng, the social sciences) and the Stack Exchange sites (food, making, medicine, science, maps, thought, literature, reference, none; technology is the catch-all) before each family's catch-all. **ZIM names carry no flavour suffix**, so a sub-row regex ends in `$`, not `_` (the first build put every Wikipedia pack under reference).
- **The tiers** live in `curated.json` → `readiness` (spec §3.5) by pack id, with `@granger-classics` and `family:maps` tokens; `catalog_gen.py` validates every token against the built catalog, folds essential into comprehensive, writes `domain` on packs and books (Classics by section: Scripture & religion and Philosophy → thought) and generates the `efficient` (360 items) and `comprehensive` (431) presets. The Kiwix report gained "Listed, by readiness domain". The regenerated catalog: 927 packs (technology 357, maps 205, literature 82, reference 81, science 58, making 25, food 25, medicine 17, thought 17, storage-only 60), 347 books (literature 284, thought 63), 0.98 MB.
- **`:core:catalog`:** `PackEntry.domain`, `BookEntry.domain`, `Catalog.readiness` (`Readiness`, `Domain`); the parser drops tokens it can't resolve and folds essential into comprehensive. `PublishedCatalogTest` pins the nine ids and that no token was lost.
- **`ReadinessModel`** (pure, in `:app`): coverage per domain (None / Basic / Comprehensive; an installed pack resolves by `zimUuid` then `zimName`, so any flavour satisfies a tier item; only `PackStatus.OK` packs, OK Granger books and `INSTALLED` records count), the three signal kinds (empty domain with the smallest missing essential; a pack over a year old with a newer snapshot in the catalog; no full-text index), storage by source (Kiwix, Granger, Archive.org, LibriVox by the `media:librivox:` group, My books from imports, models, voices, and phone media as "not stored by Granger").
- **The Readiness screen:** This phone → What you hold offline (nine rows with `DomainBar`, tap → the Kiwix screen filtered to the domain) → signals (three, then Show all) → Storage (volumes, then By source) → Files (the integrity rows as before). An older cached catalog shows "Refresh the catalog to see your coverage." `ReadinessContent` is the testable composable; the list has the test tag `readiness-list`.
- **The Kiwix screen** takes `SourceRoute(scope, domain)`: one chip with the domain's title and ✕ in place of the category chips, Recommended = the domain's tier items (essential first, uninstalled before installed), the rest by category unfolded; an unknown domain id shows everything.

Builder's calls (spec §10 lists the design ones):
- A `family:` essential (the region's map) gives no "empty domain" signal: onboarding (H3) picks the region, so Readiness has nothing to name.
- A pack listed in two domains' tiers appears once in the generated presets; tier lists may name a pack of another domain (Wikipedia's best articles under thought), and the generator allows it.
- `Source` rows are all returned (eight, in order); the screen hides the empty ones and always shows phone media.
- The Readiness view model now calls `catalog.load()` (the cache; `MainActivity` does too) and takes `bookshelf` and `catalog`.

What the walkthrough measured (Neal's Pixel 6, 2026-10-22, 33 Kiwix packs, 347 Classics, 3 models):
- Eight domains Basic, Technology "No essentials yet · 2.2 GB held · the essentials are 910 MB". Medicine 2.3 GB of 5.5 GB, Reference 16 GB of 71 GB, Literature 82 MB of 21 GB (the Classics). Two signals: Technology's empty domain (Computer by Wikipedia, See → the Kiwix screen filtered) and Haiti and Dominican Republic unsearchable. By source summed to the 37 GB Granger uses (Kiwix 31 GB, models 5.1 GB, Archive.org 356 MB, LibriVox 216 MB, Granger 179 MB).
- Medicine's tap, the chip's ✕, Settings → Readiness and the Library top bar all behaved; a catalog refresh against `main` (older, no readiness block) kept the cached one with no message. No crash.
- Two copy fixes landed from the walkthrough: a domain with packs outside its tiers says "No essentials yet · X held" instead of "Nothing yet" while its bar shows a fill; the Settings entry's line describes the new screen.
- The catalog on the phone was seeded into `files/catalog/` from the branch (signed; newer than `main`'s), since the app fetches from `main`.

~~Neal's calibration notes, for a later session (with the catalog's target size, about 90 GB, "to reach good levels across the domains"):~~ Done in H4 (2026-10-23).
- ~~**Stack Overflow is in Technology's comprehensive tier at 115 GB**, so the bar barely moves at 2.2 GB held; and its topics run well outside technology. It should not count that way.~~
- ~~**Duplicated material inflates the sums.** The tier bytes add every listed flavour: Medicine's 5.5 GB counts WikiMed with and without pictures although installing either satisfies the item (satisfaction is by `zimName`; the bytes are not). The same for a mini pack whose content is inside a bigger one. The fix is to count one entry per `zimName` in the tier bytes (the listed flavour, or the largest) and to decide what a contained pack contributes.~~

Still open:
- The catalog on `main` is still the 2026-10-22 one without domains; the signed one on this branch goes live when the branch is merged and pushed (the app fetches from `main`). R1 Task 10 rebases its `vectors` edit onto this catalog-gen (the R1 session agreed, 2026-10-22; its vector set uses the same nine ids).
- A "Granger Classics" pack record (2026-10-16, 84 MB) shows as Missing under Files on Neal's phone: a leftover ledger row from before the Classics became books; it counts in the Granger source row until it is removed or re-downloaded.
- Signals are capped at three per kind order (empty, stale, unsearchable), not ranked by size; a phone with many unsearchable packs lists them all under Show all.
- Stale uses the download record's `snapshotDate`, else the ZIM's own date; a by-hand pack with a blank date is never stale.
- `DomainBar` isn't measured on the phone (6 dp, a 1 dp essential tick; the tick hides under 1 % or over 99 %).
- The `starter` preset stays beside `efficient` and `comprehensive` until H3 decides.
- The monthly Kiwix regeneration now carries the domains; a new family with no `domain` key is caught by `test_real_family_table_has_a_domain_on_every_row`, and a pack whose domain isn't one of the nine fails the build.

## Carried forward from R3

Built 2026-10-22 on `r3-deep-links` (`2026-10-22-plan-r3-deep-links.md`), rebased onto the V2 and H1 merges; **1871 JVM tests pass**. No schema change (library v11). Neal's Pixel 6 walkthrough the same day, with logs captured:
- **Passed:** an article citation opens at its section with the cited sentence highlighted; a book citation ("take no thought for the morrow") opens at the passage; a selection sent from a book opens back at it; a chapter citation ("the white seal jungle book") shows Play, plays from that chapter and opens the audiobook at it; old chats' citations still open; a removed book ("Ray C.") gives "That source is no longer in your library."
- **Passed, second round:** a song citation ("which song on the Holst album is about war?") shows Play, which starts Mars while Research stays on screen; a tap opens the album with Mars marked.
- **Not checked:** the moved-page note (hard to set up by hand; covered by `CitationOpenerTest.aMovedArticleOpensThePackMainPageWithTheNote`).

What R3 leaves for projects 3 and 4: `PassageAddress` (format/parse; `ChatTurn.addresses()`), `CitationOpener.open(address)` for a bare address, and the fallback chain (spec §7).

Neal's findings from the walkthrough, outside R3's scope:
- **Media needs a cue word to win.** A media card beats the articles only when the question has a media word (`MediaCues`, M3), so "holst mars" and "which movement of the planets is about war?" got wiki cards although the album is on the phone; "the white seal jungle book" reached its card. **In R2** (Neal, 2026-10-22): add "movement", "piece", "suite" and "chapter" to the cue words, with tuning-split questions for them.
- **"What do I have in my library on X?"** ("on medicine" → "not in your library"; "on philosophy" → a generic answer whose first card was a bash doc) is a question about the library, not a passage search. Research has no answer path for it yet; a decision for Neal (R2, H2 or project 4).
- **Vocabulary-gap questions still miss:** "what book has a marooned guy wanting a bit of cheese?" (Ben Gunn, *Treasure Island*) gives "not in your library", and Best guess calls it too vague; "a bit of toasted cheese" finds recipes. This is R1's dense retrieval, not merged yet; worth adding to R2's tuning or held-out set.
- **"Loading model" on nearly every question:** the model unloads after two quiet minutes (`ModelManager.releaseAfterMs = 120_000`); Neal's questions were 2½–6 minutes apart (10 loads in 33 minutes). A longer timer keeps about 2 GB resident longer: a trade-off to decide.
- **Think deeper (the larger model) has never added value for Neal**, as R0 found; whether it stays is open.
- **Research UX:** + doesn't start a fresh, clean chat; Back leaves Research across the app instead of going to the chat list (today the list is reached only by deleting the current chat).
- **V2 follow-up:** with read-aloud on, a speaker icon for the Research chat does nothing when tapped (R3 doesn't touch those lines; the spoken-answer button or the rail's live entry, not yet narrowed down).
- **E-book reader:** copying and pasting a selection is sometimes jittery and buggy.

## Carried forward from H2

Built 2026-10-22 on `h2-home` (`2026-10-22-plan-h2-home.md`; spec `docs/superpowers/specs/2026-10-21-home-readiness-onboarding-design.md` §5), rebased onto the R3 merge (cd3059f) before the walkthrough. **1914 JVM tests pass.** One migration: `library.db` is at **schema 12** (`home_feeds`, `home_draws`; only creates tables). Jsoup is now an `:app` dependency (the day article, page titles, lead images).

What landed:
- **Home is the first rail tool** (`RailTool.HOME`, `HomeRoute`), pushed over the Library root so Back from it lands on the Library, as Search does. `LaunchDecider` sends first run, a crash, a reboot and "nothing to resume" to Home; a last tab whose pack is gone still lands on the Library with its notice. Settings → Appearance → **Open to: Where I left off / Home** (`AppearancePrefs.openTo`, not a separate launch store).
- **The strip:** nine dots at three alphas (None, Basic, Comprehensive) and the first signal, or "Refresh the catalog to see your coverage." / "Ready."; the strip opens Readiness. **Continue** is the rail's tabs and plays (pins first, then newest, eight at most) as cards; no second query.
- **Feeds** are rows of `home_feeds`: `scope` (`library`, `pack:<zimName>`, `family:<family>`, `collection:<id>`, `music`, `audiobooks`) × `kind` (`RANDOM`, `ON_THIS_DAY`, `SEARCH`) × `query`. A feed whose scope names nothing on the phone is hidden, so the default **Quotes** feed sits dormant until a Wikiquote pack is installed. The four defaults are seeded once (`home.seeded`): On this day and Random over `family:wikipedia`, Random over `collection:c:granger-classics`, Random over `family:wikiquote`.
- **Drawn once a day:** `FeedDrawer` seeds its choices from `epochDay xor feedId` and the cards are kept in `home_draws` as JSON for the day; a resume after midnight redraws; a cached card whose pack or book is gone is dropped and the row redrawn. Random over a pack is `randomPath()` five times; over a book collection, poetry, scripture and philosophy get a random chapter (a `BookChunk`) and a novel or play opens at the start or where it was left (Neal); over music, five albums. **On this day** parses the day article (`October_22`, `A/October_22`) with Jsoup: three events, a birth and a death, each opening the article at its section (`ReaderRoute.fragment`); a pack without the article falls back to a search for the date. **Search** takes thirty hits and keeps five, rotating with the day. Kiwix cards carry the article's lead image (`ArticleThumbnails`: first usable `img`, read from the ZIM, kept as a JPEG under `cache/home-thumbs/`); book, album and audiobook cards their own cover.
- **Add feed** is an inline panel at the top of Home (an `AlertDialog` ran out of memory under Robolectric): From (the library, each whole pack, each collection, Music, Audiobooks) × Show (Random / On this day / Search) × a query. A long press on a feed's title offers Move up, Move down, Remove.

What the walkthrough showed (Neal's Pixel 6, 2026-10-22): Home first on the rail; the strip with the Technology signal; Continue with Neal's tabs and plays; On this day over "Best of Wikipedia" fell back to the date search (that pack has no day article; a full Wikipedia has); Random Wikipedia and Random Classics rows; force-stop and reopen kept the cards; a reboot and Open to: Home both landed on Home; adding, moving and removing feeds worked. Verdict: "Nice!", "Works" on every step.

Builder's calls:
- Home is pushed over the Library, not the root (Decision 1): Back from Home is the Library, and the resume stack stays Library → reader.
- The dip-in rule reads the book's `subjects` (the Classics' category is stored there): poetry, poem, scripture, religion, philosophy, quotation, aphorism, essay, proverb. An imported poetry book behaves the same.
- Day-list cards carry no picture (they are lines, not articles); Random, search and fallback cards do. Thumbnails are read for the five kept hits only.
- Scope resolution for `family:` uses the cached catalog's `family`, else the ZIM name's first word; the first whole pack of the family wins ("Best of Wikipedia" before a full Wikipedia, by library order).

Still open (for H3 and later):
- **The strip as a swipeable reader** (Neal): each dot a page you can swipe through for a quick read of that domain. H3's Welcome screen or a Home follow-up.
- **A cover-art scraper** for albums, e-books and audiobooks without art (Neal): Granger has none; a Plan 10-era item with its own licence questions.
- A day feed over a pack without the day article says "October 22" over search hits; a "Mentions October 22" subtitle would be clearer.
- `family:wikipedia` picks the first whole pack of the family; a preference for the fullest (by size) would favour a full Wikipedia over "Best of".
- H3 needs: Start → Home (`StartScreen.Home` exists), the default feeds for a fresh library (they seed on first Home with no packs and resolve as packs arrive), and the strip's first signal as the onboarding nudge.
- The thumbnail cache lives under `cacheDir` and is never pruned by the app; the OS may clear it, and the card then shows its icon.
- Cached draws carry `Cards.VERSION` (1 since thumbnails); a draw from an older format is redrawn, so a feature that changes what a card carries bumps the version rather than waiting for midnight.

## Carried forward from H3

Built 2026-10-22 on `h3-onboarding` (`2026-10-22-plan-h3-onboarding.md`; spec `docs/superpowers/specs/2026-10-21-home-readiness-onboarding-design.md` §6). **1936 JVM tests pass**, 44 catalog-gen tests. No migration. The catalog is regenerated and signed on the branch (926 packs: Kiwix retired one since the morning; the `onboarding` block with seven use cases and ten larger areas); it goes live when the branch is merged and pushed. R1 Task 10 rebases its `vectors` edit onto it (the R1 session agreed, 2026-10-22).

What landed:
- **`curated.json` → `onboarding`:** `useCases` (id, title, blurb, `items` in the readiness token grammar) and `regions.larger`; `catalog_gen.py` validates every token with the same `check_token` the tiers use and every larger id as a maps pack; `:core:catalog` has `Catalog.onboarding` (`Onboarding`, `UseCase`) and drops what it can't resolve. `PublishedCatalogTest` pins the seven ids and that no token was lost.
- **`OnboardingModel`** (pure): the start choices from the `efficient` and `comprehensive` presets with a collection's books folded into one line; one line per ZIM (the larger flavour); regions as the maps family split by `larger`, searchable; each use case's additions beyond what is chosen or installed (`family:` contributes its smallest pack); the review with every item once and the total against free space.
- **`OnboardingViewModel` + `OnboardingScreen`:** one route, five steps (Welcome, This phone, Pick a start, Your situation, Review and start), Skip in the top bar, Back steps back and is Skip on Welcome; "Neither, I'll choose later"; the installed set is the Kiwix screen's rule plus every flavour of an installed ZIM; Start enqueues one `onboarding:<epoch>` group through `downloadSpecOf` (now public in `:core:downloads`, with a `groupId`) and a refusal stays with a notice; `BrandLockup` is the launcher icon's foreground layer and the name until Task 5.
- **Launch and Home:** `StartScreen.Onboarding` when `onboarding.done` is unset, before every other rule; the nav host opens Home then the flow (Decision 1); Home shows a "Setting up · n of m · x of y" strip for the newest onboarding group until it lands (failures say so; tap → the Kiwix screen); Settings → Readiness → "Set up suggestions again".

What the walkthrough showed (Neal's Pixel 6, 2026-10-22; the first-run path ran because the install carried no mark, the re-run from Settings): every step "good" or "works"; the placeholder lockup is fine "ahead of branding"; two things fixed on the spot: Pick a start listed WikiMed with and without pictures (the comprehensive preset carries both; now one line per ZIM, the larger flavour, and an installed flavour counts as holding the other); and **Puerto Rico was not in the region list**: Kiwix has no pack for it, but Neal found it inside the Central America pack with the rest of the Caribbean, so the catalog gained `regions.covered` (per larger pack, the places inside it with no pack of their own: Puerto Rico, Trinidad and Tobago, Barbados, the Lesser Antilles, Cayman, Aruba, Curaçao, Martinique, Guadeloupe, Turks and Caicos, Anguilla, Montserrat, "Caribbean"); searching one finds the pack under the place's name. Neal would like smaller areas partitioned off such packs, which is Kiwix's to do.

Builder's calls:
- The region list is Kiwix's maps family as published: no states, no territories without their own pack, except the places `regions.covered` names (a data table, so the next Caribbean or Pacific find is one line in `curated.json`). A blind "nearest larger area" fallback (Puerto Rico → North America, 20+ GB) would mislead; a named place → its real pack does not.
- `@collection` in a use case means the whole collection (all 347 Classics under Faith & philosophy), not the thought books only as the plan first said; with Efficient chosen it adds nothing, and the Classics are 179 MB.
- The "See" link maps use cases to readiness domains in the view model (`DOMAIN_OF`), not in the catalog: the Kiwix screen filters by domain, and seven fixed pairs don't need a schema.
- The first-run check ran on the phone by the missing mark rather than on an emulator: this Mac has no AVD, and clearing one preference file reproduces a first launch without touching data.

Still open:
- **Task 5, the brand on Welcome**, after Neal's decision (`BrandLockup` and its assets; the launcher icon if the decision includes it).
- Plan 10 slots billing and the telemetry opt-in into the flow (spec §6).
- ~~The readiness calibration session (from H1) also decides what the Comprehensive start should count: today it is the comprehensive preset as generated, with the flavour fold applied on the phone.~~ Done in H4: the presets carry one pack per ZIM and leave out a contained one (Comprehensive about 90 GB).
- A fresh install with no cached catalog shows "Refresh the catalog to see the choices" on Pick a start (tested in the JVM suite, not on a phone).

## Carried forward from H4

Built 2026-10-22/23 on `h4-readiness-calibration` (`2026-10-22-plan-h4-readiness-calibration.md`). **1949 JVM tests pass**, 50 catalog-gen tests. No migration. The catalog is regenerated and signed (926 packs; Efficient 359 items, 11.1 GB; Comprehensive 424 items, 90.3 GB, plus the region's map); it goes live with this merge. R1 Task 10 rebases its `vectors` edit onto it (granger-0d agreed, 2026-10-22).

What landed:
- **The tiers** (Neal's calls, 2026-10-22): Stack Overflow, Gutenberg LCC-PR and math/stats Stack Exchange leave the tiers (they stay in the catalog); Reference's comprehensive Wikipedia is Top 1M without pictures (17 GB), not the full one; Wiktionary moves to comprehensive. Per domain, essential / comprehensive: medicine 1.0 / 4.4 GB, food 0.1 / 4.9, making 3.6 / 12.4, science 2.3 / 13.7, technology 0.9 / 10.9, maps 0.3 / 4.0, reference 2.7 / 32.9, literature 0.1 / 4.6, thought 0.0 / 2.5. The Kiwix report prints these lines.
- **`readiness.contains`** (`curated.json`, `Readiness.contains`, `containers()`): full Wikipedia holds every `wikipedia_en_*`, Top 1M holds Top and the 100, Top holds the 100. A held pack counts as installed for its tier and onboarding, and its bytes count zero in a tier that lists its container. Measured on the WP1 lists: Top 1M holds all of Top but only 18–39 % of a subject pack's articles (83–94 % of their page views), so subject packs are not contained.
- **One pack per ZIM** in tier bytes (the largest listed flavour) and in the presets (the comprehensive flavour in Comprehensive).
- **From the walkthrough:** By source rows open to their own files with Check and Download again, and say "N need attention" in red; the Files header and the Packs, AI models, Voices and Music & audiobooks lists are gone (Neal: the Packs list named every e-book and download on one page). A domain whose bar holds packs outside its essentials says "No essentials yet for …" in its signal, not "Nothing yet".

What the walkthrough showed (Neal's Pixel 6, 2026-10-22/23): Technology reads against about 11 GB, Medicine counts WikiMed once, Reference's essentials 2.7 GB; Pick a start about 11 / 90 GB with Top 1M, WikiMed once and no Stack Overflow; no level fell. The files-under-source rework and the signal wording passed on a second install.

Still open:
- **No easy way to see what is downloading now** (Neal, 2026-10-23). Progress shows per item on the Kiwix and source screens and in the onboarding strip on Home, but nothing gathers the active and queued downloads in one place. A home for it (a Downloads screen off Readiness, a rail badge, the notification as the entry) is a decision for a later session.
- The H1 "Granger Classics" leftover pack record now shows under the Granger source as Missing (one tap opens it) rather than under Files.
- A source with hundreds of files (Granger's 347 Classics) lists them all when opened; there is no search or paging inside a source.

## Carried forward from P2

Walkthrough on the Pixel 6, 2026-10-24 (both databases backed up to `files/db-backup-2026-10-24-p2/`). Passed: the 12→13 migration on Neal's library; `KiwixZimArchiveTest` on the phone (13/13, with the PDF copy-out); import of a real 318-page archive.org Text PDF, a scan and an owner-password copy (refused); place through rotation, process death and a cold start; Contents, bookmarks, Page by page and Scroll, Whole page and Width, Night on text and scan pages; Search "floodproofing" → "p. 12" → opens at p. 12; Research cites p. 128 and p. 204 and opens there; indexing 31 s for 318 pages (bar 60 s), 11 s for a 630-page archive.org PDF, and a kill mid-index resumes on the next launch; archive.org Get · 561 KB and Get PDF · 109 MB, the prompt, Don't ask again and the Settings switch, two books with "(PDF)", Remove takes both; an irp.fas.org pack PDF opens on the first tap after a cold start, Back returns to the article, History reopens it at its page.

Fixed during the walkthrough:
- archive.org OCR writes a line-end hyphen as `¬`; the text joiner now takes it (and soft and Unicode hyphens). "flood¬ / proofing" had been indexed as two words.
- An import wasn't indexed until the next launch (EPUBs too); an import now starts the indexer.
- A PDF bookmark read "Bookmark · 46%"; it is named by outline entry and page. Contents has no Highlights tab on a PDF.
- One foldout page (a 943 pt map in a 607 pt book) shrank every page under Whole page and pushed Width pages off to the right: Readium leaves AndroidPdfViewer's fit at "every page scaled by the widest". Each page is fitted on its own now.
- Research source cards for a PDF now name the page (spec §6.2 had it; the build missed it).
- The 100 MB prompt's "Don't ask again" label didn't tick the box; the whole row toggles.
- zimit packs title a PDF entry with its path; a pack PDF takes the PDF's own title ("Emergency Childbirth") then its file name.
- The archive.org Installed tab's row for an item with text and PDF showed the text's size only; it reads "561 KB + PDF 109 MB".
- Neal removed the Military Medicine pack: its PDF stayed in Home's Continue as "Removed book". A pack's documents now take their tabs, History rows, places and bookmarks with them, and a launch sweep clears any left from before.

Still open:
- A PDF link tapped before its pack's archive is open loads in the WebView and shows nothing. Not reproduced on the phone (the article's own load opens the archive first); a `DownloadListener` fallback would close it.
- Research answered "41,000 acres of saltwater wetlands" from a sentence about acres to be restricted (p. 204; the total is 40,000 on p. 12): an answer-quality case for R4, not a PDF bug.
- Not in this pass (spec §2, §7): read-aloud of PDFs, selection and highlights on PDF pages, in-book search in an open PDF, indexing pack PDFs, OCR, dense vectors for PDF chunks (R2 should take `PdfText` chunks).
- Night and Fit on a PDF rebuild the navigator (PDFium reads both only at layout); Sepia reads as Day on PDFs.
- Home cards now name EPUB chapters by their order among chapters with text ("Chapter n"); the old label used the chunk ordinal, which restarts per chapter.
