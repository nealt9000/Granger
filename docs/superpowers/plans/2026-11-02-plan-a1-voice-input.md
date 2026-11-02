# Plan A1: Voice input — a mic in the ask box and in Search

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** You can speak a Research question or a library search instead of typing it. A mic sits in the Research ask box and in Search's field and uses the phone's own on-device recognizer: no download for US English or Spanish on the Pixel 6, and nothing leaves the phone. The words appear in the field as you speak. In Search, a misheard title is caught by a **"Did you mean …?"** line, which comes from a title matcher run against the library. The mic is hidden on phones that can't recognize speech on the device.

**Counts as Research work** (Neal, 2026-11-02), so it goes ahead under the feature freeze, which still lets Ask keep improving.

**Architecture:**
- **`SpeechInput` in `:core:speech`** (beside `SystemSpeech`, the read-aloud side): `availability(tag)` (`Ready` / `Downloadable` / `Unavailable`), `listen(tag): Flow<DictationEvent>` (listening, partial text, end of speech, final text, error), `stop()`, `cancel()`, `download(tag, onProgress)`. One implementation, `OnDeviceSpeechInput`, on `SpeechRecognizer.createOnDeviceSpeechRecognizer`, plus a fake for tests. No biasing (the spike: +5 points at best, and it broke a name).
- **`SpokenTitleMatcher` in `:core:text`** (pure Kotlin, so the iPhone path can share it later): the spike's edit-distance matcher, improved with the Task 5 lessons. It scores on words as well as the whole string, so *the Bible* finds *The King James Bible*. It drops a leading verb in English and Spanish, and has a threshold.
- **`SpokenTitles` in the app:** the live library's names (packs, books and their authors, albums, artists, audiobooks), in short forms, as `tools/asr/bias_list.py` builds them. Read from Room and refreshed when the library changes.
- **`Dictation` and `MicButton` in `app/.../voiceinput/`:** a small state holder (idle / listening / error) plus one mic button composable, shared by the ask box and Search. They handle the microphone permission, the partial text and the stop tap.
- **Settings → Voice input:** the language (follows the app; for English, US or UK), a download for a language that is supported but not installed, and **"Ask as soon as I stop talking"** (off by default).

**What the spike settled** (`docs/superpowers/spikes/2026-10-voice-input-spike-results.md`): offline; questions at 3.3% word error rate; end of speech → final at p90 ≈ 0.4 s; unaffected by a resident E2B or by music playing (no ducking, none of it in the text); names at 40–45% exact on their own, 20/20 on the replays with the matcher. The first use after the recognition service has been stopped took **16.6 s**. The endpointer ends speech by itself, so Stop was never needed.

**Tech Stack:** Kotlin, Compose, `android.speech.SpeechRecognizer` (`createOnDeviceSpeechRecognizer`, `checkRecognitionSupport`, `triggerModelDownload`, API 31+/33+), Room, SharedPreferences (as `VoicePrefs`), JUnit + Truth, Robolectric for the view models.

**Roadmap:** `docs/superpowers/plans/2026-10-15-granger-v1-roadmap.md`, the A1 row.

## Neal's decisions (2026-11-02)

- **System recognizer as is, no biasing, a title matcher after recognition.** No bundled model; sherpa-onnx stays a later fallback.
- **The mic in both** the Research ask box and Search.
- **Hidden** on phones without on-device recognition. There is no fallback to the online recognizer.
- **Dictation only.** The mic fills the field. Spoken commands (*Open Peter Pan*, *Next chapter*, *Open music*) are a later row, not A1.
- **Ask: wait for Send by default**, with an **"Ask as soon as I stop talking"** switch in Settings.
- **The recognition language follows the app language.** An English app listens in US English, or UK English if chosen in Settings, offered as a download. A Spanish app listens in Spanish: in Search now, and in Ask once Spanish Ask is on, so the ask box shows no mic while Spanish Ask is off.

## Builder's calls in this plan (for Neal's review)

- **Search's "Did you mean …?"** appears only after a spoken search. It shows when the best library name scores at least the threshold and isn't already what was searched. Tapping it searches that name. Typed searches never see it. This is the matcher's only job in A1, because questions go to the ask box as heard and you fix any word before sending.
- **Ask with auto-send on:** a misheard name goes straight to the pipeline. That's accepted (you chose it), and Stop still works.
- **Cold first use:** the microphone gets 25 s to open, and after that "Listening…" waits for the first words with no timeout: the model can load after the microphone opens while the audio waits in a buffer (Task 1). No warm-up (Task 1 measured it).
- **While read-aloud is speaking** (an answer or a book), tapping the mic stops it first, so the recognizer doesn't hear Granger. Music keeps playing, as the spike showed it does no harm.
- **The microphone permission** is asked for on the first mic tap, with one line saying why. If you refuse twice, the mic stays and its tap explains how to allow it in the phone's settings.
- **Android 13 (API 33) and up only.** Below that, `checkRecognitionSupport` doesn't exist, so the app can't tell whether a language is installed, and the mic is hidden. The app's floor stays at API 29.
- **Spanish recognizer tag:** the best match among the installed languages (`es-ES` on the Pixel 6, else `es-US`, else any `es-*`). If none is installed but one is supported, Settings offers the download.

- **Built for conversation mode next** (Neal, 2026-11-02: the next thing to use A1 is a conversation mode in Ask, where you speak, the answer is read aloud, and it listens again). So `Dictation` knows nothing about a screen: it can be started from code as well as by a tap, its final text goes to a callback, and it never hears Granger's own voice (it starts only after read-aloud has stopped, and read-aloud stops before it starts). The "Ask as soon as I stop talking" path is the same send that conversation mode will make. A1 builds none of the loop.

## Global Constraints

- **The Ask session is done** (Neal, 2026-11-02; its owl work is on main), so the ask box is free to change. Task 6 still keeps to one slot and one view-model hook. Search's files are changed by L2 (unmerged), so Task 5 rebases on whatever has merged by then.
- **Strings:** L1's guard fails on any literal in UI code. Every new string goes in `values/strings.xml` with a Spanish entry in `values-es/strings.xml`, following L1's naming (`voice_input_*`). If L1 hasn't merged when this starts, start after it does, or rebase before Task 4.
- **No new download in the base app.** No model, no library. The permission is the only manifest change.
- **Release:** `RECORD_AUDIO` reaches the Play build. The data safety form and the privacy policy draft (`docs/friend-testing/privacy-policy.md`, on `cl-cleanup`) get one line each: audio is recognized on the phone and never collected or sent (Task 8).
- The phone is shared: the Plan 9 and R0 rules apply. Never `connectedDebugAndroidTest`; never `adb reboot`; never touch the phone while a personal app is in front. One Gradle build at a time with `-Pkotlin.compiler.execution.strategy=in-process`, then `./gradlew --stop`. Only the latest build on the phone, as `app.granger`.
- Pushing commits needs Neal's OK at that step.
- Worktree: `.claude/worktrees/a1`, branch `a1-voice-input`. `JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home`, `ANDROID_HOME=/opt/homebrew/share/android-commandlinetools`.

---

### Task 1: `SpeechInput` in `:core:speech`

**Files:** create `core/speech/src/main/kotlin/app/granger/core/speech/SpeechInput.kt` (interface, `DictationEvent`, `Availability`), `OnDeviceSpeechInput.kt`, `RecognizerTags.kt`; tests under `core/speech/src/test/`.

- [ ] `SpeechInput` and `DictationEvent` (`Listening`, `Partial(text)`, `EndOfSpeech`, `Final(text)`, `Failed(reason)`, where `reason` is one of no-match, no-permission, busy, unavailable, timeout, other).
- [ ] `OnDeviceSpeechInput`: one recognizer per take, created on the main thread, and destroyed on final, error, cancel or flow cancellation. Partials on, `EXTRA_PREFER_OFFLINE`, max results 5, no biasing. Final text falls back to the last partial when the result comes back empty (the live listener does the same; it never happened in 25 takes). A 25 s timeout to the first `onReadyForSpeech`, for the cold start. After a take ends, a new recognizer opens only 500 ms later (the spike's `SERVER_DISCONNECTED` lesson).
- [ ] `availability(tag)`: `isOnDeviceRecognitionAvailable`, then `checkRecognitionSupport` (installed → `Ready`, supported → `Downloadable`, else `Unavailable`), cached per tag for the process. `download(tag)` wraps `triggerModelDownload` with its progress.
- [ ] `RecognizerTags.forApp(locale, englishVariant, installed, supported)`: `en` → `en-US` / `en-GB`; `es` → the best installed `es-*` (see Builder's calls); any other language → null (no mic). Unit-tested.
- [x] *Built 2026-11-02:* `SpeechInput.kt`, `OnDeviceSpeechInput.kt` (the spike's rules: 25 s for the mic to open, takes 500 ms apart, one retry on busy, the last partial for an empty final), `AndroidRecognizerBackend.kt` (thin, main thread), `RecognizerTags.kt`. 17 unit tests with a fake backend in virtual time.
- [x] **Warm-up check (on the phone):** force-stop Android System Intelligence, then time the first take with and without a support check made 2 s before. Record it in the plan. If the check doesn't help, the screens don't warm. *Found while setting it up: in the spike's cold run the microphone opened in 40 ms, but speech was only taken up at 18.3 s. The model loads **after** "ready", and the audio waits in a buffer (no words lost). So the cold cost shows as a long wait for the first words, not as a slow start, and the check measures time to the first words, with one replayed WAV (`--es run warm --es warm none|support|session` on the spike screen).*
  *Result (2026-11-02, Pixel 6, `c01.wav` "Read aloud", 2.06 s, three rounds each, Android System Intelligence force-stopped before every run):*

  | Before the take | Speech taken up (`onBeginningOfSpeech`) | First final segment |
  |---|---|---|
  | nothing | 2.5 / 2.0 / 1.9 s | 2.8 / 2.3 / 2.4 s |
  | `languages()` support check, 2 s before | 1.6 / 1.9 / 1.6 s | 2.4 / 2.3 / 2.4 s |
  | a recognizer opened and cancelled, 2 s before | 0.9 / 0.8 / 0.7 s | 2.3 / 2.3 / 2.3 s |

  *A force-stop alone costs about 2 s, not the spike's 18 s; that state wasn't reproduced. The final text barely changes (the buffered audio catches up). **Decision: the screens don't warm.** A support check hardly helps, and opening a recognizer early would switch on the microphone (and the green privacy dot) without the user asking, for about a second earlier first words. Once the mic is open, A1 waits for the first words without a timeout, so a very cold first take is slow but not lost.*
- [ ] **Commit.** `A1: SpeechInput, the phone's on-device recognizer`

### Task 2: `SpokenTitleMatcher` in `:core:text`

**Files:** create `core/text/src/main/kotlin/app/granger/core/text/SpokenTitleMatcher.kt`, `core/text/src/test/.../SpokenTitleMatcherTest.kt`, `core/text/src/test/resources/spoken-titles/` (the spike's transcripts and the library names as a fixture: text only, no audio).

- [x] Start from the spike's `TitleMatcher` (`app/src/debug/.../voiceinput/TitleMatcher.kt`): normalise (lowercase, letters and digits, accents folded so *Berlioz* / *berleos* and Spanish titles compare fairly), drop a leading verb (`open`, `read`, `play`, `search for`, `abrir`, `abre`, `leer`, `lee`, `reproducir`, `pon`, `buscar`, `busca`), edit-distance ratio.
- [x] **Word score** (the Task 5 lesson): every word of the heard name, minus an article (`the`, `a`, `el`, `la`, `los`, `las`), close to a word in the title (ratio ≥ 0.8). The score is the larger of the whole-string ratio and the word score scaled by how much of the title is covered, so *the Bible* → *The King James Bible* beats *The Rivals*, and *Wikipedia* → "… by Wikipedia" titles beat *WikiMed*.
- [x] `best(heard, names): Match?` and `THRESHOLD` (start at 0.55; tune on the fixture). The fixture tests: all 20 replayed names hit; *the Bible* → *The King James Bible*; no Research question from either session scores above the threshold; *Credo* → *Crito*.
- [x] *Done 2026-11-02.* Three scores, the highest wins: spelling (whole phrase), words (every spoken word in the name, so *the Bible* → *The King James Bible*), and **contained** (every word of the name in a longer phrase, so *measure for measure by Shakespeare* → *Measure for Measure*; found while testing). Threshold **0.55** (*meiodaya* → *Mi Yodeya* scores 0.56). The fixture is the spike's 85 transcripts (text only) against 1,417 public book, pack and author names from the Pixel 6, with no music names since the repo is public. All names hit. A question may get a title only if it actually names it (*Who wrote The Tale of Genji*), which is right for Search.
- [x] **Commit.** `A1: a spoken title matcher, words and spelling`

### Task 3: `SpokenTitles`, the library's names

**Files:** modify `core/library/.../Daos.kt` and `MediaDaos.kt` (lightweight name queries: pack titles, book titles and authors, distinct albums and artists, audiobook titles); create `app/src/main/kotlin/app/granger/voiceinput/SpokenTitles.kt` and its test.

- [x] Port `short_title` / `short_author` from `tools/asr/bias_list.py` (drop subtitles after `:`/`;`, "by" forms, split multi-artist fields), remove duplicates, and keep only names with at least one letter.
- [x] Exposed as a `StateFlow<List<String>>` that rebuilds when the library's tables change (Room `Flow`s), so a pack or book added today is matched today (the Task 5 lesson: the spike's list was two days stale).
- [x] Test with an in-memory database: a book, a pack and an album with two artists give the expected names.
- [x] *Done 2026-11-02.* One `LibraryNameDao.observe()` query (a `UNION` over packs, books and their authors, audiobooks and their authors, albums, artists and album artists; missing files and audiobook file tracks left out), so Room re-emits on any change. `LibraryNames` in `:core:library` does the shortening. Artists are split on `;` only, not on commas (*Crosby, Stills & Nash*). `SpokenTitles` in the app shares it while watched (`AppContainer.spokenTitles`). On Neal's library: 1,477 rows → **1,417 names**, the query 8 ms on the Mac. Tests: 4 with an in-memory database, 1 for the shared list.
- [x] **Commit.** `A1: the library's names for the title matcher`

### Task 4: The permission, `Dictation` and `MicButton`

**Files:** modify `app/src/main/AndroidManifest.xml` (`RECORD_AUDIO`), `app/src/debug/AndroidManifest.xml` (drop its own copy); create `app/src/main/kotlin/app/granger/voiceinput/Dictation.kt`, `MicButton.kt`, `VoiceInputPrefs.kt`, strings; tests in `app/src/test/.../voiceinput/`.

- [x] `VoiceInputPrefs` (as `VoicePrefs`, SharedPreferences `voice_input`): `englishVariant` (`US` default, `GB`), `askWhenDone` (off).
- [x] `Dictation(speech, tagFor, scope)`: `state` (`Idle`, `Listening(partial)`, `Failed(message)`), `start(onText, onFinal)`, `stop()`, `cancel()`. Partial text goes to `onText` as it arrives; `onFinal` gets the final text once. Starting while read-aloud speaks stops read-aloud first. Tested with the fake `SpeechInput`: partials then a final, a no-match, a cancel mid-take, a timeout.
- [x] `MicButton`: shown only when `availability(tag)` is `Ready`. A mic icon; while listening, a filled, gently pulsing mic that stops the take when tapped; content descriptions *"Speak"* / *"Stop listening"*. The permission request on first tap with a one-line reason; a refusal message with the way to the phone's settings. *Didn't catch that* on no-match, which clears after 3 s.
- [x] *Done 2026-11-02.* `RECORD_AUDIO` and the `RecognitionService` query moved from the debug manifest to the main one. `VoiceInput` (app-wide, `AppContainer.voiceInput`) works out the language and whether the mic shows (`status`), asks the phone only while a mic screen watches, and stops read-aloud through `playerHub.stopVoice()` before each take. `Dictation` (per view model) and `MicButton`. The permission logic reuses the media permission's "asked before" rule and its settings link. The mic icon is one vector drawable, not the large extended icon set. Strings are in `strings_voice_input.xml` with a Spanish twin. 7 `DictationTest`s; the string guards pass. Fixed on the way: `OnDeviceSpeechInput.languages()` and `download()` now run on the main thread, as `SpeechRecognizer` requires.
- [x] **Commit.** `A1: the mic button, dictation state and the microphone permission`

### Task 5: The mic in Search

**Files:** modify `app/src/main/kotlin/app/granger/search/SearchScreen.kt`, `SearchViewModel.kt`; tests in `SearchViewModelTest.kt`.

- [x] A `MicButton` as the field's trailing icon (when the field is empty, or always if it fits; check on the phone). Partials fill the field; the final runs the search as Search on the keyboard does, and drops the keyboard.
- [x] After a spoken search, `SpokenTitleMatcher.best(query, spokenTitles)`. If it scores at least the threshold and the name isn't the query already, a **"Did you mean *The King James Bible*?"** line sits above the results; tapping it searches that name. Typing clears it.
- [x] View-model tests: a spoken *the domapada* shows *The Dhammapada*; a typed one doesn't; a spoken exact title shows nothing.
- [x] *Done 2026-11-02 (phone check still to do).* The mic is the field's trailing icon while `micShown`, and the placeholder reads "Listening…" during a take. A failure shows as a line above the results for 3 s. The library's names start loading at the first mic tap, and "Did you mean" waits up to 2 s for them. The matcher runs on an injected dispatcher (`Dispatchers.Default` in the app). Tests: 5 in `SearchVoiceTest`; the voice fakes moved to `VoiceFakes.kt` for Task 6.
- [x] **Commit.** `A1: speak a search, with "Did you mean"`

### Task 6: The mic in the Research ask box

**Files:** modify `app/src/main/kotlin/app/granger/research/ResearchScreen.kt` (one slot beside Send), `ResearchViewModel.kt` (one hook); tests in `ResearchViewModelTest.kt`.

- [x] A `MicButton` beside Send, hidden while an answer is running and when the app's language has no Ask (Spanish while Spanish Ask is off). *Note 2026-11-02: no code switch for "Spanish Ask off" was found on main after L4. Check with Neal what gates it before building this.* Partials fill `input`; the final replaces it.
- [x] With **Ask as soon as I stop talking** on, the final sends if `canSend`; otherwise it waits in the box with the cursor at the end.
- [x] A view-model test for each: the final waits; the final sends with the setting on; the mic hides while running.
- [x] *Done 2026-11-02 (phone check still to do).* There's no Spanish Ask switch yet (Neal: not built), so `ResearchViewModel.ASK_VOICE_LANGUAGES = setOf("en")` keeps the ask box's mic to English for now; change it when Spanish Ask lands. Spoken words go after anything already typed. 4 tests in `ResearchViewModelTest` (80 pass). Test quirk: in that class `advanceUntilIdle` can return before the voice status is looked up, so the helper waits on it with `first { }`.
- [x] **Commit.** `A1: speak a Research question`

### Task 7: Settings → Voice input

**Files:** modify `app/src/main/kotlin/app/granger/settings/SettingsScreen.kt`, `SettingsViewModel.kt` (or a small `VoiceInputSettings` section beside `MediaSettingsSection.kt`); strings.

- [x] A **Voice input** section, hidden when the phone has no on-device recognition. It shows the language the mic listens in (follows the app language), and for English a US / UK choice.
- [x] For a chosen language that is `Downloadable`: a **Download** button with progress through `SpeechInput.download`, and the size if the system reports it. Record the `en-GB` size and time in this plan. A scheduled download says so ("The phone will finish this on Wi-Fi").
- [x] **Ask as soon as I stop talking**, a switch, off by default, with one line underneath: *"Otherwise the question waits in the box for you to check it."*
- [x] *Done 2026-11-02 (phone check and the `en-GB` size still to do).* `VoiceInputSettingsSection` sits under Ask in Settings. It shows the language the mic listens in, the US / UK choice when it's English, the download (progress, "later, on Wi-Fi", failed), the switch, and one privacy line. The download runs in `VoiceInput`'s app scope, so leaving Settings doesn't stop it. Hidden when the phone has no on-device recognizer (`VoiceInputStatus.phoneCanListen`). 2 `VoiceInputTest`s.
- [x] **Commit.** `A1: Settings → Voice input`

### Task 8: Release notes, the phone walkthrough, merge

- [ ] Privacy policy draft and the data safety notes: one line each. The microphone is used only while the mic is on; speech is recognized on the phone by the system's on-device recognizer; nothing is recorded, kept or sent. (If `cl-cleanup` hasn't merged, add the line there in coordination, or note it in the release checklist.)
- [ ] *Walkthrough 1 (Neal, 2026-11-02):* Search, Ask (auto-send off and on), Settings, music, read-aloud and TalkBack all good; no crashes in the log. Two findings:
  - **British English downloaded but the mic stayed hidden.** The phone did install it (`installed: [en-GB, en-US]`), but `OnDeviceSpeechInput` kept its first answer for the life of the process, and `refresh()` didn't clear it. Fixed: `SpeechInput.forget()`, called by `VoiceInput.refresh()`, and after a finished download the phone is asked again, up to 5 times a second apart, until it lists the language. 3 new tests.
  - **No Search mic in Spanish.** Correct for what the phone reported: **es-ES was no longer installed** (installed on 2026-10-30, only downloadable by now; cause unknown, perhaps the system drops unused models). Downloading en-GB did *not* push it out: es-ES downloaded afterwards in **45 s** (through the spike screen), and all three were then installed together. When a language is missing, A1 offers the download in Settings (in Spanish when the app is in Spanish).
- [ ] **Pixel 6 walkthrough, airplane mode on:** in English, five spoken searches including two misheard titles (does "Did you mean" catch them?) and five spoken questions with auto-send off, then on. The cold first use after force-stopping Android System Intelligence. With music playing, and with an answer being read aloud (it stops). TalkBack reads the mic's labels. The app in Spanish: the mic in Search listens in Spanish, and the ask box has no mic. UK English downloaded and used once.
- [ ] **A phone that isn't a Pixel** (the release checklist's second phone, when it's here): the mic either works or is hidden, and never crashes.
- [ ] Roadmap A1 row updated; results in this plan. **Commit**, then merge to main and push with Neal's OK.

---

## Later (not A1)

- **Conversation mode in Ask (next):** speak, the answer is read aloud (V2's spoken answers), then the mic listens again, hands-free until you stop. It builds on `Dictation`, auto-send, and read-aloud's end-of-speech signal. Open questions for its own plan: how a turn ends (the endpointer, as in the spike), how to interrupt an answer by speaking (that needs echo handling, since the recognizer would hear the speaker), and what the screen shows.
- **Spoken commands:** *Open/Play/Read <title>* acting straight away, app sections (*Open music*), reader and player commands (*Next chapter*, *Pause*). The matcher's command-first rule from Task 5 of the spike belongs here.
- **Correcting names inside a question** (*Thorough* → Thoreau) against the library's entities before the pipeline sees it.
- **The iPhone path** (I0): `SFSpeechRecognizer` with `requiresOnDeviceRecognition`, the same `SpokenTitleMatcher`.
- **sherpa-onnx** as a downloadable fallback for phones with no on-device recognizer (spike Task 6, never run).
