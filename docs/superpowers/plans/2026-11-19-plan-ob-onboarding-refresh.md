# Plan OB: onboarding refresh

2026-11-19. Builds on O1–O5 (O5 merged to main `f4d1aeac`).

**Neal's direction (2026-11-19):**
- Two taps to reading.
- One Outfit screen plus Review.
- Just-in-time hints instead of an explainer screen.

**Goal:** first run describes the app as it is now. Granger reads online at once, offline is what you keep, and Outfitting
fills the gaps. Two taps get a new user reading, and outfitting is one page with a confirmation.

**What's stale today (seen on the Pixel 7 fresh install, 2026-11-19):**
- **Welcome:** "all on your phone, all working with no signal". It's the opposite of the default now.
- **This phone:** a whole screen of memory and storage before anyone needs to download. It also quotes "Starter is
  9.4 GB… a full Wikipedia alone is about 100 GB" to someone who is about to read online for free.
- **Pick a start:** "Everything the readiness model counts", but Readiness is Outfitting now. "Home and the Library are
  yours either way" is also stale: there's no Library rail tool.
- **Outfitting takes four screens** (Pick a start, Region, Uses, Review), where the app's own Outfitting is one page.
- **Skip on Welcome** finishes without the privacy line. Since O5 it lands on Home, with the privacy card there.
- **Settings → "Set up suggestions again"** reopens all of it from Welcome.

**Not in OB:**
- Store listing and site copy.
- Outfitting itself, which is unchanged.
- O6 (remote big indexes).

## Builder's calls (for Neal's review)

### 1. Welcome holds the choice

One screen, top to bottom:
- **The owl and wordmark:** BrandLockup, as now.
- **The tagline:** "Your door to knowledge. Always open." (unchanged).
- **A new intro**, leading with the differentiator (Neal, 2026-11-19: the Encarta-like experience, powered by Wikipedia
  and Kiwix): "An encyclopedia to wander, built from Wikipedia and Kiwix: topic pages, a timeline of everything, and
  links between people, places and books. Read online now, and keep what you like for when there's no signal."
  - Spanish: "Una enciclopedia para recorrer, hecha con Wikipedia y Kiwix: páginas de tema, una línea de tiempo de
    todo y enlaces entre personas, lugares y libros. Lee en línea ya y guarda lo que quieras para cuando no haya
    señal."
  - The word "Encarta" stays out of app copy (Microsoft's trademark); the copy describes the feel instead.
- **Start reading now:** the filled primary button. It ends onboarding on Explore.
- **Outfit for offline ›:** a text button. It goes to the Outfit screen.
- **The privacy line,** small, under the buttons. Its wording is the spec's, unchanged.
- **"Also Spanish?"** stays where it is, under the intro, for a phone with two catalog languages.

Changes from today:
- Welcome has no Skip any more. Start reading now is the skip.
- The privacy line has been seen on both paths, so Explore never sits behind the gate.
- **This phone and How do you want to start? are removed** as steps (`Step.PHONE`, `Step.CHOOSE`).

### 2. One Outfit screen

The heading is **"Outfit for offline"**. Sections, in order:
1. **This phone:** one line, such as "60 GB free on this phone" (the chosen volume; "Store on" appears only with two
   volumes). Ask's line is shown only when `Features.ASK` is on.
2. **A start:** Starter / Efficient / Comprehensive / None. The radio rows, pack ticks and "Save space" are today's.
   - "Read aloud" stays a tick (67 MB).
   - New blurbs:
     - **Efficient:** "The essentials of every domain Outfitting counts, in the least space."
     - **Comprehensive:** "Everything Outfitting counts, for a phone that can hold it."
     - **None:** "Read online, and keep things as you go."
3. **Your region:** one row with the suggested region and its size, plus "Choose another" opening today's search list in
   place. "No region" is the first row of that list.
4. **For:** the use chips, each with what it adds, as on the Uses screen, with See.
5. **A footer** fixed at the bottom: the total and the space left ("9.6 GB of 60 GB free") and **Review**.

Back returns to Welcome. **Not now** (top right) ends onboarding on Explore, the same as Start reading now.

### 3. Review

It stays as it is: the lines by group, the quick-ones-first note, Wi-Fi only and Store on, then **Start**.
- After Start, onboarding ends on **Explore**, not Home. Reading online works while the packs come down.
- A one-time line at the top of Explore says "Downloading 9.6 GB for offline · See progress", which opens Outfitting.

### 4. Settings → "Outfit for offline"

The "Set up suggestions again" row becomes "Outfit for offline" ("Pick a start, your region and what you'll use Granger
for"). It opens the Outfit screen directly, with Back returning to Settings, not to Welcome.

### 5. Just-in-time hints

Each hint is one line, shown once, with a close button, and never on top of reading text. The hints:
- **Keep and the marks:** on the first topic page opened online, above the Keep button.
  - The text: "Keep puts this article on your phone (●). What you read is saved for now (○) until space runs short."
  - Dismissed by ×, or for good by the first Keep.
- **Local only:** the first time Granger is offline (Local only or no signal), on the reach banner, a second line.
  - The text: "Greyed things need the internet. Outfitting shows what to keep."
  - It links to Outfitting.
- **Explore's downloads line,** from call 3.

Storage is a small `HintPrefs`, one boolean per hint, cleared by "Show tips again" in Settings → Reading & listening.
- **Existing installs** see the Keep and Local only hints once too: both are new since 1495.

### 6. Spanish and TalkBack

- Every new string comes in es too.
- The Welcome buttons are real buttons, so TalkBack reads them in order: Start reading now, then Outfit for offline.
- A hint is announced politely, never as an interruption.

### 7. Checks

- **Unit and Compose tests:** the step order (WELCOME → OUTFIT → REVIEW), Not now, Settings entering at OUTFIT, the
  hints' once-only rule, and the privacy line being seen on both paths.
- **Release build on the Pixel 7** with `pm clear`: both paths, in Night and Field, then Spanish.
  - No reinstall is needed: the Pixel 7 already has a locally signed build.

## Tasks

### Task 0: branch

- Branch `ob-onboarding-refresh` from main `46f071a9`, in worktree `.claude/worktrees/ob`.
- Mockups for Neal before building: https://claude.ai/artifact/XmPBpQkoXmuoUFBBNs7Y6e

### Task 1: steps and the view model

- `Step` becomes WELCOME, OUTFIT, REVIEW.
- `startReadingNow()` and `outfit()` both call `onPrivacySeen()`. `notNow()` means start reading now.
- `OnboardingViewModel(startAt = OUTFIT)` is for the Settings entry, and Back from there exits.
- **Tests:** the step order, Back, Not now, and the Settings entry.

### Task 2: Welcome

- The layout from call 1. The intro string is replaced in en and es; `onboarding_intro*` strings become a single
  `onboarding_intro_online`.
- **Tests:** the buttons, the privacy line, and that there's no Skip.

### Task 3: the Outfit screen

- **The sections:** merge `phone()`, `start()`, `region()` and `uses()` into `outfit()`.
- **The region row:** a collapsed region row that expands to the search list.
- **The footer:** a fixed footer bar.
- **The blurbs:** new.
- **Tests:** the sections, region choose and clear, the footer's total, and Review.

### Task 4: the end of onboarding, and Settings

- Start lands on Explore, with Explore's one-time downloads line.
- Settings → Outfit for offline.
- **Tests:** the routes, and the line showing once.

### Task 5: hints

- `HintPrefs` and the Keep and Local only hints.
- "Show tips again" in Settings.
- **Tests:** shown once, Keep dismisses, and resetting brings them back.

### Task 6: walkthrough and merge

- **Pixel 7 walkthrough:** release build after `pm clear`; both paths; Night, Field and Spanish; TalkBack by tree.
- **Then:** the full suite, then a fast-forward merge to main.
- **Candidate:** a new candidate only if Neal wants one before uploading 1545.

## Order

Tasks 1–3 first, then 4, then 5, then the walkthrough.

## Results (2026-11-19)

Built as planned; the full suite passes. Pixel 7 walkthrough: release build, `pm clear`, Night theme.

**What the walkthrough covered:**
- **Welcome:** matches the mockup: the new intro, Start reading now, Outfit for offline ›, and the privacy line, with no
  Skip. Cold start was 0.31 s.
- **The Outfit page:**
  - 60 GB free; the starts with the new descriptions; Read aloud; Save space.
  - The region row: Choose another opens the list in place, and picking Andorra folds it back (the total went from 9.6
    to 9.8 GB).
  - The uses chips, and the footer with the total and Review.
- **Review,** and Back from it.
- **Not now** lands on Explore, filled online.
- **The Keep hint** shows on the spread view; Keep puts it away for good.
- **Local only:** the offline hint sits under the banner, and its Outfitting link opens Outfitting; × dismisses it.
- **Settings:** "Outfit for offline" opens the Outfit page directly, and Back returns to Settings. "Show tips again" is
  in Reading & listening.

**Fixed during the walkthrough:**
- The Keep hint was missing from the spread view (`BroadSpread`); it was only on the plain topic page.
- A suggested region too big to preselect (the US at 17 GB) showed as "No region". The row now offers it with Add and
  Other.
- Review's top-right button said Skip; it's Not now when online.
- The uses intro said "change this later in Settings"; it now says "Outfitting can change this later".

**Not walked on the phone:**
- **Start from Review:** it would queue 9.6 GB. Its Explore line is covered by tests.
- **Spanish and Field:** the strings and theme tokens are in place but weren't walked on the phone.
