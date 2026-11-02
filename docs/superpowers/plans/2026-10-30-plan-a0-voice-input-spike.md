# Plan A0: The voice input spike — the phone's own recognizer on the Pixel 6

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Before voice input is built (roadmap row A1), measure on the Pixel 6 whether the phone's own on-device recognizer is fast, accurate and offline enough for Research questions and library searches, whether biasing with library titles helps, and what it costs beside a resident E2B. The spike ends with numbers and decisions in `docs/superpowers/spikes/2026-10-voice-input-spike-results.md`, not with shipped features. Nothing reaches a release build.

**Architecture:**
- **`VoiceInputSpikeActivity` (`app/src/debug/kotlin/app/granger/voiceinput/`, beside `VoiceSpikeActivity`):** reports what the phone supports, records each test phrase once to a 16 kHz mono WAV, replays the WAVs into the recognizer (with and without biasing strings), runs a live mode for feel and true end-of-speech latency, and logs `Asr:` lines to logcat. No `:core` module yet: the system recognizer needs no library, so production code waits for A1.
- **Replay, not repeat reading:** on API 33+ the recognizer takes audio from a pipe (`RecognizerIntent.EXTRA_AUDIO_SOURCE` with `EXTRA_AUDIO_SOURCE_ENCODING` / `_SAMPLING_RATE` / `_CHANNEL_COUNT`). Neal reads the phrases once; every later run uses the same audio, so with/without biasing and E2B on/off are compared on identical input. Task 2 checks the pipe works with the on-device recognizer before anything depends on it.
- **Measurement rules follow V0:** from a force-stopped process, phone on a table, screen on, on power, **airplane mode on** for every accuracy and latency run; a cold run and a warm run, decisions on the warm run.

**Tech Stack:** Kotlin, Compose (the debug screen), `android.speech.SpeechRecognizer` (`createOnDeviceSpeechRecognizer`, `checkRecognitionSupport`, `triggerModelDownload`), `RecognizerIntent.EXTRA_BIASING_STRINGS`, `AudioRecord`, `RssMemorySampler` and `SystemMonoClock` from `:core:ai`, LiteRT-LM for the E2B-resident runs, adb over Wi-Fi (`tools/dev/phone-wifi.sh`), a small Python scorer in `tools/asr/`.

**Roadmap:** `docs/superpowers/plans/2026-10-15-granger-v1-roadmap.md`, the A1 row.

## What we know already (2026-10-30, read-only checks over adb)

- Pixel 6 (`oriole`), Android 17, API 37, build `CP3A.261005.002.A1`. Every API the plan uses (31+, 33+) is present.
- Recognition services on the phone: Android System Intelligence (`com.google.android.as`, `AiAiSpeechRecognitionService`, the on-device provider) and Speech Services by Google (`com.google.android.tts`, `GoogleTTSRecognitionService`, the phone's default `voice_recognition_service`). The Claude app also registers one; it is not measured.
- Not yet known: whether the English on-device model is installed or only downloadable, and whether `EXTRA_AUDIO_SOURCE` is honoured by the on-device recognizer. Tasks 1 and 2 answer both.

## Builder's calls in this plan (for Neal's review)

- **Only the system recognizer is measured.** The roadmap keeps a sherpa-onnx model as a later fallback; the WAVs recorded here make that a cheap follow-up (Task 6, optional, Neal's call), but nothing is downloaded for it unless Neal says so.
- **Phrases (60, in `tools/asr/phrases.json`):** 25 Research questions taken from the eval set in `tools/eval/dataset` (spoken as a person would ask, not as written), 20 library names and titles chosen to be hard (*Wikivoyage*, *Wiktionary*, *Thucydides*, *Ozymandias*, *Meditations of Marcus Aurelius*, *Gutenberg*, *iFixit*, Stack Exchange site names, music artists from the phone's library), and 15 short searches and commands (*"search Roman roads"*, *"read aloud"*, *"next chapter"*). The biasing list is the titles and names in the phone's library, capped at whatever the recognizer accepts (recorded).
- **Neal reads every phrase once, in one sitting**, in a quiet room, phone in hand at normal distance; a second read of the 20 names in a noisier place (kitchen, music on) is optional.
- **Scoring:** word error rate after lowercasing and stripping punctuation, numbers normalised both ways (*"1066"* / *"ten sixty-six"*); for names, an exact-name hit (the name appears whole in the transcript). Scored on the Mac from the logcat JSON.
- **No `RECORD_AUDIO` in the main manifest.** The permission goes in `app/src/debug/AndroidManifest.xml` only, until A1.

## Global Constraints

- **Adopt against these rules, on the warm run, airplane mode on:**
  - **The on-device recognizer is adopted for A1** if: it works with no network; median time from end of speech to final result is ≤ 800 ms in live mode (p90 ≤ 1,500 ms); word error rate on the Research questions is ≤ 10%; and the exact-name hit rate on the 20 names is ≥ 70% with biasing.
  - **Biasing earns its place** if it raises the name hit rate by ≥ 10 points without raising the question error rate by more than 2 points. Otherwise A1 ships without it.
  - **Beside E2B:** with E2B loaded on the GPU, recognition must not get the app or E2B killed, and median latency must stay within 1.5× the E2B-off figure. Peak system-wide used memory is recorded with `dumpsys meminfo`, since the recognizer runs in another process.
  - If the recognizer fails the accuracy rules but passes on latency and offline, Neal decides between shipping it as-is, holding A1, or doing Task 6 first.
- The phone is shared: the Plan 9 and R0 rules apply. Never `connectedDebugAndroidTest`; never `adb reboot`; never touch the phone while a personal app is in front; one Gradle build at a time with `-Pkotlin.compiler.execution.strategy=in-process`, then `./gradlew --stop`. Only the latest build on the phone, as `app.granger` (no Fresh install).
- Turning airplane mode on and off, and installing the English on-device model through `triggerModelDownload`, are fine. Pushing commits needs Neal's OK at that step.
- Worktree: `.claude/worktrees/a0`, branch `a0-asr-spike`. `JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home`, `ANDROID_HOME=/opt/homebrew/share/android-commandlinetools`.

---

### Task 1: What the phone supports

**Files:** create `app/src/debug/kotlin/app/granger/voiceinput/VoiceInputSpikeActivity.kt`, `RecognizerProbe.kt`; modify `app/src/debug/AndroidManifest.xml` (the activity, `RECORD_AUDIO`, a `<queries>` entry for `android.speech.RecognitionService`), `app/src/debug/kotlin/app/granger/spike/SpikeActivity.kt` (a button to the new screen).

- [x] `RecognizerProbe`: `SpeechRecognizer.isRecognitionAvailable`, `isOnDeviceRecognitionAvailable`; on the on-device recognizer, `checkRecognitionSupport` for `en-US` and `en-GB`, logging `installedOnDeviceLanguages`, `pendingOnDeviceLanguages`, `supportedOnDeviceLanguages` and `onlineLanguages`. One `Asr: probe {...}` JSON line. *Done 2026-10-30: `en-US` installed, `en-GB` downloadable; details in the results doc.*
- [x] If English is supported but not installed: a button that calls `triggerModelDownload` and logs the outcome and how long until `installedOnDeviceLanguages` includes it. Record the download size if the system shows it. *Written (`--es run download`); not run, since `en-US` is installed. Running it for `en-GB` is Neal's call.*
- [x] Find the largest biasing list the recognizer takes: 10, 100, 500, 1,000 strings; record where it errors or silently stops helping (the latter is judged in Task 4). *Done: up to 5,000 accepted. But even three real names didn't fix "Wikivoyage"; Task 4 decides whether biasing helps at all.*
- [x] Build, install, run the probe. Record the results in the results doc.
- [x] **Commit.** `Voice input spike: what the Pixel 6 recognizer supports`

### Task 2: Record once, replay through a pipe

**Files:** create `voiceinput/PhraseRecorder.kt` (`AudioRecord`, 16 kHz mono PCM16 → WAV under `files/asr/<phrase-id>.wav`), `voiceinput/ReplayRecognizer.kt`, `tools/asr/phrases.json`.

- [x] `PhraseRecorder`: shows a phrase, records while a button is held, saves, shows the next. Re-record allowed. Phrases come from `phrases.json` pushed to `files/asr/`. *Done 2026-10-30 as tap Record / tap Stop and next (holding a button tends to clip the first word), from the `VOICE_RECOGNITION` mic source; Back, Skip, Again.*
- [x] `ReplayRecognizer`: writes a WAV's PCM into a `ParcelFileDescriptor.createPipe()` and starts the on-device recognizer with `EXTRA_AUDIO_SOURCE` and the encoding, rate and channel extras, `EXTRA_LANGUAGE = en-US`, `EXTRA_PARTIAL_RESULTS`, optional `EXTRA_BIASING_STRINGS`. Times: start → first partial, end of audio → final result. Logs `Asr: replay {...}` with the transcript and confidences. *Done inside `RecognizerProbe.recognize` (segmented session by default); `--es bias names|library` and `--es only q,n,c` pick the list and the WAVs.*
- [x] **The pipe check:** record three phrases on the Mac with the phone in hand, replay them, and confirm the transcripts are real. If the on-device recognizer ignores the pipe, say so in the results and fall back: Neal reads each phrase live for each condition (Tasks 4 and 5 get shorter: names only, two conditions). *Done early, in Task 1, with macOS `say` WAVs: the pipe works, but only as a **segmented session**, with **1.5 s of trailing silence** and **500 ms between sessions** (results doc, Task 1).*
- [x] **Commit.** `Voice input spike: record phrases once, replay them into the recognizer`

### Task 3: The phrases and Neal's reading

- [x] Write `tools/asr/phrases.json` (ids, kind `question` / `name` / `command`, the text, accepted alternatives for names). Neal looks it over before the reading. *Written: 25 questions (24 with eval ids, plus a new Thucydides one), 20 names from the phone's library said as "Open …", 15 commands. `tools/asr/bias_list.py` builds `bias-library.json` (1,396 strings) and `bias-names.json` from a copy of `library.db`.*
- [x] **Neal's session (about 15 minutes):** read all 60 into `PhraseRecorder`. Pull the WAVs to `tools/asr/audio/` on the Mac (git-ignored; Neal's voice stays off GitHub). *Done 2026-11-02: 60 WAVs on the Mac.*
- [x] `tools/asr/score.py`: reads the logcat JSON, scores word error rate and name hits per condition, prints a table. Two unit tests on the normaliser (numbers, punctuation). *Four tests, passing (`~/.venvs/granger-vectors/bin/python -m pytest tools/asr/tests`). Name hits are scored exact and with spaces ignored.*
- [x] **Commit.** `Voice input spike: phrases and the scorer` *(in 89bd14bb)*

### Task 4: Accuracy and speed, replayed

- [x] Airplane mode on. Replay all 60, warm, in four conditions: no biasing; biasing with the 20 names only; biasing with the whole library list (Task 1's cap); E2B loaded on the GPU with the whole list. Cold run once for the first condition.
- [x] Record per condition: word error rate on questions and commands, name hit rate, median and p90 end-of-audio → final, first partial. During the E2B condition, sample `dumpsys meminfo` and watch logcat for `lowmemorykiller`.
- [x] Write the numbers into the results doc against the adopt rules. *Done 2026-11-02: questions 3.3% WER, names 40–45% (fails), biasing +5 points (fails), E2B no effect. A title matcher after recognition gets 19/20; see the results doc.*
- [x] **Commit.** `Voice input spike: replay results`

### Task 5: Live, for feel

- [ ] A live mode: a mic button, partial text as it arrives, the final text, the end-of-speech → final time (from `onEndOfSpeech` to `onResults`). Earcons and the system's own beep noted.
- [ ] **Neal's session (about 10 minutes):** ask ten Research questions of his own and say five names, airplane mode on. Then the same with music playing through the app (does input duck or stop it, and does the recognizer hear the music?).
- [ ] Record latency, what went wrong, and Neal's verdict on feel: is it quicker than typing?
- [ ] **Commit.** `Voice input spike: live mode`

### Task 6 (optional, Neal's call): a sherpa-onnx reference

- [ ] Only if Neal asks after Task 4. One small English model through the `OfflineRecognizer` already in the vendored AAR (licence checked first), the same WAVs, the same scorer. Answers whether the fallback is worth building, not whether to ship it.

### Task 7: Decisions

- [ ] Results doc: the numbers, the adopt rules met or not, and the questions for Neal: adopt for A1 or not; biasing on or off; where the mic goes (Research ask box, Search, both); what the app does on a phone with no on-device recognizer (hide the mic, or offer the system's online one with a clear note).
- [ ] Neal's decisions recorded; the A1 row in the roadmap updated (the spike's result, then the plan to write).
- [ ] **Commit.** `Voice input spike: results and decisions`
