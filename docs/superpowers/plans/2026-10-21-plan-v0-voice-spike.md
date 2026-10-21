# Plan V0: The voice spike — engines on the Pixel 6

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Before the voice is built (spec `docs/superpowers/specs/2026-10-21-voice-design.md`), measure on the Pixel 6 which engines read fast enough, how much memory they take beside a resident E2B, and which one Neal wants to listen to. The spike ends with numbers and decisions in `docs/superpowers/spikes/2026-10-voice-spike-results.md`, not with shipped features. Nothing reaches a release build.

**Architecture:**
- **`:core:speech` (new, minimal):** the vendored sherpa-onnx AAR and a thin `SherpaTts` wrapper over `OfflineTts.generateWithCallback`, built for `arm64-v8a` and `x86_64` only. Production code later grows here; in the spike it is only what the activity needs.
- **`VoiceSpikeActivity` (`app/src/debug`, beside `SpikeActivity` and `ResearchBenchmarkActivity`):** runs a fixed set of passages through each engine, records load time, time to first audio, real-time factor and peak RSS, plays or saves the audio, and logs `Voice:` lines to logcat. Voice files are pushed to the phone, not bundled.
- **Measurement rules follow Plan 1's model spike:** each engine from a force-stopped process, phone on a table, screen on, on power; a cold run and a warm run, decisions on the warm run.

**Tech Stack:** Kotlin, Compose (the debug screen), sherpa-onnx **1.13.8** AAR (Apache-2.0; `com.k2fsa.sherpa.onnx.OfflineTts`), Android `TextToSpeech`, `RssMemorySampler` and `SystemMonoClock` from `:core:ai`, LiteRT-LM 0.18.0 for the E2B-resident runs, adb over Wi-Fi (`tools/dev/phone-wifi.sh`).

**Roadmap:** `docs/superpowers/plans/2026-10-15-granger-v1-roadmap.md`, the four-projects list (project 3) and the V0 row.

## Decisions made with Neal (2026-10-21)

- The voice is project 3 and goes before the Home, Readiness and Onboarding work and before any storage (Slim) work.
- Engines: the system voice as the day-one default, a downloadable Piper voice through sherpa-onnx, Kokoro only if it beats real time on the Pixel 6 and Neal prefers it after listening.
- Spec §11 decisions (2026-10-21): transport controls drive the voice over music; spoken answers off by default; `ljspeech` and `libritts_r` are the catalog voices; the full view is a sheet; Read aloud is in the ⋯ menu; **no phone work until R0 is finished and hands-off.**

## Builder's calls in this plan (for Neal's review)

- Voices measured: Piper `en_US-ljspeech-medium` (public domain) and `en_US-libritts_r-medium` (CC BY 4.0, two of its speakers), and Kokoro `kokoro-multi-lang-v1_1` **int8** (falling back to `kokoro-en-v0_19` if the int8 build is missing from sherpa-onnx's `tts-models` release). Voices with research-only or non-commercial terms (`lessac`, `hfc_*`) are not measured.
- The system engine is measured as found on the Pixel 6 (Google's), with whatever offline voices it has; installing extra voice data is noted, not done.
- Audio is synthesized to WAV files for the listening session and also played through an `AudioTrack` so the mix with music is heard once. No ExoPlayer or player-service changes in the spike.
- Threads: 1, 2 and 4 for sherpa-onnx. The system engine has no thread setting.

## Global Constraints

- **Adopt against these rules, on the warm run:**
  - **Piper is adopted** if, at 2 threads, the real-time factor (synthesis seconds per second of audio) is ≤ 0.5 for every passage, first audio for a sentence arrives in ≤ 1,000 ms, and peak RSS rises by ≤ 250 MB with the engine open.
  - **Kokoro is adopted** only if, at 4 threads **with E2B resident on the GPU**, its real-time factor is ≤ 0.8 and peak RSS stays under 4.5 GB (the phone has 8 GB and the large model also has to fit), and Neal prefers it clearly in the listening session.
  - **The system voice stays** as the fallback whatever the numbers; its first-audio time and offline availability are recorded.
  - If the system voice on the Pixel 6 is offline-capable and Neal rates it as good as Piper, V1 may make the Piper download optional rather than the default. Neal decides in the results.
- The 16 KB page-size check must pass for every `.so` in the AAR used, or the AAR is not adopted until a build passes.
- **The phone is off limits until the Research spike (R0) is finished and hands-off** (Neal, 2026-10-21). Tasks 1 and 3 (code, build, JVM tests) can go ahead; Task 2 and Task 4 wait for Neal to say the phone is free. Then the Plan 9 and R0 rules still apply: never `connectedDebugAndroidTest`; never `adb reboot`; never touch the phone while a personal app is in front; one Gradle build at a time with `-Pkotlin.compiler.execution.strategy=in-process`, then `./gradlew --stop`.
- Downloading voice files from Hugging Face and GitHub releases is fine. Pushing commits or any catalog change needs Neal's OK at that step.
- New worktree setup: copy `local.properties` in, `git submodule update --init --recursive` (taglib), `JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home`, `ANDROID_HOME=/opt/homebrew/share/android-commandlinetools`.

---

### Task 1: `:core:speech` with the vendored AAR

**Files:** create `core/speech/build.gradle.kts`, `core/speech/libs/sherpa-onnx-1.13.8.aar` (plus a `LICENSE-sherpa-onnx` and a `NOTICE` naming the version and URL), `core/speech/src/main/kotlin/app/granger/core/speech/SherpaTts.kt`; modify `settings.gradle.kts`, `app/build.gradle.kts` (debug-only dependency for now: `debugImplementation(project(":core:speech"))`), `gradle/libs.versions.toml` (a `sherpa-onnx` version entry for the record).

- [ ] Download `sherpa-onnx-1.13.8.aar` from the k2-fsa/sherpa-onnx v1.13.8 release into a fresh scratch directory; record its sha256 in the NOTICE. Inspect it: list the ABIs under `jni/` and whether the Kotlin API classes are inside. If the AAR carries ABIs the app doesn't build, note that the packaging filter below keeps only two.
- [ ] `core/speech`: an Android library module, minSdk 29, `ndk { abiFilters += listOf("arm64-v8a", "x86_64") }`, the AAR as a `files()` dependency. If the Kotlin API is not in the AAR, copy `sherpa-onnx/kotlin-api/Tts.kt` (and only it) from the matching tag into the module with its license header, and say so in the NOTICE.
- [ ] `SherpaTts`: opens an `OfflineTts` from a voice directory (`model.onnx`, `tokens.txt`, `espeak-ng-data/`, and `voices.bin` for Kokoro), with `numThreads` and `provider = "cpu"`; `synthesize(text, speed): Flow<FloatArray>` built on `generateWithCallback`, cancellable (the callback returns 0 to stop, else 1; confirm the meaning against the Kotlin source and note it); `sampleRate`; `close()`.
- [ ] **16 KB check:** run `llvm-readelf -l` (from the NDK) on each `.so` the final APK carries and confirm every `LOAD` segment is 16384-aligned; or `zipalign -c -P 16 -v 4 app-debug.apk`. Record the result.
- [ ] Build `./gradlew :app:assembleDebug`. Record the debug APK's size before and after.
- [ ] **Commit.** `Speech: vendor sherpa-onnx 1.13.8 as :core:speech (spike)`

### Task 2: Voice files on the phone

- [ ] From sherpa-onnx's `tts-models` release, download into a fresh scratch directory: `vits-piper-en_US-ljspeech-medium.tar.bz2`, `vits-piper-en_US-libritts_r-medium.tar.bz2`, and `kokoro-int8-multi-lang-v1_1.tar.bz2` (else `kokoro-en-v0_19.tar.bz2`). Record each archive's size, sha256, the unpacked size, and the LICENSE and MODEL_CARD text inside.
- [ ] Push the unpacked directories to `/sdcard/Android/data/app.granger/files/voices/<id>/` over Wi-Fi adb (not into assets). Record the total space used.
- [ ] Check the Pixel 6's system engine: `adb shell settings get secure tts_default_synth`, and the engine's installed voices as the activity lists them in Task 3 (name, locale, `isNetworkConnectionRequired`, quality).

### Task 3: `VoiceSpikeActivity`

**Files:** create `app/src/debug/kotlin/app/granger/voice/VoiceSpikeActivity.kt`, `VoiceSpikeRunner.kt`, `VoiceSpikePassages.kt`; register the activity in the debug manifest; add a button on `SpikeActivity`'s launcher screen (or launch with `adb shell am start -n app.granger/.voice.VoiceSpikeActivity`).

- [ ] **Passages (fixed, in `VoiceSpikePassages`):**
  1. *Article:* about 120 words from a Wikipedia-style paragraph with a date, a place name and a parenthesis (taken from the Ray Charles test ZIM so it is on every phone).
  2. *Book:* Portia's "The quality of mercy" speech (about 190 words, verse with archaic words).
  3. *Answer:* a Research-style answer of four sentences with two numbers, a proper name and a unit ("about 2.6 million km²").
  Each passage is split into sentences with `android.icu.text.BreakIterator` (English) before synthesis, as the app will.
- [ ] **Engines:** `System` (Android `TextToSpeech`, `synthesizeToFile` per sentence to a temp WAV, with `UtteranceProgressListener` timing), `Piper ljspeech`, `Piper libritts_r` (speaker ids 0 and a second one that sounds male; note which), `Kokoro` (one English speaker). Threads 1, 2, 4 for sherpa-onnx.
- [ ] **Measured per engine and thread count, per passage:** load time (ms), time to first audio for the first sentence (ms, from the call to the first callback chunk), synthesis time per sentence and the audio seconds produced, so the real-time factor per sentence and per passage, peak RSS (`RssMemorySampler`) with the engine open, and any error. Logged as `Voice: engine=… threads=… passage=… sentence=… firstAudioMs=… synthMs=… audioMs=… rtf=… rssMb=…` and shown on screen.
- [ ] **With E2B resident:** a toggle loads E2B on the GPU through `LiteRtLlmEngine` (as `SpikeActivity` does), generates a short answer so it is warm, then runs the sherpa-onnx engines again at their best thread count. Record peak RSS and the real-time factor change.
- [ ] **With music:** a toggle plays a local music file through a plain `AudioTrack` or `MediaPlayer` at 30 % while a passage is spoken through a second `AudioTrack` (`CONTENT_TYPE_SPEECH`). Nothing is measured; it confirms the two streams mix and the voice is intelligible over the music.
- [ ] **Audio out:** every synthesized passage is saved as `voice-<engine>-<passage>.wav` under the app's external files dir for pulling; a Play button plays it back on the phone.
- [ ] JVM tests for the pure parts: sentence splitting of the three passages (counts and the first sentence of each), the real-time-factor arithmetic, and the log line format. `./gradlew :app:testDebugUnitTest --tests 'app.granger.voice.*'`.
- [ ] **Commit.** `Voice spike: VoiceSpikeActivity measures the system voice, Piper and Kokoro`

### Task 4: The runs

- [ ] Install over Wi-Fi (`tools/dev/phone-wifi.sh install`), with the library database backed up first, as every install does.
- [ ] For each engine: force-stop the app, open the activity, run the three passages (cold run), then run them again (warm run). Threads 1, 2 and 4 for sherpa-onnx. Then the E2B-resident runs at the best thread count. Then the music mix check once.
- [ ] Pull the logcat `Voice:` lines and the WAV files into `tools/spike/voice/` (the files are small; keep the WAVs out of git if they pass 5 MB in total, and say where they are).
- [ ] Tabulate: one table per engine (passage × threads: first audio, real-time factor, peak RSS) and a summary table against the adoption rules.

### Task 5: The listening session

- [ ] Neal listens to each engine's three passages (the WAVs, or on the phone) and rates each 1–5 on naturalness, names and numbers, and pacing, with one line of notes. Also whether the system voice on the Pixel is good enough to be the default.
- [ ] Record the ratings in the results doc beside the numbers.

### Task 6: Results and decisions

- [ ] Write `docs/superpowers/spikes/2026-10-voice-spike-results.md` in the model spike's shape: device line, method, raw tables (cold and warm), the E2B-resident table, the mix check, the 16 KB and APK-size findings, the voice files' sizes and licenses, Neal's ratings, then **decisions**: which engines V1 ships, the default order, the thread count, the look-ahead the real-time factor allows, and Kokoro's fate.
- [ ] Update the roadmap: the V0 row to Done with the results link, and a "Carried forward from V0" section (what V1 has to carry, e.g. the espeak-ng-data packaging, the pipe-vs-file choice for the system engine).
- [ ] Leave `:core:speech` and the debug activity in place for V1; nothing changes for release users (`Features.READ_ALOUD` stays false, the module is `debugImplementation` until V1).
- [ ] **Commit.** `Voice spike: results and decisions for V1`

## Order and size

Tasks 1 and 3 are a day of agent work and need no phone; Tasks 2 and 4 wait until R0 is finished and hands-off, then take an afternoon; Task 5 is Neal's half hour; Task 6 is an hour. The V1 plan is written once Task 6 is committed.
