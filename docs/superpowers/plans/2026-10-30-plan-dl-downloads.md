# Plan DL: Downloads you can see — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** you can always see what Granger is downloading, how far it's got, how long is left, and what comes next, and you can steer it.
- **Home** shows one live card while anything is downloading.
- **The Downloads page** shows the whole queue in order, with speed, time left and why anything is waiting.
- **First-run downloads** land the quick, immediately useful things first.

Neal, U3c fresh-install walkthrough (2026-10-29): "Homescreen should show ongoing downloads progress and ability to tap in and see the dl queue. Current download detail page is still light on detail." And: "Not much is useful before a lot of downloading happens; we should probably sort the download order by things that make the app most immediately useful during the onboarding/introduction phase. The granger collection & a granger voice perhaps."

**Settled with Neal, 2026-10-30:**
- **Order:** quick wins first, then the big packs smallest first, with Wikipedia Top and the Ask models last (Decision 5).
- **Control:** Pause all, and "Download next" on any waiting item (Decision 3).
- **Home:** one live card for every download, not just setup; it turns into "n stopped · Retry" if anything failed (Decision 7).

**Out of this plan:**
- Kiwix catalog friendliness and PDF contents-from-text, which get their own session.
- Per-item progress on Source rows, Voice settings and Ask. They keep working off the same records and get "n ahead of it" only if it falls out cheaply.
- Release builds offering no Ask models (`evalPassed=false`). That's a catalog question, noted for F1.

**Tech Stack:** unchanged (Kotlin 2.4.21, Compose Material 3, Room, WorkManager, Robolectric 4.17, JUnit 4 + Truth).
- **Schema:** one migration, `downloads.db` 3 → 4. No catalog change.
- **Build env:** `JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home ANDROID_HOME=/opt/homebrew/share/android-commandlinetools`.
- **Worktree:** `.claude/worktrees/dl` (branch `dl-downloads`, off main `80094003`), with submodules and `local.properties` already set up.
- **Phone:** the Pixel is on Wi-Fi as `adb-21121FDF6007JN-gtbanj._adb-tls-connect._tcp`. One app on the phone: install over `app.granger` only.

**Roadmap:** the DL row in `docs/superpowers/plans/2026-10-15-granger-v1-roadmap.md`. F1 follows.

## What the code looks like now (surveyed 2026-10-30, main `80094003`)

All under `core/downloads/src/main/kotlin/app/granger/core/downloads/` unless noted.

- **Ledger:** `DownloadRecord` (table `downloads`, key itemId + hash). Kinds PACK, MODEL, BOOK, AUDIO, VOICE, VECTORS. States QUEUED, RUNNING, FAILED, INSTALLED; no PAUSED. bytesDone is written at most once a second (`DownloadRunner.kt:59-62`). `downloads.db` is version 3.
- **Scheduling:** `WorkManagerScheduler` (`DownloadWorker.kt:99-148`).
  - An ungrouped item is its own unique work, `download-<itemId>`. Nothing caps how many run at once.
  - A group (onboarding, an album) is one `APPEND_OR_REPLACE` chain, so its files run one at a time in list order, alongside everything ungrouped.
  - Network constraint: UNMETERED if wifiOnly. Backoff is exponential from 30 s. After 10 attempts the record is FAILED.
- **Vectors:** each `.gvec` is queued ungrouped when its parent installs (`VectorDownloads`, `GrangerApp.kt:567-579`). The 347 Classics' vectors would all run in parallel, each with its own notification.
- **Order is fragile:**
  - Every file in a group shares one `createdAt`, so nothing stored keeps the queue order.
  - `setWifiOnly` rebuilds chains `sortedBy { it.itemId }` (`Downloads.kt:324`), and `retryGroup` uses `forGroup`, which is ORDER BY itemId (`DownloadDatabase.kt:31`). Toggling Wi-Fi only in Settings reorders the onboarding chain alphabetically.
- **Lost chains:** `resumeDownloads()` (`GrangerApp.kt:554-564`) reschedules only ungrouped records (`Downloads.kt:67-73, 328-331`). A group whose chain was lost stays QUEUED or RUNNING forever, and Home's setup strip never clears.
- **Downloads page** (`app/.../downloads/DownloadsScreen.kt`, `DownloadsModel.kt`, `DownloadsViewModel.kt`): sections Now, Stopped, Done today. A group row expands to one word per file (Queued, Downloading, Failed, Done), with no bytes or error. No speed, time left, order, "next up", or reason for waiting other than Wi-Fi. Actions: Cancel, Retry, Open.
- **Notification** (`DownloadWorker.kt:150-200`): one per item, title and percent, no actions.
- **Home:** the setup strip (`HomeViewModel.setupProgress`, `HomeViewModel.kt:193-205`; `setupText`, `HomeScreen.kt:313-316`) follows only the newest `onboarding:` group and stays while any file is FAILED. Home cards (`CardTarget`) are drawn once a day and cached, so a live card belongs in `HomeUi` beside `setup`, not in the feed.
- **Onboarding order:** `OnboardingModel.review()` (`OnboardingModel.kt:180-211`): preset lines (Classics last), voice, Ask models, map, use-case lines. `start()` (`OnboardingViewModel.kt:284-309`) enqueues it as one group. For Efficient that means WikiMed 1.86 GB → small food packs → knots → iFixit 3.57 GB → science → Wikivoyage → Wikipedia Top maxi 6.99 GB → Classics (95 MB) → voice (80 MB) → Gemma 2.0 GB → EmbeddingGemma → map. The voice and the Classics wait behind about 17 GB.
- **Onboarding's Wi-Fi switch** writes the pref only and doesn't call `downloads.setWifiOnly` (`OnboardingViewModel.kt:265-268`).

## Decisions

1. **One queue, one file at a time.**
   - A single dispatcher replaces the per-item works and the group chains. `DownloadQueueWorker` is unique work `download-queue`, KEEP.
   - It loops: pick the next runnable record by `queueOrder`, run it with the existing `DownloadRunner`, repeat; stop when nothing is runnable.
   - Groups stay as a label (`groupId`) for display, Cancel all and Retry all. They no longer decide scheduling.
   - One file at a time is the simplest thing that is fast enough: a single HTTP stream already fills a home connection. It also gives one honest time-left figure and one notification.
   - **Startup:** `resumeDownloads()` resets any record left RUNNING with no live worker to QUEUED (its `.part` is kept) and ensures `download-queue`. That fixes lost chains for groups and singles alike.

2. **Order is stored.**
   - `downloads.db` v4 adds `queueOrder INTEGER NOT NULL`, `attempts INTEGER NOT NULL DEFAULT 0` and `retryAt INTEGER` (nullable).
   - The migration backfills `queueOrder` from createdAt, then itemId.
   - `forGroup`, `observeAll` and every reschedule order by `queueOrder`, which fixes the alphabetical reshuffle.
   - **Where new items go:**
     - something you start yourself (Get, an album, a voice from Settings, the librarian from Ask) goes next, right after the file now downloading;
     - a group goes to the back, in its given order;
     - a pack's or book's vectors go right after their parent, so Ask can use it as soon as possible.

3. **Pause all and Download next.**
   - **Pause all:**
     - a `paused` flag in `DownloadPrefs`, so it survives a restart;
     - pausing cancels `download-queue`, and the running file goes back to QUEUED with its `.part` kept;
     - Resume ensures the queue and carries on with a Range request.
   - **Download next:** gives a waiting item a `queueOrder` just ahead of everything else waiting. It starts when the current file finishes; it doesn't interrupt it.
   - Cancel, Retry and Remove keep working per item. Groups get Cancel all and Retry all.

4. **Retries and waiting reasons are visible.**
   - A transient failure no longer blocks the queue. The record stays QUEUED with `attempts + 1` and `retryAt` on the same exponential backoff from 30 s, and the dispatcher moves on to the next runnable record.
   - When only records with a future `retryAt` remain, the queue reschedules itself with that delay.
   - After 10 attempts, or on a permanent failure, the record is FAILED with its error, as today.
   - A Wi-Fi-only record is skipped while on mobile data. When only those remain, the queue reschedules itself with an UNMETERED constraint.
   - **Each waiting item has one reason, shown as text:**
     - "Paused";
     - "Waiting for Wi-Fi";
     - "Trying again in 2 min (attempt 3 of 10)";
     - "Needs 1.2 GB more space";
     - otherwise its place: "Next" or "4th in line".

5. **First-run order: quick wins first** (Neal's choice). `OnboardingModel` ranks the review's items in tiers:
   1. The Granger voice.
   2. The Granger Classics.
   3. Small packs, 200 MB or less (cooking, water, food prep, knots), in readiness-domain order.
   4. The region map, if it was preselected (2 GB or less).
   5. Everything else, smallest first. That puts Wikipedia Top and the bigger maps last among the packs.
   6. The Ask models (Gemma, then EmbeddingGemma).

   The review screen keeps its grouping. It gets one line: "Quick ones first: the voice and the Classics land in a few minutes." The Wi-Fi switch in onboarding also calls `downloads.setWifiOnly`.

6. **Speed and time left.**
   - `DownloadRunner` feeds a byte counter to an in-memory `TransferRate`: a rolling 20-second window, not persisted.
   - `Downloads.live` exposes it as a flow.
   - **Time left** = the bytes still to fetch across everything runnable ÷ the rate. It shows only after 10 s of data, and is rounded ("about 25 min", "about 3 h").
   - **What counts as this round's work:** a `batchStartedAt` in `DownloadPrefs`, set when something is queued into an empty queue. The overall figures ("3 of 14", "1.2 of 9.8 GB") count records queued since then.

7. **Home: one live card** (Neal's choice).
   - It replaces the setup strip, in the same place, and comes from `HomeUi.downloads`, built from `Downloads` plus `live`. It's not in the daily feed.
   - **While anything is waiting or running:**
     - the current file's title;
     - one bar for the whole round, with "3 of 14 · 1.2 of 9.8 GB · about 25 min left";
     - a Pause/Resume icon;
     - a tap opens Downloads.
     - Paused reads "Paused · 11 waiting".
   - **If the queue is empty but something failed:** "2 stopped · Retry", with a close icon. Closing stores the failed ids in prefs; a new failure brings the card back.
   - It's gone when there's nothing to show.

8. **The Downloads page, rebuilt.**
   - **A summary header:** the overall bar and figures from Decision 6, the speed ("2.1 MB/s"), and Pause all / Resume. A storage line: "9.8 GB to go · 41 GB free on Phone".
   - **Now:** the running file, with its bar, "312 of 980 MB · 2.1 MB/s", and its group as a small label ("Setting up").
   - **Up next:** every waiting item in queue order, each with its size and its reason (Decision 4).
     - A row's overflow has Download next and Cancel.
     - A group's files appear in place, labelled by the group, not folded away.
     - Vectors stay folded into their parent's row as "+ index for Ask".
     - A long list shows the first 20, then "Show all n".
   - **Stopped:** each failed item with its error text and Retry; Retry all at the top.
   - **Done today:** unchanged.

9. **One notification.**
   - It replaces the per-item ones: "Downloading Wikipedia Top · 3 of 14 · about 25 min left", with a progress bar for the round and a Pause action.
   - Tapping it opens Downloads (`granger://downloads`, as today).
   - Paused or idle, it's gone.

## Global Constraints

- `./gradlew testDebugUnitTest :core:research:test` stays green after every task, and the count only grows. The baseline is 2464 (30 skipped).
- One migration (`downloads.db` 3 → 4), with a `DownloadMigrationTest` case. No catalog change.
- A `.part` file is never thrown away by pause, Download next, a Wi-Fi switch or a restart. Only Cancel and Remove delete it.
- Commit after every task with the message given, ending with the Co-Authored-By line.
- One app on the phone. A true first run (Task 6) needs Neal's OK to uninstall.

## Review Focus

- Does exactly one file download at a time? Does order survive a Wi-Fi toggle, Retry all and a restart?
- Does a group whose work was lost resume on the next launch?
- Do pause, then kill the app, then resume, carry on from the `.part` (a Range request, not a restart from zero)?
- Does Download next start that item right after the current file, without cutting the current file off?
- Does a flaky mirror let the queue move on and come back to it, with the attempt count showing?
- Does a fresh Efficient start land the voice and the Classics first, with Wikipedia Top and the models last?
- Is the time left steady, rather than jumping about?

---

### Task 1: One queue

- [ ] Record the baseline in the commit message.
- [ ] Migration 3 → 4 (Decision 2) and the `DownloadDao` order changes. Add a `DownloadMigrationTest` case for the backfill.
- [ ] `DownloadQueueWorker` and a new `Scheduler` that ensures, cancels and delays `download-queue` (Decisions 1 and 4).
  - Remove the per-item and group-chain scheduling.
  - Keep `setForeground` handling as the worker has it.
- [ ] `DownloadManager`:
  - enqueue positions (Decision 2);
  - `downloadNext(itemId)`, `pause()`, `resume()`;
  - attempts and `retryAt`;
  - `setWifiOnly` without reordering;
  - startup recovery of stale RUNNING records;
  - `paused` in `DownloadPrefs`.
- [ ] `VectorDownloads` puts vectors right after their parent.
- [ ] Tests, in `DownloadManagerTest` and a new `DownloadQueueTest` with a fake runner:
  - one at a time, in order;
  - a group with ids that don't sort in list order keeps its order through `setWifiOnly` and `retryGroup`;
  - a user-started item goes next and vectors follow their parent;
  - Download next;
  - pause keeps the `.part` and resume continues;
  - a transient failure moves on and comes back after `retryAt`, then FAILED after 10;
  - a Wi-Fi-only record is skipped on mobile data;
  - a stale RUNNING group record is recovered at startup.
  - Update the existing chain tests to the new model.
- [ ] **Commit.** `Downloads: one queue, in a stored order, with pause and download next`

### Task 2: Speed, time left and one notification

- [ ] `TransferRate` (pure; tests for the rolling window, a stall and the 10 s warm-up) and `Downloads.live` (Decision 6).
- [ ] `DownloadQueueSummary`: a pure function from records, live rate, prefs and free space to the round's totals, current item, ordered waiting list with reasons, stopped list and time left. Tests for each reason and for the batch boundary.
- [ ] The single notification (Decision 9). Update `DownloadNotificationsTest`, including the Pause action's intent.
- [ ] **Commit.** `Downloads: speed, time left, and one notification for the queue`

### Task 3: The Downloads page

- [ ] Rebuild `DownloadsScreen`, `DownloadsModel` and `DownloadsViewModel` on `DownloadQueueSummary` (Decision 8).
- [ ] Tests:
  - update `DownloadsModelTest`, `DownloadsViewModelTest` and `DownloadsScreenTest`;
  - the header's figures;
  - Pause all / Resume;
  - Download next moves a row to the top of Up next;
  - each reason's text;
  - Retry all;
  - "Show all n" beyond 20.
- [ ] ThemeShots at 360 dp, Field and Night: a running onboarding round, a paused queue, and a queue with a retrying item and a stopped one. Save to the scratchpad and review before Task 4.
- [ ] **Commit.** `Downloads page: the whole queue, what it's doing, and how long`

### Task 4: Home's live card

- [ ] `HomeUi.downloads` replaces `setup` (Decision 7). Remove `setupProgress` and `setupText`.
- [ ] Tests, in `HomeViewModelTest` and `HomeScreenTest`:
  - the card for a non-onboarding download;
  - paused;
  - "2 stopped · Retry" and closing it;
  - a new failure brings it back;
  - gone when idle;
  - a tap opens Downloads.
- [ ] ThemeShots: Home with the card running and stopped, 360 dp, Field and Night.
- [ ] **Commit.** `Home: one live card for downloads`

### Task 5: Quick wins first

- [ ] `OnboardingModel` ranks the group's items in Decision 5's tiers. The review screen line. The Wi-Fi switch calls `setWifiOnly`.
- [ ] Tests in `OnboardingModelTest`:
  - Efficient, Comprehensive and Starter orders: voice → Classics → packs of 200 MB or less → map → the rest smallest first → models;
  - a map over 2 GB falls into the rest;
  - no models in a release-shaped catalog.
- [ ] Update `OnboardingViewModelTest`'s group test to the new order.
- [ ] **Commit.** `Onboarding: the quick, useful downloads land first`

### Task 6: Walkthrough (Neal, Pixel 6)

- [ ] Full suite, then build and install over `app.granger`.
- [ ] Neal checks on the current install:
  - Home's card and the Downloads page while something downloads (Get a mid-size Kiwix pack);
  - Pause all, then kill the app from Recents, then reopen and Resume: does it carry on from where it was?
  - Download next on a waiting item;
  - the notification and its Pause;
  - toggling Wi-Fi only;
  - a stopped item's Retry, and closing Home's stopped card.
- [ ] **With Neal's OK,** a true first run: uninstall, install, and onboard with Efficient.
  - The voice and the Classics should land within minutes.
  - Read-aloud and the Classics should work while the big packs are still coming.
  - The time left should be believable.
  - Afterwards, reinstall the normal build as the only app.
- [ ] Fix what the walkthrough finds. Record the result in the roadmap's DL row and a "Carried forward from DL" section.
- [ ] Merge and push only on Neal's say-so.
