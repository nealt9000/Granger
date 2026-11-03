# Plan E1: The economy phone (a field tester in Tanzania)

**Goal:** Granger on a 4 GB, 2020 mid-range phone, used off-grid day to day, is a complete library and doesn't feel like a cut-down Pixel. The tester picks the **Efficient** start, adds what his situation needs, and reports back. His phone stands in for the phones in the places Granger matters most, so this tier gets the same finish as the top one.

**Counts toward:** release checklist A (a non-Pixel phone; the device checks for an SD card and read-aloud on a 4 GB phone) and the NLnet application (field evidence on a low-cost phone, off-grid, outside the US).

## The phone

docomo **SO-41A = Sony Xperia 10 II** (2020). From docomo's update pages and a retailer's spec sheet: **4 GB RAM, 64 GB storage, microSD up to 1 TB**, 6.0" OLED 2520×1080, Android 10 at launch, **Android 12** since July 2022 (the last OS update). Chip: Snapdragon 665 (from memory; not in the sources found). It's a Japanese carrier model, so it may be set to Japanese or US English rather than a Tanzanian locale.

What that means for Granger:

| | On this phone | Effect |
|---|---|---|
| RAM | `totalMem` about 3.6 GiB, under `ASK_MIN_BYTES` (4.5 GiB) | **LIBRARY_ONLY**: Ask, EmbeddingGemma and book vectors are hidden (already right) |
| Android | 12 (API 31), minSdk 29 | Installs. Per-app language (13+) isn't there; the in-app setting covers it |
| Storage | 64 GB, maybe 35–45 GB free after the system and his own files; plus a microSD | Efficient is 16.9 GB (9.1 GB with Save space) and the Tanzania map is 0.6 GB: it fits, but the card is where a library grows |
| CPU/GPU | 2019 mid-range | Cold start, long Compose lists (927 packs, 372 Classics), WebView page loads and the first full-text search of a 7 GB ZIM will be slower than on a Pixel |
| Screen | OLED | The Night theme saves battery noticeably |
| Power | Off-grid: solar or occasional charging | Background work (downloads, scans, indexing) has to earn its battery |
| Network | Costly mobile data, often a MiFi or a phone's hotspot | Wi-Fi-only downloads may never start on a metered hotspot; downloads need to survive drops |

## What already works for him (no change)

- Ask leaves the rail; onboarding's This phone and Readiness say why (`onboarding_research_needs_memory`, `readiness_tier_library_only`). The Ask line isn't offered in Pick a start (`OnboardingModel.askLine`).
- Search, the readers, Classics, Listen with the **phone's** voice, music and audiobooks, Readiness, downloads with resume (the part file is renamed into place, so no double space).
- SD card as a download location (`Volumes`, Settings → Storage).
- Send feedback carries the device, the build and "Library only".

## Anticipated limits, and what to do about each

Ranked by how much they would hurt his first week.

### 1. The map suggestion uses the phone's language setting, not where the phone is (bug)
`OnboardingViewModel` and `BrowseViewModel` take `Locale.getDefault().country`. On a Japanese docomo phone in English that gives **US** (the 17 GB map, offered) or **JP**, not Tanzania (0.6 GB, which would be preselected).
**Fix:** country from `TelephonyManager.networkCountryIso`, then `simCountryIso`, then the locale. Unit tests for the order; a Tanzanian SIM in a US-English phone suggests Tanzania. *Small.*

### 2. The Granger voice on a 4 GB phone
Pick a start offers the Granger voice on every tier (`OnboardingModel.voiceLine` has no tier check). Synthesis grows ONNX Runtime's arena by about 420 MB and keeps it; with a WebView, libkiwix and the player service, a 4 GB phone is likely to kill it during long reads. Neal expects it not to work; it hasn't been measured (device check 3 is still open).
**Do (the fallback device-checks.md already names):** under `ASK_MIN_BYTES`, no voice line in Pick a start, the phone's own voice is the default, and Settings → Voice lists the Granger voices under a "may stop partway" line rather than hiding them, so he can still run device check 3 once and report it. Also check that the phone has an English offline voice in Speech Services by Google (and whether Swahili is offered), and say how to get one if not. *Small to medium.*

### 3. Downloads on Tanzanian networks
- A MiFi or a phone's hotspot often counts as **metered**, so `NetworkType.UNMETERED` never holds and Wi-Fi-only downloads wait forever while the phone is "on Wi-Fi". The F1 fix for "Waiting for Wi-Fi" on Wi-Fi was about a different case.
- Drops in the middle of a 7 GB Wikipedia: resume from the part file needs testing on a flaky link, not only a clean pause.
**Do:** when Wi-Fi is connected but metered, the waiting line says so ("This Wi-Fi is marked as metered") and offers "Use this connection" for the queue; a resume test with the radio cut mid-file (on the Pixel, then his phone). *Small to medium.*

### 4. Getting packs without the internet
For off-grid use the realistic path is often a laptop in town, a USB cable, or a friend's SD card. Today a ZIM is found only inside the app's own `Android/data/app.granger/files/packs` (internal or on the card). That works from a computer over USB, but a phone's file manager can't write there on Android 11+, and nothing tells the user.
**Do now (no feature):** a short "Packs from a computer or SD card" section in his tester note (the exact folder on the card, that the Library picks them up on its next resume).
**Feature (Neal's call, it's past the freeze):** Library → Add a pack from a file: pick a `.zim` (SD card, USB stick through OTG), copy it into the pack folder or use it where it is on the card, check it opens, show it under its catalog entry when its UUID matches. This is what lets one download serve a village. *Medium.*

### 5. Storage: start lean, grow onto the card
- With 64 GB internal and a card slot, onboarding should say the card is there and offer it as the download location before the first big download, not only in Settings → Storage. (Check what onboarding does now on a phone with a card; add a line in Review if not.)
- **Save space** (no pictures) halves Efficient (16.9 → 9.1 GB). Preselect it when the chosen start would leave less than about 10 GB free on the chosen volume, not by RAM: pictures matter for medicine and repair.
*Small.*

### 6. Speed on a 2019 chip
- No **Baseline Profile** (`profileinstaller` isn't a dependency). On a mid-range phone that is the cheapest measurable win for cold start and scrolling in Compose: generate one on the Pixel 6 with a Macrobenchmark journey (launch → Home → Library → Classics list → open a book → Search), ship it with the release build.
- Watch for: the first full-text search of the 7 GB Wikipedia, the Classics grid with covers, Get more's 927 packs, the e-book reader's first page.
*Medium (a new `:baselineprofile` module).*

### 7. Battery off-grid
- Audit what runs without being asked: the phone-media scan on resume, "Make all searchable" indexing, vector downloads (none on this tier), the download queue. Heavy jobs that aren't downloads get `setRequiresBatteryNotLow(true)`; indexing pauses below 20 % unless charging.
- Tester note: Night theme on an OLED screen, and that Granger needs no network to read.
*Small.*

### 8. Memory pressure without Ask
No model, but a 7 GB ZIM, a WebView, the player service and the phone's TTS together on 4 GB. Expected to be fine; it needs checking, not code: the reader survives its renderer dying (F1), the e-book reader's Readium WebViews don't (carried forward from F1). That carried-forward item matters more on this phone than on any Pixel. *Fix if his phone shows it.*

### 9. Swahili content
The catalog has **no Swahili packs** (Kiwix has Swahili Wikipedia and Wiktionary). The app stays in English or Spanish; SW (the full language) waits behind Spanish as planned.
**Neal's call:** list Kiwix's Swahili packs in Get more now, content only (a catalog change, re-signed), so he can add them if he wants them. "A second language is offered only when the phone lists one" already keeps them out of everyone else's way. *Small.*

## His test kit

A one-page addendum to `round-1-tester-note.md` for economy phones:
1. Install from the internal (later closed) track; pick **Efficient** (Save space if storage is short); point downloads at the SD card.
2. What he won't see and why (Ask; Granger voices may stop).
3. What to try over a week: Search, Classics, WikiMed, the Tanzania map, iFixit, Listen with the phone's voice with the screen locked, a download that drops and resumes, a pack copied from a computer.
4. What to send with Send feedback: free space before and after, whether anything restarted, battery per day of use, what he added to his library and why.

His results fill these lines of the release checklist: *a second phone that isn't a Pixel* (fresh install; Ask doesn't apply), *SD card* (device check 2), *read-aloud on a 4 GB phone* (device check 3).

## Order

1. Region from the SIM (1) and the voice on 4 GB phones (2): before his first install, both small.
2. Metered Wi-Fi (3), the card in onboarding and Save space (5), battery constraints (7), the tester addendum: before or with the first build he gets.
3. Baseline Profile (6): before the closed test; it helps every phone.
4. Add a pack from a file (4) and Swahili packs (9): if Neal lifts the freeze for them.
5. Memory (8) and everything else: from his reports.

## Progress (2026-11-03, branch `e1-economy-phone`)

- [x] 1. The suggested map from the network's country, then the SIM's, then the locale (`device/PhoneCountry`).
- [x] 2. Granger voices under 6 GB: not offered in Pick a start; Settings → Voice lists them under a "may stop partway" line.
- [x] 3. Metered Wi-Fi: a Wi-Fi-only download on Wi-Fi Android marks metered says "this Wi-Fi is marked as metered", and Downloads explains with a Wi-Fi settings button. A run that got 1 MB or more before its line dropped starts the retry count again, so a big file on a flaky link isn't given up after 10 drops.
- [x] 4. Get more → Knowledge packs → Add a pack from a file. The picked `.zim`'s header gives its UUID; a file the catalog lists (same UUID and size) is filed as that entry and checksummed, any other ZIM is kept under its own name unchecked (`UNVERIFIED_PREFIX`). The copy goes through the download queue from its `content://` address (resume, progress, space check, notification); a local copy runs with no network (WorkManager `NOT_REQUIRED`, `connected()` in the queue) and never waits for Wi-Fi. The read grant is kept, so a copy resumes after a restart.
- [x] 5. The start goes to the SD card when it has more free space than the phone; Save space starts on when the start with pictures would leave under 10 GB free and the one without fits. The first start stores the volume, so later downloads go there too.
- [ ] 6. Baseline Profile: waits for Neal (generating one installs a benchmark build on the Pixel 6).
- [x] 7. Battery audit: nothing to change. The only background work is the download queue, which the user starts; book indexing runs in the app's scope while it is open, and Make all searchable asks first. No `setRequiresBatteryNotLow`: a download the user asked for in town shouldn't stop at 15 %.
- [ ] 8. Memory: from his reports.
- [x] 9. Swahili packs: `swa` in `kiwix.languages` and curated rows naming the language (Wikipedia with and without pictures, Wiktionary, PhET), plus five freeCodeCamp packs; no tiers. The rebuild moved no other pack (0 snapshot changes, every other entry identical), all 2,410 links answer, re-signed. Kiwix's Ubongo (Tanzanian children's educational videos in Swahili) has no family-table row: a licence check, and video packs are after launch.
- [x] Tester addendum: `docs/friend-testing/economy-phone-note.md`.

## Decisions (Neal, 2026-11-03)

- **Add a pack from a file: now**, a freeze exception for the closed test (item 4).
- **Swahili packs in Get more now**, content only; the app's text stays English or Spanish (item 9).
- **The Granger voices on phones under 6 GB: listed in Settings → Voice with a "may stop partway" line**, not offered in Pick a start; the phone's own voice is the default (item 2).

So the order's step 4 moves up beside step 2.
