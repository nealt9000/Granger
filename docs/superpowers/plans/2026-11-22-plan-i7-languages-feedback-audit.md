# Plan I7: languages, feedback, the audit and the hand-off

Spec: `docs/superpowers/specs/2026-11-20-iphone-design.md` (§6, I7). Parity list: `docs/superpowers/ios-parity.md`. Follows I6 (merged and pushed main d942e244, release ios-core-2026-11-22).

**Goal.** The iPhone app is finished to Android's release build and handed to Neal for his Simulator test:
- the whole app in Spanish as well as English, with the app language and the knowledge languages as on Android;
- Send feedback and the Report sheet by email with the same contents as Android, crash and hang reports kept on the phone, no telemetry, and a privacy manifest;
- search by meaning, if the embedder runs on the iPhone (a spike decides);
- the icon's dark and tinted variants, and an open-source licences screen;
- the parity audit: every row ticked, or marked with an agreed reason, including the VoiceOver, Dynamic Type and landscape row across every screen;
- the hand-off: a short note for Neal on running it and what to try.

When I7 merges, the parity list has no open row except the ones that need a device (I8), each with its reason.

**Shape.** One branch, two merges, as before: I7a moves the few Android pieces the iPhone needs into the shared core (Task 1) and is checked on the Pixel 6; I7b builds the iPhone side. One native piece opens with a one-hour spike: the embedder (Task 5). The plan stops to report if it fails.

**Where things stand** (survey of main d942e244):
- **Words.** Android has 1,836 English strings and 1,818 Spanish (`app/src/main/res/values-es`, neutral Latin American Spanish, L0). The iPhone has the shared models' keys in `ScreenText.swift` (English only, written by `tools/ios/make_text_ids.py`) and about 160+ English literals in SwiftUI (`Text("…")`, `Button`, `Section`, `Label`…). No String Catalog yet; the project knows `en` only.
- **Languages.** Android: `AppLanguage` (Android 13's per-app `LocaleManager`), `KnowledgeLanguages` and `LanguageSettingsScreen` in `:app`; `LanguageOffers` is already shared. The iPhone has a Wikipedia language picker in Settings only.
- **Feedback.** Android: `FeedbackReport` (subject, the body's facts, the attachment) and `CrashLog` (newest five) in `:app/feedback`, with `FeedbackTest`. The iPhone has a `mailto:` link in About.
- **Search by meaning.** Android ships it: the v2 search vectors (`.gvec`) from the catalog and EmbeddingGemma 2 (`.litertlm`) on LiteRT-LM's `EmbeddingEngine` (`LiteRtTextEmbedder`), fetched after the first vectors (`EmbedderDownloads`, shared). The iPhone's Best matches are keyword-only.
- **Icon.** The AppIcon is traced from the launcher mark (I2); no dark or tinted variants.
- **Licences.** Android's About has a licence row. The iPhone links libraries Android doesn't (TagLib statically under the MPL, Readium, sherpa-onnx and onnxruntime, libarchive, CoreKiwix/libzim, SQLite.swift, Zip, DifferenceKit) and lists none of them.

## Builder's calls in this plan (all 10 OK, Neal 2026-11-22)

1. **Android's `strings.xml` stays the one source of the words, in both languages.**
   - `make_text_ids.py` grows to write a String Catalog, `ios/Granger/Resources/Localizable.xcstrings`, with English and Spanish for every shared key and for every SwiftUI literal whose English matches an Android string (the screens were built "in Android's words", so most will).
   - What doesn't match is iPhone-only (Files, the Music library, Control Center, CarPlay…). Those go in one small file, `ios/Granger/Resources/ios-only-strings.json`, English and Spanish side by side, translated in the same neutral Latin American Spanish as L0, and listed in the Task 2 note for Neal to read.
   - `--check` fails when a literal on screen has no Spanish, so a new screen can't ship English-only.
   - The alternative, translating the iPhone's catalog by hand, would drift from Android's words within a plan.
2. **The app language is iOS's own per-app language; the knowledge languages are the app's, as on Android.**
   - iOS gives every localised app a Language row in the system Settings (Settings › Granger › Language). The app's Language row says which is in use and opens that page, as Apple asks; there's no in-app switch that restarts the app.
   - Knowledge languages (Wikipedia, packs, Classics or Clásicos, the time index, search vectors) are chosen in the app with Android's screen and the shared rules (`LanguageOffers`, `KnowledgeLanguages` moved to the core, Task 1).
   - Onboarding's language step sets the knowledge language and shows which app language iOS is using, with the same link.
3. **Send feedback and the Report sheet use Mail's compose sheet, with Android's contents.**
   - `FeedbackReport` moves to the core (Task 1): the same subject, the same facts in the body (build, device, iOS version, tier, free space, packs and books counts) and the same attachment rule.
   - `MFMailComposeViewController` sends to feedback@opengranger.org. When Mail isn't set up (the Simulator never has it), the share sheet offers the same text and attachment, so Gmail or a copy still works.
4. **Crashes and hangs are kept on the phone from two sources, newest five, as Android's crash log.**
   - Kotlin's unhandled-exception hook and an `NSException` handler write a crash file at once, as Android's `CrashLog` does.
   - MetricKit's diagnostic payloads (crashes, hangs, disk writes) are kept when iOS delivers them, which is at the next launch or a day later.
   - Nothing is sent anywhere; they go only into a feedback email the person chooses to send, as on Android.
5. **A privacy manifest for the app and the widget extension, and App Privacy "Data not collected".**
   - `PrivacyInfo.xcprivacy`: no tracking, no collected data, and the required-reason APIs the code uses (UserDefaults, file timestamps, disk space, and any others the audit finds).
   - Each vendored library is checked for its own manifest; where one is missing and Apple's list requires it, a manifest goes in for it.
   - The App Privacy answers are written down for I8's App Store Connect form.
6. **Search by meaning on the iPhone runs the same model through LiteRT-LM, if the spike shows it can.**
   - The spike builds LiteRT-LM's `EmbeddingEngine` for iOS (as libarchive and sherpa-onnx: a script, a prebuilt xcframework, not in git) and loads the catalog's EmbeddingGemma 2 `.litertlm`.
   - It passes if the iPhone's query vectors match Android's for a fixed list of queries (cosine ≥ 0.99), so the published `.gvec` files work unchanged.
   - If it passes, Best matches gets the dense half as Android has it, the embedder follows the first vectors through the shared `EmbedderDownloads`, and Models in Settings lists it.
   - If it fails, the row becomes "≈ later (keyword-only on the iPhone)" with the reason, and the plan goes on. A Core ML conversion would mean a second model whose vectors differ from the published ones.
7. **The icon's dark and tinted variants are made from the launcher mark's own layers**, traced, not drawn (the marks rule), rendered and shown to Neal before they go in.
8. **An open-source licences screen in Settings › About**, listing every library and asset the iPhone app bundles with its licence text: Granger itself (GPLv3, with the §7 App Store permission line from I8 left as a placeholder), TagLib (MPL-1.1, linked statically), Readium, sherpa-onnx, onnxruntime, libarchive, CoreKiwix and libzim, LiteRT-LM if call 6 brings it in, SQLite.swift, Zip, DifferenceKit, Spectral (OFL) and the Piper voices' licences. It's generated from a list in the repo so a new library can't be missed (`--check` against the Xcode packages and vendor scripts).
9. **The parity audit covers VoiceOver, Dynamic Type and landscape on every screen**, not just I2's:
   - every screen walked at the largest text size, in landscape, and with VoiceOver's rotor by the accessibility tree (labels, traits, order), fixing what's wrong;
   - the 12 UI tests that fail at XXXL because they don't scroll (I5) are fixed, and the whole suite runs once at XXXL.
10. **The hand-off is a page in the repo and a short message**, `ios/HANDOFF.md`: how to build and run in the Simulator from a fresh clone (the three fetch scripts, `Local.xcconfig`), what to try, area by area, in the order Android's walkthroughs use, what can only be tried on a device (I8), and how to send findings. Neal tests; his findings start I8 or a fix plan.

## Global constraints

- **Android must not change for the user (I7a).** The whole JVM suite passes (`./gradlew test testAndroidHostTest jvmTest --continue`, 3917 at the start) and `:app:bundleRelease` builds with R8. A Pixel 6 walkthrough of Send feedback (with and without a crash), the Report sheet and the language screens passes on a debug build installed over the phone's data: check `lastUpdateTime` and the DB version first (never install below the phone's DB version), use `adb -s` with the Pixel's serial, start `app.granger/.MainActivity` explicitly. Neal unlocks the Pixel.
- **No migration.** DB 18 stays.
- **Spanish is neutral Latin American (L0).** Android's Spanish is the reference; iPhone-only words follow its choices (usted or tú as Android does, the same terms).
- The repo is on the X10, which must be mounted. One Gradle, Kotlin/Native or xcodebuild run at a time.
- Pushing and GrangerCore releases have standing OK, after the noreply author check.
- Branch `i7-languages` off main d942e244, in worktree `.claude/worktrees/i0`.
- **UI tests** start from clean data (`CleanData.args`), and the full suite runs from an uninstalled app. The Simulator can't read the X10; fixtures come from the scratchpad (`TEST_RUNNER_GRANGER_TEST_*`) or are generated.
- **Never name Claude** in anything user-facing (the hand-off note included); "coding agent" where it matters.

## I7a: the shared pieces

### Task 1: Feedback and languages to the core
- [ ] Move `FeedbackReport` (subject, facts, body, attachment) and the crash log's format and newest-five rule to commonMain; Android's `:app/feedback` keeps thin wrappers with its constructors. `FeedbackTest` moves with it and passes unchanged.
- [ ] Move `KnowledgeLanguages` and the language screen's state to `:core:screens` (a `StateFlow` model, as Library's in I5). Android's `AppLanguage` stays in `:app` (it's `LocaleManager`).
- [ ] Keys for the new shared words through `UiText.Res` and `make_text_ids.py`.
  - Done 2026-11-22 (db0c76e4, 8be237db, 61def8d0). Module: `:core:screens`, as for Library and onboarding: these are screen models, it already has `UiText`, `bytesText` and `:core:store`'s `Book`, and it's exported to GrangerCore. Package names kept (`app.granger.feedback`, `app.granger.settings`).
    - **Feedback.** commonMain `FeedbackReport.kt`: `BuildFacts` (the 4th field is now `osVersion`, with a 5th `osName`, "Android" by default, "iOS" on the iPhone; `header()` and the "device, Android …" line read the same on Android), `FeedbackFacts`, `FeedbackReport` (subject, included lines, body, attachment, `bookCount`; `bytesText` in place of the app's `formatBytes`, same words), and `AskReport`'s subject and body. `CrashRecords.kt`: `CrashKind`, `PastExit`, `CrashReport` (a `path` now), the `CrashStore` interface, and `CrashRecords` (the file format and name, newest five, the 64 KB cap, `recordExits`' first-read rule, the notice for this build only, `abortMessage`, `readableRuns`).
    - Android keeps: `CrashLog` as a thin wrapper with its old constructor over `FileCrashStore(dir)`, its Java handler, `pastExits`/`readAtMost` and `stamp` (SimpleDateFormat); `BuildFacts.ofThisPhone()`, `CrashReport.file` and a `CrashReport(file, …)` constructor function, and `AskReport.intent()` as extensions (GrangerApp and ResearchScreen import `ofThisPhone`/`intent`). `FeedbackMail`, `FeedbackSheet`, `ReportSheet` are unchanged.
    - **Languages.** commonMain `KnowledgeLanguagesModel.kt`: `KnowledgePrefs`, `KnowledgeLanguagesModel` (Android's rules, `languages`/`offered`/`set`), `LanguageSettingsUi` (`showLibrary`, `rows` of `LibraryLanguageRow`: on, locked, the "Main language" note, Make main; the heading, blurb and Make main as `UiText.Res`), and `LanguageSettingsModel` (a ViewModel with `ui: StateFlow`, `toggle`, `makeMain`, `set`). `resolve`, `toggle`, `makeMain` joined `LanguageOffers`. Android's `KnowledgeLanguages` is a thin subclass over `SharedKnowledgePrefs` (same prefs file and key) with its old constructor and companion; `LanguageSettingsScreen` gets the shared model with `viewModel {}` and draws the section from its rows. `AppLanguage` is untouched.
    - **Words.** Four keys now come from shared code (`settings_language_library`, `_library_blurb`, `_main`, `_make_main`); `make_text_ids.py` wrote them into `ScreenTextIds.kt` and `ScreenText.swift`; `--check` passes.
    - **iPhone.** `IosLibrary.knowledgeLanguages` (UserDefaults `knowledge.languages`; until Task 4 Settings' Wikipedia picker still drives `languages`), `languageSettings()`, `crashRecords(facts)` over `IosCrashStore` in `crashes/` with Android's time stamp (`IosFeedback.kt`). No Swift changes.
    - **Tests.** JVM suite 3930 (3917 + 13 new), 0 failures; `FeedbackTest`, `CrashLogTest`, `AskReportTest`, `KnowledgeLanguagesTest`, `LanguageSettingsTest` unchanged and green. New commonTests `FeedbackReportTest` (4), `CrashRecordsTest` (6), `LanguageSettingsModelTest` (3), green on the JVM host and the Simulator (`iosSimulatorArm64Test`). `:app:bundleRelease` (R8) builds. iPhone: `core-local.sh`, then `GrangerTests` 38 run, 15 skipped (fixtures), 0 failures.
    - Left for Task 2's walkthrough: Send feedback with and without a crash (the sheet's lines, the attachment, Home's crash line and Not now), the Report sheet on an Ask reply, Settings › Language with both library languages (tick, untick down to one, Make main, the Main language line).

### Task 2: I7a check and merge
- [x] JVM suite, `bundleRelease`, the Pixel 6 walkthrough above.
- [x] Release GrangerCore, merge and push. Tell the peer sessions.
  - Lead's check 2026-11-22: JVM 3930 tests, 1 failure in the full run (`SubjectViewModelTest.aSubjectCountsItsErasAndListsAnEraOrOldestFirst`, its 5 s wait timing out under the suite's load; untouched by I7) that passed on two reruns of the class; `bundleRelease` OK (subagent). Pixel 6 on debug 1760 over 1738 (DB 18): Send feedback's lines (build, Pixel 6 Android 17, tier, 40 GB free, 9 packs and 776 books, 2 crash reports attached), the attach switch removes and restores the line, View reads a crash written by build 1455 through the moved format; Language: App language unchanged, library languages tick (Main language and Make main appear), survive a restart (`knowledge.xml` en,es), Make main swaps (es,en), restored to en. Write email not opened (it would leave a draft in Neal's mail; `FeedbackTest` covers the email). The Report sheet is Ask's (off in release; `AskReportTest`). Release ios-core-2026-11-22-2 pinned; the app builds against it.

## I7b: the iPhone

### Task 3: Spanish
- [x] Extend `make_text_ids.py` (call 1): the String Catalog with en and es from Android's resources, SwiftUI literals matched by English, `ios-only-strings.json` for the rest, `--check` for anything on screen without Spanish. Plurals from Android's `<plurals>`.
- [x] Turn literals that can't be matched as written (built from pieces, formatted numbers) into keys with arguments, as Android has them.
- [x] Add `es` to the project's localizations; Info.plist's usage strings and the widget's and CarPlay's words in both languages.
- [x] Walk every screen in Spanish (the Simulator's per-app language) and fix clipped or overflowing labels; Spanish runs longer.
- [x] UI tests: the app launched in Spanish shows Spanish on Home, Settings, a reader's bar and Listen; the catalog check is part of the unit tests.

  - Done 2026-11-22 (commits listed in the report). How it works:
    - **The catalog.** `make_text_ids.py` reads the literals the Swift compiler extracts (`SWIFT_EMIT_LOC_STRINGS: YES`; the build's `.stringsdata` for the app and the widget extension), matches each by its English against Android's `values` (the app's, plus `core/speech`, `core/downloads`, `core/player`), placeholders compared as placeholders, and writes `ios/Granger/Resources/Localizable.xcstrings` (en + es) with Android's Spanish. Android's `%1$s`/`%1$d` become the Swift key's own types (`%@`, `%lld`, `%d`), positional when there are several, so a translation may reorder them; escapes (`\'`, `\n`, `\u00A0`, quotes, `&amp;`) are read as aapt reads them. A key with an integer count that is an Android `<plurals>` "other" form gets its one/many/other forms (a whole-string plural, or with other arguments one substitution of whole sentences keyed on the count). What doesn't match comes from `ios/Granger/Resources/ios-only-strings.json`; `"@key"` there picks Android's Spanish where one English has two (Off, Back, History, 1900 to 1945). Brand names, addresses and debug words are `null` (not translated). The catalog and the json are the only Spanish the iPhone has; nobody edits the catalog by hand.
    - **The shared keys.** `ScreenText.swift` now carries `en` and `es` tables (and plural tables) from Android, and picks by `Bundle.main.preferredLocalizations` — the language iOS runs the app in (Settings › Granger › Language), the same choice the catalog follows. Chosen over `String(localized:)` for the keys because their templates keep Android's `%1$s` and `UiTextWords.fill` fills them with nested `UiText`, which the catalog's formatting can't. Swift can name an Android key itself (`ScreenText.word("game_close")`) and the script carries it: used for the timeline's years (`YearWords.app`, was `.english`), the centuries game (Roman numerals in Spanish, as Android's `game_century_numerals`), the voice names (`IosVoice` now takes `VoiceTitles` from Swift; it had English hard-coded) and two places where Android's English has two Spanish words.
    - **The Swift side.** Every String-typed word on screen goes through `L("…")` (`Sources/Shared/Words.swift`, a `String.LocalizationValue` the compiler extracts), sentences whole: pieces glued into a sentence became alternatives of whole sentences, and `n == 1 ? "1 x" : "\(n) xs"` became one literal with the plural form (a pre-formatted count keeps two branches). About 530 literals in 45 files. English output is unchanged.
    - **The check.** `--check` fails when an extracted literal has no Spanish, when the catalog, `ScreenText.swift`, `ScreenTextIds.kt` or `InfoPlist.xcstrings` is stale, or when a Swift literal that looks like English isn't extracted (a String the catalog never meets); `tools/ios/not-on-screen.txt` lists the 26 that are never shown (PDF operators, JS, debug, the widget gallery's sample) or are names. GrangerTests runs it as a pre-build script on every test build (`ENABLE_USER_SCRIPT_SANDBOXING: NO` for that target), so a test run fails on a word without Spanish. Tried: dropping one Spanish line fails the check with the key and its file.
    - **Counts** (864 extracted keys): 672 matched to an Android string (4 of them by `@key`), 39 Android plurals, 115 iPhone-only translated, 38 not translated (no letters, names, debug), 0 missing; 209 keys carry arguments. Info.plist: the Music library prompt (`InfoPlist.xcstrings`). The project knows `en` and `es` (XcodeGen reads the catalog's languages; regeneration checked byte-identical twice); the widget extension builds the same catalog for its words (gallery description, Control Center's and the intents' names). CarPlay's words come from `ScreenText` and follow.
    - **The walk** (`SpanishUITests.testWalkEveryScreenInSpanish`, `-AppleLanguages (es) -AppleLocale es_419`, screenshots `/private/tmp/claude-501/-Users-s-nealthompson-Developer-Granger/6431eb44-b9ce-47b0-83c7-b89afe8a8b13/scratchpad/shots-es/es-*.png`): onboarding (both pages of the outfitting path), Home, Get more (two pages and the audio one), Settings (5 pages, and Voice), Search and its results, Explore (two pages), Library, Listen, Music, Audiobooks, the mini player reading and paused, Listen's Reading part, the pack reader and its text settings, the e-book reader, its text settings and Contents. Fixed from what the pictures showed: Music's six tabs clipped ("Cancio…", "Carpet…": now at their own widths, scrolling sideways when they don't fit); Settings' "Historia" (now Android's Historial); the voice names in English (VoiceTitles from Swift); Listen's and Library's Music and Audiobooks headings (the seeded kinds kept their English names: `KindRow.shownTitle`); "Temas de 17" (a count now prefers Android's plural over a string with the same English); the highlight colours spoken in English. No other clipping seen; Spanish runs longer but the screens wrap.
    - **Tests.** `LanguageTests` (6, unit): Android's Spanish, positional arguments, plurals, a plural beside another argument, iPhone-only words, the shared tables' keys. `SpanishUITests` (2, UI): Spanish on Home's tabs and buttons, Settings (Apariencia, Solo local), the pack reader's bar (Escuchar, Índice, Ajustes de texto) and Listen; and the walk. Full suite from an uninstalled app (core-local, all fixtures): 86 tests (78 + 8), 83 pass, 3 skipped (M4B, 2 speech spikes), 0 fail; the existing UI tests still find their English labels. No new Granger crash reports (only the known AppIntentsLiveEntityService ones). The voice catalog's own entries ("Granger voice (Owl)", the licence lines) are the catalog's English data, as on Android.
    - Left for later: room names from `name(language: "en")` on Explore ("WARS & BATTLES") and the rooms' descriptions follow the knowledge language, Task 4; accessibility identifiers built from a label (`way-…`, `view-…`) differ in Spanish (English tests unchanged); stored words (a bookmark's "p. 12", an imported book's "Page 3" chapter title) keep the language they were written in, as on Android; the blank mini player over a `-debugPlay` tone is the debug engine's (it isn't a player item), not Spanish.
    - **iPhone-only strings for Neal to read** (English → Spanish; plurals show the other form):
      - "%@ are from %@" → "%1$@ son %2$@"
      - "%@ are from the %llds." → "%1$@ son de los años %2$lld."
      - "%@ free on this iPhone" → "%@ libres en este iPhone"
      - "%@ free on this iPhone · Downloads on Wi-Fi only" → "%1$@ libres en este iPhone · Descarga solo con Wi-Fi"
      - "%@ in line" → "%@ en la fila"
      - "%@ is on its way: see Downloads." → "%@ ya viene: mira Descargas."
      - "%@ is on this iPhone already." → "%@ ya está en este iPhone."
      - "%@ is on this iPhone." → "%@ está en este iPhone."
      - "%@ isn"t a pack Granger can read." → "%@ no es un paquete que Granger pueda leer."
      - "%@ of %@ free on this iPhone" → "%1$@ de %2$@ libres en este iPhone"
      - "%@ of %@ · %@" → "%1$@ de %2$@ · %3$@"
      - "%@ of %@ · %@/s" → "%1$@ de %2$@ · %3$@/s"
      - "%@ to go · %@ free on this iPhone" → "Faltan %1$@ · %2$@ libres en este iPhone"
      - "%@ · On this phone" → "%@ · En este teléfono"
      - "%@ · Saved for now" → "%@ · Guardado por ahora"
      - "%@ · on this iPhone" → "%@ · en este iPhone"
      - "%d chapters on this phone" → "%d capítulos en este teléfono" (one: "%d capítulo en este teléfono")
      - "%d of %d chapters on this phone" → "%1$d de %2$d capítulos en este teléfono"
      - "%d of %d in your library" → "%1$d de %2$d en tu biblioteca"
      - "%d of %d on this phone" → "%1$d de %2$d en este teléfono"
      - "%d of %d · %@ of %@ · %@" → "%1$d de %2$d · %3$@ de %4$@ · %5$@"
      - "%lld families: %@" → "%lld familias: %@" (one: "%lld familia: %@")
      - "%lld games with the dates in your library" → "%lld juegos con las fechas de tu biblioteca" (one: "%lld juego con las fechas de tu biblioteca")
      - "%lld percent" → "%lld por ciento"
      - "%lld songs in the Music library can"t play here: they"re protected or only in the cloud." → "%lld canciones de la biblioteca de Música no se pueden reproducir aquí: están protegidas o solo en la nube." (one: "%lld canción de la biblioteca de Música no se puede reproducir aquí: está protegida o solo en la nube.")
      - "%lld sources" → "%lld fuentes" (one: "%lld fuente")
      - "1 kept article has a newer version" → "1 artículo guardado tiene una versión más reciente"
      - "1 room" → "1 sala"
      - "1 topic" → "1 tema"
      - "A .zim file from Files, copied onto this iPhone" → "Un archivo .zim de Archivos, copiado a este iPhone"
      - "A map" → "Un mapa"
      - "Add %@ · %@" → "Agregar %1$@ · %2$@"
      - "Also read in %@" → "Leer también en %@"
      - "Chapter" → "Capítulo"
      - "Clear recent searches" → "Borrar las búsquedas recientes"
      - "Clear what you"ve read" → "Borrar lo que has leído"
      - "Copies songs into On My iPhone › Granger › Music, where Files shows them." → "Copia canciones a En mi iPhone › Granger › Music, donde Archivos las muestra."
      - "Copying %@…" → "Copiando %@…"
      - "Couldn"t copy %@." → "No se pudo copiar %@."
      - "Couldn"t get the list of packs." → "No se pudo obtener la lista de paquetes."
      - "Damaged: it can"t be read" → "Dañado: no se puede leer"
      - "Downloading · %lld%%" → "Descargando · %lld %%"
      - "Downloads waiting" → "Descargas en espera"
      - "Downloads, %lld" → "Descargas, %lld"
      - "Explore needs Wikipedia"s index. It comes when you"re online." → "Explorar necesita el índice de Wikipedia. Llega cuando tienes conexión."
      - "Find music and audiobooks in the Music library" → "Buscar música y audiolibros en la biblioteca de Música"
      - "For music in Files, on this iPhone or elsewhere. Granger only reads them." → "Para música en Archivos, en este iPhone o en otro lugar. Granger solo la lee."
      - "Get" → "Descargar"
      - "Get %@" → "Descargar %@"
      - "Get %@ · %@ · covers about %d of them" → "Descargar %1$@ · %2$@ · cubre unos %3$d de ellos"
      - "Get it" → "Descargarlo"
      - "Get the Granger voice below, or add a voice in the iPhone"s Settings › Accessibility › Spoken Content." → "Descarga la voz de Granger abajo o agrega una voz en Ajustes del iPhone › Accesibilidad › Contenido leído."
      - "Granger rescans on start and when the Music library changes." → "Granger vuelve a buscar al iniciarse y cuando cambia la biblioteca de Música."
      - "Greyed things need the internet." → "Lo gris necesita internet."
      - "Import a pack" → "Importar un paquete"
      - "Import from Files" → "Importar de Archivos"
      - "Imported %lld files." → "Se importaron %lld archivos." (one: "Se importó %lld archivo.")
      - "Keep text · %@" → "Guardar texto · %@"
      - "Keeping them: each is marked ● when it"s on this phone." → "Guardándolos: cada uno se marca con ● cuando está en este teléfono."
      - "Kept" → "Guardado"
      - "Local only is on: it opens again when you turn it off" → "Solo local está activado: se abre de nuevo cuando lo desactives"
      - "Looking round…" → "Mirando alrededor…"
      - "Missing: the file is gone" → "No encontrado: el archivo ya no está"
      - "Next or back" → "Siguiente o anterior"
      - "No PDFs or EPUBs in this pack." → "No hay PDF ni EPUB en este paquete."
      - "No packs on this iPhone yet. Get more adds them; a .zim put in Granger"s folder in the Files app shows here too." → "Aún no hay paquetes en este iPhone. Conseguir más los agrega; un .zim que pongas en la carpeta de Granger en la app Archivos también aparece aquí."
      - "No songs yet. Touch and hold a song or album to add it." → "Aún no hay canciones. Mantén presionada una canción o un álbum para agregarlo."
      - "Not allowed. iOS won"t ask again: allow Media & Apple Music in Granger"s settings." → "Sin permiso. iOS no volverá a preguntar: permite Multimedia y Apple Music en los ajustes de Granger."
      - "Not enough room for %@." → "No hay espacio suficiente para %@."
      - "Not enough room: %@ needs %@." → "No hay espacio suficiente: %1$@ necesita %2$@."
      - "Nothing downloading. Get more adds packs, and they download here one at a time." → "No se está descargando nada. Conseguir más agrega paquetes, que se descargan aquí de uno en uno."
      - "Nothing on this phone for that. Online results appear when you"re connected." → "No hay nada sobre eso en este teléfono. Los resultados en línea aparecen cuando hay conexión."
      - "Open Listen" → "Abrir Escuchar"
      - "Opening…" → "Abriendo…"
      - "PDF: %@" → "PDF: %@"
      - "Page %lld" → "Página %lld"
      - "Paused · %@" → "En pausa · %@"
      - "Place in book" → "Lugar en el libro"
      - "Play or pause" → "Reproducir o pausar"
      - "Public-domain books published before 1930" → "Libros de dominio público publicados antes de 1930"
      - "Quick wins" → "Para empezar"
      - "Read online · what you read is saved for now" → "Lectura en línea · lo que lees se guarda por ahora"
      - "Reading online asks Wikimedia for the articles you open. Nothing about you goes with it, and nothing goes out with Local only on." → "Leer en línea pide a Wikimedia los artículos que abres. No se envía nada sobre ti, y con Solo local activado no sale nada."
      - "Recent searches cleared" → "Búsquedas recientes borradas"
      - "Recently open" → "Abiertos recientemente"
      - "Remove this feed" → "Quitar este feed"
      - "Remove?" → "¿Quitar?"
      - "Search index · %@" → "Índice de búsqueda · %@"
      - "Seen" → "Visto"
      - "Sets" → "Conjuntos"
      - "Show all %lld best matches" → "Ver los %lld resultados principales" (one: "Ver %lld resultado principal")
      - "Show fewer best matches" → "Ver menos resultados principales"
      - "Sort" → "Ordenar"
      - "Stopped: %@" → "Detenida: %@"
      - "Story" → "Historia"
      - "Tab" → "Pestaña"
      - "This deletes the pack from this iPhone. Your places and bookmarks in it are kept." → "Se eliminará el paquete de este iPhone. Se conservan tus lugares y marcadores en él."
      - "This pack"s list of books can"t be read." → "No se puede leer la lista de libros de este paquete."
      - "Update available" → "Actualización disponible"
      - "Version" → "Versión"
      - "Waiting" → "En espera"
      - "What its article covers" → "Lo que trata su artículo"
      - "What"s playing: music, an audiobook or read aloud." → "Lo que se reproduce: música, un audiolibro o lectura en voz alta."
      - "Wikipedia and your packs" → "Wikipedia y tus paquetes"
      - "Words to find" → "Palabras para buscar"
      - "You are here: %@, %@" → "Estás aquí: %1$@, %2$@"
      - "You have %@ free on this iPhone." → "Tienes %@ libres en este iPhone."
      - "You have %@ free on this iPhone. That isn"t enough room for it." → "Tienes %@ libres en este iPhone. No hay espacio suficiente para descargarlo."
      - "about %lld days left" → "faltan unos %lld días" (one: "falta cerca de %lld día")
      - "about %lld h left" → "faltan unas %lld h"
      - "about %lld min left" → "faltan unos %lld min"
      - "in %lld h" → "en %lld h"
      - "in %lld min" → "en %lld min"
      - "in %lld s" → "en %lld s"
      - "less than a minute left" → "falta menos de un minuto"
      - Info.plist, the Music library prompt → "Granger muestra las canciones de tu biblioteca de Música que puede reproducir, junto a la música que agregas desde Archivos. Nunca las modifica."

  - Lead's check 2026-11-22: full Simulator suite from an uninstalled app 86 tests, 83 passed, 3 skipped, 0 failed; no Granger crash reports; Spanish screenshots looked at (Home, Music). Gotcha: `make_text_ids.py --check` run by hand reads the newest Granger build in DerivedData, which may be main's checkout (no `L(…)`), and then reports ~1,000 literals as unseen; run it after building this checkout, or pass `--build`. The test build's own pre-build check is unaffected.

### Task 4: Languages, feedback, crashes, privacy
- [x] Settings › Language: the app language row (call 2) and the knowledge languages screen on the shared model; onboarding's language step as call 2 says.
- [x] Spanish knowledge end to end: Spanish Wikipedia online, a Spanish pack, Clásicos, the Spanish time index and topic records, the rooms' Spanish descriptions, all reached from the knowledge language.
- [x] Send feedback and the Report sheet (call 3) on the shared `FeedbackReport`, with the share-sheet fallback.
- [x] The crash log (call 4): Kotlin and `NSException` handlers, MetricKit's payloads, newest five, attached when chosen.
- [x] The privacy manifests (call 5), and the App Privacy answers in the Task 4 note.
- [x] UI tests: the feedback text holds the build and the counts, with and without a crash; a forced debug crash is in the log after a relaunch; the knowledge language moves Search and Explore to Spanish.

  - Done 2026-11-22 (commits listed in the report). What it is:
    - **Languages.** `AppSettings.wikiLanguage` is gone: `knowledge` (main first) mirrors the shared `KnowledgeLanguagesModel` (`IosLibrary.knowledgeLanguages`, UserDefaults `knowledge.languages`) through `followKnowledge`, and keeps `LibraryGate.languages` (what every Kotlin `languages()` reads: Wikipedia online in each language, Get more, Home's feeds, Wander, LibriVox, availability, Search's titles) current; `mainLanguage` drives Search's online half, the index set (time index, QID index, topic records via `prepareIndexes`) and Explore. A choice from before (`wikiLanguage`, and the UI tests' `-wikiLanguage`) moves to `knowledge.languages` once. Before the catalog is read with nothing chosen the model can only say English; that guess is skipped so a Spanish phone doesn't fetch the English index first. Settings › Appearance › Language (`LanguageSettingsScreen.swift`): the App language row (iOS's per-app language by name, opening `UIApplication.openSettingsURLString`) and, when the catalog has two, "Your library's languages" from `LanguageSettingsModel`'s rows (checkbox, the last one locked, "Main language", Make main). Onboarding's Welcome sets the knowledge languages through the shared model, says "Also Spanish?" in Android's words (was the iPhone-only "Also read in %@"), and shows the App language row.
    - **Rooms follow the app language, as Android's** (not the knowledge language as this task's brief guessed): Android names rooms and picks their descriptions and the A–Z by `LocalConfiguration`/`phoneLanguage()`; the iPhone now does the same (`name(language: AppLanguage.code)`, and `IosLibrary.subject` no longer passes the knowledge language). The articles themselves come from the knowledge language: with Spanish knowledge in an English app, Explore's spread is "Guerra civil" under "HISTORY · WARS & BATTLES". The topic page's trail names the room in the article's language ("Guerras y batallas"), as the shared topic model does on Android.
    - **Spanish end to end.** Walked by `testKnowledgeLanguageMovesSearchAndExploreToSpanish`: Spanish made main and English unticked in Settings, Search finds Spanish Wikipedia ("Luna"; its page offers Read in English), Explore's spread is a Spanish article from the Spanish index (read, it's "Wikipedia · Español" in Home's Continue). Seen and not chased: on the spread's topic page the test couldn't find the "Read in English" link that Search's Luna page has (20 swipes down); for Task 6's audit. A Spanish pack and Clásicos are the catalog's Spanish rows, listed by Get more from the same `languages()`; Get more re-reads them when opened (Android's list reacts live).
    - **Send feedback** (`Sources/Feedback/FeedbackSheet.swift`): Settings › About's row (Android's summary, with the crash count), and Home's crash line's Send, open Android's sheet: the intro, "The email includes" and `FeedbackReport.included` (from `IosLibrary.feedbackFacts`: build, device and iOS, tier "Library (Ask off)", free space, packs and books counts, crash reports), Attach crash reports, each report with View, Clear crash reports, Write email. Write email builds `FeedbackReport`'s subject, body and attachment (`granger-report.txt`) and opens `MFMailComposeViewController` to feedback@opengranger.org; without Mail, the share sheet with the same body (subject for apps that take one) and the attachment file. Handing it over acknowledges the crash line, as Android's. `ThisPhone.facts`: versionName/Code from the bundle, the model by name from `utsname` (iPhone 11 to 17 and Air; a newer identifier as it is; the Simulator says "iPhone 17 (Simulator)"), `UIDevice.systemVersion`, osName "iOS". `-debugMailText YES` (debug) shows the composed email instead of Mail or the share sheet, for the UI tests. Report sheet: `ReportSheet`/`ReportFlow` on the shared `AskReport` with Android's words; Ask is off on the iPhone as in Android's release, so only `-debugReportSheet YES` opens it.
    - **Crash log** (`Sources/Feedback/CrashLog.swift`): `CrashLog` over `IosLibrary.crashRecords(ThisPhone.facts)` in Application Support/granger/crashes. Kotlin's `setUnhandledExceptionHook` (`installCrashHook`, iosMain) and `NSSetUncaughtExceptionHandler` write a report at once ("Crash", "Thread: …", the trace); MetricKit (`MXMetricManager` subscriber, plus `pastDiagnosticPayloads` at each start) keeps crash diagnostics as "Native crash", hangs as "Not responding" and disk-write exceptions as a new shared kind `CrashKind.DISK` ("Too many disk writes"; Home doesn't ask about it, since it isn't a close; Android never writes it but its sheet names it, `feedback_kind_disk` in both languages). A MetricKit crash within a minute of a report Granger's own handler wrote is skipped, so one crash isn't listed twice. Newest five, the notice for this build only, Not now / Send, all the shared rules. `-debugCrash kotlin|objc` (debug) crashes a second after the start through the matching handler; after the report is written the debug build exits cleanly (`CrashLog.afterRecord`, so XCTest doesn't fail the test that asked for the crash). `-resetData` already empties `crashes/` (Application Support/granger) and the defaults (`knowledge.languages`). MetricKit never delivers in the Simulator: its conversion is untried until I8's device.
    - **Privacy manifests.** `ios/Granger/Resources/PrivacyInfo.xcprivacy` (app) and `ios/Granger/Widgets/PrivacyInfo.xcprivacy` (widget): `NSPrivacyTracking` false, no tracking domains, no collected data types. Required-reason APIs, app: UserDefaults CA92.1 (the app's own) and 1C8F.1 (the app group shared with the widget: Local only, the snapshot); file timestamps C617.1 (files in the container and app group: Kotlin's `stat`/`fstat` through kotlinx-io and `MappedFile`, `attributesOfItem`, SQLite, libzim, Zip) and 3B52.1 (music folders and imports the person picked in Files); disk space E174.1 (checking room before a download), 85F4.1 (shown: "free on this iPhone") and 7D9E.1 (in the feedback email the person chooses to send); system boot time 35F9.1 (Kotlin's monotonic clock, `mach_absolute_time`, for timings and timeouts). No active-keyboards API. Widget: UserDefaults 1C8F.1 only. Libraries: SQLite.swift, CryptoSwift and ZIPFoundation ship their own manifests; Readium (swift-toolkit), DifferenceKit, Fuzi, GCDWebServer, SwiftSoup, Zip, CoreKiwix/libzim, libarchive, TagLib and sherpa-onnx/onnxruntime don't, and none of them is on Apple's list of SDKs that must have one; they're linked into the app (sherpa-onnx is the one embedded dynamic framework) and their API use is covered by the app's declarations above. To recheck at I8: App Store Connect's upload report (ITMS-91053) on the first signed build.
    - **App Privacy answers for I8 (App Store Connect):** Data Not Collected. Why: Granger has no account, no analytics, no ads, no tracking and no server of its own. Reading online asks Wikimedia (and the catalog's hosts, LibriVox, archive.org) for what the person opens, with nothing about them; that isn't collection by the developer. Crash reports stay on the phone and leave only inside an email the person writes and sends from their own mail app (Apple: data sent by the user's choice through a user-initiated email is not "collected" by the app). Tracking: No.
    - **Words.** Android's through the catalog (feedback_*, report_*, home_crash_*, settings_language_*, settings_feedback_summary and its plural, onboarding_also_language). iPhone-only, in `ios-only-strings.json`: "iOS keeps the app's language: Settings › Granger › Language." → "iOS guarda el idioma de la app: Ajustes › Granger › Idioma."; "Opens Granger in the iPhone's Settings, where its Language row is" → "Abre Granger en los Ajustes del iPhone, donde está su fila Idioma". The crash reports, MetricKit's lines, the model names and the debug email are English data (as Android's), listed in `tools/ios/not-on-screen.txt`. `make_text_ids.py --check` passes.
    - **Tests.** `FeedbackUITests` (5, all pass): the email with no crash (build, phone and iOS, tier, free space, counts, nothing attached); a forced Kotlin crash after relaunch (Home's line, Send, the report counted and viewed with its header and trace, the attach switch off and on, the email's attachment, answered: no line); a forced Objective-C exception (the NSException handler's report, Not now, Clear); the Report sheet's email; Settings › Language (the App language row, lock, Make main, unticking) moving Search and Explore to Spanish. `CrashRecordsTest` gained the disk-write rule. Full Simulator suite from an uninstalled app (core-local, all fixtures): 91 tests (86 + 5), 86 passed, 3 skipped, 2 failed in the full run and passed alone straight after (SmokeTests `testExploreWalk`, Wander's centre past its 90 s wait; `testOnboardingPickAStart`, Review not shown within 10 s), so load, not this task: worth watching in Task 7's run. JVM suite 3931 (3930 + 1), 0 failures; `:app:bundleRelease` (R8) builds. Granger crash reports in `~/Library/Logs/DiagnosticReports`: six from today's forced test crashes (11:17:49, 11:17:56, 11:25:30, 11:25:39, 11:28:30, 11:29:29, all SIGABRT from `-debugCrash`), before the debug build learned to exit cleanly after writing; none since.

### Task 5: Search by meaning, the icon, the licences
- [ ] **Spike, one hour; stop and report if it fails** (call 6). LiteRT-LM's `EmbeddingEngine` for iOS from a fetch script, EmbeddingGemma 2 loaded in the Simulator, query vectors against Android's for 20 fixed queries (Android's numbers recorded from a JVM or Pixel run into a test fixture), cosine ≥ 0.99 each. Record the first query's time and the memory.
- [ ] If it passes: the dense half of Best matches as Android's (`ResearchPipeline`'s search part, already shared), the embedder through `EmbedderDownloads`, Models in Settings. If it fails: the parity row's reason, and on.
- [ ] The icon variants (call 7), shown to Neal before they go in.
- [ ] The licences screen (call 8), generated with its `--check`.

### Task 6: The audit and the hand-off
- [ ] Every row of `ios-parity.md` checked against the app as it is: ticked, ≈ with the iOS way noted, — with the agreed reason, or "waits for a device (I8)" with what's needed.
- [ ] The VoiceOver, Dynamic Type and landscape pass on every screen (call 9), the 12 XXXL tests fixed and one full XXXL run.
- [ ] Rows Android gained since I1 added (check Android's commits since 2026-11-20 against the list).
- [ ] `ios/HANDOFF.md` (call 10), tried from a fresh clone in a scratch folder: the fetch scripts, the build, the first run.

### Task 7: I7b check, merge and hand-off
- [ ] The whole Simulator suite from an uninstalled app, in English and in Spanish; the JVM suite if shared code changed.
- [ ] Walk it in Night and at the largest text size once more.
- [ ] Release GrangerCore, pin it, merge and push. Tell the peer sessions.
- [ ] Hand off to Neal: the note's link and the list of what needs a device.

## Order and size

I7 is mostly words and checks, not new machinery. The String Catalog is the biggest piece by count (about 1,800 strings, nearly all generated); the embedder spike is the biggest risk. If I7 runs long it splits at Task 5: Spanish, feedback and privacy first (Tasks 1–4) as their own merge, then the embedder, icon, licences and the audit.

**Device-only items carried to I8** (not I7's to finish): the mic (I6 Task 8's spike), CarPlay on a car and in the CarPlay window (the entitlement), the Music library's songs, remote buttons, LibriVox and archive.org audio against the live services, the app group on signed builds, and the embedder's speed on a phone.
