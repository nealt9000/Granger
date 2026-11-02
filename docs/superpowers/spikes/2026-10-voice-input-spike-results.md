# Voice input spike (Plan A0): results

Plan: `docs/superpowers/plans/2026-10-30-plan-a0-voice-input-spike.md`. Phone: Pixel 6 (`oriole`), Android 17, API 37, build `CP3A.261005.002.A1`. Screen: `VoiceInputSpikeActivity` (debug only). Every run below was in airplane mode.

## Task 1: what the phone supports (2026-10-30)

**Services.** `isRecognitionAvailable` and `isOnDeviceRecognitionAvailable` are both true. Three recognition services: Android System Intelligence (`com.google.android.as/…AiAiSpeechRecognitionService`, the on-device one, running Google's SODA engine), Speech Services by Google (`com.google.android.tts/…GoogleTTSRecognitionService`, the default), and the Claude app's.

**Languages on-device.** Installed: `en-US`, `es-ES`. Supported, so downloadable: 35 more, including `en-GB`, `en-AU`, `en-IN`, `en-CA`, `en-IE`, `en-SG`. Pending: none. `onlineLanguages` comes back empty from the on-device recognizer. US English is already there, so A1 needs no download for it. UK English needs `triggerModelDownload` (not run yet: the size is unknown, and whether to offer `en-GB` is Neal's call).

**Feeding audio through a pipe works** (`EXTRA_AUDIO_SOURCE`, 16 kHz mono PCM16, paced at real time). Three phrases spoken by macOS `say -v Daniel`. Four things learned getting there:

1. **A plain session never fills `onResults`.** The text arrives only in the partials; the final bundle is empty, even with partials off. The service logs a final hypothesis and then "onResults already finished". **A segmented session** (`EXTRA_SEGMENTED_SESSION = EXTRA_AUDIO_SOURCE`) gives proper results through `onSegmentResults`, ending with `onEndOfSegmentedSession` about 25–45 ms after the pipe closes. Replays use segmented sessions from now on.
2. **Silence after the audio matters.** With 300 ms of trailing silence the recognizer never detected the end of speech; with 1.5 s it does, about 0.75 s after the words stop. The replay default is now 1.5 s.
3. **Recognizers opened back to back** fail about every other time with `SERVER_DISCONNECTED` (the previous service instance is still shutting down). A 500 ms gap between runs fixed it: no errors and no retries in 20 runs.
4. **The partials are quick:** the first word arrives about 0.45 s after speech starts.

| Phrase (as spoken) | Transcript (identical across all runs) |
|---|---|
| What were the main causes of the fall of the Roman Empire? | What were the main **courses** of the fall of the Roman Empire |
| Open Meditations by Marcus Aurelius | Open meditations by Marcus Aurelius |
| Search Wikivoyage for Lisbon | Search **with the Voyage** for Lisbon |

That's 2 word errors in 24 (8%) on a synthetic British voice, a rough sign only. Neal's own voice is the real test (Task 3).

**Biasing.** Lists of 3, 10, 100, 500, 1,000 and 5,000 strings are all accepted (the service logs "Adding phrases biasing…"), with no error and no measurable slowdown. But **even a three-string list that includes "Wikivoyage" did not fix "with the Voyage"**, in plain or segmented sessions. Whether biasing helps at all on this recognizer is still open: Task 4 tests it on Neal's 20 names. If it doesn't, A1 can correct names after recognition against the library's titles instead.

**Latency, note for the adopt rule.** The rule measures end of speech → final result. In practice what a person feels also includes the endpointer's wait (about 0.75 s of silence here) before end of speech is declared. Task 5 measures both; a mic that stops on tap avoids the wait.

## Task 3: Neal's reading (2026-11-02)

All 60 phrases read once into `PhraseRecorder` (quiet room, phone in hand): 25 questions, 20 names said as "Open …", 15 commands. 16 kHz mono, 1.6–6.8 s each (median 3.3 s), eight re-takes. The WAVs are on the Mac in `tools/asr/audio/` (git-ignored). The recorder writes to the app's **external** files folder (`/sdcard/Android/data/app.granger/files/asr/`), not internal `files/`.

## Task 4: accuracy and speed, replayed (2026-11-02)

Airplane mode on, phone on a table and on power. All 60 WAVs replayed through the on-device recognizer (`en-US`, segmented session, 1.5 s trailing silence, 500 ms between sessions). Android System Intelligence was force-stopped before the cold run. Each condition starts from a force-stopped `app.granger`. No errors or retries in 300 recognitions. Logs: `tools/asr/out/task4/` (git-ignored).

| Condition | WER questions | WER commands | Names, exact | Names, spaces ignored | Final segment after audio ends, median / p90 | After end of speech, median / p90 |
|---|---|---|---|---|---|---|
| No biasing, cold | 3.3% (7/211) | 3.4% (2/58) | 8/20 (40%) | 10/20 | 256 / 640 ms | 202 / 290 ms |
| No biasing, warm | 3.3% | 3.4% | 8/20 (40%) | 10/20 | 255 / 551 ms | 195 / 290 ms |
| Bias: the 20 names | 3.3% | 3.4% | 8/20 (40%) | 10/20 | 249 / 540 ms | 205 / 296 ms |
| Bias: whole library (1,396) | 3.3% | 3.4% | 9/20 (45%) | 11/20 | 351 / 613 ms | 277 / 358 ms |
| Bias: whole library, E2B on GPU | 3.3% | 3.4% | 9/20 (45%) | 11/20 | 254 / 502 ms | 203 / 325 ms |

*"Final segment after audio ends"* is from the end of Neal's recorded audio (which includes his own pause before tapping Stop) to the first final segment. *"After end of speech"* is from the recognizer's `onEndOfSpeech` to that segment. The scorer's own "final after audio end" (30–55 ms) is timed after the added 1.5 s of silence, so it is not a user-facing figure. Task 5 measures live latency.

**The question errors are the same in every condition:** *Jon Stewart Mill*, *Thorough* (Thoreau), *thucyides*, *What is bhagavad Gita*, *the granny knot*. Commands: two small slips.

**The name misses** (no biasing): *the domapada* (Dhammapada), *Credo* (Crito), *the metaphysics of morals*, *Castor bridge*, *Hector berleos*, *fiorio* (Fiorillo), *Marcus aurulius*, plus the ones fixed only by ignoring spaces. The whole-library list fixed *Marcus Aurelius* and broke *Toned Ear* (→ *tone deer*). The names-only list changed no exact hits.

**Matching against library titles after recognition** (a quick check: `difflib` closest match of the transcript, minus "Open", against the 1,396 library strings): **19/20 correct, with or without biasing.** The only miss is *Credo* → "Ready.gov" instead of *Crito*. So the right place to fix names is a title matcher after recognition, not biasing. The check is generous in one way: every spoken name is in the list it is matched against, as it would be in the app.

**Cold start.** After Android System Intelligence is force-stopped, the first recognition took **16.6 s** to return. Everything after that was normal. A1 should allow for a slow first use (a "Listening…" state that holds, no timeout under about 20 s), or warm the recognizer when the ask box opens.

**Beside E2B** (loaded on the GPU and warmed before the replay: 27 s load, 2.1 GB RSS). Recognition was unaffected: same transcripts, latency as without (median 254 vs 255 ms, well within the 1.5× rule). System used RAM peaked at 6.1 GB of 7.8 GB during the replay (40 `dumpsys meminfo` samples). The low-memory killer killed 13 cached background apps (adj 975–995), **all during the E2B load**, none during recognition. Neither `app.granger` nor the recognizer was killed.

### Against the adopt rules (warm)

| Rule | Result | |
|---|---|---|
| Works with no network | Yes, airplane mode for every run | ✅ |
| End of speech → final, median ≤ 800 ms, p90 ≤ 1,500 ms | 195 / 290 ms replayed; live mode (Task 5) to confirm | ✅ (provisional) |
| WER on Research questions ≤ 10% | 3.3% | ✅ |
| Exact-name hits ≥ 70% with biasing | 45% (library list) | ❌ |
| Biasing: name hits +10 points, questions ≤ +2 | +5 points (library), +0 (names); questions unchanged | ❌, A1 ships without biasing |
| Beside E2B: nothing killed, latency ≤ 1.5× | Nothing of ours killed; 1.0× | ✅ |

**The name rule fails, but the plan's fallback applies:** Neal chooses between shipping as is, holding A1, or Task 6 (sherpa-onnx). The builder's suggestion is to **ship the system recognizer without biasing, plus a title matcher after recognition**: 19/20 in the quick check, no new model and no download. Task 6 would only make sense if the matcher falls short in live use.
