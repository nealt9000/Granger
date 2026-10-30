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

- [x] **Step 1:** `git worktree add .claude/worktrees/l1 -b l1-strings main`, then `git submodule update --init` in the worktree, then copy `local.properties`.
- [x] **Step 2:** Baseline run: `./gradlew testDebugUnitTest`. Record the test count. *(2026-10-30: 2315 tests across app and the ten Android core modules, 35 skipped, 0 failures.)*

## Task 1: Foundation: `UiText`, the guard test, common strings

**Files:**
- Create `app/src/main/kotlin/app/granger/UiText.kt`
- Create `app/src/test/kotlin/app/granger/UiTextTest.kt`
- Create `app/src/test/kotlin/app/granger/HardcodedStringsTest.kt`
- Create `app/src/test/resources/hardcoded-strings-baseline.txt`
- Create `app/src/main/res/values/strings_common.xml`

- [x] **Step 1: `UiText`.** A sealed interface with three cases: `Res(@StringRes id, vararg args)`, `Plural(@PluralsRes id, count, vararg args)`, and `Raw(text)` for content such as titles that isn't translated. It has `@Composable fun resolve(): String` and `fun resolve(context: Context): String`. Args can themselves be `UiText` and are resolved recursively.
- [x] **Step 2: Unit tests.** Under Robolectric: `Res` with and without format args, `Plural` for counts 1 and 2, `Raw`, and nested args.
- [x] **Step 3: The guard test.** It scans `app/src/main/kotlin` and each `core/*/src/main/kotlin` and counts, per file:
  - `Text("<Capital or digit>…")`
  - `contentDescription = "…"` with a non-empty value
  - `label|title|text|message|placeholder|body|supportingText = "<Capital>…"` when used as a named argument
  - `Toast.makeText(…, "…"`, `showSnackbar("…"`
  - `setContentTitle("…"` and `setContentText("…"`

  Files whose name contains `Spike` or `Debug` are skipped, as are lines marked `// l10n-ignore: <why>`. The test fails if any file's count is above its line in `hardcoded-strings-baseline.txt` (`path<TAB>count`, sorted), or if a file has hits but no line. When a count goes down it prints a reminder to lower the baseline.
- [x] **Step 4: Generate the baseline.** Do it with a one-off `main` in the test, or a `-PwriteBaseline` property. Commit it. *(Done with `-PwriteBaseline`; first baseline 458 hits in 61 files.)*
- [x] **Step 5: Common strings.** Move the common words used in three or more packages into `strings_common.xml`: Back, Cancel, OK, Retry, Remove, Delete, Close, More options, Open, Play, Pause, Search, Settings, Done, Get, Downloading…. Find them with `grep -rhoE '"(Back|Cancel|…)"' | sort | uniq -c`. Don't convert any call sites yet.
- [x] **Step 6:** `./gradlew :app:testDebugUnitTest --tests '*UiText*' --tests '*HardcodedStrings*'` passes. Commit "L1: UiText, the hardcoded-strings guard, common strings".

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
| 8 (last) | sources, library, widget, top-level files (`Bytes.kt`, `GrangerNavHost.kt`, `MainActivity.kt`, `GrangerApp.kt`) | The open `gb-gutenberg` branch touches `sources/`, `library/` and `MainActivity.kt`, and `bugs-2026-10-30` touches `widget/WidgetUpdater.kt`, so convert these after those branches merge. `Bytes.kt` uses the locale's decimal separator; its tests pin `Locale.US`. **widget done** (2026-10-30): `WidgetModel` holds `UiText`; `WidgetModelStore` keeps it as JSON with stable resource names |

Steps for each package *(all of Tasks 2–8 done; Task 8 on 2026-10-30 after merging main's KB, WF, GB, IA, DL, LV and bug-pass work: sources, library, `Bytes.kt` and the top-level files, one commit each; `MainActivity.kt` and `GrangerApp.kt` had no strings of their own beyond 8b's)*:
- [x] **Step 1:** List the package's literals with the guard's patterns plus `grep -nE '"[A-Z][^"]{2,}"'`. Sort each into translate or keep (convention 6).
- [x] **Step 2:** Add the strings and plurals to `strings_<package>.xml`. Each gets a one-line comment where the context isn't obvious; translators read these comments.
- [x] **Step 3:** Replace the call sites. Use `UiText` in view models and helpers, and turn string-building helpers (such as `SearchScreen.artist()`) into `@Composable` functions or `UiText`-returning ones.
- [x] **Step 4:** Fix the tests: assertions keep their English text where Robolectric supplies it, and JVM tests move to Robolectric or assert `UiText`.
- [x] **Step 5:** `./gradlew :app:testDebugUnitTest --tests 'app.granger.<package>.*' --tests '*HardcodedStrings*'` passes. Commit "L1: <package> strings to resources".

The merge of main (2026-10-30) put main's new English in the converted packages into their strings files too: archive.org sets (parts, Choose parts, Get all, the large-download prompt per kind), Read as ebook and an ebook's original pages, the voices' names, and Plan WF's "covers" lines in Readiness and onboarding.

## Task 8b: Follow-ups found while merging

Things the package tasks left for after the merge, because they cross task boundaries:

- [x] `LaunchDecider.MISSING_NOTICE` goes through `LibraryRoute(notice: String?)` in `GrangerNavHost.kt`. Make the route carry a string id (Task 3, with Task 8's files). *Done: `LibraryRoute(notice: LibraryNotice?, noticeTitle: String?)`; `LibraryNotice` is an enum holding its string (an id that survives a rebuild, unlike a resource int).*
- [x] `ArchiveViewModel.messageOf` and `LibriVoxViewModel.messageOf` switch to `UiText.Res(e.problem.text, *e.textArgs.toTypedArray())`. `ArchiveKind.noun`'s "Search archive.org ${noun}" becomes one string per kind (Task 7 → Task 6's packages).
- [x] `HomeScreen.kt` and `DownloadsModel.kt` move to `QueueText`'s `(res, …)` functions. Then delete the deprecated English ones (Task 7 → Task 2's packages).
- [x] `core/library`: `ImportResult.message` and the `Albums` `UNKNOWN_*` display names. Separate each sentinel from the text it displays. *Done: `ImportResult.message` moved to the app as `library.importProblem`. The `UNKNOWN_*` values stay as placeholders (stored, sorted, compared) and are shown only through music's strings, now in Search and Android Auto too.*
- [x] `Librarian.get()` messages are shown as a Toast `String` in `GrangerNavHost`. Make them `UiText` when Task 8 converts that file (Task 5).
- [x] DevDocsFixes.kt's injected "☰ Contents" button: pass the label into the script from a resource (Task 5).
- [ ] **Saved in the database, decide in L2:**
  - PDF bookmark "p. N"
  - PDF place title "Page N"
  - the chat title "About: …"
  - an ebook's original pages' tab title, "… (original pages)" (Plan IA)
  - Home's drawn cards (`Cards`, stored in `home_feeds`): `chunkLabel`'s "Chapter N" / "Page N" in a card's subtitle
  - the default collection and media type names (`Filing`: Granger Classics, My books, On this phone; Music, Audiobooks), and Gutenberg's pack titles ("Every English book", `LibraryDatabase` migration)

  The voice's copy of the medical text (`AnswerText.MEDICAL`) follows `SpokenText`'s language in L4. A test pins it to the banner's English (Task 5).
- [x] Strings left as `UiText.Raw` or `String` at task boundaries:
  - `MediaDownloader.get()` notices (Task 4's packages): `UiText`
  - `ItemStatus.Failed` / `InLibrary` (sources, Task 8): `Failed(reason: String?)` keeps the downloader's stored reason (L2), null reads "Download failed"; `InLibrary(LibraryShelf)`
  - `RailViewModel.pin()` / `resume()` notices, read by GrangerNavHost (`shell_pins_full` and `shell_cant_play` exist): `pin` hands back `UiText`, `resume` a `LibraryNotice`
  - `RailTabUi.subtitle` "Removed pack/book" as Home builds it (`shell_removed_*` exist): `RailTabUi.subtitle` is `UiText`

  (Task 6)
- [ ] **Saved in book data:** archive.org's OCR note and the "(PDF)" suffix, matched with `removePrefix`. Decide in L2 with the other saved strings (Task 6). *Plan IA's set parts also save "Part N" as a part's title when it has none (`ArchiveParts`), and Nautilus books "(2 of 3)" (`NautilusBooks`).*
- [x] **Android Auto:** `media/AutoBrowseTree` folder names ("Recent", "Music", "Nothing here yet"…). Take a `(Int) -> String` from `GrangerApp.kt` (Task 4).
- [x] **English constants kept for other packages:**
  - ~~`chapterTitle` and `voiceSubtitle` (widget)~~ done: deleted, the widget uses `chapterName`/`voiceState`
  - ~~`ALL_MUSIC` and `ALL_AUDIOBOOKS` (library)~~ deleted: the Library uses `music_all` / `audiobooks_all`
  - ~~`UNKNOWN_AUTHOR`~~ deleted: Search uses `audiobooks_unknown_author`
  - ~~`MediaDownloader`'s "Download failed"~~ `sources_download_failed`, through `failedText`

  (Task 4)
- [x] **Passed through as English by Task 2:**
  - ~~`QueueText`~~ done (see the Task 7 item)
  - `row.error` (the stored download error, L2)
  - `chunkLabel`: written into Home's stored cards, so L2 (above)
  - ~~`AskReach.text`~~ `UiText`
  - ~~`GetMoreLink`~~ string ids
  - ~~`formatBytes`~~ the locale's decimal separator (neutral Latin American Spanish, `es-419`, writes "2.0 GB" like English; `es` writes "2,0 GB")
  - ~~`WidgetModel`~~ done with the widget (Listen's card now takes `UiText`)
  - ~~the volume label~~ the system's description, else `downloads_volume_*` (Task 7)
- [ ] **Changes to the English the walkthrough should confirm:**
  - Readiness's "is from" date is now localised ("Jan 10, 2025", not "2025-01-10"). Task 2; a one-line revert if Neal prefers ISO.
  - Downloads say "1 of 1 track" and "1 of 1 chapter" (Tasks 4 and 6).
  - archive.org's large-download prompt for some of a set's parts: main said "This 3 parts is 234 MB. Download it?"; now "These 3 parts are 234 MB. Download them?" (one: "This part is …").
  - "Queued 1 downloads." is now "Queued 1 download." (a preset or a set of one queued).
  - Kiwix's "Adds about 1 articles…" reads "Adds about 1 article…" at one.
  - Making a pack's books searchable with one book left says "Making 1 book searchable…" / "Copies the 1 book not yet opened…, so Search and Ask can read it in full."
  - Pack row language names now come from the phone's locale (same English names; a Spanish phone shows "inglés").
- [ ] **Not in L1, noted for L2:**
  - Download error messages are stored in `downloads.db`, and `isGone` matches them as text. Translating them needs a stored error code (a schema change).
  - `TextToEpub`'s "Part N" / "Contents" are written into the generated book, so they follow the book's language (L3). So do `PdfToEpub`'s "Notes", "Opening pages", "Pages N–M" and its EPUB's "Contents" (Plan IA).
  - `PdfToEpub.Report.why` (why a PDF won't make an ebook, "no chapters") is the converter's English; the notice wraps it in a translated sentence.
  - Kiwix's set names and slice labels (`setLabel`, "Best 50,000 articles", and `shortLabel`'s " articles") are catalog data (L3).
  - Search matches a folded family by its English words (`KiwixList.Folded.words`), like the catalog's own words (L3/L4).

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
