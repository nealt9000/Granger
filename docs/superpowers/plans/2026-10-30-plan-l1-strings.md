# Plan L1: Strings out of Compose — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** every user-facing string in Granger comes from Android resources, so a Spanish `values-es` (L2) translates the whole app. The English app looks exactly as it does today.

Spec: `docs/superpowers/specs/2026-10-30-languages-design.md` §4.1. Research: `docs/superpowers/spikes/2026-10-30-language-research.md`. Roadmap row L0.

**Settled with Neal, 2026-10-30:** both the Spanish app and Spanish knowledge, built together; neutral Latin American Spanish; proceed with everything until the phone is needed.

**Out of this plan:** any Spanish text (L2), the language setting (L2), catalog copy (L3), Research word lists (L4), `SpokenText` (L4/L5).

**Tech Stack:** unchanged (Kotlin 2.4.21, Compose Material 3, Robolectric 4.17, JUnit 4 + Truth). No schema or catalog change.
- **Build env:** `JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home ANDROID_HOME=/opt/homebrew/share/android-commandlinetools`.
- **Worktree:** `.claude/worktrees/l1` (branch `l1-strings`, off main).

## Size

A rough count of capitalised literals by package:

| Package | Literals | Package | Literals |
| --- | --- | --- | --- |
| player | 116 | media | 56 |
| settings | 111 | reader | 54 |
| sources | 103 | librivox | 47 |
| library | 96 | home | 46 |
| onboarding | 95 | audiobooks | 42 |
| ebook | 95 | readiness | 40 |
| music | 91 | downloads | 35 |
| archive | 87 | browse | 26 |
| research | 78 | widget, listen, search | 19, 18, 16 |
| shell | 63 | top-level files | 32 |

The `core/` modules with text: `downloads`, `media`, `player`, `speech`, `taglib`, `books`, `zim`, `ai`, and archive.org's shelf titles. The counts include log tags and ids, which stay, so expect about 700–900 real strings.

## Conventions (every task follows these)

1. **One resource file per package.** Strings go in `app/src/main/res/values/strings_<package>.xml`, for example `strings_search.xml`; shared words go in `strings_common.xml`. Names are `<package>_<what>`, such as `search_placeholder` and `search_show_all`. Common words take `common_` (`common_back`, `common_cancel`, `common_retry`). Separate files let tasks run in parallel without conflicts.
2. **Compose** reads strings with `stringResource(R.string.x, args)` and `pluralStringResource(R.plurals.x, n, n)`.
3. **Non-Compose code** (view models, services, workers, notifications) uses `UiText` (Task 1) when it hands text to the UI, and `context.getString` when it builds a notification itself.
   - View models never take a `Context` for text.
   - `UiText` is resolved in Compose with `.resolve()`, or in other code with `.resolve(context)`.
4. **Whole sentences only.** `"Show all $count ${group.noun}"` becomes one plural or format string per case. Never glue a translated fragment to another, and never add `"s"` for a plural.
5. **Plurals:** every count-dependent noun is a `<plurals>` with `one` and `other`.
6. **What stays a literal:**
   - log messages and tags
   - ids, routes and preference keys
   - catalog data (titles and blurbs come from the catalog, which is L3)
   - content from books, packs or tags
   - debug-only screens (`Features.*` debug and `*Spike*`)
   - test code
   - prompts sent to the model (L4)
7. **Dates and numbers** format through the current locale: `DateTimeFormatter.ofLocalizedDate`, `NumberFormat.getInstance(locale)`, and `java.time` month names with `Locale.getDefault()`. The exception is code that matches English Wikipedia page titles (`DayPage`), which takes an explicit `Locale.ENGLISH` and a comment saying it's matching data, not UI (L3 makes it per language).
8. **Tests:**
   - Robolectric tests run in English, so assertions on English text keep passing. Read expected strings from resources only where the test already has a context.
   - A pure JVM test of a function that now needs resources moves to Robolectric (`@Config(sdk = [35])`, `ApplicationProvider.getApplicationContext<Context>().resources`), or tests the `UiText` value instead.
9. **Pure-Kotlin modules** (`core/research`, `core/text`, `core/vectors`) don't get Android resources. A user-facing message there becomes an enum or sealed type that `app/` maps to a string.

## Task 0: Worktree

- [ ] **Step 1:** `git worktree add .claude/worktrees/l1 -b l1-strings main`, then `git submodule update --init` in the worktree, then copy `local.properties`.
- [ ] **Step 2:** Baseline run: `./gradlew testDebugUnitTest`. Record the test count.

## Task 1: Foundation: `UiText`, the guard test, common strings

**Files:**
- Create `app/src/main/kotlin/app/granger/UiText.kt`
- Create `app/src/test/kotlin/app/granger/UiTextTest.kt`
- Create `app/src/test/kotlin/app/granger/HardcodedStringsTest.kt`
- Create `app/src/test/resources/hardcoded-strings-baseline.txt`
- Create `app/src/main/res/values/strings_common.xml`

- [ ] **Step 1: `UiText`.** A sealed interface with three cases: `Res(@StringRes id, vararg args)`, `Plural(@PluralsRes id, count, vararg args)`, and `Raw(text)` for content such as titles that isn't translated. It has `@Composable fun resolve(): String` and `fun resolve(context: Context): String`. Args can themselves be `UiText` and are resolved recursively.
- [ ] **Step 2: Unit tests.** Under Robolectric: `Res` with and without format args, `Plural` for counts 1 and 2, `Raw`, and nested args.
- [ ] **Step 3: The guard test.** It scans `app/src/main/kotlin` and each `core/*/src/main/kotlin` and counts, per file:
  - `Text("<Capital or digit>…")`
  - `contentDescription = "…"` with a non-empty value
  - `label|title|text|message|placeholder|body|supportingText = "<Capital>…"` when used as a named argument
  - `Toast.makeText(…, "…"`, `showSnackbar("…"`
  - `setContentTitle("…"` and `setContentText("…"`

  Files whose name contains `Spike` or `Debug` are skipped, as are lines marked `// l10n-ignore: <why>`. The test fails if any file's count is above its line in `hardcoded-strings-baseline.txt` (`path<TAB>count`, sorted), or if a file has hits but no line. When a count goes down it prints a reminder to lower the baseline.
- [ ] **Step 4: Generate the baseline.** Do it with a one-off `main` in the test, or a `-PwriteBaseline` property. Commit it.
- [ ] **Step 5: Common strings.** Move the common words used in three or more packages into `strings_common.xml`: Back, Cancel, OK, Retry, Remove, Delete, Close, More options, Open, Play, Pause, Search, Settings, Done, Get, Downloading…. Find them with `grep -rhoE '"(Back|Cancel|…)"' | sort | uniq -c`. Don't convert any call sites yet.
- [ ] **Step 6:** `./gradlew :app:testDebugUnitTest --tests '*UiText*' --tests '*HardcodedStrings*'` passes. Commit "L1: UiText, the hardcoded-strings guard, common strings".

## Tasks 2–8: Convert by package group

Each task converts its packages under the conventions, lowers or removes those files' lines in the baseline (the guard must still pass), runs that package's tests plus the guard, and commits one commit per package. The groups have no files in common, so they can run in parallel in separate worktrees off Task 1's commit. The lead merges them.

| Task | Packages | Note |
| --- | --- | --- |
| 2 | search, browse, listen, home, readiness, downloads | `home/DayPage.kt` keeps `Locale.ENGLISH` for page titles (convention 7); `readiness` dates go through the locale |
| 3 | settings, onboarding, theme, voice, launch | `OnboardingModel` blurbs and labels become `UiText`. `getDisplayCountry(Locale.ENGLISH)` stays, since it matches map titles; comment it |
| 4 | player, music, audiobooks, media | Many plurals (tracks, albums, chapters) |
| 5 | ebook, reader, research | The medical banner in `research/AnswerText.kt` becomes a string. Prompts stay (L4). `ReaderBars`/`ReaderViewModel` constants become ids |
| 6 | archive, librivox, shell | `shell` holds the rail labels; keep them short |
| 7 | `core/` modules: downloads, media, player, speech, taglib, books, zim, ai, archive | Android library modules get their own `res/values/strings.xml` (`<module>_` names). Pure-Kotlin messages become types mapped in `app/`. `ArchiveQuery` shelf titles become an enum mapped in `app/archive` |
| 8 (last) | sources, library, widget, top-level files (`Bytes.kt`, `GrangerNavHost.kt`, `MainActivity.kt`, `GrangerApp.kt`) | The open `gb-gutenberg` branch touches `sources/`, `library/` and `MainActivity.kt`, and `bugs-2026-10-30` touches `widget/WidgetUpdater.kt`, so convert these after those branches merge. `Bytes.kt` uses the locale's decimal separator; its tests pin `Locale.US` |

Steps for each package:
- [ ] **Step 1:** List the package's literals with the guard's patterns plus `grep -nE '"[A-Z][^"]{2,}"'`. Sort each into translate or keep (convention 6).
- [ ] **Step 2:** Add the strings and plurals to `strings_<package>.xml`. Each gets a one-line comment where the context isn't obvious; translators read these comments.
- [ ] **Step 3:** Replace the call sites. Use `UiText` in view models and helpers, and turn string-building helpers (such as `SearchScreen.artist()`) into `@Composable` functions or `UiText`-returning ones.
- [ ] **Step 4:** Fix the tests: assertions keep their English text where Robolectric supplies it, and JVM tests move to Robolectric or assert `UiText`.
- [ ] **Step 5:** `./gradlew :app:testDebugUnitTest --tests 'app.granger.<package>.*' --tests '*HardcodedStrings*'` passes. Commit "L1: <package> strings to resources".

## Task 9: Zero and lock

- [ ] **Step 1:** The baseline is empty. Delete the file, and make the guard fail on any hit outside `// l10n-ignore`.
- [ ] **Step 2: Pseudo-locale check.**
  - Add `pseudoLocalesEnabled = true` to the debug build type.
  - A Robolectric test renders five screens (Home, Library, Search, Settings, the player) under `qualifiers = "en-rXA"` and checks that no visible text node is in plain ASCII. A plain-ASCII node means a literal was missed.
- [ ] **Step 3:** The full `./gradlew testDebugUnitTest` passes with the test count at or above the baseline from Task 0.
- [ ] **Step 4:** `git grep -c '<string name=' app/src/main/res core/*/src/main/res` gives the real string count. Write it in the roadmap row.

## Task 10: Walkthrough (needs the phone)

- [ ] **Step 1:** Wait until the Pixel 6 is free of the bug pass, GB and A0. Then `installDebug` over `app.granger` (one app on the phone).
- [ ] **Step 2:** Walk every rail tool, Settings, onboarding (Set up suggestions again), a book, a wiki, Listen, Ask and a download. The app reads exactly as before.
- [ ] **Step 3:** Set the phone to the `en-XA` pseudo-locale (Developer options) and repeat a short walk. Any plain-English text left is a missed string; fix it.
- [ ] **Step 4:** Merge to main and push. Update the roadmap row.
