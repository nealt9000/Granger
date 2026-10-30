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
