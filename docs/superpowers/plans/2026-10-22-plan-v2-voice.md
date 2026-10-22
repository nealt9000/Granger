# Plan V2: The voice — a better voice, the mix, the full view, spoken answers — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Finish project 3. The Piper voice reads years, decades and ranges the way a person does; a second, better-sounding catalog voice ships after a listening session on the Pixel 6 with voices whose licences are clean; the reader's voice button pauses instead of stopping; music keeps playing under the voice at a remembered level with the transport driving the voice; the voice gets its full-view sheet, a live entry in the rail, and a speaker button on Research answers (spoken as they stream when the setting is on); and `ReadAloud.start(target)` is there for project 4. Spec: `docs/superpowers/specs/2026-10-21-voice-design.md` §3.4, §4.3, §5, §6, §7, §10, §11. What V1 left and measured: the roadmap's "Carried forward from V1"; the spike's numbers: `docs/superpowers/spikes/2026-10-voice-spike-results.md`.

**Out of this plan:** voice input; languages beyond English; new engines (KittenTTS v0.8, Apache-2.0, 15–80M parameters, and Kyutai's Pocket TTS are in sherpa-onnx 1.13.8's model release and would need their own `OfflineTtsConfig` paths and a spike; Supertonic's weights are OpenRAIL-M and its maker is winding down, so it is out); Android Auto for read-aloud; the espeak-ng extra-dictionary route for names (kept for when a list of mispronounced names exists); Research deciding what to read (project 4).

**Architecture (what changes):**
- **`:core:speech`:** `SpokenText` (text normalisation before Piper: years, decades, ranges, circa, degrees, units after numbers) applied in `SherpaSpeech` before `Pronunciations`; `VoiceId.Granger` gains an optional speaker; `InstalledVoices` knows a voice's speakers.
- **`:core:player`:** `PlayerEngine`'s voice mode gains **music under it**: ExoPlayer plays the music queue at the mix level while the voice goes to its `AudioTrack`; `SessionPlayer` becomes a `ForwardingSimpleBasePlayer` so the session (notification, lock screen, headset) shows the voice and drives it while music plays underneath. The carrier item stays for the voice alone. `ReadSource` gets a streaming variant for answers that are still being written.
- **`:app`:** the voice's full-view sheet over the mini-player; the pause/stop pair in both readers; the second line in the mini-player; the Research speaker button and the "Speak answers as they arrive" setting; `ReadAloud` (the app-level start); the rail's live entry and the widget's voice resume; Settings → Voice gains the speaker picker, the mix level and the answers switch.
- **Catalog:** `VoiceEntry.speakers[]` (a short list of named speakers for multi-speaker voices); the second voice on release `voices-v1`; the catalog re-signed.

**Tech Stack:** unchanged: Kotlin 2.4.21, Compose Material 3, Media3 1.11.1 (`ForwardingSimpleBasePlayer`), Room 2.8.5 (no migration: the mix level and the answers switch are preferences), Readium 3.4.0, sherpa-onnx 1.13.8, Robolectric 4.17, JUnit 4 + Truth. Voice packaging: `tools/voices/pack_voice.py` (fetches by id from sherpa-onnx's `tts-models` release).

**Roadmap:** `docs/superpowers/plans/2026-10-15-granger-v1-roadmap.md`, project 3, after V1 (Done 2026-10-22).

## What the code looks like now (surveyed 2026-10-22, `main` at `f1400a1`)

- **Text before Piper:** `SherpaSpeech.synthesize` runs `Pronunciations.apply` (whole-word respellings, table empty) and hands the text to `SherpaTts` (speaker 0, two threads). espeak-ng, Piper's phonemizer, reads "1799" as "one thousand seven hundred ninety nine", "1962" as "nineteen hundred sixty two", "1960s" as "nineteen hundred sixty z", drops the dash in "1914–1918" (the years run together) and "pages 12–15", reads "c. 1850" as "see one thousand…", "km" as "K M"; "1,234", "20th" and "$5" are fine (checked on the Mac with `espeak-ng -v en-us -q -x`, 2026-10-22). The system engine gets the original text and handles these itself.
- **Voices:** `VoiceId.Granger(dir)` encodes as `granger:<dir>`; `Voice(id, title, language, speakers)`; `SherpaVoice.detect/list` reads any Piper directory under a volume's `voices/`, so a voice pushed by adb shows in Settings → Voice at once. `InstalledVoices.rank` puts the catalog voice first, other Piper voices next. The catalog's `voices[]` has one entry (`vits-piper-en_US-ljspeech-medium`, release `voices-v1`); `VoiceEntry` carries `license` and `attribution`, which Settings shows.
- **The player:** `PlayerEngine` modes `NONE, MUSIC, BOOK, VOICE`; `readAloud()` calls `stopCurrent()` (music is saved and stopped) then `startVoice()`, which loads a silent `CarrierSource` with "Reading · <title>" metadata; ExoPlayer's `onPlayWhenReadyChanged` drives `ReadAloudEngine.play()/pause()`; `applyVolume()` sets `player.volume` and the speech output's `volume` to `duck × fade`; focus is requested as speech for every non-music mode. `SessionPlayer : ForwardingPlayer` routes play/pause/next/previous/seekBack/seekForward to the engine and drops the seek commands in voice mode. Saved place: `voice_states` (library v11) through `PositionSaver`; `LAST_MODE = "voice"` and `LAST_VOICE = <source key>` in prefs; `restoreVoice()` reopens the source through `readSources` and loads the carrier paused.
- **Mini-player and view model:** `MiniPlayer` shows "Reading · <section>" with play/pause and back-a-sentence; Open goes to the page (`openVoiceSource` in `GrangerNavHost`), falling back to the Player for a `text:` key. `PlayerViewModel` has `stopVoice/nextParagraph/previousParagraph/previousSentence`; `modeOf(Voice) = null` (no tab). Sheets exist for speed (`SpeedSheet`, 0.5–3.0×) and the sleep timer.
- **Readers:** the web reader's ⋯ menu toggles "Read aloud" / "Stop reading" (`ReaderViewModel.readAloud/stopReading`, `readingThisPage`); the e-book bottom bar's headphones button does the same (`EbookBottomBar(onReadAloud, reading)`, `EbookReaderViewModel.readingThisBook`). Neither offers pause; the mini-player and the headset do.
- **Research:** `AnswerText.segments` separates words from `[n]` chips and strips `*` marks; `TurnUi(turn, partial, stage, medical, …)`; `ResearchEvent.Partial` carries the answer so far while the model writes; `ResearchTurnDao.turn(id)` reads a stored turn. `TextReadSource(key, title, text)` reads plain text through the player with `text:<key>#<block>:<n>` addresses; `readSources` returns null for `Text` keys, so a spoken answer can't be restored yet.
- **Rail:** a read-aloud is a recent play `voice:<source key>` with the library icon; tapping it resumes through `PlayResumer`. The widget's resume ignores the voice.
- **Settings → Voice:** the voice list (`splitVoices` folds other-region system voices), Get/Remove with licence, speed, "Mark the sentence being read". `VoicePrefs` holds the voice id, speed and the mark switch.
- Tests: 1730+ JVM tests pass (`./gradlew testDebugUnitTest`); `PlayerEngineTest` (36) and `PlayerEngineVoiceTest` run on `PlayerTestRig`; `SessionPlayerTest` covers the command set.

## Decisions

1. **Years, out loud.** A bare four-digit number from 1000 to 2099 is a year unless it is followed by a unit or a per-cent sign or preceded by a currency sign, "#" or "No.": 1066 "ten sixty-six", 1100 "eleven hundred", 1799 "seventeen ninety-nine", 1900 "nineteen hundred", 1962 "nineteen sixty-two", 2000 "two thousand", 2005 "two thousand five", 2010 "twenty ten". Decades: 1960s "nineteen sixties", the 1700s "the seventeen hundreds". A dash between two numbers is "to"; "c." before a year is "circa"; "°C/°F" are "degrees Celsius/Fahrenheit"; km, kg, cm, mm, mi, ft, lb, mph after a number are spoken in full. "1500 people" becomes "fifteen hundred people", which is how it is said anyway. The pass runs for Piper only.
2. **The reader's voice button is pause.** While the voice reads this page or book, the e-book bar's button and the web reader's ⋯ entry show Pause / Resume; Stop moves to the full-view sheet and to a second ⋯ entry ("Stop reading"); a long press on the e-book button stops with a snackbar. The headset and the mini-player are unchanged.
3. **The second voice is chosen by ear from voices whose licences are clean.** The licence check (2026-10-22, Piper model cards): trained from scratch and permissive are `en_US-libritts-high` (CC BY 4.0, 904 speakers, trained from scratch on train-clean-360, 131 MB), `en_US-norman-medium`, `en_US-kristin-medium` and `en_US-kusal-medium` (public domain LibriVox, single speakers, 67 MB each), and `en_US-john-medium` (public domain, fine-tuned from `kristin`). Out: `libritts_r`, `vctk`, `jenny_dioco`, `alba`, `cori`, `aru`, `northern_english_male`, `sam`, `reza_ibrahim`, `danny`, `joe` (all fine-tuned from `lessac`, whose data is non-commercial), `ryan` (CC BY-NC-SA), `bryce` (unknown base). `libritts-high` is the kin of the `libritts_r` Neal liked, but a "high" model costs two to three times a medium one, so it ships only if it clears RTF ≤ 0.5 with the screen off; its int8 build is measured too. A multi-speaker voice ships with a short named list of speakers (three or so Neal picks), not 904.
4. **Music under the voice.** Starting the voice while music plays keeps the music at the mix level (default 30 %, slider 0–100 % in the full view, remembered); starting music while the voice reads keeps the voice; an audiobook and the voice still don't mix. While both play the transport (headset, notification, mini-player, lock screen) drives the voice and the music has its own row in the sheet (spec decision 1). When the voice ends or stops, the music comes back to full volume and becomes the mode. A restore after process death brings back the voice paused, alone; the music queue stays saved for its own tab.
5. **Spoken answers.** The speaker button on an answered turn reads the whole answer, stripped of Markdown and citation chips, with the medical banner spoken first when it applies. With "Speak answers as they arrive" on (off by default, spec decision 2) a new answer is read as its sentences close. A spoken answer is a `text:answer:<turnId>` source, so it shows in the rail and resumes.
6. **`ReadAloud.start(target, from)`** is a plain object in `:app` with three targets (an article, a book or a chapter of it, an answer); project 4 drives it, nothing in V2 but the rail and the readers call it.
7. **The rail** shows a live reading at the top of its list ("Reading aloud: Hamlet", voice icon) while the voice is loaded; tapping it opens the page. Past readings stay recent plays, with the same icon.
8. **The phone:** free as of 2026-10-22 (the R1 session's eval is done; its build, main plus R1 at library v11, is installed). Both databases are backed up before any install; the R1 session is told before the phone is taken.

## Global Constraints

- **Nothing network-bound at speech time**, in either engine.
- **Read-aloud keeps V1's acceptance:** no stall with the screen off under the foreground service; `PlayerEngineVoiceTest` stays green; the media parity rows that touch focus, the mini-player and the notification don't regress (`docs/superpowers/plans/media-parity-checklist.md`).
- **The mix must not cost the voice its headroom:** V0 measured music decoding under the voice at RTF 0.17–0.18 (from 0.11); the full view's slider at 100 % is allowed, but the engine's look-ahead stays at two sentences and the screen-off check in Task 8 runs with music underneath.
- **One session, one notification, one focus request** (as speech while the voice is active).
- **Don't lose the place:** the voice's saves are unchanged; music under the voice saves as music does.
- **Licences:** every catalog voice names its dataset licence and attribution in `curated.json`; CC BY voices show the attribution in Settings (the field exists).
- **Words in the UI:** "Read aloud", "Pause reading", "Resume reading", "Stop reading", "Reading", "Music under the voice", "Speak answers as they arrive", "Speaker".
- JVM tests: `./gradlew testDebugUnitTest` and `./gradlew :core:research:test` pass. No `connectedDebugAndroidTest` without Neal's go-ahead (it wipes the library). Device checks on the Pixel 6 over Wi-Fi, never while a personal app is in front, never `adb reboot`; the phone is shared with the R1 session.
- In a new agent worktree: copy `local.properties`, `git submodule update --init --recursive`, and build with `-Pkotlin.compiler.execution.strategy=in-process`. The sandbox refuses compound shell in worktrees; split commands.

## Review Focus

- `SpokenText`: what it must *not* touch: "1,234", "20th", "$1500", "1500 km" (the unit is spoken, the number stays cardinal), "No. 1066", ISBNs and phone numbers (digit runs longer than four), decimals ("1.5"), times ("19:62" doesn't occur; "10:30" stays), ordinals in headings. Offsets: the normalised text is what Piper hears, but the reader's highlight uses the utterance's original `start/end`, so the pass must happen after sentence splitting, inside the engine, never on the utterance.
- The mix in `PlayerEngine`: focus held once; `applyVolume` keeps `player.volume = mix × duck × fade` only while the voice is loaded; a music track ending under the voice advances the queue, the queue's end leaves the voice reading; `stopVoice()` with music underneath hands the mode to music without a gap in the notification; `ReadAloudEngine.play/pause` no longer follows ExoPlayer's `playWhenReady` while music plays (the voice has its own play state); the sleep timer fades both; a call pauses both and GAIN resumes both.
- `SessionPlayer` as a `ForwardingSimpleBasePlayer`: the state it reports while music plays under the voice (metadata, `playWhenReady`, available commands) and that `MediaControllerPlayerHub`'s in-process path and the notification agree; Android Auto still browses and plays music.
- Streaming answers: the source suspends on `utterances()` while the answer is being written, ends cleanly when the turn finishes, and a stopped or failed turn ends the read; the medical banner is spoken once.
- Restore of a `text:answer:` key through the chats table; a deleted chat clears the saved voice state with the one notice.
- The speaker picker: `VoiceId` decode stays backward compatible (`granger:<dir>` is speaker 0); `voice_states.voiceId` rows written by V1 still decode.

---

### Task 1: `SpokenText` — years, decades, ranges, circa, degrees and units before Piper

**Files:**
- Create: `core/speech/src/main/kotlin/app/granger/core/speech/SpokenText.kt`
- Modify: `core/speech/src/main/kotlin/app/granger/core/speech/SherpaSpeech.kt` (apply `SpokenText.forPiper` before `Pronunciations.apply`), `Pronunciations.kt` (doc: the year pass lives in `SpokenText`)
- Create: `core/speech/src/test/kotlin/app/granger/core/speech/SpokenTextTest.kt`

**Interfaces:**
```kotlin
/** Text as a Piper voice should hear it. Runs on one sentence at a time, after splitting, so the reader's highlight offsets stay the utterance's. */
object SpokenText {
    fun forPiper(sentence: String): String
    /** "1799" → "seventeen ninety-nine"; null when [n] isn't read as a year. */
    fun year(n: Int): String?
}
```

**Rules (Decision 1):** tokens are matched with lookarounds on the folded sentence; replacements are plain words, so espeak-ng gets no digits where a year was. Order: ranges first ("1914–1918" → "1914 to 1918", "12–15" → "12 to 15", en dash, em dash and a spaced hyphen between numbers), then "c." / "ca." before a year → "circa", then decades ("1960s" → "nineteen sixties", "1700s" → "seventeen hundreds", "'60s" left alone), then years, then "°C"/"°F"/"°" and the unit table after a number (km, kg, cm, mm, mi, ft, lb, lbs, mph, km/h, kph: singular after 1, plural otherwise). Exclusions: a digit run longer than four, a comma or a decimal point adjacent, a preceding currency sign, "#" or "No.", a following "%", "th/st/nd/rd", or a unit in the table (the unit is spoken, the number stays).

- [x] **Step 1: Failing tests:** `yearsAreSpokenAsYears` (table: 1000 "one thousand", 1066 "ten sixty-six", 1100 "eleven hundred", 1799 "seventeen ninety-nine", 1800 "eighteen hundred", 1962 "nineteen sixty-two", 2000 "two thousand", 2005 "two thousand five", 2010 "twenty ten", 2026 "twenty twenty-six", 999 null, 2100 null), `decadesAndRanges` ("The 1960s" → "The nineteen sixties", "the 1700s" → "the seventeen hundreds", "1914–1918" → "nineteen fourteen to nineteen eighteen", "pages 12–15" → "pages 12 to 15"), `circaAndDegrees` ("c. 1850" → "circa eighteen fifty", "37 °C" and "37°C" → "37 degrees Celsius"), `unitsAfterNumbers` ("5 km" → "5 kilometers", "1 km" → "1 kilometer", "3.5 km", "60 mph"), `whatMustNotChange` ("1,234", "20th", "$1500", "No. 1066", "ISBN 0-306-40615-2", "12345", "1.5", "10:30", "Room 101", "in 1500 km" → "in 1500 kilometers"), `theReadersHighlightOffsetsAreUntouched` (an `Utterance` built from "He died in 1962." keeps `start/end`; only the engine's text changes).
- [x] **Step 2:** `./gradlew :core:speech:testDebugUnitTest --tests 'app.granger.core.speech.SpokenTextTest'`. Expected: compile failure. *The implementation landed while the first run was going, so the run saw 8 tests with 1 failing (the unit regex matched one digit of "11 km"), fixed before Step 4.*
- [x] **Step 3: Implement.** Number words for 0–99 and hundreds; `year()` by century: `xx00` → "<xx> hundred", 2000–2009 → "two thousand [n]", 1000–1009 likewise, else "<xx> <yy>" with a hyphen in 21–99. Check the phonemes on the Mac for a few outputs (`espeak-ng -v en-us -q -x "seventeen ninety-nine"`) and note anything odd in the test file.
- [x] **Step 4:** The same command, then `:core:speech:testDebugUnitTest`. Expected: PASS. *44 speech tests, `:core:player` tests and the app's test compile green. US spelling for units ("kilometers"); espeak-ng phonemizes "seventeen ninety-nine", "eighteen oh five", "nineteen sixties" and "circa" cleanly.*
- [x] **Step 5: Commit.** `Speech: SpokenText — years, decades, ranges, circa, degrees and units before Piper`

### Task 2: The second voice — listening session and the numbers (Neal, the phone)

No app code. The candidates are packed with the existing tool and pushed under the app's `voices/`, where `InstalledVoices` lists them in Settings → Voice as "Granger voice (<name>)".

**Files:**
- Modify: `docs/superpowers/spikes/2026-10-voice-spike-results.md` (a "Second voice (V2)" section: the licence table from Decision 3, the RTF table, Neal's ratings, the choice)
- Scratch, outside git: `/Users/s.nealthompson/Developer/Granger/voice-spike-2026-10-21/voices-v2/` (the packed candidates)

- [ ] **Step 1:** Tell the R1 session the phone is being taken; back up both databases on the phone (`files/db-backup-<date>/`).
- [ ] **Step 2:** Pack `vits-piper-en_US-libritts-high`, `vits-piper-en_US-libritts-high-int8` (packed from its own tarball; keep its directory name), `vits-piper-en_US-norman-medium`, `vits-piper-en_US-kristin-medium`, `vits-piper-en_US-kusal-medium`, `vits-piper-en_US-john-medium` with `tools/voices/pack_voice.py` in an empty directory (downloaded archives are untrusted input), then `adb push` each unpacked directory to the app's `files/voices/` (`run-as` or the debug build's external files dir, as V0 did). Remove the spike's `libritts_r` and `kokoro` directories from the phone at the same time (they are unshippable and outrank nothing now, but they clutter the list).
- [ ] **Step 3: Numbers.** With Granger in front, read the three spike passages through the player (`VoiceSpikeActivity --ez service true --es engine <id>`) for `libritts-high` and `libritts-high-int8`, screen on then screen off for five minutes each; read the `Voice: synth` lines from logcat (`tools/spike/voice/tabulate.py`). Bar: RTF ≤ 0.5 with the screen off, first audio ≤ 2 s for ordinary sentences. The medium candidates are the same size and speed as `ljspeech` and are not re-measured.
- [ ] **Step 4: Listening (Neal).** In Settings → Voice pick each candidate and read the same Classics page and the same Wikipedia article at 1.0× and 0.8×; for `libritts-high` try speakers by setting `--ei speaker` in the spike activity (it takes a speaker id; the app proper speaks speaker 0 until Task 3). Rate naturalness, names and numbers, pacing, 1–5, as in V0; name the speakers worth keeping (three at most).
- [ ] **Step 5:** Record the tables and the choice in the results doc. If `libritts-high` misses the bar and a medium voice is chosen, say so there; if nothing beats `ljspeech` to Neal's ear, the catalog keeps one voice and Task 3 ships only the speaker plumbing. **Commit.** `Voice V2: second-voice licence check, numbers and listening`

### Task 3: Speakers, and the second voice in the catalog

**Files:**
- Modify: `core/speech/src/main/kotlin/app/granger/core/speech/Speech.kt` (`VoiceId.Granger(dir, speaker: Int = 0)`, encoded `granger:<dir>` for speaker 0 and `granger:<dir>@<n>` otherwise; `Voice.speakers` already exists), `SherpaSpeech.kt` (speaker from the id), `InstalledVoices.kt` (one `Voice` per installed directory with `speakers` from the model config; `preferred()` matches by dir when the saved speaker is gone)
- Modify: `core/catalog/src/main/kotlin/app/granger/core/catalog/Catalog.kt` (`VoiceEntry.speakers: List<NamedSpeaker> = emptyList()`, `NamedSpeaker(id: Int, title: String)`), the catalog parser and `tools/catalog-gen/catalog_gen.py` (copied through), `tools/catalog-gen/curated.json` (the new entry; `speakers` for a multi-speaker voice)
- Modify: `app/src/main/kotlin/app/granger/settings/VoiceSettingsScreen.kt`, `VoiceSettingsViewModel.kt` (a Speaker row under a chosen multi-speaker voice: the catalog's named speakers as chips, "Other…" with a number field for the rest), `core/player/.../PlayerEngine.kt` (nothing: the id carries the speaker)
- Tests: `SpeechTest`/`InstalledVoicesTest` (encode/decode with and without a speaker; V1 rows decode), `CatalogParserTest` (speakers), `VoiceSettingsViewModelTest` (picking a speaker writes the id)
- Hosting: pack the chosen voice (`tools/voices/pack_voice.py <id> --out …`), `gh release upload voices-v1 <zip>` (Neal's word first), then `scratchpad/catalog-sign.sh`-style build, sign, verify, commit on main.

- [ ] **Step 1: Failing tests** as named.
- [ ] **Step 2:** `./gradlew :core:speech:testDebugUnitTest :core:catalog:testDebugUnitTest :app:testDebugUnitTest --tests 'app.granger.settings.*'`. Expected: compile failure.
- [ ] **Step 3: Implement.** `SherpaVoice` reads `num_speakers` from the model's config JSON (already parsed for the sample rate); `InstalledVoices.rank` keeps the catalog voices first (`CATALOG_VOICES` becomes a list, in catalog order).
- [ ] **Step 4:** The same command, then `testDebugUnitTest`. Expected: PASS (`PublishedCatalogTest` on the committed catalog).
- [ ] **Step 5:** Upload the zip, build and sign the catalog, verify the live file lists both voices. **Commit.** `Voices: speakers; the second catalog voice (<id>); catalog re-signed`

### Task 4: Pause and stop in the readers

**Files:**
- Modify: `app/src/main/kotlin/app/granger/ebook/ReaderControls.kt` (`EbookBottomBar`: `voice: VoiceButton` = `Idle | Reading | Paused`; icon headphones / `ic_pause` / `ic_play`, tint flame while reading or paused; `onReadAloud`, `onPauseResume`, `onStop` (long press, with a snackbar "Stopped reading")), `EbookReaderScreen.kt`, `EbookReaderViewModel.kt` (`readingThisBook` becomes `voice: VoiceButton` from `player.ui` + `voiceProgress`; `pauseResume()` = `hub.playPause()` when this book is the voice's source)
- Modify: `app/src/main/kotlin/app/granger/reader/ReaderScreen.kt` (⋯: "Read aloud" | "Pause reading" / "Resume reading" + "Stop reading"), `ReaderViewModel.kt` (`readingThisPage` → the same three-state; `pauseResume()`)
- Tests: `EbookReaderViewModelTest` (`voiceButtonFollowsThePlayerForThisBookOnly`, `pauseResumeTogglesThePlayerNotTheSource`), `ReaderViewModelTest` likewise; a Compose test for the bar's three icons and the long-press stop.

- [x] **Step 1: Failing tests** as named. *Plus `VoiceButtonTest` (the shared three-state rule) and `EbookBottomBarTest` (Compose: the three icons, tap and long press).*
- [x] **Step 2:** `./gradlew :app:testDebugUnitTest --tests 'app.granger.ebook.*' --tests 'app.granger.reader.*'`. Expected: compile failure.
- [x] **Step 3: Implement.** *`VoiceButton` lives in `app.granger.player` and both view models publish it beside `readingThisBook`/`readingThisPage` (which stay: Read from here still moves a paused voice). The bar button is a `combinedClickable` box, since `IconButton` has no long press; with no long-press action Compose treats a long press as a tap, so the idle button starts on either.* Paused means: the voice's source is this page or book and `ui.playing` is false and not `ended`; ended shows Read aloud again.
- [x] **Step 4:** The same command. Expected: PASS. *222 tests across `ebook`, `reader` and `player`.*
- [x] **Step 5: Commit.** `Readers: the voice button pauses and resumes; Stop moves to a long press and the ⋯ menu`

### Task 5: Music under the voice — the engine, focus, the session

**Files:**
- Modify: `core/player/src/main/kotlin/app/granger/core/player/PlayerEngine.kt`, `PlayerHub.kt` (`NowPlaying.Voice.music: Track?` (the track under it, null when alone); `PlayerUi` unchanged; new `setMixLevel(level: Float)`, `playPauseMusic()`, `nextMusic()`; `SavedModes` unchanged), `PlaybackService.kt` (`SessionPlayer` rewritten over `ForwardingSimpleBasePlayer`: in voice mode its state is the voice's — "Reading · <title>" metadata, the voice's `playing`, no seek commands, next/previous by paragraph — whether the underlying ExoPlayer holds the carrier or the music; otherwise it forwards as today), `PlayerSettingsKeys.kt` (`MIX_LEVEL`, default 0.3f), `FocusPolicy.kt` (unchanged: one request, speech while the voice is loaded)
- Modify: `core/player/src/main/kotlin/app/granger/core/player/voice/ReadAloudEngine.kt` (nothing structural; `playing` is already its own state)
- Tests: `PlayerEngineVoiceTest` (`startingTheVoiceOverMusicKeepsTheMusicAtTheMixLevel`, `startingMusicWhileReadingKeepsTheVoiceAndDucksTheMusic`, `transportDrivesTheVoiceWhileMusicPlays` (play/pause/next/previous go to the voice; `playPauseMusic` to ExoPlayer), `aTrackEndingUnderTheVoiceAdvancesTheQueue`, `theQueueEndingLeavesTheVoiceReading`, `theVoiceEndingHandsTheModeToMusicAtFullVolume`, `stopVoiceWithMusicUnderneathBecomesMusicWithoutAGap`, `anAudiobookStillStopsTheVoice`, `aCallPausesBothAndGainResumesBoth`, `theSleepTimerFadesBoth`, `restoreAfterDeathBringsTheVoiceBackAloneAndPaused`, `mixLevelIsRememberedAndApplied`), `SessionPlayerTest` (the reported state in each of the three shapes: music, voice alone, voice over music; available commands), `PlayerEngineTest` unchanged and green.

**Engine shape:** `mode == VOICE` gains `musicUnder: MusicQueue?`. `readAloud()` while `mode == MUSIC` keeps the queue and tracks, sets `musicUnder`, lowers `player.volume` to the mix, and does *not* load the carrier; `startVoice()` learns `keepMusic`. `onPlayWhenReadyChanged` drives the voice only when the carrier is loaded; with music underneath the voice's play/pause comes from `resume()/userPause()` directly, and the focus listener pauses and resumes both. `activate(MUSIC)` while the voice reads over music is a no-op; `playTracks()` while the voice reads starts the music underneath (Decision 4). `stopCurrent()` from voice-over-music becomes "the voice stops, music stays": `mode = MUSIC`, `applyVolume()`, the voice's state saved. Saving: the music `PositionSaver` keeps saving as music; the voice as voice; `LAST_MODE` stays `voice` while it reads.

- [x] **Step 1: Failing tests** as named. *Nine new cases in `PlayerEngineVoiceTest`; `SessionPlayerTest` unchanged.*
- [x] **Step 2:** `./gradlew :core:player:testDebugUnitTest`. Expected: compile failure.
- [x] **Step 3: Implement.** *Builder's call, after reading Media3 1.11.1's source: `ForwardingSimpleBasePlayer` was dropped before it was tried. Its public `seekToNext()` is final and only reaches `handleSeek` when the reported playlist has a next item, so a one-item playlist (the voice's carrier, a single-file audiobook) would swallow next and previous from the notification and the headset, which V1 shipped working through `ForwardingPlayer`'s overrides. Instead the music under the voice plays on a **second ExoPlayer** (`musicUnderFactory`, built like the first with its own `ReplayGainProcessor`), while the main player keeps the carrier: the session, the notification, the lock screen and the headset go on driving the voice exactly as in V1, `SessionPlayer` is untouched, and the mix is `musicUnder.volume = mix × duck × fade`. The queue moves between the players with its index and position (a short gap in the music at the hand-over); the music row's pause is remembered apart from pauses with the voice (a call, a headset unplug, the sleep timer), which resume with it. `NowPlaying.Voice` carries `music` and `musicPlaying`; `PlayerHub` gains `setMixLevel`, `playPauseMusic`, `nextMusic`. Without a factory (tests, by default) V1's behaviour stands.*
- [x] **Step 4:** The same command. Expected: PASS, including every existing `PlayerEngineTest`. *120 player tests; the app's player, widget and media tests green.*
- [x] **Step 5: Commit.** `Player: music under the voice — a second player at the mix level; the transport keeps driving the voice`

### Task 6: The mini-player's second line and the voice's full-view sheet

**Files:**
- Modify: `app/src/main/kotlin/app/granger/player/MiniPlayer.kt` (when `Voice.music != null`, a second thin line "♪ <track> · <artist>" under the section; tapping the row opens the sheet instead of the page; the page is the sheet's **Open**), `PlayerViewModel.kt` (`setMixLevel`, `playPauseMusic`, `nextMusic`, `stopVoice`, `voiceSentences: StateFlow<Triple<String?, String, String?>>` (previous, current, next from the engine's buffer: `ReadAloudEngine` exposes `neighbours()`), `voices` for the picker, `setVoice(id)` (restarts the engine at the current sentence)), `PlayerHub.kt` (`setVoice(voice: VoiceId)`), `PlayerEngine.kt` (`setVoice`: reopen the engine at `progress.address`)
- Create: `app/src/main/kotlin/app/granger/player/VoiceSheet.kt` (a `ModalBottomSheet`, spec §6: the current sentence in large type with the previous and next dimmed; previous / next paragraph, play/pause, back a sentence; speed (the voice's 0.7–2.0× with presets 0.8, 1.0, 1.2, 1.5); Voice (installed voices, speakers); sleep timer; "Music under the voice" slider and the music row (title, play/pause, next) when music plays; Stop reading; Open)
- Modify: `app/src/main/kotlin/app/granger/GrangerNavHost.kt` (the sheet's host; Open calls `openVoiceSource`; a `text:` source's Open goes to the Research chat)
- Tests: `MiniPlayerTest` (`theSecondLineNamesTheMusicUnderTheVoice`), `VoiceSheetTest` (Compose: sentences, slider shown only with music, Stop calls the hub), `PlayerViewModelTest` (`settingTheVoiceReopensAtTheCurrentSentence`).

- [x] **Step 1: Failing tests** as named. *`VoiceSheetTest` (4), `MiniPlayerTest.theMusicLineNamesTheMusicUnderTheVoice`, `PlayerEngineVoiceTest.setVoiceReopensTheNewEngineAtTheCurrentSentence`.*
- [x] **Step 2:** `./gradlew :app:testDebugUnitTest --tests 'app.granger.player.*'`. Expected: compile failure.
- [x] **Step 3: Implement.** *`VoiceProgress` carries the previous and next sentences (no separate `neighbours()`); `PlayerHub.setVoice` pauses through the carrier, reopens the new engine at the current sentence and plays again the same way. The sheet opens fully expanded (half of it would hide the music row and the buttons); the sleep timer is the existing sheet on top of it. The mini-player's third line is `labelSmall`, inside the same 64 dp.*
- [x] **Step 4:** The same command. Expected: PASS.
- [x] **Step 5: Commit.** `Player UI: the voice's full-view sheet; the music line in the mini-player`

### Task 7: Spoken Research answers

**Files:**
- Modify: `app/src/main/kotlin/app/granger/research/AnswerText.kt` (`spoken(answer: String, medical: Boolean): String`: the segments' text without chips, `#` headings as their own paragraphs, list markers dropped, the medical banner's sentence first when `medical`), `ResearchScreen.kt` (a speaker `IconButton` in `AnswerBlock` for `ANSWERED` turns and for a kept guess: "Read answer" / "Stop reading" by the player's state; a small "Reading…" tint while it is the voice's source), `ResearchViewModel.kt` (`readAnswer(turnId)`, `stopReading()`; with the setting on, `begin()` starts a `StreamingTextReadSource` on the first `Partial` and feeds it, ends it on the outcome)
- Create: `core/speech/src/main/kotlin/app/granger/core/speech/StreamingTextReadSource.kt` (`append(text)`, `complete()`; `utterances()` returns closed sentences and **suspends** while the answer is still being written, returning empty only after `complete()`; the last fragment waits for the end)
- Modify: `app/src/main/kotlin/app/granger/GrangerApp.kt` (`readSources`: `text:answer:<id>` → `chats.turn(id)` → `TextReadSource`), `GrangerNavHost.kt` (`openVoiceSource` for `text:answer:` opens the chat), `settings/VoiceSettingsScreen.kt` + `VoicePrefs` ("Speak answers as they arrive")
- Tests: `AnswerTextTest` (`spokenDropsChipsMarksAndListMarkers`, `medicalBannerIsSpokenFirstOnce`), `StreamingTextReadSourceTest` (`closedSentencesAreServedWhileOpen`, `theLastFragmentWaitsForComplete`, `completeEndsTheSource`), `ResearchViewModelTest` (`readAnswerStartsTheVoiceOnTheTurnsText`, `withTheSettingOnANewAnswerIsReadAsItStreams`, `aStoppedTurnEndsTheRead`), `GrangerAppTest`/`PlayResumerTest` (`anAnswerKeyRestoresFromTheChatsTable`).

- [x] **Step 1: Failing tests** as named. *`StreamingTextReadSourceTest` (3), `AnswerTextTest` +2, `ResearchViewModelTest` +2, the settings fake grew the switch. The restore of a `text:answer:` key runs through `GrangerApp.readSources` and is covered by the walkthrough, not a JVM test.*
- [x] **Step 2:** `./gradlew :core:speech:testDebugUnitTest :app:testDebugUnitTest --tests 'app.granger.research.*'`. Expected: compile failure.
- [x] **Step 3: Implement.** *`ResearchChats.turn(id)` was added for the restore and the sheet's Open (which goes to the chat). The speaker row sits under an answered turn and under a kept guess; while a turn runs it shows only if something is being read. The medical banner text moved to `AnswerText.MEDICAL` so the screen and the voice share it.* The streaming source splits with `Sentences` on the text so far and holds back the last sentence until a sentence break follows it or `complete()` is called. The read starts only when the Research screen is on top (V1's finding: focus is refused from the background), which is where answers stream.
- [x] **Step 4:** The same command. Expected: PASS.
- [x] **Step 5: Commit.** `Research: answers read aloud — the speaker button, Speak answers as they arrive, restore from the chat`

### Task 8: `ReadAloud.start`, the rail's live entry, the widget

**Files:**
- Create: `app/src/main/kotlin/app/granger/voice/ReadAloud.kt`:
  ```kotlin
  sealed interface ReadTarget {
      data class Article(val packId: String, val path: String) : ReadTarget
      data class Book(val bookId: String, val chapterHref: String? = null) : ReadTarget
      data class Answer(val turnId: Long) : ReadTarget
  }
  class ReadAloud(private val container: AppContainer) {
      /** Opens the target's source and starts the voice at [from], else at the chapter or the start. False when nothing could be opened or no voice can speak. */
      suspend fun start(target: ReadTarget, from: ReadAddress? = null, voice: VoiceId? = null): Boolean
  }
  ```
  A chapter: `BookReadSource.addressAt(locator)` from the publication's table of contents by href. The readers' own starts stay as they are (they have the page); `PlayResumer`'s `voice:` path and the Research button go through `ReadAloud`.
- Modify: `app/src/main/kotlin/app/granger/shell/Rail.kt` and its model (a `RailEntry.Reading` at the top of the list while `player.ui.now is Voice`, title "Reading aloud: <title>", a voice icon (new `ic_voice.xml`, a speaking-head glyph); past `voice:` plays use the same icon; tapping opens the page or the chat), `media/PlayResumer.kt` (unchanged contract), `widget/WidgetModel.kt` + the widget's resume (a `voice` tab: resume through `PlayResumer.VOICE`)
- Tests: `ReadAloudTest` (`anArticleStartsAtItsFirstSentence`, `aBookChapterStartsAtTheChaptersFirstSentence`, `anAnswerReadsTheStoredTurn`, `aMissingTargetIsFalse`), `RailTest` (`aLiveReadingHeadsTheListAndOpensItsPage`), `WidgetTest` (`theWidgetResumesTheVoice`).

- [x] **Step 1: Failing tests** as named. *`ReadAloudTest` (3), `RailViewModelTest.aLiveReadingHeadsTheListAndLeavesWithTheVoice`, `WidgetActionsTest` and `WidgetModelTest` +1 each, `BookReadSourceTest.addressAtHrefStartsAChapter`.*
- [x] **Step 2:** `./gradlew :app:testDebugUnitTest --tests 'app.granger.voice.*' --tests 'app.granger.shell.*' --tests 'app.granger.widget.*'`. Expected: compile failure.
- [x] **Step 3: Implement.** *`ReadAloud` takes the hub and the container's `readSources`, so a target is opened exactly as a restore opens it; a book chapter goes through the new `BookReadSource.addressAtHref`. The live reading is a `RailEntry.Reading` combined onto the list (never pinnable, no `at`); `ic_voice` is a speaker glyph, used for past `voice:` plays too. The widget's play with nothing loaded tries the saved voice first through the play resumer, and its idle face shows the saved voice's title.*
- [x] **Step 4:** The same command. Expected: PASS.
- [x] **Step 5: Commit.** `Voice: ReadAloud.start for project 4; the rail's live reading; the widget resumes the voice`

### Task 9: Small debts from V1

**Files:** `core/player/.../voice/ReadAloudEngine.kt` (the `recent` cache keyed by address *and* speed, so a speed change doesn't re-synthesize the sentence being replayed), `app/.../ebook/BookReadSource.kt` (`sectionTitle` from the table of contents when the locator has no title), `GrangerApp.kt` (`readSources` for a book closes the previous restored `Publication` when a different book is restored), tests for each.

- [x] Tests, implement, `testDebugUnitTest` green. *`ReadAloudEngineTest.theCacheIsKeyedBySpeedSoAStepBackAtTheOldSpeedReplaysWithoutSynthesis`, `BookReadSourceTest.sectionTitleComesFromTheTableOfContentsWhenTheLocatorHasNone`; the one-restored-publication rule lives in `GrangerApp.readSources` (`restoredBook`), covered by the walkthrough.* **Commit.** `Voice: V1 debts — speed-keyed cache, section titles from the TOC, one restored publication`

### Task 10: Pixel 6 walkthrough; roadmap

- [ ] Tell the R1 session; back up both databases; install over the app over Wi-Fi.
- [ ] Check (spec §10 and this plan):
  - An article with years and a range (Rosetta Stone: 1799, 196 BC, 1802) reads them as years; the system voice is unchanged.
  - Both catalog voices Get, pick, speak; the speaker picker; licence and attribution shown; removing the chosen voice falls back with a notice.
  - E-book: the bar button pauses and resumes at the sentence; long press stops; the ⋯ menu in the web reader has both.
  - Music playing → Read aloud: music ducks to 30 %, the voice reads; the headset button pauses and resumes the voice, not the music; the notification and lock screen show "Reading · …"; the sheet's slider changes the level; the music row pauses and skips music; a track ends and the next plays under the voice; Stop reading leaves the music at full volume with its own notification; a call pauses both and its end resumes both.
  - Screen off five minutes with music under the voice: no stall (`Voice: synth` RTF ≤ 0.5).
  - The sheet: sentences, speed, voice switch mid-read, sleep timer fades both, Open lands on the page.
  - Research: the speaker button reads an answer without chips or asterisks; with the setting on an answer is read as it streams and a Stop ends it; the medical banner is spoken once; the spoken answer shows in the rail and resumes after a force-stop.
  - The rail's live entry; the widget resumes the voice.
  - No regressions in the music and audiobook parity rows that touch focus, the mini-player and the notification.
- [ ] Update the roadmap: the V2 row to Done with what passed and what's open; "Carried forward from V2" (project 4's needs: `ReadAloud.start` targets it may want beyond these three; the names route; a new-engine spike if a better small voice appears).
- [ ] **Commit.** `Voice V2: a better voice, the mix, the full view, spoken answers; roadmap`

## Order and size

Task 1 (half a day) and Task 2 (Neal's time plus two hours of runs; needs the phone) can run on the same day; Task 3 waits for Task 2's choice. Tasks 4, 5, 6 are the core and go in that order (5 is the largest, about a day; 6 half a day). Tasks 7 and 8 are half a day each; 9 is small; 10 is Neal's walkthrough. About four days of building plus two sessions on the phone. If Task 5's `SessionPlayer` rewrite fights Media3, the fallback (a `ForwardingPlayer` with a custom `MediaNotification.Provider`, the lock screen showing the music) is taken and recorded rather than letting it stall the rest.
