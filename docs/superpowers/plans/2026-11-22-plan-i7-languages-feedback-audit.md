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

### Task 2: I7a check and merge
- [ ] JVM suite, `bundleRelease`, the Pixel 6 walkthrough above.
- [ ] Release GrangerCore, merge and push. Tell the peer sessions.

## I7b: the iPhone

### Task 3: Spanish
- [ ] Extend `make_text_ids.py` (call 1): the String Catalog with en and es from Android's resources, SwiftUI literals matched by English, `ios-only-strings.json` for the rest, `--check` for anything on screen without Spanish. Plurals from Android's `<plurals>`.
- [ ] Turn literals that can't be matched as written (built from pieces, formatted numbers) into keys with arguments, as Android has them.
- [ ] Add `es` to the project's localizations; Info.plist's usage strings and the widget's and CarPlay's words in both languages.
- [ ] Walk every screen in Spanish (the Simulator's per-app language) and fix clipped or overflowing labels; Spanish runs longer.
- [ ] UI tests: the app launched in Spanish shows Spanish on Home, Settings, a reader's bar and Listen; the catalog check is part of the unit tests.

### Task 4: Languages, feedback, crashes, privacy
- [ ] Settings › Language: the app language row (call 2) and the knowledge languages screen on the shared model; onboarding's language step as call 2 says.
- [ ] Spanish knowledge end to end: Spanish Wikipedia online, a Spanish pack, Clásicos, the Spanish time index and topic records, the rooms' Spanish descriptions, all reached from the knowledge language.
- [ ] Send feedback and the Report sheet (call 3) on the shared `FeedbackReport`, with the share-sheet fallback.
- [ ] The crash log (call 4): Kotlin and `NSException` handlers, MetricKit's payloads, newest five, attached when chosen.
- [ ] The privacy manifests (call 5), and the App Privacy answers in the Task 4 note.
- [ ] UI tests: the feedback text holds the build and the counts, with and without a crash; a forced debug crash is in the log after a relaunch; the knowledge language moves Search and Explore to Spanish.

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
