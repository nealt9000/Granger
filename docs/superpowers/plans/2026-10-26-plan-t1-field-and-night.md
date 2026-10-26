# Plan T1: Field and Night — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Granger wears the field kit. Two first-class themes from the brand brief (§8): **Field** (light: field paper, ink, enamel) and **Night** (night and gunmetal, lantern, phosphor), chosen in Settings → Appearance (Follow the phone, Field, Night). Spectral and Courier Prime are bundled and set the type. The near-black `#0E0E0E` and flame orange `#E8601C` retire everywhere: screens, players, the reader's chrome, the e-book reader's Night, the widget. Spec: `docs/superpowers/specs/2026-10-23-brand-vision-design.md` §8–§10 (step 3, "Theme pass").

**Out of this plan:** new layouts or screens (the UI pass that follows the roadmap's features does those against §9); the tagline (still open); a notification icon; Slim/Park; anything in `:core:*` (no module there draws).

**Architecture (what changes):**
- **`app/theme`:** `GrangerColors` (one static dark palette, read directly at ~100 call sites) becomes two `ColorScheme`s, `FieldColors` and `NightColors`, built from the brief's tokens (`FieldKit`: enamel, gunmetal, night, paper, ink, lantern, phosphor, brass, leather, dusk), plus `GrangerExtras` for the brand roles Material has no slot for (the status voice, the play accent, cover tiles) behind `LocalGrangerExtras`. `GrangerTheme(night: Boolean)` provides both. `GrangerTypography` sets Spectral (display, headline, title, body) and Courier Prime (labels).
- **Call sites:** every `GrangerColors.X` in a composable becomes the matching `MaterialTheme.colorScheme` role or `GrangerTheme.extras` field (Decision 3's table). The four non-Compose readers (the Glance widget, `ReaderWebView`, `ReadingPrefs`, `ReadiumBooks`) take the scheme or a day/night pair explicitly.
- **Appearance:** `AppearancePrefs.theme: ThemeChoice` (`SYSTEM` default, `FIELD`, `NIGHT`); Settings → Appearance gains a Theme radio group; `MainActivity` resolves it against `isSystemInDarkTheme()`, sets the window background and system-bar style to match, and the reader's WebView darkens pages only in Night.
- **Resources:** `res/font/` gains Spectral (regular, italic, medium, semibold) and Courier Prime (regular, bold), OFL, bundled (`docs/superpowers/brand/fonts/OFL-*.txt` holds the licences); `values/themes.xml` is Field (`isLightTheme=true`, paper window), `values-night/themes.xml` is Night.

**Tech Stack:** unchanged: Kotlin 2.4.21, Compose Material 3, Glance for the widget, Robolectric 4.17, JUnit 4 + Truth. Build env as H3 (`JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home`, `ANDROID_HOME=/opt/homebrew/share/android-commandlinetools`; a new worktree needs `local.properties` and `git submodule update --init --recursive`).

**Roadmap:** `docs/superpowers/plans/2026-10-15-granger-v1-roadmap.md`, row T1, after H3 (the brief's §10 step 3).

## What the code looks like now (surveyed 2026-10-26, `main` at `b4e2947`)

- **Theme:** `app/theme/GrangerTheme.kt`: `object GrangerColors` (Flame `#E8601C`, Background `#0E0E0E`, a grey ramp, OnSurface `#ECE6DA`, Tertiary amber, plus the brand's Enamel, Lantern, FieldPaper added by H3 Task 5) and one `darkColorScheme`; `GrangerTheme { }` sets colours only, no typography, no fonts in `res/font`.
- **Direct palette reads:** 28 files read `GrangerColors.*` instead of the scheme: Flame 48×, OnSurfaceVariant 21×, SurfaceHigh 11×, OnSurface 11×, SurfaceVariant 7×, OutlineVariant 7×, Background 5×, Outline 4×, Error 4×, and single uses of the rest. Most are in the players (`player/*`, `music/MusicParts.kt`, `audiobooks/*`), the e-book reader (`ebook/*`), Research (`research/*`), the shell (`shell/GrangerShell.kt`, `shell/Rail.kt`), `media/CitedRow.kt`, `library/BookCover.kt` (its own three hex tiles) and `onboarding/BrandLockup.kt`.
- **Non-Compose readers:** `widget/PlayerWidget.kt` (Glance `ColorProvider(GrangerColors.*)` top-level vals), `reader/ReaderWebView.kt` (background `GrangerColors.Background` before the first paint; `ALGORITHMIC_DARKENING` relies on the window theme's `isLightTheme=false`), `ebook/ReadingPrefs.kt` (`ReadingTheme` DAY/SEPIA/NIGHT; NIGHT uses Background/OnSurface), `ebook/ReadiumBooks.kt` (the Flame accent as Readium's CSS colour).
- **Window:** `values/themes.xml` `Theme.Granger` (platform Material NoActionBar, dark only, `windowBackground` `#0E0E0E`); the system splash takes the launcher icon on it.
- **Appearance:** `AppearancePrefs` (`railSide`, `openTo`) and `AppearanceSection` (radio-group pattern) in `settings/`.
- Tests: 1851 JVM tests in `testDebugUnitTest`.

## Decisions

1. **Follow the phone by default.** Field by day and Night by night is what "field-ready" (§9.4) means for most people; the Theme setting overrides it either way. Today's users (Neal) open on whatever the phone says; Night is one tap away.
2. **The schemes are the brief's tokens, filled out to Material's roles.** Text roles must pass WCAG AA (4.5:1) on every background they sit on, outlines 3:1; `ThemeContrastTest` checks every pairing in both schemes, so a later tweak can't quietly fail. Starting values:

   | Role | Field | Night |
   |---|---|---|
   | primary / onPrimary | enamel `#2E4A45` / paper | lantern `#F0A35E` / ink `#1F1813` |
   | primaryContainer / on | `#CFE0DB` / `#10211E` | `#5A3A1C` / `#FFDDB8` |
   | secondary / on | leather `#5B3322` / paper | phosphor `#91C4BA` / `#0F2622` |
   | secondaryContainer / on | `#EADBCB` / `#2E170D` | enamel `#2E4A45` / `#D6EDE7` |
   | tertiary / on | dusk `#354059` / paper | `#C9A27C` / `#2B1A0B` |
   | tertiaryContainer / on | `#D9DDEA` / `#1A2133` | `#4A3524` / `#F3DCC4` |
   | background = surface / on | paper `#EFE4D0` / ink | night `#181C1D` / paper |
   | surfaceContainer lowest → highest | `#F7EFE2` `#F2E8D7` `#E9DCC6` `#E3D5BC` `#DCCCB0` | `#121516` `#1C2122` gunmetal `#24292A` `#2C3233` `#353C3D` |
   | surfaceVariant / onSurfaceVariant | `#E3D5BC` / `#4A4239` | `#2C3233` / `#B9B3A6` |
   | outline / outlineVariant | `#7A6E5F` / `#CDBFA6` | `#7F8786` / `#3A4243` |
   | error / onError | `#962E19` / paper (was `#A3361F`: 4.3:1 on the highest container) | `#FF8A7A` / `#2A0A06` |
   | inverseSurface / inverseOn / inversePrimary | gunmetal / paper / phosphor | paper / ink / enamel |

   `GrangerExtras`: `status` (phosphor on Night, enamel on Field: the `> offline. ready.` voice), `play` (lantern on Night, enamel on Field), `brass` (fills and rules only, never text), `coverTile` / `coverText` / `coverRule` (the generated book covers: gunmetal, paper and lantern in both themes, since a cover is an object, not chrome), `mark` (lantern on Night, enamel on Field).
3. **One mapping for the call sites.** `Flame` → `primary`; `OnFlame` → `onPrimary`; `Background` → `background`; `Surface` → `surface`; `SurfaceVariant` → `surfaceContainer`; `SurfaceHigh` → `surfaceContainerHigh`; `SurfaceHighest`/`SurfaceLow`/`SurfaceLowest`/`SurfaceDim`/`SurfaceBright` → the same-named roles; `OnSurface` → `onSurface`; `OnSurfaceVariant` → `onSurfaceVariant`; `Outline`/`OutlineVariant` → the same; `Error`/`OnError` → the same; `Scrim` → `scrim`; `Tertiary*` → `tertiary*`; `SecondaryContainer`/`OnSecondaryContainer` → the same; `Inverse*` → the same. A top-level `val` that read the palette becomes a `@Composable` getter or moves inside the composable. Where the flame meant "play" (the play/pause buttons, progress fills) it becomes `extras.play`. `GrangerColors` is deleted when the last reader goes, so nothing can reach the retired palette.
4. **The widget follows the phone, not the setting.** Glance's `ColorProvider(day, night)` takes the Field and Night values; a home-screen widget sits on the launcher, which follows the system.
5. **Night darkens the web; Field doesn't.** `ReaderWebView` sets `WebSettingsCompat.setAlgorithmicDarkeningAllowed(settings, night)` from the resolved theme instead of relying on the window's `isLightTheme`, and paints its background from the scheme before the first page. In Field, Kiwix pages show as published (mostly white), which is what daylight wants.
6. **The e-book reader keeps its own Day / Sepia / Night.** Its Night takes the Night scheme's background and text; its accent (selection, highlights' default) takes the current scheme's primary. Day and Sepia stay as they are: they're the book's page, not the app's.
7. **Type: Spectral for reading and headings, Courier Prime for labels.** `displayLarge`…`bodySmall` in Spectral (titles semibold, body regular), `labelLarge`…`labelSmall` in Courier Prime (bold for `labelLarge`, the button text). Material's sizes stay; Spectral runs a little wide, so Task 4 checks the rail, chips, the mini player and the Welcome screen at 360 dp for clipping before it commits. The wordmark stays the vector from H3 Task 5.
8. **Look before committing.** No emulator is installed and the Pixel is shared, so each visual task renders its screens offscreen (Robolectric `@GraphicsMode(NATIVE)` + `captureToImage`) in both themes at 360×640 and the implementer inspects them; Task 6 publishes the set for Neal. The capture test is a dev tool (`ThemeShots`, `@Ignore`d by default so CI time doesn't grow), kept for the UI pass.

## Global Constraints

- `./gradlew testDebugUnitTest` stays green after every task.
- No new dependency beyond what Compose and Glance already ship; fonts are files in `res/font`, never downloadable fonts.
- No migration: `ThemeChoice` is SharedPreferences `"appearance"`, key `theme`.
- The phone is shared (Research has it): Task 6's walkthrough waits for it and asks on the channel first.
- Commit after every task with the message given.

## Review Focus

- Does any screen still show `#0E0E0E` or `#E8601C` (grep the source for the hexes and `GrangerColors`)?
- Does any text sit on a background it fails 4.5:1 against, in either theme (the contrast test covers the scheme; the shots cover the screens)?
- Does the window flash the wrong colour on launch when the setting disagrees with the phone?
- In Field, are Kiwix pages un-darkened, and in Night, darkened as before?

---

### Task 1: The two schemes

- [x] `FieldKit` tokens, `FieldColors`, `NightColors`, `GrangerExtras` + `LocalGrangerExtras`, `GrangerTheme(night, content)`; keep `GrangerColors` for now (Task 2 removes it) but point its brand entries at `FieldKit`.
- [x] `GrangerThemeTest` (the contrast checks): every text role on each background it's used with passes 4.5:1, outlines 3:1, in both schemes; adjust the starting values where one fails and record the change in Decision 2.
- [x] **Commit.** `Theme: Field and Night schemes from the field kit`

### Task 2: Every screen reads the theme

- [ ] Replace each `GrangerColors.*` read per Decision 3 (28 files); top-level palette vals become composable reads; the flame-as-play sites take `extras.play`; `BookCover` takes the cover extras; `BrandLockup` takes `extras.mark`.
- [ ] The non-Compose four: the widget's day/night `ColorProvider`s (Decision 4); `ReaderWebView`'s background and darkening (Decision 5) from a `night` parameter; `ReadingPrefs` NIGHT and `ReadiumBooks`' accent (Decision 6).
- [ ] Delete `GrangerColors`; grep for `0E0E0E`, `E8601C`, `GrangerColors` returns nothing in `app/src/main`.
- [ ] Shots of Home, Library, the reader, the music player, the audiobook player, Research and Settings in both themes; fix what reads wrong.
- [ ] **Commit.** `Theme: every screen reads the scheme; the flame palette is gone`

### Task 3: The setting and the window

- [ ] `ThemeChoice { SYSTEM, FIELD, NIGHT }` in `AppearancePrefs` (default SYSTEM); Settings → Appearance "Theme": Follow the phone · Field · Night; `SettingsViewModel` passes it through; tests as for `railSide`.
- [ ] `MainActivity`: resolve the choice with `isSystemInDarkTheme()`, `GrangerTheme(night)`, `enableEdgeToEdge` with the matching `SystemBarStyle`, window background from the scheme; `values/themes.xml` Field (`isLightTheme=true`, paper), `values-night/themes.xml` Night (night `#181C1D`), so the first frame matches the phone; a setting that disagrees repaints the window in `onCreate` before `setContent`.
- [ ] **Commit.** `Appearance: Theme (follow the phone, Field, Night)`

### Task 4: The type

- [ ] Spectral (Regular, Italic, Medium, SemiBold) and Courier Prime (Regular, Bold) TTFs in `res/font`, licences in `docs/superpowers/brand/fonts/`; `GrangerTypography` per Decision 7; `GrangerTheme` passes it.
- [ ] Shots at 360 dp: the rail, Home's chips and strip, the mini player, Settings, Welcome; fix clipping (shorter labels or `maxLines` + ellipsis, never smaller than Material's sizes).
- [ ] **Commit.** `Theme: Spectral and Courier Prime`

### Task 5: The brand's own touches

- [ ] The status voice: Home's readiness strip and the Library's storage line get one Courier Prime status line in `extras.status` (`> offline. 11.1 GB on this phone.`), wording from the facts already on screen, nothing new computed.
- [ ] About: the lockup in `extras.mark`, "Everything worth knowing, yours to keep.", version, GPL line (as H3 Task 5 left it, now themed).
- [ ] **Commit.** `Theme: the status voice and the themed lockup`

### Task 6: Walkthrough; roadmap

- [ ] Publish the shot set (both themes, every main screen) for Neal; take his notes.
- [ ] On the Pixel once Research hands it back (ask on the channel; back up both databases first): switch Field ↔ Night ↔ Follow the phone, open a Kiwix page in each, play music and an audiobook, open an e-book in its Night, check the widget in the phone's light and dark.
- [ ] Roadmap row T1 → Done; brief §10 step 3 marked done.
- [ ] **Commit.** `Roadmap: T1 done`

## Order and size

Tasks 1 → 2 → 3 in order (2 needs the schemes, 3 needs every screen on them to be worth switching). Task 4 can follow 1 at any point; Task 5 needs 1. About a day of work; Task 2 is the bulk (28 files, mechanical but each needs its shot checked).
