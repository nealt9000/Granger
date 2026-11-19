# Plan O5: first run, Settings → Online, and online on for everyone

2026-11-19 · spec `docs/superpowers/specs/2026-11-18-online-offline-design.md` §9 (first run, settings, privacy), §11
Decision 3 (online on by default, with the privacy line before the first online read) · builds on O1–O4 (merged and
pushed) · O4's Task 4 (updates for kept articles) moves here

**Goal:** a new install can start reading at once, online, with nothing downloaded, and knows what that means for
privacy. Settings → Online holds every online choice. Release builds turn Wikipedia online on. This is the plan that
ships the online/offline work to testers.

**Not in O5:**
- The remote big indexes (O6).
- Store listing and site copy (spec §9: later work).

## Builder's calls (for Neal's review)

1. **First run** keeps Welcome and This phone, then one new screen, **How do you want to start?**, with two choices:
   - **Start reading now** (the default, and what Skip does): online, nothing downloaded beyond the small index set,
     landing on Explore.
   - **Outfit for offline:** today's Start step (Efficient / Comprehensive), Region, Uses and Review, as now.

   Both choices show the privacy line under them.
2. **The privacy line**, word for word from the spec: "Reading online asks Wikipedia (and other free libraries) for each
   page, so they see what you open. Granger itself never sees it. You can keep things offline or switch online off any
   time."
   - **People who already have the app** (testers on 1495 or 1528) see it once, as a one-time card on Home the first
     time online is on. Nothing reads online before they've seen it.
3. **Settings → Online, complete,** in this order:
   1. **Use the internet:** On / Never go online. Never is Local only that the top bar, the tile and Outfitting can't
      turn off; they show it as locked.
   2. **Local only** (O3's switch).
   3. **Big downloads on Wi-Fi only:** moved here from Library & storage.
   4. **Offline pictures** (O2).
   5. **Saved for now:** its cap and Clear (O2).
4. **Metered networks:** online pages and pictures load on mobile data at the screen's size (as now). Only downloads
   wait for Wi-Fi, under the setting. Nothing changes in code beyond checking that the online index set asks first on
   mobile data (O1's Explore line).
5. **`Features.ONLINE` turns on in release.** The debug-only extras (`local_only`, `preview_no_wikipedia`,
   `online_title`, `kept_clear`) stay debug-only.
6. **Kept article updates** (from O4): once a day while online, 20 revisions a request; newer ones are listed in
   Outfitting's On this phone with Update and Update all. Nothing updates automatically.
7. **Release check before shipping:**
   - an R8 build (the online code uses org.json, Jsoup and OkHttp: keep rules if needed);
   - a fresh-install walkthrough on the Pixel 7, which needs Neal's OK because it means a clean install there;
   - the release bundle built for Neal's internal upload.

## Tasks

### Task 0: branch

Branch `o5-first-run-online` from main (`2d96e530`), in worktree `.claude/worktrees/o5`, as before.

### Task 1: How do you want to start?

- **The step:** a new `Step.CHOOSE` after PHONE, with the two choices and the privacy line.
  - **Start reading now** finishes onboarding: online on, the small index set queued (O1), and it lands on Explore.
  - **Outfit** goes on to START / REGION / USES / REVIEW as today.
  - **Skip** means Start reading now.
- **`OnboardingPrefs`** records that the privacy line was seen.
- **Tests:** the step order for each choice; Skip; the line's text.

### Task 2: the privacy line for existing installs

- **A one-time Home card:** the line, with OK and "Settings → Online".
- **The gate:** until OK, `NetworkPolicy` treats online reads as not yet allowed. `WikimediaClient` asks a
  `privacyAccepted()` check. Downloads aren't affected: they were always online.
- **Tests:** no Wikimedia request before OK; after OK, reads work; the card shows once.

### Task 3: Settings → Online, complete

- Add the rows from builder's call 3. Never go online locks the switch, the tile, Outfitting's switch and the top-bar
  button (each shows it as locked with a short reason).
- Big downloads on Wi-Fi only moves here; its old row goes.
- **Tests:** Never locks all four; Wi-Fi only still drives the download queue.

### Task 4: updates for kept articles

- **The check:** `WikimediaClient.revisions(lang, titles)`, batched by 20, run by a daily `PeriodicWorkRequest` while
  online.
- **Storage:** results kept in `kept_articles.revision` against the live one, as an in-memory list of updates; no schema
  change needed.
- **In Outfitting:** an "Updates" row lists them, with Update and Update all (the keep job).
- **Tests:** the batch parse, the daily rule, and an update re-keeping.

### Task 5: online on in release

- `Features.ONLINE = true`.
- R8 build, plus keep rules if Jsoup or org.json need them; smoke the release APK on the Pixel 6: online article,
  Explore, Search, Keep, Local only.
- **Tests:** `NetworkAuditTest` still passes; the full suite passes.

### Task 6: fresh install (Pixel 7, with Neal's OK)

- Uninstall, install the release candidate, then go through first run: Start reading now, then Explore, a topic page,
  Search, Keep, Local only, Outfitting.
- Then once more with Outfit for offline.
- Record timings: first screen, the index set arriving.

### Task 7: merge and the candidate

- Run the full suite, then merge to main as a fast-forward.
- Build the release bundle into `~/Desktop/granger-play`.
- Draft release notes.
- Push and upload only when Neal asks.

## Order

1. Tasks 1–3 first.
2. Task 4 alongside them.
3. Then Task 5.
4. Then the Pixel 7 fresh install.
5. Then the candidate.

## Results (2026-11-19, branch `o5-first-run-online`)

Tasks 1–5 built with tests (suite green).
- **Task 1:** How do you want to start? after This phone; Start reading now ends on Explore, Outfit goes on to Start.
- **Task 2:** the privacy line in first run, and once on Home for an install that had Granger before; `WikimediaClient`
  asks nothing until it's seen.
- **Task 3:** Settings → Online: Never go online (locks the switch, tile and Outfitting's switch), Local only, Wi-Fi only
  (moved), Offline pictures, Saved for now.
- **Task 4:** updates for kept articles. Live revisions are fetched 20 a request, checked when Outfitting shows and at
  most once a day, rather than by a background job (no network while the app isn't in use). Update all re-keeps.
- **Task 5:** `Features.ONLINE = true`. The release APK (R8) was smoke-tested on a fresh, read-only copy of the emulator
  image: Welcome → This phone → How do you want to start? → Start reading now → Explore filled online within 30 s
  (today's spread, 17 subjects, 108 rooms) → topic page → the article in the reader with its pictures. No crash; no keep
  rules needed.

**Not done:**
- **Task 6:** the Pixel 7 fresh install. The phone isn't connected, and Neal's OK is needed to uninstall there.
- **The Pixel 6:** it can't take the release build without losing its data (different signing key); it stays on debug
  builds.

**Noticed:** Welcome still says "all working with no signal". The spec leaves copy for later; worth a line now that
reading online is the default start.

### Task 6: Pixel 7 fresh install (2026-11-19, Neal's OK; Play 1309 and its 13 GB uninstalled)

Release build, Night theme.

**Timings:**
- Cold start to Welcome: 0.36–0.54 s.
- Start reading now to a full Explore (today's spread, 17 subjects, 108 rooms): about 5 s.
- Keep (558 KB) to Kept: about 2 s.

**Passed:**
- First run, both paths:
  - Start reading now lands on Explore.
  - Outfit for offline goes through Pick a start (Starter 9.4 GB), Region, Uses and Review (9.6 GB, Wi-Fi only). Stopped
    before Start; nothing downloaded.
- Skip from Welcome lands on Home. It shows the privacy card ("Granger now reads online too"), and nothing online loads
  until OK.
  - Change from the plan: Skip goes to Home, not Explore. Explore would sit empty behind the gate, because the line is
    only on Home.
- Topic page, Keep, Local only (banner, greyed results, "Online results appear when you're connected"), Outfitting
  (domains at "Nothing yet" with essentials sizes).
- Settings → Online, in order. Never go online locks Local only on, and the banner loses "Turn off".

**Found and fixed:**
- Turning Local only off didn't re-ask an open search (`ff370286`). Search now refreshes when reach comes back.
- Home's timeline never appeared on a new install: the time index lands seconds after Home first shows, and the model
  never looked again. It now retries each time Home is shown (`6d282cb4`).
- Ready offline's empty line left a stray card strip (`6d282cb4`).

**Left:**
- The privacy card's title, "Granger now reads online too", is written for updates; a fresh install that skips sees it
  too.
- The timeline lens can show a letter instead of a picture when its first mark loaded before OK.
- The Pixel 7 now holds a locally signed release build. Installing from Play needs an uninstall first (different signing
  key).
