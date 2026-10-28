# Plan U3a: The first hour — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** a friend who installs Granger cold can own, read and **ask** within the first hour, without help. Onboarding fetches the catalog by itself, offers the librarian on phones that can run it, splits Your situation into Region (guessed from the phone) and Uses, and starts at once. Research becomes **Ask**, says what it is, can fetch its own model in place, and has a working + and Back. The two wrong summaries (Voice, memory) and the fresh Library's audiobook promo go. Spec: `docs/superpowers/specs/2026-10-28-ux-audit-design.md` §5 (U3a), decisions D4 and D5.

**Out of this plan:** the navigation map (the rail's recents (D1), Get more and Downloads (D2), the Library by kind, Settings sections) and Search (D6): U3b. Reader chrome (D3), the one reader toolbar: U3c. Listen: U2. Release-build model gating (`includeUnevaluatedModels`, `Features.RESEARCH`) and the Preview line for friends: F1. This plan only makes sure that whatever the build's catalog offers, onboarding and Ask use it.

**Architecture (what changes):**
- **`onboarding/`:** the VM fetches the catalog when there's no cache. A new `Step.USES` splits Situation in two. `OnboardingModel` gains an `askLine` (the models Ask needs, from the catalog, on ASK phones) and a `suggestedRegion(locale)`. Review's first heading follows the picks. Start runs off the main thread and shows progress.
- **`research/` → user-facing "Ask":** strings only (title, rail tool, selection menu, Readiness, content descriptions). Class and route names stay `Research*` (D5). The landing gets a one-line intro and a Preview tag. MODELS_MISSING gets an in-place Get that enqueues the models and shows progress. + and Back follow one rule (Task 5).
- **`GrangerApp`:** `installedVoices.refresh()` at start. The memory figure is formatted in one place.
- **`library/LibraryScreen`:** the media hint row goes.

**Tech Stack:** unchanged (Kotlin 2.4.21, Compose Material 3, Room, WorkManager, Robolectric 4.17, JUnit 4 + Truth). Build env: `JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home`, `ANDROID_HOME=/opt/homebrew/share/android-commandlinetools`. A new worktree needs `local.properties` and `git submodule update --init --recursive` (taglib's utfcpp). `./gradlew installDebug -Pfresh` installs **Granger Fresh** (`app.granger.fresh`) beside the library install, for fresh-install checks.

**Roadmap:** row U3 in `docs/superpowers/plans/2026-10-15-granger-v1-roadmap.md`, after U1. U3b and U3c follow; U2 lands after this plan.

## What the code looks like now (surveyed 2026-10-28, branch `u1-ux-audit` at `611c084`, main `6dde87c` + the `-Pfresh` variant)

- **Onboarding** (`app/.../onboarding/`):
  - `enum class Step { WELCOME, PHONE, START, SITUATION, REVIEW }` (`OnboardingViewModel.kt:32`). One `LazyColumn` switches on the step (`OnboardingScreen.kt:122-128`), sharing one scroll state, which is why Situation opened mid-list.
  - **Catalog:** init calls only `catalog.load()` (VM 117-120), which reads the cached `filesDir/catalog/catalog.json`. Nothing fetches on a fresh install, so `noCatalog` shows "Refresh the catalog to see the choices." with a Refresh button (`OnboardingScreen.kt:169-178`). The only automatic `refresh()` is in `SourceViewModel`'s init. The Phone step's "The Efficient start is …" line also hides with no catalog (`OnboardingScreen.kt:156`).
  - **Phone step:** `tierLine()` (VM 175-181) rounds binary GiB ("about 7 GB"), while Readiness uses `formatBytes` ("8.0 GB", `ReadinessScreen.kt:100`); both read `totalRamBytes`. `researchLine()` (VM 183-186) hard-codes "Research, the AI librarian, is coming in a later release; this phone can run it." for ASK.
  - **Region:** `OnboardingModel.regions()` (91-100) gives maps packs sorted by title, split into countries and `catalog.onboarding.larger`. The UI shows 12 countries (40 with a query), then every larger area. There is no locale default; `regionId` starts null (VM 90).
  - **Uses:** `additions()` (103-107) from `catalog.onboarding.useCases`, as `FilterChip` + See, with nothing preselected.
  - **Review:** the first group heading is the preset's catalog title ("Efficient start", `OnboardingModel.kt:125`, from `catalog_gen.py:541-542`), even after the user unticks most of it.
  - **Start** (VM 234-256): `viewModelScope.launch` → `downloads.enqueueGroup(...)`. That makes one DAO read and one upsert per item, then `scheduler.scheduleGroup` loops `WorkManager.enqueueUniqueWork` per record on the caller's (main) thread. The screen leaves only when it's done, with no progress and Start still enabled.
- **Models:** `ModelEntry` (`core/catalog/.../Catalog.kt:130-143`). Gemma 4 E2B is `gemma-4-e2b-gpu`, role SMALL, 2,008,432,640 bytes. EmbeddingGemma is `embeddinggemma-2-text-270m`, role EMBEDDER, 164,626,432 bytes, used when `DENSE_RETRIEVAL` (`SourceViewModel.kt:327`). Both have `evalPassed: false`, so `Catalog.forBuild()` drops them unless `includeUnevaluatedModels` (= `BuildConfig.DEBUG`, `GrangerApp.kt:434`). `downloadSpecOf(ModelEntry)` → `ItemKind.MODEL`, `models/<fileName>` (`Downloads.kt:344-346`). `DeviceTier.ASK` is total RAM ≥ 4.5 GiB (`core/ai/.../DeviceTier.kt`), decided once at `GrangerApp.kt:418`.
- **Research screen** (`research/ResearchScreen.kt`):
  - Title "Research" (254).
  - `MODELS_MISSING` text (88) shown as `Notice(MODELS_MISSING, "Get models", onGetModels)` in the bottom bar (277-281); `onGetModels` navigates to `SourceRoute(CatalogScope.MODELS)` (`GrangerNavHost.kt:833`).
  - The empty chat doubles as the landing: "Recent chats" when there are no turns (318-327).
  - `openResearch` (`GrangerNavHost.kt:525-529`) replaces a Research route on top, so + replaces the chat (Back then leaves Research), and opening a recent chat replaces the landing.
  - The rail tool always opens a new chat (576).
  - There is no `ResearchScreen` Compose test.
- **User-facing "Research" strings:** `Rail.kt:71` (tool), `Rail.kt:130` ("Research chat: …" content description), `SelectionMenu.kt:15`, `ReaderWebView.kt:23` (selection action), `ReadinessScreen.kt:198` ("Library and Research"), `ResearchScreen.kt:254` and :88, `OnboardingViewModel.researchLine()`.
- **Voice summary:** `GrangerNavHost.kt:902-904` reads `container.installedVoices.voices`. `InstalledVoices` (`core/speech/.../InstalledVoices.kt`) starts empty, and only `refresh()` fills it: on a voice install (`GrangerApp.kt:479`), on opening Settings → Voice (`VoiceSettingsViewModel.kt:86`) and on Read aloud (`PlayerEngine.kt:967`). Settings therefore says "No offline voice on this phone" until one of those runs.
- **Library media hint:** `LibraryScreen.kt:199-205` adds `MediaHintRow` ("No music or audiobooks yet", source buttons, Settings) whenever the music or audiobook shelves are missing. The "Archive sources" section below (207-219) lists the same sources.
- **Onboarding's rail:** the shell draws the full rail over onboarding. `railVisible` already exists for the full-screen reader (`GrangerShell.kt`).
- Tests: `onboarding/OnboardingViewModelTest`, `OnboardingModelTest`, `OnboardingScreenTest`; `research/ResearchViewModelTest`, `ResearchOpeningTest`; `shell/RailTest`; `settings/VoiceSettingsViewModelTest`; `library/LibraryScreenTest`. Baseline 2026-10-28: **2066 JVM tests, 6 skipped, 0 failed** (`testDebugUnitTest :core:research:test`, every module's results summed).

## Decisions

1. **Fetch once, quietly, on a fresh install.** The onboarding VM calls `catalog.refresh()` when `load()` leaves no catalog. Pick a start shows "Getting the list of packs…" with a progress indicator while it runs. On failure it shows "Couldn't reach the catalog. Check the connection and try again." with **Try again**, and **Next** still works ("Neither" is chosen). A cached catalog is never refreshed by onboarding, so a re-run of Set up suggestions again stays offline.
2. **Ask's models are a line in Pick a start, not a preset.** On an ASK phone whose build catalog has a SMALL model, Pick a start shows a separate checkbox under the three choices: **"Ask (Preview) · 2.2 GB"**, ticked by default (D4). Under it: "The librarian answers questions from your library, on this phone. It's a preview: it can be wrong." The line holds the SMALL model plus the EMBEDDER when `DENSE_RETRIEVAL` is on, with sizes from the catalog. It's ticked under Neither too: someone who skips the packs can still ask Granger Classics. Review shows it as its own group, "Ask (Preview)". On LIBRARY_ONLY phones, or with no SMALL model in the catalog (today's release builds), the line doesn't appear.
3. **The Phone step tells the truth.**
   - ASK: "A library phone that can run Ask, with 8.0 GB of memory." / "Ask, the librarian, runs on this phone. You can get it on the next screen." *(Built: the second sentence drops when the catalog offers no models, as in release builds today.)*
   - LIBRARY_ONLY keeps its line, with "Research" read as "Ask".
   - Memory goes through `formatBytes` in both places.
4. **Region and Uses become two steps.** `Step` becomes `WELCOME, PHONE, START, REGION, USES, REVIEW`. Each step gets its own `LazyListState`, so every step opens at the top.
   - Region: the field, then **Suggested**: the pack whose title matches the phone's country (`Locale.getDefault().country` → `Locale("", cc).getDisplayCountry(Locale.ENGLISH)`, compared case-insensitively against the title and a small alias table for catalog names that differ, e.g. "United States" ↔ "US"/"USA"; the implementer checks the live catalog's maps titles and fills the table). Below it, the larger area that contains it if `catalog.onboarding` says so, otherwise the larger areas list. Then the alphabetical countries as today.
   - **The suggestion is preselected** (it's a map of where the phone is). "No region" deselects it. *(Built: the alias table maps 27 codes, checked against all 193 maps titles with the JDK's English names; larger areas are never suggested; India has no pack of its own, so nothing is suggested there.)*
   - Uses: the chips as today, on their own screen, with a one-line lead: "Add packs for what you'll use Granger for. You can change this later in Settings."
5. **Review names what's in it.** The first group is titled with the preset's title only when the picked items equal the preset's items; otherwise **"Your picks"**. *(Built: Review said 318 MB and Home 244 MB because Review counts each pack's `.gvec` (Classics alone 73 MB) and the setup group holds the files only. Review keeps the true total for space and adds "Includes 74 MB of indexes for Ask, fetched after their packs.")*
6. **Start leaves at once.** Start disables itself and shows a small progress indicator in the button. *(Built: `enqueueGroup` reads the ledger once instead of once per item, writes with one `@Upsert` list call (one transaction), and runs `scheduleGroup` on its IO dispatcher.)* Home's setup bar already shows progress, so navigating the moment the records are written is enough. Target: under 1 s from tap to Home for the Efficient start on the Pixel 6, measured in Task 4.
7. **"Ask" is the user's word; "Research" stays in code** (D5). Every string a user can see or hear (TalkBack included) says Ask: the rail tool, the screen title, "Ask chat: …", the reader selection action "Ask", Readiness "Library and Ask", onboarding. Routes, classes, prefs keys and log tags keep `Research`.
8. **Ask's landing explains itself.** With no turns, above Recent chats:
   - the title "Ask" with a **Preview** tag (a small outlined label, `labelSmall`) beside it;
   - one line: "Ask a question; the librarian answers from your library and shows where each part came from. It's a preview: it can be wrong, so check the sources.";
   - under that, while models are missing, **Get the librarian · 2.2 GB**. It enqueues the same items as Decision 2 through `downloads.enqueueGroup` and shows a progress bar and "Downloading the librarian · 1.1 of 2.2 GB" in place, from the download records. The question field stays disabled until `readiness == READY`. UNSUPPORTED keeps its sentence.
   - Settings → Models still lists them.
9. **One rule for + and Back.** The landing is `ResearchRoute(chatId = 0)`.
   - Opening a recent chat from the landing **pushes** it.
   - **+** in a chat pops back to the landing, which is the new empty chat.
   - **Back** pops: from a chat to the landing, from the landing out of Ask.
   - The rail's Ask tool brings Ask's stack to the front if it's there (`launchSingleTop`), else opens the landing.
   - A reader's "Ask about this" still opens a new chat on top of the reader, and Back returns to the reader.
   - `openResearch`'s replace-on-top stays only for the reader case, where the chat's view model must not outlive the selection.
10. **Voices are listed at start.** `GrangerApp` launches `installedVoices.refresh()` on `appScope` with IO when `Features.READ_ALOUD`, after the container is up. It's cheap: a directory listing plus the system TTS voices. *(Built instead when Settings opens (`SettingsViewModel.refreshVoices`): listing the system voices binds the TTS engine, which every app start shouldn't pay for, and Settings is the only reader of the summary.)*
11. **No audiobook or music promo in the Library.** `MediaHintRow` and its helpers go. Shelves appear when there's something on them. The sources stay under Archive sources (they move to Get more in U3b), and Settings → Music & audiobooks stays. Listen's empty Music tab points to "Find music on this phone" in U2.
12. **No rail during onboarding.** The shell hides the rail while `OnboardingRoute` is on top (the full-screen reader's `railVisible` path), so a first-run user can't wander into empty tools. Skip and Back still leave onboarding.

## Global Constraints

- `./gradlew testDebugUnitTest :core:research:test` stays green after every task. The count only grows.
- No schema change and no new prefs keys: the Region preselect is computed, not stored.
- No catalog change. Alias names live in app code.
- Release behaviour stays as it is: with no models in the catalog, nothing about Ask appears in onboarding.
- The phone is shared (V4 voice work runs on the Mac, not the phone). Tasks run on JVM tests and Robolectric shots; Task 7 is the phone walkthrough with Neal.
- Commit after every task with the message given, ending with the Co-Authored-By line.

## Review Focus

- On a fresh install, does anything still say "Refresh the catalog"? With the network off, can a user still get through to Home?
- Does any user-visible or TalkBack string still say "Research"?
- Does Start ever leave Review before the records are written, so that Home shows nothing in setup?
- + and Back: chat → + → Back → landing; landing → recent → Back → landing → Back → out; reader → Ask about this → Back → reader.
- Does the Ask line appear on a LIBRARY_ONLY phone or in a release build? It must not.

---

### Task 1: The catalog arrives by itself

- [x] Record the baseline: `./gradlew testDebugUnitTest :core:research:test`, with the count in the commit message.
- [x] `OnboardingViewModel` init: after `load()`, if `catalog.state.value.catalog == null`, `refresh()`. Expose `catalogFetch: Idle | Fetching | Failed` in `OnboardingUi`.
- [x] `OnboardingScreen.start()`: Fetching → progress + "Getting the list of packs…". Failed → the sentence + **Try again**. The old Refresh message goes. The Phone step's Efficient line appears once the catalog lands.
- [x] Tests (`OnboardingViewModelTest`): no cache → refresh is called once; cache present → never called; failure → Failed, and Next still advances with Neither.
- [x] **Commit.** `Onboarding: fetch the catalog on a fresh install`

### Task 2: Ask in onboarding, and the Phone step

- [x] `OnboardingModel.askLine(catalog, tier, dense: Boolean): AskLine?`: the SMALL model plus the EMBEDDER when `dense`, with the summed size; null on LIBRARY_ONLY, with no SMALL model, or when both are installed.
- [x] VM: `askChosen` defaults to true. `review()` adds the "Ask (Preview)" group when the line exists and is chosen. Start enqueues it in the same group.
- [x] Screen: the checkbox under the three choices, with the two-sentence lead (Decision 2).
- [x] `tierLine()` / `researchLine()` as Decision 3. Memory through `formatBytes`.
- [x] Tests (`OnboardingModelTest`): line present on ASK with models; absent on LIBRARY_ONLY; absent with no SMALL; EMBEDDER only with `dense`; sizes summed; ticked under Neither. (`OnboardingViewModelTest`): Review includes the group; unticking removes it.
- [x] **Commit.** `Onboarding: offer Ask (Preview) on phones that can run it`

### Task 3: Region and Uses

- [x] `Step.REGION` and `Step.USES` replace `SITUATION`, with titles "Your region" and "What you'll use it for". Back and Next walk six steps. Each step remembers its own `LazyListState`.
- [x] `OnboardingModel.suggestedRegion(regions, countryCode)`: the display-country match plus `REGION_ALIASES`. Check the maps titles in `catalog/catalog.json` and add aliases for every mismatch among the 20 most populous countries and the UK.
- [x] VM: `regionId` preselects the suggestion once the catalog is there, unless the user has chosen. The Region screen shows Suggested first.
- [x] Tests: suggestion found for US/GB/DE (whatever the catalog names them), none for an unknown code; preselected; "No region" clears it and it stays cleared; each step opens at the top (`OnboardingScreenTest`).
- [x] **Commit.** `Onboarding: Region (suggested from the phone) and Uses as two steps`

### Task 4: Review and Start

- [x] Review's first heading per Decision 5. Find the 318 MB / 244 MB difference (Review vs Home's setup bar on: based.cooking, Water Treatment, Food for Preppers, Knots, Granger Classics). Fix whichever sum is wrong and test both on that set.
- [x] `DownloadManager.enqueueGroup`: per-item reads and upserts in one `withTransaction`; `scheduleGroup` on `Dispatchers.Default`. Keep the mutex. Tests in `core/downloads` stay green; add one for a 360-item group (one transaction, all scheduled).
- [x] VM: `starting` state. The button is disabled with a progress indicator. Navigation happens on success as now.
- [ ] *(Moved to Task 7: queuing the Efficient start on the phone, even with Wi-Fi off, would leave 12 GB queued.)* Measure on the Pixel 6 with Granger Fresh (tap → Home, Efficient start, Wi-Fi off so nothing downloads). Note the time in the commit message.
- [x] **Commit.** `Onboarding: Review names your picks; Start returns at once`

### Task 5: Ask

- [x] Strings (Decision 7). Grep for user-visible "Research" in `app/src/main` (strings, `Text(`, `contentDescription`, `onClickLabel`, enum labels) and change each one. List them in the commit message.
- [x] Landing (Decision 8): title + Preview tag, the intro line, and Get the librarian with in-place progress. `ResearchViewModel` gains `getModels()` (enqueue) and `modelDownload: Progress?` from `downloads.records`.
- [x] + and Back (Decision 9) in `GrangerNavHost` (`openResearch`, `researchOpening`, the rail tool, `onNewChat`, `onOpenChat`). Update `ResearchOpeningTest`. Add the five paths from Review Focus as tests.
- [x] *(Built as `AskLandingTest` over the extracted `AskTitle` and `LibrarianNotice`, plus `LibrarianTest`: `ResearchScreen` takes its view model whole.)* A first `ResearchScreenTest` (Robolectric Compose): the landing shows Ask, Preview and the intro; MODELS_MISSING shows Get the librarian; while downloading the field stays disabled and the progress shows.
- [x] **Commit.** `Ask: the name, a landing that explains itself, the librarian in place, + and Back`

### Task 6: Small truths

- [x] Voices at start (Decision 10). Test: a container with a Granger voice on disk has a non-empty `installedVoices.voices` after start without opening Voice. Settings' summary names it.
- [x] *(`LibraryScreenTest` only tested the hint, so it went with it, with the hint's unit tests in `LibraryViewModelTest`.)* Remove `MediaHintRow`, `missingMediaText`, `mediaHintLinks` and `hintLabel` (Decision 11). Update `LibraryScreenTest`: an empty library shows Media's packs and Archive sources, with no media hint.
- [x] *(A one-line condition in the NavHost's `GrangerShell` call; checked on the phone in Task 7, no unit test.)* No rail during onboarding (Decision 12). Test in `GrangerShellSideTest` or a NavHost test: rail hidden on `OnboardingRoute`, shown after Skip.
- [x] *(Checked on the Pixel with Granger Fresh instead of `ThemeShots`.)* Render the onboarding steps, the Ask landing and the fresh Library in both themes with `ThemeShots` (`-PthemeShots`), and look at each.
- [x] **Commit.** `Voices listed at start; no media promo in the Library; no rail in onboarding`

### Task 7: Walkthrough (Neal, Pixel 6, Granger Fresh)

- [ ] `adb uninstall app.granger.fresh` then `./gradlew installDebug -Pfresh`. Walk it with Wi-Fi on:
  - the catalog arrives;
  - Phone says "8.0 GB" and the Ask line;
  - Pick a start has Ask (Preview) ticked;
  - Region suggests the US;
  - Uses is on its own screen;
  - Review names the picks;
  - Start returns at once (trim the picks so the phone keeps its space; keep Ask);
  - Ask downloads the librarian, then answers "what causes the tides?";
  - + and Back follow Decision 9;
  - Settings → Voice names a voice;
  - the Library has no media promo.
- [ ] Again with Wi-Fi off from the first launch: the catalog fails, Try again shows, Next reaches Home.
- [ ] On the library install (`app.granger`, `installDebug`): Ask's name everywhere, + and Back, Voice summary.
- [ ] Record the result in the roadmap's U3 row and a "Carried forward from U3a" section. Merge `u1-ux-audit` into main if Neal passes it.
- [ ] **Commit.** `Roadmap: U3a done`
