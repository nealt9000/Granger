# Voice Spike Results — Pixel 6

Date: 2026-10-21 · Device line: Google Pixel 6 · Android 17 · SoC Tensor · totalMem 7974752256 B · tier THINK_DEEPER · sherpa-onnx 1.13.8 (static-link AAR, CPU provider) · Android `TextToSpeech` with `com.google.android.tts` · LiteRT-LM 0.18.0 for the E2B-resident runs (`gemma-4-E2B-it-gpu.litertlm`)

Method: `VoiceSpikeActivity` (debug build, commit d3b533f plus the two fixes below), launched by adb with intent extras (`tools/spike/voice/run-voice.sh`), each launch from a force-stopped process. Pass 1 = cold (fresh process), pass 2 = warm (same process, engine loaded again). Three fixed passages (`VoiceSpikePassages`: a Wikipedia lead, Portia's mercy speech, a Research-style answer), split by ICU into 6, 9 and 4 sentences, synthesized one sentence at a time with `maxNumSentences = 1`. Threads 1, 2 and 4 for sherpa-onnx; speaker 0 everywhere (plus libritts_r speakers 1 and 2 for listening only); Kokoro's English speaker 0 with `lang=en-us`. Phone on a table, unplugged (battery 74 % → 60 %, 37–40 °C), screen kept on by the activity, runs back-to-back with no cool-down. First audio is measured from the `generateWithCallback` call to the first callback, which sherpa-onnx sends once per sentence, so for one sentence it equals the sentence's synthesis time. Peak RSS is `RssMemorySampler` over the process (the activity alone sits at about 310–350 MB). Logs: `tools/spike/voice/*.log` (logcat `Voice:` lines; the tables come from `tabulate.py`). Audio: 36 WAVs, 69 MB, outside git at `/Users/s.nealthompson/Developer/Granger/voice-spike-2026-10-21/wav/` with the activity's own on-phone logs beside them.

## Summary against the adoption rules

| Rule (plan V0) | Piper ljspeech | Piper libritts_r | Kokoro int8 | System (Google TTS) |
| --- | --- | --- | --- | --- |
| Real-time factor ≤ 0.5 at 2 threads, every passage | **0.11–0.12** ✓ | **0.12–0.14** ✓ | 1.8–1.9 ✗ | 0.06 |
| First audio ≤ 1,000 ms per sentence | 250–400 ms for short sentences; **up to 1,619 ms** for the long ones (✗ above about 140 characters) | same shape, up to 1,453 ms | 4.5–19 s ✗ | 85–573 ms ✓ |
| Peak RSS rise ≤ 250 MB with the engine open | +50–90 MB loaded and idle (≈ 400 MB); **+420 MB while synthesizing** (peak 770–790 MB) ✗ as written | same (peak 720–765 MB) | +540 MB (peak 890 MB) | none in-process (engine runs in its own process) |
| With E2B resident on the GPU (2 threads) | RTF 0.12–0.15, peak 2.3 GB ✓ | RTF 0.14–0.15, peak 2.25 GB ✓ | not run (fails without E2B) | — |
| Kokoro: RTF ≤ 0.8 at 4 threads with E2B | — | — | 2.1–2.2 without E2B ✗ | — |

Read: both Piper voices synthesize about eight times faster than real time at two threads; four threads add nothing on this SoC. Kokoro is one and a half to two times *slower* than real time and gets slower with more threads, so it is out for this device class. The system engine is the fastest and lightest and has 29 offline English voices. The two rules the Piper voices miss are explained below and are design matters for V1, not blockers.

## Raw results

Columns: load ms (engine construction), first audio of sentence 0 (ms), the slowest first audio in the passage (ms), the passage's real-time factor (synthesis ms ÷ audio ms), peak RSS (MB). "E2B yes" rows ran with the GPU E2B loaded and warm in the same process.

### Piper `vits-piper-en_US-ljspeech-medium`

| threads | pass | E2B | passage | load ms | first audio s0 ms | first audio max ms | RTF | peak RSS MB |
|---|---|---|---|---|---|---|---|---|
| 1 | cold |  | article | 2260 | 2461 | 2461 | 0.18 | 723 |
| 1 | cold |  | book | 2260 | 370 | 1786 | 0.18 | 755 |
| 1 | cold |  | answer | 2260 | 2111 | 2111 | 0.19 | 740 |
| 1 | warm |  | article | 1628 | 2494 | 2494 | 0.18 | 735 |
| 1 | warm |  | book | 1628 | 414 | 1872 | 0.19 | 748 |
| 1 | warm |  | answer | 1628 | 2068 | 2068 | 0.18 | 745 |
| 2 | cold |  | article | 2067 | 1529 | 1529 | 0.11 | 741 |
| 2 | cold |  | book | 2067 | 284 | 1120 | 0.11 | 772 |
| 2 | cold |  | answer | 2067 | 1290 | 1290 | 0.12 | 790 |
| 2 | warm |  | article | 1630 | 1619 | 1619 | 0.12 | 756 |
| 2 | warm |  | book | 1630 | 293 | 1210 | 0.12 | 762 |
| 2 | warm |  | answer | 1630 | 1313 | 1313 | 0.12 | 769 |
| 4 | cold |  | article | 2319 | 1364 | 1364 | 0.10 | 740 |
| 4 | cold |  | book | 2319 | 311 | 1081 | 0.11 | 773 |
| 4 | cold |  | answer | 2319 | 1209 | 1209 | 0.11 | 800 |
| 4 | warm |  | article | 1904 | 1592 | 1592 | 0.11 | 736 |
| 4 | warm |  | book | 1904 | 318 | 1175 | 0.12 | 761 |
| 4 | warm |  | answer | 1904 | 1469 | 1469 | 0.13 | 768 |
| 2 | cold | yes | article | 1889 | 1454 | 1454 | 0.11 | 2263 |
| 2 | cold | yes | book | 1889 | 326 | 1205 | 0.12 | 2288 |
| 2 | cold | yes | answer | 1889 | 1348 | 1348 | 0.12 | 2296 |
| 2 | warm | yes | article | 2189 | 1654 | 1654 | 0.12 | 2266 |
| 2 | warm | yes | book | 2189 | 362 | 1312 | 0.14 | 2288 |
| 2 | warm | yes | answer | 2189 | 1617 | 1617 | 0.15 | 2301 |
| 2 | cold, music at 30 % |  | article | 3001 | 2321 | 2321 | 0.18 | 744 |
| 2 | cold, music at 30 % |  | book | 3001 | 467 | 1626 | 0.17 | 755 |
| 2 | cold, music at 30 % |  | answer | 3001 | 1969 | 1969 | 0.18 | 742 |

### Piper `vits-piper-en_US-libritts_r-medium` (speaker 0)

| threads | pass | E2B | passage | load ms | first audio s0 ms | first audio max ms | RTF | peak RSS MB |
|---|---|---|---|---|---|---|---|---|
| 1 | cold |  | article | 1537 | 2583 | 2583 | 0.19 | 705 |
| 1 | cold |  | book | 1537 | 361 | 1679 | 0.20 | 726 |
| 1 | cold |  | answer | 1537 | 2082 | 2082 | 0.21 | 730 |
| 1 | warm |  | article | 1293 | 2977 | 2977 | 0.25 | 701 |
| 1 | warm |  | book | 1293 | 456 | 1936 | 0.23 | 723 |
| 1 | warm |  | answer | 1293 | 2143 | 2143 | 0.20 | 735 |
| 2 | cold |  | article | 1574 | 1344 | 1344 | 0.12 | 712 |
| 2 | cold |  | book | 1574 | 249 | 970 | 0.12 | 735 |
| 2 | cold |  | answer | 1574 | 1157 | 1157 | 0.12 | 748 |
| 2 | warm |  | article | 1169 | 1453 | 1453 | 0.12 | 699 |
| 2 | warm |  | book | 1169 | 331 | 1054 | 0.13 | 728 |
| 2 | warm |  | answer | 1169 | 1373 | 1373 | 0.14 | 718 |
| 4 | cold |  | article | 1795 | 1240 | 1240 | 0.10 | 719 |
| 4 | cold |  | book | 1795 | 255 | 862 | 0.11 | 749 |
| 4 | cold |  | answer | 1795 | 1150 | 1150 | 0.12 | 766 |
| 4 | warm |  | article | 1487 | 1493 | 1493 | 0.12 | 747 |
| 4 | warm |  | book | 1487 | 305 | 1034 | 0.13 | 747 |
| 4 | warm |  | answer | 1487 | 1322 | 1322 | 0.13 | 765 |
| 2 | cold | yes | article | 1436 | 1874 | 1874 | 0.15 | 2171 |
| 2 | cold | yes | book | 1436 | 317 | 1245 | 0.15 | 2218 |
| 2 | cold | yes | answer | 1436 | 1652 | 1652 | 0.15 | 2237 |
| 2 | warm | yes | article | 1420 | 1684 | 1684 | 0.15 | 2176 |
| 2 | warm | yes | book | 1420 | 330 | 1353 | 0.15 | 2250 |
| 2 | warm | yes | answer | 1420 | 1495 | 1495 | 0.14 | 2232 |

Speakers 1 and 2 (2 threads, cold, for the listening session only): RTF 0.16–0.18, first audio up to 2,054 ms and 1,715 ms, peak RSS 755 MB and 690 MB. Which of the 904 speakers sound male is for Neal's ears; nothing here tells.

### Kokoro `kokoro-int8-multi-lang-v1_1` (English speaker 0)

| threads | pass | E2B | passage | load ms | first audio s0 ms | first audio max ms | RTF | peak RSS MB |
|---|---|---|---|---|---|---|---|---|
| 1 | cold |  | article | 5445 | 32780 | 32780 | 1.82 | 874 |
| 1 | cold |  | book | 5445 | 4296 | 14222 | 1.47 | 884 |
| 1 | cold |  | answer | 5445 | 14991 | 14991 | 1.52 | 895 |
| 1 | warm |  | article | 2577 | 18946 | 18946 | 1.54 | 880 |
| 1 | warm |  | book | 2577 | 4621 | 14642 | 1.54 | 890 |
| 1 | warm |  | answer | 2577 | 15791 | 15791 | 1.59 | 896 |
| 2 | cold |  | article | — | — | 17077 | 1.76 | 872 |
| 2 | cold |  | book | — | — | 14432 | 1.88 | 880 |
| 2 | cold |  | answer | — | — | 14930 | 1.81 | 886 |
| 2 | warm |  | article | 3065 | 18986 | 18986 | 1.80 | 886 |
| 2 | warm |  | book | 3065 | 4637 | 14662 | 1.89 | 889 |
| 2 | warm |  | answer | 3065 | 15652 | 15652 | 1.86 | 888 |
| 4 | cold |  | article | — | — | 22295 | 2.06 | 874 |
| 4 | cold |  | book | — | — | 17152 | 2.24 | 882 |
| 4 | cold |  | answer | — | — | 17969 | 2.12 | 886 |
| 4 | warm |  | article | 3940 | 22380 | 22380 | 2.09 | 883 |
| 4 | warm |  | book | 3940 | 5230 | 17360 | 2.25 | 888 |
| 4 | warm |  | answer | 3940 | 17353 | 17353 | 2.12 | 893 |

The dashes are lines logcat's ring buffer dropped during these eight-minute runs (the activity's own log files on the phone have them); the passage totals are complete. The audio Kokoro produced is shorter than Piper's for the same text (120 s against 150 s for the three passages), which does not change the verdict.

### System engine (`com.google.android.tts`, voice `en-US-language`, via `synthesizeToFile`)

| threads | pass | E2B | passage | load ms | first audio s0 ms | first audio max ms | RTF | peak RSS MB |
|---|---|---|---|---|---|---|---|---|
| 1 | cold |  | article | 2162 | 573 | 573 | 0.06 | 315 |
| 1 | cold |  | book | 2162 | 136 | 250 | 0.06 | 335 |
| 1 | cold |  | answer | 2162 | 213 | 246 | 0.06 | 345 |
| 1 | warm |  | article | 1488 | 539 | 539 | 0.06 | 351 |
| 1 | warm |  | book | 1488 | 172 | 270 | 0.06 | 358 |
| 1 | warm |  | answer | 1488 | 220 | 255 | 0.06 | 306 |

`tts_default_synth` is unset on the Pixel, so the platform default applies: Google's engine, the only one installed. It reports 474 voices, 29 of them offline English (en-US, en-GB, en-AU, en-IN, en-NG; all quality 400, latency 200), default `en-US-language`. `onAudioAvailable` fires with this engine, so first audio is real; the file round-trip (`synthesizeToFile` to the cache dir, then `Wav.read`) worked for every sentence, and the 44-byte header plus 16-bit mono PCM at 24,000 Hz parsed as expected.

### Warm totals per thread count (three passages pooled)

| engine | threads | E2B | synth ms | audio ms | RTF | first audio max ms | peak RSS MB |
|---|---|---|---|---|---|---|---|
| ljspeech | 1 |  | 27703 | 151433 | 0.18 | 2494 | 748 |
| ljspeech | 2 |  | 17828 | 149078 | 0.12 | 1619 | 769 |
| ljspeech | 2 | yes | 20488 | 150893 | 0.14 | 1654 | 2301 |
| ljspeech | 4 |  | 18335 | 151014 | 0.12 | 1592 | 768 |
| libritts_r | 1 |  | 29315 | 127630 | 0.23 | 2977 | 735 |
| libritts_r | 2 |  | 15969 | 126169 | 0.13 | 1453 | 728 |
| libritts_r | 2 | yes | 18608 | 127557 | 0.15 | 1684 | 2250 |
| libritts_r | 4 |  | 16128 | 125570 | 0.13 | 1493 | 765 |
| kokoro | 1 |  | 224337 | 144625 | 1.55 | 18946 | 896 |
| kokoro | 2 |  | 221496 | 119918 | 1.85 | 18986 | 889 |
| kokoro | 4 |  | 258581 | 119974 | 2.16 | 22380 | 893 |
| system | 1 |  | 7989 | 131405 | 0.06 | 539 | 358 |

## What the numbers mean

**First audio is the first sentence's length.** sherpa-onnx returns a sentence in one piece, so time to first audio is `0.12 × the sentence's audio seconds + about 100 ms` at two threads. Sentences up to about 140 characters (eight seconds of speech) start inside a second; the 190- and 202-character sentences in the article take 1.5–1.6 s. Once the first sentence is playing, the next one is ready long before it is needed (a ten-second sentence takes 1.2 s to make), so a look-ahead of **one sentence** keeps the voice continuous with margin to spare, and two sentences cover a slow moment. For the first sentence of a read, V1 can split at the first clause boundary after about 60 characters so speech starts in roughly half a second, or accept up to 1.6 s on long openers; that is V1's call.

**Memory is ONNX Runtime's arena, not the model.** With the engine open and idle the process grows by 50–90 MB (the 63 MB model is memory-mapped). During synthesis the arena grows to about +420 MB and stays there until the engine is freed. With E2B resident on the GPU the whole process peaks at 2.3 GB, well inside the 8 GB phone and under the 4.5 GB ceiling the plan set for Kokoro. On a 4 GB phone a voice beside a model is tighter; V1 should treat a downloaded voice like a model for the device-tier gate, and can try freeing the engine after a pause rather than keeping it open.

**Two threads is the knee.** One thread costs 50 % more time, four threads give nothing (the Pixel 6 has two big cores). E2B resident on the GPU costs the Piper voices 15–25 % (RTF 0.12 → 0.14–0.15), which is noise at this speed. Music decoding under the voice through `MediaPlayer` cost more than E2B did (RTF 0.11 → 0.17–0.18), which is the first hint that the voice should run at a lower priority than playback and still has four times the headroom it needs.

**Kokoro does not fit this phone.** 1.5–2.2 times slower than real time on the CPU, slower with more threads (the int8 kernels on the mixed cores spin rather than scale), 4.5–33 s to the first sentence and 890 MB. The plan's condition (RTF ≤ 0.8 with E2B at four threads) is missed by a factor of three without E2B, so the E2B run was not made. It would need a different runtime (a GPU or NNAPI provider, or a float16 build) to be worth measuring again, and the device tier would have to carry it.

## Two things that went wrong first, and what V1 inherits from them

1. **The callback crash.** The first sherpa run died with `SIGABRT`: `JNI NewFloatArray called with pending exception java.lang.NoSuchMethodError … SherpaTts$synthesize$1$1$$ExternalSyntheticLambda`. sherpa's JNI finds the Kotlin callback by reflection as `invoke([F)Ljava/lang/Integer;`; Kotlin 2 compiles lambdas through `invokedynamic`, and the class D8 synthesizes has only the erased `invoke(Object)`. `SherpaTts` now passes an object expression implementing `(FloatArray) -> Int`, which compiles to a class with the specialized method. Any future callback handed to sherpa-onnx must do the same (or the module must compile with `-Xlambdas=class`).
2. **The screen-off cliff.** The first batch ran with the screen going dark two minutes in, and every number was five to ten times worse: ljspeech at RTF 0.6–1.7, libritts_r 1.2–2.3, Kokoro 5–11, runs timing out at 25 minutes. With the screen off and no foreground service the process is scheduled as background, on the little cores. Waking the screen was not enough, because the keyguard hid the activity. The activity now sets `setShowWhenLocked`, `setTurnScreenOn` and `FLAG_KEEP_SCREEN_ON`, and the batch was rerun from scratch; the screen-off logs are kept outside the repo for reference. **This is the main thing V1 must measure:** read-aloud in real use runs with the screen off, so the voice has to synthesize under `PlaybackService`'s foreground service and be measured there. If the foreground service does not lift the restriction, the look-ahead must grow from one sentence to a paragraph, and Piper may need the whole chapter made ahead while the screen is on.

## Build facts (from Tasks 1 and 2)

- `:core:speech` vendors `sherpa-onnx-1.13.8-static-arm64-x86_64.aar` (19,196,128 B in git): the static-link release AAR trimmed to arm64-v8a and x86_64, one `libsherpa-onnx-jni.so` per ABI, `NEEDED` only libandroid, liblog, libm, libdl, libc; LOAD segments 0x4000-aligned, so the 16 KB page requirement holds. The debug APK grew from 394,901,573 to 446,888,358 B (+52 MB, both ABIs uncompressed); a Play bundle serves one ABI, about 24 MB.
- Voice files on the phone, 379 MB under `files/voices/`: `vits-piper-en_US-ljspeech-medium` 79 MB (model 63.5 MB, `espeak-ng-data` 18 MB; public domain, trained from scratch); `vits-piper-en_US-libritts_r-medium` 99 MB (904 speakers; dataset CC BY 4.0 but fine-tuned from `lessac`, whose Blizzard 2013 data is non-commercial only, so not shippable as it stands); `kokoro-int8-multi-lang-v1_1` 210 MB (Apache-2.0). Each Piper voice carries its own 18 MB copy of `espeak-ng-data`; V1 can ship one shared copy.
- The music file for the mix check was a Holst track copied on the phone itself to `files/voices/music.mp3` (4,690,845 B).

## Listening session (Task 5, Neal)

The WAVs are at `/Users/s.nealthompson/Developer/Granger/voice-spike-2026-10-21/wav/` (one per engine, thread count and passage; the `t2` files are the ones to judge) and on the phone under `files/voice-spike/`, where the activity's Play buttons play them, with "Music under the voice" for the mix. The music is not in the WAVs; the mix is heard on the phone only.

| Engine | Naturalness 1–5 | Names and numbers 1–5 | Pacing 1–5 | Notes |
| --- | --- | --- | --- | --- |
| System (`en-US-language`) |2 | 4 | 4 | good enough, not great |
| Piper ljspeech | 4 | 2 | 5 | pretty good, but all said 'rossetter stone' in the article test |
| Piper libritts_r speaker 0 | 5 | 3 | 2 | seems to take pauses a numbers, best was t4 |
| Piper libritts_r speakers 1, 2 | 4 | 3 | 2 | 2nd voice is Male |
| Kokoro (English speaker 0) | 2 | 3 | 3 | for reference only; it is out on speed; also had high-pitched noise. |

What the listening adds to the numbers (Neal, 2026-10-21):
- **The voice people will want is a Piper voice, not the system one.** The system engine is fast and light but rated 2 for naturalness; ljspeech rated 4 and libritts_r 5. So the downloadable voice is the product, and the system voice is the fallback, which is the spec's order already.
- **Names are Piper's weak spot** (ljspeech 2, libritts_r 3). "Rosetta" came out as "rossetter" from every Piper voice, which points at the phonemizer they share rather than a model: both voices use espeak-ng phonemes (`phoneme_type: espeak` in their model cards). V1 needs a pronunciation path: espeak-ng's extra-dictionary mechanism compiled into the shipped `espeak-ng-data`, or a word-level override applied before synthesis, seeded from the mistakes the readers hit. The system engine scored 4 here, so it stays the reference for names.
- **Numbers make libritts_r pause** (pacing 2) where ljspeech flows (pacing 5); Neal found the 4-thread libritts_r file best, which the numbers do not explain (the audio is the same model at the same speed), so the difference is between takes rather than threads. Read-aloud of Research answers, which are full of numbers, favours ljspeech's pacing.
- **libritts_r is the best-sounding voice and the one we cannot ship.** Speaker 2 is male, which the catalog wants for a second voice. V1's licence search should look for a from-scratch or permissively trained voice of similar quality, and it is worth asking whether a Lessac-free LibriTTS-R training exists or can be made; LibriTTS-R itself is CC BY 4.0.
- **Kokoro's high-pitched noise** on top of its speed confirms the drop; the int8 build may be the cause of the noise, which does not matter now.

## Decisions (proposed from the numbers; confirmed by Neal after the listening session, 2026-10-21)

1. **V1 ships two engines:** the system `TextToSpeech` as the default, and Piper through sherpa-onnx as the downloadable voice. Both meet the speed bar with a wide margin on the Pixel 6.
2. **Kokoro is dropped** for this device class. It returns only with a faster runtime or a bigger phone, as its own spike.
3. **Threads: 2**, fixed. Four buys nothing; one costs 50 %.
4. **Look-ahead: one sentence**, two when memory allows, with an optional clause split for the first sentence of a read. First audio under a second for ordinary sentences, up to 1.6 s for long openers.
5. **Memory budget:** +450 MB while a Piper voice is speaking, 2.3 GB with E2B resident. Gate voice downloads by device tier like models; consider freeing the engine after a long pause.
6. **The catalog voice is `ljspeech`** (public domain). `libritts_r` is measured but not shippable; the second voice is chosen in V1 after a licence check, from voices trained from scratch or on permissive data.
7. **V1's acceptance includes a screen-off run** under the foreground service, on the same three passages, before read-aloud is called done.
